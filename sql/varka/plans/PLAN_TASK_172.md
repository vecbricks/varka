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

### 9.2 Design B, built, 24 September 2026

A new condition node, `InRanges(child, bounds)`, and a compiler case before the generic `Or`:
a disjunction of two or more ranges over one int or date column with literal bounds - written
as `>=`/`<=` in either operand order, as strict bounds, which move by one, or as equalities - is
one range set. The compiler sorts the ranges and merges those that overlap or touch, so one set
of rows is one shape however the query ordered it, and drops empty ones. Anything else compiles
as the comparisons it is written as.

**Three departures from 3.2, and why.**

* **A loop over the bounds, not a binary search.** The kernel holds each range set's bounds in a
  `private static final int[]` that the class initialiser fills, and the node emits one loop that
  ORs, range by range, the lanes with `lo <= v && v <= hi` into a mask, comparing against each
  bound as a scalar. Its code is the same size whatever the number of ranges, which is what
  removes the cliff; its time grows with them. A binary search needs a gather per step, and a
  gather of an `int[]` takes its indices from another array, spilled from the vector each step;
  the loop needs neither. The bounds are not literal slots: the literal table is keyed by value,
  so it cannot hold a contiguous block of bounds, and each slot is copied into a local at every
  method's entry, which at 400 bounds would have cost about 4 KB a method. The binary search stays
  possible as a variant to measure against the loop.
* **No emit option.** The baseline measured in 9.1 is the "off" arm, committed; design A brings
  the option when there is a second design to compare with.
* **Date columns too**, since a date range filter is the same shape on the same lane.

**What checks it.** `VarkaRangeSetCompilerSuite` pins the matching: sorting, merging, strict
bounds, equalities, operand order, empty ranges, the int extremes, date literals, and five shapes
that are not a set. `VarkaRangeSetSuite` runs such filters on the row engine and on Varka over the
same Arrow-cached rows, nulls and the int extremes included, and requires the same answer with the
kernel having run, `modified-q3`'s 200 ranges among them. `VarkaRangeFilterFusionSuite`, which
replaces the boundary suite of 9.1, pins that every number of ranges fuses and that the 200-range
kernel's methods stay under 2000 bytes. The IR fuzzer draws range sets, against a reference
evaluator written from the node's meaning, and the reach test holds it to that. The bytes oracle
moved in exactly the twelve keys that hash every fuzz shape and no coverage key.

### 9.3 Design B, measured, 24 September 2026

`VarkaRangeFilterBenchmark` again, on the laptop, both widths, with the range set: the files of 9.1
regenerated, so the vanilla arm is re-measured beside it. Nanoseconds a row at the wide width:

| ranges | vanilla | Varka, range set | vanilla / Varka |
|---:|---:|---:|---:|
| 10 | 19.3 | 8.9 | 2.2 |
| 48 | 28.0 | 13.1 | 2.1 |
| 49 | 26.9 | 12.6 | 2.1 |
| 100 | 3736.6 | 19.5 | 192 |
| 150 | 5425.0 | 27.8 | 195 |
| 200 | 6781.7 | 36.6 | 185 |

