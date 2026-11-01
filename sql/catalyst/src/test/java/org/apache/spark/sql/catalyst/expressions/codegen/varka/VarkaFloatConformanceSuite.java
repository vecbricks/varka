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

import org.apache.spark.sql.types.DataTypes;

/**
 * `FLOAT` held to the conformance contract (VARKA-484, VARKA-341): the checks of
 * {@link VarkaTypeConformance} at the FLOAT lane, over the raw bits, sign-extended to a long, of
 * the same values as {@link VarkaDoubleConformanceSuite}'s at single precision, two NaN payloads
 * included.
 *
 * <p>The arithmetic and the casts are cancelled as {@link VarkaDoubleConformanceSuite}'s are,
 * naming the rows the census entry or the {@code casts.tsv} pair gives each form.
 */
@VarkaSuite
public final class VarkaFloatConformanceSuite extends VarkaTypeConformance {

  @Override
  protected ConformanceType type() {
    float[] specials = {Float.NaN, 0.0f, -0.0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
        Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, 1.0f, -1.0f};
    long[] values = new long[specials.length + 2];
    for (int k = 0; k < specials.length; k++) {
      values[k] = Float.floatToRawIntBits(specials[k]);
    }
    values[specials.length] = 0xffc00000;
    values[specials.length + 1] = 0x7fc00001;
    // The picks are the bits of one and minus one, and a literal spells the float of its bits.
    return new ConformanceType("float", DataTypes.FloatType, "FLOAT",
        v -> Float.intBitsToFloat((int) v) + "F",
        values, new long[] {Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(-1.0f)},
        new PairForm("x + y", "+"));
  }
}
