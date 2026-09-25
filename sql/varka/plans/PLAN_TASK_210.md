# Task 210: The first post, where Spark's code generation gives up

*Opened 25 September 2026 by splitting task 181's outline in two. This plan
carries the outline of the first post only. The draft is written when the
outline's owed items are in, and it will live beside this file as
`POST_MILESTONE_6_SPARK.md`, in the shape of `POST_MILESTONE_5.md`: every
figure a script under `figures/`, every number from a committed results file.
The milestone closes on both posts (`PLAN_MILESTONE_6.md` 1.3, the note of
25 September 2026).*

## 1. The question

The owner's direction, 25 September 2026: the project's expertise covers code
generation in vanilla Spark as well as in Varka, so the milestone ends in two
posts rather than one. The first is about the internals of Spark's code
generation - every limit this work has hit, the thresholds, the timings, and
the places where code generation falls back to interpretation. The second is
the post planned from the start, about the 64KB problem and how Varka solves it
(task 181).

This post is about Spark only. Its claim is descriptive rather than
comparative: where Spark's code generation gives up, what each give-up costs,
and what, if anything, Spark says when it happens. Varka appears in the last
paragraph, as the link to the second post, and nowhere else. That is what makes
it the one to publish first: none of the Varka measurements task 181 still owes
gates it, and once it is out, the second post replaces two of its sections with
a link (`PLAN_TASK_181.md` 7).

## 2. The claim, bounded

**The claim.** Spark generates Java source and cannot know how many bytes of
bytecode a method will be, so it guards the JVM's limits with a source-length
heuristic and a set of fallbacks. The census counts 34 places where its code
generation gives up or degrades, 30 of them give-ups in the strict sense
(`PLAN_TASK_188.md` 2). Most are silent. The two that decide speed are the
method HotSpot refuses to compile past 8000 bytes (G26) and the interpreted
fallback outside a stage (G32, G34). Spark reports the first at INFO, which
`spark-shell` hides at its WARN level and `spark-submit` prints at the default
template's level (`PLAN_TASK_188.md` 5); from 4.4.0 it is a warning, once
(SPARK-59774). It reports the second at WARN, which every default shows. What
no setting reports is the cost: neither line says the method runs interpreted
from then on.

**The bounds, each from the record, each in the post.**

1. **It is rare in the benchmark suites.** Of 178 TPC-DS and TPC-H queries
   under six configurations, one stage crosses 8000 bytes, `modified-q3`
   (`PLAN_TASK_193.md` 9.1). The post says this in its second paragraph.
2. **It is not rare in the shapes people write.** A wide projection of date
   arithmetic crosses between 52 and 54 entries (`PLAN_TASK_171.md` 9.1). A
   filter of date ranges, the shape a BI tool writes for a set of periods, is
   at 7048 bytes with 49 ranges and at 14299 with 100, so it crosses between
   the two rungs the ladder has (`PLAN_TASK_172.md` 9.1); the post quotes the
   rungs, not an interpolated count.
3. **Much of it can be tuned away**, and the post says how:
   `hugeMethodLimit=8000` turns the step into 1.12 to 1.29 times the pre-cliff
   cost per entry on the runners' three JDKs (`PLAN_TASK_203.md` 9.2), and
   `wholeStage=false` into about 1.2 to 1.3 (`PLAN_TASK_192.md` 9.3, 9.4).
4. **It describes one revision.** Every behaviour is pinned by a test at a
   revision the post names, or is marked as read from the source where no test
   pins it (section 3.5). The upstream fixes are listed with their state on the
   day of publication; three of the five are merged already (3.6), so the post
   says which release has them, and none is called merged unless it is.

## 3. The outline

Seven sections and a closing paragraph. Each names the committed evidence it
may quote.

### 3.1 The four limits the JVM sets

The 64KB method (G24), the 8000 bytes HotSpot compiles (G26), the 65535
constant-pool entries (G27) and the 255 parameter slots (G28), each with what
Spark does when a generated class meets it. Evidence: the reproducers in
`VarkaCodegenGiveUpSuite` and the INFO line pinned by
`VarkaCodegenCliffLogSuite`. G28 is settled by its reproducer
(`PLAN_TASK_188.md` 6): 254 int parameters compile and 255 fail at compile
time, an ordinary compile exception, so inside a stage it takes G24's path;
the post says where Spark guards the limit (G9, G16 to G20) and what happens
where it does not.

### 3.2 How Spark guesses

Moved here from `PLAN_TASK_181.md` 3.2. Spark's configuration text concedes the
problem: "we cannot know how many bytecode will be generated, so use the code
length as metric" (`hugeMethodLimit`'s documentation). The 1024-character split
heuristic (G15); `hugeMethodLimit`, which cannot fire at its default (G25); the
three places a stage does not split at all (G12 to G14); and the history of the
limits (`PLAN_TASK_205.md`: 8000 for no release, 65535 since 2.3.0, the suite
check that did not run under adaptive execution from 3.2 until SPARK-59764's
fix in 4.4.0). G12, G14 and G25 have reproducers, G15 gets one before the
draft (section 4), and G13 is read from the source and the post says so.
Figure: the timeline from task 205.

### 3.3 The census

Moved here from `PLAN_TASK_181.md` 3.3, without the Varka column. Thirty-four
places, in the census's four groups: plan time (G1 to G11), generation time
(G12 to G23), compile time (G24 to G31) and expression code generation outside
a stage (G32 to G34). The post shows the table's shape and three rows, links
the full table, and says which entries a reproducer pins.

