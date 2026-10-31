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
import scala.util.{Random, Try}

import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.spark.SparkThrowable
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaCatalystGrammar,
  VarkaCoverageRows, VarkaShrinker}

/**
 * The pure parts of the random differential against vanilla Spark (VARKA-262): a case, its
 * draw, its rendering as SQL, the comparison of two outcomes, the shrinker and the reproducer
 * text. The sessions are the suites'; this holds nothing that needs a `SparkSession` but the
 * `outcome` of a query.
 *
 * A case is a list of outputs and a `WHERE` of conjuncts over twelve columns of random data.
 * The outputs and conjuncts are the `executable` column of `sql/varka/coverage.json`, so a row
 * added to the table is drawn here with no change to this file; half the compositions add nested
 * trees of neighbouring types from `VarkaCatalystGrammar` beside them (VARKA-298).
 */
object VarkaSparkFuzz {

  /** One row of the coverage table. */
  final case class CoverageRow(sql: String, executable: String, form: String)

  /**
   * A conjunct of the `WHERE`, possibly negated, or - rectified against a pivot row whose value of
   * it is NULL - tested `IS NULL`.
   */
  final case class Conjunct(sql: String, negated: Boolean, isNull: Boolean = false) {
    def text: String =
      if (isNull) s"($sql) IS NULL" else if (negated) s"NOT ($sql)" else s"($sql)"
  }

  /**
   * A case. `data` is a list of rows of twelve SQL literals, in the order of `columns`. `op` is
   * `AND` or `OR`, joining the conjuncts. A `pivot` is one of the rows `data` holds, which the
   * conjuncts have been rectified to select (VARKA-297): the filtered query must return it. A
   * `force` is the output forced to decline in a third run on Varka (VARKA-296). `trees` maps the
   * text of each output or conjunct drawn from `VarkaCatalystGrammar` to its tree (VARKA-298),
   * which is what lets the shrinker reduce inside it; an output or conjunct not in it is a coverage
   * row, reduced only as a whole.
   */
  final case class Case(
      outputs: Vector[String],
      conjuncts: Vector[Conjunct],
      op: String,
      data: Vector[Vector[String]],
      ansi: Boolean,
      pivot: Option[Vector[String]] = None,
      force: Option[Int] = None,
      trees: Map[String, VarkaCatalystGrammar.Node] = Map.empty) {

    def select: String = outputs.zipWithIndex.map { case (o, k) => s"$o AS c$k" }.mkString(", ")

    /** The `WHERE` expression, or None when there is no filter. */
    def where: Option[String] =
      if (conjuncts.isEmpty) None else Some(conjuncts.map(_.text).mkString(s" $op "))

    def query: String = querySql(select, where)

    def fixture: String = fixtureSql(data)
  }

  def querySql(select: String, where: Option[String]): String =
    s"SELECT $select FROM fz${where.map(w => s" WHERE $w").getOrElse("")}"

  /**
   * The twelve columns, as the fixtures of `VarkaCoverageDifferentialSuite` build them, except
   * that the year-month columns built from `y` are NULL where the cast or the construction would
   * overflow: a view that raises while it is built (the cached one computes every column, the
   * row engine prunes the unused ones) makes a composition compare which of two legitimate errors
   * came first, and about a tenth of the compositions did (VARKA-372). The hostile years are the
   * largest ones that still fit, so the interval operations of a composition are what overflow.
   * The TIMESTAMP_NTZ columns are derived, `ntz` from `d` at the time `t` and `ntz2` from `d2` at
   * `t2`, as the differential suite derives them; every date drawn here converts without
   * overflow. The floating columns are the differential suite's (VARKA-341), each picked by
   * the value of `l`, `l2`, `i` or `d2` modulo the lists' length. The cells drawn are what they
   * were; which coverage rows a seed's compositions take follows the table, and a stored
   * reproducer replays its own text.
   */
  def fixtureSql(data: Seq[Seq[String]]): String =
    fixtureRows(data.map(_.mkString("(", ", ", ")")))

