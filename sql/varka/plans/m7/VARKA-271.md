# VARKA-271: Spark's own SQL suites with Varka on, Spark's golden files the oracle

## 1. Where this came from

Every check of Varka's answers today compares Varka with something this project wrote: the
reference evaluator, the coverage table, the grammars' random trees, and row 81's corpus of
rewritten date statements. Row 271 adds the one oracle this project did not write. Spark's own
golden-file suite, `SQLQueryTestSuite`, has 374 input files under
`sql/core/src/test/resources/sql-tests/inputs/`, each with a committed `.sql.out` file that
records the schema and rows Spark answers. If Varka changes any answer to a query it serves, the
committed file shows it.

Two other accelerators do this (`m7/READING.md` 2). Comet patches a Spark checkout per Spark
version and runs Spark's suites with Comet loaded; 47 tests are excluded in its 4.1.3 patch,
each with a reason. Gluten subclasses about 480 of Spark's suites and keeps about 450 per-test
excludes. Its known-failures gate fails on a new failure and also when a listed failure starts
to pass, so the list cannot go stale. The record called this "the widest differential available
and not yet in the record as a row" (`m10/SCOPE.md` item 24, 16 September 2026). The plan review
added row 271 to milestone 7. The owner chose it as the next test-strength row on 13 October
2026, and on the same day proposed the literal-lifting step of 3.3.

## 2. The admission check, done

Probes on 13 October 2026, in a scratch worktree at `de011ea9b38` (master as of row 311). The
probe code was a test-only session extension, a query listener and a subclass of
`SQLQueryTestSuite`, all removed afterwards.

**Varka on alone reaches nothing.** Varka serves only Arrow batches (`docs/sql-varka.md`,
"Configuration", `spark.sql.codegen.varka.enabled`: "non-Arrow batches fall back entirely"). The
golden files read `VALUES` lists, temporary views over them, and Parquet tables. A query with no
`FROM` folds to a literal before any operator exists. Measured on nine test files (`date.sql`,
`interval.sql`, `extract.sql` and `int4.sql`, in their default, `nonansi/` and `postgreSQL/` forms
where they exist): 781 query executions, 4 with a Varka node in the plan, 0 batches served. The four
nodes sat over vectorized Parquet batches, which are not Arrow.

**An Arrow source puts Varka under 1,423 query executions.** The probe placed an Arrow-cached
copy of every relation that has a Varka-typed column (`DATE`, `INT` or a year-month interval)
under the query that reads it. This is the same plan node `.cache()` makes with the Arrow
serializer. The whole suite ran in two arms, each in one JVM. Each arm took 56 minutes and ran
790 tests: 779 passed and 11 failed. A further 59 tests are ignored by the suite itself. Under test
(`SPARK_TESTING`), a kernel that fails fails its task instead of falling back to the row path
(`VarkaColumnarToRowExec.failureFallbackForbidden`), so a failing kernel shows in the golden
comparison too. The listener recorded every query execution, setup statements
and commands included. In the 390 execution tests (the analyzer-only variants aside):

| step | query executions |
| --- | ---: |
| recorded | 32,429 |
| read an Arrow-cached relation | 8,047 |
| had a Varka node in the plan | 1,494 |
| had a batch that Varka served | 1,423 |

Varka served 5,323 batches in all, in 105 of the 390 execution tests. Most of the nodes were
filters: 1,464 row-consuming filter nodes, 106 row-consuming projection nodes and 24 columnar
filter nodes, counted as query executions over both kinds of test.

**Varka changed no golden answer.** The arm with the Arrow source and Varka off (the
**cache-only arm**) failed the same 11 tests as the arm with Varka on (the **Varka arm**). Ten
of the 11 pass on unmodified Spark, so the harness causes them:

| test | why it fails |
| --- | --- |
| `explain.sql`, `explain-aqe.sql`, `explain-cbo.sql`, `hints.sql`, `join-nearest-by.sql` | the expected output is a printed plan, and the plan now shows the cache scan |
| `identifier-clause.sql`, `identifier-clause-legacy.sql`, and their two analyzer tests | the probe cached the target table of a `DELETE`, and Spark raised `INTERNAL_ERROR` where it raises `UNSUPPORTED_FEATURE.TABLE_OPERATION` |
| `metadata_column_resolution.sql` | a cached file relation has no hidden `_metadata` columns, and the query reads them |

