#!/usr/bin/env python3
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
"""Rerun a benchmark run whose only failures are the runner CPU check, up to a cap.

    dev/varka_bench_cpu_retry.py --repo OWNER/NAME --run-id ID --attempt N
        [--wait-minutes 60] [--max-attempts 6] [--deadline-minutes 300] [--dry-run]
        [--dispatch-ref REF]
    dev/varka_bench_cpu_retry.py --selftest   # the decision over recorded API responses

This is the job of `.github/workflows/varka-bench-cpu-retry.yml` (VARKA-591). `benchmark.yml`
fails a job when `expected-cpu` is set and the pool hands it another CPU (`dev/varka_datapath.sh`
prints `CPU mismatch! Expected '...' but got '...'` as an error annotation). This tool watches
attempt N of one run of `benchmark.yml`:

1. It waits until attempt N completes. It stops early, with nothing to do, once another attempt
   has started or once every benchmark job of attempt N has passed its CPU check, since such a
   run can no longer fail on the CPU.
2. It decides. A rerun is due only when the run failed, the attempt is below the cap, and every
   job that did not succeed or skip failed, its first failed step is the CPU check step and its
   check run carries the mismatch annotation. A cancelled or timed-out job, a failure in any
   other step, or the probe's own control failing in the same step means no rerun.
3. It waits until `--wait-minutes` after the attempt completed, reads the run again, and reruns
   the failed jobs only if the run is still at attempt N and completed.
4. It dispatches its own workflow for attempt N + 1, which repeats this from step 1. A rerun
   requested with the job's GITHUB_TOKEN is not known to fire `workflow_run` when it completes,
   so the chain is carried by `workflow_dispatch`, which that token may start. If `workflow_run`
   does fire as well, both jobs watch the same attempt, the workflow's concurrency group runs
   them one after the other, and the second finds the run moved on and stops.

The cap is on the run's `run_attempt`, so a misconfigured `expected-cpu`, which mismatches on
every runner, ends after `--max-attempts` attempts in all. Each decision is written to the job's
step summary; the benchmark run's own summary gets the attempt and the CPU from the check itself.
Requests use the token in GH_TOKEN (or GITHUB_TOKEN); the workflow grants `actions: write` (the
rerun and the dispatch) and `checks: read` (the annotations).
"""

import argparse
import datetime
import json
import os
import pathlib
import sys
import time
import unittest
import urllib.request

API = "https://api.github.com"
WORKFLOW_PATH = ".github/workflows/benchmark.yml"
WORKFLOW_NAME = "Run benchmarks"
RETRY_WORKFLOW = "varka-bench-cpu-retry.yml"
# The step of benchmark.yml's harness and pair jobs that runs dev/varka_datapath.sh.
PROBE_STEP = "Probe the machine's CPU and vector datapath"
# What dev/varka_datapath.sh's mismatch annotation starts with.
MISMATCH = "CPU mismatch! "
# The jobs that run the CPU check, by the start of their display names in benchmark.yml.
BENCH_JOB_PREFIXES = ("Run benchmarks: ", "Benchmark pair: ")
OK_CONCLUSIONS = ("success", "skipped", "neutral")
POLL_SECONDS = 120


class Api:
    def __init__(self, repo, token):
        self.repo = repo
        self.token = token

    def request(self, method, path, body=None):
        url = path if path.startswith("https://") else f"{API}/repos/{self.repo}/{path}"
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read()
        return json.loads(raw) if raw else None

    def run(self, run_id):
        return self.request("GET", f"actions/runs/{run_id}")

    def jobs(self, run_id, attempt):
        jobs, page = [], 1
        while True:
            got = self.request(
                "GET", f"actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100&page={page}"
            )
            jobs += got["jobs"]
            if len(got["jobs"]) < 100 or len(jobs) >= got.get("total_count", 0):
                return jobs
            page += 1

    def annotations(self, job_id):
        return self.request("GET", f"check-runs/{job_id}/annotations?per_page=100")

    def rerun_failed(self, run_id):
        self.request("POST", f"actions/runs/{run_id}/rerun-failed-jobs")

    def dispatch(self, ref, inputs):
        self.request(
            "POST", f"actions/workflows/{RETRY_WORKFLOW}/dispatches", {"ref": ref, "inputs": inputs}
        )


def first_failed_step(job):
    return next((s["name"] for s in job.get("steps", []) if s.get("conclusion") == "failure"), None)


