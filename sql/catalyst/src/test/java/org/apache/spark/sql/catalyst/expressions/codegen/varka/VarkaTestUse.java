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

package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of Varka's main classes each test runs code of, and what each test compiled and emitted
 * under each configuration: the record a pull request's option matrix compares with master's to
 * rerun only the (configuration, test) pairs the change can affect (VARKA-508).
 *
 * <p>With {@code -Dvarka.matrix.useRecord=<dir>} and JaCoCo's agent on the JVM (its
 * {@code includes} naming Varka's classes, {@code output=none}), the run takes JaCoCo's probes at
 * each test's start and end, resetting them each time, so a test's set is the classes whose code
 * ran while it was open, whichever test loaded them first: Ekstazi's record on use rather than on
 * load, at the grain of executed code. What runs in a suite's set-up and between its tests is the
 * suite's, and what runs outside every suite is the JVM's; both join each test they cover, as in
 * {@link VarkaTestReach}'s record. Only main classes are kept: a test class is keyed by the test's
 * own suite instead. A test that starts a child JVM ({@link VarkaChildJvm}, or a suite's own
 * launcher, each of which calls {@link #childJvm}) runs code the probes here do not see, so its
 * set is {@code *}, every class.
 *
 * <p>Each JVM writes its own file, {@code <dir>/use-<pid>.tsv}, so the two JVMs of a split run
 * never interleave a line. Its lines, the test's name last since it may hold any character:
 * <pre>
 *   U  suite  classes  test       the main classes the test ran ("*": all; suite "*", test "*":
 *                                 the JVM's; test "*": the suite's set-up)
 *   D  suite  config  digest  test   what the test compiled and emitted under the configuration
 *                                    ({@link VarkaTestReach} computes it; "defaults" for none)
 *   #  kernel-refs  classes       the Varka classes the kernels the recording emitted refer to
 *   #  broken  reason             the recording failed: readers take the file as no record
 * </pre>
 * Without JaCoCo's agent on the JVM the class sets are left out and only the digests are written.
 */
public final class VarkaTestUse {

  /** The directory each recording JVM writes its file to. */
  public static final String PROPERTY = "varka.matrix.useRecord";

  private static final Optional<Path> FILE = Optional
      .ofNullable(System.getProperty(PROPERTY))
      .map(d -> Path.of(d, "use-" + ProcessHandle.current().pid() + ".tsv"));

  private static final Set<String> OUTSIDE = ConcurrentHashMap.newKeySet();
  // The Varka classes the emitted kernels refer to, which they may call as they run.
  private static final Set<String> KERNEL_REFS = ConcurrentHashMap.newKeySet();
  private static volatile Set<String> suite;
  private static volatile boolean suiteChild;
  private static volatile Set<String> test;
  private static volatile boolean testChild;
  private static volatile boolean broken;

  /** JaCoCo's {@code IAgent} and its {@code getExecutionData(boolean)}, or nulls without it. */
  private static final Object AGENT = FILE.isPresent() ? agent() : null;
  private static final Method DATA = AGENT == null ? null : executionData();

  static {
    if (FILE.isPresent()) {
      try {
        Files.createDirectories(FILE.get().getParent());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        Set<String> outside = new TreeSet<>(OUTSIDE);
        outside.addAll(drain());
        if (AGENT != null && !broken) {
          append("U\t*\t" + names(outside, false) + "\t*");
        }
        if (!broken && !KERNEL_REFS.isEmpty()) {
          append("#\tkernel-refs\t" + String.join(",", new TreeSet<>(KERNEL_REFS)));
        }
      }));
    }
  }

  private static Object agent() {
    try {
      return Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null);
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }

  private static Method executionData() {
    try {
      return Class.forName("org.jacoco.agent.rt.IAgent")
          .getMethod("getExecutionData", boolean.class);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private VarkaTestUse() {}

  /** Whether this JVM records. */
  static boolean recording() {
    return FILE.isPresent() && !broken;
  }

  /** Opens a suite's window: what ran before it, outside every suite, is the JVM's. */
  static void suiteBegin() {
    if (recording()) {
      OUTSIDE.addAll(drain());
      suite = ConcurrentHashMap.newKeySet();
      suiteChild = false;
    }
  }

  /** Closes the suite's window with its set-up lines. */
  static void suiteEnd(String name, SortedMap<String, String> digests) {
    Set<String> s = suite;
    if (recording() && s != null) {
      s.addAll(drain());
      suite = null;
      write(name, "*", AGENT == null ? null : names(s, suiteChild), digests);
    }
  }

  /** The digest lines of what the JVM compiled outside every suite. */
  static void outside(SortedMap<String, String> digests) {
    if (recording()) {
      write("*", "*", null, digests);
    }
  }

  /** Begins a test: what ran since the last test ended is the suite's set-up. */
  static void begin() {
    if (recording()) {
      Set<String> before = drain();
      Set<String> s = suite;
      (s != null ? s : OUTSIDE).addAll(before);
      test = ConcurrentHashMap.newKeySet();
      testChild = false;
    }
  }

  /**
   * Ends a test with its class line and, where the reach recording computed them, one digest line
   * a configuration.
   */
  static void end(String suiteName, String testName, SortedMap<String, String> digests) {
    Set<String> t = test;
    if (!recording() || t == null) {
      return;
    }
    t.addAll(drain());
    test = null;
    write(suiteName, testName, AGENT == null ? null : names(t, testChild), digests);
  }

  /** Writes a window's class line, where there is one, and its digest lines in one append. */
  private static void write(String suiteName, String testName, String classes,
      SortedMap<String, String> digests) {
    var out = new StringBuilder();
    if (classes != null) {
      out.append("U\t").append(suiteName).append('\t').append(classes).append('\t')
          .append(testName).append('\n');
    }
    for (Map.Entry<String, String> d : digests.entrySet()) {
      out.append("D\t").append(suiteName).append('\t').append(d.getKey()).append('\t')
          .append(d.getValue()).append('\t').append(testName).append('\n');
    }
    if (!out.isEmpty()) {
      append(out.substring(0, out.length() - 1));
    }
  }

  /** Marks the open test, else the open suite, as running code in a child JVM. */
  public static void childJvm() {
    if (test != null) {
      testChild = true;
    } else if (suite != null) {
      suiteChild = true;
    } else if (recording()) {
      OUTSIDE.add("*");
    }
  }

  /**
   * Notes the Varka classes a kernel the reach recording emitted refers to: a reader that takes a
   * compiler or emitter class to be covered by the digest, as only what it emits, cannot do so
   * for one a kernel calls as it runs.
   */
  static void kernelRefs(byte[] kernel) {
    if (!recording()) {
      return;
    }
    for (PoolEntry e : ClassFile.of().parse(kernel).constantPool()) {
      if (e instanceof ClassEntry c) {
        String name = c.asInternalName().replace('/', '.');
        if (name.contains(".varka.") || name.contains("Varka")) {
          KERNEL_REFS.add(name);
        }
      }
    }
  }

  /** Marks the record broken, so that its readers rerun everything. */
  static void broke(String reason) {
    if (FILE.isPresent() && !broken) {
      append("#\tbroken\t" + reason.replace('\n', ' '));
      broken = true;
    }
  }

  private static String names(Set<String> classes, boolean child) {
    if (child || classes.contains("*")) {
      return "*";
    }
    return classes.isEmpty() ? "-" : String.join(",", new TreeSet<>(classes));
  }

  private static synchronized void append(String lines) {
    try {
      Files.writeString(FILE.get(), lines + "\n", StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // JaCoCo's probes
  // ---------------------------------------------------------------------------------------------

  // A class name to whether it is one of Varka's main classes, decided once.
  private static final Map<String, Boolean> MAIN = new ConcurrentHashMap<>();

  /** The main classes whose code ran since the last drain, the probes reset; empty without the
   * agent. */
  private static Set<String> drain() {
    if (AGENT == null) {
      return Set.of();
    }
    try {
      byte[] data = (byte[]) DATA.invoke(AGENT, true);
      var out = new TreeSet<String>();
      for (String name : executed(data)) {
        if (MAIN.computeIfAbsent(name, VarkaTestUse::isMain)) {
          out.add(name);
        }
      }
      return out;
    } catch (ReflectiveOperationException | IOException | RuntimeException e) {
      broke("JaCoCo's probes could not be read: " + e);
      return Set.of();
    }
  }

  /**
   * A class is Varka's when its package or its simple name says so, and main when its class file
   * comes from neither a test-classes directory nor a test jar.
   */
  static boolean isMain(String name) {
    int dot = name.lastIndexOf('.');
    boolean varka = name.contains(".varka.") || name.substring(dot + 1).contains("Varka");
    if (!varka) {
      return false;
    }
    URL url = ClassLoader.getSystemResource(name.replace('.', '/') + ".class");
    if (url == null) {
      return false;
    }
    String where = url.toString();
    return !where.contains("/test-classes/") && !where.contains("-tests.jar");
  }

  /**
   * The classes with a probe set in JaCoCo's execution data, in its file format (version
   * 0x1007): a header block, session blocks, and one block a class of its id, its VM name and
   * its probes as a packed boolean array.
   */
  static Set<String> executed(byte[] data) throws IOException {
    var out = new TreeSet<String>();
    var in = new DataInputStream(new ByteArrayInputStream(data));
    int type;
    while ((type = in.read()) != -1) {
      switch (type) {
        case 0x01 -> {
          if (in.readChar() != 0xC0C0) {
            throw new IOException("not JaCoCo execution data");
          }
          int version = in.readChar();
          if (version != 0x1007) {
            throw new IOException("JaCoCo execution data version " + version);
          }
        }
        case 0x10 -> {
          in.readUTF();
          in.readLong();
          in.readLong();
        }
        case 0x11 -> {
          in.readLong();
          String name = in.readUTF();
          if (anyProbe(in)) {
            out.add(name.replace('/', '.'));
          }
        }
        default -> throw new IOException("JaCoCo execution data block " + type);
      }
    }
    return out;
  }

  /** Reads a packed boolean array, least significant bit first, and says whether any is set. */
  private static boolean anyProbe(DataInputStream in) throws IOException {
    int length = varInt(in);
    boolean any = false;
    for (int i = 0; i < (length + 7) / 8; i++) {
      any |= in.readUnsignedByte() != 0;
    }
    return any;
  }

  private static int varInt(DataInputStream in) throws IOException {
    int value = in.readUnsignedByte();
    if ((value & 0x80) == 0) {
      return value;
    }
    return (value & 0x7F) | (varInt(in) << 7);
  }
}
