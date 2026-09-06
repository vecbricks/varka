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

import java.lang.management.ManagementFactory
import java.lang.ref.{ReferenceQueue, WeakReference}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.util.Utils

/**
 * Task 18: the cross-task class cache. The one failure mode the ghost fallback cannot catch is
 * a wrong hit - a cached class served for a shape it was not emitted from - so the sharing
 * tests here assert both directions: equal shapes share one loaded class (constants and all
 * other non-byte-affecting context ignored), and every byte-affecting difference - structure,
 * op kind, input count, literal count - gets its own class. The milestone-3 Metaspace gate
 * moves here too: bounded by cache capacity rather than task lifetime, proven the same way as
 * `VarkaGeneratedClassLoaderSuite`'s per-task proof - weak references, now against eviction.
 */
class VarkaShapeCacheSuite extends SparkFunSuite {

  /** Total budget (ms) for GC-retry loops; generous to stay robust on loaded JVMs. */
  private val gcTimeoutMs = 10000L

  private def columnRef = new VarkaVectorIR.ColumnRef(0)
  private def literal = new VarkaVectorIR.LiteralSlot(0)

  /** An alternating add/sub chain whose op pattern is the bit pattern of `bits`. */
  private def chain(bits: Int, depth: Int): VarkaVectorIR = {
    var node: VarkaVectorIR = columnRef
    (0 until depth).foreach { j =>
      node = if (((bits >> j) & 1) == 1) {
        new VarkaVectorIR.AddDays(node, literal)
      } else {
        new VarkaVectorIR.SubDays(node, literal)
      }
    }
    node
  }

  private def keyOf(root: VarkaVectorIR, numInputs: Int = 1, numLiterals: Int = 1) =
    new VarkaShapeKey(java.util.List.of(root), numInputs, numLiterals)

  /**
   * The parent loader `VarkaShapeCache` passes on the production path. Since task 23 the cache
   * core takes it as a value rather than reading the thread's context loader itself - the facade
   * owns every read of Spark's environment - so the tests name it here once.
   */
  private def parent: ClassLoader = Utils.getContextOrSparkClassLoader

  /** The core returns a `java.util.List`; the facade's Scala view is what these tests read. */
  private def executionsOf(cache: VarkaShapeCacheImpl, hash: String): Seq[String] =
    cache.executionsFor(hash).asScala.toSeq

  test("equal shapes share one loaded class; the second lookup is a hit") {
    val cache = new VarkaShapeCacheImpl(8)
    // Two independently built but structurally equal keys: what two queries with the same
    // shape and different constants produce, since the constants never enter the IR.
    val first = cache.getOrEmit(parent, keyOf(chain(bits = 5, depth = 3)), "execA")
    val second = cache.getOrEmit(parent, keyOf(chain(bits = 5, depth = 3)), "execB")
    assert(!first.hit && second.hit)
    assert(first.entry eq second.entry, "equal keys must share one entry")
    assert(cache.hitCount === 1 && cache.missCount === 1)
    assert(cache.size === 1)
  }

  test("task 21: a condition-root shape - the filter kernel - caches and hits like any other") {
    val cache = new VarkaShapeCacheImpl(8)
    val cond = new VarkaVectorIR.Compare(VarkaVectorIR.CompareOp.LT, columnRef, literal)
    val first = cache.getOrEmit(parent, keyOf(cond), "execFilterA")
    val second = cache.getOrEmit(parent, keyOf(cond), "execFilterB")
    assert(!first.hit && second.hit)
    assert(first.entry eq second.entry, "equal mask shapes must share one entry")
    // The mask root and the same condition inside a value root are different shapes: the
    // canonical rendering of the root differs, so a filter kernel can never be served a
    // projection's class or vice versa.
    val asValue = keyOf(new VarkaVectorIR.IfElse(cond, columnRef, columnRef))
    assert(VarkaShapeCache.shapeHash(asValue) !== VarkaShapeCache.shapeHash(keyOf(cond)))
  }

