# VARKA-120: the coverage table as a differential corpus

*Milestone 5, section 2.55. Opened 15 September 2026 from the review of what the
published table proves; implemented 16 September 2026 while VARKA-106 and VARKA-84
waited on CI.*

## 1. Where this came from

`VarkaCoverageSuite` proves every row of the coverage table *compiles* to a fused
kernel, and it forces a row for every Catalyst class the compiler admits, so the
table cannot claim support that does not exist or omit support that does. What
it does not prove is that the fused kernel behind a row *answers correctly*.
`VarkaDifferentialSuite` proves that, over hand-chosen shapes, and it never reads
`coverage.json`; so the rows a reader trusts are one corpus and the rows the
differential checks are another, and nothing ties them. A new arm can be
documented, pass the coverage suite, and have no differential behind it.

## 2. The admission check, done

**What a row carries.** `coverage.json` holds 57 expressions - 44 in projection
form, 13 in predicate form - each with its SQL, family, form, the Catalyst classes
it resolves to and a note, over a fixed column set: `d` and `d2` (dates), `i`
(int), `ymm`, `ymy`, `ym` (the three year-month interval units). It carries no
expected result and no fusion verdict; the corpus is the SQL alone, and a suite
over it has to bring the fixture and the expectation.

**One row is not executable as written.** `d IN (11 to 16 date literals)` is a
caption: the coverage suite builds that row's expression by hand (an `InSet`,
which Spark's optimizer creates and no SQL spelling produces directly), and the
JSON prints the caption. A differential over the table needs SQL it can run, so
the row gains an `executable` spelling - eleven real date literals, above the
`inSetConversionThreshold` of ten, so the optimizer produces the same `InSet` -
and the JSON carries `executable` for every row, equal to `sql` everywhere else.
The rendered markdown table is unchanged; `coverage.json` is regenerated.

