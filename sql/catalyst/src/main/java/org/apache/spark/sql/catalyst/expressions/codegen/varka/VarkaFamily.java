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

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The expression families of the compiler that a session can switch off one at a time
 * (VARKA-318): the families of the chain {@code VarkaNodeCompiler} dispatches every node
 * through, less the date leaves, which read columns and fuse nothing of their own, and the
 * predicate family, whose nodes {@code VarkaConditionCompiler.compileCond} lowers outside the
 * chain. A family that is off declines every node it would claim, with {@link #offReason()} as
 * the reason, so the entry around that node runs on Spark's row engine and the rest of the
 * projection still fuses.
 *
 * <p>The switch is {@link #switchKey()}, a session configuration declared in {@code SQLConf}, and
 * every message about a family names it, as StarRocks's "set jit_level = 0 to disable jit and
 * retry" names its own: the decline reason, the planning error a failure inside the family raises
 * ({@link #retry()}), and the {@code families:} line of the context record a kernel failure's
 * warning carries ({@link VarkaErrorContext}).
 *
 * <p>The switch covers the nodes the family <em>claims</em>, wherever they sit, so each node of a
 * query is under exactly one switch: a node of the family that another family's lowering builds
 * or reaches directly - the calendar's negated interval in {@code d - ym}, its years-to-months
 * cast, a comparison in the condition of an {@code IF} or a {@code CASE WHEN} branch - asks its
 * own family's switch. Helper code two families share and that builds no node of its own - the
 * overflow decision of the int and interval arithmetic, the pick fold {@code greatest} and
 * {@code least} borrow from the condition family, the validity test {@code coalesce} folds onto -
 * runs under the switch of the node it serves. A column or literal read, and a cast of one that
 * lowers to the bare read, is no family's.
 */
public enum VarkaFamily {
  /** {@code VarkaChronoCompiler}. */
  CALENDAR("calendar", "calendar", "day arithmetic (date_add, date_sub, datediff, unix_date,"
      + " date_from_unix_date), the calendar fields (year to weekofyear), make_date, last_day,"
      + " next_day, trunc, add_months and a date plus a year-month interval"),
  /** {@code VarkaIntervalCompiler}. */
  INTERVAL("interval", "interval", "year-month interval arithmetic, negation and abs,"
      + " make_ym_interval, the year and month extractions, the multiply by an int and the"
      + " casts between year-month intervals and ints"),
  /** {@code VarkaTimeCompiler}. */
  TIME("time", "time", "the TIME functions (hour, minute, second, time_trunc, time_diff, the"
      + " conversions to and from micros and millis, a time plus a day-time interval) and the"
      + " casts to TIME and to a day-time interval"),
  /** {@code VarkaConditionCompiler.compileCond}, reached outside the chain. */
  PREDICATE("predicate", "predicate", "the comparisons, IN over dates and year-month intervals,"
      + " IS NULL and IS NOT NULL, AND, OR and NOT, wherever they sit: a filter's predicates, so"
      + " that no filter fuses, and the conditions of IF and CASE WHEN"),
  /** {@code VarkaConditionCompiler.arm}. */
  CONDITION("condition", "condition", "the conditionals IF, CASE WHEN and coalesce (the"
      + " comparisons in their conditions are the predicate family's)"),
  /** The int arithmetic arm of {@code VarkaNodeCompiler}, the int-bigint casts among it. */
  INT_ARITHMETIC("int arithmetic", "intArithmetic", "+, -, * and unary minus over int"
      + " operands, checked under ANSI, greatest and least, and the casts between int and"
      + " bigint");

  /** The prefix every family's switch shares. */
  public static final String SWITCH_PREFIX = "spark.sql.codegen.varka.family.";

  private final String label;
  private final String key;
  private final String covers;

  VarkaFamily(String label, String key, String covers) {
    this.label = label;
    this.key = key;
    this.covers = covers;
  }

  /**
   * What the family covers, in the words its switch's documentation uses: the one copy, which the
   * {@code SQLConf} entry's doc and {@code docs/sql-varka.md}'s generated switch table render.
   */
  public String covers() {
    return covers;
  }

  /** The family's name in prose and in the compiler's chain, e.g. {@code int arithmetic}. */
  public String label() {
    return label;
  }

  /**
   * The family's name inside its switch, {@code spark.sql.codegen.varka.family.<key>.enabled},
   * e.g. {@code intArithmetic}: what the coverage table's switch column prints.
   */
  public String key() {
    return key;
  }

  /** The configuration that switches the family off when false. */
  public String switchKey() {
    return SWITCH_PREFIX + key + ".enabled";
  }

  /** The reason every node of the family declines with while its switch is off. */
  public String offReason() {
    return "Varka's " + label + " family is switched off (" + switchKey() + "=false)";
  }

  /** What a failure inside the family asks of the user who wants the query to run meanwhile. */
  public String retry() {
    return "To plan the query with Varka's " + label + " family on Spark's row engine, set "
        + switchKey() + " to false and retry.";
  }

  /**
   * {@code families} as the context record's {@code families:} line renders them: each family's
   * label and its switch, in declaration order, comma-separated; empty for none.
   */
  public static String render(Collection<VarkaFamily> families) {
    return List.of(values()).stream()
        .filter(families::contains)
        .map(f -> f.label + " (" + f.switchKey() + ")")
        .collect(Collectors.joining(", "));
  }
}
