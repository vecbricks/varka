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
"""
Finds the pull request Build's archive of the test classes for the option matrix's build job
(VARKA-507), so the matrix need not compile them again.

A push that builds catalyst or sql runs the Build's varka-scoped jobs (all of them when it is
confined to Varka's files, sql's beside Spark's module matrix otherwise), and a job of their own
beside them ("Varka option matrix classes", VARKA-519) archives sql's test classes, whose classpath
holds catalyst's too, as `varka-option-matrix-build` (dev/varka_matrix_archive.sh) right after
building them. This prints `run=<id>` of the Build run of the commit that has the archive, waiting
while a run of that commit may still upload one, or `run=` when none will, with `missing=` naming
which: `classes` when a Build's classes job ran and uploaded nothing; `late` when it was still
running after --wait minutes; `admit` when the Build was still waiting in its admit job (the fork's
queue) after 30 minutes; `none` when no Build of the commit ran the job - none yet, one whose
change builds neither catalyst nor sql or holds only documents, one held by ci-hold, or one whose
job has not started within five minutes of the first poll or of its run leaving the admit job.
`reason=` says it in a sentence. The matrix compiles for itself then. A run lists its classes
job a few seconds after its admit job succeeds or is skipped, so for two minutes after the first
poll that sees the precondition and the admit done and no such job the run still counts as
pending.

The same Build's varka-scoped sql jobs record, as they run the suites at the defaults, which
configurations move each test (VarkaTestReach), one `varka-matrix-reach-<job>` artifact a job.
`reach RUN` prints `reach=1` when every such job of the run has succeeded with its artifact, so
the matrix may skip what the record allows, and `reach=` otherwise: a configuration then runs
every test. It waits up to --wait minutes (10 by default) while those jobs still run: the
classes are uploaded about four minutes into a Build and the sql jobs finish four to six minutes
after that, and the record saves about 136 bin-minutes (m8/results/VARKA-matrix-bins-1030.txt,
sections 1 and 5). `reach_state=` says why there is no record: `running` (the jobs had not
finished by then), `failed` (a job failed, which ends the wait at once, or left no artifact),
`absent` (the run has none) or `error` (the API failed on four polls in a row).

Artifacts are looked up by their exact name (`artifacts?name=`), one request each, rather than
by paging through a Build's several dozen on every 30 s poll, and the reach artifacts are not
read at all while a sql job still runs. Job listings read every page: a run of Spark's module
matrix lists more jobs than one page holds.

  dev/varka_matrix_reuse.py REPOSITORY SHA [--wait MINUTES]    # in a step, with GH_TOKEN set
  dev/varka_matrix_reuse.py reach REPOSITORY RUN [--wait MINUTES]
  dev/varka_matrix_reuse.py --selftest
"""

import argparse
import json
import subprocess
import sys
import time
import unittest

ARTIFACT = "varka-option-matrix-build"
WORKFLOW = ".github/workflows/build_main.yml"
JOB = "Varka option matrix classes"
UPLOAD = "Upload the test classes for the option matrix"
# The scoped jobs that record: the sql ones (build_and_test.yml says why not the catalyst ones).
SCOPED = "Varka suites: sql "
REACH = "varka-matrix-reach-"
PRECONDITION = "Check changes"
# The fork's queue (dev/varka_ci_admit.py ADMIT_JOB_NAME). In a run of Spark's module matrix the
# classes job needs it, and the jobs API may not list a job until what it needs has finished.
ADMIT = "Admit a full-matrix run"
# How long a run whose admit has succeeded or was skipped gets to list its classes job, which the
# API creates a few seconds later; until then the run is pending, not absent.
ADMIT_GRACE_SECONDS = 120.0
# How long a run still waiting in its admit job is waited for. A matrix that gives up then
# compiles for itself in about 12 minutes, inside its build job's 60.
ADMIT_WAIT_MINUTES = 30.0
# How long a Build gets to start the job that builds the classes: a run not yet created, a
# precondition still deciding or a job still queued behind a full fork. Only a job that has
# started compiling earns the whole wait, since a queued one may hold the matrix past the time it
# takes to compile.
START_MINUTES = 5.0
# The API's largest page.
PAGE = 100
# The reach wait retries a failed poll this many times, this many seconds apart, then gives up.
RETRIES = 3
RETRY_SECONDS = 10
# What a failed `gh api` poll raises: the command failing, or a body that is not the JSON asked.
TRANSIENT = (subprocess.CalledProcessError, KeyError, ValueError)


