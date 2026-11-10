# VARKA-591: a benchmark that lands on the wrong CPU is rerun by CI, not by a laptop

Row 591 of `PLAN.md`. Added on 9 November 2026 on the owner's word, after #1414's 128-bit run
needed four tries over about five hours from a detached laptop script.

## 0. What exists now (admission check)

1. **The check.** `benchmark.yml`'s harness and pair jobs run `dev/varka_datapath.sh --require
   any --expected-cpu "$EXPECTED_CPU" --github` in the step "Probe the machine's CPU and vector
   datapath". On another CPU it prints `::error::CPU mismatch! Expected '<want>' but got '<got>'`
   and exits 1, before the build. The same step also fails when the probe's own 256:128 control
   cannot see a width doubling, which is not a CPU mismatch.
2. **The retry.** `~/varka-ops/bench-retry-vb.sh <run>` on the laptop: it waits for the run, greps
   `gh run view --log-failed` for `CPU mismatch`, sleeps an hour and reruns the failed jobs, at most
   six attempts. It stops when the laptop sleeps or reboots.
3. **The rate.** On 10 November 2026, on vecbricks/varka, 4 of 5 attempts drew an EPYC 9V45 or
   9V74: run 42794013318's three attempts, and run 42794019027's first (its second passed). At a
   miss rate of 4 in 5, six attempts reach a 7763 with probability 1 - 0.8^6 = 0.74, and ten
   with 0.89.
4. **The records.** For a run, the jobs API (`/actions/runs/<id>/attempts/<n>/jobs`) gives each
   job's conclusion and each step's, and the check-run annotations API
   (`/check-runs/<job id>/annotations`) gives the `::error::` lines as messages: run
   42794013318's attempt 3 has the probe step failed and the annotation `CPU mismatch! Expected
   'AMD EPYC 7763' but got 'AMD EPYC 9V45 96-Core Processor'`; run 41977272181 has the probe
   passed and the step "Run benchmarks" failed. Job outputs are not in the API.

## 1. Design

* **Where.** A workflow of its own, `.github/workflows/varka-bench-cpu-retry.yml`, started by
  `workflow_run` when a run of "Run benchmarks" completes with a failure. It cannot live inside
  `benchmark.yml`: a run cannot be rerun while it is in progress, and a fresh dispatch would be a
  new run id instead of a rerun of the one being tracked. Its job holds `actions: write` (the
  rerun and the dispatch below) and `checks: read` (the annotations), checks out only the tool,
  and passes every event field through `env`.
* **The chain.** GitHub's documentation says that events caused by `GITHUB_TOKEN` do not start
  new runs, except `workflow_dispatch` and `repository_dispatch`. Whether the completion of a rerun
  requested with that token fires `workflow_run` is not stated. So after each rerun the job
  dispatches its own workflow for the next attempt, which waits for that attempt and decides
  again. If `workflow_run` does fire too, the workflow's concurrency group, one per run and
  attempt, serialises the two, and the second finds the run at another attempt and stops. The
  group names the attempt because GitHub keeps one pending job per group and cancels an older
  one: a group per run would let a late `workflow_run` job cancel the pending watcher of the next
  attempt. A watcher whose attempt is still running at its deadline (240 minutes at the default
  wait) fails its job and names the dispatch that resumes the chain.
* **What counts as a CPU-only failure** (`dev/varka_bench_cpu_retry.py`, `decide`): the run is a
  run of `benchmark.yml` and concluded `failure`; every job that neither succeeded nor skipped
  concluded `failure` (a cancelled, timed-out or startup-failed job stops the chain), its first
  failed step is the probe step, and its check run carries a failure annotation starting
  `CPU mismatch! `. The probe's control failing in the same step has no such annotation and is
  not rerun. It reads the API's structure and the annotation that `varka_datapath.sh` itself
  writes, not a log, and needs nothing new from `benchmark.yml`, so it also covers runs dispatched
  before it merged.
* **The cap and the wait.** At most 6 attempts in all (`run_attempt`), the laptop script's
  number; the rerun comes 60 minutes after the attempt completed, as the laptop's hourly loop.
  A misconfigured `expected-cpu` mismatches on every runner and stops at attempt 6 with
  "gave up: attempt 6 of 6 is the cap". After the wait the run is read again, and a run already
  rerun by someone else (a laptop watcher still running) is left alone.
* **What it costs.** vecbricks/varka is public, so the minutes are free; a waiting job holds one
  of the account's 20 slots for about an hour per retry.
* **Visibility.** `varka_datapath.sh --github` writes "CPU check, attempt N: expected ..., got
  ...: failed" to the benchmark job's summary, and on a later attempt that passes, the CPU it got;
  each retry job's own summary lists the mismatches it read and what it did.

* **Not covered.** `varka-surface-benchmark.yml` runs the same CPU check, but its runs are not
  rerun; the row names `benchmark.yml`.

## 2. Review

`/code-review high` on the branch found ten points. Fixed: the per-run concurrency group (above);
a watcher dispatched ahead of its attempt stopped instead of polling; the deadline ended the chain
with a green job; a failed dispatch after a rerun was not retried (it is now, as reads are, over
429, 5xx and network failures only); the dry run waited out the hour; a rerun's carried-over jobs
could end the watch before its rerun jobs were listed (the watch now waits until every job the
previous attempt failed is listed); a run of another workflow was rejected only after the watch;
the summary comment in `varka_datapath.sh` claimed the surface benchmark's reruns. Left: a
scheduled sweep instead of the chain, since GitHub's cron here runs hours late
(`update_build_status.yml`'s note), which would turn the hour's wait into several; and a shared
API client with `varka_ci_admit.py`, since the job checks out only this one file.

## 3. Verification

Before the merge: `dev/varka_bench_cpu_retry.py --selftest` (decisions over the recorded shapes
of runs 42794013318 and 41977272181, the cap over a misconfigured `expected-cpu`, and the names
it keys on read from `benchmark.yml` and `varka_datapath.sh`), `actionlint`, and `--dry-run`
against vecbricks/varka's runs: 42794013318 attempt 3 and 41970716865 read as CPU-only, 41977272181
as a benchmark failure, 42794019027 as a success.

`workflow_run` runs only from the default branch, and the fork's default branch is Apache Spark's,
so the workflow cannot run before the merge. After it, on vecbricks/varka: one dispatch of
`benchmark.yml` with a class that exists, `generate-tpcds=false` and `expected-cpu` set to a
model no runner has (`expected-cpu=NO-SUCH-CPU`). Each attempt dies at the probe within a few
minutes. The `workflow_run` job it starts is cancelled, and this workflow is dispatched by hand
with `run-id=<run> attempt=1 wait-minutes=0`: the chain passes the wait on, so that one dispatch
reruns attempts 1 to 5 and stops at 6 with "gave up", in about half an hour and twelve short jobs.
Any retry job started by `workflow_run` beside the dispatched ones answers whether a
`GITHUB_TOKEN` rerun fires that event.
