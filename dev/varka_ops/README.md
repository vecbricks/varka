# Varka operations scripts

The scripts that take a branch from "done on the laptop" to an open pull request with a green
option matrix. They grew on 22 and 23 November 2026, after a day of red matrices and failed fix
pushes, each of which a local check would have caught. `INDEX.md` lists every tool's inputs and
outputs; `AGENTS.md` holds the rules for agents. Each script prints its usage when run without
arguments.

## The flow

1. **Gate.** `pre-pr-slot.sh <worktree>` waits for a free slot, then runs `pre-pr.sh`:
   merge `origin/master`, the push check's R1 text check (seconds: a test registry lacking a new
   IR node fails before the regen), regenerate the generated files (or skip that, below),
   `extra-gate.sh` (lint-java, Error Prone, lint-scala, the invariant and proof suites),
   `dev/varka_push_check.py`, the skip-line recheck (below),
   then the arm sweep: every suite the branch adds or changes, under the defaults, `useAVX=2`,
   `useAVX=3` and each configuration whose option the branch's changed lines of
   `VarkaEmitOption.java`, `VarkaEmitOptions.java` or `VarkaMatrixRuns.java` name (below). On
   success it stamps HEAD in `stamps/<worktree-dir>` and appends it to that file's `.history`.
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

**Two gates at a time.** `PRE_PR_SLOTS` defaults to 2. It was 1 until late on 23 November
2026, because the width audit that a regeneration runs has 600 s probes, which timed out beside
another gate (348, 504 and 358a did on 22-23 November). Then a two-slot trial, 601's gate beside
612's, had no probe timeout, and the sweep had been narrowed (below). The gate now gives each
probe `PRE_PR_WIDTH_AUDIT_TIMEOUT` seconds, 2400 by default, through `JDK_JAVA_OPTIONS` (added to
any value already set): the 30-standins, 611 and 343b2a gates still ran past 600 s beside
another gate, and 2400 s is what 103a2's regeneration used. More parallelism lives inside a
gate: its arm sweep runs at `-j PRE_PR_J`.
A light gate - a branch that changes no input of the generated files and no test suite, so it
runs no regeneration and no sweep - starts at once beside a heavy one, unless an exclusive gate
is marked. Committed benchmark results (`sql/<module>/benchmarks/*.txt`) are no input of the
generated files, so a results-only branch is light too and does not wait behind code gates.
A second waiter for the same worktree exits at once with status 3. `PRE_PR_EXCLUSIVE=1` raises
a marker that stops every other waiter from starting, waits for the running gates to finish,
and runs alone at `-j 4`.

**The regen skip.** A branch whose own changes are all tooling, CI, plans, benchmarks or prose
(`.github/`, `sql/varka/{plans,bench,matrix,skills,ci}/`, `dev/*.{py,sh,tsv,json}`, `*.md`,
`sql/<module>/benchmarks/*.txt`)
cannot leave a generated file stale, so the gate runs only `varka_census.py --check`. The
generators and `docs/sql-varka.md` always count as inputs. `PRE_PR_FORCE_REGEN=1` forces it.

**The sweep's scope.** Until 23 November 2026 the sweep ran every configuration, about 60:
611's took about 47 minutes of wall time, and the 34 sweeps since 22 November caught one
failure. The option matrix on the pull request still runs every configuration, so the gate runs
the ones a branch is most likely to break: the defaults, the two named `UseAVX` levels and the
configurations of the options its diff adds or changes: a configuration is chosen when any
name in it (`useAVX=2,narrowConversionForm=WINDOWED` holds two) is an identifier on a changed
line of `VarkaEmitOption.java`, `VarkaEmitOptions.java` or `VarkaMatrixRuns.java`. An option
whose behaviour changes elsewhere, such as an emitter branch it guards, is not found; the pull
request's matrix still runs it. When the option files change but name no configuration, the
sweep runs every configuration. The log lists the configurations chosen and why.
`PRE_PR_SWEEP=all` runs every configuration, after a merge-only change too.

