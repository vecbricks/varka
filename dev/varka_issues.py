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
"""Mirror the milestone plans' task tables as GitHub issues (VARKA-183, scope item 38).

    dev/varka_issues.py                  # dry run: print the rule and what would change
    dev/varka_issues.py --apply          # open, update and close issues through gh
    dev/varka_issues.py --plan sql/varka/plans/m6/PLAN.md --repo vecbricks/varka

The task table in the current milestone's plan (sql/varka/plans/m<n>/PLAN.md for the highest
<n>, section 3) is the source of truth for what the project is doing;
the issues are the view GitHub shows a newcomer. Each open row is one issue titled
"[VARKA-<n>] <the row's title>", carrying the row's text, its origin and size, and links to
the plan and to the task's own plan file when it has one. When a row turns done or
withdrawn, its issue is closed with the row's outcome quoted. Nothing flows the other way:
edit the row, and this script brings the issue to it.

Every plan is read, not only the current one, because a milestone's last rows can still be
open when the next plan merges. Only the current plan's rows open new issues; an earlier plan's
rows keep the issues they already have in step - one still open there stays open, one done,
withdrawn or moved is closed - and get none new, so a milestone mirrored before this script
existed opens nothing. A task number in two plans is the later plan's row: the earlier row's
"Moved" marker is the record of the move, and the issue follows the row to its new milestone.

The rule, since the tables' status vocabulary is not uniform: a row's state is its first
bold marker. A marker beginning with "Done" (Done, DONE, "Done, negative") closes the issue
as completed; "Withdrawn" or "Moved" closes it as not planned; any other marker (Scoped,
Planned, Built, "Step 1 done", "Census drafted") is in progress and the issue stays open with
the marker in its body; a row with no marker is open and not started. So no row is left
unclassified, and the dry run prints each row's state next to the marker it read.

Labels: "task", "milestone-<n>" for the plan the row is in, and "good first issue" for the
rows listed in
dev/varka_good_first_tasks.txt, a hand-kept list of at most five rows a newcomer can
finish in a day. .github/workflows/varka-issues.yml runs this with --apply on every push to master
that touches a milestone plan, so the mirror follows the table without anyone remembering.
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLANS_DIR = os.path.join(REPO_ROOT, "sql", "varka", "plans")
GOOD_FIRST_FILE = os.path.join(REPO_ROOT, "dev", "varka_good_first_tasks.txt")
MIRROR_LINE = (
    "*Mirrored from the task table by `dev/varka_issues.py`; edit the row, not this issue.*"
)


@dataclass
class Row:
    number: int
    text: str
    origin: str
    size: str
    plan: str = ""  # the m<n>/PLAN.md the row is in, below sql/varka/plans
    milestone: int = 0


@dataclass
class State:
    kind: str  # "open", "done" or "withdrawn"
    marker: str  # the bold marker read, or "not started"


def all_plans(plans_dir=PLANS_DIR):
    """Every m<n>/PLAN.md with its milestone number, the highest first. A milestone folder that
    holds only a scope catalogue (m<n>/SCOPE.md) has no plan yet and is not listed.

    >>> import tempfile
    >>> d = tempfile.mkdtemp()
    >>> for n in (5, 12, 6):
    ...     os.makedirs(os.path.join(d, f"m{n}"))
    ...     open(os.path.join(d, f"m{n}", "PLAN.md"), "w").close()
    >>> [(os.path.relpath(path, d), n) for path, n in all_plans(d)]
    [('m12/PLAN.md', 12), ('m6/PLAN.md', 6), ('m5/PLAN.md', 5)]
    """
    plans = {}
    for path in glob.glob(os.path.join(plans_dir, "m*", "PLAN.md")):
        m = re.fullmatch(r"m(\d+)", os.path.basename(os.path.dirname(path)))
        if m:
            plans[int(m.group(1))] = path
    if not plans:
        sys.exit(f"no m<n>/PLAN.md under {plans_dir}")
    return [(plans[n], n) for n in sorted(plans, reverse=True)]


def current_plan(plans_dir=PLANS_DIR):
    """The highest-numbered m<n>/PLAN.md, and its milestone number.

    >>> import tempfile
    >>> d = tempfile.mkdtemp()
    >>> for n in (5, 12, 6):
    ...     os.makedirs(os.path.join(d, f"m{n}"))
    ...     open(os.path.join(d, f"m{n}", "PLAN.md"), "w").close()
    >>> os.path.relpath(current_plan(d)[0], d), current_plan(d)[1]
    ('m12/PLAN.md', 12)
    """
    return all_plans(plans_dir)[0]


def rows_of_plans(plans):
    """The rows of every plan's task table, given as (plan name, milestone, rows) with the highest
    milestone first, each task number taken from the highest plan that has it: a row moved to a
    later milestone is that milestone's, and its earlier "Moved" marker is the record of the move.

    >>> rows = rows_of_plans([
    ...     ("P7.md", 7, [Row(214, "Port the time family", "item 81", "small")]),
    ...     ("P6.md", 6, [Row(214, "Port. **Moved to milestone 7**: as item 81", "o", "s"),
    ...                   Row(236, "Plan the size. **Planned** (x)", "o", "s")])])
    >>> [(r.number, r.plan, r.milestone, classify(r.text).kind) for r in rows]
    [(214, 'P7.md', 7, 'open'), (236, 'P6.md', 6, 'open')]
    """
    taken, rows = set(), []
    for plan_name, milestone, plan_rows in plans:
        for row in plan_rows:
            if row.number in taken:
                continue
            taken.add(row.number)
            row.plan, row.milestone = plan_name, milestone
            rows.append(row)
    return rows


def split_cells(line):
    """The cells of a Markdown table row, a pipe inside backticks or escaped left alone.

    >>> split_cells("| 12 | a `x|y` cell \\\\| kept | b | c |")
    ['12', 'a `x|y` cell \\\\| kept', 'b', 'c']
    """
    cells, cell, in_code, i = [], [], False, 0
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|") and not body.endswith("\\|"):
        body = body[:-1]
    while i < len(body):
        ch = body[i]
        if ch == "`":
            in_code = not in_code
        if ch == "\\" and i + 1 < len(body) and body[i + 1] == "|":
            cell.append("\\|")
            i += 2
            continue
        if ch == "|" and not in_code:
            cells.append("".join(cell).strip())
            cell = []
        else:
            cell.append(ch)
        i += 1
    cells.append("".join(cell).strip())
    return cells


def parse_rows(text):
    """The rows of the plan's task table: the first table whose header starts with "task".

    >>> plan = '''## 3. Task breakdown
    ... | task | what it is | where it came from | size |
    ... | ---: | :--- | :--- | :--- |
    ... | 87 | The epilogue. **Done** (`VARKA-87.md`): one method per group | 2.18 | medium |
    ... | 173 | A disjointness test | item 41 | small |
    ...
    ... ## 4. Ordering
    ... | wave | tasks | why they wait |
    ... | ---: | :--- | :--- |
    ... | 0 | 87 | opens |
    ... '''
    >>> [(r.number, r.origin, r.size) for r in parse_rows(plan)]
    [(87, '2.18', 'medium'), (173, 'item 41', 'small')]
    """
    rows, in_table = [], False
    for line in text.splitlines():
        if not line.startswith("|"):
            if in_table:
                break
            continue
        cells = split_cells(line)
        if not in_table:
            if cells and cells[0].lower() == "task":
                in_table = True
            continue
        if cells[0].startswith("---") or cells[0].startswith(":--"):
            continue
        if len(cells) != 4 or not cells[0].isdigit():
            continue
        rows.append(Row(int(cells[0]), cells[1], cells[2], cells[3]))
    return rows


BOLD = re.compile(r"\*\*([^*]+)\*\*")


def classify(text):
    """A row's state from its first bold marker; see the module's docstring for the rule.

    >>> classify("The epilogue. **Done** (`VARKA-87.md`): per group").kind
    'done'
    >>> classify("A ladder. **Done, negative** (9.2): no").kind
    'done'
    >>> classify("Old idea. **Withdrawn** (9): superseded").kind
    'withdrawn'
    >>> classify("A port. **Moved to milestone 7** (2 October 2026, item 81): the rest").kind
    'withdrawn'
    >>> s = classify("The cliff. **Laptop ladder done** (9.1): vanilla steps"); s.kind, s.marker
    ('open', 'Laptop ladder done')
    >>> s = classify("A disjointness test for the compiler's family chain"); s.kind, s.marker
    ('open', 'not started')
    """
    m = BOLD.search(text)
    if not m:
        return State("open", "not started")
    marker = m.group(1).strip()
    if marker.lower().startswith("done"):
        return State("done", marker)
    if re.match(r"(withdrawn|moved)\b", marker.lower()):
        return State("withdrawn", marker)
    return State("open", marker)


def title_of(text):
    """The row's title: its text up to the first status marker or note, else its first sentence;
    a leading italic note such as "*Research, optional.*" becomes the title's prefix.

    >>> title_of("The epilogue is the one method no budget bounds. **Done** (`VARKA-87.md`)")
    'The epilogue is the one method no budget bounds'
    >>> title_of("Eight thousand, not sixty-five thousand: the JIT cliff. *Narrowed by VARKA-87*")
    'Eight thousand, not sixty-five thousand: the JIT cliff'
    >>> title_of("A disjointness test for the compiler's family chain")
    "A disjointness test for the compiler's family chain"
    >>> title_of("*Research, optional.* What Varka costs on the first query. Every ladder number")
    'Research, optional: What Varka costs on the first query'
    >>> title_of("Can vanilla Spark tune its way off the cliff? **Laptop runs done** (9.1): no")
    'Can vanilla Spark tune its way off the cliff?'
    >>> title_of("Whether the ladder is fair to stock Spark's input path, and to its codegen: "
    ...          "stock Spark 4.2.0 crosses 8000 bytes between 48 and 52 entries, the fork later")
    "Whether the ladder is fair to stock Spark's input path, and to its codegen"
    """
    body = text.strip()
    prefix = ""
    m = re.match(r"\*([^*]+)\.\*\s+", body)
    if m:
        prefix = m.group(1).strip() + ": "
        body = body[m.end() :]
    cut = len(body)
    for end in ".?!":
        for stop in (end + " **", end + " *"):
            i = body.find(stop)
            if i != -1:
                cut = min(cut, i + 1)
    if cut == len(body):
        i = first_sentence_end(body)
        if i != -1:
            cut = i + 1
    title = body[:cut].rstrip(" .")
    if len(title) > 100 and ": " in title:
        title = title[: title.index(": ")]
    return prefix + title


def first_sentence_end(body):
    """The index of the first sentence-ending mark followed by a space outside backticks, or -1.

    >>> first_sentence_end("See `VARKA-1.md` first. Then this. And that")
    22
    >>> first_sentence_end("Is it? Yes")
    5
    """
    in_code = False
    for i, ch in enumerate(body):
        if ch == "`":
            in_code = not in_code
        elif ch in ".?!" and not in_code and i + 1 < len(body) and body[i + 1] == " ":
            return i
    return -1


def outcome_of(text):
    """The row's text from its first bold marker on, which is what a closing comment quotes.

    >>> outcome_of("The epilogue. **Done** (`VARKA-87.md`): one method per group")
    '**Done** (`VARKA-87.md`): one method per group'
    """
    m = BOLD.search(text)
    return text[m.start() :].strip() if m else text.strip()


def issue_title(row):
    return f"[VARKA-{row.number}] {title_of(row.text)}"


def issue_body(row, state):
    plan_name, milestone = row.plan, row.milestone
    found = glob.glob(os.path.join(PLANS_DIR, "m*", f"VARKA-{row.number}.md"))
    links = [f"the milestone plan, [`{plan_name}`](sql/varka/plans/{plan_name}) section 3"]
    if found:
        task_plan = os.path.relpath(found[0], PLANS_DIR)
        links.append(f"the task's own plan, [`VARKA-{row.number}.md`](sql/varka/plans/{task_plan})")
    return "\n".join(
        [
            f"**VARKA-{row.number}** of milestone {milestone}. State: {state.marker}.",
            "",
            row.text,
            "",
            f"*Where it came from:* {row.origin}. *Size:* {row.size}.",
            "",
            "Read " + " and ".join(links) + ". To take it, open an issue with the",
            '"Varka: take a task" template, or say so here; `CONTRIBUTING.md` has the rest.',
            "",
            MIRROR_LINE,
        ]
    )


def good_first_tasks(path=GOOD_FIRST_FILE):
    """The hand-kept list: one task number per line, then a tab and the reason. At most five.

    >>> import tempfile
    >>> f = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False)
    >>> _ = f.write("# a comment\\n173\\ta test, one file\\n\\n")
    >>> _ = f.write("174\\tconstants out of the facade\\n")
    >>> f.close()
    >>> good_first_tasks(f.name)
    {173: 'a test, one file', 174: 'constants out of the facade'}
    """
    tasks = {}
    if not os.path.exists(path):
        return tasks
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            number, _, reason = line.partition("\t")
            tasks[int(number.strip())] = reason.strip()
    if len(tasks) > 5:
        sys.exit(f"{path} lists {len(tasks)} tasks; a good-first list is at most five")
    return tasks


def gh(args, repo, capture=True):
    """Runs `gh <args> --repo <repo>` and returns its stdout; exits with gh's message on failure."""
    command = ["gh", *args, "--repo", repo]
    try:
        result = subprocess.run(command, capture_output=capture, text=True, check=False)
    except FileNotFoundError:
        sys.exit("gh is not installed; see https://cli.github.com/")
    if result.returncode != 0:
        sys.exit(f"{' '.join(command)} failed:\n{result.stderr if capture else ''}")
    return result.stdout if capture else ""


