# Task 181: The closing post, outlined first

*Opened 25 September 2026, as the first item of `PLAN_MILESTONE_6.md` 9.2. This
plan carries the outline only. The draft is written when the outline's owed
numbers are in, and it will live beside this file as `POST_MILESTONE_6.md`, in
the shape of `POST_MILESTONE_5.md`: a long read whose every figure is a script
under `figures/` and whose every number is from a committed results file, with
the LinkedIn trailer at its end.*

## 1. The question

Milestones 4 and 5 ended in ratios, and a ratio invites the reader to argue
about the baseline. This post makes the owner's claim from `PLAN_MILESTONE_6.md`
1: a thing vanilla Spark cannot do, that Varka does. Not faster; possible at
all. The outline's job is to fix what the claim is, what bounds it, and which
committed evidence each section stands on, so that the measurements still owed
are known before another one is taken.

## 2. The claim, bounded

**The claim.** Spark generates Java source, cannot know how many bytes of
bytecode a method will be, and guards the JVM's method limits with a source
heuristic and a fallback. When a generated method passes 8000 bytes HotSpot
never compiles it, and Spark's answer is one INFO line and nothing else
(`PLAN_TASK_188.md` 5, G26); when a whole stage passes 64KB the compile fails
and the stage runs row by row (G24). Varka emits bytecode through the
Class-File API, measures every method after the class is built, and splits or
declines with a reason before anything runs (`PLAN_TASK_87.md` 9.4,
`PLAN_TASK_169.md`). So Varka has no method-size fallback at all, by
construction, which is the structural claim of `PLAN_MILESTONE_6.md` 6 risk 2.

**The bounds, each from the record, each in the post.**

1. **It is rare in the benchmark suites.** Of 178 TPC-DS and TPC-H queries
   under six configurations, one stage crosses 8000 bytes, `modified-q3`, the
   query Spark's own suite excludes (`PLAN_TASK_193.md` 9.1). The post says
   this in its second paragraph, not its last.
2. **It is not rare in the shapes people write.** A wide projection of date
   arithmetic crosses between 52 and 54 entries (`PLAN_TASK_171.md` 9.1), and a
   filter of date ranges, the shape a BI tool writes for a set of periods, is
   at 7048 bytes with 49 ranges and 14299 with 100, so it crosses between the
   two rungs the ladder has (`PLAN_TASK_172.md` 9.1; *corrected 25 September
   2026 from "about 50 ranges", which no committed rung says*). Both are
   ordinary.
3. **Vanilla can tune its way most of the way off the cliff**, and the post
   shows it: `hugeMethodLimit=8000` turns the step into 1.12 to 1.29 times the
   pre-cliff cost per entry on the runners (`PLAN_TASK_203.md` 9.2), and
   `wholeStage=false` into about 1.2 to 1.3 (`PLAN_TASK_192.md` 9.3, 9.4;
   *citation corrected 25 September 2026: 9.1 is the laptop*). What no tuning
   does is make Spark's default configuration say so at a level anyone sees,
   or bound the method in bytes.
4. **Varka's first query costs more than Spark's.** Every ladder number is
   steady state; a kernel is emitted and compiled once per shape (task 195).
   The post cannot go out without this number; see section 4.
5. **The native accelerators do not have this cliff, and pay differently.**
   Section 3.7 below, written from the record and nowhere else.

## 3. The outline

Eight sections, in the shape of `POST_MILESTONE_5.md`. Each names the figure
it opens with and the committed files it may quote.

### 3.1 A method Spark generates and the JVM refuses to compile

Open with the reader's own reproducer, not a claim: `sql/varka/demo/`
(`PLAN_TASK_203.md`), a spark-shell script anyone runs against a stock
distribution, and its committed output on runners at JDK 17, 21 and 25
(`method_size_cliff-jdk*-output.txt`). The step is 4.6 to 6.2 times under the
defaults. Then the one INFO line Spark writes, quoted from the census
(`VarkaCodegenCliffLogSuite`), and the shells' WARN threshold that hides it.

### 3.2 Why a source generator cannot know

Spark's own configuration text concedes it: "we cannot know how many bytecode
will be generated, so use the code length as metric" (`hugeMethodLimit`'s
documentation). The 1024-character split heuristic (G15), the three places a
stage does not split (G12 to G14), the history of the limits
(`PLAN_TASK_205.md`: 8000 for no release, 65535 since 2.3.0, the suite check
that has not run under adaptive execution since 3.2, SPARK-59764). Figure: the
timeline from task 205.

### 3.3 The census

Thirty-four places Spark's codegen gives up, from its source, with Varka's
answer to each (`PLAN_TASK_188.md` 2), and the suite that makes vanilla say
each one out loud and asserts Varka's side on the same shape
(`VarkaCodegenGiveUpSuite`). The post shows the table's shape and three rows,
not the table. This section is what lets every claim about Spark's behaviour
name a test rather than a reading.

### 3.4 What Varka does instead

The mechanism, plainly: bytecode emitted, measured in the JVM's own units after
the class is built, one budget of 8000 bytes over every method
(`methodByteBudget`), a loop method and an epilogue per output group, a regroup
when a group's methods are over, and a decline with a reason when no split is
left (`PLAN_TASK_87.md` 2, 9; `PLAN_TASK_169.md`). Figure: the emitted class
before and after the per-group form, from task 87's measured sizes. The
sentence the section exists for: no shape Varka admits can fail to emit, and a
shape it declines is declined at plan time with a reason in `EXPLAIN`.

