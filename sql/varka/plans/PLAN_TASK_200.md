# Task 200: output grouping chosen exactly

*Planned 30 September 2026 (milestone 6 row 200). The admission check ran the same day, was
first read as a rejection, and was corrected after review (section 6); what stands is below.*

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

Run on task 190's A0 branch at `9d92b1548d1`, so that no shape declines on the driver, and
committed as `VarkaGroupingBoundSuite` over `VarkaGroupingBound`, so that it can be run again
when the grouping or the cost model changes.

**The method.** For each output taken as a start, `VarkaLoopEmitter.runsForTest` grows a group
one output at a time through the grouping's own `GroupOps`, and reports each run's op total in
the weights' units and whether the greedy rule admits the run as one group. Admission is decided
by the same `admit` the grouping calls - the weights' two clauses, the cost model's prediction
where `predictGrouping` is on, and an output that adds nothing to its group joining it whatever
the budgets say - so the best partition is searched over exactly the runs the grouping could
form, and the grouping's own partition is among them. The dynamic program then takes the
partition with the fewest ops, and of those the fewest groups; a single output is always a
group of its own. Ops are the objective because they are the per-row work the weights price,
including what a group recomputes or loads of what it shares; a cost in bytes per method would
rank the same partitions the same way and prefer fewer groups more strongly. The baseline is the
greedy grouping with the prediction on, which is the first grouping the build keeps; the
weights-only first grouping and the loop methods as shipped, after the build's measurement
regroups them, are reported beside it. Nothing here is timed: the check is in the units the
grouping decides in, and section 4 is where time is read.

**The corpus.** The cost model's: the first 2000 fuzz shapes of each lane, 200 wide shapes of
each lane (20 to 200 independent random roots), and the size, `make_date` and cheap-tail
ladders. Two families the review found missing: shapes whose prefix-sharers are not adjacent -
four families rotated over one date; the fields of two dates interleaved; the fields of twelve
dates listed by date and listed by field - and sixty projections of 20 to 200 entries drawn from
the coverage table's rows through the compiler, which have the sharing a real projection has.
The fuzz shapes hold one to three roots, so grouping is trivial there; they are in the table
because the cost model's corpus is, not because they can show a gap.

| family | shapes | loop methods: greedy first grouping | with the prediction | best partition | as shipped, after the regroup | ops the best saves | largest saving on one shape |
|---|---:|---:|---:|---:|---:|---:|---:|
| fuzz int | 2000 | 3296 | 3296 | 3296 | 3296 | 0.01% | 3.84% (shape 851, two groups either way) |
| fuzz long | 2000 | 2735 | 2735 | 2735 | 2735 | 0% | 0% |
| wide int | 200 | 17856 | 17862 | 17851 | 17865 | 0.02% | 0.91% |
| wide long | 200 | 12750 | 12971 | 12865 | 13028 | 0.12% | 2.43% (shape 63, 56 to 52 groups) |
| size ladder, 16 to 400 entries | 5 | 193 | 193 | 193 | 193 | 0% | 0% |
| `make_date` ladder, 12 and 60 | 2 | 14 | 14 | 14 | 14 | 0% | 0% |
| cheap tails, 22 and 64 | 2 | 2 | 5 | 5 | 6 | 0% | 0% |
| mixed families over one date, 40 to 200 | 3 | 170 | 170 | 88 | 170 | 0% | 0% |
| two dates, fields interleaved, 24 to 120 | 3 | 204 | 204 | 204 | 204 | 0% | 0% |
| twelve dates, fields by date | 1 | 12 | 12 | 12 | 12 | 0% | 0% |
| twelve dates, fields by field | 1 | 48 | 48 | 48 | 48 | 0% | 0% |
| coverage compositions, 20 to 200 entries | 60 | 2298 | 2306 | 2287 | 2313 | 0.12% | 0.69% |

