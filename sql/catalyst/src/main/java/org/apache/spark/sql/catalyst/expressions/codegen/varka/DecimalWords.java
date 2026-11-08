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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
import org.apache.spark.sql.types.DecimalType;

/**
 * A 128-bit unscaled decimal value as the two long words Arrow's sixteen-byte slot holds it in
 * (VARKA-359): {@code v = hi * 2^64 + lo}, with {@code hi} read signed and {@code lo} unsigned,
 * so the words order as {@code v} does when the high words compare signed and, where they are
 * equal, the low words unsigned (`decimal128.smt2`'s ring layer).
 *
 * <p>Compile-time data: a literal's two long-table slots and the constant pairs of the load's
 * {@code 10^p} check, built once a shape.
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
}
