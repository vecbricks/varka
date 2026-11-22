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

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrix.PinsDefaults
import org.apache.spark.sql.execution.{ColumnarToRowExec, ProjectExec, QueryExecution, SparkPlan, VarkaColumnarRule, VarkaColumnarToRowExec, VarkaFilterColumnarToRowExec, VarkaFilterExec, VarkaPartialDecline, VarkaProjectExec, VarkaSharedSessions}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.util.QueryExecutionListener

/**
 * The decline of a projection that fuses in part under a row consumer (VARKA-613), over the
 * day-time baseline's table and the probe's four shapes: (a) `extract(DAY)` fused beside three
 * extracts of the same column on the row path, (b) the same with the three over another column,
 * (c) three fused arithmetic entries beside a residual extract of their column, and (d) the same
 * three beside a residual over other columns. Under a row consumer the shapes whose residual
 * entries outnumber their fused ones, a and b, plan Spark's own projection, the plan of the
 * session without Varka, and c and d keep their `VarkaColumnarToRowExec`; under a
 * columnar consumer, a `noop` write, every shape keeps its `VarkaProjectExec`. A projection that
 * fuses whole is unchanged. So the suite fails a rule that fires on an all-fused projection,
 * reaches a columnar consumer, or keeps a shape it should decline, at the unnamed emit level and
 * the named ones the runners and the laptop resolve; and both sessions answer every shape alike.
 */
class VarkaPartialDeclineSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 200

  /** The probe's shapes over the column with no null, by letter, and whether each declines. */
  private val shapes: Seq[(String, String, Boolean)] =
    VarkaPartialProjectionProbe.shapes("").asScala.toSeq
      .filter(_(0).matches("[a-d]\\..*")).map { s =>
        (s(0), s(1), s(0).startsWith("a.") || s(0).startsWith("b."))
      }

  /**
   * Beside two cheap residual extracts, one fused entry of three operators: the entries
   * outnumber, two to one, but weigh less, 2 to 3, so the rule keeps it.
   */
  private val heavyFusedOneEntry = "SELECT abs(-dt * 3) AS a, extract(HOUR FROM dt) AS b, " +
    "extract(MINUTE FROM dt) AS c FROM " + VarkaDayTimeIntervalBaselineBenchmark.VIEW

  private val allFused = "SELECT extract(DAY FROM dt) AS a, -dt AS b FROM " +
    VarkaDayTimeIntervalBaselineBenchmark.VIEW

  private def withTable(body: => Unit): Unit = {
    val view = VarkaDayTimeIntervalBaselineBenchmark.VIEW
    val sessions = Seq(spark, varkaSpark, disabledSpark)
    try {
      for (session <- sessions) {
        session.sql(VarkaDayTimeIntervalBaselineBenchmark.tableSql(rows))
          .createOrReplaceTempView(view)
        session.catalog.cacheTable(view)
      }
      body
    } finally {
      for (session <- sessions if session.catalog.tableExists(view)) {
        session.catalog.uncacheTable(view)
        session.catalog.dropTempView(view)
      }
    }
  }

  private def forEachLevel(body: Int => Unit): Unit =
    for (level <- Seq(-1, 2, 3)) {
      withClue(s"useAVX $level") {
        withBothConf(SQLConf.VARKA_EMIT_USE_AVX.key, level.toString)(body(level))
      }
    }

  private def varkaNodes(plan: SparkPlan): Seq[SparkPlan] = plan.collect {
    case p: VarkaColumnarToRowExec => p
    case p: VarkaProjectExec => p
  }

  /** The operators' names, from the top, which the sessions number alike. */
  private def shape(plan: SparkPlan): Seq[String] = plan.collect { case p => p.nodeName }

  /** Writes to `noop` and returns the executed plan, which a write does not hand back itself. */
  private def writeToNoop(session: SparkSession, sql: String): SparkPlan = {
    @volatile var executedPlan: SparkPlan = null
    val listener = new QueryExecutionListener {
      override def onSuccess(funcName: String, qe: QueryExecution, durationNs: Long): Unit = {
        executedPlan = qe.executedPlan
      }
      override def onFailure(funcName: String, qe: QueryExecution, e: Exception): Unit = {}
    }
    session.listenerManager.register(listener)
    try {
      session.sql(sql).write.format("noop").mode("append").save()
      session.sparkContext.listenerBus.waitUntilEmpty()
    } finally {
      session.listenerManager.unregister(listener)
    }
    assert(executedPlan != null, "the write did not report a query execution")
    executedPlan
  }

  test("a projection that fuses in part under a row consumer is left to Spark when its " +
      "residual entries outnumber its fused ones", PinsDefaults) {
    withTable {
      forEachLevel { _ =>
        for ((title, query, declines) <- shapes) {
          withClue(title) {
            val df = varkaSpark.sql(query)
            val plan = df.queryExecution.executedPlan
            if (declines) {
              // The plan of the session without Varka, operator for operator: Spark's
              // projection over the scan, which gives it rows itself.
              assert(varkaNodes(plan).isEmpty, plan.treeString)
              assert(shape(plan) === shape(disabledSpark.sql(query).queryExecution.executedPlan),
                plan.treeString)
            } else {
              val nodes = varkaNodes(plan)
              assert(nodes.size === 1 && nodes.head.isInstanceOf[VarkaColumnarToRowExec],
                plan.treeString)
            }
            checkAnswer(df, spark.sql(query).collect().toSeq)
          }
        }
      }
    }
  }

  test("a declined projection over a fused filter reads the filter's rows without compacting",
      PinsDefaults) {
    withTable {
      // The blend over the filter `dt > dt2`, which fuses: the declined projection is Spark's,
      // over the filter's to-row node, the node a row consumer of the filter alone gets, and the
      // compacting columnar filter is left in no plan.
      val (_, blend, _) = shapes.head
      val query = s"$blend WHERE dt > dt2"
      forEachLevel { _ =>
        val df = varkaSpark.sql(query)
        val plan = df.queryExecution.executedPlan
        assert(varkaNodes(plan).isEmpty, plan.treeString)
        assert(plan.exists(_.isInstanceOf[VarkaFilterColumnarToRowExec]), plan.treeString)
        assert(!plan.exists(_.isInstanceOf[VarkaFilterExec]), plan.treeString)
        checkAnswer(df, spark.sql(query).collect().toSeq)
      }
    }
  }

  test("one expensive fused entry beside two cheap residual ones keeps its kernel",
      PinsDefaults) {
    withTable {
      forEachLevel { _ =>
        val df = varkaSpark.sql(heavyFusedOneEntry)
        val nodes = varkaNodes(df.queryExecution.executedPlan)
        assert(nodes.size === 1 && nodes.head.isInstanceOf[VarkaColumnarToRowExec],
          df.queryExecution.executedPlan.treeString)
        checkAnswer(df, spark.sql(heavyFusedOneEntry).collect().toSeq)
      }
    }
  }

  test("a declined projection over a declined projection leaves both to Spark", PinsDefaults) {
    withTable {
      // The blend over the output of shape b's projection, built by hand since the optimizer
      // collapses two projections: both are residual-heavy, and both are under a row consumer.
      def projection(query: String): ProjectExec = varkaSpark.sql(query).queryExecution
        .sparkPlan.collectFirst { case p: ProjectExec => p }.get
      val inner0 = projection(shapes(1)._2)
      val scan = inner0.child
      val outer0 = projection(shapes.head._2)
      val dt = scan.output.find(_.name == "dt").get
      val inner = ProjectExec(dt +: inner0.projectList, scan)
      // Each query resolves its own copy of the view, so its columns are re-pointed by name.
      val byName = inner.output.map(a => a.name -> a).toMap
      val outer = ProjectExec(outer0.projectList.map(_.transform {
        case a: AttributeReference if byName.contains(a.name) => byName(a.name)
      }.asInstanceOf[NamedExpression]), inner)
      val pre = varkaSpark.withActive(VarkaColumnarRule.preColumnarTransitions(outer))
      assert(pre.collect { case p: VarkaProjectExec => p }.size === 2, pre.treeString)
      val post = varkaSpark.withActive(
        VarkaColumnarRule.postColumnarTransitions(ColumnarToRowExec(pre)))
      assert(varkaNodes(post).isEmpty, post.treeString)
      assert(post.isInstanceOf[ProjectExec] && post.children.head.isInstanceOf[ProjectExec],
        post.treeString)
      ()
    }
  }

  test("the decline's reason weighs the entries, and only a residual-heavy projection has one",
      PinsDefaults) {
    withTable {
      for ((title, query, declines) <- shapes ++
          Seq(("all fused", allFused, false),
            ("one heavy fused entry", heavyFusedOneEntry, false))) {
        withClue(title) {
          // The classification the rule reads, made on the plan the pre stage builds.
          val planned = varkaSpark.sql(query).queryExecution.sparkPlan
          val pre = varkaSpark.withActive(VarkaColumnarRule.preColumnarTransitions(planned))
          val node = pre.collectFirst { case p: VarkaProjectExec => p }
            .getOrElse(fail(s"the pre stage planned no Varka projection:\n${pre.treeString}"))
          val options = VarkaColumnarToRowExec.emitOptions(node.sessionEmit)
          val reason = VarkaPartialDecline.reason(node.classification.get,
            node.projectList.asJava, options)
          assert(reason.isPresent === declines, reason)
          if (declines) {
            assert(reason.get.contains("outnumber and outweigh its fused ones, " +
              "3 entries of 3 operators to 1 of 1"), reason.get)
            // A test's forced decline runs the merge path, so the rule never declines under it.
            assert(!VarkaPartialDecline.reason(node.classification.get,
              node.projectList.asJava, options.withForceResidualAt(1)).isPresent)
          }
        }
      }
    }
  }

  test("a declined projection handed to the post stage again stays Spark's projection",
      PinsDefaults) {
    withTable {
      for ((title, query, declines) <- shapes if declines) {
        withClue(title) {
          // The shape the decline emits, built from the planner's projection over its scan: the
          // post stage's projection arm sees it on any later pass, and must not fuse it there.
          val planned = varkaSpark.sql(query).queryExecution.sparkPlan
          val project = planned.collectFirst { case p: ProjectExec => p }
            .getOrElse(fail(s"no projection planned:\n${planned.treeString}"))
          // Over the scan, as the decline plans it, and over a to-row transition of it, which
          // the arm unwraps.
          for (declined <- Seq(project, project.copy(child = ColumnarToRowExec(project.child)))) {
            val once = varkaSpark.withActive(VarkaColumnarRule.postColumnarTransitions(declined))
            val twice = varkaSpark.withActive(VarkaColumnarRule.postColumnarTransitions(once))
            assert(varkaNodes(once).isEmpty && varkaNodes(twice).isEmpty, twice.treeString)
            assert(twice.isInstanceOf[ProjectExec], twice.treeString)
          }
        }
      }
    }
  }

  test("a columnar consumer keeps every partial projection's kernel") {
    withTable {
      forEachLevel { _ =>
        for ((title, query, _) <- shapes) {
          withClue(title) {
            val plan = writeToNoop(varkaSpark, query)
            assert(plan.exists(_.isInstanceOf[VarkaProjectExec]), plan.treeString)
            assert(!plan.exists(_.isInstanceOf[VarkaColumnarToRowExec]), plan.treeString)
          }
        }
      }
    }
  }

  test("a projection that fuses whole keeps its kernel under a row consumer", PinsDefaults) {
    withTable {
      forEachLevel { _ =>
        val df = varkaSpark.sql(allFused)
        val nodes = varkaNodes(df.queryExecution.executedPlan)
        assert(nodes.size === 1 && nodes.head.isInstanceOf[VarkaColumnarToRowExec],
          df.queryExecution.executedPlan.treeString)
        checkAnswer(df, spark.sql(allFused).collect().toSeq)
      }
    }
  }
}
