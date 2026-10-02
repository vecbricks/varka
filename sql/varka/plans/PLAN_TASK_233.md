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
150-entry stage's consume method (5749 bytes on both: 9.2's 5747 with this section's larger column
offsets), every one of the 150 calls to `UnsafeRowWriter.write` is refused, `failed to inline: size
> DesiredMethodLimit`, on both. The runner's tree, committed beside its results file
(`CodegenFallbackCostBenchmark-jdk25-runner-9v74-inlining-c2-tree.txt`), says where the budget went:
C2 inlines the `addExact` of the first twelve entries, each with its overflow check and boxing, and
refuses the other 138 and all 150 writes; the 8000 bytes of `DesiredMethodLimit` post 2 described
are spent a dozen entries in. So each row of the stage makes 288 calls that the same projection
outside a stage does not make: there the projection is split into small `writeFields` methods, each
compiled with its calls inlined. The same log dates the compile: C2 took the method 13.6 seconds
after the JVM started and printed its tree at 33.1, so the compile itself took about twenty seconds,
which is the wait of 9.2 and 11.3 seen from the compiler's side. What differs between the machines
is what those calls cost: under the logging the 9V74's stage settles at 748 ms against 493 outside,
1.52 times, in the same range as the 7763's 1.57 of 9.2. 11.1's projections are the same mechanism
at smaller widths: a stage's method past a few thousand bytes stops inlining its writes.

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

*2 October:* the committed `compile_wait-*` outputs are now run 36936386636, which adds the
99-entry projection under `wholeStage=false` (13.5); this table is run 36911698296, in the files'
history.

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

## 12. Before the draft, registered 1 October 2026

Reading section 4's outline against sections 9 and 11 left four gaps: the outline has no place
for the post's strongest finding (11.1 and 11.2), and still advises raising `maxFields`, which 9.2
and 11.3 show costs a wait and a lasting slowdown; its advice to move a fallback expression into a
projection of its own was never run, and `CollapseProject` looks likely to undo it; its third
section promises what the interpreter fallback costs, which nothing measured; and two of its
version claims had no source in this plan. The last needs no run: SPARK-59774 makes the 8000-byte
line a warning from 4.4.0, and the line's logger is `CodeCompiler` from 4.3.0 (`PLAN_TASK_210.md`
and the first post, which quote both); 4.3.0 has a first release candidate and no release, so
the post says "from 4.3.0" and "from 4.4.0" of releases to come, and since both changes are after
4.2.0, no 4.1.3 run is needed (section 5).

### 12.1 The rewrite, on stock Spark

`sql/varka/demo/silent-giveups/rewrite.scala`, run by the workflow of 11.3: `from_json` in a
projection of its own over the rest of the projection, `from_json` over an aggregate's output,
and `get_json_object` over the same aggregate.

14. Both rewrites are undone: the split projection is one `Project` again, without `*(n)`, and
    the projection over the aggregate is merged into the final `HashAggregate`, which leaves its
    stage while the partial aggregate stays in one; with `get_json_object` every operator is in a
    stage.

### 12.2 The log level, for one logger

`log_level.scala` provokes 11.5's four INFO give-ups with no appender, under the distribution's
`log4j2.properties.template` plus `log_level.log4j2.properties`, which raises the `codegen`
package and `HashAggregateExec` to INFO.

15. The shell prints all four give-ups, together with one `Code generated in` line per compiled
    class from the same package, and no other INFO line of Spark's.

### 12.3 What the interpreter fallback costs

`CodegenInterpreterFallbackBenchmark`, on a runner: 100, 300 and 1000 entries `x + k` over a
nullable column, outside a stage, compiled and interpreted (the object `NO_CODEGEN` builds, which
is the one a failed compile falls back to).

16. Interpreted is 5 to 15 times slower per row than compiled at every width.

### 12.4 The outline, revised

Section 4's four sections stay in the order a user meets them, with two changes: the first post's
advice on `maxFields` is corrected, and the finding that a stage can be slower is its own
section, since it is neither silent in the census's sense nor logged.

