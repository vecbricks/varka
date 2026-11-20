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

import jdk.incubator.vector.{IntVector, LongVector}

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.{ColumnRef, LiteralSlot}

/**
 * [[VarkaSpeciesGuard]] sees a second species in a kernel's bytes, and only there (VARKA-246).
 * It never defines a class: it reads the bytes an emission produced, so the shared JVM stays as
 * it was.
 */
class VarkaSpeciesGuardSuite extends VarkaEmitterTestBase {

  private val preferredIntLanes = IntVector.SPECIES_PREFERRED.length()
  private val preferredLongLanes = LongVector.SPECIES_PREFERRED.length()

  /** An int lane count that is not the JVM's preferred one, whatever width this JVM runs at. */
  private val otherIntLanes = if (preferredIntLanes == 4) 8 else 4

  private def bytesAt(options: VarkaEmitOptions): (String, Array[Byte]) =
    emit(new VarkaVectorIR.AddDays(new ColumnRef(0), new LiteralSlot(0)), 1, options)

  test("a kernel emitted for the JVM's own width names no second species",
      VarkaMatrix.PinsDefaults) {
    val (_, bytes) = bytesAt(VarkaMatrix.base)
    assert(VarkaSpeciesGuard.secondSpecies(bytes).isEmpty)
    // The preferred width named explicitly is the same species object as SPECIES_PREFERRED.
    val (_, same) = bytesAt(VarkaMatrix.base.withLanesOverride(preferredIntLanes))
    assert(VarkaSpeciesGuard.secondSpecies(same).isEmpty)
  }

  test("a kernel emitted at another lane count names a second species",
      VarkaMatrix.PinsDefaults) {
    val named = bytesAt(VarkaMatrix.base.withLanesOverride(otherIntLanes))
    val second = VarkaSpeciesGuard.secondSpecies(named._2).asScala.toSeq
    assert(second.exists(_.startsWith("IntVector.SPECIES_")), second)
  }

  test("the registry refuses a second width and keeps passing the established one",
      VarkaMatrix.PinsDefaults) {
    val registry = new VarkaSpeciesGuard.Registry
    val own = bytesAt(VarkaMatrix.base)._2
    val other = bytesAt(VarkaMatrix.base.withLanesOverride(otherIntLanes))._2
    assert(registry.admit(own).isEmpty)
    val conflicts = registry.admit(other).asScala.toSeq
    assert(conflicts.exists(_.startsWith("IntVector.SPECIES_")), conflicts)
    // A refusal registers nothing, so a kernel of the established width still passes.
    assert(registry.admit(bytesAt(VarkaMatrix.base)._2).isEmpty)
  }

  test("a JVM that runs every kernel at one other width is consistent, not a violation",
      VarkaMatrix.PinsDefaults) {
    // The option matrix's `lanesOverride=4` configuration pins the lane count for every suite: a
    // species that is not the preferred one, used by every kernel, is still one species.
    val registry = new VarkaSpeciesGuard.Registry
    val other = VarkaMatrix.base.withLanesOverride(otherIntLanes)
    assert(registry.admit(bytesAt(other)._2).isEmpty)
    assert(registry.admit(bytesAt(other)._2).isEmpty)
    assert(registry.admit(bytesAt(VarkaMatrix.base)._2).asScala.nonEmpty)
  }

  test("the long lane's second species is seen as well", VarkaMatrix.PinsDefaults) {
    val otherLongLanes = if (preferredLongLanes == 2) 4 else 2
    val col = new ColumnRef(0, VarkaVectorIR.LaneType.LONG)
    val lit = new LiteralSlot(0, VarkaVectorIR.LaneType.LONG)
    val root = new VarkaVectorIR.IntArith(
      VarkaVectorIR.IntOp.ADD, VarkaVectorIR.Overflow.WRAP, col, lit)
    val (_, bytes) = emit(root, 1, VarkaMatrix.base.withLanesOverride(otherLongLanes))
    assert(VarkaSpeciesGuard.secondSpecies(bytes).asScala
      .exists(_.startsWith("LongVector.SPECIES_")))
  }

