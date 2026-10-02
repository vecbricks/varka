# Task 145: a narrowed filter loses the columnar path

*Scoped 18 September 2026 (milestone 5 section 2.81, row 145) from task 144's
crossed experiment, corrected twice the same night; planned 22 September 2026.*

## 1. The question

Three shapes over twenty million rows under a `noop` sink, from
`PLAN_TASK_144.md` 9.3:

| query | node | columnar sink | row path forced |
| :--- | :--- | ---: | ---: |
| `SELECT i FROM t WHERE i > 50000` | no narrowing | 862.3 | 144.4 |
| `SELECT i2 FROM t WHERE i > 50000` | narrowing | 120.3 | 142.8 |
| `SELECT i, i2 FROM t WHERE i > 50000` | no narrowing | 901.3 | 109.5 |

Two readings were taken and both were wrong. The first was "forwarding a
column costs ten times"; the second was "a filter that narrows its output
costs eight times". The control that refutes both is the right-hand column:
with the row read-back forced by `toRdd`, the narrowed and un-narrowed shapes
are within 1% of each other, and task 78 had already measured that shape at
three selectivities and both widths and found the narrowed form slightly
*faster*.

So the narrowing costs nothing. What the un-narrowed shapes have is something
the narrowed one lacks: they are seven times faster under a columnar sink than
under a forced row path, and the narrowed one is not faster at all. They stay
columnar end to end; it does not. The 120.3 is task 19's read-back floor,
reached through a plan difference rather than through a kernel difference.

The question is what that plan difference is, given that
`VarkaFilterColumnarToRowExec.columnarSibling` already builds
`VarkaProjectExec(narrowing, VarkaFilterExec(condition, child))` - the
columnar-out node computing exactly what the narrowed transition computes.
The machinery for the columnar answer exists. Something is not reaching it.

## 2. The change

This is an investigation with two admissible endings, which is why the row's
acceptance line is "route it there or record why not". Step 1 is not a change
at all.

### 2.1 Step 1: read the plans, before changing anything

Print the physical plan for all three queries under a columnar sink and under
a forced row path, six trees, and record them in section 5. This is minutes of
work and it either confirms the hypothesis below or replaces it. The project's
own lesson is that a reading taken from a rate rather than from the mechanism
has been wrong twice on this very row.

### 2.2 The hypothesis the code suggests

`VarkaColumnarRule` has two stages. Before transitions, a `ProjectExec` becomes
a `VarkaProjectExec` only when `isVarkaEligible(projectList, child.output)`,
which asks the compiler whether at least one entry *fuses* - and forwarding a
bare column is explicitly not fusing it. `SELECT i2 FROM t WHERE i > 50000`
projects one bare column, so it is not eligible, the pre stage leaves an
ordinary `ProjectExec`, and the transition pass inserts a row boundary under
it. After transitions, the arm that exists for this shape absorbs the
projection into the filter's *to-row* node:

    case ProjectExec(projectList, filter: VarkaFilterColumnarToRowExec)
        if filter.narrowing.isEmpty && isForwardedNarrowing(projectList, filter.output) =>
      filter.copy(narrowing = Some(projectList))

That absorption is right on its own terms - it saves a row-level projection
above the transition - but it settles the plan at the row boundary. The
un-narrowed shapes never acquire a projection to absorb, so the pre stage
leaves `VarkaFilterExec`, which is columnar out, and the sink consumes it
columnar.

If that is what the trees show, the mechanism is: **a projection that only
narrows is not eligible, so the only node that can perform it is a row node,
even when the consumer wanted columnar.** `columnarSibling` is never consulted
because nothing strips this transition on a plain query path; the one in-tree
consumer that does is `ArrowCachedBatchSerializer.convertToColumnarPlanIfPossible`
on the cache-population path.

### 2.3 If the hypothesis holds, the arms

