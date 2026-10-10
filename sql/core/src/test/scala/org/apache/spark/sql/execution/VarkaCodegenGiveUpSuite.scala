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
import scala.jdk.OptionConverters._

import org.apache.logging.log4j.Level

import org.apache.spark.sql.{DataFrame, QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.{Add, And, Attribute, AttributeReference,
  BindReferences, BoundReference, Expression, GreaterThan, GreaterThanOrEqual,
  InterpretedOrdering, IsNotNull, LessThanOrEqual, Literal, NamedExpression, RowOrdering,
  UnaryExpression, UnsafeProjection, With}
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeAndComment, CodeCompiler,
  CodeFormatter, CodegenContext, CodeGenerator, CodegenFallback, EmptyBlock, ExprCode,
  GenerateUnsafeProjection, JaninoCodeCompiler, JavaCode, JdkCodeCompiler, VarkaCensusCodegenAccess,
  VarkaDecline, VarkaExpressionCompiler}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.FusedOutput
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrix
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.catalyst.plans.logical.{MergeRows, Project}
import org.apache.spark.sql.catalyst.plans.logical.MergeRows.{Keep, Update}
import org.apache.spark.sql.classic.{Dataset, ExpressionUtils}
import org.apache.spark.sql.execution.datasources.v2.MergeRowsExec
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DataType, DateType, IntegerType, LongType}

/**
 * Reproducers for the census of the places vanilla Spark's code generation gives up
 * (`VARKA-188.md`), one test per entry, each making Spark show the give-up in its plan or its
 * log at the revision under test. The census was read from the source; these hold its claims to
 * what Spark actually does, so the milestone's post can cite a test rather than a reading.
 *
 * Each test has two arms. The vanilla arm makes Spark give up. The Varka arm asserts the census's
 * answer for the same shape: the fused node in the plan where the census says Varka is immune or
 * has solved it, or a decline with a reason where it says Varka declines, and never an exception.
 * Where the census says the entry does not apply to Varka - an operator Varka has no node for -
 * the test says so instead of asserting nothing under a Varka heading. The Varka arms run on
 * `varkaSpark`, whose cache is Arrow-backed, or ask the compiler directly through the optimized
 * plan's projection.
 *
 * `spark.testing` changes two of Spark's reactions: a whole-stage compile failure is thrown
 * instead of falling back, and a refused split is an error instead of an INFO line. The tests
 * below assert what Spark does under test and say so where it differs from production.
 */
class VarkaCodegenGiveUpSuite extends QueryTest with VarkaSharedSessions with VarkaTestWatchdog {

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

  /** `body` with `pairs` set on `session`'s own conf, restored after; for the Varka sessions. */
  private def withSessionConf[T](session: SparkSession, pairs: (String, String)*)(body: => T): T = {
    val before = pairs.map { case (k, _) => k -> session.conf.getOption(k) }
    pairs.foreach { case (k, v) => session.conf.set(k, v) }
    try body finally before.foreach { case (k, v) =>
      v.fold(session.conf.unset(k))(session.conf.set(k, _))
    }
  }

  /**
   * The Varka projections in `df`'s executed plan: a columnar-out `VarkaProjectExec`, or the
   * `VarkaColumnarToRowExec` that carries the projection when rows are what the parent reads.
   */
  private def varkaProjections(df: DataFrame): Seq[SparkPlan] =
    df.queryExecution.executedPlan.collect {
      case p: VarkaProjectExec => p
      case p: VarkaColumnarToRowExec => p
    }

