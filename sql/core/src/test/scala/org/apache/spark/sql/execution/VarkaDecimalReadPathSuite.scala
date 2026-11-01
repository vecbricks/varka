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

import java.nio.{ByteBuffer, ByteOrder}

import scala.util.control.NonFatal

import org.apache.arrow.vector.{BaseFixedWidthVector, DateDayVector, DecimalVector, IntVector}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType}

import org.apache.spark.{Partition, SparkThrowable, TaskContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, Cast,
  DateAdd, EvalMode, Expression}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.execution.columnar.{ArrowCachedBatch, InMemoryTableScanExec}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DateType, Decimal, DecimalType, IntegerType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch}

/**
 * What Spark's ANSI and TRY casts of a `DECIMAL(p, s)` with `p <= 18` to `INT` answer over an
 * Arrow-cached table, on each path a query can take to the cast, with Varka off and on
 * (VARKA-365).
 *
 * `Decimal.roundToInt` has two rules, chosen by the value's backing. A long-backed value
 * truncates its unscaled long and checks the result exactly; a `BigDecimal`-backed one converts
 * to a double and range-checks its floor and ceiling. They part on the two values this suite
 * reads: `0.999999999999999999` as `DECIMAL(18, 18)`, which truncates to 0 but rounds to the
 * double 1.0, and `2147483647.99999999` as `DECIMAL(18, 8)`, which truncates to `Int.MaxValue`
 * but rounds to the double 2^31, past it.
 *
 * The backing is decided by the path, not by the data. `ArrowColumnVector` builds a
 * `BigDecimal`-backed value at every precision, and an `UnsafeRow` holding a decimal of
 * precision 18 or less reads it back long-backed. With Varka off, every consumer of the Arrow
 * cache here reads rows: `InMemoryTableScanExec` serves rows to a row consumer even with the
 * vectorized reader on, through the cache's row reader, which writes the unscaled long into an
 * `UnsafeRow`, or, for a scan that selects a column the row reader cannot read, through an
 * `UnsafeProjection` over the batch. So vanilla Spark follows the exact rule on every path here.
 * With Varka on, a Varka node reads the batches through `ArrowColumnVector`, and every row-by-row
 * read of a batch - residual outputs, fallbacks, the filters' copies, row and columnar output
 * alike - goes through `VarkaRowView`, which reads a small decimal as vanilla's row path does; so
 * Varka follows the exact rule on every path too, as `VARKA-365.md` 11 and 13 record. Over a
 * columnar input that is not the Arrow cache, vanilla's `ColumnarToRowExec` reads through the
 * accessor and follows the double rule, and so does Varka, which reads such a batch's own rows.
 *
 * Two more tables hold slots Spark's own writer never writes, planted in the cached bytes: `a`'s
 * low word is 10^18, a value with more digits than its precision, and `b`'s high word is not its
 * low word's sign extension. The row reader takes the low words with no check, so a scan it
 * reads answers 1 and `Int.MaxValue`, and Varka on answers the same on every path. The
 * `UnsafeProjection` reads through the accessor, which raises `NUMERIC_VALUE_OUT_OF_RANGE` on
 * either slot, so a scan that also selects an array raises; there Varka on still answers the low
 * words, the known limit `VARKA-365.md` 13 records.
 *
 * Every cell runs the two LEGACY casts and the four ANSI and TRY casts and names the reading all
 * six followed; a cell whose answers follow none of its table's readings fails with what it saw.
 */
