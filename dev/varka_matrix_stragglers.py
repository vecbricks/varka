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
"""Option-matrix stragglers: how long the bins' units ran.

    dev/varka_matrix_stragglers.py history --repo OWNER/NAME RUN_ID...
    dev/varka_matrix_stragglers.py --selftest

The step "Run this bin's units" of `.github/workflows/varka-option-matrix.yml` stops its units at
three times the bin's planned seconds, floored at DEADLINE_FLOOR_SECONDS, and fails with an error
annotation titled `Straggler` that names the bin, its minutes, its plan and its CPU (VARKA-570).

`history` prints, for each run and every attempt, the units step's own minutes per bin (from the
step's start and end in the jobs API, setup steps left out), the median and the longest, and
every bin past the floor, x86 and Arm apart; the deadline's floor was read from it. Requests use
the token in GH_TOKEN (or GITHUB_TOKEN).
"""

import argparse
import datetime
import os
import pathlib
import statistics
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from varka_bench_cpu_retry import Api

WORKFLOW_PATH = ".github/workflows/varka-option-matrix.yml"
WORKFLOW_NAME = "Varka option matrix"
BIN_PREFIX = "Option matrix: b"
UNITS_STEP = "Run this bin's units"
ANNOTATION_TITLE = "Straggler"
# Four of the planner's 480 s lanes (dev/varka_matrix_bins.py --lane): the next multiple of the
# lane above the longest green units step of 19 runs, 24.3 minutes (VARKA-570.md 6).
DEADLINE_FLOOR_SECONDS = 4 * 480


def parse_time(s):
    return datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))


def minutes_between(start, end):
    if not start or not end:
        return None
    return (parse_time(end) - parse_time(start)).total_seconds() / 60


def units_minutes(job):
    """The minutes of the job's units step, or None when it did not both start and end."""
    for s in job.get("steps", []):
        if s.get("name") == UNITS_STEP:
            return minutes_between(s.get("started_at"), s.get("completed_at"))
    return None


def is_bin(job):
    return job.get("name", "").startswith(BIN_PREFIX)


def is_arm(job):
    return any("arm" in label for label in job.get("labels", []))


def history_rows(run_id, jobs, floor_minutes=DEADLINE_FLOOR_SECONDS / 60):
    """One line for each kind of runner of a run's bins: the count, the median and the longest
    units-step minutes, and each bin past `floor_minutes` with its attempt and conclusion."""
    rows = []
    for kind in ("x86", "arm"):
        bins = [
            (units_minutes(j), j)
            for j in jobs
            if is_bin(j) and is_arm(j) == (kind == "arm") and units_minutes(j) is not None
        ]
        if not bins:
            continue
        took = [m for m, _ in bins]
        over = [
            f"{j['name'][len('Option matrix: ') :]}#{j.get('run_attempt', 1)} {m:.1f}"
            f" {j.get('conclusion')}"
            for m, j in sorted(bins, key=lambda b: -b[0])
            if m > floor_minutes
        ]
        rows.append(
            f"{run_id:<13} {kind:4} {len(bins):5} {statistics.median(took):7.1f}"
            f" {max(took):8.1f}  {', '.join(over) or '-'}"
        )
    return rows


# A matrix run lists about 190 jobs with their steps, and a page of 100 of them answered 502
# after three tries (run 40942811546, 20 November 2026), so a page is half that.
PAGE = 50


def listed_jobs(api, path):
    """Every job of a jobs listing, PAGE at a time."""
    jobs, page = [], 1
    while True:
        got = api.request("GET", f"{path}per_page={PAGE}&page={page}")
        jobs += got["jobs"]
        if len(got["jobs"]) < PAGE or len(jobs) >= got.get("total_count", 0):
            return jobs
        page += 1


def all_jobs(api, run_id):
    return listed_jobs(api, f"actions/runs/{run_id}/jobs?filter=all&")