**Varka now takes the whole query's filter, and the cliff is gone from its side.** Where step 1's
Varka arm declined from 49 ranges and ran vanilla's code, it now runs its kernel at every rung, and
at the query's 200 ranges it is 185 times faster than vanilla. Below vanilla's crossing, where both
compile, it is about twice as fast, and the loop costs no more than the tree of comparisons it
replaced where both fit (13.1 against 9.1's 14.0 at 48 ranges). At 128 bits the ratio at 200
ranges is 102 (67.2 against 6834.5).

**Prediction 3 failed, as 9.2 expected.** The range set's time grows with the ranges, about 0.16 ns
a row a range at the wide width (12.6 at 49, 36.6 at 200), because the loop compares every lane
with every range. A binary search would grow with their logarithm; whether it is worth its gathers
is the variant 9.2 leaves open. At 36.6 ns a row for 200 ranges against vanilla's 6781.7, the loop
is not what limits the claim.

**Prediction 4 held for design B**: faster than vanilla at every rung, and by more than ten times
past vanilla's crossing. Design A is still to build and to score.

### 9.4 Design B on a runner, 24 September 2026

`VarkaRangeFilterBenchmark` through `.github/workflows/benchmark.yml` from master `efe3017de8a`,
which drew an AMD EPYC 9V74 - Zen 4, without the full-width 512-bit datapath
(`VarkaRangeFilterBenchmark-jdk25-runner-9v74-results.txt`, with its provenance). Nanoseconds a
row:

| ranges | vanilla | Varka, range set | vanilla / Varka |
|---:|---:|---:|---:|
| 10 | 35.2 | 13.7 | 2.6 |
| 48 | 52.5 | 24.3 | 2.2 |
| 100 | 9121.0 | 40.5 | 225 |
| 200 | 17520.9 | 70.2 | 250 |

**The laptop's shape holds on the runner, and the cliff is steeper there.** Vanilla steps from
51.4 ns a row at 49 ranges to 9121.0 at 100, about 180 times; Varka's loop grows with the ranges
as on the laptop, about 0.3 ns a row a range (24.2 at 49 ranges, 70.2 at 200). At the query's 200 ranges Varka is 250 times
faster. The published figure is to come from the EPYC 9V45, the pool's full-width machine, as the
size ladder's does.

Two more dispatches drew a second EPYC 9V74 and an Intel Xeon Platinum 8573C
(`-runner-9v74-2-results.txt`, `-runner-xeon8573c-results.txt`). At 200 ranges they read 14084.8
against 49.1 ns a row, 287 times, and 10269.4 against 50.7, 203 times: the ratio moves with the
machine's interpreter speed, and the shape does not move at all. More dispatches are out for the
9V45.

### Correction, 24 September 2026: the cliff is logged

This plan says Spark does not report a method past HotSpot's 8000-byte limit. It does: since 2.4.0
`CodeGenerator` logs "Generated method too long to be JIT compiled: <class>.<method> is N bytes" at
INFO, which a `spark-submit` job's log shows and `spark-shell`, at WARN, hides. Spark notices the
cliff, says so once at INFO, and runs the method uncompiled anyway. `PLAN_TASK_188.md` section 5
has the evidence.

### 9.5 The band, 25 September 2026

Ten runs of `VarkaRangeFilterBenchmark` on an unchanged file, on the quiet laptop, written to
`VarkaRangeFilterBenchmark-jdk25-band.txt`.

* **Design B's claim stands.** The Varka arm spreads by at most 12% (tier 2 at 100 ranges, tier 1
  at 150 and 200), which cannot touch a ratio of 185.
* **The claim of about twice as fast below the crossing stands too.** At 48 and 49 ranges both arms
  are tier 0 or 1 (at most 4%). At 10 ranges the vanilla arm is tier 2 (16%), so 9.3's 2.2 there is
  the run's figure and a band's worth of it is noise; the 48 and 49 rungs carry the claim.
* **The vanilla arm past the crossing reads tier 0 only because of the file's resolution.** It runs
  at one to three hundred thousand rows a second, which the Rate column prints as the same single
  digit every run; the band cannot see its spread, as 9.6 of `PLAN_TASK_192.md` says of the same
  column.

### 9.6 Design A, built, 25 September 2026

A filter predicate that one method cannot hold is split across several selection outputs of one
kernel, under a new option, `splitConditions`, off by default until measured. Beside it,
`rangeSets` switches design B off, on by default as it shipped, so that a disjunction of ranges
reaches design A as the comparisons it is written as. Both are compiler options: they live in
`VarkaEmitOptions` because that is what the compiler is handed, and they render into the shape
key only when set away from their defaults, so no existing key moves.

**How the split works.** The compiler folds the fused conjuncts into one condition root, as
before, and asks the emitter whether it fits. Only when it does not does the split start, and
it looks for the fewest outputs, since every extra one is another pass over its columns and
another bitmap. First the fewest equal contiguous conjunction roots the emitter accepts, where
the only root it may still name is a single conjunct that is a disjunction; then, for each such
root, the fewest equal partial disjunctions the emitter accepts beside everything else. Each
count is found by doubling until one is accepted and a binary search below it, and the emitter
judges the whole kernel every time. A root that cannot split - a conjunct that is not a
disjunction, or a disjunct too large alone - ends the split, and the compiler demotes a
conjunct as it does without the option. Each output
is a selection bitmap, and the predicate records its shape as clauses: a row is selected when
every clause has an output that selects it. The filter evaluator gives each output its own
bitmap, runs the kernel, ORs each clause's bitmaps into its first and ANDs the clauses into
output 0's. Kleene logic allows both at the mask: a row is known true for `a AND b` exactly when
it is known true for both, and for `a OR b` exactly when it is known true for either.