# "[Task <n>]" is the title the mirror wrote before 4 October 2026; such an issue is still the
# row's, and its next update retitles it.
TITLE_NUMBER = re.compile(r"^\[(?:VARKA-|Task )(\d+)\]")


def existing_issues(repo):
    """The mirrored issues by task number: those whose title starts with "[VARKA-<n>]"."""
    found = []
    for query in ("VARKA in:title", "[Task in:title"):
        out = gh(
            [
                "issue",
                "list",
                "--state",
                "all",
                "--limit",
                "1000",
                "--search",
                query,
                "--json",
                "number,title,state,body,labels",
            ],
            repo,
        )
        found.extend(json.loads(out or "[]"))
    issues = {}
    for issue in found:
        m = TITLE_NUMBER.match(issue["title"])
        if m and MIRROR_LINE in (issue.get("body") or ""):
            issues[int(m.group(1))] = issue
    return issues


MILESTONE_LABEL = re.compile(r"milestone-\d+")


def stale_milestone_labels(issue, row):
    """The issue's milestone labels other than the row's own milestone, which an update removes.

    >>> issue = {"labels": [{"name": "task"}, {"name": "milestone-6"}]}
    >>> stale_milestone_labels(issue, Row(214, "t", "o", "s", "P7.md", 7))
    ['milestone-6']
    >>> stale_milestone_labels({}, Row(214, "t", "o", "s", "P7.md", 7))
    []
    """
    names = [label["name"] for label in issue.get("labels") or []]
    own = f"milestone-{row.milestone}"
    return [n for n in names if MILESTONE_LABEL.fullmatch(n) and n != own]


