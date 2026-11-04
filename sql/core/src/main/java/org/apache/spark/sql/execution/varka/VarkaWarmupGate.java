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


package org.apache.spark.sql.execution.varka;

import java.util.function.Supplier;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmup;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMemorySanitizer;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * Whether a batch goes to the kernel while the shape's kernel warms: once the shape's kernel is
 * compiled or nothing is warming it any more ({@link VarkaKernelWarmth}). Asked only by an
 * evaluator whose kernels are warmed; one that is not never builds this gate and serves its batches
 * at once. The first task to meet a cold shape claims it and queues the warm-up
 * on a copy of its batch; until the verdict, every batch of the shape in every task takes the row
 * path. A volatile read per batch once the shape is ready.
 */
public final class VarkaWarmupGate {

  private static final SparkLogger LOG = SparkLoggerFactory.getLogger(VarkaWarmupGate.class);

  /** Each kernel input's bytes per row, read from a batch when a warm-up starts. */
  public interface InputWidths {
    int[] of(ColumnarBatch input);
  }

  private final VarkaKernelRunner runner;
  private final boolean anyNullableInput;
  private final InputWidths inputWidths;
  private final Supplier<String> kernelIdentity;
  private final int[] outputBytes;

  /**
   * @param anyNullableInput whether any kernel input can hold a null, so that a batch can reach
   *                         the masked driver, which the warm-up then compiles too
   * @param outputBytes each output's bytes per row: eight, or sixteen for a decimal's slot
   */
  public VarkaWarmupGate(VarkaKernelRunner runner, boolean anyNullableInput,
      InputWidths inputWidths, Supplier<String> kernelIdentity, int[] outputBytes) {
    this.outputBytes = outputBytes;
    this.runner = runner;
    this.anyNullableInput = anyNullableInput;
    this.inputWidths = inputWidths;
    this.kernelIdentity = kernelIdentity;
  }

  /** Whether this batch goes to the kernel. Throws the decline the warm-up's copy met. */
  public boolean kernelReady(ColumnarBatch input) {
    VarkaKernelWarmth warmth = runner.warmth;
    if (!warmth.ready() && warmth.tryClaim()) {
      startWarmup(input);
    }
    return warmth.ready();
  }

  /**
   * Copies this batch's kernel inputs and queues the shape's warm-up on them
   * ({@link VarkaKernelWarmup#start}, which copies before it returns, so the batch is free to go).
   * A batch the evaluator declines before the kernel would run cannot be copied, so the claim goes
   * back for a later batch and the decline goes on to the caller. Every other way out without a
   * queued warm-up releases the shape - its batches then run the kernel - because a claim left
   * behind would keep them on the row path with nothing warming the kernel.
   */
  private void startWarmup(ColumnarBatch input) {
    int len = input.numRows();
    boolean settled = false;
    // The copy maps the batch's own memory, so under the memory sanitizer it runs in a window with
    // the inputs registered, as a kernel call does (VARKA-263); off, this is two returns.
    VarkaMemorySanitizer.begin();
    try {
      runner.fill(input, len);
      VarkaKernelWarmup.start(runner.warmth, runner.shapeHash, runner.newKernel(),
          runner.lane.bits() == Long.SIZE, runner.srcData, runner.srcValidity, runner.srcNullCount,
          inputWidths.of(input), anyNullableInput, len, outputBytes, runner.scalarArgs,
          runner.longArgs);
      settled = true;
    } catch (VarkaBatchDeclined e) {
      runner.warmth.unclaim();
      settled = true;
      throw e;
    } catch (Throwable e) {
      if (!VarkaKernelRunner.isCatchable(e)) {
        throw e;
      }
      LOG.warn("Could not start the warm-up of the Varka SIMD kernels " + kernelIdentity.get()
          + "; its batches run the kernel from now on.", e);
    } finally {
      VarkaMemorySanitizer.end();
      if (!settled) {
        runner.warmth.release();
      }
    }
  }
}
