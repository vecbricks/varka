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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.InterpretedUnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.PartialVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaInputBound;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChrono;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaGuardSpecs;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaGuardSpecs.Verdict;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser$;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.Project;
import org.apache.spark.sql.execution.varka.VarkaFallbackAccounting;
import org.apache.spark.sql.execution.varka.VarkaKernelRunner;
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
 * The domains of the coverage table's guarded rows (VARKA-279), below the session (VARKA-509).
 * A row whose kernel can decline a batch names in the table the predicate inside which it
 * answers. Each such row runs over the edges fixture, which straddles every guard's bound, cut
 * as TLP cuts a table - the rows inside the domain, outside it and where it is unknown, each
 * partition a fixture of its own - as a projection and as a filter. Inside and under IS NULL
 * the kernel serves every batch and declines nothing; outside, every batch declines; and each
 * batch's status is the one {@code VarkaGuardSpecs}' verdict on its rows calls for, exact under
 * {@code exactRefusalStatus}. The answers are the harness's: Spark's on every batch a kernel
 * answers.
 *
 * <p>The harness hands the evaluator each batch itself, so a batch's status is its own, read off
 * the decline the kernel threw: no JFR recording, and no batch hidden behind a row path that
 * raised before reaching it.
 */
public final class VarkaDomains {

  private VarkaDomains() {}

  // ---------------------------------------------------------------------------------------------
  // The edges fixture
  // ---------------------------------------------------------------------------------------------

  /** The columns an edges row sets, with the mixed fixture's first row's values. */
  private static final Map<String, String> DEFAULTS = defaults();

  private static Map<String, String> defaults() {
    var out = new LinkedHashMap<String, String>();
    out.put("d", "DATE'2024-01-31'");
    out.put("d2", "DATE'2024-02-29'");
    out.put("i", "3");
    out.put("m", "0");
    out.put("y", "0");
    out.put("mm", "0");
    out.put("t", "TIME'12:34:56.789'");
    out.put("t2", "TIME'00:00:00'");
    out.put("dt", "INTERVAL '1 02:03:04.5' DAY TO SECOND");
    out.put("y1", "3");
    out.put("y2", "4");
    out.put("s1", "300");
    out.put("s2", "-400");
    return out;
  }

  /** An edges row: {@link #DEFAULTS} with {@code overrides}, pairs of a column and its value. */
  private static String row(String... overrides) {
    var v = new LinkedHashMap<>(DEFAULTS);
    for (int k = 0; k < overrides.length; k += 2) {
      if (v.put(overrides[k], overrides[k + 1]) == null) {
        throw new IllegalArgumentException("no column " + overrides[k]);
      }
    }
    return "(" + v.get("d") + ", " + v.get("d2") + ", " + v.get("i") + ", " + v.get("m") + ", "
        + v.get("y") + ", " + v.get("mm") + ", CAST('5000000000' AS BIGINT), "
        + "CAST('1' AS BIGINT), " + v.get("t") + ", " + v.get("t2") + ", "
        + v.get("dt") + ", INTERVAL '0 00:00:00' DAY TO SECOND, "
        + "CAST(" + v.get("y1") + " AS TINYINT), CAST(" + v.get("y2") + " AS TINYINT), "
        + "CAST(" + v.get("s1") + " AS SMALLINT), CAST(" + v.get("s2") + " AS SMALLINT))";
  }

  /**
   * A row whose {@code ym} is {@code months}, through the view's {@code make_ym_interval(y, mm)};
   * Java's truncating division and remainder agree in sign, so the two parts add back to the
   * count.
   */
  private static String ymRow(long months, String m) {
    return row("m", m, "y", Long.toString(months / 12), "mm", Long.toString(months % 12));
  }

  private static List<Long> around(long lo, long hi) {
    return List.of(lo - 1, lo, hi, hi + 1);
  }

  private static String day(long v) {
    return "date_from_unix_date(" + v + ")";
  }

