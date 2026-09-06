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

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters._
import scala.util.Random

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * Random IR trees against the reference evaluator.
 *
 * The emitter suite's matrices are exhaustive over the shapes someone thought to write. This
 * suite writes the others: for each iteration a fresh `Random` seeded from the run seed and the
 * iteration number builds one to three roots out of every supported node type, over one to
 * three int32 columns and up to two literal slots, picks a length, a null pattern per column
 * and a random `VarkaEmitOptions` variant, emits and loads the kernel, runs it once and checks
 * every row and validity bit against [[VarkaReferenceEvaluator]]. A failure names the seed and
 * the iteration, the roots in canonical form, the options, the length and the patterns, and
 * replays with `-Dvarka.fuzz.seed=<seed> -Dvarka.fuzz.only=<iteration>`.
 *
 * What it is for: the class of bug where a slot or a local means two things under two option
 * settings, or a lane-group tail or a validity word is right for every curated shape and wrong
 * for one nobody curated. Random option combinations are the point - every boolean `with*` on
 * the options record is toggled at random (the fault injector excepted), the mod-7 lowering is
 * drawn from all three, and `groupBudget` is sometimes narrowed - so a new option is fuzzed the
 * day it lands without anyone touching this file.
 *
 * The shapes respect the emitter's structural rules, which are the compiler's: a day offset is a
 * literal slot or a column, `next_day`'s weekday and `add_months`' month count are literal
 * slots, `IsNotNull` is over a column, a selection kernel has one condition root.
 *
 * Ranges: calendar nodes are defined over `VarkaChrono`'s narrowed day range, so every tree
 * carries a bound on the magnitude of its value and a calendar node is only put over a subtree
 * whose bound fits inside that range with slack. Columns hold days within plus or minus 2.5
 * million (about six thousand years either side of 1970) and literals within plus or minus
 * 4000, which keeps sums of two columns and chains of offsets inside the range too.
 *
 * Budget: `-Dvarka.fuzz.iterations` (default 300, a few seconds); `-Dvarka.fuzz.seed` (default
 * fixed, so the committed run is reproducible and a nightly can vary it).
 */
class VarkaIrFuzzSuite extends SparkFunSuite {

  private val seed = sys.props.get("varka.fuzz.seed").map(_.toLong).getOrElse(20260903L)
  private val iterations = sys.props.get("varka.fuzz.iterations").map(_.toInt).getOrElse(300)
  private val only = sys.props.get("varka.fuzz.only").map(_.toInt)
  private val classCounter = new AtomicInteger(0)

  private val columnBound = 2500000L
  private val literalBound = 4000
  /** A calendar node may sit over a subtree whose value cannot leave this magnitude. */
  private val chronoBound = 5000000L
  private val lengths = Seq(1, 3, 7, 15, 16, 17, 33, 64, 65, 100, 257, 1000)

  /** A generated node with a bound on the magnitude of the value it can take. */
  private case class Gen(node: VarkaVectorIR, bound: Long)

  /** One iteration's shape generator; keeps a node budget so trees stay well inside the
   *  emitter's `MAX_FUSED_NODES` and `MAX_CHAIN_DEPTH`. */
  private class Shapes(rnd: Random, numInputs: Int, numLiterals: Int) {
    private var budget = 20

    private def leaf(): Gen =
      if (numLiterals > 0 && rnd.nextInt(4) == 0) {
        Gen(new LiteralSlot(rnd.nextInt(numLiterals)), literalBound)
      } else {
        Gen(new ColumnRef(rnd.nextInt(numInputs)), columnBound)
      }

    private def literal(): Gen =
      if (numLiterals > 0) Gen(new LiteralSlot(rnd.nextInt(numLiterals)), literalBound)
      else Gen(new ColumnRef(rnd.nextInt(numInputs)), columnBound)

