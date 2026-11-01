# VARKA-544: A small decimal over a non-cache Arrow source reads as vanilla's transition gives it

## 0. What exists now (admission check)

From the plans worktree at `130280562f3` (master `5c660201b35` plus plan commits) and the branch
`varka-365-reader-casts` (head `53f10ed4020`, unpushed, no pull request), read on 1 November 2026
with `git` and `gh api` GETs. Nothing was dispatched and no JVM was run. `VARKA-365.md` below is
the plan on that branch, read with `git show
varka-365-reader-casts:sql/varka/plans/m8/VARKA-365.md`.

1. **The finding.** `VARKA-365.md` section 13 part 2: over a columnar child that is not the Arrow
   cache, vanilla's whole-stage `ColumnarToRowExec` reads each value through `ArrowColumnVector`'s
   accessor, a `BigDecimal`-backed `Decimal`, so the ANSI and TRY casts to `INT` answer by the
   double rule, and Varka on answers the same; without whole-stage code vanilla's transition
   copies each row into an `UnsafeRow` and answers by the exact rule, while Varka reads the
   batch's own rows and answers by the double rule. On `0.999999999999999999` as `DECIMAL(18, 18)`
   and `2147483647.99999999` as `DECIMAL(18, 8)` that is 0 and 2147483647 against 1 and
   `CAST_OVERFLOW` (null under TRY), well-formed values both.
2. **Why the copy changes the rule.** `ColumnarToRowEvaluatorFactory` maps every row through
   `UnsafeProjection.create(childOutput, childOutput)` (`ColumnarEvaluatorFactory.scala`, the
   `toUnsafe` of `input.rowIterator().asScala.map(toUnsafe)`). `UnsafeRowWriter.write(ordinal,
   Decimal, p, s)` writes `toUnscaledLong` for `p <= 18`, and `UnsafeRow.getDecimal` reads it back
   long-backed (`VARKA-365.md` section 10 part 2 item 2). The cast's `roundToNumeric` takes its long
   branch on a long-backed value and the double conversion otherwise.
3. **What 365 built for the cache.** `VarkaRowView` reads a small decimal long-backed, and
   `VarkaEvaluatorBase.inputRows` is the one place a Varka node reads an input batch's rows
   (`VARKA-365.md` section 12 part 2). The evaluator takes `readsArrowCache` as a constructor flag,
   from `VarkaColumnarRule.readsArrowCache(child)` at each node's evaluator build
   (`VarkaColumnarToRowExec.scala:165`, `VarkaFilterExec.scala:199` and `:452`,
   `VarkaProjectExec.scala:138`, all on the branch). With the flag off, the batch's rows are read
   as they are. Over a non-cache child the view's accessor path, the one section 13 part 2 keeps for
   a field at another scale, reads through `ArrowColumnVector` and makes the value long-backed in
   place, which is what the `UnsafeRow` copy gives on a well-formed value.
4. **The cell that records it.** `VarkaDecimalReadPathSuite`'s "a columnar child that is not the
   Arrow cache" (on the branch, lines 506-546) builds `ArrowDecimalBatchPlan`, a one-batch Arrow
   leaf, and asserts vanilla whole-stage gives the double rule and vanilla without it the exact
   rule. It runs Varka's node only in the whole-stage session.
5. **Other columnar vectors are long-backed already.** `WritableColumnVector.getDecimal` returns
   `Decimal.createUnsafe` of the int or long below precision 19 (Parquet's and ORC's vectorized
   readers), so only an `ArrowColumnVector` source parts the rules: an Arrow data source v2
   reader, the test leaf, and any connector that hands Spark Arrow batches.
6. **The whole-stage decision is per stage, not per session.** `CollapseCodegenStages` puts a
   `ProjectExec` or `FilterExec` into the stage of the `ColumnarToRowExec` below it only when
   `supportCodegen` holds for it: whole-stage on, no `CodegenFallback` expression, and neither its
   output nor its input schema past `spark.sql.codegen.maxFields`. A stage of the transition alone
   ends in an `UnsafeRow` write, so a projection kept out of the stage (by one `CodegenFallback`
   entry, say) reads the copied row and answers by the exact rule with whole-stage code on.
