# VARKA-105: the `TIME` surface benchmark

*Opened 20 September 2026 from `m5/PLAN.md` 2.40. The number the
milestone's message quotes comes from this file's descendants, so the plan is
written before the first run and the predictions before the first number.*

## 1. Where this sits

The date surface (`VARKA-62.md`) is the shape: a `Surface`-shaped inventory,
one entry per expression in the spelling a reader would write, the projection
and the filter form of each, run by one driver over one cached table through
`dev/varka_bench_surface.sh` against four arms - stock 4.2.0 on JDK 17 and 25,
the fork with the engine off, the fork with the engine on - with the canary, the
datapath probe, the residency guard, the fixed-share rule and the `EXPLAIN`
check applying to every file written. VARKA-105 adds the `TIME` family to that
machinery without changing what the machinery checks.

What it produces is the input to two things after it: VARKA-101's band for the
`TIME` surface, which the message needs before any per-entry figure is quoted,
and VARKA-118, which takes the surface to a runner the datapath probe proves
full-width and writes the README's `TIME` table from it.

## 2. What is built

**The table, `varka_times`.** Two `TIME(6)` columns, two day-time intervals and
two `bigint`s - one 64-bit lane each, which is the milestone's point - generated
from `range` the way `varka_dates` is, with a fixed null pattern per column:
every 31st `t` null like `d`, every 47th `dt`, every 53rd `l2`. `t` and `t2` are
spread over the whole day by coprime strides through `make_time`; `dt` is under
a minute and runs forward before noon and backward after it, so `t + dt` stays
inside the day on every row - a crossing row is Spark's own error, Varka's guard
declines the batch, and a surface row is meant to time the kernel and not the
decline (`VARKA-102.md` 7.1). `dt2` is sub-second; `l` and `l2` are counts
under ten thousand million so the comparisons against `5000000000` select about
half.

The type sits behind `spark.sql.timeType.enabled`, internal and off outside
tests in every distribution the surface runs against. The driver sets it on the
session when it builds this table, so no arm can forget it: a forgotten flag
would fail the stock arm alone, at parse time, after the canary and the build.

**The inventory, `Times`.** Twenty-three entries over the coverage table's own
shapes, since the coverage suite already proves those fuse and the first full
run should be a measurement rather than a search for the entry that does not:
the three field extracts as projections; `time_trunc` at two levels; `t - t2`,
`time_diff` at two units; `t + dt`; the choice family over `TIME`, intervals
and `bigint`; and eight predicates on their own. The extracts have no filter
form on purpose: `hour(t) = 12` puts the narrowed int under a comparison, which
the kernel cannot hold until VARKA-28, so the compiler declines it and its row
would time the row engine (`VARKA-102.md` 8.6).

**The driver.** `DateSurfaceBenchmark.run` takes the table its entries read as a
`TableShape` (`ALL`, `DATES`, and now `TIMES`), which names the view, the
columns and the column the filter shape's columnar consumer selects; the
query builders and the checksum take the shape. `TimeSurfaceBenchmark` is the
six-line entry point, as `DateChainBenchmark` is. `--benchmark time` selects it
in the shell driver, the merge tool knows the `TimeSurface` stem, and the
workflow offers the choice.

**The tests.** `TimesTest` runs every entry in both shapes on the stock release
the module compiles against, over a thousand rows, which is also the check that
the type and every function the list uses exist in the release the stock arm
downloads; asserts the table's null counts, the spread of `t` over all 24
hours, that `t + dt` exists on every row and that `dt`'s sign follows `t`'s half
of the day; that the entries read the times table and are refused the date one;
and that no label is shared with the date surface, so a merge cannot confuse
their files.

## 3. What is not built here

The results files. Two things stand in front of them. The extracts fuse only
with #268 (VARKA-102 group C) merged, and every entry is held to
`--expect-fused`; a run before that fails on `hour(t)`, correctly. And the row
count is a measurement, not a choice: the date surface settled on 1e9 rows in
32g because the fixed-share rule (under 5% of wall time per Varka row) pushed
it up and the residency guard bounded it above. This table's rows are 8 bytes a
column where the date table's are 4, so the same memory holds half the rows,
and the extracts' kernels are divide-bound rather than bandwidth-bound, so they
may clear the rule at fewer rows. Section 5 says how the number is found.

## 4. Predictions, registered before the first run