def gh(path):
    out = subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True)
    return json.loads(out.stdout)


def gh_all(path, key):
    """Every item of a listing under `key`, read 100 to a page until a page comes back short."""
    items, page = [], 1
    sep = "&" if "?" in path else "?"
    while True:
        got = gh(f"{path}{sep}per_page={PAGE}&page={page}")[key]
        items.extend(got)
        if len(got) < PAGE:
            return items
        page += 1


def named(repository, run, name):
    """The run's artifacts called `name`, by the API's exact-name filter: one request."""
    return gh(f"repos/{repository}/actions/runs/{run}/artifacts?name={name}")["artifacts"]


def holds(artifacts):
    """Whether the artifacts include the archive, unexpired."""
    return any(a["name"] == ARTIFACT and not a.get("expired") for a in artifacts)


def verdict(run, artifacts, jobs, again=lambda: ()):
    """What one Build run says: `ready` when it holds the archive, `started` while its classes
    job runs towards the upload, `pending` while that job is not running yet, `waiting` while
    the run waits in its admit job, `unlisted` when the precondition is done and the admit has
    succeeded, was skipped or is not listed, but the job is not listed yet, `failed` when the
    job ran and will not upload one, `absent` when the run has no such job, skipped it or was
    held in its admit. The artifacts are read before the jobs, so an upload that completes
    between the two reads shows as done with no archive: `again()` reads the artifacts once
    more then, before the run is judged `failed`."""
    if holds(artifacts):
        return "ready"
    names = {j["name"].split(" / ")[-1]: j for j in jobs}
    job = names.get(JOB)
    ran = job is not None and job.get("conclusion") not in (None, "skipped")
    upload = next((st for st in (job or {}).get("steps", []) if st["name"] == UPLOAD), None)
    if upload and upload["status"] == "completed" and holds(again()):
        return "ready"
    if run["status"] == "completed":
        return "failed" if ran else "absent"
    if job is None:
        # The scoped jobs are created once the precondition decides them, or, beside Spark's
        # module matrix, once the admit job lets the run go on.
        admit = names.get(ADMIT)
        if admit and admit["status"] != "completed":
            return "waiting"
        if admit and admit.get("conclusion") not in ("success", "skipped"):
            return "absent"
        check = names.get(PRECONDITION)
        return "unlisted" if check and check["status"] == "completed" else "pending"
    if job["status"] == "completed":
        return "failed" if ran else "absent"
    if upload and upload["status"] == "completed":
        return "failed"
    return "started" if job["status"] == "in_progress" else "pending"


