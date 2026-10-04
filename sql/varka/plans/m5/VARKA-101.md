# VARKA-101: what an unchanged benchmark file does, per case

*Milestone 5, section 2.36. Opened and measured 17-18 September 2026, in an idle
machine window. The chains half is done; section 9.2 says why the surface's is
not and what it needs.*

## 1. Where this came from

Milestone row 101, out of VARKA-99. That task ran four surface arms overnight and
found each reproduced its committed twin to within 0.2% at the median over 52
entries - and each carried exactly one entry 8% to 27% away from it, in both
directions, stock Spark as readily as Varka. So a per-entry number in the README
carries a tail that one run cannot see, and a regeneration's diff cannot say
whether a single entry's move means anything.

The tooling for the answer already existed, built for the parity and throughput
benchmarks: `dev/varka_bench_repeat.sh` runs a benchmark N times pinned,
`dev/varka_bench_band.py` reports the per-case spread as a tier and writes the
committed band file, and `dev/varka_bench_diff.py --band` reads it so a move
inside the band stops being reported as a change. What nothing had done is point
them at the surface or the chains, which are driven by
`dev/varka_bench_surface.sh` against Spark distributions rather than by an sbt
`Test/runMain`.

## 2. The admission check, done

**What a run costs here, read off the committed files' own timestamps rather
than estimated.** Four surface arms on 13 September ran at 14:37, 16:32, 18:13
and 19:53 UTC - about **1h40m per arm** at the committed 1e9 rows. Four chains
arms on 12 September ran at 22:07, 22:32, 22:54 and 23:16 - about **22 minutes
per arm** at 2e8 rows, and a single arm run alone tonight took **three minutes**,
the earlier spacing having included the other three arms and their setup.

So a ten-run band costs half an hour for the chains and roughly seventeen hours
for the surface, before the second width. That is the whole reason this task
lands in halves: one of them fits an idle night and the other does not.

**The band tool parses the driver's files unchanged** - checked before any long
run by handing it two committed chains files, which it read as 24 cases each.
Nothing in the driver needed changing; what the task needed was a loop that runs
one arm N times under a scratch label, copies each result out and removes the
scratch file, so no committed file is touched.

## 3. The design

Twelve runs of one arm - `varka` at 2e8 rows, one partition, a 16g driver, the
parameters the committed chains files carry - on an otherwise idle machine, each
waiting for the one-minute load to fall below 0.9 first. The driver refuses a
machine above 1.0 and that rule is right: rather than passing `--force`, the loop
waits, because a forced run is recorded as one taken off the measured state and a
band built from those is worth less than no band.

Then `dev/varka_bench_band.py --write`, and the split-half check that decided
what a band may claim: it splits the runs in two, measures each half, and reports
how well one half's per-case spread predicts the other's.

## 4. Files

| file | what |
|---|---|
| `sql/varka/bench/benchmarks/DateChain-jdk25-band.txt` | the committed band, 24 cases |
| `sql/varka/plans/m5/PLAN.md` | row 101, section 2.36's outcome |
| `sql/varka/skills/benchmarking.md` | the lesson of 9.1 |

## 5. Tests, and what each is for

None: a measurement. Its own guards are the driver's per-arm checks and the
loop's row-count assertion on every run, so a run that produced no table fails
the series rather than being averaged into it.

## 6. The measurement

Twelve runs, as above. The band file records the tier per case, the run count,
the commit and the machine.

### 6.1 Predictions, registered before the run

1. The chains reproduce better than the parity benchmark, whose band has a
   median of 1.6% with 73 of 211 cases over 3% and 22 over 10%: a chains case
   runs for seconds through a whole Spark job, where the parity benchmark's
   cases are short in-JVM loops whose per-fork code layout dominates.
2. Some cases still land in tier 1 (3% to 10%), because VARKA-99 found one entry
   per arm 8% to 27% from its twin.
3. The split-half check reproduces as it did for the parity benchmark: *which*
   cases are noisy predicts across halves better than chance.

## 7. Risks

1. **A band measured on one machine is a band for that machine.** The file says
   which, as the parity band's does.
2. **Twelve runs is not many.** The split-half check is what says whether the
   number of runs was enough to claim anything per case; section 9.1 reports it.
3. **The surface's band is a different job**, not a longer version of this one -
   see 9.2.

## 8. Sequencing

1. The chains band, the night of 17-18 September (this PR).
2. The surface's band, which needs a window measured in days rather than hours,
   or the sharded form 9.2 proposes.

## 9. Outcome

**The chains are quiet, and quiet enough that the tiers have nothing to
discriminate.** Twelve runs, 24 cases: median spread **0.77%**, p90 **1.53%**,
max **2.55%**, and **not one case above 3%**. Every case is tier 0. The band file
is committed, and what it says to a reader of a regeneration's diff is simple: on
this benchmark, a move above 3% is not the run.

