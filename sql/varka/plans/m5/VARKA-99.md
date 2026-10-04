# VARKA-99: is the Varka arm measured in the session's worst memory state?

*Milestone 5, section 2.34. Opened 14 September 2026 out of VARKA-97's measurement.*

This task measures the benchmark harness, not the engine. No engine code changes.

## 1. Where this came from

VARKA-97 chased a 22.6% swing in `date_add(d, 3)` between two committed surfaces
and eliminated three explanations with numbers for each: the lowering is
unchanged, the six-column table costs about 6% and costs it nearly uniformly
across a four-op and a sixty-four-op entry alike, and running the Varka arm
fourth rather than first costs 1% in the favourable direction.

What it could not eliminate is elapsed time. The Varka arm of the committed
surface began at 19:53Z on 13 September, five hours and sixteen minutes after
that session's first arm started at 14:37Z; VARKA-97's runs began forty minutes
in. Ordinal position and elapsed duration are different variables and only the
first was controlled.

The control is what makes the remainder worth measuring. Stock Spark read
82.9 M rows/s for that entry on 13 September and 82.7 on 14 September, a
difference of 0.2%, so the machine was in the same state on both days. The
Varka arm on the same entry, same fixture and same ordinal position read 28%
higher on the second day.

## 2. The admission check, done

*Is the comparison clean at today's master?* Yes. Every commit touching
`sql/catalyst/src/main`, `sql/core/src/main` or `sql/varka/engine/src/main`
since the committed surface was measured is comment-only, apart from one metric
description string (`08d99473a14`, which drops a task number from the text of
`numFallbackBatchesDeclined`) and three `package-info.java` files
(`fc6a80fa5ed`). The engine that will run tonight is behaviourally the engine
that produced the committed numbers, so `varka-last` is comparable to
`DateSurface-varka-jdk25-results.txt` directly.

*Is the mechanism plausible?* The Varka arm runs under a 56g driver and holds
23.2 GiB of Arrow-cached data; the stock arms hold 6.2 GiB. Hours of allocation
churn degrade huge-page availability and fragment the address space, and the
entry most exposed to the resulting TLB cost is the one that reads four bytes
per row and does almost no arithmetic - which is also why the stock arm would
show nothing.

## 3. The design

### 3.1 The mechanism

One session, five arms, with the Varka arm measured first **and** last:

    dev/varka_bench_surface.sh --rows 1000000000 --partitions 1 --driver-memory 56g \
      varka-first=$PWD:$J25:varka \
      varka-off-night=$PWD:$J25 \
      spark-4.2.0-jdk25-night=$STOCK:$J25 \
      spark-4.2.0-jdk17-night=$STOCK:$J17 \
      varka-last=$PWD:$J25:varka

`varka-first` and `varka-last` differ in one variable: how long the machine had
been working when the arm began. Everything else - day, host, build, fixture,
row count, driver heap - is shared, because they are two arms of one session
rather than two sessions.

This is a change from section 2.34, which asked for two full-length runs
differing in arm order, about eleven hours. That design compares across
sessions and so carries day-to-day machine state as a confound it has to argue
away; this one removes the confound and costs about six and a half hours. The
three middle arms are not padding: they reproduce the churn the committed
session had between its first arm and its Varka arm, and they double as the
machine control against the committed files.

### 3.2 What is deliberately unchanged

The row count, the partition count, the driver heap and the six-column table
are the committed surface's, so `varka-last` can be read against
`DateSurface-varka-jdk25-results.txt` without adjustment. The labels are new,
so no committed results file is written by this run - which is also what keeps
VARKA-100's truncation hazard out of the way by construction rather than by care.

### 3.3 Registered op counts

Not applicable: no IR or emitter change.

## 4. Files

Results files only, under `sql/varka/bench/benchmarks/`, one per label. Whether
any of them is committed is decided by section 9 - a measurement of the harness
is not automatically a committed baseline.

## 5. Tests, and what each is for

None. The experiment is a measurement; its correctness rests on the canary
being clean at the start and on the machine staying quiet, both recorded in the
results provenance.

## 6. The measurement

Five arms at 1e9 rows, one partition, a 56g driver, on the laptop
(AMD Ryzen AI 9 HX PRO 370, 24 cores, 83 GiB), machine otherwise idle for the
whole session. Three comparisons come out of it:

1. **`varka-first` against `varka-last`** - the experiment.
2. **`varka-last` against the committed `varka-jdk25`** - does this session
   reproduce the committed conditions? If it does not, comparison 1 is still
   valid and comparison 3 is what explains the difference.
3. **the two stock arms and `varka-off` against their committed files** - the
   machine control, the same one that made this task worth opening.

### 6.1 Predictions, registered before the run

* `date_add(d, 3)` reads **25-30% higher in `varka-first` than in
  `varka-last`**. This is the entry the whole task is about and the one the
  mechanism predicts most strongly.
* The arithmetic-heavy entries - `trunc(d, 'QUARTER')`, `weekofyear(d)`,
  `extract(YEAROFWEEK FROM d)` - **move less than 5%**, because their cost is
  in the vector body rather than in the memory system.
* The stock arms **reproduce their committed numbers within 2%**, as they did
  across 13 and 14 September.
