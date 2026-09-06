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

import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every entry parses and runs on a local stock session over a thousand rows, in both shapes:
 * the failure this catches is a typo in the list, before a two-hour run finds it. On stock
 * Spark no plan has a Varka node, which pins the EXPLAIN check's negative side.
 */
public class SurfaceTest {
  private static SparkSession spark;

  @BeforeAll
  public static void start() {
    spark = SparkSession.builder().master("local[1]").appName("SurfaceTest")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .getOrCreate();
    DateSurfaceBenchmark.buildTable(spark, 1_000L, 2);
  }

  @AfterAll
  public static void stop() {
    if (spark != null) {
      spark.stop();
    }
  }

  @Test
  public void everyEntryRunsInBothShapes() {
    for (Surface.Entry e : Surface.ENTRIES) {
      if (e.projection() != null) {
        String q = DateSurfaceBenchmark.projectionQuery(e);
        var df = spark.sql(q);
        assertEquals(1, df.schema().fields().length, q);
        assertEquals(1_000L, df.count(), q);
        assertEquals(DateSurfaceBenchmark.Fusion.PLAIN,
            DateSurfaceBenchmark.plansVarka(spark, q), q);
      }
      if (e.filter() != null) {
        String qc = DateSurfaceBenchmark.filterColumnarQuery(e);
        assertTrue(spark.sql(qc).count() <= 1_000L, qc);
        assertEquals(DateSurfaceBenchmark.Fusion.PLAIN,
            DateSurfaceBenchmark.plansVarka(spark, qc), qc);
        String q = DateSurfaceBenchmark.filterQuery(e);
        List<Row> rows = spark.sql(q).collectAsList();
        assertEquals(1, rows.size(), q);
        assertEquals(DataTypes.LongType, spark.sql(q).schema().fields()[0].dataType(), q);
        assertEquals(DateSurfaceBenchmark.Fusion.PLAIN,
            DateSurfaceBenchmark.plansVarka(spark, q), q);
      }
    }
  }

  @Test
  public void theTableHasTheShapeTheEntriesAssume() {
    Row r = spark.sql("SELECT count(*), count(d), count(d2), count(i), min(d), max(d2) "
        + "FROM varka_dates").first();
    assertEquals(1_000L, r.getLong(0));
    // Every 31st row's d is null: ids 0, 31, ..., 992 are 33 rows, so 967 non-null dates.
    assertEquals(967L, r.getLong(1));
    assertEquals(1_000L, r.getLong(2));
    assertEquals(1_000L, r.getLong(3));
    assertTrue(r.getDate(4).toString().startsWith("2020-01-0"), r.getDate(4).toString());
  }

  @Test
  public void labelsAreUniqueSoTablesAreTooAcrossFiles() {
    long distinct = Surface.ENTRIES.stream().map(Surface.Entry::label).distinct().count();
    assertEquals(Surface.ENTRIES.size(), distinct);
  }
}
