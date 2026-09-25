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

**The reader, fixed 25 September 2026.** Experienced Spark users: people who
run and tune queries and do not read `CodeGenerator.scala`. The second post is
for experienced Spark developers (`PLAN_TASK_181.md` 7). The test for where a
finding goes is who can act on it: a user acts on a knob, a version or a query
rewrite, a developer on a mechanism. So this post leads with symptoms, shows
how to see the cause on the reader's own cluster, says what each knob does and
does not do, and names the release that fixes each problem. Census entry
numbers stay out of its body; the census is a link.

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

*Rewritten 25 September 2026 for the reader fixed in section 1. The first
version, written for developers, is in git at 6638c9076b8; its four limits, its
account of how Spark guesses and its census section moved to the second post
(`PLAN_TASK_181.md` 7).*

Six sections and a closing paragraph. Each opens with something the reader has
seen or can run, names the committed evidence it quotes, and ends with the
command that shows it on the reader's cluster.

### 3.1 A query that got five times slower when it grew

Open with the reader's own reproducer: `sql/varka/demo/` (`PLAN_TASK_203.md`),
a `spark-shell` script over a stock distribution whose step between 48 and 52
projected columns is 4.6 to 6.2 times under the defaults, on runners at JDK 17,
21 and 25 (`method_size_cliff-jdk*-output.txt`). Then the shapes people
write: a wide projection of date arithmetic crosses between 52 and 54 entries
(`PLAN_TASK_171.md` 9.1); a filter of date ranges, the shape a BI tool writes
for a set of periods, is at 7048 bytes with 49 ranges and 14299 with 100
(`PLAN_TASK_172.md` 9.1); a `CASE WHEN` of a few hundred branches. Bounds 1
and 2 of section 2 go here, and the field count of section 4: how many users
have filed the "grows beyond 64 KB" message over the years.

### 3.2 Two numbers: 8000 and 65535

What each means for the reader. Past 8000 bytes HotSpot never compiles the
method, so the stage answers correctly and runs several times slower, forever.
Past 65535 the compile fails, the stage falls back to its row-by-row
operators, and the log says so. One sentence on why Spark cannot prevent it:
it generates Java source and cannot count the bytes in advance, so it guesses
from source length. The history (`PLAN_TASK_205.md`) and the census
(`PLAN_TASK_188.md`) are links for the reader who wants the mechanism, with a
pointer to the second post.

### 3.3 How to see it on your cluster

Each as a command and its output, from the reproducers. The `EXPLAIN CODEGEN`
header, which prints each stage's largest method in bytes. The one INFO line
"Generated method too long to be JIT compiled", which `spark-submit` prints at
the default template's level and `spark-shell` hides at WARN
(`PLAN_TASK_188.md` 5, `VarkaCodegenCliffLogSuite`), and which is a warning
once from 4.4.0 (SPARK-59774). `-XX:+PrintCompilation` for the reader who wants
the JVM's own word (`VarkaSizeLadderJitSuite`). The WARN "Expr codegen error
and falling back to interpreter mode" when a projection outside a stage fails
to compile, and the WARN that a stage was disabled past 64 KB.

### 3.4 The knobs, and what each does

Measured, each: `spark.sql.codegen.hugeMethodLimit=8000` turns the step into
1.12 to 1.29 times on the runners (`PLAN_TASK_203.md` 9.2), and the config's
own text already suggests the value; `spark.sql.codegen.wholeStage=false` into
about 1.2 to 1.3 (`PLAN_TASK_192.md` 9.3, 9.4); `factoryMode=NO_CODEGEN`, the
comparison section 4 owes, since Spark's interpreted projection is compiled
Scala and the uncompiled generated method is not; and the JVM flag every
reader tries first, `-XX:-DontCompileHugeMethods`, which does not work: C1
gives up on the method at every size past the limit and at a hundred entries
C2 fails too (`PLAN_TASK_192.md` 9.2). What none of them does: make the default
configuration say so, or keep the stage compiled.

