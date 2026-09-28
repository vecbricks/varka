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