def mismatch_of(annotations):
    """The mismatch annotation's message, or None.

    >>> mismatch_of([{"annotation_level": "failure", "message": "CPU mismatch! Expected 'a'"}])
    "CPU mismatch! Expected 'a'"
    >>> mismatch_of([{"annotation_level": "notice", "message": "CPU mismatch! Expected 'a'"}])
    """
    for a in annotations:
        if a.get("annotation_level") == "failure" and a.get("message", "").startswith(MISMATCH):
            return a["message"]
    return None


def checks_all_passed(jobs):
    """True once every benchmark job of an attempt in flight has passed its CPU check.

    A job still queued has no steps yet and keeps the answer False.
    """
    bench = [j for j in jobs if j["name"].startswith(BENCH_JOB_PREFIXES)]
    bench = [j for j in bench if j.get("conclusion") != "skipped"]
    if not bench:
        return False
    for j in bench:
        probe = [s for s in j.get("steps", []) if s["name"] == PROBE_STEP]
        if not probe or probe[0].get("conclusion") != "success":
            return False
    return True


def decide(run, jobs, annotations, max_attempts):
    """('rerun' | 'stop', reason, [(job name, mismatch message)]) for a completed attempt."""
    if run.get("path") != WORKFLOW_PATH:
        return "stop", f"run is of {run.get('path')}, not {WORKFLOW_PATH}", []
    if run.get("status") != "completed":
        return "stop", f"run is {run.get('status')}, not completed", []
    if run.get("conclusion") != "failure":
        return "stop", f"run concluded {run.get('conclusion')}", []
    found, others = [], []
    for j in jobs:
        if j.get("conclusion") in OK_CONCLUSIONS:
            continue
        if j.get("conclusion") != "failure":
            others.append(f"{j['name']} is {j.get('conclusion')}")
            continue
        step = first_failed_step(j)
        if step != PROBE_STEP:
            others.append(f"{j['name']} failed in step '{step}'")
            continue
        msg = mismatch_of(annotations(j["id"]))
        if msg is None:
            others.append(f"{j['name']} failed the CPU check step without a CPU mismatch")
            continue
        found.append((j["name"], msg))
    if others:
        return "stop", "not only the CPU check failed: " + "; ".join(others), found
    if not found:
        return "stop", "no job failed", []
    if run["run_attempt"] >= max_attempts:
        return "stop", f"gave up: attempt {run['run_attempt']} of {max_attempts} is the cap", found
    reason = f"only the CPU check failed, attempt {run['run_attempt']} of {max_attempts}"
    return "rerun", reason, found


def parse_time(s):
    return datetime.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)


