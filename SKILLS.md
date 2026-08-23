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

- The engine module is built with Maven, not sbt: `./build/mvn -o -f
  sql/varka/engine/pom.xml install -DskipTests`. `mvn` is not on `PATH`; use
  `./build/mvn`.
- sql/core test-scope depends on the engine jar from `~/.m2`; after editing engine
  sources, rebuild + reinstall or the test classpath keeps the old bytes.
- scalastyle requires a trailing newline at EOF ("File must end with newline
  character") and rejects `throw new XxxError` via the `throwerror` rule. For a
  deliberate `NoClassDefFoundError` test hook, wrap the throw in
  `// scalastyle:off throwerror` / `// scalastyle:on throwerror`.

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

## Repo Workflow (vecbricks/varka)

- Remotes here: `origin` = `vecbricks/varka` (PR base, `master`), `fork` =
  `MaxGekk/spark` (PR head). Push the PR branch to `fork`, then open against
  `vecbricks/varka:master`.
- No JIRA IDs. Titles are `[VARKA] <short summary>`; PR descriptions are prose in
  the five standard template sections; sign off with `Generated-by: opencode`.
- Branch naming: `varka-<topic>` tracks `origin/master` and stays one commit ahead
  per PR.