| arm | what changes | reach |
| :--- | :--- | :--- |
| A | the pre stage builds `VarkaProjectExec(narrowing, VarkaFilterExec(...))` when the child is columnar and the projection only narrows | the plain query path and the cache path both |
| B | the post stage consults `columnarSibling` when the consumer supports columnar | narrow, and duplicates what transition insertion already decides |
| C | leave it, and record that a narrowing consumer pays the read-back | costs nothing, explains the 120.3 |

Arm A is the one to build if the trees confirm the hypothesis: it makes the
eligibility question "can Varka serve this plan columnar" rather than "does
any entry fuse", which is the question the sink is actually asking, and it
reuses the sibling's own construction rather than inventing a second one. Arm
B repeats the transition pass's decision in a second place, which is the shape
of bug the `VarkaFusedTransition` trait exists to prevent. Arm C is the honest
ending if A turns out to regress a shape that fuses today, and section 5 says
so either way.

**The risk arm A carries, and the test that catches it.** A projection that
narrows *and* fuses must keep its existing plan; the new case has to run after
the eligible arm, exactly as the absorption case already does, and a suite
assertion pins that ordering rather than leaving it to the reader.

## 3. Predictions, registered before the run

1. **The plan trees differ at the projection, not at the filter.** Under a
   columnar sink, the un-narrowed shapes end at `VarkaFilterExec` and the
   narrowed one at `VarkaFilterColumnarToRowExec` with `narrowing` set. If
   instead both end at the same node, the hypothesis is wrong and section 5
   records what the trees showed.
2. **The narrowed shape under arm A lands within 10% of the un-narrowed one**
   under a columnar sink - near 862.3 rather than near 120.3 - because the
   kernel is the same one and the difference is a read-back that no longer
   happens.
3. **No shape that fuses today changes plan.** The eligible arm runs first, so
   a projection with anything to fuse is untouched, and `emitted_bytes.json`
   does not move because no IR changes.
4. **The row-path column does not move.** Arm A adds a columnar route; it does
   not touch what a row consumer gets, so 142.8 stays 142.8 within its band.

## 4. Verification

- `VarkaFilterExecSuite` and `VarkaProjectExecSuite`, with a plan-shape
  assertion per arm of the rule and the ordering assertion of 2.3.
- `VarkaDifferentialSuite`, for the answers: a narrowing filter must select
  the same rows and the same columns whichever node runs it.
- `VarkaEmittedBytesSuite` and `VarkaCoverageSuite` unchanged, for prediction 3.
- The three queries re-measured under both sinks with the same harness task
  144 used, on an idle machine, and the table of section 1 reprinted beside the
  new numbers in section 5.
- `VarkaTimeArrowCacheSuite` and the serializer suite, because the cache
  builder is a columnar consumer of exactly this shape and arm A changes what
  it is handed.
- `dev/scalastyle`, and the 100-column and non-ASCII scans.

## 5. Outcome

### 5.1 Step 1: the six trees, 23 September 2026

Read before changing anything, as 2.1 asks. The `.noop()` arms are the write's
own executed plan, caught with a `QueryExecutionListener`, because a
DataFrame's `executedPlan` is always built for a row consumer and would have
answered the wrong question:

| query | columnar sink | row path (`toRdd`) |
| :--- | :--- | :--- |
| `SELECT i ...` | `VarkaFilter` | `VarkaFilterColumnarToRow` |
| `SELECT i2 ...` | `VarkaFilterColumnarToRow, List(i2)` | `VarkaFilterColumnarToRow, List(i2)` |
| `SELECT i, i2 ...` | `VarkaFilter` | `VarkaFilterColumnarToRow` |

**Prediction 1 held exactly.** Under a columnar sink the un-narrowed shapes end
at `VarkaFilterExec`, which is columnar out, and the narrowed one ends at
`VarkaFilterColumnarToRowExec` with `narrowing` set - a row node, so the sink is
handed rows however it asked. The narrowed shape's two trees are *identical*:
there is no columnar plan for it to have.

