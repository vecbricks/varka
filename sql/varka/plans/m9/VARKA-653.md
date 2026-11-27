# VARKA-653: The files every type row appends to or regenerates, one file an entry

*Planned on 27 November 2026, and revised twice the same day, after two builder's reviews,
after the owner's word to split the row (the census's files leave it for row 696: 11.1, edit
2; appendix A) and after the owner's answers to its questions (section 13). The plan stands
alone: an agent needs this file and the repository's `AGENTS.md` files. The row is not
started: milestone 9 waits for the owner's word (`m9/PLAN.md` decision 18) and for milestone
8's close, and every part that moves a list also waits for row 654's two parts and for row
656's part B (section 12). The code facts were checked on master `84d6b2aff10`, in the
worktree `/home/max/proj/varka-m9-read-w`; the first of them were read on `73fad30ead4`,
which differs from it in plan files alone; the old texts of section 11 and the other plans'
lines were matched on master `1d68f7d8d89`. Every script, function and edit of an existing
tool that this plan gives was run on a scratch copy of the first commit's files: the two
scripts whole, the tool as each part leaves it, and the existing tools' own tests with parts
B, D and F applied (3.3). No JVM ran.*

## 1. What this row is

**The row's text** (`m9/PLAN.md` section 3, row 653): "The files every type row appends to or
regenerates, one file an entry. [...] **Done when** three branches that each add a sibling
entry of one family, as the pilot's second round will, and two that add entries of different
families, merge with master in either order under `git merge-tree` with no merge driver, with
no conflict and no regeneration owed; a stale generated file fails CI with its command; and
every tool and suite that reads the lists gives the same output before and after".

**The problem in one paragraph.** A task that fuses one expression over one type edits about
eight files that every other such task edits too: the coverage table's source, three
generated JSON files, the docs page, the skip list and two census files. Git merges a text
file line by line. Two branches that insert a line at the same place of one file conflict, and
so do two branches that both rewrite one line that is computed from every row. GitHub's merge
runs no merge driver, so the driver of row 546 does not help a pull request. Each merge to
master therefore leaves the other open branches in conflict, and each of those costs a merge of
master, a regeneration on the laptop and a new Build.

**What the row gives.** After it, what a task writes for its coverage row is files of its own:
the row, its three generated records, its matrix skip. The docs page's tables are rendered in
a commit of their own. Two such branches merge on GitHub with nothing to resolve in those
files, and the merged master is green with nothing regenerated.

**What the row does not give, by the owner's split of 27 November 2026.** The census's files
stay as row 654 leaves them: the curated file, `census.tsv`, `casts.tsv`, `casts_fused.tsv`
and `casts_suites.tsv`. Two branches that edit neighbouring lines of the curated file, or
the same line, still conflict there and in `census.tsv`. Section 4.9 gives the measured rate
of each, and row 696, which 11.1's edit 2 files, removes them.

**So the done-when is not met to the letter, and this plan says which part is.** The
done-when's five branches each add "an entry". Where a branch adds a coverage row, a skip and
the generated records, they merge in every order with nothing owed (the demonstration's round
1, 4.10). Where a branch also claims its entry in the curated census, as a real fusing task
does (29 of 51 row-adding commits, fact 3), three siblings whose entries are neighbours in
the curated file conflict in that file and in `census.tsv` (round 2). 11.1's edit 1 rewrites
the row's done-when to what this row meets; the owner signed it on 27 November 2026.

**Two outputs change on purpose, against the done-when's third clause.** "Every tool and
suite that reads the lists gives the same output before and after" holds for parts A1, A2, B,
E and F, each by a byte comparison in its sheet. It does not hold for two things, both needed
for the first clause, and the owner signed them with the edited done-when (section 13):

- Part C changes the bytes file's `option_arms` values, once, and adds `option_arm_rows`:
  an arm's digest over every row becomes one over the fuzz shapes and one a row (4.4).
- Part D changes the header line of `m8/results/VARKA-612-refused.txt`, which loses a count
  over every value row, and changes who writes three blocks of the docs page (4.5). The
  blocks' text is unchanged at the part.

**What it gives the rows after it.**

| row | what it reads from this row |
| :--- | :--- |
| 658 (the task template and its oracle) | the files a generated task plan names: one `.row` file a coverage row, three record files a row that the regeneration writes, at most one skip file (4.2 to 4.8); the commands `python3 dev/varka_rows.py --check` and `dev/varka_regen_generated.sh --all`; and that a task does not touch `docs/sql-varka.md` |
| 659 (the pilot) | its second round, three sibling casts in flight together, is the case of this row's done-when; they still share the census's files until the new row |
| 667 to 669 and 672 to 674 (the fan-out) | parts of one family in flight together merge in any order in the files this row moves |
| 657, 682 to 690 and every full-strength row | the same files; a row whose coverage rows need Scala code keeps them in the Scala table (4.6) |
| 677 (the close) | the command that refreshes the docs page's tables (4.5) |
| row 696 (11.1, edit 2) | `dev/varka_rows.py`'s views, `slug` and the demonstration's round 2, which is its test |

