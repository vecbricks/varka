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

import java.lang.foreign.{Arena, ValueLayout}
import java.util.{Collections, List => JList}

import org.apache.spark.sql.catalyst.expressions
import org.apache.spark.sql.catalyst.expressions.{Expression, Literal}
import org.apache.spark.sql.catalyst.expressions.codegen.{CompiledVarkaProjection,
  PartialVarkaProjection, VarkaOutputSpec}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaReference.{Truth => Tri}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.types.{BooleanType, ByteType, DataType, IntegerType, LongType}

/**
 * A BOOLEAN column as a condition (the boolean leaf, constants, comparisons, choice and null-safe
 * equality), run through emitted kernels over every state of every leaf. A leaf is in one of four
 * states: true, false, null with the data bit clear and null with the data bit set (Spark's writer
 * clears only the validity bit, so the data bit under a null is arbitrary). A tree over k leaves
 * has 4^k combinations; one row per combination, rotated so that each lands on every lane
 * position and in the epilogue's partial group, at every width of the sweep. The oracle is
 * [[VarkaReference]], whose boolean nodes are held to Spark's own `eval` in the first test.
 *
 * <p>In its own JVM, as every suite that sweeps `lanesOverride`.
 */
class VarkaEmitterBoolSuite extends VarkaEmitterTestBase with VarkaOwnJvm {

  private val leaves = (0 until 4).map(new BoolColumn(_))
  private val Seq(b0, b1, b2, b3) = leaves

  private val widths: Seq[(String, VarkaEmitOptions)] = Seq(
    "four lanes" -> VarkaMatrix.base.withLanesOverride(4),
    "eight lanes" -> VarkaMatrix.base.withLanesOverride(8),
    "the default width" -> VarkaMatrix.base)

  /** The four leaf states: true, false, null with data bit 0, null with data bit 1. */
  private val allStates = Seq(0, 1, 2, 3)
  private val validStates = Seq(0, 1)
  private def dataBit(state: Int): Boolean = state == 0 || state == 3
  private def isNullState(state: Int): Boolean = state >= 2

