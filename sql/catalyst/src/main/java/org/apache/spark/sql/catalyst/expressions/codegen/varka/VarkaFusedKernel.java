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

/**
 * The call-site view of a fused Varka loop assembled by {@link VarkaLoopEmitter}: the generated
 * class implements this interface, so the execution path reaches the loop with an ordinary
 * interface call and every argument stays primitive (milestone 1's dispatcher lesson, kept).
 *
 * <p>The arrays are indexed by input ordinal / output position / literal slot and are unpacked
 * into locals at method entry - never indexed inside the loop. Callers reuse the same arrays
 * across batches, so a call allocates nothing.
 *
 * <p>Address contract, inherited from the hand-written kernels: a source validity address is
 * dereferenced only when {@code 0 < srcNullCount[i] < length}, so a null-free or all-null input
 * may pass {@code 0L} there. Destination validity addresses are always required; the loop zeroes
 * them first and only ORs bits in, so rows it does not write come out null. Data values of null
 * output rows are undefined.
 *
 * <p>Selection outputs: an output whose IR root is a condition writes no data at all -
 * its {@code dstData} slot is never dereferenced and callers pass {@code 0L} there - and its
 * {@code dstValidity} is the selection bitmap: a set bit means the predicate is known true for
 * that row, an unset bit means false or null (SQL's {@code WHERE} rule). The zero-then-OR
 * discipline above doubles as the selection invariant: a row the loop never writes reads as
 * unselected.
 *
 * <p>Status, and why {@code run} returns one: a lowering may be correct only over
 * part of its input domain - the narrowed civil-from-days variant is valid over a bounded day
 * range, and nothing at compile time can bound a column's values. Such a kernel detects the
 * lanes it cannot compute and reports them, rather than publishing an answer it does not have.
 * A non-zero return means <b>this batch's outputs are not valid and the caller must recompute
 * the batch on the row engine</b>; zero means they are. It is a bitmask so a later lowering can
 * add its own reason without inventing a second channel - bit 0 is
 * {@link #STATUS_CHRONO_RANGE}. A kernel with nothing to report returns zero unconditionally,
 * which costs it one constant.
 *
 * @see VarkaVectorIR
 */
public interface VarkaFusedKernel {

  /** {@code run} saw a day outside the range its calendar lowering is defined over. */
  int STATUS_CHRONO_RANGE = 1;

  /**
   * Runs the fused loop over one batch.
   *
   * @param srcData address of each input column's int32 values, by ordinal.
   * @param srcValidity address of each input column's bit-packed validity (or 0L, see above).
   * @param srcNullCount null count of each input column.
   * @param dstData address of each output column's int32 values (length * 4 bytes each).
   * @param dstValidity address of each output column's bit-packed validity
   *        ((length + 7) / 8 bytes each); always required.
   * @param scalarArgs the runtime values of the chain's literal slots.
   * @param length number of rows.
   * @return zero when the outputs are valid; otherwise a bitmask of the reasons they are not,
   *         and the caller must recompute this batch on the row engine.
   */
  int run(long[] srcData, long[] srcValidity, int[] srcNullCount,
      long[] dstData, long[] dstValidity, int[] scalarArgs, int length);
}
