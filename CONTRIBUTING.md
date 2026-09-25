## Contributing to Varka

Varka is a research fork of Apache Spark: a vectorized code generator that
compiles projections and filters into vector loops with JDK 25's Class-File API
and the Vector API. The section below is about contributing to Varka's own
code, plans and measurements. The section after it is Spark's, and it still
applies to anything that touches Spark itself.

### Read first

* [`README.md`](README.md), in particular "Reading the source", and
  [`docs/sql-varka.md`](docs/sql-varka.md): what the engine does and how a
  query becomes a vector loop.
* [`sql/varka/WALKTHROUGH.md`](sql/varka/WALKTHROUGH.md): one expression
  from SQL text to the assembly the gate checks, with the tools that show each
  step.
* [`sql/varka/VISION.md`](sql/varka/VISION.md): the architecture and where it
  is going.
* [`sql/varka/AGENTS.md`](sql/varka/AGENTS.md): the house rules, written for
  anyone (or anything) editing the engine.
* [`SKILLS.md`](SKILLS.md), the index over
  [`sql/varka/skills/`](sql/varka/skills/): measured lessons, many of them
  negative results, kept so a settled question is not reopened by accident.

### Finding work

Development runs in milestones, and each milestone is one file under
[`sql/varka/plans/`](sql/varka/plans/) with a numbered task table. The
milestone in flight is the highest-numbered `PLAN_MILESTONE_<n>.md` there,
[`PLAN_MILESTONE_6.md`](sql/varka/plans/PLAN_MILESTONE_6.md) as this is
written; a row whose first bold marker begins with **Done** is finished and
carries a pointer to its outcome, **Withdrawn** rows are closed, and every
other row is open, whether marked **Scoped**, **Planned** or not yet marked.
Each row names what has to be true before the task starts and what counts as
done, and every task has, or gets, its own `PLAN_TASK_<n>.md`. The same rows
are mirrored as [issues labelled `task`](https://github.com/vecbricks/varka/issues?q=label%3Atask),
one per open row, by `dev/varka_issues.py` on every change to the plan; the
ones a newcomer can finish in a day carry `good first issue`. The table is the
source of truth and the issues follow it, so a row is edited in the plan and
never in the issue. The next milestone's scope catalogue is
[`SCOPE_MILESTONE_7.md`](sql/varka/plans/SCOPE_MILESTONE_7.md), and design
input there is as welcome as code.

Before writing code for a task, open a GitHub issue with the "Varka: take a
task" template, so the plan is agreed first. If the work is not in any table,
say so in the issue: new tasks are added as rows, and a finding made while
doing one task becomes a new row rather than a note in the old one. A task's
plan file starts from
[`sql/varka/plans/PLAN_TASK_TEMPLATE.md`](sql/varka/plans/PLAN_TASK_TEMPLATE.md).

Measurements from hardware the project does not have are a contribution in
their own right, and need no build: run `dev/varka_datapath.sh` and open an
issue with the "Varka: add my machine" template. The machines the numbers were
measured on so far are in [`sql/varka/HARDWARE.md`](sql/varka/HARDWARE.md),
and AMD AVX2 machines and Arm are the gaps.

### How the work is done

* **Plans are records.** A task file is written as the work happens: what was
  planned, what was built, what was measured, and where the plan was wrong.
  Predictions are written down before the measurement and scored after it,
  and they stay in the file.
* **Numbers trace to files.** Every performance claim in a document quotes a
  committed results file under a `benchmarks/` directory. `dev/varka_quote_check.py`
  enforces it, and the benchmark scripts (`dev/varka_bench_regen.sh`,
  `dev/varka_bench_diff.py`) regenerate and compare those files; see the
  "Measurements" section of `sql/varka/AGENTS.md`.
* **No `TODO` in code.** Unfinished work lives in a plan file with its reason,
  never as a marker in the source.
* **Comments explain the code to a new reader.** How the code came to be this
  way belongs in the plans and in git.
* **Refactors are proven by the oracles.** `sql/varka/emitted_bytes.json`
  pins what the emitter produces for every documented shape; a change that
  should not alter the generated code leaves it unchanged.

### Building and testing

JDK 25 is required. Build with `./build/sbt` (or Maven, as the quick start
shows). The Varka suites live beside Spark's:

    ./build/sbt "catalyst/testOnly *Varka*" "sql/testOnly *Varka*"

`dev/varka_gate.sh` is the standing gate in one command: compile, the Varka
suites at the host's vector width and at 128 bits, the javadoc build, the
linters and the quote check, each step logged, one summary table.
`dev/varka_gate.sh --list` shows the steps. Install the pre-commit hook once
per clone:

    dev/varka_precommit.sh --install-hook

It checks the column limit, non-ASCII characters outside string literals,
stray `TODO` markers, the quoted numbers, and Python formatting.

CI runs only what a change can reach: a change confined to Varka's files runs
the Varka suites and the assembly gate in about half an hour; a change to a
shared Spark file runs Spark's full matrix.

A fork runs at most twenty CI jobs at once, and one Build is about thirty-five,
so with several pull requests open, give the fork's CI to one at a time in the
order they will merge. `dev/varka_ci_queue.sh` does that: `hold <pr>` after a
push cancels the Build it started and queues the PR, `run` reruns the queue one
Build at a time and prints each verdict, `drop <pr>` after a merge cancels a
run that no longer matters, and `status` shows every open PR's run.

### Pull requests

Titles read `[VARKA] <what the change achieves>`, short and goal-oriented.
The description follows [`.github/PULL_REQUEST_TEMPLATE`](.github/PULL_REQUEST_TEMPLATE)
and explains the mechanism in prose, not only the diff. If a generative AI
tool took part, the last section says so with a `Generated-by:` line naming
the tool and version, as the
[ASF generative tooling guidance](https://www.apache.org/legal/generative-tooling.html)
asks. Pull requests open against `master` of `vecbricks/varka`, from a branch
on your fork.

By contributing you affirm, as for Spark below, that the contribution is your
original work and that you license it to the project under the project's
open source license.

## Contributing to Spark

*Before opening a pull request*, review the 
[Contributing to Spark guide](https://spark.apache.org/contributing.html). 
It lists steps that are required before creating a PR. In particular, consider:

- Is the change important and ready enough to ask the community to spend time reviewing?
- Have you searched for existing, related JIRAs and pull requests?
- Is this a new feature that can stand alone as a [third party project](https://spark.apache.org/third-party-projects.html) ?
- Is the change being proposed clearly explained and motivated?

When you contribute code, you affirm that the contribution is your original work and that you 
license the work to the project under the project's open source license. Whether or not you 
state this explicitly, by submitting any copyrighted material via pull request, email, or 
other means you agree to license the material under the project's open source license and 
warrant that you have the legal authority to do so.