The eleventh, `udtf/udtf.sql - Python UDTFs`, also fails on unmodified Spark in this
environment.

**Lifting literals more than triples what Varka serves on those files.** A second probe rule ran
on the analyzed plan, before the optimizer. In each `SELECT` with no `FROM`, it replaced each
`DATE`, `INT` or year-month interval literal with a column of a 17-row relation holding that
value, and kept one row with `LIMIT 1`. On the nine test files, the query executions with a
batch Varka served rose from 26 to 88. The lift broke one query in both arms:
`map(1, interval 1 year, 2, interval 2 month)` failed once its key was a column.

**Three traps the probe found:**

1. Varka's warm-up (`spark.sql.codegen.varka.warmup.enabled`, on by default) sends a new
   kernel's first batches to the row path while C2 compiles it. A golden query has a few rows in
   one batch, so with warm-up on Varka serves nothing. The probe turned it off.
2. Spark's `ConvertToLocalRelation` evaluates a projection or a filter over a `VALUES` list at
   plan time, so no operator is left for Varka. The probe excluded that rule.
3. Building the cached plan runs the optimizer on the relation again, which runs the rule again.
   The rule must not apply while it builds a cached plan.

What the check would have rejected: running the suites with Varka switched on and nothing else,
which tests nothing; and a design that took the harness's 10 failures for Varka's.

## 3. The design

### 3.1 The Arrow source

A test-only session extension in `sql/core`'s test tree, `VarkaArrowSource`, adds two rules.

1. **Cache under queries.** An optimizer rule places an Arrow-cached copy under every relation
   that has a Varka-typed column: a `VALUES` relation and a file relation. It does not apply
   under a command (`DELETE`, `UPDATE`, `MERGE`, `INSERT` and the DDL), or to a relation whose
   query reads hidden metadata columns. These two limits remove five of the probe's harness
   failures (the four `identifier-clause` tests and `metadata_column_resolution.sql`). It does not
   apply while it builds a cached plan.
2. **Lift literals** (3.3).

The extension also sets what the probe found it needs: the Arrow cache serializer,
`ConvertToLocalRelation` excluded, and the warm-up off. It registers a listener. After each
query execution, the listener records the batches Varka served and releases the query's caches.

### 3.2 Two arms over the golden files

`VarkaSQLQueryTestSuite` extends `SQLQueryTestSuite` and runs every golden file through the Arrow
source. Spark's committed `.sql.out` files are the oracle, unchanged. One property selects the
arm:

- the **Varka arm**: the Arrow source and Varka on;
- the **cache-only arm**: the Arrow source and Varka off.

A test that fails in the Varka arm and passes in the cache-only arm is a Varka failure. A test
that fails in both is the harness's. A test that passes in both shows no difference; whether it
tested Varka at all is the served report's question (3.5). A test that passes in the Varka arm
and fails in the cache-only arm is a harness failure that Varka hides, and is listed as one.

The suite runs in a JVM of its own. `InMemoryRelation` keeps its cache serializer in one
process-wide field, set at first use, so the suite cannot share a JVM with suites that cache
with another serializer. The nightly runs the Varka arm. The cache-only arm runs only when the
Varka arm fails a test that is not on the exclusion list, and then only over the failing files.
So a normal night costs one arm, and a new failure is attributed the same night.

### 3.3 Lifting literals

A query with no `FROM` reaches no Varka kernel. The lift gives it one. A plan-normalization rule
(`SparkSessionExtensions.injectPlanNormalizationRule`) runs on the analyzed plan, before the
optimizer folds constants. In each `SELECT` with no `FROM`, it replaces each literal that Varka
can read as a column with a column of a relation of 17 equal rows, and keeps one row with
`LIMIT 1`.

- **Which literals.** A literal is lifted only where its parent expression is one the compiler
  fuses with that child as a column. The rule asks `VarkaExpressionCompiler` with the child
  replaced by a column of the same type, so the set cannot drift from what Varka fuses. This
  keeps the `map(...)` key and every other position where a column changes the meaning.
- **Why 17 rows.** One row runs only the kernel's epilogue. A lane group is the machine's vector
  width in int lanes, 4, 8 or 16 rows at 128, 256 or 512 bits (`docs/sql-varka.md`, Glossary).
  Seventeen rows run the dense loop at least once at every width, and then the epilogue.
- **Why the golden file still applies.** Analysis fixes every output column's name before the
  rule runs, and the 17 rows are equal, so `LIMIT 1` returns the query's own schema and row.