So the hypothesis of 2.2 is what the trees show. A projection that only narrows
is not Varka-eligible, because eligibility asks whether any entry *fuses* and
forwarding a bare column is not fusing it; the pre stage leaves an ordinary
`ProjectExec`, the transition pass inserts a row boundary under it, and the post
stage's absorption arm folds the projection into the filter's to-row node. That
absorption is right on its own terms and it settles the plan at the row
boundary.

### 5.2 Arm A, and the half the plan did not predict

Arm A is built: the pre stage matches a forwarding-only projection over a
`VarkaFilterExec` and builds `VarkaProjectExec(narrowing, filter)` - the pair
`columnarSibling` already builds for the cache path - and the post stage
collapses that pair back into `VarkaFilterColumnarToRowExec(condition, child,
Some(projectList))` wherever a to-row transition was inserted above it anyway.
The collapse is what keeps prediction 4's promise structurally rather than by
hope: a row consumer gets exactly the node it always got, one that reads the
selection bitmap at the row boundary instead of compacting first and projecting
after.

With only that, `SELECT i2 FROM t WHERE i > 50000` went from 120.3 to **164.6**
M rows/s. A 1.37x, against the 7x prediction 2 asked for. The plan was
columnar and the rate was not.

**The second cost, which the plan missed and the first measurement found.** A
projection that computes nothing compiles to no kernel, so
`VarkaKernelEvaluator.serveBatch` has no kernel path to take and sends every
batch to `VarkaProjectExec`'s fallback - which allocates a batch and projects
*row by row* into it, rebuilding columns the input already holds. Arm A moved
the read-back out of the plan and put a full column materialisation in its
place.

A projection that only forwards columns is a **selection**: the output is the
input's own vectors, reordered and dropped, and nothing has to be touched. The
evaluator now computes those ordinals once and takes that path, through a new
`VarkaEvaluatorBase.forwardColumns`. The ownership detail is the part worth
pointing at, because getting it wrong would have been invisible in every
answer: the batch is tracked owning *nothing*, so `release` unregisters it and
closes none of its columns. A batch built and not tracked would have reached
`release`'s "not one of ours" arm and been closed whole, taking the input
batch's vectors with it.

### 5.3 The numbers

`VarkaFilterNarrowingBenchmark`, 20M Arrow-cached rows, one core, regenerated
pinned at load 0.84 with the canary clean:

| case | before | after |
| :--- | ---: | ---: |
| A one column, filtered and output (no narrowing) | 862.3 | 907.4 |
| **B two columns, the other output (narrowing)** | **120.3** | **883.7** |
| C two columns, both output (no narrowing) | 901.3 | 964.6 |
| **D two columns, both in the predicate (narrowing)** | **101.3** | **951.8** |
| E one column, no output (count) | 104.2 | 132.2 |
| F A at about 1% selectivity | 1752.6 | 1724.2 |
| G B at about 1% selectivity | 1274.2 | 1816.4 |
| A through `toRdd` (row path forced) | 144.4 | 111.0 |
| B through `toRdd` (row path forced) | 142.8 | 140.5 |
| C through `toRdd` (row path forced) | 109.5 | 108.0 |

**B is 7.35x faster and lands within 2.6% of A**, the un-narrowed shape it was
eight times behind. **D, which nobody predicted, is 9.4x faster**: the plan
tracked one narrowing shape and the arm serves both, because `SELECT i FROM t
WHERE i > 50000 AND i2 >= 0` narrows for the same reason - a predicate reading
more columns than its consumer wants.


### 5.4 The band, and what it decides

Two rows moved that the change cannot touch - `count(*)` by +26.9% and A
through `toRdd` by -23.1% - and this file had no band, so neither move was
readable. Ten pinned runs now give it one, committed as
`VarkaFilterNarrowingBenchmark-jdk25-band.txt`: median spread 6.20%, p90 and
max 30.60%, two cases in tier 0, four in tier 1, three in tier 2 and one in
tier 3.

