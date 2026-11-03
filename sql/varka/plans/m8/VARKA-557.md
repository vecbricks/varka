# VARKA-557: A nightly job that pins the two bodies to different JIT tiers

*Planned 3 November 2026 on `varka-556-557-plan`. The owner's decision of the same day: nightly,
not the option matrix. Nothing below is built, and nothing is dispatched without the owner's word
(his rule since 2 November 2026).*

## 1. Where this came from

The both-bodies check (`VarkaKernelRunner.checkOtherBody`, row 303) failed once in many runs on
two unlike NaNs (option-matrix run 41608460861, on branch `varka-554-pr-size`), because which NaN
`x + x2` returns depends on the tier each body ran at, and the two bodies compile at different
moments. Row 555 (#1313) makes the check accept it. The lesson is wider than NaNs:
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
  and `varka-master.yml`'s runs after a merge. *Settled (7.4):* the limit is 20 and the weekly
  matrix peaked at 19, so a Sunday on which the weekly is still running when this job starts
  queues its jobs behind the weekly's; a risk, accepted.

## 6. Dependencies

#1313 merged first: before it, `masked-interpreted` fails every night on the known NaN case.
Not on row 556: the planted fault (the tolerance reverted) fails under 556's shape level too.

## 7. Open points, settled by the lead (3 November 2026)

1. **The fork's scheduled runs.** `varka-fuzz.yml`'s schedule is guarded to vecbricks/varka exactly
   as `varka-weekly-matrix.yml` guards its own, both jobs, with `workflow_dispatch` allowed on
   either repository; the precedent is the owner's decision of 30 October 2026 guarding the
   Sunday runs to vecbricks. It is part of this row's change.
2. **How a red night reaches the owner.** Through the lead's post-merge CI report, which gains a
   "scheduled runs" line; the issue `report-scheduled-failure` opens stays as it is, and closing
   issues on a green night is out of scope.
3. **Catalyst.** Not run under the configurations; 3.2's reasoning stands. `VarkaCompiledCheck`'s
   histories at volume on CI are out of scope.
4. **The job limit.** vecbricks is on GitHub's free plan for organisations, 20 concurrent jobs for
   a public repository, and its last weekly run peaked at 19. Four configuration jobs at once
   stand (`max-parallel: 4`); a Sunday overlap with the weekly matrix is a risk only (5).

## 8. Outcome of steps 1 to 3 (3 November 2026, branch `varka-557-tier-split`)

Steps 1 to 3 are built; step 4, the planted fault on CI, waits for the owner's word. The trial's
runs are in `results/VARKA-557-trial.txt`.

### 8.1 Step 1: the configurations reproduce the known case, 3 of 3 each

**The prediction scored.** On the scratch branch (`varka-557-scratch`, #1313's NaN acceptance
dropped from `compareBodies`), `VarkaCoverageHarnessSuite` failed `try_add(x, x2)` on every run of
three under `masked-interpreted` and under `masked-c1`, each with "dense and masked bodies
disagree on output 0 at row 7 of a null-free batch of 10", row 7 being the two unlike NaNs: the
prediction holds as written. It is never the only row: each run failed five to seven of the
Floating arithmetic rows, which ones moving from run to run (`x + x2`, `x * x2`, `try_multiply`,
the `f` forms), as 2 says C2's choice of operand can. On master the same configurations pass, and
so do the two `dense-` ones, 1687 tests each.

**The evidence that the split happened**, from `-XX:+PrintCompilation` on one run of each,
summed over every kernel: under `masked-interpreted` no masked method was compiled and the dense
side at tier 4 only (1933 compile records of loop methods, 2067 of epilogues, 1650 of drivers);
under `masked-c1` the masked methods were compiled at tiers 3 and 1 only, C1 both, and the dense
side at tier 4 only; the `dense-` runs are the mirror (`results/VARKA-557-trial.txt`, last section).

**Where the plan's configurations were wrong**, each found by its compile record:

1. *Threshold scaling alone does not reach C2.* At a factor of 0.01 (run p1) every dense method
   stayed at tier 3: a suite's kernel serves a few batches of ten rows, too few calls for tier
   4's thresholds even scaled. At 0.001 (p2) most stayed interpreted. At 0.0001 every compiled
   side's method reached C2 within its first calls. The factor is 0.0001.
2. *A C1 step on the compiled side.* With scaling alone the compiled side runs C1 code (tier 3)
   before C2, so the pair compared is partly C1 against the held side. The compiled side and the
   dispatch `run` are now kept from C1 by a directive (`c1: { Exclude: true }`), so they run
   interpreted until their first C2 compile, which the scaling brings at once.
3. *`exclude` does not stop inlining* (`opto/bytecodeInfo.cpp`'s `should_not_inline` reads the
   `dontinline` command, not `exclude`). In p2 the failing kernels had `run` compiled at tier 4
   and no dense method compiled on its own: C2 had inlined both sides into the dispatch. The held
   side is now `dontinline` as well, so it stays out of any compiled caller.
4. *The held side's arithmetic.* An interpreted kernel method still calls the Vector API's
   methods, which HotSpot compiles on their own, so "interpreted" pins the kernel's bytecode, not
   every instruction that computes a lane. The configurations split the two bodies' compiles,
   which is what the known case needs; they are not a pure-interpreter reference.

The `-XX:-TieredCompilation` fallback the lead allowed was not needed. The test JVMs carry
`-XX:+IgnoreUnrecognizedVMOptions` (sbt's options), so an option or a pattern that takes no
effect is silent; the job therefore checks the split by its effect (8.3).

**Times** on the loaded laptop (seconds, one JVM): `VarkaCoverageHarnessSuite` 50 at the
defaults, 206 `masked-interpreted`, 305 `masked-c1`, 481 `dense-interpreted`, 458 `dense-c1`;
`VarkaFloatingLaneSuite` 54, 81, 84, 166, 170. The dense configurations cost about twice the
masked ones; why was not measured.

A finding on the way: `VarkaSuiteRunner`'s `-z` and `-t` filter a JUnit class's `@Test` methods
only. Its `PostDiscoveryFilter` sees a `@TestFactory` as a container, so every dynamic test runs
whatever the filter; the trial ran the whole suite.

### 8.2 Step 2: the whole sql set under one configuration

**The run.** `dev/varka_tier_split.py run dense-interpreted -j 1` on the laptop, every sql suite
the job runs (69 in one JVM, three that run alone), one JVM at a time: 2768 seconds for the shared
JVM, against 655 for the same suites at the defaults in another branch's laptop run of the same
evening (the `varka-343a3` worktree's), 4.2 times. `dense-interpreted` is the dearest
configuration by step 1's times, so this prices the job from above. The check passed: 89,008
null-free and 15,136 nullable batches compared, the masked side compiled at tier 4 only (13,951
compile records), the dense side never.

**The slow suites.** The cost is not spread evenly: `VarkaSplitConditionSuite` took 566 seconds
against 4.6 (124 times), `VarkaSeveralKernelsSuite` 753 against 87, `VarkaCoverageHarnessSuite`
334 against 39, while most suites over small data stayed within twice their time. The
longest single tests were 348 and 293 seconds (`VarkaSeveralKernelsSuite`'s eight hundred
`greatest` entries) and 241 seconds (`VarkaSplitConditionSuite`'s two-column disjunction).

**The price on CI.** The committed weights (`sql/varka/ci/suite-times.tsv`) put these suites at
1373 runner seconds against the laptop's 613 at the defaults, 2.2 times; each suite's weight
scaled by its laptop ratio gives about 6400 runner seconds a configuration. Split as the job
splits it (`dev/varka_suite_shards.py --job` over the committed weights, then `--split 2`), the
share holding `VarkaCoverageHarnessSuite` and `VarkaSeveralKernelsSuite` runs two JVMs of about
36 minutes each, the other two of about 18: under the 90-minute cap with room for a runner twice
as slow. So two jobs a configuration, eight jobs four at a time. One job a configuration would
run about 53 minutes of suites, too close to the cap for the runners' spread (a suite's time
moves up to 3.38 times between two Builds, `results/VARKA-516-weights.txt`).

**What failed, and the skip list.** One test, by design: `VarkaProjectExecSuite`'s "the
allocation sampler events every sampled batch, and the samples go clean once C2 has the loop",
whose null-free batches take the dense body, which this configuration keeps from C2. Rather than
skip the suite's other tests, it now cancels itself when the tier split holds the dense body back
(`VarkaMatrixRuns.TIER_SPLIT`, set by `-Dvarka.tierSplit`). `VarkaErrorContextSuite`'s
steady-state allocation test cancelled itself already, as it does when a kernel never reads
clean. `VarkaWarmupEndToEndSuite` and `VarkaSizeLadderJitSuite`, which assert a kernel's C2 code,
are skipped from the start, as 3.2 planned; 3.2's other JIT suites (assembly, inlining cliff, the
`C2Code` tests) are catalyst's, which the job does not run. The longest test is past Varka's
10-minute per-test watchdog at a runner's speed, so the configurations raise it to 30 minutes, and
SparkFunSuite's cap from 20 to 30.

### 8.3 Step 3: what was built

- `.github/workflows/varka-tier-split.yml`: a build job (`dev/varka_matrix.sh --build-only`,
  archived by `dev/varka_matrix_archive.sh`), one job per configuration and share of the suites
  (`max-parallel: 4`, 90 minutes each), a report job and `report-scheduled-failure`. The schedule
  is 03:00 UTC, on vecbricks/varka only; a dispatch runs on either repository and takes a branch
  and a comma-separated subset of the configurations. Every job checks out the commit the build
  job built. Each configuration job's summary carries its report, the failed tests (also as an
  error annotation) and the CPU; the report job's carries, per configuration, the batches the
  check compared and the compiles of each side.
- `dev/varka_tier_split.py`: the four configurations' options, the skipped suites with their
  reasons, `run` (which hands them to `dev/varka_matrix.sh --defaults --module sql`) and
  `check`, which fails a run whose compile record shows no split or whose JVMs compared no batch.
- `sql/varka/ci/tier-split/*.json`: the four compiler directive files. Each prints the compiles of
  the methods it matches (`PrintCompilation: true`), which is what `check` reads, at no cost to
  the rest of the log.
- `VarkaKernelRunner` counts the batches its check compared, null-free and nullable, and a JVM
  under the check prints the two counts as it exits.
- `VarkaMethodNamesSuite` holds every pattern in the script and the directive files to
  `VarkaMethodNames`' builders and `VarkaShapeCacheImpl.CLASS_NAME_PREFIX`: each method glob must
  name exactly one side's methods, the dispatch or every method.
- `varka-fuzz.yml`: both jobs' schedule guarded to vecbricks/varka (7.1).
