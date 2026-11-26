# VARKA-138: three stronger test forms for every coverage row

## 1. Where this came from

Row 138 started as one of milestone 5's open items (item 39) and was refined by the paper reading
of 11 October 2026 (`m7/READING.md` 11) and by `m7/PLAN.md` 2.2. It asks for three test forms over
the rows of `sql/varka/coverage.json`, the table of every expression shape Varka fuses:

1. **Poisoned null lanes in each row's masked body.** A null lane's data holds an extreme value,
   not a harmless one, and the kernel's masked body - the one it runs when an input has nulls -
   meets it, so a kernel that computes on a null lane's data and lets the result leak shows.
2. **Output validity pre-filled both ways.** The harnesses fill an output's validity bitmap with
   ones before the kernel writes it, which catches a kernel that fails to clear a bit and never one
   that fails to set it. EMI's lesson (Le, Afshari and Su, PLDI 2014) is to vary what the program
   must not depend on; the fill is such a thing.
3. **A ternary partition oracle for every filter row, and the ANSI form.** For a predicate `p`,
   the rows where `p` is true, where `NOT p` is true and where `p IS NULL` must split the table
   exactly, compared on rows, not counts (Rigger and Su, OOPSLA 2020, p. 20: counts missed 5 of 48
   bugs). Spark rewrites these partitions before Varka sees them (`BooleanSimplification`,
   `NullDownPropagation`, `InferFiltersFromConstraints`), so the oracle runs with those rules
   excluded too. Under ANSI, an error compares on its class, its SQLSTATE and its message
   parameters, as Spark's own `checkError` does, and Varka must not raise where Spark does not
   evaluate: after a residual conjunct, the right side of AND and OR, the untaken arm of CASE and
   IF, later COALESCE arguments.

The owner chose it as the next test-strength row on 13 October 2026.

## 2. The admission check, done

Probes on 13 October 2026, in this worktree at `eca9171de4b` (master after #783). The probe code
was a throwaway copy of `VarkaCoverageDifferentialSuite` with two extra tests and a system property
read in `VarkaKernelChecks`, all removed before the plan was committed.

**Form 1 is already in the record.** `VarkaKernelChecks.runOne` writes a poison value into every
null lane of every input (`VarkaEmitterFixtures.poison`, VARKA-70's harness rule), and a batch
whose inputs have no nulls can report one anyway (`forceMasked`) so the masked body runs.
`runAndCompare` then runs each compared batch through the body it did not take (`otherBody`), so
both bodies meet the reference evaluator. `VarkaCoverageCompositionFuzzSuite`'s test "every
coverage row's kernel agrees with the reference evaluator, and the reference with Spark's eval"
runs each of the 92 rows alone, at both widths the bytes oracle pins, on four drawn batches, and
holds over 100,000 answers to Catalyst's interpreted `eval` as well. What is left is chance: each
input's null pattern is drawn as none, every fifth row or random, so a one-input row meets no null
lane at all in about 1.2% of its four batches' draws per width ((1/3)^4).

**Form 2: zero-filled, nothing fails, and zero is what production does.** With the fill read from
a property (`VarkaKernelChecks.java:603`, `0xFF` today) and set to `0x00`,
`VarkaCoverageCompositionFuzzSuite`, `VarkaIrFuzzSuite` and `VarkaSmallTreeSuite` passed: 31 tests,
220 s. The property was shown to reach the forked test JVM by setting it to `bogus`, which failed
the per-row test with `NumberFormatException: For input string: "bogus"`. Production allocates
every output with Arrow's `allocateNew` (`VarkaKernelEvaluator.java:533`,
`VarkaFilterEvaluator.java:382` and 428), and a standalone check on Spark's classpath read 0 of
8,000 validity bits set after `allocateNew` on `IntVector`, three rounds, each after another
vector had set every bit of a chunk of the same size. So the harness has tested only the fill
production never uses; the zero fill is the one that matters, and it passes today.

**Form 3, the partitions: they agree, and `p IS NULL` never reaches Varka.** For each of the 27
predicate rows, on the suite's three fixtures (nulls mixed, none, all), under the default optimizer
and with the three rules excluded: `SELECT * FROM fixture WHERE p`, `... WHERE NOT (p)` and
`... WHERE (p) IS NULL` on Spark and on Varka, 486 queries.

| optimizer | `p` fused | `NOT p` fused | `p IS NULL` fused | rows differing |
| --- | ---: | ---: | ---: | ---: |
| default | 81 of 81 | 81 of 81 | 30 of 81 | 0 |
| three rules excluded | 81 of 81 | 81 of 81 | 0 of 81 | 0 |

Every partition answered as Spark's, and the three partitions' sizes summed to the table's in all
162 cases. Under the default optimizer `NOT (d < d2)` reaches Varka as
`isnotnull(d) AND isnotnull(d2) AND d >= d2`, as the reading said; with the rules excluded it
reaches Varka as `NOT (d < d2)` and is fused as written. `(p) IS NULL` is fused only where
`NullDownPropagation` has turned it into null checks on the columns; as written, Varka fuses it
for no row. So the third partition can be checked only as the complement of the two Varka
computes, and Varka evaluating the unknown truth value directly is a gap of its own (9, candidate
row).

**Form 3, ANSI: errors agree, and two of the four positions never reach Varka.** Each of the 51
of the 65 projection rows that read only `d`, `d2`, `i`, `l`, `ym`, `t` and `dt` was run over a
two-row cached fixture of those seven columns, one row at the
types' extremes (`9999-12-31`, `Int.MaxValue`, `Long.MaxValue`, the largest month and day-time
intervals, `23:59:59.999999`) and one ordinary, in four positions: alone, the THEN of a CASE whose
condition is false on both rows and that has no ELSE, the second argument of `coalesce(1, e)`, and
the right side of an AND whose left side is false. 204 queries, each outcome - the rows, or the
error's condition, SQLSTATE and parameters - compared between the engines: all 204 agreed. Nine
rows raise alone: 5 `ARITHMETIC_OVERFLOW`, 1 `CAST_OVERFLOW`, 1
`DATETIME_FIELD_OUT_OF_BOUNDS.WITHOUT_SUGGESTION`, 2 `INVALID_EXTRACT_FIELD`. Of the queries that
did not raise, Varka fused 41 of 42 alone and 49 of 49 behind the false AND, but 0 of 49 in the
CASE without ELSE and 0 of 21 in `coalesce(1, e)`, which the optimizer folds to the literal. So the
ANSI form must use shapes that reach Varka: a CASE with an ELSE, and a COALESCE whose first
argument is a column. An error where both raise is Spark's row path raising, since a kernel only
declines a batch that would raise (`docs/sql-varka.md`, "Null semantics and predication"); the
form's value is the other two outcomes, Varka raising where Spark does not and Varka not raising
where Spark does.

