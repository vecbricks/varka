# VARKA-172: one realistic query

## 1. Where this came from

`m6/PLAN.md` 2.6 asks for one query a reader recognises, where vanilla
Spark falls off the method-size cliff and Varka does not, with both arms
measured. VARKA-193's census of every whole-stage stage of TPC-DS and TPC-H
(`VARKA-193.md` 9.1 and 9.4) found exactly one such stage in 178 queries:
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

The decline is the byte budget working as designed (VARKA-87 and VARKA-169): the
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
masks with the connective in the root. It is VARKA-87's per-group split applied
inside one condition rather than across outputs, and it serves any large
predicate, not only ranges: a long `IN`, a deep `CASE WHEN` condition, several
hundred comparisons from generated SQL.

What it costs: each group's partial mask is written and read once, a memory
pass per group. Where the partial masks live is the same question VARKA-198
asks for a shared prefix (a scratch vector per batch), and the answer should be
the same one.

What it changes: the plan-time admission (VARKA-169) must accept a condition
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
| `m6/PLAN.md` | row 172 |

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

**A correction to the milestone's wording.** Criterion 3 of `m6/PLAN.md`
1.3 asks for a query where Spark logs "the whole-stage codegen was disabled".
Spark logs that only past `spark.sql.codegen.hugeMethodLimit`, 65535 by
default, or past `spark.sql.codegen.maxFields`; the cliff this milestone is
about, at 8000 bytes, logs nothing, which is the point of VARKA-192's finding.
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
`modified-q3`'s stage (VARKA-193), which is not this benchmark's stage: in Spark's TPC-DS schema
`store_sales` is partitioned by `ss_sold_date_sk`, the query's scan is planned against that, and
this benchmark filters a plain cached column, whose method is 28699 bytes at the same 200 ranges.
Why the two differ by that much was not examined.

**Varka's kernel is 2.2 times vanilla at 10 ranges and 2.0 times at 48** (8.8 against 19.5, 14.0
against 28.0); at 128 bits 1.9 and 1.5 times. Past 48 it declines and the two arms are the same
code.

**The benchmark also exposed a fault in its neighbours.** Its first run declined even 10 ranges:
the session's cache was Spark's default, not Arrow, and Varka's filter rule needs Arrow vectors.
The cause and its consequences for VARKA-192 are in `VARKA-192.md` 9.4.

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
cliff, says so once at INFO, and runs the method uncompiled anyway. `VARKA-188.md` section 5
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
  digit every run; the band cannot see its spread, as 9.6 of `VARKA-192.md` says of the same
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
  The emitter already splits a kernel's outputs across methods under the byte budget (VARKA-87),
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
  per conjunct. That is the failure `m6/READING.md` records from TENSAT's section 5.1 -
  a greedy pass that costs each piece alone misjudges the whole - which the read had already
  drawn for `groupOutputs` (VARKA-200) and which should have been read before this was written.
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
  VARKA-200's dynamic program, which needs a cost cheaper to ask than an emission (VARKA-199), and
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
its first queries may be the slower; that is VARKA-195's question, what Varka costs on the first
query, and it is not measured here.

**The recommendation 8 asks for.** Turn `splitConditions` on by default: it changes only
predicates that are declined today, it is general, and it is the fastest arm measured. Keep
`rangeSets` on for now: on performance A would replace it, but B is one small method where A is
several of several thousand bytes, and whether that costs A on the first query is unmeasured. Retiring B is
a decision for after VARKA-195. The runner figures for both designs follow.

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

### 9.8 Design A on runners, 25 September 2026

Four runs of `VarkaRangeFilterBenchmark` from the branch at `c1b5237740b` on GitHub-hosted
runners drew three AMD EPYC 7763s and one EPYC 9V74 - again no 9V45, which makes it 0 of 15
tries. Two are committed as their workflows' artifacts, `-runner-9v74-3` and `-runner-7763`,
with provenance; the other two 7763s agree with the committed one within a few percent at every
rung. Nanoseconds a row:

