# Task 17: the debt register, swept

**Status: DONE** - the outcome is section 5. Milestone 2's closing sweep, added
after task 16 by the milestone owner: the debt register written during the task-11 audit moves from
`PLAN_MILESTONE_3.md` (where its remainder was parked) into milestone 2, and
its open items are done here rather than deferred again.

## 1. Why now

Three of the register's six items were swept by the tasks that owned them -
benchmark variance in task 14, emitter slot planning in task 13, and the
row-consumer question answered (as a measurement, not a fix) in task 14. What
was left was parked in milestone 3 for no better reason than that milestone 2's
task list was full. Two of those items are small, and one of them - the
milestone-1 dispatcher layer - is dead code that every Spark query still pays a
little for, Varka on or off. Milestone 2 built the thing that replaced it; the
milestone that replaced it should be the one that removes it.

The register itself moves with the work: milestone 2 is where these debts were
incurred and where their outcomes belong. `PLAN_MILESTONE_3.md` keeps item 13
of its scope catalogue as a pointer so the cross-references in `PLAN_TASK_13.md`
and `PLAN_TASK_14.md` still resolve, and so item 14 keeps its number.

## 2. Deliverables

### 2.1 Retire the milestone-1 dispatcher layer

The register's first item, and the reason it waited for the milestone to close.
Today's production chain is: `DateAdd`/`DateSub`/`DateDiff` mix in
`ClassFileCodegenSupport`, whose `genCode` registers each occurrence into a
`CodegenContext` buffer; `GenerateUnsafeProjection` turns that buffer into
`ClassFileGenOp`s and hangs them on the `CodeAndComment` that keys Janino's
compile cache; `CodeFormatter` carries them through. Nothing reads them. The
router that did was deleted in task 9's follow-up, and the fused loop of tasks
10-16 reaches the kernels through `VarkaExpressionCompiler` instead, which
consults none of this.

So the layer goes, in one piece:

* `ClassFileCodegenSupport` (the trait, `ClassFileGenOp` and `VarkaClassFileGen`
  with `assembleKernelClass`/`eligibleOps`/`kernelInterface`), and the two
  kernel-shape interfaces `VarkaUnaryKernel` / `VarkaBinaryKernel`.
* The `CodegenContext` registry (`classFileGenExpressions`,
  `registerClassFileGenExpression`, `isClassFileGenEligible`) and the
  `genCode` override that fed it - which is the only part with a per-query
  cost outside Varka: every date expression in every query registers itself.
* `CodeAndComment.classFileGenOps`, restoring upstream Spark's cache key
  (`body` alone). The class's own doc already argues this is behaviour-neutral:
  a body is generated from the same expressions that yield the ops, so equal
  bodies already carry equal ops - which is exactly why dropping the ops cannot
  merge two units that differ.
* The trait mixins and their `classFileGenOp` / `isClassFileGenEligible`
  overrides on the three date expressions.

What stays: `DateVectorOps` and the engine's kernels (reference code and the
differential oracle, per task 14's outcome), `VarkaGeneratedClassLoader` (the
live emitter loads through it), and `DateVarkaSupport.foldDaysOffset`, which
the IR compiler folds day offsets with.

Tests and benchmarks follow the code: `ClassFileCodegenSupportSuite`, the
`ClassFileGenOpVerifier` disassembly probe and the engine's
`DateVectorOpsEmissionTest` cross-check go with the layer they test;
`VarkaGeneratedClassLoaderSuite` switches to `VarkaLoopEmitter.emit` for its
realistic class bytes (the assembler the execution path actually uses now);
`VarkaCodegenBenchmark` keeps its fused emit case and drops the dispatcher one,
so the committed results file describes only machinery that exists.

### 2.2 Make the shared test helpers AQE-aware

`assertFused`, `assertNotFused` and `assertKernelsRan` in `VarkaSharedSessions`
use plain `SparkPlan.find`/`collectFirst`, which do not descend into
`AdaptiveSparkPlanExec`'s query stages. `assertNotFused` is the dangerous one:
with AQE on it passes when a fused node *is* there but hidden inside a stage -
a test passing for the wrong reason, which is the trap `VarkaDifferentialSuite`
dodged by hand.

`VarkaSharedSessions` mixes in `AdaptiveSparkPlanHelper` and the three helpers
use its stage-aware traversals. `VarkaDifferentialSuite`'s AQE test then drops
its hand-rolled traversal and calls the shared helpers, which is also the
regression test for this change: it runs with AQE on, so it fails if the
helpers stop seeing into stages.

### 2.3 Price the one open `GROUP_BUDGET` candidate

The register left `GROUP_BUDGET` "measured, mostly closed": single-output loop
methods are healthy at every width tried, and the only candidate left is raising
16 to ~24 so two outputs sharing a deep chain keep their cross-output CSE in one
method - conditional on a multi-output compile-latency measurement at that
width, because 48-op multi-output loops were healthy in one environment and
collapsed in another.

