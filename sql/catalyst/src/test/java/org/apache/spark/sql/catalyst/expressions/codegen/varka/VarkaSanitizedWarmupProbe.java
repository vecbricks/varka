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

import java.lang.foreign.Arena;

/**
 * One kernel warmed by {@link VarkaKernelWarmup} and then called in the memory sanitizer's
 * window, in a JVM of its own, for {@code VarkaSanitizedWarmupSuite}.
 *
 * <p>Run with {@code -Dvarka.sanitizeMemory=true} and {@code -Xlog:deoptimization=debug}, it
 * prints {@link #BEFORE} once the warm-up has its verdict, then makes {@link #CALLS} calls of
 * {@link VarkaKernelWarmupProbe#shape()}'s kernel the way the evaluator's batches do (a window
 * with the call's buffers registered), then prints {@link #AFTER}. Between the two markers the
 * deoptimization log holds what those calls threw away: a kernel warmed outside the window
 * leaves a {@code speculate_null_assert} trap at {@link VarkaMemorySanitizer#check}, whose
 * window the warm-up's profile saw as always null.
 */
public final class VarkaSanitizedWarmupProbe {

  private VarkaSanitizedWarmupProbe() {
  }

  public static final String BEFORE = "VARKA_SANITIZED_WARMUP_BEFORE";
  public static final String AFTER = "VARKA_SANITIZED_WARMUP_AFTER";
  public static final String CLASS_PREFIX = "VARKA_SANITIZED_WARMUP_CLASS=";
  public static final String STATE_PREFIX = "VARKA_SANITIZED_WARMUP_STATE=";
  public static final String CHECKED_PREFIX = "VARKA_SANITIZED_WARMUP_CHECKED=";

  /** Calls made in the window after the verdict. */
  public static final int CALLS = 200;

  private static final int ROWS = 1024;

  public static void main(String[] args) throws InterruptedException {
    var warmed = VarkaKernelWarmupProbe.warm(new VarkaShapeCacheImpl(8), 10000,
        VarkaKernelWarmupProbe.Nulls.SOME_NULLS);
    var entry = warmed.entry();
    boolean idle = warmed.queued() && VarkaKernelWarmup.awaitIdle(200000);
    var kernel = entry.newKernel();
    System.out.println(CLASS_PREFIX + kernel.getClass().getName());
    System.out.println(STATE_PREFIX + "queued=" + warmed.queued() + " idle=" + idle + " state="
        + entry.warmth().state());
    long checkedBefore = VarkaMemorySanitizer.checked();
    System.out.println(BEFORE);
    try (var arena = Arena.ofConfined()) {
      var batch = VarkaKernelWarmupProbe.dates(arena, ROWS,
          VarkaKernelWarmupProbe.Nulls.SOME_NULLS);
      long[] src = {batch.data().address()};
      long[] validity = {batch.validity().address()};
      int[] nullCounts = {batch.nullCount()};
      long[] dstData = new long[VarkaKernelWarmupProbe.NUM_OUTPUTS];
      long[] dstValidity = new long[VarkaKernelWarmupProbe.NUM_OUTPUTS];
      for (int o = 0; o < dstData.length; o++) {
        dstData[o] = arena.allocate(ROWS * 8L, 64).address();
        dstValidity[o] = arena.allocate(ROWS / 8L + 8, 64).address();
      }
      int[] literals = {2};
      long[] inStride = {4};
      long[] outStride = {8, 8, 8};
      for (int k = 0; k < CALLS; k++) {
        VarkaKernelChecks.sanitized(kernel, ROWS, inStride, outStride, src, validity, dstData,
            dstValidity, scratch -> kernel.run(src, validity, nullCounts, dstData, dstValidity,
                literals, ROWS, scratch));
      }
    }
    System.out.println(AFTER);
    System.out.println(CHECKED_PREFIX + (VarkaMemorySanitizer.checked() - checkedBefore));
  }
}
