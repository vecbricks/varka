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
    hand-written source, text and data (.java .scala .py .sh .yml .yaml .xml .sql .properties
    .md .txt .tsv), never a generated or results file, a transcribed paper or a READING note;
    a sync of Apache Spark's master counts its own commits, and of its merge only the lines
    that differ from both parents (VARKA-588).

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

import codecs
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
    fields = "title,body,headRefOid,headRefName"
    out = subprocess.run(
        ["gh", "pr", "view", str(number), "--repo", "vecbricks/varka", "--json", fields],
        capture_output=True,
        text=True,
        check=True,
    )
    pr = json.loads(out.stdout)
    return pr["title"], pr["body"], pr["headRefOid"], pr["headRefName"]


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
# Hand-written source, plans and notes, and hand-curated data; within these, the generated files
# are told apart below. A JSON, SMT-LIB or binary file never counts.
COUNTED = re.compile(r"\.(java|scala|py|sh|yml|yaml|xml|sql|properties|md|txt|tsv)$")
SOURCE = re.compile(r"\.(java|scala|py|sh|yml|yaml|xml|sql|properties)$")
# Results and golden files: the text under a results/ or benchmarks/ directory, or Spark's plan
# stability goldens; source there still counts.
RESULTS = re.compile(r"(^|/)(results|benchmarks|[^/]*-plan-stability)/")
# Not counted by the owner's word: transcribed papers and the READING notes.
EXEMPT = re.compile(r"^sql/varka/papers/|(^|/)READING_[^/]*\.md$")
# A file a tool writes says so in its first lines ("Generated by", "Generated, not written");
# a markdown or data file in its first five, past which the phrase is a plan's prose.
# "Automatically generated by" is Spark's suites' header, in any case.
GENERATED_MARK = re.compile(
    r"^\W*(<p>)?(?i:(automatically )?generated(,| by\b| with\b))|DO NOT EDIT", re.M
)
# Spark's SQL test inputs are written by hand, though one carries a golden's header.
HAND_INPUTS = re.compile(r"(^|/)sql-tests/inputs/")
# A sync merges Apache Spark's master, so its merge-base diff is upstream's work, not Varka's.
SYNC_BRANCH = "varka-sync-apache-"


def git(cwd, *args, env=None):
    # Outside this repository (the self-test's fixture) a hook's GIT_DIR or GIT_INDEX_FILE
    # would send the command into the outer repository; the fixture's environment drops them.
    if env is None and cwd != ROOT:
        env = generated.fixture_env()
    return subprocess.run(
        ["git", *args], cwd=cwd, env=env, capture_output=True, text=True, errors="replace"
    )


_ATTR_SOURCE_GIT = []  # whether this git has `--attr-source`, once asked


def attr_source_git():
    """Whether this git takes `git --attr-source=<tree>` (git 2.42's)."""
    if not _ATTR_SOURCE_GIT:
        got = re.search(r"(\d+)\.(\d+)", git(ROOT, "--version").stdout)
        _ATTR_SOURCE_GIT.append(bool(got) and tuple(map(int, got.groups())) >= (2, 42))
    return _ATTR_SOURCE_GIT[0]


def show(cwd, rev, path):
    out = git(cwd, "show", f"{rev}:{path}")
    return out.stdout if out.returncode == 0 else None


def attr_git(cwd, attrs, *args):
    """`git <args>` reading attributes from the tree `attrs` alone (None: none at all)."""
    pre = ["-c", f"core.attributesFile={os.devnull}"]
    if attrs is not None:
        if not attr_source_git():
            raise ValueError("git 2.42 or later is needed for --attr-source")
        pre = [f"--attr-source={attrs}", *pre]
    env = generated.fixture_env() if cwd != ROOT else dict(os.environ)
    env["GIT_ATTR_NOSYSTEM"] = "1"
    return git(cwd, *pre, *args, env=env)


