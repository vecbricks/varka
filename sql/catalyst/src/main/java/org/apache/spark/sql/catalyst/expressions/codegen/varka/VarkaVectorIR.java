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
 * <p>The IR is a DAG in effect if not in shape: the records carry structural
 * {@code equals}/{@code hashCode}, and the emitter memoizes on them, so a subtree appearing in
 * several outputs is computed once per lane group no matter how the caller built the trees
 * (task 10).
 *
 * <p>Task 11 splits the IR into values and <i>conditions</i>: a {@link Cond} node is
 * mask-valued - per lane a known-true and a known-false bit, SQL's three-valued logic - and is
 * interior only. {@link IfElse#cond} is typed {@code Cond}, so a value cannot appear where a
 * condition belongs; the reverse direction (a condition in a value position, or as an output
 * root) is rejected by the emitter's analysis, since {@code Cond} must extend
 * {@code VarkaVectorIR} for the shared memo machinery to see condition nodes at all.
 */
public sealed interface VarkaVectorIR
    permits VarkaVectorIR.ColumnRef, VarkaVectorIR.LiteralSlot,
            VarkaVectorIR.AddDays, VarkaVectorIR.SubDays, VarkaVectorIR.DateDiff,
            VarkaVectorIR.IfElse, VarkaVectorIR.Greatest, VarkaVectorIR.Least,
            VarkaVectorIR.DayOfWeek, VarkaVectorIR.WeekDay, VarkaVectorIR.Cond {

  /** The lane type a node evaluates to. Only 32-bit int lanes exist in milestone 2. */
  enum LaneType { INT }

  default LaneType laneType() {
    return LaneType.INT;
  }

  /** The comparison a {@link Compare} node performs; lane math is signed int ordering. */
  enum CompareOp { LT, LE, GT, GE, EQ }

  /**
   * A mask-valued interior node (task 11): per lane group it evaluates to a known-true and a
   * known-false mask, and an unknown lane (a null input somewhere below) is neither - which is
   * what makes {@code CASE WHEN}'s null condition fall through to ELSE. Never an output root.
   */
  sealed interface Cond extends VarkaVectorIR
      permits Compare, And, Or, Not {}

  /** The input column at {@code ordinal}, loaded once per lane group however often it is used. */
  record ColumnRef(int ordinal) implements VarkaVectorIR {}

  /**
   * The runtime scalar argument at {@code index}, broadcast into every lane once per call,
   * outside the loop.
   */
  record LiteralSlot(int index) implements VarkaVectorIR {}

  /**
   * {@code days + offset}, lane-wise, wrapping on overflow exactly as Spark's {@code DateAdd}
   * does. The {@code offset} must be a {@link LiteralSlot}.
   */
  record AddDays(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {}

  /** {@code days - offset}, lane-wise; the {@code DateSub} counterpart of {@link AddDays}. */
  record SubDays(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {}

  /**
   * {@code end - start}, lane-wise, over two date operands - Spark's {@code DateDiff}
   * (task 10). Lane math is the same {@code isub} as {@link SubDays}; the difference is at the
   * Spark level, where the result is an {@code IntegerType} day count rather than a date, which
   * the compiler tracks per output so the evaluator allocates the right vector.
   */
  record DateDiff(VarkaVectorIR end, VarkaVectorIR start) implements VarkaVectorIR {}

  /**
   * {@code left OP right} over two date-valued operands (task 11). Null-intolerant: the result
   * is known (true or false) exactly where both operands are valid, unknown elsewhere.
   */
  record Compare(CompareOp op, VarkaVectorIR left, VarkaVectorIR right) implements Cond {}

  /**
   * Three-valued AND: known-true where both sides are known true, known-false where either
   * side is known false.
   */
  record And(Cond left, Cond right) implements Cond {}

  /** Three-valued OR, the dual of {@link And}. */
  record Or(Cond left, Cond right) implements Cond {}

  /** Three-valued NOT: swaps the known-true and known-false masks - why known-false exists. */
  record Not(Cond child) implements Cond {}

  /**
   * SQL's if/else over the {@code cond}'s <i>known-true</i> mask (task 11): a lane takes
   * {@code thenNode} where the condition is known true and {@code elseNode} everywhere else,
   * unknown included. Validity follows the chosen branch lane-wise; nothing is ANDed globally.
   */
  record IfElse(Cond cond, VarkaVectorIR thenNode, VarkaVectorIR elseNode)
      implements VarkaVectorIR {}

  /**
   * Spark's null-skipping {@code greatest} over two operands (task 11): null only where both
   * inputs are null; where one side is null the other's value is taken, so the lane math is a
   * substitute-then-max.
   */
  record Greatest(VarkaVectorIR left, VarkaVectorIR right) implements VarkaVectorIR {}

  /** The {@code least} counterpart of {@link Greatest}. */
  record Least(VarkaVectorIR left, VarkaVectorIR right) implements VarkaVectorIR {}

  /**
   * Spark's {@code dayofweek} (task 11): {@code floorMod(days + 4, 7) + 1}, Sunday = 1 -
   * computed as {@code (floorMod(days, 7) + 4) mod 7 + 1} so the offset can never overflow the
   * int days. An {@code IntegerType} output at the Spark level.
   */
  record DayOfWeek(VarkaVectorIR days) implements VarkaVectorIR {}

  /** Spark's {@code weekday} (task 11): {@code floorMod(days + 3, 7)}, Monday = 0. */
  record WeekDay(VarkaVectorIR days) implements VarkaVectorIR {}
}
