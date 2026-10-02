// Licensed to the Apache Software Foundation (ASF) under one or more contributor license
// agreements. See the NOTICE file distributed with this work for additional information regarding
// copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with the License. You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
// applicable law or agreed to in writing, software distributed under the License is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and limitations under the
// License.
// The post's examples: each prints its SQL and the plan Spark ran it with, so the post can quote
// both. An operator inside a whole-stage codegen stage carries a `*(n)` prefix; one without it
// runs on its own, row by row, with its own compiled code where it has any.
//
//   bin/spark-shell --master local[1] --driver-memory 2g -i examples.scala

import org.apache.spark.sql.DataFrame

spark.range(0, 1000)
  .selectExpr("id", "id % 10 AS g", "concat('row-', cast(id % 100 as string)) AS s",
    "map(id % 3, id) AS m")
  .createOrReplaceTempView("t")

def plan(df: DataFrame): String = df.queryExecution.executedPlan match {
  case a: org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanExec => a.executedPlan.treeString
  case p => p.treeString
}

def show(title: String, sql: String): Unit = {
  val df = spark.sql(sql)
  df.collect() // with adaptive execution on, the final plan exists once the query has run
  println(s"### $title")
  println(s"SQL> $sql")
  plan(df).split("\n").foreach(l => println(l.take(160)))
}

show("ORDER BY ... LIMIT plans a top-k sort, which is never in a stage",
  "select id, s from t order by s desc, id limit 5")
show("percentile_approx is an object aggregate, which is never in a stage",
  "select g, percentile_approx(id, 0.5) AS p50 from t group by g")
show("max of a string plans a sort aggregate, outside any stage",
  "select g, max(s) AS last_s from t group by g")
show("map_filter has no generated code: its projection leaves the stage",
  "select id, map_filter(m, (k, v) -> v > 10) AS big from t")
show("transform has none in 4.2 either (SPARK-37019 adds it in 4.3.0)",
  "select id, transform(array(id, id + 1), x -> x * 2) AS twice from t")

val cheap99 = (1 to 99).map(k => s"id + $k AS c$k").mkString(", ")
show("99 cheap columns under the defaults: one stage", s"select $cheap99 from t")
println("### the stage's largest generated method, from explain(\"codegen\")")
spark.sql(s"select $cheap99 from t").queryExecution.debug.codegenToSeq().zipWithIndex
  .foreach { case ((_, _, stats), i) =>
    println(s"stage ${i + 1}: maxMethodCodeSize ${stats.maxMethodCodeSize} bytes")
  }
spark.conf.set("spark.sql.codegen.maxFields", "98")
show("the same with spark.sql.codegen.maxFields=98: the projection runs on its own",
  s"select $cheap99 from t")
spark.conf.unset("spark.sql.codegen.maxFields")

spark.conf.set("spark.sql.adaptive.enabled", "false")
val branches = (1 to 3000).map(k => s"WHEN id = $k THEN id * $k").mkString(" ")
val big = spark.sql(s"select CASE $branches ELSE 0 END AS v from range(0, 10)")
big.collect()
println("### a CASE WHEN of 3000 branches: the plan after the stage failed to compile")
println("SQL> select CASE WHEN id = 1 THEN id * 1 ... (3000 branches) ELSE 0 END AS v " +
  "from range(0, 10)")
big.queryExecution.executedPlan.treeString.split("\n").foreach(l => println(l.take(160)))
spark.conf.unset("spark.sql.adaptive.enabled")
println(s"### Spark ${spark.version}, Java ${System.getProperty("java.version")}")
System.exit(0)
