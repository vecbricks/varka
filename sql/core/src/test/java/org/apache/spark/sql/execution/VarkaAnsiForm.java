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

package org.apache.spark.sql.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.SparkThrowable;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.types.ByteType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DateType;
import org.apache.spark.sql.types.DayTimeIntervalType;
import org.apache.spark.sql.types.DoubleType;
import org.apache.spark.sql.types.FloatType;
import org.apache.spark.sql.types.IntegerType;
import org.apache.spark.sql.types.LongType;
import org.apache.spark.sql.types.ShortType;
import org.apache.spark.sql.types.TimeType;
import org.apache.spark.sql.types.TimestampNTZType;
import org.apache.spark.sql.types.YearMonthIntervalType;

/**
 * The ANSI form of the coverage table's rows (VARKA-138, VARKA-317), below the session
 * (VARKA-509). The extremes fixture holds the types' bounds, so under ANSI the rows that can
 * overflow or leave a range raise; each row runs where Spark evaluates it and where Spark skips
 * it - the untaken arm of a CASE, a later COALESCE argument, the right side of a false AND, of a
 * true OR - and the harness holds the kernel to Spark's per row: a batch it answers holds no row
 * Spark raises on, and a batch it declines is the row path's, which raises Spark's own error.
 *
 * <p>At the defaults a skipped position must also be served whole by a kernel, or the check
 * compared Spark with itself; {@link #DECLINED} lists where it is not, with the reason, and a
 * listed position that is served fails as stale. What a configuration of the option matrix
 * fuses is the matrix's own business, so the reach check runs at the defaults only.
 */
public final class VarkaAnsiForm {

  private VarkaAnsiForm() {}

  /** The positions; every one but the first is one Spark skips on both rows. */
  public static final String ALONE = "alone";
  public static final String CASE_ARM = "the untaken arm of a CASE";
  public static final String COALESCE_TAIL = "a later COALESCE argument";
  public static final String FALSE_AND = "the right side of a false AND";
  public static final String TRUE_OR = "the right side of a true OR";

  /**
   * The extremes fixture's {@code rid} at the lane of {@code t}: {@code lrid} for the long lane,
   * {@code xrid} and {@code frid} for the floating ones, {@code rid} for every other, so a guard
   * and the expression it guards share a kernel.
   */
  static String guardOf(DataType t) {
    Optional<LaneType> lane = VarkaTypes.lane(t);
    if (lane.isEmpty()) {
      return "rid";
    }
    return switch (lane.get()) {
      case LONG -> "lrid";
      case DOUBLE -> "xrid";
      case FLOAT -> "frid";
      default -> "rid";
    };
  }

  /**
   * A value of the extremes fixture of {@code t}'s type that never raises, to stand beside an
   * expression of that type in a CASE ({@code caseArm}) or ahead of it in a COALESCE: a column,
   * non-null on both rows. Varka compiles a bare int column as no value, so the int CASE's other
   * arm is {@code year(d2)}; the int COALESCE keeps the bare column, and the declined list says
   * why it is not fused. A narrow CASE arm is the int guard and a narrow branch, which the
   * compiler widens to the int lane and stores narrow (VARKA-329).
   */
  static Optional<String> standIn(DataType t, boolean caseArm) {
    if (t instanceof DateType) {
      return Optional.of("d2");
    } else if (t instanceof IntegerType) {
      return Optional.of(caseArm ? "year(d2)" : "i");
    } else if (t instanceof ByteType) {
      return Optional.of("y2");
    } else if (t instanceof ShortType) {
      return Optional.of("s2");
    } else if (t instanceof LongType) {
      return Optional.of("l2");
    } else if (t instanceof TimestampNTZType) {
      return Optional.of("ntz2");
    } else if (t instanceof DoubleType) {
      return Optional.of("x2");
    } else if (t instanceof FloatType) {
      return Optional.of("f2");
    } else if (t instanceof TimeType) {
      return Optional.of("t2");
    } else if (t instanceof DayTimeIntervalType) {
      return Optional.of("dt2");
    } else if (t instanceof YearMonthIntervalType ym) {
      if (ym.startField() == ym.endField()) {
        return Optional.of(ym.startField() == YearMonthIntervalType.YEAR() ? "ymy" : "ymm");
      }
      return Optional.of("ym");
    }
    return Optional.empty();
  }

