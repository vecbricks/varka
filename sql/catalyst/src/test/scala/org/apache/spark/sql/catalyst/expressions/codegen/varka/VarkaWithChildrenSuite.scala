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
 * its own children gives the node back, for every node type either grammar can draw, and a
 * replacement that does not type is refused by the constructor and not accepted silently.
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
      for (root <- (int.roots.asScala ++ long.roots.asScala ++ mixed.roots.asScala);
          node <- nodes(root)) {
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
    val undrawn = permitted -- seen - "NullSafeEqual"
    assert(undrawn.isEmpty, s"the grammars never drew: ${undrawn.toSeq.sorted.mkString(", ")}")
  }

  test("a null-safe equality is rebuilt over its own children, and refuses a mixed pair") {
    val col = new ColumnRef(0)
    val other = new ColumnRef(1)
    val node = new NullSafeEqual(col, other)
    assert(VarkaVectorIR.withChildren(node, VarkaVectorIR.childrenOf(node): _*) == node)
    assert(VarkaVectorIR.withChildren(node, other, col) == new NullSafeEqual(other, col))
    intercept[Exception](VarkaVectorIR.withChildren(node, col, new ColumnRef(1, LaneType.LONG)))
  }

  test("a replacement is rebuilt in place, the node's other fields kept") {
    val col = new ColumnRef(0)
    val other = new ColumnRef(1)
    val guarded = new GuardedRange(col, -5L, 9L)
    val swapped = VarkaVectorIR.withChildren(guarded, other)
    assert(swapped == new GuardedRange(other, -5L, 9L))
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