def find(repository, sha, wait_minutes, sleep=time.sleep, now=time.monotonic):
    """`(run, missing, reason)`: the run with the archive, or None with what is missing. The
    admit wait counts from the first poll. The short wait for the classes job to start and the
    --wait deadline count from the first poll too, and again from the poll that sees a run leave
    its admit job: time spent in the fork's queue is not taken from them. A poll the API fails
    is retried up to `RETRIES` times in a row before the error is raised."""
    began = start = now()
    deadline = start + wait_minutes * 60

    def waited():
        return f", after {now() - began:.0f} s"

    unlisted_at = {}
    admitting = set()
    errors = 0

    def poll():
        """The commit's Build runs and the state of each, or the run that holds the archive."""
        runs = [
            r
            for r in gh(f"repos/{repository}/actions/runs?head_sha={sha}&per_page=50")[
                "workflow_runs"
            ]
            if r["path"] == WORKFLOW
        ]
        states = {}
        for r in sorted(runs, key=lambda r: r["id"], reverse=True):
            artifacts = named(repository, r["id"], ARTIFACT)
            # The archive is there: the jobs need not be listed.
            if holds(artifacts):
                return runs, states, r["id"]
            jobs = gh_all(f"repos/{repository}/actions/runs/{r['id']}/jobs", "jobs")
            state = verdict(r, artifacts, jobs, lambda: named(repository, r["id"], ARTIFACT))
            if state == "ready":
                return runs, states, r["id"]
            if state == "unlisted":
                # Pending for the grace from the first poll that saw it, then absent.
                first = unlisted_at.setdefault(r["id"], now())
                state = "pending" if now() - first < ADMIT_GRACE_SECONDS else "absent"
            states[r["id"]] = state
        return runs, states, None

    while True:
        try:
            runs, states, ready = poll()
        except TRANSIENT as e:
            errors += 1
            if errors > RETRIES:
                raise
            print(f"find poll failed ({e!r}), retry {errors} of {RETRIES}", file=sys.stderr)
            sleep(RETRY_SECONDS)
            continue
        errors = 0
        if ready is not None:
            return ready, "", f"Build run {ready} has the test classes{waited()}"
        waiting = [i for i, v in states.items() if v == "waiting"]
        if admitting - set(waiting):
            # A run has left its admit job: its classes job gets the whole of both waits.
            start = now()
            deadline = start + wait_minutes * 60
        admitting = set(waiting)
        failed = [i for i, v in states.items() if v == "failed"]
        if runs and all(v in ("failed", "absent") for v in states.values()):
            if failed:
                why = f"the classes job of Build run {failed[0]} failed or skipped its upload"
                return None, "classes", why + waited()
            return None, "none", f"no Build of this commit ran its Varka jobs{waited()}"
        started = [i for i, v in states.items() if v == "started"]
        if started:
            if now() >= deadline:
                why = f"the classes job of Build run {started[0]} was still running"
                return None, "late", why + f" after {wait_minutes:g} minutes"
        elif waiting:
            if now() >= began + ADMIT_WAIT_MINUTES * 60:
                why = f"Build run {waiting[0]} was still waiting in its admit job"
                return None, "admit", why + f" after {ADMIT_WAIT_MINUTES:g} minutes"
        elif now() >= start + START_MINUTES * 60:
            what = "has started its classes job" if runs else "has started"
            why = f"no Build of this commit {what} within {START_MINUTES:g} minutes"
            return None, "none", why + waited()
        sleep(30)


def reach_name(job_name):
    """The reach artifact a varka-scoped sql job uploads: `Varka suites: sql 2 of 3` leaves
    `varka-matrix-reach-sql-2of3` (build_and_test.yml names it by the module and the slug).

    >>> reach_name("Run / Varka suites: sql 2 of 3")
    'varka-matrix-reach-sql-2of3'
    """
    part = job_name.split(" / ")[-1][len(SCOPED) :]
    return REACH + "sql-" + part.replace(" ", "")


def reach_state(jobs, artifacts_of):
    """`complete` when every varka-scoped sql job succeeded and each left a reach artifact,
    `failed` as soon as one has finished without success or when one left none, `running` while
    one has not finished, `absent` when the run has no such job. `artifacts_of(name)` gives the
    run's artifacts of that name; it is asked only once every such job has succeeded."""
    scoped = [j for j in jobs if j["name"].split(" / ")[-1].startswith(SCOPED)]
    if not scoped:
        return "absent"

    def done(j):
        return j.get("status", "completed") == "completed"

    if any(done(j) and j.get("conclusion") != "success" for j in scoped):
        return "failed"
    if not all(done(j) for j in scoped):
        return "running"
    for j in scoped:
        if not any(not a.get("expired") for a in artifacts_of(reach_name(j["name"]))):
            return "failed"
    return "complete"


def reach(repository, run, wait_minutes, sleep=time.sleep, now=time.monotonic):
    """The run's `reach_state`, read again every 30 s while it is `running`, up to the wait. A
    poll the API fails is retried up to `RETRIES` times in a row before the error is raised."""
    deadline = now() + wait_minutes * 60
    errors = 0
    while True:
        try:
            jobs = gh_all(f"repos/{repository}/actions/runs/{run}/jobs", "jobs")
            state = reach_state(jobs, lambda name: named(repository, run, name))
        except TRANSIENT as e:
            errors += 1
            if errors > RETRIES:
                raise
            print(f"reach poll failed ({e!r}), retry {errors} of {RETRIES}", file=sys.stderr)
            sleep(RETRY_SECONDS)
            continue
        errors = 0
        if state != "running" or now() >= deadline:
            return state
        sleep(30)


