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

import java.util.Locale

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Random

import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, Expression}
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.types.{DataType, DateType, IntegerType, YearMonthIntervalType}

/**
 * A census of validity words (VARKA-70's algebra): for every value root of a corpus, what its
 * word is - one input's bitmap, the constant, a single-operator chain, a tree that mixes the
 * operators, or nothing pure at all - and what two extensions of the axioms would change:
 * the coalesce axiom (an `IfElse(IsNotNull(x), x, y)` denotes `x OR y`) and the absorption
 * laws (`x AND (x OR y) = x`, `x OR (x AND y) = x`). The admission check of the milestone 5
 * follow-ups to VARKA-70, run from `dev/varka_word_census.sh`.
 *
 * Three corpora: the `Surface` inventory's projections (the shapes the surface run covers),
 * a list of composites chosen to exercise the operator boundary, and `--fuzz N` shapes from
 * a copy of `VarkaIrFuzzSuite`'s value grammar. Every single-root shape is also emitted under
 * `validityByBitmap` on and off, and the mirror's verdict is checked against the emitter's:
 * a served root changes `loopMasked0`'s bytes, a declined one does not. The mirror of
 * `pureOf` below exists because the emitter's analysis is private; it is the one thing here
 * that can drift, and the cross-check is what would show it. VARKA-74 replaces the mirror
 * with the analysis itself.
 */
object VarkaWordCensus {

  // --- the word algebra, mirrored, with the two extensions switchable ---------------------

  sealed trait W
  case class In(i: Int) extends W
  case object T extends W
  case class A(a: W, b: W) extends W
  case class O(a: W, b: W) extends W

  private def andW(a: W, b: W, absorb: Boolean): W = (a, b) match {
    case (null, _) | (_, null) => null
    case (T, x) => x
    case (x, T) => x
    case (x, y) if x == y => x
    case (x, O(p, q)) if absorb && (p == x || q == x) => x
    case (O(p, q), y) if absorb && (p == y || q == y) => y
    case (x, y) => A(x, y)
  }

  private def orW(a: W, b: W, absorb: Boolean): W = (a, b) match {
    case (null, _) | (_, null) => null
    case (T, _) | (_, T) => T
    case (x, y) if x == y => x
    case (x, A(p, q)) if absorb && (p == x || q == x) => x
    case (A(p, q), y) if absorb && (p == y || q == y) => y
    case (x, y) => O(x, y)
  }

  /** The pure word of `n`, or null; `coalesce` and `absorb` switch the two extensions on. */
  def pure(n: VarkaVectorIR, coalesce: Boolean, absorb: Boolean): W = {
    def p(x: VarkaVectorIR): W = pure(x, coalesce, absorb)
    n match {
      case c: ColumnRef => In(c.ordinal())
      case _: LiteralSlot => T
      case x: AddDays => andW(p(x.days()), p(x.offset()), absorb)
      case x: SubDays => andW(p(x.days()), p(x.offset()), absorb)
      case x: NextDay => andW(p(x.days()), p(x.offset()), absorb)
      case x: TruncDateDynamic => andW(p(x.days()), p(x.level()), absorb)
      case x: AddMonths => andW(p(x.days()), p(x.months()), absorb)
      case x: DateDiff => andW(p(x.end()), p(x.start()), absorb)
      case x: Greatest => orW(p(x.left()), p(x.right()), absorb)
      case x: Least => orW(p(x.left()), p(x.right()), absorb)
      case x: DayOfWeek => p(x.days())
      case x: WeekDay => p(x.days())
      case x: DayOfWeekIso => p(x.days())
      case x: ThursdayOf => p(x.days())
      case x: Year => p(x.days())
      case x: Month => p(x.days())
      case x: DayOfMonth => p(x.days())
      case x: Quarter => p(x.days())
      case x: DayOfYear => p(x.days())
      case x: LastDay => p(x.days())
      case x: TruncDate => p(x.days())
      case x: WeekOfYear => p(x.days())
      case x: IfElse if coalesce =>
        x.cond() match {
          case nn: IsNotNull if nn.child() == x.thenNode() =>
            orW(p(x.thenNode()), p(x.elseNode()), absorb)
          case _ => null
        }
      case _ => null
    }
  }

  sealed trait Kind
  case object NoExpr extends Kind
  case object Const extends Kind
  case object Leaf extends Kind
  case class Chain(and: Boolean, leaves: Int) extends Kind
  case object Mixed extends Kind

