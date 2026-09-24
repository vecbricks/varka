// Licensed to the Apache Software Foundation (ASF) under one or more contributor license
// agreements. See the NOTICE file distributed with this work for additional information regarding
// copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with the License. You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
// applicable law or agreed to in writing, software distributed under the License is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and limitations under the
// License.

// Spark's silent method-size cliff, on your own machine.
//
// Spark compiles a projection into one Java method. HotSpot never JIT-compiles a method whose
// bytecode is past 8000 bytes, so once a projection's method crosses that size it runs in the
// bytecode interpreter, several times slower, and Spark says nothing. This script widens one
// projection across the crossing and prints the method's size and the time per row, under
// Spark's defaults and under spark.sql.codegen.hugeMethodLimit=8000, the internal setting that
// makes Spark give up whole-stage codegen for such a method instead. The ratios are the point;
// the absolute times depend on your machine.
//
//   bin/spark-shell --master local[1] -i method_size_cliff.scala
//
// To see the JVM's side, add --driver-java-options "-XX:+PrintCompilation" and pipe the output
// through `grep project_doConsume`: the compile log names each version of the method it compiles
// with its size in bytes, and the ones past 8000 bytes never appear.
//
// Written for stock Apache Spark 4.2.0, where the crossing is between 48 and 52 entries.

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.execution.debug.codegenStringSeq

val rows = 500000L
val huge = 8000

/** One projection of n entries over generated dates, each entry a few date functions. */
def ladder(n: Int): DataFrame = spark.range(0, rows)
  .selectExpr("date_add(date'2020-01-01', cast(id % 1460 as int)) AS d")
  .selectExpr((1 to n).map(k =>
    s"greatest(add_months(d, $k), date_add(d, $k), last_day(d)) AS c$k"): _*)

/** The largest method of the projection's generated code, in bytes of bytecode. */
def methodBytes(n: Int): Int =
  codegenStringSeq(ladder(n).queryExecution.executedPlan).map(_._3.maxMethodCodeSize).max

/** Nanoseconds per row: the best of three runs, after one to warm the JIT up. */
def nsPerRow(n: Int, hugeMethodLimit: Option[Int]): Double = {
  val key = "spark.sql.codegen.hugeMethodLimit"
  hugeMethodLimit match {
    case Some(v) => spark.conf.set(key, v.toString)
    case None => spark.conf.unset(key)
  }
  try {
    val df = ladder(n)
    def run(): Long = {
      val start = System.nanoTime
      df.write.format("noop").mode("overwrite").save()
      System.nanoTime - start
    }
    run()
    Seq.fill(3)(run()).min.toDouble / rows
  } finally {
    spark.conf.unset(key)
  }
}

val rungs = Seq(44, 48, 52, 56)
val bytes = rungs.map(n => n -> methodBytes(n)).toMap
require(bytes(48) <= huge && bytes(52) > huge,
  s"on Spark ${spark.version} the method is ${bytes(48)} bytes at 48 entries and ${bytes(52)} " +
    s"at 52, so the crossing of $huge bytes is not between them; this script's rungs are for " +
    "Spark 4.2.0")

// Measured first and printed after, so Spark's progress bar does not break up the table.
val times = rungs.map(n => (nsPerRow(n, None), nsPerRow(n, Some(huge))))
println()
println("%-8s %-18s %22s %34s".format(
  "entries", "largest method", "ns per row, defaults", "ns per row, hugeMethodLimit=8000"))
for ((n, (defaults, limited)) <- rungs.zip(times)) {
  val size = s"${bytes(n)} bytes" + (if (bytes(n) > huge) " *" else "")
  println("%-8d %-18s %22.1f %34.1f".format(n, size, defaults, limited))
}
val first = rungs.find(n => bytes(n) > huge).get
println()
println(s"* past $huge bytes: HotSpot will not compile the method. At $first entries it is " +
  s"${bytes(first)} bytes (Spark ${spark.version}, Java ${System.getProperty("java.version")}).")
System.exit(0)
