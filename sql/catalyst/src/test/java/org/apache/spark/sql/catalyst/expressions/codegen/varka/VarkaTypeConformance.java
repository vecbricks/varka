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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.Batch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.CallHook;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.LongBatch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.SparkAnswers;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * The conformance contract a type is held to (VARKA-484, `VARKA-475.md` 3.1): seven checks -
 * load, round trip, null handling, SQL ordering, choice, the type's row of the cast matrix and
 * overflow in LEGACY, ANSI and TRY - each over fixtures generated from the type's values rather
 * than written per suite. A suite per type supplies the type and carries {@code @VarkaSuite};
 * the checks are inherited.
 *
 * <p>Each check compiles Catalyst expressions over two columns of the type, {@code x} and
 * {@code y}, through {@link VarkaExpressionCompiler}, and runs the emitted class over every pair
 * of the type's values under every null pattern of a pair, at lengths that reach a partial lane
 * group and the epilogue, and over a batch with no null for the dense body. Every row is compared
 * with {@link VarkaReference} and with {@link VarkaSparkOracle}, Spark's own {@code eval} of the
 * same expression, in both bodies and at both output validity fills.
 *
 * <p>Whether a form must fuse is the registers' word, not this class's: a form whose census entry
 * (or, for a cast, its form pair in {@code casts_fused.tsv}, which the compiler writes) marks the
 * type fused today must compile and agree; one it does not mark must decline, and is owed to the
 * rows the register names. A check whose forms all hold passes; one with an owed form is
 * cancelled naming the owners, after its fused forms have run. A form that fuses where the
 * register says it does not, or declines where it says it does, fails: the register has drifted
 * from the compiler.
 *
 * <p>A type is read at the lane the type registry names for it, and a check runs at the lane its
 * kernel is driven at: the int lane's batches for a narrow, int-lane or condition type, the long
 * lane's 64-bit batches for a long-lane one, with both literal tables where a kernel converts
 * between the two (a cast to {@code DATE}).
 *
 * <p>Every check also holds its fixture to what a hand-written one missed in the narrow lanes'
 * reviews (`VARKA-329.md` 9.8): each condition root is true on some row and false on another,
 * each value root is valid on some row and, where it can be null, null on another, so that a
 * kernel answering a constant could not pass it.
 */
public abstract class VarkaTypeConformance {

  // -------------------------------------------------------------------------------------------
  // The type under test
  // -------------------------------------------------------------------------------------------

  /**
   * What the checks read of a type: its census name, its Spark type and SQL spelling, how a value
   * is spelled as a literal, its values, from which every fixture is generated, two of them the
   * forms name as literals ({@code picks}), and the null-intolerant form over two of its columns
   * the null handling check runs ({@code pair}). Every value is the type's internal one, as a
   * column of it stores it: days for a date, nanoseconds of the day for a time, months for a
   * year-month interval, microseconds for a day-time interval.
   *
   * <p>{@code pair} is null for {@code BOOLEAN} alone, whose null handling is its connectives.
   */
  protected record ConformanceType(String census, DataType spark, String sql,
      LongFunction<String> literal, long[] values, long[] picks, PairForm pair) {
    public ConformanceType {
      if (picks.length != 2) {
        throw new IllegalArgumentException("two picks, not " + picks.length);
      }
      for (long p : picks) {
        if (Arrays.stream(values).noneMatch(v -> v == p)) {
          throw new IllegalArgumentException("pick " + p + " is not among the values, so a "
              + "comparison with it would be true on no row");
        }
      }
      if (pair == null && !spark.equals(DataTypes.BooleanType)) {
        throw new IllegalArgumentException(sql + " needs a pair form for its null handling");
      }
    }

    boolean isBoolean() {
      return spark.equals(DataTypes.BooleanType);
    }

    /** Whether the type is FLOAT or DOUBLE, whose arithmetic has no overflow to raise on. */
    boolean isFloating() {
      return spark.equals(DataTypes.FloatType) || spark.equals(DataTypes.DoubleType);
    }

    /**
     * The lane a column of the type is read at, the registry's; a {@code BOOLEAN}, read as a
     * condition and on no lane, is counted with the int lane, as its bitmaps' words are.
     */
    LaneType lane() {
      return VarkaTypes.lane(spark).orElse(LaneType.INT);
    }
  }

  /**
   * A form over the two columns that is null where either is and has a value elsewhere, the
   * census entry that says whether it fuses, and the values its fixture draws, or null for the
   * type's own. A pair that raises on overflow in every mode (interval arithmetic) takes values
   * whose every pair stays in range ({@link #halfRange}): over the type's extremes Spark raises
   * on a row of nearly every batch, the kernel declines each such batch, and the null patterns
   * would be compared on almost no row.
   */
  protected record PairForm(String sql, String register, long[] values) {
    public PairForm(String sql, String register) {
      this(sql, register, null);
    }
  }