The emitter gains a `groupBudgetForTesting` override beside its existing test
hooks (`disableCseForTesting`, `divFloorModForTesting`), and the parity
benchmark gains a pair of cases over the shape that would benefit - two outputs
over a shared depth-8 chain with six more ops each, 20 distinct ops, which is
the narrowest shape that straddles the budget while keeping both roots inside
`MAX_CHAIN_DEPTH` - emitted at budget 16 (two loop methods, CSE split across
them) and at 24 (one loop method, CSE kept). Task 14's diagnosis gives the model to
test against: C2 compile time runs about a millisecond per vector op, so 24 ops
should cost ~24 ms of per-task warm-up rather than the seconds-long cliff task
11 saw. The decision is whatever the measurement says, recorded either way; the
hook stays so the next retune is re-measurable.

## 3. Verification

```
build/sbt "catalyst/testOnly *Varka* *ClassFile*" "sql/testOnly *Varka*"
build/sbt "catalyst/testOnly *VarkaLoopEmitterSuite"   # and at -XX:MaxVectorSize=16
./build/mvn -f sql/varka/engine/pom.xml test
SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
  "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark" \
  "sql/Test/runMain org.apache.spark.sql.execution.benchmark.VarkaCodegenBenchmark"
build/sbt catalyst/scalastyle catalyst/Test/scalastyle sql/scalastyle sql/Test/scalastyle
dev/lint-java
```

Acceptance:

* No reference to the dispatcher layer survives outside the plan files that
  record it, and the full Varka suites plus the wider codegen suites stay green -
  the cache-key change touches every query that contains a date expression, so
  `CodeGenerationSuite` and the projection generators' suites are part of the
  net, not just the Varka ones.
* The AQE helpers are exercised with AQE on and fail if they stop seeing into
  query stages.
* The `GROUP_BUDGET` decision is a number in this file, with the committed
  parity results behind it.

## 4. Explicitly out of task 17

* **The row-consumer fusion decision** (the register's third item): task 14
  measured it, and whether `VarkaColumnarRule` should decline row-consumer
  fusions is a milestone-3 policy question tied to filters and the Arrow-native
  writer, not a debt to sweep.
* **Section 14's debuggability remainder**, which stays milestone 3's.
* **Anything the deletion reveals** beyond the layer itself: if removing the
  trait exposes further unused Varka surface, it is recorded here and swept in
  its own change rather than growing this one.

## 5. Outcome

**Status: DONE.** All three parts landed as planned; the one surprise was the
`GROUP_BUDGET` measurement, which settled the candidate in the opposite
direction from the register's expectation.

* **2.1, the dispatcher layer.** Retired whole, along with `CodeAndComment`'s
  `classFileGenOps` and the `CodegenContext` registry, so Janino's compile-cache
  key is upstream Spark's `body` again. `DateVarkaSupport.isDateAttribute` went
  with it - the retired eligibility rule was its only caller - while
  `foldDaysOffset` stays, since the IR compiler folds day offsets with it. The
  suites and benchmark that tested the layer went with it, and
  `VarkaGeneratedClassLoaderSuite` now proves Metaspace unloading with classes
  from `VarkaLoopEmitter`, which is the assembler the execution path uses.
* **2.2, the AQE helpers.** `VarkaSharedSessions` mixes in
  `AdaptiveSparkPlanHelper`; the two AQE differential tests call the shared
  assertions instead of traversing by hand, so they are the regression test.
* **2.3, `GROUP_BUDGET`.** Measured and **closed against raising it**. On 20
  distinct ops across two outputs sharing a depth-8 chain, the shipped budget of
  16 runs 4587.1 M rows/s - two loop methods, the shared chain recomputed per
  lane group - against 3196.2 M rows/s at 24, where one method keeps the
  cross-output CSE. The register framed the trade as "CSE saved versus compile
  latency risked" and expected the wider method to be at worst slower to
  compile; what the measurement shows is that it is slower to *run*.
  Recomputing eight ops in registers is cheaper than the register pressure of a
  20-op method, which is the same effect that made sibling loop methods the rule
  in task 11 - now confirmed from the other direction. The budget stays 16, and
  both cases stay in the parity benchmark behind a `groupBudgetForTesting` hook
  so the next retune is measured rather than argued.

The register itself now lives in `PLAN_MILESTONE_2.md` section 8 with every item
carrying its outcome; `PLAN_MILESTONE_3.md` keeps item 13 of its scope catalogue
as a pointer so the cross-references in `PLAN_TASK_13.md` and `PLAN_TASK_14.md`
still resolve and item 14 keeps its number. The only register item left open is the one that is
a policy decision rather than a debt - whether the rule should decline
row-consumer fusions - which stays milestone 3's.
