# VARKA-574: every skipped Varka test names its reason in its JUnit report

## 1. Where this came from

Row 574 of `PLAN.md`, from the count of 5 November 2026 over the night run. 52 of the 92 tests
skipped at the defaults carried no reason in their JUnit report: 28 said `opt-in` in their name
and 24 said nothing (14 in `VarkaAssemblySuite`, two in `VarkaEmittedBytesSuite`, one each in
five census suites and in `VarkaEmitterYmDivideSuite`, and the rest elsewhere). A skip with no
reason cannot be told from a test that never ran. The register-owed skips that do name their
reason ("owed by the registers") are the ones that must flip when a shape fuses. The row's
admission check asks for each of the 24 to be read and called an opt-in, a missing resource or a
dead test.

## 2. The admission check, done

Read from Build 44179170857 (17 November 2026), whose six `varka-scoped-junit-*` artifacts
hold every Varka suite's report at the defaults, 208 files written through
`VarkaSuiteRunner` (`results/VARKA-574-skips.txt`).

* **The problem holds, and has grown.** 115 tests are skipped. 52 carry a reason and 63 do not:
  36 of the 63 say `opt-in` in their name, and 27 say nothing. That is three more than the row's
  24. The row does not name all of its 24, so which three are new cannot be read from it.
* **The cause is the reporter, not the tests.** Every one of the 63 is a ScalaTest test, and every
  ScalaTest skip in the Build has no reason. Every one of the 52 is a JUnit 5 test, and every
  JUnit 5 skip has one. `VarkaSuiteRunner` runs the ScalaTest suites through ScalaTest's own
  `-u` reporter (`org.scalatest.tools.JUnitXmlReporter`), which writes a cancelled test as
  `<skipped/>` and drops the message of its `TestCanceled` event. It runs the JUnit 5 classes
  through Varka's own `VarkaSuiteListener`, which keeps `assumeTrue`'s message.
* **The 27, read.** Each cancels through `assume` or `cancel` with a message
  (`results/VARKA-574-skips.txt` lists them): 14 need the hsdis disassembler, which the Build's
  assembly gate job installs and requires (a missing resource, by design); 6 census tests and 4
  others are opt-in by a property or an environment variable; 2 need the memory sanitizer, which
  the Build's quiet step runs without and the option matrix runs with; 1 is a host skip (another
  CPU model's committed census). **None is dead.** Each names a condition that another job or an
  opt-in run meets.

What the check would have rejected: rewriting 27 tests to say what they already say. The fix
belongs in one place, the runner.

## 3. The design

### 3.1 The runner writes the message the reporter drops

`VarkaSuiteRunner` already attaches a reporter of its own (`SummaryReporter`, `-C`). That
reporter also records, per `(suiteClassName, testName)`, the message of each `TestCanceled`
event. A `TestIgnored` is recorded as `ignored, no reason given`, and a `TestPending` as
`pending`. After `org.scalatest.tools.Runner.run` returns, the runner rewrites the `<skipped/>`
of each recorded test in `<-u dir>/TEST-<suiteClassName>.xml` to
`<skipped message="..."/>`, with the message XML-escaped.

The rewrite is textual, inside the `<testcase>` whose `name` attribute equals the event's test
name XML-escaped as the reporter escapes it (names such as "&, | and ^ match Spark ..." in
`VarkaEmitterBitwiseSuite` hold `&`). Reparsing and rewriting
the file would reorder its attributes and line breaks, and `dev/varka_scoped_suites.sh`'s
`failed_tests` and `dev/varka_ci_ledger.py` read the reporter's layout as it is.

Alternatives considered: a Varka ScalaTest reporter writing the whole file in
`VarkaSuiteListener`'s format, with `-u` dropped (rejected: about four times the code, and every
reader of the ScalaTest layout moves with it); and messages in the test names (rejected: the
names are the tests' identity in `skips.tsv`, the reach record and the ledger).

### 3.2 The check

`dev/varka_skip_reasons.py DIR...` lists every `<skipped>` under the JUnit directories given
that has no message, or only `ignored, no reason given`. It exits 1 when any is found. It runs in
`dev/varka_matrix.sh`'s `report()`, beside the sanitizer proof, so the Build's varka-scoped
jobs, the option matrix's report and the nightly's runs all fail on a skip without a reason. The
same file prints a tally by reason prefix (`owed by the registers`, `opt-in`, `set -D...` and the
rest), so a register-owed skip that flips is visible as a count that moves.

### 3.3 What is deliberately unchanged

The tests and their messages. sbt's own `JUnitXmlTestsListener`, which Spark's full module jobs
use when a pull request leaves Varka's scope. Those jobs are Spark's, and the row is about
Varka's runs.

## 4. Files

| file | what |
|---|---|
| `VarkaSuiteRunner.java` | the recorded messages and the rewrite |
| `VarkaSuiteHarnessSuite.java` | the tests of 5 |
| `dev/varka_skip_reasons.py` | the check, its tally and `--selftest` |
| `dev/varka_matrix.sh` | the check in `report()` |
| `sql/varka/skills/testing-and-debugging.md` | one paragraph: a skip must say why |

## 5. Tests, and what each is for

* `VarkaSuiteHarnessSuite`, "a cancelled ScalaTest test's JUnit report carries the cancel's
  message": runs a fixture suite with an `assume` and a `cancel` through `VarkaSuiteRunner.run`
  and reads both messages back from the report. It fails if the rewrite misses a test.
* The same suite, "a message with XML's special characters survives the rewrite": `<`, `&` and
  `"` in a cancel message and in the cancelled test's name. It catches an unescaped attribute
  that breaks the file for every reader, and a name matched unescaped, which would leave the
  skip reasonless and fail the check.
* The same suite, "a suite's other test cases are byte-identical after the rewrite": it guards
  the layout that `failed_tests` and the ledger parse.
* `dev/varka_skip_reasons.py --selftest`: a file with a reasonless skip fails, one with
  `ignored, no reason given` fails, and one of the Build's reports with messages passes.

## 6. The measurement

### 6.1 Predictions, registered before the run

1. A Build after the change: 0 skipped Varka tests without a reason, against 63 in Build
   44179170857, and the same 115 skipped.
2. The rewrite costs under 50 ms per runner JVM. It rewrites at most a few files of a few hundred
   lines, with no calibration row this small to apply (`dev/varka_calibration.py`).

## 7. Risks

1. ScalaTest's event names a test by its full name and the XML names it the same way, but a
   nested suite (`-s` of a `Suites` wrapper) reports a child suite's class. The rewrite keys on
   the event's `suiteClassName`, the name the reporter uses for the file; test 1 runs a wrapper.
2. A run killed mid-suite leaves no XML to rewrite, so the check sees nothing for that suite,
   which is the state today.

## 8. Sequencing

One pull request of about 250 counted lines.

1. The reporter and the rewrite with the three harness tests. Done when
   `build/sbt 'catalyst/testOnly *VarkaSuiteHarnessSuite'` passes.
2. The check and its wiring. Done when `python3 dev/varka_skip_reasons.py --selftest` passes and,
   on the laptop, `dev/varka_matrix.sh --defaults --module catalyst --suites VarkaChronoSuite`
   reports its four opt-in sweeps with `set -Dvarka.sweep=true...` and no reasonless skip.
3. The skill paragraph and `dev/varka_toc.py SKILLS.md --from sql/varka/skills`.
4. The pull request's Build. Done when its varka-scoped jobs pass the check and prediction 1 is
   scored from their JUnit artifacts.

## 9. Done when

Every skipped Varka test names its reason in the JUnit message, a check fails a skip with none,
and the night's JUnit shows none.
