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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.Enumeration;
import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.EqualTo;
import org.apache.spark.sql.catalyst.expressions.EvalMode;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Greatest;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaDecline;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.Batch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.CallHook;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.LongBatch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.SparkAnswers;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * The integral casts among {@code TINYINT}, {@code SMALLINT}, {@code INT}, {@code BIGINT} and
 * {@code BOOLEAN} (VARKA-332, `VARKA-332.md` 3.1 and 3.4), through {@link VarkaExpressionCompiler}
 * and the emitted class, every row held to {@link VarkaSparkOracle}, Spark's own {@code Cast.eval},
 * and to the reference: the narrow sources exhausted, every value of each in one batch, through
 * every target in the three modes on both narrow arms; the named boundary cases of the wider
 * sources, each with a null beside it; and the narrow load and store at the long width at every
 * baked width. Under {@code ANSI} a batch holding a value Spark raises {@code CAST_OVERFLOW} on
 * must decline and every other must answer; a {@code TRY} narrowing declines to compile, its
 * null being the row's second step.
 *
 * <p>In its own JVM: the native arm needs a narrow species beside the int one.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterIntegralCastSuite {

  private record Mode(String name, Enumeration.Value evalMode) {}

  private record Arm(String name, VarkaEmitOptions options) {}

  /** One source or target type, with the column a source is read from. */
  private record Type(String sql, DataType spark, Attribute column) {
    boolean integral() {
      return !DataTypes.BooleanType.equals(spark);
    }

    VarkaTypes.IntegralRange range() {
      return VarkaTypes.integralRange(VarkaTypes.lane(spark).orElseThrow());
    }
  }

  /** One compiled projection with what its rows are checked against. */
  private record Kernel(String name, byte[] bytes, List<VarkaVectorIR> roots, int numInputs,
      int[] lits, long[] longLits, boolean longLane, VarkaSparkOracle oracle) {}

  private static final Mode LEGACY = new Mode("LEGACY", EvalMode.LEGACY());
  private static final Mode ANSI = new Mode("ANSI", EvalMode.ANSI());
  private static final Mode TRY = new Mode("TRY", EvalMode.TRY());
  private static final List<Mode> MODES = List.of(LEGACY, ANSI, TRY);

  /** Both arms, pinned as `VarkaEmitterNarrowArithSuite` pins them, the native one first. */
  private static final List<Arm> ARMS = List.of(
      new Arm("the native arm", VarkaMatrixRuns.BASE.toBuilder().narrowLanes(true).build()),
      new Arm("the widened arm", VarkaMatrixRuns.BASE.toBuilder().narrowLanes(false).build()));

  private static final Type TINYINT = type("TINYINT", DataTypes.ByteType, "y");
  private static final Type SMALLINT = type("SMALLINT", DataTypes.ShortType, "s");
  private static final Type INT = type("INT", DataTypes.IntegerType, "i");
  private static final Type BIGINT = type("BIGINT", DataTypes.LongType, "l");
  private static final Type BOOLEAN = type("BOOLEAN", DataTypes.BooleanType, "b");
  private static final List<Type> TARGETS = List.of(TINYINT, SMALLINT, INT, BIGINT, BOOLEAN);

  private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

  private static Type type(String sql, DataType spark, String column) {
    return new Type(sql, spark, VarkaSqlResolve.column(column, spark));
  }

  private static Expression cast(Expression child, Type to, Mode mode) {
    return new Cast(child, to.spark(), Option.empty(), mode.evalMode());
  }

  /** Whether the cast from {@code from} to {@code to} keeps fewer bits: the guarded kind. */
  private static boolean narrows(Type from, Type to) {
    return to.integral() && from.integral() && !to.range().contains(from.range());
  }

  /** Whether {@code reason} is a width the configuration cannot load a narrow column at. */
  private static boolean widthDecline(String reason) {
    return reason.contains("has no load at") || reason.contains("has no widening load at")
        || reason.contains("needs a baked");
  }

  /**
   * {@code expr} over {@code columns} compiled and emitted at {@code options}, or the reason it
   * declines.
   */
  private static Object compile(Expression expr, List<Attribute> columns,
      VarkaEmitOptions options) {
    NamedExpression entry = VarkaSqlResolve.named(expr, "c");
    var entries = CollectionConverters.asScala(List.of(entry)).toSeq();
    var cols = CollectionConverters.asScala(columns).toSeq();
    Optional<CompiledVarkaProjection> plan =
        VarkaExpressionCompiler.compile(entries, cols, options);
    if (plan.isEmpty()) {
      return VarkaExpressionCompiler.declines(entries, cols, options).values().stream()
          .findFirst().map(VarkaDecline::reason).orElse("declined with no reason");
    }
    CompiledVarkaProjection p = plan.get();
    String name = "org.apache.spark.sql.varka.execution.VarkaIntegralCast"
        + CLASS_COUNTER.incrementAndGet();
    byte[] bytes = VarkaLoopEmitter.emit(name, p.outputs(), p.inputOrdinals().size(),
        p.numLiterals(), null, null, options);
    var oracle = new VarkaSparkOracle(CollectionConverters.asScala(List.of(expr)).toSeq(),
        CollectionConverters.asScala(p.inputOrdinals().stream().map(columns::get).toList())
            .toSeq());
    int[] lits = p.literals().stream().mapToInt(Integer::intValue).toArray();
    long[] longLits = p.longLiterals().stream().mapToLong(Long::longValue).toArray();
    return new Kernel(name, bytes, p.outputs(), p.inputOrdinals().size(), lits, longLits,
        p.lane(options).bits() == Long.SIZE, oracle);
  }

  /** Spark's answers for one batch, as {@link VarkaKernelChecks} reads them. */
  private static SparkAnswers answers(VarkaSparkOracle oracle) {
    return new SparkAnswers() {
      @Override
      public VarkaKernelChecks.Answer answer(int output, long[] values, boolean[] isNull) {
        VarkaSparkOracle.Answer a = oracle.answer(output, row(values, isNull));
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

  private static scala.collection.immutable.Seq<Option<Object>> row(long[] values,
      boolean[] isNull) {
    var row = new ArrayList<Option<Object>>();
    for (int c = 0; c < values.length; c++) {
      row.add(isNull[c] ? Option.empty() : Option.apply((Object) values[c]));
    }
    return CollectionConverters.asScala(row).toList();
  }

  /** Whether Spark raises on some row of {@code b}: the batch an ANSI kernel must decline. */
  private static boolean raises(Kernel k, LongBatch b) {
    var values = new long[k.numInputs()];
    var isNull = new boolean[k.numInputs()];
    for (int i = 0; i < b.length(); i++) {
      for (int c = 0; c < values.length; c++) {
        values[c] = b.data()[c][i];
        isNull[c] = b.nulls()[c][i];
      }
      if (k.oracle().answer(0, row(values, isNull)) instanceof VarkaSparkOracle.Raised) {
        return true;
      }
    }
    return false;
  }

  private static Batch intBatch(LongBatch b) {
    var data = new int[b.data().length][];
    for (int c = 0; c < data.length; c++) {
      data[c] = new int[b.length()];
      for (int i = 0; i < b.length(); i++) {
        data[c][i] = (int) b.data()[c][i];
      }
    }
    return new Batch(b.length(), b.nulls(), data, b.forceMasked());
  }

  /**
   * Runs {@code batches} on {@code k}: each is compared row by row with the reference and Spark
   * where the kernel answers, and must decline exactly where Spark raises on a row. Returns how
   * many batches were answered.
   */
  private static int runAll(Kernel k, List<LongBatch> batches, String where) {
    var loaded = VarkaEmitterFixtures.load(new VarkaEmitterFixtures.Emitted(k.name(),
        k.bytes()));
    int answered = 0;
    try {
      int n = 0;
      for (LongBatch b : batches) {
        String ctx = where + " batch " + n++ + " length " + b.length();
        boolean raises = raises(k, b);
        int status = k.longLane()
            ? VarkaKernelChecks.runLoadedLong(ctx, loaded.kernel(), k.roots(), k.numInputs(),
                k.longLits(), k.lits(), b, raises, answers(k.oracle()), CallHook.NONE)
            : VarkaKernelChecks.runLoadedNarrow(ctx, loaded.kernel(), k.roots(), k.numInputs(),
                k.lits(), intBatch(b), raises, answers(k.oracle()), CallHook.NONE);
        assertEquals(raises, status != 0, ctx + ": the kernel " + (status != 0 ? "declined"
            : "answered") + " a batch on which Spark " + (raises ? "raises" : "does not raise"));
        if (status == 0) {
          answered++;
        }
      }
    } finally {
      loaded.loader().release();
    }
    return answered;
  }

  /** One column of {@code values} with the rows {@code nulls} names null. */
  private static LongBatch column(long[] values, IntPredicate nulls) {
    var data = new long[][] {values.clone()};
    var isNull = new boolean[1][values.length];
    for (int i = 0; i < values.length; i++) {
      isNull[0][i] = nulls.test(i);
    }
    return new LongBatch(values.length, isNull, data, false);
  }

  private static long[] every(Type t) {
    VarkaTypes.IntegralRange r = t.range();
    var out = new long[(int) (r.max() - r.min() + 1)];
    for (int i = 0; i < out.length; i++) {
      out[i] = r.min() + i;
    }
    return out;
  }

  private static long[] within(long[] values, VarkaTypes.IntegralRange range) {
    return java.util.Arrays.stream(values)
        .filter(v -> v >= range.min() && v <= range.max()).toArray();
  }

  private static long[] prefix(long[] values, int length) {
    var out = new long[length];
    for (int i = 0; i < length; i++) {
      out[i] = values[(i * 37 + 11) % values.length];
    }
    return out;
  }

  /**
   * The batches a source's values run as: all of them dense and with every seventh row null,
   * and a ladder of short tails, so the dense and masked bodies and the epilogue each see them.
   */
  private static List<LongBatch> batches(long[] values) {
    var out = new ArrayList<LongBatch>();
    out.add(column(values, i -> false));
    out.add(column(values, i -> i % 7 == 3));
    for (int length : new int[] {1, 2, 3, 5, 7, 8, 9, 15, 16, 17, 31, 33, 63, 64, 65}) {
      out.add(column(prefix(values, length), i -> i % 5 == 1));
    }
    return out;
  }

  /**
   * One cast over every batch of {@code values} at one arm: a {@code TRY} narrowing must decline
   * naming the step that builds it, a width the configuration cannot load at cancels, and
   * anything else must fuse and answer as Spark does. Returns the cancellation, if any.
   */
  private static Optional<String> check(Type from, Type to, Mode mode, Arm arm, long[] values) {
    Expression expr = cast(from.column(), to, mode);
    String where = expr.sql() + " " + mode.name() + " on " + arm.name();
    Object compiled = compile(expr, List.of(from.column()), arm.options());
    if (mode == TRY && narrows(from, to)) {
      assertTrue(compiled instanceof String reason && reason.contains("try_cast")
          && reason.contains("VARKA-332"), where + ": " + compiled);
      return Optional.empty();
    }
    if (compiled instanceof String reason) {
      if (widthDecline(reason)) {
        return Optional.of(where + ": " + reason);
      }
      fail(where + " declined: " + reason);
    }
    Kernel k = (Kernel) compiled;
    runAll(k, batches(values), where);
    if (mode == ANSI && narrows(from, to)) {
      // The batches above that hold an out-of-range value declined; the in-range values answer.
      int answered = runAll(k, batches(within(values, to.range())), where + " in range");
      assertTrue(answered > 0, where + ": no in-range batch was answered");
    }
    return Optional.empty();
  }

  // -------------------------------------------------------------------------------------------
  // The narrow sources, exhausted
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("every TINYINT value casts to each integral type and BOOLEAN in the three modes, "
      + "both arms")
  public void everyTinyIntValueCasts() {
    exhaust(TINYINT);
  }

  @Test
  @DisplayName("every SMALLINT value casts to each integral type and BOOLEAN in the three modes, "
      + "both arms")
  public void everySmallIntValueCasts() {
    exhaust(SMALLINT);
  }

  private static void exhaust(Type from) {
    long[] values = every(from);
    var cancelled = new ArrayList<String>();
    for (Type to : TARGETS) {
      if (to.equals(from)) {
        continue;
      }
      for (Mode mode : MODES) {
        for (Arm arm : ARMS) {
          check(from, to, mode, arm, values).ifPresent(cancelled::add);
        }
      }
    }
    assumeTrue(cancelled.isEmpty(), "this configuration cannot load a narrow column at the long "
        + "width, so the compiler declines these and the row engine answers them: " + cancelled);
  }

  @Test
  @DisplayName("BOOLEAN casts to each integral type in the three modes on both arms, both data "
      + "bits under a null, at every length to 129")
  public void booleanCastsToEachIntegralType() {
    var cancelled = new ArrayList<String>();
    var batches = new ArrayList<LongBatch>();
    for (int length = 1; length <= 129; length++) {
      var values = new long[length];
      for (int i = 0; i < length; i++) {
        values[i] = (i * 5 + length) % 3 == 0 ? 0 : 1;
      }
      // A null on every third row: the harness alternates the data bit under a null, so both
      // null states are met, beside both valid ones.
      batches.add(column(values, i -> i % 3 == 2));
    }
    for (Type to : List.of(TINYINT, SMALLINT, INT, BIGINT)) {
      for (Mode mode : MODES) {
        for (Arm arm : ARMS) {
          Expression expr = cast(BOOLEAN.column(), to, mode);
          String where = expr.sql() + " " + mode.name() + " on " + arm.name();
          Object compiled = compile(expr, List.of(BOOLEAN.column()), arm.options());
          if (compiled instanceof String reason) {
            if (widthDecline(reason)) {
              cancelled.add(where + ": " + reason);
              continue;
            }
            fail(where + " declined: " + reason);
          }
          assertEquals(batches.size(), runAll((Kernel) compiled, batches, where), where);
        }
      }
    }
    assumeTrue(cancelled.isEmpty(), "declined by width: " + cancelled);
  }

  // -------------------------------------------------------------------------------------------
  // The named cases of the wider sources
  // -------------------------------------------------------------------------------------------

  /** `VARKA-332.md` 3.4's values, each source's in its own range, every one beside a null. */
  private static long[] named(Type from) {
    long[] all = {127, 128, -128, -129, 32767, 32768, -32768, -32769, 0, 1, -1,
        Integer.MAX_VALUE, Integer.MIN_VALUE, 1L << 31, -(1L << 31) - 1, Long.MIN_VALUE,
        Long.MAX_VALUE};
    return within(all, from.range());
  }

  @Test
  @DisplayName("the named boundary values cast from SMALLINT, INT and BIGINT to each narrower "
      + "type and BOOLEAN in the three modes, each beside a null")
  public void namedBoundaryValuesCast() {
    var cancelled = new ArrayList<String>();
    for (Type from : List.of(SMALLINT, INT, BIGINT)) {
      for (Type to : TARGETS) {
        if (to.equals(from) || (to.integral() && !narrows(from, to))) {
          continue;
        }
        for (Mode mode : MODES) {
          for (Arm arm : ARMS) {
            Expression expr = cast(from.column(), to, mode);
            String where = expr.sql() + " " + mode.name() + " on " + arm.name();
            Object compiled = compile(expr, List.of(from.column()), arm.options());
            if (mode == TRY && narrows(from, to)) {
              assertTrue(compiled instanceof String reason && reason.contains("try_cast"),
                  where + ": " + compiled);
              continue;
            }
            if (compiled instanceof String reason) {
              if (widthDecline(reason)) {
                cancelled.add(where + ": " + reason);
                continue;
              }
              fail(where + " declined: " + reason);
            }
            var batches = new ArrayList<LongBatch>();
            for (long v : named(from)) {
              // The value beside a null, then alone at a length that reaches the dense body.
              batches.add(column(new long[] {v, v, v}, i -> i == 1));
              long[] many = new long[65];
              java.util.Arrays.fill(many, v);
              batches.add(column(many, i -> false));
            }
            runAll((Kernel) compiled, batches, where);
          }
        }
      }
    }
    assumeTrue(cancelled.isEmpty(), "declined by width: " + cancelled);
  }

  // -------------------------------------------------------------------------------------------
  // The narrow load and store at the long width (`VARKA-332.md` 2.5)
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a narrow column widened to BIGINT and a BIGINT narrowed to a narrow type answer "
      + "at every baked long width, or decline by name where the width has no load")
  public void narrowLoadAndStoreAtTheLongWidth() {
    for (int lanes : new int[] {2, 4, 8}) {
      VarkaEmitOptions options = VarkaMatrixRuns.BASE.toBuilder().lanesOverride(lanes).build();
      if (Lane.emitLanes(options, Lane.LONG) != lanes) {
        continue;
      }
      for (Type narrow : List.of(TINYINT, SMALLINT)) {
        // The width rule, stated from the species the load names: twice the long lanes.
        boolean loads = Lane.widenLanes(2 * lanes, Lane.of(VarkaTypes.lane(narrow.spark())
            .orElseThrow())) == 2 * lanes;
        VarkaVectorIR widen = new WidenLane(new ColumnRef(0, VarkaTypes.lane(narrow.spark())
            .orElseThrow()), LaneType.LONG);
        assertEquals(loads, VarkaKernelPlan.refusalOf(List.of(widen), options).isEmpty(),
            narrow.sql() + " at " + lanes + " long lanes");
        // The store needs no load: a long value narrowed at its root is served at every width.
        VarkaVectorIR store = new NarrowLane(new ColumnRef(0, LaneType.LONG),
            VarkaTypes.lane(narrow.spark()).orElseThrow());
        assertTrue(VarkaKernelPlan.refusalOf(List.of(store), options).isEmpty(),
            narrow.sql() + " store at " + lanes + " long lanes");
        String at = " at " + lanes + " long lanes";
        Object up = compile(cast(narrow.column(), BIGINT, LEGACY), List.of(narrow.column()),
            options);
        if (loads) {
          runAll((Kernel) up, batches(every(narrow)), narrow.sql() + " to BIGINT" + at);
          // The load feeding a long-lane pick beside a bigint column.
          List<Attribute> two = List.of(narrow.column(), BIGINT.column());
          Expression sum = new Greatest(CollectionConverters.asScala(List.<Expression>of(
              cast(narrow.column(), BIGINT, LEGACY), BIGINT.column())).toSeq());
          Kernel k = (Kernel) compile(sum, two, options);
          long[] values = every(narrow);
          var data = new long[][] {values, prefix(new long[] {0, 1, -1, Long.MAX_VALUE,
              Long.MIN_VALUE, 1L << 40}, values.length)};
          var isNull = new boolean[2][values.length];
          for (int i = 0; i < values.length; i++) {
            isNull[0][i] = i % 11 == 4;
            isNull[1][i] = i % 13 == 6;
          }
          runAll(k, List.of(new LongBatch(values.length, isNull, data, false)),
              sum.sql() + at);
        } else {
          assertTrue(up instanceof String reason && widthDecline(reason), at + ": " + up);
        }
        for (Mode mode : List.of(LEGACY, ANSI)) {
          Object down = compile(cast(BIGINT.column(), narrow, mode), List.of(BIGINT.column()),
              options);
          long[] values = {0, 1, -1, 127, 128, -129, 32767, 32768, -32769, Long.MAX_VALUE,
              Long.MIN_VALUE, 255, 65535, 1L << 40};
          var batches = batches(values);
          runAll((Kernel) down, batches, "BIGINT to " + narrow.sql() + " " + mode.name() + at);
          // Away from a store: L2I and the wrap at the int lane, read by a widening back to the
          // long lane, and by a comparison with a narrow column, which is loaded at the long
          // width when the width has the load.
          Expression back = cast(cast(BIGINT.column(), narrow, mode), BIGINT, LEGACY);
          runAll((Kernel) compile(back, List.of(BIGINT.column()), options), batches,
              back.sql() + " " + mode.name() + at);
          Expression equal = new EqualTo(cast(BIGINT.column(), narrow, mode), narrow.column());
          List<Attribute> two = List.of(BIGINT.column(), narrow.column());
          Object compared = compile(equal, two, options);
          if (loads) {
            var data = new long[][] {values, prefix(new long[] {0, 1, -1, 127, -128, 32767,
                -32768}, values.length)};
            var isNull = new boolean[2][values.length];
            isNull[1][3] = true;
            runAll((Kernel) compared, List.of(new LongBatch(values.length, isNull, data, false)),
                equal.sql() + " " + mode.name() + at);
          } else {
            assertTrue(compared instanceof String reason && widthDecline(reason),
                at + ": " + compared);
          }
        }
      }
    }
  }
}
