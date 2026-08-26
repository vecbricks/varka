---
layout: global
title: Varka - SIMD Date Arithmetic over Arrow
displayTitle: Varka - SIMD Date Arithmetic over Arrow
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Varka is a research/experimental execution engine inside this Spark fork. It
compiles whole date-expression projections into a single SIMD vector loop -
bytecode emitted with the JDK 25 Class-File API, running the Vector API over
zero-copy Panama `MemorySegment` views of Arrow `DateDayVector` buffers -
bypassing Spark's per-row code generation on the happy path.

## Overview

The Spark SQL runtime normally executes expressions by generating Java source,
compiling it with Janino, and running one expression evaluation per row. Varka
eliminates that runtime compilation overhead (string generation and Janino
parsing) and unlocks SIMD by operating on whole columnar batches at once.
Since milestone 2 it does not dispatch to per-op kernels: an eligible
projection is compiled to a vector IR and *fused* - however many expressions
and however deep their nesting, the emitted class runs one loop with one load
per input column and one store per output.

The supported expression surface, over `DateType` columns (stored as `INT`
days since epoch) and foldable integer day offsets:

* `DATE_ADD` / `DATE_SUB` / `DATEDIFF`, nested to any depth up to the
  emitter's chain cap, including chains mixing them.
* `CASE WHEN` and `IF` over date comparisons (`<`, `<=`, `>`, `>=`, `=`) and
  their `AND` / `OR` / `NOT` combinations - executed branch-free by mask
  blend, with SQL's three-valued null semantics.
* `GREATEST` / `LEAST` (null-skipping) and `DAYOFWEEK` / `WEEKDAY`.
* Common subtrees shared *across* outputs are computed once per lane group
  (DAG-CSE), which no per-row engine can keep in a vector register.

A projection does not have to be fully eligible: eligible entries fuse,
untouched input columns are forwarded zero-copy, and the remaining entries run
the standard row path per row, merged with the kernel outputs (task 12).

Explicitly out of scope are `CalendarInterval` (months/years), strings,
decimals and nested/complex types. Only integer day offsets are supported.

Varka is designed as a drop-in, zero-risk replacement: every Varka path falls
back to the standard row engine on any failure, so results are always correct.

## Architecture

### Columnar morsels

Spark's `DateType` columns reach Varka as Arrow `DateDayVector` (int32 days)
with a bit-packed validity buffer (1 bit per row, bit set = valid). A morsel
maps the data and validity buffers onto zero-copy Panama `MemorySegment`s, so
no heap objects are built per row:

    data segment     -> bytes of `4 * rowCount`
    validity segment -> bytes of `(rowCount + 7) / 8`, only when the column has
                        neither no nulls nor is fully null

The validity buffer is bit-packed. A byte-per-lane read would be a correctness
bug; the kernels instead load a `long` and build a `VectorMask` with
`VectorMask.fromLong`.

### The emitted fused loop

The live compute path since milestone 2. `VarkaExpressionCompiler` translates
an eligible projection into a small vector IR (`VarkaVectorIR` - column refs,
literal slots, arithmetic, comparisons, conditionals); `VarkaLoopEmitter`
assembles a class implementing `VarkaFusedKernel` from it with the Class-File
API - no Java source, no Janino, no external bytecode library. The emitted
class has a deliberate method anatomy:

* A per-batch dispatch picks one of two twin bodies: a *dense* body with
  unmasked loads and stores when every input is null-free (measured 2.3-2.9x
  the masked body in task 10), and a *masked* body that builds a
  `VectorMask` per lane group from the bit-packed validity words otherwise.
* The vector walk is split into sibling loop methods of at most
  `GROUP_BUDGET` (16) IR nodes each, one output group per method, plus a
  shared scalar-tail method. Separate methods, not one big loop: each gets
  its own C2 compilation, so no method's inlining budget can starve
  another's intrinsics - task 10 measured 3-4x on exactly that cliff.
* Interned subtrees (DAG-CSE) are computed once per lane group and reused
  across outputs; literals are hoisted to broadcast vectors in the prologue.
* Caps: chains up to `MAX_CHAIN_DEPTH` (16) deep, up to `MAX_FUSED_NODES`
  (64) distinct ops and `MAX_INPUTS` (64) input columns per kernel; anything
  beyond falls back.

Int32 arithmetic wraps on overflow, matching Spark's `DateAdd`/`DateSub`
non-ANSI semantics. The scalar tail mirrors the vector body row for row and
handles the remainder lanes.