  /**
   * Runs `roots` over `k` boolean leaves, one row per combination of `states`, rotated by every
   * offset in `offsets`, at each length, and compares each row with the reference: a condition's
   * selection bit, a value's validity bit and, where valid, its value at the root's lane, or a
   * boolean output's data and validity bits. `perColumn`, when given, is each leaf's own set of
   * states in place of `states`, so that a leaf can be null on every row.
   */
  private def check(
      roots: Seq[VarkaVectorIR],
      k: Int,
      states: Seq[Int],
      lengths: Seq[Int],
      offsets: Seq[Int],
      options: VarkaEmitOptions,
      ctx: String,
      perColumn: Seq[Seq[Int]] = Nil): Unit = {
    val (kernel, loader) = load(emitMulti(roots, k, 0, options))
    val columnStates = if (perColumn.isEmpty) Seq.fill(k)(states) else perColumn
    // Mixed radix: column c's digit is the row's quotient by the product of the earlier radices.
    val radix = columnStates.scanLeft(1)(_ * _.size)
    val combos = radix(k)
    try {
      for (length <- lengths; off <- offsets) {
        def stateOf(c: Int, row: Int): Int =
          columnStates(c)(((row + off) / radix(c)) % columnStates(c).size)
        val arena = Arena.ofConfined()
        try {
          // Exact-size bitmaps, with ones in the unused bits of the last byte: Arrow promises
          // nothing about them, and a read of the rows past the length must not reach the answer.
          val bytes = ((length + 7) / 8).toLong
          val data = Array.fill(k)(arena.allocate(bytes))
          val valid = Array.fill(k)(arena.allocate(bytes))
          for (seg <- data ++ valid; row <- length until bytes.toInt * 8) {
            seg.set(ValueLayout.JAVA_BYTE, row / 8L,
              (seg.get(ValueLayout.JAVA_BYTE, row / 8L) | (1 << (row % 8))).toByte)
          }
          val nullCounts = new Array[Int](k)
          for (c <- 0 until k; row <- 0 until length) {
            val s = stateOf(c, row)
            def set(seg: java.lang.foreign.MemorySegment, on: Boolean): Unit =
              if (on) {
                seg.set(ValueLayout.JAVA_BYTE, row / 8L,
                  (seg.get(ValueLayout.JAVA_BYTE, row / 8L) | (1 << (row % 8))).toByte)
              }
            set(data(c), dataBit(s))
            set(valid(c), !isNullState(s))
            if (isNullState(s)) nullCounts(c) += 1
          }
          for (fill <- VarkaKernelCheck.bothFills) {
            // Exactly a row's width at the root's lane, so a store past the batch faults; a
            // condition root's data address is 0L by the kernel contract, so a write through it
            // faults too.
            // A boolean output's data is a bitmap of whole words, as a BitVector allocates it,
            // its stale bits the fixture's garbage, which the driver must zero.
            val outs = roots.map { root =>
              val ints = root match {
                case _: BoolOutput => ((length + 63) / 64) * 2
                case _: Cond => length
                case _ if root.laneType() == LaneType.LONG => 2 * length
                case _ => length
              }
              makeOutput(arena, ints, outputValidityBytes(options, length), fill)
            }
            val dstData = roots.zip(outs).map { case (root, out) =>
              if (root.isInstanceOf[Cond]) 0L else out._1.address()
            }.toArray
            val scratch = VarkaEmitterTestSupport.scratch(kernel, length)
            // A kernel holding a long-lane root is a 64-bit-lane kernel, which has its own entry.
            val status = if (VarkaKernelPlan.of(java.util.List.of(roots: _*), options).drive() ==
                LaneType.LONG) {
              kernel.run(data.map(_.address()), valid.map(_.address()), nullCounts,
                dstData, outs.map(_._2.address()).toArray, Array.empty[Int],
                Array.empty[Long], length, scratch)
            } else {
              kernel.run(data.map(_.address()), valid.map(_.address()), nullCounts,
                dstData, outs.map(_._2.address()).toArray, Array.empty[Int],
                length, scratch)
            }
            assert(status === 0, s"$ctx: declined (length $length, offset $off, $fill)")
            for (row <- 0 until length; (root, o) <- roots.zipWithIndex) {
              val ints = Array.tabulate(k)(c => if (dataBit(stateOf(c, row))) 1 else 0)
              val nulls = Array.tabulate(k)(c => isNullState(stateOf(c, row)))
              val bit = (outs(o)._2.get(ValueLayout.JAVA_BYTE, row / 8L) & (1 << (row % 8))) != 0
              def where: String = s"$ctx: root $o ${VarkaVectorIR.canonical(root)} length " +
                s"$length offset $off row $row states " +
                s"${(0 until k).map(c => stateOf(c, row)).mkString(",")} $fill (of $combos)"
              root match {
                case b: BoolOutput =>
                  // Spark's three-valued answer: the data bit is the known-true row, the
                  // validity bit the known one, so an unknown row is null, never false.
                  val want = VarkaReference.cond(b.cond(), ints, nulls, Array.empty[Int])
                  val data =
                    (outs(o)._1.get(ValueLayout.JAVA_BYTE, row / 8L) & (1 << (row % 8))) != 0
                  assert(bit === (want != Tri.UNKNOWN), s"$where: validity (want $want)")
                  assert(data === want.isTrue, s"$where: data (want $want)")
                case c: Cond =>
                  val want = VarkaReference.cond(c, ints, nulls, Array.empty[Int]).isTrue
                  assert(bit === want, where)
                case v if v.laneType() == LaneType.LONG =>
                  // A tree of both lanes (an int-lane value widened) is answered lane by lane.
                  val want = if (VarkaVectorIR.lanesIn(v).size() > 1) {
                    VarkaReference.mixedValue(v, ints.map(_.toLong), nulls, Array.empty[Int],
                      Array.empty[Long])
                  } else {
                    VarkaReference.longValue(v, ints.map(_.toLong), nulls, Array.empty[Long])
                  }
                  assert(bit === want.isPresent, s"$where: validity (want $want)")
                  if (want.isPresent) {
                    assert(outs(o)._1.get(ValueLayout.JAVA_LONG, row * 8L) === want.getAsLong,
                      s"$where: value")
                  }
                case v =>
                  val want = VarkaReference.value(v, ints, nulls, Array.empty[Int])
                  assert(bit === want.isPresent, s"$where: validity (want $want)")
                  if (want.isPresent) {
                    assert(outs(o)._1.get(ValueLayout.JAVA_INT, row * 4L) === want.getAsInt,
                      s"$where: value")
                  }
              }
            }
          }
        } finally {
          arena.close()
        }
      }
    } finally {
      loader.release()
    }
  }

  private val tailLengths = Seq(1, 7, 8, 9, 17, 63, 64, 65)

