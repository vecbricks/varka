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

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaIrGrammar._
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * `VarkaVectorIR.withChildren` is the inverse of `childrenOf` (VARKA-277): rebuilding a node over
 * its own children gives the node back, for every node type any grammar can draw - the narrow
 * conversion draw included, whose target-carrying conversions a one-argument rebuild would
 * silently change (VARKA-329), and the nulling draw, whose guards a three-argument rebuild would
 * make failing ones (VARKA-481) - and a replacement that does not type is refused by the
 * constructor and not accepted silently.
 */
class VarkaWithChildrenSuite extends SparkFunSuite {

  private def nodes(root: VarkaVectorIR): Seq[VarkaVectorIR] =
    root +: VarkaVectorIR.childrenOf(root).toSeq.flatMap(nodes)

  test("rebuilding a node over its own children gives the node, for every drawn node type") {
    val seen = scala.collection.mutable.Set.empty[String]
    for (k <- 0 until 4000) {
      val int = drawShape(shapeRandom(FUZZ_SEED, k))
      val long = drawLongShape(shapeRandom(LONG_FUZZ_SEED, k))
      val mixed = drawMixedShape(shapeRandom(MIXED_FUZZ_SEED, k))
      val narrow = drawNarrowShape(shapeRandom(NARROW_FUZZ_SEED, k))
      val nulling = drawNullingShape(shapeRandom(NULLING_FUZZ_SEED, k))
      val nullingLong = drawNullingLongShape(shapeRandom(NULLING_FUZZ_SEED, k))
      val drawn = int.roots.asScala ++ long.roots.asScala ++ mixed.roots.asScala ++
        narrow.roots.asScala ++ nulling.roots.asScala ++ nullingLong.roots.asScala
      for (root <- drawn; node <- nodes(root)) {
        val rebuilt = VarkaVectorIR.withChildren(node, VarkaVectorIR.childrenOf(node): _*)
        assert(rebuilt == node, s"${VarkaVectorIR.canonical(node)} rebuilt as " +
          VarkaVectorIR.canonical(rebuilt))
        seen += node.getClass.getSimpleName
      }
    }
    val permitted = classOf[VarkaVectorIR].getPermittedSubclasses.toSeq.flatMap { c =>
      Option(c.getPermittedSubclasses).map(_.toSeq).getOrElse(Seq(c))
    }.filter(_.isRecord).map(_.getSimpleName).toSet
    // The null-safe equality is in no grammar yet (VARKA-336 6): it is rebuilt directly below.
    // The boolean-column nodes (VARKA-331), the condition as a value (VARKA-460) and the
    // boolean output (VARKA-27) are rebuilt directly below.
    // The decimal column (VARKA-347) is in no grammar yet either, and on purpose: a draw of it in
    // a committed sequence would move every shape after it and the corpora the cost model is
    // fitted on, so its sequence comes on a seed of its own (`VARKA-347.md` 3 item 5) and its
    // fold into the corpora with row 352's refit. It is rebuilt directly below.
    val undrawn = permitted -- seen - "NullSafeEqual" - "BoolColumn" - "CondConst" -
      "BoolCompare" - "CondIf" - "BoolNullSafeEq" - "CondValue" - "BoolOutput" - "DecimalColumn"
    assert(undrawn.isEmpty, s"the grammars never drew: ${undrawn.toSeq.sorted.mkString(", ")}")
  }

  test("a conversion keeps its target when rebuilt, and its rendering names it") {
    val col = new ColumnRef(0)
    val longCol = new ColumnRef(1, LaneType.LONG)
    val byte = new NarrowLane(col, LaneType.BYTE)
    val short = new NarrowLane(col, LaneType.SHORT)
    val nodes = Seq[VarkaVectorIR](byte, short, new WidenLane(byte, LaneType.INT),
      new NarrowLane(longCol, LaneType.INT), new NarrowLane(longCol, LaneType.BYTE))
    for (node <- nodes) {
      val rebuilt = VarkaVectorIR.withChildren(node, VarkaVectorIR.childrenOf(node): _*)
      assert(rebuilt == node, s"${VarkaVectorIR.canonical(node)} rebuilt as " +
        VarkaVectorIR.canonical(rebuilt))
    }
    // Two targets over one child are two conversions, and their renderings say so: the shape
    // hash, the class name and the executions identity all read the rendering (VARKA-329).
    assert(VarkaVectorIR.canonical(new WidenLane(byte, LaneType.INT)) !=
      VarkaVectorIR.canonical(new WidenLane(byte, LaneType.LONG)))
    assert(VarkaVectorIR.canonical(new NarrowLane(longCol, LaneType.INT)) !=
      VarkaVectorIR.canonical(new NarrowLane(longCol, LaneType.BYTE)))
  }

