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

/**
 * One arm of [[VarkaColdPathBenchmark]] in a loop, for a profiler or the JIT's own logs to
 * watch: the rung's steady-state query into a sink, by one path, for a number of seconds.
 *
 * {{{
 *   build/sbt "project sql" \
 *     'set Test/javaOptions += "-XX:StartFlightRecording=filename=arm.jfr,settings=profile"' \
 *     "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaColdPathProbe \
 *       columnar varka 16 30"
 * }}}
 *
 * The sink is `rows` or `columnar`, as in the benchmark. The arm is `vanilla` (whole-stage
 * code), `vanilla-rowwise` (whole-stage codegen off) or `varka` (the Varka node with no kernel,
 * so every batch takes its row path). The data is the benchmark's, a hundred thousand
 * Arrow-cached rows. The last line of output gives the number of queries and their mean time, so
 * that a profile can be matched to the benchmark's steady state.
 */
object VarkaColdPathProbe {
  import VarkaColdPath._

  def main(args: Array[String]): Unit = {
    require(args.length == 4, "usage: VarkaColdPathProbe <rows|columnar> " +
      "<vanilla|vanilla-rowwise|varka> <entries> <seconds>")
    val Array(sink, arm, entries, seconds) = args
    require(Set("vanilla", "vanilla-rowwise", "varka").contains(arm), s"unknown arm $arm")
    val session = VarkaArrowSessions.createSession(s"VarkaColdPathProbe-$arm",
      varkaEnabled = arm == "varka")
    try {
      VarkaSizeLadder.cacheDates(session, 100000)
      val q = query(entries.toInt, steady)
      val toSink: () => Unit = sink match {
        case "rows" => () => toRows(session, q)
        case "columnar" => () => toNoop(session, q)
        case other => throw new IllegalArgumentException(s"unknown sink $other")
      }
      val once: () => Unit = arm match {
        case "vanilla" => toSink
        case "vanilla-rowwise" => () => rowByRow(session)(toSink())
        case _ => () => noKernel(toSink())
      }
      val restoreEvaluator = silenceEvaluator()
      try {
        val start = System.nanoTime()
        val deadline = start + seconds.toLong * 1000000000L
        var queries = 0
        while (System.nanoTime() < deadline) {
          once()
          queries += 1
        }
        val meanMs = (System.nanoTime() - start) / 1e6 / queries
        // scalastyle:off println
        println(f"VARKA_COLDPATH_PROBE sink=$sink arm=$arm entries=$entries queries=$queries " +
          f"mean=$meanMs%.1f ms")
        // scalastyle:on println
      } finally {
        restoreEvaluator()
      }
    } finally {
      session.stop()
    }
  }
}
