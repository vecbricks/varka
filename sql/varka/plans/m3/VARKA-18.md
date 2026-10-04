# VARKA-18: cross-task class reuse

**Status: DONE** - the outcome is section 6. The plan below is as written
before the work; where it deviated, section 6 says so.

## 1. Why

Every Varka task today emits and defines a fresh `VarkaFusedProjection` class,
so HotSpot re-runs the whole tier ladder per task - interpreter, C1 with boxed
vectors, C2 OSR - a fixed 13-50 ms per task that grows with the loop method's
op count (`VARKA-14.md` 7.5). It is the dominant term behind the committed
depth-curve erosion (2.2x at depth 1 down to 1.3x at depth 8 in
`VarkaThroughputBenchmark`) and most of what `dayofweek` still pays after the
magic-multiply lowering. VARKA-14 also proved the fix cannot be a byte cache: a
re-defined class is a new class to the JVM and re-pays the ladder, so only
reusing the **loaded class** amortises it. This task is `m3/PLAN.md`
2.1, and its numbers gate is stated there: the committed `dayofweek` and
depth-8 columnar cases lose the per-task surcharge (depth 8 back above 2x),
and a 10k-distinct-shape stress keeps Metaspace bounded with eviction proven
by weak reference.

Two questions the milestone left open are settled here with the project owner:

* **Cache scope** (milestone open question 1): per-JVM, executor-wide. The
  shape key is purely structural - IR records, input count, literal count - so
  it carries no session state and cross-session sharing is safe by
  construction; Janino's codegen cache is the precedent.
* **Docs timing**: this task regenerates the affected committed results files
  (its gate needs them) and requotes the docs from that same run, per the
  one-run discipline; VARKA-19 regenerates again from its own run. The
  milestone's "once 18 and 19 land" reading loses to "docs never contradict
  committed files".

## 2. Design

### 2.1 The key: exactly the byte-affecting emit inputs

The emitted bytes are a pure function of `(outputs, numInputs, numLiterals)` -
the first four parameters of `VarkaLoopEmitter.emit` minus the name - plus the
name and debug strings the cache itself derives. So the key is

    VarkaShapeKey(outputs: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int)

with structural equality for free: the IR nodes are Java records, literal
slots and column refs carry dense first-occurrence indices and never values
(the property `VARKA-10.md` built for exactly this key, pinned by
`VarkaExpressionCompilerSuite`). Two queries with the same shape and different
constants hit one class; the constants travel as runtime `scalarArgs`.

**Deviation from the milestone 2.1 wording, recorded here:** the spec lists
"input ordinals ... lane and output types" as key components. Neither affects
the bytes - `ColumnRef` carries the dense kernel input index (the child plan
ordinals live only in `CompiledVarkaProjection.inputOrdinals`, bound per task
by the evaluator), and `outputTypes` never reaches the emitter. Leaving them
out is safe by the same argument that makes literal values safe, and raises
the hit rate: `date_add(a, 1)` and `date_add(b, 2)` share one class. Lane
types need no separate component because they ride the IR records themselves.
`numLiterals` stays in the key independently of the IR: it changes the emitted
bytecode even for unreferenced slots (per-slot locals, and the
broadcast-hoist regime gate).

### 2.2 The cache: a bounded loader/class LRU, one loader per shape

`VarkaShapeCache` (catalyst, `codegen/varka/` - no Class-File API type in
sight, so Scala is fine under the house rule): a JVM-wide Guava cache,
`maximumSize` from the new static conf, with a removal listener that calls
`release()` on the evicted entry's `VarkaGeneratedClassLoader`. One loader per
entry, one class per loader, so eviction keeps the unload granularity the
Metaspace proof relies on. Lookups go through `get(key, callable)`, so tasks
racing on one shape emit once.

