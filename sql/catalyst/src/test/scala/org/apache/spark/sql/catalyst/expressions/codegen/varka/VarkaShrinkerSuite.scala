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
import scala.jdk.OptionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

/**
 * The shrinker over synthetic failures (VARKA-277): a predicate on the case stands in for the
 * kernel, so nothing is emitted and the suite checks the reduction alone.
 */
class VarkaShrinkerSuite extends SparkFunSuite {

  private val sigA =
    new VarkaFailureSignature("output mismatch", "output # row # differs (want #)")
  private val sigB = new VarkaFailureSignature("declined batch", "the kernel declined the batch")

  private def shrink(c: VarkaFuzzCase, sig: VarkaFailureSignature,
      fails: VarkaFuzzCase => Option[VarkaFailureSignature],
      maxRuns: Int = 600): VarkaShrinker.Shrunk =
    VarkaShrinker.shrink(c, sig, (x: VarkaFuzzCase) => fails(x).toJava, maxRuns, 60000L)

  private def contains(node: VarkaVectorIR, cls: Class[_]): Boolean =
    cls.isInstance(node) || VarkaVectorIR.childrenOf(node).exists(contains(_, cls))

  private def makeCase(
      roots: Seq[VarkaVectorIR],
      length: Int = 20,
      options: VarkaEmitOptions = VarkaEmitOptions.DEFAULTS,
      numInputs: Int = 2,
      smallOrdinal: Int = -1): VarkaFuzzCase = new VarkaFuzzCase(
    LaneType.INT, roots.asJava, numInputs, Array(3L, 4L), length,
    Array.tabulate(numInputs, length)((c, i) => i % (c + 3) == 0),
    Array.tabulate(numInputs, length)((c, i) => (i * 7 + c).toLong),
    true, options, smallOrdinal, -1, "case")

  test("ddmin finds the culprits and no more, in about 2 log2 n runs for one") {
    val items = (0 until 100).map(Int.box).asJava
    for (culprits <- Seq(Set(17), Set(3, 80), Set(5, 6, 7), Set(0), Set(99))) {
      var runs = 0
      val found = VarkaShrinker.ddmin(items, (xs: java.util.List[Integer]) => {
        runs += 1
        culprits.forall(c => xs.contains(c))
      }).asScala.map(_.intValue)
      assert(found.toSet == culprits, s"culprits $culprits, found $found")
      if (culprits.size == 1) {
        assert(runs <= 2 * 7 + 6, s"one culprit among 100 took $runs runs")
      }
    }
  }

  test("a tree shrinks to the node that matters, with the batch and options reduced too") {
    val col0 = new ColumnRef(0)
    val col1 = new ColumnRef(1)
    val tree = new AddDays(new LastDay(new Greatest(col0, col1)),
      new IntNeg(Overflow.WRAP, new LiteralSlot(0)))
    val opts = VarkaEmitOptions.DEFAULTS.toBuilder.cse(false).groupBudget(7).build()
    val c = makeCase(Seq(tree, new Year(col1)), length = 40, options = opts)
    val fails = (x: VarkaFuzzCase) =>
      if (x.roots.asScala.exists(contains(_, classOf[LastDay])) && !x.options.cse()) Some(sigA)
      else None
    val shrunk = shrink(c, sigA, fails)
    assert(shrunk.small.roots.size == 1)
    assert(shrunk.small.roots.get(0) == new LastDay(col0), shrunk.small.roots.get(0))
    assert(VarkaFuzzCase.optionDelta(shrunk.small.options).asScala == Seq("cse=false"),
      VarkaFuzzCase.optionDelta(shrunk.small.options))
    assert(shrunk.small.length == 1)
    assert(!shrunk.small.forceMasked)
    assert(shrunk.small.nulls.forall(_.forall(!_)))
    assert(shrunk.small.data.forall(_.forall(_ == 0L)), VarkaFuzzCase.describe(shrunk.small))
    assert(!shrunk.stoppedEarly)
  }

  test("a reduction that fails differently is not smaller") {
    val col0 = new ColumnRef(0)
    val tree = new LastDay(new AddDays(col0, new IntNeg(Overflow.WRAP, new LiteralSlot(0))))
    // Below four nodes the case fails as a different bug: the shrinker has to stop at four.
    val fails = (x: VarkaFuzzCase) => x.roots.asScala.headOption.map(VarkaShrinker.size) match {
      case Some(n) if n >= 4 && contains(x.roots.get(0), classOf[LastDay]) => Some(sigA)
      case Some(_) if contains(x.roots.get(0), classOf[LastDay]) => Some(sigB)
      case _ => None
    }
    val shrunk = shrink(makeCase(Seq(tree)), sigA, fails)
    assert(VarkaShrinker.size(shrunk.small.roots.get(0)) == 4, shrunk.small.roots.get(0))
  }

