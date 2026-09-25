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

So one measurement, task 195, and one test task, 188's Varka arm, stand between
the outline and the draft. Everything else the post needs is committed.

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
