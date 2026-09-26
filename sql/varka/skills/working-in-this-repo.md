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
  `HEAD` (task 161): a number that only the incoming side committed is not an
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
  task 67 added was affected. It resolves through the analyzer now, so re-take any
  such number rather than trusting it - and note the general shape, since this is the
  second tool in the same family to have carried it: a hand-rolled resolver that
  binds names and looks up functions is enough for `year(d)` and silently not enough
  for `d + ym`, because the operator needs a rule the analyzer owns.
- `dev/varka_hsdis_build.sh` builds `hsdis-<arch>.so` from the JDK's single source
  file against the distribution's libcapstone, no JDK build needed;
  `dev/varka_worktree.sh gc` removes the worktrees whose PRs merged.

## A recipe for a cheap agent ages at the rate of the emitter, not of the arithmetic

Task 35, the third of the four recipe tasks (34-37) to be executed. Its section 2 arithmetic
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
computes `id` and `id + 1` from a tuple list - so a grep can hand you one that is
already taken, and the emitter's `require` then reports it twenty minutes into a
regeneration, after every earlier case has been timed. That happened twice in one
evening, on two different guesses.

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
job on task 116's suite after every test in it had passed locally. `dev/scalastyle`
takes about a minute on a warm build; run it before the first push of any Scala
change, and spell large literals without separators.

## A run's job list on this fork says nothing about what the pull request changed

Every pull request on the fork runs the full matrix - about 36 jobs, about an
hour - however small the change, and that is not a sign the change touched
something wide. The `Check changes` job diffs the checked-out tree against
apache/spark master (`APACHE_SPARK_REF`), which is the fork's whole delta from
upstream, so every module gate answers true; the documents-only pull request that
recorded this (#222, one plan file) was required to run the Varka engine on two
architectures, the bench module, the Java 25 Maven build and every test shard.
Do not read a skipped job as "this PR did not touch that module" or a required
one as "it did", and do not plan a task around a gate skipping until the
precondition measures the pull request's own files (`PLAN_TASK_106.md` 9).

## A red Build here may be failing on code this repository does not contain

The fork's CI does not build the branch. It builds the branch *merged with a newer
upstream Spark* - the Precompile job's log opens with a page of `Auto-merging` lines
across the workflows, `AGENTS.md` and the error-condition tables. So the tree under
test is this repository plus upstream commits it does not carry, and a failing test
can belong entirely to Spark rather than to anything here.

That is not hypothetical. `AvroSchemaHelperSuite."SPARK-59311: a pathologically
nested map-key type names the map-key property on overflow"` failed on every branch
in this fork for a week, including documentation-only ones, while the test existed
nowhere in this repository: `grep -rn "SPARK-59311" .` found nothing outside CI logs.

**The check, and the order to do it in.** Before investigating a failing test, grep
this repository for its name. If the test is not here, the failure is upstream's and
nothing in the branch can have caused it - stop. Only if it is here does the usual
question arise, which is whether the branch or the machine caused it, and which
`.github` documents under "Investigating PR CI Failures".

**The second check, once the sync has brought the test in.** After task 117 synced
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

## A two-track milestone drifts to the measurable track; review it against the done-when list

Milestone 6 has two tracks the owner set on the day it opened: the compiler's foundation and
continuous promotion. Two days in, the foundation spine was nearly through, the research rows
around it were mostly done, the task table had grown from 23 rows to 43, and the promotion
track had no commit at all (`PLAN_MILESTONE_6.md` 9). Nothing in the day-to-day chose that: each
next step was the row with the clearest measurement, and a row with no number in it never won
that comparison.

Two habits follow. The first is to review the milestone against its own done-when list
(section 1.3 of each milestone plan) rather than against the task table, every few days and
whenever a row closes: the table rewards adding and closing rows, the list says what the
milestone is for. The second is that a new row needs a reason to be done in this milestone,
written in the row, or it goes to the furthest-out scope catalogue instead; twenty rows in two
days is more than a milestone closes, and each one added is a choice not to do the track that
has none.

## A day of merges is a day of Builds; the queue now keeps a verdict across docs-only pushes

On 25 September 2026 the owner asked why the Varka pull requests were taking so long to pass
CI, and the fork's run list for the day answered with four causes, in the order they cost.
The queue runner (`dev/varka_ci_queue.sh`, task 176) waits until no Build runs anywhere on the
fork, because the twenty job slots are shared, and two upstream Builds on the same fork that
day took 125 and 132 minutes of them, pausing the Varka queue for 2 h 18 min at one stretch.
One Build failed in a way that hung a job until its timeout (the G32 heap case in
`testing-and-debugging.md`) and cost 4 h 29 min before its fix could be built and rerun. A PR
that touches the build workflow runs every module, 39 jobs with the longest over an hour,
where a plan-only PR runs a handful in about 33 minutes. And seven merges each sent a merge of
master into every other open PR, and each such push cancelled that PR's Build and needed a
new one: the day's list held more than twenty cancelled runs.

The last cause is the one the tooling can remove, and task 211 did: a PR whose head moved
since a Build passed keeps that verdict when everything changed since is outside what the
Build tests - the plans, skills and papers, any Markdown, and committed benchmark results - and
the passed head is an ancestor of the new one. The check is GitHub's compare API from the
tested head to the current one, which for a merge of master lists master's new commits, so a
merge that brought a test file still reruns. The other three are choices rather than tooling:
upstream Builds on the same fork pause the queue for their two hours; a workflow change runs
the full matrix; and merging green PRs in one sitting, then merging master into the rest once,
replaces several requeue rounds with one.
