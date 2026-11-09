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

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongBinaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.SubtractTimestamps;
import org.apache.spark.sql.catalyst.expressions.TimestampAddInterval;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaDecline;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.catalyst.util.DateTimeConstants;
import org.apache.spark.sql.catalyst.util.DateTimeUtils;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DayTimeIntervalType;

/**
 * The {@code TIMESTAMP_NTZ} difference and interval sums (VARKA-339, `VARKA-339.md` 2.1 and 3.7).
 * The kernel computes {@code ntz - ntz} as {@code Math.subtractExact}, {@code ntz + dt} as
 * {@code Math.addExact} and {@code ntz - dt} as {@code subtractExact} over an interval guarded off
 * {@code Long.MIN_VALUE}. Spark computes them through {@code java.time} at UTC, so the first test
 * holds Spark's functions to those three at the long range's ends and their neighbours, raising
 * exactly where they raise; the others pin the trees the compiler builds and the forms it declines.
 * {@code timestampadd} up to a week is {@code t + q * f}, the product guarded and the sum checked
 * (2.2), held to Spark's {@code timestampAdd} at the guard's bound and one past.
 */
@VarkaSuite
public final class VarkaTimestampNtzArithmeticSuite {

  /** The long range's ends and their neighbours, zero and one each way, and a day each way. */
  private static final long[] EDGES = {Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE + 2,
      -86_400_000_000L, -1, 0, 1, 86_400_000_000L, Long.MAX_VALUE - 2, Long.MAX_VALUE - 1,
      Long.MAX_VALUE};

  /** {@code f(a, b)}, or empty where it raises an arithmetic or date-time error. */
  private static Optional<Long> attempt(LongBinaryOperator f, long a, long b) {
    try {
      return Optional.of(f.applyAsLong(a, b));
    } catch (ArithmeticException | java.time.DateTimeException e) {
      return Optional.empty();
    }
  }

  @Test
  @DisplayName("Spark's NTZ difference, sum and difference with an interval are subtractExact, "
      + "addExact and subtractExact under negateExact's domain at the long range's ends")
  void sparkIsExactLongArithmetic() {
    int compared = 0;
    for (long a : EDGES) {
      for (long b : EDGES) {
        assertEquals(attempt(Math::subtractExact, a, b),
            attempt((x, y) -> DateTimeUtils.subtractTimestamps(x, y, ZoneOffset.UTC), a, b),
            "subtractTimestamps(" + a + ", " + b + ")");
        assertEquals(attempt(Math::addExact, a, b),
            attempt((x, y) -> DateTimeUtils.timestampAddDayTime(x, y, ZoneOffset.UTC), a, b),
            "timestampAddDayTime(" + a + ", " + b + ")");
        // `ntz - dt` is Spark's sum with the negated interval; the kernel's checked subtraction
        // under the guard dt > Long.MIN_VALUE.
        Optional<Long> kernel = b == Long.MIN_VALUE ? Optional.empty()
            : attempt(Math::subtractExact, a, b);
        assertEquals(kernel, attempt((x, y) -> DateTimeUtils.timestampAddDayTime(x,
            Math.negateExact(y), ZoneOffset.UTC), a, b), "ntz - dt at (" + a + ", " + b + ")");
        compared++;
      }
    }
    assertEquals(EDGES.length * EDGES.length, compared);
  }

  private final Attribute ntz = VarkaSqlResolve.column("ntz", DataTypes.TimestampNTZType);
  private final Attribute ntz2 = VarkaSqlResolve.column("ntz2", DataTypes.TimestampNTZType);
  private final Attribute dt = VarkaSqlResolve.column("dt", DayTimeIntervalType.apply());
  private final Attribute ltz = VarkaSqlResolve.column("ltz", DataTypes.TimestampType);
  private final Attribute i = VarkaSqlResolve.column("i", DataTypes.IntegerType);
  private final Attribute l = VarkaSqlResolve.column("l", DataTypes.LongType);

  private scala.collection.immutable.Seq<Attribute> columns() {
    return CollectionConverters.asScala(List.of(ntz, ntz2, dt, ltz, i, l)).toList();
  }

  private Optional<CompiledVarkaProjection> compile(Expression e) {
    return VarkaExpressionCompiler.compile(
        CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toList(), columns(),
        VarkaMatrixRuns.BASE);
  }

