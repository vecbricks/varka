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

import java.lang.foreign.{Arena, ValueLayout}

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader

/**
 * VARKA-50: the compiled-size watch.
 *
 * Everything here except the last case runs without JFR compiling anything, by driving
 * `VarkaCompilationWatch.record` directly - the seam the JFR handler calls once it has read the
 * event's fields. That split is deliberate: the parts that can be wrong in an interesting way
 * are the key and the threshold, and neither needs a running compiler to check, while a test
 * that waits for C2 is exactly the kind that goes flaky on a loaded runner.
 */
class VarkaCompilationWatchSuite extends SparkFunSuite with VarkaTestWatchdog {

  private val prefix = VarkaShapeCacheImpl.CLASS_NAME_PREFIX
  private val shapeA = "0123456789abcdef"
  private val shapeB = "fedcba9876543210"
  private def classOf(shape: String): String = prefix + shape

  test("the key names the method and the tier, not just the shape") {
    // The correction in VARKA-50.md 2.1. A shape emits run, runDense, loopDense0,
    // epilogueMasked and more, whose compiled sizes differ by an order of magnitude - measured
    // on a probe at 576 bytes against 10552 for two methods of one class. Keyed on the shape
    // alone, the second method compiled would be reported as a divergence and the detector
    // would fire constantly on a healthy JVM.
    val a = VarkaCompilationWatch.keyFor(classOf(shapeA), "loopDense0", 4)
    assert(a !== VarkaCompilationWatch.keyFor(classOf(shapeA), "epilogueMasked", 4),
      "two methods of one shape must not share a key")
    assert(a !== VarkaCompilationWatch.keyFor(classOf(shapeA), "loopDense0", 3),
      "two tiers of one method must not share a key - the probe measured 576 bytes at tier 3 " +
        "against 696 at tier 4 for the same method")
    assert(a !== VarkaCompilationWatch.keyFor(classOf(shapeB), "loopDense0", 4),
      "two shapes must not share a key")
    assert(a === VarkaCompilationWatch.keyFor(classOf(shapeA), "loopDense0", 4),
      "the same method, shape and tier must key the same")
  }

  test("a class that is not a generated kernel is not keyed") {
    // The filter that keeps jdk.Compilation's firehose out. Every method the JVM compiles
    // reaches the handler; only Varka's generated classes may get past this.
    assert(VarkaCompilationWatch.keyFor("java.util.HashMap", "put", 4) === null)
    assert(VarkaCompilationWatch.keyFor(
      "org.apache.spark.sql.varka.vector.ChronoVectorOps", "vectorFourFields", 4) === null,
      "the hand-written kernels are not shape-keyed, so they have no baseline to compare against")
    assert(VarkaCompilationWatch.keyFor(prefix, "run", 4) === null,
      "the prefix with no shape hash after it is not a kernel class")
    assert(VarkaCompilationWatch.keyFor(null, "run", 4) === null)
    assert(VarkaCompilationWatch.keyFor(classOf(shapeA), null, 4) === null)
  }

  test("the first compilation is a baseline, not a divergence") {
    val watch = VarkaCompilationWatch.inert()
    watch.record(classOf(shapeA), "loopDense0", 4, 1600L)
    assert(watch.divergenceCount() === 0, "the first size seen has nothing to be compared with")
    assert(watch.observedCount() === 1)
  }

  test("a size within the threshold is not reported, one beyond it is") {
    val watch = VarkaCompilationWatch.inert()
    watch.record(classOf(shapeA), "loopDense0", 4, 1600L)
    watch.record(classOf(shapeA), "loopDense0", 4, 1700L)
    assert(watch.divergenceCount() === 0,
      s"a 6% difference is inside the ${VarkaCompilationWatch.DIVERGENCE_RATIO} threshold")
    // VARKA-32's actual failure: 1581 instructions against 3000, about 2x, and worth 30-40%.
    watch.record(classOf(shapeA), "loopDense0", 4, 3000L)
    assert(watch.divergenceCount() === 1, "a 2x difference is the case this exists to catch")
  }