  /** The sweep for `k` leaves: a length that holds every combination and short tails, rotated. */
  private def sweep(roots: Seq[VarkaVectorIR], k: Int, ctx: String,
      widthSet: Seq[(String, VarkaEmitOptions)] = widths): Unit = {
    val full = Math.pow(allStates.size, k).toInt + 13
    for ((w, options) <- widthSet) {
      check(roots, k, allStates, tailLengths :+ full, 0 until 16, options, s"$ctx at $w")
      // Dense: only valid leaves, so the null-free body runs.
      check(roots, k, validStates, Seq(1, 9, 65), 0 until 4, options, s"$ctx (dense) at $w")
    }
  }

  private def trueOf(v: Option[Boolean]): Literal = Literal.create(v.orNull, BooleanType)

  test("the reference answers booleans as Spark does, for every state of each operand") {
    val tri = Seq[Option[Boolean]](Some(true), Some(false), None)
    def lit(v: Option[Boolean]): Expression = trueOf(v)
    def want(e: Expression): Tri = e.eval(null) match {
      case null => Tri.UNKNOWN
      case v: java.lang.Boolean => if (v) Tri.TRUE else Tri.FALSE
    }
    def leaf(v: Option[Boolean]): (Int, Boolean) = (if (v.contains(true)) 1 else 0, v.isEmpty)
    val ops = Seq(CompareOp.LT -> ((a: Expression, b: Expression) => expressions.LessThan(a, b)),
      CompareOp.LE -> ((a: Expression, b: Expression) => expressions.LessThanOrEqual(a, b)),
      CompareOp.GT -> ((a: Expression, b: Expression) => expressions.GreaterThan(a, b)),
      CompareOp.GE -> ((a: Expression, b: Expression) => expressions.GreaterThanOrEqual(a, b)),
      CompareOp.EQ -> ((a: Expression, b: Expression) => expressions.EqualTo(a, b)))
    for (x <- tri; y <- tri; z <- tri) {
      val (xi, xn) = leaf(x)
      val (yi, yn) = leaf(y)
      val (zi, zn) = leaf(z)
      val row = Array(xi, yi, zi)
      val nulls = Array(xn, yn, zn)
      def ref(c: Cond): Tri = VarkaReference.cond(c, row, nulls, Array.empty[Int])
      for ((op, mk) <- ops) {
        assert(ref(new BoolCompare(op, b0, b1)) === want(mk(lit(x), lit(y))), s"$op $x $y")
      }
      assert(ref(new BoolNullSafeEq(b0, b1)) === want(expressions.EqualNullSafe(lit(x), lit(y))))
      assert(ref(new CondIf(b0, b1, b2)) === want(expressions.If(lit(x), lit(y), lit(z))))
      assert(ref(new CondIf(new IsNotNull(b0), b0, b1)) ===
        want(expressions.Coalesce(Seq(lit(x), lit(y)))))
      assert(ref(new And(b0, b1)) === want(expressions.And(lit(x), lit(y))))
      assert(ref(new Or(b0, b1)) === want(expressions.Or(lit(x), lit(y))))
      assert(ref(new Not(b0)) === want(expressions.Not(lit(x))))
      assert(ref(new Not(new IsNotNull(b0))) === want(expressions.IsNull(lit(x))))
    }
  }

  /** Every And/Or/Not tree over three leaves of up to `maxOps` operators, one per truth table. */
  private def trees(maxOps: Int): Seq[Cond] = {
    val byOps = scala.collection.mutable.ArrayBuffer[Seq[Cond]](Seq(b0, b1, b2))
    val seen = scala.collection.mutable.Set[Seq[Tri]]()
    def signature(c: Cond): Seq[Tri] = for (s <- 0 until 64) yield {
      val ints = Array.tabulate(3)(i => if (dataBit(s >> (2 * i) & 3)) 1 else 0)
      val nulls = Array.tabulate(3)(i => isNullState(s >> (2 * i) & 3))
      VarkaReference.cond(c, ints, nulls, Array.empty[Int])
    }
    byOps(0).foreach(c => seen += signature(c))
    for (n <- 1 to maxOps) {
      val made = scala.collection.mutable.ArrayBuffer[Cond]()
      def offer(c: Cond): Unit = if (seen.add(signature(c))) made += c
      byOps(n - 1).foreach(x => offer(new Not(x)))
      for (l <- 0 until n; x <- byOps(l); y <- byOps(n - 1 - l)) {
        offer(new And(x, y))
        offer(new Or(x, y))
      }
      byOps += made.toSeq
    }
    byOps.flatten.toSeq
  }