7. **Row 350's kernel builds the exact rule** (`VARKA-365.md` section 11 part 4, `VARKA-350.md`'s
   dated note): right over the cache, and the opposite of vanilla over a non-cache Arrow child whose
   consumer is compiled with the transition (the double rule, item 1).

### What differs from the finding's text

- The finding names "whole-stage codegen off". The condition is that vanilla's consumer is not
  compiled into one stage with the transition (item 6), which also holds with codegen on when a
  `CodegenFallback` expression or the field limit keeps the consumer out. Both are this row's.
- 365 is not merged and has no pull request; this row builds on its view and flag.

## 1. Goal and non-goal

**Goal.** Over any columnar child, each Varka node reads a small decimal as vanilla's plan would
hand it to the operator it replaces: long-backed where vanilla's operator reads the transition's
copied rows, as the batch gives it where vanilla's operator is compiled with the transition. On
well-formed data the ANSI and TRY casts to `INT`, `SMALLINT` and `TINYINT` then answer as vanilla
in every cell of the read-path suite.

**Non-goal.** No change to Spark's readers, the transition or `ArrowColumnVector` (the owner's
answer to `m8/PLAN.md` 7 question 3, option (iii)). No parity on corrupt slots (`VARKA-365.md`
section 13 part 1). No run-time whole-stage compile failure (a stage that fails to compile and falls
back), row 380's gap, as row 541 also leaves it.

## 2. Design

### 2.1 One question per node: does vanilla's operator read copied rows

