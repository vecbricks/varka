# VARKA-142: what a 64-bit lane costs

*Milestone 5, section 2.77. Opened and measured 17 September 2026, in an idle
machine window, from VARKA-85's own finding.*

## 1. Where this came from

VARKA-85 gave the emitter a second lane, and nothing prices it. The milestone's
remaining tasks are all built on that lane - VARKA-104's `bigint` arithmetic,
VARKA-102's `TIME` expressions, VARKA-29's widened loads and stores - and every
one of them will be judged by a ratio against the row engine. A ratio has two
factors, and if the lane's own cost is not known separately then a disappointing
`TIME` number cannot be told apart from a lane that is simply twice as wide.

VARKA-29's section 2.3 already names "the halved headroom" as something the lane
owns, and VARKA-104's admission rule asks for "the halved-headroom number
committed per shape, not discovered". This is that number, committed before the
compiler arms that will be measured against it - the project's rule that a
baseline lands as its own change, ahead of the work it exists to judge.

## 2. The admission check, done

**Nothing else measures it.** `VarkaArithmeticBenchmark` drives emitted kernels
straight over memory segments, which is the right harness, but every shape in it
is int32; the surface and chains benchmarks measure SQL against the row engine,
where the lane is one term among many. A new file rather than rows in an
existing one, because this is a feature family of its own.

**It can be measured today.** No compiler arm admits a `LongType` column, so no
SQL reaches the long lane - but the emitter does, through the same
`VarkaLoopEmitter.emit` the int benchmark uses. So the lane can be priced before
any of the SQL that will use it exists, which is the whole point of doing it
first.

## 3. The design

The same eight shapes at both lanes, emitted twice and driven over buffers of
the matching width: a copy, a literal add, a column add, a checked add, a
multiply, `greatest`, a comparison as a selection, and a `CASE`. The int arm and
the long arm differ in the lane and nothing else - same rows, same null-free
inputs, same driver.

**Read against 2.** A 512-bit register holds sixteen int lanes and eight long
ones, so the same row count is twice the lane groups; it is also twice the
bytes. Compute-bound or memory-bound, the wider lane should cost about twice as
much per row, and a shape costing less than twice would mean neither bound was
reached.

**A ladder, not a row count.** Doubling the width doubles the working set, so at
some sizes the two arms are not measured in the same place: the int arm still
fits a cache level the long arm has just left. The rungs bracket that boundary -
16 384 rows (192 KB and 384 KB, both in L2), 262 144 (3 MB and 6 MB, both in
L3), 1 000 000 (12 MB and 24 MB, the split against this machine's 24 MB L3) and
8 388 608 (96 MB and 192 MB, both past it). Each rung owns its arena and closes
it, so a rung never measures a cache an earlier one left warm.

## 4. Files

* `sql/catalyst/src/test/scala/org/apache/spark/sql/VarkaLongLaneBenchmark.scala`
  and `sql/catalyst/benchmarks/VarkaLongLaneBenchmark-jdk25-results.txt` - new.
* `sql/varka/plans/m5/PLAN.md` - section 2.77 and row 142.
* `SKILLS.md` - the ladder lesson from section 9.2.

## 5. Tests, and what each is for

None of its own: a measurement. Its correctness guard is the `status == 0`
assertion on every call, which fails the run if a kernel declines a batch - the
checked-add shape would do exactly that if the input values were chosen wrong.

## 6. The measurement

### 6.1 Predictions, registered before the first run

1. Every shape lands near 2x, within about 20 per cent either way.
2. The copy is the closest to 2, being purely memory-bound.
3. The checked add is the furthest above it, since its overflow test is extra
   work per lane group and there are twice as many groups.

## 7. Risks

The one that materialised is in section 9.1. The others: a lane count this host
does not have (`SPECIES_PREFERRED` answers for both arms, so both are measured
at whatever this machine is); and a shape whose long form is not emitted at all,
which the `status == 0` assertion and the emitter's own refusals catch.

## 8. Sequencing

After VARKA-85's step 4, since it needs the long lane to exist. Before VARKA-104,
which is the first change this baseline judges.

## 9. Outcome

**The long lane costs what its width says, and the first run said otherwise.**

Ratios of int32 rate to int64 rate, by rung:

| shape | 16K, both L2 | 256K, both L3 | 1M, split | 8.4M, both DRAM |
|---|---|---|---|---|
| a column, copied | 1.61 | 1.76 | 2.94 | 2.17 |
| column + literal, wrapping | 1.53 | 1.77 | 3.01 | 2.16 |
| column + column, wrapping | 1.85 | 1.98 | 3.60 | 2.11 |
| column + literal, ANSI checked | 2.02 | 1.80 | 3.17 | 2.16 |
| column * column, wrapping | 1.89 | 1.64 | 3.80 | 2.11 |
| greatest(column, column) | 1.92 | 1.64 | 3.58 | 2.11 |
| a comparison, as a selection | 1.66 | 1.89 | 2.85 | 2.20 |
| CASE WHEN c < c2 THEN c ELSE literal END | 1.71 | 1.95 | 3.55 | 2.13 |

Wherever both arms sit in the same level of the hierarchy the answer is the
width and nothing else: 1.5 to 2.0 in cache, and a flat 2.11 to 2.20 out of it,
where the ratio is bytes moved and the lane has nowhere to hide. The in-cache
figures below 2 are the interesting half - a long lane group does the work of
two int groups with one set of loop overheads, and in L2 there is issue slack to
absorb the difference, so the copy and the literal add come in at about 1.6.

The 1M rung is the one to read as a warning rather than a result: 2.85 to 3.80,
because an int working set of 12 MB fits this machine's 24 MB L3 and the long
one at 24 MB does not. Nothing about the lane changes between that rung and its
neighbours.

### 9.1 The predictions, scored

1. **Wrong at first, right once the rungs were separated.** The first run was a
   single rung at one million rows and read 2.74 to 3.74 - outside the predicted
   band on every shape, and the conclusion it invited, that a 64-bit lane costs
   three to four times an int one, is false.
2. **Wrong.** The copy is the *furthest below* 2 in cache (1.61 at L2), not the
   closest to it. Being memory-bound is what puts a shape at 2 in DRAM; in cache
   it is what leaves the most issue slack.
3. **Wrong.** The checked add is at 2.02 in L2 and 1.80 in L3, in the middle of
   the spread, not above it. The overflow test is a compare and a blend over a
   register that is already loaded, which is cheap next to the extra loop group.

### 9.2 The lesson, for the next benchmark that doubles a width

A benchmark that changes the bytes per row changes the working set, and a single
row count then measures the cache hierarchy as much as the change. Pick the
rungs from the machine's cache sizes and the arms' working sets so that at least
one rung has both arms on the same side of every boundary, or the number is not
about what its name says. This is the same discipline the partitions ladder
(`VARKA-134.md`) applies to cores, one level down.

### 9.3 What this changes for the milestone

VARKA-104 and VARKA-102 now have a floor to be judged against: a long-lane shape
that prices at about half its int twin is at the lane's cost and has no further
problem to find. A shape materially worse than half is the one to investigate,
and the number to compare against is this file's same-level rung, not the 1M
one.
