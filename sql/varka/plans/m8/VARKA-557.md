# VARKA-557: A nightly job that pins the two bodies to different JIT tiers

*Planned 3 November 2026 on `varka-556-557-plan`. The owner's decision of the same day: nightly,
not the option matrix. Nothing below is built, and nothing is dispatched without the owner's word
(his rule since 2 November 2026).*

## 1. Where this came from

The both-bodies check (`VarkaKernelRunner.checkOtherBody`, row 303) failed once in many runs on
two unlike NaNs (option-matrix run 41608460861, on branch `varka-554-pr-size`), because which NaN
`x + x2` returns depends on the tier each body ran at, and the two bodies compile at different
moments. #1313 (row 555 on its branch) makes the check accept it. The lesson is wider than NaNs:
any answer that depends on the tier shows up only when a run happens to split the bodies across
tiers, which no suite arranges. This row arranges it every night, so a tier-dependent disagreement
fails at once rather than once a fortnight.

## 2. The admission check, done

**Where the cross-checks run.** `dev/varka_matrix.sh` (line 303) puts
`-Dvarka.checkBothBodies=true` on every test JVM, and it is how every Varka suite runs on CI: the
Build's "Varka suites" jobs (`dev/varka_scoped_suites.sh` calls it with `--defaults`), the option
matrix and the gate; `dev/varka_new_tests.py` and `dev/varka_pit.sh` set the flag themselves. So
the check is already on in every Build. The cross-checks whose verdict can depend on the tier:

1. the both-bodies check, in every sql/core suite that serves batches through a
   `VarkaKernelRunner` (the harness suites, the differential, floating, long-lane and end-to-end
   suites);
2. the kernel against Spark's row engine (rule 4 of `VARKA-556.md` 2), and the harness's
   comparison of Spark's interpreted projection with its generated one, which runs at its own
   tier;
3. the catalyst checks (`VarkaKernelChecks`) run each body once on a fresh class, so the kernel is
   interpreted while the reference evaluator, called on every row, may be compiled;
   `VarkaCompiledCheck` drives its eight histories on purpose, at `VarkaIrFuzzSuite`'s default
   of 20 draws a lane in each JVM of the CI nightly fuzz and at volume in the laptop nightly
   (`dev/varka_compiled_fuzz.sh`);
4. inside one body, the rows past the last whole vector run in the epilogue methods, compiled
   apart from the loop, so one batch's rows can be computed at two tiers.

**Why the suites never split the bodies.** #1313's plan records that `VarkaCoverageHarnessSuite`
under `-XX:CompileCommand=exclude,*.loopMasked0` passed with the tolerance switched off, while a
probe that called one kernel 50,000 times disagreed on 49,598 calls. The test sessions run the
warm-up off (`VarkaSharedSessions.scala` 130), so a suite's kernel serves a few batches and both
bodies stay interpreted; excluding the masked loop from compilation changes nothing while the
dense loop is not compiled either. A configuration must therefore compile one side early as well
as hold the other back.

**What HotSpot offers per method** (jdk-25-ga source):

- `-XX:CompileCommand=exclude,<pattern>` keeps a method interpreted, C1 and C2 and OSR alike
  (`compileBroker.cpp` 1566), and applies inside a matching compiler directive unless the
  directive sets `Exclude` itself (`compilerDirectives.cpp` 423 to 425), so Varka's own directive,
  which excludes only C1 and only for warmed classes, does not undo it;
- a method C2 may not compile is compiled by C1 alone, at tier 1 (`compilationPolicy.cpp` 911 to
  931), so a directive `{ match: <pattern>, c2: { Exclude: true } }` from
  `-XX:CompilerDirectivesFile` (a diagnostic flag, `compiler_globals.hpp` 312) pins a side to C1;