**The merge-only re-gate.** A re-gate whose only new commits since the branch's last stamp are
merges of `origin/master` and commits touching only plans, prose and generated files (not the
hand-written `sql/varka/matrix/skips.tsv`) skips the
sweep, and logs `arm sweep skipped: merge-only since <sha>`. A merge counts only when its code
files are exactly what git's own merge of its two parents writes (`git merge-tree`), so a
hand-resolved code conflict runs the full gate and a resolved conflict in `PLAN.md` does not.
The gate still merges, regenerates, runs `sql/Test/compile` when the branch touches catalyst or
sql (a clean merge can still break compilation, as #1258 and #1261 did), and runs
`extra-gate.sh`, the push check and the skip-line recheck. `PRE_PR_FULL=1` runs the sweep anyway.

**The skip-line recheck.** A clean merge of master keeps a branch's old green matrix, yet
master's edits to `sql/varka/matrix/skips.tsv` can leave a line stale that no matrix has run
with the branch: #1644 re-added a line #1643 had deleted, both merged clean, and #1693's option
matrix failed it. So every skip line added or deleted since the branch's last stamp (with no
stamp, since its merge base) reruns its suite under its configuration, in a merge-only re-gate
too, and so does every line on which the branch differs from `origin/master`, since master never
ran its matrix with it. A line both sides carry is master's to keep true: rerunning every skip
line of the suites that read `coverage.json` on each coverage change would be hundreds of runs.
A deleted line whose option the sources no longer declare went with the option and is not rerun.

**The tree-equal path.** `PRE_PR_TREE_EQUAL=<sha>` serves a stacked branch whose parent
squash-merged after a rehearsed merge of the two had passed the full gate. When HEAD, after the
gate's merge of master, has exactly the tree of `<sha>`, `<sha>` passed the full gate in this
worktree (`stamps/<worktree-dir>.history`), and HEAD contains `origin/master`, the gate runs no
step and stamps HEAD with the mode `tree-equal:<sha>`. Otherwise it logs why and runs as usual. A
dry run ignores it, `PRE_PR_FULL=1` and `PRE_PR_SWEEP=all` override it, and a value that names no
commit exits 2 before anything runs.

**A dry run.** `PRE_PR_DRY=1 pre-pr.sh <worktree>` prints the steps a gate would take, the
merge-only decision and the sweep's configurations, and changes nothing: no fetch, merge,
commit, log or stamp. It reads the configuration list an earlier sweep left under
`target/pre-pr-sweep-<module>/`. `PRE_PR_DRY_SINCE=<rev>` decides from that commit instead of
the last stamp.

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
| `PRE_PR_SLOTS`, `PRE_PR_MIN_GB`, `PRE_PR_J` | 2, 20, 5 | slots, free memory, sweep jobs |
| `PRE_PR_WIDTH_AUDIT_TIMEOUT` | 2400 | seconds for each width audit probe in the gate's regen |
| `PRE_PR_EXCLUSIVE`, `PRE_PR_FORCE_REGEN` | 0, 0 | run alone; regenerate anyway |
| `PRE_PR_FULL`, `PRE_PR_SWEEP` | 0, empty | sweep after a merge-only change; `all`: every arm |
| `PRE_PR_DRY`, `PRE_PR_DRY_SINCE` | 0, empty | print the plan only; decide from that commit |
| `PRE_PR_TREE_EQUAL` | empty | a stamped commit whose tree HEAD may equal: stamp without running |
| `SKIP` | empty | PR numbers `watch-any-ready.sh` ignores |
| `VARKA_OPS_WATCHERS` | `watch-ready.sh watch-failed.sh` | watchers `ops-status.sh` looks for |

With `VARKA_OPS_HOME=~/varka-ops`, a fork remote named `fork` and the worktrees beside the main
checkout, the scripts behave as the copies in the owner's `~/varka-ops` did. Run them from a
checkout that no gate is merging into, such as the main checkout: `pre-pr.sh` merges master into
the worktree it gates, and bash reads a script as it runs.

Two defaults to know before the first gate. `common.sh` creates the ops home and its `logs`,
`stamps`, `locks`, `pids` and `matrix-comments` directories when a script first runs, so a new
machine needs no setup for it. And the gate signs its own two commits, the merge of master and
"Regenerate the generated files", with `VARKA_TRAILER`: set it to the tool and model that run
the gate when they are not the default's.

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
- `skip recheck: <suite> under <configuration>`: a changed skip line is stale or its suite now
  fails there; fix the line or the code, commit, and gate again.
- `skip recheck: not rerun, their options are gone`: deleted lines of an option the branch
  removed; nothing to do.
- `no pre-pr.sh pass on <sha>` or `review-covers: commits after the reviewed ...`: the head moved
  after a stamp; gate again, or review again for a non-merge commit.
- `matrix-after-build-link.sh` writes `build ... failed=[...], no matrix` to its log when the
  Build fails: fix, gate, and `push-ci.sh` again. `watch-infra.sh` prints `REAL #<pr>` for a
  failure it does not rerun: read the run's annotations.
