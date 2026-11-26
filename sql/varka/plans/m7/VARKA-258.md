# VARKA-258: how Spark compiles a query, and why it compiles it again

*Row 258 of `m7/PLAN.md`, `m10/SCOPE.md` item 78. Outlined on 13 October 2026 at the owner's
decision in the halfway review (`m7/PLAN.md` 9.4), so that the post stops losing the comparison
with the next measurable row. Planned in the shape of the earlier posts (`m6/VARKA-210.md`,
`m6/VARKA-233.md`, `m7/VARKA-288.md`): the question, what the record holds, what has to be
measured with its predictions, the outline, and done-when. The measurements wait for their turn
in the review's ordering (9.3, row 8); this file is the skeleton they fill.*

## 1. The question

The reader runs Spark and has read the earlier posts: "The 8000-byte cliff in Spark SQL", "When
Spark stops compiling your query" and "A large CASE WHEN, split only where it has to be". They
know a stage's method can be too large to compile well. They do not know what happens between the
plan and the running code, or that the same query is compiled several times and, with a new
literal, compiled again. The post answers that, and says what it costs them: on a dashboard whose
filter changes, compiling can be most of a short query's time.

Like the earlier Spark posts, it mentions Varka only through a link, here at the end, to the
companion post (row 259, bytecode without source).

## 2. What the record already holds

- **The pipeline in master's source.** Whole-stage codegen writes one Java source string per
  stage. `CodeGenerator.compile` compiles it with Janino and reads the methods' sizes
  (`ByteCodeStats`), which is where the 64 KB fallback and the 8000-byte check of the first post
  are decided. A per-JVM cache of generated classes keyed by the source (`CodeGenerator.cache`)
  holds `spark.sql.codegen.cache.maxEntries` classes, 100 by default, a static setting. The
  source travels to the executors in the stage's task binary; an internal setting since 4.0.0,
  `spark.sql.codegen.broadcastCleanedSourceThreshold`, can broadcast it separately and is off by
  default.
- **Literals in the source.** `Literal`'s code generation writes integer, long, date, timestamp,
  float and double values into the source text, and passes strings, decimals and binaries as
  references (`ctx.addReferenceObj`), so only the first kind changes the cache key.
- **The JIT's wait**, measured by VARKA-233 for "When Spark stops compiling your query": a new
  class runs interpreted, then as C1's code, then C2's.
- **The 3,000-branch stage** of VARKA-233 logged `Broadcasting large task binary with size 1071.4
  KiB`: the source rides in the task binary.
- **The hook, checked by hand on stock 4.2.0 on the laptop and not committed**: `WHERE id = 6`
  after `WHERE id = 5` compiled two new classes, `WHERE s = 'b'` after `WHERE s = 'a'` none, a new
  date two, and a new value of a named parameter `:p` two, since Spark binds a parameter as a
  literal.

## 3. What has to be measured before the draft

Every measurement runs on a stock distribution, Spark 4.2.0, with its output committed under
`sql/varka/demo/` with the release and JDK in its provenance, as the earlier posts' snippets are.

