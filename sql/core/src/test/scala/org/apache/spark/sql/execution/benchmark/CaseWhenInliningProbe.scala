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

package org.apache.spark.sql.execution.benchmark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.internal.SQLConf

/**
 * The 64-branch rung of [[CaseWhenCodegenBenchmark]] run until C2 has compiled its stage, under
 * one split mode, for the JIT's own account of what the stage's `processNext` inlines
 * (VARKA-288.md 3.2). At 64 branches the unsplit stage's method is under 8000 bytes, so the split
 * as needed leaves it whole, and split always moves each branch into a method of its own.
 *
 * {{{
 *   java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining -cp <sql test classpath> \
 *     org.apache.spark.sql.execution.benchmark.CaseWhenInliningProbe always|asNeeded
 * }}}
 */
object CaseWhenInliningProbe {

  private val rows = 2L * 1000 * 1000
  private val branches = 64

  def main(args: Array[String]): Unit = {
    val mode = args.headOption.getOrElse("asNeeded")
    val split = mode match {
      case "always" => Seq(SQLConf.WHOLESTAGE_SPLIT_EXPRESSIONS.key -> "true",
        SQLConf.WHOLESTAGE_SPLIT_EXPRESSIONS_METHOD_LIMIT.key -> "0")
      case "asNeeded" => Seq(SQLConf.WHOLESTAGE_SPLIT_EXPRESSIONS.key -> "true")
      case other => throw new IllegalArgumentException(s"mode $other: always or asNeeded")
    }
    val builder = SparkSession.builder().master("local[1]").appName("CaseWhenInliningProbe")
      .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
      .config("spark.ui.enabled", "false")
      // The emitter's default level, not the host's: see TestSQLContext.overrideConfs.
      .config(SQLConf.VARKA_EMIT_USE_AVX.key, "-1")
    val spark = split.foldLeft(builder) { case (b, (k, v)) => b.config(k, v) }.getOrCreate()
    try {
      val whens = (1 to branches).map(k => s"WHEN v = $k THEN v * $k").mkString(" ")
      val df = spark.sql(
        s"SELECT CASE $whens ELSE 0 END AS c FROM (SELECT id % $branches AS v FROM range($rows))")
      // Twenty runs of two million rows: C2 compiles the stage's loop well before the last.
      for (_ <- 1 to 20) df.write.format("noop").mode("overwrite").save()
    } finally {
      spark.stop()
    }
  }
}