### 3.5 The size ladder

The figure of the milestone, `figures/svg/fig11-the-size-ladder.svg`, read
from the full-width runner's file (`VarkaSizeLadderBenchmark-jdk25-runner-*`):
vanilla steps more than five times where its consume method crosses 8000
bytes, between 52 and 54 entries, and never compiles it again; Varka is a line
through it. Beside it, tuned vanilla (`VarkaSizeLadderTuningBenchmark-*`): the
step becomes a slope, and the line is still 16 and 19 times below it at 54 and
a hundred entries on the 9V45 (`PLAN_TASK_192.md` 9.5). The bands
(`*-band.txt`) are in the figure, not in a footnote.

### 3.6 One realistic query

TPC-DS `modified-q3`, the census's one crossing: 200 ranges over
`ss_sold_date_sk`, a 12167-byte `processNext`, the scan loop interpreted
(`PLAN_TASK_193.md`, `PLAN_TASK_172.md` 9.1). Varka's two designs, both built
and both measured: a predicate split across selection outputs the filter
combines (design A, on by default), and a range set over a static table of
bounds (design B, kept). The runner figure from `VarkaRangeFilterBenchmark-*`;
the honest line that below the crossing Varka is about twice vanilla, not a
hundred times (`PLAN_TASK_172.md` 9.3). What this section still needs is in
section 4.

### 3.7 The accelerators, from the record

Gluten, Comet and Photon avoid the method-size problem by leaving the JVM: no
JVM, no 64KB method and no 8000-byte compile refusal for the operators they
run natively. What they pay is a coverage boundary instead: each accelerates
the operators and expressions its native library implements and falls back to
Spark for the rest, where every limit above returns. The record this rests on:

