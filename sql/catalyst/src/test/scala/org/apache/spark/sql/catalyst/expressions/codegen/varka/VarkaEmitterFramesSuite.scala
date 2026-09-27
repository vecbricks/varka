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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaIrGrammar.{drawShape,
  fuzzSeed, shapeRandom}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The frames a group's methods get under `groupLocalSlots` (task 191): the group's own nodes'
 * slots and no others, so a method's `max_locals` is the group's size rather than the kernel's;
 * and what the switch may and may not change - a one-group kernel's bytes not at all, a
 * several-group kernel's operations not at all.
 */
class VarkaEmitterFramesSuite extends VarkaEmitterTestBase {

  private val col = new ColumnRef(0)

  /** `VarkaEmissionBenchmark`'s wide shape: four-op outputs, the k-th over literal k. */
  private def wide(n: Int): Seq[VarkaVectorIR] = (0 until n).map { k =>
    new Greatest(new Greatest(new AddMonths(col, new LiteralSlot(k)),
      new AddDays(col, new LiteralSlot(k))), new LastDay(col))
  }

  private val valueOwners =
    Seq("IntVector", "LongVector", "DoubleVector", "Vector").map("jdk.incubator.vector." + _)
  private val maskOwner = "jdk.incubator.vector.VectorMask"

  /**
   * One emitted class, parsed once: per method with code, its code length, its frame, its lane
   * arithmetic - invocations on the Vector API's value types - and its mask operations. A
   * four-hundred-output class is nearly two megabytes, so the suite reads it once rather than
   * once per question per method.
   */
  private case class Method(codeLength: Int, maxLocals: Int, valueOps: Int, maskOps: Int)

  private def profile(bytes: Array[Byte]): Map[String, Method] =
    VarkaEmitterTestSupport.methodProfile(bytes, (valueOwners :+ maskOwner).asJava).asScala
      .map { case (name, row) =>
        name -> Method(row(0), row(1), row.slice(2, 2 + valueOwners.size).sum,
          row(2 + valueOwners.size))
      }.toMap

  private def bodies(methods: Map[String, Method]): Seq[String] =
    methods.keys.toSeq.filter(m => m.startsWith("loop") || m.startsWith("epilogue")).sorted

  private def severalGroups(methods: Map[String, Method]): Boolean =
    methods.contains("loopDense1") || methods.contains("loopMasked1")

  private def emit(name: String, roots: Seq[VarkaVectorIR], numInputs: Int,
      numLiterals: Int, options: VarkaEmitOptions): Either[VarkaEmitDeclined, Array[Byte]] =
    try {
      Right(VarkaLoopEmitter.emit(name, roots.asJava, numInputs, numLiterals, null, null,
        options))
    } catch {
      case d: VarkaEmitDeclined => Left(d)
    }

  /**
   * What the switch keeps for a several-group kernel grouped the same way by both forms: every
   * method's lane arithmetic, node for node, and no method larger. One thing it may drop, found
   * by this suite's second test on the shared grammar: with the frames planned over the kernel,
   * a body allocated the guard accumulator whenever any output of the kernel guarded, and a
   * body that guards nothing still initialised it and tested it on exit - two mask operations
   * that could never fire. Planned over the group, such a body has no accumulator, so its mask
   * operations may fall by exactly those two.
   */
  private def sameOperations(kernelWide: Map[String, Method], groupLocal: Map[String, Method],
      where: String): Unit = {
    bodies(kernelWide).foreach { m =>
      val (before, after) = (kernelWide(m), groupLocal(m))
      assert(after.valueOps === before.valueOps, s"$where: $m")
      val dropped = before.maskOps - after.maskOps
      assert(dropped == 0 || dropped == 2, s"$where: $m dropped $dropped mask operations")
      assert(after.codeLength <= before.codeLength, s"$where: $m")
    }
  }

