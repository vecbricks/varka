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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaIrGrammar._
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.catalyst.util.DateTimeUtils

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
 * literal slot or a column, `next_day`'s weekday and `add_months`' month count are a literal
 * slot or a column, `IsNotNull` is over a column, a selection kernel has one condition root.
 *
 * Ranges: calendar nodes are defined over `VarkaChrono`'s narrowed day range, so every tree
 * carries a bound on the magnitude of its value and a calendar node is only put over a subtree
 * whose bound fits inside that range with slack. Columns hold days within plus or minus 2.5
 * million (about six thousand years either side of 1970) and literals within plus or minus
 * 4000, which keeps sums of two columns and chains of offsets inside the range too.
 *
 * The long lane has a corpus of its own, drawn from `VarkaIrGrammar.LongShapes` over 64-bit
 * columns and literals and run through the kernel's eight-argument entry point against
 * `evalLong`. It is a second sequence with a second seed rather than long shapes mixed into the
 * first, because the first is also the emitted-bytes oracle's committed corpus. The same
 * option draws apply, which is what puts `useAVX` under the constant division and so fuzzes
 * both of its lowerings on one machine.
 *
 * Budget: `-Dvarka.fuzz.iterations` (default 300, a few seconds); `-Dvarka.fuzz.seed` (default
 * fixed, so the committed run is reproducible and a nightly can vary it). Both apply to both
 * lanes.
 */
class VarkaIrFuzzSuite extends SparkFunSuite {