  /**
   * What the compiler does with `df`'s projection, asked of its optimized plan the way the
   * planner asks: the fused entries by position and the declined ones with their reasons. Every
   * position is in exactly one of the two, or the compiler threw, which is the property the Varka
   * arms below hold.
   */
  private def classified(df: DataFrame): (Set[Int], Map[Int, VarkaDecline]) = {
    val project = df.queryExecution.optimizedPlan.collectFirst { case p: Project => p }.get
    val list: Seq[NamedExpression] = project.projectList
    val output: Seq[Attribute] = project.child.output
    val fused = VarkaExpressionCompiler.compilePartial(list, output)
      .map(_.specs.asScala.toSeq.zipWithIndex.collect { case (_: FusedOutput, i) => i }.toSet)
      .getOrElse(Set.empty)
    val declined = VarkaExpressionCompiler.declines(list, output)
    assert(fused.size + declined.size == list.size && (fused & declined.keySet).isEmpty,
      s"fused $fused, declined ${declined.keySet}")
    assert(declined.values.forall(_.reason.nonEmpty), declined)
    (fused, declined)
  }

  /** The `varka_dates` view, cached with the Arrow serializer, in the Varka session. */
  private def varkaDates(): DataFrame = {
    cacheDates(varkaSpark)
    varkaSpark.table("varka_dates")
  }

  /** The loggers of the two objects that compile generated code and report on the classes. */
  private val compilerLoggers =
    Seq(classOf[CodeGenerator[_, _]].getName, CodeCompiler.getClass.getName.stripSuffix("$"))

  /**
   * The projection class for `exprs` over `input`, as `GenerateUnsafeProjection.create` assembles
   * it, compiled for what the generator computes and discards: the byte-code size of its largest
   * method, and beside it the number of `writeFields` methods the splitter made.
   */
  private def projectionMethods(exprs: Seq[Expression], input: Seq[Attribute]): (Int, Int) = {
    val ctx = new CodegenContext
    val eval = GenerateUnsafeProjection.createCode(ctx, BindReferences.bindReferences(exprs, input))
    val functions = ctx.declareAddedFunctions()
    val body = s"""
      |public java.lang.Object generate(Object[] references) { return new Probe(references); }
      |class Probe extends ${classOf[UnsafeProjection].getName} {
      |  private Object[] references;
      |  ${ctx.declareMutableStates()}
      |  public Probe(Object[] references) {
      |    this.references = references;
      |    ${ctx.initMutableStates()}
      |  }
      |  public void initialize(int partitionIndex) { ${ctx.initPartition()} }
      |  ${CodeGenerator.function1ApplyBridge(ctx.INPUT_ROW)}
      |  public UnsafeRow apply(InternalRow ${ctx.INPUT_ROW}) { ${eval.code} return ${eval.value}; }
      |  $functions
      |}""".stripMargin
    val code = CodeFormatter.stripOverlappingComments(
      new CodeAndComment(body, ctx.getPlaceHolderToComments()))
    (CodeGenerator.compile(code)._2.maxMethodCodeSize,
      "private void writeFields_".r.findAllIn(functions).length)
  }

  /** The byte sizes of the methods `lines` report as too long for the JIT, by class and method. */
  private def jitRefused(lines: Seq[(Level, String)]): Seq[(String, Int)] = lines.collect {
    case (Level.INFO, m) if m.contains("Generated method too long to be JIT compiled") =>
      val words = m.split(" ")
      (words(words.indexOf("is") - 1), words(words.indexOf("is") + 1).toInt)
  }

  test("G1: with whole-stage codegen switched off there is no stage at all") {
    noAqe {
      withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        assert(staged(spark.range(10).selectExpr("id + 1 AS v").filter("v > 3")).isEmpty)
      }
    }
    // Varka's answer: its nodes are not whole-stage codegen, so with it off they are still there.
    withSessionConf(varkaSpark, SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
      val df = varkaDates().selectExpr("date_add(d, 1) AS e")
      assert(staged(df).isEmpty)
      assert(varkaProjections(df).nonEmpty, df.queryExecution.executedPlan)
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
    // Varka's answer: the kernel is not in a stage and its width is decided in bytes, so a
    // projection of 101 outputs fuses whole. (The same limit counted over a cached table's
    // schema is G3, which `VarkaSchemaWidthSuite` checks.)
    def wide(session: SparkSession): DataFrame = session.table("varka_dates")
      .selectExpr((1 to 101).map(k => s"date_add(d, $k) AS c$k"): _*)
    val fused = wide(varkaDates().sparkSession)
    assert(varkaProjections(fused).exists(_.output.size == 101), fused.queryExecution.executedPlan)
    assert(classified(fused)._1.size == 101)
    cacheDates(disabledSpark)
    checkAnswer(fused, wide(disabledSpark).collect())
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
    assert(specs.head.decline.toScala.exists(_.reason.startsWith("unsupported")), specs)
  }