  test("every And/Or/Not tree over three boolean columns, one per distinct truth table, in " +
      "both bodies at every width") {
    val all = trees(4)
    assert(all.size > 100, s"only ${all.size} distinct trees")
    all.grouped(8).foreach(group => sweep(group, 3, s"trees ${group.map(VarkaVectorIR.canonical)}"))
  }

  test("boolean comparisons, null-safe equality, choice and constants over every leaf state") {
    val consts = Seq(Truth.TRUE, Truth.FALSE, Truth.NULL).map(new CondConst(_))
    val cmp = CompareOp.values().toSeq.map(op => new BoolCompare(op, b0, b1))
    sweep(cmp, 2, "boolean comparison")
    sweep(Seq(new BoolNullSafeEq(b0, b1), new Not(new BoolNullSafeEq(b0, b1))), 2, "<=>")
    sweep(Seq(new CondIf(b0, b1, b2), new CondIf(new IsNotNull(b0), b0, b1),
      new CondIf(b0, new Not(b1), new And(b1, b2))), 3, "choice")
    sweep(Seq(new And(b0, new BoolCompare(CompareOp.LE, b1, b2)),
      new Or(new Not(b0), new BoolNullSafeEq(b1, b2))), 3, "mixed")
    // Constants: an unknown from valid inputs, which the dense body could not hold.
    val withConst = consts.flatMap(c => Seq(new And(b0, c), new Or(b0, c), new Not(c),
      new BoolCompare(CompareOp.EQ, b0, c), new BoolNullSafeEq(b0, c), new CondIf(b0, c, b1)))
    sweep(withConst, 2, "constants")
    sweep(Seq(new And(b0, new CondConst(Truth.NULL))), 1, "NULL beside a valid column")
  }

  test("IS [NOT] NULL over a bare boolean column is its validity word, over a predicate the " +
      "predicate being known") {
    val roots = Seq(new IsNotNull(b0), new Not(new IsNotNull(b0)),
      new And(b0, new IsNotNull(b0)), new IsNotNull(new And(b0, b1)),
      new Not(new IsNotNull(new BoolCompare(CompareOp.LT, b0, b1))))
    sweep(roots, 2, "null tests")
    // The bare-column test takes the column form: no condition is lowered under it, so the
    // kernel keeps a dense body (the special case of a test over a predicate is not taken).
    val bytes = emitMulti(Seq(new IsNotNull(b0)), 1, 0)._2
    assert(VarkaEmitterTestSupport.codeSize(bytes, "loopDense0") > 0)
  }

  test("boolean leaves on the long lane") {
    // A boolean column's lane is the lane of what it meets (a long comparison beside it), so
    // the kernels below drive their loop at the long species while the bitmaps are unchanged.
    val Seq(l0, l1, l2) = (0 until 3).map(new BoolColumn(_, LaneType.LONG))
    val long = Seq(l0, new And(l0, new Not(l1)), new BoolCompare(CompareOp.LT, l0, l1),
      new BoolNullSafeEq(l0, l1), new CondIf(l0, l1, l2),
      new Or(new CondConst(Truth.NULL, LaneType.LONG), l2))
    long.foreach(r =>
      assert(VarkaKernelPlan.emissionLane(r, VarkaEmitOptions.DEFAULTS) === LaneType.LONG,
        r.toString))
    sweep(long, 3, "long-lane leaves")
    // One column at both lanes in one kernel needs a value output (a WidenLane over an int-lane
    // IF), which this suite's selection harness cannot read; the differential suite's mixed-lane
    // test holds that case to Spark's rows.
  }

  test("four leaves, every combination, for the connectives and a comparison tree") {
    val root = new Or(new And(b0, new Not(b1)), new BoolCompare(CompareOp.GE, b2, b3))
    sweep(Seq(root), 4, "four leaves", Seq(widths.last))
  }

  // The condition as a value (VARKA-460): CondValue is CAST(c AS INT), or BIGINT on the long
  // lane - 1, 0, or null where the condition is unknown, which IF(c, 1, 0) is not.

  /** `node` with every boolean leaf on the long lane, and so every condition over them. */
  private def onLong(node: VarkaVectorIR): VarkaVectorIR = node match {
    case b: BoolColumn => new BoolColumn(b.ordinal(), LaneType.LONG)
    case c: CondConst => new CondConst(c.value(), LaneType.LONG)
    case n => VarkaVectorIR.withChildren(n, VarkaVectorIR.childrenOf(n).map(onLong): _*)
  }

