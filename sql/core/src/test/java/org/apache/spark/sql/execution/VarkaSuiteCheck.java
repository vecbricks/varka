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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.annotation.Nullable;

import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.AttributeReference;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.InterpretedUnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression$;
import org.apache.spark.sql.catalyst.expressions.Not;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.PartialVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaDecline;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.FusedOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.SelectionVectorOps;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAdmission;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.execution.varka.VarkaKernelRunner;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.junit.jupiter.api.Assertions;

/**
 * One check of a Spark expression suite run again through the Varka evaluator, with each literal
 * of a type a kernel reads lifted into a column ({@code m8/VARKA-368.md} 2.2 to 2.7). The Scala
 * trait {@code VarkaSuiteArm} hands each check here after the suite's own has passed; this class
 * lifts it, asks the compiler for its verdict, runs a fused check through
 * {@link VarkaExpressionHarness} on the column path, hands each bare cast's verdict to
 * {@link VarkaSuiteRecord}, and says at the end of each test whether what it found fails the
 * test, is a listed exclusion, or passes.
 */
public final class VarkaSuiteCheck {

  private VarkaSuiteCheck() {}

  /**
   * The rows of a lifted fixture: one more than the host's byte lanes, the widest step any
   * kernel's loop takes, so every kernel runs its loop over a full lane group and then its
   * epilogue, where one row would run the epilogue alone. The plan's 17 fills a group only at
   * int or wider lanes: a {@code TINYINT} kernel steps 32 rows at 256 bits and 64 at 512, since
   * no byte species of that many lanes is baked ({@code VARKA-368.md} 2.2).
   */
  public static final int LIFTED_ROWS = 4 * SelectionVectorOps.intLanes() + 1;

  /**
   * Something a check found, by kind: {@code varka}, a wrong answer or a broken path of the
   * kernel's, which fails its test; {@code read-path}, Spark's answer over the rows read back
   * from Arrow differing from its answer over the fixture's own; {@code lift}, Spark's answer
   * to the lifted expression differing from its answer to the suite's; {@code spark}, Spark's
   * interpreted and generated projections disagreeing, so the row was not compared. The last
   * three are not the kernel's, and a test whose findings are all of one of them is cancelled
   * when {@code expression_excluded.tsv} lists it under that kind, and fails otherwise.
   */
  public record Finding(Kind kind, String text) {}

  /** The kinds of {@link Finding}, each with the name the lists and messages give it. */
  public enum Kind {
    VARKA("varka"),
    READ_PATH("read-path"),
    LIFT("lift"),
    SPARK("spark");

    public final String label;

    Kind(String label) {
      this.label = label;
    }
  }

  /** What the lift made of a check: the expression over columns, and the fixture it reads. */
  public record Lifted(Expression expression, VarkaHarnessPlans.Fixture fixture, int lifted) {}

  /** What the end of a test can do with it. */
  public enum Action {
    PASS,
    FAIL,
    CANCEL
  }

  /** What the end of a test decides, with the message. */
  public record Decision(Action action, String message) {
    static final Decision PASS = new Decision(Action.PASS, "");
  }

  // ---------------------------------------------------------------------------------------------
  // The lift
  // ---------------------------------------------------------------------------------------------

  /** Whether a kernel reads a column of {@code type}: the types a literal is lifted for. */
  static boolean lifts(DataType type) {
    return VarkaTypes.read(type).isPresent();
  }