1. **The literal check, committed.** The hand check of section 2 as a snippet with its output: the
   classes compiled per query (Spark's own `CodeGenerator` metrics), for a repeated query, a new
   int, a new date, a new string and a new parameter value.
2. **Compiles per executor.** The same queries on `local-cluster[2,1,1024]`, the classes each
   executor compiles, from the executors' own metrics.
3. **What a new literal costs a short query.** The latency of a short query over a small cached
   table, a repeated literal against a new one each time, on the driver and end to end.
4. **Janino's compile time against a stage's size**, from the codegen benchmark's rungs.
5. **Past the cache's 100 entries.** A loop of 150 distinct queries and the same loop again: how
   many of the second loop's queries compile.
6. **Whether passing such literals as references would pay**, on a patched build: the classes
   compiled and the short query's latency with the int literal passed as a reference, and the
   steady-state cost of reading it from a field instead of a constant.

### 3.1 Predictions, registered before the runs

1. A new int, date or parameter value compiles at least one new class per stage on the driver and
   on each executor; a new string compiles none.
2. On `local-cluster`, each of the two executors compiles the stage's class once per new literal.
3. For a query of under 100 ms over a small cached table, a new literal at least doubles its
   latency against a repeated one.
4. The second loop of 150 distinct queries compiles at least 50 of them again: the cache keeps
   100.
5. Passing the int literal as a reference removes the recompilation, and costs under 5% on a
   steady-state scan of the stage.

## 4. The outline

1. **A dashboard that is slow on the first click of every filter.** The symptom, from section
   3's latency (3): the same query with a new date is slower, every time.
2. **From a plan to running code.** The pipeline in one figure: the plan, one Java source per
   stage, Janino on the driver, the task binary, Janino on each executor, the interpreter, C1, C2.
   Each step with where in Spark it happens (section 2).
3. **Why the same query compiles again.** The cache is keyed by the source, and some literals are
   in the source. The literal check (1) as the post's central table; parameters do not help.
4. **What it costs.** Per executor (2), per query (3), against a stage's size (4), and past the
   cache's 100 entries (5).
5. **What to do today.** What a user can change: a string-typed parameter where the column allows
   it, a larger cache where the driver's memory allows it, fewer distinct stages; each with its
   measured effect or with the reason it was not measured.
6. **What Spark could do.** Literals as references, as strings already are (6), and whether it is
   worth proposing upstream.
7. **A link** to the companion post, for readers who want to see the bytecode itself.

## 5. Done when

The literal check and every other snippet run on stock Spark 4.2.0 with their outputs committed;
the measurements of section 3 are committed with provenance and the predictions of 3.1 scored in
section 9; the post is drafted, kept short (the owner cuts length from Varka's public writing), and
published, with every number tracing under `dev/varka_quote_check.py`.

## 6. Sequencing

1. This outline, and the row marked as outlined.
2. The snippets and measurements of section 3, in one pull request, in the review's order.
3. The draft, then publication, then section 9.

## 7. Explicitly out of this task

- Varka's own compilation, which is the companion post's (row 259) and the closing post's (270).
- Proposing the literals-as-references change upstream: the post says whether it would pay; a
  proposal is its own decision.

## 8. Risks

1. **The cache or the literal handling changes upstream before publication.** The post names its
   release, and the snippets rerun on the release current at publication.
2. **The short-query latency depends on the machine.** The post states the machine and quotes a
   ratio as well as the times.

## 9. Outcome

### 9.1 The measurements, 16 October 2026

The counts ran on the laptop, since they do not depend on the machine: `sql/varka/demo/compiles/
run.sh` runs three `spark-shell` scripts on the stock Spark 4.2.0 distribution under JDK 25 and
writes their outputs beside them, each with its commit. Four runs gave the same counts. The times
are `CodegenRecompileBenchmark` (sql/core, Java on `VarkaBenchmarkBase`), on the fork's Spark with
Varka off, run on a GitHub-hosted runner through `benchmark.yml`.

**1, the literal check** (`literals.scala`, `literals-jdk25-output.txt`). In local mode a new int,
bigint, double, date or timestamp compiled three classes; a new string or decimal none; a new
value of an int parameter three, of a string parameter none; a string value for an int column
three, since it is cast and folded into an int literal. The three are the stage compiled by the
driver, the stage compiled again by the task, and the cached table's batch filter
(`SpecificPredicate`), which holds the value too. The stage is compiled twice in one JVM because the
cache is keyed by the compiling thread's class loader as well as the source (SPARK-44795), and a
task runs under the executor's loader. The hand check of section 2 counted two classes; its
query is not in the record, and one over a table that was not cached, which has no batch filter,
would give two.

**2, compiles per executor** (`executors.scala`, `executors-jdk25-output.txt`). On
`local-cluster[2,1,1024]` a new int compiled one class on the driver and two on each executor (the
stage and the batch filter); a new string and a new string parameter none anywhere.

**3, 4 and 6, the times** (`sql/core/benchmarks/CodegenRecompileBenchmark-jdk25-results.txt`,
its provenance beside it: run 38892282294 on MaxGekk/spark, AMD EPYC 7763, JDK 25, commit
`33e1cbc59b9`). They ran on this repository's Spark with Varka off and Janino pinned, not on the
4.2.0 distribution section 3 named; the classes compiled per query match the 4.2.0 counts (3 for a
new int, date or parameter value, 0 for a string).

- **3.** Over 40 queries of `SELECT count(*) FROM t WHERE <column> = <value>` on 100,000 cached
  rows: 24.92 ms a query with the same int, 41.45 ms with a new one, of which Janino 16.42 ms; a new
  date 42.77 ms (Janino 16.05), a new int parameter 42.57 ms (Janino 15.46), a new string 21.61 ms.
  Best times 35 ms against 55. Nearly all of the added time is Janino; the new class's time in the
  interpreter hardly shows on a query this short.
- **4.** Janino's best of nine compiles of sources new to the cache: 3.91 ms for a projection of
  one entry `id + k` (4470 chars), 9.07 ms for 99 entries (31930 chars), 7.97 ms for an aggregate
  of 10 sums (20456 chars), 17.85 ms for 60 sums (82037 chars). Section 4 uses projections and
  aggregates, not the `CASE WHEN` rungs: on this build a stage with a `CASE WHEN` is already
  compiled while its code is generated, to decide whether to split it (SPARK-33301), so a timed
  compile of its source is a cache hit.
- **6.** `IntSlot`, a test-only expression that reads the value from a field set from the class's
  references, over `i % 1000000 = <value>` for the short query (one row for every value, and no
  batch pruning by the value for either arm) and `i % 1000 = 7` for the steady state; the
  benchmark first checked that both arms count the same rows. With a new value each time the
  literal compiled 2 classes a query and averaged 42.96 ms, the reference none and 19.92 ms, as
  against 19.30 ms for one repeated literal. Steady state over 20 million cached rows: 12.4 ns a
  row with the literal, 12.1 with the reference, one run each.

**5, past the cache's 100 entries** (`cache.scala`, `cache-100-jdk25-output.txt`,
`cache-1000-jdk25-output.txt`). Loops of n queries differing only in an int, run twice: under the
default cache the second loop compiled nothing up to 30 queries (90 classes), 31 of 33 queries
at 33, and every query from 40 on; the 150-query loop compiled 450 classes in each loop. Under
`maxEntries=1000` the second loop compiled nothing at any n up to 150.

**Predictions scored** (3.1).

1. **Held**, with a second class the prediction did not name: one new class a stage on the driver,
   and on each executor the stage and the cached table's batch filter; a new string none.
2. **Held**, with the same batch filter beside the stage on each executor.
3. **Missed on its own query, held on the second.** A new int made `i = <value>` about two
   thirds slower, not twice (41.45 ms against 24.92 on average, best 55 against 35); on the
   `i % 1000000` query of section 6 a new literal took 42.96 ms against 19.30, more than twice.
   The added time is Janino's in both, so the ratio depends on how cheap the repeated query is.
4. **Held, and stronger than predicted**: all 150 of the second loop's queries compiled again, not
   50. A loop larger than the cache evicts each class just before it is needed, and at three
   classes a query in local mode the cache holds about 30 such queries; at 33 queries, 99
   classes, it already evicted, since Guava's cache evicts within each of its four segments.
5. **Held.** The reference compiled no class for a new value, and its steady state was within
   3% of the literal's (12.1 ns a row against 12.4), the reference the faster; one run, under the
   1.3x rerun bar, so the record says no measurable cost rather than a gain.

### 9.2 The draft, 16 October 2026

`POST_COMPILES.md`, from the counts above, with figures 36 (where one query is compiled, read from
`executors-jdk25-output.txt`) and 37 (what the cache keeps, read from the two cache outputs). The
runner's numbers were filled in from the results file of 9.1 the same day; what it still owes is
row 259's published link and the owner's edit.

### 9.3 The times on 4.2.0, 16 October 2026

The owner chose to take every time a reader would quote about their own Spark from the 4.2.0
distribution, since this repository's master differs from v4.2.0 in Janino (3.1.12 against 3.1.9),
in `CodeGenerator`'s cache key and in whole-stage codegen. `sql/varka/demo/compiles/times.scala`
runs the stock sections of `CodegenRecompileBenchmark` (measurements 3 and 4) as a `spark-shell`
script, with the benchmark's queries, shapes and table formats; `run.sh --times` runs it, and the
`compiles-times` job of `varka-demo.yml` runs that on a GitHub-hosted runner under JDK 25. A push
cannot pin the CPU, and run 38899204181 landed on two CPUs: attempt 1 on an AMD EPYC 9V74
(`times-attempt1-jdk25-output.txt`), attempt 2 on an AMD EPYC 9V45 (`times-jdk25-output.txt`).
The post quotes attempt 2 and names its CPU; measurement 6 stays on the fork's benchmark (EPYC
7763), since `IntSlot` is test code, and the post labels it as a separate run on another build.