  /**
   * The fixture the guarded rows' partitions are cut from: for every guard the table's rows
   * carry, its bound and one past it on both sides, in the column the guard reads. {@code i} at
   * the int extremes and one in from each, at the day-interval limit, at the month range and at
   * the months of {@code make_date}; {@code d} of year 9999 beside the {@code i} that takes
   * {@code year(d) + i} to the int's top and one past, and {@code d} at the column bound's ends
   * and one past each (VARKA-379), and at the days a DATE converts to TIMESTAMP_NTZ without
   * overflow and one past each; the TIMESTAMP_NTZ values a day inside those days, {@code ntz}
   * near the long's top beside {@code ntz2} near its bottom, whose difference and whose sum with
   * a day overflow, {@code ntz} near the bottom beside the greatest interval, whose difference
   * overflows, and the least interval, whose negation does, also beside a null {@code ntz}, and
   * a {@code d} past those days beside a null {@code ntz2}; {@code ym} at the month range on both
   * signs, at the one count whose negation overflows and one in from it, and at the counts whose
   * difference with {@code ymm} leaves the int, {@code ymm} at its most negative; a time at each
   * end of the day; the narrow lanes' bounds. Then rows null in those columns, one column group at
   * a time and all at once, which the {@code IS NULL} partitions select. The other columns hold
   * the mixed fixture's first row.
   */
  static String edgesSql() {
    long dayLimit = VarkaChrono.INTERVAL_DAY_LIMIT_DAYS;
    long minMonths = VarkaChrono.MONTH_ARITH_MIN_MONTHS;
    long maxMonths = VarkaChrono.MONTH_ARITH_MAX_MONTHS;
    long lastNtzDay = VarkaCoverageDifferentialSuite$.MODULE$.lastNtzDay();
    var ints = new ArrayList<Long>(List.of((long) Integer.MIN_VALUE, Integer.MIN_VALUE + 1L,
        Integer.MAX_VALUE - 1L, (long) Integer.MAX_VALUE));
    ints.addAll(around(-dayLimit, dayLimit));
    ints.addAll(around(minMonths, maxMonths));
    ints.addAll(around(1, 12));
    var rows = new ArrayList<String>();
    ints.forEach(i -> rows.add(row("i", Long.toString(i))));
    for (long i : List.of(Integer.MAX_VALUE - 9999L, Integer.MAX_VALUE - 9998L)) {
      rows.add(row("d", "DATE'9999-12-31'", "i", Long.toString(i)));
    }
    around(VarkaChrono.CONTRACT_MIN_DAYS, VarkaChrono.CONTRACT_MAX_DAYS)
        .forEach(v -> rows.add(row("d", day(v))));
    around(-lastNtzDay, lastNtzDay).forEach(v -> rows.add(row("d", day(v))));
    rows.add(row("d", day(lastNtzDay - 1), "d2", day(-(lastNtzDay - 1))));
    rows.add(row("d", day(-(lastNtzDay - 1)), "t", "TIME'00:00:00'",
        "dt", "INTERVAL '106751991 04:00:54.775807' DAY TO SECOND"));
    rows.add(row("dt", "INTERVAL '-106751991 04:00:54.775808' DAY TO SECOND"));
    // Each guard on one operand alone beside a null in the other: the guard declines the batch
    // though the row's value is null.
    rows.add(row("d", "NULL", "dt", "INTERVAL '-106751991 04:00:54.775808' DAY TO SECOND"));
    rows.add(row("d2", "NULL", "d", day(lastNtzDay + 1)));
    var months = new ArrayList<>(around(minMonths, maxMonths));
    months.addAll(around(-maxMonths, -minMonths));
    months.forEach(n -> rows.add(ymRow(n, "0")));
    rows.addAll(List.of(ymRow(Integer.MIN_VALUE, "0"), ymRow(Integer.MIN_VALUE, "1"),
        ymRow(Integer.MIN_VALUE + 1L, "0"), ymRow(Integer.MAX_VALUE, "0"),
        ymRow(Integer.MAX_VALUE, "-1"), row("m", Integer.toString(Integer.MIN_VALUE)),
        row("t", "TIME'00:00:00'", "t2", "TIME'23:59:59.999999'"),
        row("t", "TIME'23:59:59.999999'", "t2", "TIME'00:00:00.000001'"),
        // A sum and a product that leave the narrow type, a difference that leaves it in the
        // other direction, and the minimum that negation and abs refuse. The short product needs
        // a row of its own inside the range: the other rows' 300 * -400 is out of it.
        row("s1", "100", "s2", "3"),
        row("y1", "-128", "y2", "-128", "s1", "-32768", "s2", "-32768"),
        row("y1", "127", "y2", "-128", "s1", "32767", "s2", "-32768"),
        row("y1", "NULL", "y2", "NULL", "s1", "NULL", "s2", "NULL"),
        "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, "
            + "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
        row("i", "NULL"), row("d", "NULL"), row("m", "NULL", "y", "NULL", "mm", "NULL"),
        row("t", "NULL", "t2", "NULL")));
    return VarkaCoverageDifferentialSuite$.MODULE$.fixtureView(
        CollectionConverters.asScala(rows).toList(), "");
  }