* Comet's planning surface, surveyed at commit `8c229a703`
  (`SCOPE_MILESTONE_7.md` item 24, `VISION.md` "Outside Spark, but for
  Spark"): a support level per expression, fallback reasons printed by
  `EXPLAIN` as `[COMET: ...]`, a rule that all data-producing children must
  be native before an operator converts. The same contract as Varka's, arrived
  at twice, which is the sentence that keeps this section fair.
* Velox, read for the calendar family (`calendar-algorithms.md` under
  `sql/varka/skills`, "Velox is a semantics reference") and for its evaluator
  (item 18): an
  interpreter over vectors with no code generation at all, so the limit does
  not exist there and neither does the fusion.
* What is *not* in the record and so is not claimed: any measurement of an
  accelerator on the ladder or on `modified-q3`. Row 202's spark-vector arm,
  if it lands before the draft, is the one comparison the post may quote as a
  number; otherwise the section is about mechanisms and says so.

The section's claim is therefore narrow and checkable: Varka is the one
approach in the set that stays on the JVM, keeps Spark's own evaluation for
everything it declines, and still has no method-size fallback, because it
bounds the method in the unit the JVM enforces.

### 3.8 How it is measured, and what it cost

The measurement section of `POST_MILESTONE_5.md` 7, shortened: the runner pool
and the datapath gate, the bands, the quote checker, the provenance files. Then
the first-query cost from task 195 as its own figure, beside the steady-state
ladder, so the reader sees both. The trailer follows.

## 4. What the outline still owes, and what it does not

| Owed | For | State on 25 September | Needed? |
| :-- | :-- | :-- | :-- |
| The first-query cost (195) | 3.8, bound 4 | not started | **Yes.** The post does not go out without it |
| The 9V45 figure of `modified-q3` (172) | 3.6 | 0 hits in 15 dispatches; the 9V74 file is committed | No. The 9V74 file carries 3.6, and the 9V45 replaces it if it lands |
| Parallelism (197) | 3.8 | not started | No. One sentence saying the ladder is `local[1]` and why; 197 is a follow-up post |
| Varka's side in every census reproducer (188) | 3.3 | in progress, `VarkaCodegenGiveUpSuite` | **Yes.** Otherwise 3.3 cites a reading for the Varka column |
| The threshold below 8000 (170) | 3.4 | open | No. The post says 8000, the limit HotSpot enforces; 170 is a footnote if it lands |
| spark-vector's arm (202) | 3.7 | not started | No. Without it 3.7 quotes no number, and says so |
| The JVM's inlining evidence for the split-call case, from a forked JVM under `-XX:+PrintInlining`, asserted the way `VarkaSizeLadderJitSuite` asserts the compile log (*added 25 September 2026, section 7*) | 3.4 | not started; the laptop's log was not kept | **Yes**, or the sentence on inlining goes |
| Reproducers for G15, G33 and G34 in `VarkaCodegenGiveUpSuite` (*added 25 September 2026*) | 3.3 | not started; `PLAN_TASK_188.md` 6 lists them as provokable | **Yes.** Otherwise 3.3 cites readings for two fallbacks; G13 and G30 stay readings and the post says so |
| The distribution of generated method sizes over a real workload, from the histogram `CodegenMetrics` has kept since 2.x, exported over the SQL golden-file suites and the TPC suites (*added 25 September 2026*) | 3.3 | not started | No. A figure of thousands of methods against the two limits would open 3.3 well; without it 3.3 opens with the census table |

So one measurement, task 195, and one test task, 188's Varka arm, stand between
the outline and the draft. Everything else the post needs is committed.

*27 September 2026*: the reproducers for G15, G33 and G34 landed
(`PLAN_TASK_188.md` 6), and task 195's cost is written in (section 9). Of the
table's rows marked needed, the JVM's inlining evidence for the split-call case
is the one still open.

## 5. Verification

* Every number in the draft traces under `dev/varka_quote_check.py`, at zero
  orphans.
* Every claim about Spark's behaviour names a census entry and the test that
  pins it, at a revision named in the post.
* Section 3.7 quotes only what `VISION.md`, `SCOPE_MILESTONE_7.md` items 18
  and 24 and `calendar-algorithms.md` record, and a reader can find each
  sentence there.
* Bounds 1 to 4 of section 2 appear in the draft, each before the claim it
  bounds.
* The trailer is under the length `varka-public-writing-stays-short` allows:
  the milestone 5 trailer is the ceiling, not the floor.

## 6. Explicitly out of this task

* The draft itself, until task 195's number is committed.
* Any new measurement beyond 195. The 9V45 dispatches continue as they are and
  the outline does not wait on them.
* A comparison figure with an accelerator. Row 202 decides whether one exists.

## 7. The outline split in two, 25 September 2026

This corrects the scope of sections 1 and 3, not their content. The owner
decided the milestone ends in two posts. The first, about where vanilla Spark's
code generation gives up, is task 210 (`PLAN_TASK_210.md`); this task keeps the
second, about how Varka solves the problem.

Section 3.2, why a source generator cannot know, moves to task 210 whole and
becomes one paragraph here that states the result and links the first post.
Section 3.3 splits: the census itself - the 34 entries, the four groups, the
reproducers of vanilla's side - moves, and 3.3 keeps the Varka column, which is
Varka's answer to each entry and the table 3.4 draws on, so task 188's Varka
arm is still owed here (section 4). Everything else in sections 2 to 6 stands.
The bounds of section 2 appear in both posts, because each has to stand on its
own for a reader who never sees the other. The milestone closes on both posts
(`PLAN_MILESTONE_6.md` 1.3).

**The reader, fixed later the same day.** This post is for experienced Spark
developers; the first is for experienced Spark users (`PLAN_TASK_210.md` 1).
The test for where a finding goes is who can act on it, and a developer acts
on a mechanism. So the developer-facing material of task 210's first outline
(git 6638c9076b8) comes here: the four limits the JVM sets and how Spark
guesses at each (its 3.1 and 3.2, into 3.2 above); the census in full, with
its Varka column (3.3); and four findings that surprise a reader of
`CodeGenerator.scala`, each with the test that pins it - the `hugeMethodLimit`
check that cannot fire at its default (G25), the TPC suites' size check that
found no stage under adaptive execution from 3.2 until 4.4.0 (SPARK-59764), the
split that moves the problem into the method holding the calls (SPARK-59783,
*fixed in 4.4.0 on 26 September 2026, so the section tells it as found and
fixed*),
and code that compiles but is no longer inlined once its caller passes C2's
budget. The last two go in 3.4, as the contrast with measuring bytes after the
build. Section 4 gains their evidence.

## 8. What this post owes the first one, 26 September 2026

Task 210's post is published first (`PLAN_TASK_210.md` 9.7), and its closing
sentence says this post is still to come. When this post is published, that
sentence gets this post's link.

## 9. The first-query cost, written in, 27 September 2026

Section 4's first owed item. Task 195 measured it on 26 September, before task
212's warm-up existed, and found bound 4 sharper than written: a new shape's
kernel was never compiled over a hundred thousand rows, so Varka's first query
was slower than stock Spark's at every rung. Task 212 then compiled a new
kernel in the background while the shape's batches take Spark's own path, on by
default (`spark.sql.codegen.varka.warmup.enabled`, since 5.0.0), and
`VarkaColdStartBenchmark` gained the warm-up's arms. The numbers below are that
file's, `VarkaColdStartBenchmark-jdk25-results.txt` as task 212 committed it:
the laptop, a hundred thousand Arrow-cached rows per query, best of five, in
milliseconds.

| entries | stock Spark, first run | Varka with warm-up, first run | stock Spark, second run | Varka, once compiled |
| --: | --: | --: | --: | --: |
| 16 | 115 | 169 | 52 | 26 |
| 32 | 159 | 222 | 69 | 26 |
| 48 | 222 | 316 | 120 | 28 |
| 52 | 185 | 337 | 143 | 28 |
| 54 | 448 | 342 | 427 | 27 |
| 100 | 867 | 619 | 812 | 37 |

**Bound 4, restated.** Below the cliff Varka's first query costs more than
stock Spark's, by about half again; past it, it already costs less. What stands
between a new shape and Varka's steady state is the warm-up's verdict: about two
seconds at 16 entries and twelve to thirteen at a hundred (the file's verdict
lines: COMPILED after 1550 to 1775 ms at 16 entries, 12179 to 12965 at 100).
Fifteen queries of one shape back to back show the shape of it: at 54 entries
Varka with the warm-up runs 225 to 253 ms a query from the third on, stock
Spark 424 to 468. The bound the post states is therefore not "Varka's first
query is slower" but "below the cliff, Varka's first queries of a new shape
are slower; the cost is paid once per shape, because the shape cache keys a
kernel by its tree and not its literals".

**For section 3.8, in the post's voice** (the numbers are the table's; the
runner's numbers replace them, see below):

> **The first query.** Every number above is steady state. A new shape pays for
> its kernel once: Varka plans it, emits a class, and the JVM has to compile
> that class before it is fast. By default a background thread warms the
> kernel while the shape's first batches run on Spark's own path, and the
> kernel takes over when the JIT has compiled it. Past the cliff the first
> query is already faster than stock Spark's - 342 ms against 448 at 54
> columns, 619 against 867 at a hundred, on a hundred thousand cached rows.
> Below the cliff it is slower: 169 against 115 at 16 columns, 316 against 222
> at 48, since a compiled stage is hard to beat on one short query while Varka
> is still warming. Once the kernel is compiled, 16 columns take 26 ms against
> stock Spark's 52 on a second run, and 54 columns 27 against 427. A dashboard
> that reruns one shape with new values pays the first cost once: the kernel
> is keyed by the expression's tree, not its literals.

**What this leaves before the post can quote it.** These are laptop numbers,
and the laptop's datapath is 256 bits: every headline number in the post comes
from a GitHub-hosted runner, so `VarkaColdStartBenchmark` runs there before
publication, and the paragraph takes the runner's numbers. Task 195's own
outcome already said so ("the number the post quotes still comes from a
runner"). The figure section 3.8 plans - the first-query cost beside the
steady-state ladder - is drawn from the runner's file. The warm-up's verdict
may also come a compile later once task 221's fix lands (#467), which the
runner's run will include. Section 4's row for 195 is now "written; the
runner's run owed".


### 9.1 Regenerated with tasks 228 and 230, and run on a runner, 27 September 2026

The committed file was regenerated the same evening on master `7a83a8ef13c`,
which has the row path's two fixes of the day (`PLAN_TASK_228.md`,
`PLAN_TASK_230.md`), and the same code ran on a GitHub-hosted runner, an Intel
Xeon Platinum 8370C with four cores, through `benchmark.yml`
(`VarkaColdStartBenchmark-jdk25-runner-xeon8370c-results.txt`, with its
provenance): the run section 4 owed. Against the morning's committed file:

* **Below the cliff the first query's loss mostly closed on the laptop.**
  Varka's first run against vanilla's went from 1.31 to 1.10 times at 16
  entries (127 against 115 ms), from 1.88 to 1.06 at 32 and from 1.82 to 1.03
  at 52; the second run from 1.63 to 1.29 at 16 (66 against 51) and to 0.94 at
  52 (136 against 145). Past the cliff, from 54 entries, the first run is 0.54
  to 0.60 times vanilla's (263 against 457 at 54) and the second 0.29 to 0.33.
* **On the runner the loss is larger and the win past the cliff the same.**
  The first run is 1.31 to 1.52 times vanilla's below the cliff (294 against
  224 ms at 16 entries, 644 against 433 at 52) and 0.89 to 0.93 past it; the
  second run 1.06 to 1.40 below (146 against 104 at 16) and 0.33 to 0.37 past
  (255 against 686 at 54). The verdict takes 4.2 to 4.5 s at 16 entries and
  22.5 to 24.3 at 100 on the runner's four cores, against 1.8 to 1.9 and 11.1
  to 11.6 on the laptop, so short queries on a new shape stay on the row path
  longer: fifteen back-to-back queries at 16 entries never reach the kernel on
  either machine, at 52 to 57 ms against vanilla's 38 to 41 on the laptop and
  73 to 85 against 64 to 68 on the runner.