  /**
   * {@code prepared} with each literal of a type a kernel reads, and each {@code BoundReference}
   * of {@code input}, replaced by a column holding its value; a bound reference of any other type
   * becomes the literal of its value. A null literal becomes a nullable column whose every row is
   * null, any other a column that is not nullable. The fixture has the lifted columns, in the
   * order the lift met them, then an int {@code rid} numbering the {@link #LIFTED_ROWS} rows.
   */
  public static Lifted lift(Expression prepared, InternalRow input) {
    var columns = new ArrayList<Attribute>();
    var values = new ArrayList<Object>();
    Expression lifted = prepared.transformUp(new scala.runtime.AbstractPartialFunction<>() {
      @Override
      public boolean isDefinedAt(Expression e) {
        return e instanceof Literal l && lifts(l.dataType()) || e instanceof BoundReference;
      }

      @Override
      public Expression apply(Expression e) {
        Object value;
        DataType type;
        if (e instanceof Literal l && lifts(l.dataType())) {
          value = l.value();
          type = l.dataType();
        } else if (e instanceof BoundReference b) {
          type = b.dataType();
          value = input.isNullAt(b.ordinal()) ? null : input.get(b.ordinal(), type);
          if (!lifts(type)) {
            return new Literal(value, type);
          }
        } else {
          return e;
        }
        AttributeReference column = column("c" + columns.size(), type, value == null);
        columns.add(column);
        values.add(value);
        return column;
      }
    });
    columns.add(column("rid", DataTypes.IntegerType, false));
    var rows = new ArrayList<InternalRow>(LIFTED_ROWS);
    for (int r = 0; r < LIFTED_ROWS; r++) {
      var row = new Object[values.size() + 1];
      for (int c = 0; c < values.size(); c++) {
        row[c] = values.get(c);
      }
      row[values.size()] = r;
      rows.add(new GenericInternalRow(row));
    }
    Seq<Attribute> seq = CollectionConverters.asScala(columns).toList();
    return new Lifted(lifted, new VarkaHarnessPlans.Fixture(seq, rows), values.size());
  }

  private static AttributeReference column(String name, DataType type, boolean nullable) {
    return new AttributeReference(name, type, nullable, Metadata.empty(),
        NamedExpression$.MODULE$.newExprId(),
        CollectionConverters.asScala(List.<String>of()).toList());
  }

  /** Why {@code lifted} cannot run, an argument that had to stay foldable, or null. */
  static @Nullable String unresolved(Expression lifted) {
    if (!lifted.resolved()) {
      return "the lifted expression does not resolve";
    }
    var check = lifted.checkInputDataTypes();
    return check.isFailure() ? "the lifted expression fails its type check: " + check : null;
  }

  // ---------------------------------------------------------------------------------------------
  // The compile verdict
  // ---------------------------------------------------------------------------------------------

  /** The compiler's verdict on a lifted check: fused, or declined with its reasons. */
  record Verdict(boolean fused, SortedMap<String, Boolean> reasons) {
    String reason() {
      return String.join("; ", reasons.keySet());
    }
  }

  /**
   * The compiler's verdict on {@code lifted} under the evaluator's options and the defaults'
   * admission, as the evaluator compiles it: a {@code BOOLEAN} result as the filter's predicate,
   * every conjunct fused; any other as a projection's one entry, fused. A decline's reasons are
   * {@link VarkaDecline#reason} alone, with any {@code #} expression id stripped, so that a line
   * of the record does not move from run to run.
   */
  static Verdict verdict(Expression lifted, Seq<Attribute> columns) {
    VarkaEmitOptions options = VarkaColumnarToRowExec$.MODULE$.currentEmitOptions();
    VarkaAdmission admission = VarkaSessionEmit.DEFAULTS.admission();
    var reasons = new TreeMap<String, Boolean>();
    boolean fused;
    if (lifted.dataType().equals(DataTypes.BooleanType)) {
      java.util.Optional<CompiledVarkaPredicate> p =
          VarkaExpressionCompiler.compilePredicate(lifted, columns, options, admission);
      fused = p.isPresent() && p.get().residualConjuncts().isEmpty();
      if (!fused) {
        for (VarkaConjunctSpec s :
            VarkaExpressionCompiler.explainPredicate(lifted, columns, options, admission)) {
          s.decline().ifPresent(d -> reasons.put(clean(d.reason()), true));
        }
      }
    } else {
      Seq<NamedExpression> entry = entry(lifted);
      java.util.Optional<PartialVarkaProjection> p =
          VarkaExpressionCompiler.compilePartial(entry, columns, options, admission);
      fused = p.isPresent() && p.get().specs().stream().allMatch(s -> s instanceof FusedOutput);
      if (!fused) {
        for (VarkaDecline d :
            VarkaExpressionCompiler.declines(entry, columns, options, admission).values()) {
          reasons.put(clean(d.reason()), true);
        }
      }
    }
    if (!fused && reasons.isEmpty()) {
      reasons.put("no kernel", true);
    }
    return new Verdict(fused, reasons);
  }

