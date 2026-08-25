# Task 12: multi-output, passthrough, escape hatch

**Status: DONE.** See `PLAN_MILESTONE_2.md` (task row 12, section 2.5) for
the milestone context. Sections 1-5 are the plan as reviewed; section 6
records the outcome, including the escape-hatch measurement that went the
other way than either variant's backers would have guessed.

## 1. Why this task, and what it stands on

Eligibility is still all-or-nothing: one entry the compiler declines sends the
whole projection to Janino, so `SELECT date_add(d, 3), i, i + 1` gets nothing -
the flat "mixed projection" row in the committed throughput results, and the
last of milestone 1's three measured consequences still standing. Task 12 makes
a projection eligible when *any* entry compiles, assembling the output batch
column by column: kernel-served columns from the fused loop, bare columns
forwarded as the input's own vectors (zero copy), and everything else evaluated
per row into writable vectors with the machinery the columnar-write change
already added (`RowToColumnConverter`, `OnHeapColumnVector` /
`OffHeapColumnVector`).

The ownership question this raises was surveyed before this plan was written,
and Spark turns out to already have the needed protocol. `ColumnarBatch.close()`
closes every column unconditionally (`ColumnarBatch.java:42`) - the hazard the
milestone flagged - but there is a second tier: **`closeIfFreeable()`**
(`ColumnarBatch.java:52`, `ColumnVector.java:81`) releases only columns "whose
resources are freeable between batches", and `WritableColumnVector` /
`ConstantColumnVector` override it as a no-op (`WritableColumnVector.java:119`).
That is exactly how the vectorized Parquet reader's reusable vectors survive
the standard `ColumnarToRowExec` consumer, which calls
`current.closeIfFreeable()` between batches (`Columnar.scala:231`); the reader
closes its vectors once, at its own `close()`. The Arrow cache serializer in
this tree already practices explicit borrow semantics
(`ArrowCachedBatchSerializer.scala:1211`: "we don't close the root here because
we don't own the vectors"). There is no delegating non-owning wrapper class
in-tree; the no-op-release override *is* the in-tree mechanism. Task 12's
design follows that grain rather than inventing a wrapper (2.2).

What this task deliberately does not change: the compiler stays the single
eligibility oracle, the emitted kernel and its method layout are untouched
(the fused entries just form a smaller output list), and the per-batch
fallback remains whole-batch - a kernel failure still sends the entire batch
to the row projection, exactly as today.

## 2. Deliverables

### 2.1 Compiler: per-entry classification

`VarkaExpressionCompiler` gains the partial entry point both the rule and the
evaluator move to:

```scala
sealed trait VarkaOutputSpec
case class FusedOutput(fusedIndex: Int) extends VarkaOutputSpec      // kernel column
case class ForwardedOutput(childOrdinal: Int) extends VarkaOutputSpec // zero copy
case object ResidualOutput extends VarkaOutputSpec                    // per-row

case class PartialVarkaProjection(
    specs: Seq[VarkaOutputSpec],          // one per projectList entry, in order
    fused: Option[CompiledVarkaProjection]) // the fused entries only; None if none

def compilePartial(projectList, childOutput): Option[PartialVarkaProjection]
```

Classification per bound entry: an expression that compiles to IR is
`FusedOutput` (its root joins the fused sub-projection's outputs); a bare
column reference - *any* type, not just dates, since forwarding does not care
about lanes - is `ForwardedOutput`; anything else is `ResidualOutput`. The
result is `Some` only when at least one entry fused: a projection of
forwards and residuals alone gains nothing from Varka and stays on Janino
untouched, so "eligible when any entry is" means any *fused* entry. Task 10's
deliberate bare-column decline is retired by this classification - the
task-10 differential that pinned "stays unfused until task 12" flips here.

`compile` (all-or-nothing) remains only as `compilePartial` + "every entry
fused" for the tests that want it; production callers use the partial form.

### 2.2 Evaluator: batch assembly and the ownership discipline

`VarkaKernelEvaluator` drives the partial plan per batch:

1. The fused sub-projection runs exactly as today, into freshly allocated
   Arrow vectors - **owned**.
2. Forwarded entries reference `input.column(ordinal)` directly - **borrowed**,
   zero copy, the same object (a test asserts `eq`).
3. Residual entries are evaluated in one per-row pass over
   `input.rowIterator()` through a residual-only `UnsafeProjection` (built
   lazily, task 15's discipline) into writable vectors sized to the batch -
   **owned**. One pass for all residual columns together, not one per column.
4. The output `ColumnarBatch` assembles the vectors in projection order.

Ownership follows the survey's conclusion: the evaluator keeps a per-batch
*owned-columns* list (`openBatches` becomes a map from batch to its owned
vectors), and every release path - the caller's `release(batch)`, and the
task-completion listener that drains abandoned batches - closes exactly the
owned vectors, never `batch.close()`. Borrowed vectors are released by
whoever owns the input batch, which stays correct because of an ordering rule
both exec nodes already obey and this task writes down as a contract: **the
output batch is released before the next input batch is requested**, so a
forwarded vector can never outlive its input. `canRun` extends naturally: the
Arrow-backed check applies to the columns the *fused* entries reference;
forwarded and residual entries put no constraint on the input format beyond
what `rowIterator` needs.

### 2.3 The row-node escape hatch, measured

For `VarkaProjectExec` (columnar out) the assembly above is the only option -
a columnar consumer needs materialised vectors. For `VarkaColumnarToRowExec`
the milestone left a measured decision open: naive batch assembly gives the
residual columns per-row evaluation *into a vector* and then a read back out
to rows - an extra materialisation for exactly the columns that gained
nothing. Two variants, benchmarked head to head on a mixed projection with a
row consumer:

* **(a) Assemble-then-read**: reuse 2.2 wholesale, then the existing
  result-batch row conversion.
* **(b) Merge-at-row**: convert only the fused columns' result batch to rows
  and evaluate the residual projection per input row during that same pass,
  splicing fused, forwarded and residual values into one output row (a
  projection over a joined view of the input row and the kernel-output row).

(a) ships first because 2.2 must exist anyway; (b) is implemented only if the
benchmark shows the extra materialisation costs more than a few percent, and
whichever loses is deleted, not left as a code path. The expectation, written
down before measuring (the record on such predictions is 0 for 3): (b) wins
on residual-heavy projections and the gap shrinks as the fused share grows.

### 2.4 Rule and nodes

`VarkaColumnarRule.isFullyVarkaEligible` becomes "does `compilePartial` return
`Some`" - both stages, otherwise unchanged. Both exec nodes keep their
structure; only the evaluator behind them changes shape. The injected-failure
hook and the `numVarkaBatches` metric keep their meaning: a batch counts as
Varka-served only when the fused kernel ran.

## 3. Verification

### 3.1 Unit and plan tests

* Compiler suite: classification triples (fused/forwarded/residual) including
  a bare int column forwarding, an all-residual projection returning `None`,
  and the fused sub-projection's outputs/ordinals/literals matching the
  fused entries alone.
* Rule tests on both sides of the transition fork: the mixed projection is
  fused pre- and post-transition; an all-residual projection is untouched.
* Exec-node suites, both nodes: a mixed projection (fused + forwarded + two
  residuals) produces correct batches/rows; the forwarded column is the same
  `ColumnVector` instance as the input's (`eq`); an injected kernel failure
  on a mixed batch falls back whole-batch with correct results and
  `numVarkaBatches == 0`.

### 3.2 Lifetime tests (the milestone's named requirement)

On both the drained and the abandoned-iterator paths, with the Arrow
allocator's memory accounting as the oracle (the pattern the existing release
tests use): kernel-output and residual vectors are freed exactly once;
forwarded vectors are *not* closed by any Varka release path - the input's
own release frees them, once; a task that stops mid-stream (LIMIT-style)
leaves nothing allocated after the task-completion listener runs. A
double-close would surface as an Arrow reference-count underflow; the tests
assert clean allocator state instead of merely not crashing.

### 3.3 End-to-end and the benchmark decision

* `VarkaDifferentialSuite`: `SELECT date_add(d, 3) AS a, i, i + 1 FROM ...`
  and the bare-date-column projection flip to `expectFused = true` with
  kernels-ran asserted (both were pinned as "until task 12" by name);
  predicated mixed projections (a `CASE WHEN` beside a residual); an
  all-residual projection stays unfused; the AQE and injected-failure
  controls repeated on a mixed plan.
* The escape-hatch benchmark (2.3), plus a throughput case for the mixed
  projection against the Janino baseline - the number that has been flat
  since milestone 1 and should now move. Full benchmark/docs refresh stays
  task 14.

### 3.4 Commands

```
build/sbt "catalyst/testOnly *VarkaExpressionCompilerSuite *VarkaLoopEmitterSuite"
build/sbt "sql/testOnly *Varka*"
build/mvn -f sql/varka/engine/pom.xml test          # untouched, must stay green
build/sbt "catalyst/scalastyle" "catalyst/Test/scalastyle" "sql/scalastyle" \
  "sql/Test/scalastyle" && dev/lint-java
```

## 4. Explicitly out of task 12

A profitability threshold for fusing residual-heavy projections (one fused
column among fifty residuals fuses under this task's rule; whether that ever
regresses and deserves a config is a measurement for task 14's matrix, a
config for milestone 3 if real). Boolean outputs, filters, aggregate-input
fusion (milestone 3, items 3-4 and the survey's aggregate-input note).
Telemetry (task 13). The benchmark/docs refresh (task 14).

## 5. Risks, named

* Ownership is the correctness surface: a forwarded vector double-closed, or
  an owned vector leaked on the abort path, fails intermittently and
  off-node. The 3.2 allocator-accounting tests exist because review alone
  cannot see reference counts.
* The ordering contract (output released before the next input is requested)
  is load-bearing for borrowed vectors and currently implicit in both nodes'
  iterators; it gets stated in the evaluator's doc and a test drains batches
  out of order against a defensive copy to prove the contract is real.
* Residual evaluation adds a per-row pass to batches that previously took
  one; the escape-hatch benchmark (2.3) and the mixed-projection throughput
  case are the check that partial fusion pays at realistic mixes, not only
  at kernel-heavy ones.

## 6. Outcome

Everything in sections 2 and 3 shipped; the deviations and the measurements
follow.

### 6.1 What shipped, and where it differs from the plan

* `compilePartial` classifies entries as planned, with one detail the plan did
  not call out: the input and literal tables are shared across entries (that
  is what lets CSE fire across outputs), so a *declining* entry must roll them
  back to their pre-entry state. Without the rollback, a residual entry's
  partially compiled subtrees would widen the fused kernel's input set - and
  `canRun`'s Arrow check - for no output. A compiler test pins the rollback on
  a `datediff` whose end child registers a column and a literal before its
  start child declines.
* `PartialVarkaProjection.fused` is not the `Option` of section 2.1's sketch:
  `compilePartial` returning `Some` already means at least one entry fused, so
  the field is always present and the type now says so.
* Ownership landed as designed: `openBatches` is a map from batch to owned
  vectors, every release path closes exactly those, nothing calls
  `ColumnarBatch.close()` on an assembled batch, and the ordering contract is
  stated in the evaluator doc. The lifetime tests assert exact allocator
  levels in both directions - above the expected level is a leak, below it a
  double-closed forwarded vector - on the drained, abandoned and failed paths
  (the failed path in both flavors: kernel failure and residual failure, which
  differ in what is already allocated when the throw happens).
* The rule change is one line per stage plus renames: `isVarkaEligible` asks
  `compilePartial(...).isDefined`.

### 6.2 The escape hatch, measured - and what it actually found

Assemble-then-read (a) shipped first, per the plan, and immediately paid for
the "measured decision" framing: with a row consumer over 2M Arrow-cached
rows, the mixed projection (`date_add(d, 3), i, i + 1`) ran at **0.7x**
Janino, and a residual-heavy one (one fused entry, four residuals) at
**0.5x** - far beyond the few-percent threshold, so merge-at-row (b) was
built. (b) evaluates forwarded and residual entries during the row
conversion's own per-row pass, over the input row joined with the
fused-output row, and the kernels produce only the fused columns
(`projectFused`).

Head to head, (b) beat (a) on both shapes - mixed 81 ms vs 89 ms, residual-
heavy 104 ms vs 128 ms best-time - with the margin largest where residuals
dominate and shrinking as the fused share grows. That is exactly the written
prediction, the first of four in this project to survive contact with a
benchmark. (a) is deleted from the row node per the plan's loser-goes rule;
`project`'s full assembly remains as the columnar node's path, where
materialising is the only option.

The control case is the real finding. An **all-fused** `date_add` through the
same row consumer also measures ~**0.8x** Janino (62 ms vs 49 ms), so the
row-consumer gap is not the merge, not the forwarding, and not the residual
materialisation (a) was blamed for: it is the per-row read-back
`VarkaColumnarToRowExec` always pays - iterator, `ColumnarBatchRow`, copy
projection - against a whole-stage-codegen baseline that reads the cached
vectors with generated accessors. A depth-1 kernel's margin cannot cover it;
the columnar-consumer cases are untouched by it (1.5x mixed, 1.8x-2.2x pure,
same runs). This was true before task 12 and simply had never been measured,
because every committed throughput case wrote to a columnar sink. Task 14's
matrix should measure how chain depth amortises the read-back on the row
path, and the fuse-profitability threshold stays the milestone 3 question
that section 4 already flagged - now with numbers attached.

### 6.3 The numbers (2M Arrow-cached rows, best time, vs Janino baseline)

| case | consumer | varka | relative |
| :--- | :--- | ---: | ---: |
| `date_add(d, 3)` | columnar (`noop`) | 34 ms | 1.8x |
| `date_sub(d, 5)` | columnar (`noop`) | 31 ms | 1.8x |
| `datediff(d2, d)` | columnar (`noop`) | 34 ms | 1.9x |
| mixed projection | columnar (`noop`) | 44 ms | **1.5x** |
| `date_add(d, 3)` | rows (`toRdd`) | 62 ms | 0.8x |
| mixed projection | rows (`toRdd`) | 81 ms | 0.8x |
| residual-heavy | rows (`toRdd`) | 104 ms | 0.7x |

The mixed-projection columnar row is the milestone-1 consequence retired: it
had been flat (~1.1x, fallback on both sides) in every committed run since
task 7, and partial fusion moves it to 1.5x. The row-consumer rows are the
new, honestly recorded cost of the read-back, owned by task 14 and
milestone 3 as above.
