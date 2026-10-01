// Licensed to the Apache Software Foundation (ASF) under one or more contributor license
// agreements. See the NOTICE file distributed with this work for additional information regarding
// copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with the License. You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by
// applicable law or agreed to in writing, software distributed under the License is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and limitations under the
// License.

// Where a code generation give-up fails the query.
//
// Most of Spark's generators are reached through a factory that falls back to an interpreted
// version when the generated code fails to compile. About thirty call sites ask a generator
// directly, and there the failure is the query's. `ORDER BY ... LIMIT` is one: the top-k operator
// generates its ordering itself. With method splitting off, an ordering over a CASE WHEN of 1200
// branches is one method past 64 KB, and the query fails with the compiler's error.
//
//   bin/spark-shell --master local[1] --driver-memory 2g -i failing.scala

spark.conf.set("spark.sql.adaptive.enabled", "false")
spark.conf.set("spark.sql.codegen.methodSplitThreshold", Int.MaxValue.toString)
val branches = (1 to 1200).map(k => s"WHEN id = $k THEN id * $k").mkString(" ")
println("### ORDER BY a CASE WHEN of 1200 branches, LIMIT 5")
try {
  spark.sql(s"select id from range(10) order by CASE $branches ELSE 0 END limit 5").collect()
  println("### the query answered")
} catch {
  case e: Throwable =>
    val chain = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).toSeq
    println(s"### the query failed: ${e.getClass.getName}")
    chain.map(c => String.valueOf(c.getMessage).split("\n").head.take(200)).distinct
      .foreach(m => println(s"    $m"))
}
spark.conf.unset("spark.sql.codegen.methodSplitThreshold")
spark.conf.unset("spark.sql.adaptive.enabled")
println(s"### Spark ${spark.version}, Java ${System.getProperty("java.version")}")
System.exit(0)
