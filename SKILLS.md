# Varka Project Lessons Learned

Working notes from implementing the Varka MVP (Spark columnar execution). These are
reusable debugging lessons and project-specific gotchas. Not instructions; see
`AGENTS.md` for the workflow rules.

## Classpath Shadowing (the stub trap)

- Spark modules declare `test->test` project dependencies (e.g. `sql/core` on
  `sql/catalyst`). That puts the *compiled test classes directory* of the dependency
  on the dependent module's test classpath, ahead of jars in `~/.m2`.
- A test-only source that redefines a class at the same FQCN as a dependency silently
  shadows the real jar: the code "runs", produces no exception, and reinstalling the
  jar changes nothing.
- Varka kept a no-op stub `DateVectorOps` in `sql/catalyst/src/test/java` (Task 5) so
  catalyst tests could resolve the kernel owner FQCN without the engine jar. It
  shadowed the real kernel on the sql/core test classpath and made the kernel path
  appear to write nothing.
- Fix used: delete the stub, add the engine jar as a test-scope dep in the module's
  `pom.xml` (mirroring sql/core), delete the stale compiled class under `target/`.
- Detection: instrument the layer you *think* is running. If the debug prints never
  appear, you are executing a different class than the source you edited. Look for
  stale copies: `find target -name '*.class'`, search every jar/dir on the classpath
  for the FQCN.

## Buffer-Reuse Aliasing (UnsafeProjection)

- `UnsafeProjection` (and `GenerateUnsafeProjection`) reuses a single output
  `UnsafeRow` buffer across calls. Materializing results into an `Array` without
  copying yields an array whose elements all alias the last row's buffer: every
  output shows the last value.
- The copy belongs to whoever *materializes*, not to the operator. Spark operators
  stream reused rows deliberately: `ColumnarToRowEvaluatorFactory` is
  `input.rowIterator().asScala.map(toUnsafe)`, with no copy. `VarkaColumnarToRowExec`
  copied per row on both its kernel and fallback paths until finding 9 removed it; that
  cost an `UnsafeRow` allocation plus a memcpy per row on the hot path and bought a
  guarantee the standard path never gave. (Earlier revisions of this file prescribed the
  copy, from when the evaluator materialized with `process(...).toArray`; it streams now.)
- `QueryExecution.toRdd`'s scaladoc states the contract and names `collect()` as "one of
  known bad usage" - `RDD.collect` is `iter.toArray` per partition, so it aliases. Use
  `Dataset.collect` (which serializes each row as it iterates), or, in a test that wants
  `InternalRow`s, `toRdd.map(_.copy()).collect()`.
- Not a Varka rule: collecting a plain row-engine query that way returns 2 distinct row
  objects for 5 rows, with wrong values. `SparkContext.hadoopRDD`'s scaladoc documents
  the same hazard for reused Hadoop `Writable`s.
- Corollary for tests: a suite that materializes rows can pass for the wrong reason while
  an operator copies. `VarkaDifferentialSuite` did, and only failed once the operator
  stopped - the copy had been masking an unsound `toRdd.collect()` in the test itself.

## Alias Unwrap Is Needed at Two Layers

- A projection list is `Alias(expr, name)` at the top level, and after
  `BindReferences` the bound expression is `Alias(BoundReference(...), ...)`.
- Any code matching projection expressions against concrete types must unwrap `Alias`
  twice: once on the unbound list (`eligibleOps`) and once on the bound list
  (`buildOutputPlan`). Missing either makes eligibility silently fail (kernel never
  runs) or the plan match return `None`.
- Symptom: with both missing, fallback covers it; the kernel path never executes. The
  rule test and the `numVarkaBatches` metric reveal it once rows are correct.

## Masked Bugs

- A fallback path that returns *wrong* results can hide an entirely untouched kernel
  path. Here the "kernel results" seen early on were actually the fallback (with the
  aliasing bug). The real kernel only ran once the Alias unwraps landed, which is
  also when the stub trap surfaced. Two independent bugs masked each other.

## Debugging Method: Progressive Isolation

1. Verify the mechanism in isolation (e.g. a manual `MemorySegment.ofAddress(...).set`
   write that Arrow can read).
2. Verify the middle layer directly (call the kernel straight on Arrow buffers).
3. Instrument the deepest layer (prints inside the kernel). Prints not appearing =
   wrong class on the classpath.
Each step either pins the fault or narrows it.

## Environment Facts (verified in this repo)

- `TaskContext.get()` is non-null on the driver in local-mode tests, so kernel
  execution also happens in driver-side eval.
- Test JVMs pass `--enable-native-access=ALL-UNNAMED` (`project/SparkBuild.scala`),
  so `MemorySegment.ofAddress(...).reinterpret(n)` works in tests.
- `ColumnarBatch` and Arrow vectors are not serializable. To feed a batch into a node
  under test, rebuild the batch inside the task from a serializable spec. Nested case
  classes capture the non-serializable test suite via `$outer`; keep spec classes
  top-level.
- `SparkPlan.executeCollect()` goes through `getByteArrayRdd()` and casts rows to
  `UnsafeRow`; row-producing evaluators must emit `UnsafeRow`.

## Columnar Transition Wiring (plan level)

- `ApplyColumnarRulesAndInsertTransitions` runs `preColumnarTransitions`, then
  `insertTransitions`, then `postColumnarTransitions`. A `postColumnarTransitions`
  rule sees the transitions already inserted.
- `ensureOutputsRowBased` gives a dual-mode plan (`supportsRowBased` and
  `supportsColumnar`, e.g. `InMemoryTableScanExec`) row output when its parent
  consumes rows, so above a cached scan there is often *no* `ColumnarToRowExec` to
  pattern-match on. Fuse on `child.supportsColumnar` (and switch the dual-mode
  child to columnar output), not on the presence of a transition.
- `ColumnarToRowExec` is row-only: it has no `doExecuteColumnar`, so calling
  `executeColumnar()` on it throws. A fusion node that consumes a columnar child
  must absorb the transition (`case ColumnarToRowExec(inner) => inner`) instead of
  wrapping it.
- The `ColumnarToRowTransition` tag is read by some machinery as "semantics-free
  row conversion" - `CachedBatchSerializer.convertToColumnarPlanIfPossible` strips
  a topmost transition and executes its *child* to get columnar cache input. A
  fused node wearing the tag (every Varka `*ColumnarToRowExec`) carries real work
  inside it, so every tag consumer that strips must instead convert the fused node
  to its columnar sibling (identical kernels, columnar out) - the Arrow serializer
  override does. Found in task 21 as a wrong-cached-view bug latent since task 6:
  every direct query stays right, and only a *cached* view materializes the
  dropped work. When adding a fused transition node, grep the tag's consumers.

## Metrics as the "did it really run" proof

- A fused plan plus correct results does not prove the kernels ran: the per-batch
  fallback also returns correct results. Prove execution with a metric
  (`numVarkaBatches`) bumped only on the kernel path.
- Read metrics *after* execution: run `checkAnswer`/`collect` first, then read.
  Reading before execution returns 0 even though every guard passed.
- The executed-plan root is often a `WholeStageCodegenExec` whose `metrics` map
  only has `pipelineTime`. Read the node's own metric via
  `plan.collectFirst { case v: VarkaColumnarToRowExec => v }`, not `plan.metrics`.

## Extra Sessions on the Shared Context

- For side-by-side engine comparison, build extra sessions on the same context:
  `SparkSession.builder().sparkContext(spark.sparkContext)`. Clear the active and
  default sessions between creations (`SparkSession.clearActiveSession()` /
  `clearDefaultSession()`).
- Sessions have separate catalogs: a temp view cached in one is not visible in
  another; register and cache the data in every session.
- `InMemoryRelation` holds the cache serializer in a process-wide static
  initialized on first use; call `InMemoryRelation.clearSerializer()` in
  `beforeAll` and again in `afterAll` so the choice does not leak to later suites.
- AQE is on by default in the test framework's shared session; disable it
  explicitly on custom sessions, or plans change shape and QueryStage threads leak.

## Build Gotchas

- The engine module is a reactor module since the sbt wiring change: sbt builds it
  in-tree and puts its jar on the catalyst/sql test classpaths itself
  (`VarkaEngine`/`VarkaEngineDependency` in `project/SparkBuild.scala`), so no manual
  install step remains. Maven still builds it standalone
  (`./build/mvn -f sql/varka/engine/pom.xml test`; `mvn` is not on `PATH`, use
  `./build/mvn`), which is how the engine-only suites and JMH run.
  (Earlier revisions of this file described a `~/.m2` install cycle from before the
  reactor change.)
