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

import java.util.Random

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaCompiledCheck.{History, Run,
  Settings, Tally}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTierWitness._
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The tier witness's own tests (VARKA-274): that [[VarkaTierWitness]] tells the interpreter, C2,
 * a trap and an on-stack replacement apart and puts each in the call it happened in, that its
 * reading of the code list is right, and that [[VarkaCompiledCheck]]'s histories compare what
 * they run. Without these, a witness that always said "C2" would pass every history.
 *
 * The verdicts are facts about the JIT, so the suite runs with the other JIT-measuring suites,
 * on a quiet machine and without the memory sanitizer (`dev/varka_gate.sh`'s quiet step), and in
 * a JVM of its own: production's compiler directive, which the warmed histories install, is
 * JVM-wide. It holds under the sanitizer too, as `dev/varka_matrix.sh --defaults` runs it: the
 * check's warm-up calls the kernel in the sanitizer's window, as the compared calls are, so C2's
 * code is not compiled for a check that never finds a window (VARKA-400).
 */
class VarkaTierWitnessSuite extends SparkFunSuite with VarkaOwnJvm {

  private val settings = Settings.fromProperties()

  private val c0 = new ColumnRef(0)
  private val c1 = new ColumnRef(1)

  /** A range guard over column 0 and the year of a column-offset day: a guard edge to reach. */
  private val guarded = Seq[VarkaVectorIR](new GuardedRange(c0, -1000L, 1000L),
    new Year(new AddDays(c0, c1)))

  /**
   * A fixed two-column case of `roots` on 100 rows drawn inside every guard and with no nulls.
   * The label starts as a fuzz case's does, so a failure message's replay can be checked.
   */
  private def fixedCase(roots: Seq[VarkaVectorIR]): VarkaFuzzCase = {
    val length = 100
    val rnd = new Random(20261013L)
    val data = Array.tabulate(2, length)((_, _) => (rnd.nextInt(2001) - 1000).toLong)
    new VarkaFuzzCase(LaneType.INT, roots.asJava, 2, Array.emptyLongArray, length,
      Array.ofDim[Boolean](2, length), data, false, VarkaMatrix.base, -1, -1,
      "seed=7 iteration=3 a fixed case")
  }

  private def emitter(c: VarkaFuzzCase): java.util.function.Function[String, Array[Byte]] =
    (name: String) => VarkaLoopEmitter.emit(name, c.roots, c.numInputs, c.lits.length, null,
      null, c.options)

  private val replay = "-Dvarka.fuzz.seed=7 -Dvarka.fuzz.only=3"

  /** One history of `c`, its verdicts settled. */
  private def run(c: VarkaFuzzCase, h: History, s: Settings = settings): Run = {
    val result = VarkaCompiledCheck.run(c, emitter(c), s.withOnly(h), replay)
    VarkaTierWitness.get().settle()
    assert(result.runs.size == 1)
    result.runs.get(0)
  }

  private def labels(r: Run): Seq[String] = r.verdicts.asScala.map(_.label).toSeq

  private def interpreted(v: Verdict): Boolean = v match {
    case m: Mixed => m.interpreted
    case _ => false
  }

  test("the witness sees each tier: a cold call interpreted, a warmed one C2, and a trap") {
    val cold = run(fixedCase(guarded), History.COLD)
    assert(cold.verdicts.asScala.forall(interpreted), s"cold: ${cold.verdicts}")
    val warm = run(fixedCase(guarded), History.WARM_SAME)
    assert(warm.settled, s"the warm-up did not settle in ${settings.settleMillis} ms")
    assert(warm.confirmed, "the warm-up's decision did not hold once the recording caught up")
    assert(labels(warm).forall(_ == "C2"), s"warm-same: ${warm.verdicts}")
    // The trap is the remainder's, in an epilogue: C2 compiled it from calls that never left a
    // remainder past the last whole vector. The plan's all-null input traps in the masked
    // driver's all-null shortcut, and only when C2 has compiled the driver, which it often does
    // not (`VARKA-274.md` 9), so it cannot be the trap a test relies on.
    val quiet = run(fixedCase(guarded), History.QUIET_REMAINDER)
    assert(quiet.verdicts.asScala.exists {
      case t: Trapped => t.reason == "unstable_if" && t.method.startsWith("epilogue")
      case _ => false
    }, s"quiet-remainder: ${quiet.verdicts}")
  }

  test("the witness sees an on-stack replacement at C1's tier and at C2's") {
    val released = run(fixedCase(guarded), History.RELEASED)
    val osr3 = Tally.first(released, "osr3")
    val osr4 = Tally.first(released, "osr4")
    info(s"released over ${released.calls.size} batches: the first served by a tier-3 OSR is " +
      s"$osr3, by a tier-4 OSR $osr4")
    assert(osr3 >= 0 && osr4 > osr3, s"released: ${labels(released)}")
  }

  test("a trap is attributed to the call it fell in") {
    // quiet-remainder's first call is a batch like the warm-up's, and the next two the batch with
    // a remainder through each body: a trap falls in one of those calls' windows and not the
    // first's, in the epilogue of the body its own call ran. Whether the second body traps too
    // depends on the width: at 128 bits the masked epilogue has been seen not to.
    val quiet = run(fixedCase(guarded), History.QUIET_REMAINDER)
    val verdicts = quiet.verdicts.asScala
    assert(verdicts.size == 3, s"quiet-remainder: $verdicts")
    assert(verdicts(0).isInstanceOf[C2], s"the quiet call: ${verdicts(0)}")
    val traps = verdicts.tail.zip(Seq("Dense", "Masked")).collect {
      case (t: Trapped, side) => (t, side)
    }
    assert(traps.nonEmpty, s"no call with a remainder trapped: $verdicts")
    traps.foreach { case (t, side) => assert(t.method.startsWith("epilogue" + side), s"$t") }
  }

  test("the code list is read right") {
    // Lines as `compilerCodelist` prints them (`<id> <level> <state> <method> [addresses]`, the
    // addresses shortened here), renamed onto one kernel class: a method at level 4 in use
    // (state 0) beside an older level-3 version made not entrant (state 1); one whose level-4
    // code was made not entrant beside a level-3 version in use; one recompiled at level 4 after
    // a not-entrant version; one whose only level-4 code is an on-stack replacement; and one with
    // no line at all.
    val cls = "org.apache.spark.sql.varka.execution.VarkaFusedFuzzTier9"
    val text =
      s"""1729 3 1 $cls.loopDense0(JJJJ)I [0x...]
         |1731 4 0 $cls.loopDense0(JJJJ)I [0x...]
         |1740 4 1 $cls.loopMasked0(JJJJ)I [0x...]
         |1741 3 0 $cls.loopMasked0(JJJJ)I [0x...]
         |1750 4 1 $cls.epilogueDense0(JJJJ)I [0x...]
         |1751 4 0 $cls.epilogueDense0(JJJJ)I [0x...]
         |1760 4 0 $cls.epilogueMasked0(JJJJ)I [0x...]
         |1770 4 0 java.lang.String.hashCode()I [0x...]
         |""".stripMargin
    val entries = VarkaTierWitness.parse(text, cls)
    assert(entries.size == 7)
    val methods = Set("loopDense0", "loopMasked0", "epilogueDense0", "epilogueMasked0",
      "runDense").asJava
    // What the recording reported: every compile finished, 1760 an on-stack replacement.
    val known = new java.util.HashMap[Integer, java.lang.Boolean]()
    Seq(1729, 1731, 1740, 1741, 1750, 1751).foreach(id => known.put(id, false))
    known.put(1760, true)
    val none = java.util.Map.of[Integer, java.util.Set[String]]()
    val levels = VarkaTierWitness.levels(entries, methods, known, none).asScala
    assert(levels == Map("loopDense0" -> 4, "loopMasked0" -> 3, "epilogueDense0" -> 4,
      "epilogueMasked0" -> 0, "runDense" -> 0))
    // A method C2 inlined into a caller's level-4 code in use counts as C2 code.
    val inlined = java.util.Map.of[Integer, java.util.Set[String]](1731, Set("runDense").asJava)
    assert(VarkaTierWitness.levels(entries, methods, known, inlined).get("runDense") == 4)
    assert(VarkaTierWitness.osrLevels(entries, methods, known).get("epilogueMasked0") == 4)
    assert(VarkaTierWitness.allC2(entries, Set("loopDense0", "epilogueDense0").asJava, known,
      none))
    assert(!VarkaTierWitness.allC2(entries, Set("loopDense0", "loopMasked0").asJava, known,
      none))
    // A version whose compile the recording has not delivered yet does not count: it could be an
    // on-stack replacement, and what it inlined is not known.
    known.remove(1751)
    assert(VarkaTierWitness.levels(entries, methods, known, none).get("epilogueDense0") == 0)
    known.put(1751, false)
    // C2 at both edges means the same version: 1751 at one edge and a recompiled 1790 at the
    // other is not one C2 version that served the whole call.
    val recompiled = VarkaTierWitness.parse(text.replace("1751 4 0", "1790 4 0"), cls)
    known.put(1790, false)
    val both = VarkaTierWitness.c2Versions(entries, "epilogueDense0", known, none)
    both.retainAll(VarkaTierWitness.c2Versions(recompiled, "epilogueDense0", known, none))
    assert(both.isEmpty)
    assert(VarkaTierWitness.c2Versions(recompiled, "epilogueDense0", known, none).asScala ==
      Set(1790))
  }

  test("a planted wrong answer fails every history, the history named") {
    VarkaKernelChecks.plantWrongAnswer = true
    try {
      for (h <- History.values) {
        val e = intercept[IllegalStateException] {
          VarkaCompiledCheck.run(fixedCase(guarded), emitter(fixedCase(guarded)),
            settings.withOnly(h).withReleased(3, 1000), replay)
        }
        assert(e.getMessage.contains(s"history=${h.label}") &&
          e.getMessage.contains("differs (want"), s"${h.label}: ${e.getMessage}")
      }
    } finally {
      VarkaKernelChecks.plantWrongAnswer = false
    }
  }

  test("an unsettled kernel is a result, its batches compared") {
    val r = run(fixedCase(guarded), History.WARM_SAME, settings.withSettleMillis(1))
    assert(!r.settled)
    assert(r.calls.size == 2, "both bodies' batches are compared")
  }

  test("a failure names the seed, the iteration and the history, and one history replays alone") {
    VarkaKernelChecks.plantWrongAnswer = true
    val e = try {
      intercept[IllegalStateException] {
        VarkaCompiledCheck.run(fixedCase(guarded), emitter(fixedCase(guarded)),
          settings.withOnly(History.WARM_OTHER), replay)
      }
    } finally {
      VarkaKernelChecks.plantWrongAnswer = false
    }
    Seq("seed=7 iteration=3", "history=warm-other",
      "replay with -Dvarka.fuzz.seed=7 -Dvarka.fuzz.only=3 -Dvarka.fuzz.history=warm-other")
      .foreach(part => assert(e.getMessage.contains(part), s"'$part' in ${e.getMessage}"))
    val before = System.getProperty("varka.fuzz.history")
    System.setProperty("varka.fuzz.history", "quiet-null")
    try {
      val s = Settings.fromProperties()
      val result = VarkaCompiledCheck.run(fixedCase(guarded), emitter(fixedCase(guarded)), s,
        replay)
      assert(result.runs.asScala.map(_.history) == Seq(History.QUIET_NULL))
    } finally {
      if (before == null) System.clearProperty("varka.fuzz.history")
      else System.setProperty("varka.fuzz.history", before)
    }
  }
}
