# Adding an expression to Varka

How to teach Varka one Spark function (a "port"), in the order the code runs: a lookup that says
whether the function is yours to port now (step 0), ten steps that each end in a check, then the
regeneration and the closing flow. Words such as *lane*, *word* and *morsel* are defined in the
`## Glossary` section of `docs/sql-varka.md`; read that section alone, not the page.

Every `build/sbt` command and every `dev/` script marked **JVM** starts a JVM and takes minutes;
JDK 25 is required. The rest are Python or `grep` and take seconds. An sbt command is done when
its last lines read `[success]`; `[error]` lines name what failed.

Where the code is:

- `C` = `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/`: the
  compiler families, `VarkaNodeCompiler.java` and the `Varka*Compiler.java` it chains.
- `C/varka/`: the IR (`VarkaVectorIR.java`), the emitter and the type registry.
- `sql/catalyst/src/test/{java,scala}/.../codegen/` and `.../codegen/varka/`: the catalyst suites.
- `sql/core/src/test/{java,scala}/org/apache/spark/sql/execution/`: the suites that need a session.

## Step 0. Is this function yours to port now?

Do this before any edit. The function's name is `<f>` below; the commands start no JVM.

1. **Does it fuse already?** `grep -n '"sql" : ".*<f>(' sql/varka/coverage.json`. Each row has
   its SQL text, its `operands` (`Class:type`, so `Hour:ntz` is `hour` over `TIMESTAMP_NTZ`) and
   the `columns` map at the top names each column's type. A row with your function over your type
   means the work is done: stop and report.
2. **What does the census say?** `awk -F'\t' '$1=="<f>"' sql/varka/plans/m8/census.tsv`. The
   columns are name, class, section, scope, reason, types, result, fused_today, new_lanes, need,
   rows, note, fused_in_part. Scope `in` with your type under `types` and not under `fused_today`
   is open work, and `rows` holds its plan rows. Scope `out` means a decision excluded the
   function, and `reason` says which. `census.tsv` is generated: never edit it. Its inputs are
   the hand-edited `sql/varka/plans/m8/census_curated.txt` (the `in` entries) and the exclusion
   tables `SECTION_EXCLUSIONS` and `NAME_EXCLUSIONS` in `dev/varka_census.py` (the `out` ones);
   `python3 dev/varka_census.py` rewrites it and `python3 dev/varka_census.py --check` (prints
   nothing, exit 0) says it is current.
3. **Which plan row owns it?** With row numbers from the census:
   `grep -n '^| <n> |' sql/varka/plans/m*/PLAN.md`. With none, search by name:
   `grep -nw '<f>' sql/varka/plans/m*/PLAN.md sql/varka/plans/m10/SCOPE.md`, and if no table row
   names it, `grep -rnw '<f>' sql/varka/plans/m9 sql/varka/plans/m10`: a row often names a group
   of functions, and the row plans (`VARKA-<n>.md`) list the functions with the row's number.
