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

import java.math.BigInteger;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NullSafeEqual;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Or;
import org.apache.spark.sql.types.DecimalType;

/**
 * A 128-bit unscaled decimal value as the two long words Arrow's sixteen-byte slot holds it in
 * (VARKA-359): {@code v = hi * 2^64 + lo}, with {@code hi} read signed and {@code lo} unsigned,
 * so the words order as {@code v} does when the high words compare signed and, where they are
 * equal, the low words unsigned (`decimal128.smt2`'s ring layer).
 *
 * <p>Compile-time data: a literal's two long-table slots and the constant pairs of the load's
 * {@code 10^p} check, built once a shape. The comparisons over two values' words are here too
 * ({@link #compare}, {@link #nullSafeEqual}), as conditions built from the IR's long-lane nodes.
 */
public record DecimalWords(long lo, long hi) {

  /** The widest precision of a decimal, Spark's {@code DecimalType.MAX_PRECISION}. */
  public static final int MAX_PRECISION = DecimalType.MAX_PRECISION();

  private static final BigInteger WORD = BigInteger.ONE.shiftLeft(64);
  private static final BigInteger MIN = BigInteger.ONE.shiftLeft(127).negate();
  private static final BigInteger MAX = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);

  /** The words of {@code v}, which must lie in {@code [-2^127, 2^127)}. */
  public static DecimalWords of(BigInteger v) {
    if (v.compareTo(MIN) < 0 || v.compareTo(MAX) > 0) {
      throw VarkaErrors.illegalArgument("a 128-bit word pair holds -2^127 to 2^127 - 1, not "
          + v);
    }
    return new DecimalWords(v.longValue(), v.shiftRight(64).longValueExact());
  }

  /**
   * Refuses a precision a pair does not hold: one of {@link DecimalColumn#MAX_LONG_PRECISION}
   * digits or fewer, which is one long word, or one wider than {@link #MAX_PRECISION}.
   */
  static void requirePairPrecision(int precision) {
    if (precision <= DecimalColumn.MAX_LONG_PRECISION || precision > MAX_PRECISION) {
      throw VarkaErrors.illegalArgument("a pair's precision is from "
          + (DecimalColumn.MAX_LONG_PRECISION + 1) + " to " + MAX_PRECISION + ", not "
          + precision);
    }
  }

  /**
   * The words of {@code 10^precision - 1}, the largest unscaled value of a decimal of the
   * precision, for a precision of the pair ({@link #requirePairPrecision}).
   */
  public static DecimalWords bound(int precision) {
    requirePairPrecision(precision);
    return of(BigInteger.TEN.pow(precision).subtract(BigInteger.ONE));
  }

  /** The value the words hold. */
  public BigInteger value() {
    BigInteger low = BigInteger.valueOf(lo);
    if (lo < 0) {
      low = low.add(WORD);
    }
    return BigInteger.valueOf(hi).shiftLeft(64).add(low);
  }

  /** The words of {@code -value()}, which must not be {@code -2^127}. */
  public DecimalWords negate() {
    return of(value().negate());
  }

  // ---- The comparisons over two values' words (`m8/VARKA-359.md` 2.5) ----------------------

  /**
   * {@code x OP y} over the words of two values, {@code op} one of the five signed comparisons:
   * {@code x = y} is {@code EQ(x.hi, y.hi) & EQ(x.lo, y.lo)}, and {@code x < y} is
   * {@code LT(x.hi, y.hi) | (EQ(x.hi, y.hi) & ULT(x.lo, y.lo))}: the values order by their high
   * words, read signed, and where those are equal by their low words, read unsigned
   * ({@code decimal128.smt2}). {@code <=} is the same with {@code ULE}, and {@code >} and
   * {@code >=} are {@code <} and {@code <=} with the operands swapped. A null row leaves every
   * word's compare unknown, so the result is unknown there, as Spark's comparison is null.
   */
  public static Cond compare(CompareOp op, VarkaVectorIR xLo, VarkaVectorIR xHi,
      VarkaVectorIR yLo, VarkaVectorIR yHi) {
    return switch (op) {
      case EQ -> new And(new Compare(CompareOp.EQ, xHi, yHi), new Compare(CompareOp.EQ, xLo,
          yLo));
      case LT -> lessThan(CompareOp.ULT, xLo, xHi, yLo, yHi);
      case LE -> lessThan(CompareOp.ULE, xLo, xHi, yLo, yHi);
      case GT -> lessThan(CompareOp.ULT, yLo, yHi, xLo, xHi);
      case GE -> lessThan(CompareOp.ULE, yLo, yHi, xLo, xHi);
      case ULT, ULE, UGT, UGE -> throw VarkaErrors.illegalArgument("two decimal values compare "
          + "signed, not " + op);
    };
  }

  /** {@code LT(x.hi, y.hi) | (EQ(x.hi, y.hi) & low(x.lo, y.lo))}, {@code low} unsigned. */
  private static Cond lessThan(CompareOp low, VarkaVectorIR xLo, VarkaVectorIR xHi,
      VarkaVectorIR yLo, VarkaVectorIR yHi) {
    return new Or(new Compare(CompareOp.LT, xHi, yHi), new And(new Compare(CompareOp.EQ, xHi,
        yHi), new Compare(low, xLo, yLo)));
  }

  /**
   * {@code x <=> y} over the words of two values: the null-safe equality of each word, ANDed.
   * Both words of a value are valid on the same rows (a column's two words share its validity
   * word, a literal's are always valid, and a sign extension's high word is valid where its low
   * word is), so the AND is true where both values are null, false where one is, and the two
   * words' equality where both are valid.
   */
  public static Cond nullSafeEqual(VarkaVectorIR xLo, VarkaVectorIR xHi, VarkaVectorIR yLo,
      VarkaVectorIR yHi) {
    return new And(new NullSafeEqual(xHi, yHi), new NullSafeEqual(xLo, yLo));
  }
}
