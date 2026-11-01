# VARKA-516: the Build's Varka jobs, balanced to the suite that sets them

*Written 30 October 2026, a rework of row 516's plan for the Build's catalyst jobs (step 3). The
data are six Varka-scoped Builds of MaxGekk/spark, read through the Actions API and the jobs'
JUnit artifacts, in `m8/results/VARKA-516-shards.txt`; every number below is from that file,
which also gives the commands. The row's steps 1 and 2 merged in #1119: the weights are the mean
of two Builds and `--check-weights` warns when a suite moves past a factor of 3.5.*

## 1. The critical path

A Varka-scoped Build without long new tests took 15.9 to 20.6 minutes, median 18.0 (results 1).
Its last job is a catalyst Varka job, and inside that job the tests phase dominates (results 2):

- **Not the precondition alone**: it is 1.9 to 2.9 minutes, serial before every job, the same for
  every split.
- **Not the restore or the compile**: with the saved compile state (row 520) a job's setup is 1.4
  to 3.5 minutes and its build 0.7 to 2.4.
- **Not a sequential job**: the option-matrix classes job ends 5.8 to 7.2 minutes in (results
  7), and the reach record and the harness store come from the sql jobs, which end before the
  slowest catalyst job in every Build.
- **The tests phase of a catalyst job is.** Catalyst's first two jobs take 633 to 674 s whenever
  they draw an EPYC 7763, against 309 to 414 s for the sql jobs there. 19 of the 36 Varka jobs
  drew a 7763, the most common CPU and among the slowest (results 3), and every Build had one on
  a catalyst job.

Inside a job the suites run as JVM lines, two at a time: one line for each own-JVM suite, then the
two shared JVMs (results 5). Two things set the phase:

1. **One suite.** `VarkaWidthAuditSuite` takes 589.0 s on a 7763 (results 4), alone in one of its
   job's shared JVMs. No split can put a job's phase below it. It forks a probe JVM per census,
   one after another, and its forced 128-bit test forks three, at 16, 32 and 64 bytes. That test
   reads only the 128-bit census. The other two feed only the census-file test, and that test
   cancels on every runner. So on CI, two of the suite's five probes have nothing asserted on
   them.
2. **The launch order.** Own-JVM lines go first, so the shared JVM that holds the audit starts
   after them: 659 s replayed against 588 s with the longest line first, in 41059844622's
   catalyst 1 of 3.

The weights add a third, smaller cost. They are the mean of Builds on mixed CPUs, and one suite
moves 1.73 times between the fastest CPU and the slowest (results 4). So the split over-weights
whatever ran on a fast runner when the weights were taken.

The replay in results 6 runs `dev/varka_suite_shards.py`'s own placement over the 7763 medians. It
gives the current split 673, 648 and 523 s, within 2.3% of every catalyst measurement on a 7763.
It is the model the predictions below use.

## 2. The fix

Three changes, all in Varka-scoped files. None of them touches `build_and_test.yml`: that edit
would make the pull request's Build run Spark's module matrix, not the Varka jobs it changes
(`dev/varka_scope.py`).

**(a) Weights from one CPU.** `sql/varka/ci/suite-times.tsv` is refit with `--update-weights`
over only the JUnit reports of jobs that ran on an EPYC 7763. Each job's CPU is in its log; the
directories are chosen from results 2. Every catalyst and sql suite ran on a 7763 in the six
Builds, apart from the three JIT-measuring suites of the quiet step, whose reports the refit
reads from that step's directory. The file's header states the rule. A suite that has no 7763
time keeps its weight.

**(b) A job's JVMs from the committed weights, longest first.** `dev/varka_matrix.sh` gains
`--weights FILE`. With it, the shared suites are split over the `--split` JVMs by FILE's seconds,
and the JVM lines start in order of their seconds, longest first. Without it the script does what
it does today. Each line keeps its index, so the `<module>-<part>` directories, the JUnit paths
and the reports keep their names. `dev/varka_scoped_suites.sh` passes the committed file.

