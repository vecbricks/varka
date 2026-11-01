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

import org.apache.spark.sql.types.DataType;

/**
 * `INTERVAL MONTH` held to the conformance contract (VARKA-484): the seven checks of
 * {@link VarkaTypeConformance} over fixtures generated from the type's values.
 *
 * <p>A year-month interval is a count of months, any int. The column is {@code INTERVAL MONTH},
 * the type registry's spelling, whose casts to {@code INT} fuse. Interval arithmetic raises on
 * overflow whatever the mode, so its LEGACY forms decline the batches Spark raises on as the ANSI
 * ones do. The null handling check's {@code x + y} therefore draws half the int range, whose
 * sums all fit: over the extremes nearly every batch raises and its null patterns go unread.
 */
@VarkaSuite
public final class VarkaYearMonthIntervalConformanceSuite extends VarkaTypeConformance {

  @Override
  protected ConformanceType type() {
    return new ConformanceType("ym", DataType.fromDDL("INTERVAL MONTH"), "INTERVAL MONTH",
        v -> "INTERVAL '" + v + "' MONTH", integralValues(Integer.MIN_VALUE, Integer.MAX_VALUE),
        new long[] {1, -1},
        new PairForm("x + y", "+", halfRange(Integer.MIN_VALUE, Integer.MAX_VALUE)));
  }
}