| ranges | 9V74: vanilla | B | A | 7763: vanilla | B | A |
|---:|---:|---:|---:|---:|---:|---:|
| 10 | 35.7 | 12.7 | 11.7 | 36.2 | 12.5 | 11.3 |
| 49 | 50.1 | 22.7 | 22.5 | 46.7 | 21.2 | 21.2 |
| 100 | 9288.7 | 38.4 | 37.9 | 10394.1 | 36.2 | 36.1 |
| 200 | 17773.9 | 69.6 | 69.7 | 18345.2 | 65.4 | 66.6 |

**On these machines the designs tie.** Past ten ranges A and B are within 6% of each other on
all four runs, either way round; at ten, A is ahead by several percent on every run.
Both are about 230 to 295 times vanilla past its crossing, so prediction 4 holds on runners too.

**Why the laptop's lead does not travel, as far as the evidence goes.** Neither runner CPU exposes
AVX-512 (`sql/varka/HARDWARE.md`), so C2 compiles both kernels to 256-bit AVX2, where a comparison
yields a vector rather than a mask register. The laptop's assembly put B's loss on mask registers:
the accumulator spilled through the stack between iterations of the range loop, because only
seven are usable. With masks in sixteen vector registers that spill need not happen. This is the
likely reading, not a read one: the runners' assembly was not captured.

**The recommendation of 9.7 stands, and B's case for staying is stronger.** A is general and never
slower than B by more than noise; B ties it on the pool's common machines with one small method
where A has several. `splitConditions` on by default, `rangeSets` kept, rows 207 and 208 for B's
loop.

### 9.9 The recommendation applied, 25 September 2026

The owner agreed to 9.7's recommendation as 9.8 left it. `splitConditions` is on by default: a
filter predicate that one method cannot hold is now split across several selection outputs
rather than declined, wherever it can be. `rangeSets` stays on, so `modified-q3`'s ranges still
compile to one range set, and design A serves every other predicate too large for one method.
The shape key renders `splitConditions` only when it is off, so default keys and every earlier
variant's keep their rendering. Rows 207 and 208 of `m6/PLAN.md` carry the two
improvements to B's loop that the assembly suggested.

What is left of this task is the 9V45 figure for the post, and VARKA-195's first-query cost,
which decides whether B stays.

### 9.10 The 9V45 figure, 28 September 2026

Ten dispatches of `benchmark.yml` from master `1f68b5e5de9` with `expected-cpu` set to
`AMD EPYC 9V45`, so that a run on any other CPU stops at the workflow's CPU check before the
build. One drew the 9V45 (`VarkaRangeFilterBenchmark-jdk25-runner-9v45-results.txt`, with its
provenance); the other nine drew seven EPYC 7763s and two Xeons. Nanoseconds a row, with both
Varka arms at their defaults of today - B is the range set, A the split conditions:

| ranges | vanilla | B | A | vanilla / B | vanilla / A |
|---:|---:|---:|---:|---:|---:|
| 10 | 23.9 | 8.6 | 7.1 | 2.8 | 3.4 |
| 49 | 28.1 | 11.2 | 10.6 | 2.5 | 2.7 |
| 100 | 4490.3 | 18.1 | 18.6 | 248 | 241 |
| 200 | 8426.4 | 34.3 | 33.0 | 246 | 255 |

**The shape is the other runners' and the ratio is the 9V74's.** Vanilla steps about 160 times
between 49 and 100 ranges, and at the query's 200 ranges Varka is 246 to 255 times faster,
against 250 on the first 9V74 (9.4). Both sides are about twice as fast as on the 9V74 - 8426.4
against 17773.9 ns a row for vanilla, 34.3 against 69.6 for B (9.8) - so the ratio the post
quotes does not depend on which of the two machines it comes from. The A and B arms are within
5% of each other past ten ranges except at 150, where A is 10% ahead (24.9 against 27.6).

