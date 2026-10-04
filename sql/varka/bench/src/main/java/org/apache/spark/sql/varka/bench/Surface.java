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
 * The date surface: one entry per date expression the Varka compiler covers, in the spelling a
 * reader would write, with the projection form and the filter form of each where both exist.
 * The list is data on purpose: a task that adds an expression adds one line here and the next
 * dispatch times it (VARKA-62.md 3.4). {@code expectFused} is what the shell driver checks
 * on the fork: an entry marked fused that the plan shows residual fails the run rather than
 * being timed as if it were the kernel.
 *
 * <p>The table the queries run over is {@code varka_dates}: {@code d} a date with every 31st
 * row null, {@code d2} a second date about a year later, {@code i} a small int.
 */
public final class Surface {

  /** One entry: a label, a projection expression, a filter predicate; either may be null. */
  public record Entry(String label, String projection, String filter, boolean expectFused) {
    static Entry projection(String expr) {
      return new Entry(expr, expr, null, true);
    }

    static Entry filter(String pred) {
      return new Entry(pred, null, pred, true);
    }

    /**
     * This file used to carry a {@code residualFilter} factory for.
     * Three entries below - the two-column predicates - were held out of
     * {@code --expect-fused} because a row-engine {@code Project} sat above the Varka filter
     * whenever the consumer wanted fewer columns than the predicate reads, which Spark's
     * column pruning cannot remove and the columnar rule did not take. The rule absorbs that
     * projection into the filter node now, so the shapes fuse whole and are ordinary
     * {@link #filter} entries again, held to the flag like every other row. The factory is
     * gone rather than left unused: a future expected-partial shape should arrive with its
     * own reason written beside it, not inherit one from a debt that closed.
     */

    static Entry both(String expr, String pred) {
      return new Entry(expr, expr, pred, true);
    }
  }

  public static final List<Entry> ENTRIES = List.of(
      // Day arithmetic.
      Entry.projection("date_add(d, 3)"),
      Entry.projection("date_add(d, i)"),
      Entry.projection("date_sub(d, 5)"),
      // the int-cast day interval, a column offset under a per-batch bound.
      Entry.projection("d + CAST(i AS INTERVAL DAY)"),
      Entry.projection("datediff(d2, d)"),
      Entry.projection("unix_date(d)"),
      Entry.projection("date_from_unix_date(unix_date(d))"),
      // Calendar fields.
      Entry.both("year(d)", "year(d) = 2021"),
      Entry.projection("month(d)"),
      Entry.projection("day(d)"),
      Entry.projection("quarter(d)"),
      Entry.projection("dayofyear(d)"),
      Entry.both("dayofweek(d)", "dayofweek(d) = 1"),
      Entry.projection("weekday(d)"),
      Entry.projection("last_day(d)"),
      Entry.projection("next_day(d, 'MONDAY')"),
      // The week fold and the ISO fields.
      Entry.projection("weekofyear(d)"),
      Entry.projection("extract(DAYOFWEEK_ISO FROM d)"),
      Entry.projection("extract(YEAROFWEEK FROM d)"),
      // Month arithmetic and trunc. The column count is a column, and
      // `i` is 0..3649, inside VarkaChrono.MONTH_ARITH_MAX_MONTHS (24564), so no batch of it
      // trips the guard - a declined batch here would measure the row engine under a fused
      // row's name, which --expect-fused catches but the rate would not.
      Entry.projection("add_months(d, 3)"),
      Entry.projection("add_months(d, i)"),
      Entry.projection("d + INTERVAL 3 MONTH"),
      // the year-month interval column, one entry per unit, plus the two relabel
      // casts. The `d + ym` rows are the same kernel as `add_months(d, i)` above with the
      // count arriving as an interval, which is the point: the type costs nothing.
      Entry.projection("d + ymm"),
      Entry.projection("d + ymy"),
      Entry.projection("d + ym"),
      Entry.projection("CAST(i AS INTERVAL MONTH)"),
      Entry.projection("CAST(ymm AS INT)"),
      // the algebra whose operands and result are both intervals, and the two shapes
      // that reach `add_months`' month-count position through a derived count. Every one of
      // these is checked in every ANSI mode - Spark computes them with `addExact`,
      // `negateExact` or `multiplyExact` unconditionally - except where both operands are
      // calendar fields, which is what the last two projection rows are: the multiply and
      // `make_ym_interval` fuse with no check at all only because the bound proves them safe.
      Entry.projection("ymm + ymy"),
      Entry.projection("ymm - ymy"),
      Entry.projection("-ymm"),
      Entry.projection("abs(ymm)"),
      Entry.projection("d - ymm"),
      Entry.projection("d + CAST(month(d) AS INTERVAL YEAR)"),
      Entry.projection("CAST(month(d) AS INTERVAL YEAR) * 3"),
      Entry.projection("make_ym_interval(year(d), month(d))"),
      Entry.projection("trunc(d, 'YEAR')"),
      Entry.projection("trunc(d, 'MONTH')"),
      Entry.projection("trunc(d, 'QUARTER')"),
      Entry.projection("trunc(d, 'WEEK')"),
      // Conditionals and the n-ary functions.
      Entry.projection("if(d < d2, d, d2)"),
      Entry.projection("CASE WHEN d < d2 THEN d ELSE d2 END"),
      Entry.projection("coalesce(d, d2)"),
      Entry.projection("greatest(d, d2)"),
      Entry.projection("least(d, d2)"),
      // A fused chain: day arithmetic feeding a calendar field (a guarded shape).
      Entry.projection("year(date_add(d, 30))"),
      // Predicates.
      Entry.filter("d < d2"),
      Entry.filter("d = d2"),
      Entry.filter("d BETWEEN DATE'2020-06-01' AND DATE'2021-06-01'"),
      Entry.filter("d IN (DATE'2020-01-01', DATE'2020-07-01', DATE'2021-01-01')"),
      Entry.filter("d IS NULL"),
      Entry.filter("d IS NOT NULL"),
      Entry.filter("d < d2 AND month(d) = 6"));

  private Surface() {}
}
