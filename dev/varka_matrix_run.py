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
"""What one run of the fanned-out option matrix cost, job by job and suite by suite.

    dev/varka_matrix_run.py RUN_ID [--repo R] [--top N] [--suite NAME]... [--dir DIR]
    dev/varka_matrix_run.py --build RUN_ID [--repo R] [--suite NAME]... [--dir DIR]
    dev/varka_matrix_run.py --self-test

Reads the run's jobs from the Actions API (`gh`) and every bin's uploaded artifact
(`varka-option-matrix-bNNN`, never the build job's large one), and prints: the run's wall time,
the build-and-plan job, the bins' minutes, the report job, the JVM time the units' `seconds`
files record and the share of it inside the suites by their JUnit reports, and the suite hours
under the configurations (the defaults apart), the `--top` largest and each `--suite` named.
`--dir` keeps the downloaded artifacts there; without it they go to a temporary directory that
is removed. Run times are UTC.

With `--build`, RUN_ID is a PR Build of the fork (`build_and_test.yml`): the minutes of its
sql and catalyst test jobs, and each `--suite`'s seconds in their JUnit reports, every `--suite`
named whatever job ran it. A Build runs either the full module jobs (`test-results-*`
artifacts) or, for a change the Varka scope covers, the "Varka suites" jobs
(`varka-scoped-junit-*`).
"""

import argparse
import collections
import json
import os
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET
from datetime import datetime

BIN_PREFIX = "varka-option-matrix-b"
# A Build's sql and catalyst test jobs: the full module jobs, or the Varka-scoped share's.
TEST_JOBS = ("sql - ", "catalyst", "Varka suites: ")


def is_test_report(name):
    """A Build artifact holding sql or catalyst JUnit reports, in either job layout."""
    if name.startswith("test-results-"):
        return "sql--" in name or "catalyst" in name
    return name.startswith("varka-scoped-junit-")


def gh(*args):
    """`gh` with its output, retried: the Actions API answers 502 now and then."""
    for attempt in range(5):
        done = subprocess.run(["gh", *args], capture_output=True, text=True)
        if done.returncode == 0:
            return done.stdout
        if attempt < 4:
            time.sleep(10 * (attempt + 1))
    raise RuntimeError(f"gh {' '.join(args)} failed: {done.stderr.strip()}")


def gh_json(*args):
    return json.loads(gh(*args))


def parse_time(value):
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")


def minutes(job):
    return (parse_time(job["completed_at"]) - parse_time(job["started_at"])).total_seconds() / 60


def read_units(root):
    """Every unit under `root`: (configuration, its seconds file's value, {suite: seconds})."""
    units = []
    for dirpath, _, files in os.walk(root):
        if "seconds" not in files:
            continue
        config = os.path.basename(os.path.dirname(dirpath))
        with open(os.path.join(dirpath, "seconds")) as f:
            text = f.read().strip()
        seconds = float(text) if text else 0.0
        units.append((config, seconds, suite_seconds(os.path.join(dirpath, "junit"))))
    return units


def summarize(units):
    """The JVM seconds, the suites' seconds, and each suite's seconds off the defaults."""
    jvm = sum(s for _, s, _ in units)
    inside = sum(sum(t.values()) for _, _, t in units)
    by_suite = collections.Counter()
    for config, _, suites in units:
        if config != "defaults":
            by_suite.update(suites)
    return jvm, inside, by_suite


def download(repo, run, names, root):
    for name in names:
        gh("run", "download", str(run), "-R", repo, "-n", name, "-D", os.path.join(root, name))


def report(repo, run, top, named, root):
    info = gh_json("api", f"repos/{repo}/actions/runs/{run}")
    jobs = []
    page = 1
    while True:
        got = gh_json("api", f"repos/{repo}/actions/runs/{run}/jobs?per_page=100&page={page}")
        jobs += got["jobs"]
        if len(got["jobs"]) < 100:
            break
        page += 1
    done = [j for j in jobs if j.get("started_at") and j.get("completed_at")]
    bins = [j for j in done if j["name"].startswith("Option matrix: b")]
    build = [j for j in done if "build" in j["name"].lower()]
    rep = [j for j in done if j["name"].endswith(": report")]
    start = parse_time(info["run_started_at"])
    end = max(parse_time(j["completed_at"]) for j in done)
    lines = [f"run {run} on {info['head_sha'][:11]} ({info['head_branch']}), {info['conclusion']}"]
    lines.append(
        f"wall: {round((end - start).total_seconds() / 60)} minutes, "
        f"{start:%H:%M:%S} to {end:%H:%M:%S} UTC"
    )
    for j in build:
        lines.append(f"build and plan job: {minutes(j):.1f} minutes")
    if bins:
        mins = [minutes(j) for j in bins]
        lines.append(
            f"bins: {len(bins)}; minutes median {statistics.median(mins):.1f}, longest "
            f"{max(mins):.1f}, {sum(mins):.0f} job-minutes in all"
        )
    for j in rep:
        lines.append(f"report job: {minutes(j):.1f} minutes")
    arts = []
    page = 1
    while True:
        got = gh_json("api", f"repos/{repo}/actions/runs/{run}/artifacts?per_page=100&page={page}")
        arts += got["artifacts"]
        if len(got["artifacts"]) < 100:
            break
        page += 1
    names = sorted(a["name"] for a in arts if a["name"].startswith(BIN_PREFIX))
    download(repo, run, names, root)
    units = read_units(root)
    jvm, inside, by_suite = summarize(units)
    lines.append(
        f"JVM time: {jvm / 3600:.1f} hours in {len(units)} units, {inside / 3600:.1f} "
        f"inside the suites"
    )
    total = sum(by_suite.values())
    lines.append(f"suite hours under the configurations, the defaults apart: {total / 3600:.2f}")
    cumulative = 0.0
    for suite, seconds in by_suite.most_common(top):
        cumulative += seconds
        lines.append(
            f"  {suite:<38} {seconds / 3600:5.2f} h  {100 * seconds / total:4.1f}%  "
            f"cumulative {100 * cumulative / total:5.1f}%"
        )
    for suite in named:
        lines.append(f"  {suite:<38} {by_suite.get(suite, 0.0) / 3600:5.2f} h  (named)")
    return "\n".join(lines)