Row 81's corpus stays as it is (`VarkaGoldenCorpusSuite`): the seven date-family files, select
statements rewritten as text, fixtures with null rows, and each statement compared on the row
engine and on Varka. 271 covers every file and whole statements, with the golden files as the
oracle.

### 3.4 The exclusion list

`sql/varka/spark_suites/excluded.tsv` lists each test that may fail, tab separated: test name,
arm, kind (`harness` or `environment`) and reason. The suite wraps each test through
`SQLQueryTestSuite.createScalaTestCase`. A listed test is cancelled with its reason when it
fails, and fails as a **stale entry** when it passes. Its line must then be removed (Gluten's
rule, and the matrix's `skips.tsv` rule). `SQLQueryTestSuite.ignoreList` is not used, because an
ignored test never runs and so can never show that its entry is stale.

The first list has six entries: the five tests whose output is a printed plan, kind `harness`,
and `udtf/udtf.sql - Python UDTFs`, kind `environment`.

### 3.5 The served report

`sql/varka/spark_suites/served.json` records, per test, the query executions and the executions
with a batch Varka served. It is regenerated with a flag, as `golden_corpus.json` is. A test
that passes in the Varka arm with no served batch tests nothing about Varka, and the report shows
it. Whether the counts repeat run to run is not yet measured (prediction 4). If they repeat, the
suite compares the report byte for byte. If not, it holds each test to a floor that
regeneration raises.

### 3.6 Deliberately unchanged

- **Spark's code and golden files.** The suite is a subclass in this repository. Comet patches
  Spark because it lives outside it.
- **Spark's other SQL suites** (`DateFunctionsSuite` and the like). The same extension can serve
  them as subclasses, as Gluten does. That is a second step, after the golden files show what the
  harness costs.
- **The JIT tier.** With the warm-up off, the kernels run before C2 compiles them. That is a
  departure from production, where the warm-up is on. Row 274 checks the compiled code.

### 3.7 Registered op counts

None: no emitted code changes.

## 4. Files

| file | what |
| --- | --- |
| `sql/core/src/test/scala/.../VarkaArrowSource.scala` | the extension: the cache rule, the lift rule, the settings, the listener |
| `sql/core/src/test/scala/.../VarkaSQLQueryTestSuite.scala` | the subclass, its two arms, the exclusion list's wrap, the report |
| `sql/varka/spark_suites/excluded.tsv` | the exclusion list |
| `sql/varka/spark_suites/served.json` | the served report |
| `dev/varka_nightly.sh` | the Varka arm, and the cache-only arm over a new failure's files |
| `docs/sql-varka.md` | the suite in "Testing and benchmarks" |

## 5. Tests, and what each is for

1. **A planted fault fails golden tests.** The fault injector `misdescribeAdd` emits `AddDays`
   (`date_add`) against a wrong method descriptor, so the kernel throws `NoSuchMethodError` on its
   first run. Turned on through the emit-options test hook the SQL suites use, with the fallback
   after a kernel failure forbidden as it is under test, it fails the golden tests whose served
   queries call `date_add` in the Varka arm, and none in the cache-only arm. Without it, a suite
   that never fails could mean the harness does not reach Varka. The fault raises an error rather
   than computing a wrong answer; no injector today plants a wrong value, and one is not added
   here.
2. **A stale entry fails.** An entry for a test that passes fails that test with "stale entry".
3. **The exclusion list parses**, and a malformed line fails.
4. **The lift keeps the golden answer.** On the nine probe files, the lifted Varka arm passes the
   same tests as the unlifted one, and the served executions rise.
5. **The cache rule leaves commands alone.** The four `identifier-clause` tests and
   `metadata_column_resolution.sql` pass in both arms.

No pinned fixture moves.

## 6. The measurement

The whole Varka arm, once without the lift and once with it, the funnel of 2 and the time of
each. The Varka arm run twice, to compare the served report.

### 6.1 Predictions, registered before the run

1. With the command and metadata limits of 3.1, the cache-only arm fails exactly the six tests on
   the first exclusion list.
2. The Varka arm fails no test that the cache-only arm passes.
3. With the lift, the query executions with a served batch rise from 1,423 to at least 2,000.
   The nine probe files rose from 26 to 88, but most files have few date or int literals.
4. With the warm-up off, the served report is the same in two runs of the Varka arm.
5. The planted fault of test 1 fails at least one golden test, and every test it fails has a
   served query that calls `date_add`.
6. The Varka arm takes under 70 minutes in one JVM.

## 7. Risks

1. **The harness can change what Spark answers.** A cached relation can change row order,
   statistics or which errors surface. The cache-only arm separates these effects from Varka's,
   and the list's reasons name them.
2. **The lift can change a query's meaning.** The compiler-derived rule of 3.3 limits it, and
   test 4 holds the lifted and unlifted arms to the same golden answers.
3. **Memory.** Each query builds caches. The listener releases them after each execution. A
   failing query whose listener never runs could keep them, and the suite clears what is left
   after each file.
4. **Time.** One arm is about an hour in one JVM. The suite is one class, so a split runs file
   lists across JVMs with `-z`, in the nightly only.
5. **Spark upgrades.** A new Spark version brings new golden files and changes old ones. The
   suite runs whatever files the checkout has, and the list's stale rule shows when an entry no
   longer applies.

## 8. Sequencing

Two pull requests.

1. **271a:** the extension's cache rule and listener, the suite and its two arms, the exclusion
   list, the served report, and the nightly step. Proved by tests 1, 2, 3 and 5, and predictions
   1, 2, 4, 5 and 6.
2. **271b:** the lift. Proved by test 4 and prediction 3. Then section 9.

## 9. Outcome (271a)

Done on 13 October 2026, as 3.1, 3.2, 3.4 and 3.5 describe, without the lift (271b).

* **The suite.** `VarkaSQLQueryTestSuite` runs every golden file through `VarkaArrowSource` in one
  of the two arms `-Dvarka.goldenSuite.arm` selects; without the property it registers one
  cancelled test and starts no session, so the gate and CI, which find it by name, are not
  affected. `dev/varka_golden_suite.sh` builds once, runs an arm in a JVM of its own, and with
  `--attribute` reruns the Varka arm's failures in the cache-only arm and names each one; the
  nightly runs it so (`--skip-golden` leaves it out).
* **Both arms, every golden file**, run side by side on the laptop: 790 tests each (394 golden
  files' execution tests, as many analyzer tests, and the parent's two own tests), 784 passed,
  6 cancelled by `excluded.tsv`, 59 ignored by Spark itself (Python and pandas UDF variants with no
  pyspark, `ignored.sql`), none failed. The Varka arm took 1,995 s and the cache-only arm 1,979 s.
* **What Varka served**, in the first run. 47,323 query executions, 1,583 of them with a batch Varka
  served, 5,841 batches, in 108 tests (106 of them execution tests). Counting the execution tests
  alone, as the admission check did: 36,280 executions, 1,520 served, 5,597 batches, against 32,429,
  1,423 and 5,323. The rise is the cache rule asking the compiler which column types a kernel reads
  (`VarkaExpressionCompiler.readsColumnOf`, new), which adds the long lane's bigint, TIME and
  day-time interval columns to the probe's date, int and year-month set. The tests serving most are
  joins and subqueries over the `postgreSQL` and `subquery` fixtures (`postgreSQL/join.sql` 441
  served executions); the date files serve almost nothing, since their statements have no `FROM`,
  which is 271b's case.
* **The two limits.** No cached relation under a command, and none whose output holds a hidden
  metadata column: the four `identifier-clause` tests and `metadata_column_resolution.sql`, which
  failed in both arms of the admission check, pass in both (test 5).
* **The exclusion list** has the six entries of 3.4. Its parse, its arm matching, the cancel, the
  stale entry and the environment kind are `VarkaGoldenExclusionsSuite`'s five tests (tests 2
  and 3).

