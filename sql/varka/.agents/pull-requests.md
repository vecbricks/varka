# Branches and pull requests of Varka

Varka's pull requests do not follow Spark's flow (a JIRA ticket, a `[SPARK-xxxx]` title, a pull
request against `apache/spark`). The rules below replace it. Each rule is stated here and nowhere
else; other documents point to this file.

## The remotes

- `origin` is `vecbricks/varka`, the base: every pull request opens against its `master`. Never
  push a branch to it; the pre-push hook (`dev/varka_prepush.sh`) refuses one.
- `fork` is the GitHub repository that takes the branches, and where a pull request's Build
  runs. The scripts in `dev/varka_ops/` read its `<owner>/<repo>` from this remote's URL, or
  from `VARKA_FORK`; `VARKA_FORK_REMOTE` gives the remote another name. `dev/varka_ci_times.py`
  and `dev/varka_matrix_run.py` take `--repo` instead, and default to the owner's fork.
- `upstream` is `apache/spark`. Only a sync pull request reads it
  (`sql/varka/skills/working-in-this-repo.md`, "Upstream enters only through a sync pull
  request").

Uncommitted changes in a worktree are committed or saved as a diff, never stashed: the rule and
its reason are the bullet "Never `git stash`" of `sql/varka/AGENTS.md`, under "Processes".

## The branch

One branch for each pull request, each in a worktree of its own, cut from `origin/master`.

| Branch | For |
|---|---|
| `varka-<n>` | row `<n>`, in the worktree `../varka-<n>`: what `dev/varka_task_new.sh` makes |
| `varka-<n>-<topic>` | row `<n>`, made by hand (below), such as `varka-689-plan` |
| `varka-<topic>` | a change that belongs to no row |
| `varka-sync-apache-<mmdd>` | a sync of Apache Spark's master; the size check reads the prefix |

A branch is made by hand when the row has a plan file already, so the script refuses, when it is
the row's second pull request, or when the change is plan-only.

From the main checkout, with the script's own two steps:

    git fetch origin master
    git worktree add -b varka-<n>-<topic> ../varka-<n>-<topic> origin/master

`dev/varka_worktree.sh list` shows the worktrees, and `gc` removes the merged ones.

## The title

    [VARKA-<n>] <what the change achieves>         a row's pull request
    [VARKA-<n>] Plan: <what the plan decides>      a row's pull request that changes only plans
    [VARKA-<a>][VARKA-<b>] <what it achieves>      one change for two rows
    [VARKA] <what the change achieves>             a change that belongs to no row
    [VARKA] Plan: <what the plan decides>          a plan-only change that belongs to no row

- At most 80 characters, and no "Step N" or "Task N": the title names the goal.
  `dev/varka_msg_check.py pr --title ... --body-file ...` checks both, and `open-pr.sh` runs it.
- `Plan:` is not decoration. The merge gives the commit on master the pull request's title, and
  `dev/varka_plan_lint.py` reads a `[VARKA-<n>]` subject that does not start with `Plan` as work
  on row `<n>`: the row must then open with its state in bold.
- The size cap of `sql/varka/AGENTS.md` ("A pull request stays under 1000 changed lines") is for
  code. A plan-only pull request may pass it by the owner's rule, but the tool has no exemption:
  `dev/varka_msg_check.py size` counts plan files, so its count still fails. The pre-push hook
  runs that count in its rule `R4`, and `VARKA_PUSH_CHECK_SKIP=R4` passes over it for such a push.

The body's rules are in the header of `dev/varka_msg_check.py`.

## The gate, the review and the push

Read `dev/varka_ops/AGENTS.md`: it holds the rule of the one gate, `dev/varka_ops/pre-pr.sh`,
and the steps that open or fix a pull request.

## Who merges

- **The owner merges a pull request that changes code.**
- **The lead agent**, the one that hands out the tasks and reports to the owner, merges a
  plan-only or a results-only pull request once its checks are green.
- **A builder agent**, one that was given a single task, commits on its branch and reports. It
  does not push, open a pull request or merge unless its task says so.

Before any merge, read "Master stays green, ahead of everything else" in `sql/varka/AGENTS.md`.
