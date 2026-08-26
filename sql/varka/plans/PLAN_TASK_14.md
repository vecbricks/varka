# Task 14: benchmarks and docs

**Status: DONE.** Outcome in section 7; the predictions of section 3 scored
1.5 of 4 (section 7.3). The milestone's closing task (`PLAN_MILESTONE_2.md`,
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

## 7. Outcome

### 7.1 What ran, and under what conditions

All four results files were regenerated on the committed methodology
(2.1: `minNumIters = 5`, two-second warmup and measurement windows,
machine otherwise idle): `VarkaThroughputBenchmark` (twice, back to back -
the second run doubles as the A/B check below), the new
`VarkaColdStartBenchmark`, `VarkaCodegenBenchmark` with its new fused case,
and `VarkaEmitterParityBenchmark` unchanged. Hardware and conditions, stated
once for every number this task quotes: AMD Ryzen AI 9 HX PRO 370 (Zen 5,
AVX-512), OpenJDK 25.0.4, Linux 7.0, `local[1]`, 2M-row Arrow-cached tables
(100K for cold start).

Two harness hardenings landed beyond the plan. Every throughput case now
runs an untimed guard first that executes the query and requires
`numVarkaBatches > 0` off the plan's Varka node - plan-shape checking alone
cannot see a runtime ghost fallback, and the `dayofweek` result below is
exactly the case that made the stronger guard worth having (it passed: the
kernels really ran). And the cold-start timer acts on the pre-planned
`queryExecution.toRdd` rather than a `noop` write, because the write API
re-plans a fresh command inside the timer; planning stays in setup, so the
timed region is Janino compile / kernel emission plus the small compute.

### 7.2 The numbers

Columnar consumer, best-of->=5, relative to Janino: `date_add` 1.9x,
`date_sub` 2.1x, `datediff` 2.4x, nested `datediff(date_add(d, 1), d2)`
2.2x, shared subchain (DAG-CSE) 1.8x, mixed projection 1.6x, `CASE WHEN`
1.9x on predictable data and 2.1x on pseudo-random data, `dayofweek` 0.9x.
Chain depth 1/2/4/8: 2.2x/2.0x/1.7x/1.4x columnar, 0.7x/0.6x/0.6x/0.5x
through `toRdd`. Cold start: 18 ms vs 27 ms best (22 vs 36 average) per
fresh plan shape - 1.5x. Fused emit+define+load+instantiate: ~80 us, 75x
under one Janino projection compile (the milestone-1 dispatcher case: 418x).

The claims-under-1.3x rule (2.1) applied twice. The `CASE WHEN` gap: varka
is 27 ms on both data patterns in both runs (blend is data-oblivious), while
Janino pays +6 ms (~12%) on the unpredictable table - direction and size
replicated across the two generations, so the gap is committed as Janino's
misprediction cost, though at ~12% it fell short of the predicted >=15%.
`dayofweek` 0.9x also replicated exactly (72 ms vs 64 ms both runs).

### 7.3 Predictions scored: 1.5 of 4

1. **Wrong, instructively.** The columnar depth relative does not grow - it
   falls, 2.2x at depth 1 to 1.4x at depth 8. Janino's cost is *flat* in
   depth (~21 ns/row whatever the chain: eight dependent int adds vanish
   inside per-row overhead on a 5 GHz core) - the premise "Janino pays
   per-row call overhead per op" was milestone-1 thinking; inside one
   compiled row loop, an op costs well under a nanosecond. The varka side's
   growth with depth was first attributed to masked ops accumulating per op;
   the post-commit diagnosis (7.5) corrected that: it is a *fixed per-task*
   JIT warm-up cost that grows with op count, not steady-state loop cost.
   What fusion buys end to end at the committed task size is
   batch-versus-per-row overhead - about 2x on this hardware - and the
   per-task compile erodes it with depth.
2. **Wrong.** No row-consumer break-even exists: 0.7x at depth 1 *falling*
   to 0.5x at depth 8, for the same reason as (1) - the varka side's cost
   grows with depth, Janino's does not, so the curves diverge. Recorded in
   `PLAN_MILESTONE_3.md`'s register as a measured answer: the profitability
   question is not "how deep" but "should the rule decline row consumers".
3. **Half right.** The headline held (2.1x >= 1.8x on unpredictable data)
   but the data-pattern gap is ~12% relative, under the predicted 15%.
4. **Right.** Milliseconds per fresh shape (9 ms best, 14 ms average),
   visible and committed, nowhere near the isolated 636x - the scan and
   framework dominate both sides, exactly as reasoned.

Running score across tasks 12 and 14: 2.5 of 8.

### 7.4 Deviations and notes

