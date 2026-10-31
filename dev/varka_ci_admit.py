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
"""Admit a full-matrix Build on a fork only while fewer than N other full-matrix runs are ahead.

    dev/varka_ci_admit.py --repo OWNER/NAME --run-id ID --branch BRANCH [--base-repo OWNER/NAME]
        [--concurrent N]
    dev/varka_ci_admit.py --self-test       # the decision over recorded API responses

This is the `admit` job of `.github/workflows/build_and_test.yml` (VARKA-255.md 3.1). The job
runs only in a Build that will precompile Spark and fan out behind it - Spark's module matrix,
the PySpark groups, the Docker and Kubernetes jobs - and `Precompile Spark` needs it to succeed,
as do the Varka jobs such a run starts when its change builds catalyst or sql, so `ci-hold` stops
them too. A Varka-scoped or documents-only run skips it and never waits.

The marker of a full run is its own `admit` job, read from the jobs API: one `in_progress` is a
full run waiting, one that succeeded in a run still `queued` or `in_progress` is a full run that
has fanned out. A run is ahead of this one when it has fanned out, or when its `admit` started
waiting before this run's own (`started_at`, ties broken by the lower run id); this run's `admit`
waits while N or more runs are ahead. N is `--concurrent`, 2 by default: the fork runs 40 jobs at
once since its account moved to Pro, and a full run holds about 20 of them at its widest (twelve
module slots and the jobs beside them), so two fit side by side and a third would share slots.
A rerun starts a new `admit`, so a rerun of an old run queues behind the full runs already
waiting instead of passing them on its first attempt's creation time. A run whose precondition
is still choosing has no `admit` yet and takes its place when it has one.

A pull request on the base repository labelled `ci-hold` fails the job with "held by ci-hold",
so `Precompile Spark` and everything behind it skip; removing the label and rerunning the
failed jobs resumes it. The label is read on every poll, so a run already waiting can be held.

The wait polls every 120 seconds and gives up after 330 minutes, inside the 360-minute job
cap, naming the runs it waited on. A poll that cannot read the API is retried at the next one;
after five such polls in a row (a token without `actions: read`, a run that is gone) the job
admits with a warning rather than hold a slot for the whole deadline and then fail the Build:
like the hold, the queue is a convenience a broken read must not turn into a red run.

On apache/spark and on the base repository it admits at once and makes no request: their
Builds are not queued here. Reads use the token in
GH_TOKEN (or GITHUB_TOKEN), the fork's own, which the calling job grants `actions: read`.
Every poll prints the requests made so far, so a changed rate limit shows as a number.
"""

import argparse
import http.client
import json
import os
import sys
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
# The job's display name in build_and_test.yml. The jobs API prefixes the calling job's name
# ("Run / ..."), so another run's admit is found by this suffix.
ADMIT_JOB_NAME = "Admit a full-matrix run"
HOLD_LABEL = "ci-hold"
# Repositories whose Builds are not queued: upstream's own, and the base, whose only full run
# is the weekly matrix.
EXEMPT_REPOS = ("apache/spark", "vecbricks/varka")
WORKFLOW_FILE = "build_main.yml"
# What a failed read raises: URLError and HTTPError (both OSError), a socket timeout or reset, a
# response cut short (http.client.HTTPException) and a body that is not JSON (ValueError).
READ_ERRORS = (OSError, http.client.HTTPException, ValueError)


class Api:
    """GET against the REST API, following `Link: rel="next"` and counting requests."""

    def __init__(self, token, opener=None):
        self.token = token
        self.requests = 0
        self.opener = opener or self._urlopen

    def _urlopen(self, url):
        req = urllib.request.Request(url)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode()), resp.headers.get("Link", "")

    def get(self, path):
        """One GET, its body parsed."""
        self.requests += 1
        body, _ = self.opener(path if path.startswith("http") else API + path)
        return body

    def get_all(self, path, key):
        """Every page of a listing whose items sit under `key` (None: the body is the list)."""
        url = path if path.startswith("http") else API + path
        items = []
        while url:
            self.requests += 1
            body, link = self.opener(url)
            items.extend(body if key is None else body.get(key, []))
            url = next_link(link)
        return items