def as_text(cwd, base, head, old, path):
    """(added, removed) of one file diffed as text whatever its bytes: `--numstat` ignores
    `--text`, so the counts are summed from the `-U0` patch's hunk headers."""
    out = attr_git(cwd, base, "diff", "--text", "-U0", "-M", base, head, "--", old, path)
    if out.returncode:
        raise ValueError(f"git diff --text failed: {out.stderr.strip()}")
    added = removed = 0
    for m in re.finditer(r"^@@ -\d+(?:,(\d+))? \+\d+(?:,(\d+))? @@", out.stdout, re.M):
        removed += int(m.group(1) or 1)
        added += int(m.group(2) or 1)
    return added, removed


def numstat(cwd, attrs, *args, ok=(0,)):
    """(added, removed) or None for a binary file, the new path and the old, per changed file
    of `git <args> --numstat -z -M`; a git exit outside `ok`, a count that is not a number, or
    output left over (git's warning that it skips an octopus merge) raises ValueError, which
    the check reports as a count it could not make. Whether a file diffs as binary is read
    from the attributes of the tree `attrs` only (None: no repository, so none), never the
    head's, the user's or the system's: a pull request's `-diff` line would hide its edit."""
    out = attr_git(cwd, attrs, *args, "--numstat", "-z", "-M")
    if out.returncode not in ok:
        raise ValueError(f"git {args[0]} failed: {out.stderr.strip()}")
    tokens, rows = out.stdout.split("\0"), []
    while len(tokens) > 1:
        added, removed, path = (tokens.pop(0).split("\t", 2) + ["", ""])[:3]
        old = path
        if not path:  # a rename: the old path, then the new
            old, path = tokens[:2]
            del tokens[:2]
        if (added, removed) == ("-", "-"):
            rows.append((None, path, old))
        elif added.isdigit() and removed.isdigit():
            rows.append(((int(added), int(removed)), path, old))
        else:
            raise ValueError(f"git {args[0]} gave {added!r} {removed!r} for {path!r}")
    if tokens and tokens[0]:
        raise ValueError(f"git {args[0]} gave {tokens[0].strip()!r}, not a count")
    return rows


def text_diff(a, b):
    """Lines added plus removed between two line lists, as git counts them."""
    with tempfile.TemporaryDirectory() as tmp:
        for name, lines in (("a", a), ("b", b)):
            with open(os.path.join(tmp, name), "w", encoding="utf-8") as f:
                f.write("".join(line + "\n" for line in lines))
        # Exit 1: they differ. Not a repository, so no tree to read attributes from.
        rows = numstat(tmp, None, "diff", "--no-index", "a", "b", ok=(0, 1))
    if any(n is None for n, _, _ in rows):
        raise ValueError("git diff --no-index read a generated block's prose as binary")
    return sum(sum(n) for n, _, _ in rows)


def exempt_why(path, text, marked):
    """Why one side of a changed file counts nothing, or None: `text` is that side's content
    (None where the file is not on it) and `marked` whether that side's attributes mark it."""
    if not COUNTED.search(path):
        return "not hand-written"
    if EXEMPT.search(path):
        return "a paper or a reading note"
    if RESULTS.search(path) and not SOURCE.search(path):
        return "results"
    if path in generated.BLOCKS:
        return None  # its prose counts, whatever a clone's info/attributes says of it
    if marked:
        return "generated"
    window = "\n".join((text or "").split("\n")[: 40 if SOURCE.search(path) else 5])
    if GENERATED_MARK.search(window) and not HAND_INPUTS.search(path):
        return "generated"
    return None


def counted_lines(row, base, head, cwd, marks):
    """(the lines of one changed file the cap counts, why it counts none, a note to print). A
    file the base has is judged by the base's path, attributes and text, so a pull request
    cannot exempt its own edit by changing them; a file new in the head by the head's."""
    n, path, old = row
    texts = [show(cwd, base, old), show(cwd, head, path)]
    if n is None and texts[0] is not None and "\0" not in texts[0] and "\0" in (texts[1] or ""):
        # A text file the head makes binary with a NUL: counted as text, by the base's rules.
        n = as_text(cwd, base, head, old, path)
    if n is None:
        return 0, "binary", None
    head_why = exempt_why(path, texts[1], path in marks[1])
    if texts[0] is None:
        note = None
        if head_why and path in marks[1] and old not in marks[0]:
            note = f"{path}: new, marked generated by attributes this pull request adds"
        return (0, head_why, note) if head_why else (sum(n), None, None)
    why = exempt_why(old, texts[0], old in marks[0])
    if why:
        return 0, why, None
    note = (
        f"{path}: exemption added by this pull request ({head_why}), counted" if head_why else None
    )
    if path in generated.BLOCKS:
        # A file with generated blocks counts its prose: both sides drop the blocks.
        a, b = (generated.hand_lines(path, t or "") for t in texts)
        if a is not None and b is not None:
            return text_diff(a, b), None, note
    return sum(n), None, note