**The averages of arm A are not its cost.** Its average is far above its best at 48, 100, 150
and 200 ranges (1362 against 66 ms at 200, a standard deviation of 2170 ms): one slow iteration
a case. Every committed file of this benchmark shows the same, the laptop's and the two earlier
runners' included, so it is not the 9V45's; this task reads best times, and the figure does too.

The 9V45 figure the post needs is this file. What is left of this task is the decision on B
that VARKA-195's first-query cost was to inform.

### 9.11 The first query of the two designs, planned, 28 September 2026

The decision 9.9 left open - whether design B, the range set, stays beside design A, the
split conditions, which is faster or equal at steady state - was to be informed by VARKA-195's
first-query cost. VARKA-195's benchmark prices the ladder's projections, not this filter, so the
number does not exist. `VarkaColdStartBenchmark` gains a section for it: the same cases as its
projection section (plan only, first run, second run; and once compiled on the warm-up arm) for
each design on each Varka arm, at 48 ranges and at the query's 200, over its hundred thousand
rows, with every bound shifted by a day per iteration so no iteration's source is one Janino
has compiled. Vanilla's arm is the baseline, as in the range filter benchmark.

**Predictions, registered before the run.**

1. *Plan only* holds the emission. A emits several methods of several thousand bytes where B
   emits one under 2000, so A's plan-only case is slower than B's at 200 ranges, by more than
   the noise; both are tens of milliseconds.
2. *First run without the warm-up*: both designs' kernels run interpreted until compiled, and A
   has more code to interpret and more methods for C2 to compile, so A's first run is slower
   than B's at 200 ranges. At 48 ranges the two are within the noise.
3. *With the warm-up*, A's verdict comes later than B's at 200 ranges (more methods to compile),
   and its once-compiled case equals B's within the noise, as the steady state does.
4. *Vanilla* at 200 ranges pays a Janino compile of a method past 8000 bytes and then runs it
   interpreted, so its first run is slower than either design's, and its second run is not
   faster than its first by much: it never leaves the interpreter.

**The rule for the decision.** If A's first-run and plan-only costs at 200 ranges are within
about a fifth of B's on the runner's file, B has no case left and goes, with rows 207 and 208;
if A pays clearly more, both stay and the post says when each applies. The figure the post
quotes comes from a GitHub runner, as every headline number does; the laptop's run checks the
benchmark and gives the first reading.

### 9.12 The first query of the two designs, measured: B stays, 28 September 2026

The runner's run of `VarkaColdStartBenchmark` with the section 9.11 added, on an
EPYC 9V74 with four cores (`VarkaColdStartBenchmark-jdk25-runner-9v74-results.txt`,
with its provenance), at 48 and 200 ranges over a hundred thousand rows, best of
five iterations. Milliseconds a query; B is the range set, A the split conditions:

| case | 48: vanilla | B | A | 200: vanilla | B | A |
|:--|--:|--:|--:|--:|--:|--:|
| plan only | 13 | 12 | 14 | 27 | 28 | 61 |
| first run | 175 | 73 | 110 | 1748 | 142 | 420 |
| second run | 41 | 24 | 97 | 1634 | 52 | 350 |
| with the warm-up, first run | | 418 | 159 | | 302 | 1503 |
| with the warm-up, second run | | 283 | 148 | | 55 | 1005 |
| once compiled | | 23 | 24 | | 53 | 55 |
| the warm-up's verdict | | 0.72-0.86 s | 0.62-0.66 s | | 0.10-0.13 s | 3.1-3.6 s |

**Prediction by prediction (9.11).**

1. **Held at 200, within the noise at 48.** Plan only is 61 against 28 ms at
   200 ranges, 2.2 times: A's emission of several methods against B's one loop
   over a table. At 48 ranges, 14 against 12.
