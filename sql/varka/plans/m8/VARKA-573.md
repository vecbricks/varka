# VARKA-573: the exhaustive sweeps run in a scheduled job

Row 573 of `PLAN.md`. Found on 5 November 2026 while verifying row 568.

## 0. What exists now (admission check)

An exhaustive sweep is a test that does nothing unless a system property switches it on:
`-Dvarka.sweep=true`, or `varka.sweep.long` for the two over about ten minutes. It is the only
complete check of an operator with a 32-bit domain: every float pattern through `+`, `-` and `*`,
every int through `~` and `bit_count`, every day of the covered years, every pair of `SMALLINT`
values. A fuzzer samples; a sweep covers every input.

1. **No workflow runs one.** A search of `.github/workflows` for the word "sweep" finds nothing. A
   PR's Build and its option matrix run none of them.
2. **Two scripts run them, by hand.** `dev/varka_nightly.sh` and `dev/varka_gate.sh` run "the checks
   that need volume or an idle machine" on one laptop and leave a dated log. The newest nightly log
   on the laptop is dated 26 September 2026, and the newest gate logs 26 October 2026.
3. **The nightly's sweep step does not run every sweep.** It runs `testOnly *VarkaChronoSuite
   *VarkaEmitter*Suite -- -z opt-in`, and a name filter selects no test of a JUnit suite. On
   5 November 2026 it ran 48 tests and skipped 47 of `VarkaEmitterFloatingArithSuite`, 27 of
   `VarkaEmitterFloatingCastSuite`, 8 of `VarkaEmitterIntegralCastSuite`, 51 of
   `VarkaEmitterNarrowArithSuite` and 16 of `VarkaEmitterNarrowSuite`, with exit status 0. The float
   arithmetic sweep and the narrow-integer pair sweeps are in two of those suites; the cast suites'
   skipped tests have not been read for sweeps.
4. **Nine suites read a sweep property, all in `sql/catalyst`; none in `sql/core`.**
   `VarkaCoverageSuite`'s opt-in test reads none (it checks the sources' text).

## 1. Goal and non-goal

Goal: every sweep that fits runs in a scheduled job, on x86 and on Arm, and a new sweep cannot be
added without a row saying which job runs it. A red night is reported as the fuzz night is.

Not in this row: making a sweep faster, adding sweeps, or the Arm runner for the PR's own
suites (row 505).

## 2. Design

1. **A manifest, `sql/varka/ci/sweeps.tsv`**: one row for each suite that reads a sweep property:
   suite, kind (`java` or `scala`), property, how a job selects it (`class`: the whole suite with
   the property set; `filter`: its opt-in tests by name), the job that runs it or `unscheduled`,
   and a note.
2. **A check, `dev/varka_sweeps.py --check`**, run by the pre-commit hook and by the lint job. It
   fails a suite that reads a sweep property and has no row, a row whose suite no longer does, a
   wrong kind or property, a duplicate, and a JUnit suite listed as `filter`, which is how five
   suites went unrun (section 0, item 3). `--self-test` pins each case.
3. **A scheduled workflow**, `varka-sweeps.yml`, at night like `varka-fuzz.yml`, with
   `workflow_dispatch`. It builds a job matrix from the manifest's rows whose job is not
   `unscheduled`, on `ubuntu-latest` and on `ubuntu-24.04-arm`, each job one `testOnly` of its
   suites with the property set and `-Dspark.test.timeout` raised. A suite over the six-hour cap
   is sharded by test name (`-z`). A failure turns the job red and uploads the JUnit reports.
4. **The nightly script reads the manifest**, so that a suite it lists as `class` runs whole, and
   the second selector that dropped five suites is gone.

## 3. Times and the fit

Measured on the laptop (24 threads) on 5 November 2026, `results/VARKA-573-sweep-times.txt`: the
nightly's sweep step took 2004 s; the two suites it skips that read a property, narrow and float
arithmetic, take 3568 s in all with the coverage suite's check. Per suite, in seconds: `VarkaEmitterNarrowArithSuite` 3389,
`VarkaTimeFractionSuite` 991, `VarkaEmitterYmDivideSuite` 640, `VarkaEmitterFloatingArithSuite` 148,
`VarkaEmitterDivisionSuite` 106, `VarkaEmitterBitwiseSuite` 68, `VarkaEmitterChronoSuite` 51,
`VarkaEmitterMixedLaneSuite` 8, `VarkaChronoSuite` 1. The sum is about 5400 s, 90 minutes of 24
threads.

A GitHub runner has 4 cores, so each sweep takes about six times as long on one if it scales
with cores, and a runner's core is slower than the laptop's. Neither is measured. On that reading
`VarkaEmitterNarrowArithSuite` is about 5.6 hours or more on one runner, so it needs shards;
`VarkaTimeFractionSuite` about 1.6 hours and the year-month divide about 1.1; the rest are
minutes. The first step of the workflow is therefore a calibration: one sweep dispatched on each
runner type, and the table above scaled by what it reads.

## 4. Order

1. The manifest and its check (built, branch `varka-573-sweeps`).
2. The calibration dispatch on both runner types, on the owner's word.
3. The workflow and the shards, and the nightly script's use of the manifest.
4. The Arm runs, with row 505's runner pool.

## 5. Done when

A scheduled workflow runs the sweeps of the manifest whose times fit, on x86 and on Arm, and
reports a red night; the ones that do not fit are listed in `dev/varka_nightly.sh` with the reason;
the check passes in the pre-commit hook and the lint job; and one night of the workflow is cited.

## 10. Outcome

### 10.1 Step 1: the manifest and its check (5 November 2026)

Built on branch `varka-573-sweeps`: `sql/varka/ci/sweeps.tsv` lists nine suites, all
`unscheduled` until the workflow exists; `dev/varka_sweeps.py` checks it with a 14-case
self-test, run by `dev/varka_precommit.sh` for a change to a test source, the manifest or the
script, and by the lint job. Its discovery reads a sweep property through `getProperty`,
`getBoolean`, `getInteger` or `props`; a string that only names the property, as the coverage
suite's source check does, reads nothing.
