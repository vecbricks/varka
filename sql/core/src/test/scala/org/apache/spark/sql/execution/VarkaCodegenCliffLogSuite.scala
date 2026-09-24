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

import org.apache.logging.log4j.Level

import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

/**
 * What vanilla Spark says when a whole-stage codegen method passes HotSpot's 8000-byte JIT
 * limit: a reproducer for the census's entry G26 (`PLAN_TASK_188.md`). Spark computes every
 * generated method's bytecode size when it compiles a class and logs "Generated method too long
 * to be JIT compiled: <class>.<method> is N bytes" for a method past the limit - at INFO, which
 * the shells' WARN level hides, and nothing else acts on it at the default settings. This pins
 * that the line is there, for the size ladder's projection past the limit and not below it, so
 * the claim the milestone's post makes about it rests on a test rather than on one run's log.
 */
class VarkaCodegenCliffLogSuite extends QueryTest with SharedSparkSession {

  /**
   * The size ladder's projection at `n` entries over generated dates. The offsets are this
   * suite's own, so no other test's compiled class can be served from the cache and skip the
   * compile that logs the line.
   */
  private def ladder(n: Int) = spark.range(0, 1000)
    .selectExpr("date_add(date'2020-01-01', cast(id % 1460 as int)) AS d")
    .selectExpr((1 to n).map(k => s"greatest(add_months(d, ${k + 7000}), " +
      s"date_add(d, ${k + 7000}), last_day(d)) AS c$k"): _*)

  /** The JIT-limit lines Spark logs while running the projection at `n` entries. */
  private def jitLines(n: Int): Seq[(Level, String)] = {
    val appender = new LogAppender("the JIT limit line")
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false") {
      withLogAppender(appender, loggerNames = Seq(classOf[CodeGenerator[_, _]].getName),
          Some(Level.INFO)) {
        ladder(n).write.format("noop").mode("overwrite").save()
      }
    }
    appender.loggingEvents.toSeq
      .map(e => (e.getLevel, e.getMessage.getFormattedMessage))
      .filter(_._2.contains("Generated method too long to be JIT compiled"))
  }

  test("past 8000 bytes, Spark logs that the consume method will not be JIT-compiled") {
    val lines = jitLines(56)
    assert(lines.nonEmpty, "no line for a projection past the limit")
    // INFO today; SPARK-59774 proposes WARN.
    assert(lines.forall { case (level, _) => level == Level.INFO || level == Level.WARN }, lines)
    val sizes = lines.collect { case (_, m) if m.contains("project_doConsume") =>
      """is (\d+) bytes""".r.findFirstMatchIn(m).map(_.group(1).toInt)
    }.flatten
    assert(sizes.nonEmpty && sizes.forall(_ > CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT), lines)
  }

  test("below 8000 bytes there is no such line") {
    assert(jitLines(48).isEmpty)
  }
}