* **Once compiled, unchanged:** 27, 27 and 40 ms at 16, 54 and 100 entries on
  the laptop; 37, 44 and 72 on the runner, against vanilla's 104, 686 and 1288.

What bound 4 says now: a new shape's first query below the cliff costs within
about a tenth of stock Spark's on the laptop and up to half again on a
four-core runner, and past the cliff it is already faster; short queries on a
new shape run 1.2 to 1.4 times slower than stock for the warm-up's two to four
seconds, then about twice as fast at 16 entries and fifteen times at 54. The
figures the draft quotes are to be replaced from these two files.


## 10. The inlining evidence, and four rows made owed, 28 September 2026

**The owner's decision.** Section 4 marked four rows as improving the post but
not gating it: the 9V45 figure of `modified-q3` (172), parallelism (197), the
threshold below 8000 (170) and spark-vector's arm (202). The owner decided the
post is not submitted until all four are done as well. Section 4's "No" for
those rows no longer stands; its rows marked "Yes" are unchanged. The size
distribution row was not part of that decision and is still open.

**The inlining evidence, done.** `VarkaSplitInliningSuite` forks a JVM
(`VarkaSplitInliningProbe`) that runs one `CASE WHEN` of `WHEN v = k THEN v * k`
branches over a long column as a projection outside a stage, under `-Xbatch
-XX:+PrintCompilation` and `PrintInlining` for the generated class, and reads
which split `caseWhen_*` methods C2 inlined into their caller. On JDK 25 on the
laptop, the same in each of six forks per rung:

| branches | split methods | C2's caller | inlined | refused, `size > DesiredMethodLimit` |
| ---: | ---: | :-- | ---: | ---: |
| 16 | 6 | `apply` | 5 | 0 (one cold, "too big") |
| 32 | 11 | `CaseWhen_0$` | 7 | 4 |
| 64 | 22 | `CaseWhen_0$` | 7 | 15 |
| 128 | 43 | `CaseWhen_0$` | 9 | 34 |
| 300 | 101 | `CaseWhen_0$` | 11 | 90 |

From 32 branches the calls are grouped into `CaseWhen_0$` (SPARK-59783, which
the fork carries), and `apply` calls it. Every method C2 refuses to inline is
compiled by C2 on its own, so the code is compiled and each row still makes the
calls. The suite asserts the two ends: at 16 branches at least four split
methods inlined and none refused for the budget; at 300 some inlined, more than
four times as many refused for the budget, and every refused one compiled
alone. `DesiredMethodLimit` is 8000 bytes of inlined bytecode in HotSpot's
source, a develop flag no product JDK can change, and a separate budget from
the 8000-byte `HugeMethodLimit` the post is named after.

