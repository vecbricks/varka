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

package org.apache.spark.sql.execution.benchmark;

import java.util.List;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.VarkaBenchmark;

/**
 * The cost of a filter whose fused part is only its null tests (VARKA-598), split into its parts
 * over the decimal baseline's table at each null density, each query timed on both sessions:
 * the baseline's {@code DECIMAL(15, 2) x * y < z} filter, whose only fusing conjuncts are the
 * three {@code IS NOT NULL} tests Spark infers; those tests alone with one column out, the kernel
 * and the row conversion with nothing above; the same with the three columns out, the copy the
 * split hands its row filter; and {@code y IS NULL AND (x * y) IS NULL}, a selective null test
 * beside a conjunct that does not fuse. A diagnosis probe, not a baseline: its output is
 * committed under {@code sql/varka/plans/m8/results/}, and nothing checks its plans' classes.
 */
public class VarkaNullTestFilterProbe extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = VarkaDecimalBaselineBenchmark.NUM_ROWS;

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-598-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-598-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.conf().set("spark.sql.timeType.enabled", "true");
        session.sql(VarkaDecimalBaselineBenchmark.tableSql(NUM_ROWS))
            .createOrReplaceTempView("varka_decimal");
        VarkaArrowSessions.cache(session, "varka_decimal");
      }
      for (String n : List.of("", "n")) {
        String x = "x15" + n;
        String y = "y15" + n;
        String z = "z15" + n;
        String from = " FROM varka_decimal WHERE ";
        String tests = x + " IS NOT NULL AND " + y + " IS NOT NULL AND " + z + " IS NOT NULL";
        List<String[]> queries = List.of(
            new String[] {"x * y < z filter" + n, "SELECT " + x + from + x + " * " + y + " < " + z},
            new String[] {"null tests alone" + n, "SELECT " + x + from + tests},
            new String[] {"null tests, all columns out" + n,
                "SELECT " + x + ", " + y + ", " + z + from + tests},
            new String[] {"y IS NULL AND x * y IS NULL" + n,
                "SELECT " + x + from + y + " IS NULL AND (" + x + " * " + y + ") IS NULL"});
        for (String[] q : queries) {
          System.out.println("PLAN " + q[0] + ":\n"
              + varka.sql(q[1]).queryExecution().executedPlan().treeString());
          section(q[0], () -> {
            VarkaBenchmark benchmark =
                benchmark(q[0] + " over " + NUM_ROWS + " rows", NUM_ROWS).timed().build();
            benchmark.addCase("spark row path (varka off)", i -> noop(baseline.sql(q[1])));
            benchmark.addCase("varka on", i -> noop(varka.sql(q[1])));
            runCases(benchmark);
          });
        }
      }
    } finally {
      baseline.stop();
      varka.stop();
    }
  }
}