  // ---------------------------------------------------------------------------------------------
  // The total domains
  // ---------------------------------------------------------------------------------------------

  /**
   * Rows whose outside partition is empty by construction - the guard's bound is the column
   * type's own range, or the guarded value cannot reach the bound from any value of the column -
   * with the reason. Checked both ways: an unlisted row with no row outside fails, so the check
   * cannot pass on a partition that selects nothing, and a listed row with rows outside fails as
   * stale.
   */
  static final Map<String, String> TOTAL = Map.ofEntries(
      Map.entry("extract(YEAR FROM ym) - 1",
          "the quotient is within +/-178956970, so the checked subtract cannot overflow"),
      Map.entry("time_from_millis(time_to_millis(t2))",
          "a TIME's millisecond count is inside the day by construction"),
      Map.entry("time_from_micros(time_to_micros(t))",
          "a TIME's microsecond count is inside the day by construction"),
      Map.entry("t + INTERVAL '0 00:00:00' DAY TO SECOND",
          "a zero interval leaves the sum where the time was, inside the day"),
      Map.entry("CAST(time_to_millis(t) AS INT)",
          "a day's milliseconds, under 86400000, always fit an int"),
      Map.entry("CAST(time_to_millis(t) AS INT) > i",
          "a day's milliseconds, under 86400000, always fit an int"),
      Map.entry("hour(t) + 1", "an hour is at most 23, so the checked add cannot overflow"));

  // ---------------------------------------------------------------------------------------------
  // The check
  // ---------------------------------------------------------------------------------------------

  /** What a row's domain check found, and what it saw, for the test's report. */
  record Result(List<String> findings, List<String> answered, List<String> declined,
      int mayBatches) {
    String report() {
      return String.join("\n\n", findings) + "\n\nanswered: " + String.join(", ", answered)
          + "\ndeclined: " + (declined.isEmpty() ? "nothing" : String.join(", ", declined))
          + "\nbatches whose verdict leaves a guard free to fire: " + mayBatches;
    }
  }

  /** One way a row runs: a projection list, or a filter's condition. */
  private record Form(String name, @Nullable Seq<NamedExpression> list,
      @Nullable Expression condition) {}

  /**
   * {@code expression}'s value on each of {@code fixture}'s rows, by Spark's interpreter, over the
   * plan the optimizer leaves: a stack of projections over the scan, where a domain's
   * {@code BETWEEN} puts its operand in a projection of its own below the comparison.
   */
  static List<InternalRow> sparkValues(String expression, VarkaHarnessPlans.Fixture fixture) {
    NamedExpression alias = VarkaSqlResolve.named(
        CatalystSqlParser$.MODULE$.parseExpression(expression), "v");
    LogicalPlan plan = VarkaHarnessPlans.optimize(new Project(
        CollectionConverters.asScala(List.of(alias)).toList(),
        new VarkaHarnessScan(fixture.columns())));
    var projects = new ArrayList<Project>();
    while (plan instanceof Project p) {
      projects.add(0, p);
      plan = p.child();
    }
    if (!(plan instanceof VarkaHarnessScan) || projects.isEmpty()) {
      throw new IllegalArgumentException(expression + " is no projection over the scan:\n"
          + plan.treeString());
    }
    return evaluate(projects, fixture);
  }

  /**
   * {@link #sparkValues(String, VarkaHarnessPlans.Fixture)} of each of {@code expressions}, each
   * to be evaluated when asked, so an error is its own expression's. The expressions are planned
   * together ({@link VarkaHarnessPlans#together}), where the list comes out as one projection of
   * one entry an expression over the scan, each the entry it would be alone; an expression whose
   * plan is a stack of projections, or whose planning raises, is planned alone when asked.
   */
  static List<Supplier<List<InternalRow>>> sparkValues(List<String> expressions,
      VarkaHarnessPlans.Fixture fixture) {
    List<Seq<NamedExpression>> planned = VarkaHarnessPlans.together(expressions, fixture.columns());
    var out = new ArrayList<Supplier<List<InternalRow>>>();
    for (int i = 0; i < expressions.size(); i++) {
      Seq<NamedExpression> list = planned.get(i);
      String expression = expressions.get(i);
      out.add(list == null ? () -> sparkValues(expression, fixture)
          : () -> evaluate(List.of(new Project(list, new VarkaHarnessScan(fixture.columns()))),
              fixture));
    }
    return out;
  }

