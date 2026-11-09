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
import java.util.Set;

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DecimalType;

/**
 * `DECIMAL` above 18 digits held to the conformance contract (`m8/VARKA-359.md` 11.8 point 6),
 * at the type registry's {@code DECIMAL(30, 4)}: two columns read as the 128-bit lane pair, a low
 * and a high word a row, from Arrow's sixteen-byte slots. {@link VarkaDecimalLongConformanceSuite}
 * holds the other representation, the long of 18 digits or fewer, to the same checks.
 *
 * <p>A fixture's value is an index into {@code VALUES}, the unscaled values of a column: the
 * harness writes each slot as the indexed value's two words and hands Spark the {@code Decimal}
 * it names ({@link VarkaTypeConformance#decimalSlots}). The values are the precision's bounds and
 * their neighbours, a low word with its sign bit set under a high word of 0 ({@code 2^63}) and
 * its mirror under -1 ({@code -2^63 - 1}), a value whose low word is 0 ({@code -2^64}), one with
 * both words set ({@code 2^64 + 5}), and zero and one each way, so that both words of a value
 * carry bits and both signs occur.
 *
 * <p>The census marks the comparisons fused in part for {@code dec}: at one scale they fuse,
 * across two they wait for rows 360 and 363. So load, null handling and ordering hold every
 * comparison, {@code <=>} and the null tests to the reference and to Spark. The forms the census
 * owes ({@code IN}, the choices, {@code greatest} and {@code least}, 359b's; the casts, 364's; the
 * arithmetic, 360 to 363's) must decline, and cancel their checks naming the rows. A comparison
 * that declines fails: at one scale the census's fused-in-part marker means it fuses.
 *
 * <p>In its own JVM, as the long-lane suites are ({@link VarkaSpeciesGuard}).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaDecimalConformanceSuite extends VarkaTypeConformance {

  private static final DecimalType TYPE = DataTypes.createDecimalType(30, 4);
  private static final BigInteger BOUND = BigInteger.TEN.pow(30).subtract(BigInteger.ONE);
  private static final BigInteger TWO_63 = BigInteger.ONE.shiftLeft(63);
  private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

  /** The unscaled values the fixture's indices name. */
  private static final BigInteger[] VALUES = {
    BOUND.negate(), BOUND.negate().add(BigInteger.ONE), TWO_64.negate(),
    TWO_63.negate().subtract(BigInteger.ONE), BigInteger.ONE.negate(), BigInteger.ZERO,
    BigInteger.ONE, TWO_63, TWO_64.add(BigInteger.valueOf(5)), BOUND.subtract(BigInteger.ONE),
    BOUND};

  /** The indices of {@code 2^64 + 5} and of -1, the forms' literals. */
  private static final long[] PICKS = {8, 4};

  @Override
  protected ConformanceType type() {
    return decimal(TYPE, VALUES, PICKS);
  }

  @Override
  protected BigInteger[] decimalSlots() {
    return VALUES;
  }

  /** {@code IN} over the pair is 359b's: its fixture declines where the long's fuses. */
  @Override
  protected Set<String> declinesInPart() {
    return Set.of("in");
  }
}
