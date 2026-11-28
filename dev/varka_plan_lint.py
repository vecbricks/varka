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
"""Do the milestone's records hold against the tree? (VARKA-473.)

  dev/varka_plan_lint.py                     # check the m8 plan table
  dev/varka_plan_lint.py --check FILE...     # check these plan files
  dev/varka_plan_lint.py --offline           # skip the run-head check that needs `gh`
  dev/varka_plan_lint.py --require-gh        # a run head that cannot be read is a finding

A row of a milestone plan is the only record of why a change exists, and it is
prose: nothing stops it naming a run whose head is another commit, citing a
results file that was never committed, pointing at a section of a plan that has
moved, or repeating an id that is already in the table. Each of those was found
by hand in the first week of milestone 8 (row 471 named the wrong run head,
described the wrong failure, cited a suite that pins options per call and left
its provenance cell empty), and none of them is caught by a compiler.

The checks, one per line of the row:

  * the table: a numbered row's provenance cell (the third) is not empty, and no
    two rows of one table share a number;
  * paths: every backticked file the row cites - `results/...`, `VARKA-<n>.md`,
    a source file, a generated JSON - resolves in the tree, as the citing file's
    directory, the plans directory, the repository root, or a unique suffix;
  * sections: a plan file cited with a section number ("`VARKA-329.md` 9.2") has
    a heading that starts with that number;
  * rows: every number a row cites as "row N" or "rows N and M" exists in some
    milestone's table, since a citation crosses milestones without a path;
  * run heads: a row that cites exactly one Actions run and exactly one commit
    has the run's `head_sha` read through `gh api` and compared with the commit;
    with two runs or two commits the pairing is a guess, so the row is left
    unchecked rather than reported;
  * state: a row of the default plan whose work has a commit in the branch's
    history - a `[VARKA-<n>]` subject that does not plan the row or file it -
    opens with its state in bold, `**Done**`, `**Done in part**`,
    `**Started**`, `**Withdrawn**` or `**Moved to milestone <n>**`, so a count of
    the milestone can tell finished rows from part done ones and from rows that
    left it; a started row says in its text what is left.

The default file is `sql/varka/plans/m8/PLAN.md`, the plan of the milestone in flight
(`sql/varka/plans/README.md` names it, and a milestone's close moves `DEFAULT_PLAN`); the
other milestone plans are read for the row numbers a citation may name, and are checked when
named with `--check`. The exit status is the number of
findings, and `--offline` leaves the run-head check to CI, which has `gh`.
"""

import argparse
import os
import re
import subprocess
import sys

