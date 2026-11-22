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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.storage.StorageLevel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TimesTest}'s twin for the math surface: every entry parses and runs on the stock
 * release the module compiles against, over a thousand rows of {@code varka_doubles}, so a
 * function the stock arm lacks, or a table the entries do not match, is found here and not by
 * the first runner dispatch.
 */
public class MathsTest {
  private static SparkSession spark;
  private static final DateSurfaceBenchmark.TableShape DOUBLES =
      DateSurfaceBenchmark.TableShape.DOUBLES;

  @BeforeAll
  public static void start() {
    spark = BenchSession.start("MathsTest");
    DateSurfaceBenchmark.buildTable(spark, 1_000L, 2, StorageLevel.MEMORY_ONLY(), DOUBLES);
  }

  @AfterAll
  public static void stop() {
    // The session is shared across the module's suites through getOrCreate, and the date
    // suites' residency check counts every cached partition in it; the doubles table leaves.
    spark.catalog().uncacheTable("varka_doubles");
    spark.catalog().dropTempView("varka_doubles");
    BenchSession.stop(spark);
  }

  @Test
  public void everyEntryRunsAsAProjectionOfOneDouble() {
    for (Surface.Entry e : Maths.ENTRIES) {
      assertEquals(null, e.filter(), e.label());
      String q = DateSurfaceBenchmark.projectionQuery(e, DOUBLES);
      Dataset<Row> df = spark.sql(q);
      assertEquals(1, df.schema().fields().length, q);
      assertEquals(DataTypes.DoubleType, df.schema().fields()[0].dataType(), q);
      assertEquals(1_000L, df.count(), q);
      assertEquals(DateSurfaceBenchmark.Fusion.PLAIN,
          DateSurfaceBenchmark.plansVarka(spark, q), q);
    }
  }

  /** The ranges the entries assume, and the two null patterns on different rows. */
  @Test
  public void theTableHasTheShapeTheEntriesAssume() {
    Row r = spark.sql("SELECT count(*), count(x), count(u), min(x), max(x), min(u), max(u), "
        + "count(CASE WHEN x IS NULL AND u IS NULL THEN 1 END) FROM varka_doubles").first();
    assertEquals(1_000L, r.getLong(0));
    assertEquals(967L, r.getLong(1), "every 31st x is null: ids 0, 31, ..., 992");
    assertEquals(968L, r.getLong(2), "every 31st u is null: ids 15, 46, ..., 976");
    assertTrue(r.getDouble(3) >= -10.0 && r.getDouble(3) < -9.0, "x reaches near -10");
    assertTrue(r.getDouble(4) < 10.0 && r.getDouble(4) > 9.0, "x reaches near 10");
    assertTrue(r.getDouble(5) > -1.0 && r.getDouble(6) < 1.0, "u is inside (-1, 1)");
    assertEquals(0L, r.getLong(7), "no row has both columns null");
    Row finite = spark.sql("SELECT count(*) FROM varka_doubles "
        + "WHERE isnan(x) OR isnan(u) OR abs(x) = double('inf')").first();
    assertEquals(0L, finite.getLong(0));
  }

  @Test
  public void labelsAreUniqueAndNotSharedWithTheOtherSurfaces() {
    long distinct = Maths.ENTRIES.stream().map(Surface.Entry::label).distinct().count();
    assertEquals(Maths.ENTRIES.size(), distinct);
    for (Surface.Entry e : Maths.ENTRIES) {
      assertTrue(Surface.ENTRIES.stream().noneMatch(d -> d.label().equals(e.label())),
          e.label());
      assertTrue(Times.ENTRIES.stream().noneMatch(t -> t.label().equals(e.label())),
          e.label());
    }
  }

  /** The entries read the doubles table's columns and nothing of the date table's. */
  @Test
  public void everyEntryIsSatisfiedByTheDoublesTableAndRefusedByTheDateOne() {
    for (Surface.Entry e : Maths.ENTRIES) {
      DateSurfaceBenchmark.requireColumns(e, DOUBLES);
      assertThrows(IllegalArgumentException.class,
          () -> DateSurfaceBenchmark.requireColumns(e, DateSurfaceBenchmark.TableShape.ALL),
          e.label());
    }
  }

  @Test
  public void theDoublesShapeBuildsTheColumnsItClaims() {
    List<String> built = List.of(spark.table("varka_doubles").schema().fieldNames());
    assertEquals(DOUBLES.columns(), built);
    assertEquals("varka_doubles", DOUBLES.tableName());
    assertEquals("x", DOUBLES.filterColumn());
    assertEquals(DOUBLES, DateSurfaceBenchmark.TableShape.of("doubles"));
  }

  @Test
  public void theMathSurfaceIsMeasuredOverTheCacheOnly() throws Exception {
    Path dir = Files.createTempDirectory("maths-test");
    assertThrows(IllegalArgumentException.class, () -> DateSurfaceBenchmark.buildParquetTable(
        spark, 1_000L, 2, DOUBLES, dir));
    Files.delete(dir);
  }
}