| case | band | moved | reading |
| :--- | ---: | ---: | :--- |
| A no narrowing | 19.0% | +5.2% | inside |
| **B narrowing** | 4.3% | **+634.6%** | the change |
| C no narrowing, two columns | 7.3% | +7.0% | inside |
| **D narrowing** | 4.1% | **+839.6%** | the change |
| E count | 5.2% | +26.9% | outside; see below |
| F A at 1% | 10.4% | -1.6% | inside |
| **G B at 1%** | 12.7% | **+42.6%** | the change |
| A through `toRdd` | **30.6%** | -23.1% | inside, and tier 3 |
| B through `toRdd` | 2.3% | -1.6% | inside |
| C through `toRdd` | 1.5% | -1.4% | inside |

**A through `toRdd` is the noisiest case in the file**, 110.8 to 144.7 over ten
runs with nothing changed. The committed before (144.4) and after (111.0) are
almost exactly its two ends. Without the band that row reads as a 23%
regression in the shape the task promised not to touch; with it, the row is
tier 3 and says nothing at all. It is the clearest case this project has of
row 90's rule earning its keep.

**`count(*)` is the one row the band does not settle**, and the answer is that
its committed baseline predates the tree rather than the change. Today's
*pre-change* run of the same benchmark on this machine read 125.9, inside the
post-change band of 126.2 to 132.7; the committed 104.2 was taken in September.
Both of today's numbers agree and neither agrees with the file, so what moved
is somewhere between the two commits and is not this arm - which touches no
plan without a projection in it, and `SELECT count(*)` has none.

### 5.5 The predictions scored

1. **Held**, exactly as written: 5.1's trees differ at the projection and not
   at the filter.
2. **Held.** B reads 883.7 against the un-narrowed A's 907.4, a 2.6% gap
   against the predicted 10%, and its own band is 4.3%. The prediction was
   right about the destination and wrong about the road: it assumed arm A alone
   would get there, and arm A alone reached 164.6. What closed the rest was
   5.2's selection path, which the plan did not know it needed.
3. **Held.** No shape that fuses changed plan - the eligibility arm runs first
   and a suite assertion now pins that ordering - and `emitted_bytes.json` did
   not move, because no IR changed.
4. **Held, and only the band could say so.** B and C through `toRdd` moved
   -1.6% and -1.4% against bands of 2.3% and 1.5%. A through `toRdd` moved
   -23.1% against a band of 30.6%, which is tier 3: not readable, and inside
   its own spread either way.

**What the task also found**, beyond its question. Two shapes nobody was
tracking improved for the same reason as B: D, `WHERE i > 50000 AND i2 >= 0`,
by 9.4x, because a two-column predicate under a one-column consumer narrows
exactly as a one-column predicate under a different consumer does; and G, B at
one per cent selectivity, by 42.6%. The plan reasoned from one query and the
mechanism serves a family.

And one tooling defect, found by trying to use the band script on a `sql/core`
benchmark: `dev/varka_bench_repeat.sh` passed its module argument straight to
sbt, so `core/Test/runMain` reached Spark Core, where no Varka benchmark class
exists. `dev/varka_bench_regen.sh` has mapped `core` to the `sql` project since
it was written and this script never did, so its own usage line -
`dev/varka_bench_repeat.sh core VarkaThroughputBenchmark 4 --band` - could not
run. Fixed here, since the band above is the first thing that needed it.

## 6. Explicitly out of this task

- **The read-back floor itself** (task 19, and item 13 of
  `SCOPE_MILESTONE_8.md`): this task removes one plan's need to pay it, not
  the cost of paying it.
- **Whole-stage codegen support** for the Varka transition nodes, which the
  `VarkaColumnarToRowExec` comment records as a follow-up and which no
  measurement here needs.
- **Narrowing inside the kernel.** A filter that narrows is forwarding
  columns, not computing them; making the kernel drop columns is a different
  design and nothing here asks for it.