class SelfTest(unittest.TestCase):
    RUN = {"status": "in_progress"}

    def test_an_archive_is_ready_whatever_the_jobs_say(self):
        self.assertEqual(verdict(self.RUN, [{"name": ARTIFACT}], []), "ready")

    def test_an_expired_archive_is_not(self):
        done = {"status": "completed"}
        self.assertEqual(verdict(done, [{"name": ARTIFACT, "expired": True}], []), "absent")

    def test_waits_for_the_precondition_then_for_the_job(self):
        check = {"name": "Run / Check changes", "status": "in_progress"}
        self.assertEqual(verdict(self.RUN, [], [check]), "pending")
        check = dict(check, status="completed")
        self.assertEqual(verdict(self.RUN, [], [check]), "unlisted", "the job may come next")
        self.assertEqual(verdict({"status": "completed"}, [], [check]), "absent")
        job = {"name": "Run / " + JOB, "status": "queued"}
        self.assertEqual(verdict(self.RUN, [], [check, job]), "pending")
        job = dict(job, status="in_progress")
        self.assertEqual(verdict(self.RUN, [], [check, job]), "started")
        done = dict(job, status="completed", conclusion="failure")
        self.assertEqual(verdict(self.RUN, [], [check, done]), "failed")
        # Past its upload step without an archive, the job will not make one.
        uploaded = dict(job, steps=[{"name": UPLOAD, "status": "completed"}])
        self.assertEqual(verdict(self.RUN, [], [check, uploaded]), "failed")

    def test_a_run_waiting_on_admit_may_still_start_the_job(self):
        check = {"name": "Run / Check changes", "status": "completed"}
        admit = {"name": "Run / " + ADMIT, "status": "in_progress"}
        self.assertEqual(verdict(self.RUN, [], [check, admit]), "waiting")
        # Waited for past the job's own wait, then given up on as still queued.
        self.assertEqual(
            self.fake_find([self.RUNNING], [check, admit], 25),
            (None, "admit", ADMIT_WAIT_MINUTES * 60),
        )
        admitted = dict(admit, status="completed", conclusion="success")
        self.assertEqual(verdict(self.RUN, [], [check, admitted]), "unlisted")
        skipped = dict(admit, status="completed", conclusion="skipped")
        self.assertEqual(verdict(self.RUN, [], [check, skipped]), "unlisted")
        held = dict(admit, status="completed", conclusion="failure")
        self.assertEqual(verdict(self.RUN, [], [check, held]), "absent", "held by ci-hold")

    def test_a_run_just_admitted_is_pending_for_the_grace_then_absent(self):
        # Right after its admit succeeds the run's classes job is not listed yet.
        check = {"name": "Run / Check changes", "status": "completed"}
        admitted = {"name": "Run / " + ADMIT, "status": "completed", "conclusion": "success"}
        got = self.fake_find([self.RUNNING], [check, admitted], 25)
        self.assertEqual(got, (None, "none", ADMIT_GRACE_SECONDS))
        # A job listed within the grace is waited for as usual.
        job = {"name": "Run / " + JOB, "status": "in_progress", "steps": []}
        listings = iter([[check, admitted], [check, admitted, job]])
        got = self.fake_find([self.RUNNING], lambda: next(listings, [check, admitted, job]), 25)
        self.assertEqual(got, (None, "late", 1500.0))

    def test_a_run_whose_admit_was_skipped_gets_the_grace_too(self):
        check = {"name": "Run / Check changes", "status": "completed"}
        skipped = {"name": "Run / " + ADMIT, "status": "completed", "conclusion": "skipped"}
        got = self.fake_find([self.RUNNING], [check, skipped], 25)
        self.assertEqual(got, (None, "none", ADMIT_GRACE_SECONDS))
        job = {"name": "Run / " + JOB, "status": "in_progress", "steps": []}
        listings = iter([[check, skipped], [check, skipped, job]])
        got = self.fake_find([self.RUNNING], lambda: next(listings, [check, skipped, job]), 25)
        self.assertEqual(got, (None, "late", 1500.0))

    def test_a_skipped_job_is_absent_and_a_failed_one_is_not(self):
        done = {"status": "completed"}
        skipped = {"name": "Run / " + JOB, "status": "completed", "conclusion": "skipped"}
        self.assertEqual(verdict(done, [], [skipped]), "absent")
        self.assertEqual(verdict(self.RUN, [], [skipped]), "absent")
        failed = dict(skipped, conclusion="failure")
        self.assertEqual(verdict(done, [], [failed]), "failed")

    def test_reach_is_complete_only_with_every_scoped_job_and_its_artifact(self):
        jobs = [
            {
                "name": "Run / Varka suites: sql 1 of 3",
                "status": "completed",
                "conclusion": "success",
            },
            {
                "name": "Run / Varka suites: sql 2 of 3",
                "status": "completed",
                "conclusion": "success",
            },
            {"name": "Run / Varka docs checks", "status": "completed", "conclusion": "failure"},
        ]

        def of(artifacts):
            return lambda name: [a for a in artifacts if a["name"] == name]

        both = [{"name": REACH + "sql-1of3"}, {"name": REACH + "sql-2of3"}]
        self.assertEqual(reach_state(jobs, of(both)), "complete")
        self.assertEqual(reach_state(jobs, of(both[:1])), "failed")
        expired = [both[0], dict(both[1], expired=True)]
        self.assertEqual(reach_state(jobs, of(expired)), "failed")
        failed = [dict(jobs[0], conclusion="failure")] + jobs[1:]
        self.assertEqual(reach_state(failed, of(both)), "failed")

        def never(name):
            raise AssertionError(f"read {name} while a job runs")

        running = [dict(jobs[0], status="in_progress", conclusion=None)] + jobs[1:]
        self.assertEqual(reach_state(running, never), "running")
        # One shard failed while another still runs: the record cannot be complete.
        early = [running[0], dict(jobs[1], conclusion="failure")]
        self.assertEqual(reach_state(early, never), "failed")
        self.assertEqual(reach_state([jobs[2]], never), "absent", "a Build with no scoped job")
        catalyst = {
            "name": "Run / Varka suites: catalyst 1 of 3",
            "status": "completed",
            "conclusion": "success",
        }
        self.assertEqual(
            reach_state(jobs + [catalyst], of(both)), "complete", "catalyst jobs record nothing"
        )

    def fake(self, runs, jobs, artifacts=()):
        """A `gh` over a repository whose Build runs and artifacts never change, serving job
        listings a page at a time as the API does and artifacts only by exact name, as this
        script asks for them. `jobs` and `artifacts` are lists, or callables giving each
        listing's items. `self.artifact_reads` counts the artifact requests and
        `self.job_reads` the job listings."""
        self.artifact_reads = 0
        self.job_reads = 0

        def api(path):
            if "runs?" in path:
                return {"workflow_runs": runs}
            if "/artifacts" in path:
                self.assertIn("/artifacts?name=", path, "artifacts are looked up by name")
                self.artifact_reads += 1
                name = path.split("?name=", 1)[1]
                held = artifacts() if callable(artifacts) else artifacts
                return {"artifacts": [a for a in held if a["name"] == name]}
            page = int(path.rsplit("page=", 1)[1]) if "&page=" in path else 1
            if page == 1:
                self.job_reads += 1
                self.listed = list(jobs() if callable(jobs) else jobs)
            return {"jobs": self.listed[(page - 1) * PAGE : page * PAGE]}

        return api

    def run_with(self, api, call, t0=0.0):
        """`call(sleep, now)` with `gh` replaced by `api` and a fake clock that starts at `t0`
        and is kept in `self.clock`; (result, seconds slept)."""
        clock = self.clock = [t0]
        global gh
        saved = gh
        gh = api
        try:
            got = call(lambda s: clock.__setitem__(0, clock[0] + s), lambda: clock[0])
        finally:
            gh = saved
        return got, clock[0] - t0

    def fake_find(self, runs, jobs, wait, artifacts=(), t0=0.0, api=None):
        """`find` over `fake` (or `api` given the fake); the run, what is missing and the
        seconds slept."""
        fake = self.fake(runs, jobs, artifacts)
        got, slept = self.run_with(
            api(fake) if api else fake,
            lambda sleep, now: find("o/r", "s", wait, sleep=sleep, now=now),
            t0,
        )
        return got[0], got[1], slept

    RUNNING = {"id": 1, "path": WORKFLOW, "status": "in_progress"}

    def test_a_commit_with_no_build_gets_the_short_wait(self):
        self.assertEqual(self.fake_find([], [], 25), (None, "none", START_MINUTES * 60))

    def test_a_queued_classes_job_gets_the_short_wait(self):
        job = {"name": "Run / " + JOB, "status": "queued", "steps": []}
        got = self.fake_find([self.RUNNING], [job], 25)
        self.assertEqual(got, (None, "none", START_MINUTES * 60))

    def test_a_started_classes_job_is_waited_for_until_the_deadline(self):
        job = {"name": "Run / " + JOB, "status": "in_progress", "steps": []}
        self.assertEqual(self.fake_find([self.RUNNING], [job], 25), (None, "late", 1500.0))

    def test_a_finished_build_says_whether_its_classes_job_ran(self):
        run = dict(self.RUNNING, status="completed")
        failed = {"name": "Run / " + JOB, "status": "completed", "conclusion": "failure"}
        self.assertEqual(self.fake_find([run], [failed], 25), (None, "classes", 0.0))
        skipped = dict(failed, conclusion="skipped")
        self.assertEqual(self.fake_find([run], [skipped], 25), (None, "none", 0.0))

    def test_the_archive_is_looked_up_by_its_name_among_many(self):
        decoys = [{"name": f"junit-{i}"} for i in range(PAGE + 30)]
        artifacts = decoys[:PAGE] + [{"name": ARTIFACT}] + decoys[PAGE:]
        job = {"name": "Run / " + JOB, "status": "in_progress", "steps": []}
        got = self.fake_find([self.RUNNING], [job], 25, artifacts)
        self.assertEqual(got, (1, "", 0.0))
        self.assertEqual(self.artifact_reads, 1)
        self.assertEqual(self.job_reads, 0, "the jobs are not listed once the archive is found")

    def test_an_upload_done_between_the_two_reads_is_read_again(self):
        job = {"name": "Run / " + JOB, "status": "in_progress"}
        job["steps"] = [{"name": UPLOAD, "status": "completed"}]
        self.assertEqual(verdict(self.RUN, [], [job], lambda: [{"name": ARTIFACT}]), "ready")
        self.assertEqual(verdict(self.RUN, [], [job], lambda: []), "failed")
        done = dict(job, status="completed", conclusion="success")
        self.assertEqual(verdict(self.RUN, [], [done], lambda: [{"name": ARTIFACT}]), "ready")

        def never():
            raise AssertionError("read again before the upload completed")

        running = dict(job, steps=[{"name": UPLOAD, "status": "in_progress"}])
        self.assertEqual(verdict(self.RUN, [], [running], never), "started")
        # Through find: the first artifact read misses, the one after the job listing hits.
        reads = iter([[], [{"name": ARTIFACT}]])
        got = self.fake_find([self.RUNNING], [job], 25, lambda: next(reads))
        self.assertEqual(got, (1, "", 0.0))
        self.assertEqual(self.artifact_reads, 2)

    def admitted_after(self, minutes, t0):
        """A Build whose admit waits `minutes` from `t0`, whose classes job is listed a minute
        after, starts four minutes after the admit and uploads 20 minutes after it: inside a
        start window and a 25-minute deadline counted from the admit, not from the first poll.
        The listings, for `fake`."""
        check = {"name": "Run / Check changes", "status": "completed"}
        admit = {"name": "Run / " + ADMIT, "status": "in_progress"}
        admitted = dict(admit, status="completed", conclusion="success")
        job = {"name": "Run / " + JOB, "status": "queued", "steps": []}
        at = t0 + minutes * 60

        def jobs():
            t = self.clock[0]
            if t < at:
                return [check, admit]
            if t < at + 60:
                return [check, admitted]
            return [check, admitted, dict(job, status="in_progress" if t >= at + 240 else "queued")]

        def artifacts():
            return [{"name": ARTIFACT}] if self.clock[0] >= at + 20 * 60 else []

        return jobs, artifacts

    def test_the_waits_restart_when_a_run_leaves_its_admit_job(self):
        for minutes in (8, 27):
            with self.subTest(admit_minutes=minutes):
                t0 = 1000.0
                jobs, artifacts = self.admitted_after(minutes, t0)
                got = self.fake_find([self.RUNNING], jobs, 25, artifacts, t0=t0)
                self.assertEqual(got[:2], (1, ""))
                self.assertGreaterEqual(got[2], (minutes + 20) * 60)

    def test_find_retries_a_failed_poll(self):
        job = {"name": "Run / " + JOB, "status": "in_progress", "steps": []}

        def artifacts():
            return [{"name": ARTIFACT}] if self.clock[0] >= 300 else []

        def flaky(api):
            left = [1]

            def call(path):
                if "/jobs" in path and self.clock[0] >= 120 and left[0]:
                    left[0] -= 1
                    raise subprocess.CalledProcessError(1, ["gh", "api", path])
                return api(path)

            return call

        got = self.fake_find([self.RUNNING], [job], 25, artifacts, api=flaky)
        self.assertEqual(got[:2], (1, ""))
        self.assertEqual(got[2], 300 + RETRY_SECONDS)

    def test_the_jobs_are_read_past_their_first_page(self):
        filler = [{"name": f"Run / Build modules: m{i}", "status": "queued"} for i in range(PAGE)]
        job = {"name": "Run / " + JOB, "status": "in_progress", "steps": []}
        self.assertEqual(self.fake_find([self.RUNNING], filler + [job], 25), (None, "late", 1500.0))

    def test_reach_waits_while_the_sql_jobs_run_and_no_longer(self):
        sql = {"name": "Run / Varka suites: sql 1 of 3", "status": "in_progress"}
        got = self.run_with(
            self.fake([], [sql]), lambda sleep, now: reach("o/r", 1, 10, sleep=sleep, now=now)
        )
        self.assertEqual(got, ("running", 600.0))
        self.assertEqual(self.artifact_reads, 0, "no artifact is read while a job runs")
        done = dict(sql, status="completed", conclusion="success")
        record = [{"name": f"junit-{i}"} for i in range(PAGE)] + [{"name": REACH + "sql-1of3"}]
        got = self.run_with(
            self.fake([], [done], record),
            lambda sleep, now: reach("o/r", 1, 10, sleep=sleep, now=now),
        )
        self.assertEqual(got, ("complete", 0.0))

    def test_reach_retries_a_failed_poll_and_gives_up_after_three(self):
        done = {"name": "Run / Varka suites: sql 1 of 3", "status": "completed"}
        done["conclusion"] = "success"
        record = [{"name": REACH + "sql-1of3"}]

        def flaky(failures):
            api = self.fake([], [done], record)
            left = [failures]

            def call(path):
                if "/jobs" in path and left[0]:
                    left[0] -= 1
                    raise subprocess.CalledProcessError(1, ["gh", "api", path])
                return api(path)

            return call

        def go(sleep, now):
            return reach("o/r", 1, 10, sleep=sleep, now=now)

        self.assertEqual(self.run_with(flaky(1), go), ("complete", RETRY_SECONDS))
        self.assertEqual(self.run_with(flaky(3), go), ("complete", 3 * RETRY_SECONDS))
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_with(flaky(4), go)


