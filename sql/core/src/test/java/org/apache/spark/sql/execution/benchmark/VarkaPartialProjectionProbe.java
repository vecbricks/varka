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
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.VarkaColumnarToRowExec;
import org.apache.spark.sql.execution.VarkaFusionReport;

/**
 * The cost of a projection that fuses in part under a row consumer (VARKA-613), over the
 * day-time baseline's table at each null density, each query timed on both sessions. Four shapes
 * tell the candidate decline rules apart: (a) the baseline's blend, {@code extract(DAY)} fused
 * beside {@code HOUR}, {@code MINUTE} and {@code SECOND} of the same column on the row path;
 * (b) the same with the three residual extracts over the other column {@code dt2}; (c) three
 * fused arithmetic entries, {@code -dt}, {@code abs(dt)} and {@code dt * 3}, beside the residual
 * {@code extract(HOUR FROM dt)} of their column; and (d) the same three beside
 * {@code make_dt_interval(i, i2, i3)}, which reads none of their columns. The control is
 * {@code extract(DAY FROM dt)} alone, which fuses whole. Each query's plan on the Varka session
 * is printed with the Varka node's line per entry, so the output shows each shape is the split
 * it is named for. A diagnosis probe, not a baseline: its output is committed under
 * {@code sql/varka/plans/m8/results/}, and nothing checks its plans' classes.
 */
public class VarkaPartialProjectionProbe extends VarkaSqlBenchmarkBase {

  static final int NUM_ROWS = VarkaDayTimeIntervalBaselineBenchmark.NUM_ROWS;

  /** The shapes over one density's columns, each a title and a query. */
  static List<String[]> shapes(String nulls) {
    String dt = "dt" + nulls;
    String i = "i" + nulls;
    String from = " FROM " + VarkaDayTimeIntervalBaselineBenchmark.VIEW;
    String arithmetic = "-" + dt + " AS a, abs(" + dt + ") AS b, " + dt + " * 3 AS c, ";
    return List.of(
        new String[] {"control, extract(DAY FROM dt) alone" + nulls,
            "SELECT extract(DAY FROM " + dt + ") AS a" + from},
        new String[] {"a. the blend" + nulls,
            "SELECT extract(DAY FROM " + dt + ") AS a, extract(HOUR FROM " + dt + ") AS b, "
                + "extract(MINUTE FROM " + dt + ") AS c, extract(SECOND FROM " + dt + ") AS d"
                + from},
        new String[] {"b. other inputs" + nulls,
            "SELECT extract(DAY FROM " + dt + ") AS a, extract(HOUR FROM dt2) AS b, "
                + "extract(MINUTE FROM dt2) AS c, extract(SECOND FROM dt2) AS d" + from},
        new String[] {"c. heavy, shared" + nulls,
            "SELECT " + arithmetic + "extract(HOUR FROM " + dt + ") AS d" + from},
        new String[] {"d. heavy, other" + nulls,
            "SELECT " + arithmetic + "make_dt_interval(" + i + ", i2, i3) AS d" + from});
  }

  /** The plan's tree, then each Varka projection node's line per entry. */
  static String describe(SparkPlan plan) {
    var out = new StringBuilder(plan.treeString());
    for (SparkPlan p : operators(plan)) {
      if (p instanceof VarkaColumnarToRowExec node) {
        out.append("  entries: ").append(VarkaFusionReport.lines(node.classification(),
            node.projectList(), node.child().output()).mkString("; ")).append('\n');
      }
    }
    return out.toString();
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    stopLeftoverSessions();
    SparkSession baseline = VarkaArrowSessions.createSession(
        "varka-613-off", false, false, "local[1]");
    clearSessions();
    SparkSession varka = VarkaArrowSessions.createSession(
        "varka-613-on", true, false, "local[1]");
    clearSessions();
    try {
      for (SparkSession session : List.of(baseline, varka)) {
        session.sql(VarkaDayTimeIntervalBaselineBenchmark.tableSql(NUM_ROWS))
            .createOrReplaceTempView(VarkaDayTimeIntervalBaselineBenchmark.VIEW);
        VarkaArrowSessions.cache(session, VarkaDayTimeIntervalBaselineBenchmark.VIEW);
      }
      for (String nulls : List.of("", "_n")) {
        for (String[] q : shapes(nulls)) {
          System.out.println("PLAN " + q[0] + ":\n"
              + describe(varka.sql(q[1]).queryExecution().executedPlan()));
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
