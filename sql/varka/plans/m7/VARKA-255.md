# VARKA-255: CI and benchmark runs with no laptop in the path

## 1. Where this came from

Milestone 7 row 255 (`m7/PLAN.md` 2.4), item 76 redesigned. The fork's CI is kept to one Build at a
time by `dev/varka_ci_queue.sh` (VARKA-176), a process on the owner's laptop that cancels the run a
push starts and reruns it in turn; `VARKA-227.md` 3 proposed moving that dispatcher to a workflow on
`vecbricks/varka` with a personal access token on the fork, and the owner declined any stored token
on 2 October 2026. `m7/PLAN.md` 2.4 then laid out the token-free split: the queue on `MaxGekk/spark`
on a five-minute cron with its own `GITHUB_TOKEN`, the check sync on the base repository on a cron
with its own; master's head run after each merge; a pull request's new tests rerun several times,
and a fix's new test required to fail on the base, as ClickHouse's CI does (`m7/READING.md` 9).

The halfway review of 13 October (`m7/PLAN.md` 9.2, second and third findings; 9.5) added two
things the owner agreed to put in this row: **(a)** the benchmark pairs
(`dev/varka_bench_pair.sh`, VARKA-256) and results-file regenerations move to GitHub-hosted
runners through `.github/workflows/benchmark.yml`, the datapath probe (`dev/varka_datapath.sh`)
checked first, so that 322a's ten Java-against-Scala pairs, row 325's two results files (#789)
and 322a-2's pair (#791) stop waiting for an idle laptop; and **(b)** each gate and nightly step's
time is recorded from CI into a committed budget, and a pull request that adds more than 5% to a
step says so (`sql/varka/AGENTS.md`, "Pull requests").

The admission check below overturns two premises of `m7/PLAN.md` 2.4 - that a five-minute cron can
drive a queue, and that one Build at a time is the right rule - so section 3 replaces that section's
dispatcher with a different mechanism and keeps its constraints: no stored credential, each
repository acting only on itself with its own `GITHUB_TOKEN`, and nothing on the laptop. That change
was the owner's to accept, and the owner accepted it on 13 October 2026 (section 8,
owner action 1).

## 2. The admission check, done

Read-only, on 13 October 2026 between 20:30 and 21:55 CEST, from the laptop with the owner's
`gh` authentication for reads and `curl` without any for the credential-free probes. Times below
are CEST; the API reports UTC, two hours earlier. Nothing was dispatched, rerun, cancelled,
enabled or labelled. The probe scripts lived in the scratch area and are not committed.

### 2.1 GitHub's cron does not fire on time here, by hours

`m7/PLAN.md` 2.4 rests on a five-minute cron. GitHub's documentation says the shortest interval is
five minutes, that "the `schedule` event can be delayed during periods of high loads" and that "some
queued jobs may be dropped" (Events that trigger workflows, `schedule`). What it does in these two
repositories:

| workflow (repository) | cron | when it actually started | late by |
| --- | --- | --- | --- |
| `update_build_status.yml` (base) | every 15 min | 6 runs in 48 h of 12 and 13 October: 04:02, 11:10, 21:02, 03:20, 10:05, 17:57 | gaps of 377 to 592 min |
| `varka-fuzz.yml` (base) | 04:00 daily | 09:56 to 10:45, every day 6 to 13 October | about 6 to 7 h |
| `varka-weekly-matrix.yml` (base) | 03:00 Sunday | 08:01, 08:32, 08:39 on 27 September, 4 and 11 October | about 5 to 6 h |
| `stale.yml` (fork) | 02:00 daily | 05:50 to 07:31, 2 to 13 October | about 4 to 6 h |
| `build_java25.yml` (fork) | 06:00 every second day | 11:09 to 13:30, 21 September to 13 October | about 5 to 8 h |

(`gh api repos/<repo>/actions/workflows/<file>/runs?event=schedule`.) A fifteen-minute cron
acting three times a day is not a queue tick, and a five-minute one would not do better: the
delay is GitHub's, not the interval's. **No part of the design may wait on a cron**; crons stay
only as a backstop. This also explains why pull requests' `Build` checks have read stale since
the laptop queue stopped dispatching the sync (2.3): the cron is the only other thing that
updates them.

