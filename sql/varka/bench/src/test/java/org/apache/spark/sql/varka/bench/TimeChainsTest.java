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

import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TimeChains}, checked the way {@link ChainsTest} checks the date chains: every entry
 * runs over {@code varka_times}, produces one column, plans without a Varka node on stock
 * Spark, and carries the op count that earns it a place. The one property a unit test cannot
 * reach is the op count itself, which comes from the emitter in catalyst test scope;
 * {@link TimeChains} records it per entry and {@code dev/varka_emit.sh --table} regenerates it.
 */
public class TimeChainsTest {
  private static SparkSession spark;
  private static final DateSurfaceBenchmark.TableShape TIMES =
      DateSurfaceBenchmark.TableShape.TIMES;

  @BeforeAll
  public static void start() {
    spark = BenchSession.startWithTimes("TimeChainsTest");
  }

  @AfterAll
  public static void stop() {
    // The session is shared across the module's suites through getOrCreate, and the date
    // suites' residency check counts every cached partition in it; the times table leaves.
    spark.catalog().uncacheTable("varka_times");
    spark.catalog().dropTempView("varka_times");
    BenchSession.stop(spark);
  }

  /**
   * Every chain runs on every row of the table. This is also the midnight check: a sum with
   * an interval that left the day would throw here, on the same rows it would throw on in a
   * benchmark run.
   */
  @Test
  public void everyChainRuns() {
    for (Surface.Entry e : TimeChains.ENTRIES) {
      String q = DateSurfaceBenchmark.projectionQuery(e, TIMES);
      var df = spark.sql(q);
      assertEquals(1, df.schema().fields().length, q);
      assertEquals(1_000L, df.count(), q);
      assertEquals(DateSurfaceBenchmark.Fusion.PLAIN, DateSurfaceBenchmark.plansVarka(spark, q), q);
    }
  }

  /** The reason each entry is here, asserted: below {@link TimeChains#MIN_OPS} an entry is
   *  still bound by memory and cannot show the datapath the list exists to measure. */
  @Test
  public void everyChainIsHeavyEnoughToEarnItsPlace() {
    for (Surface.Entry e : TimeChains.ENTRIES) {
      int ops = TimeChains.emitterOps(e.label());
      assertTrue(ops >= TimeChains.MIN_OPS,
          e.label() + " is " + ops + " ops, under the " + TimeChains.MIN_OPS + " this list needs");
    }
  }

  @Test
  public void chainsAreExpectedToFuseAndAreNotInTheSurface() {
    List<String> surface = Times.ENTRIES.stream().map(Surface.Entry::label).toList();
    for (Surface.Entry e : TimeChains.ENTRIES) {
      assertTrue(e.expectFused(), e.label() + " must be expected to fuse or it times a fallback");
      assertTrue(!surface.contains(e.label()), e.label() + " is already in the TIME surface");
    }
  }

  /**
   * The list demonstrates the long lane's three types in single expressions. Every entry has
   * to read a {@code TIME} column and an interval column; the {@code bigint} columns can enter
   * only through a comparison, a null test, {@code greatest} or {@code least} until VARKA-104
   * builds their arithmetic, so they are required in a third of the list rather than all of
   * it. Counted over column references, tokenised, so that a literal never passes for a column.
   */
  @Test
  public void theChainsMixTheLongLanesThreeTypes() {
    int withBigint = 0;
    for (Surface.Entry e : TimeChains.ENTRIES) {
      List<String> tokens = List.of(e.projection().split("[^A-Za-z0-9_]+"));
      boolean time = tokens.contains("t") || tokens.contains("t2");
      boolean interval = tokens.contains("dt") || tokens.contains("dt2");
      assertTrue(time, e.label() + " has no TIME column");
      assertTrue(interval, e.label() + " has no interval column");
      if (tokens.contains("l") || tokens.contains("l2")) {
        withBigint++;
      }
    }
    assertTrue(withBigint * 3 >= TimeChains.ENTRIES.size(),
        "only " + withBigint + " entries read a bigint column; the list has drifted to two types");
  }

  @Test
  public void labelsAreUniqueSoTablesAreTooAcrossFiles() {
    long distinct = TimeChains.ENTRIES.stream().map(Surface.Entry::label).distinct().count();
    assertEquals(TimeChains.ENTRIES.size(), distinct);
  }
}