  test("a smaller later compilation counts too") {
    // The baseline is whichever came first, and the bad allocation is not guaranteed to be it -
    // VARKA-32 saw the fast outcome 4 times in 21 runs, so the first compilation of a JVM is more
    // likely to be the slow one. The comparison is on absolute difference for that reason.
    val watch = VarkaCompilationWatch.inert()
    watch.record(classOf(shapeA), "loopDense0", 4, 3000L)
    watch.record(classOf(shapeA), "loopDense0", 4, 1581L)
    assert(watch.divergenceCount() === 1)
  }

  test("a second method of the same shape establishes its own baseline") {
    // The regression VARKA-50.md 2.1 exists to prevent, asserted rather than argued.
    val watch = VarkaCompilationWatch.inert()
    watch.record(classOf(shapeA), "run", 4, 271L)
    watch.record(classOf(shapeA), "loopDense0", 4, 3000L)
    watch.record(classOf(shapeA), "epilogueMasked", 4, 9000L)
    assert(watch.divergenceCount() === 0,
      "three methods of one shape, all first compilations - nothing has diverged")
    assert(watch.observedCount() === 3)
  }

  test("a repeatedly diverging method is counted every time and logged once") {
    val watch = VarkaCompilationWatch.inert()
    watch.record(classOf(shapeA), "loopDense0", 4, 1600L)
    (1 to 5).foreach(_ => watch.record(classOf(shapeA), "loopDense0", 4, 3000L))
    assert(watch.divergenceCount() === 5,
      "the counter keeps counting; it is the log that speaks once, so a recompiling loop " +
        "cannot flood it")
  }

  test("a nonsense size is ignored rather than treated as a baseline") {
    val watch = VarkaCompilationWatch.inert()
    watch.record(classOf(shapeA), "loopDense0", 4, 0L)
    watch.record(classOf(shapeA), "loopDense0", 4, 1600L)
    watch.record(classOf(shapeA), "loopDense0", 4, 1650L)
    assert(watch.divergenceCount() === 0,
      "a zero size must not become the baseline every later compilation is measured against")
  }

  test("the watch JFR could not start is inert and safe") {
    // What VarkaCompilationWatch.start() returns when the stream cannot be opened - JFR
    // disabled, or a stripped runtime. A diagnostic must never be why a JVM fails to start, so
    // every method has to stay callable.
    val watch = VarkaCompilationWatch.inert()
    assert(!watch.isRunning())
    watch.record(classOf(shapeA), "loopDense0", 4, 1600L)
    watch.record(classOf(shapeA), "loopDense0", 4, 3000L)
    assert(watch.divergenceCount() === 1, "the decision path works with no stream behind it")
    watch.close()
  }

  test("the watch is off unless it is asked for") {
    // The default, and the whole cost of this feature to anyone who has not enabled it: no
    // stream, no thread, no map. Read through the singleton rather than the class, because
    // "off" is a decision VarkaShapeCache makes, not one the watch makes about itself.
    assert(!VarkaShapeCache.compilationWatchRunning(),
      "spark.sql.codegen.varka.compilationWatch.enabled defaults to false")
    assert(VarkaShapeCache.compilationDivergences() === 0)
  }

  // --- The end-to-end case, and the measurement behind the threshold ---------------------------

  /**
   * Emits one kernel under a production-shaped class name, loads it, and runs it hot enough for
   * C2 to compile its methods, with `watch` subscribed throughout. Returns what the watch saw.
   *
   * The class name matters: the watch's filter is the generated-class prefix, so a kernel named
   * anything else is invisible to it and this test would pass by seeing nothing. It is built
   * through `VarkaShapeCacheImpl.classNameFor`, the same call production uses.
   */
  private def runKernelHot(watch: VarkaCompilationWatch, shapeHash: String): Unit = {
    val className = VarkaShapeCacheImpl.classNameFor(shapeHash)
    val col = new VarkaVectorIR.ColumnRef(0)
    val outputs = java.util.List.of[VarkaVectorIR](new VarkaVectorIR.Year(col))
    val bytes = VarkaLoopEmitter.emit(
      className, outputs, 1, 0, null, null, VarkaEmitOptions.DEFAULTS)
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    loader.defineGeneratedClass(className, bytes)
    val kernel = loader.loadClass(className).getConstructor().newInstance()
      .asInstanceOf[VarkaFusedKernel]

    val rows = 1024
    val arena = Arena.ofConfined()
    try {
      val src = arena.allocate(rows * 4L, 64)
      var i = 0
      while (i < rows) {
        src.set(ValueLayout.JAVA_INT, i * 4L, 18000 + i)
        i += 1
      }
      val srcValidity = arena.allocate((rows + 7) / 8L, 8)
      srcValidity.fill(0xFF.toByte)
      val dstData = arena.allocate(rows * 4L, 64)
      val dstValidity = arena.allocate((rows + 7) / 8L, 8)
      var r = 0
      while (r < 50000) {
        kernel.run(Array(src.address()), Array(srcValidity.address()), Array(0),
          Array(dstData.address()), Array(dstValidity.address()), Array.empty[Int], rows)
        r += 1
      }
    } finally {
      arena.close()
    }
  }