**In ops, greedy is at the optimum.** On every family the best partition saves 0.12% of the ops
or less; the largest saving on any one shape is 3.84%, on a fuzz shape of two groups where a
different cut shares a little more. This is the row's own cost, and in it the exact partition
buys nothing. The likely reason, which the check measures the gap of rather than proves: the
partition is over a sequence, and the budget that closes a group is local, so a greedy close
seldom forecloses a cheaper cut later.

**In loop methods, greedy strands the cheap outputs.** On the mixed family the best partition
has 88 groups where greedy has 170, at the same ops to the op. The shape rotates a `greatest`
ladder entry, a `make_date`, a cheap tail `year(d) + k` and a `date_add(d, k)` over one date.
The `date_add` shares no prefix, so it can join a group only under clause 1, while the group's
total is still within `GROUP_BUDGET`; greedy offers it to the group it has just filled with the
three outputs before it, where it cannot fit, and it becomes a one-op loop method of its own,
loading the column again. The best partition closes the group one output earlier and starts the
next one at the tail: `year(d) + k` alone is 14 ops with its prefix loaded, `date_add` makes 15,
and the `greatest` entry and the `make_date` then join over the prefix. Forcing those starts on
the grouping itself reproduces the partition exactly, 11 groups for 40 entries against greedy's
20, so it is a partition the rule already permits and greedy does not find. On the wide
long-lane shapes the same effect is 0.8% fewer methods over 61 shapes, and on the coverage
compositions 0.8% over 14: the stranding needs a cheap no-reuse output between two full groups,
which the rotation puts at every fourth output and a real projection puts somewhere now and then.

**What is reordering's, not partition's.** The two interleaved-date families take a loop method
per output under every partition that keeps the order - 24 methods for 24 fields of two dates,
each loading a prefix - because adjacent outputs share nothing and two fields with loaded
prefixes are 26 ops, over clause 1's 16, with no reuse to open clause 2; the same twelve dates'
fields take 12 methods listed by date and 48 listed by field. That is task 72's case
(`SCOPE_MILESTONE_7.md` item 15), now with its numbers, and no task of this milestone.

**The boundary.** The search is over the runs the grouping's rule admits, so the verdict is
about greedy against the best partition under the same rule; whether a different rule - one
that let a cheap output into a full group, or counted a loaded prefix as reuse - would do
better is not asked. And the verdict is in the grouping's units, not in time: whether 88
methods run faster than 170 at equal ops is section 4's measurement.

**The first probe's error, for the record.** Its rule applied the byte and call-site budgets to
every run, where the grouping skips them for an output that adds nothing to its group; on one
wide long-lane shape a seventh, duplicate output took a group past the six-output exemption from
the call-site budget with its predicted call sites already over it, the probe cut where the
grouping had not, and its "best" partition cost one op more. The shared `admit` closes that.

**The verdict.** Admitted, in a narrower form than the row's: not for ops, where greedy is at
the optimum, but for the methods a kernel is cut into, where greedy strands cheap outputs that
share nothing. Built behind a switch and measured, as every grouping change has been.

## 3. The design

### 3.1 The exact partition, behind a switch

`VarkaEmitOptions.exactGrouping`, off by default: `groupOutputs` prices every run the rule
admits, through the machinery `runsForTest` already uses, and takes the partition with the
fewest ops and then the fewest groups as the first grouping, in place of the greedy walk. The
rule itself - the weights' two clauses, the prediction under `predictGrouping`, the free join of
an output that adds nothing - is unchanged, so every partition the switch chooses is one the
greedy grouping could have formed, and the emit loop's measurement and regroup keep the last
word exactly as they do for the greedy grouping. The cost is a walk per start bounded by the
rule's own admission, which the check paid in about 25 ms for the widest shapes; a forced
start from a regroup halves a group as before.

### 3.2 What is deliberately unchanged