* **Eviction is safe mid-flight.** `release()` only clears the loader's
  registry and blocks new defines; a running task's strong references to the
  kernel instance and its `Class` keep them alive until the task drops them -
  the owner-side unload contract the engine's `VarkaClassLoader` javadoc has
  documented since VARKA-3. Metaspace is now bounded by cache size instead of
  task lifetime - a weaker guarantee than milestone 1's, proven the same way
  (weak references, now against eviction).
* **Fate sharing.** Spark's `NonFateSharingCache` exists because a task
  cancelled while populating an entry fails other waiters; it cannot be used
  here (no removal listener), and it is not needed: any failure out of the
  cache lookup lands in the evaluator's existing catch and degrades to the
  ghost fallback, so a poisoned lookup can never fail a query. Guava swallows
  removal-listener exceptions; `release()` does not throw.
* **Parent loader pinned** to `classOf[VarkaFusedKernel].getClassLoader`
  rather than the context class loader, which removes the loader from the key
  (Janino keys on it weakly; Varka does not need to): the generated bytes
  reference only the JDK, `jdk.incubator.vector`, and Varka support classes on
  Spark's own classpath, never user code - and the evaluator's cast to
  `VarkaFusedKernel` needs that exact interface class anyway.
* **Escape hatch.** `spark.sql.codegen.varka.cache.maxEntries` = 0 disables
  sharing: the entry is emitted uncached and the evaluator releases its loader
  on task completion, exactly the pre-task-18 lifecycle.

### 2.3 Naming and telemetry reconciliation

The class is named by its shape: SHA-256 over a canonical rendering of the
key, truncated to 16 hex characters -
`VarkaFusedProjection_<hash>`, `SourceFile` `VarkaFusedProjection_<hash>.java`.
The cache map is keyed on the full structural key, so a hash collision cannot
cause a wrong hit; it could only give two distinct shapes the same *name*
(distinct loaders keep the runtime classes distinct; the class dump would
overwrite one file - astronomically unlikely and diagnostics-only).

Per-execution identity moves out of the bytes into a side table: the
`VarkaDebugInfo` attribute keeps its three-field format, `SourceFile` and the
line map stay properties of the shape (VARKA-16's `LineNumberTable` is indexed
by IR node, so it is exactly what should be shared), and the attribute's
`planFragment` field now carries the shape identity (`shape <hash>`) instead
of one query's projection list. The cache records each lookup's execution
identity - `Varka_<operator>_Stage<n>: <projection list>` - in a bounded map
keyed by the hash, and `executionsFor(hash)` is the diagnostics join. Ghost
fallback warnings stay fully attributed because the evaluator knows its own
operator and stage: `kernelIdentity` becomes the shape name plus the IR plus
the per-execution operator/stage.

