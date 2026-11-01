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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VarkaKernelChecks} runs a kernel whose bytes it loaded last on the thread on that class
 * instead of defining the class again for every batch and body. The reuse must leave every check
 * as defining it again would: each tree of the series gives the same outcome - compared, declined
 * with a status, or failed with a message - on its loaded class as on a class defined for the run,
 * under the matrix's options, under a planted wrong answer that fails the comparison after the
 * kernel ran, and under a misdescribed call that fails the class as it loads; and only a change of
 * bytes defines a class again.
 */
@VarkaSuite
public final class VarkaKernelReuseSuite {

  private static int counter;

  /** Every int tree to {@code size}, each over the series' four batches, under {@code options}. */
  private static List<String> outcomes(int size, VarkaEmitOptions options, boolean reuse) {
    return outcomes(size, options, reuse, root -> true);
  }

  /** {@link #outcomes} over the trees {@code keep} accepts. */
  private static List<String> outcomes(int size, VarkaEmitOptions options, boolean reuse,
      Predicate<VarkaVectorIR> keep) {
    boolean was = VarkaKernelChecks.reuseLoaded;
    VarkaKernelChecks.reuseLoaded = reuse;
    try {
      var out = new ArrayList<String>();
      var trees = new VarkaIrSeries.IntSeries().upTo(size);
      int index = 0;
      while (trees.hasNext()) {
        VarkaVectorIR root = trees.next().root();
        if (!keep.test(root)) {
          continue;
        }
        // The same name under both modes, so that a failure's message is the same text.
        String name = "org.apache.spark.sql.varka.execution.VarkaKernelReuse" + index++;
        byte[] bytes;
        try {
          bytes = VarkaLoopEmitter.emit(name, List.of(root), VarkaIrSeries.NUM_INPUTS,
              VarkaIrSeries.LITERALS, null, null, options);
        } catch (VarkaEmitDeclined e) {
          out.add("past the cap");
          continue;
        } catch (RuntimeException e) {
          out.add("emit: " + e.getMessage());
          continue;
        }
        for (var b : VarkaIrSeries.intBatches()) {
          String label = VarkaVectorIR.canonical(root) + " batch=" + b.name();
          try {
            out.add(label + ": status " + VarkaKernelChecks.runAndCompareStatus(label, name,
                bytes, List.of(root), VarkaIrSeries.NUM_INPUTS, b.lits(), b.batch(), true));
          } catch (RuntimeException | LinkageError e) {
            out.add(label + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
          }
        }
      }
      return out;
    } finally {
      VarkaKernelChecks.reuseLoaded = was;
    }
  }

  @Test
  @DisplayName("every tree to size 2 has the same outcome on its loaded class as on a fresh one")
  void sameOutcomes() {
    // Small, since every configuration runs it; the series suite's own counts are the wide check.
    List<String> fresh = outcomes(2, VarkaMatrixRuns.BASE, false);
    assertEquals(fresh, outcomes(2, VarkaMatrixRuns.BASE, true));
  }

  @Test
  @DisplayName("under a planted wrong answer the same trees fail with the same messages")
  void sameFailuresAfterTheRun() {
    VarkaKernelChecks.plantWrongAnswer = true;
    try {
      List<String> fresh = outcomes(2, VarkaMatrixRuns.BASE, false);
      long failed = fresh.stream().filter(o -> o.contains("differs")).count();
      assertTrue(failed > 0, "the planted answer failed no tree, so the comparison shows nothing");
      assertEquals(fresh, outcomes(2, VarkaMatrixRuns.BASE, true));
    } finally {
      VarkaKernelChecks.plantWrongAnswer = false;
    }
  }

  @Test
  @DisplayName("under a misdescribed call the same trees fail to load with the same messages")
  void sameFailuresAtLoad() {
    VarkaEmitOptions planted = VarkaMatrixRuns.BASE.withMisdescribeAdd(true);
    // The trees of size 3 that add days, the smallest the misdescribed call reaches.
    Predicate<VarkaVectorIR> adds = root -> root instanceof VarkaVectorIR.AddDays;
    List<String> fresh = outcomes(3, planted, false, adds);
    long failed = fresh.stream().filter(o -> o.contains("NoSuchMethodError")).count();
    assertTrue(failed > 0, "the misdescribed call failed no tree, so the comparison shows nothing");
    assertEquals(fresh, outcomes(3, planted, true, adds));
  }

  @Test
  @DisplayName("a kernel is defined once over its batches and bodies, and again for new bytes")
  void definedOncePerBytes() {
    var root = new VarkaVectorIR.ColumnRef(0);
    String name = "org.apache.spark.sql.varka.execution.VarkaKernelReuseCount" + counter++;
    byte[] bytes = VarkaLoopEmitter.emit(name, List.of(root), VarkaIrSeries.NUM_INPUTS,
        VarkaIrSeries.LITERALS, null, null, VarkaMatrixRuns.BASE);
    var batches = VarkaIrSeries.intBatches();
    long before = VarkaKernelChecks.KERNELS_DEFINED.get();
    for (var b : batches) {
      VarkaKernelChecks.runAndCompare("count", name, bytes, List.of(root),
          VarkaIrSeries.NUM_INPUTS, b.lits(), b.batch(), false, null);
    }
    assertEquals(1, VarkaKernelChecks.KERNELS_DEFINED.get() - before,
        "the kernel was defined again for a batch or a body");
    byte[] copy = bytes.clone();
    VarkaKernelChecks.runAndCompare("count", name, copy, List.of(root),
        VarkaIrSeries.NUM_INPUTS, batches.get(0).lits(), batches.get(0).batch(), false, null);
    assertEquals(1, VarkaKernelChecks.KERNELS_DEFINED.get() - before,
        "equal bytes in another array were defined again");
    // Other bytes under the same name, as a planted fault's rewrite of a kernel has them.
    var otherRoot = new VarkaVectorIR.ColumnRef(1);
    byte[] otherBytes = VarkaLoopEmitter.emit(name, List.of(otherRoot), VarkaIrSeries.NUM_INPUTS,
        VarkaIrSeries.LITERALS, null, null, VarkaMatrixRuns.BASE);
    VarkaKernelChecks.runAndCompare("count", name, otherBytes, List.of(otherRoot),
        VarkaIrSeries.NUM_INPUTS, batches.get(0).lits(), batches.get(0).batch(), false, null);
    assertEquals(2, VarkaKernelChecks.KERNELS_DEFINED.get() - before,
        "a kernel of other bytes under the same name ran on the class loaded before it");
  }
}
