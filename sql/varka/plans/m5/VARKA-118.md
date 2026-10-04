# VARKA-118: the closing task - the measurement, the README, and the post

*Scoped 15 September 2026 (milestone 5 section 2.53, row 118); planned 21
September 2026, with VARKA-105's four arms committed and its band in progress.
Last in the milestone by definition.*

## 1. The question

The milestone's exit is a message: Varka now runs a 64-bit lane, and on it
three of Spark's types - `bigint`, `TIME` and day-time intervals - with `TIME`
as the type the story is told on, because it is new, about to be on by
default, and its vanilla implementation allocates a `LocalTime` per row. The
message has to say two things a reader can check: what is supported, stated
exactly as the coverage table states it, and how much faster it is than
vanilla Spark, stated from committed files, with the number a full-width
512-bit machine reads beside the number this laptop reads.

VARKA-62 closed milestone 4 with the same three parts and spent most of its
plan file on what went wrong between them. This plan takes each of those
lessons as a control (section 3) before it says what to measure.

## 2. What the message may claim, from the coverage table

The support claim is read off `docs/sql-varka.md` and `coverage.json`, which
`VarkaCoverageSuite` keeps current by construction, and not written from
memory. As of this plan:

* **`TIME`**: the fifteen `StaticInvoke` targets the compiler's table names -
  `hour`, `minute`, `second`, `time_trunc`, `make_time` from integer parts,
  `t + interval`, `t - interval`, `t - t2`, `time_diff` in every unit, the
  three `time_to_*` and the three `time_from_*` conversions - plus
  comparisons, `greatest`/`least`, `CASE`/`IF` and the null tests over `TIME`
  columns. *Correction, 22 September 2026, from VARKA-164: `t - interval` was
  written from memory and the table never had it. Spark resolves `t - dt` to
  `t + (-dt)`, the interval negation is not lowered, and the compiler declines
  it - a literal interval folds before it reaches the compiler, a column does
  not. Item 12 of section 3 is the control for exactly this, and the message
  says `t + interval` only until VARKA-103 builds the negation.* What declines, by name and with its reason in the table: the two
  decimal-valued functions (`second` with a fraction and `time_to_seconds`),
  `make_time` with a decimal seconds argument, `current_time` and `to_time`
  from strings. `spark.sql.timeType.enabled` has to be on, as it does in
  vanilla.
* **`bigint`**: comparisons, the null tests, `greatest`/`least`, `CASE`/`IF`
  and literals in the long lane's own slot table. Not arithmetic: `l + l2`,
  `l * 2` and the rest are VARKA-104, planned and not built, so the message
  says "comparisons and selections over bigint" and nothing wider.
* **Day-time intervals**: comparisons, the null tests, selections, and the
  interval as an operand of `TIME` arithmetic (`t + dt`, `t - t2` producing
  one, `time_diff`). Not interval arithmetic between intervals or with a
  number: VARKA-103, scoped and not built.

So the honest word is *partial*, and the honest shape of the claim is "one
64-bit lane serves these three types; here is the table of what fuses on it
today and here is each decline with its reason". The 128-bit column of that
table is this laptop's census at a forced species and says nothing about NEON
(`VARKA-153.md` section 6); the message does not quote it as an Arm
number.

## 3. What milestone 4's close taught, and the control for each

Read from `VARKA-62.md` 11.9 to 11.18 and from this milestone's own week.
Each item is something that was missed once and is a step here.

1. **The probe measured the wrong thing** (11.9): the first datapath line was
   a scalar recurrence and read 1.00 everywhere. Control: the probe is
   `dev/varka_datapath.sh` with its 256:128 positive control, and a run is
   admitted only when the control reads about 2.
2. **The gate certified a different VM** (11.15): a probe in one job says
   nothing about the machine another job gets. Control: the measure job
   probes its own machine and the results files carry the `datapath:` line
   written independently, and the two must agree (11.17). Already the
   workflow's shape; the plan checks it on the first run rather than assuming
   it survived the `time` selector.
3. **The build and the one-in-eighteen machine shared a job** (11.10).
   Control: `stop-after: build` on `require-datapath: any` warms the jar
   cache for the commit before any gated dispatch, and gated dispatches pay
   the cache restore and the run only.