Cache hit/miss counts surface twice: `LongAdder` counters on the cache (tests,
and VARKA-22's JFR events later), and two new `SQLMetric`s on both exec nodes
("Varka class cache hits"/"misses") threaded through the evaluator factories
like the existing three.

## 3. Files

New: `VarkaShapeCache.scala` (catalyst `codegen/varka/`), its suite, this
plan. Changed: `VarkaKernelEvaluator` (cache lookup instead of per-task
emission), both exec nodes and their factories (metrics), `StaticSQLConf` /
`SQLConf` (the cache-size conf and accessor), doc-only updates to both class
loaders and `VarkaDebugInfo` (their per-task and deferred-conflict paragraphs),
`VarkaDifferentialSuite` (warm runs, wrong-hit guards, the reworked Metaspace
test), `VarkaKernelEvaluatorSuite` (shape naming, side-table join),
`VarkaProjectExecSuite` (metrics), `docs/sql-varka.md` and `README.md`
(requoted from the regeneration run). Unchanged on purpose: `VarkaLoopEmitter`
(the name and debug strings were already parameters), the `VarkaDebugInfo`
byte format, `VarkaGeneratedClassLoader` behaviour, the engine module.

## 4. Validation

* Differential suites warm as well as cold: `checkDifferential` runs the
  Varka side twice, so every case checks a cache-hit execution against the row
  engine. Wrong-hit guards run near-miss shape pairs back to back:
  `date_add` vs `date_sub` on the same operands, same structure with a
  different literal count, same shape with different literal values.
* `VarkaShapeCacheSuite`: same shape different constants share one entry
  (hit); distinct structure / input count / literal count each miss;
  concurrent lookups emit once; eviction releases the evicted loader and the
  weak-reference proof collects it; a 10k-distinct-shape stress (op pattern
  from the bit pattern of the index) keeps the cache at capacity and Metaspace
  bounded; capacity 0 shares nothing.
* The reworked integration Metaspace test: 100 distinct-literal queries are
  now one shape - the suite asserts they hit (misses <= 1) and the footprint
  stays bounded.
* The gate: `VarkaThroughputBenchmark` regenerated in one run - `chain depth
  8` columnar back above 2x, `dayofweek` sheds the ~50 ms surcharge class
  reuse was diagnosed to fix; `VarkaColdStartBenchmark` and
  `VarkaCodegenBenchmark` regenerated with it (cold start emits fresh shapes
  per iteration, so it should stay near 1.5x - a prediction to score).
  Docs requoted from that run.
* Standing gates: everything green at the preferred width and
  `-XX:MaxVectorSize=16`; engine module untouched and green; `catalyst/doc`.

## 5. Explicitly out of VARKA-18

The row-consumer profitability decision (VARKA-19 - this task hands it the
flattened matrix it was waiting for), fallback-cause SQL-UI metrics and JFR
events (VARKA-22 - the counters land here, the events do not), the four gating
shapes and filters (VARKA-20 to VARKA-21), any emitter change, any engine-module code
change.

## 6. Outcome

Built as planned: `VarkaShapeCache` (key, entry, impl, side table, counters)
in catalyst `codegen/varka/`, the static conf
`spark.sql.codegen.varka.cache.maxEntries` (default 100, 0 = the per-task
lifecycle), the evaluator's `FusedRunner` reduced to a cache lookup plus a
per-task kernel instance, shape-hash naming end to end, two new SQL metrics
on both exec nodes, and the suites: 8 new cache tests (sharing, near-miss
distinctness, concurrency, eviction-with-weak-reference proof, the
10k-distinct-shape stress at capacity 64 with every evicted loader
collected, capacity 0, the side-table join), warm re-runs inside
`checkDifferential` so all differential cases check a cache-hit execution,
and the near-miss guard test. Everything green at the preferred width and
`-XX:MaxVectorSize=16`; engine module untouched and green; `catalyst/doc`
and scalastyle clean.

### 6.1 The gate, and the numbers

`VarkaThroughputBenchmark` regenerated in one run (a first run carried a
one-case Janino-baseline outlier - `date_add` at 76 ms against its usual
~50 - so the file was regenerated once more whole; the committed file is the
second run, and the two runs agree on every varka-side number within noise):

| case | VARKA-17 file | VARKA-18 file |
|---|---|---|
| `date_add` / `date_sub` / `datediff` | 1.8x / 1.7x / 2.3x | 3.8x / 4.2x / 5.2x |
| nested / shared subchain / mixed | 2.2x / 1.8x / 1.7x | 5.7x / 5.9x / 2.5x |
| `CASE WHEN` unpredictable / predictable | 2.1x / 2.0x | 7.0x / 6.2x |
| `dayofweek` | 1.2x | **9.2x** (7 ms vs 64 ms) |
| chain depth 1 / 2 / 4 / 8, columnar | 2.2x / 1.9x / 1.8x / 1.3x | 6.5x / 6.7x / 7.2x / **7.0x** |
| chain depth 1-8, row consumer | 0.6x falling | 0.8x flat |
| cold start (fresh shape, 100K rows) | 1.5x | 1.9x |
| emit+define+load+instantiate vs Janino | 77.5x | 68x |

The gate asked for depth 8 "back above 2x" and `dayofweek` shedding its
surcharge: both cleared with room - the whole depth curve is flat, exactly
the shape the buffer-level parity numbers always predicted (depth 8 within
10% of depth 1). The wins exceed the 7.5 surcharge arithmetic because that
arithmetic measured surcharges *relative to `date_add`'s own ladder*:
removing the whole ladder also recovers the baseline's share (`date_add`
itself went 27 ms to 13 ms). The 10k-shape stress holds Metaspace at cache
capacity with every evicted loader weak-reference-collected.