  test("a real recording stream sees Varka kernel compilations " +
      "(opt-in: -Dvarka.jfr=true)") {
    // Opt-in because it waits for C2, and a test that waits for a compiler is exactly the kind
    // that goes flaky on a loaded runner. Everything this suite actually decides is checked
    // above without JFR; this case checks the wiring between the two.
    assume(System.getProperty("varka.jfr") == "true",
      "set -Dvarka.jfr=true to run the end-to-end JFR case")
    val watch = VarkaCompilationWatch.start()
    try {
      assert(watch.isRunning(), "the stream did not open; JFR may be disabled in this JVM")
      runKernelHot(watch, "00000000deadbeef")
      val deadline = System.nanoTime() + 30L * 1000 * 1000 * 1000
      while (watch.observedCount() == 0 && System.nanoTime() < deadline) {
        Thread.sleep(200)
      }
      assert(watch.observedCount() > 0,
        "no Varka kernel compilation reached the watch within 30s - either the class-name " +
          "filter no longer matches what the emitter names its classes, or nothing compiled")
      // The measurement behind DIVERGENCE_RATIO: these are the sizes a healthy JVM produces for
      // this shape. VARKA-50.md section 3 compares them across runs.
      watch.baselines().asScala.toSeq.sortBy(_._1).foreach { case (key, size) =>
        logInfo(s"VARKA_CODESIZE $key = $size")
      }
      assert(watch.divergenceCount() === 0,
        "a healthy JVM compiling one shape must not report a divergence")
    } finally {
      watch.close()
    }
  }

  test("a re-emitted shape compiles twice under one key " +
      "(opt-in: -Dvarka.jfr=true)") {
    // The case that decides whether this feature can ever fire, and it is not the obvious one.
    // VARKA-32's bimodality was *between* JVM runs - "stdev 0 inside a run, 42% between runs" -
    // and a per-JVM baseline cannot see that: measured over three JVMs, every key was compiled
    // exactly once and the sizes were byte-identical, so there was nothing to compare.
    //
    // What produces two compilations of one key inside a JVM is re-emission: the same shape
    // emitted into a fresh class of the *same name* under a different loader, which is what the
    // shape cache does at maxEntries = 0 and after an eviction. The class name is what JFR
    // reports, so both compilations land on the same key - and the allocator gets a fresh roll
    // for the second, which is precisely the event worth noticing.
    assume(System.getProperty("varka.jfr") == "true",
      "set -Dvarka.jfr=true to run the end-to-end JFR case")
    val watch = VarkaCompilationWatch.start()
    try {
      assert(watch.isRunning())
      runKernelHot(watch, "00000000cafebabe")
      runKernelHot(watch, "00000000cafebabe")
      val deadline = System.nanoTime() + 30L * 1000 * 1000 * 1000
      while (watch.observedCount() < 2 && System.nanoTime() < deadline) {
        Thread.sleep(200)
      }
      logInfo(s"VARKA_REEMIT observed=${watch.observedCount()} " +
        s"divergences=${watch.divergenceCount()} keys=${watch.baselines().size()}")
      watch.baselines().asScala.toSeq.sortBy(_._1).foreach { case (key, size) =>
        logInfo(s"VARKA_REEMIT_SIZE $key = $size")
      }
      assert(watch.observedCount() >= 2,
        "two emissions of one shape should produce at least two kernel compilations")
    } finally {
      watch.close()
    }
  }
}
