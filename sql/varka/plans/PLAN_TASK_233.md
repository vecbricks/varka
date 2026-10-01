# Task 233: when Spark stops compiling your query, and how you'd know

*Row 233 of `PLAN_MILESTONE_6.md`, opened 30 September 2026. Planned 1 October 2026, after the
owner asked whether the post had everything it needed: it had its list and not its numbers. This
plan registers the two investigations that supply them before a word of the post is drafted
(sections 10 and 11 add five more, the same day), in the order of the milestone's other posts
(`PLAN_TASK_210.md`, `PLAN_TASK_181.md`): the question, what the record holds, the measurements with
their predictions, the outline, and done-when.*

## 1. The question

The census (`PLAN_TASK_188.md`) lists 34 places where Spark's code generation gives up, 23 with
a reproducer in `VarkaCodegenGiveUpSuite`, and says what each logs: 19 nothing, 7 only at INFO or
DEBUG, which a default `spark-shell` hides, 6 at WARN or ERROR, 2 only in `EXPLAIN` or unknown.
The first post covered one of them, the 8000-byte cliff, and two traps; the second shows three
rows beside Varka's answers. This post is for people who run Spark, groups the rest by what the
user sees - silent, hidden in the log, logged with a fallback, failing the task - and ends each
group with a `spark-shell` snippet, what it costs, and what to do: a rewrite, a setting or an
upgrade. It mentions no Varka, and census entry numbers stay out of its body, as the first
post's rules had it.

What the record does not hold is what such a post lives on: **how often** each give-up happens
to real queries, and **what it costs** when it does. Those are sections 2 and 3. Everything else
the post needs - the reproducers, the log table, the limits' history (`PLAN_TASK_205.md`) - is
committed.

## 2. Investigation 1: how often, over real queries