class VarkaDecimalReadPathSuite extends QueryTest with VarkaSharedSessions
  with VarkaTestWatchdog {

  import VarkaDecimalReadPathSuite._

  private val ExactRule = "the exact rule"
  private val DoubleRule = "the double rule"
  private val LowWords = "the row reader's low words"
  private val Raises = "the accessor's NUMERIC_VALUE_OUT_OF_RANGE"
  private val NoRow = "no row"
  private val OutOfRange = "NUMERIC_VALUE_OUT_OF_RANGE.WITHOUT_SUGGESTION"

  /** A named set of answers: the LEGACY casts of `a` and `b`, then their ANSI and TRY casts. */
  private case class Reading(name: String, answers: Seq[String])

  private val raises = Reading(Raises, Seq.fill(6)(OutOfRange))

  /**
   * A cached table: the readings a cast as an output column and a cast tested in a filter can
   * follow over it, and the values that filter tests the cast against (those of its first
   * reading), so that the query returns `i` (3) when the row passes.
   */
  private case class Table(
      name: String,
      inList: String,
      asValue: Seq[Reading],
      asFilter: Seq[Reading])

  private val valid = (name: String) => Table(name, "(0, 2147483647)",
    asValue = Seq(
      Reading(ExactRule, Seq("0", "2147483647", "0", "2147483647", "0", "2147483647")),
      Reading(DoubleRule, Seq("0", "2147483647", "1", "CAST_OVERFLOW", "1", "null"))),
    asFilter = Seq(
      Reading(ExactRule, Seq.fill(6)("3")),
      Reading(DoubleRule, Seq("3", "3", NoRow, "CAST_OVERFLOW", NoRow, NoRow))))

  private val malformed = (name: String) => Table(name, "(1, 2147483647)",
    asValue = Seq(
      Reading(LowWords, Seq("1", "2147483647", "1", "2147483647", "1", "2147483647")),
      raises),
    asFilter = Seq(Reading(LowWords, Seq.fill(6)("3")), raises))

  /** `a` and `b` the two decimals, `d` and `i` a date and an int that `date_add(d, i)` fuses. */
  private val fused = valid("rp_fused")
  /** With `arr` an array instead of `d` and `i`, which the cache's row reader cannot read. */
  private val array = valid("rp_array")
  /**
   * `rp_fused`'s decimals with the two malformed slots planted, beside another date: a table
   * whose plan matched `rp_fused`'s would share its cache, and so its planted bytes.
   */
  private val bad = malformed("rp_bad")
  /** The same, beside an array. */
  private val badArray = malformed("rp_bad_array")

  private val fixtures = Seq(
    fused.name -> """SELECT CAST(a AS DECIMAL(18, 18)) AS a, CAST(b AS DECIMAL(18, 8)) AS b,
      |  d, i FROM VALUES (0.999999999999999999, 2147483647.99999999, DATE'2024-01-01', 3)
      |AS v(a, b, d, i)""".stripMargin,
    array.name -> """SELECT CAST(a AS DECIMAL(18, 18)) AS a, CAST(b AS DECIMAL(18, 8)) AS b,
      |  arr FROM VALUES (0.999999999999999999, 2147483647.99999999, array(1, 2))
      |AS v(a, b, arr)""".stripMargin,
    bad.name -> """SELECT CAST(a AS DECIMAL(18, 18)) AS a, CAST(b AS DECIMAL(18, 8)) AS b,
      |  d, i FROM VALUES (0.999999999999999999, 2147483647.99999999, DATE'2024-01-02', 3)
      |AS v(a, b, d, i)""".stripMargin,
    badArray.name -> """SELECT CAST(a AS DECIMAL(18, 18)) AS a, CAST(b AS DECIMAL(18, 8)) AS b,
      |  d, i, arr FROM VALUES (0.999999999999999999, 2147483647.99999999, DATE'2024-01-01',
      |  3, array(1, 2)) AS v(a, b, d, i, arr)""".stripMargin)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    for (session <- Seq(spark, varkaSpark); (name, query) <- fixtures) {
      session.sql(query).createOrReplaceTempView(name)
      session.catalog.cacheTable(name)
    }
    Seq(bad, badArray).foreach(t => plantMalformedSlots(t.name))
  }

  /**
   * Rewrites `a`'s and `b`'s slots in the cached bytes of `table`, in every session's cache of
   * it, and checks that the rewrite holds. The cache keeps its blocks deserialized and its Arrow
   * messages uncompressed (the default codec), so the rewrite is what every later scan reads.
   */
  private def plantMalformedSlots(table: String): Unit = {
    val caches = Seq(spark, varkaSpark).map { session =>
      val plan = session.table(table).queryExecution.executedPlan
      collectFirst(plan) {
        case scan: InMemoryTableScanExec => scan.relation.cacheBuilder.cachedColumnBuffers
      }.getOrElse(fail(s"$table is not read from the cache:\n${plan.treeString}"))
    }.distinctBy(_.id)
    for (buffers <- caches) {
      buffers.count()
      val planted = spark.sparkContext.longAccumulator
      buffers.foreach {
        case batch: ArrowCachedBatch => planted.add(rewrite(batch.arrowData, Plants))
        case other => throw new IllegalStateException(s"not an Arrow cached batch: $other")
      }
      assert(planted.value === Plants.size, s"$table: ${planted.value} slots planted")
      val found = spark.sparkContext.longAccumulator
      buffers.foreach {
        case batch: ArrowCachedBatch =>
          found.add(rewrite(batch.arrowData, Plants.map { case (_, to) => to -> to }))
        case _ =>
      }
      assert(found.value === Plants.size, s"$table: the planted slots did not stay")
    }
  }

  /**
   * The answer of a query of at most one row: its value, `null`, `no row`, or the condition it
   * raised.
   */
  private def answer(query: => Seq[String]): String =
    try {
      val rows = query
      assert(rows.length <= 1, s"${rows.length} rows")
      rows.headOption.getOrElse(NoRow)
    } catch {
      case e: Exception =>
        Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).collectFirst {
          case t: SparkThrowable if t.getCondition != null => t.getCondition
        }.getOrElse(throw e)
    }

  /**
   * How a cell reads its query: as rows (the query as planned), or as the batches of the plan's
   * top Varka node rebuilt with columnar output - the node a columnar consumer, such as a noop
   * write, keeps in the plan - reading the column the query returns.
   */
  private sealed trait Read {
    /**
     * Runs `query` and returns the plan whose metrics and scans the cell checks. A query that
     * raises still returns its plan: what it answers is the cell's to measure.
     */
    def probe(session: SparkSession, query: String): SparkPlan
    def values(session: SparkSession, query: String): Seq[String]
  }

  private object AsRows extends Read {
    override def probe(session: SparkSession, query: String): SparkPlan = {
      val df = session.sql(query)
      try df.collect() catch {
        case NonFatal(_) =>
      }
      df.queryExecution.executedPlan
    }
    override def values(session: SparkSession, query: String): Seq[String] =
      session.sql(query).collect().map(r => String.valueOf(r.get(0))).toSeq
  }

  private object AsBatches extends Read {
    /** The plan's top Varka node with columnar output, and the ordinal of the column to read. */
    private def columnar(session: SparkSession, query: String): (SparkPlan, Int) = {
      val df = session.sql(query)
      val returned = df.queryExecution.executedPlan.output.head.name
      // Built under the session, which a plan node takes as its own when it is made.
      val node = session.withActive(collectFirst(df.queryExecution.executedPlan) {
        case v: VarkaColumnarToRowExec => VarkaProjectExec(v.projectList, v.child, v.sessionEmit)
        case f: VarkaFilterColumnarToRowExec =>
          VarkaFilterExec(f.condition, f.child, f.wholeCondition, f.sessionEmit)
      }).getOrElse(fail(s"no Varka node in:\n${df.queryExecution.executedPlan.treeString}"))
      val ordinal = node.output.indexWhere(_.name == returned)
      assert(ordinal >= 0, s"$returned is not an output of\n${node.treeString}")
      (node, ordinal)
    }

    private def read(session: SparkSession, node: SparkPlan, ordinal: Int): Seq[String] = {
      val dataType = node.output(ordinal).dataType
      // Only the returned column is read: a whole-row copy would read every decimal through
      // the accessor.
      session.withActive(node.executeColumnar().mapPartitions { batches =>
        batches.flatMap { batch =>
          (0 until batch.numRows()).map { r =>
            val row = batch.getRow(r)
            if (row.isNullAt(ordinal)) "null" else String.valueOf(row.get(ordinal, dataType))
          }
        }
      }.collect().toSeq)
    }

    override def probe(session: SparkSession, query: String): SparkPlan = {
      val (node, ordinal) = columnar(session, query)
      try read(session, node, ordinal) catch {
        case NonFatal(_) =>
      }
      node
    }
    override def values(session: SparkSession, query: String): Seq[String] = {
      val (node, ordinal) = columnar(session, query)
      read(session, node, ordinal)
    }
  }

  /**
   * A read path: the session, the table, the SQL configurations it sets, how a query is built
   * around the cast, whether every Varka batch is declined, how the query is read, and the check
   * that the plan takes the path the cell names.
   */
  private case class Path(
      session: () => SparkSession,
      table: Table,
      confs: Seq[(String, String)],
      shape: QueryShape,
      declineAll: Boolean,
      check: SparkPlan => Unit,
      read: Read = AsRows)

  /** A query around a cast, given the table, and whether the cast is tested in a filter. */
  private case class QueryShape(build: (String, Table) => String, inFilter: Boolean)

  private def withConfs[T](session: SparkSession, confs: Seq[(String, String)])(body: => T): T = {
    val before = confs.map { case (k, _) => k -> session.conf.getOption(k) }
    confs.foreach { case (k, v) => session.conf.set(k, v) }
    try body finally before.foreach {
      case (k, Some(v)) => session.conf.set(k, v)
      case (k, None) => session.conf.unset(k)
    }
  }

  /**
   * Runs the six casts on `path` and returns the reading they followed. The plan is checked on a
   * TRY query, which raises on neither rule over the valid tables.
   */
  private def readingOf(path: Path): String = {
    val session = path.session()
    def query(cast: String): String = path.shape.build(cast, path.table)
    def run(cast: String): String = answer(path.read.values(session, query(cast)))
    val readings = if (path.shape.inFilter) path.table.asFilter else path.table.asValue
    VarkaColumnarToRowExec.setDeclineKernelForTesting(path.declineAll)
    try withConfs(session, path.confs) {
      val probe = path.read.probe(session, query("try_cast(a AS INT)"))
      path.check(probe)
      assert(cachedReads(probe) == Seq(path.table.name),
        s"the query does not read the cached ${path.table.name}")
      val legacy = withConfs(session, Seq(SQLConf.ANSI_ENABLED.key -> "false")) {
        Seq(run("CAST(a AS INT)"), run("CAST(b AS INT)"))
      }
      val ansi = withConfs(session, Seq(SQLConf.ANSI_ENABLED.key -> "true")) {
        Seq("CAST(a AS INT)", "CAST(b AS INT)", "try_cast(a AS INT)", "try_cast(b AS INT)")
          .map(run)
      }
      val seen = legacy ++ ansi
      readings.find(_.answers == seen).map(_.name)
        .getOrElse(fail(s"the casts answered $seen, by none of ${readings.map(_.name)}"))
    } finally {
      VarkaColumnarToRowExec.setDeclineKernelForTesting(false)
    }
  }

  /**
   * A plan whose scan selects an array, which sends vanilla's row path through its
   * `UnsafeProjection` over the batch rather than the row reader, and `batches` of the plan.
   */
  private def selectsArray(batches: SparkPlan => Unit): SparkPlan => Unit = { plan =>
    assert(collect(plan) { case scan: InMemoryTableScanExec => scan.output }.flatten
      .exists(_.name == "arr"), s"expected the scan to select arr:\n${plan.treeString}")
    batches(plan)
  }

  /** A plan that reads the cache as rows: no Varka node and no columnar-to-row transition. */
  private def rowRead(plan: SparkPlan): Unit = {
    assertNotFused(plan)
    assert(find(plan)(_.isInstanceOf[ColumnarToRowTransition]).isEmpty,
      s"expected the cache read as rows:\n${plan.treeString}")
  }

  private def varkaBatches(plan: SparkPlan): Long = {
    assertFused(plan)
    collect(plan) {
      case node if node.metrics.contains("numVarkaBatches") => node.metrics("numVarkaBatches").value
    }.sum
  }

  private val served: SparkPlan => Unit =
    p => assert(varkaBatches(p) > 0, s"the kernel served no batch:\n${p.treeString}")
  private val declined: SparkPlan => Unit =
    p => assert(varkaBatches(p) == 0, s"the kernel served a batch:\n${p.treeString}")
  /** No batch served, and the Varka filter's decimal-slot guard declined at least one. */
  private val declinedBySlots: SparkPlan => Unit = { p =>
    declined(p)
    val bySlots = collect(p) {
      case f: VarkaFilterExec => f.metrics.get("numDeclinedBatches[decimal-slot]").map(_.value)
    }.flatten.sum
    assert(bySlots > 0, s"the filter's decimal-slot guard declined no batch:\n${p.treeString}")
  }
  /** A filter split into a row `FilterExec` over a Varka filter, and `batches` of the plan. */
  private def split(batches: SparkPlan => Unit): SparkPlan => Unit = { plan =>
    assert(find(plan) {
      case f: FilterExec => find(f.child)(_.isInstanceOf[VarkaFilterExecBase]).isDefined
      case _ => false
    }.isDefined, s"expected a FilterExec over a Varka filter:\n${plan.treeString}")
    batches(plan)
  }

  /** A Varka projection over a Varka filter with columnar output, and `batches` of the plan. */
  private def overFilter(batches: SparkPlan => Unit): SparkPlan => Unit = { plan =>
    assert(find(plan) {
      case v: VarkaColumnarToRowExec => v.child.isInstanceOf[VarkaFilterExec]
      case _ => false
    }.isDefined, s"expected a Varka projection over a Varka filter:\n${plan.treeString}")
    batches(plan)
  }

  private val alone = QueryShape((cast, t) => s"SELECT $cast AS r FROM ${t.name}", false)
  /** The same beside the array, which the scan then selects. */
  private val besideArray =
    QueryShape((cast, t) => s"SELECT $cast AS r, size(arr) AS n FROM ${t.name}", false)
  private val besideFused =
    QueryShape((cast, t) => s"SELECT $cast AS r, date_add(d, i) AS f FROM ${t.name}", false)
  private val besideFusedTwice = QueryShape((cast, t) =>
    s"SELECT $cast AS r, a AS a2, b AS b2, date_add(d, i) AS f FROM ${t.name}", false)
  /** The same beside an array, which the scan then selects. */
  private val besideFusedAndArray = QueryShape((cast, t) =>
    s"SELECT $cast AS r, date_add(d, i) AS f, size(arr) AS n FROM ${t.name}", false)
  /**
   * The cast in a conjunct the filter kernel declines, beside one it fuses: the rule splits the
   * condition into a `FilterExec` over a Varka filter, whose fallback evaluates the whole
   * condition.
   */
  private val leftoverFilter = QueryShape((cast, t) =>
    s"SELECT i FROM ${t.name} WHERE date_add(d, 30) > DATE'2020-07-01' " +
      s"AND $cast IN ${t.inList}", true)
  /**
   * The cast beside a fused output, over a filter on `b` that fuses whole, so that the filter
   * hands the projection batches: the filter kernel reads `b`'s slots.
   */
  private val overFilterOnB = QueryShape((cast, t) =>
    s"SELECT $cast AS r, date_add(d, i) AS f FROM ${t.name} WHERE b > 0", false)

  private val paths: Seq[(String, Path, String)] = Seq(
    ("Varka off, the vectorized reader on, the cache read as rows by the row reader",
      Path(() => spark, fused, Nil, alone, declineAll = false, rowRead), ExactRule),
    ("Varka off, the vectorized reader off",
      Path(() => spark, fused, Seq(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key -> "false"),
        alone, declineAll = false, rowRead), ExactRule),
    ("Varka off, the row reader's columnar fallback for a scan that selects an array",
      Path(() => spark, array, Nil, besideArray, declineAll = false, selectsArray(rowRead)),
      ExactRule),
    ("Varka off, without whole-stage code",
      Path(() => spark, fused, Seq(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false"),
        alone, declineAll = false, rowRead), ExactRule),
    ("Varka on, a cast nothing fuses beside, the cache read as rows",
      Path(() => varkaSpark, fused, Nil, alone, declineAll = false, rowRead), ExactRule),
    ("Varka on, a residual cast beside a fused output, the kernel serving the batch",
      Path(() => varkaSpark, fused, Nil, besideFused, declineAll = false, served), ExactRule),
    ("Varka on, a residual cast beside a column read twice, the kernel serving the batch",
      Path(() => varkaSpark, fused, Nil, besideFusedTwice, declineAll = false, served),
      ExactRule),
    ("Varka on, the fallback over the batch's own rows",
      Path(() => varkaSpark, fused, Nil, besideFused, declineAll = true, declined),
      ExactRule),
    ("Varka on, the fallback over a copied row, a column read twice",
      Path(() => varkaSpark, fused, Nil, besideFusedTwice, declineAll = true, declined),
      ExactRule),
    ("Varka off, the cast in a filter beside a fusable conjunct",
      Path(() => spark, fused, Nil, leftoverFilter, declineAll = false, rowRead), ExactRule),
    ("Varka on, the cast in a filter left beside a fused conjunct, the kernel serving the batch",
      Path(() => varkaSpark, fused, Nil, leftoverFilter, declineAll = false,
        split(served)), ExactRule),
    ("Varka on, the cast in a filter left beside a fused conjunct, the fallback",
      Path(() => varkaSpark, fused, Nil, leftoverFilter, declineAll = true,
        split(declined)), ExactRule),
    ("Varka on, a residual cast over a fused filter, both kernels serving the batch",
      Path(() => varkaSpark, fused, Nil, overFilterOnB, declineAll = false,
        overFilter(served)), ExactRule),
    ("Varka on, columnar out, a residual cast beside a fused output, the kernel serving",
      Path(() => varkaSpark, fused, Nil, besideFused, declineAll = false, served, AsBatches),
      ExactRule),
    ("Varka on, columnar out, the projection's fallback",
      Path(() => varkaSpark, fused, Nil, besideFused, declineAll = true, declined, AsBatches),
      ExactRule),
    ("Varka on, columnar out, the filter's fallback over its whole condition",
      Path(() => varkaSpark, fused, Nil, leftoverFilter, declineAll = true, declined,
        AsBatches), ExactRule),

    ("malformed slots: Varka off, the cache read as rows by the row reader",
      Path(() => spark, bad, Nil, alone, declineAll = false, rowRead), LowWords),
    ("malformed slots: Varka off, the cast beside a fusable output",
      Path(() => spark, bad, Nil, besideFused, declineAll = false, rowRead), LowWords),
    ("malformed slots: Varka off, the cast in a filter beside a fusable conjunct",
      Path(() => spark, bad, Nil, leftoverFilter, declineAll = false, rowRead), LowWords),
    ("malformed slots: Varka off, the cast over a filter on the decimal",
      Path(() => spark, bad, Nil, overFilterOnB, declineAll = false, rowRead), LowWords),
    ("malformed slots: Varka on, a cast nothing fuses beside, the cache read as rows",
      Path(() => varkaSpark, bad, Nil, alone, declineAll = false, rowRead), LowWords),
    ("malformed slots: Varka on, a residual cast beside a fused output, the kernel serving",
      Path(() => varkaSpark, bad, Nil, besideFused, declineAll = false, served), LowWords),
    ("malformed slots: Varka on, the fallback over the batch's own rows",
      Path(() => varkaSpark, bad, Nil, besideFused, declineAll = true, declined), LowWords),
    ("malformed slots: Varka on, the fallback over a copied row, a column read twice",
      Path(() => varkaSpark, bad, Nil, besideFusedTwice, declineAll = true, declined),
      LowWords),
    ("malformed slots: Varka on, the cast in a filter left beside a fused conjunct, the kernel " +
      "serving and the selected rows copied",
      Path(() => varkaSpark, bad, Nil, leftoverFilter, declineAll = false, split(served)),
      LowWords),
    ("malformed slots: Varka on, the cast in a filter left beside a fused conjunct, the fallback",
      Path(() => varkaSpark, bad, Nil, leftoverFilter, declineAll = true, split(declined)),
      LowWords),
    ("malformed slots: Varka on, a filter on the decimal, its kernel declining the slots and " +
      "its columnar fallback answering",
      Path(() => varkaSpark, bad, Nil, overFilterOnB, declineAll = false,
        overFilter(declinedBySlots)), LowWords),
    ("malformed slots: Varka on, columnar out, a residual cast beside a fused output, the " +
      "kernel serving",
      Path(() => varkaSpark, bad, Nil, besideFused, declineAll = false, served, AsBatches),
      LowWords),
    ("malformed slots: Varka on, columnar out, the projection's fallback",
      Path(() => varkaSpark, bad, Nil, besideFused, declineAll = true, declined, AsBatches),
      LowWords),
    ("malformed slots: Varka on, columnar out, the filter's fallback over its whole condition",
      Path(() => varkaSpark, bad, Nil, leftoverFilter, declineAll = true, declined, AsBatches),
      LowWords),

    ("malformed slots beside an array: Varka off, the projection over the batch",
      Path(() => spark, badArray, Nil, besideFusedAndArray, declineAll = false,
        selectsArray(rowRead)), Raises),
    // The known limit: Varka's view takes the row reader's low word whatever else the scan
    // selects, where vanilla's projection over the batch raises.
    ("malformed slots beside an array, the known limit: Varka on, a residual cast beside a " +
      "fused output",
      Path(() => varkaSpark, badArray, Nil, besideFusedAndArray, declineAll = false,
        selectsArray(served)), LowWords))

  paths.foreach { case (name, path, expected) =>
    test(s"$name: the decimal-to-INT casts follow $expected") {
      assert(readingOf(path) === expected)
    }
  }

  test("a columnar child that is not the Arrow cache: Varka on reads its rows as " +
      "ColumnarToRowExec does, by the double rule") {
    val child = spark.withActive(ArrowDecimalBatchPlan())
    assert(!VarkaColumnarRule.readsArrowCache(child))
    val Seq(a, b, d, i) = child.output
    def casts(mode: EvalMode.Value): Seq[Expression] =
      Seq(a, b).map(c => Cast(c, IntegerType, Option(conf.sessionLocalTimeZone), mode))
    val all = casts(EvalMode.LEGACY) ++ casts(EvalMode.ANSI) ++ casts(EvalMode.TRY)
    def projectList(cast: Expression): Seq[Alias] =
      Seq(Alias(cast, "r")(), Alias(DateAdd(d, i), "f")())
    def values(node: SparkPlan): Seq[String] =
      node.executeCollect().map(r => if (r.isNullAt(0)) "null" else r.getInt(0).toString).toSeq
    def rule(name: String): Seq[String] = fused.asValue.find(_.name == name).get.answers
    // Vanilla's plan: the projection fused with the transition into whole-stage code, which
    // reads each value from the vector's accessor.
    def vanillaPlan(cast: Expression): SparkPlan =
      CollapseCodegenStages().apply(ProjectExec(projectList(cast), ColumnarToRowExec(child)))
    val vanilla = all.map(c => spark.withActive(answer(values(vanillaPlan(c)))))
    assert(vanilla === rule(DoubleRule))
    // Without whole-stage code the transition copies each row into an UnsafeRow first, which
    // reads the value back long-backed; Varka's node reads the batch's rows as they are.
    val interpreted = all.map { c =>
      spark.withActive(answer(values(ProjectExec(projectList(c), ColumnarToRowExec(child)))))
    }
    assert(interpreted === rule(ExactRule))
    for (declineAll <- Seq(false, true)) {
      VarkaColumnarToRowExec.setDeclineKernelForTesting(declineAll)
      try {
        val nodes =
          all.map(c => varkaSpark.withActive(VarkaColumnarToRowExec(projectList(c), child)))
        val seen = nodes.map(node => varkaSpark.withActive(answer(values(node))))
        assert(seen === vanilla, s"declineAll = $declineAll")
        val batches = nodes.map(_.metrics("numVarkaBatches").value).sum
        // A task that raises reports no metrics, so a served path counts at least one batch.
        assert(if (declineAll) batches == 0 else batches > 0,
          s"declineAll = $declineAll: $batches batches served")
      } finally {
        VarkaColumnarToRowExec.setDeclineKernelForTesting(false)
      }
    }
  }

  test("a decimal field at another scale reads its value through the accessor") {
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("rp-scale", 0, Long.MaxValue)
    try {
      def read(bitWidth: Int, fieldScale: Int, unscaled: Long, schema: DecimalType): Decimal = {
        val vector = new DecimalVector(new Field("x",
          FieldType.nullable(new ArrowType.Decimal(18, fieldScale, bitWidth)), null), allocator)
        try {
          vector.allocateNew(1)
          vector.setSafe(0, unscaled)
          vector.setValueCount(1)
          val batch = new ColumnarBatch(Array(new ArrowColumnVector(vector)), 1)
          val view = VarkaRowView.of(Seq(AttributeReference("x", schema)()))
          try view.rows(batch).next().getDecimal(0, schema.precision, schema.scale)
          finally view.release()
        } finally {
          vector.close()
        }
      }
      // VarkaLaneArrow.decimalFieldMatches declines both fields below, so the kernels send such
      // a batch to the row path, and the row reader's low word would be read at the wrong scale.
      val rescaled = read(128, 1, 125L, DecimalType(18, 2))
      assert(rescaled.toString === "12.50")
      assert(rescaled.toUnscaledLong === 1250L)
      assert(read(64, 2, 1250L, DecimalType(18, 2)).toString === "12.50")
      // The field the cache writes: the row reader's low word.
      assert(read(128, 2, 1250L, DecimalType(18, 2)).toString === "12.50")
    } finally {
      allocator.close()
    }
  }
}