- scalastyle requires a trailing newline at EOF ("File must end with newline
  character") and rejects `throw new XxxError` via the `throwerror` rule. For a
  deliberate `NoClassDefFoundError` test hook, wrap the throw in
  `// scalastyle:off throwerror` / `// scalastyle:on throwerror`.
- **Java in a non-core module must not pass a Guava type to a `core` API - and only
  Maven can tell you.** `core/pom.xml` relocates `com.google.common` to
  `org.sparkproject.guava` when it shades, so in the shaded jar the signature reads
  `NonFateSharingCache(org.sparkproject.guava.cache.Cache)`. Scala never notices,
  because scalac resolves the symbol from the Scala pickle, which the shade plugin
  does not rewrite; javac reads the relocated descriptor and fails with
  `cannot infer type arguments`. So the same call compiles from Scala and not from
  Java, and SBT - which does not shade - hides it from both: the only gate that sees
  it is the "Java 25 build with Maven" CI job. Upstream hit this too (SPARK-44064
  added a Guava-free `NonFateSharingCache.apply` overload precisely "to avoid non-core
  modules Maven test failures caused by using shaded core module"), and the two other
  non-core users, `CodeGenerator` and `ProtobufUtils`, both take Guava-free overloads.
  Note what this means for the Scala side as well: a Scala call site that passes a
  Guava type compiles against a method that does not exist in the shaded artifact, so
  it is a latent runtime failure rather than a safe alternative. Keep Guava types
  inside the module that owns them; when a `core` utility cannot be reached without
  one, reimplement the few lines locally (task 23 did this for the shape cache's
  single-flight gate). Verifiable in seconds without a Maven run: `javac` the file
  against `~/.m2/.../spark-core_2.13-*.jar` and see it fail.
- **A Java class with an incubator-module type in a field needs `--add-modules` twice
  under Maven, and again only Maven can tell you.** Task 24 put `SelectionVectorOps` -
  a `jdk.incubator.vector` kernel - in catalyst's main sources. Adding
  `--add-modules jdk.incubator.vector` to `scala-maven-plugin`'s `javacArgs` compiles
  it, and the build then fails *after* a successful compile with
  `NoClassDefFoundError: jdk/incubator/vector/VectorSpecies`. The reason is zinc's
  API extraction: `sbt.internal.inc.ClassToAPI.structure` calls
  `Class.getDeclaredFields()` on the class file it just wrote, which loads the field
  types **reflectively, in the compiler's own JVM**. So the flag has to go in that
  plugin's `jvmArgs` as well - two blocks, both with `combine.children="append"` so
  the parent pom's own arguments survive. SBT never reaches this because the sbt
  launcher already runs with `--add-modules=jdk.incubator.vector`, which is exactly
  the shape of the Guava trap above: an SBT-green, Maven-red failure on the Varka Java
  surface. Reproducible in seconds without a Maven run - `java -cp
  sql/catalyst/target/scala-2.13/classes` a one-liner that calls
  `getDeclaredFields()` on the class, with and without the flag.

## Build Performance (measured, Aug 2026)

Benchmarked on a ThinkPad P16s Gen 4 (Ryzen AI 9 HX PRO 370, 12c/24t, 96 GB, NVMe).
Numbers are wall-clock for a cold `sql/compile` chain unless noted.

- The ceiling is Scala 2's single-threaded compiler frontend plus Spark's serial module
  graph (core -> catalyst -> sql/core). CPU sits at 350-570% out of a possible 2400%,
  so most of a build is one core running scalac.
- **Background CPU contention was the single largest effect.** The same cold build took
  103.9 s while three runaway browser tabs ate ~1.3 cores, and 89 s once they were gone
  (-14%). It also widened run-to-run spread from +/-1.7% to +/-15%.
- **One-shot `build/sbt` invocations cost ~9.3 s each** in JVM startup and build-definition
  loading. A no-op `sql/compile` is 11.0 s standalone but ~1.7 s as another command in a
  live session.
- **sbt beats Maven** for the same chain: 103.9 s vs 139.5 s, plus real incremental
  compilation. When Maven is required, `MAVEN_ARGS="-T 1C"` builds independent modules in
  parallel (CPU 551% -> 817%); `build/mvn` also ships a 4 GB heap and a 128 MB code cache,
  which `MAVEN_OPTS` can raise.

Tuning knobs that were tested and made **no measurable difference** -- do not re-litigate
these without new evidence:

- `-Ybackend-parallelism` (verified it reached scalac via `show sql/scalacOptions`)
- raising the sbt heap above the 8 GB set in `.sbtopts`
- Zinc's `recompileAllFraction` (0.2 / 0.5 / 1.0 all identical)
- scalac warning analysis: `-Wunused:imports` and the whole `-Wconf` list cost nothing
- JVM transparent huge pages, CPU governor / power profile, AC vs battery
- I/O, swap and filesystem tuning -- a cold build takes 62 major page faults and writes
  ~2 MB/s, so the kernel is not in the path at all
- genjavadoc is already gated to the unidoc config and does not run on normal compiles

Benchmarking method: compare interleaved A/B runs by their minimums. Single-run
comparisons on a contended machine carried a +/-15% noise band, large enough that several
apparent small wins turned out to be noise.

## C2 Compile Latency Is the Wide-Vector-Loop Cliff (root cause, proven)

- A 64-op emitted vector loop ran 1.0 G rows/s in one JVM and 9-13 M rows/s in
  another. First hypothesis - "history-dependent inlining", suspecting
  `InlineSmallCode` - was *refuted* by experiment: raising it changed nothing. The
  proven mechanism (`-XX:+PrintCompilation`): the method's tier-4 OSR compile takes
  ~10 seconds for a 1457-byte method whose 64 Vector API call sites each expand into
  large intrinsic graphs, and until it lands the loop runs the C1 version with boxed
  vectors. A 30-second window showed the rate jump 9 -> ~1000 M rows/s at t=12s.
  "JVM history" only shifted when the compile started relative to the measurement
  window - fresh JVMs got it in during warmup, busy ones did not.

  **Update, task 43: this no longer reproduces, on the same path, at four times the width.**
  A ladder of single-output loops from 20 to 248 `IntVector` ops - a `greatest`/`least` tree
  over independent `dayofweek(d + k)` subtrees, which is linear at 19 ops per step - was
  measured with `-XX:+PrintCompilation` at both widths on JDK 25. Tier-4 **OSR** compile of
  `loopDense0`, which is the path the paragraph above describes: 15, 52, 100, 163, 165 and
  186 ms across the ladder at AVX-512, and 12 to 140 ms at 128-bit. The standard, non-OSR
  compile is slower and still small: 30 to 271 ms at AVX-512, 58 to 501 ms at 128-bit, linear
  at roughly 1.1 and 2.0 ms per op. So a 248-op loop compiles in 186 ms where a 64-op loop
  once took ~10 s. The old observation is not being called a mismeasurement - a rate jumping
  9 to ~1000 M rows/s at t=12s is not subtle - but the number describes a JDK that is no
  longer the one in use, and anything resting on it (`GROUP_BUDGET`'s javadoc, among others)
  needs re-deriving rather than re-citing.

  **And it disagreed with another number this repository already carried.** `PLAN_MILESTONE_4.md`
  section 2.3 and its debt register both price a wide loop's compile at "~1 ms per vector op",
  which at 64 ops is 64 ms rather than 10 s - a 150x disagreement that sat unremarked. The
  ladder agrees with the per-op figure. So the honest reading of the ~10 s is that it was
  probably never ten seconds of compiler *work*: the bullet above says fresh JVMs got the
  compile in during warmup and busy ones did not, which describes a compile task **queueing**
  behind others under load. That keeps the observation - a loop running C1-boxed at ~1% until
  its compile lands - and drops the inference that op count caused it. A queued compile can
  bite at any width, which is a scheduling property and not something a per-method op budget
  can bound.

  **Stamp measured numbers with the host and JDK that produced them.** The ~10 s entry did not,
  which is why nobody could tell staleness from disagreement for as long as both numbers sat
  in the tree. Everything above was measured on an AMD Ryzen AI 9 HX PRO 370 under OpenJDK
  25.0.4+7 on Linux, via `-XX:+PrintCompilation` over the committed
  `VarkaEmitterParityBenchmark` ladder.

  **Throughput does not fall off either, at either width.** Nanoseconds per row per op over
  the same ladder: 0.0078, 0.0073, 0.0072, 0.0077, 0.0075 from 58 ops up at AVX-512, and
  0.0212, 0.0179, 0.0163, 0.0163, 0.0168 at 128-bit - flat, and at 128-bit *improving* with
  width, since the narrowest body spreads the loop's fixed costs over the fewest ops. There
  is no register-pressure cliff for an emitted single-output loop up to 248 ops.

  **And C1's `out of virtual registers in linear scan` is not about the machine register
  file.** The bimodality section below attributes that refusal to 128-bit and its sixteen
  `xmm` registers. Measured, it happens identically at 512-bit with thirty-two `zmm`
  available, on exactly the same two methods - `ChronoVectorOps::vectorFourFields` (936
  bytes) and the widest ladder point's `epilogueDense` (1954 bytes), never any
  `loopDense0`. It is C1's own linear-scan allocator running out of *virtual* registers on a
  large body, and it ends in "retry at different tier", so the method goes to C2 rather than
  failing to compile - which is why it was never visibly slow.
- The structural fix stands regardless: keep every hot loop method small by
  construction (the emitter splits outputs across sibling loop methods of at most
  `GROUP_BUDGET = 16` ops, called from a driver). Small methods compile in
  moments; the 64-op kernel as four 16-op methods hits ~1 G rows/s in the same
  polluted JVM that showed the cliff.
- Corollary for benchmarks of generated code: a case that never speeds up may be
  waiting on a compile, not hitting a wall. Distinguish with a long window and
  periodic rate reporting before concluding anything; then read
  `-XX:+PrintCompilation` (a repeated OSR task line marked `blocked` was the tell).
- Related cost numbers: emitting + defining + loading + instantiating a fused kernel
  class is 130-450 us even for the widest shape - class *generation* is never the
  cold-start cost; C2 compile latency is.
- Same family, earlier finding (task 10): two vector loops emitted into one method
  also degrade each other (3x-4x on the second loop). One C2 compilation per hot
  loop, always - sibling methods, not longer methods.
- Same family, task 14's post-commit diagnosis: **a class defined per task re-pays
  the whole tier ladder per task.** The per-task loader defines a fresh kernel class
  each task; HotSpot treats it as new, so every task runs interpreter, then C1 with
  boxed vectors, then the C2 OSR compile - a *fixed per-task* cost that grows with
  the loop method's vector-op count (~13 ms for an 8-op chain, ~50 ms for the 20-op
  dayofweek fold) and dwarfs the ~80 us emission it sits next to. Two diagnostics
  that pin it: (1) scale the table 4x - a per-task-fixed cost leaves the absolute
  delta unchanged where a per-row cost quadruples it; (2) `-XX:+PrintCompilation`
  shows one tier-4 OSR of the same-named method *per task*, each followed by
  "made not entrant: OSR invalidation" as the task's class dies. The decomposition
  (PLAN_TASK_14.md 7.5): the C2 compile itself is ~1 ms per vector op (2/10/20-25 ms
  for 3/10/20-op loops), and the interpreted and C1 profiling phases before it
  scale the same way, because tier counters advance per backedge at boxed speed -
  which is also why a scratch-batch warm spin saves nothing. Corollary for any
  cross-task cache: caching `byte[]` does not help - a re-defined class is a new
  class and re-pays the ladder; only reusing the *loaded class* preserves the C2
  code. And benchmark tasks must be long enough to amortise the ladder, or the
  committed number prices JIT warm-up, not the kernel. Task 18 acted on the
  corollary - `VarkaShapeCache` shares the loaded class across tasks, keyed on
  the IR shape - and the committed depth curve flattened from 2.2x-eroding-to-1.3x
  into 6.5-7.2x flat, confirming the ladder was the whole erosion.
- Second corollary, caught by task 18's PR review after the results file was
  committed: **a cache keyed on structure silently defeats a harness that
  manufactures freshness through values.** `VarkaColdStartBenchmark` made each
  iteration "fresh" via distinct columns and literals - exactly what the shape key
  ignores by design - so after task 18 the guard query warmed the process-wide
  cache and every timed "cold" iteration measured a hit while the harness's own
  comments still promised a fresh emission. When a cache key changes, re-derive
  every benchmark's freshness argument from the new key rather than trusting the
  harness; the fix here invalidates the shape cache inside the timer loop.
- **The 64-op cliff does not generalize to every wide method - it is specific to
  what was compiling it.** Task 32 step B2 built the real thing the cliff worried
  about, a 200-vector-op single loop method (four calendar fields fused by a
  widened `GROUP_BUDGET`), and measured its compile time directly with
  `-XX:+PrintCompilation` filtered to the generated class's own name (every Varka
  kernel gets a distinct class, so this is exact). It reached tier 4 in **272 ms**,
  and the widest kernel in the same suite (twenty separate loop methods) in 2.4 s -
  neither approaches the ~10-second cliff, and neither shows the cliff's own tell
  (a repeated tier-4 task line marked `blocked`, zero occurrences anywhere in the
  log). The original 64-op finding was on a *different* kernel - `javac`-compiled
  Java source with a heavier call-site mix - and the risk register correctly
  treated it as a hazard to re-check rather than an assumed fact, which is exactly
  why this measurement was taken before recommending the design rather than after.
  The general lesson: a historic compile-time cliff belongs to the method that hit
  it until proven otherwise, not to "loop methods of that op count" as a category -
  measure the actual generated bytecode's compile time before assuming a past
  finding transfers to a new emitter, a new op mix, or a new class shape.

- **Op count is not the only compile gate: bytes are a second, harder one.**
  `GROUP_BUDGET` bounds a loop method's *vector ops* because compile time grows with
  them. `HugeMethodLimit` (8000 bytes, a product default) bounds a method's *bytecode*,
  and past it HotSpot does not compile the method slowly - it does not compile it at
  all, at any tier, so the method runs interpreted with boxed vectors forever. The two
  gates catch different shapes, and the emitter's epilogue is where the second one
  bites: task 24 made it one method over *every* output, so its size grows with the
  whole projection rather than with a group, and four calendar fields over five date
  columns crossed 8000 bytes. Measure it rather than estimating - the `Code`
  attribute's length, which is exactly what HotSpot measures, is two lines through
  `java.lang.classfile` (`VarkaEmitterTestSupport.codeSize`) - and assert the crossing
  in a test, so the next wide node moves a number instead of quietly falling off.
  Task 32 step B1's ladder is in `PLAN_TASK_32.md` section 7.1.

## A hand-written comparison kernel needs every fast path the real one has

`ChronoVectorOps.vectorFourFields` was built as task 32's throughput ceiling: same
arithmetic, same guard, same op count as the emitted kernel it stands in for. It has no
dense/masked split, though - it always builds a `VectorMask` and uses masked load/store
overloads, even when the caller reports every row non-null. On null-free data the emitted
kernel dispatches to a genuinely unmasked dense body (task 10's split), so the "ceiling" was
silently measuring the masked-body cost the whole time. Once step B2 made the true emitted
dense-shared kernel buildable, it beat the "ceiling" by 1.15-1.20x, reproducibly across three
runs. The lesson generalizes past this one kernel: a hand-written stand-in for an emitted
path is only a fair bound if it takes every fast path the emitted dispatcher can reach for
the data it is fed - a masked-only comparison kernel run on null-free data is not measuring
the same thing the emitted kernel is, and the gap does not announce itself; nothing crashes
or looks wrong, the "ceiling" just quietly is not one.

## Calling into an uncompilable method costs something even when it does nothing

A method past `HugeMethodLimit` is never compiled at any tier, so every call into it runs
interpreted - including a call that hits an early return and does no real work at all.
Measured on a 20-calendar-output epilogue (9436 bytes unshared, past the limit; 4048 bytes
shared, under it) at an *aligned* chunk size, where the generated early-return check
(`if (loopBound < length) ...; return 0`) fires on every single call and the epilogue never
executes a real row: the unshared (uncompilable) version still cost 1.36x the shared
(compilable) one, invisible at large chunks (4096 calls per pass, real vector work
dominates) and clearly visible at small ones (15625 calls per pass, the per-call cost
dominates). Do not describe a `HugeMethodLimit` crossing as "costs nothing on aligned
batches" - it costs nothing *extra in arithmetic* on aligned batches, but the call itself,
paid on every batch whether aligned or not, is measurably more expensive when the callee can
never leave the interpreter. The unaligned case remains far larger (7.3x at a chunk where
most of the batch is remainder) because there real interpreted arithmetic dominates too.

## A benchmark that reuses `repeats` for wall-clock scaling must also scale its declared row count

`Benchmark`'s Rate/Per-Row columns divide the measured time by the row count passed to its
constructor, not by what the timed closure actually does. A section whose closure loops
`repeats` times over `numRows` (the pattern this file's "year" and alignment-ladder sections
both use, to pay the per-call prologue at production rate rather than timing one giant call)
must construct `Benchmark` with `numRows * repeats`, not `numRows` - passing the smaller
number does not corrupt any *ratio* between cases in the same section (both are scaled by the
same missing factor), but every absolute Rate and Per-Row figure comes out wrong by exactly
that factor, silently, with no error and a plausible-looking table. Sanity-check a new
section's absolute numbers against a neighboring section of comparable op count before
trusting them, not just its ratios.

## Sharing below the node level in an emitter

- **The values worth sharing are not always nodes.** Varka's DAG-CSE memoizes on
  structural equality between IR nodes, which cannot help when the redundancy is
  *inside* one node's emitted run: `year(d)` and `month(d)` are two nodes that each
  emit the same forty-op civil-from-days decomposition, and era, century and the rest
  are locals, invisible to any walk over nodes. The fix does not need an IR change -
  a *fragment*, keyed on (kind, child) and tracked in a per-lane-group set beside the
  node-level `computed` set, shares the locals directly. A multi-value IR node emits
  identical bytes, so choosing between them is engineering cost, not throughput; the
  fragment wins because it generalizes to every node built on the same prefix without
  the IR naming any of them.
- **A shared run must be keyed on everything it reads, not just on its input.** The
  prefix at the time also emitted a range guard (task 26's, since moved by tasks 51
  and 52), ANDed with the node's validity word. Every plain extraction aliases its
  word to its child's, so those shared safely - but `add_months(d, n)` ANDs the date's
  word with the month count's, so keying on the child alone would have given it a
  guard computed under a different mask. Put the extra input in the key and the
  mistake becomes unrepresentable rather than a rule in a comment; the key kept the
  extra input after the guard left, since the lesson is about what a run reads.
- **A generated class's raw bytes never compare equal across two emissions**, because
  the harness names each class afresh and the name is in the constant pool. An
  assertion of the form "this option changed / did not change the bytecode" has to
  compare a *method's* code size (or its instructions), not `Arrays.equals` on the
  class. The false direction is the dangerous one: `!Arrays.equals` passes for any two
  emissions whatsoever, so a test written that way proves nothing at all.

## Vector API on HotSpot, Measured (JDK 25, x86-64)

- **A day-indexed lookup table beats the civil-from-days arithmetic, and a plain
  scalar loop beats the vector gather** (`VarkaVectorApiProbeBenchmark`,
  committed). Impala reads `year` out of a table covering 1950-2049 and computes
  it only outside that window; priced on lanes here, over 20M dates at AVX-512:

  | | whole 100-year table (143 KB) | seven-year span (~10 KB, TPC-H shaped) |
  |---|---|---|
  | `IntVector` gather | 3573.9 M rows/s | 3728.2 |
  | `IntVector` arithmetic | 2379.8 | 2368.3 |
  | scalar `int[]` loop | 3766.3 | **4630.0** |

  Two expectations died here. A gather is **not** automatically slower than
  forty lane ops on this hardware, even when the table overflows L1. And the
  **scalar** loop is fastest of the three - 1.95x the vector arithmetic on the
  realistic span - because the Vector API's gather takes its index map as an
  `int[]`, so the index vector has to be stored and read back, and that spill
  is an artifact of the API rather than of the machine.

- **`IntVector`'s index-map overload exists only on `fromArray`, never on
  `fromMemorySegment`.** Enumerated, not assumed - the whole `from*`/`into*`
  surface is `fromArray(species, int[], int, int[], int)` and
  `fromMemorySegment(species, MemorySegment, long, ByteOrder)`, with no third
  form.

  **This bullet first drew the wrong conclusion from that fact, and the
  correction is the useful part.** It said a gather is therefore unreachable for
  a Varka kernel, because Varka's inputs are off-heap. The API limit is real but
  it is about gathering *from* an off-heap table - item 9's dictionary, which
  genuinely is off-heap. It says nothing about gathering an **on-heap constant
  table** with indices derived from off-heap data, which is what a calendar
  table would be: the column loads with `fromMemorySegment`, the index vector
  spills with `intoArray`, and the gather reads a table Varka owns. Measured in
  exactly that shape (`year(d) = 1998`, counted, column in a `MemorySegment`):
  **2070.8 M rows/s against the arithmetic's 1329.3, a 1.6x**. One API fact,
  two different situations, and reading them as one cost a real option.

- **Size a lookup table to the calendar's period and it needs no fallback.**
  ClickHouse's `DATE_LUT_SIZE` is `0x23AB1` - 146097, exactly one Gregorian era,
  anchored at 1900. Timestamps outside the window fall back to cctz, but day
  numbers do not: `shiftIntoLUTRange` moves them by whole 400-year cycles and
  adds `400 * cycles` to the year, because 400 years is the calendar's period. A
  table indexed by **day of era** makes that the only path - it covers every
  `int32` date with no fallback at all, and the reported year is
  `400 * (era - bias) + table[dayOfEra]`. Varka's prefix already computes that
  index - `emitEra` is the first thing it emits - so the table replaces
  everything after it. 571 KB as an `int[]`; a seven-year query touches 10 KB of
  it. This is the shape behind the 1.6x above.

  ClickHouse's 16-byte entry is six bytes of calendar - year, month, day, day of
  week, days in month - and ten of time zone. The calendar part packs into 26
  bits, so a four-field table is the same `int[]` as the year-only one and each
  further field is a shift and a mask after the one gather: the problem task 32
  solves with a shared prefix, solved with memory instead. ClickHouse also keeps
  the inverse, a 4800-entry first-day-of-month table that makes days-from-civil
  one lookup plus `day - 1`. Neither variant is measured here; both are listed
  under milestone 6's item 10, which also records what the rest of ClickHouse's
  date code was checked for and why none of it transfers.

- **Fusion reverses all of it, and that is the result that matters.** The table
  above times one field into an `int[]` - the shape where Varka's advantage is
  zero. Measured again as `year(d) = 1998`, counted, where the vector paths
  never leave a register:

  | | M rows/s | |
  |---|---|---|
  | gathered from the table, compared in lanes | **3999.8** | 2.8x |
  | arithmetic in lanes, compared in lanes | 1453.0 | 1.0x |
  | scalar lookup inside a vector kernel (spill, scalar, reload) | 1446.9 | 1.0x |
  | scalar loop end to end, no lanes anywhere | 848.1 | 0.58x |

  **The scalar loop that won the unfused test by 1.95x loses the fused one by
  1.7x**, 4630.0 down to 848.1: once the result has to be compared and counted,
  a per-row loop cannot keep up with lanes doing the same work sixteen at a
  time. And the hybrid an emitter would actually have to produce - spill the
  lane group, scalar-lookup, reload, carry on in lanes - is a **wash** with the
  arithmetic (1446.9 against 1453.0): the spill costs exactly what the lookup
  saves. So *emitting scalar ops with a lookup table buys nothing inside a fused
  kernel*, which is the question this was run to answer.

  What does win is the gather, by 2.8x, because it replaces forty lane ops while
  the compare and the count stay in registers. That is the lowering blocked by
  the missing `fromMemorySegment` index-map overload and nothing else - the one
  place where an API gap, not the hardware and not the arithmetic, is costing a
  measured 2.8x on the corpus shape.

  One caveat on reading the vector rows: all three vector paths end in
  `VectorMask.trueCount()`, and the arithmetic drops from 2368.3 unfused to
  1453.0 fused, which is more than a compare should cost. Whatever that is, it
  is paid equally by the gather and the arithmetic, so the 2.8x between them
  stands; the scalar comparison is unaffected by it and loses anyway. Also still
  unmeasured: writing to a `MemorySegment` rather than an `int[]`, null
  handling, and the batch-level fallback a 1950-2049 table needs for the
  `0001..9999` range SQL allows.

- An *exact* magic multiply on int lanes exists only for dividends under roughly
  **46341**, and the bound falls straight out of the two conditions rather than
  needing a search: worst-case `e ~ d` forces `2^k > d * v`, hence `M ~ v`, hence
  `v * M < 2^31` gives `v < 2^15.5`. Past that, use a **round-down** magic
  (`M = floor(2^k/d)`), which never overestimates the quotient, and pay a fixed
  number of carry steps - one compare and two masked adjustments each, on a
  remainder the algorithm usually wants anyway. Task 26 needed two such divisions
  (146097 and 36524) and found no exact form at any useful range for either; what
  made the rest exact was *restructuring* - splitting an era into centuries first
  drops the `/365` dividend from 146096 to 36524, under the bound. Reach for a
  different decomposition before reaching for more carries.
- **The `v * M < 2^31` bound is easy to check for the wrong variable and still
  ship.** Both `PLAN_TASK_34.md`'s leap-flag derivation and, independently,
  task 36's own local copy of it picked `M = 167773` for `/100` and `/400` by
  reasoning about the divisor rather than checking the bound against the actual
  dividend range: the biased year climbs to 46334 over the covered range, and
  `46334 * 167773` is over three and a half times past `2^31`, so a lane's
  signed 32-bit multiply wraps and the shifted quotient is silently wrong past
  roughly year 12400. No boundary list either task's differential used - 1900,
  2000, 2100, 2400, the usual century years - reaches that far, so the bug
  shipped past every targeted test both times; only an **exhaustive sweep of
  the real emitted kernel over the whole covered range** (16,777,216 days,
  seconds of wall time at these widths) found it, on the first and only day it
  could show up. The fix both times was the same round-down-plus-one-carry
  shape the paragraph above already prescribes for a large divisor
  (`M = 41943` at `k = 22`/`24`, the largest product `46334 * 41943` safely
  under `2^31`). The generalizable lesson: when a magic pair is *derived* by
  hand rather than found by the kind of exhaustive search
  `verify_long_lane_magic.py` runs, checking `M * dividend_max < 2^31` is a
  five-second arithmetic check worth doing explicitly before trusting the
  derivation's own prose - and an opt-in exhaustive sweep against the emitted
  kernel, not a curated boundary list, is what actually catches it if that
  check is skipped or miscounted.
- Those carries are not free, and the reason is the masked ops in them. Task 26
  predicted that its full-range variant would cost 5-12% over its narrowed one on
  op count alone (five ops on forty) and measured 14-24%: the five extra ops are
  masked adds and subtracts, which this project has separately measured at 2.3-2.9x
  an unmasked one. Count masked ops at their own weight when predicting.
- **`java.time` itself got 2.0x faster between JDK 17 and JDK 25**
  (`LocalDate.ofEpochDay(d).getYear()`: 236 against 479 M rows/s, same machine,
  `sql/varka/baselines/`). Any speedup quoted against a scalar `java.time` baseline
  has to say which JDK the baseline ran on, and a figure inherited from an older
  task may have a denominator two-fold different from today's. The same trap in the
  other direction: escape analysis scalarizes the `LocalDate` allocation in a tight
  loop, so a scalar calendar loop measured in a microbenchmark is far faster than
  the same code inside a query - task 26 predicted 15-30x over it and measured 3.7x.

- `VectorOperators` has no multiply-high on any lane type, and there is no sign that it
  will get one soon, so do not design around its arrival. Checked against JDK 25 (`javap`
  on `jdk.incubator.vector.VectorOperators`: `MUL` is the only multiply) and against
  openjdk/jdk master (code search for `MUL_HIGH` and `VECTOR_OP_MULHI`: no hits). C2 does
  have the operation internally - `MulHiLNode`/`UMulHiLNode` in `opto/mulnode.hpp`, used by
  `divnode.cpp` to lower scalar division by a constant and by the `Math.multiplyHigh`
  intrinsics, plus `MulHiLoLNode` for the fused 64x64-to-128 form - it is simply not exposed
  lanewise. The nearest request is JDK-8219881, "[vector] Optimized 32-to-64 bit vectorized
  multiply": an Enhancement, still Open, P4, filed February 2019, last touched October 2024,
  with `fixVersion` `repo-panama` rather than any release. The API does accept new integer
  ops when someone drives them - JDK-8338352 delivered `SADD`/`SSUB`/`SUADD`/`SUSUB`,
  `UMIN`/`UMAX` and the unsigned comparisons, all present in JDK 25 - so an RFE backed by a
  concrete workload is a real option, but it is a contribution to make, not a dependency to
  plan against.
- Because of that, full-range
  Granlund-Montgomery magic division is not expressible on int lanes - but a
  *range-narrowed* magic is: shrink the value first until the correctness condition
  (`v * e < 2^k`) and the no-overflow condition (`v * M < 2^31`) both fit in the low
  32 bits that `mul` does return. Mod-7 (task 14 follow-up, after a reviewer asked
  the right question): two 15-bit folds (`2^15 = 1 mod 7`) leave `v <= 32774` with
  the sign fixup, where `q = (v * 37450) >>> 18` is exactly `v / 7` with no final
  fixup - measured 1.6-1.8x the six-fold digit sum it replaced (which stays as a
  reference variant), ~9x lanewise `DIV` (no SIMD divide exists on x86; it
  effectively scalarizes), and ~57x a per-row `LocalDate` loop. The ~10-op-smaller
  method also cuts the per-task JIT warm-up above by ~28 ms per task.
- **The vector path is worth 6.5x over good scalar code, and the project had never checked.**
  `ChronoScalarOps` is `year(date)` as an ordinary Java loop over the same Arrow buffer, in the
  same 4096-row chunks, writing the same outputs: 284.2 M rows/s against the emitted kernel's
  1816.6 at AVX-512, both from the committed parity file. Until it was written, the only
  scalar anchor in that file was a per-row `LocalDate` loop, and the headline ratio was quoted
  against it. Keep the real
  baseline: "faster than `java.time`" and "faster than scalar arithmetic" are different claims
  and the second is the one that justifies the emitter.
- **`LocalDate.ofEpochDay(d).getYear()` in a tight loop does not allocate, and is a legitimate
  scalar baseline.** It measures 480.8 M rows/s, and C2 scalar-replaces the `LocalDate` -
  escape analysis sees it created and consumed in the same loop. It is worth stating because the
  opposite is the natural assumption and it was asserted out loud in this project before being
  checked. It also beats a hand-written 64-bit civil-from-days by 1.7x (480.8 against 284.2):
  `java.time` does the same Hinnant decomposition in 32-bit ints, and forcing everything through
  64-bit longs to buy exactness over the whole int32 range costs more than the exactness is
  worth in scalar code.
- **Spelling a constant division as `(x * M) >>> k` instead of `x / d` is worth 1.38x in scalar
  code** - 284.2 against 205.4 M rows/s for the same algorithm. C2 lowers `long / constant` to
  `MulHiL`, one instruction but a costlier one: it keeps the high half, and on x86-64 it
  clobbers RDX:RAX. An ordinary `imulq` plus a shift is cheaper wherever the product fits 64
  bits, which for a 32-bit dividend and a ~30-bit magic it does.
- **SuperWord will not auto-vectorize the calendar arithmetic, for two independent reasons,
  and neither is the `MemorySegment`.** The `(x * M) >>> k` form uses only `MulL` and
  `URShiftL`, both of which have vector counterparts (`MulVL`, `URShiftVL`), and the loop body
  was written branchless for exactly this reason. C2 still does not vectorize it:
  `-XX:-UseSuperWord` moves the number by 0.1% (284.1 against 284.4 in the A/B run). A
  four-case bisection -
  {`int[]`, `MemorySegment`} x {trivial body, full decomposition} - locates it: the trivial
  `int[]` loop gains 4.84x from SuperWord, the trivial `MemorySegment` loop only 1.09x, and the
  full body gains nothing on *either* (0.99x on `int[]`, 1.00x on the segment). The absolute
  figures in the committed parity file say the same thing: 26996.5 M rows/s for the trivial
  `int[]` loop against 6265.2 for the same body over a `MemorySegment`, and 287.1 for the full
  body on `int[]` against 284.2 over the segment - identical once the arithmetic is the real
  work. So the
  arithmetic is the binding constraint; segment addressing hurts as well but is not what stops
  it.
  `-XX:+TraceSuperWord` on a fastdebug JVM gives the mechanism exactly:
    - At the default `LoopUnrollLimit=60`, `SuperWord::transform_loop` is entered **zero
      times**. The body is too large to unroll, and with no pre/main/post structure there is no
      main loop for SuperWord to work on. It never gets asked.
    - At `-XX:LoopUnrollLimit=1000` it is entered four times and succeeds none. It builds packs
      - 8-wide `LoadI`, 8-wide `MulL` - and then
      `SuperWord::filter_packs_for_profitable` discards all of them
      (`WARNING: Removed pack: not profitable`), ending at `0 packs` and
      `SLP_extract did not vectorize`. The width mismatch is visible in the pack contents:
      eight 32-bit loads feeding 64-bit multiplies do not occupy one vector.
  The consequence for this project: the explicit Vector API is not a convenience over
  auto-vectorization here, it is the only route, and that is now measured rather than assumed.
  A lowering with no int-to-long mixing would clear the second gate, but the first is a tunable
  no shipped Spark can depend on.
- **The A/B that needs no tools**: run the case twice, once with `-XX:-UseSuperWord`. A loop
  SuperWord vectorized slows down; one it never touched does not move. That answers "did it
  vectorize" in a product JVM. Answering *why not* needs a fastdebug build - see the
  fastdebug section below.
- **Op counts bound a speed-up; they do not estimate one.** Three predictions in one milestone,
  all made from op ratios, all optimistic: sharing one decomposition across four calendar fields
  was predicted at 2.0x-3.2x and measured 1.5x; a scalar `year` was predicted within 2.5x of the
  vector kernel and came in at 6.5x; keeping fewer values live was predicted to help at narrow
  widths and lost at both. What the op-count model leaves out - stores, validity bookkeeping,
  loop control, dependency-chain latency - is routinely half the time or more. Predict a bound,
  say it is a bound, and measure.
- Masked lanewise ops and masked stores cost 2.3x-2.9x even when the mask is all-true:
  a runtime mask is opaque to C2 and a masked store never becomes a plain store. If
  masks carry no correctness (in-bounds accesses, invalid destination lanes declared
  undefined), run unmasked and keep validity in long words on the side.
- A vector held in a Java local across a loop pins one register for the whole body and
  blocks C2's rematerialization; ~32 such broadcasts collapsed throughput 7x. Emitted
  at each use, a loop-invariant broadcast gets hoisted when registers allow and
  rematerialized (one instruction) when they do not. Hoist only in measured-small
  regimes.
- Corollary for manual unrolling and software pipelining - the standard prescription
  for feeding a superscalar core, and a real gap, since C2 does not unroll Vector API
  pipelines: three of the measurements above price the experiment before it is run, and
  two of them cut against the prescription. (1) Unrolling by K multiplies the body's
  live temporaries by K, against a register file where ~32 pinned broadcasts already
  cost 7x - so unrolling and pre-broadcasting the loop's constants *compete* rather
  than compose, and K has to be varied together with the broadcast strategy, never
  alone. (2) It multiplies the loop method's op count by K against `GROUP_BUDGET = 16`,
  which exists because C2 compile latency is ~1 ms per vector op - affordable only
  since task 18 pays that once per shape rather than once per task. (3) It cannot
  rescue a chain built on lanewise `DIV`, which scalarizes: interleaving two scalarized
  chains is still scalar, so any such constant needs its range-narrowed magic first.
  None of this predicts that unrolling loses - it says the experiment has three known
  confounders. Registered as `PLAN_MILESTONE_4.md` task 25 (catalogue item 13);
  unmeasured as of this entry, and this bullet gets rewritten with the numbers when it
  is.
- Apply constant offsets *after* a mod, not before: `floorMod(days + 4, 7)` overflows
  int for days near `Int.MaxValue`, while `(floorMod(days, 7) + 4) mod 7` cannot.
  Negative inputs are where every strength-reduced mod goes wrong silently - a test
  range that never crosses zero proves nothing. The rule is about avoiding overflow,
  though, not an end in itself - it inverts when the oracle's own arithmetic already
  overflows on purpose. `next_day` (`PLAN_TASK_33.md`) computes `k - d` *before* the
  mod because Spark's `getNextDateForDayOfWeek` computes it in plain wrapping `int`
  arithmetic; reducing first disagreed with the row engine on 28 boundary cases in the
  planning pass's own check. Whose arithmetic the oracle is - exact (`LocalDate`,
  never wraps) or wrapping (plain Spark `int` math) - decides which way this rule
  points, and the reflex answer for one is the wrong answer for the other.
- A fixed-width species literal (`IntVector.SPECIES_256`) is not a safe way to get "the
  int species with half `LongVector.SPECIES_PREFERRED`'s lane count": under
  `-XX:MaxVectorSize=16` (this project's narrow-vector CI shape, 128-bit), no 256-bit
  registers exist, and the mismatch surfaces as a `VectorIntrinsics` bounds exception
  at a `fromMemorySegment` call site far from the real cause. Derive the matching
  species instead: `VectorSpecies.of(int.class,
  VectorShape.forBitSize(longSpecies.vectorBitSize() / 2))` tracks whatever
  "preferred" resolves to at the JVM's actual configured width, including the narrow
  shape. Any code pairing two lane types by a literal `SPECIES_*` constant needs the
  same check.
- Buffer alignment is not a null hypothesis once the buffer fits in cache: a 64-byte
  (AVX-512 register width) misaligned start costs 1.6-1.7x throughput at a 4096-row
  (one Spark batch, L1/L2-resident) working set, and 1.2x at 128-bit - reproducible
  across repeated runs at both widths (`VarkaMilestone4MeasurementsBenchmark`,
  `PLAN_MILESTONE_4.md` section 8). The same misalignment costs under 2% on a
  multi-megabyte streaming buffer, where DRAM bandwidth dominates and hides it -
  measure at the working-set size the real kernel runs at, not whichever size is
  convenient to allocate once. A 2-way unrolled kernel loses the same 50-60% as the
  non-unrolled one: unrolling does not hide a cache-line-split load.
- A materialization strategy's ranking can flip across the two vector widths this
  project already tests at. Packing a comparison mask straight to its output bitmap
  (skipping an intermediate int 0/1 column) wins by 1.16-1.18x at AVX-512 but *loses*
  by 1.40-1.51x at 128-bit (`VarkaMilestone4MeasurementsBenchmark`). A single
  same-JVM run at the development machine's native width is not enough evidence for
  a strategy that has to also hold at the narrow-vector CI shape.
- When a benchmark's K=1 case is fully unrolled straight-line source (the shape a real
  emitted kernel carries), the K>1 cases must be too - a small constant-bound runtime
  `for` loop over the op index is not a safe stand-in for hand-unrolled code, even
  though C2 usually fully unrolls tiny fixed-trip-count loops itself. Measuring
  `VarkaUnrollFactorBenchmark`'s K=2/K=4 cases through such a loop first showed K=4
  losing 30-60% on some shapes; rewriting them as straight-line interleaved code (same
  shape as K=1, just K independent lane groups instead of one) turned that into a
  reproducible +4-6% win on the shape where unrolling should help at all. The first
  number was an artifact of comparing a loop-shaped baseline against a straight-line
  one, not a real unrolling cost - a benchmark comparing "K=1" against "K>1" has to
  keep every other structural choice, including loop-vs-straight-line shape, identical
  between the arms.
- A hand-written kernel standing in for emitted code must not introduce a method
  boundary the emitted code does not have, and "it is a small private helper, it will
  inline" is not something to assume. Task 32 built a kernel to price sharing one
  civil-from-days decomposition across `year`/`month`/`dayofmonth`/`quarter`, wrote the
  decomposition as a `computeFields` helper returning a record of four `IntVector`s, and
  measured it 1.9x *slower* than the four independently emitted nodes - and task 32 was
  declined on that number. `computeFields` compiles to 376 bytecode bytes; C2's
  `FreqInlineSize` is 325, so it never inlined, so escape analysis never saw the record's
  allocation and its consumers in one compilation unit, so the record and its four vectors
  were really heap-allocated once per lane group. `VarkaLoopEmitter.emitChrono` emits zero
  call boundaries in its lane path. The kernel was measuring a Java abstraction the thing
  it modelled does not have. **Two cheap checks that would have caught it before the
  number was believed**: `javap -c -p` for any method holding lane arithmetic that exceeds
  325 bytes, and `-XX:+PrintInlining` (narrowed with
  `-XX:CompileCommand=option,Class::method,PrintInlining`) for a `failed to inline` inside
  the loop. Rebuilt hand-inlined, the same kernel runs 1.5x *faster* than the four nodes.
- An op-count ratio bounds a sharing win; it does not estimate one. The same task 32
  kernel shares ~45 of each field's ~50 vector ops, so four fields cost ~200 ops separate
  against ~65 shared - a 3x op-count ratio, which was registered as a prediction of
  2.0x-3.2x throughput. Measured: **1.51x-1.54x** at AVX-512 across three runs, the
  committed parity file's being 679.0 against 445.7 M rows/s. The half that went missing is
  everything the model ignored - four stores, four validity-bitmap read-modify-writes, the
  chunk prologue, loop control - none of which sharing touches. Predict a bound, not a number.
- Once a lane path has no calls left in it, `-XX:CompileCommand=inline` buys nothing, and
  it was never a fix anyway - Spark cannot require a `CompileCommand` on a user's JVM, so a
  flag that helped would only be a diagnostic pointing at a code change. Task 32 tested it
  properly before concluding that: `-XX:+PrintInlining` showed the two `VarkaVectorSupport`
  validity helpers genuinely failing to inline inside the shared loop
  (`NodeCountInliningCutoff` on one compilation, `callee is too large` on another - 212
  bytes of a four-arm switch on the lane width that a constant lane count would fold away),
  and forcing them in changed nothing at either vector width. Nor did forcing every Varka
  class (`inline,*varka*::*`). This is the third time an inlining flag has moved under 1% in
  the catalyst parity harness while the engine's JMH harness moves 50-190% on the same flag;
  a flag worth that much in only one harness is measuring the harness.
- A kernel can have two stable machine-code outcomes and no reachable reason. Task 32's
  shared kernel is bimodal at 128-bit: 121 ms or 85 ms, stdev 0 ms inside a run and 42%
  between runs, 3 fast outcomes in 14 runs. Neither forcing inlining, nor disabling
  on-stack replacement, nor rescheduling the body to keep fewer values live made either mode
  deterministic or shifted the distribution. **Report both modes; never average them** - the
  mean describes a state no run is ever in - and treat "which compilation the JVM landed on"
  as a first-class outcome rather than as noise to be smoothed away.
- Keeping fewer values live is not automatically faster, and the intuition is worth
  distrusting. Task 32 built a variant of the same kernel that hoisted the year assembly so
  three intermediates died early and stored each of four outputs the moment it existed
  instead of all four at the end. It lost at both widths (633.0 against 679.0 M rows/s in
  the committed parity file, 156.5 against 165.6 at 128-bit). C2's scheduler did better with
  the wider window than with the shorter live ranges - and the losing shape is the one the
  emitter naturally produces, which is worth knowing before assuming emitted code will match
  a hand-written ceiling.
- The same sharing win is width-dependent, and that is where task 17's register-pressure
  finding actually lives. At 128-bit the identical kernel is a wash: 1.06x in four runs of
  five, 1.50x in the fifth, stdev 0 ms inside each run and 42% between them - a
  compilation the JVM either finds or does not, so the two modes must be reported rather
  than averaged. Five live intermediates plus four outputs fit comfortably in 32 zmm plus
  8 dedicated mask registers and marginally in 16 xmm that must hold masks too; C1 refuses
  the 936-byte body outright at both widths ("out of virtual registers in linear scan").
  Task 17's `GROUP_BUDGET` result (raising it to keep two outputs' cross-output CSE in one
  method lost 4119.9 against 2928.2 M rows/s, current committed parity file) is the same
  effect. It sets a ceiling on how much sharing can win; it does not decide the sign, and
  a narrow-vector measurement is not optional for anything that shares live values.

