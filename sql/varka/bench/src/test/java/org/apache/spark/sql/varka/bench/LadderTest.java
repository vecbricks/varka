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

import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LadderBenchmark}'s rungs, checked the way {@link ChainsTest} checks the chains: every
 * rung runs on stock Spark, produces as many columns as its entry count with the last one the
 * driver's {@code a}, and plans without a Varka node, which is the negative side of the
 * {@code EXPLAIN} check the driver relies on. The rungs are also held to the ladder's: nine,
 * increasing, straddling the vanilla crossing between 52 and 54 entries.
 */
public class LadderTest {
  private static SparkSession spark;

  @BeforeAll
  public static void start() {
    spark = BenchSession.start("LadderTest");
  }

  @AfterAll
  public static void stop() {
    BenchSession.stop(spark);
  }

  @Test
  public void everyRungRunsWithItsColumnsAndPlansPlain() {
    for (int i = 0; i < LadderBenchmark.ENTRIES.size(); i++) {
      Surface.Entry e = LadderBenchmark.ENTRIES.get(i);
      int n = LadderBenchmark.RUNGS.get(i);
      String q = DateSurfaceBenchmark.projectionQuery(e, DateSurfaceBenchmark.TableShape.DATES);
      var df = spark.sql(q);
      assertEquals(n, df.schema().fields().length, q);
      assertEquals("a", df.schema().fields()[n - 1].name(), "the driver's checksum column");
      assertEquals(1_000L, df.count(), q);
      assertEquals(DateSurfaceBenchmark.Fusion.PLAIN, DateSurfaceBenchmark.plansVarka(spark, q), q);
    }
  }

  @Test
  public void theRungsAreTheLaddersAndTheLastEntryIsUnaliased() {
    assertEquals(9, LadderBenchmark.RUNGS.size());
    for (int i = 1; i < LadderBenchmark.RUNGS.size(); i++) {
      assertTrue(LadderBenchmark.RUNGS.get(i) > LadderBenchmark.RUNGS.get(i - 1));
    }
    assertTrue(LadderBenchmark.RUNGS.contains(52) && LadderBenchmark.RUNGS.contains(54),
        "the rungs straddle vanilla's crossing");
    for (Surface.Entry e : LadderBenchmark.ENTRIES) {
      assertFalse(e.projection().matches("(?s).* AS c\\d+$"),
          "the last entry takes the driver's alias: " + e.label());
      assertTrue(e.expectFused(), e.label() + " is expected to fuse on the fork");
    }
  }
}