* The JMH fused-chain case was resolved as planned deviation 2.5 (module
  boundary); the parity benchmark's chain-depth case remains the buffer-level
  fused-chain number and was regenerated with everything else.
* `dayofweek` was expected (2.2) to be "the one case where Varka replaces an
  allocating path"; at query level it is a small honest loss (0.9x). Half
  the story: C2 scalar-replaces the `LocalDate` allocation inside the
  compiled row loop, so the stock path is already allocation-free where it
  matters. The other half was diagnosed after commit (7.5): the fold's
  deficit is the per-task JIT warm-up of its larger loop method, a fixed
  ~50 ms per task, not per-element compute. Docs and README carry the loss
  at the committed shape, per the no-promises rule, with the mechanism
  named.
* The depth cases run over `varka_dates` (mixed nulls), so they price the
  masked body - the general case, not the dense fast path.
* `date_add` at 1.9x vs "chain depth 1" at 2.2x is the same query modulo
  literal and table position in the run; the spread is the honest remaining
  noise band at these sub-50 ms case times, which is why the docs quote
  each number from its own committed case rather than averaging cousins.

### 7.5 Post-commit diagnosis: the op-count cost is per-task JIT warm-up

Asked why `dayofweek` lost to a path it beats 36x at buffer level, this task's
review dug further, and the answer revises 7.3's causal story (the *numbers*
stand; the mechanism behind two of them was misattributed). Two experiments:

* **Scaling discriminator.** The same three queries over 2M and 8M-row
  copies of `varka_dates`, per-iteration minimums. The extra cost of the
  op-heavier kernels over `date_add` is *flat* across the 4x growth: chain
  depth 8 costs +13.4 ms at 2M and +13.3 ms at 8M; `dayofweek` +53.1 ms and
  +50.6 ms. A per-row cost would have quadrupled; a fixed per-task cost
  stays put - and it stayed put to within 5%.
* **`-XX:+PrintCompilation`.** One tier-4 OSR compile of
  `VarkaFusedProjection::loopMasked0` *per task* - 48 of them for 48 tasks -
  each preceded by the interpreter and tier-3 (boxed vectors) phases and
  followed by "made not entrant: OSR invalidation" as the task's class dies.
  Method sizes 254/396/426 bytes for `date_add`/chain-8/`dayofweek`.

The mechanism: the evaluator defines a fresh kernel class per task (the
per-task loader), so HotSpot re-runs the full tier ladder every query
execution; until the C2 OSR lands, the loop runs interpreted or C1 with
boxed vectors. The buffer-level parity benchmark compiles once and measures
steady state, which is why the two disagree. Consequences:

* `dayofweek` 0.9x is a short-task artifact: the ~50 ms fixed cost happens
  to eat a 2M-row task; at 8M rows the same query is ~1.8x `date_add`'s
  time while Janino's scales per-row - roughly 2x in Varka's favor.
* The columnar depth-curve erosion (7.3 point 1) is mostly this fixed cost,
  not steady-state op cost - at buffer level depth 8 runs within 10% of
  depth 1 (14.3 vs 15.9 G rows/s, parity file). Prediction 1's premise
  about Janino was still wrong; the varka-side attribution is corrected.
* The row-consumer conclusion (7.3 point 2) survives with its mechanism
  split: the ~16 ns/row read-back genuinely keeps row consumers under 1.0x,
  but the *decline* with depth is the per-task compile; with class reuse
  the curve flattens near 0.7x. No crossing either way.
* The fix is specific, and it corrects `PLAN_MILESTONE_3.md` item 2's
  design: caching assembled *bytes* does not help - a re-defined class is a
  new class to HotSpot and re-pays the ladder. Only reusing the *loaded
  class* across tasks preserves the C2 code, which tenses against the
  per-task-unload Metaspace principle: the cache must be a bounded
  class/loader cache, not a byte cache. The item carries the correction.

The lesson joined `SKILLS.md`'s C2-latency section; the diagnosis driver was
scratch code and is not committed. The committed benchmark files are
unchanged by this - 2M-row tasks are the committed shape, honestly labeled.

### 7.6 Docs delivered

`docs/sql-varka.md` revised to milestone 2 (fused-loop architecture,
semantics section, telemetry, all numbers from 7.2, limitations rewritten -
including the row-consumer cost stated with its number); `VISION.md` given
its status pass (top banner, sections 7 and 12 annotated, roadmap pointed at
the milestone plans); `README.md` rewritten as the fork's front page (what
it is, the main ideas as one-liners, the benchmark table honest rows
included, quick start, status/roadmap/docs map, upstream attribution
replacing the stock badge wall). `PLAN_MILESTONE_2.md` task row and closing
note, and the two `PLAN_MILESTONE_3.md` register entries, updated as 2.8
required.