The milestone-1 per-op kernels (`DateVectorOps.vectorAddDays` and friends)
remain in the engine as reference code and as the differential oracle for the
emitter's tests; the per-op dispatcher machinery they were called through is
scheduled for retirement (`PLAN_MILESTONE_3.md` debt register).

### Per-task class loader and Metaspace

Spark's codegen cache keeps compiled classes (and their loaders) alive under
high query diversity, which retains Metaspace. Varka instead loads its
generated classes into a `VarkaClassLoader` scoped to a Spark task
and calls `release()` when the task completes. Once the loader becomes
unreachable, the JVM unloads its classes and frees Metaspace. A registry +
`findClass` mirror Spark's `InMemoryClassLoader`.

### Null semantics and predication

The vector loop cannot branch per row, so SQL's null and conditional
semantics are implemented in mask algebra; `PLAN_MILESTONE_2.md` section 2.6
is the normative statement of the rules. In brief:

* Arithmetic is null-intolerant: an output row is valid only where every
  referenced input is valid, tracked as per-lane-group validity words.
* Comparisons and `AND`/`OR`/`NOT` follow three-valued logic as a
  *known-true / known-false* mask pair (`unknown` is neither), so
  `null AND false = false` comes out right without a branch.
* `IF`/`CASE WHEN` execute *all* arms and pick per lane with
  `VectorMask.blend` - branch-free, so data-dependent conditions cost the
  same as predictable ones (the throughput benchmark prices this).
* `GREATEST`/`LEAST` skip null operands (Spark semantics) rather than
  propagating them.