### 3.5 Two traps

The cached table wider than `spark.sql.codegen.maxFields`: its columnar path
counts the whole cached schema, so a table of 101 columns is read as rows even
by a one-column query, while the same Parquet file is read as batches
(`PLAN_TASK_185.md` 8.5). Section 4 owes vanilla's measurement of the cost and
the upstream ticket. And the large `CASE WHEN`, slow in every configuration:
inside a stage it is not split and falls out at 64 KB; outside one the split
functions' calls make the method holding them too long to compile
(SPARK-59783), so every row runs it interpreted. Section 4 owes the benchmark.

### 3.6 Which release fixes what

Each ticket with its state read from the tracker on the day of publication.
On 25 September 2026:

| Ticket | What the reader sees | State |
| :-- | :-- | :-- |
| SPARK-59774 | a warning, once, naming the remedy, instead of the INFO line | fixed, 4.4.0 |
| SPARK-59783 | a wide `CASE WHEN`, `COALESCE` or `IN` outside a stage stays compiled | open, apache/spark#59042 |
| SPARK-33301 | a large `CASE WHEN` inside a stage split into methods | open since 30 October 2020 |
| SPARK-59764, SPARK-59765 | Spark's own size check over the TPC suites runs again | fixed, 4.4.0 (59765 also 5.0.0) |

The post names the release a fix ships in and calls nothing merged that is
not.

**Closing.** One paragraph: the limits are the JVM's, the guessing is the
generator's, and an engine that emits bytecode can measure the method in the
unit the JVM enforces. The link to the second post, and nothing else about
Varka.

## 4. What the outline still owes, and what it does not

| Owed | For | State on 25 September | Needed? |
| :-- | :-- | :-- | :-- |
| A committed vanilla benchmark of the large `CASE WHEN`: inside a stage, outside one, and outside one with the calls grouped | 3.5 | not started | **Yes.** The trap is a claim without it |
| `factoryMode=NO_CODEGEN` against the default on the same shape | 3.4 | not started; one laptop run says whether it is worth a runner benchmark | **Yes.** If the interpreter wins, it is the post's opening |
| The field count: JIRAs carrying "grows beyond 64 KB", by year | 3.1 | not started | **Yes.** Bound 2 rests on our ladders alone otherwise |
| The wide-cache trap measured on vanilla: a one-column query over a 101-column cached table, rows against batches; and the upstream ticket if none exists | 3.5 | not started; the mechanism is pinned on Varka's side (`VarkaSchemaWidthSuite`) | **Yes.** A trap without a number is an anecdote |
| The upstream tickets' state | 3.6 | read on the day of publication | **Yes**, and only then |
| Reproducers for G15, G33 and G34; the inlining evidence; the size distribution | - | moved to the second post (`PLAN_TASK_181.md` 7) | No. Developer material |
| Task 188's Varka arm, task 195's first-query cost | - | owed by task 181 | No. This post makes no Varka claim |

The `CASE WHEN` benchmark is a Spark benchmark, not a Varka one: a class in the
style of `WholeStageCodegenSizeBenchmark` with its results file under
`sql/core/benchmarks/`, which `dev/varka_quote_check.py` already searches, run
through Spark's benchmark workflow on GitHub runners, where every headline
number of this project comes from. Row 182 covers it. By the baseline rule the
results file without grouping is committed first, and the grouped one after.
Whether the class goes to apache/spark with SPARK-59783 or into the fork only is
decided with the upstream reviewers; either way the fork carries the file. The
`NO_CODEGEN` and wide-cache measurements join it as cases, so one workflow run
produces every number in this post.

## 5. Verification

* Every number in the draft traces under `dev/varka_quote_check.py`, at zero
  orphans.
* Every claim about Spark's behaviour names a census entry and the test that
  pins it, at a revision named in the post. The entry numbers are in the
  footnotes and links, not in the body.
* Every section ends with a command the reader can run and the output to
  expect.
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