- **3.** Over 40 queries: 18.87 ms a query with the same int, 39.59 ms with a new one, of which
  Janino 10.96 ms; a new date 41.11 ms (Janino 11.63), a new int parameter 40.39 ms (Janino
  10.78), a new string 26.80 ms with no compile. On the 9V74, 26.88 ms against 48.32, Janino
  15.43 ms. Unlike the fork's run, Janino is only about half of the added time on 4.2.0; the rest
  is outside Janino and was not split further. The classes per query match the counts: 3 for a
  new int, date or parameter value, 0 for a string.
- **4.** Janino's best of nine: 2.82 ms for a one-entry projection (4470 chars), 5.75 ms for 99
  entries (31930 chars), 12.43 ms for 10 sums and 30.72 ms for 60 sums. The projections' sources
  are byte for byte as long as on the fork; the aggregates' are 2.7 to 3.0 times longer (54379
  against 20456 chars for 10 sums, 243154 against 82037 for 60), the whole-stage codegen
  difference between the builds.

**Prediction 3 rescored on 4.2.0. Mixed: held on the 9V45, missed on the 9V74.** A new int took
2.1 times the repeated query's time on the 9V45 (39.59 ms against 18.87) and 1.8 times on the
9V74 (48.32 against 26.88); with the fork's 1.7 times on the 7763 (9.1), the ratio sits around
the prediction's bar of two and moves with the machine, so the record does not call it either
way. The other predictions are counts or ran on the fork, and stand as scored in 9.1.