### 6.2 Predictions scored: 2.5 of 3

* **Depth-8 recovery (7.5): right, and understated.** Predicted -13 ms;
  actual -25 ms at depth 8, for the relative-baseline reason above.
* **Row-consumer curve flattens near 0.7x (7.5): right.** Flat at 0.8x
  (mixed 0.9x, residual-heavy 0.7x) - no crossing, no break-even depth.
  VARKA-19 now decides the profitability rule on numbers that mean something.
* **Cold start stays near 1.5x (this plan): half.** The mechanism held - a
  fresh shape misses by construction and the varka side is unchanged at
  18 ms best - but the committed ratio moved to 1.9x anyway because the
  Janino baseline read 33 ms against VARKA-14's 27 in this run (stdev 7 on a
  cold-start measurement; the ratio is not load-bearing for any decision).

### 6.3 Deviations and findings

* **The key is narrower than the milestone 2.1 wording**, as section 2.1
  planned: input ordinals and output Spark types are out (neither affects
  the bytes), lane types ride the IR records. No other deviation from the
  plan's design sections.
* **Fallback tasks now cost a lookup, not an emission.** `canRun` forces the
  runner, so before this task a partition whose batches all fell back still
  emitted and defined a whole class it never ran; now it takes a cache hit.
  Surfaced by the exec-suite metrics test, which counts one lookup per task
  including the fallback task.
* **A GC-proof lesson**: a block-scoped `val` holding the to-be-evicted
  entry stays live in the test method's local slots and pins the loader -
  the eviction proof only passes with the emission extracted into its own
  method frame (`emitForEviction`). The loader suite's `var x = null`
  pattern is the same lesson in another shape.
* Docs and README requoted from this run per the settled docs-timing
  decision; VARKA-19 regenerates again from its own run and should expect
  the row-consumer matrix at 0.8x flat as its starting point.

## 7. Ultra-review follow-up

An ultra review of the PR (vecbricks/varka #46) returned ten verified
findings against the sections above; all ten were fixed on the branch
before merge. The ones that correct this file's own record first:

* **The committed cold-start run measured hits, not cold shapes.** Section
  6.1's 1.9x row and 6.2's "unchanged at 18 ms" were wrong about what ran:
  `VarkaColdStartBenchmark` manufactures freshness through distinct columns
  and literals, which the shape key ignores by design, so after this task
  the guard query warmed the process-wide cache and every timed "cold"
  iteration was a hit. The benchmark now invalidates the shape cache before
  each timed iteration, and the regenerated committed file reads 1.7x -
  19 ms vs 31 ms best, 25 ms vs 38 ms average. The genuinely cold varka
  side is essentially the per-task era's 18 ms (this chain's emit, define
  and ladder are small), so the mislabeling flattered nothing material -
  but the harness's promise and the measurement now agree again. Lesson
  recorded in `SKILLS.md`.
* **The loader parent broke the documented `--jars` deployment.** Section
  2.2's parent-pinning rationale missed that the emitted bytes call the
  engine's `VarkaVectorSupport`, which in production arrives via `--jars`
  and is visible only through the context class loader - the loader of
  `VarkaFusedKernel` (catalyst, app classpath) cannot see it, so every task
  would have thrown `NoClassDefFoundError` into the ghost fallback,
  silently, while tests (engine on the test classpath) stayed green. The
  parent is the context loader at emit time again, as the per-task loaders
  had it; the loader stays out of the key because the bytes reference only
  JVM-wide classes, never session-isolated user code.