def plan_actions(rows, issues, current, good_first):
    """What to do for each row, as (row, action, detail) with action in create, update, close,
    reopen, keep or skip; pure, so the dry run and --apply agree by construction. Only a row of
    the current milestone's plan opens an issue; an earlier plan's row keeps the one it has.

    >>> rows = [Row(1, "Open one", "o", "small", "P.md", 6),
    ...         Row(2, "Closed one. **Done** (x): won", "o", "s", "P.md", 6),
    ...         Row(3, "Never mirrored. **Withdrawn**: no", "o", "s", "P.md", 6),
    ...         Row(4, "Open in an earlier plan", "o", "s", "Q.md", 5),
    ...         Row(5, "Moved. **Moved to milestone 6**: as row 9", "o", "s", "Q.md", 5)]
    >>> issues = {1: {"number": 11, "state": "OPEN", "title": "[VARKA-1] Open one",
    ...                "body": issue_body(rows[0], classify(rows[0].text))},
    ...           2: {"number": 12, "state": "OPEN", "title": "[VARKA-2] Closed one", "body": ""},
    ...           5: {"number": 15, "state": "OPEN", "title": "[VARKA-5] Moved", "body": ""}}
    >>> [(r.number, a) for r, a, _ in plan_actions(rows, issues, 6, {})]
    [(1, 'keep'), (2, 'close'), (3, 'skip'), (4, 'skip'), (5, 'close')]
    """
    actions = []
    for row in rows:
        state = classify(row.text)
        issue = issues.get(row.number)
        title = issue_title(row)
        body = issue_body(row, state)
        if state.kind == "open":
            if issue is None:
                if row.milestone == current:
                    actions.append((row, "create", (title, body, state)))
                else:
                    actions.append((row, "skip", f"milestone {row.milestone}, never mirrored"))
            elif issue["state"] != "OPEN":
                actions.append((row, "reopen", (title, body, state)))
            elif (
                issue["title"] != title
                or issue.get("body") != body
                or stale_milestone_labels(issue, row)
            ):
                actions.append((row, "update", (title, body, state)))
            else:
                actions.append((row, "keep", (title, body, state)))
        elif issue is None:
            actions.append((row, "skip", f"{state.kind}, never mirrored"))
        elif issue["state"] == "OPEN":
            actions.append((row, "close", (state, outcome_of(row.text))))
        else:
            actions.append((row, "keep", f"{state.kind}, already closed"))
    return actions