**Two departures from 3.1, and why.**

* **The partial masks are bitmaps the filter combines, not scratch vectors inside the kernel.**
  The emitter already splits a kernel's outputs across methods under the byte budget (task 87),
  and a condition output is already a bitmap, so a partial root as an output of its own needs
  no emitter change at all. What it costs is one bit a row per output, written by the kernel and
  read once by the combine, eight bytes at a time. Sharing one bitmap between the outputs, which
  would have saved the combine, does not work: under `validityByWord` a loop stores each
  validity word whole, without reading it, so two outputs writing one bitmap overwrite each
  other.
* **The emitter decides the split, not an estimate, and the split searches for the fewest
  outputs.** Two versions came first. The first packed conjuncts greedily into roots that each
  fitted in a kernel of their own. On 300 conjuncts every root fitted alone and the kernel of all
  four still failed: root 1's loop method was 8216 bytes beside the others, because a method in a
  kernel of several outputs is not byte for byte what it is alone, and it asked the emitter once
  per conjunct. That is the failure `READING_MILESTONE_6.md` records from TENSAT's section 5.1 -
  a greedy pass that costs each piece alone misjudges the whole - which the read had already
  drawn for `groupOutputs` (task 200) and which should have been read before this was written.
  The second halved whatever the emitter named, which fits by construction but only makes
  pieces of halving sizes. Measured on the same shapes, 25 September 2026, outputs of the split
  kernel, every conjunct fused in both:

  | predicate | halving | search on counts |
  |---|---:|---:|
  | `s >= 0` and 49 of the ranges | 1 + 2 | 1 + 2 |
  | and 100 | 1 + 4 | 1 + 3 |
  | and 150 | 1 + 4 | 1 + 4 |
  | and 200 | 1 + 8 | 1 + 5 |
  | a disjunction of 150 over two columns | 4 | 4 |
  | a conjunction of 300 comparisons | 4 | 4 |

  The search asks the emitter a few times more and compiles in the same tenth of a second. It
  is exact among splits into equal pieces, not over every partition; the exact partition is
  task 200's dynamic program, which needs a cost cheaper to ask than an emission (task 199), and
  design A's split is a second place it would serve. A first search went straight to its upper
  count and read the refusal there as "nothing fits": past some number of outputs the driver
  method itself is over the budget, so acceptance is monotone in the count only up to a point,
  and the search now doubles up to the answer instead.

**What checks it.** `VarkaSplitConditionFusionSuite` pins, with range sets off, that without the
split the comparisons still fuse up to 48 ranges and decline from 49; that a predicate one
method holds compiles exactly as it does without the option; that `modified-q3`'s ranges at 49,
100 and 200 split into the clause of `s >= 0` and a clause of partial disjunctions, with every
method of the kernel within 8000 bytes; that a disjunction over two columns and a conjunction of
300 comparisons split the same way; that a conjunct too large alone which is not a disjunction
still declines with the method-budget reason; and that the fusion report says when the predicate
is split and only then. `VarkaSplitConditionSuite` runs the same shapes, and one with conjunction
roots beside a split disjunction at the int extremes, on the row engine and on Varka over the same
Arrow-cached rows with nulls, and requires the same rows with the kernel having run.

The IR fuzzer does not draw the split: it lives in the compiler and the evaluator, above the IR,
and the fuzzer draws IR. The end-to-end suite is its differential check.

**The benchmark** gains the arm "Varka, split conditions" beside "Varka", whose name stays so that
its committed rows and its band keep their key.

What remains: the measurement, on the quiet laptop and on a runner, and the scoring of
predictions 2 and 4 for design A, with the recommendation 8 asks for.

### 9.7 Design A, measured, 25 September 2026

`VarkaRangeFilterBenchmark` regenerated on the quiet laptop at both widths with the arm
"Varka, split conditions", then three more wide runs, committed together in
`VarkaRangeFilterBenchmark-jdk25-repeats-results.txt`, so that the two designs, whose ratios are
all under 1.3, are compared by minimums as the house rule asks. Nanoseconds a row at the wide
width, each case's minimum over the four runs:

| ranges | vanilla | Varka, range set (B) | Varka, split conditions (A) |
|---:|---:|---:|---:|
| 10 | 19.0 | 8.7 | 7.7 |
| 48 | 26.9 | 12.3 | 12.0 |
| 49 | 26.5 | 12.2 | 11.8 |
| 100 | 3619.9 | 19.0 | 17.7 |
| 150 | 5485.6 | 27.1 | 24.4 |
| 200 | 6748.2 | 34.1 | 31.0 |

**Prediction 2 held.** Design A fuses all 200 ranges with every method within 8000 bytes (9.6's
test reads them from the built class), and its time grows about linearly with the ranges, since
every lane still evaluates every comparison.

**Prediction 4 held for design A**: faster than vanilla at every rung, about twice as fast below
vanilla's crossing and more than two hundred times past it at the wide width.

**A is faster than B, by a little.** By minimums A is 2 to 11% faster at every rung, and at most
rungs all four of its runs are below B's best. At 128 bits the regenerated file puts A ahead by
more, 53.0 against 63.2 at 200 ranges, from one run only. That B's loop over a table of bounds
loses to A's unrolled comparisons is no surprise in hindsight: A compares against constants in
registers and B broadcasts each bound from memory, and both do one comparison pair per range per
lane.

**One thing the best times hide.** The split arm's average is far above its best at some rungs of
the regenerated file (73 ms best against 943 average at 200 ranges): one slow iteration, most
likely C2 still compiling the split kernel, whose five partial disjunctions of 40 ranges each
are about the size section 2's table gives for 40 ranges, while the benchmark had started
timing. B's kernel is one method under 2000 bytes. So A's steady state is the faster and
its first queries may be the slower; that is task 195's question, what Varka costs on the first
query, and it is not measured here.

**The recommendation 8 asks for.** Turn `splitConditions` on by default: it changes only
predicates that are declined today, it is general, and it is the fastest arm measured. Keep
`rangeSets` on for now: on performance A would replace it, but B is one small method where A is
several of several thousand bytes, and whether that costs A on the first query is unmeasured. Retiring B is
a decision for after task 195. The runner figures for both designs follow.

### Correction, 25 September 2026: why A is faster, read from the assembly

9.7 explains A's lead as constants in registers against bounds broadcast from memory. The
assembly says that is half of it, and that the other half is a dependency chain. Both kernels
were compiled from the same 40 of the query's ranges - the size of each of A's partial
disjunctions at 200 ranges - as `if(<ranges>, d, date_add(d, 1))` over a date column, the one
value position `dev/varka_emit.sh` can take a condition in, and read with `--asm` from C2's
standard compilation of `loopDense0` (`--options rangeSets=true`, then `false`):

* **B** loops over its table of bounds, and C2 unrolls that loop four times, eight bounds an
  iteration. Every bound is loaded from the table and broadcast afresh for every group of lanes,
  two instructions a bound, because a bound read inside a loop cannot be hoisted out of the lane
  loop around it. The mask that accumulates the ranges is carried from one iteration of the range
  loop to the next through the stack - `kmovq %k7, 8(%rsp)` at its end, `kmovq 8(%rsp), %k4` at
  the start of the next - so every four ranges the chain waits on a store and the load that
  reads it back.
* **A** is straight-line code: 80 `vpcmpnltd`/`vpcmpled`, two a range, and no loop over ranges.
  C2 keeps many of the 80 broadcast bounds in vector registers across the lane loop and
  broadcasts the rest from the stack. Every comparison's mask is spilled, since only seven mask
  registers are usable, but the disjunction is a balanced tree, so no mask waits on the one
  before it.

So both spill masks and both broadcast some bounds every group of lanes; what separates them is
that B broadcasts every bound every time and serialises its ranges through one accumulator, and
A does neither. Two things follow for B, neither done here. It could keep several accumulators
and OR them at the end, which breaks the chain without changing its code size. And both designs
spend two comparisons and an AND on `lo <= v && v <= hi`, where `(v - lo)` compared unsigned
against `(hi - lo)` is one subtraction and one comparison, since a value below `lo` wraps to a
large unsigned number.

`VarkaEmitDump` compiled with the default options whatever `--options` said, so a compiler option
such as `rangeSets` could not be probed with it; it now compiles with the options it emits with.