  private VarkaVectorIR root(String sql) {
    Expression e = VarkaSqlResolve.parse(sql, columns());
    Optional<CompiledVarkaProjection> p = compile(e);
    assertTrue(p.isPresent(), () -> sql + " declined: " + declineOf(e));
    assertEquals(1, p.get().outputs().size(), sql);
    return p.get().outputs().get(0);
  }

  private String declineOf(Expression e) {
    var entry = CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toList();
    return VarkaExpressionCompiler.declines(entry, columns(), VarkaMatrixRuns.BASE).values()
        .stream().findFirst().map(VarkaDecline::reason).orElse("nothing declined");
  }

  /** The kernel's first and second input, in the order the expression reads its columns. */
  private static final ColumnRef FIRST = new ColumnRef(0, LaneType.LONG);
  private static final ColumnRef SECOND = new ColumnRef(1, LaneType.LONG);

  @Test
  @DisplayName("ntz - ntz2, ntz + dt and ntz - dt compile to the checked long subtract and add, "
      + "the negated interval guarded off Long.MIN_VALUE")
  void trees() {
    assertEquals(new IntArith(IntOp.SUB, Overflow.FAIL, FIRST, SECOND), root("ntz - ntz2"));
    assertEquals(new IntArith(IntOp.ADD, Overflow.FAIL, FIRST, SECOND), root("ntz + dt"));
    assertEquals(new IntArith(IntOp.SUB, Overflow.FAIL, FIRST,
        new GuardedRange(SECOND, Long.MIN_VALUE + 1, Long.MAX_VALUE)), root("ntz - dt"));
    // The interval a nested difference.
    assertEquals(new IntArith(IntOp.ADD, Overflow.FAIL, FIRST,
        new IntArith(IntOp.SUB, Overflow.FAIL, SECOND, FIRST)), root("ntz + (ntz2 - ntz)"));
    // `ntz - INTERVAL '1' HOUR` as an optimized plan holds it: ConstantFolding has folded the
    // negation into the literal, which the plain checked sum takes in a slot.
    Expression minusHour = new TimestampAddInterval(ntz,
        Literal.create(-3_600_000_000L, DayTimeIntervalType.apply()), Option.apply("UTC"));
    Optional<CompiledVarkaProjection> p = compile(minusHour);
    assertTrue(p.isPresent(), () -> "ntz - INTERVAL '1' HOUR declined: " + declineOf(minusHour));
    assertEquals(new IntArith(IntOp.ADD, Overflow.FAIL, FIRST, new LiteralSlot(0, LaneType.LONG)),
        p.get().outputs().get(0));
  }

  /** The microseconds of each {@code timestampadd} unit up to a week, as Spark adds them. */
  private static final Map<String, Long> UNITS = Map.of("MICROSECOND", 1L,
      "MILLISECOND", DateTimeConstants.MICROS_PER_MILLIS,
      "SECOND", DateTimeConstants.MICROS_PER_SECOND,
      "MINUTE", DateTimeConstants.MICROS_PER_MINUTE,
      "HOUR", DateTimeConstants.MICROS_PER_HOUR,
      "DAY", DateTimeConstants.MICROS_PER_DAY,
      "DAYOFYEAR", DateTimeConstants.MICROS_PER_DAY,
      "WEEK", 7 * DateTimeConstants.MICROS_PER_DAY);

  /** Spark's {@code timestampadd} at UTC, or empty where it raises. */
  private static Optional<Long> sparkAdd(String unit, long q, long t) {
    try {
      return Optional.of(DateTimeUtils.timestampAdd(unit, q, t, ZoneOffset.UTC));
    } catch (ArithmeticException e) {
      return Optional.empty();
    }
  }

