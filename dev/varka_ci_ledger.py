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
"""The ledger of the fork's CI failures and flakes (VARKA-530): every failed test, failed step
and rerun of the Build and the option matrix, one line each, in `sql/varka/matrix/ledger.tsv`.

    dev/varka_ci_ledger.py update [--since YYYY-MM-DD] [--cache DIR]
    dev/varka_ci_ledger.py run RUN_ID [--annotate]
    dev/varka_ci_ledger.py --summary [--days N] [--flaky-threshold N] [--annotate]
    dev/varka_ci_ledger.py --self-test

`update` reads the completed runs of both workflows created since the newest line's date, less
LOOKBACK_DAYS so that a run rerun after it was first read is read again, or since `--since`,
and rewrites every line of each run it read: a rerun turns an earlier attempt's `failed` into
`flaked`, so a run's lines are recomputed whole rather than appended to. A run that passed
leaves no line, so the runs after the newest line are read again on the next update; that costs
one GraphQL query each. `run` prints one run's lines, whatever its status, without writing the
ledger; the option matrix's report job can put its own run's lines in its summary with it.
`--summary` counts the window's lines by test, by configuration, by CPU and by step, and
`--flaky-threshold N` lists the tests with at least N flakes in the window.

What a line's kind means:

  failed   a test failed and no later attempt of its job, nor a later run of the same workflow
           on the same commit, passed
  flaked   a test or a step failed and then passed: in a later attempt of the job, in a later
           run on the same commit, or in the fresh JVM `dev/varka_scoped_suites.sh` retries a
           suite of its `flaky=` list in (its "Flaky suite retried" annotation)
  step     a step that is not a test run failed, and nothing passed it later; `detail` names
           the step and the failure's message
  infra    the runner or GitHub failed rather than the code: a network error from an action
           (ECONNRESET and its kind), a setup step (checkout, Java, a download or an upload),
           or an option-matrix bin's setup step retried (its "Setup step retried" annotation)
  slow     a matrix bin whose tests passed, or none of which failed before the job's time limit
           cancelled it, took SLOW_RATIO times its attempt's median bin: the runner was slow

Where each part of a line comes from, cheapest first: one GraphQL query per run reads every
check run of the run's check suite with its annotations (the Build's "Report test results"
check run names Spark's failed tests, the flaky retry and the setup retry are warnings on jobs
that passed); the jobs listing gives the attempts and the failed steps; a failed matrix bin's
artifact gives its failed tests from the JUnit reports (`failures`) and its
CPU from platform.txt; a failed Varka suites job's log, at most LOG_LIMIT bytes and in practice
about 150 KB since the suites write to files, gives the failed tests the job's report printed;
and the first LOG_HEAD bytes of any other job with a line give the CPU that
`.github/actions/record-runner-platform` printed. Nothing else of a log is read. Every call is a
read; `gh` must be logged in.
"""

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from xml.etree import ElementTree

REPO = "MaxGekk/spark"
HERE = Path(__file__).resolve().parent
LEDGER = HERE.parent / "sql" / "varka" / "matrix" / "ledger.tsv"
FIXTURES = HERE / "varka_ci_ledger_fixtures"
# The workflows read, by file, with the name a line carries.
WORKFLOWS = (("build_main.yml", "Build"), ("varka-option-matrix.yml", "Varka option matrix"))
COLUMNS = (
    "date",
    "run",
    "attempt",
    "commit",
    "workflow",
    "job",
    "configuration",
    "suite",
    "test",
    "kind",
    "cpu",
    "detail",
)
HEADER = """\
# The ledger of the fork's CI failures and flakes (VARKA-530; see dev/varka_ci_ledger.py).
# Tab separated: date, run, attempt, commit, workflow, job, configuration, suite (simple name),
# test, kind (failed, flaked, step, infra or slow), the runner's CPU and a detail: the step, the
# failure's message or what passed it later. A field that does not apply is "-".
# Written by `dev/varka_ci_ledger.py update`; a run's lines are rewritten whole when it is read
# again, so do not edit them by hand.
"""
KINDS = ("failed", "flaked", "step", "infra", "slow")
# A matrix bin is slow when it took this many times its attempt's median bin and at least
# SLOW_MINUTES: the bins are planned to about the same length, so one far over the others ran on
# a slow machine (run 40942811546's b021: every suite 12 to 25 times its usual time on one EPYC
# 9V74 while 25 other bins on the same CPU ran normally).
SLOW_RATIO = 3.0
SLOW_MINUTES = 30
TIMEOUT = re.compile(r"exceeded the maximum execution time")
# A run rerun within this many days of the newest line is read again by the next update.
LOOKBACK_DAYS = 3
# The most of a Varka suites job's log read for its failed tests, and the head of any other
# job's log read for its CPU, which the platform step prints before the tests run.
LOG_LIMIT = 1 << 20
LOG_HEAD = 512 << 10
# The calls an update leaves unspent of each hourly budget, REST and GraphQL.
RESERVE = 1500
DETAIL_WIDTH = 240
# The part of a detail a failure's message may take, leaving room for what passed it later.
MESSAGE_WIDTH = 140

