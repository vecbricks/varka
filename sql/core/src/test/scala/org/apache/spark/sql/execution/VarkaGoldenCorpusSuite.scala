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

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.mutable
import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.spark.SparkThrowable
import org.apache.spark.sql.{DataFrame, QueryTest, Row, SparkSession, SQLQueryTestHelper}
import org.apache.spark.sql.catalyst.analysis.{UnresolvedAlias, UnresolvedAttribute}
import org.apache.spark.sql.catalyst.expressions.{Alias, Literal, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.catalyst.plans.logical.{OneRowRelation, Project}
import org.apache.spark.sql.types.{BooleanType, DataType, DateType, DayTimeIntervalType, IntegerType, LongType, TimeType, YearMonthIntervalType}

/**
 * Spark's own date tests as a differential corpus (VARKA-81): the `select` statements of the
 * golden-file inputs under `sql-tests/inputs/`, the one corpus this project did not write.
 *
 * The files' statements are literal expressions, which fold before a physical operator exists,
 * so as written they reach no kernel. Each is rewritten: every literal of a type Varka reads -
 * a date, an int, a year-month interval, a bigint, a `TIME`, a day-time interval - becomes a
 * column `c<k>` of a fixture cached with the Arrow serializer, whose rows are the literals'
 * values, the same row with each column null in turn, and a row with every column null. The
 * rewritten statement then runs on both engines as a projection and again as a filter - the
 * expression itself where it is boolean, `IS NOT NULL` of it otherwise - and its outcome is
 * `agree` (the same rows), `error` (both raise the same condition) or a disagreement, the
 * finding the corpus exists to make. Strings stay literals: a string operand is a shape Varka
 * declines, which the verdict should show rather than a fixture paper over.
 *
 * `sql/varka/golden_corpus.json` records every statement, its rewrite, its plan verdict on each
 * form and its outcome, and the counts per file; the suite byte-compares it, as
 * `VarkaCoverageSuite` does `coverage.json`.
 *
 * {{{
 *   VARKA_GOLDEN_REGEN=true build/sbt -batch "sql/testOnly *VarkaGoldenCorpusSuite"
 * }}}
 */
class VarkaGoldenCorpusSuite extends QueryTest with VarkaSharedSessions with SQLQueryTestHelper
  with VarkaTestWatchdog {

  import VarkaGoldenCorpusSuite._

  /** The date-family inputs (`VARKA-81.md` 2), in the order the corpus lists them. */
  private val files = Seq("date.sql", "interval.sql", "extract.sql", "timestamp.sql",
    "datetime-formatting.sql", "datetime-parsing.sql", "datetime-special.sql")

  /** The types whose literals become fixture columns: the lanes Varka reads. */
  private def lane(t: DataType): Boolean = t match {
    case DateType | IntegerType | LongType => true
    case _: YearMonthIntervalType | _: DayTimeIntervalType | _: TimeType => true
    case _ => false
  }

  /** What one file produced, filled by its test and rendered by the last. */
  private val results = mutable.LinkedHashMap.empty[String, Seq[Entry]]

  private def regenerate: Boolean = sys.env.get("VARKA_GOLDEN_REGEN").contains("true")

  /**
   * A statement's select items with each lane-typed literal replaced by a column, and the
   * literals in the order the columns number them; None for a statement that is not a select
   * over no relation.
   */
  private[execution] def rewrite(statement: String): Option[(Seq[NamedExpression],
      Seq[Literal])] = {
    spark.sessionState.sqlParser.parsePlan(statement) match {
      case Project(items, OneRowRelation()) =>
        val literals = mutable.ArrayBuffer.empty[Literal]
        val rewritten = items.map(_.transformUp {
          case l: Literal if l.value != null && lane(l.dataType) =>
            literals += l
            UnresolvedAttribute(s"c${literals.size - 1}")
        }.asInstanceOf[NamedExpression])
        Some((rewritten, literals.toSeq))
      case _ => None
    }
  }

  /** The fixture's VALUES: the literals, each column null in turn, and every column null. */
  private def fixtureSql(literals: Seq[Literal]): String = {
    def nul(l: Literal) = s"CAST(NULL AS ${l.dataType.sql})"
    val full = literals.map(_.sql)
    val rows = full +: literals.indices.map(k => literals.zipWithIndex.map {
      case (l, i) => if (i == k) nul(l) else l.sql
    }) :+ literals.map(nul)
    val columns = literals.indices.map(k => s"c$k").mkString(", ")
    s"SELECT * FROM VALUES ${rows.map(_.mkString("(", ", ", ")")).mkString(", ")} " +
      s"AS v($columns)"
  }

  /** An item as SQL, its alias dropped: the rewrite renders the expression it computes. */
  private def render(item: NamedExpression): String = item match {
    case a: Alias => a.child.sql
    case UnresolvedAlias(child, _) => child.sql
    case other => other.sql
  }

  /** Both engines on one query: the outcome, and the Varka plan's verdict. */
  private def compare(query: String): (String, String) = {
    def attempt(session: SparkSession): Either[Throwable, (Seq[Row], DataFrame)] =
      try {
        val df = session.sql(query)
        Right((df.collect().toSeq, df))
      } catch {
        case NonFatal(e) => Left(e)
      }
    (attempt(disabledSpark), attempt(varkaSpark)) match {
      case (Left(b), Left(v)) =>
        val same = condition(b) == condition(v)
        (if (same) "error" else s"DISAGREE: errors ${condition(b)} and ${condition(v)}",
          "-")
      case (Left(b), Right(_)) => (s"DISAGREE: only the row engine raised ${condition(b)}", "-")
      case (Right(_), Left(v)) => (s"DISAGREE: only Varka raised ${condition(v)}", "-")
      case (Right((expected, _)), Right((_, df))) =>
        val verdict = classifyPlan(df.queryExecution.simpleString)
        val mismatch = QueryTest.getErrorMessageInCheckAnswer(df, expected, checkToRDD = false)
        val ran = verdict == "PLAIN" || expected.isEmpty || batchesOf(df) > 0
        val outcome = if (mismatch.isDefined) "DISAGREE: the answers differ"
          else if (!ran) "DISAGREE: the plan is Varka's and no batch reached its kernel"
          else "agree"
        (outcome, verdict)
    }
  }

  /**
   * The batches a Varka kernel was given: the ones it served and the ones it declined to the row
   * engine, which a `make_date` past its year limits does by design.
   */
  private def batchesOf(df: DataFrame): Long = df.queryExecution.executedPlan.collect {
    case node if node.metrics.contains("numVarkaBatches") =>
      node.metrics("numVarkaBatches").value +
        node.metrics.get("numFallbackBatchesDeclined").map(_.value).getOrElse(0L)
  }.sum

  /** One statement, harvested, rewritten and run. */
  private def run(file: String, statement: String, index: Int): Entry = {
    val written = statement.trim.replaceAll("\\s+", " ")
    if (!written.toLowerCase(java.util.Locale.ROOT).startsWith("select")) {
      return Entry(file, written, kind = "skipped: not a select")
    }
    val parsed = try rewrite(statement) catch {
      case NonFatal(e) => return Entry(file, written, kind = s"does not parse: ${condition(e)}")
    }
    parsed match {
      case None => Entry(file, written, kind = "reads a relation: not rewritten")
      case Some((items, literals)) if literals.isEmpty =>
        val (outcome, verdict) = compare(statement)
        Entry(file, written, kind = "nothing to rewrite", projection = verdict,
          projectionOutcome = outcome)
      case Some((items, literals)) =>
        val view = s"varka_golden_$index"
        val select = items.zipWithIndex.map { case (e, i) => s"${render(e)} AS v$i" }
        val projection = s"SELECT ${select.mkString(", ")} FROM $view"
        val columns = literals.indices.map(k => s"c$k").mkString(", ")
        try {
          for (session <- Seq(disabledSpark, varkaSpark)) {
            session.sql(fixtureSql(literals)).createOrReplaceTempView(view)
            session.catalog.cacheTable(view)
          }
          val (pOutcome, pVerdict) = compare(projection)
          // The filter form: each item where it is boolean, IS NOT NULL of it otherwise, so the
          // filter kernels and their null rule meet the corpus too.
          val conditions = try {
            val types = disabledSpark.sql(projection).schema.fields.map(_.dataType)
            items.zip(types).map { case (e, t) =>
              if (t == BooleanType) s"(${render(e)})" else s"(${render(e)}) IS NOT NULL"
            }
          } catch {
            case NonFatal(_) => Seq.empty
          }
          val (fOutcome, fVerdict) = if (conditions.isEmpty) ("-", "-")
            else compare(s"SELECT $columns FROM $view WHERE ${conditions.mkString(" AND ")}")
          Entry(file, written, kind = "rewritten",
            rewritten = s"SELECT ${items.map(render).mkString(", ")}",
            columns = literals.map(l => l.dataType.simpleString), projection = pVerdict,
            projectionOutcome = pOutcome, filter = fVerdict, filterOutcome = fOutcome)
        } catch {
          case NonFatal(e) => Entry(file, written, kind = s"rewrite failed: ${condition(e)}",
            rewritten = s"SELECT ${items.map(render).mkString(", ")}")
        } finally {
          for (session <- Seq(disabledSpark, varkaSpark)) {
            session.catalog.uncacheTable(view)
            session.catalog.dropTempView(view)
          }
        }
    }
  }

  private def harvest(file: String): Seq[Entry] = {
    val path = getWorkspaceFilePath("sql", "core", "src", "test", "resources", "sql-tests",
      "inputs", file)
    val input = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    val (comments, code) = splitCommentsAndCodes(input)
    getQueries(code, comments, Seq.empty).zipWithIndex.map { case (q, i) => run(file, q, i) }
  }

  test("the plan classifier is DateSurfaceBenchmark's") {
    val fused = Seq("== Physical Plan ==",
      "VarkaColumnarToRow [year(d#1) AS y#2]",
      "+- Scan In-memory table t [d#1]").mkString("\n")
    val residualFilter = Seq("== Physical Plan ==",
      "*(1) Filter (year(d#1) = 2021)",
      "+- VarkaColumnarToRow [d#1]",
      "   +- Scan In-memory table t [d#1]").mkString("\n")
    val countedEmpty = Seq("== Physical Plan ==",
      "+- *(1) Project",
      "   +- VarkaFilterColumnarToRow (isnotnull(d#1))",
      "      +- Scan In-memory table t [d#1]").mkString("\n")
    val plain = Seq("== Physical Plan ==",
      "*(1) Filter (year(d#1) = 2021)",
      "+- Scan In-memory table t [d#1]").mkString("\n")
    assert(classifyPlan(fused) === "FUSED")
    assert(classifyPlan(residualFilter) === "PARTIAL")
    assert(classifyPlan(countedEmpty) === "FUSED")
    assert(classifyPlan(plain) === "PLAIN")
  }

  test("the rewrite: lane-typed literals become columns, strings stay") {
    val (items, literals) = rewrite("select make_date(2019, 1, 1)").get
    assert(literals.map(_.dataType) === Seq(IntegerType, IntegerType, IntegerType))
    assert(render(items.head).toLowerCase(java.util.Locale.ROOT).contains("c0"))
    val (_, sub) = rewrite("select date_sub('2011-11-11', 1)").get
    assert(sub.map(_.dataType) === Seq(IntegerType))
    val (next, day) = rewrite("select next_day(date'2011-11-11', 'MONDAY')").get
    assert(day.map(_.dataType) === Seq(DateType))
    assert(render(next.head).contains("MONDAY"))
    assert(rewrite("select * from date_view").isEmpty)
  }

  files.foreach { file =>
    test(s"$file: every select, rewritten, agrees with the row engine") {
      val entries = harvest(file)
      results(file) = entries
      val disagreements = entries.filter(e =>
        e.projectionOutcome.startsWith("DISAGREE") || e.filterOutcome.startsWith("DISAGREE"))
      assert(disagreements.isEmpty, s"$file: ${disagreements.size} statements disagree:\n" +
        disagreements.map(e => s"  ${e.statement}\n    projection ${e.projectionOutcome}; " +
          s"filter ${e.filterOutcome}").mkString("\n"))
    }
  }

  test("sql/varka/golden_corpus.json carries every file's entries") {
    assume(results.size == files.size, "run with every file's test to compare the whole file")
    val rendered = renderCorpus(files.map(f => f -> results(f)))
    val path = getWorkspaceFilePath("sql", "varka", "golden_corpus.json")
    if (regenerate) {
      Files.write(path, rendered.getBytes(StandardCharsets.UTF_8))
    } else {
      val committed = if (Files.exists(path)) Files.readString(path) else ""
      assert(committed == rendered, "sql/varka/golden_corpus.json differs from the corpus run; " +
        "regenerate with VARKA_GOLDEN_REGEN=true build/sbt -batch " +
        "\"sql/testOnly *VarkaGoldenCorpusSuite\"")
    }
  }
}

private object VarkaGoldenCorpusSuite {

  /** One harvested statement and what became of it. */
  case class Entry(file: String, statement: String, kind: String, rewritten: String = "",
      columns: Seq[String] = Nil, projection: String = "-", projectionOutcome: String = "-",
      filter: String = "-", filterOutcome: String = "-")

  /** An error's condition, which is what the two engines must agree on, else its class. */
  def condition(e: Throwable): String = e match {
    case s: SparkThrowable if s.getCondition != null => s.getCondition
    case other => other.getClass.getSimpleName
  }

  private val residualAbove =
    "^[\\s+:|-]*(?:\\*\\(\\d+\\) )?(?:Filter |Project \\[[^\\]]+\\])".r

  /**
   * `DateSurfaceBenchmark.classifyPlan`, ported (the bench module is not on this classpath):
   * PLAIN when no line names a Varka node, PARTIAL when a row-engine `Filter` or a non-empty
   * `Project` sits above the first that does, FUSED otherwise.
   */
  def classifyPlan(explain: String): String = {
    val lines = explain.split("\n")
    val varkaAt = lines.indexWhere(_.contains("Varka"))
    if (varkaAt < 0) "PLAIN"
    else if (lines.take(varkaAt).exists(l => residualAbove.findFirstIn(l).isDefined)) "PARTIAL"
    else "FUSED"
  }

  /** The corpus file: per file the counts, then every entry. */
  def renderCorpus(byFile: Seq[(String, Seq[Entry])]): String = {
    def ordered(pairs: (String, Any)*): java.util.LinkedHashMap[String, Any] = {
      val m = new java.util.LinkedHashMap[String, Any]()
      pairs.foreach { case (k, v) => m.put(k, v) }
      m
    }
    import scala.jdk.CollectionConverters._
    val files = byFile.map { case (file, entries) =>
      def count(f: Entry => Boolean) = entries.count(f)
      file -> ordered(
        "statements" -> entries.size,
        "selects" -> count(e => !e.kind.startsWith("skipped")),
        "rewritten" -> count(_.kind == "rewritten"),
        "projection_fused" -> count(_.projection == "FUSED"),
        "projection_partial" -> count(_.projection == "PARTIAL"),
        "projection_plain" -> count(_.projection == "PLAIN"),
        "filter_fused" -> count(_.filter == "FUSED"),
        "filter_partial" -> count(_.filter == "PARTIAL"),
        "errors" -> count(e => e.projectionOutcome == "error"),
        "not_run" -> count(e => e.projection == "-" && e.projectionOutcome == "-"),
        "entries" -> entries.map(e => ordered(
          "statement" -> e.statement, "kind" -> e.kind, "rewritten" -> e.rewritten,
          "columns" -> e.columns.asJava, "projection" -> e.projection,
          "projection_outcome" -> e.projectionOutcome, "filter" -> e.filter,
          "filter_outcome" -> e.filterOutcome)).asJava)
    }
    val doc = ordered(
      "generated_by" -> ("VarkaGoldenCorpusSuite (VARKA-81); regenerate with " +
        "VARKA_GOLDEN_REGEN=true build/sbt -batch \"sql/testOnly *VarkaGoldenCorpusSuite\""),
      "files" -> ordered(files: _*))
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n"
  }
}
