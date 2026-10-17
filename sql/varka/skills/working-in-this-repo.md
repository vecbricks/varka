# Working in this repository

Process lessons about this repository rather than about the engine.

One of Varka's lesson files; the index over all of them is
[`SKILLS.md`](../../../SKILLS.md) at the repository root, which is generated from
these files by `dev/varka_toc.py`.

## Repo Workflow (vecbricks/varka)

- Remotes here: `origin` = `vecbricks/varka` (PR base, `master`), `fork` =
  `MaxGekk/spark` (PR head). Push the PR branch to `fork`, then open against
  `vecbricks/varka:master`.
- No JIRA IDs. Titles are `[VARKA] <short summary>`; PR descriptions are prose in
  the five standard template sections; sign off with a `Generated-by:` line naming
  the actual tool (recent PRs: `Generated-by: Claude Code (Claude Fable 5)`).
- Branch naming: `varka-<topic>` tracks `origin/master` and stays one commit ahead
  per PR.
- The standing gate is one command, `dev/varka_gate.sh`: compile, the Varka suites
  at both widths, the opt-in exhaustive sweeps, `catalyst/doc`, both linters, each
  step logged under `target/varka-gate/`, one summary table, non-zero exit on any
  failure. `--only`/`--skip` take step names, `--list` shows them. It finds
  `hsdis-<arch>.so` for the assembly suite in the usual local places and says
  whether it did, so a run whose instruction assertions cancelled is visible.
- After every merge to master, `dev/varka_pr_sweep.sh` dry-merges every open PR
  against master and against each other through GitHub's `refs/pull/<n>/head`,
  and exits with the number of conflicts. It uses `git merge-tree --write-tree`;
  the legacy three-argument `merge-tree` prints a diff, so its conflict markers
  carry a leading `+` and a grep for `^<<<<<<<` sees none - the script's first
  version passed a conflicting PR that way.
