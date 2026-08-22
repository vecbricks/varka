# Varka MVP - code review findings

Review of the Varka code as of `b56e9f7f34a` (`sql/varka/`, the catalyst hooks, the
`sql/core` exec node and rule, and `docs/sql-varka.md`).

The review itself was a reading of the code; nothing was built or run to produce it.
The engine test suite has since been run while fixing finding 1, but the `sql/core`
Varka suites still have not been executed.

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

**Status: FIXED** on branch `varka-simd-lane-mask`. The bitmap is now addressed one
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

`sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaColumnarToRowExec.scala:205-236`

`runKernels` is called **once per input batch**, and each call allocates a fresh Arrow
child allocator and registers a fresh task-completion listener that closes the batch and
the allocator. Nothing is released until the task ends. A partition with 1000 batches
therefore accumulates 1000 listeners and holds 1000 batches worth of off-heap Arrow
memory concurrently: the entire partition's projected output is materialized off-heap,
which is exactly what the streaming iterator model exists to avoid.

The `numVarkaBatches > 1` test (`VarkaDifferentialSuite.scala:192`) exercises this shape
(32 batches) but asserts nothing about memory.

Fix: hoist one child allocator to the evaluator (closed by a single task-completion
listener) and wrap each batch's row iterator in a `CompletionIterator` that closes that
batch when its rows are exhausted. Keep the task-completion listener only as a safety
net for early task termination.

### 3. `outputPartitioning` / `outputOrdering` are not alias-aware

`VarkaColumnarToRowExec.scala:73-75`

```scala
override def outputPartitioning: Partitioning = child.outputPartitioning
override def outputOrdering: Seq[SortOrder] = child.outputOrdering
```

This node *is* a projection: it renames and drops columns. But it reports its child's
partitioning and ordering verbatim. `ProjectExec` deliberately mixes in
`PartitioningPreservingUnaryExecNode` and `OrderPreservingUnaryExecNode`
(`basicPhysicalOperators.scala:46-51`) to map these through the alias mapping and drop
whatever is no longer in `output`. Copying `ColumnarToRowExec`'s pass-through
implementations is the wrong model, because `ColumnarToRowExec` does not change the
column set.

Today this mostly fails *conservatively* (`EnsureRequirements` will not match a
partitioning over an attribute that is not in `output`, so it adds an exchange), but it
advertises properties over dead attributes into `EliminateSorts`, exchange reuse and
AQE.

Fix: mix in the two alias-aware traits, or - safest for the MVP - return
`UnknownPartitioning(child.outputPartitioning.numPartitions)` and `Nil`.

## Build and integration

### 4. `varka-engine` is not in the Maven reactor, and no CI builds it

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

`sql/core/src/main/scala/org/apache/spark/sql/classic/SparkSession.scala:1057-1060`

`extensions.injectColumnar(_ => VarkaColumnarRule)` sits inside `Builder.getOrCreate`,
in the branch that constructs a brand-new session. Consequences:

* sessions built any other way do not get it, which is exactly why
  `VarkaSharedSessions.scala:81` has to inject it manually on top of
  `SharedSparkSession`;
* calling `getOrCreate` twice on the same builder injects the rule twice into the same
  `SparkSessionExtensions`;
* it mutates a user-supplied extensions object, so `spark.extensions` shows a rule the
  user never added.

The designed hook is `BaseSessionStateBuilder.columnarRules`
(`BaseSessionStateBuilder.scala:406`). Prepending `VarkaColumnarRule` there covers every
session - cloned, Connect, and test - with no duplication.

## Design landmines

### 6. `JavaClassFileEngine`'s ghost fallback does not cover the stub

`JavaClassFileEngine.scala:129-146` catches assembly and *load* failures, but the
assembled `VarkaProjection.apply` unconditionally throws `UnsupportedOperationException`
(`ClassFileAssembler.java:102-108`). Assembly and loading both succeed, so
`assembleOrFallback` returns a working-looking `GeneratedClass` and the failure surfaces
at row-evaluation time, past the fallback, with no recovery.

