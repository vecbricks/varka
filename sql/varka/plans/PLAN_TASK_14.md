# Task 14: benchmarks and docs

**Status: PLANNED.** The milestone's closing task (`PLAN_MILESTONE_2.md`,
task row 14): measure what milestones 1 and 2 actually bought, end to end and
honestly, then rewrite the public story - `docs/sql-varka.md`, `VISION.md`,
and (added to the task by the milestone owner) the repository's `README.md`,
which today is still stock Apache Spark and says nothing about Varka at all.
Numbers first, prose second: every figure the docs quote comes from the runs
this task commits.

## 1. Why this task, and what it stands on

Milestone 2 built the fused vector loop (tasks 9-11), opened it to real
projections (task 12) and made the generated classes self-describing
(task 13). What it has *not* done is demonstrate the milestone's thesis at
the query level: the committed throughput results still cover only single-op
projections plus the task-12 mixed cases, while the fusion, DAG-CSE and
predication wins exist only as buffer-level parity numbers in
`VarkaEmitterParityBenchmark`. The milestone's validation row is explicit:
the committed results must show the chain speedup and its scaling with
depth, the `CASE WHEN` case is the headline fusion number, and the cold-query
case must turn the class-generation figure into a query-level number.

Two debts land here by prior appointment:

* **Task 12's outcome** (`PLAN_TASK_12.md` 6.2) handed this task the
  row-consumer question: an all-fused `date_add` through `toRdd` measures
  ~0.8x Janino because of the row node's generic per-row read-back, so the
  matrix must measure how chain depth amortises that fixed cost - and find
  the break-even depth, if it is in reach.
* **The benchmark-variance debt** (`PLAN_MILESTONE_3.md` 13): committed
  results are single runs, and the same case has swung 1.5-3.3 G rows/s
  across one day. The benchmark task is where that gets fixed, before any
  new number is committed on top of the old methodology.

## 2. Deliverables

### 2.1 Methodology first: variance under control

Before any new case is added, the committed-run methodology changes, closing
the debt-register item:

* Throughput cases move from `minNumIters = 2, warmupTime = 1s, minTime = 1s`
  to `minNumIters = 5, warmupTime = 2s, minTime = 2s`. Cheap - the whole
  suite still runs in minutes - and it is the register's own "longer
  minTime / more iterations" fix.
* Any *claim* that hinges on a ratio under ~1.3x is verified with an
  interleaved A/B re-run compared by minimums before it is written into a
  doc - the `SKILLS.md` build-benchmark method, which found a +/-15% noise
  band on this machine. The outcome section records which claims needed it.
* Results are generated with the machine otherwise idle (the measured
  largest single effect), and the outcome section states the hardware and
  conditions once, instead of each doc repeating them.

### 2.2 The throughput matrix: fusion end to end

`VarkaThroughputBenchmark` gains the milestone-mandated cases, all over the
existing 2M-row Arrow-cached tables (plus one new table, below), columnar
consumer unless stated:

* **Nested projection**: `datediff(date_add(d, 1), d2)` - the exact query
  the milestone's "why" section opens with, which milestone 1 could not fuse
  at all.
* **Shared subchain (DAG-CSE)**:
  `date_add(d, 1) AS a, datediff(date_add(d, 1), d2) AS b` - the interned
  subtree computed once per lane group, across outputs, which neither the
  per-op kernels nor Janino's per-row CSE can keep in a vector register.
* **`CASE WHEN`, the headline** - with one design point the milestone row
  does not spell out but honesty requires. The existing `varka_date_pairs`
  table has `d2 - d` constant, so any comparison over it is perfectly
  predictable and Janino's per-row branch costs nothing: measuring the blend
  there would understate its point. A new cached table therefore carries
  pseudo-random day offsets (`pmod(hash(id), ...)`) so the condition flips
  irregularly, and the case runs on **both** tables:
  `CASE WHEN d < d2 THEN date_add(d, 7) ELSE date_sub(d2, 7) END`. The
  predictable-data run shows the pure fusion win; the unpredictable-data run
  adds the branch-free win. The gap between them *is* the misprediction
  cost, priced rather than asserted.
* **`dayofweek`**: the kernel-level 36x over the `LocalDate` path
  (`PLAN_TASK_11.md`), measured through a query for the first time - the
  one case where Varka does not just fuse but replaces an allocating path.

The mixed-projection and row-consumer cases from task 12 stay as they are.

### 2.3 Chain-depth scaling, on both consumers

A depth-parameterised chain - alternating `date_add`/`date_sub` with
distinct literals, so neither Catalyst nor C2 can reassociate it - at depths
1, 2, 4 and 8:

* **Columnar consumer**: the fused loop pays one load and one store however
  deep the chain, while Janino pays per-row call overhead per op. The
  relative should *grow* with depth; the committed curve is the milestone
  validation row's "scaling with depth".