  /** The type this suite holds to the contract. */
  protected abstract ConformanceType type();

  /**
   * An integral type between {@code min} and {@code max}, its literals spelled with
   * {@code suffix}: the integral values, 1 and -1 as the picks, and {@code x + y} as the pair.
   */
  protected static ConformanceType integral(String census, DataType spark, String sql,
      String suffix, long min, long max) {
    return new ConformanceType(census, spark, sql, v -> v + suffix, integralValues(min, max),
        new long[] {1, -1}, new PairForm("x + y", "+"));
  }

  /** The integral values a fixture draws: the extremes, their neighbours, zero and one each way. */
  protected static long[] integralValues(long min, long max) {
    return new long[] {min, min + 1, -1, 0, 1, max - 1, max};
  }

  /**
   * {@link #integralValues} between half of {@code min} and half of {@code max}: the sum or the
   * difference of any two of them is between {@code min} and {@code max}, so a pair form over
   * them never overflows.
   */
  protected static long[] halfRange(long min, long max) {
    return integralValues(min / 2, max / 2);
  }

  // -------------------------------------------------------------------------------------------
  // The checks
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("load: a column of the type is read as its values, null rows included")
  void load() {
    ConformanceType t = type();
    if (t.isBoolean()) {
      run(List.of(condition("x", null), condition("NOT x", "not")));
    } else {
      run(List.of(condition("x < y", "<"), condition("x = " + pick(0), "="),
          condition("x IS NOT NULL", "isnotnull"),
          condition("x IN (" + pick(0) + ", " + pick(1) + ")", "in")));
    }
  }

  @Test
  @DisplayName("round trip: a value of the type stored by a kernel is the value read")
  void roundTrip() {
    ConformanceType t = type();
    if (t.isBoolean()) {
      run(List.of(value("x", null)));
    } else {
      run(List.of(value("if(x IS NULL, " + pick(0) + ", x)", "if"),
          value("coalesce(x, " + pick(1) + ")", "coalesce")));
    }
  }

  @Test
  @DisplayName("null handling: every null pattern of a pair, null-intolerant and null-safe")
  void nullHandling() {
    ConformanceType t = type();
    if (t.isBoolean()) {
      run(List.of(condition("x AND y", "and"), condition("x OR y", "or"),
          condition("x <=> y", "<=>"), condition("x IS NULL", "isnull")));
    } else {
      PairForm pair = t.pair();
      run(List.of(value(pair.sql(), pair.register()).legacy().over(pair.values()),
          condition("x = y", "="),
          condition("x <=> y", "<=>"), condition("x IS NULL", "isnull")));
    }
  }

  @Test
  @DisplayName("SQL ordering: every comparison over every pair of the type's values")
  void ordering() {
    var forms = new ArrayList<Form>();
    for (String op : List.of("<", "<=", ">", ">=", "=", "<=>")) {
      forms.add(condition("x " + op + " y", op));
    }
    if (!type().isBoolean()) {
      forms.add(value("greatest(x, y)", "greatest"));
      forms.add(value("least(x, y)", "least"));
    }
    run(forms);
  }

  @Test
  @DisplayName("choice: CASE WHEN, IF and coalesce over the type, unknown taking ELSE")
  void choice() {
    if (type().isBoolean()) {
      run(List.of(condition("IF(x, y, NOT y)", "if"), condition("CASE WHEN x THEN y END", "when"),
          condition("coalesce(x, y)", "coalesce")));
    } else {
      run(List.of(value("CASE WHEN x > y THEN x ELSE y END", "when"),
          value("if(x < y, x, y)", "if"), value("coalesce(x, y)", "coalesce")));
    }
  }

  @Test
  @DisplayName("the type's row of the cast matrix, in LEGACY, ANSI and TRY")
  void castRow() {
    var forms = new ArrayList<Form>();
    for (CastPair pair : Registers.casts(type().census())) {
      String target = CAST_TARGETS.get(pair.to());
      boolean toBoolean = pair.to().equals("bool");
      for (Mode mode : Mode.values()) {
        // A cast Spark's analyzer rejects in a mode (DATE to a number under ANSI) has no form
        // there to fuse or decline.
        if (Registers.illegal(type().spark(), DataType.fromDDL(target), mode)) {
          continue;
        }
        String sql = (mode == Mode.TRY ? "try_cast" : "CAST") + "(x AS " + target + ")";
        Form f = new Form(sql, toBoolean ? Kind.CONDITION : Kind.VALUE,
            "cast:" + pair.to(), mode, false);
        forms.add(f);
      }
    }
    assertTrue(!forms.isEmpty(), "casts.tsv has no row from " + type().census());
    run(forms);
  }