What 3.4 can now say: splitting keeps every method compilable, but past a few
thousand bytes of split code C2 stops inlining the calls, so the code is
compiled and still pays a call per method a row. The same mechanism showed
inside a stage on apache/spark#59069 (SPARK-33301), where 32 split methods at
64 branches had 13 inlined and 11 refused for the budget; that PR is not in
the fork, and the post cites it as upstream work in review.

What stands between the outline and the draft now: the four rows above, none
of which is a test task.

*Later the same day*: asked about the one optional row left, the distribution
of generated method sizes over a real workload, the owner said to include it
too. Every row of section 4 is now owed before the post is submitted, so what
stands between the outline and the draft is five rows: 170, 172's 9V45 figure,
197, 202 and the size distribution.

## 11. The size distribution, 28 September 2026

Section 4's last owed row. `VarkaMethodSizeCensus` runs Spark's golden-file suite and its
TPC-DS, TPC-H and SSB query suites in one JVM with Varka off, and counts the bytecode size of
every method of every class Spark compiles, per suite family
(`VarkaCodegenMethodSizes-jdk25-results.txt`, with its provenance). Spark records the same
sizes in `CodegenMetrics`' `generatedMethodSize` histogram, but that histogram keeps a sample
of about a thousand values that decays with time; the census puts an exact count in its place.

| suite | methods | median | p99 | largest | past 8000 |
| :-- | ---: | ---: | ---: | ---: | ---: |
| golden files | 331256 | 26 | 395 | 6704 | 0 |
| TPC-DS | 17436 | 63 | 758 | 12450 | 1 |
| TPC-H | 1322 | 50 | 548 | 2533 | 0 |
| SSB | 370 | 50 | 619 | 763 | 0 |
| all | 350384 | 27 | 425 | 12450 | 1 |

**Spark's own queries almost never reach the limit.** Of 350384 methods, 99.330% are under
500 bytes and 5 are between 4000 and 8000. The one past 8000 is `modified-q3`'s
`hashAgg_doAggregateWithKeys_0`, at 12450 bytes: the query `TPCDSQuerySuite` exempts from its
size check (SPARK-29128), and the one task 172 measures. So the cliff is not where Spark's
test queries go; it is where wide expressions go, which is why the post's evidence is the size
ladder and one realistic query rather than a sweep of the suites. The distribution is the
figure that says so, and 3.3 can open with it.

**What the census needed.** On the fork, the TPC suites compiled nothing: their size check
walks the plan with `foreach`, which does not enter an adaptive plan, so under AQE it found no
stage (SPARK-59764). The upstream fixes, SPARK-59764 (the check with AQE off) and SPARK-59765
(broadcast joins generated in full rather than as the stub an empty build side gives), merged
into apache/spark on 25 September and are not in the fork, which last merged upstream on 15
September. This branch carries both as cherry-picks.

**How to read the counts.** A class is counted once per JVM, since Spark caches compiled
classes by their source, so each family's count depends on which suites ran before it: TPC-H
counts 1328 methods alone and 1322 after the golden files. One golden-file test of 788
failed, `udtf.sql`'s Python UDTFs, in the Python worker: the local Python environment, not the
code generation.

Every row of section 4 is now done or owed as a measurement: 170, 172's 9V45 figure (in
review), 197 and 202.

*Later the same day*: the 9V45 figure merged (`PLAN_TASK_172.md` 9.10), so of section 4's
rows 170, 197 and 202 remain.

## 12. The threshold below 8000, 29 September 2026 (task 170)

Row 170 was owed before the post (section 10). Task 209 answered it (`PLAN_TASK_209.md` 10 to 13):
a limit below 8000 earns its calls, but the limit is C1's and is counted in Vector API call sites,
not in bytes, and it earns them for wide groups only.

* **The limit.** C1 compiles a loop method of 93 vector call sites and refuses one of 99 on JDK 25,
  at under 1500 bytes for cheap tails and near 1900 for `make_date` outputs, so no byte limit
  tracks it. A method C1 refuses runs interpreted, boxing every vector, until C2 compiles it
  seconds later, and in some JVMs then enters the deoptimization cycle for good.
* **What the lower limit costs and buys.** Split under 93 sites, a wide group of cheap outputs
  costs nothing measurable at steady state: sixty-four `year(d) + k` outputs in four methods run at
  3.3 ns a row where one method read 243 to 265 in the committed run
  (`VarkaSharedPrefixBenchmark-jdk25-results.txt`), and the cheap shape at 22 to 64 outputs forked
  again under the budget is 0 of 140 forks slow against 13 of 66 without it. A narrow group of heavy
  outputs does not earn the split: sixty `make_date` outputs one to a method run 2.3 times slower
  than in eleven groups, so such groups stay past C1 and wait for C2.
* **What the emitter does.** It holds every wide group's loop and epilogue methods under 93 sites
  (`VarkaEmitBudget.CALL_SITE_BUDGET`) beside the 8000-byte budget, and never declines on it.

**The footnote for 3.4.** "8000 bytes is the limit HotSpot enforces, and the one this post's
comparison is about. A second sits below it: C1, HotSpot's first compiler, refuses a method of
about a hundred Vector API calls whatever its bytes, and such a method waits seconds for C2.
Varka's emitter keeps wide groups under that count too, at no cost we can measure, and leaves
groups of a few heavy expressions past it, where splitting would cost more than the wait
(`PLAN_TASK_209.md`)." Its numbers trace to the committed files 209 names; what the budget leaves -
C1's boundary on the runners' JDK and on mask-heavy groups, and the heavy groups' seconds - is
milestone 7's item 61, and the post says nothing about it.

