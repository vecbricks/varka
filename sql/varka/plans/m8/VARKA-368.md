# VARKA-368: Spark's cast and hash suites through the Varka evaluator

## 0. What exists now (admission check)

From master at `7ea5b6242c7` (30 October 2026), from row 332's plan on the open branch
`varka-332-plan` (PR #1173) and from the plans of rows 343, 350 (branch `varka-350-366-plan`) and
365. Spark's test sources are under `sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/
expressions/`; Varka's compiler under `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/
expressions/codegen/`; the harness under `sql/core/src/test/java/org/apache/spark/sql/execution/`.
The numbers are the laptop's, from one run of the four suites and one of a throwaway probe mixed
into them, both recorded in `m8/results/VARKA-368-probe-local.txt`; the probe is not committed.

1. **The suites check through five helpers of `ExpressionEvalHelper`.** `checkEvaluation`
   (`ExpressionEvalHelper.scala:87-98`) runs the expression interpreted, as a mutable projection
   in both factory modes, as an unsafe projection and after `SimpleTestOptimizer`, whose constant
   folding turns a cast of a literal into a literal (`:341-347`). `checkErrorInExpression`'s
   four-argument form (`:169-198`) intercepts the error and holds it to a condition and its
   message parameters through `checkError`; `checkExceptionInExpression`'s three-argument form
   (`:206-240`) to a class and a message substring. `cmpInterpretWithCodegen` (`:478-515`) is
   what the `checkConsistencyBetweenInterpretedAndCodegen` properties call, ScalaCheck drawing
   ten random literals of a type each. `prepareEvaluation` (`:79-85`) is private. The trait's
   self-type is `SparkFunSuite` (`:48-49`), so a mixin restates it.
2. **The casts are built over literals.** `CastSuiteBase.cast` wraps any value in a `Literal`
   (`CastSuiteBase.scala:55-60`), `TryCastSuite` the same in TRY mode (`TryCastSuite.scala:33-38`).
   `TryCastSuite` overrides the two error helpers as public methods that call `checkEvaluation`
   with `null` (`:40-53`), so a mixin's overrides must be public too, and a TRY error check reaches
   both helpers. `HashExpressionsSuite` builds `Murmur3Hash`, `XxHash64` and `HiveHash` over
   literals; four checks bind a `BoundReference` to a real input row
   (`HashExpressionsSuite.scala:786`, `:835`, `:926`), and 41 calls evaluate with `.eval()`
   directly, outside any helper.
3. **A projection or predicate with no input column never reaches a kernel.**
   `VarkaExpressionCompiler.java:608` returns no kernel when the outputs read no column, and the
   predicate pass the same at `:1010`, as row 332's plan found (its section 7). The probe asked the
   compiler about every check of the four suites as they build it: 55551 helper calls, and 0 of
   them fuse.
4. **With each literal a column, 1780 checks fuse today.** The probe replaced each `Literal` whose
   type has a Varka lane (`VarkaTypes.lane`) by an `AttributeReference` of its type, nullable when
   the literal is null: 6200 checks have such a literal (2224, 1925, 1977 and 74 across the
   Off, On, TRY and hash suites), and 1780 of them fuse (600, 592, 588, 0). By helper: the random
   properties 4110 with 1650 fused (almost all `TIME` to `TIME`), `checkEvaluation` 1891 with 128,
   the two error helpers 199 with 2. The other 49351 checks read only types Varka has no lane for
   (strings, binary, arrays, maps, structs, `TIMESTAMP`, nanosecond timestamps).
5. **The suites and the matrix agree where they meet, and the suites reach forms it lacks.**
   Approximating a bare cast as a check with one cast node between two census types: 594 keys of
   (source form, target form, mode) over 88 ordered pairs of census types. On the 261 whose forms
   are lines of `m8/casts_fused.tsv`, the probe and the file agree on every one; 333 are forms the
   file does not hold (other decimal precisions, `TIME(1)` to `TIME(9)` beside the register's
   three, and 81 casts of a form to itself, which the analyzer removes and the suites build
   directly), and none of those declines where `m8/casts.tsv` says every form of its pair fuses.
   The hash checks with a column, 74, all decline: row 337 has not landed, and `HiveHash` is not a
   census entry.
6. **No "forced on" switch exists.** Nothing in the tree forces the evaluator on; the session path
   adds the planner's choices and the warm-up gate in front of it. Below the session,
   `VarkaExpressionHarness` (row 509) builds `VarkaKernelEvaluator` and `VarkaFilterEvaluator`
   directly (`VarkaExpressionHarness.java:234`, `:301`) through their four-argument constructors,
   which take no class dump, no metrics and no warm-up (`VarkaKernelEvaluator.java:180-187`),
   writes a fixture's rows into Arrow batches with the cache's own iterators, serves each batch
   through `serveBatch` as the exec nodes do, and holds the answer to Spark's interpreted and
   generated projections of the same expression (`:137-161`). A batch the kernel declines goes to
   a fallback that returns nothing (`:394-395`) and is counted, not evaluated. `Outcome.served()`
   (`:97-99`) is true when some batch answered, none declined and no output was left residual.
   The decline reasons come from `VarkaExpressionCompiler.declines` (`:178-200`) and
   `explainPredicate` (`:251-262`), each a `VarkaDecline(reason, expr)`.
7. **Two lifts exist already.** `VarkaArrowSource`'s lift (sql/core,
   `VarkaArrowSource.scala:134-215`) turns a `SELECT` without `FROM`'s literals into a relation of
   `LiftedRows = 17` equal rows, "so a kernel runs its loop over at least one lane group at every
   vector width ... and then its epilogue, where one row would run the epilogue alone", lifts a
   literal only where `fusesAsColumn` says the compiler fuses it, and lifts nothing under a `try_`
   function, whose error carries the query context of the SQL text. `VarkaGoldenCorpusSuite` lifts
   the date golden files' literals into columns of an Arrow-cached fixture.
8. **Gluten's tracker.** Gluten replaces `checkEvaluation` with a one-row `DataFrame` round trip
   in a session that excludes `ConvertToLocalRelation`, `ConstantFolding` and `NullPropagation`
   (`gluten-ut/common/src/test/scala/org/apache/spark/sql/GlutenTestsTrait.scala:97-107`,
   `:144-160`, at `90186c196`), and `TestStats.java` records per test case the class names of the
   operators that fell back. The literal stays a literal there, since Velox evaluates a projection
   of constants; Varka does not (item 3).
9. **Where suites run.** `dev/varka_matrix.sh:183-222` takes every `class Varka...Suite` under
   `src/test/scala` as a Varka suite, so a subclass so named in sql/core runs in the pull
   request's Varka-scoped sql jobs, split by `sql/varka/ci/suite-times.tsv` (a suite missing there
   takes the median), and under the option configuration those jobs then run. The one scheduled
   nightly on GitHub is `varka-fuzz.yml` (02:00 UTC), which compiles catalyst's tests only and
   reports a failed scheduled run through its `report-failure` job; `dev/varka_nightly.sh` is the
   laptop's; `varka-master.yml` runs Varka's suites after each merge. No workflow runs a sql suite
   on a schedule today.
10. **The four suites unchanged** take 52.8 s for 456 tests on the laptop
    (`m8/results/VARKA-368-probe-local.txt`).
11. **Row 365's answer (iii) sets the decimal rule the kernels follow**: vanilla Spark's column
    path, `ArrowColumnVector`'s `BigDecimal`-backed value, for the ANSI and TRY casts of a decimal
    to `INT`, `SMALLINT` and `TINYINT` (`VARKA-365.md` 9.1). The harness's Spark side evaluates the
    fixture's own rows, not the rows read back from Arrow.

### What differs from the row text and the milestone plan

- **"Forced on" is the harness, not a switch**: no configuration exists or is added (item 6).
- **A departure from the row's text: "constant folding excluded" excludes nothing.** The row
  names the exclusion, as Gluten's session does (item 8). Below the session no optimizer runs, so
  there is no rule to exclude, and the lift makes each literal a column, which is what keeps the
  expression from being a constant. Spark's own optimizer arm of `checkEvaluation` stays as it
  is, folding included, since it is Spark's check and not Varka's.
- **Spark's checks are kept and Varka's added beside them**, where Gluten replaces them (item 8):
  the subclass must pass Spark's assertions and Varka's both.
- **The record is per form, with no counts** (2.6): ScalaCheck's literals change every run.
- **The suites reach 333 forms the matrix does not hold** (item 5): recorded, and checked
  against `casts.tsv`'s claims only (2.7). Whether the registry's forms grow to hold them is the
  owner's scope choice (section 8).
- **The random checks run nightly, not in the Build** (section 4).
- **The hash half waits on row 337** (section 5): until 337 lands every hash declines.

*The lead's decisions on the plan's five open points, 30 October 2026, after the reviewer: 17
rows a literal; a decline fails the diff only where `casts_fused.tsv` says the form fuses, with an
expected-declines list under the option matrix's stale rule; the fixed-value checks in the Build
and the random ones in the nightly; the forms outside the matrix recorded and checked against
`casts.tsv` only, the registry's gap put to the owner; the harness alone. Sections 2 to 9 carry
them.*

## 1. Goal and non-goal

Every check of `CastWithAnsiOffSuite`, `CastWithAnsiOnSuite`, `TryCastSuite` and
`HashExpressionsSuite` that holds a literal of a type Varka reads also runs through the production
evaluator with those literals as columns, and holds the kernel's answer to Spark's; each bare cast
and hash the suites exercise is recorded fused or declined per form and mode, with the reason; and
a form the matrix calls fused that the suites find declined fails a check. Not this row: making
any cast or hash fuse (rows 332, 343, 350, 364, 337), Spark's other expression suites, a session
route (section 7), checks that bypass the helpers (`HashExpressionsSuite`'s `.eval()`
calls, `CastSuiteBase.scala:1658` and `:1662`'s `evaluateWithoutCodegen`).

## 2. Design

### 2.1 Four subclasses and one trait

In sql/core's Scala test sources, package `org.apache.spark.sql.execution` beside the harness
(sql/core already reaches catalyst's test classes: `VariantSuite` and `STExpressionsSuite` mix in
`ExpressionEvalHelper`):

    class VarkaCastWithAnsiOffSuite extends CastWithAnsiOffSuite with VarkaSuiteArm
    class VarkaCastWithAnsiOnSuite extends CastWithAnsiOnSuite with VarkaSuiteArm
    class VarkaTryCastSuite extends TryCastSuite with VarkaSuiteArm
    class VarkaHashExpressionsSuite extends HashExpressionsSuite with VarkaSuiteArm

`VarkaSuiteArm` is a thin Scala trait (`self: SparkFunSuite =>`) because it overrides Scala
methods of Scala suites: `checkEvaluation`, the four-argument `checkErrorInExpression` and the
three-argument `checkExceptionInExpression` (public, item 0.2), and `cmpInterpretWithCodegen`. Each
override calls the suite's own first, unchanged, then hands the expression and what the suite
expected to `VarkaSuiteCheck`, a Java class beside the harness that does the work of 2.2 to 2.6.
The two- and three-argument overloads delegate to the overridden forms, so every route arrives.
Within one test, a check already run (the same expression and input row) is not run again, which
removes `TryCastSuite`'s second arrival (item 0.2).

### 2.2 The lift: a literal becomes a column

`VarkaSuiteCheck` prepares the expression as `prepareEvaluation` does without its serialization
round trip (`replace` of each `RuntimeReplaceable`, then `ResolveTimeZone`), and replaces every
`Literal` whose type has a Varka lane by an `AttributeReference` of its type: nullable, and every
row null, for a null literal, so a null cast is a null column; not nullable otherwise, as
`VarkaArrowSource`'s lift has it. Literals of other types stay literals. Unlike that lift, it
lifts under TRY, which is half of what the row is for: below the session there is no SQL text and
no query context on either side, so the reason that lift skips `try_` does not apply. A lifted
expression that no longer resolves (an argument that must be foldable) is not run, and the check is
recorded `unresolved`. A `BoundReference` over a real input row becomes a column holding that
row's value at its ordinal (item 0.2's four hash checks).

The fixture is `VarkaHarnessPlans.Fixture(columns, rows)` with the lifted columns, an int `rid`
column numbering the rows, and `LiftedRows` (17) equal rows, so the kernel runs a full lane group
and its epilogue at every width; one row would never fill a lane. `VarkaLiteralLiftSuite` holds
the golden suite's lift to the same count (`VarkaLiteralLiftSuite.scala:53`).

### 2.3 Each check through the harness

A check whose lifted expression reads no column (no literal had a lane) is recorded
`no-varka-type` and not run. Otherwise:

- **A non-`BOOLEAN` result** runs through `VarkaExpressionHarness.projection` with the
  `COLUMNS` consumer, the columnar node's path, as `Alias(lifted, "c")`.
- **A `BOOLEAN` result** runs as two filters, the lifted expression and its `NOT`, through
  `VarkaExpressionHarness.filter`, which keys rows by `rid`: true where the first keeps the row,
  false where the second does, null where neither does. A boolean value output is row 27's; when
  it lands the projection replaces the two filters, as `VarkaCastMatrixSuite.fuses` will.
  If the `NOT` declines while the expression fuses, false and null cannot be told apart: the
  check then compares the rows kept as true only, and records the second filter's decline.
- **The compile verdict** is asked once per check, of the compiler alone: `compile` (or
  `compilePredicate` with no residual conjunct), and when it declines, the reasons from
  `declines` or `explainPredicate` (item 0.6), as `VarkaCastMatrixSuite` asks the matrix.
- **The conf** is the test's own: the arm clones `SQLConf.get()` at the check, as
  `VarkaCoverageHarnessSuite.conf` does, so a test's `withSQLConf` (a session time zone, a
  legacy flag; eleven in the four suites) reaches the batch writer, `currentEmitOptions()` and
  Spark's side alike.

The harness gains two things. First, its Spark side evaluates the rows as the fallback reads them,
back from the Arrow batch through `ArrowColumnVector`, rather than the fixture's own objects, so
the answer it holds the kernel to is vanilla Spark's column path, the rule row 365 settled (item
0.11). Second, an optional fallback that evaluates Spark's projection over the declined batch's
rows, as the row node does, and returns the rows or the error raised, so 2.5 has something to
compare. Both are options of the existing entry points; the suites that use the harness today
keep their behaviour.

The record and every check run at the defaults; under another option configuration of the
Build's step or the option matrix, the answers are still held to Spark's, but the record is not
compared, as `VarkaAnsiForm`'s reach check runs at the defaults only.

### 2.4 Fused, declined, and what a failure means

Each run check ends in one of these, per batch and per output:

| outcome | meaning | the test |
|---|---|---|
| answered, equal | the kernel answered and equals Spark | passes |
| answered, differs | the kernel's answer is not Spark's | **fails: a Varka bug** |
| answered a raising row | the kernel answered a row Spark raises on | **fails: a Varka bug** |
| kernel path threw | not a decline; `serveBatch` raises `INTERNAL_ERROR` | **fails: a Varka bug** |
| raised | the batch declined with a value status and Spark raises on the row | passes (2.5) |
| batch-declined | the batch declined on a row Spark answers | passes; recorded (2.7) |
| compile-declined | no kernel, or the output left residual, with the reason | passes; recorded |
| Spark disagrees | Spark's interpreted and generated projections differ | not compared |

"Answered, differs" compares by the harness's rule, floating values by raw bits, and also by the
suite's own `checkResult` against what the suite expected (its `Spread` and NaN rules), so the
subclass passes the suite's assertion through Varka and Spark's answer bit for bit. A difference
between Spark's column-path answer and the suite's expected value (the read path of item 0.11, or
a lift that changed the expression's meaning) is not Varka's: such a test is listed in the
exclusions of 2.6 with its kind and reason, and fails as stale when the difference goes. A
compile or batch decline never fails a test by itself; it fails the diff of 2.7 when the matrix
says the form fuses.

### 2.5 Errors

A kernel never raises: a batch holding a value Spark raises on declines through its guard, and the
row path raises (`VARKA-332.md` 0 item 3, 2.3). So below the session there is no error of Varka's
own to compare, and what the subclass checks is three things:

1. **The kernel answered no raising row** (2.4). This is the check that catches a missing guard.
2. **The decline is the guard's**: the status the batch declined with is a value status
   (`VALUE_RANGE`, the decimal slot's), not a refusal or an internal one, recorded as `raised`
   with the status, as VARKA-279 reads the status off its fallback event.
3. **The error the user sees is Spark's**: the harness's fallback (2.3) evaluates Spark's
   projection over the declined batch's rows read from Arrow, as the exec node's row path does,
   and its error is compared with Spark's evaluation of the suite's own literal expression: a
   `SparkThrowable` by `getCondition` and `getMessageParameters`, as `checkError` compares them,
   with the SQLSTATE; any other exception by class and message (the harness's `sameError`). Then
   it is held to the suite's own expectation: the condition and parameters
   `checkErrorInExpression` was given, or the class and message substring of
   `checkExceptionInExpression`. Under TRY the suite expects `null` and the kernel must answer
   it, so no error arises.

The third check is equal by construction while the fallback is Spark's own projection; what it
proves is that the Arrow read and `serveBatch`'s dispatch hand the user Spark's error, not a
wrapped or a lost one. The error a query raises end to end, through a session, the planner and
the cache, is compared by the golden-file suite and `VarkaCoverageDifferentialSuite`, not here
(section 7).

### 2.6 The record

Two files, beside the matrix's, written by the subclasses when `VARKA_SUITES_REGEN=true` and
otherwise compared with what the run found, failing with the regeneration command as
`VarkaCastMatrixSuite` does:

- **`sql/varka/plans/m8/casts_suites.tsv`**, one line per suite, form and mode of each bare cast
  the suites exercise: `suite`, `from`, `to` (census names, or `-` outside the census),
  `from_type`, `to_type` (as `casts_fused.tsv` writes them), `mode`, `verdict` (`fused`,
  `declined` or `unresolved`) and `reason` (the decline reasons, sorted and joined: only
  `VarkaDecline.reason`, never its `expr`, the expression's SQL text, and with any `#` expression
  id stripped, so that a line does not move from run to run; `DeclineSink.java:245-261` builds
  both fields). A cast is
  bare when the lifted expression is exactly `Cast(column, to, mode)`, possibly under its alias;
  every other check (a cast of a cast, a cast inside a hash, a cast with a literal child of no
  lane) is run and held to Spark but not keyed.