    def value(depth: Int): Gen = {
      if (depth == 0 || budget <= 1) return leaf()
      budget -= 1
      rnd.nextInt(18) match {
        case 0 =>
          val a = value(depth - 1); val b = literal()
          Gen(new AddDays(a.node, b.node), a.bound + b.bound)
        case 1 =>
          val a = value(depth - 1); val b = literal()
          Gen(new SubDays(a.node, b.node), a.bound + b.bound)
        case 2 =>
          val a = value(depth - 1); val b = value(depth - 1)
          Gen(new DateDiff(a.node, b.node), a.bound + b.bound)
        case 3 =>
          val a = value(depth - 1); val b = value(depth - 1)
          Gen(new Greatest(a.node, b.node), math.max(a.bound, b.bound))
        case 4 =>
          val a = value(depth - 1); val b = value(depth - 1)
          Gen(new Least(a.node, b.node), math.max(a.bound, b.bound))
        case 5 =>
          val c = cond(depth - 1); val a = value(depth - 1); val b = value(depth - 1)
          Gen(new IfElse(c, a.node, b.node), math.max(a.bound, b.bound))
        case 6 =>
          val a = value(depth - 1)
          Gen(new DayOfWeek(a.node), 7)
        case 7 =>
          val a = value(depth - 1)
          Gen(new WeekDay(a.node), 6)
        case 8 =>
          // next_day's weekday as a literal slot (task 33) or, on the other draw, a column
          // (task 59's derived weekday leaf), whose values the reference reads like any int:
          // the lowering is exact for every k, so a date column serves as the weekday column.
          val a = value(depth - 1)
          val k = if (rnd.nextBoolean()) literal()
            else Gen(new ColumnRef(rnd.nextInt(numInputs)), columnBound)
          Gen(new NextDay(a.node, k.node), a.bound + 8)
        case 14 =>
          // make_date (task 42) over a date's own fields: always a valid triple in range, so
          // both modes run to status 0 and the answer is the date itself; every third one
          // takes a literal day instead under the NULL form, where an invalid day is a null
          // output and the batch still runs.
          val a = value(depth - 1)
          if (a.bound > chronoBound) return a
          if (numLiterals > 0 && rnd.nextInt(3) == 0) {
            val k = literal()
            Gen(new MakeDate(new Year(a.node), new Month(a.node), k.node, false), a.bound + 31)
          } else {
            Gen(new MakeDate(new Year(a.node), new Month(a.node), new DayOfMonth(a.node),
              rnd.nextBoolean()), a.bound)
          }
        case 15 =>
          // The Thursday of the day's week (task 37): a day-typed producer within three days.
          val a = value(depth - 1)
          Gen(new ThursdayOf(a.node), a.bound + 3)
        case 16 =>
          // weekofyear as the compiler builds it, the pair as a unit: the week tail is defined
          // over ThursdayOf only (the emitter refuses any other child), and the subtree has to
          // stay inside the narrowed range like every calendar node's.
          val a = value(depth - 1)
          if (a.bound > chronoBound) return a
          Gen(new WeekOfYear(new ThursdayOf(a.node)), 53)
        case 17 =>
          val a = value(depth - 1)
          Gen(new DayOfWeekIso(a.node), 7)
        case n =>
          // The calendar family, over a subtree that stays inside the narrowed range.
          val a = value(depth - 1)
          if (a.bound > chronoBound) return a
          n match {
            case 9 => Gen(new Year(a.node), 40000)
            case 10 => Gen(new Month(a.node), 12)
            case 11 => Gen(new DayOfMonth(a.node), 31)
            case 12 => Gen(new Quarter(a.node), 4)
            case _ => rnd.nextInt(4) match {
              case 0 => Gen(new DayOfYear(a.node), 366)
              case 2 if numLiterals > 0 =>
                // add_months' month count must be a literal slot, like next_day's weekday
                // (the emitter's message for it says next_day, which is the same check).
                val m = literal()
                Gen(new AddMonths(a.node, m.node), a.bound + m.bound * 31)
              case 3 =>
                // trunc (task 35) moves a date down by at most a year, so the child's bound
                // holds; the level is drawn at random so all three tails are fuzzed.
                val levels = TruncLevel.values()
                Gen(new TruncDate(a.node, levels(rnd.nextInt(levels.length))), a.bound)
              case _ => Gen(new LastDay(a.node), a.bound + 31)
            }
          }
      }
    }