  test("G8: stack of more than 50 rows leaves the stage, 50 stays") {
    noAqe {
      def stack(n: Int): DataFrame = spark.range(10)
        .selectExpr(s"stack($n, ${(1 to n).map(k => s"id + $k").mkString(", ")}) AS v")
      assert(staged(stack(50)).exists(_.isInstanceOf[GenerateExec]))
      assert(!staged(stack(51)).exists(_.isInstanceOf[GenerateExec]))
    }
    // Varka's answer: none. Varka has no generator node, so this give-up is not one it meets.
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
    // Varka's answer: none. Varka has no union node, so this give-up is not one it meets.
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
    // Varka's answer, for G25 and G26 alike: the same 56 entries fuse, and every emitted method
    // is measured against the 8000-byte budget after the class is built, so none is left to
    // HotSpot's HugeMethodLimit. `VarkaHugeMethodSuite` pins the per-method sizes; this pins the
    // plan and the answer.
    def rungs(session: SparkSession): DataFrame = session.table("varka_dates").selectExpr(
      (1 to 56).map(k => s"greatest(add_months(d, ${k + 8000}), date_add(d, ${k + 8000}), " +
        s"last_day(d)) AS c$k"): _*)
    val fused = rungs(varkaDates().sparkSession)
    assert(varkaProjections(fused).nonEmpty, fused.queryExecution.executedPlan)
    assert(classified(fused)._1.size == 56)
    cacheDates(disabledSpark)
    checkAnswer(fused, rungs(disabledSpark).collect())
  }