def next_link(link):
    """The `rel="next"` URL of a Link header, or None.

    >>> next_link('<https://x/a?page=2>; rel="next", <https://x/a?page=5>; rel="last"')
    'https://x/a?page=2'
    >>> next_link('<https://x/a?page=1>; rel="prev"') is None
    True
    """
    for part in (link or "").split(","):
        seg = part.strip().split(";")
        if len(seg) >= 2 and seg[1].strip() == 'rel="next"':
            return seg[0].strip().lstrip("<").rstrip(">")
    return None


def admit_job(jobs):
    """The `admit` job of one run's latest attempt, or None."""
    for job in jobs:
        if job.get("name", "").endswith(ADMIT_JOB_NAME):
            return job
    return None


def order_key(job, run_id):
    # ISO 8601 UTC timestamps from the API compare as strings.
    return (job.get("started_at") or "", run_id)


def blockers(own_run_id, own_admit, others):
    """The runs this run's admit waits on, each as (run_id, why).

    `others` maps a run id to its jobs, for every Build run on the fork that is `queued` or
    `in_progress` other than this one. A run with no admit job, a queued admit (not started)
    or an admit that failed or was skipped blocks nothing.
    """
    found = []
    mine = order_key(own_admit, own_run_id)
    for run_id, jobs in sorted(others.items()):
        if run_id == own_run_id:
            continue
        job = admit_job(jobs)
        if job is None:
            continue
        if job.get("status") == "completed" and job.get("conclusion") == "success":
            found.append((run_id, "has fanned out"))
        elif job.get("status") == "in_progress" and order_key(job, run_id) < mine:
            found.append((run_id, "started waiting at %s, before this run" % job["started_at"]))
    return found


def held(pulls):
    """The number of an open pull request carrying the hold label, or None."""
    for pr in pulls:
        if any(label.get("name") == HOLD_LABEL for label in pr.get("labels", [])):
            return pr.get("number")
    return None


class Admission:
    def __init__(self, api, repo, run_id, branch, base_repo, concurrent=1):
        self.api = api
        self.concurrent = concurrent
        self.repo = repo
        self.run_id = int(run_id)
        self.branch = branch
        self.base_repo = base_repo

    def hold(self):
        if not self.base_repo:
            return None
        owner = self.repo.split("/")[0]
        try:
            pulls = self.api.get_all(
                "/repos/%s/pulls?state=open&head=%s&per_page=100"
                % (self.base_repo, urllib.parse.quote("%s:%s" % (owner, self.branch), safe=":")),
                None,
            )
        except READ_ERRORS as e:
            # The hold is an operator's convenience; a failed read must not stop CI.
            print(
                "warning: could not read %s's pull requests (%s); no hold applied"
                % (self.base_repo, e)
            )
            return None
        return held(pulls)

    def in_flight(self):
        runs = []
        for status in ("in_progress", "queued"):
            runs.extend(
                self.api.get_all(
                    "/repos/%s/actions/workflows/%s/runs?status=%s&per_page=100"
                    % (self.repo, WORKFLOW_FILE, status),
                    "workflow_runs",
                )
            )
        return sorted({r["id"] for r in runs})

    def jobs(self, run_id):
        return self.api.get_all(
            "/repos/%s/actions/runs/%d/jobs?per_page=100" % (self.repo, run_id), "jobs"
        )

    def poll(self):
        """('held', pr) | ('wait', blockers) | ('admit', [])."""
        pr = self.hold()
        if pr is not None:
            return "held", pr
        own = admit_job(self.jobs(self.run_id))
        if own is None or not own.get("started_at"):
            # Off CI, or a listing that lags the job's start: order this run last among those
            # already waiting rather than guess an earlier place.
            own = {"started_at": "9999", "status": "in_progress"}
        others = {r: self.jobs(r) for r in self.in_flight() if r != self.run_id}
        found = blockers(self.run_id, own, others)
        return ("wait", found) if len(found) >= self.concurrent else ("admit", found)