  static String clean(String reason) {
    return reason.replaceAll("#\\d+", "").replace('\t', ' ').replace('\n', ' ');
  }

  private static Seq<NamedExpression> entry(Expression e) {
    return CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toList();
  }

  // ---------------------------------------------------------------------------------------------
  // One check
  // ---------------------------------------------------------------------------------------------

  /** One test's state: the checks run so far, and what they found. */
  private static final class TestState {
    final Set<List<Object>> seen = new HashSet<>();
    final List<Finding> findings = new ArrayList<>();
  }

  /**
   * Each suite's checks: those run, those with a literal lifted, those the compiler fused, and
   * those of which a kernel answered some batch. A fused check whose every batch declined compared
   * Spark with Spark only, so the last count tells it from one a kernel answered.
   */
  private static final Map<String, int[]> COUNTS = new HashMap<>();

  /** {@code suite}'s counts so far, as a line the end of a suite prints. */
  public static synchronized String summary(String suite) {
    int[] c = COUNTS.getOrDefault(suite, new int[4]);
    return suite + ": " + c[0] + " checks through Varka, " + c[1] + " with a column, " + c[2]
        + " fused, " + c[3] + " answered by a kernel";
  }

  /** The test running now, by suite; a check outside a test has none. */
  private static final Map<String, TestState> CURRENT = new HashMap<>();

  /** Starts {@code test} of {@code suite}. */
  public static synchronized void beginTest(String suite, String test) {
    CURRENT.put(suite, new TestState());
  }

  /**
   * What a check expected besides its value: the error condition {@code checkErrorInExpression}
   * was given, or the message {@code checkExceptionInExpression} was; both null for a value.
   */
  public record Expect(@Nullable String condition, @Nullable String message) {
    public static final Expect VALUE = new Expect(null, null);
  }

  /**
   * Runs one check of {@code suite} through Varka: {@code prepared} is the suite's expression as
   * {@code ExpressionEvalHelper} prepares it, {@code input} the row it is evaluated on. A check
   * this test already ran, with the same expectation or as a value check, is not run again.
   * {@code record} says whether the check is a fixed-value one, whose bare cast joins the record;
   * the random properties' are not. A check whose form does not fuse is counted and skipped once
   * its verdict is recorded. A check outside any test fails at once on what it finds.
   */
  public static synchronized void check(String suite, Expression prepared, InternalRow input,
      boolean record, Expect expect) {
    @Nullable TestState current = CURRENT.get(suite);
    TestState state = current != null ? current : new TestState();
    // A value check already ran it through the harness; TryCastSuite's error checks arrive
    // as one, then as themselves.
    List<Object> key = List.of(prepared, input.copy(), record);
    if (state.seen.contains(List.of(key, Expect.VALUE)) || !state.seen.add(List.of(key, expect))) {
      return;
    }
    try {
      checkOnce(suite, prepared, input, record, expect, state.findings);
    } catch (RuntimeException | AssertionError e) {
      state.findings.add(new Finding(Kind.VARKA, prepared.sql() + ": the check failed: " + e));
    }
    if (current == null && !state.findings.isEmpty()) {
      Assertions.fail("a Varka check outside a test found:\n  "
          + String.join("\n  ", state.findings.stream().map(Finding::text).toList()));
    }
  }

  /**
   * The statuses a kernel declines a batch with on a value Spark raises on: its guards'. The
   * others are refusals of a range its lowering covers, after which the row path still answers.
   * {@code VALUE_RANGE} is both: a {@code GuardedRange} raises it where Spark raises and also
   * where a lowering is exact only within a bound (a {@code ConstDivide}'s dividend), so a decline
   * with it on a raising row passes either way. The evaluator's {@code STATUS_DERIVED_INPUT} is a
   * guard too, reported where the row engine raises; its {@code STATUS_INPUT_BOUND} is a refusal,
   * so a raising row it declines is reported, as the plan's rule asks.
   */
  static final int VALUE_STATUSES = VarkaFusedKernel.STATUS_MAKE_DATE
      | VarkaFusedKernel.STATUS_INT_OVERFLOW | VarkaFusedKernel.STATUS_VALUE_RANGE
      | VarkaFusedKernel.STATUS_DECIMAL_SLOT | VarkaFusedKernel.STATUS_DIVIDE_BY_ZERO
      | VarkaKernelRunner.STATUS_DERIVED_INPUT;

