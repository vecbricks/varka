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
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The driver's {@code --input parquet} (VARKA-194): the date table written to a Parquet file
 * and read back as {@code varka_dates}, with the same rows and columns as the cached table and
 * nothing cached, so a timing over it is a timing of the file's path; and the refusal of the
 * {@code TIME} table, which Parquet does not write on every distribution.
 */
public class ParquetInputTest {
  private static SparkSession spark;
  private static Path dir;

  @BeforeAll
  public static void start() throws IOException {
    spark = BenchSession.start("ParquetInputTest");
    dir = Files.createTempDirectory("varka-bench-parquet-test");
  }

  @AfterAll
  public static void stop() {
    BenchSession.stop(spark);
  }

  @Test
  public void theParquetTableHasTheCachedTablesRowsAndIsNotCached() {
    var cached = spark.sql("SELECT count(*), count(d), count(d2), count(i) FROM varka_dates")
        .first();
    DateSurfaceBenchmark.buildParquetTable(spark, 1_000L, 2,
        DateSurfaceBenchmark.TableShape.DATES, dir);
    var parquet = spark.sql("SELECT count(*), count(d), count(d2), count(i) FROM varka_dates")
        .first();
    assertEquals(cached, parquet);
    assertFalse(spark.catalog().isCached("varka_dates"), "the Parquet table must not be cached");
    assertTrue(spark.table("varka_dates").queryExecution().executedPlan().toString()
        .contains("Parquet"), "the view reads the file");
    // A ladder rung runs over it as over the cache.
    String q = DateSurfaceBenchmark.projectionQuery(LadderBenchmark.ENTRIES.get(0),
        DateSurfaceBenchmark.TableShape.DATES);
    assertEquals(1_000L, spark.sql(q).count());
  }

  @Test
  public void theTimesTableIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> DateSurfaceBenchmark.buildParquetTable(
        spark, 1_000L, 2, DateSurfaceBenchmark.TableShape.TIMES, dir));
  }
}
