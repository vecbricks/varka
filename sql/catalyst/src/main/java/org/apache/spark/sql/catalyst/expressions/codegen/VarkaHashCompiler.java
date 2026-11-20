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

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.EvalMode$;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.Murmur3Hash;
import org.apache.spark.sql.catalyst.expressions.XxHash64;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOpKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CanonicalBits;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CanonicalFloat;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.HashMix;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.HashStep;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.BooleanType;
import org.apache.spark.sql.types.ByteType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DateType;
import org.apache.spark.sql.types.DayTimeIntervalType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.DoubleType;
import org.apache.spark.sql.types.FloatType;
import org.apache.spark.sql.types.IntegerType;
import org.apache.spark.sql.types.LongType;
import org.apache.spark.sql.types.NullType;
import org.apache.spark.sql.types.ShortType;
import org.apache.spark.sql.types.TimeType;
import org.apache.spark.sql.types.TimestampNTZType;
import org.apache.spark.sql.types.YearMonthIntervalType;

/**
 * The hash family (VARKA-337 2.3): Spark's {@code hash} ({@code Murmur3Hash}) and
 * {@code xxhash64} ({@code XxHash64}), claimed by exact class, so the collation-aware variants
 * and {@code HiveHash} stay unclaimed. Spark's value is a fold: the expression's seed, then for
 * each child in order the child's word hashed into it, a null child leaving it as it is
 * ({@code HashExpression.eval}). The fold here is the same chain of {@link HashStep}s from a
 * literal seed; each step skips a null word as Spark skips a null child, so the root is never
 * null, as Spark's is not.
 *
 * <p>Each child's word is Spark's per-type rule ({@code computeHashWithTailRec}): a
 * {@code BOOLEAN} as the int 1 or 0, the narrow integers sign-extended, an {@code INT},
 * {@code DATE} or year-month interval as itself through {@code hashInt}; a {@code BIGINT},
 * {@code TIME}, {@code TIMESTAMP_NTZ} or day-time interval through {@code hashLong}; a
 * {@code FLOAT} or {@code DOUBLE} as the bits of its canonicalisation ({@code -0.0} to 0, every
 * NaN to the canonical one, which is {@code genHashFloat}'s and {@code genHashDouble}'s rule); a
 * decimal of precision 18 or less as its unscaled long. A decimal above 18 hashes its byte array
 * and is row 337b's; a null-typed child is skipped, as Spark's empty step is.
 */
final class VarkaHashCompiler {

  private VarkaHashCompiler() {
  }

