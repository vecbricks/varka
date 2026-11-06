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

1. **A manifest, `sql/varka/ci/sweeps.tsv`**: one row for each suite that reads a sweep property,
   and for each shard of one too long for a job: suite, kind (`java` or `scala`), property, how a
   job selects it (`class`: the whole suite with the property set; `filter`: a ScalaTest suite's
   opt-in tests by name; `shard`: the part of a JUnit suite whose test names contain one of the
   row's texts), the select text, the job that runs it or `unscheduled`, and a note.
2. **A check, `dev/varka_sweeps.py --check`**, run by the pre-commit hook and by the lint job. It
   fails a suite that reads a sweep property and has no row, a row whose suite no longer does, a
   wrong kind or property, a duplicate, a JUnit suite listed as `filter`, which is how five
   suites went unrun (section 0, item 3), and shards that do not between them select each opt-in
   test exactly once, so a sweep added to a sharded suite lands in a shard or fails.
   `--self-test` pins each case, and `--matrix` prints the scheduled jobs as JSON for the workflow.
3. **A scheduled workflow**, `varka-sweeps.yml`, at night like `varka-fuzz.yml`, with
   `workflow_dispatch`. It builds a job matrix from `--matrix`, on `ubuntu-latest` and on
   `ubuntu-24.04-arm`, each job one run of the project's own runner, `VarkaSuiteRunner` (the one
   `dev/varka_matrix.sh` starts), over a classpath exported once, with `-s <class>` and a `-z` for
   each of its shard's texts, the property set, `-Dvarka.ownJvm=true` and `-Dspark.test.timeout`
   raised. That runner matches a `-z` to a JUnit test's display name, which sbt's `testOnly -z`
   does not. Without `-Dvarka.ownJvm=true` a `@VarkaSuite(ownJvm = true)` suite forks a child JVM
   that runs the whole suite, and each test then reads its result from the child's report
   (`VarkaOwnJvm`), so a `-z` shortens nothing; the property runs the tests in the JVM at hand, as
   `varka-fuzz.yml` does. A failure turns the job red and uploads the JUnit reports.
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
minutes. The narrow-arithmetic suite is cut by operator into three shards of about 1100 s on the
laptop each (`+` 1111, `-` 1065, `*` 1177: the 12 sweeps of 200 to 320 s of the three modes'
LEGACY and TRY arms, and the six ANSI ones of about 55 s), which on the same reading is about 1.8
hours a shard, and a fourth shard for its few-second timing test. The first step of the workflow is therefore a calibration: one sweep dispatched on each
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


### 10.2 Step 2: the narrow-arithmetic shards (5 November 2026)

The suite has 19 tests whose display names say `opt-in`: the 18 all-pairs `SMALLINT` sweeps (three
operators, three modes, two arms) and a timing test. `sweeps.tsv` lists four `shard` rows for it,
one for each of `pair for + in`, `pair for - in` and `pair for * in`, and `oracle's share`; the
check finds each of the 19 selected exactly once. The shard texts run through
`VarkaSuiteRunner -s <class> -z <text>`, whose filter matches a display name by substring.

Checked through `VarkaSuiteRunner` over the exported test classpath with `-Dvarka.sweep=true` and
`-Dvarka.ownJvm=true`: `-z "oracle's share"` ran the one test and `-z "pair for + in ANSI"` the
two ANSI sweeps for `+`, all passing. A first try without `-Dvarka.ownJvm=true` forked the child
JVM, which ran the whole suite; it was stopped after more than ten minutes.

### 10.3 Step 3: the nightly script reads the manifest (5 November 2026)

`dev/varka_sweeps.py --nightly` checks the manifest, then prints the sbt commands of the nightly's
sweep step: for each property, `set Test/javaOptions` with the property and the raised test cap and
watchdog (170 minutes, under the step's default deadline of 10800 s), a `testOnly` of the ScalaTest
suites with their filter text, and a `testOnly` of the JUnit suites whole. The shard rows of a suite
run as that whole suite here, since sbt's `-z` selects none of a JUnit suite's tests. The step in
`dev/varka_nightly.sh` runs those commands in one `build/sbt -batch`, and a manifest that fails its
check makes the step `FAILED` with the findings in `sweep-manifest.err`. The suites that the old
filter skipped now run; by the times of section 3 the step takes about 5400 s of the laptop. The
gate's sweep step (`dev/varka_gate.sh`) keeps its calendar filter on purpose: it is the quick one.
The step has not run on the laptop yet.

### 10.4 Steps 3 and 4: the laptop run and the workflow draft (5 November 2026)

**The nightly's sweep step ran once on the laptop** (the commands of `dev/varka_sweeps.py
--nightly`, on master `3f4b1fb22da`): every suite passed, exit 0 in 6127 s, against the 5400 s of
section 3, 13% more. That includes the suites the old name filter skipped.

**`varka-sweeps.yml`**, dispatch only: a build job checks the manifest, lists the rows with
`dev/varka_sweeps.py --matrix` (every row, one suite's, or only the scheduled ones) and archives the
test classpath as the option matrix does; each row then runs as one job on `ubuntu-latest` and on
`ubuntu-24.04-arm` through `VarkaSuiteRunner`, with the options of `--jvm-args` (the sweep's
property, a 300-minute cap of a test and of the watchdog, and `-Dvarka.ownJvm=true` for a JUnit
suite) and the arguments of `--runner-args` (`-s`, and a `-z` for each text). The step is tested
against a stub `java` by `dev/varka_bin_units_test.py`: a JUnit shard, a ScalaTest filter, a long
sweep, and a failing status. It has not run on a runner. No schedule yet: the first dispatch, of
one suite on each runner type, is the calibration of section 3.

### 10.5 The calibration and the schedule (6 November 2026)

**Two dispatches of `varka-sweeps.yml`** on vecbricks/varka, at master `53a81e281f2`, timed the
`Run the sweep` step on each runner type, against the laptop's times of section 3:

| Sweep | Laptop | `ubuntu-latest` | `ubuntu-24.04-arm` |
|---|---|---|---|
| `VarkaEmitterFloatingArithSuite` (run 42167775744) | 148 s | 550 s | 364 s |
| narrow `+` | 1111 s | 1103 s | 1344 s |
| narrow `-` | 1065 s | 1141 s | 1361 s |
| narrow `*` | 1177 s | 1671 s | 1618 s |
| narrow oracle's share (run 42171236541) | a few seconds | 4 s | 4 s |

The runner is 1.0 to 1.4 times the laptop on the narrow shards and 2.5 to 3.7 times on the float
sweep, not the 6 times section 3 assumed: the narrow sweeps barely use the laptop's 24 threads.
The build job took 10 minutes and a job's setup under 15 s. Not timed: the other suites, among
them `VarkaTimeFractionSuite` (991 s on the laptop), whose factor is somewhere in 1.0 to 3.7, so at
most about an hour. The longest timed job is the narrow `*` shard, 28 minutes.

**The schedule**, by the owner's word of 6 November 2026: every Saturday at 02:00 UTC, and on
dispatch, since a sweep's domain is fixed and its result changes only with the code. Every row of
the manifest names `varka-sweeps` as its job; a failed or cancelled scheduled run opens an issue as
the fuzz and the matrix runs do. The first scheduled run is to be cited here.

