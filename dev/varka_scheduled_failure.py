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
"""Report a failed scheduled workflow run as a GitHub issue.

    dev/varka_scheduled_failure.py --workflow "Varka fuzz" --run-url URL --sha SHA \\
        --jobs-file jobs.ndjson --date 2026-10-20 --repo vecbricks/varka --apply
    dev/varka_scheduled_failure.py --selftest

A red scheduled run has no author to see it, so nobody read the weekly matrix while it failed
five weeks running. The last job of each scheduled Varka workflow calls this (through
.github/actions/report-scheduled-failure) when an earlier job of the run failed. The rule:

  * An open issue labelled "scheduled-run-failed" that belongs to the same workflow gets a
    comment with the run, the failed jobs and the head commit; a second red night of the same
    workflow never opens a second issue.
  * Otherwise one issue is opened, titled "Scheduled run failed: <workflow> (first red <date>)".
  * Nothing is closed. A person closes the issue once the cause is fixed or filed, and the
    next red run then opens a new one.

An issue belongs to a workflow by the marker line in its body, or, if someone edited the body
away, by its title. Failed jobs are those whose conclusion is failure, timed_out or cancelled:
a job that exceeds its timeout-minutes ends as cancelled, and a run is not green with one in it.
A run that fails at startup runs no job and so reports nothing. The jobs come from a file, one
JSON object per line ({"name", "conclusion", "html_url"}) or one {"jobs": [...]} document, so the
decision runs without a network; without --apply nothing is changed on GitHub.
"""

import argparse
import json
import re
import subprocess
import sys

LABEL = "scheduled-run-failed"
LABEL_COLOR = "B60205"
LABEL_DESCRIPTION = "A scheduled workflow run failed; a person closes the issue"
FAILED = ("failure", "timed_out", "cancelled")
MAX_JOBS_LISTED = 40


def marker(workflow):
    return f"<!-- varka-scheduled-failure: {one_line(workflow)} -->"


def one_line(text):
    """The text on one line, so a name cannot break out of a title or a marker comment."""
    return re.sub(r"\s+", " ", text.replace("-->", "--")).strip()


def code(text):
    """A markdown code span that holds the text whatever it contains."""
    return "`" + one_line(text).replace("`", "'") + "`"


def read_jobs(text):
    """The jobs from gh's output: one object per line, or a {"jobs": [...]} document."""
    stripped = text.strip()
    if not stripped:
        return []
    try:
        whole = json.loads(stripped)
        if isinstance(whole, dict) and "jobs" in whole:
            return whole["jobs"]
        if isinstance(whole, dict):
            return [whole]
        return list(whole)
    except json.JSONDecodeError:
        return [json.loads(line) for line in stripped.splitlines() if line.strip()]


def failed_jobs(jobs):
    return [j for j in jobs if j.get("conclusion") in FAILED]


def issue_title(workflow, date):
    return f"Scheduled run failed: {one_line(workflow)} (first red {date})"


def job_lines(failed):
    lines = []
    for job in failed[:MAX_JOBS_LISTED]:
        url = job.get("html_url") or job.get("url") or ""
        name = code(job.get("name", "?"))
        lines.append(f"- {name}: {job.get('conclusion')}" + (f", {url}" if url else ""))
    if len(failed) > MAX_JOBS_LISTED:
        lines.append(f"- and {len(failed) - MAX_JOBS_LISTED} more; the run lists them all")
    return lines


def report(workflow, run_url, sha, failed):
    """The run's facts, the body of a new issue and of a comment alike."""
    lines = [
        f"Run: {run_url}",
        f"Head commit: {sha}",
        f"Failed jobs ({len(failed)}):",
        *job_lines(failed),
    ]
    return "\n".join(lines)


def issue_body(workflow, run_url, sha, failed):
    return (
        f"The scheduled workflow {code(workflow)} failed.\n\n"
        + report(workflow, run_url, sha, failed)
        + "\n\nThis issue is the one place its red runs are reported: a later red run of the "
        "same workflow comments here while the issue is open, and a person closes it once the "
        "cause is fixed or filed.\n\n" + marker(workflow) + "\n"
    )


def belongs_to(issue, workflow):
    if marker(workflow) in (issue.get("body") or ""):
        return True
    return (issue.get("title") or "").startswith(f"Scheduled run failed: {one_line(workflow)} (")


def decide(workflow, run_url, sha, jobs, open_issues, date):
    """("none",), ("comment", number, text) or ("create", title, body) for the run."""
    failed = failed_jobs(jobs)
    if not failed:
        return ("none",)
    mine = sorted((i for i in open_issues if belongs_to(i, workflow)), key=lambda i: i["number"])
    if mine:
        return ("comment", mine[0]["number"], report(workflow, run_url, sha, failed) + "\n")
    return ("create", issue_title(workflow, date), issue_body(workflow, run_url, sha, failed))


def gh(args, repo, stdin=None):
    result = subprocess.run(
        ["gh", *args, "--repo", repo], capture_output=True, text=True, check=False, input=stdin
    )
    if result.returncode != 0:
        sys.exit(f"gh {' '.join(args)} failed:\n{result.stderr}")
    return result.stdout


