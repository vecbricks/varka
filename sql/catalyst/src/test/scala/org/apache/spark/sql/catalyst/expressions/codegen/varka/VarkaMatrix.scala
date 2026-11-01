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

import org.scalatest.exceptions.TestCanceledException

/**
 * The option configuration matrix's ScalaTest face (VARKA-248, VARKA-312): [[VarkaMatrixRuns]],
 * the Java class that holds the matrix's logic, with Scala types and ScalaTest's way of
 * cancelling and failing a test. What each member means is documented there; nothing is decided
 * here.
 */
object VarkaMatrix {

  /** [[VarkaMatrixRuns.PROPERTY]]. */
  val PROPERTY: String = VarkaMatrixRuns.PROPERTY

  /** [[VarkaMatrixRuns.REPORT_PROPERTY]]. */
  val REPORT_PROPERTY: String = VarkaMatrixRuns.REPORT_PROPERTY

  /** [[VarkaMatrixRuns.CONFIG]]: this JVM's configuration, `""` for the defaults. */
  val config: String = VarkaMatrixRuns.CONFIG

  /** [[VarkaMatrixRuns.ARROW_BATCH_SIZE]]. */
  final val ARROW_BATCH_SIZE = "arrowBatchSize"

  /** [[VarkaMatrixRuns.ARROW_BATCH_SIZES]]. */
  val ARROW_BATCH_SIZES: Seq[Int] = VarkaMatrixRuns.ARROW_BATCH_SIZES.asScala.map(_.intValue).toSeq

  /** [[VarkaMatrixRuns.ARROW_BATCH_SIZE_ROWS]]. */
  val arrowBatchSize: Option[Int] = VarkaMatrixRuns.ARROW_BATCH_SIZE_ROWS.toScala

  /** [[VarkaMatrixRuns.BASE]]: the options every test starts from. */
  val base: VarkaEmitOptions = VarkaMatrixRuns.BASE

  /** [[VarkaMatrixRuns.parse]]. */
  def parse(spec: String): VarkaEmitOptions = VarkaMatrixRuns.parse(spec)

  /** [[VarkaMatrixRuns.configurations]]. */
  def configurations: Seq[String] = VarkaMatrixRuns.configurations.asScala.toSeq

  /** The tag of a test whose subject is the defaults' emitted structure ([[VarkaMatrixRuns]]). */
  object PinsDefaults extends org.scalatest.Tag(VarkaMatrixRuns.PINS_DEFAULTS_TAG)

  /** The tag of a test whose subject is C2's code ([[VarkaMatrixRuns.C2_CODE_TAG]]). */
  object C2Code extends org.scalatest.Tag(VarkaMatrixRuns.C2_CODE_TAG)

  /** [[VarkaMatrixRuns.Kind]]. */
  type Kind = VarkaMatrixRuns.Kind
  object Kind {
    val FAILS: Kind = VarkaMatrixRuns.Kind.FAILS
    val DECLINES: Kind = VarkaMatrixRuns.Kind.DECLINES
  }

  /** [[VarkaMatrixRuns.Skip]]: one line of the skip list. */
  type Skip = VarkaMatrixRuns.Skip
  object Skip {
    def apply(config: String, suite: String, test: String, kind: Kind, reason: String): Skip =
      new VarkaMatrixRuns.Skip(config, suite, test, kind, reason)
  }

  /** [[VarkaMatrixRuns.SKIPS_PATH]]. */
  val SKIPS_PATH: String = VarkaMatrixRuns.SKIPS_PATH

  /** [[VarkaMatrixRuns.skips]]: the committed skip list. */
  def skips: Seq[Skip] = VarkaMatrixRuns.skips.asScala.toSeq

  private[varka] def parseSkips(text: String): Seq[Skip] =
    VarkaMatrixRuns.parseSkips(text).asScala.toSeq

  /**
   * [[VarkaMatrixRuns.fusedBatches]]: batches the Varka kernels have processed in this JVM so
   * far, or -1 where nothing counts them. The SQL suites' sessions set it.
   */
  def fusedBatches: () => Long = {
    val counter = VarkaMatrixRuns.fusedBatches
    () => counter.getAsLong
  }

  def fusedBatches_=(counter: () => Long): Unit =
    VarkaMatrixRuns.setFusedBatches(() => counter())

  /** ScalaTest's cancel and fail, and its cancel exception, which passes through. */
  private val outcomes = new VarkaMatrixRuns.Outcomes(
    (message: String) => new TestCanceledException(message, 0),
    (message: String) => new org.scalatest.exceptions.TestFailedException(message, 0),
    classOf[TestCanceledException])

  /** [[VarkaMatrixRuns.around]]: runs one test under the matrix. */
  def around[T](suite: String, test: String)(body: => T): T =
    VarkaMatrixRuns.around(suite, test, outcomes, () => body)

  private[varka] def around[T](
      entries: Seq[Skip],
      config: String,
      report: Option[String],
      suite: String,
      test: String)(body: => T): T =
    VarkaMatrixRuns.around(entries.asJava, config, report.toJava, suite, test, outcomes,
      () => body)
}

/** Prints the matrix's configurations, one per line, for `dev/varka_matrix.sh`. */
object VarkaMatrixMain {
  def main(args: Array[String]): Unit = {
    // scalastyle:off println
    VarkaMatrix.configurations.foreach(println)
    // scalastyle:on println
  }
}