- **`sql/varka/plans/m8/hash_suites.tsv`**: `suite`, `function` (`hash`, `xxhash64`,
  `hive_hash`), `argument_types`, `verdict`, `reason`, for a hash whose every argument is a lifted
  column.

**No counts and no batch outcomes are committed.** The random properties draw new literals every
run, and `HashExpressionsSuite`'s random rows and seeds too (`:1008-1035`), so a count or a set of
batch statuses would move run to run; the compile verdict of a form does not depend on its
values. The batch outcomes of each run go to `target/varka-suites/batches.tsv`, which CI keeps as
an artifact, and feed the expected-declines check of 2.7. Each suite owns its lines, so the four
can run in different jobs. The record's lines come from the fixed-value checks of the Build; the
nightly's random checks add no line, and a form that only they reach is written to the run's
artifact, not to the record.

**The exclusions**, `sql/varka/spark_suites/expression_excluded.tsv`, take the golden suite's form
(`VARKA-271.md` 3.4): test name, suite, kind (`read-path`, `lift`, `spark`) and reason. A listed
test that fails on its listed kind is cancelled with the reason; one that passes fails as stale.
None is listed in advance.

**What reads the record.** `dev/varka_census.py` reads `casts_suites.tsv` and writes a `suites`
column into `m8/casts.tsv` per pair, as it writes `fused_today` from `casts_fused.tsv`: the modes
in which every form the suites exercise fuses, and how many forms they exercise per mode. The
census's `cast`, `try_cast`, `hash` and `xxhash64` entries already name row 368. In
`docs/sql-varka.md`'s list of Spark's suites run with Varka, a bullet beside the golden-file
suite's says what the subclasses check, how a literal becomes a column, and where the two files
are.

