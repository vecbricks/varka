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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntBinaryOperator;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.Enumeration;
import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Abs$;
import org.apache.spark.sql.catalyst.expressions.Add$;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.BitwiseAnd;
import org.apache.spark.sql.catalyst.expressions.BitwiseCount;
import org.apache.spark.sql.catalyst.expressions.BitwiseNot;
import org.apache.spark.sql.catalyst.expressions.BitwiseOr;
import org.apache.spark.sql.catalyst.expressions.BitwiseXor;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.DateAdd;
import org.apache.spark.sql.catalyst.expressions.EqualTo;
import org.apache.spark.sql.catalyst.expressions.EvalMode;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Greatest$;
import org.apache.spark.sql.catalyst.expressions.GreaterThan$;
import org.apache.spark.sql.catalyst.expressions.If;
import org.apache.spark.sql.catalyst.expressions.If$;
import org.apache.spark.sql.catalyst.expressions.Literal$;
import org.apache.spark.sql.catalyst.expressions.Multiply$;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.Subtract$;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus$;
import org.apache.spark.sql.catalyst.expressions.Year;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.NarrowGroupStore;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.Batch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.CallHook;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.SparkAnswers;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.ByteType;
import org.apache.spark.sql.types.DateType;
import org.apache.spark.sql.types.IntegerType$;
import org.apache.spark.sql.types.ShortType;