  private static void checkOnce(String suite, Expression prepared, InternalRow input,
      boolean record, Expect expect, List<Finding> findings) {
    int[] counts = COUNTS.computeIfAbsent(suite, k -> new int[4]);
    counts[0]++;
    Lifted lifted = lift(prepared, input);
    if (lifted.lifted() == 0) {
      return;
    }
    counts[1]++;
    Expression e = lifted.expression();
    Seq<Attribute> columns = lifted.fixture().columns();
    @Nullable String unresolved = unresolved(e);
    Verdict verdict = unresolved != null ? null : verdict(e, columns);
    if (record) {
      VarkaSuiteRecord.record(suite, e, verdict, unresolved);
    }
    if (verdict == null || !verdict.fused()) {
      return;
    }
    counts[2]++;
    String context = suite + ": " + prepared.sql();
    SQLConf conf = conf();
    var outcomes = new ArrayList<VarkaExpressionHarness.Outcome>();
    SQLConf.withExistingConf(conf, () -> {
      if (e.dataType().equals(DataTypes.BooleanType)) {
        outcomes.add(VarkaExpressionHarness.filter(context, e, lifted.fixture(), conf,
            VarkaExpressionHarness.Options.COLUMN_PATH));
        outcomes.add(VarkaExpressionHarness.filter(context + " (its NOT)", new Not(e),
            lifted.fixture(), conf, VarkaExpressionHarness.Options.COLUMN_PATH));
      } else {
        outcomes.add(VarkaExpressionHarness.projection(context, entry(e), lifted.fixture(), conf,
            VarkaExpressionHarness.Consumer.COLUMNS, VarkaExpressionHarness.Options.COLUMN_PATH));
      }
      return null;
    });
    if (outcomes.stream().anyMatch(o -> o.answered > 0)) {
      counts[3]++;
    }
    for (VarkaExpressionHarness.Outcome o : outcomes) {
      for (String f : o.findings) {
        // The harness's findings are the kernel's and the evaluator's, but Spark's own.
        findings.add(new Finding(o.sparkDisagreements.contains(f) ? Kind.SPARK : Kind.VARKA, f));
      }
      for (String f : o.readPath) {
        findings.add(new Finding(Kind.READ_PATH, f));
      }
    }
    String lift = liftDifference(prepared, input, lifted);
    if (lift != null) {
      findings.add(new Finding(Kind.LIFT, context + ": " + lift));
    }
    for (VarkaExpressionHarness.Outcome o : outcomes) {
      raised(context, o, expect, findings);
    }
    if (record && VarkaSuiteRecord.recording() && e instanceof Cast cast
        && cast.child() instanceof AttributeReference) {
      for (VarkaExpressionHarness.Outcome o : outcomes) {
        VarkaSuiteRecord.batchDecline(suite, cast, o, findings, context);
      }
    }
  }

  /**
   * On rows Spark raises on: each batch the kernel declined declined through a guard
   * ({@link #VALUE_STATUSES}), and each error the row path raised out of {@code serveBatch} is
   * the one the suite expected, its condition or its message. The error's equality with Spark's
   * over the same rows is the harness's check, and with Spark's over the suite's literals the
   * lift's ({@link #liftDifference}).
   */
  private static void raised(String context, VarkaExpressionHarness.Outcome o, Expect expect,
      List<Finding> findings) {
    if (o.sparkRaised == 0) {
      return;
    }
    for (VarkaExpressionHarness.Batch b : o.batches) {
      if (b.status() > 0 && (b.status() & VALUE_STATUSES) == 0) {
        findings.add(new Finding(Kind.VARKA, context + ": the batch of rows " + b.start()
            + " on which Spark raises declined with status " + b.status() + ", no guard's"));
      }
    }
    for (RuntimeException x : o.rowPathErrors) {
      boolean met = expect.condition() != null
          ? x instanceof org.apache.spark.SparkThrowable t
              && expect.condition().equals(t.getCondition())
          // A hot JDK throw may have no message (OmitStackTraceInFastThrow), as sameError allows.
          : expect.message() == null || x.getMessage() == null
              || x.getMessage().contains(expect.message());
      if (!met) {
        findings.add(new Finding(Kind.LIFT, context + ": the row path raised " + x + ", the suite "
            + "expects " + expect));
      }
    }
  }