  test("every byte-affecting difference gets its own class") {
    val cache = new VarkaShapeCacheImpl(16)
    val keys = Seq(
      keyOf(new VarkaVectorIR.AddDays(columnRef, literal)),
      keyOf(new VarkaVectorIR.SubDays(columnRef, literal)),
      // Same structure, one more (unreferenced) literal slot: numLiterals changes the emitted
      // bytecode (per-slot locals, the broadcast-hoist gate), so it must miss.
      keyOf(new VarkaVectorIR.AddDays(columnRef, literal), numLiterals = 2),
      keyOf(new VarkaVectorIR.AddDays(columnRef, literal), numInputs = 2),
      // Swapped op order at depth 2: same op multiset, different structure.
      keyOf(chain(bits = 1, depth = 2)),
      keyOf(chain(bits = 2, depth = 2)))
    val entries = keys.map(cache.getOrEmit(parent, _, "exec").entry)
    assert(cache.missCount === keys.size && cache.hitCount === 0)
    assert(entries.map(_.klass).distinct.size === keys.size)
    assert(entries.map(_.shapeHash).distinct.size === keys.size)
  }

  test("the shape hash is a stable pure function and drives the naming") {
    val a = keyOf(chain(bits = 9, depth = 4))
    val b = keyOf(chain(bits = 9, depth = 4))
    assert(VarkaShapeCache.shapeHash(a) === VarkaShapeCache.shapeHash(b))
    assert(VarkaShapeCache.shapeHash(a) !== VarkaShapeCache.shapeHash(keyOf(chain(6, 4))))
    val entry = new VarkaShapeCacheImpl(2).getOrEmit(parent, a, "exec").entry
    val hash = VarkaShapeCache.shapeHash(a)
    assert(hash.matches("[0-9a-f]{16}"), hash)
    assert(entry.shapeHash === hash)
    assert(entry.className === s"org.apache.spark.sql.varka.execution.VarkaFusedProjection_$hash")
    assert(entry.sourceFile === s"VarkaFusedProjection_$hash.java")
  }

  test("concurrent lookups of one shape emit once and share the class") {
    val cache = new VarkaShapeCacheImpl(8)
    val threads = 8
    val key = keyOf(chain(bits = 3, depth = 5))
    val start = new CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(threads)
    try {
      val futures = (0 until threads).map { _ =>
        pool.submit(new java.util.concurrent.Callable[Class[_]] {
          override def call(): Class[_] = {
            start.await()
            cache.getOrEmit(parent, keyOf(chain(bits = 3, depth = 5)), "exec").entry.klass
          }
        })
      }
      start.countDown()
      val classes = futures.map(_.get(30, TimeUnit.SECONDS))
      assert(classes.forall(_ eq classes.head), "racing lookups must share one class")
      assert(cache.missCount === 1, s"exactly one thread must emit, got ${cache.missCount}")
      assert(cache.hitCount === threads - 1)
    } finally {
      pool.shutdownNow()
    }
    assert(cache.getOrEmit(parent, key, "exec").hit)
  }

  test("a failed emit is not shared: every racing caller fails for its own reason") {
    val cache = new VarkaShapeCacheImpl(8)
    val threads = 6
    val start = new CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(threads)
    val thrown =
      try {
        val futures = (0 until threads).map { _ =>
          pool.submit(new java.util.concurrent.Callable[Throwable] {
            override def call(): Throwable = {
              start.await()
              // numInputs = 0 is outside what the emitter serves, so emitting this shape always
              // throws - a deterministic failure that needs no hook in the production path.
              val failing = new VarkaShapeKey(java.util.List.of(chain(bits = 1, depth = 2)), 0, 1)
              intercept[IllegalArgumentException] {
                cache.getOrEmit(parent, failing, "exec")
              }
            }
          })
        }
        start.countDown()
        futures.map(_.get(30, TimeUnit.SECONDS))
      } finally {
        pool.shutdownNow()
      }
    // SPARK-43300's property, asserted directly: `Throwable` does not override `equals`, so
    // distinct elements are distinct instances. A caller that inherited the winner's failure
    // would hold the winner's very Throwable - which is what would turn one task's cancelled
    // emit into unrelated tasks' ghost fallbacks.
    assert(thrown.distinct.size === threads,
      "each caller must fail with its own exception, never a co-waiter's")
    assert(thrown.forall(_.getMessage.contains("numInputs")))
    // A failure caches nothing and counts as neither a hit nor a miss.
    assert(cache.hitCount === 0 && cache.missCount === 0)
  }