The only thing preventing that today is that `CodeGenerator.compile` gates routing on
`JavaClassFileEngine.routingEnabledForTesting`, a thread-local test flag
(`CodeGenerator.scala:1628`). That is a lot of production code whose sole guard is a
test knob.

Fix: either delete the funnel routing until `apply` is real, or make
`assembleGeneratedClass` refuse to return a stub outside tests.

### 7. `CodeAndComment` ignores `classFileGenOps` in `equals` / `hashCode`

`CodeGenerator.scala:1451-1462`. The compile cache is keyed on `CodeAndComment`, and
`equals` compares only `body`. The doc's claim that "the winning path is cached under
the same key so a failed assembly is never retried" is precisely the hazard: once
routing is real, a Janino-compiled entry and a Varka-assembled entry for the same body
become interchangeable in the cache.

Fix: include the ops in the key (or key the cache on the routing decision) before
enabling routing.

### 8. `extractMorsel` assumes `numRows == valueCount`

`VarkaColumnarToRowExec.scala:312-319`. `nullCount` comes from the whole Arrow vector
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

`VarkaColumnarToRowExec.scala:191` and `:225`:

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

`VarkaColumnarToRowExec.scala:287-298` and `:373-375` box every argument into
`java.lang.Long` / `java.lang.Integer` per batch. Per batch this is noise, but the whole
point of the generated dispatcher was an `invokestatic` with a primitive stack, and
reaching it through `Method.invoke` undoes that.

Fix: use a `MethodHandle`, or have the dispatcher implement a small generated interface,
so the primitive path survives.

## Smaller things

* **Scalastyle will fail**: `VarkaThroughputBenchmark.scala:42` is 102 characters.
* **`docs/sql-varka.md` is orphaned**: no entry in `docs/_data/menu-sql.yaml`, so the
  page never appears in the site navigation.
* **Duplicated eligibility logic**: `VarkaColumnarToRowExec.foldDaysOffset` (`:325-328`)
  is a verbatim copy of `DateVarkaSupport.foldDaysOffset`
  (`datetimeExpressions.scala:521-524`), because the latter is `private[expressions]`.
  Widen the visibility rather than forking the rule - the two must stay in lockstep or
  the rule and the exec will disagree about eligibility.
* **Two identical class loaders**: `VarkaGeneratedClassLoader.scala` (catalyst) and
  `VarkaClassLoader.java` (engine) are the same class; the engine copy is currently
  exercised only by its own test. If it is meant as the shared contract, only one should
  exist.
* **Non-local return in a closure**: `buildOutputPlan`
  (`VarkaColumnarToRowExec.scala:350`) uses `return None` inside a `map`. It works on
  2.13 via an exception and is deprecated in 3; `collectFirst` / `traverse` reads better.
* **Redundant node rebuild**: `VarkaColumnarRule.scala:47` returns
  `ProjectExec(projectList, child)` in the not-columnar branch, allocating an equal copy
  of the node instead of leaving it alone.
* **Mis-indented comment block**: `VarkaDifferentialSuite.scala:73-78` starts at column 0
  inside a method body.
* **`arrow.version` is hardcoded to 19.0.0** in `sql/varka/engine/pom.xml` and happens to
  match the root pom today. It will drift silently the next time Spark bumps Arrow, and
  the engine writes into Spark-allocated Arrow buffers, so a mismatch is not benign.

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

1. ~~Fix the lane-alignment bug (finding 1) and add a narrow-species test.~~ Done on
   branch `varka-simd-lane-mask`; the residual 4-lane hardware coverage depends on
   finding 4.
2. Fix the per-batch listener leak (finding 2).
3. Get the engine into CI (finding 4) so 1 and 2 stay fixed.
4. Then alias-awareness (3), the registration point (5), and the `.copy()` removal (9).