* `DAYOFWEEK`/`WEEKDAY` lower `Math.floorMod(d, 7)` to a branch-free base-8
  digit-sum fold, several times faster than the lanewise-DIV variant at
  buffer level (the parity benchmark's dayofweek section has both).

### Telemetry and debuggability

Every emitted class is self-describing (task 13): a `SourceFile` attribute
named for the operator and stage (`Varka_Project_Stage3.java`), so stack
traces, profilers and heap dumps name the plan node with no mapping table,
and a `VarkaDebugInfo` custom attribute carrying the vector IR and the plan
fragment it was compiled from. Task 16 extends that to the questions the
attributes alone did not answer:

* **Bytecode maps back to IR nodes.** The class carries a `LineNumberTable`
  whose line `n` is the `n`-th IR node in topological order, and
  `VarkaDebugInfo` records the decoding key (`<line>=<node>` per line). A
  stack frame reading `Varka_Project_Stage3.java:7` therefore names the node
  that threw, not merely the method - and profilers and crash logs inherit
  the same resolution for free.
* **Fallbacks name their kernel.** Every warning on the ghost-fallback path -
  emission failure and per-batch kernel failure, in both exec nodes - carries
  the kernel's `SourceFile` name and the IR it computes, so a log line
  identifies the plan node without correlating timestamps.
* **The class reaches disk.** `spark.sql.codegen.varka.classDumpDirectory`
  writes each emitted class under its `SourceFile` name, so `javap -c -p`
  disassembles a generated loop with no debugger attached. Diagnostics only:
  a failed write is logged and never fails the query.
* **`EXPLAIN` says why an entry did not fuse.** Verbose `EXPLAIN` on either
  Varka node lists every projection entry as fused, forwarded (naming the
  child column) or residual with the compiler's decline reason - "unsupported
  expression", "day offset is not a foldable literal", "CASE WHEN without an
  ELSE branch", "non-date column of type ..." - in the query's own column
  names. The same account goes to the debug log once per task.

All of it is metadata or diagnostics: the emitted methods are byte-identical
with and without the attributes, which the JVM ignores by specification.
`VarkaDebugInfoReader` turns captured class bytes back into those strings.

### Execution integration

`VarkaColumnarRule` (a `ColumnarRule`) rewrites a Varka-eligible projection
(at least one fusable entry) over a columnar source when
`spark.sql.codegen.varka.enabled` is set.
It works in two stages, on either side of Spark's transition insertion, because
which node belongs in the plan depends on what the consumer above wants:

    // preColumnarTransitions: columnar in, columnar out
    ProjectExec(projectList, columnarChild)
      -> VarkaProjectExec(projectList, columnarChild)

    // postColumnarTransitions: a to-row transition that was inserted anyway is fused in
    ColumnarToRowExec(VarkaProjectExec(projectList, child))
      -> VarkaColumnarToRowExec(projectList, child)

A consumer that takes batches - a DSv2 write whose connector declares
`supportsColumnarWrite`, such as `noop` - therefore receives the kernels' own
Arrow batches with no transition at all, while a row consumer gets the single
fused node. The rule is registered on every `SparkSession` but is inert while
the config is off.

Both nodes run the same kernels through `VarkaKernelEvaluator`, and differ only
in what they do with its output batch and in how they fall back:
`VarkaColumnarToRowExec` converts to rows and, when the kernels cannot serve a
batch, projects the input's rows one by one; `VarkaProjectExec` passes the batch
on and has to materialise its fallback into a writable batch instead.

Per task and Arrow-supported batch:

1. Bind the projection and compile it with `VarkaExpressionCompiler` into
   fused entries (the IR), forwarded entries (bare input columns, passed
   through zero-copy) and residual entries (everything else).
2. Emit the fused-kernel class once per task, lazily, into a per-task loader
   (`VarkaGeneratedClassLoader`); the literals travel as runtime arguments,
   so one class serves every batch of the task.
3. Guard per batch that every referenced column is an `ArrowColumnVector`
   backed by a `DateDayVector`; otherwise the batch takes the per-row path.
4. Run the kernel: one vector loop writes every fused output into freshly
   allocated Arrow vectors. Forwarded columns are re-wrapped, not copied.
   Residual entries are evaluated per row and merged - at-row on the
   row-consumer node (the escape hatch task 12 measured both ways), into a
   writable batch on the columnar one.
5. Track `numVarkaBatches`, which only counts batches where the kernels
   succeeded, and release the per-task loader on task completion. The Janino
   fallback projection is compiled lazily, only if a batch actually needs it
   (task 15).

Neither node is `CodegenSupport`; whole-stage codegen splits at the boundary
with the columnar producer. They depend on the engine only by kernel
descriptors (strings), so a missing engine jar degrades to the fallback.

## Key design decisions

* **Java 25 baseline and a self-contained engine.** The Vector API and the
  Class-File API require a recent JDK. `sql/varka/engine` is a module of the
  Spark reactor, so a plain `./build/mvn install` builds it, but it keeps its
  own pom rather than inheriting `spark-parent` so its sources and tests can use
  the incubator-vector and native-access flags the Spark build does not set;
  catalyst uses `java.lang.classfile` on the Java 25 baseline.
* **Arrow-only fast path.** Arrow-backed batches (for example the Arrow cache
  serializer) map directly to segments. Vectorized Parquet produces
  `OnHeapColumnVector`/`OffHeapColumnVector`, not Arrow, so those batches fall
  back per batch.
* **Plan-level interception.** The rewrite happens in a `ColumnarRule` rather
  than by editing `ColumnarToRowExec` itself, and it straddles Spark's
  transition insertion: the projection becomes columnar-out before transitions,
  and a transition inserted above it is fused back in afterwards. That way the
  decision of whether rows are needed at all stays Spark's.
* **Ghost fallback and caching.** Any assembly or load failure lazily routes to
  Janino; the winning path is cached under the same key so a failed assembly is
  never retried and the job never crashes.
* **Extreme-offset oracle.** At `INT` overflow (`Int.MaxValue - 1`,
  `Int.MinValue`) the differential oracle is the plain int32 day wrap that
  `DateAdd.eval` and the kernels implement; Spark's end-to-end row engine adds a
  calendar-day rebase for out-of-range `DATE` results.
* **No unused configuration.** Every `spark.sql.codegen.varka.*` entry must be
  consumed. Today only `spark.sql.codegen.varka.enabled` exists.

## Module and file layout

| Location | Responsibility |
| :--- | :--- |
| `sql/varka/engine` | Standalone Java 25 module (`varka-engine`, Arrow 19.0.0): `VarkaMorsel`, `DateVectorOps`, `VarkaClassLoader` and their tests. |
| `sql/catalyst` | The vector IR, loop emitter and telemetry attribute under `codegen/varka/`; `VarkaExpressionCompiler`; `VarkaGeneratedClassLoader`; the milestone-1 `ClassFileCodegenSupport` + `VarkaClassFileGen` (retirement scheduled); config `spark.sql.codegen.varka.enabled`. |
| `sql/core` | `VarkaColumnarRule`, `VarkaColumnarToRowExec`, end-to-end test suites and benchmarks. |
| `sql/varka` | `VISION.md`, `Varka_MVP.md`, and `plans/` with the milestone plans (`PLAN_MILESTONE_1.md` is the MVP) and per-task plans. |

## Configuration

Both Varka configurations are internal:

| Config | Default | Description |
| :--- | :--- | :--- |
| `spark.sql.codegen.varka.enabled` | `false` | When true, an eligible projection (at least one fusable entry) over Arrow `DateDayVector` columns runs the fused SIMD kernel instead of per-row codegen - as `VarkaProjectExec` where the consumer takes batches, and as `VarkaColumnarToRowExec` where it wants rows; ineligible entries run the row path per row and merge, and non-Arrow batches fall back entirely. |
| `spark.sql.codegen.varka.classDumpDirectory` | (none) | Diagnostics (task 16). When set, every emitted kernel class is written to this directory under its `SourceFile` name, for `javap`. A failed write is logged and never fails the query; tasks of one stage emit identical bytes and overwrite one file. |

The rule is registered on every `SparkSession` but does nothing while the
config is off, so enabling the config is all that is needed:

```scala
val spark = SparkSession.builder()
  .appName("app")
  .config("spark.sql.codegen.varka.enabled", "true")
  // Arrow cache is the recommended production source of DateDayVector batches.
  .config("spark.sql.cache.serializer",
    "org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer")
  .getOrCreate()
```

The Arrow fast path reads cached batches through the in-memory columnar
vectorized reader (`spark.sql.inMemoryColumnarStorage.enableVectorizedReader`,
`true` by default), so caching with the Arrow serializer produces
`ArrowColumnVector` `DateDayVector` batches. Without an Arrow-backed source
Varka silently uses the row engine for every batch and results stay correct.

The VISION draft also describes `spark.sql.codegen.varka.patch.threshold` and
`spark.sql.codegen.varka.fallback.ghost.enabled`. They are design intentions,
not configuration entries in this MVP: per the project rule ("no unused
config"), they will be added to `SQLConf` only when the code paths they gate
exist.

## Testing and benchmarks

* **Engine differential tests** cross-check every kernel against Arrow's own
  vector accessors, including null patterns, empty batches and offsets near
  `Integer.MAX_VALUE`.
* **Catalyst tests** check bytecode shape by disassembly, the loader
  define/release lifecycle, and ghost-fallback injection.
* **sql/core tests** (`VarkaColumnarToRowExecSuite`, `VarkaProjectExecSuite`,
  `VarkaColumnarWriteSuite`, `VarkaEndToEndSuite`,
  `VarkaDifferentialSuite`, `VarkaAutoRegistrationSuite`) prove plan fusion,
  `checkAnswer` equality over a query matrix, `numVarkaBatches > 0` on fused
  plans, Metaspace bounds, and config-driven activation.
* **Metaspace proof** (`VarkaGeneratedClassLoaderSuite`) verifies with weak
  references that a released loader is collected and that a batch of 1000
  loaders is fully collected.

Benchmark highlights from the task 14 committed runs (AMD Ryzen AI 9 HX PRO
370, JDK 25, Linux, machine otherwise idle; every number below is the best of
at least five two-second-windowed iterations and lives in the committed
results files, which are the source of truth as the code moves):

* **End-to-end columnar throughput** over 2M Arrow-cached rows
  (`VarkaThroughputBenchmark`): 1.9-2.4x Janino for single ops
  (`date_add` 1.9x, `datediff` 2.4x), 2.2x for the nested
  `datediff(date_add(d, 1), d2)`, 1.8x for the two-output shared subchain,
  1.6x for a mixed projection where only one entry fuses.
* **`CASE WHEN` by mask blend**: 2.1x on data where the condition flips
  pseudo-randomly, 1.9x where it is perfectly predictable. The varka side
  costs the same on both (branch-free execution is data-oblivious); the gap
  is Janino's branch misprediction, ~12% of its runtime on this shape.
* **Chain depth** (alternating `date_add`/`date_sub`, columnar consumer):
  2.2x at depth 1 falling to 1.4x at depth 8. The relative *shrinks* with
  depth - the opposite of the pre-run prediction - for two diagnosed
  reasons (`PLAN_TASK_14.md` 7.5): Janino's cost is flat in depth (eight
  dependent int adds hide entirely behind its per-row overhead at
  ~21 ns/row), and the varka side pays a *fixed per-task* JIT warm-up that
  grows with the loop method's op count - each task defines a fresh kernel
  class, so HotSpot re-runs the tier ladder every task, while at buffer
  level depth 8 runs within 10% of depth 1. Fusion's end-to-end win at this
  task size is batch-versus-per-row overhead; class reuse across tasks
  (milestone 3's cache item) is the identified fix for the erosion.
* **The row-consumer cost, stated plainly**: through `toRdd` the same chains
  measure 0.7x at depth 1 down to 0.5x at depth 8 - there is no break-even
  depth. The ~16 ns/row read-back of the assembled batch genuinely keeps
  row consumers under 1.0x; the decline with depth is the same per-task
  warm-up cost as above. Fusing row-consumer projections of this shape is
  currently unprofitable at any depth (`PLAN_MILESTONE_3.md` carries the
  profitability question).
* **`dayofweek`**: 0.9x at the committed shape - honestly, a small loss.
  The kernel-level fold is 36x a `LocalDate`-per-row loop in isolation
  (`VarkaEmitterParityBenchmark`); at query level C2 scalar-replaces the
  `LocalDate` allocation (the stock path is already allocation-free where
  it matters), and the fold's larger loop method makes the per-task JIT
  warm-up its dominant cost - a fixed per-task charge, not per-row, so
  longer tasks amortise it (`PLAN_TASK_14.md` 7.5 has the diagnosis).
* **Cold start** (`VarkaColdStartBenchmark`, first execution of a fresh plan
  shape over 100K rows): 1.5x - 18 ms vs 27 ms best, 22 ms vs 36 ms average -
  about 9-14 ms saved per fresh query shape. Visible, but far from the
  isolated generation-time ratio, because the scan and the execution
  framework spend the same time on both sides.
* **Class generation in isolation** (`VarkaCodegenBenchmark`): emitting,
  defining, loading and instantiating a fused two-output kernel takes ~80 us
  against ~6 ms for one Janino projection compile - 75x; the milestone-1
  single-op dispatcher case is ~420x.

## Deployment and requirements

* JDK 25 with the incubator Vector API module:
  `--add-modules jdk.incubator.vector`
  `--enable-native-access=ALL-UNNAMED`
* The engine jar in the repo is a test-scoped dependency; at runtime supply it
  with `--jars` (its absence only falls back to per-row execution).
* Arrow `DateDayVector` buffers come from Arrow-backed producers; the Arrow
  cache serializer is the recommended source.

## Limitations

The real current edges, stated with their numbers where they have one:

* **Int32 lanes only.** The IR carries one lane type; every supported
  expression is `INT`-shaped (`DateType` days or integer results). No
  `CalendarInterval`, strings, decimals, timestamps or nested types, and only
  foldable integer day offsets.
* **ANSI arithmetic over `datediff` outputs is excluded by design**: an
  integer `Add` over a `datediff` result is not a date expression, and ANSI
  overflow cannot throw row-accurately from a SIMD lane, so such entries stay
  residual.
* **The row-consumer read-back costs more than fusion saves**: 0.7x at chain
  depth 1, falling to 0.5x at depth 8 (committed in
  `VarkaThroughputBenchmark`). Varka currently pays off on columnar
  consumers; whether the rule should decline row-consumer fusions is
  milestone 3's profitability item.
* **Vectorized Parquet falls back**: `OnHeap`/`OffHeapColumnVector` batches
  are not Arrow. The Arrow cache serializer is the production source of
  eligible batches.
* **No whole-stage codegen integration.** The Varka nodes are not
  `CodegenSupport`; whole-stage codegen splits at the boundary.
* **Emitter caps**: chain depth 16, 64 distinct ops, 64 input columns per
  kernel; beyond them the projection (or entry) falls back.

## Building, testing and running benchmarks

```bash
./build/mvn -f sql/varka/engine/pom.xml install
build/sbt "sql/testOnly org.apache.spark.sql.execution.VarkaDifferentialSuite"
# Engine JMH kernels (in-process, gated):
./build/mvn -f sql/varka/engine/pom.xml test -Dvarka.jmh=true
# Spark benchmarks (SPARK_GENERATE_BENCHMARK_FILES=1 to regenerate the committed files):
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaThroughputBenchmark"
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaColdStartBenchmark"
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaCodegenBenchmark"
build/sbt "catalyst/test:runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
```