  test("eviction releases the evicted loader, and the class unloads once unreferenced") {
    val cache = new VarkaShapeCacheImpl(2)
    val queue = new ReferenceQueue[ClassLoader]()
    val evictedRef = emitForEviction(cache, queue)
    // Two more shapes evict the first (LRU capacity 2).
    cache.getOrEmit(parent, keyOf(chain(bits = 1, depth = 1)), "exec")
    cache.getOrEmit(parent, keyOf(chain(bits = 0, depth = 2)), "exec")
    assert(cache.size <= 2)
    // The removal listener released the loader; with the entry unreferenced the loader (and
    // so its class) must now be collectable - the milestone-3 form of the Metaspace proof.
    assert(awaitCollected(evictedRef, queue),
      "the evicted loader must be collected so its class unloads from Metaspace")
  }

  /**
   * Emits the to-be-evicted shape in its own method frame and hands back only a weak
   * reference to its loader: a block-scoped val in the test body can stay live in the test
   * method's local slots and pin the entry, so nothing would ever unload.
   */
  private def emitForEviction(
      cache: VarkaShapeCacheImpl,
      queue: ReferenceQueue[ClassLoader]): WeakReference[ClassLoader] = {
    val entry = cache.getOrEmit(parent, keyOf(chain(bits = 0, depth = 1)), "exec").entry
    assert(!entry.loader.isReleased)
    new WeakReference[ClassLoader](entry.loader, queue)
  }

  test("a 10k-distinct-shape stress stays at capacity, and every evicted loader collects") {
    val capacity = 64
    val shapes = 10000
    val cache = new VarkaShapeCacheImpl(capacity)
    val queue = new ReferenceQueue[ClassLoader]()
    val before = metaspaceUsed()
    val refs = (0 until shapes).map { i =>
      // Depth 14 gives 16384 distinct op patterns, so every index is its own shape.
      val entry = cache.getOrEmit(parent, keyOf(chain(bits = i, depth = 14)), "stress").entry
      new WeakReference[ClassLoader](entry.loader, queue)
    }
    assert(cache.size <= capacity)
    assert(cache.missCount === shapes)
    val collected = awaitCollectedCount(refs, queue, refs.size - capacity)
    val after = metaspaceUsed()
    logInfo(s"shape stress: shapes=$shapes capacity=$capacity collected=$collected " +
      s"metaspace before=$before after=$after")
    assert(collected >= refs.size - capacity, "every evicted loader must be collected")
    // Lenient, like the integration check: at most `capacity` live kernel classes (a few KB
    // each) must keep the Metaspace footprint far below this bound.
    assert(after - before < 64L * 1024 * 1024,
      s"Metaspace grew by ${after - before} bytes across $shapes shapes")
  }

  test("capacity 0 disables sharing: every lookup emits, evicted and released on load") {
    val cache = new VarkaShapeCacheImpl(0)
    val key = keyOf(chain(bits = 5, depth = 3))
    val first = cache.getOrEmit(parent, key, "exec")
    val second = cache.getOrEmit(parent, key, "exec")
    assert(!first.hit && !second.hit)
    assert(!(first.entry eq second.entry))
    assert(cache.size === 0 && cache.missCount === 2)
    // The single cache path degenerates to the pre-task-18 lifecycle: `maximumSize(0)` evicts
    // each entry as it loads and the removal listener releases its loader - a caller's strong
    // references (here, the lookup results) keep the class usable to task end regardless.
    assert(first.entry.loader.isReleased && second.entry.loader.isReleased)
    assert(first.entry.newKernel() != null)
    // Diagnostics still record while sharing is off: with the bytes carrying only the shape,
    // the side table is the one place the execution identity lives.
    assert(executionsOf(cache, first.entry.shapeHash) === Seq("exec"))
  }

