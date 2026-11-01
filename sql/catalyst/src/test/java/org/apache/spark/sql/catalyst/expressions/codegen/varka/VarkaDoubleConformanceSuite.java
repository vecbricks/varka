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

import java.util.stream.DoubleStream;

import org.apache.spark.sql.types.DataTypes;

/**
 * `DOUBLE` held to the conformance contract (VARKA-484, VARKA-341): the checks of
 * {@link VarkaTypeConformance} at the DOUBLE lane, whose values are the raw bits of Spark's
 * special values - the canonical NaN and one with its sign bit set and another payload, both
 * zeros, both infinities, the extremes, the smallest subnormal - and of one and minus one, the
 * values the contract's literals name. Every pair of them meets in the ordering and choice
 * checks, so `NaN = NaN`, `-0.0 = 0.0` and NaN above every other value are held to Spark's
 * `eval` and to the reference evaluator, bit for bit on the picks.
 *
 * <p>The registers mark neither the arithmetic of the null-handling and overflow checks nor any
 * cast fused, so those checks are cancelled once their fused forms have run, naming the rows the
 * registers give each form: an operator's census entry names every row of its gaps, the
 * floating arithmetic's 342 among them, and a cast its {@code casts.tsv} pair's, 343 for most
 * and 346, 350 and 364 for the cast to {@code DECIMAL}.
 */
@VarkaSuite
public final class VarkaDoubleConformanceSuite extends VarkaTypeConformance {

  @Override
  protected ConformanceType type() {
    long[] values = DoubleStream.of(Double.NaN, 0.0, -0.0, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, 1.0,
            -1.0)
        .mapToLong(Double::doubleToRawLongBits)
        .toArray();
    long[] withPayloads = new long[values.length + 2];
    System.arraycopy(values, 0, withPayloads, 0, values.length);
    withPayloads[values.length] = 0xfff8000000000000L;
    withPayloads[values.length + 1] = 0x7ff8000000000001L;
    // The picks are the bits of one and minus one, and a literal spells the double of its bits.
    return new ConformanceType("double", DataTypes.DoubleType, "DOUBLE",
        v -> Double.longBitsToDouble(v) + "D", withPayloads,
        new long[] {Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(-1.0)},
        new PairForm("x + y", "+"));
  }
}
