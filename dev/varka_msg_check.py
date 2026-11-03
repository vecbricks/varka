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
The house rules for commit messages and pull request text (VARKA-492), each a slip that reached
vecbricks/varka more than once.

Commit messages and pull request bodies alike:
  * the only attribution is one `Generated-by: <tool> (<model>)` line (ASF generative tooling
    guidance): no Co-Authored-By or Claude-Session trailer, no session link, no robot footer,
    which a tool's default text adds and the ASF does not ask for.

Pull requests also:
  * every section heading of .github/PULL_REQUEST_TEMPLATE, verbatim and in order;
  * paragraphs soft-wrapped: GitHub renders a hard-wrapped body with its line breaks;
  * a title of at most 80 characters, with no "Step N" or "Task N" (it names the goal);
  * "Why are the changes needed?" opening with the reason, not a task id or plan row, which a
    reader outside the project cannot follow;
  * under 1000 changed lines (VARKA-554): added plus removed against the merge base, counting
    hand-written source (.java, .scala, .py, .sh, .yml) and markdown, never a generated file.

  dev/varka_msg_check.py commit FILE         # a commit message (the commit-msg hook)
  dev/varka_msg_check.py range A..B [--not REF...]
                                             # the branch's own commit messages in a range:
                                             # never one reachable from a REF, such as Varka's
                                             # or Apache Spark's master, whose commits a merge
                                             # brings in and which follow their own rules
  dev/varka_msg_check.py pr --title T --body-file F [--base REF]
                                             # with HEAD's size against REF, by default
                                             # $VARKA_DIFF_BASE or the merge base with
                                             # origin/master
  dev/varka_msg_check.py pr --number N [--base REF]
                                             # a pull request of vecbricks/varka, through gh
  dev/varka_msg_check.py pr --this-branch    # the open pull request of a CI run's branch, if
                                             # any; its size is the `size` step's, in the lint job
  dev/varka_msg_check.py size [--base REF] [--head REF]  # the size alone
  dev/varka_msg_check.py --selftest