  /** A row as the text of a `VALUES` row, which a reproducer stores its pivot as. */
  def rowText(row: Seq[String]): String = row.mkString("(", ", ", ")")

  /** The fixture over `VALUES` rows already written as text. */
  def fixtureRows(valueRows: Seq[String]): String = {
    val floating = VarkaCoverageDifferentialSuite.floatingColumns(
      Seq("CAST(pmod(l, %d) AS INT)", "CAST(pmod(l2, %d) AS INT)", "pmod(i, %d)",
        "pmod(unix_date(d2), %d)")
        .map(_.format(VarkaCoverageDifferentialSuite.floatingPicks)))
    s"""SELECT d, d2, i,
       |       CAST(m AS INTERVAL MONTH) AS ymm,
       |       CASE WHEN 12L * y BETWEEN -2147483648L AND 2147483647L
       |            THEN CAST(y AS INTERVAL YEAR) END AS ymy,
       |       CASE WHEN 12L * y + mm BETWEEN -2147483648L AND 2147483647L
       |            THEN make_ym_interval(y, mm) END AS ym,
       |       l, l2, CAST(t AS TIME(6)) AS t, CAST(t2 AS TIME(6)) AS t2, dt, dt2,
       |       CAST(d AS TIMESTAMP_NTZ) + (t - TIME'00:00:00') AS ntz,
       |       CAST(d2 AS TIMESTAMP_NTZ) + (t2 - TIME'00:00:00') AS ntz2,
       |       $floating
       |FROM VALUES ${valueRows.mkString(",\n  ")}
       |AS v(d, d2, i, m, y, mm, l, l2, t, t2, dt, dt2)""".stripMargin
  }

  /**
   * The coverage table's rows, except those that read a BOOLEAN column: the fixture's batches and
   * its oracle draw the twelve columns above, and a boolean input is a bitmap. Those rows are held
   * to the row engine by `VarkaCoverageDifferentialSuite` and to the reference by
   * `VarkaEmitterBoolSuite`.
   */
  def coverageRows(file: java.io.File): Seq[CoverageRow] =
    new ObjectMapper().readTree(file).get("expressions").elements().asScala.toSeq.map { e =>
      CoverageRow(e.get("sql").asText(), e.get("executable").asText(), e.get("form").asText())
    }.filterNot(r =>
      VarkaCoverageRows.readsBoolean(r.executable) || VarkaCoverageRows.readsNarrow(r.executable)
        || VarkaCoverageRows.readsDecimal(r.executable))

  /** The families the coverage table names, which the expected-errors list's rows cite. */
  def coverageFamilies(file: java.io.File): Set[String] =
    new ObjectMapper().readTree(file).get("expressions").elements().asScala
      .map(_.get("family").asText()).toSet

  // ---- the draw ---------------------------------------------------------------------------

  private val dates = Seq("2024-01-31", "2024-02-29", "2023-12-27", "2021-01-01", "2021-06-01",
    "2021-03-15", "1969-12-31", "1970-01-01", "2000-02-29", "1999-12-31", "2021-11-01")
  private val farDates = Seq("9999-12-01", "0001-01-15")
  private val longs = Seq("0", "1", "-1", "42", "-42", "5000000000", "-5000000000", "2147483647",
    "2147483648", "-2147483648", "-2147483649", "12345678901234")
  private val farLongs = Seq("9223372036854775807", "-9223372036854775808")
  private val times = Seq("00:00:00", "12:34:56.789", "23:59:59.999999", "06:00:00",
    "00:00:00.000001", "18:00:00", "09:30:00", "01:02:03.456")
  private val intervals = Seq("0 00:00:00", "1 02:03:04.5", "-3 00:00:00.000001",
    "100000 00:00:00", "-100000 00:00:00", "7 12:00:00", "0 00:00:01", "-0 00:00:00.5")

  private def pick[A](rnd: Random, xs: Seq[A]): A = xs(rnd.nextInt(xs.size))

