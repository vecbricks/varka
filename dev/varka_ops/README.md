# Varka operations scripts

The scripts that take a branch from "done on the laptop" to an open pull request with a green
option matrix. They grew on 22 and 23 November 2026, after a day of red matrices and failed fix
pushes, each of which a local check would have caught. `INDEX.md` lists every tool's inputs and
outputs; `AGENTS.md` holds the rules for agents. Each script prints its usage when run without
arguments.

## The flow

1. **Gate.** `pre-pr-slot.sh <worktree>` waits for a free slot, then runs `pre-pr.sh`:
   merge `origin/master`, regenerate the generated files (or skip that, below), `extra-gate.sh`
   (lint-java, Error Prone, lint-scala, the invariant and proof suites), `dev/varka_push_check.py`,
   then the arm sweep: every suite the branch adds or changes, under every option configuration.
   On success it stamps HEAD in `stamps/<worktree-dir>`.
2. **Review.** `/code-review high` on the branch, one triaged fix round, then
   `mark-reviewed.sh <worktree> high [findings-file]`, which stamps the reviewed HEAD. A fix
   commit makes the gate's stamp stale, so gate again after it.
3. **Push.** `open-pr.sh` for a new pull request, `push-ci.sh` for a fix push with CI, and
   `push-no-ci.sh` for a push whose CI is not wanted yet. All three refuse a head without both
   stamps. `review-covers.sh` decides whether the review still covers HEAD: only merges and the
   gate's "Regenerate the generated files" commits may follow the reviewed commit.
4. **Matrix.** `push-ci.sh` calls `start-waiter.sh`, which starts `matrix-after-build-link.sh`:
   it waits for the Build, dispatches the option matrix, and `matrix-link.sh` posts the matrix
   run's link on the pull request and keeps its state current. After `open-pr.sh`, start the
   waiter by hand with the Build's run id.
5. **Watch.** `watch-any-ready.sh` exits when a pull request is green and clean to merge.
   `watch-infra.sh` reruns a run's failed jobs once when every failure is a known runner or
   network fault, and exits on any other failure. `ops-status.sh` prints both views at once.

**One gate at a time.** `PRE_PR_SLOTS` defaults to 1, because the width audit that every
regeneration runs has 600 s probes, which time out beside another gate (348, 504 and 358a did on
22-23 November). The parallelism lives inside a gate: its arm sweep runs at `-j PRE_PR_J`.
A second waiter for the same worktree exits at once with status 3. `PRE_PR_EXCLUSIVE=1` raises a
marker that stops every other waiter from starting, waits for the running gates to finish, and
runs alone at `-j 4`.

**The regen skip.** A branch whose own changes are all tooling, CI, plans, benchmarks or prose
(`.github/`, `sql/varka/{plans,bench,matrix,skills,ci}/`, `dev/*.{py,sh,tsv,json}`, `*.md`)
cannot leave a generated file stale, so the gate runs only `varka_census.py --check`. The
generators and `docs/sql-varka.md` always count as inputs. `PRE_PR_FORCE_REGEN=1` forces it.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `VARKA_OPS_HOME` | `~/.varka-ops` | stamps, locks, logs, pids, matrix comment ids |
| `VARKA_WORKTREES` | parent of the main checkout | where a bare worktree name is looked up |
| `VARKA_FORK` | owner/repo of the `fork` remote's URL | where pushes go and CI runs |
| `VARKA_FORK_REMOTE` | `fork` | the remote the scripts push to |
| `VARKA_REPO` | `vecbricks/varka` | where pull requests open |
| `VARKA_VENV` | `~/.venv-ruff` | its `bin` goes first on `PATH` (ruff for the lints) |
| `VARKA_TRAILER` | `Generated-by: Claude Code (Claude Opus 5.5)` | trailer of the gate's commits |
| `PRE_PR_SLOTS`, `PRE_PR_MIN_GB`, `PRE_PR_J` | 1, 20, 5 | slots, free memory, sweep jobs |
| `PRE_PR_EXCLUSIVE`, `PRE_PR_FORCE_REGEN` | 0, 0 | run alone; regenerate anyway |
| `SKIP` | empty | PR numbers `watch-any-ready.sh` ignores |
| `VARKA_OPS_WATCHERS` | `watch-ready.sh watch-failed.sh` | watchers `ops-status.sh` looks for |

With `VARKA_OPS_HOME=~/varka-ops`, a fork remote named `fork` and the worktrees beside the main
checkout, the scripts behave as the copies in the owner's `~/varka-ops` did. Run them from a
checkout that no gate is merging into, such as the main checkout: `pre-pr.sh` merges master into
the worktree it gates, and bash reads a script as it runs.

## Examples and failures

```sh
dev/varka_ops/pre-pr-slot.sh varka-123-foo          # queue the gate; prints pre-pr: lines
PRE_PR_EXCLUSIVE=1 dev/varka_ops/pre-pr-slot.sh varka-123-foo
dev/varka_ops/mark-reviewed.sh varka-123-foo high ~/notes/review-123.md
dev/varka_ops/open-pr.sh varka-123-foo varka-123-foo /abs/body.md pr123 "[VARKA-123] Fuse foo"
dev/varka_ops/push-ci.sh varka-123-foo varka-123-foo 1700
dev/varka_ops/start-waiter.sh 1700 <build-run-id> varka-123-foo
```

- `pre-pr: FAILED at <step>`: read `logs/pre-pr-<dir>.log` (and `logs/extra-gate-<dir>.log`),
  fix, commit, and queue the gate again. `merge: conflict` lists the files: resolve, commit,
  rerun. `start: the worktree has uncommitted changes`: commit or move them aside first.
- `arm sweep`: the failing configurations are printed; rerun one with `dev/varka_matrix.sh`.
- `no pre-pr.sh pass on <sha>` or `review-covers: commits after the reviewed ...`: the head moved
  after a stamp; gate again, or review again for a non-merge commit.
- `matrix-after-build-link.sh` writes `build ... failed=[...], no matrix` to its log when the
  Build fails: fix, gate, and `push-ci.sh` again. `watch-infra.sh` prints `REAL #<pr>` for a
  failure it does not rerun: read the run's annotations.