class Retry:
    def __init__(self, api, run_id, attempt, wait_minutes, max_attempts, dry_run, ref, clock=None):
        self.api = api
        self.run_id = run_id
        self.attempt = attempt
        self.wait = datetime.timedelta(minutes=wait_minutes)
        self.max_attempts = max_attempts
        self.dry_run = dry_run
        self.ref = ref
        self.now, self.sleep = clock or (
            lambda: datetime.datetime.now(datetime.timezone.utc),
            time.sleep,
        )
        self.lines = []

    def log(self, line):
        print(line)
        self.lines.append(line)

    def watch(self, deadline):
        """Wait for attempt N to complete; the run, or None when nothing can be due."""
        while True:
            run = self.api.run(self.run_id)
            if run["run_attempt"] != self.attempt:
                self.log(f"the run is at attempt {run['run_attempt']}, not {self.attempt}: stop")
                return None
            if run["status"] == "completed":
                return run
            if checks_all_passed(self.api.jobs(self.run_id, self.attempt)):
                self.log(f"attempt {self.attempt} passed the CPU check on every job: stop")
                return None
            if self.now() >= deadline:
                self.log(f"attempt {self.attempt} did not complete by the deadline: stop")
                return None
            self.sleep(POLL_SECONDS)

    def go(self, deadline):
        run = self.watch(deadline)
        if run is None:
            return "stop"
        verdict, reason, found = decide(
            run, self.api.jobs(self.run_id, self.attempt), self.api.annotations, self.max_attempts
        )
        for name, msg in found:
            self.log(f"- {name}: {msg}")
        self.log(f"attempt {self.attempt}: {reason}")
        if verdict != "rerun":
            return "stop"
        due = parse_time(run["updated_at"]) + self.wait
        left = (due - self.now()).total_seconds()
        if left > 0:
            self.log(f"waiting {round(left / 60)} min, until {due:%H:%M} UTC")
            self.sleep(left)
        again = self.api.run(self.run_id)
        if again["run_attempt"] != self.attempt or again["status"] != "completed":
            self.log(f"the run moved on (attempt {again['run_attempt']}, {again['status']}): stop")
            return "stop"
        if self.dry_run:
            self.log("dry run: no rerun")
            return "dry-run"
        self.api.rerun_failed(self.run_id)
        self.log(f"reran the failed jobs: attempt {self.attempt + 1} of {self.max_attempts}")
        self.api.dispatch(
            self.ref,
            {
                "run-id": str(self.run_id),
                "attempt": str(self.attempt + 1),
                "wait-minutes": str(int(self.wait.total_seconds() // 60)),
            },
        )
        self.log(f"dispatched {RETRY_WORKFLOW} on {self.ref} to watch attempt {self.attempt + 1}")
        return "rerun"


def summary(repo, run_id, attempt, lines):
    url = f"https://github.com/{repo}/actions/runs/{run_id}/attempts/{attempt}"
    out = [f"### CPU-check retry: run [{run_id}]({url}), attempt {attempt}", ""]
    return "\n".join(out + [f"    {line}" for line in lines]) + "\n"


def main(argv):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--selftest", action="store_true")
    p.add_argument("--repo")
    p.add_argument("--run-id", type=int)
    p.add_argument("--attempt", type=int)
    p.add_argument("--wait-minutes", type=int, default=60)
    p.add_argument("--max-attempts", type=int, default=6)
    # The job's budget for the watch and the wait together, inside the workflow's timeout.
    p.add_argument("--deadline-minutes", type=int, default=300)
    p.add_argument("--dispatch-ref", default="master")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args(argv)
    if args.selftest:
        import doctest

        failed, _ = doctest.testmod()
        result = unittest.main(argv=[sys.argv[0]], exit=False, verbosity=2).result
        return 1 if failed or not result.wasSuccessful() else 0
    if not (args.repo and args.run_id and args.attempt):
        p.error("--repo, --run-id and --attempt are required")
    if not 0 <= args.wait_minutes <= 240 or args.wait_minutes >= args.deadline_minutes:
        p.error("--wait-minutes must be within 0 to 240 and below --deadline-minutes")
    api = Api(args.repo, os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN"))
    retry = Retry(
        api,
        args.run_id,
        args.attempt,
        args.wait_minutes,
        args.max_attempts,
        args.dry_run,
        args.dispatch_ref,
    )
    # The watch gives up early enough that the wait after it still ends inside the budget.
    deadline = retry.now() + datetime.timedelta(minutes=args.deadline_minutes - args.wait_minutes)
    retry.go(deadline)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a") as f:
            f.write(summary(args.repo, args.run_id, args.attempt, retry.lines))
    return 0


# The tests: shapes recorded from vecbricks/varka's runs of 5 and 10 November 2026.

ROOT = pathlib.Path(__file__).resolve().parent.parent


def probe(conclusion):
    return {"name": PROBE_STEP, "status": "completed", "conclusion": conclusion}


def step(name, conclusion):
    return {"name": name, "status": "completed", "conclusion": conclusion}


def job(job_id, name, conclusion, steps=()):
    return {"id": job_id, "name": name, "conclusion": conclusion, "steps": list(steps)}


BENCH = "Run benchmarks: *.VarkaBigintArithmeticBaselineBenchmark (JDK 25, Scala 2.13, 1 out of 1)"
GOT_9V45 = "CPU mismatch! Expected 'AMD EPYC 7763' but got 'AMD EPYC 9V45 96-Core Processor'"
EXIT_1 = {"annotation_level": "failure", "message": "Process completed with exit code 1."}
ANNOTATIONS = {
    # Run 42794013318, attempt 3: the probe step failed on an EPYC 9V45.
    1: [
        EXIT_1,
        {"annotation_level": "failure", "message": GOT_9V45},
        {"annotation_level": "notice", "message": "Runner CPU: AMD EPYC 9V45 96-Core Processor"},
    ],
    # Run 41977272181: the CPU check passed and the benchmark itself failed.
    2: [
        EXIT_1,
        {"annotation_level": "failure", "message": "SELECT dt AS a FROM varka_day_time_interval"},
        {"annotation_level": "notice", "message": "Runner CPU: AMD EPYC 7763 64-Core Processor"},
    ],
    # The probe's own control failing, in the same step as the CPU check.
    3: [
        EXIT_1,
        {"annotation_level": "failure", "message": "the probe's own control is 1.02 - it cannot"},
    ],
}


def cpu_failed(job_id=1):
    return job(
        job_id,
        BENCH,
        "failure",
        [
            step("Setup build environment", "success"),
            probe("failure"),
            step("Run benchmarks", "skipped"),
        ],
    )


def other_jobs():
    return [
        job(10, "Generate matrix for job splits", "success", [step("Generate matrix", "success")]),
        job(11, "Generate an TPC-DS dataset with SF=1", "success"),
        job(12, "Benchmark pair: ${{ matrix.class.name }}", "skipped"),
    ]


def run_record(
    attempt=1,
    status="completed",
    conclusion="failure",
    updated="2026-11-10T14:17:12Z",
    path=WORKFLOW_PATH,
):
    return {
        "id": 42794013318,
        "path": path,
        "status": status,
        "conclusion": conclusion,
        "run_attempt": attempt,
        "updated_at": updated,
    }


class FakeApi:
    """A run whose attempts follow the scripted (status, conclusion, jobs) per attempt."""

    def __init__(self, attempts, runs_after_wait=None):
        self.attempts = attempts
        self.reads = 0
        self.attempt = 1
        self.runs_after_wait = runs_after_wait
        self.reruns = 0
        self.dispatches = []

    def run(self, run_id):
        self.reads += 1
        if self.runs_after_wait and self.reads > 1:
            return self.runs_after_wait
        status, conclusion, _ = self.attempts[self.attempt]
        return run_record(self.attempt, status, conclusion)

    def jobs(self, run_id, attempt):
        return self.attempts[attempt][2]

    def annotations(self, job_id):
        return ANNOTATIONS.get(job_id, [EXIT_1])

    def rerun_failed(self, run_id):
        self.reruns += 1

    def dispatch(self, ref, inputs):
        self.dispatches.append((ref, inputs))


class Clock:
    def __init__(self, start="2026-11-10T14:17:30Z"):
        self.t = parse_time(start)
        self.slept = []

    def now(self):
        return self.t

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.t += datetime.timedelta(seconds=seconds)


def retry(api, attempt=1, wait=60, cap=6, dry_run=False):
    c = Clock()
    r = Retry(api, 42794013318, attempt, wait, cap, dry_run, "master", (c.now, c.sleep))
    return r, c


FAR = parse_time("2026-11-11T00:00:00Z")


class DecideTest(unittest.TestCase):
    def test_only_the_cpu_check_failed_is_rerun(self):
        v, reason, found = decide(
            run_record(3), [cpu_failed()] + other_jobs(), FakeApi({}).annotations, 6
        )
        self.assertEqual(v, "rerun", reason)
        self.assertEqual(found, [(BENCH, GOT_9V45)])

    def test_a_benchmark_failure_is_never_rerun(self):
        failed = job(2, BENCH, "failure", [probe("success"), step("Run benchmarks", "failure")])
        v, reason, _ = decide(run_record(), [failed] + other_jobs(), FakeApi({}).annotations, 6)
        self.assertEqual(v, "stop")
        self.assertIn("failed in step 'Run benchmarks'", reason)

    def test_the_probe_control_failing_in_the_same_step_is_not_a_mismatch(self):
        v, reason, _ = decide(run_record(), [cpu_failed(3)], FakeApi({}).annotations, 6)
        self.assertEqual(v, "stop")
        self.assertIn("without a CPU mismatch", reason)

    def test_one_mismatch_beside_another_failure_is_not_rerun(self):
        steps = [probe("success"), step("Run benchmarks", "failure")]
        other = job(2, BENCH + " 2", "failure", steps)
        v, reason, found = decide(run_record(), [cpu_failed(), other], FakeApi({}).annotations, 6)
        self.assertEqual(v, "stop")
        self.assertEqual(len(found), 1)

    def test_a_cancelled_or_timed_out_job_is_not_rerun(self):
        for c in ("cancelled", "timed_out", "startup_failure"):
            v, reason, _ = decide(
                run_record(), [cpu_failed(), job(5, BENCH, c)], FakeApi({}).annotations, 6
            )
            self.assertEqual(v, "stop", c)
            self.assertIn(c, reason)

    def test_a_failed_job_ahead_of_the_benchmark_is_not_rerun(self):
        gen = job(
            10, "Generate matrix for job splits", "failure", [step("Generate matrix", "failure")]
        )
        v, _, _ = decide(run_record(), [gen], FakeApi({}).annotations, 6)
        self.assertEqual(v, "stop")

    def test_the_cap_stops_and_says_so(self):
        v, reason, _ = decide(run_record(6), [cpu_failed()], FakeApi({}).annotations, 6)
        self.assertEqual(v, "stop")
        self.assertTrue(reason.startswith("gave up: attempt 6 of 6"), reason)

    def test_other_workflows_and_other_outcomes_are_not_rerun(self):
        a = FakeApi({}).annotations
        for r in (
            run_record(path=".github/workflows/build_main.yml"),
            run_record(conclusion="cancelled"),
            run_record(conclusion="success"),
            run_record(status="in_progress", conclusion=None),
        ):
            self.assertEqual(decide(r, [cpu_failed()], a, 6)[0], "stop", r)


class RetryTest(unittest.TestCase):
    def test_waits_until_the_wait_after_completion_then_reruns_and_chains(self):
        api = FakeApi({1: ("completed", "failure", [cpu_failed()] + other_jobs())})
        r, clock = retry(api)
        self.assertEqual(r.go(FAR), "rerun")
        self.assertEqual(api.reruns, 1)
        # Completed 14:17:12, now 14:17:30: 59.7 minutes left of the hour.
        self.assertAlmostEqual(clock.slept[-1], 3600 - 18)
        self.assertEqual(
            api.dispatches,
            [("master", {"run-id": "42794013318", "attempt": "2", "wait-minutes": "60"})],
        )
        self.assertIn("reran the failed jobs: attempt 2 of 6", r.lines)

    def test_a_misconfigured_expected_cpu_stops_at_the_cap(self):
        # Every attempt mismatches: the chain reruns five times and the sixth attempt gives up.
        attempts = {n: ("completed", "failure", [cpu_failed()]) for n in range(1, 8)}
        api = FakeApi(attempts)
        results = []
        for n in range(1, 8):
            api.attempt = n
            results.append(retry(api, attempt=n)[0].go(FAR))
        self.assertEqual(results, ["rerun"] * 5 + ["stop", "stop"])
        self.assertEqual(api.reruns, 5)

    def test_a_run_rerun_by_someone_else_during_the_wait_is_left_alone(self):
        api = FakeApi(
            {1: ("completed", "failure", [cpu_failed()])},
            runs_after_wait=run_record(2, "queued", None),
        )
        r, _ = retry(api)
        self.assertEqual(r.go(FAR), "stop")
        self.assertEqual((api.reruns, api.dispatches), (0, []))

    def test_a_watcher_of_an_older_attempt_stops(self):
        api = FakeApi({2: ("in_progress", None, [])})
        api.attempt = 2
        r, _ = retry(api, attempt=1)
        self.assertEqual(r.go(FAR), "stop")
        self.assertEqual(api.reruns, 0)

    def test_waits_for_the_attempt_and_stops_once_every_check_passed(self):
        queued = [job(1, BENCH, None)]
        passed = [
            job(
                1,
                BENCH,
                None,
                [probe("success"), {"name": "Run benchmarks", "status": "in_progress"}],
            )
        ]
        api = FakeApi({1: ("in_progress", None, queued)})
        r, clock = retry(api)
        script = iter([queued, passed])
        api.jobs = lambda run_id, attempt: next(script)
        self.assertEqual(r.go(FAR), "stop")
        self.assertEqual(clock.slept, [POLL_SECONDS])
        self.assertIn("attempt 1 passed the CPU check on every job: stop", r.lines)

    def test_dry_run_neither_reruns_nor_dispatches(self):
        api = FakeApi({1: ("completed", "failure", [cpu_failed()])})
        r, _ = retry(api, dry_run=True)
        self.assertEqual(r.go(FAR), "dry-run")
        self.assertEqual((api.reruns, api.dispatches), (0, []))


class CouplingTest(unittest.TestCase):
    """The names this tool keys on, read from the files that define them."""

    def test_benchmark_workflow_name_and_probe_step(self):
        text = (ROOT / WORKFLOW_PATH).read_text()
        self.assertIn(f"\nname: {WORKFLOW_NAME}\n", text)
        self.assertEqual(text.count(f"- name: {PROBE_STEP}\n"), 2)
        self.assertIn('\n    name: "Run benchmarks: ${{ matrix.class.name }}', text)
        self.assertIn('\n    name: "Benchmark pair: ${{ matrix.class.name }}"', text)

    def test_retry_workflow_triggers_on_the_benchmark_workflow(self):
        text = (ROOT / ".github/workflows" / RETRY_WORKFLOW).read_text()
        self.assertIn(f'workflows: ["{WORKFLOW_NAME}"]', text)

    def test_datapath_prints_the_mismatch_prefix(self):
        text = (ROOT / "dev/varka_datapath.sh").read_text()
        self.assertIn(f'fail "{MISMATCH}Expected', text)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