  /**
   * `n` rows of random data. The rows are drawn from a pool that keeps every value inside the
   * guards and the ANSI checks (a valid month, a date near the epoch, magnitudes that do not
   * overflow), so a query over them compares values. A `safe` case is only such rows; a full one
   * adds up to two hostile rows, each with one to three cells at an extreme - a far date, a bound
   * of an int, a bound of a long - where the comparison is of what is raised or declined. A pool
   * that was hostile throughout made 70% of the full cases fail on both engines, which compares
   * the class of an error and nothing about the other rows. The null rate is drawn per case.
   */
  def drawData(rnd: Random, n: Int, safe: Boolean,
      wide: Option[Random] = None): Vector[Vector[String]] = {
    val nullRate = pick(rnd, Seq(0.0, 0.2, 0.2, 0.5, 1.0))
    // A null is typed, as the fixtures of `VarkaCoverageDifferentialSuite` type theirs: a bare
    // `NULL` in every row of a column makes it `VOID`, a type the relation can carry and the
    // evaluator's row fallback cannot convert (see the reproducer `void-column-fallback.sql`).
    def maybe(sqlType: String)(s: => String): String =
      if (rnd.nextDouble() < nullRate) s"CAST(NULL AS $sqlType)" else s
    def int(lo: Int, hi: Int): String = maybe("INT")((lo + rnd.nextInt(hi - lo + 1)).toString)
    def date(): String = maybe("DATE")(s"DATE'${pick(rnd, dates)}'")
    def long(): String = maybe("BIGINT")(s"CAST('${pick(rnd, longs)}' AS BIGINT)")
    def time(): String = maybe("TIME(6)")(s"TIME'${pick(rnd, times)}'")
    def interval(): String =
      maybe("INTERVAL DAY TO SECOND")(s"INTERVAL '${pick(rnd, intervals)}' DAY TO SECOND")
    // Column by column: the safe draw, and the extreme a hostile row puts there.
    val columns: Vector[(() => String, () => String)] = Vector(
      (() => date(), () => s"DATE'${pick(rnd, farDates)}'"),
      (() => date(), () => s"DATE'${pick(rnd, farDates)}'"),
      (() => int(1, 12),
        () => pick(rnd, Seq(0, -1, 13, 100000, Int.MaxValue, Int.MinValue)).toString),
      (() => int(-14, 100), () => pick(rnd, Seq(Int.MaxValue, Int.MinValue)).toString),
      (() => int(-2, 4), () => pick(rnd, Seq(178956969, -178956970)).toString),
      (() => int(0, 11), () => pick(rnd, Seq(12, -1)).toString),
      (() => long(), () => s"CAST('${pick(rnd, farLongs)}' AS BIGINT)"),
      (() => long(), () => s"CAST('${pick(rnd, farLongs)}' AS BIGINT)"),
      (() => time(), () => s"TIME'${pick(rnd, times)}'"),
      (() => time(), () => s"TIME'${pick(rnd, times)}'"),
      (() => interval(), () => s"INTERVAL '${pick(rnd, intervals)}' DAY TO SECOND"),
      (() => interval(), () => s"INTERVAL '${pick(rnd, intervals)}' DAY TO SECOND"))
    val hostile =
      if (safe) Set.empty[Int] else rnd.shuffle((0 until n).toList).take(rnd.nextInt(3)).toSet
    Vector.tabulate(n) { row =>
      val cells = columns.map(_._1())
      val drawn = if (!hostile.contains(row)) cells
      else {
        val spots = rnd.shuffle(columns.indices.toList).take(1 + rnd.nextInt(3)).toSet
        cells.zipWithIndex.map { case (cell, j) =>
          if (spots.contains(j)) columns(j)._2() else cell
        }
      }
      // The wider pool (VARKA-297) replaces about half the safe cells, from a stream of its own
      // so that the compositions the main stream draws are the ones it drew before; a cell that
      // is NULL or hostile stays as it is.
      wide.fold(drawn) { w =>
        drawn.zipWithIndex.map { case (cell, j) =>
          if (cell.startsWith("CAST(NULL") || hostile.contains(row) || w.nextBoolean()) cell
          else wideCell(w, j)
        }
      }
    }
  }