* The admission rule and the weights in `VarkaEmitBudget`: item 63's question.
* The output order: task 72's.
* The measurement of the built class, the regroup and the declines.
* `predictGrouping`'s meaning: the switches compose, and the exact partition under the
  prediction searches the runs the prediction admits.

## 4. Tests and measurement

* **Formability:** on every family of the check, the partition the switch chooses is what the
  greedy grouping produces when its starts are forced, so the switch never asks the emitter for
  a group the rule would not form.
* **Never worse:** under the switch no shape has more ops than under the greedy grouping, and
  on every family the loop methods equal the check's best-partition column;
  `VarkaGroupingBoundSuite` gains that arm.
* **Answers:** the mixed family at 40 and 200 entries against the reference evaluator on both
  bodies, and the fuzzer's option draw, which takes the switch like every boolean.
* **The measurement**, a section of `VarkaWideKernelBenchmark`: the mixed family at 100 and 200
  entries, greedy against exact, null-free and with every seventh row null, in 4096-row batches;
  the size ladder at 400 as the control, since its grouping does not change; and one emission of
  the mixed family at 200 entries under each switch in `VarkaEmissionBenchmark`, for the
  partition's cost at plan time.

### 4.1 Predictions, registered before the run

1. **The mixed family runs at least 3% faster per row under the exact partition**, on both
   bodies: a one-op loop method costs its column load, its loop and its call per batch, and
   the exact partition removes ninety of them from two hundred entries.
2. **The size ladder is unchanged** within the run's noise: its grouping is the same.
3. **Emission of the mixed family at 200 entries costs under 10% more** under the switch.
4. **No fuzz shape answers differently**, and the formability test finds no partition the rule
   would not form.

The switch flips on if 1 to 3 hold; the guard suite then holds the shipped grouping to the best
partition's methods as well as its ops.

## 5. What remains elsewhere

* **Reordering the outputs** so that sharers sit together: task 72, carried in
  `SCOPE_MILESTONE_7.md` item 15's table from `PLAN_MILESTONE_5.md` 2.25, with the numbers of
  section 2 added there.
* **`predictGrouping`'s default**, which task 199 left to this task: `SCOPE_MILESTONE_7.md` item
  71, measured on the same benchmark section as the switch above.

## 6. Sequencing

1. This plan, the check as a committed suite, and row 200 Planned.
2. `exactGrouping` behind its switch, with the tests of section 4.
3. The benchmark section, the predictions scored, the default set.

## 7. The review, 30 September 2026

The plan's first version reported the check as a rejection: greedy within a percent of the best
partition everywhere. A review found nine problems with it; the owner asked for all nine to be
addressed, and the answer to the fourth changed the verdict.

1. The plan said the best partition was never worse than greedy's "by construction", and the
   probe's own count showed one shape where it was. The cause is in section 2: the probe and the
   grouping disagreed on an output that adds nothing. The check now decides admission through
   the grouping's own `admit`.
2. The table mixed two baselines, so the 1.2% fewer methods it quoted on the wide long-lane
   shapes included the 0.4% that `predictGrouping` saves on its own. The table now names each
   column.
3. It called 2.3% the largest saving on one shape; the table showed 3.84%.
4. The corpus had no shape where the partition could matter: four thousand shapes of one to
   three roots, homogeneous ladders and independent random trees. The mixed family and the
   coverage compositions were added, and the mixed family is where greedy takes twice the
   methods.
5. The search's boundary - the same admission rule - was not stated. It is now.
6. The verdict rested on a proxy and did not say so. It does, and section 4 is the timing.
7. `predictGrouping`'s default was left to nobody. It is `SCOPE_MILESTONE_7.md` item 71.
8. "The debt register" was cited without a reference. Task 72 is item 15 there.
9. The probe was deleted after the run. It is `VarkaGroupingBoundSuite`, which holds greedy to
   the best partition's ops on every family and will hold it to the methods once the switch
   ships.
