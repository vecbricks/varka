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

package org.apache.spark.sql.catalyst.expressions.codegen;

import java.util.Optional;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolCompare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolNullSafeEq;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondConst;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondIf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondValue;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Not;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * The casts among {@code TINYINT}, {@code SMALLINT}, {@code INT}, {@code BIGINT} and
 * {@code BOOLEAN} (VARKA-332), the {@link
 * org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily#INTEGRAL_CAST} family.
 * Every pair is one of four compositions over existing nodes, chosen from the pair's two types
 * by {@link #claims}, so no pair has an arm of its own (`VARKA-332.md` 2.1):
 *
 * <ul>
 *   <li><b>widen</b>, a source on a narrower lane than the target: {@code WidenLane(x, target)},
 *       a sign extension, exact in every mode;
 *   <li><b>narrow</b>, a source on a wider lane: {@code NarrowLane(g(x), target)}, where
 *       {@code g} is the identity under {@code LEGACY} - Java's narrowing, the low bits kept, as
 *       Spark's {@code (byte) c} - and under {@code ANSI} a {@link GuardedRange} of the target's
 *       range ({@link VarkaTypes#integralRange}), which declines the batch holding a value Spark
 *       raises {@code CAST_OVERFLOW} on, so the row engine raises it on the row;
 *   <li><b>test</b>, a number to {@code BOOLEAN}: {@code NOT(x = 0)} as a condition, Spark's
 *       {@code x != 0}, which cannot fail;
 *   <li><b>select</b>, {@code BOOLEAN} to a number: {@link CondValue}, 1 where the condition is
 *       known true, 0 where known false, null where unknown, Spark's {@code if (b) 1 else 0}.
 * </ul>
 *
 * <p>The three casts row 28 lowered - {@code BIGINT} to {@code INT}, {@code INT} to
 * {@code BIGINT} and a narrow value to {@code INT} - stay in the int arithmetic family's arm,
 * under its switch, so this family claims none of them ({@link #claims}). A {@code TRY}
 * narrowing declines: its null where the value does not fit is the second step of the row
 * (332b), over the range guard's {@code NULL} mode.
 */
final class VarkaIntegralCastCompiler {

  /** The composition a cast between two of the row's types is. */
  enum Kind { WIDEN, NARROW, TEST, SELECT }

  private VarkaIntegralCastCompiler() {
  }

  /**
   * The value arm: a cast this family claims whose target is a number, or {@code null}. A cast to
   * {@code BOOLEAN} is a condition and is claimed by {@link #condArm}.
   */
  static @Nullable VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (!(e instanceof Cast c)) {
      return null;
    }
    Kind kind = claims(c);
    if (kind == null || kind == Kind.TEST) {
      return null;
    }
    return switch (kind) {
      case WIDEN -> () -> widen(c, inputs, literals, sink);
      case NARROW -> () -> narrow(c, inputs, literals, sink);
      case SELECT -> () -> select(c, inputs, literals, sink);
      case TEST -> throw VarkaErrors.illegalState("a test is a condition");
    };
  }

  /** The condition arm: a cast of a number to {@code BOOLEAN}, or {@code null}. */
  static @Nullable Supplier<Optional<Cond>> condArm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (e instanceof Cast c && claims(c) == Kind.TEST) {
      return () -> test(c, inputs, literals, sink);
    }
    return null;
  }

  /**
   * The composition {@code c} is if this family claims it, or {@code null}: an identity, a pair
   * outside the row's five types, and the three casts the int arithmetic family lowers are not
   * this family's.
   */
  static @Nullable Kind claims(Cast c) {
    DataType from = c.child().dataType();
    DataType to = c.dataType();
    boolean fromBool = DataTypes.BooleanType.equals(from);
    boolean toBool = DataTypes.BooleanType.equals(to);
    Optional<LaneType> source = integralLane(from);
    Optional<LaneType> target = integralLane(to);
    if (fromBool && target.isPresent()) {
      return Kind.SELECT;
    }
    if (toBool && source.isPresent()) {
      return Kind.TEST;
    }
    if (source.isEmpty() || target.isEmpty() || source.get() == target.get()) {
      return null;
    }
    LaneType s = source.get();
    LaneType t = target.get();
    // Row 28's three, which stay under the int arithmetic switch (`VARKA-332.md` 2.1).
    boolean intArithmetic = (s == LaneType.LONG && t == LaneType.INT)
        || (s == LaneType.INT && t == LaneType.LONG) || (s.isNarrow() && t == LaneType.INT);
    if (intArithmetic) {
      return null;
    }
    return t.isWiderThan(s) ? Kind.WIDEN : Kind.NARROW;
  }

  /** The lane of {@code TINYINT}, {@code SMALLINT}, {@code INT} or {@code BIGINT}. */
  private static Optional<LaneType> integralLane(DataType type) {
    if (DataTypes.ByteType.equals(type) || DataTypes.ShortType.equals(type)
        || DataTypes.IntegerType.equals(type) || DataTypes.LongType.equals(type)) {
      return VarkaTypes.lane(type);
    }
    return Optional.empty();
  }

  private static LaneType laneOf(DataType type) {
    return integralLane(type).orElseThrow();
  }

  /** {@code c}'s operand, which must be on its type's lane. */
  private static Optional<VarkaVectorIR> operand(
      Cast c, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    LaneType lane = laneOf(c.child().dataType());
    // An int operand through the int leaf, which the chain's leaves do not hold (a bare int has
    // no meaning as a date); the other lanes' leaves are the chain's.
    Optional<VarkaVectorIR> compiled = lane == LaneType.INT
        ? VarkaNodeCompiler.intOperand(c.child(), inputs, literals, sink)
        : VarkaNodeCompiler.compileNode(c.child(), inputs, literals, sink);
    return compiled.flatMap(x ->
        x.laneType() == lane ? Optional.of(x)
            : sink.decline("a " + c.child().dataType().simpleString() + " operand on the "
                + x.laneType() + " lane, not the " + lane + " one", c));
  }

  /**
   * A sign extension. A narrow literal widened to {@code BIGINT} is the long literal of the same
   * value, so it needs no load at the long lane; a column is the narrow load at the long width
   * (`VARKA-332.md` 2.5).
   */
  private static Optional<VarkaVectorIR> widen(
      Cast c, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    LaneType target = laneOf(c.dataType());
    if (target == LaneType.LONG && c.child() instanceof Literal l
        && l.value() instanceof Number n) {
      return Optional.of(sink.longSlot(n.longValue()));
    }
    return operand(c, inputs, literals, sink).map(x -> new WidenLane(x, target));
  }

  /**
   * The low bits under {@code LEGACY}; under {@code ANSI} the same narrowing over a range guard of
   * the target's range, which declines the batch where Spark raises. {@code TRY} declines.
   */
  private static Optional<VarkaVectorIR> narrow(
      Cast c, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    Overflow mode = VarkaNodeCompiler.overflowOf(c.evalMode());
    if (mode == Overflow.NULL) {
      return sink.decline("try_cast of a " + c.child().dataType().simpleString() + " to "
          + c.dataType().simpleString() + ", whose null where the value does not fit is not "
          + "fused yet (VARKA-332)", c);
    }
    LaneType target = laneOf(c.dataType());
    return operand(c, inputs, literals, sink).map(x -> {
      VarkaTypes.IntegralRange range = VarkaTypes.integralRange(target);
      VarkaVectorIR guarded = mode == Overflow.FAIL
          ? new GuardedRange(x, range.min(), range.max()) : x;
      return new NarrowLane(guarded, target);
    });
  }

  /** {@code NOT(x = 0)}, the zero on the operand's lane. */
  private static Optional<Cond> test(
      Cast c, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    return operand(c, inputs, literals, sink).map(x -> {
      VarkaVectorIR zero = switch (x.laneType()) {
        case LONG -> sink.longSlot(0L);
        case BYTE, SHORT, INT -> VarkaNodeCompiler.slot(0, x.laneType(), literals);
        case FLOAT, DOUBLE -> throw VarkaErrors.illegalState("a floating operand: " + x);
      };
      return new Not(new Compare(CompareOp.EQ, x, zero));
    });
  }

  /**
   * The condition as a number. A condition built of boolean leaves alone - a {@code BOOLEAN}
   * column and the connectives over it - names the target's lane, so {@code CAST(b AS TINYINT)}
   * computes in the byte lane under the native arm. Any other condition is on its operands'
   * lane: its value is taken there and converted, a widening or the narrowing of a 0 or a 1.
   */
  private static Optional<VarkaVectorIR> select(
      Cast c, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    LaneType target = laneOf(c.dataType());
    Optional<Cond> compiled = VarkaConditionCompiler.compileCond(c.child(), inputs, literals,
        sink);
    if (compiled.isEmpty()) {
      return Optional.empty();
    }
    Cond cond = compiled.get();
    if (cond.laneType().isFloating()) {
      return sink.decline("a condition on the " + cond.laneType() + " lane cast to a number", c);
    }
    Optional<Cond> relaned = onLane(cond, target);
    if (relaned.isPresent()) {
      return Optional.of(new CondValue(relaned.get(), target));
    }
    Cond lifted = VarkaConditionCompiler.onIntLane(cond);
    VarkaVectorIR value = new CondValue(lifted, lifted.laneType());
    if (lifted.laneType() == target) {
      return Optional.of(value);
    }
    return Optional.of(target.isWiderThan(lifted.laneType())
        ? new WidenLane(value, target) : new NarrowLane(value, target));
  }

  /**
   * {@code cond} on {@code lane} where it reads no value - its leaves are {@code BOOLEAN} columns
   * and constants, which name a lane but read only bits - or empty.
   */
  static Optional<Cond> onLane(Cond cond, LaneType lane) {
    return switch (cond) {
      case BoolColumn b -> Optional.of(new BoolColumn(b.ordinal(), lane));
      case CondConst k -> Optional.of(new CondConst(k.value(), lane));
      case Not n -> onLane(n.child(), lane).map(Not::new);
      case VarkaVectorIR.And a -> onLane(a.left(), lane).flatMap(l -> onLane(a.right(), lane)
          .map(r -> new VarkaVectorIR.And(l, r)));
      case VarkaVectorIR.Or o -> onLane(o.left(), lane).flatMap(l -> onLane(o.right(), lane)
          .map(r -> new VarkaVectorIR.Or(l, r)));
      case BoolCompare b -> onLane(b.left(), lane).flatMap(l -> onLane(b.right(), lane)
          .map(r -> new BoolCompare(b.op(), l, r)));
      case BoolNullSafeEq b -> onLane(b.left(), lane).flatMap(l -> onLane(b.right(), lane)
          .map(r -> new BoolNullSafeEq(l, r)));
      case CondIf i -> onLane(i.cond(), lane).flatMap(k -> onLane(i.thenCond(), lane)
          .flatMap(t -> onLane(i.elseCond(), lane).map(f -> new CondIf(k, t, f))));
      case IsNotNull n when n.child() instanceof Cond inner ->
          onLane(inner, lane).map(IsNotNull::new);
      case IsNotNull n -> Optional.empty();
      case Compare x -> Optional.empty();
      case VarkaVectorIR.NullSafeEqual x -> Optional.empty();
      case VarkaVectorIR.InRanges x -> Optional.empty();
    };
  }
}
