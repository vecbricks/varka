# Varka MVP - code review findings

Review of the Varka code as of `b56e9f7f34a` (`sql/varka/`, the catalyst hooks, the
`sql/core` exec node and rule, and `docs/sql-varka.md`).

The review itself was a reading of the code; nothing was built or run to produce it.
Findings 1 to 5 have since been fixed and merged - PRs #11, #13, #16, #12 and #14 -
and findings 6 and 7 are fixed by the change this file ships with. The engine test suite
and the `sql/core` Varka suites were built and run as part of those fixes. Line
references below were refreshed against `c6fbfe34d88`; entries marked "pre-fix line
numbers" point into the code as it stood before that finding's fix.

Ordering inside each section is by severity, not by file. Findings are open unless a
**Status** line says otherwise.

## Contents

* [Blocking correctness](#blocking-correctness)
* [Build and integration](#build-and-integration)
* [Design landmines](#design-landmines)
* [Performance](#performance)
* [Smaller things](#smaller-things)
* [What is solid](#what-is-solid)
* [Suggested order](#suggested-order)

## Blocking correctness

### 1. The SIMD kernels are wrong on any machine with fewer than 8 int lanes

**Status: FIXED** in `a6b7f7d3541` (PR #11). The bitmap is now addressed one
64-bit word at a time with an explicit shift, via the `validityBitsAt` /
`orValidityBitsAt` / `wordAlignedEnd` helpers, which both kernels share.
`DateVectorOpsBitmapTest` walks every lane width from 1 to 64 over the bitmaps the
kernels see, so the widths this host's species cannot produce are covered. Verified to
catch the original defect: restoring the pre-fix arithmetic fails the new test at
`lane width 4, length 64, row 4`. See the caveat at the end of this entry.

`sql/varka/engine/src/main/java/org/apache/spark/sql/varka/vector/DateVectorOps.java`,
in both kernel loops:

```java
for (; i < loopBound && i <= safeEnd; i += SPECIES.length()) {
  VectorMask<Integer> mask = hasNulls
      ? VectorMask.fromLong(SPECIES, validity.get(UNALIGNED_LONG, i / 8L))
      : VectorMask.fromLong(SPECIES, -1L);
  ...
  dstValiditySeg.set(UNALIGNED_LONG, i / 8L,
      dstValiditySeg.get(UNALIGNED_LONG, i / 8L) | mask.toLong());
}
```

`VectorMask.fromLong` consumes only the **lowest `SPECIES.length()` bits** of the long,
and the long is read at byte offset `i / 8`. That pairing is correct only when
`SPECIES.length()` is a multiple of 8, so that every `i` lands on a byte boundary.
`IntVector.SPECIES_PREFERRED` is 8 lanes on AVX2 (the Ryzen benchmark box) and 16 on
AVX-512, but **4 lanes on aarch64 NEON and on x86 without AVX2**.

On a 4-lane machine:

* `i = 4` -> `i / 8 == 0` -> the mask is built from bits 0-3, which describe rows 0-3,
  not rows 4-7.
* The destination write `set(UNALIGNED_LONG, 0, ... | mask.toLong())` ORs those 4 bits
  back into rows 0-3.

The null-free case is broken too: `mask.toLong()` is `0xF`, so at `i = 4` it re-ORs bits
0-3 and rows 4-7 never get their validity bit set. **Half the output rows silently
become NULL** on a column that has no nulls at all. Values are wrong as well, since the
masked load and store use the same bad mask.

`DateVectorOpsTest` would catch this (it covers `n = 1000` with several null patterns);
it simply never runs on a 4-lane host. Anyone on Graviton, Ampere, or an Apple-silicon
laptop gets wrong query results with no error.

Remaining caveat: the fix and its test pin down the bit arithmetic, but no `IntVector`
path has actually executed at 4 lanes. Genuine end-to-end coverage needs an ARM or
SSE-only runner, which depends on finding 4.

### 2. Per-batch `addTaskCompletionListener` retains every result batch for the whole task

**Status: FIXED** in `25f2feb4999` (PR #13). The evaluator now holds a single Arrow
child allocator for the whole task, created lazily on the first kernel batch and closed
by one task-completion listener. Each result batch's rows are wrapped in a
`CompletionIterator` that closes that batch when they run out, and an `openBatches` set
is the safety net the task-completion listener drains when a task stops reading early (a
LIMIT, a failure). `VarkaColumnarToRowExecSuite` gained two tests that assert on
`ArrowUtils.rootAllocator.getAllocatedMemory`: one drains 16 batches and requires the
peak to stay near a single batch and the allocation to return to its baseline, the other
abandons a partly-read iterator and requires task completion to release it. Verified to
catch the original defect: on the pre-fix code the first fails with "the kernel result
batches were not released when their rows ran out".

`sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaColumnarToRowExec.scala:205-236`
(pre-fix line numbers)

`runKernels` was called **once per input batch**, and each call allocated a fresh Arrow
child allocator and registered a fresh task-completion listener that closed the batch
and the allocator. Nothing was released until the task ended. A partition with 1000
batches therefore accumulated 1000 listeners and held 1000 batches worth of off-heap
Arrow memory concurrently: the entire partition's projected output was materialized
off-heap, which is exactly what the streaming iterator model exists to avoid.

The `numVarkaBatches > 1` test (`VarkaDifferentialSuite.scala:192`) exercises this shape
(32 batches) but asserts nothing about memory.

### 3. `outputPartitioning` / `outputOrdering` are not alias-aware

**Status: FIXED** in `1d3c0c40052` (PR #16). `VarkaColumnarToRowExec` now mixes in
`PartitioningPreservingUnaryExecNode` and `OrderPreservingUnaryExecNode` and supplies
`outputExpressions = projectList` and `orderingExpressions = child.outputOrdering`,
exactly as `ProjectExec` does; both traits declare `outputPartitioning` /
`outputOrdering` `final`, so the two pass-through overrides are gone.
`VarkaColumnarToRowExecSuite` gained two tests over a child plan with a real partitioning
and ordering: one where the child is partitioned and ordered by the aliased expression
and both properties survive restated over the output attribute, and one where they are
over `d`, which the projection drops, so they collapse to `UnknownPartitioning` and
`Nil`. Verified to catch the original defect: both fail on the pre-fix code.

`VarkaColumnarToRowExec.scala:75-77` (pre-fix line numbers)

```scala
override def outputPartitioning: Partitioning = child.outputPartitioning
override def outputOrdering: Seq[SortOrder] = child.outputOrdering
```

This node *is* a projection: it renames and drops columns. But it reported its child's
partitioning and ordering verbatim. `ProjectExec` deliberately mixes in
`PartitioningPreservingUnaryExecNode` and `OrderPreservingUnaryExecNode`
(`basicPhysicalOperators.scala:46-51`) to map these through the alias mapping and drop
whatever is no longer in `output`. Copying `ColumnarToRowExec`'s pass-through
implementations is the wrong model, because `ColumnarToRowExec` does not change the
column set.

This mostly failed *conservatively* (`EnsureRequirements` will not match a partitioning
over an attribute that is not in `output`, so it adds an exchange), but it advertised
properties over dead attributes into `EliminateSorts`, exchange reuse and AQE.

## Build and integration

### 4. `varka-engine` is not in the Maven reactor, and no CI builds it

**Status: PARTIALLY FIXED** in `48a9886051b` (PR #12). A composite action,
`.github/actions/install-varka-engine`, builds `sql/varka/engine` and installs it into
the local Maven repository. It runs in every job that compiles or tests Spark
(`build_and_test.yml`, `maven_test.yml`, `python_hosted_runner_test.yml`), and
`build_main.yml` pins the workflow JDK to 25 so the engine's `release 25` sources
compile. A dedicated `varka-engine` job runs the engine's own test suite by passing
`run-tests: 'true'`; the other jobs skip those tests to stay fast. The `sql/core` Varka
suites run with the rest of the `sql` module, so `dev/sparktestsupport/modules.py` needs
no entry of its own.

Still open: the engine is not a reactor module - the root `pom.xml` `<modules>` list is
unchanged - so a Maven build outside CI still depends on the manual install; and every
runner is x86_64, so the residual 4-lane risk from finding 1 remains unexercised.

The original state:

The root `pom.xml` `<modules>` list does not include `sql/varka/engine`, yet
`sql/catalyst/pom.xml:155-160` and `sql/core/pom.xml:301-306` declare a hard
`org.apache.spark.varka:varka-engine:0.1.0-SNAPSHOT` test-scope dependency. sbt resolves
it only because `SparkBuild.scala:319` includes `Resolver.mavenLocal`, i.e. it works
locally because of the manual `./build/mvn -f sql/varka/engine/pom.xml install`.

There is no `varka` reference anywhere under `.github/` or `dev/`, so:

* a clean CI checkout has nothing to resolve `varka-engine` from, and dependency
  resolution for `sql/catalyst` and `sql/core` fails before any test runs;
* none of the Varka test suites are registered in `dev/sparktestsupport/modules.py`, so
  even if resolution succeeded, nothing would run them.

Fix: either add a workflow step that installs the engine before the Spark build, or make
it a real reactor module under a profile. As it stands the MVP's regression net does not
run anywhere but the developer's machine.

This finding also gates the residual risk in finding 1: an aarch64 runner is what would
actually execute the kernels at 4 lanes.

### 5. `VarkaColumnarRule` is registered in one code path, not "on every SparkSession"

**Status: FIXED** in `c6fbfe34d88` (PR #14). The `extensions.injectColumnar` call is
gone from `SparkSession.Builder`, and `BaseSessionStateBuilder.columnarRules` now
returns `extensions.buildColumnarRules(session) :+ VarkaColumnarRule`, so every session
gets the rule exactly once and no user-supplied extensions object is mutated.
`VarkaSharedSessions` no longer injects it by hand.

The rule is *appended*, not prepended as suggested below.
`ApplyColumnarRulesAndInsertTransitions` applies `postColumnarTransitions` in reverse
list order (`Columnar.scala:618-620`), so a trailing entry runs its post transition
first, which leaves user-injected rules the final say over the plan; prepending would
invert that and change the existing ordering. `VarkaAutoRegistrationSuite` gained three
tests: a cloned session carries exactly one rule and still fuses, a classic builder
reused for a second session does not register the rule twice, and `spark.extensions`
does not list the built-in rule. Verified to catch the original defect: the last two
fail on the pre-fix code.

Of the three consequences below, the first is the weakest: sessions made by
`newSession()` / `cloneSession()` inherit the parent's (already mutated) extensions, so
they did get the rule. The double registration and the extensions mutation were the real
defects.

`sql/core/src/main/scala/org/apache/spark/sql/classic/SparkSession.scala:1057-1060`
(pre-fix line numbers)

`extensions.injectColumnar(_ => VarkaColumnarRule)` sat inside `Builder.getOrCreate`,
in the branch that constructs a brand-new session. Consequences:

* sessions built any other way appeared not to get it, which is why
  `VarkaSharedSessions.scala:81` injected it manually on top of `SharedSparkSession`;
* calling `getOrCreate` twice on the same classic builder injected the rule twice into
  the same `SparkSessionExtensions`;
* it mutated a user-supplied extensions object, so `spark.extensions` showed a rule the
  user never added.

The designed hook is `BaseSessionStateBuilder.columnarRules`
(`BaseSessionStateBuilder.scala:420`), which covers every session - cloned, Connect, and
test - with no duplication.

## Design landmines

### 6. `JavaClassFileEngine`'s ghost fallback does not cover the stub

**Status: FIXED**, by the first of the two fixes below: the funnel routing is gone.
`CodeGenerator.compile`'s cache loader is back to a plain `backend.compile(code)`, and
`assembleOrFallback`, `routingEnabledForTesting` and `failAssemblyForTesting` are deleted
with it. `assembleGeneratedClass` and `assembleAndLoad` stay - they are Task 5's
deliverable and the piece a later change will wire up - and `assembleAndLoad` no longer
takes the `CodeAndComment` it never used.

Removal was chosen over guarding because the routing has no production caller at all:
Task 6 wired the live path elsewhere (`VarkaColumnarToRowExec` assembles per-op kernel
dispatchers with `VarkaClassFileGen.assembleKernelClass`). Every guard that keeps the
funnel also makes the funnel's own fallback tests vacuous, because nothing can reach the
catch any more.

`JavaClassFileEngineSuite` is re-pointed at the engine itself: the funnel never assembles
for a Class-File-eligible unit, `assembleAndLoad` returns a shell whose `apply` still
throws - documenting *why* the funnel does not route to it, since assembly, loading and
construction all succeed and a fallback around them sees no failure - and a corrupt
assembly surfaces as a `LinkageError` after the loader is released.

`JavaClassFileEngine.scala:129-146` (pre-fix line numbers) caught assembly and *load*
failures, but the assembled `VarkaProjection.apply` unconditionally throws
`UnsupportedOperationException` (`ClassFileAssembler.java:102-108`). Assembly and
loading both succeeded, so `assembleOrFallback` returned a working-looking
`GeneratedClass` and the failure surfaced at row-evaluation time, past the fallback,
with no recovery. Nothing catches it there either:
`CodeGeneratorWithInterpretedFallback.createObject` wraps only the *construction* of the
projection, not the per-row `apply`.

The only thing preventing that was that `CodeGenerator.compile` gated routing on
`JavaClassFileEngine.routingEnabledForTesting`, a thread-local test flag
(`CodeGenerator.scala:1628`). That was a lot of production code whose sole guard was a
test knob.

Fix: either delete the funnel routing until `apply` is real, or make
`assembleGeneratedClass` refuse to return a stub outside tests.

### 7. `CodeAndComment` ignores `classFileGenOps` in `equals` / `hashCode`

**Status: FIXED**, with a caveat worth recording. `equals` and `hashCode` now cover
`classFileGenOps` as well as `body`, so the key spans every field that can change what
compiling the unit produces, and `JavaClassFileEngineSuite` pins that. Hit rates are
unchanged: a `body` is generated from the same expressions that yield the ops, so equal
bodies already carry equal ops.

The caveat: that alone does *not* separate an assembled entry from a Janino-compiled one.
Routing is decided outside `CodeAndComment`, and both outcomes carry the same `body` and
the same ops, so they would still collide on the key. The parenthetical in the fix below
- key the cache on the routing decision, the way the active `CodeCompiler` backend
already is - is the part that actually separates them, and it belongs with the change
that wires routing back in. With the funnel routing deleted (finding 6) there is a single
backend again, so nothing can collide today. The class scaladoc says as much, so the
equality fix is not read as more than it is.

`CodeGenerator.scala:1451-1462` (pre-fix line numbers). The compile cache is keyed on
`CodeAndComment`, and `equals` compared only `body`. The doc's claim that "the winning
path is cached under the same key so a failed assembly is never retried" is precisely
the hazard: once
routing is real, a Janino-compiled entry and a Varka-assembled entry for the same body
become interchangeable in the cache.

Fix: include the ops in the key (or key the cache on the routing decision) before
enabling routing.

### 8. `extractMorsel` assumes `numRows == valueCount`

`VarkaColumnarToRowExec.scala:344-350`. `nullCount` comes from the whole Arrow vector
but is compared against the batch's `len`:

```scala
val validity = if (nullCount == len) null else ofAddress(ddv.getValidityBuffer())
```

For a vector with `valueCount = 100` and `nullCount = 10`, in a batch with
`numRows = 10`, this takes the all-null branch and the kernel emits 10 nulls for 10
non-null rows. The `require` only checks `len <= valueCount`. The Arrow cache path
happens to keep them equal, but the invariant is not enforced.

Fix: make it `require(len == ddv.getValueCount())`, or compute the null count over
`[0, len)`.

## Performance

### 9. Both paths add a per-row `.copy()` that the standard path does not have

`VarkaColumnarToRowExec.scala:204` and `:264`:

```scala
input.rowIterator().asScala.map(r => fallbackProjection(r).copy())   // fallback
resultBatch.rowIterator().asScala.map(r => toRow(r).copy())          // kernel path
```

The stock evaluator is `input.rowIterator().asScala.map(toUnsafe)`, with no copy
(`ColumnarEvaluatorFactory.scala:50`). This pays an extra `UnsafeRow` allocation plus a
memcpy on **every row**, on the hot path, in both branches. That is very likely a large
part of why the end-to-end win is 1.1-1.2x while the kernel microbenchmark shows 3x:
row-conversion overhead was added to buy back SIMD arithmetic.

Fix: drop the copies unless a specific consumer needs them (and if one does, that is
`ColumnarToRowExec`'s problem too, not Varka's).

### 10. The vector loop is disabled entirely for batches under 57 rows

The vector loop stops at the last row covered by a whole 64-bit word of the validity
bitmap (`DateVectorOps.wordAlignedEnd`, previously the equivalent `safeEnd`). A batch of
fewer than 57 rows has a bitmap under 8 bytes, so the bound is 0 and the whole batch
goes scalar.

`VarkaMorsel`'s javadoc (`VarkaMorsel.java:37-41`) says segments are deliberately sized
to the `ArrowBuf` capacity so the last validity word never trips a bounds check, but
`DateVectorOps.ofAddress` re-wraps at exactly `(length + 7) / 8` and throws that away.

Fix: honour the morsel contract (pass capacities through, or reinterpret to a rounded-up
size); then the word-alignment bound can be dropped and the tail vectorized too.

The fix for finding 1 widened the bound slightly as a side effect - a 1000-row batch now
vectorizes to row 960 rather than 936, and a 64-row batch vectorizes at all - but the
sub-57-row gap is unchanged.

Related: `zero()` zeroes the destination validity byte-by-byte in a Java loop,
immediately after `BaseFixedWidthVector.allocateNew` has already zeroed it. Use
`MemorySegment.fill` at minimum; better, skip it.

### 11. Reflective `Method.invoke` with boxed arguments

`VarkaColumnarToRowExec.scala:319-329` and `:405-406` box every argument into
`java.lang.Long` / `java.lang.Integer` per batch. Per batch this is noise, but the whole
point of the generated dispatcher was an `invokestatic` with a primitive stack, and
reaching it through `Method.invoke` undoes that.

Fix: use a `MethodHandle`, or have the dispatcher implement a small generated interface,
so the primitive path survives.

## Smaller things

* ~~**Scalastyle will fail**: `VarkaThroughputBenchmark.scala:42` is 102 characters.~~
  Fixed in `48a9886051b` (PR #12).
* ~~**`docs/sql-varka.md` is orphaned**: no entry in `docs/_data/menu-sql.yaml`, so the
  page never appears in the site navigation.~~ Fixed: listed under Performance Tuning,
  next to Arrow Cache Format. The page already had the `title` / `displayTitle` front
  matter the layout needs, so the menu entry was all that was missing.
* **Duplicated eligibility logic**: `VarkaColumnarToRowExec.foldDaysOffset` (`:357-360`)
  is a verbatim copy of `DateVarkaSupport.foldDaysOffset`
  (`datetimeExpressions.scala:521-524`), because the latter is `private[expressions]`.
  Widen the visibility rather than forking the rule - the two must stay in lockstep or
  the rule and the exec will disagree about eligibility.
* ~~**Two identical class loaders**: `VarkaGeneratedClassLoader.scala` (catalyst) and
  `VarkaClassLoader.java` (engine) are the same class; the engine copy is currently
  exercised only by its own test. If it is meant as the shared contract, only one should
  exist.~~ Resolved as by-design, and documented as such on both sides. Neither can be
  the shared one: the engine is a standalone module outside the reactor, a test-scope
  dependency of catalyst, and deployed externally at runtime, so catalyst cannot compile
  against it - and the engine cannot depend on catalyst either. Deleting the engine copy
  would also cost real coverage: `VarkaClassLoaderTest` pins the define/release semantics
  and the Metaspace-unload proof against it, and `DateVectorOpsEmissionTest` loads its
  probe class through it. Both javadocs now say the duplication is deliberate and that a
  change to one belongs in the other.
* **Non-local return in a closure**: `buildOutputPlan`
  (`VarkaColumnarToRowExec.scala:382`) uses `return None` inside a `map`. It works on
  2.13 via an exception and is deprecated in 3; `collectFirst` / `traverse` reads better.
* ~~**Redundant node rebuild**: `VarkaColumnarRule.scala:47` returns
  `ProjectExec(projectList, child)` in the not-columnar branch, allocating an equal copy
  of the node instead of leaving it alone.~~ Fixed: the case binds the matched node and
  returns it.
* ~~**Mis-indented comment block**: `VarkaDifferentialSuite.scala:73-78` starts at
  column 0 inside a method body.~~ Fixed: reflowed at the enclosing indentation.
* ~~**`arrow.version` is hardcoded to 19.0.0** in `sql/varka/engine/pom.xml` and
  happens to match the root pom today. It will drift silently the next time Spark bumps
  Arrow, and the engine writes into Spark-allocated Arrow buffers, so a mismatch is not
  benign.~~
  Fixed as far as it can be: the engine has no parent pom to inherit the property from
  (that is finding 4's design), so the value still has to be repeated, but it can no
  longer drift silently. `dev/check_varka_arrow_version.py` compares the two poms and
  fails with the value to change; the `install-varka-engine` CI action runs it before
  building, so every job that needs the engine checks it, and the engine pom now says
  what the value is coupled to.

## What is solid

The fallback discipline is genuinely good: `isCatchable` covering `NonFatal` plus
`LinkageError`, per-batch fallback, the Arrow-backing guard in `isArrowBacked`, and the
`setFailKernelForTesting` injection that proves the fallback actually fires.

`DateVectorOpsTest`'s differential design (Arrow's own accessors as the oracle, sizes
straddling every lane and byte boundary, five null patterns, extreme offsets) is the
right shape and would have caught finding 1 on the right host.

`VarkaGeneratedClassLoaderSuite`'s weak-reference Metaspace check is a real proof, not a
smoke test.

The docs are honest about scope, including the "class-file routing is still a stub"
note. That candour is worth keeping.

## Suggested order

1. ~~Fix the lane-alignment bug (finding 1) and add a narrow-species test.~~ Done in
   `a6b7f7d3541`; the residual 4-lane hardware coverage still depends on finding 4.
2. ~~Fix the per-batch listener leak (finding 2).~~ Done in `25f2feb4999`.
3. ~~Get the engine into CI (finding 4) so 1 and 2 stay fixed.~~ Done in `48a9886051b`,
   apart from the reactor module and an aarch64 runner.
4. ~~Move the registration point (finding 5).~~ Done in `c6fbfe34d88`.
5. ~~Alias-awareness (finding 3).~~ Done in `1d3c0c40052`.
6. ~~The class-file routing pair (findings 6 and 7).~~ Done together, as one change:
   both gated turning class-file routing on and both touched `CodeGenerator.scala`.
7. Next: the `numRows == valueCount` invariant (8) and the `.copy()` removal (9), both in
   `VarkaColumnarToRowExec.scala`.