    def cond(depth: Int): Cond = {
      budget -= 1
      if (depth == 0 || budget <= 1 || rnd.nextInt(3) == 0) {
        val ops = CompareOp.values()
        new Compare(ops(rnd.nextInt(ops.length)), value(depth).node, value(depth).node)
      } else {
        rnd.nextInt(4) match {
          case 0 => new And(cond(depth - 1), cond(depth - 1))
          case 1 => new Or(cond(depth - 1), cond(depth - 1))
          case 2 => new Not(cond(depth - 1))
          // IsNotNull reads a column's validity word directly, so its child must be a column.
          case _ => new IsNotNull(new ColumnRef(rnd.nextInt(numInputs)))
        }
      }
    }
  }

  /** A random variant of the options record, through its own `with*` methods. */
  private def randomOptions(rnd: Random): VarkaEmitOptions = {
    var opts = VarkaEmitOptions.DEFAULTS
    for (m <- classOf[VarkaEmitOptions].getMethods.sortBy(_.getName)
        if m.getName.startsWith("with") && m.getParameterCount == 1
          && !m.getName.toLowerCase(java.util.Locale.ROOT).contains("misdescribe")) {
      val param = m.getParameterTypes.head
      val value: Option[AnyRef] =
        if (param == classOf[Boolean]) Some(java.lang.Boolean.valueOf(rnd.nextBoolean()))
        else if (param.isEnum) {
          val constants = param.getEnumConstants.asInstanceOf[Array[AnyRef]]
          Some(constants(rnd.nextInt(constants.length)))
        } else if (param == classOf[Int] && rnd.nextInt(5) == 0) {
          // Task 46's lanesOverride is an emitted vector width, so it takes powers of two and
          // nothing else; the other int is groupBudget, which takes any positive number. The
          // widths above 16 have no specialised validity helpers and exercise the fallback.
          if (m.getName == "withLanesOverride") {
            Some(Integer.valueOf(Seq(2, 4, 8, 16, 32)(rnd.nextInt(5))))
          } else {
            Some(Integer.valueOf(Seq(8, 24, 32)(rnd.nextInt(3))))
          }
        } else None
      value.foreach(v => opts = m.invoke(opts, v).asInstanceOf[VarkaEmitOptions])
    }
    opts
  }

  private val patternNames = Seq("null-free", "every-5th", "alternating", "all-null", "random")

  private def pattern(rnd: Random, which: Int, length: Int): Int => Boolean = which match {
    case 0 => _ => false
    case 1 => i => i % 5 == 0
    case 2 => i => i % 2 == 1
    case 3 => _ => true
    case _ =>
      val bits = Array.fill(length)(rnd.nextInt(3) == 0)
      i => bits(i)
  }

  private def alloc(arena: Arena, bytes: Long): MemorySegment =
    arena.allocate(math.max(bytes, 1L), 8)