  /**
   * A cell of column `j` from beyond the fixtures' lists, inside every guard and ANSI check: a
   * date within about two centuries of the epoch day 0 either way, ints and months over a wider
   * span, longs within 2^40, a time to the microsecond, an interval to the microsecond over
   * thousands of days. The draw reaches values no list above holds, which is where a constant
   * folded wrongly or a guard placed one off would be.
   */
  private def wideCell(w: Random, j: Int): String = {
    def ranged(lo: Int, hi: Int): Int = lo + w.nextInt(hi - lo + 1)
    j match {
      case 0 | 1 => s"DATE'${java.time.LocalDate.ofEpochDay(ranged(-200000, 200000))}'"
      case 2 => ranged(-5000, 5000).toString
      case 3 => ranged(-1000, 1000).toString
      case 4 => ranged(-100, 100).toString
      case 5 => ranged(0, 11).toString
      case 6 | 7 => s"CAST('${w.nextLong() % (1L << 40)}' AS BIGINT)"
      case 8 | 9 =>
        val micros = Math.floorMod(w.nextLong(), 86400L * 1000000L)
        s"TIME'${java.time.LocalTime.ofNanoOfDay(micros * 1000)}'"
      case _ =>
        val micros = Math.floorMod(w.nextLong(), 86400L * 1000000L)
        val time = java.time.LocalTime.ofNanoOfDay(micros * 1000)
        s"INTERVAL '${ranged(-3000, 3000)} ${time}' DAY TO SECOND"
    }
  }

  /**
   * Composition `iteration` of the stream for `seed`. The draw is stratified: composition `k`
   * includes row `order(k mod rows)` of a shuffled order of the table, as an output if it is a
   * projection and as a conjunct if it is a predicate, so that every row is drawn in every pass
   * of the table's length - a uniform draw found a bug in one row after 254 of 300 compositions.
   */
  def draw(rows: Seq[CoverageRow], seed: Long, iteration: Int): Case = {
    val drawn = drawRows(rows, seed, iteration)
    if (nestedIteration(iteration)) withNested(drawn, seed, iteration) else drawn
  }

  /** Composition `iteration` of the coverage rows alone, before any nested tree is added. */
  def drawRows(rows: Seq[CoverageRow], seed: Long, iteration: Int): Case = {
    val projections = rows.filter(_.form == "projection")
    val predicates = rows.filter(_.form == "predicate")
    val order = new Random(seed).shuffle(rows.indices.toVector)
    val forced = rows(order(iteration % rows.size))
    val rnd = new Random(seed * 1000003L + iteration)
    val outs = scala.collection.mutable.ArrayBuffer.empty[String]
    val conj = scala.collection.mutable.ArrayBuffer.empty[Conjunct]
    if (forced.form == "projection") outs += forced.executable
    val k = 1 + rnd.nextInt(4)
    while (outs.size < k) outs += pick(rnd, projections).executable
    val filter = rnd.nextInt(3)
    if (filter > 0 || forced.form == "predicate") {
      if (forced.form == "predicate") conj += Conjunct(forced.executable, rnd.nextInt(4) == 0)
      val more = if (filter == 2) 1 + rnd.nextInt(2) else if (conj.isEmpty) 1 else 0
      (0 until more).foreach(_ =>
        conj += Conjunct(pick(rnd, predicates).executable, rnd.nextInt(4) == 0))
    }
    val outputs = rnd.shuffle(outs.toVector)
    val conjuncts = rnd.shuffle(conj.toVector)
    val op = if (rnd.nextBoolean()) "AND" else "OR"
    // The wider pool on every other pair of compositions, from a stream of its own.
    val wide = if ((iteration / 2) % 2 == 1) Some(new Random(~(seed * 1000003L + iteration)))
      else None
    val data = drawData(rnd, 12, safe = iteration % 2 == 0, wide)
    val ansi = rnd.nextBoolean()
    // A pivot (VARKA-297) on every third composition with a filter; the conjuncts are rectified
    // against it by the suite, which has the row engine to ask.
    val pivot = if (conjuncts.nonEmpty && iteration % 3 == 1) Some(pick(rnd, data)) else None
    Case(outputs, conjuncts, op, data, ansi, pivot)
  }

