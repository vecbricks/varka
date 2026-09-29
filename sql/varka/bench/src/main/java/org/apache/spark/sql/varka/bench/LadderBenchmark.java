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

import java.io.IOException;
import java.util.List;

/**
 * The size ladder through the surface's driver, so that it runs on a stock Spark distribution
 * as well as on the fork (task 194): the projection of task 171's ladder widened rung by rung,
 * {@code greatest(add_months(d, k), date_add(d, k), last_day(d))} for every {@code k} up to the
 * rung, over the surface's {@code varka_dates}, whose {@code d} is generated as the ladder's
 * date column is.
 *
 * <pre>
 *   spark-submit --master local[1] --driver-memory 6g --class ...LadderBenchmark \
 *     varka-bench.jar --label spark-4.2.0-jdk25 --rows 2000000 --out FILE [--input parquet]
 * </pre>
 *
 * <p><b>Why.</b> The fork's ladder ({@code VarkaSizeLadderBenchmark}) times its vanilla arm
 * through the fork's own codegen over the fork's Arrow cache, and a stock user runs neither:
 * stock Spark 4.2.0 crosses HotSpot's 8000-byte limit between 48 and 52 entries where the fork
 * crosses between 52 and 54 ({@code PLAN_TASK_192.md} 9.2), and Spark's default cache is not
 * Arrow. Here every distribution the surface runs - stock 4.2.0 on two JDKs, the fork with Varka
 * off and with it on - times the same rungs over its own default cache, or over a Parquet file
 * under {@code --input parquet}, so the ratio a reader would see against the Spark they
 * download is in one table with the fork's.
 *
 * <p><b>The shape of an entry.</b> A rung is one entry whose projection lists the rung's
 * entries; the driver aliases the last as {@code a} and checksums that column, so every rung's
 * row carries a checksum the distributions' answers are checked against. The rungs are
 * {@code VarkaSizeLadder}'s, written out here because this module runs on distributions that do
 * not carry the fork's test classes. The rows are the ladder's two million, not the surface's
 * hundred million: vanilla's interpreted arm takes eighteen seconds an iteration at a hundred
 * entries over two million rows on a runner, so the fixed-share rule, made for rows that run in
 * milliseconds, is lifted for this benchmark and the file says so in its provenance.
 */
public final class LadderBenchmark {

  /** The ladder's rungs, straddling vanilla's crossing (task 171). */
  static final List<Integer> RUNGS = List.of(16, 32, 48, 52, 54, 56, 64, 80, 100);

  /** One entry of the ladder, aliased; the rung's last entry goes unaliased for the driver. */
  static String entry(int k) {
    return "greatest(add_months(d, " + k + "), date_add(d, " + k + "), last_day(d))";
  }

  /** The projection of a rung: every entry but the last aliased, the last left to the driver. */
  static String rung(int n) {
    StringBuilder sb = new StringBuilder();
    for (int k = 1; k < n; k++) {
      sb.append(entry(k)).append(" AS c").append(k).append(", ");
    }
    return sb.append(entry(n)).toString();
  }

  public static final List<Surface.Entry> ENTRIES = RUNGS.stream()
      .map(n -> new Surface.Entry(n + " entries", rung(n), null, true))
      .toList();

  public static void main(String[] argv) throws IOException {
    DateSurfaceBenchmark.run(argv, ENTRIES, "ladder", "VarkaLadder",
        DateSurfaceBenchmark.TableShape.DATES);
  }

  private LadderBenchmark() {}
}
