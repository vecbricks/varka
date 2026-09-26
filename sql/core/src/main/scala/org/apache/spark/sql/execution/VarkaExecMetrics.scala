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

import org.apache.spark.{SparkContext}
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}

/**
 * The Varka-specific SQL metrics one exec node threads to its factory and evaluator,
 * bundled so the parameter lists stop growing metric by metric (an earlier signature threaded two
 * options; this one would have made it five). Every field is optional: suites and diagnostics
 * construct evaluators with none. Deliberately Scala rather than a Java record (the task-21
 * review's call, recorded): every construction site is forced-Scala code leaning on named
 * arguments and defaults over a list of same-typed fields, where a record's positional constructor
 * would be a silent-swap hazard.
 */
private[sql] case class VarkaExecMetrics(
    varkaBatches: Option[SQLMetric] = None,
    cacheHits: Option[SQLMetric] = None,
    cacheMisses: Option[SQLMetric] = None,
    fallbackBatchesNonArrow: Option[SQLMetric] = None,
    fallbackBatchesKernel: Option[SQLMetric] = None,
    fallbackBatchesRowPath: Option[SQLMetric] = None,
    fallbackBatchesDeclined: Option[SQLMetric] = None,
    emissionFailures: Option[SQLMetric] = None,
    suspectAllocationSamples: Option[SQLMetric] = None,
    warmupBatches: Option[SQLMetric] = None)

private[sql] object VarkaExecMetrics {

  /**
   * The metric set every Varka node registers, defined once (task-21 review: the four nodes
   * carried byte-identical copies, where a changed key or description would compile clean and
   * fork the UI vocabularies). `numOutputRows` semantics stay per node: a projection counts
   * input rows, a filter counts selected rows.
   */
  def nodeMetrics(sparkContext: SparkContext): Map[String, SQLMetric] = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"),
    "numInputBatches" -> SQLMetrics.createMetric(sparkContext, "number of input batches"),
    "numVarkaBatches" -> SQLMetrics.createMetric(
      sparkContext, "number of input batches processed by the Varka SIMD kernels"),
    "numVarkaCacheHits" -> SQLMetrics.createMetric(
      sparkContext, "number of tasks served a kernel class by the Varka shape cache"),
    "numVarkaCacheMisses" -> SQLMetrics.createMetric(
      sparkContext, "number of tasks that emitted and defined the Varka kernel class"),
    "numFallbackBatchesNonArrow" -> SQLMetrics.createMetric(
      sparkContext, "batches falling back: input not Arrow-backed"),
    "numFallbackBatchesKernel" -> SQLMetrics.createMetric(
      sparkContext, "batches falling back: kernel failure (the ghost fallback)"),
    "numFallbackBatchesRowPath" -> SQLMetrics.createMetric(
      sparkContext, "batches falling back: per-row machinery failure beside the kernel"),
    "numFallbackBatchesDeclined" -> SQLMetrics.createMetric(
      sparkContext, "batches falling back: a value outside a lowering's range"),
    "numEmissionFailures" -> SQLMetrics.createMetric(
      sparkContext, "tasks that could not emit or define the kernel class"),
    "numSuspectAllocationSamples" -> SQLMetrics.createMetric(
      sparkContext, "sampled kernel batches that allocated like a boxing Vector API loop"),
    "numWarmupBatches" -> SQLMetrics.createMetric(
      sparkContext, "batches on the per-row path while a new kernel was compiled"))

  /** [[nodeMetrics]] plus the projection nodes' static residual-entry count; a filter's
   * residual is a visible row `FilterExec` above it rather than a number. */
  def projectionMetrics(sparkContext: SparkContext): Map[String, SQLMetric] =
    nodeMetrics(sparkContext) + ("numResidualEntries" -> SQLMetrics.createMetric(
      sparkContext, "projection entries declined to the per-row residual (reasons in EXPLAIN)"))

  /** The evaluator-facing bundle built from a node's registered metrics. */
  def fromNode(metric: String => SQLMetric): VarkaExecMetrics = VarkaExecMetrics(
    varkaBatches = Some(metric("numVarkaBatches")),
    cacheHits = Some(metric("numVarkaCacheHits")),
    cacheMisses = Some(metric("numVarkaCacheMisses")),
    fallbackBatchesNonArrow = Some(metric("numFallbackBatchesNonArrow")),
    fallbackBatchesKernel = Some(metric("numFallbackBatchesKernel")),
    fallbackBatchesRowPath = Some(metric("numFallbackBatchesRowPath")),
    fallbackBatchesDeclined = Some(metric("numFallbackBatchesDeclined")),
    emissionFailures = Some(metric("numEmissionFailures")),
    suspectAllocationSamples = Some(metric("numSuspectAllocationSamples")),
    warmupBatches = Some(metric("numWarmupBatches")))
}