def marked(cwd, rev, paths):
    """The `paths` that `rev`'s attributes mark generated: git's reading, or the hand reading
    of `generated.ATTRIBUTES` where this git cannot read attributes at a revision."""
    env = None if cwd == ROOT else generated.fixture_env()
    got = generated.git_generated(paths, rev, cwd, env)
    if got is None:
        rules = generated.attribute_rules(lambda path: show(cwd, rev, path))
        got = {p for p in paths if generated.is_generated(p, rules)}
    return got


def size_rows(base, head, cwd, rows=None):
    """((counted, changed, path, why) per changed file of head against base, the notes to
    print); `rows` gives the changed files where they are not `base..head`'s diff."""
    rows = numstat(cwd, base, "diff", base, head) if rows is None else rows
    marks = (marked(cwd, base, [r[2] for r in rows]), marked(cwd, head, [r[1] for r in rows]))
    out, notes = [], []
    for row in rows:
        c, why, note = counted_lines(row, base, head, cwd, marks)
        out.append((c, sum(row[0] or (0,)), row[1], why))
        notes += [note] if note else []
    return out, notes


def apache_ref(cwd):
    """Apache Spark's master: the CI step's fetched ref, or a remote of apache/spark."""
    if git(cwd, "rev-parse", "-q", "--verify", "refs/apache/master").returncode == 0:
        return "refs/apache/master"
    for remote in git(cwd, "remote").stdout.split():
        url = git(cwd, "remote", "get-url", remote).stdout.strip()
        if re.search(r"github\.com[:/]apache/spark(\.git)?$", url):
            return f"{remote}/master"
    return None


def sync_commits(base, head, cwd):
    """A sync's own commits, its merges included, or None when base..head merges no commit of
    Apache Spark's master; raises ValueError when there is no Apache Spark master to tell."""
    apache = apache_ref(cwd)
    if not apache:
        raise ValueError("no Apache Spark master to check the sync's merge against")
    for merge in git(cwd, "rev-list", "--merges", f"{base}..{head}").stdout.split():
        parents = git(cwd, "rev-list", "--parents", "-n1", merge).stdout.split()[1:]
        if any(git(cwd, "merge-base", "--is-ancestor", p, apache).returncode == 0 for p in parents):
            out = git(cwd, "rev-list", f"{base}..{head}", "--not", apache)
            return out.stdout.split()
    return None


def own_rows(commit, cwd):
    """A sync's commit against its first parent, or a merge's own changes: its conflict
    resolutions and any edit made in it, the merge against git's automatic merge of its
    parents (`--remerge-diff`, git 2.36's)."""
    parents = len(git(cwd, "rev-list", "--parents", "-n1", commit).stdout.split()) - 1
    if parents > 2:
        raise ValueError(f"{commit} is an octopus merge, which --remerge-diff cannot replay")
    if parents < 2:
        return size_rows(f"{commit}^", commit, cwd)
    rows = numstat(cwd, f"{commit}^1", "show", "--remerge-diff", "--format=", commit)
    return size_rows(f"{commit}^1", commit, cwd, both_parents_rows(commit, cwd, rows))


