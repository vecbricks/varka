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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The split driver (`VarkaEmitOptions.splitDriver`): past about 180 groups the driver from a
 * table is over the byte budget, and under the option its calls to the groups move into stages it
 * calls in turn. See `VARKA-190.md` 11.
 */
class VarkaEmitterSplitDriverSuite extends VarkaEmitterTestBase {

  // Both forms named: the split driver is the default since `VARKA-190.md` 11.5. The plan
  // (VARKA-236) is off throughout: this suite is the size loop's, which reads the stage size off
  // the built class, and the plan that reads it off a driver built alone is
  // `VarkaKernelPlanSuite`'s.
  private val whole = VarkaEmitOptions.DEFAULTS.withSplitDriver(false).withPlanSize(false)
  private val split = VarkaEmitOptions.DEFAULTS.withSplitDriver(true).withPlanSize(false)

  /** One group per output: a group budget of one closes a group after every `date_add`. */
  private def oneEach(options: VarkaEmitOptions): VarkaEmitOptions =
    options.withGroupBudget(1).withFusedCeiling(1)

  private def dateAdds(n: Int): Seq[VarkaVectorIR] =
    (0 until n).map(k => new AddDays(new ColumnRef(0), new LiteralSlot(k)))

  private def ladderEntry(k: Int): VarkaVectorIR = {
    val col = new ColumnRef(0)
    new Greatest(new Greatest(new AddMonths(col, new LiteralSlot(k)),
      new AddDays(col, new LiteralSlot(k))), new LastDay(col))
  }

  /** The class, how many times it was built, and its measurement. */
  private def emitted(roots: Seq[VarkaVectorIR], lits: Int,
      options: VarkaEmitOptions): (Array[Byte], Int, VarkaEmittedClass) = {
    val builds = new Array[Int](2)
    val bytes = VarkaLoopEmitter.emitCountingBuilds(
      "org.apache.spark.sql.varka.execution.VarkaSplitDriverTest", roots.asJava, 1, lits,
      options, builds)
    (bytes, builds(0), VarkaEmittedClass.measure(bytes))
  }

  private def named(m: VarkaEmittedClass, prefix: String): Int =
    m.codeLength.keySet.asScala.count(_.startsWith(prefix))

  test("three hundred groups decline on the driver, and under the option emit in one rebuild " +
      "with every method under the budget") {
    val roots = dateAdds(300)
    val declined = intercept[VarkaEmitDeclined](emitted(roots, 300, oneEach(whole)))
    assert(declined.getMessage.contains("runDense") && declined.getMessage.contains("runMasked"))
    val (_, builds, m) = emitted(roots, 300, oneEach(split))
    assert(builds === 2, "the stage size is read off the driver, so one rebuild settles it")
    assert(VarkaEmitBudget.overLimits(m).isEmpty, VarkaEmitBudget.overLimits(m))
    assert(named(m, "loopDense") === 300)
    val stages = named(m, "stageDense")
    assert(stages === named(m, "stageMasked") && stages >= 2 && stages <= 3, s"$stages stages")
    for (driver <- Seq("runDense", "runMasked")) {
      assert(m.codeLength.get(driver) < 200, s"$driver is ${m.codeLength.get(driver)} bytes")
    }
  }

  test("a class whose drivers fit is the same class under the option, byte for byte") {
    for ((name, roots, lits) <- Seq(
        ("four hundred greatest entries", (0 until 400).map(ladderEntry), 400),
        ("a hundred and eighty groups", dateAdds(180), 180))) {
      val options = if (lits == 180) oneEach(whole) else whole
      val splitOptions = if (lits == 180) oneEach(split) else split
      val (before, _, _) = emitted(roots, lits, options)
      val (after, builds, m) = emitted(roots, lits, splitOptions)
      assert(named(m, "stage") === 0, name)
      assert(builds === 1, name)
      assert(java.util.Arrays.equals(before, after), name)
    }
  }

  test("under a tight budget a stage over it is halved until every method fits") {
    // At 1000 bytes the first stage size, read off the driver, already fits; the check is that
    // the stages and the driver both come in under the budget, however many rebuilds it takes.
    val roots = dateAdds(300)
    val tight = oneEach(split).withMethodByteBudget(1000)
    val (_, _, m) = emitted(roots, 300, tight)
    m.codeLength.asScala.foreach { case (method, bytes) =>
      if (method.startsWith("stage") || method.startsWith("run")) {
        assert(bytes <= 1000, s"$method is $bytes bytes")
      }
    }
    assert(named(m, "stageDense") > 10)
  }

  test("a driver past the class-file cap is split into stages under the byte budget in one " +
      "rebuild") {
    // Fifteen hundred one-output groups: the driver is past 64KB, so the Class-File API refuses it
    // while the class is assembled. The stages are sized for the byte budget, not for the cap
    // that found the driver over, so the next build fits (the review of #529).
    val roots = dateAdds(1500)
    val (_, builds, m) = emitted(roots, 1500, oneEach(split))
    assert(builds === 2, s"$builds builds")
    assert(VarkaEmitBudget.overLimits(m).isEmpty, VarkaEmitBudget.overLimits(m))
    assert(named(m, "stageDense") > 1)
  }

  test("a split driver answers as the reference evaluator does, on both bodies") {
    val roots = dateAdds(300)
    for (masked <- Seq(false, true)) {
      checkMatrix(roots, 1, (0 until 300).map(k => k * 7 - 1000).toArray,
        if (masked) Seq(17, 129) else Seq(1, 17, 129), combos(1), forceMasked = masked,
        ctx = s"300 one-output groups, split, forceMasked $masked", options = oneEach(split))
    }
  }

  test("eight hundred greatest entries - two hundred groups over one date - answer from a split " +
      "driver, the prefix each group shares computed as before") {
    val roots = (0 until 800).map(ladderEntry)
    val (_, _, m) = emitted(roots, 800, split)
    assert(named(m, "stageDense") >= 2, "two hundred groups are past the driver's ceiling")
    for (masked <- Seq(false, true)) {
      checkMatrix(roots, 1, (0 until 800).map(k => k % 40 - 20).toArray,
        if (masked) Seq(17, 129) else Seq(1, 129), combos(1), forceMasked = masked,
        ctx = s"800 greatest entries, split, forceMasked $masked", options = split)
    }
  }
}
