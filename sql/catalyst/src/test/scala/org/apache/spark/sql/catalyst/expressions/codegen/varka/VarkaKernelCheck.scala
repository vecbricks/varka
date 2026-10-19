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

package org.apache.spark.sql.catalyst.expressions.codegen.varka

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/**
 * The kernel check's Scala face: [[VarkaKernelChecks]], the Java class that runs an emitted kernel
 * over a batch and holds every row and validity bit to the reference evaluator (VARKA-238,
 * VARKA-311), over the Scala batches its suites build. Each method converts the batch's null
 * patterns to flags and adapts a [[VarkaSparkOracle]]; nothing is checked here.
 */
object VarkaKernelCheck {

  /**
   * One batch's columns: `length` rows, each column's null pattern and drawn values, and whether
   * the masked body is forced by reporting a null over a full bitmap.
   */
  case class Batch(length: Int, patterns: Seq[Int => Boolean], data: Array[Array[Int]],
      forceMasked: Boolean) {
    private[varka] def toJava: VarkaKernelChecks.Batch =
      new VarkaKernelChecks.Batch(length, flags(patterns, length), data, forceMasked)
  }

  /** [[Batch]] at the long lane: the same, with 64-bit column values. */
  case class LongBatch(length: Int, patterns: Seq[Int => Boolean], data: Array[Array[Long]],
      forceMasked: Boolean) {
    private[varka] def toJava: VarkaKernelChecks.LongBatch =
      new VarkaKernelChecks.LongBatch(length, flags(patterns, length), data, forceMasked)
  }

  private def flags(patterns: Seq[Int => Boolean], length: Int): Array[Array[Boolean]] =
    patterns.map(p => Array.tabulate(length)(p)).toArray

  /** [[VarkaKernelChecks.ValidityFill.BOTH]]: production's zero fill, then ones. */
  val bothFills: Seq[VarkaKernelChecks.ValidityFill] =
    VarkaKernelChecks.ValidityFill.BOTH.asScala.toSeq

  /** Answers the reference evaluator has been held to Spark on, so a suite can see it was. */
  val sparkComparisons: java.util.concurrent.atomic.AtomicLong =
    VarkaKernelChecks.SPARK_COMPARISONS

  /** `spark`'s answers as the Java check reads them. */
  private def answers(spark: Option[VarkaSparkOracle]): VarkaKernelChecks.SparkAnswers =
    spark.map { s =>
      new VarkaKernelChecks.SparkAnswers {
        override def answer(output: Int, values: Array[Long], isNull: Array[Boolean])
            : VarkaKernelChecks.Answer = {
          val row = values.indices.map(c => if (isNull(c)) None else Some(values(c)))
          s.answer(output, row) match {
            case VarkaSparkOracle.Value(v) => new VarkaKernelChecks.Answer.Value(v)
            case VarkaSparkOracle.Null => new VarkaKernelChecks.Answer.Null()
            case VarkaSparkOracle.Raised(e) => new VarkaKernelChecks.Answer.Raised(e)
          }
        }
        override def sql(output: Int): String = s.sql(output)
      }
    }.orNull