  @Test
  @DisplayName("timestampadd up to a week is Spark's: the sum checked under the guard on the "
      + "quantity, every quantity past the guard of a unit up to an hour raising")
  void timestampAddUpToAWeek() {
    long[] quantities = {0, 1, -1, 7, -7, Integer.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE,
        Long.MIN_VALUE, 106_751_991, -106_751_991, 106_751_992, -106_751_992, 15_250_284,
        -15_250_284, 15_250_285, -15_250_285, 306_783_378, 306_783_379};
    int compared = 0;
    for (Map.Entry<String, Long> unit : UNITS.entrySet()) {
      long f = unit.getValue();
      long bound = Long.MAX_VALUE / f;
      for (long q0 : quantities) {
        for (long q : new long[] {q0, bound, -bound, f == 1 ? 0 : bound + 1, -bound - 1}) {
          for (long t : EDGES) {
            Optional<Long> spark = sparkAdd(unit.getKey(), q, t);
            String at = "timestampadd(" + unit.getKey() + ", " + q + ", " + t + ")";
            if (f == 1 || (q >= -bound && q <= bound)) {
              assertEquals(attempt(Math::addExact, t, q * f), spark, at);
              compared++;
            } else if (f < DateTimeConstants.MICROS_PER_DAY) {
              // MILLISECOND to HOUR: the guard is multiplyExact's domain, so Spark raises here.
              assertEquals(Optional.empty(), spark, at);
            }
          }
        }
      }
    }
    assertTrue(compared > 1000, "compared " + compared);
  }

  @Test
  @DisplayName("timestampadd up to a week compiles to a checked add of the guarded product, an "
      + "int quantity unguarded up to an hour, and declines NANOSECOND and a literal past the "
      + "guard")
  void timestampAddTrees() {
    long hour = DateTimeConstants.MICROS_PER_HOUR;
    long day = DateTimeConstants.MICROS_PER_DAY;
    assertEquals(new IntArith(IntOp.ADD, Overflow.FAIL, SECOND, FIRST),
        root("timestampadd(MICROSECOND, l, ntz)"));
    assertEquals(new IntArith(IntOp.ADD, Overflow.FAIL, SECOND, new IntArith(IntOp.MUL,
        Overflow.WRAP, new GuardedRange(FIRST, -Long.MAX_VALUE / hour, Long.MAX_VALUE / hour),
        new LiteralSlot(0, LaneType.LONG))), root("timestampadd(HOUR, l, ntz)"));
    assertEquals(new IntArith(IntOp.ADD, Overflow.FAIL, SECOND, new IntArith(IntOp.MUL,
        Overflow.WRAP, new WidenLane(new ColumnRef(0, LaneType.INT)),
        new LiteralSlot(0, LaneType.LONG))), root("timestampadd(hour, i, ntz)"));
    assertEquals(new IntArith(IntOp.ADD, Overflow.FAIL, SECOND, new IntArith(IntOp.MUL,
        Overflow.WRAP, new GuardedRange(new WidenLane(new ColumnRef(0, LaneType.INT)),
        -Long.MAX_VALUE / day, Long.MAX_VALUE / day), new LiteralSlot(0, LaneType.LONG))),
        root("timestampadd(DAY, i, ntz)"));
    assertEquals(new IntArith(IntOp.ADD, Overflow.FAIL, FIRST, new LiteralSlot(0, LaneType.LONG)),
        root("timestampadd(WEEK, 3, ntz)"));
    for (String month : List.of("timestampadd(MONTH, i, ntz)", "timestampadd(YEAR, i, ntz)",
        "timestampadd(QUARTER, l, ntz)")) {
      assertTrue(root(month).laneType() == LaneType.LONG, month);
    }
    for (String declined : List.of("timestampadd(DAY, 106751992, ntz)",
        "timestampadd(NANOSECOND, l, ntz)", "timestampadd(HOUR, l, ltz)")) {
      Expression e = VarkaSqlResolve.parse(declined, columns());
      assertTrue(compile(e).isEmpty(), declined + " fused");
    }
  }

  @Test
  @DisplayName("the legacy CalendarInterval difference and a zoned TIMESTAMP decline")
  void declines() {
    Expression legacy = new SubtractTimestamps(ntz, ntz2, true, Option.apply("UTC"));
    assertTrue(compile(legacy).isEmpty(), "the legacy difference fused");
    String reason = declineOf(legacy);
    assertTrue(reason.contains("legacy CalendarInterval"), reason);
    for (String zoned : List.of("ltz - ltz", "ltz + dt", "ltz - dt")) {
      if (compile(VarkaSqlResolve.parse(zoned, columns())).isPresent()) {
        fail(zoned + " fused over a zoned TIMESTAMP, which no lane carries");
      }
    }
  }
}