def main(argv):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--selftest", action="store_true")
    sub = p.add_subparsers(dest="cmd")
    h = sub.add_parser("history")
    h.add_argument("--repo", required=True)
    h.add_argument("runs", nargs="+", type=int)
    args = p.parse_args(argv)
    if args.selftest:
        result = unittest.main(argv=[sys.argv[0]], exit=False, verbosity=0).result
        print("varka_matrix_stragglers selftest: " + ("ok" if result.wasSuccessful() else "FAILED"))
        return 0 if result.wasSuccessful() else 1
    if args.cmd is None:
        p.print_help()
        return 2
    api = Api(args.repo, os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN"))
    floor = DEADLINE_FLOOR_SECONDS / 60
    print(f"bins' units-step minutes; bins past {floor:.0f} (bin#attempt minutes conclusion)")
    print(f"{'run':<13} {'kind':4} {'bins':>5} {'median':>7} {'longest':>8}  past the floor")
    for run_id in args.runs:
        try:
            rows = history_rows(run_id, all_jobs(api, run_id))
        except OSError as e:
            rows = [f"{run_id:<13} no jobs returned ({type(e).__name__})"]
        print("\n".join(rows or [f"{run_id:<13} no bins"]))
    return 0


# The tests: job shapes after the jobs listing of the matrix.

ROOT = pathlib.Path(__file__).resolve().parent.parent


def step(name, conclusion, start=None, end=None):
    return {"name": name, "conclusion": conclusion, "started_at": start, "completed_at": end}


def bin_job(job_id, n, conclusion, failed_step=None, attempt=1, labels=("ubuntu-latest",)):
    steps = [step("Checkout", "success")]
    if failed_step == UNITS_STEP:
        steps.append(step(UNITS_STEP, "failure"))
    elif failed_step:
        steps += [step(failed_step, "failure"), step(UNITS_STEP, "skipped")]
    else:
        steps.append(step(UNITS_STEP, conclusion))
    return {
        "id": job_id,
        "name": f"{BIN_PREFIX}{n:03d}",
        "conclusion": conclusion,
        "run_attempt": attempt,
        "labels": list(labels),
        "steps": steps,
    }


class HistoryTest(unittest.TestCase):
    def test_the_units_step_alone_is_timed_and_arm_is_apart(self):
        def timed(job_id, n, start, end, labels=("ubuntu-latest",)):
            j = bin_job(job_id, n, "success", labels=labels)
            j["steps"] = [
                step("Install Java 25", "success", "2026-11-17T10:00:00Z", "2026-11-17T10:16:00Z"),
                step(UNITS_STEP, "success", start, end),
            ]
            return j

        jobs = [
            timed(1, 1, "2026-11-17T10:16:00Z", "2026-11-17T10:24:00Z"),
            timed(2, 2, "2026-11-17T10:16:00Z", "2026-11-17T10:49:00Z"),
            timed(3, 3, "2026-11-17T10:16:00Z", "2026-11-17T10:22:00Z"),
            timed(4, 4, "2026-11-17T10:16:00Z", "2026-11-17T10:26:00Z", ("ubuntu-24.04-arm",)),
        ]
        rows = history_rows(7, jobs)
        self.assertEqual(len(rows), 2)
        self.assertRegex(rows[0], r"^7 +x86 +3 +8\.0 +33\.0  b002#1 33\.0 success$")
        self.assertRegex(rows[1], r"^7 +arm +1 +10\.0 +10\.0  -$")


class ReportTest(unittest.TestCase):
    """The report job merges a straggler's units with the others': a JVM the watchdog stopped
    has no exit file and leaves the report green, a unit that failed before the stop does not."""

    def report(self, stopped_exit):
        import contextlib
        import io
        import tempfile

        import varka_matrix_report

        with tempfile.TemporaryDirectory() as root:
            runs = pathlib.Path(root, "runs")
            (runs / "modules").parent.mkdir(parents=True)
            (runs / "modules").write_text("sql\n")
            for config, leaf, exit_status in (
                ("defaults", "sql-b001-0", "0"),
                ("cse=false", "sql-b005-0", stopped_exit),
            ):
                jvm = runs / config / leaf
                jvm.mkdir(parents=True)
                (runs / config / "name").write_text(config + "\n")
                (jvm / "run.log").write_text("Tests: succeeded 3, failed 0, canceled 0\n")
                (jvm / "fused.tsv").write_text("p.S\tt\t2\n")
                if exit_status is not None:
                    (jvm / "exit").write_text(exit_status + "\n")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                status = varka_matrix_report.main(runs, pathlib.Path(root, "skips.tsv"))
            return status, out.getvalue()

    def test_a_stopped_unit_is_listed_as_not_finished_and_does_not_fail(self):
        status, text = self.report(None)
        self.assertEqual(status, 0, text)
        self.assertIn("cse=false: sql-b005-0 did not finish", text)

    def test_a_unit_that_failed_before_the_stop_still_fails(self):
        self.assertEqual(self.report("1")[0], 1)


class CouplingTest(unittest.TestCase):
    """The names this tool keys on, read from the files that define them."""

    def test_the_matrix_workflow_names_the_step_and_the_annotation(self):
        text = (ROOT / WORKFLOW_PATH).read_text()
        self.assertIn(f"\nname: {WORKFLOW_NAME}\n", text)
        self.assertIn(f"\n    - name: {UNITS_STEP}\n", text)
        self.assertIn(f'name: "{BIN_PREFIX[:-1]}' + '${{ matrix.bin }}"', text)
        self.assertIn(f"::error title={ANNOTATION_TITLE}::", text)
        self.assertIn(f"DEADLINE_FLOOR: '{DEADLINE_FLOOR_SECONDS}'", text)

    def test_the_floor_is_three_lanes_of_the_planner(self):
        text = (ROOT / "dev/varka_matrix_bins.py").read_text()
        self.assertIn("(default 480)", text)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
