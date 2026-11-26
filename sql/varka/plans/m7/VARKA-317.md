# VARKA-317: Errors compared as answers

*Row 317 of `m7/PLAN.md`, added by the owner on 12 October 2026 from `m10/SCOPE.md` item 24.
Planned on 16 October 2026 at master `7cc402b8f34`. Section 2 corrects the row in one place: the
check it asks for exists, private to one suite, and what is missing is the check shared and a
kernel planted against it.*

## 1. Where this came from

The row: a differential check that holds Varka and Spark to the same outcome on a row where Spark
may raise - both raise the same error condition, or both answer the same - as Comet's
`checkSparkAnswerMaybeThrows` does (`CometTestBase`; `m10/SCOPE.md` item 24, "Three things worth
taking", 3: "the 'maybe throws' form is the one it lacks and the one `ANSI` rows need"). Done when
the ANSI rows of the coverage differential run through it and a planted kernel that answers an
overflowing row fails it.

## 2. The admission check, done

**What exists.** Read at master `7cc402b8f34`, every place a sql/core Varka suite compares an
error of the row engine with one of Varka's:

| site | what it does |
|---|---|
| `VarkaCoverageDifferentialSuite` (VARKA-138b) | a private `Outcome` (`Answered(rows)` or `Raised(condition, sqlState, parameters)`), `run`, `outcome` and `outcomeDifference`: both answer the same rows or both raise the same error, else a finding naming which side raised. The ANSI form runs every coverage row through it at five positions, and the domain check (VARKA-279) and the `date_add` test use it too |
| `VarkaDifferentialSuite`, six tests | `intercept[SparkXException]` on each engine, then `getCondition` and `getMessage` equal |
| `VarkaTimeArithmeticSuite`, three tests | the same |
| `VarkaSparkFuzz` / `VarkaSparkDifferential` (VARKA-262, 319) | its own `Outcome = Either[String, Seq[String]]`, the condition alone, keyed by the expected-errors list; row 298 owns both files |

So the row's check is the coverage suite's `outcomeDifference`, and the ANSI rows already run
through it. What the row's diagnosis gets right is what is missing around it:

* **No test plants a kernel against it.** The only evidence that the ANSI form catches a kernel
  answering an overflowing row is three lines of `sql/varka/matrix/skips.tsv` (22 to 24): under
  `checkIntOverflow=false` the ANSI-form tests of `i + 1`, `year(d) + i` and `ym - ymm` fail. The
  weekly matrix is the only run that sees it; a change that made the form blind to it (a position
  served by no kernel, a difference that compared nothing) would pass every pull request's run.
  The suite's one planted test (`the ANSI form fails a raise where the row engine skips, and a
  missing one`) plants the fault in outcomes, not in a kernel.
* **Nine hand-rolled sites elsewhere are weaker.** When only one engine raises, a double
  `intercept` fails as ScalaTest's "Expected exception ... but no exception was thrown", naming
  neither engine nor what the other answered; none compares the SQLSTATE or the parameters.

**The plant works, checked before any code.** `checkIntOverflow` (`VarkaEmitOptions`) is the
priced-check switch: off, a `FAIL` int add emits as `LEGACY` and wraps. Under ANSI on the extremes
row (`i = Int.MaxValue`) Spark raises `ARITHMETIC_OVERFLOW` for `i + 1`; skips.tsv line 23 records
that Varka with the check off answers instead and the ANSI form fails. The emit options are laid
over by `VarkaColumnarToRowExec.emitOptions` from the process-wide test hook, which
`VarkaSparkDifferential.forcedDifference` sets and restores per query; the options are part of
the kernel's cache key (a different options record is a different class), so a kernel compiled
earlier with the check on is not reused. The test asserts the wrapped answer, so an inert plant
fails it rather than passing it (`skills/testing-and-debugging.md`, "A planted bug has a
prerequisite").

What the check would have rejected: building a second comparison beside the coverage suite's, or
planting the fault in outcomes again.

## 3. The design

### 3.1 One outcome comparison, shared

`VarkaMaybeThrows.scala` in the sql/core test tree holds:

* **The companion object**: the sealed `Outcome`, `Answered(rows)` and `Raised(condition,
  sqlState, parameters, message, exceptionClass)`; `run(session, query)` giving the outcome and
  the plan it ran with (analysis, planning and execution inside, the first `SparkThrowable` with
  a condition on the cause chain, else the root cause's class and message), and
  `difference(onSpark, onVarka)` - the coverage suite's `outcomeDifference` lifted unchanged
  except that a raise now also compares the message and the exception's class, which the nine
  hand-rolled sites compare and the coverage suite did not.
* **The trait** `VarkaMaybeThrows` (over `QueryTest with VarkaSharedSessions`):
  `checkSparkAnswerMaybeThrows(query)` runs `query` on `spark` and `varkaSpark`, fails with the
  difference, and returns the agreed outcome, so a caller can still assert which condition was
  raised; `outcome` and `runOutcome` are the object's run on a session.

Scala, not Java: the row arm is `QueryTest.sameRows`, Spark's Scala oracle, and the rest is a thin
layer over `SparkSession` and `DataFrame`, the interop surface `sql/varka/AGENTS.md` leaves in
Scala. Item 24 said "a small addition to `VarkaSharedSessions`"; it is a trait of its own instead,
because that trait is mixed into some twenty-five suites, and a sealed type declared inside it
would be one type per suite instance.

### 3.2 What is deliberately unchanged

* The fuzzer family's `Outcome` (`VarkaSparkFuzz`, `VarkaSparkDifferential`): keyed by condition
  alone for the expected-errors list, and row 298's files. Unifying it is recorded in 9.
* Every test title: skips.tsv keys on them (lines 17 to 30 name tests this task converts).
* Every plan assertion beside a converted comparison (`assertFused`, `kernelServed`): the helper
  compares outcomes only, and an outcome comparison with no proof that a kernel served compares
  Spark with itself (`skills/testing-and-debugging.md`, "A Varka node in the plan is not a kernel
  evaluating the expression").
* The coverage suite's per-row and partition tests, whose fixtures raise nothing.

### 3.3 Registered op counts

None: this task emits no kernel.

## 4. Files

| file | what |
|---|---|
| `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaMaybeThrows.scala` | new: the outcome, the run, the difference, `checkSparkAnswerMaybeThrows` |
| `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaCoverageDifferentialSuite.scala` | its private outcome replaced by the shared one; the planted-kernel test |
| `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaDifferentialSuite.scala` | six tests' double intercepts through the check |
| `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaTimeArithmeticSuite.scala` | three tests' double intercepts through the check |
| `sql/varka/plans/m7/PLAN.md`, `VARKA-317.md` | row 317's cell; this file |

## 5. Tests, and what each is for

1. **A kernel planted to answer an overflowing row fails the check** (coverage suite, new).
   Under ANSI, `SELECT i + 1 AS v FROM varka_coverage_extremes` with the emit options'
   `checkIntOverflow` off: Varka's outcome is `Answered`, a kernel served the whole query, the
   answer holds the wrapped `Int.MinValue`, and `checkSparkAnswerMaybeThrows` fails with "only
   Spark raises". The same query with the shipped options passes and returns Spark's
   `ARITHMETIC_OVERFLOW`. Catches a check that cannot see a kernel's wrong answer where Spark
   raises - the failure the row names - in every pull request's run, not only the weekly matrix.
2. **Different conditions, and one side only, from real outcomes** (the existing planted-outcome
   test, extended): `i + 1` and `make_date(2021, i, 1)` on the extremes row raise
   `ARITHMETIC_OVERFLOW` and `DATETIME_FIELD_OUT_OF_BOUNDS...` on Spark; the difference of the two
   is "errors differ". The existing cases (only Varka raises, only Spark raises, other
   parameters) stay, and a raise of another exception class or message is "errors differ".
3. **The ANSI form, the domain check and the `date_add` test** run through the shared
   comparison unchanged in what they assert; their titles and skip lines stay.
4. **The nine converted tests** assert, beside the shared check, the condition (and where they
   did, the exception class) the agreed outcome carries, so a test that both engines stopped
   raising in fails as it did.

Both widths are not a question here: the planted test reads the JVM's own species, and the matrix
runs the suites under its configurations (8).

## 6. The measurement

None: a test-strength change with no performance claim. The cost is the suites' time.

### 6.1 Predictions, registered before the run

1. `VarkaCoverageDifferentialSuite` takes at most a few seconds longer (one new test of four
   queries and a kernel compile; the calibration table's nearest line, VARKA-243, ran at about
   twice its estimate, so the estimate is stated as at most ten seconds).
2. The nine converted tests pass with the message and the exception class compared too: the
   sites compared both already.
3. Under `checkIntOverflow=false` the planted test still passes (the plant over a plant is the
   same plant), and the three skip lines 22 to 24 still fail as listed.

## 7. Risks

1. **The plant is inert** (a cached kernel with the check on, a decline): the test asserts the
   answer and `kernelServed`, so it fails rather than passes (5.1).
2. **A message differs between the engines where condition and parameters agree** (a query
   context from another fragment): the coverage suite would start failing on it. That would be a
   real difference in what the user sees; it is checked by the full suite run before the commit.
3. **The process-wide emit options leak** past the planted test: restored in a `finally`, as
   `forcedDifference` does.

## 8. Sequencing

1. This plan and the row marked planned.
2. `VarkaMaybeThrows.scala` and the coverage suite on it, with the planted-kernel test; the
   suite green.
3. The nine conversions; both suites green.
4. `dev/varka_matrix.sh --module sql --suites VarkaCoverageDifferentialSuite --config
   checkIntOverflow=false`, the outcome and the row's cell.

## 9. Outcome

Built as planned, on 16 October 2026, in one commit after the plan's.

**What landed.** `VarkaMaybeThrows.scala`: the outcome (`Answered`, `Raised` with condition,
SQLSTATE, parameters, message and exception class), `run`, `difference`, and the trait's
`checkSparkAnswerMaybeThrows(query)` and `checkSparkRaisesTheSame[T](query)` - the latter the
form a double `intercept[T]` becomes, which also holds the shared error to the class `T`.
`VarkaCoverageDifferentialSuite`'s private outcome is gone; its ANSI form, domain check and
`date_add` test run through the shared one. Ten sites converted rather than the nine 2 counted:
`VarkaDifferentialSuite`'s checked-add test under a CASE arm (`a guard under a CASE arm condemns
from the taken arm only`) asked each engine to raise in a loop and compared nothing, and now
compares the two errors. No test title moved.

**The runs** (pinned to cores 12 to 23):

* The three suites together under the defaults: 336 tests, none failed. The new test, `the ANSI
  form fails a kernel planted to answer an overflowing row`, took 126 ms; the extended
  planted-outcome test 182 ms. The planted kernel served the whole query and answered the wrapped
  `Int.MinValue`, and the check failed it with "only Spark raises" naming `ARITHMETIC_OVERFLOW`.
* `dev/varka_matrix.sh --module sql --suites VarkaCoverageDifferentialSuite,
  VarkaDifferentialSuite,VarkaTimeArithmeticSuite --config checkIntOverflow=false -j 2`: the
  defaults 336 passed; the configuration 322 passed and 14 cancelled, exactly the 14 skip lines
  of those suites under that configuration, each still failing as listed (none stale). The
  planted test passed under both.

**6.1 scored.**

1. *At most ten seconds added to the coverage differential*: the one new test takes 126 ms and
   the conversion adds no query (the ANSI form ran the same two per position before). Right in
   direction, pessimistic by far; the estimate priced a kernel compile the cache key makes
   cheap. Ratio about one in eighty; the line is owed to the calibration table in
   `skills/testing-and-debugging.md`, a file outside this task's brief.
2. *The converted tests pass with message and class compared*: held, and the coverage suite's
   positions and domains, which compared neither before, pass with both (risk 2 did not occur).
3. *Under `checkIntOverflow=false` the planted test passes and lines 22 to 24 still fail*: held.

**The self-review** of the diff found three things, fixed before the commit: scalastyle's
`Class.forName` rule (now `Utils.classForName`) and its import order; and that
`checkSparkRaisesTheSame` called without its type argument infers `Nothing`, whose class
`scala.runtime.Nothing$` is a `Throwable`, so the class check would fail confusingly - it now
asserts that `T` was named. The suites' targeted tests reran green after each fix.

**Left for later.**

* The fuzzer family keeps its own outcome (`VarkaSparkFuzz.Outcome`, the condition alone, keyed
  by the expected-errors list of VARKA-319). Moving it onto `VarkaMaybeThrows.Raised` would
  compare SQLSTATE, parameters and message in the random differential too, and needs the
  expected-errors lookup to read `Raised.condition`; it is row 298's files, so it waits for a
  task that owns them.
* The plant reaches the int lane's checked add only. A long-lane plant (`checkIntOverflow` also
  governs the long lane's checked arithmetic, skips.tsv line 16) and a calendar-guard plant
  (`guardDayProducers=false`) would hold the check to the other families the ANSI form covers;
  the matrix's configurations already run them weekly.