## 3. The design

### 3.1 Form 1: a null lane in every row's masked body, by construction

The per-row test of `VarkaCoverageCompositionFuzzSuite` keeps its four drawn batches and adds one
fixed batch per row and width: 257 rows, every input null on every third row and poisoned there,
so both bodies (the masked one by the nulls, the dense one by `otherBody`) meet a poisoned lane at
the loop and at the epilogue. 257 is a loop of whole lane groups at every width plus an epilogue.

### 3.2 Form 2: every compared batch at both fills

`VarkaKernelChecks.runOne` takes the fill as a parameter. Every caller that compares rows -
`runAndCompare`, the long-lane form, `runEachBodyStatus` - runs each batch at `0xFF` and at `0x00`,
and both runs must agree with the reference. The kernel runs twice per batch; the emission, the
class load and the reference evaluation, which cost more than a 1,000-row kernel call, run once.
The sentinel fill of the output values stays: it is the data side of the same question.

### 3.3 Form 3: the partition oracle in `VarkaCoverageDifferentialSuite`

The fixtures gain a row id column, `rid`, an int the predicates do not name. For each predicate
row, fixture and optimizer setting (default, and the three rules excluded through
`spark.sql.optimizer.excludedRules` on both sessions):

- `T` = `WHERE p` and `F` = `WHERE NOT (p)` on Varka, each with a Varka filter asserted in the
  plan (the probe found one in every case), each compared on rows with Spark's;
- `T` and `F` share no `rid`, and `U`, the ids in neither, equals Spark's `WHERE (p) IS NULL` on
  ids and values.

`U` is the complement and not a query of its own because Varka fuses no `IS NULL` over a
comparison (2); were it fused (9), the same test would query it.

### 3.4 Form 3: the ANSI form in `VarkaCoverageDifferentialSuite`

A fourth fixture, `varka_coverage_extremes`, holds the extremes row of 2 beside an ordinary one,
over all of the table's columns. For each projection row, four queries:

1. the row alone;
2. `CASE WHEN rid < 0 THEN e ELSE <a column of e's type> END`, an untaken arm with an ELSE;
3. `coalesce(<a column of e's type>, e)` over a column with no nulls in this fixture, a later
   argument;
4. `WHERE rid < 0 AND e IS NOT NULL`, the right side of a false AND.

and for each predicate row, `WHERE rid < 0 AND p` and `WHERE rid >= 0 OR p`. Each outcome is
compared: rows as rows; an error by its condition, SQLSTATE and message parameters, the three
`checkError` compares. A shape that raises on one engine only fails. Positions 2 to 4 assert a
Varka node in the plan, so the check cannot pass by never reaching Varka; a row whose shape Varka
declines in that position is listed in the test with its reason, as the coverage suite lists its
declines.

### 3.5 Deliberately unchanged