def labels_for(row, good_first):
    labels = ["task", f"milestone-{row.milestone}"]
    if row.number in good_first:
        labels.append("good first issue")
    return labels


def ensure_labels(repo, milestones):
    wanted = [("task", "0E8A16", "A row of a milestone plan's task table")] + [
        (f"milestone-{m}", "1D76DB", f"Milestone {m}'s task table") for m in sorted(milestones)
    ]
    for name, color, description in wanted:
        command = ["label", "create", name, "--color", color, "--description", description]
        result = subprocess.run(
            ["gh", *command, "--repo", repo], capture_output=True, text=True, check=False
        )
        if result.returncode != 0 and "already exists" not in result.stderr:
            sys.exit(f"gh label create {name} failed:\n{result.stderr}")


def apply(actions, issues, repo, good_first):
    ensure_labels(
        repo,
        {row.milestone for row, action, _ in actions if action in ("create", "update", "reopen")},
    )
    for row, action, detail in actions:
        if action == "create":
            title, body, _ = detail
            args = ["issue", "create", "--title", title, "--body", body]
            for label in labels_for(row, good_first):
                args += ["--label", label]
            print(f"task {row.number}: opened {gh(args, repo).strip()}")
        elif action in ("update", "reopen"):
            title, body, _ = detail
            issue = issues[row.number]
            number = str(issue["number"])
            if action == "reopen":
                gh(["issue", "reopen", number], repo)
            args = ["issue", "edit", number, "--title", title, "--body", body]
            for label in labels_for(row, good_first):
                args += ["--add-label", label]
            for label in stale_milestone_labels(issue, row):
                args += ["--remove-label", label]
            gh(args, repo)
            print(f"task {row.number}: {action} #{number}")
        elif action == "close":
            state, outcome = detail
            number = str(issues[row.number]["number"])
            reason = "completed" if state.kind == "done" else "not planned"
            comment = f"The row is now {state.kind}:\n\n{outcome}\n\n{MIRROR_LINE}"
            gh(["issue", "close", number, "--reason", reason, "--comment", comment], repo)
            print(f"task {row.number}: closed #{number} as {reason}")


