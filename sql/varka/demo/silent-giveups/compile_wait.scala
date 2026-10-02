// Licensed to the Apache Software Foundation (ASF) under one or more contributor license
// agreements. See the NOTICE file distributed with this work for additional information regarding
// copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with the License. You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
// applicable law or agreed to in writing, software distributed under the License is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and limitations under the
// License.

// How long a new whole-stage codegen stage runs before its code is fast.
//
// HotSpot compiles a hot method with C1 at once and queues it for C2; until C2's code arrives the
// stage runs C1's, which for a wide stage can mean many seconds, and every executor JVM pays it
// again for every stage it compiles. This script runs a few query shapes back to back, each new to
// the JVM, and prints every query's time and when it settled.
//
//   bin/spark-shell --master local[1] --driver-memory 2g -i compile_wait.scala
//
// COMPILE_WAIT_SECONDS sets how long each shape runs (45 by default). Run it under
// --driver-java-options "-XX:+PrintCompilation" to see C2's compiles land.

import org.apache.spark.sql.DataFrame

val rows = 1000000L
val seconds = sys.env.getOrElse("COMPILE_WAIT_SECONDS", "45").toInt

spark.range(0, rows).selectExpr("id", "id % 10 AS g").createOrReplaceTempView("source")
spark.sql("cache table compile_wait_rows as select * from source")
def run(df: DataFrame): Unit = df.write.format("noop").mode("overwrite").save()
run(spark.table("compile_wait_rows"))

def projection(n: Int, offset: Int): DataFrame = spark.sql(
  s"select ${(1 to n).map(k => s"id + ${k + offset} AS c$k").mkString(", ")} " +
  "from compile_wait_rows")
def aggregate(n: Int, offset: Int): DataFrame = spark.sql(
  s"select g, ${(1 to n).map(k => s"sum(id + ${k + offset}) AS s$k").mkString(", ")} " +
  "from compile_wait_rows group by g")

// Spark's own code paths compile on the first queries; a small shape runs first, unrecorded.
(1 to 5).foreach { _ => run(projection(5, 1)); run(aggregate(5, 1)) }

def upperMedian(xs: Seq[Long]): Long = { val sorted = xs.sorted; sorted(sorted.size / 2) }
var offset = 1000
val results = scala.collection.mutable.ArrayBuffer.empty[String]
def series(label: String, settings: Seq[(String, String)], query: Int => DataFrame): Unit = {
  offset += 1000
  val o = offset
  settings.foreach { case (k, v) => spark.conf.set(k, v) }
  val times = scala.collection.mutable.ArrayBuffer.empty[Long]
  val start = System.nanoTime()
  while (System.nanoTime() - start < seconds * 1000000000L) {
    val t = System.nanoTime()
    run(query(o))
    times += (System.nanoTime() - t) / 1000000
  }
  settings.foreach { case (k, _) => spark.conf.unset(k) }
  // The median of the last ten queries is the steady state, and the wait is the time before the
  // first five queries in a row whose median is within 10% of it.
  val median = upperMedian(times.takeRight(10).toSeq)
  val settled = (0 to times.size - 5).find { i =>
    upperMedian(times.slice(i, i + 5).toSeq) <= median * 1.1
  }
  val steady = settled.map(i => times.take(i).sum).getOrElse(times.sum)
  results += s"### $label: steady after $steady ms at a median of $median ms " +
    s"over ${times.size} queries"
  results += s"ms per query: ${times.mkString(" ")}"
}

series("150 entries id + k, maxFields=200, in a stage",
  Seq("spark.sql.codegen.maxFields" -> "200"), projection(150, _))
series("150 entries id + k, maxFields=100 (the default), outside a stage", Nil, projection(150, _))
series("99 entries id + k, the defaults, in a stage", Nil, projection(99, _))
series("99 entries id + k, wholeStage=false",
  Seq("spark.sql.codegen.wholeStage" -> "false"), projection(99, _))
series("60 sums by a key of ten values, the defaults, in a stage", Nil, aggregate(60, _))
series("60 sums by a key of ten values, wholeStage=false",
  Seq("spark.sql.codegen.wholeStage" -> "false"), aggregate(60, _))

// Printed after the runs, so Spark's progress bar does not break up the lines.
println()
results.foreach(println)
println(s"### Spark ${spark.version}, Java ${System.getProperty("java.version")}")
System.exit(0)
