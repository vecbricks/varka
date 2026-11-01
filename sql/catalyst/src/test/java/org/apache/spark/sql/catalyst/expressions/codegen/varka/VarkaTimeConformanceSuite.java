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

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import org.apache.spark.sql.types.TimeType;

/**
 * `TIME` held to the conformance contract (VARKA-484): the seven checks of
 * {@link VarkaTypeConformance} over fixtures generated from the type's values, at the long lane.
 *
 * <p>A time is nanoseconds from midnight, here at {@code TIME(6)}, the precision a {@code TIME}
 * literal takes, so every value is a whole microsecond: midnight, the microsecond after it, noon,
 * and the last two microseconds of the day. Two times subtract to a day-time interval, which the
 * null handling check takes as its pair; the census admits none of the overflow check's operators
 * for the type.
 *
 * <p>In its own JVM, as the long-lane emitter suites are: under the option matrix's lane-pinning
 * configurations its kernels use an int species of a size the int-lane suites do not, and a
 * shared JVM refuses a second one ({@link VarkaSpeciesGuard}).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaTimeConformanceSuite extends VarkaTypeConformance {

  private static final long MICRO = 1_000L;
  private static final long NOON = 12L * 3_600_000_000_000L;
  private static final long LAST = 24L * 3_600_000_000_000L - MICRO;
  private static final DateTimeFormatter LITERAL = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS");

  @Override
  protected ConformanceType type() {
    return new ConformanceType("time", new TimeType(TimeType.MICROS_PRECISION()), "TIME",
        v -> "TIME'" + LocalTime.ofNanoOfDay(v).format(LITERAL) + "'",
        new long[] {0, MICRO, NOON, LAST - MICRO, LAST}, new long[] {MICRO, NOON},
        new PairForm("x - y", "time_minus_time"));
  }
}