FAILED = ("failure", "timed_out", "startup_failure")
# The jobs that report on a run rather than test it: their failures are the others' lines.
REPORTING = re.compile(r"^(Report test results|Report a failed run)$")
# A matrix bin or an old shard of the matrix, and the step that runs its units.
MATRIX_JOB = re.compile(r"^Option matrix: (b\d+|shard (\d+) of \d+)$")
MATRIX_STEP = re.compile(r"^Run this (bin's units|shard's configurations)$")
VARKA_JOB = re.compile(r"^Run / Varka suites: (catalyst|sql)(?: (\d+) of (\d+))?$")
VARKA_STEP = re.compile(r"^(Run the Varka suites of|The Varka suites of)")
# The jobs whose failed tests the "Report test results" check run names.
SPARK_JOB = re.compile(r"^Run / (Build modules: |Run Docker integration tests$)")
# A failure the runner or GitHub caused rather than the code under test.
INFRA_MESSAGE = re.compile(
    r"ECONNRESET|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|socket hang up|Unable to make request"
    r"|lost communication with the server|received a shutdown signal|HTTP 50[0-9]"
    r"|Bad Gateway|Service Unavailable|API rate limit exceeded",
    re.I,
)
INFRA_STEP = re.compile(
    r"^(Set up job|Checkout|Install Java|Install Python|Cache |Download |Upload |Set up )", re.I
)
GENERIC = re.compile(r"^Process completed with exit code \d+\.?$")
LOG_STAMP = re.compile(r"^\ufeff?\d{4}-\d\d-\d\dT[\d:.]+Z ?")
ANSI = re.compile(r"\x1b\[[0-9;]*m")
CPU_LINE = re.compile(r"^CPU: (.+?), (\d+) cores\s*$")
# The report line `dev/varka_matrix_report.py` prints for a failed test, as the job logs it.
REPORT_FAILED = re.compile(r"^\s+(\S+): - (.+?) \*\*\* FAILED \*\*\*\s*$")
REPORT_ABORTED = re.compile(r"^\s+(\S+): (.+?) \*\*\* ABORTED \*\*\*\s*$")
FLAKY_RETRY = re.compile(r"^(.+?) failed in its JVM \(attempt \d+ of \d+: (.*)\); a fresh JVM")


# Reading the API.


class Gh:
    """Read-only calls through `gh api`, each retried on a failure, since the API answers a
    large listing with a 502 now and then. With a cache directory every response is kept there
    under a hash of its request, and with `offline` only the cache is read: the self-test's
    fixtures are such a directory, saved from real responses and trimmed."""

    def __init__(self, cache=None, offline=False, tries=6):
        self.cache = Path(cache) if cache else None
        self.offline = offline
        self.tries = tries
        self.calls = 0

    def _file(self, key):
        return self.cache / (hashlib.sha1(key.encode()).hexdigest()[:16])

    def _call(self, key, args):
        if self.cache:
            f = self._file(key)
            if f.exists():
                data = f.read_bytes()
                # A recorded text response opens with the license header the release audit
                # asks of a committed file, up to its first blank line.
                if self.offline and data.startswith(b"#"):
                    data = data.split(b"\n\n", 1)[1]
                return data
            if self.offline:
                raise LookupError(f"no recorded response for {key}")
        for n in range(self.tries):
            proc = subprocess.run(["gh", "api", *args], capture_output=True)
            self.calls += 1
            if proc.returncode == 0:
                break
            if n + 1 == self.tries:
                raise RuntimeError(f"gh api {key}: {proc.stderr.decode(errors='replace')}")
            time.sleep(5 * (n + 1))
        if self.cache:
            self.cache.mkdir(parents=True, exist_ok=True)
            self._file(key).write_bytes(proc.stdout)
            with open(self.cache / "index.tsv", "a", encoding="utf-8") as index:
                index.write(f"{self._file(key).name}\t{key}\n")
        return proc.stdout

    def get(self, path):
        return json.loads(self._call(path, [path]))

    def graphql(self, query, **variables):
        key = "graphql " + json.dumps(variables, sort_keys=True)
        args = ["graphql", "-f", "query=" + query]
        for name, value in variables.items():
            if value is not None:
                args += ["-f", f"{name}={value}"]
        return json.loads(self._call(key, args))

    def raw(self, path, first=None, last=None):
        """The response's bytes; `first` bytes of it, or the `last` ones, by a Range header."""
        args = ["--allow-escape-sequences", path]
        key = path
        if first is not None:
            args = ["-H", f"Range: bytes=0-{first - 1}", *args]
            key += f" first={first}"
        elif last is not None:
            args = ["-H", f"Range: bytes=-{last}", *args]
            key += f" last={last}"
        return self._call(key, args)


# A listing's page. The jobs of a matrix run with a rerun attempt, over 150, answer a second page
# of 100 with a 502 every time, and pages of 50 at once.
PAGE = 50


def listing(gh, path, key):
    """Every item of a paged REST listing."""
    items, page = [], 1
    while True:
        sep = "&" if "?" in path else "?"
        body = gh.get(f"{path}{sep}per_page={PAGE}&page={page}")
        chunk = body.get(key, [])
        items += chunk
        if len(chunk) < PAGE:
            return items
        page += 1


CHECK_RUNS = """
query($id: ID!, $after: String) {
  node(id: $id) {
    ... on CheckSuite {
      checkRuns(first: 100, after: $after, filterBy: {checkType: ALL}) {
        pageInfo { hasNextPage endCursor }
        nodes {
          databaseId name conclusion
          annotations(first: 100) { nodes { annotationLevel title message path } }
        }
      }
    }
  }
}
"""