  /**
   * Whether composition `iteration` adds nested trees (VARKA-298): half of them, five in a row and
   * then five without. A run of five covers every residue of two and four and the period of ten
   * is prime to three, so the nested ones meet both pools, the wider pool, pivots in both pools
   * and the partition check (which only safe compositions take).
   * `-Dvarka.sparkfuzz.nested=false` draws none, which replays a seed's compositions as they were
   * drawn before VARKA-298 and measures what the nested ones cost.
   */
  def nestedIteration(iteration: Int): Boolean = nestedOn && (iteration / 5) % 2 == 1

  private val nestedOn = !sys.props.get("varka.sparkfuzz.nested").contains("false")

  /**
   * `c` with one or two outputs drawn from `VarkaCatalystGrammar` and, on a filter one time in two
   * or without one a time in three, a nested conjunct, each at a random place among the coverage
   * rows. The trees come from a stream of their own, so the coverage rows, the data, the ANSI mode
   * and the pivot are the ones the composition drew without them.
   */
  private def withNested(c: Case, seed: Long, iteration: Int): Case = {
    val n = new java.util.Random(seed * 1000003L + iteration + NestedSalt)
    // A tree that renders as one of the composition's coverage rows (`year(d)` is both) is not
    // added, so that `trees` names nested trees only.
    val taken = (c.outputs ++ c.conjuncts.map(_.sql)).toSet
    val outputs = (0 until 1 + n.nextInt(2)).map(_ => VarkaCatalystGrammar.drawOutput(n))
    var trees = c.trees
    var outs = c.outputs
    outputs.foreach { t =>
      val at = n.nextInt(outs.size + 1)
      if (!taken.contains(t.sql)) {
        outs = outs.patch(at, Seq(t.sql), 0)
        trees += t.sql -> t
      }
    }
    var conjuncts = c.conjuncts
    if (if (conjuncts.nonEmpty) n.nextBoolean() else n.nextInt(3) == 0) {
      val p = VarkaCatalystGrammar.drawPredicate(n)
      val at = n.nextInt(conjuncts.size + 1)
      val negated = n.nextInt(4) == 0
      if (!taken.contains(p.sql)) {
        trees += p.sql -> p
        conjuncts = conjuncts.patch(at, Seq(Conjunct(p.sql, negated)), 0)
      }
    }
    c.copy(outputs = outs, conjuncts = conjuncts, trees = trees)
  }

  /** Separates the nested trees' stream from the composition's and the wider pool's. */
  private val NestedSalt = 0x298298298L

  /**
   * Data for a later batch of `c`'s shape (VARKA-327): every cell of `c.data` that is not NULL
   * replaced by a fresh draw's cell of the same kind (safe or full, and from the wider pool when
   * `wide`), and every NULL kept where it is. A column holds a NULL here exactly where it did, so
   * each column's nullability, which the shape is compiled for, is the same, and a kernel warmed
   * on `c.data` serves these rows without a new warm-up; the values are new, so the compiled code
   * meets guard outcomes and values its profile never saw. A fresh draw draws its own null rate,
   * so a cell takes the first of up to `LaterDraws` draws that is not NULL there.
   */
  def laterData(c: Case, rnd: Random, safe: Boolean, wide: Boolean): Vector[Vector[String]] = {
    def isNull(cell: String): Boolean = cell.startsWith("CAST(NULL")
    val fresh = Vector.fill(LaterDraws)(
      drawData(rnd, c.data.size, safe, if (wide) Some(new Random(rnd.nextLong())) else None))
    c.data.indices.toVector.map { r =>
      c.data(r).indices.toVector.map { j =>
        val cell = c.data(r)(j)
        if (isNull(cell)) cell
        else fresh.iterator.map(_(r)(j)).find(!isNull(_)).getOrElse(cell)
      }
    }
  }

  /** Fresh draws `laterData` takes a cell from; a cell NULL in all of them keeps its value. */
  private val LaterDraws = 4