## Generated Code Can Carry Its Own Debug Info (Class-File API)

- `CodeBuilder.lineNumber(n)` needs no options or flags: a `LineNumberTable` lands in
  the emitted method, and with a `SourceFile` attribute the JVM fills in file and line
  on every stack frame through the generated code - so a generated loop can name the
  *IR node* that threw, not just the method. Place the marker immediately before the
  node's own defining instruction, after its children are emitted: a marker at the
  start of a post-order case attributes the parent's op to whichever child was emitted
  last.
- Pick line numbers from a property of the IR (task 16 uses the children-before-parents
  topological index), not of the emission order, and record the decoding key inside the
  class - a custom attribute is the natural place, since it travels with the bytes into
  a heap dump or a `javap` capture.
- A custom attribute's payload is fixed-width: adding a field means updating the
  `attribute_length` the writer emits (4 -> 6 for two -> three constant-pool refs) *and*
  the reader's offsets. They are two sides of one format and belong in one commit.

## The Class-File API's Stack-Map Generator Is a Free Verifier

- `ClassFile.of().build(...)` computes stack map frames and rejects inconsistent
  operand stacks at *emit* time (`IllegalArgumentException` naming the bytecode
  offset, with a full instruction dump). A double-store bug in task 11 never reached
  the JVM - one layer earlier than the `ClassFile.verify`-before-load discipline,
  and two earlier than a runtime `VerifyError`.