/**
 * `TINYINT` and `SMALLINT` arithmetic in Spark's three evaluation modes, on both of
 * VARKA-329's arms (VARKA-330, `VARKA-329.md` 4): `LEGACY` wraps at the type's own width,
 * `ANSI` declines the batch Spark raises on, and `TRY` nulls the row it would raise on, for `+`,
 * `-`, `*`, the negation, `abs` and the `try_` forms. The expressions go through
 * {@link VarkaExpressionCompiler} and the emitted class, so each check holds the compiler's
 * admission, the emitter and the evaluator at once, and every row is held to
 * {@link VarkaSparkOracle}, Spark's own `eval`.
 *
 * <p>The named cases - `-(-128Y)`, `abs(-128Y)`, `127Y + 1Y`, `-128Y * -1Y` and the short
 * analogues, with in-range neighbours - run at every length from 1 to 129 and on four null
 * patterns; every pair of a `TINYINT` square runs in one 2^16-row batch per operator, mode and
 * arm, and the `SMALLINT` sweeps run a 2^24-row slice on every build and all 2^32 pairs as
 * opt-in tests. A `LEGACY` chain `(b1 + b2) * b3 - b4` is checked as a root and under
 * consumers that are not ring operations, which is where the deferred wrap is emitted.
 *
 * <p>In its own JVM: the native arm needs a narrow species beside the int one, and the
 * `SMALLINT` sweeps keep one class loaded across thousands of batches.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterNarrowArithSuite {

  /** One evaluation mode, as a Spark arithmetic expression carries it. */
  private record Mode(String name, Enumeration.Value evalMode) {
    /**
     * The flag `abs` and the negation take. Spark has no `try_negate` and no `try_abs`, so only
     * `ANSI` checks them; `LEGACY` and `TRY` wrap.
     */
    boolean checked() {
      return evalMode == EvalMode.ANSI();
    }
  }

  /** One emitted class with what its rows are checked against. */
  private record EmittedClass(String name, byte[] bytes, List<VarkaVectorIR> roots, int numInputs,
      int[] lits, VarkaSparkOracle oracle) {}

  /** The two arms: computed at the int lane and stored narrow, and computed narrow. */
  private record Arm(String name, VarkaEmitOptions options) {}

  private record NullPattern(String name, IntPredicate pattern) {}

  /** One named batch's outcome: its length, pattern, number of valid rows and answer. */
  private record Run(int length, String pattern, int valid, boolean answered) {}

  /**
   * A named binary case: the operator over two narrow operands, and whether the exact result
   * leaves the type's range - what `ANSI` raises on and `TRY` nulls.
   */
  private record Named(String label, String op, int left, int right) {
    boolean overflows(LaneType lane) {
      long exact = switch (op) {
        case "+" -> (long) left + right;
        case "-" -> (long) left - right;
        case "*" -> (long) left * right;
        default -> throw new IllegalArgumentException(op);
      };
      long[] range = bounds(lane);
      return exact < range[0] || exact > range[1];
    }

    Expression expression(List<Attribute> attrs, Enumeration.Value mode) {
      return binary(op, attrs.get(0), attrs.get(1), mode);
    }
  }

  /** A named unary case: the negation or `abs` of one narrow value. */
  private record Unary(String label, String op, int value) {
    boolean overflows(LaneType lane) {
      return value == bounds(lane)[0];
    }

    Expression expression(List<Attribute> attrs, boolean checked) {
      return switch (op) {
        case "neg" -> UnaryMinus$.MODULE$.apply(attrs.get(0), checked);
        case "abs" -> Abs$.MODULE$.apply(attrs.get(0), checked);
        default -> throw new IllegalArgumentException(op);
      };
    }
  }

  private record LaneCases(LaneType lane, List<Named> named, List<Unary> unary,
      List<Attribute> attrs) {}

  private record Sweep(int run, int took) {}

  private static final List<Attribute> BYTE_COLS = List.of(
      VarkaSqlResolve.column("b1", new ByteType()),
      VarkaSqlResolve.column("b2", new ByteType()));
  private static final List<Attribute> SHORT_COLS = List.of(
      VarkaSqlResolve.column("s1", new ShortType()),
      VarkaSqlResolve.column("s2", new ShortType()));
  private static final List<Attribute> CHAIN_COLS = List.of(
      BYTE_COLS.get(0), BYTE_COLS.get(1),
      VarkaSqlResolve.column("b3", new ByteType()),
      VarkaSqlResolve.column("b4", new ByteType()));

  private static final Mode LEGACY = new Mode("LEGACY", EvalMode.LEGACY());
  private static final Mode ANSI = new Mode("ANSI", EvalMode.ANSI());
  private static final Mode TRY = new Mode("TRY", EvalMode.TRY());

  /**
   * The two arms, each with `narrowLanes` set explicitly rather than left to the matrix's base:
   * under the matrix's `narrowLanes=true` configuration the base is the native arm, and an
   * unpinned "widened arm" would run the native lowering twice under the widened arm's name.
   */
  private static final Arm WIDENED_ARM =
      new Arm("the widened arm", VarkaMatrixRuns.BASE.toBuilder().narrowLanes(false).build());
  private static final Arm NATIVE_ARM =
      new Arm("the native arm", VarkaMatrixRuns.BASE.toBuilder().narrowLanes(true).build());

  /**
   * Both arms, the native one first: a configuration that cannot widen (`validityByWidth=false`)
   * cancels a test at the widened arm's decline, and running the native arm before it keeps that
   * arm's kernels checked in such a configuration rather than cancelled with it.
   */
  private static final List<Arm> ARMS = List.of(NATIVE_ARM, WIDENED_ARM);

  /** The lengths the named cases run at: every length to 129, the 64-tail ladder's top included. */
  private static final int[] LADDER = ladder();

  /** The null patterns every named case runs at. */
  private static final List<NullPattern> NULL_PATTERNS = List.of(
      new NullPattern("with no nulls", row -> false),
      new NullPattern("with every seventh row null", row -> row % 7 == 3),
      new NullPattern("with null 32-row groups", row -> (row / 32) % 3 == 0),
      new NullPattern("with every row null", row -> true));

  private static final List<Named> NAMED_BYTE = List.of(
      new Named("127Y + 1Y", "+", 127, 1),
      new Named("-128Y - 1Y", "-", -128, 1),
      new Named("-128Y * -1Y", "*", -128, -1),
      new Named("127Y * 127Y", "*", 127, 127),
      new Named("-128Y + -128Y", "+", -128, -128),
      new Named("126Y + 1Y", "+", 126, 1),
      new Named("-127Y - 1Y", "-", -127, 1),
      new Named("-128Y * 1Y", "*", -128, 1),
      new Named("127Y - 127Y", "-", 127, 127));

  private static final List<Named> NAMED_SHORT = List.of(
      new Named("32767S + 1S", "+", 32767, 1),
      new Named("-32768S - 1S", "-", -32768, 1),
      new Named("-32768S * -1S", "*", -32768, -1),
      new Named("32767S * 32767S", "*", 32767, 32767),
      new Named("-32768S + -32768S", "+", -32768, -32768),
      new Named("32766S + 1S", "+", 32766, 1),
      new Named("-32767S - 1S", "-", -32767, 1),
      new Named("-32768S * 1S", "*", -32768, 1),
      new Named("32767S - 32767S", "-", 32767, 32767));

  private static final List<Unary> NAMED_UNARY_BYTE = List.of(
      new Unary("-(-128Y)", "neg", -128), new Unary("abs(-128Y)", "abs", -128),
      new Unary("-127Y", "neg", -127), new Unary("abs(-127Y)", "abs", -127),
      new Unary("abs(0Y)", "abs", 0), new Unary("-0Y", "neg", 0));

  private static final List<Unary> NAMED_UNARY_SHORT = List.of(
      new Unary("-(-32768S)", "neg", -32768), new Unary("abs(-32768S)", "abs", -32768),
      new Unary("-32767S", "neg", -32767), new Unary("abs(-32767S)", "abs", -32767));

  private static final List<LaneCases> LANE_CASES = List.of(
      new LaneCases(LaneType.BYTE, NAMED_BYTE, NAMED_UNARY_BYTE, BYTE_COLS),
      new LaneCases(LaneType.SHORT, NAMED_SHORT, NAMED_UNARY_SHORT, SHORT_COLS));

  /** Every short value, in order: the second column of a sweep batch, and the first's values. */
  private static final int[] ALL_SHORTS = allShorts();

  /**
   * The 256 constant first operands of the always-on slice: the two extremes, zero and one, and
   * a spread of every 256th value, so all three overflow shapes are met.
   */
  private static final List<Integer> SLICE_OPERANDS = sliceOperands();

  private static final String[] OPS = {"+", "-", "*"};

  private static int[] ladder() {
    var lengths = new int[129];
    for (int i = 0; i < lengths.length; i++) {
      lengths[i] = i + 1;
    }
    return lengths;
  }

  private static int[] allShorts() {
    var values = new int[65536];
    for (int i = 0; i < values.length; i++) {
      values[i] = i - 32768;
    }
    return values;
  }

  private static List<Integer> sliceOperands() {
    var operands = new ArrayList<Integer>();
    operands.add((int) Short.MIN_VALUE);
    operands.add((int) Short.MAX_VALUE);
    operands.add(0);
    operands.add(1);
    for (int k = 4; k < 256; k++) {
      operands.add((k * 256) - 32768);
    }
    return operands;
  }

  private static long[] bounds(LaneType lane) {
    return switch (lane) {
      case BYTE -> new long[] {Byte.MIN_VALUE, Byte.MAX_VALUE};
      case SHORT -> new long[] {Short.MIN_VALUE, Short.MAX_VALUE};
      case INT -> new long[] {Integer.MIN_VALUE, Integer.MAX_VALUE};
      case LONG -> new long[] {Long.MIN_VALUE, Long.MAX_VALUE};
      case FLOAT, DOUBLE -> throw new IllegalArgumentException("no integer bounds at " + lane);
    };
  }

  private static Expression binary(String op, Expression left, Expression right,
      Enumeration.Value mode) {
    return switch (op) {
      case "+" -> Add$.MODULE$.apply(left, right, mode);
      case "-" -> Subtract$.MODULE$.apply(left, right, mode);
      case "*" -> Multiply$.MODULE$.apply(left, right, mode);
      default -> throw new IllegalArgumentException(op);
    };
  }

  /**
   * Whether the arm's lane count cannot widen `lane` (the kernel plan's narrow refusal): the
   * widened arm reads a narrow column with the narrow species of the int species' width, which
   * the Vector API does not have for a four-lane int species, and a width with no baked lane
   * count names no species at all. The question is asked of a widened drive on the native arm too,
   * because a checked narrow multiply is driven at the int lane on either arm (VARKA-329.md 2.4)
   * and meets the same refusal there. The compiler demotes such an entry and the row engine
   * answers it, which is what the checks below assert instead of running.
   */
  private static boolean cannotWiden(VarkaEmitOptions options, LaneType lane) {
    return VarkaKernelPlan.refusalOf(List.of(new ColumnRef(0, lane)),
        options.toBuilder().narrowLanes(false).build()).isPresent();
  }

  /**
   * Compiles `expr` over `attrs` at `options` and emits it. Where the arm's lane count cannot widen
   * `lane` the compiler demotes the entry and the row engine answers it, so the test is cancelled
   * naming that refusal - as `VarkaEmitterNarrowSuite` does - rather than passing with no kernel
   * run; a decline of any other kind fails it. A cancelled test shows in the matrix report's
   * cancelled column, where a silent pass would not.
   */
  private static EmittedClass emitArm(Expression expr, List<Attribute> attrs,
      LaneType lane, VarkaEmitOptions options, String where) {
    NamedExpression entry = VarkaSqlResolve.named(expr, "c");
    var entries = CollectionConverters.asScala(List.of(entry)).toSeq();
    var columns = CollectionConverters.asScala(attrs).toSeq();
    Optional<CompiledVarkaProjection> plan =
        VarkaExpressionCompiler.compile(entries, columns, options);
    if (plan.isEmpty()) {
      assertTrue(cannotWiden(options, lane), where + ": " + expr.sql()
          + " did not compile, and not because the width cannot widen the " + lane + " lane");
      assumeTrue(false, where + ": this configuration cannot widen the " + lane + " lane ("
          + VarkaKernelPlan.refusalOf(List.of(new ColumnRef(0, lane)),
              options.toBuilder().narrowLanes(false).build()).orElseThrow()
          + "), so the compiler declines the entry and the row engine answers it; the matrix "
          + "records the decline in sql/varka/matrix/skips.tsv (VARKA-329)");
    }
    CompiledVarkaProjection p = plan.get();
    String className =
        "org.apache.spark.sql.varka.execution.VarkaNarrowArith" + CLASS_COUNTER.incrementAndGet();
    byte[] bytes = VarkaLoopEmitter.emit(className, p.outputs(), p.inputOrdinals().size(),
        p.numLiterals(), null, null, options);
    var exprSeq = CollectionConverters.asScala(List.of(expr)).toSeq();
    var inputAttrs = CollectionConverters.asScala(
        p.inputOrdinals().stream().map(attrs::get).toList()).toSeq();
    var oracle = new VarkaSparkOracle(exprSeq, inputAttrs);
    int[] lits = p.literals().stream().mapToInt(Integer::intValue).toArray();
    return new EmittedClass(className, bytes, p.outputs(), p.inputOrdinals().size(), lits,
        oracle);
  }

  private static final java.util.concurrent.atomic.AtomicInteger CLASS_COUNTER =
      new java.util.concurrent.atomic.AtomicInteger(0);

  /** Loads an emitted class, runs {@code f}, and releases the loader. */
  private static void withKernel(EmittedClass emitted,
      java.util.function.Consumer<VarkaFusedKernel> f) {
    var loaded = VarkaEmitterFixtures.load(
        new VarkaEmitterFixtures.Emitted(emitted.name(), emitted.bytes()));
    try {
      f.accept(loaded.kernel());
    } finally {
      loaded.loader().release();
    }
  }

  /** Spark's answers for one batch, as the Java check reads them. */
  private static SparkAnswers answers(VarkaSparkOracle oracle) {
    return new SparkAnswers() {
      @Override
      public VarkaKernelChecks.Answer answer(int output, long[] values, boolean[] isNull) {
        var row = new ArrayList<Option<Object>>();
        for (int c = 0; c < values.length; c++) {
          row.add(isNull[c] ? Option.empty() : Option.<Object>apply(values[c]));
        }
        VarkaSparkOracle.Answer a = oracle.answer(output, CollectionConverters.asScala(row)
            .toSeq());
        if (a instanceof VarkaSparkOracle.Value v) {
          return new VarkaKernelChecks.Answer.Value(v.v());
        } else if (a instanceof VarkaSparkOracle.Raised r) {
          return new VarkaKernelChecks.Answer.Raised(r.e());
        }
        // The oracle's third answer is the Null singleton, which has no case class to name.
        return new VarkaKernelChecks.Answer.Null();
      }

      @Override
      public String sql(int output) {
        return oracle.sql(output);
      }
    };
  }

  /**
   * One batch on a loaded kernel: whether the batch was answered, and so compared row by row
   * with the reference evaluator and with Spark. A declined batch is one the row engine answers.
   */
  private static boolean run(EmittedClass emitted, VarkaFusedKernel kernel, Batch batch,
      String ctx, boolean spark) {
    return VarkaKernelChecks.runLoadedNarrow(ctx, kernel, emitted.roots(), emitted.numInputs(),
        emitted.lits(), batch, true, spark ? answers(emitted.oracle()) : null,
        CallHook.NONE) == 0;
  }

  private static Batch batch(int length, IntPredicate pattern, List<Integer> cols) {
    return batch(length, pattern, cols, false);
  }

  private static Batch batch(int length, IntPredicate pattern, List<Integer> cols,
      boolean forceMasked) {
    var nulls = new boolean[cols.size()][length];
    var data = new int[cols.size()][];
    for (int c = 0; c < cols.size(); c++) {
      for (int row = 0; row < length; row++) {
        nulls[c][row] = pattern.test(row);
      }
      data[c] = new int[length];
      Arrays.fill(data[c], cols.get(c));
    }
    return new Batch(length, nulls, data, forceMasked);
  }

  /**
   * Runs one named binary case at every length and null pattern on one arm. The valid-row count
   * lets a checked case tell "Spark raises" from "every row that would raise is null": a null
   * row does not raise, so an all-null batch is answered.
   */
  private static List<Run> namedRuns(Named c, LaneType lane, List<Attribute> attrs, Mode mode,
      VarkaEmitOptions options, String where) {
    Expression expr = c.expression(attrs, mode.evalMode());
    EmittedClass emitted = emitArm(expr, attrs, lane, options, where);
    var runs = new ArrayList<Run>();
    withKernel(emitted, kernel -> {
      for (int length : LADDER) {
        for (NullPattern np : NULL_PATTERNS) {
          boolean answered = run(emitted, kernel,
              batch(length, np.pattern(), List.of(c.left(), c.right())),
              where + " len=" + length + " " + np.name(), true);
          int valid = 0;
          for (int i = 0; i < length; i++) {
            if (!np.pattern().test(i)) {
              valid++;
            }
          }
          runs.add(new Run(length, np.name(), valid, answered));
        }
      }
    });
    return runs;
  }

  // -------------------------------------------------------------------------------------------
  // The width rule (the kernel plan's narrow refusal), which the arms rest on
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a widened narrow load is served exactly where the int species holds the narrow one")
  public void widenedNarrowLoadIsServedExactlyWhereTheIntSpeciesHoldsTheNarrowOne() {
    // The claims are the emitter's own admission rule, so the test pins its options rather than
    // taking the configuration's: the subject is the width, not the configuration.
    VarkaVectorIR byteCol = new ColumnRef(0, LaneType.BYTE);
    VarkaVectorIR shortCol = new ColumnRef(0, LaneType.SHORT);
    for (int lanes : new int[] {2, 4, 8, 16}) {
      boolean refusedByte =
          VarkaKernelPlan.refusalOf(List.of(byteCol), widened(lanes)).isPresent();
      boolean refusedShort =
          VarkaKernelPlan.refusalOf(List.of(shortCol), widened(lanes)).isPresent();
      // At every baked width up to sixteen int lanes both narrow loads are served: where the int
      // width has no narrow species of its own - a byte at four lanes, either lane at two - the
      // load reads the next width up and converts it in parts (VARKA-329's part-form load), so
      // neither lane is refused.
      boolean[] expected = new boolean[] {false, false};
      assertEquals(expected[0], refusedByte, "byte at " + lanes + " int lanes");
      assertEquals(expected[1], refusedShort, "short at " + lanes + " int lanes");
    }
    // A width with no baked lane count names no species: validityByWidth off always refuses.
    VarkaEmitOptions noWidth = VarkaEmitOptions.DEFAULTS.toBuilder().narrowLanes(false)
        .validityByWidth(false).build();
    assertTrue(VarkaKernelPlan.refusalOf(List.of(byteCol), noWidth).isPresent());
    assertTrue(VarkaKernelPlan.refusalOf(List.of(shortCol), noWidth).isPresent());
    // The native arm computes in the narrow lane itself, so nothing has to widen at any width.
    for (int lanes : new int[] {0, 2, 4, 8, 16}) {
      VarkaEmitOptions nativeArm = VarkaEmitOptions.DEFAULTS.toBuilder().narrowLanes(true)
          .lanesOverride(lanes).build();
      assertTrue(VarkaKernelPlan.refusalOf(List.of(byteCol), nativeArm).isEmpty(),
          "native at " + lanes + " int lanes");
      assertTrue(VarkaKernelPlan.refusalOf(List.of(shortCol), nativeArm).isEmpty(),
          "native at " + lanes + " int lanes");
    }
  }

  private static VarkaEmitOptions widened(int lanes) {
    return VarkaEmitOptions.DEFAULTS.toBuilder().narrowLanes(false).lanesOverride(lanes).build();
  }

  // -------------------------------------------------------------------------------------------
  // The named cases, mode by mode
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the named TINYINT and SMALLINT cases answer in LEGACY on both arms, every length")
  public void namedLegacyCasesAnswerOnBothArms() {
    for (Arm arm : ARMS) {
      for (LaneCases lc : LANE_CASES) {
        for (Named c : lc.named()) {
          String where = c.label() + " LEGACY " + arm.name();
          for (Run r : namedRuns(c, lc.lane(), lc.attrs(), LEGACY, arm.options(), where)) {
            assertTrue(r.answered(), where + ": the kernel declined the batch at length "
                + r.length() + " (" + r.pattern() + "), and LEGACY overflows at no value");
          }
        }
      }
    }
  }

  @Test
  @DisplayName("the named TRY cases answer and null exactly the overflowing rows on both arms")
  public void namedTryCasesAnswerAndNullTheOverflowingRows() {
    for (Arm arm : ARMS) {
      for (LaneCases lc : LANE_CASES) {
        for (Named c : lc.named()) {
          String where = c.label() + " TRY " + arm.name();
          // The harness holds every row to the reference's null and to Spark's null; what this
          // check adds is that the batch is answered at all, since a checked narrow lane under
          // TRY clears validity rather than condemning the batch.
          for (Run r : namedRuns(c, lc.lane(), lc.attrs(), TRY, arm.options(), where)) {
            assertTrue(r.answered(), where + ": the kernel declined the batch at length "
                + r.length() + " (" + r.pattern() + "), where Spark answers a null");
          }
        }
      }
    }
  }

  @Test
  @DisplayName("the named ANSI cases decline the batch where Spark raises, and answer the rest")
  public void namedAnsiCasesDeclineWhereSparkRaises() {
    for (Arm arm : ARMS) {
      for (LaneCases lc : LANE_CASES) {
        for (Named c : lc.named()) {
          String where = c.label() + " ANSI " + arm.name();
          boolean raises = c.overflows(lc.lane());
          for (Run r : namedRuns(c, lc.lane(), lc.attrs(), ANSI, arm.options(), where)) {
            // A pattern that nulls every row keeps Spark from raising, so the batch is answered;
            // this is also the check that a null row's value never reaches the range compare.
            assertEquals(!(raises && r.valid() > 0), r.answered(), where + ": at length "
                + r.length() + " (" + r.pattern() + ") the kernel "
                + (r.answered() ? "answered" : "declined") + " where Spark "
                + (raises && r.valid() > 0 ? "raises" : "answers"));
          }
        }
      }
    }
  }

  @Test
  @DisplayName("the named negation and abs cases follow the mode on both arms, every length")
  public void namedNegationAndAbsFollowTheMode() {
    for (Arm arm : ARMS) {
      for (LaneCases lc : LANE_CASES) {
        for (Mode mode : List.of(LEGACY, ANSI)) {
          for (Unary u : lc.unary()) {
            String where = u.label() + " " + mode.name() + " " + arm.name();
            EmittedClass emitted = emitArm(u.expression(lc.attrs(), mode.checked()),
                lc.attrs(), lc.lane(), arm.options(), where);
            withKernel(emitted, kernel -> {
              for (int length : LADDER) {
                for (NullPattern np : NULL_PATTERNS) {
                  boolean answered = run(emitted, kernel,
                      batch(length, np.pattern(), List.of(u.value())),
                      where + " len=" + length + " " + np.name(), true);
                  // As above: a null row does not raise, so only a valid one can condemn the
                  // batch.
                  boolean raises = mode.checked() && u.overflows(lc.lane());
                  if (raises) {
                    boolean anyValid = false;
                    for (int i = 0; i < length; i++) {
                      if (!np.pattern().test(i)) {
                        anyValid = true;
                        break;
                      }
                    }
                    raises = anyValid;
                  }
                  assertEquals(!raises, answered, where + ": at length " + length + " ("
                      + np.name() + ") the kernel " + (answered ? "answered" : "declined")
                      + " where Spark " + (raises ? "raises" : "answers"));
                }
              }
            });
          }
        }
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Every TINYINT pair, 2^16 rows in one batch
  // -------------------------------------------------------------------------------------------

  /** Row `i` holds the pair `((i >> 8) - 128, (i & 0xFF) - 128)`: every byte pair once each. */
  private static Batch byteSquare() {
    int length = 65536;
    var data = new int[2][];
    data[0] = new int[length];
    data[1] = new int[length];
    for (int i = 0; i < length; i++) {
      data[0][i] = (i >> 8) - 128;
      data[1][i] = (i & 0xFF) - 128;
    }
    return new Batch(length, new boolean[2][length], data, false);
  }

  /** The in-range square: every pair whose exact answer stays inside the byte. */
  private static Batch inRangeSquare(String op) {
    if (op.equals("*")) {
      int length = 529;
      var data = new int[2][];
      data[0] = new int[length];
      data[1] = new int[length];
      for (int i = 0; i < length; i++) {
        data[0][i] = i / 23 - 11;
        data[1][i] = i % 23 - 11;
      }
      return new Batch(length, new boolean[2][length], data, false);
    }
    int length = 16384;
    var data = new int[2][];
    data[0] = new int[length];
    data[1] = new int[length];
    for (int i = 0; i < length; i++) {
      data[0][i] = (i >> 7) - 64;
      data[1][i] = (i & 0x7F) - 64;
    }
    return new Batch(length, new boolean[2][length], data, false);
  }

  private static EmittedClass byteRoot(String op, Mode mode, VarkaEmitOptions options,
      String where) {
    return emitArm(new Named(op, op, 0, 0).expression(BYTE_COLS, mode.evalMode()), BYTE_COLS,
        LaneType.BYTE, options, where);
  }

  private static boolean answered(EmittedClass emitted, Batch b, String where) {
    var result = new boolean[1];
    withKernel(emitted, kernel -> result[0] = run(emitted, kernel, b, where, true));
    return result[0];
  }

  @Test
  @DisplayName("every TINYINT pair answers in LEGACY on both arms, 2^16 rows in one batch")
  public void everyTinyintPairAnswersInLegacy() {
    for (Arm arm : ARMS) {
      for (String op : OPS) {
        String where = "TINYINT " + op + " LEGACY " + arm.name();
        EmittedClass emitted = byteRoot(op, LEGACY, arm.options(), where);
        assertTrue(answered(emitted, byteSquare(), where + ", the square"),
            where + ": the kernel declined a LEGACY square, which overflows at no pair");
      }
    }
  }

  @Test
  @DisplayName("every TINYINT pair in TRY nulls exactly the overflowing rows, both arms")
  public void everyTinyintPairInTryNullsTheOverflowingRows() {
    for (Arm arm : ARMS) {
      for (String op : OPS) {
        String where = "TINYINT " + op + " TRY " + arm.name();
        EmittedClass emitted = byteRoot(op, TRY, arm.options(), where);
        assertTrue(answered(emitted, byteSquare(), where + ", the square"),
            where + ": the kernel declined a TRY square, where Spark answers a null");
      }
    }
  }

  @Test
  @DisplayName("the overflowing TINYINT square declines under ANSI and the in-range one answers")
  public void theOverflowingTinyintSquareDeclinesUnderAnsi() {
    for (Arm arm : ARMS) {
      for (String op : OPS) {
        String where = "TINYINT " + op + " ANSI " + arm.name();
        EmittedClass emitted = byteRoot(op, ANSI, arm.options(), where);
        // The square holds 127 + 1 and its kin, so Spark raises on a row of it under every
        // operator and the batch must decline rather than wrap. The in-range square must
        // answer, which is what shows the range compare does not condemn unconditionally.
        assertFalse(answered(emitted, byteSquare(), where + ", the square"),
            where + ": the kernel answered a square with an overflowing row");
        assertTrue(answered(emitted, inRangeSquare(op), where + ", the in-range square"),
            where + ": the kernel declined an in-range square");
      }
    }
  }

  @Test
  @DisplayName("the negation and abs over every TINYINT value answer in LEGACY and check in ANSI")
  public void theNegationAndAbsOverEveryTinyintValueFollowTheMode() {
    int length = 256;
    var all = new int[1][length];
    for (int i = 0; i < length; i++) {
      all[0][i] = i - 128;
    }
    int aboveLength = 255;
    var aboveMin = new int[1][aboveLength];
    for (int i = 0; i < aboveLength; i++) {
      aboveMin[0][i] = i - 127;
    }
    var allBatch = new Batch(length, new boolean[1][length], all, false);
    var aboveBatch = new Batch(aboveLength, new boolean[1][aboveLength], aboveMin, false);
    for (Arm arm : ARMS) {
      for (String op : List.of("neg", "abs")) {
        for (Mode mode : List.of(LEGACY, ANSI)) {
          boolean checked = mode.checked();
          String where = "TINYINT " + op + " " + mode.name() + " " + arm.name();
          EmittedClass emitted = emitArm(new Unary("v", op, -128).expression(BYTE_COLS,
              checked), BYTE_COLS, LaneType.BYTE, arm.options(), where);
          assertTrue(answered(emitted, aboveBatch, where + ", the values above the minimum"),
              where + ": the kernel declined a batch with no overflowing value");
          boolean allAnswered = answered(emitted, allBatch, where + ", every value");
          assertEquals(!checked, allAnswered, where + ": the kernel "
              + (allAnswered ? "answered" : "declined") + " the whole range, and Spark "
              + (checked ? "raises on its minimum" : "wraps it"));
        }
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // The narrow store's form (`narrowGroupStore`, VARKA-603)
  // -------------------------------------------------------------------------------------------

  /** The store's two forms, the reference first as every differential runs it. */
  private static final List<NarrowGroupStore> STORE_FORMS =
      List.of(NarrowGroupStore.MASKED, NarrowGroupStore.COMBINED);

  /** One shape whose narrow store the form changes: its expression, columns and store lane. */
  private record StoreShape(String label, Expression expr, List<Attribute> attrs, LaneType lane,
      LaneType stored) {}

  private static final List<Attribute> INT_COLS = List.of(
      VarkaSqlResolve.column("i", IntegerType$.MODULE$));

  /**
   * The shapes the form reaches: a checked byte multiply and the unary arithmetic, whose store
   * species spans two int groups at four lanes, a {@code CAST(int AS TINYINT)}, which reads no
   * narrow value at all - only its store is narrow - so the masked form's iteration is one
   * group where the combined form's is two, and a {@code CAST(int AS SMALLINT)}, whose store
   * spans two int groups at two lanes, where the combined form's {@code I2S} branch takes two
   * parts (VARKA-603).
   */
  private static List<StoreShape> storeShapes() {
    return List.of(
        new StoreShape("y1 * y2, ANSI", Multiply$.MODULE$.apply(BYTE_COLS.get(0),
            BYTE_COLS.get(1), EvalMode.ANSI()), BYTE_COLS, LaneType.BYTE, LaneType.BYTE),
        new StoreShape("try_multiply(y1, y2)", Multiply$.MODULE$.apply(BYTE_COLS.get(0),
            BYTE_COLS.get(1), EvalMode.TRY()), BYTE_COLS, LaneType.BYTE, LaneType.BYTE),
        new StoreShape("-y1", UnaryMinus$.MODULE$.apply(BYTE_COLS.get(0), false), BYTE_COLS,
            LaneType.BYTE, LaneType.BYTE),
        new StoreShape("CAST(i AS TINYINT)", new Cast(INT_COLS.get(0), new ByteType(),
            Option.empty(), EvalMode.LEGACY()), INT_COLS, LaneType.INT, LaneType.BYTE),
        new StoreShape("CAST(i AS SMALLINT)", new Cast(INT_COLS.get(0), new ShortType(),
            Option.empty(), EvalMode.LEGACY()), INT_COLS, LaneType.INT, LaneType.SHORT));
  }

  /** The loop methods of an emitted class, whichever bodies it has. */
  private static List<String> loopMethods(byte[] bytes) {
    var names = new ArrayList<String>();
    for (var method : ClassFile.of().parse(bytes).methods()) {
      if (method.code().isPresent() && method.methodName().stringValue().startsWith("loop")) {
        names.add(method.methodName().stringValue());
      }
    }
    return names;
  }

  /**
   * The {@code intoMemorySegment} calls one method makes on {@code owner}, as {@code [whole,
   * masked]}: the masked overload takes the lane mask as a fourth argument, so the descriptor's
   * parameter count tells the two apart. Read off the class file, so the count is a fact about
   * the emitted bytes rather than the emitter's source.
   */
  private static int[] narrowStores(byte[] bytes, String method, LaneType lane) {
    String owner = "jdk.incubator.vector." + (lane == LaneType.BYTE ? "ByteVector" : "ShortVector");
    int whole = 0;
    int masked = 0;
    for (var m : ClassFile.of().parse(bytes).methods()) {
      if (!m.methodName().equalsString(method) || m.code().isEmpty()) {
        continue;
      }
      for (var element : m.code().get()) {
        if (element instanceof InvokeInstruction invoke
            && invoke.owner().asInternalName().equals(owner.replace('.', '/'))
            && invoke.name().stringValue().equals("intoMemorySegment")) {
          if (invoke.typeSymbol().parameterCount() == 4) {
            masked++;
          } else {
            whole++;
          }
        }
      }
    }
    return new int[] {whole, masked};
  }

  @Test
  @DisplayName("the combined store leaves the loop one whole narrow store and no masked one")
  public void combinedStoreLeavesOneWholeNarrowStoreAndNoMaskedOne() {
    // The width and the form are the subject, so the options are pinned rather than taken from
    // the configuration. The masked form keeps its per-group count; the combined form holds
    // one whole store per run of `parts` groups, and no masked store at all.
    for (StoreShape shape : storeShapes()) {
      for (int lanes : new int[] {4, 2}) {
        for (boolean narrowLanes : new boolean[] {false, true}) {
          VarkaEmitOptions base = VarkaMatrixRuns.BASE.toBuilder()
              .narrowLanes(narrowLanes).lanesOverride(lanes).build();
          String where = shape.label() + " at " + lanes + " int lanes, narrowLanes="
              + narrowLanes;
          VarkaEmitOptions maskedOptions = base.withNarrowGroupStore(NarrowGroupStore.MASKED);
          VarkaEmitOptions combinedOptions =
              base.withNarrowGroupStore(NarrowGroupStore.COMBINED);
          EmittedClass masked = emitArm(shape.expr(), shape.attrs(), shape.lane(),
              maskedOptions, where + ", MASKED");
          EmittedClass combined = emitArm(shape.expr(), shape.attrs(), shape.lane(),
              combinedOptions, where + ", COMBINED");
          var methods = loopMethods(masked.bytes());
          assertFalse(methods.isEmpty(), where + ": the class has no loop method to count");
          boolean intDriven =
              VarkaKernelPlan.of(combined.roots(), combinedOptions).drive() == LaneType.INT;
          int species = Lane.widenLanes(lanes, Lane.of(shape.stored()));
          int parts = species > lanes ? species / lanes : 1;
          int groupsMasked =
              VarkaKernelPlan.of(masked.roots(), maskedOptions).narrowGroups();
          int groupsCombined =
              VarkaKernelPlan.of(combined.roots(), combinedOptions).narrowGroups();
          if (intDriven && parts > 1) {
            // The count the assertion divides must stand on its own, or a plan that
            // under-counted would divide to zero stores, as a kernel storing nothing does.
            assertTrue(groupsCombined >= parts && groupsCombined / parts > 0,
                where + ": the plan's " + groupsCombined + " groups hold no run of " + parts);
            assertEquals(0, groupsCombined % parts,
                where + ": the iteration does not hold whole runs of the store");
          }
          for (String method : methods) {
            int[] m = narrowStores(masked.bytes(), method, shape.stored());
            int[] c = narrowStores(combined.bytes(), method, shape.stored());
            String at = where + ", " + method;
            if (!intDriven) {
              // A narrow-driven kernel never reaches the int lane's store: the form changes
              // nothing there.
              assertEquals(m[0], c[0], at + ": the whole store count moved off the int lane");
              assertEquals(m[1], c[1], at + ": the masked store count moved off the int lane");
              continue;
            }
            if (parts == 1) {
              // The species is the group's own, so the store is whole under either form.
              assertEquals(groupsMasked, m[0], at + ": MASKED whole stores");
              assertEquals(0, m[1], at + ": MASKED masked stores");
              assertEquals(groupsCombined, c[0], at + ": COMBINED whole stores");
              assertEquals(0, c[1], at + ": COMBINED masked stores");
              continue;
            }
            assertEquals(0, m[0], at + ": MASKED whole stores");
            assertEquals(groupsMasked, m[1], at + ": MASKED masked stores");
            assertEquals(groupsCombined / parts, c[0], at + ": COMBINED whole stores");
            assertEquals(0, c[1], at + ": COMBINED masked stores");
          }
        }
      }
    }
  }

  @Test
  @DisplayName("every TINYINT pair answers under each narrow store form at four int lanes")
  public void everyTinyintPairAnswersUnderEachStoreFormAtFourIntLanes() {
    // The exhaustive square under each form: the combined store's parts are disjoint blocks of
    // the species, so a pair landing on any lane position still reaches its own row.
    for (Arm arm : ARMS) {
      for (NarrowGroupStore form : STORE_FORMS) {
        VarkaEmitOptions options = arm.options().toBuilder().lanesOverride(4)
            .narrowGroupStore(form).build();
        for (Mode mode : List.of(LEGACY, TRY)) {
          for (String op : OPS) {
            String where = "TINYINT " + op + " " + mode.name() + " " + arm.name()
                + " at four int lanes, " + form;
            EmittedClass emitted = byteRoot(op, mode, options, where);
            assertTrue(answered(emitted, byteSquare(), where + ", the square"),
                where + ": the kernel declined a square, where the form must answer as the "
                    + "reference does");
          }
        }
      }
    }
  }

  @Test
  @DisplayName("a checked multiply answers ragged batches under each store form at four and two "
      + "int lanes")
  public void checkedMultiplyAnswersRaggedBatchesUnderEachStoreForm() {
    // Lengths leaving 0, 1, lanes - 1, lanes and groups * lanes - 1 rows past the loop, 24 and
    // 40 crossing the back edge twice, and one body per length forcing the masked body over
    // all-valid data; each (lanes, arm, form) case emits its kernels once.
    Expression ansi = Multiply$.MODULE$.apply(BYTE_COLS.get(0), BYTE_COLS.get(1),
        EvalMode.ANSI());
    Expression tryMultiply = Multiply$.MODULE$.apply(BYTE_COLS.get(0), BYTE_COLS.get(1),
        EvalMode.TRY());
    Map<VarkaEmitOptions, EmittedClass> ansiClasses = new HashMap<>();
    Map<VarkaEmitOptions, EmittedClass> tryClasses = new HashMap<>();
    for (int lanes : new int[] {4, 2}) {
      for (int length : new int[] {5, 8, 9, 11, 12, 15, 24, 40}) {
        for (int[] data : List.of(new int[] {2, 3}, new int[] {-128, -128})) {
          for (int body = 0; body < 3; body++) {
            // Two null patterns and the masked body forced over all-valid data.
            IntPredicate nulls = body == 1 ? row -> row % 7 == 3 : row -> false;
            boolean forced = body == 2;
            for (Arm arm : ARMS) {
              for (NarrowGroupStore form : STORE_FORMS) {
                VarkaEmitOptions options = arm.options().toBuilder().lanesOverride(lanes)
                    .narrowGroupStore(form).build();
                String where = "y1 * y2 " + data[0] + "," + data[1] + " at " + length
                    + " rows, " + lanes + " int lanes, " + arm.name() + ", " + form
                    + (forced ? ", the masked body" : "");
                Batch batch = batch(length, nulls, List.of(data[0], data[1]), forced);
                boolean overflows = (long) data[0] * data[1] < Byte.MIN_VALUE
                    || (long) data[0] * data[1] > Byte.MAX_VALUE;
                EmittedClass h = ansiClasses.computeIfAbsent(options,
                    o -> emitArm(ansi, BYTE_COLS, LaneType.BYTE, o, where));
                withKernel(h, kernel -> assertEquals(!overflows,
                    run(h, kernel, batch, where + ", ANSI", true),
                    where + ": ANSI " + (overflows ? "answered an overflow" : "declined")
                        + " the batch"));
                EmittedClass i = tryClasses.computeIfAbsent(options,
                    o -> emitArm(tryMultiply, BYTE_COLS, LaneType.BYTE, o, where));
                withKernel(i, kernel -> assertTrue(
                    run(i, kernel, batch, where + ", TRY", true),
                    where + ": TRY declined a batch, where Spark answers it"));
              }
            }
          }
        }
      }
    }
  }


  // -------------------------------------------------------------------------------------------
  // The deferred wrap: a LEGACY chain under its root and under non-ring consumers
  // -------------------------------------------------------------------------------------------

  private record ChainShape(String name, UnaryOperator<Expression> consumer) {}

  private static final List<ChainShape> CHAIN_SHAPES = List.of(
      new ChainShape("as a root", c -> c),
      new ChainShape("under greatest", c -> Greatest$.MODULE$.apply(
          CollectionConverters.asScala(List.of(c, CHAIN_COLS.get(3))).toSeq())),
      new ChainShape("under abs", c -> Abs$.MODULE$.apply(c, false)),
      new ChainShape("under a choice", c -> If$.MODULE$.apply(
          GreaterThan$.MODULE$.apply(c, CHAIN_COLS.get(3)), CHAIN_COLS.get(0),
          CHAIN_COLS.get(1))));

  /**
   * A narrow pick passes the chain's unreduced value through and reduces once, where its reader
   * is not a ring node: at the store as a root, before abs under one. LEGACY only: the chain is a
   * branch here, which Spark evaluates only on the rows the condition picks it for, so an
   * overflow elsewhere raises nothing and the checked chains' tests do not apply.
   */
  private static final List<ChainShape> PICK_SHAPES = List.of(
      new ChainShape("picked by a choice", c -> pick(c)),
      new ChainShape("picked by a choice under abs", c -> Abs$.MODULE$.apply(pick(c), false)));

  /** The chain or the second column, as the fourth column and the first compare. */
  private static Expression pick(Expression chain) {
    return If$.MODULE$.apply(GreaterThan$.MODULE$.apply(CHAIN_COLS.get(3), CHAIN_COLS.get(0)),
        chain, CHAIN_COLS.get(1));
  }

  private static Expression chain(Enumeration.Value mode) {
    return Subtract$.MODULE$.apply(
        Multiply$.MODULE$.apply(
            Add$.MODULE$.apply(CHAIN_COLS.get(0), CHAIN_COLS.get(1), mode),
            CHAIN_COLS.get(2), mode),
        CHAIN_COLS.get(3), mode);
  }

  /** Data that stays in the byte range, and data whose intermediate sums leave it. */
  private record ChainData(String name, IntBinaryOperator value) {}

  private static final List<ChainData> CHAIN_DATA = List.of(
      new ChainData("in range", (c, i) -> ((i * (7 + 3 * c)) % 41) - 20),
      new ChainData("over the range",
          (c, i) -> i % 2 == 0 ? ((i * (5 + c)) % 61) - 30 : 100 - 20 * c));

  @Test
  @DisplayName("a LEGACY chain answers at its root and under every non-ring consumer, both arms")
  public void legacyChainAnswersUnderEveryNonRingConsumer() {
    for (Arm arm : ARMS) {
      for (ChainShape shape : Stream.concat(CHAIN_SHAPES.stream(), PICK_SHAPES.stream())
          .toList()) {
        for (ChainData data : CHAIN_DATA) {
          String where = "the chain " + shape.name() + " (" + data.name() + ") LEGACY "
              + arm.name();
          EmittedClass emitted = emitArm(shape.consumer().apply(chain(EvalMode.LEGACY())),
              CHAIN_COLS, LaneType.BYTE, arm.options(), where);
          withKernel(emitted, kernel -> {
            for (int n : LADDER) {
              for (NullPattern np : NULL_PATTERNS) {
                var cols = new int[4][n];
                for (int c = 0; c < 4; c++) {
                  for (int i = 0; i < n; i++) {
                    cols[c][i] = data.value().applyAsInt(c, i);
                  }
                }
                var nulls = new boolean[4][n];
                for (int c = 0; c < 4; c++) {
                  for (int i = 0; i < n; i++) {
                    nulls[c][i] = np.pattern().test(i);
                  }
                }
                assertTrue(run(emitted, kernel, new Batch(n, nulls, cols, false),
                        where + " len=" + n + " " + np.name(), true),
                    where + ": the kernel declined at length " + n + " (" + np.name() + ")");
              }
            }
          });
        }
      }
    }
  }

  @Test
  @DisplayName("a checked chain raises on its first overflowing node, so the batch declines")
  public void checkedChainRaisesOnItsFirstOverflowingNode() {
    // Every node is checked, so `(b1 + b2) * b3 - b4` over data whose sum alone leaves the byte
    // raises even where a later wrap would have brought the value back into range.
    for (Arm arm : ARMS) {
      for (ChainShape shape : CHAIN_SHAPES) {
        String where = "the chain " + shape.name() + " ANSI " + arm.name();
        EmittedClass emitted = emitArm(shape.consumer().apply(chain(EvalMode.ANSI())),
            CHAIN_COLS, LaneType.BYTE, arm.options(), where);
        withKernel(emitted, kernel -> {
          int n = 64;
          // 40 + 100 is 140 at the first node, out of range, though the later nodes and the
          // store would have brought some rows back into it.
          var cols = new int[4][n];
          Arrays.fill(cols[0], 40);
          Arrays.fill(cols[1], 100);
          Arrays.fill(cols[2], 1);
          Arrays.fill(cols[3], 0);
          assertFalse(run(emitted, kernel, new Batch(n, new boolean[4][n], cols, false),
                  where + ", overflowing", true),
              where + ": the kernel answered a chain whose first node overflows");
        });
      }
    }
  }

  @Test
  @DisplayName("a TRY chain nulls the overflowing row and keeps its neighbours")
  public void tryChainNullsTheOverflowingRow() {
    // The per-node check clears the row's validity bit, so the chain is answered and the
    // overflowing rows come out null beside valid neighbours, which the harness compares row by
    // row with Spark.
    for (Arm arm : ARMS) {
      for (ChainShape shape : CHAIN_SHAPES) {
        String where = "the chain " + shape.name() + " TRY " + arm.name();
        EmittedClass emitted = emitArm(shape.consumer().apply(chain(EvalMode.TRY())),
            CHAIN_COLS, LaneType.BYTE, arm.options(), where);
        withKernel(emitted, kernel -> {
          int n = 64;
          // Row `i` overflows at the first node exactly when `i` is odd, so every overflowing
          // row has a valid neighbour.
          var cols = new int[4][n];
          for (int i = 0; i < n; i++) {
            cols[0][i] = i % 2 == 0 ? 1 : 100;
          }
          Arrays.fill(cols[1], 100);
          Arrays.fill(cols[2], 1);
          Arrays.fill(cols[3], 0);
          assertTrue(run(emitted, kernel, new Batch(n, new boolean[4][n], cols, false),
                  where + ", alternating", true),
              where + ": the kernel declined a TRY chain, where Spark answers a null");
        });
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // SMALLINT: the 2^24-row slice on every build, and all 2^32 pairs opt-in
  // -------------------------------------------------------------------------------------------

  /**
   * Whether the rectangle of `op` over the constant operand `v` and every short holds a pair
   * whose exact result leaves the range - what Spark raises on under `ANSI`, and so what must
   * decline the batch. `+` and `-` are monotone in the second operand and `*` is linear in it,
   * so its two ends are its extremes.
   */
  private static boolean shortSquareOverflows(String op, int v) {
    long[] range = bounds(LaneType.SHORT);
    long lo = range[0];
    long hi = range[1];
    return switch (op) {
      case "+" -> v + hi > hi || v + lo < lo;
      case "-" -> v - hi < lo || v - lo > hi;
      case "*" -> {
        long atLo = (long) v * lo;
        long atHi = (long) v * hi;
        yield atLo < lo || atLo > hi || atHi < lo || atHi > hi;
      }
      default -> throw new IllegalArgumentException(op);
    };
  }

  /**
   * Whether the opt-in run asks for the sweep at `where`: all of them, or only those whose name
   * holds one of the comma-separated entries of `-Dvarka.sweep.filter`. The child JVM runs every
   * test whatever the parent's name filter, so this is how one 2^32 sweep is timed on its own -
   * the budget of 18 (`VARKA-329.md` 4.4) is extrapolated from that one.
   */
  private static boolean sweeps(String where) {
    String filters = System.getProperty("varka.sweep.filter");
    if (filters == null) {
      return true;
    }
    return Arrays.stream(filters.split(",")).anyMatch(where::contains);
  }

  /**
   * `op` over SMALLINT pairs against Spark: each operand against every short value, 2^16 rows a
   * batch. The reference holds every row; Spark every `oracleEvery`-th batch, since Spark for
   * row through 2^24 rows costs more than the kernel and the reference together (VARKA-329.md
   * 4.4).
   */
  private static Sweep sweepShort(EmittedClass emitted, VarkaFusedKernel kernel,
      List<Integer> operands, int oracleEvery, IntPredicate answered, String where) {
    int run = 0;
    int took = 0;
    for (int k = 0; k < operands.size(); k++) {
      int v = operands.get(k);
      int length = 65536;
      var data = new int[2][];
      data[0] = new int[length];
      Arrays.fill(data[0], v);
      data[1] = ALL_SHORTS;
      boolean got = run(emitted, kernel,
          new Batch(length, new boolean[2][length], data, false), where + " v=" + v,
          k % oracleEvery == 0);
      assertEquals(answered.test(v), got, where + ": v=" + v + " was "
          + (got ? "answered" : "declined"));
      run++;
      if (got) {
        took++;
      }
    }
    return new Sweep(run, took);
  }

  private static void shortSweep(Mode mode, String op, VarkaEmitOptions options,
      List<Integer> operands, int oracleEvery, String arm) {
    String where = "SMALLINT " + op + " " + mode.name() + " " + arm;
    EmittedClass emitted = emitArm(new Named(op, op, 0, 0).expression(SHORT_COLS,
        mode.evalMode()), SHORT_COLS, LaneType.SHORT, options, where);
    withKernel(emitted, kernel -> {
      long started = System.nanoTime();
      Sweep result = sweepShort(emitted, kernel, operands, oracleEvery,
          v -> mode != ANSI || !shortSquareOverflows(op, v), where);
      // Under a checked mode only the operands with no overflowing pair are answered, and the
      // prediction is exact: an unexecuted or unconditionally declining sweep fails here.
      long expected = operands.stream()
          .filter(v -> mode != ANSI || !shortSquareOverflows(op, v)).count();
      assertEquals(operands.size(), result.run(),
          where + ": ran " + result.run() + " of " + operands.size() + " batches");
      assertEquals(expected, result.took(),
          where + ": answered " + result.took() + " batches, expected " + expected);
      // One line per slice, so that the 2^32 sweeps' budget can be read off a build's own log.
      System.out.println(where + ": " + result.run() + " batches in "
          + (System.nanoTime() - started) / 1000000L + " ms");
    });
  }

  private static void sliceSweep(Mode mode, String op, Arm arm) {
    shortSweep(mode, op, arm.options(), SLICE_OPERANDS, 64, arm.name());
  }

  /** The 2^32 opt-in sweep: every short against every short, Spark on every 256th batch. */
  private static void fullSweep(Mode mode, String op, Arm arm) {
    assumeTrue("true".equals(System.getProperty("varka.sweep")),
        "set -Dvarka.sweep=true to sweep all 2^32 SMALLINT pairs");
    String where = "SMALLINT " + op + " " + mode.name() + " " + arm.name();
    assumeTrue(sweeps(where), "-Dvarka.sweep.filter does not name '" + where + "'");
    Expression expr = new Named(op, op, 0, 0).expression(SHORT_COLS, mode.evalMode());
    EmittedClass emitted = emitArm(expr, SHORT_COLS, LaneType.SHORT, arm.options(), where);
    var operands = new ArrayList<Integer>();
    for (int v : ALL_SHORTS) {
      operands.add(v);
    }
    withKernel(emitted, kernel -> {
      long started = System.nanoTime();
      long before = VarkaKernelChecks.SPARK_COMPARISONS.get();
      Sweep result = sweepShort(emitted, kernel, operands, 256,
          v -> mode != ANSI || !shortSquareOverflows(op, v), where);
      long seconds = (System.nanoTime() - started) / 1000000000L;
      long rows = VarkaKernelChecks.SPARK_COMPARISONS.get() - before;
      // Printed rather than logged: the child JVM's log does not reach the log of the run that
      // started it, and its output does, which is where a timed sweep is read.
      System.out.println(where + ": " + result.run() + " batches (" + result.took()
          + " answered) in " + seconds + " s, " + rows + " rows held to Spark");
    });
  }

  @Test
  @DisplayName("the oracle's share of a sweep batch, timed (opt-in: -Dvarka.sweep=true)")
  public void theOraclesShareOfASweepBatch() {
    // The 2^32 budget is extrapolated from the sweeps' own timings; this separates the Spark
    // oracle from the kernel and the reference on one 2^16-row batch of the same shape.
    assumeTrue("true".equals(System.getProperty("varka.sweep")),
        "set -Dvarka.sweep=true to time the oracle's share");
    int length = 65536;
    var data = new int[2][];
    data[0] = new int[length];
    Arrays.fill(data[0], 1);
    data[1] = ALL_SHORTS;
    Batch b = new Batch(length, new boolean[2][length], data, false);
    boolean timed = false;
    for (Arm arm : ARMS) {
      String where = "the oracle's share " + arm.name();
      // A filter that names one arm times that arm alone; the other is skipped, not the test.
      if (!sweeps(where)) {
        continue;
      }
      timed = true;
      EmittedClass emitted = emitArm(new Named("+", "+", 0, 0).expression(SHORT_COLS,
          EvalMode.LEGACY()), SHORT_COLS, LaneType.SHORT, arm.options(), where);
      withKernel(emitted, kernel -> {
        long without = 0L;
        long withOracle = 0L;
        for (int round = 0; round < 3; round++) {
          long t0 = System.nanoTime();
          run(emitted, kernel, b, where + ", no oracle, round " + round, false);
          long t1 = System.nanoTime();
          run(emitted, kernel, b, where + ", with the oracle, round " + round, true);
          long t2 = System.nanoTime();
          without += t1 - t0;
          withOracle += t2 - t1;
        }
        System.out.println(where + ": 2^16 rows in " + without / 3000
            + " us without the oracle and " + withOracle / 3000 + " us with it");
      });
    }
    assumeTrue(timed, "-Dvarka.sweep.filter names neither arm of the oracle's share");
  }

  // -------------------------------------------------------------------------------------------
  // The generated sweep tests: one method a mode, operator and arm, as ScalaTest named them
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a 2^24-row SMALLINT + slice in LEGACY agrees with Spark on the widened arm")
  public void a2Pow24RowSmallintAddSliceInLegacyOnTheWidenedArm() {
    sliceSweep(LEGACY, "+", WIDENED_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT + slice in LEGACY agrees with Spark on the native arm")
  public void a2Pow24RowSmallintAddSliceInLegacyOnTheNativeArm() {
    sliceSweep(LEGACY, "+", NATIVE_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT - slice in LEGACY agrees with Spark on the widened arm")
  public void a2Pow24RowSmallintSubtractSliceInLegacyOnTheWidenedArm() {
    sliceSweep(LEGACY, "-", WIDENED_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT - slice in LEGACY agrees with Spark on the native arm")
  public void a2Pow24RowSmallintSubtractSliceInLegacyOnTheNativeArm() {
    sliceSweep(LEGACY, "-", NATIVE_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT * slice in LEGACY agrees with Spark on the widened arm")
  public void a2Pow24RowSmallintMultiplySliceInLegacyOnTheWidenedArm() {
    sliceSweep(LEGACY, "*", WIDENED_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT * slice in LEGACY agrees with Spark on the native arm")
  public void a2Pow24RowSmallintMultiplySliceInLegacyOnTheNativeArm() {
    sliceSweep(LEGACY, "*", NATIVE_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT + slice in ANSI agrees with Spark on the widened arm")
  public void a2Pow24RowSmallintAddSliceInAnsiOnTheWidenedArm() {
    sliceSweep(ANSI, "+", WIDENED_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT + slice in ANSI agrees with Spark on the native arm")
  public void a2Pow24RowSmallintAddSliceInAnsiOnTheNativeArm() {
    sliceSweep(ANSI, "+", NATIVE_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT - slice in ANSI agrees with Spark on the widened arm")
  public void a2Pow24RowSmallintSubtractSliceInAnsiOnTheWidenedArm() {
    sliceSweep(ANSI, "-", WIDENED_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT - slice in ANSI agrees with Spark on the native arm")
  public void a2Pow24RowSmallintSubtractSliceInAnsiOnTheNativeArm() {
    sliceSweep(ANSI, "-", NATIVE_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT * slice in ANSI agrees with Spark on the widened arm")
  public void a2Pow24RowSmallintMultiplySliceInAnsiOnTheWidenedArm() {
    sliceSweep(ANSI, "*", WIDENED_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT * slice in ANSI agrees with Spark on the native arm")
  public void a2Pow24RowSmallintMultiplySliceInAnsiOnTheNativeArm() {
    sliceSweep(ANSI, "*", NATIVE_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT + slice in TRY agrees with Spark on the widened arm")
  public void a2Pow24RowSmallintAddSliceInTryOnTheWidenedArm() {
    sliceSweep(TRY, "+", WIDENED_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT + slice in TRY agrees with Spark on the native arm")
  public void a2Pow24RowSmallintAddSliceInTryOnTheNativeArm() {
    sliceSweep(TRY, "+", NATIVE_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT - slice in TRY agrees with Spark on the widened arm")
  public void a2Pow24RowSmallintSubtractSliceInTryOnTheWidenedArm() {
    sliceSweep(TRY, "-", WIDENED_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT - slice in TRY agrees with Spark on the native arm")
  public void a2Pow24RowSmallintSubtractSliceInTryOnTheNativeArm() {
    sliceSweep(TRY, "-", NATIVE_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT * slice in TRY agrees with Spark on the widened arm")
  public void a2Pow24RowSmallintMultiplySliceInTryOnTheWidenedArm() {
    sliceSweep(TRY, "*", WIDENED_ARM);
  }

  @Test
  @DisplayName("a 2^24-row SMALLINT * slice in TRY agrees with Spark on the native arm")
  public void a2Pow24RowSmallintMultiplySliceInTryOnTheNativeArm() {
    sliceSweep(TRY, "*", NATIVE_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for + in LEGACY on the widened arm "
      + "(opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForAddInLegacyOnTheWidenedArm() {
    fullSweep(LEGACY, "+", WIDENED_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for + in LEGACY on the native arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForAddInLegacyOnTheNativeArm() {
    fullSweep(LEGACY, "+", NATIVE_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for - in LEGACY on the widened arm "
      + "(opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForSubtractInLegacyOnTheWidenedArm() {
    fullSweep(LEGACY, "-", WIDENED_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for - in LEGACY on the native arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForSubtractInLegacyOnTheNativeArm() {
    fullSweep(LEGACY, "-", NATIVE_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for * in LEGACY on the widened arm "
      + "(opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForMultiplyInLegacyOnTheWidenedArm() {
    fullSweep(LEGACY, "*", WIDENED_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for * in LEGACY on the native arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForMultiplyInLegacyOnTheNativeArm() {
    fullSweep(LEGACY, "*", NATIVE_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for + in ANSI on the widened arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForAddInAnsiOnTheWidenedArm() {
    fullSweep(ANSI, "+", WIDENED_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for + in ANSI on the native arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForAddInAnsiOnTheNativeArm() {
    fullSweep(ANSI, "+", NATIVE_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for - in ANSI on the widened arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForSubtractInAnsiOnTheWidenedArm() {
    fullSweep(ANSI, "-", WIDENED_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for - in ANSI on the native arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForSubtractInAnsiOnTheNativeArm() {
    fullSweep(ANSI, "-", NATIVE_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for * in ANSI on the widened arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForMultiplyInAnsiOnTheWidenedArm() {
    fullSweep(ANSI, "*", WIDENED_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for * in ANSI on the native arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForMultiplyInAnsiOnTheNativeArm() {
    fullSweep(ANSI, "*", NATIVE_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for + in TRY on the widened arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForAddInTryOnTheWidenedArm() {
    fullSweep(TRY, "+", WIDENED_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for + in TRY on the native arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForAddInTryOnTheNativeArm() {
    fullSweep(TRY, "+", NATIVE_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for - in TRY on the widened arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForSubtractInTryOnTheWidenedArm() {
    fullSweep(TRY, "-", WIDENED_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for - in TRY on the native arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForSubtractInTryOnTheNativeArm() {
    fullSweep(TRY, "-", NATIVE_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for * in TRY on the widened arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForMultiplyInTryOnTheWidenedArm() {
    fullSweep(TRY, "*", WIDENED_ARM);
  }

  @Test
  @DisplayName("every SMALLINT pair for * in TRY on the native arm (opt-in: -Dvarka.sweep=true)")
  public void everySmallintPairForMultiplyInTryOnTheNativeArm() {
    fullSweep(TRY, "*", NATIVE_ARM);
  }

  // -------------------------------------------------------------------------------------------
  // The bitwise operators, bit_count, nullif and the date offset at a narrow type (VARKA-329)
  // -------------------------------------------------------------------------------------------

  private static Expression bitwise(String op, Expression l, Expression r) {
    return switch (op) {
      case "&" -> new BitwiseAnd(l, r);
      case "|" -> new BitwiseOr(l, r);
      case "^" -> new BitwiseXor(l, r);
      default -> throw new IllegalArgumentException(op);
    };
  }

  /** {@code ~x} and {@code bit_count(x)} over the first column, the unary cases of the family. */
  private static Expression unaryBit(String op, Expression x) {
    return switch (op) {
      case "~" -> new BitwiseNot(x);
      case "bit_count" -> new BitwiseCount(x);
      default -> throw new IllegalArgumentException(op);
    };
  }

  /** The TINYINT or SMALLINT values the unary cases run over, each once: 2^16 rows. */
  private static Batch everyValue(boolean tiny) {
    int length = 65536;
    var data = new int[1][length];
    for (int i = 0; i < length; i++) {
      data[0][i] = tiny ? (i & 0xFF) - 128 : i - 32768;
    }
    return new Batch(length, new boolean[1][length], data, false);
  }

  @Test
  @DisplayName("& | ^ over every TINYINT pair and every SMALLINT operand slice, both arms")
  public void bitwiseOperatorsAnswerEveryPairOnBothArms() {
    for (Arm arm : ARMS) {
      for (String op : new String[] {"&", "|", "^"}) {
        String where = "TINYINT " + op + " " + arm.name();
        EmittedClass bytes = emitArm(bitwise(op, BYTE_COLS.get(0), BYTE_COLS.get(1)), BYTE_COLS,
            LaneType.BYTE, arm.options(), where);
        assertTrue(answered(bytes, byteSquare(), where), where + ": the kernel declined");
        String shorts = "SMALLINT " + op + " " + arm.name();
        EmittedClass emitted = emitArm(bitwise(op, SHORT_COLS.get(0), SHORT_COLS.get(1)),
            SHORT_COLS, LaneType.SHORT, arm.options(), shorts);
        withKernel(emitted, kernel -> {
          Sweep sweep = sweepShort(emitted, kernel, SLICE_OPERANDS, 8, v -> true, shorts);
          assertEquals(SLICE_OPERANDS.size(), sweep.took(), shorts + ": a batch declined");
        });
      }
    }
  }

  @Test
  @DisplayName("~ and bit_count over every TINYINT and SMALLINT value, both arms")
  public void notAndBitCountAnswerEveryValueOnBothArms() {
    for (Arm arm : ARMS) {
      for (String op : new String[] {"~", "bit_count"}) {
        String where = op + " " + arm.name();
        EmittedClass bytes = emitArm(unaryBit(op, BYTE_COLS.get(0)), BYTE_COLS, LaneType.BYTE,
            arm.options(), "TINYINT " + where);
        assertTrue(answered(bytes, everyValue(true), "TINYINT " + where),
            "TINYINT " + where + ": the kernel declined");
        EmittedClass shorts = emitArm(unaryBit(op, SHORT_COLS.get(0)), SHORT_COLS,
            LaneType.SHORT, arm.options(), "SMALLINT " + where);
        assertTrue(answered(shorts, everyValue(false), "SMALLINT " + where),
            "SMALLINT " + where + ": the kernel declined");
      }
    }
  }

  @Test
  @DisplayName("narrow & | ^ ~ bit_count and nullif at every length and null pattern, both arms")
  public void narrowBitwiseAndNullifAnswerUnderEveryNullPattern() {
    int[][] pairs = {{-128, 127}, {127, 127}, {-128, -128}, {0, -1}, {-32768, 32767},
        {32767, -32768}, {-1, -1}, {5, 5}};
    for (Arm arm : ARMS) {
      for (boolean tiny : new boolean[] {true, false}) {
        List<Attribute> cols = tiny ? BYTE_COLS : SHORT_COLS;
        LaneType lane = tiny ? LaneType.BYTE : LaneType.SHORT;
        var type = tiny ? new ByteType() : new ShortType();
        List<Expression> exprs = new ArrayList<>();
        for (String op : new String[] {"&", "|", "^"}) {
          exprs.add(bitwise(op, cols.get(0), cols.get(1)));
        }
        exprs.add(unaryBit("~", cols.get(0)));
        exprs.add(unaryBit("bit_count", cols.get(0)));
        // The operators under each other and under a ring node, whose unreduced wrapping sum on
        // the widened arm must be reduced before the bitwise operation reads it.
        exprs.add(unaryBit("bit_count", bitwise("&", cols.get(0), cols.get(1))));
        exprs.add(bitwise("^", Add$.MODULE$.apply(cols.get(0), cols.get(1), EvalMode.LEGACY()),
            cols.get(1)));
        exprs.add(Add$.MODULE$.apply(bitwise("|", cols.get(0), cols.get(1)), cols.get(1),
            EvalMode.LEGACY()));
        // nullif(a, b) is if(a = b, NULL, a): the null of the narrow lane beside a column.
        exprs.add(new If(new EqualTo(cols.get(0), cols.get(1)),
            Literal$.MODULE$.create(null, type), cols.get(0)));
        // nullifzero(a) is if(a = 0, NULL, a).
        Object zero = tiny ? (Object) Byte.valueOf((byte) 0) : Short.valueOf((short) 0);
        exprs.add(new If(new EqualTo(cols.get(0), Literal$.MODULE$.create(zero, type)),
            Literal$.MODULE$.create(null, type), cols.get(0)));
        for (Expression expr : exprs) {
          String where = expr.sql() + " " + arm.name();
          EmittedClass emitted = emitArm(expr, cols, lane, arm.options(), where);
          withKernel(emitted, kernel -> {
            for (int[] pair : pairs) {
              if (tiny && (pair[0] != (byte) pair[0] || pair[1] != (byte) pair[1])) {
                continue;
              }
              for (int length : new int[] {1, 7, 31, 64, 65, 130}) {
                for (NullPattern np : NULL_PATTERNS) {
                  assertTrue(run(emitted, kernel,
                      batch(length, np.pattern(), List.of(pair[0], pair[1])),
                      where + " " + pair[0] + "," + pair[1] + " len=" + length + " " + np.name(),
                      true), where + ": the kernel declined");
                }
              }
            }
          });
        }
      }
    }
  }

  @Test
  @DisplayName("date_add with a TINYINT or SMALLINT offset answers every offset, both arms")
  public void dateAddOfANarrowOffsetAnswersEveryOffset() {
    for (Arm arm : ARMS) {
      for (boolean tiny : new boolean[] {true, false}) {
        var offset = tiny ? BYTE_COLS.get(0) : SHORT_COLS.get(0);
        var cols = List.of(VarkaSqlResolve.column("d", new DateType()), offset);
        int length = 65536;
        var data = new int[2][length];
        for (int i = 0; i < length; i++) {
          data[0][i] = i * 13 - 400000;
          data[1][i] = tiny ? (i & 0xFF) - 128 : i - 32768;
        }
        // The sum itself, and a calendar field of it, which arms the range guard on the offset.
        for (Expression expr : List.of(new DateAdd(cols.get(0), offset),
            new Year(new DateAdd(cols.get(0), offset)))) {
          String where = (tiny ? "TINYINT " : "SMALLINT ") + expr.sql() + " " + arm.name();
          EmittedClass emitted = emitArm(expr, cols, tiny ? LaneType.BYTE : LaneType.SHORT,
              arm.options(), where);
          assertTrue(answered(emitted, new Batch(length, new boolean[2][length], data, false),
              where), where + ": the kernel declined");
        }
      }
    }
  }
}