- **The fixtures' existing columns and rows**, which every coverage row's answer depends on; `rid`
  and the extremes fixture are added beside them.
- **`IS NULL` over a predicate stays unfused.** Fusing it is a compiler change with its own bytes
  oracle entries, a row of its own (9).
- **The fuzzers' null-pattern draws.** 3.1 adds a fixed batch; it does not change what is drawn,
  so seeds replay as before.

### 3.6 Registered op counts

None: no emitted code changes.

## 4. Files

| file | what |
| --- | --- |
| `VarkaKernelChecks.java` | the fill as a parameter, every compared batch at both fills |
| `VarkaCoverageCompositionFuzzSuite.scala` | the fixed poisoned batch per row and width |
| `VarkaCoverageDifferentialSuite.scala` | `rid`, the extremes fixture, the partition and ANSI tests |
| `docs/sql-varka.md` | "Testing and benchmarks": the three forms |

## 5. Tests, and what each is for

1. **A kernel that never sets a validity bit fails.** A planted fault: a test emits a coverage
   row's kernel with its validity store removed (through the emitter's existing test hooks, or a
   class-file rewrite of the store, whichever the code admits) and shows the `0x00` run fails it
   and the `0xFF` run does not. Without it, a zero fill that never fails could mean the fill never
   reaches the kernel.
2. **A kernel that never clears a bit still fails** under `0xFF`, the existing check, kept.
3. **Every row's masked body meets a poisoned lane**: the per-row test counts the fixed batches and
   asserts one per row and width.
4. **The partitions** (3.3) over all predicate rows, both optimizer settings.
5. **The ANSI form** (3.4) over all rows, every position.

## 6. The measurement

The per-row test's time and `VarkaCoverageDifferentialSuite`'s time, before and after, at the
defaults and in the matrix's `cse=false` configuration.

### 6.1 Predictions, registered before the run

1. No kernel fails at the zero fill, as the admission check found; test 1's planted fault does.
2. The per-row test takes at most 25% longer: the kernel runs twice a batch, and one batch of five
   is added, while emission and the reference dominate.
3. `VarkaCoverageDifferentialSuite` takes at most twice as long: it gains two queries per predicate
   row per fixture and setting, and four to six per row on the extremes fixture.
4. Positions 2 and 3 of the ANSI form reach Varka for at least 50 of the 65 projection rows, now
   that the CASE has an ELSE and the COALESCE starts with a column.
5. The partition oracle and the ANSI form find no disagreement, as the probes found none; a
   disagreement is fixed or becomes a row.

## 7. Risks

1. **The zero fill may expose a real bug** in a kernel shape the coverage rows do not reach; the
   fuzzers run through the same `runOne`, so it would show there first, as it did not in the
   probe's 31 tests.
2. **The ANSI shapes can change what fuses.** A CASE with an ELSE, or a COALESCE over a column, is
   a different expression from the row; where Varka declines it, the declined list says so, and a
   row with nothing fused in a position is not silently counted as checked.
3. **`rid` changes the fixtures' column set.** Rows name columns, so `rid` changes no row; it does
   widen `SELECT *`, which only the partition test uses.

## 8. Sequencing

Two pull requests.

1. **138a, the kernel side:** 3.1 and 3.2, tests 1 to 3, predictions 1 and 2.
2. **138b, the SQL side:** 3.3 and 3.4, tests 4 and 5, predictions 3 to 5. Then section 9.

## 9. Candidate rows found

- **Varka fuses `IS NULL` and `IS NOT NULL` over a comparison.** As written, `(p) IS NULL` is fused
  for none of the 27 predicate rows (2); the unknown truth value is exactly the validity a fused
  comparison already computes, so the node is one mask. Spark's `NullDownPropagation` hides the gap
  only where it can push the test onto columns.
- **Varka fuses a CASE without an ELSE.** `CASE WHEN c THEN e END` was fused for none of 49 rows
  (2), while the same CASE with an ELSE is the coverage table's own shape; the missing ELSE is a
  null literal of `e`'s type.

## 10. Outcome (138a)

Done on 13 October 2026, as 3.1 and 3.2 describe, without the SQL side (138b).

* **Both fills (3.2).** `VarkaKernelChecks.runOne` takes the fills to run as a parameter, a list
  of the new `ValidityFill` (`ZERO`, `ONES`). It loads the class once, runs the kernel once per
  fill into output buffers of its own, then walks the rows once: the reference and Spark's answer
  are computed once a row, and every fill's bits and values are held to them. The runs must
  return the same status, since a kernel declines on its inputs, never its outputs; a status that
  differs fails as a finding of its own. A failure names its fill in the context, before the
  `": "` - `kernel of year(d) (output validity pre-filled 0x00): validity of output 0 row 0
  differs` - which `VarkaFailureSignature` takes off with the context, so no signature changed.
  Every entry point that compares rows runs both fills: `runAndCompare`, `runAndCompareStatus`
  and `runEachBodyStatus`, at either lane (3.2 named the first and the last; the status forms
  go through the same `runOne`). `runOwnBodyAt` and its long-lane form run a batch through its
  own body at one fill, which test 1 needs.