def report(actions, plans_read, current, good_first):
    names = ", ".join(f"{name} ({len(rows)} rows)" for name, _, rows in plans_read)
    print(f"Plans read: {names}; milestone {current} is current.")
    print('The rule: a row\'s state is its first bold marker; "Done..." closes as completed,')
    print('"Withdrawn"/"Moved..." as not planned, any other marker is in progress and stays open,')
    print("no marker is open and not started. Only the current plan opens issues.\n")
    counts = {}
    for row, action, detail in actions:
        if action == "skip" and row.milestone != current:
            counts["skip, earlier plan"] = counts.get("skip, earlier plan", 0) + 1
            continue
        state = classify(row.text)
        counts[action] = counts.get(action, 0) + 1
        extra = ""
        if row.number in good_first:
            extra = f"  [good first issue: {good_first[row.number]}]"
        print(
            f"  {row.number:>4}  m{row.milestone:<3} {action:<7} {state.kind:<9} ({state.marker})"
            f"{extra}"
        )
        if action in ("create", "update", "reopen"):
            print(f"        {issue_title(row)}")
    print("\n" + ", ".join(f"{k}: {v}" for k, v in sorted(counts.items())))
    current_rows = {r.number for r in plans_read[0][2]} if plans_read else set()
    unknown = [n for n in good_first if n not in current_rows]
    if unknown:
        print(f"good-first list names rows not in the current table: {unknown}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[1])
    parser.add_argument("--plan", help="one m<n>/PLAN.md alone; every plan by default")
    parser.add_argument("--repo", default="vecbricks/varka", help="owner/name for gh")
    parser.add_argument("--apply", action="store_true", help="change the issues; dry run otherwise")
    args = parser.parse_args()
    if args.plan:
        m = re.search(r"m(\d+)/PLAN\.md$", args.plan)
        if not m:
            sys.exit(f"{args.plan} is not a m<n>/PLAN.md")
        plans = [(args.plan, int(m.group(1)))]
    else:
        plans = all_plans()
    plans_read = []
    for plan_path, milestone in plans:
        with open(plan_path, encoding="utf-8") as handle:
            plans_read.append(
                (os.path.relpath(plan_path, PLANS_DIR), milestone, parse_rows(handle.read()))
            )
    if not plans_read[0][2]:
        sys.exit(f"{plans_read[0][0]} has no task table")
    current = plans_read[0][1]
    rows = rows_of_plans(plans_read)
    good_first = good_first_tasks()
    issues = existing_issues(args.repo)
    actions = plan_actions(rows, issues, current, good_first)
    report(actions, plans_read, current, good_first)
    if args.apply:
        apply(actions, issues, args.repo, good_first)
    else:
        print("\ndry run; pass --apply to change the issues")


if __name__ == "__main__":
    main()