  /**
   * The type's own arithmetic at its extremes, each operator the census admits for the type. An
   * interval's arithmetic raises on overflow in every mode, which Spark's answer shows and the
   * kernel is held to. A type the census admits for none of them ({@code BOOLEAN}, {@code DATE},
   * {@code TIME}, and {@code TIMESTAMP_NTZ}, which has no arithmetic of its own type until row 339
   * adds its interval arithmetic) has no overflow to check, and that is stated rather than
   * skipped: a census row that admits one gives the check its first form here. A floating type's
   * arithmetic reaches an infinity at its extremes, which Spark answers in every mode
   * (`VARKA-342.md` 3.8), so its forms are held to Spark over the extremes with nothing raised
   * or nulled, and the kernel declines no batch.
   */
  @Test
  @DisplayName("overflow: LEGACY wraps, ANSI declines what Spark raises on, TRY nulls it")
  void overflow() {
    var forms = new ArrayList<Form>();
    for (Mode mode : List.of(Mode.LEGACY, Mode.ANSI)) {
      for (String op : List.of("+", "-", "*")) {
        forms.add(value("x " + op + " y", op).in(mode).reachingOverflow());
      }
      forms.add(value("-x", "negative").in(mode).reachingOverflow());
      forms.add(value("abs(x)", "abs").in(mode).reachingOverflow());
    }
    for (String fn : List.of("try_add", "try_subtract", "try_multiply")) {
      forms.add(value(fn + "(x, y)", fn).in(Mode.TRY).reachingOverflow());
    }
    forms.removeIf(f -> !Registers.census(f.register()).types().contains(type().census()));
    if (forms.isEmpty()) {
      return;
    }
    run(forms);
  }

  // -------------------------------------------------------------------------------------------
  // Forms and their verdicts
  // -------------------------------------------------------------------------------------------

  /** A form is a condition (a filter's predicate) or a value (a projection's output). */
  private enum Kind { CONDITION, VALUE }

  /** The session's evaluation mode a form is resolved under; TRY is spelled in the form. */
  private enum Mode { LEGACY, ANSI, TRY }

  /**
   * One expression a check runs: its SQL over {@code x} and {@code y}, its kind, the register
   * entry that says whether it fuses ({@code null} for a bare column, which every type reads),
   * the mode, whether it is meant to reach the type's overflow, and the values its fixture draws
   * ({@code null} for the type's own).
   */
  private record Form(String sql, Kind kind, String register, Mode mode, boolean overflowing,
      long[] values) {
    Form(String sql, Kind kind, String register, Mode mode, boolean overflowing) {
      this(sql, kind, register, mode, overflowing, null);
    }

    Form legacy() {
      return in(Mode.LEGACY);
    }

    Form in(Mode m) {
      return new Form(sql, kind, register, m, overflowing, values);
    }

    Form reachingOverflow() {
      return new Form(sql, kind, register, mode, true, values);
    }

    /** This form over {@code v} rather than the type's values; {@code null} keeps them. */
    Form over(long[] v) {
      return new Form(sql, kind, register, mode, overflowing, v);
    }

    /**
     * Whether Spark raising on the fixture is the point of the form, so that the batches the
     * kernel answers need not discriminate it: an overflow form, and an ANSI cast, which raises
     * on the values outside its target's range. Over the type's extremes a narrowing one raises
     * on a row of every batch but a tail of one null row, so it is held to declining them; its
     * LEGACY and TRY twins answer the same fixture and are held to the rule.
     */
    boolean meantToRaise() {
      return overflowing || (mode == Mode.ANSI && register != null && register.startsWith("cast:"));
    }

    @Override public String toString() {
      return sql + " (" + mode + ")";
    }
  }

  private static Form condition(String sql, String register) {
    return new Form(sql, Kind.CONDITION, register, Mode.ANSI, false);
  }

  private static Form value(String sql, String register) {
    return new Form(sql, Kind.VALUE, register, Mode.ANSI, false);
  }

  /** Pick {@code i} of the type, spelled as its literal. */
  private String pick(int i) {
    return type().literal().apply(type().picks()[i]);
  }

