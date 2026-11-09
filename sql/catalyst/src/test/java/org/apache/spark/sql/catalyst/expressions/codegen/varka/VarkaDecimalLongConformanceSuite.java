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

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DecimalType;

/**
 * `DECIMAL` of 18 digits or fewer held to the conformance contract (`m8/VARKA-359.md` 11.8 point
 * 6), at the type registry's {@code DECIMAL(10, 2)}: two columns read as one long a row, the low
 * word of Arrow's sixteen-byte slot (row 347's leaf). The other representation, the 128-bit pair,
 * is {@link VarkaDecimalConformanceSuite}'s, whose fixture form this suite shares: a value is an
 * index into {@link #values}, a slot holds the indexed value's two words, the high one its sign
 * extension, and Spark is handed the {@code Decimal} the index names.
 *
 * <p>The values are the precision's bounds and their neighbours and zero and one each way. The
 * census marks the comparisons fused in part for {@code dec}, which this representation fuses
 * at every scale (row 348) and the pair at one; every other form the census owes must decline.
 *
 * <p>In its own JVM, as the long-lane suites are ({@link VarkaSpeciesGuard}).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaDecimalLongConformanceSuite extends VarkaTypeConformance {

  private static final DecimalType TYPE = DataTypes.createDecimalType(10, 2);
  private static final BigInteger BOUND = BigInteger.TEN.pow(10).subtract(BigInteger.ONE);

  /** The unscaled values the fixture's indices name. */
  private static final BigInteger[] VALUES = {
    BOUND.negate(), BOUND.negate().add(BigInteger.ONE), BigInteger.ONE.negate(),
    BigInteger.ZERO, BigInteger.ONE, BOUND.subtract(BigInteger.ONE), BOUND};

  /** The indices of 1 and of -1, the forms' literals. */
  private static final long[] PICKS = {4, 2};

  @Override
  protected ConformanceType type() {
    return VarkaDecimalConformanceSuite.slotType(TYPE, VALUES, PICKS);
  }

  @Override
  protected BigInteger[] decimalSlots() {
    return VALUES;
  }
}