**What.** `VarkaCodegenGiveUpCensus`, beside `VarkaMethodSizeCensus` in `sql/core`'s test tree and
run the same way (`VARKA_CENSUS=1`, so CI discovers it and runs nothing): the golden-file suite
and the TPC-DS, TPC-H and SSB suites in one JVM, Varka off, each query's final physical plan
walked after it ran. Every operator is counted as inside a whole-stage codegen stage or outside
one, and an operator outside is given its reason by re-deriving `CollapseCodegenStages`' rule
(`WholeStageCodegenExec.scala`, `supportCodegen`): not a `CodegenSupport` operator (the class is
named); a `CodegenSupport` operator whose `supportCodegen` is false (the class, which is the
aggregate, join, generator and union conditions of the census's G5 to G10); an expression that is
a non-leaf `CodegenFallback` (the expression's class is named, G4); more nested output fields than
`spark.sql.codegen.maxFields` (G2); more input fields than it (G2, the other side); and the
structural ones, a columnar scan, a stage root that is never wrapped (G11). The plans are
reached through a `QueryExecutionListener` named in `spark.sql.queryExecutionListeners`, set as a
system property before the suites build their sessions, so no suite is changed; an adaptive plan
is read through `AdaptiveSparkPlanExec.finalPhysicalPlan`, since the golden-file suite runs with
adaptive execution on and the TPC suites, since SPARK-59764, with it off.

**The file.** `sql/core/benchmarks/VarkaCodegenGiveUps-jdk25-results.txt`, with its provenance:
per family, operators counted, operators in a stage, operators outside by reason with the five
most frequent classes or expressions under each, the queries touched by each reason, and the
list of every `CodegenFallback` expression met. Sizes do not depend on the machine, so the laptop
is the right place to take it, as it was for the method sizes.

**What the expression list already says**, read from the source on 1 October 2026 and to be
confirmed by the count: `from_json` (`JsonToStructs`) is `CodegenFallback`, as are `array_sort`
with a comparator, `zip_with`, `map_filter`, `map_zip_with`, `transform_keys`, `transform_values`
and `java_method`; `transform`, `filter`, `exists`, `forall`, `aggregate`, `to_json`, `json_tuple`
and `get_json_object` generate code. So the everyday way to lose a stage silently is one
`from_json` in a projection.

**Predictions, registered before the run.**

1. In TPC-DS the operators outside a stage are under a fifth of all operators, and the leading
   reason is an operator that is not `CodegenSupport` or whose `supportCodegen` is false - the
   window, the sort aggregate with a non-codegen path, the object hash aggregate, the generators -
   not a wide schema and not a fallback expression.
2. No TPC-DS, TPC-H or SSB query has a `CodegenFallback` expression; the golden files have
   hundreds, concentrated in the json, higher-order-function and reflection files.
3. `maxFields` puts fewer than five TPC-DS queries' operators outside a stage, all on wide joins.
4. Across the golden files, at least a quarter of the operators outside a stage are there for a
   reason that logs nothing (G2, G4, G5, G11), which is the post's first section.

## 3. Investigation 2: what the silent ones cost

**What.** `CodegenFallbackCostBenchmark`, a Spark-style benchmark under `sql/core`'s benchmark
package with no Varka arm, in the form of task 210's `CaseWhenCodegenBenchmark`, run on a
GitHub-hosted runner through `benchmark.yml` and committed with provenance. Two cases:

* **One fallback expression in a projection** (G4). The size ladder's shape at 16, 32 and 48
  entries, below the cliff so the stage is compiled, in three arms: the entries alone, inside a
  stage; the entries plus one `from_json` of a short constant JSON string, which takes the
  operator out of the stage; and the entries plus one `get_json_object` over the same string,
  which keeps it in. The slope per entry of each arm is what a date expression costs compiled in
  a stage against what it costs when its operator runs outside one; the difference between the
  second and third arms at one rung is the fallback's own cost against a function that generates
  code.
* **A projection past `maxFields`** (G2). The first post warned that raising the limit lets a
  150-column projection into one stage whose method may pass 8000 bytes, with no number. The
  same projection of 150 cheap entries over a cached table, with `maxFields` at its default (the
  projection runs as a separate operator) and at 200 (it joins the stage), with the stage's
  largest method printed above the rung as the size ladder prints it.

**What the mechanism says to expect.** An operator that leaves a stage for a `CodegenFallback`
expression is not interpreted: `ProjectExec` outside a stage still compiles its projection, and
only the fallback expression itself runs through `eval`. What is lost is the stage: the operator
reads and writes rows at its boundary instead of keeping values in locals, which the first post
priced at an eighth to a fifth on this shape (`wholeStage=false`, `PLAN_TASK_192.md` 9.3, 9.4).
So the silent cost should be that, not the 5x of the cliff or the 34x of the interpreter.

**Predictions.**

5. The per-entry slope of the `from_json` arm is 1.1 to 1.4 times the compiled arm's at the
   runner's width, the `wholeStage=false` band; the `get_json_object` arm's slope is the compiled
   arm's within 5%.
6. `from_json` of a short constant string costs under two microseconds a row on its own, so at
   16 entries the fallback expression, not the lost stage, is most of the second arm's extra
   time, and at 48 entries the lost stage is.
7. The 150-column projection at `maxFields=200` is a stage whose largest method is under 8000
   bytes for cheap entries (`x + k`), and runs 1.1 to 1.3 times faster than at the default; a
   second rung with `date_add(d, k)` entries puts the method past 8000 bytes and the stage
   slower than the default, which is the trap the first post named.

## 4. The outline

Four sections in the order a user meets them, each opening with the number section 2 gives it
and ending with a snippet and what to do.

1. **Silent.** An operator drops out of its stage and the only sign is a missing `*(n)` in
   `EXPLAIN` or a `WholeStageCodegen` box that stops short in the SQL UI: a `from_json` in the
   projection, a window or an object aggregate in the chain, a schema past a hundred fields.
   What it costs (section 3), how to see it (`explain()`, the UI), what to do (move the
   fallback expression to its own projection after the heavy work; raise `maxFields` with the
   method size checked).
2. **Hidden in the log.** The lines a default `spark-shell` never shows: the method past 8000
   bytes (the first post's cliff, one paragraph and a link), the split refusals, the fast hash
   map that was not generated. How to turn the level up for one logger, and from 4.4.0 what
   comes as a warning.
3. **Logged, with a fallback.** The compile that fails past 64 KB and the stage that runs its
   operators one by one; the projection factory's "falling back to interpreter mode". What each
   fallback costs, from the first post's `CASE WHEN` ladder and this task's measurements.
4. **Failing the task.** The generators called directly with no fallback: a top-k sort over an
   expression too large to compile, and the executor-side compile with no fallback, which the
   census could not provoke and the post says so.

Closing: the setting, the rewrite and the upgrade that cover most of the above, and where the
census and the two posts are. If section 2 shows the silent group is common, the closing names
what Spark could add - a per-operator mark in the SQL metrics that an operator runs outside
codegen, or a counter beside `CodegenMetrics` - as a proposal, not a claim.

## 5. The snippets

Every snippet runs on a stock distribution, not the fork: Spark 4.2.0, the latest release
(`archive.apache.org/dist/spark/` on 1 October 2026 lists up to 4.2.0), through the
`varka-demo.yml` pattern that downloads the distribution onto a runner, and 4.1.3 for any line
whose behaviour the record says moved between releases (the logger's name in 4.3.0 is after
both). Each snippet's output is committed under `sql/varka/demo/` with the release and JDK in
its header, as the cliff demo's are, and the post quotes outputs, not expectations. The
reproducers in `VarkaCodegenGiveUpSuite` run under `spark.testing`, where the compile-failure
fallback is off and two split refusals are errors rather than INFO lines; the snippets are the
production behaviour, and the plan expects at least those two to read differently.

## 6. Done when

* `VarkaCodegenGiveUps-jdk25-results.txt` is committed with its provenance and predictions 1 to
  4 are scored against it.
* `CodegenFallbackCostBenchmark`'s runner file is committed with its provenance and predictions
  5 to 7 are scored.
* Every snippet in the post has a committed output on a named release.
* `CodegenCompileWaitBenchmark`'s runner file and the 9V74 inlining file are committed with their
  provenance and predictions 8 to 10 are scored.
* The scripts' outputs on stock 4.2.0 under JDK 17, 21 and 25 are committed and predictions 11 and
  13 are scored; `dev/varka_codegen_report.py` is validated against the census, prediction 12.
* The post is published on the site with every number tracing under `dev/varka_quote_check.py`,
  the first two posts link it, and this row links it.

## 7. Sequencing

1. This plan, and row 233 **Planned**.
2. Investigation 1 on the laptop, its file and the scored predictions, one PR.
3. Investigation 2's benchmark, its runner file and the scored predictions, one PR.
4. The snippets on the runner, their committed outputs, one PR.
5. The draft, its figures (the census's shares as the opening figure; the two benchmarks), the
   review, the publication, as the first post went (`PLAN_TASK_210.md` 9).

## 8. Explicitly out of this task

* Any Varka measurement. The post makes no Varka claim.
* The upstream proposal of section 4's closing, if the census earns it: its own JIRA under row
  204's line, not this post's.
* The 8000-byte cliff beyond one paragraph: the first post has it.

## 9. Outcome

### 9.1 Investigation 1: how often, 1 October 2026

`VarkaCodegenGiveUpCensus` ran the golden-file, TPC-DS, TPC-H and SSB suites in one JVM on the
laptop (`VarkaCodegenGiveUps-jdk25-results.txt`, with its provenance): 22284 final plans, 93787
operators, and 17377 exchanges apart. A first run found the classifier counting two kinds of
thing as give-ups that are not: the golden-file suite runs every query file under several codegen
settings, so an operator planned under `wholeStage=false` was "structural", and the exchanges'
plumbing - `AQEShuffleReadExec`, the reused exchange, `SubqueryBroadcastExec` - and the write
commands' nodes were counted as operators. The committed run names the first apart and walks
through the second.

| | in a stage | a columnar scan | silent give-ups | switched off by a setting |
|:--|--:|--:|--:|--:|
| TPC-DS | 80.3% | 15.8% | 3.9% | - |
| TPC-H | 78.2% | 20.6% | 1.2% | - |
| SSB | 79.1% | 20.9% | 0 | - |
| golden files | 68.5% | 2.7% | 7.1% | 21.6% |

**Prediction 1 held.** TPC-DS runs 19.7% of its operators outside a stage, most of them columnar
scans, which a stage reads through a conversion by design; the give-ups proper are 3.9%, led by
operators that are not `CodegenSupport`: `TakeOrderedAndProjectExec` 115 and `WindowExec` 62.
**Prediction 2 held.** No TPC query has a `CodegenFallback` expression; the golden files have 153
operators taken out by one, `from_json` first with 32, then `map_zip_with`, `json_value`,
`zip_with` and `transform_values`. **Prediction 3 held more strongly than written:** `maxFields`
puts no operator outside a stage in any of the four suites. **Prediction 4 held:** of the golden
files' 8647 operators outside a stage for a reason other than a setting, 6261, 72%, are there for a
reason that logs nothing - an operator that is not `CodegenSupport` (3506: the object hash
aggregate 1239, Python UDF evaluation 882, the window 493), a structural one (1910, almost all a
`LocalTableScanExec` that is never a stage root), `supportCodegen` false (692) and the fallback
expressions (153); the rest are columnar scans.

**What it means for the post.** In the benchmark suites a stage loses almost nothing, so the
silent section is not about frequency in analytic SQL. It is about the specific constructs that
cause it, which a user's query may have and the benchmark suites do not: an object aggregate, a
Python UDF, a window, a JSON function, a higher-order function over maps.

### 9.2 Investigation 2: what the silent ones cost, 1 October 2026

`CodegenFallbackCostBenchmark` on a GitHub-hosted runner, an AMD EPYC 7763, JDK 25
(`CodegenFallbackCostBenchmark-jdk25-results.txt`, run 36890882924, with its provenance). A first
run of the same code on the same CPU model (36886940829) gave the same shape; the committed one
adds the first-minute section that run's finding called for.

**Prediction 5 was wrong.** Losing the stage costs no per-entry multiple. The slope from 16 to 48
entries is 38.6 ns a row per entry with the entries alone in a stage, 40.4 with one `from_json`
taking them out of it and 37.6 with one `get_json_object` keeping them in: within 7% of one
another, not 1.1 to 1.4 times. Outside a stage `ProjectExec` still compiles its projection, so
what leaving costs is the row boundary, once a row, not anything per expression.

**Prediction 6 was half right.** Either JSON function costs about a microsecond a row over the
entries alone, under the two predicted. The two JSON arms differ by a constant 135 to 224 ns a
row, at 48 entries as at 16, and that difference is the stage's loss plus whatever `from_json`'s
`eval` and `get_json_object`'s generated code differ by in their own cost: section 3 read it as
the second, a first version of this section as the first, and the benchmark has no arm that
separates them. What it bounds is enough for the post: the silent give-up of the census's most
frequent fallback expression costs at most a fifth of a microsecond a row, and the expression
itself about five times that. An arm that separates the two - both functions in a projection
outside a stage by another cause, `wholeStage=false` - is a run for the draft if it wants the
split.

**Prediction 7 was wrong three ways.** A hundred and fifty cheap `id + k` entries at
`maxFields=200` are a stage whose largest method is 5747 bytes, under the limit, and run 2.4 times
slower than outside a stage (1902.5 against 779.1 ns a row), not faster. The date entries' method
is 5454 bytes, not past 8000, and their stage ties the projection outside one (857.8 against
847.3).

**Why the cheap stage is slower: the wait for C2, and after it.** A probe on the laptop under
`-XX:+PrintCompilation` showed the stage's 5747-byte consume method compiled by C1 at once and
queued for C2, whose compile took about seventeen seconds; until it lands the stage runs C1's
profiled code. The first-minute section measures it on the runner: a stage new to the JVM runs
its first eleven queries at 1961 to 2145 ms, about twenty-two seconds, then drops to 1230 to 1280
when C2's code arrives - and stays 1.57 times the 783 to 809 ms the projection takes outside a
stage, where on the laptop the two met after C2. So raising `maxFields` for a wide cheap
projection costs 2.5 times for the first twenty-odd seconds of every executor JVM and about 1.6
times after, on this processor. The first post warned only of the 8000-byte case.

**What it means for the post.** The silent section's numbers are reassuring for the fallback
expression - at most a fifth of a microsecond a row - and the opposite for the setting a user
reaches for to keep a wide projection in its stage: a method well under the limit can wait twenty
seconds for C2 and stay slower after it. That is the post's strongest new finding, and the one the
first post did not have.

## 10. Five more investigations, registered 1 October 2026

The two investigations of sections 2 and 3 turned up a finding bigger than the post's plan
expected - a stage under the 8000-byte limit that waits twenty seconds for C2 and stays slower
after it (9.2) - and left the post's "how you'd know" without an answer a reader can run. The owner
asked for all five of the follow-ups proposed the same day; they are registered here with their
predictions before any of them runs; they land in one pull request after #541.

### 10.1 Investigation 3: does the wait for C2 hit stages under the default settings?

9.2's stage only existed because `maxFields` was raised. A projection of 99 columns is in a stage
by default, and so is an aggregate of dozens of sums, the shape a BI tool writes.
`CodegenCompileWaitBenchmark`, Spark-style, no Varka arm, on a runner: for projections of 25, 50,
75 and 99 cheap `id + k` entries and aggregates of 10, 20, 40 and 60 sums grouped by a key of ten
values, each with a source new to the JVM so that its class is compiled from cold, every query of
one shape is timed back to back for 45 seconds with whole-stage codegen on and again with it off,
and the stage's largest method is printed. The time to steady state is the first query within 10%
of the median of the last ten. The same run under `-XX:+PrintCompilation`, through the workflow's
`extra-java-options`, names when C2's compile of each stage method finished.

8. The wait grows with the stage's largest method, and under the default settings it is over five
   seconds for the 99-column projection and the 60-sum aggregate and under two for the smallest
   shape of each.
9. Once C2's code is in, the stage is at least as fast as the same query without whole-stage
   codegen at every width under the defaults: 9.2's 1.57 times after C2 is a property of widths
   past `maxFields`.

### 10.2 Investigation 4: why the wide stage stays slower after C2 on the runner

On the laptop the two arms of 9.2's first minute met once C2's code arrived; on the EPYC 7763 the
stage stayed 1.57 times slower. The benchmark's first-minute section runs on the runner alone
(`-Dcodegen.cost.sections=first-minute`) under `-XX:+PrintCompilation` and C2's inlining log for the
stage's class (`-XX:CompileCommand=PrintInlining`), and the same on the laptop, and the two logs'
decisions for the 5747-byte consume method are compared.

10. The runner's C2 refuses to inline the row writer's and the column accessors' calls into the
    consume method for size (`size > DesiredMethodLimit` or "hot method too big") where the
    laptop's inlines them, so the stage after C2 still pays a call per column a row.

### 10.3 Investigation 5: the wait on released Spark and three JDKs

Everything so far is the fork's master on JDK 25. A spark-shell script,
`sql/varka/demo/silent-giveups/compile_wait.scala`, times the first minute of a new stage the way
9.2 does, with `maxFields` raised and at the default, and the 60-sum aggregate, on stock Spark 4.2.0
under JDK 17, 21 and 25 through a workflow modelled on `varka-demo.yml`.

11. The wait exists on all three JDKs, within a factor of two of each other, longest on JDK 17.

### 10.4 Investigation 6: how you'd know, from your own event logs

The census proves the classification is mechanical. An event log records each execution's plan as
`SparkPlanInfo` - node names, their strings, children - with the adaptive updates and the
execution's modified settings, which is enough to classify an operator offline: inside a
`WholeStageCodegen (n)` node and not under an `InputAdapter` is in a stage; outside, the node's name
says whether it is a `CodegenSupport` operator, its string names any fallback expression, and the
modified settings say whether whole-stage codegen was off. `dev/varka_codegen_report.py` reads an
event log and prints the census's table for it. It is validated against the census: the golden-file
suite run again with `spark.eventLog.enabled`, the script over its log, the two tables compared.

12. The script reproduces the census's golden-file split within 2% of operators per reason, except
    that it cannot tell `supportCodegen` false from the structural rest - both depend on state the
    plan string does not carry - and reports the two together.

### 10.5 Investigation 7: the snippets on stock Spark

Section 5's method, now with the workflow of 10.3: one spark-shell script per section of the post
under `sql/varka/demo/silent-giveups/`, run on stock 4.2.0 under the three JDKs, outputs committed.

13. The reproducers known to behave differently under `spark.testing` read differently on stock
    4.2.0: a stage past 64 KB falls back with `WARN WholeStageCodegenExec` instead of failing, and
    the two split refusals log their INFO lines instead of raising.

### 10.6 Sequencing

10.3 and 10.5 share the new workflow, so it comes first; 10.1 and 10.2 run on the benchmark workflow
meanwhile; 10.4 runs on the laptop. Each investigation's outcome is written under section 11 as it
lands, and the post waits for all five.

## 11. Outcome of the five, 1 October 2026

### 11.1 Investigation 3: the wait for C2 under default settings

`CodegenCompileWaitBenchmark` on a runner that drew an Intel Xeon Platinum 8573C, JDK 25
(`CodegenCompileWaitBenchmark-jdk25-results.txt`, run 36908262583, with its provenance). The
summary line's "steady after" turned out useless on a runner - one slow query near the end of a
series, a collection most likely, puts the last slow query late in every series - so both
programs now print the end of the first five queries in a row whose median is within 10% of the
median of the last ten, and the committed files' summary lines are recomputed from their series
under that definition.

**Prediction 8 was wrong.** Under the default settings the wait is short: every shape in a stage
settles within 2.6 seconds - 99 entries, the widest, after 2.6 - except the first one measured,
25 entries at 9.6 seconds, which ran while the JVM was still compiling Spark's own code. The
twenty-second wait of 9.2 belongs to stages wider than `maxFields` lets in by default.

**Prediction 9 was wrong, and this is the finding.** Once compiled, a projection of 50 or more
cheap entries runs slower inside a stage than outside one, with nothing raised. The medians of the
last ten queries, in a stage and outside one:

| entries | in a stage | outside | | sums | in a stage | outside |
|--:|--:|--:|:-:|--:|--:|--:|
| 25 | 67 ms | 79 ms | | 10 | 92 ms | 122 ms |
| 50 | 167 ms | 130 ms | | 20 | 139 ms | 211 ms |
| 75 | 279 ms | 180 ms | | 40 | 248 ms | 493 ms |
| 99 | 400 ms | 242 ms | | 60 | 357 ms | 755 ms |

So the stage is 1.28, 1.55 and 1.65 times slower at 50, 75 and 99 entries, and the aggregates are
the other way round, the stage about twice as fast from 40 sums. 11.2 says why the projections lose.

### 11.2 Investigation 4: why the wide stage stays slower after C2

The first-minute section alone, under C2's inlining log for the stage's class, on a runner that
drew an AMD EPYC 9V74 (`CodegenFallbackCostBenchmark-jdk25-runner-9v74-inlining-results.txt`, run
36908273868, the log in its job log) and on the laptop.

**Prediction 10 was wrong:** the two machines make the same decisions. In C2's compile of the
150-entry stage's consume method (5749 bytes on both: 9.2's 5747 with this section's larger
column offsets), every one of the
150 calls to `UnsafeRowWriter.write` is refused, `failed to inline: size > DesiredMethodLimit`, on
both. The runner's tree, committed beside its results file
(`CodegenFallbackCostBenchmark-jdk25-runner-9v74-inlining-c2-tree.txt`), says where the budget
went: C2 inlines the `addExact` of the first twelve entries, each with its overflow check and
boxing, and refuses the other 138 and all 150 writes; the 8000 bytes of `DesiredMethodLimit` post
2 described are spent a dozen entries in. So each row of the stage makes 288 calls that the same
projection outside a stage does not make: there the projection is split into small `writeFields`
methods, each compiled with its calls inlined. The same log dates the compile: C2 took the method
13.6 seconds after the JVM started and printed its tree at 33.1, so the compile itself took about
twenty seconds, which is the wait of 9.2 and 11.3 seen from the compiler's side. What differs
between the machines is what those calls cost: under the logging the 9V74's stage settles at 748 ms against 493 outside, 1.52 times,
in the same range as the 7763's 1.57 of 9.2, and the laptop's Zen 5 shows a smaller gap in an
uncommitted run. 11.1's projections are the same mechanism at smaller widths: a stage's method
past a few thousand bytes stops inlining its writes.

### 11.3 Investigation 5: the wait on released Spark and three JDKs

The scripts of 10.3 and 10.5 live together in `sql/varka/demo/silent-giveups/`, run by
`varka-silent-giveups.yml` on stock Spark 4.2.0 under JDK 17, 21 and 25, one runner per JDK; each
`<script>-jdk<n>-output.txt` there is run 36911698296. `compile_wait.scala` times every query of a
shape new to the JVM back to back for 45 seconds, and the wait is read from its printed series as
in 11.1:

| | JDK 17, Xeon 8573C | JDK 21, EPYC 9V74 | JDK 25, EPYC 7763 |
|:--|--:|--:|--:|
| 150 entries in a stage (`maxFields=200`): settles after | 19.0 s | 17.3 s | 21.8 s |
| then, in a stage / outside one | 706 / 388 ms | 692 / 436 ms | 1212 / 698 ms |
| 99 entries in a stage, the defaults: settles after | 2.7 s | 2.1 s | 2.9 s |
| 60 sums, in a stage / `wholeStage=false` | 347 / 719 ms | 316 / 647 ms | 536 / 865 ms |

**Prediction 11 held in what it could test:** the wait is on all three JDKs of released Spark, 17
to 22 seconds, within a factor of 1.3; the first run (36908683124, whose other scripts still
needed fixing) read 17.1 to 19.1. "Longest on JDK 17" cannot be scored: each JDK drew a different
CPU, and the order changed between the two runs. Both of the earlier findings carry over to
released Spark: the wide stage settles 1.6 to 1.8 times slower than outside, and under the
defaults the wait is under three seconds, with the aggregate 1.6 to 2.1 times as fast in a stage.

### 11.4 Investigation 6: how you'd know, from your own event logs

`dev/varka_codegen_report.py` over the event log of a second run of the census's golden-file
family with `spark.eventLog.enabled` (1.8 GB uncompressed, read in about three seconds), against
the census's own table from that run:

| reason | census | the script |
|:--|--:|--:|
| in a stage | 60121 | 60248 |
| switched off by a setting | 18947 | 18950 |
| not a CodegenSupport operator | 3506 | 3543 |
| a columnar scan | 2386 | 2378 |
| a CodegenSupport operator left out | 1909 + 692 | 2687 |
| a CodegenFallback expression | 153 | 151 |

**Prediction 12 held.** Every reason is within 2% of the census except the one the script cannot
split, which it reports as one line 3.3% above the census's two; the plans are 22118 against
22099, the event log recording a few executions the census's listener did not see. The first
version missed the columnar scans, whose parent in a stage is the `InputAdapter` and not the
`ColumnarToRow` above it; the adapter is now walked through, as the census does.

### 11.5 Investigation 7: the snippets on stock Spark

The same run, all three JDKs alike apart from timings. **Prediction 13 held.** A `CASE WHEN` of
3000 branches inside a stage passes 64 KB, logs `ERROR CodeGenerator: Failed to compile` and
`WARN WholeStageCodegenExec: Whole-stage codegen disabled for plan`, and answers
(`fallback-*`); the two split refusals log `INFO CodegenContext: Failed to split subexpression
code` and `INFO HashAggregateExec: Failed to split aggregate code` and answer, as do the method
past 8000 bytes and the struct key the fast hash map turns down (`hidden_log-*`, which catches the
INFO lines with an appender, since the shell's console shows WARN and above). Two more read as the
post needs them: 1000 entries outside a stage with method splitting off fall back to the
interpreter, `WARN UnsafeProjection: Expr codegen error and falling back to interpreter mode`,
over a nullable column (over a non-nullable one the class compiled); and an `ORDER BY` of a
1200-branch `CASE WHEN` under `LIMIT` fails the query, `InternalCompilerException: Code grows
beyond 64 KB` in the top-k's comparator, which has no fallback (`failing-*`). `silent-*` shows the
window and the object hash aggregate with no `*(n)` in the final adaptive plan, `from_json`
taking its projection out of the stage while `get_json_object` keeps it, and the 101st column
taking a projection out.