The rest, briefly: Guava's `get(key, callable)` wrappers (`ExecutionError`
and friends) would have let an OOM or interrupt during emit degrade to the
ghost fallback - `getOrEmit` unwraps to the cause before rethrowing. The
emitter's byte-affecting test hooks are emit inputs outside the key; the
cache now refuses to emit while one is set. The capacity conf's lazy
`SQLConf.get` read could freeze the default in when first touched on a
non-`SQLExecution` action; it reads `SparkEnv.get.conf` now (static confs
travel in `SparkConf`), with `SQLConf.get` kept for env-less test JVMs.
The side table records identities truncated to 256 chars and not at all
when the cache is disabled (it previously retained unbounded projection
strings JVM-wide, visible across Thrift Server sessions). The class dump
writes once per shape and directory instead of on every task. The shape
hash is computed on the miss path only; hits read the entry's stored hash.
The `maxEntries = 0` escape hatch collapsed into the one Guava path -
`maximumSize(0)` evicts each entry as it loads and the removal listener
releases it, so the separate unshared-entry lifecycle, its `shared` flag
and the evaluator's release listener are gone. And the never-called
`SQLConf.varkaCacheMaxEntries` accessor was deleted.

## 8. The second review round: max effort, on the tree

A local max-effort review over both varka catalyst directories, run after
section 7 landed, returned ten more findings - five of them critiques of
section 7's own fixes, which is the loop working as intended: fixed code is
new code and gets reviewed again. The emitter and the mod-7 magic-multiply
lowering, the review's original motivation, came out clean. What changed:

* **The loader question, settled properly.** Section 7's context-loader
  parent fixed `--jars` but would pin one session's isolated loader
  JVM-wide - and link other sessions' kernels through it - under
  executor-side artifact isolation. The parent loader is now part of the
  cache key by identity (`VarkaLoaderShapeKey`): with the one executor-wide
  context loader of the `--jars` deployment nothing changes (one class per
  shape), and under artifact isolation each session's loader gets its own
  entries. A closed session's entries are not released eagerly - nothing in
  catalyst observes session close - they age out of the LRU, bounded by
  capacity; recorded here as the accepted limitation.
* **Fate sharing, corrected twice.** The claim that `NonFateSharingCache`
  "exposes no removal listener" was wrong - its `apply(cache)` wraps a
  pre-built cache, listener intact. Lookups now go through it, so a
  cancelled or failed emit fails only its own task instead of cascading to
  every co-waiter (SPARK-43300); section 7's unwrap stays on top for the
  wrappers Guava still adds.
* **The hook guard moved to lookup entry** - a warm hit would have served
  *plain* bytes to a hooked caller, the mirror image of the poisoning the
  emit-path check stopped - plus a post-emit re-check so a hook flipped
  mid-emission cannot cache its bytes.
* **The hash no longer rides `Record.toString`**, whose exact format no
  JDK promises: `VarkaVectorIR.canonical` is a hand-pinned rendering,
  exhaustive over the sealed interface (a new node type refuses to compile
  until it renders), digested through the shared `JavaUtils.sha256Hex`.
  The suite pins the committed value (`586434f9b9739c40`) so any future
  drift - JDK or rendering change - fails loudly instead of silently
  renaming every dumped class across a cluster.
* **The dump memo is per-JVM** instead of skip-if-exists on disk: a file
  left by an older emitter under the same shape name is refreshed by each
  process's first write, while later tasks still skip.
* **Capacity 0 records identities again.** Section 7 stopped recording
  with the cache disabled, which broke the one remaining join from a
  shape-named class to its plan nodes; with truncation and the entry
  bounds, recording is safe at every capacity, so it is back.