1. **The allocation rows read a larger ratio against stock than the date
   surface's median.** `hour`, `minute`, `second` and both `time_trunc` rows
   against stock 4.2.0 on JDK 25 read above the date surface's committed median
   of 19.5x (README, the surface's 45 projection rows), because the stock arm
   builds a `LocalTime` per row where the date arm did integer arithmetic.
2. **Most of that difference is the baseline, not the lane.** The same rows
   against the fork with the engine off - which runs the same `LocalTime` path
   - read within 20% of their stock ratio, as the date surface's engine-off
   control tracked stock (49 of 50 pairs within 20%, `VARKA-62.md`); and the
   arithmetic rows (`t - t2`, `time_diff`, `t + dt`, the predicates) read in the
   date surface's ordinary class, near its 10x, because stock is integer
   arithmetic there too.
3. **The row count that clears the fixed-share rule is between 2.5e8 and 5e8**
   in a 32g driver, and the cache is resident there: about 48 bytes a row
   uncompressed against the date table's 23 GiB at 1e9 rows.
4. **The filter rows split as the date surface's did**: the columnar-consumer
   filters win by less than their projections, and the counted filters least,
   since a count is one aggregate over a mask and the row engine's cost per row
   is smallest there.
5. **No entry declines.** Every entry fuses on the fork with `--expect-fused`
   and zero fallback batches, at the first run after #268 merges.

## 5. Sequencing

1. This PR: the table, the inventory, the driver's shape parameter, the entry
   point, the selectors, the tests, this plan. No numbers.
2. After #268 merges: a smoke run of the fork arm alone at a small row count
   with `--expect-fused`, which is prediction 5's test and costs minutes.
3. The row-count ladder on the fork arm, 2.5e8 and 5e8 rows in 32g, reading the
   fixed share and the residency line; the smallest count that clears both is
   the committed one (prediction 3).
4. The four arms at that count on the laptop, committed as
   `TimeSurface-<label>-results.txt` with provenance, and the diff table read
   against predictions 1, 2 and 4.
5. The band (VARKA-101): twelve runs of the fork arm under a scratch label, by
   shards if one arm takes over twenty minutes, written as
   `TimeSurface-jdk25-band.txt`; no per-entry figure is quoted before it exists.
6. VARKA-118 takes the file to the full-width runner and writes the README.

## 6. The four arms, 21 September 2026

Measured on the laptop at 500000000 rows in a 48g driver, one partition, one
core, with the fixed-share bound lifted from 5% to 6% for one row. Section 5's
ladder found why: the times table is 45.6 bytes a row, so 250000000 rows fit a
32g driver but left the fastest projections over the 5% rule (`hour(t)` at
9.8%), and 500000000 rows are 22.8 GiB, which a 32g driver cannot keep
resident and a 48g one can. At that setting every row is under 5% except
`hour(t)` at 5.9%, which understates that row's Varka rate by at most a
twentieth and makes its ratio conservative. Wall-time rates in M rows/s, from
the four committed `TimeSurface-*-results.txt` files; the ratio is against
stock 4.2.0 on JDK 25.

| entry | shape | stock 4.2.0 JDK 25 | fork, engine off | fork, Varka | ratio |
|---|---|---|---|---|---|
| `hour(t)` | projection | 61.3 | 60.8 | 1041.4 | 17x |
| `minute(t)` | projection | 61.1 | 60.5 | 821.3 | 13x |
| `second(t)` | projection | 61.1 | 60.8 | 835.4 | 14x |
| `time_trunc('MINUTE', t)` | projection | 28.5 | 28.2 | 978.0 | 34x |
| `time_trunc('MILLISECOND', t2)` | projection | 25.5 | 25.5 | 973.0 | 38x |
| `t - t2` | projection | 55.2 | 55.0 | 796.9 | 14x |
| `time_diff('HOUR', t, t2)` | projection | 36.4 | 36.5 | 791.9 | 22x |
| `t + dt` | projection | 33.5 | 52.3 | 689.1 | 21x |
| `greatest(t, t2)` | projection | 54.0 | 52.3 | 876.6 | 16x |
| `greatest(l, l2)` | projection | 55.2 | 55.0 | 915.6 | 17x |
| `t < TIME'12:00:00'` | filter, columnar consumer | 95.1 | 95.8 | 330.7 | 3.5x |
| `t < TIME'12:00:00'` | filter, counted | 131.0 | 132.4 | 110.1 | 0.84x |
| `l2 IS NULL` | filter, columnar consumer | 93.1 | 92.2 | 549.3 | 5.9x |
| `dt IS NOT NULL` | filter, counted | 208.4 | 201.6 | 70.4 | 0.34x |

The predictions, scored:

1. **Failed for the extracts, held for the truncations.** `hour`, `minute` and
   `second` read 17x, 13x and 14x against stock, under the date surface's
   projection median of 19.5x, not above it; the two `time_trunc` rows read 34x
   and 38x, above it. The prediction's reason was the stock arm's `LocalTime`
   per row; stock reads about 61 M rows/s on the extracts, about the same as
   its date extracts, so the object either costs little or is elided, and the
   ratio is set by Varka's rate, which the narrowed store and the 64-bit lane
   put at 800 to 1000 M rows/s against the date surface's 1200 and more.
   `time_trunc` is the row the prediction described: stock reads 25 to 28
   M rows/s there.
2. **Held on the baseline, failed upward on the arithmetic.** The engine-off
   arm reads within 3% of stock on every row but one; `t + dt` is the
   exception, where the fork's own row engine reads 52.3 against stock's 33.5,
   a difference in Spark master's `TIME + INTERVAL` path against 4.2.0's, not
   in Varka. The arithmetic rows do not sit near the date surface's 10x: `t - t2`
   reads 14x, `time_diff` 22x and 31x, `t + dt` 21x, the comparisons and
   selections 16x, because stock is slower on them than on its date arithmetic
   (25 to 55 M rows/s) while Varka's long lane reads 800 to 900.
3. **Held on the count, not on the memory.** 500000000 rows is the count that
   clears the rules, and the cache is resident there, at 48g rather than 32g;
   the estimate of 48 bytes a row was right and the driver it implied was not
   computed from it.
4. **Held, with the same losses.** The columnar-consumer filters win by less
   than the projections (3.5x and 5.9x at the best, 1.3x to 1.7x on the
   two-column comparisons), and the counted filters least: nine of the
   nineteen counted rows are under 1x, `dt IS NOT NULL` counted at 0.34x the
   worst, as the date surface's `d IS NOT NULL` counted was its worst at
   0.45x. The cause is the date surface's: a count over a mask is one aggregate
   the row engine does in a few cycles a row, and the columnar boundary costs
   more than the kernel saves. It is recorded there and not repeated here.
5. **Held.** Every entry fused with no fallback batch, at the smoke run and at
   every arm.

The band (VARKA-101's method, twelve fork-arm runs) is the remaining
measurement; no per-entry figure above is quoted in the README before it
exists. VARKA-121 then takes the same table under `-XX:UseAVX=2`, and VARKA-118
writes the README section.

## 7. The band, 21 September 2026

Twelve fork-arm runs at the committed setting, 500000000 rows in 48g, over
the day: five in the morning window, seven in the afternoon one, each started
on a quiet machine, under an 8% fixed-share bound for the band alone (the
committed arms ran under 6%; the first band attempt had read `hour(t)` at 6.2%
with an sbt run starting beside it, and the band measures spread, not the
number). `TimeSurface-jdk25-band.txt`, 86 cases.

**The surface is not the chains.** The date chains' band read every case under
3% (`VARKA-101.md` 9); this surface reads median 3.38%, p90 10.15%, max
17.03%, with 38 cases in tier 0, 38 in tier 1 and 10 in tier 2. The split is by
shape, and it is the split a reader needs:

* **Every projection the message quotes is tier 0 or 1.** `hour(t)` 2.10%,
  `minute(t)` 2.32%, `second(t)` 2.22%, `time_trunc('MINUTE', t)` 2.59%,
  `time_trunc('MILLISECOND', t2)` 2.80%; the arithmetic and selection
  projections between 2.9% and 5.2%. A regeneration that moves one of these
  rows by more than its tier has moved something.
* **The filters carry the spread**, and the two-column and counted filters
  most: `greatest(t, t2)` as a columnar filter 16.82%, `t < TIME'12:00:00'`
  counted 15.29%, `t + dt` counted 14.34%, `greatest(t, t2)` counted 13.06%.
  These are the rows whose Varka rate is within a factor of two of the row
  engine's - a job of a second or two where the columnar boundary and the
  task's fixed cost are a real share - and their tier says they are read only
  against it.

The two halves do not agree the way the chains' did: the first six runs put
two cases in tier 2 and the last six put six there, the same filter shapes
in both, so the filters' spread grew through the afternoon while the
projections' did not. Nothing else ran on the machine in either half; the
band records the day it was measured on and the tiers are the conservative
reading. Whether the surface band tightens on a night's run, as the chains'
did, is a question for the next regeneration and is written here rather than
assumed.

What this settles for VARKA-118: the extracts and truncations can be quoted
with their committed values and a 3% tier; the filter rows are quoted with
their tier beside them or as a range, never bare; and the band file is
committed before the README quotes any row, which is the order VARKA-101
asked for.