* Therefore: if the bandwidth-bound entries do not move, the duration
  hypothesis is dead and the committed surface stands exactly as measured.
  That outcome closes this task as a negative result, which is worth the night
  either way - it is the last explanation VARKA-97 left standing.

## 7. Risks

* **The laptop sleeping mid-session** ends the run and wastes the night. It is
  the one thing outside this plan's control.
* **A short night.** If the session is cut off, `varka-first` is already
  measured in the first eighty minutes and can be read against the committed
  `varka-jdk25` instead, at the cost of comparing across sessions.
* **The canary refusing.** The build churns the machine, so the run starts only
  after it has quiesced; `dev/varka_bench_surface.sh` refuses above a 1.0 load
  average and that refusal is respected rather than forced.

## 8. Sequencing

1. `build/sbt package` and the engine jar, then let the machine settle.
2. The five-arm run, logged, unattended.
3. Section 9 from the numbers: the three comparisons, the predictions scored,
   and - if the hypothesis holds - what `dev/varka_bench_surface.sh` has to say
   or stop doing about arm order.

## 9. Outcome

*Measured overnight 14-15 September 2026 on the laptop, five arms, 22:52 to 04:35,
canary clean on every arm (`compute +0.2% cache +2.4% memory -0.7%`), datapath probe
1.14. All five results files are committed beside this plan.*

**The hypothesis is dead, and prediction 1 was wrong.** Section 6.1 registered
`date_add(d, 3)` reading 25-30% higher in `varka-first` than in `varka-last`. It
reads **1.1% lower**: 1763.5 M rows/s fresh against 1783.7 M/s after five hours and
twenty minutes of the same session. Across all 52 entries the two Varka arms differ
by a median of 0.1%, the largest single deviation is 3.3%, and not one entry is 10%
apart. Elapsed time does not degrade the Varka arm, and the memory mechanism that
would have explained it - huge-page availability and address-space fragmentation
under a 56g heap - is not happening, or is not costing anything measurable.

**What is actually going on is better, and it is not about Varka.** Every arm
reproduces its committed counterpart almost exactly at the median, and every arm
carries exactly one entry that does not:

| arm | median vs committed | entries over 3% | over 8% | worst entry |
| :--- | ---: | ---: | ---: | :--- |
| `varka` | -0.1% | 1 | 1 | `date_add(d, 3)` +27.3% |
| `varka-off` | -0.2% | 8 | 1 | `d = d2` +8.5% |
| stock 4.2.0 JDK 25 | +0.0% | 1 | 1 | `weekofyear(d)` +14.2% |
| stock 4.2.0 JDK 17 | -0.1% | 1 | 1 | `trunc(d, 'MONTH')` -9.1% |

One outlier per arm, a different entry each time, in both directions, and **stock
Spark carries them as readily as Varka does**. A 14.2% swing on stock's
`weekofyear(d)` cannot be about a Varka heap, a Varka kernel or a Varka arm's
position, and it is the same phenomenon as the 22.6% that opened VARKA-97.

**So the committed `date_add(d, 3)` is the outlier, not tonight's numbers.** Four
independent measurements of that entry over the identical six-column fixture:

| the arm's start, relative to its session | rate |
| :--- | ---: |
| committed, 13 September, 5h16m in | 1401.4 M/s |
| VARKA-97, VARKA-14 September, 40 min in | 1780.6 M/s |
| tonight, first arm, 0 min in | 1763.5 M/s |
| tonight, last arm, 5h20m in | 1783.7 M/s |

Three agree within 1.2% across two days and three sessions; the fourth sits 21%
below all of them. VARKA-97 spent its effort asking what was different about the
13 September *session* - the fixture, the position, the machine, and now the
duration - when the answer is that one entry in that run drew badly, the way one
entry in each of tonight's four arms did.

**Predictions scored.**

1. *`date_add(d, 3)` reads 25-30% higher fresh.* **Wrong**, and wrong in the
   informative direction: -1.1%, which is what kills the hypothesis outright rather
   than leaving it half-supported.
2. *The arithmetic-heavy entries move less than 5%.* **Right** - `trunc(d,
   'QUARTER')` +0.3%, `weekofyear(d)` -0.3% - but it carries no weight, because
   nothing moved.
3. *The stock arms reproduce their committed numbers within 2%.* **Right at the
   median and wrong in the tail**, which turned out to be the whole story: both stock
   arms sit at 0.0% and -0.1% across 52 entries while each carries one entry 9% to
   14% away.

**What this means for numbers this project publishes.** The surface's *aggregate*
figures are solid: four arms reproduced their committed medians to within 0.2% over
52 entries each, two days apart. A *single entry's* figure is not: any one of them
can land 8% to 27% off, and the README quotes per-entry rows. Nothing needs
correcting today - the fastest-row and median figures are unaffected, and the
LinkedIn post quotes a median - but no single-entry ratio should be quoted again
without a band behind it, which is what the follow-up row asks for.

**What does not follow.** This does not say the benchmark is unreliable, and it does
not license re-running until a number looks good. It says the per-entry
distribution has a tail that four runs are enough to see and one run is not, and
that `dev/varka_bench_band.py` - which already reports exactly this, per case, and
whose own documentation records that *which* cases are noisy reproduces strongly -
has never been pointed at the surface.
