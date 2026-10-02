// Licensed to the Apache Software Foundation (ASF) under one or more contributor license
// agreements. See the NOTICE file distributed with this work for additional information regarding
// copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with the License. You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
// applicable law or agreed to in writing, software distributed under the License is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and limitations under the
// License.
// The same give-ups as hidden_log.scala, with no appender: two loggers raised to INFO in
// `conf/log4j2.properties`, which is all a shell needs to show them. `spark-shell` drops INFO
// lines unless their logger is configured on its own, so these two lines pass and the rest of
// Spark's INFO stays hidden. The workflow appends `log_level.log4j2.properties` to the
// distribution's `log4j2.properties.template` before this script runs, as you would.
//
//   cat conf/log4j2.properties.template log_level.log4j2.properties > conf/log4j2.properties
//   bin/spark-shell --master local[1] --driver-memory 2g -i log_level.scala

import org.apache.spark.sql.DataFrame

def run(df: DataFrame): Unit = df.write.format("noop").mode("overwrite").save()
def nullableInts(n: Int): DataFrame = spark.range(0, 20).selectExpr(
  (1 to n).map(k => s"cast(if(id % 7 = $k % 7, null, id + $k) as int) AS c$k"): _*)
  .repartition(1)
val sum130 = (1 to 130).map(k => s"c$k").mkString(" + ")

println("### a method past 8000 bytes: 56 date entries in one projection")
run(spark.range(0, 100)
  .selectExpr("date_add(date'2020-01-01', cast(id % 1460 as int)) AS d")
  .selectExpr((1 to 56).map(k => s"greatest(add_months(d, ${k + 9000}), " +
    s"date_add(d, ${k + 9000}), last_day(d)) AS c$k"): _*))

spark.conf.set("spark.sql.codegen.maxFields", "1000")
spark.conf.set("spark.sql.adaptive.enabled", "false")
println("### a common subexpression over 130 nullable columns: 260 parameter slots")
run(nullableInts(130).selectExpr(s"($sum130) * 2 AS a", s"($sum130) * 3 AS b"))
println("### an aggregate over 130 nullable columns")
nullableInts(130).selectExpr(s"sum($sum130) AS s").collect()
spark.conf.unset("spark.sql.codegen.maxFields")

println("### a struct key: the fast hash map does not support it")
spark.range(0, 100).selectExpr("struct(id % 10) AS s", "id AS v").groupBy("s").sum("v").collect()
spark.conf.unset("spark.sql.adaptive.enabled")
println(s"### Spark ${spark.version}, Java ${System.getProperty("java.version")}")
System.exit(0)
