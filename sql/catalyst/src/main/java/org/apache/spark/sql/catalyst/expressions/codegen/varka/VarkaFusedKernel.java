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
 * <p>A destination's data must not overlap any source's: a kernel that converts between the
 * two lanes stores a lane group's int output a whole int vector at a time, so it writes the
 * next group's rows before that group reads its inputs, and stores over them when it does.
 *
 * <p>Selection outputs: an output whose IR root is a condition writes no data at all -
 * its {@code dstData} slot is never dereferenced and callers pass {@code 0L} there - and its
 * {@code dstValidity} is the selection bitmap: a set bit means the predicate is known true for
 * that row, an unset bit means false or null (SQL's {@code WHERE} rule). The zero-then-OR
 * discipline above doubles as the selection invariant: a row the loop never writes reads as
 * unselected.
 *
 * <p>Boolean outputs: an output whose IR root is a {@code BoolOutput} writes two bitmaps, an
 * Arrow {@code BitVector}'s (VARKA-27). Its {@code dstData} is a bitmap of whole 64-bit words,
 * {@code ((length + 63) / 64) * 8} bytes, which the kernel zeroes and may store a word at a
 * time: a set bit means the condition is known true for that row. Its {@code dstValidity} is
 * set where the condition is known, true or false, so an unknown row is null, never false.
 *
 * <p>Status, and why {@code run} returns one: a lowering may be correct only over
 * part of its input domain - the narrowed civil-from-days variant is valid over a bounded day
 * range, and nothing at compile time can bound a column's values. Such a kernel detects the
 * lanes it cannot compute and reports them, rather than publishing an answer it does not have.
 * A non-zero return means <b>this batch's outputs are not valid and the caller must recompute
 * the batch on the row engine</b>; zero means they are. It is a bitmask with a bit per reason a
 * kernel refuses a batch for, one per {@code Analysis.Refusal} (VARKA-304), so a declined batch
 * says which guard fired. A method whose guards all share one reason returns exactly that bit;
 * one that folds guards of several reasons into one mask returns all of their bits, a candidate
 * set, unless {@code VarkaEmitOptions.exactRefusalStatus} gives each reason its own mask. The
 * bits above the reasons' are the evaluator's own ({@code VarkaKernelRunner}), never emitted. A
 * kernel with nothing to report returns zero unconditionally, which costs it one constant.
 *
 * @see VarkaVectorIR
 */
public interface VarkaFusedKernel {

  /**
   * A column-offset day producer's result fell outside the range the calendar lowering reading
   * it is defined over ({@code Refusal.DAY_PRODUCER}). Bit 0, the bit every reason reported before
   * VARKA-304 and the one {@code ChronoVectorOps}' hand-written kernels mirror.
   */
  int STATUS_DAY_PRODUCER = 1;

  /** An {@code add_months} month count outside its magic multiply's range. */
  int STATUS_MONTH_COUNT = 1 << 1;

  /** A {@code make_date} year outside its limits, or under ANSI an invalid date. */
  int STATUS_MAKE_DATE = 1 << 2;

  /** An int lane that overflowed under the FAIL overflow mode. */
  int STATUS_INT_OVERFLOW = 1 << 3;

  /** A {@code GuardedDay}'s day outside the range the compiler re-armed. */
  int STATUS_DAY_RANGE = 1 << 4;

  /**
   * A {@code FAIL} {@code GuardedRange}'s value outside the bounds it carries: a value the row
   * engine throws on, or a date column's day outside the column bound the calendar lowering rests
   * on (VARKA-379). A {@code NULL} one nulls the lane instead and reports nothing.
   */
  int STATUS_VALUE_RANGE = 1 << 5;

  /**
   * A {@code DecimalColumn}'s valid slot that holds no value of its type: a high word that is not
   * the low word's sign, or more digits than the precision (VARKA-347). Spark's column reader
   * raises on such a slot, so the row engine raises it again.
   */
  int STATUS_DECIMAL_SLOT = 1 << 6;

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
    throw VarkaErrors.unsupportedOperation(
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
    throw VarkaErrors.unsupportedOperation(
        getClass().getName() + " is a 32-bit-lane kernel; call the seven-argument run");
  }

  /**
   * How many bytes of scratch a call must pass per row: zero for every kernel that materializes
   * no calendar prefix (VARKA-198), which ignores the address. A caller allocates
   * {@code scratchBytesPerRow() * length} bytes and passes their address to the two overloads
   * below; a kernel with scratch owns none of it, so a call still allocates nothing.
   *
   * <p><b>The contract</b> (VARKA-253): a caller inside a Spark task passes its own scratch,
   * through the forms that take an address, as the evaluator and the warm-up do. The forms
   * without one are for callers outside a task - the suites, the probes and the tools - which on
   * a kernel with scratch get the thread's fallback buffer ({@link VarkaScratch}), kept for the
   * thread's life; inside a task {@link VarkaScratch} refuses them, so a production path cannot
   * come to rely on that buffer unseen.
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
