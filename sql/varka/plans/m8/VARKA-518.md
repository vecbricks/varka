# VARKA-518: The catalyst suites' reach record

## 0. What exists now (admission check)

From master at `e6d2d4516c8`, from branch `varka-518-catalyst-reach` (`33a9043b806`, built and
not merged) and from branch `varka-518-options-read` (`884152e3c1c`, the experiment that does not
ship). CI numbers are in `m8/results/VARKA-518-replan.txt`, read from MaxGekk/spark with GETs
only; the laptop's recording numbers are `m8/results/VARKA-518-catalyst-reach.txt`, committed
here from the built branch unchanged.

1. **How sql's record works.** The Build's three sql Varka jobs run their suites at the defaults
   with `VARKA_REACH_RECORD` set (`build_and_test.yml:1621-1623`). `VarkaTestReach` hears every
   compile and emission through `VarkaCompileObserver`, emits each again under every covered
   configuration's options and writes, per test, the configurations whose compiled form, decline
   or bytes differ, plus a suite line and a JVM-wide line. Each job uploads its part as
   `varka-matrix-reach-<module>-<slug>` (`:1669-1676`, `if: matrix.module == 'sql'`, kept one
   day). The option matrix's build job takes the parts only when every "Varka suites: sql" job
   succeeded with one (`dev/varka_matrix_reuse.py`, `SCOPED`, `reach_complete`), concatenates
   them, and the planner weighs each suite by the share of its tests a configuration runs
   (`dev/varka_matrix_bins.py`, `reach_share`); a configuration's JVM skips a test whose line,
   joined with its suite's and the JVM's, does not name it (`VarkaTestReach.skips`). Thirteen
   configurations run everything for the reasons in `VarkaTestReach.FULL`; four sql suites are
   left out by `UNRECORDED`; a test with no line runs.