def suite_seconds(root):
    """{suite: seconds} over every JUnit report under `root`."""
    seconds = collections.Counter()
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name.startswith("TEST-") and name.endswith(".xml"):
                node = ET.parse(os.path.join(dirpath, name)).getroot()
                for suite in [node] if node.tag == "testsuite" else node.iter("testsuite"):
                    simple = suite.get("name", name).rsplit(".", 1)[-1]
                    seconds[simple] += float(suite.get("time", "0") or 0)
    return seconds


def build_report(repo, run, named, root):
    info = gh_json("api", f"repos/{repo}/actions/runs/{run}")
    jobs = gh_json("api", f"repos/{repo}/actions/runs/{run}/jobs?per_page=100")["jobs"]
    lines = [
        f"Build {run} on {info['head_sha'][:11]} ({info['head_branch']}), {info['conclusion']}"
    ]
    for j in sorted(jobs, key=lambda j: j["name"]):
        if j.get("completed_at") and any(m in j["name"] for m in TEST_JOBS):
            lines.append(f"  {j['name']}: {minutes(j):.1f} minutes")
    arts = gh_json("api", f"repos/{repo}/actions/runs/{run}/artifacts?per_page=100")["artifacts"]
    names = sorted(a["name"] for a in arts if is_test_report(a["name"]))
    download(repo, run, names, root)
    seconds = suite_seconds(root)
    for suite in named:
        lines.append(f"  {suite:<38} {seconds.get(suite, 0.0):7.1f} s")
    return "\n".join(lines)


class SelfTest(unittest.TestCase):
    def unit(self, root, bin_, config, leaf, seconds, suites):
        d = os.path.join(root, bin_, f"{bin_}-u0", "runs", config, leaf)
        os.makedirs(os.path.join(d, "junit"))
        with open(os.path.join(d, "seconds"), "w") as f:
            f.write(str(seconds))
        for suite, spent in suites.items():
            with open(os.path.join(d, "junit", f"TEST-org.x.{suite}.xml"), "w") as f:
                f.write(f'<testsuite name="org.x.{suite}" tests="1" time="{spent}"/>')

    def test_suite_hours_leave_the_defaults_out_and_jvm_time_counts_every_unit(self):
        with tempfile.TemporaryDirectory() as root:
            self.unit(root, "b000", "defaults", "sql", 100, {"A": 60, "B": 30})
            self.unit(root, "b001", "cse=false", "sql-b001-0", 50, {"A": 40})
            self.unit(root, "b002", "cse=false", "catalyst-b002-0", 20, {"C": 10})
            jvm, inside, by_suite = summarize(read_units(root))
            self.assertEqual(jvm, 170)
            self.assertEqual(inside, 140)
            self.assertEqual(dict(by_suite), {"A": 40, "C": 10})

    def test_both_build_layouts_name_their_reports(self):
        self.assertTrue(is_test_report("test-results-sql--slow-tests-17-hadoop3-hive2.3"))
        self.assertTrue(is_test_report("test-results-api--catalyst--hive-thriftserver-17"))
        self.assertTrue(is_test_report("varka-scoped-junit-sql-2of3"))
        self.assertFalse(is_test_report("test-results-core--17-hadoop3-hive2.3"))
        self.assertFalse(is_test_report("varka-matrix-reach-sql-2of3"))


def main(argv):
    if argv == ["--self-test"]:
        return 0 if unittest.main(argv=[argv[0]], exit=False).result.wasSuccessful() else 1
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=int)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--repo", default="MaxGekk/spark")
    parser.add_argument("--top", type=int, default=15)
    parser.add_argument("--suite", action="append", default=[])
    parser.add_argument("--dir")
    args = parser.parse_args(argv)

    def run(root):
        if args.build:
            return build_report(args.repo, args.run, args.suite, root)
        return report(args.repo, args.run, args.top, args.suite, root)

    if args.dir:
        os.makedirs(args.dir, exist_ok=True)
        print(run(args.dir))
        return 0
    root = tempfile.mkdtemp(prefix="varka-matrix-run-")
    try:
        print(run(root))
    finally:
        shutil.rmtree(root)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