- Benchmark files are regenerated with `dev/varka_bench_regen.sh` and read with
  `dev/varka_bench_diff.py`; `sql/varka/AGENTS.md`'s "Measurements" section says
  how and why, including the committed 128-bit companion file, the machine canary
  (`dev/varka_bench_canary.sh`) the regen script runs first, and the quote check
  (`dev/varka_quote_check.py`, a gate step) that holds every quoted number to a
  committed file. A bare class name resolves to `org.apache.spark.sql.<name>`
  (the script's `*.*) ... *) fqcn="org.apache.spark.sql.$klass"` fallback);
  `VarkaThroughputBenchmark` actually lives under
  `org.apache.spark.sql.execution.benchmark`, so the bare name sends `runMain`
  looking for a class that is not there. The wide run's own stdout - where
  sbt's "No main class detected" would show - is redirected to `/dev/null` by
  the regen script, so this fails in about twenty seconds with no visible
  error at all, just a bare exit 1: pass the fully-qualified name for any
  benchmark outside `org.apache.spark.sql` directly, rather than trying a bare
  name first and reading the silence as a machine or environment problem.
- The quote check walks the history of the results directories as well as their
  current files, and while a merge is in progress it walks `MERGE_HEAD` beside
  `HEAD` (VARKA-161): a number that only the incoming side committed is not an
  orphan. If the hook refuses a merge commit for numbers master holds, the fix
  is the checker, not `--no-verify`.
- Run `dev/varka_precommit.sh` before committing, or install it as the pre-commit hook:
  non-ASCII outside strings, lines over 100 columns, TODO/FIXME under Varka
  directories, the quote check, and ruff (`check` and `format --check`) on Python
  files. Each of those has reached CI or a reviewer at least once; the formatter
  reached CI on five PRs at once, because `dev/lint-python` skips ruff silently
  when it is not installed. Git hooks live in the main repository's `.git/hooks`
  and are shared by every worktree, so the installed hook resolves the script
  through `git rev-parse --show-toplevel` at run time rather than through the
  installing worktree's path: the earlier form hardcoded one worktree, and
  pruning the merged worktrees (`dev/varka_worktree.sh gc`) deleted it and
  broke commits in every remaining worktree at once.
- `VarkaIrFuzzSuite` fuzzes the emitter: random IR over random null patterns, lengths
  and option variants against the shared reference evaluator, reproducible by seed
  and iteration.
- A task starts with `dev/varka_task_new.sh <n> "<title>"` (worktree, branch, plan from
  `sql/varka/plans/TEMPLATE_TASK.md`, hook); a regeneration ends with
  `dev/varka_bench_diff.py --git HEAD <file> --requote`; the volume checks run from
  `dev/varka_nightly.sh`.
- Before registering op counts in a plan, print them: `dev/varka_emit.sh "<sql>"`
  gives the IR, the shape hash and per-method `IntVector` invocation counts on the
  suite's own scale; `--asm` adds C2's assembly for the dense loop; `--table
  --variant k=v` prints the plan's op-count table with deltas against the defaults.
  **A count taken from that tool before 12 September 2026 for an expression written
  with an operator rather than a function is wrong, and wrong in the direction that
  hides work**: the tool resolved attributes and functions but never ran type
  coercion, so `d + ym` stayed an `Add` over a date and an interval instead of
  becoming `DateAddYMInterval`, and it reported `declined` for shapes the surface had
  been timing with `expectFused` for weeks. Every date/interval arithmetic spelling
  VARKA-67 added was affected. It resolves through the analyzer now, so re-take any
  such number rather than trusting it - and note the general shape, since this is the
  second tool in the same family to have carried it: a hand-rolled resolver that
  binds names and looks up functions is enough for `year(d)` and silently not enough
  for `d + ym`, because the operator needs a rule the analyzer owns.
- `dev/varka_hsdis_build.sh` builds `hsdis-<arch>.so` from the JDK's single source
  file against the distribution's libcapstone, no JDK build needed;
  `dev/varka_worktree.sh gc` removes the worktrees whose PRs merged.

## A recipe for a cheap agent ages at the rate of the emitter, not of the arithmetic

VARKA-35, the third of the four recipe tasks (34-37) to be executed. Its section 2 arithmetic
was verified in planning and was right on the first run under every variant; every correction
the build needed was to the recipe's picture of the emitter, and the re-plan written six weeks
earlier (its section 7) had itself gone stale in three places by build time: the leap flag's
signature (seven parameters, then one), a helper the re-plan assumed would exist (it did not;
the code was inline in another arm), and the weight constants (two values each moved twice).
The lesson for writing such recipes: pin the arithmetic in a verification script, which
survives, and describe the emitter by *what to look for* - "the method that leaves the leap
mask", "the switch that throws on an unknown calendar node" - rather than by signatures and
numbers, which do not. The one thing that reliably told the builder what had moved was the
compiler: every exhaustive switch over the sealed IR family fails to compile until the new
record is handled, and the two that are not exhaustive (`tailReadsMarchMonth`, `chronoChild`)
throw at emit time on the first test. The hand-maintained lists are the ones to check by hand:
the fuzzer's node generator and the two pinned fixtures.

## A value that depends on repo state is queried, not chosen

Adding a case to `VarkaEmitterParityBenchmark` needs a free case id, and the id
names the emitted kernel's class. Picking one by reading the ids near where you
are editing fails: not every id in that file is a literal - the trunc block
computes `id` and `id + 1` from a list of records - so a grep can hand you one
that is already taken, and the emitter's `require` then reports it twenty minutes
into a regeneration, after every earlier case has been timed. That happened twice
in one evening, on two different guesses.

`dev/varka_bench_ids.sh` is the answer to the question the guess was trying to
answer. It runs the benchmark with `-Dvarka.bench.dryRun=true`, which emits and
registers every case and times none, and prints the ids in use and the next free
one - the file's own answer, in about as long as a JVM takes to start. The id
space is per file, since each benchmark names its kernels with its own class
prefix.

The general form is worth more than the script. A value whose correctness depends
on something already in the repository should be computed from that something,
never chosen because it looks right: the next free id from the set of ids, a
test's expected value from the same function that builds its data, a fixture's
selectivity from a count query rather than from the arithmetic meant to produce
it, the safety of widening a shared fixture from a grep for who reads it. The
cost asymmetry is what makes it a rule rather than a habit - where the repository
has a guard the mistake is loud and costs one round trip, and where it has none
the run succeeds and publishes something other than what its name says.

A dry run is a check on structure and says nothing about numbers. It is not a
faster regeneration, and the script's header says so where someone tired might
reach for it.

## Run `dev/scalastyle` before pushing Scala; its parser is older than the language

The pre-commit hook's line-length and non-ASCII scans are hints, not the linter. CI's
Scala linter job runs `dev/scalastyle`, whose parser (scalariform) predates parts of
Scala 2.13 it will meet in new code: it rejects underscore separators in numeric
literals (`86_400_000_000_000L` fails with "Expected token RPAREN but got
Token(INTEGER_LITERAL ...)") although scalac accepts them. That failed the linter
job on VARKA-116's suite after every test in it had passed locally. `dev/scalastyle`
takes about a minute on a warm build; run it before the first push of any Scala
change, and spell large literals without separators.

## A run's job list on this fork used to say nothing about what the pull request changed

Until VARKA-106, every pull request on the fork ran the full matrix - about 36
jobs, about an hour - however small the change, because the `Check changes` job
diffed against apache/spark master, the fork's whole delta from upstream. It now
diffs the checked-out commit against its merge base with Varka's master
(`VARKA_DIFF_BASE`), and prints the count as "changed files vs base". A count in
the thousands means that base could not be fetched and the run fell back to the
whole delta: then a required job still says nothing about what the pull request
touched.

## Upstream enters only through a sync pull request

Until 14 October 2026 the fork's CI did not build the branch. Upstream's
checkout action, which Varka had kept, checked out apache/spark's newest master and
squash-merged the branch on top, so the tree under test was this repository plus
upstream commits it did not carry. Two things followed. One upstream edit to a file
Varka rewrote failed every open branch at checkout at once (`build_and_test.yml` on
9 October, `README.md` on 14 October). And a test could fail on every branch while
it existed nowhere in this repository, as `AvroSchemaHelperSuite."SPARK-59311: a
pathologically nested map-key type names the map-key property on overflow"` did for
a week.

`.github/actions/checkout-and-sync` now checks out the triggering commit itself.
Upstream changes arrive only through a sync pull request (branch
`varka-sync-apache-<MMDD>`) that merges apache/spark's master into Varka's master;
its run is where an upstream conflict or an upstream test failure shows, once, and
the checks below are for that run.

**The check, and the order to do it in.** Before investigating a test that fails
on a sync pull request, grep the pre-sync master for its name. If the test is not
there, the failure is upstream's and nothing in Varka can have caused it - stop.
Only if it is there does the usual question arise, which is whether the branch or
the machine caused it, and which `.github` documents under "Investigating PR CI
Failures".

**The second check, once the sync has brought the test in.** After VARKA-117 synced
the fork with upstream master, the same test was in the tree and still red, and the
grep no longer settles it. The next question is whether upstream's own CI fails it
at the same JDK: this fork builds on Java 25 (`build_main.yml` sets `java: 25`),
upstream's default job on 17. `gh run list --repo apache/spark --workflow
build_java25.yml --branch master` and the Avro shard of the latest run answered it
in one command - upstream's Java 25 run fails the identical test, its Java 17 run
passes, and the fork has no diff against upstream under `connector/avro` or the
parser. Locally the test fails three runs out of three on JDK 25, so it is not a
flake to re-run past. That makes it upstream's Java 25 problem, which the fork
inherits on every pull request until upstream fixes it, and still not a milestone
row: what the fork can do about it is know why the shard is red.

Doing that backwards is expensive and the expense is invisible: comparing a failure
across several branches establishes only that it is widespread, which is a weaker
fact than the grep gives in one command, and it invites the conclusion that a
widespread failure is *this project's* problem to schedule. It is not. A failure in
code this repository does not contain is not Varka work and does not become a
milestone task row; at most it becomes a line here, which is what this is.

## A run of master scoped by its diff from master tests nothing, and says it passed

Since upstream stopped being merged in at checkout, a pull request's Build tests the branch as
it was cut, not the branch merged with today's master, so two pull requests green on their own
can break master together. `varka-master.yml` (VARKA-255) builds master after each merge for
that reason, on `vecbricks/varka`'s own slots: Varka's jobs, the linters and the Java 25 Maven
build, one run at a time with the newest push waiting; Spark's module matrix is the weekly run's.

The trap in giving master a run is that every scoping step measures a change against the merge
base with master, and on master that base is the head. Three places read the empty diff as
"nothing to do" and passed: the precondition selected none of the test jobs (the weekly run
did this for three Sundays), `dev/run-tests.py` narrowed each module job to no module and printed
"There are no modules to test", and the option step reran a failure on its "merge base" - the
same tree - and excused it. Two more said something false: the docs job skipped the Python and
R API docs, and the emitted-bytes step called the merged tree "byte-identical". The master runs
now name their jobs, and the other readers treat a base equal to the head as no change to
measure. When a CI step compares against a base, ask what it does when the base is the commit
itself.

## A path-based checkstyle suppression matches the absolute path, checkout directory included

`dev/checkstyle-suppressions.xml` exempted Spark's sources from `InvalidJavadocPosition` with
"no path segment named `varka`". Checkstyle matches `files=` against the absolute path, and
`vecbricks/varka`'s runners check out into `/__w/varka/varka/`, so on that repository nothing was
exempt and the weekly run's Java linter failed on six Spark files, while the fork, checked out
into `/__w/spark/spark/`, passed. The pattern now requires a Varka package path,
`java/org/apache/spark/.../varka/`, or a class name holding `Varka`. A suppression keyed on a
directory name has to be keyed below the source root, or it depends on where the repository was
cloned. Checkstyle 11.0.1 runs in
seconds from the coursier cache (`com.puppycrawl.tools.checkstyle.Main -c dev/checkstyle.xml
<dir>`), which is how the failure was reproduced under a root named `varka/varka`.

## A two-track milestone drifts to the measurable track; review it against the done-when list

Milestone 6 has two tracks the owner set on the day it opened: the compiler's foundation and
continuous promotion. Two days in, the foundation spine was nearly through, the research rows
around it were mostly done, the task table had grown from 23 rows to 43, and the promotion
track had no commit at all (`m6/PLAN.md` 9). Nothing in the day-to-day chose that: each
next step was the row with the clearest measurement, and a row with no number in it never won
that comparison.

Two habits follow. The first is to review the milestone against its own done-when list
(section 1.3 of each milestone plan) rather than against the task table, every few days and
whenever a row closes: the table rewards adding and closing rows, the list says what the
milestone is for. The second is that a new row needs a reason to be done in this milestone,
written in the row, or it goes to the furthest-out scope catalogue instead; twenty rows in two
days is more than a milestone closes, and each one added is a choice not to do the track that
has none.

## A day of merges was a day of Builds; the laptop queue kept a verdict across docs-only pushes

On 25 September 2026 the owner asked why the Varka pull requests were taking so long to pass
CI, and the fork's run list for the day answered with four causes, in the order they cost.
The queue runner of the time (`dev/varka_ci_queue.sh`, VARKA-176, deleted by VARKA-255) waited
until no Build ran anywhere on the fork, because the twenty job slots were shared, and two
upstream Builds on the same fork that day took 125 and 132 minutes of them, pausing the Varka
queue for 2 h 18 min at one stretch. One Build failed in a way that hung a job until its timeout
(the G32 heap case in `testing-and-debugging.md`) and cost 4 h 29 min before its fix could be
built and rerun. A PR that touched the build workflow ran every module, 39 jobs with the longest
over an hour, where a plan-only PR ran a handful in about 33 minutes. And seven merges each sent a
merge of master into every other open PR, and each such push cancelled that PR's Build and needed
a new one: the day's list held more than twenty cancelled runs.

VARKA-211 removed the last cause in the laptop script: a PR whose head moved since a Build passed
kept that verdict when everything changed since was outside what the Build tests and the passed
head was an ancestor of the new one. Since #806 a documents-only push runs only the six short
documents jobs, so the kept verdict saved little, and VARKA-255 retired the script with it: the
script ran on the laptop, slept when the laptop did, and had dispatched nothing since 3 October.
The other three causes were choices rather than tooling, and the next section says what replaced
the queue.

## The fork's CI queue is a job inside the Build, and the check sync rides the push

VARKA-255 replaced the laptop queue (above) with two token-free parts, after an admission check
found that GitHub's cron fires four to ten hours late on both repositories (`VARKA-255.md` 2.1),
so nothing may wait on a cron, and that one Build at a time would have ended 13 October almost
nine hours late (2.2): the harm is a full-matrix run taking every slot, not two runs side by side.

* **`admit`** in `build_and_test.yml` runs only in a Build that will precompile Spark, and
  `Precompile Spark` needs it to succeed. `dev/varka_ci_admit.py --concurrent 2` waits while two
  other fork runs are ahead (their `admit` has succeeded, so they have fanned out, or started
  waiting earlier), so full runs go at most two at a time in the order they asked, and scoped
  runs never wait. On a fork the module and PySpark matrices take at most 12 and 6 slots, so two
  full runs fit in forty. A pull request labelled `ci-hold`
  on `vecbricks/varka` fails at `admit`; remove the label and rerun the failed jobs to resume.
  A re-push cancels a waiting run through the Build's own concurrency group.
* **The check sync**, `update_build_status.yml` on the base, starts when notify ("On pull request
  update") completes for a push, and passes every 120 seconds while any open pull request's fork
  run is in flight. Rerunning a fork run fires nothing on the base: dispatch the sync yourself
  (`gh workflow run update_build_status.yml -R vecbricks/varka`) if no loop is alive.
* **A job that waits cannot run on `ubuntu-slim`**: GitHub stops a slim job at 15 minutes (the
  runner's changelog of 28 October 2025), so both run on `ubuntu-latest`. A slot is a slot either
  way.
* **Step times** have a committed budget, `sql/varka/ci/step-budget.tsv`, written by
  `dev/varka_ci_times.py collect` from master's runs, the nightly fuzz and laptop gate
  directories; each completed Build check carries `compare`'s table, and `dev/varka_ci_times.py
  gate target/varka-gate` reads a laptop gate (or a nightly's dated directory) against it. A
  marked step - more than 5% and at least 30 s over the budget's 90th percentile - is a line in
  the pull request's description, not a failure: one day's suites step ranged 2.8 times across
  runners.
* **New tests rerun, and a fix shows its bug.** The Varka suite jobs rerun the tests a pull
  request adds five times (`dev/varka_new_tests.py rerun`), naming a test whose name is not a
  literal as unlistable rather than dropping it; label a pull request `fix` and its new tests
  must also fail on the merge base's main sources (`fails-on-base`), or the job fails naming the
  one that passed. Add the label before the push, or rerun the job after adding it: a push build
  reads the label when it runs.

## GitHub Actions facts the CI queue cost a run each to learn

From VARKA-255 (13 to 17 October 2026, `VARKA-255.md` 2 and 9), which moved the fork's CI queue
into the Build. Each is a fact of the platform, checked against a run or its documentation:

* **Scheduled, dispatched and `workflow_run` workflows run only from a file on the default
  branch.** The fork's `master` is apache/spark's, so a new workflow file cannot be dispatched
  there, and nothing in the queue's design puts one on it.
* **Events made with a `GITHUB_TOKEN` start no workflows**, except `workflow_dispatch` and
  `repository_dispatch`: a rerun made with the token fires nothing to wait on.
* **An explicit `permissions` block zeroes every scope it does not name.** `build_main.yml`'s
  `packages: write` left its job without `actions: read`, and a reusable workflow's job can only
  narrow what its caller grants.
* **Unauthenticated reads get 60 an hour per IP**, an address every hosted runner shares; read
  through the reading repository's own token, which gets 1,000 an hour.
* **`runs/<id>/jobs` returns the latest attempt only**, with the earlier attempt's times on the
  jobs it carried over; `attempts/<n>/jobs` returns an earlier one.
* **A `concurrency` group is decided before any job runs**, from contexts such as the ref, so it
  cannot tell a full-matrix run from a scoped one and would serialize both.
* **"Re-run failed jobs" keeps the first attempt's `admit`**, so its matrix fans out at once;
  only "Re-run all jobs" queues again.
* **A branch name goes to a shell step through `env`, not a `${{ }}` expression**, and two SHAs
  are compared by git, not as strings, since an abbreviated one defeats the string.