  /**
   * The rows that own a form the registers do not mark fused, or empty where they do. A value of
   * type {@code BOOLEAN} is owed to row 27, which stores one, whatever its operator's entry says.
   */
  private Optional<String> owedTo(Form f, DataType result) {
    String census = type().census();
    if (f.kind() == Kind.VALUE && result.equals(DataTypes.BooleanType)) {
      return Optional.of("27 (a BOOLEAN output)");
    }
    if (f.register() == null) {
      return Optional.empty();
    }
    if (f.register().startsWith("cast:")) {
      CastPair pair = Registers.cast(census, f.register().substring(5));
      return pair.fusedIn(f.mode(), type().spark(), result) ? Optional.empty()
          : Optional.of(pair.rows());
    }
    String gap = CHECKED_GAPS.get(census + " " + f.register());
    if (gap != null && f.mode() == Mode.ANSI) {
      return Optional.of(gap);
    }
    CensusEntry e = Registers.census(f.register());
    return e.fused().contains(census) ? Optional.empty() : Optional.of(e.rows());
  }

  /**
   * The gaps the census cannot state, by type and operator, with the row that owns each: its
   * claims are not per mode, and these operators fuse over two columns in LEGACY while the
   * compiler declines their checked form under ANSI.
   *
   * <p>The key has no mode or operand shape, so an entry owes every ANSI form of its operator
   * over the type. When the checked form fuses - row 95's bounded ANSI {@code int *} - the check
   * reports it as drift ("fuses, and the register owes it"): remove the entry then, and if the
   * new form fuses only for some operands, key the gap more narrowly rather than letting it owe
   * the forms that now fuse.
   */
  private static final Map<String, String> CHECKED_GAPS = Map.of(
      "int *", "95 (a checked int multiply has no int-lane overflow test)");

  // -------------------------------------------------------------------------------------------
  // Running a check
  // -------------------------------------------------------------------------------------------

  private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

  /**
   * One compiled form: the emitted class, its roots, its two literal tables, whether its loop runs
   * at the long lane (and so which {@code run} it is called through), and the expressions Spark
   * answers.
   */
  private record Compiled(String className, byte[] bytes, List<VarkaVectorIR> roots,
      List<Integer> inputs, int[] lits, long[] longLits, boolean longLane,
      List<Expression> outputs) {}

  /**
   * Runs every form of a check: each that fuses is held to the reference and to Spark, each the
   * registers owe is required to decline. A check with an owed form is cancelled naming them.
   */
  private void run(List<Form> forms) {
    var owed = new ArrayList<String>();
    var drift = new ArrayList<String>();
    for (Form f : forms) {
      Expression expr = resolve(f);
      Optional<String> owner = owedTo(f, expr.dataType());
      Optional<Compiled> compiled = compile(f, expr);
      if (owner.isPresent()) {
        if (compiled.isPresent()) {
          drift.add(f + " fuses, and the register owes it to rows " + owner.get()
              + ": mark the type fused in the census or casts.tsv");
        }
        owed.add(f + ": rows " + owner.get() + declineReason(f, expr));
        continue;
      }
      if (compiled.isEmpty()) {
        cancelIfTheWidthCannotWiden(f);
        drift.add(f + " declines, and the register marks " + type().census() + " fused for "
            + f.register() + declineReason(f, expr));
        continue;
      }
      check(f, compiled.get());
    }
    // Every form of the check is tried before a drift fails it, so that one run names them all.
    if (!drift.isEmpty()) {
      fail("the registers have drifted from the compiler: " + String.join("; ", drift));
    }
    assumeTrue(owed.isEmpty(), "owed by the registers: " + String.join("; ", owed));
  }

  /** Why the compiler declines a value form, as it reports it, for a message; empty otherwise. */
  private String declineReason(Form f, Expression expr) {
    if (f.kind() != Kind.VALUE) {
      return "";
    }
    var entry = CollectionConverters.asScala(List.of(VarkaSqlResolve.named(expr, "c"))).toSeq();
    var cols = CollectionConverters.asScala(columns()).toSeq();
    return VarkaExpressionCompiler.declines(entry, cols, VarkaMatrixRuns.BASE).values().stream()
        .findFirst().map(d -> " (declined: " + d.reason() + ")").orElse("");
  }

  /**
   * A narrow column at a width whose int species cannot widen it is declined by the compiler and
   * answered by the row engine (VARKA-329): the check is cancelled naming the refusal, as the
   * narrow suites cancel theirs, rather than failing on a configuration of the option matrix.
   */
  private void cancelIfTheWidthCannotWiden(Form f) {
    LaneType lane = type().lane();
    if (!lane.isNarrow()) {
      return;
    }
    Optional<String> refusal = VarkaKernelPlan.refusalOf(List.of(new ColumnRef(0, lane)),
        VarkaMatrixRuns.BASE.toBuilder().narrowLanes(false).build());
    assumeTrue(refusal.isEmpty(), f + ": this configuration cannot widen the " + lane
        + " lane (" + refusal.orElse("") + "), so the compiler declines it and the row engine "
        + "answers it");
  }

  /** The two columns, {@code x} and {@code y}: built once, since a form binds their ids. */
  private List<Attribute> columns;

