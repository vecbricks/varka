// Licensed to the Apache Software Foundation (ASF) under one or more contributor license
// agreements. See the NOTICE file distributed with this work for additional information regarding
// copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with the License. You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
// applicable law or agreed to in writing, software distributed under the License is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and limitations under the
// License.

// Where Spark gives up whole-stage codegen and says nothing.
//
// An operator that leaves its whole-stage codegen stage leaves no log line; the only sign is in
// the plan, where the operators of a stage carry a `*(n)` prefix and the one that left does not.
// Each section below runs a query and prints its executed plan. Run the query before explaining
// it: with adaptive execution on, as it is by default, the stages exist only once the query has
// run.
//
//   bin/spark-shell --master local[1] -i silent.scala

import org.apache.spark.sql.DataFrame

spark.range(0, 1000)
  .selectExpr("id", "id % 10 AS g", "concat('{\"a\":', cast(id % 10 as string), '}') AS js")
  .createOrReplaceTempView("t")

def show(title: String, df: DataFrame): Unit = {
  df.write.format("noop").mode("overwrite").save()
  println(s"### $title")
  df.explain()
}

show("from_json has no generated code: its projection leaves the stage",
  spark.sql("select id + 1 AS a, from_json(js, 'a INT').a AS j from t"))
show("get_json_object has: the same projection stays in the stage",
  spark.sql("select id + 1 AS a, get_json_object(js, '$.a') AS j from t"))
show("a window is not part of any stage",
  spark.sql("select id, sum(id) over (partition by g order by id) AS running from t"))
show("collect_list makes an object hash aggregate, which is not part of any stage",
  spark.sql("select g, collect_list(id) AS ids from t group by g"))
show("100 output columns: in a stage",
  spark.sql(s"select ${(1 to 100).map(k => s"id + $k AS c$k").mkString(", ")} from t"))
show("101 output columns: past spark.sql.codegen.maxFields, out of the stage",
  spark.sql(s"select ${(1 to 101).map(k => s"id + $k AS c$k").mkString(", ")} from t"))
println(s"### Spark ${spark.version}, Java ${System.getProperty("java.version")}")
System.exit(0)
