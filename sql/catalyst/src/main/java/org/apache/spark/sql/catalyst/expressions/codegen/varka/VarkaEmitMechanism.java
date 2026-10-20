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
 * What the emitter can do to a shape beyond lowering its nodes one by one, as a closed list, so
 * that a suite can say which of them its shapes reached and which they never did (VARKA-280).
 *
 * <p>A green fuzz run says nothing about a mechanism its shapes never reach: the node types are
 * asserted reachable by the IR fuzzer, but the things the emitter decides about a whole shape -
 * how it groups the outputs, what one group shares with another, which guards a body folds,
 * how the size loop reacts, why it declines - are reached or not by what the draws happen to
 * be. QPG (Ba and Rigger, ICSE 2023; {@code m7/READING.md} 11) found more bugs by steering
 * toward unseen query plans than a coverage-guided fuzzer that reached more code; the plan of
 * an emission is what this list records.
 *
 * <p>Each mechanism is noted where the emitter does it, at compile time, into the emission's
 * {@link Analysis#reached}, and the size loop hands the set to the {@link VarkaEmitTrace} when
 * the emission ends, built or declined: the last pass's grouping and bodies, and the loop's
 * reactions and declines over all its passes. A body mechanism is noted where its bytes are
 * emitted, not where a slot is planned for it, since the planner plans slots for bodies and nodes
 * the emission never builds. Nothing here is per batch, and nothing changes a byte the emitter
 * writes.
 *
 * <p>The list leaves out what an option selects on its own - a lowering the options record
 * names, such as the mod-7 form or the calendar's division - since the fuzzers draw every option
 * at random and reach those by construction. What it lists is what the options and the shape
 * decide together.
 */
enum VarkaEmitMechanism {

  // The grouping: how the outputs were split into loop methods.
  ONE_GROUP(Family.GROUPING, "every output in one loop method"),
  SEVERAL_GROUPS(Family.GROUPING, "the outputs split over several loop methods"),
  GROUP_PAST_BUDGET_BY_SHARING(Family.GROUPING,
      "a group of several outputs past the group budget, held together by what they share"),
  GROUP_WIDENED_BY_PREDICTION(Family.GROUPING,
      "a group the cost model's prediction bounded in the group budget's place"),
  STAGED_DRIVER(Family.GROUPING, "a driver whose calls to the groups moved into stages"),

  // What one computation serves more than once.
  SHARED_LOCAL(Family.SHARING, "a node computed once in a body and loaded again from its local"),
  PREFIX_REUSED(Family.SHARING,
      "a calendar node finding its civil-from-days prefix computed by a sibling in its body"),
  PREFIX_STORED(Family.SHARING, "a calendar prefix stored into the scratch for later groups"),
  PREFIX_LOADED(Family.SHARING, "a calendar prefix loaded from the scratch in place of its run"),
  MONTH_STEP_ELIDED(Family.SHARING, "a calendar prefix run without the month step nothing reads"),

  // The validity words.
  BITMAP_PASS(Family.VALIDITY, "an output's validity written whole by the driver's bitmap pass"),
  BITMAP_PASS_DECLINED(Family.VALIDITY,
      "an output the bitmap pass declined because its tree mixes AND and OR"),
  MASKED_ONLY(Family.VALIDITY,
      "a kernel with no dense body: it nulls valid inputs, or a null test skips a side effect"),
  ALL_NULL_SHORTCUT(Family.VALIDITY, "a driver that returns early when every output is null"),
  DEAD_INPUT_WORD(Family.VALIDITY, "a masked body that never reads an input's validity word"),

  // The guards a body folds into its status.
  GUARD_DAY_PRODUCER(Family.GUARDS, "a guard on a column-offset day producer"),
  GUARD_MONTH_COUNT(Family.GUARDS, "a guard on a column month count"),
  GUARD_MAKE_DATE(Family.GUARDS, "a guard on make_date's year and date"),
  GUARD_INT_OVERFLOW(Family.GUARDS, "a guard on checked integer arithmetic"),
  GUARD_DAY_RANGE(Family.GUARDS, "a re-armed day range guard"),
  GUARD_VALUE_RANGE(Family.GUARDS, "a re-armed value range guard"),
  GUARD_UNDER_ARM(Family.GUARDS, "a guard qualified by the IfElse arms its node sits under"),
  GUARD_EXACT_STATUS(Family.GUARDS, "a body folding two or more reasons into masks of their own"),
  GUARD_MIN_MAX(Family.GUARDS,
      "a column's range guard kept as a lane-wise min and max, compared once after the loop"),

  // The constant divisions, by the form taken.
  DIVIDE_MAGIC_LONG(Family.DIVISION, "a long-lane constant division by the magic form"),
  DIVIDE_MULTIPLY_HIGH(Family.DIVISION, "an int-lane constant division by multiply-high"),
  DIVIDE_CONVERTED(Family.DIVISION, "a constant division through a double conversion"),

  // The size loop's reactions to a measurement, and the plan's (VarkaEmitTrace's counters).
  BYTE_REGROUP(Family.SIZE, "a group halved on bytes"),
  CALL_SITE_SPLIT(Family.SIZE, "a group split on call sites"),
  CALL_SITE_ROLLBACK(Family.SIZE, "the call-site splits rolled back"),
  STAGE_SPLIT(Family.SIZE, "a driver split into stages after a build"),
  EXACT_GROUPING_DROPPED(Family.SIZE, "the exact grouping dropped"),
  PREDICTION_DROPPED(Family.SIZE, "the prediction dropped"),
  PLANNED_STAGES(Family.SIZE, "a driver split into stages by the plan before the first build"),
  PLAN_CORRECTED(Family.SIZE, "a reaction correcting the planned build"),
  METHOD_CAP_REFUSAL(Family.SIZE,
      "a method the class-file cap refused, read as the measurement of that method"),

  // Why the shape was refused.
  DECLINE_PLANNED(Family.DECLINE, "declined by the plan before any build, naming a cut"),
  DECLINE_OUTPUT(Family.DECLINE, "declined on an output no regroup makes smaller"),
  DECLINE_CLASS(Family.DECLINE, "declined on a class-wide limit, the driver's or the class's"),
  DECLINE_CONSTANT_POOL(Family.DECLINE, "declined on the constant pool's cap");

  /** The kinds of mechanism, in the order a report lists them. */
  enum Family { GROUPING, SHARING, VALIDITY, GUARDS, DIVISION, SIZE, DECLINE }

  final Family family;
  /** What the mechanism is, as a report names it. */
  final String sentence;

  VarkaEmitMechanism(Family family, String sentence) {
    this.family = family;
    this.sentence = sentence;
  }

  /** The guard mechanism of a refusal reason: exhaustive, so a new reason is named here. */
  static VarkaEmitMechanism guard(Analysis.Refusal reason) {
    return switch (reason) {
      case DAY_PRODUCER -> GUARD_DAY_PRODUCER;
      case MONTH_COUNT -> GUARD_MONTH_COUNT;
      case MAKE_DATE -> GUARD_MAKE_DATE;
      case INT_OVERFLOW -> GUARD_INT_OVERFLOW;
      case DAY_RANGE -> GUARD_DAY_RANGE;
      case VALUE_RANGE -> GUARD_VALUE_RANGE;
    };
  }
}
