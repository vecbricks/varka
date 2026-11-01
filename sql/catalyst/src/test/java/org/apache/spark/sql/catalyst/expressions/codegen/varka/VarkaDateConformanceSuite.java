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

import java.time.LocalDate;

import org.apache.spark.sql.types.DataTypes;

/**
 * `DATE` held to the conformance contract (VARKA-484): the seven checks of
 * {@link VarkaTypeConformance} over fixtures generated from the type's values.
 *
 * <p>A date is a day count from 1970-01-01. Its extremes are the first and last days of the
 * range Spark documents, 0001-01-01 to 9999-12-31, which is also the range a date column is read
 * as ({@link VarkaChrono#CONTRACT_MIN_DAYS}); the epoch and its neighbours sit between them. Two
 * dates have no arithmetic of their own type, so the null handling check takes {@code datediff},
 * and the overflow check finds no operator the census admits for the type.
 *
 * <p>In its own JVM: its cast to {@code TIMESTAMP_NTZ} runs a kernel of both lanes (VARKA-338),
 * and under the option matrix's lane-pinning configurations that kernel uses an int species of a
 * size the int-lane suites do not, a second one a shared JVM refuses ({@link VarkaSpeciesGuard}).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaDateConformanceSuite extends VarkaTypeConformance {

  @Override
  protected ConformanceType type() {
    long min = VarkaChrono.CONTRACT_MIN_DAYS;
    long max = VarkaChrono.CONTRACT_MAX_DAYS;
    return new ConformanceType("date", DataTypes.DateType, "DATE",
        v -> "DATE'" + LocalDate.ofEpochDay(v) + "'",
        new long[] {min, min + 1, -1, 0, 1, max - 1, max}, new long[] {1, -1},
        new PairForm("datediff(x, y)", "datediff"));
  }
}
