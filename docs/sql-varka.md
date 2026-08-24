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
runs a small, hot set of date arithmetic expressions directly over Arrow
`DateDayVector` buffers with the JDK 25 Vector API (SIMD) and Panama
`MemorySegment`, bypassing Spark's per-row code generation on the happy path.

## Overview

The Spark SQL runtime normally executes expressions by generating Java source,
compiling it with Janino, and running one expression evaluation per row.
Varka eliminates that runtime compilation overhead (string generation and
Janino parsing) and unlocks SIMD by operating on whole columnar batches at once.

The MVP covers three expressions over the `DateType` column (stored as `INT`
days since epoch):

* `DATE_ADD(start_date, days)` - SIMD `INT` addition.
* `DATE_SUB(start_date, days)` - SIMD `INT` subtraction.
* `DATEDIFF(end_date, start_date)` - SIMD `INT` subtraction of two columns.

Explicitly out of MVP scope are `CalendarInterval` (months/years), strings,
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

### SIMD kernels

`DateVectorOps` exposes the kernels over raw addresses (`long`), so generated
bytecode calls them with a primitive stack:

* `vectorAddDays(src, validity, nullCount, dst, dstValidity, len, days)`
* `vectorSubDays(src, validity, nullCount, dst, dstValidity, len, days)`
* `vectorDateDiff(a, validityA, nullCountA, b, validityB, nullCountB,
  dst, dstValidity, len)`

Each kernel runs an `IntVector` loop (masked load/store so null lanes never read
garbage) followed by a strict scalar tail. Int32 arithmetic wraps on overflow,
matching Spark's `DateAdd`/`DateSub` semantics. A source validity pointer may be
`0L` when a column is null-free or fully null; the destination validity pointer
is always required and the kernel writes the output null bits.

### Per-task class loader and Metaspace

Spark's codegen cache keeps compiled classes (and their loaders) alive under
high query diversity, which retains Metaspace. Varka instead loads each batch
of generated dispatch classes into a `VarkaClassLoader` scoped to a Spark task
and calls `release()` when the task completes. Once the loader becomes
unreachable, the JVM unloads its classes and frees Metaspace. A registry +
`findClass` mirror Spark's `InMemoryClassLoader`.

### Catalyst hooks and bytecode assembly

Eligible expressions (`DateAdd`, `DateSub`, `DateDiff`) implement the
`ClassFileCodegenSupport` trait, which registers the expression and exposes a
`ClassFileGenOp` - the owner, method name and JVM descriptor of the `invokestatic`
call that the batch kernel embodies.

The `java.lang.classfile` API (JEP 484, JDK 25) assembles dispatch classes
that forward to the kernels; no external bytecode library is used:
`ClassFileAssembler`/`JavaClassFileEngine` assemble the full class, and
`VarkaClassFileGen.assembleKernelClass` builds the small per-op dispatchers.

Each dispatcher implements the kernel-shape interface matching its descriptor -
`VarkaUnaryKernel` for a one-input kernel with a scalar argument,
`VarkaBinaryKernel` for a two-input one - so the execution path reaches the
kernel with an ordinary interface call and the arguments stay primitive from the
caller's stack into the kernel. A new kernel shape means a new interface.

Note on status: `JavaClassFileEngine` is not wired into the `CodeGenerator.compile`
funnel. Its assembled `VarkaProjection.apply` is still a stub that throws, and
assembly, loading and construction all succeed, so routing to it would hand back a
class that fails only at row-evaluation time, past any fallback. Wiring the funnel
belongs with the change that gives `apply` a real body. The live execution path is
the columnar-to-row node described next.

### Execution integration

`VarkaColumnarRule` (a `ColumnarRule`) rewrites a fully Varka-eligible
projection over a columnar source when `spark.sql.codegen.varka.enabled` is set.
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

Per Arrow-supported batch:

1. Bind the projection and match each expression to a kernel op.
2. Guard that every referenced column is an `ArrowColumnVector` backed by a
   `DateDayVector`; otherwise the batch takes the per-row Janino path.
3. Assemble/define one kernel-dispatcher class per distinct op in a per-task
   loader (`VarkaGeneratedClassLoader`) and invoke it with the input addresses
   and the days offset as runtime arguments.
