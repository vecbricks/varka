# Task 200: output grouping chosen exactly

*Planned 30 September 2026 (milestone 6 row 200), with its admission check run the same day.*

## 1. Where this came from

Milestone 6 row 200, from `READING_MILESTONE_6.md` section 3. `groupOutputs` is greedy: it walks
the outputs in order and closes a loop-method group when the next output would pass a budget.
TENSAT's section 5.1 shows that a greedy pass over a graph with sharing overestimates the cost,
and the reading note observes that the best partition of outputs in their given order is a
dynamic program, quadratic in the outputs. The row gates the task on whether grouping moves the
numbers at all.

Three things the task inherits since the row was written:

* **A cost that needs no emission.** Task 199's fitted model (`VarkaEmitCost`) predicts each
  candidate group's bytes and call sites from its features, so a dynamic program can price all
  of the roughly n * n / 2 contiguous runs of outputs.
* **A partition whose cost is a sum over its groups.** Until task 190's A0 the driver grew with
  every group and had a byte limit of its own, a constraint across the whole partition. With the
  driver from a table (`PLAN_TASK_190.md` 10) it is 44 bytes a group and 181 groups fit, so each
  group can be priced alone.
* **A shared prefix is cheap to cut through.** Since task 198 a later group loads a calendar
  prefix an earlier group computes, six vector loads in place of a thirty-one-op
  decomposition, so a cut through sharing costs less than when the row was written.

## 2. The admission check, done

Run on task 190's A0 branch at `9d92b1548d1` through a scratch hook and probe, neither committed.
For each start output, the hook grew a group one output at a time through the grouping's own
`GroupOps` and reported, for every run of outputs, its op total in the weights' units, whether
the greedy rule would admit that run as one group (both of its clauses, whole-node reuse
included), and the fitted model's predicted bytes and call sites. The probe then solved the
dynamic program: the partition with the fewest total ops, ties broken by fewer groups, over runs
the greedy rule admits and the model predicts within the byte budget, and within the call-site
budget for a group wider than `HEAVY_GROUP_OUTPUTS`. It compared that with the shipped greedy
grouping, both before and after the build's measurement regroups it, over the cost model's
corpus: the first 2000 fuzz shapes of each lane, 200 wide shapes of each lane (20 to 200 roots),
and the size, `make_date` and cheap-tail ladders.

| family | shapes | loop methods, shipped (after regroup) | groups, exact | total ops saved | largest saving on one shape |
|---|---:|---:|---:|---:|---:|
| fuzz int | 2000 | 3296 | 3296 | 0.01% | 3.84% (one shape) |
| fuzz long | 2000 | 2735 | 2735 | 0% | 0% |
| wide int | 200 | 17865 | 17851 | 0.02% | 0.91% |
| wide long | 200 | 13028 | 12867 | 0.12% | 2.32% |
| size ladder, 16 to 400 entries | 5 | 193 | 193 | 0% | 0% |
| `make_date` ladder, 12 and 60 | 2 | 14 | 14 | 0% | 0% |
| cheap tails, 22 and 64 | 2 | 6 | 5 | 0% | 0% |

Total ops are measured against the greedy grouping with task 199's `predictGrouping`, which
closes groups where the measurement would split them; against it the exact partition is never
worse, by construction.

**What the check says.** Greedy grouping in output order is already at or within a fraction of a
percent of the optimum on every family. On the ladders, the shapes the size-control work is
measured on, it is the optimum exactly. The largest gain anywhere is 1.2% fewer loop methods on
the wide long-lane shapes and 2.3% fewer ops on the best single one of them. The cheap tails'
one method saved is the one `predictGrouping` already saves (`PLAN_TASK_199.md` 9.3). The
likely reason greedy does so well here, unlike in TENSAT's case, is that the partition is over a
sequence rather than a graph, and the budget that closes a group is local, so a greedy close
seldom forecloses a better cut later; the check measures the gap, not this explanation.

**The verdict: not admitted.** The row's condition was that grouping should move the numbers,
and in output order it does not, by more than a percent anywhere. A dynamic program in
`groupOutputs` would add a quadratic pass and a second grouping to keep in step with the first
for gains below what the benchmarks can resolve.

## 3. What remains, and where it goes

* **Reordering the outputs** so that outputs sharing a prefix sit in one group is the lever the
  check leaves untouched: every partition above keeps the projection's order. It is the
  NP-flavoured part the reading note names, and it is already in the milestone's debt register
  (the known limitation `year(d), year(d2), month(d)` that `groupOutputs`' doc describes). It is
  not a task of this milestone.
* **`predictGrouping`'s default**, which task 199 left to this task, does not need the exact
  partition: the greedy close with the prediction is what the check compares the optimum
  against, and it is within the table's margins of it. Whether to switch it on is a question of
  run time, since it changes which outputs share a method on the shapes where the measurement
  would otherwise halve a group; its measurement is the next step for it, a section of
  `VarkaWideKernelBenchmark` over the cheap tails and the wide shapes, predicted and not.
* **The call-site limit's calibration** (`SCOPE_MILESTONE_7.md` item 61) and a register of
  measured costs in place of the weights (item 63) are unaffected.
