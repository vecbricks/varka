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
 * first, because the first is also the emitted-bytes oracle's committed corpus. Its value roots
 * are narrowed to an int column where their bound fits (`LongShapes.root`, VARKA-235), and a
 * narrowing root's output is read at the store's four bytes a row. The same
 * option draws apply, which is what puts `useAVX` under the constant division and so fuzzes
 * both of its lowerings on one machine.
 *
 * Past the ceilings (VARKA-238): the byte budget is drawn small as well as off and 8000, so the
 * regroup and the declines happen at the widths drawn here, and a third test composes
 * `drawWideShape`'s roots into kernels of at least 250 outputs, past the driver's ceiling of about
 * 180 groups, under option variants that reach every size mechanism - the regroup, the call-site
 * splits and their rollback, the split driver's stages, both grouping switches dropped, and the
 * decline. Every composition is checked row by row, under the defaults where its variant
 * declines, and a run that reaches no instance of some mechanism fails (`VarkaEmitTrace`).
 *
 * Budget: `-Dvarka.fuzz.iterations` (default 300, a few seconds); `-Dvarka.fuzz.wide` (default
 * 10 compositions, a few seconds); `-Dvarka.fuzz.seed` (default fixed, so the committed run is
 * reproducible and a nightly can vary it). The iterations and the seed apply to both lanes.
 */
class VarkaIrFuzzSuite extends SparkFunSuite {

  private val seed = sys.props.get("varka.fuzz.seed").map(_.toLong).getOrElse(fuzzSeed)
  private val longSeed = sys.props.get("varka.fuzz.seed").map(_.toLong).getOrElse(longFuzzSeed)
  private val iterations = sys.props.get("varka.fuzz.iterations").map(_.toInt).getOrElse(300)
  private val only = sys.props.get("varka.fuzz.only").map(_.toInt)
  private val classCounter = new AtomicInteger(0)
  private val skippedPastTheCap = new AtomicInteger(0)
  // What the random shapes' emissions did about size, reported after each run (VARKA-238).
  private val randomTrace = new VarkaEmitTrace
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
          // fallback. VARKA-88's useAVX is a machine's reported AVX level, whose interesting
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
            // VARKA-87's switch: off, the limit HotSpot enforces, or a smaller budget that brings
            // the size machinery - the regroup, the stages, the declines - down to the widths
            // drawn here (VARKA-238). A single output over a small budget declines, and
            // `emitOrSkip` then runs the shape without the budget, so its answers are still
            // checked. One draw whatever the list's length, so the stream is not moved.
            Some(Integer.valueOf(Seq(0, 8000, 1000, 2000, 4000)(rnd.nextInt(5))))
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