  test("emit options are part of the key: a variant gets its own entry and its own name") {
    val cache = new VarkaShapeCacheImpl(4)
    val shape = chain(bits = 3, depth = 2)
    val plain = keyOf(shape)
    val noCse =
      new VarkaShapeKey(java.util.List.of(shape), 1, 1, VarkaEmitOptions.DEFAULTS.withCse(false))
    // Before task 23 this pair could not coexist. The emitter's non-shape inputs were static
    // hooks the key could not see, so the cache refused every lookup - hit and miss alike -
    // while any of them was set. They are a key component now, so the variant simply misses.
    val first = cache.getOrEmit(parent, plain, "exec")
    val variant = cache.getOrEmit(parent, noCse, "exec")
    assert(!first.hit && !variant.hit)
    assert(!(first.entry eq variant.entry))
    assert(first.entry.shapeHash !== variant.entry.shapeHash,
      "a variant must get its own name, or the two merge in the execution side table")
    assert(cache.size === 2)
    // And both keep serving: neither poisons the other, which is what the gate was for.
    assert(cache.getOrEmit(parent, plain, "exec").hit)
    assert(cache.getOrEmit(parent, noCse, "exec").hit)
  }

  test("the default options are the ones the three-argument key supplies, and render to none") {
    val shape = chain(bits = 3, depth = 2)
    val explicit = new VarkaShapeKey(java.util.List.of(shape), 1, 1, VarkaEmitOptions.DEFAULTS)
    // The convenience constructor every production caller uses must mean exactly DEFAULTS, and
    // DEFAULTS must contribute nothing to the hash - that is what keeps the two committed
    // hashes below a valid oracle for the task-23 migration.
    assert(keyOf(shape) === explicit)
    assert(VarkaEmitOptions.DEFAULTS.canonical() === "")
    assert(VarkaEmitOptions.DEFAULTS.withCse(false).canonical().nonEmpty)
  }

  test("task 46: every option component can change the canonical rendering") {
    // `truncDate` was left out of canonical() from task 35 until task 46, so two option values
    // differing only in the trunc lowering rendered the same string: different keys in the
    // cache's map, one shared execution identity in the side table keyed on the hash, which is
    // the collision the record's class doc says must not exist. Nothing failed, because nothing
    // looked - so this walks the record's components and holds each one to it. It reflects
    // rather than listing names on purpose: a list would have to be updated by the same person
    // who forgot the field.
    val defaults = VarkaEmitOptions.DEFAULTS
    val components = classOf[VarkaEmitOptions].getRecordComponents
    assert(components.length >= 13, "components were removed; re-read this test's reason")

    def otherValue(component: java.lang.reflect.RecordComponent): AnyRef = {
      val current = component.getAccessor.invoke(defaults)
      component.getType match {
        case t if t == classOf[Boolean] || t == java.lang.Boolean.TYPE =>
          java.lang.Boolean.valueOf(!current.asInstanceOf[java.lang.Boolean])
        case t if t == classOf[Int] || t == java.lang.Integer.TYPE =>
          // lanesOverride must stay a power of two, and groupBudget positive; doubling is both.
          val cur = current.asInstanceOf[java.lang.Integer]
          java.lang.Integer.valueOf(if (cur == 0) 4 else cur * 2)
        case t if t.isEnum =>
          t.getEnumConstants.find(_ != current)
            .getOrElse(fail(s"${component.getName} has one enum constant"))
        case t => fail(s"${component.getName}: no other value known for $t")
      }
    }

    def build(values: Array[AnyRef]): VarkaEmitOptions =
      classOf[VarkaEmitOptions].getConstructors.head
        .newInstance(values: _*).asInstanceOf[VarkaEmitOptions]

    // Every component pinned away from its default before any pair is compared, so neither
    // instance below can take canonical()'s isDefault() shortcut to "" - otherwise reverting
    // just one field to its default would render DEFAULTS' empty string regardless of whether
    // that field reaches canonical() at all, which is exactly the bug this test exists to
    // catch (validityOrFirst repeated it, missing from canonical() despite this test passing).
    val allOther = components.map(otherValue)
    val everythingOther = build(allOther)
    for ((component, i) <- components.zipWithIndex) {
      val thisOneAtDefault = build(allOther.updated(i, component.getAccessor.invoke(defaults)))
      assert(everythingOther.canonical() !== thisOneAtDefault.canonical(),
        s"${component.getName} does not reach canonical(), so two variants share a shape hash")
    }
  }