def run(
    admission,
    poll_seconds,
    deadline_minutes,
    sleep=time.sleep,
    clock=time.monotonic,
    max_failed_polls=5,
):
    start = clock()
    last = []
    failed_polls = 0
    while True:
        stamp = time.strftime("%H:%M:%SZ", time.gmtime())
        try:
            verdict, detail = admission.poll()
            failed_polls = 0
        except READ_ERRORS as e:
            # A server error or a dropped connection: try again at the next poll. A read that
            # keeps failing will not mend by the deadline, so admit rather than wait it out.
            failed_polls += 1
            if failed_polls >= max_failed_polls:
                print(
                    "%s warning: admitted without the queue, %d polls in a row could not read "
                    "the fork's runs (last: %s)" % (stamp, failed_polls, e)
                )
                return 0
            print("%s could not read the fork's runs (%s); polling again" % (stamp, e))
            verdict, detail = "wait", []
        if verdict == "held":
            print(
                "%s held by %s (pull request #%s): remove the label and rerun the failed "
                "jobs to resume" % (stamp, HOLD_LABEL, detail)
            )
            return 1
        if verdict == "admit":
            print(
                "%s admitted: %d other full-matrix run(s) ahead, %d allowed at once (%d requests)"
                % (stamp, len(detail), admission.concurrent, admission.api.requests)
            )
            return 0
        for run_id, why in detail:
            print(
                "%s waiting on run %d, which %s (https://github.com/%s/actions/runs/%d)"
                % (stamp, run_id, why, admission.repo, run_id)
            )
        print("  %d requests so far" % admission.api.requests)
        if detail:
            last = detail
        if clock() - start + poll_seconds > deadline_minutes * 60:
            print(
                "gave up after %d minutes waiting on run(s) %s"
                % (deadline_minutes, ", ".join(str(r) for r, _ in last))
            )
            return 1
        sleep(poll_seconds)


def main(argv):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--self-test", action="store_true")
    p.add_argument("--repo")
    p.add_argument("--run-id")
    p.add_argument("--branch")
    p.add_argument("--base-repo", default="vecbricks/varka")
    p.add_argument("--poll", type=int, default=120)
    p.add_argument("--deadline-minutes", type=int, default=330)
    p.add_argument("--max-failed-polls", type=int, default=5)
    p.add_argument("--concurrent", type=int, default=2)
    args = p.parse_args(argv)
    if args.self_test:
        import doctest

        failed, _ = doctest.testmod()
        result = unittest.main(argv=[sys.argv[0]], exit=False, verbosity=2).result
        return 1 if failed or not result.wasSuccessful() else 0
    if not (args.repo and args.run_id and args.branch):
        p.error("--repo, --run-id and --branch are required")
    if args.concurrent < 1:
        p.error("--concurrent must be at least 1")
    if args.repo in EXEMPT_REPOS:
        print("admitted: %s's Builds are not queued" % args.repo)
        return 0
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    api = Api(token)
    base = args.base_repo if args.base_repo != args.repo else None
    return run(
        Admission(api, args.repo, args.run_id, args.branch, base, args.concurrent),
        args.poll,
        args.deadline_minutes,
        max_failed_polls=args.max_failed_polls,
    )


# The tests: recorded shapes of the API's responses (VARKA-255.md 5, test 1).


def job(name, status, conclusion=None, started_at=None):
    return {"name": name, "status": status, "conclusion": conclusion, "started_at": started_at}


def admit(status, conclusion=None, started_at=None):
    return job("Run / " + ADMIT_JOB_NAME, status, conclusion, started_at)


class FakeApi(Api):
    """Serves recorded responses by URL; a listing may be split over several pages."""

    def __init__(self, pages):
        super().__init__(None, opener=self._serve)
        self.pages = pages

    def _serve(self, url):
        return self.pages[url]