4. **A run passed the fixed-share rule and measured nothing** (11.12): a
   table that did not fit recomputed on every iteration, an eleven-fold
   collapse reported as a better fixed share. Control: the driver now refuses
   a non-resident cache outright (VARKA-105's ladder hit exactly that at
   500000000 rows in 32g), and every file's `cache:` line is read before its
   numbers are.
5. **The surface is memory-bound and cannot show the datapath** (11.13), and
   **the surface cannot be measured honestly on a runner at all**: the job's
   constant is about 36 ms there against 15 on the laptop, so a 1 ns/row
   entry needs over a billion rows for the 5% rule, and the times table is
   45.6 bytes a row on a 15 GiB machine. Milestone 4 answered with the
   chains, which do enough work per row to need 200M. Control: the same
   answer. The `TIME` surface stays a laptop table, as the date surface is;
   the runner measures a `TIME` chains benchmark, which does not exist yet and
   is row 164 of this milestone - a twin of `DateChainBenchmark` over the
   `varka_times` table, with the same `MIN_OPS` floor, at most twelve entries,
   measured under the band. Without it there is no full-width number to show,
   which is the thing this task is for.
6. **The datapath was worth 1.14x, not 2x** (11.16, the README's own
   section): on the date chains the 512-bit machine gained 2.07x over a
   256-bit one but the scalar arms gained 1.78x, so the width itself bought
   about 1.14x. Control: the same subtraction is made for the `TIME` chains
   before any "512-bit" sentence is written, and section 4 predicts what it
   reads; the message says what the lane story is worth on this workload
   rather than what the lane count suggests.
