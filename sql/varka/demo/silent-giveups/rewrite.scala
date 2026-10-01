// Licensed to the Apache Software Foundation (ASF) under one or more contributor license
// agreements. See the NOTICE file distributed with this work for additional information regarding
// copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with the License. You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
// applicable law or agreed to in writing, software distributed under the License is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and limitations under the
// License.
// Moving a fallback expression out of the way does not take it out of the plan.
//
// A `from_json` in a projection takes the projection out of its whole-stage codegen stage
// (silent.scala). The obvious rewrite, computing everything else first and `from_json` in a
// projection of its own, is undone by the optimizer: `CollapseProject` merges two projections
// whenever the upper one uses each column of the lower one once, and a projection over an
// aggregate into the aggregate. Each section runs a query and prints its executed plan; an
// operator in a stage carries a `*(n)` prefix.
//
//   bin/spark-shell --master local[1] -i rewrite.scala

import org.apache.spark.sql.DataFrame

spark.range(0, 1000)
  .selectExpr("id", "id % 10 AS g", "concat('{\"a\":', cast(id % 10 as string), '}') AS js")
  .createOrReplaceTempView("t")

def show(title: String, df: DataFrame): Unit = {
  df.collect()
  println(s"### $title")
  df.explain()
}

show("from_json in its own projection, after the rest: merged back into one",
  spark.table("t").selectExpr("id + 1 AS a", "js")
    .selectExpr("a", "from_json(js, 'a INT').a AS j"))
show("from_json over an aggregate's output: merged into the aggregate",
  spark.sql("select g, s, from_json(js, 'a INT').a AS j " +
    "from (select g, sum(id) AS s, max(js) AS js from t group by g)"))
show("get_json_object over the aggregate's output: everything in a stage",
  spark.sql("select g, s, get_json_object(js, '$.a') AS j " +
    "from (select g, sum(id) AS s, max(js) AS js from t group by g)"))
println(s"### Spark ${spark.version}, Java ${System.getProperty("java.version")}")
System.exit(0)
