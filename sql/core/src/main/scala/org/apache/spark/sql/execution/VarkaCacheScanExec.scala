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

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, SortOrder}
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.columnar.{ArrowCachedBatchSerializer, InMemoryTableScanExec}
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * The in-memory cache read as columnar batches where Spark reads it as rows only because of its
 * width (task 185, the census's G3). `InMemoryTableScanExec.supportsColumnar` is false when the
 * cached relation has more than `spark.sql.codegen.maxFields` fields, counted over the whole
 * cached schema rather than the columns a query reads. That limit protects whole-stage codegen,
 * which consumes the batches in generated code; a Varka node consumes them in its kernels instead,
 * and under Varka's Arrow serializer the batches exist at any width.
 *
 * So this node wraps the scan and asks it for its columnar output directly: the scan's own
 * `executeColumnar()`, with its partition pruning, its serializer conversion of the attributes it
 * reads, and its metrics, of which this node reports the same objects. Nothing of the scan is
 * reimplemented here. [[VarkaColumnarRule]] places it only beneath a Varka projection or filter,
 * and only where the scan is kept from columnar output by the field count alone - see
 * [[VarkaCacheScanExec.widens]] - so a plan Varka does not fuse keeps the scan as it was.
 */
case class VarkaCacheScanExec(scan: InMemoryTableScanExec) extends LeafExecNode {

  override def output: Seq[Attribute] = scan.output

  override def nodeName: String = "VarkaCacheScan"

  override def simpleString(maxFields: Int): String =
    s"$nodeName ${scan.simpleString(maxFields)}"

  override lazy val metrics: Map[String, SQLMetric] = scan.metrics

  override def outputPartitioning: Partitioning = scan.outputPartitioning

  override def outputOrdering: Seq[SortOrder] = scan.outputOrdering

  override def vectorTypes: Option[Seq[String]] = scan.vectorTypes

  override def supportsColumnar: Boolean = true

  override protected def doExecuteColumnar(): RDD[ColumnarBatch] = scan.executeColumnar()

  override protected def doExecute(): RDD[InternalRow] = scan.execute()

  override def doCanonicalize(): SparkPlan =
    copy(scan = scan.canonicalized.asInstanceOf[InMemoryTableScanExec])
}

object VarkaCacheScanExec {

  /**
   * `plan` as a columnar source for a Varka node, if it is a cache scan kept from columnar output
   * by its width alone: the cache is Varka's Arrow serializer, which produces batches for this
   * schema, the vectorized cache reader is on, and still the scan reads rows. Any other reason
   * the scan is not columnar is left as it is.
   */
  def widens(plan: SparkPlan): Option[VarkaCacheScanExec] = plan match {
    case scan: InMemoryTableScanExec
        if !scan.supportsColumnar && scan.conf.cacheVectorizedReaderEnabled =>
      val serializer = scan.relation.cacheBuilder.serializer
      if (serializer.isInstanceOf[ArrowCachedBatchSerializer] &&
          serializer.supportsColumnarOutput(scan.relation.schema)) {
        Some(VarkaCacheScanExec(scan))
      } else {
        None
      }
    case _ => None
  }
}