* **Row consumer**: the same chain through `toRdd`, against the task-12
  finding. The read-back is a fixed per-row cost, the kernel win grows with
  depth, so somewhere the curve should cross 1.0x. Finding that break-even
  depth (or showing it lies beyond depth 8) is the concrete number
  milestone 3's fuse-profitability question needs; it goes into the outcome
  section and into `PLAN_MILESTONE_3.md`'s item.

Buffer-level depth scaling already exists in the parity benchmark
(fused vs sequential kernel passes); these cases are its query-level
counterpart against Janino, which is the comparison a user actually gets.

### 2.4 Cold-query latency: the generation-time figure at query level

The MVP measured class *generation* at ~2-3 orders of magnitude under a
Janino compile (`VarkaCodegenBenchmark`), but no committed number says what
that is worth for a whole query. A new `VarkaColdStartBenchmark` (sql/core,
own results file) measures **first execution of a fresh plan shape**:

* A cached wide table (`c0 ... cN` date columns, ~100K rows - small enough
  that compilation, not compute, dominates the first run). Each iteration
  projects a chain over a *different* column, so every iteration is a fresh
  code shape to Janino's compile cache and a fresh emission for Varka; the
  scan's own codegen shape repeats and is cached on both sides after the
  first query, so the delta isolates projection compile vs kernel emission.
* Timer-based cases with no warmup (warmup would eat the fresh shapes):
  N pre-built queries per side, each executed once, best and average
  reported. The varka side asserts `numVarkaBatches > 0` once outside the
  timing so the number cannot silently measure the fallback.
* `VarkaCodegenBenchmark` gains one case alongside its per-op dispatcher
  generation: emit + define + load + instantiate of a representative fused
  kernel, so the committed gen-time figure describes the milestone-2
  machinery and not only the milestone-1 dispatchers it replaced.

### 2.5 The "JMH fused-chain case", resolved as a deviation

The milestone row asks for a JMH fused-chain case, and this plan records why
it will not be delivered literally: JMH lives in the engine module
(`-Dvarka.jmh=true`), the emitter lives in catalyst, and the engine must not
depend on catalyst - so JMH cannot execute an emitted kernel without
inverting the module boundary the MVP set on purpose. The fused-chain
microbenchmark exists instead as the parity benchmark's chain-depth case
(committed since task 9, `Benchmark`-based, same buffers as the kernels),
and 2.3 adds the query-level counterpart. The outcome section records this
as the task's planned deviation; if a JMH-grade absolute is ever needed, the
route is a hand-fused reference chain in `DateVectorOps` - reference code,
milestone 3 material, not a docs-task side effect.

### 2.6 Public docs: `docs/sql-varka.md` and `VISION.md`

`docs/sql-varka.md` is milestone-1 vintage and now wrong in load-bearing
places: it says only three date expressions are supported, calls the
class-file routing "a stub", and quotes 1.1-1.2x end to end. It gets a
milestone-2 revision, not a rewrite - the architecture sections stand:

* **Architecture**: the emitted fused loop replaces the per-op dispatcher
  story (IR -> `VarkaLoopEmitter` -> `VarkaFusedKernel`; dense/masked twin
  bodies; `GROUP_BUDGET` sibling methods and the measured JIT cliff behind
  them); partial eligibility with zero-copy forwarding and merge-at-row;
  the telemetry attributes and `VarkaDebugInfoReader`.
* **Semantics**: a short predication section - three-valued conditions,
  blend, null-skipping `greatest`/`least`, `floorMod` - pointing at
  `PLAN_MILESTONE_2.md` 2.6 as the normative rules.
* **Benchmarks and limitations**: every number replaced from this task's
  runs; the limitations section rewritten to the real current edges (int32
  lanes only; ANSI `Add` on `datediff` outputs excluded by design; the
  row-consumer read-back cost, stated plainly with its number; vectorized
  Parquet still falls back; no whole-stage codegen integration).

`VISION.md` gets a status pass, not a rework: it is the architectural
source of truth and its principles are unchanged, but its MVP-scope and
next-steps sections predate milestone 2; they now point at the milestone
plans as the live roadmap, with milestone 3 as the next step.

### 2.7 `README.md`: the fork's front page

Requested by the milestone owner for this task: the root `README.md` is
verbatim Apache Spark's - badge wall pointing at `apache/spark` CI included -
and a visitor to `vecbricks/varka` cannot tell what the fork *is*. It is
rewritten as Varka's front page:

* **What this is**: a research fork of Apache Spark exploring SIMD
  vectorized execution for SQL - generated vector loops (Class-File API +
  Vector API) over Arrow-backed columnar data, behind a config flag,
  falling back to stock Spark per batch on anything it cannot serve.
* **The main ideas**, each one line with a pointer into
  `docs/sql-varka.md` / `VISION.md`: generate the loop, not a call to it
  (per-task classes make shared call sites megamorphic); zero-copy Arrow
  morsels; whole-projection fusion with cross-output DAG-CSE in vector
  registers; predication by mask blend with SQL's three-valued logic;
  partial eligibility with zero-copy column forwarding; the ghost fallback
  (a Varka failure never fails a query); per-task class loading with proven
  Metaspace unload; telemetry baked into the generated class bytes.
