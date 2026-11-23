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

import scala.reflect.ClassTag
import scala.util.control.NonFatal

import org.scalactic.anyvals.PosInt
import org.scalactic.source.Position
import org.scalatest.Tag

import org.apache.spark.{SparkFunSuite, SparkThrowable}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.ResolveTimeZone
import org.apache.spark.sql.catalyst.expressions.{Expression, ExpressionEvalHelper}

/**
 * Runs each check of a Spark expression suite a second time through the Varka evaluator, after
 * the suite's own has passed (`m8/VARKA-368.md` 2.1): a subclass of the suite that mixes this in
 * passes only if Spark's assertions and Varka's both hold. `VarkaSuiteCheck` does the work; this
 * trait is the Scala surface it needs, the overrides of `ExpressionEvalHelper`'s checks.
 *
 * Every route arrives at three overrides - `checkEvaluation`, the four-argument
 * `checkErrorInExpression` and the three-argument `checkExceptionInExpression`, to which the
 * shorter overloads delegate - and the random properties at `cmpInterpretWithCodegen`, whose
 * Varka arm runs only under `-Dvarka.suites.random=true`. The error overrides are public, as
 * `TryCastSuite`'s are. What a test's checks found is decided at its end, so the suite's own
 * checks all run first.
 */
trait VarkaSuiteArm extends SparkFunSuite with ExpressionEvalHelper {

  /**
   * The random properties' draws per property: ScalaCheck's ten unless
   * `-Dvarka.suites.minSuccessful` raises them, as the nightly does.
   */
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = PosInt.from(VarkaSuiteArm.minSuccessful).get)

  /** The suite's name in the record and the lists: the subclass's. */
  private def varkaSuite: String = getClass.getSimpleName

  override protected def test(testName: String, testTags: Tag*)(testBody: => Any)
      (implicit pos: Position): Unit = {
    // A test SparkFunSuite excludes runs as ignored, and so never reaches its decision.
    if (!excluded.contains(testName)) {
      VarkaSuiteRecord.registerTest(varkaSuite)
    }
    super.test(testName, testTags: _*) {
      val suite = varkaSuite
      VarkaSuiteCheck.beginTest(suite, testName)
      try {
        testBody
      } catch {
        case e: Throwable =>
          VarkaSuiteCheck.endTest(suite, testName, false)
          throw e
      }
      val decision = VarkaSuiteCheck.endTest(suite, testName, true)
      decision.action match {
        case VarkaSuiteCheck.Action.FAIL => fail(decision.message)
        case VarkaSuiteCheck.Action.CANCEL => cancel(decision.message)
        case VarkaSuiteCheck.Action.PASS =>
      }
    }
  }

  override protected def afterAll(): Unit = {
    try {
      val problems = VarkaSuiteRecord.finish(varkaSuite)
      if (problems != null) {
        fail(problems)
      }
    } finally {
      super.afterAll()
    }
  }

  /**
   * The suite's expression, as `prepareEvaluation` prepares it without its serialization, through
   * Varka on `inputRow`: `EmptyRow`, the helpers' default, is null.
   */
  private def varka(
      expression: => Expression,
      inputRow: InternalRow,
      record: Boolean,
      expect: VarkaSuiteCheck.Expect = VarkaSuiteCheck.Expect.VALUE): Unit = {
    val row = Option(inputRow).getOrElse(InternalRow.empty)
    val prepared =
      try Some(replace(ResolveTimeZone.resolveTimeZones(expression))) catch {
        case NonFatal(_) => None
      }
    prepared.filter(_.resolved).foreach { e =>
      VarkaSuiteCheck.check(varkaSuite, e, row, record, expect)
    }
  }

  override protected def checkEvaluation(
      expression: => Expression, expected: Any, inputRow: InternalRow): Unit = {
    super.checkEvaluation(expression, expected, inputRow)
    varka(expression, inputRow, record = true)
  }

  override def checkErrorInExpression[T <: SparkThrowable : ClassTag](
      expression: => Expression,
      inputRow: InternalRow,
      condition: String,
      parameters: Map[String, String]): Unit = {
    super.checkErrorInExpression[T](expression, inputRow, condition, parameters)
    varka(expression, inputRow, record = true, new VarkaSuiteCheck.Expect(condition, null))
  }

  override def checkExceptionInExpression[T <: Throwable : ClassTag](
      expression: => Expression,
      inputRow: InternalRow,
      expectedErrMsg: String): Unit = {
    super.checkExceptionInExpression[T](expression, inputRow, expectedErrMsg)
    varka(expression, inputRow, record = true, new VarkaSuiteCheck.Expect(null, expectedErrMsg))
  }

  override def cmpInterpretWithCodegen(
      inputRow: InternalRow, expr: Expression, exceptionAllowed: Boolean): Unit = {
    super.cmpInterpretWithCodegen(inputRow, expr, exceptionAllowed)
    if (VarkaSuiteArm.random) {
      varka(expr, inputRow, record = false)
    }
  }
}

object VarkaSuiteArm {
  /** Whether the random properties' checks run through Varka too: the nightly's run. */
  val random: Boolean = sys.props.get("varka.suites.random").contains("true")

  /** The random properties' draws per property, ScalaCheck's ten by default. */
  val minSuccessful: Int = sys.props.get("varka.suites.minSuccessful").map(_.toInt).getOrElse(10)
}
