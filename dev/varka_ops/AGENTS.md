# Rules for agents using the operations scripts

These add to `sql/varka/AGENTS.md`. The tools are listed in `INDEX.md` and the flow is in
`README.md`. Branch names, titles and who merges: `sql/varka/.agents/pull-requests.md`.

## Rules the tools enforce, and why

- **Gate before every push.** `pre-pr.sh` through `pre-pr-slot.sh`, never a hand-picked subset.
  Three of the red matrices of 22 November 2026 were option arms nobody had run locally. It is
  the one gate: `dev/varka_gate.sh` runs the suites locally and gates no push.
- **Review once, then stamp.** `/code-review high` with the branch name, one triaged fix round,
  then `mark-reviewed.sh` on the head that round produced. Do not review to zero findings.
- **Never `git push` by hand.** Use `open-pr.sh`, `push-ci.sh` or `push-no-ci.sh`; they check
  both stamps and push to the fork remote only.
- **Two gates at a time.** `PRE_PR_SLOTS` is 2, and the gate gives the width audit's probes
  2400 s each (`PRE_PR_WIDTH_AUDIT_TIMEOUT`), since the suite's 600 s ran out beside another
  gate. Do not raise either without a trial; use `PRE_PR_EXCLUSIVE=1` for a gate that must run
  alone. A light gate (no generated-file input, no suite, or only benchmark results) starts at
  once; do not force it through the queue.
- **The sweep's scope is mechanical.** The gate sweeps the defaults, `useAVX=2`, `useAVX=3` and
  the options the branch's diff names, and skips the sweep after a merge-only change
  (`README.md`). Widen it with `PRE_PR_SWEEP=all` or `PRE_PR_FULL=1`; never narrow it by hand.
  `PRE_PR_DRY=1` shows what a gate would do.
- **The tree-equal path is for a rehearsed tree.** Pass `PRE_PR_TREE_EQUAL=<sha>` only for a
  stacked branch whose rehearsed merge `<sha>` passed the full gate in this worktree; the gate
  checks the tree, the stamp and `origin/master` itself, and runs in full on any mismatch.
- **The regen skip is mechanical.** It applies only when every changed file is tooling, CI,
  plans, benchmarks or prose (`README.md`); do not skip a regeneration by hand.
- **Kill only recorded PIDs.** Never `pkill` or `killall`; `start-waiter.sh` keeps the pid.
- **Never print the environment** (`env`, `printenv`, `set`): it holds tokens.
- **Wait on outputs, not by fast polling.** A gate ends with `pre-pr: passed on <sha>` or
  `pre-pr: FAILED at <step>` in its log and a stamp; wait on the command's exit, or poll every
  few minutes.
- **Never edit a running script.** Bash reads it as it runs. Write a new file and swap it in
  with `mv`.
- **A killed waiter can leave its lock held for up to 40 s.** Its child `sleep` inherits the
  worktree's lock, so a requeue right after a kill exits 3; wait a minute and queue again.

## Opening or fixing a pull request

1. Commit; the worktree must be clean.
2. `pre-pr-slot.sh <worktree>`, run as a background command; wait for its exit.
3. On `FAILED at <step>`, read the log the line names, fix, commit, go to 2.
4. `/code-review high` on the branch, one fix round, then gate again if it committed.
5. `mark-reviewed.sh <worktree> high <findings-file>`.
6. New PR: `open-pr.sh` with an absolute body path whose body records the review; then
   `start-waiter.sh` with the Build's run id. Fix: `push-ci.sh <worktree> <branch> <pr>`.

## Traps seen on 22-23 November 2026

- **The wrong remote.** A push to `origin` reaches the shared repository. The scripts push to
  `VARKA_FORK_REMOTE` only.
- **A relative body path** broke once the script changed directory; `open-pr.sh` resolves it
  first, but pass an absolute path anyway.
- **zsh does not split an unquoted variable.** The agent shell is zsh: `for x in $list` runs
  once. Run multi-word loops under `bash -c` or in a script.
- **Merged master commits counted as fixes.** `review-covers.sh` read master's commits as work
  after the review until it took `--first-parent`; a stale review after a merge means a real
  commit followed it.
- **Duplicate waiters.** Two gates for one worktree, or two matrix waiters for one PR, fight
  over the same files; the worktree lock and the pid file stop the second.
