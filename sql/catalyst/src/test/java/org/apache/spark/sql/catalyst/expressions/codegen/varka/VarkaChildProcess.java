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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Starts a probe JVM for a JIT suite and holds it to a time budget: the launcher of
 * {@code VarkaHugeMethodSuite}, {@code VarkaSplitInliningSuite}, {@code VarkaAssemblySuite} and
 * {@code VarkaWidthAuditSuite} (VARKA-582), and of {@code VarkaOperatorCensusSuite},
 * {@code VarkaInliningCliffSuite}, the two warm-up suites, sql/core's
 * {@code VarkaSizeLadderJitSuite}, {@code VarkaDeoptCycleSuite}'s Python parser and
 * {@code VarkaVectorTemplateWarmupSuite}'s {@code -version} JVMs (VARKA-585).
 *
 * <p>The child's output ends only when the child exits, so a launcher that reads it to its end on
 * the calling thread and waits afterwards never sees a timeout, and a read blocked on a pipe does
 * not answer {@code VarkaTestWatchdog}'s interrupt either. Here a reader thread hands each line to
 * the caller while the calling thread waits for the child's exit, which a budget and an interrupt
 * both end; either way the child is destroyed and the failure names it, with its last lines.
 */
public final class VarkaChildProcess {

  private VarkaChildProcess() {}

  /** The lines of the child's output a failure quotes. */
  static final int TAIL_LINES = 40;

  /**
   * Seconds set aside from a test's cap for the parent's own work and its report, so that a
   * test's children, at their budgets, end before the watchdog does.
   */
  static final long MARGIN_SECONDS = 60;

  /** A child that timed out, was interrupted or failed, named with its last lines. */
  public static final class ChildFailure extends RuntimeException {
    ChildFailure(String message) {
      super(message);
    }

    ChildFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** How a child ended: its name, PID, exit status and last {@link #TAIL_LINES} lines. */
  public record Run(String what, long pid, int exitValue, List<String> tail) {
    public Run {
      tail = List.copyOf(tail);
    }

    /**
     * Throws, naming the child with its exit and last lines, unless it exited 0 and {@code done}
     * says its output reached the end mark the caller looks for.
     */
    public void requireSuccess(boolean done) {
      if (exitValue != 0 || !done) {
        String how = exitValue != 0 ? "failed (exit " + exitValue + ")"
            : "exited without printing its end mark";
        throw new ChildFailure(what + " (pid " + pid + ") " + how + "; its last lines:\n"
            + lastLines());
      }
    }

    public String lastLines() {
      return String.join("\n", tail);
    }
  }

  /**
   * The budget of each child when a test whose cap is {@code capMinutes} starts up to
   * {@code childrenPerTest} of them one after another: the cap less {@link #MARGIN_SECONDS},
   * shared out, so that the children's budgets sum to under the cap.
   */
  public static long budgetSeconds(long capMinutes, int childrenPerTest) {
    return Math.max(1L, (capMinutes * 60 - MARGIN_SECONDS) / childrenPerTest);
  }

  /**
   * The command that runs {@code mainClass} with {@code arguments} on this JVM's {@code java} and
   * the test classpath, with the Vector API module, native access and then {@code jvmFlags}.
   */
  public static List<String> javaCommand(List<String> jvmFlags, String mainClass,
      List<String> arguments) {
    var command = new ArrayList<String>();
    command.add(new File(new File(System.getProperty("java.home"), "bin"), "java").getPath());
    command.add("--add-modules");
    command.add("jdk.incubator.vector");
    // The kernels read off-heap memory through MemorySegment.reinterpret, a restricted method:
    // without this the child prints a warning per call site.
    command.add("--enable-native-access=ALL-UNNAMED");
    command.addAll(jvmFlags);
    command.add("-cp");
    String dist = System.getenv("SPARK_DIST_CLASSPATH");
    command.add(dist != null && !dist.isEmpty() ? dist : System.getProperty("java.class.path"));
    command.add(mainClass);
    command.addAll(arguments);
    return command;
  }

  /**
   * Runs {@code builder}'s child, its errors merged into its output, handing each line to
   * {@code onLine} on a reader thread, and returns once it has exited and every line is read.
   * {@code what} names the child in every failure: the suite, the probe and its case or width.
   *
   * <p>Throws, with the child destroyed, when it is still running after {@code budgetSeconds},
   * when the calling thread is interrupted, and when {@code onLine} throws, which is how a caller
   * stops a child whose output it has seen enough of. The interrupt is not set again: the
   * watchdog that sends it has already failed the test, and a flag left on the runner's thread
   * would fail every later probe on it at once.
   */
  public static Run run(String what, ProcessBuilder builder, long budgetSeconds,
      Consumer<String> onLine) throws IOException {
    // The child's code runs where the class-use record does not see it (VarkaTestUse).
    VarkaTestUse.childJvm();
    builder.redirectErrorStream(true);
    long begun = System.nanoTime();
    Process process = builder.start();
    var tail = new ArrayDeque<String>();
    var readFailure = new Throwable[1];
    var reader = new Thread(() -> {
      try (var in = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = in.readLine()) != null) {
          synchronized (tail) {
            tail.addLast(line);
            if (tail.size() > TAIL_LINES) {
              tail.removeFirst();
            }
          }
          onLine.accept(line);
        }
      } catch (Throwable t) {
        readFailure[0] = t;
        // Nothing reads the child's output any more, so it would block on a full pipe.
        destroy(process);
      }
    }, "varka-child-reader: " + what);
    reader.setDaemon(true);
    reader.start();
    try {
      if (!process.waitFor(budgetSeconds, TimeUnit.SECONDS)) {
        destroy(process);
        process.waitFor();
        reader.join();
        throw new ChildFailure(what + " (pid " + process.pid() + ") did not finish within "
            + budgetSeconds + " s and was destroyed; its last lines:\n" + lines(tail));
      }
      // The child's output ends once it has exited, so the reader ends too, and what onLine
      // wrote is visible here once it is joined.
      reader.join();
    } catch (InterruptedException e) {
      destroy(process);
      awaitExit(process);
      throw new ChildFailure(what + " (pid " + process.pid() + ") was interrupted after "
          + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begun)
          + " s, the test's watchdog the likely cause, and was destroyed; its last lines:\n"
          + lines(tail), e);
    } finally {
      // Whatever ended the wait, the child does not outlive it.
      destroy(process);
    }
    if (readFailure[0] instanceof RuntimeException r) {
      throw r;
    } else if (readFailure[0] instanceof Error r) {
      throw r;
    } else if (readFailure[0] != null) {
      throw new ChildFailure("reading the output of " + what + " failed", readFailure[0]);
    }
    return new Run(what, process.pid(), process.exitValue(), List.copyOf(tail));
  }

  /** Waits a little for a destroyed child to be gone, so a failure names an ended process. */
  private static void awaitExit(Process process) {
    try {
      process.waitFor(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      // A second interrupt: the child was destroyed, which is all that is owed here.
    }
  }

  private static void destroy(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  private static String lines(ArrayDeque<String> tail) {
    synchronized (tail) {
      return String.join("\n", tail);
    }
  }
}