  private List<Attribute> columns() {
    if (columns == null) {
      columns = List.of(VarkaSqlResolve.column("x", type().spark()),
          VarkaSqlResolve.column("y", type().spark()));
    }
    return columns;
  }

  /** {@code f}'s SQL resolved over the two columns, ANSI on or off as its mode says. */
  private Expression resolve(Form f) {
    SQLConf conf = SQLConf.get().clone();
    conf.setConfString(SQLConf.ANSI_ENABLED().key(), f.mode() == Mode.LEGACY ? "false" : "true");
    var cols = CollectionConverters.asScala(columns()).toSeq();
    return SQLConf$.MODULE$.withExistingConf(conf, () -> VarkaSqlResolve.parse(f.sql(), cols));
  }

  private Optional<Compiled> compile(Form f, Expression expr) {
    var cols = CollectionConverters.asScala(columns()).toSeq();
    CompiledVarkaProjection plan;
    List<Expression> outputs;
    if (f.kind() == Kind.CONDITION) {
      Optional<CompiledVarkaPredicate> p =
          VarkaExpressionCompiler.compilePredicate(expr, cols, VarkaMatrixRuns.BASE);
      if (p.isEmpty() || !p.get().residualConjuncts().isEmpty()) {
        return Optional.empty();
      }
      plan = p.get().fused();
      // The fused conjuncts recombined into one root, which is the whole form when all fuse.
      outputs = List.of(expr);
    } else {
      NamedExpression entry = VarkaSqlResolve.named(expr, "c");
      Optional<CompiledVarkaProjection> p = VarkaExpressionCompiler.compile(
          CollectionConverters.asScala(List.of(entry)).toSeq(), cols, VarkaMatrixRuns.BASE);
      if (p.isEmpty()) {
        return Optional.empty();
      }
      plan = p.get();
      outputs = List.of(expr);
    }
    assertEquals(outputs.size(), plan.outputs().size(),
        f + ": one Spark expression a kernel output");
    // A derived input is computed by the evaluator from a column before the kernel runs, which
    // this harness does not do: every input must be a column as stored.
    assertTrue(plan.derivedInputs().isEmpty(), f + ": a derived input " + plan.derivedInputs());
    String className = "org.apache.spark.sql.varka.execution.VarkaConformance"
        + CLASS_COUNTER.incrementAndGet();
    byte[] bytes = VarkaLoopEmitter.emit(className, plan.outputs(), plan.inputOrdinals().size(),
        plan.numLiterals(), null, null, VarkaMatrixRuns.BASE);
    int[] lits = plan.literals().stream().mapToInt(Integer::intValue).toArray();
    long[] longLits = plan.longLiterals().stream().mapToLong(Long::longValue).toArray();
    // The long harness at every 64-bit lane, DOUBLE's bits included.
    boolean longLane = plan.lane(VarkaMatrixRuns.BASE).bits() == Long.SIZE;
    return Optional.of(new Compiled(className, bytes, plan.outputs(), plan.inputOrdinals(), lits,
        longLits, longLane, outputs));
  }

  /** The fixture's rows: every pair of the type's values under every null pattern of a pair. */
  private record Row(long x, long y, boolean xNull, boolean yNull) {
    long value(int column) {
      return column == 0 ? x : y;
    }

    boolean isNull(int column) {
      return column == 0 ? xNull : yNull;
    }
  }

  private List<Row> rows(Form f, boolean withNulls) {
    long[] values = f.values() != null ? f.values() : type().values();
    var rows = new ArrayList<Row>();
    for (long a : values) {
      for (long b : values) {
        for (int pattern = 0; pattern < (withNulls ? 4 : 1); pattern++) {
          rows.add(new Row(a, b, (pattern & 1) != 0, (pattern & 2) != 0));
        }
      }
    }
    return rows;
  }

  /** Lengths that reach a single row, a partial lane group, whole groups and the epilogue. */
  private static final int[] TAILS = {1, 7, 8, 9, 17, 63, 64, 65};

  /**
   * {@code rows} from {@code offset} on, cycled to {@code length}, over the kernel's inputs: the
   * values at 64 bits, which a kernel run at a narrower lane reads narrowed to its column's width.
   */
  private static LongBatch batch(List<Row> rows, int offset, int length, List<Integer> inputs) {
    var nulls = new boolean[inputs.size()][length];
    var data = new long[inputs.size()][length];
    for (int c = 0; c < inputs.size(); c++) {
      int column = inputs.get(c);
      for (int i = 0; i < length; i++) {
        Row r = rows.get((offset + i) % rows.size());
        nulls[c][i] = r.isNull(column);
        data[c][i] = r.value(column);
      }
    }
    return new LongBatch(length, nulls, data, false);
  }