Exit status is the number of findings.
"""

import difflib
import importlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# The merge driver's list of generated files and its docs-block reader (VARKA-554).
sys.path.insert(0, os.path.join(ROOT, "dev"))
generated = importlib.import_module("varka_generated_merge")
TEMPLATE = os.path.join(ROOT, ".github", "PULL_REQUEST_TEMPLATE")
TITLE_LIMIT = 80

FORBIDDEN = [
    (re.compile(r"^Co-Authored-By:", re.I | re.M), "a Co-Authored-By trailer"),
    (re.compile(r"^Claude-Session:", re.I | re.M), "a Claude-Session trailer"),
    (re.compile(r"https?://claude\.ai/code\S*"), "a session link"),
    (re.compile(r"Generated with \[?Claude Code"), "the tool's default footer"),
    (re.compile("\U0001f916"), "the robot footer"),
]
GENERATED_BY = re.compile(r"^Generated-by:.*$", re.M)
GENERATED_BY_FORM = re.compile(r"^Generated-by: \S.*\(.+\)\s*$")


def attribution(text, where):
    out = []
    for pattern, what in FORBIDDEN:
        if pattern.search(text):
            out.append(f"{where}: {what}; the only attribution is one Generated-by line")
    lines = GENERATED_BY.findall(text)
    if len(lines) > 1:
        out.append(f"{where}: {len(lines)} Generated-by lines; one names every tool")
    for line in lines:
        if not GENERATED_BY_FORM.match(line):
            out.append(f"{where}: '{line.strip()}' is not 'Generated-by: <tool> (<model>)'")
    return out


SCISSORS = "# ------------------------ >8 ------------------------"


def commit_message(text, where="commit message"):
    # git drops everything below the scissors line (`git commit -v` puts the diff there) and
    # strips comment lines before it records the message; so does this.
    text = text.split(SCISSORS)[0]
    text = "\n".join(line for line in text.splitlines() if not line.startswith("#"))
    return attribution(text, where)


def headings():
    with open(TEMPLATE, encoding="utf-8") as f:
        return [line.rstrip() for line in f if line.startswith("### ")]


def prose_blocks(body):
    """Paragraphs outside code fences, as lists of lines, without lists, tables and headings."""
    block, fenced = [], False
    for line in body.splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
            if block:
                yield block
            block = []
            continue
        structural = re.match(r"\s*([-*+>|#]|\d+\.\s|<!--)", line)
        if fenced or not line.strip() or structural:
            if block:
                yield block
            block = []
        else:
            block.append(line)
    if block:
        yield block


def pull_request(title, body):
    out = attribution(body, "pull request body")
    found = [line.rstrip() for line in body.splitlines() if line.startswith("### ")]
    wanted = headings()
    missing = [h for h in wanted if h not in found]
    for h in missing:
        out.append(f"pull request body: the template heading '{h}' is missing or reworded")
    if not missing and [h for h in found if h in wanted] != wanted:
        out.append("pull request body: the template's headings are out of order")
    for block in prose_blocks(body):
        # A paragraph of three or more lines all broken near one width was wrapped by hand.
        if len(block) >= 3 and all(55 <= len(line) <= 100 for line in block[:-1]):
            out.append(
                f"pull request body: a hard-wrapped paragraph ('{block[0][:40]}...'); "
                "write each paragraph on one line"
            )
            break
    if len(title) > TITLE_LIMIT:
        out.append(f"pull request title: {len(title)} characters, over {TITLE_LIMIT}")
    if re.search(r"\b(Step|Task) \d", title):
        out.append("pull request title: names a step or task; name the goal")
    why = re.search(r"^### Why are the changes needed\?\s*\n+(.*)", body, re.M)
    if why and re.match(r"\s*(Rows?|VARKA-|Tasks?|Steps?|Item)\s*[-#]?\d", why.group(1)):
        out.append(
            "pull request body: 'Why' opens with a task id or plan row; open with the reason"
        )
    return out


def from_gh(number):
    fields = "title,body,headRefOid"
    out = subprocess.run(
        ["gh", "pr", "view", str(number), "--repo", "vecbricks/varka", "--json", fields],
        capture_output=True,
        text=True,
        check=True,
    )
    pr = json.loads(out.stdout)
    return pr["title"], pr["body"], pr["headRefOid"]


def open_pr_of_this_branch():
    """The open pull request of vecbricks/varka whose head is this CI run's branch, or None.

    A pull_request event names it; a push build on a fork does not know its pull request, so it
    is found by the branch, as dev/varka_new_tests.py finds a fix label.
    """
    event = os.environ.get("GITHUB_EVENT_PATH")
    if event and os.path.exists(event):
        with open(event, encoding="utf-8") as f:
            pr = json.load(f).get("pull_request")
        if pr:
            return pr.get("title", ""), pr.get("body") or ""
    branch = os.environ.get("GITHUB_HEAD_REF") or os.environ.get("GITHUB_REF_NAME") or ""
    owner = (os.environ.get("GITHUB_REPOSITORY") or "/").split("/")[0]
    if not branch or not owner:
        return None
    head = urllib.parse.quote(f"{owner}:{branch}", safe=":")
    url = f"https://api.github.com/repos/vecbricks/varka/pulls?state=open&head={head}"
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=60) as resp:
        pulls = json.loads(resp.read().decode())
    return (pulls[0]["title"], pulls[0]["body"] or "") if pulls else None


def commits_in(rev_range, exclude=()):
    excluded = []
    for ref in exclude:
        ok = subprocess.run(
            ["git", "rev-parse", "--verify", "-q", ref + "^{commit}"], cwd=ROOT, capture_output=True
        )
        if ok.returncode == 0:
            excluded.append("^" + ref)
    shas = subprocess.run(
        ["git", "rev-list", "--no-merges", rev_range, *excluded],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.split()
    for sha in shas:
        msg = subprocess.run(
            ["git", "log", "-1", "--format=%B", sha], cwd=ROOT, capture_output=True, text=True
        ).stdout
        yield sha[:11], msg


# --- the size of a pull request (VARKA-554) ------------------------------------------------

SIZE_CAP = 1000
COUNTED = re.compile(r"\.(java|scala|py|sh|yml|md)$")
# Generated beyond the merge driver's list, which names the files it merges: results files, and
# a markdown report whose first lines say a tool wrote it (the coverage and mutation reports).
RESULTS = re.compile(r"(^|/)results/")
REPORT_MARK = re.compile(r"^Generated by ", re.M)
# A sync merges Apache Spark's master, so its merge-base diff is upstream's work, not Varka's.
SYNC_BRANCH = "varka-sync-apache-"


def git(cwd, *args):
    # Outside this repository (the self-test's fixture) a hook's GIT_DIR or GIT_INDEX_FILE
    # would send the command into the outer repository; the fixture's environment drops them.
    env = None if cwd == ROOT else generated.fixture_env()
    return subprocess.run(["git", *args], cwd=cwd, env=env, capture_output=True, text=True)


def numstat(base, head, cwd):
    """(added, removed, path) per changed file, a binary file's counts None, a rename's path
    its new one."""
    out = git(cwd, "diff", "--numstat", "-z", "-M", base, head)
    if out.returncode:
        raise ValueError(out.stderr.strip())
    tokens, rows = out.stdout.split("\0"), []
    while len(tokens) > 1:
        added, removed, path = tokens.pop(0).split("\t", 2)
        if not path:  # a rename: the old path, then the new
            path = tokens[1]
            del tokens[:2]
        n = None if added == "-" else (int(added), int(removed))
        rows.append((n, path))
    return rows


def counted_lines(path, n, base, head, cwd, generated_paths):
    """The lines of one changed file the cap counts, and why a file counts none."""
    if n is None:
        return 0, "binary"
    if not COUNTED.search(path):
        return 0, "not source or markdown"
    if RESULTS.search(path):
        return 0, "generated"
    if path in generated_paths and path != generated.DOCS:
        return 0, "generated"
    if not path.endswith(".md"):
        return sum(n), None
    sides = [git(cwd, "show", f"{rev}:{path}") for rev in (base, head)]
    texts = [side.stdout if side.returncode == 0 else "" for side in sides]
    if REPORT_MARK.search("\n".join((texts[1] or texts[0]).split("\n")[:5])):
        return 0, "generated"
    if path != generated.DOCS:
        return sum(n), None
    # Two thirds of the docs file is prose; its generated blocks are dropped from both sides.
    a, b = (generated.hand_lines(t) for t in texts)
    diff = difflib.unified_diff(a, b, n=0, lineterm="")
    return sum(1 for line in diff if line[:1] in "+-" and line[:3] not in ("+++", "---")), None


def pr_size(base, head="HEAD", cwd=ROOT, branch=None):
    """(findings, the line to print): the counted size of head against base."""
    if branch and branch.startswith(SYNC_BRANCH):
        return [], f"pull request size: not counted on {branch}, a sync of Apache Spark"
    if git(cwd, "rev-parse", f"{base}^{{commit}}").stdout == git(cwd, "rev-parse", head).stdout:
        return [], "pull request size: the head is its base; nothing to count"
    gen = set(generated.generated_files())
    rows = []
    for n, path in numstat(base, head, cwd):
        rows.append((counted_lines(path, n, base, head, cwd, gen)[0], sum(n or (0,)), path))
    count, total = sum(r[0] for r in rows), sum(r[1] for r in rows)
    where = f"against {git(cwd, 'rev-parse', '--short=11', base).stdout.strip()}"
    if count < SIZE_CAP:
        line = f"pull request size: {count} counted lines of {total} changed {where}"
        return [], line + f", under the cap of {SIZE_CAP}"
    top = ", ".join(f"{p} ({c})" for c, _, p in sorted(rows, reverse=True)[:5] if c)
    return [
        f"pull request size: {count} counted lines {where} ({total} changed in all), at or "
        f"over the cap of {SIZE_CAP}; the largest: {top}; split it into pull requests under "
        "the cap"
    ], None


def default_base(head="HEAD"):
    env = os.environ.get("VARKA_DIFF_BASE")
    if env:
        return env
    out = git(ROOT, "merge-base", head, "origin/master")
    return out.stdout.strip() if out.returncode == 0 else None


def size_findings(base, head="HEAD"):
    """pr_size for a run of this script: the base found, the line printed."""
    base = base or default_base(head)
    if not base:
        return ["pull request size: no merge base with origin/master; name one with --base"]
    branch = None
    if head == "HEAD":
        branch = os.environ.get("GITHUB_HEAD_REF") or os.environ.get("GITHUB_REF_NAME")
        branch = branch or git(ROOT, "rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    try:
        findings, line = pr_size(base, head, ROOT, branch)
    except ValueError as e:
        return [f"pull request size: could not diff {base}..{head}: {e}"]
    if line:
        print(line)
    return findings


def size_selftest():
    """pr_size over a temporary repository: (name, got, want) cases."""
    with tempfile.TemporaryDirectory() as tmp:
        env = generated.fixture_env()

        def run(*args):
            subprocess.run(args, cwd=tmp, env=env, check=True, capture_output=True)

        def write(rel, text):
            os.makedirs(os.path.dirname(os.path.join(tmp, rel)), exist_ok=True)
            with open(os.path.join(tmp, rel), "w", encoding="utf-8") as f:
                f.write(text)

        def commit():
            run("git", "add", "-A")
            run("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c")
            return git(tmp, "rev-parse", "HEAD").stdout.strip()

        def lines(k, tag="x"):
            return "".join(f"{tag} {i}\n" for i in range(k))

        block = "<!-- BEGIN generated t -->\n{}<!-- END generated t -->\n"
        run("git", "init", "-q")
        write("A.java", lines(10))
        write("b.py", lines(30))
        write(generated.DOCS, "prose\n" + block.format(lines(20)) + "tail\n")
        write("sql/varka/coverage/r.md", "# r\n\nGenerated by a tool.\n" + lines(3))
        base = commit()
        write("A.java", lines(15))  # 5 added
        os.rename(os.path.join(tmp, "b.py"), os.path.join(tmp, "c.py"))  # a rename: 0
        write("sql/varka/coverage.json", lines(50))  # generated
        write(generated.DOCS, "prose!\n" + block.format(lines(20, "y")) + "tail\n")  # 2
        write("sql/varka/coverage/r.md", "# r\n\nGenerated by a tool.\n" + lines(10))
        write("sql/varka/plans/m8/results/r.md", lines(9))  # generated
        write("notes.txt", lines(4))  # not counted
        head = commit()
        under = pr_size(base, head, tmp)
        write("Big.scala", lines(SIZE_CAP - 7))
        over = pr_size(base, commit(), tmp)
        sync = pr_size(base, "HEAD", tmp, branch=SYNC_BRANCH + "1103")
    return [
        ("a pull request under the cap", under[0], 0),
        ("its count and total", [] if "7 counted lines of 117 changed" in under[1] else [1], 0),
        ("a pull request at the cap", over[0], 1),
        ("the largest file named", [] if "Big.scala (993)" in "".join(over[0]) else [1], 0),
        ("a sync of Apache Spark", sync[0] if sync[1] else [1], 0),
    ]


def selftest():
    good = "[VARKA-1] Do a thing\n\nBody.\n\nGenerated-by: Claude Code (Opus 5.5)\n"
    cases = [
        ("a clean message", commit_message(good), 0),
        ("a co-author trailer", commit_message(good + "Co-Authored-By: X <x@y>\n"), 1),
        ("a session link", commit_message(good + "Claude-Session: https://claude.ai/code/s\n"), 2),
        ("two Generated-by lines", commit_message(good + "Generated-by: Other (1)\n"), 1),
        ("a Generated-by without a model", commit_message("x\n\nGenerated-by: Claude Code\n"), 1),
        ("a comment line git strips", commit_message(good + "# Co-Authored-By: x\n"), 0),
        (
            "a diff below the scissors",
            commit_message(good + SCISSORS + "\n+Co-Authored-By: x\n"),
            0,
        ),
    ]
    body = "".join(f"{h}\n\nText.\n\n" for h in headings())
    body += "Generated-by: Claude Code (Opus 5.5)\n"
    wrapped = body.replace("Text.", ("word " * 14 + "\n") * 3 + "end.", 1)
    why_row = body.replace(
        "### Why are the changes needed?\n\nText.", "### Why are the changes needed?\n\nRow 12."
    )
    cases += [
        ("a clean pull request", pull_request("[VARKA-1] Short goal", body), 0),
        ("a missing heading", pull_request("t", body.replace(headings()[0], "### What")), 1),
        ("a hard-wrapped paragraph", pull_request("t", wrapped), 1),
        ("a long title", pull_request("x" * 81, body), 1),
        ("a step in the title", pull_request("[VARKA-1] Step 2 of the thing", body), 1),
        ("'Why' opening with a row", pull_request("t", why_row), 1),
        ("a footer", pull_request("t", body + "\U0001f916 Generated with [Claude Code](u)\n"), 2),
    ]
    cases += size_selftest()
    fails = 0
    for name, got, want in cases:
        if len(got) != want:
            print(f"varka_msg_check selftest: {name}: expected {want} finding(s), got {got}")
            fails += 1
    if fails == 0:
        print(f"varka_msg_check selftest: ok ({len(cases)} cases)")
    return fails


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0 if argv else 2
    if argv[0] == "--selftest":
        return selftest()
    if argv[0] == "commit":
        with open(argv[1], encoding="utf-8") as f:
            findings = commit_message(f.read())
    elif argv[0] == "range":
        findings = []
        exclude = argv[3:] if argv[2:3] == ["--not"] else []
        for sha, msg in commits_in(argv[1], exclude):
            findings += commit_message(msg, f"commit {sha}")
    elif argv[0] == "pr":
        if argv[1:2] == ["--this-branch"]:
            try:
                pr = open_pr_of_this_branch()
            except (OSError, ValueError) as e:
                print(f"::warning::could not read the pull request ({e}); not checked")
                return 0
            if pr is None:
                print("no open pull request for this branch; nothing to check")
                return 0
            findings = pull_request(*pr)
            for f in findings:
                print(f)
            return len(findings)
        opts = dict(zip(argv[1::2], argv[2::2]))
        head = "HEAD"
        if "--number" in opts:
            title, body, head = from_gh(opts["--number"])
        else:
            title = opts.get("--title", "")
            with open(opts["--body-file"], encoding="utf-8") as f:
                body = f.read()
        if git(ROOT, "cat-file", "-e", f"{head}^{{commit}}").returncode == 0:
            findings = size_findings(opts.get("--base"), head)
        else:
            findings = []
            print(f"pull request size: not counted; its head {head} is not in this clone")
        findings += pull_request(title, body)
    elif argv[0] == "size":
        opts = dict(zip(argv[1::2], argv[2::2]))
        findings = size_findings(opts.get("--base"), opts.get("--head", "HEAD"))
    else:
        print(__doc__)
        return 2
    for f in findings:
        print(f)
    # An exit status wraps at 256; a count past that would read as success.
    return min(len(findings), 100)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