  /** The fixture's rows through {@code projects}, the lowest first, by Spark's interpreter. */
  private static List<InternalRow> evaluate(List<Project> projects,
      VarkaHarnessPlans.Fixture fixture) {
    List<InternalRow> rows = fixture.rows();
    for (Project p : projects) {
      var bound = new ArrayList<Expression>();
      for (NamedExpression e : CollectionConverters.asJava(p.projectList())) {
        bound.add(VarkaSqlResolve.bind((Expression) e, p.child().output()));
      }
      UnsafeProjection projection = InterpretedUnsafeProjection.createProjection(
          CollectionConverters.asScala(bound).toList());
      var out = new ArrayList<InternalRow>(rows.size());
      for (InternalRow row : rows) {
        out.add(projection.apply(row).copy());
      }
      rows = out;
    }
    return rows;
  }

  /**
   * The domain check for one guarded row on {@code edges}, under the thread's conf and the
   * current emit options: its three partitions, each as a projection of the expression and as a
   * filter comparing it with a column of its type (a predicate row as the filter it is). And
   * first, on Spark, that each partition has rows, the outside one unless the domain is total.
   */
  static Result check(VarkaCoverageHarnessSuite.Row row, VarkaHarnessPlans.Fixture edges,
      SQLConf conf) {
    String domain = row.domain().orElseThrow();
    var findings = new ArrayList<String>();
    var answered = new ArrayList<String>();
    var declined = new ArrayList<String>();
    int mayBatches = 0;
    // Spark's value of the domain on each row cuts the fixture: each row has exactly one.
    List<InternalRow> truth = sparkValues("(" + domain + ")", edges);
    Map<String, List<InternalRow>> parts = new LinkedHashMap<>();
    for (String p : List.of("inside", "outside", "null")) {
      parts.put(p, new ArrayList<>());
    }
    for (int r = 0; r < truth.size(); r++) {
      InternalRow v = truth.get(r);
      parts.get(v.isNullAt(0) ? "null" : v.getBoolean(0) ? "inside" : "outside")
          .add(edges.rows().get(r));
    }
    if (parts.get("inside").isEmpty()) {
      findings.add("no row inside the domain");
    }
    if (parts.get("null").isEmpty()) {
      findings.add("no row where the domain is unknown");
    }
    String reason = TOTAL.get(row.sql());
    List<InternalRow> outside = parts.get("outside");
    if (reason == null && outside.isEmpty()) {
      findings.add("no row outside the domain, and the row is not listed as a total domain");
    } else if (reason != null && !outside.isEmpty()) {
      findings.add("rows outside the domain (rid " + rids(outside, edges) + "), but the row is "
          + "listed as a total domain: " + reason);
    }
    Seq<Attribute> columns = edges.columns();
    String e = row.executable();
    var forms = new ArrayList<Form>();
    if (row.form().equals("predicate")) {
      forms.add(new Form("a filter", null, VarkaHarnessPlans.condition(e, columns)));
    } else {
      Seq<NamedExpression> list = VarkaHarnessPlans.projection(e, columns);
      DataType t = ((Expression) list.head()).dataType();
      String c = VarkaAnsiForm.standIn(t, false).orElseThrow(
          () -> new IllegalStateException("no column of type " + t + " beside " + e));
      forms.add(new Form("a projection", list, null));
      forms.add(new Form("a filter", null,
          VarkaHarnessPlans.condition("(" + e + ") = " + c, columns)));
    }
    VarkaEmitOptions options = VarkaColumnarToRowExec$.MODULE$.currentEmitOptions();
    // An empty partition - a total domain's outside - has no batch for a kernel to serve or
    // decline, so nothing runs over it; its emptiness is what the list above polices.
    for (Map.Entry<String, List<InternalRow>> part : parts.entrySet()) {
      if (part.getValue().isEmpty()) {
        continue;
      }
      String partition = part.getKey();
      var fixture = new VarkaHarnessPlans.Fixture(columns, part.getValue());
      for (Form form : forms) {
        String where = partition + " the domain, in " + form.name();
        String context = row.sql() + ", " + where;
        VarkaExpressionHarness.Outcome outcome = form.list() != null
            ? VarkaExpressionHarness.projection(context, form.list(), fixture, conf)
            : VarkaExpressionHarness.filter(context, form.condition(), fixture, conf);
        findings.addAll(outcome.findings);
        Optional<CompiledVarkaProjection> kernel = kernel(form, columns, options, findings,
            where);
        if (kernel.isEmpty()) {
          continue;
        }
        List<Verdict> verdicts = verdicts(kernel.get(), options, fixture, outcome.batches);
        mayBatches += (int) verdicts.stream().filter(v -> v.may() != 0).count();
        boolean anyMust = verdicts.stream().anyMatch(v -> v.must() != 0);
        // The domain itself is what is held here: no batch inside it or under IS NULL may be one
        // the verdict condemns, and every batch outside it must be, so a domain that is too wide
        // or too narrow disagrees with the guards' own verdict.
        if (!partition.equals("outside") && verdicts.stream().anyMatch(v -> !v.answers())) {
          findings.add(where + ": the domain admits batches the verdict condemns: "
              + render(verdicts));
        }
        if (partition.equals("outside") && !verdicts.stream().allMatch(v -> v.must() != 0)) {
          findings.add(where + ": the domain excludes batches the verdict answers: "
              + render(verdicts));
        }
        for (int b = 0; b < verdicts.size(); b++) {
          VarkaExpressionHarness.Batch batch = outcome.batches.get(b);
          Verdict v = verdicts.get(b);
          if (batch.status() == VarkaExpressionHarness.Batch.FELL_BACK) {
            findings.add(where + ", rows " + batch.start() + " to "
                + (batch.start() + batch.rows() - 1) + ": the evaluator ran no kernel");
          } else if (!v.admits(batch.status())) {
            findings.add(where + ", rows " + batch.start() + " to "
                + (batch.start() + batch.rows() - 1) + ": "
                + (batch.status() == VarkaExpressionHarness.Batch.ANSWERED ? "answered"
                    : "declined with " + VarkaFallbackAccounting.describeStatus(batch.status()))
                + " where the verdict is " + render(v));
          }
        }
        if (!anyMust && !outcome.served()) {
          findings.add(where + ": not served whole by a kernel (" + outcome.answered
              + " answered, " + outcome.declined + " declined, " + outcome.residual
              + " outputs residual)");
        }
        if (anyMust) {
          declined.add(where + " (" + verdicts.stream().filter(v -> v.must() != 0)
              .map(v -> VarkaFallbackAccounting.describeStatus(v.must()))
              .collect(Collectors.joining("; ")) + ")");
        } else {
          answered.add(where);
        }
      }
    }
    return new Result(findings, answered, declined, mayBatches);
  }