  /** {@code b} for the int entry point, whose columns are at most 32 bits wide. */
  private static Batch intBatch(LongBatch b) {
    var data = new int[b.data().length][];
    for (int c = 0; c < data.length; c++) {
      data[c] = Arrays.stream(b.data()[c]).mapToInt(v -> (int) v).toArray();
    }
    return new Batch(b.length(), b.nulls(), data, b.forceMasked());
  }

  /** Every batch a form runs: the fixture whole at several offsets, its tails, and dense. */
  private static List<LongBatch> batches(List<Row> all, List<Row> dense, List<Integer> inputs) {
    var out = new ArrayList<LongBatch>();
    for (int offset : new int[] {0, 1, 5}) {
      out.add(batch(all, offset, all.size(), inputs));
    }
    for (int length : TAILS) {
      out.add(batch(all, 3, length, inputs));
    }
    for (int length : new int[] {1, 9, 65, dense.size()}) {
      out.add(batch(dense, 0, length, inputs));
    }
    return out;
  }

  private void check(Form f, Compiled c) {
    var oracle = new VarkaSparkOracle(CollectionConverters.asScala(c.outputs()).toSeq(),
        CollectionConverters.asScala(c.inputs().stream().map(columns()::get).toList()).toSeq());
    List<Row> all = rows(f, true);
    requireTheFixtureDiscriminates(f, c, oracle, all);
    var answered = new Seen[c.outputs().size()];
    for (int o = 0; o < answered.length; o++) {
      answered[o] = new Seen();
    }
    var loaded = VarkaEmitterFixtures.load(new VarkaEmitterFixtures.Emitted(c.className(),
        c.bytes()));
    try {
      int n = 0;
      for (LongBatch b : batches(all, rows(f, false), c.inputs())) {
        String ctx = getClass().getSimpleName() + " " + f + " batch " + n++ + " length "
            + b.length();
        boolean raises = raises(oracle, c, b);
        int status = c.longLane()
            ? VarkaKernelChecks.runLoadedLong(ctx, loaded.kernel(), c.roots(), c.inputs().size(),
                c.longLits(), c.lits(), b, raises, answers(oracle), CallHook.NONE)
            : VarkaKernelChecks.runLoadedNarrow(ctx, loaded.kernel(), c.roots(),
                c.inputs().size(), c.lits(), intBatch(b), raises, answers(oracle), CallHook.NONE);
        if (type().isFloating() && f.overflowing()) {
          assertEquals(0, status, ctx + ": the kernel declined a floating overflow, which Spark "
              + "answers");
        }
        assertEquals(raises, status != 0, ctx + ": the kernel " + (status != 0 ? "declined"
            : "answered") + " a batch on which Spark " + (raises ? "raises" : "does not raise"));
        if (status == 0) {
          see(answered, oracle, c, b);
        }
      }
    } finally {
      loaded.loader().release();
    }
    if (!f.meantToRaise()) {
      for (int o = 0; o < answered.length; o++) {
        requireDiscriminates(f, c, o, f + " output " + o + " (" + oracle.sql(o)
            + ") over the batches the kernel answered, not those Spark raises on", answered[o]);
      }
    }
  }

  /** What Spark answers an output over some rows: the answers a constant kernel could not give. */
  private static final class Seen {
    boolean sawTrue;
    boolean sawFalse;
    boolean sawValue;
    boolean sawNull;
    boolean sawNullFromValid;
    boolean sawRaise;
    /** The raw bits of every value seen, for a floating type's infinity test. */
    final List<Long> values = new ArrayList<>();

    /** Whether a value seen is an infinity of {@code type}, a floating type's bits read back. */
    boolean sawInfinity(ConformanceType type) {
      boolean isDouble = type.spark().equals(DataTypes.DoubleType);
      for (long v : values) {
        if (isDouble ? Double.isInfinite(Double.longBitsToDouble(v))
            : Float.isInfinite(Float.intBitsToFloat((int) v))) {
          return true;
        }
      }
      return false;
    }

    void see(VarkaSparkOracle.Answer answer, boolean bothValid) {
      if (answer instanceof VarkaSparkOracle.Value v) {
        sawValue = true;
        values.add(v.v());
        sawTrue |= v.v() == 1L;
        sawFalse |= v.v() == 0L;
      } else if (answer instanceof VarkaSparkOracle.Raised) {
        sawRaise = true;
      } else {
        sawNull = true;
        sawNullFromValid |= bothValid;
      }
    }
  }