  test("the reference answers a condition as a value as Spark's cast does, for every state of " +
      "each operand") {
    val tri = Seq[Option[Boolean]](Some(true), Some(false), None)
    def leaf(v: Option[Boolean]): (Int, Boolean) = (if (v.contains(true)) 1 else 0, v.isEmpty)
    def want(e: Expression): Option[Long] = Option(e.eval(null)).map {
      case i: java.lang.Integer => i.longValue
      case l: java.lang.Long => l.longValue
    }
    for (x <- tri; y <- tri; z <- tri) {
      val Seq((xi, xn), (yi, yn), (zi, zn)) = Seq(x, y, z).map(leaf)
      val row = Array(xi, yi, zi)
      val nulls = Array(xn, yn, zn)
      val Seq(lx, ly, lz) = Seq(x, y, z).map(trueOf)
      val pairs: Seq[(Cond, Expression)] = Seq(
        b0 -> lx,
        new Not(b0) -> expressions.Not(lx),
        new And(b0, b1) -> expressions.And(lx, ly),
        new Or(b0, b1) -> expressions.Or(lx, ly),
        new And(b0, new CondConst(Truth.NULL)) -> expressions.And(lx, trueOf(None)),
        new CondConst(Truth.NULL) -> trueOf(None),
        new BoolCompare(CompareOp.LT, b0, b1) -> expressions.LessThan(lx, ly),
        new BoolNullSafeEq(b0, b1) -> expressions.EqualNullSafe(lx, ly),
        new CondIf(b0, b1, b2) -> expressions.If(lx, ly, lz),
        new IsNotNull(b0) -> expressions.IsNotNull(lx))
      for ((c, e) <- pairs) {
        val ctx = s"${VarkaVectorIR.canonical(c)} at $x $y $z"
        val int = VarkaReference.value(new CondValue(c, LaneType.INT), row, nulls,
          Array.empty[Int])
        assert((if (int.isPresent) Some(int.getAsInt.toLong) else None) ===
          want(expressions.Cast(e, IntegerType)), ctx)
        val longCond = onLong(c).asInstanceOf[Cond]
        val long = VarkaReference.longValue(new CondValue(longCond, LaneType.LONG),
          row.map(_.toLong), nulls, Array.empty[Long])
        assert((if (long.isPresent) Some(long.getAsLong) else None) ===
          want(expressions.Cast(e, LongType)), ctx + " (long)")
        // And it is not IF(c, 1, 0), which answers 0 where the condition is unknown.
        if (x.isEmpty && c == b0) {
          assert(want(expressions.If(lx, Literal(1), Literal(0))) === Some(0L))
          assert(!int.isPresent)
        }
      }
    }
  }

  test("a condition as a value over every leaf state, in both bodies at every width") {
    def v(c: Cond): CondValue = new CondValue(c, LaneType.INT)
    sweep(Seq(v(b0), v(new Not(b0)), v(new IsNotNull(b0)), v(new And(b0, b1)),
      v(new Or(b0, new Not(b1)))), 2, "condition as a value")
    sweep(Seq(v(new BoolCompare(CompareOp.LT, b0, b1)), v(new BoolNullSafeEq(b0, b1)),
      v(new CondIf(b0, b1, b2)), v(new CondConst(Truth.TRUE)), v(new CondConst(Truth.FALSE))),
      3, "comparison, choice and constants as values")
  }

  test("a condition as a value feeds the value operators the casts and bit_count will build on") {
    def v(c: Cond): CondValue = new CondValue(c, LaneType.INT)
    sweep(Seq(
      new IntArith(IntOp.ADD, Overflow.WRAP, v(b0), v(b1)),
      new IntArith(IntOp.SUB, Overflow.FAIL, v(b0), v(new Not(b1))),
      new BitCount(v(b0)),
      new IfElse(b2, v(b0), v(b1)),
      new Greatest(v(b0), v(b1)),
      // One condition read as a value twice and as a condition once: the CSE'd mask serves all.
      new IntArith(IntOp.MUL, Overflow.WRAP, v(b0), new IfElse(b0, v(b1), v(b2)))),
      3, "values over conditions")
  }

