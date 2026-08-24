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
 * The vector IR a fused Varka loop is emitted from (milestone 2, task 9). A node is a value over
 * int32 lanes; {@link VarkaLoopEmitter} walks a tree of them post-order, leaving intermediates on
 * the JVM operand stack so they live in vector registers, never in memory.
 *
 * <p>Literal values never appear in the IR. A folded literal occupies a {@link LiteralSlot} - an
 * index into the {@code scalarArgs} array of {@link VarkaFusedKernel#run} - so one emitted class
 * serves every literal a query might use, and a chain's identity is its shape, not its
 * constants. That identity is what a future cross-task cache will key on (milestone 3).
 *
 * <p>Every node reports a {@link LaneType}; only {@code INT} exists in this milestone, and the
 * emitter rejects anything else. The field is carried from day one so that wider lanes extend
 * the IR instead of reworking it.
 *
 * <p>Task 9 defines only what its emitter serves: a chain of day arithmetic over one column.
 * Task 10 adds the remaining nodes (comparisons, blend, the null-skipping ops) as new
 * {@code permits} entries, together with the Catalyst-to-IR compiler; until then IR trees are
 * built by hand in tests.
 */
public sealed interface VarkaVectorIR
    permits VarkaVectorIR.ColumnRef, VarkaVectorIR.LiteralSlot,
            VarkaVectorIR.AddDays, VarkaVectorIR.SubDays {

  /** The lane type a node evaluates to. Only 32-bit int lanes exist in milestone 2. */
  enum LaneType { INT }

  default LaneType laneType() {
    return LaneType.INT;
  }

  /** The input column at {@code ordinal}, loaded once per lane group however often it is used. */
  record ColumnRef(int ordinal) implements VarkaVectorIR {}

  /**
   * The runtime scalar argument at {@code index}, broadcast into every lane once per call,
   * outside the loop.
   */
  record LiteralSlot(int index) implements VarkaVectorIR {}

  /**
   * {@code days + offset}, lane-wise, wrapping on overflow exactly as Spark's {@code DateAdd}
   * does. In task 9 {@code offset} must be a {@link LiteralSlot}.
   */
  record AddDays(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {}

  /** {@code days - offset}, lane-wise; the {@code DateSub} counterpart of {@link AddDays}. */
  record SubDays(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {}
}