  private val seed = sys.props.get("varka.fuzz.seed").map(_.toLong).getOrElse(fuzzSeed)
  private val longSeed = sys.props.get("varka.fuzz.seed").map(_.toLong).getOrElse(longFuzzSeed)
  private val iterations = sys.props.get("varka.fuzz.iterations").map(_.toInt).getOrElse(300)
  private val only = sys.props.get("varka.fuzz.only").map(_.toInt)
  private val classCounter = new AtomicInteger(0)
  private val lengths = Seq(1, 3, 7, 15, 16, 17, 33, 64, 65, 100, 257, 1000)

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
          // The int setters do not share a domain, so each one that has its own is named. Task
          // 46's lanesOverride is an emitted vector width: powers of two and nothing else, the
          // ones above 16 having no specialised validity helpers and so exercising the
          // fallback. Task 88's useAVX is a machine's reported AVX level, whose interesting
          // boundary is 3 - below it a 64-bit division takes the magic-number form and at or
          // above it the conversions - so a range of large numbers would draw one of the two
          // lowerings every time and never the other. The rest is groupBudget or
          // fusedCeiling, which take any positive number.
          if (m.getName == "withLanesOverride") {
            Some(Integer.valueOf(Seq(2, 4, 8, 16, 32)(rnd.nextInt(5))))
          } else if (m.getName == "withUseAVX") {
            Some(Integer.valueOf(
              Seq(VarkaEmitOptions.USE_AVX_UNKNOWN, 0, 2, 3)(rnd.nextInt(4))))
          } else if (m.getName == "withMethodByteBudget") {
            // Task 87's switch: off, or the limit HotSpot enforces. A small number here would
            // later mean "every method is over budget", which is a decline, not a variant.
            Some(Integer.valueOf(Seq(0, 8000)(rnd.nextInt(2))))
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
    val rnd = shapeRandom(seed, iteration)
    // The shape itself comes from the shared draw, so this suite and the emitted-bytes oracle
    // run over one corpus; `rnd` is left where the lane values and null patterns below pick up.
    val Drawn(roots, numInputs, numLiterals, smallOrdinal, levelOrdinal) = drawShape(rnd)
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
    def draw(bound: Long): Int =
      (rnd.nextLong() % (2 * bound + 1) - bound).toInt.max(-bound.toInt).min(bound.toInt)
    val data = Array.tabulate(numInputs, length) { (c, _) =>
      if (c == levelOrdinal) {
        // Exactly the codes TruncLevelLeaf produces; a value outside them is a lane the
        // kernel's contract does not define, so the fuzzer must not invent one.
        DateTimeUtils.TRUNC_TO_WEEK + rnd.nextInt(
          DateTimeUtils.TRUNC_TO_YEAR - DateTimeUtils.TRUNC_TO_WEEK + 1)
      } else {
        draw(if (c == smallOrdinal) VarkaChrono.MONTH_ARITH_MAX_MONTHS.toLong else columnBound)
      }
    }

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
          if (patterns(c)(i)) {
            // Poisoned, not left at the drawn value (task 70's harness rule, the same one
            // VarkaEmitterTestBase.poison states). `data` is drawn inside `columnBound` and
            // `MONTH_ARITH_MAX_MONTHS`, so a null lane holding its drawn value is in range by
            // construction and can never reach a guard's condemning comparison - which is the
            // one thing the fuzzer is here to reach. Alternating on the null ordinal puts each
            // extreme on both sides of every bound whatever the null pattern is.
            d.set(ValueLayout.JAVA_INT, i * 4L,
              if ((nulls & 1) == 0) Int.MinValue else Int.MaxValue)
            nulls += 1
          } else {
            d.set(ValueLayout.JAVA_INT, i * 4L, data(c)(i))
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


  /**
   * `runOne` at the long lane: the same draw of length, null patterns, masking and options
   * over a long-lane shape, 64-bit buffers, the eight-argument `run`, and `evalLong` as the
   * oracle. Null lanes are poisoned with the lane's own extremes, for the reason `runOne`
   * gives: every drawn value is inside the guards and the checked modes by construction, so
   * only a poisoned null lane can reach a condemning comparison, and a kernel that reads one
   * has to be caught reading it.
   */
  private def runOneLong(iteration: Int): Unit = {
    val rnd = shapeRandom(longSeed, iteration)
    val DrawnLong(roots, numInputs, numLiterals) = drawLongShape(rnd)
    // A floor modulus, so a negative draw lands inside the bound too: a signed `%` would put
    // it as far as three bounds below zero, outside every guard the grammar drew.
    def draw(bound: Long): Long = Math.floorMod(rnd.nextLong(), 2 * bound + 1) - bound
    val lits = Array.fill(numLiterals)(draw(longLiteralBound))
    val length = lengths(rnd.nextInt(lengths.length))
    val patternIds = Seq.fill(numInputs)(rnd.nextInt(patternNames.length))
    val patterns = patternIds.map(pattern(rnd, _, length))
    val forceMasked = length > 1 && rnd.nextInt(4) == 0
    val options = randomOptions(rnd)
    val data = Array.tabulate(numInputs, length)((_, _) => draw(longColumnBound))

    val context = s"lane=long seed=$longSeed iteration=$iteration " +
      s"roots=${roots.map(r => VarkaVectorIR.canonical(r)).mkString("[", ", ", "]")} " +
      s"options=${if (options.isDefault) "(defaults)" else options.canonical()} " +
      s"length=$length patterns=${patternIds.map(patternNames).mkString(",")} " +
      s"literals=${lits.mkString(",")} forceMasked=$forceMasked"

    val className =
      s"org.apache.spark.sql.varka.execution.VarkaFusedFuzzLong${classCounter.addAndGet(1)}"
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
        val d = alloc(arena, length * 8L)
        val v = alloc(arena, (length + 7) / 8L)
        v.fill(0.toByte)
        var nulls = 0
        for (i <- 0 until length) {
          if (patterns(c)(i)) {
            d.set(ValueLayout.JAVA_LONG, i * 8L,
              if ((nulls & 1) == 0) Long.MinValue else Long.MaxValue)
            nulls += 1
          } else {
            d.set(ValueLayout.JAVA_LONG, i * 8L, data(c)(i))
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
        val d = alloc(arena, length * 8L)
        for (i <- 0 until length) d.set(ValueLayout.JAVA_LONG, i * 8L, 0xDEADBEEFCAFEBABEL)
        val v = alloc(arena, (length + 7) / 8L)
        v.fill(0xFF.toByte)
        (if (r.isInstanceOf[Cond]) 0L else d.address(), d, v)
      }
      val status = kernel.run(srcData, srcValidity, nullCounts, outs.map(_._1).toArray,
        outs.map(_._3.address()).toArray, Array.empty[Int], lits, length)
      assert(status === 0, s"$context: the kernel declined the batch (status $status)")
      for (i <- 0 until length) {
        val row = (0 until numInputs).map(c => if (patterns(c)(i)) None else Some(data(c)(i)))
        for ((root, o) <- roots.zipWithIndex) {
          val bit = (outs(o)._3.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0
          root match {
            case c: Cond =>
              val want = VarkaReferenceEvaluator.evalCondLong(c, row, lits).contains(true)
              assert(bit === want, s"$context: selection row $i differs (want $want)")
            case _ =>
              val want = VarkaReferenceEvaluator.evalLong(root, row, lits)
              assert(bit === want.isDefined,
                s"$context: validity of output $o row $i differs (want $want)")
              want.foreach { v =>
                assert(outs(o)._2.get(ValueLayout.JAVA_LONG, i * 8L) === v,
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

  /** The record node types of the sealed IR, by simple name. */
  private def recordNodeTypes: Set[Class[_]] = {
    def walk(c: Class[_]): Set[Class[_]] = {
      val subs = Option(c.getPermittedSubclasses).map(_.toSet).getOrElse(Set.empty[Class[_]])
      if (subs.isEmpty) Set(c) else subs.flatMap(walk)
    }
    walk(classOf[VarkaVectorIR]).filter(_.isRecord)
  }

  /** Every node type reachable from `node`, by simple name, added to `seen`. */
  private def collectNodeTypes(node: AnyRef, seen: scala.collection.mutable.Set[String]): Unit = {
    seen += node.getClass.getSimpleName
    node.getClass.getRecordComponents.foreach { rc =>
      val v = rc.getAccessor.invoke(node)
      if (v != null && classOf[VarkaVectorIR].isInstance(v)) {
        collectNodeTypes(v.asInstanceOf[AnyRef], seen)
      }
    }
  }

  /**
   * Whether a node type admits 64-bit lanes, asked of the type itself: its canonical
   * constructor is called once over long leaves, and a type that decomposes epoch days refuses
   * there (`requireInt`), while a lane-generic one constructs. Read off the constructors rather
   * than written as a list, so a node type added to the IR is classified by what it does and
   * the long-lane reach test below sees it without anyone editing this file.
   */
  private def admitsLongLanes(cls: Class[_]): Boolean = {
    val longCol = new ColumnRef(0, LaneType.LONG)
    val longCond = new Compare(CompareOp.LT, longCol, longCol)
    val components = cls.getRecordComponents
    val args: Array[AnyRef] = components.map { rc =>
      val t = rc.getType
      if (t == classOf[Cond]) longCond
      else if (classOf[VarkaVectorIR].isAssignableFrom(t)) longCol
      else if (t == java.lang.Long.TYPE) java.lang.Long.valueOf(1L)
      else if (t == java.lang.Integer.TYPE) Integer.valueOf(1)
      else if (t == java.lang.Boolean.TYPE) java.lang.Boolean.FALSE
      else if (t.isEnum) t.getEnumConstants.head.asInstanceOf[AnyRef]
      else fail(s"${cls.getSimpleName}.${rc.getName} has a component type this probe cannot " +
        s"build: ${t.getName}")
    }
    try {
      cls.getDeclaredConstructor(components.map(_.getType): _*).newInstance(args: _*)
      true
    } catch {
      case e: java.lang.reflect.InvocationTargetException
          if e.getCause.isInstanceOf[IllegalArgumentException] => false
    }
  }

  test("the generator reaches every IR node type") {
    // A green fuzz run says nothing about a node type the generator cannot build: the shapes
    // that would have exercised it are simply never drawn, and the suite reports success for
    // the ones it did draw. That is not hypothetical - `TruncDateDynamic` was outside this
    // generator from task 61 until this test was written, and the gap was found by reading the
    // arms rather than by anything failing.
    //
    // So the reachable set is asserted rather than assumed, against the sealed hierarchy itself
    // so a node type added to the IR fails here until the generator can build it. Generation
    // only: no bytes are emitted and nothing runs, so this is cheap enough to draw far more
    // shapes than the differential test does.
    val permitted = recordNodeTypes.map(_.getSimpleName)
    val seen = scala.collection.mutable.Set.empty[String]
    val rnd = new Random(seed)
    for (_ <- 0 until 20000) {
      val numInputs = 1 + rnd.nextInt(3)
      val numLiterals = rnd.nextInt(3)
      val smallOrdinal = if (numInputs > 1) numInputs - 1 else -1
      val levelOrdinal = if (numInputs > 2) numInputs - 2 else -1
      val shapes = new Shapes(rnd, numInputs, numLiterals, smallOrdinal, levelOrdinal)
      val depth = 1 + rnd.nextInt(4)
      val root: AnyRef = if (rnd.nextInt(5) == 0) shapes.cond(depth) else shapes.value(depth).node
      collectNodeTypes(root, seen)
    }
    // Deliberately out of reach: `NarrowLane` is admitted at an output root only, and the
    // generator composes nodes under other nodes, so a shape holding one is not a shape to fuzz
    // until task 28 lets a narrowing sit inside a tree; its root form is the emitter suite's.
    val missing = permitted -- seen - "NarrowLane"
    assert(missing.isEmpty,
      s"the generator never built: ${missing.toSeq.sorted.mkString(", ")} - add an arm, or " +
        "state here why the node type is deliberately out of the fuzzer's reach")
  }

  test("the long-lane generator reaches every node type that admits 64-bit lanes") {
    // The same assertion for the second corpus, against the set the IR itself defines: a node
    // type is in the long lane's reach exactly when its constructor accepts long leaves. So a
    // lane-generic node added to the IR fails here until `LongShapes` builds it, and a
    // calendar node - which refuses a 64-bit child where it is built - is not asked for.
    val (laneGeneric, intOnly) = recordNodeTypes.partition(admitsLongLanes)
    // The split has to be the one the emitter's javadoc describes, or the probe is not asking
    // the constructors what it thinks it is: every calendar node refuses, and the leaves and
    // the arithmetic accept.
    assert(intOnly.map(_.getSimpleName).contains("Year"))
    assert(laneGeneric.map(_.getSimpleName).contains("ConstDivide"))
    val seen = scala.collection.mutable.Set.empty[String]
    val rnd = new Random(longSeed)
    for (_ <- 0 until 20000) {
      val numInputs = 1 + rnd.nextInt(3)
      val numLiterals = rnd.nextInt(3)
      val shapes = new LongShapes(rnd, numInputs, numLiterals)
      val depth = 1 + rnd.nextInt(4)
      val root: AnyRef = if (rnd.nextInt(5) == 0) shapes.cond(depth) else shapes.value(depth).node
      collectNodeTypes(root, seen)
    }
    // `NarrowLane` constructs over long leaves and so counts as lane-generic here, and is out
    // of reach for the reason the int test states: a root-only node has no place in a tree.
    val missing = laneGeneric.map(_.getSimpleName) -- seen - "NarrowLane"
    assert(missing.isEmpty,
      s"the long-lane generator never built: ${missing.toSeq.sorted.mkString(", ")} - add an " +
        "arm to LongShapes, or state here why the node type is deliberately out of its reach")
  }

  test(s"random IR trees match the reference evaluator (seed $seed, $iterations iterations)") {
    only match {
      case Some(k) => runOne(k)
      case None => (0 until iterations).foreach(runOne)
    }
  }

  test(s"random long-lane IR trees match the reference evaluator (seed $longSeed, " +
      s"$iterations iterations)") {
    only match {
      case Some(k) => runOneLong(k)
      case None => (0 until iterations).foreach(runOneLong)
    }
  }
}