* **The planted fault.** `VarkaValidityFault` rewrites an emitted class's bytes with the Class-File
  API, as `VarkaUnreadLocalsTrim` does, replacing every call that writes an output's validity -
  the `VarkaVectorSupport` helpers `prepareOutputValidity`, `zero`, `setValid`,
  `copyColumnValidity`, `orValidityBitsAt*`, `orPartialValidityBitsAt`, `putValidityWord` - by
  pops of its arguments. A rewrite in the test, not an emit option: it needs no seam in the
  emitter, so nothing in production can reach it, and it is the same fault whichever form the
  options emitted (each of the four kernels below had two such calls).
* **The poisoned batch (3.1).** `checkKernel` takes `poisoned`: 257 rows, every input null on
  rows 1, 4, ..., 256 (`i % 3 == 1`), values from a stream of their own (`seed ^ 138`), so the
  four drawn batches, and every seed that replays them, are unchanged. The plan said "every third
  row"; the phase is chosen so that row 256, the epilogue's one row at every width, is a null
  lane, and row 1 is one in the first lane group. A batch a guard declines is drawn again nearer
  zero, as the drawn ones are, with the same null pattern.

**A correction to 3.1.** The plan says both bodies meet a poisoned lane, "the dense one by
`otherBody`". They do not, and cannot: the other body of a batch with nulls is the same values
with no nulls, and the harness poisons only a null lane, so the dense body is compared on the
batch's values unpoisoned. A poisoned lane is something only the masked body can meet; the fixed
batch puts one in front of it for every row, which is what form 1 asks.

**Why the zero fill passed the admission check.** The driver writes every output's validity
before anything can return - zeroes it, fills it on a dense batch, or copies and combines the
inputs' bitmaps - and the loop ORs the rest in (`VarkaBodyEmitter.emitOutputSegments`, "the
emitter invariant"). So the fill reaches an answer only through a missing write, and a kernel
that misses none answers the same at any fill. Test 1 shows the check sees it when one is
missing.

**Tests (5).**

1. **A kernel that never sets a validity bit fails at 0x00 only.** For `year(d)` (int lane) and
   `greatest(l, l2)` (long lane), at the default width and at four lanes, the faulty kernel on a
   257-row batch with no nulls passes at 0xFF and fails at 0x00:
   `kernel of year(d) at the default width, no nulls (output validity pre-filled 0x00): validity
   of output 0 row 0 differs (want Some(1946), got None)`, and for `greatest(l, l2)`
   `(want Some(560545912292), got None)`. The kernel as emitted passes at either fill and through
   the fuzzers' own check, and that check, both bodies at both fills, fails the faulty one.
