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

import org.apache.spark.sql.catalyst.expressions.{EqualNullSafe, Literal}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrix.PinsDefaults
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaReference.Truth
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.types.IntegerType

/**
 * The null-safe equality node, `a <=> b` (VARKA-336), checked on its own: the node is total, true
 * where both operands are null or both are valid and equal, and it is the one comparison whose
 * answer depends on a null lane. The emitted formula
 * {@code kT = (E & vA & vB) | ~(vA | vB)} is held to the reference evaluator's meaning (a case
 * analysis, not the formula), and the reference to Spark's own {@code EqualNullSafe.eval}, over
 * every pair of validity states and equal and different values, in the dense and the masked body,
 * at several lane counts and at lengths that leave a tail.
 *
 * <p>In its own JVM, as every suite that sweeps {@code lanesOverride}: a second species of one
 * lane type in a shared JVM is what {@code VarkaSpeciesGuard} fails.
 */
class VarkaEmitterNullSafeSuite extends VarkaEmitterTestBase with VarkaOwnJvm {

  private val c0 = new ColumnRef(0)
  private val c1 = new ColumnRef(1)
  private val c2 = new ColumnRef(2)

  /** Lengths that are, and are not, a multiple of any lane count the sweep uses. */
  private val tailLengths = Seq(1, 3, 7, 8, 9, 15, 16, 17, 31, 33, 63, 64, 65, 130, 257)

  /** The widths of the sweep: four and eight lanes, and the default of the host. */
  private val widths: Seq[(String, VarkaEmitOptions)] = Seq(
    "four lanes" -> VarkaMatrix.base.withLanesOverride(4),
    "eight lanes" -> VarkaMatrix.base.withLanesOverride(8),
    "the default width" -> VarkaMatrix.base)

  /**
   * Values that include both extremes, which is what a null lane's stored value is poisoned with:
   * a valid operand equal to the other column's poison is a lane a formula that read the null
   * lane's value would answer true.
   */
  private val extremes = Array(Int.MinValue, Int.MaxValue, 0, 7)

  /** Column 0 cycles the extremes; column 1 equals it on every third row and differs elsewhere. */
  private def pairs(c: Int, i: Int): Int = c match {
    case 0 => extremes(i % 4)
    case 1 => if (i % 3 == 0) extremes(i % 4) else extremes((i + 1) % 4)
    case _ => extremes((i + 2) % 4)
  }

  test("the reference evaluator answers a null-safe equality as Spark does, for every " +
      "validity pair and for equal and different values") {
    val node = new NullSafeEqual(c0, c1)
    for (aNull <- Seq(false, true); bNull <- Seq(false, true); a <- Seq(3, 4); b <- Seq(3, 4)) {
      val spark = EqualNullSafe(
        Literal(if (aNull) null else a, IntegerType),
        Literal(if (bNull) null else b, IntegerType)).eval(null).asInstanceOf[Boolean]
      val got = VarkaReference.cond(node, Array(a, b), Array(aNull, bNull), Array.empty[Int])
      assert(got === (if (spark) Truth.TRUE else Truth.FALSE),
        s"null=($aNull,$bNull) values=($a,$b)")
    }
  }

  test("a null-safe equality over two columns, in every validity combination, as a filter " +
      "root, under NOT and as the condition of a choice, in both bodies at every width") {
    val roots: Seq[(String, Seq[VarkaVectorIR])] = Seq(
      "filter root" -> Seq(new NullSafeEqual(c0, c1)),
      "under NOT" -> Seq(new Not(new NullSafeEqual(c0, c1))),
      "under AND with a comparison" -> Seq(
        new And(new NullSafeEqual(c0, c1), new Compare(CompareOp.LE, c0, c1))),
      "under OR with a comparison" -> Seq(
        new Or(new NullSafeEqual(c0, c1), new Compare(CompareOp.LT, c0, c1))),
      "choosing between the columns" -> Seq(new IfElse(new NullSafeEqual(c0, c1), c0, c1)),
      "against a literal" -> Seq(new NullSafeEqual(c0, new LiteralSlot(0))))
    for ((width, options) <- widths; (name, shape) <- roots) {
      // combos(2) is every pair of the four null patterns: both valid, either null, both null.
      checkMatrix(shape, 2, Array(7), tailLengths, combos(2), data = pairs,
        ctx = s"$name at $width", options = options)
      checkMatrix(shape, 2, Array(7), Seq(17, 65), combos(2), data = pairs, forceMasked = true,
        ctx = s"$name, masked, at $width", options = options)
    }
  }

