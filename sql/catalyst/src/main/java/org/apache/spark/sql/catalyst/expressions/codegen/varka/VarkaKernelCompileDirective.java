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

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.sun.management.HotSpotDiagnosticMXBean;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;

/**
 * Keeps HotSpot's first-tier compiler, C1, away from the kernel classes Varka warms, so that their
 * methods go from the interpreter to C2 and to nothing in between. One compiler directive, added
 * once per JVM, the first time a session decides to warm its kernels ({@link #readyForWarmup}).
 *
 * <p><b>Why.</b> A kernel's loop and epilogue methods are too large for C1 to compile with full
 * profiling, tier 3: its LIR generator runs out of virtual registers, the compile is skipped with
 * "retry at different tier", and the method is profiled in the interpreter until C2 takes it. But
 * they are not always too large for tier 2, the limited-profile code the tiered policy asks C1
 * for instead of tier 3 while the C2 queue is long. A method compiled at tier 2 that way is
 * stranded: from tier 2 the policy climbs only to tier 3, which C1 cannot compile, and tier-2 code
 * does not update the profile a direct climb to C2 would read. It then runs C1 code, boxing every
 * vector operation, for as long as its class lives. Whether a kernel falls into this depends on
 * how busy C2 is at the moment the kernel crosses its first threshold, and a warm-up, which
 * crosses every threshold within a second while a new query's own compiles fill the queue, falls
 * into it often (`PLAN_TASK_212.md` 10).
 *
 * <p>With C1 excluded, the first C1 request for a kernel method is refused and marks the method
 * not C1-compilable - where a tier-3 failure leaves it anyway - so the interpreter profiles it and
 * C2 compiles it, whatever the queues were doing. C1 code for these methods boxes every vector
 * operation just as the interpreter does, so the tiers lose nothing - but the profiling starts
 * later: the failed tier-3 request is what creates a method's profile, and without it the
 * interpreter creates one only at twice the tier-3 threshold. A kernel fed only by its own
 * batches would then reach C2 about a hundred batches later (`PLAN_TASK_212.md` 10.6). So the
 * directive matches warmed kernels alone: a kernel is emitted under the warmed name
 * ({@link VarkaShapeCacheImpl#WARMED_MARK}) only when its session warms kernels and this JVM can,
 * every such class gets a warm-up whose calls create its profile at once, and every other kernel
 * compiles as HotSpot decides.
 *
 * <p><b>Where it applies.</b> Where C1 and C2 are tiered, which is HotSpot's default. Where C2 is
 * the only compiler (tiered compilation off) there is no C1 to exclude and a warm-up needs no
 * directive. Where C2 is not in use - C1 alone, a JVMCI compiler, the interpreter - a warm-up
 * could never end in C2 code, and Varka does not warm; nor does it where the directive could not
 * be added.
 *
 * <p><b>How.</b> The DiagnosticCommand MBean's {@code compilerDirectivesAdd}, the in-process form
 * of {@code jcmd Compiler.directives_add}, reads the directive from a file, whose path it is
 * given in quotes because the command line it builds is split at spaces.
 */
public final class VarkaKernelCompileDirective {

  private static final SparkLogger LOG =
      SparkLoggerFactory.getLogger(VarkaKernelCompileDirective.class);

  /** The warmed kernel classes' methods, in the directive syntax: slashes, and a wildcard. */
  public static final String METHOD_PATTERN =
      (VarkaShapeCacheImpl.CLASS_NAME_PREFIX + VarkaShapeCacheImpl.WARMED_MARK).replace('.', '/')
          + "*.*";

  /** Which compilers this JVM runs; see the class doc. */
  enum Compilers { TIERED, C2_ONLY, NO_C2 }

  private static volatile Compilers compilers;

  private static volatile boolean attempted;

  private static volatile boolean installed;

  private VarkaKernelCompileDirective() {
  }

  /**
   * Whether a warm-up in this JVM can end in C2 code, adding the directive the first time it is
   * needed: true where C2 is the only compiler, or where C1 and C2 are tiered and the directive is
   * in place. False otherwise, for good - a failed attempt is not repeated - and Varka then emits
   * its kernels unwarmed, as it would with the warm-up off.
   */
  public static boolean readyForWarmup() {
    switch (compilers()) {
      case C2_ONLY:
        return true;
      case NO_C2:
        return false;
      default:
        if (!attempted) {
          synchronized (VarkaKernelCompileDirective.class) {
            if (!attempted) {
              installed = install();
              attempted = true;
            }
          }
        }
        return installed;
    }
  }

  /** Whether this JVM has the directive: added, and not skipped or failed. */
  public static boolean installed() {
    return installed;
  }

  /** Which compilers this JVM runs, read once from its flags. */
  static Compilers compilers() {
    Compilers c = compilers;
    if (c == null) {
      c = detect();
      compilers = c;
      if (c == Compilers.NO_C2) {
        LOG.info("Varka does not warm its kernels: C2 does not compile code in this JVM.");
      }
    }
    return c;
  }

  private static boolean install() {
    try {
      Path file = Files.createTempFile("varka-kernel-directive", ".json");
      try {
        Files.writeString(file,
            "[{ match: \"" + METHOD_PATTERN + "\", c1: { Exclude: true } }]");
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName command = new ObjectName("com.sun.management:type=DiagnosticCommand");
        Object reply = server.invoke(command, "compilerDirectivesAdd",
            new Object[] {new String[] {"\"" + file + "\""}},
            new String[] {String[].class.getName()});
        boolean added = String.valueOf(reply).contains("added");
        if (added) {
          LOG.info("Varka excluded C1 for the kernel classes it warms (" + METHOD_PATTERN
              + "): their methods go from the interpreter to C2.");
        } else {
          LOG.warn("Varka could not exclude C1 for the kernel classes it warms, and does not warm "
              + "them; the JVM answered: " + reply);
        }
        return added;
      } finally {
        Files.deleteIfExists(file);
      }
    } catch (Exception | LinkageError e) {
      // No DiagnosticCommand MBean, no writable temporary directory, or a JVM that refuses the
      // directive: the kernels then compile as HotSpot decides, unwarmed.
      LOG.warn("Varka could not exclude C1 for the kernel classes it warms, and does not warm "
          + "them.", e);
      return false;
    }
  }

  /** Reads which compilers run from the JVM's flags; any doubt reads as no C2. */
  private static Compilers detect() {
    HotSpotDiagnosticMXBean bean;
    try {
      bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    } catch (RuntimeException | LinkageError e) {
      return Compilers.NO_C2;
    }
    if (bean == null
        || !"true".equals(option(bean, "UseCompiler"))
        || "true".equals(option(bean, "UseJVMCICompiler"))
        || "true".equals(option(bean, "NeverActAsServerClassMachine"))) {
      return Compilers.NO_C2;
    }
    String mode = option(bean, "CompilationMode");
    if ("quick-only".equals(mode)) {
      return Compilers.NO_C2;
    }
    if (!"true".equals(option(bean, "TieredCompilation"))
        || "high-only".equals(mode) || "high-only-quick-internal".equals(mode)) {
      return Compilers.C2_ONLY;
    }
    return "4".equals(option(bean, "TieredStopAtLevel")) ? Compilers.TIERED : Compilers.NO_C2;
  }

  private static String option(HotSpotDiagnosticMXBean bean, String name) {
    try {
      return bean.getVMOption(name).getValue();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
