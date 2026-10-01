# Task 233: when Spark stops compiling your query, and how you'd know

*Row 233 of `PLAN_MILESTONE_6.md`, opened 30 September 2026. Planned 1 October 2026, after the
owner asked whether the post had everything it needed: it had its list and not its numbers. This
plan registers the two investigations that supply them before a word of the post is drafted, in
the order of the milestone's other posts (`PLAN_TASK_210.md`, `PLAN_TASK_181.md`): the question,
what the record holds, the measurements with their predictions, the outline, and done-when.*

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
| TPC-DS | 80.3% | 15.8% | 3.8% | - |
| TPC-H | 78.2% | 20.6% | 1.2% | - |
| SSB | 79.1% | 20.9% | 0 | - |
| golden files | 68.5% | 2.7% | 7.2% | 21.6% |

**Prediction 1 held.** TPC-DS runs 19.7% of its operators outside a stage, most of them columnar
scans, which a stage reads through a conversion by design; the give-ups proper are 3.8%, led by
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

**Prediction 6 was half right.** Either JSON function costs about a microsecond a row, under the
two predicted; but the stage's loss is the constant difference between the two arms, 135 to 224
ns a row, and never the larger part, at 48 entries as at 16. The silent give-up of the census's
most frequent fallback expression costs a fifth of a microsecond a row; the expression itself
costs five times that.

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
expression - a fifth of a microsecond a row - and the opposite for the setting a user reaches for
to keep a wide projection in its stage: a method well under the limit can wait twenty seconds for
C2 and stay slower after it. That is the post's strongest new finding, and the one the first post
did not have.