  /**
   * A position's query over {@code t}, and for a projection {@code SELECT <x> AS v FROM t} its
   * {@code x}, which {@link #check} plans beside the row's other projections.
   */
  record Written(String sql, Optional<String> projected) {
    static Written projection(String x) {
      return new Written("SELECT " + x + " AS v FROM t", Optional.of(x));
    }

    static Written filter(String where) {
      return new Written("SELECT rid FROM t WHERE " + where, Optional.empty());
    }
  }

  /** Each row of the coverage table as the value {@link #queries} plans for its type. */
  static List<String> values() {
    return Values.ALL;
  }

  /** Read from the table on first use, so the suite is set up before the form reads it. */
  private static final class Values {
    static final List<String> ALL = VarkaCoverageHarnessSuite.rows().stream()
        .map(r -> "(" + r.executable() + ")").toList();
  }

  /**
   * A row's positions and their queries over {@code t}, empty where a position cannot be written
   * for the row. A projection {@code e} runs alone, in the untaken arm of
   * {@code CASE WHEN rid < 0}, after a column in a COALESCE, and compared with a column behind
   * {@code rid < 0}; a predicate {@code p} behind {@code rid < 0 AND} and {@code rid >= 0 OR}.
   * {@code rid} is 0 and 1, so each guard decides every row and Spark never evaluates the guarded
   * side. Where the guard and the guarded side form one Varka expression, the guard is at the
   * expression's lane.
   */
  static Map<String, Optional<Written>> queries(VarkaCoverageHarnessSuite.Row row,
      VarkaHarnessPlans.Fixture fixture) {
    String e = row.executable();
    var out = new LinkedHashMap<String, Optional<Written>>();
    // Only the type and the columns are read, so the plan is remembered across the tests, with
    // every row's planned together on the first ask.
    Expression value = (Expression) CollectionConverters.asJava(
        VarkaHarnessPlans.remembered("(" + e + ")", values(), fixture.columns())).get(0);
    if (row.form().equals("predicate")) {
      // The predicate's own columns: the first one off the int lane picks the guard.
      String guard = "rid";
      for (Attribute a : CollectionConverters.asJava(value.references().toSeq())) {
        String g = guardOf(a.dataType());
        if (!g.equals("rid")) {
          guard = g;
          break;
        }
      }
      out.put(FALSE_AND, Optional.of(Written.filter(guard + " < 0 AND (" + e + ")")));
      out.put(TRUE_OR, Optional.of(Written.filter(guard + " >= 0 OR (" + e + ")")));
    } else {
      DataType t = value.dataType();
      String guard = guardOf(t);
      out.put(ALONE, Optional.of(Written.projection(e)));
      out.put(CASE_ARM, standIn(t, true).map(c ->
          Written.projection("CASE WHEN " + guard + " < 0 THEN " + e + " ELSE " + c + " END")));
      out.put(COALESCE_TAIL, standIn(t, false).map(c ->
          Written.projection("coalesce(" + c + ", " + e + ")")));
      // e = <column>, not e IS NOT NULL: Varka fuses no IS NOT NULL over a computed value.
      out.put(FALSE_AND, standIn(t, false).map(c ->
          Written.filter(guard + " < 0 AND (" + e + ") = " + c)));
    }
    return out;
  }

  /**
   * {@code queries}' plans under the thread's conf, by position: the projections planned
   * together in one run of the optimizer ({@link VarkaHarnessPlans#projections}), each filter
   * alone, and none for a position that cannot be written.
   */
  static Map<String, VarkaHarnessPlans.Query> plans(Map<String, Optional<Written>> queries,
      VarkaHarnessPlans.Fixture fixture) {
    var positions = new ArrayList<String>();
    var texts = new ArrayList<String>();
    for (Map.Entry<String, Optional<Written>> q : queries.entrySet()) {
      Optional<String> x = q.getValue().flatMap(Written::projected);
      if (x.isPresent()) {
        positions.add(q.getKey());
        texts.add(x.get());
      }
    }
    var lists = VarkaHarnessPlans.projections(texts, fixture.columns());
    var out = new LinkedHashMap<String, VarkaHarnessPlans.Query>();
    for (Map.Entry<String, Optional<Written>> q : queries.entrySet()) {
      int at = positions.indexOf(q.getKey());
      if (at >= 0) {
        out.put(q.getKey(), new VarkaHarnessPlans.Query.Projection(lists.get(at)));
      } else if (q.getValue().isPresent()) {
        out.put(q.getKey(), VarkaHarnessPlans.query(q.getValue().get().sql(), fixture.columns()));
      }
    }
    return out;
  }