def open_issues_of(repo):
    out = gh(
        ["issue", "list", "--label", LABEL, "--state", "open", "--limit", "200",
         "--json", "number,title,body"],
        repo,
    )  # fmt: skip
    return json.loads(out or "[]")


def ensure_label(repo):
    result = subprocess.run(
        ["gh", "label", "create", LABEL, "--color", LABEL_COLOR,
         "--description", LABEL_DESCRIPTION, "--repo", repo],
        capture_output=True, text=True, check=False,
    )  # fmt: skip
    if result.returncode != 0 and "already exists" not in result.stderr:
        sys.exit(f"gh label create {LABEL} failed:\n{result.stderr}")


def selftest():
    jobs = [
        {"name": "ok", "conclusion": "success", "html_url": "u0"},
        {"name": "Run / Build `x`\nmodules", "conclusion": "failure", "html_url": "u1"},
        {"name": "slow", "conclusion": "timed_out", "html_url": "u2"},
        {"name": "stopped", "conclusion": "cancelled", "html_url": "u3"},
        {"name": "neutral", "conclusion": "neutral", "html_url": "u5"},
        {"name": "skipped", "conclusion": "skipped", "html_url": "u4"},
    ]
    names = [j["name"] for j in failed_jobs(jobs)]
    assert names == ["Run / Build `x`\nmodules", "slow", "stopped"], names
    green = decide("W", "r", "s", [jobs[0], jobs[4], jobs[5]], [], "2026-10-20")
    assert green == ("none",), green
    kind, title, body = decide("Varka fuzz", "https://r/1", "abc123", jobs, [], "2026-10-20")
    assert kind == "create"
    assert title == "Scheduled run failed: Varka fuzz (first red 2026-10-20)", title
    assert "https://r/1" in body and "abc123" in body and marker("Varka fuzz") in body
    assert "Failed jobs (3)" in body and "neutral" not in body, body
    assert "`Run / Build 'x' modules`: failure, u1" in body, body
    # The name that is a prefix of another workflow's must not match it.
    own = {"number": 7, "title": "x", "body": "text\n" + marker("Varka fuzz") + "\n"}
    other = {"number": 3, "title": "Scheduled run failed: Varka fuzz matrix (first red d)"}
    renamed = {"number": 9, "title": "Scheduled run failed: Varka fuzz (first red d)", "body": ""}
    assert not belongs_to(other, "Varka fuzz")
    assert belongs_to(renamed, "Varka fuzz") and belongs_to(own, "Varka fuzz")
    dec = decide("Varka fuzz", "https://r/2", "def456", jobs, [other, renamed, own], "d2")
    assert dec[0] == "comment" and dec[1] == 7, dec
    assert "https://r/2" in dec[2] and "def456" in dec[2] and "slow" in dec[2], dec
    again = decide("Varka fuzz", "https://r/2", "d", jobs, [other], "d2")
    assert again[0] == "create", again
    # A hostile name stays on one line and inside its marker and code span.
    hostile = "a --> <script>\n# x"
    assert "\n" not in marker(hostile) and "-->" not in marker(hostile)[:-3]
    many = [{"name": f"j{i}", "conclusion": "failure"} for i in range(MAX_JOBS_LISTED + 5)]
    assert "and 5 more" in decide("W", "r", "s", many, [], "d")[2]
    # Both input shapes of gh.
    nd = '{"name":"a","conclusion":"failure"}\n{"name":"b","conclusion":"success"}\n'
    assert len(read_jobs(nd)) == 2 and len(read_jobs('{"jobs":[{"name":"a"}]}')) == 1
    assert read_jobs("") == [] and len(read_jobs('{"name":"a"}')) == 1
    print("selftest ok")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--workflow")
    parser.add_argument("--run-url")
    parser.add_argument("--sha")
    parser.add_argument("--date", help="UTC date of this run, YYYY-MM-DD")
    parser.add_argument("--jobs-file", help="the run's jobs, as gh prints them")
    parser.add_argument("--issues-file", help="open labelled issues as JSON; gh when absent")
    parser.add_argument("--repo", default="vecbricks/varka")
    parser.add_argument("--apply", action="store_true", help="change GitHub; dry run otherwise")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return
    for need in ("workflow", "run_url", "sha", "date", "jobs_file"):
        if not getattr(args, need):
            parser.error(f"--{need.replace('_', '-')} is required")
    with open(args.jobs_file, encoding="utf-8") as handle:
        jobs = read_jobs(handle.read())
    if args.issues_file:
        with open(args.issues_file, encoding="utf-8") as handle:
            issues = json.loads(handle.read() or "[]")
    else:
        issues = open_issues_of(args.repo) if args.apply else []
    decision = decide(args.workflow, args.run_url, args.sha, jobs, issues, args.date)
    if decision[0] == "none":
        print("no failed job in the run; nothing to report")
    elif decision[0] == "comment":
        print(f"comment on issue #{decision[1]}:\n{decision[2]}")
        if args.apply:
            gh(["issue", "comment", str(decision[1]), "--body-file", "-"], args.repo, decision[2])
    else:
        print(f"open an issue titled {decision[1]!r}:\n{decision[2]}")
        if args.apply:
            ensure_label(args.repo)
            gh(
                ["issue", "create", "--title", decision[1], "--label", LABEL, "--body-file", "-"],
                args.repo,
                decision[2],
            )


if __name__ == "__main__":
    main()