`VarkaColumnarRule` gains `readsRowsLongBacked(vanilla: SparkPlan, child: SparkPlan): Boolean`,
true when `readsArrowCache(child)` (365's case, unchanged) or when vanilla's operator, the
`ProjectExec` or `FilterExec` the rule is replacing, would not be compiled into one stage with the
`ColumnarToRowExec` over `child`. The second is asked of Spark's own rule rather than ported:
`CollapseCodegenStages()` applied to `vanilla` over `ColumnarToRowExec(child)` (child replaced by
a leaf of its output, so nothing executes), and the answer is whether the result holds `vanilla`
and the transition inside one `WholeStageCodegenExec`. The rule runs before
`CollapseCodegenStages` in the planner, so it holds the vanilla node when it builds the Varka one.
For a stack (a Varka filter under a Varka projection), each node asks of its own vanilla node,
which is what vanilla does: a compiled filter reads the batch, a projection kept out of the stage
reads the stage's `UnsafeRow`s.

The flag is computed once on the driver, stored as the node's constructor field
(`readsLongBacked`, replacing the evaluator's `readsArrowCache` argument), and passed to the
evaluator as 365's flag is.

### 2.2 The view over a non-cache child

With the flag on over a non-cache child, `VarkaRowView` takes its accessor path for every small
Arrow decimal column (the low-word path stays the cache's, gated by
`VarkaLaneArrow.decimalFieldMatches` as 365 has it), and returns the long-backed value of what the
accessor gave, as `UnsafeRowWriter.write` and `getDecimal` do. A non-Arrow vector is passed
through, being long-backed already (0 item 5).

### 2.3 The kernel's casts (row 350)

When row 350 fuses the casts, a node with the flag off is a place where vanilla answers by the
double rule over an Arrow batch. The kernel runs only over Arrow batches (`VarkaEvaluatorBase`'s
`canRun` declines any other to the fallback, whose read of a long-backed vector is the exact rule
vanilla gives too), so the flag alone decides the kernel's case. 350's kernel either declines the
cast there (the residual's view then gives the double rule) or builds both rules and picks by the
flag. This row only exposes the flag on the compiler's side (`VarkaSessionEmit` or the node's emit
context) and leaves the choice to 350 (question 2).

## 3. Tests

`VarkaDecimalReadPathSuite`, the non-cache cell split into its sessions, each with Varka's node
served and with every batch declined (`setDeclineKernelForTesting`):

1. whole-stage on, the projection compiled with the transition: double rule (as today);
2. whole-stage off: exact rule (fails today);
3. whole-stage on, a `CodegenFallback` expression beside the cast in the projection: exact rule
   (*[I]: vanilla's answer here is an inference from item 6, and the cell asserts vanilla first*);
4. whole-stage on, more columns than `spark.sql.codegen.maxFields`: exact rule, vanilla asserted
   first;
5. the filter forms: `WHERE <cast> IN (0, 2147483647)` beside a fused conjunct over the leaf,
   whole-stage on and off.

Each new cell is checked to fail with the flag forced off. The cache's cells do not move.

## 4. Predictions and costs, registered before the build

1. Cells 2 to 5 fail before the change, answering by the double rule, and pass after it.
2. Cell 3's vanilla answer is the exact rule, and so is cell 4's (the [I] of 3).
3. No emitted byte moves; no cache cell's answer moves.
4. Planning cost: one `CollapseCodegenStages` pass over a two-node plan per Varka node, under a
   millisecond (read, not measured); per-row cost over a non-cache Arrow source is 365's view,
   which reads less per decimal than the accessor alone (`VARKA-365.md` section 11 part 2's
   allocation reading).
5. Added test time under 15 s: about eight cells over a one-row leaf.

## 5. Order, files and size

After 365 merges (question 1).

| file | what |
|---|---|
| `sql/core/.../execution/VarkaColumnarRule.scala` | `readsRowsLongBacked` |
| `VarkaColumnarToRowExec.scala`, `VarkaFilterExec.scala`, `VarkaProjectExec.scala` | the field |
| `VarkaEvaluatorBase.java`, `VarkaFilterEvaluator.java`, `VarkaKernelEvaluator.java` | the flag |
| `VarkaRowView.java` | the non-cache accessor path; its javadoc's limit |
| `VarkaDecimalReadPathSuite.scala` | 3's cells |

Size: **small**, one pull request, about one day.

## 6. Out of this row

- Corrupt slots, by the owner's word (`VARKA-365.md` section 13 part 1).
- Python, R and external UDF runners' Arrow reads, which are not Varka's nodes.
- A stage that compiles at planning but falls back at run time (row 380's gap).

## 7. Questions for the owner

1. **Build on 365's branch or wait.** 365 is built and unpushed. (a) Wait for 365's pull request
   to merge, then branch. (b) Build on 365's branch and merge both together. *Recommended: (a)*:
   365's review rounds are recorded and its pull request is the next step anyway; this row is a
   day's work after it.
2. **Row 350's casts over a non-cache Arrow child.** (a) The kernel declines the cast where the
   node's flag is off, so the residual's read gives vanilla's double rule (the kernel meets only
   Arrow batches, 2.3). (b) The kernel builds both rules and picks by the flag. *Recommended: (a)*:
   the double rule is the one 365's answer moved away from, an Arrow source other than the cache is
   rare in Spark SQL, and (a) keeps one rule in the kernel.
3. **Spark's rule or a port.** (a) Ask `CollapseCodegenStages` of the vanilla node (2.1). (b) Port
   `supportCodegen`'s three tests, as `fallbackCondition` does for a filter. *Recommended: (a)*: it
   follows upstream changes to the stage rule without a port; `fallbackCondition` could take the
   same answer later.

## 8. Done when

Over `ArrowDecimalBatchPlan`, every cell of section 3 answers as vanilla's plan in the same session
does, served and declined, on well-formed values; the cache's cells are unchanged; 350's plan
carries a dated note naming question 2's answer; the PR Build and an option-matrix dispatch pass.

## 9. Open points for the lead

1. Item 7 is a correctness point for row 350's kernel independent of this row's fix: over a
   non-cache Arrow child with the consumer compiled, vanilla answers by the double rule, and a
   kernel building only the exact rule would answer otherwise. 350's plan wants the note before
   its build.
2. `fallbackCondition`'s `compiled` test (`VarkaFilterExec.scala:80-89`) ports part of the same
   stage rule; if question 3 is (a), it could ask the same question and drop its port.

## 10. Outcome

(Written when the work lands.)