  def leaves(w: W): Set[Int] = w match {
    case In(i) => Set(i)
    case A(a, b) => leaves(a) ++ leaves(b)
    case O(a, b) => leaves(a) ++ leaves(b)
    case _ => Set()
  }

  private def ops(w: W): Set[Boolean] = w match {
    case A(a, b) => ops(a) ++ ops(b) + true
    case O(a, b) => ops(a) ++ ops(b) + false
    case _ => Set()
  }

  /** The tree's Strahler number: the registers a lockstep evaluation needs (Ershov). */
  def strahler(w: W): Int = w match {
    case A(a, b) => comb(strahler(a), strahler(b))
    case O(a, b) => comb(strahler(a), strahler(b))
    case _ => 1
  }

  private def comb(x: Int, y: Int): Int = if (x == y) x + 1 else math.max(x, y)

  def kind(w: W): Kind = w match {
    case null => NoExpr
    case T => Const
    case In(_) => Leaf
    case _ =>
      val o = ops(w)
      if (o.size == 1) Chain(o.head, leaves(w).size) else Mixed
  }

  /** Whether today's emitter serves a root of this kind (VARKA-70's rule). */
  private def served(k: Kind): Boolean = k match {
    case NoExpr | Mixed => false
    case _ => true
  }

  // --- SQL to IR, as VarkaEmitDump does ------------------------------------------------

  // The interval columns are here so the corpus below can carry the date/interval shapes task
  // 67 added. They were absent while `resolve` had no analyzer pass, because without coercion
  // `d + ymm` declined and there was nothing to census.
  private val columns: Seq[Attribute] =
    Seq("d:date", "d2:date", "d3:date", "d4:date", "i:int", "j:int",
      "ymm:ymm", "ymy:ymy", "ym:ym").map { c =>
      val Array(name, tpe) = c.split(":")
      val dt: DataType = tpe.toLowerCase(Locale.ROOT) match {
        case "date" => DateType
        case "ymm" => YearMonthIntervalType(YearMonthIntervalType.MONTH)
        case "ymy" => YearMonthIntervalType(YearMonthIntervalType.YEAR)
        case "ym" => YearMonthIntervalType()
        case _ => IntegerType
      }
      AttributeReference(name, dt)()
    }

  private def resolve(e: Expression): Expression = VarkaSqlResolve.resolve(e, columns)

  private case class Fused(roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int)

  /** The fused roots of a projection given as SQL, or None if nothing fused. */
  private def fuse(exprs: Seq[String]): Option[Fused] = {
    val named = exprs.map(e => Alias(resolve(CatalystSqlParser.parseExpression(e)), "c")())
    VarkaExpressionCompiler.compilePartial(named, columns).map { p =>
      Fused(p.fused.outputs.asScala.toSeq, p.fused.inputOrdinals.size,
          p.fused.literals.size)
    }
  }

  /**
   * The `Surface` projections, minus its date/interval shapes - **not yet** the whole surface,
   * and the gap is why this comment is longer than it looks like it should be.
   *
   * This read "the `Surface` projections that resolve without the analyzer's type coercion",
   * which was a workaround for a bug rather than a choice: `resolve` had no analyzer pass, so
   * `d + ymm` parsed to an `Add` the compiler declined, and the entries were dropped to keep
   * the census running. That bug is fixed - `resolve` now shares `VarkaSqlResolve` with
   * `VarkaEmitDump` - and the interval columns are declared above, so the omitted entries
   * *can* be carried now.
   *
   * They are not carried yet, deliberately. Adding them moves every number the census
   * publishes, and `m5/PLAN.md` 2.x quotes a 7 September run of it while describing
   * that run as covering "the `Surface` projections". Re-running and requoting belongs to the
   * task that owns the census (74/75), not to the benchmark change that happened to find the
   * resolver bug. Until then this list is about two thirds of the surface and says so here.
   */
  private val surface = Seq(
    "date_add(d, 3)", "date_add(d, i)", "date_sub(d, 5)", "datediff(d2, d)", "unix_date(d)",
    "date_from_unix_date(unix_date(d))", "year(d)", "month(d)", "day(d)", "quarter(d)",
    "dayofyear(d)", "dayofweek(d)", "weekday(d)", "last_day(d)", "next_day(d, 'MONDAY')",
    "weekofyear(d)", "extract(DAYOFWEEK_ISO FROM d)", "extract(YEAROFWEEK FROM d)",
    "add_months(d, 3)", "add_months(d, i)", "d + INTERVAL 3 MONTH", "trunc(d, 'YEAR')",
    "trunc(d, 'MONTH')", "trunc(d, 'QUARTER')", "trunc(d, 'WEEK')", "if(d < d2, d, d2)",
    "CASE WHEN d < d2 THEN d ELSE d2 END", "coalesce(d, d2)", "greatest(d, d2)",
    "least(d, d2)", "year(date_add(d, 30))")

