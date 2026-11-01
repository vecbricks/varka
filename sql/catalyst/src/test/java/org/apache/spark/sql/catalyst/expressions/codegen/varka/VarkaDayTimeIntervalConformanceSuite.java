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

import org.apache.spark.sql.catalyst.util.IntervalStringStyles$;
import org.apache.spark.sql.catalyst.util.IntervalUtils$;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DayTimeIntervalType;

/**
 * `INTERVAL DAY TO SECOND` held to the conformance contract (VARKA-484): the seven checks of
 * {@link VarkaTypeConformance} over fixtures generated from the type's values, at the long lane.
 *
 * <p>A day-time interval is a count of microseconds, any long. The column is
 * {@code INTERVAL DAY TO SECOND}, the form that holds every microsecond (an {@code INTERVAL DAY}
 * holds whole days), and a literal is spelled in it by Spark's own formatter. Interval arithmetic
 * raises on overflow whatever the mode, so its LEGACY forms decline the batches Spark raises on
 * as the ANSI ones do. The null handling check's {@code x + y} therefore draws half the long
 * range, whose sums all fit: over the extremes nearly every batch raises.
 *
 * <p>In its own JVM, as the long-lane emitter suites are: under the option matrix's lane-pinning
 * configurations its kernels use an int species of a size the int-lane suites do not, and a
 * shared JVM refuses a second one ({@link VarkaSpeciesGuard}).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaDayTimeIntervalConformanceSuite extends VarkaTypeConformance {

  private static final DayTimeIntervalType TYPE =
      (DayTimeIntervalType) DataType.fromDDL("INTERVAL DAY TO SECOND");

  @Override
  protected ConformanceType type() {
    return new ConformanceType("dt", TYPE, "INTERVAL DAY TO SECOND",
        v -> IntervalUtils$.MODULE$.toDayTimeIntervalString(v,
            IntervalStringStyles$.MODULE$.ANSI_STYLE(), TYPE.startField(), TYPE.endField()),
        integralValues(Long.MIN_VALUE, Long.MAX_VALUE), new long[] {1, -1},
        new PairForm("x + y", "+", halfRange(Long.MIN_VALUE, Long.MAX_VALUE)));
  }
}