**Nothing needs enabling on the fork.** `m7/PLAN.md` 2.4 expected a step "enable the scheduled
workflows once in the Actions tab". The fork already runs them: `actions/runs?event=schedule` on
`MaxGekk/spark` reports 2,500 scheduled runs, five of them on 13 October (90 of the latest 100
skipped, since they are apache/spark's branch schedulers). What does bind is the default-branch
rule: `schedule`, `workflow_dispatch` and `workflow_run` all trigger "only if the workflow file
exists on the default branch" (same page), and the fork's `master` is apache/spark's of 21 August
(`ee11a92a9f1`); `varka-canary.yml` is not on it (404), while `benchmark.yml` is, which is why
`benchmark.yml` can be dispatched on the fork at a PR branch's ref and a new workflow file could
not. Section 3 puts no file on the fork's `master`, which settles open question 7.3 of `m7/PLAN.md`
by not needing it.

### 2.2 The fork's runner slots on 13 October: full only while a full-matrix run held them

`build_main.yml` runs on the fork for every push; 48 Build runs were created on 13 October, and
the 36 that ran to success or failure were read job by job (`actions/runs/<id>/jobs`, and for the
one run with two attempts `actions/runs/<id>/attempts/<n>/jobs`, since the first form returns the
latest attempt's list with the earlier attempt's times on the jobs it carried over).

* **Most runs are small.** A Varka-scoped change runs 6 to 14 jobs (precondition, linters, docs
  checks, the Varka suites of catalyst and sql, the engine on two architectures, the assembly
  gate); two runs ran Spark's full module matrix, 35 jobs each: `varka-322` from 14:25 to 16:42
  (136 minutes) and `varka-312b` from 16:25 to 18:56 (151 minutes, failing two jobs, which were
  rerun from 18:56 to 20:10). Their longest job, `Build modules: sql - slow tests`, took 118 and
  128 minutes, and the two runs overlapped for 17 minutes.
* **The slots were saturated for 79 minutes of the day.** Minutes at each count of running jobs,
  from every job's start and end: 0 jobs for 225 minutes, 1 to 5 jobs for 370, 6 to 10 for 110,
  11 to 19 for 89, and 20 (the account's limit, GitHub Actions limits: "Free: 20 jobs running at
  once") for 79, all of them between 14:44 and 17:16, while one or both full-matrix runs held
  the slots.
* **Job waits came from those runs.** Of 367 jobs, the median waited 2 s from creation to start
  and the 90th percentile 6 min; the long waits, up to 49 minutes (`varka-bg-processes`'
  `Precompile Spark`), were smaller runs queued behind `varka-322`'s 35 jobs. This is
  the "queue sat for up to fifty minutes" of `m7/PLAN.md` 9.2.
* **One Build at a time would have cost the day almost nine hours.** The 36 runs, 37 attempts,
  took 1,283 run-minutes between 06:54 and 21:27. Run strictly one after another, in the order
  they started, the last would have finished at 06:12 the next morning.
* **`ubuntu-slim` counts against the twenty.** During the saturated window the `Run Spark UI
  tests` jobs, which run on `ubuntu-slim`, waited 19 and 27 minutes. So a job that only waits
  still holds a slot.

So the rule 227 enforced, one Build at a time, is wrong for today's mix: the harm is a
full-matrix run taking every slot, and two of them interleaving. **Scoped runs must never wait;
a full-matrix run must not start while another full-matrix run is in flight; and a full-matrix
run should leave slots for the small runs beside it.**

### 2.3 The laptop queue has not run a rerun since 3 October

`dev/varka_ci_queue.sh run` dispatches `update_build_status.yml` after every rerun it completes
(`VARKA-227.md` 2). The base repository's dispatch history: 69 dispatches from 29 September to 3
October, the last at 22:04 on 3 October, none since. The queue file in the clone's git directory is
empty and the runner's lock was last taken at 23:43 on 3 October. The one rerun on 13 October,
`varka-312b`'s at 18:56, reran the two jobs its first attempt had failed, the minute that attempt
ended, and no sync dispatch followed it, so it was not the queue's `run`. So for ten days the fork's
CI has run without a queue, the checks have been synced only by the late cron (2.1), and 2.2's
numbers are what no queue looks like.

### 2.4 Each repository can read the other without a credential, and write only itself

* **Reads.** The fork's runs are public: `curl https://api.github.com/repos/MaxGekk/spark/
  actions/runs/38413226994` with no token returned the run (`completed`, `success`, attempt 2),
  with `x-ratelimit-limit: 60`. Sixty an hour per IP is not enough on a hosted runner, whose
  egress address is shared, so every read in the design goes through the reading repository's own
  `GITHUB_TOKEN`, which GitHub limits to "1,000 requests per hour per repository" (Rate limits for
  the REST API). That a job's `GITHUB_TOKEN` reads another public repository is not an
  assumption: `update_build_status.yml` already reads the fork's runs with the base's token
  (`github.request('GET /repos/{owner}/{repo}/actions/runs/{run_id}', params)` with the fork's
  owner and name) and synced eight checks that way on 1 October (`VARKA-227.md` 5).
* **Writes.** The same unauthenticated client asking to rerun that run got `401`. The fork's
  default workflow permission is `write` (`actions/permissions/workflow`), but `build_main.yml`
  grants its job only `packages: write` (lines 29-30), and an explicit `permissions` block sets
  every scope it does not name to none, so a step inside the Build has no `actions: read` today.
  The base's default is `read`; `update_build_status.yml` and `notify_test_workflow.yml` ask for
  `checks: write` explicitly.
* **`notify_test_workflow.yml` runs on `pull_request_target`** (line 27), on `opened`,
  `reopened` and `synchronize`: every push to a pull request starts a job on the base with the
  base's write token, at the moment the fork's Build starts. That is the event the check sync can
  hang on instead of a cron. It must never check out the pull request's code, and does not.
* **Events from a `GITHUB_TOKEN` start no workflows**, "with the exception of `workflow_dispatch`
  and `repository_dispatch`" (Events that trigger workflows). `m7/PLAN.md` 2.4's queue would rerun
  with the fork's token and then wait to hear the rerun end; whether a token-made rerun's completion
  fires `workflow_run` is not documented, and section 3 does not depend on it.

### 2.5 Master: nothing runs after a merge, and the weekly run does not run what it says

`build_main.yml` skips pushes to `master` on any repository but apache/spark (lines 34-36), and
`vecbricks/varka` is itself a fork of apache/spark (`repos/vecbricks/varka`: `fork: true`), so
641 of its 642 Build runs are skipped pushes to `master` (the other, a branch pushed there once,
was cancelled). 23 pull requests merged on 13 October and 8 to 29 a day over the week before;
none got a run of the merged head. The weekly matrix
(`varka-weekly-matrix.yml`, on the base) did run on 11 October, but ran 6 of its 20 jobs, skipped
the Varka suites, the engine, the assembly gate and every `Build modules` job, and failed the Java
linter (`InvalidJavadocPosition`), as it did on 27 September and 4 October. It passes no `jobs`
input, so its precondition decides from a diff (`build_and_test.yml` lines 131-134), and on
`master` the pull-request diff it computes is, by every sign, empty; the job log has expired, so
the first PR of section 8 confirms the cause before it relies on it (confirmed from the log, which
had not expired, in 2.9). `build_java25.yml` shows the
form that runs what it names: an explicit `jobs` JSON, which the precondition takes verbatim
(lines 109, 332).

The base repository has runner slots of its own: `vecbricks` is an organization on the free plan
(`orgs/vecbricks`), and the twenty-job limit is per account, so work placed on the base does not
compete with pull requests' Builds on `MaxGekk/spark`. On 13 October the base ran the two fuzz
jobs and three sync passes.

### 2.6 Benchmarks on GitHub-hosted runners: what the pool gives and how long a class takes

`benchmark.yml` has run on the fork 133 times. The 40 latest successful runs, 25 September to 12
October, one job each (`actions/workflows/benchmark.yml/runs`, then each job's `Runner CPU`
annotation and its `Run benchmarks` step, which includes the `Test/package` build):

| runner CPU | draws | `dev/varka_datapath.sh` 512:256 (`sql/varka/HARDWARE.md`) |
| --- | ---: | --- |
| AMD EPYC 7763 (Zen 3) | 18 | 1.00, no AVX-512 |
| AMD EPYC 9V45 (Zen 5) | 9 | 1.99, full width |
| AMD EPYC 9V74 (Zen 4) | 8 | 0.91 to 1.00 |
| Intel Xeon Platinum 8370C | 3 | not in the table |
| Intel Xeon Platinum 8573C | 2 | 1.34 to 1.36 |

The 9V45 came up 9 times in 40, against "one draw in eighteen" in HARDWARE.md's survey of 11
September; the pool moves, which is why the probe runs on the measuring job itself
(`dev/varka_datapath.sh`, header: "a gate in one GitHub job says nothing about the machine
another job gets").

| class | step minutes per run, on runners |
| --- | --- |
| `CachedTableWidthBenchmark` | 8, 13 |
| `CodegenFallbackCostBenchmark` | 11, 14, 17 |
| `VarkaRangeFilterBenchmark` | 14 |
| `CaseWhenCodegenBenchmark` | 13 to 31 (16 runs) |
| `VarkaSizeLadderBenchmark` | 19 to 20 (5 runs) |
| `VarkaColdStartBenchmark` | 41, 42 |
| `VarkaWideKernelBenchmark` | 11, 88, 103, 189 |

Four facts follow for a pair on a runner. **Both sides in one job**: the pair's alternation is
the control only on one machine. **The six-hour job cap** ("Each job in a workflow can run for up
to 6 hours", Actions limits): a pair is two builds plus at least three rounds of two class runs
(`dev/varka_bench_pair.sh`, `--rounds`, early stop after three), so a twenty-minute class fits at
five rounds and `VarkaWideKernelBenchmark` does not fit at all. **The canary and the load check
refuse a runner**: `dev/varka_bench_regen.sh` stops when `dev/varka_canary/baseline-<host>.txt`
is missing (only `baseline-aqua.txt` exists) or the load is above 1.0, so a runner pair runs with
`--force`, which the pair script passes through. **The merge base comes from `origin`**:
`dev/varka_bench_pair.sh` fetches `origin master`, which on a fork checkout is `MaxGekk/spark`'s
master of August; on a base checkout it is `vecbricks/varka`'s, the right one.

### 2.7 Step times available today, for the first budget

* **CI, the Varka jobs of 13 October** (21 runs of each suite job; minutes, min / median / max):
  `Varka suites: catalyst` 12 / 23 / 43, of which the suites step 8 / 14 / 22 and the one-option
  step 3 / 6 / 23; `Varka suites: sql` 18 / 26 / 54, suites 12 / 16 / 19, option 5 / 7 / 34;
  `Varka assembly gate` 5 / 7 / 8; `Varka bench drivers` 2 / 2 / 3; the engine jobs 1 to 2; docs
  checks under 1.
* **CI, the nightly** (`varka-fuzz.yml`, 12 October, seconds): compile 222, IR fuzzer 404,
  composition fuzzer 43, variants to size 4 514; second job compile 299, every tree to size 5
  2,807, size 4 at 128 bits 124. (The 13 October run failed in its IR fuzzer step; that failure
  is not this row's.)
* **The laptop gate** writes `<step>.secs` under `target/varka-gate/` (`dev/varka_gate.sh:127`);
  sixteen worktrees still hold theirs. The `wide` step: 488 to 498 s on 5 and 6 October, 650 to
  848 s on 12 October (full gates in `varka-278` to `varka-311`); `doc` 207 to 304 s, `lint` 163
  to 280 s, `sweep` 185 to 387 s.
* **The laptop nightly** prints its step times only in its summary (`dev/varka_nightly.sh:151`)
  and keeps no file of them; no nightly log newer than 26 September survives. Its golden-file step
  is recorded at 21 to 33 minutes per arm in `m7/PLAN.md` 9.2.

The spread is the fact the budget has to live with: one day's catalyst suites step ranges 2.8
times from fastest to slowest across runners, and the laptop gate's `wide` step moved 15% between
two gates of the same day. **A 5% rule read against one run is noise.** The budget records
medians and spreads; a pull request's disclosure is read against the median, and an automatic
flag can only name a step beyond the spread.

### 2.8 What the check rejected

1. **A cron-driven queue** (`m7/PLAN.md` 2.4): the cron runs hours late (2.1).
2. **One Build at a time** (227): almost nine hours on 13 October's load (2.2).
3. **A dispatcher that cancels and reruns.** It needs a waiter that hears the rerun end, which
   with a `GITHUB_TOKEN` rerun is either an undocumented `workflow_run` or a job holding a slot;
   and a cancelled attempt is what made the PR's check misleading in 227's first friction.
4. **A workflow file on the fork's `master`** for the same reason as 7.3: it must be carried
   across every sync of that branch with apache/spark, and nothing in section 3 needs it.
5. **GitHub's own `concurrency` queue.** Since `queue: max` a group can hold up to 100 pending
   runs in order (Workflow syntax, `concurrency`), but a group is decided before any job runs,
   from contexts such as the ref, so it cannot tell a full-matrix run from a scoped one; it would
   serialize every run, which is rejection 2.
6. **Pairs on the fork.** A pair holds one fork slot for hours, and its merge base would be
   August's (2.6).

### 2.9 255b's admission check, against the code of 14 October

Read on 14 October 2026 between 13:30 and 14:30 CEST, at `90b8871b705`, with the owner's `gh`
for reads; nothing was dispatched. It found the plan's master run (3.3) right in outline and five
things it did not know.

**#806 made the master run the only build of the merged tree.** #806 merged today and changed
`.github/actions/checkout-and-sync`: outside apache/spark every job now checks out the triggering
commit itself (`github.sha`, full history) instead of merging the branch onto apache/spark's
master, and sets `VARKA_DIFF_HEAD` to that commit and `VARKA_DIFF_BASE` to its merge base with
`vecbricks/varka`'s master, from which the precondition (`dev/is-changed.py`, `dev/varka_scope.py`)
scopes the jobs to what the branch changed (`working-in-this-repo.md`, "Upstream enters only
through a sync pull request"). A pull request's Build is therefore a test of the branch as it was
cut, not of the branch merged with today's master. Two pull requests each green on its own can
break master together - one renames a helper the other's new test calls, or both move a pinned
file such as `sql/varka/emitted_bytes.json` - and no run builds the result. 3.8's "deliberately
unchanged" checkout-and-sync merge with apache/spark no longer exists; the master run replaces the
coverage it gave, against the right master.

**2.5's reading of the weekly run's skip, confirmed.** Its job log had not expired after all
(`gh run view 38118793414 -R vecbricks/varka --log`, the run of 11 October). Its precondition
printed `change detection base: c7e7cc85c0f... (merge base with ... vecbricks/varka.git master)`,
which is the run's own head, then `changed files vs base: 0` and `varka change scope: spark`, and
chose `"build": "false"` and every `varka-*` key false, with `lint`, `java25`, `docs`, `buf` and
`ui` true: the six jobs it ran. The cause holds under #806's action too: on a run of master, the
merge base of the head with `vecbricks/varka`'s master is the head, so any master run that leaves
the precondition to decide selects nothing to test.

**An explicit `jobs` alone would still test nothing in the module jobs.** `Build modules` runs
`dev/run-tests --modules ...`, and `dev/run-tests.py` (lines 507-544) narrows the modules to those
the diff from `VARKA_DIFF_BASE` to `VARKA_DIFF_HEAD` touches whenever `APACHE_SPARK_REF` is set,
which the action sets on every run outside apache/spark. On master that diff is empty, so each
module job would print "There are no modules to test, exiting without testing." and pass. The
`pyspark`, `sparkr` and `docker-integration-tests` jobs call the same script. So the weekly
matrix, given its `jobs`, would have turned green while testing nothing; `dev/run-tests.py` must
read a base equal to the head as "no change to measure", test the modules it was given, and say
so. A pull request is unaffected: its base is never its head, and a branch pushed with no commits
of its own has no module job, since its precondition selects none.

**The option step would pass every failure on master.** The Varka suite jobs' second step
(`dev/varka_matrix_ci.sh`) reruns a failing option configuration on `VARKA_DIFF_BASE` and passes,
with a warning, when every failure is also there. On master that base is the head, so it would
rebuild the same tree, find the same failures and pass: no option failure could ever turn the
master run red. Its pick (`dev/varka_matrix_pick.py`) hashes the branch name, `master`, so it
would also draw the same configuration on every run. When the base is the head the step has no
change to blame: it fails on any failure, and it keys the pick on the commit instead, so that
successive master runs walk the configuration list while a rerun of one commit draws the same
configuration. The emitted-bytes step beside it prints "unchanged since <head>" in the same case;
it is right, if uninformative, and is left as it is. *(Corrected in 9.2: it says the oracle
"proves this change byte-identical", which on a merged tree is false, so it now says a run of
master has nothing to compare with.)*

**The Java linter's failure is a suppression keyed on the checkout's directory name.** The six
errors of 11 October (`InvalidJavadocPosition` in `CollationSupport.java`, `GeometryModel.java`,
`HadoopLineRecordReader.java`, `CLIService.java`, `HiveSessionProxy.java`,
`HiveSessionImpl.java`) are all in Spark's own sources, which `dev/checkstyle-suppressions.xml`
exempts from the rule with `files="^(?!.*[\\/]varka[\\/]).*$"`: suppress where no path segment is
`varka`. Checkstyle matches it against the absolute path, and on `vecbricks/varka` the runner's
workspace is `/__w/varka/varka/`, so every file has such a segment and nothing is exempt; on the
fork the workspace is `/__w/spark/spark/` and the rule works, which is why only the base's lint
was red. Reproduced on the laptop with checkstyle 11.0.1 over copies of the six files and a
planted misplaced comment in a `varka` package: under a root `.../varka/varka/` the six and the
plant are reported, under `.../plain/spark/` only the plant. A clone in a directory named `varka`
would fail the same way. The fix anchors the segment under the Java source root's package path,
`java/org/apache/spark/.../varka/`, where every Varka Java source lives; the six files are left
alone, being Spark's. *(Corrected in 9.2: 44 Varka classes live outside such a package, among them
`VarkaKernelEvaluator` and `PartialVarkaProjection`, and were exempt under the old pattern as
well; the pattern now also covers a class whose name holds `Varka`.)*

**The called workflow's concurrency does not cancel master runs.** `build_and_test.yml` declares
`concurrency: build-test-<calling workflow>-<ref>` with `cancel-in-progress: true`. A calling
workflow with a workflow-level group of its own holds its second run pending until the first
completes, so two calls never overlap in the inner group and the inner cancel cannot fire;
`varka-master.yml`'s group therefore decides, and no line of `build_and_test.yml` changes for it.
(Two of its lines changed for other reasons; 9.2.)

## 3. The design

### 3.1 The queue becomes an admission job inside the Build

The Build itself decides whether it may fan out, so nothing outside it has to cancel, rerun or
wait for it. `build_and_test.yml` gains one job, `admit`, on `ubuntu-slim`:

* **Which runs it gates.** `admit` needs `precondition` and carries `Precompile Spark`'s own
  condition (`build_and_test.yml` 648-658: `build`, `pyspark`, `pyspark-pandas`, `pyspark-periodic`,
  `sparkr`, `docker-integration-tests`, `k8s-integration-tests`, `oidc-e2e` or `tpcds-1g` true), and
  `precompile` gains `needs: admit` and `needs.admit.result != 'failure'` in its condition. So
  "full-matrix" means exactly the runs that precompile Spark and fan out behind it, the two
  conditions sit side by side in one file, and a run whose `admit` is skipped - every Varka-scoped
  and documents-only run, whose scopes set `build` and the Python and Docker gates false (230-245),
  and whose 13 October runs read here skipped `Precompile Spark` - is not touched; its light jobs
  never needed `precompile`. A run that precompiles without the module matrix, as
  `varka-bg-processes` did on 13 October (13 jobs), counts as full: the precompile and its
  dependants are what take the slots. On apache/spark and on vecbricks/varka the job passes at once.
* **The marker is the job itself.** An `admit` job that is `in_progress` is a full run waiting;
  one that succeeded, in a run still in progress, is a full run that has fanned out. No
  annotation, output or copy of the condition is read from another run.
* **The rule.** `admit` lists the fork's Build runs that are `queued` or `in_progress` and reads
  each one's jobs (`per_page=100`, matched by the `admit` job's name as a suffix, since the API
  prefixes the caller's job name). It waits while any other run has a succeeded `admit`, and while
  any other run's `admit` started waiting before its own; otherwise it ends and the fan-out
  starts. The order is the `admit` jobs' own `started_at`, which a rerun renews, so a rerun of an
  old run queues behind the full runs already waiting or running instead of passing them on its
  first attempt's creation time. It polls every 120 seconds. A run whose precondition is still
  choosing has no `admit` job yet and takes its place when it has one. First come, first served
  is the order GitHub's own queue keeps; 227's pull-request-number order assumed merges in number
  order, which 13 October's 23 merges did not keep.
* **`ci-hold` takes a pull request out.** `admit` finds its pull request on the base
  (`pulls?head=MaxGekk:<branch>`) and, when it carries the label, fails with "held by ci-hold",
  so `precompile` and everything behind it skip. Removing the label and rerunning the failed jobs
  resumes it, and the rerun's `admit` waits its turn by the rule above.
* **A re-push needs nothing.** `build_and_test.yml`'s concurrency group per ref with
  `cancel-in-progress: true` (lines 75-77) cancels the waiting run when the branch moves, as it
  does today.
* **The wait has a deadline**: 330 minutes, inside the 360-minute job cap, after which `admit`
  fails with the run it waited on named.
* **A full-matrix run leaves room.** The `Build modules` matrix's `max-parallel` (line 412) is
  20, so one full run can take every slot (2.2). On a fork it becomes 12, keeping eight slots for
  the small runs beside it; on apache/spark it stays 20. `strategy` may read the `github` context
  (Contexts, availability table), so this is one expression on the repository name.

What this costs and gains, against the laptop queue: a waiting full run holds one `ubuntu-slim`
slot while it waits (2.2), and a run waits only behind another full run, which on 13 October would
have been `varka-312b` for the 17 minutes it overlapped `varka-322`; nothing runs on the laptop;
the run is `in_progress` while it waits, so the pull request's check says "in progress" rather
than "cancelled", which removes 227's first friction; and 211's docs-only verdict keeping is
unnecessary, since a documents-only push already runs only six short jobs (13 October's
documents-only runs, 2.2; VARKA-160). The job's token needs `actions: read`, added beside
`packages: write` in `build_main.yml`'s job, the only change to that file. The logic is a stdlib
Python script, `dev/varka_ci_admit.py`, so it can be run and tested off CI.

The job polls with the fork's token, which every job of the fork shares at 1,000 requests an hour:
one run listing and one job listing per run in flight per poll, under 200 requests an hour per
waiting run with five runs in flight.

### 3.2 The check sync: on the base, after the check exists

`update_build_status.yml` keeps its pass and gains a loop and a trigger:

* **Trigger**: `workflow_run` on the completion of `notify_test_workflow.yml` ("On pull request
  update"), which runs on every push to a pull request (2.4) and creates the `Build` check; the
  sync then starts with the check in place, rather than racing notify on the same
  `pull_request_target` event and finding no check (which its backfill would answer with a second
  one). `workflow_run` needs the file on the base's default branch, where it is, and notify is
  started by a person's push, not by a token, so the event fires. The cron stays as a backstop, and
  `workflow_dispatch` stays.
* **Loop**: the pass repeats every 120 seconds while any open pull request's fork run is not
  completed, read from the run itself rather than from the check, so a rerun that reopens a
  completed run keeps or restarts the loop's work. A pass reads every open pull request's run as
  today's does and patches a check only when its status or conclusion differs from the run's;
  today's pass patches every completed check on every pass.
* **One loop at a time, and no gap at the cap**: `concurrency: {group: update-build-status,
  cancel-in-progress: false}`, so a trigger during a loop leaves one run pending, and a loop
  already running reads every open pull request on each pass. At 345 minutes, if any run is still
  in flight, the loop dispatches the workflow again before it ends (`workflow_dispatch` is the
  exception to the rule that a `GITHUB_TOKEN`'s events start nothing, 2.4), so a full run that
  waited and then ran past the cap keeps its sync.
* **A rerun with no push**: rerunning a fork run fires nothing on the base. While a loop is alive
  it sees the rerun; when none is, the person who reruns dispatches the sync
  (`gh workflow run update_build_status.yml -R vecbricks/varka`), as the laptop script did after
  its reruns (`VARKA-227.md` 2); the backstop cron catches the rest, hours late.

Requests: one pull-request listing per pass plus about three per pull request with a run in
flight; with four in flight, about 400 an hour against the base's 1,000. The sync no longer
depends on anything dispatching it after a push, so `dev/varka_ci_queue.sh`'s dispatch goes with
the script (3.7).

*Corrected 10 November 2026 (VARKA-589): the trigger above, and what 2.4, 3.8, section 4's
`update_build_status.yml` row and risk 7.4 say of `notify_test_workflow.yml`, no longer hold.
apache/spark deleted that workflow (SPARK-60578) because the ASF is turning `pull_request_target`
off and GitHub changes its default on 2 December 2026, and upstream's updater now creates a missing
`Build` check itself on its cron (the backfill, SPARK-58660). Here the cron runs hours late (2.1),
so the push signal stays: notify is deleted, and `varka-pr-update.yml`, under notify's workflow name
"On pull request update", runs on `pull_request`, which for a fork's pull request gets a read-only
token and no secrets. It checks nothing out, writes nothing, and waits up to three minutes for the
fork's `build_main.yml` run of the head commit to be listed, so the updater's first pass finds the
run instead of writing an `action_required` check. Its completion starts this workflow on
`workflow_run`, which runs the default branch's file with the base's write token and reads nothing
from the triggering run, so the sync starts after a push as before; the difference is that the
updater's backfill now creates the `Build` check, and its summary links the fork's Actions run
rather than its `Run / Check changes` check. A pull request whose merge with the base conflicts gets
no `pull_request` run, and the updater skips it anyway (`dirty` is not in its ready states). A
first-time contributor's `pull_request` run waits for a maintainer's approval (the repository's
fork-run policy is `first_time_contributors`), which `pull_request_target` did not; the cron covers
that case.*

### 3.3 Master's head after each merge, on the base

A workflow on `vecbricks/varka`, `varka-master.yml`, on `push` to `master` and `workflow_dispatch`,
calls `build_and_test.yml` with an explicit `jobs` JSON: the Varka set (`varka-scoped`,
`varka-engine`, `varka-bench`, `varka-asm`, `varka-docs`), the linters and the Java 25 Maven build
(`java25`), which is the only place the shading and incubator-module failures of
`sql/varka/AGENTS.md`'s house rules appear. About ten jobs, about thirty minutes (2.7), on the
base's own slots (2.5). Its concurrency group keeps one run in flight and replaces a pending run
with the newest (GitHub's default for a group, Workflow syntax, `concurrency`), so a burst of merges
builds the last head of the burst; on 13 October's 23 merges that is a run every half hour while
merges come. A failure is visible on the commit and the workflow's page; the CI-failure
investigation in `CLAUDE.md` applies unchanged.

The Varka suite jobs' second step runs one emit-option configuration, chosen from the option a
diff names or a hash of the branch name, and blames only failures the merge base does not share
(`sql/varka/AGENTS.md`, "Measurements"). On a push to `master` there is no pull-request diff and
the merge base is the head itself, so 255b reads what `dev/varka_scoped_suites.sh` does with those
inputs on `master` and gives the master run a fixed choice - the defaults, or a rotation by date -
before its first run rather than after its first red one.

`varka-weekly-matrix.yml` passes an explicit `jobs` JSON too, so that it runs the matrix its
comment promises: Spark's module set as `build_java25.yml` names it, but with
`k8s-integration-tests` and `oidc-e2e` false (this fork switches the Kubernetes job off because it
cannot build its image, `build_and_test.yml` 288-296, and an explicit JSON is taken as it is,
bypassing that line), and with `lint`, `java25`, `docs` and the five `varka-*` keys added, which
`build_java25.yml`'s JSON lacks; and the
`InvalidJavadocPosition` finding that has failed it three Sundays is fixed in the same pull
request, since the master run's linter would fail on it as well.

**255b's form, after its admission check (2.9).** What a master run tests, and how often:

* **A fixed job set, not the commits since the previous run.** The run names `varka-scoped`,
  `varka-engine`, `varka-bench`, `varka-asm`, `varka-docs`, `lint` and `java25`: eleven jobs with
  the precondition and the lint image, every lint step (the flags `lint-spark`, `lint-code` and
  `lint-python` are left out, which the lint job reads as true), and no `Precompile Spark`, `Build
  modules`, `docs`, `buf` or `ui`. Scoping to the commits since the previous master run was the
  alternative and loses on three counts. It needs that run's head from the API, since under
  coalescing a push's `before` may never have been built. A merge that touches a Spark source
  would start the full matrix, 35 jobs and two to two and a half hours (2.2), on the base's twenty
  slots beside the benchmark jobs, of which seven were in flight at 14:26 today. And what a
  merged tree adds over its pull requests is the interaction between them, which for Varka's pull
  requests is in Varka's code, its pinned files and its suites; each pull request's own scope,
  Spark modules included, already ran on its branch. An interaction inside Spark's modules is the
  weekly matrix's to find, within a week.
* **One run at a time, the newest waiting.** `concurrency: {group: varka-master,
  cancel-in-progress: false}`. A push during a run leaves one pending run, and a later push
  replaces it, so a burst of merges builds the head the run started on and the burst's last head.
  Cancelling in progress was rejected: at several merges an hour against a run of half an hour or
  more, every run would be cancelled by the next merge and none would finish while merges came.
* **On failure.** A red master run is visible on the commit and on the workflow's page, and the
  next merge's run says whether it was fixed. The run is read as `CLAUDE.md`'s CI-failure steps
  describe, against `vecbricks/varka` rather than the fork.

The weekly matrix's explicit `jobs` turns on the module jobs, and `dev/run-tests.py` then tests
every module each job names (2.9), so on Sunday they test something for the first time on this
repository's master.

### 3.4 New tests rerun several times, and a fix's new test failing on the base

Both in the Varka suite jobs of a pull request's Build, after the suites:

* **Reruns.** `dev/varka_new_tests.py` lists the tests the pull request adds, from the diff
  against its merge base: a new `test("...")` in a ScalaTest suite, a new `@DisplayName("...")` in
  a JUnit class under `@VarkaSuite`. The step reruns each through sbt's `-z` filter (JUnit by its
  display name) five times in the suite's JVM, and fails on any failure, naming the run. ClickHouse
  runs fifty (`m7/READING.md` 9); five is what fits the Varka job's time, and the count is an
  input. A new test that is already a fuzz or sweep under a seed is rerun with five other seeds,
  which is what a rerun is for there.
* **A fix's test fails on the base.** For a pull request labelled `fix`, the step builds the merge
  base's main sources with the pull request's test sources over them: `git checkout <base> --`
  every changed path outside `src/test`, and deletes the main files the pull request adds (`git
  diff --diff-filter=A`), which a checkout of the base cannot remove and which may hold the fix.
  It then runs the new tests and passes when each fails, and fails, naming the test, when one
  passes on the base. A fix whose test passes before the fix has not shown the bug. When the
  overlay does not compile - a test helper using an API the fix adds - nothing is shown either
  way, so the step reports "not shown: the tests need the fix's main code" as a notice and passes;
  the count of such pull requests is part of section 6, and if it is most of them the check needs
  a finer overlay or is dropped.

These two are the most likely to outgrow the row: the test listing has edge cases (a renamed test,
a test moved between files, a parameterized title), and the base build adds a compile. If either
needs more than section 8's last pull request, it becomes a row of its own, and section 9 says
so.

### 3.5 Benchmarks on GitHub-hosted runners (the review's point a)

`benchmark.yml` gains a pair mode and runs on the base repository by default.

* **Inputs**: `pr` (a pull request number; the job checks out `refs/pull/<pr>/head`, which the
  base keeps for every pull request, and blank for the dispatched ref as today), `pair` (true runs
  `dev/varka_bench_pair.sh` instead of the harness), `rounds` (default 3), and the existing
  `class`, `sections`, `expected-cpu` and `extra-java-options`. A list of classes in `class` gives
  one matrix job per class, `max-parallel` 6.
* **One job per pair**: the job runs `dev/varka_datapath.sh --require any --github` first,
  writing the CPU, the control and the 512:256 ratio to the job summary, then
  `dev/varka_bench_pair.sh <module> <class> --base <merge base> --rounds <n> --force`, both sides
  on that machine. The merge base is computed against the base's `master`, which a base checkout
  has as `origin` (2.6). The pair's verdict, its per-row table and both sides' files are the job's
  artifact and its summary.
* **Results files** keep today's path, `benchmark.yml` without `pair`, with `pr` added: the
  regenerated files come back as the artifact and the author commits them; `create-commit` cannot
  push from the base to a fork branch without a credential, so it stays for the fork's own
  dispatches. The file's provenance names the runner's CPU and the probe's ratio, as Spark's
  files name theirs (`varka-benchmarks-on-github-runners` in the project's record); a file that
  needs a full-width machine sets `expected-cpu` and the job stops at the probe on any other.
* **Why the base**: its slots are its own (2.5), so ten pairs of hours each do not take ten of the
  twenty slots the pull requests' Builds share; and its `origin` is the right merge base. The fork
  keeps `benchmark.yml` as it is, dispatchable at a branch's ref.
* **What does not fit**: a class whose single run exceeds about 50 minutes on a runner cannot pair
  at three rounds inside six hours. `VarkaWideKernelBenchmark` (88 to 189 minutes, 2.6) is the
  one known. A case subset per job is not available as things stand: `VarkaBenchmarkBase`
  refuses to run part of a benchmark while results files are written
  (`VarkaBenchmarkBase.java:64-70`), and the pair reads the results file each round writes
  (`dev/varka_bench_regen.sh:198`). So its pair stays a laptop pair unless 255a teaches the pair to
  read the harness's printed table for a subset, and section 9 records which it did.

The three waiting jobs are the first users: 322a's ten pairs, 325's two results files and 322a-2's
pair, each dispatched with `pr` set.

### 3.6 The step-time budget (the review's point b)

* **The file**: `sql/varka/ci/step-budget.tsv`, one line per step: where it runs (`ci` or
  `laptop`), the job and step names, the runner CPU (for `ci`), the number of runs read, the
  median and the 90th percentile in seconds, and the date range. The first version is 2.7's times,
  as the review asked ("the first budget is the times of 13 October").
* **The tool**: `dev/varka_ci_times.py`. `collect` reads a range of runs' jobs and steps from the
  API - master's runs of 3.3 and `varka-fuzz.yml`'s by default, which are the only runs of a fixed
  tree - and writes the file; `compare <run>` prints a pull request run's steps against it, by
  the same runner CPU where the budget has one, marking a step above the budget's 90th percentile
  by more than 5%; `gate <dir>` does the same for a laptop gate's `.secs` files.
* **Where it shows**: the check sync (3.2) writes `compare`'s table into the `Build` check's
  summary when the run completes, so every pull request shows its step times against the budget
  without anyone running anything. The mark is a disclosure prompt, not a failure, because of
  2.7's spread.
* **The nightly writes its step times**: `dev/varka_nightly.sh` writes `<step>.secs` beside each
  log, as the gate does, so `gate <dir>` reads it too.
* **Kept current**: the budget is regenerated from master's runs by the closing PR of each
  milestone and whenever a PR's description says it moved a step; the file's history is the
  record of what the tests cost.

### 3.7 The laptop queue retires

`dev/varka_ci_queue.sh` is deleted in the pull request after the admission job has been proven
(section 8), with `sql/varka/skills/working-in-this-repo.md`'s two queue sections rewritten in
the past tense, and its `update_build_status.yml` dispatch comment removed.

### 3.8 Deliberately unchanged

* **The precondition's scope rules** (`dev/varka_scope.py`, VARKA-160): the admission job reads
  their verdict and does not change it.
* **The checkout-and-sync merge with apache/spark** before every build
  (`working-in-this-repo.md`, "A red Build here may be failing on code this repository does not
  contain"). *Superseded on 14 October by #806, which removed that merge; 2.9
  says what the master run takes over from it.*
* **`notify_test_workflow.yml`**: it still creates the check; 3.2 only keeps it current.
  *Superseded on 10 November 2026 by VARKA-589, which deleted it; 3.2's correction says what
  creates the check now.*
* **The fork's `master`** and its apache/spark schedulers.
* **`varka-surface-benchmark.yml`**, the published-number path with its own datapath gate.
* **Row 287's lane split of the Varka suite jobs**, measured on the runners in its own row.

### 3.9 Whether (a) and (b) belong here

Both do, for reasons of mechanism rather than convenience. (a) moves benchmark jobs to the base
repository's slots for the same reason 3.3 moves master's run there, and it is the same split of
work between the two accounts that 3.1 relies on to keep the fork's slots for pull requests. (b)
has one sound source of step times - master's per-merge runs of 3.3, the only CI runs of a fixed
tree after the fuzz nightly - and one place to show them on every pull request, the check sync of
3.2. Either built elsewhere would rebuild those parts. Each is its own pull request (section 8),
so either could still be cut to a row of its own without touching the others; 3.4 is the part
most likely to be.

### 3.10 Registered op counts

None: no emitted code changes.

## 4. Files

| file | what |
| --- | --- |
| `.github/workflows/build_and_test.yml` | the `admit` job and `precompile`'s need of it; `max-parallel` 12 on forks for `Build modules` |
| `.github/workflows/build_main.yml` | `actions: read` beside `packages: write` |
| `dev/varka_ci_admit.py` | the admission decision: scoped or full, older full runs in flight, `ci-hold` |
| `.github/workflows/update_build_status.yml` | `workflow_run` on notify, the loop, one loop at a time, the re-dispatch at the cap, patch only on change, the step-time summary |
| `.github/workflows/varka-master.yml` | new: master's head after each merge, explicit `jobs` |
| `.github/workflows/varka-weekly-matrix.yml` | explicit `jobs` |
| `dev/checkstyle-suppressions.xml` (was "the file the Java linter names", 2.9) | the `InvalidJavadocPosition` exemption anchored under the package path |
| `dev/run-tests.py` (255b, 2.9) | a diff base equal to the head tests the modules given |
| `dev/varka_matrix_ci.sh` (255b, 2.9) | on a run of master: no base comparison, the pick keyed on the commit |
| `.github/workflows/build_and_test.yml` (255b, 9.2) | on a run of master: the `docs` job builds every API doc; the emitted-bytes step says there is nothing to compare |
| `dev/varka_new_tests.py` | the new tests of a diff; the base-overlay check for `fix` |
| `.github/workflows/build_and_test.yml` (Varka suite jobs) | the rerun and fails-on-base steps |
| `.github/workflows/benchmark.yml` | `pr`, `pair`, `rounds`, the probe first, a class list as a matrix |
| `sql/varka/ci/step-budget.tsv` | new: the budget |
| `dev/varka_ci_times.py` | new: `collect`, `compare`, `gate` |
| `dev/varka_nightly.sh` | `<step>.secs` per step |
| `dev/varka_ci_queue.sh` | deleted after 3.1 is proven |
| `sql/varka/skills/working-in-this-repo.md`, `benchmarking.md`, `SKILLS.md` | the queue's sections in the past tense; the runner pair; the budget |
| `sql/varka/AGENTS.md` | "Pull requests": the budget's file and tool named where the rule is |

## 5. Tests, and what each is for

1. **`dev/varka_ci_admit.py` over recorded API responses** (unit tests in Python, run by the docs
   job): a full run with another run's `admit` succeeded and that run in flight waits; a full run
   whose `admit` started first passes ahead of one that started later; a rerun of an old run
   (renewed `admit` start) waits behind a run already waiting; a run with no `admit` job yet does
   not block; a held pull request fails; a completed run blocks nothing; a job listing over one
   page is read whole. Without these, the only test of the job is a live day of CI.
2. **The job on the fork, live**: a scoped push skips `admit` and `precompile`; two
   full-matrix pushes (a workflow-file change each) a minute apart: the second's log says it
   waited on the first's run id and its fan-out starts within one poll of the first's end.
3. **`max-parallel` on the fork**: the full run's job list never shows more than 12 `Build
   modules` jobs running at once, read from the jobs API.
4. **The check sync**: a push to a test pull request; its `Build` check goes to `in_progress` and
   to the run's conclusion within one poll interval of the fork run's, read from the check-runs
   API against the run's `updated_at`. And a pass on unchanged checks issues no `PATCH` (the
   job's log counts them).
5. **The master run**: the merge of section 8's second PR runs `varka-master.yml` with the jobs
   it names and no `Build modules` job; the weekly run on the following Sunday runs the module
   jobs.
   255b's checklist for the coordinator, from 2.9, read on `vecbricks/varka` after the merge
   (`gh run list -R vecbricks/varka --workflow varka-master.yml`, then the run's jobs and logs):
   (a) a run starts on the merge commit's push, its event `push`; (b) its jobs are `Check
   changes`, `Base image build`, `Linters, licenses, and dependencies`, `Java 25 build with
   Maven`, `Varka suites: catalyst` and `: sql`, `Varka engine (x86_64)` and `(aarch64)`, `Varka
   bench drivers`, `Varka assembly gate` and `Varka docs checks`, and every other job is skipped,
   `Precompile Spark` and `Build modules` among them; (c) the lint job's `Java linter` step passes
   on the base's `/__w/varka/varka/` path, with no `InvalidJavadocPosition` line; (d) each suite
   job's option step prints `== <module> under <configuration>: no change to measure, the merge
   base is the head; the hash of commit <sha>` and, if it fails, ends `failed on a run of the
   merge base itself, so nothing excuses it`, never `fails on the merge base too`, and two master
   runs at different commits may name different configurations; (e) two merges inside one run:
   the run in progress finishes, the first merge's pending run shows as cancelled when the second
   push supersedes it (that is the coalescing, not a failure), and the next run builds the later
   head; (f) the run's wall time, for prediction 5. On the Sunday after: the weekly run's `Build modules` jobs run, and every one's
   log reads `[info] no change to measure: the diff base <sha> is the head; testing the modules
   given` and then runs tests, never `There are no modules to test`.
6. **`dev/varka_new_tests.py` over recorded diffs**: a new ScalaTest test, a new JUnit display
   name, a renamed test (counted as new), a moved test (not new), a test whose title is an
   interpolated string (reported as unlistable, not silently dropped).
7. **The fails-on-base step, planted**: a test PR adds a test that passes on master and labels it
   `fix`; the step fails naming it. The same PR with a real one-line bug fix and its test passes.
8. **The runner pair, A/A**: `benchmark.yml` with `pair` and `--base` equal to the head on one
   short class (`VarkaRangeFilterBenchmark`): the verdict passes and its widest row change is the
   runner's own spread, recorded beside the laptop's A/A.
9. **`dev/varka_ci_times.py` over recorded runs**: `collect` reproduces 2.7's medians from the
   same runs; `compare` marks a planted 20% slower step and not a 3% one inside the spread.

No pinned fixture moves.

## 6. The measurement

After each pull request lands: the fork's job waits and run times on the next working day with at
least one full-matrix run, read the way 2.2 read 13 October; the lag from a fork run's end to its
check's update; master runs per day and their times; the three waiting benchmark jobs' times and
CPUs; the budget's spread per step after two weeks of master runs; and, over the first ten
pull requests labelled `fix`, how many the base overlay could not compile.

### 6.1 Predictions, registered before the run

Cost predictions are stated at the calibration table's observed ratio
(`skills/testing-and-debugging.md`): the table's cost lines ran 1.1 to 5 times over, the harness
overhead being what plans missed, so the estimates below carry a factor of 1.5 over the
arithmetic unless they come from a measured run.

1. On a day with a full-matrix run, no scoped run's job waits more than 10 minutes, against 49 on
   13 October; the 90th percentile job wait stays under 6 minutes (6 measured with no queue).
2. A full-matrix run capped at 12 parallel `Build modules` jobs takes at most 20% longer than an
   uncapped one on a quiet fork, since its critical path is the 118 to 128 minute `sql - slow
   tests` job (2.2's two runs).
3. A pull request's `Build` check reaches the fork run's conclusion within 4 minutes of it (one
   120 s interval and the pass, at 1.5 times), against hours today.
4. The sync loop stays under 600 requests an hour on the base on a day like 13 October (400 by
   arithmetic, at 1.5 times).
5. `varka-master.yml` runs in 30 to 45 minutes and fewer than 15 times on a day of 23 merges.
   (255b, registered before its first run: its longest job is the Java 25 Maven build, 35
   minutes in the weekly run of 11 October, or a Varka suite job at up to 54 minutes on the fork
   on 13 October (2.7), so 45 is the edge of this prediction rather than its middle.)
6. The new-test reruns add under 3 minutes to a Varka suite job on a typical pull request, and the
   fails-on-base step under 8 minutes when it runs (a compile of the base, measured at 4 to 5
   minutes in `varka-fuzz.yml`'s compile step, plus the tests).
7. A pair of a twenty-minute class at three rounds finishes in under 4 hours on a runner; the
   three waiting jobs, apart from `VarkaWideKernelBenchmark`'s pair, complete with no laptop
   time.
8. The runner A/A pair's widest row change is under 10%, the pair's threshold, on at least two of
   the pool's CPU models; if not, the runner pair's threshold is raised for that class with the
   A/A as the reason.
9. With 2.7's spread, `compare` marks between one and three steps per ten pull requests that do
   not change test code, all of them on a runner CPU the budget has few runs of.

## 7. Risks

1. **The admission job holds a slot.** A full run waiting behind another holds an `ubuntu-slim`
   slot for up to two hours; two waiting hold two. Test 2 and prediction 1 measure it; if waits
   pile up, the job can cancel its own run instead of waiting, which is 227's cancellation
   brought back for full runs only.
2. **The `admit` job's name and condition.** Another run is recognized by its `admit` job (3.1),
   and `admit` copies `Precompile Spark`'s condition; an upstream edit to that condition must be
   copied too. The two sit side by side in `build_and_test.yml`, and a test in
   `dev/varka_ci_admit.py`'s suite reads the file and fails when the two conditions differ.
3. **The sync loop's slot on the base.** One `ubuntu-slim` job for as long as a run is in flight,
   on the base's own slots, beside master runs (about ten jobs) and up to six benchmark jobs: 17 of
   20 at the busiest. A benchmark list larger than that waits in GitHub's queue, which costs time
   and nothing else.
4. **The base's workflows run with a write token.** The loop, started from notify's
   `pull_request_target` run, must never check out or execute the
   pull request's code; it reads the API only, as `notify_test_workflow.yml` does. The review of
   the PR checks that the file contains no `actions/checkout`.
5. **Runner pairs on a CPU nobody chose.** A pair's verdict is read on its own machine, with the
   probe beside it; a results file regenerated on a runner names a CPU the committed neighbours
   in other sections may not share. `sections` (VARKA-256) keeps the rest of the file as
   committed, and the provenance says which machine wrote which.
6. **The master run's first runs fail on what nothing has run since September**, the Javadoc
   position first. That is the point of the run; the second PR of section 8 fixes what its own
   first run shows before it merges.
7. **The master run shares the base's twenty slots** with `benchmark.yml` dispatches (255a),
   which take up to six jobs a dispatch and hours each: seven were in flight at 14:26 on 14
   October. Eleven master jobs beside them fit; two class lists dispatched together do not, and
   the master run then waits in GitHub's queue, late rather than lost. If it does so often, the
   benchmark lists' `max-parallel` comes down before the master set does.
8. **GitHub changes a limit.** Every limit the design leans on is quoted in section 2 with its page;
   the admission job and the loop log their request counts, so a change shows as a failure with a
   number rather than as silence.

## 8. Sequencing

Five pull requests, each green on its own; the benchmark one first because three jobs are waiting
on it and it touches nothing the others do.

1. **255a, benchmarks on runners**: `benchmark.yml`'s `pr`, `pair` and `rounds`, the probe first,
   a class list as a matrix; test 8; then the three waiting jobs dispatched (322a's pairs, 325's
   files, 322a-2's pair) and prediction 7 scored as they finish.
2. **255b, master after each merge**: `varka-master.yml`, the weekly matrix's explicit `jobs`, the
   Javadoc fix and whatever else its first run shows; test 5; predictions 5. Confirms 2.5's reading
   of the weekly run's skip before relying on it.
3. **255c, the admission job and the check sync**: 3.1 and 3.2, `dev/varka_ci_admit.py` and its
   tests; tests 1 to 4; predictions 1 to 4 over the next working day with a full-matrix run.
4. **255d, the budget**: `step-budget.tsv` seeded from 2.7, `dev/varka_ci_times.py`, the nightly's
   `.secs`, the check summary; `dev/varka_ci_queue.sh` deleted and the skills rewritten; test 9;
   prediction 9 after ten pull requests.
5. **255e, new tests rerun and a fix's test failing on the base**: 3.4; tests 6 and 7; prediction
   6. If it grows past one pull request, a row of its own.

**Owner actions**, none of which an agent may take:

1. **Accept 3.1 and 3.2 in place of `m7/PLAN.md` 2.4's cron dispatcher**, on 2.1 and 2.2's numbers.
   Done: the owner accepted them on 13 October 2026.
2. **Create the labels `ci-hold` and `fix` on `vecbricks/varka`** before 255c and 255e merge.
   Done on 13 October 2026, on the owner's request.
3. **Stop starting `dev/varka_ci_queue.sh run`, `hold` and `drop`** once 255c merges; a held
   run would be cancelled by the script and never rerun by anything.
4. **Nothing to enable in the fork's Actions tab** (2.1), and nothing to decide on 7.3: no file
   goes on the fork's `master`.

## 9. Outcome

### 9.1 255a: benchmark pairs and results files on GitHub-hosted runners (14 October 2026)

**What landed.** `benchmark.yml` gains five inputs (`pr`, `pair`, `rounds`; `base`, which test
8 needed for an A/A run and which also takes a commit or a branch of `vecbricks/varka`; and
`threshold`, which the second A/A below asked for), fifteen in all; GitHub accepted the dispatch
with fourteen, so the old limit of ten no longer binds. A list in
`class` is one job per class, six at a time (the splits of one class keep twenty). Every
measuring job runs `dev/varka_datapath.sh --require any` first; it stops the job only when
`expected-cpu` names another machine. In pair mode the job resolves the module from the class's
source, takes the merge base against `vecbricks/varka`'s master fetched by URL (so the fork's
August `origin` never enters), and runs `dev/varka_bench_pair.sh ... --force --out target/pair`
on JDK 25; the verdict, the per-row table, the CPU and the probe go to the job summary, and every
round's results file, provenance and log to the artifact. Results-file mode with `pr` writes a
`-provenance.txt` beside each results file it changes (runner, run URL, commit, inputs, JDK,
kernel, CPU, the probe's lanes and ratio; not for the class `*`) and returns both as the
artifact; `create-commit` is skipped with `pr`. With `pr` the scripts come from the workflow's own
commit (`.varka-tooling`), the pull request's checkout gets no credentials, and the workflow's
token is read-only except for the harness job, which `create-commit` needs. The pair script gained
`--out DIR`, and its forced idle wait gives up after two minutes with a warning; its refusal of a
tree with uncommitted (or untracked) results no longer discards them on the way out - its exit
trap restored both trees' files before the check had passed, which on 13 October threw away a
fresh `VarkaEmissionBenchmark` regeneration - and a scratch-repository check reproduced the loss
on the old script and its absence on the new;
`dev/varka_bench_regen.sh` no longer hands `taskset` an unmatched glob on a machine with no
cpufreq directory.

**Test 8, the runner A/A pair.** `VarkaRangeFilterBenchmark`, `base=HEAD`, three rounds, run
[38467933738](https://github.com/MaxGekk/spark/actions/runs/38467933738) on the fork at
`8a854eec54f`: an Intel Xeon 6973P-C (Granite Rapids), probe 48.2 / 97.1 / 130.2 lane-ops/ns,
control 2.01, 512:256 = 1.34. Verdict **pass**: 18 rows, none slower or faster by 10% by both the
median and the best. The widest change is **504.9% by median and 110.0% by best**, and both come
from rows that are bimodal across JVM forks rather than from the pair: `200 ranges ... Varka,
split conditions` read a best time of 87 to 93 ms in three runs and 346 to 1,348 ms in the other
three, with a standard deviation of 1,800 to 2,500 ms inside every run, so whichever side drew
two slow forks lost by its median; `48 ranges ... split conditions` is the same by its best.
Without those two rows the widest change is 18.2% by median and 7.3% by best. The laptop's only
recorded A/A (`VARKA-256.md` 9, `VarkaCompileBenchmark`, a different class) was within 4.1% by
median. So on a runner, **a row is read by the best and against its own A/A**, which is what the
pair's both-measures rule already does: the four rows the median alone flagged (marked "the
median's alone") are the A/A's noise, and the verdict, which needs both measures, stayed a pass.
The job took 67 minutes: 5 minutes of setup, then 61 for the base's build, the head's and six
class runs.

**Test 8, second draw, with `pr`.** The same A/A on #799's head (`pr=799`, `2724cc8a924`), run
[38476690417](https://github.com/MaxGekk/spark/actions/runs/38476690417), the path the waiting
jobs take: the scripts from `.varka-tooling`, the pull request's tree, a `/tmp` base worktree,
six class runs, 59 minutes. An AMD EPYC 9V45 (Zen 5), 512:256 = 1.99. Verdict **FAIL**: one row
slower by both measures, `10 ranges ... Varka`, -18.2% by median and -16.0% by best, on identical
code. The row is about 20 ms an iteration and bimodal across forks: the base drew two forks at a
best of 18 ms and one at 21, the head three at 21 to 22, and the harness's integer milliseconds
leave nothing between. The other 17 rows are within 10.0% by median and 6.1% by best. The job
failing on that verdict is also the first demonstration that the `shell: bash` fix carries the
pair's exit status.

A first dispatch ([38465858064](https://github.com/MaxGekk/spark/actions/runs/38465858064))
"passed" in five minutes having measured nothing: its pair step piped through `tee` without
`pipefail` (GitHub's default `bash -e`), and the regeneration script failed its first round on
the `taskset` glob. Both are fixed above, and the lesson is in `skills/benchmarking.md`.

**Results-file mode.** Run [38467960582](https://github.com/MaxGekk/spark/actions/runs/38467960582)
with `pr=799` and `class=VarkaRangeFilterBenchmark,CachedTableWidthBenchmark`: two jobs, both
green, each artifact holding the class's `-jdk25-results.txt` and the new provenance file (AMD
EPYC 9V74, 512:256 = 0.91), and nothing from `.varka-tooling`. An earlier run of the same inputs
failed with "No benchmark found to run": the harness matches its glob against the fully qualified
name, so a bare name now becomes `*.<Name>`.

**`VarkaWideKernelBenchmark` stays a laptop pair.** A subset path would need the regeneration
script to run without `SPARK_GENERATE_BENCHMARK_FILES` (which `VarkaBenchmarkBase` requires for
a partial run, `VarkaBenchmarkBase.java:64-70`), a parser for the harness's printed table in place
of the results file the pair reads each round, and a way to shard the class's cases over jobs
whose verdicts are then merged. That is a row's work, not a step of this one, and one class does
not repay it. Its *results file* fits a runner as a single harness run (88 to 189 minutes, 2.6);
only its pair does not.

**Predictions.** 7 is half scored: a fourteen-minute class paired at three rounds in 59 and 67
minutes, so a twenty-minute one is on course for well under four hours; the three waiting jobs are
dispatched after this merges. **8 failed** on both CPU models drawn: the Xeon 6973P-C's widest
change is 110.0% by best on one bimodal row (7.3% without it), the EPYC 9V45's 16.0% by best on a
20 ms row, which failed the pair. As 8 provided, the threshold is now an input, raised per class
with its runner A/A as the reason; and on a runner **a row a pair flags is read against that
class's own runner A/A, or rerun, before it is called a regression**. Each class's spread is its
own, so the waiting jobs below run at five rounds, where one side must draw three slow forks
to move its median.

**After the merge**, the three waiting jobs, on `vecbricks/varka`:

```
# 322a: the A/A on the Java side, then the nine ported classes against their Scala form
gh workflow run benchmark.yml -R vecbricks/varka -f pr=780 -f pair=true -f rounds=5 \
  -f class=VarkaEmissionBenchmark -f base=HEAD
gh workflow run benchmark.yml -R vecbricks/varka -f pr=780 -f pair=true -f rounds=5 \
  -f class="VarkaCodegenBenchmark,VarkaUnreadLocalsBenchmark,VarkaSharedPrefixBenchmark,\
VarkaLongLaneBenchmark,VarkaEmissionBenchmark,VarkaVectorApiProbeBenchmark,\
VarkaMethodSizeBenchmark,VarkaArithmeticBenchmark,VarkaTimeBenchmark"
# 325 (#789): both results files, and the emission class's pair
gh workflow run benchmark.yml -R vecbricks/varka -f pr=789 -f jdk=25 -f generate-tpcds=false \
  -f class="VarkaWideKernelBenchmark,VarkaEmissionBenchmark" \
  -f extra-java-options="--add-modules jdk.incubator.vector"
gh workflow run benchmark.yml -R vecbricks/varka -f pr=789 -f pair=true -f rounds=5 \
  -f class=VarkaEmissionBenchmark
# 322a-2 (#791)
gh workflow run benchmark.yml -R vecbricks/varka -f pr=791 -f pair=true -f rounds=5 \
  -f class=VarkaEmitterParityBenchmark
```

`VarkaWideKernelBenchmark`'s pairs (322a's tenth, 325's second) stay on the laptop.

**The three waiting jobs, run** (14 October 2026). The block above was dispatched at 13:21 CEST,
two minutes after this merged, as five runs on `vecbricks/varka`: the A/A
[38555923693](https://github.com/vecbricks/varka/actions/runs/38555923693), the nine classes
[38555927657](https://github.com/vecbricks/varka/actions/runs/38555927657), #789's two results
files [38555932989](https://github.com/vecbricks/varka/actions/runs/38555932989), #789's Emission
pair [38555937778](https://github.com/vecbricks/varka/actions/runs/38555937778) and #791's parity
pair [38555942066](https://github.com/vecbricks/varka/actions/runs/38555942066). All five
completed with success; the last job ended at 18:12 CEST. The verdicts and the per-row reading are
in `VARKA-322.md` 9 ("The ten pairs, on GitHub-hosted runners"); the cost and the mechanics are
here.

**Prediction 7 held for the parity class and failed for `VarkaUnreadLocalsBenchmark`.** Wall
times per pair job, `started_at` to `completed_at`, every pair at five rounds but the probe
class, which stopped after three: `VarkaVectorApiProbeBenchmark` 0:30:52,
`VarkaArithmeticBenchmark` 0:39:10, `VarkaCodegenBenchmark` 0:47:36, `VarkaEmissionBenchmark`
0:49:41 (322a-1), 0:51:07 (the A/A) and 0:52:30 (#789), `VarkaSharedPrefixBenchmark` 0:58:37,
`VarkaLongLaneBenchmark` 1:08:06, `VarkaMethodSizeBenchmark` 1:22:59, `VarkaTimeBenchmark`
1:59:53, `VarkaEmitterParityBenchmark` 3:29:26 and `VarkaUnreadLocalsBenchmark` 4:51:27. The
parity class, the twenty-minute class the prediction had in mind, took 19 to 25 minutes a
side-run on an EPYC 7763 (the `date:` lines of the artifact's ten provenance files, one run to
the next) and paired at five rounds in under three and a half hours, so three would have been
about two and a quarter; and the three waiting jobs completed with no laptop time. But
`VarkaUnreadLocalsBenchmark` is no twenty-minute class on a runner: 26 to 34 minutes a side-run
on an EPYC 9V45, by the same provenance dates, so its pair at five rounds took 4 h 51 min, ten
single runs and the two builds, with an hour of the six to spare. What failed for it is the
prediction's premise, that the waiting classes were twenty-minute classes, not its arithmetic:
three rounds of this class would have ended under four hours too. The class, whose every case
runs its first iterations before C2 has finished a 1.7 MB class (`VARKA-322.md` 9), was not in
2.6's table of runner times. The results-file run took 2:55:41 for `VarkaWideKernelBenchmark` on
an EPYC 7763 and 0:13:25 for `VarkaEmissionBenchmark` on an EPYC 9V74 (`VARKA-322.md` 9, "Row
325's pair and its two runner files").

**Mechanics seen.** The nine-class list ran six jobs at a time as 3.5 says, so three jobs started
31 to 50 minutes after the first six, and the run took 4:51:38 in all. Eight of the twelve pair
jobs and the wide-kernel results job end with two warnings, `Failed to save: Unable to reserve
cache with key coursier-Linux-<hash>, another job may be creating this cache` and the same for
`build-Linux-<hash>`: the jobs of one dispatch share a key, the first job to finish saves it
(`VarkaVectorApiProbeBenchmark`'s log reads `Cache saved with key` for both; for the key the
#789 and #791 jobs share, the Emission results job on the 9V74 does), and `actions/cache`'s post
step in every job that finishes later finds the key taken and reports success with the warning.
Nothing is lost: the next dispatch restores what that first job saved. The Xeon 6973P-C job pinned
itself (`taskset -c 0,1,2,3`): that runner VM has a cpufreq directory, so the regeneration
script's automatic pin applied, where every EPYC job ran unpinned (`the runner is not pinned` in
its provenance). And the Java side's A/A at five rounds read 16.2% by best on one emission row,
while two pairs drew three slow forks of five on one side of a bimodal row (`VARKA-322.md` 9),
the draw the scoring of prediction 8 above reckoned five rounds would make rare. By the rule that
scoring set, `VarkaEmissionBenchmark`'s next runner pair dispatches with `threshold=17`, its A/A's
widest best rounded up, with that A/A as the reason: the A/A itself would have failed at 10 had
its median on that row read 10.0% rather than 9.9%. No other class has a runner A/A yet, so the
others keep 10 and read a flagged row against an A/A run then. A runner's median alone decides
nothing.

### 9.2 255b: master after each merge (14 October 2026)

**What landed.** `varka-master.yml` on `vecbricks/varka`, on a push to `master` and on dispatch,
calls `build_and_test.yml` with the eleven-job set of 3.3 (255b's form), in the group
`varka-master-<ref>` without cancelling, and runs only on `master`. `varka-weekly-matrix.yml`
names its jobs: `build_java25.yml`'s set with the Kubernetes and OIDC jobs off, plus the
pandas-on-Spark, old Connect client and periodic PySpark groups, `lint`, `java25`, `docs` and
Varka's five. `dev/run-tests.py` reads a diff base that resolves to the head as no change to
measure and tests the modules a job names. `dev/varka_matrix_ci.sh`, on such a run, keys its pick
on the commit and fails on any failure instead of rerunning the same tree, and stops when no
configuration was picked. `dev/checkstyle-suppressions.xml` keys the `InvalidJavadocPosition`
exemption on a Varka package path below a `java/` source root or a class name holding `Varka`.
In `build_and_test.yml`, the `docs` job builds the Python and R API docs on a run of master
instead of reading the empty diff as no change, and the emitted-bytes step says a run of master
has nothing to compare with. The checkout action is unchanged.

**The review** (`/code-review high` on the branch's diff) found nine things; eight are fixed
above. A dispatch from another branch shared the group and would have taken a merge's pending
slot and built that branch, hence the ref in the group and the job's `master` condition. The
weekly set left out three PySpark groups, six of the pyspark job's ten module groups. The `docs`
job and the emitted-bytes step were two more readers of the empty diff. The comparison in
`dev/run-tests.py` was of strings, which an abbreviated SHA would defeat; both sides are now
resolved by git. The pick's failure went unchecked, and the failure lines were computed before
the master-run exit that discards them. The exemption's comment claimed every Varka Java source
sits in a `varka` package, which 44 do not; a checkstyle run with no suppressions over those 44
reports nothing, so the pattern now covers them, and a planted misplaced comment in a
`VarkaPlanted` class outside any `varka` package is reported under both roots. Not taken: one flag
exported by the checkout action for "no change", read by every consumer, in place of each
consumer's own comparison. The action is what #806 just settled for pull requests, and the four
readers this branch touches are one comparison each; the lesson in `working-in-this-repo.md` is
the guard for the next one.

**Evidence, off CI.** Checkstyle 11.0.1, run from the coursier cache over copies of the six
flagged files and a planted misplaced comment in a `varka` package, under roots `varka/varka/` and
`plain/spark/`: before the fix the first root reported the six and the plant, the second only the
plant; after it, both report only the plant, and the worktree's own Varka Java sources stay clean.
`dev/run-tests.py` loaded with its builds and test runs stubbed out, `--modules catalyst` in a
GitHub Actions environment: with the base at the head it now tests `catalyst` (master's file
printed "There are no modules to test"), and with the base at the parent commit, whose diff is a
plan file, it still tests nothing, as a pull request's run should. `dev/varka_matrix_ci.sh` over a
stub matrix whose configuration always fails, in a scratch repository: with the base at the head,
master's script passed with "fails on the merge base too" and the new one fails with "failed on a
run of the merge base itself"; with the base at the parent it still compares, and with no base it
still fails. actionlint reports the same 103 findings as on master, none in the two new or
changed caller workflows (the docs script's five pre-existing shellcheck notes move down four
lines); shellcheck the same single pre-existing warning in `dev/varka_matrix_ci.sh`.

**Owed, after the merge** (test 5's checklist, for the coordinator): the merge commit's own push
starts the first master run, and its jobs, lint verdict, option-step lines and wall time are read
against (a) to (f). The weekly run can be dispatched at once instead of waiting for Sunday
(`gh workflow run varka-weekly-matrix.yml -R vecbricks/varka`), at the price of Spark's full matrix
on the base's slots for about two and a half hours; it cannot run before the merge, because a
pull request's branch lives on the fork, whose default branch does not carry the file. Prediction
5 is scored over the first working day of merges.

**Known and left.** `dev/is-changed.py` keeps reading an empty diff as no change: the master runs
name their jobs and never ask it, and a branch pushed with no commits of its own has the same
empty diff and must not start the full matrix.

### 9.3 The two owed wide-kernel pairs: split into one-round jobs? (16 October 2026)

The owner asked whether row 322a's tenth pair (PR #780's Java port against its Scala form at the
merge base) and row 325's second (PR #789's split fix against its merge base) can run on the
fork's runners as separate one-round pair jobs (`rounds=1`), three per pair, instead of one job
of at least three rounds. Nothing was dispatched: the first question below ends it.

**0. Neither pair can run on any runner, at any round count.** A pair runs the class on the base
and on the head (`dev/varka_bench_pair.sh`), and its `run_side` ends the pair on a side whose
regeneration fails (`the base run failed`); a harness run that dies writes no results file.
Both bases die. #789's merge base `453944cbfb1` has the sixth section's `require(sizes.size() >
1, shape.label() + " fits one kernel: nothing to compare")` (its line 560): sixty-four dates by
field, 256 outputs, fits one kernel on today's emitter, so the run stops there and the seventh
section never runs. #780's merge base `ad1feaa347c` is the Scala form of the same class with the
same shape and the same failure (`VARKA-322.md` 9: "the Scala form fails the same way on today's
emitter"), and #780's own head, the Java port, has the bug too: #789 is what fixed it. 322a's
tenth pair and 325's second therefore have a head that finishes (only #789's) and a base that
never does; the five earlier sections are all a base can give. The `VARKA-322.md` 9 comparison of
the first five sections, by dry-run ids and by the laptop's run to the sixth, is what exists.

**What a section subset would need, and why it cannot be had for these two bases.** (2) The class
has no section filter. `-Dvarka.bench.only` (`VarkaBenchmarkBase.only`) selects cases by name
substring and is refused whenever a results file is written, which the regeneration always does;
the workflow's `sections` input only chooses which sections of a finished run replace the
committed ones, and `extra-java-options` does not apply to a pair. A subset path is a change to
the class (a property read where `section(...)` is called), to `dev/varka_bench_regen.sh` and
`dev/varka_bench_pair.sh` (pass it, and compare the sections that ran), and to `benchmark.yml`
(an input); and it must exist on both sides of the pair. The two bases predate it and cannot be
moved: the workflow's `base` is `HEAD`, a SHA of the checkout (the base repository's pull-request
head with its history) or a branch of `vecbricks/varka`, so a fork-only patch commit on top of
`ad1feaa` or `453944cbfb1` is unreachable, and nothing may be pushed to `origin`.

**1. Time, for a pair that could run.** Per run of the class's step on the fork's runners (the
list is 2.6's, with the two runs found for this section): EPYC 9V74, 88 min 53 s, run
`37048974333` (2 October, an older class, as the committed runner file's provenance); Xeon
8370C, 189 min 48 s, run `37125243691` (3 October, an older class); EPYC 7763, 173 min 54 s, run
`38555932989`, today's class, build included (the job's 2:55:41). A one-round pair is two builds
(the setup step takes 3 min, a tree's first compile a few more: the Emission pair of
`38555937778` spent 5 min 30 s before its probe) and two runs: 2 x 174 + about 15 = about 363
min on the 7763 against the cap of 355 (`timeout-minutes`), and 2 x 190 + 15 = about 395 on the
8370C: **a one-round pair does not fit on either**, and the margin the owner computed (5 min) is
on the 7763 negative. It fits only on a 9V74: about 100 min a run on today's class (the 7763 is
57% to 86% slower on its kernel rows, `VARKA-322.md` 9, which scales 174 to 100 to 110), so about
215 to 235 min, one to two hours of margin, and `rounds=1` only (two rounds would be about 420).
Getting a 9V74 means `-f expected-cpu="AMD EPYC 9V74"`, which the probe step checks by substring
and fails the job on a mismatch about four minutes in; the pool gave a 9V74 8 times in 40, so
about five dispatches per kept job, about thirty for six. The 9V45 (9 in 40, 512:256 = 1.99, a
different datapath) has no run of this class and is not mixed in.

**3. Whether one-round jobs from different runners combine honestly, and the rule.** Within one
CPU model, yes, with two named weaknesses. Each job's pair is a self-contained control (both
sides on one VM, minutes apart), and `dev/varka_bench_pair.py compare` takes any number of files
per side, so three jobs give three base and three head files: the same medians and bests as a
three-round pair, taken on three VMs rather than one. Weaknesses: (i) a one-round job always
runs the base first (`r % 2 = 1`), so a drift that favours the second run reads as a head effect
in all three jobs alike, where a real pair swaps the order each round; and (ii) no A/A of this
class exists on a runner (`VARKA-322.md` 9: "no A/A run of this class exists yet"), while the
neighbouring class's runner A/A read 9.9% by median and 16.2% by best on one row (`VARKA-322.md`
9), so a 10% threshold can be crossed by the runner alone. Different CPU models do not combine:
their rates differ by 60% or more (7763 against 9V74), and a median over a mixed set takes
whichever model sits in the middle, for the two sides alike only by luck.

**The rule, registered before any data.**

1. A pair is three jobs, each `rounds=1`, `expected-cpu="AMD EPYC 9V74"`, `class=
   VarkaWideKernelBenchmark`, all three on the same base and head. A job on any other CPU, or one
   that does not finish, is not used and is dispatched again; the verdict needs all three.
2. Pool the three base and the three head files with `dev/varka_bench_pair.py compare --threshold
   10`. A row is **slower** only if (a) the pooled change is at most -10% by both the median and
   the best, and (b) at least two of the three jobs' own base-against-head changes, each from its
   one pair of runs, are at most -10%. A row that passes (a) and fails (b) is reported as
   unconfirmed, not slower. Faster is the same with the signs reversed.
3. The pair passes if no row is slower. An unconfirmed row, or a slower one on a row whose
   section the plan names bimodal, is read against that row's spread over the three jobs before
   any call, and is not rerun to a better draw.
4. The order confound of (i) is stated with the verdict, and the verdict is "no row moved by 10%
   in this order", not a claim about the other order.

**Alternatives, for the owner.** (A) Close 322a's tenth pair on what exists: dry-run ids and the
names of every section, benchmark and case identical (`VARKA-322.md` 9), and the first five
sections matching the committed laptop file; the sixth and seventh sections exist only in the
Java class's fixed form. 325's second pair has no base to measure: its evidence is the laptop
regeneration within a few percent of the 3 October file and the runner regeneration and
Emission pair already recorded. (B) Build the subset path above as its own row, with the three
one-round jobs and the rule here as its first use, on a base that has it; it serves every pair
after those two, and a class past the cap by another margin. (C) Run, on the 9V74, a pair that
can finish, the head against #789's tip, which measures neither owed question and is not
recommended.

### 9.4 255c: the admission job and the check sync (16 October 2026)

**Admission check: `ubuntu-slim` cuts a job off at 15 minutes.** GitHub's changelog of 28
October 2025 that introduced the runner says "Jobs that use this runner type are limited to 15
minutes of execution time" and "Jobs exceeding this limit will be terminated and fail". 3.1 put
`admit` on `ubuntu-slim` with a 330-minute wait, and 3.2's loop ran in `update_build_status.yml`'s
`ubuntu-slim` job for up to 345 minutes; neither could have waited past a quarter of an hour.
Both jobs run on `ubuntu-latest`. A slot is a slot whatever the runner (2.2: the slim jobs counted
against the twenty), so 3.1's cost line - a waiting full run holds one slot - and prediction 1
stand; the sync loop holds one `ubuntu-latest` slot on the base, which risk 3 already counted.
The alternative for the loop, a slim job of 13 minutes that dispatches the workflow again while
anything is in flight, was not taken: a run in the list every 13 minutes and a gap of a minute or
two at each hop, against one run per episode.

**The fork has forty slots, not twenty.** The owner upgraded `MaxGekk` to GitHub Pro on 14
October, after 2.2 was measured, and a full run now has about 41 jobs. `max-parallel` 12 on the
`Build modules` matrix is implemented as 3.1 states it (accepted on 13 October), but its premise,
"keeping eight slots" of twenty, has moved: the PySpark matrix keeps its own `max-parallel` 20
beside the 12 module jobs, so a full run can still take most of forty. The owner kept 12 on 16
October, with the review's fail-open rule for unreadable runs (the job admits after five failed
polls in a row, about ten minutes, rather than failing the Build).

*Two full runs at once, the owner's request of 16 October 2026.* With forty slots the owner asked
for at least two pull requests' CI to run side by side; one full run at a time had four pull
requests' runs waiting behind one full run that afternoon. `dev/varka_ci_admit.py` takes
`--concurrent N` and admits a run while fewer than N runs are ahead of it (fanned out, or waiting
since earlier); the workflow passes 2. So that two admitted runs fit in forty, the PySpark matrix
on a fork takes at most 6 slots beside the modules' 12, keeping a full run near twenty at its
widest; apache/spark keeps 20 for both. Two new self-tests: a run is admitted beside one that has
fanned out, and waits behind two runs ahead.

**What landed.** `build_and_test.yml` has the `admit` job, "Admit a full-matrix run", on
`ubuntu-latest` with a 340-minute timeout, carrying `Precompile Spark`'s old condition;
`Precompile Spark` now needs it and runs on `(!cancelled()) && needs.admit.result ==
'success'`. That is the second of two routes for risk 2: instead of a copy of the condition and a
test that the copies agree, one condition decides both jobs, so an upstream edit to it conflicts
in this file rather than drifting, and a self-test reads the file and fails if `Precompile Spark`
stops depending on `admit`'s success or `admit` stops carrying the gates. On `apache/spark` and
`vecbricks/varka` the job's two steps are skipped and it succeeds at once; `varka-master.yml`'s
run never reaches it, the weekly matrix passes through it. `build_main.yml`'s job grants
`actions: read` beside `packages: write` (a reusable workflow's job can only narrow what its
caller grants). `Build modules`' `max-parallel` is 20 on `apache/spark` and 12 elsewhere.

`dev/varka_ci_admit.py` is stdlib Python. Each poll reads the `ci-hold` label of the branch's open
pull request on `vecbricks/varka` (named on the command line: the fork's API parent is
`apache/spark`, so the base cannot be derived), lists the fork's `build_main.yml` runs that are
`in_progress` or `queued`, reads each one's jobs (every page, `per_page=100`), finds the job
named with the suffix "Admit a full-matrix run" (the API names it `Run / Admit a full-matrix
run`, confirmed against a live run's job list), and waits while another run's `admit` has
succeeded or is in progress with an earlier `started_at` (ties to the lower run id). A failed
read of the label is a warning and no hold; a failed read of the runs is retried at the next
poll. It prints the request count on each poll. A read-only poll against the fork with the
owner's token (no run of this branch exists) took five requests and found two runs in flight,
neither full.

`update_build_status.yml` starts on `workflow_run` of "On pull request update" as well as the
cron and dispatch, keeps one loop at a time (`concurrency: update-build-status`, not
cancelling), repeats its pass every 120 seconds while any open pull request's fork run is not
completed, read from the run, patches a check only when its status or conclusion differs from
the run's, logs each pass's runs in flight, patches and requests, and at 345 minutes with a run
still in flight dispatches itself (`actions: write`) before ending. It checks nothing out. Its
dispatch comment naming `dev/varka_ci_queue.sh` is gone; the script itself goes in 255d.

**Tests.** Test 1, the self-test (`dev/varka_ci_admit.py --self-test`, twelve unit tests and the
doctests): a full run waits on a run that has fanned out; the earlier `admit` passes first and a
tie goes to the lower run id; a rerun of an older run waits behind a run already waiting and the
one fanned out; a run with no `admit` yet, a skipped, failed or queued `admit` and a completed run
block nothing; a held pull request fails; a 131-job listing is read across two pages; the wait
gives up at its deadline naming the run; the two exempt repositories make no request; and the
workflow file gates `Precompile Spark` on `admit`. The `Varka docs checks` job runs it, and
`dev/varka_ci_*.py` joins the `varka-docs` module's files so a change to the script selects that
job (`varka_change_scope` reads such a change as `docs`; the module doctests pass). The YAML of
the three changed workflows parses; actionlint reports nothing in `update_build_status.yml` and
`build_main.yml`, and the same 60 findings in `build_and_test.yml` as on master. No JavaScript
runtime is on the laptop, so the sync script was checked for balanced brackets only.

**What only a real run can show** (tests 2 to 4, after the merge, since `workflow_run` and the
Build's own jobs run from the default branch and the pushed branch respectively): that a scoped
push skips `admit` and `Precompile Spark`; that of two full-matrix pushes a minute apart the
second's log names the first's run and fans out within one poll of its end; that `actions: read`
in the caller is enough to list the fork's runs and that the fork's token reads the base's pull
requests (2.4 showed it for runs, not for pulls; a failure there is a logged warning and no
hold); that the module matrix never shows more than 12 jobs running; that the Build check of a
test pull request follows its run within one poll; that a pass on unchanged checks logs zero
patches; and whether GitHub lets a completed check be patched back to `in_progress` when its run
is rerun, which the old pass also attempted. A fork run that never completes would keep the loop
re-dispatching every 345 minutes; none has been seen, and the log would show it.

**The author's review** (`/code-review high` on the diff, 16 October) found four things, fixed in a
second commit. The branch name reached the `admit` step's shell through a `${{ }}` expression;
it now goes through the environment. A read of the API that kept failing - a caller without
`actions: read`, such as `build_java25.yml` dispatched on the fork - was retried every poll for
the whole 330 minutes and then failed the Build, and a dropped connection or a body cut short
raised past the retry and failed it at once; every such error is now retried, and five failed
polls in a row admit with a warning, the hold's own rule that a broken read must not stop CI.
The branch went unquoted into the hold's `head=` query, where a `+` or `#` would miss the pull
request. And the check sync had no error boundary around its pull-request listing or its patches,
so one failed request ended a loop meant to run for hours; a failed patch is now logged and left
to a later pass, a failed pass is retried, and five failed passes in a row end the loop without
a dispatch, so a lasting fault cannot chain runs. Two things it found are the owner's: 3.2's
request count is low, since a pass lists the check runs and reads the run of every open pull
request, completed ones included, about 1 + 2N requests for N open pull requests, so 30 passes an
hour reach the base's 1,000 at about 16 open; and "Re-run failed jobs" on a completed full run
keeps the first attempt's succeeded `admit`, so its matrix fans out at once without waiting, and
only "Re-run all jobs" queues as 3.1 describes.

**Owner action 3** applies when this merges: stop starting `dev/varka_ci_queue.sh run`, `hold`
and `drop`, since a run the script cancels is now never rerun, and a held pull request is held
with the `ci-hold` label instead.

### 9.5 255d: the step-time budget, and the laptop queue retired (16 October 2026)

**What landed.** `dev/varka_ci_times.py` with `collect`, `compare` and `gate` as 3.6 describes,
and `sql/varka/ci/step-budget.tsv`, its first version. `collect` reads runs' jobs and steps from
the API (by default every completed run of `varka-master.yml` and `varka-fuzz.yml` on
`vecbricks/varka`; `--created`, `--runs`, `--jobs` and `--conclusion` narrow it) and laptop
`<step>.secs` directories, and rewrites only the lines it read. A job's own wall time is the step
`(job)`; the runner's own steps (`Set up job`, `Complete job`, `Post ...`) are left out; a failed
step, and every step of a failed job, is not a sample. `compare <run>` reads every completed job
of a run, `gate <dir>` a laptop directory, and both mark a step more than 5% over the budget's
90th percentile **and at least 30 seconds over it**: the first live `compare` marked a docs step
of 5 s against a 90th percentile of 4 s, which the API's whole seconds make a 25% move. The CPU
column is `-` throughout: a Build job does not record its runner's CPU (only `benchmark.yml`
annotates it), so the budget has no per-CPU lines yet, and a CI line is read across the pool.
`dev/varka_nightly.sh` writes `<step>.secs` and `<step>.status` beside each log, as the gate
does, and a step whose status is not `ok` is skipped. The check sync checks out the base's
default branch (only the tool and the budget, no credentials kept) and, when it patches a check
to completed, appends `compare --markdown` to the check's summary after a marker, so a later
completion replaces the table rather than adding a second; a failure of the tool leaves the
summary as it was. Risk 4's guard, read as its invariant: the sync never checks out the pull
request's code; a checkout of the base's own default branch is this repository's trusted code.

**The first budget.** CI lines: the Varka jobs of the fork's Build runs created on 13 October
CEST (`2026-10-12T22:00:00Z..2026-10-13T21:59:59Z`), successful jobs only, and the nightly of 12
October (run 38222969275). Laptop lines: the twelve full gates whose `.secs` files survive in the
worktrees of `varka-250` to `varka-311` (5 to 12 October); the four partial gates beside them
are left out, two of them run beside a night load (`varka-269-nightly`'s `wide` 2,023 s). Master's
own runs exist since 14 October and are the default source from the next regeneration on.

**Test 9.** The self-test (nine unit tests and the doctests) plants a step 20% over its 90th
percentile and marks it, leaves one 3% over and one 20% over the median (inside the spread)
unmarked, does not mark a 4-second step at 9 s, skips failed jobs, steps and gate steps, and
keeps the lines `collect` did not read. Against the API, read-only: the nightly of 12 October
reproduces 2.7 to the second (compile 222, IR fuzzer 404, composition 43, variants 514; second
job 299, 2,807, 124). The 13 October Varka jobs reproduce 2.7's medians to within two minutes and
not exactly, and on more runs: 27 catalyst and 26 sql suite jobs where 2.7 counted 21, medians
for the catalyst job, suites and option steps of 22.8, 12.1 and 5.5 minutes against 23, 14 and 6,
and for sql 24.3, 15.8 and 7.2 against 26, 16 and 7; the assembly gate 7.1 against 7, the bench
drivers 2.2 against 2. 2.7 did not record which runs it read (by the counts, not every run of the
day), so the window is the difference, and the budget is the reproducible version. Its first
`compare`, on a fork run of 16 October, marked the `Checkout and sync Spark repository` step of
five jobs at 50 to 62 s against a 90th percentile of 41 to 46 s before the 30 s floor was added:
the full-history checkout #806 introduced on 14 October, after the budget's window, which is the
kind of move the budget is for.

**The laptop queue retired.** `dev/varka_ci_queue.sh` is deleted. In
`sql/varka/skills/working-in-this-repo.md` its section is in the past tense and a new one
describes the admission job, the check sync, the 15-minute `ubuntu-slim` cap and the budget;
`SKILLS.md` is regenerated. Two files outside the row's list named the script or the budget and
are changed with it: `CONTRIBUTING.md`'s paragraph on the queue now describes `admit` and
`ci-hold`, and `sql/varka/AGENTS.md`'s "Pull requests" rule names the budget file and the tool.
`dev/varka_ci_*.py` were added to the `varka-docs` module's files in 255c, so the docs job runs
this tool's self-test too.

**What only a real run can show**: that `require('child_process')` and `python3` are available
to `actions/github-script` on the base's runner and the table lands in a completed check's
summary; that the base's token reads a fork run's jobs for it (the sync already reads the run
itself that way); and prediction 9, after ten pull requests that do not change test code.

**The author's review** (16 October) found the skills section stating the mark as "more than 5%
over the budget's 90th percentile" without the 30 s floor, now stated, and added a guard so that
only a plain owner and repository name and a numeric run id from a check's text reach the tool's
API path. The checkout in the sync is of the base's default branch with no credentials kept, and
the tool is called with an argument list, never through a shell.

### 9.6 255e: new tests rerun, and a fix's new test failing on the base (16 October 2026)

**It fits one pull request**, so 3.4 stays in this row: one script, `dev/varka_new_tests.py`, and
two steps at the end of each Varka suite job.

**What landed.** `list` reads the module's Varka suites as `dev/varka_matrix.sh` finds them
(ScalaTest classes named `Varka...Suite`, JUnit classes under `@VarkaSuite`) and takes a test as
new when its name is in a file the diff from `VARKA_DIFF_BASE` changes and in no Varka-named
suite file of the base nor the base's version of a changed file: a renamed test is new, a test
moved between files is not. A name is read as a chain of string literals joined by `+` across
lines, which is how the long titles and display names are written; anything else - `s"..."`, a
literal plus a value, a `@ParameterizedTest`, `@RepeatedTest` or `@TestFactory` - is reported
`unlistable` with its file and line, as a notice in the job, and is not rerun. A class-level
`@DisplayName` is not a test. The comparison is by name alone, so a new test that reuses another
suite's title is missed; the runner's report names it if it matters.

`rerun` runs the new tests five rounds (`--times`), one JVM a round with every new test in it,
on the classpath and JVM options the suites step left in `target/varka-scoped`, with the memory
sanitizer and the both-bodies check on, through `VarkaSuiteRunner`'s `-s <suite> -t <name>` (one
`-s` per suite: a second `-s` of the same suite reran it and its report replaced the first's,
which a local run showed). Rounds 2 to 5 set `-Dvarka.fuzz.seed` to four other seeds; a suite
that does not read the property ignores it. Results come from the runner's JUnit reports, by
suite and name; a failure in any round fails the step naming the test and the round, and a
listed test that ran in no round is a notice. New tests of the timing suites (`VarkaAssemblySuite`,
`VarkaTierWitnessSuite`, `VarkaWarmupEndToEndSuite`), which run alone and unsanitized, are listed
but not rerun. "In the suite's JVM" of 3.4 became one JVM per round rather than five repetitions
inside one JVM: ScalaTest's runner has no repeat, and a fresh JVM is also what a flaky warm-up
needs to show.

`fails-on-base` asks `vecbricks/varka` for the open pull request of the branch and acts only on
the `fix` label (a fork's push build does not know its pull request; a label added after the
push needs the job rerun). In a scratch worktree of the head it checks out from the base every
changed path outside `src/test/` and deletes every such path the pull request adds, builds the
module's tests there as `dev/varka_matrix.sh` does (`Test/compile`, the exported classpath and
JVM options), runs the new tests once and passes when each fails; a test that passes fails the
step by name; an overlay that does not compile is the notice "not shown: the tests need the fix's
main code", and the step passes. The `dev/` scripts run from the head, so the overlay's restored
tooling never runs.