  test("newKernel unwraps the reflective wrapper, so a fatal constructor error stays fatal") {
    // Constructor.newInstance wraps whatever the constructor body throws in an
    // InvocationTargetException, which is itself NonFatal - so rethrowing the wrapper would
    // make the evaluator's isCatchable test say "ordinary kernel failure" about an error that
    // must fail the task. The entry unwraps for exactly this case.
    def entryFor(klass: Class[_]): VarkaShapeEntry =
      new VarkaShapeEntry(new VarkaGeneratedClassLoader(getClass.getClassLoader), klass,
        Array.emptyByteArray, "0123456789abcdef", klass.getConstructor())
    val fatal = intercept[OutOfMemoryError](entryFor(classOf[FatalKernel]).newKernel())
    assert(fatal.getMessage === "injected")
    val ordinary = intercept[IllegalStateException](entryFor(classOf[FailingKernel]).newKernel())
    assert(ordinary.getMessage === "injected")
  }

  test("the parent class loader is part of the key: another loader gets its own entry") {
    val cache = new VarkaShapeCacheImpl(8)
    val key = keyOf(chain(bits = 5, depth = 3))
    // Since task 23 the parent is an argument rather than something the cache reads off the
    // thread, so the two linkage contexts are named here directly. `VarkaShapeCache` is what
    // binds it to `Utils.getContextOrSparkClassLoader` on the production path.
    val isolated = new java.net.URLClassLoader(Array.empty, parent)
    val first = cache.getOrEmit(parent, key, "sessionA")
    val second = cache.getOrEmit(isolated, key, "sessionB")
    // Same shape, different linkage context: no sharing across loaders (a class linked
    // through one session's chain must not serve another), same shape identity outward.
    assert(!second.hit)
    assert(!(first.entry eq second.entry))
    assert(second.entry.loader.getParent eq isolated)
    assert(first.entry.shapeHash === second.entry.shapeHash)
    assert(cache.size === 2)
    // The original loader's entry still hits for the original context.
    assert(cache.getOrEmit(parent, key, "sessionC").hit)
  }

  test("task 22: JFR events cover emission and lookups, joined by the shape hash") {
    val cache = new VarkaShapeCacheImpl(8)
    val key = keyOf(chain(bits = 11, depth = 4))
    val hash = VarkaShapeCache.shapeHash(key)
    // The second lookup's identity overshoots the side-table bound, so this also pins the
    // task-21 review fix: the event must carry the same abbreviated string the side table
    // stores, or the advertised join between the two never matches.
    val longIdentity = "jfr-exec-b-" + ("x" * (VarkaShapeCache.maxExecutionIdentityLength * 2))
    val (_, recorded) = VarkaJfrTestSupport.withJfrRecording(
      classOf[VarkaEmissionEvent], classOf[VarkaCacheLookupEvent]) {
      assert(!cache.getOrEmit(parent, key, "jfr-exec-a").hit)
      assert(cache.getOrEmit(parent, key, longIdentity).hit)
    }
    // The recording sees every cache in the JVM (suites share it): filter by this test's
    // shape hash, never count globally.
    val events = recorded.filter(e => e.hasField("shapeHash") && e.getString("shapeHash") == hash)
    val emissions = events.filter(VarkaJfrTestSupport.isEvent(_, classOf[VarkaEmissionEvent]))
    val lookups = events.filter(VarkaJfrTestSupport.isEvent(_, classOf[VarkaCacheLookupEvent]))
    assert(emissions.size === 1, events.mkString("; "))
    assert(emissions.head.getInt("byteCount") > 0)
    assert(emissions.head.getString("className") === VarkaShapeCache.classNameFor(hash))
    assert(lookups.size === 2)
    assert(lookups.count(_.getBoolean("hit")) === 1)
    val executions = lookups.map(_.getString("execution")).toSet
    assert(executions.contains("jfr-exec-a"))
    val bounded = (executions - "jfr-exec-a").head
    assert(bounded.length <= VarkaShapeCache.maxExecutionIdentityLength,
      "the lookup event must carry the bounded identity, not the raw one")
    assert(executionsOf(cache, hash).contains(bounded),
      "the event's identity must equal the side table's entry, or the join breaks")
  }