  /** Shapes chosen to sit on the operator boundary. */
  private val composites = Seq(
    "datediff(greatest(d, d2), d)",
    "datediff(greatest(d, d2), greatest(d3, d4))",
    "datediff(greatest(d, d2), least(d, d2))",
    "year(greatest(date_add(d, i), d2))",
    "date_add(greatest(d, d2), i)",
    "greatest(date_add(d, i), date_sub(d2, i))",
    "greatest(date_add(d, i), d)",
    "coalesce(d, d2, d3)",
    "year(coalesce(d, d2))",
    "datediff(coalesce(d, d2), d)",
    "date_add(coalesce(d, d2), i)",
    "greatest(coalesce(d, d2), d3)",
    "add_months(greatest(d, d2), i)",
    "datediff(date_add(d, i), date_add(d2, j))",
    "datediff(greatest(d, d2), date_add(d, i))",
    "next_day(greatest(d, d2), 'MON')")

  // --- the emitter's verdict, read off the bytes ----------------------------------------

  private val off = VarkaEmitOptions.DEFAULTS.withValidityByBitmap(false)

  /** Whether the emitter serves a single root: the pass changes the masked loop's bytes. */
  private def emitterServes(f: Fused): Option[Boolean] = {
    def size(o: VarkaEmitOptions): Int = VarkaEmitterTestSupport.codeSize(
      VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaFusedCensus",
        f.roots.asJava, f.numInputs, f.numLiterals, null, null, o), "loopMasked0")
    try Some(size(VarkaEmitOptions.DEFAULTS) != size(off)) catch {
      case _: IllegalArgumentException | _: IllegalStateException => None
    }
  }

  // --- the fuzzer's value grammar, copied (VarkaIrFuzzSuite's Shapes is private) ----------

  private class Shapes(rnd: Random, numInputs: Int, numLiterals: Int) {
    private var budget = 12
    private def leaf(): VarkaVectorIR = new ColumnRef(rnd.nextInt(numInputs))
    private def literal(): VarkaVectorIR =
      if (numLiterals > 0) new LiteralSlot(rnd.nextInt(numLiterals)) else leaf()
    def value(depth: Int): VarkaVectorIR = {
      if (depth == 0 || budget <= 1) return leaf()
      budget -= 1
      rnd.nextInt(16) match {
        case 0 => new AddDays(value(depth - 1), literal())
        case 1 => new SubDays(value(depth - 1), literal())
        case 2 => new DateDiff(value(depth - 1), value(depth - 1))
        case 3 => new Greatest(value(depth - 1), value(depth - 1))
        case 4 => new Least(value(depth - 1), value(depth - 1))
        case 5 => new IfElse(cond(depth - 1), value(depth - 1), value(depth - 1))
        case 6 => new DayOfWeek(value(depth - 1))
        case 7 => new WeekDay(value(depth - 1))
        case 8 => new NextDay(value(depth - 1), if (rnd.nextBoolean()) literal() else leaf())
        case 9 => new Year(value(depth - 1))
        case 10 => new Month(value(depth - 1))
        case 11 => new DayOfMonth(value(depth - 1))
        case 12 => new Quarter(value(depth - 1))
        case 13 => new DayOfYear(value(depth - 1))
        case 14 => new AddMonths(value(depth - 1), if (rnd.nextBoolean()) literal() else leaf())
        case _ => new LastDay(value(depth - 1))
      }
    }
    def cond(depth: Int): Cond = {
      if (depth == 0 || budget <= 1) return new IsNotNull(leaf())
      budget -= 1
      rnd.nextInt(5) match {
        case 0 => new And(cond(depth - 1), cond(depth - 1))
        case 1 => new Or(cond(depth - 1), cond(depth - 1))
        case 2 => new Not(cond(depth - 1))
        case 3 => new IsNotNull(value(depth - 1))
        case _ => new Compare(CompareOp.LT, value(depth - 1), value(depth - 1))
      }
    }
  }

  // --- the census -----------------------------------------------------------------------

  private def report(s: String): Unit = {
    // scalastyle:off println
    println(s)
    // scalastyle:on println
  }

  private def row(label: String, f: Fused): Unit = {
    val base = pure(f.roots.head, coalesce = false, absorb = false)
    val ext = pure(f.roots.head, coalesce = true, absorb = true)
    val check = if (f.roots.size != 1) "-" else emitterServes(f) match {
      case Some(s) if s == served(kind(base)) => "agrees"
      case Some(_) => "DISAGREES"
      case None => "not emitted"
    }
    report(f"$label%-46s ${kind(base)}%-16s ${kind(ext)}%-16s " +
      f"${if (ext == null) 0 else strahler(ext)}%d  ${leaves(ext).toSeq.sorted.mkString(",")}%-8s" +
      f"  $check")
  }

  def main(args: Array[String]): Unit = {
    var fuzz = 20000
    var seed = 7L
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--fuzz" => fuzz = args(i + 1).toInt; i += 2
        case "--seed" => seed = args(i + 1).toLong; i += 2
        case other => throw new IllegalArgumentException(s"unknown argument $other")
      }
    }
    report(f"${"shape"}%-46s ${"today"}%-16s ${"extended"}%-16s strahler leaves    emitter")
    report("---- Surface projections ----")
    surface.foreach(e => fuse(Seq(e)).foreach(row(e, _)))
    report("---- composites ----")
    composites.foreach(e => fuse(Seq(e)).foreach(row(e, _)))

    val rnd = new Random(seed)
    val kinds = mutable.Map[String, Int]().withDefaultValue(0)
    val strahlers = mutable.Map[Int, Int]().withDefaultValue(0)
    var roots = 0
    var mixed = 0
    var mixedRescued = 0
    var none = 0
    var noneRescued = 0
    var multiShapes = 0
    var multiServed = 0
    var multiDistinct = 0
    var repeatedNonLeaf = 0
    var subsetPairs = 0
    var checked = 0
    var disagreements = 0
    for (_ <- 0 until fuzz) {
      val numInputs = 1 + rnd.nextInt(3)
      val numLiterals = rnd.nextInt(3)
      val shapes = new Shapes(rnd, numInputs, numLiterals)
      val depth = 1 + rnd.nextInt(4)
      val rs = Seq.fill(1 + rnd.nextInt(3))(shapes.value(depth)).distinct
      roots += rs.size
      val forms = rs.map { r =>
        val base = pure(r, coalesce = false, absorb = false)
        val abs = pure(r, coalesce = false, absorb = true)
        val ext = pure(r, coalesce = true, absorb = true)
        kinds(kind(base).toString.replaceAll("\\(.*", "")) += 1
        kind(base) match {
          case Mixed =>
            mixed += 1
            if (kind(abs) != Mixed) mixedRescued += 1
          case NoExpr =>
            none += 1
            if (ext != null) noneRescued += 1
          case _ =>
        }
        if (ext != null) strahlers(strahler(ext)) += 1
        if (ext != null && kind(ext) != Mixed) Some((ops(ext).headOption, leaves(ext))) else None
      }
      if (rs.size == 1) {
        emitterServes(Fused(rs, numInputs, numLiterals)).foreach { s =>
          checked += 1
          if (s != served(kind(pure(rs.head, coalesce = false, absorb = false)))) {
            disagreements += 1
            report(s"DISAGREES: ${VarkaVectorIR.canonical(rs.head)}")
          }
        }
      } else {
        multiShapes += 1
        val servedForms = forms.flatten
        multiServed += servedForms.size
        val distinct = servedForms.distinct
        multiDistinct += distinct.size
        repeatedNonLeaf += servedForms.groupBy(identity).count { case (f, g) =>
          g.size > 1 && f._2.size > 1
        }
        subsetPairs += (for {
          a <- distinct; b <- distinct
          if a != b && a._1 == b._1 && a._1.isDefined && a._2.subsetOf(b._2)
        } yield 1).size
      }
    }
    report(s"---- fuzzer grammar: $fuzz shapes, seed $seed ----")
    report(s"roots=$roots kinds=${kinds.toSeq.sortBy(-_._2).mkString(", ")}")
    report(s"mixed today=$mixed, of which absorption makes a chain or a leaf: $mixedRescued")
    report(s"no expression today=$none, of which the coalesce axiom gives one: $noneRescued")
    report(s"strahler numbers of the extended pure words: ${strahlers.toSeq.sorted.mkString(", ")}")
    report(s"multi-root shapes=$multiShapes servedRoots=$multiServed distinctForms=$multiDistinct" +
      s" repeatedNonLeafForms=$repeatedNonLeaf subsetPairsSameOp=$subsetPairs")
    report(s"emitter cross-check on single-root shapes: $checked checked, " +
      s"$disagreements disagreements")
  }
}
