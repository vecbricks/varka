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
import org.apache.spark.sql.varka.vector.VarkaVectorSupport

/**
 * The driver under `VarkaEmitOptions.driverOutputTable`: its per-output work - each output's
 * validity zeroed, filled or written by the bitmap pass, and the all-null shortcut's test - read
 * from a table by one call, so the driver no longer grows with the outputs and stops capping a
 * kernel's width. The loop and epilogue methods are untouched, and every answer must be the
 * unrolled driver's. See `VARKA-190.md` 9.2 and 10.
 */
class VarkaEmitterDriverTableSuite extends VarkaEmitterTestBase {

  private val table = VarkaEmitOptions.DEFAULTS.withDriverOutputTable(true)
  private val unrolled = VarkaEmitOptions.DEFAULTS.withDriverOutputTable(false)

  test("the emitter's plan steps are the engine's") {
    // Catalyst names the engine's support class by string and cannot import its constants, so
    // the emitter restates them; a step spelled differently on the two sides would be a plan the
    // engine rejects on the first batch.
    assert(Seq(VarkaBodyEmitter.PLAN_ZERO, VarkaBodyEmitter.PLAN_ZERO_WORDS,
      VarkaBodyEmitter.PLAN_FILL, VarkaBodyEmitter.PLAN_COPY, VarkaBodyEmitter.PLAN_AND,
      VarkaBodyEmitter.PLAN_OR) === Seq(VarkaVectorSupport.PLAN_ZERO,
      VarkaVectorSupport.PLAN_ZERO_WORDS, VarkaVectorSupport.PLAN_FILL,
      VarkaVectorSupport.PLAN_COPY, VarkaVectorSupport.PLAN_AND, VarkaVectorSupport.PLAN_OR))
  }

  private def ladderEntry(k: Int): VarkaVectorIR = {
    val col = new ColumnRef(0)
    new Greatest(new Greatest(new AddMonths(col, new LiteralSlot(k)),
      new AddDays(col, new LiteralSlot(k))), new LastDay(col))
  }

  private def measured(roots: Seq[VarkaVectorIR], lits: Int,
      options: VarkaEmitOptions, inputs: Int = 1): VarkaEmittedClass =
    VarkaEmittedClass.measure(emitMulti(roots, inputs, lits, options)._2)

  private def groups(m: VarkaEmittedClass): Int =
    m.codeLength.keySet.asScala.count(_.startsWith("loopDense"))

  test("the driver grows with the groups and not with the outputs or the columns") {
    // One `date_add(d, k)` family at four widths, over one column and over the sixty-four
    // columns a kernel may read, the budget out of reach so the unrolled form can be read past
    // its ceiling too. Unrolled, each output adds about forty bytes to either driver, each literal
    // it hoists and never reads about seven more, and each column its null state and segments;
    // from a table the driver's size is a constant plus its two calls per group, 44 bytes.
    val wide = VarkaEmitOptions.DEFAULTS.withMethodByteBudget(VarkaEmitBudget.METHOD_CODE_CAP)
    for (inputs <- Seq(1, VarkaEmitBudget.MAX_INPUTS); n <- Seq(64, 100, 200, 400)) {
      val roots = (0 until n).map(k => new AddDays(new ColumnRef(k % inputs), new LiteralSlot(k)))
      val before = measured(roots, n, wide.withDriverOutputTable(false), inputs)
      val tabled = measured(roots, n, wide.withDriverOutputTable(true), inputs)
      assert(groups(tabled) === groups(before))
      for (driver <- Seq("runDense", "runMasked")) {
        val bytes = tabled.codeLength.get(driver).toInt
        assert(bytes <= 100 + 44 * groups(tabled), s"$n outputs over $inputs columns: $driver " +
          s"is $bytes bytes over ${groups(tabled)} groups")
        assert(before.codeLength.get(driver) - bytes >= 35 * n,
          s"$n outputs over $inputs columns: $driver is $bytes bytes from the table and " +
            s"${before.codeLength.get(driver)} unrolled")
      }
    }
  }

  test("a table past what one class-file constant holds declines with the reason") {
    // A plan string is one CONSTANT_Utf8, capped at 65535 bytes in the class file's modified
    // UTF-8, where column ordinal 0 takes two bytes. A kernel that wide is past every other limit
    // too; the point is that it declines naming the table rather than failing the class build.
    val fits = "z" * 65535
    assert(VarkaBodyEmitter.tableConstant(fits, "output plan") eq fits)
    val declined = intercept[VarkaEmitDeclined](
      VarkaBodyEmitter.tableConstant("\u0000" * 32768, "all-null shortcut"))
    assert(declined.getMessage.contains("all-null shortcut table is 65536 bytes"))
    assert(declined.outputs().isEmpty)
  }

