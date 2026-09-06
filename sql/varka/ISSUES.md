# Varka MVP - code review findings

Review of the Varka code as of `b56e9f7f34a` (`sql/varka/`, the catalyst hooks, the
`sql/core` exec node and rule, and `docs/sql-varka.md`).

The review itself was a reading of the code; nothing was built or run to produce it.
Every numbered finding has since been fixed and merged - PRs #11, #13, #16, #12, #14,
#17, #18, #19 and, for finding 11, the change this file ships with - apart from the
residual part of finding 4, which is recorded in its own section. The engine test suite
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

**Status: FIXED** in `a6b7f7d3541` (PR #11). The bitmap is addressed with an explicit
shift rather than at byte `row / 8`, via the `validityBitsAt` / `orValidityBitsAt`
helpers, which both kernels share. (The fix for finding 10 later narrowed those helpers
from a 64-bit word to the bytes each lane group occupies, and dropped the
`wordAlignedEnd` bound they needed; the shift, and this fix, are unchanged.)
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

Remaining caveat, narrowed by task 23: the aarch64 CI job added in `1cf964c9abe` runs
the engine module's JUnit classes on `ubuntu-24.04-arm`, at the host width and again
under `-XX:MaxVectorSize=16`, so the hand-written kernels' `IntVector` path now does
execute at 4 lanes on real ARM hardware. The emitted loops still do not: the catalyst
and sql/core jobs that run them are x86_64 only and skip the engine's tests. Closing
that means putting those suites on the arm matrix leg as well.

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

**Status: FIXED**. `48a9886051b` (PR #12) put the engine in CI; the change this file ships
with closes the two parts that were left over.

`sql/varka/engine` is now a module of the root `pom.xml`, listed before `sql/api` and
`sql/catalyst`. Maven orders it ahead of catalyst on its own, from catalyst's declared
dependency on it, so a plain `./build/mvn install` builds and installs the engine before
anything that needs it and no manual step is left in a Maven build. It keeps its own pom
rather than inheriting `spark-parent`, because its sources need the incubator Vector API and
its tests need native-access flags the Spark build does not set, and because inheriting
would apply the Scala, shading and packaging plugins it has no use for. It therefore still
builds standalone with `-f sql/varka/engine/pom.xml`, which is how the composite action puts
it into the local Maven repository; the Maven test jobs build a subset of modules with `-pl`
and no `-am`, so they resolve it from there and the action stays.

Adding the module changes the sbt build too, because sbt reads its projects from the same
poms (sbt-pom-reader): the engine became an sbt project named `engine`, and the aggregate root
now compiles it. It needs three things `project/SparkBuild.scala` did not give it, all in
`VarkaEngine.settings`, since the engine is deliberately not in `allProjects` - it is a plain
Java module none of the publishing, genjavadoc, MiMa or scalastyle settings apply to. javac
needs `--add-modules jdk.incubator.vector`, which sbt does not take from the pom.
`src/jmh/java` has to come back out of `Compile`: it is a Maven *test* source directory, added
by build-helper, and sbt-pom-reader maps it into `Compile`, where its test-scope jmh-core
dependency is not on the classpath. And the module names no Scala version, so pom-reader falls
back to its own default and would otherwise put a second scala-library on catalyst's
classpath. The engine is also excluded from unidoc, which is a deny-list.

Two more consequences, neither of them a setting on the engine project. sbt-pom-reader does
not turn catalyst's and `sql/core`'s test-scope dependency on the engine into a project
dependency once the engine is a module of the same build - it drops it, so the Varka suites
lose the classes they load by name and fail with
`ClassNotFoundException: org.apache.spark.sql.varka.vector.DateVectorOps`. Both modules now
put the engine project's jar on their test classpath explicitly
(`VarkaEngineDependency.settings`), which is also better than what they had: the suites run
against the engine in the working tree rather than against whatever the local Maven repository
holds. And zinc extracts a compiled Java class's API by loading it in the sbt JVM, so that JVM
needs the incubator module too - without it the engine's sources compile and the compile task
then dies with `NoClassDefFoundError: jdk/incubator/vector/Vector`. `.sbtopts` passes
`-J--add-modules=jdk.incubator.vector`.

Having no parent has a cost that being in the reactor makes visible: `dev/lint-java` runs
`checkstyle:check` over every module, and the plugin resolves a relative `configLocation`
against the *parent* project's directory. With no parent the engine was checked against the
plugin's default sun_checks - 294 violations. The engine pom now configures checkstyle
itself, pointing at `${maven.multiModuleProjectDirectory}/dev/checkstyle.xml`, which is the
repository root whether the reactor or that pom alone is built. Against Spark's actual rules
the engine had two violations, both missing trailing newlines, now fixed. The engine's Java
is linted by `dev/lint-java` from here on.

The 4-lane risk is covered from two directions. The engine's own build now runs its suite
twice: once at the host's preferred vector width, and once with `-XX:MaxVectorSize=16`,
which makes `IntVector.SPECIES_PREFERRED` four lanes - the shape of a 128-bit NEON machine.
That second run passes `-Dvarka.expected.int.lanes=4`, and
`DateVectorOpsTest.preferredSpeciesIsTheWidthTheRunAskedFor` fails if the JVM did not honour
it, so the run cannot silently degrade into a duplicate of the first. Independently, the
`varka-engine` CI job is now a matrix over `ubuntu-latest` and `ubuntu-24.04-arm`, so the
kernels also execute on real aarch64 hardware. Both were needed: the capped-width run covers
the four-lane *code path* on every runner, and the arm runner covers the four-lane *machine*.

The original partial fix:

A composite action,
`.github/actions/install-varka-engine`, builds `sql/varka/engine` and installs it into
the local Maven repository. It runs in every job that compiles or tests Spark
(`build_and_test.yml`, `maven_test.yml`, `python_hosted_runner_test.yml`), and
`build_main.yml` pins the workflow JDK to 25 so the engine's `release 25` sources
compile. A dedicated `varka-engine` job runs the engine's own test suite by passing
`run-tests: 'true'`; the other jobs skip those tests to stay fast. The `sql/core` Varka
suites run with the rest of the `sql` module, so `dev/sparktestsupport/modules.py` needs
no entry of its own.

What that left open, and what the change this file ships with closes: the engine was not a
reactor module - the root `pom.xml` `<modules>` list was unchanged - so a Maven build outside
CI still depended on the manual install; and every runner was x86_64, so the residual 4-lane
risk from finding 1 was unexercised.

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

*(Later, milestone 2: nothing ever wired them up. The whole shell - engine, assembler
and their suites - was deleted after the task-10 loop emitter shipped the real
Class-File-API-generated compute; see `plans/PLAN_TASK_9.md` section 5.4.)*

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

**Status: FIXED**. `isArrowBacked` - the per-batch eligibility guard - now also requires
`ddv.getValueCount() == input.numRows()`, so a vector holding rows beyond the batch takes
the per-row fallback and never reaches the kernels; the `require` in `extractMorsel` was
tightened from `<=` to `==` to state the same invariant where the null count is read. The
guard was the better of the two places the finding suggests: rejecting the batch before
`buildVector` allocates the destination vector avoids throwing an
`IllegalArgumentException` through the kernel-failure path, which would have logged a
misleading "the Varka SIMD kernels failed on this batch". Serving such a batch from the
kernels instead - the finding's other option - means counting nulls over `[0, len)`, and
is worth doing if a producer of these batches ever turns up.

`VarkaColumnarToRowExecSuite` gained a test over a vector of 20 rows - ten dates, then ten
nulls - in a batch declaring ten. Verified to catch the original defect: on the pre-fix
code it returns ten nulls where the ten dates should be.

`VarkaColumnarToRowExec.scala:344-350` (pre-fix line numbers). `nullCount` comes from the
whole Arrow vector
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

**Status: FIXED**. Both branches now emit the projection's own row - `map(toRow)` and
`map(fallbackProjection)` - exactly as `ColumnarToRowEvaluatorFactory` does. Dropping the
copy is safe as well as cheaper: an `UnsafeProjection` writes into its own buffer rather
than a view of the batch, so the row it hands back outlives the release of the kernel
result batch it came from. `VarkaColumnarToRowExecSuite` gained a test that pulls rows
from both paths and asserts the same row object comes back rewritten in place, and that a
kernel row still reads correctly after its batch has been closed. Verified to catch the
original defect: it fails on the pre-fix code.

One existing test had to change with it. `VarkaDifferentialSuite` collected its rows
with `queryExecution.toRdd.collect()`, which builds an array of row references per
partition and so needs the rows copied first; the removed `.copy()` had been masking
that. This is not a Varka rule - collecting the same query from the row engine that way
returns two distinct row objects for five rows, with wrong values - so the test now
copies, as Spark's own `Dataset.collect` does. Anything reading `toRdd` directly is
subject to the same contract it always was under `ColumnarToRowExec`.

The end-to-end effect on the 1.1-1.2x figure has not been measured; that wants a
benchmark run, not a unit test.

`VarkaColumnarToRowExec.scala:204` and `:264` (pre-fix line numbers):

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

**Status: FIXED**. `validityBitsAt` and `orValidityBitsAt` now take the lane width and
touch only the bytes that group occupies - one byte for widths up to 8, two for 16, four
for 32, eight for 64 - at byte `row / 8`, rather than always addressing a 64-bit word. A
group whose rows are all below `length` can then only touch bytes below `(length + 7) /
8`, so the vector loop runs to `SPECIES.loopBound(length)` and `wordAlignedEnd` is gone.
Both the sub-57-row range and the tail of every larger batch are vectorized.

This is a third option, not either of the two the finding suggests. Passing the buffer
capacities through would change the kernel ABI, which the codegen descriptors and the
exec node both pin; reinterpreting to a rounded-up size would stop the *segment* bounds
check without making the memory beyond the bitmap any more real. Reading only the bytes
the group occupies needs neither, and stays inside the nominal bitmap.

`DateVectorOpsBitmapTest` allocates every bitmap at exactly `(length + 7) / 8` bytes, so
an overrun throws rather than reading a neighbouring allocation, and walks every lane
group of every length from 1 to 200 across all seven lane widths. Verified to catch the
original defect twice over: restoring word addressing under the unbounded loop fails the
new test at `byteSize: 1` for the sub-57-row lengths, and also breaks `DateVectorOpsTest`
end to end at `byteSize: 2` - which is exactly why the bound existed.

Measured with the JMH harness, which gained a 32-row size for this (it had only 10000 and
1000000, so it could not see the defect at all). At 32 rows, throughput in ops/ms, three
iterations:

| kernel | before | after |
|---|---|---|
| `vectorAddDays` NULL_FREE | 34576 | 110612 |
| `vectorAddDays` MIXED_NULL | 21620 | 72660 |
| `vectorSubDays` NULL_FREE | 20996 | 73862 |
| `vectorSubDays` MIXED_NULL | 19685 | 78211 |
| `vectorDateDiff` NULL_FREE | 31711 | 72067 |
| `vectorDateDiff` MIXED_NULL | 18430 | 62630 |

The telling comparison is against the scalar kernels at the same size, which ran at 27000
to 36000 ops/ms: before the fix the vector kernels matched them, because at 32 rows they
*were* the scalar path. After it they are roughly 3x faster, which is what the
microbenchmark reports at large sizes. The 10000- and 1000000-row numbers moved within
their error bars, so this run says nothing about the tail; only the small-batch effect is
measured here.

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

**Status: FIXED**. The second of the two options the finding lists. Each generated runner
now implements the kernel-shape interface matching its descriptor - `VarkaUnaryKernel`
for a one-input kernel with a scalar argument, `VarkaBinaryKernel` for a two-input one -
so `invokeKernel` reaches the kernel with an ordinary interface call and the arguments
stay primitive from the caller's stack into the kernel. Nothing is boxed and no argument
array is allocated per batch.

The generated `run` became an instance method to implement the interface, so the
parameters start at slot 1; `ClassFileGenOpVerifier` reads the first slot off the method's
own static flag rather than assuming one. Two things fell out with the reflection:
`paramClasses`, which parsed the descriptor at runtime to find the `Method`, and the
`java.lang.reflect` import.

A `MethodHandle` - the finding's other option - would have kept the descriptor parsing and
needed `invokeExact` to avoid boxing, which is awkward to reach from Scala. The interface
also states the kernel contract in one readable place, which is where the null and address
conventions now live.

`ClassFileCodegenSupportSuite` gained three tests: that a runner assembled for each op
really implements the expected interface (it is instantiated, so the class is linked and
a merely same-named method would not pass), that an unknown descriptor is rejected rather
than producing a class implementing nothing, and that each interface's `DESCRIPTOR`
constant still matches its `run` signature - the constant is what matches kernels to
interfaces, so a drifted one would silently mis-dispatch.

## Smaller things

* ~~**Scalastyle will fail**: `VarkaThroughputBenchmark.scala:42` is 102 characters.~~
  Fixed in `48a9886051b` (PR #12).
* ~~**`docs/sql-varka.md` is orphaned**: no entry in `docs/_data/menu-sql.yaml`, so the
  page never appears in the site navigation.~~ Fixed: listed under Performance Tuning,
  next to Arrow Cache Format. The page already had the `title` / `displayTitle` front
  matter the layout needs, so the menu entry was all that was missing.
* ~~**Duplicated eligibility logic**: `VarkaColumnarToRowExec.foldDaysOffset` (`:357-360`)
  is a verbatim copy of `DateVarkaSupport.foldDaysOffset`
  (`datetimeExpressions.scala:521-524`), because the latter is `private[expressions]`.
  Widen the visibility rather than forking the rule - the two must stay in lockstep or
  the rule and the exec will disagree about eligibility.~~ Fixed as suggested:
  `DateVarkaSupport` is now `private[sql]`, its scaladoc says why, and the copy in the
  exec node is gone.
* ~~**Two identical class loaders**: `VarkaGeneratedClassLoader.scala` (catalyst) and
  `VarkaClassLoader.java` (engine) are the same class; the engine copy is currently
  exercised only by its own test. If it is meant as the shared contract, only one should
  exist.~~ Resolved as by-design, and documented as such on both sides. Neither can be
  the shared one: the engine is only a test-scope dependency of catalyst and is deployed
  externally at runtime, so catalyst cannot compile against it - and the engine cannot depend on catalyst either. Deleting the engine copy
  would also cost real coverage: `VarkaClassLoaderTest` pins the define/release semantics
  and the Metaspace-unload proof against it, and `DateVectorOpsEmissionTest` loads its
  probe class through it. Both javadocs now say the duplication is deliberate and that a
  change to one belongs in the other. Task 23 ported the catalyst copy from Scala to Java,
  so the two bodies now differ only in class name and package.
* ~~**Non-local return in a closure**: `buildOutputPlan`
  (`VarkaColumnarToRowExec.scala:382`) uses `return None` inside a `map`. It works on
  2.13 via an exception and is deprecated in 3; `collectFirst` / `traverse` reads
  better.~~ Fixed: the per-expression match moved to an `outputOp` helper returning
  `Option[OutputOp]`, and `buildOutputPlan` is `Option.when(ops.forall(_.isDefined))`.
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
   `a6b7f7d3541`; the 4-lane coverage it depended on finding 4 for is in place too - a
   capped-width test run and an aarch64 CI runner.
2. ~~Fix the per-batch listener leak (finding 2).~~ Done in `25f2feb4999`.
3. ~~Get the engine into CI (finding 4) so 1 and 2 stay fixed.~~ Done in `48a9886051b`, and
   completed by the change this file ships with: reactor module, and 4-lane coverage from
   both a capped-width test run and an aarch64 runner.
4. ~~Move the registration point (finding 5).~~ Done in `c6fbfe34d88`.
5. ~~Alias-awareness (finding 3).~~ Done in `1d3c0c40052`.
6. ~~The class-file routing pair (findings 6 and 7).~~ Done together, as one change:
   both gated turning class-file routing on and both touched `CodeGenerator.scala`.
7. ~~The `numRows == valueCount` invariant (8) and the `.copy()` removal (9).~~ Done
   together, both in `VarkaColumnarToRowExec.scala`.
8. ~~The sub-57-row vector loop (10), which is engine-only, then the reflective
   `Method.invoke` (11).~~ Done in `b58bc2fc87e` and in the change this file ships with.
9. ~~Left: the residual half of finding 4 - an aarch64 runner to exercise the kernels at
   4 lanes. The other half of that residual, `sql/varka/engine` as a reactor module,
   shipped in PR #22, so a plain `./build/mvn install` builds the engine today.~~ The
   runner exists: `1cf964c9abe` gave the `varka-engine` job a matrix over
   `ubuntu-latest` and `ubuntu-24.04-arm`, and it ran green on PR #51. It runs the
   engine module alone, so what it closes is the kernels' half. The emitter's half is
   still open, and task 23 split the record accordingly (`PLAN_MILESTONE_3.md`
   section 5): the catalyst and sql/core Varka suites are x86_64 only, so an emitted
   loop has never executed on ARM - only under `-XX:MaxVectorSize=16`, which is the
   narrow species on the same instruction set.

### 12. The engine jar is not in the distribution

Found by task 62's driver, the first thing to run Varka through `bin/spark-submit`
of a packaged tree rather than under sbt (PLAN_TASK_62.md 2.5). `sql/varka/engine`
is a test-scope dependency of the build, so `build/sbt package` and the assembly
leave its jar out, yet every kernel the emitter produces links against
`VarkaVectorSupport` from it. A distribution with `spark.sql.codegen.varka.enabled`
on and no engine jar therefore declines every batch with a `ClassNotFoundException`
in the log and measures the row engine under the kernel's name - the plan says
`VarkaProjectExec`, the numbers are Janino's. The launcher already adds
`--add-modules=jdk.incubator.vector`, so the module is not the problem; the jar is.
The shell driver works around it with `--driver-class-path`; the fix is to make the
engine a runtime dependency of `sql/core` so the assembly ships it, which is a
build change with its own task.