  private static String rids(List<InternalRow> rows, VarkaHarnessPlans.Fixture fixture) {
    int rid = fixture.ordinal("rid");
    var ids = new TreeSet<Integer>();
    rows.forEach(r -> ids.add(r.getInt(rid)));
    return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
  }

  /** A verdict in the accounting's words: the bits a batch must decline with, and may. */
  private static String render(Verdict v) {
    if (v.answers()) {
      return "answer";
    }
    return (v.must() != 0 ? VarkaFallbackAccounting.describeStatus(v.must()) : "nothing")
        + (v.may() != 0 ? " (may " + VarkaFallbackAccounting.describeStatus(v.may()) + ")" : "");
  }

  private static String render(List<Verdict> verdicts) {
    return verdicts.stream().map(VarkaDomains::render).collect(Collectors.joining("; "));
  }

  /**
   * The one kernel {@code form} compiles to under {@code options}, as the harness's evaluator
   * compiles it, or empty with a finding where it compiles to none or to several: a verdict is
   * per kernel.
   */
  private static Optional<CompiledVarkaProjection> kernel(Form form, Seq<Attribute> columns,
      VarkaEmitOptions options, List<String> findings, String where) {
    CompiledVarkaProjection kernel;
    if (form.condition() != null) {
      Optional<CompiledVarkaPredicate> p =
          VarkaExpressionCompiler.compilePredicate(form.condition(), columns, options);
      // A residual conjunct is the row FilterExec's above the Varka node, outside any verdict.
      if (p.isEmpty() || !p.get().specs().stream().allMatch(VarkaConjunctSpec::fused)) {
        findings.add(where + ": the condition does not compile to one kernel whole: "
            + p.map(Object::toString).orElse(form.condition().toString()));
        return Optional.empty();
      }
      kernel = p.get().fused();
    } else {
      Optional<PartialVarkaProjection> p =
          VarkaExpressionCompiler.compilePartial(form.list(), columns, options);
      if (p.isEmpty() || !p.get().more().isEmpty() || !p.get().declines().isEmpty()) {
        findings.add(where + ": the projection does not compile to one kernel: "
            + p.map(Object::toString).orElse(form.list().toString()));
        return Optional.empty();
      }
      kernel = p.get().fused();
    }
    if (!kernel.derivedInputs().isEmpty()) {
      findings.add(where + ": a derived input is the row engine's own map of a column, which "
          + "this verdict does not model");
      return Optional.empty();
    }
    return Optional.of(kernel);
  }