* **Benchmarks**: one table of this task's headline numbers - kernel-level,
  fusion/`CASE WHEN`, end-to-end columnar, cold start - *including* the
  honest rows (row-consumer read-back), with hardware/JDK stated and a
  pointer to the committed results files and the regeneration commands.
* **Quick start**: build, the two config lines
  (`spark.sql.codegen.varka.enabled`, the Arrow cache serializer), one
  spark-shell example that shows a fused plan and its metrics.
* **Status and roadmap**: milestones 1-2 done with one-line summaries,
  milestone 3 next (`sql/varka/plans/`); docs map (`docs/sql-varka.md`,
  `sql/varka/VISION.md`, `SKILLS.md`); a short "About Apache Spark"
  section linking upstream and the Apache 2.0 license, replacing the stock
  build matrix rather than keeping it.

What the README does *not* get: promises. Deferred work is named as
deferred, numbers carry their consumer shape, and the row-consumer cost is
in the table, not a footnote.

### 2.8 Milestone bookkeeping

`PLAN_MILESTONE_2.md`: task 14 row marked DONE with the headline numbers;
a closing note on the milestone (all tasks 9-15 done, gates passed, the
open question already settled). `PLAN_MILESTONE_3.md`: the variance debt
item marked swept; the fuse-profitability item gains the measured
break-even depth from 2.3.

## 3. Predictions, written before the runs

The project keeps score on these (1 for 4 so far, `PLAN_TASK_12.md` 6.2):

1. The columnar chain-depth relative grows monotonically from ~1.8x at
   depth 1 to >=2.5x by depth 8.
2. The row-consumer chain crosses 1.0x at depth 2 or 3, and clears 1.2x by
   depth 8.
3. `CASE WHEN` on unpredictable data beats the same query on predictable
   data by a visible margin (>=15% relative-to-Janino), and lands >=1.8x as
   the headline.
4. Cold start: the Varka side's first execution beats Janino's by
   milliseconds per fresh shape - visible but not the 636x of the
   microbenchmark, because the scan and the framework dominate.

## 4. Verification

```
# The full sweep stays green (no production code changes expected
# outside the benchmarks):
build/sbt "catalyst/testOnly *Varka*" "sql/testOnly *Varka*"

# Regenerate the three sql/core results files and the parity file:
SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
  "sql/Test/runMain org.apache.spark.sql.execution.benchmark.VarkaThroughputBenchmark" \
  "sql/Test/runMain org.apache.spark.sql.execution.benchmark.VarkaColdStartBenchmark" \
  "sql/Test/runMain org.apache.spark.sql.execution.benchmark.VarkaCodegenBenchmark" \
  "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"

build/sbt "sql/scalastyle" "sql/Test/scalastyle" && dev/lint-java
```

Acceptance, from the milestone's validation row plus this plan:

* Committed results show the chain speedup and its scaling with depth, on
  both consumers; the row-consumer break-even depth is a number (or an
  explicit "beyond depth 8").
* The `CASE WHEN` pair is committed with both data patterns and the
  headline number is the unpredictable one.
* The cold-start file exists and the varka side demonstrably ran the
  kernels.
* Docs and README quote only numbers present in the committed files; the
  grep hygiene (100-char, ASCII) passes on every touched file, markdown
  included.

## 5. Explicitly out of task 14

* **Retiring the milestone-1 dispatchers** and deciding the
  `ClassFileCodegenSupport` trait's fate - its own small PR once the
  milestone closes, per the debt register.
* **`GROUP_BUDGET` retuning** (the ~24 candidate): needs the multi-output
  compile-latency measurement the register describes; nothing in this task
  depends on it.
* **Acting on the fuse-profitability threshold** - this task *measures* the
  break-even depth; deciding whether the rule should decline shallow
  row-consumer fusions is milestone 3's item, now with its number.
* **AQE-aware test helpers** (debt register): test infrastructure, not
  benchmarks or docs.
* **A hand-fused JMH reference chain in the engine** (2.5's deferral).

## 6. Risks, named

* **Benchmark-driven scope creep.** Every case in 2.2-2.4 maps to a line in
  the milestone row or a recorded handoff; anything else found interesting
  along the way goes to the outcome section as a note, not a new case.
* **The cold-start harness lies easily.** Compile caches (Janino's, and the
  JVM's own profile pollution across iterations) can make either side look
  cached; the fresh-shape-per-iteration design and the no-warmup rule are
  the mitigations, and the outcome section must state what was verified
  (cache misses on the Janino side, emission on the Varka side).
* **README staleness by design.** A front page quoting concrete numbers
  will drift as the code moves. Mitigation: the README states the commit
  and date of its numbers and points at the results files as the living
  source, so drift is visible rather than silent.