2. **Held, and at 48 ranges too.** A's first run without the warm-up is 420
   against 142 ms at 200 ranges and 110 against 73 at 48, where the prediction
   had the two within the noise. And A's second run is 350 ms at 200 ranges
   against B's 52: A's methods are not compiled by the second query either,
   where B's loop is.
3. **Held.** The warm-up's verdict on A takes 3.1 to 3.6 seconds at 200 ranges
   against 0.10 to 0.13 on B, some thirty times; once compiled the two are 55
   and 53 ms, equal as at steady state. At 48 ranges the verdicts are alike.
4. **Held.** Vanilla at 200 ranges takes 1748 ms on the first run and 1634 on
   the second: its method is past 8000 bytes and never leaves the interpreter,
   so it is slower than either design on every run.

**The decision.** By 9.11's rule A would have to be within about a fifth of B
on the first run and plan only at 200 ranges; it is three times B on the one
and twice on the other. **B stays**, as 9.9 configured it: `rangeSets` on, so
a disjunction of ranges over one column compiles to the range set, and
`splitConditions` on for every other predicate too large for one method, where
A is the only design there is. Rows 207 and 208, B's loop improvements, keep
their point. The post says which shape each serves.

**A finding for the warm-up (rows 212 and 213, VARKA-221).** With the warm-up
on, which is the default, A's first two queries at 200 ranges take 1503 and
1005 ms, against 420 and 350 without it: while the warm-up compiles A's methods
for three seconds the queries run on the row path, and the row path over two
hundred ranges is the interpreted stage vanilla runs, about 1.7 seconds a
query. For a kernel whose verdict is slow and whose row path is this slow, the
warm-up costs more than it saves; B, whose verdict comes in a tenth of a
second, shows the policy at its best (302 then 55 ms). The verdict's cost
against the row path's is a per-shape question the warm-up does not ask today.

The laptop's regeneration of the committed file, with this section, is queued
for the night of 28 September and lands beside this run's file; the decision
is read from the runner's, as every headline number is.

**The laptop's companion files**, regenerated on the night of 28 to 29 September 2026 with the
section in (`VarkaColdStartBenchmark-jdk25-results.txt`, its 128-bit companion and
provenance), read the same ordering at 200 ranges: plan only 25 against 43 ms, first run 107
against 285, second run 41 against 241, and with the warm-up 171 and 47 against 535 and 406.
The rows the section did not touch moved within the band, the sub-second ones by a few
milliseconds either way.

### 9.13 Corrections from the review of the pull request, 29 September 2026

* **What design A is at 48 ranges.** The split begins at 49 ranges (section 3.1's table: 48
  fit one method, 49 would be 8142 bytes), so at the 48 rung the "split conditions" arm is the
  plain comparison tree in one method, the range set merely off. 9.12's prediction 2 stands at
  200 ranges, where A splits; at 48 its 110 against 73 ms is the one-method tree against the
  range set's loop, not a split kernel, and the table's "48: A" column reads that way. The
  smoke run takes 49 now, so it reaches a split emission.
* **The designs are emitted exclusively.** B was the defaults, which have the split on too, so
  a B that failed to lower to the range set would have run as A without a word; B is now the
  defaults with the split off, and a design that does not lower declines, which the rung's
  check catches. B's kernel is the same either way, so the committed numbers stand.
* **The section runs second** in the file, after the projection's first-query section and
  before the back-to-back and steady-state sections, which start from the JVM it leaves; the
  class doc said fourth. The plan's note that the untouched rows moved within the band is the
  measure of that.
* **The warm-up finding has a row**: `m8/SCOPE.md` item 60, the verdict's cost against
  the row path's as a per-shape question.
* The two benchmarks over the range keys share their helpers through `VarkaArrowSessions`, and
  this benchmark's two first-query sections register their cases through one helper.