1. **Silent.** A `from_json`, a window, an object hash aggregate, a schema past a hundred
   fields: the operator leaves its stage and only `EXPLAIN` shows it. What it costs: a fifth of
   a microsecond a row for the fallback expression (9.2), less than the expression itself. What
   not to do: move it into a projection of its own (12.1). What to do: a function that generates
   code where one exists (`get_json_object`), or nothing.
2. **In a stage and slower.** A projection of 50 or more columns runs up to 1.65 times slower
   inside a stage than outside one under the defaults (11.1), because C2 stops inlining the row
   writes in a method past a few thousand bytes (11.2); raised past `maxFields`, it waits 17 to
   22 seconds for C2 first on released Spark (11.3). Aggregates win in a stage (11.1). The
   corrected advice: do not raise `maxFields` for a wide projection.
3. **Hidden in the log.** The method past 8000 bytes (the first post, one paragraph), the split
   refusals, the fast hash map: two lines of `log4j2.properties` (12.2), and the 4.3.0 and 4.4.0
   changes.
4. **Logged, with a fallback.** The 64 KB stage that runs its operators one by one (the first
   post's ladder) and the projection that runs interpreted (12.3).
5. **Failing the query.** The top-k over a 1200-branch `CASE WHEN` (11.5).

Closing: how you'd know, over your own history - `dev/varka_codegen_report.py` over event logs
(11.4) - and what Spark could add, as a proposal, not a claim.

### 12.5 Three more, registered 2 October 2026

Reading the outline against sections 11 and 13 left the post's main finding with three gaps. Its
evidence is all cheap entries, `id + k`, where 9.2's 150 `date_add` entries tied; it offers no
remedy, and `hugeMethodLimit` cannot be one, since the 40 and 60-sum aggregates that win in a stage
have larger methods (1746 and 2586 bytes) than the 50-entry projection that loses (1861); and on
stock Spark, 11.3 timed the 99-entry stage without the same projection outside one.

`CodegenWideProjectionBenchmark`, on a runner: projections of 50 and 99 entries, cheap (`id + k`)
and a mix of six kinds of entry (an addition, `date_add`, a string `concat`, a division over a
cast, a null test over a nullable column, `substr`), each under the defaults, with `maxFields`
just below its width so that the projection alone leaves the stage, and with `wholeStage=false`;
timed after fifteen seconds of warm-up, so that C2's code is in. And `compile_wait.scala` gains the
99-entry projection under `wholeStage=false`.

17. The mix loses in a stage at 99 entries by less than the cheap entries do, 1.1 to 1.3 times,
    and ties at 50.
18. Both remedies recover the time outside a stage, within 5% of each other, for every shape that
    loses in a stage.
19. On stock 4.2.0 the 99-entry projection is slower in a stage than with `wholeStage=false` on
    all three JDKs.

## 13. Outcome of section 12, 1 October 2026

### 13.1 The rewrite, on stock Spark

`rewrite-jdk{17,21,25}-output.txt`, run 36919500989. **Prediction 14 held** on all three JDKs. The
projection split in two is one `Project` again in the executed plan, with `from_json` in it and
no `*(n)`, over a `*(1) Range`. Over an aggregate the projection is merged into the final
`HashAggregate`'s result expressions, and that aggregate leaves its stage while the partial
aggregate below it keeps its `*(1)`; with `get_json_object` in its place every operator is in a
stage. A first version of the snippet aggregated a string, `max(js)`, which makes a sort
aggregate that is outside a stage either way and tests nothing; the committed one aggregates
`sum(id)` by a key and builds the JSON from the key.

### 13.2 The log level, for one logger

`log_level-jdk{17,21,25}-output.txt`, run 36917213523. **Prediction 15 held** on all three JDKs,
line for line: the twelve give-up lines of 11.5 appear in the shell with no appender, together
with fifteen `Code generated in` lines from `CodeGenerator`, and no other INFO line of Spark's. So
the post's advice is the two loggers of `log_level.log4j2.properties`, and the price is one line
per compiled class.

### 13.3 What the interpreter fallback costs

`CodegenInterpreterFallbackBenchmark` on a runner that drew an AMD EPYC 7763
(`CodegenInterpreterFallbackBenchmark-jdk25-results.txt`, run 36917218645, with its provenance).

| entries | compiled | interpreted | times |
|--:|--:|--:|--:|
| 100 | 1080 ns a row | 3380 ns | 3.1 |
| 300 | 2320 ns | 9548 ns | 4.1 |
| 1000 | 9032 ns | 39526 ns | 4.4 |

**Prediction 16 was wrong:** three to four and a half times, not five to fifteen. The interpreter's
price per entry grows a little with width (34 ns an entry at 100, 40 at 1000) while the compiled
projection's stays at 8 to 11, so the ratio widens slowly; the first post's thirty-four times was
the interpreter against a stage, over `CASE WHEN`, where the generated code keeps the row's values
in locals - here both arms are outside a stage, and the compiled one already pays the row boundary.

### 13.4 C2's budget at 25 to 99 entries

The review of this plan (1 October) found 11.2 extending its mechanism to 11.1's widths without a
log for them, so `CodegenCompileWaitBenchmark` ran again under C2's inlining log, on a runner
that drew an AMD EPYC 7763 (`CodegenCompileWaitBenchmark-jdk25-runner-inlining-results.txt`,
run 36920247002, with its provenance, and the four trees in `-c2-trees.txt` beside it). No
prediction was registered for it; 11.2's last sentence is the claim under test. C2's verdicts in
each stage's consume method:

| entries | method | `addExact` inlined / refused | `write` inlined / refused | in a stage / outside |
|--:|--:|--:|--:|--:|
| 25 | 936 bytes | 25 / 0 | 25 / 0 | 68 / 154 ms |
| 50 | 1861 | 33 / 17 | 0 / 50 | 363 / 275 |
| 75 | 2786 | 28 / 47 | 0 / 75 | 566 / 401 |
| 99 | 3674 | 23 / 76 | 0 / 99 | 795 / 517 |
| 150 (11.2) | 5749 | 12 / 138 | 0 / 150 | 748 / 493 |

The claim holds, and the table says where the line is. At 25 entries every call is inlined and
the stage wins by better than two to one; from 50 the writes are all refused and the stage loses,
by the 1.3 to 1.6 of 11.1 under the logging too. The budget is spent sooner the wider the method,
not later: `DesiredMethodLimit` bounds the caller's own bytes together with what it inlines, so
the 1861-byte method has room for 33 of its additions and the 5749-byte one for 12. The
aggregates' consume methods are 189 to 539 bytes at 10 to 60 sums, each sum's update in a small
method of its own, and C2 inlines all of them, which is the other half of 11.1: the generated
aggregate is already split the way the projection outside a stage is.

### 13.5 The wide projection's last three questions

`CodegenWideProjectionBenchmark` on a runner that drew an AMD EPYC 7763
(`CodegenWideProjectionBenchmark-jdk25-results.txt`, run 36936386326, with its provenance), and
the same benchmark on the laptop (13.6). Time a row in a stage against the projection taken out
of it (both remedies agree, so one column stands for them):

| shape | runner, in a stage / out | laptop, in a stage / out |
|:--|--:|--:|
| 50 cheap | 402 / 285 ns, 1.41 times slower | 129 / 111 ns, 1.16 times slower |
| 99 cheap | 800 / 521 ns, 1.54 times slower | 256 / 205 ns, 1.25 times slower |
| 50 mixed | 876 / 943 ns, 7% faster | 382 / 432 ns, 12% faster |
| 99 mixed | 1534 / 1814 ns, 15% faster | 716 / 856 ns, 16% faster |

**Prediction 17 was wrong, and it narrows the post's finding.** The mix of six kinds of entry does
not lose in a stage at either width, on either machine; it wins. An entry that does work of its
own - a string built, a date shifted, a division - costs more than the call C2 leaves behind, and
the stage's saving on the row boundary is the larger term. The loss is a property of wide
projections of cheap entries: arithmetic and column copies, the shape of a wide select of
derived columns, not of a projection that computes. **Prediction 18 held:** `maxFields` just below
the width and `wholeStage=false` are within 1% of each other in all eight rows, and both recover
the time outside a stage for the cheap shapes; for the mix they cost what the stage saved. Over a
cached table the two are one setting, since the scan alone is never a stage; under a filter or an
aggregate `maxFields` is the narrower one, and it is the post's remedy, for cheap projections
only. **Prediction 19 held:** on stock 4.2.0 (`compile_wait-jdk*-output.txt`, run 36936386636)
the 99 cheap entries are 1.30, 1.45 and 1.37 times slower in a stage than under `wholeStage=false`
on JDK 17, 21 and 25 (656 against 503 ms, 738 against 510, 273 against 200).

The same run's JDK 25 job drew an AMD EPYC 9V45, a Zen 5 server, and its 150-entry stage waits
12.0 seconds and then runs 1.21 times the time outside a stage (417 against 344 ms), where the
Zen 3 and Zen 4 runners stay 1.4 to 1.6 times slower; the laptop's Zen 5 is closer still (13.6).
The calls C2 leaves in the stage cost less on Zen 5.

### 13.6 The laptop

`CodegenWideProjectionBenchmark`, `CodegenCompileWaitBenchmark` and `CodegenFallbackCostBenchmark`
on the laptop, quiet, one after another (`*-jdk25-laptop-results.txt`, each with its
provenance). `CodegenInterpreterFallbackBenchmark` did not run: its class was not in the tree the
job ran from, and the runner's result (13.3) stands alone.

The wait under the defaults is shorter than any runner's: the 99-entry stage settles after 0.7
seconds, and the 60-sum aggregate is 1.63 times faster in a stage (335 against 546 ms). The
50 and 99-entry cheap projections are 1.15 and 1.19 times slower in a stage once compiled (121
against 105 ms, 254 against 214). The 150-entry stage of 9.2 waits 10.7 seconds for C2 and then
runs 1.06 times the time outside a stage (400 against 378 ms): the laptop result that 11.2
mentioned without a committed run, now committed. So the size of the post's finding depends on
the processor, from a few percent on Zen 5 to half again on Zen 3 and 4, while its direction does
not; the post gives the range and names the machines.

### 13.7 The warm-up test under load

#541's Build of 1 October failed one test, `VarkaWarmupEndToEndSuite`'s first: the projection's
warm-up was released at its 60-second deadline, `RELEASED did not equal COMPILED`, with its last
probe already down from 5,809,968 bytes to 20,720 - one clean probe short of the two the verdict
needs (`VarkaKernelWarmup.run`, which checks the deadline after each probe). The suite ran 50
minutes into the job's test JVM, on a four-core runner. The overnight study asked whether CPU
contention alone reproduces it: the suite back to back in a fresh JVM each time on the laptop,
beside 0, 16, 24, 32 and 48 busy processes, about an hour each
(`VarkaWarmupUnderLoad-laptop-results.txt`, with its provenance).

| busy processes | runs | failed | warm-up, median | slowest |
|--:|--:|--:|--:|--:|
| 0 | 240 | 0 | 0.32 s | 0.38 s |
| 16 | 134 | 0 | 0.85 s | 1.02 s |
| 24 | 90 | 0 | 1.59 s | 2.09 s |
| 32 | 77 | 0 | 1.86 s | 2.67 s |
| 48 | 65 | 0 | 2.27 s | 3.11 s |

It does not: 606 runs, every warm-up compiled, the slowest in 3.11 seconds at twice as many busy
processes as hardware threads. What a fresh JVM cannot have is what CI's had - fifty minutes of
other suites, thousands of generated classes queued for C2 ahead of the kernel's methods. The
failed job's log has no `CodeCache is full`, so the compile queue's backlog looked the likelier
cause; 13.8 tried it and did not reproduce the release either.
The fix does not wait on that: a deadline that releases a shape whose last probe was clean
discards a verdict one probe from done, and a fixed sixty seconds cannot tell a compile that is
slow because C2's queue is long from one that will never come, while the probes already can -
their allocation was falling. `SCOPE_MILESTONE_7.md` item 77 takes both.

### 13.8 Two follow-ups the next morning

**The laptop's interpreter run**, which the night's job missed
(`CodegenInterpreterFallbackBenchmark-jdk25-laptop-results.txt`, with its provenance): the
interpreter is 3.6, 3.6 and 4.4 times the compiled projection at 100, 300 and 1000 entries,
against the runner's 3.1 to 4.4 (13.3).

**The compile queue, tried twice.** 13.7 named a backlog in C2's queue as the likelier cause of
CI's release. The suite ran after `SQLQuerySuite` in one JVM, with C2's queue read through
`jcmd Compiler.queue` every two seconds: the queue never held more than 5 methods, and the
projection's warm-up compiled in 0.28 seconds. Then pinned to four cores (`taskset`,
`-XX:ActiveProcessorCount=4`, a CI runner's count and so its two or three compiler threads),
after `SQLQuerySuite`, `DataFrameSuite`, `DataFrameAggregateSuite` and `WholeStageCodegenSuite`:
the queue reached 40 methods as the warm-up suite began, and the warm-up compiled in 0.33
seconds. Neither reproduces it. The cause of the sixty seconds on CI is open - fifty minutes of
a module's suites in one JVM is more than these two minutes, and a GitHub runner is a shared
virtual machine - and item 77's two changes do not depend on it.

## 14. The draft, 2 October 2026

`POST_MILESTONE_6_GIVEUPS.md`, in the first post's form, follows 12.4's outline as 13.5 narrowed
it: section 2 is about wide projections of cheap columns, and its remedy is `maxFields` below the
width for such a query only. Its three figures, each drawn from committed files when its
script runs:

| figure | what it shows | from |
|:--|:--|:--|
| 1, `fig28-where-operators-run` | per suite, the share of operators in a stage, outside one by design, and outside one for a reason | `VarkaCodegenGiveUps-jdk25-results.txt` |
| 2, `fig29-wide-projection-in-and-out` | in-stage time over out-of-stage time, cheap and mixed, 50 and 99 columns, runner and laptop | `CodegenWideProjectionBenchmark-jdk25-*`, `CodegenCompileWaitBenchmark-jdk25-results.txt` |
| 3, `fig30-first-minute` | each query's time for a minute, the 150-column projection in a stage and out | `compile_wait-jdk*-output.txt`, `CodegenFallbackCostBenchmark-jdk25-results.txt` |

**The examples, 2 October 2026.** The owner asked for more SQL in the post, each with its plan
and why. `examples.scala` prints them on stock 4.2.0 (`examples-jdk{17,21,25}-output.txt`, run
36973845828, the plans the same on all three JDKs): a top-k sort, an object aggregate, a sort
aggregate over a string, `map_filter` and `transform`, the 99-column projection with its method
size (3,670 bytes) and with `maxFields=98`, and the plan of the 3,000-branch stage after its
compile failed, which still shows `*(1)` - `EXPLAIN` gives the plan Spark made, not how it ran,
so the WARN line is that fallback's only sign. Two of them correct section 2's reading of the
source, which was master's: on 4.2.0 `transform` has no generated code - SPARK-37019 gives the
five array higher-order functions code in 4.3.0 - and a sort aggregate with grouping keys has
none either, SPARK-32750, also 4.3.0. The census ran on master, so on 4.2 its silent share
would be larger by those operators; the post says so, and its section 6 lists the two tickets.

## 15. Approved, 2 October 2026

The owner read the rendered draft, with its figures and the examples of section 14, and approved
it. The post is to live at https://vecbricks.github.io/when-spark-stops-compiling-your-query/, and
the first two posts now link it - the first in its introduction, beside the second post's link, the
second where it introduces the first. What remains is the publication itself, on the owner's go:
the page rendered by `dev/varka_post_page.py` into the site, the site's index listing it first, a
link card from Figure 1, and the first two posts' pages rebuilt with the new links; then the site's
commit is recorded here and row 233 is marked done.

## 16. Published, 2 October 2026

The post is live at https://vecbricks.github.io/when-spark-stops-compiling-your-query/, beside the
first two, and the site's index lists it first. It was rendered by `dev/varka_post_page.py` from
`POST_MILESTONE_6_GIVEUPS.md` at `02a9e559057`; the site's commit is `89851f1` in
`vecbricks/vecbricks.github.io`, and its link card is Figure 1 rendered to a 1200 by 630 PNG by
headless Chromium. The first two posts' pages were rebuilt from the same revision and differ from
what was live only by their link to this one. The post's section 6 was reread against the tracker
at publication and needed no change: SPARK-37019 and SPARK-32750 fixed in 4.3.0, SPARK-59774 in
4.4.0, SPARK-33301 open with apache/spark#59069 unmerged. Its spark-shell snippets ran on stock
Spark 4.2.0 under JDK 17, 21 and 25 (11.5, 13.1, 13.2 and 14), so the row's done-when holds.

## 17. The laptop runs, pinned and repeated, 2 October 2026

Section 13.6's laptop runs, and 13.8's interpreter run, were not pinned: `build/sbt runMain`
without `dev/varka_bench_regen.sh`, so the benchmark thread could move between the four Zen 5
cores at 5.16 GHz and the eight Zen 5c at 3.29 GHz. In the owner's idle hour the four ran again,
one at a time, pinned to the fast cores as that script pins them (`taskset -c 0-3,12-15`), from
the classes as at #544's head, then were repeated where the post quotes them: the fallback-cost
and interpreter benchmarks five times, the wide projection twice (its first run moved under 3% on
every row), the compile wait once. Each `*-jdk25-laptop-pinned-results.txt` holds every run as
printed, with its provenance. The pin is not only a choice of cores: on eight processors the JVM
starts 4 C2 compiler threads and 8 parallel GC threads, against 12 and 18 unpinned. From the
fallback-cost benchmark's third run on, other work ran on the other core complex, as its file
says. The rule was set before the repeats: a published laptop number is corrected only where all
five pinned runs fall outside it.

| the post, for the laptop | unpinned | pinned | verdict |
|:--|:--|:--|:--|
| 50 and 99 cheap columns, slower in a stage | 1.16, 1.25 | 1.18 to 1.20, 1.24 to 1.27 (2 runs) | stands |
| 50 and 99 mixed columns, faster in a stage | 12%, 16% | 11 to 17%, 16 to 18% (2 runs) | stands |
| 150 columns in a stage after C2, against outside | 1.06 | 0.74 to 1.00 (5 runs) | corrected |
| the interpreter at 100 to 1000 columns | 3.6 to 4.4 | 3.1 to 4.2 (5 runs) | corrected |
| C2's code arrives (Figure 3) | 13 s | 13.0 to 14.4 s (5 runs) | stands |

**The 150-column stage after C2.** In a stage the time settles at 406 to 423 ms in all five pinned
runs (400 unpinned). Outside a stage is what moves: 407 to 564 ms pinned, 378 unpinned, and the
same projection in the same runs' tables reads 385 to 440, run 1's table 385 against its own
series' 564 - a JIT outcome per JVM, the lottery `dev/varka_bench_repeat.sh` documents, more than
the pin. Over the six runs the stage ends at 0.7 to 1.1 times the time outside one, and the post
now says that. Figure 3 keeps its laptop line, one of the six runs and inside that range.

**The interpreter.** Interpreted over compiled, per row: 3.06 to 3.44 at 100 columns, 3.22 to
3.36 at 300 and 3.61 to 4.20 at 1000 pinned; the unpinned 3.55, 3.56 and 4.36 are above all five
at every width. The post now says 3.1 to 4.2 on the laptop, beside the runner's 3.1 to 4.4.

**13.6's numbers, requoted from the pinned compile wait** (one run): the 99-entry stage still
settles after 0.7 seconds (0.67); the 60-sum aggregate is 1.68 times faster in a stage (355
against 597 ms; 1.63 unpinned); the 50 and 99-entry cheap projections are 1.17 and 1.08 times
slower once compiled (125 against 107 ms, 251 against 232; 1.15 and 1.19 unpinned). The 99-entry
ratio moving by a tenth in one pair of runs is the same lottery, and is why the post's table
quotes the wide-projection benchmark, measured twice pinned, rather than this one.

**Republished.** The page was rebuilt from `POST_MILESTONE_6_GIVEUPS.md` at `a05938dbfb4` and
differs from what was live only in the two corrected sentences; the site's commit is `708fd21`
in `vecbricks/vecbricks.github.io`.