  /**
   * How the warmed arm's compositions went (VARKA-327), one per test so that two tests' counts do
   * not mix. `rowPathRuns` were served, at least in part, on the row path while a kernel warmed;
   * `kernelRuns` had at least one node serve batches on its kernel; `handedOver` counts the
   * compositions that had runs of both kinds. Of those that never reached a kernel, `unfused` had
   * no Varka node, `raised` raised (compared by the error's class), `noBatches` had every cached
   * batch pruned by its statistics, `declined` had their batches declined, and `stillWarming`
   * were still waiting after four runs. `laterRuns` ran a shape that reached its kernel over
   * other data, and `laterNewShape` of them met a new shape instead, which compares the row path
   * again. `notIdle` counts the waits for the warm-up that timed out.
   */
  final class WarmedCounts {
    var compositions = 0
    var rowPathRuns = 0
    var kernelRuns = 0
    var handedOver = 0
    var unfused = 0
    var raised = 0
    var noBatches = 0
    var declined = 0
    var stillWarming = 0
    var laterRuns = 0
    var laterNewShape = 0
    var notIdle = 0

    def summary: Seq[String] = Seq(
      s"compositions $compositions: runs on the row path while warming $rowPathRuns, on a " +
        s"kernel $kernelRuns, handed over $handedOver; never on a kernel: no Varka node " +
        s"$unfused, raised $raised, no batch $noBatches, batches declined $declined, still " +
        s"warming after four runs $stillWarming",
      s"runs over other data $laterRuns, of which $laterNewShape met a new shape; waits that " +
        s"timed out $notIdle")
  }

  // ---- the comparison ---------------------------------------------------------------------

  /** A query's outcome: its rows sorted as strings, or the class of what it raised. */
  type Outcome = Either[String, Seq[String]]

  def outcome(session: SparkSession, sql: String): Outcome = outcomeOf(session.sql(sql))