4. Write each projected column into a freshly allocated Arrow vector
   (zero-copy) and wrap the result batch - which `VarkaColumnarToRowExec` then
   converts to rows with the standard copy projection, and `VarkaProjectExec`
   hands to its consumer, closing it when the next batch is asked for.
5. Track `numVarkaBatches`, which only counts batches where the kernels
   succeeded, and release the per-task loader on task completion.

Neither node is `CodegenSupport`; whole-stage codegen splits at the boundary
with the columnar producer. They depend on the engine only by kernel
descriptors (strings), so a missing engine jar degrades to the fallback.

## Key design decisions

* **Java 25 baseline and a standalone engine.** The Vector API and the
  Class-File API require a recent JDK. The engine is a standalone Maven module
  outside the Spark reactor so its tests can use the native-access and
  incubator-vector flags; catalyst uses `java.lang.classfile` on the Java 25
  baseline.
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
| `sql/catalyst` | `ClassFileCodegenSupport` + `VarkaClassFileGen`, `ClassFileAssembler`/`JavaClassFileEngine`, `VarkaGeneratedClassLoader`, config `spark.sql.codegen.varka.enabled`. |
| `sql/core` | `VarkaColumnarRule`, `VarkaColumnarToRowExec`, end-to-end test suites and benchmarks. |
| `sql/varka` | `VISION.md`, `Varka_MVP.md`, and `plans/` with the milestone plans (`PLAN_MILESTONE_1.md` is the MVP) and per-task plans. |

## Configuration

There is only one Varka configuration and it is internal:

| Config | Default | Description |
| :--- | :--- | :--- |
| `spark.sql.codegen.varka.enabled` | `false` | When true, a fully eligible projection over Arrow `DateDayVector` columns runs the SIMD kernels instead of per-row codegen - as `VarkaProjectExec` where the consumer takes batches, and as `VarkaColumnarToRowExec` where it wants rows; non-Arrow batches fall back to the standard per-row path. |

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

Benchmark highlights on a Ryzen AI 9 HX PRO 370 (JDK 25, indicative):

* The kernels measure ~3x the scalar-loop baseline at 1M rows in the JMH
  microbenchmark.
* Class generation is ~2-3 orders of magnitude cheaper than a Janino compile
  (`VarkaCodegenBenchmark`).
* End-to-end rows/sec over 2M Arrow-cached rows improves ~1.1-1.2x for
  `date_add`/`date_sub`; the row-based consumer is dominated by the Arrow-to-row
  conversion and the scan, not the arithmetic, so the SIMD kernel speedup does
  not translate proportionally to that shape.

## Deployment and requirements

* JDK 25 with the incubator Vector API module:
  `--add-modules jdk.incubator.vector`
  `--enable-native-access=ALL-UNNAMED`
* The engine jar in the repo is a test-scoped dependency; at runtime supply it
  with `--jars` (its absence only falls back to per-row execution).
* Arrow `DateDayVector` buffers come from Arrow-backed producers; the Arrow
  cache serializer is the recommended source.

## Limitations

* Only the three date expressions; only integer day offsets; no
  `CalendarInterval`, strings, decimals or nested types.
* Vectorized Parquet (`OnHeap/OffHeapColumnVector`) is not Arrow and falls back.
* The Varka nodes are not `CodegenSupport`, so whole-stage codegen
  splits at the boundary, and the codegen-funnel class-file routing is still a
  stub.
* The end-to-end speedup is bounded by row-conversion overhead on row-based
  consumers; the largest wins are class-generation time and columnar pipelines.

## Building, testing and running benchmarks

```bash
./build/mvn -f sql/varka/engine/pom.xml install
build/sbt "sql/testOnly org.apache.spark.sql.execution.VarkaDifferentialSuite"
# Engine JMH kernels (in-process, gated):
./build/mvn -f sql/varka/engine/pom.xml test -Dvarka.jmh=true
# Spark benchmarks:
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaThroughputBenchmark"
build/sbt "sql/test:runMain org.apache.spark.sql.execution.benchmark.VarkaCodegenBenchmark"
```