  test("the canonical rendering pins the hash: the committed value never drifts") {
    // SHA-256 over VarkaVectorIR.canonical, not Record.toString - this exact value must
    // hold on every JVM and JDK release, or cluster-wide diagnostics joins break. If this
    // fails, the canonical rendering changed, which renames every dumped class: make sure
    // that is intended, then update the value here and say so in the task plan.
    val key = keyOf(chain(bits = 9, depth = 4))
    assert(VarkaShapeCache.shapeHash(key) === "586434f9b9739c40")
  }

  test("every node type's canonical rendering is pinned, not only the chain ops") {
    // One key that uses all 24 IR node types (and three CompareOps), so a rendering change
    // to any of them - operand order, a token - fails here even though the chain-based
    // pinned hash above would still pass. Same update rule as above when intended. Task 20
    // added IsNotNull and re-pinned the value (recorded in PLAN_TASK_20.md); task 26 added
    // the four calendar extractions and re-pinned it again (PLAN_TASK_26.md); task 33 added
    // NextDay, task 40 added AddMonths, task 36 added LastDay, task 34 added DayOfYear and
    // task 35 added TruncDate and task 61 added TruncDateDynamic, each re-pinning it again
    // (PLAN_TASK_33.md, PLAN_TASK_40.md, PLAN_TASK_36.md, PLAN_TASK_34.md, PLAN_TASK_35.md,
    // PLAN_TASK_61.md). This value is re-pinned from the
    // failing assertion's own output on every such change - never carried over from either
    // side of a merge, since a hash that is right for one node set is wrong for the union of
    // two.
    import VarkaVectorIR._
    val cond = new And(
      new Or(
        new Compare(CompareOp.LT, columnRef, literal),
        new Not(new Compare(CompareOp.EQ, columnRef, literal))),
      new And(new Compare(CompareOp.GE, columnRef, literal), new IsNotNull(columnRef)))
    val chrono = new Greatest(
      new Least(
        new Greatest(new Year(columnRef), new Month(columnRef)),
        new Greatest(new DayOfMonth(columnRef),
          new Least(new Quarter(columnRef),
            new Least(new LastDay(columnRef), new TruncDate(columnRef, TruncLevel.YEAR))))),
      new Greatest(new DayOfYear(columnRef), new WeekOfYear(new ThursdayOf(columnRef))))
    val everyNode = new IfElse(
      cond,
      new Greatest(new AddDays(columnRef, literal), new SubDays(columnRef, literal)),
      new Least(new DateDiff(chrono, new DayOfWeek(columnRef)),
        new Least(new Least(new WeekDay(columnRef), new DayOfWeekIso(columnRef)),
          new Least(new NextDay(columnRef, literal),
            new Least(new AddMonths(columnRef, literal),
              new Least(new TruncDateDynamic(columnRef, columnRef),
                new Least(new MakeDate(columnRef, literal, literal, false),
                  new MakeDate(columnRef, literal, literal, true))))))))
    assert(VarkaShapeCache.shapeHash(keyOf(everyNode)) === "1661b1b146818e6a")
  }

