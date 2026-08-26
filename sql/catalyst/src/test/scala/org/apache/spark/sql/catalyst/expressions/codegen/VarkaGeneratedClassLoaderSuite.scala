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

package org.apache.spark.sql.catalyst.expressions.codegen

import java.lang.management.ManagementFactory
import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaLoopEmitter, VarkaVectorIR}

/**
 * Task 7 Metaspace/unloadability proof for the catalyst-side [[VarkaGeneratedClassLoader]],
 * mirroring the engine module's `VarkaClassLoaderTest` (Task 3). Generated classes are produced
 * with `VarkaLoopEmitter.emit` (the assembler the execution path uses since task 10 - the
 * milestone-1 dispatcher assembler this suite used before retired in task 17), so each "task"
 * defines one real fused-kernel class in its own per-task loader and releases it on
 * completion.
 *
 * The deterministic guarantee is weak-reference based: after `release()` and dropping all
 * references, a retried `System.gc()` must enqueue the loader's `WeakReference`, proving the
 * loader (and its classes) were collected from Metaspace. Metaspace pool usage is logged
 * before/after the batch stress as a diagnostic only - never asserted, because GC/collection
 * timings are JVM dependent.
 */
class VarkaGeneratedClassLoaderSuite extends SparkFunSuite {

  private val genPackage = "org.apache.spark.sql.varka.gen"

  /** Total budget (ms) for GC-retry loops; generous to stay robust on loaded JVMs. */
  private val gcTimeoutMs = 10000L

  private def genClass(simple: String): String = s"$genPackage.$simple"

  /** Emits a real one-op fused kernel class (no-arg ctor + `run`) under the given name. */
  private def generatedClass(name: String): Array[Byte] = {
    VarkaLoopEmitter.emit(name,
      java.util.List.of[VarkaVectorIR](
        new VarkaVectorIR.AddDays(new VarkaVectorIR.ColumnRef(0),
          new VarkaVectorIR.LiteralSlot(0))),
      1, 1)
  }

  test("define, registry and instantiation") {
    val name = genClass("Hello")
    val loader = new VarkaGeneratedClassLoader(Thread.currentThread().getContextClassLoader)
    try {
      val clazz = loader.defineGeneratedClass(name, generatedClass(name))

      assertSame(clazz, loader.loadClass(name))
      assertSame(loader, clazz.getClassLoader)
      assert(!loader.isReleased)
      // A public no-arg constructor must be present so class loaders can instantiate runners.
      assert(clazz.getConstructor().newInstance() != null)
    } finally {
      loader.release()
    }
  }

  test("a released loader whose references are dropped is collected") {
    val parent = Thread.currentThread().getContextClassLoader
    val queue = new ReferenceQueue[ClassLoader]()
    var loader = new VarkaGeneratedClassLoader(parent)
    val ref = new WeakReference[ClassLoader](loader, queue)
    val name = genClass("Unload")
    loader.defineGeneratedClass(name, generatedClass(name))
    loader.loadClass(name)

    loader.release()
    assert(loader.isReleased)

    loader = null

    assert(awaitCollected(ref, queue),
      "released loader must be collected so its classes unload from Metaspace")
  }

  test("a 1000-loader batch is fully collected without Metaspace growth") {
    val parent = Thread.currentThread().getContextClassLoader
    val queue = new ReferenceQueue[ClassLoader]()
    val refs = (0 until 1000).map { i =>
      val name = genClass(s"Gen$i")
      val loader = new VarkaGeneratedClassLoader(parent)
      loader.defineGeneratedClass(name, generatedClass(name))
      loader.release()
      new WeakReference[ClassLoader](loader, queue)
    }
    val before = metaspaceUsed()
    val collected = awaitCollectedCount(refs, queue)
    val after = metaspaceUsed()
    logInfo(s"batch stress: defined=${refs.size} collected=$collected " +
      s"metaspace before=$before after=$after")

    assert(collected == refs.size, "every per-task loader must be collected")
  }

  test("release is idempotent and rejects further definitions") {
    val loader = new VarkaGeneratedClassLoader(Thread.currentThread().getContextClassLoader)
    loader.release()
    loader.release()
    assert(loader.isReleased)
    intercept[IllegalStateException] {
      loader.defineGeneratedClass(genClass("AfterRelease"), new Array[Byte](0))
    }
  }

  test("the registry resolves multiple classes and rejects unknown names") {
    val loader = new VarkaGeneratedClassLoader(Thread.currentThread().getContextClassLoader)
    try {
      val nameA = genClass("RegA")
      val nameB = genClass("RegB")
      val a = loader.defineGeneratedClass(nameA, generatedClass(nameA))
      val b = loader.defineGeneratedClass(nameB, generatedClass(nameB))

      assert(!(a eq b))
      assertSame(a, loader.loadClass(nameA))
      assertSame(b, loader.loadClass(nameB))
      intercept[ClassNotFoundException] {
        loader.loadClass(genClass("Unknown"))
      }
    } finally {
      loader.release()
    }
  }

  // --- helpers -------------------------------------------------------------

  private def assertSame(a: AnyRef, b: AnyRef): Unit = {
    assert(a.asInstanceOf[AnyRef] eq b, s"expected the same instance but got $a and $b")
  }

  private def metaspaceUsed(): Long = {
    ManagementFactory.getMemoryPoolMXBeans.asScala.collect {
      case p if p.getName == "Metaspace" || p.getName == "Compressed Class Space" =>
        p.getUsage.getUsed
    }.sum
  }

  /**
   * Retries `System.gc()` until `ref` is enqueued or the timeout elapses.
   */
  private def awaitCollected(
      ref: WeakReference[_],
      queue: ReferenceQueue[_]): Boolean = {
    val deadline = System.nanoTime() + gcTimeoutMs * 1000000L
    while (System.nanoTime() < deadline) {
      if (queue.poll() eq ref) {
        return true
      }
      System.gc()
      System.runFinalization()
      Thread.sleep(25)
    }
    queue.poll() eq ref
  }

  /**
   * Retries `System.gc()` until every reference is enqueued or the timeout elapses, returning
   * how many were collected.
   */
  private def awaitCollectedCount(
      refs: Seq[WeakReference[ClassLoader]],
      queue: ReferenceQueue[ClassLoader]): Int = {
    val deadline = System.nanoTime() + gcTimeoutMs * 1000000L
    var collected = 0
    while (collected < refs.size && System.nanoTime() < deadline) {
      while (queue.poll() != null) {
        collected += 1
      }
      System.gc()
      System.runFinalization()
      Thread.sleep(25)
    }
    collected
  }
}