  /** The family's claim: {@code hash} or {@code xxhash64}, or null. */
  static @Nullable VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (e.getClass() == Murmur3Hash.class) {
      Murmur3Hash m = (Murmur3Hash) e;
      return () -> fold(m, false, CollectionConverters.asJava(m.children()),
          VarkaNodeCompiler.intSlot(m.seed(), literals), inputs, literals, sink);
    }
    if (e.getClass() == XxHash64.class) {
      XxHash64 x = (XxHash64) e;
      return () -> fold(x, true, CollectionConverters.asJava(x.children()),
          sink.longSlot(x.seed()), inputs, literals, sink);
    }
    return null;
  }

  /** The chain of steps over {@code children} from {@code seed}, in Spark's order. */
  private static Optional<VarkaVectorIR> fold(Expression whole, boolean xxh64,
      List<Expression> children, VarkaVectorIR seed, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    VarkaVectorIR h = seed;
    boolean any = false;
    for (Expression child : children) {
      if (child.dataType() instanceof NullType
          || (child instanceof Literal l && l.value() == null)) {
        continue;
      }
      Optional<VarkaVectorIR> word = word(whole, child, inputs, literals, sink);
      if (word.isEmpty()) {
        return word;
      }
      VarkaVectorIR w = word.get();
      if (xxh64) {
        h = w.laneType() == LaneType.INT
            ? new HashStep(HashMix.XXH64_INT, h, new WidenLane(w, LaneType.LONG))
            : new HashStep(HashMix.XXH64_LONG, h, w);
      } else if (w.laneType() == LaneType.INT) {
        h = new HashStep(HashMix.MURMUR3_INT, h, w);
      } else {
        // hashLong: the low int, then the high one, (int) v and (int) (v >>> 32), both halves
        // with the value's validity.
        VarkaVectorIR low = new NarrowLane(w, LaneType.INT);
        VarkaVectorIR high = new NarrowLane(
            new BitOp(BitOpKind.USHR, w, sink.longSlot(Integer.SIZE)), LaneType.INT);
        h = new HashStep(HashMix.MURMUR3_HIGH, new HashStep(HashMix.MURMUR3_LOW, h, low), high);
      }
      any = true;
    }
    if (!any) {
      return sink.decline("a hash of no column, a constant Spark's optimizer folds", whole);
    }
    return Optional.of(h);
  }

  /**
   * The word Spark's rule hashes for {@code child}: an int-lane value for {@code hashInt}, a
   * long-lane one for {@code hashLong}, or empty with the reason noted.
   */
  private static Optional<VarkaVectorIR> word(Expression whole, Expression child,
      VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    DataType t = child.dataType();
    return switch (t) {
      // A boolean's word is the int 1 or 0, which is its cast to INT.
      case BooleanType b -> lane(new Cast(child, DataTypes.IntegerType, Option.empty(),
          EvalMode$.MODULE$.LEGACY()), LaneType.INT, inputs, literals, sink);
      case ByteType b -> narrow(child, inputs, literals, sink);
      case ShortType s -> narrow(child, inputs, literals, sink);
      // A bare int column is a leaf only where an int operand is read, as arithmetic's are.
      case IntegerType i -> VarkaNodeCompiler.intOperand(child, inputs, literals, sink);
      case DateType d -> lane(child, LaneType.INT, inputs, literals, sink);
      case YearMonthIntervalType y -> lane(child, LaneType.INT, inputs, literals, sink);
      case LongType l -> lane(child, LaneType.LONG, inputs, literals, sink);
      case TimeType tt -> lane(child, LaneType.LONG, inputs, literals, sink);
      case TimestampNTZType n -> lane(child, LaneType.LONG, inputs, literals, sink);
      case DayTimeIntervalType d -> lane(child, LaneType.LONG, inputs, literals, sink);
      case FloatType f -> floating(whole, child, LaneType.FLOAT, inputs, literals, sink);
      case DoubleType d -> floating(whole, child, LaneType.DOUBLE, inputs, literals, sink);
      case DecimalType d when d.precision() <= Decimal.MAX_LONG_DIGITS() ->
          VarkaDecimalCompiler.value(child, inputs, literals, sink)
              .map(VarkaDecimalCompiler.Scaled::unscaled);
      case DecimalType d -> sink.decline("a hash of a decimal above precision 18, whose word is "
          + "its unscaled value's byte array (row 337b)", child);
      default -> sink.decline("a hash of a " + t.simpleString() + " child, outside the types "
          + "Varka hashes (VARKA-337)", child);
    };
  }

  /** A {@code TINYINT} or {@code SMALLINT} child sign-extended to the int lane. */
  private static Optional<VarkaVectorIR> narrow(Expression e, VarkaSlotTable inputs,
      VarkaSlotTable literals, DeclineSink sink) {
    Optional<VarkaVectorIR> x = VarkaNodeCompiler.compileNode(e, inputs, literals, sink);
    if (x.isPresent() && x.get().laneType().isNarrow()) {
      return Optional.of(new WidenLane(x.get(), LaneType.INT));
    }
    if (x.isPresent() && x.get().laneType() != LaneType.INT) {
      return sink.decline("a hash child computed on the " + x.get().laneType() + " lane, not "
          + "INT", e);
    }
    return x;
  }

  /** {@code e} compiled to a value on {@code lane}, or empty with the reason noted. */
  private static Optional<VarkaVectorIR> lane(Expression e, LaneType lane,
      VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    Optional<VarkaVectorIR> x = VarkaNodeCompiler.compileNode(e, inputs, literals, sink);
    if (x.isPresent() && x.get().laneType() != lane) {
      return sink.decline("a hash child computed on the " + x.get().laneType() + " lane, not "
          + lane, e);
    }
    return x;
  }

  /**
   * A floating child's word: the bits of its canonicalisation. The canonicalisation is the
   * floating arithmetic family's node (VARKA-337 2.1), so it is built under that family's switch,
   * and a hash of a floating child declines while it is off.
   */
  private static Optional<VarkaVectorIR> floating(Expression whole, Expression child,
      LaneType lane, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    return lane(child, lane, inputs, literals, sink).flatMap(x -> VarkaNodeCompiler.inFamily(
        VarkaFamily.FLOATING_ARITHMETIC, () -> Optional.of(CanonicalFloat.of(x)), whole, sink))
        .map(CanonicalBits::new);
  }
}