## 13. Section 3.7 drafted, 29 September 2026 (task 202)

Row 202's measurement is the one comparison 3.7 may quote as a number (section 3.7 above). The
draft was written before the runner's numbers landed, with four placeholders defined below, and
filled from the 9V45 run of 29 September 2026 (run 36616758753, `PLAN_TASK_202.md` 7): its two
files `VarkaLadder-vecruntime-0.0.3-jdk25-parquet` and `VarkaLadder-spark-4.1.3-jdk25-parquet`,
and Varka's value from the committed 9V45 ladder (`VarkaSizeLadderBenchmark-jdk25-runner`). No
laptop reading is quoted.

> **The accelerators.** Gluten, Comet and Photon avoid the method limits by leaving the JVM: for
> the operators their native libraries implement there is no 64KB method and no 8000-byte compile
> refusal. What they pay is a coverage boundary, past which they fall back to Spark and every limit
> above returns. Comet draws that boundary with the contract Varka arrived at on its own: a support
> level per expression, the fallback reason in `EXPLAIN`, and no conversion until every child
> that produces data is native.
>
> An engine that evaluates one expression node at a time, over a whole batch, needs neither
> native code nor a size limit. Velox works this way in C++, and vecruntime 0.0.3 does it on the
> JVM with the Vector API, as a plugin for stock Spark 4.1: each node writes its result out as a
> column and the next node reads it back. On the size ladder, on the same 9V45 runner as the
> figure above, vecruntime has no step. Its time grows by about 58 ns a row for each entry,
> the price of the four columns an entry's `add_months`, `date_add`, `last_day` and `greatest`
> write and read. Below the cliff that makes it 2.9 times slower than Spark 4.1.3, whose
> whole-stage code keeps an entry's values in locals; at a hundred entries, past the cliff, it
> is 1.7 times faster, though with `hugeMethodLimit=8000` vanilla Spark is 2.7 times faster
> than it. Varka runs the same hundred entries at 97.1 ns a row, 59 times faster than
> vecruntime. Having no cliff is all an engine of that kind gets; fusion is what keeps the
> intermediate values out of memory, as whole-stage code does, without its limit.
>
> Two bounds on those numbers. vecruntime leaves a cached table to Spark, so it reads Parquet
> where Varka reads its Arrow cache; Spark itself runs the ladder as fast from one as from the
> other, so the ratio is the engines'. And the ladder is the one shape both engines run:
> vecruntime converts none of the date chains, which add a year-month interval to a date.

What each placeholder was, so that filling it was arithmetic rather than judgment, and what
it came to from the executor-time tables:

* **58**: vecruntime's per-row time at 100 entries less its time at 16, over 84: 5748 less 857.
* **2.9**: vecruntime over Spark 4.1.3 at 16 entries, 857 against 293.5; at 48 entries it is the
  same, 2510.5 against 866.5, so the rung needs no naming.
* **1.7**: Spark 4.1.3 over vecruntime at 100 entries, 9509 against 5748.
* **59**: vecruntime at 100 entries over Varka's 97.1.

The sentence "Spark itself runs the ladder as fast from one as from the other" rests on task 194's
two inputs on the 7763 runner (`PLAN_TASK_194.md` 7). On the 9V45 the fork with Varka off reads
Parquet within 15% of its cache ladder at every rung, which cannot move a ratio of fifty-nine, so
the sentence stands (`PLAN_TASK_202.md` 7). The second paragraph's last sentence is the section's
claim and `PLAN_TASK_202.md`'s prediction 4, which held by a factor of fifty-nine against ten. A
second 9V45 run, the repeat, moves none of the four numbers by more than 4%.

The clause after "1.7 times faster" was added the same day, on the owner's decision. The first
draft compared vecruntime with Spark's defaults only, while section 3.5 shows tuned vanilla beside
the ladder figure, and with `hugeMethodLimit=8000` the fork's vanilla runs the ladder 2.5 to 3.0
times faster than vecruntime at every rung (`PLAN_TASK_202.md` 7): vecruntime is faster than
Spark only under Spark's defaults. The clause's 2.7 is vecruntime at 100 entries over tuned
vanilla's 2155.6 on the 9V45 (`VarkaSizeLadderTuningBenchmark-jdk25-runner`), which reads the
cache; the third paragraph's bound on the input covers it as it covers Varka's 97.1.

## 14. The first draft, 1 October 2026

Every row of section 4 was done by 29 September (197 in `PLAN_TASK_197.md` 7, 170 in section 12,
202 in section 13), so the draft was written: `POST_MILESTONE_6.md`, in the first post's form -
a title, a lede, numbered sections each opening with its figure, a closing paragraph and a
measurement note - at 4023 words after 14.1, against the first post's 3239 and the milestone 5
post's 4392, the ceiling `varka-public-writing-stays-short` sets. Every number in it is from a
committed results file, the quote checker reads it at zero orphans, and it is ASCII.

**Where each outline section went.**