### 2.7 The diff against the matrix and the census

`dev/varka_census.py`'s `cast_errors`, which holds Comet's four-way rule today, gains two rules over
the committed files, so it runs wherever `--check` runs (the docs job of every Build) without a
JVM:

1. **A form `casts_fused.tsv` calls fused in a mode, which the suites exercise bare in that mode,
   is `fused` in `casts_suites.tsv`**; else the error names the form, the mode and the suites'
   reason. This is the row's done-when.
   A batch that declines on a row Spark answers, in a fixed-value check of such a form, fails the
   same way; this is checked by the subclasses, which see the batches, not by the census tool.
2. **A pair whose `fused_forms` in `casts.tsv` is "all" for a mode has no form the suites find
   declined in that mode.** This is the only check of the 333 forms outside the matrix (item
   0.5): they can contradict the claim the matrix's sample makes, and are otherwise a record.

Everywhere else a decline is a record and not a failure: Spark computing the answer is correct.
**The expected declines**, `sql/varka/spark_suites/expression_declines.tsv`, take the option
matrix's rule (`sql/varka/matrix/skips.tsv`, "a declines test that fuses again, fails as a stale
line"): suite, form, mode, kind (`compile` or `batch`) and reason, for a form `casts_fused.tsv`
calls fused that is meant to decline in the suites, such as a TRY form on row 338's declining
guard ("a correct answer computed slower", `VARKA-338.md` 0 item 4). A listed decline passes; a
listed form whose checks ran and did not decline fails as stale, so row 481's `NULL` mode, which
retires those guards, also retires their lines. A batch entry is judged stale only from the
fixed-value checks, of which two loops draw random values (`CastSuiteBase.scala:131-133`,
`:168`); an entry whose form only those loops reach is named so.