- Member-resolution mistakes (wrong erased descriptor) still pass both build and
  verify and surface at first execution as `NoSuchMethodError`; keep the
  wrong-descriptor negative-control test so that failure mode stays diagnosable.

## Independent Reference Evaluators as Test Oracles

- For an algebraic surface (three-valued logic, null-skipping picks, blend
  semantics), implement the semantics *twice*: the generated code, and a tiny
  interpreter over the same IR inside the test suite (`Option[Int]` values,
  `Option[Boolean]` Kleene conditions). Run matrices against it row for row. Wrong-
  in-the-same-way bugs are unlikely across two representations that share nothing.
- A fold's *association* is not its *effect order*: a monadic `foldRight` over
  CASE branches evaluated the ELSE first and registered input ordinals right-to-left.
  Where side effects assign identities (ordinals, slots), compile in source order
  explicitly, then fold the already-compiled pieces.

## Testing Under AQE

- Every Varka suite session disables AQE for plan determinism, which silently leaves
  the default-config path (AQE on) unpinned. It worked - but only an experiment
  proved it.
- Under AQE the fused node sits inside a query stage, and a query stage is a *leaf*:
  `SparkPlan.collect`/`collectFirst` never descend into it, so a naive assertion
  reports "not fused" while the node is right there in `treeString`. Traverse with
  `AdaptiveSparkPlanHelper` in AQE tests.

## Write the Prediction Down, Then Measure

- Three perf predictions in this repo's plans were reversed by measurement: "the
  dense path won't beat masked-with-all-true" (it won 2.3x-2.9x), "the masked body
  needs masked ops" (unmasked + validity words doubled mixed-null throughput), and
  "no cliff at the op cap" (there was one, and it moved). JIT-adjacent performance
  intuition loses often enough that the plan should record the expectation, the A/B,
  and ship whichever wins - the written-down prediction is what makes the reversal
  visible and the numbers re-checkable.
- **Check what a benchmark never executes before believing what it says about a
  change there** (task 24). Every committed harness in this repo happened to be
  lane-aligned - this file's parity benchmark ran one call over 1,000,000 rows, the
  engine JMH's sizes are 32 / 10000 / 1000000, and Spark's default
  `COLUMN_BATCH_SIZE` is 4096, all multiples of 4, 8 and 16 - so `loopBound ==
  length` everywhere and the emitter's scalar remainder path had never executed a
  row under measurement. Any remainder-handling change was invisible to every
  committed number. When the code under test has an aligned fast path and a
  remainder path, the size ladder needs sizes like 4095 and 63 on it deliberately;
  a pair one row apart isolates the remainder (equal call counts), and a magnified
  pair (64/63) makes a per-row cost measurable that a 4096-row batch hides in
  noise. Two more measurement lessons from the same task: a cost quoted at one
  rung of a ladder is not a bound on the whole ladder (task 21's "~1-3 ns/row"
  copy cost, read as a ceiling, under-predicted the compress win threefold - the
  scalar copy grew with selectivity and the ceiling was one point on that curve);
  and an in-run control (cases the change cannot affect, measured in the same
  process) is what turns "the numbers moved" into "the noise floor is 15% and the
  effect is inside it".
- **A task that shrinks emitted bytecode must regenerate the committed benchmark
  file, or the next task to regenerate inherits its win** (task 48, measured).
  Task 51 removed the per-extraction range guard - two compares plus mask work on
  every calendar node's tail - and shipped without regenerating
  `VarkaEmitterParityBenchmark-jdk25-results.txt`. Task 48's regeneration
  therefore showed `year, null-free` moving 1823.4 to 2166.5 M rows/s, a fifth,
  for a change whose own A/B measures 1.01x. Three things separated the two, and
  all three are worth reproducing: an **in-run control** (`per-row LocalDate
  year`, which no Varka change touches, read 481.1-481.6 against a committed
  479.4, proving the machine had not drifted); an **in-run A/B** (both sides of
  the change as adjacent cases in one `Benchmark`, which is what actually
  isolates the task); and `git log` on the results file itself against `git log`
  on the emitter directory, which named the two commits that had landed in
  between and left exactly one candidate. Without the first two, a plausible and
  entirely false 21% could have been written down.
- Debugging corollary from the same stretch: before concluding files changed or
  vanished, verify the working directory. A shell whose cwd resets between commands
  plus relative paths fabricates convincing evidence of disaster; absolute paths in
  forensics, always.

## Reading a paper into the repo

- **An extractor that drops things beats one that invents them.** Varka keeps
  machine transcriptions of load-bearing third-party papers in
  `sql/varka/papers/`, and the conversion is deliberately mechanical: the text is
  rebuilt from `pdftotext -bbox-layout` word geometry, where a glyph smaller than
  its line's body and off its baseline is a superscript or a subscript. A plain
  `pdftotext` is useless for a maths paper - it flattens every script, so `2^16`
  becomes `216` and `N_C` becomes `NC` - while the geometric pass recovered 1408
  scripts across 35 pages with seven misses, all of them tokens the PDF had
  already merged.