| Post | Outline | What it carries |
| :-- | :-- | :-- |
| lede | 2 | the claim; bounds 1 and 2; bound 3 and bound 4 named before the numbers they bound, as section 5 asks |
| 1 | 3.2 as section 7 amended it | the four limits and how Spark meets each; the splitter's characters, with G15's measured sizes; G12 and G14; the first two surprises, G25 and SPARK-59764 |
| 2 | 3.3 | the size distribution (section 11) opening it; the census's four groups and three rows (G15, G25, G26); the whole table a link |
| 3 | 3.4 | the emitted class, the budget, the regroup and the plan-time decline; Varka's own epilogue cliff (`PLAN_TASK_87.md` 2.3, 9.3), in bytes and the compile log only; the driver from a table, the stages and several kernels (`PLAN_TASK_190.md` 10, 11); the fuzzer past the ceilings (row 238); the last two surprises, SPARK-59783 and the inlining evidence (section 10); section 12's footnote |
| 4 | 3.5 | the 9V45 ladder, tuned Spark beside it, task 197's sentence, the spread of the four 9V45 runs |
| 5 | 3.6 | `modified-q3`'s filter on the 9V45 (`PLAN_TASK_172.md` 9.10), both designs, why the range set stays (9.12) |
| 6 | 3.7 | section 13's text, one phrase changed: "the figure above" is "section 4's figure" |
| 7 | 3.8 | the first query from the runner's file (section 9.1), and the measurement note |

**What moved since the outline, and what the draft quotes instead.**

* **The ladder's Varka numbers.** Task 198's 9V45 run of 29 September replaced the ladder file, so
  Varka reads 59.1 ns a row at 54 entries and 97.1 at a hundred, where 3.5 took 69.6 and 111.4 from
  `PLAN_TASK_192.md` 9.5. Against tuned Spark that is 19 and 22 times, not 16 and 19. The tuned
  numbers are another run (24 September, run 36045946987) on the same CPU model; its defaults are
  within 7% of the ladder file's at every rung, and both the post and Figure 11 say so.
* **The warm-up's verdict at 16 entries.** The runner's file reads 3482 to 4506 ms, where 9.1 said
  4.2 to 4.5 s; the post quotes the file.
* **The splitter's documentation.** 3.2 attributed "we cannot know how many bytecode will be
  generated" to `hugeMethodLimit`. It is `methodSplitThreshold`'s text, as the first post has it.
* **The warm-up's path.** While a projection's kernel warms, its batches run Spark's projection
  outside a stage, split into compiled methods (`PLAN_TASK_212.md` 10), which is why Varka's
  first query past the cliff already beats Spark's interpreted stage. The post says so; a filter's
  row path is the stage itself (`PLAN_TASK_172.md` 9.12), and the post makes no first-query claim
  for the filter beyond planning and the verdict.

**Decisions the draft took, for review.**

1. **The title**, "Under 8000 bytes by construction": the claim, with the first post's number.
2. **Five figures, one per claim.** `fig21.py` (new): the hundred entries as Spark's one method of
   17132 bytes and as Varka's class, from a class dump committed under `figures/data/` with the
   command and commit that produced it. `fig22.py` (new): section 11's census. `fig11.py`
   (rewritten): tuned Spark beside the defaults and Varka. `fig23.py` (new): the filter.
   `fig24.py` (new): the first query.
3. **No band drawn on the ladder figure.** 3.5 asked for the bands in the figure. The `*-band.txt`
   files are the laptop's, and of the four 9V45 ladder runs only one is a committed results file,
   so the figure draws that run and the text gives the four runs' spread from its provenance.
4. **No vecruntime figure.** Its numbers are another run, over Parquet; section 6's text carries
   them with that bound.
5. **Ratios round to whole numbers**, as in the first post: the quote checker cannot trace a
   two-digit ratio with a decimal, since results files print ratios with an `X`.

**The line from a run.** Section 3 quotes `EXPLAIN`'s fusion report for a declined output as
`VarkaProjectExecSuite`'s task 169 test printed it on 1 October (`PLAN_TASK_169.md` 5): a
balanced `greatest` over thirty-two `add_months` residual, with `loopDense0 is 23505 bytes, over
the method budget of 8000`, beside a fused `date_add`. The line is wrapped at the post's width
and its expression cut where the plan's own rendering cuts it; the test asserts its start.

**What the draft still owes:**

| Owed | Where | Comes from |
| :-- | :-- | :-- |
| The owner's review of the draft | all of it | this commit |
| The trailer's last read, and where its link goes | `POST_MILESTONE_6_SHORT.md` | drafted with this draft, from the post's numbers; reread once the post's text settles |
| The tickets' states: SPARK-33301 in review, SPARK-59764 and SPARK-59783 in 4.4.0 | 1, 3 | reread on the day of publication |
| The page, and the link in the first post's closing | `dev/varka_post_page.py`, `POST_MILESTONE_6_SPARK.md` | on publication (section 8) |

### 14.1 The review of the draft, 1 October 2026