The JUnit cache that `--split` reads today is not CPU-consistent. Worse, after a refit it holds
nothing for a suite that has just moved into the job, so that suite would take the median of
the job's other suites, and the audit could start last. With the committed file, a job's whole
plan depends only on its checkout, which is also what the replay assumes. The cache step stays
in the workflow and goes unused by these jobs; removing it is a workflow edit for another pull
request.

**(c) The audit forks only the probes something reads.** `byWidth`'s lazy census of every width
becomes one lazy census per width. The forced 128-bit test asks for 16 bytes only. The census-file
test still asks for all three, so on the census host the regeneration and the comparison are
unchanged. On a runner the suite forks three probes instead of five, and every assertion it makes
there is the same. This edits a test, so it is the first open point.

**Considered and not taken:**

- **Four catalyst jobs.** These stay at 591 s until (c) lands (results 6). After (c) they buy 60
  to 70 s over three jobs, and the Build's next job, Linters, ends at 12.3 to 15.2 minutes and
  would take that time back (results 7). Four jobs would also mean editing the five
  `shard == '0/3'` tests in the workflow.
- **Two sql jobs, giving the third job to catalyst.** sql's slowest phase goes from 364 to 470 s,
  and its build is the longer of the two modules'.
- **A placer that balances a job's two-slot makespan instead of its sum.** After (a) and (b), the
  sum placer already reaches the floor the largest unit sets.
- **More JVMs a job.** Runners have four cores, and these suites measure the JIT.

## 3. Predictions, registered before any run

These are replays of measured seconds through the planner's own code, which matches today's
split within 2.3% (results 6). No calibration-table ratio is applied: none of the table's rows
prices a replay. Each band is the replay plus 5%. A phase is a job's tests phase, read on a job
that drew an EPYC 7763.

1. **After (a) and (b):** the slowest catalyst job's phase is at most 620 s (replay 591), against
   633 to 674 now. The other two catalyst jobs' phases are at most 560 s (replay 530 and 533).
2. **After (c):** `VarkaWidthAuditSuite` takes 330 to 430 s on a 7763, against 588.4 and 589.7.
   The band covers the 16-byte probe being a third to a half of the forced test's 362.5 s. The
   slowest catalyst phase is at most 525 s (replay 490 to 500).
3. **sql:** no sql job's phase passes 390 s (replay 364).
4. **The Build:** after both, the median wall of the Builds measured (section 5) is at most 15
   minutes, against 18.0, and none passes 16, where they ran 15.9 to 20.6. Linters is the last job
   to end, or ends within a minute of the last, in at least half of them.

## 4. Correctness

- **Every suite runs exactly once.** `place()` puts each suite in exactly one job. Every job
  computes all N shares and prints their digest, so two jobs that saw different inputs print
  different digests. The new launch order changes when a line starts, not what is in it. The
  pull request adds a doctest that the union of the N shares is the input, with no repeats. The
  measurement checks it again on the JUnit artifacts: each listed suite appears once per module.
- **The reach record and the harness store are still written.** The sql jobs write them at the
  defaults, one artifact a job, under the same names. The sql job count stays at three, and
  `dev/varka_matrix_reuse.py` counts the reach parts against the scoped sql jobs it finds. A refit
  moves sql suites between jobs, but the union of the parts is the same tests. In sql, (b)'s
  launch order changes nothing, since sql has no own-JVM lines and its two JVMs start together.
  Its split source does change which suites share each of a job's two JVMs. The record and the
  store are written per job, not per JVM, so their union is unchanged.
- **Nothing the option matrix reads changes shape.** The matrix does not pass `--weights`.
  `suite-times.tsv` keeps its columns. The matrix planner reads its values only for suites
  `sql/varka/matrix/suite-seconds.tsv` lacks. The audit and `VarkaOperatorCensusSuite` are outside
  the matrix already. `job-suites`, which the new-tests and emitted-bytes steps read, is written
  as before.
- **(c) changes no assertion on any host.** On the census host the census-file test still forks
  every width. On a runner it cancels before it renders, as it does now.

## 5. How it is measured

