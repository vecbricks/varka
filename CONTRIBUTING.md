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
milestone in flight is named in
[`sql/varka/plans/README.md`](sql/varka/plans/README.md), and nowhere else;
a row whose first bold marker begins with **Done** is finished and
carries a pointer to its outcome, **Withdrawn** rows are closed, and every
other row is open, whether marked **Scoped**, **Planned** or not yet marked.
Each row names what has to be true before the task starts and what counts as
done, and every task has, or gets, its own plan in its milestone's folder,
`m<n>/VARKA-<id>.md`. Every milestone ends
with a closing task, a row of its table, that does the housekeeping the
work leaves behind: the text that names the milestone in flight
changed, the lessons in the skills files, the oracles regenerated
on the closing commit, the milestone's entry in [`CHANGELOG.md`](CHANGELOG.md),
an annotated tag `varka-m<n>` on the commit that closes the last row, and the
worktrees and branches of merged pull requests removed; milestone 7's is
VARKA-261 (`m7/VARKA-261.md`), and the close is recorded as a section of the
plan. The same rows
are mirrored as [issues labelled `task`](https://github.com/vecbricks/varka/issues?q=label%3Atask),
one per open row, by `dev/varka_issues.py` on every change to the plan; the
ones a newcomer can finish in a day carry `good first issue`. The table is the
source of truth and the issues follow it, so a row is edited in the plan and
never in the issue. The scope catalogue, the work no milestone has taken yet, is
[`m10/SCOPE.md`](sql/varka/plans/m10/SCOPE.md), and design
input there is as welcome as code.

Before writing code for a task, say on the row's issue that you take it and
how, so the plan is agreed first. Work that is in no table has no issue yet:
open one with the "Varka: take a task" template and say so there. New tasks
are added as rows, and a finding made while doing one task becomes a new row
rather than a note in the old one. How a task starts, and the one template
its plan file starts from, are in
[`sql/varka/plans/README.md`](sql/varka/plans/README.md).

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
* **Java is modern Java 25.** The rules are in `sql/varka/AGENTS.md`, "Java code"; they apply to
  the code a change touches and to every port from Scala.
* **Comments explain the code to a new reader.** How the code came to be this
  way belongs in the plans and in git.
* **Refactors are proven by the oracles.** `sql/varka/emitted_bytes.json`
  pins what the emitter produces for every documented shape; a change that
  should not alter the generated code leaves it unchanged.

### Building and testing

JDK 25 is required. Build with `./build/sbt` (or Maven, as the quick start
shows). The Varka suites live beside Spark's:

    ./build/sbt "catalyst/testOnly *Varka*" "sql/testOnly *Varka*"

The gate before a pull request is `dev/varka_ops/pre-pr.sh`; its rule and the
steps around it are in [`dev/varka_ops/AGENTS.md`](dev/varka_ops/AGENTS.md).
`dev/varka_gate.sh` is not that gate. It is a full local run in one command: compile, the Varka
suites at the host's vector width and at 128 bits, the javadoc build, the
linters and the quote check, each step logged, one summary table. After the
compile its steps run in parallel lanes and the suites are split over several
JVMs, so it takes about 11 minutes on a 24-core laptop (VARKA-286).
`dev/varka_gate.sh --list` shows the steps. Install the pre-commit hook once
per clone:

    dev/varka_precommit.sh --install-hook

It checks the column limit, non-ASCII characters outside string literals,
stray `TODO` markers, the quoted numbers, and Python formatting.

CI runs only what a change can reach: a change confined to Varka's files runs
the Varka suites and the assembly gate in about half an hour; a change to a
shared Spark file runs Spark's full matrix.

A Build that runs Spark's full matrix waits in its "Admit a full-matrix run"
job while two other full-matrix runs on the same fork are ahead of it, so at
most two share the fork's forty job slots; scoped runs never wait. Label a pull request
`ci-hold` to stop its Build at that job, and rerun the failed jobs after
removing the label to resume it.

### Pull requests

The title's forms, the branch's name, the remotes and who merges are in
[`sql/varka/.agents/pull-requests.md`](sql/varka/.agents/pull-requests.md).
The description follows [`.github/PULL_REQUEST_TEMPLATE`](.github/PULL_REQUEST_TEMPLATE)
and explains the mechanism in prose, not only the diff. If a generative AI
tool took part, the last section says so with a `Generated-by:` line naming
the tool and version, as the
[ASF generative tooling guidance](https://www.apache.org/legal/generative-tooling.html)
asks.

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
