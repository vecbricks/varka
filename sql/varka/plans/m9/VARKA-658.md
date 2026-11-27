# VARKA-658: The fan-out task: its template, generator, oracle and ledger

*Planned on 27 November 2026. The plan stands alone: an agent needs this file and the
repository's `AGENTS.md` files. The row is not started: milestone 9 waits for the owner's word
(`m9/PLAN.md` decision 18) and for milestone 8's close (the tag `varka-m8`), and this row also
waits for row 653's seven parts, for row 654's two and for rows 642 and 646 (section 1). The
code facts were checked on master `e0aee0a14b1`, in the worktree
`/home/max/proj/varka-m9-read-w`. Every script, card, template and guide text that this plan
gives was run on a scratch copy: the tool's own tests, its commands over the two pilot cards
against their merged commits, and its git commands in a throwaway repository (3.4). No JVM ran:
each of the oracle's three JVM steps is written from a command the house runs, named with its
file and line (4.5), and part C, which runs them first, has a closed list of the command lines
it may repair (5.3). The findings of three reviews are in, 16, 9 and 1, and so are the
owner's answers of 27 November 2026 to the plan's eight questions (section 13): a builder
stops at `READY` and the lead opens every fan-out pull request, and the row waits for rows
642 and 646.*

## 1. What this row is

**The row's text** (`m9/PLAN.md` section 3, row 658): "The fan-out task: its template,
generator, oracle and ledger. [...] the admission check: `sql/varka/ADDING_AN_EXPRESSION.md`
describes milestone 4's int-lane engine, and no command says of one entry and one type whether
the work is done) Section 2.7. **Done when** a plan generated for a merged entry names every
file that entry's pull request touched; the oracle passes on that entry and fails with its
kernel reverted; the oracle's time on the laptop is measured; the ledger has its columns and
one line; and the guide describes today's tree".

**The problem in one paragraph.** The owner will give the milestone's repetitive work, about
50 pull requests that each fuse a few expressions over one type, to cheaper models that are
not Claude models (decision 3). Such a model has only a task plan and the `AGENTS.md` files. A
plan written by hand for each task costs as much as the task. And nothing today says, in one
command, whether such a task is done: the house's gate checks a whole branch, not "is `abs`
fused over `INT`, with its row, its records and its census line, and nothing else changed".
So the lead would have to read every diff.

**What the row gives.** Five things, one for each clause of the done-when:

| clause of the done-when | what this plan makes of it | the check | part |
| :--- | :--- | :--- | :--- |
| "a plan generated for a merged entry names every file that entry's pull request touched" | a card for each of two merged entries, #1584 and #1434; the generator's list of the files a task may change; a table of the paths that moved since those pull requests merged | `python3 dev/varka_fanout.py scope <card> --against <commit>` prints `0 not named by the plan` for 13 of 13 and 23 of 23 files | A, B |
| "the oracle passes on that entry and fails with its kernel reverted" | `python3 dev/varka_fanout.py oracle <card>`: seven steps, exit 0 and a `fanout: PASSED` line, or exit 1 and `fanout: FAILED <id> at <step>` | on #1584's card it passes; with the arm of `Abs` over an int deleted it prints `fanout: FAILED VARKA-621-abs-int at fused` | C |
| "the oracle's time on the laptop is measured" | the oracle prints each step's seconds; one run with the regeneration forced is committed | `m9/results/VARKA-658-oracle.txt` | C |
| "the ledger has its columns and one line" | one file a pull request under `m9/fanout/ledger/`, 18 columns, printed as a table; the first line is #1584's | `python3 dev/varka_fanout.py ledger` prints the header and one line | D |
| "the guide describes today's tree" | `ADDING_AN_EXPRESSION.md` rewritten; `check` holds every path and class it names to the tree in every Build | seven stale statements gone; `python3 dev/varka_fanout.py check` prints `0 error(s)` | E |

**How an agent knows it is done, and how the lead knows it without reading the diff.**

- The agent runs one command. It is done when the last line is `fanout: PASSED <id> tree
  <tree> in <n> s`, it has committed, `python3 dev/varka_fanout.py ready <card> --body-file
  <file>` prints `fanout: READY <id> head <commit>`, and it has sent its report (4.6). It does
  not push and opens no pull request. Every other outcome names a step and a log, or is a stop
  with a report (4.5, 4.6).
- The lead reads four things before it reads the diff: the `fanout: PASSED` line in the body
  file, which names a tree; `ready` run on the builder's head, which says that the head is
  that tree, that every changed file is one the generated plan names and that each test the
  card names is in the change; its own gate and review, after which it opens the pull request
  (the owner's answer to question 2); and the Build, whose docs job runs the same scope check
  on the branch and whose suites run every test. A change outside the plan's files fails the
  docs job whatever the body says.

**What it gives the rows after it.**

| row | what it reads from this row |
| :--- | :--- |
| 659 (the pilot) | the card's grammar, `plan --write`, the generated plan, `files`, the oracle, `ready` and the report a builder ends on, the ledger's `--add`, and the guide as part E leaves it. Its builders start after part E is merged, when this row is **Done**: every generated plan sends its builder to the guide's steps 3 to 7, and those are stale until part E rewrites them (3.1). It can write its cards once part B is merged. It writes the cards of the four casts from `TIME`, and it changes the template with what the pilot teaches |
| 667 to 669 and 672 to 674 (the fan-out) | one card and one generated plan a part; the ledger's line a pull request |
| 677 (the close) | `python3 dev/varka_fanout.py ledger --summary`, which is G5's report (1.2, item 8) |
| 653 | its prediction 8, scored from the ledger's `conflicts` column |
| 655 (parts B and C) and 679 (the library) | a ledger line of kind `other` and of kind `library` (4.7) |

**What must be merged before it starts.**

- This plan, and the tag `varka-m8` (decision 11; the owner's word of 27 November 2026 that
  nothing of milestone 9 starts before milestone 8 closes).
- Row 653's parts A1 to F: the tool `dev/varka_rows.py` with `slug` and `family_slug`, the
  records under `sql/varka/rows/`, a coverage row as a `.row` file (part E) and a skip as a
  file under `sql/varka/matrix/skips.d/` (part F). The generated plan names those files.
- Row 654's parts a and b: `dev/varka_census.py` with `CURRENT = 9`, and milestone 9's census
  files under `sql/varka/plans/m9/`.
- Rows 642 and 646, by the row's own "first" column and by the owner's answer to question 5
  (27 November 2026): the row waits for both, and part A's premises 16 and 17 hold them as
  done. Recorded for a later re-ordering, and for nothing else: no sheet of this plan reads
  either row's layout (4.10).

**The goals it serves.** `m9/PLAN.md` 3.1 puts row 658 under G5.

- **G5**, quoted: "**G5. Delivery by cheaper models.** After the foundation, each fan-out task
  is one task plan from a template, with a one-command oracle; most fan-out pull requests are
  built by the cheaper models, each through the same review, and a ledger (row 658) records
  each one's builder and what its review found." The row builds the template, the oracle and
  the ledger that this sentence names. The item of 1.2 it moves is item 8: "**G5:** the share
  of fan-out pull requests the cheaper models built, and their review finding counts, reported
  from row 658's ledger; no threshold." A reader checks that the row moved it by `python3
  dev/varka_fanout.py ledger --summary`, which prints the line `built by a cheaper model: <k>
  of <n> fan-out pull requests that landed` and each builder's finding counts.
- **G1, G3 and G4** it serves only through the fan-out rows: it fuses nothing and moves no
  census cell.
- **G6** only through the ledger, from which the post reads "how much of it the cheaper models
  built". **G2 and G7** are not served.

**What is out of the row** is in 4.11, each excluded thing with the row that holds it.

## 2. What an agent must know

### 2.1 The idea

A fan-out task is small and has one shape: an arm in a family compiler, a coverage row, tests,
and a line of the census. What differs from task to task is short: which expression, over which
type, what Spark's code does, which merged kernel to copy, where the arm goes, which tests
it adds. That short part
is a **card**, which the coordinator writes. Everything else of the task's plan is the same
text every time, or follows from the tree by a rule: the file names that row 653's layout
gives a coverage row, the entry's line of the census and what it becomes, the commands and
what they print, the house rules. So the plan is generated from the card and a template, and
holds nothing to decide.

The same list of files serves three purposes. It is section 3 of the generated plan, "the
files, in order". It is the scope check, "no edit outside the listed files", which the oracle
and CI run. And, read against a merged pull request, it is the done-when's first clause.

### 2.2 Terms

- **Entry**: one line of the census, a function or an operator of Spark, by its registered
  name: `abs`, `year`, `make_timestamp_ntz`.
- **Type**: a census type, by its census name in `sql/varka/types.tsv`: `int`, `bigint`,
  `ntz`, `time`, `dt`, and from row 654 on `ltz`.
- **Unit**: what a fan-out task fuses, one of two kinds. `entry <name> <type>` is an entry over
  a type. `cast <from> <to>` is a cast pair of the cast table. A task holds one to four units;
  the milestone plan calls them "sibling entries a part".
- **Coverage row**: one SQL expression that the compiler fuses, with its family, its note and
  its domain. Since row 653's part E a new one is a **row file**,
  `sql/varka/rows/<family>/<slug>.row`. Its three **records** are the files beside it that the
  regeneration writes: `<slug>.json`, `<slug>.bytes.json` and `<slug>.audit.json`.
- **Slug** and **family's directory**: row 653's two naming rules, `slug(text)` and
  `family_slug(title)` of `dev/varka_rows.py`. `slug("abs(i)")` is `abs-i-d1122699` and
  `family_slug("Integer arithmetic")` is `integer-arithmetic`.
- **Card**: the hand-written input of one task,
  `sql/varka/plans/m9/fanout/<id>.txt`, one `key: value` a line (4.2).
- **Generated plan**: `sql/varka/plans/m9/fanout/<id>.md`, written by `plan --write` from the
  card and the template. It ends with the line `<!-- end of the generated plan -->`; below
  that line is the task's Outcome section, which the builder writes before the oracle runs
  (4.6).
- **Template**: `sql/varka/plans/TEMPLATE_FANOUT.md`, the fixed text with `{{name}}`
  placeholders.
- **Class of a file**: how the generated plan names a file: `edit` and `add` (the task's own
  files, from the card), `row`, `record`, `skip`, `curated`, `census`, `casts`, `host`, `plan`.
  A changed file with no class is **not named**.
- **Body file**: `target/varka-fanout/<id>/body.md` in the builder's worktree, the text of the
  pull request's description. Git ignores `target/`, so the file is no part of any tree.
- **Pilot**: here, a merged pull request that the generator is checked against. Its card
  holds `merged: <commit>`. (Row 659's pilot is the cheaper models' first tasks; this plan
  calls those "row 659's tasks".)
- **Oracle**: `python3 dev/varka_fanout.py oracle <card>`, the one command that says whether
  the task is done.
- **Ledger**: one line a pull request, one file a line, under
  `sql/varka/plans/m9/fanout/ledger/`.
- **Lead**, **coordinator**: the agent that runs the milestone for the owner: it writes
  cards, generates plans, reviews, merges plan-only pull requests and writes ledger lines.
- **Builder**: whoever builds a task from its generated plan: a cheaper model, or the lead. A
  builder's work ends at `READY`; it never pushes.
- **The heavy lock**: the file `~/proj/.varka-locks/heavy.lock`, or the one `VARKA_HEAVY_LOCK`
  names. The laptop runs one heavy JVM at a time, and every `build/sbt` run takes it.

### 2.3 The names this row reads, and the names it adds

Read, as they are on the plan's commit or as the merged plans of rows 653 and 654 give them:

| name | where | what it is |
| :--- | :--- | :--- |
| `slug(text)`, `family_slug(title)`, `--check` | `dev/varka_rows.py`, row 653 part A2 | the two naming rules, and the check that the committed records are the rows' |
| `sql/varka/rows/<family>/<slug>.row` and its three records | row 653 parts B and E | a coverage row as a file, and what the regeneration writes for it |
| `sql/varka/matrix/skips.d/varka-<row>-<slug>.tsv` | row 653 part F | a task's matrix skips; `<slug>` is `slug` of the first line's test name |
| `CURRENT = 9` | `dev/varka_census.py`, row 654 part a | the milestone whose census the tools write |
| `sql/varka/plans/m9/census_curated.txt`, `census.tsv`, `casts.tsv`, `casts_fused.tsv`, `casts_suites.tsv` | row 654 part a | the census's five files |
| `dev/varka_regen_generated.sh --all [--full]` | | every regeneration, in order; it takes the heavy lock itself |
| `dev/varka_push_check.py --no-jvm` | | the house checks a push owes that need no JVM: the pre-commit rules over the branch, the plan lint, the quote check, the census check and the size cap |
| `VarkaCoverageSuite`, its test "every documented expression compiles to a fused kernel" | catalyst tests, Scala | compiles every coverage row through the real compiler; fails on a row that does not fuse |
| `VarkaCoverageHarnessSuite`, `VarkaCoverageDifferentialSuite` | sql/core tests | every coverage row on Spark's row path and on Varka's |
| `dev/varka_msg_check.py pr --title T --body-file F` | | a pull request's title, body and size |
| `<Class>-jdk25-runner-epyc7763-results.txt` and `...-128bit-results.txt` | `sql/core/benchmarks/` | a baseline class's committed results on the runners, at 256 and 128 bits |

Added:

| name | part | what it is |
| :--- | :--- | :--- |
| `dev/varka_fanout.py` with `check [--branch NAME]`, `scope CARD [--against REV]`, `--self-test` and the option `--base REV` | A | the tool: cards, the scope check, what CI runs |
| `sql/varka/plans/m9/fanout/VARKA-621-abs-int.txt`, `VARKA-340-time-to-ntz.txt` | A | the two pilot cards |
| `sql/varka/plans/m9/results/VARKA-658-pilots.txt` | A | the two scope checks' outputs |
| `sql/varka/plans/TEMPLATE_FANOUT.md` | B | the template |
| `plan CARD [--write\|--check]`, `files CARD` | B | the generator, and the writer of the files a plan gives whole |
| `sql/varka/plans/m9/fanout/VARKA-621-abs-int.md`, `VARKA-340-time-to-ntz.md` | B | the two generated plans |
| `oracle CARD [--only STEPS] [--full-regen]`, `ready CARD --body-file FILE` | C | the oracle, and the check that the head is the tree the oracle passed on: a builder's last command |
| `sql/varka/plans/m9/results/VARKA-658-oracle.txt` | C | the oracle's runs on #1584's entry and their times |
| `ledger [--summary]`, `ledger --add KEY=VALUE...` | D | the ledger |
| `sql/varka/plans/m9/fanout/ledger/varka-1584.tsv` | D | the ledger's first line |
| `sql/varka/ADDING_AN_EXPRESSION.md`, rewritten | E | the guide |

## 3. The admission check

Each fact was checked on master `e0aee0a14b1`. `$GEN` is
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen`, `$IR` is
`$GEN/varka`, and `$TS` is
`sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka`. In the
tables a `\|` inside a command stands for `|`.

### 3.1 The guide is stale, and where

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 1 | The guide holds seven statements that today's tree contradicts | `grep -c "int32 lanes\|m4/PLAN.md\|emitValue\|dev/varka_gate.sh\|VarkaEmitterParityBenchmark\|VarkaThroughputBenchmark\|VARKA_COVERAGE_REGEN" sql/varka/ADDING_AN_EXPRESSION.md` | `7` |
| 2 | "Every Varka node computes over int32 lanes": the IR has six lane types | `grep -c "BYTE(Kind.INTEGRAL, 8), SHORT(Kind.INTEGRAL, 16), INT(Kind.INTEGRAL, 32)," $IR/VarkaVectorIR.java`; `grep -c "LONG(Kind.INTEGRAL, 64), FLOAT(Kind.FLOATING, 32), DOUBLE(Kind.FLOATING, 64);" $IR/VarkaVectorIR.java` | `1`, `1` |
| 3 | "Add the arm to `emitValue`" in `VarkaLoopEmitter`: the method is in `VarkaVectorWalk` | `grep -c emitValue $IR/VarkaLoopEmitter.java $IR/VarkaVectorWalk.java` | `0` for the first file, `46` for the second |
| 4 | `m4/PLAN.md` "the task row": the table of today's tasks is `m9/PLAN.md` | `grep -c "^\| 658 \| The fan-out task" sql/varka/plans/m9/PLAN.md` | `1` |
| 5 | Step 7 edits `VarkaCoverageSuite`'s `families` list and regenerates with `VARKA_COVERAGE_REGEN=true`, which also writes the docs table: after row 653 a new row is a file, the regeneration is `dev/varka_regen_generated.sh --all`, and no task writes the docs table | `VARKA-653.md` 4.5 and 4.6 | the plan's text |
| 6 | The guide says nothing of the census, the cast table, the emitted bytes, the width audit, the matrix skips or the size cap, which 10 of #1584's 13 files and 12 of #1434's 23 are | `grep -c -i "census\|casts.tsv\|emitted_bytes\|width_audit\|skips\|1000" sql/varka/ADDING_AN_EXPRESSION.md` | `0` |
| 7 | Its gate, `dev/varka_gate.sh`, is no longer the house's: the gate before a push is `dev/varka_ops/pre-pr.sh` | `grep -c "Gate before every push" dev/varka_ops/AGENTS.md` | `1` |
| 8 | Adding a node has a newer recipe than the guide's step 2 | `grep -c "^### Adding a node, after 489c" sql/varka/plans/m8/VARKA-489.md` | `1` |
| 9 | The stale guide names no path and no class that has left the tree, so a check of names alone would pass it | the tool's `name_errors` over the committed guide, on a scratch copy | no error |

Fact 9 is why the done-when's last clause is checked twice: by fact 1's seven statements gone
at part E, and by the names' check from then on.

### 3.2 No command says of one entry and one type whether the work is done

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 10 | No tool under `dev` names the fan-out, and the three files this row adds first do not exist | `git grep -l -i "fanout\|fan-out" -- dev \| wc -l`; `ls dev/varka_fanout.py dev/varka_rows.py sql/varka/plans/TEMPLATE_FANOUT.md 2>&1 \| grep -c "No such file"` | `0`; `3` |
| 11 | The house's gate before a push needs a recorded review and a gate's stamp, which a cheaper model has neither of | `grep -c "no pre-pr.sh pass on this head" dev/varka_ops/open-pr.sh`; `grep -c "body records no /code-review high pass" dev/varka_ops/open-pr.sh` | `1`; `1` |
| 12 | The emit tool's default columns hold no long, time or timestamp column, so it cannot say of a coverage row whether it fuses without the row's own columns | `grep -c '"d:date,d2:date,i:int,sh:short,by:byte,ymm:ymm,ymy:ymy,ym:ym";' sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaEmitDump.java` | `1` |
| 13 | One test compiles every coverage row over the coverage table's own columns and fails on a row that does not fuse | `grep -c 'test("every documented expression compiles to a fused kernel")' $TS/VarkaCoverageSuite.scala` | `1` |
| 14 | A results file's section holds a line `varka on` whose last cell is its rate over the row path's | `grep -c "^varka on .* [0-9.]*X$" sql/core/benchmarks/VarkaTimestampNtzFieldsBaselineBenchmark-jdk25-runner-epyc7763-results.txt` | `14` |
| 15 | `.txt`, `.tsv` and `.md` files need no license header | `grep -c -x '\.\*\\\.txt\|\.\*\\\.tsv\|\.\*md' dev/.rat-excludes` | `3` |
| 16 | The size check does not count a file whose first five lines say "Generated by" | `grep -c 'a markdown or data file in its first five' dev/varka_msg_check.py` | `1` |
| 17 | No branch of this row exists | `git branch -a --list '*varka-658*'` | nothing |

### 3.3 The two pilots

A pilot must be a merged pull request that fused an entry over a type, with its review in its
description. Two are chosen, a small one and the one `m9/PLAN.md` 2.6 already counts.

**Pilot 1: #1584, `[VARKA-621] Fuse abs over an int`,** merged on 21 November 2026 as
`43cf0d94acc`. One unit, `entry abs int`. `git show --stat --format= 43cf0d94acc | tail -1`
prints ` 13 files changed, 159 insertions(+), 53 deletions(-)`. Its description records
"/code-review high (20 November 2026): 6 findings, fixed: 4, kept: 2" and "Generated-by:
Claude Code (Claude Opus 5.5)". It is the entry the oracle runs on (part C), because one arm
of four lines is its kernel: fact 18. `git show --name-only --format= 43cf0d94acc` prints:

    docs/sql-varka.md
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaNodeCompiler.java
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaVectorIR.java
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaVectorWalk.java
    sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCoverageSuite.scala
    sql/varka/coverage.json
    sql/varka/emitted_bytes.json
    sql/varka/matrix/skips.tsv
    sql/varka/plans/m8/PLAN.md
    sql/varka/plans/m8/VARKA-621.md
    sql/varka/plans/m8/census.tsv
    sql/varka/plans/m8/census_curated.txt
    sql/varka/width_audit.json

**Pilot 2: #1434, `[VARKA-340] Fuse make_timestamp_ntz and the cast of a TIME to
TIMESTAMP_NTZ`,** merged on 10 November 2026 as `39a404b73ec`: the pull request whose 23 files
`m9/PLAN.md` 2.6 counts. Three units: `entry make_timestamp_ntz date`, `entry
make_timestamp_ntz time` and `cast time ntz`. `git show --stat --format= 39a404b73ec | tail
-1` prints ` 23 files changed, 607 insertions(+), 66 deletions(-)`. It holds what pilot 1 does
not: a cast, three coverage rows, three skips, a benchmark class and an edit of
`VarkaExpressionCompilerSuite.scala`. `git show --name-only --format= 39a404b73ec` prints:

    dev/varka_census.py
    docs/sql-varka.md
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaNodeCompiler.java
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaTimeCompiler.java
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaFamily.java
    sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCatalystGrammar.java
    sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTimestampNtzCastSuite.java
    sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/VarkaExpressionCompilerSuite.scala
    sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCoverageSuite.scala
    sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaSqlResolve.scala
    sql/core/src/test/java/org/apache/spark/sql/execution/VarkaDomains.java
    sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaTimestampNtzFieldsBaselineBenchmark.java
    sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaTimestampNtzSuite.scala
    sql/varka/coverage.json
    sql/varka/emitted_bytes.json
    sql/varka/matrix/skips.tsv
    sql/varka/plans/m8/PLAN.md
    sql/varka/plans/m8/VARKA-340.md
    sql/varka/plans/m8/casts.tsv
    sql/varka/plans/m8/casts_fused.tsv
    sql/varka/plans/m8/census.tsv
    sql/varka/plans/m8/census_curated.txt
    sql/varka/width_audit.json

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 18 | Pilot 1's kernel is one arm of four lines, still in the tree | `grep -c "case Abs a when isInt(a) ->" $GEN/VarkaNodeCompiler.java`; `sed -n '/case Abs a when isInt(a) ->/,/^      });$/p' $GEN/VarkaNodeCompiler.java \| wc -l` | `1`; `4` |
| 19 | One coverage row reads that arm, so deleting it unfuses one row | `python3 -c "import json;d=json.load(open('sql/varka/coverage.json'));print([e['sql'] for e in d['expressions'] if 'Abs:int' in e['operands']])"` | `['abs(i)']` |
| 20 | Before #1584 that row declined: the row's probe "fails the coverage row with "no entry fused"" | `git show 43cf0d94acc -- sql/varka/plans/m8/PLAN.md \| grep -c 'fails the coverage row with "no entry fused"'` | `1` |
| 21 | The census counts both pilots' units as fused today | `grep -c "^abs\|.*\|byte,short,int,bigint," sql/varka/plans/m8/census_curated.txt`; `grep -c "^make_timestamp_ntz\|.*\|date,time\|" sql/varka/plans/m8/census_curated.txt` (each `\|` here is a bar typed as it is, with no backslash: a literal bar to `grep`); `grep -c -P "^time\tntz\tyes\tyes\tyes\t.*\tLEGACY,ANSI,TRY\t" sql/varka/plans/m8/casts.tsv` | `1`; `1`; `1` |
| 22 | Pilot 2's benchmark section is committed at both widths, and still holds the baseline's numbers | `grep -c -x "TIMESTAMP_NTZ make_timestamp_ntz(d, tm), no nulls" sql/core/benchmarks/VarkaTimestampNtzFieldsBaselineBenchmark-jdk25-runner-epyc7763-results.txt sql/core/benchmarks/VarkaTimestampNtzFieldsBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt` | `1` for each file |
| 23 | The pilots' siblings are merged commits | `git show -s --format=%s 230773d872d 77a323f0da3` | `[VARKA-104] Fuse BIGINT +, -, *, negation and abs in LEGACY, ANSI and TRY (#1484)`; `[VARKA-338] Read TIMESTAMP_NTZ in Varka's kernels and cast it with DATE (#1031)` |

### 3.4 What ran on a scratch copy, and what could not

On a scratch tree that held the tool as part D leaves it, row 653's two naming functions as
`VARKA-653.md` 5.2 gives them, a file with the line `CURRENT = 9`, copies of milestone 8's
census files under `m9`, the template, the two cards and the new guide, with git read from the
reading worktree:

| # | what ran | result |
| ---: | :--- | :--- |
| 24 | `--self-test` of the tool as each of parts A, B, C and D leaves it | `varka_fanout self-test: ok (1 groups)`, `(3 groups)`, `(4 groups)`, `(5 groups)` |
| 25 | `scope <card> --against <commit>` for the two pilots | 13 files and 23 files, `0 not named by the plan`: the outputs of 5.1's commands 3 and 4 |
| 26 | `plan` for the two pilots | plans of 12 and of 30 files, 463 and 505 lines; `plan --check` after `plan --write` prints `is current`, and prints `is behind its card or the tree` after one word of the card changes |
| 27 | `plan`, then `files` twice, on a card of two unfused entries (`factorial` and `year` over `int`) | the two lines of the curated census changed once; the second run changes nothing; the skip file's fields are parted by tabs |
| 28 | `check` | `fanout: check: 0 error(s)` with the new guide and the template; with the committed guide too (fact 9) |
| 29 | `ledger --add` of #1584's line, then `ledger` and `ledger --summary` | the outputs of 5.4's commands 3 and 4 |
| 30 | in a throwaway git repository: `scope` with one file the plan names and one it does not; the tree of the worktree against `git rev-parse 'HEAD^{tree}'` after `git add -A` and a commit; `ready` on a clean and on a dirty worktree; `check --branch` | `1 not named by the plan`; the two trees equal; `fanout: READY`, then `the worktree has uncommitted changes`; the changed file named |
| 31 | the benchmark step's reading of pilot 2's section in the committed results file | `{'spark row path (varka off)': 1.0, 'varka on': 1.0}`; `owed` when the branch does not change the file |
| 32 | `ruff check` and `ruff format --check` (ruff 0.14.8, the repository's `pyproject.toml`) on the tool as each of parts A, B, C and D leaves it, as part D leaves it with parts B and C not merged, and as the first half of part B's cut leaves it | `All checks passed!` and `1 file already formatted`, six times |
| 33 | the tests of pilot 2's card sought in the lines `39a404b73ec` adds: three JUnit methods of `VarkaTimestampNtzCastSuite.java` and three ScalaTest sentences of `VarkaTimestampNtzSuite.scala`, each written over two source lines | all six found; with one name changed on a copy of the card, `scope --against` prints `the test ... is not in the lines the change adds to ...` and exits 1 |
| 34 | how sbt's JUnit reports name a test, read from reports that earlier runs left on the laptop (`sql/catalyst/target/test-reports/TEST-<class>.xml` of two other worktrees, 12 and 22 November 2026) | a ScalaTest test by its sentence (`name="every documented expression compiles to a fused kernel"`); a JUnit test by its method (`name="makeTimestampNtzAtTheEdges()"`), not by its `@DisplayName`; a test that a JUnit factory makes by `<factory>():<sentence>` (`name="ansiForm():Months and year-month intervals: abs(ym) raises under ANSI only where the row engine does"` in `VarkaCoverageHarnessSuite`; `name="everyCase():time_trunc at every level agrees with the row engine over every second"` in `VarkaTimeHarnessSuite`, a report of 25 November 2026); a case of a JUnit `@ParameterizedTest` by `<method>(<parameter types>):<n>` (`name="slice(String, VarkaEmitterNarrowArithSuite$Mode, VarkaEmitterNarrowArithSuite$Arm):1"` in `VarkaEmitterNarrowArithSuite`, a report of 23 November 2026). A report writes `<` and `>` in a name as `&lt;` and `&gt;` |
| 35 | every name the tool's `HARNESS_TESTS` gives the rows of a coverage table, held to a real report: the 868 rows of one worktree's `sql/varka/coverage.json` and the `TEST-org.apache.spark.sql.execution.VarkaCoverageHarnessSuite.xml` its run of 27 November 2026 wrote | 2837 names made and 2837 factory-made test cases in the report, none on one side alone: `everyRow()` and `ansiForm()` 868 each, `domains()` 138, `partitions()`, `nullTestConditions()` and `booleanOutputs()` 321 each |
| 36 | in a throwaway repository with two throwaway remotes, `origin` and `fork`, `ready` on a branch made by the template's set-up line: with no commit of its own; with a file the plan does not name and a test missing; clean; with an upstream set; after a push to `fork`; after one more commit; on a detached head | the branch has no upstream; `the branch is on refs/remotes/origin/HEAD, refs/remotes/origin/master`; `dev/x.txt is changed and the plan does not name it` and the test's line; `fanout: READY`; `the branch has an upstream, refs/remotes/origin/master`; `the branch is on refs/remotes/fork/varka-9a-abs-int`; that line again, exit 1: a commit under the head was pushed; and again on the detached head, which is that branch's |
| 39 | there, the fix round (4.6, condition 8), on three branches. One the builder pushed: then two more commits; a `findings.md` with no `pushed:` line; one whose line names the unpushed head; one whose line names master's commit. One the lead pushed with `git push -q fork HEAD:<branch>`: the lead's line and no new commit; one commit `Address the review of <id>`; that commit pushed; one more commit after it. One whose pushed head is a merge of a later master, as the gate makes it: the fix commit; the same with `findings.md` deleted. One never pushed: made with tracking; after `git branch --unset-upstream`; on a detached head | `the branch is on refs/remotes/fork/<branch>` and exit 1 for the first four, the last two with `findings.md says the lead pushed <commit>, and that is no pushed commit of this branch` too; the second branch has no upstream after the push, and prints `the branch is on ...`, `fanout: READY`, `the branch is on ...`, `the branch is on ...`; the third prints `fanout: READY`, then `the branch is on ...`; the fourth prints `the branch has an upstream, refs/remotes/origin/master`, `fanout: READY`, `fanout: READY` |
| 37 | there, `oracle --only card` on a task's plan: as `plan --write` leaves it; with two fields written; with all below the end marker deleted; with the heading reworded; with the three fields written | three lines, `the field ... is empty`; one; ``no line `## 9. Outcome` below the end marker``, both times; `card passed` |
| 38 | there, the `fused` and the `suites` steps with the tool's `run` replaced by a function that exits 0 and writes, or does not write, the reports: eight cases, no JVM | `fused` passes with its test in a report of this run; it fails with no report of this run though an older one stands, with a report of no test, and with a report of another test. `suites` passes with every suite's report and every test of the card; it fails with a test renamed, with a suite's report missing, and with the row's harness test missing |
| 39 | the example body of the template's section 8, with a tree, seconds, a tool and a model put in, through `python3 dev/varka_msg_check.py pr --title "[VARKA-621] Fuse abs over an int" --body-file <file>` | exit 0, no finding |

**What I could not check.**

- No JVM ran. The oracle's steps `fused`, `regen` and `suites` start `build/sbt` and
  `dev/varka_regen_generated.sh`; 4.5 names the house command each follows, and part C's runs
  1 and 2 are their check, with 5.3's repair list for a command line that fails. That deleting
  the arm of fact 18 makes `VarkaCoverageSuite` fail rests on facts 19 and 20, not on a run.
  That `build/sbt -Phive` resolves on the laptop with no network, and that a forced
  regeneration of an unchanged tree writes nothing, rest on no run either: 3.5 gives the
  commands that settle each on master today. That sbt exits 0 when `-z` matches no test is
  taken as true on the lead's word (27 November 2026); which report sbt then leaves is not
  known, and the `fused` step fails either way (fact 38). 3.5's command 4 shows both.
- The reports of fact 34 are from runs before row 653; that the coverage harness still names
  a row's test `<factory>():<family>: <sql> ...` after row 653's part E rests on that plan.
  Part A's premise 18 and part C's premise 14 read the sentences from the tree at the build.
- The oracle's time. Section 7 registers a prediction; part C measures it.
- The code of rows 653 and 654 does not exist yet. What this plan reads of it is 2.3's first
  table, each name as its merged plan gives it; every part's B0 holds them at the build.
- `python3 -m doctest dev/sparktestsupport/modules.py` with part A's edit: the edit adds one
  regex to a tuple and no doctest; part A's command 6 is its check.

### 3.5 What a JVM run settles, on master today

Four points of 4.5 and 5.3 rest on no run. The lead can run a JVM; each command below runs
on master as it is (no part of this row is needed), in a clean worktree of master on the
laptop, with `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1`
and `LOCK="${VARKA_HEAVY_LOCK:-$HOME/proj/.varka-locks/heavy.lock}"`. None changes this plan's
design; each only says which line of 5.3's repair table part C will meet.

| # | the open point | the command | it is settled when |
| ---: | :--- | :--- | :--- |
| 1 | the `fused` step's command runs the one test, and sbt takes `-z` with a quoted sentence | `flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaCoverageSuite -- -z "every documented expression compiles to a fused kernel"'` | it prints `Tests: succeeded 1, failed 0` and exits 0, and `grep -c 'name="every documented expression compiles to a fused kernel"' sql/catalyst/target/test-reports/TEST-org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaCoverageSuite.xml` prints `1` on a file that `ls -l` dates after the command's start |
| 2 | `-Phive` builds on the laptop, both modules run in one sbt call, and the reports name the tests as fact 34 says | `flock "$LOCK" build/sbt -Phive 'catalyst/testOnly *VarkaCoverageSuite' 'sql/testOnly *VarkaCoverageHarnessSuite *VarkaCoverageDifferentialSuite'`, then `grep -c 'name="domains():Integer arithmetic: abs(i) is answered inside its domain' sql/core/target/test-reports/TEST-org.apache.spark.sql.execution.VarkaCoverageHarnessSuite.xml` | sbt exits 0 and the grep prints `1`. If sbt cannot resolve under `-Phive`, the oracle's `suites` command drops the flag (5.3) |
| 3 | a forced regeneration of an unchanged tree writes nothing on the JDK the audit names | `dev/varka_regen_generated.sh --all --full; git status --short` | the script ends with `varka regen: done; review git diff and commit the regenerated files` and `git status --short` prints nothing. A line for `sql/varka/width_audit.json` alone, whose `git diff` is the `host` block, is the JDK case of 4.5 |
| 4 | sbt exits 0 when `-z` matches no test, and what report it then leaves | `date +%T; flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaCoverageSuite -- -z "no test of this suite has this name"'; echo "exit $?"`, then `ls -l --time-style=+%T sql/catalyst/target/test-reports/TEST-org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaCoverageSuite.xml` and `grep -c "<testcase" ` of that file | it prints `No tests were executed` and `exit 0`. Then either the file is older than the command's start or is not there (part C's run 2b prints `VarkaCoverageSuite wrote no report in this run`), or it is newer and the grep prints `0` (run 2b prints `the test ... did not run and pass in VarkaCoverageSuite`). If it prints another exit status, the report's check is still right and costs nothing |

Command 3 is the long one: it holds the lock for the width audit's whole run. Its seconds are
also a first reading of section 7's prediction 1.

## 4. The design

### 4.1 One tool, built in four parts

Everything is `dev/varka_fanout.py`, Python with the standard library alone, and three kinds of
file under `sql/varka/plans`: the template, and under the milestone's `fanout` folder the
cards with their generated plans and the ledger. Appendix A holds the tool whole, as each part
leaves it; appendix B the guide; appendix C the template; appendix D the two cards.

| command | who runs it | reads | writes | exit status |
| :--- | :--- | :--- | :--- | :--- |
| `plan CARD` | the coordinator | the card, the template, the census at the branch's merge base, the sibling's commit | the plan on standard output; with `--write` the file `<id>.md` beside the card, keeping the text below its end marker; with `--check` nothing | 0; with `--check` 1 when the committed plan is behind; 2 on a card that does not parse |
| `files CARD` | the builder | the card, the curated census | the row files, the skip file, the curated census's lines | 0; 2 on a pilot's card or a missing line |
| `scope CARD` | the builder, the oracle, CI | the card, the changed files and their added lines | one line a changed file with its class | 0; 1 when a file is not named or a test of the card is not in the change |
| `oracle CARD` | the builder | the card, the tree | logs and a stamp under `target/varka-fanout/<id>/`; the regeneration's records in the worktree | 0 passed; 1 a step failed; 2 usage |
| `ready CARD --body-file F` | the builder as its last command; the lead on the builder's head, before its gate | the card, the body file, the head | nothing | 0 ready; 1 not |
| `ledger` | the lead | the ledger's files | with `--add` one file | 0; 1 when a line is malformed; 2 on `--add` with a wrong value |
| `check` | CI's docs job, anyone | the guide, the template, the cards, the ledger, the rule files | nothing | 0; 1 with one line an error |

Every command also takes `--base REV`, the commit a branch's changes are counted from. Without
it the base is `VARKA_DIFF_BASE` where CI sets it, else the merge base of `HEAD` and
`origin/master`.

### 4.2 The card

A card is `sql/varka/plans/m9/fanout/<id>.txt`. It is printable ASCII, one `key: value` a
line (the key, a colon, one space, the value to the end of the line); a line that starts with
`#` is a comment; empty lines are skipped. That is the grammar of row 653's row files, so a
builder learns one. The keys:

| key | how often | value |
| :--- | :--- | :--- |
| `id` | once | `VARKA-<row>-<words>`: lower-case letters, digits and dashes after the number, at most 60 characters; the file's name without `.txt` |
| `row` | once | the task row's number; the number in `id` |
| `title` | once | the pull request's goal; with `[VARKA-<row>] ` before it at most 80 characters; no `"`, backtick, `$` or backslash, since the plan puts it on a shell's command line |
| `branch` | once | the branch the builder works on, `varka-<row><letter>-<words>` |
| `merged` | at most once | a pilot's alone: the commit that merged the entry. Such a card's units may be fused already, `files` refuses it, and `check` holds the commit to the plan |
| `unit` | one to four | `entry <name> <type>` or `cast <from> <to>`, the names as the census has them |
| `spark` | once or more | `<Class>.<method>: <what it defines>`: Spark's code that defines the answer |
| `sibling` | once | `<commit> \| <a test file of that commit> \| <what to copy from it>`: the merged kernel of the nearest type, the one test file whose shape the task's tests copy (a path with `/src/test/` that the commit changed), and one sentence that names the arm |
| `lowering` | once | one sentence: the nodes the arm builds, the guard, what declines |
| `edit` | any | `<path> \| where: <place>; when: <condition>; copy: <code to copy>; result: <what it does>`: an existing file the task changes, and the four parts of what goes in (below) |
| `add` | any | the same form: a file the task adds |
| `test` | once or more | `<test file> \| <name>` or `coverage \| <name>`: a test the task adds, by the name sbt's report gives it (4.9, the two tables of names) |
| `no-own-test` | at most once | the reason why a card that changes main source names no test in a test file of its own (4.9) |
| `coverage` | once or more | a coverage row's SQL. The lines `coverage-family` (once), `coverage-form` (once: `projection` or `predicate`), and where they apply `coverage-note`, `coverage-domain`, `coverage-executable`, `coverage-verified` and `coverage-ansi-declined` follow it and belong to it; they are the keys of a row file (`VARKA-653.md` 4.6) |
| `skip` | any | a matrix configuration, as `checkIntOverflow=false`. The lines `skip-suite`, `skip-test`, `skip-verdict` and `skip-reason` follow it, each once: the other four fields of a skip line |
| `decline` | any | an expression that declines today and must still decline. The lines `decline-reason` and `decline-test` follow it, each once |
| `suite` | any | `catalyst \| <Suite>` or `sql \| <Suite>`: a suite the oracle runs beside the three it always runs |
| `bench` | once or more | `<BenchmarkClass> \| <section title>`, or `none \| <why the task has no section>` |
| `not-here` | any | one more thing this task must not do |

A value's fields are parted by ` | `, a space, a bar and a space, and only the first such bar
parts (the first two in a `sibling` line): a `what goes in` text may hold bars.

**What an `edit` or an `add` line holds.** Four parts, in this order, each after its word and
a colon, parted by `; `. The parser refuses a line without all four. The two pilot cards are
the examples.

1. `where`: the place, by class member: the method, the `switch` and the arm the new arm
   stands after, the record whose javadoc changes, the table. Never a line number.
2. `when`: the condition under which the new code applies, as the code writes it (`case Abs a
   when isInt(a)`), or `always` for a text.
3. `copy`: the existing code to model the change on, by file and member: an arm of the same
   `switch`, a test of the same suite, a line of the same table; or `nothing` for a comment.
4. `result`: what the new code builds, returns, throws or declines, with the names of the
   nodes, modes and helpers it uses.

**What is no fan-out task.** A task whose coverage row is built in code (`built`) or is a math
form (`math`) cannot be a card: such a row stays in the Scala table of `VarkaCoverageSuite`
(`VARKA-653.md` 4.6), and a card's `coverage` lines become `.row` files alone. The coordinator
gives such a task out by hand; the template's section 7 tells a builder to stop if it meets
one. A coverage row's SQL, a test's name and a reason
stand alone on their lines, so they may hold any printable character.

What the coordinator decides in a card, and nowhere else: the units; Spark's methods; the
sibling; the lowering; the task's own files and what goes in each; the coverage rows with
their notes and domains; the skips; the declines; the benchmark section. **Where an arm goes**
is part of an `edit` line's text ("the arm of `Abs` over an int, after the arm of the int
negation"). Row 653 left this to this row (`VARKA-653.md` 4.11: "the template names the arm's
place, or the first task of a family lays out every sibling's arm"). The rule is: cards whose
tasks are in flight together put their arms after different existing arms; where a family has
fewer arms than siblings in flight, the coordinator gives the tasks out one after the other.

### 4.3 The template and the generated plan

`sql/varka/plans/TEMPLATE_FANOUT.md` (appendix C) is the plan's fixed text, in the eight items
of `m9/PLAN.md` 2.7 with a section 0 before them: the four pages to read, in order, with what
to take from each, the words the plan uses, the worktree and two checks before any edit; what
to fuse and what defines the answer; the sibling; the files in order; the commands in order,
each with what it prints; the declines to keep; the house rules, quoted; what not to do and
when to stop; how to hand the task over at `READY`. The plan restates what a builder needs and
sends it to four pages by name (the docs page's glossary, the guide's steps 3 to 7, two
sections of the rule files); it has no other pointer.

A placeholder is `{{name}}`. The generator has 24 values, and the template must use exactly
those 24: a placeholder with no value, or a value no placeholder uses, is an error (exit 2,
naming the difference), so the template and the generator cannot drift apart.

| value | what it is |
| :--- | :--- |
| `id`, `row`, `title`, `branch`, `lowering` | the card's lines |
| `card`, `plan` | the card's path, and the plan's: the same with `.md` |
| `milestone` | `CURRENT` of `dev/varka_census.py` |
| `merged` | the pilot's commit in backticks, or `no` |
| `units` | one table line a unit: the pair, its kind, and where the census holds it |
| `spark`, `not_here`, `declines`, `bench` | bullet lists from the card's lines; `None.` where the card has none |
| `sibling`, `sibling_files` | the text after the sibling's second bar; and the commit's `<hash> <subject>` with a table of the files it changed, each marked by its path: main source, test, benchmark, generated, or plan, note or tool; the card's test file is marked as the one to copy the tests' shape from |
| `tests` | one bullet a `test` line: the name and its file, or that `VarkaCoverageHarnessSuite` makes it from the row; and the `no-own-test` claim with its reason |
| `own_suites` | one `build/sbt` command line a module for the card's own suites, the `sql` one under `-Phive`; a comment line where the card has none |
| `files` | one table line a file the plan names, numbered, with its class and what goes in |
| `row_files` | each row file's path and its text, indented by four |
| `curated` | for each entry unit, the entry's line of the curated census at the merge base, and the line it becomes; `None: no entry's line changes.` otherwise |
| `skips` | the skip file's path and its lines, `<TAB>` standing for a tab; `None: the card names no skip.` otherwise |
| `suites_catalyst`, `suites_sql` | `*VarkaCoverageSuite`, and `*VarkaCoverageHarnessSuite *VarkaCoverageDifferentialSuite`, each followed by the card's suites of that module |

The plan's first line is `<!-- Generated by dev/varka_fanout.py plan from <card>, template
<12 hex digits of the template's SHA-256>. Do not edit above the end marker. -->`. It has two
uses: the size check reads "Generated by" in a file's first five lines and counts nothing of
it (fact 16), so a plan of 280 lines costs a pull request no line; and a reader sees which
template wrote it. The plan ends with `<!-- end of the generated plan -->`. `plan --write`
keeps what stands below that line; on a new plan it writes below it the heading `## 9.
Outcome`, a sentence that starts "To be written by the builder before the oracle runs", and
three empty fields, one a line: `- Built:`, `- Plan gaps:` and `- Oracle failures:`. The
oracle's `card` step fails while the heading or a field is missing or a field is empty (4.6).

**The census line.** For a unit `entry <name> <type>` the generator finds the entry's line in
the curated census of the branch's merge base: the line whose first field, before the first
`|`, is the name, with seven fields (the `[registered]` section) or nine (`[implicit]`). The
new line has the type added last among the fused types (the fourth field of seven, the sixth
of nine) and the task row's number added last among the rows (two fields later) unless it is
there. A type marked `~` or `^` there counts as present. Two units of one entry give one old
line and one new line. An entry that is no line of the file, or a type that is fused already
on a card with no `merged` line, is an error (exit 2). The merge base, not the worktree: the
plan describes what the task starts from, so `plan --check` gives the same answer before and
after the builder's edit, and says `is behind` once a merge of master has changed the entry's
line.

**What `files` writes.** Each row file with the keys `sql`, `family`, `form`, then `note`,
`executable`, `domain`, `verified` and `ansi-declined` where the card has them, in that order;
the skip file, one line a skip, its five fields parted by tabs; and the curated census with
each old line replaced by its new one. It is repeatable: an edit whose new line is already in
the file is skipped. It is the builder's first edit, so no builder types a file name with a
hash in it or a tab.

### 4.4 The files a plan names, and the scope check

| class | the files | from |
| :--- | :--- | :--- |
| `edit`, `add` | the card's `edit` and `add` paths | the card |
| `row` | `sql/varka/rows/<family_slug(family)>/<slug(sql)>.row`, one a coverage row | the card and row 653's rules |
| `record` | the same path with `.json`, `.bytes.json` and `.audit.json`; and any `sql/varka/rows/_admitted/<Class>.json`, which the regeneration adds when a class is first admitted | the regeneration |
| `skip` | `sql/varka/matrix/skips.d/varka-<row>-<slug(first skip's test name)>.tsv`, when the card has a skip | the card and row 653's rule |
| `curated` | `sql/varka/plans/m9/census_curated.txt`, when the card has an entry unit | row 654 |
| `census` | `sql/varka/plans/m9/census.tsv` | the regeneration |
| `casts` | `sql/varka/plans/m9/casts.tsv`, `casts_fused.tsv`, `casts_suites.tsv`, when the card has a cast unit | the regeneration |
| `host` | `sql/varka/rows/_audit.json`, the head of the width audit's records, which holds the host (`VARKA-653.md` 4.2) | the regeneration, and only on a JDK newer than the one the file names (4.5) |
| `plan` | `sql/varka/plans/m9/fanout/<id>.md`: its Outcome section | the builder |

`scope CARD` lists the files the branch changes: `git diff --name-only --no-renames <base>`,
which holds committed and uncommitted changes, and the untracked files that git does not
ignore. Each is printed with its class, or `NOT NAMED`. Then each `test` line of the card whose file
is a path is sought in the lines the change adds to that file (4.9); a test that is not there
prints `fanout: scope: the test `<name>` is not in the lines the change adds to <file>`. The
last line is `fanout: scope: <n> file(s), <k> not named by the plan`, and the exit status is
1 when `k` is not 0 or a test is missing.

**Against a merged commit.** `scope CARD --against <commit>` lists the commit's files instead.
The two pilots merged before rows 653, 654 and 658 moved the shared files, so a path of theirs
is first taken through `MOVED`, the table of what each such path has become; the class it
gives must be one the plan names a file of. A path that `MOVED` does not hold must be a path
of the plan itself.

| a path of then | the class of now | moved by |
| :--- | :--- | :--- |
| `docs/sql-varka.md` | `lead`: no task writes it | row 653 part D: the render commit writes the row tables |
| `sql/varka/coverage.json`, `emitted_bytes.json`, `width_audit.json` | `record` | row 653 parts B and C |
| `.../varka/VarkaCoverageSuite.scala` | `row` | row 653 part E: a new row is a row file |
| `sql/varka/matrix/skips.tsv` | `skip` | row 653 part F |
| `sql/varka/plans/m<n>/census_curated.txt` | `curated` | row 654 part a |
| `sql/varka/plans/m<n>/census.tsv` | `census` | row 654 part a |
| `sql/varka/plans/m<n>/casts.tsv`, `casts_fused.tsv`, `casts_suites.tsv` | `casts` | row 654 part a |
| `sql/varka/plans/m<n>/PLAN.md` | `lead`: no task writes it | the lead keeps a row's status |
| `sql/varka/plans/m<n>/VARKA-<n>.md` | `plan` | this row: the task's plan is generated |
| `.../codegen/VarkaExpressionCompilerSuite.scala` | `suite`: the plan names a test file of its own, an `edit` or `add` path with `/src/test/` in it | this row: a task's compile tests are in its own suite (4.9) |

So the done-when's first clause reads: every file the pull request touched is a file the plan
names, or was a shared file whose content the plan now names in the file that replaced it, or
is one of the two files that no task writes any more, which the plan names in its section 7.
The outputs for the two pilots are 5.1's commands 3 and 4. The owner confirmed this reading
(question 1, 27 November 2026).

**What the two pilots prove, and what they do not.** A card's `edit` and `add` paths are the
coordinator's, and here they were copied from the merged diffs: 3 of pilot 1's 13 files and
11 of pilot 2's 23. So "0 not named" proves the rules for the other 10 and 12 files (the
classes `row`, `record`, `skip`, `curated`, `census`, `casts` and `plan`, and `MOVED`), and
says nothing on whether a coordinator would have listed the 3 and the 11 before the work. That
is scored on the first task built from a card written ahead of its diff: section 7's
prediction 6 counts the files row 659's first builder had to change that its card did not
list.

### 4.5 The oracle

`python3 dev/varka_fanout.py oracle CARD` runs seven steps in this order and stops at the
first that fails.

| step | what it runs | it fails when | JVM |
| :--- | :--- | :--- | :--- |
| `card` | renders the plan again and compares it with the committed plan above its end marker; reads the Outcome below it | the plan is behind its card or the tree; or a task's Outcome has no heading, lacks a field or has an empty field (4.6; a pilot's card of a merged entry has none) | no |
| `scope` | 4.4's check | a changed file is not named by the plan; a test of the card is not in the change | no |
| `fused` | `flock <lock> build/sbt 'catalyst/testOnly *VarkaCoverageSuite -- -z "every documented expression compiles to a fused kernel"'`; then that test is sought in the report sbt wrote | the JDK is not a JDK 25; sbt exits non-zero: a coverage row does not fuse, or the code does not compile; or sbt exits 0 and no report of this run holds that test as passed | yes |
| `regen` | `dev/varka_regen_generated.sh --all`, with `--full` under `--full-regen`; then 4.4's check again | the JDK is not a JDK 25; the script fails; it changed a file the plan does not name (another row's bytes moved, or a file is stale that is not the task's); or it changed the audit's head beyond its `host` block | yes |
| `suites` | `flock <lock> build/sbt -Phive 'catalyst/testOnly *VarkaCoverageSuite <the card's>' 'sql/testOnly *VarkaCoverageHarnessSuite *VarkaCoverageDifferentialSuite <the card's>'`; then each `test` of the card is sought in the reports sbt wrote | the JDK is not a JDK 25; sbt exits non-zero; a suite of the command has no report of this run, or one in which no test passed; a test of the card is in no report of this run as passed | yes |
| `checks` | `python3 dev/varka_rows.py --check`; `python3 dev/varka_push_check.py --no-jvm`; and for each unit the census: the type among the entry's `fused_today` in `census.tsv`, or the pair's `fused_today` not empty in `casts.tsv` | a record is missing or left over; a house check or the size cap fails; the census does not count a unit as fused | no |
| `bench` | for each `bench` line, the section in the class's two runner-named results files | a results file or the section is missing; or the branch changes the file and the section's `varka on` line reads under `1.0X` | no |

- **What it prints.** For each step `fanout: <id> step <name>: passed in <n> s`, or `FAILED`
  with one line a failure before it. Then one of three last lines: `fanout: PASSED <id> tree
  <tree> in <n> s`; `fanout: FAILED <id> at <step> in <n> s (logs under
  target/varka-fanout/<id>)`; or, under `--only`, `fanout: <id>: <steps> passed; only a run
  of every step says PASSED`.
- **Exit status.** 0 on `PASSED` and on an `--only` run whose steps passed; 1 on `FAILED`; 2 on
  a usage error or a card that does not parse.
- **The tree.** `<tree>` is the tree that `git add -A` would commit in the worktree as the
  oracle leaves it, computed through an index file of its own under `target/`, so the real
  index is not touched. The builder then commits everything, and `git rev-parse 'HEAD^{tree}'`
  prints the same 40 hex digits. The line is also written to
  `target/varka-fanout/<id>/stamp`.
- **The lock.** The steps that start `build/sbt` run it under `flock` on the heavy lock, so a
  builder's oracle waits its turn behind the lead's runs (`m9/PLAN.md` 2.7): a bare `build/sbt`
  takes no lock. The regeneration script takes the same lock itself, around its one sbt call,
  so the oracle starts it bare; an outer `flock` there would never return.
  `VARKA_HEAVY_LOCK=none` runs with no lock; the script's own `flock none` then leaves an empty
  file `none` in the worktree, which the step removes, as `dev/varka_ops/pre-pr.sh` does
  (`rm -f none`, line 234).
- **Where each JVM command comes from.** Line numbers are those of master `e0aee0a14b1`.

| the oracle's command | the house's command it follows | where |
| :--- | :--- | :--- |
| `build/sbt '<module>/testOnly *Suite -- -z "<test>"'`, the test's name inside the one quoted argument | "run a suite or test case with `build/sbt '<module>/testOnly *MySuite -- -z "test name"'`" | the root `AGENTS.md`, line 35; `.agents/build-and-test.md`, line 25 |
| the modules `catalyst` and `sql` | `Seq("sql-api", "catalyst", "sql", "hive", ...)`: `sql` is the project of `sql/core` | `project/SparkBuild.scala`, lines 51 and 52 |
| no profile for a run of `catalyst` alone, `-Phive` for a run that holds `sql` | `cmd += ["--module", "catalyst"] if modules == {"catalyst"} else ["--sbt-arg", "-Phive"]`; `dev/varka_matrix.sh --module sql ... --sbt-arg -Phive   # as the PR job runs`; #1434's description: "SQL (`-Phive`)" | `dev/varka_push_check.py`, line 507; `dev/varka_matrix.sh`, line 30; `dev/varka_scoped_suites.sh`, line 117, which the Build's job calls (`.github/workflows/build_and_test.yml`, line 1830) |
| `-Phive` as sbt's first argument, before the commands | `build/sbt -batch "${sbt_args[@]}" "$project/Test/compile"` | `dev/varka_matrix.sh`, line 295 |
| no input: sbt never waits at a prompt | `-batch) exec </dev/null && shift ;;` | `build/sbt`, line 94 |
| the regeneration, started bare, with its switches | `env "${envs[@]}" flock "$lock" build/sbt "${steps[@]}"`: the script sets `VARKA_COVERAGE_REGEN=true` and the other `*_REGEN` switches in the environment of its one sbt call, with `SPARK_LOCAL_IP=127.0.0.1`. No command of the oracle passes a switch | `dev/varka_regen_generated.sh`, lines 86, 122 and 144 |
| `SPARK_LOCAL_IP=127.0.0.1` in every step's environment | `envs=(SPARK_LOCAL_IP=127.0.0.1)`; `export SPARK_LOCAL_IP=127.0.0.1 ...` | `dev/varka_regen_generated.sh`, line 86; `dev/varka_ops/pre-pr.sh`, line 72 |
| the JDK read before a JVM starts | `running_jdk`: `java -XshowSettings:properties -version` of `JAVA_HOME`'s `java`, else the first on `PATH` | `dev/varka_regen_inputs.py`, line 88 |

  The regeneration script itself runs `sql/testOnly` with no profile (line 95, the cast
  suites), so both forms build on the laptop; the oracle takes the Build's form for the step
  whose result the Build repeats. 5.3 lets part C's builder drop the flag if it fails there.
- **The JDK.** Each JVM step first reads the running JDK's `java.runtime.version` and fails
  with `the JDK is <version>, not a JDK 25: export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
  in the shell that runs the oracle` unless it starts with `25`. The Build runs JDK 25
  (`java-version: 25`, `build_and_test.yml` line 1358; `java: 25`, `varka-master.yml` line 75),
  the results files are named `-jdk25-`, and the laptop has a JDK 26 beside the JDK 25
  (`/usr/lib/jvm/java-26-openjdk-amd64`), which a shell without the export may start. The
  oracle does not set `JAVA_HOME` itself: the path is the machine's, and the template gives it.
- **The host, and a regeneration on a newer JDK.** The width audit is "a property of the CPU
  and the JDK, so the host is part of the record": its head holds `host.cpu` and `host.jdk`
  (`VarkaWidthAuditSuite`, `"jdk" -> System.getProperty("java.runtime.version")`, line 582).
  The house treats the two fields differently, and the oracle follows it.
  - *Another CPU.* The regeneration skips the audit and leaves it owed (`varka regen: skipping
    width_audit.json, owed on its host`, `dev/varka_regen_generated.sh`). The oracle then fails
    at `checks`, on the missing audit record: a task is built on the laptop.
  - *The same CPU, a newer JDK 25* (a package update). The suite does not fail on the string:
    "every verdict agrees, so the file stands until the next regeneration updates its
    provenance" (line 638). The regeneration does not skip (`dev/varka_regen_inputs.py`, line
    117: "the JDK is ... and the committed width audit was written on ..."), rewrites the
    head, and the house's gate commits what the regeneration changed with the branch (`git add
    -u && git commit -q -m "Regenerate the generated files after merging master`,
    `dev/varka_ops/pre-pr.sh`, line 236). So the plan names the head, class `host`: the
    `regen` step passes when the head differs from the merge base's in its `host` block
    alone, and prints `fanout: regen: the audit's host moved with the JDK: commit
    sql/varka/rows/_audit.json`; the builder commits it with the records. If anything else of
    the head moved, or another row's audit record moved because the new JDK lowers a shape
    otherwise, the step fails, and the task stops: that refresh is the lead's, in a pull
    request of its own. Two tasks that carry the same new `host` merge cleanly, since both
    made one change.
