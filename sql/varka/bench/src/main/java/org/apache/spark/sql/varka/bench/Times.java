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

package org.apache.spark.sql.varka.bench;

import java.util.List;

/**
 * The {@code TIME} surface: one entry per expression over {@code TIME}, day-time interval and
 * {@code bigint} columns that the Varka compiler covers on its 64-bit lane, in the spelling a
 * reader would write, with the projection form and the filter form of each where both fuse.
 * Like {@link Surface} the list is data: a task that lowers an expression adds one line here
 * and the next run times it. Every entry is held to {@code --expect-fused} on the fork.
 *
 * <p>The table is {@code varka_times} ({@code DateSurfaceBenchmark.TableShape#TIMES}):
 * {@code t} a {@code TIME(6)} with every 31st row null, {@code t2} a second one, {@code dt} a
 * day-time interval under a minute whose sign keeps {@code t + dt} inside the day, {@code dt2}
 * a sub-second one, {@code l} and {@code l2} counts under ten thousand million.
 *
 * <p>Two things about the list are deliberate. The three field extracts have no filter form:
 * {@code hour(t) = 12} puts the extract's narrowed int under a comparison, which the kernel
 * cannot hold until VARKA-28, so the compiler declines it and a row timing that decline would
 * time the row engine (VARKA-102.md 8.6). And the arithmetic rows carry the shapes the
 * coverage table already proves fuse, so the first full run is a measurement and not a search
 * for the entry that does not.
 */
public final class Times {

  public static final List<Surface.Entry> ENTRIES = List.of(
      // The field extracts: on stock Spark each builds a LocalTime per row (an allocation);
      // on the fork each is one or two 64-bit constant divisions narrowed at the store.
      Surface.Entry.projection("hour(t)"),
      Surface.Entry.projection("minute(t)"),
      Surface.Entry.projection("second(t)"),
      // Truncation: a LocalTime truncatedTo per row on stock, a divide and a multiply here.
      Surface.Entry.both("time_trunc('MINUTE', t)", "time_trunc('MINUTE', t) < TIME'12:00:00'"),
      Surface.Entry.projection("time_trunc('MILLISECOND', t2)"),
      // Differences: integer arithmetic on both sides; what the ratio measures is the lane.
      Surface.Entry.both("t - t2", "t - t2 > INTERVAL '0' SECOND"),
      Surface.Entry.both("time_diff('HOUR', t, t2)", "time_diff('HOUR', t, t2) > 0"),
      Surface.Entry.projection("time_diff('microsecond', t2, t)"),
      // The interval add, guarded to the day on every row by how dt is generated.
      Surface.Entry.both("t + dt", "t + dt < TIME'12:00:00'"),
      // The choice family over the lane.
      Surface.Entry.both("greatest(t, t2)", "greatest(t, t2) > TIME'18:00:00'"),
      Surface.Entry.projection("CASE WHEN dt > INTERVAL '0' SECOND THEN dt ELSE dt2 END"),
      Surface.Entry.both("greatest(l, l2)", "greatest(l, l2) >= 5000000000"),
      Surface.Entry.projection("least(l, 5000000000)"),
      Surface.Entry.projection("CASE WHEN l < l2 THEN l ELSE l2 END"),
      Surface.Entry.projection("if(l IS NULL, l2, l)"),
      // The predicates on their own.
      Surface.Entry.filter("t < t2"),
      Surface.Entry.filter("t < TIME'12:00:00'"),
      Surface.Entry.filter("dt > dt2"),
      Surface.Entry.filter("dt < INTERVAL '0' SECOND"),
      Surface.Entry.filter("dt IS NOT NULL"),
      Surface.Entry.filter("l > l2"),
      Surface.Entry.filter("l >= 5000000000"),
      Surface.Entry.filter("l2 IS NULL"));

  private Times() {}
}