TOP = subprocess.run(
    ["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True, text=True
).stdout.strip()

DEFAULT_PLAN = "sql/varka/plans/m8/PLAN.md"
PLANS_DIR = "sql/varka/plans"

PATH_WITH_DIR = re.compile(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+\.(?:md|txt|json|tsv)")
PLAN_MD = re.compile(r"(?:[A-Za-z0-9_./-]*/)?VARKA-\d+\.md")
SECTION = re.compile(r"`?([A-Za-z0-9_./-]*VARKA-\d+\.md)`?\s+(?:section\s+)?(\d+(?:\.\d+)*)\b")
ROW_CITE = re.compile(r"\brows? (\d{2,4}[a-z]?)(?: to (\d{2,4}[a-z]?))?(?: and (\d{2,4}[a-z]?))?")
RUN_CITE = re.compile(r"\brun (\d{9,12})\b")
SHA_CITE = re.compile(r"`([0-9a-f]{7,40})`")
HEADING = re.compile(r"^#{1,4}\s+(\d+(?:\.\d+)*)[.:\s]")
WORK_SUBJECT = re.compile(r"^\[VARKA-(\d+)[a-z]?\] (.*)$")
# A commit that plans a row or files one in the table is not work on it.
NOT_WORK = re.compile(
    r"^(?:Plan\b|Re-?plan\b|Rework the plan\b|Add (?:\w+ )?(?:plan )?rows?\b|File \w+ rows?\b)"
)
STATE = re.compile(r"\*\*(?:Done|Done in part|Started|Withdrawn|Moved to milestone \d+)\*\*")


def work_commits():
    """Row number -> the subjects of the commits in HEAD's history that do work on it."""
    out = subprocess.run(
        ["git", "-C", TOP, "log", "--format=%s", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    work = {}
    for subject in out.splitlines():
        m = WORK_SUBJECT.match(subject)
        if m and not NOT_WORK.match(m.group(2)):
            work.setdefault(int(m.group(1)), []).append(m.group(2))
    return work


def tree_files():
    out = subprocess.run(
        ["git", "-C", TOP, "ls-files"], check=True, capture_output=True, text=True
    ).stdout
    return out.splitlines()


def plan_files():
    root = os.path.join(TOP, PLANS_DIR)
    return sorted(
        os.path.relpath(os.path.join(root, d, "PLAN.md"), TOP)
        for d in os.listdir(root)
        if os.path.isfile(os.path.join(root, d, "PLAN.md"))
    )


def task_table(text):
    """The task table's lines with their 1-based line numbers: (line number, line).

    The plan has other tables whose first column is a number - the waves of section 4 - so
    the region is bounded by its header, `| task |`, to the first line after it that is not
    a table row."""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if line.startswith("| task |"):
            rows = []
            for j in range(i + 1, len(lines)):
                if not lines[j].startswith("|"):
                    break
                rows.append((j + 1, lines[j]))
            return rows
    return []


def cells(line):
    """A table line's four cells, or None. The last three separators are counted from the
    right, since a row's text may hold a `|` of its own; a cell may be empty."""
    s = line.strip()
    if not (s.startswith("|") and s.endswith("|")):
        return None
    rest, size = s[1:-1].rsplit("|", 1)
    rest, provenance = rest.rsplit("|", 1)
    if "|" not in rest:
        return None
    ident, text = rest.split("|", 1)
    return [ident.strip(), text.strip(), provenance.strip(), size.strip()]


def table_rows(region):
    """The numbered rows of the task table: id -> [(line, text, provenance, size)]."""
    rows = {}
    for number, line in region:
        parts = cells(line)
        if parts and parts[0].isdigit():
            rows.setdefault(int(parts[0]), []).append((number, parts[1], parts[2], parts[3]))
    return rows


def malformed_rows(region):
    """Task-table lines that start like a numbered row but do not have the four cells."""
    bad = []
    for number, line in region:
        if re.match(r"^\| \d+ \|", line) and cells(line) is None:
            bad.append((number, len(line.strip().strip("|").split("|"))))
    return bad


def headings(path):
    found = set()
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = HEADING.match(line)
            if m:
                found.add(m.group(1))
    return found


def resolve(token, citing, files):
    """The tree path `token` names, or None: beside the citing file, in plans/, at the root, or
    as a unique suffix."""
    token = token.strip("`.,;:()")
    citing_dir = os.path.dirname(citing)
    for base in (citing_dir, PLANS_DIR, ""):
        candidate = os.path.normpath(os.path.join(base, token)) if base else os.path.normpath(token)
        if os.path.isfile(os.path.join(TOP, candidate)):
            return candidate
    hits = [p for p in files if p == token or p.endswith("/" + token)]
    return hits[0] if len(hits) == 1 else None


def run_head(run_id, cache):
    if run_id not in cache:
        out = subprocess.run(
            ["gh", "api", f"repos/vecbricks/varka/actions/runs/{run_id}", "--jq", ".head_sha"],
            capture_output=True,
            text=True,
        )
        cache[run_id] = out.stdout.strip() if out.returncode == 0 else None
    return cache[run_id]


def main():
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("files", nargs="*", help=f"plan files to check (default: {DEFAULT_PLAN})")
    p.add_argument("--check", action="store_true", help="the default action; accepted for symmetry")
    p.add_argument("--offline", action="store_true", help="skip the run-head check")
    p.add_argument("--require-gh", action="store_true", help="a run head that cannot be read fails")
    args = p.parse_args()
    files = tree_files()
    plans = args.files or [DEFAULT_PLAN]
    all_rows = set()
    for plan in plan_files():
        region = task_table(open(os.path.join(TOP, plan), encoding="utf-8").read())
        all_rows |= set(table_rows(region))
    findings = []
    unchecked = 0
    heads = {}
    work = work_commits()
    for plan in plans:
        path = os.path.join(TOP, plan)
        if not os.path.isfile(path):
            findings.append(f"{plan}: no such file")
            continue
        text = open(path, encoding="utf-8").read()
        region = task_table(text)
        rows = table_rows(region)
        for line, cells in malformed_rows(region):
            findings.append(f"{plan}:{line}: a numbered row has {cells} cells, not 4")
        for rid, entries in rows.items():
            for line, _, _, _ in entries[1:]:
                findings.append(
                    f"{plan}:{line}: row {rid} is already in the table "
                    f"(first at line {entries[0][0]})"
                )
        for rid, entries in rows.items():
            for line, body, provenance, _ in entries:
                if plan == DEFAULT_PLAN and rid in work and not STATE.match(body):
                    findings.append(
                        f"{plan}:{line}: row {rid} has merged work ({work[rid][0]!r}) and does "
                        f"not open with its state, **Done**, **Done in part**, **Started**, "
                        f"**Withdrawn** or **Moved to milestone <n>**"
                    )
                if not provenance.strip():
                    findings.append(f"{plan}:{line}: row {rid} has an empty provenance cell")
                cited = body + " " + provenance
                for token in PATH_WITH_DIR.findall(cited) + PLAN_MD.findall(cited):
                    if resolve(token, plan, files) is None:
                        findings.append(f"{plan}:{line}: row {rid} cites {token}, not in the tree")
                for name, number in SECTION.findall(cited):
                    target = resolve(name, plan, files)
                    if target is not None and number not in headings(os.path.join(TOP, target)):
                        findings.append(
                            f"{plan}:{line}: row {rid} cites {name} {number}, "
                            f"which has no such section"
                        )
                # "output 0 row 1" is a row of data, not a row of a plan.
                prose = re.sub(r"\boutput \d+ rows? \d+", "", cited)
                for group in ROW_CITE.findall(prose):
                    for cited_id in group:
                        if not cited_id:
                            continue
                        n = int(re.match(r"\d+", cited_id).group(0))
                        if n not in all_rows:
                            findings.append(
                                f"{plan}:{line}: row {rid} cites row {cited_id}, "
                                f"which no milestone's table has"
                            )
                runs = RUN_CITE.findall(cited)
                shas = [s for s in SHA_CITE.findall(cited) if not s.isdigit()]
                if len(runs) == 1 and len(shas) == 1:
                    if args.offline:
                        unchecked += 1
                        continue
                    head = run_head(runs[0], heads)
                    if head is None:
                        if args.require_gh:
                            findings.append(
                                f"{plan}:{line}: row {rid} cites run {runs[0]} and its head "
                                f"cannot be read"
                            )
                        else:
                            unchecked += 1
                    elif not head.startswith(shas[0]):
                        findings.append(
                            f"{plan}:{line}: row {rid} cites run {runs[0]} as head {shas[0]}, "
                            f"but the run's head is {head[:11]}"
                        )
    for finding in findings:
        print(finding)
    summary = f"{len(findings)} finding(s) in {len(plans)} plan file(s)"
    if unchecked:
        summary += f"; {unchecked} run citation(s) unchecked" + (
            " (offline)" if args.offline else ""
        )
    print(summary)
    return len(findings)


if __name__ == "__main__":
    sys.exit(min(main(), 255))
