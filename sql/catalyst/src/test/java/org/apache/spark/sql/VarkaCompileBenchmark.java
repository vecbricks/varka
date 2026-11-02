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

package org.apache.spark.sql;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import scala.collection.immutable.Seq;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.HoursOfTime;
import org.apache.spark.sql.catalyst.expressions.MinutesOfTime;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.SecondsOfTime;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAdmission;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.TimeType;

/**
 * What it costs to *compile* an expression into the IR - the work of the compiler families, before
 * the emitter sees anything - which {@code VarkaEmissionBenchmark} cannot show: that benchmark
 * builds IR by hand and times only {@code VarkaLoopEmitter.emit}, so no change to a family compiler
 * can move it (VARKA-215 9).
 *
 * <p>One case per shape, each a call of {@code VarkaExpressionCompiler.compile} (a projection) or
 * {@code compilePredicate} (a filter's condition) over expressions built once. The shapes are
 * chosen to run each family: the calendar arms, the TIME arms, the conditionals ({@code CASE
 * WHEN}, {@code IF}, {@code coalesce}, {@code greatest}), the predicate compiler (comparisons, the
 * connectives, {@code IN}, a range set, the validity predicates), and a wide projection that mixes
 * them. Every shape is checked to fuse before it is timed, so a case measures compilation and not
 * a decline - which is why the {@code CASE WHEN} has six branches: one of sixteen does not fuse as
 * a whole.
 *
 * <p>Time per call, in the file's "per row" column, since one iteration is one call. The numbers
 * move with the machine; compare two runs of the same day ({@code dev/varka_bench_regen.sh} refuses
 * a busy machine). The first benchmark on the Java adapter ({@link VarkaBenchmarkBase}, VARKA-224).
 *
 * <p>To run this benchmark:
 * <pre>
 *   dev/varka_bench_regen.sh catalyst VarkaCompileBenchmark
 * </pre>
 */
public class VarkaCompileBenchmark extends VarkaBenchmarkBase {

  private static final Attribute D = VarkaSqlResolve.column("d", DataTypes.DateType);
  private static final Attribute D2 = VarkaSqlResolve.column("d2", DataTypes.DateType);
  private static final Attribute I = VarkaSqlResolve.column("i", DataTypes.IntegerType);
  private static final Attribute T = VarkaSqlResolve.column("t", new TimeType(6));
  private static final Seq<Attribute> COLUMNS = seq(List.of(D, D2, I, T));

  private static final int CALLS_PER_ITERATION = 200;

  /** A projection: a title and its expressions. */
  private record Projection(String title, List<Expression> exprs) { }

  /** A filter: a title and its condition. */
  private record Predicate(String title, Expression condition) { }

  /** Keeps the compiler's results live. */
  private volatile int blackhole;
  private int sink;

  private static Expression sql(String text) {
    return VarkaSqlResolve.parse(text, COLUMNS);
  }

  private static Seq<NamedExpression> named(List<Expression> exprs) {
    var out = new ArrayList<NamedExpression>();
    for (int k = 0; k < exprs.size(); k++) {
      out.add(VarkaSqlResolve.named(exprs.get(k), "c" + k));
    }
    return seq(out);
  }

  private static final String CASE_WHEN_6 = "CASE " + IntStream.rangeClosed(1, 6)
      .mapToObj(k -> "WHEN d < DATE'20" + (10 + k) + "-01-01' THEN date_add(d, " + k + ")")
      .collect(Collectors.joining(" ")) + " ELSE d2 END";

