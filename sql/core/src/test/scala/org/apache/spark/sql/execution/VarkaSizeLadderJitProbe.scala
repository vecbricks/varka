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

package org.apache.spark.sql.execution

import org.apache.spark.internal.config.UI.UI_ENABLED
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator

/**
 * The child process behind [[VarkaSizeLadderJitSuite]] (task 171). Launched in a forked JVM under
 * `-Xbatch -XX:+PrintCompilation`, it runs one vanilla-Spark projection of `n` size-ladder
 * entries - `greatest(add_months(d, k), date_add(d, k), last_day(d))` - over a generated date
 * column, with whole-stage codegen on and Varka off, and prints the stage's largest generated
 * method - the projection's consume function - before it and a marker after it. What HotSpot
 * prints in between says whether that method was compiled.
 *
 * Exactly one query runs, so the one generated stage class is the only one named
 * `GeneratedIteratorForCodegenStage1`: every query numbers its stages from one, and a second
 * query would make the name ambiguous. The rows are enough for the consume method to earn a C2
 * compile many times over; `-Xbatch` finishes each compile before
 * the call that earned it continues, so by the marker every compile has been printed.
 */
object VarkaSizeLadderJitProbe {

  val BYTES_PREFIX = "VARKA_LADDER_BYTES="
  val DONE = "VARKA_LADDER_DONE"

  private val rows = 300000L

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: VarkaSizeLadderJitProbe <entries>")
    val n = args(0).toInt
    val spark = SparkSession.builder().master("local[1]").appName("ladder-jit")
      .config(UI_ENABLED.key, false)
      .getOrCreate()
    try {
      val entries = (1 to n).map(k =>
        s"greatest(add_months(d, $k), date_add(d, $k), last_day(d)) AS c$k")
      val df = spark.range(0, rows)
        .selectExpr("date_add(date'2020-01-01', cast(id % 1460 as int)) AS d")
        .selectExpr(entries: _*)
      val bytes = df.queryExecution.executedPlan.collect {
        case w: WholeStageCodegenExec => CodeGenerator.compile(w.doCodeGen()._2)._2
      }.map(_.maxMethodCodeSize).max
      // scalastyle:off println
      println(BYTES_PREFIX + bytes)
      df.write.format("noop").mode("overwrite").save()
      println(DONE)
      // scalastyle:on println
    } finally {
      spark.stop()
    }
  }
}
