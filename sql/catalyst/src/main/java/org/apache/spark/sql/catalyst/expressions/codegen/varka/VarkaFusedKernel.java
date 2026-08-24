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
 * @see VarkaVectorIR
 */
public interface VarkaFusedKernel {

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
   */
  void run(long[] srcData, long[] srcValidity, int[] srcNullCount,
      long[] dstData, long[] dstValidity, int[] scalarArgs, int length);
}
