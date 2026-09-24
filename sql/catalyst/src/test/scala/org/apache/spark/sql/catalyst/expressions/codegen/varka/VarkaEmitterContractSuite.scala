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

import java.lang.foreign.Arena
import java.lang.ref.{ReferenceQueue, WeakReference}

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The entry point's contract: out-of-shape IR is refused with a reason and never emitted wrong, a
 * wrong descriptor fails naming the call, the class passes verification and unloads with its
 * loader, the shallow rendering of every node is pinned, and the telemetry attributes index the IR
 * nodes they record.
 */
class VarkaEmitterContractSuite extends VarkaEmitterTestBase {

  test("the emitted class passes class-file verification before it is ever loaded") {
    val errors = VarkaEmitterTestSupport.verify(emit(addDays(0), 1)._2).asScala
    assert(errors.isEmpty, s"verifier errors: ${errors.mkString("; ")}")
  }

  test("IR outside the emitter's shape is rejected with a reason, not emitted wrong") {
    def rejects(body: => Unit, fragment: String): Unit = {
      val e = intercept[IllegalArgumentException](body)
      assert(e.getMessage.contains(fragment), s"message was: ${e.getMessage}")
    }
    rejects(emit(chain(VarkaLoopEmitter.MAX_CHAIN_DEPTH + 1),
      VarkaLoopEmitter.MAX_CHAIN_DEPTH + 1), "MAX_CHAIN_DEPTH")
    rejects(emit(new AddDays(new ColumnRef(1), new LiteralSlot(0)), 1), "column ordinal")
    rejects(emit(new AddDays(new ColumnRef(0), new LiteralSlot(1)), 1), "literal slot")
    // A column offset (task 38) is legal IR now - AddDays(ColumnRef, ColumnRef) no longer
    // throws; see "AddDays/SubDays with a column offset (task 38) match the reference
    // evaluator" above for its coverage.
    rejects(VarkaLoopEmitter.emit("t", java.util.List.of[VarkaVectorIR](), 1, 0),
      "no output chains")
    rejects(VarkaLoopEmitter.emit("t", java.util.List.of(addDays(0)), 0, 1), "numInputs")
    rejects(VarkaLoopEmitter.emit("t", java.util.List.of(addDays(0)),
      VarkaLoopEmitter.MAX_INPUTS + 1, 1), "numInputs")
    // 5 disjoint depth-13 chains hold 65 distinct ops, one past the total-size cap of the form
    // without a byte budget. The cap counts nodes after CSE: the same 4 chains repeated as 8
    // outputs stay within it. Under the byte budget, the default, bytes decide instead and the
    // five chains emit (task 190).
    val disjointChains = (0 until 5).map(k => chain(13, slotBase = k * 13))
    val reference = VarkaEmitOptions.DEFAULTS.withMethodByteBudget(0)
    rejects(emitMulti(disjointChains, 1, 65, reference), "MAX_FUSED_NODES")
    val (_, sharedOk) = emitMulti(
      disjointChains.take(4) ++ disjointChains.take(4), 1, 52, reference)
    assert(sharedOk.nonEmpty)
    assert(emitMulti(disjointChains, 1, 65)._2.nonEmpty)
    // Task 11: conditions are never values. (A condition as an output ROOT became legal in
    // task 21 - it emits a selection bitmap - so only the value positions reject now.)
    val cmp = new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(0))
    rejects(emitMulti(Seq(new AddDays(cmp, new LiteralSlot(0))), 1, 1), "value position")
    rejects(emitMulti(Seq(new Greatest(new ColumnRef(0), cmp)), 1, 0), "value position")
  }

  test("a wrong descriptor fails naming the call, not as an anonymous VerifyError") {
    val named = emit(addDays(0), 1, VarkaEmitOptions.DEFAULTS.withMisdescribeAdd(true))
    // Member resolution is link-time work, so the class still verifies...
    assert(VarkaEmitterTestSupport.verify(named._2).isEmpty)
    val (kernel, loader) = load(named)
    try {
      val arena = Arena.ofConfined()
      try {
        // Long enough that the vector loop (where the wrong call sits) runs at any width.
        val length = 64
        val input = makeInput(arena, length, _ => false)
        val out = makeOutput(arena, length)
        val e = intercept[LinkageError] {
          kernel.run(
            Array(input.data.address()), Array(0L), Array(0),
            Array(out._1.address()), Array(out._2.address()), Array(1), length)
        }
        // ...and the first execution names the exact call the descriptor table got wrong.
        assert(e.isInstanceOf[NoSuchMethodError], s"got ${e.getClass}: ${e.getMessage}")
        assert(e.getMessage.contains("IntVector.add"), s"message was: ${e.getMessage}")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("the emitted class unloads once the loader is released") {
    val queue = new ReferenceQueue[ClassLoader]()
    val (className, bytes) = emit(addDays(0), 1)
    var loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    val ref = new WeakReference[ClassLoader](loader, queue)
    loader.defineGeneratedClass(className, bytes)
    loader.loadClass(className)
    loader.release()
    // Drop the only strong reference; the frame slot must not pin the loader (the reason the
    // existing loader suite uses a var too).
    loader = null
    var collected = false
    var attempts = 0
    while (!collected && attempts < 50) {
      System.gc()
      collected = queue.remove(100) != null
      attempts += 1
    }
    assert(collected, "the loader (and with it the emitted class) was not collected")
    assert(ref.get() == null)
  }

  /** The committed line map of the every-node-type key; see the test that pins it. */
  private val pinnedLineMap = Seq(
    "1=col:0",
    "2=lit:0",
    "3=(cmp:LT 1 2)",
    "4=(cmp:EQ 1 2)",
    "5=(not 4)",
    "6=(or 3 5)",
    "7=(cmp:GE 1 2)",
    "8=(isNotNull 1)",
    "9=(and 7 8)",
    "10=(and 6 9)",
    "11=(addDays 1 2)",
    "12=(subDays 1 2)",
    "13=(greatest 11 12)",
    "14=(year 1)",
    "15=(month 1)",
    "16=(greatest 14 15)",
    "17=(dayOfMonth 1)",
    "18=(quarter 1)",
    "19=(lastDay 1)",
    "20=(truncDate:YEAR 1)",
    "21=(least 19 20)",
    "22=(least 18 21)",
    "23=(greatest 17 22)",
    "24=(least 16 23)",
    "25=(dayOfYear 1)",
    "26=(thursdayOf 1)",
    "27=(weekOfYear 26)",
    "28=(greatest 25 27)",
    "29=(greatest 24 28)",
    "30=(dayOfWeek 1)",
    "31=(dateDiff 29 30)",
    "32=(weekDay 1)",
    "33=(dayOfWeekIso 1)",
    "34=(least 32 33)",
    "35=(nextDay 1 2)",
    "36=(addMonths 1 2)",
    "37=(truncDateDynamic 1 1)",
    "38=(makeDate:NULL 1 2 2)",
    "39=(makeDate:ANSI 1 2 2)",
    "40=(least 38 39)",
    "41=(least 37 40)",
    "42=(least 36 41)",
    "43=(least 35 42)",
    "44=(least 34 43)",
    "45=(least 31 44)",
    "46=(if 10 13 45)").mkString("\n")

  /** The class's own LineNumberTable key, parsed back into line -> rendered IR node. */
  private def lineKey(bytes: Array[Byte]): Map[Int, String] = {
    val recorded = VarkaDebugInfoReader.lineMap(bytes)
    assert(recorded != null && recorded.nonEmpty, "the class recorded no line map")
    recorded.linesIterator.map { entry =>
      val parts = entry.split("=", 2)
      parts(0).toInt -> parts(1)
    }.toMap
  }

  test("emit rejects null options the way it rejects its other arguments") {
    // The other two argument checks throw IllegalArgumentException with a message; options
    // would otherwise have failed as a bare NPE partway through the analysis walk.
    val e = intercept[IllegalArgumentException] {
      VarkaLoopEmitter.emit("X", Seq[VarkaVectorIR](addDays(0)).asJava, 1, 1, null, null, null)
    }
    assert(e.getMessage.contains("options"), e.getMessage)
  }

  test("the shallow rendering of every node type is pinned, like the shape hash") {
    // The line map travels inside the class bytes and is read back by tooling with no live
    // session, so its rendering is a contract, not an implementation detail - and it used to
    // ride Record.toString, whose format no JDK promises. One key using all 24 node types (and
    // three CompareOps), so a change to any rendering, to the operand order, or to the
    // topological schedule fails here. If it does: make sure the change is intended, then
    // update the literal and say so in the task plan - the same rule as the pinned shape
    // hashes in VarkaShapeCacheSuite. Task 26 added the four calendar extractions and
    // re-pinned it (PLAN_TASK_26.md); task 33 added NextDay, task 40 added AddMonths, task 36
    // added LastDay, task 34 added DayOfYear and task 61 added TruncDateDynamic, each
    // re-pinning it again (PLAN_TASK_33.md, PLAN_TASK_40.md, PLAN_TASK_36.md, PLAN_TASK_34.md,
    // PLAN_TASK_61.md). Re-pinned from the failing
    // assertion's own output, never carried over from one side of a merge: a line map that is
    // right for one node set is wrong for the union of two.
    val col = new ColumnRef(0)
    val lit = new LiteralSlot(0)
    val cond = new And(
      new Or(
        new Compare(CompareOp.LT, col, lit),
        new Not(new Compare(CompareOp.EQ, col, lit))),
      new And(new Compare(CompareOp.GE, col, lit), new IsNotNull(col)))
    val chrono = new Greatest(
      new Least(
        new Greatest(new Year(col), new Month(col)),
        new Greatest(new DayOfMonth(col),
          new Least(new Quarter(col),
            new Least(new LastDay(col), new TruncDate(col, TruncLevel.YEAR))))),
      new Greatest(new DayOfYear(col), new WeekOfYear(new ThursdayOf(col))))
    val everyNode = new IfElse(
      cond,
      new Greatest(new AddDays(col, lit), new SubDays(col, lit)),
      new Least(new DateDiff(chrono, new DayOfWeek(col)),
        new Least(new Least(new WeekDay(col), new DayOfWeekIso(col)),
          new Least(new NextDay(col, lit),
            new Least(new AddMonths(col, lit),
              new Least(new TruncDateDynamic(col, col),
                new Least(new MakeDate(col, lit, lit, false),
                  new MakeDate(col, lit, lit, true))))))))
    val (_, bytes) = emitMulti(Seq(everyNode), 1, 1)
    val lineMap = VarkaDebugInfoReader.lineMap(bytes)
    assert(lineMap === pinnedLineMap, s"re-pin pinnedLineMap from this output:\n$lineMap")
    // The DAG, not a tree: col:0 is written once as line 1 and pointed at sixteen times. The
    // Record.toString rendering this replaced inlined every subtree, so line 25 alone carried
    // the whole IR and the key grew quadratically in exactly the sharing the emitter exploits.
    assert(pinnedLineMap.linesIterator.count(_.contains("col:0")) === 1)
  }

  test("telemetry: the emitted lines index the IR nodes the debug attribute records") {
    // datediff(date_add(d, 1), d2): five distinct nodes, so the loop and the epilogue
    // attribute their instructions to lines 1..5 and the key decodes every one of them.
    val add = new AddDays(new ColumnRef(0), new LiteralSlot(0))
    val root = new DateDiff(add, new ColumnRef(1))
    val (_, bytes) = emitMulti(Seq(root), 2, 1)
    val key = lineKey(bytes)
    assert(key.keys.toSeq.sorted === (1 to key.size).toSeq,
      "the key must number the nodes 1..N with no gaps")
    // Children strictly before parents, which is what makes a line number a schedule position.
    assert(key(key.size).startsWith("(dateDiff"), s"the root should be last: ${key(key.size)}")
    assert(key.values.exists(_.startsWith("col:")))
    assert(key.values.count(_.startsWith("(addDays")) === 1)
    for (method <- Seq("loopMasked0", "epilogueMasked0", "loopDense0", "epilogueDense0")) {
      val lines = VarkaEmitterTestSupport.lineNumbers(bytes, method)
      assert(lines.asScala.nonEmpty, s"$method carries no LineNumberTable")
      assert(lines.asScala.forall(line => key.contains(line)),
        s"$method has lines outside the key: ${lines.asScala.mkString(", ")}")
    }
  }

  test("a kernel failure's stack frame resolves to the IR node that threw") {
    // The misdescribe option fails the AddDays call site at link time, inside the loop - the
    // shape a real kernel failure takes. The frame through the generated class must name the
    // SourceFile and a line, and the class's own key must decode that line to the node.
    val named = emit(addDays(0), 1, VarkaEmitOptions.DEFAULTS.withMisdescribeAdd(true))
    val (className, bytes) = named
    val (kernel, loader) = load(named)
    try {
      val arena = Arena.ofConfined()
      try {
        val length = 64
        val input = makeInput(arena, length, _ => false)
        val out = makeOutput(arena, length)
        val e = intercept[LinkageError] {
          kernel.run(
            Array(input.data.address()), Array(0L), Array(0),
            Array(out._1.address()), Array(out._2.address()), Array(1), length)
        }
        val frame = e.getStackTrace.find(_.getClassName == className).getOrElse(
          fail(s"no frame in the generated class:\n${e.getStackTrace.mkString("\n")}"))
        val simpleName = className.substring(className.lastIndexOf('.') + 1)
        assert(frame.getFileName === s"$simpleName.java")
        assert(frame.getLineNumber > 0, "the frame carries no line number")
        val node = lineKey(bytes).getOrElse(frame.getLineNumber,
          fail(s"line ${frame.getLineNumber} is not in the recorded key"))
        assert(node.startsWith("(addDays"), s"the failing line decoded to $node")
      } finally {
        arena.close()
      }
    } finally {
      loader.release()
    }
  }

  test("telemetry: the SourceFile and VarkaDebugInfo attributes round-trip off the bytes") {
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedTest${classCounter.addAndGet(1)}"
    val bytes = VarkaLoopEmitter.emit(name, Seq(addDays(0)).asJava, 1, 1,
      "Varka_Project_Stage3.java", "date_add(d#1, 3) AS a#2")
    // The attributes are metadata: the class must verify exactly as it did without them.
    assert(VarkaEmitterTestSupport.verify(bytes).isEmpty)
    // A reader without the mapper sees an opaque attribute under the right name - the shape
    // any third-party class-file tool gets - while the diagnostics reader registers the
    // mapper and recovers the payload: the rendered IR and the caller's plan fragment.
    assert(VarkaEmitterTestSupport.hasAttributeNamed(bytes, "VarkaDebugInfo"))
    assert(VarkaDebugInfoReader.sourceFile(bytes) === "Varka_Project_Stage3.java")
    val ir = VarkaDebugInfoReader.ir(bytes)
    assert(ir.contains("outputs=[(addDays col:0 lit:0)]"))
    assert(ir.contains("numInputs=1"))
    assert(VarkaDebugInfoReader.planFragment(bytes) === "date_add(d#1, 3) AS a#2")
    // Task 16: the same attribute carries the LineNumberTable's decoding key.
    assert(VarkaDebugInfoReader.lineMap(bytes).startsWith("1="))
  }

  test("the telemetry-defaulted emit derives the SourceFile and records no plan fragment") {
    val (className, bytes) = emit(addDays(0), 1)
    val simpleName = className.substring(className.lastIndexOf('.') + 1)
    assert(VarkaDebugInfoReader.sourceFile(bytes) === s"$simpleName.java")
    assert(VarkaDebugInfoReader.ir(bytes).contains("(addDays col:0 lit:0)"))
    assert(VarkaDebugInfoReader.planFragment(bytes) === "")
  }

  test("a debug payload past a class-file constant's limit is cut and marked, and the class " +
      "still builds (task 190)") {
    // Each VarkaDebugInfo field is one constant-pool UTF-8 entry, which a u2 counts, and a
    // kernel of several hundred outputs renders an IR past it; the class-file builder then
    // refused the whole class ("string too long") over metadata the JVM never reads. The plan
    // fragment is the caller's string, so an oversized one reproduces that without the width.
    val name = s"org.apache.spark.sql.varka.execution.VarkaFusedTest${classCounter.addAndGet(1)}"
    val fragment = "x" * 70000
    val bytes = VarkaLoopEmitter.emit(name, Seq(addDays(0)).asJava, 1, 1, null, fragment)
    assert(VarkaEmitterTestSupport.verify(bytes).isEmpty)
    val recorded = VarkaDebugInfoReader.planFragment(bytes)
    assert(recorded.endsWith(VarkaDebugInfo.TRUNCATED) && fragment.startsWith(
      recorded.stripSuffix(VarkaDebugInfo.TRUNCATED)), recorded.takeRight(40))
    assert(recorded.length <= 65535)
    // Three bytes a character is the worst case the count has to get right.
    val wide = VarkaDebugInfo.bounded(0x4e2d.toChar.toString * 30000)
    assert(wide.endsWith(VarkaDebugInfo.TRUNCATED) &&
      (wide.length - VarkaDebugInfo.TRUNCATED.length) * 3 + VarkaDebugInfo.TRUNCATED.length
        <= 65535, wide.length)
    assert(VarkaDebugInfo.bounded("short") === "short")
  }
}