private object VarkaDecimalReadPathSuite {

  /**
   * A columnar leaf that is not the Arrow cache, built as `TestColumnarBatchPlan` is: one batch
   * of Arrow vectors holding the row of `rp_fused` - the two decimals that part the rules, a date
   * and an int that `date_add(d, i)` fuses.
   */
  case class ArrowDecimalBatchPlan() extends LeafExecNode {
    override val output: Seq[Attribute] = Seq(
      AttributeReference("a", DecimalType(18, 18))(),
      AttributeReference("b", DecimalType(18, 8))(),
      AttributeReference("d", DateType)(),
      AttributeReference("i", IntegerType)())
    override def supportsColumnar: Boolean = true
    override protected def doExecute(): RDD[InternalRow] =
      throw new UnsupportedOperationException("columnar only")
    override protected def doExecuteColumnar(): RDD[ColumnarBatch] = {
      val fields = output
      new RDD[ColumnarBatch](sparkContext, Nil) {
        override protected def getPartitions: Array[Partition] =
          Array(new Partition { override def index: Int = 0 })
        override def compute(split: Partition, context: TaskContext): Iterator[ColumnarBatch] = {
          val allocator =
            ArrowUtils.rootAllocator.newChildAllocator("rp-columnar", 0, Long.MaxValue)
          val vectors = fields.map { f =>
            ArrowUtils.toArrowField(f.name, f.dataType, nullable = true, null)
              .createVector(allocator)
          }
          vectors.foreach(_.asInstanceOf[BaseFixedWidthVector].allocateNew(1))
          val Seq(a: DecimalVector, b: DecimalVector, d: DateDayVector, i: IntVector) = vectors
          a.setSafe(0, 999999999999999999L)
          b.setSafe(0, 214748364799999999L)
          d.setSafe(0, 19723)
          i.setSafe(0, 3)
          vectors.foreach(_.setValueCount(1))
          val batch = new ColumnarBatch(vectors.map(new ArrowColumnVector(_)).toArray, 1)
          context.addTaskCompletionListener[Unit] { _ =>
            batch.close()
            allocator.close()
          }
          Iterator.single(batch)
        }
      }
    }
  }

