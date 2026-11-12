# Build sheets: the rules every sheet shares

*Written 11 November 2026, at the owner's request: a row's build sheet is detailed enough that a
coding agent can build the row with no judgment calls and stop at a local commit, while review,
push and CI stay with the lead.*

A build sheet is the section "Build sheet (11 November 2026)" at the end of a row's plan file
(`sql/varka/plans/m8/VARKA-<n>.md`). Each sheet says "Follow BUILD_SHEETS.md": the rules below
apply to it in full, and the sheet adds only what is the row's own. Where a sheet and this file
disagree, stop and report; do not pick one.

## 1. Before the first command

1. Read the whole sheet, then `sql/varka/AGENTS.md` and the repository's `CLAUDE.md`. A sheet
   never asks you to read `/home/max/proj/runtime-master`: never open, read, search or list
   anything under it.
2. Work in the worktree the lead names. If none is named, first stop if
   `git branch --list 'varka-<n>'` (the exact name) prints a branch, since someone has started the
   row; then `git fetch -q origin master && git worktree add --no-track -b varka-<n> ../varka-<n>
   origin/master`. Do not run `dev/varka_task_new.sh`: the row's plan file exists. Stop if `git show origin/master:sql/varka/plans/m8/BUILD_SHEETS.md` fails: the sheets have not
   merged yet.
3. Install the hooks once in the worktree: `dev/varka_precommit.sh --install-hook`.
4. Run the sheet's step B0 (the premise re-check) before any edit. A grep that does not print
   what the sheet says it prints is a stop: report the command and its output.

## 2. The environment

Every shell that runs a build, a test or a check starts with these three exports:

```
export SPARK_LOCAL_IP=127.0.0.1
export PATH=$HOME/.venv-ruff/bin:$PATH
export VARKA_HEAVY_LOCK=/home/max/varka-ops/locks/<slot>.lock
```

`<slot>` is the lock slot the lead gave you (for example `heavy8`); if none was given, use
`heavy`. Never print the environment: no `env`, `printenv`, `set` or `export -p`, and no grep of
their output. A variable is checked by name, `echo "$VARKA_HEAVY_LOCK"`.

## 3. The lock, and the two tools that take it themselves

- Wrap every plain `build/sbt` command in the lock:
  `flock "$VARKA_HEAVY_LOCK" build/sbt '<module>/testOnly *Suite'`.
- Call `dev/varka_regen_generated.sh` and `dev/varka_push_check.py` **bare**, never under
  `flock`. Both take `$VARKA_HEAVY_LOCK` themselves, and an outer `flock` on the same file
  deadlocks with no error.
- One sbt command at a time. Do not start JVMs side by side, and do not background a test run
  and start another.
- A flag the test JVMs must see goes into `Test / javaOptions`, not `JAVA_OPTS`, for example the
  memory sanitizer:
  `flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *Suite'`.
  Use `LocalProject("catalyst")` for a catalyst suite. A test that `assume`s the sanitizer and is
  reported as canceled did not run: that is a failed step, not a pass.
- A run that aborts with `BindException` or `Cannot assign requested address` is the
  environment, not the code: check `SPARK_LOCAL_IP` and rerun.

## 4. Processes

- Never kill by pattern: no `pkill`, `killall`, `kill $(pgrep ...)`. Kill only a PID you
  recorded when you started the process.
- Never wait in a `pgrep` loop. Run a long command in the foreground, or as a background
  command whose exit the tool reports.
- Never `git stash`. To set changes aside, commit them on your branch or save
  `git diff > <scratch file>`.
- Write only inside your worktree and your scratch directory.

## 5. Generated files

- Never edit `coverage.json`, `emitted_bytes.json`, `width_audit.json`, `types.tsv`, the census
  files or the generated blocks of `docs/sql-varka.md` by hand. A sheet that expects one to change
  names the regeneration command and the expected diff.
- After any merge of master into your branch, or any rebase, run
  `dev/varka_regen_generated.sh --all` (bare). If its first line says "no JVM regeneration owed"
  after a rebase, report it: the files were not regenerated.
- A sheet that says a generated file stays unchanged means `git diff --exit-code -- <file>`
  exits 0 after the sheet's last test run. Anything else is a stop.

## 6. Linters and checks

Run steps 1 to 5 on the final tree before the commit, in this order, and fix what they report;
step 6 runs after it:

1. `dev/varka_precommit.sh` (it also runs as the pre-commit hook).
2. `git fetch -q origin master` and then
   `flock "$VARKA_HEAVY_LOCK" dev/varka_errorprone.sh --master "$(git rev-parse origin/master)"`
   when the change touches Java. javac stops at the first file with findings, so rerun until it
   exits 0.
3. `flock "$VARKA_HEAVY_LOCK" dev/lint-scala` when the change touches Scala, and
   `flock "$VARKA_HEAVY_LOCK" dev/lint-java` when it touches Java. These three start sbt and do
   not take the lock themselves, so they go under `flock`.
4. `ruff check dev && ruff format --check dev` when the change touches Python.
5. `python3 dev/varka_plan_lint.py --check --offline`.
6. After the commit (section 7), `python3 dev/varka_push_check.py` (bare, with the exports of
   section 2): it reads the branch's commits, prints the size count and runs the suites the diff
   implies, and must exit 0. If it makes you change a file, amend the unpushed commit
   (`git commit --amend --no-edit`) and run it again.

Source lines stay within 100 columns and ASCII outside string literals. A quick check over the
changed files: `git diff --name-only --diff-filter=ACM origin/master...HEAD | xargs grep -nP
'[^\x00-\x7F]'` prints nothing.

## 7. The commit, and where you stop

- One commit, or a few, on your branch. The subject is `[VARKA-<n>] <what the change achieves>`,
  at most 80 characters, then a blank line, then a body of a few lines, then a blank line and
  exactly one attribution line, `Generated-by: <tool name> (<model name>)`, the agent filling in
  its own tool and model, for example `Generated-by: Claude Code (Haiku 5.5)`. Nothing else: no
  `Co-Authored-By:`, no session link, no other trailer.
- Before committing, edit the row's line in `sql/varka/plans/m8/PLAN.md` as the sheet says
  (usually: it opens with `**Started**` or `**Done**`), because the plan lint counts a
  `[VARKA-<n>]` commit as work on row n.
- Fill the row's plan file's "Outcome" section (add one at the end if it has none): what was
  built, the tests and their results, each prediction the plan registered scored, and anything
  that went differently from the sheet.
- Commit the work before the first planted fault (step B3; amend the commit afterwards). A fault
  is checked by rerunning the step B4 command that runs the test it names, and reverted with
  `git checkout -- <file>` before the next. A new member goes where the sheet says; where it names
  only the file, directly after the member it is used with.
- **No push, no pull request, no workflow dispatch, no comment on GitHub.** Stop at the local
  commit and report: the branch, the commit hash, the commands you ran with their results, the
  size count `dev/varka_push_check.py` printed, and every stop rule you met.

## 8. Stopping

Each sheet lists its own stop rules. These apply to every sheet:

- a premise grep that does not match;
- a test the sheet expects to pass that fails, or one it expects to fail that passes;
- a generated file that moves where the sheet says it does not;
- a size count over the sheet's estimate by more than half, or over 900;
- an open pull request the sheet lists that has merged since the sheet was written, and whose
  changes conflict with yours: merge master, run `dev/varka_regen_generated.sh --all`, rerun the
  sheet's tests, and continue only if they pass as the sheet says;
- anything the sheet does not decide. Report it with what you found; do not choose.