  test("a kernel of two lane widths fails at the defaults naming the annotation, and passes in "
      + "its own JVM", VarkaMatrix.PinsDefaults) {
    // A long kernel holding an int value: at the defaults both species are the preferred width,
    // so the registry alone admits it, and only a lane configuration would refuse it.
    val sum = new VarkaVectorIR.IntArith(
      VarkaVectorIR.IntOp.ADD, VarkaVectorIR.Overflow.WRAP, new ColumnRef(0), new LiteralSlot(0))
    val (mixedName, mixed) = emit(new VarkaVectorIR.WidenLane(sum), 1)
    assert(new VarkaSpeciesGuard.Registry().admit(mixed).isEmpty)
    val refused = intercept[IllegalStateException] {
      VarkaSpeciesGuard.check(mixedName, mixed, "fail", new VarkaSpeciesGuard.Registry)
    }
    assert(refused.getMessage.contains("@VarkaSuite(ownJvm = true)") &&
      refused.getMessage.contains("VarkaOwnJvm"), refused.getMessage)
    // In a suite's own JVM the guard is off, and a one-lane kernel passes in either.
    VarkaSpeciesGuard.check(mixedName, mixed, "off", new VarkaSpeciesGuard.Registry)
    val (oneName, one) = bytesAt(VarkaMatrix.base)
    assert(VarkaSpeciesGuard.mixedLaneCounts(one).isEmpty)
    VarkaSpeciesGuard.check(oneName, one, "fail", new VarkaSpeciesGuard.Registry)
    VarkaSpeciesGuard.check(oneName, one, "off", new VarkaSpeciesGuard.Registry)
  }

  test("the lane-count rule at the defaults selects the kernels a lane configuration refuses",
      VarkaMatrix.PinsDefaults) {
    // Bytes only: each kernel is emitted under each lane configuration and admitted to a registry
    // seeded with that configuration's one-lane int and long kernels, as the shared JVM would be;
    // nothing is defined. A kernel the rule selects at the defaults must conflict under some
    // configuration, and one it passes under none.
    val int = new ColumnRef(0)
    val long = new ColumnRef(0, VarkaVectorIR.LaneType.LONG)
    val intSum = new VarkaVectorIR.IntArith(
      VarkaVectorIR.IntOp.ADD, VarkaVectorIR.Overflow.WRAP, int, new LiteralSlot(0))
    val longSum = new VarkaVectorIR.IntArith(VarkaVectorIR.IntOp.ADD,
      VarkaVectorIR.Overflow.WRAP, long, new LiteralSlot(0, VarkaVectorIR.LaneType.LONG))
    // Each kernel with whether the rule selects it: the two that hold two lane widths do.
    val kernels = Seq(
      ("a widened int sum", new VarkaVectorIR.WidenLane(intSum), true),
      ("an int column divided by 7", new VarkaVectorIR.ConstDivide(int, 7), true),
      ("an int sum", intSum, false),
      ("a long sum", longSum, false))
    val configurations = Seq("lanesOverride=4", "lanesOverride=16", "narrowHalfSpecies=true")
    val disagreements = kernels.flatMap { case (name, root, expected) =>
      val selected = !VarkaSpeciesGuard.mixedLaneCounts(emit(root, 1)._2).isEmpty
      assert(selected === expected, name)
      val refusedUnder = configurations.filter { c =>
        val options = VarkaMatrix.parse(c)
        val registry = new VarkaSpeciesGuard.Registry
        assert(registry.admit(emit(intSum, 1, options)._2).isEmpty, c)
        assert(registry.admit(emit(longSum, 1, options)._2).isEmpty, c)
        !registry.admit(emit(root, 1, options)._2).isEmpty
      }
      if (selected == refusedUnder.nonEmpty) None
      else Some(s"$name: selected $selected, refused under " +
        refusedUnder.mkString("[", ", ", "]"))
    }
    assert(disagreements.isEmpty, disagreements.mkString("; "))
  }
}