  /** [[VarkaKernelChecks.runAndCompare]]. */
  def runAndCompare(context: String, className: String, bytes: Array[Byte],
      roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Int], batch: Batch,
      declineAllowed: Boolean = false, spark: Option[VarkaSparkOracle] = None): Boolean =
    VarkaKernelChecks.runAndCompare(context, className, bytes, roots.asJava, numInputs, lits,
      batch.toJava, declineAllowed, answers(spark))

  /** [[VarkaKernelChecks.runOwnBody]]: both fills unless `fills` says otherwise. */
  def runOwnBody(context: String, className: String, bytes: Array[Byte],
      roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Int], batch: Batch,
      declineAllowed: Boolean = false, spark: Option[VarkaSparkOracle] = None,
      fills: Seq[VarkaKernelChecks.ValidityFill] = bothFills): Boolean =
    VarkaKernelChecks.runOwnBody(context, className, bytes, roots.asJava, numInputs, lits,
      batch.toJava, declineAllowed, answers(spark), fills.asJava)

  /** [[VarkaKernelChecks.validityBytesWritten]]. */
  def validityBytesWritten(className: String, bytes: Array[Byte], roots: Seq[VarkaVectorIR],
      numInputs: Int, lits: Array[Int], batch: Batch,
      fill: VarkaKernelChecks.ValidityFill): Int =
    VarkaKernelChecks.validityBytesWritten(className, bytes, roots.asJava, numInputs, lits,
      batch.toJava, fill)

  /** [[VarkaKernelChecks.runAndCompareStatus]]. */
  def runAndCompareStatus(context: String, className: String, bytes: Array[Byte],
      roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Int], batch: Batch,
      declineAllowed: Boolean): Int =
    VarkaKernelChecks.runAndCompareStatus(context, className, bytes, roots.asJava, numInputs,
      lits, batch.toJava, declineAllowed)

  /** [[VarkaKernelChecks.runEachBodyStatus]]. */
  def runEachBodyStatus(context: String, className: String, bytes: Array[Byte],
      roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Int], batch: Batch): Seq[Int] =
    VarkaKernelChecks.runEachBodyStatus(context, className, bytes, roots.asJava, numInputs, lits,
      batch.toJava).asScala.map(_.intValue).toSeq

  /** [[VarkaKernelChecks.runEachBodyLongStatus]]. */
  def runEachBodyLongStatus(context: String, className: String, bytes: Array[Byte],
      roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Long], batch: LongBatch): Seq[Int] =
    VarkaKernelChecks.runEachBodyLongStatus(context, className, bytes, roots.asJava, numInputs,
      lits, batch.toJava).asScala.map(_.intValue).toSeq

  /** [[VarkaKernelChecks.runAndCompareLong]]. */
  def runAndCompareLong(context: String, className: String, bytes: Array[Byte],
      roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Long], batch: LongBatch,
      declineAllowed: Boolean = false, spark: Option[VarkaSparkOracle] = None,
      intLits: Array[Int] = Array.empty): Boolean =
    VarkaKernelChecks.runAndCompareLong(context, className, bytes, roots.asJava, numInputs, lits,
      intLits, batch.toJava, declineAllowed, answers(spark))

  /** [[VarkaKernelChecks.runAndCompareLongStatus]]. */
  def runAndCompareLongStatus(context: String, className: String, bytes: Array[Byte],
      roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Long], batch: LongBatch,
      declineAllowed: Boolean): Int =
    VarkaKernelChecks.runAndCompareLongStatus(context, className, bytes, roots.asJava,
      numInputs, lits, batch.toJava, declineAllowed)

  /** [[VarkaKernelChecks.runOwnBodyLong]]. */
  // scalastyle:off argcount
  def runOwnBodyLong(context: String, className: String, bytes: Array[Byte],
      roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Long], batch: LongBatch,
      declineAllowed: Boolean = false, spark: Option[VarkaSparkOracle] = None,
      fills: Seq[VarkaKernelChecks.ValidityFill] = bothFills,
      intLits: Array[Int] = Array.empty): Boolean =
    VarkaKernelChecks.runOwnBodyLong(context, className, bytes, roots.asJava, numInputs, lits,
      intLits, batch.toJava, declineAllowed, answers(spark), fills.asJava)
  // scalastyle:on argcount

  /** [[VarkaKernelChecks.validityBytesWrittenLong]]. */
  def validityBytesWrittenLong(className: String, bytes: Array[Byte],
      roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Long], batch: LongBatch,
      fill: VarkaKernelChecks.ValidityFill, intLits: Array[Int] = Array.empty): Int =
    VarkaKernelChecks.validityBytesWrittenLong(className, bytes, roots.asJava, numInputs, lits,
      intLits, batch.toJava, fill)

  /** [[VarkaKernelChecks.otherBody]], with the null flags as patterns. */
  private[varka] def otherBody(numInputs: Int, length: Int, patterns: Seq[Int => Boolean],
      forceMasked: Boolean): Option[(Seq[Int => Boolean], Boolean)] =
    VarkaKernelChecks.otherBody(numInputs, length, flags(patterns, length), forceMasked).toScala
      .map(b => (b.nulls().toSeq.map(column => (i: Int) => column(i)), b.forceMasked()))
}
