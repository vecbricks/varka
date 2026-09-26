# Task 183: The task table as issues

*Opened 25 September 2026, the first of the rows `PLAN_MILESTONE_6.md` 9.2
lists after its top four, and the last third of scope item 38.*

## 1. The question

Item 38 named three pieces of onboarding. Two landed on 21 September 2026,
before this milestone opened, with the walkthrough of one expression: the
hardware census page `sql/varka/HARDWARE.md` and the two issue templates,
"take a task" and "add my machine" (`8d02d3ae033`). Row 183 kept all three
in its title, so the row overstated what was left. What was left is the
first piece: **a newcomer arriving from a post finds a task table inside a
plan file, which is a record and not a front door; how does the table become
the view GitHub shows, without becoming a second source of truth?**

## 2. The change

`dev/varka_issues.py`. It reads the current milestone's plan, the
highest-numbered `PLAN_MILESTONE_<n>.md`, finds the task table of section 3,
and for every open row keeps one issue, "[Task <n>] <the row's title>", whose
body is the row's text, its origin and size, and links to the plan and to the
task's own plan file when one exists. A row that turns done or withdrawn
closes its issue with the row's outcome quoted, as completed or as not planned.
Nothing flows the other way: the issue's last line says to edit the row, and
the script brings the issue to it on the next run. `varka-issues.yml` runs it
with `--apply` on every push to master that touches a milestone plan, the
script or the good-first list, so the mirror follows the table without anyone
remembering to run it; by hand it is a dry run unless asked otherwise.

**The rule, stated because the vocabulary is not uniform.** Milestones 5 and
6 mark rows with Done, DONE, "Done, negative", Scoped, Planned, Withdrawn,
Built, "Census drafted", "Laptop ladder done", "Step 1 done", and leave
twenty rows of milestone 6 with no marker at all. So: a row's state is its
first bold marker. A marker beginning with "Done" is done; "Withdrawn" or
"Moved" is withdrawn; any other marker is in progress, open with the marker
shown; no marker is open and not started. Under that rule no row is
unclassifiable, and the dry run prints each row's state beside the marker it
read, which is how the rule is checked against the table rather than asserted.

**Labels.** `task` and `milestone-<n>`, created if missing, and
`good first issue` for the rows in `dev/varka_good_first_tasks.txt`, a
hand-kept list of at most five with a reason each: 173, 174, 186, 187 and 194
today, each a test, a move or a measurement a newcomer finishes in a day with
the pattern already in the tree.

`CONTRIBUTING.md` and the "take a task" template now point at the
highest-numbered plan rather than milestone 5's, state the rule, and send a
newcomer to the row's issue when it has one.

## 3. Verification

* The script's doctests cover the table parser (a pipe inside backticks, the
  ordering table after the task table left alone), the rule on each marker
  shape, the title extraction, the good-first list and the action planner,
  which is pure so that the dry run and `--apply` agree by construction.
* A dry run against `PLAN_MILESTONE_6.md` lists every row with its state.
* `ruff check` and `ruff format` pass at the pinned version.
* The first `--apply` happens through the workflow when this merges, which is
  the owner's approval of the issues it opens; its run is the first check that
  the labels and bodies read as intended, and section 4 records it.

## 4. Outcome

*Written from the workflow's first run.*

## 5. Explicitly out of this task

* Issues for earlier milestones' tables: the mirror is of the milestone in
  flight, and milestone 5's rows are a record.
* Any flow from an issue back into the table.
* A project board or milestone objects on GitHub; labels are enough for the
  question the item asked.
