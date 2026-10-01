// Licensed to the Apache Software Foundation (ASF) under one or more contributor license
// agreements. See the NOTICE file distributed with this work for additional information regarding
// copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with the License. You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
// applicable law or agreed to in writing, software distributed under the License is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and limitations under the
// License.

// What Spark logs where the shell does not show it.
//
// `spark-shell` prints WARN and above; Spark logs several of its code generation give-ups at INFO,
// so in the shell they never appear, while `spark-submit` writes them. This script attaches one
// appender, at INFO, to Spark's loggers, filtered to those lines, and then provokes each: a method
// past the 8000 bytes HotSpot compiles, a common subexpression and an aggregate whose split
// functions would need more than 255 parameter slots, and an aggregate key the fast hash map does
// not support. The appender is the part to copy if you want these lines in your own shell.
//
//   bin/spark-shell --master local[1] --driver-memory 2g -i hidden_log.scala

import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.{Filter, LoggerContext}
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.filter.RegexFilter
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.spark.sql.DataFrame

val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
val config = ctx.getConfiguration
val appender = ConsoleAppender.newBuilder()
  .setName("codegen-give-ups")
  .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
  .setLayout(PatternLayout.newBuilder().withPattern("LOGGED %p %c{1}: %m%n").build())
  .setFilter(RegexFilter.createFilter(
    "(?s).*(too long|Failed to split|fast hashmap|Found too long).*",
    Array.empty[String], false, Filter.Result.ACCEPT, Filter.Result.DENY))
  .build()
appender.start()
config.addAppender(appender)
val spark_ = new LoggerConfig("org.apache.spark.sql", Level.INFO, true)
spark_.addAppender(appender, Level.INFO, null)
config.addLogger("org.apache.spark.sql", spark_)
ctx.updateLoggers()

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

// The next two need the operator in a stage with 130 input columns, past maxFields' 100.
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