- **An exit status of 0 is no pass.** sbt's `testOnly` exits 0 when its filter matched no
  test: it prints `No tests were executed` and succeeds. So no JVM step that runs tests
  trusts the exit status alone. After sbt exits 0, `fused` and `suites` read what ran, with
  one reader (`report_text`, `passed_tests` and `not_passed` of the tool). sbt writes a JUnit
  report a suite, of a ScalaTest suite and of a JUnit one alike: `TEST-<package>.<suite>.xml`
  under `sql/catalyst/target/test-reports` and `sql/core/target/test-reports`, one element a
  test. This line is from a report that a run of 26 November 2026 left on the laptop:

        <testcase classname="org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaCoverageSuite" name="every documented expression compiles to a fused kernel" time="4.441">

  A test passed when a report whose file is newer than the step's start holds a `testcase`
  whose `name` is the wanted name, character for character once XML's escapes are undone
  (`&lt;` is `<`), with no `failure`, `error` or `skipped` element in it. Fact 34 says how sbt
  names each kind of test. Every other case is a failure with a line of its own:
  - `<suite> wrote no report in this run: sbt ran no test of it`: no report, or only one
    older than the step's start;
  - ``the test `<name>` did not run and pass in <suite>``: the report of this run does not
    hold the name as passed;
  - `no test of <suite> passed in this run's report`.

  `fused` seeks its one test, "every documented expression compiles to a fused kernel", in
  the report of `VarkaCoverageSuite`. `suites` seeks, for each suite of its command, a report
  of this run in which a test passed; and each `test` line of the card by its whole name
  (4.9): a `coverage` test in the report of `VarkaCoverageHarnessSuite`, any other in the
  report of the suite its file is named for. `regen` reads no report: its proof is the files
  the regeneration wrote, which the `checks` step holds. So an oracle cannot pass on a task
  whose tests were never written, were renamed or did not run, nor on a filter that matched
  nothing.
- **What "a stale generated file" means here.** The oracle regenerates, so after the `regen`
  step no record of the task can be stale. What the step catches is the other case: the
  regeneration changed a file the plan does not name. That is a change of another row's bytes
  or of a list no fan-out task touches, and the task stops there. On a task's branch the
  script runs its JVM steps without `--full`: a changed source file or row file is "not known
  to leave the regeneration alone" (`dev/varka_regen_inputs.py`). `--full-regen` is for part
  C's run on an unchanged tree.
- **The benchmark.** Numbers come from the runners, after the push, so on the laptop the step
  can only read what is committed. A section whose file the branch does not change still holds
  the baseline's numbers: the step prints `fanout: bench: owed: <file> holds the baseline's
  numbers of <section>` and passes. Once the lead has dispatched the benchmark and its results
  are committed on the branch, the same step fails on a section under `1.0X`. `Relative` is
  the house's ratio of best times; a ratio near 1 is read again by row 680's gate, with its
  band. The owner confirmed that "owed" may pass (question 3, 27 November 2026).
- **Why `VarkaCoverageSuite` and not `dev/varka_emit.sh`.** `m9/PLAN.md` 2.7 names the emit
  tool. Its default columns are not the coverage table's (fact 12), so each task would need a
  `--columns` list, and it knows no session zone. The suite's test compiles the task's own
  coverage rows over the table's own columns, and it is what CI runs. Section 11's edit 2
  records the change.
- **What it does not run.** The option matrix, the width audit under `-XX:UseAVX=2`, the Maven
  build and the benchmark: they run in CI, as for every pull request (`m9/PLAN.md` 1.6). The
  review: the lead's.

### 4.6 From `PASSED` to a merged pull request

**One rule for everything a builder writes.** A tracked file changes only before the oracle's
last run. Whatever is written after it lives under `target/varka-fanout/<id>/`, which git
ignores. So the tree in the `PASSED` line is the head's tree, and stays so.

| what | where | when |
| :--- | :--- | :--- |
| the Outcome, section 9 of the generated plan: what was built, each place where the plan was wrong or left a choice, each oracle run that failed with its step and the fix | tracked, in the plan's file below its end marker | before every oracle run; the `card` step fails while the heading or one of the three fields is missing, or a field is empty |
| the records, the census and the cast tables | tracked | written by the oracle's `regen` step, committed right after `PASSED` with nothing else |
| the body file: the pull request's description, with the `fanout: PASSED` line and the `Generated-by` line | `target/varka-fanout/<id>/body.md`, not tracked | after `PASSED` |
| the lead's findings for a fix round: a first line `pushed: <commit>`, the head the lead pushed, then one finding a paragraph | `target/varka-fanout/<id>/findings.md`, not tracked, written by the lead and by nobody else | after the review, and after the pull request opens |

**The Outcome's form.** Below the end marker the plan holds the line `## 9. Outcome` and
three fields, each a line that starts `- Built:`, `- Plan gaps:` or `- Oracle failures:`. A
field's text is what follows the colon, on that line and on the lines under it up to the next
field; `none` is a text. The `card` step fails with one line a fault, each starting `section
9, the Outcome, is not written: `, and then: ``no line `## 9. Outcome` below the end marker``
(the section or all below the marker was deleted, or the heading reworded); ``no field `-
Plan gaps:` ``; ``the field `- Built:` is empty``. So a deleted section fails as an unwritten
one does.

`ready CARD --body-file F` exits 0 and prints `fanout: READY <id> head <commit>` when all of
these hold, and otherwise prints one line a reason, `fanout: ready: <reason>`, and exits 1:

1. the worktree is clean (`git status --porcelain` prints nothing);
2. the body holds exactly one line `fanout: PASSED <id> tree <tree> in <n> s` of this card's
   id, and `<tree>` is `git rev-parse 'HEAD^{tree}'`;
3. 4.4's check names every changed file and finds every test of the card; where it does not,
   the reasons are the check's own lines, one a file and one a test: `<file> is changed and
   the plan does not name it`, ``the test `<name>` is not in the lines the change adds to
   <file>``;
4. the body holds exactly one line `Generated-by: <tool> (<model>)`;
5. `python3 dev/varka_msg_check.py pr --title "[VARKA-<row>] <title>" --body-file F` exits 0:
   the five headings, soft-wrapped paragraphs, the title's length, the size cap;
6. the branch has no upstream. `git rev-parse --symbolic-full-name HEAD` prints the branch's
   ref, and `git for-each-ref --format='%(upstream)' <that ref>` prints nothing. A branch made
   with `--no-track` has none; one made from `origin/master` without it tracks
   `refs/remotes/origin/master`, and one pushed with `-u` tracks its remote branch. The reason
   reads `the branch has an upstream, <ref>: a fan-out branch tracks nothing; run `git branch
   --unset-upstream`, and do not push`;
7. no remote-tracking ref holds a commit of the branch. The branch's commits are the lines of
   `git rev-list <base>..HEAD`, where `<base>` is what `git merge-base HEAD origin/master`
   prints (the commit that 4.4's check counts from). For each of them, `git for-each-ref
   --contains <commit> --format='%(refname)' refs/remotes` prints nothing. Where the branch
   holds no commit of its own the list is empty, and the head is tested in its place: it is
   on `refs/remotes/origin/master`. A commit is on a ref when it was pushed
   (`refs/remotes/fork/<branch>`: `fork` is the remote the house's scripts push to,
   `FORK_REMOTE` of `dev/varka_ops/common.sh`), whether it is the head or a commit under the
   head. So a builder that pushes and then commits once more is refused, as one that pushed
   its head is. No commit of the list is on master's own remote-tracking ref, by the merge
   base, so the test needs no exception for master. The reason is one line, with every ref
   that holds one of the commits, sorted: `the branch is on <refs>: it was pushed, or it
   holds no commit of its own; a fan-out builder does not push: stop and report this line`;
8. in a fix round, condition 7 counts from the head the lead pushed. `ready` reads
   `target/varka-fanout/<id>/findings.md` where the file exists. The lead writes that file,
   and its first line is `pushed: <commit>`: the forty hex digits that `git rev-parse
   fork/<branch>` prints in the worktree after the lead's push. Where the first line has that
   form, and the commit is one of the branch's commits, and a remote-tracking ref holds it,
   the commits tested are the lines of `git rev-list <that commit>..HEAD`: the fix round's
   own. Where that list is empty the head is tested, as in condition 7, so a fix round with
   no commit is refused, and so is one whose builder pushed. Where the line names a commit
   that is no commit of the branch, or one that no remote-tracking ref holds, condition 7
   counts from the merge base as in a first round, and one more reason is printed:
   `findings.md says the lead pushed <commit>, and that is no pushed commit of this branch:
   the line is the lead's to write; stop and report this line`. A file with no such first
   line changes nothing: the lead gave its findings before it pushed, and the round is held
   as a first round is.

