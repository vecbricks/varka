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

import scala.jdk.CollectionConverters._
import scala.reflect.{classTag, ClassTag}
import scala.util.{Failure, Success, Try}

import org.apache.spark.SparkThrowable
import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.util.Utils

/**
 * Errors compared as answers (VARKA-317): the differential check for a query Spark may raise on,
 * after Comet's `checkSparkAnswerMaybeThrows`. The row engine and Varka must both answer the same
 * rows, or both raise the same error; a kernel that answers a row Spark raises on, or raises
 * where Spark answers, or raises another error, fails it. A double `intercept` sees only that an
 * engine did not raise; this names which one, and what the other did instead.
 *
 * The suites mix in the trait for `checkSparkAnswerMaybeThrows`, which runs a query on `spark`
 * and `varkaSpark`; the outcome itself lives in the companion object, so that a suite that runs
 * its own pairs - the coverage differential's ANSI form - compares them the same way.
 */
trait VarkaMaybeThrows extends QueryTest with VarkaSharedSessions {
  import VarkaMaybeThrows._

  /** The outcome of `query` on `session`, and the plan it ran with unless it raised first. */
  protected def runOutcome(session: SparkSession, query: String): (Outcome, Option[SparkPlan]) =
    VarkaMaybeThrows.run(session, query)

  protected def outcome(session: SparkSession, query: String): Outcome =
    runOutcome(session, query)._1

  /**
   * The row engine's outcome for `query`, over a cached table: its plan checked by
   * [[sameCacheReads]] against `varkaPlan`, the plan Varka ran the same query with, when both got
   * as far as a plan.
   */
  protected def expectedOutcome(query: String, varkaPlan: Option[SparkPlan]): Outcome = {
    val (onSpark, sparkPlan) = runOutcome(spark, query)
    for (expected <- sparkPlan; actual <- varkaPlan) {
      sameCacheReads(expected, actual, query, mustRead = true)
    }
    onSpark
  }

  /**
   * Runs `query` on the row engine and on Varka, fails the test with how Varka's outcome differs
   * from Spark's, and returns the outcome they share - rows or one error - so that the caller
   * can still say which it expected. Both plans must read the same cached tables
   * ([[sameCacheReads]]); beyond that it compares outcomes only: whether a kernel served the
   * query is the caller's to assert, since a comparison no kernel took part in holds Spark to
   * itself.
   */
  protected def checkSparkAnswerMaybeThrows(query: String): Outcome = {
    val (onVarka, varkaPlan) = runOutcome(varkaSpark, query)
    val (onSpark, sparkPlan) = runOutcome(spark, query)
    for (expected <- sparkPlan; actual <- varkaPlan) sameCacheReads(expected, actual, query)
    difference(onSpark, onVarka).foreach(d => fail(s"$query\n$d"))
    onSpark
  }

  /**
   * As [[checkSparkAnswerMaybeThrows]], where both engines must raise a `T`, as a double
   * `intercept[T]` asks: the shared error. `T` is named at the call: left to inference it is
   * `Nothing`, which no error is.
   */
  protected def checkSparkRaisesTheSame[T <: Throwable : ClassTag](query: String): Raised = {
    val expected = classTag[T].runtimeClass
    assert(expected != classOf[scala.runtime.Nothing$], "name the exception class T")
    checkSparkAnswerMaybeThrows(query) match {
      case r: Raised =>
        val actual = Utils.classForName[Throwable](r.exceptionClass, initialize = false)
        assert(expected.isAssignableFrom(actual),
          s"$query\nexpected both engines to raise a ${expected.getName}, both raised $r")
        r
      case a: Answered => fail(s"$query\nexpected both engines to raise, both answered $a")
    }
  }
}

object VarkaMaybeThrows {

  /** What a query did: the rows it answered, or the error it raised. */
  sealed trait Outcome

  case class Answered(rows: Seq[Row]) extends Outcome

  /**
   * An error as `checkError` sees one - condition, SQLSTATE and message parameters - with the
   * message and the class of the exception that carried them, which a user sees too. An error
   * with no condition anywhere on its cause chain is kept by its root cause's class and message.
   */
  case class Raised(
      condition: String,
      sqlState: String,
      parameters: Map[String, String],
      message: String,
      exceptionClass: String) extends Outcome

  /**
   * The outcome of `query` on `session`, and the plan it ran with unless it raised before
   * running. Analysis, planning and execution are all inside, so an error raised at any stage
   * is an outcome on either engine. An error is the first `SparkThrowable` with a condition on
   * its cause chain: a task's error reaches the driver wrapped in the job's failure. An error
   * with no condition anywhere is kept by its root cause, not the job failure, whose text names
   * stage and task numbers that differ between any two runs.
   */
  def run(session: SparkSession, query: String): (Outcome, Option[SparkPlan]) = {
    var plan: Option[SparkPlan] = None
    val result = Try {
      val df = session.sql(query)
      plan = Some(df.queryExecution.executedPlan)
      df.collect().toSeq
    }
    val out = result match {
      case Success(rows) => Answered(rows)
      case Failure(e) => raised(e)
    }
    (out, plan)
  }

  /** `e` as an outcome, as [[run]] reads a failure. */
  def raised(e: Throwable): Raised = {
    val chain = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).toSeq
    chain.collectFirst { case t: SparkThrowable if t.getCondition != null =>
      Raised(t.getCondition, t.getSqlState, t.getMessageParameters.asScala.toMap,
        t.asInstanceOf[Throwable].getMessage, t.getClass.getName)
    }.getOrElse {
      val root = chain.last
      // The message as it is, null included: the JVM's preallocated ArithmeticException has
      // none, and sameError tells that case apart only by a real null.
      Raised(root.getClass.getName, null, Map.empty, root.getMessage, root.getClass.getName)
    }
  }

  /**
   * How Varka's outcome differs from Spark's, or nothing. Rows are compared as rows, in any
   * order; errors field by field.
   */
  /**
   * Whether two errors are one, allowing for the JVM's preallocated `ArithmeticException`. Once
   * a `Math.*Exact` overflow has trapped often enough at one call site, C2 throws a shared
   * instance with no message there (`inline_math_mathExact`, under the default
   * `OmitStackTraceInFastThrow`), so the row engine's own overflow reads "long overflow" in a cold
   * JVM and null in a hot one. Which engine meets which depends on what ran before, not on Varka,
   * so a missing message on an otherwise equal `ArithmeticException` is no difference; any other
   * field, or a message both carry, still has to agree. What this can hide is two conditionless
   * `ArithmeticException`s of different causes where the JVM erased one's message; Spark gives
   * its other arithmetic errors a condition, such as `DIVIDE_BY_ZERO`, and those stay compared
   * in full, as does every subclass.
   */
  private def sameError(s: Raised, v: Raised): Boolean = s == v ||
    (s.exceptionClass == classOf[ArithmeticException].getName &&
      (s.message == null || v.message == null) &&
      s.copy(message = null) == v.copy(message = null))

  def difference(onSpark: Outcome, onVarka: Outcome): Option[String] =
    (onSpark, onVarka) match {
      case (Answered(s), Answered(v)) => QueryTest.sameRows(s, v).map(d => s"rows differ:\n$d")
      case (s: Raised, v: Raised) =>
        Option.when(!sameError(s, v))(s"errors differ: Spark $s, Varka $v")
      case (s: Raised, v: Answered) => Some(s"only Spark raises: Spark $s, Varka $v")
      case (s: Answered, v: Raised) => Some(s"only Varka raises: Spark $s, Varka $v")
    }
}