  /** `df`'s outcome, for a caller that reads `df`'s plan and metrics afterwards. */
  def outcomeOf(df: DataFrame): Outcome =
    Try(df.collect().map(_.toString).sorted.toSeq).toEither.left.map { e =>
      val chain = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).toSeq
      chain.collectFirst { case t: SparkThrowable if t.getCondition != null => t.getCondition }
        .getOrElse(e.getClass.getName)
    }

  /**
   * The kind of finding when `outcome`, raised by `engine` (ROW or VARKA) under `ansi`, is an
   * error the expected-errors list (VARKA-319) does not allow for that side and mode, else None.
   * A raise that is a class name rather than a condition is unlisted unless the list names it.
   */
  def unexpectedRaise(
      outcome: Outcome,
      engine: VarkaExpectedErrors.Side,
      ansi: Boolean,
      list: java.util.List[VarkaExpectedErrors.Entry] = VarkaExpectedErrors.entries())
      : Option[String] = {
    val mode = if (ansi) VarkaExpectedErrors.Mode.ANSI else VarkaExpectedErrors.Mode.LEGACY
    outcome.left.toOption.filterNot(VarkaExpectedErrors.expected(list, _, engine, mode)).map {
      raised =>
        val who = if (engine == VarkaExpectedErrors.Side.ROW) "the row engine" else "Varka"
        s"unexpected error condition $raised from $who under $mode"
    }
  }

  /** [[unexpectedRaise]] for the row engine's outcome and Varka's. */
  def unexpectedError(
      off: Outcome,
      on: Outcome,
      ansi: Boolean,
      list: java.util.List[VarkaExpectedErrors.Entry] = VarkaExpectedErrors.entries())
      : Option[String] =
    unexpectedRaise(off, VarkaExpectedErrors.Side.ROW, ansi, list)
      .orElse(unexpectedRaise(on, VarkaExpectedErrors.Side.VARKA, ansi, list))

  /**
   * Whether `kind` is two different conditions, each one the expected-errors list allows from
   * either engine under `ansi`: the signature of two legitimate errors on rows in different
   * partitions, where whichever task fails first decides what the query raises, on either engine
   * and from one run to the next (VARKA-298.md 9). Such a disagreement is believed only if it
   * repeats.
   */
  def racingErrors(kind: String, ansi: Boolean): Boolean = {
    val marker = "error classes differ: "
    val at = kind.indexOf(marker)
    val mode = if (ansi) VarkaExpectedErrors.Mode.ANSI else VarkaExpectedErrors.Mode.LEGACY
    val list = VarkaExpectedErrors.entries()
    at >= 0 && kind.substring(at + marker.length).split(", ").toSeq.forall { raised =>
      Seq(VarkaExpectedErrors.Side.ROW, VarkaExpectedErrors.Side.VARKA)
        .forall(VarkaExpectedErrors.expected(list, raised, _, mode))
    }
  }

  /** How two outcomes differ, or None when they agree. */
  def difference(off: Outcome, on: Outcome): Option[String] = (off, on) match {
    case (Right(a), Right(b)) => if (a == b) None else Some("values differ")
    case (Left(a), Left(b)) => if (a == b) None else Some(s"error classes differ: $a, $b")
    case (Left(a), Right(_)) => Some(s"only the row engine throws: $a")
    case (Right(_), Left(b)) => Some(s"only Varka throws: $b")
  }

  /**
   * Whether the row engine and Varka returned the pivot row (VARKA-297): `pivotOut` is the
   * outputs over the pivot row alone, and a filter rectified to select the pivot must return
   * it from the whole table on each engine. None when it does, or when a side raised - an error
   * on another row says nothing about the pivot.
   */
  def pivotMissing(off: Outcome, on: Outcome, pivotOut: Outcome): Option[String] =
    pivotOut match {
      case Right(Seq(expected)) =>
        def lacks(o: Outcome): Boolean = o.exists(rows => !rows.contains(expected))
        if (lacks(on)) Some("pivot row not selected by Varka")
        else if (lacks(off)) Some("pivot row not selected by the row engine")
        else None
      case _ => None
    }

  /**
   * The ternary partition of a filter: the rows of `WHERE p`, `WHERE NOT (p)` and
   * `WHERE (p) IS NULL` together are the rows of the unfiltered query. None when it holds or
   * when a side raised (an error says nothing about the rows), except that a raise `unexpected`
   * does not allow (the expected-errors list, VARKA-319) is reported.
   */
  def partitionBroken(
      select: String,
      where: String,
      run: String => Outcome,
      unexpected: Outcome => Option[String] = _ => None): Option[String] = {
    val whole = run(querySql(select, None))
    val parts = Seq(where, s"NOT ($where)", s"($where) IS NULL").map(w =>
      run(querySql(select, Some(w))))
    val all = whole +: parts
    if (all.exists(_.isLeft)) all.iterator.flatMap(unexpected(_)).nextOption()
    else {
      val union = parts.flatMap(_.toOption.get).sorted
      if (union == whole.toOption.get.sorted) None else Some("partition broken")
    }
  }

  // ---- the shrinker -----------------------------------------------------------------------

  /** [[VarkaShrinker.ddmin]] over a Scala vector. */
  private def ddmin[A](items: Vector[A])(fails: Vector[A] => Boolean): Vector[A] =
    VarkaShrinker.ddmin(items.asJava, (xs: java.util.List[A]) => fails(xs.asScala.toVector))
      .asScala.toVector

  /**
   * `c` reduced while `kind` still shows: the outputs, the conjuncts and the rows of data, each by
   * ddmin, and between them the inside of each nested output and conjunct by
   * `VarkaShrinker.shrinkTree` (VARKA-298), repeated until a pass changes nothing, within `maxRuns`
   * checks. `check` returns the kind of disagreement a case shows, or None.
   */
  def shrink(c: Case, kind: String, check: Case => Option[String], maxRuns: Int = 400): Case = {
    var runs = 0
    def still(x: Case): Boolean = {
      if (runs >= maxRuns) false
      else {
        runs += 1
        check(x).contains(kind)
      }
    }
    var best = c
    var changed = true
    while (changed && runs < maxRuns) {
      val before = best
      if (best.outputs.size > 1) {
        best = best.copy(outputs = ddmin(best.outputs)(os =>
          os.nonEmpty && still(best.copy(outputs = os))))
      }
      // Inside each nested output (VARKA-298): any subtree may become the output, since an output
      // takes any type, and a node inside it only a tree of its own type.
      for (k <- best.outputs.indices; tree <- best.trees.get(best.outputs(k))) {
        val small = VarkaShrinker.shrinkTree(tree, VarkaCatalystGrammar.MOVES, true,
          (t: VarkaCatalystGrammar.Node) => still(withOutput(best, k, t)))
        if (small != tree) best = withOutput(best, k, small)
      }
      if (best.conjuncts.nonEmpty) {
        best = best.copy(conjuncts = ddmin(best.conjuncts)(cs =>
          still(best.copy(conjuncts = cs))))
      }
      // Inside each nested conjunct, which stays a boolean; not under a pivot, since a conjunct was
      // rectified to the value it has on the pivot row, and a smaller one may have another.
      for (k <- best.conjuncts.indices if best.pivot.isEmpty;
          tree <- best.trees.get(best.conjuncts(k).sql)) {
        val small = VarkaShrinker.shrinkTree(tree, VarkaCatalystGrammar.MOVES, false,
          (t: VarkaCatalystGrammar.Node) => still(withConjunct(best, k, t)))
        if (small != tree) best = withConjunct(best, k, small)
      }
      if (best.data.size > 1) {
        // The pivot row stays: a filter rectified against it selects nothing else for certain.
        best = best.copy(data = ddmin(best.data)(ds =>
          ds.nonEmpty && best.pivot.forall(ds.contains) && still(best.copy(data = ds))))
      }
      changed = best != before
    }
    best
  }

  private def withOutput(c: Case, k: Int, t: VarkaCatalystGrammar.Node): Case =
    c.copy(outputs = c.outputs.updated(k, t.sql), trees = c.trees + (t.sql -> t))

  private def withConjunct(c: Case, k: Int, t: VarkaCatalystGrammar.Node): Case =
    c.copy(conjuncts = c.conjuncts.updated(k, c.conjuncts(k).copy(sql = t.sql)),
      trees = c.trees + (t.sql -> t))

  // ---- the reproducer file ----------------------------------------------------------------

  /** A reproducer: what a file holds, enough to replay without the draw. */
  final case class Reproducer(
      status: String,
      note: String,
      ansi: Boolean,
      kind: String,
      select: String,
      where: Option[String],
      fixture: String,
      pivot: Option[String] = None,
      force: Option[Int] = None)

  def render(r: Reproducer): String =
    s"""-- VARKA-262 reproducer
       |-- status: ${r.status}
       |-- note: ${r.note}
       |-- ansi: ${r.ansi}
       |-- kind: ${r.kind}
       |-- select: ${r.select}
       |-- where: ${r.where.getOrElse("")}${r.pivot.map(p => s"\n-- pivot: $p").getOrElse("")}${
      r.force.map(k => s"\n-- force: $k").getOrElse("")}
       |-- fixture
       |${r.fixture}
       |-- query
       |${querySql(r.select, r.where)}
       |""".stripMargin

  def parse(text: String): Reproducer = {
    val lines = text.linesIterator.toVector
    def header(key: String): String = lines.collectFirst {
      case l if l.startsWith(s"-- $key: ") => l.substring(key.length + 5)
      case l if l == s"-- $key:" => ""
    }.getOrElse(throw new IllegalArgumentException(s"no '-- $key:' line in the reproducer"))
    val f = lines.indexOf("-- fixture")
    val q = lines.indexOf("-- query")
    require(f >= 0 && q > f, "a reproducer has a '-- fixture' and then a '-- query' section")
    val where = header("where")
    val pivot = lines.collectFirst { case l if l.startsWith("-- pivot: ") => l.substring(10) }
    val force = lines.collectFirst {
      case l if l.startsWith("-- force: ") => l.substring(10).trim.toInt
    }
    Reproducer(header("status"), header("note"), header("ansi").toBoolean, header("kind"),
      header("select"), if (where.isEmpty) None else Some(where),
      lines.slice(f + 1, q).mkString("\n"), pivot, force)
  }
}