  test("an operand whose domain the grammar constrains is left alone") {
    val months = new ColumnRef(1)
    val tree = new AddMonths(new ColumnRef(0), months)
    val fails = (x: VarkaFuzzCase) =>
      if (x.roots.asScala.exists(contains(_, classOf[AddMonths]))) Some(sigA) else None
    val shrunk = shrink(makeCase(Seq(tree), smallOrdinal = 1), sigA, fails)
    assert(shrunk.small.roots.get(0) == tree, shrunk.small.roots.get(0))
  }

  test("the run and time budgets stop a shrink and it returns what it has") {
    val tree = new LastDay(new AddDays(new ColumnRef(0), new LiteralSlot(0)))
    val fails = (x: VarkaFuzzCase) =>
      if (x.roots.asScala.exists(contains(_, classOf[LastDay]))) Some(sigA) else None
    val shrunk = shrink(makeCase(Seq(tree)), sigA, fails, maxRuns = 2)
    assert(shrunk.stoppedEarly)
    assert(shrunk.runs <= 2)
  }

  test("a failure is grouped by kind and message with the numbers taken out") {
    val label = "seed=1 iteration=2 roots=[x]"
    def sig(m: String, t: Throwable = null): VarkaFailureSignature =
      VarkaFailureSignature.of(if (t != null) t else new RuntimeException(s"$label: $m"), label)
    assert(sig("output 0 row 7 differs (want 12)") == sig("output 1 row 3 differs (want -4)"))
    assert(sig("output 0 row 7 differs (want 12)").kind == "output mismatch")
    assert(sig("validity of output 0 row 7 differs (want None)").kind == "validity mismatch")
    assert(sig("selection row 7 differs (want true)").kind == "selection mismatch")
    assert(sig("the kernel declined the batch (status 2)").kind == "declined batch")
    assert(sig("x") != sig("output 0 row 7 differs (want 12)"))
    // A context the check decorates still classifies, and the decoration leaves the signature:
    // the other body's suffix, and the coverage row's expression the composition fuzzer adds.
    def decorated(m: String): VarkaFailureSignature =
      VarkaFailureSignature.of(new IllegalStateException(m), label)
    assert(decorated(s"$label (the other body): output 0 row 7 differs (want 12)") ==
      sig("output 0 row 7 differs (want 12)"))
    assert(VarkaFailureSignature.of(new IllegalStateException(
      "kernel of year(d) + 1: selection row 3 differs (want true, got false)"), "kernel").kind ==
      "selection mismatch")
    val linkage = new NoSuchMethodError("IntVector.add(IntVector)")
    assert(VarkaFailureSignature.of(linkage, label).kind == "generated class: NoSuchMethodError")
  }

  test("a mismatch the kernel check finds is signed by its kind, not by the exception's class " +
      "(VARKA-311)") {
    // The test above signs messages written to the classifier's taste. This one signs what a real
    // check throws: a kernel emitted for one tree, checked against another. A check failing
    // through ScalaTest's assertions, whose comparison text comes before the label, would sign
    // these "TestFailedException" and group them by nothing.
    val col = new ColumnRef(0)
    val lit = new LiteralSlot(0)
    val label = "seed=1 iteration=2 roots=[x]"
    def signOf(emitted: VarkaVectorIR, checked: VarkaVectorIR, inputs: Int): String = {
      val name = s"org.apache.spark.sql.varka.execution.VarkaSignedMismatch${inputs}" +
        s"${emitted.getClass.getSimpleName}"
      // From the matrix's base, so that under a lanes override the kernel takes the JVM's one
      // species rather than putting a second one beside it (VARKA-246).
      val bytes = VarkaLoopEmitter.emit(name, java.util.List.of(emitted), inputs, 1, null, null,
        VarkaMatrix.base)
      val batch = VarkaKernelCheck.Batch(16, Seq.fill(inputs)((_: Int) => false),
        Array.tabulate(inputs, 16)((c, i) => i * 3 + c), forceMasked = false)
      val thrown = intercept[IllegalStateException] {
        VarkaKernelCheck.runAndCompare(label, name, bytes, Seq(checked), inputs, Array(5), batch)
      }
      // What the kernel stored, and on a second line the row and the body, which the signature
      // leaves out so that a different null pattern is not a different bug.
      val lines = thrown.getMessage.linesIterator.toSeq
      assert(lines.head.contains(", got ") && lines(1).trim.startsWith("inputs ("), lines)
      assert(!VarkaFailureSignature.of(thrown, label).text.contains("inputs"))
      VarkaFailureSignature.of(thrown, label).kind
    }
    assert(signOf(new AddDays(col, lit), new SubDays(col, lit), 1) === "output mismatch")
    val c1 = new ColumnRef(1)
    assert(signOf(new Compare(CompareOp.LT, col, c1), new Compare(CompareOp.GT, col, c1), 2) ===
      "selection mismatch")
  }
}