def both_parents_rows(merge, cwd, rows):
    """`rows`, a merge's `--remerge-diff` counts, each cut to the lines of the merge that
    differ from both parents (VARKA-588): a conflict resolved by keeping one side, hunk by
    hunk, counts nothing, though `--remerge-diff` counts the other side and the markers,
    while a line written by hand matches neither parent and counts in full. The combined
    diff (`--cc`) shows those lines as `++` and `--`, and leaves out a file in which every
    hunk equals one parent or the other, which so counts nothing. A binary row keeps its
    None. The rule cannot tell a side kept from work dropped: a merge that deletes lines or a
    file only one parent has counts them as nothing, as it would count a conflict kept whole."""
    out = attr_git(
        cwd, f"{merge}^1", "show", "--cc", "-U0", "--no-color", "--no-ext-diff", "--format=", merge
    )
    if out.returncode:
        raise ValueError(f"git show --cc failed: {out.stderr.strip()}")
    both, path, in_hunk = {}, None, False
    for line in out.stdout.split("\n"):
        if line.startswith("diff --cc "):
            path, in_hunk = unquote(line[len("diff --cc ") :]), False
            both[path] = [0, 0]
        elif line.startswith("@@@ "):
            in_hunk = True
        elif in_hunk:
            if line.startswith("++"):
                both[path][0] += 1
            elif line.startswith("--"):
                both[path][1] += 1
    cut = []
    for n, path, old in rows:
        if n is not None:
            mine = both.get(path, [0, 0])
            n = tuple(mine) if sum(mine) < sum(n) else n
        cut.append((n, path, old))
    return cut


def unquote(path):
    """A path as git prints it in a patch header: as is, or C-quoted in double quotes when it
    holds a quote, a backslash or a control character (`core.quotePath` also quotes any byte
    past ASCII, as an octal escape)."""
    if not (len(path) > 1 and path[0] == path[-1] == '"'):
        return path
    return codecs.escape_decode(path[1:-1].encode("utf-8"))[0].decode("utf-8", "replace")


def pr_size(base, head="HEAD", cwd=ROOT, branch=None):
    """(findings, the line to print, notes): the counted size of head against base."""
    if git(cwd, "rev-parse", f"{base}^{{commit}}").stdout == git(cwd, "rev-parse", head).stdout:
        return [], "pull request size: the head is its base; nothing to count", []
    where = f"against {git(cwd, 'rev-parse', '--short=11', base).stdout.strip()}"
    own = None
    if branch and branch.startswith(SYNC_BRANCH):
        own = sync_commits(base, head, cwd)
    if own is None:
        rows, notes = size_rows(base, head, cwd)
    else:  # each of the sync's own commits, merges by their own changes; the rest is upstream's
        rows, notes = [], []
        for c in own:
            r, n = own_rows(c, cwd)
            rows, notes = rows + r, notes + n
        where = f"in the {len(own)} commit(s) of sync {branch} that are not Apache Spark's"
    count, total = sum(r[0] for r in rows), sum(r[1] for r in rows)
    if count < SIZE_CAP:
        line = f"pull request size: {count} counted lines of {total} changed {where}"
        return [], line + f", under the cap of {SIZE_CAP}", notes
    top = ", ".join(f"{p} ({c})" for c, _, p, _ in sorted(rows, reverse=True)[:5] if c)
    return (
        [
            f"pull request size: {count} counted lines {where} ({total} changed in all), at or "
            f"over the cap of {SIZE_CAP}; the largest: {top}; split it into pull requests under "
            "the cap"
        ],
        None,
        notes,
    )


def default_base(head="HEAD", cwd=ROOT):
    env = os.environ.get("VARKA_DIFF_BASE")
    if env:
        return env
    out = git(cwd, "merge-base", head, "origin/master")
    return out.stdout.strip() if out.returncode == 0 else None