  private def runOne(iteration: Int): Unit = {
    val rnd = new Random(seed * 1000003L + iteration)
    val numInputs = 1 + rnd.nextInt(3)
    val numLiterals = rnd.nextInt(3)
    val shapes = new Shapes(rnd, numInputs, numLiterals)
    val depth = 1 + rnd.nextInt(4)
    // Either a projection of value roots or one selection root: the two kinds of kernel
    // production emits, never mixed in one class.
    val roots: Seq[VarkaVectorIR] =
      if (rnd.nextInt(5) == 0) Seq(shapes.cond(depth))
      else Seq.fill(1 + rnd.nextInt(3))(shapes.value(depth).node).distinct
    val lits = Array.fill(numLiterals)(rnd.nextInt(2 * literalBound + 1) - literalBound)
    val length = lengths(rnd.nextInt(lengths.length))
    val patternIds = Seq.fill(numInputs)(rnd.nextInt(patternNames.length))
    val patterns = patternIds.map(pattern(rnd, _, length))
    // Forcing the masked path reports one null over a full bitmap, which the dispatcher reads
    // as "has nulls". Never at length 1: a null count equal to the length is the contract's
    // all-null column, and the fuzzer's first run found exactly that contradiction (44 cases,
    // every one at length 1) before it found anything about the kernel.
    val forceMasked = length > 1 && rnd.nextInt(4) == 0
    val options = randomOptions(rnd)
    val data = Array.fill(numInputs, length)(
      (rnd.nextLong() % (2 * columnBound + 1) - columnBound).toInt.max(-columnBound.toInt)
        .min(columnBound.toInt))

    val context = s"seed=$seed iteration=$iteration " +
      s"roots=${roots.map(r => VarkaVectorIR.canonical(r)).mkString("[", ", ", "]")} " +
      s"options=${if (options.isDefault) "(defaults)" else options.canonical()} " +
      s"length=$length patterns=${patternIds.map(patternNames).mkString(",")} " +
      s"literals=${lits.mkString(",")} forceMasked=$forceMasked"

    val className =
      s"org.apache.spark.sql.varka.execution.VarkaFusedFuzz${classCounter.addAndGet(1)}"
    val bytes =
      try {
        VarkaLoopEmitter.emit(className, roots.asJava, numInputs, numLiterals, null, null, options)
      } catch {
        case e: IllegalArgumentException =>
          fail(s"$context: the emitter rejected the shape: ${e.getMessage}", e)
      }
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    loader.defineGeneratedClass(className, bytes)
    val kernel = loader.loadClass(className).getConstructor().newInstance()
      .asInstanceOf[VarkaFusedKernel]
    val arena = Arena.ofConfined()
    try {
      val srcData = new Array[Long](numInputs)
      val srcValidity = new Array[Long](numInputs)
      val nullCounts = new Array[Int](numInputs)
      for (c <- 0 until numInputs) {
        val d = alloc(arena, length * 4L)
        val v = alloc(arena, (length + 7) / 8L)
        v.fill(0.toByte)
        var nulls = 0
        for (i <- 0 until length) {
          d.set(ValueLayout.JAVA_INT, i * 4L, data(c)(i))
          if (patterns(c)(i)) nulls += 1
          else {
            val off = i / 8L
            v.set(ValueLayout.JAVA_BYTE, off,
              (v.get(ValueLayout.JAVA_BYTE, off) | (1 << (i % 8))).toByte)
          }
        }
        srcData(c) = d.address()
        nullCounts(c) = if (forceMasked && nulls == 0) 1 else nulls
        srcValidity(c) =
          if (nullCounts(c) == 0 || nulls == length) 0L else v.address()
      }
      val outs = roots.map { r =>
        val d = alloc(arena, length * 4L)
        for (i <- 0 until length) d.set(ValueLayout.JAVA_INT, i * 4L, 0xDEADBEEF)
        val v = alloc(arena, (length + 7) / 8L)
        v.fill(0xFF.toByte)
        (if (r.isInstanceOf[Cond]) 0L else d.address(), d, v)
      }
      val status = kernel.run(srcData, srcValidity, nullCounts, outs.map(_._1).toArray,
        outs.map(_._3.address()).toArray, lits, length)
      assert(status === 0, s"$context: the kernel declined the batch (status $status)")
      for (i <- 0 until length) {
        val row = (0 until numInputs).map(c => if (patterns(c)(i)) None else Some(data(c)(i)))
        for ((root, o) <- roots.zipWithIndex) {
          val bit = (outs(o)._3.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
          root match {
            case c: Cond =>
              val want = VarkaReferenceEvaluator.evalCond(c, row, lits).contains(true)
              assert(bit === want, s"$context: selection row $i differs (want $want)")
            case _ =>
              val want = VarkaReferenceEvaluator.evalValue(root, row, lits)
              assert(bit === want.isDefined,
                s"$context: validity of output $o row $i differs (want $want)")
              want.foreach { v =>
                assert(outs(o)._2.get(ValueLayout.JAVA_INT, i * 4L) === v,
                  s"$context: output $o row $i differs (want $v)")
              }
          }
        }
      }
    } finally {
      arena.close()
      loader.release()
    }
  }

  test(s"random IR trees match the reference evaluator (seed $seed, $iterations iterations)") {
    only match {
      case Some(k) => runOne(k)
      case None => (0 until iterations).foreach(runOne)
    }
  }
}