  /**
   * The positions where Varka does not serve a row's expression, with the reason, keyed by the
   * row's {@code sql} and the position, as {@code sql + "\t" + position}.
   */
  public static final Map<String, String> DECLINED = declined();

  private static Map<String, String> declined() {
    var out = new HashMap<String, String>();
    String intCoalesce = "Varka's COALESCE takes only a bare date column before its last "
        + "operand, and compiles no bare int column as a value (m9/SCOPE.md item 55)";
    for (String sql : List.of("datediff(d2, d)", "unix_date(d)", "year(d)", "month(d)",
        "day(d)", "quarter(d)", "dayofyear(d)", "dayofweek(d)", "weekday(d)",
        "extract(DAYOFWEEK_ISO FROM d)", "weekofyear(d)", "extract(YEAROFWEEK FROM d)",
        "extract(YEAR FROM ym)", "extract(YEAR FROM ym) - 1", "i + 1", "i - 1", "year(d) * 2",
        "-i", "year(d) + i", "CAST(ymm AS INT)", "CAST(ymy AS INT)", "hour(t)", "minute(t2)",
        "second(t)", "hour(t) + 1", "CAST(time_to_millis(t) AS INT)", "i & 255", "i | 4096",
        "i ^ 21845", "~i", "i << 3", "shiftright(i, i)", "i >>> 28", "bit_count(i)",
        "bit_count(l)", "bit_count(l) + 1", "y1 + i", "CAST(s1 AS INT)",
        "CAST(y1 AS INT) * CAST(y2 AS INT)", "CAST(s1 AS INT) * CAST(s2 AS INT)", "try_add(i, 1)",
        "try_subtract(i, 1)")) {
      out.put(sql + "\t" + COALESCE_TAIL, intCoalesce);
    }
    // CAST(i AS INTERVAL DAY) throws past INTERVAL_DAY_LIMIT_DAYS, so the compiler bounds i and
    // the evaluator checks the bound before the kernel runs; the bound belongs to the column,
    // not to the arm reading it, and Int.MaxValue declines the batch wherever the expression sits.
    String columnBound = "the offset column's bound is checked per batch whichever arm reads "
        + "it, and Int.MaxValue is past it, so the batch declines (m9/SCOPE.md item 96)";
    for (String position : List.of(CASE_ARM, COALESCE_TAIL, FALSE_AND)) {
      out.put("d + CAST(i AS INTERVAL DAY)\t" + position, columnBound);
    }
    // A check that condemns the batch is qualified by a CASE arm's condition (guardUnderArm) but
    // not by the left side of an AND: the kernel computes the right side on every lane, and the
    // extremes row's lane condemns the batch although rid < 0 already rejected it.
    String unqualified = "a check on the right side of an AND condemns the batch from a lane "
        + "the left side rejected (m9/SCOPE.md item 96)";
    for (String sql : List.of("make_date(2021, i, 1)", "add_months(d, i)", "d + ym", "d - ym",
        "ym - ymm", "ym + ym", "i + 1", "year(d) + i", "y1 + y2", "y1 - y2", "y1 * y2", "-y1",
        "abs(y1)", "s1 + s2", "s1 - s2", "s1 * s2", "-s1", "abs(s1)", "x / x2")) {
      out.put(sql + "\t" + FALSE_AND, unqualified);
    }
    return Map.copyOf(out);
  }