  test("a condition as a value over AND or OR is known over an all-null column, each alone in " +
      "its kernel") {
    // Alone, so that no other root turns the driver's all-null shortcut off: NULL OR TRUE is
    // 1 and NULL AND FALSE is 0, and a kernel that took the shortcut would answer null.
    def v(c: Cond): CondValue = new CondValue(c, LaneType.INT)
    for (root <- Seq[VarkaVectorIR](v(new Or(b0, b1)), v(new And(b0, b1)),
        new IntArith(IntOp.ADD, Overflow.WRAP, v(new Or(b0, b1)), v(b1)),
        new BitCount(v(new And(b0, new Not(b1)))))) {
      sweep(Seq(root), 2, s"${VarkaVectorIR.canonical(root)} alone")
    }
  }

  test("a condition as a value on the long lane") {
    val Seq(l0, l1, l2) = (0 until 3).map(new BoolColumn(_, LaneType.LONG))
    def v(c: Cond): CondValue = new CondValue(c, LaneType.LONG)
    val long = Seq(v(l0), v(new Not(l1)), v(new BoolCompare(CompareOp.GE, l0, l1)),
      v(new BoolNullSafeEq(l0, l1)), v(new CondIf(l0, l1, l2)),
      new IntArith(IntOp.ADD, Overflow.WRAP, v(l0), v(l1)), new BitCount(v(l2)))
    long.foreach(r =>
      assert(VarkaKernelPlan.emissionLane(r, VarkaEmitOptions.DEFAULTS) === LaneType.LONG,
        r.toString))
    sweep(long, 3, "long-lane condition values")
    // An int-lane condition value widened into a kernel driven at the long lane: the int species
    // of the long group's width, and the mask read off a word sized for the long group.
    sweep(Seq(new WidenLane(new CondValue(new Or(b0, new Not(b1)), LaneType.INT)),
      new IntArith(IntOp.ADD, Overflow.WRAP, new WidenLane(new CondValue(b0, LaneType.INT)),
        v(l2))), 3, "a widened int-lane condition value")
  }

  test("a condition that is unknown from valid inputs keeps a condition value's kernel " +
      "masked-only, and one that is not keeps the dense body") {
    def names(r: VarkaVectorIR): Seq[String] = methodNames(emitMulti(Seq(r), 1, 0))
    val unknown = new CondValue(new And(b0, new CondConst(Truth.NULL)), LaneType.INT)
    assert(!names(unknown).exists(_.startsWith("loopDense")), names(unknown).mkString(", "))
    assert(!names(unknown).exists(_.startsWith("epilogueDense")), names(unknown).mkString(", "))
    sweep(Seq(unknown), 1, "unknown from valid inputs")
    val known = new CondValue(b0, LaneType.INT)
    assert(names(known).contains("loopDense0"), names(known).mkString(", "))
    // The constructor holds the value's lane to its condition's, and refuses a narrow lane,
    // which no test lowers yet.
    intercept[IllegalArgumentException](new CondValue(b0, LaneType.LONG))
    intercept[IllegalArgumentException](
      new CondValue(new BoolColumn(0, LaneType.BYTE), LaneType.BYTE))
  }

  // ---------------------------------------------------------------------------------------------
  // A condition as a BOOLEAN output column (VARKA-27)
  // ---------------------------------------------------------------------------------------------

  /**
   * The ways a boolean output's two bitmaps are written (`VARKA-27.md` 2.2), each at every width
   * of the sweep: per lane group or a word at a time, the dense validity filled by the driver or
   * written per group (under either writer), and the driver from its table or unrolled, where
   * the data bitmap's zero is emitted differently.
   */
  private val writers: Seq[(String, VarkaEmitOptions => VarkaEmitOptions)] = Seq(
    "per group" -> (o => o),
    "by word" -> (o => o.toBuilder.validityByWord(true).build()),
    "per group, unrolled driver, no dense fill" ->
      (o => o.toBuilder.driverOutputTable(false).denseValidityOnce(false).build()),
    "by word, unrolled driver" ->
      (o => o.toBuilder.validityByWord(true).driverOutputTable(false).build()),
    // The word writer with the dense validity written per group rather than by the driver.
    "by word, no dense fill" ->
      (o => o.toBuilder.validityByWord(true).denseValidityOnce(false).build()))

  private val writerWidths: Seq[(String, VarkaEmitOptions)] =
    for ((w, base) <- widths; (name, f) <- writers) yield (s"$w, $name", f(base))

  private def out(c: Cond): BoolOutput = new BoolOutput(c)