  test("side-table identities are recorded truncated, so one entry cannot grow unbounded") {
    val cache = new VarkaShapeCacheImpl(4)
    val key = keyOf(chain(bits = 6, depth = 3))
    val longIdentity = "Varka_Project_Stage1: " + ("x" * 1000)
    cache.getOrEmit(parent, key, longIdentity)
    val recorded = executionsOf(cache, cache.getOrEmit(parent, key, "short").entry.shapeHash)
    assert(recorded.exists(_.endsWith("...")), recorded.mkString("; "))
    assert(recorded.forall(_.length < 300), "identities must be bounded")
    assert(recorded.contains("short"))
  }

  test("the side table joins a shape hash back to its recorded executions, bounded") {
    val cache = new VarkaShapeCacheImpl(8)
    val key = keyOf(chain(bits = 7, depth = 3))
    val hash = VarkaShapeCache.shapeHash(key)
    cache.getOrEmit(parent, key, "Varka_Project_Stage3: date_add(d#1, 3) AS a#2")
    cache.getOrEmit(parent, key, "Varka_ProjectToRow_Stage4: date_add(d#5, 9) AS b#6")
    val recorded = executionsOf(cache, hash)
    assert(recorded === Seq(
      "Varka_Project_Stage3: date_add(d#1, 3) AS a#2",
      "Varka_ProjectToRow_Stage4: date_add(d#5, 9) AS b#6"))
    // Bounded per shape: the oldest identities fall off, most recent kept in order.
    (0 until 12).foreach(i => cache.getOrEmit(parent, key, s"exec$i"))
    val bounded = executionsOf(cache, hash)
    assert(bounded.size === 8)
    assert(bounded.last === "exec11" && bounded.head === "exec4")
    assert(executionsOf(cache, "no such hash") === Seq.empty)
  }

  // --- helpers -------------------------------------------------------------

  private def metaspaceUsed(): Long = {
    ManagementFactory.getMemoryPoolMXBeans.asScala.collect {
      case p if p.getName == "Metaspace" || p.getName == "Compressed Class Space" =>
        p.getUsage.getUsed
    }.sum
  }

  /** Retries `System.gc()` until `ref` is enqueued or the timeout elapses. */
  private def awaitCollected(ref: WeakReference[_], queue: ReferenceQueue[_]): Boolean = {
    val deadline = System.nanoTime() + gcTimeoutMs * 1000000L
    while (System.nanoTime() < deadline) {
      if (queue.poll() eq ref) {
        return true
      }
      System.gc()
      System.runFinalization()
      Thread.sleep(25)
    }
    queue.poll() eq ref
  }

  /**
   * Retries `System.gc()` until at least `expected` references are enqueued or the timeout
   * elapses, returning how many were collected.
   */
  private def awaitCollectedCount(
      refs: Seq[WeakReference[ClassLoader]],
      queue: ReferenceQueue[ClassLoader],
      expected: Int): Int = {
    val deadline = System.nanoTime() + gcTimeoutMs * 1000000L
    var collected = 0
    while (collected < expected && System.nanoTime() < deadline) {
      while (queue.poll() != null) {
        collected += 1
      }
      System.gc()
      System.runFinalization()
      Thread.sleep(25)
    }
    while (queue.poll() != null) {
      collected += 1
    }
    collected
  }
}

/** A kernel whose constructor raises a fatal error; see the unwrap test above. */
private class FatalKernel extends VarkaFusedKernel {
  // scalastyle:off throwerror
  throw new OutOfMemoryError("injected")
  // scalastyle:on throwerror
  override def run(srcData: Array[Long], srcValidity: Array[Long], srcNullCount: Array[Int],
      dstData: Array[Long], dstValidity: Array[Long], scalarArgs: Array[Int], length: Int): Int =
    0
}

/** A kernel whose constructor raises an ordinary, catchable failure. */
private class FailingKernel extends VarkaFusedKernel {
  throw new IllegalStateException("injected")
  override def run(srcData: Array[Long], srcValidity: Array[Long], srcNullCount: Array[Int],
      dstData: Array[Long], dstValidity: Array[Long], scalarArgs: Array[Int], length: Int): Int =
    0
}