**What "both consumers" means, from the code.** A Varka projection or filter is
consumed either by rows - `VarkaColumnarToRowExec` and
`VarkaFilterColumnarToRowExec`, the plan every direct `SELECT` gets - or by
columns, when the parent wants batches: the Arrow cache builder strips the
transition and caches the columnar `VarkaProjectExec` or `VarkaFilterExec`
directly (`VarkaColumnarRule`, and `VarkaDifferentialSuite`'s "caching a view
over fused Varka work keeps the work"). So the columnar consumer is exercised by
caching a view over the row's query and reading the cache back, which is also
the path VARKA-116 proved for `TIME`. Both are cheap.

**The fixture.** `VarkaSharedSessions` builds its tables per shape; none has all
six columns. This task builds `varka_coverage` through SQL `VALUES`, the way
`cacheDatesIntervals` does (an interval column has no plain Scala literal), with
the dates the existing fixtures use - leap days, a 31st, 1969-12-31, the two
dates the `IN` rows name, 2021 dates for the `year(d) = 2021` rows, a far date
inside the contract - `d2` shifted against `d`, `i` small (inside the day-offset
guard, and doubling as a month for `make_date(2021, i, 1)`, including values the
calendar rejects), and month counts inside the emitter's guard. Three null
patterns, the ones the emitter's matrices use: nulls sprinkled independently per
column, no nulls, and every column null in every row (the branch with no
validity buffer, and the one where a guard has no live lane to condemn).

**The expectation.** Every row fuses under both consumers and on all three
fixtures, and answers what the row engine answers. The coverage suite already
guarantees compile-time admission; a decline at run time (a guard condemning a
batch) or an answer that differs is a finding, not a fixture problem, and the
suite reports the row, the fixture and the consumer.

## 3. The design

### 3.1 The mechanism

One new suite, `VarkaCoverageDifferentialSuite` in `sql/core`, on
`VarkaSharedSessions`. It reads `sql/varka/coverage.json` through
`getWorkspaceFilePath` (the coverage suite's own precedent) and generates one
test per row. Each test builds the row's query - `SELECT <executable> AS v FROM
<fixture>` for a projection, `SELECT d, d2, i FROM <fixture> WHERE <executable>`
for a predicate - and, for each of the three fixtures:

1. *Row consumer.* Runs the query on the baseline and the Varka session, asserts
   the Varka plan carries a Varka node, that the answers match, that the kernels
   ran (`numVarkaBatches > 0`), and that a second run - served from the warm
   shape cache - answers the same (VARKA-18's discipline, copied from
   `checkDifferential`).
2. *Columnar consumer.* Caches a view over the query on both sessions, asserts
   the Varka session's cached plan carries the columnar Varka node, and compares
   the cached views' contents.

The failure message names the row's SQL, the family, the fixture and the consumer.

### 3.2 What is deliberately unchanged

`VarkaDifferentialSuite`'s hand-chosen shapes: this suite is the floor under
them, not their replacement - the differential's extreme offsets, overflow rows
and cache-conversion cases are shapes the table does not spell. The coverage
table's rendering and the coverage suite's three checks. The compiler and the
emitter.

### 3.3 Registered op counts

Not applicable.

## 4. Files

* `sql/core/src/test/scala/.../execution/VarkaCoverageDifferentialSuite.scala` - new.
* `sql/catalyst/src/test/scala/.../codegen/varka/VarkaCoverageSuite.scala` - the
  `executable` spelling on the `InSet` row and the `executable` field in the JSON.
* `sql/varka/coverage.json` - regenerated, one field per row added.
* `docs/sql-varka.md` - the suite named in the testing section.
* `sql/varka/plans/m5/PLAN.md` - row 120.

## 5. Tests, and what each is for

The suite *is* the test. Its own property is that it reads the committed JSON and
nothing else: a row added to the coverage suite and regenerated into the JSON is
run here with no change to this file, which is the guarantee section 2.55 asks
for - adding an arm without a row fails `VarkaCoverageSuite`, adding a row
without correctness fails this suite.

    VARKA_COVERAGE_REGEN=true build/sbt -batch "catalyst/testOnly *VarkaCoverageSuite"
    build/sbt -batch "catalyst/testOnly *VarkaCoverageSuite"
    build/sbt -batch "sql/testOnly *VarkaCoverageDifferentialSuite"

## 6. The measurement

None.

### 6.1 Predictions, registered before the run

1. All 57 rows pass on all three fixtures under both consumers on the first run:
   the coverage suite already proves admission, and every row's family has a
   hand-written differential somewhere in `VarkaDifferentialSuite`.
2. The suite runs in under two minutes: 57 rows x 3 fixtures x 2 consumers is 342
   small queries over a few dozen cached rows each.

## 7. Risks

* **A fixture value that trips a runtime guard.** `date_add(d, i)` declines a
  batch whose result leaves the narrowed range; `i` stays small, and a far date
  is only ever in `d`, never shifted by a large offset. A decline would show as a
  missing Varka metric, with the row named.
* **The all-null fixture and `numVarkaBatches`.** A batch of all-null lanes is
  still a batch the kernel processes, so the metric is expected to be positive;
  if some node short-circuits an all-null batch before the kernel, the row
  consumer's third assertion says so, and the answer comparison still holds.
* **The `IN` rows' dates.** The fixture has to contain the literals the rows name
  or the predicates select nothing on every fixture and the differential compares
  empty against empty; both `IN` rows' dates are in the fixture on purpose.

## 8. Sequencing

1. This plan. 2. The `executable` field, and `coverage.json` regenerated and
checked. 3. The suite. 4. Row 120, the docs line, section 9.

## 9. Outcome

Done, 16 September 2026. `VarkaCoverageDifferentialSuite`: 58 tests (57 rows plus
one that checks the table was read), every row on three fixtures under both
consumers, green, in 42 seconds.

    build/sbt -batch "sql/testOnly *VarkaCoverageDifferentialSuite"
    Tests: succeeded 58, failed 0, canceled 0, ignored 0, pending 0

**Prediction 1 was wrong: 13 rows failed the first run**, 45 passed. None of the
13 was a wrong answer from a kernel. They fell into four causes, and the fourth
is the one worth the task.

1. *Two fixture mistakes, mine.* The dense fixture's columns were inferred
   non-nullable from a `VALUES` list with no `NULL`, so the optimizer folded
   `d IS NULL` to false and `coalesce(d, d2)` to `d` and left nothing for Varka
   to fuse; the fixture now carries the all-null row in its `VALUES` and filters
   it out again, which keeps the columns nullable. And `i` held months the
   calendar rejects, which under the session's ANSI default make
   `make_date(2021, i, 1)` an *error* on the row engine, not a null; `i` stays in
   1..12, and invalid parts remain `varka_date_parts`' business in the
   differential suite.
2. *One legitimate fast path, which risk 2 half-predicted.* A predicate that
   selects nothing on a fixture never reaches the kernel: the in-memory scan
   prunes a batch its column statistics rule out - an all-null batch under a
   null-rejecting predicate, a null-free batch under `IS NULL` - before the Varka
   node sees it. The suite asserts the node and the empty answer there and skips
   only the batch metric, for exactly the case where the baseline's answer is
   empty.
3. *A conjunct the table claimed and the engine did not serve.* `year(d) = 2021
   AND i > 0` was a row, and end to end its plan is a row `Filter (i > 0)` above
   `VarkaFilterColumnarToRow (year(d) = 2021)`: the compiler's `compare` sends a
   non-literal operand through `compileNode`, whose value leaves are date columns
   and fused int fields, so a bare int column declines. `VarkaCoverageSuite`
   accepted the row because its predicate check asked whether *any* conjunct
   fused. That check now requires every conjunct, the row is
   `year(d) = 2021 AND month(d) > 6` with a note naming the gap, and the gap is
   VARKA-122 (section 2.57 of the milestone plan): one operand rule in `compare`,
   no guard question, three coverage rows when it lands. The columnar consumer
   found it, not the row consumer: the residual filter sat on top, so the cache
   builder had no columnar Varka node to keep.
4. The remaining first-run failures were the same three causes on other rows.

**Prediction 2 held.** 42 seconds for 342 differential runs, against a budget of
two minutes.

**What the plan got wrong, recorded rather than erased.** Section 2 said `i`
would include "values the calendar rejects"; it cannot under ANSI, see 1. Section
7's risk 2 expected an all-null short-circuit *inside* a Varka node; the skip is
one level down, in the in-memory scan's statistics, and it also fires on a
null-free batch under `IS NULL`, which the plan did not foresee. And the plan
expected the suite to find nothing in the table; it found a row the table should
not have carried as written, which is the point of running the table.

**Files.** The suite; `VarkaCoverageSuite`'s stricter predicate check, the
`executable` field and the rewritten row; `coverage.json` and the docs table
regenerated; VARKA-122 in the milestone plan; the suite named in
`docs/sql-varka.md`.
