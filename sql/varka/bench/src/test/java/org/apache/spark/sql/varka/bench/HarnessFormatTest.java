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
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the harness's layout to the regexes {@code dev/varka_bench_diff.py} keys on, copied
 * here verbatim: a drift in the format would otherwise show up as an empty diff, not a
 * failure.
 */
public class HarnessFormatTest {
  private static final Pattern ROW = Pattern.compile(
      "^(.*?)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)X\\s*$");
  private static final Pattern HEADER = Pattern.compile("^(.*?):\\s+Best Time\\(ms\\)");

  @Test
  public void tableMatchesTheDiffScriptsRegexes() {
    String table = Harness.table("year(d) over 200000000 rows", 200_000_000L, List.of(
        new Harness.Case("projection, columnar consumer", new Harness.Stats(180.4, 190.0, 7.2)),
        new Harness.Case("filter, counted", new Harness.Stats(95.0, 99.5, 3.1))));
    String[] lines = table.split("\\R");
    assertEquals(4, lines.length, table);
    assertTrue(HEADER.matcher(lines[0]).find(), lines[0]);
    assertEquals("year(d) over 200000000 rows", HEADER.matcher(lines[0]).results()
        .findFirst().orElseThrow().group(1));
    assertTrue(lines[1].startsWith("----"));
    var row = ROW.matcher(lines[2]);
    assertTrue(row.matches(), lines[2]);
    assertEquals("projection, columnar consumer", row.group(1));
    assertEquals("180", row.group(2));
    assertEquals("190", row.group(3));
    assertEquals("7", row.group(4));
    // 200M rows in 180.4 ms is 1108.6 M rows/s, 0.9 ns per row, and the first case is 1.0X.
    assertEquals("1108.6", row.group(5));
    assertEquals("0.9", row.group(6));
    assertEquals("1.0", row.group(7));
    var second = ROW.matcher(lines[3]);
    assertTrue(second.matches(), lines[3]);
    assertEquals("1.9", second.group(7));
  }

  @Test
  public void longNamesWidenTheNameColumnWithoutBreakingTheRows() {
    String name = "d IN (DATE'2020-01-01', DATE'2020-07-01', DATE'2021-01-01') over 200000000 rows";
    String table = Harness.table(name, 1_000L,
        List.of(new Harness.Case("filter, counted", new Harness.Stats(1.0, 1.0, 0.0))));
    String[] lines = table.split("\\R");
    assertTrue(HEADER.matcher(lines[0]).find());
    assertTrue(ROW.matcher(lines[2]).matches(), lines[2]);
  }
}