**Predictions scored (6.1), 271a's.**

1. **Held.** The cache-only arm failed exactly the six tests on the list, cancelled.
2. **Held.** The Varka arm failed nothing the cache-only arm passed; the two arms' outcomes are
   identical test for test.
4. **Failed, narrowly.** A second full Varka arm gave the same 788 tests, the same 47,323
   executions and the same 1,583 served, but three tests moved: `postgreSQL/int4.sql` served 23
   and 24, `postgreSQL/join.sql` 1,626 and 1,625 batches, `postgreSQL/select_having.sql` 2 and 1
   served (8 and 3 batches). Run alone three times each, `select_having.sql` served 1, 1 and 2,
   and `int4.sql` 24, 23 and 23, so the movement is the file's own and not the suite's order; its
   source is not chased here. So, as 3.5 provided, the suite holds the report as a floor, not byte
   for byte, in the weakest form that still answers 3.5's question: a test that served a batch
   in `served.json` fails when it passes and serves none ("the suite no longer reaches Varka for
   it"). Shown by setting `date.sql`'s committed served count to 5 and running it, which failed
   with that message. The report committed with this section was the second run's; the one
   committed after the review (9.1) is a third run's. The second run took 983 s alone
   on the machine.
5. **Not testable in 271a as written.** The planted `misdescribeAdd` fault failed no test over the
   fourteen files whose statements call `date_add`: none of their served executions calls it,
   because every `date_add` in the golden files is over literals with no `FROM`, the statements
   only the lift reaches. The fault the suite can be shown to reach today is the emitter's other
   injector, `misdescribeWordLiveness`, under which every emission raises: over
   `pipe-operators.sql`, `postgreSQL/date.sql` and `sql-udf.sql`, the three files of a short run
   that had served executions, it failed exactly those three tests (`postgreSQL/date.sql` at its
   query 17, a date filter over `DATE_TBL`) and none of their analyzer tests, which serve nothing.
   So test 1 runs with `--fault misdescribeWordLiveness` until 271b, and prediction 5 is scored
   with `misdescribeAdd` after the lift.
6. **Held.** 1,995 s, 33 minutes, with the cache-only arm and other agents' test JVMs running
   beside it.

**Corrections to sections 3 and 4**, which are a record and are not rewritten:

* 3.1's "Varka-typed column" is the compiler's own list now, both lanes, not the probe's three
  int-lane types.
* 3.4: a passing `environment` entry is not stale. The Python UDTF test passes wherever pyspark
  is installed, so a stale rule there would fail the suite on a better-equipped machine.
* 4: the runner is a script of its own, `dev/varka_golden_suite.sh`, which the nightly calls,
  and the exclusion list's logic is Java (`VarkaGoldenExclusions`), under the house rule that new
  code is Java where Scala is not forced.
* The served report counts every test that runs through the golden-file path, analyzer tests
  included, since an analyzer test of a scripting file executes its statements too
  (`scripting/randomly_generated_scripts.sql_analyzer_test` serves 62).

### 9.1 The review's fixes

A review of the pull request found ten issues; all are fixed, each shown by a run.

* **The suite never writes golden files.** With `SPARK_GENERATE_GOLDEN_FILES=1` set, the parent
  rewrites every `.sql.out` from the answers it gets, which here are Varka's. The suite now refuses
  to start: a run with the variable set aborted in 3 s with that message, and no golden file
  changed.
* **The served report is rewritten only from a run that can set the floor**: the Varka arm, no
  fault planted, and every registered test run, which the suite reads from the tests ScalaTest
  selected. A rewrite over one file aborted with "left 788 tests out" and left the file alone.
* **A plan-printing file compares everything but its plans.** The five whole-file `harness`
  entries cancelled a file on any failure, so a wrong answer in one of its other statements would
  have been cancelled too. They are now of a new kind, `plans`: the file's `EXPLAIN` statements
  whose output differs are left uncompared and counted, every other statement is compared, and the
  entry is stale once none differs. All five files now pass in both arms; the list keeps one
  cancelling entry, the Python UDTF file.
* **The listener releases only the caches the rule built**, tracked by identity, so the caches a
  golden file makes itself (`CACHE TABLE` in `identifier-clause.sql`, `parse-sql.sql`) are left to
  it; both files pass in both arms.
* **A nested deadline stops its command.** `dev/varka_deadline.sh` put the command in a session of
  its own, so an outer deadline's TERM reached the script and not the command, and the golden
  step's 6 GB test JVM would have outlived the nightly's step. It now forwards TERM and INT to the
  command's group: under an outer deadline of 3 s around an inner one of 100 s, the inner command
  was gone after the outer fired, where master's script left it running. The nightly also gives
  each arm 4,800 s, so the build and two arms fit the step's 10,800.
* **Smaller:** fatal errors are no longer caught as test failures (`NonFatal`); the listener bus
  is drained for up to two minutes, and a drain timeout no longer replaces the test's own failure
  or skips its counts; the served report is written and read with Jackson; the docs name
  `misdescribeWordLiveness` as the fault that shows the suite reaching Varka before the lift; and
  the numbers above say which run they come from.

**Both arms again, every golden file**, side by side, 13 October 2026: 790 tests each, 789 passed,
the Python UDTF test cancelled, 59 ignored by Spark, none failed, the two arms identical test for
test, 1,256 s and 1,251 s. The Varka arm served 1,584 of 47,323 query executions and 5,843
batches, in 108 tests; that run's report is the committed `served.json`.

## 10. Outcome (271b), 13 October 2026

Done as 3.3 describes, with two guards the first full run added.

* **The lift.** `VarkaArrowSource.LiftRule`, a plan-normalization rule, rewrites each `SELECT`
  with no `FROM`: every non-null literal of a type a kernel reads, in a position the compiler fuses
  as a column (asked of `VarkaExpressionCompiler` with the literal replaced by a column), becomes a
  column of a 17-row relation, and `LIMIT 1` keeps one row. One literal used twice is one column.
  `-Dvarka.goldenSuite.lift=false` (`dev/varka_golden_suite.sh --no-lift`) turns it off.
* **A bug the first short run found**, fixed before the full run: the relation's row was built by
  mapping over the set of lifted literals, so two literals of different types holding one value
  (`INTERVAL '-1-1' YEAR TO MONTH = INTERVAL '-13' MONTH`, -13 months each) became one value for
  two columns, and Spark's own plan normalization under test threw
  `ArrayIndexOutOfBoundsException`. The row is built from the sequence now; a unit test holds it.
* **Two guards the first full run added.** It failed two tests in both arms, so both were the
  harness's: `time-bucket.sql`, whose `time_bucket` needs a constant bucket width, where the
  lifted interval sum - which Varka fuses, so the parent check passed - made it a column and plan
  validation refused the plan; and `try_arithmetic.sql`, where an overflow inside
  `try_multiply(1, 2147483647 + 1)` reports a different query-context fragment when evaluated at
  run time rather than folded. A lifted projection entry is now kept only if every node of it
  still passes Spark's own `checkInputDataTypes`, where a constant-argument rule lives; and nothing
  under a `try_` function is lifted, neither a `TryEval` nor the runtime-replaceable `try_add`
  family, whose replacement over numbers is the operation in TRY mode with no `TryEval` at all.
* **Tests.** `VarkaLiteralLiftSuite`, seven: a fused expression over literals reads them from 17
  equal rows with one kept and the output's name unchanged; one literal used twice is one column;
  two literals of different types holding one value are two columns of one row; a literal in a
  map key or of a type no kernel reads stays; nothing under `try_` is lifted, as `TryEval` or as
  `try_multiply`; each scan partition holds 17 rows, so the kernel's batch is 17 long; a query
  with a `FROM` is left to the cache rule.

**Predictions scored (6.1), 271b's.**

3. **Failed.** Served executions rose from 1,584 to 1,761 of 47,323, and in the execution tests
   alone from 1,521 to 1,698, in 115 tests against 106; the plan predicted at least 2,000. The date
   files now reach Varka (`date.sql` 0 to 7 served executions, `interval.sql` 0 to 10,
   `extract.sql` 0 to 3, `time.sql` +114), but most golden statements with literals either have no
   type a kernel reads or put the literal where a column is not fused, and the nine probe files'
   rise from 26 to 88 did not extrapolate to the whole suite.
5. **Held, with the lift.** With `misdescribeAdd` planted, over `date.sql`, `interval.sql`,
   `extract.sql` and `int4.sql`, the Varka arm failed `date.sql` and `nonansi/date.sql` at query
   29, `date_add(DATE '2011-11-11', 1)`, and nothing else; the same files pass without the fault.

**Test 4 held.** On those four files the lifted Varka arm passed all 16 tests, as the unlifted arm
and the lifted cache-only arm did; served executions there rose from 24 to 72.

**Both arms with the lift, every golden file**, side by side on 13 October 2026: 790 tests each,
789 passed, the Python UDTF test cancelled, 59 ignored by Spark, none failed, the two arms
identical test for test, 1,353 s and 1,357 s. The Varka arm served 1,761 of 47,323 query
executions and 6,018 batches, in 117 tests; that run's report is the committed `served.json`.
This run is the reviewed version: the 17 rows are split across the scan's partitions, so each
batch is 17 long, and a lifted entry is kept only if the whole entry still compiles.
Prediction 1 holds with the lift as well: the cache-only arm fails only the listed environment
test.

Row 271 is done.