  /**
   * The verdict on each of {@code batches}, the batches the harness handed the evaluator, for
   * {@code kernel}: the rows read as the kernel reads them - epoch days, month counts,
   * nanoseconds, a floating value's raw bits - and the evaluator's per-batch input bound applied
   * first, as the runner applies it: a live lane outside it declines the batch with
   * {@code STATUS_INPUT_BOUND} and the kernel's guards never run.
   */
  private static List<Verdict> verdicts(CompiledVarkaProjection kernel,
      VarkaEmitOptions options, VarkaHarnessPlans.Fixture fixture,
      List<VarkaExpressionHarness.Batch> batches) {
    // The long harness at every 64-bit lane, DOUBLE's bits included.
    boolean isLong = kernel.lane(options).bits() == Long.SIZE;
    var guards = new VarkaGuardSpecs.Guards(kernel.outputs(), kernel.inputOrdinals().size(),
        kernel.numLiterals(), options, isLong);
    long[] lits = isLong
        ? kernel.longLiterals().stream().mapToLong(Long::longValue).toArray()
        : kernel.literals().stream().mapToLong(Integer::longValue).toArray();
    // A kernel of both lanes (VARKA-28) reads the int table beside the long one.
    int[] intLits = isLong
        ? kernel.literals().stream().mapToInt(Integer::intValue).toArray() : new int[0];
    List<Integer> ordinals = kernel.inputOrdinals();
    List<Attribute> columns = CollectionConverters.asJava(fixture.columns());
    var out = new ArrayList<Verdict>();
    for (VarkaExpressionHarness.Batch batch : batches) {
      var values = new long[batch.rows()][ordinals.size()];
      var isNull = new boolean[batch.rows()][ordinals.size()];
      for (int r = 0; r < batch.rows(); r++) {
        InternalRow row = fixture.rows().get(batch.start() + r);
        for (int k = 0; k < ordinals.size(); k++) {
          int o = ordinals.get(k);
          isNull[r][k] = row.isNullAt(o);
          if (!isNull[r][k]) {
            values[r][k] = laneValue(row, o, columns.get(o).dataType());
          }
        }
      }
      boolean outside = false;
      for (VarkaInputBound b : kernel.inputBounds()) {
        for (int r = 0; r < batch.rows(); r++) {
          long v = values[r][b.inputIndex()];
          outside |= !isNull[r][b.inputIndex()] && (v < b.lo() || v > b.hi());
        }
      }
      out.add(outside ? new Verdict(VarkaKernelRunner.STATUS_INPUT_BOUND, 0)
          : VarkaGuardSpecs.verdict(guards, lits, intLits, values, isNull, List.of()));
    }
    return out;
  }

  /**
   * Column {@code o}'s value as its lane holds it. A narrow type is an int in a narrower lane
   * (VARKA-329): the kernel's check reads the sign-extended value, so the verdict does too.
   */
  private static long laneValue(InternalRow row, int o, DataType t) {
    if (t instanceof DateType || t instanceof IntegerType || t instanceof YearMonthIntervalType) {
      return row.getInt(o);
    } else if (t instanceof ByteType) {
      return row.getByte(o);
    } else if (t instanceof ShortType) {
      return row.getShort(o);
    } else if (t instanceof LongType || t instanceof TimestampNTZType || t instanceof TimeType
        || t instanceof DayTimeIntervalType) {
      return row.getLong(o);
    } else if (t instanceof DoubleType) {
      // A floating value is its raw bits on its lane (VARKA-341).
      return Double.doubleToRawLongBits(row.getDouble(o));
    } else if (t instanceof FloatType) {
      return Float.floatToRawIntBits(row.getFloat(o));
    }
    throw new IllegalArgumentException("no lane reads a " + t + " input");
  }
}