The owner asked for the draft to be reviewed for consistency and correctness before anything
else. Every number was traced to its committed file, every mechanism claim to the plan that
recorded it, the five tickets to the tracker on the day (SPARK-59764, 59774 and 59783 fixed in
4.4.0, 59765 in 4.4.0 and 5.0.0, SPARK-33301 open with apache/spark#59069 unmerged), and
Figure 1's class to its dump method by method. Seven errors and five inconsistencies, each
corrected in the same pull request:

1. *"The grouped loop methods stayed under 5,400"* in section 3 was false: in the legacy form
   `loopMasked3` read 5597 bytes at sixty outputs (`PLAN_TASK_87.md` 9.2; 2.2's table, which
   section 3.1 of that plan summarized as "under 5400", lists `loopMasked0` alone). The post
   now says the loop methods stayed under the limit, with no number.
2. *"Puts each branch of a wide `CASE WHEN` into a method of its own"* was false: 300 branches
   make 101 split methods, about three to a method (section 10's table). Reworded.
3. *"Its class at a hundred entries is Figure 1's"* was not quite so. The ladder ran on
   29 September at `4cedebc4719`, before the driver from a table (`PLAN_TASK_190.md` 10, 30
   September). The class was dumped again at that commit
   (`figures/data/size-ladder-class-100-at-ladder-run.txt`): every method but the two drivers
   is byte for byte today's, and the drivers were 5278 and 5932 bytes against 1120 and 1121 now. The
   post says so, and that the table form ran the hundred entries 3 to 8% faster on the same
   runner (`PLAN_TASK_190.md` 10.4), so the committed ladder number is conservative.
4. Figure 1's caption said two drivers of 1,120 bytes; they are 1,120 and 1,121.
5. *"Every timing before section 7 is steady state"* was contradicted by section 5's last
   sentence, which quotes planning and warm-up times. Now "every time per row".
6. Section 1's closing claimed every claim of the section is a test; the limit's history and the
   five years of the unhooked check are the tracker's and the source's. Reworded.
7. *"Errs far on the safe side, as its documentation says it means to"*: the splitter's comment
   names 8K and also warns against methods too small, so "means to" was a reading. Now a fact.
8. The post never named which Spark its Spark arm is; section 4 now says a September 2026
   build of master, this fork with Varka off, as the first post does.
9. The 8000-byte line is a warning once from 4.4.0 (first post, SPARK-59774); section 1 says so.
10. The lede's "no method-size fallback to take" read against section 3's residual entry on
    Spark's path. Now: no method Varka runs is one the JIT never compiles, and the decision is
    taken before the class runs anywhere.
11. Two sentences said "the same reading" with no antecedent, a late edit. Fixed.
12. Section 7's "that path has no cliff either" holds at these widths; the caller of the split
    methods has its own cliff further out (first post, section 5). Qualified.

What the review verified and left standing: all ladder, tuning, filter, cold-start, census and
vecruntime numbers; the drift between the tuned run and the ladder run, 6.3% at most at a
hundred entries; 25 groups of exactly four entries, read off the 22 line-map entries of every
group method; the census's four groups (11, 12, 8 and 3) and its 23 reproducers; `sql/testOnly`
for the two suites, which live in sql/core; `d` as a default column of `dev/varka_emit.sh`;
`CodegenMetrics`' histogram as Dropwizard's decaying reservoir of 1028 samples.

### 14.2 The second draft, 1 October 2026

The owner read the first draft and said it reads like a lab report, and asked how to make it
readable; the diagnosis and the restructure were agreed the same afternoon, with the first draft
kept in the branch's history (`e0c47936820`) in case the second is not liked.

**The diagnosis.** The traceability standard had leaked into the voice: three numbers a sentence
("7,868 bytes at 52 entries and 8,254 at 54, and the time per row goes from 876.0 to 4,671.8 ns,
5.3 times"), provenance clauses in the argument ("whose defaults read within 7% of this run's at
every rung"), tests described as prose (the fuzzer's "seven reactions"), and headings that name
topics ("The census, and Varka's column") rather than points.

**What changed.** Three questions instead of seven topics: why Spark cannot just split the
method; what measuring the class looks like, and what we got wrong doing it; what it buys and
costs. The lede opens on Figure 1 in words. Three listings carry the mechanism: Spark's generated
`project_doConsume_0` for the hundred entries with its `maxMethodCodeSize:17132` header, the
method table `dev/varka_emit.sh` prints for the same entries, and C2's inlining lines for the
split `CASE WHEN` methods at 300 branches. One number a sentence, rounded; the decimals stay in
the figures and the note. Every provenance clause moved to the measurement note: the Spark
build, the pinning, the four-of-thirty-six, the tuned run's 7%, the 7763's four cores, the
ladder's drivers at its commit, vecruntime's Parquet. Cut: the census's group arithmetic, the
fuzzer sentence, the driver table's mechanics beyond one sentence, and the four limits as a
bullet list, now one paragraph. The first person for what Varka got wrong. 3497 words, from 4023.

**The listings' provenance.** The generated code was dumped at this commit with a scratch
`runMain` in the benchmark package that uses `VarkaArrowSessions.createSession` and
`VarkaSizeLadder.cacheDates`, the ladder benchmark's own vanilla session and cached table, under
`spark.sql.adaptive.enabled=false`; its header reads `maxMethodCodeSize:17132`, the committed
ladder's number, and the method runs from line 125 to 4140 of the generated source. A first
attempt over an uncached `range`-derived column read 17015: the consume method's size depends
on the input column's nullability, so a listing that is to match a benchmark's number is taken
from the benchmark's own plan. The inlining lines are `VarkaSplitInliningProbe` at 300 branches
on the laptop's JDK 25, under the suite's own flags; C2's compile of `CaseWhen_0$` (1441 bytes)
inlined 11 split methods and refused 90 with `size > DesiredMethodLimit`, section 10's counts.
Neither scratch file is committed; both listings are reproducible from the suites named.

**Still owed:** as section 14's table, with the owner's read of this draft in place of the first.

*Later the same day*: the owner found Figure 5 hard to read, four of its five lines being two reds
told apart only by a dash. `fig24.py` now draws two panels on one scale - the first query, Spark
against Varka, and the second query with the compiled kernel beside it - in three colours and no
dashes; the caption says so.