  /** Spark's answers over the rows of {@code b}, added to {@code seen} output by output. */
  private static void see(Seen[] seen, VarkaSparkOracle oracle, Compiled c, LongBatch b) {
    for (int i = 0; i < b.length(); i++) {
      var row = new ArrayList<Option<Object>>();
      boolean bothValid = true;
      for (int col = 0; col < c.inputs().size(); col++) {
        bothValid &= !b.nulls()[col][i];
        row.add(b.nulls()[col][i] ? Option.empty() : Option.apply((Object) b.data()[col][i]));
      }
      var seq = CollectionConverters.asScala(row).toList();
      for (int o = 0; o < seen.length; o++) {
        seen[o].see(oracle.answer(o, seq), bothValid);
      }
    }
  }

  /**
   * Each condition root true on a row and false on another, each value root valid on a row and,
   * where Spark's expression is null-intolerant, null on another.
   */
  private static void requireDiscriminates(Form f, Compiled c, int o, String at, Seen s) {
    if (f.kind() == Kind.CONDITION) {
      assertTrue(s.sawTrue && s.sawFalse, at + ": the rows never make it "
          + (s.sawTrue ? "false" : "true") + ", so a constant kernel would pass");
    } else {
      assertTrue(s.sawValue, at + ": the rows never give it a value");
      // A null-intolerant expression is null exactly where an input is; one that can mask a
      // null (IF, coalesce) need not be null anywhere, and so is not asked to be.
      assertTrue(s.sawNull || !c.outputs().get(o).nullIntolerant(),
          at + ": the rows never make it null");
    }
  }

  /**
   * The rule the narrow lanes' hand-written fixtures broke: over the fixture, each condition root
   * is true on a row and false on another, each value root valid on a row and, where Spark's
   * expression is null-intolerant, null on another; a form meant to reach the type's overflow
   * does (raises in ANSI, nulls a row of valid inputs in TRY; its LEGACY twin runs over the same
   * fixture, which the ANSI form shows reaches the overflow). A form whose raising is not its
   * point is held to the same rule again over the batches the kernel answered ({@link #check}),
   * since a batch Spark raises on is declined and compares no row.
   */
  private void requireTheFixtureDiscriminates(Form f, Compiled c, VarkaSparkOracle oracle,
      List<Row> rows) {
    for (int o = 0; o < c.outputs().size(); o++) {
      var s = new Seen();
      for (Row r : rows) {
        s.see(oracle.answer(o, rowOf(r, c.inputs())), !r.xNull() && !r.yNull());
      }
      String at = f + " output " + o + " (" + oracle.sql(o) + ") over the fixture";
      requireDiscriminates(f, c, o, at, s);
      if (type().isFloating()) {
        // A floating form overflows to an infinity, which Spark answers in every mode: some row
        // reaches one, none raises, and none nulls from valid inputs.
        if (f.overflowing()) {
          assertTrue(s.sawInfinity(type()), at + ": the fixture never overflows it to an infinity");
          assertTrue(!s.sawRaise, at + ": Spark raises on a floating overflow");
          assertTrue(!s.sawNullFromValid, at + ": Spark nulls a floating overflow");
        }
        continue;
      }
      if (f.overflowing() && f.mode() == Mode.ANSI) {
        assertTrue(s.sawRaise, at + ": the fixture never overflows it");
      }
      if (f.overflowing() && f.mode() == Mode.TRY) {
        assertTrue(s.sawNullFromValid, at + ": the fixture never overflows it to a null");
      }
    }
  }

  private static scala.collection.immutable.Seq<Option<Object>> rowOf(Row r,
      List<Integer> inputs) {
    var row = new ArrayList<Option<Object>>();
    for (int column : inputs) {
      row.add(r.isNull(column) ? Option.empty() : Option.apply((Object) r.value(column)));
    }
    return CollectionConverters.asScala(row).toList();
  }

  /** Whether Spark raises on some row of {@code b}: the batch an ANSI kernel must decline. */
  private static boolean raises(VarkaSparkOracle oracle, Compiled c, LongBatch b) {
    for (int i = 0; i < b.length(); i++) {
      var row = new ArrayList<Option<Object>>();
      for (int col = 0; col < c.inputs().size(); col++) {
        row.add(b.nulls()[col][i] ? Option.empty() : Option.apply((Object) b.data()[col][i]));
      }
      var seq = CollectionConverters.asScala(row).toList();
      for (int o = 0; o < c.outputs().size(); o++) {
        if (oracle.answer(o, seq) instanceof VarkaSparkOracle.Raised) {
          return true;
        }
      }
    }
    return false;
  }