The two halves agree on that independently - half A (runs 1-6) median 0.73%, max
2.55%; half B (runs 7-12) median 0.59%, max 1.53% - so it is not an artefact of
where the series was cut.

### 9.1 The predictions, scored

1. **Right, and by more than the prediction implied.** The parity benchmark's
   band has 73 of 211 cases over 3% and a worst near 26%; the chains have zero
   over 3% and a worst of 2.55%. A chains case is a whole Spark job over 2e8
   rows, where the per-fork JIT and layout lottery averages out; a parity case is
   a short in-JVM loop where it does not. The practical consequence is that the
   two benchmarks need different kinds of band, not different thresholds of one.
2. **Wrong.** No case reached tier 1. VARKA-99's one-entry-per-arm tail, 8% to
   27%, was measured on the *surface*, and it does not appear on the chains.
   Whether it is a property of the surface's lighter, bandwidth-bound entries or
   of the 1e9-row scale is the question 9.2's job would answer.
3. **Not answerable here, and the reason is the result itself.** The split-half
   correlation is 0.366 with a worst-quartile overlap of 3 of 6 against a chance
   of about 1 - weaker than the parity benchmark's 0.325 to 0.728 at the wide and
   narrow widths. But when every case sits inside 2.55%, the ranking *within* the
   band is noise about noise: there is nothing for the halves to agree on. A
   split-half check discriminates only where there is a spread to discriminate,
   and that is worth knowing before the surface's band is read the same way.

### 9.2 What the surface's band needs, and why it is not this

At 1h40m per arm, ten runs is seventeen hours at one width and thirty-four at
both - a different kind of window from a night, and the reason row 101 is now
two halves rather than one. Three ways to get it, in the order they should be
tried:

* **Shards.** `dev/varka_bench_surface.sh --shard I/N` runs entries I, I+N, ...
  so twelve repeats of one shard of thirteen costs about ninety minutes and
  bands four entries. Thirteen such nights band all 52, and each night's file is
  usable on its own. This is the only form that fits the machine's real windows.
* **A row count of its own.** A band at 1e8 rows would cost a tenth, but it would
  describe a file nobody commits: the committed surface is 1e9, and tonight's
  result is a reminder that scale changes what dominates.
* **A runner.** The workflow of VARKA-62 (B) already runs the surface on GitHub;
  a band built there would describe that pool rather than this machine, which is
  what the README's numbers increasingly come from.

Until one of those lands, the surface's per-entry rows carry VARKA-99's finding -
one entry per arm 8% to 27% from its twin - and no band. Section 5 of the
milestone plan reads "the `TIME` surface is quoted only from under its own band
file"; on tonight's evidence the chains' band is cheap to produce and the
surface's is not, which is worth knowing before VARKA-105 plans its own.

## 10. The surface's band, 22 September 2026

Section 9.2 priced a surface band at seventeen hours from an arm that took
1h40m, and found three ways round it. The fourth was the plain one: the fork's
Varka arm alone, at the committed setting of 1e9 rows in one partition, takes
34 minutes once the table is built, because the 1h40m was the four arms with
stock's row engine among them. Twelve runs of that arm were one night, 23:25 to
06:15 Tivat time, each started on a quiet machine under an 8% fixed-share
bound (the band measures spread, not the number; the committed arms ran under
5%), in a 64g driver since the 23.2 GiB table is not resident in 48g.
`DateSurface-jdk25-band.txt`, 126 cases.

**The projections are quiet and the counted filters are not**, the split the
`TIME` surface's band showed the day before (`VARKA-105.md` section 7).
Across the 126 cases: median spread 2.11%, p90 3.30%; 107 cases in tier 0, 9 in
tier 1, 8 in tier 2 and 2 in tier 3. Every projection row is within 3.2% - the
widest, `date_add(d, i)` at 3.19% and `abs(ymm)` at 3.06%, sit just over the
tier 0 line - so the whole date table the README quotes can be read with a 3%
tier. The spread is the filters', and one shape is a different kind of case:

| filter, counted | spread | tier |
|---|---|---|
| `d < d2` | 44.85% | 3 |
| `d < d2 AND month(d) = 6` | 17.95% | 2 |
| `d = d2` | 11.63% | 2 |
| `d IN (three dates)` | 10.51% | 2 |
| `d IS NULL` | 6.66% | 1 |
| `d BETWEEN two dates` | 2.13% | 0 |

`d < d2` counted is the README's known-loss row (0.80x against stock) and it
moves by half of itself between runs of an unchanged file: tier 3, which the
band file defines as not readable from a diff at all. That row's ratio is a
statement about a range, not a number, and the README already prints it as a
loss with its cause; the band says it may not be quoted more precisely than
that. The two-column and `IN` counted filters are the next widest, as their
`TIME` counterparts were, for the same reason - jobs of a second or two where
the columnar boundary is a real share of the time and the kernel is not.

What this closes: row 101's second half. Both surfaces and the chains now have
a band file, the regeneration diff reads each case against its tier, and VARKA-118 quotes no surface row bare.