- **`marker-pdf` was measured against that and rejected, on five pages.** It is
  genuinely better at what the geometric pass loses: eight displayed formulas
  came out as correct LaTeX with their tall delimiters intact, plus 95 table rows
  including assembly listings the geometric pass drops entirely. But on the same
  five pages it silently closed three half-open intervals (`[0, U[` to `[0, U]`,
  twice more elsewhere) and dropped a digit from an eleven-digit validity bound
  (`10441974239` to `1044197429`), and mangled two glyphs in prose. Every one of
  those reads as plausible. A model-based pipeline never leaves a gap where it
  could not read something - it produces something reasonable instead - so its
  output cannot be trusted for the constants and ranges that are the whole reason
  to have the paper. Note it also needs a `llama-server` binary since surya 0.22
  dropped the torch backend, and its `pillow<11` pin does not build on Python
  3.14.
- The general shape: **for anything a plan will quote as a number, prefer the
  extractor whose failure mode is a visible hole.** Then say in the file what the
  hole is, and point at the PDF for the parts that did not survive.

## A reciprocal's top bits are a remainder, and other things a neighbouring codebase had

A review of `datealgo-rs` (Nuutti Kotivuori's Rust port of Neri-Schneider, with Cassio Neri as a
contributor), done after task 53 had shipped the same month block. The papers in `sql/varka/papers`
give the algorithms; a production port by people who have already fought the constants is a second
source worth an hour, because it shows which corners the paper leaves to the reader. Four things
came out of it, each checked exhaustively over Varka's day range rather than taken on trust.

**Weekday from the top bits of a truncated reciprocal.** `((m + k) * floor(2^32 / 7)) >>> 29` is
`(m + k - 1) mod 7 + 1` for every `m` up to about 1.34e8 (checked to that bound at a 2^16 step, and
over all 16,777,216 days of the narrow range with zero mismatches). The multiply wraps modulo 2^32
on purpose: the top three bits of the wrapped product are the fractional part of `m / 7` to three
bits, which is the remainder. Three ops. Today's `dayofweek(col)` body is 19 IntVector ops, of
which the two-fold mod-7 is about 16; `weekday` and `next_day` carry the same fold. This is the
largest single saving found since the calendar family started, and it is **range-bounded**: the
fold is exact for every int32 day, the reciprocal only inside the narrow range, so it belongs
inside the range task 52's compile-time analysis guarantees a calendar input (it would make
`dayofweek` a range-checked node like the extractions), as a fourth `FloorMod7` variant with the
fold kept as the total-range reference. It is a task of its own after the emitter settles, not a
rider on another PR.

**The Thursday rule for the ISO week.** `weekofyear` planned as "provisional week, then two
year-boundary corrections and a weeks-in-year helper". `datealgo-rs` does it as: move to the
Thursday of the same week, `t = d + 3 - weekday0(d)`; the ISO week-year is that Thursday's year
and the week is `(ordinal(t) - 1) / 7 + 1`. Varka already has the January ordinal from task 34, so
the whole rule is the weekday, a shift, the day-of-year over `t`, and one exact division. Same op
count as the planned design, but both boundary corrections and the helper vanish by construction,
and the test burden with them. Row 37 in `PLAN_MILESTONE_4.md` now says so.

**Days-from-civil without the era split.** `emitDaysFromCivil` (task 40, used by `add_months`)
does `era = y / 400` with a carry, then `century = yoe / 100` with a carry. `datealgo-rs` writes the
year part as `1461 * y / 4 - c + c / 4` with `c = y / 100`: equal to the era/yoe/century form over
all 102,500 biased years, `1461 * y` fits int32, and the `/400` division and its carry step are
gone. Worth about six ops off `add_months`'s 117. The `/100` still needs its correction step: no
exact magic exists on that domain with the product under 2^31 (re-checked, k = 16..31).

**A closed-form month length.** `30 | (m ^ (m >> 3))` is the length of every month except
February, for the January-based `m` in 1..12. Three ops against `last_day`'s two `emitMonthStart`
calls and a subtract, but the January month costs two ops from the March axis and February still
needs its blend, so the net is a few ops. Recorded; not worth a task on its own.

**Not taken.** `is_leap_year` there is the branchy `y % 25` form; Varka's Hueffner hash is four
branchless ops and stays. The century and year steps of `rd_to_date` use a 64-bit multiply-high,
which int lanes cannot express; they become borrowable verbatim when task 49 brings int64 lanes.

The general lesson is the admission check: every one of these was a claim about a constant over a
range until the range was swept. The month-length identity took twelve cases; the weekday trick
took the full narrow range plus a search for where it stops holding, because a trick that is exact
to 1.34e8 and needed to 1.68e7 has eight times the headroom, and that number is the thing to write
down, not "it works".

## Validate a fixed-format string with a saturating subtraction

From Daniel Lemire's `sse_date.c` (2023, "Parsing time stamps faster with SIMD instructions"), read
for a `cast(string AS DATE)` fast path; the design it produced is under milestone 4's item 8. The
general lesson is independent of dates.

A fixed-format string is a row that is either exactly in shape or not the kernel's business, and
the cheapest way to decide that for a whole row at once is not a compare per field but **one
saturating unsigned subtraction against a per-position limit vector**. XOR the bytes with `0x30`
so digits become 0..9 and every other byte becomes something large; subtract, saturating at zero,
the largest value each position may hold (`9` for a free digit, `1` for the leading digit of a
month, `3` for the leading digit of a day, the XORed separator for a separator). A byte in range
leaves zero; anything else leaves a residue. Where a per-byte limit is too loose - months 13..19
pass a leading-digit test - pair the bytes into two-digit values and subtract again against the
field's limit. Subtract the other way against a minimum vector to reject zero, and put the
separator's own value in that vector too, since an upper bound alone lets a digit sit where a dash
belongs. OR the residues and
test for all-zero: one mask for the row, no branch per field, and the failing rows go to the row
engine. It is the same discipline as task 26's range guard applied to bytes - the kernel checks
that the row is the shape it compiled for and declines the rest, rather than parsing.

Two facts that make it expressible here. JDK 25's `VectorOperators` has the saturating operators
(`SUSUB`, `SUADD`, `SSUB`, `SADD`, `UMIN`, `UMAX`; checked with `javap` on this machine), so the
trick needs no compare-and-blend emulation. And the digit *combine* that follows does not need
x86's byte multiply-add: with the digits packed into a long lane, the SWAR ladder from Lemire's
2018 `eightchartoi.c` - multiply by `1 + (10 << 8)`, shift, mask; multiply by `1 + (100 << 16)`,
shift, mask - leaves the two-digit fields in 16-bit slots after two steps, which for a date is
where to stop.

What the same repository does *not* have, so nobody looks twice: any SIMD date or time
*formatter*. Its integer-to-string work (the 2026 IFMA paper, eight digits in two `vpmadd52`
instructions) rests on a 52-bit multiply-add the Vector API does not expose; what survives for a
future `date_format` is only the idea of producing all eight fixed-width digits with lane
multiplies and inserting the separators with a shuffle, paired with ClickHouse's template-and-patch
(milestone 6, section 6).

## Velox is a semantics reference for the calendar family, not a performance one

Read in September 2026 for the same question as ClickHouse, `datealgo-rs` and Lemire's repository:
is there anything to borrow. There is not, and the reason is worth one paragraph so nobody reads it
again for speed. Every Spark-compatible date function in `velox/functions/sparksql` converts the
day to a `struct tm` through a full civil decomposition and reads one field, one decomposition per
row per function with nothing shared. Since May 2026 (PR #17371, `velox/type/FastDate.h`) that
decomposition is Neri-Schneider's reference code with era shift 82 - the same month block task 53
shipped, the same `1461 * y / 4 - c + c / 4` and `(979 * m - 2919) / 32` inverse the `datealgo-rs`
review recorded, and the 64-bit year multiply task 49 is waiting for. Their measured gain from the
swap was 1.6-1.9x end to end on `month` and `day`, none on `year(date)`. ISO week goes through
Howard Hinnant's `iso_week.h`; `yearofweek` keeps the two boundary corrections task 37 dropped for
the Thursday rule; `next_day` is `start + 1 + floorMod(dow - 1 - start, 7)` off the day number, as
task 33 does. No datetime file contains SIMD. The only SIMD near expressions,
`SIMDComparisonUtil.h`, computes 64 comparison bytes and packs them into a bitmask, which the
Vector API gives Varka as `VectorMask.toLong()`.

What Velox *is* good for: its Spark-compatibility tests were written by people who had to match
Spark exactly, and they are a second, independent list of the edge cases worth pinning.
`sparksql/tests/DateTimeFunctionsTest.cpp` has 56 cases; task 37's row now names the `weekOfYear`
set as fixtures to import, and the `addMonths` and `makeDate` sets are a cross-check for tasks 40
and 42. Its string-to-date cast is a character loop over exactly the Spark grammar milestone 4's
item 8 sends to the fallback - optional sign, at least four year digits, optional `-[m]m` and
`-[d]d`, then end, space or `T` - which confirms that design's shape mask covers the right subset.
Velox also ships `DateExtractBenchmark` and `FormatDateTimeBenchmark` over 1024-row vectors fuzzed
within 67 years of the epoch; an external scalar-engine reference number is available from them at
the cost of a Velox build, and that ~67-year range is a fair data-shape argument in milestone 6's
item 10 cache question.

## The Julian map: one division stage fewer in civil-from-days

From `benjoffe/fast-date-benchmarks` (Ben Joffe's fork of Neri and Schneider's harness, with his
own algorithms from four posts, 2025-2026), read in September 2026. The fifth codebase read for the
calendar family and the first that changes Varka's arithmetic.

Neri-Schneider, and Varka's prefix after it, take the day of era to a century, then a year of
century, then a day of year, and pay for the leap day at the year step with an underflow
correction. Joffe removes the middle stage. Scale the day by four first, `qds = 4 * doe + 3`; the
century is `qds / 146097` (146097 / 4 is 36524.25, the mean century). Then add four back per
century, `jul = qds + 4 * cen` within an era (the general form subtracts `cen & ~3` as well, which
is zero inside one era): that maps the Gregorian count onto a calendar in which every fourth year
is leap without exception, and in that calendar `jul / 1461` is the year and `(jul mod 1461) >>> 2`
the day of year, Feb 29 included, with no leap test at all. The `+ 3` also puts the era's last day
in century 3, so the `cen == 4` fold goes too.

Checked here in Varka's terms - 32-bit low products, round-down magic, one carry per division -
over all 146097 days of an era against Python's calendar: zero mismatches with
`cen = (qds * 1837) >>> 28` and `yrs = (jul * 2870) >>> 22`, largest product 1677225130, one carry
sufficient for each (46 and 8627 of the 146097 days take it). Against `emitChronoPrefix` today
that is century 10 ops to 7, year 13 to 10, year assembly 5 to 3, and one correction stage fewer
on the dependent chain. It is task 54, run as task 53 was: a variant, an A/B, both widths.

**Measured, task 54 (`PLAN_TASK_54.md` 9).** Shipped as the default. The A/B in one run, Julian map
against century-then-year: `year` null-free 3444.2 against 2746.4 M rows/s at AVX-512 (+25%) and
1333.0 against 1054.5 at 128-bit (+26%, from the committed 128-bit companion file); four fields
unshared +19% and +20%; `add_months` +3% and +4%; the mixed-null rows +14% and +8%. The op-count prediction was 8-12% and the reason it was under
by half is the lesson: the five ops that went were a serial stage - a compare, a leap-flag mask,
three masked fixes, each waiting on the last - on a body that is latency-bound on its chain, so
they were worth their depth, not their count. Count ops to predict a throughput-bound body; count
dependent stages to predict this one. The same run moved the epilogue's `HugeMethodLimit` ladder a
fourth time (21 unshared, 44 shared), which is the fixture every prefix change moves and every
prefix plan should list.

Three neighbours of the idea, for the record:

- **Blend the constant, not the result.** Joffe picks the numerator's offset before the multiply
  rather than fixing the quotient after the shift. A January offset of `197913 - 12 * 65536` on
  task 53's month numerator makes `num >>> 16` the final month, and the low half is untouched so
  the day formula still holds (checked over all 366 days); `979 * 12 - 2919 = 8829` does the same
  for the month-start formula. The op count does not move; the blend leaves the critical path.
  That is a change for the instruction harness of task 31 to see, not for a ratio.
- **The rest of `fast64` needs a multiply-high.** The year multiply's low bits feed the month step
  directly, and `(yrs % 4) * constant` absorbs the leap day; four multiplies for the whole date
  where Neri-Schneider takes seven, about 40% faster in his scalar measurements. The Vector API has
  no multiply-high on any lane, so these wait for task 49's long lanes and its exact low products,
  where the admission check should now try the two-division form beside the three-division one.
- **The bucket technique** is the guard-free int-lane total if task 49 fails its gate: choose an
  approximate era by a shift, reduce the day into a window, fix the year up by `bucket * 2800`.
  About 14 ops against task 26's `TOTAL` at 16, without the deliberate wrap; the eight-entry offset
  table in his `article_2_l1` is one lane permute on a 256-bit int species.

Confirmed and left alone: `emitLeapFlag`'s Hueffner hash is the fastest leap test in Joffe's own
leap benchmark (0.79x of the Drepper-Neri-Schneider form on x64), and his signed variant is six
lane ops to its four; both are exact over Varka's biased year range.

## A GPU port of Spark's date code is scalar code run per thread

`NVIDIA/spark-rapids-jni` was read in September 2026 on the guess that a lane-per-thread engine
with Spark's exact semantics would have solved Varka's branch-free problem for string parsing and
time zones. It has not, and the reason is worth keeping so the guess is not made again: CUDA
tolerates divergence, so its kernels are ordinary scalar C++ - `while (pos < end)` digit loops,
early returns, a per-row `switch` on the format string - run once per thread. The arithmetic under
them is Hinnant's `civil_from_days` with plain divisions and a weekday by `(days - c) mod 7`. None
of that shape transfers to Vector API lanes, where a divergent row costs the whole vector.

What it contributes is the residue of having matched Spark to the row: the trim definition
(`c <= 32 || c == 127`), the year-digit and year-range limits, the trailing `T`/space rule and a
fixture list for the string-to-date fallback (milestone 4, item 8); the ANSI protocol of parsing to
a nullable column and failing the batch if nulls appeared, which is Varka's status bit; and a
production instance of the transition-table timezone design milestone 4's item 2 already names -
two sorted instant arrays per zone, DST rules taking over past the table's end, and the
floor-versus-truncate decision at a gap that is wrong by an hour if made the other way. What it
does not contain is any extraction: `year` and its family live in cuDF on `cuda::std::chrono`.

## Every operator the plans rely on is one instruction; two species in one JVM is a box per iteration

Established in September 2026 from the product JDK 25's own C2 output on this machine (AMD Ryzen AI
9 HX PRO 370, `UseAVX=3`, preferred int species 512 bits), with hsdis borrowed from the fastdebug
build's `support/hsdis` directory via `LD_LIBRARY_PATH` - the product JVM loads it from there, so
the assembly is production C2's. Probe, logs and scripts: a `Probe.java` with one loop per
operator, `-XX:CompileCommand=print,Probe::op_*`, and a script that splits the log per nmethod and
counts mnemonics and Java call sites.