7. **The one-in-eighteen lottery** (11.9's census, `HARDWARE.md`): a Zen 5
   draw is one dispatch in about eighteen. Control: dispatch in batches of
   ten with `dev/varka_surface_shards.py`'s re-dispatch, budget a day for the
   hit, and do the laptop and AVX2 work in parallel rather than waiting.
8. **The engine-off arm has to be within 20% of stock, or explained** (11.18;
   VARKA-105's `t + dt` already breaks it at 56%, because Spark master's own
   `TIME + INTERVAL` path is faster than 4.2.0's). Control: the README's
   explanation of that row is written from `VARKA-105.md` section 6
   before the row is quoted, and the same check runs on the chains file.
9. **A number superseded inside a pull request lost its provenance when the
   request squash-merged** (`SKILLS.md`, benchmarking). Control: every
   results file lands in its own pull request before any document quotes it,
   and the documents follow in a second one.
10. **The band came after the numbers last time** (VARKA-101 was opened from
    the date surface's tail). Control: the `TIME` surface's band is being
    measured now, before the README quotes any per-entry figure, and the
    chains' band follows their first regeneration; no row is quoted bare.
11. **The fixed-share rule needed a hand on the bound** (VARKA-105: `hour(t)`
    at 5.9% against 5%, lifted to 6% for that row). Control: the bound and
    the rows it was lifted for are stated in the README's methodology
    paragraph, not left in a plan file.
12. **The claim and the coverage table can drift** (VARKA-158: a row that
    compiled and failed the differential; VARKA-28's widening cast that does
    not exist). Control: section 2 above is regenerated from the table on
    the day the post is written, by reading `coverage.json`, and the post's
    support paragraph is checked against it by a reviewer other than the
    author.
13. **CI noise ate days** (the gate flake, the Kafka timeout, the census that
    flipped). Control: the closing pull requests carry results files and
    documents only, so they run the documents path or the scoped one, and a
    flake cannot block the merge of a number.
14. **A surface ratio is the row engine's cost as much as the kernel's saving**
    (VARKA-155: `time_trunc('MINUTE', t)` runs within 7% of `hour(t)`'s kernel
    rate and scores 34.7x against 17.1x, because the row engine costs twice as
    much on it). Control: the post headlines the kernel rate in rows per
    second with the ratio beside it and says which is which, and every ratio
    it quotes names the row-engine rate it is against, so a reader can tell a
    fast kernel from a slow comparand.

## 4. The measurement, and its predictions

**(A) The arms.** Stock 4.2.0 on JDK 17 and JDK 25 with
`spark.sql.timeType.enabled=true`, the fork with the engine off, the fork with
Varka - the date surface's four, so the tables read alike - and, from VARKA-121, the fork with Varka under `-XX:UseAVX=2` at the magic lowering.

**(B) The benchmarks and their machines.**

| benchmark | machine | setting | state |
|---|---|---|---|
| `TIME` surface, four arms | laptop | 500000000 rows, 48g, bound 6% | committed (#286) |
| `TIME` surface band | laptop | twelve fork-arm runs | 7 of 12 kept at planning time |
| `TIME` surface, AVX2 arm | laptop under the flag, then a Zen 3 runner | VARKA-121 | planned |
| `TIME` chains, four arms | a runner the probe proves full-width at 512 bits | row 164, 2e8 rows, one partition, 12g, as the date chains | to build, then the lottery |
| `TIME` chains, four arms | laptop | the same entries | for the width subtraction of item 6 |
| date surface and chains | laptop | only if the canary or `dev/varka_bench_diff.py --git` says a committed number moved | decided by the tool |

**(C) The README, the docs, the post.** The benchmark section gains the
`TIME` surface table beside the date one - one row per expression, the query
text beside the number, losses printed beside wins, the median and the banded
range leading - and a `TIME` chains table with the full-width machine named.
The "which figure generalises" paragraph says that the allocation rows
(`hour`, `minute`, `second`, `time_trunc`, `make_time`) measure vanilla's
`LocalTime` as much as Varka's lane, and reports them apart from the
arithmetic rows, with the engine-off ratio beside the stock ratio on every
row. The reproduction guide gains the flag on both arms and the `time`
selector. "Reading the source" gains the long-lane entry points and the
walkthrough. The post has a long draft in
`sql/varka/plans/m5/POST.md` and a short one in
`m5/POST_SHORT.md`: ideas first, numbers last, the median never the
best row, the honest split, and the support claim of section 2 verbatim; the
quote check runs on both like on any document. *Correction, 23 September 2026:
the two drafts were meant to share one file, and they cannot -
`dev/varka_post_page.py` publishes everything in the long read's file, so a
trailer drafted at the end of it appeared on the page as part of the piece.*

**Predictions, registered before the runs that remain.**

1. **The surface's headline.** Against stock 4.2.0 on JDK 25, the `TIME`
   surface's projection rows have a median between 14x and 17x on the
   laptop, with the extracts at 13x to 17x as the four arms read (`hour(t)`
   1041.4 M rows/s against 61.3) and the truncations at 34x and 38x; the
   band's tiers keep the median's row within one tier of its committed value.
2. **The chains show the lane.** A `TIME` chain of three or more divisions
   reads above 20x against stock on the laptop, and the four-arm ratio on the
   full-width runner is within the band of the laptop's ratio, as the date
   chains' was.
3. **The datapath is worth less than 1.3x and more than 1.0x on the `TIME`
   chains**, by item 6's subtraction, and the post says which. The reason to
   expect the low end: the 64-bit divisions are a dependency chain through
   the double divider, and VARKA-156's files show the wide store's `hour` in L3
   reading 4176.5 M rows/s at 512 bits against 4711.9 at 256 on this laptop,
   width buying nothing there; past L3 the store sets the rate.
4. **The AVX2 arm is the smaller number and the post carries it**: under
   `UseAVX=2` the magic lowering reads between a third and two thirds of the
   full-width rate on the division rows (VARKA-121's prediction 2 against its
   prediction 1), and above 5x against stock.
5. **The support claim needs no correction after review**, because it is read
   from the table; if the reviewer finds a claim the table does not back, the
   claim goes, not the table.

## 5. Verification

Every number in the README and the post traces to a committed results file
under `dev/varka_quote_check.py`, zero orphans. Each results file passes
`dev/varka_bench_gate.py`'s invariants and carries the CPU, flags, `UseAVX`,
`MaxVectorSize`, the datapath line, the canary, the row count, the residency
line and both flags' values. The full-width files' `datapath:` line agrees
with the gate's probe on the same job. The engine-off arm is within 20% of
stock on every row or explained by name. The band files exist for both `TIME`
benchmarks before the tables are written. The coverage table is unchanged by
this task, and section 2 matches it on the day of the post.

## 6. Outcome

*(A) landed with this plan, (B) per results file, (C) with the README and the
published post. Written 23 September 2026, when the last of the three closed.*

**(B) what was measured, and where.** The `TIME` surface's four arms and its
twelve-run band are laptop files, because a third of its entries are fast
enough that a cloud runner's 36 ms constant cannot be cleared inside 15 GiB -
section 3 item 11's reasoning, unchanged. The `TIME` chains are the public
number and were measured on GitHub Actions at both ends of the datapath: an
AMD EPYC 9V45 at probe 1.97, reached by twenty-four gated dispatches, and an
AMD EPYC 7763 at probe 1.00 for the width subtraction. VARKA-121's AVX2 arm is
a laptop file under `-XX:UseAVX=2` at `MaxVectorSize` 32.

**(C) the README and the post.** The README carries a `TIME` chains table and a
`TIME` surface table beside the date ones, the datapath section carries both
answers, and the reproduction section carries the dispatch recipe with
`max-fixed-share=11`. The long read is `m5/POST.md`, published at
<https://vecbricks.github.io/eight-rows-per-instruction/>; the trailer is
`m5/POST_SHORT.md`, posted 23 September 2026.

**The five predictions scored.**

1. **Held, on all three of its parts.** The surface's projection rows read a
   median of 16.2x against stock 4.2.0 on JDK 25, inside the predicted 14x to
   17x; the extracts read 13.4x (`minute`) to 17.0x (`hour`), inside 13x to
   17x; the truncations read 34.3x and 38.2x against a predicted 34x and 38x.
2. **Half held, half was withdrawn rather than measured.** Every one of the
   twelve chains is a `TIME` chain of three or more divisions and all but one
   read above 20x - 19.6x to 46.9x - so the substance holds. The other half
   asked whether the full-width runner's ratio falls inside the band of *the
   laptop's* ratio, and no laptop chains file exists: the owner's instruction
   of 22 September removed laptop runs from the critical path of any published
   number, so the comparison this prediction wanted was replaced by the one
   between two runners. The contrast it was reaching for is real and larger
   than expected - the same twelve on the Zen 3 runner read 2.6x to 5.9x,
   median 3.9x, *below* the surface rather than above it.
3. **Wrong, and wrong by more than a factor of six.** It put the datapath at
   under 1.3x and over 1.0x on the `TIME` chains. Measured, the kernel gains
   8.3x between the two runners once the 1.76x machine generation is divided
   out. The error is in what the prediction counted: it reasoned from VARKA-156's store measurements, where width buys nothing past L3, and a `TIME`
   chain is not a store - it is its divisions, and the wider machine does not
   merely run them eight lanes at a time, it runs a different instruction
   sequence for them. Lane count and lowering multiply, 2 x 4.7 = 9.4 predicted
   against 8.3 measured, and prediction 3 counted neither factor.
4. **Wrong on both halves, for the same reason as 3.** The AVX2 arm was put
   between a third and two thirds of the full-width rate on the division rows
   and above 5x against stock. On the laptop's own surface it reads **0.09 to
   0.16** of the full-width rate on those rows, and 1.18x to 4.96x against
   stock - `minute(t)` and `second(t)` barely clear parity at 1.18x and 1.19x.
   The number the prediction should have carried is the one item 3 missed: at
   `UseAVX=2` the lane count halves *and* the lowering falls back to the
   fourteen-operation magic form, which is about 9x together and is what the
   file shows.
5. **Held.** The support claim was read from the coverage table and survived
   the closing review without a change. What the review did correct was the
   mechanism prose around it - the division's operation count, how
   subexpression elimination shares a value, the dividend bound - and in every
   case the correction moved the prose to the table and the code rather than
   the other way round, which is what this prediction was for.

**What the three wrong predictions have in common** is worth stating once,
because it is the milestone's own lesson and not a scoring detail. Every one of
them priced a 64-bit lane as *the same work, more lanes*, and on this type that
is not what a wider machine buys. The division is lowered differently depending
on what the machine can do, so the instruction count per row changes with the
hardware, and a model that holds the lowering fixed will underestimate the
width and overestimate the fallback by the same factor. The date chains, where
the lowering does not change, are where the "more lanes" model is right - and
milestone 4's numbers were built on it, which is why it was carried here
unexamined.

## 7. Explicitly out of this task

Widening the support claim: VARKA-103's interval arithmetic and VARKA-104's
bigint arithmetic would make the three types' story rounder and are not
built here; the message says "partial" and points at the rows. An Arm number:
the width census on the aarch64 runner is a refusal census and not a rate,
and `HARDWARE.md` invites the measurement. The message itself is the owner's
to send.