  test("a null-safe equality over computed operands, one of which nulls a valid input") {
    // The case a composition from IsNotNull could not serve: a computed operand's validity is
    // read after its value walk. The checked add makes a null of two valid inputs where it
    // overflows (the extremes), so the node meets a null that no input column holds.
    val sum = new IntArith(IntOp.ADD, Overflow.NULL, c0, c1)
    val shifted = new IntArith(IntOp.ADD, Overflow.WRAP, c1, new LiteralSlot(0))
    val shapes: Seq[(String, VarkaVectorIR, Int)] = Seq(
      ("a checked sum against a column", new NullSafeEqual(sum, c2), 3),
      ("a column against a sum with a literal", new NullSafeEqual(c0, shifted), 2),
      ("two computed operands", new NullSafeEqual(sum, shifted), 2))
    for ((width, options) <- widths; (name, root, inputs) <- shapes) {
      checkMatrix(Seq(root), inputs, Array(1), Seq(1, 17, 64, 65, 130), combos(inputs),
        data = pairs, ctx = s"$name at $width", options = options)
    }
  }

  test("a null-safe equality over two bigint columns, in both bodies at every long width") {
    val l0 = new ColumnRef(0, LaneType.LONG)
    val l1 = new ColumnRef(1, LaneType.LONG)
    val roots = Seq[VarkaVectorIR](new NullSafeEqual(l0, l1), new Not(new NullSafeEqual(l0, l1)))
    def longs(c: Int, i: Int): Long = pairs(c, i).toLong * (if (i % 5 == 0) 3L else 1L)
    for (lanes <- Seq(2, 4, 8); root <- roots) {
      checkLongMatrix(Seq(root), 2, Array.empty[Long], tailLengths, combos(2), longs,
        ctx = s"bigint ${root.getClass.getSimpleName} at $lanes lanes", lanes = lanes)
    }
  }

  test("a null-safe equality over an int and a bigint column compares them widened") {
    val widened = new NullSafeEqual(new WidenLane(c0), new WidenLane(c1))
    assert(widened.laneType() === LaneType.LONG)
    val rejected = intercept[org.apache.spark.SparkIllegalArgumentException](
      new NullSafeEqual(c0, new WidenLane(c1)))
    assert(rejected.getMessage.contains("a null-safe equality mixes lanes"), rejected.getMessage)
  }

  test("the dense body of a null-safe equality is a comparison's, and the masked body adds " +
      "scalar word operations only, no Vector API call", PinsDefaults) {
    // Written from the plan's prediction: `a <=> b` is `a = b` in a dense body (no lane is null),
    // and its validity formula adds no vector or mask call to the masked loop.
    def counts(root: VarkaVectorIR, method: String): (Int, Int, Int) = {
      val bytes = emitMulti(Seq(root), 2, 0)._2
      def calls(owner: String) = VarkaEmitterTestSupport.invocationCount(
        bytes, method, s"jdk.incubator.vector.$owner")
      (calls("IntVector"), calls("VectorMask"), VarkaEmitterTestSupport.codeSize(bytes, method))
    }
    val eq = new Compare(CompareOp.EQ, c0, c1)
    val nullSafe = new NullSafeEqual(c0, c1)
    assert(counts(nullSafe, "loopDense0") === counts(eq, "loopDense0"))
    val (eqLane, eqMask, eqSize) = counts(eq, "loopMasked0")
    val (nsLane, nsMask, nsSize) = counts(nullSafe, "loopMasked0")
    assert(nsLane === eqLane && nsMask === eqMask, "no Vector API call beyond the comparison's")
    assert(nsSize > eqSize, "the validity formula is scalar code in the masked loop")
  }

  test("a selection never sets a bit past the row count, with the last lanes null in both " +
      "operands, where ~(vA | vB) is all ones") {
    val shapes: Seq[(String, VarkaVectorIR)] = Seq(
      "filter root" -> new NullSafeEqual(c0, c1),
      "under NOT" -> new Not(new NullSafeEqual(c0, c1)))
    for ((width, options) <- widths; (name, root) <- shapes; length <- tailLengths) {
      val (className, bytes) = emitMulti(Seq(root), 2, 0, options)
      // The last three rows are null in both columns; the rest alternate. The check also reads the
      // selection's last byte (VarkaKernelChecks): a bit set past the row count fails it, at both
      // validity fills.
      val nullTail: Int => Boolean = i => i >= length - 3
      val batch = VarkaKernelCheck.Batch(length, Seq(nullTail, nullTail),
        Array.tabulate(2, length)((c, i) => pairs(c, i)), forceMasked = false)
      VarkaKernelCheck.runAndCompare(s"$name, $length rows at $width", className, bytes,
        Seq(root), 2, Array.empty[Int], batch)
    }
  }
}