* Smaller: `recordExecution` reinstates a side-table set evicted mid-write
  instead of silently dropping the identity; the class/`SourceFile` naming
  has one rendering (`classNameFor`/`sourceFileFor`) used by both the
  cache and `kernelIdentity`; and the evaluator builds the execution
  identity bounded to what the table keeps rather than rendering a wide
  projection per task.

## 9. Third round: convergence

Re-running the same max-effort review on the fixed tree returned seven
findings - none of production-outage class, five of them residue of the
section 8 fixes. Both of that section's structural decisions held: the
loader-in-key design survived against its recorded limitation, and the
fate-sharing stack was not re-flagged. What round three changed:

* **The hook guard got a write-generation.** Sampling the hook values
  around the emission cannot catch a hook set *and cleared* inside the
  window, so the guard's old comment overclaimed. The hook fields are
  private now, written only through setters that bump an `AtomicLong`
  generation; `emit` snapshots the generation and refuses to cache if it
  moved at all. A reflection test closes the other end: every `*ForTesting`
  field must register in `anyTestHookSet()`, so a future hook cannot
  bypass the guard unnoticed.
* **The conf read consults the source that has the key.** Section 8's
  `SparkEnv`-first read missed static confs set through a `SparkSession`
  builder over a pre-existing context (they land in the session, and `SET`
  reports them, but never reach `SparkEnv`'s `SparkConf`). The order is
  now: the SQL view if it carries the key, else the JVM-wide `SparkConf`
  (`--conf` statics, for the non-propagated-task first touch), else the
  default - covering both this and the round-one freeze case.
* **Both pinned-hash gaps closed**: a second golden-hash test uses a key
  with all 14 IR node types, so no canonical rendering is unguarded (the
  chain-only test covered 4 of 14 despite the javadoc's claim).
* **The side table is floored at 64 shapes** independently of capacity, so
  the diagnostics join works at `maxEntries = 0` beyond four live shapes;
  its reinstate-after-eviction is now `putIfAbsent`-with-retry, closing the
  lost-update window the plain reinstate had; and identities are bounded
  through the shared `SparkStringUtils.abbreviate` (marker inside the 256,
  not appended past it).

## 10. Deferred: the fourth round's residue

A fourth max-effort round on the fixed tree returned seven findings, none
production-facing: three test-JVM-only items in the hook-guard machinery,
one conf-determinism gap, three cleanups. The fixing stopped here by
decision - the items below are deferred to the milestone-3 debt register
(`m3/PLAN.md` section 10) rather than patched in this PR, because
the right fix for the largest cluster is a refactor, not a fourth patch:

* **Hooks as emit options.** The round's design finding, and the reason to
  stop patching: the guard stack built across sections 7-9 (private
  fields, write-generation counter, reflection-enforced registration, the
  gate plus the snapshot) is a bolt-on, and two races remain inside it - a
  hook set between the gate and the generation snapshot still caches its
  bytes under the plain key, and the gate fails every concurrent lookup
  JVM-wide instead of letting unrelated queries emit uncached. An explicit
  emit-options record that rides the shape-cache key removes both, and the
  enforcement suite with them, by construction.
* **Deterministic executor-side sizing.** The lazy singleton freezes
  whatever capacity the first-touching thread resolves, so two identically
  configured JVMs can size differently, and a builder-set value never
  reaches an executor at all. Needs one deterministic executor-side source
  and a documented boundary for builder-set statics.
* Cleanups: `recordExecution`'s retry loop is `asMap().compute` in one
  atomic call; the entry should cache the resolved kernel constructor
  (today a reflective `getConstructor()` per task) and derive
  `className`/`sourceFile` from its stored hash instead of storing all
  three.
* From the reuse angle, adjacent: `renderLineMap` still renders IR nodes
  via `Record.toString` into the `LineNumberTable` key baked into the now
  shared bytes - the same unspecified-format concern `canonical` was
  written to end.