And the reverse, as a warning: a form the suites find fused that `casts_fused.tsv` calls declined,
which can only mean the two compiled different expressions. The nightly job (section 4)
regenerates the record on master and runs the same check against it, so a drift that a merge let
through fails there.

## 3. Tests and proofs

1. **The four subclasses pass**, at the defaults, Spark's assertions and Varka's, with the
   record and the exclusions current.
2. **A planted kernel** fails a subclass: under `checkIntOverflow=false`, `CAST(l AS INT)`'s
   ANSI form answered without its guard must fail an ANSI suite's out-of-range check as
   "answered a raising row", as `VarkaExpressionHarness`'s planted kernel of row 509 does.
3. **A planted decline**: a form forced to decline through `VarkaEmitOptions.forceResidualAt`
   (VARKA-296's fault injector) in one run makes the census check of 2.7 rule 1 fail on a
   temporary copy of the record.
4. **The lift**: a unit test that a null literal becomes a nullable all-null column, a `TRY`
   cast is lifted, a non-lane literal stays, and an unresolvable lift is recorded `unresolved`.
5. **`dev/varka_census.py`'s doctests** for the two rules and the warning, on fixtures as
   `casts_fused` has; and a test that a stale line of `expression_declines.tsv` fails.
6. **The option matrix and the Build** pass with the four subclasses in the sql jobs.

## 4. Time, and where it runs

*An estimate, not a measurement; the arithmetic is in `m8/results/VARKA-368-probe-local.txt`.*
A check of a shape not yet compiled costs about 36 ms on the laptop (row 509's
`VarkaCoverageHarnessSuite`, 38.9 s over 1067 tests, `m8/results/VARKA-509-step4.txt`); a check
whose shape is in the shape cache is assumed at 2 to 5 ms. The fixed-value checks with a column
number 2090, of which 130 fuse today; the random checks 4110, of which 1650 fuse. In the Build the
arm runs the fixed-value checks only: about 8 to 17 s on the laptop at the calibration table's
ratios of 1.3 to 2.9, beside Spark's own 52.8 s. All checks, as the nightly runs them, take about
1.2 to 4.3 minutes on the laptop (all in `m8/results/VARKA-368-probe-local.txt`).

**Where it runs.**

- **The pull request's Build: the fixed-value checks.** The subclasses are named `Varka...Suite`,
  so the Varka-scoped sql jobs run them (item 0.9), each suite weighted in `suite-times.tsv` once
  it has run; the trait skips the Varka arm of `cmpInterpretWithCodegen` unless
  `-Dvarka.suites.random=true`, while Spark's own random checks still run. There the harness ran
  about 6 times its laptop time with the reach recording, so the estimate is **about 3.4 to 4.3
  minutes summed over the three sql jobs**, Spark's own part about 2.6 of it
  (`m8/results/VARKA-368-probe-local.txt`); the jobs took 14 to 24 minutes each before row 520's
  compile restore (`m8/results/VARKA-509-step4.txt`). The same time lands in Spark's own sql
  module job for a change outside Varka's files, and in the Sunday weekly matrix
  (`varka-weekly-matrix.yml`).
  The new-tests step reads test names from the diff (`dev/varka_new_tests.py`); the subclasses
  add none, so it reruns nothing.
- **A new nightly job: everything, the random checks included.** No workflow runs a sql suite on
  a schedule. The plan adds a job to `.github/workflows/varka-fuzz.yml`, "Spark's cast and hash
  suites under Varka", in the workflow's 02:00 UTC run and in `report-failure`'s `needs`: it
  compiles sql's tests, runs the four subclasses with `-Dvarka.suites.random=true`, the record
  regenerated into `target/` and ScalaCheck's `minSuccessful` raised from 10 to 100 for the random
  properties (`-Dvarka.suites.minSuccessful`, read by the trait), and runs `dev/varka_census.py
  --check` against the regenerated record. Estimated at
  **about 15 to 30 minutes** on a runner (the arithmetic file), under a 60-minute timeout.
- **`dev/varka_nightly.sh`** gains the same step, skippable as its others are, for a laptop
  night.

**Predictions**, registered before the build:

0. **The Build's wall time grows by at most 2 minutes** over the 18 it takes since row 520's
   compile restore (Build run 41017862239, the first with a restored compile state), the 3.4 to 4.3
   job-minutes above being split over three jobs that run side by side. Measured on 368a's pull
   request against the Build of its base commit, from the Actions API, as row 509 step 4 measured
   its own.

1. The four subclasses pass with no Varka finding today; any finding is a bug of a fused form
   (`TIME` to `TIME`, the year-month, day-time and `TIMESTAMP_NTZ` casts, `bigint` to `int`).
2. The record holds fewer than item 0.5's 594 keys, since it takes the fixed-value checks' bare
   casts only, and the census check of 2.7 passes on it, as the probe found no disagreement.
   The expected-declines list is empty on the first run: no fused form declines a batch in a
   fixed-value check.
3. Nothing is listed in the exclusions after the first run but `read-path` entries, if any.

## 5. Order, files and size

1. **368a, the harness options and the cast arm**: the column-path Spark side and the evaluating
   fallback in `VarkaExpressionHarness`, `VarkaSuiteCheck`, `VarkaSuiteArm`, the three cast
   subclasses, `casts_suites.tsv`, the exclusions and expected-declines files, the tests of
   section 3 items 1 to 4, and the Build's wall time measured (prediction 0).
2. **368b, the diff and the nightly**: `dev/varka_census.py`'s two rules and `suites` column,
   `m8/casts.tsv` regenerated, the job in `varka-fuzz.yml`, the step in `dev/varka_nightly.sh`,
   the docs bullet, the weights. The job is the same mechanism row 537 (the sweeps on CI, being
   added now) needs - a scheduled job that compiles a module's tests and runs a long suite - so
   the two land as one scheduled run, whichever row comes first building it and the other adding
   its step.
3. **368c, the hash half, after row 337**: `VarkaHashExpressionsSuite` and `hash_suites.tsv`.
   Before 337 every hash declines (item 0.5), so the record would hold nothing but declines.

| file | what |
|---|---|
| `VarkaExpressionHarness.java` (sql/core test) | the column-path Spark side, the fallback |
| `VarkaSuiteCheck.java` (new, sql/core test) | the lift, the routing, the verdicts, the record |
| `VarkaSuiteArm.scala` (new, sql/core test) | the overrides, the dedupe, `minSuccessful` |
| four `Varka...Suite.scala` (new, sql/core test) | the subclasses (the hash one in 368c) |
| `sql/varka/plans/m8/casts_suites.tsv`, `hash_suites.tsv` | the record |
| `sql/varka/spark_suites/expression_excluded.tsv`, `expression_declines.tsv` | the lists |
| `dev/varka_census.py`, `sql/varka/plans/m8/casts.tsv` | the rules, the `suites` column |
| `.github/workflows/varka-fuzz.yml`, `dev/varka_nightly.sh` | the nightly job and step |
| `docs/sql-varka.md`, `sql/varka/ci/suite-times.tsv` | the bullet, the weights |

**Size: medium, in three pull requests**, about three days, one and half a day: the harness's two
options and the arm are most of it. 368a and 368b depend on nothing unmerged, 368c on row 337;
each cast row that lands regenerates the record in its own pull request, as it regenerates
`casts_fused.tsv`.

## 6. What it unlocks in the census

The row moves no pair. It gives each cast pair a second witness beside the matrix: the forms of
Spark's own tests, 594 keys over 88 pairs today against the matrix's sample of each type's forms
(item 0.5), and an answer check against Spark's expected values for every form that fuses, which
the matrix, compile only, never had. It gives row 337's `hash` and `xxhash64` the same once they
fuse, and the cast rows 332, 343 and 350 the dry run their plans name (`VARKA-332.md` 3 item 8).

## 7. Out of this row, and declines that stay

Out: Spark's other expression suites (the same trait serves them as subclasses, Gluten's way, as
`VARKA-271.md` 3.6 left for later); the 41 `.eval()` calls of `HashExpressionsSuite`; a session
route: the error a query raises end to end, through the planner and the cache, is the golden-file
suite's and `VarkaCoverageDifferentialSuite`'s to compare, and this row stays below the session
with the harness alone; a boolean value output (27); the 128-bit width, where row 503 declines
long-lane conditions at two lanes, since the record is at the host's default width.

