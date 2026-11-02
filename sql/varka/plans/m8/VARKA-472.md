# VARKA-472: A test's option premises are declared where it reads them, and the matrix holds them

## 0. What exists now (admission check)

From master at `5c660201b35`, read on 1 November 2026 with `git`. Nothing was run. The local
branch `varka-472-473` holds only the commit that filed this row and row 473, merged as #980.

1. **How a test meets the matrix today.** `VarkaMatrixRuns.around` (`VarkaMatrixRuns.java`, "Running
   one test") runs a test's body. If `skips.tsv` has a `fails` line for this configuration, suite
   and test, a failure is cancelled with the line's reason and a pass fails as "stale entry". A
   test with no line gets nothing: its failure under a configuration is the bare assertion,
   with no word on the configuration or the remedy. The Scala face (`VarkaMatrix.around`, through
   `VarkaMatrixTests.test`) and the JUnit face (`VarkaSuiteExtension.runTest`) both call it.
2. **The three remedies and their record.** `skills/testing-and-debugging.md` names them in two
   places, the paragraph after "A test that pins an emitted shape" and the section "A planted bug
   has a prerequisite":
   - pin the option, with the reason beside it;
   - `assume`-cancel the case where a pin would rerun the defaults;
   - list the test in `sql/varka/matrix/skips.tsv`.

   Rows 470 and 471 took each of them, and 471 records that a pin of the very option a
   configuration changes reruns the defaults' coverage while the shard reports it as the
   configuration's. Nothing checks that rule: it is prose.
3. **The pins are many and undeclared.** Test sources call `VarkaMatrix.base.with...`,
   `VarkaMatrixRuns.BASE.with...` or `base.with...` at 236 sites in 47 files. Most are the arms
   of an A/B, which set an option both ways on purpose, and a few are premises; the source does
   not say which. Five sites in test sources `assume` or `cancel` on the configuration.
4. **The save and restore idiom.** `VarkaColumnarToRowExec.setEmitOptionsForTesting` is called
   from 24 test files in sql/core. Some restore the value they captured (`previous`):
   `VarkaErrorContextSuite`, `VarkaFallbackReasonSuite`, `VarkaRunbookSuite`,
   `VarkaReproductionEndToEndSuite`, `VarkaSparkDifferential`, `VarkaSparkFuzzSuite`,
   `VarkaProjectExecSuite`, `VarkaCoverageDifferentialSuite`. Others restore a fixed value:
   - `VarkaMatrix.base` or `BASE`: `VarkaDifferentialSuite`, `VarkaFarDaySuite`,
     `VarkaFilterRaiseOrderSuite`, `VarkaKernelEvaluatorSuite`, `VarkaMaterializedPrefixSuite`,
     `VarkaSeveralKernelsSuite`, `VarkaHarnessCases`, `VarkaCoverageHarnessSuite`;
   - `VarkaEmitOptions.DEFAULTS`, from an `@AfterAll` or `afterAll`: `VarkaCoverageHarnessSuite`,
     `VarkaDifferentialHarnessSuite`, `VarkaTimeHarnessSuite`, `VarkaSQLQueryTestSuite`.

   A fixed restore is harmless while the value it replaced was that same fixed value, which it is
   today in each case read. `VarkaSharedSessions.beforeAll` sets `VarkaMatrix.base` for every
   Scala suite that mixes it in, and the Java harness suites set `BASE` in their own
   `@BeforeAll` or `@AfterEach`. It breaks when a helper runs inside another change: for instance
   inside `VarkaSQLQueryTestSuite`'s planted fault, which sets planted options in `beforeAll`, any
   helper restoring `base` would drop the plant. Whether a matrix JVM runs a class that starts
   from what a previous `@AfterAll` left is **[I]**: it depends on the bin's suite order, which
   this check did not read.
5. **The decline check's message.** `dev/varka_matrix_report.py:167` prints `fuses nothing,
   fused under the defaults: <suite> / <test>`. It does not say that the remedy is a `declines`
   line, nor give its syntax.
6. **Planted faults.** Row 504's plan (branch `varka-m8-triage`) requires every check the
   milestone rests on to come with a planted case that proves it can fail. A new runner check here
   follows it.

### What differs from the row text

- "Fails locally" has two readings (question 1). Under one, a test can be checked without running
  the configuration. Under the other, the test is run under the configuration on the developer's
  machine (`dev/varka_matrix.sh --module <m> --suites <Suite> --config <option>=<value>`, the
  skill's own advice), and fails there with the option and the remedies named instead of a bare
  assertion. Only the second can be built: an option's effect on a test's premise shows only when
  the test runs with the option changed. This plan builds the second, and its checks of
  declarations work without running the configuration.
- "Used by every suite that changes options for a test" covers the 24 files of item 4, not the
  236 sites of item 3, which set the options of a kernel a test emits directly and do not go
  through the session hook.

## 1. Goal and non-goal

**Goal.** A test that depends on an option says so in its body, in one of two declared forms the
runner understands, a pin or a premise. The runner enforces both under every configuration. A
test that fails under a configuration it did not declare against fails with a message naming
the configuration's options and the three remedies, the skip line ready to paste. The session
options are changed through one helper that restores what it captured.

**Non-goal.** No change to `skips.tsv`'s format or its existing lines, and no migration of the
A/B arms of item 3. No change to `VarkaTestReach`'s selection.

## 2. Design

### 2.1 The two declarations

Both live in `VarkaMatrixRuns`, with Scala forwarders on `VarkaMatrix`. They record into the
current test's record, a thread-local that `around` opens and closes.

- `VarkaMatrixRuns.pin(options, spec, reason)` returns `options` with `spec`, a `name=value`
  pair, applied through `VarkaMatrixRuns.parseOver`, and records the pin. That reuses the
  matrix's own parsing and checks for every kind of option, count, flag and choice. A test
  keeps the matrix's other options and fixes the one it needs: `pin(VarkaMatrix.base,
  "checkIntOverflow=true", "a NULL-mode add that nulls a valid input needs the check")`.
- `VarkaMatrixRuns.premise(name, reason)` records that the test's subject needs option `name` at
  its default, and returns nothing. It is the declared form of today's `assume`.

The names are checked against `VarkaEmitOption.TABLE` at the call: a misspelt name throws, as
`parse` does.

### 2.2 What `around` does with them

After the body, `around` compares the test's record with the options this JVM's configuration
changes. Those are the names in `CONFIG`, with a subject flag's whole set and with
`arrowBatchSize` and `lanesOverride` named as they are.

- **A pin of an option the configuration changes, to a value other than the configuration's,
  where that option is all the configuration changes.** The test reran the defaults, and a pass
  is turned into a cancel: "pins `<name>`, which `<config>` changes alone: the run repeated the
  defaults' (reason)". A pin to the configuration's own value is not touched: the test ran the
  configuration's arm. A failure stays a failure,
  since the pin was meant to make it pass. This is row 471's rule, enforced.
- **A premise of an option the configuration changes.** As a `fails` line does: a failure is
  cancelled with the premise's reason, and a pass fails as a stale premise, "`<name>` is
  declared a premise but the test passes under `<config>`; drop the declaration". So a premise
  carries the same accountability as the skip list.
- **An undeclared failure under a configuration.** The failure's message gains a paragraph
  naming the configuration's options and the remedies: `pin(...)`, `premise(...)` or the line
  `<config>\t<suite>\t<test>\tfails\t<reason>` for `skips.tsv`. It also says that the failure may
  instead be a bug of the configuration's form. The original failure stays the cause.
- **Under the defaults**, nothing changes, except that a declared name is validated.

A test with both a `skips.tsv` line and a declaration for the same configuration fails at the
declaration's call, which knows the suite and the test from the thread-local, naming both, so a
dependency is recorded in one place.

### 2.3 `withEmitOptions`

`VarkaSharedSessions`'s companion object gains `withEmitOptions[T](change: VarkaEmitOptions =>
VarkaEmitOptions)(body: => T): T`, with a Java-callable form taking a `UnaryOperator` and a
`Supplier`. It captures `VarkaColumnarToRowExec.currentEmitOptions`, sets `change(captured)`, and
restores the captured value in a `finally`. Each of item 4's 24 files moves its set and restore
onto it, and a class-wide set (`beforeAll`/`@BeforeAll` with its `afterAll`) becomes one helper
call at the class's start and its end. `dev/varka_lint.py` gains a rule: a call of
`setEmitOptionsForTesting` outside `VarkaSharedSessions` and `VarkaColumnarToRowExec` is a
finding. That keeps new suites on the helper.

### 2.4 The decline check's message

`dev/varka_matrix_report.py`'s unfused line ends with the remedy: `list it as
<config>\t<suite>\t<test>\tdeclines\t<reason> in sql/varka/matrix/skips.tsv`. That is the
syntax of the file's header.

### 2.5 The known premises move to the declarations

The pins and cancels the record names become declarations, with their reasons kept. Those are
row 470's three tests, `VarkaUnreadLocalsSuite`'s month-once case (row 411), the five `assume` and
`cancel` sites of item 3, and the day-producer and validity pins that
`skills/testing-and-debugging.md` lists. The skill's two passages are rewritten to name the two
forms and the runner's checks.

## 3. Tests and proofs

1. `VarkaMatrixSuite` (Scala) and `VarkaSuiteHarnessSuite` (JUnit), through `around`'s seam
   with a configuration, a skip list and a body as parameters:
   - a pin under its own option's configuration that passes is cancelled with the message;
   - a pin under another configuration runs and passes;
   - a premise under its configuration that fails is cancelled, and one that passes fails as
     stale;
   - an undeclared failure carries the remedies' paragraph with the paste-ready line;
   - a declaration and a skip line for one test fail at the declaration;
   - a misspelt name throws.
2. The planted case of item 6: a test whose premise is planted false (a body that fails only
   under `checkIntOverflow=false`), with no declaration, must fail under that configuration with
   the paragraph. With the declaration it is cancelled.
3. `withEmitOptions` restores a captured non-default value: nested inside another call, and
   when the body throws.
4. `dev/varka_matrix_report.py --self-test`, if it has one, or a selftest case added: the unfused
   line names the skip syntax.
5. The dispatched option matrix on the branch, which is the verification. Every configuration
   must be green, with the converted tests cancelled where row 471's rule says.

## 4. Predictions and costs, registered before the build

- P1: row 470's two converted pins turn from passes into cancels under their own option's
  configuration: `VarkaEmitterNullSafeSuite`'s test under `checkIntOverflow=false`, and
  `VarkaUnreadLocalsSuite`'s month-once case under `materializeChronoPrefix=false` where its
  `assume` did not already cancel it. No other test's outcome changes.
- P2: no undeclared failure appears in the branch's matrix. If one does, it is a premise the
  record did not have, and it is declared in the same pull request.
- P3: the runner's cost is a set comparison per test, below anything a step's time shows
  (`sql/varka/ci/step-budget.tsv` unchanged).

## 5. Order, files and size

| File | Change |
|---|---|
| `VarkaMatrixRuns.java`, `VarkaMatrix.scala` | `pin`, `premise`, the record, `around`'s checks |
| `VarkaSuiteExtension.java` | nothing beyond what `around` does; a test in its suite |
| `VarkaSharedSessions.scala` | `withEmitOptions` |
| the 24 files of item 4 | onto the helper |
| the tests of 2.5 | the declarations |
| `dev/varka_matrix_report.py`, `dev/varka_lint.py` | the message; the rule |
| `sql/varka/skills/testing-and-debugging.md` | the two passages |

Order: the declarations and `around`'s checks with their tests; `withEmitOptions` and the 24
files; the known premises; the report and the lint; the matrix dispatch.

Size: medium, as the row says. The 24 files are mechanical and the runner's change is in one
class.

## 6. Out of this row

- The 236 direct `base.with...` sites of item 3, unless question 2 brings them in.
- Moving existing `skips.tsv` lines into premises: both forms stay valid.

## 7. Questions for the owner

1. **"Fails locally".** (a) A test run locally under the configuration fails with the option and
   the remedies named (this plan). (b) A check that needs no configuration run, which would
   require knowing, from the defaults' run alone, that a test's assertions depend on an option.
   Nothing in the harness can see that, since the option acts through the emitter's bytes.
   Recommended: (a), with the skill's sweep command named in the message.
2. **The A/B sites.** Should every `base.with...` in a test become a declared `pin`, so that the
   runner sees all of them, or only the premises? Recommended: only the premises. An A/B arm sets
   both values, and a pin's cancel rule would misfire on it.
3. **A pin's pass under its own option's configuration.** (a) Cancel it (row 471's rule, this
   plan). (b) Count it as a pass. Recommended: (a). A green result there claims coverage the run
   did not have.

## 8. Done when

- a test that fails under a configuration it did not declare against, and has no skip line, fails
  locally under that configuration with a message naming the option and the remedies;
- the dependency is declared in the test's body as a `pin` or a `premise`, and `around` checks
  both;
- `withEmitOptions` on `VarkaSharedSessions` restores the captured value and is the one way the
  suites change the session's options;
- the decline check's message names the skip line's syntax;
- the branch's option matrix is green.

## 9. Open points for the lead

1. Row 508's selection keys tests by what they ran. A test whose premise is declared may be
   cancelled where row 508 would have skipped it, which is harmless, but the two rows touch
   `around` and should not land in the same week.

## 10. Outcome
