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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    spark = BenchSession.start("SurfaceTest");
  }

  @AfterAll
  public static void stop() {
    BenchSession.stop(spark);
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

  /**
   * The residency guard's positive side: a table this small is trivially resident, so the
   * check must say so and must say it in the shape the provenance line publishes. The
   * negative side cannot be tested here without a table larger than the test JVM's heap; it
   * is evidenced instead by the three GitHub runs of 11 and 12 September 2026 that this
   * guard was written from, whose logs carry "Persisting block rdd_4_0 to disk instead".
   */
  @Test
  public void theCachedTableIsResidentAndTheProvenanceSaysSo() {
    assertTrue(DateSurfaceBenchmark.cacheResident(spark, 2));
    String state = DateSurfaceBenchmark.cacheState(spark, 2);
    assertTrue(state.startsWith("2 of 2 partitions cached"), state);
    assertTrue(state.endsWith("0.0 GiB on disk"), state);
  }

  /**
   * The two table shapes carry exactly the columns they claim.
   *
   * <p>This is the check whose absence produced VARKA-97: the benchmark table gained three
   * interval columns, every bandwidth-bound row in the surface was then measured over a table
   * twice as wide as before, and nothing said so. A shape whose column list and whose built
   * schema disagree would make any comparison between them meaningless.
   */
  @Test
  public void eachTableShapeBuildsTheColumnsItClaims() {
    try {
      for (DateSurfaceBenchmark.TableShape shape : DateSurfaceBenchmark.TableShape.values()) {
        DateSurfaceBenchmark.buildTable(spark, 1_000L, 1,
            org.apache.spark.storage.StorageLevel.MEMORY_ONLY(), shape);
        List<String> built = List.of(spark.table(shape.tableName()).schema().fieldNames());
        assertEquals(shape.columns(), built, shape + " builds a different set than it claims");
      }
    } finally {
      // `varka_dates` is the shared session's fixture and every other test in this class
      // reads it, so a shape left behind here fails them instead of this one. Restoring it
      // is not tidiness: the first version of this test did not, and two unrelated tests
      // failed with an unresolved interval column and a cache that was no longer cached.
      // The times table is dropped for the same reason: the residency check counts every
      // cached partition in the session, and the driver only ever caches one table.
      spark.catalog().uncacheTable("varka_times");
      spark.catalog().dropTempView("varka_times");
      DateSurfaceBenchmark.buildTable(spark, 1_000L, 2,
          org.apache.spark.storage.StorageLevel.MEMORY_ONLY(), DateSurfaceBenchmark.TableShape.ALL);
    }
  }

  /**
   * An entry reading a column the shape does not build is refused, and the message names it.
   *
   * <p>Skipping it instead would produce a results file with fewer rows that looks exactly
   * like a complete one - so two shapes could be compared over different entry sets with
   * neither file saying so, which is the failure this whole task exists to rule out.
   */
  @Test
  public void anEntryNeedingAnAbsentColumnIsRefusedRatherThanSkipped() {
    Surface.Entry interval = Surface.ENTRIES.stream()
        .filter(e -> (e.projection() != null ? e.projection() : e.filter()).contains("ymm"))
        .findFirst().orElseThrow(() -> new AssertionError("no entry reads an interval column"));
    var thrown = assertThrows(IllegalArgumentException.class,
        () -> DateSurfaceBenchmark.requireColumns(interval, DateSurfaceBenchmark.TableShape.DATES));
    assertTrue(thrown.getMessage().contains("ymm"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains(interval.label()), thrown.getMessage());
    // and the same entry is accepted by the shape that does build the column
    DateSurfaceBenchmark.requireColumns(interval, DateSurfaceBenchmark.TableShape.ALL);
  }

  /**
   * The checksum sees a wrong answer, which is the whole of VARKA-125.
   *
   * <p>The surface asserts that a row fused and that no batch fell back; it never compared what
   * the arms computed. A kernel that is fast and wrong would therefore publish a rate. Here the
   * "wrong kernel" is simulated the only way a test at this level can - the same shape over a
   * deliberately different expression - and the checksums must differ.
   */
  @Test
  public void theChecksumSeparatesTwoAnswersThatCountTheSame() {
    var right = DateSurfaceBenchmark.checksum(spark, "SELECT date_add(d, 3) AS a "
        + "FROM varka_dates", "a");
    var wrong = DateSurfaceBenchmark.checksum(spark, "SELECT date_add(d, 4) AS a "
        + "FROM varka_dates", "a");
    // The failure this guards against does not change the row count or the null count: an
    // off-by-one kernel produces exactly as many rows, exactly as many of them non-null, and
    // different values. Only the fold can tell them apart, so the test asserts that the other
    // two fields are equal and the fold is not.
    assertEquals(right.rows(), wrong.rows());
    assertEquals(right.nonNull(), wrong.nonNull());
    assertNotEquals(right.fold(), wrong.fold());
  }

  /**
   * And it sees a filter that selects the right number of rows and the wrong ones, which the
   * selectivity line beside it cannot.
   */
  @Test
  public void theChecksumSeparatesTwoFiltersOfEqualSelectivity() {
    var lo = DateSurfaceBenchmark.checksum(spark,
        "SELECT d FROM varka_dates WHERE i < 500", "d");
    var hi = DateSurfaceBenchmark.checksum(spark,
        "SELECT d FROM varka_dates WHERE i >= 500", "d");
    assertEquals(lo.rows(), hi.rows(), "the fixture should split evenly, or this proves nothing");
    assertNotEquals(lo.fold(), hi.fold());
  }

  /**
   * The fold is order-independent, because partitions finish in whatever order they finish in.
   * Two partitionings of one table must therefore agree, and a fold that depended on order -
   * a running hash, say - would not.
   */
  @Test
  public void theFoldDoesNotDependOnPartitionOrder() {
    String q = "SELECT date_add(d, 3) AS a FROM varka_dates";
    var two = DateSurfaceBenchmark.checksum(spark, q, "a");
    var seven = DateSurfaceBenchmark.checksum(spark,
        "SELECT date_add(d, 3) AS a FROM (SELECT /*+ REPARTITION(7) */ d FROM varka_dates)", "a");
    assertEquals(two.rows(), seven.rows());
    assertEquals(two.fold(), seven.fold());
  }

  /** Every entry runs under the full shape - so the refusal above cannot fire on a normal run. */
  @Test
  public void everyEntryIsSatisfiedByTheFullTable() {
    for (Surface.Entry e : Surface.ENTRIES) {
      DateSurfaceBenchmark.requireColumns(e, DateSurfaceBenchmark.TableShape.ALL);
    }
  }
}