4. **Read the row.** A table row is `| number | text | provenance | size |`. The first bold word
   of the text is the state: `Filed`, `Planned`, `Started`, `Done in part`, `Done`, `Withdrawn`
   or `Moved to milestone <n>` (then the row lives in that milestone's table). The provenance
   cell ends with what the row waits on, written "rows X and Y first". Print the state of each
   row it names: `grep -E '^\| (X|Y) \|' sql/varka/plans/m*/PLAN.md | grep -oE
   '^[^:]+:\| [0-9a-z]+ \|[^*]*\*\*[^*]+\*\*'`.

**The rule. Stop and report, and do not port, when any of these holds:**

- a row the function's row waits on is not `Done`;
- the row is in a milestone that has not started. Milestone 8 (`plans/m8/PLAN.md`) is the open
  one. Milestone 9's rows are planned and not started: row 708 of `m8/PLAN.md` says no kernel of
  milestone 9 is built before that row is done;
- the row is `Done`, `Withdrawn` or moved to the scope catalogue (`m10/SCOPE.md`);
- the census says `out`. A reason that looks wrong for your type is a finding to report, not a
  permission: a later row may already plan to change it;
- no row owns the function. New work gets a row first, and the owner files it.

Otherwise the row is yours when you were given it. Start it with
`dev/varka_task_new.sh <n> "<title>"` (no JVM): it makes the worktree `../varka-<n>` on branch
`varka-<n>` and the plan file `sql/varka/plans/m<k>/VARKA-<n>.md`, and refuses when that branch or
file exists. The first commit is the plan.

## The lanes

A kernel computes on vector lanes of one width. The lane of each type is the `lane` column of
`sql/varka/types.tsv` (generated from `C/varka/VarkaTypes.java`); `ADDING_A_TYPE.md` is the path
for a type that has none.

| SQL type | Lane | Where its rules are written |
| :--- | :--- | :--- |
| `INT`, `DATE`, year-month interval | `INT`, 32 bits | `skills/calendar-algorithms.md` |
| `BIGINT`, `TIMESTAMP_NTZ`, `TIME`, day-time interval | `LONG`, 64 bits | below; (1) |
| `DECIMAL` to precision 18 | `LONG`, one word: the unscaled long | (2) |
| `DECIMAL` of precision 19 to 38 | `LONG`, two words (128 bits) | (2), its `Pair` paragraph |
| `TINYINT`, `SMALLINT` | `BYTE`, `SHORT`; their kernels also reach `INT` | (3) |
| `FLOAT`, `DOUBLE` | `FLOAT`, `DOUBLE` | `VarkaFloatingCompiler.java`'s class Javadoc |
| `BOOLEAN` | none: a condition, one bit a row | `ADDING_A_TYPE.md` |
| `TIMESTAMP` (zoned) | declined today | `plans/m9/PLAN.md` row 656 |

- (1) `skills/emitter-and-ir.md`, "A kernel of both lanes is driven at the long lane".
- (2) The class Javadoc of `VarkaDecimalCompiler.java`.
- (3) `skills/testing-and-debugging.md`, "A narrow column read as an int is unsigned".

Two rules of the long lane that the int lane does not have:

- **A division states a proved bound.** `ConstDivide` over a `LONG` child needs a
  `dividendBound` of at most `EXACT_DIVIDEND_BOUND` (2^52). Either the type proves it (a `TIME`
  is under a day), or the arm wraps the child in a `GuardedRange` over the bound, and a batch
  with a value outside it goes to Spark. `VarkaTimeCompiler.dayTimeToLong` is the worked example;
  `VarkaNodeCompiler.floorDivLong` and `truncDivLong` are the helpers. A guarded kernel carries a
  `domain` in step 7.
- **A function declines every input type it does not lower.** Read the Catalyst expression's
  `inputTypes`: one that admits "any timestamp" also admits the zoned and the nanosecond types,
  and the arm matches only the type it handles (`case Hour h when VarkaTypes.isNtz(...)`).

## What a port touches

| File | When |
| :--- | :--- |
| the family's compiler in `C` (step 3) | always |
| `VarkaExpressionCompilerSuite.scala`: what compiles, what declines and why | always |
| `VarkaCoverageSuite.scala`, then the generated files (step 7 and "Regenerating") | always |
| `census_curated.txt`: the function's line, the types fused today | always |
| the row's plan file and the row's state in its `PLAN.md` | always |
| `VarkaVectorIR.java`, `VarkaVectorWalk.java` and the two emitters | a new node (steps 2, 4) |
| `VarkaReference.java` (semantics), `VarkaIrGrammar.java` (the fuzzer) | a new node |
| a file under `sql/varka/proofs/` (`proofs/README.md`) | a new lowering exact over a bound |
| the type's suites (below) | a test of the new kernel's answers |
| a benchmark class and its results files | step 8 |
| `sql/varka/skills/*.md` | step 9 |

The type's suites: `git ls-files | grep 'Varka<Type>.*Suite'`, where `<Type>` is the
`conformance` column of `types.tsv` (`TimestampNtz` gives the `VarkaTimestampNtz*Suite` files).
A new catalyst suite is JUnit 5 under `@VarkaSuite` (`sql/varka/AGENTS.md`, "The suites").

## The path

Run each check before the next step. Steps 2 and 4 are skipped when the arm builds nodes that
exist; steps 8 and 9 are conditional.

**1. Decide the lowering before writing anything.** A kernel has no branches: the arithmetic
must be adds, subtracts, multiplies, shifts, compares and blends on the type's lane. A division
by a constant has a multiply form (`VarkaChronoLowering.emitFloorMod7` on the int lane; the long
lane's rule is above). A branch becomes a mask and a blend. If neither works, declining the
expression is a normal outcome. Check: none. Write the lowering into the row's plan file.

**2. Add the IR node** to `VarkaVectorIR`, a sealed interface of records: a record plus a
`permits` entry. A node holds no literal value, only a literal slot index, so two queries that
differ in constants share one class.
Check (**JVM**): `build/sbt catalyst/Test/compile`. It must fail, and its `[error]` lines list
every `switch` that lacks the node: the work of steps 3 and 4.

**3. Teach the compiler to build it**, in the family that owns the function's kind
(`VarkaTimeCompiler` holds the `TIMESTAMP_NTZ` and `TIME` fields, for one; grep `C` for a sibling
function's class to find the family). Match the Catalyst tree, compile the children, return the
node, or decline with a reason. A wrong decline costs speed; a wrong acceptance, answers.
Check (**JVM**): `dev/varka_emit.sh "<expression>" --columns <name>:<type>`. Done when the
entry's line ends `FusedOutput(<n>)`; `ResidualOutput - <reason>` is the compiler's own decline.
The type spellings are the `columnType` switch of `VarkaEmitDump.java`; `TIMESTAMP_NTZ` has none
today (row 707 of `m8/PLAN.md`), so check such a column by its test instead:
`build/sbt 'catalyst/testOnly *VarkaExpressionCompilerSuite -- -z "<words of the test name>"'`.

**4. Emit it.** The compile errors of step 2 list the sites in the walk and the two emitters
(`ADDING_A_TYPE.md`, "Where to read first", says what each owns).
Check (**JVM**): `build/sbt 'catalyst/testOnly *VarkaEmitter*Suite'`.

**5. Say what its nulls do.** Null when any input is null is the default and needs nothing. A
node that makes a null from valid inputs, or is valid where an input is not, must say so.
Check (**JVM**): `build/sbt 'catalyst/testOnly *VarkaIrFuzzSuite'`. A new node needs its
generator entry and its reference arm first: the suite fails a node type it cannot reach.

**6. Say what its range does.** A lowering exact only inside a range is bounded at compile time
or carries a runtime guard (`GuardedRange`), and a new bounded lowering ships its proof file.
Check (**JVM**): `build/sbt 'sql/testOnly *VarkaDifferentialHarnessSuite'`: the query against
Spark's own answer, with no session.

**7. Add the coverage rows.** A row in `VarkaCoverageSuite`'s `families` list, in the family of
the type, with a `domain` when the kernel can decline a batch (step 6's guard). The suite fails
a guarded row without one; `VarkaCoverageDifferentialSuite` then runs the row inside and outside
the domain, so add fixture rows there when its values do not reach the new bound. Then edit the
function's line in `census_curated.txt` (the types fused today, the need) and regenerate, next
section.
Check (**JVM**): `build/sbt 'catalyst/testOnly *VarkaCoverageSuite'` with no environment
variable, then `python3 dev/varka_census.py --check`. The suite fails while an expression class
the compiler matches has no documented row, so this step cannot be skipped.

**8. Add a benchmark case,** if the function is meant to be fast: a performance claim traces to
a committed results file. `dev/varka_bench_ids.sh <Class>` (**JVM**) prints the taken case ids
and the next free one. A new family gets its own class. `skills/benchmarking.md` says how the
results files are made (on GitHub's runners, through the `benchmark.yml` workflow, once the
branch is pushed) and how to read a move; `git ls-files 'sql/*/benchmarks/<Class>-*'` lists a
class's files.
Check (**JVM**): `dev/varka_emit.sh "<expression>" --table`, the op counts the plan registers.

**9. Write down what it taught you,** if it will outlive the row: a new `##` section in the
right file under `sql/varka/skills/`, then `dev/varka_toc.py SKILLS.md --from sql/varka/skills`.
Check: `dev/varka_toc.py --check SKILLS.md --from sql/varka/skills` exits 0 (no JVM).

**10. Close it out:** "Closing a row" below.

## Regenerating the generated files

`dev/varka_regen_generated.sh --all` (**JVM**) rewrites, in the order they read each other:
`types.tsv`, `fuzz/types.tsv`, the cast tables (`plans/m8/casts_fused.tsv`, `casts_suites.tsv`,
`casts.tsv`), `coverage.json` and the generated blocks of `docs/sql-varka.md`,
`width_audit.json`, `emitted_bytes.json`, and last `plans/m8/census.tsv`.
`python3 dev/varka_generated_merge.py --list` prints the list. Done when the script prints
`varka regen: done`; then read `git diff`, see that only your rows moved, and commit the files.

- **No argument or `--all`.** With no argument the script regenerates only what the merge driver
  recorded as owed by the last merge. After a rebase or a merge of master, and after a change to
  the compiler or the coverage rows, run `--all`. `--all` skips the JVM steps when nothing they
  read changed and says so; `--dry-run` prints the steps and runs no sbt.
- **Never wrap it in the heavy lock.** It starts sbt under the machine's heavy lock
  (`VARKA_HEAVY_LOCK`, else `~/proj/.varka-locks/heavy.lock`) by itself; a caller that holds
  that lock blocks it forever.
- **What it does not cover.** `width_audit.json` is one CPU's record (`host.cpu` in the file):
  on any other CPU the script skips it and prints that it is still owed on its host.
  `operator_census.json` is not in the list: `ADDING_A_TYPE.md` step 12 has its command.
  `SKILLS.md` is step 9's.
- The gate below runs `--all` itself and commits what changed. The map of the code base (row
  706 of `m8/PLAN.md`) lists every generated file.

## Closing a row

The scripts are in `dev/varka_ops/`; `README.md` there is the flow and `AGENTS.md` the rules.
Each prints its usage when run with no argument. `<w>` is the worktree's directory name.

1. **Mark the row, in the row's own pull request.** Fill the plan file's outcome and open the
   row's text in `PLAN.md` with its state in bold (`**Done**`, `**Done in part**` or
   `**Started**`, saying what is left). `python3 dev/varka_plan_lint.py --offline` (no JVM, done
   at `0 finding(s)`) fails a row that has a `[VARKA-<n>]` commit and no state; the gate and
   CI run it.
2. **Commit.** `dev/varka_precommit.sh --install-hook` once per clone installs the hooks.
3. **Gate.** `dev/varka_ops/pre-pr-slot.sh <w>` (**JVM**, long: run it as a background command
   and wait for its exit). It queues `pre-pr.sh`, the one gate: merge master, regenerate, the
   linters, the push check, the sweep of the changed suites. Done at `pre-pr: passed on <sha>`;
   on `pre-pr: FAILED at <step>` read the log it names, fix, commit, gate again. Never push on a
   hand-picked subset of checks.
4. **Review.** `/code-review high` on the branch, one round of fixes for the findings you
   verify, the gate again if that round committed, then
   `dev/varka_ops/mark-reviewed.sh <w> high <findings-file>`.
5. **Open the pull request.** Never `git push` by hand.
   `dev/varka_ops/open-pr.sh <w> <branch> <absolute-body-file> <lock-name> "[VARKA-<n>] <goal>"`
   checks the title and body (`dev/varka_msg_check.py pr`: the headings of
   `.github/PULL_REQUEST_TEMPLATE`, under 1000 counted lines), refuses a head without both
   stamps or a body that does not record the review, pushes to the fork remote and opens the
   pull request on `vecbricks/varka`. `<lock-name>` names a lock file under the ops home's
   `locks/`. A later fix goes out with `dev/varka_ops/push-ci.sh <w> <branch> <pr>`.
6. **The option matrix.** The Build runs the suites at the defaults only. `push-ci.sh` arms the
   waiter that dispatches the matrix after the Build and posts its link on the pull request;
   after `open-pr.sh` start it by hand: `dev/varka_ops/start-waiter.sh <pr> <build-run> <branch>`.
7. **The merge is the owner's.** A code pull request is merged by the owner, not by its author.
   The row is done when it is merged with a green Build and a green matrix.

## Declining is a feature

Every stage may refuse: the compiler an expression, the emitter a shape, the kernel a batch
whose values leave the range it proved. Each falls back to Spark and the answer stays right.
Write the refusal as deliberately as the acceptance, and test it.

*Row 658 of `plans/m9/PLAN.md` adds the fan-out task's sections to this guide.*
