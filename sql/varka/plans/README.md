# `sql/varka/plans`

One folder for each milestone, `m<n>/`. A folder holds the milestone's own document, `PLAN.md`
(a numbered task table) or `SCOPE.md` (a catalogue with no task numbers yet), and one plan file
for each task, `VARKA-<n>.md`. How a plan is written and kept is in `sql/varka/AGENTS.md`,
"Plans are records, not scratch space".

## The milestone in flight

**Milestone 8 is the milestone in flight.** Its task table is section 3 of `m8/PLAN.md`.

Milestone 9 is planned and not started: `m9/PLAN.md` holds its rows, and none of them is built
before the owner's word (`m9/PLAN.md` section 1, decision 18). The scope catalogue, the work no
milestone has taken yet, is `m10/SCOPE.md`.

This section is the one text that names the milestone in flight; every other document points
here. The milestone in flight is not "the highest-numbered `m<n>/PLAN.md`": the next milestone's
plan is opened while the current one runs. A milestone's closing task changes this section, and
`DEFAULT_PLAN` in `dev/varka_plan_lint.py`, which checks the table of the milestone in flight.

## A task's plan file

A task's plan is `m<k>/VARKA-<n>.md`, in the folder of the milestone whose task table holds row
`<n>`. It starts from the one template, `TEMPLATE_TASK.md` in this directory: keep its headings
in its order, replace the guidance under each, and add a section after the last one when the
task needs it (`m8/VARKA-611.md` adds "Questions for the owner" and "Done when").

Start a task whose row is on master and has no plan file yet with

    dev/varka_task_new.sh <n> "<title>"

It makes a worktree and a branch from `origin/master` (their names:
`sql/varka/.agents/pull-requests.md`, "The branch"), writes the plan file from the template and
installs the commit hooks. It commits nothing: the first commit is the plan, once its admission
check (section 2 of the template) is done.

The script reads the row from `origin/master`. Two cases it does not cover:

- **The row has a plan file already.** The script exits 1 with "this task has a plan". Make the
  worktree and the branch by hand (`sql/varka/.agents/pull-requests.md`, "The branch") and
  continue the plan file that exists.
- **No task table holds the row yet.** The script falls back to the newest `m<k>/PLAN.md`, which
  is milestone 9's, not the milestone in flight. File the row in its table first, by a plan-only
  pull request, then run the script.
