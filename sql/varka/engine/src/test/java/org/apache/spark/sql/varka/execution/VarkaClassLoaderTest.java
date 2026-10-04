/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.varka.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/**
 * Validates {@link VarkaClassLoader} (VARKA-3): define/instantiate semantics, the
 * registry behind {@code loadClass}, the release lifecycle, and - the core Metaspace
 * guarantee - that a released loader whose references are dropped is collected by the
 * JVM so its generated classes unload.
 *
 * <p>Test classes are generated on the fly with the Class-File API
 * ({@code java.lang.classfile}). Each generated class has a no-arg constructor, a
 * {@code String hello()} returning a constant, and a {@code long now()} calling
 * {@code System.currentTimeMillis()} (exercises resolution through the parent loader).
 */
public class VarkaClassLoaderTest {

  private static final Logger log = Logger.getLogger(VarkaClassLoaderTest.class.getName());

  private static final String GEN_PACKAGE = "org.apache.spark.sql.varka.gen";

  /** Total budget (ms) for GC-retry loops; generous to stay robust on loaded JVMs. */
  private static final long GC_TIMEOUT_MS = 10000;

  @Test
  void defineAndInstantiate() throws Exception {
    String name = className("Hello");
    VarkaClassLoader loader = newLoader();
    try {
      Class<?> clazz = loader.defineGeneratedClass(name, generatedClass(name));

      assertSame(clazz, loader.loadClass(name));
      assertSame(loader, clazz.getClassLoader());
      assertFalse(loader.isReleased());

      Object instance = clazz.getConstructor().newInstance();
      assertEquals("hello", clazz.getMethod("hello").invoke(instance));
      assertTrue((Long) clazz.getMethod("now").invoke(instance) > 0);
    } finally {
      loader.release();
    }
  }

  @Test
  void unloadabilityAfterRelease() throws Exception {
    String name = className("Unload");
    ClassLoader parent = Thread.currentThread().getContextClassLoader();
    ReferenceQueue<ClassLoader> queue = new ReferenceQueue<>();
    VarkaClassLoader loader = new VarkaClassLoader(parent);
    WeakReference<ClassLoader> ref = new WeakReference<>(loader, queue);
    Class<?> clazz = loader.defineGeneratedClass(name, generatedClass(name));
    Object instance = clazz.getConstructor().newInstance();

    loader.release();
    assertTrue(loader.isReleased());

    loader = null;
    clazz = null;
    instance = null;

    assertTrue(awaitCollected(ref, queue),
        "released loader must be collected so its classes unload from Metaspace");
  }

  @Test
  void batchStressAllLoadersCollected() {
    int count = 1000;
    ClassLoader parent = Thread.currentThread().getContextClassLoader();
    ReferenceQueue<ClassLoader> queue = new ReferenceQueue<>();
    List<WeakReference<ClassLoader>> refs = new ArrayList<>(count);
    long before = metaspaceUsed();

    for (int i = 0; i < count; i++) {
      String name = className("Gen" + i);
      VarkaClassLoader loader = new VarkaClassLoader(parent);
      loader.defineGeneratedClass(name, generatedClass(name));
      loader.release();
      refs.add(new WeakReference<>(loader, queue));
    }

    long collected = awaitCollectedCount(refs, queue);
    long after = metaspaceUsed();
    log.info("batch stress: defined=" + count + " collected=" + collected
        + " metaspace before=" + before + " after=" + after);
    assertEquals(count, collected, "every per-task loader must be collected");
  }

  @Test
  void releaseLifecycle() {
    VarkaClassLoader loader = newLoader();
    loader.release();
    loader.release(); // idempotent
    assertTrue(loader.isReleased());
    assertThrows(IllegalStateException.class,
        () -> loader.defineGeneratedClass(className("AfterRelease"), new byte[0]));
  }

  @Test
  void registryAndFindClass() throws Exception {
    String nameA = className("RegA");
    String nameB = className("RegB");
    VarkaClassLoader loader = newLoader();
    try {
      Class<?> a = loader.defineGeneratedClass(nameA, generatedClass(nameA));
      Class<?> b = loader.defineGeneratedClass(nameB, generatedClass(nameB));

      assertNotSame(a, b);
      assertSame(a, loader.loadClass(nameA));
      assertSame(b, loader.loadClass(nameB));
      assertThrows(ClassNotFoundException.class,
          () -> loader.loadClass(className("Unknown")));
    } finally {
      loader.release();
    }
  }

  // --- helpers -------------------------------------------------------------

  private static VarkaClassLoader newLoader() {
    return new VarkaClassLoader(Thread.currentThread().getContextClassLoader());
  }

  private static String className(String simple) {
    return GEN_PACKAGE + "." + simple;
  }

  /**
   * Builds a minimal public class via the Class-File API: a no-arg constructor calling
   * {@code Object.<init>}, {@code String hello()}, and {@code long now()} delegating to
   * {@code System.currentTimeMillis()}.
   */
  private static byte[] generatedClass(String className) {
    ClassDesc cls = ClassDesc.of(className);
    ClassDesc system = ClassDesc.of("java.lang.System");
    MethodTypeDesc voidType = MethodTypeDesc.of(ConstantDescs.CD_void);
    int flags = AccessFlag.PUBLIC.mask();
    return ClassFile.of().build(cls, b -> b
        .withFlags(AccessFlag.PUBLIC)
        .withSuperclass(ConstantDescs.CD_Object)
        .withMethodBody("<init>", voidType, flags,
            cb -> cb.aload(0)
                .invokespecial(ConstantDescs.CD_Object, "<init>", voidType)
                .return_())
        .withMethodBody("hello", MethodTypeDesc.of(ConstantDescs.CD_String), flags,
            cb -> cb.loadConstant("hello").areturn())
        .withMethodBody("now", MethodTypeDesc.of(ConstantDescs.CD_long), flags,
            cb -> cb.invokestatic(system, "currentTimeMillis",
                MethodTypeDesc.of(ConstantDescs.CD_long)).lreturn()));
  }

  /**
   * Retries {@code System.gc()} until {@code ref} is enqueued or the timeout elapses.
   */
  private static boolean awaitCollected(WeakReference<?> ref, ReferenceQueue<?> queue) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GC_TIMEOUT_MS);
    while (System.nanoTime() < deadline) {
      if (queue.poll() == ref) {
        return true;
      }
      System.gc();
      System.runFinalization();
      sleepQuietly(25);
    }
    return queue.poll() == ref;
  }

  /**
   * Retries {@code System.gc()} until every reference is enqueued or the timeout elapses,
   * returning how many were collected.
   */
  private static long awaitCollectedCount(
      List<WeakReference<ClassLoader>> refs, ReferenceQueue<ClassLoader> queue) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GC_TIMEOUT_MS);
    long collected = 0;
    while (collected < refs.size() && System.nanoTime() < deadline) {
      while (queue.poll() != null) {
        collected++;
      }
      System.gc();
      System.runFinalization();
      sleepQuietly(25);
    }
    return collected;
  }

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Diagnostic only: bytes currently used by the JVM's Metaspace pools ("Metaspace" and
   * "Compressed Class Space"). Never asserted - class unloading is verified via the weak
   * references instead, which is deterministic.
   */
  private static long metaspaceUsed() {
    long used = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      String name = pool.getName();
      if (name.equals("Metaspace") || name.equals("Compressed Class Space")) {
        used += pool.getUsage().getUsed();
      }
    }
    return used;
  }
}