  test("G24: a stage past 64KB fails to compile; under test the failure is thrown") {
    // One CASE WHEN of 3000 branches: inside a stage, its branches are not split into methods
    // (G12), so the stage's method grows past the JVM's 64KB limit. In production Spark falls
    // back to the stage's children with WARN "Whole-stage codegen disabled for plan"; under
    // `spark.testing` it throws, which is what this asserts.
    val branches = (1 to 3000).map(k => s"WHEN id = $k THEN id * $k").mkString(" ")
    val df = spark.range(0, 10).selectExpr(s"CASE $branches ELSE 0 END AS v")
    // Released Spark's behaviour: since SPARK-33301 (apache/spark#59225, 4.4.0) a stage splits such
    // an expression into methods by default, so the stage compiles; the conf restores the old one.
    val lines = logged(Seq(classOf[WholeStageCodegenExec].getName), Level.INFO) {
      noAqe {
        withSQLConf(SQLConf.WHOLESTAGE_SPLIT_EXPRESSIONS.key -> "false") {
          val e = intercept[Throwable](df.write.format("noop").mode("overwrite").save())
          val chain = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).map(_.toString).toSeq
          assert(chain.exists(_.contains("64 KB")), chain.take(3))
        }
      }
    }
    assert(!lines.exists(_._2.contains("Found too long generated codes")))
    // Varka's answer: the compiler declines the entry with a reason at plan time and the query
    // is Spark's; nothing is emitted, so nothing can fail to compile.
    val (fused, declined) = classified(df)
    assert(fused.isEmpty && declined.contains(0), declined)
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
    // Varka's answer: 3000 outputs over a date column are classified without an exception,
    // each one fused or declined with a reason; the bytes of every emitted method stay under
    // the budget by construction, whatever the count.
    val wide = varkaDates().selectExpr((1 to 3000).map(k => s"date_add(d, $k) AS c$k"): _*)
    val (fused, declined) = classified(wide)
    val reasons = declined.values.map(_.reason).toSet
    assert(fused.nonEmpty && reasons.forall(_.contains("budget")), s"fused ${fused.size}: $reasons")
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
    // Varka's answer: `VarkaEmitBudget` counts the emitted class's constant pool against the same
    // cap and declines the shape with a reason before anything is loaded; the cap is out of reach
    // of any admitted shape, so `VarkaEmitterBudgetSuite` pins the check on a hand-built class.
  }

  test("G32: outside a stage, a projection that fails to compile falls back to the interpreter") {
    // With splitting off, 1000 entries compile into one method past 64KB. The projection
    // factory catches the compile error and builds the interpreted projection instead. The count
    // is kept near the smallest that crosses 64KB (600 does not) because Janino's heap grows
    // much faster than the method: about 0.5GB at 1000 entries and 3GB at 3000, which is most
    // of the 4GB a test JVM shares with every other suite in its module.
    val input = AttributeReference("x", org.apache.spark.sql.types.LongType)()
    val exprs = (1 to 1000).map(k =>
      org.apache.spark.sql.catalyst.expressions.Add(input, Literal(k.toLong)))
    val lines = logged(Nil, Level.WARN) {
      withSQLConf(SQLConf.CODEGEN_FACTORY_MODE.key -> "FALLBACK",
          SQLConf.CODEGEN_METHOD_SPLIT_THRESHOLD.key -> Int.MaxValue.toString) {
        val projection = UnsafeProjection.create(exprs, Seq(input))
        val row = projection(org.apache.spark.sql.catalyst.InternalRow(5L))
        assert(row.getLong(0) == 6L && row.getLong(999) == 1005L)
      }
    }
    assert(lines.exists { case (level, m) =>
      level == Level.WARN && m.contains("Expr codegen error and falling back to interpreter mode")
    }, lines.map(_._2).take(3))
    // Varka's answer: a shape the emitter cannot serve in bytes is declined at plan time with a
    // reason and left to Spark, so no compile failure is caught after the fact
    // (`VarkaEmitterBudgetSuite`, "a shape still over the byte budget when no split is left").
  }

  test("G15: the splitter counts source characters, and a character is not a byte") {
    // Forty `x + k` outside a stage: the splitter closes a `writeFields` method when a block's
    // source passes `methodSplitThreshold`, so their count follows the threshold - one per
    // expression at 1, fourteen at the default 1024 on this shape, none at Int.MaxValue, where
    // the whole projection is inlined. The JIT's limit is 8000 bytes of bytecode, and the proxy
    // is in characters: the shape's source compiles at about seven characters to a byte, so a
    // threshold of 8000 makes methods of about 1400 bytes, and the default of 1024 methods of
    // about 230 - the splitter errs far on the small side, and nothing measures the result.
    val x = AttributeReference("x", LongType)()
    val adds = (1 to 40).map(k => Add(x, Literal(k.toLong)))
    def at(threshold: Int): (Int, Int) =
      withSQLConf(SQLConf.CODEGEN_METHOD_SPLIT_THRESHOLD.key -> threshold.toString) {
        projectionMethods(adds, Seq(x))
      }
    val (bytesAtOne, methodsAtOne) = at(1)
    assert(methodsAtOne == 40)
    val (bytesAtDefault, methodsAtDefault) =
      at(SQLConf.CODEGEN_METHOD_SPLIT_THRESHOLD.defaultValue.get)
    assert(methodsAtDefault > 1 && methodsAtDefault < 40, methodsAtDefault)
    assert(bytesAtDefault < 1024 && bytesAtOne < 1024, (bytesAtOne, bytesAtDefault))
    val (bytesAtLimit, _) = at(CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT)
    assert(bytesAtLimit < CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT / 4, bytesAtLimit)
    val (bytesUnsplit, methodsUnsplit) = at(Int.MaxValue)
    assert(methodsUnsplit == 0 && bytesUnsplit > 2 * bytesAtLimit, (bytesUnsplit, bytesAtLimit))
    // Varka's answer: the budget every emitted method is held to is in the JVM's unit and is
    // the JIT's own limit, measured on the class after it is built and regrouped until it holds
    // (`VarkaEmitterBudgetSuite`, "regroup before decline"; `VarkaHugeMethodSuite`).
    assert(VarkaMatrix.base.methodByteBudget() ==
      CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT)
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
    // Varka's answer: its methods take a fixed few parameters however many columns an entry
    // reads, so the 255-slot cap is not one it meets. It does not get that far: the same
    // 130-column expression, in two entries and without the checked multiplies so that nothing
    // else can decline it, is refused at Varka's own budget of fused nodes, with that reason. The
    // census's "immune" for this entry is therefore "not reached" until that budget gives way
    // to the byte budget (VARKA-190), and this arm pins where the shape stops today.
    val (fused, declined) =
      classified(nullableInts(130).selectExpr(s"($sum) AS a", s"($sum) + 1 AS b"))
    assert(fused.isEmpty && declined.keySet == Set(0, 1), declined)
    assert(declined.values.forall(_.reason == "exceeds the emitter's fused budget"), declined)
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
    // Varka's answer: none. Varka has no aggregate node, so this give-up is not one it meets.
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
    // Varka's answer: its methods have a fixed arity, and `VarkaEmitBudget` counts every emitted
    // method's parameter slots against the cap all the same (`VarkaEmitterBudgetSuite`).
  }

  test("G33: an ordering asked of its generator directly fails the query the factory would save") {
    // `ORDER BY ... LIMIT` plans `TakeOrderedAndProjectExec`, whose `LazilyGeneratedOrdering`
    // calls `GenerateOrdering.generate` itself. With the splitter off, the 1200-branch CASE WHEN
    // is generated twice into one `compare` method, past 64KB, and the failure is the query's:
    // `CodeGenerator` logs its ERROR and the exception carries the cause. The same order asked
    // through `RowOrdering.create` - the factory with the interpreted fallback, which `SortExec`
    // uses - answers with an `InterpretedOrdering`. The count is kept near the smallest that
    // crosses, since Janino's heap grows much faster than the method (see G32).
    val branches = (1 to 1200).map(k => s"WHEN id = $k THEN id * $k").mkString(" ")
    val query = s"SELECT id FROM range(10) ORDER BY CASE $branches ELSE 0 END LIMIT 5"
    withSQLConf(SQLConf.CODEGEN_METHOD_SPLIT_THRESHOLD.key -> Int.MaxValue.toString,
        SQLConf.CODEGEN_FACTORY_MODE.key -> "FALLBACK") {
      noAqe {
        val df = spark.sql(query)
        val top = df.queryExecution.executedPlan
          .collectFirst { case t: TakeOrderedAndProjectExec => t }
          .getOrElse(fail(df.queryExecution.executedPlan.treeString))
        val lines = logged(compilerLoggers, Level.ERROR) {
          val e = intercept[Throwable](df.collect())
          assert(causes(e).exists(_.contains("64 KB")), causes(e).take(3))
        }
        assert(lines.exists(_._2.contains("Failed to compile the generated Java code")), lines)
        val ordering = RowOrdering.create(top.sortOrder, top.child.output)
        assert(ordering.isInstanceOf[InterpretedOrdering], ordering.getClass.getName)
      }
    }
    // Varka's answer: none. Varka has no ordering and no node that sorts, and it calls no
    // generator of Spark's; the query plans no Varka node.
    assert(varkaProjections(varkaSpark.sql(query)).isEmpty)
  }

  /** A view of a date and a string column, cached with the Arrow serializer, in `session`. */
  private def cacheDatedStrings(session: SparkSession): Unit = {
    val rows = (0 until 200).map(i => (date(s"2024-01-${1 + i % 28}"), s"ab$i"))
    session.createDataFrame(rows).toDF("d", "s").createOrReplaceTempView("varka_dated_strings")
    session.catalog.cacheTable("varka_dated_strings")
  }

  test("G34: outside a stage nothing measures a method against 8000 bytes") {
    // Sixty string CASE WHENs of five branches each, beside a fused date entry; distinct, so
    // subexpression elimination shares nothing. Outside a stage the splitter's characters (G15)
    // keep every method of the projection near 300 bytes, and no method can reach 8000; with the
    // splitter off the projection is one method of about 31000 bytes, which Spark compiles,
    // logs as too long for the JIT (G26's line) and uses - `hugeMethodLimit`, which replaces a
    // stage whose method is past it (G25), does not apply to a projection outside one.
    val query = "SELECT date_add(d, 1) AS a, " + (1 to 60).map(k => "CASE " +
      (1 to 5).map(b => s"WHEN s = 'k$k-$b' THEN 'v$b'").mkString(" ") + s" ELSE 'z' END AS c$k")
      .mkString(", ") + " FROM varka_dated_strings"
    val stageLogger = Seq(classOf[WholeStageCodegenExec].getName)
    val unsplit = SQLConf.CODEGEN_METHOD_SPLIT_THRESHOLD.key -> Int.MaxValue.toString
    val limitAt8000 = SQLConf.WHOLESTAGE_HUGE_METHOD_LIMIT.key -> "8000"
    cacheDatedStrings(disabledSpark)
    def run(session: SparkSession): Unit =
      session.sql(query).write.format("noop").mode("overwrite").save()
    withSessionConf(disabledSpark, SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false", limitAt8000) {
      assert(!disabledSpark.sql(query).queryExecution.executedPlan
        .exists(_.isInstanceOf[WholeStageCodegenExec]))
      val split = logged(compilerLoggers, Level.INFO)(run(disabledSpark))
      assert(jitRefused(split).isEmpty, split.map(_._2))
      withSessionConf(disabledSpark, unsplit) {
        val whole = logged(compilerLoggers ++ stageLogger, Level.INFO)(run(disabledSpark))
        val refused = jitRefused(whole)
        assert(refused.exists { case (method, bytes) =>
          method.contains("SpecificUnsafeProjection") && bytes > 8000
        }, refused)
        assert(!whole.exists(_._1 == Level.WARN), whole.filter(_._1 == Level.WARN))
        assert(!whole.exists(_._2.contains("Found too long generated codes")))
      }
    }
    // The stage's expressions are not split, as in the releases before SPARK-33301 (G24's note).
    val stageUnsplit = SQLConf.WHOLESTAGE_SPLIT_EXPRESSIONS.key -> "false"
    withSessionConf(disabledSpark, SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true", limitAt8000,
        stageUnsplit) {
      val staged = logged(stageLogger, Level.INFO)(run(disabledSpark))
      assert(staged.exists(_._2.contains("Found too long generated codes")), staged.map(_._2))
    }
    // Varka's answer: the sixty entries decline and the date entry fuses, and the node evaluates
    // the declined entries through a projection of Spark's, in the same class as vanilla's: under
    // the splitter its methods stay small, and with the splitter off it is the same one method
    // past 8000 bytes, used as vanilla's is.
    cacheDatedStrings(varkaSpark)
    val df = varkaSpark.sql(query)
    assert(varkaProjections(df).nonEmpty, df.queryExecution.executedPlan)
    val (fused, declined) = classified(df)
    assert(fused == Set(0) && declined.size == 60, (fused, declined.keySet))
    // The expected rows are computed first, so that only the Varka query compiles in the window.
    val expected = disabledSpark.sql(query).collect().toSeq
    val split = logged(compilerLoggers, Level.INFO)(checkAnswer(df, expected))
    assert(jitRefused(split).isEmpty, split.map(_._2))
    withSessionConf(varkaSpark, unsplit) {
      val whole = logged(compilerLoggers, Level.INFO)(checkAnswer(df, expected))
      val refused = jitRefused(whole)
      assert(refused.exists { case (method, bytes) =>
        method.contains("SpecificUnsafeProjection") && bytes > 8000
      }, refused)
    }
  }

  /** The generated source of every whole-stage codegen stage of `df`'s executed plan. */
  private def stageSources(df: DataFrame): Seq[String] =
    df.queryExecution.executedPlan.collect {
      case w: WholeStageCodegenExec => w.doCodeGen()._2.body
    }

  test("G9: a MergeRows over more than 255 parameter slots leaves the stage") {
    // `MergeRowsExec`, the row-level operator of `MERGE INTO`, is in a stage only while its
    // child's output fits the 255 parameter slots of one method: two for each nullable int. The
    // operator is built as `MergeRowsExecBenchmark` builds it, over the join output a `MERGE`
    // would read, so no catalog is needed. Spark logs nothing when the operator leaves.
    def merge(n: Int): DataFrame = {
      val input = nullableInts(n).selectExpr(
        (1 to n).map(k => s"c$k") ++ Seq("1 AS _source_marker", "1 AS _target_marker"): _*)
      val attrs = input.queryExecution.analyzed.output
      val plan = MergeRows(
        isSourceRowPresent = IsNotNull(attrs(n)),
        isTargetRowPresent = IsNotNull(attrs(n + 1)),
        matchedInstructions = Seq(Keep(Update, Literal.TrueLiteral, attrs.take(n))),
        notMatchedInstructions = Nil,
        notMatchedBySourceInstructions = Nil,
        checkCardinality = false,
        output = attrs.take(n).map(a => AttributeReference(a.name, a.dataType, a.nullable)()),
        child = input.queryExecution.analyzed)
      Dataset.ofRows(spark, plan)
    }
    wideStages {
      assert(staged(merge(10)).exists(_.isInstanceOf[MergeRowsExec]),
        merge(10).queryExecution.executedPlan)
      val wide = merge(130)
      assert(wide.queryExecution.executedPlan.exists(_.isInstanceOf[MergeRowsExec]))
      assert(!staged(wide).exists(_.isInstanceOf[MergeRowsExec]), wide.queryExecution.executedPlan)
      assert(wide.count() == 20)
    }
    // Varka's answer: none. Varka has no row-level operator, so this give-up is not one it meets.
  }

  test("G20: a With definition over more than 255 parameter slots stays inline") {
    // `BETWEEN` is a `With` that evaluates its operand once. The optimizer inlines a `With`
    // except in a conditional branch, where it is left for code generation to memoize - which is
    // how one reaches a stage at all, as the plan below shows, settling open question 7.
    val sum = (1 to 60).map(k => s"c$k").mkString(" + ")
    val query = nullableInts(60).selectExpr(s"if(c1 > 0, ($sum) BETWEEN 0 AND 1000, false) AS a")
    wideStages {
      assert(query.queryExecution.executedPlan.toString.contains("with("),
        query.queryExecution.executedPlan)
      assert(query.count() == 20)
    }
    // There the definition goes to a method of its own, `computeCommonExpr`, when its code is
    // long and every value it reads can be passed in 255 parameter slots; otherwise it stays
    // inline, with no log line. In the stages a query builds the method is refused earlier, since
    // an input the stage has not yet evaluated cannot be passed at all, so the slot bound is
    // reached here the way the operator would reach it: the definition generated against input
    // variables already evaluated, two slots each for a nullable int. Sixty fit, a hundred and
    // thirty do not.
    def methodsOver(n: Int): Int = {
      val ctx = new CodegenContext
      ctx.INPUT_ROW = null
      ctx.currentVars = (0 until n).map { k =>
        ExprCode(EmptyBlock, JavaCode.isNullVariable(s"v${k}IsNull"),
          JavaCode.variable(s"v$k", IntegerType))
      }
      val total = (0 until n).map(k => BoundReference(k, IntegerType, nullable = true): Expression)
        .reduce(Add(_, _))
      val between = With(total) { case Seq(ref) =>
        And(GreaterThanOrEqual(ref, Literal(0)), LessThanOrEqual(ref, Literal(1000)))
      }
      between.genCode(ctx)
      "private void computeCommonExpr_".r.findAllIn(ctx.declareAddedFunctions()).size
    }
    withSQLConf(SQLConf.CODEGEN_METHOD_SPLIT_THRESHOLD.key -> "1024") {
      assert(methodsOver(60) == 1, "120 parameter slots fit a method")
      assert(methodsOver(130) == 0, "260 do not, and the definition stays inline")
    }
    // Varka's answer: the `With` reaches its compiler only as an operand of a conditional, and an
    // int-valued entry is not one it fuses (scope item 55), so the entry declines with a reason.
    val (fused, declined) = classified(query)
    assert(fused.isEmpty && declined.keySet == Set(0), declined)
  }

  test("G21: an aggregate the fast hash map does not support gets only the regular map") {
    // The partial aggregate puts a generated first-level map, `FastHashMap`, in front of its
    // hash table when every key and buffer is primitive, decimal, string or interval. A struct key
    // is none of these, so the map is refused and the stage has the regular table alone. In
    // production Spark logs INFO "... is set to true, but current version of codegened fast
    // hashmap does not support this aggregate."; under `spark.testing` it logs nothing, so the
    // generated source is what this asserts.
    noAqe {
      val base = spark.range(0, 100).selectExpr("id % 10 AS k", "id AS v")
      val plain = base.groupBy("k").sum("v")
      val struct = base.selectExpr("struct(k) AS s", "v").groupBy("s").sum("v")
      assert(stageSources(plain).exists(_.contains("FastHashMap")), "a long key gets the map")
      assert(!stageSources(struct).exists(_.contains("FastHashMap")), "a struct key does not")
      assert(struct.count() == 10)
    }
    // Varka's answer: none. Varka has no aggregate node.
  }

  test("G29: a class the statistics cannot parse reports -1, and the size check passes it") {
    // Every compiled class is parsed for its largest method and constant pool. Where the parse
    // fails, `CodeCompiler` logs WARN "Error calculating stats of compiled class." and reports -1
    // for both, and G25's check - the largest method against `hugeMethodLimit` - passes a class
    // whose size it never saw. A real compile does not produce such a class today; the bytes
    // here are not a class file, which is the parse failure itself.
    val lines = logged(compilerLoggers, Level.WARN) {
      val stats = VarkaCensusCodegenAccess.byteCodeStats("not a class file".getBytes("UTF-8"))
      assert(stats.maxMethodCodeSize == -1 && stats.maxConstPoolSize == -1, stats)
    }
    assert(lines.exists(_._2.contains("Error calculating stats of compiled class.")), lines)
    // A class that parses reports its largest method, as the check needs.
    val a = AttributeReference("a", LongType)()
    assert(projectionMethods(Seq(Add(a, Literal(1L))), Seq(a))._1 > 0)
    // Varka's answer: its emitter measures the classes it builds itself, in the JVM's units
    // (`VarkaEmittedClass.measure`), and does not depend on this statistic.
  }

  test("G31: the JDK compiler backend routes a unit it cannot name back to Janino") {
    // With `spark.sql.codegen.compiler=jdk`, a unit whose source names a class nested in a Scala
    // package object is compiled by Janino anyway, since `package` is a word Java cannot spell as
    // an identifier; so are REPL units and units naming an unnarrowable anonymous or local
    // class. Each routing logs INFO once per JVM. It changes which compiler's limits (G24)
    // apply to that unit, not whether it compiles.
    withSQLConf(SQLConf.CODEGEN_COMPILER.key -> "jdk") {
      assert(VarkaCensusCodegenAccess.backendFor("int f() { return 1; }") eq JdkCodeCompiler)
      assert(VarkaCensusCodegenAccess.backendFor(
        "Object f() { return new a.b.package$Inner(); }") eq JaninoCodeCompiler)
    }
    assert(VarkaCensusCodegenAccess.backendFor("int f() { return 1; }") eq JaninoCodeCompiler,
      "the default backend")
    // Varka's answer: immune. Varka emits bytecode itself with the Class-File API; no source
    // compiler, and so no backend choice, is involved.
  }
}

/** A deterministic identity that has no generated code: G4's non-leaf `CodegenFallback`. */
case class GiveUpFallbackIdentity(child: Expression) extends UnaryExpression with CodegenFallback {
  override def dataType: DataType = child.dataType
  override protected def nullSafeEval(input: Any): Any = input
  override protected def withNewChildInternal(newChild: Expression): GiveUpFallbackIdentity =
    copy(child = newChild)
}