def size_findings(base, head="HEAD", branch=None, cwd=ROOT):
    """pr_size for a run of this script: the base found, the line printed."""
    base = base or default_base(head, cwd)
    if not base:
        return ["pull request size: no merge base with origin/master; name one with --base"]
    if head == "HEAD" and not branch:
        branch = os.environ.get("GITHUB_HEAD_REF") or os.environ.get("GITHUB_REF_NAME")
        branch = branch or git(cwd, "rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    branch = branch or head  # `size --head X`: a branch named X may be a sync
    try:
        findings, line, notes = pr_size(base, head, cwd, branch)
    except ValueError as e:
        return [f"pull request size: could not count {base}..{head}: {e}"]
    for note in notes:
        print(f"pull request size: {note}")
    if line:
        print(line)
    return findings


def size_selftest():
    """pr_size over a temporary repository: (name, got, want) cases."""
    with tempfile.TemporaryDirectory() as tmp:
        env = generated.fixture_env()

        def run(*args):
            subprocess.run(args, cwd=tmp, env=env, check=True, capture_output=True)

        def write(rel, text, mode="w"):
            os.makedirs(os.path.dirname(os.path.join(tmp, rel)) or tmp, exist_ok=True)
            with open(os.path.join(tmp, rel), mode) as f:
                f.write(text)

        def commit():
            run("git", "add", "-A")
            run("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c")
            return git(tmp, "rev-parse", "HEAD").stdout.strip()

        def lines(k, tag="x"):
            return "".join(f"{tag} {i}\n" for i in range(k))

        def block(name, body):
            return f"<!-- BEGIN generated {name} -->\n{body}<!-- END generated {name} -->\n"

        run("git", "init", "-q", "-b", "main")
        attrs = "/types.tsv merge=varka-generated\n/matrix/*seconds.tsv linguist-generated\n"
        write("sql/varka/.gitattributes", attrs)
        write("A.java", lines(10))
        write("b.py", lines(30))
        write("D.scala", lines(6, "d"))
        write("old.md", lines(5, "o"))
        write(generated.DOCS, "prose\n-- x\n" + block("coverage table", lines(20)) + "tail\n")
        write("SKILLS.md", block("contents", lines(3)) + "prose\n")
        write("sql/varka/coverage/r.md", "# r\n\nGenerated by a tool.\n" + lines(3))
        write("Hand.java", lines(4))
        write("sql/varka/matrix/hand.tsv", lines(4))
        write("moved.md", lines(4))
        write("Turned.txt", lines(5, "t"))
        base = commit()
        expect = {
            "A.java": 5,  # 5 added
            "c.py": 2,  # renamed from b.py with one line edited
            "D.scala": 6,  # deleted
            "old.md": 5,  # a deleted markdown file
            "sql/varka/types.tsv": 0,  # on the merge driver's list, though a .tsv
            "sql/varka/matrix/seconds.tsv": 0,  # linguist-generated
            "sql/varka/matrix/skips.tsv": 4,  # hand-curated data
            "notes.txt": 4,
            generated.DOCS: 2,  # its prose; its block's rows are not counted
            "SKILLS.md": 9,  # an edited END marker: the whole file, not its prose's 3
            "notes.md": 6,  # a marker not BLOCKS names is prose
            "sql/varka/coverage/r.md": 0,  # a report that says a tool wrote it
            "Gen.java": 0,  # "Generated, not written" past a licence header
            "sql/varka/plans/m8/results/r.md": 0,
            "sql/varka/plans/m8/results/R/S.java": 7,  # source under results/
            "sql/varka/papers/p.md": 0,
            "sql/varka/plans/m8/READING_X.md": 0,
            "data.txt": 0,  # binary
            # The head cannot exempt a file the base has: a header it adds, an attribute line
            # it adds, a move into results/. A file new in the head is read by the head's.
            "Hand.java": 9,
            "sql/varka/matrix/hand.tsv": 2,
            "sql/varka/plans/m8/results/moved.md": 2,
            "sql/varka/gen/a.txt": 0,
            "sql/core/src/test/resources/c/map.md": 0,  # Spark's "Automatically generated by"
            "sql/core/src/test/resources/sql-tests/inputs/e.sql": 3,  # a hand input, so headed
            "Turned.txt": 1,  # a text file the head makes binary with a NUL: counted as text
        }
        write("A.java", lines(15))
        os.rename(os.path.join(tmp, "b.py"), os.path.join(tmp, "c.py"))
        write("c.py", "edited\n" + lines(30)[4:])
        os.remove(os.path.join(tmp, "D.scala"))
        os.remove(os.path.join(tmp, "old.md"))
        for path in ("sql/varka/types.tsv", "sql/varka/matrix/seconds.tsv"):
            write(path, lines(40))
        write("sql/varka/matrix/skips.tsv", lines(4, "s"))
        write("notes.txt", lines(4, "n"))
        write(generated.DOCS, "prose!\n-- x\n" + block("coverage table", lines(20, "y")) + "tail\n")
        skills = block("contents", lines(3, "y")).replace("END generated c", "END generated C")
        write("SKILLS.md", skills)
        write("notes.md", "<!-- BEGIN generated coverage table -->\n" + lines(5))
        write("sql/varka/coverage/r.md", "# r\n\nGenerated by a tool.\n" + lines(10))
        write("Gen.java", lines(20) + " * <p>Generated, not written: a suite.\n" + lines(50))
        write("sql/varka/plans/m8/results/r.md", lines(9))
        write("sql/varka/plans/m8/results/R/S.java", lines(7, "j"))
        write("sql/varka/papers/p.md", lines(8))
        write("sql/varka/plans/m8/READING_X.md", lines(8))
        write("data.txt", b"\0\1\2" * 40, "wb")
        write("Hand.java", "// DO NOT EDIT\n" + lines(4, "h"))
        write("sql/varka/matrix/hand.tsv", lines(3) + "e\n")
        write("sql/varka/.gitattributes", attrs + "/matrix/hand.tsv linguist-generated\n")
        write("sql/varka/.gitattributes", "/gen/*.txt linguist-generated=true\n", "a")
        os.rename(
            os.path.join(tmp, "moved.md"), os.path.join(tmp, "sql/varka/plans/m8/results/moved.md")
        )
        write("sql/varka/plans/m8/results/moved.md", lines(3) + "e\n")
        write("sql/varka/gen/a.txt", lines(6))
        write("sql/core/src/test/resources/c/map.md", "<!-- Automatically generated by S -->\n")
        hand_input = "-- Automatically generated by SQLQueryTestSuite\nSELECT 1;\nSELECT 2;\n"
        write("sql/core/src/test/resources/sql-tests/inputs/e.sql", hand_input)
        write("Turned.txt", "\0\n" + lines(5, "t"))
        write(".gitattributes", "*.java -diff\n")  # the head's: A.java still diffs as text
        head = commit()
        rows, notes = size_rows(base, head, tmp)
        got = {r[2]: r[0] for r in rows}
        # The user's attributes file does not change a count: it marks the TSVs binary here.
        xdg = os.path.join(tmp, ".git", "xdg")
        os.makedirs(os.path.join(xdg, "git"))
        with open(os.path.join(xdg, "git", "attributes"), "w") as f:
            f.write("*.tsv binary\n*.java -diff\n")
        saved = dict(os.environ)
        os.environ["XDG_CONFIG_HOME"] = xdg
        try:
            user_attrs = {r[2]: r[0] for r in size_rows(base, head, tmp)[0]}
        finally:
            os.environ.clear()
            os.environ.update(saved)
        user_attrs = {p: (c, got[p]) for p, c in user_attrs.items() if c != got[p]}
        added = sorted(n.split(":")[0] for n in notes if "exemption added" in n)
        hand_tsv, moved = "sql/varka/matrix/hand.tsv", "sql/varka/plans/m8/results/moved.md"
        new_marked = [n.split(":")[0] for n in notes if "new, marked" in n]
        under = pr_size(base, head, tmp)
        # A BEGIN that never closes: the whole file counts, not only the line before it.
        write(generated.DOCS, "prose\n<!-- BEGIN generated verification counts -->\n" + lines(50))
        lone = {r[2]: r for r in size_rows(head, commit(), tmp)[0]}[generated.DOCS]
        write("Big.scala", lines(SIZE_CAP))
        over = pr_size(base, commit(), tmp)
        # A sync: a commit of its own, Apache Spark's master merged with a conflict in A.java
        # and one in a generated file, then one more commit of its own.
        seconds, a_java = "sql/varka/matrix/seconds.tsv", lines(10).replace("x 5", "{} 5")
        # Five more conflicts, in files Apache Spark's master had before the sync began.
        k_java = lines(10).replace("x 3", "{} 3")
        h_java = lines(10).replace("x 7", "{} 7")
        run("git", "checkout", "-q", "-b", "apache", base)
        quoted = 'q"x.java'  # a name git C-quotes in a patch header
        for name in ("K.java", "H.java", "Both.java", quoted):
            write(name, lines(10))
        write("Gone.java", lines(8, "g"))  # unlike Both.java, so no rename pairs them
        upstream_had = commit()
        write("Up.java", lines(2000))
        write("A.java", a_java.format("a"))
        write(seconds, lines(3, "a"))
        write("K.java", k_java.format("a").replace("x 9", "a 9"))  # 9 merges cleanly
        write(quoted, a_java.format("a"))
        write("H.java", h_java.format("a"))
        os.remove(os.path.join(tmp, "Gone.java"))
        write("Both.java", lines(10).replace("x 0", "a 0"))
        run("git", "update-ref", "refs/apache/master", commit())
        run("git", "checkout", "-q", "-b", SYNC_BRANCH + "1", upstream_had)
        write("A.java", a_java.format("s"))  # 2 lines
        write(seconds, lines(3, "s"))
        write("K.java", k_java.format("s"))  # 2 lines each
        write(quoted, a_java.format("s"))
        write("H.java", h_java.format("s"))
        write("Both.java", lines(10).replace("x 9", "s 9"))
        commit()
        git(tmp, "-c", "user.name=t", "-c", "user.email=t@t", "merge", "-q", "apache")
        # The resolutions: A.java's one line written by hand (--remerge-diff, which counts
        # the conflict's markers and both sides, gives 6) and the generated file's none;
        # K.java keeps the sync's side of its conflict and Apache Spark's clean hunk, so it
        # equals neither parent but each hunk equals one, and Gone.java, which Apache Spark
        # deleted, is restored as the sync had it, both nothing; H.java's conflict is
        # rewritten as four lines of hand, counted in full; Both.java, which both parents
        # have, the merge deletes: the 8 lines the parents share; q"x.java, as A.java, 1.
        write("A.java", a_java.format("r"))
        write(seconds, lines(3, "r"))
        write("K.java", k_java.format("s").replace("x 9", "a 9"))
        write(quoted, a_java.format("r"))
        write("H.java", h_java.replace("{} 7\n", lines(4, "h")))
        write("Gone.java", lines(8, "g"))
        os.remove(os.path.join(tmp, "Both.java"))
        merge = commit()
        merged = {p: c for c, _, p, _ in own_rows(merge, tmp)[0]}
        remerged = {
            r[1]: sum(r[0])
            for r in numstat(tmp, None, "show", "--remerge-diff", "--format=", merge)
        }
        write("Own.java", lines(3))
        sync_head = commit()
        saved = dict(os.environ)
        os.environ.update(GITHUB_HEAD_REF="", GITHUB_REF_NAME=SYNC_BRANCH + "1")
        try:
            sync = size_findings(base, "HEAD", cwd=tmp)
            sync_line = pr_size(base, sync_head, tmp, SYNC_BRANCH + "1")[1] or ""
        finally:
            os.environ.clear()
            os.environ.update(saved)
        # An octopus merge, which --remerge-diff cannot replay, is a count not made.
        for name in ("o1", "o2"):
            run("git", "checkout", "-q", "-b", name, sync_head)
            write(f"{name}.java", lines(2))
            commit()
        run("git", "checkout", "-q", SYNC_BRANCH + "1")
        write("o3.java", lines(2))
        commit()
        run("git", "-c", "user.name=t", "-c", "user.email=t@t", "merge", "-q", "o1", "o2")
        octopus = git(tmp, "rev-parse", "HEAD").stdout.strip()
        octopus_counted = []
        for bad in (
            lambda: own_rows(octopus, tmp),
            lambda: numstat(tmp, None, "show", "--remerge-diff", "--format=", octopus),
        ):
            try:
                octopus_counted.append(bad())
            except ValueError:
                pass
        octopus_sync = size_findings(base, octopus, SYNC_BRANCH + "1", tmp)
        octopus_sync = [] if "could not count" in "".join(octopus_sync) else [octopus_sync]
        # The name alone, with no merge of Apache Spark's master, is counted as any change.
        named = pr_size(base, head, tmp, SYNC_BRANCH + "2")
        # A count git does not give as numbers, or a failed git, is a count not made.
        unreadable = []
        for bad in (
            lambda: text_diff(["\0"], []),
            lambda: numstat(tmp, None, "diff", "no-such-rev"),
        ):
            try:
                unreadable.append(bad())
            except ValueError:
                pass
        not_counted = size_findings("no-such-rev", "HEAD", cwd=tmp)
        not_counted = [] if "could not count" in "".join(not_counted) else [not_counted]
    # #1427's first merge, the shape row 588 was written from, where this repository has it:
    # 1335 lines by --remerge-diff, 52 that differ from both parents.
    pin = "8149ab82419d6224c57298d8f2630ef8b9c97535"
    pinned = []
    if git(ROOT, "cat-file", "-e", f"{pin}^{{commit}}").returncode == 0:
        pinned = sum(c for c, _, _, _ in own_rows(pin, ROOT)[0])
        pinned = [] if pinned == 52 else [pinned]
    merge_want = {
        "A.java": 1,
        "K.java": 0,
        "H.java": 4,
        "Gone.java": 0,
        "Both.java": 8,
        'q"x.java': 1,
        "sql/varka/matrix/seconds.tsv": 0,
    }
    wrong = {p: (got.get(p), c) for p, c in expect.items() if got.get(p) != c}
    return [
        ("each file's counted lines", [wrong] if wrong else [], 0),
        ("the user's attributes file changes no count", [user_attrs] if user_attrs else [], 0),
        ("a pull request under the cap", under[0], 0),
        ("an unclosed BEGIN counts the file", [] if lone[0] == lone[1] > 50 else [lone], 0),
        ("a pull request at the cap", over[0], 1),
        ("the largest file named", [] if f"Big.scala ({SIZE_CAP})" in over[0][0] else [1], 0),
        ("a sync read from GITHUB_REF_NAME", sync, 0),
        (
            "a sync counts its own commits",
            [] if "27 counted lines" in sync_line else [sync_line],
            0,
        ),
        ("... its merge's among them", [] if "3 commit(s)" in sync_line else [sync_line], 0),
        ("#1427's merge counts 52 lines", pinned, 0),
        (
            "a merge counts the lines that differ from both parents",
            [] if merged == merge_want else [merged],
            0,
        ),
        (
            "... where --remerge-diff counts more",
            [] if remerged.get("K.java") and remerged.get("Gone.java") else [remerged],
            0,
        ),
        ("exemptions the head adds", [] if added == ["Hand.java", hand_tsv, moved] else added, 0),
        (
            "a new file the head marks",
            [] if new_marked == ["sql/varka/gen/a.txt"] else new_marked,
            0,
        ),
        ("a sync branch with no sync merge", named[0], 0),
        ("... counted as any change", [] if named[1] == under[1] else [named[1]], 0),
        ("a count git cannot give raises", unreadable, 0),
        ("... and is a finding", not_counted, 0),
        ("an octopus merge raises", octopus_counted, 0),
        ("... and in a sync is a finding", octopus_sync, 0),
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
        head, branch = "HEAD", None
        if "--number" in opts:
            title, body, head, branch = from_gh(opts["--number"])
        else:
            title = opts.get("--title", "")
            with open(opts["--body-file"], encoding="utf-8") as f:
                body = f.read()
        if "--number" in opts and git(ROOT, "cat-file", "-e", f"{head}^{{commit}}").returncode:
            pull = f"refs/pull/{opts['--number']}/head"
            git(ROOT, "fetch", "-q", "https://github.com/vecbricks/varka.git", pull)
        if git(ROOT, "cat-file", "-e", f"{head}^{{commit}}").returncode == 0:
            findings = size_findings(opts.get("--base"), head, branch)
        else:
            findings = [f"pull request size: could not count; its head {head} is not here"]
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
