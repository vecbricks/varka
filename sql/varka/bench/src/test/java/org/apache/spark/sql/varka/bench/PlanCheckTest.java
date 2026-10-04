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

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The three plan shapes the laptop's first full run produced (VARKA-62.md 9), as
 * {@code EXPLAIN} prints them: the check must tell a fused filter from one with a row-engine
 * operator above it, since all three contain a Varka node. And the same check against another
 * engine's node, vecruntime's, as its arm runs it (VARKA-202).
 */
public class PlanCheckTest {

  private static final String FUSED = String.join("\n",
      "== Physical Plan ==",
      "VarkaFilterColumnarToRow ((isnotnull(d#311) AND (d#311 >= 2020-06-01)) AND "
          + "(d#311 <= 2021-06-01))",
      "+- Scan In-memory table varka_dates [d#311], [isnotnull(d#311)]",
      "      +- InMemoryRelation [d#311, d2#312, i#313], StorageLevel(disk, memory, "
          + "deserialized, 1 replicas)",
      "            +- *(1) Project [date_add(2020-01-01, cast((id#10L % 1500) as int)) AS d#7]",
      "               +- *(1) Range (0, 100000, step=1, splits=1)");

  private static final String RESIDUAL_FILTER = String.join("\n",
      "== Physical Plan ==",
      "*(1) Filter (year(d#200) = 2021)",
      "+- VarkaFilterColumnarToRow isnotnull(d#200)",
      "   +- Scan In-memory table varka_dates [d#200], [isnotnull(d#200), (year(d#200) = 2021)]");

  private static final String RESIDUAL_PROJECT = String.join("\n",
      "== Physical Plan ==",
      "*(1) Project [d#422]",
      "+- VarkaFilterColumnarToRow ((isnotnull(d#422) AND isnotnull(d2#423)) AND "
          + "(d#422 < d2#423))",
      "   +- Scan In-memory table varka_dates [d#422, d2#423], [isnotnull(d#422)]");

  private static final String COUNTED_FUSED = String.join("\n",
      "== Physical Plan ==",
      "*(1) HashAggregate(keys=[], functions=[count(1)], output=[count(1)#538L])",
      "+- *(1) Project",
      "   +- VarkaFilterColumnarToRow (isnotnull(d#533) AND (d#533 = 2020-01-01))",
      "      +- Scan In-memory table varka_dates [d#533], [isnotnull(d#533)]");

  private static final String PLAIN = String.join("\n",
      "== Physical Plan ==",
      "*(1) Filter (year(d#200) = 2021)",
      "+- Scan In-memory table varka_dates [d#200], [isnotnull(d#200), (year(d#200) = 2021)]");

  @Test
  public void aVarkaRootIsFused() {
    assertEquals(DateSurfaceBenchmark.Fusion.FUSED, DateSurfaceBenchmark.classifyPlan(FUSED));
  }

  @Test
  public void aRowEngineFilterAboveTheVarkaNodeIsPartial() {
    assertEquals(DateSurfaceBenchmark.Fusion.PARTIAL,
        DateSurfaceBenchmark.classifyPlan(RESIDUAL_FILTER));
  }

  @Test
  public void aNarrowingProjectAboveTheVarkaNodeIsPartial() {
    assertEquals(DateSurfaceBenchmark.Fusion.PARTIAL,
        DateSurfaceBenchmark.classifyPlan(RESIDUAL_PROJECT));
  }

  @Test
  public void theCountsEmptyProjectIsNotResidual() {
    assertEquals(DateSurfaceBenchmark.Fusion.FUSED,
        DateSurfaceBenchmark.classifyPlan(COUNTED_FUSED));
  }

  @Test
  public void noVarkaNodeIsPlain() {
    assertEquals(DateSurfaceBenchmark.Fusion.PLAIN, DateSurfaceBenchmark.classifyPlan(PLAIN));
  }

  /** vecruntime's projection over a Parquet scan, as its 0.0.3 plugin plans the ladder. */
  private static final String VECRUNTIME = String.join("\n",
      "== Physical Plan ==",
      "*(1) ColumnarToRow",
      "+- VectorProject [greatest(add_months(d#22, 1), date_add(d#22, 1), last_day(d#22)) "
          + "AS c1#2], false",
      "   +- FileScan parquet spark_catalog.default.dates[d#22] Batched: true");

  private static final String VECRUNTIME_UNDER_A_ROW_PROJECT = String.join("\n",
      "== Physical Plan ==",
      "*(1) Project [c1#2]",
      "+- *(1) ColumnarToRow",
      "   +- VectorProject [year(d#22) AS c1#2], false",
      "      +- FileScan parquet spark_catalog.default.dates[d#22] Batched: true");

  private static final Pattern VECRUNTIME_NODE = Pattern.compile("Vector(Project|Filter)");

  @Test
  public void anotherEnginesNodeIsReadByItsOwnMarker() {
    assertEquals(DateSurfaceBenchmark.Fusion.FUSED,
        DateSurfaceBenchmark.classifyPlan(VECRUNTIME, VECRUNTIME_NODE));
    assertEquals(DateSurfaceBenchmark.Fusion.PARTIAL,
        DateSurfaceBenchmark.classifyPlan(VECRUNTIME_UNDER_A_ROW_PROJECT, VECRUNTIME_NODE));
    // Varka's marker sees no Varka node in it, and vecruntime's none in a Varka plan.
    assertEquals(DateSurfaceBenchmark.Fusion.PLAIN, DateSurfaceBenchmark.classifyPlan(VECRUNTIME));
    assertEquals(DateSurfaceBenchmark.Fusion.PLAIN,
        DateSurfaceBenchmark.classifyPlan(FUSED, VECRUNTIME_NODE));
    assertEquals("VectorProject", DateSurfaceBenchmark.nodeFound(VECRUNTIME, VECRUNTIME_NODE));
    assertEquals("plain", DateSurfaceBenchmark.nodeFound(PLAIN, VECRUNTIME_NODE));
  }
}