**The operator table.** Every operator the six codebase reviews put into plans is intrinsic at 256
bits and compiles to the instruction one would hope for, with no call back into Java:

| operator | instruction | for |
|---|---|---|
| `SUSUB` on bytes (256 and 128) | `vpsubusb`; `vpcmpneqb` to a k-mask and `kortestd` for `anyTrue` | item 8's shape mask |
| unsigned compare, ints and longs | `vpcmpltud` / `vpcmpltuq` to a k-mask, `kmovq` for `toLong` | leap hash, shape mask |
| `selectFrom` on 8 ints | `vpermd`, alone | eight-entry tables |
| `rearrange(ix.toShuffle())` on 8 ints | `vpermd` plus four wrap ops (`vpcmpeqd`, `vpsubd`, `vpblendmd`, `vpand`) | use `selectFrom` instead |
| index-map gather, 8 ints | `vpgatherdd` plus a five-op Java-side index check and `kxnorw` | item 10; the check is not removable through the API |
| `LongVector.mul` by a constant, then shift | `vpmullq`, `vpsrlq` | task 49; native here because of AVX-512DQ+VL, a three-multiply emulation on plain AVX2 |
| byte `rearrange` with a constant shuffle | `vpermb` | item 8's three-rows-to-long-lanes compaction; needs VBMI, present |
| `IntVector.mul` then shift | `vpmulld`, `vpsrld` | the baseline everything else is measured against |

The x86 match rules in `src/hotspot/cpu/x86/x86.ad` (`match_rule_supported_vector`) agree with all
of it, but they say what *can* match; only the disassembly says what a given loop got.

**The finding that outranks the table.** A first probe used `SPECIES_256` and `SPECIES_128` of the
same lane types in one process, and in that JVM the byte, permute and gather loops each carried a
heap allocation per iteration - a TLAB bump, a mark-word store, an `int[8]` payload, and the loaded
vector written into it - while the multiply and compare loops did not. The identical methods copied
into a class touching one species were clean, and the first probe became clean when its 128-bit
methods were never called. `-XX:CompileCommand=PrintInlining` names the mechanism: in the polluted
JVM the shared `ByteVector` templates inline bimorphically, "callee changed to
`Byte128Vector::lanewise`" beside the `Byte256Vector` path at the same call sites, so the receiver
must exist as an object for the other branch and the box survives. Same loops, same flags,
polluted against clean: saturating subtract 2.06 against 0.32 ns per vector (6.4x), `selectFrom`
3.06 against 0.24 (12.8x), gather 6.47 against 2.39 (2.7x), long multiply unchanged.

**Assert it as a rate, not as sites** (task 55, `PLAN_TASK_55.md`). A count of allocation sites
in the disassembly cannot separate a per-call setup object from a per-iteration box:
`ChronoVectorOps.vectorFourFields` carries four `NativeMemorySegmentImpl` views C2 never
scalar-replaces, one allocation per call, and an allocation's slow path jumps backwards to its
retry point, so a backward-branch range is not a loop. The assembly suite therefore measures
`ThreadMXBean.getThreadAllocatedBytes` around a thousand calls at steady state and allows one byte
per row; a box is at least 5 per row, a per-call view under 0.25. And whether a bimorphic template
boxes depends on the shape *and the order the profiles filled in*: a `selectFrom` lookup boxes
only when the second species ran hot first, an index-map gather boxes under either order at both
widths. The suite's positive self-test is the gather for that reason.

What this means here. The emitter and every kernel use `SPECIES_PREFERRED` only, and the 128-bit
gate is a separate JVM under `MaxVectorSize=16`, so production and the catalyst harness are safe by
construction - keep them so: never introduce a second species of a lane type, not for a half-width
load, not for a test, not for a benchmark that shares a JVM with anything else.
`VarkaMilestone4MeasurementsBenchmark` did exactly that with its half-width int species in a
`forks = 0` JVM, which is one named cause of the engine harness's degraded state (the debt register
in `PLAN_MILESTONE_4.md`). Two tells, either sufficient: an allocation inside a kernel loop body in
the disassembly (task 55 makes it an assertion), and a "callee changed to" line naming a second
species class in `-XX:+PrintInlining` output.

### The runtime half: the evaluator samples allocation, because nothing else can see a box

A boxing kernel is correct, so the ghost fallback, the differential suites and the fuzzer are all
blind to it; the assembly suite (task 55) catches it in the test JVM, and the test JVM is clean by
construction. `VarkaKernelEvaluator` therefore samples the same signal at run time: bytes the thread
allocated across `run`, from `ThreadMXBean`, on a schedule that skips the JIT warm-up (an
interpreted or C1 Vector API loop allocates every vector, which C2's escape analysis then removes -
sampling batch 1 would report boxing that is about to stop; the evaluator's wiring test watches
exactly that, every early sample suspect and the tail clean at a constant 520 bytes per call). How
long the warm-up lasts is the machine's business: this laptop had C2's loop inside 300 batches of
1024 rows, GitHub's runner took 1112 - so the wiring test runs rounds of batches until a whole round
samples clean rather than asserting a fixed tail, and a host that slow sees the default schedule's
first two samples (512 and 1024) both inside the warm-up, which is one spurious warning. Batch 512
first, then powers of two and every 4096th - two million rows at the default batch size; suspect
above 4 KB plus one byte per row; one warning per task on two consecutive suspect samples, a
`numSuspectAllocationSamples` metric, and a `KernelAllocation` JFR event on every sample. The decisions live in `VarkaAllocationSampler` and are unit-tested against a loop that
allocates on purpose - the positive case is not a polluted Vector API loop, because making the shared
test JVM box would degrade every vector suite after it.
## A range guard belongs where the value is made, not where it is read

Task 52. Task 26 checked the narrowed civil-from-days range at every calendar extraction, per
lane, per batch; task 51 removed that on the argument that the range is decidable once; task 52
is the decision. Three things came out of building it.

- **Most of the guard is a compile-time interval.** A date column is the contract range
  0001..9999 (`VarkaChrono.CONTRACT_MIN/MAX_DAYS`, derived from `LocalDate` in source), a literal
  day offset shifts it by exactly its value, `next_day` by 1..7, `add_months(n)` by 28n..31n,
  `last_day` by 0..30, and `greatest`/`least`/`if`/`coalesce` take the hull. The check is one
  compare of that interval against `NARROW_MIN/MAX_DAYS` in the compiler's calendar arms
  (`dayRange`/`calendarInput` in `VarkaExpressionCompiler`), and it costs nothing at run time.
  The slack is large - 8449747 days forward and 4675410 back from the contract - so the
  corpus never trips it, and a query that does is computed by the row engine with the interval
  named in `EXPLAIN`.
- **A date-typed calendar output is not "back in range".** The tempting rule "a calendar node's
  output re-enters the contract" is false for `last_day` and `add_months`: their input passed
  the check at their own arm, but the output is up to 30 days (or 31 per month) later, so a
  second calendar node over it needs the child's interval plus that bound. The analysis
  propagates the interval through them for exactly this reason; the +-1 tests at the bound
  are what keep the rule honest.
- **The runtime half is one producer, guarded once, behind an option.** The only shift the
  compiler cannot see is a column offset (task 38), so `AddDays`/`SubDays` with a `ColumnRef`
  offset under a calendar node re-emit task 26's guard block on their own result
  (`emitRangeGuard`), ANDed with the node's validity word (a null offset must not condemn a
  batch) and the epilogue mask, ORed into the per-body accumulator task 51 left in place. The
  accumulator is allocated only when the body reaches such a producer and
  `VarkaEmitOptions.guardDayProducers` is on, so every other shape is byte-identical under
  both settings - the suite asserts it on method sizes. The analysis returns two answers:
  bounded and unknown (decline), so a producer nobody has taught to the analysis fails as a
  residual entry, never as a wrong year. It returned a third for a while - "column-shifted:
  admit, the emitter guards it" - and the entry below is what that cost.
- **A mask guard costs its `fromLong`, not its compares.** Measured on `year(date_add(d, off))`
  (`VarkaEmitterParityBenchmark`, two regenerations and a second run each): the guard costs
  13-14% with mixed nulls at both widths in every run, against 5-15% null-free at 256 bits
  and 2.5-4% at 128 - 0.05 to 0.07 ns per row in the masked body against 0.02 to 0.05 in the
  dense one for the same two compares. The difference is the validity AND, whose
  `VectorMask.fromLong` materializes a mask from a scalar word; the prediction counted it as
  one lane op and it is not. A guard that reuses a mask the body has already built for its
  store would not pay it.
- **The guard generalizes to a value bounded by anything other than the day range - and the
  block itself needed no change to do it.** Task 60 widened `add_months`' month count from a
  compile-time-bounded literal to a column, and reused this same block
  (renamed `emitProducerGuard` to `emitRangeGuard`, taking the two bounds as parameters) to
  guard the count against `MONTH_ARITH_MIN/MAX_MONTHS` instead of the day range. The correction
  this forced onto `PLAN_MILESTONE_4.md` 2.27: a column bounded by a runtime guard is a
  `Bounded` day range at the guard's own extremes (`shifted(days, 31 * MIN, 31 * MAX)`), not an
  unbounded shift - "unbounded" is for a shift the compiler genuinely cannot bound at all, which
  a *guarded* column is not. Getting this wrong would have re-widened every consumer's
  range to "unknowable" for no reason, the same over-approximation task 51 had just finished
  removing.
- **State a guard's guarantee as an interval, not as a verdict, or it will not compose.** Task
  60's review found the hole this makes. The analysis had a `ColumnShifted` answer meaning
  "some producer below is guarded at run time, so admit this", and `admitCalendar` admitted it
  without any range test. That is sound only while the guarded producer is the calendar node's
  direct child. Put anything above it that moves the day - `add_months` with a column count,
  worth up to 31 * `MONTH_ARITH_MAX_MONTHS` days - and the verdict still said "admit", because
  a verdict carries no arithmetic for the shift to act on. Both runtime guards passed on their
  own operands and `year(add_months(date_add(d, off), m))` answered 87585 for a true -14848.
  The fix is a one-liner and the lesson is in its shape: have the guarded producer return
  `Bounded(NARROW_MIN_DAYS, NARROW_MAX_DAYS)` - the interval its guard actually establishes -
  and every existing rule composes with it for free, because they were already written to shift
  intervals. `ColumnShifted` then has no producer and is deleted. Generally: when a runtime
  check establishes a fact the compiler wants to rely on, encode the *fact* in the same
  representation the analysis already manipulates, never as a special case meaning "trust me".
  The special case is invisible to every rule written before it.