What stays declined is every cast and hash no row has built, each with the reason the compiler
gives, in the record: today 4420 of the 6200 checks with a column, and every hash.

## 8. Questions for the owner

The lead decided the plan's five open points on 30 October 2026 (the note under "What differs").
One question is the owner's, a scope choice:

1. **The forms the suites reach and the registry does not hold.** The probe found 333 keys of
   (source form, target form, mode) between census types whose forms `casts_fused.tsv` does not
   hold, 81 of them a form cast to itself; the rest are `TIME(1)`, `(2)`, `(4)`, `(5)`, `(7)`,
   `(8)` and `(9)` beside the registry's `TIME(0)`, `(3)` and `(6)`, and decimal precisions beside
   its `(10, 2)` and `(30, 4)` (`m8/results/VARKA-368-probe-local.txt`). (a) Leave the registry's
   forms as they are: this row records those forms and checks them against `casts.tsv`'s "all"
   claims (2.7 rule 2), which is the plan as written. (b) Widen `VarkaTypes`' forms so the matrix
   compiles them too, which moves every test that reads the forms and grows `casts_fused.tsv`;
   a row of its own.

## 9. Done when

Row 368's text holds, with the corrections above: the three cast subclasses pass Spark's
assertions and Varka's at the defaults, with no finding, their fixed-value checks in the Build
and all checks in the nightly job; `casts_suites.tsv` is committed and regenerated by them;
`dev/varka_census.py --check` fails when a form `casts_fused.tsv` calls fused is declined in the
record, in the docs job and in the nightly, and a subclass fails on an unlisted batch decline of
such a form; the planted kernel and the planted decline of section 3 are caught; the exclusions
and expected-declines lists hold only entries with a reason, none stale; the Build's wall time is
measured against prediction 0 and the prediction scored; and, after row 337, the hash subclass
and `hash_suites.tsv` the same.

## 10. Outcome

(Written when the work lands.)