### 3.4 What it costs

The timings. The demo's step under the defaults, 4.6 to 6.2 times on runners at
JDK 17, 21 and 25 (`PLAN_TASK_203.md` 9.2); the size ladder's vanilla arm and
tuned vanilla (`PLAN_TASK_171.md`, `PLAN_TASK_192.md`). Then the case upstream
work found on 25 September 2026: an expression split into hundreds of
functions leaves one call per function in the method holding them, the calls
alone take that method past 8000 bytes, and every row runs it interpreted
(SPARK-59783). The same work found that splitting is not free even below the
limit: C2 stops inlining the split functions once their caller passes its
inlining budget, which `-XX:+PrintInlining` shows and a timing only suggests.
Those numbers were laptop runs and the inlining log was not kept; section 4
owes the committed benchmark and the JVM's own evidence, in the form
`VarkaSizeLadderJitSuite` uses for the compile log.

### 3.5 When it falls back to interpretation

The fallbacks in one place: the method that is never compiled (G26), the
interpreted projection a failed compile degrades to (G32), the callers that do
not degrade and fail instead (G33), the absence of any size check outside a
stage (G34), and the executor compile that has no fallback (G30). What each
logs, and at which level, from the census's "Logged?" column. G26 and G32 are
pinned; G33 and G34 get reproducers before the draft (section 4); G30 cannot
be provoked honestly (`PLAN_TASK_188.md` 4, item 8) and the post presents it
as a reading of the source.

### 3.6 What is fixed upstream, and what is not yet

Each ticket with its state read from the tracker on the day of publication.
On 25 September 2026:

| Ticket | What | State |
| :-- | :-- | :-- |
| SPARK-59764 | the TPC suites' size check checked nothing under adaptive execution | fixed, 4.4.0 |
| SPARK-59765 | the same check and `WholeStageCodegenSizeBenchmark` measured empty-broadcast stubs | fixed, 4.4.0 and 5.0.0 |
| SPARK-59774 | G26's INFO line raised to a warning, once, naming the remedy | fixed, 4.4.0 |
| SPARK-59783 | the calls to split functions grouped so their caller compiles | open, apache/spark#59042 |
| SPARK-33301 | a large `CASE WHEN` split inside a stage | open since 30 October 2020 |

The post names the release a fix ships in and calls nothing merged that is
not.

### 3.7 What a user can do today

The tuning from bound 3; where G26's line goes by version (INFO up to 4.3,
hidden in the shell and printed by `spark-submit`; a warning once from 4.4.0);
and how to read `EXPLAIN CODEGEN`, whose header prints each stage's largest
method in bytes. From `PLAN_TASK_192.md` and `PLAN_TASK_203.md`.

**Closing.** One paragraph: a JVM engine that emits bytecode can measure the
method in the unit the JVM enforces, and the second post shows one. The link to
it, and nothing else about Varka.

## 4. What the outline still owes, and what it does not

| Owed | For | State on 25 September | Needed? |
| :-- | :-- | :-- | :-- |
| A committed vanilla benchmark of the split-call case: the caller's size, whether it compiles, and the time per row with and without grouping | 3.4 | not started | **Yes.** 3.4 quotes no timing of it otherwise |
| The JVM's inlining evidence for the same case, from a forked JVM under `-XX:+PrintInlining`, asserted the way `VarkaSizeLadderJitSuite` asserts the compile log | 3.4 | not started; the laptop's log was not kept | **Yes**, or the inlining sentence goes |
| Reproducers for G15, G33 and G34 in `VarkaCodegenGiveUpSuite` | 3.2, 3.5 | not started; `PLAN_TASK_188.md` 6 lists them as provokable | **Yes.** Otherwise 3.5 cites readings for two fallbacks |
| The upstream tickets' state | 3.6 | read on the day of publication | **Yes**, and only then |
| Task 188's Varka arm, task 195's first-query cost | - | owed by task 181 | No. This post makes no Varka claim |

The benchmark is a Spark benchmark, not a Varka one: a class in the style of
`WholeStageCodegenSizeBenchmark` with its results file under
`sql/core/benchmarks/`, which `dev/varka_quote_check.py` already searches, run
through Spark's benchmark workflow on GitHub runners, where every headline
number of this project comes from. Row 182 covers it. By the baseline rule the
results file without grouping is committed first, and the grouped one after.
Whether the class goes to apache/spark with SPARK-59783 or into the fork only is
decided with the upstream reviewers; either way the fork carries the file.

## 5. Verification

* Every number in the draft traces under `dev/varka_quote_check.py`, at zero
  orphans.
* Every claim about Spark's behaviour names a census entry and the test that
  pins it, at a revision named in the post; the two that no test can pin, G13
  and G30, are named as readings of the source.
* The draft mentions Varka only in its closing paragraph.
* Bounds 1 to 4 of section 2 appear in the draft, each before the claim it
  bounds.
* The length follows `varka-public-writing-stays-short`: the milestone 5 post
  is the ceiling.

## 6. Explicitly out of this task

* Varka's mechanism, the size ladder's Varka line, `modified-q3` under Varka
  and the accelerators: task 181.
* New measurements beyond the one benchmark in section 4.
* New upstream tickets. The post reports the ones filed; filing more is its own
  decision.