- **A guard the compiler relies on cannot sit behind an option the compiler cannot see.** The
  same review caught the count guard filed with the option-gated day-producer guards while
  `dayRange` returned `Bounded` for a column count unconditionally. With
  `guardDayProducers=false` the guard vanished and the compile-time bound stayed - wrong
  answers, not a slower reference variant. The criterion that sorts these is already in the
  code: `selfGuarding` (task 42's `make_date`) is "the check is the node's own correctness" and
  is never optional, `guardedProducers` is "insurance for a consumer" and may be. A count guard
  protecting its own magic multiply is the former, and moving it there made the option's name
  honest again as well.
- **A word this block reads must already be stored, not merely available on the stack.** The
  guard's mask-body AND reads `Slots#wordRef` for the node under guard - a *stored local*, not
  whatever the emitter last pushed. For `AddDays`/`SubDays` that word is computed immediately
  before the guard runs (`emitAndValidatedOp`'s own call site), so this was never visible at
  task 52. `add_months` computes its own word differently: task 40's dispatcher ran
  `emitAndWord` *after* `emitAddMonths` returned, once the whole value was on the stack - fine
  for every reader that came after, but the guard needed to run *inside* `emitAddMonths`, right
  after the count loads and before the magic-multiply's bias folds it in, which is earlier than
  that word existed. The result was `VerifyError: Bad local variable type ... top ... not
  assignable to long` in the masked epilogue, the one body where the guard reads that word.
  What made it invisible was not the absence of a calendar consumer - `Year(AddMonths(col,
  col))` aliases the same word slot and would have failed the same way - but that no curated
  test ran a *live* violation through the masked epilogue at all; the shapes that did reach it
  nulled the offending lane, where the guard is silent either way. The fix moved the
  `emitAndWord` call earlier, into `emitAddMonths` itself, right after the count's
  `emitValue` - both children's words are provably ready by then, so nothing about the word
  itself changed, only when it is stored. The general lesson: before reusing a block that
  reads "the node's own word," check where that word is written relative to where the reused
  block will run, not just that it is written somewhere.

## A derived input must never raise, because the row engine's null check comes first

Task 59. A string-argument date function (`next_day(d, s)` with a weekday column) runs in the
kernel without string lanes by having the evaluator derive an int32 column per batch, before
the kernel, through the row engine's own parser (`WeekdayLeaf`); the kernel then reads a plain
int input (`CompiledVarkaProjection.derivedInputs`, a plan property like `inputBounds`, keyed
under a negative synthetic input-table key so the compiler's mark-and-truncate rollback covers
it). Two things came out of building it.

- **The obvious ANSI story was wrong.** The milestone's section 2.26 said the pre-pass could
  simply call the same function and raise the same error at the same row. But
  `NextDay.nullSafeEval` never parses the name when the date beside it is null:
  `next_day(NULL, 'xyz')` is NULL under ANSI, not an error. A pre-pass that raised on the parse
  alone would err where the row engine does not. So the leaf never throws: under ANSI an
  unrecognised name declines the batch (`STATUS_DERIVED_INPUT`) and the row engine computes it
  by its own rules - NULL beside a null date, the error beside a live one - which also keeps the
  invariant that no kernel-side code raises a user-facing exception. The general rule: a derived
  input reproduces a *function's* semantics, and the expression around it may have null
  short-circuits the function does not; the row engine is the only safe place to raise.
- **An ASCII fast path must delegate every non-ASCII row, not just reject it.**
  `"\u017Funday".toUpperCase(Locale.ROOT)` is `SUNDAY` (long s) and `"fr\u0131day"` is
  `FRIDAY` (dotless i), so a byte-level parser that rejected non-ASCII input would disagree with
  the definition on rows that are, by the definition, weekdays. The parser hands any row with a
  byte at or above 0x80 to `getDayOfWeekFromString` itself, and `WeekdayLeafSuite` holds both
  parsers to the definition over every case pattern of the 21 spellings, every one- and
  two-byte ASCII string and every printable one-byte mutation of every spelling.
- **The second leaf had no ANSI question at all, and that was a finding, not an assumption**
  (task 61, `trunc(d, fmt)` with a format column). `TruncDate` has no `failOnError`, and
  `TruncInstant.evalHelper` answers every non-date level - a null format, an unrecognised
  spelling, `'DAY'` and below - with NULL in both modes, so the kind (`TRUNC_LEVEL`) has no
  ANSI twin and `TruncLevelLeaf` never declines. The order of work that made this cheap: read
  the row engine's eval for the error path first, and derive the leaf's contract (never throw;
  null lane or decline) from what that path does. The other transferable piece: when the
  derived value selects *which computation* applies (the level) rather than feeding one (the
  weekday's `k`), the kernel computes every alternative and blends on the lane - 91 dense-loop
  ops for the four periods against 36..62 for one literal level - so the literal form remains
  the shape a query should write, and the doc says so.

## A recipe for a cheap agent ages at the rate of the emitter, not of the arithmetic

Task 35, the third of the four recipe tasks (34-37) to be executed. Its section 2 arithmetic
was verified in planning and was right on the first run under every variant; every correction
the build needed was to the recipe's picture of the emitter, and the re-plan written six weeks
earlier (its section 7) had itself gone stale in three places by build time: the leap flag's
signature (seven parameters, then one), a helper the re-plan assumed would exist (it did not;
the code was inline in another arm), and the weight constants (two values each moved twice).
The lesson for writing such recipes: pin the arithmetic in a verification script, which
survives, and describe the emitter by *what to look for* - "the method that leaves the leap
mask", "the switch that throws on an unknown calendar node" - rather than by signatures and
numbers, which do not. The one thing that reliably told the builder what had moved was the
compiler: every exhaustive switch over the sealed IR family fails to compile until the new
record is handled, and the two that are not exhaustive (`tailReadsMarchMonth`, `chronoChild`)
throw at emit time on the first test. The hand-maintained lists are the ones to check by hand:
the fuzzer's node generator and the two pinned fixtures.

## Repo Workflow (vecbricks/varka)

- Remotes here: `origin` = `vecbricks/varka` (PR base, `master`), `fork` =
  `MaxGekk/spark` (PR head). Push the PR branch to `fork`, then open against
  `vecbricks/varka:master`.
- No JIRA IDs. Titles are `[VARKA] <short summary>`; PR descriptions are prose in
  the five standard template sections; sign off with a `Generated-by:` line naming
  the actual tool (recent PRs: `Generated-by: Claude Code (Claude Fable 5)`).
- Branch naming: `varka-<topic>` tracks `origin/master` and stays one commit ahead
  per PR.
- The standing gate is one command, `dev/varka_gate.sh`: compile, the Varka suites
  at both widths, the opt-in exhaustive sweeps, `catalyst/doc`, both linters, each
  step logged under `target/varka-gate/`, one summary table, non-zero exit on any
  failure. `--only`/`--skip` take step names, `--list` shows them. It finds
  `hsdis-<arch>.so` for the assembly suite in the usual local places and says
  whether it did, so a run whose instruction assertions cancelled is visible.
- After every merge to master, `dev/varka_pr_sweep.sh` dry-merges every open PR
  against master and against each other through GitHub's `refs/pull/<n>/head`,
  and exits with the number of conflicts. It uses `git merge-tree --write-tree`;
  the legacy three-argument `merge-tree` prints a diff, so its conflict markers
  carry a leading `+` and a grep for `^<<<<<<<` sees none - the script's first
  version passed a conflicting PR that way.
- Benchmark files are regenerated with `dev/varka_bench_regen.sh` and read with
  `dev/varka_bench_diff.py`; `sql/varka/AGENTS.md`'s "Measurements" section says
  how and why, including the committed 128-bit companion file, the machine canary
  (`dev/varka_bench_canary.sh`) the regen script runs first, and the quote check
  (`dev/varka_quote_check.py`, a gate step) that holds every quoted number to a
  committed file.
- Run `dev/varka_precommit.sh` before committing, or install it as the pre-commit hook:
  non-ASCII outside strings, lines over 100 columns, TODO/FIXME under Varka
  directories, the quote check, and ruff (`check` and `format --check`) on Python
  files. Each of those has reached CI or a reviewer at least once; the formatter
  reached CI on five PRs at once, because `dev/lint-python` skips ruff silently
  when it is not installed.
- `VarkaIrFuzzSuite` fuzzes the emitter: random IR over random null patterns, lengths
  and option variants against the shared reference evaluator, reproducible by seed
  and iteration.
- A task starts with `dev/varka_task_new.sh <n> "<title>"` (worktree, branch, plan from
  `sql/varka/plans/TEMPLATE_TASK.md`, hook); a regeneration ends with
  `dev/varka_bench_diff.py --git HEAD <file> --requote`; the volume checks run from
  `dev/varka_nightly.sh`.
- Before registering op counts in a plan, print them: `dev/varka_emit.sh "<sql>"`
  gives the IR, the shape hash and per-method `IntVector` invocation counts on the
  suite's own scale; `--asm` adds C2's assembly for the dense loop; `--table
  --variant k=v` prints the plan's op-count table with deltas against the defaults.
- `dev/varka_hsdis_build.sh` builds `hsdis-<arch>.so` from the JDK's single source
  file against the distribution's libcapstone, no JDK build needed;
  `dev/varka_worktree.sh gc` removes the worktrees whose PRs merged.

## A store the loop repeats per group with a constant operand is a fill the driver should do once

Task 45. The emitted dense loop ended every value output with
`orValidityBitsAt(seg, i, -1L, lanes)` - a 212-byte helper that does not inline in a wide loop -
once per lane group per output, ORing a word of all ones into a bitmap the driver had zeroed a
moment earlier. On a dense batch the dispatcher has already proven every input null-free and
task 11's invariant makes every value output valid on every row, so those bits were known before
the loop started. Setting them once in the driver, and not emitting the tail, is worth:

| shape | AVX-512 | 128-bit |
|---|---|---|
| shared four-field calendar | +86% | +149% |
| `year` | +26% | +41% |
| `dayofweek` | +12% | +46% |

(AMD Ryzen AI 9 HX PRO 370, OpenJDK 25.0.4, one regeneration on the tree merged with task 53.) The
four-field shape then beats `ChronoVectorOps.vectorFourFields`, the hand-written ceiling task 32
spent its time chasing, by 2.3x.

Three things generalise beyond this one store.

* **The constant operand is the tell.** A per-iteration call whose data argument is a compile-time
  constant is doing work whose answer the emitter already knows. Look for the loop-invariant
  operand rather than for the expensive-looking call.
* **The win is larger at narrow widths, and that is arithmetic rather than luck.** A four-lane
  group makes four times as many calls per row as a sixteen-lane one and the call's cost is per
  call, not per lane. Any per-group fixed cost is worth four times as much to remove at 128-bit.
  This was registered as a prediction and held for all three shapes.
* **The ratio can invert between widths.** `dayofweek` gains *less* than `year` at AVX-512 (+12%
  against +26%) and *more* than it at 128-bit (+46% against +41%), because a ~14-op body at four
  lanes is dominated by per-group overhead while the same body at sixteen lanes is dominated by
  its stores. A ranking measured at one width is not a ranking.

**And bit-exactness is the contract, not an implementation detail.** The old path zeroed
`(rows + 7) / 8` bytes and OR-ed lane-masked words, leaving the bits past `rows` in the final
byte at zero. The replacement has to set exactly `rows` bits and not fill that last byte, because
the differential compares dense against masked validity byte for byte - and because nothing
promises every Arrow reader stops at `valueCount`. Producing identical bits is what lets the
existing differential be the change's oracle rather than something to rewrite.

## Read two fields out of one product, and put the axis where the formula wants it

Task 53. The civil-from-days prefix used to find the month with a magic multiply on the March
day-of-year and then run `emitMonthStart` *forwards* to recover the day of month, on a March = 0
axis that needed an add in front of every reported month. Neri and Schneider (2022) show that one
affine numerator does both jobs at once: with `num = 2141 * doy + 197913`, the month index is
`num >>> 16` and the day of month is `((num & 0xFFFF) * 31345 >>> 26) + 1`. The high half and the
low half of one product are two fields, and the constants only work on the March = 3 axis, which
is exactly the axis that makes the reported month `m3 < 13 ? m3 : m3 - 12` with no add. The
identities are exact over their domains (366, 65536 and 12 cases) and the exhaustive sweep runs
both axes over all 16,777,216 covered days, so the older lowering stays as the reference variant
the new one is checked against rather than dead code.

| shape | AVX-512 | 128-bit |
|---|---|---|
| `dayofmonth` (null-free / mixed nulls) | +13.5% / +11.9% | +12.6% / +12.7% |
| `month` (null-free / mixed nulls) | +4.3% / +0.7% | +5.7% / +4.2% |
| shared four-field calendar | +3.6% | +5.0% |

Three lessons that outlive the constants.

* **When a formula wants a different origin, move the origin rather than correcting for it.**
  The March = 3 axis looks like churn - every slot comment, every helper and every test moved -
  but the alternative was a permanent add on the hot path to translate between the paper's axis
  and ours. The prefix slot `t[5]` now holds the numerator, not a month, and the tails that read
  it (`tailReadsMarchMonth`) are an exhaustive switch so a new tail cannot silently assume the
  old contents.
* **A shared shape gains least from a cheaper shared step, and that is the denominator, not a
  surprise.** The four-field shape was predicted to gain most "because it pays the month block
  once and the tails three times". Fragment sharing already collapses its four prefixes into one,
  so it saves the block once spread across four outputs and a body five times the size, while
  `dayofmonth` alone saves it on every row of a small body. Prediction 4 missed for exactly this
  reason; write the denominator down before predicting a ratio.
* **A boundary measured in whole outputs does not move for a saving smaller than one output.**
  The unshared `HugeMethodLimit` crossing was predicted to move from 19/20 outputs to 21 or 22.
  It did not move on either axis: an output costs roughly 400 bytes of epilogue and the saving
  was 149. Count the units the boundary is measured in before predicting it will shift.

## A bimodal kernel is usually the register allocator, and here is how to prove it

Task 32's shared four-field calendar kernel ran at either 165 or 236 M rows/s under
`-XX:MaxVectorSize=16` - stdev 0 ms *inside* a run, 42% *between* runs, 4 fast outcomes in 21.
Six hypotheses were tested and all failed: shorter live ranges, forcing the validity helpers to
inline, forcing every Varka class to inline, disabling on-stack replacement, raising
`LoopUnrollLimit`, and buffer alignment (which had an OpenJDK bug report behind it,
JDK-8380195, describing the same shape and blaming alignment - it is not that here).

**What it actually is.** Capture `PrintAssembly` for the method in both modes and compare the
*standard* (non-OSR) nmethod:

| | instructions | stack traffic | xmm spill moves | vpmulld | vpsrld | vpsubd |
|---|---|---|---|---|---|---|
| fast (240.6) | 1581 | 721 | **4** | 26 | 14 | 20 |
| slow (164.7) | 3000 | 1567 | **74** | 26 | 14 | 20 |

The vector op counts are **identical**, so it is not unrolling, not a different lowering and
not a missing intrinsic. The entire 1581-to-3000 difference is spill and reload traffic, 18x
more of it. C2's register allocator sometimes finds a clean allocation for this body and
sometimes does not, from the same IR. The OSR compilations, by contrast, are the same in both
runs to within one instruction (7303 against 7304).

It is width-specific for the obvious reason: 128-bit has 16 xmm registers and this body sits at
the edge of them - C1 refuses the same method outright with
`COMPILE SKIPPED: out of virtual registers in linear scan` - while AVX-512's 32 zmm registers
leave slack and show no bimodality at all.

**It is a property of the measurement environment, not of the kernel.** It does not reproduce
standalone (six runs, all 210.5 M rows/s, no spread) and does not reproduce under a fastdebug
JVM even running the whole benchmark (six runs, all ~161). It needs the product C2 *and* the
benchmark's accumulated JVM state. So production is not exposed to it, but a long benchmark's
later cases are, and a ratio is only trustworthy when both arms sit in the same run - which for
the shared-versus-four-node comparison they do, since they are adjacent cases.

**The general rule.** When a kernel is bimodal across JVMs with no spread inside a run, diff
the standard nmethod between the two modes and count spill moves before theorising. Equal op
counts with unequal instruction counts means allocation, not transformation, and no amount of
inlining, unrolling or alignment flags will touch it. The design answer is to reduce what must
be live at once - and note that *scheduling* to shorten live ranges did not help here
(`vectorFourFieldsShortLive` is bimodal too); what helps is not putting four outputs in one
method at a narrow width, which is why the four-node baseline is stable in all 21 runs.

**The effect is observable at runtime, with public API.** JFR's `jdk.Compilation` event
carries `method`, `compileLevel`, `isOsr` and - the useful one - `codeSize`, so a
`jdk.jfr.consumer.RecordingStream` can watch the compiled size of Varka's own generated kernel
methods as they are compiled, with no agent and no diagnostic flags. The fast and slow
allocations differ by roughly 2x in code size here, so the anomaly is visible in that number.
And because every kernel is emitted into a fresh class, "recompile it" is available too: emit
the same shape again under a new class name and the allocator gets a fresh roll.
That makes a detect-and-resample loop *possible*; it does not make it wise. Each resample costs
another class, another compile and another warm-up, against a shape whose kernel a short query
may only run a handful of times, and the detection needs a per-shape expected size that will
drift. Prefer the structural fix - do not put four outputs in one method at a width whose
register file cannot hold them - and use the JFR signal as a *diagnostic*, so that a
badly-allocated kernel is reportable rather than invisible, instead of as a control loop.

**Update, task 32 step B2: the "structural fix" above turns out to be a property of this
one hand-written kernel's bytecode, not of "four outputs in one method at 128-bit."** Once
the emitter's own fragment mechanism made the *real* shared-loop-method shape buildable
(`VarkaEmitOptions.withGroupBudget(200)`, four calendar outputs genuinely fused into one
generated method - the same four-output-one-method shape this section just said to avoid),
it was measured at 128-bit across three separate runs and showed **no bimodality at
all** - stable to a few milliseconds every time, at every field count from two to four. The
two hand-written kernels this task also built (`ChronoVectorOps.vectorFourFields`, the
"ceiling", and `vectorFourFieldsNoValidity`) remain bimodal on the same three runs, one of
them in a new flavor - flipping between fast and slow *within* a single run's iterations
rather than settling into one mode for the run's duration (`PLAN_TASK_32.md` section 7.4).
So: a register allocation this fragile is a property of one specific compiled method's
bytecode (here, `javac`'s output for a hand-written 936-byte body), not an inherent cost of
the technique it demonstrates. Do not generalize "four outputs in one method is unsafe at
128-bit" from a single hand-written kernel to the emitter's own generated bytecode for the
same shape - measure the actual generated path before declining a design on this basis.

## Watching what C2 compiled, at runtime, with no flags

JFR's `jdk.Compilation` event carries `method`, `compileLevel`, `isOsr` and `codeSize`, and
`jdk.jfr.consumer.RecordingStream` consumes it in-process with no agent and no diagnostic flags.
That makes compiled size observable in any JVM, which the bimodality section above needed a
fastdebug build and `PrintAssembly` to see. Task 50 builds this; four things it cost to learn.

* **The success field is spelled `succeded`** in the JDK's own event metadata. Asking for the
  correctly spelled name throws, and if the handler catches broadly the stream goes quietly dead
  rather than failing loudly. Dump the schema (`FlightRecorder.getFlightRecorder().getEventTypes()`)
  rather than trusting the field names you would expect.
* **Key any size comparison on (class, method, compile level), never on the class alone.** A
  Varka kernel is many methods by design, and the tiers differ enormously: measured on one `year`
  kernel, `epilogueDense` is 165728 bytes at tier 3 and 1888 at tier 4 - profiled C1 against
  optimised C2, a factor of 88. Compare across tiers and every method reports a 98% "divergence"
  in every JVM. Drop OSR compilations too: same method, same tier, 744 bytes as OSR against 576
  not.
* **Compiled size for a given key is byte-identical run to run.** Measured across three JVMs and
  across two emissions inside one JVM, every key came out the same to the byte. So any threshold
  between zero and the ~2x that a bad allocation costs will do, and the choice is not delicate.
* **A per-JVM baseline cannot see the bimodality that motivated it.** Every key is normally
  compiled exactly once per JVM, and task 32's spread was *between* runs - "stdev 0 inside a run,
  42% between runs". What gives a second compilation of one key is **re-emission**: the same
  shape emitted into a fresh class of the same name under a different loader, which is what
  `maxEntries = 0` and cache eviction already do, and which is also the parked "resample" idea.
  Emitting one shape twice in a JVM produced 16 compilations across 8 keys, each compiled twice.
  Design the diagnostic around re-emission, not around recompilation.

**You do not need to run the fastdebug JVM to read product assembly.** HotSpot's fourth
fallback for locating the disassembler is `hsdis-<arch>.so` on `LD_LIBRARY_PATH`
(`disassembler.cpp`), so a `libhsdis.so` built once against a fastdebug tree can be copied to
`hsdis-amd64.so` and used with the *product* JVM (and you do not need the fastdebug tree
either: `dev/varka_hsdis_build.sh` compiles `src/utils/hsdis/capstone/hsdis-capstone.c`
against the distribution's `libcapstone-dev` with `make/Hsdis.gmk`'s flags - a 16 KB shared
object in under a second, the JDK headers only for `jni.h`):

```
LD_LIBRARY_PATH=<dir with hsdis-amd64.so> java -XX:+UnlockDiagnosticVMOptions \
    -XX:CompileCommand=print,<class>::<method> ...
```

That matters because some behaviour only appears in the product build - this bimodality among
it - so being able to disassemble there rather than only in fastdebug is what made the
comparison above possible at all.

**Four details that decide whether a disassembly-reading test works or quietly passes.** These
came out of task 31's feasibility check, run against the system JDK 25.0.4 product build with
the fastdebug tree's `hsdis-amd64.so` on `LD_LIBRARY_PATH`.

* **Prefer `-XX:CompileCommand=print,<class>::<method>` to `-XX:+PrintAssembly`.** `print` emits
  one method's disassembly; `PrintAssembly` emits the whole compilation log, and the difference
  is hundreds of lines against tens of megabytes. With `print` there is nothing left for a
  `compileonly` filter to do.
* **Both a C1 and a C2 nmethod are printed for the same method**, headed `C1-compiled nmethod`
  and `C2-compiled nmethod`. C1's body is scalar by construction, so anything reading the output
  must split on those headers and keep the C2 one. Scanning the concatenated text finds scalar
  instructions in a method that vectorized perfectly.
* **The mnemonic and its operands are separated by tabs, not spaces** - `vpaddd\t\t0x10(%rsi,
  %rax, 4), %zmm0, %zmm0`, confirmed with `cat -A`. A pattern written for whitespace-as-spaces
  matches nothing, and *matching nothing looks exactly like a body with no vector instructions*.
  Any such test needs a self-test - a deliberately scalar method asserted to contain none of the
  family and a vector one asserted to contain one - or every case can pass vacuously.
* **"hsdis is present" is not the same as "hsdis loaded".** Without a working disassembler
  HotSpot prints `Loading hsdis library failed` and degrades to bytecode-level output rather than
  erroring, so detection must look for a real `[Disassembly]` section with hex-addressed
  instruction lines, and should distinguish "no library found" from "found and refused to load"
  when it reports a skip.

**Three ways this went wrong in practice, all caught by the self-test before any real kernel was
looked at.** Each produced a *plausible* result rather than an error, which is why the
scalar/vector pair is built and run before anything else.

* **`-XX:CompileCommand`'s method pattern cannot mix `/` with `::`.**
  `print,org/apache/spark/.../Probe::method` fails VM startup outright -
  `Method pattern uses '/' together with '::'`. Use `package.Class::method` or
  `package/Class.method`, not a mix. The child then never starts, and a suite that only checks
  for disassembly reports "no disassembler" for what is really a malformed flag - so check the
  child's exit code first, and fail rather than skip on it.
* **Gate instruction parsing on the `[Disassembly]` marker, not on the shape of a line.** With no
  usable disassembler HotSpot prints the nmethod under `[MachCode]` as raw hex words -
  `0x...: ff1f 0045 | 85c9 0f84 | ...` - and those lines have exactly the `0x<addr>:` shape an
  instruction line has. Requiring the mnemonic to start with a letter does not separate them
  either, since hex words routinely do (`ff1f`, `e929`, `c349`). Measured: 68 such lines parsed
  as instructions, and the suite reported "the intrinsic did not fire" about a body it had never
  read.
* **`Loading hsdis library failed` does not mean a library was found.** HotSpot prints it both
  when it looked and found nothing and when it found something it could not load, so a skip
  message keyed off that line says "a disassembler was found but HotSpot refused to load it"
  when none exists. Discriminate on your own search result instead.

**Assert families, never mnemonics or counts.** The register class is a property of the host -
`zmm` under AVX-512, `ymm` under AVX2, `xmm` at `-XX:MaxVectorSize=16` - so derive it from
`IntVector.SPECIES_PREFERRED.vectorBitSize()` at runtime. And do not assert instruction *counts*:
the bimodality section above found identical vector-op counts with a 2x difference in total
instructions, so a count assertion goes red on a register-allocation roll with nothing wrong.

**And derive the family from output you actually read, not from a mnemonic list written from
memory.** The obvious list for an integer comparison - `vpcmpd`, `vpcmpeqd`, `vpcmpgtd` - matches
nothing on AVX-512, because the predicate is folded into the mnemonic: `a > b` on int lanes comes
out as `vpcmpnled`, not-less-or-equal. The full set runs to a dozen suffixes and varies with how
C2 chose to spell the comparison, so the durable rule is the shape `[v]pcmp<predicate>d` rather
than an enumeration that goes stale on the next lowering change.

**Forcing C2 to inline Varka's own packages is a decline, and not because it does nothing.**
`-XX:CompileCommand=inline,org.apache.spark.sql.varka.*::*` (plus the catalyst varka package)
leaves every loop body byte-identical - the emitted `year` body stays at 327 instructions with
10 `vpaddd`, 8 `vpmulld`, 4 `vpsrld`, over three runs each with zero variance, and
`ChronoVectorOps.vectorFourFields` stays at 1174. What it changes is the method boundary: the
emitted `run` grows from 271 instructions with no vector ops to 471 carrying the whole year
lowering, `runDense` stops being compiled standalone, and the vectorized body then exists in two
places. That is exactly the sibling-method structure task 24 built and `GROUP_BUDGET` exists to
control, so the flag works against the emitter's design - and it would have to be set on every
executor to do so. If method fusion is ever worth measuring, produce it from the emitter with
`withGroupBudget`, which is per-shape and needs no flag.

`-XX:+PrintInlining` explains why the bodies survive: the directive overrides the size heuristic
and lands on a harder limit. `VarkaVectorSupport::orValidityBitsAt` (212 bytes) goes from
`failed to inline: callee is too large` to `failed to inline: NodeCountInliningCutoff`. The
reason changes, the outcome does not - which is also evidence that task 46's inlining problem
cannot be solved with a flag.

**Measure the caller, not only the method you are interested in.** The finding above was nearly
missed: the A/B started on `loopDense0`, found it identical under both configurations, and would
have concluded "the flag changes nothing". It changes the *caller*. When a flag affects inlining,
the method whose body moves is the one doing the calling.

**What the kernels actually compile to**, read this way rather than inferred from a ratio, on a
Zen 5 host at AVX-512 (JDK 25 product build). `DateVectorOps.vectorAddDays`: 5 `vpaddd`, 23 `%zmm`
operands. `ChronoVectorOps.vectorFourFields`: 15 `vpaddd`, 13 `vpmulld`, 7 `vpsrld`. The emitted
`year` loop body: 10 `vpaddd`, 8 `vpmulld`, 4 `vpsrld`, 63 `%zmm` operands. The emitted
`dayofweek` body: 65 `vpaddd`, 26 `vpmulld`, 39 `vpsrld` - task 14's range-narrowed magic is
packed, which is the whole reason that lowering exists. An emitted comparison: 15 `vpcmpnled` and
15 `vpblendmd`, no branch. Everything Varka emits or hand-writes for date work vectorizes; that
was an assumption until task 31.

## This machine's AVX-512 is 256 bits wide, and every "512-bit" number in this repo is really a 256-bit one

Measured, not read off a spec sheet. Task 43's committed op-count ladder - a single-output loop
from 20 to 248 `IntVector` ops - was run at three widths on the development machine (AMD Ryzen
AI 9 HX PRO 370, Zen 5 mobile, JDK 25.0.4), by setting `Test / javaOptions +=
"-XX:MaxVectorSize=N"` to 16, 32 and 64:

| ops | 128-bit | 256-bit | 512-bit |
|---|---|---|---|
| ns/row/op | 0.0163-0.0212 | 0.0070-0.0074 | 0.0072-0.0078 |

| step | lanes | speedup |
|---|---|---|
| 128 -> 256 | doubled | **2.48x** |
| 256 -> 512 | doubled | **0.95x** |

**Doubling the vector width from 256 to 512 bits buys nothing here - it is very slightly
negative.** The chip has the whole AVX-512 instruction set (`lscpu` lists `avx512f` through
`avx512_vp2intersect`) and HotSpot picks `MaxVectorSize=64`, so everything *looks* 512-bit; the
execution units behind it are 256 bits wide and 512-bit operations are issued as two halves.
128 -> 256 is even superlinear at 2.48x, which is the per-lane-group fixed costs amortising over
twice the rows on top of the real datapath widening.

**What this means for numbers already committed.** Every result in milestone 4 labelled AVX-512
is a 256-bit-datapath result. None of the *decisions* move, because each is a comparison between
two lowerings at one fixed width - task 45's validity fill, task 53's month numerator, task 48's
elision, task 43's own flatness - and both arms of every comparison ran on the same hardware.
What is overstated is the label: "at both widths" has meant "at 4 lanes and at 16 lanes issued
through a 256-bit datapath", not "at two datapath widths".

**And it means there is unmeasured headroom on other hardware.** A host with a full-width 512-bit
datapath - Intel Sapphire Rapids and Emerald Rapids, or AMD EPYC Turin - should turn that 0.95x
into something near 2x on unmodified code. That is the cheapest performance work available to
this project and it requires no port: the same jar, a different instance type.

**The method generalises: to find out whether a machine's widest vector is real, measure three
widths, not two.** Two points cannot distinguish "the wide path is not helping" from "the wide
path does not exist", and `lscpu` and `MaxVectorSize` both report the instruction set rather than
the datapath. A committed op-count ladder is the right instrument because its x-axis is asserted
off the class file, so the same shapes are being compared at each width.

**`-XX:MaxVectorSize=32` is arguably the honest "wide" setting on this laptop**: identical
throughput, smaller emitted bodies, shorter compiles.

## Building a fastdebug JDK for HotSpot diagnostics

Three questions in milestone 4 could not be answered from a product JVM - why SuperWord
declined a loop, what C2 actually emitted, and which of two compilations a bimodal kernel had
landed on. The flags that answer them (`TraceSuperWord`, `PrintOptoAssembly`, and a
`PrintAssembly` that can actually disassemble) are `develop` flags or need `hsdis`, and neither
ships in a product build. No prebuilt debug JDK exists on `jdk.java.net` for any version, so
building one is the only route. It takes about three and a half minutes on 24 cores.

```
git clone --depth 1 --branch jdk-25-ga https://github.com/openjdk/jdk.git
bash configure --with-debug-level=fastdebug --enable-headless-only \
    --with-hsdis=capstone --with-boot-jdk=$JAVA_HOME \
    --with-jobs=24 --disable-warnings-as-errors
make images build-hsdis
cp build/*/support/hsdis/libhsdis.so build/*/images/jdk/lib/server/
```

Four things that cost time and are not obvious:

* **`--enable-headless-only` does not remove the X11 or cups build dependencies.** It affects
  the runtime, not what configure demands. The full Ubuntu list is still needed: `autoconf`,
  `libx11-dev`, `libxext-dev`, `libxrender-dev`, `libxrandr-dev`, `libxtst-dev`, `libxt-dev`,
  `libcups2-dev`, `libfontconfig1-dev`, `libfreetype-dev`, `libasound2-dev`, and
  `libcapstone-dev` for hsdis.
* **Ubuntu 26.04 ships `uutils coreutils` as `date`, and it breaks the build.** OpenJDK's
  configure decides GNU-ness with `date --version | grep "GNU\|BusyBox"`
  (`make/autoconf/basic_tools.m4`). uutils names itself differently while behaving
  GNU-compatibly, so configure falls back to the BSD `date -u -j -f` form, produces an empty
  `SOURCE_DATE_ISO_8601`, and the build later fails packaging `jrt-fs.jar` with
  `option --date requires an argument`. Passing `--with-source-date=<ISO string>` does not help
  - it is rejected as unparseable by the same broken path. The fix is a shim earlier in `PATH`
  that answers `--version` with a string containing "GNU" and `exec`s `/usr/bin/date` for
  everything else.
* **`make images` does not build hsdis.** It needs the separate `build-hsdis` target, and the
  result is left in `support/hsdis/libhsdis.so`. HotSpot looks for it beside `libjvm.so`, so it
  has to be copied to `images/jdk/lib/server/` - putting it in `images/jdk/lib/` is not enough
  and yields `Loading hsdis library failed` with no further explanation.
* **The flag is `TraceSuperWord`, not `TraceAutoVectorization`**, in JDK 25 - check
  `src/hotspot/share/opto/c2_globals.hpp` rather than trusting a flag name from a newer
  release.

**Never take a number from this JVM.** fastdebug keeps the assertions, so absolute throughput
is not comparable with the product build and nothing measured on it belongs in a committed
results file. It is for reading what C2 did, not how fast it did it.