def main(argv):
    if argv[:1] == ["reach"]:
        parser = argparse.ArgumentParser(prog="varka_matrix_reuse.py reach")
        parser.add_argument("repository")
        parser.add_argument("run")
        parser.add_argument("--wait", type=float, default=10.0)
        args = parser.parse_args(argv[1:])
        try:
            state = reach(args.repository, args.run, args.wait)
        except TRANSIENT as e:
            print(f"no reach: {e}", file=sys.stderr)
            state = "error"
        print(f"reach state: {state}", file=sys.stderr)
        print(f"reach={'1' if state == 'complete' else ''}")
        print(f"reach_state={state}")
        return 0
    if argv[:1] == ["--selftest"]:
        import doctest

        failed, _ = doctest.testmod()
        result = unittest.main(argv=[sys.argv[0]], exit=False, verbosity=0).result
        ok = result.wasSuccessful() and not failed
        print("varka_matrix_reuse selftest: " + ("ok" if ok else "FAILED"))
        return 0 if ok else 1
    parser = argparse.ArgumentParser()
    parser.add_argument("repository")
    parser.add_argument("sha")
    parser.add_argument("--wait", type=float, default=25.0)
    args = parser.parse_args(argv)
    run, missing, why = find(args.repository, args.sha, args.wait)
    print(why, file=sys.stderr)
    print(f"run={run or ''}")
    print(f"missing={missing}")
    print(f"reason={why}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
