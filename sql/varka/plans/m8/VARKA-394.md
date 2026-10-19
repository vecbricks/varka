# VARKA-394: the option matrix green

*Row 394 of `m8/PLAN.md`, run beside wave 0 (section 4.1's note of 19 October 2026). Planned on
19 October 2026 from master `e11d025e91e` and the dispatch of `varka-option-matrix.yml` on master
`25d8b251098` (run 39168155152, 18 October), read with the two scheduled runs before it (11 October,
run 38129966925; 18 October, run 39109529723) and the green dispatch of 5 October (run
37291764919).*

## 1. Where this came from

The option matrix reruns the Varka suites once per configuration, the emit defaults with one option
changed, in twelve jobs a week (`m7/VARKA-248.md` 3.2). It is where every new lane of milestone 8
is checked across the emit options, and it cannot judge one while it fails. Its last green run was
the dispatch of 5 October. The scheduled run of 11 October failed 5 tests, the scheduled run of 18
October 18, and the dispatch on `25d8b251098` that evening 51, in 13 configurations. Milestone 7's
close review found nobody had read them (`m7/PLAN.md` 12).

## 2. The 51 failures, read

### 2.1 Thirty-two are one trap: the child JVM's budget

All sixteen tests of `VarkaIrFuzzSuite` failed under `useAVX=0` (shard 10) and under
`groupLocalSlots=false` (shard 10) with "did not finish in 9 minutes in its own JVM". The suite mixes
in `VarkaOwnJvm` (VARKA-246), so the matrix's runner JVM, which runs a module's whole shard of
suites, does not run it: its first test forks a child that runs the whole suite, with a budget of
one test's cap less a minute, nine minutes, and the parent waits inside that test's timed body. This
is row 392's trap (`VARKA-392.md` 2). The child under the two options reached the suite's last test,
"random IR trees match the reference evaluator", when it was ended: the suite fits nine minutes on a
runner under the defaults and runs a little past it under the two options, which slow every kernel
(no vector instructions at all, or every group's values through the shared slots).
*Corrected the same day by the runs of section 8: the options do not slow the suite, whose time is
its compiled test's warm-up waits; the margin was thin under the defaults as well.*

Row 392 fixed the nightly fuzz workflow by running the suite directly in JVMs that hold it alone
(`-Dvarka.ownJvm=true`). The matrix cannot pass that flag to its runner JVM, which runs every
catalyst suite. But `dev/varka_matrix.sh` already decides what each runner JVM runs, so it can
give a suite that runs alone a runner JVM of its own, and that JVM the flag.

**Why this is not milestone 9's scope item 111.** Item 111 is the harness redesign: the child run
outside any timed test body, so that a suite sharing a JVM it does not control (sbt `testOnly` over a
mixed set, an IDE) can take as long as its tests need. This row uses the escape row 392 used, in the
one runner that already chooses its JVMs' suites. The child, its budget and its message stay as they
are for every other way of running such a suite.

### 2.2 Nineteen fail under one option each

Each was read and put on one of two sides: the test depends on the default and must say so, or
the option found a wrong answer. Each failing test was bisected to the pull request that added it
first (`git log -S` on its name over `edd80eae02c..25d8b251098`, the green dispatch to the failing
one).

| configuration | test (suite) | added by | reading | change |
| :--- | :--- | :--- | :--- | :--- |
| `driverOutputTable=false`, `splitDriver=false`, `methodByteBudget=0` | a trace shared across emissions names each emission's correction (`VarkaKernelPlanSuite`) | #715, VARKA-290, 11 October | the 300-entry shape with a misdescribed driver is its sibling's ("a planned build the measurement finds over a limit is corrected once"), which has a skip line under each of the three with its reason: the reference driver forms are over the driver's ceiling and decline, and with no byte budget the emitter caps a kernel at 64 ops | three `fails` lines, the sibling's reasons |
| `methodByteBudget=0` | a split predicate's raising conjunct: the projection's earlier error (`VarkaFilterRaiseOrderSuite`) | #911, VARKA-387, 17 October | the 160 comparisons are past the 64-op cap that replaces the byte budget, so the filter does not fuse and the plan has no `VarkaFilterExec`; the test already pins the two options its shape rests on | the budget pinned beside them, as `VARKA-253`'s test pins it |
| `checkIntOverflow=false` | with no error above, the filter's own error is the answer; a LIMIT the rows before the filter's raising row satisfy answers...; a projection that would raise on a row after the filter's raising row never sees it (`VarkaFilterRaiseOrderSuite`) | #911 | `y + 1 > 0` on `Int.MaxValue` wraps with the check off, so the filter raises nothing and the later rows reach the projection: the option's documented wrong value | three `fails` lines, the option's reason |
| `checkIntOverflow=false` | a residual conjunct that can raise sees every row Spark evaluates it on... (`VarkaDifferentialSuite`) | #908, VARKA-380, 17 October | `ym + ym` overflows a year-month interval and wraps with the check off | a `fails` line, the option's reason |
| `checkIntOverflow=false` | every saved reproducer is a regression that now agrees or a known one that still differs (`VarkaSparkReproducerSuite`) | #909, VARKA-381, 17 October (the reproducer file) | `varka-386-20261017-22897.sql` is known as "only Varka throws: INTERVAL_ARITHMETIC_OVERFLOW" (row 386); with the check off Varka stops throwing and the two agree. A reproducer's status is the defaults' | a `fails` line; the whole corpus's test is then not run under this one configuration |
| `checkIntOverflow=false` | `year(d) + i` is answered inside its domain... (`VarkaCoverageDifferentialSuite`) | #818, VARKA-279, 15 October, which also added its skip line | a stale line: the test passes, because the domain's guard declines the overflowing rows with the value-range status, which the option does not turn off | the line removed |
| `arrowBatchSize=1` | a residual conjunct that can raise... (`VarkaDifferentialSuite`) | #908 | both engines answered `[5], [7]`: with one row a batch the cached scan's statistics prune the overflow row's batch on `d < DATE'2021-06-01'`, so Spark evaluates the raising conjunct on no row. The test's premise, that both raise, fails; the engines agree | a `fails` line, the reason the axis's `null-as-false` line gives |
| `arrowBatchSize=1`, `arrowBatchSize=3` | a far day in a projection split across two kernels declines the whole batch (`VarkaFarDaySuite`) | #874, VARKA-379, 17 October | the test counted the served batches as 0, true of one batch; at 1 and 3 rows a batch the batches without the far day are served (at 1 row a batch 3 of 4, or 4 of 5 with the null row; at 3 rows 1 of 2) | the expectation is the cache's batch count less the far day's |
| `validityByBitmap=false`, and its subject's three flags | VARKA-379: the column bound is checked in the kernel's own loop... (`VarkaGuardEdgeSuite`) | #874 | the split shape set a 600-byte method budget so that each output takes a method; the reference validity forms make a loop method 611 bytes, and the compiler declines it | the budget raised to one every validity form fits, the test's own `loops.size >= 2` still holding |
| `lanesOverride=4`, `lanesOverride=16` | a mismatch the kernel check finds is signed by its kind (`VarkaShrinkerSuite`) | #761, VARKA-311, 12 October | the test emitted from `VarkaEmitOptions.DEFAULTS`, so under a lanes override its 256-bit kernel was a second species beside the JVM's established one, which the species guard refuses | emitted from `VarkaMatrix.base`, the house rule |
| `lanesOverride=16` | the allocation sampler events every sampled batch, and the samples go clean once C2 has the loop (`VarkaProjectExecSuite`) | before 5 October | the dispatch's shard 4 ran on a runner whose preferred int vector is 256 bits (its species guard refused `SPECIES_256` beside the established 512): a 16-lane kernel is wider than any vector its C2 keeps in registers, so every batch allocated the same 12,464 bytes. The scheduled run's shard 4 that morning had a 512-bit runner and passed. Runner class, not a regression | the test assumes the base's lanes are no wider than the JVM's preferred |
| `heavyGroupOutputs=0` | a warm-up claimed by a batch with only nulls compiles both of the kernel's drivers (`VarkaKernelWarmupSuite`) | e15b56c3050, VARKA-295, 9 October | **not this option's**: the test runs its warm-up in a forked probe whose command line carries no `varka.matrix.config`, so the probe ran the defaults. A timing failure under the defaults on a runner's four cores beside a second JVM, out of this row (section 7) | none |

None of the nineteen read as a wrong answer, eighteen of them under their option and the nineteenth
not the option's. The 11 October run's VARKA-253 failure
("inside a Spark task, the forms without a scratch address refuse a kernel with scratch", under
`methodByteBudget=0` and `shareChronoPrefix=false`) is gone from the dispatch: #918 (VARKA-261, 18
October) pinned the options its kernel needs. The row's count is 19 on the dispatch, not 21.

## 3. The change

* `dev/varka_matrix.sh`: a suite whose source mixes in `VarkaOwnJvm`, or a JUnit suite under
  `@VarkaSuite(ownJvm = true)`, takes a shard line of its own, first in its module, and its runner
  JVM gets `-Dvarka.ownJvm=true`. One JVM per such suite, not one for all of them: with the species
  guard off, suites emitting at different widths in one JVM are what the trait keeps apart. A
  module with more than one line names its JVM directories `<module>-<k>`, as `--split` did;
  `dev/varka_matrix_report.py` already reads both forms. This reaches every caller: the matrix, the
  gate (`--defaults --split`), CI's scoped and matrix jobs, and the coverage run.
* `sql/varka/matrix/skips.tsv`: the nine `fails` lines and the one removal of section 2.2.
* `VarkaFilterRaiseOrderSuite`, `VarkaFarDaySuite`, `VarkaGuardEdgeSuite`, `VarkaShrinkerSuite`,
  `VarkaProjectExecSuite`: the test changes of section 2.2. `VarkaSpeciesGuard` gains
  `preferredIntLanes()`, so that a Scala test reads the JVM's width without an incubator type in
  its own signature.
* `sql/varka/skills/testing-and-debugging.md`: how the matrix runs a suite that runs alone.

## 4. Predictions, registered before the runs

Read against the calibration table (`dev/varka_calibration.py`); the nearest rows are suite times
(VARKA-243's ratio of about 2 for a new suite's setup) and VARKA-274a's compiled test.

1. **`VarkaIrFuzzSuite` run directly in one laptop JVM under the defaults takes 2 to 5 minutes**,
   and under `useAVX=0` and `groupLocalSlots=false` each 1.0 to 1.6 times that. The runner's child
   fitted nine minutes under the defaults and not under the two options, so the options cost the
   suite a few tens of percent there.
2. **No test of the suite takes more than 40% of the suite's time** under either option, so on a
   runner where the whole suite runs 9 to 12 minutes the longest test stays under 5 minutes, half
   its 10-minute cap.
3. **A configuration's catalyst run takes within 10% of its time before the change**, on the
   laptop, for one configuration: the child was already a JVM of its own, and the parent's own
   suites ran while no child was running. *(19 October, before any run: scored from the runners
   instead, section 8.)*
4. **Every added skip line fails under its configuration and the removed one passes**, so the
   report has no stale line; and every changed test passes under the defaults and under the
   configurations it failed in.

## 5. Verification

Locally, under the shared slots, from this branch's build (`dev/varka_matrix.sh --skip-build`):

* each touched suite under each configuration it failed in, and under the defaults;
* `VarkaProjectExecSuite`'s sampler test under `lanesOverride=16` with `-XX:MaxVectorSize=32`, the
  dispatch's runner class: cancelled, where before the change it fails;
* `VarkaIrFuzzSuite` alone under the defaults, `useAVX=0` and `groupLocalSlots=false`, through the
  matrix script, for the per-test times of prediction 1 and 2;
* one configuration's whole catalyst module, for the layout and its times.

What only the runners show: the matrix's first dispatch on master after the merge. It cannot run
on this branch: the fork the pull request comes from does not carry the workflow on its default
branch, and the branch is not pushed to vecbricks/varka. The workflow has no shard selector, so the
whole matrix is dispatched; section 8 says which shards must change.

## 6. Explicitly out of this row

* The harness redesign of scope item 111.
* The warm-up probe's failure (section 7).
* The matrix logging its runner's CPU and vector width, as row 396 makes the fuzz workflow do.

## 7. Findings outside the row

* **The warm-up verdict test failed under the defaults on a runner.** "a warm-up claimed by a batch
  with only nulls compiles both of the kernel's drivers" reported `COMPILED` and then 301,312 bytes
  allocated over the batches with some nulls, against a limit of 20,480: a verdict the later calls
  did not bear out, on a runner beside a second test JVM. Its fork runs the defaults whatever the
  configuration (it passes no `varka.matrix.config`), so the probe tests no option at all.
* **A runner-class failure cannot be diagnosed from the matrix's logs.** The lanes-16 sampler failed
  on a 256-bit runner and passed on a 512-bit one the same day; only a species-guard message in
  another test showed which was which.

## 8. Outcome

Built and run on the laptop on 19 October 2026 (Zen 5, `MaxVectorSize=64`, JDK 25.0.4.1), from
this branch's build, through `dev/varka_matrix.sh --skip-build`, under the shared slots.

**The touched suites under every configuration they failed in.** `VarkaGuardEdgeSuite`,
`VarkaShrinkerSuite`, `VarkaKernelPlanSuite`, `VarkaFilterRaiseOrderSuite`,
`VarkaDifferentialSuite`, `VarkaSparkReproducerSuite`, `VarkaFarDaySuite`, `VarkaProjectExecSuite`
and `VarkaCoverageDifferentialSuite` under the defaults and ten configurations
(`validityByBitmap=false`, its subject's three flags, `lanesOverride=4` and `16`,
`driverOutputTable=false`, `splitDriver=false`, `methodByteBudget=0`, `checkIntOverflow=false`,
`arrowBatchSize=1` and `3`): every configuration exit 0, no failure, no unfused test, no stale
line, 42 minutes at `-j 8`. Read test by test from the JUnit reports: each of the nine new lines'
tests was cancelled as expected to fail under its configuration and passed everywhere else; the
`year(d) + i` test passed under `checkIntOverflow=false`; the far-day, column-bound, split-predicate
and signed-mismatch tests passed under every configuration.

**The dispatch's runner class.** `VarkaProjectExecSuite` and `VarkaShrinkerSuite` under
`lanesOverride=16` and `4` with `-XX:MaxVectorSize=32`: the sampler test cancelled under 16 ("a
kernel at 16 int lanes is wider than this JVM's preferred 8"), passed under 4 and the defaults; the
signed-mismatch test passed in all three.

**The split shape's budget.** `dev/varka_emit.sh` on `dayofweek(d2), weekday(d2), month(d)` under
`exactRefusalStatus=true,severalKernels=false`: at 600 bytes the defaults split it into a 376-byte
and a 506-byte loop, and with `validityByBitmap=false` the guarded month's masked loop is 611 bytes
and declines, as on the runner; at 700 every validity form gives two loops, the month's last
(611 bytes masked at most), and one method for all three is 803 bytes, so 700 still splits.

The margin the other way, which the review below asked for: `VarkaGuardEdgeSuite` under every
one of the matrix's 51 configurations and the defaults, all exit 0, so no configuration shrinks the
three outputs into one 700-byte method.

**The suites that run alone, in runner JVMs of their own.** The whole catalyst module under
`groupLocalSlots=false` with the defaults beside it, `-j 6`: 12 JVMs a configuration, the shared
one and one for each of the 11 own-JVM suites the matrix keeps (`VarkaTierWitnessSuite` is
excluded), all exit 0, 694 tests a configuration as before, 9 min 38 s in all; the shared JVM 500 s
and 451 s, `VarkaIrFuzzSuite`'s 306 s and 271 s.

**The predictions of section 4, scored.**

1. The suite in 2 to 5 minutes under the defaults, 1.0 to 1.6 times that under the options:
   **held** under the defaults (248 s) and `useAVX=0` (248 s, 1.00), **missed low** under
   `groupLocalSlots=false` (237 s, 0.95). The reasoning was wrong: the suite is its compiled test,
   whose cases wait for warm-ups to settle, and those draws carry their own options rather than the
   configuration's, so neither option slows it. On the runner the two options' compiled tests were
   not slower either: case medians of 12.6 and 12.4 s (int and long lane) under `useAVX=0` and 8.6
   and 8.4 s under `groupLocalSlots=false`, against 14.4 and 10.9 s under the defaults of the same
   shard, with a tail to 68 s under `useAVX=0`. The nine minutes were thin under the defaults too,
   and which configuration ran past them is closer to chance than to the option.
2. No test over 40% of the suite: **missed**. The compiled test is 81 to 88% of it (219 s, 192 s
   and 219 s). What the prediction was for still holds: on the runner that test finished inside the
   children's nine minutes under both options, the child going on to the suite's last test, and it
   now has a 10-minute cap of its own.
3. A configuration's catalyst run within 10% of before, on the laptop: **not scored here**, since
   a before run needs a build of master beside this one. Scored instead from the dispatch on this
   branch against 39168155152, shard by shard.
4. Every new line fails under its configuration and the removed one passes: **held**.

**Checks run:** scalastyle on catalyst's and sql's tests (0 errors), checkstyle on catalyst's tests
(one error, in upstream's `StUtilsSuite.java`, not touched here), `shellcheck` on
`dev/varka_matrix.sh` (one warning, at a line not touched here), the 100-column and non-ASCII
checks, `dev/varka_calibration.py --check`.

**The self-review**, an independent read of the diff, found no correctness bug and seven smaller
points, six taken: the column-bound budget checked under every configuration (above); the Scala
detection pattern skipping comment lines and taking `extends` as well as `with`; the JVM counts in
the gate's, the scoped suites' and the matrix's own usage text; the script's and the skill's
sentence on why the fuzz suite ran out, which had named the options as the cause; and two places in
this file, the count of nineteen and the far-day batches with the null row. The seventh, the
commit's attribution line, is the form the row's rules set.

**What only the runners show.** The first dispatch of `varka-option-matrix.yml` on master after
the merge (section 5 says why not on this branch). The shards that must change are 0 (`arrowBatchSize=3`, the validity subject), 1 (`validityByBitmap=false`),
2 (`checkIntOverflow=false`), 3 (`driverOutputTable=false`, `lanesOverride=4`), 4
(`lanesOverride=16`), 5 (`splitDriver=false`), 7 (`methodByteBudget=0`), 10 (`useAVX=0`,
`groupLocalSlots=false`) and 11 (`arrowBatchSize=1`); shard 1's `heavyGroupOutputs=0` failure is
the warm-up probe of section 7 and may recur. The `lanesOverride=16` sampler fix is exercised only
when shard 4 lands on a 256-bit runner, so one green run does not prove it. The done-when's full
run on master is the first dispatch or scheduled run after the merge.
