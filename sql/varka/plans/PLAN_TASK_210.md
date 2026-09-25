# Task 210: The first post, where Spark's code generation gives up

*Opened 25 September 2026 by splitting task 181's outline in two. This plan
carries the outline of the first post only. The draft is written when the
outline's owed items are in, and it will live beside this file as
`POST_SPARK_CODEGEN.md`, in the shape of `POST_MILESTONE_5.md`: every figure a
script under `figures/`, every number from a committed results file.*

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
fallback outside a stage (G32, G34), and Spark reports them at INFO and WARN,
below what a default shell shows.

**The bounds, each from the record, each in the post.**

1. **It is rare in the benchmark suites.** Of 178 TPC-DS and TPC-H queries
   under six configurations, one stage crosses 8000 bytes, `modified-q3`
   (`PLAN_TASK_193.md` 9.1). The post says this in its second paragraph.
2. **It is not rare in the shapes people write.** A wide projection of date
   arithmetic crosses at about 52 entries (`PLAN_TASK_171.md` 9.1), a filter of
   date ranges at about 50 (`PLAN_TASK_172.md` 9.1).
3. **Much of it can be tuned away**, and the post says how:
   `hugeMethodLimit=8000` or `wholeStage=false` turns the step into about 1.1
   to 1.3 times the pre-cliff cost per entry (`PLAN_TASK_192.md` 9.1, 9.4).
4. **It describes one revision.** Every behaviour is pinned by a test at a
   revision the post names, and upstream fixes in flight are listed with their
   state on the day of publication. None is called merged unless it is.

## 3. The outline

Seven sections and a closing paragraph. Each names the committed evidence it
may quote.

### 3.1 The four limits the JVM sets

The 64KB method (G24), the 8000 bytes HotSpot compiles (G26), the 65535
constant-pool entries (G27) and the 255 parameter slots (G28), each with what
Spark does when a generated class meets it. Evidence: the reproducers in
`VarkaCodegenGiveUpSuite` and the INFO line pinned by
`VarkaCodegenCliffLogSuite`. G28's outcome is marked unsettled in the census;
the post states only what a test pins, so it names the limit and where Spark
guards it (G9, G16 to G20) and does not claim what an unguarded overflow does.

### 3.2 How Spark guesses

Moved here from `PLAN_TASK_181.md` 3.2. Spark's configuration text concedes the
problem: "we cannot know how many bytecode will be generated, so use the code
length as metric" (`hugeMethodLimit`'s documentation). The 1024-character split
heuristic (G15); `hugeMethodLimit`, which cannot fire at its default (G25); the
three places a stage does not split at all (G12 to G14); and the history of the
limits (`PLAN_TASK_205.md`: 8000 for no release, 65535 since 2.3.0, the suite
check that has not run under adaptive execution since 3.2, SPARK-59764).
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
limit, because C2 stops inlining once a compilation unit passes its budget.
Those numbers were laptop runs and are not quotable yet; section 4 owes their
committed benchmark.

### 3.5 When it falls back to interpretation

The fallbacks in one place: the method that is never compiled (G26), the
interpreted projection a failed compile degrades to (G32), the callers that do
not degrade and fail instead (G33), the absence of any size check outside a
stage (G34), and the executor compile that has no fallback (G30). What each
logs, and at which level, from the census's "Logged?" column.

### 3.6 What is being fixed upstream

Each ticket with its state on the day of publication, read from the tracker:
SPARK-59764 (the TPC suites' size check under adaptive execution), SPARK-59765
(the benchmark that measures stage sizes), SPARK-59774 (the INFO line of G26
raised to a warning), SPARK-59783 (the calls to split functions grouped), and
SPARK-33301 (a large CASE WHEN split inside a stage, open since 2020).

### 3.7 What a user can do today

The tuning from bound 3, the log level at which G26's line becomes visible, and
how to read `EXPLAIN CODEGEN` for a stage's largest method. From
`PLAN_TASK_192.md` and `PLAN_TASK_203.md`.

**Closing.** One paragraph: a JVM engine that emits bytecode can measure the
method in the unit the JVM enforces, and the second post shows one. The link to
it, and nothing else about Varka.

## 4. What the outline still owes, and what it does not

| Owed | For | State on 25 September | Needed? |
| :-- | :-- | :-- | :-- |
| A committed vanilla benchmark of the split-call case: the caller's size, its compile, and the time per row with and without grouping | 3.4 | not started; row 182 is the natural home, as a case in Spark's own benchmarks | **Yes.** 3.4 quotes no timing of it otherwise |
| The upstream tickets' state | 3.6 | read on the day of publication | **Yes**, and only then |
| G28's outcome settled by a reproducer | 3.1 | unsettled in the census | No. The post names the limit without claiming the outcome |
| Task 188's Varka arm, task 195's first-query cost | - | owed by task 181 | No. This post makes no Varka claim |

## 5. Verification

* Every number in the draft traces under `dev/varka_quote_check.py`, at zero
  orphans.
* Every claim about Spark's behaviour names a census entry and the test that
  pins it, at a revision named in the post.
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
