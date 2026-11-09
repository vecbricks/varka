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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChildProcess.ChildFailure;

/**
 * {@code VarkaChildProcess} holds a child to its budget while the child runs, ends it on an
 * interrupt or when its reader stops, and names it in each failure (VARKA-582). Plain programs
 * stand in for a probe JVM, since the waiting, not the JVM, is what is under test.
 */
@VarkaSuite
public final class VarkaChildProcessSuite {

  private static final Pattern PID = Pattern.compile("\\(pid (\\d+)\\)");

  private static String program(String path) {
    assumeTrue(new File(path).canExecute(), "no " + path + " to stand in for a probe");
    return path;
  }

  private static long seconds(long since) {
    return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - since);
  }

  /** The child the failure names, by the PID it quotes, is no longer running. */
  private static void assertEnded(ChildFailure e) {
    var m = PID.matcher(e.getMessage());
    assertTrue(m.find(), e.getMessage());
    long pid = Long.parseLong(m.group(1));
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
        "the child, pid " + pid + ", is still running");
  }

  @Test
  @DisplayName("a child past its budget is destroyed and the failure names it")
  void budget() {
    var sleep = new ProcessBuilder(program("/bin/sleep"), "30");
    long begun = System.nanoTime();
    var e = assertThrows(ChildFailure.class,
        () -> VarkaChildProcess.run("a sleeping child", sleep, 1L, line -> {}));
    assertTrue(seconds(begun) < 20L, "the budget did not end the child");
    assertTrue(e.getMessage().startsWith("a sleeping child (pid ")
        && e.getMessage().contains(") did not finish within 1 s and was destroyed; its last"
            + " lines:"), e.getMessage());
    assertEnded(e);
  }

  @Test
  @DisplayName("an interrupt of the waiting thread destroys the child, names it and is not kept")
  void interrupt() throws InterruptedException {
    var sleep = new ProcessBuilder(program("/bin/sleep"), "30");
    Thread waiting = Thread.currentThread();
    var interrupter = new Thread(() -> {
      try {
        Thread.sleep(500);
        waiting.interrupt();
      } catch (InterruptedException ignored) {
        // The test ended first.
      }
    });
    interrupter.start();
    long begun = System.nanoTime();
    try {
      var e = assertThrows(ChildFailure.class,
          () -> VarkaChildProcess.run("a sleeping child", sleep, 300L, line -> {}));
      // A flag left set would fail the next probe on this thread at once.
      assertFalse(Thread.interrupted(), "the interrupt was left set");
      assertTrue(seconds(begun) < 20L, "the interrupt did not end the wait");
      assertTrue(e.getMessage().contains(") was interrupted after "), e.getMessage());
      assertEnded(e);
    } finally {
      interrupter.join();
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("a reader that stops ends the child, and its failure reaches the caller")
  void readerStops() {
    var yes = new ProcessBuilder(program("/usr/bin/yes"));
    var lines = new AtomicInteger();
    long begun = System.nanoTime();
    var e = assertThrows(IllegalStateException.class,
        () -> VarkaChildProcess.run("an endless child", yes, 300L, line -> {
          if (lines.incrementAndGet() == 1000) {
            throw new IllegalStateException("enough");
          }
        }));
    assertEquals("enough", e.getMessage());
    assertTrue(seconds(begun) < 20L, "the child ran on after its reader stopped");
  }

  @Test
  @DisplayName("a child that fails is named with its exit and its last lines")
  void failure() throws Exception {
    var sh = new ProcessBuilder(program("/bin/sh"), "-c", "echo first; echo last >&2; exit 3");
    var run = VarkaChildProcess.run("a failing child", sh, 60L, line -> {});
    assertEquals(3, run.exitValue());
    var e = assertThrows(ChildFailure.class, () -> run.requireSuccess(true));
    assertEquals("a failing child (pid " + run.pid() + ") failed (exit 3); its last lines:\n"
        + "first\nlast", e.getMessage());
    var ok = VarkaChildProcess.run("a quiet child", new ProcessBuilder(program("/bin/sh"), "-c",
        "exit 0"), 60L, line -> {});
    var missing = assertThrows(ChildFailure.class, () -> ok.requireSuccess(false));
    assertTrue(missing.getMessage().startsWith("a quiet child (pid " + ok.pid()
        + ") exited without printing its end mark"), missing.getMessage());
  }

  @Test
  @DisplayName("a test's children at their budgets end before its cap")
  void budgets() {
    assertEquals(540L, VarkaChildProcess.budgetSeconds(10, 1));
    assertEquals(135L, VarkaChildProcess.budgetSeconds(10, 4));
    assertTrue(4 * VarkaChildProcess.budgetSeconds(10, 4) < 10 * 60);
  }
}
