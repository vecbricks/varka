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
   * @param srcData address of each input column's values, by ordinal - int32 at this entry
   *        point, and int64 at the eight-argument one below.
   * @param srcValidity address of each input column's bit-packed validity (or 0L, see above).
   * @param srcNullCount null count of each input column.
   * @param dstData address of each output column's values (length * 4 bytes each; eight at
   *        the eight-argument entry point below, except an output whose root is a
   *        {@link VarkaVectorIR.NarrowLane}, which is four bytes a row there too).
   * @param dstValidity address of each output column's bit-packed validity
   *        ((length + 7) / 8 bytes each); always required.
   * @param scalarArgs the runtime values of the chain's literal slots.
   * @param length number of rows.
   * @return zero when the outputs are valid; otherwise a bitmask of the reasons they are not,
   *         and the caller must recompute this batch on the row engine.
   */
  default int run(long[] srcData, long[] srcValidity, int[] srcNullCount,
      long[] dstData, long[] dstValidity, int[] scalarArgs, int length) {
    throw new UnsupportedOperationException(
        getClass().getName() + " is a 64-bit-lane kernel; call the eight-argument run");
  }

  /**
   * The same call for a kernel whose lanes are 64 bits wide, with a scalar array of its own:
   * a {@code bigint} or {@code TIME} literal does not fit the {@code int[]} above, and widening
   * that array for every kernel would change the descriptor and the loads of every 32-bit
   * kernel already emitted - see {@code VARKA-85.md} 3.1.
   *
   * <p>A class implements the one its lane needs, and the other throws: one emitted class is
   * one species, so calling a long kernel through the int entry point is a caller error rather
   * than a conversion to perform. The two defaults here are what make that a named failure
   * instead of an {@code AbstractMethodError}.
   *
   * <p>Every literal slot of a long-lane shape lives in {@code longArgs}: the lane's leaves are
   * 64 bits wide, so a literal is widened once by the caller rather than per batch by the loop.
   * {@code scalarArgs} is still a parameter because the body methods of both lanes share one
   * descriptor shape, and a long-lane kernel never reads it - callers may pass an empty array.
   */
  default int run(long[] srcData, long[] srcValidity, int[] srcNullCount,
      long[] dstData, long[] dstValidity, int[] scalarArgs, long[] longArgs, int length) {
    throw new UnsupportedOperationException(
        getClass().getName() + " is a 32-bit-lane kernel; call the seven-argument run");
  }

  /**
   * How many bytes of scratch a call must pass per row: zero for every kernel that materializes
   * no calendar prefix (VARKA-198), which ignores the address. A caller allocates
   * {@code scratchBytesPerRow() * length} bytes and passes their address to the two overloads
   * below; a kernel with scratch owns none of it, so a call still allocates nothing. A caller
   * that uses the forms without the address on such a kernel gets the thread's fallback buffer
   * ({@link VarkaScratch}), which is for the suites and the tools; the evaluator and the warm-up
   * pass their own.
   */
  default int scratchBytesPerRow() {
    return 0;
  }

  /**
   * The seven-argument {@code run} with the scratch address after the length: the form the
   * evaluator and the warm-up use. A kernel with no scratch inherits this default, which drops
   * the address and runs the seven-argument form, so its emitted bytes are unchanged; a kernel
   * with scratch implements this one, refuses a zero address by name, and its seven-argument
   * form takes the thread's fallback buffer and calls this one.
   */
  default int run(long[] srcData, long[] srcValidity, int[] srcNullCount,
      long[] dstData, long[] dstValidity, int[] scalarArgs, int length, long scratch) {
    return run(srcData, srcValidity, srcNullCount, dstData, dstValidity, scalarArgs, length);
  }

  /** The eight-argument {@code run} with the scratch address after the length; as above. */
  default int run(long[] srcData, long[] srcValidity, int[] srcNullCount,
      long[] dstData, long[] dstValidity, int[] scalarArgs, long[] longArgs, int length,
      long scratch) {
    return run(srcData, srcValidity, srcNullCount, dstData, dstValidity, scalarArgs, longArgs,
        length);
  }
}
