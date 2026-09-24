# Task 172: one realistic query

## 1. Where this came from

`PLAN_MILESTONE_6.md` 2.6 asks for one query a reader recognises, where vanilla
Spark falls off the method-size cliff and Varka does not, with both arms
measured. Task 193's census of every whole-stage stage of TPC-DS and TPC-H
(`PLAN_TASK_193.md` 9.1 and 9.4) found exactly one such stage in 178 queries:
TPC-DS `modified-q3`. Its filter restricts `store_sales` by 200
`ss_sold_date_sk between a and b` ranges joined by `or`, the "partition key
filters" its comment names, which is the shape a BI tool writes for a set of
date ranges. With table statistics the crossing stage is
`Project < Filter < ColumnarToRow`, a filter over a scan and nothing else, and
its `processNext` is 12167 bytes: the scan loop itself runs interpreted.

That is the candidate. The owner agreed on 24 September 2026 to build two
competing ways for Varka to take it, and to measure both against vanilla.

## 2. The admission check, done

**Varka declines it today.** A probe of `VarkaExpressionCompiler.compilePredicate`
on the filter's first n ranges, over an int column, 24 September 2026 on master
`d08c8bc06db` (the probe becomes step 1's test):

| ranges | Varka | largest emitted method |
|---:|---|---:|
| 10 | fuses | 1546 bytes (`epilogueMasked0`) |
| 40 | fuses | 6635 bytes |
| 48 | fuses | 7995 bytes |
| 49 | declines at plan time | would be 8142 bytes (`loopMasked0`) |
| 200 | declines at plan time | would be 18315 bytes (`loopDense0`) |

The decline is the byte budget working as designed (tasks 87 and 169): the
whole `or` chain is one conjunct, one conjunct is one condition root, and a
condition root is emitted into one method, because the emitter splits a
kernel's *outputs* across methods but not the inside of one condition. Past 48
ranges the kernel would exceed 8000 bytes, so Varka declines it with that
reason and the filter stays with Spark: on the real query, vanilla's
interpreted 12167-byte method. So on `modified-q3` Varka neither helps nor
hurts today.

**Vanilla is more compact per range than Varka.** Varka spends about 165 bytes
a range in its masked loop (7995 at 48), vanilla's whole 200-range stage is
12167 bytes. So there is a band of range counts where vanilla still compiles
and Varka declines. The benchmark in 3.3 measures where vanilla crosses rather
than assuming it.

**Varka's condition compiler already has what both designs need.** It compiles
`Or`, `And`, `Not` and integer comparisons against literals
(`VarkaConditionCompiler.compileCond`), `BETWEEN` arrives as `>=` and `<=`, and
`IN` is fused up to `MaxInLiterals`, 16.

## 3. The design

Two designs, built behind options and measured against each other and against
vanilla, as the owner prefers for competing designs. They are not exclusive:
A is general, B is a specialisation; the measurement decides whether B earns
its place beside A.

### 3.1 Design A: one condition split across methods

`or` and `and` are associative, so a chain too large for one method can be
evaluated in groups. The emitter flattens a chain of one connective into its
operands, partitions them into groups whose methods fit the byte budget, emits
one method per group that computes a partial mask, and combines the partial
masks with the connective in the root. It is task 87's per-group split applied
inside one condition rather than across outputs, and it serves any large
predicate, not only ranges: a long `IN`, a deep `CASE WHEN` condition, several
hundred comparisons from generated SQL.

What it costs: each group's partial mask is written and read once, a memory
pass per group. Where the partial masks live is the same question task 198
asks for a shared prefix (a scratch vector per batch), and the answer should be
the same one.

What it changes: the plan-time admission (task 169) must accept a condition
that one method cannot hold, if its chain splits; a condition that is not a
chain of one connective, or whose single operand is over the budget, still
declines with a reason.

### 3.2 Design B: a range-set lowering

A disjunction whose every operand is a range over the same integer column with
literal bounds - `c between a and b`, `c >= a and c <= b`, `c = a` - is lowered
to one IR node, a set of ranges: the bounds are sorted and merged at compile
time into disjoint intervals, and each lane asks whether its value lies in one
of them. The kernel does that with a branch-free binary search over the sorted
lower bounds, about eight steps for 200 ranges, instead of 400 comparisons,
and its code is the same size whatever the number of ranges.

So it is asymptotically cheaper than the chain vanilla generates as well as
compact, and it would also lift the `IN` cap for integers, a list of values
being a set of one-point ranges. What it does not do is help any predicate
that is not ranges over one column; that is A's job.

Semantics to keep: a null value is unselected, as the `or` chain's null is;
bounds are inclusive; an empty range (`a > b`) is dropped; overlapping and
adjacent ranges merge; bounds at `Int.MinValue` and `Int.MaxValue` are
ordinary values.

### 3.3 The benchmark

`VarkaRangeFilterBenchmark`, its own family and results files: an Arrow-cached
int column of two million values drawn uniformly over `date_dim`'s surrogate
keys, filtered by the first n of `modified-q3`'s ranges, for n in 10, 48, 49,
100, 150 and 200, and counted. The arms: vanilla Spark, Varka as it is today
(which is vanilla from 49 ranges on), Varka with design A, Varka with design B.
Each rung writes vanilla's largest generated method, whether it is past 8000
bytes, and what each Varka arm fused, after its table, as the size ladder
does.

The baseline arms - vanilla and today's Varka - are committed first, as their
own PR, so the improvement is measured against a committed file.

This is the stage that crosses, not the whole query. `modified-q3` also joins
and aggregates, which Varka does not fuse; its time is the filter stage's plus
theirs. Whether the full query is timed as well is decided after the stage's
numbers: it only matters if the filter is a large share of it.

## 4. Files

| file | what |
|---|---|
| `VarkaConditionCompiler.scala` | B's pattern match, before the generic `Or` |
| the IR and the emitter | B's range-set node; A's chain split and partial masks |
| `VarkaEmitOptions.java` | an option for each, off until measured |
| `VarkaRangeFilterBenchmark.scala` | 3.3, and its results files |
| tests, per 5 | |
| `PLAN_MILESTONE_6.md` | row 172 |

## 5. Tests, and what each is for

* **The boundary is pinned.** Step 1's test asserts that today's compiler fuses
  48 of the ranges and declines 49 with a byte-budget reason, so the designs
  are measured against a boundary a test holds, not one this plan remembers.
* **Each design matches the row engine.** A differential suite over the range
  shapes, with nulls, bounds on both edges of every range, overlapping,
  adjacent and empty ranges, and the int extremes.
* **Every method stays under the budget.** At 200 ranges both designs emit no
  method past 8000 bytes, read from the built class.
* **The fuzzer draws the new shapes.** A long chain of one connective for A,
  and ranges over one column for B.

## 6. Predictions, registered before the build

1. **Vanilla crosses 8000 bytes between 120 and 160 ranges.** Its 200-range
   stage is 12167 bytes; if its cost per range is roughly constant, it crosses
   near 130.
2. **Design A fuses all 200 ranges** with every method under 8000 bytes, and
   costs roughly linearly in the ranges, since every lane still evaluates every
   comparison.
3. **Design B's time per row barely moves with the ranges** and is below A's
   from a few tens of ranges on.
4. **Both beat vanilla at every rung**, and above vanilla's crossing by more
   than ten times, as the size ladder's Varka arm does.

## 7. Risks

1. **A's partial masks may cost more than they save** at small group counts,
   where one method would have held the chain. The split applies only when one
   method cannot hold it, so below 49 ranges nothing changes.
2. **B's gathers.** A vectorised binary search reads the bounds array with a
   gather per step, and a gather is not free on every machine. The benchmark
   runs at both widths, and the laptop's figure is checked on a runner.
3. **The realistic case may be rare.** One query in 178 is what the census
   found. The post says so, and says the shape is a BI tool's, not a benchmark's
   invention.

## 8. Sequencing

1. The probe as a test that pins the boundary, and the baseline benchmark
   (vanilla and today's Varka), committed on their own; the timed run in a
   quiet window.
2. Design B, the smaller of the two, with its tests.
3. Design A, with its tests.
4. Both arms measured in a quiet window and on a runner, the predictions
   scored in 9, and a recommendation on which to keep on by default.

**A correction to the milestone's wording.** Criterion 3 of `PLAN_MILESTONE_6.md`
1.3 asks for a query where Spark logs "the whole-stage codegen was disabled".
Spark logs that only past `spark.sql.codegen.hugeMethodLimit`, 65535 by
default, or past `spark.sql.codegen.maxFields`; the cliff this milestone is
about, at 8000 bytes, logs nothing, which is the point of task 192's finding.
This task accepts a query whose method is past 8000 bytes and which Spark runs
interpreted without a word, and the post says the silence is the problem.

## 9. Outcome

### 9.1 Step 1: the boundary pinned and the baseline measured, 24 September 2026

`VarkaRangeFilterBoundarySuite` pins that Varka fuses the first 48 of the ranges and declines the
49th for the byte budget. It parses the ranges rather than building the `or` chain by hand: the
parser builds a long chain of one connective as a balanced tree, while a hand-built left-deep chain
of 48 is declined for its depth before its size is asked, which is how the test's first version
failed.

`VarkaRangeFilterBenchmark`, laptop, both widths (`VarkaRangeFilterBenchmark-jdk25-results.txt` and
its 128-bit companion). Nanoseconds a row, at the wide width:

| ranges | vanilla | Varka today | vanilla's method |
|---:|---:|---:|---:|
| 10 | 19.5 | 8.8 (kernel) | 1510 bytes |
| 48 | 28.0 | 14.0 (kernel) | 6906 bytes |
| 49 | 27.3 | 27.2 (declined) | 7048 bytes |
| 100 | 3675.6 | 3694.2 (declined) | 14299 bytes |
| 200 | 6695.8 | 6699.4 (declined) | 28699 bytes |

**The cliff is far steeper than the size ladder's.** Vanilla goes from 27.3 ns a row at 49 ranges
to 3675.6 at 100, about 135 times, where the ladder's projection steps about five times. The filter
is generated into the scan's `processNext`, so when that method is not compiled the whole scan loop
runs in the interpreter, not one method called from a compiled loop.

**Prediction 1 failed.** Vanilla's method grows about 145 bytes a range and passes 8000 near 55
ranges, not between 120 and 160. The prediction extrapolated from the census's 12167 bytes for
`modified-q3`'s stage (task 193), which is not this benchmark's stage: in Spark's TPC-DS schema
`store_sales` is partitioned by `ss_sold_date_sk`, the query's scan is planned against that, and
this benchmark filters a plain cached column, whose method is 28699 bytes at the same 200 ranges.
Why the two differ by that much was not examined.

**Varka's kernel is 2.2 times vanilla at 10 ranges and 2.0 times at 48** (8.8 against 19.5, 14.0
against 28.0); at 128 bits 1.9 and 1.5 times. Past 48 it declines and the two arms are the same
code.

**The benchmark also exposed a fault in its neighbours.** Its first run declined even 10 ranges:
the session's cache was Spark's default, not Arrow, and Varka's filter rule needs Arrow vectors.
The cause and its consequences for task 192 are in `PLAN_TASK_192.md` 9.4.

What remains: designs B and A, and the predictions they carry.

### Correction, 24 September 2026: the cliff is logged

This plan says Spark does not report a method past HotSpot's 8000-byte limit. It does: since 2.4.0
`CodeGenerator` logs "Generated method too long to be JIT compiled: <class>.<method> is N bytes" at
INFO, which a `spark-submit` job's log shows and `spark-shell`, at WARN, hides. Spark notices the
cliff, says so once at INFO, and runs the method uncompiled anyway. `PLAN_TASK_188.md` section 5
has the evidence.
