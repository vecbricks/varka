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

import org.apache.spark.sql.{DataFrame, QueryTest}
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, Expression,
  GreaterThan, Literal, UnaryExpression, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeAndComment, CodeGenerator,
  CodegenFallback, VarkaExpressionCompiler}
import org.apache.spark.sql.classic.ExpressionUtils
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DataType, DateType}

/**
 * Reproducers for the census of the places vanilla Spark's code generation gives up
 * (`PLAN_TASK_188.md`), one test per entry, each making Spark show the give-up in its plan or its
 * log at the revision under test. The census was read from the source; these hold its claims to
 * what Spark actually does, so the milestone's post can cite a test rather than a reading.
 *
 * `spark.testing` changes two of Spark's reactions: a whole-stage compile failure is thrown
 * instead of falling back, and a refused split is an error instead of an INFO line. The tests
 * below assert what Spark does under test and say so where it differs from production.
 */
class VarkaCodegenGiveUpSuite extends QueryTest with SharedSparkSession {

  /** Every operator inside a whole-stage codegen stage of `df`'s executed plan. */
  private def staged(df: DataFrame): Seq[SparkPlan] = {
    def members(p: SparkPlan): Seq[SparkPlan] = p +: (p match {
      case _: InputAdapter => Nil
      case other => other.children.flatMap(members)
    })
    df.queryExecution.executedPlan.collect { case w: WholeStageCodegenExec => members(w.child) }
      .flatten
  }

  /** `body`'s log lines at `level` and above from `loggers`, as (level, message). */
  private def logged(loggers: Seq[String], level: Level)(body: => Unit): Seq[(Level, String)] = {
    val appender = new LogAppender("census reproducer", maxEvents = 10000)
    withLogAppender(appender, loggerNames = loggers, Some(level))(body)
    appender.loggingEvents.toSeq.map(e => (e.getLevel, e.getMessage.getFormattedMessage))
  }

  private def noAqe[T](body: => T): T =
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")(body)