  test("four hundred greatest entries emit under the shipped budget from a table, and decline " +
      "on the driver without it") {
    // The size ladder's widest rung. Unrolled, both drivers pass 8000 bytes past about 140
    // entries whatever the grouping; from a table the driver is 44 bytes a group, and a
    // hundred groups fit.
    val roots = (0 until 400).map(ladderEntry)
    val declined = intercept[VarkaEmitDeclined](emitMulti(roots, 1, 400, unrolled))
    assert(declined.getMessage.contains("runDense") && declined.getMessage.contains("runMasked"))
    val m = measured(roots, 400, table)
    assert(VarkaEmitBudget.overLimits(m).isEmpty, VarkaEmitBudget.overLimits(m))
    assert(groups(m) === 100)
  }

  test("from a table the loop and epilogue methods are the unrolled form's, byte for byte") {
    // The table replaces the driver's own work and nothing it calls, so every group method is
    // unchanged; only `runDense` and `runMasked` may differ. Over the fuzzer's own shapes at both
    // lanes, as the bytes oracle reads them.
    def bodies(b: Array[Byte]): Map[String, String] =
      VarkaEmitterTestSupport.methodBodies(b).asScala.toMap
        .map { case (m, body) => m -> body.replaceAll("VarkaFusedTest\\d+", "K") }
        .filterNot { case (m, _) => m.startsWith("runDense(") || m.startsWith("runMasked(") }
    def emitted(roots: Seq[VarkaVectorIR], inputs: Int, lits: Int,
        options: VarkaEmitOptions): Option[Array[Byte]] =
      try Some(emitMulti(roots, inputs, lits, options)._2) catch {
        case _: VarkaEmitDeclined => None
      }
    var compared = 0
    for (k <- 0 until 400) {
      val d = VarkaIrGrammar.drawShape(VarkaIrGrammar.shapeRandom(VarkaIrGrammar.fuzzSeed, k))
      val l = VarkaIrGrammar.drawLongShape(
        VarkaIrGrammar.shapeRandom(VarkaIrGrammar.longFuzzSeed, k))
      for ((roots, inputs, lits) <- Seq((d.roots, d.numInputs, d.numLiterals),
          (l.roots, l.numInputs, l.numLiterals))) {
        (emitted(roots, inputs, lits, VarkaEmitOptions.DEFAULTS),
          emitted(roots, inputs, lits, table)) match {
          case (Some(a), Some(b)) =>
            compared += 1
            assert(bodies(a) === bodies(b), s"shape $k: $roots")
          case (a, b) => assert(a.isEmpty && b.isEmpty, s"shape $k: one form declines: $roots")
        }
      }
    }
    assert(compared >= 600, s"only $compared shapes emitted under both forms")
  }

  test("from a table every kind of output validity answers as the reference evaluator does") {
    // Each step the table can hold, on the batches that reach it: a zero for an output the loop
    // writes, a fill for a dense value output, and on a masked batch the bitmap pass's copy, AND,
    // OR and three-column chain, a constant fill for an output over literals alone, and a
    // selection's zeroed bitmap - and, with a column all-null, the shortcut's early return. At the
    // host's width and at sixteen lanes, where validity is written a word at a time and the zero
    // maps the bitmap to its last whole word.
    val c0 = new ColumnRef(0)
    val c1 = new ColumnRef(1)
    val c2 = new ColumnRef(2)
    val shapes: Seq[(String, Seq[VarkaVectorIR], Int)] = Seq(
      ("copy, fill and zero", Seq(new Year(c0), new AddDays(c1, new LiteralSlot(0)),
        new IfElse(new Compare(CompareOp.LT, c0, c1), c0, c2)), 1),
      ("and, or and a chain", Seq(new IntArith(IntOp.ADD, Overflow.WRAP, c0, c1),
        new Greatest(c0, c2), new IntArith(IntOp.SUB, Overflow.WRAP,
          new IntArith(IntOp.ADD, Overflow.WRAP, c0, c1), c2)), 0),
      ("a literal alone and a column", Seq(new IntNeg(Overflow.WRAP, new LiteralSlot(0)),
        new IntNeg(Overflow.WRAP, c1)), 1),
      ("a selection", Seq(new And(new Compare(CompareOp.LT, c0, c1), new IsNotNull(c2))), 0))
    // A forced-masked run starts at seven rows: at one, the null count it reports for a null-free
    // column equals the length, which the kernel contract reads as all-null.
    for ((name, roots, lits) <- shapes; lanes <- Seq(0, 16); masked <- Seq(false, true)) {
      checkMatrix(roots, 3, (0 until lits).map(_ + 3).toArray,
        if (masked) Seq(7, 17, 64, 129) else Seq(1, 7, 17, 64, 129),
        combos(3), forceMasked = masked, ctx = s"$name, lanes $lanes, forceMasked $masked",
        options = table.withLanesOverride(lanes))
    }
  }

  test("two hundred greatest entries answer from a table as the reference evaluator does") {
    // A width the unrolled driver cannot emit at all, run on both bodies.
    val roots = (0 until 200).map(ladderEntry)
    for (masked <- Seq(false, true)) {
      checkMatrix(roots, 1, (0 until 200).map(k => k % 40 - 20).toArray,
        if (masked) Seq(17, 129) else Seq(1, 17, 129),
        combos(1), forceMasked = masked, ctx = s"200 greatest entries, forceMasked $masked",
        options = table)
    }
  }
}