  /**
   * The error conditions the coverage rows raise alone on the extremes fixture under ANSI, each
   * with the first row of the table that raises it: the rows the end-to-end suite runs through a
   * session, one a condition, so that each error reaches a user as Spark raises it.
   * {@code VarkaCoverageHarnessSuite} holds the list to the table both ways.
   */
  public static final Map<String, String> RAISES = Map.ofEntries(
      Map.entry("ARITHMETIC_OVERFLOW", "add_months(d, i)"),
      Map.entry("BINARY_ARITHMETIC_OVERFLOW", "y1 + y2"),
      Map.entry("CAST_OVERFLOW", "d + CAST(i AS INTERVAL DAY)"),
      Map.entry("DATETIME_FIELD_OUT_OF_BOUNDS.WITHOUT_SUGGESTION", "make_date(2021, i, 1)"),
      Map.entry("DIVIDE_BY_ZERO", "x / x2"),
      Map.entry("INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION", "ym - ymm"));

  /**
   * The condition Spark's interpreter raises evaluating {@code expression} over the fixture's
   * rows under the thread's conf, or empty where it raises none.
   */
  static Optional<String> sparkCondition(String expression, VarkaHarnessPlans.Fixture fixture) {
    return condition(() -> VarkaDomains.sparkValues(expression, fixture));
  }

  /**
   * {@link #sparkCondition} of each of {@code expressions}, the expressions planned together
   * ({@link VarkaDomains#sparkValues(List, VarkaHarnessPlans.Fixture)}) and each evaluated alone,
   * so a condition is still the one its own expression raises.
   */
  static List<Optional<String>> sparkConditions(List<String> expressions,
      VarkaHarnessPlans.Fixture fixture) {
    var out = new ArrayList<Optional<String>>();
    for (Supplier<List<InternalRow>> values :
        VarkaDomains.sparkValues(expressions, fixture)) {
      out.add(condition(values));
    }
    return out;
  }

  private static Optional<String> condition(Supplier<List<InternalRow>> values) {
    try {
      values.get();
      return Optional.empty();
    } catch (RuntimeException e) {
      if (e instanceof SparkThrowable t && t.getCondition() != null) {
        return Optional.of(t.getCondition());
      }
      throw e;
    }
  }

  /**
   * The ANSI form for {@code row} on the extremes fixture, under the thread's conf, which must
   * have ANSI on; {@code strict} adds the reach check, at the defaults.
   */
  public static List<String> check(VarkaCoverageHarnessSuite.Row row,
      VarkaHarnessPlans.Fixture fixture, SQLConf conf, boolean strict) {
    var out = new ArrayList<String>();
    Map<String, Optional<Written>> queries = queries(row, fixture);
    Map<String, VarkaHarnessPlans.Query> plans = plans(queries, fixture);
    for (Map.Entry<String, Optional<Written>> q : queries.entrySet()) {
      String position = q.getKey();
      String declined = DECLINED.get(row.sql() + "\t" + position);
      if (q.getValue().isEmpty()) {
        if (strict && declined == null) {
          out.add(position + ": no column of the expression's type, and not on the declined "
              + "list");
        }
        continue;
      }
      String sql = q.getValue().get().sql();
      VarkaHarnessPlans.Query plan = plans.get(position);
      VarkaExpressionHarness.Outcome outcome = switch (plan) {
        case VarkaHarnessPlans.Query.Projection p ->
            VarkaExpressionHarness.projection(position + ": " + sql, p.list(), fixture, conf);
        case VarkaHarnessPlans.Query.Selection s ->
            VarkaExpressionHarness.filter(position + ": " + sql, s.condition(), fixture, conf);
        case VarkaHarnessPlans.Query.Constant c -> null;
      };
      if (outcome != null) {
        out.addAll(outcome.findings);
      }
      boolean served = outcome != null && outcome.served();
      if (!strict || position.equals(ALONE)) {
        continue;
      }
      if (served && declined != null) {
        out.add(position + ": " + sql + "\nserved by a kernel, but on the declined list: remove "
            + "the stale entry");
      } else if (!served && declined == null) {
        out.add(position + ": " + sql + "\nnot served by a kernel ("
            + (outcome == null ? "the optimizer left no expression"
                : outcome.answered + " answered, " + outcome.declined + " declined, "
                    + outcome.residual + " residual")
            + "), and not on the declined list");
      }
    }
    return out;
  }
}
