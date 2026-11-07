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
import java.util.ArrayList;
import java.util.List;

/**
 * The constants of the {@code HALF_UP} division of a decimal's unscaled value by {@code 10^k}
 * (VARKA-363 2.3), and the arithmetic they stand for, one value at a time.
 *
 * <p>Rounding a quotient to the nearest integer, halves away from zero, needs the remainder when
 * done the obvious way. For a magnitude {@code n >= 0} it does not need one:
 *
 * <pre>
 *   N = floor(n / 2^(k-1))                the pre-shift
 *   floor(N / 5^k)  = floor(2n / 10^k)    because 10^k = 2^k * 5^k
 *   HALF_UP(n / 10^k) = (floor(N / 5^k) + 1) / 2     rounded down
 * </pre>
 *
 * <p>and {@code floor(N / 5^k)} is a multiply and a shift: {@code floor(N * c / 2^L)} with
 * {@code c = ceil(2^L / 5^k)}. The multiply is exact for every {@code N <= Nmax} once
 * {@code e * Nmax < 2^L}, where {@code e = c * 5^k - 2^L} is how far {@code c} rounds up. Write
 * {@code N = q * 5^k + r}: then {@code N * c / 2^L = q + (r + N * e / 2^L) / 5^k}, and the sum
 * stays below {@code q + 1} while {@code r + N * e / 2^L < 5^k}, which {@code r <= 5^k - 1} and
 * {@code N * e / 2^L < 1} guarantee. {@link #of} picks the least such {@code L}, so the constant is
 * as narrow as the bound allows, and a narrower constant is fewer limb products in the kernel.
 *
 * <p>The condition is sufficient, not necessary, so {@link Entry#firstFailure} says where the
 * constant really stops being exact, and {@code decimal_scale.smt2} proves both ends. Nothing here
 * depends on a lane layout: the table is read by the emitter, by the proof renderer
 * ({@code VarkaProofFiles}) and by the tests, and {@link #halfUpDivide} is the answer a kernel
 * built from it must give.
 */
final class VarkaDecimalScale {

  private VarkaDecimalScale() {
  }

  /** The least {@code k} the division is built for; {@code k = 0} is a relabel, not a division. */
  static final int MIN_K = 1;

  /**
   * The greatest {@code k}: Spark's cut never drops more than 39 digits of scale, first at
   * {@code DECIMAL(38, 7) * DECIMAL(38, 38)} (VARKA-363 0 item 5).
   */
  static final int MAX_K = 39;

  private static final BigInteger MASK_32 = BigInteger.valueOf(0xFFFFFFFFL);
  private static final long LOW_32 = 0xFFFFFFFFL;
  private static final BigInteger TEN_38_LESS_1 = BigInteger.TEN.pow(38).subtract(BigInteger.ONE);

  /**
   * The dividend bounds the table has constants for, ascending. A shape's own bound (a column's
   * {@code 10^p - 1}, a product's {@code (10^p1 - 1)(10^p2 - 1)}) takes the entry of the least
   * rung at or above it, so the open set of shapes the compiler meets is covered by this short
   * list, each rung proven once. The three named ones are the 128-bit value, an equal-scale sum
   * and a 256-bit product (VARKA-363 2.3); the others are the whole of a 32-bit limb count.
   */
  static final List<BigInteger> BOUNDS = List.of(
      twoToThe(64), twoToThe(96),
      TEN_38_LESS_1,
      TEN_38_LESS_1.shiftLeft(1),
      twoToThe(128), twoToThe(160), twoToThe(192),
      TEN_38_LESS_1.multiply(TEN_38_LESS_1),
      twoToThe(256));

  /** {@code 2^bits - 1}. */
  private static BigInteger twoToThe(int bits) {
    return BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
  }

  /**
   * One constant of the division: for dividends {@code n <= bound}, {@code N = n >> (k - 1)} and
   * {@code floor(N * multiplier / 2^shift) = floor(N / 5^k)}.
   */
  record Entry(int k, BigInteger bound, int shift, BigInteger multiplier) {

    Entry {
      if (k < MIN_K || k > MAX_K) {
        throw new IllegalArgumentException("k = " + k + " is outside " + MIN_K + ".." + MAX_K);
      }
      if (bound.signum() <= 0 || shift <= 0 || multiplier.signum() <= 0) {
        throw new IllegalArgumentException("a bound, shift and multiplier must be positive");
      }
    }

    /** {@code 5^k}, the divisor left once the pre-shift has taken the factors of 2. */
    BigInteger divisor() {
      return BigInteger.valueOf(5).pow(k);
    }

    /** The pre-shifted value's bound, {@code Nmax}. */
    BigInteger maxPreShifted() {
      return bound.shiftRight(k - 1);
    }

    /** How far the multiplier rounds {@code 2^shift / 5^k} up: {@code m * 5^k - 2^shift}. */
    BigInteger error() {
      return multiplier.multiply(divisor()).subtract(BigInteger.ONE.shiftLeft(shift));
    }

    /**
     * The least {@code N} for which {@code floor(N * multiplier / 2^shift)} is not
     * {@code floor(N / 5^k)}. The product never falls below the quotient, since the multiplier
     * rounds up, so a failure is the product reaching {@code q + 1} inside block {@code q} of
     * {@code N = q * 5^k + r}. That needs {@code N * multiplier >= (q + 1) * 2^shift}, whose least
     * {@code N} is {@code (q + 1) * 5^k - floor((q + 1) * e / multiplier)} with {@code e} the
     * {@link #error}, since {@code 2^shift / multiplier = 5^k - e / multiplier}. That least
     * {@code N} lies inside the block exactly when the floor is at least 1, which first happens
     * at {@code q + 1 = ceil(multiplier / e)}.
     */
    BigInteger firstFailure() {
      BigInteger e = error();
      BigInteger blocks = multiplier.add(e).subtract(BigInteger.ONE).divide(e);
      return blocks.multiply(divisor()).subtract(blocks.multiply(e).divide(multiplier));
    }