  test("a null-safe equality is rebuilt over its own children, and refuses a mixed pair") {
    val col = new ColumnRef(0)
    val other = new ColumnRef(1)
    val node = new NullSafeEqual(col, other)
    assert(VarkaVectorIR.withChildren(node, VarkaVectorIR.childrenOf(node): _*) == node)
    assert(VarkaVectorIR.withChildren(node, other, col) == new NullSafeEqual(other, col))
    intercept[Exception](VarkaVectorIR.withChildren(node, col, new ColumnRef(1, LaneType.LONG)))
  }

  test("the boolean-column nodes are rebuilt over their own children, and refuse a mixed pair") {
    val a = new BoolColumn(0)
    val b = new BoolColumn(1)
    val c = new BoolColumn(2)
    val nodes = Seq[VarkaVectorIR](a, new CondConst(Truth.NULL),
      new BoolCompare(CompareOp.LE, a, b), new CondIf(a, b, c), new BoolNullSafeEq(a, b),
      new CondValue(new CondIf(a, b, c), LaneType.INT), new BoolOutput(new CondIf(a, b, c)))
    for (node <- nodes) {
      assert(VarkaVectorIR.withChildren(node, VarkaVectorIR.childrenOf(node): _*) == node)
    }
    assert(VarkaVectorIR.withChildren(nodes(2), b, a) == new BoolCompare(CompareOp.LE, b, a))
    intercept[Exception](VarkaVectorIR.withChildren(nodes(4), a, new BoolColumn(1, LaneType.LONG)))
  }

  test("a decimal column, a leaf, is rebuilt as itself, and its parent over it") {
    val dec = new DecimalColumn(0, LaneType.LONG, 9)
    assert(VarkaVectorIR.withChildren(dec) == dec)
    intercept[Exception](VarkaVectorIR.withChildren(dec, new ColumnRef(0, LaneType.LONG)))
    val sum = new IntArith(IntOp.ADD, Overflow.WRAP, dec, new DecimalColumn(1, LaneType.LONG, 18))
    assert(VarkaVectorIR.withChildren(sum, VarkaVectorIR.childrenOf(sum): _*) == sum)
  }

  test("a replacement is rebuilt in place, the node's other fields kept") {
    val col = new ColumnRef(0)
    val other = new ColumnRef(1)
    val guarded = new GuardedRange(col, -5L, 9L)
    val swapped = VarkaVectorIR.withChildren(guarded, other)
    assert(swapped == new GuardedRange(other, -5L, 9L))
    // A nulling guard stays one: a rebuild through the three-argument form would make it fail.
    val nulling = new GuardedRange(col, -5L, 9L, Overflow.NULL)
    assert(VarkaVectorIR.withChildren(nulling, other) ==
      new GuardedRange(other, -5L, 9L, Overflow.NULL))
    // And the two render apart, so they never share a kernel, a failing guard's rendering
    // carrying no mode.
    assert(VarkaVectorIR.canonical(guarded) === "(guardedRange col:0 -5 9)")
    assert(VarkaVectorIR.canonical(nulling) === "(guardedRange:NULL col:0 -5 9)")
    // The reproduction's parser reads each rendering back as the node it came from.
    for (node <- Seq(guarded, nulling)) {
      assert(VarkaCanonicalIr.parse(VarkaVectorIR.canonical(node)) == node)
    }
    val compare = new Compare(CompareOp.LT, col, other)
    assert(VarkaVectorIR.withChildren(compare, other, col) ==
      new Compare(CompareOp.LT, other, col))
  }

  test("a replacement that does not type, or does not fit the node, is refused") {
    val int = new ColumnRef(0)
    val long = new ColumnRef(1, LaneType.LONG)
    intercept[IllegalArgumentException](VarkaVectorIR.withChildren(new Year(int), long))
    intercept[IllegalArgumentException](
      VarkaVectorIR.withChildren(new And(new IsNotNull(int), new IsNotNull(int)), int, int))
    intercept[IllegalArgumentException](VarkaVectorIR.withChildren(new Year(int)))
    intercept[IllegalArgumentException](VarkaVectorIR.withChildren(int, int))
    intercept[IllegalArgumentException](
      VarkaVectorIR.withChildren(new Compare(CompareOp.LT, int, int), int, long))
  }
}