  /** Spark's answers for one batch, as {@link VarkaKernelChecks} reads them. */
  private static SparkAnswers answers(VarkaSparkOracle oracle) {
    return new SparkAnswers() {
      @Override
      public VarkaKernelChecks.Answer answer(int output, long[] values, boolean[] isNull) {
        var row = new ArrayList<Option<Object>>();
        for (int c = 0; c < values.length; c++) {
          row.add(isNull[c] ? Option.empty() : Option.apply((Object) values[c]));
        }
        VarkaSparkOracle.Answer a = oracle.answer(output, CollectionConverters.asScala(row)
            .toList());
        if (a instanceof VarkaSparkOracle.Value v) {
          return new VarkaKernelChecks.Answer.Value(v.v());
        } else if (a instanceof VarkaSparkOracle.Raised r) {
          return new VarkaKernelChecks.Answer.Raised(r.e());
        }
        return new VarkaKernelChecks.Answer.Null();
      }

      @Override
      public String sql(int output) {
        return oracle.sql(output);
      }
    };
  }

  // -------------------------------------------------------------------------------------------
  // The registers
  // -------------------------------------------------------------------------------------------

  /** Each census type as a cast target's SQL, for the cast row: the type registry's. */
  private static final Map<String, String> CAST_TARGETS = castTargets();

  private static Map<String, String> castTargets() {
    var out = new HashMap<String, String>();
    for (VarkaTypes.Info i : VarkaTypes.all()) {
      if (i.census() != null) {
        out.put(i.census(), i.sql());
      }
    }
    return Map.copyOf(out);
  }

  /** A census entry: the types it admits, those it fuses today, and the rows that own the rest. */
  private record CensusEntry(Set<String> types, Set<String> fused, String rows) {}

  /**
   * A {@code casts.tsv} pair: the target and the rows that own its gaps. Whether a form of it
   * fuses is {@code casts_fused.tsv}'s answer for that form pair and mode (VARKA-486), which the
   * compiler writes; a form pair the file lacks fails rather than being guessed at.
   */
  private record CastPair(String from, String to, String rows) {
    boolean fusedIn(Mode mode, DataType source, DataType target) {
      return Registers.answers(source, target)[mode.ordinal()].equals("fused");
    }
  }

  /** The two registers, read once from the committed files. */
  private static final class Registers {
    private static final Map<String, CensusEntry> CENSUS = new HashMap<>();
    private static final List<String[]> CASTS = new ArrayList<>();
    /** {@code casts_fused.tsv} by {@code <from type>><to type>}: the LEGACY, ANSI, TRY answers. */
    private static final Map<String, String[]> FUSED = new HashMap<>();

    static {
      for (String[] f : tsv("census.tsv")) {
        CENSUS.put(f[0], new CensusEntry(Set.of(f[5].split(",")),
            Set.of(f[7].isEmpty() ? new String[0] : f[7].split(",")), f[10]));
      }
      CASTS.addAll(tsv("casts.tsv"));
      for (String[] f : tsv("casts_fused.tsv")) {
        FUSED.put(f[2] + ">" + f[3], new String[] {f[4], f[5], f[6]});
      }
    }

    /** The data lines of {@code m8/<name>}, split on tabs with empty fields kept. */
    private static List<String[]> tsv(String name) {
      String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
      assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
      try {
        List<String> lines = Files.readAllLines(
            Path.of(home, "sql", "varka", "plans", "m8", name), StandardCharsets.UTF_8);
        return lines.stream().skip(1).filter(l -> !l.isBlank()).map(l -> l.split("\t", -1))
            .toList();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    static CensusEntry census(String name) {
      CensusEntry e = CENSUS.get(name);
      assertTrue(e != null, "census.tsv has no entry " + name);
      return e;
    }

    /** The pairs from {@code from}, its identity cast left out: the analyzer removes that one. */
    static List<CastPair> casts(String from) {
      return CASTS.stream().filter(f -> f[0].equals(from) && !f[1].equals(from))
          .map(f -> new CastPair(f[0], f[1], f[9])).toList();
    }

    /**
     * {@code casts_fused.tsv}'s LEGACY, ANSI and TRY answers for the form pair; a pair the file
     * lacks fails rather than being guessed at.
     */
    static String[] answers(DataType source, DataType target) {
      String key = source.sql() + ">" + target.sql();
      String[] answers = FUSED.get(key);
      assertTrue(answers != null, "casts_fused.tsv has no line for " + key
          + ": add the form to VarkaCastMatrixSuite's TYPES and regenerate it");
      return answers;
    }

    /** Whether Spark's analyzer rejects the cast in {@code mode}: no form of it exists there. */
    static boolean illegal(DataType source, DataType target, Mode mode) {
      return answers(source, target)[mode.ordinal()].equals("illegal");
    }

    static CastPair cast(String from, String to) {
      return casts(from).stream().filter(p -> p.to().equals(to)).findFirst().orElseThrow();
    }
  }
}