The CPU draw moves a phase more than the split does, so each side is measured on one commit, built
three times:

- **Before:** 516c's first commit, which adds only the partition doctest. Its split digest must
  still read de36b04d7e69.
- **After:** 516c's head, and later 516d's.

The extra Builds of a commit are pushes of it under two more branch names, so each is a fresh run
with its own artifacts. Pushing is the lead's step; this plan starts nothing. Reruns are not used.
The one rerun Build read, 40947407586 (results 7), re-ran only its failed jobs, so whether "re-run
all jobs" can upload a JUnit artifact under a name the first attempt already used is unverified.
A rerun's earlier attempt is also read only through `attempts/<n>/jobs`.

Each Build is read the way results 2 reads its Builds: CPU, setup, build, tests phase and wall.
Predictions 1 to 3 are scored on the 7763 draws, prediction 4 on the walls. A Build that draws no
7763 on a catalyst job is reported and not scored. The five Builds of results 1 stay as the record
of the split across commits. The scores go in `m8/results/VARKA-516-after.txt`.

## 6. Order, pull requests and size

1. **516c, small:** (a) and (b), with their doctests, in `dev/varka_suite_shards.py`,
   `dev/varka_matrix.sh`, `dev/varka_scoped_suites.sh` and `sql/varka/ci/suite-times.tsv`. Its
   first commit is the doctest alone, so that commit and the head are the before and the after
   of section 5.
2. **516d, a few lines of a test:** (c), if the owner takes it. In the same pull request, the
   audit's weight is set from its own Build, as step 2 of the row asks of a change that moves a
   suite's cost.
3. **The row's last item:** two consecutive Builds of master timed after both, scored against the
   done-when, or the restated done-when (second open point).

## 7. Out of this row

Results 7 has the numbers.

- **The precondition's full-history checkout** runs serially before every job: 98 and 137 s in
  the two Builds whose steps were read.
- **Linters** ends 12.3 to 15.2 minutes in and becomes the floor once the Varka jobs end sooner.
- **A change's new tests** rerun five times in whichever job holds their suite. In 41099294241
  that took 1698 s and set a wall of 43.5 minutes.
- **The JUnit times cache** goes unused by the scoped jobs after (b).

## 8. Open points for the lead

*Settled, the lead, 30 October 2026:* point 1, change 3 (the audit suite starting only the probes
something checks) is this row's, as 516d; point 2, the done-when compares the shards of Builds whose
catalyst shards drew one CPU, so a runner draw alone cannot fail it; point 3, the weights are refit
on the AMD EPYC 7763, the runners' most common CPU; point 4, sql's weights are not refit, since it
gains no wall time there.

1. **Is (c) part of this row?** (c) edits `VarkaWidthAuditSuite`, a test. On the census host it
   changes no assertion, but it does change which probes a runner forks.
   - (i) In row 516 as 516d, since without it the split cannot get below the audit (results 6).
   - (ii) A row of its own, so that row 516 changes only CI tooling.
   - (iii) Not taken. The slowest phase then stays near 591 s on a 7763.
2. **The done-when.** It asks that each module's longest job be within a fifth of its shortest on
   two consecutive Builds of master. With today's split, the same job's phase moved from 394 s on
   a 9V45 to 670 s on a 7763 (results 2), so the runner draw alone can fail it.
   - (i) Keep it, and count only Builds whose catalyst jobs drew one CPU model.
   - (ii) Restate it on the tests phases of jobs on the same CPU.
   - (iii) Restate it on the planned loads, plus a Build wall bound such as prediction 4.
3. **The CPU to refit on.** The plan takes the EPYC 7763: it is the most common draw and the
   slowest.
   - (i) The 7763.
   - (ii) Each suite's median over every CPU. That places by an average machine, and the replay
     was not run for it.
4. **sql's weights.** Refitting sql moves its suites between its jobs for no gain in wall time,
   since its slowest phase stays at 364 s.
   - (i) Refit both modules, under one rule.
   - (ii) Refit catalyst only, so the sql reach parts keep their tests.
