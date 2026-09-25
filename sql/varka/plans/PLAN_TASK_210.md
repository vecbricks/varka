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
| A committed vanilla benchmark of the large `CASE WHEN`: inside a stage, outside one, and outside one with the calls grouped | 3.5 | **done for the first two arms, section 9.2**: `CaseWhenCodegenBenchmark-jdk25-results.txt` on the 9V74; the grouped arm waits for SPARK-59783 | The grouped arm, when the fork carries it |
| `factoryMode=NO_CODEGEN` against the default on the same shape | 3.4 | **done, sections 7.2 and 9.2**: the runner's file carries the arm; 52.6 times the outside-a-stage cost at 1000 branches | No longer owed |
| The field count: JIRAs carrying "grows beyond 64 KB", by year | 3.1 | **done, section 7.1**: 44 tickets, peaking in 2016 and 2017, four open | No longer owed |
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

## 7. The cheap explorations, 25 September 2026

Two of section 4's owed items needed no quiet machine and were done the day
the outline was written.

### 7.1 The field count

SPARK tickets whose text carries Janino's message "grows beyond 64 KB", read
from the tracker's search API on 25 September 2026 (`project = SPARK AND text
~ "grows beyond 64 KB"`):

| Year | 2015 | 2016 | 2017 | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
| :-- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| Tickets | 1 | 18 | 10 | 6 | 3 | 2 | 1 | 1 | 0 | 0 | 1 | 1 |

44 in all. About 35 are user reports; the rest are sub-tasks of the 2017 fix
wave and a few false positives (a UI ticket, a parser stack overflow). Four
are open: SPARK-33301 (2020, a large `CASE WHEN`), SPARK-34485 (2021, a
streaming stage), SPARK-40701 (2022, `LIKE ANY` with many patterns) and
SPARK-51582 (2025, `ExpandExec` with many projections). The wider search for
"64 KB" or "64KB" anywhere finds 92, 35 of them in 2017, the year of the fix
wave (`PLAN_TASK_205.md` (a)); its 2026 entries are almost all sub-tasks of
SPARK-56908, the upstream umbrella task 205 records, which shrinks generated
code and does not bound the method. "too long to be JIT compiled" appears in
three tickets, all from 2025 and 2026, two of them this project's.

What it gives the post: the reports peak in 2016 and 2017, when Spark failed
loudly at 64 KB, and fall away after the split machinery of 2.3.0. The
8000-byte cliff leaves no such trail because it does not fail; it answers,
slowly, at INFO. Section 3.1 opens on that contrast. The counts are integers
and outside `dev/varka_quote_check.py`'s scope; the search and its date are the
record, and a reader can repeat it.

### 7.2 `factoryMode=NO_CODEGEN` against the default

The question was whether Spark's interpreted projection, which is compiled
Scala, beats the generated method HotSpot refuses to compile. It does not, by
more than fifty times, and the reason is not the interpreter.

The check: the thousand-branch `CASE WHEN` of section 3.5, `SELECT CASE WHEN
v = 1 THEN v * 1 ... WHEN v = 1000 THEN v * 1000 ELSE 0 END FROM (SELECT id %
1000 AS v FROM range(N))`, written to the `noop` sink, on the stock
`spark-4.2.0-bin-hadoop3` distribution with `spark-submit --driver-memory 4g`,
`local[1]`, JDK 25, one configuration per JVM, on the laptop with nothing else
running. Laptop figures: they decide what the runner benchmark measures and
are not quoted by the post.

| Configuration | Rows | Time per run, after warm-up | Per row | What the log says |
| :-- | --: | :-- | --: | :-- |
| default (`wholeStage=true`, `factoryMode=FALLBACK`) | 2,000,000 | 11.4 to 12.1 s | 5.7 us | stage disabled past 64 KB, then `CaseWhen_0$` is 8060 bytes, too long to be JIT compiled |
| `wholeStage=false` | 2,000,000 | 10.9 to 11.2 s | 5.5 us | the same 8060-byte method |
| `factoryMode=NO_CODEGEN`, either `wholeStage` | 20,000 | 5.1 to 6.1 s | 255 to 306 us | nothing: no stage, no generated code |

The first `NO_CODEGEN` attempt, at two million rows, had not finished one pass
after nine minutes. A thread dump of the task thread put it in
`scala.collection.immutable.List.apply`, called from `CaseWhen.eval`
(`conditionalExpressions.scala`, the `while (i < size)` loop that reads
`branches(i)` twice per iteration). The branches of a parsed `CASE WHEN` are a
`List`, so each index walks from the head, and one row costs time quadratic in
the branch count. A sweep of the interpreted `CASE WHEN` at 20,000 rows, min
of three runs:

| Branches | 125 | 250 | 500 | 1000 |
| :-- | --: | --: | --: | --: |
| ms per run | 143 | 373 | 1238 | 6115 |

About fourfold per doubling, which is the square. Upstream master has the same
loop, and the interpreted `eval` of `Murmur3Hash`, `XxHash64` and `HiveHash`
indexes `children(i)` the same way (`hash.scala`); `InterpretedUnsafeProjection`
and `CreateMap` do not, since they index an array and an `IndexedSeq`. The fix
is to index an array built once per expression; whether to file it is the
owner's decision, and the post reports whichever state the tracker shows.

What it gives the post: section 3.4's knob paragraph gets its measurement,
and its point sharpens. The one setting that avoids generated code altogether
makes the wide `CASE WHEN` fifty times slower still, for a reason no user
would guess, so the knob is not a way out of the cliff. The runner benchmark of
section 4 carries the `NO_CODEGEN` arm at a row count it can finish.

## 8. The benchmarks, as built, 25 September 2026

Two Spark-style classes under `sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark/`,
each with a results file under `sql/core/benchmarks/` written by the runners
through `.github/workflows/benchmark.yml` (`jdk=25`, `create-commit=false`, the
artifact committed by hand beside a `-runner-provenance.txt`, as
`VarkaSizeLadderBenchmark`'s was). Neither mentions Varka, so either can go
upstream as it is.

**`CaseWhenCodegenBenchmark`.** The `CASE WHEN` of section 7.2 at 30, 60, 100,
300 and 1000 branches over 200,000 rows, three cases per rung - whole-stage
codegen on, off, and `factoryMode=NO_CODEGEN` - and, above each rung's table,
the stage's largest method from Spark's own compile of it, or "fails to
compile, past 64 KB". The row count is what the interpreted case at 1000
branches can finish: about a minute per iteration on the laptop (7.2). The
first laptop run, at 20,000 rows and so not a timing, placed the rungs: 30
branches is a 2853-byte stage method and 100 branches is already 9433 bytes,
past the JIT limit, which is why 60 is a rung. The fourth case, the calls
grouped, arrives with SPARK-59783 when the fork carries it.

**`CachedTableWidthBenchmark`.** A cached table of 100 and of 101 int columns
over two million rows, and two queries over each: `sum(c1)`, and every column
through the noop sink. Three cases per query - 100 columns, 101 columns, and
101 columns with `maxFields` raised to 101 - with a line above the table
saying whether the scan was columnar. The laptop's run, two million rows,
JDK 25, before the runner's:

| Query | 100 columns (columnar) | 101 columns (row-based) | 101, limit raised (columnar) |
| :-- | --: | --: | --: |
| `sum(c1)` | 18.9 ns/row | 18.1 | 17.0 |
| all columns | 115.5 ns/row | 423.1 | 111.9 |

So the outline's section 3.5 was half right. For a one-column aggregate the
row path costs nothing extra: vanilla turns the batches back into rows before
the aggregate anyway (`ColumnarToRowExec`), and Spark's own
`InMemoryColumnarBenchmark` already shows the two deserializers within 1.2 of
each other. Reading the whole table is where the trap is, at 3.7 times, and
the post's example is that one - `df.cache()` followed by a read of every
column, the shape a cached table is usually made for. The one-column case
stays in the benchmark as the bound on the claim. A first version of the
probe reported the plan of the first case for the third, because a Dataset
keeps the physical plan it was first given; the query is now a fresh
`select("*")`.

Both classes print their verdict lines through the benchmark's own output
stream, so they are in the results file and a reader of the file sees which
path each timing measured without opening the plan.

**The first runner dispatch of `CaseWhenCodegenBenchmark` failed, and the
reason is a finding.** `BenchmarkBase.main` sets `spark.testing` "so the
behavior between running benchmark via spark-submit or SBT will be
consistent", and under that flag `WholeStageCodegenExec` rethrows a compile
failure instead of falling back to its row-by-row operators. So Spark's own
benchmarks never measure the fallback past 64 KB; at 1000 branches the stage
case threw and the run ended with no results file. The class now clears the
property at the start of its suite, since the fallback is what it measures,
and the run is dispatched again. The 300-branch rung, which did complete on
the EPYC 9V45, already shows the shape in the run's log: the stage's method is
past 8000 bytes and never compiled, and the same branches with whole-stage
codegen off, split into methods HotSpot compiles, run about eight times faster
per row; the interpreted case is about four times slower again than the stage.
The numbers themselves wait for the committed file, which the quote checker
holds this plan to.

The cached-table run completed on the same machine
(`CachedTableWidthBenchmark-jdk25-results.txt`): the one-column sum is 17 to
19 ns a row on either path; reading every column is 454.9 ns a row for the
101-column table against 100.6 for the 100-column one and 88.0 with
`maxFields` raised, so the trap is 4.5 times on a whole-table read.

## 9. The first draft, 25 September 2026

`POST_MILESTONE_6_SPARK.md`, written from the outline of section 3 in the
shape of `POST_MILESTONE_5.md`, about 3300 words against that post's 4400.
Every number in it is from a committed results file and the quote checker
reads it at zero orphans: the demo's three JDK outputs, the size ladder's
runner file, the tuning files on the 9V45 and, with the JVM flag off, on the
7763, the range filter's 9V74 file, the cached-table file of section 8, and
the tracker count of 7.1. Every claim about Spark's behaviour names the log
line, the setting text or the plan section that pins it; the census's entry
numbers are not in the body. Each of the six sections ends with a command and
what it prints, and the JVM flag's output and the `EXPLAIN CODEGEN` header
were taken from a run rather than typed.

**What the draft still owes**, marked `[[...]]` in the text:

| Owed | Where | Comes from |
| :-- | :-- | :-- |
| The `CASE WHEN` ladder's numbers: whole-stage on and off at 100, 300 and 1000 branches, and the interpreted case at 300 and 1000 | 4, 5 | `CaseWhenCodegenBenchmark`'s runner file, dispatched 25 September after the `spark.testing` fix (section 8) |
| The link to the second post | closing | task 181 |
| Three figures: the step on three JDKs, the tracker's years, the cached table's six bars | 1, 5 | scripts under `figures/`, in `POST_MILESTONE_5.md`'s form |
| The trailer | a `_SHORT` file, as milestone 5's | written last |
| The tickets' states | 6 | reread on the day of publication |

Two things the draft settled on the way. The `CASE WHEN` sizes inside a stage
quoted in section 5 (2853 bytes at 30 branches to past 64 KB at 1000) are the
lines the benchmark prints above each rung, from the first runner run's log;
the results file will carry the same lines. And the second witness for the
step, the size ladder's runner file, is this fork's vanilla arm on a master
build; the post calls it a September 2026 build of master and names its
crossing separately from 4.2.0's, since they differ by two entries.

### 9.1 The `CASE WHEN` ladder, predicted before its file, 25 September 2026

The owner asked for the draft to carry expected numbers rather than blanks, so
these are registered here first and scored against the runner's file when it
lands. They are for an AMD EPYC 9V45, the machine the first dispatch drew; a
dispatch that lands on an EPYC 7763 reads about twice these throughout (the
tuning files put the two machines at 1747.3 against 892.1 ns a row on the same
rung), and the ratios are the machine-independent part of the prediction.

The basis. The first dispatch's 300-branch rung completed before the run died
at 1000 (section 8): inside a stage, a 32605-byte method that HotSpot never
compiles, about 7000 ns a row; outside a stage, the branches split into
methods HotSpot compiles, about 900; interpreted, about 30700. A row evaluates
half the branches on average, since `v` is uniform over them, so those are
about 47 ns per evaluated branch interpreted and 6 compiled. The stock 4.2.0
check of 7.2 gave the outside-a-stage cost at 1000 branches on the laptop, and
its sweep gave the interpreted case's growth.

| Branches | Stage method | Whole-stage on | Whole-stage off | Interpreted | Why |
| --: | --: | --: | --: | --: | :-- |
| 30 | 2853 bytes, compiled | about 150 | about 200 | about 600 | 15 evaluated branches at 6 ns plus the row's fixed cost; off pays an operator boundary; interpreted is the square of a small number |
| 60 | 5673 bytes, compiled | about 250 | about 300 | about 1800 | 30 evaluated branches; the interpreted case scales with the square from the laptop's 125-branch rung |
| 100 | 9433 bytes, never compiled | about 2400 | about 400 | about 5000 | 50 evaluated branches at 47 ns interpreted against 6 compiled |
| 300 | 32605 bytes, never compiled | about 7000 | about 900 | about 30000 | the rung the first dispatch measured |
| 1000 | fails to compile, past 64 KB | about 10000 | about 6500 | about 300000 | outside a stage: 500 compiled branches plus about 85 calls from an 8060-byte caller that runs interpreted; inside: the same path after a failed compile of a 23000-line class, paid on every run, which at 200,000 rows is about a third of the time; interpreted: the square, from 30700 at 300 |

The ratios, which are what the post's sentences rest on: inside against
outside a stage about six at 100 branches and eight at 300, where the stage's
method runs interpreted; about one and a half at 1000, where both run the
same split code and the difference is the failed compile; the interpreted
case about forty-five times the outside-a-stage cost at 1000 branches, and
about ten times its own cost at 300 for three and a third times the branches.
Scoring: an absolute number is right within a factor of 1.5 on the 9V45, a
ratio within a third.

### 9.2 The ladder's file, and the predictions scored, 25 September 2026

The rerun after the `spark.testing` fix completed
(`CaseWhenCodegenBenchmark-jdk25-results.txt`, with its provenance file), on an
AMD EPYC 9V74, not the 9V45 the predictions were written for. The one rung both
machines measured, 300 branches, says how the two compare: inside and outside
a stage they agree within a tenth (7439.8 and 893.7 ns a row on the 9V74
against about 7000 and 900 in the 9V45's log), and the interpreted case is
about 1.65 times slower on the 9V74 (50734.4 against about 30700), the
pointer-chasing path being the one that feels the older core. Measured, in ns
a row:

| Branches | Stage method | Whole-stage on | Whole-stage off | Interpreted |
| --: | --: | --: | --: | --: |
| 30 | 2853 bytes | 350.4 | 262.1 | 861.6 |
| 60 | 5673 bytes | 193.2 | 257.3 | 2350.6 |
| 100 | 9433 bytes | 2154.7 | 334.3 | 5678.6 |
| 300 | 32605 bytes | 7439.8 | 893.7 | 50734.4 |
| 1000 | past 64 KB | 16439.8 | 13655.9 | 717953.5 |

**The scoring**, against 9.1's rule of a factor of 1.5 for an absolute number
on the 9V45 and a third for a ratio.

* The ratios held where they were the point. Inside against outside a stage:
  6.4 at 100 branches (predicted about six), 8.3 at 300 (eight), 1.2 at 1000
  (one and a half). The interpreted case against the outside-a-stage cost at
  1000: 52.6 (forty-five). All four within a third.
* The absolutes at 60, 100 and 300 held on all three paths, before any
  machine adjustment: the largest miss is the interpreted case at 300, 1.69
  times the prediction, which the 9V74's 1.65 accounts for.
* **Two misses.** The 1000-branch absolutes on every path: 16439.8 against
  10000 predicted, 13655.9 against 6500, 717953.5 against 300000, misses of
  1.6, 2.1 and 2.4, of which the machine explains 1.65 of the interpreted one
  and none of the other two. And the interpreted case's growth from 300 to
  1000 branches, 14.2 times against the tenfold predicted.
* **The 30-branch rung carries warm-up**, not a measurement: the stage case
  at 30 branches (350.4) is slower than at 60 (193.2), and it is the first
  case the JVM runs. The post does not quote it.

**What the misses say.** The prediction for 1000 branches outside a stage was
built on the stock 4.2.0 laptop check (7.2): 500 compiled branch evaluations
plus about 85 calls from the 8060-byte caller that runs interpreted, at about
40 ns a call. The runner puts the calls at about 125 ns each: an invocation
from an interpreted frame into compiled code, with the fold's state tests in
interpreted bytecode between them, costs three times what was assumed. That
makes the finding of section 5 sharper than the outline had it: outside a
stage the cost per row goes from 893.7 at 300 branches to 13655.9 at 1000,
fifteen times for three and a third times the branches, because between the
two the method holding the calls crosses 8000 bytes itself. That is the second
cliff, and it is exactly what SPARK-59783 removes. The failed compile inside a
stage at 1000 branches costs the difference of the two best times, about
560 ms a run, or 2800 ns a row at this row count: a sixth of the time rather
than the third predicted, because the per-row cost under it was twice the
prediction.

The post's `[[expected]]` marks are replaced by the file's numbers, quoted for
the 9V74, and 9.1 stands as written, since a prediction is scored, not edited.