2. **A kernel that never clears a bit fails at 0xFF only.** The same fault on a batch whose every
   row is null: passes at 0x00, fails at 0xFF with `validity of output 0 row 0 differs (want None,
   got Some(-559038737))` (the int sentinel `0xDEADBEEF`), and for `greatest(l, l2)`
   `got Some(-9223372036854775808)`, the poison carried through. So each fill catches what the
   other cannot, in one test (`VarkaCoverageCompositionFuzzSuite`, "a kernel that writes no
   validity fails at one fill and passes at the other").
3. **Every row's masked body meets a poisoned lane.** The per-row test counts the poisoned batches
   compared row by row: 184 of 184 kernels (92 rows at two widths), and asserts one per row and
   width.

Runs at the defaults, on the laptop, beside other agents' test JVMs:
`VarkaCoverageCompositionFuzzSuite` (13 tests), `VarkaIrFuzzSuite`, `VarkaSmallTreeSuite`,
`VarkaGuardEdgeSuite`, `VarkaMemorySanitizerSuite`, `VarkaShrinkerSuite` and `VarkaVariantSuite`,
the seven suites that reach `VarkaKernelChecks`: 61 passed, 2 cancelled as on master (an opt-in
dump, and the sanitizer test that needs `-Dvarka.sanitizeMemory=true`), none failed. And the gate's
catalyst step, `dev/varka_matrix.sh --defaults --module catalyst --split 3 -j 3`, every Varka suite
of the module with the memory sanitizer on: 52 suites in 421 s, 617 tests passed, none failed, 40
cancelled, each an opt-in (the exhaustive sweeps, the assembly tests with no disassembler, the
dumps, the JFR case).

**Predictions scored (6.1), 138a's.**

1. **Held.** No kernel failed at the zero fill, in the seven suites or the catalyst matrix run;
   the planted fault did, at the zero fill alone on a batch with no nulls (test 1).
2. **Failed.** The per-row test took 38% longer by minimums, against at most 25% predicted. Five
   interleaved pairs, master's test code and this change's on the same build, 17:00 to 17:09 CEST
   with the load average rising from 1.3 to 6.9: before 1.218, 1.539, 1.480, 1.888, 2.014 s;
   after 1.816, 1.682, 1.734, 2.228, 2.394 s. Minimums 1.218 and 1.682 s (+38%); the median pair
   +18%. The prediction assumed emission and the reference dominate and the kernel's second run
   is cheap; the second half held, the first did not, because the fixed batch alone adds more
   than a fifth of the reference's work: 257 rows against the drawn batches' mean of 238 (lengths
   1, 7, 64, 100, 257 and 1,000, uniform), and the answers held to Spark rose from 347,552 to
   442,128 (+27.2%). At about 1.5 s for 184 kernels the per-row work, not emission, is what the
   test spends.

   The times are the child JVM's: the suite runs in a JVM of its own (`VarkaOwnJvm`), and the
   time sbt prints for the test it asks first is the whole child run's - 42.8, 32.7 and 32.0 s on
   master for a test that took 2.4 to 2.9 s of it. The per-test times are in the child's JUnit
   report under `target/tmp/varka-own-jvm*` (10.2's candidate).

`docs/sql-varka.md`, which section 4 lists, is unchanged: its "Testing and benchmarks" does not
describe the harness's fill, and the three forms go in there together with 138b.

### 10.1 The review's fixes

A code review of the pull request found seven things, all fixed in it; none was a false positive,
though the "line-508 sweep" of the fourth fills the sweep's *input* bitmap, every row valid,
and the sweep's outputs came from `makeOutput`, so the fix there is the output's fill.

1. **The poisoned batch made every input null together.** Poison is chosen per column by the
   null's ordinal, so every poisoned row had all inputs null and equal poisons, and a null never
   sat beside a valid input. Now input `c` is null where `i % 3 == (c + 1) % 3`, and every input
   where `i % 9 == 0`: at two inputs, 171 of the 257 rows hold a null beside a valid input, and
   input 0 is still null on row 256, the epilogue's. Shown by a planted wrong kernel: for
   `greatest(l, l2)`, the IR of `CASE WHEN l > l2 THEN l ELSE l2 END`, which answers null where
   only `l2` is null. It passes the first batch's same-phase pattern and fails the new one at
   both widths: `wrong kernel of greatest(l, l2) (output validity pre-filled 0x00): validity of
   output 0 row 2 differs (want Some(-274643580971), got None)`.
2. **The fault's check was circular.** The test asserted the rewritten kernel called no writer by
   the same predicate that chose the calls to remove. Now it runs the faulty kernel and counts the
   validity bytes, every byte of the whole-word buffers, that differ from the fill
   (`VarkaKernelChecks.validityBytesWritten`): none, at both fills, on both batches, for both rows
   at both widths; the emitted kernel changes bytes at the fill that hid its answer. A writer the
   predicate missed would change a byte.
3. **The bits past the last row were never compared.** `setValid` and `copyValidity` promise
   "exactly the low `rows` bits ... and zero past them in the final byte", so `runOne` now
   requires those bits zero in every run. The bytes past the nominal `(length + 7) / 8` are not
   checked, since a byte-wise output maps only those (`VarkaBodyEmitter.emitOutputSegments`).
   No kernel of any suite fails it; the planted fault does, at the ones fill on 257 rows (`has
   bits set past row 256 in its last byte (0xff)`), which is why the fault test's row assertions
   now run on 256 rows.
4. **The fill reached only the kernel checks.** `VarkaEmitterFixtures.makeOutput` and
   `makeLongOutput` take the fill (ones where a suite's own case names none, as before);
   `VarkaEmitterTestBase.checkMatrix` and `checkLongMatrix`, the matrices behind most emitter
   suites, run every case at both fills; and the chrono sweeps fill the outputs again before each
   chunk, the two fills in turn, and now require every row valid, which they did not check at all.
5. **The poisoned batch's dense run was redundant.** It runs through the masked body alone now,
   at both fills with Spark's answers (`runOwnBody`), since its other body meets no poisoned lane.
6. **The status message named run 0's fill** for the previous run's status; it names run `f - 1`.
7. **`runOwnBodyLong`** (formerly `runOwnBodyLongAt`) and its Scala face moved to the long-lane
   group of each file.

**Prediction 2, measured again.** Ten interleaved pairs, master's test files against these, 18:57
to 19:13 CEST, load 1.6 to 3.7: master 1.271 s best (median 1.373), this change 1.615 s best
(median 1.811), +27% by minimums, +32% by medians. Still over the 25% predicted, so still failed,
by less: the dense run gone takes the answers held to Spark from 442,128 to 394,840 (master
347,552, +13.6%), and what is left is the fixed batch's rows and each run's second fill. The
catalyst matrix run (`--defaults --module catalyst --split 3 -j 3`, sanitizer on), which every
suite here and the emitter matrices' second fill reach, passed 618 tests, none failed, 40
cancelled opt-ins as before, in 393 s against 421 s before, on a quieter machine: the second
fill of the emitter matrices costs less than the run's noise.

### 10.2 Candidate rows found

- **An own-JVM suite reports its tests' own times.** `VarkaOwnJvm` reruns a suite in a child JVM
  and passes or fails each parent test from the child's report, so sbt's per-test time is the
  child's whole run for the first test asked and about zero for the rest: here 42.8 s printed for
  a 2.8 s test. The child's JUnit report holds each test's time; the parent test could report it
  (an `info` line, or the parent's own duration), so a measurement does not need the temporary
  directory.

### 10.3 Outcome (138b)

Done on 13 October 2026, as 3.3 and 3.4 describe, in `VarkaCoverageDifferentialSuite`.

* **`rid`.** Every fixture's view gains `rid`, the row's position in its VALUES, through one
  shared builder (`fixtureView`). No coverage row names it, and no query of the suite or of any
  other suite selects `*` from these fixtures (the per-row queries name their columns, and the
  fixtures are referenced nowhere else), so the per-row tests ran unchanged: 93 of 93 passed
  before and after.
* **The partition oracle (3.3, test 4).** One test per predicate row: on each of the three
  fixtures, under the default optimizer and with `BooleanSimplification`, `NullDownPropagation`
  and `InferFiltersFromConstraints` in `spark.sql.optimizer.excludedRules` of both sessions,
  `WHERE p` and `WHERE NOT (p)` run on Varka with a Varka filter in each plan whose kernel served
  the batches and left no conjunct to a row `FilterExec`, with batch pruning off on both sessions,
  each is compared on rows with Spark's, the two share no `rid`, and the rows in neither are Spark's
  `WHERE (p) IS NULL` on ids and values. 27 rows, 162 cases, 324 Varka filters: all agree. The
  comparison is a function of the six answers (`partitionFindings`), so the planted test feeds it
  real answers with Spark's unknown rows standing in for `NOT (p)`, which is what a filter that
  swapped known-false and unknown would return: on the mixed fixture under `d < d2` it names both
  the wrong false rows and the wrong rest, and passes the true answers. No option or hook breaks a
  kernel that way, so this shows the assertions see the swap, not that a kernel can make one; the
  probe of 2 is the evidence that `NOT (p)` reaches Varka as written with the rules excluded, which
  the per-row test, comparing `WHERE p` alone under the default optimizer, never runs. Given
  `WHERE p` and `WHERE NOT (p)` equal to Spark's, the disjointness and the rest hold Spark's three
  filters to each other: both engines share the optimizer, so a rewrite of `NOT (p)` both get wrong
  passes a differential and still breaks the partition.
* **The ANSI form (3.4, test 5).** `varka_coverage_extremes`: all twelve columns at their types'
  extremes (`9999-12-31` beside `0001-01-01`, `Int.MaxValue`, `Long.MaxValue` beside
  `Long.MinValue`, the largest year-month interval beside the smallest month count, the largest
  and smallest day-time intervals, `23:59:59.999999`) and one ordinary row, a third row of nulls
  filtered out by `rid` so every column stays nullable, one partition. One test per row, under
  ANSI and with the in-memory scan's batch pruning off on both sessions, since `rid < 0` rules out
  every batch by its statistics and a pruned batch meets no kernel. Outcomes compare as rows, or
  as condition, SQLSTATE and message parameters; one engine raising alone fails. A skipped
  position Varka does not serve with a kernel must be on the declined list with its reason, and a
  listed one that is served fails as stale, under the defaults; under a configuration of the option
  matrix only the outcomes are compared. Served means the kernels served a batch
  (`numVarkaBatches > 0`) and left nothing to the row engine - no projection entry in the residual,
  no row `FilterExec` above the Varka filter - since a node in the plan can decline every batch or
  fuse only the guard. A further test checks that every declined entry is one the reach check
  reads: a row the table has, a position its form runs, and not the lone position.

**Three corrections to 3.4's shapes.** As first written - `CASE WHEN rid < 0 THEN e ELSE <column>`
with the column of `e`'s type - the CASE reached a kernel for 26 of 65 rows, not the 61 it reaches
now, for two reasons the probe's date and int fixture did not meet:

1. *Varka compiles no bare int column as a value* (`non-date column of type int`, m10/SCOPE.md item
   55), so `ELSE i` declines every int-valued CASE. The int CASE's other arm is now `year(d2)`, an
   int the kernel computes that cannot raise; the int COALESCE keeps `coalesce(i, e)`, which stays
   on the declined list for the same reason.
2. *One kernel holds one lane*, so the int guard `rid < 0` over a long-lane value mixes lanes and
   declines; so does `rid >= 0 OR p` for a long-lane predicate (the AND was served, as a filter's
   conjuncts are fused apart). The extremes fixture carries `lrid`, `rid` as a BIGINT, and a
   long-lane CASE, AND or predicate is guarded by it.
3. *Varka fuses no `IS NOT NULL` over a computed value*, so position 4 as planned,
   `WHERE rid < 0 AND e IS NOT NULL`, put `isnotnull(e)` in a row `FilterExec` above a Varka filter
   that held only `rid < 0`: Spark evaluated `e`, not the kernel, in 61 of 65 rows (the other four
   the optimizer had rewritten onto columns). The probe of 2 counted those as fused, and so did the
   first version of this test, which asked only for a served batch; the review found it (10.4).
   Position 4 is now `WHERE <guard> < 0 AND e = <column of e's type>`, a comparison the kernel
   evaluates.

**The declined list, 38 entries.** 19 int-valued rows in the COALESCE (item 55); `hour(t)`,
`minute(t2)` and `second(t)` in the CASE, the COALESCE and the false AND, an int computed on the
long lane, which is narrowed only at a kernel's store (VARKA-28); `d + CAST(i AS INTERVAL DAY)` in
the same three, whose column bound declines the batch whichever side reads the column; and seven
rows behind the false AND whose check condemns the batch from the extremes row's lane although
`rid < 0` rejected it - `make_date(2021, i, 1)`, `add_months(d, i)`, `d + ym`, `d - ym`,
`ym - ymm`, `i + 1`, `year(d) + i` (item 96, below, for both).

**The form bites.** Under the matrix configuration `checkIntOverflow=false`, which emits ANSI
arithmetic without its overflow check, three rows fail alone, each "only Spark raises": `i + 1`
(Spark `ARITHMETIC_OVERFLOW`, SQLSTATE 22003, Varka the wrapped `-2147483648`), `year(d) + i`
(Varka `-2147473650`) and `ym - ymm` (Spark `INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION`, Varka
`P-1M`); they are in `sql/varka/matrix/skips.tsv` now, as the configuration breaks them by design.
That is Varka not raising where Spark does, from a real kernel. Varka raising where Spark skips has
no fault option: no kernel raises, it declines, and the row path evaluates what Spark evaluates. A
planted test feeds the comparison real outcomes - `i + 1` alone, `ARITHMETIC_OVERFLOW` on the
extremes row, standing in for the untaken arm's - and it fails as "only Varka raises", and the
reverse as "only Spark raises", and an error with one parameter changed as "errors differ".

**Runs.** At the defaults: 215 tests passed (93 before), and `dev/varka_matrix.sh --defaults
--module sql --suites VarkaCoverageDifferentialSuite`, sanitizer on, 215 passed in 74 s. The
configurations `cse=false` (215 passed) and `checkIntOverflow=false` (212 passed, the 3 above
failed). Then every configuration of the matrix, `--all -j 8`, 21:10 to 21:30 CEST: the defaults and
all 50 configurations passed, `checkIntOverflow=false` with its three tests cancelled by the new
skip lines. After the review's fixes (10.4), again, 21:55 to 22:20 CEST: every configuration's tests
passed, and the runner failed `guardUnderArm=false` for seven tests that fused under the defaults
and fuse nothing there - the rows whose CASE and COALESCE positions a kernel served only because
the arm qualifies their guard, and whose other positions raise or decline on the extremes row.
That is the configuration's design, as for three of its existing skip lines, so the seven are
`declines` lines in `skips.tsv`, and `--config guardUnderArm=false` passed with them.

**Batch pruning had to go.** With `spark.sql.inMemoryColumnarStorage.partitionPruning` left on,
all 92 tests failed: behind `rid < 0`, the false AND, no kernel served a batch for any row, since
the scan dropped the one batch on its statistics before the Varka node saw it. The check would have
passed every outcome by never running.

**Predictions scored (6.1), 138b's.**

3. **Held.** The suite took at most twice as long: three interleaved pairs of master's test file
   and this one on the same build, 20:49 to 20:59 CEST, load 2.8 to 5.3, by the JUnit report's
   suite time: master 48.8, 47.9, 40.9 s; this change 76.1, 66.8, 60.2 s. Minimums 40.9 and
   60.2 s (+47%), medians 47.9 and 66.8 s (+39%). In the `cse=false` configuration of the matrix,
   one run each: master 54 s, this change 90 s (+67%), though this change's ran beside the
   `checkIntOverflow=false` JVM and master's alone. The 122 new tests are short: the
   partition tests about 0.5 s each, the ANSI tests about 0.2 s. Those pairs predate the
   review's fixes (10.4), so three more ran on the suite as merged, 22:23 to 22:36 CEST on a
   busier machine, load 5.4 to 7.9: master 64.1, 62.1, 61.3 s; this change 97.4, 95.1, 103.1 s.
   Minimums 61.3 and 95.1 s (+55%), medians 62.1 and 97.4 s (+57%): still within twice.
4. **Failed for the COALESCE, held for the CASE.** Of the 65 projection rows, a kernel served the
   untaken CASE arm for 61 and the later COALESCE argument for 42, against at least 50 in both
   predicted; 42 rows reach both. The 23 the COALESCE misses are the int-valued rows (item 55), the
   three TIME fields (VARKA-28) and the column bound (item 96). Behind the false AND 54 of 65 were
   served (the eleven: the seven unqualified checks and the column bound of item 96, and the three
   TIME fields), and of the predicate rows all 27 behind the false AND and all 27 behind the true OR
   once the long-lane ones are guarded on their lane. Alone, 57 of 65 were served; the other 8
   raise on the extremes row on both engines alike - `d + CAST(i AS INTERVAL DAY)`,
   `make_date(2021, i, 1)`, `add_months(d, i)`, `d + ym`, `d - ym`, `ym - ymm`, `i + 1` and
   `year(d) + i` - where the kernel declines the batch and the row path raises, and no skipped
   position raised on either engine.
5. **Held.** The partition oracle and the ANSI form found no disagreement at the defaults or in
   any of the 50 configurations but `checkIntOverflow=false`, whose three are that option's design
   and not a finding. What the forms did find is reach: positions Spark skips that Varka hands to
   the row path. None is a wrong answer, so none serves milestone 7's done-when list (`PLAN.md`
   1.1, which asks the tests to reach these forms, as they now do), and each goes to the scope
   catalogue or is already there.

**Candidate rows found (138b).**

- **A check or a column bound qualified by the side that reads it** - `m10/SCOPE.md` item 96,
  added: seven rows' checks condemn the batch from the right side of a false AND, which
  `guardUnderArm` qualifies under a CASE arm but not there; and `d + CAST(i AS INTERVAL DAY)`
  declines its batch from every skipped position, because the bound on `i` is checked per batch
  before the kernel, whatever reads `i`.
- **Int values as CASE and COALESCE operands** - already `m10/SCOPE.md` item 55; the ANSI form now
  measures it: 19 int-valued rows unserved in the COALESCE, and the CASE served only through
  `year(d2)` standing in for a bare int column.
- **An int computed on the long lane under a CASE or COALESCE** - already VARKA-28's lane
  conversion: `hour(t)`, `minute(t2)` and `second(t)`.
- **An int guard over a long-lane value** - the same lane rule; the form guards on `lrid` rather
  than record a gap that VARKA-28 already owns.

### 10.4 The author's review (138b)

A `/code-review high` of the diff before the pull request opened (`sql/varka/AGENTS.md`, "Pull
requests: reviewed by their author before they open") found nine things; eight are fixed here.

1. **"Served" meant any Varka node served any batch.** True for the false AND whose kernel held
   only `rid < 0` while a row `FilterExec` above it evaluated `isnotnull(e)`. Served now also means
   no residual projection entry and no row `FilterExec` in the plan (`kernelServed`), and position 4
   compares `e` with a column (correction 3 above). That moved seven rows' checks and three more
   entries onto the declined list and found the first half of item 96.
2. **The partition oracle asked only for a Varka filter in the plan,** with batch pruning on, so a
   pruned or declined batch would have compared Spark with itself. It now asks `kernelServed` of
   both of Varka's queries, with pruning off; all 162 cases still agree, every one served.
3. **An error with no condition was kept as the job failure's class and message**, whose stage and
   task numbers differ between runs; it is the root cause's now.
4. **Varka's planning ran outside the outcome's `Try`**, so an error raised while planning would
   have aborted the test instead of comparing; analysis, planning and execution run inside now
   (`run`), for both engines.
5. **The stand-in can widen the expression's type** (`t - t2`, HOUR TO SECOND, beside `dt2`): kept,
   since the widening cast is evaluated only where `e` is, and the scaladoc no longer claims the
   type is unchanged.
6. **The predicate's lane was read from every filter of the analyzed plan**, the fixture view's
   `WHERE rid < 2` among them; it is read from the predicate analyzed as a projection now.
7. **Not changed: the outcome model duplicates `VarkaSparkFuzz.outcome`.** The fuzzer's outcome is a
   string signature its shrinker and failure records key on, comparing the condition alone; making
   it carry SQLSTATE and parameters changes what every recorded fuzz failure looks like, a change of
   its own rather than a by-product of this one.
8. **`withBothConf` duplicated `withAnsi`.** It moved into `VarkaSharedSessions` beside `withAnsi`,
   which is now written with it.
9. **The declined list's check accepted entries no reach check reads** (the lone position, a
   position the row's form never runs); it rejects them now.