  /**
   * A 16-byte Arrow decimal slot: the low word at the offset the cache's row reader reads it
   * from (`ArrowCachedBatchSerializer.compactDecimalUnscaledOffset`), the high word beside it.
   */
  private def slot(low: Long, high: Long): Array[Byte] = {
    val order = ByteOrder.nativeOrder()
    val lowAt = if (order == ByteOrder.LITTLE_ENDIAN) 0 else 8
    val bytes = ByteBuffer.allocate(16).order(order)
    bytes.putLong(lowAt, low)
    bytes.putLong(8 - lowAt, high)
    bytes.array()
  }

  /**
   * The slots planted: `a` = 0.999999999999999999 as `DECIMAL(18, 18)` gets the low word 10^18,
   * nineteen digits, and `b` = 2147483647.99999999 as `DECIMAL(18, 8)` keeps its low word under
   * a high word of 1.
   */
  val Plants: Seq[(Array[Byte], Array[Byte])] = Seq(
    slot(999999999999999999L, 0L) -> slot(1000000000000000000L, 0L),
    slot(214748364799999999L, 0L) -> slot(214748364799999999L, 1L))

  /** Replaces every occurrence of each `from` in `data` by its `to`; returns how many. */
  def rewrite(data: Array[Byte], plants: Seq[(Array[Byte], Array[Byte])]): Long = {
    var count = 0L
    for ((from, to) <- plants; at <- 0 to data.length - from.length) {
      if ((0 until from.length).forall(k => data(at + k) == from(k))) {
        System.arraycopy(to, 0, data, at, to.length)
        count += 1
      }
    }
    count
  }
}