    /** The multiplier on 32-bit limbs, least significant first, each in {@code [0, 2^32)}. */
    long[] limbs() {
      return limbsOf(multiplier);
    }

    /** The limbs of the pre-shifted dividend's bound: how many limbs {@code N} may occupy. */
    int dividendLimbs() {
      return limbsOf(maxPreShifted()).length;
    }

    /** The 32-bit limb products of {@code N * multiplier}, the kernel's multiply count. */
    int products() {
      return dividendLimbs() * limbs().length;
    }
  }

  /** Every entry, for each rung of {@link #BOUNDS} and each {@code k} in {@code MIN_K..MAX_K}. */
  static final List<Entry> TABLE = buildTable();

  private static List<Entry> buildTable() {
    var table = new ArrayList<Entry>();
    for (BigInteger bound : BOUNDS) {
      for (int k = MIN_K; k <= MAX_K; k++) {
        table.add(derive(k, bound));
      }
    }
    return List.copyOf(table);
  }

  /**
   * The constant for dividends up to {@code bound}: the entry of the least rung of
   * {@link #BOUNDS} at or above it. A bound past the last rung, 256 bits, has no entry.
   */
  static Entry of(int k, BigInteger bound) {
    for (BigInteger rung : BOUNDS) {
      if (bound.compareTo(rung) <= 0) {
        return TABLE.get(BOUNDS.indexOf(rung) * (MAX_K - MIN_K + 1) + (k - MIN_K));
      }
    }
    throw new IllegalArgumentException("no constant for a bound of " + bound.bitLength() + " bits");
  }

  /**
   * The least shift that meets {@code e * Nmax < 2^shift} for this {@code k} and bound, with the
   * round-up multiplier it gives. The search starts at 0 and counts up, so the answer is the
   * least by construction.
   */
  static Entry derive(int k, BigInteger bound) {
    BigInteger divisor = BigInteger.valueOf(5).pow(k);
    BigInteger preShifted = bound.shiftRight(k - 1);
    for (int shift = 1; ; shift++) {
      BigInteger power = BigInteger.ONE.shiftLeft(shift);
      BigInteger multiplier = power.add(divisor).subtract(BigInteger.ONE).divide(divisor);
      BigInteger error = multiplier.multiply(divisor).subtract(power);
      if (error.multiply(preShifted).compareTo(power) < 0) {
        return new Entry(k, bound, shift, multiplier);
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // The arithmetic, one value at a time: what a kernel built from the table must compute.
  // -----------------------------------------------------------------------------------------

  /** {@code floor(N / 5^k)} for {@code N = n >> (k - 1)}, as a {@code BigInteger} multiply. */
  static BigInteger floorDivideByFives(Entry entry, BigInteger n) {
    requireMagnitude(entry, n);
    return n.shiftRight(entry.k() - 1).multiply(entry.multiplier()).shiftRight(entry.shift());
  }

  /**
   * The same value from 32-bit limbs, as the kernel forms it: every limb product is split into its
   * low and high halves, each added into the column it lands in (a column sums at most 16 halves of
   * 32 bits, so a {@code long} cannot wrap), and one pass of carries then gives the product's
   * limbs. Dropping the halves' carries, or adding two whole products into one column, is the
   * defect the planted fault of VARKA-363 2.7 stands for.
   */
  static BigInteger floorDivideByFivesLimbs(Entry entry, BigInteger n) {
    requireMagnitude(entry, n);
    long[] dividend = limbsOf(n.shiftRight(entry.k() - 1));
    long[] multiplier = entry.limbs();
    long[] columns = new long[dividend.length + multiplier.length + 1];
    for (int i = 0; i < dividend.length; i++) {
      for (int j = 0; j < multiplier.length; j++) {
        long product = dividend[i] * multiplier[j];
        columns[i + j] += product & LOW_32;
        columns[i + j + 1] += product >>> 32;
      }
    }
    BigInteger product = BigInteger.ZERO;
    long carry = 0;
    for (int column = 0; column < columns.length; column++) {
      long sum = columns[column] + carry;
      product = product.or(BigInteger.valueOf(sum & LOW_32).shiftLeft(32 * column));
      carry = sum >>> 32;
    }
    return product.shiftRight(entry.shift());
  }

  /**
   * {@code n / 10^k} rounded to the nearest integer, halves away from zero, for any
   * {@code |n| <= bound}: the limb form's quotient plus one, halved, with the sign put back.
   */
  static BigInteger halfUpDivide(Entry entry, BigInteger n) {
    BigInteger floor = floorDivideByFivesLimbs(entry, n.abs());
    BigInteger magnitude = floor.add(BigInteger.ONE).shiftRight(1);
    return n.signum() < 0 ? magnitude.negate() : magnitude;
  }

  private static void requireMagnitude(Entry entry, BigInteger n) {
    if (n.signum() < 0 || n.compareTo(entry.bound()) > 0) {
      throw new IllegalArgumentException(n + " is outside [0, " + entry.bound() + "]");
    }
  }

  /** A non-negative value on 32-bit limbs, least significant first; zero is one zero limb. */
  static long[] limbsOf(BigInteger v) {
    int count = Math.max(1, (v.bitLength() + 31) / 32);
    long[] limbs = new long[count];
    for (int i = 0; i < count; i++) {
      limbs[i] = v.shiftRight(32 * i).and(MASK_32).longValue();
    }
    return limbs;
  }
}