def recorded(repo, runs, jobs_by_run, pulls=(), page_size=100, branch="b"):
    """URL -> (body, Link) for a fork with `runs` (id -> status) and their jobs."""
    pages = {}
    for status in ("in_progress", "queued"):
        url = "%s/repos/%s/actions/workflows/%s/runs?status=%s&per_page=100" % (
            API,
            repo,
            WORKFLOW_FILE,
            status,
        )
        listed = [{"id": r} for r, s in runs.items() if s == status]
        pages[url] = ({"workflow_runs": listed}, "")
    for run_id, jobs in jobs_by_run.items():
        url = "%s/repos/%s/actions/runs/%d/jobs?per_page=100" % (API, repo, run_id)
        chunks = [jobs[i : i + page_size] for i in range(0, len(jobs), page_size)] or [[]]
        for n, chunk in enumerate(chunks):
            nxt = "%s&page=%d" % (url.split("&page=")[0], n + 2)
            link = '<%s>; rel="next"' % nxt if n + 1 < len(chunks) else ""
            pages[url] = ({"jobs": chunk}, link)
            url = nxt
    pulls_url = "%s/repos/vecbricks/varka/pulls?state=open&head=MaxGekk:%s&per_page=100" % (
        API,
        urllib.parse.quote(branch, safe=""),
    )
    pages[pulls_url] = (list(pulls), "")
    return pages


REPO = "MaxGekk/spark"


def admission(runs, jobs_by_run, pulls=(), page_size=100, own=1, branch="b", concurrent=1):
    api = FakeApi(recorded(REPO, runs, jobs_by_run, pulls, page_size, branch))
    return Admission(api, REPO, own, branch, "vecbricks/varka", concurrent)


class FailingApi(Api):
    """Every read fails with `error`."""

    def __init__(self, error):
        super().__init__(None, opener=self._fail)
        self.error = error

    def _fail(self, url):
        raise self.error


