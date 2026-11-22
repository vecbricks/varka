# Index of the Varka operations scripts

Paths under "Writes" are relative to `VARKA_OPS_HOME`; `<dir>` is the worktree's directory name.
Every script exits 2 on a usage error. The flow and the configuration are in `README.md`.

| Tool | Purpose | Inputs | Writes, exit codes | Calls / called by | When |
|---|---|---|---|---|---|
| `pre-pr.sh` | the local gate: merge master, regen or its skip, extra gate, push check, arm sweep | `<worktree> [--no-merge] [-j N]`; `PRE_PR_FORCE_REGEN` | `stamps/<dir>` = HEAD, `logs/pre-pr-<dir>.log`; 0 passed, 1 a step failed | `extra-gate.sh`, `dev/varka_*`; by `pre-pr-slot.sh` | through the slot, before every push |
| `pre-pr-slot.sh` | queue a gate until a slot is free; one per worktree | `<worktree> [pre-pr args]`; `PRE_PR_SLOTS`, `PRE_PR_MIN_GB`, `PRE_PR_J`, `PRE_PR_EXCLUSIVE` | `locks/pre-pr-<dir>.lock`, `locks/pre-pr-exclusive`; pre-pr's code, 3 already queued | `pre-pr.sh` | to gate a branch |
| `extra-gate.sh` | CI's checks the push check lacks: lint-java, Error Prone, lint-scala, invariant and proof suites | `<worktree>` | `logs/extra-gate-<dir>.log`; prints `extra-gate: passed`; 1 failed | lints, `build/sbt`; by `pre-pr.sh` | inside the gate; alone to recheck lints |
| `mark-reviewed.sh` | record a high review of HEAD | `<worktree> <high\|xhigh\|max> [findings]` | `stamps/review-<dir>` | by hand | after the review's one triaged fix round |
| `review-covers.sh` | does the recorded review cover HEAD | `<worktree>` | 0 covered, 1 not (says why) | by the push scripts | before any push |
| `open-pr.sh` | check title and body, push, open the PR | `<worktree> <branch> <body> <lock> <title>`; fork, repo | `logs/push-<dir>.log`; 1 refused | `review-covers.sh`, `varka_msg_check.py`, `gh` | to open a PR |
| `push-ci.sh` | push a fix with CI, arm the matrix waiter | `<worktree> <branch> <pr>` | 0 pushed and armed, 1 refused or no Build | `review-covers.sh`, `start-waiter.sh` | every fix push |
| `push-no-ci.sh` | push and cancel the runs it starts | `<worktree> <branch>` | 1 refused | `review-covers.sh`, `gh run cancel` | to park a head without CI |
| `start-waiter.sh` | start the matrix waiter, stop its older one by recorded pid | `<pr> <build-run> <branch>` | `pids/matrix-<pr>.pid`, `logs/link-matrix-<pr>.log` | `matrix-after-build-link.sh`; by `push-ci.sh` | after a push with CI |
| `matrix-after-build-link.sh` | wait for the Build, dispatch the matrix, link it | `<run> <branch> <out> [--size-ok]` | `logs/<out>`; 0 dispatched, 1 Build failed | `matrix-link.sh`; by `start-waiter.sh` | through `start-waiter.sh` |
| `matrix-link.sh` | post the matrix run's link on the PR, keep its state current | `<branch> <run-url\|id>` | `matrix-comments/<pr>.id`; 1 no PR | `gh api` | by the waiter; by hand for a manual dispatch |
| `watch-any-ready.sh` | exit when an open PR is clean with a green Build and matrix | `SKIP` | prints `READY #<pr> head <sha>` | `gh` | while waiting to merge |
| `watch-infra.sh` | rerun failed jobs once when all are runner or network faults | none | `watch-infra.seen`; prints `INFRA` or `REAL` and exits | `gh run rerun` | while PRs run CI; restart after each exit |
| `ops-status.sh` | open PRs, matrix verdicts, live watchers | `VARKA_OPS_WATCHERS` | prints only | `gh`, `ps` | at the start of a turn |
| `common.sh` | the shared configuration, sourced | the variables in `README.md` | creates the ops home's directories | by every script | never run directly |