  private static List<Projection> projections() {
    var wide = new ArrayList<Expression>();
    for (int k = 1; k <= 60; k++) {
      wide.add(switch (k % 4) {
        case 0 -> sql("year(d) + " + k);
        case 1 -> sql("IF(d < DATE'2020-01-01', date_add(d, " + k + "), d2)");
        case 2 -> sql("add_months(d, " + k + ")");
        default -> sql("coalesce(d, date_add(d2, " + k + "))");
      });
    }
    return List.of(
        new Projection("calendar: year, month, make_date, add_months, last_day, weekofyear",
            List.of(sql("year(d)"), sql("month(d)"), sql("make_date(year(d), month(d), 1)"),
                sql("add_months(d, 3)"), sql("last_day(d)"), sql("weekofyear(d)"))),
        new Projection("TIME: hour, minute, second",
            List.of(new HoursOfTime(T), new MinutesOfTime(T), new SecondsOfTime(T))),
        new Projection("conditionals: IF, coalesce, greatest, CASE WHEN of 6 branches",
            List.of(sql("IF(d < d2, d, d2)"), sql("coalesce(d, d2)"), sql("greatest(d, d2)"),
                sql(CASE_WHEN_6))),
        new Projection("a wide projection of 60 mixed outputs", wide));
  }

  private static List<Predicate> predicates() {
    String eightDates = IntStream.rangeClosed(1, 8).mapToObj(k -> "DATE'2020-0" + k + "-15'")
        .collect(Collectors.joining(", "));
    return List.of(
        new Predicate("predicate: a range, IS NOT NULL and an int comparison", sql(
            "d >= DATE'2020-01-01' AND d < DATE'2021-01-01' AND d IS NOT NULL AND i > 0")),
        new Predicate("predicate: IN over eight dates", sql("d IN (" + eightDates + ")")),
        new Predicate("predicate: a set of three date ranges", sql(
            "(d >= DATE'2020-01-01' AND d <= DATE'2020-03-31') OR "
                + "(d >= DATE'2020-07-01' AND d <= DATE'2020-09-30') OR "
                + "(d >= DATE'2021-01-01' AND d <= DATE'2021-01-31')")),
        new Predicate("predicate: NOT, OR and IS NULL over two dates", sql(
            "NOT (d < d2) OR i IS NULL OR d2 IS NOT NULL")));
  }

  private static void requireFused(boolean fused, String message) {
    if (!fused) {
      throw new IllegalArgumentException(
          "requirement failed: the shape does not fuse, so it would time a decline: " + message);
    }
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    section("compiling an expression into the IR", () -> {
      var benchmark = benchmark("one compile", CALLS_PER_ITERATION)
          .minIterations(5).warmup(Duration.ofSeconds(2)).minTime(Duration.ofSeconds(2)).build();
      for (var p : projections()) {
        var projectList = named(p.exprs());
        for (var e : p.exprs()) {
          var one = named(List.of(e));
          requireFused(VarkaExpressionCompiler.compile(one, COLUMNS).isPresent(),
              p.title() + ": " + e.sql() + ": " + VarkaExpressionCompiler.compilePartial(one,
                  COLUMNS).map(partial -> partial.declines().toString()).orElse("nothing fused"));
        }
        requireFused(VarkaExpressionCompiler.compile(projectList, COLUMNS).isPresent(), p.title());
        benchmark.addCase(p.title(), iteration -> {
          for (int k = 0; k < CALLS_PER_ITERATION; k++) {
            sink += VarkaExpressionCompiler.compile(projectList, COLUMNS).isPresent() ? 1 : 0;
          }
        });
      }
      for (var p : predicates()) {
        requireFused(VarkaExpressionCompiler.compilePredicate(p.condition(), COLUMNS,
            VarkaEmitOptions.DEFAULTS, VarkaAdmission.NO_HOST_REFUSAL).isPresent(),
            p.title());
        benchmark.addCase(p.title(), iteration -> {
          for (int k = 0; k < CALLS_PER_ITERATION; k++) {
            sink += VarkaExpressionCompiler.compilePredicate(p.condition(), COLUMNS,
                VarkaEmitOptions.DEFAULTS, VarkaAdmission.NO_HOST_REFUSAL).isPresent()
                ? 1 : 0;
          }
        });
      }
      benchmark.run();
    });
    blackhole = sink;
  }
}