- `-XX:CompileCommand=CompileThresholdScaling,<pattern>,<factor>` scales one method's thresholds
  (`compilerOracle.hpp` 77, `compilationPolicy.cpp` 313 and 351), and
  `BackgroundCompilation,<pattern>,false` makes its first compile synchronous, so a side can be
  compiled on its first calls and at a fixed moment;
- `-Xint` and `-XX:TieredStopAtLevel=1` put both bodies, the reference and Spark's row engine on
  one tier: they split nothing.

The method names are `VarkaMethodNames`'s: per side a driver (`runDense`, `runMasked`), stages
(`stageDense<k>`), loops (`loopDense<k>`, `loopMasked<k>`) and epilogues (`epilogueDense`,
`epilogueDense<k>`), all in classes named
`org.apache.spark.sql.varka.execution.VarkaFusedProjection_<hash>`, so a pattern can name the
kernel classes and nothing of Spark's.

**The scheduled workflows today.** `varka-fuzz.yml` runs nightly at 02:00 UTC as two jobs (the
fuzzers, about 21 minutes in `sql/varka/ci/step-budget.tsv`; every tree to size 5, about 55),
guarded only by `github.repository != 'apache/spark'`, so it runs on vecbricks/varka and on the
fork (MaxGekk/spark) both; its last eight vecbricks runs started between 07:06 and 09:28 UTC, five
to seven and a half hours after the cron. `varka-weekly-matrix.yml` runs Spark's whole matrix
on Sundays, guarded to vecbricks/varka for `schedule`. `varka-option-matrix.yml` (its Sunday schedule) is
disabled on vecbricks/varka; it runs by dispatch on the fork, as 30 bins. A failed scheduled run
on vecbricks opens or updates an issue through `report-scheduled-failure`, which closes nothing;
the fork's scheduled runs report nowhere (its fuzz failed on 31 October unreported). They are read
late: issue #1252 (the weekly, red on 1 November, as on four Sundays from 27 September to 18
October) and #959 (fuzz, first red on 21 October) are both open, #959 though fuzz has passed every
night since 27 October.

## 3. The design

### 3.1 The configurations

Four, each one JVM option set given to `dev/varka_matrix.sh --defaults --jvm-arg ...`, with
`-XX:CompileCommand=quiet` so an excluded class prints nothing per method:

| name | held back | brought forward |
| :--- | :--- | :--- |
| `masked-interpreted` | masked driver, stages, loops and epilogues excluded | dense side's thresholds scaled down, its compiles synchronous |
| `dense-interpreted` | the mirror | the mirror |
| `masked-c1` | masked side C2-excluded by a directives file (tier 1, left operand) | dense side as above (C2) |
| `dense-c1` | the mirror | the mirror |

The interpreted pairs put the interpreter (right operand on `dadd`) against C2; the C1 pairs put
C1 (left) against C2 (node order), the split #1313's failure came from. The threshold factor is
set by the first step's trial (3.4), not guessed. `-Xint` and `TieredStopAtLevel=1` are left out:
they split nothing (2), and `-Xint` would slow every Spark operator too.

### 3.2 Which suites

The sql module's Varka suites as `--defaults --module sql` finds them, which is where cross-checks
1, 2 and 4 run, less the suites whose subject is the JIT itself, under `--skip-suites` with the
reason in the workflow: the `C2Code` tests, the size-ladder and inlining-cliff suites, the
assembly suites and the warm-up suites, which assert that a kernel reaches C2 code or runs
without allocating, and which an interpreted or tier-1 side breaks by design. The catalyst module
is not run: its checks call each kernel once, so a pattern changes nothing there, and its
deliberate tier histories already run in `VarkaCompiledCheck` (open point 3).

### 3.3 The job

A new workflow, `varka-tier-split.yml`, rather than a job in `varka-fuzz.yml`, so its time and its
issue are its own:

- one build job (`dev/varka_matrix.sh --build-only`, packed by `dev/varka_matrix_archive.sh`, as
  the option matrix does), then one job per configuration running the archive with `--split 2`,
  then the report job: six jobs, four of them at once;
