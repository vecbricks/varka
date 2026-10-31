# VARKA-537: The exhaustive sweeps run on CI, and a sweep filter is proven to select something

*Planned 31 October 2026. The row was added on 30 October by the owner's word, from the plans of
rows 30, 157 and 481. Evidence not in the code is in `results/VARKA-537-admission.txt`: the
gate's sweep step as it last ran, the five gates' step times, and where the nightly runs.*

## 0. What exists now (admission check)

### 0.1 The sweeps today

A sweep here is a test whose name says `opt-in: -Dvarka.sweep=true` or `long-sweep:
-Dvarka.sweep.long=true` and which cancels itself (`assume`, JUnit's `assumeTrue`) unless that
property is set. Eight catalyst suites hold 44 of them. The times are the laptop's, from the
gate's sweep step of 26 October (`results/VARKA-537-admission.txt` 1) unless the column says
otherwise.

| suite | kind | sweeps | gate | nightly script | CI | laptop time |
| --- | --- | ---: | --- | --- | --- | --- |
| `VarkaChronoSuite` (`:316`, `:370`, `:445`, `:460`) | Scala | 4 | yes | yes | no | 5 s together |
| `VarkaEmitterChronoSuite` (`:621`, `:689`, `:881`, `:934`) | Scala | 4 | yes | yes | no | 113 s together |
| `VarkaEmitterDivisionSuite` (`:216`, `:333`, `:376` per divisor) | Scala, own JVM | 13 | yes | yes | no | 245 s, the child's run |
| `VarkaEmitterBitwiseSuite` (`:392`, `:398`) | Scala, own JVM | 2 | yes | yes | no | 74 s alone (`VARKA-335.md` 9), 148 s in the gate |
| `VarkaEmitterMixedLaneSuite.java` (`:642`) | JUnit, own JVM | 1 | no | yes, whole suite | no | about 25 s (`VARKA-28.md` 9) |
| `VarkaEmitterYmDivideSuite` (`:218`) | Scala, own JVM, `varka.sweep.long` | 1 | no | yes, last | no | 11 min 46 s on 24 cores (`VARKA-89.md` 9) |
| `VarkaEmitterNarrowArithSuite.java` (`:943-948`, tests `:1124-1229`) | JUnit, own JVM | 18 and a timing test | no | no | no | 1106 s for one sweep, serial (`results/VARKA-330-sweep.txt`) |

The paths are under `sql/catalyst/src/test/{scala,java}/.../codegen/varka/`. Every suite but the
last two prints no time of its own; its time is in ScalaTest's per-test durations and the JUnit
reports. `VarkaEmitterNarrowArithSuite` prints one line per sweep, `<where>: <n> batches (<k>
answered) in <s> s` (`:964`), which is how `results/VARKA-330-sweep.txt` timed the first.

The parallel sweeps (a parallel stream over chunks with a kernel per thread, as
`VarkaEmitterBitwiseSuite.scala:282-330` does) run at a fraction of their CPU time. The
`SMALLINT` sweep is a plain loop over the 65536 operands (`VarkaEmitterNarrowArithSuite.java:
892-913`): 1106 s over 65536 batches is 16.9 ms a batch on one thread, so on a runner it costs
what one core costs, not a sixth of it.

`VarkaEmitterNarrowArithSuite` alone has a second filter, `-Dvarka.sweep.filter` (`:873-885`):
comma-separated entries matched against each sweep's `where` text, `SMALLINT <op> <MODE> the
<widened|native> arm`, not against its display name. A sweep whose `where` holds no entry
cancels (`:948`).

### 0.2 The two scripts' sweep steps

- `dev/varka_gate.sh:169-172`: `-Dvarka.sweep=true` and `testOnly *VarkaChronoSuite
  *VarkaEmitter*Suite -- -z opt-in`. On 26 October it ran 23 ScalaTest tests and printed three
  `Test run finished: 0 failed, 0 ignored, 0 total` lines, one per JUnit class the glob matches
  (`VarkaEmitterMixedLaneSuite`, `VarkaEmitterNarrowArithSuite`, `VarkaEmitterNarrowSuite`), and
  passed (`results/VARKA-537-admission.txt` 1). Five gates of 25 and 26 October took 618 to 761 s
  for the step, all green.
- `dev/varka_nightly.sh:168-175`: the gate's line, then `testOnly *VarkaEmitterMixedLaneSuite`
  whole (`:171`, for the reason its comment gives at `:161-167`), then the long sweep under its
  own property with one-hour caps (`:173-174`). The newest nightly log on the laptop is from 26
  September, so this step has not run for five weeks.

So the 18 `SMALLINT` sweeps run in neither script, as the row says, and the gate's step is green
whether or not a JUnit sweep exists.

### 0.3 The runner and the reports

`VarkaSuiteRunner` (`VarkaSuiteRunner.java:24-40`) is the main the option matrix, the gate's
suites and `dev/varka_new_tests.py` start: `-s <class>` with `-z`/`-t` filters, ScalaTest suites
to ScalaTest's Runner, `@VarkaSuite` JUnit classes to the JUnit Platform with **the filters
matched against each test's display name**, and a `TEST-<class>.xml` per class into the `-u`
directory. `VarkaSuiteListener.java:139-143` writes a skipped case for a skipped test and
`:169-182` for an aborted one (a failed assumption), so a cancelled sweep is visible in the XML.
`dev/varka_new_tests.py:304-323` (`junit_results`) already reads that XML into passed, failed
and skipped. Run under `-Dvarka.ownJvm=true`, an own-JVM suite runs in the runner's JVM with
each test under its own cap and no child (`skills/testing-and-debugging.md`, the `VarkaOwnJvm`
paragraph), which is how `varka-fuzz.yml` runs its suites.

`VarkaCoverageSuite.scala:1385-1391` refuses a coverage row whose verification is an opt-in
sweep, "runs in no build", detected by `optIn` (`:1419-1427`) from the test's declaration.

### 0.4 CI, the slots and the schedules

- No file under `.github/` mentions `sweep` or `opt-in` (`results/VARKA-537-admission.txt` 3).
  The PR Build runs the 2^24 slices only.
- `varka-fuzz.yml` is scheduled at 02:00 UTC with jobs guarded `github.repository !=
  'apache/spark'` (`:50`, `:222`); it runs on vecbricks/varka (six scheduled runs, 25 to 30
  October, all green; the last started at 07:55 UTC and took 28 and 43 minutes for its two
  jobs). On MaxGekk/spark it has run once, by a push. Row 525 says it will run on both once the
  fork's master follows. Its `report-failure` job (`:347-372`) runs only for a scheduled run on
  vecbricks/varka.
- vecbricks/varka is on GitHub's Free plan, 20 jobs at once (row 522's text); its Sunday weekly
  and option matrices are disabled or guarded there (rows 522 and 525). MaxGekk/spark has 40
  slots, shared by the PR Builds, upstream Spark PR builds and, after row 522, the option matrix's
  30 bins on Sunday, which runs alone. Both repositories are public, so neither pays for minutes.
- A GitHub-hosted job ends at six hours; `varka-mutation.yml:84-85` sets 330 minutes for that
  reason.

### 0.5 Failures

Row 393's composite action `report-scheduled-failure` (`dev/varka_scheduled_failure.py`) opens
or comments on a `scheduled-run-failed` issue when a scheduled run's job failed or was cancelled
(`varka-fuzz.yml:347-372`, the weekly and option matrices the same). It needs a red job: a step
that passes with every sweep cancelled reports nothing. The memory of 18 October records that the
scheduled runs went unread for weeks while PR CI was green.

### 0.6 The planned sweeps and their volume

| row | sweeps | priced in its plan |
| --- | --- | --- |
| 30 | five 2^32 pair sweeps, parallel streams (`VARKA-30.md` 3 item 2) | 15 to 30 s each on the laptop |
| 157 | `60 * 10^p` pairs at `TIME(p)`; `p = 8` and `p = 9` nightly (`VARKA-157.md` 3 item 4) | not priced: 6e9 and 6e10 evaluations, no rate |
| 332 | 38 sweeps of 2^32, or 24 at one arm a tree (`VARKA-332.md` 3 item 2) | at 330's serial 1106 s: 11 h 40 min, or 7 h 22 min |
| 343 | 19 sweeps of 2^32 (`VARKA-343.md` 3 item 1) | 19 x 7 s = 133 s on the laptop |
| 344 | 30 integral rounding sweeps and five twins (`VARKA-344.md` 3 items 3 and 5) | about 15 minutes on the laptop |
| 481 | the four `*` sweeps of ANSI and TRY (`VARKA-481.md` 5.1) | already among 330's 18, 74 min |

### What differs from the row text

1. **`-z` selects nothing under sbt, not under the runner.** The row reads "a JUnit suite runs
   nothing" under `-z opt-in`; that is sbt's `testOnly` with ScalaTest's argument. Through
   `VarkaSuiteRunner` the same `-z` selects JUnit tests by display name (0.3). So the fix is to
   run the sweeps through the runner, not to drop `-z`, and the done-when's "neither script
   passes `-z` to a JUnit suite" is met by meaning, not by letter: question 4.
2. **The time lines exist in one suite.** "Each sweep's time read from the lines the suites
   print" holds for `VarkaEmitterNarrowArithSuite` alone; the other sweeps' times come from the
   JUnit reports (0.1).
3. **Row 481 adds no sweep.** Its four are four of 330's eighteen (0.6).
4. **The nightly runs on vecbricks/varka, not the fork.** The brief's "CI runs on the fork" is the
   PR Build's; the scheduled nightly and its failure report are vecbricks/varka's (0.4).
5. **The `SMALLINT` sweeps cannot run in one job.** Eighteen serial sweeps at 1106 s are 5 h 32
   min on the laptop, at a runner core's speed or slower, against a six-hour job (0.4); they must
   be split, and `-Dvarka.sweep.filter` is the split.
6. **The nightly script's sweeps have not run since 26 September** (0.2), so the long sweep and
   the mixed-lane sweep have no run in five weeks, not only the `SMALLINT` ones.

## 1. Goal and non-goal

**Goal.** Every sweep in the tree runs on a schedule on GitHub's runners, each from one committed
list, through `VarkaSuiteRunner` with a JUnit report; a check over the report fails the job when a
listed filter selects no test that ran, when a selected sweep was cancelled, or when the count of
sweeps that ran differs from the list's; a red job opens or updates the scheduled-failure issue.
The gate and the nightly script run their sweeps through the same script and check.

**Non-goal.** The milestone's sweep budget (row 330's, `PLAN.md` 10.3 item 5); making any sweep
faster or parallel; the 2^24 slices of the PR Build; coverage rows citing a sweep (0.3, row 490's
rule stays).

## 2. Design

### 2.1 One list of sweeps

`sql/varka/sweeps.tsv`, one line per run of a suite, tab-separated:

```
suite	filter	properties	sweep.filter	tier	expect	cap
VarkaChronoSuite	opt-in	varka.sweep=true		nightly	4	30
VarkaEmitterChronoSuite	opt-in	varka.sweep=true		nightly	4	60
VarkaEmitterDivisionSuite	opt-in	varka.sweep=true		nightly	13	90
VarkaEmitterBitwiseSuite	opt-in	varka.sweep=true		nightly	2	60
VarkaEmitterMixedLaneSuite	opt-in	varka.sweep=true		nightly	1	30
VarkaEmitterYmDivideSuite	long-sweep	varka.sweep.long=true		long	1	180
VarkaEmitterNarrowArithSuite	opt-in	varka.sweep=true	LEGACY the widened arm,oracle	weekly	4	120
VarkaEmitterNarrowArithSuite	opt-in	varka.sweep=true	LEGACY the native arm	weekly	3	120
... (ANSI and TRY, each arm: six lines in all)
```

`filter` is the `-z` text, `expect` the number of selected tests that must run and pass (not be
skipped), `cap` the per-test minutes given to both `-Dspark.test.timeout` (minutes,
`SparkFunSuite.scala:42`; the Scala suites' cap) and `-Dvarka.test.watchdog.minutes` (the only
cap a JUnit `@VarkaSuite` has, `VarkaSuite.java:79`). The six `SMALLINT` lines split the 18 sweeps by mode and arm,
three each; the oracle-share test (`:970-1009`) runs both arms where an entry holds `oracle`,
on the first line, and skips itself on the other five. A tier is where a line runs (2.4).

### 2.2 The runner script

`dev/varka_sweeps.sh --tier T[,T] [--build-dir DIR] [--out DIR] [--plant SUITE]`. Without
`--build-dir` it builds catalyst's tests once by `dev/varka_matrix.sh --build-only` and reads the
`classpath` and `jvm.opts` it leaves, as `dev/varka_new_tests.py` reads a build directory. For
each line of the tiers it starts one `VarkaSuiteRunner` JVM, `-Dvarka.ownJvm=true`, the line's
properties and caps, the suite's `@VarkaSuite(jvmFlags)` read as `dev/varka_matrix.sh`'s
`own_jvm_flags` reads them, `-oW -u <out>/<n>/junit -s <class> -z <filter>`, its output to
`<out>/<n>/run.log`; lines run one after another, since a parallel sweep takes every core. Then it
runs the check (2.3) over `<out>` and exits with the check's status or the first runner's
failure. `--plant SUITE` adds one line for that suite with the filter `planted: selects nothing`,
which must make the check fail (3, item 3).

### 2.3 The check

`dev/varka_sweep_check.py <out> [--manifest sql/varka/sweeps.tsv]` fails, naming the line, when:

1. **empty**: no test of the line's suite in its report has a name holding the filter and ran;
2. **cancelled**: a test the filter selects is skipped for any reason but the line's own
   `sweep.filter`. The report keeps each skip's reason (`VarkaSuiteReport.java:89-93` writes it
   as the `message` of `<skipped>`), so a skip naming `-Dvarka.sweep.filter` (`:948`, `:1008`)
   is allowed and one asking for `-Dvarka.sweep=true`, the property that never reached the JVM,
   fails;
3. **count**: the tests that ran and passed differ in number from `expect`, so a rename that
   moves one test out of a filter fails as surely as an empty filter;
4. **entry**: a `sweep.filter` entry is held by no line of the line's `run.log` that the suite
   prints for a sweep or for the oracle's share, `<where>: ... batches (` (`:964`) or `<where>:
   2^16 rows in` (`:1004`), since the entries match `where` and not the names;
5. **unlisted** (also run alone as `--sources`, no report needed): a suite under
   `sql/catalyst/src/test` declares a test whose declaration holds `-Dvarka.sweep` (the rule of
   `VarkaCoverageSuite.optIn`, narrowed to the property so the `varka.jfr` opt-ins are not
   sweeps) and no manifest line names the suite.

The report is read by `junit_results`, moved from `dev/varka_new_tests.py` into a small shared
module both scripts import, not copied. The check writes a table, one row per test that ran:
suite, test, seconds from the XML, the `SMALLINT` line's own seconds where printed, and the
line's cap, to `<out>/times.tsv` and, under Actions, to the step summary.

### 2.4 Where and when

A new workflow, `.github/workflows/varka-sweeps.yml` (question 3), on the repository of question
1, recommended vecbricks/varka, every job guarded to it so the fork's copy, once row 525 syncs
it, runs nothing:

- **`nightly`**, 03:30 UTC daily, after the fuzz workflow's 02:00 start: two jobs, `nightly`
  (five lines, `timeout-minutes: 150`) and `long` (the year-month sweep, `timeout-minutes: 240`,
  above its test cap of 180 so the test's own cap fires first), each of the fuzz job's checkout, caches, Java,
  engine and `record-runner-platform` steps, then `dev/varka_sweeps.sh --tier <t>`.
- **`weekly`**, Wednesday 03:30 UTC, away from Sunday's matrices on either repository: a matrix
  of one job per `weekly` line, `max-parallel: 6`, `timeout-minutes: 330`.
- `workflow_dispatch` with `tier` and `plant` inputs, so one tier is rerun and the planted failure
  is shown on CI.
- `report-failure`, needing every job, as `varka-fuzz.yml:347-372` has it.
- Each job uploads `<out>` (reports, logs, `times.tsv`) for 30 days.

On the fork instead, the same jobs fit the 40 slots if the weekly matrix keeps to six and avoids
Sunday; the cost is six slots for two to three hours a week beside upstream PR builds.

### 2.5 The gate and the nightly script

The gate's sweep step becomes `dev/varka_sweeps.sh --tier nightly --build-dir "$suites_build"`
when the compile step passed in this run (the lint step's condition, `dev/varka_gate.sh:163-167`),
or without it otherwise. The nightly script's step becomes `--tier nightly,long`. Neither runs the
`weekly` tier, which is by hand on the laptop (`--tier weekly`) or on CI. The gate gains the mixed-
lane sweep, 25 s.

## 3. Tests and proofs

1. **The check's self-test**, `dev/varka_sweep_check.py --self-test`, on fixture reports: a line
   whose tests are all skipped; a filter no name holds; a count one short and one over; a
   `sweep.filter` entry no printed line holds; a suite source with a sweep and no line; each fails
   with its line named; a complete fixture passes. Run in the PR Build beside
   `dev/varka_new_tests.py --self-test` (`build_and_test.yml:1728`), with `--sources` over the
   tree, so a new sweep without a manifest line fails at the pull request.
2. **The first full run on CI**: every tier dispatched once on master after the merge, every
   line green, the times recorded (done when).
3. **The planted filter on CI**: one dispatch with `plant: VarkaEmitterBitwiseSuite`, which must
   end red on the check's empty rule with the line named, and must open no issue (a dispatch).
4. **The script on the laptop**: `--tier nightly` against the gate's build, under the heavy lock
   and `SPARK_LOCAL_IP=127.0.0.1`, once before the pull request, as the gate will run it.

## 4. Predictions and costs

Registered before the build; the calibration table has no runner-against-laptop row for a sweep,
so the bands are wide and the first run scores them. **[I]** A runner has 4 vCPUs against the
laptop's 24 threads, so a parallel sweep is taken at 3 to 12 times its laptop time, and a serial
one at 1 to 2 times, one core against one core.

1. **`nightly` job**: Chrono 15 s to 1 min, EmitterChrono 6 to 23 min, Division 12 to 49 min,
   Bitwise 4 to 15 min, MixedLane 1 to 5 min; the job under 120 minutes with its build (the
   fuzz job's build is inside its 28 minutes).
2. **`long` job**: the year-month sweep 35 to 140 minutes; its cap of 180.
3. **`weekly` jobs**: each `SMALLINT` sweep 18 to 37 minutes (LEGACY's 1106 s at 1 to 2 times;
   ANSI and TRY pay a compare and a mask more, so they sit at the top); each line of three under
   2 h, under the cap of 120 a test; 18 sweeps 5.5 to 11 hours of runner time a week.
4. **The check** adds under 10 s to a job; the self-test and `--sources` under 5 s to the PR
   Build.
5. **Answers**: every sweep passes on the runners. A failure is a finding, read against the
   runner's platform record (the pool mixes AVX2 and AVX-512, `varka-fuzz.yml:80-83`).

**What the planned sweeps add**, at the same factors:

| row | tier | added runner time |
| --- | --- | --- |
| 30 | nightly | 5 to 30 min (five sweeps of 15 to 30 s, parallel) |
| 343 | nightly | 7 to 27 min (133 s, parallel) |
| 344 | nightly, its own job | 45 min to 3 h (about 15 min, parallel) |
| 332 | weekly | 11 to 23 hours (38 sweeps, serial) or 7 to 15 (24); 8 to 16 lines of three |
| 157 | weekly | not priced: `p = 9` is 6e10 evaluations, 25 min at 335's 25 ns of CPU each over 4 vCPUs as a floor, hours if Spark's double and decimal path costs a microsecond **[I]**; its first `p = 8` run prices it |
| 481 | weekly | none beyond 330's 18 |

So the nightly reaches three to five jobs and the weekly 15 to 25 job-lines once 332 lands, of
which 332's serial sweeps are three quarters. On vecbricks/varka that fits 20 slots at a
`max-parallel` of six to eight, finishing in under a night; the weekly tier's wall time is the
reason the open point on 332's form is raised (Open points, 2).

## 5. Order, files and size

One pull request, then the record.

| file | change |
| --- | --- |
| `sql/varka/sweeps.tsv` | new: the manifest, eleven lines |
| `dev/varka_sweeps.sh` | new: build, run each line, check |
| `dev/varka_sweep_check.py` | new: the five rules, `--sources`, `--self-test`, fixtures inline |
| `dev/varka_junit.py` | new: `junit_results` moved from `dev/varka_new_tests.py` |
| `dev/varka_new_tests.py` | imports it |
| `.github/workflows/varka-sweeps.yml` | new: nightly, long, weekly, report |
| `.github/workflows/build_and_test.yml` | the self-test and `--sources` beside the new-tests self-test |
| `dev/varka_gate.sh`, `dev/varka_nightly.sh` | the sweep steps call the script |
| `skills/testing-and-debugging.md` | the long-run section names the manifest and the script |
| `m8/results/VARKA-537-sweeps.txt` | the first CI run's times and the planted run (after the merge) |

Size: medium, as the row says; about a day for the scripts and workflow, and a week of wall time
for the first weekly run and its record.

## 6. Out of this row

- **Parallel `SMALLINT` sweeps.** Making 330's loop a parallel stream as 335's sweep is would cut
  the weekly tier by about four on a runner, but it moves the number 330's budget and 481's
  74 minutes rest on, and the oracle path's thread safety is unchecked. Row 330's or a row of its
  own; question 2.
- **The sweep budget** stays row 330's; this row's times file is an input to it.
- **Coverage rows citing a sweep** (`VarkaCoverageSuite.scala:1388-1390`): a scheduled run is not
  a build a merge relies on, so the rule stands.
- **The nightly fuzz running on both repositories** after row 525 is row 525's; this row's
  workflow guards its own jobs.

## 7. Questions for the owner

1. **Which repository runs the sweeps?** (a) vecbricks/varka: 20 slots of its own, no upstream
   builds, the nightly and its issue already there; (b) MaxGekk/spark: 40 slots shared with the
   PR Builds, upstream PR builds and Sunday's option matrix. *Recommended (a)*: the sweeps need no
   reach record or shared build, and a weekly fan-out of six to sixteen jobs is the slot pressure
   row 522 and the upstream finding were about.
2. **Does this row make the `SMALLINT` sweeps parallel?** *Recommended no* (section 6): split by
   filter, which needs no test code, and leave the speed to 330 or a new row.
3. **A new workflow, or jobs in `varka-fuzz.yml`?** *Recommended a new `varka-sweeps.yml`*: the
   fuzz job's 90 minutes and its seed inputs fit none of these, and a red sweep should not read
   as a red fuzz night.
4. **The done-when's "neither script passes `-z` to a JUnit suite".** Through `VarkaSuiteRunner`
   `-z` selects JUnit tests by name (0.3). *Recommended*: read it as "neither script runs a JUnit
   suite under sbt's `-z`", which the change meets.
5. **Cadence.** *Recommended*: the parallel sweeps nightly, the serial ones weekly on Wednesday
   night; 344's thirty in a nightly job of their own when they land.

*The owner took all five recommendations (31 October 2026):* the sweeps run on vecbricks/varka in
a new `varka-sweeps.yml`, the `SMALLINT` sweeps split by filter and stay serial, the done-when
reads "no JUnit suite under sbt's `-z`", and the cadence is nightly parallel, weekly serial.

## 8. Done when

- `varka-sweeps.yml` has run every line of `sql/varka/sweeps.tsv` on CI once, green, and each
  sweep's time is in `m8/results/VARKA-537-sweeps.txt` against row 330's budget file;
- a dispatch with a planted filter in one named suite ended red on the check, naming that line;
- the self-test and `--sources` run in the PR Build;
- the gate's and the nightly script's sweep steps call `dev/varka_sweeps.sh`, and neither runs a
  JUnit suite under sbt's `-z`;
- a red scheduled sweep job reaches `report-scheduled-failure`.

## 9. Outcome

*(empty until the work lands)*

## Open points for the lead

1. **The runner's speed for a sweep is unmeasured.** Every band in 4 rests on a core ratio
   **[I]**; the first nightly scores it. If the `SMALLINT` sweeps run past 37 minutes, the lines
   go to two sweeps each.
2. **332's sweeps are priced serial.** `VARKA-332.md` 3 item 2 prices its 38 at 330's 1106 s,
   which is 330's loop. Written in 335's parallel form they would cost about a quarter on a
   runner; whether 332 adopts that form is 332's plan, and it decides most of the weekly tier.
3. **Where `--sources` runs in the PR Build.** The new-tests self-test's job is the natural place;
   whether that job runs on every change to the catalyst Varka tests was not read.
4. **The year-month sweep's cap.** Its 11 min 46 s was on 24 cores; at a sixth of that the cap of
   180 minutes is a guess with a margin, and the line may need splitting by divisor class, which
   `VARKA-89.md` would own.
