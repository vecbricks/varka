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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.Enumeration;
import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.EvalMode;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.Batch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.LongBatch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.SparkAnswers;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.util.DateTimeConstants;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DayTimeIntervalType;
import org.apache.spark.sql.types.YearMonthIntervalType;

/**
 * The two interval casts with an integral type that move numbers (VARKA-332,
 * `VARKA-332.md` 10.3): {@code CAST(i AS INTERVAL YEAR)}, a checked multiply by twelve, and
 * {@code CAST(dt AS BIGINT)}, the truncating division by the end field's unit.
 *
 * <p>Both go through {@link VarkaExpressionCompiler} and the emitted class, every row held to
 * {@link VarkaSparkOracle}, Spark's own {@code Cast.eval}. Spark raises the year cast's overflow
 * in every mode, so a batch holding an overflowing count declines under LEGACY and ANSI and the
 * row engine raises, and under {@code TRY} the kernel answers null. The division cannot fail; a
 * batch declines only for the {@code 2^52} microsecond guard of the HOUR, MINUTE and SECOND ends
 * (the DAY end is exact over every long), whatever the mode, and the row engine then divides.
 *
 * <p>In its own JVM: the year cast runs at the int lane and the division at the long lane.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterIntervalCastSuite {

  private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

  /** Spark's bound on a year count: {@code Int.MaxValue / 12}, either sign. */
  private static final long MAX_YEARS = Integer.MAX_VALUE / 12;

  /** The guard of a sub-day division: {@code |v| < 2^52} microseconds. */
  private static final long GUARD = 1L << 52;

  private static final List<Enumeration.Value> MODES =
      List.of(EvalMode.LEGACY(), EvalMode.ANSI(), EvalMode.TRY());

  private static final YearMonthIntervalType YEAR_INTERVAL = new YearMonthIntervalType(
      YearMonthIntervalType.YEAR(), YearMonthIntervalType.YEAR());

  private static Expression cast(Attribute child, DataType to, Enumeration.Value mode) {
    return new Cast(child, to, Option.empty(), mode);
  }

  // -------------------------------------------------------------------------------------------
  // CAST(i AS INTERVAL YEAR)
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the guard of INT to INTERVAL YEAR is multiplyExact's, over every int")
  void yearGuardExhausted() {
    Attribute i = VarkaSqlResolve.column("i", DataTypes.IntegerType);
    for (Enumeration.Value mode : MODES) {
      Expression e = cast(i, YEAR_INTERVAL, mode);
      CompiledVarkaProjection plan = compile(e, List.of(i)).orElseThrow(() ->
          new AssertionError(e.sql() + " declines under " + mode));
      if (!(plan.outputs().get(0) instanceof IntArith mul) || mul.op() != IntOp.MUL
          || mul.mode() != Overflow.WRAP || !(mul.left() instanceof GuardedRange guard)
          || !(mul.right() instanceof LiteralSlot slot)) {
        fail(e.sql() + " compiles to " + plan.outputs().get(0)
            + ", not a wrapping multiply of a guarded count");
        return;
      }
      assertEquals(mode == EvalMode.TRY() ? Overflow.NULL : Overflow.FAIL, guard.mode(),
          e.sql() + ": the guard's mode");
      int factor = plan.literals().get(slot.index());
      assertEquals(12, factor);
      long lo = guard.lo();
      long hi = guard.hi();
      var bad = new AtomicLong();
      var first = new AtomicLong();
      int chunks = 256;
      long span = (1L << 32) / chunks;
      IntStream.range(0, chunks).parallel().forEach(c -> {
        long from = Integer.MIN_VALUE + c * span;
        for (long v = from; v < from + span; v++) {
          boolean admitted = lo <= v && v <= hi;
          boolean overflows = v * factor != (int) (v * factor);
          if (admitted == overflows && bad.getAndIncrement() == 0) {
            first.set(v);
          }
        }
      });
      assertEquals(0, bad.get(), e.sql() + ": the guard [" + lo + ", " + hi + "] differs from "
          + "multiplyExact at " + bad.get() + " counts, first " + first.get());
    }
  }

  @Test
  @DisplayName("INT to INTERVAL YEAR answers as Spark does around the guard's ends: LEGACY and "
      + "ANSI decline a batch Spark raises on, TRY answers null")
  void yearAtTheEdges() {
    long[] inside = {0, 1, -1, 12, MAX_YEARS - 1, MAX_YEARS, -MAX_YEARS + 1, -MAX_YEARS};
    long[] past = {MAX_YEARS + 1, -MAX_YEARS - 1, Integer.MAX_VALUE, Integer.MIN_VALUE,
        Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1};
    Attribute i = VarkaSqlResolve.column("i", DataTypes.IntegerType);
    for (Enumeration.Value mode : MODES) {
      Kernel k = kernel(cast(i, YEAR_INTERVAL, mode), List.of(i));
      assertTrue(k.answers(inside), mode + " declined the counts inside the guard");
      for (long v : past) {
        long[] values = {0, 1, v, -1};
        if (mode == EvalMode.TRY()) {
          assertTrue(k.answers(values), mode + " declined a batch holding " + v);
        } else {
          assertNotEquals(0, k.status(values), mode + " answered a batch holding " + v
              + ", which Spark raises on");
        }
      }
      // A spread of counts of every bit length, with the ends of the range.
      var rnd = new Random(332);
      var spread = new ArrayList<Long>();
      for (int n = 0; n < 4000; n++) {
        int bits = 1 + rnd.nextInt(31);
        long v = rnd.nextLong() >> (64 - bits);
        spread.add(mode == EvalMode.TRY() ? v : Math.max(-MAX_YEARS, Math.min(MAX_YEARS, v)));
      }
      assertTrue(k.answers(spread.stream().mapToLong(Long::longValue).toArray()),
          mode + " declined a spread of counts");
    }
  }

  // -------------------------------------------------------------------------------------------
  // CAST(dt AS BIGINT)
  // -------------------------------------------------------------------------------------------

  /** Microseconds of one unit of an end field: DAY, HOUR, MINUTE, SECOND. */
  private static long unit(int endField) {
    return switch (endField) {
      case 0 -> DateTimeConstants.MICROS_PER_DAY;
      case 1 -> DateTimeConstants.MICROS_PER_HOUR;
      case 2 -> DateTimeConstants.MICROS_PER_MINUTE;
      default -> DateTimeConstants.MICROS_PER_SECOND;
    };
  }

  /** Values around the unit's multiples and the guard, both signs, and the long's ends. */
  private static long[] dayTimeValues(long unit) {
    var out = new ArrayList<Long>();
    for (long v : new long[] {0, 1, 2, unit - 1, unit, unit + 1, 2 * unit - 1, 2 * unit,
        3 * unit + 17, 1_234_567_890_123L, GUARD - 1, GUARD - unit, GUARD - unit - 1,
        GUARD - unit + 1}) {
      out.add(v);
      out.add(-v);
    }
    return out.stream().mapToLong(Long::longValue).toArray();
  }

  @Test
  @DisplayName("a day-time interval of each field range casts to BIGINT as Spark does in the "
      + "three modes: exact over every long for a DAY end, to 2^52 microseconds below it")
  void dayTimeToBigint() {
    var rnd = new Random(332);
    for (int start = 0; start <= 3; start++) {
      for (int end = start; end <= 3; end++) {
        var type = new DayTimeIntervalType((byte) start, (byte) end);
        Attribute dt = VarkaSqlResolve.column("dt", type);
        long unit = unit(end);
        long[] edges = dayTimeValues(unit);
        // The guard's own ends, and values of every bit length below it.
        var spread = new ArrayList<Long>();
        for (int n = 0; n < 3000; n++) {
          spread.add(rnd.nextLong() >> (12 + rnd.nextInt(52)));
        }
        long[] inside = LongStream.concat(Arrays.stream(edges),
            spread.stream().mapToLong(Long::longValue)).toArray();
        long[] wide = {Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE, Long.MAX_VALUE - 1,
            -GUARD, GUARD, GUARD + unit, -GUARD - unit, GUARD - 1, -GUARD + 1, -unit * 3 + 1};
        for (Enumeration.Value mode : MODES) {
          Kernel k = kernel(cast(dt, DataTypes.LongType, mode), List.of(dt));
          String where = type.simpleString() + " " + mode;
          assertTrue(k.answers(inside), where + " declined values inside the guard");
          if (end == 0) {
            assertTrue(k.answers(wide), where + " declined values past 2^52: the DAY end is "
                + "exact over every long");
            for (long v : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, -1, 1, -unit + 1}) {
              assertTrue(k.answers(new long[] {0, v, 5}), where + " at " + v);
            }
          } else {
            for (long v : new long[] {GUARD, -GUARD, Long.MIN_VALUE, Long.MAX_VALUE,
                GUARD + 12345, -GUARD - 12345}) {
              assertNotEquals(0, k.status(new long[] {0, 1, v, -1}), where
                  + " answered a batch holding " + v + ", past the 2^52 guard");
            }
            assertTrue(k.answers(new long[] {GUARD - 1, -GUARD + 1}), where
                + " declined the guard's own ends");
          }
        }
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Running a kernel
  // -------------------------------------------------------------------------------------------

  private static Optional<CompiledVarkaProjection> compile(Expression e, List<Attribute> cols) {
    return VarkaExpressionCompiler.compile(
        CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq(),
        CollectionConverters.asScala(cols).toSeq(), VarkaMatrixRuns.BASE);
  }

  private static Kernel kernel(Expression e, List<Attribute> cols) {
    return new Kernel(e, cols, compile(e, cols).orElseThrow(() ->
        new AssertionError(e.sql() + " declines")));
  }

  /** One compiled cast, emitted, with Spark's evaluation of it for the answers. */
  private static final class Kernel {
    final Expression expr;
    final CompiledVarkaProjection plan;
    final String className;
    final byte[] bytes;
    final VarkaSparkOracle oracle;
    final boolean longLane;

    Kernel(Expression expr, List<Attribute> cols, CompiledVarkaProjection plan) {
      this.expr = expr;
      this.plan = plan;
      this.className = "org.apache.spark.sql.varka.execution.VarkaIntervalCast"
          + CLASS_COUNTER.incrementAndGet();
      this.bytes = VarkaLoopEmitter.emit(className, plan.outputs(), plan.inputOrdinals().size(),
          plan.numLiterals(), null, null, VarkaMatrixRuns.BASE);
      this.oracle = new VarkaSparkOracle(CollectionConverters.asScala(List.of(expr)).toSeq(),
          CollectionConverters.asScala(List.of(cols.get(plan.inputOrdinals().get(0)))).toSeq());
      this.longLane = plan.lane(VarkaMatrixRuns.BASE).bits() == Long.SIZE;
    }

    /** Each value once valid and once null, so both bodies and a null lane run. */
    private LongBatch batch(long[] values) {
      int n = values.length * 2;
      var data = new long[1][n];
      var nulls = new boolean[1][n];
      for (int i = 0; i < n; i++) {
        data[0][i] = values[i / 2];
        nulls[0][i] = i % 2 == 1;
      }
      return new LongBatch(n, nulls, data, false);
    }

    private Batch intBatch(long[] values) {
      LongBatch b = batch(values);
      var data = new int[1][b.length()];
      for (int i = 0; i < b.length(); i++) {
        data[0][i] = (int) b.data()[0][i];
      }
      return new Batch(b.length(), b.nulls(), data, false);
    }

    private long[] longLits() {
      return plan.longLiterals().stream().mapToLong(Long::longValue).toArray();
    }

    private int[] intLits() {
      return plan.literals().stream().mapToInt(Integer::intValue).toArray();
    }

    /** Whether the kernel answers {@code values}, every row compared with Spark. */
    boolean answers(long[] values) {
      String ctx = className + " " + expr.sql();
      return longLane
          ? VarkaKernelChecks.runAndCompareLong(ctx, className, bytes, plan.outputs(), 1,
              longLits(), intLits(), batch(values), false, sparkAnswers(oracle))
          : VarkaKernelChecks.runAndCompare(ctx, className, bytes, plan.outputs(), 1,
              intLits(), intBatch(values), false, sparkAnswers(oracle));
    }

    /** The kernel's status over {@code values}: non-zero when it declines the batch. */
    int status(long[] values) {
      String ctx = className + " " + expr.sql();
      return longLane
          ? VarkaKernelChecks.runAndCompareLongStatus(ctx, className, bytes, plan.outputs(), 1,
              longLits(), batch(values), true)
          : VarkaKernelChecks.runAndCompareStatus(ctx, className, bytes, plan.outputs(), 1,
              intLits(), intBatch(values), true);
    }
  }

  private static SparkAnswers sparkAnswers(VarkaSparkOracle oracle) {
    return new SparkAnswers() {
      @Override
      public VarkaKernelChecks.Answer answer(int output, long[] values, boolean[] isNull) {
        var row = new ArrayList<Option<Object>>();
        for (int c = 0; c < values.length; c++) {
          row.add(isNull[c] ? Option.empty() : Option.apply((Object) values[c]));
        }
        VarkaSparkOracle.Answer a = oracle.answer(output,
            CollectionConverters.asScala(row).toList());
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
}