2. **Catalyst records nothing**, so its suites run whole under all 46 of its configurations. In
   run 41092042163 catalyst is 40896 of the suites' JUnit seconds and sql 19558 (`VARKA-518-
   replan.txt` 2). The three tree suites already skip their own unmoved cases (`VarkaReach`, row
   507 step 4; row 517 for the composition fuzzer); the rest do not.
3. **What the built branch did** (`33a9043b806`): the catalyst jobs set `VARKA_REACH_RECORD` and
   upload; `reach_complete` requires every Varka job's part; the emitter reports an emission to
   the observer after its argument checks (`VarkaEmitterContractSuite`'s null options had broken
   the record); `VarkaShrinkerSuite` and `VarkaMemorySanitizerSuite` open a suite window through
   the new `VarkaReachWindow`; `UNRECORDED` names ten catalyst suites (five past the watchdog when
   recorded, two that cost more than they save, the three tree suites). Of that, master has only
   the `VarkaReachWindow` trait, which row 536 merged for `VarkaLiteralLiftSuite`; the emitter
   still reports before its checks (`VarkaLoopEmitter.java:339`) and the two suites open no
   window.
4. **Why it was held.** No CI run of the branch exists; the hold is a derivation from the
   laptop's numbers (`VARKA-518-replan.txt` 3): the kept suites cost 743.3 s to record, about 820
   JVM seconds on CI, which on catalyst's six JVMs is at least 2.3 minutes a JVM and, for the JVM
   holding `VarkaGroupingBoundSuite`'s 267.0 s, about 4.9. The catalyst jobs are the PR Build's
   last to end in 10 of 11 recent Builds (`VARKA-518-replan.txt` 1), so those minutes are the
   Build's. They bought 12559 matrix seconds, 3.5 minutes of matrix wall at 30 slots.
5. **The cost is eight suites, not the recording.** Ranked by saving over cost, the 67 kept
   suites the matrix runs reach 8352 of their 11572 saved seconds for 80.8 s of laptop cost; eight
   suites carry 683.9 s of the 901.9 for 2601 of the saving (`VARKA-518-replan.txt` 3). Each
   re-emits large kernels once per covered configuration, 34 times; the closed options-read
   design was the only way past that (`m8/results/VARKA-518-reads-local.txt`).
6. **The options-read experiment stays closed.** On the sql suites it allowed 34631 test-runs
   where the byte comparison allows 10488 and missed a configuration in 22 tests
   (`VARKA-518-reads-local.txt`); this plan keeps the byte comparison.
7. **Seven catalyst suites are new since the laptop table**, 1038 JUnit seconds of run
   41092042163, `VarkaEmitterYmDivideSuite` 974 of them; they have no measured recording cost.

### What differs from the row text

The row's step 1 had the PR Build's catalyst jobs record all but the tree suites, and its step 2
gave hooks to 51 of 57 suites. The built branch found step 2 already done (the emitter suites run
through `VarkaMatrixTests` by `VarkaEmitterTestBase`); this plan keeps the PR Build's recording to
the suites that pay for it and moves the rest to the fork's master Build, where nothing waits.

## 1. Goal and non-goal

Goal: a configuration of the option matrix runs a catalyst test only where the record says the
configuration moves something the test compiled or emitted, the record costing the PR Build's
catalyst jobs no more than their run-to-run spread, and a configuration never skipping a test
whose behaviour it changes.

Non-goals: a cheaper recorder (the options-read design is closed); a pull request's matrix that
reruns only what its change moves against master's (row 508); the weekly run's reuse of master's
classes and record (row 522); one stray emitter disabling a module's skipping (row 539).

## 2. Design

### 2.1 Where the recording runs

Four candidates, costed in `VARKA-518-replan.txt` 4:

| | where | cost | on whose path | catalyst JUnit-s saved a matrix |
| :--- | :--- | :--- | :--- | ---: |
| (a) | the matrix's build-and-plan job | catalyst's defaults, 1688.2 s, at least 14 minutes before any bin | the matrix's | 8314 |
| (a') | the matrix's own defaults bins, catalyst planned after them | a barrier of about one bin; sql fills the slots for about 5.4 minutes | the matrix's | 8314 |
| (b) | the fork's master Build | every catalyst suite but five, about 12 minutes on its longest job | nobody's | up to 8314 + 3985 (+ 4980 tree suites) |
| (c) | the PR Build's catalyst jobs, suites chosen by 2.2's rule | about 16 s a JVM | the PR Build's, within its spread | 8314 |

(a) and (a') lose by arithmetic. Taken: **(c)** for every pull request, and **(b)** for the
suites (c) leaves out, which a matrix of master's commit reads directly and a pull request's
matrix reads where the master record is still valid for it (2.3). A job of its own beside the
Varka jobs for the left-out suites (d) would be the Build's longest, about 2000 laptop seconds on
one runner, and is not taken.

### 2.2 The rule for which suites a PR Build records

A catalyst suite records in the PR Build where its saving over its recording cost is at least 20,
or where its recording cost is at most 2.0 s on the laptop; any other suite is in
`VarkaTestReach.UNRECORDED` with its two numbers as the reason, which runs it whole under every
configuration as today. 20 is twice the break-even: a suite's cost lands in one of the Build's
six JVMs and its saving spreads over the matrix's 60 lanes, so with row 516's split rebalancing
the jobs on the recorded times break-even is 10, and the factor of two absorbs the derivation's
error (laptop costs, skip shares weighed by tests, not seconds). On the laptop's table the rule
keeps 49 suites for 88.2 s and 8314 saved seconds, and leaves out 20 for 1891.1 s and 3985
(`VARKA-518-replan.txt` 3). The cost is re-derived from CI's JUnit times in step 6 of section 5,
and a suite new since the table (0.7) starts left out until measured.

`UNRECORDED` then carries two kinds of entry, which the code keeps apart: **never** (past the
watchdog when recorded, or a tree suite until the owner's answer in section 8) and **cost** (left
out of the PR Build only). The master Build records the cost kind too (2.3).

### 2.3 The master record, and when a pull request may use it

The fork's master Build's catalyst jobs run with `VARKA_REACH_RECORD_ALL=1` beside
`VARKA_REACH_RECORD`, under which `install()` records every suite but the never kind. Its parts
are uploaded as the PR Build's are, kept seven days. Readers:

- **a matrix of master's commit** (row 522's weekly run) reads them as its own Build's record;
- **a pull request's matrix** reads master's catalyst parts in place of its own Build's when the
  newest master Build with a complete catalyst record is of a commit that is an ancestor of the
  head, and `dev/is-changed.py -m catalyst` over the diff from that commit to the head is false:
  nothing catalyst builds changed, so every catalyst test's compiles and emissions are master's.
  Otherwise it reads its own Build's (c) parts. Of the heads of the last 20 matrices, 10 touched
  no catalyst file, 8 only catalyst tests and 2 catalyst main sources (`VARKA-518-replan.txt` 4).

Taking a part by job: a part is read only where its job succeeded, and a module's record stands
without the other's. A job that failed leaves its tests with no line, which then run under every
configuration; the sql record no longer depends on catalyst's jobs passing. A part from a job that
did not finish is never read, since its suite lines may be missing while its test lines are not.
Master's catalyst parts read beside a pull request's sql parts bring their JVM-wide lines, which
the readers join to every test of the concatenated record, sql's included; they are kept, since
they can only cost skipping, and row 539 step 3's per-JVM lines are what remove that cost.

### 2.4 The reach rule for catalyst tests

A configuration may skip a catalyst test only where all of these hold, and runs it otherwise:

1. **The test's kernels reach the record.** Every kernel a test runs is either emitted through
   `VarkaLoopEmitter.emit` inside a window (the test's, through `VarkaMatrixTests` or
   `VarkaSuiteExtension`, or the suite's, through `VarkaReachWindow`), or a kernel no option can
   change (hand-built bytes, a committed class file). A test that builds kernels directly through
   the emitter is heard like any other; one emitting outside every window is row 539's warning,
   and in catalyst `VarkaShrinkerSuite` and `VarkaMemorySanitizerSuite` get the window here.
2. **The test reads the configuration's options only through the compiler and the emitter.** An
   option read anywhere else changes behaviour no byte comparison sees, so its configuration goes
   in `VarkaTestReach.FULL`. Of the main sources, only `exactRefusalStatus` is read outside the
   compiler and the emitter (by the evaluator; already `FULL`); `Lane` reads `validityByWidth` and
   `lanesOverride` when the emitter asks; `VarkaChrono.java:565` reads the constant
   `VarkaEmitOptions.DEFAULTS.julianMap()`, never a configuration's value (`VARKA-518-replan.txt`
   5). On the test side, a test that uses `VarkaMatrixRuns.BASE` (`VarkaMatrix.base` in Scala) for
   anything but a compile or an emission - `VarkaTypeConformance.java:541` picks its expectations
   from `plan.lane(BASE)`, `VarkaKernelWarmupProbe` keys shape-cache lookups by it - either has
   every option it reads in `FULL`, or is shown to branch only on what the emission also shows;
   step 2 of section 5 classifies each such call site in the recorded suites, and a test it cannot
   place gets the tag `VarkaMatrix.ReadsOptions`, whose tests record `*`.
3. **The configuration changes nothing beyond the bytes the test depends on.** A test tagged
   `C2Code` is about the C2 code a kernel compiles to, which `C2_OPTIONS` (the lane count, the
   half species, `useAVX`) change without the bytes: under those configurations it runs whatever
   its line says. Today `skips` does not check this; no recorded suite of 2.2 holds a `C2Code`
   test, so the check guards the future.
4. **Tests about the defaults.** A `PinsDefaults` test is cancelled under every configuration
   before the record is asked (`VarkaMatrixTests.scala:86-88`), so the record neither runs nor
   skips it. The planner's share may still count it as run, which can only overweigh a unit.

### 2.5 What the matrix and the Build change

- `build_and_test.yml`: the catalyst jobs set `VARKA_REACH_RECORD` (and `_ALL` when the Build is
  the master one); the upload step loses `if: matrix.module == 'sql'`.
- `dev/varka_matrix_reuse.py`: `SCOPED` covers both modules; `reach` prints each successful
  job's artifact name instead of one flag, the download step taking those; the master lookup of
  2.3 (`varka-master.yml` runs, ancestor check, `is-changed`).
- `VarkaTestReach`: `UNRECORDED` built as a `LinkedHashMap` of the two kinds; `_ALL`; the
  `C2Code` check in `skips`.
- `dev/varka_matrix_bins.py`: no change; the planner and the JVMs read a concatenated record by
  suite name (the built branch's finding).

## 3. Tests and proofs

1. **The digest proof, over the recorded suites.** As on the built branch's five suites, each
   test's emitted bytes logged as digests (an experiment patch, not committed) at the defaults and
   under three configurations of different reach (`cse=false`, `julianMap=false`,
   `narrowLanes=false`), and a run filtered by the record: no test whose digest moved may be left
   out. Over the 49 suites of 2.2, two JVMs a configuration.
2. **Planted fault 1, a misdescribed kernel.** The arm `misdescribeAdd=true`, which moves only
   AddDays' kernels and makes them throw, recorded beside the covered configurations: a full run
   with the record's named tests listed as expected failures fails no test the record does not
   name. On the built branch's five suites it failed 17 tests, all named; here over the 49.
3. **Planted fault 2, a test the record misses.** A scratch test in a recorded suite that reads
   `VarkaMatrix.base` without emitting and asserts `narrowLanes` is on, so it fails under
   `narrowLanes=false` and the record names nothing for it: step 2's classification must flag it
   (the tag or `FULL`), and the audit run of 3.5 must report it as a record miss when the tag is
   withheld.
4. **Planted fault 3, a tampered line.** One recorded test that `cse=false` moves (from proof 1)
   has `cse=false` removed from its line in a scratch copy of the record. It is caught without a
   run: the recording also writes `VarkaTestUse`'s digest lines (`-Dvarka.matrix.useRecord`,
   without JaCoCo's agent, so digests only), one per test and covered configuration, and a new
   check (`dev/varka_matrix_bins.py check-reach`, run in the matrix's build job before it plans)
   fails the record where a line omits a configuration whose digest differs from the defaults'.
   The line and the digests come from the same comparison, so only a damaged or edited file
   disagrees; the check is what makes such a file refused rather than read.
5. **The audit run**, for what the recorder never heard (fault 2): the matrix gains a dispatch
   input `audit`, under which each configuration runs exactly the catalyst tests the record would
   skip, and the report lists any that fails as a record miss, failing the run. A configuration's
   JVM computes no digests, so a miss that changes behaviour without failing is outside its reach;
   that is what the classification of 2.4 rule 2 is for. Fault 2 with its tag withheld is its
   self-test; the owner chooses whether it also runs on a schedule (section 8).
6. **Self-tests.** `varka_matrix_reuse.py --selftest` for the per-job parts, the module standing
   alone and the master lookup's three refusals (not an ancestor, catalyst changed, no complete
   record); `VarkaTestReachSuite` for the two `UNRECORDED` kinds, `_ALL` and the `C2Code` check.

## 4. The baseline, and predictions

The baseline is run 41092042163 (catalyst 40896 JUnit-s, 689.3 bin-minutes, 27.1 minutes) and the
eight master Builds of `VARKA-518-replan.txt` 1, whose catalyst suites steps sum to 20.0 to 39.2
minutes: job walls move more than the recording costs, so its cost is read from JUnit seconds.
Registered in `VARKA-518-replan.txt` 6 before any run:

- P1: the first matrix with the PR record: catalyst JUnit-s fall by 5800 to 10800 (point 8314);
- P2: its bin-minutes fall by 50 to 90;
- P3: its mean skip over the recorded suites within 10 points of the laptop's;
- P4: the first master Build after the merge: the recorded suites' JUnit-s at the defaults at
  most 180 s above master's median in all, 30 s in any JVM.

The wall is not predicted: runs at the same bin-minutes took 27.0 to 37.0 minutes as other runs
shared the slots. A miss of P1 or P2 below their range reopens 2.2's rule before step 7.

## 5. Order, files and size

1. **The PR record** (pull request A, medium). Rebuilt from `33a9043b806`: the emitter's report
   after its checks, the two suites' windows, the catalyst jobs' recording and upload,
   `UNRECORDED` by 2.2's rule with its two kinds, the per-job parts in `varka_matrix_reuse.py`,
   the `C2Code` check, `sql/varka/AGENTS.md`'s window rule. Files: `build_and_test.yml`,
   `dev/varka_matrix_reuse.py`, `VarkaLoopEmitter.java`, `VarkaTestReach.java`,
   `VarkaTestReachSuite.java`, `VarkaShrinkerSuite.scala`, `VarkaMemorySanitizerSuite.scala`,
   `sql/varka/AGENTS.md`. Its own Build edits a workflow, so it is a Spark Build that runs no
   catalyst Varka job (section 6 of the analysis VARKA-matrix-bins-1030.txt on branch
   `varka-ci-varka-jobs-always`, not merged): the first CI measurement is the fork's master
   Build of the merge.
2. **The option-reader classification** (in A): each recorded suite's non-compile uses of
   `BASE`, the tag where one cannot be placed, the result committed as a results file; proofs 1
   to 3 on the laptop before A is pushed.
3. **The record's checks** (pull request B, small): the digest lines written beside the record
   and uploaded with it, `check-reach` in the matrix's build job, the `audit` input and its
   report; proofs 4 and 5. Files: `build_and_test.yml`, `varka-option-matrix.yml`,
   `dev/varka_matrix_bins.py`.
4. **The master record** (pull request C, small): `_ALL`, the seven-day retention, the master
   lookup of 2.3 and its self-tests.
5. **The new suites measured** (in C): the seven suites of 0.7 recorded on the laptop and placed
   by 2.2's rule.
6. **CI's costs** (after A merges): each recorded suite's JUnit seconds in the master Build of
   the merge against master's median, P4; any suite whose CI ratio falls under 20 moved to the
   cost kind.
7. **The matrix timed** (after A, then after C): the first matrix on a commit with each record,
   P1 to P3, and an audit run on master.

Size: medium, as the row says; A carries most of it.

## 6. What it unlocks

The catalyst half of row 508's selection: a pull request's matrix keyed by test needs master's
record per test in both modules, which step 4's master record is. Row 522's weekly run reads the
full catalyst record of the same commit. Slot headroom: about 2.3 minutes of matrix wall at 30
slots for every pull request, more for those of 2.3's rule.

## 7. Out of this row

- A recorder that re-emits less (the closed options-read design; parallel re-emission inside a
  JVM, at most the runner's spare cores, is not planned).
- The tree suites' test-level skip on top of their own per-case filter, until the owner's answer.
- The sql record's own gaps: its suites outside a window (row 539), and whether the sql record
  should also honour 2.4's `C2Code` rule (it reads the same `skips`, so A's check covers it).
- Matrix packing and slot sharing (options 4 and 2 of the analysis VARKA-matrix-bins-1030.txt).

## 8. Questions for the owner

*Settled, the lead, 30 October 2026:* point 1, the tree suites are recorded in the master record
only, off the Build's critical path, and the audit run checks them like the others; point 2, the
threshold stays at 20; point 3, the audit run is dispatched by hand until row 522's weekly run
exists, then runs weekly; point 4, a pull request's reuse of master's record is this row's, and
row 508's wiring may share the lookup later; point 5, the hold is recorded as the cost estimate.

1. **The tree suites.** `VarkaSmallTreeSuite`, `VarkaIrFuzzSuite` and
   `VarkaCoverageCompositionFuzzSuite` cost 423.3 laptop seconds to record against 4980 saved
   (`VARKA-518-replan.txt` 3), but the fuzzers draw options per case, and whether a test-level
   skip over their own filter is sound wants a look. Record them in the master record only, in
   both, or in neither?
2. **The threshold.** 20 against a break-even of 10; 10 would add `VarkaDayTimeInterval-
   ConformanceSuite` (18.2) and nothing else of the laptop's table.
3. **The audit run's schedule.** On dispatch only, or weekly on the fork after row 522, at the
   cost of a full catalyst matrix (40896 JUnit-s) a week.
4. **The master record for pull requests** (2.3's second reader) here or in row 508's CI wiring,
   which needs the same lookup.

## 9. Done when

- a covered configuration runs a catalyst test only where the record names it, or where 2.4 says
  it must run;
- the PR record's cost on CI's catalyst jobs is measured from JUnit seconds (P4);
- the matrix's catalyst JUnit seconds and bin-minutes are measured before and after (P1 to P3);
- planted faults 1 to 3 are caught as 3 says, and the audit run reports no record miss on master.

## 10. Outcome