  /** Every boolean form the row writes: AND, OR, NOT, the comparisons, <=>, IF, coalesce. */
  private def boolForms(x: Cond, y: Cond, z: Cond): Seq[Cond] =
    Seq(x, new Not(x), new And(x, y), new Or(x, new Not(y)), new IsNotNull(x),
      new Not(new IsNotNull(x))) ++
      CompareOp.values().toSeq.map(op => new BoolCompare(op, x, y)) ++
      Seq(new BoolNullSafeEq(x, y), new CondIf(x, y, z), new CondIf(new IsNotNull(x), x, y),
        new CondConst(Truth.TRUE, x.laneType()), new CondConst(Truth.FALSE, x.laneType()))

  /**
   * `CASE WHEN x THEN y END`: a choice whose missing ELSE is a null constant. It is unknown from
   * valid inputs, which makes its whole kernel masked-only, so it is swept alone rather than in
   * a group of [[boolForms]] whose dense body it would take away.
   */
  private def nullElse(x: Cond, y: Cond): Cond =
    new CondIf(x, y, new CondConst(Truth.NULL, x.laneType()))

  test("a boolean output over every leaf state, in both bodies, both writers and every width") {
    for (forms <- boolForms(b0, b1, b2).grouped(5)) {
      sweep(forms.map(out), 3, "boolean outputs", writerWidths)
    }
    sweep(Seq(out(nullElse(b0, b1))), 3, "a boolean output with a null ELSE", writerWidths)
    // A comparison of two values, beside its own condition read as a filter root would be: the
    // value compare's validity word is computed, not a leaf's.
    def v(c: Cond): CondValue = new CondValue(c, LaneType.INT)
    sweep(Seq(out(new Compare(CompareOp.LT, v(b0), v(b1))),
      out(new And(new Compare(CompareOp.EQ, v(b0), v(new Not(b1))), b2))), 3,
      "boolean outputs over value comparisons", writerWidths)
  }

  test("a boolean output on the long lane, in both bodies, both writers and every width") {
    val Seq(l0, l1, l2) = (0 until 3).map(new BoolColumn(_, LaneType.LONG))
    val forms = boolForms(l0, l1, l2) :+ nullElse(l0, l1)
    forms.foreach(c => assert(VarkaKernelPlan.emissionLane(out(c), VarkaEmitOptions.DEFAULTS) ===
      LaneType.LONG, c.toString))
    for (group <- forms.init.grouped(5)) {
      sweep(group.map(out), 3, "long-lane boolean outputs", writerWidths)
    }
    sweep(Seq(out(forms.last)), 3, "a long-lane boolean output with a null ELSE", writerWidths)
  }

  test("a boolean output on the narrow lanes, whose bits are written at the driving lane") {
    for (lane <- Seq(LaneType.BYTE, LaneType.SHORT)) {
      val Seq(y0, y1, y2) = (0 until 3).map(new BoolColumn(_, lane))
      sweep(Seq(out(new And(y0, new Not(y1))), out(new BoolCompare(CompareOp.LT, y0, y1)),
        out(new CondIf(y0, y1, y2))), 3, s"$lane boolean outputs", writerWidths)
    }
  }

  test("a boolean output beside value outputs and a second boolean output in one kernel") {
    def v(c: Cond): CondValue = new CondValue(c, LaneType.INT)
    sweep(Seq(out(new Or(b0, b1)), new IntArith(IntOp.ADD, Overflow.WRAP, v(b0), v(b1)),
      out(new BoolCompare(CompareOp.GE, b1, b2)), new IfElse(b2, v(b0), v(b1))), 3,
      "boolean outputs among values", writerWidths)
  }

  test("a boolean output whose leaf is null on every row is answered, not short-cut to null") {
    // Each alone in its kernel, so that no other root turns the driver's all-null shortcut off:
    // b0 is null throughout; NULL OR TRUE is true, b IS NULL is true on every row, and b <=> b2
    // is true where both are null. A kernel that took the shortcut would answer null.
    val nullOnly = Seq(2, 3)
    for ((root, k, perColumn) <- Seq[(VarkaVectorIR, Int, Seq[Seq[Int]])](
        (out(new Or(b0, b1)), 2, Seq(nullOnly, allStates)),
        (out(new And(b0, b1)), 2, Seq(nullOnly, allStates)),
        (out(new Not(new IsNotNull(b0))), 1, Seq(nullOnly)),
        (out(new BoolNullSafeEq(b0, b1)), 2, Seq(nullOnly, nullOnly)),
        (out(new BoolNullSafeEq(b0, b1)), 2, Seq(nullOnly, allStates)),
        (out(new CondConst(Truth.TRUE)), 1, Seq(nullOnly)));
        (w, options) <- writerWidths) {
      check(Seq(root), k, allStates, tailLengths :+ 77, 0 until 4, options,
        s"${VarkaVectorIR.canonical(root)} over an all-null column at $w", perColumn)
    }
  }