class AdmitTest(unittest.TestCase):
    def test_two_at_once_admits_beside_one_run_that_has_fanned_out(self):
        runs = {1: "in_progress", 2: "in_progress"}
        jobs = {
            1: [admit("in_progress", started_at="2026-10-16T10:05:00Z")],
            2: [admit("completed", "success", "2026-10-16T10:00:00Z")],
        }
        self.assertEqual(
            admission(runs, jobs, concurrent=2).poll(), ("admit", [(2, "has fanned out")])
        )

    def test_two_at_once_waits_behind_two_runs_ahead(self):
        # One run has fanned out and another started waiting first: two ahead, so a third waits.
        runs = {1: "in_progress", 2: "in_progress", 3: "in_progress"}
        jobs = {
            1: [admit("in_progress", started_at="2026-10-16T10:05:00Z")],
            2: [admit("completed", "success", "2026-10-16T10:00:00Z")],
            3: [admit("in_progress", started_at="2026-10-16T10:01:00Z")],
        }
        verdict, found = admission(runs, jobs, concurrent=2).poll()
        self.assertEqual(verdict, "wait")
        self.assertEqual(sorted(r for r, _ in found), [2, 3])
        # The run that started waiting first is admitted beside the one that fanned out.
        self.assertEqual(admission(runs, jobs, own=3, concurrent=2).poll()[0], "admit")

    def test_waits_on_a_run_that_has_fanned_out(self):
        a = admission(
            {1: "in_progress", 2: "in_progress"},
            {
                1: [admit("in_progress", started_at="2026-10-16T10:05:00Z")],
                2: [
                    admit("completed", "success", "2026-10-16T10:00:00Z"),
                    job("Run / Precompile Spark", "in_progress"),
                ],
            },
        )
        self.assertEqual(a.poll(), ("wait", [(2, "has fanned out")]))

    def test_the_admit_that_started_first_passes_first(self):
        runs = {1: "in_progress", 2: "in_progress"}
        jobs = {
            1: [admit("in_progress", started_at="2026-10-16T10:00:00Z")],
            2: [admit("in_progress", started_at="2026-10-16T10:01:00Z")],
        }
        self.assertEqual(admission(runs, jobs, own=1).poll(), ("admit", []))
        verdict, found = admission(runs, jobs, own=2).poll()
        self.assertEqual(verdict, "wait")
        self.assertEqual([r for r, _ in found], [1])

    def test_a_tie_on_start_goes_to_the_lower_run_id(self):
        same = "2026-10-16T10:00:00Z"
        runs = {7: "in_progress", 9: "in_progress"}
        jobs = {
            7: [admit("in_progress", started_at=same)],
            9: [admit("in_progress", started_at=same)],
        }
        self.assertEqual(admission(runs, jobs, own=7).poll(), ("admit", []))
        self.assertEqual(admission(runs, jobs, own=9).poll()[0], "wait")

    def test_a_rerun_of_an_old_run_waits_behind_a_run_already_waiting(self):
        # Run 1 is older (lower id, created first) but its admit was rerun at 10:30, after
        # run 5's admit started waiting at 10:10.
        runs = {1: "in_progress", 5: "in_progress", 6: "in_progress"}
        jobs = {
            1: [admit("in_progress", started_at="2026-10-16T10:30:00Z")],
            5: [admit("in_progress", started_at="2026-10-16T10:10:00Z")],
            6: [admit("completed", "success", "2026-10-16T09:00:00Z")],
        }
        verdict, found = admission(runs, jobs, own=1).poll()
        self.assertEqual(verdict, "wait")
        self.assertEqual(sorted(r for r, _ in found), [5, 6])

    def test_a_run_with_no_admit_job_yet_does_not_block(self):
        runs = {1: "in_progress", 2: "queued", 3: "in_progress"}
        jobs = {
            1: [admit("in_progress", started_at="2026-10-16T10:05:00Z")],
            2: [],
            3: [job("Run / Check changes", "in_progress")],
        }
        self.assertEqual(admission(runs, jobs).poll(), ("admit", []))

    def test_scoped_and_held_runs_block_nothing(self):
        runs = {1: "in_progress", 2: "in_progress", 3: "in_progress", 4: "in_progress"}
        jobs = {
            1: [admit("in_progress", started_at="2026-10-16T10:05:00Z")],
            2: [admit("completed", "skipped")],
            3: [admit("completed", "failure", "2026-10-16T09:00:00Z")],
            4: [admit("queued")],
        }
        self.assertEqual(admission(runs, jobs).poll(), ("admit", []))

    def test_a_held_pull_request_fails(self):
        pulls = [{"number": 850, "labels": [{"name": "fix"}, {"name": HOLD_LABEL}]}]
        a = admission(
            {1: "in_progress"},
            {1: [admit("in_progress", started_at="2026-10-16T10:00:00Z")]},
            pulls,
        )
        self.assertEqual(a.poll(), ("held", 850))
        self.assertEqual(run(a, 0, 1, sleep=lambda s: None), 1)

    def test_a_completed_run_blocks_nothing(self):
        # Run 2 fanned out but has completed: the listing of queued and in-progress runs
        # does not name it, so its jobs are never read.
        runs = {1: "in_progress", 2: "completed"}
        jobs = {
            1: [admit("in_progress", started_at="2026-10-16T10:05:00Z")],
            2: [admit("completed", "success", "2026-10-16T09:00:00Z")],
        }
        self.assertEqual(admission(runs, jobs).poll(), ("admit", []))

    def test_a_job_listing_over_one_page_is_read_whole(self):
        # Run 2's admit is the 131st job, on the second page of a 100-job listing.
        filler = [job("Run / Build modules: m%d" % i, "queued") for i in range(130)]
        jobs = {
            1: [admit("in_progress", started_at="2026-10-16T10:05:00Z")],
            2: filler + [admit("completed", "success", "2026-10-16T09:00:00Z")],
        }
        a = admission({1: "in_progress", 2: "in_progress"}, jobs, page_size=100)
        self.assertEqual(a.poll(), ("wait", [(2, "has fanned out")]))

    def test_the_wait_gives_up_at_its_deadline_naming_the_run(self):
        jobs = {
            1: [admit("in_progress", started_at="2026-10-16T10:05:00Z")],
            2: [admit("completed", "success", "2026-10-16T09:00:00Z")],
        }
        a = admission({1: "in_progress", 2: "in_progress"}, jobs)
        ticks = iter(range(0, 10**6, 120))
        self.assertEqual(run(a, 120, 10, sleep=lambda s: None, clock=lambda: next(ticks)), 1)

    def test_a_branch_name_is_quoted_in_the_hold_lookup(self):
        # "+" and "#" read raw would be a space and a fragment, and the hold would miss.
        pulls = [{"number": 851, "labels": [{"name": HOLD_LABEL}]}]
        a = admission(
            {1: "in_progress"},
            {1: [admit("in_progress", started_at="2026-10-16T10:00:00Z")]},
            pulls,
            branch="varka-1+2#x",
        )
        self.assertEqual(a.poll(), ("held", 851))

    def test_reads_that_keep_failing_admit_with_a_warning(self):
        # A 403 for a token without actions: read, a reset connection, a body cut short, a
        # body that is not JSON: each is retried, and five in a row admit.
        errors = (
            urllib.error.HTTPError("u", 403, "Resource not accessible by integration", {}, None),
            ConnectionResetError("reset"),
            http.client.IncompleteRead(b""),
            ValueError("not JSON"),
        )
        for error in errors:
            a = Admission(FailingApi(error), REPO, 1, "b", "vecbricks/varka")
            slept = []
            self.assertEqual(run(a, 120, 330, sleep=slept.append, clock=lambda: 0), 0)
            self.assertEqual(len(slept), 4)

    def test_one_failed_read_is_retried_and_the_wait_goes_on(self):
        jobs = {
            1: [admit("in_progress", started_at="2026-10-16T10:05:00Z")],
            2: [admit("completed", "success", "2026-10-16T09:00:00Z")],
        }
        a = admission({1: "in_progress", 2: "in_progress"}, jobs)
        polls = iter([OSError("dropped"), None, None])
        real = a.poll

        def flaky():
            failure = next(polls, None)
            if failure is not None:
                raise failure
            return real()

        a.poll = flaky
        ticks = iter(range(0, 10**6, 120))
        # The failure does not admit: the run behind run 2 waits on to its deadline.
        self.assertEqual(run(a, 120, 10, sleep=lambda s: None, clock=lambda: next(ticks)), 1)

    def test_exempt_repositories_make_no_request(self):
        self.assertEqual(main(["--repo", "vecbricks/varka", "--run-id", "1", "--branch", "m"]), 0)
        self.assertEqual(main(["--repo", "apache/spark", "--run-id", "1", "--branch", "m"]), 0)

    def test_the_workflow_gates_precompile_on_this_job(self):
        # VARKA-255.md 7.2: `admit` carries the condition upstream gives Precompile Spark, and
        # Precompile Spark runs only on admit's success, so one condition decides both and an
        # upstream edit to that condition conflicts here instead of drifting.
        here = os.path.dirname(os.path.abspath(__file__))
        path = os.path.join(here, "..", ".github", "workflows", "build_and_test.yml")
        if not os.path.exists(path):
            self.skipTest("no build_and_test.yml beside this script")
        jobs = job_blocks(open(path).read())
        self.assertIn('name: "%s"' % ADMIT_JOB_NAME, jobs["admit"])
        self.assertIn("needs.admit.result == 'success'", jobs["precompile"])
        self.assertIn("admit", jobs["precompile"].split("needs:")[1].split("\n")[0])
        for key in ("build", "pyspark", "sparkr", "docker-integration-tests", "tpcds-1g"):
            self.assertIn(".%s == 'true'" % key, jobs["admit"])
            self.assertNotIn(".%s == 'true'" % key, jobs["precompile"])
        # The Varka jobs a full run starts wait on this job too, so a held run holds them.
        self.assertIn("needs: [precondition, admit]", jobs["varka-matrix-classes"])
        self.assertIn("needs: [precondition, admit]", jobs["varka-scoped"])


def job_blocks(workflow):
    """The text of each top-level job of a workflow file, by job key (two-space indent)."""
    blocks, key = {}, None
    in_jobs = False
    for line in workflow.splitlines():
        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if in_jobs and line[:2] == "  " and line[2:3] not in (" ", "#", "") and line.endswith(":"):
            key = line.strip()[:-1]
            blocks[key] = ""
        elif key:
            blocks[key] += line + "\n"
    return blocks


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