**What must be merged before it starts.** This plan. The tag `varka-m8` (decision 11 and the
owner's decision of 27 November 2026: nothing of milestone 9 starts before milestone 8 closes).
For parts A1 and A2, nothing else. For parts B to F: row 654's parts a and b and row 656's
part B, since their build sheets are cut for the lists as single files (section 12).

**The goals it serves.** `m9/PLAN.md` 3.1 puts row 653 under G5 alone.

- **G5**, quoted: "**G5. Delivery by cheaper models.** After the foundation, each fan-out task
  is one task plan from a template, with a one-command oracle; most fan-out pull requests are
  built by the cheaper models, each through the same review, and a ledger (row 658) records
  each one's builder and what its review found." The item of 1.2 it moves is item 8: "**G5:**
  the share of fan-out pull requests the cheaper models built, and their review finding
  counts, reported from row 658's ledger; no threshold." The row moves it only through rows
  658, 659 and the fan-out: `m9/PLAN.md` 2.7 says "That makes the oracle's time the fan-out's
  rate: the pilot measures it, and row 653 keeps a task's regeneration to its own files.", and
  section 5 says "**Row 653 is late.** Then the fan-out runs one pull request at a time, each
  merge costing the others a regeneration." A reader checks that the row moved it by the
  done-when's own test: `python3 dev/varka_rows_demo.py` exits 0 and prints `round 1: 0
  file(s) in conflict or stale`.
- **G1, G3 and G4** it serves only through the fan-out rows: it fuses nothing and moves no
  census cell.

**What is out of the row** is in 4.11, each excluded thing with the row that holds it.

## 2. What an agent must know

### 2.1 The idea

A list that many branches append to is kept as one file an entry. Where a suite or a tool
needs the whole list as one file, that file is assembled from the entries, is ignored by git,
and is called a *view*. Nothing that is computed from every entry is committed in an entry
branch: a digest over every row becomes one digest a row, and the docs page's tables are
rendered in a commit of their own.

The row keeps every reader's input the same. A suite still renders `coverage.json` as one
document and compares it with one file; that file is now the view. So "every tool and suite
that reads the lists gives the same output before and after" is checked by one byte
comparison of each view with the file it replaces (part B, command 5).

### 2.2 Terms

- **Coverage row** (or **row**): one line of the coverage table, a SQL expression that the
  compiler fuses, with its family, its notes and its domain. Today each is a `Row(...)` in the
  Scala source of `VarkaCoverageSuite`; 868 on the plan's commit. This plan's "row" is always a
  coverage row; a line of the task table is "row 653" with its number.
- **Family**: a titled group of coverage rows (`Family(title, predicates, rows)`), 63 on the
  plan's commit. A family's rows are either all projections or all predicates.
- **Entry**: one thing a task adds to a shared list: a coverage row, a matrix skip, a census
  claim.
- **Sibling entries**: entries of one family added by different branches, which today land on
  the same or on neighbouring lines.
- **Generated file**: a committed file that a suite or a tool writes and a check holds to what
  it renders: `sql/varka/coverage.json` (`VarkaCoverageSuite`), `sql/varka/emitted_bytes.json`
  (`VarkaEmittedBytesSuite`), `sql/varka/width_audit.json` (`VarkaWidthAuditSuite`, on the
  laptop's CPU only), the census files and four blocks of `docs/sql-varka.md`.
- **Aggregate**: a generated value computed from every row: an `option_arms` digest of
  `emitted_bytes.json`, a line of the docs' verification counts, a cell of the docs' support
  table. Any two branches that add rows both rewrite it, and no merge can compute it. Two
  branches that both raise a count by one merge with no conflict and leave it one short.
- **Record**: one row's part of a generated file, in a file of its own under
  `sql/varka/rows/`.
- **Head**: what a generated file holds that belongs to no row (its description, the column
  map, the fuzz digests, the audit's host), in one file a kind under `sql/varka/rows/`.
- **View**: a generated file assembled from its head and its records, at the path the file
  has today. Git ignores it. `python3 dev/varka_rows.py --views` writes it; the test JVM and
  the Python tools write it before they read it.
- **Slug**: the file name made from a text: its letters and digits in lower case, every other
  run of characters one dash, cut to 40 characters, then a dash and the first eight hex digits
  of the text's SHA-256. `slug("CAST(i AS TINYINT)")` is `cast-i-as-tinyint-b36ebbbe`. A
  family's directory is its title's letters and digits with dashes and no hash: `integral-casts`.
- **Row file**: a hand-written file `sql/varka/rows/<family>/<slug>.row` that adds a coverage
  row without editing the Scala table (4.6).
- **Merge driver**: `dev/varka_generated_merge.py` (row 546), which git runs in a clone that
  installed it, and which GitHub never runs.
- **The demonstration**: `dev/varka_rows_demo.py`, the done-when's test as a script (4.10).
- **The render commit**: a commit that changes `docs/sql-varka.md`'s generated blocks and
  nothing else, made by `dev/varka_ops/render-docs.sh` (4.5).

### 2.3 The names this row reads, and the names it adds

Read, as they are on the plan's commit:

| name | where | what it is |
| :--- | :--- | :--- |
| `VarkaCoverageSuite` | catalyst tests, Scala, package `...codegen.varka` | holds the table (`families`), renders `coverage.json` (`renderJson`), the docs' coverage table (`render`), the verification counts (`verificationCounts`) and the family switches (`renderSwitches`); regenerates under `VARKA_COVERAGE_REGEN=true` |
| `VarkaEmittedBytesSuite` | the same package | renders `emitted_bytes.json` (`document`, `render`); `armDigest` is one digest an option arm and width; regenerates under `VARKA_BYTES_REGEN=true` |
| `VarkaWidthAuditSuite` | the same package | renders `width_audit.json`; regenerates under `VARKA_AUDIT_REGEN=true`, on the host its file names |
| `VarkaMatrixRuns.skips()` and `SKIPS_PATH` | catalyst tests, Java | reads `sql/varka/matrix/skips.tsv` |
| `VarkaAnsiForm.DECLINED` | sql/core tests, Java | the positions where a coverage row is expected to decline, by the row's SQL |
| `render_json` | `dev/varka_generated_merge.py` | Jackson's pretty printer in Python, byte for byte |
| `dev/varka_regen_generated.sh --all` | | every regeneration, in order, under the heavy lock |
| `dev/varka_census.py`, its `main` | | writes the census, the cast matrix and the docs' support table; `--check` fails when any of the three is stale |

Added:

| name | part | what it is |
| :--- | :--- | :--- |
| `dev/varka_rows_demo.py` | A1 | the demonstration |
| `dev/varka_rows.py` with `--views`, `--split KIND [--from FILE]`, `--check`, `--show KIND [REV]`, `--self-test`; its functions `views(root)`, `assemble(kind, tree)`, `slug(text)`, `family_slug(title)` | A2 | the records' tool |
| `VarkaRowFiles` (catalyst tests, Java, package `...codegen.varka`, public): `static Path view(String name)`, `static void write(String kind, String text)`, `static void views()`; package-private `static String run(String... args)` and `static String home()` | A2 | the test JVM's door to the tool |
| `VarkaRowFilesSuite` (JUnit 5, `@VarkaSuite`, the same package) | A2 | the door's tests |
| `sql/varka/rows/` and `sql/varka/.gitignore` | B | the records, the heads, and the three ignored views |
| `option_arm_rows` in the bytes view; `"arms"` in a bytes record | C | one digest a row, arm and width |
| `dev/varka_rows.py --docs [--check]`; `dev/varka_census.py --docs`; `dev/varka_ops/render-docs.sh` | D | the docs page's three row-dependent blocks, rendered apart |
| `sql/varka/rows/<family>/<slug>.row`; `VarkaRowFiles.rows()`, `VarkaRowFiles.parseRow(String path, String text)` and the public record `VarkaRowFiles.RowFile` | E | a coverage row as a file |
| `sql/varka/matrix/skips.d/varka-<row>-<slug>.tsv`; `VarkaMatrixRuns.SKIPS_DIR` (public) and `skipsText(Path)` (package-private) | F | a matrix skip as a file |

## 3. The admission check

Each fact was checked on master `84d6b2aff10`. `$T` is
`sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka`, `$TJ` the
same path with `java` for `scala`, and `$S` is `$T/VarkaCoverageSuite.scala`.

### 3.1 What conflicts, measured

**No merge driver.** The clone has row 546's driver installed (`git config --get-regexp
'^merge\.'` prints three `merge.varka-generated` lines), and `git merge-tree` runs an
installed driver. So every measurement below ran through a bare repository that shares the
clone's objects and has no configuration, as GitHub's merge has none:

    git init -q --bare /tmp/nodriver.git
    git rev-parse --git-common-dir | sed 's|$|/objects|' > /tmp/nodriver.git/objects/info/alternates
    export GIT_DIR=/tmp/nodriver.git

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 1 | The row's admission check: #1434 touched 23 files | `git show --stat --format= 39a404b73ec \| tail -1` | `23 files changed, 607 insertions(+), 66 deletions(-)` |
| 2 | Of 440 merge commits of the clone since 10 November 2026, replayed from their two parents with no driver, 194 conflict | for each line `m p1 p2` of `git log --all --merges --since=2026-11-10 --format='%h %p'`: `git merge-tree --write-tree --name-only --no-messages p1 p2` | by file: `m8/PLAN.md` 82 (row 646's), `emitted_bytes.json` 54, `docs/sql-varka.md` 36, `width_audit.json` 8, `coverage.json` 6, `m8/census.tsv` 5, `VarkaExpressionCompilerSuite.scala` 4, `skips.tsv` 3, `VarkaCoverageSuite.scala` 2, `census_curated.txt` 2 |
| 3 | 51 squash commits of master since 1 November 2026 change `VarkaCoverageSuite.scala`; of them `docs/sql-varka.md` changes in 48, `coverage.json` in 44, `width_audit.json` in 43, `emitted_bytes.json` in 41, `census.tsv` in 30, `census_curated.txt` in 29, `VarkaExpressionCompilerSuite.scala` in 28, `skips.tsv` in 20, `VarkaProofFiles.java` in 20, `VarkaAnsiForm.java` in 19, `VarkaFamily.java` in 15, `VarkaDomains.java` in 9, `casts.tsv` and `casts_fused.tsv` in 9 each | `git log --first-parent --no-merges --since=2026-11-01 --format=%h -- $S`, then `git diff --name-only c^ c` for each | the counts |
| 4 | Each of those commits against the one before it, as two branches off one base (`git merge-tree --write-tree --merge-base=b^ a^ b`, counting only files both changed): 41 of 50 pairs conflict | the same list, pairwise | shared and in conflict: `emitted_bytes.json` 31 and 29, `docs/sql-varka.md` 44 and 26, `m8/PLAN.md` 44 and 14, `coverage.json` 37 and 7, `width_audit.json` 34 and 7, `VarkaCoverageSuite.scala` 50 and 5, `VarkaExpressionCompilerSuite.scala` 15 and 5, `VarkaAnsiForm.java` 7 and 4, `skips.tsv` 9 and 3, `VarkaProofFiles.java` 8 and 2, `census.tsv` 16 and 1, `census_curated.txt` 14 and 1 |
| 5 | The two files that conflict most do so in aggregates: for the pair `43cf0d94acc` (`abs` over an int) and `c155e691e6a` (the trigonometric functions), `emitted_bytes.json` conflicts in the `option_arms` digests and `docs/sql-varka.md` in the `int` line of the verification counts | `git merge-tree --write-tree --merge-base=c155e691e6a^ 43cf0d94acc^ c155e691e6a`, then `git show <tree>:<file> \| grep -n -A4 '^<<<<<<<'` | the two hunks |
| 5a | The docs page's conflicts by block: of fact 2's 36, the verification counts in 27, the support table in 10, the coverage table in 6, the family switches in 2, prose in 1; of fact 4's 26, the counts in 22, the coverage table in 7, the support table in 3, the switches in 1 | each conflicting merge's page, read for the block that holds each `<<<<<<<` | the counts |
| 6 | The done-when's five branches, built on the plan's commit by the demonstration: with what belongs to a coverage row alone, three siblings conflict in 6 files and two rows of two families in 2; with the census's edits too, three siblings conflict in 8 | `python3 dev/varka_rows_demo.py` (4.10, part A1) | the output of 5.1's command 2 |

Facts 2 to 5 were measured once, by two scripts in the planner's scratch folder; fact 6 is the
one a builder runs, and part A1 commits its output.

### 3.2 What the files are, and who reads them

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 7 | The coverage view holds 868 rows in 63 families; each family's rows are contiguous, each SQL text is unique and ASCII, and the longest is 81 characters | `python3 -c "import json;d=json.load(open('sql/varka/coverage.json'));s=[e['sql'] for e in d['expressions']];print(len(s),len(set(s)),max(map(len,s)),len({e['family'] for e in d['expressions']}))"` | `868 868 81 63` |
| 8 | `emitted_bytes.json` holds one map a lane count (`4`, `16`) keyed by the row's SQL in the coverage view's order, seven fuzz sections a lane count, and 24 `option_arms` names with one digest a width | `python3 -c "import json;d=json.load(open('sql/varka/emitted_bytes.json'));print(list(d['lanes']),len(d['lanes']['4']['coverage']),len(d['lanes']['4'])-1,len(d['option_arms']))"` | `['4', '16'] 868 7 24` |
| 9 | `width_audit.json` holds one sorted map a width (`128`, `256`, `512`) with 886 shapes: the 868 rows and 18 that start `construction: ` | `python3 -c "import json;d=json.load(open('sql/varka/width_audit.json'));k=list(d['widths']['128']);print(list(d['widths']),len(k),k==sorted(k),sum(x.startswith('construction: ') for x in k))"` | `['128', '256', '512'] 886 True 18` |
| 10 | An arm's digest is one hash over every coverage row and the 30,000 shapes of three fuzz sequences | `grep -n "sha((rowLines ++ shapeLines).mkString)" $T/VarkaEmittedBytesSuite.scala` | one line, the last of `armDigest` |
| 11 | The three files are opened at 28 places of the test sources: 21 in Scala as `getWorkspaceFilePath("sql", "varka", "<file>")`, 5 in Java as `Path.of(home, "sql", "varka", "<file>")` (one of them `home()`), 2 in Java as `new File(home, "sql/varka/coverage.json")` | `git grep -c 'getWorkspaceFilePath("sql", "varka", "\(coverage\|emitted_bytes\|width_audit\)\.json")' -- sql/catalyst/src/test sql/core/src/test` and the two Java spellings (5.3, B0) | nine files with 1, 1, 3, 5, 1, 4, 2, 1 and 3; five lines; two lines |
| 12 | Each suite writes its file at one place | `grep -n "Files.writeString(file, rendered)" $S`; `grep -c "Files.write(path, render().getBytes(StandardCharsets.UTF_8))" $T/VarkaEmittedBytesSuite.scala $T/VarkaWidthAuditSuite.scala` | two lines of `$S`, of which the one under `"sql", "varka", "coverage.json"` is the file's; `1` and `1` |
| 13 | `docs/sql-varka.md` has four generated blocks; `VarkaCoverageSuite` writes three, the census the support table | `grep -c "BEGIN generated" docs/sql-varka.md`; `grep -n "verificationMarks -> verificationCounts())" $S` | `4`; one line |
| 14 | The docs' coverage table and verification counts are functions of the coverage view and the audit view alone: a Python rendering of both, from the two files, equals the committed blocks byte for byte | the functions of 5.5 step 1, run on the plan's commit | `coverage table same`, `verification counts same` |
| 15 | Splitting the three files into records and assembling them again gives the same bytes; 2,724 files: three heads, 99 under `_admitted`, 18 under `_construction` and three a row in 63 family directories | `dev/varka_rows.py --split` of each kind, then `--views`, then `cmp`, on a scratch copy | three times `same`; `--check` exits 0 |
| 16 | The driver's file list, which the regeneration script and the push check read, is the `merge=varka-generated` lines of `sql/varka/.gitattributes` and the docs page | `python3 dev/varka_generated_merge.py --list` | nine paths under `sql/varka` and `docs/sql-varka.md` |
| 17 | The skip list is read by `VarkaMatrixRuns.skips()`, by `skip_lines` of `dev/varka_matrix_report.py`, by `rule_skips_tsv` of `dev/varka_lint.py` and by the skip recheck of `dev/varka_ops/pre-pr.sh` | `git grep -n "SKIPS_PATH)" -- sql/catalyst/src/test`; `grep -n "^def skip_lines\|^def rule_skips_tsv" dev/varka_matrix_report.py dev/varka_lint.py`; `grep -n "^skips=sql/varka/matrix/skips.tsv" dev/varka_ops/pre-pr.sh` | the lines |
| 18 | The skip list has 447 lines and `VarkaExpressionCompilerSuite.scala` 4,309 | `wc -l sql/varka/matrix/skips.tsv $T/../VarkaExpressionCompilerSuite.scala` | `447`, `4309` |
| 19 | 782 `Row(` constructions, 178 lines with an interpolated string and 20 rows built as a Catalyst expression make the table code, not data | `sed -n '/private val families: Seq\[Family\] = Seq(/,/private def out(/p' $S \| grep -c 'Row('` | `782` |
| 20 | `VarkaAnsiForm.declined` holds 66 rows' SQL in one packed list, several to a line, for the position "the right side of a false AND" | `sed -n '/for (String sql : List.of("make_date(2021, i, 1)"/,/out.put(sql + "\\t" + FALSE_AND/p' sql/core/src/test/java/org/apache/spark/sql/execution/VarkaAnsiForm.java \| grep -o '"[^"]*"' \| grep -v '"\\t"' \| wc -l` | `66` |
| 21 | A new text file needs a license header unless `dev/.rat-excludes` matches its name; `.json`, `.tsv` and `.gitignore` are matched | `grep -n -x '\.\*\\\.json\|\.\*\\\.tsv\|\.gitignore' dev/.rat-excludes` | three lines |
| 22 | No branch of this row exists | `git branch -a --list '*varka-653*'` | nothing |
| 23 | Row 547 (one digest a row for each option arm) is planned in the catalogue and not started | `tail -3 sql/varka/plans/m10/VARKA-547.md` | `## 10. Outcome`, an empty line, `Not started.` |

In the tables a `\|` inside a command stands for `|`, escaped for the table.

### 3.3 The existing tools' own tests, with the parts applied

Facts 24 and 26 ran in a scratch clone of the repository, made and used so:

    git clone -q --shared /home/max/proj/vecbricks-varka clone && cd clone
    git checkout -q --detach 84d6b2aff10 && git update-ref refs/remotes/origin/master HEAD
    python3 dev/varka_generated_merge.py --self-test 2>&1 | grep "FAILED\|^self-test:"

which printed two `FAILED: 5:` lines and `self-test: 87 case(s), 2 failed` (the laptop's JDK
is the audit's, and nothing differs from `origin/master`); then, with part A2's two scripts
copied in and, made in the tree and not committed, the edits of part B's steps 1, 3, 5, 7 and
9, two of step 4's three lines and, of step 6, the regeneration script's and the push
check's, the same command printed `self-test: 87 case(s), 0 failed`. The other facts ran in
a copy of `dev/`, of `sql/varka/` and of the documents with generated blocks, made a
repository of one commit, with `sql/catalyst` and `sql/core` linked.

| # | what ran | with | result |
| ---: | :--- | :--- | :--- |
| 24 | `python3 dev/varka_generated_merge.py --self-test` | part B's records, `.gitattributes`, `.gitignore`, `modules.py`, regeneration script and the four edits of the tool itself (5.3 step 7) | `self-test: 87 case(s), 0 failed`, where the clean tree printed `2 failed` for case 5 (see above). Without the edits of step 7 three cases fail or crash: case 22 (`"sql/varka/coverage.json" in files`), case 4 (the docs module must hold the two JSON files) and case 5 (it opens `sql/varka/width_audit.json`) |
| 25 | the same, with the three JSON files' attribute lines kept as `linguist-generated` | | case 22 fails with `attributes read unlike git`: a line of the attributes file must match a tracked file, and the views are not tracked. So part B removes the three lines |
| 26 | `python3 dev/varka_push_check.py --self-test` | part B | `Ran 22 tests`, `OK`. With the regeneration script reading the host from `rows/_audit.json` three tests fail: their fixture holds `sql/varka/width_audit.json` alone. So the script keeps reading the view, which it writes first |
| 27 | `python3 dev/varka_regen_inputs.py --selftest`, `dev/varka_precommit.sh --selftest`, `python3 -m doctest dev/sparktestsupport/modules.py` | part B | `self-test passed`, `selftest: ok`, no failure |
| 28 | `python3 dev/varka_lint.py --selftest` | part F's edit of the rule, and its new case | `varka_lint selftest: ok (74 cases)`, 73 before |
| 29 | `python3 dev/varka_census.py --check`, `--docs`, `--self-test` | part D's edit of `main` | exit 0; with one cell of the support table changed by hand, `--check` prints the notice and exits 0, and `--docs` restores the page byte for byte |
| 30 | the demonstration with the tool as parts A2, D, E and F leave it, on records made by `--split` from the plan's commit's files | each stage of 4.10 | round 1: 6, 4, 3, 2, 1 and 0 files |
| 31 | Every workflow that runs a test JVM over Varka's suites runs on a GitHub-hosted image, with no `self-hosted` label and no `container:`: Ubuntu on x86 and on aarch64 for the sbt workflows, and those two and `macos-26` for the Maven one | `grep -l "build/sbt\|build/mvn\|varka_matrix.sh\|varka_scoped_suites" .github/workflows/*.yml \| xargs grep -c "self-hosted\|^ *container:"`; `grep -h "os:" .github/workflows/build_maven*.yml \| sort -u` | `0` for each but `build_and_test.yml`, whose four `container:` lines are in jobs of Spark's that run no Varka suite; `os: macos-26`, `os: ubuntu-24.04-arm` |

**What I could not check.**

- No JVM ran. The Scala and Java edits of parts A2, B, C, D, E and F are written from the
  sources; each part's commands are their check.
- `python3 dev/varka_msg_check.py --selftest` needs `.github/PULL_REQUEST_TEMPLATE`, which the
  scratch repository lacks; no part edits that tool or what its tests read.
- `ruff` is not installed on the planning machine, so the scripts' formatting was checked by
  eye and by the 100-column scan only. Each part runs `dev/varka_precommit.sh`, which runs
  `ruff check` and `ruff format --check`; a finding there is fixed as `ruff format` writes it,
  which changes no behaviour.
- Whether `python3` is on the path of the sbt-forked test JVM on each runner image: fact 31
  says which images, part A2's premises check each workflow, and its suite is the proof.

## 4. The design

### 4.1 The decision, file by file

"Of 51" is how many of the 51 row-adding commits of fact 3 change the file. "Conflicts" is
fact 4's pairs (in conflict of shared) and fact 2's replayed merges (of 194 that conflict
among 440).

| file | written by | read by | of 51 | conflicts | decision | part |
| :--- | :--- | :--- | ---: | :--- | :--- | :--- |
| the coverage table, `VarkaCoverageSuite.scala` | hand | the suite alone | 51 | 5 of 50; 2 | the table stays, as the home of the rows it holds and of a row that needs Scala; a new row is a **row file** (4.6) | E |
| `sql/varka/coverage.json` | `VarkaCoverageSuite`, `VARKA_COVERAGE_REGEN=true` | 21 places of the test sources, `dev/varka_census.py`, `dev/varka_generated_merge.py --check`, `dev/varka_new_lane.py`, `dev/varka_push_check.py`, the demo `trap_sums.sh` | 44 | 7 of 37; 6 | **one record a row** plus a head; the file is a view (4.2, 4.3) | B |
| `sql/varka/emitted_bytes.json` | `VarkaEmittedBytesSuite`, `VARKA_BYTES_REGEN=true` | the suite, `dev/varka_bytes_moved.py`, the driver's `--check`, the Build's verdict step | 41 | 29 of 31; 54 | **one record a row**, with the row's arm digests, plus a head that holds the fuzz sections; a view (4.2, 4.4) | B, C |
| `sql/varka/width_audit.json` | `VarkaWidthAuditSuite`, `VARKA_AUDIT_REGEN=true`, on its host | the suite, `VarkaCoverageSuite`, `VarkaFamilySwitchSuite`, the driver's `--check`, the width-audit workflow | 43 | 7 of 34; 8 | **one record a shape** plus a head; a view | B |
| `docs/sql-varka.md`: the coverage table and the verification counts | `VarkaCoverageSuite` | readers of the page; `dev/varka_quote_check.py` | 48 | 26 of 44; 36 | stay in the page; **rendered by `dev/varka_rows.py --docs` in the render commit**; no row-adding branch writes them (4.5) | D |
| `docs/sql-varka.md`: the family switches | `VarkaCoverageSuite` | readers of the page | 0 | none | stays as it is: it moves only with `VarkaFamily` | - |
| `docs/sql-varka.md`: the support table | `dev/varka_census.py` | readers of the page | every row: its last column counts the coverage rows a type reads | 3 of 26 of the page's; 10 of 36 | stays in the page; **rendered by `dev/varka_census.py --docs` in the render commit** (4.5): its count merges with no conflict and one short, which would leave master red | D |
| `m8/results/VARKA-612-refused.txt` | `VarkaCoverageSuite` | `m8/VARKA-612.md`, `m8/VARKA-337.md` | every value row | 4 of 4; 2 | stays one file; its header loses the count over every value row, so only a refused row changes it (4.5) | D |
| `sql/varka/matrix/skips.tsv` | hand | fact 17's four readers | 20 | 3 of 9; 3 | stays; a new skip is **a file under `skips.d/`** (4.8) | F |
| `m9/census_curated.txt` (row 654 makes it) | hand | `dev/varka_census.py` alone | 29 | 1 of 14; 2 | **stays as row 654 leaves it; the new row's** (4.9) | - |
| `m9/census.tsv`, `m9/casts.tsv` | `dev/varka_census.py` | the readers behind row 654's `VarkaCensusFiles.of`, `dev/varka_new_lane.py` | 30, 9 | 1 of 16; 5 for the first, 0 of 3 and 0 for the second | **stay; the new row's** (4.9) | - |
| `m9/casts_fused.tsv`, `m9/casts_suites.tsv` | `VarkaCastMatrixSuite`; Spark's three cast suites under Varka | `dev/varka_census.py`, `VarkaCastMatrixSuite`, `VarkaSuiteRecord` | 9 | 0 of 3; 0 | **stay; the new row's** (4.9) | - |
| `VarkaAnsiForm.DECLINED` | hand, sql/core tests | `VarkaCoverageHarnessSuite` | 19 | 4 of 7 | stays for the rows it holds; a row file states its own positions (4.6) | E |
| `VarkaExpressionCompilerSuite.scala` | hand | sbt | 28 | 5 of 15; 4 | stays: its content is tests, not a list; where a task's compile tests go is row 658's to say (4.7) | - |
| `sql/varka/types.tsv`, `sql/varka/fuzz/types.tsv`, `VarkaTypes`, `VarkaFuzzTypes`, `VarkaLaneArrow`, the coverage `columns`, the fixtures of `VarkaDomains` | hand and `VarkaTypesSuite`, `VarkaFuzzTypesSuite` | the registries of `ADDING_A_TYPE.md` | a type row only | 0 of 6 for `VarkaTypes.java` | stay: a type is added by one full-strength row at a time (`m9/PLAN.md` 4), never by the fan-out | - |
| `VarkaProofFiles.java`, `sql/varka/proofs/README.md`, `VarkaFamily.java`, `VarkaTypeConformance.java` | hand | their suites | 20, 12, 15, 10 | 2 of 8 for the first | stay: a row adds to them only with a new bounded lowering, a new family or a new cast test, which a fan-out task plan does not hold (`m9/PLAN.md` 2.7: "no new IR node the plan does not name") | - |
| `sql/varka/golden_corpus.json`, `sql/varka/operator_census.json`, `sql/varka/emit_cost_audit.json`, `sql/varka/spark_suites/*` | their suites, each under its own `*_REGEN` | their suites | none of the top 60 | none | stay: no row-adding commit of fact 3 regenerates them as a rule | - |
| `sql/varka/ci/step-budget.tsv`, `ci/suite-times.tsv`, `matrix/*seconds.tsv`, `matrix/ledger.tsv`, `ci/sweeps.tsv` | CI's timing tools; `sweeps.tsv` by hand | CI's splitters | `sweeps.tsv` 5, the rest 0 | none | stay: a task does not refresh timings; a new sweep is a full-strength row's | - |
| `m8/PLAN.md`, `m9/PLAN.md` | hand | the plan tools | 48 | 14 of 44; 82 and 7 | milestone 8's row 646, not this row | - |

So three kinds of file change: the three generated JSON files become records and views; three
blocks of the docs page are rendered in the render commit; and two hand lists, the table and
the skips, keep what they hold and take new entries as files. The census's five files do not
change (4.9).

### 4.2 The layout under `sql/varka/rows`

    sql/varka/rows/_coverage.json            head: generated_by, description, columns, families
    sql/varka/rows/_bytes.json               head: generated_by, description, coverage_rows_skipped,
                                             the fuzz sections of each lane count, option_arms
    sql/varka/rows/_audit.json               head: generated_by, description, host, the widths' names
    sql/varka/rows/_admitted/<Class>.json    one file a Catalyst class the compiler admits, "{ }"
    sql/varka/rows/_construction/<slug>.audit.json   an audit shape that is no coverage row
    sql/varka/rows/<family>/<slug>.json          a row's coverage record
    sql/varka/rows/<family>/<slug>.bytes.json    a row's method digests, and its arm digests (part C)
    sql/varka/rows/<family>/<slug>.audit.json    a row's width-audit lines
    sql/varka/rows/<family>/<slug>.row           hand-written: a row added as a file (part E)

- `<family>` is `family_slug(title)`, `<slug>` is `slug(sql)` (2.2). The name depends on the
  row's own text alone, so a regeneration that adds a row writes that row's files and no other.
- **The names' edges.** A family title with no letter or digit has an empty directory name:
  the split stops with `rows: the family title '<title>' has no letter or digit`, and the
  title is changed. Two titles with one directory name share the directory and nothing else:
  a record holds its family's title, and the views group by it. Two rows cannot share a file:
  the name ends in eight hex digits of the SQL's hash, and a row's SQL is unique in the table
  (fact 7). Two branches that each admit the same new class both add the same
  `_admitted/<Class>.json` with the same two bytes, which merges. `_bytes.json` holds
  `coverage_rows_skipped`, a list that is always empty (`VarkaEmittedBytesSuite.document`:
  "Always empty: a row that does not resolve or fuse fails `coverageOf`"), so no row adds to
  it.
- Every `.json` file is written by Jackson's pretty printer, or by `render_json`, which is the
  same bytes. A coverage record is the row's object of `coverage.json` with one more key last,
  `"after"`: the SQL of the row before it in its family in the Scala table, `""` for the
  family's first. A row added as a file has no `"after"`.
- A bytes record is `{"sql": <sql>, "lanes": {"4": {...}, "16": {...}}}`, each lane's object
  the row's methods and hashes, and from part C on `"arms": {<arm>: {<lanes>: <digest>}}`.
- An audit record is `{"shape": <key>, "widths": {"128": [...], "256": [...], "512": [...]}}`.
- **Order.** A view's rows are: the families that `_coverage.json` lists, in that order, then
  any other family by title; in a family, the rows of the Scala table by following `"after"`
  from `""`, then the row files by slug. Nothing holds the order in one shared place. Two
  branches that insert Scala rows at two places of a family change two different records'
  `"after"` and merge right; two that insert at one place both change the same record's
  `"after"` and conflict, as they do in the Scala source. A stored index would merge two such
  insertions with no conflict and a wrong order, which is why the link is used.
- The bytes view's `lanes.<n>.coverage` follows the coverage view's order; the audit view's
  maps are sorted by key. All keys are ASCII (fact 7), so Python's and Java's orders agree.
- `sql/varka/.gitattributes` marks `/rows/**/*.json` `linguist-generated`, so the size check
  does not count the records and GitHub folds them. `sql/varka/.gitignore` ignores the three
  views.

### 4.3 The views, and who makes them

`python3 dev/varka_rows.py --views` assembles each view from the records and writes it where
it differs, through a rename. It reads about 2,700 small files; its cost is prediction 3 of
section 7. Every reader gets the view first:

| reader | how the view is there |
| :--- | :--- |
| a test JVM | every read site calls `VarkaRowFiles.view("<file>")`, which runs `--views` once a JVM and returns the path the site used before |
| a suite that regenerates | calls `VarkaRowFiles.write(<kind>, <text>)` where it wrote the file: the tool splits the text into records and writes the views again |
| a Python tool | calls `varka_rows.views(ROOT)` at the start of its `main` |
| a shell, a grep of another plan | the git hooks `post-checkout`, `post-merge` and `post-rewrite`, which `dev/varka_precommit.sh --install-hook` installs, run `--views`; so does `dev/varka_task_new.sh` in a new worktree, and the docs job as its first Varka step. After a `git reset --hard`, which runs no hook, and in a clone whose hooks were never installed, run it by hand: until then a grep of a view finds no file, which is an error and not an empty answer |

A suite's `write` hands the tool the rendered text, not the view file: another test JVM of the
same worktree may write the views between the suite's write and the split, and a split that
read the file would then split the stale one and lose the regeneration.

**What "a stale generated file fails CI with its command" means once the files are not
committed.** Nothing in git can be stale but a record. Which check sees which staleness:

| what is wrong | what sees it | with which words |
| :--- | :--- | :--- |
| a row has no record of a kind, or a record is of no row, misnamed or not in the tool's form | `python3 dev/varka_rows.py --check`, in the docs job of every Build, on any CPU | `rows: <path> ... ; run dev/varka_regen_generated.sh --all` |
| a coverage or a bytes record whose content is not what the compiler gives (a digest after an emitter change, a note after an edit of the table) | `VarkaCoverageSuite`, `VarkaEmittedBytesSuite` in the Build's catalyst job: each renders its file and compares it with the view | the suite's message of today, which names the view's path (`sql/varka/emitted_bytes.json does not match what the emitter produces ... regenerate with VARKA_BYTES_REGEN=true build/sbt ...`) and, for the bytes, the row and the method. The command it prints is still the right one: it writes the records |
| an audit record whose content is stale | `VarkaWidthAuditSuite` on the laptop's CPU alone, as today; on every other CPU only the row's presence is checked, by `--check` | the suite's message of today |
| a docs block behind the records (from part D) | nothing fails: `--check` and `dev/varka_census.py --check` print a notice, as a GitHub warning in CI, and the render commit refreshes it (4.5) | `rows: docs/sql-varka.md's <block> is behind the records` |
| a view on disk older than the records | nothing needs to: the test JVM and the Python tools write the views before they read them | - |

`dev/varka_push_check.py`'s rule R3 ("every generated file the branch changes as the
regeneration wrote it") no longer covers the three files: it works from the driver's list,
which loses them. A record edited by hand or left stale passes the push check and fails the
Build, by the first two lines of the table. That is the one guard this row gives up.

### 4.4 One digest a row for each option arm (part C)

Today `option_arms.<arm>.<lanes>` is one digest over every coverage row and 30,000 fuzz shapes
(fact 10). Part C splits it in two:

    "option_arms"     : { "<arm>" : { "<lanes>" : "<digest of the three fuzz sequences>" } }
    "option_arm_rows" : { "<arm>" : { "<lanes>" : { "<sql>" : "<digest of the row's line>" } } }

A row's line is what `armDigest` builds for it today: the SQL, then each method and its hash.
A row that is not pinned at a width (`pinsAt` is false) has no entry there. The split writes
`option_arm_rows.<arm>.<lanes>.<sql>` into the row's bytes record as `arms.<arm>.<lanes>`, and
the head keeps, under `option_arm_rows`, each arm's list of widths, so that the view comes back
with the same maps in the same order. No emission is added: the row lines are the ones
`armDigest` hashes together today. What this gives row 547 of the catalogue
(`m10/VARKA-547.md`): its done-when's two clauses, in another form than its plan's (one
digest a row, arm and width, with no test of "differs from the defaults"); that row's status
is its own row's to change, not this part's.

### 4.5 Three blocks of the docs page, rendered in the render commit (part D)

The coverage table and the verification counts of `docs/sql-varka.md` are functions of the
coverage view and the audit view (fact 14). The support table is a function of the census,
the cast probe and the coverage view; its last column, *Coverage rows*, counts the rows that
read a type. All three hold a value that every row-adding branch would write, and the counts
merge with no conflict and one short (the demonstration's "merged with no conflict and
stale"). From part D on:

- `python3 dev/varka_rows.py --docs` writes the coverage table and the verification counts;
  `--docs --check` exits 1 and names the block when one differs. `python3 dev/varka_census.py
  --docs` writes the support table. None needs a JVM.
- `VarkaCoverageSuite` renders and checks the family switches alone. Its `render`,
  `verificationCounts`, `narrowVerdict`, `constructionOf` and `widthAudit` go.
- `python3 dev/varka_census.py` with no argument writes `census.tsv` and `casts.tsv` and no
  longer the page; its `--check` holds those two files and prints a notice for the page.
- No row-adding branch writes the three blocks. Between two render commits the page's tables
  are behind master by the rows merged since; the records are never behind.
- **Who makes the render commit, and when.** The lead, with `dev/varka_ops/render-docs.sh`,
  which runs the two `--docs` commands and says what to commit: after each merge of a pull
  request whose run on master shows the notice, in the lead's routine after a merge
  (`dev/varka_ops/AGENTS.md` gains the rule), and at a milestone's close. Both checks print
  the notice as a GitHub warning on the run's page when they run in CI: `::warning
  file=docs/sql-varka.md::rows: ... is behind the records` from `dev/varka_rows.py --check`
  and `::warning file=docs/sql-varka.md::census: ... is behind the census` from
  `dev/varka_census.py --check`; outside CI the same lines with no `::warning` prefix. So
  master's run shows that the page is behind and by which block. A cheaper model never makes the
  render commit: its task plan does not list the page.
- The two-lane census's header line `# <n> of <m> value rows refused; family, row, reason:`
  becomes `# family, row, reason:`. The count `<m>` moved with every value row; the lines
  below it, one a refused row, stay.

Why not the other two ways: the tables cannot leave the page for a file of their own, since
two siblings would conflict there too; and they cannot be refreshed by CI after a merge,
since the fork has no token (`m10/VARKA-546.md` 2.4).

The support table is in this row although the census's files are not (the owner's decision
of 27 November 2026, section 13): without it this row would open a new way to turn master
red. Today a second row-adding branch cannot merge without a merge of master, because
`emitted_bytes.json` conflicts; after part C it can, and two branches that each read an `int`
column would then both write the same *Coverage rows* count, merge with no conflict, and
leave `dev/varka_census.py --check` failing on master. The line's *Entries*, *Fused* and *In
part* cells come from the census and go stale the same way where two branches each fuse an
entry over one type. Part D's edit of `dev/varka_census.py` is five small edits (5.5, step
6). Two of them sit beside edits of row 654's: the new `--docs` argument goes after the
`--self-test` argument, where `VARKA-654.md` 4.2 puts `--milestone`, and the docstring's
usage lines are the ones above the `--summary` line that row 654 extends. Row 654 is built
first (part B's premises 3 and 3a), and each old text of step 6 is in the file once with row
654's edits and once without them.

### 4.6 A coverage row as a file (part E)

A row file is `sql/varka/rows/<family_slug(family)>/<slug(sql)>.row`: printable ASCII and
newlines only (no tab, no carriage return, no other control character: the Java and the
Python parser split lines differently on those, so both refuse them), one `key: value` a line
(the key, a colon, one space, the value to the end of the line), `#` starts a comment line,
empty lines are skipped. Keys, each at most once but for the last:

| key | required | meaning |
| :--- | :--- | :--- |
| `sql` | yes | the row's SQL over the table's columns; it is the row's identity |
| `family` | yes | the family's title. A title of the Scala table adds the row to that family, after its rows; any other title makes a family of row files |
| `form` | yes | `projection` or `predicate`; it must be the family's |
| `note` | no | the Notes cell; empty by default |
| `executable` | no | the text a session can run, where `sql` is not one; `sql` by default |
| `domain` | no | the predicate inside which the kernel answers, as SQL; none by default. The suite's test "every row whose kernel can decline a batch names its domain, and no other row does" holds it |
| `verified` | no | how the row is verified, in the words `coverage.json` prints: `differential`, `proof: <file>`, `refinement: <name>; <name>`, `exhaustive: <suite>, <test>`, `rendered` or `owed: row <n>`. By default the family's entry of `familyVerified`, else `differential` |
| `ansi-declined` | no, may repeat | `<position> <reason>`: a position where `VarkaAnsiForm.DECLINED` expects the row to decline, `case-arm`, `coalesce-tail` or `false-and`, and its reason, `column-bound`, `unqualified` or `narrow-choice` |

A row that needs a Catalyst expression built in code (`built`) or a math form (`math`) cannot
be a file: it goes in the Scala table, as today. A value that today is interpolated from a
constant (`s"i > ${Int.MinValue}"`) is written out in a file (`i > -2147483648`); the domain
test and the differential suites hold it.

`VarkaRowFiles.rows()` parses the files, in Java, once a JVM, sorted by family directory and
file name. `VarkaCoverageSuite` adds them to its families; `VarkaAnsiForm.declined` reads their
`ansi-declined` lines. `dev/varka_rows.py --check` holds each file's name to its `sql` and
`family`, and a row file with no record fails it with the regeneration command.

### 4.7 What this row does not rule: a task's compile tests

`VarkaExpressionCompilerSuite.scala` is 4,309 lines that 28 of 51 row-adding commits edit, and
two of its import lines list classes several to a line. No list can be split out of it: its
content is tests. Where a task puts its compile tests (its family's suite or a suite of its
own) is a rule of the task template, row 658's. This row adds no check on that file and no
limit on any file's length: a limit at a file's line count would fail the check for every
branch the day another pull request lengthened the file.

### 4.8 A matrix skip as a file (part F)

`sql/varka/matrix/skips.d/varka-<row>-<slug>.tsv` holds skip lines in the form of
`skips.tsv`: five tab-separated fields, `#` comment lines allowed. `<row>` is the task's
number and `<slug>` is `slug` (2.2) of the test name of the file's first skip line, so two
tasks, or two siblings of one row, never pick one name unless they skip the same test first,
which is a real conflict. `dev/varka_rows.py --check` holds the name. The list is `skips.tsv`
followed by the files of `skips.d` in name order. The four readers of fact 17 read both;
`merge=union` leaves `sql/varka/.gitattributes`, since GitHub never ran it. `skips.tsv` keeps
its lines and still takes an edit or a removal of one; a new line goes in a file.

### 4.9 What still conflicts after this row, and the row that removes it

The census's five files stay single files, as row 654 leaves them. A task that fuses an
entry over a type edits that entry's line of the curated file (its fused types, its rows, its
note), and the regeneration rewrites that entry's line of `census.tsv`; a task that fuses a
cast also moves lines of `casts_fused.tsv`, `casts_suites.tsv` and `casts.tsv`.

| file | when two branches conflict in it | measured: fact 4's pairs; fact 2's replayed merges | in the demonstration's round 2 after part F |
| :--- | :--- | :--- | :--- |
| `census_curated.txt` | both edit one entry's line, or the lines of two entries that are neighbours in the file | 1 of 14 pairs that both changed it; 2 of 194 | three siblings whose entries are neighbours: in conflict. Two entries far apart: none |
| `census.tsv` | the same, since it has one line an entry in the curated file's order | 1 of 16; 5 of 194 | the same |
| `casts_fused.tsv`, `casts.tsv`, `casts_suites.tsv` | both fuse casts whose pairs are on neighbouring lines: three casts from one source type are | 0 of 3 pairs; 0 of 194 | not modelled: no branch of the demonstration is a cast |

So after this row a conflict is left where two open branches claim neighbouring census
entries, and for sibling casts in the cast tables too. It is a conflict that GitHub shows,
never a silent stale file: every line of those files belongs to one entry, and no value in
them is computed from every row. The owner split the row on 27 November 2026; the new row
(11.1, edit 2) makes a claim a file and the census's rendered files views, and
appendix A holds the design notes for its plan. Until it lands, sibling tasks of one family
merge one after the other where they claim neighbouring entries, each with a merge of master
that resolves the two census files by taking both lines and running `python3
dev/varka_census.py`, which takes about a second and no JVM.

### 4.10 The demonstration

`dev/varka_rows_demo.py` is the done-when's test. It copies the list files into a scratch
repository that has no attributes file and reads no configuration. On one base commit it
builds five branches with `git commit-tree`, each adding one row the way a task would: three
siblings after one row of the family "Integral casts" and two rows in two other families. It
merges the three siblings into the base in each of their six orders and the two others in
both, with `git merge-tree --write-tree`, and prints the files in conflict. Where every order
merges it requires three more things: the merged trees of all orders are one tree; that tree
is the tree one branch adding all the rows would give (a count that two branches each raise
by one merges with no conflict and is one short, which is a regeneration owed); and
`dev/varka_rows.py --check` passes on it.

**It uses the parts' own code.** Once the records exist, a branch's row is added to the three
views as the suites would render it, and the views are handed to `dev/varka_rows.py --split
<kind> --from <file>`, the command `VarkaRowFiles.write` runs after a regeneration. What the
branch commits is what that command wrote, with the `.row` file. Only the values are a
model: a new row's digests and audit lines are copies of an existing row's, since no kernel
is compiled.

**Its rows cannot become real rows.** A new row's SQL is an existing row's followed by
` -- demo row <n>`. No row of the table holds a SQL comment, so no later task can add a row of
that text, and the script stays green in CI whatever rows land. The models are chosen from
the tree at run time (the last row of "Integral casts" that the suite writes as a literal
`Row("...")`, and the first such rows of the next two families), so a removed row does not
break it either.

**It reads the layout from the tree**, so no part of this row edits it after A1: records
under `sql/varka/rows`; the tool's `docs_blocks` (part D), `row_file` (part E) and
`skip_file_errors` (part F). Row 696 edits it: a cast among round 2's branches, and round 2
deciding the exit status.

**What it proves with the parts' own code, and what is a model.**

| what | the parts' own code | a model |
| :--- | :--- | :--- |
| a row's record files | written by `dev/varka_rows.py --split`, the command the test JVM's door runs, from the three views with the row added | - |
| a record's values | - | a new row's coverage fields, digests and audit lines are copies of its model row's; no kernel is compiled, and no suite renders the views |
| a row's arm digests (from part C) | the tool's split of `option_arm_rows` | the values are the model row's; and the planner's run of this stage used a bytes view with an `option_arm_rows` section made by hand, since no JVM ran: part C's builder regenerates it, and the stage's output in part C's results file is then the first real one |
| the `.row` file (from part E) | its name is the tool's `family_slug` and `slug`, held by the tool's `--check` on the merged tree | it is never parsed by Java here: `VarkaRowFilesSuite` and part E's command 3 hold the parser |
| that a regenerated row's files are what `--split` writes | part E's command 3 shows the four new files of a real row; part B's command 7 shows that a regeneration with nothing to change writes nothing | the script does not compare a JVM's regeneration with its own files |
| the docs' blocks, the skip, the suite's source line | the skip file's name is the tool's rule, held by `--check` | the edits of the docs page, of `skips.tsv` and of the Scala source are line edits by the script, in the form a task's are |
| the census's edits (round 2) | - | a task's number added to its entry's line of the curated file and of `census.tsv`, and the support table's count; no branch is a cast, so the cast tables are not in it |

So the script proves the merge behaviour of the layout and of the tool that writes it, and
the sheets' JVM commands prove that the suites write through that tool.

**Two rounds.** Round 1 holds what belongs to a coverage row and decides the exit status.
Round 2 adds what the census needs of a task (its entry's line of the curated file and of
`census.tsv`, and, until part D, the support table's count) and is printed alone: it is the
new row's test.

| after part | layout | round 1: files in conflict or stale | round 2 adds |
| :--- | :--- | ---: | :--- |
| A1 | every list in one file | 6: the docs page, the table's source, the three JSON files, the skips | the curated census and the census: 8 |
| B | records | 4: the docs page, the table's source, the skips, `rows/_bytes.json` | the same two: 6 |
| C | records, with `option_arm_rows` | 3: the docs page, the table's source, the skips | 5 |
| D | records, docs | 2: the table's source, the skips | 4 |
| E | records, docs, row_files | 1: the skips | 3 |
| F | records, docs, row_files, skips | 0: the script exits 0 | 2: the curated census and the census, for the three siblings; nothing for the two families |

These outputs are from the script's runs on a scratch copy of the plan's commit, with the
tool as each part leaves it and the records made by `--split`; for the row C the bytes view
was given an `option_arm_rows` section by hand, since no JVM ran.

### 4.11 What is out of this row

| excluded | the row that holds it |
| :--- | :--- |
| a census claim as a file; `census.tsv` and `casts.tsv` no longer committed; the two cast tables as records | row 696 (11.1, edit 2); appendix A |
| the task table in one file a row | milestone 8's row 646 |
| an emit option declared in one place | 642 |
| the task template, its oracle and `ADDING_AN_EXPRESSION.md` brought to today's tree; where a task's compile tests go | 658 |
| a family compiler's source, where two siblings add arms to one `switch` | 658: the template names the arm's place, or the first task of a family lays out every sibling's arm |
| a pull request's Build on the merge with master, which catches a row whose bytes another merge moved | 590 |
| the per-row lists that only a row with new IR or a new bounded lowering appends (`VarkaProofFiles`, `proofs/README.md`, `VarkaDomains`' fixtures, `VarkaFamily`, `VarkaTypeConformance`) | none: they stay as they are (4.1), decided on 27 November 2026 (section 13) |
| moving the 868 rows of the Scala table into files | none: no done-when asks for it, and 20 rows cannot be data (fact 19) |
| a limit on any file's length | none (4.7) |
| row 547's status | its own row |
| retiring the merge driver | none: it stays for `types.tsv`, `fuzz/types.tsv` and the census's files; its list loses the three JSON files |

## 5. The parts

Seven pull requests of code; the last also holds the results and closes the row.

| part | title | branch | counted lines, estimate |
| :--- | :--- | :--- | ---: |
| A1 | `[VARKA-653] Show which shared files two row-adding branches conflict in` | `varka-653a1-merge-demo` | 540 |
| A2 | `[VARKA-653] Split a coverage row's generated records out and assemble them back` | `varka-653a2-rows-tool` | 715 |
| B | `[VARKA-653] Commit each coverage row's generated records as files of its own` | `varka-653b-records` | 300 |
| C | `[VARKA-653] Pin each option arm's bytes one coverage row at a time` | `varka-653c-arm-rows` | 150 |
| D | `[VARKA-653] Render the docs' row tables in a commit of their own` | `varka-653d-docs-blocks` | 490 |
| E | `[VARKA-653] Add a coverage row as a file` | `varka-653e-row-files` | 480 |
| F | `[VARKA-653] Add a matrix skip as a file` | `varka-653f-skip-files` | 300 |

The estimates are file by file. A1: the script 515, `modules.py` 6, this file 15. A2: the tool
526, `VarkaRowFiles` 95, its suite 40, the two workflows 6, `modules.py` 2, this file 15, a
margin of 30; if the
size check prints over 900, the suite moves to part B and A2 says so. B: 28 read sites as 56
changed lines, 6 imports, 3 write sites as 6, the Python tools and shell scripts 80, the
workflow and `modules.py` 35, the attributes and the ignore file 12, the documents 50, the
eight lines of the four merged plans 18, this file 25. C: Scala 110, the documents 20, this
file 20. D: Python 165 added, the census tool 35, Scala 130 removed and 20 changed, the ops
script and its index 45, the documents 60, this file 25. E: Java 200, Scala 90, the tool 50,
the suite's tests 60, the documents 50, this file 20. F: Java 30, Scala 20, Python 110, shell
4, the workflow 4, the documents 50, the results and this file 60. The records, the views and
the results files count in none: they are generated.

| part | needs | gives |
| :--- | :--- | :--- |
| A1 | this plan merged; the tag `varka-m8` | the demonstration, and its output on master as it is. Master is green; no list moves. Every later part runs it |
| A2 | A1 merged | the tool and the test JVM's door, with nothing reading through them yet. Master is green; no list moves |
| B | A2; row 654's parts a and b; row 656's part B | the three JSON files as records and views. Demonstration, round 1: 4 |
| C | B; merged only with D behind it (step 10) | one digest a row and arm. Round 1: 3. From here two row-adding branches of two families merge on GitHub in the generated files |
| D | C, built on C's branch and merged right after it | the docs' three blocks rendered apart. Round 1: 2 |
| E | D | row files. Round 1: 1. Row 658 can write its template from here |
| F | E | skip files. Round 1: 0. The results, the demonstration in CI, the row **Done** |

A1 and A2 can be built while rows 654 and 656 are built: they move no list and touch no line
that those rows' premises read (section 12). From B on the parts are built one after the
other, each from master with the part before it merged, and no two side by side: every one of
them edits `dev/varka_rows.py`, `sql/varka/AGENTS.md` and this file. Each part leaves master
green and useful alone, with one exception that a step holds: after any of B to F the lists it
moved no longer conflict, whatever the later parts do, but between parts C and D two
row-adding branches can merge with no conflict and leave a docs count stale, which fails
`VarkaCoverageSuite` on master. So parts C and D merge together (part C's step 10): part D is
built on part C's branch, both Builds are green, and the lead merges C and then D with no
row-adding pull request between.

### 5.0 What every sheet shares

- Start from master, in a worktree of the part's branch: `dev/varka_task_new.sh` is not used,
  since the plan exists; `git worktree add -b <branch> ../<branch> origin/master`, then
  `dev/varka_precommit.sh --install-hook`.
- Names, set once a shell:

        M9=sql/varka/plans/m9
        G=org/apache/spark/sql/catalyst/expressions/codegen/varka
        TS=sql/catalyst/src/test/scala/$G
        TJ=sql/catalyst/src/test/java/$G
        CS=sql/core/src/test/scala/org/apache/spark/sql/execution
        CJ=sql/core/src/test/java/org/apache/spark/sql/execution
        S=$TS/VarkaCoverageSuite.scala; B=$TS/VarkaEmittedBytesSuite.scala; W=$TS/VarkaWidthAuditSuite.scala
        LOCK="${VARKA_HEAVY_LOCK:-$HOME/proj/.varka-locks/heavy.lock}"
        X=target/varka-653; mkdir -p $X

  `$X` is a scratch folder under `target/`, which git ignores.
- Every local JVM run pins JDK 25 and binds the local address:
  `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1`. Wrap every
  `build/sbt`, `dev/lint-java`, `dev/lint-scala` and `dev/varka_errorprone.sh` call in `flock
  "$LOCK"`. Call `dev/varka_regen_generated.sh` bare: it takes the lock itself.
- Run B0 before any edit. Each line's comment is what it must print; a line that prints
  otherwise is a stop: report the command and its output and edit nothing, but where the
  sheet's table under B0 gives another action.
- Where a sheet gives lines of code, type them as given; each is within 100 columns at the
  indentation shown. Where it gives an old line and a new one, the old line is in the file
  once unless the sheet says how many times.
- A ScalaTest run passes when sbt exits 0 and prints `Tests: succeeded N, failed 0`; a JUnit
  run when sbt prints `Passed: Total N, Failed 0, Errors 0, Passed N`.
- Before the commit: `dev/varka_precommit.sh`; for Java `flock "$LOCK" dev/varka_errorprone.sh
  --master "$(git rev-parse origin/master)"` and `flock "$LOCK" dev/lint-java`; for Scala
  `flock "$LOCK" dev/lint-scala`. Where scalac or Error Prone reports an import or a variable
  that an edit of the sheet left unused, delete it; that is the one edit a sheet does not list.
  Where `ruff format --check` reports a Python line, run `ruff format` on the file.
- The size: `python3 dev/varka_msg_check.py size` prints the counted lines. Over the
  estimate by half, or over 900, is a stop: report the count and the three largest files.
- Before the pull request: `/code-review high` on the diff, its findings fixed and named in the
  description; `python3 dev/varka_msg_check.py pr --title "<title>" --body-file <file>`.
- Every part runs `python3 dev/varka_rows_demo.py > $X/demo.txt; echo "exit $?"` and puts the
  file's text into `$M9/results/VARKA-653-demo.txt`, under one first line, `# python3
  dev/varka_rows_demo.py on <the commit's short hash>, <the date>`. No part of this row edits
  the script after A1: it reads the layout from the tree. (Row 696 will, for a cast branch.)
- Each part's last step edits this file: a dated paragraph in section 14 with what was built,
  the commands' outputs and each prediction of section 7 scored; and in `$M9/PLAN.md` row 653's
  status, `**Planned**` to `**Started**` in part A1 and to `**Done**` in part F, each with the
  part's pull request named.
- **What runs only in CI.** The Build's catalyst and sql jobs, the docs job's checks, the
  option matrix (dispatched by the lead on each of parts B, C, D, E and F, which change what
  suites read), and the Maven build. The width audit's own check runs only on the laptop's
  CPU, so part B's command 6 runs there. What the builder runs first is each sheet's commands.
- Stop also on: a test that should pass and fails; a file in `git status --short` that the
  sheet does not name; anything the sheet does not decide. A stop reports and waits.

### 5.1 Part A1: the demonstration

**B0, the premises.**

    test -f $M9/VARKA-653.md && echo merged                               # 1: merged
    git remote | while read r; do git fetch -q "$r" tag varka-m8 2>/dev/null; done
    git tag -l varka-m8                                                   # 2: varka-m8
    git branch -a --list '*varka-653a1*'                                  # 3: nothing
    ls dev/varka_rows_demo.py dev/varka_rows.py 2>&1 | grep -c "No such file"   # 4: 2
    python3 -c "import sys; sys.path.insert(0, 'dev'); from varka_generated_merge import render_json; print(render_json({'a': []}))"
                                                                          # 5: three lines: {   "a" : [ ]   }
    ls docs/sql-varka.md $S sql/varka/matrix/skips.tsv sql/varka/plans/m8/results/VARKA-612-refused.txt sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json | wc -l   # 6: 7
    grep -c -F 'varka_docs_check_regexes = (r"sql/varka/(width_audit|emitted_bytes)\.json$",)' dev/sparktestsupport/modules.py   # 7: 1
    grep -c '^\[registered\]$' sql/varka/plans/m*/census_curated.txt      # 8: 1 for each file that exists: m8's, and m9's once row 654's part a merged

| line | it prints | what the builder does |
| :--- | :--- | :--- |
| 2 | nothing | stop: "no remote has the tag `varka-m8`; milestone 8 is not closed" |
| 7 | `0` | stop: report the tuple's lines; a part of another row changed them |

The script chooses its model rows and census entries from the tree at run time, so no premise
names a row.

**The steps.**

1. Add `dev/varka_rows_demo.py`, mode 755, with exactly this text:

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
        """Do branches that each add a coverage row merge with nothing to resolve? (VARKA-653.)

          dev/varka_rows_demo.py            # print each merge's conflicts; exit 1 if round 1 has any
          dev/varka_rows_demo.py --keep DIR # the same, leaving the scratch repository in DIR

        The script copies the list files a row-adding task touches into a scratch repository with no
        attributes file, so that git merges them as GitHub does, with no merge driver. On its one
        commit it makes five branches, each adding one row as a task would: three siblings in one
        family at one place and two rows in two other families. It merges the three siblings into the
        base in each of their six orders and the other two in both orders, with `git merge-tree
        --write-tree`, and prints the files in conflict. Where every order merges it also requires the
        merged trees of all orders to be equal, and to be the tree that the same rows give when one
        branch adds them all: a count over every row that two branches both raise by one merges with
        no conflict and is then one short, which is a regeneration owed. Once the records exist,
        `dev/varka_rows.py --check` must pass on the merged tree too.

        It runs two rounds. In round 1 a branch writes what belongs to its coverage row: the row, its
        generated records, the docs' row tables, a matrix skip. Round 1 decides the exit status. In
        round 2 a branch also writes what the census needs of it: its entry's line of the curated
        census, the census's line, and, until the docs' blocks are rendered apart, the support
        table's count. Round 2 is printed and decides nothing: the census's files are another row's
        to move (VARKA-653.md 4.9).

        The script reads how a row is added from the tree it runs in, so no part of VARKA-653 edits
        it: with records under sql/varka/rows it writes a row's records with `dev/varka_rows.py
        --split`, the command the test JVM runs after a regeneration, on the views with the row
        added; with the tool's `docs_blocks` it leaves the docs' row tables alone; with its `row_file`
        it adds a `.row` file and not a line of the Scala table; with its `skip_file_errors` it adds a
        skip file. A new row's SQL is a row of the table followed by ` -- demo row <n>`: no row of the
        table holds a comment, so none can ever have that text, and its generated values are its
        model's.
        """

        import hashlib
        import itertools
        import json
        import os
        import shutil
        import subprocess
        import sys
        import tempfile

        from varka_generated_merge import render_json

        ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        ROWS = "sql/varka/rows"
        try:
            import varka_rows
        except ImportError:
            varka_rows = None
        LAYOUT = {
            "records": os.path.exists(os.path.join(ROOT, ROWS, "_coverage.json")),
            "docs": hasattr(varka_rows, "docs_blocks"),
            "row_files": hasattr(varka_rows, "row_file"),
            "skips": hasattr(varka_rows, "skip_file_errors"),
        }
        SUITE = (
            "sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/"
            "VarkaCoverageSuite.scala"
        )
        DOCS = "docs/sql-varka.md"
        SKIPS = "sql/varka/matrix/skips.tsv"
        TWO_LANE = "sql/varka/plans/m8/results/VARKA-612-refused.txt"
        VIEWS = {
            "coverage": "sql/varka/coverage.json",
            "bytes": "sql/varka/emitted_bytes.json",
            "audit": "sql/varka/width_audit.json",
        }
        KINDS = ["refinement", "rendered", "proof", "exhaustive", "owed", "differential"]
        SIBLINGS = "Integral casts"
        ROW = 659


        def census_folder(root):
            """The plans folder that holds the current census: the highest milestone with one."""
            for m in ("m9", "m8"):
                if os.path.exists(os.path.join(root, f"sql/varka/plans/{m}/census_curated.txt")):
                    return f"sql/varka/plans/{m}"
            sys.exit("demo: no census_curated.txt under sql/varka/plans/m9 or m8")


        def read(root, name):
            with open(os.path.join(root, name), encoding="utf-8") as f:
                return f.read()


        def write(root, name, text):
            os.makedirs(os.path.dirname(os.path.join(root, name)), exist_ok=True)
            with open(os.path.join(root, name), "w", encoding="utf-8") as f:
                f.write(text)


        def dump(value):
            return render_json(value) + "\n"


        def sha(text):
            return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


        def view(root, kind):
            """A view's document: the file's, or what the records under `root` assemble into."""
            if LAYOUT["records"]:
                return json.loads(varka_rows.assemble(kind, varka_rows.Disk(root)))
            return json.loads(read(root, VIEWS[kind]))


        def copy_lists(root, to):
            """Copies the files a row-adding task touches from `root` to `to`, and returns them."""
            folder = census_folder(root)
            names = [SUITE, DOCS, SKIPS, TWO_LANE, f"{folder}/census_curated.txt", f"{folder}/census.tsv"]
            if not LAYOUT["records"]:
                names += list(VIEWS.values())
            for extra in (ROWS, "sql/varka/matrix/skips.d"):
                for where, _, files in os.walk(os.path.join(root, extra)):
                    names += [os.path.relpath(os.path.join(where, f), root) for f in files]
            copied = []
            for name in names:
                if os.path.exists(os.path.join(root, name)):
                    os.makedirs(os.path.dirname(os.path.join(to, name)), exist_ok=True)
                    shutil.copyfile(os.path.join(root, name), os.path.join(to, name))
                    copied.append(name)
            return copied


        def choose(root):
            """The five branches: (name, the new row's SQL, the row it is modelled on, the census entry
            it claims). A model is a row the suite writes as a literal `Row("<sql>"` and the docs page
            lists; the three siblings share one, the last such row of the family SIBLINGS (or of the
            family with the most such rows), and the other two take the first such row of the next two
            families. The entries are lines of the curated census: three neighbours and two far ones."""
            suite, docs = read(root, SUITE), read(root, DOCS)
            by_family = {}
            for e in view(root, "coverage")["expressions"]:
                sql = e["sql"]
                if f'\n      Row("{sql}"' in suite and f"\n| `{sql}` |" in docs and "|" not in sql:
                    by_family.setdefault(e["family"], []).append(sql)
            if len(by_family) < 3:
                sys.exit("demo: fewer than three families have a row to model a new row on")
            first = SIBLINGS if SIBLINGS in by_family else max(by_family, key=lambda f: len(by_family[f]))
            others = [f for f in by_family if f != first][:2]
            models = [by_family[first][-1]] * 3 + [by_family[others[0]][0], by_family[others[1]][0]]
            curated = read(root, census_folder(root) + "/census_curated.txt")
            registered = curated.split("\n[registered]\n", 1)[1].split("\n[", 1)[0].splitlines()
            entries = [x.split("|")[0] for x in registered if x and not x.startswith("#")]
            entries = [entries[i] for i in (0, 1, 2, 10, 20)]
            names = ["sibling-1", "sibling-2", "sibling-3", "family-a", "family-b"]
            rows = {e["sql"] for e in view(root, "coverage")["expressions"]}
            out = []
            for n, (name, model, entry) in enumerate(zip(names, models, entries), 1):
                sql = f"{model} -- demo row {n}"
                if sql in rows:
                    sys.exit(f"demo: the table holds a row {sql}")
                out.append((name, sql, model, entry))
            return out


        def edit_lines(root, name, change):
            """Rewrites file `name` as `change(lines)` gives it; `change` must change something."""
            before = read(root, name).splitlines(keepends=True)
            after = change(list(before))
            if after == before:
                sys.exit(f"demo: no line of {name} matched; the file's form has changed")
            write(root, name, "".join(after))


        def add_to_views(root, sql, model, at_end):
            """The three views with the row `sql` added as the suites would render it: its entries are
            its model's, after the model, or last in the model's family where the row is a file."""
            coverage, emitted, audit = (view(root, k) for k in ("coverage", "bytes", "audit"))
            rows = coverage["expressions"]
            at = next(i for i, e in enumerate(rows) if e["sql"] == model)
            record = rows[at]
            if at_end:
                at = max(i for i, e in enumerate(rows) if e["family"] == record["family"])
            rows.insert(at + 1, {**record, "sql": sql, "executable": sql})
            order = [e["sql"] for e in rows]
            for section in emitted["lanes"].values():
                section["coverage"][sql] = section["coverage"][model]
                section["coverage"] = {k: section["coverage"][k] for k in order if k in section["coverage"]}
            if "option_arm_rows" in emitted:
                for by_lanes in emitted["option_arm_rows"].values():
                    for lanes, digests in by_lanes.items():
                        if model in digests:
                            digests[sql] = digests[model]
                            by_lanes[lanes] = {k: digests[k] for k in order if k in digests}
            else:
                # A digest over every row moves with each row added, to a value only a JVM computes.
                for arm in emitted["option_arms"].values():
                    for lanes in arm:
                        arm[lanes] = sha(arm[lanes] + sql)
            for width, shapes in audit["widths"].items():
                shapes[sql] = shapes[model]
                audit["widths"][width] = dict(sorted(shapes.items()))
            return record, {"coverage": coverage, "bytes": emitted, "audit": audit}


        def add_row_files(root, sql, model):
            """One row added to the three files that hold every row."""
            record, views = add_to_views(root, sql, model, at_end=False)
            for kind, doc in views.items():
                write(root, VIEWS[kind], dump(doc))
            return record


        def add_row_records(root, sql, model):
            """One row added as a regeneration writes it: the three views with the row are handed to
            `dev/varka_rows.py --split`, as the test JVM's door hands them, and the views it writes back
            are removed, since git ignores them."""
            record, views = add_to_views(root, sql, model, at_end=LAYOUT["row_files"])
            if LAYOUT["row_files"]:
                name = f"{ROWS}/{varka_rows.family_slug(record['family'])}/{varka_rows.slug(sql)}.row"
                lines = [f"sql: {sql}", f"family: {record['family']}", f"form: {record['form']}"]
                write(root, name, "\n".join(lines) + "\n")
            tool = os.path.join(ROOT, "dev/varka_rows.py")
            for kind in ("coverage", "bytes", "audit"):
                rendered = os.path.join(root, f".rendered-{kind}.json")
                write(root, f".rendered-{kind}.json", dump(views[kind]))
                args = [sys.executable, tool, "--root", root, "--split", kind, "--from", rendered]
                out = subprocess.run(args, capture_output=True, text=True)
                if out.returncode:
                    sys.exit(f"demo: {' '.join(args)}: {out.stdout}{out.stderr}")
                os.remove(rendered)
            for path in VIEWS.values():
                os.remove(os.path.join(root, path))
            return record


        def types_of(record):
            return list(dict.fromkeys(o.split(":")[1] for o in record["operands"]))


        def add_to_docs(root, sql, model, record):
            """The row's line in the docs' coverage table, and its types' lines of the counts."""
            kind = record["verified"].split(":")[0]
            types = types_of(record)

            def change(lines):
                out = []
                for line in lines:
                    cells = [c.strip() for c in line.split("|")]
                    if len(cells) == 10 and cells[1].strip("`") in types and cells[2].isdigit():
                        cells[2] = str(int(cells[2]) + 1)
                        at = 3 + KINDS.index(kind)
                        cells[at] = str(int(cells[at]) + 1)
                        line = "| " + " | ".join(cells[1:-1]) + " |\n"
                    out.append(line)
                    if line.startswith(f"| `{model}` |"):
                        out.append(line.replace(f"`{model}`", f"`{sql}`", 1))
                return out

            edit_lines(root, DOCS, change)


        def add_to_two_lane_census(root):
            """The count of value rows in the header of the two-lane census, one more."""

            def change(lines):
                out = []
                for line in lines:
                    words = line.split()
                    if line.startswith("# ") and words[2:3] == ["of"] and words[3].isdigit():
                        words[3] = str(int(words[3]) + 1)
                        line = " ".join(words) + "\n"
                    out.append(line)
                return out

            edit_lines(root, TWO_LANE, change)


        def add_to_suite(root, sql, model):
            def change(lines):
                at = next(i for i, x in enumerate(lines) if x.startswith(f'      Row("{model}"'))
                return lines[:at] + [f'      Row("{sql}"),\n'] + lines[at:]

            edit_lines(root, SUITE, change)


        def add_skip(root, sql, family):
            test = f"{family}: {sql} matches the row engine under both consumers"
            line = f"checkIntOverflow=false\tVarkaCoverageHarnessSuite\t{test}\tfails\tthe demo's skip\n"
            if LAYOUT["skips"]:
                name = f"varka-{ROW}-{varka_rows.slug(test)}.tsv"
                write(root, f"sql/varka/matrix/skips.d/{name}", line)
            else:
                # At the end of the file, where a task that knows no better place puts it.
                edit_lines(root, SKIPS, lambda lines: lines + [line])


        def add_to_census(root, entry, record):
            """What the census needs of a row-adding task: the task's number on its entry's line of
            the curated census and of the census, and, while a branch writes the docs' blocks, one more
            row for each of the row's types in the last cell of the docs' support table, which counts
            the coverage rows that read a type."""
            folder = census_folder(root)

            def curated(lines):
                return [
                    x.rstrip("\n") + f"; row {ROW}\n" if x.startswith(entry + "|") else x for x in lines
                ]

            def census(lines):
                return [
                    x.rstrip("\n") + f"; row {ROW}\n" if x.startswith(entry + "\t") else x for x in lines
                ]

            def support(lines):
                out = []
                for line in lines:
                    cells = [c.strip() for c in line.split("|")]
                    types = [f"(`{t}`)" for t in types_of(record)]
                    if len(cells) == 10 and cells[1].endswith(tuple(types)) and cells[8].isdigit():
                        cells[8] = str(int(cells[8]) + 1)
                        line = "| " + " | ".join(cells[1:-1]) + " |\n"
                    out.append(line)
                return out

            edit_lines(root, f"{folder}/census_curated.txt", curated)
            edit_lines(root, f"{folder}/census.tsv", census)
            if not LAYOUT["docs"]:
                edit_lines(root, DOCS, support)


        def add_row(root, sql, model, entry, with_census):
            """Everything a task that fuses the row `sql` writes to the shared lists."""
            if LAYOUT["records"]:
                record = add_row_records(root, sql, model)
            else:
                record = add_row_files(root, sql, model)
            if not LAYOUT["docs"]:
                add_to_docs(root, sql, model, record)
                if record["form"] == "projection":
                    add_to_two_lane_census(root)
            if not LAYOUT["row_files"]:
                add_to_suite(root, sql, model)
            add_skip(root, sql, record["family"])
            if with_census:
                add_to_census(root, entry, record)


        class Repo:
            """A scratch repository that reads no configuration and no attributes."""

            def __init__(self, top):
                self.top = top
                self.env = {
                    "PATH": os.environ["PATH"],
                    "HOME": top,
                    "GIT_CONFIG_GLOBAL": os.devnull,
                    "GIT_CONFIG_SYSTEM": os.devnull,
                    "GIT_DIR": os.path.join(top, "repo.git"),
                    "GIT_AUTHOR_NAME": "demo",
                    "GIT_AUTHOR_EMAIL": "demo@localhost",
                    "GIT_COMMITTER_NAME": "demo",
                    "GIT_COMMITTER_EMAIL": "demo@localhost",
                    "GIT_AUTHOR_DATE": "2026-11-27T00:00:00Z",
                    "GIT_COMMITTER_DATE": "2026-11-27T00:00:00Z",
                }
                self.git("init", "-q", "--bare", self.env["GIT_DIR"])

            def git(self, *args, env=None, check=True):
                out = subprocess.run(
                    ["git", *args], env={**self.env, **(env or {})}, capture_output=True, text=True
                )
                if check and out.returncode:
                    sys.exit(f"demo: git {' '.join(args)}: {out.stderr.strip()}")
                return out

            def commit(self, work, message, *parents):
                """A commit of the directory `work`, on `parents`."""
                env = {"GIT_WORK_TREE": work, "GIT_INDEX_FILE": os.path.join(self.top, "index")}
                if os.path.exists(env["GIT_INDEX_FILE"]):
                    os.remove(env["GIT_INDEX_FILE"])
                self.git("add", "-A", ".", env=env)
                tree = self.git("write-tree", env=env).stdout.strip()
                return self.commit_tree(tree, message, *parents)

            def commit_tree(self, tree, message, *parents):
                args = [x for p in parents for x in ("-p", p)]
                return self.git("commit-tree", tree, *args, "-m", message).stdout.strip()

            def merge(self, ours, theirs):
                """(tree, conflicted files) of merging commit `theirs` into commit `ours`."""
                out = self.git(
                    "merge-tree", "--write-tree", "--name-only", "--no-messages", ours, theirs, check=False
                )
                if out.returncode not in (0, 1):
                    sys.exit(f"demo: git merge-tree: {out.stderr.strip()}")
                lines = out.stdout.strip().split("\n")
                return lines[0], sorted(set(lines[1:])) if out.returncode else []

            def tree_of(self, commit):
                return self.git("rev-parse", commit + "^{tree}").stdout.strip()

            def extract(self, tree, to):
                """The files of `tree` written under the directory `to`."""
                os.makedirs(to)
                env = {"GIT_WORK_TREE": to, "GIT_INDEX_FILE": os.path.join(self.top, "index-x")}
                self.git("read-tree", tree, env=env)
                self.git("checkout-index", "-a", "-f", env=env)


        def run_orders(repo, base, names, commits):
            """Merges `names` into `base` in every order; returns (conflicted files, merged trees)."""
            conflicted, trees = set(), set()
            for order in itertools.permutations(names):
                head, failed = base, []
                for step, name in enumerate(order, 1):
                    tree, failed = repo.merge(head, commits[name])
                    if failed:
                        print(f"  {' then '.join(order)}: merge {step} conflicts in {len(failed)} file(s)")
                        conflicted.update(failed)
                        break
                    head = repo.commit_tree(tree, f"merge {name}", head, commits[name])
                if not failed:
                    print(f"  {' then '.join(order)}: no conflict")
                    trees.add(repo.tree_of(head))
            return conflicted, trees


        def run_round(top, title, with_census):
            """One round over the five branches; returns the files in conflict or stale."""
            print(title)
            os.makedirs(top)
            repo = Repo(top)
            work = os.path.join(top, "base")
            copied = copy_lists(ROOT, work)
            base = repo.commit(work, "base")
            branches = choose(work)
            print(f"  base: {len(copied)} files, no attributes file, no merge driver")
            commits = {}
            for name, sql, model, entry in branches:
                branch = os.path.join(top, name)
                shutil.copytree(work, branch)
                add_row(branch, sql, model, entry, with_census)
                commits[name] = repo.commit(branch, name, base)
                changed = repo.git("diff", "--name-only", base, commits[name]).stdout.split()
                print(f"  branch {name}: adds {sql}, changes {len(changed)} file(s)")
            owed = set()
            groups = [("three siblings of one family", [b[0] for b in branches[:3]])]
            groups.append(("two rows of two families", [b[0] for b in branches[3:]]))
            for group, names in groups:
                print(f"  {group}:")
                files, trees = run_orders(repo, base, names, commits)
                for name in sorted(files):
                    print(f"  in conflict: {name}")
                owed |= files
                if files:
                    continue
                if len(trees) != 1:
                    print(f"  the orders give {len(trees)} different trees")
                    owed.add("(the merged tree depends on the order)")
                    continue
                # What one branch that adds the same rows would commit.
                whole = os.path.join(top, "whole-" + names[0])
                shutil.copytree(work, whole)
                for branch in branches:
                    if branch[0] in names:
                        add_row(whole, *branch[1:], with_census)
                wanted = repo.commit(whole, "whole", base)
                stale = repo.git("diff", "--name-only", next(iter(trees)), wanted).stdout.split()
                for name in stale:
                    print(f"  merged with no conflict and stale, a regeneration owed: {name}")
                owed |= set(stale)
                if LAYOUT["records"]:
                    out = os.path.join(top, "merged-" + names[0])
                    repo.extract(next(iter(trees)), out)
                    tool = os.path.join(ROOT, "dev/varka_rows.py")
                    check = subprocess.run(
                        [sys.executable, tool, "--root", out, "--check"], capture_output=True, text=True
                    )
                    print(f"  dev/varka_rows.py --check on the merged tree: exit {check.returncode}")
                    sys.stdout.write(check.stderr)
                    if check.returncode:
                        owed.add("(dev/varka_rows.py --check)")
            print(f"  files in conflict or stale in some order: {len(owed)}")
            return owed


        def main(argv):
            keep = argv[2] if argv[1:2] == ["--keep"] and len(argv) == 3 else None
            if argv[1:] and keep is None:
                print(__doc__, file=sys.stderr)
                return 2
            top = keep or tempfile.mkdtemp(prefix="varka-rows-demo-")
            if keep and os.path.exists(top) and os.listdir(top):
                sys.exit(f"demo: {top} is not empty")
            print(f"layout: {', '.join(k for k, v in LAYOUT.items() if v) or 'every list in one file'}")
            own = run_round(os.path.join(top, "1"), "round 1, the coverage row's lists:", False)
            run_round(os.path.join(top, "2"), "round 2, with what the census needs (not counted):", True)
            print(f"round 1: {len(own)} file(s) in conflict or stale")
            if not keep:
                shutil.rmtree(top)
            return 1 if own else 0


        if __name__ == "__main__":
            sys.exit(main(sys.argv))

2. In `dev/sparktestsupport/modules.py`, the one line of premise 7 becomes six:

        varka_docs_check_regexes = (
            r"sql/varka/(width_audit|emitted_bytes)\.json$",
            # The row records' tool and the merge demonstration (VARKA-653): the docs job runs
            # their cases, and every test JVM runs the tool, so a change to either is code.
            r"dev/varka_rows(_demo)?\.py$",
        )

   That tuple, not `varka_docs_regexes`: a change confined to a file of `varka_docs_regexes`
   is "docs only" and skips the catalyst job, and the tool is run by every test JVM.
3. Run the demonstration into the results file (5.0).
4. This file's section 14 and the row's status (5.0).

**The tests.**

| check | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| command 2's output, line by line | the script builds the five branches on today's files and finds each shared file | a list file that changed form, which `edit_lines` reports as "no line of <file> matched" and exits on |
| command 3 | a merge driver in the clone's configuration is not used | a scratch repository that inherits the clone's driver and hides every conflict in a generated file |
| each branch's line "changes 7 file(s)" in round 1 and "changes 9 file(s)" in round 2 | every edit of `add_row` changed its file | a branch that adds nothing and so merges clean |
| command 4 | `modules.py`'s doctests, and the scope of the two new files | a tool whose change runs the docs job alone |

**The commands.**

    dev/varka_precommit.sh                                                # 1: exits 0
    python3 dev/varka_rows_demo.py; echo "exit $?"                        # 2: the block below
    git config --get merge.varka-generated.driver | wc -l; python3 dev/varka_rows_demo.py | grep -c "in conflict: sql/varka/coverage.json"   # 3: 1 and 2: once a round
    python3 -m doctest dev/sparktestsupport/modules.py; python3 -c "import sys; sys.path.insert(0, 'dev'); from sparktestsupport import modules as m; print(m.varka_change_scope(['dev/varka_rows.py']), m.varka_docs.contains_file('dev/varka_rows_demo.py'))"   # 4: no doctest failure; scoped True
    python3 dev/varka_msg_check.py size                                   # 5: a count under 800

Command 2 prints, with `<m>` the census folder (`m8`, or `m9` once row 654's part a has
merged), `<T>` the path of `$TS`, and each "(... lines)" standing for the orders' lines, one
an order, each `<order>: merge 2 conflicts in <n> file(s)`:

    layout: every list in one file
    round 1, the coverage row's lists:
      base: 9 files, no attributes file, no merge driver
      branch sibling-1: adds CAST(dt AS BIGINT) -- demo row 1, changes 7 file(s)
      branch sibling-2: adds CAST(dt AS BIGINT) -- demo row 2, changes 7 file(s)
      branch sibling-3: adds CAST(dt AS BIGINT) -- demo row 3, changes 7 file(s)
      branch family-a: adds date_add(d, 3) -- demo row 4, changes 7 file(s)
      branch family-b: adds year(d) -- demo row 5, changes 7 file(s)
      three siblings of one family:
      (six lines)
      in conflict: docs/sql-varka.md
      in conflict: <T>/VarkaCoverageSuite.scala
      in conflict: sql/varka/coverage.json
      in conflict: sql/varka/emitted_bytes.json
      in conflict: sql/varka/matrix/skips.tsv
      in conflict: sql/varka/width_audit.json
      two rows of two families:
      (two lines)
      in conflict: sql/varka/emitted_bytes.json
      in conflict: sql/varka/matrix/skips.tsv
      files in conflict or stale in some order: 6
    round 2, with what the census needs (not counted):
      (the same lines, each branch with "changes 9 file(s)", and among the siblings' files)
      in conflict: sql/varka/plans/<m>/census.tsv
      in conflict: sql/varka/plans/<m>/census_curated.txt
      files in conflict or stale in some order: 8
    round 1: 6 file(s) in conflict or stale
    exit 1

The three model rows are the tree's at the build: where master's table has changed, the SQL
texts differ and the counts stand. The two-lane census's file is among the nine and in no
conflict: each branch raises its count by one, which merges with no conflict; the stale count
is what part D removes, and the script's "merged with no conflict and stale" check reports it
once nothing else conflicts.

**Done when** command 2 prints the block; the results file is committed; the Build is green.

**The expectations, to tick.**

- [ ] `dev/varka_rows_demo.py` exists, is executable, and exits 1 on master with 6 files in
  round 1 and 8 in round 2.
- [ ] `git status --short` before the commit: `A dev/varka_rows_demo.py`,
  `M dev/sparktestsupport/modules.py`, `A sql/varka/plans/m9/results/VARKA-653-demo.txt`,
  `M sql/varka/plans/m9/PLAN.md`, `M sql/varka/plans/m9/VARKA-653.md`, and no other line.
- [ ] No generated file, no census cell, no emitted byte and no benchmark changes.
- [ ] `modules.py` is not Varka-scoped: this pull request's Build runs Spark's whole matrix
  once. Say so in the description.

**Not here.** `dev/varka_rows.py`; any CI step that runs the script (part F adds it, when it
exits 0); any list file.

### 5.2 Part A2: the tool and the test JVM's door

**B0, the premises.**

    grep -c 'r"dev/varka_rows(_demo)?\\.py\$",' dev/sparktestsupport/modules.py   # 1: 1 (A1 merged)
    git branch -a --list '*varka-653a2*'                                  # 2: nothing
    ls dev/varka_rows.py $TJ/VarkaRowFiles.java $TJ/VarkaRowFilesSuite.java 2>&1 | grep -c "No such file"   # 3: 3
    ls sql/varka/rows sql/varka/.gitignore 2>&1 | grep -c "No such file"  # 4: 2
    grep -n "^    - name: The generated files' merge driver$" .github/workflows/build_and_test.yml   # 5: one line
    grep -c "^@VarkaSuite$" $TJ/VarkaCastMatrixSuite.java                 # 6: 1 (the annotation's form)
    command -v python3                                                    # 7: a path
    for w in $(grep -l "build/sbt\|build/mvn\|varka_matrix.sh\|varka_scoped_suites" .github/workflows/*.yml); do echo "$w $(grep -c 'self-hosted\|^ *container:' $w) $(grep -h 'runs-on:' $w | grep -vc 'ubuntu-latest\|ubuntu-24.04-arm\|matrix.runner\|inputs.runner\|inputs.os')"; done | grep "varka-\|benchmark.yml\|maven_test.yml"
                                                                          # 8: one line a workflow, each ending " 0 0"
    grep -c "^      - name: Run tests$" .github/workflows/maven_test.yml; grep -h "os:" .github/workflows/build_maven*.yml | sort -u   # 8b: 1; then os: macos-26 and os: ubuntu-24.04-arm (the default is ubuntu-latest)
    grep -c '^        r"sql/varka/width_audit\\.json\$",$' dev/sparktestsupport/modules.py   # 9: 1, the last line of the catalyst module's list

Premise 8 lists every workflow that runs a test JVM over Varka's suites (on the plan's commit:
`varka-fuzz`, `varka-mutation`, `varka-option-matrix`, `varka-sweeps`,
`varka-surface-benchmark`, `varka-tier-split`, `varka-width-audit`, `benchmark.yml` and
`maven_test.yml`, which the `build_maven*.yml` workflows call and which runs catalyst's tests
with `build/mvn` on `ubuntu-latest`, `ubuntu-24.04-arm` and `macos-26`; the Build's Varka
jobs are in `build_and_test.yml`, whose docs and catalyst jobs this pull request runs). Under
Maven the door finds the repository as under sbt: the root `pom.xml` sets the system property
`spark.test.home` to `${session.executionRootDirectory}` for surefire and for the ScalaTest
plugin, the property that `VarkaRowFiles.home` and the Java suites of today read. Premise 8's
two numbers are the lines that name a self-hosted runner or a container, and the `runs-on`
lines that name another image than GitHub's `ubuntu-latest` and
`ubuntu-24.04-arm` (directly, or through `matrix.runner`, `inputs.runner` or `inputs.os`,
whose values premises 8a and 8b list). GitHub's hosted Ubuntu and macOS images have `python3`
on the path; for the Maven workflow, which no pull request runs, step 4a adds the check
itself.

    for w in $(grep -l "build/sbt\|varka_matrix.sh\|varka_scoped_suites" .github/workflows/varka-*.yml .github/workflows/benchmark.yml); do grep -h -o 'ubuntu-[a-z0-9.-]*\|macos-[a-z0-9.-]*\|windows-[a-z0-9.-]*' $w; done | sed 's/\.$//' | sort -u
                                                                          # 8a: ubuntu-24.04-arm and ubuntu-latest, and no other line

| line | it prints | what the builder does |
| :--- | :--- | :--- |
| 7 | nothing | stop: the laptop has no `python3` on the path |
| 8 or 8a | a workflow with another number than 0, or another image | no stop. In that workflow, in each job that runs sbt, add as the job's first step the two lines `- name: python3 is on the path, for the test JVM's door (VARKA-653)` and `run: python3 --version`. Name the workflow in the description and ask the lead to dispatch it on the branch. Where that step fails in the run, add before it the step `- uses: actions/setup-python@v7` with `with:` and `python-version: '3.12'`, as `build_and_test.yml`'s step "Install Python 3.12" has it, and ask for the dispatch again |

**The steps.**

1. Add `dev/varka_rows.py`, mode 755, with exactly this text:

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
        """A coverage row's generated records, one file a row, and the three files assembled from them.

          dev/varka_rows.py --views            # write the three views from the records, where they differ
          dev/varka_rows.py --split KIND [--from FILE]  # write KIND's records from its view's text
          dev/varka_rows.py --check            # fail unless the records are what --split would write
          dev/varka_rows.py --show KIND [REV]  # print a view, from the disk or from a revision
          dev/varka_rows.py --self-test

        KIND is coverage, bytes or audit. A view is the file a suite renders and reads:
        sql/varka/coverage.json (VarkaCoverageSuite), sql/varka/emitted_bytes.json
        (VarkaEmittedBytesSuite) and sql/varka/width_audit.json (VarkaWidthAuditSuite). Git ignores the
        views (VARKA-653): two branches that each add a coverage row would both insert lines at one
        place of each, and GitHub's merge runs no merge driver. What is committed is under
        sql/varka/rows: for the row with SQL text S in family F, the files F/S.json (its coverage
        record), F/S.bytes.json (its method digests) and F/S.audit.json (its width-audit lines), F and
        S each a slug; the audit's construction shapes under _construction; one file a class the
        compiler admits under _admitted; and what belongs to no row in _coverage.json, _bytes.json and
        _audit.json. A branch that adds a row adds that row's files and changes no other, so two such
        branches merge with nothing to resolve and nothing to regenerate.

        The order of a view's rows is not stored in one place, since two branches would both edit it:
        a row of the Scala table names the row before it in its family as "after" ("" for the first),
        and a row added as a file F/S.row comes after them, by its slug; the families of the Scala
        table are listed in _coverage.json and a family made of row files alone comes after them, by
        its title.
        """

        import hashlib
        import json
        import os
        import re
        import subprocess
        import sys
        import tempfile

        from varka_generated_merge import render_json

        ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        ROWS = "sql/varka/rows"
        VIEWS = {
            "coverage": "sql/varka/coverage.json",
            "bytes": "sql/varka/emitted_bytes.json",
            "audit": "sql/varka/width_audit.json",
        }
        HEADS = {"coverage": "_coverage.json", "bytes": "_bytes.json", "audit": "_audit.json"}
        SUFFIX = {"coverage": ".json", "bytes": ".bytes.json", "audit": ".audit.json"}
        CONSTRUCTION = "_construction"
        ADMITTED = "_admitted"
        REGEN = "dev/varka_regen_generated.sh --all"


        def slug(text, limit=40):
            """A file name for `text`: its letters and digits in lower case, every other run of
            characters one dash, cut to `limit`, then a dash and eight hex digits of its SHA-256, so
            that two texts never share a name and a name never depends on another row.

            >>> slug("CAST(i AS TINYINT)")
            'cast-i-as-tinyint-b36ebbbe'
            >>> slug("Integral casts", 0)
            '060e4c22'
            """
            stem = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:limit].strip("-")
            digest = hashlib.sha256(text.encode("utf-8")).hexdigest()[:8]
            return f"{stem}-{digest}" if stem else digest


        def family_slug(title):
            """A family's directory: its title's letters and digits, with no hash (a title is unique
            and a directory a reader can find by eye).

            >>> family_slug("TINYINT and SMALLINT widened to int, as values")
            'tinyint-and-smallint-widened-to-int-as-values'
            """
            return re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")


        def dump(value):
            return render_json(value) + "\n"


        class Disk:
            """The files under a root directory."""

            def __init__(self, root):
                self.root = root

            def read(self, rel):
                path = os.path.join(self.root, rel)
                if not os.path.exists(path):
                    return None
                with open(path, encoding="utf-8") as f:
                    return f.read()

            def names(self, rel):
                """Every file under `rel`, as paths relative to it, sorted."""
                top = os.path.join(self.root, rel)
                found = []
                for where, _, files in os.walk(top):
                    found += [os.path.relpath(os.path.join(where, f), top) for f in files]
                return sorted(found)


        class Revision:
            """The files of a git revision, read with one `git ls-tree` and one `git cat-file`."""

            def __init__(self, root, rev):
                self.root, self.rev, self.blobs = root, rev, None

            def git(self, *args, text=None):
                out = subprocess.run(["git", *args], cwd=self.root, capture_output=True, input=text)
                if out.returncode:
                    sys.exit(f"git {' '.join(args)}: {out.stderr.decode().strip()}")
                return out.stdout

            def load(self, rel):
                listing = self.git("ls-tree", "-r", "-z", self.rev, "--", rel).decode().split("\0")
                entries = [e.split("\t", 1) for e in listing if e]
                ids = "".join(meta.split()[2] + "\n" for meta, _ in entries).encode()
                data, at, self.blobs = self.git("cat-file", "--batch", text=ids), 0, {}
                for _, path in entries:
                    end = data.index(b"\n", at)
                    size = int(data[at:end].split()[2])
                    self.blobs[path] = data[end + 1 : end + 1 + size].decode("utf-8")
                    at = end + 1 + size + 1

            def read(self, rel):
                if self.blobs is None:
                    self.load(ROWS)
                return self.blobs.get(rel)

            def names(self, rel):
                if self.blobs is None:
                    self.load(ROWS)
                return sorted(p[len(rel) + 1 :] for p in self.blobs if p.startswith(rel + "/"))


        def records(tree):
            """The coverage records in a view's order, each (family slug, row slug, record)."""
            head = json.loads(tree.read(f"{ROWS}/{HEADS['coverage']}") or '{"families": []}')
            found = {}
            for name in tree.names(ROWS):
                where, _, base = name.rpartition("/")
                if where.startswith("_") or not where or base.count(".") != 1:
                    continue
                if base.endswith(".json"):
                    record = json.loads(tree.read(f"{ROWS}/{name}"))
                    found.setdefault(record["family"], []).append((where, base[: -len(".json")], record))
            listed = [t for t in head["families"] if t in found]
            titles = listed + sorted(t for t in found if t not in head["families"])
            out = []
            for title in titles:
                rows = found[title]
                # The table's rows, each after the one it names; a row no chain reaches (two rows
                # after one, a row after none) is put with the row files, and --check reports it.
                after = {r[2]["after"]: r for r in rows if "after" in r[2]}
                chain, at = [], ""
                while at in after and after[at] not in chain:
                    chain.append(after[at])
                    at = after[at][2]["sql"]
                out += chain + sorted((r for r in rows if r not in chain), key=lambda r: r[1])
            return out


        def assemble(kind, tree):
            """The view of `kind` as the records under `tree` give it, or None with no head file."""
            head_text = tree.read(f"{ROWS}/{HEADS[kind]}")
            if head_text is None:
                return None
            head = json.loads(head_text)
            rows = records(tree)

            def record(family, row, suffix):
                text = tree.read(f"{ROWS}/{family}/{row}{suffix}")
                return json.loads(text) if text is not None else None

            if kind == "coverage":
                names = [n[: -len(".json")] for n in tree.names(f"{ROWS}/{ADMITTED}")]
                doc = {k: v for k, v in head.items() if k != "families"}
                doc["expressions"] = [{k: v for k, v in r.items() if k != "after"} for _, _, r in rows]
                doc["catalyst_classes_admitted"] = sorted(names)
                return dump(doc)
            if kind == "bytes":
                held = [(r["sql"], record(f, s, SUFFIX["bytes"])) for f, s, r in rows]
                held = [(sql, r) for sql, r in held if r is not None]
                doc = {k: v for k, v in head.items() if k in ("generated_by", "description")}
                doc["coverage_rows_skipped"] = head["coverage_rows_skipped"]
                doc["lanes"] = {}
                for lanes, section in head["lanes"].items():
                    coverage = {sql: r["lanes"][lanes] for sql, r in held if lanes in r["lanes"]}
                    doc["lanes"][lanes] = {"coverage": coverage, **section}
                doc["option_arms"] = head["option_arms"]
                if "option_arm_rows" in head:
                    doc["option_arm_rows"] = {
                        arm: {
                            lanes: {
                                sql: r["arms"][arm][lanes]
                                for sql, r in held
                                if lanes in r.get("arms", {}).get(arm, {})
                            }
                            for lanes in head["option_arm_rows"][arm]
                        }
                        for arm in head["option_arm_rows"]
                    }
                return dump(doc)
            shapes = [record(f, s, SUFFIX["audit"]) for f, s, _ in rows]
            for name in tree.names(f"{ROWS}/{CONSTRUCTION}"):
                shapes.append(json.loads(tree.read(f"{ROWS}/{CONSTRUCTION}/{name}")))
            shapes = sorted((s for s in shapes if s is not None), key=lambda s: s["shape"])
            doc = {k: v for k, v in head.items() if k != "widths"}
            doc["widths"] = {
                w: {s["shape"]: s["widths"][w] for s in shapes if w in s["widths"]} for w in head["widths"]
            }
            return dump(doc)


        def split(kind, view, hand):
            """The files `view`, a view's text, splits into, as {path under ROWS: text}. `hand` holds
            the (family slug, row slug) of each row added as a file, which names no row before it."""
            doc = json.loads(view)
            out = {}
            if kind == "coverage":
                families, last = [], {}
                for e in doc["expressions"]:
                    f, s = family_slug(e["family"]), slug(e["sql"])
                    if not f:
                        sys.exit(f"rows: the family title {e['family']!r} has no letter or digit")
                    record = dict(e)
                    if (f, s) not in hand:
                        if e["family"] not in families:
                            families.append(e["family"])
                        record["after"] = last.get(f, "")
                        last[f] = e["sql"]
                    out[f"{f}/{s}.json"] = dump(record)
                own = ("expressions", "catalyst_classes_admitted")
                head = {k: v for k, v in doc.items() if k not in own}
                head["families"] = families
                out[HEADS[kind]] = dump(head)
                for name in doc["catalyst_classes_admitted"]:
                    out[f"{ADMITTED}/{name}.json"] = dump({})
                return out
            return out


        def split_keyed(kind, view, family_of):
            """As `split`, for the two views keyed by a row's SQL; `family_of` maps it to its family's
            slug, and a width-audit shape that is no row goes under CONSTRUCTION."""
            doc = json.loads(view)
            out = {}
            if kind == "bytes":
                rows = {}
                for lanes, section in doc["lanes"].items():
                    for sql, methods in section["coverage"].items():
                        rows.setdefault(sql, {"sql": sql, "lanes": {}})["lanes"][lanes] = methods
                for arm, by_lanes in doc.get("option_arm_rows", {}).items():
                    for lanes, digests in by_lanes.items():
                        for sql, digest in digests.items():
                            arms = rows[sql].setdefault("arms", {})
                            arms.setdefault(arm, {})[lanes] = digest
                for sql, record in rows.items():
                    out[f"{family_of[sql]}/{slug(sql)}.bytes.json"] = dump(record)
                head = {k: v for k, v in doc.items() if k != "option_arm_rows"}
                head["lanes"] = {
                    n: {k: v for k, v in section.items() if k != "coverage"}
                    for n, section in doc["lanes"].items()
                }
                if "option_arm_rows" in doc:
                    head["option_arm_rows"] = {a: list(w) for a, w in doc["option_arm_rows"].items()}
                out[HEADS[kind]] = dump(head)
                return out
            shapes = {}
            for width, by_shape in doc["widths"].items():
                for shape, lines in by_shape.items():
                    shapes.setdefault(shape, {"shape": shape, "widths": {}})["widths"][width] = lines
            for shape, record in shapes.items():
                where = family_of.get(shape, CONSTRUCTION)
                out[f"{where}/{slug(shape)}.audit.json"] = dump(record)
            head = {k: v for k, v in doc.items() if k != "widths"}
            head["widths"] = list(doc["widths"])
            out[HEADS[kind]] = dump(head)
            return out


        def hand_rows(tree):
            """The (family slug, row slug) of each row added as a file."""
            names = tree.names(ROWS)
            return {tuple(n[: -len(".row")].split("/")) for n in names if n.endswith(".row")}


        def family_of(coverage):
            """Each row's family slug, by the row's SQL, from the coverage view's text."""
            return {e["sql"]: family_slug(e["family"]) for e in json.loads(coverage)["expressions"]}


        def expected(tree, kind, view):
            """What --split writes for `kind` from `view`, its view's text: {path under ROWS: text}."""
            if kind == "coverage":
                return split("coverage", view, hand_rows(tree))
            coverage = assemble("coverage", tree)
            if coverage is None:
                sys.exit(f"rows: no {ROWS}/{HEADS['coverage']}; split the coverage view first")
            return split_keyed(kind, view, family_of(coverage))


        def owned(kind, name):
            """Whether the file `name` under ROWS is one --split writes for `kind`."""
            where, _, base = name.rpartition("/")
            if name == HEADS[kind]:
                return True
            if where == ADMITTED:
                return kind == "coverage"
            if not where or base.endswith(".row"):
                return False
            if where == CONSTRUCTION:
                return kind == "audit"
            if base.endswith(SUFFIX["bytes"]) or base.endswith(SUFFIX["audit"]):
                return base.endswith(SUFFIX[kind])
            return kind == "coverage" and base.endswith(".json")


        def write_if_changed(path, text):
            """Writes `text` to `path` through a rename, unless the file already holds it."""
            if os.path.exists(path):
                with open(path, encoding="utf-8") as f:
                    if f.read() == text:
                        return False
            os.makedirs(os.path.dirname(path), exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".rows-")
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(text)
            os.replace(tmp, path)
            return True


        def do_split(root, kind, source):
            """Writes the records of `kind` from the view in the file `source`, removes the records it
            no longer gives, and writes the views again."""
            with open(source, encoding="utf-8") as f:
                want = expected(Disk(root), kind, f.read())
            top = os.path.join(root, ROWS)
            changed = sum(write_if_changed(os.path.join(top, name), text) for name, text in want.items())
            gone = 0
            for name in Disk(root).names(ROWS):
                if name not in want and owned(kind, name):
                    os.remove(os.path.join(top, name))
                    gone += 1
            print(f"rows: split {kind}: {changed} written, {gone} removed, {len(want)} in all")
            views(root)
            return 0


        def views(root=ROOT):
            """Writes each view the records give, where the file differs; returns the paths written.
            Every tool that reads a view calls this first, and so does the test JVM (VarkaRowFiles)."""
            tree = Disk(root)
            written = []
            for kind, rel in VIEWS.items():
                text = assemble(kind, tree)
                if text is not None and write_if_changed(os.path.join(root, rel), text):
                    written.append(rel)
            return written


        def check(root):
            """The findings of --check: the views as the records give them must split back into the
            records, so no record is missing, left over, misnamed or written in another form."""
            tree = Disk(root)
            errors = []
            views = {kind: assemble(kind, tree) for kind in VIEWS}
            if views["coverage"] is None:
                return [f"no {ROWS}/{HEADS['coverage']}"]
            hand = hand_rows(tree)
            families = family_of(views["coverage"])
            want = split("coverage", views["coverage"], hand)
            for kind in ("bytes", "audit"):
                if views[kind] is not None:
                    want.update(split_keyed(kind, views[kind], families))
            for name in tree.names(ROWS):
                if name.endswith(".row"):
                    if name[: -len(".row")] + ".json" not in want:
                        errors.append(f"{ROWS}/{name} has no coverage record; run {REGEN}")
                elif name not in want:
                    errors.append(f"{ROWS}/{name} is no row's record; run {REGEN}")
                elif tree.read(f"{ROWS}/{name}") != want[name]:
                    errors.append(f"{ROWS}/{name} is not as a regeneration writes it; run {REGEN}")
            rows = set(families)
            for kind in ("bytes", "audit"):
                if views[kind] is None:
                    continue
                doc = json.loads(views[kind])
                sections = doc["lanes"] if kind == "bytes" else doc["widths"]
                skipped = set(doc.get("coverage_rows_skipped", []))
                for key, section in sections.items():
                    have = set(section["coverage"] if kind == "bytes" else section)
                    have = {s for s in have if not s.startswith("construction: ")}
                    for sql in sorted(rows - have - skipped)[:3]:
                        errors.append(f"{VIEWS[kind]} at {key} lacks the row {sql!r}; run {REGEN}")
                    for sql in sorted(have - rows)[:3]:
                        errors.append(f"{VIEWS[kind]} at {key} holds {sql!r}, no row; run {REGEN}")
            return errors


        def self_test():
            import doctest

            failed, _ = doctest.testmod()
            coverage = {
                "generated_by": "g",
                "columns": {"i": "int"},
                "expressions": [
                    {"sql": "abs(i)", "family": "Integer arithmetic", "note": "a|b"},
                    {"sql": "-i", "family": "Integer arithmetic", "note": ""},
                    {"sql": "i < 3", "family": "Predicates", "note": ""},
                ],
                "catalyst_classes_admitted": ["Abs", "LessThan", "UnaryMinus"],
            }
            digests = {sql: {"<class>": sql} for sql in ("abs(i)", "-i", "i < 3")}
            emitted = {
                "generated_by": "g",
                "coverage_rows_skipped": [],
                "lanes": {"4": {"coverage": digests, "fuzz": {"blocks": ["x"]}}},
                "option_arms": {"useAVX=2": {"4": "f"}},
                "option_arm_rows": {"useAVX=2": {"4": {"abs(i)": "a", "i < 3": "p"}}},
            }
            audit = {
                "generated_by": "g",
                "host": {"cpu": "c"},
                "widths": {
                    "128": {"-i": [], "abs(i)": ["n"], "construction: x": [], "i < 3": []},
                    "256": {"-i": [], "abs(i)": [], "construction: x": [], "i < 3": []},
                },
            }
            with tempfile.TemporaryDirectory() as root:
                texts = {"coverage": dump(coverage), "bytes": dump(emitted), "audit": dump(audit)}
                os.makedirs(os.path.join(root, "sql/varka"))
                for kind, text in texts.items():
                    with open(os.path.join(root, VIEWS[kind]), "w", encoding="utf-8") as f:
                        f.write(text)
                for kind in VIEWS:
                    do_split(root, kind, os.path.join(root, VIEWS[kind]))
                for kind, text in texts.items():
                    if assemble(kind, Disk(root)) != text:
                        print(f"self-test: the {kind} view does not come back from its records")
                        failed += 1
                if check(root):
                    print(f"self-test: a fresh split fails the check: {check(root)}")
                    failed += 1
                # A row added as a file comes after its family's rows, whatever its slug.
                family = os.path.join(root, ROWS, "integer-arithmetic")
                added = {"sql": "+i", "family": "Integer arithmetic", "note": ""}
                with open(os.path.join(family, slug("+i") + ".row"), "w", encoding="utf-8") as f:
                    f.write("sql: +i\n")
                with open(os.path.join(family, slug("+i") + ".json"), "w", encoding="utf-8") as f:
                    f.write(dump(added))
                order = [e["sql"] for e in json.loads(assemble("coverage", Disk(root)))["expressions"]]
                if order != ["abs(i)", "-i", "+i", "i < 3"]:
                    print(f"self-test: a row file is out of place: {order}")
                    failed += 1
                # Each planted fault is named: a record left over, one missing, one reformatted.
                plants = {
                    "is no row's record": lambda: open(os.path.join(family, "x-0.bytes.json"), "w").close(),
                    "lacks the row '+i'": lambda: None,
                    "is not as a regeneration writes it": lambda: open(
                        os.path.join(family, slug("-i") + ".json"), "a", encoding="utf-8"
                    ).write("\n"),
                }
                for message, plant in plants.items():
                    plant()
                    if not any(message in e for e in check(root)):
                        print(f"self-test: the check does not say {message!r}: {check(root)}")
                        failed += 1
            print("self-test: " + ("FAILED" if failed else "ok"))
            return 1 if failed else 0


        def main(argv):
            root = ROOT
            if "--root" in argv:
                at = argv.index("--root")
                root = os.path.abspath(argv[at + 1])
                argv = argv[:at] + argv[at + 2 :]
            if argv[1:] == ["--views"]:
                for rel in views(root):
                    print(f"rows: wrote {rel}")
                return 0
            if argv[1:2] == ["--split"] and len(argv) in (3, 5) and argv[2] in VIEWS:
                if len(argv) == 5 and argv[3] != "--from":
                    return 2
                source = argv[4] if len(argv) == 5 else os.path.join(root, VIEWS[argv[2]])
                return do_split(root, argv[2], source)
            if argv[1:] == ["--check"]:
                errors = check(root)
                for e in errors:
                    print("rows: " + e, file=sys.stderr)
                return 1 if errors else 0
            if argv[1:2] == ["--show"] and len(argv) in (3, 4) and argv[2] in VIEWS:
                tree = Revision(root, argv[3]) if len(argv) == 4 else Disk(root)
                text = assemble(argv[2], tree)
                if text is None:
                    sys.exit(f"rows: no {ROWS}/{HEADS[argv[2]]} there")
                sys.stdout.write(text)
                return 0
            if argv[1:] == ["--self-test"]:
                return self_test()
            print(__doc__, file=sys.stderr)
            return 2


        if __name__ == "__main__":
            sys.exit(main(sys.argv))

2. Add `$TJ/VarkaRowFiles.java`: the license header of `$TJ/VarkaCastMatrixSuite.java` (its
   first 16 lines), an empty line, then:

        package org.apache.spark.sql.catalyst.expressions.codegen.varka;

        import java.io.File;
        import java.io.IOException;
        import java.io.UncheckedIOException;
        import java.nio.charset.StandardCharsets;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.util.ArrayList;
        import java.util.List;

        /**
         * The test JVM's door to {@code dev/varka_rows.py} (VARKA-653). The three generated files a suite
         * renders and reads - {@code coverage.json}, {@code emitted_bytes.json} and
         * {@code width_audit.json} under {@code sql/varka} - are views: git ignores them, and the tool
         * assembles them from one record a coverage row under {@code sql/varka/rows}. A suite reads a
         * view through {@link #view}, which assembles the views first, and a suite that regenerates
         * writes through {@link #write}, which splits the rendered text into records.
         */
        public final class VarkaRowFiles {

          private static boolean assembled;

          private VarkaRowFiles() {
          }

          /** The Spark home the tests run in. */
          static String home() {
            String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
            if (home == null) {
              throw new IllegalStateException("spark.test.home or SPARK_HOME is not set.");
            }
            return home;
          }

          /** Writes the views from the records, once a JVM. */
          public static synchronized void views() {
            if (!assembled) {
              run("--views");
              assembled = true;
            }
          }

          /** The view {@code name} under {@code sql/varka}, assembled from the records first. */
          public static Path view(String name) {
            views();
            return Path.of(home(), "sql", "varka", name);
          }

          /**
           * Writes the records of {@code kind} ({@code coverage}, {@code bytes} or {@code audit}) from
           * {@code text}, the view as a suite renders it, and then the views. The text goes to the tool
           * in a file of its own, never through the view: another test JVM may write the views meanwhile.
           */
          public static synchronized void write(String kind, String text) {
            try {
              Path rendered = Files.createTempFile("varka-rows-", ".json");
              try {
                Files.writeString(rendered, text, StandardCharsets.UTF_8);
                run("--split", kind, "--from", rendered.toString());
              } finally {
                Files.deleteIfExists(rendered);
              }
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }

          /** Runs the tool with {@code args} in the Spark home, and returns what it printed. */
          static String run(String... args) {
            String home = home();
            var command = new ArrayList<String>(List.of("python3", "dev/varka_rows.py"));
            command.addAll(List.of(args));
            String shown = String.join(" ", command);
            try {
              Process process = new ProcessBuilder(command)
                  .directory(new File(home))
                  .redirectErrorStream(true)
                  .start();
              String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
              int status = process.waitFor();
              if (status != 0) {
                throw new IllegalStateException(
                    shown + " exited " + status + " in " + home + ":\n" + printed);
              }
              return printed;
            } catch (IOException e) {
              throw new UncheckedIOException("cannot run " + shown + " in " + home, e);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("interrupted while running " + shown, e);
            }
          }
        }

3. Add `$TJ/VarkaRowFilesSuite.java`: the same header, an empty line, then:

        package org.apache.spark.sql.catalyst.expressions.codegen.varka;

        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.assertThrows;
        import static org.junit.jupiter.api.Assertions.assertTrue;

        import java.nio.file.Files;
        import java.nio.file.Path;

        import org.junit.jupiter.api.DisplayName;
        import org.junit.jupiter.api.Test;

        /** {@link VarkaRowFiles} against the tool it runs, {@code dev/varka_rows.py} (VARKA-653). */
        @VarkaSuite
        public final class VarkaRowFilesSuite {

          @Test
          @DisplayName("the rows tool's own cases pass in the test JVM's environment")
          void selfTest() {
            String printed = VarkaRowFiles.run("--self-test");
            assertTrue(printed.strip().endsWith("self-test: ok"), printed);
          }

          @Test
          @DisplayName("a view's path is the file under sql/varka, and it is there")
          void view() {
            Path coverage = VarkaRowFiles.view("coverage.json");
            assertEquals(Path.of(VarkaRowFiles.home(), "sql", "varka", "coverage.json"), coverage);
            assertTrue(Files.exists(coverage), coverage + " is missing");
          }

          @Test
          @DisplayName("a failing run of the tool is an exception that carries what it printed")
          void failure() {
            var e = assertThrows(IllegalStateException.class, () -> VarkaRowFiles.run("--no-such-mode"));
            assertTrue(e.getMessage().contains(" exited 2 in "), e.getMessage());
          }
        }

4. In `.github/workflows/build_and_test.yml`, after the two lines of premise 5's step (its
   `- name:` line and its `run:` line), add:

            - name: The row records' tool
              run: python3 dev/varka_rows.py --self-test

   indented as the step above it is (four spaces before `- name:`).
4a. In `.github/workflows/maven_test.yml`, before the line of premise 8b (`      - name: Run
   tests`) and the three comment lines above it, add:

              - name: python3 is on the path, for the test JVM's door (VARKA-653)
                run: python3 --version

   indented as that step is (six spaces before `- name:`). It runs on the three images of
   the Maven workflows; where it fails on one, the step "Install Python 3.12" of the same file
   loses its `if:` line, so that Python is installed for every module, and the lead is told.
4b. In `dev/sparktestsupport/modules.py`, after the line of premise 9, add:

                # The row records' tool, which every test JVM runs (VARKA-653).
                r"dev/varka_rows\.py$",

   So a change confined to the tool runs the catalyst and sql suites, which run it, and not
   the docs job alone.
5. The demonstration into the results file (5.0): its output is part A1's, since no record
   exists yet. This file's section 14.

**The tests.**

| suite | case | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `dev/varka_rows.py --self-test` (Python, the docs job and the suite below) | the doctests of `slug` and `family_slug` | the two texts in the docstrings | the names printed there | a slug rule that changes, which would rename every record |
| | the round trip | three views of three rows in two families, one construction shape, one arm with two rows | each view comes back byte for byte from its records, and `check` finds nothing | a split that drops a key or reorders one |
| | a row file's place | a `.row` and a record for `+i` in a family whose table rows are `abs(i)` and `-i` | the order `abs(i)`, `-i`, `+i`, `i < 3` | a row file sorted among the table's rows by its slug |
| | three planted faults | a record of no row; a row with no bytes record; a record with one more line | `check` names each: `is no row's record`, `lacks the row '+i'`, `is not as a regeneration writes it` | a check that passes a stale tree |
| `VarkaRowFilesSuite` (Java, JUnit 5, catalyst) | "the rows tool's own cases pass in the test JVM's environment" | `run("--self-test")` | the output ends `self-test: ok` | a runner whose test JVM has no `python3` |
| | "a view's path is the file under sql/varka, and it is there" | `view("coverage.json")` | `<home>/sql/varka/coverage.json`, and the file exists | a door that returns another path than the read sites used |
| | "a failing run of the tool is an exception that carries what it printed" | `run("--no-such-mode")` | an `IllegalStateException` whose message holds ` exited 2 in ` | a failure of the tool swallowed, which would leave a suite reading a stale view |
| command 6a | a family title with no letter or digit | a coverage view whose one row has the family `"--"` | the split exits 1 with `rows: the family title '--' has no letter or digit` | a record written to the file system's root |

**The commands.**

    python3 dev/varka_rows.py --self-test | tail -1                       # 1: self-test: ok
    python3 dev/varka_rows.py --views; echo "exit $?"                     # 2: exit 0 and no other line: there are no records yet
    python3 dev/varka_rows.py --check; echo "exit $?"                     # 3: rows: no sql/varka/rows/_coverage.json, then exit 1
    mkdir -p $X/t/sql/varka && cp sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json $X/t/sql/varka/ && for k in coverage bytes audit; do python3 dev/varka_rows.py --root $X/t --split $k; done
                                                                          # 4: three lines "rows: split <kind>: N written, 0 removed, N in all"
    for f in coverage emitted_bytes width_audit; do python3 dev/varka_rows.py --root $X/t --show $(echo $f | sed 's/emitted_bytes/bytes/;s/width_audit/audit/') | cmp - sql/varka/$f.json && echo same; done
                                                                          # 5: same, three times
    python3 dev/varka_rows.py --root $X/t --check; echo "exit $?"         # 6: exit 0
    mkdir -p $X/u/sql/varka && printf '{\n  "expressions" : [ {\n    "sql" : "i",\n    "family" : "--"\n  } ],\n  "catalyst_classes_admitted" : [ ]\n}\n' > $X/u/sql/varka/coverage.json && python3 dev/varka_rows.py --root $X/u --split coverage; echo "exit $?"
                                                                          # 6a: rows: the family title '--' has no letter or digit, and exit 1
    flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaRowFilesSuite'       # 7: Passed: Total 3, Failed 0, Errors 0, Passed 3
    dev/varka_precommit.sh                                                # 8: exits 0
    python3 -m doctest dev/sparktestsupport/modules.py; python3 -c "import sys; sys.path.insert(0, 'dev'); from sparktestsupport import modules as m; print(m.catalyst.contains_file('dev/varka_rows.py'), m.varka_docs.contains_file('dev/varka_rows.py'), m.varka_change_scope(['dev/varka_rows.py']))"   # 8a: no doctest failure; True True scoped
    python3 dev/varka_msg_check.py size                                   # 9: a count under 900

On the plan's commit command 4 printed 968, 869 and 887: 868 rows, their head and 99 admitted
classes; 868 rows and a head; 886 shapes and a head.

**Done when** commands 1 to 7 print what the sheet says and the Build is green: its catalyst
job runs `VarkaRowFilesSuite`, which is the proof that a runner's test JVM finds `python3`.

**The expectations, to tick.**

- [ ] `dev/varka_rows.py` with the five modes of its docstring; `VarkaRowFiles.view`, `.write`,
  `.views` and the package-private `.run` and `.home`; `VarkaRowFilesSuite` with three tests.
- [ ] `git status --short`: `A dev/varka_rows.py`, `A` the two Java files,
  `M .github/workflows/build_and_test.yml`, `M .github/workflows/maven_test.yml`,
  `M dev/sparktestsupport/modules.py`, `M` this file and the results file, and no other line
  (but a workflow that premise 8's table made the builder edit).
- [ ] No list moves: `ls sql/varka/rows` still fails.
- [ ] The workflow file is not Varka-scoped, so this pull request's Build runs Spark's whole
  matrix once; say so in the description.
- [ ] The description asks the lead for one dispatch of the option matrix, of
  `varka-width-audit.yml` and of `build_maven_java21_macos26.yml` on the branch: they run
  catalyst suites on images the Build does not (the aarch64 runner, macOS), and
  `VarkaRowFilesSuite` runs there.

**Not here.** Any read site, any write site, `sql/varka/rows`, `.gitattributes`.

### 5.3 Part B: the records

**B0, the premises.**

    test -x dev/varka_rows.py && test -f $TJ/VarkaRowFiles.java && echo a2    # 1: a2
    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f3,5                # 2: ltz and on_lane, a tab between: row 656's part B is merged
    test -f $M9/census_curated.txt && echo 654                            # 3: 654
    grep -c '^    9: Milestone(9, M8_TYPES + \["ltz"\], \["ltz"\], None),$' dev/varka_census.py   # 3a: 1: row 654's part b is in the script
    git branch -a --list '*varka-653b*'                                   # 4: nothing
    ls sql/varka/rows sql/varka/.gitignore 2>&1 | grep -c "No such file"  # 5: 2
    git grep -c 'getWorkspaceFilePath("sql", "varka", "\(coverage\|emitted_bytes\|width_audit\)\.json")' -- sql/catalyst/src/test sql/core/src/test | awk -F: '{s+=$2} END {print s}'   # 6: 21, or more
    git grep -c 'Path.of(home\(()\)\?, "sql", "varka", "\(coverage\|emitted_bytes\|width_audit\)\.json")' -- sql/catalyst/src/test sql/core/src/test | awk -F: '{s+=$2} END {print s}'   # 7: 5, or more
    git grep -c 'new File(home, "sql/varka/coverage\.json")' -- sql/catalyst/src/test sql/core/src/test | awk -F: '{s+=$2} END {print s}'   # 8: 2, or more
    git grep -n '\(coverage\|emitted_bytes\|width_audit\)\.json"' -- sql/catalyst/src/test sql/core/src/test | grep -v 'getWorkspaceFilePath("sql", "varka", "\|Path.of(home\(()\)\?, "sql", "varka", "\|new File(home, "sql/varka/' | grep -c 'Path\|File\|get('   # 9: 0: no read site in a fourth spelling
    grep -c "Files.writeString(file, rendered)" $S                        # 10: 2
    grep -c "Files.write(path, render().getBytes(StandardCharsets.UTF_8))" $B $W   # 11: 1 and 1
    grep -n '^/coverage.json merge=varka-generated$\|^/emitted_bytes.json merge=varka-generated$\|^/width_audit.json merge=varka-generated$' sql/varka/.gitattributes   # 12: three lines
    grep -n '^REASON_PLACES = TEST_TREES + ("sql/varka/coverage.json", "docs")$' dev/varka_push_check.py   # 13: one line
    grep -c "varka_generated_merge.py --list | tr" dev/varka_regen_generated.sh   # 14: 1: the line that sets files under --all
    grep -c -x -F 'cd "$top"' dev/varka_regen_generated.sh                # 15: 1
    grep -c -F "NONCODE='^sql/varka/plans/|\.md\$|^sql/varka/(coverage|emitted_bytes|width_audit)\.json\$'" dev/varka_ops/pre-pr.sh   # 16: 1
    grep -c "sql/varka/coverage\\\\.json" dev/varka_precommit.sh          # 17: 1, in census_inputs
    grep -c '^    - name: The census and the cast matrix match their inputs$' .github/workflows/build_and_test.yml   # 18: 1
    grep -c 'HEAD -- sql/varka/emitted_bytes.json' .github/workflows/build_and_test.yml   # 19: 2
    grep -c 'r"sql/varka/coverage\\.json\$",' dev/sparktestsupport/modules.py   # 20: 2: the docs' regexes and the catalyst module's
    grep -c '"sql/varka/coverage.json" in files and\|for rel in ("sql/varka/width_audit.json", "sql/varka/emitted_bytes.json"):\|with open(os.path.join(root, "sql", "varka", "width_audit.json"), encoding="utf-8") as f:\|    coverage = load("sql/varka/coverage.json")' dev/varka_generated_merge.py   # 21: 4
    git grep -c 'git diff[^|]*\(emitted_bytes\|coverage\.json\|width_audit\)\|then shows `coverage.json`' -- $M9 | grep -v 'VARKA-65[346]\.md'   # 22: ten files on master `1d68f7d8d89`, step 11's; the counts are the commands' (2 for VARKA-690.md, whose other three lines are status lists; 4 for VARKA-657-PARTS.md)
    python3 dev/varka_generated_merge.py --self-test 2>&1 | grep "FAILED\|^self-test:" | cut -c1-90; python3 dev/varka_push_check.py --self-test 2>&1 | tail -1   # 23: the lines of the table below; then OK
    python3 dev/varka_rows_demo.py | tail -1                              # 24: round 1: 6 file(s) in conflict or stale

| line | it prints | what the builder does |
| :--- | :--- | :--- |
| 2 | `-` and `declined`, or `ltz` and `owed` | stop: "row 656's part B is not merged" |
| 3 | nothing | stop: "row 654's part a is not merged" |
| 3a | `0` | stop: "row 654's part b is not merged"; report `grep -n "Milestone(9" dev/varka_census.py` |
| 6, 7 or 8 | more | no stop: a suite added since the plan reads a file in the same spelling; step 3's command rewrites it too. Name it in the description |
| 9 | above 0 | stop: report the lines. A read site in a spelling this sheet does not rewrite |
| 22 | other files or counts | no stop: step 11's command rewrites every line that matches; name the files and counts in the description, and read each changed line as step 11 says |
| 23, on a machine whose `java` is the JDK that wrote the width audit (the laptop: `python3 -c "import json; print(json.load(open('sql/varka/width_audit.json'))['host']['jdk'])"` prints the version `java -version` prints), in a worktree whose HEAD is `origin/master` | two lines `self-test FAILED: 5: on '<cpu>' the regeneration printed (exit 0): varka regen: skipping the JVM steps`, then `self-test: N case(s), 2 failed` | expected, and no stop: on a tree with nothing changed since master, the regeneration's dry run skips its JVM steps, and case 5 wants to see them. It is master's state before this part and not this part's to fix; note N |
| 23, on any other machine, or once the worktree holds an edit | `self-test: N case(s), 0 failed` | go on; note N |
| 23 | any other `FAILED` line | stop: report it |

**The steps.**

1. **The records.** Keep the three files for step 2's comparison, split them, and stop
   committing them:

        cp sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json $X/
        for k in coverage bytes audit; do python3 dev/varka_rows.py --split $k; done
        git rm -q --cached sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json
        printf '%s\n' '# Assembled from sql/varka/rows by dev/varka_rows.py --views (VARKA-653).' /coverage.json /emitted_bytes.json /width_audit.json > sql/varka/.gitignore
        git add sql/varka/rows sql/varka/.gitignore

2. **The proof that no reader sees a difference.** `rm sql/varka/coverage.json
   sql/varka/emitted_bytes.json sql/varka/width_audit.json`, then `python3 dev/varka_rows.py
   --views`, then command 5 below.
3. **The read sites.** One command rewrites the three spellings; it changes 28 lines in 15
   files on the plan's commit:

        git grep -l '\(coverage\|emitted_bytes\|width_audit\)\.json"' -- sql/catalyst/src/test sql/core/src/test | xargs sed -i -E 's/getWorkspaceFilePath\("sql", "varka", "(coverage|emitted_bytes|width_audit)\.json"\)/VarkaRowFiles.view("\1.json")/g; s/Path\.of\(home(\(\))?, "sql", "varka", "(coverage|emitted_bytes|width_audit)\.json"\)/VarkaRowFiles.view("\2.json")/g; s/new File\(home, "sql\/varka\/coverage\.json"\)/VarkaRowFiles.view("coverage.json").toFile()/g'

   `getWorkspaceFilePath` returns a `java.nio.file.Path`, as `view` does, so no call around a
   site changes. Then add the import of `VarkaRowFiles`, from the package
   `org.apache.spark.sql.catalyst.expressions.codegen.varka`, to each rewritten file that is not
   in that package: on the plan's commit `VarkaFamilyChainSuite.scala` and
   `VarkaFamilySwitchSuite.java` (package `...codegen`), `VarkaCoverageDifferentialSuite.scala`,
   `VarkaFarDaySuite.scala`, `VarkaSparkFuzzSuite.scala` and `VarkaCoverageHarnessSuite.java`
   (sql/core). In a Scala file that already imports names of that package in braces, add
   `VarkaRowFiles` among them in alphabetical place; otherwise add the import line where the
   file's import order puts it, which `dev/lint-scala` and `dev/lint-java` check.
4. **The write sites.** Three lines:

| file | old line | new line |
| :--- | :--- | :--- |
| `$S`, in the test "sql/varka/coverage.json carries the same rows in machine-readable form" (the first of the two lines of premise 10) | `      Files.writeString(file, rendered)` | `      VarkaRowFiles.write("coverage", rendered)` |
| `$B` | `      Files.write(path, render().getBytes(StandardCharsets.UTF_8))` | `      VarkaRowFiles.write("bytes", render())` |
| `$W` | `      Files.write(path, render().getBytes(StandardCharsets.UTF_8))` | `      VarkaRowFiles.write("audit", render())` |

5. **`sql/varka/.gitattributes`.** Delete the three lines of premise 12: a line of this file
   must match a tracked file (fact 25), and the views are not tracked. The size check does not
   count the three files' removal: it judges a file the base has by the base's attributes
   (`counted_lines` in `dev/varka_msg_check.py`). In their place add `/rows/**/*.json
   linguist-generated`. The file's first three comment lines become:

        # Generated files merged per key by dev/varka_generated_merge.py (VARKA-546): the type
        # registry's two tables and the census's files. The driver itself is set in the clone's
        # config by dev/varka_precommit.sh --install-hook; without it git merges these as text.
        # The records under rows/ need no merge: a row's are its own files (VARKA-653).

6. **The Python tools and the shell scripts.**

| file | change |
| :--- | :--- |
| `dev/varka_census.py` | after `import sys` add an empty line and `import varka_rows`; make `    varka_rows.views(ROOT)` the first statement of `main()`. `ruff`'s import order puts a module of `dev/` in a block of its own after the standard library's, which is this |
| `dev/varka_push_check.py` | the line of premise 13 becomes `REASON_PLACES = TEST_TREES + ("sql/varka/rows", "docs")`. No other edit: its rule R3 works from the driver's list, which loses the three files, so the push check no longer holds a record to what the regeneration wrote; the Build does (4.3). Its own tests pass unchanged (fact 26) |
| `dev/varka_regen_inputs.py` | no edit. `committed_jdk` reads `sql/varka/width_audit.json`, the view, and its one caller, the regeneration script, writes the views first (below). A changed record is a path it does not know, so it answers "run the JVM steps", which is the safe answer |
| `dev/varka_bytes_moved.py` | after `import sys` add an empty line and `import varka_rows`. `committed(rev)`'s body becomes the lines below. In `main`, the two lines `with open(os.path.join(ROOT, FILE), encoding="utf-8") as f:` and `now = json.load(f)` become `varka_rows.views(ROOT)` followed by the same two lines |
| `dev/varka_regen_generated.sh` | after the line of premise 15 add the three lines below; after the line of premise 14 add `  files="$files sql/varka/coverage.json sql/varka/width_audit.json sql/varka/emitted_bytes.json"`. The line that reads the host from `sql/varka/width_audit.json` stays: the push check's tests run this script over a fixture that holds that file alone (fact 26) |
| `dev/varka_precommit.sh` | in `census_inputs`, `sql/varka/coverage\.json` becomes `sql/varka/rows/.*`; after the `install_hook pre-push` call (two lines) add the hooks' three lines below |
| `dev/varka_task_new.sh` | after the line `  (cd "$dir" && dev/varka_precommit.sh --install-hook > /dev/null)` add `  (cd "$dir" && python3 dev/varka_rows.py --views > /dev/null)` |
| `dev/varka_ops/pre-pr.sh` | the line of premise 16 becomes `NONCODE='^sql/varka/plans/\|\.md$\|^sql/varka/rows/.*\.json$'` (in the file a plain `\|` is `|`): a record is a generated file, as the three were, and a `.row` file stays code. The comment line 36, `#      sql/varka/coverage.json reruns the suites drawn from it (below)`, keeps its words: the suites still read that file. The lead's own copy of the script, `~/varka-ops/pre-pr.sh`, differs from the repository's: the lead makes the same one-line change there, and the description of this pull request says so |

   The regeneration script's three lines, after `cd "$top"`:

        # The three JSON files are views of the records under sql/varka/rows (VARKA-653), which git
        # ignores: they are written here, before anything below reads one.
        if [ -f dev/varka_rows.py ]; then python3 dev/varka_rows.py --views > /dev/null; fi

   `committed(rev)` of `dev/varka_bytes_moved.py`:

            """The bytes view at `rev`: assembled from its records, or the file a revision
            before VARKA-653 committed."""
            text = varka_rows.assemble("bytes", varka_rows.Revision(ROOT, rev))
            if text is not None:
                return json.loads(text)
            out = subprocess.run(["git", "show", f"{rev}:{FILE}"], cwd=ROOT, capture_output=True, text=True)

   followed by the function's last three lines as they are (`if out.returncode != 0:`, its
   `sys.exit` and `return json.loads(out.stdout)`). Wrap the `subprocess.run` line as `ruff
   format` does.

   The hooks of `dev/varka_precommit.sh`:

          # The views of the row records (VARKA-653), which git ignores, follow what is checked out.
          for hook in post-checkout post-merge post-rewrite; do
            install_hook "$hook" '[ -f "$top/dev/varka_rows.py" ] || exit 0; exec python3 "$top/dev/varka_rows.py" --views > /dev/null'
          done

7. **`dev/varka_generated_merge.py`.** Four edits of code and one of its docstring; without
   the first three its self-test, which the docs job runs, fails (fact 24).

| where | old | new |
| :--- | :--- | :--- |
| `self_test`, case 22 | `        "sql/varka/coverage.json" in files and f"{DOCS} merge=varka-generated" in installer,` | the same line with `"sql/varka/types.tsv"` for `"sql/varka/coverage.json"` |
| `review_cases`, case 4 | `    for rel in ("sql/varka/width_audit.json", "sql/varka/emitted_bytes.json"):` | `    for rel in ("sql/varka/rows/_audit.json", "sql/varka/rows/_bytes.json"):` |
| `review_cases`, case 5 | `    with open(os.path.join(root, "sql", "varka", "width_audit.json"), encoding="utf-8") as f:` | `    with open(os.path.join(root, "sql", "varka", "rows", "_audit.json"), encoding="utf-8") as f:` |
| `check` | `    coverage = load("sql/varka/coverage.json")` | before it, three lines: `    import varka_rows`, an empty line, `    varka_rows.views(root)`. The import is inside the function because `varka_rows` imports this module |
| the module docstring, after its paragraph that ends "to run only the owed regenerations, in order." | - | an empty line and: `The three JSON files are not committed (VARKA-653): git ignores them, what is committed is` / `one record a coverage row under sql/varka/rows, which needs no merge, and no attributes line` / `sends them here. Their rules stay for a merge of a branch that still holds the files.` |

   The other cases of the self-test that name the three files build their own files in a
   temporary directory, or read the views `check` has just written, and pass unchanged.
8. **`.github/workflows/build_and_test.yml`.** Before the step of premise 18 add:

            - name: The row records assemble into the files the tools read
              run: ./dev/varka_rows.py --views && ./dev/varka_rows.py --check

   In the step "The emitted-bytes oracle's verdict", the two `-- sql/varka/emitted_bytes.json`
   of premise 19 become `-- sql/varka/rows/_bytes.json ':(glob)sql/varka/rows/**/*.bytes.json'`,
   and in the two `echo` lines and the comment above the step `sql/varka/emitted_bytes.json`
   becomes `the bytes records under sql/varka/rows`.
   `.github/workflows/varka-width-audit.yml` needs no edit: it reads and uploads
   `sql/varka/width_audit.json` after `VarkaWidthAuditSuite` ran under `VARKA_AUDIT_REGEN`, and
   the suite's `VarkaRowFiles.write` ends by writing the views, so the file is there.
9. **`dev/sparktestsupport/modules.py`.**

| old | new |
| :--- | :--- |
| in `varka_docs_regexes`: `    r"sql/varka/coverage\.json$",` | `    r"sql/varka/rows/(_coverage\.json\|_admitted/[^/]+\|[^_/][^/]*/[^/.]+\.json)$",` |
| in `varka_docs_check_regexes`: `    r"sql/varka/(width_audit\|emitted_bytes)\.json$",` | `    r"sql/varka/rows/(_(bytes\|audit)\.json\|[^/]+/[^/]+\.(bytes\|audit)\.json)$",` |
| in the catalyst module: the three lines `r"sql/varka/coverage\.json$",`, `r"sql/varka/emitted_bytes\.json$",` and `r"sql/varka/width_audit\.json$",` | one line, `        r"sql/varka/rows/",`; the line `r"sql/varka/emit_cost_audit\.json$",` and part A2's two lines for the tool stay |

   In the table a `\|` stands for `|`. In the comments beside each, `coverage.json`,
   `emitted_bytes.json` and `width_audit.json` become "the coverage records", "the bytes
   records" and "the audit records under `sql/varka/rows`". The two doctests that name
   `sql/varka/emitted_bytes.json` stay: the path is still Varka-scoped. The three kinds keep
   the scope they had (command 14): a coverage record is a census input as `coverage.json`
   was, a bytes or an audit record runs the docs job's check and the catalyst suites, and a
   `.row` file is catalyst's.
10. **The documents.** In `sql/varka/AGENTS.md`, the paragraph that starts "The generated
    files every type row rewrites" is replaced by:

        The three generated files every type row rewrites (`coverage.json`, `emitted_bytes.json`,
        `width_audit.json`) are not committed: git ignores them. What is committed is one record a
        coverage row under `sql/varka/rows/` (`VARKA-653.md` 4.2), and `python3 dev/varka_rows.py
        --views` assembles the three files from the records; the test JVM, the Python tools and
        the git hooks run it, so a suite and a grep read the files as before. A regeneration
        (`dev/varka_regen_generated.sh --all`) writes a row's records and no other row's, so two
        branches that each add a row merge with nothing to resolve in them. After `git reset
        --hard`, and in a clone with no hooks, run `--views` by hand. To see whether a change
        moved a record, ask `git add -N sql/varka/rows && git diff --exit-code -- sql/varka/rows`:
        a `git diff` of one of the three files answers nothing, since git does not track them. The push check does not
        hold a record to what the regeneration wrote; the Build's suites do. `types.tsv`,
        `fuzz/types.tsv` and the census files keep row 546's merge driver
        (`dev/varka_generated_merge.py`).

    In `sql/varka/ADDING_A_TYPE.md` step 11, the sentence "and check that only the new rows
    moved" / "in `coverage.json`, `docs/sql-varka.md`, `width_audit.json` and
    `emitted_bytes.json`." becomes "and check with `git status --short sql/varka/rows` that
    only the new rows' record files are new, beside `docs/sql-varka.md`."; and its last
    paragraph ("When master moves under the branch, merge it: ...") becomes "When master moves
    under the branch, merge it: the records of another row are other files, so the merge has
    nothing to resolve in them." In `sql/varka/skills/working-in-this-repo.md`, "It reads the
    committed `coverage.json`." becomes "It reads `coverage.json`, the view of the committed
    coverage records.". No other document is edited: `SKILLS.md`, `CONTRIBUTING.md`,
    `WALKTHROUGH.md`, `HARDWARE.md` and the other lesson files name the three files as what a
    suite reads or pins, which stays true of the views, and the plans are records.
11. **The other plans' commands that would answer nothing.** Sheets of other plans of this
    folder end with a `git diff` over the three JSON files, and two name the three files in an
    expected `git status --short`. Once git ignores the files, such a `git diff` exits 0
    whatever moved. These edits are this part's, made in the pull request that changes the
    layout, and not the plan's own pull request's: until this part merges the commands are
    right as they stand, and a builder of one of those plans who runs them before it must find
    them unchanged. Write this file as `$X/plans.sed`:

        /git diff/ {
          s#sql/varka/(coverage|emitted_bytes|width_audit)\.json( sql/varka/(coverage|emitted_bytes|width_audit)\.json)*#sql/varka/rows#g
          s#git diff (--exit-code|--stat)(.*sql/varka/rows)#git add -N sql/varka/rows \&\& git diff \1\2#
        }
        s#then shows `coverage.json`, `emitted_bytes.json`, `width_audit.json`, the census file#then shows the rows' records under `sql/varka/rows`, the census file#
        s#^`sql/varka/coverage.json`, `emitted_bytes.json`, `width_audit.json`, `\$M9/casts_fused.tsv`,$#the rows' records under `sql/varka/rows`, `$M9/casts_fused.tsv`,#
        s#^status --short` shows, beside the steps' files, `sql/varka/coverage.json`,$#status --short` shows, beside the steps' files, the rows' records under `sql/varka/rows`,#
        s#^`emitted_bytes.json`, `width_audit.json`, `\$M9/census_curated.txt`, `\$M9/census.tsv` and$#`$M9/census_curated.txt`, `$M9/census.tsv` and#

    and run it over every plan of the folder that holds such a line, but those of rows 653,
    654 and 656 (the first is this file; the other two are built, by premises 2 to 3a, and
    their commands were run as written):

        git grep -l 'git diff[^|]*\(emitted_bytes\|coverage\.json\|width_audit\)\|then shows `coverage.json`' -- $M9 | grep -v 'VARKA-65[346]\.md' | xargs sed -i -E -f $X/plans.sed

    The first rule turns the three paths, in any order and any number, into `sql/varka/rows`
    on a line that holds `git diff`, and puts `git add -N sql/varka/rows &&` before the
    command: `git add -N` makes git show a record that a regeneration added and that is not
    yet tracked, which a plain `git diff` would not. Other paths of the command stay: the
    second form of `VARKA-690.md`, with `sql/varka/fuzz`, keeps `sql/varka/fuzz`. A command
    that named one of the three files now covers every record, which is stricter and asks the
    same question. The last four rules rewrite the two status lists. On master `1d68f7d8d89`
    the command changes 28 lines in ten files:

    | file | lines changed | which |
    | :--- | ---: | :--- |
    | `VARKA-657-PARTS.md` | 4 | three `git diff` commands; the status list of its regeneration |
    | `VARKA-678.md` | 3 | three commands, each keeping `sql/varka/operator_census.json sql/varka/types.tsv` |
    | `VARKA-682.md` | 3 | three commands |
    | `VARKA-683.md` | 1 | one command, keeping `sql/varka/operator_census.json` |
    | `VARKA-684.md` | 3 | three commands |
    | `VARKA-685.md` | 1 | one command |
    | `VARKA-686.md` | 3 | three commands |
    | `VARKA-687.md` | 1 | the `git diff --stat ... \| wc -l` command |
    | `VARKA-688.md` | 4 | four commands |
    | `VARKA-690.md` | 5 | two commands, the second keeping `sql/varka/fuzz`; three lines of its two status lists |

    `VARKA-655.md`, `VARKA-660.md` and `VARKA-679.md` hold no such line. Then add to each
    changed file, after its first italic paragraph, the line `*<date>: the three generated JSON
    files are views of the records under `sql/varka/rows` (`VARKA-653.md` 4.2); a sheet's check
    of them is `git add -N sql/varka/rows && git diff --exit-code -- sql/varka/rows`.*`, wrapped
    within 100 columns. Command 15 checks the result.
12. The demonstration into the results file; this file's section 14.

**The tests.**

| check | case | expected | the failure only it catches |
| :--- | :--- | :--- | :--- |
| command 5, the proof of "the same output" | each view against the file it replaces | `same` three times | a record format that loses a byte: every reader's input would differ |
| command 6, on the laptop | `VarkaCoverageSuite`, `VarkaEmittedBytesSuite`, `VarkaWidthAuditSuite` with no regeneration variable | every test passes: each suite renders its file and compares it with the view | a read site left on a path that no longer exists (the suite fails with `NoSuchFileException`) |
| command 7 | a regeneration with nothing to change | `git status --porcelain -- sql/varka/rows` prints nothing | a write site that still writes the ignored file, so that the records never move |
| command 8, the planted fault | one hash of one bytes record changed by hand | `VarkaEmittedBytesSuite`'s defaults test fails and names the row and the method; `git checkout` of the record restores it | a view assembled from somewhere else than the records |
| command 9, the planted fault | one audit record deleted | `dev/varka_rows.py --check` exits 1 with `sql/varka/width_audit.json at 128 lacks the row` and the regeneration command | a missing record that only the laptop's audit would see |
| command 10 | the demonstration | 4.10's line B | a record layout in which two siblings still share a file |
| command 13 | the existing tools' own tests | each passes as before the part | a self-test of a tool that reads one of the three files or their attributes |
| command 14 | the scope of a record, by kind | `docs True True`, `scoped True True`, `scoped False True` | a record whose change runs no catalyst suite |

**The commands.**

    python3 dev/varka_rows.py --check; echo "exit $?"                     # 1: exit 0
    git status --short | grep -v '^A  sql/varka/rows/' | sort             # 2: the list below
    git ls-files sql/varka/rows | wc -l                                   # 3: the sum of step 1's three counts (2724 on the plan's commit, more with row 656's rows)
    git grep -c 'VarkaRowFiles.view(' -- sql/catalyst/src/test sql/core/src/test | awk -F: '{s+=$2} END {print s}'   # 4: premise 6 + premise 7 + premise 8, and one more: VarkaRowFilesSuite's
    for f in coverage.json emitted_bytes.json width_audit.json; do cmp $X/$f sql/varka/$f && echo same; done   # 5: same, three times
    flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaCoverageSuite *VarkaEmittedBytesSuite *VarkaWidthAuditSuite *VarkaRowFilesSuite *VarkaFamilySwitchSuite'   # 6: Tests: succeeded N, failed 0, and the JUnit line
    dev/varka_regen_generated.sh --all --full && git status --porcelain -- sql/varka/rows docs | grep -v '^A '   # 7: the script's "varka regen: done" line, then nothing
    f=$(git ls-files 'sql/varka/rows/integer-arithmetic/abs-i-*.bytes.json'); sed -i '0,/"<class>" : "/s//"<class>" : "0/' $f && flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite -- -z "at the defaults"'; git checkout $f
                                                                          # 8: the test fails, and its message holds "lanes 4: abs(i) method <class>"
    f=$(git ls-files 'sql/varka/rows/integer-arithmetic/abs-i-*.audit.json'); mv $f $X/ && python3 dev/varka_rows.py --check; echo "exit $?"; mv $X/$(basename $f) $f
                                                                          # 9: a line with "at 128 lacks the row 'abs(i)'", and exit 1
    python3 dev/varka_rows_demo.py | grep -v ' then '                     # 10: 4.10's line B: round 1 4 files, round 2 6
    flock "$LOCK" build/sbt 'sql/testOnly *VarkaCoverageHarnessSuite *VarkaFarDaySuite'   # 11: both pass
    python3 dev/varka_bytes_moved.py origin/master; echo "exit $?"        # 12: nothing, and exit 0
    python3 dev/varka_generated_merge.py --self-test 2>&1 | grep "FAILED\|^self-test:"; python3 dev/varka_generated_merge.py --check; echo "exit $?"; python3 dev/varka_push_check.py --self-test 2>&1 | tail -1; python3 dev/varka_regen_inputs.py --selftest | tail -1; dev/varka_precommit.sh --selftest | tail -1; python3 -m doctest dev/sparktestsupport/modules.py
                                                                          # 13: self-test: N case(s), 0 failed, with premise 23's N and no FAILED line, on every machine: the part's edits are in the tree, so the dry run of case 5 runs its JVM steps; exit 0; OK; varka_regen_inputs: self-test passed; varka_precommit selftest: ok; nothing
    python3 -c "import sys; sys.path.insert(0, 'dev'); from sparktestsupport import modules as m
    for p in ('sql/varka/rows/integral-casts/x-1.json', 'sql/varka/rows/integral-casts/x-1.bytes.json', 'sql/varka/rows/a/b.row'): print(m.varka_change_scope([p]), m.varka_docs.contains_file(p), m.catalyst.contains_file(p))"
                                                                          # 14: docs True True, scoped True True, scoped False True
    git grep -c 'git diff[^|]*\(emitted_bytes\|coverage\.json\|width_audit\)\|then shows `coverage.json`' -- $M9 | grep -v 'VARKA-65[346]\.md'; git diff --stat -- $M9 | grep -c 'VARKA-6[5-9][0-9]\(-PARTS\)\?\.md'; git grep -c 'git add -N sql/varka/rows && git diff' -- $M9 | grep -v 'VARKA-653'   # 15: nothing; the number of files of premise 22 and this file; one line a file of premise 22, each with its count of commands
    python3 dev/varka_msg_check.py size                                   # 16: a count under 450

Command 2's list, with `D ` for the three files step 1 removed:

     M .github/workflows/build_and_test.yml
     M dev/sparktestsupport/modules.py
     M dev/varka_bytes_moved.py
     M dev/varka_census.py
     M dev/varka_generated_merge.py
     M dev/varka_ops/pre-pr.sh
     M dev/varka_precommit.sh
     M dev/varka_push_check.py
     M dev/varka_regen_generated.sh
     M dev/varka_task_new.sh
     M sql/varka/.gitattributes
     M sql/varka/ADDING_A_TYPE.md
     M sql/varka/AGENTS.md
     M sql/varka/plans/m9/VARKA-653.md
     M sql/varka/plans/m9/VARKA-657-PARTS.md
     M sql/varka/plans/m9/VARKA-678.md
     M sql/varka/plans/m9/VARKA-682.md
     M sql/varka/plans/m9/VARKA-683.md
     M sql/varka/plans/m9/VARKA-684.md
     M sql/varka/plans/m9/VARKA-685.md
     M sql/varka/plans/m9/VARKA-686.md
     M sql/varka/plans/m9/VARKA-687.md
     M sql/varka/plans/m9/VARKA-688.md
     M sql/varka/plans/m9/VARKA-690.md
     M sql/varka/plans/m9/results/VARKA-653-demo.txt
     M sql/varka/skills/working-in-this-repo.md
    A  sql/varka/.gitignore
    D  sql/varka/coverage.json
    D  sql/varka/emitted_bytes.json
    D  sql/varka/width_audit.json

and one ` M` line for each test source that steps 3 and 4 rewrote: the fifteen files of fact
11 on the plan's commit, among which the three suites of step 4 are.

**Done when** commands 1 to 15 print what the sheet says, the Build is green, and the option
matrix dispatched on the branch is green.

**The expectations, to tick.**

- [ ] `sql/varka/rows` holds every row's three records, the three heads, `_admitted` and
  `_construction`; `git ls-files sql/varka/coverage.json` prints nothing.
- [ ] Every view equals the file it replaces, byte for byte (command 5): no reader's input
  moved, so no census cell, no emitted byte, no docs block and no benchmark moved.
- [ ] `dev/varka_generated_merge.py --list` no longer names the three files.
- [ ] A branch made before this part crosses it by merging master and then running
  `dev/varka_regen_generated.sh --all`: git reports the three files as deleted by master and
  modified by the branch; the builder takes master's side (`git rm` the three) and the
  regeneration writes the branch's rows as records. Say this in the pull request's
  description, for the owners of open branches.
- [ ] The description names the lead's two changes outside the pull request: the one line of
  `~/varka-ops/pre-pr.sh`, and `dev/varka_precommit.sh --install-hook` run once in the main
  checkout, which installs the three hooks for every worktree.

**Not here.** `armDigest` (part C); the docs blocks (part D); `VarkaCoverageSuite`'s table
(part E); `skips.tsv`; any census file.
### 5.4 Part C: one digest a row for each option arm

**B0, the premises.**

    git ls-files sql/varka/rows/_bytes.json | wc -l                       # 1: 1 (part B merged)
    git branch -a --list '*varka-653c*'                                   # 2: nothing
    grep -c "    sha((rowLines ++ shapeLines).mkString)" $B               # 3: 1
    grep -c "      times: GroupTimes = new GroupTimes): String = {" $B    # 4: 1
    grep -c "      digest: (String, VarkaEmitOptions => VarkaEmitOptions, Int) => String): String = {" $B   # 5: 1
    grep -c '      "option_arms" -> armHashes)' $B                        # 6: 1
    grep -c "  private def committedDigest(doc: JsonNode, name: String, lanes: Int): String =" $B   # 7: 1
    grep -c '"defaults: one digest over every coverage row and int, long and mixed shape at each " +' $B   # 8: 1
    grep -c "option_arm_rows" $B dev/varka_rows.py                        # 9: 0 in the suite, above 0 in the tool
    python3 dev/varka_rows_demo.py | tail -1                              # 10: round 1: 4 file(s) in conflict or stale
    python3 -c "import json;print(len(json.load(open('sql/varka/rows/_bytes.json'))['option_arms']))"   # 11: a count; note it as A (24 on the plan's commit)

**The steps.** All in `$B` but the last two. First keep the bytes view for command 5:
`python3 dev/varka_rows.py --views && cp sql/varka/emitted_bytes.json $X/bytes-before.json`.

1. Before the comment that starts "One hash over every shape the oracle holds", add:

          /**
           * An arm's digests at one width: one over the three fuzz sequences, and one a coverage row
           * pinned there, by the row's SQL in the table's order (VARKA-653).
           */
          private case class ArmDigest(fuzz: String, rows: Seq[(String, String)])

2. `armDigest`. Its comment's two lines become:

           * The digests of every shape the oracle holds at `lanes` (`pinsAt`), emitted under `arm`: one
           * over the fuzz shapes and one a coverage row, with each shape group's time added to `times`.

   The line of premise 4 ends `): ArmDigest = {`. The line of premise 3 becomes two:

            ArmDigest(sha(shapeLines.mkString),
              rows.map(_.sql).zip(rowLines).collect { case (sql, l) if l.nonEmpty => sql -> sha(l) })

3. `document`. The line of premise 5 ends `=> ArmDigest): String = {`. The five lines from
   `    val armHashes = ordered(digests.map(_._1).distinct.map { name =>` to its closing
   `    }: _*)` become:

            // Each arm's digests once: `digest` emits every shape.
            val computed = digests.map { case (name, arm, w) => (name, w) -> digest(name, arm, w) }.toMap
            def perArm(value: ArmDigest => Any): java.util.LinkedHashMap[String, Any] =
              ordered(digests.map(_._1).distinct.map { name =>
                name -> ordered(digests.filter(_._1 == name).map { case (_, _, w) =>
                  w.toString -> value(computed((name, w)))
                }: _*)
              }: _*)
            val armHashes = perArm(_.fuzz)
            val armRows = perArm(d => ordered(d.rows: _*))

   The line of premise 6 becomes `      "option_arms" -> armHashes,` and under it
   `      "option_arm_rows" -> armRows)`. In the description, the line that ends "`option_arms`
   covers emissions beyond the " +`, the line of premise 8 and the line `"width per value of
   spark.sql.codegen.varka.emit.useAVX, an emit option a " +` become four:

                "is a change in what an emitted method does. `option_arms` and `option_arm_rows` " +
                "cover emissions beyond the defaults: one digest over every int, long and mixed " +
                "shape and one a coverage row, at each width, per value of " +
                "spark.sql.codegen.varka.emit.useAVX, an emit option a " +

4. `committedDigest`, with its comment, becomes:

          /** The committed digests of arm `name` at `lanes`, [[notCommitted]] where the file has none. */
          private def committedDigest(doc: JsonNode, name: String, lanes: Int): ArmDigest = {
            val fuzz = committedAt(doc, "option_arms", name, lanes.toString) match {
              case node: JsonNode => node.asText()
              case other => other.toString
            }
            val rows = committedAt(doc, "option_arm_rows", name, lanes.toString) match {
              case node: JsonNode => node.fields().asScala.map(e => e.getKey -> e.getValue.asText()).toSeq
              case _ => Seq(notCommitted -> notCommitted)
            }
            ArmDigest(fuzz, rows)
          }

5. `movedBetween`. The three comment lines above `    val bArms = before.get("option_arms")`
   become:

            // The option arms' fuzz digests: one per arm and width over the three fuzz sequences, so
            // what moved is the arm's name; the fuzz blocks above say which shapes only for the
            // defaults (VARKA-306).

   After the block that ends that walk (the `    }` before `    if (moved.result().isEmpty) {`)
   add:

            // An arm's digest of one coverage row (VARKA-653): what moved is the row and the arm.
            val bRows = before.get("option_arm_rows")
            val aRows = after.get("option_arm_rows")
            (keysOf(bRows) ++ keysOf(aRows)).foreach { arm =>
              val (b, a) = (Option(bRows).map(_.get(arm)).orNull, Option(aRows).map(_.get(arm)).orNull)
              (keysOf(b) ++ keysOf(a)).foreach { lanes =>
                val (was, now) = (Option(b).map(_.get(lanes)).orNull, Option(a).map(_.get(lanes)).orNull)
                (keysOf(was) ++ keysOf(now)).foreach { row =>
                  if (hashOf(was, row) != hashOf(now, row)) {
                    moved += s"lanes $lanes: row $row: option arm $arm"
                  }
                }
              }
            }

6. The planted-fault test "one coverage method's hash and one fuzz block changed in the pinned
   file are each named" gains a third plant. Its name becomes "one coverage method's hash, one
   fuzz block and one row's arm digest changed in the pinned file are each named". After the
   two lines that set fuzz block 3, add:

            val armRows = planted.get("option_arm_rows")
            val arm = armRows.fieldNames().next()
            val armLanes = armRows.get(arm).fieldNames().next()
            val armRow = armRows.get(arm).get(armLanes).fieldNames().next()
            armRows.get(arm).get(armLanes).asInstanceOf[ObjectNode].put(armRow, "0" * 64)

   and the expected `Seq(...)` gains a third element, `s"lanes $armLanes: row $armRow: option
   arm $arm"`, on a line of its own.
7. Regenerate: `VARKA_BYTES_REGEN=true flock "$LOCK" build/sbt 'catalyst/testOnly
   *VarkaEmittedBytesSuite'`. It writes `_bytes.json` and every row's bytes record.
8. The texts the change makes false:

| file | old | new |
| :--- | :--- | :--- |
| `dev/varka_bytes_moved.py`, the docstring's two lines "for every existing key, but `option_arms`, a digest over every shape that moves with any new" / "coverage row (pass `--ignore option_arms`)." | as quoted | "for every existing key: a new row adds its own keys under `lanes` and `option_arm_rows`, and" / "moves no other row's (VARKA-653)." |
| the same file, the usage line `  dev/varka_bytes_moved.py origin/master --ignore option_arms` | as quoted | `  dev/varka_bytes_moved.py origin/master` |
| `sql/varka/ADDING_A_TYPE.md` step 9 | "`python3 dev/varka_bytes_moved.py origin/master --ignore option_arms`, which must print nothing:" and "a new coverage row moves `option_arms` and nothing else of the existing keys, and a new sequence" | "`python3 dev/varka_bytes_moved.py origin/master`, which must print nothing:" and "a new coverage row adds its own keys and moves no existing one, and a new sequence" |
| `VarkaEmitOptions.java`, the class comment's line that holds "{@code option_arms}, and the test-only ones are covered by the defaults, with" | `{@code option_arms}` | `{@code option_arms} and {@code option_arm_rows}` (wrap the comment within 100 columns) |

9. The demonstration into the results file; this file's section 14. What this part gives
   row 547 is in 4.4; that row's status and its plan are not edited here.
10. **This part is not merged alone.** When its Build and its option matrix are green, the
    builder says so and does not ask for the merge. Part D is then built on this part's
    branch (`git worktree add -b varka-653d-docs-blocks ../varka-653d-docs-blocks
    varka-653c-arm-rows`), its pull request opened against master with this part's named as
    the one before it. When part D's Build is green too, the lead merges this part, then
    merges master into part D's branch if anything else merged meanwhile, and merges part D,
    with no pull request that adds a coverage row merged between the two. The description of
    this part's pull request states this rule in its first paragraph. The reason is risk 6.

**The tests.**

| suite | case | inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `VarkaEmittedBytesSuite` | the defaults' pin and the six arm parts, unchanged in text | the regenerated records | pass | an arm's row digest that is not what the arm emits |
| | the planted fault, step 6 | one method hash, one fuzz block, one row's arm digest set to 64 zeros in memory | three lines, the third `lanes <n>: row <sql>: option arm <arm>` | a walker that does not read `option_arm_rows`: a moved arm would print "no value differs" |
| | "an arm the option matrix runs in another's place emits that arm's bytes", unchanged in text | `armDigest` of two arms, compared | equal, as before: `ArmDigest` is a case class, so `===` compares both parts | a split that left the coverage rows out of the comparison |
| `dev/varka_rows.py --self-test` | the round trip with `option_arm_rows` (part A2's case) | an arm with two rows of three | byte for byte | a split that drops a row's arm |
| command 5, the proof of what did not change | the bytes view before the regeneration against the view after it | the `lanes` sections, the skipped rows and the arms' names and widths are equal; only `option_arms`' values and the new `option_arm_rows` differ | a regeneration that moved a default digest: the part must change the arms alone |
| command 5a | a row added in a scratch copy of the bytes view | the model row's entries copied under a new key | `_bytes.json` is unchanged by the split | an aggregate left in the head |

**The commands.**

    VARKA_BYTES_REGEN=true flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite'   # 1: passes
    git status --short | grep -v 'rows/.*\.bytes\.json$'                  # 2: the list below
    python3 -c "import json;d=json.load(open('sql/varka/emitted_bytes.json'));print(len(d['option_arms']),len(d['option_arm_rows']),all(len(d['lanes'][w]['coverage'])>=len(r) for a in d['option_arm_rows'].values() for w,r in a.items() if w in d['lanes']))"   # 3: A A True
    flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite'   # 4: passes, with the JDK and CPU of the Build to follow
    python3 -c "import json;a=json.load(open('$X/bytes-before.json'));b=json.load(open('sql/varka/emitted_bytes.json'));print(a['lanes']==b['lanes'], a['coverage_rows_skipped']==b['coverage_rows_skipped'], {k:list(v) for k,v in a['option_arms'].items()}=={k:list(v) for k,v in b['option_arms'].items()}, sorted(set(b)-set(a)))"
                                                                          # 5: True True True ['option_arm_rows']
    python3 - <<'PY'                                                      # 5a: head unchanged: True
    import json, sys
    sys.path.insert(0, "dev")
    import varka_rows
    view = json.load(open("sql/varka/emitted_bytes.json"))
    for section in view["lanes"].values():
        section["coverage"]["abs(i - 7)"] = section["coverage"]["abs(i)"]
    for arm in view["option_arm_rows"].values():
        for rows in arm.values():
            if "abs(i)" in rows:
                rows["abs(i - 7)"] = rows["abs(i)"]
    family = {**varka_rows.family_of(open("sql/varka/coverage.json").read()), "abs(i - 7)": "integer-arithmetic"}
    out = varka_rows.split_keyed("bytes", varka_rows.dump(view), family)
    print("head unchanged:", out["_bytes.json"] == open("sql/varka/rows/_bytes.json").read())
    PY
    python3 dev/varka_rows_demo.py | grep -v ' then '                     # 6: 4.10's line C: round 1 3 files, round 2 5
    python3 dev/varka_rows.py --check; echo "exit $?"                     # 7: exit 0
    python3 dev/varka_msg_check.py size                                   # 8: a count under 230

Command 2's list: ` M` for `$B`, `dev/varka_bytes_moved.py`, `sql/varka/ADDING_A_TYPE.md`,
`VarkaEmitOptions.java`, `sql/varka/rows/_bytes.json`, this file and the results file. Every
row's bytes record is modified too, which the `grep -v` hides; no coverage record and no audit
record is.

**Done when** the commands print what the sheet says; the Build's catalyst job passes the
suite on its own CPU; the option matrix on the branch is green.

**The expectations, to tick.**

- [ ] `option_arms` holds A arms, each width one digest over the fuzz sequences;
  `option_arm_rows` holds the same arms and widths, each a map from a row's SQL to a digest.
- [ ] Each bytes record has `"arms"`; `_bytes.json` changes in its `option_arms` values, once.
- [ ] `lanes` is value for value what it was (command 5). This part changes an output on
  purpose (section 1): `option_arms`' values, once, and the new `option_arm_rows`.
- [ ] Prediction 2 of section 7 is scored from command 1's time.
- [ ] The description's first paragraph says: do not merge this part before part D's pull
  request is green; then merge the two one after the other, with no pull request that adds a
  coverage row between them (step 10, risk 6).

**Not here.** Which arms are pinned; the fuzz sections; `coverage.json`; the docs; row 547's
status and plan.

### 5.5 Part D: three blocks of the docs page

**B0, the premises.**

    grep -c "option_arm_rows" $B                                          # 1: above 0: part C is merged, or this branch is cut from part C's (5.4, step 10)
    git branch -a --list '*varka-653d*'                                   # 2: nothing
    grep -c "    val blocks = Seq(tableMarks -> render(), switchMarks -> renderSwitches()," $S   # 3: 1
    grep -c "      verificationMarks -> verificationCounts())" $S         # 4: 1
    grep -c '  test("docs/sql-varka.md carries the generated coverage table and family switches") {' $S   # 5: 1
    grep -c -F '      logInfo(s"regenerated the coverage table and the family switches in $doc")' $S   # 6: 1
    grep -c -F '      s"# ${refused.size} of ${valueRows.size} value rows refused; family, row, reason:")' $S   # 7: 1
    grep -c "narrowVerdict\|widthAudit\|constructionOf" $S                # 8: a count; every line is inside the definitions step 3 deletes
    grep -c "^def self_test():$" dev/varka_rows.py                        # 9: 1
    grep -c "BEGIN generated coverage table\|BEGIN generated verification counts\|BEGIN generated support table" docs/sql-varka.md   # 10: 3
    grep -c -F '    files[DOCS] = splice(read(DOCS), block)' dev/varka_census.py   # 11: 1
    grep -c -F '        found = stale(files, read)' dev/varka_census.py   # 12: 1
    grep -c -F '    ap.add_argument("--self-test", action="store_true", help="run the doctests and stop")' dev/varka_census.py   # 13: 1
    grep -c "rewrite the census, the cast matrix and the docs' support$" dev/varka_census.py   # 14: 1
    grep -c '^CURRENT = 9$' dev/varka_census.py; grep -c '^    9: Milestone(9, M8_TYPES + \["ltz"\], \["ltz"\], None),$' dev/varka_census.py; grep -c '^        "--milestone",$' dev/varka_census.py   # 14a: 1, 1 and 1: row 654's parts a and b are in the script
    test -f $M9/VARKA-660.md && grep -c '^| 9 | `python3 dev/varka_census.py > /dev/null` |' $M9/VARKA-660.md   # 14b: 1
    grep -c "^# census, which reads them all, rewrites the docs' support table and costs a second; a merge$" dev/varka_regen_generated.sh   # 15: 1
    grep -c '^| `common.sh` |' dev/varka_ops/INDEX.md; grep -c '^## Opening or fixing a pull request$' dev/varka_ops/AGENTS.md   # 16: 1 and 1
    ls dev/varka_ops/render-docs.sh 2>&1 | grep -c "No such file"         # 17: 1
    python3 dev/varka_census.py --check; echo "exit $?"                   # 18: exit 0
    python3 dev/varka_rows_demo.py | tail -1                              # 19: round 1: 3 file(s) in conflict or stale

| line | it prints | what the builder does |
| :--- | :--- | :--- |
| 11, 12 or 13 | `0` | stop: report `sed -n '/^def main/,$p' dev/varka_census.py`. A row after the plan changed the end of `main`; the edit of step 6 is of the six lines from the line of premise 11 |
| 14a | `1`, `1`, `1` | the expected state: row 654 is built, as part B's premises 3 and 3a required. Step 6 is the same in both states of the file: each of its old texts is in the file once with row 654's edits and once without them (the planner applied it to both) |
| 14a | a `0` | no stop, and say so in the description: row 654's edits are not all in the script, which part B's premises should have refused. Step 6 applies as written. Row 654's builder then puts its `--milestone` argument after the `--docs` line, and its `--milestone 8 --summary` usage line after the `--summary` line, as section 11 tells `VARKA-654.md` |
| 14b | no file, or `0` | row 660's plan is not on master, or its step changed: leave it out of step 9's command and name it in the description |

**The steps.**

1. In `dev/varka_rows.py`, before `def self_test():`, add this text, then two empty lines:

        DOCS = "docs/sql-varka.md"
        SWITCH_PREFIX = "spark.sql.codegen.varka.family."
        KINDS = ["refinement", "rendered", "proof", "exhaustive", "owed", "differential"]


        def construction_of(line):
            """The construction a `not supported` line of the width audit is about, in the docs' words."""
            if "op=comp" in line:
                return "compare to mask"
            if "op=blend" in line:
                return "blend"
            if "op=cast" in line:
                return "mask cast"
            if "op=broadcast" in line and "ismask=1" in line:
                return "mask broadcast"
            if "op=test" in line:
                return "mask test"
            if "is_masked_op=1" in line:
                return "masked operation"
            if "ismask=1" in line:
                return "mask logic"
            if "op=load" in line or "op=store" in line:
                return "load or store"
            return "other"


        def distinct(items):
            return list(dict.fromkeys(items))


        def narrow_verdict(narrow, sql):
            """The 128-bit lanes cell of a row: what C2 printed for its kernel at 128 bits. A
            `declined` line is the stronger answer: the emitter has no lowering there, so no kernel ran."""
            if narrow is None or sql not in narrow:
                return "not audited"
            lines = narrow[sql]
            declined = [x[len("declined: ") :] for x in lines if x.startswith("declined: ")]
            refused = [x for x in lines if x.startswith("not supported")]
            if declined:
                return "not emitted: " + ", ".join(distinct(declined))
            if not refused:
                return "vector"
            return "per-lane: " + ", ".join(distinct(construction_of(x) for x in refused))


        def coverage_table(coverage, audit):
            """The docs' coverage table: a section a family, a line a row, from the two views."""
            out = []
            narrow = None
            if audit is not None:
                narrow = audit["widths"].get("128", {})
                out.append(
                    "The *128-bit lanes* column is `sql/varka/width_audit.json`'s census on "
                    f"{audit['host']['cpu']} at a forced 128-bit species: `vector` means C2 refused no "
                    "Vector API call in the row's kernel there; `per-lane` names the constructions it "
                    "had no lowering for, which then run as Java loops over the lanes (VARKA-153). It "
                    "is this x86 JVM's table below its own width: the pool's aarch64 runner, whose "
                    "native species is 128 bits, refuses nothing (`VARKA-153.md` 6).\n\n"
                )
            out.append(
                "The *Switches* column names the family switches that keep the row off Varka, each "
                f"`<name>` the setting `{SWITCH_PREFIX}<name>.enabled` (Configuration): the families "
                "whose nodes the row's kernel holds, as the compiler records them for a failure's "
                "`families:` line. A row with none compiles to a bare column read, which no switch "
                "declines.\n\n"
            )
            families = {}
            for e in coverage["expressions"]:
                families.setdefault(e["family"], []).append(e)

            def cell(text):
                # A cell's own `|` is escaped, or the table reads it as a column break.
                return text.replace("|", "\\|")

            for title, rows in families.items():
                # A family with a math row has the form column (VARKA-345.md 2.10); no other does.
                math = any("math_form" in r for r in rows)
                first = "| Predicate" if rows[0]["form"] == "predicate" else "| Expression"
                out.append(f"#### {title}\n\n")
                out.append(first + " | Switches | Domain | Notes | " + ("Form | " if math else ""))
                out.append("128-bit lanes |\n|---|---|---|---|" + ("---|" if math else "") + "---|\n")
                for r in rows:
                    domain = f"`{cell(r['domain'])}`" if r["domain"] else ""
                    names = [s[len(SWITCH_PREFIX) : -len(".enabled")] for s in r["switches"]]
                    switches = ", ".join(f"`{n}`" for n in names) if names else "none"
                    form = cell(r.get("math_form", "")) + " | " if math else ""
                    out.append(
                        f"| `{cell(r['sql'])}` | {switches} | {domain} | {cell(r['note'])} | {form}"
                        f"{narrow_verdict(narrow, r['sql'])} |\n"
                    )
                out.append("\n")
            return "".join(out)


        def verification_counts(coverage):
            """The docs' verification counts: a row counts once for each type among its operands."""
            counts = {}
            for e in coverage["expressions"]:
                kind = e["verified"].split(":")[0]
                for t in distinct(o.split(":")[1] for o in e["operands"]):
                    by = counts.setdefault(t, {})
                    by[kind] = by.get(kind, 0) + 1
            out = ["| Type | Rows | " + " | ".join(KINDS) + " |\n"]
            out.append("| :--- |" + " ---: |" * (len(KINDS) + 1) + "\n")
            for t in sorted(counts):
                cells = " | ".join(str(counts[t].get(k, 0)) for k in KINDS)
                out.append(f"| `{t}` | {sum(counts[t].values())} | {cells} |\n")
            return "".join(out)


        def docs_blocks(root):
            """The two blocks of the docs page that are rendered from the views, by block name."""
            views(root)
            tree = Disk(root)
            coverage = json.loads(tree.read(VIEWS["coverage"]))
            audit = tree.read(VIEWS["audit"])
            return {
                "coverage table": coverage_table(coverage, json.loads(audit) if audit else None),
                "verification counts": verification_counts(coverage),
            }


        def docs_behind(root):
            """The names of the docs' blocks that are not what the views render, and the page's text
            with each rendered again."""
            text = Disk(root).read(DOCS)
            behind = []
            for name, body in docs_blocks(root).items():
                begin, end = f"<!-- BEGIN generated {name} -->\n", f"<!-- END generated {name} -->"
                at, to = text.index(begin) + len(begin), text.index(end)
                if text[at:to] != body:
                    behind.append(name)
                    text = text[:at] + body + text[to:]
            return behind, text


        def do_docs(root, check_only):
            behind, text = docs_behind(root)
            for name in behind:
                print(f"rows: {DOCS}'s {name} is behind the records")
            if check_only:
                return 1 if behind else 0
            if behind:
                with open(os.path.join(root, DOCS), "w", encoding="utf-8") as f:
                    f.write(text)
            print(f"rows: {DOCS}: {len(behind)} block(s) written")
            return 0


        def docs_notice(root):
            """What --check prints for each block of the docs page that is behind the records. It is
            a notice and never a failure (VARKA-653.md 4.5), and a warning on the run's page in CI."""
            if Disk(root).read(DOCS) is None:
                return
            mark = f"::warning file={DOCS}::" if os.environ.get("GITHUB_ACTIONS") else ""
            for name in docs_behind(root)[0]:
                print(
                    f"{mark}rows: {DOCS}'s {name} is behind the records; the render commit refreshes"
                    " it: dev/varka_ops/render-docs.sh"
                )

   In `main`, before the two lines of `--self-test`, add:

            if argv[1:] in (["--docs"], ["--docs", "--check"]):
                return do_docs(root, len(argv) == 3)

   In the `--check` branch of `main`, between the loop that prints the errors and the
   `return`, add the line `        docs_notice(root)`. The docstring's usage gains, after the
   `--check` line:
   `  dev/varka_rows.py --docs [--check]  # render the docs' coverage table and counts, or compare`
   and, at its end, a paragraph: `The docs page's coverage table and verification counts are
   rendered from the views by --docs, in a commit that does nothing else: a branch that adds a
   row does not write them, since every such branch would write the same lines.`
2. `$S`, the docs test. The two lines of premises 3 and 4 become one; the lines of premises 5
   and 6 lose "coverage table and". The three new lines:

            val blocks = Seq(switchMarks -> renderSwitches())
          test("docs/sql-varka.md carries the generated family switches") {
              logInfo(s"regenerated the family switches in $doc")

3. `$S`, delete, each from the `/**` that opens its comment (for the two `val`s, from the
   `private val` line) through its last line: `verificationCounts` (to its closing `  }`);
   `private val tableMarks =` and its next line; `private val verificationMarks =` and its next
   line; `widthAudit` (to `  }`); `narrowVerdict` (to `  }`); `constructionOf` (to its last line,
   `    else "other"`); `private def render(): String = {` (to `  }`). `switchMarks`,
   `renderSwitches`, `between` and `replaceBetween` stay.
4. `$S`, the class comment. Its first paragraph, "The expression coverage table in
   `docs/sql-varka.md`, generated and checked rather than" / "written by hand.", becomes "The
   expression coverage table, generated and checked rather than written by hand: its rows are
   here and in the row files, `coverage.json` is rendered from them, and `dev/varka_rows.py
   --docs` renders the table of `docs/sql-varka.md` from that (VARKA-653).". Its third bullet,
   "The committed markdown must equal what this suite renders, so the file in the repository"
   / "is the generated artifact and not a copy of it.", becomes "The committed records must
   equal what this suite renders, so `coverage.json` is the generated artifact and not a copy
   of it; the docs page's table follows in a commit of its own.".
5. `$S`, the two-lane census: the line of premise 7 becomes `      "# family, row, reason:")`.
6. `dev/varka_census.py`, five edits. Each old text is in the file once, with row 654's
   edits in it or not.

   (a) After the line of premise 13 add the line

            ap.add_argument("--docs", action="store_true", help="write the docs' support table and stop")

   (b) The six lines from the line of premise 11 through `        return 1 if found else 0`,

            files[DOCS] = splice(read(DOCS), block)
            if args.check:
                found = stale(files, read)
                for message in found:
                    print(message, file=sys.stderr)
                return 1 if found else 0

   become:

            page = splice(read(DOCS), block)
            if args.docs:
                # The support table is written in a commit of its own (VARKA-653.md 4.5): every branch
                # that adds a coverage row would write the same count, one short of the merge's.
                behind = page != read(DOCS)
                if behind:
                    with open(os.path.join(ROOT, DOCS), "w", encoding="utf-8") as f:
                        f.write(page)
                print(f"census: {DOCS}: {int(behind)} block(s) written")
                return 0
            if args.check:
                found = stale(files, read)
                for message in found:
                    print(message, file=sys.stderr)
                if not found and page != read(DOCS):
                    # A notice, never a failure, and a warning on the run's page in CI.
                    mark = f"::warning file={DOCS}::" if os.environ.get("GITHUB_ACTIONS") else ""
                    print(
                        f"{mark}census: {DOCS}'s {SUPPORT} is behind the census; the render commit"
                        " refreshes it: dev/varka_ops/render-docs.sh"
                    )
                return 1 if found else 0

   So `files` holds the census and the cast matrix alone, and a run with no argument writes
   those two.

   (c) In the module docstring, the three lines

          dev/varka_census.py            # rewrite the census, the cast matrix and the docs' support
                                         # table, print the summary
          dev/varka_census.py --check    # fail if any of the three differs from a fresh one

   become three; the `--summary` line under them, and row 654's `--milestone` line under
   that, stay as they are:

          dev/varka_census.py            # rewrite the census and the cast matrix, print the summary
          dev/varka_census.py --check    # fail if either of the two differs from a fresh one
          dev/varka_census.py --docs     # write the docs' support table, in a commit of its own

   (d) In the docstring's last paragraph, the line `script writes between its markers and
   --check holds to what it renders: per type and per family,` becomes `script writes between
   its markers under --docs: per type and per family,`.

   (e) In the same paragraph, the line `coverage.json, so a new lowering moves the table in
   the same commit as the census.` becomes `coverage.json, so the table follows the census in
   the render commit (VARKA-653).`

   In `dev/varka_regen_generated.sh`, the comment line of premise 15 becomes

        # census, which reads them all and costs a second (the docs' support table is --docs'); a merge

7. Add `dev/varka_ops/render-docs.sh`, mode 755: the line `#!/usr/bin/env bash`, the license
   comment of `dev/varka_ops/matrix-link.sh` (its lines 2 to 17), and:

        # Render the docs page's tables in a commit of their own (VARKA-653.md 4.5).
        #
        #   dev/varka_ops/render-docs.sh          # write the blocks that are behind, say what to commit
        #   dev/varka_ops/render-docs.sh --check  # exit 1 when a block is behind, write nothing
        #
        # The coverage table, the verification counts and the support table of docs/sql-varka.md are
        # computed from every coverage row, so no branch that adds a row writes them: every one would
        # write the same lines, and a count that two branches raise by one merges one short. Run this
        # on master after a merge whose docs job warned that the page is behind, and at a milestone's
        # close, and commit the page alone.
        set -euo pipefail
        cd "$(git rev-parse --show-toplevel)"
        if [ "${1:-}" = "--check" ]; then
          python3 dev/varka_rows.py --docs --check
          # A census check that fails ends the script here, with its status.
          out=$(python3 dev/varka_census.py --check)
          case "$out" in *"is behind the census"*) echo "$out"; exit 1 ;; esac
          exit 0
        fi
        [ $# -eq 0 ] || { echo "usage: dev/varka_ops/render-docs.sh [--check]" >&2; exit 2; }
        if [ -n "$(git status --porcelain -- docs/sql-varka.md)" ]; then
          echo "render-docs: docs/sql-varka.md has changes of its own; commit or drop them first" >&2
          exit 2
        fi
        python3 dev/varka_rows.py --docs
        python3 dev/varka_census.py --docs
        if [ -z "$(git status --porcelain -- docs/sql-varka.md)" ]; then
          echo "render-docs: the page is not behind; nothing to commit"
        else
          echo "render-docs: commit docs/sql-varka.md alone, titled: [VARKA] Render the docs' row tables"
        fi

   In `dev/varka_ops/INDEX.md`, before the row of premise 16, add the row:

        | `render-docs.sh` | write the docs page's coverage table, verification counts and support table from the records and the census, for a commit of their own (`VARKA-653.md` 4.5) | `[--check]` | `docs/sql-varka.md`; 1 under `--check` when a block is behind | `dev/varka_rows.py --docs`, `dev/varka_census.py --docs` | on master after a merge whose docs job warned that the page is behind; at a milestone's close |

   In `dev/varka_ops/AGENTS.md`, before the heading of premise 16, add a bullet and an empty
   line:

        - **Render the docs page apart.** No pull request that adds a coverage row edits the
          generated blocks of `docs/sql-varka.md`. The docs job's steps `./dev/varka_rows.py --check`
          and `./dev/varka_census.py --check` each print a warning on the run's page when a block is
          behind (`rows: ... is behind the records`, `census: ... is behind the census`) and do not
          fail. After a merge whose run on master shows one, run `render-docs.sh` on master and open
          its one-file change as `[VARKA] Render the docs' row tables`. Every row-adding branch would
          write the same count, and two of them merge one short.

8. Regenerate: `VARKA_COVERAGE_REGEN=true flock "$LOCK" build/sbt 'catalyst/testOnly
   *VarkaCoverageSuite'`; then `dev/varka_ops/render-docs.sh`, which must print `the page is
   not behind`.
9. The documents. `sql/varka/AGENTS.md`, first paragraph: "Both are generated by
   `VarkaCoverageSuite` and neither can" / "drift" becomes "`coverage.json` is generated by
   `VarkaCoverageSuite` and cannot drift", and "fails if either file differs" / "from what it
   renders" becomes "fails if the records differ from what it renders; the docs page's tables
   are rendered from them by `dev/varka_ops/render-docs.sh`, in a commit of its own, so
   between two such commits the page is behind by the rows merged since". Wrap the paragraph
   within 100 columns. `sql/varka/ADDING_A_TYPE.md` step 11: "then `VarkaCoverageSuite` again,
   and check" becomes "and check", and "beside `docs/sql-varka.md`" (part B's words) goes; a
   sentence joins the step: "A row-adding branch does not change `docs/sql-varka.md`: its
   tables are rendered apart (`dev/varka_ops/render-docs.sh`)."
   `sql/varka/ADDING_AN_EXPRESSION.md` step 7 is row 658's to rewrite: leave it.
9a. **The other plans that say a regeneration or the census writes the page.** These edits
   are this part's and not the plan's own pull request's: until this part merges, what those
   plans say is right. Write this file as `$X/docs.sed`:

        s#`python3 dev/varka_census.py` rewrites the census, the cast table and the docs' support table; `--check` verifies#`python3 dev/varka_census.py` rewrites the census and the cast table; `--check` verifies. The docs' support table is left to the render commit#
        s# writes `sql/varka/coverage.json` and the table of `docs/sql-varka.md`\.# writes the coverage records under `sql/varka/rows`; the table of `docs/sql-varka.md` is left to the render commit.#
        s#then shows the rows' records under `sql/varka/rows`, the census file and `docs/sql-varka.md` \|#then shows the rows' records under `sql/varka/rows` and the census file \|#
        s#^`\$M9/casts.tsv`, `\$M9/casts_suites.tsv`, `\$M9/census.tsv` and `docs/sql-varka.md`\.$#`$M9/casts.tsv`, `$M9/casts_suites.tsv` and `$M9/census.tsv`, and not `docs/sql-varka.md`.#
        /^`\$M9\/census_curated.txt`, `\$M9\/census.tsv` and$/ {
          N
          s#^`\$M9/census_curated.txt`, `\$M9/census.tsv` and\n`docs/sql-varka.md`\.$#`$M9/census_curated.txt` and `$M9/census.tsv`, and not `docs/sql-varka.md`.#
        }
        s#^\| 9 \| `python3 dev/varka_census.py > /dev/null` \|#| 9 | `python3 dev/varka_census.py > /dev/null \&\& python3 dev/varka_census.py --docs` |#
        s#^(\| `docs/sql-varka.md`, the support table \|.*)\| `python3 dev/varka_census.py`; 9 \|$#\1| `python3 dev/varka_census.py --docs`; 9 |#
        s# and the same for `docs/sql-varka.md` \|$#; `docs/sql-varka.md` is not checked: a line says its support table is behind the census |#

   and run `sed -i -E -f $X/docs.sed $M9/VARKA-657-PARTS.md $M9/VARKA-690.md $M9/VARKA-660.md`
   (without the last file where premise 14b says so). On master `1d68f7d8d89` with part B's
   edits it changes three lines of `VARKA-657-PARTS.md` (what the coverage regeneration and
   the census write, and the status list of its regeneration), two lines of `VARKA-690.md`
   (the ends of its two status lists, which lose `docs/sql-varka.md`), and three lines of
   `VARKA-660.md`: its step 9 runs `python3 dev/varka_census.py --docs` after the census run,
   its table of what changes names `--docs` as the support table's writer, and its planted
   fault no longer expects the census check to fail on the page. Row 660's rows add types to
   the support table, so its builder writes the page in its own pull request (risk 12). Then
   add to `$M9/VARKA-657-PARTS.md` and `$M9/VARKA-690.md`, after the dated line part B added,
   the line `*<date>: no part of this row changes `docs/sql-varka.md`'s generated blocks but
   the family switches: they are rendered apart (`VARKA-653.md` 4.5).*`
10. The demonstration into the results file; this file's section 14.

**The tests.**

| check | case | inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| command 2, the proof of "the same output" | the Python rendering against the page the Scala suite last wrote | master's page, before any regeneration | `--docs --check` exits 0 | a renderer that differs from the suite's in one character: every page would be rewritten once |
| command 3, the planted fault | one cell of one row of the page changed by hand | the `date_add(d, 3)` line's Notes cell set to `x` | `--docs --check` exits 1 and prints `docs/sql-varka.md's coverage table is behind the records`; `--check` prints the notice and exits 0; `--docs` restores the page byte for byte | a check that reads the page it would write |
| command 4 | the census's three modes | the tree's page; then the page with the `IntegerType` line's last cell one higher | `--check` exits 0 with no line; then exits 0 with the `is behind the census` line, which starts `::warning file=docs/sql-varka.md::` under `GITHUB_ACTIONS=true`; `--docs` prints `1 block(s) written` and the page is restored | a census check that still fails a row-adding branch on the page, or a `--docs` that writes when nothing is behind |
| `VarkaCoverageSuite` | the docs test | the family switches block | passes; and with a row's line of the page changed by hand it still passes, since the suite no longer holds that block | a suite that still fails an entry branch on the page |
| command 6 | the two-lane census | regenerated | its header is four lines, the fourth `# family, row, reason:`; its other lines are unchanged | a count that two branches each raise by one |
| command 7 | the demonstration | | 4.10's line D; round 2's two families merge with nothing stale | a support-table count still written by a branch |
| command 8 | the ops script | a clean tree; then the page changed by hand | `the page is not behind; nothing to commit`; then, under `--check`, exit 1 | a render script that commits or that passes a stale page |

**The commands.**

    python3 dev/varka_rows.py --self-test | tail -1                       # 1: self-test: ok
    python3 dev/varka_rows.py --docs --check; echo "exit $?"              # 2: exit 0, before step 8 and after it
    sed -i 's/^| `date_add(d, 3)` | `calendar` |  |  | vector |$/| `date_add(d, 3)` | `calendar` |  | x | vector |/' docs/sql-varka.md; python3 dev/varka_rows.py --docs --check; echo "exit $?"; python3 dev/varka_rows.py --check; echo "exit $?"; python3 dev/varka_rows.py --docs; git diff --stat docs/sql-varka.md
                                                                          # 3: the "is behind the records" line and exit 1; the notice and exit 0; "1 block(s) written"; then no diff
    python3 dev/varka_census.py --check; echo "exit $?"; n=$(grep -n '^| `IntegerType` (`int`) |' docs/sql-varka.md | cut -d: -f1); sed -i "${n}s/ | \([0-9]*\) |$/ | 9\1 |/" docs/sql-varka.md; python3 dev/varka_census.py --check; echo "exit $?"; GITHUB_ACTIONS=true python3 dev/varka_census.py --check | cut -c1-36; python3 dev/varka_census.py --docs; git diff --stat docs/sql-varka.md; python3 dev/varka_census.py --self-test; echo "exit $?"
                                                                          # 4: exit 0; the "is behind the census" line and exit 0; ::warning file=docs/sql-varka.md::; "census: docs/sql-varka.md: 1 block(s) written"; no diff; exit 0
    VARKA_COVERAGE_REGEN=true flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaCoverageSuite'   # 5: passes
    git diff sql/varka/plans/m8/results/VARKA-612-refused.txt | grep '^[-+][^-+]'   # 6: two lines: the old header line removed, "+# family, row, reason:" added
    python3 dev/varka_rows_demo.py | grep -v ' then '                     # 7: 4.10's line D: round 1 2 files, round 2 4
    dev/varka_ops/render-docs.sh; echo "exit $?"; echo >> docs/sql-varka.md; dev/varka_ops/render-docs.sh; echo "exit $?"; git checkout docs/sql-varka.md; dev/varka_ops/render-docs.sh --check; echo "exit $?"
                                                                          # 8: "the page is not behind; nothing to commit" and exit 0; "has changes of its own" and exit 2; exit 0
    sed -i 's/^| `IntegerType` (`int`) | \(.*\) | \([0-9]*\) |$/| `IntegerType` (`int`) | \1 | 9\2 |/' docs/sql-varka.md; dev/varka_ops/render-docs.sh --check; echo "exit $?"; git checkout docs/sql-varka.md
                                                                          # 8a: the "is behind the census" line and exit 1: the script's check fails on a stale page
    dev/varka_regen_generated.sh --all --full && git status --porcelain -- docs sql/varka/rows   # 9: the script's "varka regen: done" line, then nothing: a regeneration no longer writes the page
    git status --short                                                    # 10: the list below
    python3 dev/varka_msg_check.py size                                   # 11: a count under 780

Command 10's list: ` M` for `$S`, `dev/varka_rows.py`, `dev/varka_census.py`,
`dev/varka_regen_generated.sh`, `dev/varka_ops/INDEX.md`, `dev/varka_ops/AGENTS.md`,
`sql/varka/AGENTS.md`, `sql/varka/ADDING_A_TYPE.md`, `$M9/VARKA-657-PARTS.md`,
`$M9/VARKA-690.md`, `$M9/VARKA-660.md`,
`sql/varka/plans/m8/results/VARKA-612-refused.txt`, this file and the results file; `A` for
`dev/varka_ops/render-docs.sh`. No record and not `docs/sql-varka.md`.

**Done when** the commands print what the sheet says and the Build is green.

**The expectations, to tick.**

- [ ] `python3 dev/varka_rows.py --docs` and `--docs --check`, `python3 dev/varka_census.py
  --docs` and `dev/varka_ops/render-docs.sh` exist; on master the page is what they render.
- [ ] `VarkaCoverageSuite` writes and checks the family switches block alone; a run of
  `python3 dev/varka_census.py` writes `census.tsv` and `casts.tsv` and not the page.
- [ ] `docs/sql-varka.md`, the records and every census cell are unchanged.
- [ ] This part changes an output on purpose (section 1): the header line of the two-lane
  census, and who writes three blocks of the page.
- [ ] The description tells the lead: from this merge on, after a merge whose docs job warned
  that the page is behind, `dev/varka_ops/render-docs.sh` on master, committed alone.
- [ ] A plan written before this part that says a regeneration or `python3
  dev/varka_census.py` updates the support table (`VARKA-656.md` part B is built before it;
  section 12 names the others) is read with this: the page is left to the render commit.

**Not here.** The family switches block; the page's prose; `census.tsv`, `casts.tsv` and
every other census file; `dev/varka_census.py` above the last lines of `main`.

### 5.6 Part E: a coverage row as a file

**B0, the premises.**

    grep -c "^def docs_blocks(root):$" dev/varka_rows.py                  # 1: 1 (part D merged)
    git branch -a --list '*varka-653e*'                                   # 2: nothing
    grep -c "^  private val families: Seq\[Family\] = Seq($" $S           # 3: 1
    grep -B2 '^  private def out(e: Expression): NamedExpression = Alias(e, "c")()$' $S | head -1   # 4: "  )", the table's last line
    grep -c "    rowVerified.getOrElse(row.sql, familyVerified.getOrElse(family.title, Differential))" $S   # 5: 1
    grep -c "    return Map.copyOf(out);" $CJ/VarkaAnsiForm.java           # 6: 1, the last line of declined()
    grep -c 'String columnBound = \|String unqualified = \|String narrowChoice = ' $CJ/VarkaAnsiForm.java   # 7: 3
    grep -c "^import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;$" $CJ/VarkaAnsiForm.java   # 8: 1
    git ls-files 'sql/varka/rows/*.row' | wc -l                           # 9: 0
    grep -c '\.\*\\\.row' dev/.rat-excludes                               # 10: 0
    python3 dev/varka_rows_demo.py | tail -1                              # 11: round 1: 2 file(s) in conflict or stale

**The steps.**

1. `$TJ/VarkaRowFiles.java`. Add the imports `java.util.Comparator`,
   `java.util.LinkedHashMap`, `java.util.Map` and `java.util.stream.Stream` in their
   alphabetical places, and before the class's closing brace:


          /**
           * A coverage row added as a file, {@code sql/varka/rows/<family>/<slug>.row}: one
           * {@code key: value} a line (VARKA-653.md 4.6). {@code path} is the file's path under
           * {@code sql/varka/rows}, and {@code values} each key's values in the file's order.
           */
          public record RowFile(String path, Map<String, List<String>> values) {

            /** The value of a key a file holds at most once, or {@code fallback} where it has none. */
            public String one(String key, String fallback) {
              List<String> held = values.getOrDefault(key, List.of());
              return held.isEmpty() ? fallback : held.get(0);
            }

            /** Every value of a key that may repeat, in the file's order. */
            public List<String> all(String key) {
              return values.getOrDefault(key, List.of());
            }

            public String sql() {
              return one("sql", null);
            }

            public String family() {
              return one("family", null);
            }

            public boolean predicate() {
              return "predicate".equals(one("form", null));
            }
          }

          private static final List<String> ONCE =
              List.of("sql", "family", "form", "note", "executable", "domain", "verified");
          private static final List<String> REPEATED = List.of("ansi-declined");

          /** The positions and the reasons an {@code ansi-declined} line names (VarkaAnsiForm). */
          public static final List<String> ANSI_POSITIONS =
              List.of("case-arm", "coalesce-tail", "false-and");
          public static final List<String> ANSI_REASONS =
              List.of("column-bound", "unqualified", "narrow-choice");

          /** A row file's text as a {@link RowFile}; a failure names {@code path}. */
          public static RowFile parseRow(String path, String text) {
            for (int i = 0; i < text.length(); i++) {
              char c = text.charAt(i);
              if (c != '\n' && (c < 32 || c > 126)) {
                throw new IllegalArgumentException(
                    path + ": a row file is printable ASCII and newlines; it holds character " + (int) c);
              }
            }
            var values = new LinkedHashMap<String, List<String>>();
            for (String line : text.split("\n")) {
              if (line.isBlank() || line.startsWith("#")) {
                continue;
              }
              int colon = line.indexOf(": ");
              String key = colon < 0 ? line : line.substring(0, colon);
              if (colon < 0 || !(ONCE.contains(key) || REPEATED.contains(key))) {
                throw new IllegalArgumentException(
                    path + ": not a `key: value` line of a row file: " + line);
              }
              List<String> held = values.computeIfAbsent(key, k -> new ArrayList<>());
              if (!held.isEmpty() && ONCE.contains(key)) {
                throw new IllegalArgumentException(path + ": " + key + " is given twice");
              }
              held.add(line.substring(colon + 2));
            }
            var row = new RowFile(path, values);
            boolean formed = List.of("projection", "predicate").contains(row.one("form", ""));
            if (row.sql() == null || row.family() == null || !formed) {
              throw new IllegalArgumentException(
                  path + ": a row file needs sql, family and form (projection or predicate)");
            }
            for (String stated : row.all("ansi-declined")) {
              String[] parts = stated.split(" ");
              if (parts.length != 2 || !ANSI_POSITIONS.contains(parts[0])
                  || !ANSI_REASONS.contains(parts[1])) {
                throw new IllegalArgumentException(path + ": ansi-declined takes a position "
                    + ANSI_POSITIONS + " and a reason " + ANSI_REASONS + ", not: " + stated);
              }
            }
            return row;
          }

          private static List<RowFile> rows;

          /**
           * Every row file under {@code sql/varka/rows}, read once: by family directory, and in a
           * directory by the file's name without its extension, which is the order
           * {@code dev/varka_rows.py} gives a family's row files.
           */
          public static synchronized List<RowFile> rows() {
            if (rows == null) {
              Path top = Path.of(home(), "sql", "varka", "rows");
              try (Stream<Path> files = Files.exists(top) ? Files.walk(top) : Stream.<Path>empty()) {
                var found = new ArrayList<RowFile>();
                List<String> paths = files
                    .map(p -> top.relativize(p).toString().replace(File.separatorChar, '/'))
                    .filter(p -> p.endsWith(".row"))
                    .sorted(Comparator.comparing((String p) -> p.substring(0, p.length() - 4)))
                    .toList();
                for (String path : paths) {
                  found.add(parseRow(path, Files.readString(top.resolve(path), StandardCharsets.UTF_8)));
                }
                rows = List.copyOf(found);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }
            return rows;
          }

2. `$S`. The line of premise 3 becomes `  private val tableFamilies: Seq[Family] = Seq(`.
   After the table's end (the `  )` of premise 4) and an empty line, add:

          /**
           * The rows added as files (VARKA-653.md 4.6): `sql/varka/rows/<family>/<slug>.row`, parsed by
           * `VarkaRowFiles`, by family directory and then by file name.
           */
          private lazy val fileRows: Seq[VarkaRowFiles.RowFile] = VarkaRowFiles.rows().asScala.toSeq

          private def rowOf(f: VarkaRowFiles.RowFile): Row = Row(f.sql, f.one("note", ""),
            executable = Option(f.one("executable", null)), domain = Option(f.one("domain", null)))

          /**
           * The table: the families above, each followed by its row files, and then the families that
           * row files alone make, by title. `dev/varka_rows.py` assembles `coverage.json` in this order.
           */
          private lazy val families: Seq[Family] = {
            val byTitle = fileRows.groupBy(_.family)
            val titles = tableFamilies.map(_.title).toSet
            tableFamilies.map(f => f.copy(rows = f.rows ++ byTitle.getOrElse(f.title, Nil).map(rowOf))) ++
              byTitle.keys.filterNot(titles).toSeq.sorted.map { title =>
                Family(title, byTitle(title).head.predicate, byTitle(title).map(rowOf))
              }
          }

          /** How a row file says it is verified, in the words `Verified.render` prints. */
          private def verifiedFrom(text: String): Verified = text match {
            case "differential" => Differential
            case "rendered" => Rendered
            case s if s.startsWith("proof: ") => Proved(s.stripPrefix("proof: "))
            case s if s.startsWith("refinement: ") =>
              Refined(s.stripPrefix("refinement: ").split("; ").toSeq: _*)
            case s if s.startsWith("exhaustive: ") =>
              val Array(suite, test) = s.stripPrefix("exhaustive: ").split(", ", 2)
              Exhausted(suite, test)
            case s if s.startsWith("owed: row ") => Owed(s.stripPrefix("owed: row ").toInt)
            case other =>
              throw new IllegalArgumentException(s"a row file's verified is none of the six forms: $other")
          }

   `verifiedOf` (the line of premise 5 and the line above it) becomes:

          private def verifiedOf(family: Family, row: Row): Verified = {
            val stated = fileRows.find(_.sql == row.sql).flatMap(f => Option(f.one("verified", null)))
            stated.map(verifiedFrom).getOrElse(
              rowVerified.getOrElse(row.sql, familyVerified.getOrElse(family.title, Differential)))
          }

   and, after the test "every documented expression compiles to a fused kernel", add:

          test("a row file joins its family after the table's rows, in its form, and once") {
            val table = tableFamilies.flatMap(f => f.rows.map(_.sql -> f)).toMap
            fileRows.foreach { f =>
              assert(!table.contains(f.sql), s"${f.path}: the table already holds the row ${f.sql}")
              tableFamilies.find(_.title == f.family).foreach { family =>
                assert(family.predicates == f.predicate, s"${f.path}: its form is not its family's")
              }
            }
            fileRows.groupBy(_.family).foreach { case (title, rows) =>
              assert(rows.map(_.predicate).distinct.size == 1,
                s"the row files of $title mix projections and predicates")
            }
            assert(fileRows.map(_.sql).distinct.size == fileRows.size, "two row files hold one row")
            families.foreach { f =>
              val fromFiles = f.rows.dropWhile(r => table.contains(r.sql))
              assert(fromFiles.forall(r => !table.contains(r.sql)),
                s"${f.title}: a row file comes before a row of the table")
            }
          }

3. `$CJ/VarkaAnsiForm.java`. Add `import
   org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaRowFiles;` above the line of
   premise 8. Before the line of premise 6 add:

            // A row added as a file states its own positions (VARKA-653.md 4.6), one a line:
            // `ansi-declined: <position> <reason>`, each a name that VarkaRowFiles.parseRow has checked.
            var positions = Map.of(
                "case-arm", CASE_ARM, "coalesce-tail", COALESCE_TAIL, "false-and", FALSE_AND);
            var reasons = Map.of(
                "column-bound", columnBound, "unqualified", unqualified, "narrow-choice", narrowChoice);
            for (VarkaRowFiles.RowFile row : VarkaRowFiles.rows()) {
              for (String stated : row.all("ansi-declined")) {
                String[] parts = stated.split(" ");
                out.put(row.sql() + "\t" + positions.get(parts[0]), reasons.get(parts[1]));
              }
            }

4. `$TJ/VarkaRowFilesSuite.java` gains six tests, before its closing brace:


          @Test
          @DisplayName("a row file's keys are read, comments and empty lines skipped")
          void rowFile() {
            var row = VarkaRowFiles.parseRow("f/x.row", "# c\n\nsql: i + 1\nfamily: Integer arithmetic\n"
                + "form: projection\nansi-declined: false-and unqualified\n"
                + "ansi-declined: case-arm column-bound\n");
            assertEquals("i + 1", row.sql());
            assertEquals("Integer arithmetic", row.family());
            assertTrue(!row.predicate());
            assertEquals("", row.one("note", ""));
            assertEquals(List.of("false-and unqualified", "case-arm column-bound"),
                row.all("ansi-declined"));
          }

          @Test
          @DisplayName("a line that is no key of a row file is refused, naming the file")
          void unknownKey() {
            var e = assertThrows(IllegalArgumentException.class, () -> VarkaRowFiles.parseRow(
                "f/x.row", "sql: i\nfamily: F\nform: projection\ncolour: red\n"));
            assertTrue(e.getMessage().contains("f/x.row") && e.getMessage().contains("colour: red"),
                e.getMessage());
          }

          @Test
          @DisplayName("a key given twice is refused")
          void twice() {
            var e = assertThrows(IllegalArgumentException.class, () -> VarkaRowFiles.parseRow(
                "f/x.row", "sql: i\nsql: i + 1\nfamily: F\nform: projection\n"));
            assertTrue(e.getMessage().contains("sql is given twice"), e.getMessage());
          }

          @Test
          @DisplayName("a row file with no form is refused")
          void noForm() {
            var e = assertThrows(IllegalArgumentException.class,
                () -> VarkaRowFiles.parseRow("f/x.row", "sql: i\nfamily: F\n"));
            assertTrue(e.getMessage().contains("needs sql, family and form"), e.getMessage());
          }

          @Test
          @DisplayName("a carriage return or a tab in a row file is refused")
          void controlCharacter() {
            for (String text : List.of("sql: i\r\nfamily: F\nform: projection\n",
                "sql: i\nfamily: F\nform:\tprojection\n")) {
              var e = assertThrows(IllegalArgumentException.class,
                  () -> VarkaRowFiles.parseRow("f/x.row", text));
              assertTrue(e.getMessage().contains("printable ASCII"), e.getMessage());
            }
          }

          @Test
          @DisplayName("an ansi-declined line that names no position or no reason is refused")
          void ansiDeclined() {
            for (String stated : List.of("false-and", "nowhere unqualified", "false-and because")) {
              var e = assertThrows(IllegalArgumentException.class, () -> VarkaRowFiles.parseRow(
                  "f/x.row", "sql: i\nfamily: F\nform: projection\nansi-declined: " + stated + "\n"));
              assertTrue(e.getMessage().contains("ansi-declined takes a position"), e.getMessage());
            }
          }

   with the import `java.util.List` added in its alphabetical place.
5. `dev/varka_rows.py`. Before `def self_test():` add this text, then two empty lines:

        ROW_KEYS = ("sql", "family", "form", "note", "executable", "domain", "verified", "ansi-declined")


        def row_file(text):
            """A row file's keys and values (VARKA-653.md 4.6); a key that may repeat keeps its last.

            >>> row_file("# a comment\\nsql: i + 1\\nfamily: Integer arithmetic\\nform: projection\\n")
            {'sql': 'i + 1', 'family': 'Integer arithmetic', 'form': 'projection'}
            >>> row_file("sql i + 1\\n")
            Traceback (most recent call last):
            ...
            ValueError: not a `key: value` line of a row file: sql i + 1
            >>> row_file("sql: i\\r\\n")
            Traceback (most recent call last):
            ...
            ValueError: a row file is printable ASCII and newlines; it holds '\\r'
            """
            for c in text:
                if c != "\n" and not 32 <= ord(c) < 127:
                    raise ValueError(f"a row file is printable ASCII and newlines; it holds {c!r}")
            values = {}
            for line in text.split("\n"):
                if line.strip() and not line.startswith("#"):
                    key, sep, value = line.partition(": ")
                    if not sep or key not in ROW_KEYS:
                        raise ValueError(f"not a `key: value` line of a row file: {line}")
                    values[key] = value
            return values


        def hand_errors(root):
            """What --check finds in the row files: one that is no row file, and one under another
            name than its row's."""
            tree = Disk(root)
            errors = []
            for name in tree.names(ROWS):
                if not name.endswith(".row"):
                    continue
                try:
                    row = row_file(tree.read(f"{ROWS}/{name}"))
                except ValueError as e:
                    errors.append(f"{ROWS}/{name}: {e}")
                    continue
                if "sql" not in row or not family_slug(row.get("family", "")):
                    errors.append(f"{ROWS}/{name}: a row file needs sql and a family with a letter")
                elif name != f"{family_slug(row['family'])}/{slug(row['sql'])}.row":
                    want = f"{ROWS}/{family_slug(row['family'])}/{slug(row['sql'])}.row"
                    errors.append(f"{ROWS}/{name} holds the row {row['sql']!r}, whose file is {want}")
            return errors

   and in `main`'s `--check` branch `errors = check(root)` becomes `errors = check(root) +
   hand_errors(root)`.
6. `dev/.rat-excludes`: add the line `.*\.row` after the line `.*\.json`. A row file carries
   no license header: its lines are data that two tools parse.
7. The documents. `sql/varka/AGENTS.md` gains, after the paragraph part B rewrote:

        A new coverage row is a file, not a line of the Scala table: `sql/varka/rows/<family>/<slug>.row`
        with `sql`, `family`, `form` and what else the row needs (`VARKA-653.md` 4.6). `python3 -c
        "import sys; sys.path.insert(0, 'dev'); import varka_rows; print(varka_rows.family_slug('<title>'),
        varka_rows.slug('<sql>'))"` prints the two names, and `python3 dev/varka_rows.py --check` holds
        them. A row that needs a Catalyst expression built in code or a math form stays in the table.

   `sql/varka/ADDING_A_TYPE.md` step 11's first sentence, "A load, a comparison, a choice and
   an output in" / "`VarkaCoverageSuite.scala`", becomes "A load, a comparison, a choice and an
   output, each a row file under `sql/varka/rows/` (`AGENTS.md`; a row that needs Scala goes in
   `VarkaCoverageSuite.scala`)".
8. The demonstration into the results file; this file's section 14.

**The tests.**

| suite | case | inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `VarkaRowFilesSuite` | "a row file's keys are read, comments and empty lines skipped" | `"# c\n\nsql: i + 1\nfamily: Integer arithmetic\nform: projection\nansi-declined: false-and unqualified\nansi-declined: case-arm column-bound\n"` | `sql` `i + 1`, not a predicate, `one("note", "")` empty, `all("ansi-declined")` two values in order | a parser that keeps the last of a repeated key |
| | "a line that is no key of a row file is refused, naming the file" | `"sql: i\nfamily: F\nform: projection\ncolour: red\n"` | `IllegalArgumentException` whose message holds the path and `colour: red` | a misspelt key read as nothing, so that a domain is silently dropped |
| | "a key given twice is refused" | two `sql:` lines | the message holds `sql is given twice` | two rows in one file, the second lost |
| | "a row file with no form is refused" | `"sql: i\nfamily: F\n"` | the message holds `needs sql, family and form` | a predicate row rendered as a projection |
| `VarkaCoverageSuite` | "a row file joins its family after the table's rows, in its form, and once" | the tree's row files (none on master) | passes | a row file that repeats a table row, or sits in a family of the other form |
| | every existing test, with one row file added for command 3 | `sql: -i - 1`, `family: Integer arithmetic`, `form: projection` | the row compiles to a fused kernel, is the family's last row in `coverage.json`, and the three record files appear | a suite that reads the files and renders them nowhere |
| `VarkaCoverageHarnessSuite` (sql/core) | with the same file and `ansi-declined: false-and unqualified` | | the harness's ANSI positions pass for the row | a row file's positions not reaching `DECLINED`, which fails the row at "the right side of a false AND" |
| `dev/varka_rows.py --self-test` | `row_file`'s two doctests | | as printed | |
| | "a carriage return or a tab in a row file is refused" | `"sql: i\r\nfamily: F\nform: projection\n"`; `"sql: i\nfamily: F\nform:\tprojection\n"` | the message holds `printable ASCII` | a file the Java parser and the Python one read differently |
| | "an ansi-declined line that names no position or no reason is refused" | a valid file with `ansi-declined: false-and`; with `nowhere unqualified`; with `false-and because` | each an `IllegalArgumentException` whose message holds `ansi-declined takes a position` | a mistyped position read as none, so that the harness fails the row far from its cause |
| command 5, the planted fault | the row file renamed | | `--check` exits 1 with `holds the row '-i - 1', whose file is` | a row file under a name that another branch adding the same row would not collide with |

**The commands.**

    flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaRowFilesSuite *VarkaCoverageSuite'   # 1: both pass; the JUnit line reads Total 9
    python3 dev/varka_rows.py --check; echo "exit $?"                     # 2: exit 0
    dev/varka_regen_generated.sh --all --full && git status --porcelain -- sql/varka/rows docs   # 2a: the script's "varka regen: done" line, then nothing
    d=sql/varka/rows/integer-arithmetic; f=$d/$(python3 -c "import sys; sys.path.insert(0, 'dev'); import varka_rows; print(varka_rows.slug('-i - 1'))").row; printf 'sql: -i - 1\nfamily: Integer arithmetic\nform: projection\nansi-declined: false-and unqualified\n' > $f; dev/varka_regen_generated.sh --all --full; git status --short sql/varka/rows
                                                                          # 3: four "??" lines: the row file and its three records, and no " M" line
    flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaCoverageSuite' 'sql/testOnly *VarkaCoverageHarnessSuite'   # 4: both pass
    mv $f $d/x.row; python3 dev/varka_rows.py --check; echo "exit $?"; mv $d/x.row $f
                                                                          # 5: the message of the tests table, and exit 1
    rm $f $d/*-i-1-*.json; python3 dev/varka_rows.py --views; git status --short sql/varka/rows   # 6: nothing: the trial row is gone
    python3 dev/varka_rows_demo.py | grep -v ' then '                     # 7: 4.10's line E: round 1 1 file, round 2 3
    python3 dev/varka_msg_check.py size                                   # 8: a count under 720

If `-i - 1` does not fuse on the build's master (command 3's regeneration fails in "every
documented expression compiles to a fused kernel"), use `i + 2` in its place in commands 3 to
6: the trial needs any row of the family that fuses and is not in the table. In command 3 the
width audit's record is written only on the laptop's CPU; on another host the regeneration
says the audit is owed and three `??` lines print.

**Done when** the commands print what the sheet says, the Build is green, and the option
matrix on the branch is green.

The proof of "the same output" for this part is command 2a: with no row file in the tree, a
regeneration by the changed suite writes nothing.

**The expectations, to tick.**

- [ ] `VarkaRowFiles.rows()`, `.parseRow` and the record `RowFile` (public, with `sql()`,
  `family()`, `predicate()`, `one(key, fallback)`, `all(key)` and `path()`).
- [ ] With no row file in the tree, `coverage.json`, the records and every suite's output are
  unchanged: `git status --short sql/varka/rows docs` prints nothing after a regeneration.
- [ ] The trial row of command 3 is not committed.
- [ ] `git status --short`: ` M` for `$S`, `$TJ/VarkaRowFiles.java`,
  `$TJ/VarkaRowFilesSuite.java`, `$CJ/VarkaAnsiForm.java`, `dev/varka_rows.py`,
  `dev/.rat-excludes`, `sql/varka/AGENTS.md`,
  `sql/varka/ADDING_A_TYPE.md`, this file and the results file.
- [ ] `dev/.rat-excludes` is not Varka-scoped: this pull request's Build runs Spark's whole
  matrix once. Say so in the description.

**Not here.** Moving any row of the Scala table to a file; `VarkaDomains`' fixtures;
`ADDING_AN_EXPRESSION.md` (row 658).
### 5.7 Part F: a matrix skip as a file

**B0, the premises.**

    grep -c "^def hand_errors(root):$" dev/varka_rows.py                  # 1: 1 (part E merged)
    git branch -a --list '*varka-653f*'                                   # 2: nothing
    grep -c '          Path file = Path.of(System.getProperty("spark.test.home", "."), SKIPS_PATH);' $TJ/VarkaMatrixRuns.java   # 3: 1
    grep -c "^def skip_lines(skips_path):$" dev/varka_matrix_report.py    # 4: 1
    grep -c "^def rule_skips_tsv(ch):$" dev/varka_lint.py                 # 5: 1
    sed -n '/^def rule_skips_tsv(ch):$/,/^def /p' dev/varka_lint.py | grep -c "{SKIPS}:{n}:"   # 6: 9
    grep -c '^skips=sql/varka/matrix/skips.tsv$' dev/varka_ops/pre-pr.sh  # 7: 1
    grep -c -F -- '-- "$skips"; done' dev/varka_ops/pre-pr.sh             # 8: 1
    grep -c '^/matrix/skips.tsv merge=union$' sql/varka/.gitattributes    # 9: 1
    ls sql/varka/matrix/skips.d 2>&1 | grep -c "No such file"             # 10: 1
    grep -c "^    - name: The row records' tool$" .github/workflows/build_and_test.yml   # 11: 1
    python3 dev/varka_rows_demo.py | tail -1                              # 12: round 1: 1 file(s) in conflict or stale

**The steps.**

1. `$TJ/VarkaMatrixRuns.java`. After the declaration of `SKIPS_PATH` add:

          /** Where a task adds a skip (VARKA-653.md 4.8): a file of its own, in the list's form. */
          public static final String SKIPS_DIR = "sql/varka/matrix/skips.d";

          /** The skip list's text under {@code home}: the committed list, then each file of the folder. */
          static String skipsText(Path home) throws IOException {
            var text = new StringBuilder();
            Path list = home.resolve(SKIPS_PATH);
            if (Files.exists(list)) {
              text.append(Files.readString(list, StandardCharsets.UTF_8)).append('\n');
            }
            Path folder = home.resolve(SKIPS_DIR);
            if (Files.isDirectory(folder)) {
              try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".tsv")).sorted().toList()) {
                  text.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
                }
              }
            }
            return text.toString();
          }

   with the import `java.util.stream.Stream`. In `skips()`, the line of premise 3 becomes
   `          Path home = Path.of(System.getProperty("spark.test.home", "."));`, and the three
   lines `            list = Files.exists(file)` / `                ?
   parseSkips(Files.readString(file, StandardCharsets.UTF_8))` / `                : List.of();`
   become one, `            list = parseSkips(skipsText(home));`. The comment above
   `parseSkips` gains a sentence: "The list is that file and the files of {@link #SKIPS_DIR}."
2. `dev/varka_matrix_report.py`: `skip_lines` becomes:

        def skip_lines(skips_path):
            """The skip list's entries: the file's, then those of each file of skips.d beside it
            (VARKA-653)."""
            entries = set()
            files = [skips_path] if skips_path.exists() else []
            files += sorted(skips_path.with_name("skips.d").glob("*.tsv"))
            for path in files:
                for line in path.read_text().splitlines():
                    if not line.strip() or line.startswith("#"):
                        continue
                    config, suite, test, kind, _reason = line.split("\t")
                    entries.add((kind, config, suite, test))
            return entries

3. `dev/varka_lint.py`. After the line `SKIPS = "sql/varka/matrix/skips.tsv"` add
   `SKIPS_DIR = "sql/varka/matrix/skips.d"`. The first six lines of `rule_skips_tsv` (to the
   `for n, line in enumerate(read(SKIPS).splitlines(), 1):` line) become:

        def skip_files():
            """The skip list's files: the committed list, then each skip file of skips.d, by name."""
            top = os.path.join(ROOT, SKIPS_DIR)
            names = sorted(os.listdir(top)) if os.path.isdir(top) else []
            return [SKIPS] + [f"{SKIPS_DIR}/{name}" for name in names if name.endswith(".tsv")]


        def rule_skips_tsv(ch):
            paths = skip_files()
            if not any(path in ch.files for path in paths):
                return
            options = option_names()
            seen, keys = {}, {}
            for path in paths:
                yield from skip_findings(ch, path, options, seen, keys)


        def skip_findings(ch, path, options, seen, keys):
            for n, line in enumerate(read(path).splitlines(), 1):

   and in the rest of the function, now `skip_findings`: each of the nine `{SKIPS}:{n}:` becomes
   `{path}:{n}:`; `ch.wrote(SKIPS, n)` becomes `ch.wrote(path, n)`; `seen[line] = n` becomes
   `seen[line] = f"{path}:{n}"`; `keys[key] = n` becomes `keys[key] = f"{path}:{n}"`. So a line
   that repeats a line of another file is named with that file. The comment "Git merges the
   file by union, which keeps both the old and the new form of a line one" / "side edited; the
   matrix acts on the first fails line for a test and ignores the rest." becomes "Two files
   may name one test, or one file twice; the matrix acts on the first fails line for a" /
   "test and ignores the rest.".
4. `dev/varka_ops/pre-pr.sh`: the line of premise 7 becomes
   `skips="sql/varka/matrix/skips.tsv sql/varka/matrix/skips.d"`, and in the line of premise 8
   `-- "$skips"` becomes `-- $skips` (the script is bash, which splits it into the two paths).
   No other edit there: `code_files` counts a file of `skips.d` as code, since `NONCODE`'s
   pattern for `sql/varka/matrix/` matches a file directly in that folder alone, and the
   comment above `NONCODE` wants the skip list counted as code. The lead makes the same two
   changes in `~/varka-ops/pre-pr.sh`.
5. `dev/varka_rows.py`. Before `def self_test():` add this text, then two empty lines:

        SKIPS_DIR = "sql/varka/matrix/skips.d"


        def skip_file_errors(root):
            """What --check finds under skips.d (VARKA-653.md 4.8): a file that is neither the README
            nor a skip file named for its task and its first skipped test,
            varka-<row>-<slug of the test>.tsv, which no two tasks share."""
            top = os.path.join(root, SKIPS_DIR)
            errors = []
            for name in sorted(os.listdir(top)) if os.path.isdir(top) else []:
                if name == "README.md":
                    continue
                with open(os.path.join(top, name), encoding="utf-8") as f:
                    lines = [x for x in f.read().splitlines() if x.strip() and not x.startswith("#")]
                fields = lines[0].split("\t") if lines else []
                want = f"-{slug(fields[2])}.tsv" if len(fields) == 5 else None
                if want is None:
                    errors.append(f"{SKIPS_DIR}/{name}: its first line is no skip line of five fields")
                elif not re.fullmatch(r"varka-[0-9]+" + re.escape(want), name):
                    errors.append(f"{SKIPS_DIR}/{name}: a skip file is named varka-<row>{want}")
            return errors

   and in `main`'s `--check` branch `errors = check(root) + hand_errors(root)` becomes
   `errors = check(root) + hand_errors(root) + skip_file_errors(root)`.
6. `sql/varka/.gitattributes`: delete the line of premise 9 and the three comment lines above
   it.
7. Add `sql/varka/matrix/skips.d/README.md`, with no license comment, as
   `sql/varka/proofs/README.md` has none, and this text:

        # A skip a task adds is a file of its own

        A matrix skip that a task adds is a line of a file here, in the form of `../skips.tsv`:
        configuration, suite, test, kind, reason, tab separated. The file is named
        `varka-<row>-<slug>.tsv`: the task's number, and the slug of the test of the file's first
        line, which `python3 -c "import sys; sys.path.insert(0, 'dev'); import varka_rows;
        print(varka_rows.slug('<test>'))"` prints and `python3 dev/varka_rows.py --check` holds. The
        skip list is `../skips.tsv` followed by these files in name order (`VARKA-653.md` 4.8).

8. `sql/varka/AGENTS.md`: the text from "and an answer test a configuration breaks by design
   gets a line in" to "which reads every line and flags a test named twice." becomes: "and an
   answer test a configuration breaks by design gets a line with its reason in a file of its
   task under `sql/varka/matrix/skips.d/` (its `README.md` gives the name and the form): the
   skip list is `skips.tsv` and those files, so two branches that add skips share no file.
   `dev/varka_lint.py` reads every line of the list and flags a test named twice."
   `ADDING_A_TYPE.md` step 15's "gets a line in `sql/varka/matrix/skips.tsv` with" becomes
   "gets a line in a file under `sql/varka/matrix/skips.d/` with".
9. **The demonstration in CI.** In `.github/workflows/build_and_test.yml`, after the two
   lines of the step of premise 11, add:

            - name: Five row-adding branches merge in every order with nothing owed
              run: ./dev/varka_rows_demo.py

   It exits 0 from this part on, and it stays green whatever rows land: its rows are not real
   rows (4.10). In `sql/varka/ADDING_A_TYPE.md`, step 11 gains: "`python3
   dev/varka_rows_demo.py` shows that five branches that each add a row merge in every order;
   it runs in the docs job."
10. **The results and the close.** The demonstration into the results file. Section 14 of
    this file: the outcome, each prediction of section 7 scored. `$M9/PLAN.md` row 653 to
    `**Done**`, with the seven pull requests named.

**The tests.**

| suite | case | inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `VarkaMatrixSuite` (Scala, `$TS/VarkaMatrixSuite.scala`, which holds `parseSkips`' test), the new test below | a temporary home with `sql/varka/matrix/skips.tsv` holding a comment and one line for test `a`; then `skips.d/b.tsv` and `skips.d/a.tsv` with one line each, for tests `c` and `b`, and `skips.d/notes.txt` | `VarkaMatrixRuns.parseSkips(VarkaMatrixRuns.skipsText(home))` | first one skip, `a`, with no folder; then three, their tests in the order `a`, `b`, `c`; the `.txt` is not read | a reader that still reads one file: a task's skip would be ignored and its test fail under the configuration; a reader that fails on a tree with no folder |
| `dev/varka_lint.py --selftest` (its cases run in the docs job), the new case below | a fixture whose `skips.d/varka-1-x.tsv` names a test that `skips.tsv` names | the rule reports `skips-tsv` with `names the test of line` | 74 cases pass, one more than on master | a duplicate across two files unseen |
| command 4, two planted faults | a skip file `varka-9-wrong.tsv` whose first line skips the test `some test`; the same file with a first line of three fields | | `--check` exits 1 with `a skip file is named varka-<row>-some-test-b39a18f4.tsv`; with `its first line is no skip line of five fields` | two tasks that pick one file name; a file the readers would fail on |
| command 5 | the report over a scratch run folder | a line in `skips.d/x.tsv` marks a test | `skip_lines` returns it | a matrix report that calls the skipped test a failure |

The new test of `VarkaMatrixSuite`, added after the test that calls `VarkaMatrix.parseSkips`:

          test("the skip list is the committed file and then each file of skips.d, by name") {
            val home = Files.createTempDirectory("varka-skips")
            val matrix = Files.createDirectories(home.resolve("sql/varka/matrix"))
            def line(test: String): String = s"lanesOverride=4\tVarkaMatrixSuite\t$test\tfails\tr\n"
            def tests: Seq[String] =
              VarkaMatrixRuns.parseSkips(VarkaMatrixRuns.skipsText(home)).asScala.map(_.test).toSeq
            Files.writeString(matrix.resolve("skips.tsv"), "# a comment\n" + line("a"))
            assert(tests === Seq("a"))
            val folder = Files.createDirectories(matrix.resolve("skips.d"))
            Files.writeString(folder.resolve("b.tsv"), line("c"))
            Files.writeString(folder.resolve("a.tsv"), line("b"))
            Files.writeString(folder.resolve("notes.txt"), "not a skip\n")
            assert(tests === Seq("a", "b", "c"))
          }

The lint's new case, in `selftest`, added after the case named "a skip line kept in its old
and its edited form, as a union merge leaves it" (after its closing `        )`):

                case(
                    "a skip file of skips.d that names a test the list names",
                    {
                        tables: split,
                        suite: lic + "class VarkaShapeSuite {}\n",
                        SKIPS: skip % "a name split across two lines",
                        SKIPS_DIR + "/varka-1-x.tsv": (skip % "a name split across two lines").replace(
                            "the reason", "another"
                        ),
                    },
                    "skips-tsv:names the test of line",
                    True,
                )

**The commands.**

    flock "$LOCK" build/sbt 'catalyst/testOnly *VarkaMatrixSuite'         # 1: passes
    python3 dev/varka_lint.py --selftest | tail -1                        # 2: varka_lint selftest: ok (N cases), N one more than on master
    python3 dev/varka_lint.py sql/varka/matrix/skips.tsv; echo "exit $?"  # 3: exit 0
    f=sql/varka/matrix/skips.d/varka-9-wrong.tsv; printf 'a=1\tS\tsome test\tfails\tr\n' > $f; python3 dev/varka_rows.py --check; echo "exit $?"; printf 'a=1\tS\tr\n' > $f; python3 dev/varka_rows.py --check; echo "exit $?"; rm $f
                                                                          # 4: the two messages, each with exit 1
    python3 -c "import sys, pathlib; sys.path.insert(0, 'dev'); import varka_matrix_report as r; print(len(r.skip_lines(pathlib.Path('sql/varka/matrix/skips.tsv'))))"   # 5: the number of distinct (kind, configuration, suite, test) of the list, the same as on master
    python3 dev/varka_rows_demo.py; echo "exit $?"                        # 6: 4.10's line F: "round 1: 0 file(s) in conflict or stale" and exit 0; round 2 names the two census files for the three siblings
    python3 dev/varka_msg_check.py size                                   # 7: a count under 450

The proof of "the same output" for this part is command 5: with no skip file, every reader's
list is the list of `skips.tsv`.

**Done when** the commands print what the sheet says; the Build is green, with the docs job's
new step; the option matrix on the branch is green (it reads the list); and row 653's status
reads Done.

**The expectations, to tick.**

- [ ] `VarkaMatrixRuns.SKIPS_DIR` (public) and `skipsText(Path)` (package-private).
- [ ] With an empty `skips.d`, every reader's list is what it was: command 5's count.
- [ ] `git status --short`: ` M` for `$TJ/VarkaMatrixRuns.java`, `$TS/VarkaMatrixSuite.scala`,
  `dev/varka_matrix_report.py`, `dev/varka_lint.py`, `dev/varka_ops/pre-pr.sh`,
  `dev/varka_rows.py`, `.github/workflows/build_and_test.yml`, `sql/varka/.gitattributes`,
  `sql/varka/AGENTS.md`, `sql/varka/ADDING_A_TYPE.md`, `sql/varka/plans/m9/PLAN.md`, this file
  and the results file; `A` for `sql/varka/matrix/skips.d/README.md`.
- [ ] The workflow file is not Varka-scoped: the Build runs Spark's whole matrix once.

**Not here.** Moving the 447 lines; a limit on the list's length; `VarkaSuiteHarnessSuite`'s
read of the committed list, which checks the list's own lines and is left on that file; the
census's files.

## 6. Tests, proofs, fuzzing and coverage

The row adds no kernel, no IR record and no lowering: no proof file, no fuzz production, no
coverage row of its own, no JaCoCo or mutation run. Its tests hold three things: the records
are the files they replace, a stale record fails, and row-adding branches merge.

| suite or check | where | what it holds | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `dev/varka_rows_demo.py` | the docs job from part F; by hand in every part | five row-adding branches, their records written by the tool's own `--split`, merge in every order, with one merged tree, nothing stale and the records' check passing | a list that two row-adding branches still share; a count that merges with no conflict and is stale |
| `dev/varka_rows.py --self-test` | the docs job; `VarkaRowFilesSuite` | the slug rule; split and assemble are inverse; a row file's place; three planted faults; from part E the row file's grammar | a record format that loses or reorders something |
| `dev/varka_rows.py --check` | the docs job, before the census's check | the committed records are exactly what a regeneration writes from the views they give: none missing, none left over, none misnamed; from part E the row files' names; from part F the skip files' names | a missing or misnamed record on CI, the width audit's among them, whose own suite runs on the laptop only |
| `VarkaCoverageSuite`, `VarkaEmittedBytesSuite`, `VarkaWidthAuditSuite`, unchanged in what they compare but for part D's two docs blocks | the Build's catalyst job; the audit on the laptop | each renders its file and compares it with the view | a row whose record is not what the compiler gives (4.3's table) |
| `VarkaRowFilesSuite` (Java, JUnit 5) | catalyst | the test JVM can run the tool; a failure of the tool is an exception; a row file's grammar | a runner with no `python3`; a misspelt key read as nothing |
| `VarkaMatrixSuite`'s new test (Scala) | catalyst | the skip list is the file and then the folder's files | a task's skip that no reader reads |
| the existing tools' own tests: `dev/varka_generated_merge.py --self-test`, `dev/varka_push_check.py --self-test`, `dev/varka_lint.py --selftest`, `dev/varka_regen_inputs.py --selftest`, `dev/varka_precommit.sh --selftest`, `modules.py`'s doctests, `dev/varka_census.py --self-test` | the docs job | they pass with each part applied (3.3), three of them only with the edits the sheets name | a tool's own test that reads one of the moved files |
| the proof of "the same output" in each part: B command 5, C command 5, D command 2, E command 2a, F command 5 | by hand, in the sheets | B: every view is byte for byte the file it replaces. C: the `lanes` sections are value for value what they were. D: the page is what the new renderer gives. E and F: with no row file and no skip file, nothing a reader sees changes | a reader whose input changed |
| the planted faults of parts B, C, D, E and F | by hand, in the sheets | each check fails on the fault it names | a check that cannot fail |

**What runs only in CI:** the Build (catalyst, sql, docs), the option matrix on parts B, C, D,
E and F, the Maven build, and the workflows of part A2's premise 8 on their own images.
**What runs only on the laptop:** `VarkaWidthAuditSuite`'s own check, so part B's command 6
and every regeneration that adds a row's audit record.

**Test time.** A test JVM that reads a view runs the tool once: one process that reads about
2,700 small files and writes at most three. `VarkaRowFilesSuite` runs it three times more.
The three git hooks run it once a checkout, a merge and a rebase. The three suites' work is
unchanged: part C hashes each row's line alone where it hashed them together, and adds no
emission. Section 7 registers both.

## 7. Measurement

No benchmark: the row changes no kernel and no runner. The predictions, registered on 27
November 2026 before any build:

1. **The demonstration's round 1** after parts A1, B, C, D, E and F counts 6, 4, 3, 2, 1 and 0
   files, the lists of 4.10, and its round 2 two more each time. Scored by each part's results
   file. Yes or no a part.
2. **Part C adds no time** to `VarkaEmittedBytesSuite`: within 10% of the suite's time on
   the same runner class before it (about 445 s on a GitHub runner by
   `sql/varka/ci/suite-times.tsv`). The calibration table (`python3 dev/varka_calibration.py`)
   has no row for the same work recorded another way, so the ratio is 1. Scored by the Build's
   time of the suite on part C's pull request against master's.
3. **The tool's cost a test JVM** is under 4 s: a prediction of 2 s for one process over
   about 2,700 files, at the table's ratio of 2 for a first estimate of a new suite's fixed
   cost (VARKA-243). Scored by the time of `VarkaRowFilesSuite`'s test "a view's path is the
   file under sql/varka, and it is there" in the Build's JUnit report on part B's pull request.
   The same number is a checkout's added time through the hook. Over 10 s is a finding, with
   this remedy as a part of its own: `views` first compares the newest modification time under
   `sql/varka/rows` with the views' and returns when the views are newer.
4. **Every view is byte for byte the file it replaces** (part B). Yes or no.
5. **The Python rendering of the docs' two blocks equals the suite's** on the master part D is
   built on, as it does on the plan's commit. Yes or no, by part D's command 2.
6. **Each part is under its estimate times one and a half**, and under 900 counted lines.
7. **The pilot's second round** (row 659: three sibling casts in flight together) merges with
   no conflict in any file this row moves and with no commit that only regenerates; it
   conflicts, if at all, in the census's five files. Yes or no, read from the three pull
   requests' histories.
8. **Of the first ten fan-out pull requests**, none needs a merge of master to resolve a
   conflict in a file this row moves, and at most three need one for the census's files.
   Scored from row 658's ledger and the pull requests; the second number sizes the new row.

## 8. Who builds it

Every part at full strength. The shared-file fixes are the foundation that the fan-out stands
on (`m9/PLAN.md` 1.4, wave 0 and wave 1), and G5 is about the fan-out's tasks, not this row's:
"After the foundation, each fan-out task is one task plan from a template". Parts B, C and E
edit the three suites every pull request runs, and a fault there turns master red for every
open branch. Part A1 is small enough for a cheaper model, and the sheets are written so that
one could build any part; the owner decides, and the default is full strength.

## 9. Risks

1. **A view is stale or missing and nothing says so.** After `git reset --hard`, and in a
   clone whose hooks were never installed, no hook runs. Held by: the test JVM and every
   Python tool write the views before they read them, so a stale view is read only by a grep
   or an editor, and a missing one is an error there; `AGENTS.md` says to run `--views` by
   hand.
2. **Two test JVMs of one worktree write the views at once.** Held by: each write is a rename
   of a whole file, and both write the same bytes; a regenerating suite hands its text to the
   tool and never reads the view back (4.3).
3. **A runner's test JVM has no `python3`.** Every workflow that runs a test JVM is on a
   GitHub-hosted Ubuntu or macOS image (fact 31), which has it. Held by part A2: its premise 8
   lists the workflows and their images, its table says which step to add where an image is
   another, its step 4a checks the Maven workflow's three images, and `VarkaRowFilesSuite`'s
   first test fails in the Build and in the three dispatched workflows before any suite
   depends on the tool. If it fails on an image that the table's
   `setup-python` step cannot fix, part B does not start, and the stop reports the image.
4. **Two branches insert rows at two places of one family of the Scala table.** The `"after"`
   links merge right (4.2). At one place they conflict, in the Scala source too. Row files have
   neither problem; `AGENTS.md` sends new rows there.
5. **A row's bytes depend on code that another branch changes.** Then both branches are green
   alone and the merged master is red in `VarkaEmittedBytesSuite`, with the row named. No
   layout prevents this; row 590's Build on the merge with master catches it before the merge,
   and `m10/SCOPE.md` item 121 allows it: "unless master moved the branch's own family".
6. **Between part C and part D a clean merge can leave master red.** After part C two
   row-adding branches of two families no longer conflict in the generated files, and both
   still write the docs' counts: two that read one type merge with no conflict and leave a
   count one short, which `VarkaCoverageSuite` and `dev/varka_census.py --check` fail on
   master. Held by a step, not a habit: part C's step 10 has part D built on part C's branch
   and green before part C is merged, and the lead merges the two one after the other with
   no pull request that adds a coverage row between them.
7. **The docs page's tables fall behind.** By design between two render commits (4.5). Held
   by the warning in every docs job, by `dev/varka_ops/AGENTS.md`'s rule and by the close.
   The owner decided for the page with a render commit (section 13).
8. **Open branches at part B.** Each branch that regenerated the three files conflicts with
   their removal once. Held by part B's recipe in its description (take master's side, run
   the regeneration) and by landing part B when few type branches are open: right after row
   656's part B, before wave 2 starts.
9. **The push check no longer holds the records** (4.3). A stale record reaches the Build
   and fails there. Held by the Build; a later row may add the records to R3's set.
10. **About 2,700 small files** make a regeneration after an emitter change a diff of every
    bytes record. The records are `linguist-generated`, so GitHub folds them and the size
    check skips them; `git` stores them as it stores any text.
11. **The census's files still conflict** for siblings that claim neighbouring entries (4.9).
    It is a conflict GitHub shows, never a stale file. Held by the new row; until then by the
    one-at-a-time merge of such siblings.
12. **A type row changes the support table's shape** (a new type's line, a new column of a
    cast matrix). It may run `python3 dev/varka_census.py --docs` in its own pull request: it
    is one full-strength row at a time, not a fan-out task. Section 12 names the two plans
    this touches.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md`:

- "**A pull request stays under 1000 changed lines** of hand-written files - source, plans and
  notes, and hand-curated data such as `census_curated.txt` - added plus removed against its
  merge base. Generated and results files, the papers under `sql/varka/papers/` and the
  `READING_*.md` notes do not count." A row file and a skip file are hand-written and count;
  a record does not.
- "**Check before merging that a pull request cannot turn master red.** If master moved since
  its last green CI and the pull request commits generated records or test-time-sensitive
  changes, merge master, regenerate and rerun before merging." Parts B and C commit generated
  records: merge master and regenerate right before the merge.
- "**A comment describes the code as it is.**" No "used to", "the old", "since VARKA-<n>" in
  a comment: each sheet gives the new text of a comment its edit makes false.
- "**Never `git stash`.**" and "**Never kill by name pattern**".
- "**A new catalyst suite whose helpers are Java is a JUnit 5 class under `@VarkaSuite`**":
  `VarkaRowFilesSuite`.
- "**Review your own diff before opening the pull request**".

From the repository's root `CLAUDE.md`:

- "Never remove, skip or weaken a failing test (`ignore`, `assume(false)`, `@unittest.skip`,
  or an expected value or golden file updated just to match), or mock the code under test,
  unless asked." Part D removes two checks on purpose, the suite's comparison of two docs
  blocks and the census check's failure on the support table, and replaces them by `--docs
  --check` and a warning; part B changes three cases of the merge driver's self-test to the
  files that are still its own. Each says so in its description.
- "Every new text file that `dev/.rat-excludes` does not already match needs the Apache
  License header, copied from a neighboring file of the same type and placed after the `#!`
  line of an executable script".
- "Keep source lines within 100 characters".

What not to do: do not edit a record by hand; do not commit a view; do not edit the generated
blocks of `docs/sql-varka.md` in a pull request that does anything else; do not move a row of
the Scala table to a file; do not touch a census file but `dev/varka_census.py`'s last lines
in part D; do not change what any suite compares, but for part D's blocks and part C's
digests; do not run a JVM outside the heavy lock; do not push before the sheet's commands
pass.

## 11. Edits to other plans

Three groups, by who makes them and when. Every old text below was matched on master
`1d68f7d8d89`; "once" means `grep -c` of it there prints 1.

### 11.1 Made by this plan's own pull request

All in `sql/varka/plans/m9/PLAN.md`.

1. Section 3, row 653, three texts, each once in the file:

| old | new |
| :--- | :--- |
| `one file an entry. **Filed** (25 November 2026; the admission check:` | `one file an entry. **Planned** (`VARKA-653.md`, 27 November 2026: seven parts, the demonstration first; the three generated JSON files become one record a row with views that git ignores, each option arm one digest a row, three blocks of the docs page rendered in a commit of their own, and a new coverage row and a new skip each a file; the parts that move a list wait for rows 654 and 656; the plan measures 41 of 50 consecutive row-adding commits in conflict; by the owner's word of 27 November 2026 the census's files left the row for row 696, and the done-when below is the one the row meets, which the owner signed the same day; filed 25 November 2026; the admission check:` |
| `**Done when** three branches that each add a sibling entry of one family, as the pilot's second round will, and two that add entries of different families, merge with master in either order under `git merge-tree` with no merge driver, with no conflict and no regeneration owed; a stale generated file fails CI with its command; and every tool and suite that reads the lists gives the same output before and after` | `**Done when** three branches that each add a sibling coverage row of one family, with its generated records and its matrix skip, and two that add rows of different families, merge with master in either order under `git merge-tree` with no merge driver, with no conflict in those files and with no record to regenerate; the three generated JSON files are no longer committed, and a missing record fails CI's docs job and a record that is not what the compiler gives fails the Build's suite, each with its command (the width audit's content on the laptop alone, as before); the docs page's row tables are written by no such branch, are refreshed by a render commit, and a page that is behind warns and never fails; and every tool and suite that reads the lists gives the same output before and after, but for the option arms' digests and the two-lane census's header, which change once and on purpose. What a branch writes for the census (its entry's line of the curated file, the census's line, the cast tables) is row 696's` |
| `\| `m10/SCOPE.md` item 121, its part of 11 November 2026; row 546 \| medium \|` | the same with `large` for `medium` |

   In the table a `\|` stands for `|`.
2. Section 3: a new line after the line of row 695, the table's last on that master. The
   number is the next free one, 696 at the time of writing; if another plan has taken it, the
   next free number, and every `696` of this plan with it.

        | 696 | A census claim, a cast pair's probe lines and the census's rendered files, one file an entry. **Filed** (27 November 2026, split from row 653 by the owner's word; the admission check, from `VARKA-653.md` 3 and 4.9: after row 653 two branches still conflict in `census_curated.txt` and `census.tsv` where they claim one entry or neighbouring entries (1 of 14 and 1 of 16 consecutive row-adding commits that both changed them; 2 and 5 of 194 replayed merges that conflict), three sibling casts would on neighbouring lines of the cast tables (0 of 3 measured), and round 2 of `dev/varka_rows_demo.py` shows three siblings in conflict in the two census files) `VARKA-653.md` appendix A holds design notes that bind no one. The row edits `dev/varka_rows_demo.py`: a cast among round 2's five branches, and round 2 deciding the exit status with round 1. **Done when** round 2 of that script prints no file in conflict or stale, with a cast among its branches; a newly fused type for an entry is a file of its own and no edit of the curated file; no row-adding branch changes a line of `census.tsv`, `casts.tsv`, `casts_fused.tsv` or `casts_suites.tsv` that another row-adding branch changes; and every reader of those five files gives the same output before and after | the owner's word, 27 November 2026; `VARKA-653.md` 4.9 and appendix A; rows 654 (its parts a and b built), 656 (its part B) and 653 first | medium |

3. Section 3.1. Old, once: `| G5 cheaper models | 653, 658, 659, 590, `. New:
   `| G5 cheaper models | 653, 696, 658, 659, 590, `.
4. Section 4, wave 1. Old, one line of the file, once:

        and 656; row 653's code; row 658; and row 680, whose findings before the close would fall on

   New, two lines:

        and 656; row 653's code, its parts that move a list after row 656's part B
        (`VARKA-653.md` 12); row 658; and row 680, whose findings before the close would fall on

5. Section 4, wave 2. Old, one line, once: `659); then the fan-out that reads no zone: row
   672, row 667 and the first 16 pairs of row 669.` New: the same line, and after it the line
   `Row 696 (the census's files, split from row 653), behind rows 654, 656 and 653, and before
   the pilot's second round where it is ready by then.`
6. Section 2.6. After the line that is exactly `is not enough.`, the last line of 2.6's last
   paragraph ("Row 653's rule: ..."; `grep -c -x 'is not enough\.'` prints 1; the words also
   end a sentence inside a line of 2.2, which is not that line), add an empty line and:
   `*27 November 2026:* `VARKA-653.md` is the plan. The three generated JSON files stay where
   a reader opens them, as views that git ignores, and what is committed is one record a row;
   the coverage table and the skip list keep what they hold and take a new entry as a file;
   the docs' row tables are rendered in a commit of their own. By the owner's word the
   census's files are row 696's.`, wrapped within 100 columns.
7. `sql/varka/plans/m9/VARKA-654.md`, one sentence, for a builder of row 654 who finds this
   row's part D merged first, which part B's premises 3 and 3a are there to prevent. After
   its 4.2's block "5. `main`, three insertions:" (the code block that ends `errors +=
   reader_errors()`), add the paragraph: `*27 November 2026, from `VARKA-653.md` 5.5:* if
   `main` already holds the line `ap.add_argument("--docs", ...)` after the `--self-test`
   line, the `--milestone` argument goes after the `--docs` line; and in the docstring the
   `--milestone 8 --summary` line goes after the `--summary` line whatever stands above it.`
   Old anchor, once in that file: `        errors += reader_errors()`.

### 11.2 Made by part B, when the layout changes

Not by this plan's own pull request: until part B merges, the commands of those plans are
right as they stand, and a builder who runs them before it must find them unchanged.

| plan | lines the part's `sed` changes on master `1d68f7d8d89` | what changes |
| :--- | ---: | :--- |
| `VARKA-657-PARTS.md` | 4 | three `git diff` commands over the three JSON files; the status list of its regeneration |
| `VARKA-678.md` | 3 | three commands |
| `VARKA-682.md` | 3 | three commands |
| `VARKA-683.md` | 1 | one command |
| `VARKA-684.md` | 3 | three commands |
| `VARKA-685.md` | 1 | one command |
| `VARKA-686.md` | 3 | three commands |
| `VARKA-687.md` | 1 | one command |
| `VARKA-688.md` | 4 | four commands |
| `VARKA-690.md` | 5 | two commands, the second keeping `sql/varka/fuzz`; three lines of its two status lists |

In each, a `git diff` command's three JSON paths become `sql/varka/rows` and the command
gains `git add -N sql/varka/rows &&` before it; a status list names "the rows' records under
`sql/varka/rows`" for the three files. The `sed` program, its command and the dated head line
of each file are 5.3's step 11. `VARKA-655.md`, `VARKA-660.md` and `VARKA-679.md` hold no
such line; `VARKA-654.md` and `VARKA-656.md` are built before part B and are not rewritten.

### 11.3 Made by part D, when the page is rendered apart

For the same reason not by this plan's own pull request.

| plan | lines changed | old (once in the file, with part B's edit where it applies) | new |
| :--- | ---: | :--- | :--- |
| `VARKA-657-PARTS.md` | 3 | `` `python3 dev/varka_census.py` rewrites the census, the cast table and the docs' support table; `--check` verifies ``; `` writes `sql/varka/coverage.json` and the table of `docs/sql-varka.md`. ``; the status list's `` , the census file and `docs/sql-varka.md` `` | the census and the cast table, with the support table left to the render commit; the coverage records, with the page's table left to the render commit; the status list without the page |
| `VARKA-690.md` | 2 | the ends of its two status lists: `` `$M9/census.tsv` and `docs/sql-varka.md`. `` and the line `` `docs/sql-varka.md`. `` after `` `$M9/census_curated.txt`, `$M9/census.tsv` and `` | each list ends `` and `$M9/census.tsv`, and not `docs/sql-varka.md`. `` |
| `VARKA-660.md` | 3 | `` \| 9 \| `python3 dev/varka_census.py > /dev/null` \| `` | `` \| 9 \| `python3 dev/varka_census.py > /dev/null && python3 dev/varka_census.py --docs` \| `` |
| | | on the line that starts `` \| `docs/sql-varka.md`, the support table \| ``: `` \| `python3 dev/varka_census.py`; 9 \| `` | `` \| `python3 dev/varka_census.py --docs`; 9 \| `` |
| | | on its planted fault's line: `` and the same for `docs/sql-varka.md` \| `` | `` ; `docs/sql-varka.md` is not checked: a line says its support table is behind the census \| `` |

The `sed` program and its command are 5.5's step 9a, with a dated head line for
`VARKA-657-PARTS.md` and `VARKA-690.md`. Row 547's plan and status are not edited by this row
(4.4).

## 12. Seams with the plans beside this one

**Rows 654 and 656: the order, and why neither plan needs an edit.** Both plans stop if this
row's layout merged first: `VARKA-654.md` 5.1's premise 4 counts five census and cast files,
`VARKA-656.md` 5.1's premises 3 and 4 hold the curated file, `casts_suites.tsv` and the
coverage table as single files, and its part B expects a `git status --short` of 41 lines that
names `sql/varka/coverage.json`, `sql/varka/emitted_bytes.json` and `sql/varka/width_audit.json`.
So:

| this row's part | merges | the premise that holds the order |
| :--- | :--- | :--- |
| A1, A2 | any time after the tag `varka-m8`; beside rows 654 and 656 | none needed: they add `dev/varka_rows_demo.py`, `dev/varka_rows.py`, two Java test files, six lines of `modules.py` and one step of the workflow |
| B, C, D, E, F | after row 654's parts a and b and after row 656's part B | part B's premises 2 and 3: `grep -P "^TIMESTAMP\t" sql/varka/types.tsv \| cut -f3,5` prints `ltz` and `on_lane` (it prints `-` and `declined` today, and `ltz` and `owed` after row 656's part A), and `test -f sql/varka/plans/m9/census_curated.txt` |

Parts A1 and A2 touch nothing those plans' premises read. `VARKA-654.md` 5.1 greps `dev` for
`plans/m8/(census|casts)` and for `"plans", "m8"`: the demonstration names the census folder
as `f"sql/varka/plans/{m}/census_curated.txt"` and the tool names no census path, so neither
grep gains a line. Its premise 4 lists `sql/varka/plans/m*/census*` and `.../casts*`: the
results file is `m9/results/VARKA-653-demo.txt`, which neither pattern matches. Row 656's
parts C and D come after its part B and may be built after this row's part B: their sheets
read the three JSON files only by path, and the views are at those paths in any worktree with
the hooks installed (4.3).

**This row does not touch the census's files.** The curated file, `census.tsv`, `casts.tsv`
and the two cast tables are row 654's to move by milestone, and after that row 696's. The one
thing this row changes in `dev/varka_census.py` is part D's five edits (4.5; 5.5, step 6).
Two of them sit beside insertions of `VARKA-654.md` 4.2 ("`main`, three insertions": the
`--milestone` argument after the `--self-test` line; and its docstring line after the
`--summary` line). The order is fixed: row 654's two parts are built before part B (part B's
premises 3 and 3a), so before part D; part D's premise 14a prints which state the script is
in, and its step 6 applies to both. Section 11.1's edit 7 tells row 654's builder where its
insertions go if the order is ever broken.

**The plans beside this one**, each read at `/home/max/varka-ops/m9-row-plans/`:

| plan | what it says of this row | what holds |
| :--- | :--- | :--- |
| `VARKA-655.md` 11 (the baselines) | "nothing of its layout: this row appends to no coverage list, census file, cast table or skip list, and regenerates nothing. It edits one cell of `m9/PLAN.md`'s table" | so: no seam. It merges before row 656's part B, before this row moves a list |
| `VARKA-660.md` 11 (the precisions; row 661 builds its census edits) | "Assumed: when row 661 starts, the curated file is either one file, as today, or row 653's one file an entry with the same seven fields a line. [...] and `casts.tsv`'s 20 lines are written where row 653 puts a cast pair." | the first case: this row leaves the curated file and `casts.tsv` single files, and a cast entry's curated line stays a hand edit. Its plan needs three lines changed once part D has merged, since the census run no longer writes the support table; part D makes them (11.3). If row 696 lands before row 661, the seam is that row's to state |
| `VARKA-690.md` 11 (the two `ToInstant` records) | "it may move the coverage rows or the curated census to one file an entry. The content of a step is the same and its home is the one B0 prints" | its coverage rows may stay in the Scala table or be row files; part B rewrites its two closing `git diff` commands and its two status lists (11.2), and part D takes the docs page out of those lists (11.3) |
| `VARKA-657-PARTS.md` 5.0, merged | "Rows 653, 646 and 654 may have moved a list to one file an entry or to milestone 9's folder; where B0 shows that, the content of a step is the same and its home is the one B0 prints." | its sheets add rows to the table, which still works; parts B and D rewrite its commands and its status list (11.2, 11.3) |
| `VARKA-678.md`, `VARKA-682.md` to `VARKA-688.md`, merged | their sheets end with a `git diff` over the three JSON files | part B rewrites those commands (11.2) |
| row 658 (the template), no plan yet | - | it names, for a task of one to four units: one row file a coverage row, at most one skip file, the census edit as row 654 has it until row 696, and the command `dev/varka_regen_generated.sh --all`; it rewrites `ADDING_AN_EXPRESSION.md` step 7, which this row leaves; it says where a task's compile tests go |
| milestone 8's row 646 | - | it moves the task table; this row does not touch `PLAN.md`'s rows but its own status |
| row 642 | - | it owns `VarkaEmitOptions` and the option suites' lists; part C touches one comment line of `VarkaEmitOptions.java` |
| row 590 | - | its Build on the merge with master is what catches risk 5 |

## 13. The owner's decisions on this plan's questions

Nothing is open. Each question of the plan's second version is decided, and the plan is
written on the decision.

| question | decided | by whom, when |
| :--- | :--- | :--- |
| 1. The row's done-when, rewritten to what the row meets (11.1, edit 1) | signed | the owner, 27 November 2026 |
| 2. The docs' support table rendered apart in part D, although `dev/varka_census.py` is otherwise row 654's and row 696's (4.5) | yes: without it this row would let two row-adding branches merge with no conflict and leave master's census check red | the owner, 27 November 2026 |
| 3. The docs page's row tables stay in the page and are refreshed by a render commit, not taken out of the page (4.5) | yes | the owner, 27 November 2026 |
| 4. The push check gives up the three files; the Build holds every record (4.3, risk 9) | yes | the owner, 27 November 2026 |
| 5. No new row now for the per-row lists this row leaves (`VarkaProofFiles`, `VarkaDomains`' fixtures, `proofs/README.md`); file one if section 7's prediction 8 fails in one of them | no new row | the lead, on this plan's recommendation, 27 November 2026; the owner did not object |
| 6. The 868 rows of the Scala table stay there; only new rows are files | they stay | the lead, on this plan's recommendation, 27 November 2026; the owner did not object |
| The split of the row: the census's files leave it for row 696 | split | the owner, 27 November 2026 ("try to split") |
| No limit on any file's length; row 547's status left to its own row; the closing part merged into part F | as written | the lead, 27 November 2026 |

## 14. Outcome

To be written when the row closes. Each part adds a dated paragraph here (5.0).

## Appendix A. For the new row's plan: design notes on the census's files

*These notes bind no builder of row 653. They are what this plan's first version designed
for the census's files before the owner split the row, kept for whoever plans row 696 after
row 654's parts a and b are built. Nothing below was checked against row 654's code, which is
not on master; the two code blocks were run on milestone 8's files.*

**A claim.** `sql/varka/plans/m<n>/census_claims/<slug(entry)>.<type>.claim`, lines of `key:
value`: `entry` (the entry's name in the curated file), `fused` (one census type, with `~` or
`^` as the curated file writes them), `row` (the task's number), `note` (optional).
`dev/varka_census.py` folds each claim into its entry's parsed line before the entry's rules
run. An entry that took a claim and is then fused over every type it admits has the need
`done` whatever the curated line says, since two tasks that each fuse one of an entry's last
two types cannot each write `done` (the rule `(need == "done") != (set(f) == set(t))` in
`row`). The reader and the fold, with a doctest that passed:

        CLAIMS = MILESTONE.path("census_claims")


        def read_claims(root=ROOT):
            """The claims under census_claims (VARKA-653.md 4.9), by file name, each (file, entry,
            fused, row, note), and an error for each file there that is no claim."""
            top = os.path.join(root, CLAIMS)
            claims, errors = [], []
            for name in sorted(os.listdir(top)) if os.path.isdir(top) else []:
                if not name.endswith(".claim"):
                    continue
                with open(os.path.join(top, name), encoding="utf-8") as f:
                    lines = [x for x in f.read().splitlines() if x.strip() and not x.startswith("#")]
                fields = dict(x.partition(": ")[::2] for x in lines)
                keys = set(fields)
                if len(fields) != len(lines) or not {"entry", "fused", "row"} <= keys <= {
                    "entry",
                    "fused",
                    "row",
                    "note",
                }:
                    errors.append(f"{CLAIMS}/{name}: a claim is the lines entry, fused, row and note")
                    continue
                fused = fields["fused"]
                want = f"{varka_rows.slug(fields['entry'])}.{fused.rstrip('~^')}.claim"
                if name != want:
                    errors.append(f"{CLAIMS}/{name}: a claim of {fused} for {fields['entry']} is {want}")
                    continue
                claims.append((name, fields["entry"], fused, fields["row"], fields.get("note", "")))
            return claims, errors


        def fold_claims(rows, at, claims, errors):
            """Folds each claim into its entry's parsed line, where `rows` holds the entry: the type
            joins the fused types at index `at`, the row the rows at `at + 2`, the note the note at
            `at + 3`. Returns the entries that took a claim.

            >>> rows = {"year": ["year", "date,ntz", "int", "date", "plumbing", "340", "a note"]}
            >>> errors = []
            >>> claims = [("f", "year", "ntz", "668", "by the zone node"), ("g", "year", "date", "1", "")]
            >>> sorted(fold_claims(rows, 3, claims + [("h", "month", "ntz", "668", "")], errors))
            ['year']
            >>> rows["year"][3:]
            ['date,ntz', 'plumbing', '340,668', 'a note; by the zone node']
            >>> errors
            ['sql/varka/plans/m9/census_claims/g: the curated line of year already holds date; \
        delete the claim']
            """
            claimed = set()
            for name, entry, fused, row, note in claims:
                line = rows.get(entry)
                if line is None:
                    continue
                held = [p for p in line[at].split(",") if p]
                if line[at] == "matrix":
                    errors.append(f"{CLAIMS}/{name}: {entry} is a cast entry, fused by the matrix")
                elif fused.rstrip("~^") in [p.rstrip("~^") for p in held]:
                    errors.append(
                        f"{CLAIMS}/{name}: the curated line of {entry} already holds "
                        f"{fused.rstrip('~^')}; delete the claim"
                    )
                else:
                    line[at] = ",".join(held + [fused])
                    numbers = [p for p in line[at + 2].split(",") if p]
                    line[at + 2] = ",".join(numbers + ([row] if row not in numbers else []))
                    line[at + 3] = "; ".join(p for p in (line[at + 3], note) if p)
                    claimed.add(entry)
            return claimed

In `build()`, after its `errors = []`: read the claims, fold them into `curated` at index 3
and into the implicit entries by name at index 5, report a claim for an entry the file lacks,
and pass `claimed=name in claimed` to both calls of `row`.

**The census's two rendered files.** `census.tsv` and `casts.tsv` are what the script renders
from committed inputs in about a second; as views that git ignores they cannot conflict. The
JVM readers go through row 654's `VarkaCensusFiles.of`, which has no once-a-JVM step today
and would need one. Two cautions found in review: a `--views` mode must write the files even
when a rule fails, since the suite that regenerates the cast probe reads `casts.tsv` first;
and it must never make a test JVM fail to start.

**The cast probe.** `casts_fused.tsv` and `casts_suites.tsv` as one record a pair of types,
`<from>-<to>.fused.tsv` and `<from>-<to>.suites.tsv` with `none` for a `-` target. Milestone
8's two files come back byte for byte from this split, the probe's in the registry's type
order and the suites' sorted as text:

        CASTS = {
            "casts_fused": ("casts_fused.tsv", ".fused.tsv"),
            "casts_suites": ("casts_suites.tsv", ".suites.tsv"),
        }


        def census_folder(tree_root):
            """The plans folder of the current census: the highest milestone that has a curated file."""
            plans = os.path.join(tree_root, "sql/varka/plans")
            found = [d for d in os.listdir(plans) if re.fullmatch(r"m\d+", d)]
            found = [d for d in found if os.path.exists(os.path.join(plans, d, "census_curated.txt"))]
            return "sql/varka/plans/" + max(found, key=lambda d: int(d[1:]))


        def census_order(tree):
            """The census's type names in the type registry's order (sql/varka/types.tsv)."""
            lines = [x for x in tree.read("sql/varka/types.tsv").splitlines() if x and x[0] != "#"]
            at = lines[0].split("\t").index("census")
            return [x.split("\t")[at] for x in lines[1:] if x.split("\t")[at] != "-"]


        def split_casts(kind, text):
            """A cast table's records, as {file under casts/: text}: its header, and one file a pair
            of types with the pair's lines in the table's order. A `-` target is written `none`."""
            name, suffix = CASTS[kind]
            lines = text.splitlines(keepends=True)
            at = 0 if kind == "casts_fused" else 1
            out = {f"_{name}": lines[0]}
            for line in lines[1:]:
                fields = line.rstrip("\n").split("\t")
                pair = f"{fields[at]}-{'none' if fields[at + 1] == '-' else fields[at + 1]}"
                out[pair + suffix] = out.get(pair + suffix, "") + line
            return out


        def assemble_casts(kind, tree, folder):
            """A cast table from its records: the probe's by the registry's type order, first the
            source and then the target; the suites' record sorted as text, as its suites write it."""
            name, suffix = CASTS[kind]
            top = f"{folder}/casts"
            header = tree.read(f"{top}/_{name}")
            if header is None:
                return None
            names = [n for n in tree.names(top) if n.endswith(suffix)]
            records = {n[: -len(suffix)]: tree.read(f"{top}/{n}") for n in names}
            if kind == "casts_suites":
                lines = [x for t in records.values() for x in t.splitlines(keepends=True)]
                return header + "".join(sorted(lines))
            order = census_order(tree)

            def place(pair):
                source, target = pair.split("-")
                return order.index(source), order.index(target)

            return header + "".join(records[p] for p in sorted(records, key=place))

`dev/varka_rows.py`'s `Revision` reads only `sql/varka/rows`; a view assembled at a revision
from another folder needs it to load that folder too.

**What the notes leave open, and row 696's plan must settle.** How a cast entry (`cast`,
`try_cast`, the aliases), whose fused types are the matrix's, takes a task's number and note
with no edit of its curated line: row 660's design has row 661 edit cast entries' lines by a
script. Whether `READERS` of `VARKA-654.md` 4.2 still names a file that no longer holds the
milestone's path. Whether a claim is folded into the curated file at a milestone's close.
And the demonstration's round 2, which today has no cast among its five branches.