Conditions 6, 7 and 8 make "a builder never pushes" a check: a builder that pushed all the
same does not reach `READY`, on that head or on a later one. After the lead opens the pull
request the branch is on the fork, and a fix round's builder must still reach `READY`: the
lead's line says which commits were the lead's to push, and `ready` holds the commits after
it. The rule is a line in a file that only the lead writes, and not a flag of `ready`: a
flag that the fix round's text names is one that a first round's builder, refused after a
push, can read in the same plan and add. The lead's scripts push with no `-u` (`git push -q
"$FORK_REMOTE" HEAD:"$b"`, `dev/varka_ops/open-pr.sh`), so they set no upstream (facts 36
and 39). One case is not held: a builder that pushes, writes the lead's line itself with the
head it pushed, and commits again. The template forbids it twice, and the lead finds a
branch on the fork and a `findings.md` that it did not write.

**The loop** (the owner's answer to question 2, 27 November 2026: a builder stops at `READY`,
and the lead opens every fan-out pull request through `open-pr.sh` after a gate and a review,
as the house rules have it).

1. *The builder* builds, writes the Outcome, commits, runs the oracle, commits the records,
   writes the body file and runs `ready`. Its last act is a report of five lines, and then it
   ends its work. It never pushes, never runs `gh` and posts nothing:

        fanout: READY <id> head <commit>
        branch: <branch>
        worktree: <the worktree's path>
        body: target/varka-fanout/<id>/body.md
        oracle: <the fanout: PASSED line>

2. *The lead*, in the builder's worktree: runs `ready` itself on that head with that body
   file (the check of the builder's claim: it costs no JVM); runs the house's gate
   (`dev/varka_ops/pre-pr-slot.sh`) and `/code-review high`; adds the review's line to the
   body file; and opens the pull request with `open-pr.sh <worktree> <branch> <body file>
   <lock> "[VARKA-<row>] <title>"`, which refuses a head with no gate stamp and a body with no
   recorded review (fact 11).
3. *The tree after the gate.* The gate may merge master and commit a regeneration, so the
   pull request's head can be a descendant of the builder's head, with another tree. The
   `PASSED` line then stands for the builder's head, which the `READY` line names: the lead's
   check is step 2's `ready` before the gate, not one after it. From the gate on, the gate's
   stamp and the Build hold the head.
4. *The fix round,* one for a task. Where the review has findings for the builder, the lead
   writes them to `target/varka-fanout/<id>/findings.md` in the worktree, one finding a
   paragraph under the first line `pushed: <commit>` (condition 8; the commit is the output
   of `git rev-parse fork/<branch>`), and gives a builder the generated plan's path and that
   file's. The plan assumes
   a new builder session of the same model: a builder keeps nothing from one session to the
   next, and the generated plan's section 8, "The fix round", says all it must do. It fixes
   or answers each finding, adds to the Outcome, commits, runs the oracle again, commits the
   records, replaces the `PASSED` line in the body file, runs `ready`, reports as in step 1
   and ends its work. The lead gates again and pushes with `push-ci.sh`.
5. Then the pull request merges, or the lead takes it over, or the task returns to the pool,
   and the lead writes the ledger's line (4.7).

**The house rules, kept whole.** `dev/varka_ops/AGENTS.md` says "**Never `git push` by hand.**"
and `sql/varka/AGENTS.md` says "**Review your own diff before opening the pull request**". A
cheaper model has no `/code-review` and no gate stamp, so it opens nothing: the lead, which
has both, does. `m9/PLAN.md` 2.7 has the model open the pull request; section 11's edit 5
records the owner's answer there. Part D adds one sentence to `dev/varka_ops/AGENTS.md` and
one to the root `AGENTS.md`'s section "Pull Requests": a fan-out task ends at `READY`.

**The root `AGENTS.md` says otherwise, and the generated plan overrides it in words.** That
file tells every agent to "read and follow every `AGENTS.md`", and its section "Pull Requests"
says "Always push to the personal fork" and "Use `gh pr create` to open PRs". The generated
plan itself sends its builder to that file, for "Code Standards". So the template says, where
it names the file (its section 0) and again where the task ends (its section 8), in these
words: "This task ends at `READY`. Do not push. Do not open a pull request. Do not run `gh`.
The root `AGENTS.md`'s section on pull requests is for the lead, not for this task." Section 0
also names the other texts that are the lead's: the root file's "Pre-flight Checks" and
"Investigations", the section "Pull requests: reviewed by their author before they open" of
`sql/varka/AGENTS.md`, and all of `dev/varka_ops/AGENTS.md`; and it says that for this task
the plan wins. The set-up line makes the branch with `--no-track`, as the root file's
pre-flight asks of a new branch, and `ready`'s conditions 6, 7 and 8 are the mechanical
half.

### 4.7 The ledger

One line a pull request, one file a line: `sql/varka/plans/m9/fanout/ledger/<repository's
name>-<pr>.tsv`, as `varka-1584.tsv` or `vechrono-12.tsv`. A file holds 18 cells parted by
tabs and one newline. One file a line, because every fan-out pull request adds a line and a
shared file would conflict at its last line every time, as the calibration table did before
`dev/varka_calibration.py`. The lead writes the lines, never a builder (the owner's answer to
question 8): the review's counts and the outcome are the lead's to know. The lead adds a
line's file in a plan-only pull request of its own, which it merges when green as it does
every plan-only pull request; one such pull request may carry several lines. A returned or an
open task gets its line the same way, since it has no merge to carry a file.

| # | column | value |
| ---: | :--- | :--- |
| 1 | `task` | the card's id; for a pull request with no card, a short name as `VARKA-655-B` |
| 2 | `row` | the task row's number |
| 3 | `kind` | `fanout`: a part of rows 667 to 669 or 672 to 674. `pilot`: a task of row 659. `library`: a pull request of `vecbricks/vechrono` built by a cheaper model (`VARKA-679.md`). `other`: any other pull request a cheaper model built, as row 655's parts B and C. `reference`: a pull request merged before the ledger, kept as a pilot of this row |
| 4 | `repo` | `vecbricks/varka` or `vecbricks/vechrono` |
| 5 | `pr` | the pull request's number |
| 6 | `units` | how many units it fuses; `1` where the word does not apply |
| 7 | `builder` | the model, as the `Generated-by` line of the body file has it in the brackets |
| 8 | `tool` | the tool, as that line has it before the brackets |
| 9 | `opened` | the day the lead opened the pull request, as `2026-11-27`: `gh pr view <pr> --json createdAt -q .createdAt`, its first ten characters. The opener is always the lead, so no column holds it |
| 10 | `closed` | the day it merged or closed; `-` while it is open |
| 11 | `outcome` | `open`; `merged`: the builder's work merged; `taken-over`: it merged after the lead rewrote it; `returned`: closed, the task back in the pool |
| 12 | `fix_rounds` | how many fix rounds the lead gave a builder (4.6, step 4): the commits of the branch whose subject starts `Address the review`, the message the generated plan gives a fix round: `git log --format=%s <base>..<head> \| grep -c "^Address the review"`. `0` for none; `-` for a pull request from before the ledger |
| 13 | `findings` | the findings of the lead's reviews, in all |
| 14 | `fixed` | how many of them were fixed |
| 15 | `kept` | how many were kept with a reason |
| 16 | `oracle_s` | the seconds of the last `fanout: PASSED` line in the body; `-` where there is none |
| 17 | `conflicts` | `-`, or the classes of the files in which the branch conflicted with master, as `census` or `census,edit`: what `VARKA-653.md` prediction 8 scores. For each merge commit `m` of the branch (`git log --merges --format=%h <base>..<head>`), the conflicted paths are the lines after the first of `git merge-tree --write-tree --name-only --no-messages m^1 m^2` (nothing after the first line: no conflict); a path's class is what `python3 dev/varka_fanout.py scope <card>` prints beside it on the branch |
| 18 | `note` | one sentence; `-` for none |

No cell is empty; none holds a tab or a space at an end. `fixed` plus `kept` is at most
`findings`. `closed` is `-` exactly while `outcome` is `open`.

`ledger` prints the header line and the lines, by day opened and then by number. `ledger
--summary` is G5's report:

    ledger: <n> line(s), <f> of them fan-out pull requests
    fan-out: merged <a>, taken over <b>, returned <c>, open <d>
    built by a cheaper model: <k> of <a + b> fan-out pull requests that landed
    <builder>: <n> pull request(s), merged <m>; findings <x>, fixed <y>, kept <z>; fix rounds [<r>, ...]
    other kinds: pilot <p>, library <l>, other <o>, reference <r>

A builder is a cheaper model when its `builder` cell starts with `DeepSeek Flash`, `Kimi K2.7
Code` or `GigaChat Pro`, decision 3's three. `k` counts the `merged` fan-out lines of such a
builder; a `taken-over` line is in the denominator and never in `k` ("a pull request the
coordinator had to rewrite does not count as built by the model", `m9/PLAN.md` 2.7); a
`returned` line is in neither. The builders' lines leave out the `reference` lines. Library
pull requests are ledger lines of their own kind, counted beside the share and not in it:
`m9/PLAN.md` 2.10 left that to this plan, and the owner confirmed it (question 4).

**The first line** is pilot 1's, of kind `reference`: its builder, its review's counts and its
dates are in its description and in git (3.3). Part D also gives a line to each part of row
655 that a cheaper model built before the ledger existed (`VARKA-655.md` 8 says the lead wrote
their facts in that plan's section 11).

### 4.8 The guide

`sql/varka/ADDING_AN_EXPRESSION.md` keeps its form, ten numbered steps each ending in a check,
and is rewritten whole (appendix B). What changes, statement by statement:

| the guide of today | the guide after part E |
| :--- | :--- |
| "the last ten expression additions", counted on milestone 4 | the two pilots' files by class |
| "Every Varka node computes over int32 lanes" | six lane types; `VarkaTypes` says which lane a type is read on |
| step 2, "It **must fail**, listing every `switch`" | reuse a node, or add one by `VARKA-489.md`'s recipe; R1 of the push check |
| step 3, one check with the default columns | the list of family compilers; `--columns <name>:<type>` |
| step 4, "`VarkaLoopEmitter`. Add the arm to `emitValue`" | `VarkaVectorWalk` and the five lowering classes |
| step 6, "if it touches the calendar" | any guarded range; the two coverage suites |
| step 7, a row of the Scala table and `VARKA_COVERAGE_REGEN=true` | a row file, the census's line, a skip file, `dev/varka_regen_generated.sh --all`, and no edit of the docs page |
| step 8, a case id in `VarkaEmitterParityBenchmark` | the baseline class's section and its fusion class |
| step 10, `dev/varka_gate.sh` and `m4/PLAN.md` | the oracle for a fan-out task; `dev/varka_ops/pre-pr.sh` for a row of its own |
| nothing on generated plans | "Two ways to take the path" and "The fan-out task" |

`check` then holds the page to the tree: a token in backticks that is a path under `dev`,
`sql`, `docs`, `build` or `.github`, with no placeholder in it, must be a tracked file or a
directory with a tracked file; a token `Varka<Name>`, alone or followed by `.java`, `.scala`
or `.<member>`, must be the name of a tracked source file. The same check runs over the
template, and there also each quoted rule (lines that start with `> `, joined while they
follow each other) must be in one of the rule files as written, white space aside: the root
`AGENTS.md`, `sql/varka/AGENTS.md`, `dev/varka_ops/AGENTS.md`, and the `.md` files of `.agents`
and of `sql/varka/.agents`. So a rule that row 694 moves into `sql/varka/.agents/` is still
found, and a rule that is reworded fails the docs job with its first 70 characters.

### 4.9 Where a task's tests go

Row 653 left this to this row (`VARKA-653.md` 4.7). `VarkaExpressionCompilerSuite.scala` is
4,309 lines that 28 of 51 row-adding commits edit. The rule, which the template states: a
fan-out task adds no test to that file. Its compile tests and its answer tests go in a suite
the card names: an existing suite of the family, as an `edit`, or a new JUnit 5 class under
`@VarkaSuite`, as an `add`. The scope check enforces it, since that file is in no card. Two
tasks in flight never get the same suite as an `edit`; the coordinator gives one of them a
new suite.

**The rule of a task's tests.** A task that adds a kernel adds tests, and the oracle does not
pass with none.

- Every card has one `test` line or more. `test: <test file> | <name>` names a test in a test
  file of the card's own (an `edit` or `add` path with `/src/test/` in it, whose suite a
  `suite` line names). `test: coverage | <name>` names a test that `VarkaCoverageHarnessSuite`
  makes from one of the card's coverage rows. In both, `<name>` is the `name` that sbt's
  report gives the test, character for character (fact 34): the two tables below give every
  form, and the parser refuses any other.
- A card with an `edit` or `add` path under `/src/main/`, which is a card that adds or changes
  kernel code, must have a `test` in a test file. **The one exception** is the line
  `no-own-test: <reason>`: the card claims that the tests the coverage suites make from its
  rows are enough, and then all its `test` lines are `coverage` ones. The coordinator writes
  the claim; the generated plan prints it in its section 3; the lead's review of the pull
  request confirms it or makes it a finding. Question 9 of section 13 asks the owner whether
  the lead's review is approval enough. A card with no main source needs no claim: its rows
  are its change.
- Three checks hold it. The parser refuses a card that breaks the two rules above, or whose
  test has a name of no form of the tables below (exit 2). The `scope` step, and CI's `check`
  on the branch, seek each test of a test file in the lines the change adds to that file, by
  the first table's last column, with a string that the source writes over several lines
  closed up first. The `suites` step seeks each test, a `coverage` one too, in sbt's reports
  as passed, by its whole name (4.5).
- What no check reads is whether the tests hold the template's four cases; the lead's review
  does, and the template says so.

**A test in a test file of the card's own.** The examples are `name` attributes of reports
on the laptop (fact 34).

| the test | its file | its name in the report, which is its name in the card | an example | what `scope` seeks in the added lines |
| :--- | :--- | :--- | :--- | :--- |
| a ScalaTest `test("...")` | `.scala` | the sentence, as the source writes it between the quotes | `make_timestamp_ntz answers every day whose midnight is a long on the kernel, in every mode` | the sentence |
| a JUnit `@Test` method | `.java` | `<method>()`; never its `@DisplayName`, which no report holds | `makeTimestampNtzAtTheEdges()` | `void <method>(` |
| a test that a JUnit `@TestFactory` method makes | `.java` | `<factory>():<the display name the factory gives the test>` | `everyCase():time_trunc at every level agrees with the row engine over every second` | ` <factory>(`, the factory method |
| a case of a JUnit `@ParameterizedTest` | `.java` | `<method>(<parameter types>):<n>`: the types as the report writes them, `<n>` the case's number from 1 | `slice(String, VarkaEmitterNarrowArithSuite$Mode, VarkaEmitterNarrowArithSuite$Arm):1` | ` <method>(`, the method |

In a `.java` file the parser takes these three JUnit forms and no other (``a JUnit test is
`<method>()`, `<factory>():<sentence>` or `<method>(<types>):<n>` ``). The source of a
factory-made test or of a parameterized case holds the method and not the whole name, so
`scope` seeks the method: the task adds that method. A case added to a factory that master
already has cannot be a `test` line; such a card names a plain `@Test`. The coordinator takes
the types of a parameterized method from its signature, each by its simple name, a nested
type as `<Outer>$<Inner>`, parted by a comma and a space.

**A `coverage` test.** `VarkaCoverageHarnessSuite` makes them, through six `@TestFactory`
methods. A name is `<factory>():<family>: <sql> <tail>`: the row's `coverage-family`, a colon
and a space, its `coverage` line, a space, and the factory's tail. The card writes `<` and `>`
as they are; the report writes them as `&lt;` and `&gt;`, and the tool undoes that.

| the coverage row | factory | tail | an example |
| :--- | :--- | :--- | :--- |
| every row: the row's answers under both consumers | `everyRow` | `matches the row engine under both consumers` | `everyRow():Integer arithmetic: abs(i) matches the row engine under both consumers` |
| every row: its ANSI form | `ansiForm` | `raises under ANSI only where the row engine does` | `ansiForm():Integer arithmetic: abs(i) raises under ANSI only where the row engine does` |
| a row with a `coverage-domain`, in a projection and in a filter | `domains` | `is answered inside its domain and where it is unknown, and declined outside with the verdict's status, in a projection and a filter` | `domains():Integer arithmetic: abs(i) is answered inside its domain and where it is unknown, and declined outside with the verdict's status, in a projection and a filter` |
| a row whose `coverage-form` is `predicate`: as a filter | `partitions` | `splits every fixture into true, false and unknown rows` | `partitions():Predicates: d < d2 splits every fixture into true, false and unknown rows` |
| the same row under a null test | `nullTestConditions` | `under IS NULL and IS NOT NULL fuses as a CASE condition` | `nullTestConditions():Predicates: d < d2 under IS NULL and IS NOT NULL fuses as a CASE condition` |
| the same row as a projection's boolean output | `booleanOutputs` | `as a boolean output matches the row engine` | `booleanOutputs():Predicates: d < d2 as a boolean output matches the row engine` |

So a row of form `projection` has two names, or three with a domain; a row of form
`predicate` has five, or six with a domain. The tool builds them from the card's rows
(`HARNESS_TESTS`, held to a real report: fact 35), and the parser refuses a `coverage` test
that is none of them: a name without its factory, a `domains()` name for a row with no
`coverage-domain`, a filter's name for a projection row (`is no name the harness gives a
row`).

**Which coverage test a card names.** The `suites` step runs the whole harness, so every name
above must pass whatever the card names. A card names coverage tests where they are its only
tests, which is a card with `no-own-test` or with no main source: for each coverage row, the
`domains()` name where the row has a `coverage-domain`, and the `everyRow()` name where it
has none. A card names no test of the other two coverage suites: `VarkaCoverageDifferentialSuite`
makes a test from the first row of each family alone, and `VarkaCoverageSuite` makes none from
one row.

**The two pilots, from what they really added.** `git show --stat 43cf0d94acc` has no file
under `src/test` but the coverage table (2 lines): #1584 added an arm with no test of its own,
and its review kept that ("no emitter-level matrix test for `IntNeg(..., abs=true)` at the int
lane (the harness covers the answers)"). So pilot 1's card claims `no-own-test` with those
words, and its one test is the harness's `domains()` test of the row `abs(i)`, which has a
domain. `git show --stat
39a404b73ec` has `VarkaTimestampNtzCastSuite.java` (148 lines changed) and
`VarkaTimestampNtzSuite.scala` (128): three `@Test` methods and three `test(...)` sentences,
which are pilot 2's six `test` lines, each found in the commit's added lines (fact 33).

### 4.10 Edge cases, and what declines by name

| case | what happens |
| :--- | :--- |
| a card with a tab or a non-ASCII character | `fanout: <card>:<line>: a character that is not printable ASCII`, exit 2 |
| an unknown key; a key given twice that is given once; a block's line before its block | exit 2 with the line's number or the key |
| five units, or none | `one to four `unit` lines`, exit 2 |
| a unit of a type the census does not have, or of an entry it does not have | `plan`: `` `<name>` is no entry of the curated census``, exit 2. A type is not checked against `types.tsv`: the `checks` step fails later, since the census never counts the unit |
| a unit fused already | `plan` and `files`: `` `<name>` is fused over `<type>` already; a pilot's card says `merged:` ``, exit 2 |
| an entry whose type is marked `^` or `~` (fused in part, or over a derived operand) | counted as present: the task is no fan-out task, and the card is refused as fused already |
| two units of one entry | one old line and one new line, with both types |
| two coverage rows whose SQL differs only in case or spacing | two files: the slug ends in eight hex digits of the SQL's hash |
| a card with no `edit` and no `add` | allowed: a task may be a coverage row and a census line alone, where a kernel fuses already. Its `test` lines are `coverage` ones, and it needs no `no-own-test` |
| a card with no `test` line; a `test` in a file that is no `edit` or `add` path, or whose suite no `suite` line names; main source and neither a test file's test nor `no-own-test` | exit 2, with `at least one `test` line`, `names no test file of the card's `edit` and `add``, ``no `suite` line names the file's suite``, or `main source changes, so a `test` in a test file, or `no-own-test`` |
| a `test` in a `.java` file named by a sentence, as its `@DisplayName` | exit 2: ``a JUnit test is `<method>()`, `<factory>():<sentence>` or `<method>(<types>):<n>` ``; no report holds a `@DisplayName` |
| a test that a `@TestFactory` makes, or a case of a `@ParameterizedTest`, in a test file of the card's own | named as the report names it (4.9's first table); `scope` seeks the method in the added lines, and `suites` the whole name in the report. A case added to a factory that master already has is no `test` line |
| a `coverage` test that is no name the harness gives one of the card's rows | exit 2: `is no name the harness gives a row` (4.9's second table) |
| a task's plan with section 9 deleted, its heading reworded, a field deleted or a field left empty | the `card` step fails with `section 9, the Outcome, is not written: ` and the fault (4.6) |
| sbt exits 0 and ran no test: a `-z` sentence or a suite's name that matches nothing | the `fused` or the `suites` step fails on the report: `wrote no report in this run`, or `did not run and pass` (4.5) |
| a branch made without `--no-track`, or pushed with `-u` | `ready` exits 1 with `the branch has an upstream, <ref>` (4.6, condition 6) |
| a commit of the branch that a remote-tracking ref holds, the head or one under it: it was pushed; or a branch with no commit of its own | `ready` exits 1 with `the branch is on <refs>` (4.6, condition 7) |
| a fix round, whose branch the lead pushed when it opened the pull request | `ready` counts from the commit of the lead's line `pushed: <commit>` in `findings.md`; with no such line it exits 1 as the line above; with a line whose commit is not a pushed commit of the branch it exits 1 with `findings.md says the lead pushed <commit>` too (4.6, condition 8) |
| an `edit` or `add` line without the four parts | exit 2: ``is not `where: <place>; when: <condition>; copy: <code to copy>; result: <what it does>` `` |
| an entry whose name holds a bar: `\|`, the bitwise or, whose line of the curated census starts `\|\|` and so has eight fields | `unit: entry \| int` parses, and `plan` stops with `` `\|` is no entry of the curated census``, exit 2. It is fused over its four types, so no task needs it; a type added to it later is a task given out by hand |
| a JDK that is not a JDK 25, or no `java` | the first JVM step fails with `the JDK is <version>, not a JDK 25`, before any JVM starts |
| a JDK 25 newer than the one the audit's head names | the `regen` step passes with the head's `host` block changed, prints that the host moved, and the builder commits the head (4.5) |
| `VARKA_HEAVY_LOCK=none` | no lock; the `regen` step removes the empty file `none` that the script's own `flock` leaves |
| a test's sentence with a quote or a backslash in it | the source escapes it, so the card's name is not in the added lines as written and `scope` fails: name such a test by a sentence without them |
| the plan's file edited above its end marker | the oracle's `card` step fails; `plan --check` prints `is behind` |
| master merged into the branch, and the entry's line changed there | the same: the plan is stale, and the coordinator runs `plan --write` |
| a regeneration that moves another row's record | the `regen` step fails with that file as not named |
| the heavy lock held by another run | the step waits; its seconds hold the wait |
| a host that is not the width audit's | the regeneration skips the audit and leaves it owed, as it does today; `python3 dev/varka_rows.py --check` then fails on the missing audit record. A fan-out task is built on the laptop (decision 10), which is the audit's host |
| a results file the branch changes, with a section at `0.9X` | the `bench` step fails |
| `--only` with every step named | it still prints no `PASSED` line: only a run with no `--only` does |
| rows 642 and 646 | the row waits for both (the owner's answer to question 5). A recorded fact, for a later re-ordering: no command reads an emit option's file or the task table. A task adds no option (the template's section 7), and no task edits a row's status. This row's own status edits find their file by its text (5.0) |
| row 696 lands | a census claim becomes a file. The classes `curated` and `census` then name other files, and `files` writes a claim file. That edit of the tool is row 696's, and section 12 states the seam |

### 4.11 What is out of the row

| excluded | the row that holds it |
| :--- | :--- |
| the cards of the four casts from `TIME`, the three models' first tasks, the admission rule's verdicts, what the pilot teaches the template | 659 |
| every card of a fan-out row | 667 to 669, 672 to 674, each written by the coordinator when the part is given out |
| the benchmark gate with its band at each width | 680 |
| a pull request's Build on the merge with master | 590 |
| a census claim as a file | 696 |
| the template of a library task and its oracle (`mvn -B verify`) | 679 (`VARKA-679.md` 5.3); this row only gives its pull requests a ledger line |
| a command that writes a card from the census | none: a card holds what needs judgement; the owner's answer to question 6 leaves it to row 659 |
| the option matrix and the width audit under AVX2 inside the oracle | none: CI's, as for every pull request |
| an ops script that pushes for a builder | none: a builder does not push; the lead opens every fan-out pull request through `open-pr.sh` (4.6) |

## 5. The parts

Five pull requests. The first four each add one block of the tool and what it reads; the last
rewrites the guide and closes the row.

| part | title | branch | counted lines, estimate |
| :--- | :--- | :--- | ---: |
| A | `[VARKA-658] Check a change against the files its fan-out task names` | `varka-658a-cards-scope` | 755 |
| B | `[VARKA-658] Generate a fan-out task's plan from its card` | `varka-658b-plans` | 830 |
| C | `[VARKA-658] Say in one command whether a fan-out task is done` | `varka-658c-oracle` | 600 |
| D | `[VARKA-658] Record each fan-out pull request in a ledger` | `varka-658d-ledger` | 240 |
| E | `[VARKA-658] Bring the expression guide to today's tree` | `varka-658e-guide` | 390 |

The estimates are file by file, and the tool's lines are those of the text as `ruff format`
leaves it (fact 32), which is how appendix A gives it. A: the tool 634, the two cards 91,
`modules.py` 2, the workflow 12, this file 15. B: the tool 416 (the block's 405 and the
docstring's 11), the template 383, this file 15, the documents 15; the two generated plans
count nothing (fact 16). C: the tool 550 (543 and 7), a calibration entry 8, this file 40;
the results file counts nothing. D: the tool 204 (198 and 6), the ledger's line 1, the ops
rules 6, the root `AGENTS.md` 3, this file 20. E: the guide 175 added and 163 removed,
`sql/varka/AGENTS.md` 9, a lesson 16, this file 25. Parts A and B are over 750, so each sheet
has a cut line (5.1, 5.2); no other part is near it.

| part | needs | gives |
| :--- | :--- | :--- |
| A | this plan merged; the tag `varka-m8`; row 653's parts A1 to F; row 654's parts a and b; rows 642 and 646 done | the cards' grammar, the scope check, the two pilot cards, CI's check. The done-when's first clause for the files' list. Master is green; nothing reads the tool but the docs job |
| B | A | the template, `plan`, `files`, the two generated plans. The done-when's first clause in full. Row 659 can write its cards from here; its builders wait for part E |
| C | B | the oracle and `ready`; the oracle's runs on pilot 1 and its time. The done-when's second and third clauses |
| D | A (it needs no line of B or C) | the ledger and its first line. The done-when's fourth clause |
| E | B, C and D: the guide names their commands | the guide. The done-when's last clause; the row **Done** |

Parts A, B and C are built one after the other. Part D may be built beside part B or part C
by another agent: its block is its own, but it also inserts before the same line of the tool
and edits the same docstring, so it merges after the other and takes master's side of those
few lines first. Part E is last. Each part leaves master green and useful alone.

### 5.0 What every sheet shares

- Start from master, in a worktree of the part's branch: `git worktree add --no-track -b
  <branch> ../<branch> origin/master`, then `dev/varka_precommit.sh --install-hook`.
  `--no-track` leaves the branch with no upstream, as the root `AGENTS.md`'s pre-flight asks
  of a new branch; part C's run 3 reads that. `dev/varka_task_new.sh` is not used: the plan
  exists.
- Names, set once a shell:

        M9=sql/varka/plans/m9
        GEN=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen
        TJ=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
        F=$M9/fanout; P1=$F/VARKA-621-abs-int.txt; P2=$F/VARKA-340-time-to-ntz.txt
        LOCK="${VARKA_HEAVY_LOCK:-$HOME/proj/.varka-locks/heavy.lock}"
        X=target/varka-658; mkdir -p $X

  `$X` is a scratch folder under `target/`, which git ignores.
- Only part C starts a JVM. There, in every shell that starts one: `export
  JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1`. The oracle takes the
  heavy lock itself and refuses a JDK that is not a JDK 25.
- Run B0 before any edit. Each line's comment is what it must print. A line that prints
  otherwise is a stop: report the command and its output and edit nothing.
- Where a sheet gives a file's text, type it as given; where it gives an old line and a new
  one, the old line is in the file once.
- The tool's docstring grows with each part. A usage line that a sheet's step 2 gives starts
  with two spaces in the file, as the usage lines around it do; a paragraph's lines start at
  the first column. The sheets show both indented by eight more.
- Before the commit: `git add -A`, then `dev/varka_precommit.sh`, which reads the staged
  files. The tool's text as appendix A gives it passes `ruff check` and `ruff format --check`
  at every part (fact 32); if a newer ruff reports a line, run `ruff format
  dev/varka_fanout.py`, which changes no behaviour, and say so in the description.
- The size: `python3 dev/varka_msg_check.py size` prints `pull request size: <n> counted lines
  of <m> changed ..., under the cap of 1000`. A count over 950, or over the part's estimate by
  half, is a stop: report the count and the three largest files.
- Before the pull request: `/code-review high` on the diff, its findings fixed and named in
  the description; `python3 dev/varka_msg_check.py pr --title "<title>" --body-file <file>`;
  then the house's gate and push scripts, as `dev/varka_ops/AGENTS.md` says. These five parts
  are not fan-out tasks; like a fan-out task's, their pull requests open through the house's
  scripts.
- **The row's status.** The row's line is in `$M9/PLAN.md`, or in a file of its own once row
  646 has moved the table. Find the file by its text; this prints one path:

        grep -rl -F "The fan-out task: its template, generator, oracle and ledger. **" $M9 | grep -v VARKA-658.md

  In that file, part A replaces the text of the first line below by the second, and part E the
  third by the fourth. Each old text is in the file once. `<the day>` is the day of the edit,
  as `3 December 2026`.

        oracle and ledger. **Planned** (
        oracle and ledger. **Started** (part A, branch `varka-658a-cards-scope`, <the day>) **Planned** (
        oracle and ledger. **Started** (part A,
        oracle and ledger. **Done** (parts A to E, <the day>; started with part A,
- Each part's last step also edits this file: a dated paragraph in section 14 with what was
  built, the commands' outputs, and each prediction of section 7 that the part scores.
- **What runs only in CI:** the Build, whose docs job runs the tool's own cases and its
  `check`; and the Maven build. No part needs the option matrix: no part changes what a suite
  reads. Part A edits the workflow and `modules.py`, which are not Varka-scoped, so its Build
  runs Spark's whole matrix once: say so in its description.
- Stop also on: a test that should pass and fails; a file in `git status --short` that the
  sheet does not name; anything the sheet does not decide. A stop reports and waits.

### 5.1 Part A: cards and the scope check

**B0, the premises.**

    test -f $M9/VARKA-658.md && echo merged                               # 1: merged
    git remote | while read r; do git fetch -q "$r" tag varka-m8 2>/dev/null; done
    git tag -l varka-m8                                                   # 2: varka-m8
    grep -c '^CURRENT = 9$' dev/varka_census.py                           # 3: 1 (row 654 part a)
    ls $M9/census_curated.txt $M9/census.tsv $M9/casts.tsv | wc -l        # 4: 3
    python3 -c "import sys; sys.path.insert(0, 'dev'); import varka_rows as r; print(r.slug('abs(i)'), r.family_slug('Integer arithmetic'))"
                                                                          # 5: abs-i-d1122699 integer-arithmetic (row 653 part A2)
    grep -q "parseRow(String path, String text)" $TJ/VarkaRowFiles.java && echo rows   # 6: rows (row 653 part E)
    grep -q "SKIPS_DIR" $TJ/VarkaMatrixRuns.java && echo skips            # 7: skips (row 653 part F)
    ls dev/varka_fanout.py $F 2>&1 | grep -c "No such file"               # 8: 2
    git branch -a --list '*varka-658a*'                                   # 9: nothing
    git cat-file -t 43cf0d94acc; git cat-file -t 39a404b73ec              # 10: commit, commit
    grep -c '^    r"dev/varka_regen_generated\\.sh\$",$' dev/sparktestsupport/modules.py   # 11: 1
    grep -c '^        dev/varka_prepush.sh --selftest$' .github/workflows/build_and_test.yml   # 12: 1
    grep -c "^    - name: The branch's commit messages and its pull request follow the house rules$" .github/workflows/build_and_test.yml   # 13: 1
    grep -c '^        python3 dev/varka_msg_check.py pr --this-branch$' .github/workflows/build_and_test.yml   # 14: 1
    grep -c "^abs|" $M9/census_curated.txt; grep -c "^make_timestamp_ntz|" $M9/census_curated.txt   # 15: 1, 1
    grep -rh --exclude=VARKA-658.md -F "Declare an emit option in one place, so parallel option rows stop conflicting" $M9 | grep -c -F "**Done**"   # 16: 1 or more (row 642 is done)
    grep -rh --exclude=VARKA-658.md -F "Each row of the task table in its own file, and the table generated from them" $M9 | grep -c -F "**Done**"   # 17: 1 or more (row 646 is done)
    grep -c -F -e '" matches the row engine under both consumers"' -e '" raises under ANSI only where the row engine does"' -e '" is answered inside its domain and where it is unknown, and declined "' -e '"outside with the verdict'"'"'s status, in a projection and a filter"' -e '" splits every fixture into true, false and unknown rows"' -e '" under IS NULL and IS NOT NULL fuses as a CASE condition"' -e '" as a boolean output matches the row engine"' sql/core/src/test/java/org/apache/spark/sql/execution/VarkaCoverageHarnessSuite.java   # 18: 7 (the harness's six sentences, one of them on two lines)

| line | it prints | what the builder does |
| :--- | :--- | :--- |
| 2 | nothing | stop: "no remote has the tag `varka-m8`; milestone 8 is not closed" |
| 3, 4 | `0`, or under 3 | stop: "row 654's part a is not merged" |
| 5, 6, 7 | an error, or nothing | stop: "row 653's part A2, E or F is not merged", with the line |
| 16 | `0` | stop: "row 642 is not done": the owner's answer to question 5 has this row wait for it |
| 17 | `0` | where row 646's plan has merged and added its own grep to this B0, that grep decides. Where it has not: row 646 moves the task table into a file a row, so its title may no longer share a line with its status, and a `0` does not prove the row open. Run `grep -rl --exclude=VARKA-658.md -F "Each row of the task table in its own file, and the table generated from them" $M9`, open each file it prints, and read the row's status there. `**Done**`: go on, and say in the description that premise 17 printed `0` and where the status stands. Any other status, or no file: stop: "row 646 is not done" |
| 18 | not `7` | the harness names a row's tests otherwise. Stop: report `grep -n "DynamicTest.dynamicTest" -A2 sql/core/src/test/java/org/apache/spark/sql/execution/VarkaCoverageHarnessSuite.java`. The sentences of `HARNESS_TESTS` in appendix A.1 and 4.9's second table are then the lead's to correct, before part A is built |

Premises 1 to 10 and 15 to 17 could not print this today: the rows they wait for are not
built. Premises 11 to 14 print `1` on the plan's commit, and premise 18 prints `7`. Premises
16 and 17 read each row's own line, wherever row 646 has put it, for the status `**Done**`,
and leave this file out, which holds both sentences; today both print `0`
(`**Planned**` and `**Filed**`). Row 642's status stays on its row's line, so premise 16 is
safe. Row 646 is the row that moves the table, and it has no plan yet, so nothing it adds can
be named here and its layout is not known: premise 17 can print `0` for a finished row 646,
and the table above says what the builder then does, by hand, without a stop on the grep
alone. When row 646's plan exists, that plan's own pull request adds to this B0 one grep of a
name its last part adds, as premises 5 to 7 do for row 653, and that grep replaces the
reading by hand.

**The steps.**

1. Add `dev/varka_fanout.py`, mode 755, with exactly the text of appendix A.1. `wc -l` of it
   prints `634`.
2. Add the two cards, `$P1` and `$P2`, with exactly the texts of appendix D.
3. In `dev/sparktestsupport/modules.py`, after the line of premise 11, which is in the tuple
   `varka_docs_regexes`, add two lines:

            # The fan-out task's tool (VARKA-658), whose cases and whose check the docs job runs.
            r"dev/varka_fanout\.py$",

   That tuple, so that a change confined to the tool runs the docs job alone: no test JVM
   reads the tool.
4. In `.github/workflows/build_and_test.yml`, three insertions in the job `varka-docs`:
   - after the line of premise 12, one line at the same indentation:

            python3 dev/varka_fanout.py --self-test

   - before the line of premise 13, four lines:

            # The fan-out tasks' files (VARKA-658): what `dev/varka_fanout.py check` holds to the
            # tree, as the tool's own first lines list it.
            - name: The fan-out cards, plans and ledger match the tree
              run: python3 dev/varka_fanout.py check

     In the file the two comment lines and the `- name:` line start at four spaces, as the
     steps around them do, and the `run:` line at six.
   - after the line of premise 14, six lines at that line's indentation, eight spaces:

            # On the branch that a fan-out card names, every changed file is one the card's plan
            # names (VARKA-658).
            if [ -n "$base" ]; then
              branch="${GITHUB_HEAD_REF:-$GITHUB_REF_NAME}"
              python3 dev/varka_fanout.py check --branch "$branch" --base "$base"
            fi

     `$base` is set four lines above, in the same script (`base="$(git merge-base HEAD
     refs/varka/master ...`): the merge base of `HEAD` and Varka's master.
5. Run command 3 and command 4 below into the results file:

        { echo "# python3 dev/varka_fanout.py scope <card> --against <commit>, on $(git rev-parse --short HEAD), $(date +%F)"
          python3 dev/varka_fanout.py scope $P1 --against 43cf0d94acc
          python3 dev/varka_fanout.py scope $P2 --against 39a404b73ec; } > $M9/results/VARKA-658-pilots.txt

6. This file's section 14 and the row's status (5.0).

**The tests.**

| check | where | the cases, with their inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `test_cards` of `--self-test` | the docs job | the card `CARD` of the tool, 24 lines; then sixteen cards each made from it by one change: `row: 8` for `row: 9`; `unit: abs int`; no `coverage-form` line; no `skip-verdict` line; a line `colour: red`; a title with a backtick; no `lowering` line; an `edit` line without its `copy` part; a `sibling` with no test file; no `test` in a test file; that test and a `no-own-test` line together; the `coverage` test named by `partitions()`, a filter's factory, on a projection row; the `coverage` test without its factory; the JUnit test named by a sentence; no `suite` line; a `test` in a file the card does not list | the first parses, with its domain `i > -2147483648`; each of the sixteen raises with its own words (`` `id` must be``, ``is no `entry``, `one family and one form`, `needs suite, test, verdict`, `unknown key`, `the title holds`, `` `lowering` must be given once``, ``is not `where: <place>; when:``, `` `sibling` is``, `main source changes`, `` `no-own-test` is given once``, `is no name the harness gives` twice, `a JUnit test is`, ``no `suite` line names``, `names no test file`) | a card that parses with a field missing, and so a plan with a hole; a task with no test |
| the same, the names | | `names` of `CARD` with a slug rule of the test's own; `CARD` with its test file's test replaced by a `no-own-test` line | 14 files, in the classes `edit`, `add`, `row`, three `record`, `skip`, `curated`, `census`, three `casts`, `host`, `plan`; the row file `sql/varka/rows/integer-arithmetic/s6.row`; the claim parses | a class left out of the list |
| the same, the tests in a change | | a diff of three added lines, a `@DisplayName` written over two of them and `void absOverAnInt() {`; the same with the method renamed; `CARD` with a test of a `.scala` file named by the sentence `abs of an int fuses`; what is sought for `everyCase():t + dt agrees with the row engine`, for `slice(String, VarkaA$Mode):1` and for `absOverAnInt()` in a `.java` file | `added_text` gives `@DisplayName("abs of an int fuses") void absOverAnInt() {`; no test missing; `the test `absOverAnInt()` is not in the lines the change adds to sql/a/src/test/java/VarkaAbsIntSuite.java`; no test missing; ` everyCase(`, ` slice(` and `void absOverAnInt(` | a task that passes with the test it names never written; a sentence split over two source lines read as missing; a factory-made test that no added line could ever hold |
| the same, the harness's names | | `harness_names` of `CARD`'s row, which has a domain and the form `projection`; of a row `d < d2` of the family `Predicates` with the form `predicate` and no domain | three names, of `everyRow`, `ansiForm` and `domains`, the last ending in the `domains()` tail of 4.9; five names, one of them `partitions():Predicates: d < d2 splits every fixture into true, false and unknown rows` | a card that names a harness test the harness never makes, which the `suites` step would then never find |
| the same, the moved paths | | `classify` of `.../varka/VarkaCoverageSuite.scala`, `docs/sql-varka.md` and `sql/varka/plans/m8/VARKA-621.md`, each with and without `moved`; of `.../VarkaExpressionCompilerSuite.scala` with a plan that has a test file and with one that has none; of a path in no table; of `sql/varka/rows/_admitted/Abs.json`; of `sql/varka/rows/_audit.json` | `row`, `lead`, `plan` with `moved` and none without; `suite` and none; none; `record`; `host` | a moved path accepted on a live branch, where it is a file no task may touch |
| command 3 and 4 | by hand; `check` in the docs job | the two pilot cards against their commits | the two blocks below, and no `fanout: scope: the test` line: pilot 2's six tests are in its commit's added lines | a file of a real pull request that the plan cannot name; a test the card names that the pull request never added |
| command 5, five planted faults | by hand | below | each fails as written | a check that cannot fail |

**The commands.**

    python3 dev/varka_fanout.py --self-test                               # 1: varka_fanout self-test: ok (1 groups)
    python3 dev/varka_fanout.py check; echo "exit $?"                     # 2: fanout: check: 0 error(s), then exit 0
    python3 dev/varka_fanout.py scope $P1 --against 43cf0d94acc; echo "exit $?"   # 3: the first block below, then exit 0
    python3 dev/varka_fanout.py scope $P2 --against 39a404b73ec; echo "exit $?"   # 4: the second block below, then exit 0
    # 5: the planted faults, after the part's commit; each line's comment is what it prints
    sed '/VarkaVectorWalk.java/d' $P1 > $X/VARKA-621-abs-int.txt
    python3 dev/varka_fanout.py scope $X/VARKA-621-abs-int.txt --against 43cf0d94acc | tail -1   # fanout: scope: 13 file(s), 1 not named by the plan
    sed '/^unit: cast time ntz$/d' $P2 > $X/VARKA-340-time-to-ntz.txt
    python3 dev/varka_fanout.py scope $X/VARKA-340-time-to-ntz.txt --against 39a404b73ec | grep -c "NOT NAMED"   # 2: casts.tsv and casts_fused.tsv
    sed 's/^row: 621$/row: 620/' $P1 > $X/VARKA-621-abs-int.txt
    python3 dev/varka_fanout.py scope $X/VARKA-621-abs-int.txt; echo "exit $?"   # fanout: target/varka-658/VARKA-621-abs-int.txt: `id` must be VARKA-<row>-<words>, at most 60 characters; exit 2
    touch dev/x658.txt; python3 dev/varka_fanout.py scope $P1 --base HEAD; echo "exit $?"; rm dev/x658.txt   # dev/x658.txt<TAB>NOT NAMED; fanout: scope: 1 file(s), 1 not named by the plan; exit 1
    sed 's/| timeToNtz()$/| timeToLtz()/' $P2 > $X/VARKA-340-time-to-ntz.txt
    python3 dev/varka_fanout.py scope $X/VARKA-340-time-to-ntz.txt --against 39a404b73ec > $X/t.txt; echo "exit $?"; tail -2 $X/t.txt   # exit 1; fanout: scope: the test `timeToLtz()` is not in the lines the change adds to sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTimestampNtzCastSuite.java; fanout: scope: 23 file(s), 0 not named by the plan
    python3 -m doctest dev/sparktestsupport/modules.py; python3 -c "import sys; sys.path.insert(0, 'dev'); from sparktestsupport import modules as m; print(m.varka_change_scope(['dev/varka_fanout.py']), m.varka_docs.contains_file('dev/varka_fanout.py'))"   # 6: no doctest failure; docs True
    ruff check dev/varka_fanout.py; ruff format --check dev/varka_fanout.py   # 6a: All checks passed!; 1 file already formatted
    python3 dev/varka_msg_check.py size                                   # 7: a count under 950

Command 3 prints, a tab between a path and its class:

    docs/sql-varka.md	lead
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaNodeCompiler.java	edit
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaVectorIR.java	edit
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaVectorWalk.java	edit
    sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCoverageSuite.scala	row
    sql/varka/coverage.json	record
    sql/varka/emitted_bytes.json	record
    sql/varka/matrix/skips.tsv	skip
    sql/varka/plans/m8/PLAN.md	lead
    sql/varka/plans/m8/VARKA-621.md	plan
    sql/varka/plans/m8/census.tsv	census
    sql/varka/plans/m8/census_curated.txt	curated
    sql/varka/width_audit.json	record
    fanout: scope: 13 file(s), 0 not named by the plan

Command 4 prints:

    dev/varka_census.py	edit
    docs/sql-varka.md	lead
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaNodeCompiler.java	edit
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaTimeCompiler.java	edit
    sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaFamily.java	edit
    sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCatalystGrammar.java	edit
    sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTimestampNtzCastSuite.java	edit
    sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/VarkaExpressionCompilerSuite.scala	suite
    sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCoverageSuite.scala	row
    sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaSqlResolve.scala	edit
    sql/core/src/test/java/org/apache/spark/sql/execution/VarkaDomains.java	edit
    sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaTimestampNtzFieldsBaselineBenchmark.java	edit
    sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaTimestampNtzSuite.scala	edit
    sql/varka/coverage.json	record
    sql/varka/emitted_bytes.json	record
    sql/varka/matrix/skips.tsv	skip
    sql/varka/plans/m8/PLAN.md	lead
    sql/varka/plans/m8/VARKA-340.md	plan
    sql/varka/plans/m8/casts.tsv	casts
    sql/varka/plans/m8/casts_fused.tsv	casts
    sql/varka/plans/m8/census.tsv	census
    sql/varka/plans/m8/census_curated.txt	curated
    sql/varka/width_audit.json	record
    fanout: scope: 23 file(s), 0 not named by the plan

**The cut line.** The part is estimated at 755 counted lines. If command 7 prints over 950,
cut it in two by file. The first pull request holds the tool and the edit of `modules.py`
(steps 1 and 3); its commands are 1, 2, 6 and 6a, and command 2 prints `fanout: check: 0
error(s)` there, since no card exists yet. The second holds the two cards, the workflow's
three insertions, the results file, this file's section 14 and the row's status (steps 2, 4,
5 and 6); its commands are 2 to 5 and 7. Each is green alone: the first adds a tool that
only a hand runs, and the second lets the docs job run it.

**Done when** commands 1 to 4 print as written, each planted fault fails as written, the
results file is committed and the Build is green.

**The expectations, to tick.**

- [ ] `dev/varka_fanout.py` exists, is executable, has `check`, `scope` and `--self-test`, and
  exits 2 with its usage on any other command.
- [ ] The two cards parse, and each pilot's commit touched only files its plan names: 13 of
  13 and 23 of 23. Pilot 2's six tests are in its commit; pilot 1 claims `no-own-test`.
- [ ] `git status --short` before the commit: `A dev/varka_fanout.py`,
  `M dev/sparktestsupport/modules.py`, `M .github/workflows/build_and_test.yml`, `A` the two
  cards, `A sql/varka/plans/m9/results/VARKA-658-pilots.txt`, `M` the row's file,
  `M sql/varka/plans/m9/VARKA-658.md`, and no other line.
- [ ] No generated file, no census cell, no emitted byte and no benchmark changes.
- [ ] The docs job of the part's Build runs the three insertions; its log shows `fanout:
  check: 0 error(s)` twice and `varka_fanout self-test: ok (1 groups)`.

**Not here.** The template, `plan`, `files`; the oracle; the ledger; the guide; any file under
`sql/catalyst` or `sql/core`.

### 5.2 Part B: the template and the generated plan

**B0, the premises.**

    grep -c "^# ---- The command line (part A) " dev/varka_fanout.py      # 1: 1 (part A merged)
    grep -c "^# ---- Plans (part B) " dev/varka_fanout.py                 # 2: 0
    ls sql/varka/plans/TEMPLATE_FANOUT.md $F/VARKA-621-abs-int.md 2>&1 | grep -c "No such file"   # 3: 2
    grep -c "^  dev/varka_fanout.py scope CARD \[--against REV\]    # is every changed file one the plan names?$" dev/varka_fanout.py   # 4: 1
    grep -c "^Exit status: 0 passed; 1 a check or a step failed; 2 a usage error or a card that does not$" dev/varka_fanout.py   # 5: 1
    git branch -a --list '*varka-658b*'                                   # 6: nothing
    git cat-file -t 230773d872d; git cat-file -t 77a323f0da3              # 7: commit, commit
    python3 dev/varka_fanout.py check | tail -1                           # 8: fanout: check: 0 error(s)

**The steps.**

1. In `dev/varka_fanout.py`, insert the block of appendix A.2 before the line of premise 1,
   with one empty line between the block's last line and that line.
2. In the tool's docstring, after the line of premise 4, add two lines:

          dev/varka_fanout.py plan CARD [--write|--check]   # print, write or compare the task's plan
          dev/varka_fanout.py files CARD                    # write the files the plan gives whole

   and before the line of premise 5, add these two paragraphs, with the one empty line between
   them, and one empty line after them:

        `plan` renders sql/varka/plans/TEMPLATE_FANOUT.md with the card and the tree's own facts (the
        census line of each entry at the branch's merge base, the names VARKA-653's layout gives a
        coverage row's files) into `<id>.md` beside the card. `files` writes the files that plan gives
        word for word: the coverage rows, the skip file, the census's lines.

        `check` also holds every path and every `Varka...` class that sql/varka/ADDING_AN_EXPRESSION.md
        and the template name in backticks to the tree, and every house rule the template quotes to the
        AGENTS.md files.

   `wc -l dev/varka_fanout.py` then prints `1050`.
3. Add `sql/varka/plans/TEMPLATE_FANOUT.md` with exactly the text of appendix C.
4. Generate the two pilots' plans: `python3 dev/varka_fanout.py plan $P1 --write` and the same
   for `$P2`. Each prints `fanout: wrote <the plan's path>`.
5. This file's section 14 (5.0).

**The tests.**

| check | where | the cases, with their inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `test_names` of `--self-test` | the docs job | a text of eight tokens in backticks and a list of three files: `dev/varka_emit.sh`, `sql/x/VarkaNodeCompiler.java`, `sql/varka/skills/a.md` | exactly two errors: `` `VarkaLost.emitValue`: no source file is named VarkaLost`` and `` `dev/varka_gone.sh` is no file or directory of the tree``; `sql/varka/skills/`, `VarkaNodeCompiler`, `VarkaNodeCompiler.java`, `m4/PLAN.md` and `<Class>.java` give none | a guide that names a deleted script or class |
| the same, the quotes | | the rule text "**Never `git stash`.** The stash list is one per repository."; a quote of it over two `> ` lines; a quote "Never stash anything." | no error; one error | a template that quotes a rule the house no longer has |
| `test_plans` | the docs job | the line `abs\|NUM,IVL\|same\|byte,short,bigint\|semantics\|330,104\|the minimum overflows`, the type `int`, the row `621`; the same edit again; the line `abs\|NUM\|same\|byte,dec^\|semantics\|330\|note` with `dec` | `...bigint,int\|semantics\|330,104,621\|...`; nothing; nothing | a type added twice, or a `^` type read as unfused |
| the same, what `files` writes | | `CARD` over a curated text of three lines; the result fed in again; the units' edit asked of the edited text | a row file that starts `sql: abs(i)`, a skip line with four tabs, the census with one line changed; nothing to write the second time; `is fused over `int` already` | a second run that doubles an edit; a skip file with spaces for tabs |
| commands 3 to 5 | by hand | the two pilots' plans | below | a plan that differs from its card |
| command 6, three planted faults | by hand | below | each fails as written | a check that cannot fail |

**The commands.**

    python3 dev/varka_fanout.py --self-test                               # 1: varka_fanout self-test: ok (3 groups)
    python3 dev/varka_fanout.py check | tail -1                           # 2: fanout: check: 0 error(s)
    python3 dev/varka_fanout.py plan $P1 --check; python3 dev/varka_fanout.py plan $P2 --check   # 3: fanout: <each plan's path> is current
    grep -c "^| [0-9]* | " $F/VARKA-621-abs-int.md $F/VARKA-340-time-to-ntz.md   # 4: 12 and 30
    head -1 $F/VARKA-621-abs-int.md | cut -c1-100                         # 5: <!-- Generated by dev/varka_fanout.py plan from sql/varka/plans/m9/fanout/VARKA-621-abs-int.txt,
    # 6: the planted faults; put each file back with `git checkout <file>` after its line
    sed -i 's/^> \*\*Never `git stash`.\*\*$/> **Never stash.**/' sql/varka/plans/TEMPLATE_FANOUT.md; python3 dev/varka_fanout.py check | head -1   # fanout: sql/varka/plans/TEMPLATE_FANOUT.md: no AGENTS.md file holds the quoted rule: **Never stash.**
    echo '{{nothing}}' >> sql/varka/plans/TEMPLATE_FANOUT.md; python3 dev/varka_fanout.py plan $P1 > /dev/null; echo "exit $?"   # fanout: sql/varka/plans/TEMPLATE_FANOUT.md: placeholders and the generator's values differ: ['nothing']; exit 2
    sed -i '0,/^You fuse the pairs/s//You fuse the units/' $F/VARKA-621-abs-int.md; python3 dev/varka_fanout.py plan $P1 --check; echo "exit $?"   # fanout: sql/varka/plans/m9/fanout/VARKA-621-abs-int.md is behind its card or the tree; exit 1
    python3 dev/varka_fanout.py files $P1; echo "exit $?"                 # 7: fanout: sql/varka/plans/m9/fanout/VARKA-621-abs-int.txt: a pilot's card of a merged entry writes no file; exit 2
    ruff check dev/varka_fanout.py; ruff format --check dev/varka_fanout.py   # 7a: All checks passed!; 1 file already formatted
    python3 dev/varka_msg_check.py size                                   # 8: a count under 950; the two plans count nothing

The plan of pilot 1 is 463 lines and that of pilot 2 is 505 (`wc -l`), where the sibling's
commit and the template are those of this plan. Command 4 counts the lines of each plan's
table of files: pilot 1 has three files of its own, a row file, three records, a skip file,
the curated census, the census, the audit's head and the plan itself; pilot 2 has ten of its
own, three row files, nine records, a skip file, the curated census, the census, the three
cast files, the audit's head and the plan.

**The cut line.** The part is estimated at 830 counted lines. If command 8 prints over 950,
cut it in two at the block's second header, `# ---- Plans (part B) `. The first pull request
holds the block's first 85 lines, to the line `SELF_TESTS.append(test_names)` (the names and
the quoted rules, with `test_names`), inserted as step 1 says; the docstring's second
paragraph alone, the three lines that start "`check` also holds every path", with one empty
line after them; and the template. It adds neither usage line and not the first paragraph:
that pull request builds neither `plan` nor `files`, and the docstring lists only what is
built. The tool then has 724 lines; its commands are 1 (`ok (2 groups)`), 2, 6's first line
and 7a. The second holds the rest of the block, from its line 87, inserted before the same
line of premise 1; the docstring's two usage lines; the docstring's first paragraph, the four
lines on `plan` and `files`, before the line that starts "`check` also holds every path",
with one empty line after it; and the two generated plans. The tool then has the 1050 lines
of the whole part; its commands are 1 (`ok (3 groups)`) and 3 to 8. Each is green alone:
`check` reads the template in the first, and `plan` exists in the second. Both halves were
built and run on a scratch copy (3.4).

**Done when** commands 1 to 5 and 7 print as written, each planted fault fails as written,
the two plans are committed and the Build is green.

**The expectations, to tick.**

- [ ] `plan` and `files` exist; `plan` with no flag writes no file.
- [ ] Each pilot's plan is committed, starts with the "Generated by" line and ends with the
  end marker, the Outcome heading, the stub's sentence and the three empty fields. Section 2
  of each marks one file of the sibling as the one to copy the tests' shape from. Section 8
  names pushing and `gh` only to forbid them, and holds neither as a command: `sed -n '/^##
  8\./,/^<!-- end/p' $F/VARKA-621-abs-int.md | grep -c "git push\|gh "` prints `0`.
- [ ] `git status --short` before the commit: `M dev/varka_fanout.py`,
  `A sql/varka/plans/TEMPLATE_FANOUT.md`, `A` the two plans, `M
  sql/varka/plans/m9/VARKA-658.md`, and no other line.
- [ ] The census, the records and every source file are unchanged: `files` ran on no card.
- [ ] The docs job's `check` now also reads the committed guide and the template; the guide
  as it is passes (fact 9).

**Not here.** The oracle; the ledger; the guide's text; a card of row 659.

### 5.3 Part C: the oracle

**Who builds it.** The lead, or an agent at full strength: this part runs three JVM commands
that no one has run, and it may repair their command lines. It is not given to a cheaper
model. Everything else of the part is typed as given.

**B0, the premises.**

    grep -c "^# ---- Plans (part B) " dev/varka_fanout.py                 # 1: 1 (part B merged)
    grep -c "^# ---- The oracle (part C) " dev/varka_fanout.py            # 2: 0
    grep -c "^  dev/varka_fanout.py files CARD                    # write the files the plan gives whole$" dev/varka_fanout.py   # 3: 1
    grep -c "case Abs a when isInt(a) ->" $GEN/VarkaNodeCompiler.java     # 4: 1
    sed -n '/case Abs a when isInt(a) ->/,/^      });$/p' $GEN/VarkaNodeCompiler.java | wc -l   # 5: 4
    python3 -c "import sys; sys.path.insert(0, 'dev'); import varka_rows as r; print(r.slug('abs(i)'))"; ls sql/varka/rows/integer-arithmetic/abs-i-d1122699.json   # 6: abs-i-d1122699, and the record's path
    grep -c 'test("every documented expression compiles to a fused kernel")' sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCoverageSuite.scala   # 7: 1
    grep -c 'usage: dev/varka_regen_generated.sh \[--all \[--full\]\] \[--dry-run\]' dev/varka_regen_generated.sh   # 8: 1
    grep -c '"--no-jvm"' dev/varka_push_check.py                          # 9: 1
    python3 dev/varka_fanout.py plan $P1 --check                          # 10: fanout: sql/varka/plans/m9/fanout/VARKA-621-abs-int.md is current
    git branch -a --list '*varka-658c*'                                   # 11: nothing
    python3 dev/varka_calibration.py | grep -c "VARKA-658"                # 12: 0: the row has no calibration entry yet
    "$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | grep "java.runtime.version"   # 13: java.runtime.version = 25.<the rest>
    grep -c 'row.sql() + " is answered inside its domain and where it is unknown, and declined "' sql/core/src/test/java/org/apache/spark/sql/execution/VarkaCoverageHarnessSuite.java   # 14: 1
    python3 -c "import json; print(json.load(open('sql/varka/rows/_audit.json'))['host']['jdk'])"   # 15: a version; note whether it is premise 13's
    grep -c -F 'env "${envs[@]}" flock "$lock" build/sbt "${steps[@]}"' dev/varka_regen_generated.sh   # 16: 1
    grep -c -F -- '-batch) exec </dev/null && shift ;;' build/sbt         # 17: 1

| line | it prints | what the builder does |
| :--- | :--- | :--- |
| 4, 5 | `0`, or not `4` | stop: "pilot 1's arm has moved"; report `grep -n "case Abs" $GEN/VarkaNodeCompiler.java` |
| 7 | `0` | stop: "the coverage suite's test has another name"; report `grep -n 'test("every' ` of the suite |
| 10 | `is behind its card or the tree` | run `python3 dev/varka_fanout.py plan $P1 --write`, commit it with this part, and say so in the description: a merge of master changed the line of `abs` in the curated census |
| 13 | another version, or nothing | `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` and ask again; still not 25: stop |
| 14 | `0` | the harness names a row's test otherwise than part A's premise 18 read. Read the `@TestFactory` method `domains` in that file; put the sentence it gives a row in the `domains` line of `HARNESS_TESTS` in the tool (one of the repairs below), and the name it gives the row `abs(i)` in the `test` line of `$P1`; run `--self-test` and `plan $P1 --write`; say so in the description |
| 15 | an error | stop: "row 653 keeps the audit's host elsewhere"; report `ls sql/varka/rows/_*.json` |

Premises 4, 5, 7 to 9, 14, 16 and 17 print this on the plan's commit; premise 13 printed
`java.runtime.version = 25.0.5.1+2-1-26.04.1-Ubuntu` on the laptop on 27 November 2026, which
is the `host.jdk` of the committed width audit.

**The steps.**

1. In `dev/varka_fanout.py`, insert the block of appendix A.3 before the line that starts `#
   ---- The command line (part A) `, with one empty line after the block.
2. In the docstring, after the line of premise 3, add two lines:

          dev/varka_fanout.py oracle CARD [--only STEPS] [--full-regen]   # is the task done?
          dev/varka_fanout.py ready CARD --body-file FILE   # may the pull request open, or merge?

   and before the line that starts `Exit status: 0 passed;`, add this paragraph and one empty
   line after it:

        `oracle` is the one command that says whether the task is done: it runs its steps in order,
        stops at the first that fails and exits 1 naming it, and on success prints `fanout: PASSED <id>
        tree <tree> in <n> s`, the line a pull request's body carries. `ready` reads that line back
        against the head.

   `wc -l dev/varka_fanout.py` then prints `1600`.
3. Commit. The runs below need a clean worktree whose head has the tool.
4. **Run 1, the oracle passes on the merged entry.** Check that the lock is free (`flock -n
   "$LOCK" true && echo free` prints `free`; if it prints nothing, wait five minutes and ask
   again), note `uptime`, then:

        python3 dev/varka_fanout.py oracle $P1 --base HEAD --full-regen > $X/run1.txt 2>&1; echo "exit $?" >> $X/run1.txt
        git status --short >> $X/run1.txt; cat $X/run1.txt

   `--base HEAD` makes the branch's own head the base, so the part's own files are no change of
   the task. `--full-regen` runs every regeneration though nothing changed, which is what a
   task with a new arm pays. **What run 1 must print**, and nothing else:

        fanout: VARKA-621-abs-int step card: passed in <n> s
        fanout: VARKA-621-abs-int step scope: passed in <n> s
        fanout: VARKA-621-abs-int step fused: passed in <n> s
        fanout: VARKA-621-abs-int step regen: passed in <n> s
        fanout: VARKA-621-abs-int step suites: passed in <n> s
        fanout: VARKA-621-abs-int step checks: passed in <n> s
        fanout: bench: no benchmark section: no baseline class holds an int abs, and the pull request merged with none
        fanout: VARKA-621-abs-int step bench: passed in <n> s
        fanout: PASSED VARKA-621-abs-int tree <40 hex> in <n> s
        exit 0

   and no line of `git status --short` after `exit 0`: a regeneration of an unchanged tree on
   the JDK of premise 15 writes nothing. Where it does not print this, go to "The repairs"
   below.
5. **Run 2, it fails with the kernel reverted.** Delete the arm, run, and put the file back:

        sed -i '/case Abs a when isInt(a) ->/,/^      });$/d' $GEN/VarkaNodeCompiler.java
        git diff --stat | tail -1                                         #  1 file changed, 4 deletions(-)
        python3 dev/varka_fanout.py oracle $P1 --base HEAD > $X/run2.txt 2>&1; echo "exit $?" >> $X/run2.txt; cat $X/run2.txt
        git checkout $GEN/VarkaNodeCompiler.java; git status --short      # nothing

   **What run 2 must print**, and nothing else:

        fanout: VARKA-621-abs-int step card: passed in <n> s
        fanout: VARKA-621-abs-int step scope: passed in <n> s
        fanout: fused: a coverage row does not fuse
        fanout: VARKA-621-abs-int step fused: FAILED in <n> s
        fanout: FAILED VARKA-621-abs-int at fused in <n> s (logs under target/varka-fanout/VARKA-621-abs-int)
        exit 1

   The `scope` step passes because the one changed file is an `edit` file of the plan. The
   step's log holds the suite's failure, "documented as covered, but the compiler declines:",
   and under it the row: `grep -c "Integer arithmetic: abs(i)  ->"
   target/varka-fanout/VARKA-621-abs-int/fused.log` prints 1 or more. If that grep prints 0,
   the step failed for another reason (a compile error, a lock): that is not the proof, and
   "The repairs" applies. So does it where the line before `step fused: FAILED` is not `a
   coverage row does not fuse` but one of the report's lines (4.5): sbt exited 0, so no test
   failed, and that is not the proof either.

   **Run 2b, a filter that matches no test.** sbt exits 0 when `-z` matches nothing, and the
   step must fail all the same. Change the sentence, run the one step, and put the file back:

        sed -i 's/^FUSED_TEST = "every documented/FUSED_TEST = "no documented/' dev/varka_fanout.py
        git diff --stat | tail -1                                         #  1 file changed, 1 insertion(+), 1 deletion(-)
        python3 dev/varka_fanout.py oracle $P1 --base HEAD --only fused > $X/run2b.txt 2>&1; echo "exit $?" >> $X/run2b.txt; cat $X/run2b.txt
        git checkout dev/varka_fanout.py; git status --short              # nothing

   **What run 2b must print:** one of the first two lines, then the other three:

        fanout: fused: VarkaCoverageSuite wrote no report in this run: sbt ran no test of it
        fanout: fused: the test `no documented expression compiles to a fused kernel` did not run and pass in VarkaCoverageSuite
        fanout: VARKA-621-abs-int step fused: FAILED in <n> s
        fanout: FAILED VARKA-621-abs-int at fused in <n> s (logs under target/varka-fanout/VARKA-621-abs-int)
        exit 1

   and `grep -c "No tests were executed" target/varka-fanout/VARKA-621-abs-int/fused.log`
   prints 1 or more. Where the first line is `fanout: fused: a coverage row does not fuse`,
   sbt exited non-zero on a filter that matches nothing: the step failed as it must, and the
   results file says so under run 2b. Where the step passes, stop: the report's check does
   not work, and that is no repair of a command line.

   **Run 2c, a test of the card that no suite ran.** A copy of the card names a coverage row
   the harness does not have. This runs the three coverage suites once more, so it takes
   about as long as run 1's `suites` step:

        sed 's/abs(i)/abs(j)/' $P1 > $X/VARKA-621-abs-int.txt
        python3 dev/varka_fanout.py oracle $X/VARKA-621-abs-int.txt --base HEAD --only suites > $X/run2c.txt 2>&1; echo "exit $?" >> $X/run2c.txt; cat $X/run2c.txt

   **What run 2c must print**, and nothing else:

        fanout: suites: the test `domains():Integer arithmetic: abs(j) is answered inside its domain and where it is unknown, and declined outside with the verdict's status, in a projection and a filter` did not run and pass in VarkaCoverageHarnessSuite
        fanout: VARKA-621-abs-int step suites: FAILED in <n> s
        fanout: FAILED VARKA-621-abs-int at suites in <n> s (logs under target/varka-fanout/VARKA-621-abs-int)
        exit 1
6. **Run 3, `ready`.** Write a body and ask:

        T=$(git rev-parse 'HEAD^{tree}')
        printf '### How was this patch tested?\n\n%s\n\nGenerated-by: Claude Code (the model of this session)\n' "$(grep '^fanout: PASSED' $X/run1.txt)" > $X/body.md
        grep -c "tree $T in" $X/body.md                                   # 1: run 1 passed on the head's tree
        python3 dev/varka_fanout.py ready $P1 --base HEAD --body-file $X/body.md | grep "^fanout: ready"

   The house's message check fails on this body, which has one heading of five. So the last
   command prints exactly one line, `fanout: ready: dev/varka_msg_check.py pr failed`: the
   tree, the scope, the worktree and the attribution passed, the branch has no upstream and
   no remote holds a commit of it, and the body's check ran. A second line, `fanout: ready: the
   branch has an upstream, ...`, says that the part's branch was made without `--no-track`
   (5.0): run `git branch --unset-upstream` and the command again.
   Then `echo >> dev/varka_fanout.py; python3 dev/varka_fanout.py ready $P1 --base HEAD
   --body-file $X/body.md | grep -c "uncommitted changes\|the head's tree"; git checkout
   dev/varka_fanout.py` prints `1`: a dirty worktree is named.
7. Write `$M9/results/VARKA-658-oracle.txt`: one first line `# python3 dev/varka_fanout.py
   oracle on VARKA-621-abs-int, on <the commit's short hash>, <the date>, the laptop (<the
   CPU's model from /proc/cpuinfo>), load <the first number of uptime> before run 1`; then
   `$X/run1.txt` under the line `## run 1: the merged entry, every regeneration forced`; then
   `$X/run2.txt` under the line `## run 2: the arm of Abs over an int deleted`; then
   `$X/run2b.txt` under `## run 2b: a -z sentence that matches no test` and `$X/run2c.txt`
   under `## run 2c: a card's test that no suite ran`; then, where a repair was made, a
   section `## repairs` (below).
8. Score prediction 1 of section 7 from run 1's last line, and add the calibration entry:
   `python3 dev/varka_calibration.py --add-row "| VARKA-658 (7 #1) | the oracle on one merged
   entry, every regeneration forced, takes 12 to 30 minutes on the laptop | <n> s, by steps
   <the seven steps' seconds> | <n / 1200, two digits> | <one sentence: which step was most
   of it> |"`, which writes one file under `sql/varka/skills/calibration/`.
9. This file's section 14 (5.0).

**The repairs: what the builder may change when a JVM step fails on a correct tree.** The
tool's text is fixed, with one exception, and this is all of it.

- *May change:* inside `step_fused`, `step_regen` and `step_suites` of the block, the list
  `command` (a flag, a module, a suite's name, the order); the constant `FUSED_TEST`; an
  environment variable passed to `run`; the two folder names of `REPORT_DIRS`. Outside the
  block, three places that repeat those command lines or that the table below names: in
  `render` of part B's block, the profile `"-Phive "` in the `sql` entry of the loop that
  builds `own`; in part A's block, a sentence of `HARNESS_TESTS`; and in the template's
  section 4 and this plan's 4.5, the same command lines and the words "under `-Phive`". Each
  change is made in the tool and in the template in one commit, `plan --write` is run for
  both pilots, and `--self-test` and `check` are run again.
- *May not change:* what a step proves (which test must pass, which files may change, which
  tests must be in the reports, that a report must be this run's); a step's failure texts; the
  lines the oracle prints; the exit
  statuses; any step that starts no JVM (`card`, `scope`, `checks`, `bench`); `ready`; the
  JDK's check; the lock.
- *Records:* each change goes in the results file's section `## repairs` with the failing
  command, the last 30 lines of its log, the new command and the run that then passed; in this
  file's section 14; and in the description.

| run 1 fails at | the log holds | the repair |
| :--- | :--- | :--- |
| `fused` | `the JDK is ..., not a JDK 25` (the oracle's own line, no log) | no change of the tool: export `JAVA_HOME` as premise 13 says, run again |
| `fused` | sbt's `Not a valid command`, `Not a valid key` or `Expected` at `-z` or at a quote | the oracle passes sbt one argument with no shell, and it is the text between the single quotes of the root `AGENTS.md`'s form. Print it (`python3 -c "import sys; sys.path.insert(0, 'dev'); import varka_fanout as f; print(f.FUSED_TEST)"`); where the sentence there is not premise 7's, correct `FUSED_TEST`; where it is, stop, with the log |
| `fused` (run 1, or run 2 with a line other than `a coverage row does not fuse`) | sbt exited 0, and the oracle prints `VarkaCoverageSuite wrote no report in this run` or ``the test `every documented ...` did not run and pass in VarkaCoverageSuite`` | `grep -c "No tests were executed" target/varka-fanout/VARKA-621-abs-int/fused.log`. `1` or more: sbt ran no test, so the suite's name or the test's sentence differs from premise 7: stop, with the output of premise 7. `0`, and the log holds `Tests: succeeded 1`: the test ran and the report is elsewhere or names it otherwise. `ls -t sql/catalyst/target/test-reports \| head -3` and `grep -o 'name="every documented[^"]*"' sql/catalyst/target/test-reports/TEST-*VarkaCoverageSuite.xml`: where the folder is another, change `REPORT_DIRS`; where the name differs from the sentence, stop, with both |
| `fused` | a test failure of "every documented expression compiles to a fused kernel" | master itself has a row that does not fuse: stop; it is no fault of the command |
| `regen` | `varka regen: skipping width_audit.json, owed on its host` | this machine is not the audit's host: stop; part C runs on the laptop |
| `regen` | the script's own failure (`set -e`), sbt's last lines | run `dev/varka_regen_generated.sh --all --full --dry-run`, which prints the script's sbt command and runs nothing; where the oracle's `command` is not `dev/varka_regen_generated.sh --all --full`, make it so; where it is, the regeneration fails on master: stop |
| `regen` | no failure of the script; the oracle prints `sql/varka/rows/_audit.json changed beyond its `host` block`, or names another file as changed | on an unchanged tree the regeneration wrote a file. `git diff --stat`: where every changed file is an `.audit.json` record or the head, the JDK of premise 13 is not the one the records were taken on and lowers a shape otherwise; where it is another file, a record on master is stale. Either way stop: the refresh is a pull request of the lead's own, and run 1 waits for it |
| `regen` | passed, with the line `the audit's host moved with the JDK` | no failure: premise 15 differed from premise 13. `git diff` shows the `host` block alone. Commit the head in this part (`git add sql/varka/rows/_audit.json`, the message `Carry the width audit's host to JDK <version>`), and run 1 again: it must then end clean |
| `suites` | `-Phive` fails to resolve or to load the build (`unresolved dependency`, `not found: ... hive`, `Project loading failed`) | drop `"-Phive"` from `command` in `step_suites`; make `"-Phive "` the empty string in the `sql` entry of the loop that builds `own` in `render`; and take the words "under `-Phive`," out of the template's section 4, step 4, the sentence on the `suites` step: the regeneration script runs `sql/testOnly` with no profile (line 95) |
| `suites` | sbt passed, and the oracle prints ``the test `domains():Integer arithmetic: abs(i) ...` did not run and pass in VarkaCoverageHarnessSuite``, or `<suite> wrote no report in this run` | the report's name or place differs from facts 34 and 35. `ls -t sql/core/target/test-reports \| head -3` and `grep -o 'name="[^"]*abs(i) is answered[^"]*"' sql/core/target/test-reports/TEST-*VarkaCoverageHarnessSuite.xml`: where the name there differs from the card's `test` line, correct the sentence in the `domains` line of `HARNESS_TESTS` and then the card's line (the names are wrong, not the step), and run `--self-test`; where the folder is another, change the two folder names of `REPORT_DIRS`: that changes where the proof is read, not what is proved |
| `suites` | a suite's test fails | master is red in that suite: stop, with the test's name |
| any | `flock: ...: No such file or directory` | make the lock's folder: `mkdir -p "$(dirname "$LOCK")"`; run again |
| any | nothing for 30 minutes and the step's log does not grow | the lock is held by another run (`flock -n "$LOCK" true` prints nothing): wait; kill nothing |

**The stop.** After two repairs of one step, or a failure that no line above names, the part
stops. Report: the step; the command as the oracle ran it (`grep -n "command" ` of the
step's function); the last 60 lines of `target/varka-fanout/VARKA-621-abs-int/<step>.log`;
premises 13 and 15; `git status --short`; and which line of the table was tried. The part's
branch stays as it is, with steps 1 to 3 committed.

**The tests.**

| check | where | the cases, with their inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `test_oracle` of `--self-test`, the results reader | the docs job | a results text of two sections, the first titled `INT abs(i), no nulls` with a `varka on` line at `1.1X`; asked for that title and for `INT abs` | `{"spark row path (varka off)": 1.0, "varka on": 1.1}`; nothing for the title that is only a prefix | a section read by a title's prefix, so another section's ratio |
| the same, the steps | | three steps, the second failing, with a clock that gives 0, 5, 10, ...; then the same with `--only card,regen` | the first run stops at `fused` after `card` and `fused` ran, in 25 s, and prints `fanout: T step fused: FAILED in 5 s`; the second runs `card` and `regen` and fails nothing | an oracle that runs on after a failure |
| the same, `ready` | | a body with one `PASSED` line of the card's id and a tree of forty `a`, and one `Generated-by` line; the same with a head tree of forty `b`; with the line's prefix cut, a dirty worktree and two scope errors, a file's and a test's; with `Made-by`; with the upstream `refs/remotes/origin/master`; with a commit of the branch on `refs/remotes/fork/varka-9a-abs-int`; with a false `pushed:` commit of forty `c`; the pattern of the lead's line on a findings text that starts `pushed: ` and forty `c`, and on one that has that line second | no reason; one; four, the last two the scope errors' own lines; one; one that holds `has an upstream, refs/remotes/origin/master`; one that holds `the branch is on refs/remotes/fork/varka-9a-abs-int`; one that holds `says the lead pushed` and the forty `c`; the forty `c`; no match | a pull request whose head is not the tree the oracle passed on; a `ready` that says "an error of scope" and not which; a builder that pushed and still reads `READY`; a `pushed:` line read from the middle of the lead's findings |
| the same, the JDK | | the versions `25.0.5.1+2-1-26.04.1-Ubuntu`, `25+36-LTS`, `26.0.1+8` and none | no error, no error, one, one that holds `the JDK is None` | a regeneration on the wrong JDK, which would rewrite every audit record |
| the same, the host | | a head of three keys; the same with `host.jdk` changed; with a width's name changed | the first pair differs in `host` alone; the second does not | a task that carries a change of the audit's head beyond the host |
| the same, the Outcome | | a plan that ends with what `plan --write` leaves, for a pilot and for a task; the same with text after each field, one of them on the line under its field; with the line of `- Plan gaps:` deleted; with all below the end marker deleted, with no end marker, and with the heading reworded to `## Outcome` | nothing; three errors, the first ending ``the field `- Built:` is empty``; nothing; ``section 9, the Outcome, is not written: no field `- Plan gaps:` ``; ``section 9, the Outcome, is not written: no line `## 9. Outcome` below the end marker``, three times | a `PASSED` line on a tree whose Outcome is written afterwards, so a head with another tree; a builder that deletes the section and passes |
| the same, the reports | | a report of three test cases: `absOverAnInt()` passed, `everyRow():Integer arithmetic: abs(i) matches the row engine under both consumers` as an empty element, `a > b` (escaped) with a `failure`; then the first renamed; then the harness test asked for without its `everyRow()` | the first two are the passed names; `CARD`'s two tests are found, in `VarkaAbsIntSuite` and in `VarkaCoverageHarnessSuite`, and so is "any test" of the suite; then `the test `absOverAnInt()` did not run and pass in VarkaAbsIntSuite`; then one error that holds `did not run and pass in VarkaCoverageHarnessSuite` | an oracle that passes on a task whose test did not run; a name matched by its end, so another factory's test |
| the same, an exit status of 0 with no test | | the `fused` step's test and "any test" asked of a report with no test case, `<testsuite name="s" tests="0"></testsuite>`; the `fused` step's test asked twice of no report | ``the test `every documented expression compiles to a fused kernel` did not run and pass in VarkaCoverageSuite`` and `no test of VarkaCoverageSuite passed in this run's report`; one line, `VarkaCoverageSuite wrote no report in this run: sbt ran no test of it` | a `-z` sentence or a suite's name that matches nothing: sbt exits 0, and the step would pass on no test |
| run 1 | by hand, the laptop | the merged entry | the ten lines above, a clean worktree | an oracle that cannot pass on a correct tree; a regeneration that writes on an unchanged tree; a test of a card that sbt's reports name otherwise |
| run 2, the planted fault | by hand, the laptop | the arm of fact 18 deleted | the six lines above | an oracle that passes with no kernel |
| run 2b, a planted fault | by hand, the laptop | `FUSED_TEST` changed to a sentence no test has | the five lines of step 5, one of the first two | a `fused` step that passes when sbt ran nothing: run 2 could then pass with the kernel deleted |
| run 2c, a planted fault | by hand, the laptop | a card whose coverage row the harness does not have | the four lines of step 5 | a `suites` step that trusts sbt's exit status for a test of the card |
| run 3 | by hand | the head's tree, a dirty worktree | as step 6 says | a stamp that outlives an edit |

**The commands.**

    python3 dev/varka_fanout.py --self-test                               # 1: varka_fanout self-test: ok (4 groups)
    python3 dev/varka_fanout.py oracle $P1 --only card,scope --base HEAD | tail -1   # 2: fanout: VARKA-621-abs-int: card, scope passed; only a run of every step says PASSED
    tail -2 $X/run1.txt                                                   # 3: fanout: PASSED VARKA-621-abs-int tree <40 hex> in <n> s, then exit 0
    grep "^fanout: FAILED" $X/run2.txt                                    # 4: fanout: FAILED VARKA-621-abs-int at fused in <n> s (logs under target/varka-fanout/VARKA-621-abs-int)
    tail -1 $X/run2.txt                                                   # 5: exit 1
    python3 dev/varka_fanout.py oracle $P1 --only nothing; echo "exit $?" # 6: fanout: --only takes steps of: card, scope, fused, regen, suites, checks, bench; exit 2
    python3 dev/varka_calibration.py --check; echo "exit $?"              # 7: exit 0
    ruff check dev/varka_fanout.py; ruff format --check dev/varka_fanout.py   # 7a: All checks passed!; 1 file already formatted
    grep -c "^exit 1$" $X/run2b.txt $X/run2c.txt                          # 5a: 1 for each file
    python3 dev/varka_msg_check.py size                                   # 8: a count under 860

**Done when** run 1 prints its ten lines with a clean worktree, run 2 prints its six lines,
runs 2b and 2c fail as written, the results file with the four runs, run 1's seconds and
every repair is committed, prediction 1
is scored, and the Build is green.

**The expectations, to tick.**

- [ ] `oracle` and `ready` exist, with the exit statuses of 4.5 and 4.6.
- [ ] `git status --short` before the commit: `M dev/varka_fanout.py`, `A
  sql/varka/plans/m9/results/VARKA-658-oracle.txt`, `A` one file under
  `sql/varka/skills/calibration/`, `M sql/varka/plans/m9/VARKA-658.md`, `M` the plan of
  pilot 1 only where premise 10 or 14 asked for it, `M sql/varka/rows/_audit.json` only where
  the host moved, and, where a repair was made, `M sql/varka/plans/TEMPLATE_FANOUT.md` and
  `M` the two generated plans; no other line. `$GEN/VarkaNodeCompiler.java` is as master has
  it.
- [ ] No other generated file, no census cell, no emitted byte and no benchmark changes.
- [ ] The oracle's time is in the results file by step, and section 14 says which step was
  most of it.

**Not here.** A change of any suite, of the regeneration script or of the push check; a
benchmark run; the ledger; a card of row 659; a change of the tool beyond "The repairs".

### 5.4 Part D: the ledger

**B0, the premises.**

    grep -c "^# ---- The command line (part A) " dev/varka_fanout.py      # 1: 1
    grep -c "^# ---- The ledger (part D) " dev/varka_fanout.py            # 2: 0
    ls $F/ledger 2>&1 | grep -c "No such file"                            # 3: 1
    git show -s --format='%ad %s' --date=short 43cf0d94acc                # 4: 2026-11-21 [VARKA-621] Fuse abs over an int (#1584)
    git show -s --format=%b 43cf0d94acc | grep -c "/code-review high (20 November 2026): 6 findings, fixed: 4, kept: 2"   # 5: 1
    git show -s --format=%b 43cf0d94acc | grep -c "^Generated-by: Claude Code (Claude Opus 5.5)$"   # 6: 1
    grep -c -F -- '- **Never `git push` by hand.** Use `open-pr.sh`, `push-ci.sh` or `push-no-ci.sh`; they check' dev/varka_ops/AGENTS.md   # 7: 1
    awk '/^## 11\. Outcome/{p=1} /^## 12\./{p=0} p' $M9/VARKA-655.md | grep -n "not fan-out"   # 8: nothing, or one line a part of row 655 that a cheaper model built
    git branch -a --list '*varka-658d*'                                   # 9: nothing
    grep -c -F "  PR URL for the user and recommend installing the GitHub CLI." AGENTS.md   # 10: 1

Premises 4 to 7 and 10 print this on the plan's commit, and premise 8 prints nothing there.
`VARKA-655.md` 8 has the lead write each such part's facts under the part's line of its
section 11, "Outcome", marked "not fan-out: VARKA-655 part B" (or C).

**The steps.**

1. In `dev/varka_fanout.py`, insert the block of appendix A.4 before the line of premise 1,
   with one empty line after the block.
2. In the docstring, before the line `  dev/varka_fanout.py --self-test`, add two lines:

          dev/varka_fanout.py ledger [--summary]            # the ledger's table, or G5's counts
          dev/varka_fanout.py ledger --add KEY=VALUE...     # write one line's file

   and before the line that starts `Exit status: 0 passed;`, add this paragraph and one empty
   line after it:

        `ledger` prints one line a pull request from one file a line, as dev/varka_calibration.py
        prints its table, and --summary prints the counts of m9/PLAN.md 1.2, item 8. `check` also holds
        every line's form.

   The tool then has 204 lines more than before: `wc -l` prints `1804` where parts B and C
   are merged, and `838` where neither is.
3. Write the first line:

        python3 dev/varka_fanout.py ledger --add task=VARKA-621-abs-int row=621 kind=reference repo=vecbricks/varka pr=1584 units=1 "builder=Claude Opus 5.5" "tool=Claude Code" opened=2026-11-20 closed=2026-11-21 outcome=merged fix_rounds=- findings=6 fixed=4 kept=2 oracle_s=- conflicts=- "note=the first pilot entry of VARKA-658, merged before the ledger; its review is in the pull request's body"

   It prints `fanout: wrote sql/varka/plans/m9/fanout/ledger/varka-1584.tsv`. The day opened is
   20 November 2026: milestone 8's row 621 says "built and open as a pull request of 20
   November 2026".
4. For each line that premise 8 printed, add a line of kind `other` with the same command:
   `task=VARKA-655-<the part's letter>`, `row=655`, `units=1`, the builder, the tool, the days,
   the outcome, the fix rounds and the three counts as that section of `VARKA-655.md` records
   them, `oracle_s=-`, `conflicts=-` and `note=not fan-out: VARKA-655 part <letter>`. A value
   that the section does not record is `-` where the column allows it; where it does not (a
   day, the outcome), stop and report the line. If premise 8 printed nothing, add none.
5. In `dev/varka_ops/AGENTS.md`, after the bullet of premise 7 (it is two lines: the line of
   premise 7 and the line `  both stamps and push to the fork remote only.`), add one bullet:

        - **A fan-out task ends at `READY`, and the lead opens it.** Its builder runs
          `python3 dev/varka_fanout.py ready`, reports and stops; it never pushes. The lead gates
          and reviews the branch and opens the pull request through the scripts above, as for every
          other pull request. A fix round's `findings.md` starts with the lead's own line, `pushed:
          <the head the lead pushed>` (`sql/varka/plans/m9/VARKA-658.md` 4.6).

   And in the root `AGENTS.md`, section "Pull Requests", after the section's last line, which
   is the line of premise 10, add one bullet of two lines:

        - A Varka fan-out task, built from a plan that `dev/varka_fanout.py plan` wrote, ends at
          `READY`: its builder does not push, opens no PR and runs no `gh`. The lead opens the PR.

   `CLAUDE.md` at the root is a link to that file and needs no edit. The file is Spark's own,
   so a later sync with apache/spark may conflict in that section: the bullet stays last.
6. This file's section 14 (5.0).

**The tests.**

| check | where | the cases, with their inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `test_ledger` of `--self-test`, a line's form | the docs job | #1584's line of 18 cells under the name `varka-1584.tsv`; under `varka-1585.tsv`; with one cell changed at a time: the kind `fan-out`, the number `x`, the closed day `-` on a merged line, `fixed` 5 of 6 findings with 2 kept, an empty `conflicts`, a note that starts with a space; with 17 cells | no error; one; at least one each; one | a line that the table would print askew, or that the summary would count wrong |
| the same, the summary | | seven lines: the reference line; fan-out lines merged by `Kimi K2.7 Code`, taken over from `Kimi K2.7 Code` with two fix rounds, merged by `Claude Opus 5.5`, returned from `GigaChat Pro`, open by `DeepSeek Flash`; a pilot line merged by `DeepSeek Flash`; three findings each, all fixed | `ledger: 7 line(s), 5 of them fan-out pull requests`; `fan-out: merged 2, taken over 1, returned 1, open 1`; `built by a cheaper model: 1 of 3 fan-out pull requests that landed`; `Kimi K2.7 Code: 2 pull request(s), merged 1; findings 6, fixed 6, kept 0; fix rounds [1, 2]`; `other kinds: pilot 1, library 0, other 0, reference 1` | a taken-over pull request counted as the model's; a pilot counted in the fan-out's share |
| commands 2 to 4 | by hand; `check` in the docs job | the committed ledger | below | a ledger with no line, or with a line nobody can read back |
| command 5, two planted faults | by hand | below | each fails as written | a check that cannot fail |

**The commands.**

    python3 dev/varka_fanout.py --self-test                               # 1: varka_fanout self-test: ok (<n> groups): 5 with parts B and C merged, 2 with neither
    python3 dev/varka_fanout.py check | tail -1                           # 2: fanout: check: 0 error(s)
    python3 dev/varka_fanout.py ledger                                    # 3: the header line and one line, below
    python3 dev/varka_fanout.py ledger --summary                          # 4: the four lines below
    # 5: the planted faults
    printf 'x\ty\n' > $F/ledger/varka-1.tsv; python3 dev/varka_fanout.py check | head -1; rm $F/ledger/varka-1.tsv   # fanout: varka-1.tsv: 2 cells, not 18
    python3 dev/varka_fanout.py ledger --add task=x; echo "exit $?"       # fanout: --add takes every column once: task row kind repo pr units builder tool opened closed outcome fix_rounds findings fixed kept oracle_s conflicts note; exit 2
    python3 dev/agent_docs_lint.py --strict; echo "exit $?"               # 6: exit 0
    ruff check dev/varka_fanout.py; ruff format --check dev/varka_fanout.py   # 6a: All checks passed!; 1 file already formatted
    python3 dev/varka_msg_check.py size                                   # 7: a count under 360

Command 3 prints, the cells parted by tabs (shown here with ` | ` for a tab):

    task | row | kind | repo | pr | units | builder | tool | opened | closed | outcome | fix_rounds | findings | fixed | kept | oracle_s | conflicts | note
    VARKA-621-abs-int | 621 | reference | vecbricks/varka | 1584 | 1 | Claude Opus 5.5 | Claude Code | 2026-11-20 | 2026-11-21 | merged | - | 6 | 4 | 2 | - | - | the first pilot entry of VARKA-658, merged before the ledger; its review is in the pull request's body

and, where step 4 added lines, those after it. Command 4 prints, with no line of step 4:

    ledger: 1 line(s), 0 of them fan-out pull requests
    fan-out: merged 0, taken over 0, returned 0, open 0
    built by a cheaper model: 0 of 0 fan-out pull requests that landed
    other kinds: pilot 0, library 0, other 0, reference 1

With `k` lines of step 4 the first line reads `ledger: <1 + k> line(s)`, the last reads `other
<k>`, and one line a builder of theirs stands before the last.

**Done when** the ledger prints its 18 columns and #1584's line, the summary prints as
written, each planted fault fails as written, and the Build is green.

**The expectations, to tick.**

- [ ] `ledger`, `ledger --summary` and `ledger --add` exist; `check` reads every ledger file.
- [ ] `git status --short` before the commit: `M dev/varka_fanout.py`, `A
  sql/varka/plans/m9/fanout/ledger/varka-1584.tsv` and one more `A` for each line of step 4,
  `M dev/varka_ops/AGENTS.md`, `M AGENTS.md`, `M sql/varka/plans/m9/VARKA-658.md`, and no
  other line.
- [ ] No generated file, no census cell, no emitted byte and no benchmark changes.

**Not here.** A line for a pull request that is not merged or closed yet and has no review; a
script that pushes; the guide.

### 5.5 Part E: the guide

**B0, the premises.**

    grep -c "^# ---- The ledger (part D) \|^# ---- The oracle (part C) \|^# ---- Plans (part B) " dev/varka_fanout.py   # 1: 3 (parts B, C and D merged)
    grep -c "int32 lanes\|m4/PLAN.md\|emitValue\|dev/varka_gate.sh\|VarkaEmitterParityBenchmark\|VarkaThroughputBenchmark\|VARKA_COVERAGE_REGEN" sql/varka/ADDING_AN_EXPRESSION.md   # 2: 7
    wc -l < sql/varka/ADDING_AN_EXPRESSION.md                             # 3: 163
    grep -rl -F "ten numbered steps each ending in a command whose result" sql/varka/AGENTS.md sql/varka/.agents 2>/dev/null   # 4: one path: sql/varka/AGENTS.md, or a file of sql/varka/.agents once row 694 has moved the paragraph
    ls $F/VARKA-621-abs-int.txt sql/varka/plans/TEMPLATE_FANOUT.md sql/varka/rows/_coverage.json dev/varka_ops/pre-pr.sh sql/varka/plans/m8/VARKA-489.md | wc -l   # 5: 5
    git branch -a --list '*varka-658e*'                                   # 6: nothing

Premises 2 and 3 print this on the plan's commit; premise 4 prints `sql/varka/AGENTS.md`
there.

**The steps.**

1. Replace `sql/varka/ADDING_AN_EXPRESSION.md` whole with the text of appendix B.
2. In the file that premise 4 printed, the paragraph of four lines

        Adding an expression is the most common change here and has its own recipe:
        `ADDING_AN_EXPRESSION.md`, ten numbered steps each ending in a command whose result
        says whether that step is done. Step 7 is the coverage table, and it cannot be
        skipped quietly - the suite fails until the new expression is documented.

   becomes these five lines:

        Adding an expression is the most common change here and has its own recipe:
        `ADDING_AN_EXPRESSION.md`, ten numbered steps each ending in a command whose result
        says whether that step is done. A fan-out task takes it from a generated plan:
        `dev/varka_fanout.py plan` writes the plan from a card, and `dev/varka_fanout.py oracle`
        says in one command whether the task is done. The page says what each command does.

   Where row 694 has moved the paragraph and reworded it, the old text is the one sentence
   that premise 4's words are in, and the three new sentences from "A fan-out task" on are
   added after it; report that in the description.
3. In `sql/varka/skills/working-in-this-repo.md`, add at the end of the file a section:

        ## A fan-out task is a card, a generated plan and one oracle (VARKA-658)

        A task that fuses a few expressions over one type, with no new IR record, is not planned by
        hand. The coordinator writes a card under the milestone's `fanout` folder, and
        `python3 dev/varka_fanout.py plan <card> --write` renders the plan. The plan names every file
        the task may change; `scope` and the docs job fail on any other. `oracle` runs the task's
        seven steps and prints `fanout: PASSED <id> tree <tree> in <n> s`, the line the pull
        request's body carries; `ready` reads it back against the head, and there the builder stops:
        the lead gates, reviews and opens the pull request. The lead writes the ledger's
        line when the pull request merges or closes, and `ledger --summary` is the count of what the
        cheaper models built. On the entry of #1584 the oracle took <n> s on the laptop with every
        regeneration forced (`sql/varka/plans/m9/results/VARKA-658-oracle.txt`), and <the step that
        was most of it> was most of it.

   with the two `<...>` filled from part C's results file, and regenerate the index:
   `dev/varka_toc.py SKILLS.md --from sql/varka/skills`.
4. This file's section 14: the row's outcome, and each prediction of section 7 scored or
   named as scored by a later row. The row's status to `**Done**` (5.0).

**The tests.**

| check | where | what it holds | the failure only it catches |
| :--- | :--- | :--- | :--- |
| command 1 | by hand | the seven stale statements of fact 1 are gone | a guide that still describes milestone 4's engine |
| command 2 | by hand | eleven statements of today's tree are there, each once or more | a rewrite that dropped a step |
| command 3, and `check` in the docs job from now on | the docs job | every path and class the guide names is in the tree | a guide that names a file a later row deletes or renames |
| command 4, the planted fault | by hand | `check` fails on a class that does not exist | a check that cannot fail |

**The commands.**

    grep -c "int32 lanes\|m4/PLAN.md\|emitValue\|dev/varka_gate.sh\|VarkaEmitterParityBenchmark\|VarkaThroughputBenchmark\|VARKA_COVERAGE_REGEN" sql/varka/ADDING_AN_EXPRESSION.md   # 1: 0
    for w in "six lane types" "VarkaVectorWalk" "sql/varka/rows/<family>/<slug>.row" "census_curated.txt" "dev/varka_regen_generated.sh --all" "dev/varka_rows.py --check" "dev/varka_fanout.py oracle" "dev/varka_ops/pre-pr.sh" "after 489c" "under 1000 counted lines" "fusion class"; do grep -c -F "$w" sql/varka/ADDING_AN_EXPRESSION.md; done   # 2: eleven numbers, none 0
    python3 dev/varka_fanout.py check | tail -1                           # 3: fanout: check: 0 error(s)
    sed -i 's/`VarkaVectorWalk`, and/`VarkaVectorStroll`, and/' sql/varka/ADDING_AN_EXPRESSION.md; python3 dev/varka_fanout.py check | head -1; git checkout sql/varka/ADDING_AN_EXPRESSION.md   # 4: fanout: sql/varka/ADDING_AN_EXPRESSION.md: `VarkaVectorStroll`: no source file is named VarkaVectorStroll
    dev/varka_toc.py --check SKILLS.md --from sql/varka/skills; python3 dev/agent_docs_lint.py --strict; echo "exit $?"   # 5: exit 0
    python3 dev/varka_quote_check.py                                      # 6: its last line reports 0 orphans
    python3 dev/varka_msg_check.py size                                   # 7: a count under 600

Command 4 needs the guide committed first, so that `git checkout` restores the new text.

**Done when** command 1 prints 0, command 2 prints no 0, `check` passes and fails on the
planted class, and the Build is green. Then every clause of the row's done-when has its check
on master.

**The expectations, to tick.**

- [ ] The guide is the text of appendix B; `README.md` and `sql/varka/ADDING_A_TYPE.md`, which
  link to it, are unchanged.
- [ ] `git status --short` before the commit: `M sql/varka/ADDING_AN_EXPRESSION.md`, `M` the
  file of premise 4, `M sql/varka/skills/working-in-this-repo.md`, `M SKILLS.md`, `M` the
  row's file, `M sql/varka/plans/m9/VARKA-658.md`, and no other line.
- [ ] No code, no generated file, no census cell, no emitted byte and no benchmark changes.

**Not here.** Any change of the tool; `sql/varka/ADDING_A_TYPE.md`; the docs page.

## 6. Tests, proofs, fuzzing and coverage

The row adds no kernel, no IR record and no lowering: no proof file, no fuzz production, no
coverage row of its own, no JaCoCo or mutation run, no registry suite. Its tests hold four
things: a card parses or is refused by name; a plan names the files of a real pull request;
the oracle passes on a fused entry and fails on an unfused one; a ledger line has its form.

| suite or check | where | what it holds | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `dev/varka_fanout.py --self-test`, five groups (`test_cards`, `test_names`, `test_plans`, `test_oracle`, `test_ledger`) | the docs job of every Build; by hand in every part | 5.1 to 5.4's tables, case by case | a card with a hole; a task with no test, or whose named test was never written or never ran; a moved path accepted on a live branch; a census edit applied twice; an oracle that runs on after a failure, or stamps a tree whose Outcome is not written; a taken-over pull request counted as a model's |
| `dev/varka_fanout.py check` | the docs job | every card parses; each pilot's commit touched only files its plan names (13 and 23); every path and class the guide and the template name is in the tree; every quoted rule is in an `AGENTS.md` file; every ledger line has its form | a later row that renames a file the guide names, rewords a quoted rule, or moves a shared file without a line of `MOVED` |
| `dev/varka_fanout.py check --branch <branch> --base <base>` | the docs job's last step | on the branch that a card names, every changed file is one the card's plan names, and each test the card names is in the change | a fan-out pull request that edits a file outside its plan, or lacks a test its card names, whatever its description says |
| the oracle's run 1 and run 2 | part C, by hand, on the laptop | `PASSED` on the merged entry with a clean worktree after a forced regeneration; `FAILED at fused` with the arm deleted | an oracle that cannot pass, or cannot fail |
| the planted faults of parts A, B, C, D and E: a card with an `edit` line removed, a card with a cast unit removed, a wrong row number, an untracked file; a reworded quote, a placeholder with no value, a plan edited above its end marker; the arm deleted, a `-z` sentence that matches no test, a card's test that no suite ran, a dirty worktree; a ledger file of two cells, an `--add` with one column; a class that does not exist in the guide | by hand, in the sheets | each check fails with the words its sheet gives | a check that cannot fail |
| `python3 -m doctest dev/sparktestsupport/modules.py`, and the scope of the tool | part A | the tool's change runs the docs job, and alone | a tool no CI module claims |
| `VarkaCoverageSuite`, `VarkaCoverageHarnessSuite`, `VarkaCoverageDifferentialSuite`, unchanged | the Build; inside the oracle | what they hold today | - |

**What runs only in CI:** the docs job's three new lines, the Build's suites and the Maven
build. **What runs only on the laptop:** part C's runs 1, 2, 2b and 2c, which start JVMs
under the heavy lock; the regeneration's width audit runs on the laptop's CPU alone.

**Test time.** The self-test builds no file and starts no process: its five groups are string
work over a card of 24 lines and a short results text, and it runs in well under a second.
`check` reads the guide, the template, the rule files, the cards and the ledger's files, and
runs `git ls-files` once and `git show --name-only` once a pilot card: two commits today.
Section 7 registers both. Nothing is added to any suite, so a Build's suites take what they
took.

## 7. Measurement

No benchmark: the row changes no kernel. The predictions, registered on 27 November 2026
before any build:

1. **The oracle's time.** On pilot 1's merged entry, with every regeneration forced, the
   oracle takes between 12 and 30 minutes on the laptop, and its `regen` step is more than
   half of it. The estimate is 20 minutes, 1200 s: the steps are the coverage suite's one test,
   the three regenerating suites (the coverage, the width audit and the emitted bytes), the
   three suites of the `suites` step, and three starts of sbt; `sql/varka/ci/suite-times.tsv`
   has those suites at about 21 minutes together on a GitHub runner of four processors, where
   the laptop has more cores and one heavy JVM at a time. The calibration table (`python3
   dev/varka_calibration.py`) has no row for a command made of several suites, so the ratio is
   1. Scored by part C's run 1, which joins the table (5.3, step 8). This number is the
   fan-out's rate (`m9/PLAN.md` 2.7): at 20 minutes a task, three tasks an hour pass through
   the lock when nothing else holds it.
2. **Run 2 fails at `fused`,** and at no earlier step. Yes or no, by part C's results file.
3. **The two pilots' scope checks at the build** print the blocks of 5.1: 13 of 13 and 23 of
   23 files named. Yes or no, by part A's results file.
4. **`check` in the docs job takes under 5 s,** and the self-test under 1 s. Scored by the
   two steps' times in part A's Build and again in part E's. Over 20 s is a finding.
5. **Each part is under its estimate times one and a half.** Yes or no a part.
6. **Row 659's first task** reaches `fanout: PASSED` within three runs of the oracle, no
   step's failure is one that its plan did not let its builder fix, and its builder changes no
   file that its card did not list: the count of files in the task's reports of a `NOT NAMED`
   file is 0. Scored from that task's Outcome section, its stop reports and the ledger; it is
   the first reading of whether the plan "holds nothing to decide", and of whether a
   coordinator can list a task's own files before the work (4.4).
7. **Of the first ten fan-out pull requests,** none is found by the lead's review or by the
   docs job to change a file outside its plan after its builder's `ready` passed. Yes or no,
   from the ledger's notes.

## 8. Who builds it

Every part at full strength. `m9/PLAN.md` 1.4 puts "the task template, its oracle and its
ledger (row 658)" in "Before the fan-out", and G5 is about the fan-out's tasks: "After the
foundation, each fan-out task is one task plan from a template". A fault in the scope check or
in the oracle is a fault in every task that follows, and none of these five parts has a
generated plan: this row is what makes one. Part C above all: its builder runs the three JVM
steps for the first time and may repair their command lines (5.3), which is a judgement no
sheet can hold. Part E's text is given whole and a cheaper model could build it from its
sheet; the owner decides, and the default is full strength.

## 9. Risks

1. **The oracle's JVM steps were never run by the planner.** Held by 4.5, which names the
   house command each follows; by part C's B0, which holds those commands' forms to the tree;
   by runs 1 and 2, which are the done-when's own test and whose lines the sheet gives whole;
   and by 5.3's repairs: a closed list of first-run failures, each with the one change of a
   command line the builder may make, then the stop and what to report. 3.5 gives the three
   commands with which the lead can settle the open points on master before part C starts.
2. **The oracle is slow, and it is the fan-out's rate.** About 50 tasks at 20 minutes are 17
   hours of the heavy lock, more with fix rounds. Held by measuring it first (prediction 1),
   by row 695, which cuts the width audit's cost, and by the `--only` flag, with which a
   builder reruns one step while it works; only a full run says `PASSED`.
3. **A builder writes the `PASSED` line by hand.** The line names a tree, and anyone can
   compute a tree. Held by what does not depend on the builder: the docs job's scope check on
   the branch, the Build's suites, and the review. The line saves the lead a local run; it is
   not the proof.
4. **The plan is stale when the builder starts.** Another task merged and changed the entry's
   line. Held by section 0 of every generated plan (`plan --check` before any edit) and by the
   oracle's `card` step.
5. **Two tasks in flight edit one line of the curated census** (two types of one entry). They
   conflict there, as row 653 says they will until row 696 (`VARKA-653.md` 4.9). Held by the
   coordinator, who gives out one task an entry at a time, or one card with both units.
6. **Two siblings put their arms at one place of a family compiler.** Held by 4.2's rule; a
   conflict there is one GitHub shows, never a silent one.
7. **The template quotes a rule that row 694 moves or rewords.** A moved rule is found in
   `sql/varka/.agents/`; a reworded one fails the docs job, and the fix is the template's
   line, in the pull request that reworded the rule.
8. **The guide names a file that a later row deletes.** The docs job fails in that row's pull
   request, which is the point; the fix is one line of the guide.
9. **`MOVED` grows stale the other way:** a later row moves a shared file again, and a pilot's
   commit no longer maps. `check` fails in that row's pull request; the fix is a line of
   `MOVED`, or the row gives the pilot's card up by deleting its `merged` line. Row 696 will
   meet this for the census's files (section 12).
10. **A cheaper model pushes all the same,** though its plan forbids it, or because the root
    `AGENTS.md` tells every agent to push to the fork and run `gh pr create`. Held by the
    plan's own words on that file, in its sections 0 and 8 (4.6); by `ready`, which refuses a
    branch with an upstream and a branch any commit of which a remote-tracking ref holds, so
    a builder that pushed does not reach `READY`, on that head or on a later one (4.6,
    conditions 6 to 8); by the bullet part D adds to the root file; by the
    installed pre-push hook, which refuses a branch pushed to vecbricks/varka; by the lead,
    which opens every pull request itself and so sees a branch it did not push; and by the
    ledger's note for that task.
11. **The laptop's lock is held for the oracle's whole JVM time.** The oracle takes it step by
    step, not once, so the lead's runs can pass between a builder's steps; a step's seconds
    then hold the wait, and part C's run 1 is taken with the lock free.
12. **A card's `what goes in` text is too thin** for a cheaper model. The parser holds the
    form (the four parts, a sibling's test file, a test for a kernel); nothing checks that the
    words are true or enough. Held by the two pilot cards as examples, and by row 659: its
    first task is the test, and what it teaches goes into the template.
13. **A test's name in a report is not what facts 34 and 35 read.** The reports were read
    from runs before row 653. Held by part A's premise 18 and part C's premise 14, which read
    the harness's sentences from the tree; by run 1, which seeks pilot 1's harness test in a
    fresh report; and by the repair that corrects the sentence in `HARNESS_TESTS` and the
    card's line.
14. **The gate changes the head after `READY`,** so the pull request's tree is not the
    `PASSED` line's. Held by 4.6, step 3: the lead's `ready` runs before its gate, and the
    gate's stamp and the Build hold the head from there.
15. **sbt exits 0 on no test.** A `-z` sentence or a suite's name that matches nothing would
    pass a step that reads the exit status alone, and run 2 could then pass with the kernel
    deleted. Held by the report's check in `fused` and `suites` (4.5), by the self-test's
    cases of a report with no test and of no report, and by part C's runs 2b and 2c.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md`:

- "**A pull request stays under 1000 changed lines** of hand-written files - source, plans and
  notes, and hand-curated data such as `census_curated.txt` - added plus removed against its
  merge base." A card and the template are hand-written and count; a generated plan and a
  results file do not.
- "**A comment describes the code as it is.**" The tool's docstring lists only the commands a
  part has built: each sheet adds its lines.
- "**Never `git stash`.**" and "**Never kill by name pattern**". Part C's run 2 edits a
  tracked file and puts it back with `git checkout <file>`, in the builder's own worktree.
- "**Write only inside your own worktree and scratch area.**"
- "**Review your own diff before opening the pull request**": these five parts do; a fan-out
  task's review is the lead's, before the lead opens its pull request (4.6).

From the repository's root `AGENTS.md`:

- "Never remove, skip or weaken a failing test (`ignore`, `assume(false)`, `@unittest.skip`, or
  an expected value or golden file updated just to match), or mock the code under test, unless
  asked." No part changes a suite.
- "Every new text file that `dev/.rat-excludes` does not already match needs the Apache License
  header". The tool carries it; `.txt`, `.tsv` and `.md` files are matched (fact 15).
- "Agent instructions go in `AGENTS.md` or the `.agents/` directory beside it. Before adding or
  editing them: read `.agents/agent-docs.md`." Parts D and E edit two such files and run
  `python3 dev/agent_docs_lint.py --strict`.
- "Always get user approval before external operations such as pushing commits, creating PRs,
  or posting comments." The builder of each part asks; a fan-out task's builder never pushes
  or posts, and the lead opens its pull request (the owner's answer to question 2).

From `dev/varka_ops/AGENTS.md`: "**Never print the environment** (`env`, `printenv`, `set`): it
holds tokens."

What not to do: do not run `files` on a pilot's card; do not commit anything under `target/`;
do not leave `$GEN/VarkaNodeCompiler.java` changed after run 2; do not start a JVM outside
the heavy lock, and put no `flock` around the oracle or the regeneration; do not edit a
generated plan above its end marker; do not write a ledger line for a pull request that is
still open with no review; do not change a suite, the regeneration script or the push check;
in part C, change no line of the tool beyond 5.3's repairs.

## 11. Edits to other plans, made by this plan's own pull request

Every old text below was matched on master `e0aee0a14b1`; "once" means `grep -c -F` of it
there prints 1. No part of this row edits another plan. Edit 5 and the fourth departure of
edit 2 record the owner's answer of 27 November 2026 to question 2.

1. `sql/varka/plans/m9/PLAN.md`, section 3, row 658. Old, once:

        oracle and ledger. **Filed** (25 November 2026; the admission check:

   New:

        oracle and ledger. **Planned** (`VARKA-658.md`, 27 November 2026: five parts, the cards and the scope check first, then the template and the generator, the oracle, the ledger, the guide; one tool, `dev/varka_fanout.py`; the pilots are #1584 and #1434, whose 13 and 23 files the generated plans name, through a table of the paths that have moved since; filed 25 November 2026; the admission check:

2. `sql/varka/plans/m9/PLAN.md`, section 2.7. Before the line that is exactly `### 2.8 The
   upstream sync (row 663)` (once), add this paragraph and an empty line after it:

        *27 November 2026:* `VARKA-658.md` is the plan. One tool, `dev/varka_fanout.py`, renders a
        task's plan from a card, holds a change to the files that plan names, runs the oracle and
        prints the ledger. Four things differ from the list above. The oracle asks
        `VarkaCoverageSuite` whether the task's coverage rows fuse, not `dev/varka_emit.sh`, whose
        default columns are not the coverage table's. The model writes no ledger line: its pull
        request's body carries the oracle's line, and the lead writes the ledger. A benchmark
        section whose numbers the branch does not bring is reported as owed and does not fail; it
        fails once the runner's numbers are committed on the branch. And the model opens no pull
        request (item 8, and "The loop" below): it stops when `dev/varka_fanout.py ready` prints
        `READY`, and the lead opens the pull request after its own gate and review. The library's
        pull requests (2.10) are ledger lines of a kind of their own, counted beside the fan-out's
        share.

3. `sql/varka/plans/m9/VARKA-653.md`, section 1, the table "What it gives the rows after it",
   the line of row 658. Old, once:

        the files a generated task plan names: one `.row` file, three record files that the regeneration writes, at most one skip file (4.2 to 4.8)

   New:

        the files a generated task plan names: one `.row` file a coverage row, three record files a row that the regeneration writes, at most one skip file (4.2 to 4.8)

4. `sql/varka/plans/m9/VARKA-653.md`, section 12, the line of row 658. Old, once:

        it names, for one entry and one type: one row file, at most one skip file,

   New:

        it names, for a task of one to four units: one row file a coverage row, at most one skip file,

5. `sql/varka/plans/m9/PLAN.md`, section 2.7, the paragraph "The loop". Old, once, the
   paragraph's last line:

        coordinator had to rewrite does not count as built by the model.

   New, that line, an empty line and a dated note:

        coordinator had to rewrite does not count as built by the model.

        *27 November 2026, the owner's answer to `VARKA-658.md`'s question 2:* the model does not
        open the pull request. It builds, runs the oracle, runs `dev/varka_fanout.py ready` and
        stops at `READY` with a report. The lead then gates and reviews the branch and opens the
        pull request through `open-pr.sh`, as for every pull request; it gives the review's
        findings to a builder as a file, and that fix round ends at `READY` again.

**Rule files, edited by part D and not by this plan's pull request.** Two bullets, each given
whole in 5.4's step 5 with the line it follows, which is in its file once on master
`e0aee0a14b1`: in `dev/varka_ops/AGENTS.md`, after the bullet that starts ``- **Never `git
push` by hand.**``, the bullet "**A fan-out task ends at `READY`, and the lead opens it.**";
and in the root `AGENTS.md`, at the end of the section "Pull Requests", after the line
`  PR URL for the user and recommend installing the GitHub CLI.`, the bullet "A Varka fan-out
task, built from a plan that `dev/varka_fanout.py plan` wrote, ends at `READY`: its builder
does not push, opens no PR and runs no `gh`. The lead opens the PR."

**Plans that need no edit.** `VARKA-654.md` says a generated plan's commands end in `python3
dev/varka_census.py --check`: the oracle's `checks` step runs it inside `dev/varka_push_check.py
--no-jvm`. `VARKA-655.md` 8 gives its parts B and C a ledger line marked "not fan-out": part
D's step 4 writes them as kind `other` with that note. `VARKA-679.md` 10.2 leaves "the ledger
line of a library pull request" to this row: 4.7's kind `library`. `VARKA-657-PARTS.md` 4.8
says where the guide is stale and that this row rewrites it: part E does. `m9/PLAN.md`,
section 3, row 659: its "first" column reads "owner's decisions 3, 4 and 12; row 658 first".
Row 659's builders must wait for part E, because a generated plan sends them to the guide
that part E rewrites (section 1's table). Part E is this row's last part and makes it
**Done**, so "row 658 first" says it, and the line stays as it is.

## 12. Seams with the plan written beside this one, and with the rows around it

**Row 661** (the census and the cast matrix with the nanosecond types), whose plan is written
beside this one and which I could not read. `VARKA-660.md` 6 holds half of its sheet, so the
seams are stated against that. Row 661 is in wave 4 and this row in wave 1, so this row's
tool is on master when row 661 is built.

| # | what this row assumes of row 661 | what breaks if it is otherwise, and whose edit it is |
| ---: | :--- | :--- |
| 1 | The two types' census names are `ntz_ns` and `ltz_ns` (`VARKA-660.md` 4.4) | nothing: a unit's type is any word of lower-case letters and underscores |
| 2 | `m9/census.tsv` keeps its 13 columns in today's order, `fused_today` the eighth; `m9/casts.tsv` keeps `from`, `to` first and `fused_today` the ninth | the oracle's `checks` step reads those two columns by position (`unit_errors`). A row that inserts a column before them edits that function in the same pull request; the self-test does not hold the positions, so row 661's B0 should grep `def unit_errors` |
| 3 | `m9/census_curated.txt` stays one file, a registered entry a line of seven fields parted by bars, an implicit one of nine, the fused types the fourth or the sixth | `plan` and `files` find and edit the entry's line by that form (`curated_edits`) |
| 4 | An entry that Spark rejects over a nanosecond type does not hold the type among its types, and carries the reason (`Spark rejects ntz_ns: <error class>`) | a card of such a unit is the coordinator's mistake; its oracle fails at `checks`, since the census cannot count the unit as fused. Nothing in the tool reads the reason |
| 5 | The 20 nanosecond cast pairs are lines of `m9/casts.tsv` with census names in the first two cells | a unit `cast time ntz_ns` reads that line |
| 6 | A unit has no precision. Where an answer depends on the precision (`timestampadd` with `NANOSECOND`, six cast pairs: `VARKA-660.md` 4.4), the card's coverage rows hold the precisions, and the census counts the type as fused only when the task fuses every precision | if row 661 marks a type fused in part (`^`) for one precision alone, a task of one precision cannot pass `checks`; it is then no fan-out task, or its card holds all three |
| 7 | The admission table (`m9/nanos_admission.tsv`) changes only when Spark's surface moves, never by a task that fuses an entry | if the regeneration rewrote it inside a task, the oracle's `regen` step would stop on a file the plan does not name. Row 661 keeps its regeneration byte-stable on an unchanged Spark, or adds the file to `names` as a class of its own |

**The rows around it.**

| row | the seam | who holds it |
| :--- | :--- | :--- |
| 653 (merged plan) | this row reads `slug`, `family_slug`, the row file's keys, the skip file's name and `python3 dev/varka_rows.py --check` as that plan gives them; it rewrites the guide's step 7, says where a task's tests go (4.9) and where an arm goes (4.2), which that plan leaves to it | part A's premises 5 to 7 |
| 654 (merged plan) | `CURRENT = 9` and the five files under `m9/` | part A's premises 3 and 4 |
| 696 (filed, no plan) | when a census claim becomes a file, the classes `curated` and `census` name other files, `files` writes a claim file, the template's census paragraph changes, and `MOVED` gains the lines that take the old census paths to the new ones | row 696's plan; this plan's 4.10 names it |
| 659 (filed, no plan) | it writes its cards in 4.2's grammar; it may change the template and the tool; its ledger lines are of kind `pilot`. It gives no task to a builder before part E is merged: a generated plan sends its builder to the guide's steps 3 to 7, which part E rewrites. `m9/PLAN.md` holds that already: row 659's "first" column says "row 658 first", and this row is **Done** with part E | row 659 |
| 646 and 642 (no built layout) | none is read (4.10); the row waits for both all the same | the owner's answer to question 5; part A's premises 16 and 17. Row 646's plan, when written, adds its own name's grep to part A's B0 |
| 694 (filed) | it moves text of `sql/varka/AGENTS.md` into `sql/varka/.agents/`: the template's quotes are still found there; part E's premise 4 finds the paragraph it edits by its words | the tool's `RULE_DIRS`; part E |
| 655, parts B and C | a ledger line each of kind `other` | part D's step 4 |
| 679 | a ledger line a library pull request, of kind `library`, its file named `vechrono-<pr>.tsv` | the lead, with `ledger --add` |
| 680 | it gates a section's speed nightly with its band; the oracle's `bench` step reads one committed ratio | row 680 |
| 590 | its Build on the merge with master catches a task whose records another merge moved | row 590 |

## 13. The owner's decisions on this plan's questions

The eight questions of the plan's first version are decided, and the plan is written on the
decisions. Two decisions are not what the first version recommended, questions 2 and 5; the
sections named beside them were rewritten.

| question | decided | by whom, when |
| :--- | :--- | :--- |
| 1. Is the done-when's first clause met when each file the two merged pull requests touched is named by the plan, or was a shared file that `MOVED` takes to the file that replaced it, or is one of the two files no task writes any more (4.4)? | yes | the owner, 27 November 2026 |
| 2. May a fan-out pull request open by its plan's own commands after `ready`, with no gate stamp and with the review after it (4.6)? | no: a builder stops at `READY`, does not push and opens no pull request; the lead opens every fan-out pull request through `open-pr.sh` after a gate and a review, as the house rules have it (4.6, the template's sections 7 and 8, part D's sentence, section 11's edits 2 and 5) | the owner, 27 November 2026 |
| 3. May the oracle pass while a benchmark section's runner numbers are owed (4.5)? | yes: it prints `owed`, and fails once the numbers are on the branch | the owner, 27 November 2026 |
| 4. How do the library's pull requests count for G5 (`m9/PLAN.md` 2.10)? | ledger lines of kind `library`, counted beside the fan-out's share | the owner, 27 November 2026 |
| 5. Must this row wait for rows 642 and 646, as its "first" column says? | yes: the column stands, and part A's premises 16 and 17 hold both rows as done. That no sheet reads either row's layout stays in 4.10 as a recorded fact | the owner, 27 November 2026 |
| 6. Should a command draft a card from the census? | not in this row: row 659 decides after its first cards | the owner, 27 November 2026 |
| 7. Which builders are "cheaper models" for the share? | decision 3's three names, a constant of the tool (`CHEAPER`) | the owner, 27 November 2026 |
| 8. Who writes a ledger line? | the lead, when the pull request merges or closes | the owner, 27 November 2026 |

**Open, raised by the first review's fixes.** The plan is written on the default of each.

| # | question | the choices | recommended | default |
| ---: | :--- | :--- | :--- | :--- |
| 9 | Who approves a card's `no-own-test` claim, by which a task that changes kernel code adds no test file of its own and counts its coverage rows' tests as its tests (4.9)? Pilot 1 merged so. | (a) the lead's review of the pull request confirms the claim or makes it a finding; (b) the owner approves each such card before it is given out; (c) no exception: every task that changes main source adds a test file | (a) for the lead's own tasks; for a cheaper model's task the coordinator should not write the claim at all | (a) |
| 10 | When a package update moves the laptop's JDK 25, a task's regeneration rewrites the `host` block of the width audit's head. Does the task carry that change (class `host`, 4.5), as the house's gate commits what its regeneration changed, or does every task wait for a refresh by the lead? | (a) the task carries the `host` block alone, and stops if anything else of the audit moves; (b) the oracle refuses to run until the lead has refreshed the head | (a): it is the house's rule today, and two tasks that carry the same block merge cleanly | (a) |

## 14. Outcome

To be written when the row closes. Each part adds a dated paragraph here (5.0).

## Appendix A. The tool

`dev/varka_fanout.py`, as each part leaves it. A.1 is the whole file after part A. A.2, A.3
and A.4 are the blocks that parts B, C and D insert before the line that starts `# ---- The
command line (part A) `; their docstring lines are in each part's step 2. Each text is given
indented by four spaces: take the four spaces off every line, and leave an empty line empty.
Each text is byte for byte the copy that was tested (3.4): the self-test at each part, `ruff
check` and `ruff format --check`, and the sheets' insertions replayed to the same files of
634, 1050, 1600 and 1804 lines. The blocks of parts B and C start with their own imports, so
that no part's file imports a module it does not use.

### A.1 The file after part A (634 lines)

    #!/usr/bin/env python3
    #
    # Licensed to the Apache Software Foundation (ASF) under one or more
    # contributor license agreements.  See the NOTICE file distributed with
    # this work for additional information regarding copyright ownership.
    # The ASF licenses this file to You under the Apache License, Version 2.0
    # (the "License"); you may not use this file except in compliance with
    # the License.  You may obtain a copy of the License at
    #
    #    http://www.apache.org/licenses/LICENSE-2.0
    #
    # Unless required by applicable law or agreed to in writing, software
    # distributed under the License is distributed on an "AS IS" BASIS,
    # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    # See the License for the specific language governing permissions and
    # limitations under the License.
    #
    """A fan-out task: its plan from a card, its oracle and its ledger (VARKA-658).

      dev/varka_fanout.py check [--branch NAME] [--base REV]   # what the docs job holds, below
      dev/varka_fanout.py scope CARD [--against REV]    # is every changed file one the plan names?
      dev/varka_fanout.py --self-test

    A fan-out task fuses one to four (entry, type) pairs from a plan nobody writes by hand
    (sql/varka/plans/m9/PLAN.md 2.7). The coordinator writes a card,
    `sql/varka/plans/m<n>/fanout/<id>.txt`: what needs judgement, one `key: value` a line. `scope`
    holds a change to the files the card's plan names and to the tests the card names; with
    --against it holds a merged commit to them, through the table of the paths that have moved
    since (`MOVED`). `check` is what the docs job runs: every card parses, a pilot's merged commit
    touched only files its plan names and added the card's tests, and with --branch the same holds
    for the branch that a card names.

    Exit status: 0 passed; 1 a check or a step failed; 2 a usage error or a card that does not
    parse.
    """

    import os
    import re
    import subprocess
    import sys

    ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    GUIDE = "sql/varka/ADDING_AN_EXPRESSION.md"
    TEMPLATE = "sql/varka/plans/TEMPLATE_FANOUT.md"
    # Where a quoted house rule may live: the index files and the detail files beside them.
    RULE_FILES = ("CLAUDE.md", "AGENTS.md", "sql/varka/AGENTS.md", "dev/varka_ops/AGENTS.md")
    RULE_DIRS = (".agents", "sql/varka/.agents")
    COMMANDS = {}
    SELF_TESTS = []
    # --base REV, before or after the command's own arguments: the commit a branch is compared with.
    OPTIONS = {"base": None}
    # What `check` runs: functions that return error lines, and functions of a branch's name.
    CHECKS = []
    BRANCH_CHECKS = []


    class Fail(Exception):
        """A usage error or a card that does not parse: exit status 2."""


    def read(rel, root=ROOT):
        with open(os.path.join(root, rel), encoding="utf-8") as f:
            return f.read()


    def git(*args, root=ROOT):
        out = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
        if out.returncode != 0:
            raise Fail(f"git {' '.join(args)}: {out.stderr.strip()}")
        return out.stdout


    def milestone(root=ROOT):
        """The number of the milestone whose census the tools write (dev/varka_census.py)."""
        found = re.search(r"^CURRENT = (\d+)$", read("dev/varka_census.py", root), re.M)
        if not found:
            raise Fail("dev/varka_census.py has no line `CURRENT = <n>`")
        return int(found.group(1))


    def fanout_dir(root=ROOT):
        return f"sql/varka/plans/m{milestone(root)}/fanout"


    def tracked(root=ROOT):
        return git("ls-files", root=root).split("\n")


    # ---- Cards and scope (part A) ---------------------------------------------------------------

    ID = re.compile(r"^VARKA-(\d+)-[a-z0-9]+(-[a-z0-9]+)*$")
    ONCE = ("id", "row", "title", "branch", "sibling", "lowering")
    MANY = ("unit", "spark", "edit", "add", "suite", "bench", "not-here", "merged", "test")
    MANY += ("no-own-test",)
    # What an `edit` or `add` line holds after its bar: the place, the condition, the code to copy
    # and the result, so that the builder decides none of them.
    WHAT_FORM = "where: <place>; when: <condition>; copy: <code to copy>; result: <what it does>"
    WHAT = re.compile(r"^where: .+; when: .+; copy: .+; result: .+$")
    # The head of the width audit's records (VARKA-653 4.2). It holds the host, so a regeneration
    # on a newer JDK rewrites it; a task carries that change and no other change of it.
    AUDIT_HEAD = "sql/varka/rows/_audit.json"
    # How VarkaCoverageHarnessSuite names the tests it makes from a coverage row, as sbt's report
    # writes them: `<factory>():<family>: <sql> <sentence>`. The third field says which rows the
    # factory takes: every row, a row with a domain, or a row whose form is `predicate`.
    HARNESS_TESTS = (
        ("everyRow", "matches the row engine under both consumers", "every"),
        ("ansiForm", "raises under ANSI only where the row engine does", "every"),
        (
            "domains",
            "is answered inside its domain and where it is unknown, and declined outside with the "
            "verdict's status, in a projection and a filter",
            "domain",
        ),
        ("partitions", "splits every fixture into true, false and unknown rows", "predicate"),
        ("nullTestConditions", "under IS NULL and IS NOT NULL fuses as a CASE condition", "predicate"),
        ("booleanOutputs", "as a boolean output matches the row engine", "predicate"),
    )
    # A JUnit test's name in sbt's report: `<method>()`, a factory's `<factory>():<sentence>`, or a
    # parameterized test's `<method>(<parameter types>):<n>`.
    JUNIT_NAME = re.compile(r"^([A-Za-z_]\w*)\(([^()]*)\)(:.+)?$")
    JUNIT_FORM = "`<method>()`, `<factory>():<sentence>` or `<method>(<types>):<n>`"
    BLOCKS = {
        "coverage": ("family", "form", "note", "domain", "executable", "verified", "ansi-declined"),
        "decline": ("reason", "test"),
        "skip": ("suite", "test", "verdict", "reason"),
    }
    # What a merged pull request's paths have become since. A path is matched before it is looked up
    # among the plan's own names; the class is what the plan must then name. Each line names the row
    # that moved the path.
    MOVED = (
        (r"docs/sql-varka\.md", "lead", "VARKA-653 part D: the render commit writes the row tables"),
        (r"sql/varka/coverage\.json", "record", "VARKA-653 part B: one record a row"),
        (r"sql/varka/emitted_bytes\.json", "record", "VARKA-653 parts B and C"),
        (r"sql/varka/width_audit\.json", "record", "VARKA-653 part B"),
        (
            r"sql/catalyst/src/test/scala/.*/varka/VarkaCoverageSuite\.scala",
            "row",
            "VARKA-653 part E: a new row is a row file",
        ),
        (r"sql/varka/matrix/skips\.tsv", "skip", "VARKA-653 part F: a new skip is a file"),
        (r"sql/varka/plans/m\d+/census_curated\.txt", "curated", "VARKA-654 part a"),
        (r"sql/varka/plans/m\d+/census\.tsv", "census", "VARKA-654 part a"),
        (r"sql/varka/plans/m\d+/casts(_fused|_suites)?\.tsv", "casts", "VARKA-654 part a"),
        (r"sql/varka/plans/m\d+/PLAN\.md", "lead", "the lead edits a row's status"),
        (r"sql/varka/plans/m\d+/VARKA-\d+\.md", "plan", "VARKA-658: the task's plan is generated"),
        (
            r"sql/catalyst/src/test/scala/.*/VarkaExpressionCompilerSuite\.scala",
            "suite",
            "VARKA-658: a task's compile tests are in its own suite",
        ),
    )


    def parse_card(text, name="card"):
        """A card's lines as {key: [value, ...]}; a block key's value is a dict of its own lines."""
        card, block = {}, None
        for n, line in enumerate(text.split("\n"), 1):
            if not line.strip() or line.startswith("#"):
                continue
            if re.search(r"[^\x20-\x7e]", line):
                raise Fail(f"{name}:{n}: a character that is not printable ASCII")
            key, sep, value = line.partition(": ")
            if not sep or not value.strip():
                raise Fail(f"{name}:{n}: not `key: value`")
            value = value.strip()
            head, _, tail = key.partition("-")
            if key in BLOCKS:
                block = {key: value}
                card.setdefault(key, []).append(block)
            elif head in BLOCKS and tail in BLOCKS[head]:
                if block is None or head not in block:
                    raise Fail(f"{name}:{n}: `{key}` before its `{head}` line")
                block.setdefault(tail, []).append(value)
            elif key in ONCE or key in MANY:
                block = None
                card.setdefault(key, []).append(value)
            else:
                raise Fail(f"{name}:{n}: unknown key `{key}`")
        for key in ONCE:
            if len(card.get(key, [])) != 1:
                raise Fail(f"{name}: `{key}` must be given once")
        found = ID.match(card["id"][0])
        if not found or found.group(1) != card["row"][0] or len(card["id"][0]) > 60:
            raise Fail(f"{name}: `id` must be VARKA-<row>-<words>, at most 60 characters")
        if len(f"[VARKA-{card['row'][0]}] {card['title'][0]}") > 80:
            raise Fail(f"{name}: the title with its `[VARKA-<row>] ` is over 80 characters")
        if not 1 <= len(card.get("unit", [])) <= 4:
            raise Fail(f"{name}: one to four `unit` lines")
        for unit in card["unit"]:
            if not re.match(r"^(entry \S+ [a-z_]+|cast [a-z_]+ [a-z_]+)$", unit):
                raise Fail(f"{name}: `unit: {unit}` is no `entry <name> <type>` or `cast <from> <to>`")
        if re.search(r'["`$\\]', card["title"][0]):
            raise Fail(f"{name}: the title holds a quote, a backtick, a dollar or a backslash")
        for key in ("spark", "coverage", "bench"):
            if not card.get(key):
                raise Fail(f"{name}: at least one `{key}` line")
        for key in ("edit", "add", "suite", "bench"):
            for value in card.get(key, []):
                if " | " not in value:
                    raise Fail(f"{name}: `{key}: {value}` needs ` | ` and a second field")
                what = value.partition(" | ")[2]
                if key in ("edit", "add") and not re.match(WHAT, what):
                    raise Fail(f"{name}: `{key}: {value[:60]}...` is not `{WHAT_FORM}`")
        for row in card["coverage"]:
            forms = (["projection"], ["predicate"])
            if len(row.get("family", [])) != 1 or row.get("form") not in forms:
                raise Fail(f"{name}: `coverage: {row['coverage']}` needs one family and one form")
        for skip in card.get("skip", []):
            if any(len(skip.get(k, [])) != 1 for k in BLOCKS["skip"]):
                raise Fail(f"{name}: `skip: {skip['skip']}` needs suite, test, verdict and reason")
        for decline in card.get("decline", []):
            if any(len(decline.get(k, [])) != 1 for k in BLOCKS["decline"]):
                raise Fail(f"{name}: `decline: {decline['decline']}` needs a reason and a test")
        sibling = card["sibling"][0].split(" | ", 2)
        if len(sibling) != 3 or "/src/test/" not in sibling[1]:
            raise Fail(f"{name}: `sibling` is `<commit> | <a test file of it> | <what to copy>`")
        test_errors(name, card)
        return card


    def harness_names(row):
        """The names sbt's report gives the tests that the coverage harness makes from one row."""
        kinds = {"every"} | ({"domain"} if row.get("domain") else set())
        kinds |= {"predicate"} if row.get("form") == ["predicate"] else set()
        stem = f"{row['family'][0]}: {row['coverage']}"
        return [f"{factory}():{stem} {tail}" for factory, tail, kind in HARNESS_TESTS if kind in kinds]


    def test_errors(name, card):
        """The rule of a card's tests: every card names a test, by the name sbt's report gives it;
        a card that changes main source names one in a test file of its own, or says with
        `no-own-test` why its coverage rows' tests are enough."""
        own = [v.partition(" | ")[0] for key in ("edit", "add") for v in card.get(key, [])]
        suites = [v.partition(" | ")[2] for v in card.get("suite", [])]
        made = [test for row in card["coverage"] for test in harness_names(row)]
        files = 0
        for value in card.get("test", []):
            where, sep, test = value.partition(" | ")
            if where == "coverage":
                if test not in made:
                    raise Fail(f"{name}: `test: {value[:60]}...` is no name the harness gives a row")
            elif not sep or where not in own or "/src/test/" not in where:
                raise Fail(f"{name}: `test: {value}` names no test file of the card's `edit` and `add`")
            elif os.path.basename(where).rsplit(".", 1)[0] not in suites:
                raise Fail(f"{name}: `test: {value}`: no `suite` line names the file's suite")
            elif where.endswith(".java") and not JUNIT_NAME.match(test):
                raise Fail(f"{name}: `test: {value[:60]}...`: a JUnit test is {JUNIT_FORM}")
            else:
                files += 1
        if not card.get("test"):
            raise Fail(f"{name}: at least one `test` line")
        claims = card.get("no-own-test", [])
        if len(claims) > 1 or (claims and files):
            raise Fail(f"{name}: `no-own-test` is given once, on a card whose tests are all `coverage`")
        if any("/src/main/" in path for path in own) and not files and not claims:
            raise Fail(f"{name}: main source changes, so a `test` in a test file, or `no-own-test`")


    def load_card(path, root=ROOT):
        rel = os.path.relpath(os.path.abspath(path), root)
        card = parse_card(read(rel, root), rel)
        if os.path.basename(rel) != card["id"][0] + ".txt":
            raise Fail(f"{rel}: the file is not named for its id")
        return rel, card


    def slugs():
        """The two naming functions of dev/varka_rows.py (VARKA-653): a row's file and its folder."""
        sys.dont_write_bytecode = True
        sys.path.insert(0, os.path.join(ROOT, "dev"))
        import varka_rows

        return varka_rows.slug, varka_rows.family_slug


    def names(card, m, slug, family_slug):
        """Every file the task's plan names, as {path: (class, what)}, in the order they are met."""
        out = {}
        for key, cls in (("edit", "edit"), ("add", "add")):
            for value in card.get(key, []):
                path, _, what = value.partition(" | ")
                out[path] = (cls, what)
        for row in card["coverage"]:
            stem = f"sql/varka/rows/{family_slug(row['family'][0])}/{slug(row['coverage'])}"
            out[stem + ".row"] = ("row", f"the coverage row `{row['coverage']}`")
            for kind in (".json", ".bytes.json", ".audit.json"):
                out[stem + kind] = ("record", "written by the regeneration")
        if card.get("skip"):
            test = card["skip"][0]["test"][0]
            path = f"sql/varka/matrix/skips.d/varka-{card['row'][0]}-{slug(test)}.tsv"
            out[path] = ("skip", "the matrix skips")
        folder = f"sql/varka/plans/m{m}"
        if any(u.startswith("entry ") for u in card["unit"]):
            out[f"{folder}/census_curated.txt"] = ("curated", "each entry's line")
        out[f"{folder}/census.tsv"] = ("census", "written by the regeneration")
        if any(u.startswith("cast ") for u in card["unit"]):
            for name in ("casts.tsv", "casts_fused.tsv", "casts_suites.tsv"):
                out[f"{folder}/{name}"] = ("casts", "written by the regeneration")
        out[AUDIT_HEAD] = ("host", "written by the regeneration, on a newer JDK alone")
        out[f"{folder}/fanout/{card['id'][0]}.md"] = ("plan", "its Outcome section alone")
        return out


    def classify(path, named, moved=False):
        """How the plan names `path`: its class, or None. With `moved`, a path of a merged commit
        is first taken through MOVED."""
        if moved:
            for pattern, cls, _ in MOVED:
                if re.fullmatch(pattern, path):
                    if cls == "lead":
                        return cls
                    if cls == "suite":
                        own = [p for p, (c, _) in named.items() if c in ("add", "edit")]
                        return cls if any("/src/test/" in p for p in own) else None
                    return cls if any(c == cls for c, _ in named.values()) else None
        if path in named:
            return named[path][0]
        if path.startswith("sql/varka/rows/_admitted/") and path.endswith(".json"):
            return "record"
        return None


    def merge_base(root=ROOT):
        """The commit a branch's changes are counted from: --base, else VARKA_DIFF_BASE, which CI
        sets, else the merge base with origin/master."""
        given = OPTIONS["base"] or os.environ.get("VARKA_DIFF_BASE")
        return given or git("merge-base", "HEAD", "origin/master", root=root).strip()


    def changed_paths(root=ROOT):
        paths = git("diff", "--name-only", "--no-renames", merge_base(root), root=root).split("\n")
        paths += git("ls-files", "--others", "--exclude-standard", root=root).split("\n")
        return sorted({p for p in paths if p})


    def scope(card, against=None, root=ROOT):
        """(lines, unnamed): one line a changed path with how the plan names it."""
        slug, family_slug = slugs()
        named = names(card, milestone(root), slug, family_slug)
        if against:
            paths = git("show", "--name-only", "--no-renames", "--format=", against, root=root)
            paths = sorted(p for p in paths.split("\n") if p)
        else:
            paths = changed_paths(root)
        lines, unnamed = [], 0
        for path in paths:
            cls = classify(path, named, moved=bool(against))
            unnamed += cls is None
            lines.append(f"{path}\t{cls or 'NOT NAMED'}")
        return lines, unnamed


    def added_text(diff):
        """The lines a diff adds, joined by spaces, with the `"..." + "..."` of a Java or Scala
        string written over several lines closed up."""
        lines = [x[1:].strip() for x in diff.split("\n") if x.startswith("+") and x[:3] != "+++"]
        return re.sub(r'"\s*\+\s*"', "", " ".join(lines))


    def sought(where, test):
        """What the scope check seeks in the lines a change adds to a test file. In a `.java` file a
        test `<method>()` is sought as `void <method>(`, and a factory-made or a parameterized test
        by its method alone, ` <method>(`: the source holds no such test's whole name. Any other
        name, a ScalaTest sentence, is sought as written."""
        found = JUNIT_NAME.match(test) if where.endswith(".java") else None
        if not found:
            return test
        plain = not found.group(2) and not found.group(3)
        return f"void {found.group(1)}(" if plain else f" {found.group(1)}("


    def tests_missing(card, diff_of):
        """The card's tests that the change does not add. `diff_of(path)` is the change's diff of a
        file. A `coverage` test is the row file's own and is not sought."""
        missing = []
        for value in card.get("test", []):
            where, _, test = value.partition(" | ")
            if where != "coverage" and sought(where, test) not in " " + added_text(diff_of(where)):
                missing.append(f"the test `{test}` is not in the lines the change adds to {where}")
        return missing


    def change_diff(against=None, root=ROOT):
        """A function of a path: the diff of a merged commit, or of the branch against its base,
        where a file git does not track yet is added whole."""

        def diff_of(path):
            if against:
                return git("show", "--format=", against, "--", path, root=root)
            if git("ls-files", "--others", "--exclude-standard", "--", path, root=root).strip():
                return "".join("+" + line for line in read(path, root).splitlines(keepends=True))
            return git("diff", merge_base(root), "--", path, root=root)

        return diff_of


    def scope_errors(card, against=None, root=ROOT):
        """(lines, errors): the changed paths with their classes, and one error a path the plan does
        not name and a test of the card that the change does not add. A pilot's card is held to its
        tests against its merged commit alone."""
        lines, _ = scope(card, against, root)
        errors = [
            x.split("\t")[0] + " is changed and the plan does not name it"
            for x in lines
            if x.endswith("NOT NAMED")
        ]
        if against or not card.get("merged"):
            errors += tests_missing(card, change_diff(against, root))
        return lines, errors


    def cmd_scope(argv):
        if not argv:
            raise Fail("usage: scope CARD [--against REV]")
        _, card = load_card(argv[0])
        against = argv[argv.index("--against") + 1] if "--against" in argv else None
        lines, errors = scope_errors(card, against)
        for line in lines:
            print(line)
        for e in errors[sum(x.endswith("NOT NAMED") for x in lines) :]:
            print(f"fanout: scope: {e}")
        unnamed = sum(x.endswith("NOT NAMED") for x in lines)
        print(f"fanout: scope: {len(lines)} file(s), {unnamed} not named by the plan")
        return 1 if errors else 0


    COMMANDS["scope"] = cmd_scope


    def card_errors(root=ROOT):
        """Every card parses; a pilot's merged commit touched only files its plan names."""
        errors = []
        folder = fanout_dir(root)
        top = os.path.join(root, folder)
        for name in sorted(os.listdir(top)) if os.path.isdir(top) else []:
            if not name.endswith(".txt"):
                continue
            try:
                _, card = load_card(os.path.join(top, name), root)
                for commit in card.get("merged", []):
                    _, found = scope_errors(card, commit, root)
                    errors += [f"{folder}/{name}: {commit}: {e}" for e in found]
            except Fail as e:
                errors.append(str(e))
        return errors


    def branch_errors(branch):
        """On the branch a card names, every changed file is one its plan names."""
        folder = fanout_dir()
        top = os.path.join(ROOT, folder)
        for name in sorted(os.listdir(top)) if branch and os.path.isdir(top) else []:
            if name.endswith(".txt"):
                _, card = load_card(os.path.join(top, name))
                if card["branch"][0] == branch and not card.get("merged"):
                    return [f"{folder}/{name[:-4]}.md: {e}" for e in scope_errors(card)[1]]
        return []


    CHECKS.append(card_errors)
    BRANCH_CHECKS.append(branch_errors)

    CARD = """id: VARKA-9-abs-int
    row: 9
    title: Fuse abs over an int
    branch: varka-9a-abs-int
    unit: entry abs int
    unit: cast time ntz
    spark: Abs.nullSafeEval: the answer
    sibling: 0000000 | sql/a/src/test/java/VarkaAbsLongSuite.java | the long arm
    lowering: one node
    edit: sql/a/src/main/java/VarkaNodeCompiler.java | where: w; when: c; copy: k; result: r
    add: sql/a/src/test/java/VarkaAbsIntSuite.java | where: w; when: c; copy: k; result: r
    test: sql/a/src/test/java/VarkaAbsIntSuite.java | absOverAnInt()
    test: coverage | everyRow():Integer arithmetic: abs(i) matches the row engine under both consumers
    coverage: abs(i)
    coverage-family: Integer arithmetic
    coverage-form: projection
    coverage-domain: i > -2147483648
    skip: checkIntOverflow=false
    skip-suite: VarkaCoverageHarnessSuite
    skip-test: Integer arithmetic: abs(i) is answered
    skip-verdict: fails
    skip-reason: the check is off by design
    suite: catalyst | VarkaAbsIntSuite
    bench: none | no baseline class holds an int abs
    """


    def test_cards():
        card = parse_card(CARD)
        assert card["coverage"][0]["domain"] == ["i > -2147483648"], card
        for bad, why in (
            (CARD.replace("row: 9", "row: 8"), "`id` must be"),
            (CARD.replace("unit: entry abs int", "unit: abs int"), "is no `entry"),
            (CARD.replace("coverage-form: projection\n", ""), "one family and one form"),
            (CARD.replace("skip-verdict: fails\n", ""), "needs suite, test, verdict"),
            (CARD + "colour: red\n", "unknown key"),
            (CARD.replace("title: Fuse abs", "title: Fuse `abs`"), "the title holds"),
            (CARD.replace("lowering: one node\n", ""), "`lowering` must be given once"),
            (CARD.replace("; copy: k", "", 1), "is not `where: <place>; when:"),
            (
                CARD.replace("0000000 | sql/a/src/test/java/VarkaAbsLongSuite.java | ", "0000000 | "),
                "`sibling` is",
            ),
            (
                CARD.replace("test: sql/a/src/test/java/VarkaAbsIntSuite.java | absOverAnInt()\n", ""),
                "main source changes",
            ),
            (
                CARD.replace("| absOverAnInt()", "| absOverAnInt()\nno-own-test: the harness"),
                "`no-own-test` is given once",
            ),
            (CARD.replace("| everyRow():", "| partitions():"), "is no name the harness gives"),
            (CARD.replace("| everyRow():Integer", "| Integer"), "is no name the harness gives"),
            (CARD.replace("| absOverAnInt()", "| abs of an int fuses"), "a JUnit test is"),
            (CARD.replace("suite: catalyst | VarkaAbsIntSuite\n", ""), "no `suite` line names"),
            (
                CARD.replace("test: sql/a/src/test/java/VarkaAbsIntSuite.java", "test: sql/b/X.java"),
                "names no test file",
            ),
        ):
            try:
                parse_card(bad)
                raise AssertionError(why)
            except Fail as e:
                assert why in str(e), (why, str(e))
        named = names(card, 9, lambda t: "s" + str(len(t)), lambda t: t.lower().replace(" ", "-"))
        classes = [c for c, _ in named.values()]
        assert classes == [
            "edit",
            "add",
            "row",
            "record",
            "record",
            "record",
            "skip",
            "curated",
            "census",
            "casts",
            "casts",
            "casts",
            "host",
            "plan",
        ], classes
        claim = CARD.replace(
            "test: sql/a/src/test/java/VarkaAbsIntSuite.java | absOverAnInt()\n",
            "no-own-test: the harness covers the answers\n",
        )
        assert parse_card(claim)["no-own-test"] == ["the harness covers the answers"]
        diff = '+++ b/X.java\n+  @DisplayName("abs of an int "\n+      + "fuses")\n'
        diff += "+  void absOverAnInt() {\n"
        assert added_text(diff) == '@DisplayName("abs of an int fuses") void absOverAnInt() {'
        assert tests_missing(card, lambda path: diff) == []
        gone = tests_missing(card, lambda path: diff.replace("absOverAnInt", "absOverALong"))
        assert gone == [
            "the test `absOverAnInt()` is not in the lines the change adds to "
            "sql/a/src/test/java/VarkaAbsIntSuite.java"
        ], gone
        scala = card | {"test": ["sql/a/src/test/scala/VarkaAbsSuite.scala | abs of an int fuses"]}
        assert tests_missing(scala, lambda path: diff) == []
        java = "sql/a/src/test/java/VarkaAbsIntSuite.java"
        assert sought(java, "everyCase():t + dt agrees with the row engine") == " everyCase("
        assert sought(java, "slice(String, VarkaA$Mode):1") == " slice("
        assert sought(java, "absOverAnInt()") == "void absOverAnInt("
        made = harness_names(card["coverage"][0])
        assert [name.split("(")[0] for name in made] == ["everyRow", "ansiForm", "domains"], made
        assert made[2].endswith(
            "abs(i) is answered inside its domain and where it is unknown, "
            "and declined outside with the verdict's status, in a projection and a filter"
        )
        filter_row = {"coverage": "d < d2", "family": ["Predicates"], "form": ["predicate"]}
        assert len(harness_names(filter_row)) == 5
        assert (
            "partitions():Predicates: d < d2 splits every fixture into true, false and unknown rows"
            in harness_names(filter_row)
        )
        assert "sql/varka/rows/integer-arithmetic/s6.row" in named
        assert "sql/varka/matrix/skips.d/varka-9-s38.tsv" in named, list(named)
        then = "sql/catalyst/src/test/scala/a/varka/VarkaCoverageSuite.scala"
        assert classify(then, named, moved=True) == "row"
        assert classify(then, named) is None
        assert classify("docs/sql-varka.md", named, moved=True) == "lead"
        assert classify("docs/sql-varka.md", named) is None
        assert classify("sql/varka/plans/m8/VARKA-621.md", named, moved=True) == "plan"
        compiler_suite = "sql/catalyst/src/test/scala/a/VarkaExpressionCompilerSuite.scala"
        assert classify(compiler_suite, named, moved=True) == "suite"
        assert classify("sql/a/VarkaVectorWalk.java", named, moved=True) is None
        assert classify(AUDIT_HEAD, named) == "host"
        no_test = {p: v for p, v in named.items() if "/src/test/" not in p}
        assert classify(compiler_suite, no_test, moved=True) is None
        assert classify("sql/varka/rows/_admitted/Abs.json", named) == "record"


    SELF_TESTS.append(test_cards)

    # ---- The command line (part A) --------------------------------------------------------------


    def cmd_check(argv):
        branch = argv[argv.index("--branch") + 1] if "--branch" in argv else ""
        errors = [e for check in CHECKS for e in check()]
        errors += [e for hook in BRANCH_CHECKS for e in hook(branch)]
        for e in errors:
            print(f"fanout: {e}")
        print(f"fanout: check: {len(errors)} error(s)")
        return 1 if errors else 0


    COMMANDS["check"] = cmd_check


    def main(argv):
        if "--base" in argv[:-1]:
            at = argv.index("--base")
            OPTIONS["base"] = argv[at + 1]
            argv = argv[:at] + argv[at + 2 :]
        if argv[1:] == ["--self-test"]:
            for test in SELF_TESTS:
                test()
            print(f"varka_fanout self-test: ok ({len(SELF_TESTS)} groups)")
            return 0
        if len(argv) < 2 or argv[1] not in COMMANDS:
            print(__doc__.split("\n\n")[1], file=sys.stderr)
            return 2
        try:
            return COMMANDS[argv[1]](argv[2:])
        except Fail as e:
            print(f"fanout: {e}", file=sys.stderr)
            return 2


    if __name__ == "__main__":
        sys.exit(main(sys.argv))

### A.2 The block of part B (405 lines)

    # ---- The names and the quoted rules (part B) ------------------------------------------------

    PATH_CLAIM = re.compile(r"^(dev|sql|docs|build|\.github)/[A-Za-z0-9_./-]+$")
    CLASS_CLAIM = re.compile(r"^(Varka[A-Za-z0-9]+)(\.java|\.scala|\.[a-z][A-Za-z0-9]*)?$")


    def name_errors(rel, text, files):
        """The names `text` gives in backticks that the tree does not have: a path under dev, sql,
        docs, build or .github, or a class `Varka...` with no source file of that name."""
        paths = set(files)
        dirs = {f[: i + 1] for f in files for i, c in enumerate(f) if c == "/"}
        stems = {os.path.basename(f).rsplit(".", 1)[0] for f in files}
        errors = []
        for token in sorted(set(re.findall(r"`([^`\n]+)`", text))):
            if PATH_CLAIM.match(token):
                if token not in paths and token.rstrip("/") + "/" not in dirs:
                    errors.append(f"{rel}: `{token}` is no file or directory of the tree")
            else:
                cls = CLASS_CLAIM.match(token)
                if cls and cls.group(1) not in stems:
                    errors.append(f"{rel}: `{token}`: no source file is named {cls.group(1)}")
        return errors


    def squeeze(text):
        return " ".join(text.split())


    def quote_errors(rel, text, rules):
        """The template's quoted rules (`> ` lines, joined while they follow each other) that are
        in no rule file as written."""
        errors, quote = [], []
        for line in text.split("\n") + [""]:
            if line.startswith("> "):
                quote.append(line[2:])
            elif quote:
                wanted = squeeze(" ".join(quote))
                if wanted not in rules:
                    errors.append(f"{rel}: no AGENTS.md file holds the quoted rule: {wanted[:70]}")
                quote = []
        return errors


    def rule_text(root=ROOT):
        texts = []
        for rel in RULE_FILES:
            if os.path.exists(os.path.join(root, rel)):
                texts.append(read(rel, root))
        for rel in RULE_DIRS:
            top = os.path.join(root, rel)
            for name in sorted(os.listdir(top)) if os.path.isdir(top) else []:
                if name.endswith(".md"):
                    texts.append(read(f"{rel}/{name}", root))
        return squeeze(" ".join(texts))


    def check_errors(root=ROOT):
        files = tracked(root)
        errors = name_errors(GUIDE, read(GUIDE, root), files)
        if os.path.exists(os.path.join(root, TEMPLATE)):
            template = read(TEMPLATE, root)
            errors += name_errors(TEMPLATE, template, files)
            errors += quote_errors(TEMPLATE, template, rule_text(root))
        return errors


    CHECKS.append(check_errors)


    def test_names():
        files = ["dev/varka_emit.sh", "sql/x/VarkaNodeCompiler.java", "sql/varka/skills/a.md"]
        text = "`dev/varka_emit.sh` `dev/varka_gone.sh` `sql/varka/skills/` `VarkaNodeCompiler`"
        text += " `VarkaLost.emitValue` `m4/PLAN.md` `VarkaNodeCompiler.java` `<Class>.java`"
        got = name_errors("g", text, files)
        assert got == [
            "g: `VarkaLost.emitValue`: no source file is named VarkaLost",
            "g: `dev/varka_gone.sh` is no file or directory of the tree",
        ], got
        rules = squeeze("**Never `git stash`.** The stash list is one per repository.")
        kept = "> **Never `git stash`.** The stash\n> list is one per repository.\n\ntext\n"
        assert quote_errors("t", kept, rules) == []
        assert len(quote_errors("t", "> Never stash anything.\n", rules)) == 1


    SELF_TESTS.append(test_names)

    # ---- Plans (part B) -------------------------------------------------------------------------

    import hashlib

    END = "<!-- end of the generated plan -->"
    # The Outcome that `plan --write` leaves below the end marker: its heading and its three
    # fields, each empty. The oracle's `card` step fails until each field has text after it.
    HEADING = "## 9. Outcome"
    FIELDS = ("Built", "Plan gaps", "Oracle failures")
    OUTCOME = (
        f"\n{HEADING}\n\nTo be written by the builder before the oracle runs (section 4, step 2).\n"
    )
    OUTCOME += "Keep the heading and the three fields. Write after each colon: what was built; each\n"
    OUTCOME += (
        "place where the plan was wrong or left a choice; each oracle run that failed, with its\n"
    )
    OUTCOME += "step and the fix. Write `none` where there is nothing to say.\n\n"
    OUTCOME += "".join(f"- {field}:\n" for field in FIELDS)
    # How the plan's section 2 marks a file of the sibling's commit, by the first pattern that
    # matches its path.
    SIBLING = (
        (r"/benchmarks/|Benchmark\.(java|scala)$", "benchmark: do not copy"),
        (r"/src/main/", "main source: read what this section's first paragraph names"),
        (r"/src/test/", "test: do not copy; read it for a helper the marked file calls"),
        (
            r"^docs/sql-varka\.md$|^sql/varka/[a-z_]+\.(json|tsv)$|^sql/varka/rows/.*\.json$"
            r"|^sql/varka/plans/m\d+/(census|casts|casts_fused|casts_suites)\.tsv$|^SKILLS\.md$",
            "generated: never copy, never edit",
        ),
        (r"", "plan, note or tool: do not copy"),
    )


    def curated_edit(line, fields, unit_type, row):
        """The entry's line with the type among its fused types and the row among its rows."""
        at = 3 if len(fields) == 7 else 5
        fused = [t for t in fields[at].split(",") if t]
        rows = [r for r in fields[at + 2].split(",") if r]
        if unit_type in [t.rstrip("~^") for t in fused]:
            return None
        new = list(fields)
        new[at] = ",".join(fused + [unit_type])
        new[at + 2] = ",".join(rows + ([row] if row not in rows else []))
        return "|".join(new)


    def row_text(row):
        """A coverage row's file, as VARKA-653 4.6 has it: one `key: value` a line."""
        text = [f"sql: {row['coverage']}", f"family: {row['family'][0]}", f"form: {row['form'][0]}"]
        for key in ("note", "executable", "domain", "verified", "ansi-declined"):
            text += [f"{key}: {value}" for value in row.get(key, [])]
        return text


    def skip_cells(card):
        return [
            [s["skip"], s["suite"][0], s["test"][0], s["verdict"][0], s["reason"][0]]
            for s in card.get("skip", [])
        ]


    def base_text(rel, root=ROOT):
        """A file as the branch's merge base has it: what the task starts from."""
        return git("show", f"{merge_base(root)}:{rel}", root=root)


    def curated_edits(rel, card, text):
        """(old line, new line) for each entry unit that `text`, the curated census of the branch's
        merge base, does not count as fused; two units of one entry give one edit."""
        edits = []
        for unit in card["unit"]:
            kind, a, b = unit.split(" ")
            if kind != "entry":
                continue
            now = text
            for old, new in edits:
                now = now.replace(old + "\n", new + "\n")
            line, fields = None, None
            for candidate in now.split("\n"):
                cells = candidate.split("|")
                if cells[0] == a and len(cells) in (7, 9) and not candidate.startswith("#"):
                    line, fields = candidate, cells
            if line is None:
                raise Fail(f"{rel}: `{a}` is no entry of the curated census")
            new = curated_edit(line, fields, b, card["row"][0])
            if new is None and not card.get("merged"):
                raise Fail(f"{rel}: `{a}` is fused over `{b}` already; a pilot's card says `merged:`")
            if new is not None:
                first = [i for i, (_, n) in enumerate(edits) if n == line]
                if first:
                    edits[first[0]] = (edits[first[0]][0], new)
                else:
                    edits.append((line, new))
        return edits


    def given_files(card, named, edits, curated_path, curated_now):
        """What the plan gives word for word, as {path: text}: each coverage row's file, the skip
        file, and the curated census with the edits applied to its text of now."""
        written = {}
        rows = iter(card["coverage"])
        for path, (cls, _) in named.items():
            if cls == "row":
                written[path] = "\n".join(row_text(next(rows))) + "\n"
            elif cls == "skip":
                written[path] = "".join("\t".join(c) + "\n" for c in skip_cells(card))
        for old, new in edits:
            if curated_now.count(new + "\n") == 1 and curated_now.count(old + "\n") == 0:
                continue
            if curated_now.count(old + "\n") != 1:
                raise Fail(f"{curated_path}: the line of `{old.split('|')[0]}` is not there once")
            curated_now = curated_now.replace(old + "\n", new + "\n")
            written[curated_path] = curated_now
        return written


    def bullets(items):
        return "\n".join(f"- {item}" for item in items) if items else "None."


    def render(rel, card, root=ROOT):
        """The task's plan: the template with the card's and the tree's facts."""
        slug, family_slug = slugs()
        m = milestone(root)
        folder = f"sql/varka/plans/m{m}"
        named = names(card, m, slug, family_slug)
        template = read(TEMPLATE, root)
        digest = hashlib.sha256(template.encode("utf-8")).hexdigest()[:12]
        merged = card.get("merged", [])
        units = []
        for unit in card["unit"]:
            kind, a, b = unit.split(" ")
            if kind == "cast":
                where = f"`{folder}/casts.tsv`, the line of `{a}` and `{b}`"
                units.append(f"| `CAST({a} AS {b})` | cast | {where} |")
            else:
                where = f"`{folder}/census_curated.txt`, the line of `{a}`"
                units.append(f"| `{a}` over `{b}` | entry | {where} |")
        edits = curated_edits(rel, card, base_text(f"{folder}/census_curated.txt", root))
        curated = [f"Old, once in the file:\n\n    {old}\n\nNew:\n\n    {new}" for old, new in edits]
        files = [f"| {n} | `{p}` | {c} | {w} |" for n, (p, (c, w)) in enumerate(named.items(), 1)]
        rows = []
        for row in card["coverage"]:
            path = f"sql/varka/rows/{family_slug(row['family'][0])}/{slug(row['coverage'])}.row"
            rows.append(f"`{path}`:\n\n" + "\n".join("    " + t for t in row_text(row)))
        skips = "None: the card names no skip."
        if card.get("skip"):
            path = [p for p, (c, _) in named.items() if c == "skip"][0]
            body = "\n".join("    " + "<TAB>".join(c) for c in skip_cells(card))
            skips = f"`{path}`, one line a skip, `<TAB>` standing for one tab:\n\n{body}"
        commit, sibling_test, sibling = card["sibling"][0].split(" | ", 2)
        shown = git("show", "--name-only", "--format=%h %s", commit, root=root).rstrip("\n")
        shown = [x for x in shown.split("\n") if x]
        if sibling_test not in shown[1:]:
            raise Fail(f"{rel}: the sibling {commit} did not change {sibling_test}")
        marked = [f"`{shown[0]}`", "", "| file | what it is to you |", "| :--- | :--- |"]
        for path in shown[1:]:
            what = [w for pattern, w in SIBLING if re.search(pattern, path)][0]
            if path == sibling_test:
                what = "test: **the file to copy the shape of your tests from**"
            marked.append(f"| `{path}` | {what} |")
        tests = []
        for value in card.get("test", []):
            where, _, test = value.partition(" | ")
            made = (
                "a test `VarkaCoverageHarnessSuite` makes from the coverage row"
                if where == "coverage"
                else f"in `{where}`"
            )
            tests.append(f"`{test}`, {made}")
        for claim in card.get("no-own-test", []):
            tests.append(
                f"No test file of its own, by the card's claim, which the lead's review "
                f"confirms or rejects: {claim}"
            )
        own = []
        for module, profile in (("catalyst", ""), ("sql", "-Phive ")):
            mine = ["*" + v.partition(" | ")[2] for v in card.get("suite", []) if v.startswith(module)]
            if mine:
                own.append(
                    f"        flock \"$LOCK\" build/sbt {profile}'{module}/testOnly {' '.join(mine)}'"
                )
        suites = {
            "catalyst": ["VarkaCoverageSuite"],
            "sql": ["VarkaCoverageHarnessSuite", "VarkaCoverageDifferentialSuite"],
        }
        for value in card.get("suite", []):
            module, _, suite = value.partition(" | ")
            if module not in suites:
                raise Fail(f"{rel}: `suite: {value}`: the module is catalyst or sql")
            suites[module].append(suite)
        declines = [
            f'`{d["decline"]}` declines with "{d["reason"][0]}", held by the test "{d["test"][0]}"'
            for d in card.get("decline", [])
        ]
        values = {
            "id": card["id"][0],
            "row": card["row"][0],
            "title": card["title"][0],
            "branch": card["branch"][0],
            "card": rel,
            "plan": rel[:-4] + ".md",
            "milestone": str(m),
            "lowering": card["lowering"][0],
            "units": "\n".join(units),
            "spark": bullets(card["spark"]),
            "sibling": sibling,
            "sibling_files": "\n".join(marked),
            "tests": bullets(tests),
            "own_suites": "\n".join(own) or "        # none: this task has no suite of its own",
            "files": "\n".join(files),
            "row_files": "\n\n".join(rows),
            "curated": "\n\n".join(curated) or "None: no entry's line changes.",
            "skips": skips,
            "declines": bullets(declines),
            "bench": bullets(
                [
                    f"`{b.replace(' | ', '`, the section `')}`"
                    if not b.startswith("none | ")
                    else b[7:]
                    for b in card["bench"]
                ]
            ),
            "suites_catalyst": " ".join("*" + s for s in suites["catalyst"]),
            "suites_sql": " ".join("*" + s for s in suites["sql"]),
            "not_here": bullets(card.get("not-here", [])),
            "merged": f"`{merged[0]}`" if merged else "no",
        }
        used = set(re.findall(r"\{\{([a-z_]+)\}\}", template))
        if used != set(values):
            odd = sorted(used ^ set(values))
            raise Fail(f"{TEMPLATE}: placeholders and the generator's values differ: {odd}")
        body = re.sub(r"\{\{([a-z_]+)\}\}", lambda found: values[found.group(1)], template)
        head = f"<!-- Generated by dev/varka_fanout.py plan from {rel}, template {digest}."
        return f"{head} Do not edit above the end marker. -->\n{body.rstrip()}\n\n{END}\n"


    def cmd_plan(argv):
        if not argv:
            raise Fail("usage: plan CARD [--write|--check]")
        rel, card = load_card(argv[0])
        text = render(rel, card)
        path = os.path.join(ROOT, rel[:-4] + ".md")
        old = read(rel[:-4] + ".md") if os.path.exists(path) else ""
        if "--check" in argv:
            same = old.split(END)[0] == text.split(END)[0]
            print(f"fanout: {rel[:-4]}.md is {'current' if same else 'behind its card or the tree'}")
            return 0 if same else 1
        if "--write" in argv:
            tail = old.split(END, 1)[1] if END in old else OUTCOME
            with open(path, "w", encoding="utf-8") as f:
                f.write(text + tail)
            print(f"fanout: wrote {rel[:-4]}.md")
            return 0
        sys.stdout.write(text)
        return 0


    def cmd_files(argv):
        """Write what the plan gives word for word: the row files, the skip file, the census lines."""
        if not argv:
            raise Fail("usage: files CARD")
        rel, card = load_card(argv[0])
        if card.get("merged"):
            raise Fail(f"{rel}: a pilot's card of a merged entry writes no file")
        slug, family_slug = slugs()
        curated = f"sql/varka/plans/m{milestone()}/census_curated.txt"
        edits = curated_edits(rel, card, base_text(curated))
        named = names(card, milestone(), slug, family_slug)
        for path, text in given_files(card, named, edits, curated, read(curated)).items():
            full = os.path.join(ROOT, path)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w", encoding="utf-8") as f:
                f.write(text)
            print(f"fanout: wrote {path}")
        return 0


    COMMANDS["plan"] = cmd_plan
    COMMANDS["files"] = cmd_files


    def test_plans():
        card = parse_card(CARD)
        line = "abs|NUM,IVL|same|byte,short,bigint|semantics|330,104|the minimum overflows"
        new = curated_edit(line, line.split("|"), "int", "621")
        want = "abs|NUM,IVL|same|byte,short,bigint,int|semantics|330,104,621|the minimum overflows"
        assert new == want, new
        assert curated_edit(new, new.split("|"), "int", "621") is None
        part = "abs|NUM|same|byte,dec^|semantics|330|note"
        assert curated_edit(part, part.split("|"), "dec", "9") is None
        assert row_text(card["coverage"][0]) == [
            "sql: abs(i)",
            "family: Integer arithmetic",
            "form: projection",
            "domain: i > -2147483648",
        ]
        assert skip_cells(card)[0][3:] == ["fails", "the check is off by design"]
        named = names(card, 9, lambda t: "s" + str(len(t)), lambda t: t.lower().replace(" ", "-"))
        base = "# note\n" + line + "\nacos|double|double||ulp|367|Math\n"
        edits = curated_edits("c", card, base)
        wanted = line.replace("bigint|", "bigint,int|").replace("104|", "104,9|")
        assert edits == [(line, wanted)], edits
        got = given_files(card, named, edits, "m/census_curated.txt", base)
        assert got["sql/varka/rows/integer-arithmetic/s6.row"].startswith("sql: abs(i)\nfamily: ")
        skip = got["sql/varka/matrix/skips.d/varka-9-s38.tsv"]
        assert skip.count("\t") == 4 and skip.endswith("the check is off by design\n"), skip
        assert got["m/census_curated.txt"] == base.replace(line, edits[0][1])
        again = given_files(card, named, edits, "m/census_curated.txt", got["m/census_curated.txt"])
        assert "m/census_curated.txt" not in again
        try:
            curated_edits("c", card, got["m/census_curated.txt"])
            raise AssertionError("fused already")
        except Fail as e:
            assert "is fused over `int` already" in str(e), str(e)


    SELF_TESTS.append(test_plans)

### A.3 The block of part C (543 lines)

    # ---- The oracle (part C) --------------------------------------------------------------------

    import contextlib
    import html
    import io
    import json
    import time

    STEPS = ("card", "scope", "fused", "regen", "suites", "checks", "bench")
    FUSED_TEST = "every documented expression compiles to a fused kernel"
    RESULTS = "sql/core/benchmarks/{cls}-jdk25-runner-epyc7763{narrow}-results.txt"
    # Where sbt writes its JUnit reports, one `TEST-<package>.<suite>.xml` a suite, of a ScalaTest
    # suite and of a JUnit one alike.
    REPORT_DIRS = ("sql/catalyst/target/test-reports", "sql/core/target/test-reports")
    HARNESS = "VarkaCoverageHarnessSuite"


    def lock():
        path = os.environ.get("VARKA_HEAVY_LOCK", os.path.expanduser("~/proj/.varka-locks/heavy.lock"))
        return [] if path == "none" else ["flock", path]


    def run(command, log, locked=False):
        """Run a command with its output in `log` and no input, as `build/sbt -batch` runs; True
        when it exits 0."""
        env = dict(os.environ, SPARK_LOCAL_IP="127.0.0.1")
        os.makedirs(os.path.dirname(log), exist_ok=True)
        with open(log, "w", encoding="utf-8") as out:
            full = (lock() if locked else []) + command
            done = subprocess.run(
                full, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT
            )
            return done.returncode == 0


    def jdk_version():
        """java.runtime.version of the JVM sbt would start, JAVA_HOME's or else the first on PATH,
        read as dev/varka_regen_inputs.py reads it; None when no java starts."""
        home = os.environ.get("JAVA_HOME")
        java = os.path.join(home, "bin", "java") if home else "java"
        try:
            out = subprocess.run(
                [java, "-XshowSettings:properties", "-version"], capture_output=True, text=True
            ).stderr
        except OSError:
            return None
        found = re.search(r"java\.runtime\.version = (\S+)", out)
        return found.group(1) if found else None


    def jdk_errors(version):
        """Why no JVM step may start: the records and the results files are JDK 25's."""
        if version and re.match(r"25\b", version):
            return []
        return [
            f"the JDK is {version}, not a JDK 25: export "
            "JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 in the shell that runs the oracle"
        ]


    def host_only(old, new):
        """Whether two texts of the width audit's head differ in the `host` block alone."""
        a, b = json.loads(old), json.loads(new)
        return {k: v for k, v in a.items() if k != "host"} == {
            k: v for k, v in b.items() if k != "host"
        }


    def passed_tests(report):
        """The names of the test cases of one JUnit report of sbt's that passed: those with no
        failure, error or skipped element."""
        names = set()
        for attrs, closed, body in re.findall(
            r"<testcase\b([^>]*?)(/?)>(.*?)(?=<testcase\b|\Z)", report, re.S
        ):
            found = re.search(r'\bname="([^"]*)"', attrs)
            if found and (closed or not re.search(r"<(failure|error|skipped)\b", body)):
                names.add(html.unescape(found.group(1)))
        return names


    def report_text(suite, began, root=ROOT):
        """The text of sbt's reports of one suite that were written since `began`, the start of the
        step: an older report is another run's."""
        texts = []
        for folder in REPORT_DIRS:
            top = os.path.join(root, folder)
            for name in sorted(os.listdir(top)) if os.path.isdir(top) else []:
                full = os.path.join(top, name)
                if name.endswith(f".{suite}.xml") and os.path.getmtime(full) >= began:
                    texts.append(read(f"{folder}/{name}", root))
        return "".join(texts)


    def card_tests(card):
        """(suite, test) for each `test` line: a `coverage` test is the harness's, any other is the
        suite's that its file is named for."""
        pairs = []
        for value in card.get("test", []):
            where, _, test = value.partition(" | ")
            suite = HARNESS if where == "coverage" else os.path.basename(where).rsplit(".", 1)[0]
            pairs.append((suite, test))
        return pairs


    def not_passed(wanted, reports):
        """Why an sbt run that exited 0 is no pass: `testOnly` exits 0 when no test matched its
        filter. `wanted` is (suite, test) pairs, the test None for any test of the suite;
        `reports(suite)` is the text of the suite's reports of this run. A test is sought by the
        name the report gives it: a ScalaTest test's sentence, a JUnit test's `<method>()`, a
        factory-made test's `<factory>():<sentence>`."""
        errors = []
        for suite, test in wanted:
            text = reports(suite)
            if not text:
                error = f"{suite} wrote no report in this run: sbt ran no test of it"
            elif test is None:
                error = "" if passed_tests(text) else f"no test of {suite} passed in this run's report"
            elif test in passed_tests(text):
                error = ""
            else:
                error = f"the test `{test}` did not run and pass in {suite}"
            if error and error not in errors:
                errors.append(error)
        return errors


    def section_ratios(text, title):
        """The `Relative` cell of each case of a results file's section, by case name; None when
        the file has no such section."""
        lines = text.split("\n")
        for i, line in enumerate(lines):
            if line.strip() == title and i > 0 and lines[i - 1].startswith("====="):
                out = {}
                for row in lines[i + 2 :]:
                    if row.startswith("====="):
                        break
                    found = re.match(r"^(\S.*?)\s{2,}.*\s(\d+\.\d+)X\s*$", row)
                    if found:
                        out[found.group(1)] = float(found.group(2))
                return out
        return None


    def bench_verdict(card, changed, root=ROOT):
        """(failures, notes) of the card's benchmark sections: a section slower than the row path in
        a results file the branch brings fails; one the branch brings no numbers for is owed."""
        failures, notes = [], []
        for value in card["bench"]:
            cls, _, title = value.partition(" | ")
            if cls == "none":
                notes.append(f"no benchmark section: {title}")
                continue
            for narrow in ("", "-128bit"):
                rel = RESULTS.format(cls=cls, narrow=narrow)
                if not os.path.exists(os.path.join(root, rel)):
                    failures.append(f"{rel} is not committed: the baseline comes before the kernel")
                    continue
                ratios = section_ratios(read(rel, root), title)
                if ratios is None:
                    failures.append(f"{rel} has no section `{title}`")
                elif rel not in changed:
                    notes.append(f"owed: {rel} holds the baseline's numbers of `{title}`")
                elif "varka on" not in ratios or ratios["varka on"] < 1.0:
                    ratio = ratios.get("varka on")
                    failures.append(f"{rel}: `{title}` runs at {ratio}X of the row path")
        return failures, notes


    def unit_errors(card, root=ROOT):
        """The units the census does not count as fused."""
        folder = f"sql/varka/plans/m{milestone(root)}"
        census = [x.split("\t") for x in read(f"{folder}/census.tsv", root).split("\n")]
        casts = [x.split("\t") for x in read(f"{folder}/casts.tsv", root).split("\n")]
        errors = []
        for unit in card["unit"]:
            kind, a, b = unit.split(" ")
            if kind == "entry":
                line = [x for x in census if x[0] == a and len(x) >= 8]
                if not line or b not in line[0][7].split(","):
                    errors.append(f"{folder}/census.tsv does not count `{a}` as fused over `{b}`")
            else:
                line = [x for x in casts if x[:2] == [a, b] and len(x) >= 9]
                if not line or not line[0][8]:
                    errors.append(f"{folder}/casts.tsv does not count the cast `{a}` to `{b}` as fused")
        return errors


    def tree_of_worktree(root=ROOT):
        """The tree the worktree would commit with `git add -A`, through an index of its own."""
        index = os.path.join(root, "target", "varka-fanout", "index")
        os.makedirs(os.path.dirname(index), exist_ok=True)
        env = dict(os.environ, GIT_INDEX_FILE=index)
        for args in (["read-tree", "HEAD"], ["add", "-A"]):
            subprocess.run(["git", *args], cwd=root, env=env, check=True, capture_output=True)
        done = subprocess.run(["git", "write-tree"], cwd=root, env=env, check=True, capture_output=True)
        return done.stdout.decode().strip()


    def oracle_steps(rel, card, full_regen, logs):
        """Each step's name and the function that runs it and returns its failures."""
        suites = {
            "catalyst": ["VarkaCoverageSuite"],
            "sql": ["VarkaCoverageHarnessSuite", "VarkaCoverageDifferentialSuite"],
        }
        for value in card.get("suite", []):
            module, _, suite = value.partition(" | ")
            suites[module].append(suite)

        def step_scope():
            return scope_errors(card)[1]

        def step_fused():
            began = time.time()
            command = ["build/sbt", f'catalyst/testOnly *VarkaCoverageSuite -- -z "{FUSED_TEST}"']
            if jdk_errors(jdk_version()):
                return jdk_errors(jdk_version())
            if not run(command, f"{logs}/fused.log", True):
                return ["a coverage row does not fuse"]
            return not_passed([("VarkaCoverageSuite", FUSED_TEST)], lambda s: report_text(s, began))

        def step_regen():
            command = ["dev/varka_regen_generated.sh", "--all"] + (["--full"] if full_regen else [])
            if jdk_errors(jdk_version()):
                return jdk_errors(jdk_version())
            if not run(command, f"{logs}/regen.log"):
                return ["the regeneration failed"]
            # Under VARKA_HEAVY_LOCK=none the script's own flock leaves an empty file `none`.
            stray = os.path.join(ROOT, "none")
            if not lock() and os.path.isfile(stray) and os.path.getsize(stray) == 0:
                os.remove(stray)
            if AUDIT_HEAD in changed_paths():
                if not host_only(base_text(AUDIT_HEAD), read(AUDIT_HEAD)):
                    return [f"{AUDIT_HEAD} changed beyond its `host` block"]
                print(f"fanout: regen: the audit's host moved with the JDK: commit {AUDIT_HEAD}")
            return step_scope()

        def step_suites():
            began = time.time()
            command = ["build/sbt", "-Phive"]
            command += [
                f"{m}/testOnly " + " ".join("*" + s for s in suites[m]) for m in ("catalyst", "sql")
            ]
            if jdk_errors(jdk_version()):
                return jdk_errors(jdk_version())
            if not run(command, f"{logs}/suites.log", True):
                return ["a suite failed"]
            ran = [(suite, None) for m in ("catalyst", "sql") for suite in suites[m]]
            return not_passed(ran + card_tests(card), lambda s: report_text(s, began))

        def step_checks():
            failures = []
            for name, command in (
                ("rows", ["python3", "dev/varka_rows.py", "--check"]),
                ("push", ["python3", "dev/varka_push_check.py", "--no-jvm"]),
            ):
                if not run(command, f"{logs}/checks-{name}.log"):
                    failures.append(f"{' '.join(command)} failed")
            return failures + unit_errors(card)

        def step_bench():
            failures, notes = bench_verdict(card, set(changed_paths()))
            for note in notes:
                print(f"fanout: bench: {note}")
            return failures

        def step_card():
            path = os.path.join(ROOT, rel[:-4] + ".md")
            old = read(rel[:-4] + ".md") if os.path.exists(path) else ""
            if old.split(END)[0] != render(rel, card).split(END)[0]:
                return [f"{rel[:-4]}.md is behind its card or the tree"]
            return outcome_errors(old, bool(card.get("merged")))

        return (
            ("card", step_card),
            ("scope", step_scope),
            ("fused", step_fused),
            ("regen", step_regen),
            ("suites", step_suites),
            ("checks", step_checks),
            ("bench", step_bench),
        )


    def outcome_errors(plan, pilot):
        """A task's plan must hold its Outcome before the oracle stamps the tree: below the end
        marker the heading and each field, with text after each. A pilot's card of a merged entry
        has none to write."""
        if pilot:
            return []
        why = "section 9, the Outcome, is not written: "
        lines = plan.split(END, 1)[1].split("\n") if END in plan else []
        if HEADING not in lines:
            return [why + f"no line `{HEADING}` below the end marker"]
        marks = tuple(f"- {field}:" for field in FIELDS)
        errors = []
        for mark in marks:
            at = [i for i, line in enumerate(lines) if line.startswith(mark)]
            if not at:
                errors.append(why + f"no field `{mark}`")
                continue
            text = lines[at[0]][len(mark) :]
            for line in lines[at[0] + 1 :]:
                if line.startswith(marks):
                    break
                text += line
            if not text.strip():
                errors.append(why + f"the field `{mark}` is empty")
        return errors


    def run_steps(ident, steps, only, logs, clock=time.time):
        """Run the steps in order; (the first failed step or None, seconds)."""
        start = clock()
        for name, step in steps:
            if only and name not in only:
                continue
            began = clock()
            failures = step()
            took = int(clock() - began)
            for failure in failures:
                print(f"fanout: {name}: {failure}")
            print(f"fanout: {ident} step {name}: {'FAILED' if failures else 'passed'} in {took} s")
            if failures:
                return name, int(clock() - start)
        return None, int(clock() - start)


    def cmd_oracle(argv):
        if not argv:
            raise Fail("usage: oracle CARD [--only STEPS] [--full-regen]")
        rel, card = load_card(argv[0])
        ident = card["id"][0]
        only = argv[argv.index("--only") + 1].split(",") if "--only" in argv else []
        if [s for s in only if s not in STEPS]:
            raise Fail(f"--only takes steps of: {', '.join(STEPS)}")
        logs = f"target/varka-fanout/{ident}"
        steps = oracle_steps(rel, card, "--full-regen" in argv, logs)
        failed, seconds = run_steps(ident, steps, only, logs)
        if failed:
            print(f"fanout: FAILED {ident} at {failed} in {seconds} s (logs under {logs})")
            return 1
        if only:
            print(f"fanout: {ident}: {', '.join(only)} passed; only a run of every step says PASSED")
            return 0
        line = f"fanout: PASSED {ident} tree {tree_of_worktree()} in {seconds} s"
        with open(os.path.join(ROOT, logs, "stamp"), "w", encoding="utf-8") as f:
            f.write(line + "\n")
        print(line)
        return 0


    PASSED = re.compile(r"^fanout: PASSED (\S+) tree ([0-9a-f]{40}) in (\d+) s$", re.M)


    def ready_errors(card, body, head_tree, dirty, found, upstream="", pushed=(), false=""):
        """Why a pull request of this card may not open or merge. `found` is the scope check's
        errors; `upstream`, `pushed` and `false` are `push_state`'s."""
        errors = []
        lines = [x for x in PASSED.findall(body) if x[0] == card["id"][0]]
        if dirty:
            errors.append("the worktree has uncommitted changes")
        if len(lines) != 1:
            errors.append(f"the body holds {len(lines)} oracle line(s) of {card['id'][0]}, not one")
        elif lines[0][1] != head_tree:
            errors.append(f"the oracle passed on tree {lines[0][1]}, the head's tree is {head_tree}")
        errors += found
        if upstream:
            errors.append(
                f"the branch has an upstream, {upstream}: a fan-out branch tracks nothing; run "
                "`git branch --unset-upstream`, and do not push"
            )
        if pushed:
            errors.append(
                f"the branch is on {', '.join(pushed)}: it was pushed, or it holds no commit of "
                "its own; a fan-out builder does not push: stop and report this line"
            )
        if false:
            errors.append(
                f"findings.md says the lead pushed {false}, and that is no pushed commit of this "
                "branch: the line is the lead's to write; stop and report this line"
            )
        if len(re.findall(r"^Generated-by: \S.*\(.+\)\s*$", body, re.M)) != 1:
            errors.append("the body needs one line `Generated-by: <tool> (<model>)`")
        return errors


    LEAD_PUSHED = re.compile(r"pushed: ([0-9a-f]{40})\n")


    def push_state(findings="", root=ROOT):
        """(the upstream of the branch that HEAD is on, or "", the remote-tracking refs that hold
        a commit of the branch, and the commit of a false `pushed:` line, or ""): a fan-out builder
        never pushes, so its branch has none of them. The branch's commits are those since the
        merge base, and the head where there is none. In a fix round the lead's findings start
        `pushed: <the head the lead pushed>`, and the commits are those since that one."""
        ref = git("rev-parse", "--symbolic-full-name", "HEAD", root=root).strip()
        upstream = git("for-each-ref", "--format=%(upstream)", ref, root=root).strip()

        def commits(since):
            return git("rev-list", f"{since}..HEAD", root=root).split()

        def held(commit):
            refs = ["for-each-ref", "--contains", commit, "--format=%(refname)", "refs/remotes"]
            return git(*refs, root=root).split()

        since, lead, false = merge_base(root), LEAD_PUSHED.match(findings), ""
        if lead and lead[1] in commits(since) and held(lead[1]):
            since = lead[1]
        elif lead:
            false = lead[1]
        return upstream, sorted({r for c in commits(since) or ["HEAD"] for r in held(c)}), false


    def cmd_ready(argv):
        if len(argv) < 3 or argv[1] != "--body-file":
            raise Fail("usage: ready CARD --body-file FILE")
        _, card = load_card(argv[0])
        with open(argv[2], encoding="utf-8") as f:
            body = f.read()
        findings = f"target/varka-fanout/{card['id'][0]}/findings.md"
        errors = ready_errors(
            card,
            body,
            git("rev-parse", "HEAD^{tree}").strip(),
            bool(git("status", "--porcelain").strip()),
            scope_errors(card)[1],
            *push_state(read(findings) if os.path.exists(os.path.join(ROOT, findings)) else ""),
        )
        title = f"[VARKA-{card['row'][0]}] {card['title'][0]}"
        check = ["python3", "dev/varka_msg_check.py", "pr", "--title", title, "--body-file", argv[2]]
        if subprocess.run(check, cwd=ROOT).returncode != 0:
            errors.append("dev/varka_msg_check.py pr failed")
        for e in errors:
            print(f"fanout: ready: {e}")
        if not errors:
            print(f"fanout: READY {card['id'][0]} head {git('rev-parse', 'HEAD').strip()}")
        return 1 if errors else 0


    COMMANDS["oracle"] = cmd_oracle
    COMMANDS["ready"] = cmd_ready

    SECTION = """================
    INT abs(i), no nulls
    ================

    OpenJDK 64-Bit Server VM 25.0.5.0.3+1-LTS on Linux 7.0.0-1017-azure
    INT abs(i), no nulls over 2000000 Arrow-cached rows:  Best Time(ms)   Avg Time(ms)   Relative
    -----------------------------------------------------------------------------------------
    spark row path (varka off)                                       89            101       1.0X
    varka on                                                         79             88       {r}X


    ================
    INT other
    ================
    varka on                                                         79             88       9.9X
    """


    def test_oracle():
        assert section_ratios(SECTION.format(r="1.1"), "INT abs(i), no nulls") == {
            "spark row path (varka off)": 1.0,
            "varka on": 1.1,
        }
        assert section_ratios(SECTION.format(r="1.1"), "INT abs") is None
        seen = []

        def step(name, failures):
            return name, lambda: seen.append(name) or failures

        steps = [step("card", []), step("fused", ["a coverage row does not fuse"]), step("regen", [])]
        ticks = iter(range(0, 1000, 5))
        with contextlib.redirect_stdout(io.StringIO()) as printed:
            failed, seconds = run_steps("T", steps, [], "", clock=lambda: next(ticks))
            assert (failed, seconds, seen) == ("fused", 25, ["card", "fused"]), (failed, seconds, seen)
            seen.clear()
            assert run_steps("T", steps, ["card", "regen"], "", clock=lambda: next(ticks))[0] is None
            assert seen == ["card", "regen"], seen
        assert "fanout: T step fused: FAILED in 5 s" in printed.getvalue()
        card = parse_card(CARD)
        tree = "a" * 40
        body = f"text\nfanout: PASSED VARKA-9-abs-int tree {tree} in 1500 s\n\n"
        body += "Generated-by: Aider (Kimi K2.7 Code)\n"
        assert ready_errors(card, body, tree, False, []) == []
        assert len(ready_errors(card, body, "b" * 40, False, [])) == 1
        unnamed = ["dev/x.txt is changed and the plan does not name it", "the test `t()` is not in"]
        cut = ready_errors(card, body.replace("fanout: PASSED", "PASSED"), tree, True, unnamed)
        assert len(cut) == 4 and cut[2:] == unnamed, cut
        assert len(ready_errors(card, body.replace("Generated-by", "Made-by"), tree, False, [])) == 1
        tracked = ready_errors(card, body, tree, False, [], "refs/remotes/origin/master")
        assert len(tracked) == 1 and "has an upstream, refs/remotes/origin/master" in tracked[0]
        sent = ready_errors(card, body, tree, False, [], "", ["refs/remotes/fork/varka-9a-abs-int"])
        assert len(sent) == 1 and "the branch is on refs/remotes/fork/varka-9a-abs-int" in sent[0]
        wrong = ready_errors(card, body, tree, False, [], "", [], "c" * 40)
        assert len(wrong) == 1 and f"says the lead pushed {'c' * 40}, and that is no" in wrong[0]
        assert LEAD_PUSHED.match(f"pushed: {'c' * 40}\n\nA finding.\n")[1] == "c" * 40
        assert not LEAD_PUSHED.match(f"A finding.\npushed: {'c' * 40}\n")
        assert jdk_errors("25.0.5.1+2-1-26.04.1-Ubuntu") == [] and jdk_errors("25+36-LTS") == []
        assert len(jdk_errors("26.0.1+8")) == 1 and "the JDK is None" in jdk_errors(None)[0]
        head = '{"description": "d", "host": {"jdk": "25.0.5"}, "widths": ["128"]}'
        assert host_only(head, head.replace("25.0.5", "25.0.6"))
        assert not host_only(head, head.replace('"128"', '"256"'))
        stub = f"plan\n{END}\n{OUTCOME}"
        assert outcome_errors(stub, True) == []
        empty = outcome_errors(stub, False)
        assert len(empty) == 3 and empty[0].endswith("the field `- Built:` is empty"), empty
        written = stub.replace("- Built:", "- Built: the arm.").replace("gaps:", "gaps: none")
        written = written.replace("failures:\n", "failures:\n  fused, once: a missing import.\n")
        assert outcome_errors(written, False) == []
        assert outcome_errors(written.replace("- Plan gaps: none\n", ""), False) == [
            "section 9, the Outcome, is not written: no field `- Plan gaps:`"
        ]
        for gone in (f"plan\n{END}\n", "plan\n", written.replace(HEADING, "## Outcome")):
            assert outcome_errors(gone, False) == [
                "section 9, the Outcome, is not written: no line `## 9. Outcome` below the end marker"
            ], gone
        mine = "everyRow():Integer arithmetic: abs(i) matches the row engine under both consumers"
        report = '<testsuite name="s"><testcase classname="c" name="absOverAnInt()" time="1">\n'
        report += f'</testcase><testcase classname="c" name="{mine}" time="0.1"/>'
        report += '<testcase classname="c" name="a &gt; b" time="1">'
        report += '<failure message="m"/></testcase></testsuite>'
        assert passed_tests(report) == {"absOverAnInt()", mine}
        assert card_tests(card) == [("VarkaAbsIntSuite", "absOverAnInt()"), (HARNESS, mine)]
        assert not_passed(card_tests(card) + [("VarkaAbsIntSuite", None)], lambda suite: report) == []
        late = not_passed(card_tests(card), lambda suite: report.replace("absOverAnInt()", "other()"))
        assert late == ["the test `absOverAnInt()` did not run and pass in VarkaAbsIntSuite"], late
        suffix = not_passed([(HARNESS, mine.split(":", 1)[1])], lambda suite: report)
        assert len(suffix) == 1 and "did not run and pass in VarkaCoverageHarnessSuite" in suffix[0]
        fused = [("VarkaCoverageSuite", FUSED_TEST)]
        none_ran = '<testsuite name="s" tests="0"></testsuite>'
        assert not_passed(fused + [("VarkaCoverageSuite", None)], lambda suite: none_ran) == [
            f"the test `{FUSED_TEST}` did not run and pass in VarkaCoverageSuite",
            "no test of VarkaCoverageSuite passed in this run's report",
        ]
        assert not_passed(fused + fused, lambda suite: "") == [
            "VarkaCoverageSuite wrote no report in this run: sbt ran no test of it"
        ]


    SELF_TESTS.append(test_oracle)

### A.4 The block of part D (198 lines)

    # ---- The ledger (part D) --------------------------------------------------------------------

    COLUMNS = (
        "task",
        "row",
        "kind",
        "repo",
        "pr",
        "units",
        "builder",
        "tool",
        "opened",
        "closed",
        "outcome",
        "fix_rounds",
        "findings",
        "fixed",
        "kept",
        "oracle_s",
        "conflicts",
        "note",
    )
    KINDS = ("fanout", "pilot", "library", "other", "reference")
    OUTCOMES = ("open", "merged", "taken-over", "returned")
    # The owner's decision 3 of m9/PLAN.md: the models whose pull requests G5 counts.
    CHEAPER = ("DeepSeek Flash", "Kimi K2.7 Code", "GigaChat Pro")
    DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


    def ledger_errors(name, cells):
        """What is wrong with one ledger line, given its file's name and its cells."""
        if len(cells) != len(COLUMNS):
            return [f"{name}: {len(cells)} cells, not {len(COLUMNS)}"]
        line = dict(zip(COLUMNS, cells))
        errors = []
        if name != f"{line['repo'].rsplit('/', 1)[-1]}-{line['pr']}.tsv":
            errors.append("the file is not named <repository>-<pr>.tsv")
        if line["kind"] not in KINDS or line["outcome"] not in OUTCOMES:
            errors.append(f"kind is one of {KINDS} and outcome one of {OUTCOMES}")
        for key in ("row", "pr", "units"):
            if not line[key].isdigit():
                errors.append(f"{key} is a number")
        for key in ("fix_rounds", "findings", "fixed", "kept", "oracle_s"):
            if line[key] != "-" and not line[key].isdigit():
                errors.append(f"{key} is a number, or - where it is not known")
        if not DATE.match(line["opened"]) or not (DATE.match(line["closed"]) or line["closed"] == "-"):
            errors.append("opened and closed are dates as 2026-11-27, closed - while open")
        if (line["closed"] == "-") != (line["outcome"] == "open"):
            errors.append("a line is open exactly while it has no closed date")
        counts = [line[k] for k in ("findings", "fixed", "kept")]
        if all(c.isdigit() for c in counts) and int(counts[1]) + int(counts[2]) > int(counts[0]):
            errors.append("fixed and kept are more than the findings")
        if any(not c or c != c.strip() or "\t" in c for c in cells):
            errors.append("a cell is empty, or has a tab or a space at an end")
        return [f"{name}: {e}" for e in errors]


    def ledger_lines(root=ROOT):
        """(lines as dicts in the order they were opened, errors)."""
        top = os.path.join(root, fanout_dir(root), "ledger")
        lines, errors = [], []
        for name in sorted(os.listdir(top)) if os.path.isdir(top) else []:
            text = read(os.path.join(fanout_dir(root), "ledger", name), root)
            cells = text.rstrip("\n").split("\t")
            found = ledger_errors(name, cells)
            if text.count("\n") != 1:
                found.append(f"{name}: one line, ended by one newline")
            errors += found
            if not found:
                lines.append(dict(zip(COLUMNS, cells)))
        return sorted(lines, key=lambda x: (x["opened"], int(x["pr"]))), errors


    def cheaper(line):
        return line["builder"].startswith(CHEAPER)


    def summary(lines):
        """G5's report (m9/PLAN.md 1.2, item 8): the share and the finding counts."""
        fan = [x for x in lines if x["kind"] == "fanout"]
        by = {o: [x for x in fan if x["outcome"] == o] for o in OUTCOMES}
        landed = by["merged"] + by["taken-over"]
        out = [
            f"ledger: {len(lines)} line(s), {len(fan)} of them fan-out pull requests",
            f"fan-out: merged {len(by['merged'])}, taken over {len(by['taken-over'])}, "
            f"returned {len(by['returned'])}, open {len(by['open'])}",
            f"built by a cheaper model: {sum(cheaper(x) for x in by['merged'])} of {len(landed)} "
            "fan-out pull requests that landed",
        ]
        for builder in sorted({x["builder"] for x in lines}):
            own = [x for x in lines if x["builder"] == builder and x["kind"] != "reference"]
            if own:
                total = {
                    k: sum(int(x[k]) for x in own if x[k].isdigit())
                    for k in ("findings", "fixed", "kept")
                }
                rounds = sorted(int(x["fix_rounds"]) for x in own if x["fix_rounds"].isdigit())
                out.append(
                    f"{builder}: {len(own)} pull request(s), merged "
                    f"{sum(x['outcome'] == 'merged' for x in own)}; "
                    f"findings {total['findings']}, fixed {total['fixed']}, "
                    f"kept {total['kept']}; fix rounds {rounds}"
                )
        kinds = ", ".join(f"{k} {sum(x['kind'] == k for x in lines)}" for k in KINDS[1:])
        return out + [f"other kinds: {kinds}"]


    def cmd_ledger(argv):
        if "--add" in argv:
            given = dict(pair.split("=", 1) for pair in argv[argv.index("--add") + 1 :])
            if set(given) != set(COLUMNS):
                raise Fail(f"--add takes every column once: {' '.join(COLUMNS)}")
            cells = [given[c] for c in COLUMNS]
            name = f"{given['repo'].rsplit('/', 1)[-1]}-{given['pr']}.tsv"
            errors = ledger_errors(name, cells)
            if errors:
                raise Fail("; ".join(errors))
            top = os.path.join(ROOT, fanout_dir(), "ledger")
            os.makedirs(top, exist_ok=True)
            with open(os.path.join(top, name), "w", encoding="utf-8") as f:
                f.write("\t".join(cells) + "\n")
            print(f"fanout: wrote {fanout_dir()}/ledger/{name}")
            return 0
        lines, errors = ledger_lines()
        for e in errors:
            print(f"fanout: {e}")
        if "--summary" in argv:
            print("\n".join(summary(lines)))
        else:
            print("\t".join(COLUMNS))
            print("\n".join("\t".join(x[c] for c in COLUMNS) for x in lines))
        return 1 if errors else 0


    COMMANDS["ledger"] = cmd_ledger
    CHECKS.append(lambda: ledger_lines()[1])

    LINE = (
        "VARKA-621-abs-int\t621\treference\tvecbricks/varka\t1584\t1\tClaude Opus 5.5\tClaude Code"
        "\t2026-11-20\t2026-11-21\tmerged\t-\t6\t4\t2\t-\t-\tthe pilot entry of VARKA-658"
    )


    def test_ledger():
        cells = LINE.split("\t")
        assert ledger_errors("varka-1584.tsv", cells) == []
        assert len(ledger_errors("varka-1585.tsv", cells)) == 1
        for at, value in ((2, "fan-out"), (4, "x"), (9, "-"), (13, "5"), (16, ""), (17, " late")):
            bad = list(cells)
            bad[at] = value
            assert len(ledger_errors("varka-1584.tsv", bad)) >= 1, (at, value)
        assert len(ledger_errors("varka-1584.tsv", cells[:-1])) == 1

        def line(pr, kind, builder, outcome, findings="3", rounds="1"):
            closed = "-" if outcome == "open" else "2026-12-02"
            values = [
                "VARKA-667-x",
                "667",
                kind,
                "vecbricks/varka",
                pr,
                "2",
                builder,
                "Aider",
                "2026-12-01",
                closed,
                outcome,
                rounds,
                findings,
                findings,
                "0",
                "1500",
                "-",
                "none",
            ]
            assert ledger_errors(f"varka-{pr}.tsv", values) == [], values
            return dict(zip(COLUMNS, values))

        lines = [
            dict(zip(COLUMNS, cells)),
            line("1", "fanout", "Kimi K2.7 Code", "merged"),
            line("2", "fanout", "Kimi K2.7 Code", "taken-over", rounds="2"),
            line("3", "fanout", "Claude Opus 5.5", "merged"),
            line("4", "fanout", "GigaChat Pro", "returned"),
            line("5", "fanout", "DeepSeek Flash", "open"),
            line("6", "pilot", "DeepSeek Flash", "merged"),
        ]
        got = summary(lines)
        assert got[0] == "ledger: 7 line(s), 5 of them fan-out pull requests", got[0]
        assert got[1] == "fan-out: merged 2, taken over 1, returned 1, open 1", got[1]
        assert got[2] == "built by a cheaper model: 1 of 3 fan-out pull requests that landed", got[2]
        kimi = "Kimi K2.7 Code: 2 pull request(s), merged 1; findings 6, fixed 6, kept 0; "
        assert kimi + "fix rounds [1, 2]" in got, got
        assert got[-1] == "other kinds: pilot 1, library 0, other 0, reference 1", got[-1]


    SELF_TESTS.append(test_ledger)

## Appendix B. The guide

`sql/varka/ADDING_AN_EXPRESSION.md` after part E, whole. Take four spaces off every line.

    # Adding an expression to Varka

    Teaching Varka an expression over a type is the most common change this engine takes, and it
    follows the same path every time. This is that path, in the order the code runs, with the
    files named. `dev/varka_fanout.py check` holds every path and every `Varka...` class named on
    this page to the tree, in the docs job of every Build, so the page cannot fall behind it.

    Read [the architecture guide](../../docs/sql-varka.md) first, at least its glossary: *lane*,
    *word*, *morsel* and *epilogue* mean something specific here, and this page uses them without
    further explanation.

    ## Two ways to take the path

    **A fan-out task** fuses one to four (entry, type) pairs that need no new IR record, no new
    family and no new option: an arm in a family compiler, its coverage rows and its tests. Its
    plan is generated, not written: the coordinator writes a card under the current milestone's
    `fanout` folder (today `sql/varka/plans/m9/fanout/`), and `python3 dev/varka_fanout.py plan
    <card> --write` renders `sql/varka/plans/TEMPLATE_FANOUT.md` with it. The plan names every file
    the task may change, and one command, `python3 dev/varka_fanout.py oracle <card>`, says whether
    the task is done. The section "The fan-out task" below says what each command does.

    **A row of its own** is everything else: a new IR record, a new bounded lowering, a new
    family, a change to a default. It has a plan written by hand in the milestone's folder, and it
    takes the same ten steps below with the house's gate at the end (step 10).

    ## The shape of the change

    What the two pilot entries of VARKA-658 touched, read through today's layout
    (`python3 dev/varka_fanout.py scope <card> --against <commit>` prints the list):

    | | `abs` over an `INT` (#1584, 13 files) | `make_timestamp_ntz` and a cast (#1434, 23 files) |
    | :--- | ---: | ---: |
    | the task's own files: a family compiler, the IR or the emitter where a comment or a guard moves, its suites, a benchmark class | 3 | 11 |
    | a coverage row, as a file under `sql/varka/rows/` | 1 | 1 |
    | the row's generated records, written by the regeneration | 3 | 3 |
    | a matrix skip, as a file `sql/varka/matrix/skips.d/varka-<row>-<slug>.tsv` | 1 | 1 |
    | the census: the entry's line of the curated file, and the files the census tool writes | 2 | 4 |
    | the task's plan | 1 | 1 |
    | not the task's: the docs page's tables and the row's status in the milestone plan | 2 | 2 |

    Four things are true of every addition: an arm in the family that claims the expression, a
    coverage row, a test that holds the kernel to Spark's row engine, and a line of the census.

    ## The path

    Ten numbered steps, each ending in a command that either passes or tells you what is still
    missing. Run the check before starting the next step.

    **1. Decide the lowering before writing anything.** A Varka node computes over lanes with no
    branches. There are six lane types (`VarkaVectorIR`'s `LaneType`: `BYTE`, `SHORT`, `INT`,
    `LONG`, `FLOAT`, `DOUBLE`), and `VarkaTypes` says which lane a Spark type is read on. The
    arithmetic must be adds, subtracts, multiplies, shifts, compares and blends. If the natural
    formula needs a division, look for a magic-multiply form: `VarkaChronoLowering` is full of
    them. If it needs a branch, express it as a mask and a blend. If it can do neither, decline
    the expression, which is a normal outcome and not a failure. A lowering that is exact only
    inside a bound needs a proof file under `sql/varka/proofs/`, rendered by `VarkaProofFiles`;
    that is a row of its own, never a fan-out task.

    > *Check:* none, this step is a decision. A fan-out card states the lowering in its
    > `lowering` line; a row's plan states it before step 2.

    **2. Reuse a node, or add one.** Most additions need no new node: `abs` over an int is the
    negation's node with a flag, and `bit_get` is a shift, a mask and a narrowing. A new record of
    `VarkaVectorIR` states its properties in its `props()` (`VarkaNodeProps`) and joins the
    registries that hold a sample of every node; `sql/varka/plans/m8/VARKA-489.md`, "Adding a
    node, after 489c", lists them in order. Carry no literal *values* in a node: a folded constant
    is a literal slot, which lets two queries that differ only in their constants share one
    emitted class.

    > *Check:* `build/sbt catalyst/Test/compile` fails at every `switch` that does not yet handle
    > a new record, and rule R1 of `python3 dev/varka_push_check.py --no-jvm` names every registry
    > that lacks it.

    **3. Teach the compiler to build it** in the family that claims the expression.
    `VarkaExpressionCompiler` only classifies entries; `VarkaNodeCompiler` holds the chain of
    families (`VarkaFamily` lists them), and each family is a compiler of its own:
    `VarkaChronoCompiler`, `VarkaTimeCompiler`, `VarkaIntervalCompiler`, `VarkaConditionCompiler`,
    `VarkaIntegralCastCompiler`, `VarkaFloatingCastCompiler`, `VarkaDecimalCastCompiler`,
    `VarkaDecimalCompiler`, `VarkaFloatingCompiler`, `VarkaMathCompiler`, `VarkaRoundingCompiler`
    and `VarkaHashCompiler`. Match the Catalyst tree in a `switch` over type patterns, compile the
    children, and return the node, or decline with a reason (`VarkaDecline`), which the tests
    assert on. A wrong decline costs performance; a wrong acceptance costs correctness.

    > *Check:* `dev/varka_emit.sh "<your expression>" --columns <name>:<type>`. The entry must
    > read `FusedOutput(0)`. A `ResidualOutput` line is followed by the reason it declined, in the
    > compiler's own words.

    **4. Emit it,** if the node is new or its lane is. The emission walk is `VarkaVectorWalk`, and
    the lowerings of each kind are in `VarkaChronoLowering`, `VarkaConversionLowering`,
    `VarkaDivisionLowering`, `VarkaRoundingLowering` and `VarkaHashLowering`; `VarkaLoopEmitter`
    builds the class around them.

    > *Check:* the family's emitter suite: `build/sbt 'catalyst/testOnly *VarkaEmitter*Suite'`. A
    > new suite is a JUnit 5 class under `@VarkaSuite`.

    **5. Say what its nulls do.** A node that is null when any input is null needs nothing: that
    is the default. Anything else, a node that can produce a null from valid inputs or that is
    valid where an input is not, states it in its `props()`.

    > *Check:* `build/sbt 'catalyst/testOnly *VarkaIrFuzzSuite'`, which runs random trees over
    > random null patterns against `VarkaReferenceEvaluator`. `VarkaIrGrammar` must be able to
    > draw a new node: the suite fails on a node type it cannot reach.

    **6. Say what its range does.** A kernel that is exact only inside a range either proves the
    range at compile time (`VarkaRangeAnalysis`) or guards its batch: a batch outside the guard
    declines, and the row engine answers it. The coverage row then names the guard's predicate as
    its domain (step 7).

    > *Check:* `build/sbt 'sql/testOnly *VarkaCoverageHarnessSuite *VarkaCoverageDifferentialSuite'`:
    > every coverage row on Spark's row path and on Varka's, inside its domain, outside it and
    > where it is unknown, in LEGACY and in ANSI mode.

    **7. Add the coverage row, and regenerate.** A coverage row is a file,
    `sql/varka/rows/<family>/<slug>.row`, one `key: value` a line: `sql`, `family`, `form`, and
    where they apply `note`, `domain`, `verified` and `ansi-declined` (`sql/varka/plans/m9/VARKA-653.md`
    4.6 has each key). A row that needs a Catalyst expression built in code stays in
    `VarkaCoverageSuite`'s table. Then claim the pair in the census: add the type to the entry's
    fused types in the current milestone's `census_curated.txt` (today
    `sql/varka/plans/m9/census_curated.txt`). A test that a configuration of the option matrix
    breaks by design gets a skip, as a file `sql/varka/matrix/skips.d/varka-<row>-<slug>.tsv`. A
    fan-out task writes all three with `python3 dev/varka_fanout.py files <card>`. Then
    `dev/varka_regen_generated.sh --all` writes the row's three records beside it, the census and
    the cast tables. Do not touch `docs/sql-varka.md`: its tables are rendered in a commit of
    their own.

    > *Check:* `python3 dev/varka_rows.py --check` and `python3 dev/varka_census.py --check`, and
    > `build/sbt 'catalyst/testOnly *VarkaCoverageSuite'`, which compiles every row through the
    > real compiler and fails on a row that does not fuse.

    **8. Set the benchmark section.** A feature's baseline class times its sections on Spark's row
    path before the kernel exists, in a pull request of its own. The pull request that fuses a
    section sets the section's fusion class in that benchmark class, and the class's
    `...QueriesSuite` holds it in every Build. The numbers come from the AMD EPYC 7763 runners,
    dispatched by the lead, never from the laptop.

    > *Check:* the benchmark's queries suite, under
    > `sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark/`.

    **9. Write down what it taught you,** if it will outlive the task: a new `##` section in the
    right file under `sql/varka/skills/`, then `dev/varka_toc.py SKILLS.md --from sql/varka/skills`.

    > *Check:* `dev/varka_toc.py --check SKILLS.md --from sql/varka/skills`.

    **10. Close it out.** A fan-out task runs its oracle and `ready`, reports and stops: the lead
    opens its pull request. A row of its own runs the house's gate and its review as
    `dev/varka_ops/AGENTS.md` says, and checks its title and body with
    `python3 dev/varka_msg_check.py pr`. A pull request stays under 1000 counted lines either way.

    > *Check:* `python3 dev/varka_fanout.py oracle <card>` prints `fanout: PASSED`, or the gate of
    > `dev/varka_ops/pre-pr.sh` passes.

    ## The fan-out task

    | command | what it does |
    | :--- | :--- |
    | `python3 dev/varka_fanout.py plan <card> [--write\|--check]` | renders the task's plan from its card and the template; `--check` says whether the committed plan is still what the card and the tree give |
    | `python3 dev/varka_fanout.py files <card>` | writes the files the plan gives word for word: the coverage rows, the skip file, the census's lines |
    | `python3 dev/varka_fanout.py scope <card>` | prints each changed file with its class in the plan, and fails on a file the plan does not name |
    | `python3 dev/varka_fanout.py oracle <card>` | the one command that says the task is done: seven steps, the first failure named, and on success the line `fanout: PASSED <id> tree <tree> in <n> s` |
    | `python3 dev/varka_fanout.py ready <card> --body-file <file>` | says whether the task is ready for the lead: the head is the tree the oracle passed on, and the body file carries its line |
    | `python3 dev/varka_fanout.py ledger [--summary]` | the ledger: one line a pull request, and the share that the cheaper models built |

    The oracle's steps are `card`, `scope`, `fused`, `regen`, `suites`, `checks` and `bench`. It
    exits 0 when every step passed, 1 at the first step that failed, and 2 on a card that does not
    parse.

    ## Two things that catch people

    **A benchmark section is not optional if the expression is meant to be fast.** A performance
    claim traces to a committed results file, so an expression with no section has no claim.

    **Declining is a feature.** Every stage may refuse: the compiler on an expression it cannot
    translate, the emitter on a shape it will not build, the kernel on a batch whose values leave
    the range it proved. Each falls back to stock Spark for that batch and the query still returns
    the right answer. Write the refusal as deliberately as the acceptance, and test it.

## Appendix C. The template

`sql/varka/plans/TEMPLATE_FANOUT.md`, whole. Take four spaces off every line. Its long
command lines are one line each.

    # {{id}}: {{title}}

    *A fan-out task of row {{row}} of milestone {{milestone}}
    (`sql/varka/plans/m{{milestone}}/PLAN.md`, section 2.7). `dev/varka_fanout.py plan` wrote this
    plan from the card `{{card}}`. Every decision of the task is in this file; the four pages it
    sends you to are listed in section 0, in reading order, with what to take from each. A pilot's
    card of a merged entry: {{merged}}.*

    You fuse the pairs of section 1 and nothing else. You are done when the oracle of section 4
    prints `fanout: PASSED {{id}}`, the command of section 8 prints `fanout: READY {{id}}`, and you
    have sent the report of section 8. You do not push and you open no pull request: the lead does
    both. Where a step cannot be done as it is written, stop and report (section 7). Decide nothing
    yourself.

    ## 0. Before any edit

    **Read, in this order.**

    1. This file, whole.
    2. `docs/sql-varka.md`, its section "Glossary" alone: take the meaning of *lane*, *lane type*,
       *word*, *epilogue*, *fused kernel*, *literal slot*, *decline*, *ghost fallback* and *range
       guard*.
    3. `sql/varka/ADDING_AN_EXPRESSION.md`, its steps 3 to 7: take where a family compiler, the
       emission walk and a coverage row are. Its commands are not yours: yours are in section 4.
    4. `sql/varka/AGENTS.md`, its sections "Java code", "Processes" and "House rules that bite here
       specifically", and the root `AGENTS.md`, its section "Code Standards". Section 6 below quotes
       the rules you are most likely to break.

    **What those files say that is not for you.** This task ends at `READY`. Do not push. Do not
    open a pull request. Do not run `gh`. The root `AGENTS.md`'s section on pull requests is for the
    lead, not for this task. So are its sections "Pre-flight Checks" and "Investigations", the
    section "Pull requests: reviewed by their author before they open" of `sql/varka/AGENTS.md`,
    and all of `dev/varka_ops/AGENTS.md`. Where one of them tells you to ask the user, to push to a
    fork, to run `gh pr create` or to review with `/code-review`, this plan says otherwise, and for
    this task this plan wins: you ask nobody, your branch and your worktree are the "Set up" lines
    below, the lead reviews, and your last command is section 8's.

    **Words this plan uses.**

    - *To fuse* an expression: to make the compiler build a node for it, so that it runs in a fused
      kernel. *To decline*: to refuse it with a reason; Spark's row engine then answers.
    - *The row engine*: stock Spark's evaluation, one row at a time. It is the reference.
    - *LEGACY*, *ANSI*, *TRY*: Spark's three ways to treat an overflow or a bad value: wrap or
      return null, raise an error, return null. A node's `Overflow` mode is `WRAP`, `FAIL` or `NULL`.
    - *A coverage row*: one SQL expression in the list of what fuses. Its *domain* is the predicate
      inside which the kernel answers; outside it the batch goes to the row engine.
    - *The census*: the list of Spark's functions with the types each is fused over.
    - *The lead*: the agent that gave you this task, reviews it and opens its pull request.

    **Set up,** from the repository's root:

        git worktree add --no-track -b {{branch}} ../{{branch}} origin/master && cd ../{{branch}}
        dev/varka_precommit.sh --install-hook
        python3 dev/varka_fanout.py plan {{card}} --check
        python3 dev/varka_fanout.py scope {{card}}

    The `plan --check` line must print `fanout: {{plan}} is current`. If it prints `is behind its
    card or the tree`, stop and report: the plan is stale. The `scope` line must print `fanout:
    scope: 0 file(s), 0 not named by the plan`.

    `origin` is the remote of `vecbricks/varka`, whose master the task starts from. `--no-track`
    leaves the branch with no upstream: you never push it, and section 8's command refuses a
    branch that has an upstream.

    **The two lines before every JVM command.** Your shell may forget variables between commands.
    So every command block of section 4 starts with these two lines; type them each time:

        export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1
        LOCK="${VARKA_HEAVY_LOCK:-$HOME/proj/.varka-locks/heavy.lock}"

    The laptop runs one heavy JVM at a time. Start every `build/sbt` command as `flock "$LOCK"
    build/sbt ...`, so that it waits for its turn. Start `dev/varka_regen_generated.sh` and
    `dev/varka_fanout.py oracle` with no `flock`: they take the lock themselves, and a second
    `flock` around them never returns.

    ## 1. What to fuse, and what defines the answer

    | pair | kind | where the census holds it |
    | :--- | :--- | :--- |
    {{units}}

    Spark's class and the methods that define the answer. Read them under
    `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/` before you write code. The answer
    of Spark's row engine is the reference: the kernel's answer equals it bit for bit, or the
    kernel declines and the row engine answers.

    {{spark}}

    **The lowering.** {{lowering}}

    ## 2. The sibling to copy

    {{sibling}}

    It is the commit below. Read it with `git show <its hash> -- <file>`, one file at a time. Copy
    the shape of its arm from its main source, and the shape of your tests from the one test file
    that is marked. Copy nothing from a file marked "do not copy" or "never".

    {{sibling_files}}

    ## 3. The files, in order

    Change these files and no other. The class says who writes a file: `edit` and `add` are
    yours, by hand; `row`, `skip` and `curated` are written by the `files` command below, with the
    texts below; `record`, `census`, `casts` and `host` are written by the regeneration inside the
    oracle, never by hand (`host` changes only when the laptop's JDK is newer than the one the
    file names, and the oracle then tells you to commit it); `plan` is this file, where you write
    section 9 and nothing above it.

    For an `edit` or `add` file, "what goes in" has four parts: `where` is the place, `when` is
    the condition under which the new code applies, `copy` is the existing code to model it on,
    and `result` is what the new code does.

    | # | file | class | what goes in |
    | ---: | :--- | :--- | :--- |
    {{files}}

    **Start with one command.** It writes the three kinds of file below, word for word, so that
    you type none of them:

        python3 dev/varka_fanout.py files {{card}}

    It prints `fanout: wrote <file>` for each. Then `git status --short` shows them.

    **The coverage rows.** Each file holds exactly this text:

    {{row_files}}

    **The census.** In `sql/varka/plans/m{{milestone}}/census_curated.txt` exactly these lines
    change. Each change adds the pair's type to the entry's fused types and row {{row}} to its
    rows:

    {{curated}}

    **The matrix skips.**

    {{skips}}

    **The tests.** The task adds these tests, and the oracle fails while one of them is missing
    from your change or did not run and pass. Each name is the name sbt's report gives the test,
    character for character. `<method>()` is a JUnit `@Test` method. `<factory>():<sentence>` is a
    test that a JUnit `@TestFactory` method makes, and `<method>(<parameter types>):<n>` is the
    n-th case of a `@ParameterizedTest`: for these two you add the method, and the report makes
    the rest of the name. Any other name, in a `.scala` file, is the sentence of a ScalaTest
    `test("...")`, word for word:

    {{tests}}

    Put your compile tests (what compiles, what declines and why) and your answer tests in the
    test files of the table above. Do not add a test to `VarkaExpressionCompilerSuite.scala`:
    every task would edit that file. A suite this plan adds is a JUnit 5 class under `@VarkaSuite`,
    and each `@Test` carries its sentence as a `@DisplayName`. A new file starts with the license
    header of a neighbouring file of its type: copy it. Together the tests hold at least these
    cases; the lead's review reads them, since the oracle checks only that the named tests exist
    and pass:

    1. each pair of section 1 compiles to a fused kernel;
    2. the kernel's answer equals the row engine's over a batch that holds a null row, zero, the
       least and the greatest value of the operand's type and the values next to them, in LEGACY
       and in ANSI mode, at a batch length that is no multiple of the lane count; and where an
       operand or the result is a 64-bit type (`bigint`, `time`, `ntz`, `ltz`, `dt`, `double`), at
       the default lane count, at two long lanes and at eight;
    3. a batch with no live row runs and answers nothing;
    4. one case for each decline of section 5, which asserts the reason's text.

    **The benchmark.**

    {{bench}}

    For a named section, set its fusion class in the benchmark class to `Fusion.WHOLE` where the
    files above say so. Run no benchmark: the lead dispatches it on the runners.

    ## 4. The commands, in order

    1. While you work, compile, ask whether your rows fuse, and run your own suites:

            export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1
            LOCK="${VARKA_HEAVY_LOCK:-$HOME/proj/.varka-locks/heavy.lock}"
            flock "$LOCK" build/sbt catalyst/Test/compile
            flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaCoverageSuite -- -z "every documented expression compiles to a fused kernel"'
    {{own_suites}}

       A ScalaTest run passes when sbt prints `Tests: succeeded N, failed 0`. A JUnit run passes
       when sbt prints `Passed: Total N, Failed 0, Errors 0, Passed N`. Run no other test of
       `VarkaCoverageSuite` here: the rest of it reads the records, which only the oracle writes.
    2. Write section 9 of this file, the Outcome, now. It stands below the end marker and has a
       heading and three fields; keep the heading line and the three field names as they are, and
       write after each colon, on that line and on lines under it:
       - `- Built:` what you built, in two or three sentences;
       - `- Plan gaps:` each place where this plan was wrong or left you a choice, or `none`;
       - `- Oracle failures:` each oracle run that failed, with its step and your fix, or `none`.

       The oracle's `card` step fails with `section 9, the Outcome, is not written` and the reason
       while the heading is gone, a field is gone or a field is empty. After the oracle prints `PASSED`
       you change no tracked file: what you write later goes under `target/varka-fanout/{{id}}/`,
       which git ignores.
    3. Commit your work. `dev/varka_precommit.sh` checks the staged files and prints one line a
       finding; fix each finding and run the three commands again until it prints none:

            git add -A
            dev/varka_precommit.sh
            git commit -m "[VARKA-{{row}}] {{title}}" -m "Generated-by: <your tool> (<your model>)"

       In the second `-m`, write the name of the tool you run in and of your model, as `Generated-by:
       Aider (Kimi K2.7 Code)`. Every commit message ends with exactly one such line and no other
       trailer; the commit hook refuses a `Co-Authored-By` line, a session link and a second
       `Generated-by` line.
    4. Run the oracle. It is the one command that says the task is done:

            export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1
            python3 dev/varka_fanout.py oracle {{card}}

       It runs seven steps and stops at the first that fails: `card` (this plan is current and its
       section 9 is written), `scope` (you changed only files of section 3, and each test of
       section 3 is in your change), `fused` (every coverage row compiles to a fused kernel),
       `regen` (the regeneration writes the records, and changes no file this plan does not name),
       `suites` (`catalyst/testOnly {{suites_catalyst}}` and, under `-Phive`, `sql/testOnly
       {{suites_sql}}`; then each test of section 3 must be in sbt's reports as passed), `checks`
       (the records, the house checks, the size, and the census counts each pair as fused) and
       `bench`. Each step prints `fanout: {{id}} step <name>: passed in <n> s`. On a failure the
       last line is `fanout: FAILED {{id}} at <step> in <n> s (logs under
       target/varka-fanout/{{id}})`: read the step's log, fix, add the failure and the fix to
       section 9, commit as in step 3 with the message `"Fix <step> of {{id}}"`, and run the oracle
       again. On success the last line is

            fanout: PASSED {{id}} tree <40 hex digits> in <n> s

    5. Commit what the regeneration wrote, and check that the commit is the tree that passed:

            git add -A && git commit -m "Regenerate the records of {{id}}" -m "Generated-by: <your tool> (<your model>)"
            git rev-parse 'HEAD^{tree}'

       The second command prints the 40 hex digits of the oracle's line. If it prints others, you
       changed a file after the oracle: run the oracle again, and commit again.

    ## 5. The declines to keep

    Each of these declines today and must still decline after your change, with this reason,
    held by this test:

    {{declines}}

    ## 6. The house rules that bind this change

    From the repository's `AGENTS.md` files, word for word:

    > Never remove, skip or weaken a failing test (`ignore`, `assume(false)`, `@unittest.skip`, or an
    > expected value or golden file updated just to match), or mock the code under test, unless asked.

    > Keep source lines within 100 characters; the linters enforce this for Scala, Java, and Python.

    > Every new text file that `dev/.rat-excludes` does not already match needs the Apache License
    > header, copied from a neighboring file of the same type

    > **The Varka code carries no `TODO` or `FIXME` comments, and should not start.**

    > **A comment describes the code as it is.**

    > **Never kill by name pattern**

    > **Never `git stash`.**

    > **Write only inside your own worktree and scratch area.**

    > **Never `git push` by hand.**

    > **Never print the environment** (`env`, `printenv`, `set`): it holds tokens.

    > The ghost fallback is a correctness contract: a Varka failure degrades to the
    > row engine and never fails a query.

    New code is Java 25 in the house style: a `record` for a data carrier, a `switch` with no
    `default` over a sealed type or an enum, explicit imports, ASCII only. No allocation, stream,
    lambda or boxing on a path that runs for each batch or each row.

    ## 7. What not to do

    - No `git push`, no `gh pr create`, no comment on GitHub. The lead opens every fan-out pull
      request, after its own gate and review; a branch you pushed would start CI on a tree nobody
      reviewed.
    - No new IR record, no new emit option, no change to a default, no new family.
    - No coverage row that is built in code or is a math form: such a row stays in the Scala table
      of `VarkaCoverageSuite`, and its task is no fan-out task. If section 3 seems to need one,
      stop and report.
    - No edit of a file that section 3 does not list. `python3 dev/varka_fanout.py scope {{card}}`
      prints each changed file with its class, and `NOT NAMED` for a file you may not change.
    - Not `docs/sql-varka.md`: the lead renders its tables in a commit of their own. Not
      `sql/varka/plans/m{{milestone}}/PLAN.md` or a row's file: the lead keeps the row's status. Not
      the ledger: the lead writes your line.
    - No record, census or cast table edited by hand; no test removed or weakened; no benchmark
      run; no JVM started outside the lock.
    - Also, for this task:

    {{not_here}}

    **When to stop.** Stop and report, with the command and its output, when: a check of section
    0 fails; a pair cannot fuse as the lowering says; the oracle's `regen` or `scope` step names a
    file this plan does not list; a test of another feature fails; the oracle fails twice at one
    step for a reason you cannot fix inside section 3's files. A report is the right result then:
    the task returns to the lead.

    ## 8. How to hand the task over

    This task ends at `READY`. Do not push. Do not open a pull request. Do not run `gh`. The root
    `AGENTS.md`'s section on pull requests is for the lead, not for this task. You write the pull
    request's body, check it, report, and end your work.

    1. Write the body to `target/varka-fanout/{{id}}/body.md`. Git ignores that folder, so the
       file does not change the tree the oracle passed on. It holds the five headings of
       `.github/PULL_REQUEST_TEMPLATE`, word for word and in order, each paragraph on one line.
       What goes under each:

       | heading | what you write under it |
       | :--- | :--- |
       | `### What changes were proposed in this pull request?` | one or two sentences: which pairs of section 1 now fuse, and how, from section 1's lowering in your own words |
       | `### Why are the changes needed?` | one or two sentences that open with the reason in plain words, not with a task number: what a query with these pairs paid before |
       | `### Does this PR introduce _any_ user-facing change?` | the sentence `No answer changes.` and one sentence that says which queries can now run in a fused kernel |
       | `### How was this patch tested?` | one sentence that names the tests of section 3; then, on a line of its own, the oracle's `fanout: PASSED` line, copied whole |
       | `### Was this patch authored or co-authored using generative AI tooling?` | your `Generated-by: <your tool> (<your model>)` line and nothing else |

       An example, the body of the task that fused `abs` over an `INT`. The tree, the seconds, the
       tool and the model are yours, not these:

            ### What changes were proposed in this pull request?

            `abs` over an `INT` column now fuses. The compiler builds the int negation's node with its `abs` flag, and the emitter lets the int lane reach the lanewise `ABS` after the compare against the least int.

            ### Why are the changes needed?

            An int `abs` in a projection or a filter sends the whole batch to the row engine today, while the same call over a bigint or a smallint fuses.

            ### Does this PR introduce _any_ user-facing change?

            No answer changes. A query with `abs` over an int column can now run in a fused kernel.

            ### How was this patch tested?

            The coverage row `abs(i)` is answered by the kernel inside its domain and declined outside it, in the coverage harness.

            fanout: PASSED VARKA-621-abs-int tree <40 hex digits> in <n> s

            ### Was this patch authored or co-authored using generative AI tooling?

            Generated-by: <your tool> (<your model>)

    2. Check:

            python3 dev/varka_fanout.py ready {{card}} --body-file target/varka-fanout/{{id}}/body.md

       It must print `fanout: READY {{id}} head <the commit>`. Otherwise it prints one line a
       reason, each starting `fanout: ready: `, and above them the lines of
       `dev/varka_msg_check.py` for the body. Each reason names its own fix:

       | the reason | what you do |
       | :--- | :--- |
       | `<file> is changed and the plan does not name it` | the file is none of section 3: put it back as master has it (`git checkout origin/master -- <file>`, or delete it if you added it), then go back to section 4, step 2 |
       | ``the test `<name>` is not in the lines the change adds to <file>`` | the test of section 3 is missing or has another name: add it or rename it, then go back to section 4, step 2 |
       | `the worktree has uncommitted changes` | `git status --short` shows them; a file of section 3: go back to section 4, step 2; any other file: put it back with `git checkout -- <file>`, or delete it if it is new |
       | `the body holds <n> oracle line(s) of {{id}}, not one` | copy the oracle's last line into the body, once |
       | `the oracle passed on tree <a>, the head's tree is <b>` | a tracked file changed after the oracle: go back to section 4, step 4 |
       | ``the body needs one line `Generated-by: <tool> (<model>)` `` | write it under the last heading, once |
       | `dev/varka_msg_check.py pr failed` | read the `pull request body:` and `pull request title:` lines printed above it and fix the body as each says; a title's fault is the card's: stop and report |
       | `the branch has an upstream, <ref>` | run `git branch --unset-upstream`, then `ready` again |
       | `the branch is on <refs>: it was pushed` | a commit of this branch is on a remote. Stop and report the line (section 7): the lead decides. Write no `findings.md` and change none |
       | `findings.md says the lead pushed <commit>` | the first line of `findings.md` is the lead's, and it is wrong: stop and report the line |

    3. Send this report to the lead, and stop:

            fanout: READY {{id}} head <the commit>
            branch: {{branch}}
            worktree: <the output of pwd>
            body: target/varka-fanout/{{id}}/body.md
            oracle: <the fanout: PASSED line>

    **The fix round.** The lead reviews the branch, opens the pull request, and may give a
    builder, you or another, the file `target/varka-fanout/{{id}}/findings.md` in this worktree.
    Its first line, `pushed: <commit>`, is the lead's record of the head it pushed: `ready` reads
    it, and you never write or change it. Under it is one finding a paragraph. Then:
    fix each finding, or answer it in section 9 with your reason; add to section 9 what you
    changed; commit as in section 4, step 3, with the message `"Address the review of {{id}}"`;
    run section 4's steps 4 and 5 again; replace the `fanout: PASSED` line in the body; run
    `ready` again; send the report again. One round: after it the lead merges, takes the task
    over, or returns it.

## Appendix D. The two pilot cards

`sql/varka/plans/m9/fanout/VARKA-621-abs-int.txt`. Take four spaces off every line; every
value is on one line, however long.

    # The first pilot of VARKA-658: the entry that pull request #1584 merged on 21 November 2026.
    # Its plan is checked against that commit (dev/varka_fanout.py scope --against).
    id: VARKA-621-abs-int
    row: 621
    title: Fuse abs over an int
    branch: varka-621a-abs-int
    merged: 43cf0d94acc
    unit: entry abs int
    spark: Abs.nullSafeEval: the answer in LEGACY mode, Math.abs, where abs of the least int is the least int
    spark: Abs.doGenCode: the answer in ANSI mode, x < 0 ? MathUtils.negateExact(x) : x, which raises on the least int
    sibling: 230773d872d | sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaEmitterLongLaneSuite.scala | The long arm of abs and of the negation: the arm of Abs over a long in VarkaNodeCompiler, and the node IntNeg with its abs flag.
    lowering: The int negation's node IntNeg with its abs flag: one lanewise ABS on the int lane after the negation's compare against the least int, which is checked under ANSI unless the operand's bounds exclude that value.
    edit: sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaNodeCompiler.java | where: the switch that holds the arm `case UnaryMinus n when isInt(n)`, a new arm right after that arm; when: `case Abs a when isInt(a)`; copy: the arm `case Abs a when isLong(a)` of the same switch, with intOperand and boundedByIntMax in the place of longOperand and its bound, as the int negation's arm has them; result: an IntNeg with its abs flag true, Overflow.FAIL when a.failOnError() and the operand is not bounded, Overflow.WRAP otherwise
    edit: sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaVectorIR.java | where: the javadoc of the record IntNeg; when: always; copy: nothing, it is a comment alone; result: the sentence on the lanewise ABS names the int lane beside the narrow and the floating lanes
    edit: sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaVectorWalk.java | where: the method emitIntNeg, the test that stands before the lanewise ABS, and the method's javadoc; when: n.abs() at LaneType.INT; copy: the long lane's test in the same method, which throws on Overflow.NULL; result: an int abs in the mode Overflow.NULL throws VarkaErrors.illegalArgument, any other int abs falls through to the lanewise ABS after the negation's compare, and the long lane keeps its blend
    coverage: abs(i)
    coverage-family: Integer arithmetic
    coverage-form: projection
    coverage-note: `abs(Int.MinValue)` is out of range, and wraps back to itself in the legacy mode
    coverage-domain: i > -2147483648
    skip: checkIntOverflow=false
    skip-suite: VarkaCoverageHarnessSuite
    skip-test: Integer arithmetic: abs(i) is answered inside its domain and where it is unknown, and declined outside with the verdict's status, in a projection and a filter
    skip-verdict: fails
    skip-reason: the overflow check is off by design, so an overflowing lane computes the wrapped value instead of raising or nulling
    test: coverage | domains():Integer arithmetic: abs(i) is answered inside its domain and where it is unknown, and declined outside with the verdict's status, in a projection and a filter
    no-own-test: #1584 merged with no test file of its own; its review kept "no emitter-level matrix test for IntNeg(..., abs=true) at the int lane (the harness covers the answers)"
    bench: none | no baseline class holds an int abs, and the pull request merged with none

`sql/varka/plans/m9/fanout/VARKA-340-time-to-ntz.txt`:

    # The second pilot of VARKA-658: the entry and the cast that pull request #1434 merged on
    # 10 November 2026, the pull request of m9/PLAN.md 2.6 (23 files, 18 of them shared).
    id: VARKA-340-time-to-ntz
    row: 340
    title: Fuse make_timestamp_ntz and the cast of a TIME to TIMESTAMP_NTZ
    branch: varka-340a-time-to-ntz
    merged: 39a404b73ec
    unit: entry make_timestamp_ntz date
    unit: entry make_timestamp_ntz time
    unit: cast time ntz
    spark: MakeTimestampNTZ.replacement: a StaticInvoke of DateTimeUtils.makeTimestampNTZ
    spark: DateTimeUtils.makeTimestampNTZ: the day's midnight plus the time's microseconds, raising past the long
    sibling: 77a323f0da3 | sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTimestampNtzCastSuite.java | The casts between DATE and TIMESTAMP_NTZ: the arm in VarkaTimeCompiler, its cast suite and its coverage rows.
    lowering: The date's cast to TIMESTAMP_NTZ plus the time's whole microseconds, the add checked in every mode; the cast of a TIME is the same node over the query's current date, a literal.
    edit: dev/varka_census.py | where: the table VIA; when: the entry make_timestamp_ntz; copy: the line of time_from_micros above it; result: one line that gives make_timestamp_ntz the class MakeTimestampNTZ
    edit: sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaNodeCompiler.java | where: after the method that casts a DATE to TIMESTAMP_NTZ; when: a day is taken to its midnight on the long lane; copy: the body of that cast, moved out of it; result: three static helpers that the cast and the time family both call, hasNtzMidnight(long day), midnightOf(day, sink) and ntzMidnightDay(day)
    edit: sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaTimeCompiler.java | where: the switch over a StaticInvoke's method name, a new arm, with a new private method makeTimestampNtz; and the switch over expressions, a new arm for the cast; when: `case "makeTimestampNTZ"`, and a Cast of a TIME to TIMESTAMP_NTZ that the optimizer left unrewritten; copy: the neighbouring arms of the same two switches for the dispatch, and the guard of the cast of a DATE to TIMESTAMP_NTZ in VarkaNodeCompiler; result: the midnight plus the time's nanoseconds divided by 1000 in a checked add, a date column guarded to the days hasNtzMidnight admits, a date literal checked when the kernel compiles and taken as a slot, and the unrewritten cast declined with a reason that names the rewrite
    edit: sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaFamily.java | where: the description string of the TIME family's constant; when: always; copy: nothing, it is a text alone; result: the string names make_timestamp_ntz(date, time) and the cast of a TIME to TIMESTAMP_NTZ
    edit: sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCatalystGrammar.java | where: the productions of TIME, after the casts to TIME; when: the fuzzer draws a TIMESTAMP_NTZ from a TIME; copy: the Production lines above it; result: one Production, make_timestamp_ntz(DATE'2021-06-01', $0), over a date literal alone, with a comment that says why not the cast and why not a date column
    edit: sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTimestampNtzCastSuite.java | where: new @Test methods after the tests of the date casts; when: the column form, the literal form and the cast; copy: the test dateToNtzExhausted of the same suite, with its helpers and its loop over the lane counts; result: the three tests of this card's `test` lines, each at the default, two and eight long lanes, in LEGACY and in ANSI mode
    edit: sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaSqlResolve.scala | where: the object VarkaSqlResolve; when: a test needs an expression as written, before the current-time rewrite; copy: the method resolve, cut in two; result: parseAsWritten and resolveAsWritten, and resolve applies ComputeCurrentTime after them
    edit: sql/core/src/test/java/org/apache/spark/sql/execution/VarkaDomains.java | where: the map of the rows whose domain is total; when: the row over a date literal and the row of the cast; copy: the entry of hour(t) + 1 above them; result: two Map.entry lines, each with the reason why its add cannot overflow
    edit: sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaTimestampNtzFieldsBaselineBenchmark.java | where: the Section lines of CAST(tm AS TIMESTAMP_NTZ) and of make_timestamp_ntz(d, tm); when: always; copy: the Section line of CAST(ntz AS TIME(3)) above them; result: the last argument of each line, whether the section fuses, is true
    edit: sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaTimestampNtzSuite.scala | where: new tests after the tests of the date casts; when: a session runs the two forms; copy: the tests of the date cast in the same suite, with withAnsi and the declined-batch metrics; result: the three tests of this card's `test` lines
    coverage: make_timestamp_ntz(d, t)
    coverage-family: TIMESTAMP_NTZ fields
    coverage-form: projection
    coverage-note: the date guarded to the days whose midnight is a long, as the cast of a date is
    coverage-domain: unix_date(d) BETWEEN -106751991 AND 106751991 AND if(d IS NULL OR t IS NULL, NULL, try_make_timestamp_ntz(d, t) IS NOT NULL)
    coverage: make_timestamp_ntz(DATE'2021-06-01', t)
    coverage-family: TIMESTAMP_NTZ fields
    coverage-form: projection
    coverage-note: a literal date is checked when the kernel is compiled and takes a slot, so every date shares one kernel
    coverage-domain: if(t IS NULL, NULL, try_make_timestamp_ntz(DATE'2021-06-01', t) IS NOT NULL)
    coverage: CAST(t AS TIMESTAMP_NTZ)
    coverage-family: TIMESTAMP_NTZ fields
    coverage-form: projection
    coverage-note: the query's current date at the time of day: the optimizer rewrites the cast to make_timestamp_ntz of the date literal
    coverage-domain: if(t IS NULL, NULL, try_make_timestamp_ntz(current_date(), t) IS NOT NULL)
    skip: checkIntOverflow=false
    skip-suite: VarkaTimestampNtzCastSuite
    skip-test: make_timestamp_ntz of a date and a time answers every day whose midnight is a long as Spark does, and declines a batch past those days or past the long
    skip-verdict: fails
    skip-reason: the overflow check is off by design, so an overflowing lane computes the wrapped value instead of raising or nulling
    skip: checkIntOverflow=false
    skip-suite: VarkaTimestampNtzCastSuite
    skip-test: a date literal is checked when make_timestamp_ntz compiles, and every date in range shares one kernel
    skip-verdict: fails
    skip-reason: the overflow check is off by design, so an overflowing lane computes the wrapped value instead of raising or nulling
    skip: checkIntOverflow=false
    skip-suite: VarkaTimestampNtzSuite
    skip-test: make_timestamp_ntz past the long raises from the row engine, and the day before the first is answered there, its batch alone leaving the kernel
    skip-verdict: fails
    skip-reason: the overflow check is off by design, so an overflowing lane computes the wrapped value instead of raising or nulling
    test: sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTimestampNtzCastSuite.java | makeTimestampNtzAtTheEdges()
    test: sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTimestampNtzCastSuite.java | makeTimestampNtzOverALiteral()
    test: sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTimestampNtzCastSuite.java | timeToNtz()
    test: sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaTimestampNtzSuite.scala | make_timestamp_ntz answers every day whose midnight is a long on the kernel, in every mode
    test: sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaTimestampNtzSuite.scala | make_timestamp_ntz past the long raises from the row engine, and the day before the first is answered there, its batch alone leaving the kernel
    test: sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaTimestampNtzSuite.scala | CAST(time AS TIMESTAMP_NTZ) carries one date a query, on the kernel and on the batches a date field declines, and two zones share its kernel and answer their own dates
    suite: catalyst | VarkaTimestampNtzCastSuite
    suite: sql | VarkaTimestampNtzSuite
    bench: VarkaTimestampNtzFieldsBaselineBenchmark | TIMESTAMP_NTZ make_timestamp_ntz(d, tm), no nulls