  /**
   * The shape's class, or None for a shape no form of the emitter holds. Under the byte budget
   * a shape whose single output is over the budget declines with a reason, by design (VARKA-87);
   * the heaviest trees are the ones most worth checking, so such a shape is run in the form
   * without the budget rather than skipped. When that form declines too, or the budget was off
   * and it declined at once, the reason is the class-file cap on a method's code, the one limit
   * the legacy form has: the JVM holds no method the emitter could make of the shape, the
   * decline is the emitter's answer (VARKA-219), and the shape is counted and skipped. Anything
   * else the emitter throws is a failure - it rejected a shape the grammar builds.
   */
  private def emitOrSkip(context: String, options: VarkaEmitOptions)(
      emitWith: VarkaEmitOptions => Array[Byte]): Option[Array[Byte]] = {
    def pastTheCap(d: VarkaEmitDeclined): Option[Array[Byte]] = {
      assert(d.getMessage.contains("over the class-file cap of"),
        s"$context: declined with no budget to decline on: ${d.getMessage}")
      skippedPastTheCap.incrementAndGet()
      None
    }
    try {
      Some(emitWith(options))
    } catch {
      case d: VarkaEmitDeclined if options.methodByteBudget() > 0 =>
        assert(d.getMessage.contains("bytes"), s"$context: a size decline without a size")
        try {
          Some(emitWith(options.withMethodByteBudget(0)))
        } catch {
          case again: VarkaEmitDeclined => pastTheCap(again)
          case e: IllegalArgumentException =>
            fail(s"$context: the emitter rejected the shape without the budget: " +
              e.getMessage, e)
          case e: IllegalStateException =>
            fail(s"$context: the emitter failed its own check without the budget: " +
              e.getMessage, e)
        }
      case d: VarkaEmitDeclined => pastTheCap(d)
      case e: IllegalArgumentException =>
        fail(s"$context: the emitter rejected the shape: ${e.getMessage}", e)
      // One of the emitter's own invariants, such as the word check: named with the shape, so
      // a failure found at a high iteration count says which iteration to replay.
      case e: IllegalStateException =>
        fail(s"$context: the emitter failed its own check: ${e.getMessage}", e)
    }
  }

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
    def emitWith(o: VarkaEmitOptions): Array[Byte] =
      VarkaLoopEmitter.emitTraced(className, roots.asJava, numInputs, numLiterals, o, randomTrace)
    val bytes = emitOrSkip(context, options)(emitWith) match {
      case Some(b) => b
      case None => return
    }
    VarkaKernelCheck.runAndCompare(context, className, bytes, roots, numInputs, lits,
      VarkaKernelCheck.Batch(length, patterns, data, forceMasked))
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
    def emitWith(o: VarkaEmitOptions): Array[Byte] =
      VarkaLoopEmitter.emitTraced(className, roots.asJava, numInputs, numLiterals, o, randomTrace)
    val bytes = emitOrSkip(context, options)(emitWith) match {
      case Some(b) => b
      case None => return
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
        outs.map(_._3.address()).toArray, Array.empty[Int], lits, length,
        VarkaEmitterTestSupport.scratch(kernel, length))
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
              // A narrowing root stores four bytes a row; read at that width and sign-extended,
              // a value that did not fit 32 bits differs from the evaluator's 64-bit answer
              // instead of being truncated on both sides.
              val got: Long = root match {
                case _: NarrowLane => outs(o)._2.get(ValueLayout.JAVA_INT, i * 4L).toLong
                case _ => outs(o)._2.get(ValueLayout.JAVA_LONG, i * 8L)
              }
              want.foreach { v =>
                assert(got === v, s"$context: output $o row $i differs (want $v)")
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
      else if (t == classOf[java.util.List[_]]) {
        java.util.List.of(Integer.valueOf(1), Integer.valueOf(2))
      }
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
    // generator from VARKA-61 until this test was written, and the gap was found by reading the
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
    // Deliberately out of this generator's reach: `NarrowLane` takes a long child, nothing in
    // the IR widens an int lane, and this grammar builds int trees only, so no int shape can
    // hold one. The long-lane generator draws it at a root, and the test below asserts that.
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
      // Through `root`, as `drawLongShape` draws, so the narrowing an output root may carry is
      // asked for with the rest: it constructs over long leaves, so it counts as lane-generic.
      val root: AnyRef = if (rnd.nextInt(5) == 0) shapes.cond(depth) else shapes.root(depth)
      collectNodeTypes(root, seen)
    }
    val missing = laneGeneric.map(_.getSimpleName) -- seen
    assert(missing.isEmpty,
      s"the long-lane generator never built: ${missing.toSeq.sorted.mkString(", ")} - add an " +
        "arm to LongShapes, or state here why the node type is deliberately out of its reach")
  }

  // ---------------------------------------------------------------------------------------------
  // Wide compositions: past the ceilings the one-to-three-root draw never reaches (VARKA-238).
  // ---------------------------------------------------------------------------------------------

  private val wideIterations = sys.props.get("varka.fuzz.wide").map(_.toInt).getOrElse(10)

  /**
   * The options the wide compositions cycle through, each aimed at mechanisms that only a kernel
   * past the driver's ceiling of about 180 groups reaches: the stages, the grouping switches
   * dropped before a decline, the call-site splits and their rollback, the regroup under a small
   * budget, and the declines themselves.
   */
  private val wideVariants: Seq[(String, VarkaEmitOptions)] = {
    // The variants are the size loop's, with the plan and the prediction off (VARKA-236): under
    // both the first build is the last and no mechanism is reached. The defaults run beside
    // them, planned, so every composition is also checked as production emits it.
    val d = VarkaEmitOptions.DEFAULTS.withPlanSize(false).withPredictGrouping(false)
    Seq(
      "the defaults, planned" -> VarkaEmitOptions.DEFAULTS,
      "split driver" -> d.withSplitDriver(true),
      "whole driver" -> d.withSplitDriver(false),
      "whole driver, predicted grouping" -> d.withSplitDriver(false).withPredictGrouping(true),
      "whole driver, call sites split" ->
        d.withSplitDriver(false).withCallSiteBudget(8).withHeavyGroupOutputs(2),
      "split driver, 2000-byte budget" -> d.withSplitDriver(true).withMethodByteBudget(2000))
  }

  private var wideDeclines = 0
  private var wideChecked = 0

  /**
   * One wide composition: `drawWideShape`'s value roots, drawn until there are at least 250, over
   * the draws' shared column layout. A column keeps the narrowest domain any draw gives it - trunc
   * levels, then month counts, then days - since each is inside the next. The kernel it emits is
   * checked row by row like a drawn shape's; a decline must name a size.
   */
  private def runWide(iteration: Int, trace: VarkaEmitTrace): Unit = {
    val rnd = shapeRandom(seed ^ 0x57494445L, iteration)
    val (label, options) = wideVariants(iteration % wideVariants.size)
    val draws = scala.collection.mutable.ArrayBuffer.empty[Drawn]
    while (draws.map(_.roots.size).sum < 250 && draws.size < 12) {
      draws += drawWideShape(rnd)
    }
    val roots = draws.flatMap(_.roots).distinct.toSeq
    val numInputs = draws.map(_.numInputs).max
    val numLiterals = draws.map(_.numLiterals).max
    val levels = draws.map(_.levelOrdinal).filter(_ >= 0).toSet
    val small = draws.map(_.smallOrdinal).filter(_ >= 0).toSet
    val lits = Array.fill(numLiterals)(rnd.nextInt(2 * literalBound + 1) - literalBound)
    val length = lengths(rnd.nextInt(lengths.length))
    val patternIds = Seq.fill(numInputs)(rnd.nextInt(patternNames.length))
    val patterns = patternIds.map(pattern(rnd, _, length))
    val forceMasked = length > 1 && rnd.nextInt(4) == 0
    def draw(bound: Long): Int =
      (rnd.nextLong() % (2 * bound + 1) - bound).toInt.max(-bound.toInt).min(bound.toInt)
    val data = Array.tabulate(numInputs, length) { (c, _) =>
      if (levels(c)) {
        DateTimeUtils.TRUNC_TO_WEEK + rnd.nextInt(
          DateTimeUtils.TRUNC_TO_YEAR - DateTimeUtils.TRUNC_TO_WEEK + 1)
      } else {
        draw(if (small(c)) VarkaChrono.MONTH_ARITH_MAX_MONTHS.toLong else columnBound)
      }
    }
    val context = s"wide seed=$seed iteration=$iteration ($label) roots=${roots.size} " +
      s"inputs=$numInputs length=$length patterns=${patternIds.map(patternNames).mkString(",")} " +
      s"literals=${lits.mkString(",")} forceMasked=$forceMasked"
    val className =
      s"org.apache.spark.sql.varka.execution.VarkaFusedFuzzWide${classCounter.addAndGet(1)}"
    def emitWith(o: VarkaEmitOptions, into: VarkaEmitTrace): Option[Array[Byte]] =
      try {
        Some(VarkaLoopEmitter.emitTraced(className, roots.asJava, numInputs, numLiterals, o,
          into))
      } catch {
        case d: VarkaEmitDeclined =>
          assert(d.getMessage.contains("bytes"), s"$context: a decline names no size: " +
            d.getMessage)
          None
      }
    // A variant that declines is the answer it gives, counted; the composition's answers are
    // then checked under the defaults, where the split driver serves it, on a trace of its own
    // so the retry does not count as the variant reaching a mechanism.
    val bytes = emitWith(options, trace).orElse {
      wideDeclines += 1
      emitWith(VarkaEmitOptions.DEFAULTS, new VarkaEmitTrace)
    }
    bytes.foreach { b =>
      VarkaKernelCheck.runAndCompare(context, className, b, roots, numInputs, lits,
        VarkaKernelCheck.Batch(length, patterns, data, forceMasked))
      wideChecked += 1
    }
  }

  test(s"wide compositions past the driver's ceiling match the reference evaluator, and reach " +
      s"every size mechanism (seed $seed, $wideIterations compositions)") {
    val trace = new VarkaEmitTrace
    wideDeclines = 0
    wideChecked = 0
    (0 until wideIterations).foreach(runWide(_, trace))
    // Under the defaults nothing of this width declines: the split driver serves every one.
    assert(wideChecked === wideIterations,
      s"$wideChecked of $wideIterations compositions ran against the reference evaluator")
    val reached = Seq(
      "a group halved on bytes" -> trace.byteRegroups,
      "a group split on call sites" -> trace.siteSplits,
      "the call-site splits rolled back" -> trace.siteRollbacks,
      "a driver split into stages" -> trace.stageSplits,
      "the exact grouping dropped" -> trace.exactFallbacks,
      "the prediction dropped" -> trace.predictFallbacks,
      "a decline" -> wideDeclines)
    reached.foreach { case (mechanism, n) => info(s"$mechanism: $n") }
    // Every variant runs at least once only from a full cycle up.
    if (wideIterations >= wideVariants.size) {
      val missed = reached.collect { case (mechanism, 0) => mechanism }
      assert(missed.isEmpty, s"no wide composition reached: ${missed.mkString(", ")}")
    }
  }

  test(s"random IR trees match the reference evaluator (seed $seed, $iterations iterations)") {
    only match {
      case Some(k) => runOne(k)
      case None => (0 until iterations).foreach(runOne)
    }
    reportSkipped()
  }

  test(s"random long-lane IR trees match the reference evaluator (seed $longSeed, " +
      s"$iterations iterations)") {
    only match {
      case Some(k) => runOneLong(k)
      case None => (0 until iterations).foreach(runOneLong)
    }
    reportSkipped()
  }

  /**
   * The shapes skipped as past the class-file cap, reported and bounded: the night that found
   * them saw one in three hundred thousand trees per lane, so a run that skips more than a few
   * per thousand has either a heavier grammar or an emitter that declines where it should
   * build, and neither may pass quietly.
   */
  private def reportSkipped(): Unit = {
    info(s"size control over the run: ${randomTrace.builds} builds, " +
      s"${randomTrace.byteRegroups} regroups on bytes, ${randomTrace.siteSplits} call-site " +
      s"splits, ${randomTrace.siteRollbacks} rolled back, ${randomTrace.stageSplits} stage " +
      s"splits, ${randomTrace.fallbacks()} grouping switches dropped")
    val skipped = skippedPastTheCap.getAndSet(0)
    if (skipped > 0) {
      info(s"$skipped shape(s) past the class-file cap on a method in every form the emitter " +
        "has: declined, and skipped (VARKA-219)")
    }
    assert(skipped <= 2 + iterations / 1000,
      s"$skipped shapes skipped as past the class-file cap in $iterations iterations")
  }
}