**Tests.** Test 6, the self-test (twelve unit tests and the doctests), over recorded diffs: a new
ScalaTest test; a title concatenated over two lines; a new JUnit display name, and a class-level
one ignored; a Java file without `@VarkaSuite` not read; a renamed test counted as new; a test
moved to another file not new; an interpolated title reported unlistable, and one already on the
base not reported; a parameterized JUnit test unlistable; JUnit reports matched by suite with or
without the package; the overlay's split of changed main paths into restored and deleted, in a
scratch repository; the timing suites left out of the rerun. The step runs the self-test before
the rerun. Over this repository's history, `list` named exactly the tests six merged commits
added (for example 5 in `VarkaVectorTemplateWarmupSuite` at `aa457c3797b`, 9 in the new
`VarkaPlatformSuite` at `4b3524ada43`, 9 display names in `VarkaRangeRulesSuite` at
`ad6009d0fbf`), and at `cb92050e6d9` one new test, a test whose line changed but whose title did not left out, and one
`s"..."` title reported unlistable. One local round, on another worktree's exported catalyst
build and cores 12 to 23, ran a JUnit display name and a ScalaTest name through the runner and
read both as passed from the reports, and a name that matches nothing as not run.

**What only a real run can show**: test 7 (a planted pull request labelled `fix` whose new test
passes on master fails the step naming it; the same with a real one-line fix passes), the base
build's time on a runner beside the suites (prediction 6: under 8 minutes), the reruns' time on
a typical pull request (prediction 6: under 3 minutes), and that the fork's token reads the
base's pull-request labels (a failed read is a warning and no check). Section 6's count of `fix`
pull requests whose overlay did not compile starts with the first one.

**The author's review** (16 October) found the self-test failing: a doctest of `literal_chain`
started one character past an `s"..."` title's `s`, so it read a plain literal, and the step,
which runs the self-test first, would have failed every Varka suite job. The doctest now starts
at the `s`. `rerun` dropped the runner's exit status, so a suite whose fixture threw, or a JVM
that died, left no report, every new test "ran in no round" as a notice and the step passed; a
round that exits non-zero with no new test failed in its reports now fails the step, with a
thirteenth unit test, and a failed or aborted round prints the end of its log, since the scratch
directory goes with the job. The branch is quoted in the label lookup's `head=` query, a body
that is not JSON is a warning like any failed read, and the self-test closes the files it writes.
