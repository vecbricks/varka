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

import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.config.Configurator

import org.apache.spark.sql.{SaveMode, SparkSession}
import org.apache.spark.sql.execution.{SparkPlan, SQLExecution, VarkaColumnarToRowExec}
import org.apache.spark.sql.internal.SQLConf

/**
 * The queries, sinks and switches of [[VarkaColdPathBenchmark]], shared with
 * [[VarkaColdPathProbe]], which runs one of the benchmark's arms in a loop for a profiler. A
 * plain object, not the benchmark's, for the reason [[VarkaArrowSessions]] gives.
 */
object VarkaColdPath {

  /**
   * The offset family of the steady-state query, which every arm of the benchmark shares and
   * the probe runs.
   */
  val steady = 30

  /**
   * The rung's query at an offset family's iteration, as [[VarkaColdStartBenchmark]] builds it:
   * a hundred months between iterations, so no two share a query, and every executed offset
   * within the kernel's month bound.
   */
  def query(n: Int, iteration: Int): String = {
    val base = 100 * (iteration + 1)
    s"SELECT ${(1 to n).map(k => VarkaSizeLadder.entry(base + k)).mkString(", ")} " +
      "FROM ladder_dates"
  }

  /**
   * Every row consumed as a row: Varka plans `VarkaColumnarToRowExec`. Inside an SQL execution
   * of its own, as a Dataset action runs and as the noop write runs: a query run outside one
   * gets another class loader, and the loader is part of the shape cache's key, so the two
   * sinks would warm two kernels for one shape.
   */
  def toRows(session: SparkSession, q: String): Unit = rowsPlan(session, q)

  /** Runs `q` as [[toRows]] does and returns the executed plan, its metrics filled in. */
  def rowsPlan(session: SparkSession, q: String): SparkPlan = {
    val qe = session.sql(q).queryExecution
    SQLExecution.withNewExecutionId(qe, Some("rows"))(qe.toRdd.count())
    qe.executedPlan
  }

  /** Into the noop sink, which takes batches: Varka plans `VarkaProjectExec`. */
  def toNoop(session: SparkSession, q: String): Unit =
    session.sql(q).write.format("noop").mode(SaveMode.Overwrite).save()

  /**
   * Runs `body` with the Varka node unable to obtain a kernel, so that every batch takes its
   * row path and no warm-up starts.
   */
  def noKernel[T](body: => T): T = {
    VarkaColumnarToRowExec.setFailEmissionForTesting(true)
    try body finally VarkaColumnarToRowExec.setFailEmissionForTesting(false)
  }

  /** Runs `body` with whole-stage codegen off in `session`. */
  def rowByRow[T](session: SparkSession)(body: => T): T = {
    session.conf.set(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key, false)
    try body finally session.conf.unset(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key)
  }

  /**
   * Turns the evaluator's warnings off and returns what turns them back on. [[noKernel]]'s
   * emissions fail on purpose, and each task would log the failure with its stack trace: a cost
   * of the injection, not of the path. Callers read the nodes' metrics instead.
   */
  def silenceEvaluator(): () => Unit = {
    val logger = "org.apache.spark.sql.execution.VarkaKernelEvaluator"
    val level = LogManager.getLogger(logger).getLevel
    Configurator.setLevel(logger, Level.ERROR)
    () => Configurator.setLevel(logger, level)
  }
}
