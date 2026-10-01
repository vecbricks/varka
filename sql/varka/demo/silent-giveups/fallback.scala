// Licensed to the Apache Software Foundation (ASF) under one or more contributor license
// agreements. See the NOTICE file distributed with this work for additional information regarding
// copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with the License. You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
// applicable law or agreed to in writing, software distributed under the License is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and limitations under the
// License.

// Where Spark gives up whole-stage codegen, says so, and still answers.
//
// Two fallbacks that log at WARN, which the shell shows: a stage whose generated method passes the
// JVM's 64 KB limit fails to compile and runs its operators one by one instead, and a projection
// outside a stage that fails to compile is evaluated by the interpreter. Both answers are right;
// the log is the only sign, along with the time.
//
//   bin/spark-shell --master local[1] --driver-memory 2g -i fallback.scala

def time[T](body: => T): (T, Long) = {
  val t = System.nanoTime(); val r = body; (r, (System.nanoTime() - t) / 1000000)
}

spark.conf.set("spark.sql.adaptive.enabled", "false")
println("### a CASE WHEN of 3000 branches inside a stage: past 64 KB")
val branches = (1 to 3000).map(k => s"WHEN id = $k THEN id * $k").mkString(" ")
val (rows3000, ms3000) = time(spark.sql(
  s"select CASE $branches ELSE 0 END AS v from range(0, 10)").collect())
println(s"### the query answered ${rows3000.length} rows in $ms3000 ms")

println("### 1000 entries outside a stage with method splitting off: past 64 KB")
spark.conf.set("spark.sql.codegen.wholeStage", "false")
spark.conf.set("spark.sql.codegen.methodSplitThreshold", Int.MaxValue.toString)
// Over a nullable column, so that every entry carries its null check: over `id`, which is never
// null, the same 1000 entries stay under 64 KB.
val (rows1000, ms1000) = time(spark.sql(
  s"select ${(1 to 1000).map(k => s"x + $k AS c$k").mkString(", ")} " +
  "from (select if(id % 7 = 0, null, id) AS x from range(0, 10))").collect())
println(s"### the query answered ${rows1000.length} rows in $ms1000 ms")
spark.conf.unset("spark.sql.codegen.wholeStage")
spark.conf.unset("spark.sql.codegen.methodSplitThreshold")
spark.conf.unset("spark.sql.adaptive.enabled")
println(s"### Spark ${spark.version}, Java ${System.getProperty("java.version")}")
System.exit(0)