- `schedule` at 03:00 UTC, after fuzz's 02:00, guarded to vecbricks/varka as the weekly is, and
  `workflow_dispatch` on either repository, with inputs for a branch and a subset of
  configurations, so the planted fault and a pull request can be run on demand;
- failure surfaced by `report-scheduled-failure`, with the failing configuration, suite and test
  in the issue, and each run's step summary listing per configuration the batches the both-bodies
  check compared, so a green night that compared nothing reads as such;
- a `VarkaMethodNamesSuite` check that the workflow's patterns name what the builders produce, as
  it already checks five scripts that mirror the names.

### 3.4 The steps, each ending in its check

1. **Reproduce on the laptop first** (debugging, which the laptop is for): on a scratch branch
   with #1313's `bothNaN` arm reverted, `VarkaCoverageHarnessSuite -z "Floating arithmetic"` under
   `masked-interpreted` and `masked-c1`, with the watchdog raised for the trial. **Prediction:**
   both fail on `try_add(x, x2)` on every run of three, where `exclude` alone passed (#1313's plan).
   If neither fails, the row stops and reports: a nightly that cannot reproduce the one known case
   proves nothing. The same trial times each configuration on `VarkaCoverageHarnessSuite` and
   `VarkaFloatingLaneSuite` against the defaults, and fixes the threshold factor.
2. **Time the whole suite set** on the laptop under one configuration, under the shared JVM lock,
   to price the job count; the Build's sql suites take about 24 minutes a job at the defaults
   (`step-budget.tsv`), and an interpreted side is much slower on a large batch, which the table
   cannot price.
3. **Write the workflow** and the names check; `actionlint` and the workflow's dry listing.
4. **The planted fault on CI**, on the owner's word: the workflow dispatched on the scratch branch
   of step 1 fails in `masked-interpreted` and `masked-c1`; dispatched on master with #1313 merged,
   it passes. Both runs cited in the outcome.

## 4. Size

About 450 hand-written lines: the workflow (about 200), the directive files and the skip list
(about 40), the names check (about 40), the report's batch count (about 60), this plan's outcome
and the row (about 60), the lesson's last paragraph if row 556 has not landed it (about 20). One
pull request.

## 5. Risks

- **An interpreted side is too slow** for the suites' watchdogs on large batches: the trial of
  step 2 decides between more splits, a smaller suite set and a raised watchdog; the job stays
  under 90 minutes, the bins' cap.
- **A configuration fails on a real tier-dependent answer** other than NaN payloads on its first
  night: that is a finding, filed as a row of its own, and the configuration stays red until it is
  fixed or excused by `VarkaSameAnswer` (row 556), never skipped.
- **Slots**: the vecbricks repository's concurrent-job limit is not recorded anywhere this plan
  read; four jobs at once at 03:00 UTC, though the schedule fires hours late, may meet fuzz's two
  and `varka-master.yml`'s runs after a merge.

## 6. Dependencies

#1313 merged first: before it, `masked-interpreted` fails every night on the known NaN case.
Not on row 556: the planted fault (the tolerance reverted) fails under 556's shape level too.

## 7. Open points for the lead

1. The fork's scheduled runs: `varka-fuzz.yml` runs nightly on the fork as well as on
   vecbricks/varka, and its failures there report nowhere. Guard it to vecbricks/varka as the
   weekly is, or leave it; this row's workflow is guarded either way.
2. How a red night reaches the owner beyond the issue, given #959 and #1252: a line in the
   post-merge CI report, or the issue alone.
3. Whether the catalyst module should run under the configurations too, and whether
   `VarkaCompiledCheck`'s histories should run at volume on CI, where the nightly fuzz runs them
   at the suite's default of 20 draws a lane per JVM.
4. Whether the vecbricks repository's job limit allows four jobs at 03:00 UTC, or the
   configurations run two at a time.