def check_runs(gh, suite_node_id):
    """Each check run of a check suite, by id: its name, conclusion and annotations, every
    attempt's together, in one query a hundred check runs."""
    out, after = {}, None
    while True:
        body = gh.graphql(CHECK_RUNS, id=suite_node_id, after=after)
        runs = ((body.get("data") or {}).get("node") or {}).get("checkRuns")
        if not runs:
            return out
        for node in runs["nodes"]:
            out[node["databaseId"]] = {
                "name": node["name"],
                "conclusion": (node["conclusion"] or "").lower(),
                "annotations": [
                    {
                        "level": (a["annotationLevel"] or "").lower(),
                        "title": a["title"] or "",
                        "message": a["message"] or "",
                        "path": a["path"] or "",
                    }
                    for a in node["annotations"]["nodes"]
                ],
            }
        if not runs["pageInfo"]["hasNextPage"]:
            return out
        after = runs["pageInfo"]["endCursor"]


def artifact_dir(gh, repo, run_id, name, into):
    """Unpack the run's artifact of this name under `into`; False when it is missing or gone."""
    found = gh.get(f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100&name={name}")
    live = [a for a in found.get("artifacts", []) if not a.get("expired")]
    if not live:
        return False
    data = gh.raw(f"repos/{repo}/actions/artifacts/{live[0]['id']}/zip")
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        z.extractall(into)
    return True


# Reading what the responses say.


def one_line(text, width=DETAIL_WIDTH):
    text = " ".join(str(text).replace("\t", " ").split())
    return text if len(text) <= width else text[: width - 3] + "..."


def log_lines(data):
    text = data.decode("utf-8", errors="replace")
    return [LOG_STAMP.sub("", ANSI.sub("", line)) for line in text.splitlines()]


def cpu_of(lines):
    """The CPU the platform step printed, `model, N cores`, or None."""
    for line in lines:
        m = CPU_LINE.match(line)
        if m:
            model = re.sub(r"\s+(Processor|CPU @.*)$", "", m.group(1).strip())
            return f"{model}, {m.group(2)} cores"
    return None


def report_failures(lines):
    """(configuration, test) of each failed test the matrix report printed in a job's log."""
    found = []
    for line in lines:
        m = REPORT_FAILED.match(line) or REPORT_ABORTED.match(line)
        if m and (m.group(1), m.group(2)) not in found:
            found.append((m.group(1), m.group(2)))
    return found


def failures(junit_dirs):
    """The failed and errored tests of the JUnit reports under
    `<dir>/<configuration>/<module>-.../junit/TEST-*.xml`, as sorted (configuration, suite, test)
    triples, the suite by its simple name. The configuration is the name its directory's `name`
    file holds where there is one, the directory's own name otherwise. The report is parsed as
    XML, since ScalaTest puts a tag's attributes on the line after the tag and JUnit 5 on the
    tag's own line."""
    found = set()
    for d in junit_dirs:
        for xml in Path(d).glob("**/*/*/junit/TEST-*.xml"):
            try:
                root = ElementTree.parse(xml).getroot()
            except ElementTree.ParseError:
                continue
            config_dir = xml.parent.parent.parent
            name_file = config_dir / "name"
            config = (
                name_file.read_text(encoding="utf-8").strip()
                if name_file.exists()
                else config_dir.name
            )
            for case in root.iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    suite = (case.get("classname") or root.get("name") or "").rsplit(".", 1)[-1]
                    found.add((config, suite, case.get("name") or ""))
    return sorted(found)


def run_log_failures(root):
    """(configuration, suite, test) of each failed test in the ScalaTest logs under a matrix
    artifact, `runs/<configuration>/<jvm>/run.log`: the layout's fallback where it kept no JUnit
    report. A suite's tests follow the line that names it."""
    found = []
    for log in sorted(Path(root).glob("**/runs/*/*/run.log")):
        config_dir = log.parent.parent
        name = config_dir / "name"
        config = name.read_text().strip() if name.exists() else config_dir.name
        suite = "-"
        for line in log_lines(log.read_bytes()):
            head = re.match(r"^(\w+Suite):$", line)
            if head:
                suite = head.group(1)
                continue
            m = re.match(r"^- (.+?) \*\*\* FAILED \*\*\*\s*$", line)
            if m and (config, suite, m.group(1)) not in found:
                found.append((config, suite, m.group(1)))
    return found


def timed_out(job, annotations):
    """Whether the job hit its time limit: the API calls such a job cancelled, and only its
    annotation tells it from a cancel by hand or by a newer push."""
    return job.get("conclusion") == "timed_out" or (
        job.get("conclusion") == "cancelled"
        and any(TIMEOUT.search(a["message"]) for a in annotations)
    )


def minutes(job):
    fmt = "%Y-%m-%dT%H:%M:%SZ"
    if not job.get("started_at") or not job.get("completed_at"):
        return None
    start = datetime.strptime(job["started_at"], fmt)
    return (datetime.strptime(job["completed_at"], fmt) - start).total_seconds() / 60


def first_failed_step(job):
    for step in job.get("steps") or []:
        if step.get("conclusion") in FAILED or (
            job.get("conclusion") in ("timed_out", "cancelled")
            and step.get("conclusion") == "cancelled"
        ):
            return step["name"]
    return "-"


def failure_message(annotations):
    """The first failure annotation that says more than the exit code, or ''."""
    for a in annotations:
        if a["level"] == "failure" and not GENERIC.match(a["message"].strip()):
            return a["message"]
    return ""


def flaky_retries(annotations):
    """(suites, tests) of each "Flaky suite retried" warning: a suite of the `flaky=` list that
    failed in its JVM and was run again in a fresh one."""
    out = []
    for a in annotations:
        if a["title"] != "Flaky suite retried":
            continue
        m = FLAKY_RETRY.match(" ".join(a["message"].split()))
        if not m:
            continue
        tests = [t.strip().strip('"') for t in m.group(2).split('"; "')]
        out.append((m.group(1).strip(), [t for t in tests if t]))
    return out


def setup_retries(annotations):
    """The setup steps a matrix bin retried, from its "Setup step retried" warning."""
    out = []
    for a in annotations:
        if a["title"] == "Setup step retried":
            out += [p.strip() for p in a["message"].split(";") if p.strip()]
    return out


# Building a run's lines.


class Run:
    """One run's lines, computed from its responses."""

    def __init__(self, gh, repo, run, workflow):
        self.gh, self.repo, self.run, self.workflow = gh, repo, run, workflow
        self.lines = []
        self._cpu = {}

    def line(self, job, config, suite, test, kind, detail="-", cpu=None):
        # One line a test of a job's attempt, whichever source names it first; a later source
        # that knows the suite fills it in.
        key = (job["run_attempt"], job["name"], config or "-", test or "-")
        for existing in self.lines:
            if existing["_key"] == key:
                if existing["suite"] == "-" and suite:
                    existing["suite"] = suite
                return
        self.lines.append(
            {
                "_key": key,
                "_job": job,
                "date": (job.get("completed_at") or self.run["updated_at"])[:10],
                "run": str(self.run["id"]),
                "attempt": str(job["run_attempt"]),
                "commit": self.run["head_sha"][:11],
                "workflow": self.workflow,
                "job": job["name"],
                "configuration": config or "-",
                "suite": suite or "-",
                "test": test or "-",
                "kind": kind,
                "cpu": cpu,
                "detail": one_line(detail) if detail else "-",
            }
        )

    def log(self, job, first):
        return log_lines(
            self.gh.raw(f"repos/{self.repo}/actions/jobs/{job['id']}/logs", first=first)
        )

    def cpu(self, job, annotations):
        if job["id"] not in self._cpu:
            cpu = None
            for a in annotations:
                m = re.match(r"^Runner CPU: (.+)$", a["message"].strip())
                if m:
                    cpu = re.sub(r"\s+Processor$", "", m.group(1).strip())
            if cpu is None:
                try:
                    cpu = cpu_of(self.log(job, LOG_HEAD))
                except (RuntimeError, LookupError):
                    cpu = None
            self._cpu[job["id"]] = cpu or "-"
        return self._cpu[job["id"]]

    def compute(self):
        checks = check_runs(self.gh, self.run["check_suite_node_id"])
        notes = any(
            flaky_retries(c["annotations"]) or setup_retries(c["annotations"])
            for c in checks.values()
        )
        failed = any(c["conclusion"] in FAILED for c in checks.values())
        if not (failed or notes or self.run.get("run_attempt", 1) > 1):
            return []
        jobs = listing(
            self.gh, f"repos/{self.repo}/actions/runs/{self.run['id']}/jobs?filter=all", "jobs"
        )
        for job in jobs:
            annotations = checks.get(job["id"], {}).get("annotations", [])
            if REPORTING.match(job["name"]):
                continue
            if job.get("conclusion") in FAILED or timed_out(job, annotations):
                self.failed_job(job, annotations, jobs, checks)
            for suites, tests in flaky_retries(annotations):
                kind = "flaked" if job.get("conclusion") == "success" else "failed"
                for test in tests:
                    self.line(job, "defaults", suites, test, kind, "retried in a fresh JVM", None)
            for retry in setup_retries(annotations):
                self.line(job, "-", "-", "-", "infra", "setup step " + retry, None)
        self.slow_bins(jobs, checks)
        # The matrix's report fails whenever a bin did; its own line is kept only for a failure
        # no bin shows, an unfused or stale skip line.
        tested = {ln["attempt"] for ln in self.lines if ln["job"] != "Option matrix: report"}
        self.lines = [
            ln
            for ln in self.lines
            if not (ln["job"] == "Option matrix: report" and ln["attempt"] in tested)
        ]
        self.mark_reruns(jobs)
        for ln in self.lines:
            if ln["cpu"] is None:
                job = ln["_job"]
                ln["cpu"] = self.cpu(job, checks.get(job["id"], {}).get("annotations", []))
        return self.lines

    def failed_job(self, job, annotations, jobs, checks):
        step = first_failed_step(job)
        message = failure_message(annotations)
        before = len(self.lines)
        matrix = MATRIX_JOB.match(job["name"])
        varka = VARKA_JOB.match(job["name"])
        if matrix and MATRIX_STEP.match(step):
            self.matrix_failures(job, matrix)
        elif varka and VARKA_STEP.match(step):
            self.varka_failures(job, varka)
        elif SPARK_JOB.match(job["name"]) and step == "Run tests":
            self.spark_failures(job, jobs, checks)
        if len(self.lines) > before:
            return
        infra = INFRA_MESSAGE.search(message) or INFRA_STEP.match(step)
        detail = step + (": " + one_line(message, MESSAGE_WIDTH) if message else "")
        self.line(job, "-", "-", "-", "infra" if infra else "step", detail)

    def matrix_failures(self, job, matrix):
        unit = matrix.group(1)
        shard = matrix.group(2)
        name = "varka-option-matrix-" + (unit if shard is None else f"shard-{shard}")
        with tempfile.TemporaryDirectory() as d:
            if not artifact_dir(self.gh, self.repo, self.run["id"], name, d):
                return
            found = failures([d]) or run_log_failures(d)
            platform = [p.read_bytes() for p in sorted(Path(d).glob("**/platform.txt"))]
            cpu = cpu_of(log_lines(b"\n".join(platform))) or None
            for config, suite, test in found:
                self.line(job, config, suite, test, "failed", "-", cpu)

    def varka_failures(self, job, varka):
        lines = self.log(job, LOG_LIMIT)
        cpu = cpu_of(lines)
        self._cpu[job["id"]] = cpu or "-"
        suites = {}
        slug = f"-{varka.group(2)}of{varka.group(3)}" if varka.group(2) else ""
        with tempfile.TemporaryDirectory() as d:
            name = f"varka-scoped-junit-{varka.group(1)}{slug}"
            if artifact_dir(self.gh, self.repo, self.run["id"], name, d):
                for _, suite, test in failures([d]):
                    suites[test] = suite
        for config, test in report_failures(lines):
            self.line(job, config, suites.get(test, "-"), test, "failed", "-", cpu or "-")

    def spark_failures(self, job, jobs, checks):
        """Spark's failed tests, from the annotations of the "Report test results" check run of
        the same attempt, given to the one module job that failed its tests in that attempt."""
        failing = [
            j
            for j in jobs
            if j["run_attempt"] == job["run_attempt"]
            and SPARK_JOB.match(j["name"])
            and j.get("conclusion") in FAILED
            and first_failed_step(j) == "Run tests"
        ]
        if len(failing) != 1:
            return
        for j in jobs:
            if j["name"] != "Report test results" or j["run_attempt"] != job["run_attempt"]:
                continue
            for a in checks.get(j["id"], {}).get("annotations", []):
                if a["level"] != "failure" or "." not in a["title"]:
                    continue
                suite, test = a["title"].split(".", 1)
                self.line(job, "-", suite, test, "failed", a["message"][:120])

    def slow_bins(self, jobs, checks):
        """A matrix bin far over its attempt's median bin, its tests having passed or none
        having failed before its time limit."""
        by_attempt = {}
        for j in jobs:
            m = MATRIX_JOB.match(j["name"])
            if m and minutes(j) is not None and j.get("conclusion") in ("success", "cancelled"):
                annotations = checks.get(j["id"], {}).get("annotations", [])
                # A bin a newer push cancelled stopped early and says nothing of its length.
                if j.get("conclusion") == "success" or timed_out(j, annotations):
                    by_attempt.setdefault(j["run_attempt"], []).append(j)
        for attempt, bins in by_attempt.items():
            # A rerun attempt holds the bins it copied too, so its median is the run's.
            took = sorted(minutes(j) for j in bins)
            median = took[len(took) // 2]
            for j in bins:
                annotations = checks.get(j["id"], {}).get("annotations", [])
                if j.get("conclusion") == "cancelled" and not timed_out(j, annotations):
                    continue
                if any(ln["_job"]["id"] == j["id"] and ln["kind"] == "failed" for ln in self.lines):
                    continue
                m = minutes(j)
                if m >= SLOW_MINUTES and m >= SLOW_RATIO * median:
                    # The time limit's line is this one: drop the step line it left.
                    self.lines = [ln for ln in self.lines if ln["_job"]["id"] != j["id"]]
                    end = (
                        "cancelled at its time limit"
                        if j.get("conclusion") == "cancelled"
                        else "passed"
                    )
                    self.line(
                        j,
                        "-",
                        "-",
                        "-",
                        "slow",
                        f"{m:.0f} min against the attempt's median bin {median:.0f} min; {end}",
                    )

    def mark_reruns(self, jobs):
        """A failure whose job passed in a later attempt flaked; infra stays infra."""
        passed = {}
        for j in jobs:
            if j.get("conclusion") == "success":
                passed.setdefault(j["name"], []).append(j["run_attempt"])
        for ln in self.lines:
            later = [a for a in passed.get(ln["job"], []) if a > int(ln["attempt"])]
            if not later:
                continue
            note = f"passed on attempt {min(later)}"
            if ln["kind"] in ("failed", "step"):
                ln["kind"] = "flaked"
            ln["detail"] = note if ln["detail"] == "-" else one_line(ln["detail"] + "; " + note)


def mark_same_commit(lines, runs):
    """A failure of a run that a later run of the same workflow on the same commit passed flaked
    too: the code did not change and the second run is the rerun."""
    green = {}
    for run, workflow in runs:
        if run.get("conclusion") == "success":
            green.setdefault((workflow, run["head_sha"][:11]), []).append(run)
    for ln in lines:
        if ln["kind"] not in ("failed", "step"):
            continue
        later = [
            r for r in green.get((ln["workflow"], ln["commit"]), []) if r["id"] > int(ln["run"])
        ]
        if later:
            ln["kind"] = "flaked"
            note = f"run {later[0]['id']} on the same commit passed"
            ln["detail"] = note if ln["detail"] == "-" else one_line(ln["detail"] + "; " + note)


# The ledger file.


def read_ledger(path):
    lines = []
    if Path(path).exists():
        for raw in Path(path).read_text(encoding="utf-8").splitlines():
            if not raw.strip() or raw.startswith("#"):
                continue
            fields = raw.split("\t")
            if len(fields) != len(COLUMNS):
                raise ValueError(f"{path}: {len(fields)} fields, not {len(COLUMNS)}: {raw}")
            lines.append(dict(zip(COLUMNS, fields)))
    return lines


def sort_key(ln):
    return (ln["date"], int(ln["run"]), int(ln["attempt"]), ln["job"], ln["suite"], ln["test"])


def render(lines):
    rows = sorted(lines, key=sort_key)
    return HEADER + "".join("\t".join(ln[c] for c in COLUMNS) + "\n" for ln in rows)


def listed_runs(gh, repo, since):
    """The completed runs of both workflows created on or after `since`, with the workflow's
    name a line carries."""
    out = []
    for file, workflow in WORKFLOWS:
        path = f"repos/{repo}/actions/workflows/{file}/runs?status=completed&created=>={since}"
        out += [(r, workflow) for r in listing(gh, path, "workflow_runs")]
    return out


def update(gh, repo, ledger, since=None, log=print):
    old = read_ledger(ledger)
    if since is None:
        newest = max((ln["date"] for ln in old), default=None)
        if newest is None:
            raise SystemExit("an empty ledger needs --since YYYY-MM-DD")
        since = (date.fromisoformat(newest) - timedelta(days=LOOKBACK_DAYS)).isoformat()
    runs = listed_runs(gh, repo, since)
    read_ids, new = set(), []
    for n, (run, workflow) in enumerate(runs, 1):
        if run.get("conclusion") == "skipped":
            continue
        lines = Run(gh, repo, run, workflow).compute()
        read_ids.add(str(run["id"]))
        new += lines
        if lines:
            log(f"{n}/{len(runs)} run {run['id']} ({workflow}): {len(lines)} line(s)")
    kept = [ln for ln in old if ln["run"] not in read_ids]
    lines = kept + [{c: ln[c] for c in COLUMNS} for ln in new]
    mark_same_commit(lines, runs)
    Path(ledger).write_text(render(lines), encoding="utf-8")
    log(f"read {len(read_ids)} runs since {since} in {gh.calls} calls; {len(lines)} lines")
    return lines


# The summary.


def window(lines, days, today=None):
    today = today or datetime.now(timezone.utc).date()
    first = (today - timedelta(days=days)).isoformat()
    return [ln for ln in lines if ln["date"] >= first]


def counts(lines, key):
    table = {}
    for ln in lines:
        row = table.setdefault(key(ln), dict.fromkeys(KINDS, 0))
        row[ln["kind"]] += 1
    return sorted(table.items(), key=lambda kv: (-kv[1]["flaked"], -sum(kv[1].values()), kv[0]))


def flaky_tests(lines, threshold):
    """(suite, test, flakes, runs) of the tests with at least `threshold` flakes."""
    tests = {}
    for ln in lines:
        if ln["kind"] == "flaked" and ln["test"] != "-":
            tests.setdefault((ln["suite"], ln["test"]), set()).add(ln["run"])
    flakes = {
        k: sum(1 for ln in lines if (ln["suite"], ln["test"]) == k and ln["kind"] == "flaked")
        for k in tests
    }
    return [
        (suite, test, flakes[(suite, test)], sorted(runs))
        for (suite, test), runs in sorted(tests.items())
        if flakes[(suite, test)] >= threshold
    ]


def summary(lines, days, top=15, today=None):
    lines = window(lines, days, today)
    out = [
        f"{len(lines)} lines over the last {days} days: "
        + ", ".join(f"{sum(1 for ln in lines if ln['kind'] == k)} {k}" for k in KINDS)
    ]

    def table(title, rows, label):
        out.append("")
        out.append(f"{title:66} {'flaked':>6} {'failed':>6} {'step':>4} {'infra':>5} {'slow':>4}")
        for key, row in rows[:top]:
            name = one_line(label(key), 66)
            out.append(
                f"{name:66} {row['flaked']:>6} {row['failed']:>6} {row['step']:>4} "
                f"{row['infra']:>5} {row['slow']:>4}"
            )

    tests = [ln for ln in lines if ln["test"] != "-"]
    table(
        "by test (suite / test)",
        counts(tests, lambda ln: (ln["suite"], ln["test"])),
        lambda k: f"{k[0]} / {k[1]}",
    )
    table("by configuration", counts(lines, lambda ln: ln["configuration"]), str)
    table("by CPU", counts(lines, lambda ln: ln["cpu"]), str)
    steps = [ln for ln in lines if ln["test"] == "-"]
    table(
        "by job and step (no test named)",
        counts(steps, lambda ln: (ln["job"], ln["detail"].split(":")[0].split(";")[0])),
        lambda k: f"{k[0]} | {k[1]}",
    )
    return "\n".join(out)


# The tests, over responses recorded from the fork's API and trimmed (varka_ci_ledger_fixtures).


class SelfTest(unittest.TestCase):
    def lines(self, run_id, workflow):
        gh = Gh(FIXTURES, offline=True)
        run = gh.get(f"repos/{REPO}/actions/runs/{run_id}")
        return Run(gh, REPO, run, workflow).compute()

    def test_an_upload_reset_rerun_to_green_is_infra_noting_the_rerun(self):
        # Build run 40947407586: two Varka suites jobs failed uploading their JUnit reports
        # with ECONNRESET and passed when rerun as attempt 2.
        got = self.lines(40947407586, "Build")
        self.assertEqual(
            sorted((ln["job"], ln["kind"], ln["attempt"]) for ln in got),
            [
                ("Run / Varka suites: catalyst 3 of 3", "infra", "1"),
                ("Run / Varka suites: sql 1 of 3", "infra", "1"),
            ],
        )
        for ln in got:
            self.assertIn("Upload the Varka suites' JUnit reports: Failed to", ln["detail"])
            self.assertIn("passed on attempt 2", ln["detail"])
            self.assertEqual(ln["cpu"], "AMD EPYC 7763 64-Core, 4 cores")

    def test_a_matrix_bin_names_its_configuration_suite_test_and_cpu(self):
        # Option matrix run 40834493709: bin b065 failed one coverage row under
        # arrowBatchSize=1, on a 256-bit EPYC 7763; no attempt passed it.
        got = self.lines(40834493709, "Varka option matrix")
        self.assertEqual(len(got), 1)
        ln = got[0]
        self.assertEqual(
            (ln["job"], ln["configuration"], ln["suite"], ln["kind"], ln["cpu"]),
            (
                "Option matrix: b065",
                "arrowBatchSize=1",
                "VarkaCoverageHarnessSuite",
                "failed",
                "AMD EPYC 7763 64-Core, 4 cores",
            ),
        )
        self.assertTrue(ln["test"].startswith("TIMESTAMP_NTZ columns choosing values: CAST("))

    def test_a_spark_test_failure_is_named_by_the_report_check_run(self):
        # Build run 40031251245: Kafka's real-time-mode aggregation test timed out, and the UI
        # tests' Jest step failed with no report to name its test.
        got = {
            (ln["job"], ln["suite"], ln["test"], ln["kind"])
            for ln in self.lines(40031251245, "Build")
        }
        kafka = [k for k in got if k[1] == "KafkaRealTimeModeAggregationSuite"]
        self.assertEqual([k[2:] for k in kafka], [("tumbling window min", "failed")])
        self.assertTrue(kafka[0][0].startswith("Run / Build modules: streaming, sql-kafka"))
        self.assertIn(("Run / Run Spark UI tests", "-", "-", "step"), got)

    def test_a_flaky_suite_retried_in_a_fresh_jvm_is_a_flake(self):
        # Build run 39148626990: VarkaWarmupEndToEndSuite failed in two JVMs and in the third,
        # so the job failed: each retried test is a failure, not a flake.
        got = self.lines(39148626990, "Build")
        warm = [ln for ln in got if ln["suite"] == "VarkaWarmupEndToEndSuite"]
        self.assertTrue(warm)
        self.assertEqual({ln["kind"] for ln in warm}, {"failed"})
        # The same warning on a job that passed is a flake.
        ann = [
            {
                "level": "warning",
                "title": "Flaky suite retried",
                "path": ".github",
                "message": 'S failed in its JVM (attempt 1 of 3: "a"; "b c"); a fresh JVM follows',
            }
        ]
        self.assertEqual(flaky_retries(ann), [("S", ["a", "b c"])])

    def test_a_bin_far_over_the_median_is_a_slow_runner(self):
        # Option matrix run 40942811546: bin b021 ran on an EPYC 9V74 at 12 to 25 times its
        # suites' usual time, no test failed, and its 90-minute limit cancelled it; the
        # median bin took 8 minutes.
        got = self.lines(40942811546, "Varka option matrix")
        self.assertEqual(
            [(ln["job"], ln["kind"], ln["cpu"]) for ln in got],
            [("Option matrix: b021", "slow", "AMD EPYC 9V74 80-Core, 4 cores")],
        )
        self.assertIn("91 min against the attempt's median bin 8 min", got[0]["detail"])

    def test_failures_name_the_configuration_suite_and_test(self):
        with tempfile.TemporaryDirectory() as root:
            d = Path(root, "b001-u0", "runs", "x=1", "sql-b001-0", "junit")
            d.mkdir(parents=True)
            Path(root, "b001-u0", "runs", "x=1", "name").write_text("x=1\n", encoding="utf-8")
            # ScalaTest's layout: the attributes on the line after the tag.
            Path(d, "TEST-org.x.A.xml").write_text(
                '<?xml version="1.0"?>\n<testsuite\n name="org.x.A" failures="1">\n'
                '<testcase\n classname="org.x.A" name="t &amp; u"><failure message="m"/>'
                '</testcase>\n<testcase classname="org.x.A" name="ok"/>\n</testsuite>\n',
                encoding="utf-8",
            )
            # A configuration directory without a name file is named by itself.
            e = Path(root, "runs", "defaults", "catalyst-0", "junit")
            e.mkdir(parents=True)
            Path(e, "TEST-org.y.B.xml").write_text(
                '<testsuite name="org.y.B"><testcase classname="org.y.B" name="e">'
                "<error/></testcase></testsuite>",
                encoding="utf-8",
            )
            self.assertEqual(failures([root]), [("defaults", "B", "e"), ("x=1", "A", "t & u")])

    def test_a_later_run_on_the_same_commit_turns_a_failure_into_a_flake(self):
        lines = [
            {"run": "1", "workflow": "W", "commit": "abcdef", "kind": "failed", "detail": "-"},
            {"run": "1", "workflow": "W", "commit": "abcdef", "kind": "infra", "detail": "-"},
        ]
        runs = [
            ({"id": 2, "head_sha": "abcdef", "conclusion": "success"}, "W"),
            ({"id": 3, "head_sha": "abcdef", "conclusion": "success"}, "V"),
        ]
        mark_same_commit(lines, runs)
        self.assertEqual([ln["kind"] for ln in lines], ["flaked", "infra"])
        self.assertEqual(lines[0]["detail"], "run 2 on the same commit passed")

    def test_the_ledger_round_trips_and_the_summary_counts_by_test(self):
        def ln(run, kind, test="t", cpu="X, 4 cores"):
            return {
                "date": "2026-10-20",
                "run": run,
                "attempt": "1",
                "commit": "c",
                "workflow": "Build",
                "job": "j",
                "configuration": "defaults",
                "suite": "S",
                "test": test,
                "kind": kind,
                "cpu": cpu,
                "detail": "-",
            }

        rows = [ln("2", "flaked"), ln("1", "flaked"), ln("3", "failed"), ln("4", "step", "-")]
        with tempfile.TemporaryDirectory() as d:
            f = Path(d, "ledger.tsv")
            f.write_text(render(rows), encoding="utf-8")
            back = read_ledger(f)
        self.assertEqual([r["run"] for r in back], ["1", "2", "3", "4"])
        self.assertEqual(flaky_tests(back, 2), [("S", "t", 2, ["1", "2"])])
        self.assertEqual(flaky_tests(back, 3), [])
        text = summary(back, 14, today=date(2026, 10, 29))
        self.assertIn("4 lines over the last 14 days: 1 failed, 2 flaked, 1 step, 0 infra", text)
        self.assertRegex(text, r"S / t +2 +1 +0 +0")

    def test_the_committed_ledger_parses(self):
        self.assertTrue(all(ln["kind"] in KINDS for ln in read_ledger(LEDGER)))


def main(argv):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("cmd", nargs="?", choices=("update", "run"))
    p.add_argument("run_id", nargs="?", type=int)
    p.add_argument("--since", help="read the runs created on or after this date")
    p.add_argument("--repo", default=REPO)
    p.add_argument("--ledger", default=str(LEDGER))
    p.add_argument("--cache", help="keep every response in this directory, and reuse it")
    p.add_argument("--summary", action="store_true")
    p.add_argument("--days", type=int, default=14)
    p.add_argument("--flaky-threshold", type=int)
    p.add_argument("--annotate", action="store_true", help="also print GitHub warnings")
    p.add_argument("--self-test", action="store_true")
    args = p.parse_args(argv)
    if args.self_test:
        result = unittest.main(argv=[sys.argv[0]], exit=False, verbosity=0).result
        print("varka_ci_ledger selftest: " + ("ok" if result.wasSuccessful() else "FAILED"))
        return 0 if result.wasSuccessful() else 1
    gh = Gh(args.cache)
    if args.cmd == "update":
        limits = gh.get("rate_limit")["resources"]
        for name in ("core", "graphql"):
            if limits[name]["remaining"] < RESERVE:
                raise SystemExit(f"{limits[name]['remaining']} {name} calls left this hour")
        update(gh, args.repo, args.ledger, args.since, log=lambda s: print(s, file=sys.stderr))
    elif args.cmd == "run":
        if args.run_id is None:
            p.error("run needs a RUN_ID")
        run = gh.get(f"repos/{args.repo}/actions/runs/{args.run_id}")
        name = dict(WORKFLOWS).get(os.path.basename(run["path"]), run["name"])
        lines = Run(gh, args.repo, run, name).compute()
        for ln in sorted(lines, key=sort_key):
            print("\t".join(ln[c] for c in COLUMNS))
            if args.annotate and ln["kind"] in ("failed", "flaked"):
                print(
                    f"::warning title=CI ledger: {ln['kind']}::{ln['suite']} / {ln['test']}"
                    f" under {ln['configuration']} on {ln['cpu']}"
                )
    lines = read_ledger(args.ledger)
    if args.summary:
        print(summary(lines, args.days))
    if args.flaky_threshold is not None:
        flaky = flaky_tests(window(lines, args.days), args.flaky_threshold)
        print(
            f"\ntests with at least {args.flaky_threshold} flakes in {args.days} days: {len(flaky)}"
        )
        for suite, test, n, runs in flaky:
            print(f"  {suite} / {test}: {n} flakes, runs {' '.join(runs)}")
            if args.annotate:
                print(
                    f"::warning title=Flaky test::{suite} / {test}: {n} flakes in {args.days} days"
                )
    if not (args.cmd or args.summary or args.flaky_threshold is not None):
        p.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