  /**
   * The conf the batches and Spark's answers read: the test's own, with the option matrix's batch
   * size where it sets one, as the coverage harness's.
   */
  private static SQLConf conf() {
    SQLConf conf = SQLConf.get().clone();
    VarkaMatrixRuns.ARROW_BATCH_SIZE_ROWS.ifPresent(rows -> conf.setConfString(
        SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH().key(), Integer.toString(rows)));
    return conf;
  }

  /**
   * Null when Spark answers the lifted expression on a fixture row as it answers the suite's on
   * its input row, interpreted, else the difference: the value, or the error's class and message.
   */
  static @Nullable String liftDifference(Expression prepared, InternalRow input, Lifted lifted) {
    Answer own = interpreted(prepared, input);
    Expression bound = VarkaSqlResolve.bind(lifted.expression(), lifted.fixture().columns());
    Answer over = interpreted(bound, lifted.fixture().rows().get(0));
    return own.agrees(over) ? null
        : "Spark answers the suite's expression " + own + " and the lifted one " + over;
  }

  /**
   * Spark's interpreted answer: a value's text, or an error's class and message, which a hot JDK
   * throw may lack (OmitStackTraceInFastThrow), so that a missing one agrees with any.
   */
  private record Answer(String text, @Nullable String errorClass, @Nullable String message) {
    boolean agrees(Answer o) {
      if (errorClass == null || o.errorClass == null) {
        return errorClass == null && o.errorClass == null && text.equals(o.text);
      }
      return errorClass.equals(o.errorClass)
          && (message == null || o.message == null || message.equals(o.message));
    }

    @Override
    public String toString() {
      return errorClass == null ? "value " + text : "error " + errorClass + ": " + message;
    }
  }

  private static Answer interpreted(Expression e, InternalRow row) {
    try {
      UnsafeProjection p = InterpretedUnsafeProjection.createProjection(
          CollectionConverters.asScala(List.of(e)).toList());
      return new Answer(p.apply(row).copy().toString(), null, null);
    } catch (RuntimeException x) {
      return new Answer("", x.getClass().getName(), x.getMessage());
    }
  }

  /**
   * At the end of {@code test}: fails it on a finding of the kernel's; cancels it when every
   * finding is of the kind {@code expression_excluded.tsv} lists it under; fails a listed test
   * that found nothing, or something of another kind. {@code ran} says whether the test's body
   * returned; one that threw or was cancelled reached part of its checks, so it does not count
   * towards the suite's record.
   */
  public static synchronized Decision endTest(String suite, String test, boolean ran) {
    TestState state = CURRENT.remove(suite);
    List<Finding> findings = state == null ? List.of() : state.findings;
    @Nullable String[] listed = VarkaSuiteRecord.excluded(suite, test);
    Decision decision = decide(findings, listed);
    if (ran && decision.action() != Action.FAIL) {
      VarkaSuiteRecord.completed(suite);
    }
    return decision;
  }

  private static Decision decide(List<Finding> findings, @Nullable String[] listed) {
    if (findings.isEmpty()) {
      // A configuration may decline what the defaults fuse, so a line is stale at the defaults.
      if (listed == null || !VarkaSuiteRecord.recording()) {
        return Decision.PASS;
      }
      return new Decision(Action.FAIL, "expression_excluded.tsv lists this test (" + listed[0]
          + ": " + listed[1] + "), and it found nothing: a stale line, to be removed");
    }
    var text = new ArrayList<String>();
    boolean allListed = listed != null;
    for (Finding f : findings) {
      text.add("[" + f.kind().label + "] " + f.text());
      allListed &= listed != null && f.kind().label.equals(listed[0]);
    }
    String message = "Varka found " + findings.size() + " thing(s):\n  "
        + String.join("\n  ", text);
    return allListed ? new Decision(Action.CANCEL, listed[0] + ": " + listed[1] + "\n" + message)
        : new Decision(Action.FAIL, message);
  }
}