  test("G1: with whole-stage codegen switched off there is no stage at all") {
    noAqe {
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        assert(staged(spark.range(10).selectExpr("id + 1 AS v").filter("v > 3")).isEmpty)
      }
    }
  }

  test("G2: an operator whose output passes spark.sql.codegen.maxFields leaves the stage") {
    noAqe {
      def project(n: Int): DataFrame =
        spark.range(10).selectExpr((1 to n).map(k => s"id + $k AS c$k"): _*)
      assert(staged(project(100)).exists(_.isInstanceOf[ProjectExec]))
      assert(!staged(project(101)).exists(_.isInstanceOf[ProjectExec]))
      // The count is of nested leaf fields: one struct column of 101 leaves crosses it too.
      val struct = spark.range(10).selectExpr(
        s"named_struct(${(1 to 101).map(k => s"'f$k', id + $k").mkString(", ")}) AS s")
      assert(!staged(struct).exists(_.isInstanceOf[ProjectExec]))
    }
  }

  test("G4: a non-leaf CodegenFallback expression takes its whole operator out of the stage") {
    noAqe {
      val df = spark.range(10).toDF("id")
      val plain = df.filter("id > 3")
      val fallback = df.filter(ExpressionUtils.column(
        GreaterThan(GiveUpFallbackIdentity(UnresolvedAttribute("id")), Literal(3L))))
      assert(staged(plain).exists(_.isInstanceOf[FilterExec]))
      assert(!staged(fallback).exists(_.isInstanceOf[FilterExec]))
      checkAnswer(fallback, plain)
    }
    // Varka's answer: the conjunct declines with a reason, and the filter stays Spark's.
    val d = AttributeReference("d", DateType)()
    val specs = VarkaExpressionCompiler.explainPredicate(
      GreaterThan(GiveUpFallbackIdentity(d), Literal.create(0, DateType)), Seq[Attribute](d))
    assert(specs.size == 1 && !specs.head.fused)
    assert(specs.head.decline.exists(_.reason.startsWith("unsupported")), specs)
  }

  test("G8: stack of more than 50 rows leaves the stage, 50 stays") {
    noAqe {
      def stack(n: Int): DataFrame = spark.range(10)
        .selectExpr(s"stack($n, ${(1 to n).map(k => s"id + $k").mkString(", ")}) AS v")
      assert(staged(stack(50)).exists(_.isInstanceOf[GenerateExec]))
      assert(!staged(stack(51)).exists(_.isInstanceOf[GenerateExec]))
    }
  }

  test("G10: a union of more children than wholeStage.union.maxChildren leaves the stage") {
    // Spark also logs "UnionExec codegen skipped: reason=max-children-exceeded" at DEBUG; that
    // line is not asserted here, since this suite's appender did not receive it at DEBUG, and
    // the plan is the stronger evidence.
    noAqe {
      val max = SQLConf.get.getConf(SQLConf.WHOLESTAGE_UNION_MAX_CHILDREN)
      def union(n: Int): DataFrame =
        (0 until n).map(k => spark.range(k, k + 2).toDF("id")).reduce(_ union _)
      assert(staged(union(max)).exists(_.isInstanceOf[UnionExec]))
      assert(!staged(union(max + 1)).exists(_.isInstanceOf[UnionExec]))
    }
  }

  /**
   * The size ladder's projection at `n` entries, whose consume method passes 8000 bytes at 56;
   * the offsets are this suite's own, so no other test's cached class skips the compile.
   */
  private def ladder(n: Int) = spark.range(0, 100)
    .selectExpr("date_add(date'2020-01-01', cast(id % 1460 as int)) AS d")
    .selectExpr((1 to n).map(k => s"greatest(add_months(d, ${k + 8000}), " +
      s"date_add(d, ${k + 8000}), last_day(d)) AS c$k"): _*)

  test("G25: hugeMethodLimit falls back only when set below 64KB; at its default it cannot fire") {
    val found = "Found too long generated codes"
    val stageLogger = Seq(classOf[WholeStageCodegenExec].getName)
    noAqe {
      val atDefault = logged(stageLogger, Level.INFO) {
        ladder(56).write.format("noop").mode("overwrite").save()
      }
      assert(!atDefault.exists(_._2.contains(found)))
      val at8000 = logged(stageLogger, Level.INFO) {
        withSQLConf(SQLConf.WHOLESTAGE_HUGE_METHOD_LIMIT.key -> "8000") {
          ladder(56).write.format("noop").mode("overwrite").save()
        }
      }
      assert(at8000.exists { case (level, m) => level == Level.INFO && m.contains(found) })
    }
  }

  test("G24: a stage past 64KB fails to compile; under test the failure is thrown") {
    // One CASE WHEN of 3000 branches: inside a stage, its branches are not split into methods
    // (G12), so the stage's method grows past the JVM's 64KB limit. In production Spark falls
    // back to the stage's children with WARN "Whole-stage codegen disabled for plan"; under
    // `spark.testing` it throws, which is what this asserts.
    val branches = (1 to 3000).map(k => s"WHEN id = $k THEN id * $k").mkString(" ")
    val df = spark.range(0, 10).selectExpr(s"CASE $branches ELSE 0 END AS v")
    val lines = logged(Seq(classOf[WholeStageCodegenExec].getName), Level.INFO) {
      noAqe {
        val e = intercept[Throwable](df.write.format("noop").mode("overwrite").save())
        val chain = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).map(_.toString).toSeq
        assert(chain.exists(_.contains("64 KB")), chain.take(3))
      }
    }
    assert(!lines.exists(_._2.contains("Found too long generated codes")))
  }

  test("G12: the CASE WHEN that fails past 64KB inside a stage compiles outside one") {
    // G24's expression again, with whole-stage codegen off: outside a stage Spark splits the
    // branches into methods of their own, so the same 3000 branches compile and answer.
    val branches = (1 to 3000).map(k => s"WHEN id = $k THEN id * $k").mkString(" ")
    noAqe {
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        val df = spark.range(0, 10).selectExpr(s"CASE $branches ELSE 0 END AS v")
        checkAnswer(df, (0L until 10L).map(id => org.apache.spark.sql.Row(id * id)))
      }
    }
  }

  test("G14: 3000 top-level output fields fail past 64KB inside a stage and compile outside") {
    // Inside a stage the row writer's top-level fields are not split into methods. Past 100
    // fields the projection would leave the stage by G2, so `maxFields` is raised to let it in.
    // A DataFrame keeps the plan it was first executed with, so each arm builds its own.
    def df: DataFrame = spark.range(0, 10).selectExpr((1 to 3000).map(k => s"id + $k AS c$k"): _*)
    noAqe(withSQLConf(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key -> "10000") {
      assert(staged(df).exists(_.isInstanceOf[ProjectExec]))
      val e = intercept[Throwable](df.write.format("noop").mode("overwrite").save())
      assert(causes(e).exists(_.contains("64 KB")), causes(e).take(3))
    })
    noAqe {
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        assert(df.collect().map(_.getLong(2999)).toSeq == (0L until 10L).map(_ + 3000))
      }
    }
  }

  test("G27: a class past 65535 constant-pool entries fails to compile, and nothing warns first") {
    // Spark computes a class's constant-pool size but compares it with nothing, so the only
    // guard is the compile failure. String literals do not reach the pool - Spark passes them
    // through `references` - and a class past a million characters of functions spills them into
    // nested classes with pools of their own, so this builds the class by hand: 40 methods of
    // 900 distinct long constants, two pool entries each, about 72000 in all, while no method is
    // near 64KB.
    def compile(methods: Int): Unit = {
      val body = (0 until methods).map { m =>
        val sum = (0 until 900).map(k => s"${1000000000000L + m * 900L + k}L").mkString(" + ")
        s"public long f$m() { return $sum; }"
      }.mkString("\n")
      val code = s"""public Object generate(Object[] references) { return new Probe(); }
        |class Probe { $body }""".stripMargin
      CodeGenerator.compile(new CodeAndComment(code, Map.empty))
    }
    compile(20)
    val e = intercept[Throwable](compile(40))
    assert(causes(e).exists(_.contains("0xFFFF")), causes(e).take(2))
  }

  test("G32: outside a stage, a projection that fails to compile falls back to the interpreter") {
    // With splitting off, 3000 entries compile into one method past 64KB. The projection
    // factory catches the compile error and builds the interpreted projection instead.
    val input = AttributeReference("x", org.apache.spark.sql.types.LongType)()
    val exprs = (1 to 3000).map(k =>
      org.apache.spark.sql.catalyst.expressions.Add(input, Literal(k.toLong)))
    val lines = logged(Nil, Level.WARN) {
      withSQLConf(SQLConf.CODEGEN_FACTORY_MODE.key -> "FALLBACK",
          SQLConf.CODEGEN_METHOD_SPLIT_THRESHOLD.key -> Int.MaxValue.toString) {
        val projection = UnsafeProjection.create(exprs, Seq(input))
        val row = projection(org.apache.spark.sql.catalyst.InternalRow(5L))
        assert(row.getLong(0) == 6L && row.getLong(2999) == 3005L)
      }
    }
    assert(lines.exists { case (level, m) =>
      level == Level.WARN && m.contains("Expr codegen error and falling back to interpreter mode")
    }, lines.map(_._2).take(3))
  }

  /**
   * `n` nullable int columns, `c1` to `cn`, read from a shuffle, so each is an input of the stage
   * above it rather than an expression the optimizer folds into its consumer, and a split
   * function over them needs two parameter slots each: the value and its null flag.
   */
  private def nullableInts(n: Int): DataFrame = spark.range(0, 20).selectExpr(
    (1 to n).map(k => s"cast(if(id % 7 = $k % 7, null, id + $k) as int) AS c$k"): _*)
    .repartition(1)

  /**
   * `body` with whole-stage codegen allowed over `nullableInts(130)`: past 100 input fields the
   * operator would leave the stage by G2 before any split is attempted.
   */
  private def wideStages[T](body: => T): T =
    noAqe(withSQLConf(SQLConf.WHOLESTAGE_MAX_NUM_FIELDS.key -> "1000")(body))

  private def causes(e: Throwable): Seq[String] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).map(_.toString).toSeq

  test("G17: a common subexpression over more than 255 parameter slots is not split out") {
    // Its function would need 2 slots for each of 130 columns. Spark logs INFO "Failed to split
    // subexpression code into small functions" and leaves it inline; under `spark.testing` that
    // is an internal error, which this asserts.
    val sum = (1 to 130).map(k => s"c$k").mkString(" + ")
    val df = nullableInts(130).selectExpr(s"($sum) * 2 AS a", s"($sum) * 3 AS b")
    wideStages {
      val e = intercept[Throwable](df.write.format("noop").mode("overwrite").save())
      assert(causes(e).exists(_.contains("Failed to split subexpression code")), causes(e).take(3))
    }
  }

  test("G18: an aggregate function over more than 255 parameter slots is not split out") {
    // The same bound for the aggregate's update code: INFO "Failed to split aggregate code into
    // small functions" in production, an internal error under `spark.testing`.
    val sum = (1 to 130).map(k => s"c$k").mkString(" + ")
    val df = nullableInts(130).selectExpr(s"sum($sum) AS s")
    wideStages {
      val e = intercept[Throwable](df.collect())
      assert(causes(e).exists(_.contains("Failed to split aggregate code")), causes(e).take(3))
    }
  }

  test("G28: a generated method past 255 parameter slots is a compile failure, not a load error") {
    // An instance method's slots count `this`, so 254 int parameters compile and 255 do not:
    // Janino refuses the method with "too many parameters (256)". That is an ordinary
    // CompileException, so inside a stage it takes G24's path.
    def compile(n: Int): Unit = {
      val params = (0 until n).map(i => s"int a$i").mkString(", ")
      val code = s"""public Object generate(Object[] references) { return new Probe(); }
        |class Probe { public int f($params) { return a0; } }""".stripMargin
      CodeGenerator.compile(new CodeAndComment(code, Map.empty))
    }
    compile(254)
    val e = intercept[Throwable](compile(255))
    assert(causes(e).exists(_.contains("has too many parameters (256)")), causes(e).take(2))
  }
}

/** A deterministic identity that has no generated code: G4's non-leaf `CodegenFallback`. */
case class GiveUpFallbackIdentity(child: Expression) extends UnaryExpression with CodegenFallback {
  override def dataType: DataType = child.dataType
  override protected def nullSafeEval(input: Any): Any = input
  override protected def withNewChildInternal(newChild: Expression): GiveUpFallbackIdentity =
    copy(child = newChild)
}