  test("a boolean output that can be unknown from valid inputs keeps its kernel masked-only, " +
      "and is a root or nothing") {
    def names(r: VarkaVectorIR): Seq[String] = methodNames(emitMulti(Seq(r), 1, 0))
    val unknown = out(new Or(b0, new CondConst(Truth.NULL)))
    assert(!names(unknown).exists(_.startsWith("loopDense")), names(unknown).mkString(", "))
    assert(!names(unknown).exists(_.startsWith("epilogueDense")), names(unknown).mkString(", "))
    sweep(Seq(unknown, out(new CondConst(Truth.NULL))), 1, "unknown from valid inputs",
      writerWidths)
    assert(names(out(b0)).contains("loopDense0"), names(out(b0)).mkString(", "))
    // Its canonical form is its own, so it never shares the filter root's class.
    assert(VarkaVectorIR.canonical(out(b0)) === "(bool-out " + VarkaVectorIR.canonical(b0) + ")")
    // Below a root it is refused: it writes the output's two bitmaps itself.
    val below = intercept[IllegalArgumentException](emitMulti(Seq(
      new IntArith(IntOp.ADD, Overflow.WRAP, out(b0), new CondValue(b1, LaneType.INT))), 2, 0))
    assert(below.getMessage.contains("a boolean output below a root"), below.getMessage)
  }

  test("a root writes bitmaps exactly when its output is BOOLEAN, and a projection's is a " +
      "BoolOutput") {
    // Each shape is built directly, as no compiler arm builds it, and each is one the evaluator
    // would serve wrongly with no throw: a value root typed BOOLEAN gets a BitVector and stores
    // a value a row past its bitmap (a TINYINT root's one byte a row matches BOOLEAN's default
    // size, so the width compare alone passes it); a BoolOutput typed as a number writes bitmaps
    // into a value vector; and a filter's Cond root as a projection's boolean output writes only
    // the validity, so every true row reads false.
    def kernel(root: VarkaVectorIR, dataType: DataType): CompiledVarkaProjection =
      new CompiledVarkaProjection(JList.of(root), JList.of(dataType), JList.of(0), JList.of(),
        JList.of(), JList.of(), JList.of(), JList.of())
    def projection(k: CompiledVarkaProjection): PartialVarkaProjection =
      new PartialVarkaProjection(JList.of(new VarkaOutputSpec.FusedOutput(0)), k,
        Collections.emptyMap(), JList.of())
    def refused(what: String, build: => Any, message: String): Unit = {
      val e = intercept[IllegalArgumentException](build)
      assert(e.getMessage.contains(message), s"$what: ${e.getMessage}")
    }
    val byteValue = new ColumnRef(0, LaneType.BYTE)
    refused("a TINYINT value typed BOOLEAN", kernel(byteValue, BooleanType),
      "output 0 is a value but its type is boolean")
    refused("an int value typed BOOLEAN", kernel(new ColumnRef(0), BooleanType),
      "output 0 is a value but its type is boolean")
    refused("a boolean output typed INT", kernel(out(b0), IntegerType),
      "output 0 is a bitmap root, BoolOutput, but its type is int")
    refused("a condition typed TINYINT", kernel(b0, ByteType),
      "output 0 is a bitmap root, BoolColumn, but its type is tinyint")
    refused("a filter's condition as a projection's output", projection(kernel(b0, BooleanType)),
      "a projection's boolean output is a BoolOutput root, not a filter's condition root")
    // Each in a further kernel too, which a projection over several kernels holds in `more`.
    refused("a filter's condition in a further kernel", new PartialVarkaProjection(
      JList.of(new VarkaOutputSpec.FusedOutput(0)), kernel(out(b0), BooleanType),
      Collections.emptyMap(), JList.of(kernel(new Not(b1), BooleanType))),
      "not a filter's condition root")
    // The shapes that are right: a TINYINT value typed TINYINT, a filter's kernel of condition
    // roots typed BOOLEAN, and a boolean output typed BOOLEAN in a projection.
    kernel(byteValue, ByteType)
    kernel(b0, BooleanType)
    projection(kernel(out(b0), BooleanType))
  }
}