  test("a group's method frame holds its group's slots and not the kernel's (task 191)") {
    // The benchmark's four-hundred-output shape, the byte budget out of reach as the benchmark
    // sets it: with the frames planned over the kernel a loop or epilogue method carries ten
    // thousand locals (PLAN_TASK_191.md 2.3), with them planned over the group a few hundred,
    // while the drivers - the kernel's by construction - keep theirs. The test that catches a
    // planner walking the kernel again. The lane arithmetic is the same, node for node, and no
    // method grew: locals past 255 lost their wide forms.
    val n = 400
    val options = VarkaEmitOptions.DEFAULTS.withMethodByteBudget(1 << 20)
    val name = "org.apache.spark.sql.varka.execution.VarkaFrames"
    val kernelWide = profile(emit(name, wide(n), 1, n, options.withGroupLocalSlots(false))
      .fold(d => fail(d.getMessage), identity))
    val groupLocal = profile(emit(name, wide(n), 1, n, options.withGroupLocalSlots(true))
      .fold(d => fail(d.getMessage), identity))
    assert(groupLocal.keySet === kernelWide.keySet)
    val methods = bodies(kernelWide)
    assert(methods.size > 100, methods.size)
    val widestBefore = methods.map(kernelWide(_).maxLocals).max
    val widestAfter = methods.map(groupLocal(_).maxLocals).max
    assert(widestBefore > 5000, s"kernel-wide frames: $widestBefore locals at the widest")
    assert(widestAfter < 400, s"group-local frames: $widestAfter locals at the widest")
    Seq("runDense", "runMasked").foreach { driver =>
      assert(groupLocal(driver).maxLocals === kernelWide(driver).maxLocals, driver)
    }
    sameOperations(kernelWide, groupLocal, "400 outputs")
  }

  test("a one-group kernel emits the same bytes either way, and a several-group one the same " +
      "operations (task 191)") {
    // The first three hundred shapes of the shared fuzz grammar at its seed, under the default
    // budget - one to three roots, so both kinds of kernel occur - and the benchmark's shape at
    // 25 and 100 outputs. A one-group kernel's body set is the whole kernel, so its slots are
    // numbered as before and its bytes are identical. A several-group one renumbers and keeps
    // every lane operation, less the dead accumulator `sameOperations` describes - where both
    // forms group it the same way: grouping is decided by measuring bytes, and group-local
    // methods are smaller, so near the budget the two forms may split differently
    // (PLAN_TASK_191.md 6.1, prediction 5), and then only building is compared. A shape one
    // form declines, the other declines too, or the group-local form is the one that builds.
    var oneGroup = 0
    var several = 0
    var regrouped = 0
    def check(where: String, roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int)
        : Unit = {
      val name =
        s"org.apache.spark.sql.varka.execution.VarkaFramesShape${classCounter.addAndGet(1)}"
      val options = VarkaEmitOptions.DEFAULTS
      (emit(name, roots, numInputs, numLiterals, options.withGroupLocalSlots(false)),
        emit(name, roots, numInputs, numLiterals, options.withGroupLocalSlots(true))) match {
        case (Right(kernelWideBytes), Right(groupLocalBytes)) =>
          val (kernelWide, groupLocal) = (profile(kernelWideBytes), profile(groupLocalBytes))
          if (!severalGroups(kernelWide) && !severalGroups(groupLocal)) {
            oneGroup += 1
            assert(java.util.Arrays.equals(kernelWideBytes, groupLocalBytes),
              s"$where: the bytes moved")
          } else if (kernelWide.keySet == groupLocal.keySet) {
            several += 1
            sameOperations(kernelWide, groupLocal, where)
          } else {
            regrouped += 1
          }
        case (Left(_), Left(_)) =>
        case (Left(_), Right(_)) =>
          // Smaller methods can fit a budget the kernel-wide form missed; never the reverse.
          regrouped += 1
        case (Right(_), Left(d)) =>
          fail(s"$where: group-local frames declined a shape kernel-wide frames build: " +
            d.getMessage)
      }
    }
    (0 until 300).foreach { k =>
      val drawn = drawShape(shapeRandom(fuzzSeed, k))
      check(s"fuzz shape $k", drawn.roots, drawn.numInputs, drawn.numLiterals)
    }
    Seq(25, 100).foreach(n => check(s"$n outputs", wide(n), 1, n))
    assert(oneGroup > 100 && several > 5,
      s"$oneGroup one-group, $several several-group and $regrouped regrouped shapes")
  }
}
