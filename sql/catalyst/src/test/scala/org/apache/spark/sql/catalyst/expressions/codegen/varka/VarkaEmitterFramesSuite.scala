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

  private val laneOwners =
    Seq("IntVector", "LongVector", "DoubleVector", "Vector").map("jdk.incubator.vector." + _)
  private val maskOwner = "jdk.incubator.vector.VectorMask"

  /** The lane arithmetic of one method: its invocations on the Vector API's value types. */
  private def valueOps(bytes: Array[Byte], method: String): Int =
    laneOwners.map(VarkaEmitterTestSupport.invocationCount(bytes, method, _)).sum

  private def maskOps(bytes: Array[Byte], method: String): Int =
    VarkaEmitterTestSupport.invocationCount(bytes, method, maskOwner)

  private def bodies(bytes: Array[Byte]): Seq[String] =
    VarkaEmitterTestSupport.methodNames(bytes).asScala.toSeq
      .filter(m => m.startsWith("loop") || m.startsWith("epilogue"))

  private def severalGroups(bytes: Array[Byte]): Boolean =
    bodies(bytes).exists(m => m == "loopDense1" || m == "loopMasked1")

  /** Both forms of one shape, emitted under one class name so the bytes are comparable. */
  private def bothForms(name: String, roots: Seq[VarkaVectorIR], numInputs: Int,
      numLiterals: Int, options: VarkaEmitOptions): (Array[Byte], Array[Byte]) = {
    def emit(o: VarkaEmitOptions): Array[Byte] =
      VarkaLoopEmitter.emit(name, roots.asJava, numInputs, numLiterals, null, null, o)
    (emit(options.withGroupLocalSlots(false)), emit(options.withGroupLocalSlots(true)))
  }

  /**
   * What the switch keeps for a several-group kernel: every method's lane arithmetic, node for
   * node, and no method larger. One thing it may drop, found by this suite's second test on the
   * shared grammar: with the frames planned over the kernel, a body allocated the guard
   * accumulator whenever any output of the kernel guarded, and a body that guards nothing
   * still initialised it and tested it on exit - two mask operations that could never fire.
   * Planned over the group, such a body has no accumulator, so its mask operations may fall by
   * exactly those two.
   */
  private def sameOperations(kernelWide: Array[Byte], groupLocal: Array[Byte],
      where: String): Unit = {
    assert(VarkaEmitterTestSupport.methodNames(groupLocal).asScala.toSet ===
      VarkaEmitterTestSupport.methodNames(kernelWide).asScala.toSet, where)
    bodies(kernelWide).foreach { m =>
      assert(valueOps(groupLocal, m) === valueOps(kernelWide, m), s"$where: $m")
      val dropped = maskOps(kernelWide, m) - maskOps(groupLocal, m)
      assert(dropped == 0 || dropped == 2, s"$where: $m dropped $dropped mask operations")
      assert(VarkaEmitterTestSupport.codeSize(groupLocal, m) <=
        VarkaEmitterTestSupport.codeSize(kernelWide, m), s"$where: $m")
    }
  }

  test("a group's method frame holds its group's slots and not the kernel's (task 191)") {
    // The benchmark's four-hundred-output shape, the byte budget out of reach as the benchmark
    // sets it: with the frames planned over the kernel a loop or epilogue method carries ten
    // thousand locals (PLAN_TASK_191.md 2.3), with them planned over the group a few hundred,
    // while the drivers - the kernel's by construction - keep theirs. The test that catches a
    // planner walking the kernel again. The operations are the same, node for node, and no
    // method grew: locals past 255 lost their wide forms.
    val n = 400
    val (kernelWide, groupLocal) = bothForms("org.apache.spark.sql.varka.execution.VarkaFrames",
      wide(n), 1, n, VarkaEmitOptions.DEFAULTS.withMethodByteBudget(1 << 20))
    val methods = bodies(kernelWide)
    assert(methods.size > 100, methods.size)
    val widestBefore = methods.map(VarkaEmitterTestSupport.maxLocals(kernelWide, _)).max
    val widestAfter = methods.map(VarkaEmitterTestSupport.maxLocals(groupLocal, _)).max
    assert(widestBefore > 5000, s"kernel-wide frames: $widestBefore locals at the widest")
    assert(widestAfter < 400, s"group-local frames: $widestAfter locals at the widest")
    Seq("runDense", "runMasked").foreach { driver =>
      assert(VarkaEmitterTestSupport.maxLocals(groupLocal, driver) ===
        VarkaEmitterTestSupport.maxLocals(kernelWide, driver), driver)
    }
    sameOperations(kernelWide, groupLocal, "400 outputs")
  }

  test("a one-group kernel emits the same bytes either way, and a several-group one the same " +
      "operations (task 191)") {
    // The first three hundred shapes of the shared fuzz grammar at its seed, under the default
    // budget - one to three roots, so both kinds of kernel occur - and the benchmark's shape at
    // 25 and 100 outputs. A one-group kernel's body set is the whole kernel, so its slots are
    // numbered as before and its bytes are identical; a several-group one renumbers and keeps
    // every lane operation, less the dead accumulator `sameOperations` describes. A shape the
    // budget declines declines both ways.
    var oneGroup = 0
    var several = 0
    def check(where: String, roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int)
        : Unit = {
      val name =
        s"org.apache.spark.sql.varka.execution.VarkaFramesShape${classCounter.addAndGet(1)}"
      val forms =
        try {
          Some(bothForms(name, roots, numInputs, numLiterals, VarkaEmitOptions.DEFAULTS))
        } catch {
          case d: VarkaEmitDeclined =>
            intercept[VarkaEmitDeclined] {
              VarkaLoopEmitter.emit(name, roots.asJava, numInputs, numLiterals, null, null,
                VarkaEmitOptions.DEFAULTS.withGroupLocalSlots(true))
            }
            None
        }
      forms.foreach { case (kernelWide, groupLocal) =>
        if (severalGroups(kernelWide)) {
          several += 1
          sameOperations(kernelWide, groupLocal, where)
        } else {
          oneGroup += 1
          assert(java.util.Arrays.equals(kernelWide, groupLocal), s"$where: the bytes moved")
        }
      }
    }
    (0 until 300).foreach { k =>
      val drawn = drawShape(shapeRandom(fuzzSeed, k))
      check(s"fuzz shape $k", drawn.roots, drawn.numInputs, drawn.numLiterals)
    }
    Seq(25, 100).foreach(n => check(s"$n outputs", wide(n), 1, n))
    assert(oneGroup > 100 && several > 5, s"$oneGroup one-group and $several several-group shapes")
  }
}
