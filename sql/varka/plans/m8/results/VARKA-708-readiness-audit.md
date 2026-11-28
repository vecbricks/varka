# The cold-start audit of 28 November 2026

*Rows 700 to 708 and row 694 of `m8/PLAN.md` come from this audit, and row 708 reruns it. Three agents that had never seen the project each got the brief below and one task, read master `9e7b1612402`, and ran nothing. The three reports follow the brief, as written, with the local worktree path replaced.*

**The tasks.** (1) Make `unix_seconds` over a `TIMESTAMP_NTZ` column fuse. (2) A test fails in one option matrix configuration: find and debug it; then show that a kernel got no slower. (3) Start row 360 of milestone 8; add a boolean emit option `demoOption`; draw the map of the code and the tools.

**The verdicts.** "Mostly" on every task: 1 of 1, 2 of 2 and 3 of 3.

---

## A cold-start audit of the Varka repository (28 November 2026)

You are a new engineer (an AI agent) who has never seen this project. You are given ONE task and
the repository. Nobody is there to ask. Your job in this audit is NOT to finish the task: it is
to find out, honestly, how far the repository's own documents take you, and to log every place
where you had to guess, search, or choose between two texts that disagree.

### The repository

A git worktree, read-only for you: `the repository worktree` (a fork of Apache Spark that
holds "Varka"). Start where any new agent would: the `AGENTS.md` (or `CLAUDE.md`) at its root,
then whatever those files point you to. Do not use any knowledge of this project from outside
the repository; if you know Apache Spark, you may use that.

### How to work

1. Read the entry documents as they tell you to. Follow their pointers. Note how many files and
   bytes you read before you know what to do first.
2. Plan the task as far as you can WITHOUT changing anything: which files you would create or
   edit and where in them, in which order, which commands you would run (exact command lines)
   to build, to run one test, to regenerate what must be regenerated, to check your work, and
   what "done" would be. You do not write code and you do not run builds: you establish that
   you COULD, from the documents.
3. Keep a log while you work. One entry for each of these events, with the file and line that
   caused it:
   - GUESS: you had to assume something the documents do not say.
   - SEARCH: you spent more than about a minute (or more than three searches) finding something
     a document should have pointed to. Say what you searched for and where you found it.
   - CONFLICT: two documents, or a document and the code, say different things. Quote both.
   - STALE: a document names a file, class, command, option or number that the tree does not
     have (check it: `ls`, `grep`).
   - DEAD END: a pointer leads nowhere, or to a text that does not answer the question.
   - OVERLOAD: a document made you read a lot that the task did not need (say how much).
   - GOOD: a place where a document answered exactly what you needed, fast. (These matter too:
     they show what to keep.)
4. For every tool or script the task needs (`dev/...`, `build/sbt ...`), say how you found it
   and whether a document told you its arguments, or you had to read its source or `--help`.

### Hard limits

- Read-only. Do not edit, create or delete anything in the repository. No `git` command that
  changes state (no checkout, commit, stash, branch, worktree).
- Do not run a build, a test, a benchmark or any JVM: no `build/sbt`, no `build/mvn`, no `java`,
  no `javac`, and no script under `dev/` that starts one (if unsure whether a script starts a
  build, read it instead of running it). Python tools that only read are allowed (`--help`,
  `--check`, `--self-test` where the script says it only reads).
- Read nothing outside the worktree. Never print the environment
  (`env`, `printenv`, `set`). Kill nothing. Start no sub-agents. No network.
- Write exactly one file, the report named in your task, and nothing else.

### The report

1. **The task and your verdict in one line:** "From the repository alone I could start this
   task with no question: yes / mostly / no", and the one biggest obstacle.
2. **The path you took:** the documents in the order you read them, with bytes read, up to the
   point where you knew your first action.
3. **Your plan for the task:** files, order, exact commands, what "done" is. Mark each line as
   "from a document (which)" or "my guess".
4. **The log,** grouped by kind, each entry with file and line and a one-line statement of what
   was missing or wrong, and what one sentence or one pointer, placed where, would have fixed it.
5. **The five changes to the documents (not to the code) that would have helped most,** each as
   a concrete proposal: which file, what it should say or point to.
6. **Whether the code's structure itself blocked you** (a class too large to find the place, two
   places that look like the right one, a name that misleads): only what you actually met, with
   file and line; say "no" if the documents were the problem and the code was not.

---

## Audit 1: make `unix_seconds` over a `TIMESTAMP_NTZ` column fuse

Repository read: `the repository worktree` at `9e7b1612402`. Nothing was edited, built or run.

### 1. Verdict

**From the repository alone I could start this task with no question: mostly.**

The biggest obstacle: the documents describe the recipe well but do not say that this task
conflicts with the plan's own state. `unix_seconds` is row 667 in `plans/m9/PLAN.md`, "Filed",
and part of a fan-out whose prerequisites (rows 656, 659, 653, 654, 658) are not built. The census
says the entry is "out" for a reason (LTZ) that does not apply to NTZ. A new agent cannot tell
from the recipe which of two lists to update.

### 2. The path I took, in order

| # | document | bytes | what it gave me |
|---|---|---|---|
| 1 | `AGENTS.md` (root; `CLAUDE.md` is the same file) | 7,078 | Spark's rules. Nothing about Varka except "read every AGENTS.md in the directory and its ancestors". |
| 2 | `sql/varka/AGENTS.md` | 33,018 | Found by guessing `sql/varka`. Names `coverage.json` as the lookup for "is it fused", names `ADDING_AN_EXPRESSION.md`, and lists the tools. |
| 3 | `sql/varka/ADDING_AN_EXPRESSION.md` | 9,392 | The recipe: ten steps, each with a check command. |
| 4 | `dev/varka_ops/INDEX.md` and `AGENTS.md` | 3,976 + 3,936 | Reached through one sentence in the middle of AGENTS.md. Says `pre-pr.sh` is the one gate. |
| 5 | `.agents/pr-workflow.md` | 2,356 | Spark's JIRA and PR-title rules. Not usable here (see log). |

That is about 60 KB over 5 files before I knew my first action (step 1 of the recipe, then
`coverage.json`). I then read, in part, `coverage.json`, `plans/m9/VARKA-654.md`, `-656.md`,
`-658.md`, the headers of `dev/varka_emit.sh`, `varka_gate.sh`, `varka_regen_generated.sh`,
`varka_census.py`, `.agents/code-conventions.md`, and the sources named below.

**Does it fuse today? No.** How I could tell:
- `grep unix_seconds sql/varka/coverage.json docs/sql-varka.md` finds nothing.
- `grep UnixSeconds` over `sql/catalyst/src/main`, apart from its definition and its registry
  entry, finds nothing. Neither `VarkaTimeCompiler`, `VarkaNodeCompiler` nor `VarkaExpressionCompiler`
  mentions it.
- `plans/m8/census.tsv:274` has `unix_seconds ... out ... TIMESTAMP with local time zone: owner's decision, 14 October 2026`.
- The documented live check is `dev/varka_emit.sh "unix_seconds(ntz)" --columns ntz:timestamp_ntz`
  (ADDING step 3). It must print `ResidualOutput` today. I did not run it (it starts a JVM).

### 3. Plan

Steps follow `ADDING_AN_EXPRESSION.md`. Source marks: [doc] means a document says it, [guess] means I inferred it.

**Before any edit**
1. Pre-flight [doc: root AGENTS.md]: `git remote -v`, `git status`, new worktree. [guess] The Varka way is
   `dev/varka_task_new.sh <n> "<title>"` [doc: sql/varka/AGENTS.md, "A task starts with"]. It needs a
   task number; row 667 exists, so I would use 667. [guess] It is unclear whether I may take a row
   marked "Filed" that waits on other rows.
2. `export JAVA_HOME` to JDK 25 [guess: only AGENTS.md says JDK 25; no document says to pin it].

**Step 1, the lowering [doc: ADDING step 1].** `Math.floorDiv(micros, 1_000_000)` on the long lane.
Existing pieces, found in code, not in the recipe:
- `VarkaNodeCompiler.floorDivLong(x, divisor, sink)` (line 1077) and `truncDivLong` (1130).
- `VarkaVectorIR.ConstDivide(child, divisor, dividendBound)` (line 1593). Its Javadoc says a long-lane
  division needs a proved bound of at most 2^52 (`EXACT_DIVIDEND_BOUND`, line 1612).
- `VarkaTimeCompiler.dayTimeToLong` (line 195) is the worked precedent. It wraps the child in
  `GuardedRange(x, -(bound-1), bound-1)`, so a batch outside the bound goes to Spark.
- So the kernel is range-guarded and carries a coverage `domain` (ADDING step 7), because
  micros values above 2^52 (about year 2112) are legal NTZ values.
- Write this into a plan file first: `sql/varka/plans/m9/VARKA-667.md` (does not exist yet) [guess: the name follows `VARKA-<id>.md`
  in AGENTS.md "Plans are records"; the template is `plans/TEMPLATE_TASK.md`, which `varka_task_new.sh` copies].

**Step 2, the IR [doc].** Probably no new node: reuse `ConstDivide` plus `GuardedRange` [guess].
If so, step 2 is skipped. ADDING does not say what to do when no node is needed.

**Step 3, the compiler arm [doc].** Add to `VarkaTimeCompiler.java`
(`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/`), beside
`case Hour h when VarkaTypes.isNtz(...)` (line 325):
`case UnixSeconds u when VarkaTypes.isNtz(u.child().dataType()) -> () -> longOperand(...)` then
`floorDivLong`/`ConstDivide` under a guard. Must decline for a zoned TIMESTAMP and for the nanosecond
timestamp type (`TimestampToLongBase.inputTypes` also admits `AnyTimestampNanoType`) [guess].
Check [doc]: `dev/varka_emit.sh "unix_seconds(ntz)" --columns ntz:timestamp_ntz` reads `FusedOutput`.

**Step 4, emitter [doc].** Probably no change if the nodes exist [guess].
Check [doc]: `build/sbt 'catalyst/testOnly *VarkaEmitter*Suite'`.

**Step 5, nulls [doc].** Default null-in null-out; `UnixSeconds.nullIntolerant = true`
(`datetimeExpressions.scala:1207`). Check [doc]: `build/sbt 'catalyst/testOnly *VarkaIrFuzzSuite'`.

**Step 6, range [doc].** The guard above. Check [doc]: `build/sbt 'sql/testOnly *VarkaDifferentialHarnessSuite'`.
Note the file is under `sql/core/src/test/java/.../execution/`.

**Tests to add [doc, partly].**
- A case in `VarkaExpressionCompilerSuite.scala` (compiles, and declines for the zoned and nano types).
- A case in `VarkaTimestampNtzArithmeticSuite.java` or `VarkaTimestampNtzConformanceSuite.java`
  (JUnit 5 under `@VarkaSuite`) [guess: the closest existing NTZ suites; the recipe's
  "VarkaEmitter<Family>Suite" does not name one].
- A case in `VarkaTimestampNtzSuite.scala` (sql/core, end to end) [guess].
- A generator entry in `VarkaIrFuzzSuite` only if a new node was added; a reference-evaluator arm in
  `VarkaReferenceEvaluator.scala` (ADDING table says 7/10).
- Proof file: `sql/varka/proofs/` only if the lowering is new ("A new bounded lowering comes with its
  proof file", AGENTS.md). Here `floorDivLong` is old; not needed [guess].

**One test [doc: root AGENTS.md, plus skills/testing-and-debugging.md ~line 1433 for JUnit].**
`build/sbt 'catalyst/testOnly *VarkaExpressionCompilerSuite -- -z "unix_seconds"'`. For a JUnit 5 suite
the `-z` text is the `@DisplayName` [doc: testing-and-debugging.md lines 1385-1433, read only in part].

**Step 7, coverage [doc].** Add a row to `VarkaCoverageSuite.scala` `families`, in
"TIMESTAMP_NTZ columns" (line 1149) or a new family, with a `domain` (the guard's bounds).
Regenerate: `VARKA_COVERAGE_REGEN=true build/sbt 'catalyst/testOnly *VarkaCoverageSuite'`
[doc, verified in the suite, lines 71 and 2225]. This rewrites `docs/sql-varka.md` and
`sql/varka/coverage.json`. If the guard's bound is not reached by the fixture, add rows to
`VarkaCoverageDifferentialSuite` (sql/core).

**Generated files the recipe does not name [doc: AGENTS.md, partly].** These can change:
`emitted_bytes.json`, `width_audit.json`, `types.tsv`, the census, the docs support table.
- The census: `python3 dev/varka_census.py` rewrites `plans/m8/census.tsv` and the docs table
  (script header). The input is the hand file `plans/m8/census_curated.txt` plus the constant `LTZ` at
  `dev/varka_census.py:108`. I would have to add a `unix_seconds|...` line to the curated file and
  remove the entry from the exclusion that produces "out" [guess]. Then `python3 dev/varka_census.py --check`.
- All of it, in order: `dev/varka_regen_generated.sh --all` [doc: script header; AGENTS.md only
  mentions the no-argument form after a merge]. It runs sbt under a lock.

**Step 8, benchmark [doc].** `dev/varka_bench_ids.sh <Class>` for the next id. The nearest
class is `VarkaTimestampNtzBaselineBenchmark` (core). The results files are four per class
(`...-jdk25-runner-epyc7763-results.txt`, `...-128bit-results.txt`, and two `-provenance.txt`), and
the regeneration command is `dev/varka_bench_regen.sh <module> <Class>` [doc: AGENTS.md "Measurements"],
or a dispatched runner run [doc: skills/benchmarking.md:222, read only that line]. Which one applies
to this class I could not tell.
- Then `dev/varka_bench_diff.py --git HEAD <file> --requote`, and `dev/varka_quote_check.py`.

**Step 9, lesson [doc].** Only if the work taught one. `dev/varka_toc.py SKILLS.md --from sql/varka/skills`.

**Step 10 and the gate [doc, with a conflict].** ADDING says `dev/varka_gate.sh`;
`dev/varka_ops/AGENTS.md` says `pre-pr-slot.sh <worktree>` and "never a hand-picked subset".
Then `/code-review high`, `mark-reviewed.sh`, `dev/varka_msg_check.py pr --title ... --body-file ...`,
`open-pr.sh` [all doc: dev/varka_ops/AGENTS.md "Opening or fixing a pull request"]. The pre-commit
hook: `dev/varka_precommit.sh --install-hook`.

**Done is** [doc, merged from three places]: the compiler suite and `FusedOutput` check pass; the
coverage suite is green with no env var; `varka_census.py --check` is clean; the gate or
`pre-pr: passed on <sha>`; a reviewed head; PR under 1000 counted lines; title `[VARKA-667] ...`.

### 4. Log

Counts: GUESS 6, SEARCH 4, CONFLICT 5, STALE 5, DEAD END 2, OVERLOAD 2, GOOD 5. Total 29.

#### GUESS
1. `sql/varka/AGENTS.md` (whole): nothing says whether a row marked "Filed" in `plans/m9/PLAN.md:721`, whose
   prerequisites are not built, may be started. Fix: one line in `ADDING_AN_EXPRESSION.md` "Before you start: the row's `Done when` and its `first` column decide; if rows named in `first` are not Done, stop."
2. `ADDING_AN_EXPRESSION.md` step 1: says "int32 lanes with no branches". `unix_seconds` is on the
   64-bit lane. The doc never says that a long-lane division needs a proved bound. Fix: a paragraph
   in step 1 naming `ConstDivide`'s 2^52 bound and `dayTimeToLong` (`VarkaTimeCompiler.java:195`) as the example.
3. Step 3: a function that must decline for a second input type (here the nanosecond timestamp `AnyTimestampNanoType`) has no guidance. Fix: say "read the expression's `inputTypes`, and decline each type you do not lower".
4. `dev/varka_emit.sh` usage (line 12-35): lists `--columns d:date`, `t:time`, but not `ntz:timestamp_ntz`. I took the type spelling from `coverage.json:23`. Fix: list the type names in the usage text.
5. `ADDING_AN_EXPRESSION.md` step 2: no word on a function that needs no new node. Fix: "Steps 2 and 4 are skipped when the arm builds existing nodes; the step 3 check then ends the task's code".
6. Which test suite takes the new test: the doc names "VarkaEmitter<Family>Suite" but the NTZ tests are `VarkaTimestampNtz*Suite` (Java). Fix: a table "type -> suites" in the recipe.

#### SEARCH
1. Was `unix_seconds` fused: I knew to grep `coverage.json` from AGENTS.md lines 12-20 (GOOD), but the answer "no, and why" was only in `plans/m8/census.tsv:274` after grepping the plans, 4 searches.
2. Where the census exclusion lives: `census_curated.txt` has no `unix_seconds` line; the reason string is the `LTZ` constant at `dev/varka_census.py:108`. Found after 5 greps. Fix: in AGENTS.md, one line saying that census entries come from `census_curated.txt` and the script's constants.
3. How a long-lane floor division is built: `ConstDivide` Javadoc (VarkaVectorIR.java:1570-1600), `floorDivLong`, `dayTimeToLong`. Found by grepping `ConstDivide`. Fix as GUESS 2.
4. Which benchmark class holds NTZ cases and how many results files it has: `git ls-files | grep Benchmark | grep ntz`. Recipe says "three results files"; there are four. Fix: name the files by pattern.

#### CONFLICT
1. Root `AGENTS.md` "Open PRs against `master` on the upstream repo ... `origin` for the fork" and `.agents/pr-workflow.md` ("PR title format is `[SPARK-xxxx][COMPONENT] Title`", create a JIRA ticket) versus `sql/varka/AGENTS.md` ("`[VARKA-<n>] <what it achieves>`") and `dev/varka_ops/AGENTS.md` ("push to `VARKA_FORK_REMOTE` only... A push to `origin` reaches the shared repository"). Fix: a "Varka PRs" paragraph in the root `AGENTS.md` pull-request section pointing at `sql/varka/AGENTS.md` and saying the SPARK rules do not apply.
2. Root pre-flight "create a new git worktree from `<upstream>/master`" versus `sql/varka/AGENTS.md` "A task starts with `dev/varka_task_new.sh <n> "<title>"`". Fix: root AGENTS.md pre-flight step 4 names the script for `sql/varka` work.
3. `ADDING_AN_EXPRESSION.md` step 10: "`dev/varka_gate.sh` ... a green gate is what 'done' means" versus `dev/varka_ops/AGENTS.md`: "Gate before every push. `pre-pr.sh` through `pre-pr-slot.sh`, never a hand-picked subset". Fix: step 10 names `pre-pr-slot.sh` and links `dev/varka_ops/AGENTS.md`.
4. `plans/m8/census.tsv:274` ("out: TIMESTAMP with local time zone") versus the entry's input type `AnyTimestampType` (`datetimeExpressions.scala:1212`), which admits NTZ, and `plans/m9/VARKA-654.md:1495`, which plans `unix_seconds|ntz,ltz`. The "out" is wrong for NTZ today. Fix: the census row should say "out for LTZ only", or the census gets the NTZ type.
5. `sql/varka/AGENTS.md` "Measurements": regenerate on "an otherwise idle machine" with `dev/varka_bench_regen.sh`; `skills/benchmarking.md:222` "Both run on GitHub-hosted runners, with no laptop in the path". I read only that line, so this may be two different cases. Fix: one sentence in ADDING step 8 saying which path a new case takes.

#### STALE
1. `ADDING_AN_EXPRESSION.md:22`: `m4/PLAN.md` is "the task row". The tree has `plans/m4/PLAN.md` but the open milestone is `plans/m9/PLAN.md` (row 667). Step 10 "mark the milestone row" gives no file.
2. `ADDING_AN_EXPRESSION.md` table: "`VarkaEmitterParityBenchmark` + its three results files" - results files are four per class (two results, two provenance), named `-jdk25-runner-epyc7763-...`.
3. `ADDING_AN_EXPRESSION.md` step 7: names only `coverage.json` and the docs table as generated. `AGENTS.md` lists more (`emitted_bytes.json`, `width_audit.json`, `types.tsv`, census, cast tables) and `plans/m9/VARKA-658.md` (`dev/varka_fanout.py`, `sql/varka/rows/`, `dev/varka_rows.py`) names tools that do not exist yet (checked by `ls`: none exist). VARKA-658 states the guide "describes milestone 4's int-lane engine".
4. `ADDING_AN_EXPRESSION.md` opening: "Every Varka node computes over int32 lanes". The engine has 64-bit and 128-bit lanes (`VarkaTimeCompiler` NTZ arms are long-lane).
5. `ADDING_AN_EXPRESSION.md` file table: `VarkaEmitter<Family>Suite.scala`, `VarkaDifferentialHarnessSuite.java`, `VarkaIrFuzzSuite.scala`: most new suites are Java JUnit 5 (AGENTS.md "The suites"). `sql/varka/census/` is the TPC census, not the function census (`plans/m8/census*.`): a name that misleads.

#### DEAD END
1. Root `AGENTS.md` (7 KB) has no pointer to `sql/varka/AGENTS.md`. I found it only because the root text says to read AGENTS.md "in that directory and its ancestors" and I guessed the directory. A task phrased as "fuse a function" never names a directory.
2. `.agents/pr-workflow.md` (JIRA and `[SPARK-xxxx]`) leads nowhere for a Varka PR (see CONFLICT 1). `dev/varka_ops/README.md` is the flow document and is referenced only in `dev/varka_ops/INDEX.md`; the ops home `~/varka-ops` lives outside the repository.

#### OVERLOAD
1. `ADDING_AN_EXPRESSION.md` says "Read the architecture guide `docs/sql-varka.md` first, at least its glossary". It is 328 KB; I did not need it. Fix: give the glossary's heading or line range.
2. `sql/varka/AGENTS.md` is 33 KB; about 20 KB (JIT, Guava, Maven shading, process rules, master-green rules) was not needed to plan this task, though the Measurements and PR parts were. Fix: a "task checklist" table at the top by task type (add expression / add type / benchmark / PR).

#### GOOD
1. `sql/varka/AGENTS.md` lines 8-20: "is this expression covered is a lookup in `coverage.json`" with the reason it cannot drift. Answered the first question in seconds.
2. `ADDING_AN_EXPRESSION.md`: each of ten steps ends in a named check command, and the file-frequency table. The best-shaped document in the tree.
3. `dev/varka_emit.sh` header: usage lines with examples and what each output means. Needed no `--help`.
4. `dev/varka_regen_generated.sh` header: states the order and why; `--dry-run` exists.
5. `VarkaVectorIR.ConstDivide` Javadoc: states the bound rule and why, with the two ways to meet it.

#### Tools: how found, and whether the arguments were documented
| tool | found via | arguments |
|---|---|---|
| `build/sbt ...` | root AGENTS.md | documented, root AGENTS.md and ADDING steps |
| `dev/varka_emit.sh` | ADDING step 3 | documented in its header; column type names guessed |
| `dev/varka_regen_generated.sh` | AGENTS.md prose | documented in its header |
| `dev/varka_census.py` | grep for `census`; AGENTS.md does not name it | documented in its docstring |
| `dev/varka_bench_ids.sh`, `varka_bench_regen.sh`, `varka_bench_diff.py` | ADDING step 8, AGENTS.md | header comments |
| `dev/varka_gate.sh` | ADDING step 10 | header; superseded by `pre-pr-slot.sh` |
| `dev/varka_ops/*.sh` | one sentence in AGENTS.md | `INDEX.md` table; arguments read from it |
| `dev/varka_task_new.sh`, `varka_msg_check.py` | AGENTS.md | listed, arguments only partly (`pr --title ... --body-file ...`) |

### 5. Five changes to the documents

1. **Root `AGENTS.md`: a "Varka" paragraph near the top**: "Work under `sql/varka`, `sql/catalyst/.../codegen/varka`, or on a Varka expression goes by `sql/varka/AGENTS.md`; its PR, branch, remote and title rules replace the Spark ones below (`[VARKA-<n>]`, fork only, `dev/varka_task_new.sh`, `dev/varka_ops/`)." Fixes DEAD END 1 and CONFLICTs 1-2.
2. **`ADDING_AN_EXPRESSION.md`: a "Before step 1" block** that says: look the expression up (`coverage.json`, then `plans/m8/census.tsv`, then the `plans/m<n>/PLAN.md` row); stop if the row's `first` rows are not Done; name the row's plan file and the current milestone's PLAN.md (replacing the `m4/PLAN.md` entry). Fixes GUESS 1, STALE 1, SEARCH 1.
3. **`ADDING_AN_EXPRESSION.md`: a "longer lanes" section** in step 1 and 3 and a type-to-suites table: the 64-bit lane, `ConstDivide`'s 2^52 bound, `GuardedRange`, `dayTimeToLong` as example, "decline each input type you do not lower", and for each type (DATE, NTZ, TIME, DECIMAL) the suites, the coverage family and the benchmark class. Fixes GUESS 2, 3, 5, 6, STALE 4, 5, SEARCH 3.
4. **`ADDING_AN_EXPRESSION.md` steps 7, 8, 10: the full list of what to regenerate and how to finish.** Step 7: add `python3 dev/varka_census.py` and `--check`, and `dev/varka_regen_generated.sh --all`, naming `emitted_bytes.json` and `width_audit.json`. Step 8: four results files, which regeneration path (laptop `varka_bench_regen.sh` or runner dispatch), then `varka_bench_diff.py --requote`. Step 10: `pre-pr-slot.sh`, review and stamp, `varka_msg_check.py`, `open-pr.sh`, replacing `varka_gate.sh`. Fixes CONFLICT 3 and 5, STALE 2 and 3, SEARCH 2 and 4.
5. **`plans/m8/census_curated.txt` / `dev/varka_census.py` header, and `sql/varka/AGENTS.md`: say where the census is edited** ("the hand-edited half is `plans/m8/census_curated.txt`; exclusions are the constants near `dev/varka_census.py:108`"), and correct the `unix_seconds` family's reason from LTZ-only to LTZ. Also add the type spellings to the `dev/varka_emit.sh` usage text (`ntz:timestamp_ntz`). Fixes CONFLICT 4, SEARCH 2, GUESS 4.

### 6. Did the code's structure block me?

No, the documents were the problem. Two small points I did meet:
- `VarkaTimeCompiler.java` is a clear home (NTZ arms at lines 308-343, `ntzMicros` at 378). The recipe says "`VarkaNodeCompiler.java`, or the `Varka*Compiler.java` it chains", which names two places that look right. I picked `VarkaTimeCompiler` because `Hour/Minute/Second` over NTZ are there.
- The two compiler files that matter, `VarkaTimeCompiler.java` and `VarkaNodeCompiler.java`, sit in `codegen/`, while the IR and emitter sit in `codegen/varka/`. The recipe gives bare file names, so finding each took one `git ls-files`. Likewise the "census" directory `sql/varka/census/` holds the TPC-DS data, not the function census (`plans/m8/census.tsv`): a name that misleads.

---

## Audit 2: debug a matrix failure, and show a kernel got no slower (28 November 2026)

Repository read: `the repository worktree` (head 9e7b1612402). Nothing was run or changed.

### 1. Verdicts

* **(a) A test fails in one matrix configuration: mostly.** Every fact is in the repository, but the
  walk from "the matrix failed in a bin on unit X" to "this command, this log, this rule" crosses
  five documents, and nothing says "start here for a red matrix". Biggest obstacle: no document
  defines bin, unit, and configuration spelling in one place, or says where the failing test's
  message is (a CI artifact's `run.log` and JUnit XML, or `target/varka-matrix/runs/...` locally).
* **(b) Show a kernel did not get slower: mostly.** The tools and the methodology are all
  documented, but spread over `AGENTS.md`, `benchmarking.md` (63 KB), the workflow's input
  descriptions and plan files. Biggest obstacle: the terms "runner results file" and "provenance
  file" and the rule for another CPU model are not defined in any agent-facing document; they are
  only in `dev/varka_bench_sections.py`'s docstring and `plans/m8/VARKA-591.md` / `VARKA-599.md`.
  Committed file names are also inconsistent (`runner-7763`, `runner-epyc7763`, `-1/-2/-3` suffixes).

### 2. The path I took, up to my first action

| # | Document | Bytes | What it gave me |
|---|---|---|---|
| 1 | the brief above | ~4 K | the rules |
| 2 | `AGENTS.md` (= `CLAUDE.md`, symlink) | 7,078 | Spark index; only a Spark build/test/PR text, nothing Varka-specific; no pointer to `sql/varka/AGENTS.md` (I found it by `ls sql/varka`) |
| 3 | `sql/varka/AGENTS.md` | 33,018 | the whole answer map: matrix paragraph (lines 166-197), benchmark rules (102-160), `dev/varka_emit.sh` (214-229) |
| 4 | `dev/varka_matrix.sh` header | ~5 K | one-command usage, `--config`, `--suites`, `--module` |
| 5 | `dev/varka_matrix_bins.py` docstring | ~5 K | "unit" and "bin" defined |
| 6 | `sql/varka/skills/testing-and-debugging.md` lines 765-860, 1308-1335, 1694-1706 (file is 130 KB; found by grep) | ~12 K read of 130 K | pinning and skip rules |
| 7 | `sql/catalyst/.../VarkaEmitOptions.java` 298-312 | ~1 K | `methodByteBudget` 0 meaning |

About 42 KB over 2 files before I knew the first action for half (a); for half (b) the same two
files plus `benchmarking.md` sections found by `grep -n '^## '` (I read about 25 KB of its 63 KB).
The root `AGENTS.md` does not point into `sql/varka/`, so the first 7 KB gave me nothing for
either half; the "read every AGENTS.md in ancestors" rule is what leads there, and it is in the
Spark text.

### 3. Plan, half (a): the red configuration

Source tags: [doc X] = from a document, [guess] = mine.

1. **Decode the words.**
   * Option matrix: the `Varka option matrix` workflow (`.github/workflows/varka-option-matrix.yml`
     header) reruns the Varka suites once per emit-option configuration, the defaults with ONE
     option changed. [doc: AGENTS.md 170-186; workflow header]
   * Configuration: `option=value`, from `VarkaEmitOption.TABLE`. `dev/varka_matrix.sh --list`
     prints them. [doc: matrix.sh header; AGENTS.md 173]
   * `methodByteBudget=0`: the bytecode limit per emitted method is switched off; the emitter
     builds the old single-epilogue form, caps a kernel at 64 distinct ops, measures nothing. The
     default is `VarkaEmitBudget.HUGE_METHOD_LIMIT`; table arms are 0, 8000, 1000, 2000, 4000.
     [doc: `VarkaEmitOptions.java` 300-311; `skills/emitter-and-ir.md` 868 "single-epilogue form
     is a reference variant"]
   * Unit: one runner JVM, written `configuration|module|suite,suite,...`, so the failing unit is
     configuration `methodByteBudget=0`, module `catalyst`, suite `VarkaEmitterLongLaneSuite`.
     [doc: `dev/varka_matrix_bins.py` docstring 22-58]
   * Bin: one CI job with two lanes (two JVMs at a time) holding several units, packed to a target
     time. The job is named "Option matrix: <bin>". [doc: same docstring; workflow 418]
2. **Reproduce that one unit on a laptop, one command** [doc: matrix.sh header + `--module`,
   `--suites`, `--config` parsing, lines 140-160]:
   `dev/varka_matrix.sh --config methodByteBudget=0 --module catalyst --suites VarkaEmitterLongLaneSuite`
   It builds with sbt first (no `--skip-build` on a first run), runs the defaults as configuration
   zero as well, and exits 1 on a failure. Add `-j 1`. Whether a platform unit needs
   `--platform` or `--jvm-arg`: not for this unit (a platform unit is named `isa=...`).
   [doc: testing-and-debugging.md 1694-1703 for the platform case]
   * One test only: no flag in `varka_matrix.sh` selects a test (grep for `-z` found none). [guess]
     Fallback: run the single suite directly with sbt and `-Dvarka.matrix.config=methodByteBudget=0`
     (the property the script sets, `VarkaMatrixRuns.PROPERTY`); the shared JVM and the memory
     sanitizer (`-Dvarka.sanitizeMemory=true`) then need setting by hand. [guess, from reading
     `run_one` in matrix.sh 401-430]
   * `VarkaEmitterLongLaneSuite` has no `methodByteBudget` line in `skips.tsv` (it has
     `checkIntOverflow=false` lines at 16-18), so a failure there is new. [checked, `skips.tsv`]
3. **See the failing message.**
   * Locally: `target/varka-matrix/runs/methodByteBudget=0/catalyst/run.log` (ScalaTest output) and
     `.../junit/*.xml`, plus `.../exit`. [from `run_one`, matrix.sh 410-430; no document says it]
   * On CI: the bin's artifact `varka-option-matrix-<bin>` holds the same `run.log`, `exit`, `junit/*.xml`
     under `target/varka-units/<bin>/*-u*/runs/<config>/...`; the "report" job's step summary and the
     `varka-option-matrix-report` artifact give one line per configuration.
     [from workflow 679-700 and 812-875; no document says it]
4. **Look at what the compiler emitted for the expression.**
   * Bytecode sizes and vector-op counts per method, IR and shape hash:
     `dev/varka_emit.sh "<sql expr>" --columns c:long --options methodByteBudget=0`. [doc: AGENTS.md
     214-222; script header]
   * A listing of the bytecode itself: no documented command. `varka_emit.sh` prints sizes and
     counts only; `javap` appears in `skills/vector-api-and-width.md` 327 for another purpose; a
     replay file (`dev/varka_emit.sh --replay`) rebuilds the class but only for a failed kernel.
     [guess: write the class from `VarkaEmitDump` and `javap -c -p` it]
   * The JIT's machine code: `dev/varka_emit.sh "<expr>" --columns c:long --asm [--width=16]`
     (adds C2's assembly for `loopDense0`, saved under `target/varka-emit/asm-<time>.log`); it
     needs hsdis, built by `dev/varka_hsdis_build.sh` (needs `libcapstone-dev`, gcc). [doc: AGENTS.md
     224-227; both scripts' headers] For C2's decisions (inlining, traps) the table in
     `docs/sql-varka.md` 2873+ names `varka_c2_report.py`, `varka_trap_census.py`. [doc]
5. **Decide the fix: what the rules allow.** [doc: AGENTS.md 173-186; testing-and-debugging.md 799-851]
   * A test starts from `VarkaMatrix.base`, not `DEFAULTS`.
   * If the test's premise depends on the option (here: a 64-op cap or a method name that exists
     only in the per-group form), pin that option, from `VarkaEmitOptions.DEFAULTS` or the value
     it needs, with the reason beside it, and keep every other option from the matrix. Where the
     pinned run would repeat the defaults' run, `assume` (cancel) there.
   * Tag `PinsDefaults` only when the whole test is about the defaults' emitted structure;
     `C2Code` when it is about what C2 makes of a kernel.
   * A `skips.tsv` line (tab separated: configuration, suite, test, kind, reason) only for an
     ANSWER test that the configuration breaks by design; kinds `fails`, `declines`, `draws`
     (`draws` only for a test whose cases a seed draws from the coverage table). A listed test that
     starts to pass is reported stale and the line must go. After a merge run
     `dev/varka_lint.py sql/varka/matrix/skips.tsv`.
   * A test that pins a method name must say so: a test naming `"epilogueMasked"` under the
     defaults is stale; use `withMethodByteBudget(0)` (`emitter-and-ir.md` 868-889).
   * Never weaken or skip a failing test unless asked (root `CLAUDE.md`).
6. **Verify.** Rerun step 2 for the suite; then for the sibling arm `--config methodByteBudget=8000`;
   with `--module sql` if a SQL suite is touched; whole matrix over the new suites:
   `dev/varka_matrix.sh --all --module catalyst --suites <S> -j 4` (25 suites x 50 configurations
   about 40 min on a 24-core laptop). [doc: testing-and-debugging 843]. Before the push the one gate
   is `dev/varka_ops/pre-pr.sh <worktree>` through `pre-pr-slot.sh` [doc: `dev/varka_ops/INDEX.md`];
   on CI dispatch the matrix on the branch. "Done" = the unit green under that configuration, the
   defaults still green, `skips.tsv` unchanged or one reasoned line, `dev/varka_precommit.sh` clean.

### 4. Plan, half (b): "this change did not make a kernel slower"

1. **Find the benchmark for a function.**
   * Grep `sql/*/benchmarks/` and the plans for the function's name; read the committed numbers
     first. [doc: `skills/benchmarking.md` 579-606]
   * Classes: catalyst `sql/catalyst/src/test/java/org/apache/spark/sql/Varka*Benchmark.java`
     (`VarkaEmitterParityBenchmark`, one section per expression family, and `VarkaArithmeticBenchmark`);
     end to end in `sql/core/src/test/java/.../execution/benchmark/` (`VarkaThroughputBenchmark`,
     `VarkaFilterBenchmark`...); 63 Varka benchmark sources in all. [doc: `ADDING_AN_EXPRESSION.md`
     35-36 and step 8; `docs/sql-varka.md` 2831-2845] There is no index "function -> benchmark class". [GUESS: grep]
2. **Run it on a laptop.** `dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark`
   (`--sections "title|title"` for one section; `--no-narrow`, `--width=N`). It refuses a machine
   with 1-minute load above 1.0, runs `dev/varka_bench_canary.sh` first, runs both widths, writes
   `<Class>-jdk25-results.txt`, `-128bit-results.txt` and `-provenance.txt` under `sql/<module>/benchmarks/`,
   and ends with the diff against the committed wide file. [doc: AGENTS.md 112-122; script header]
   Module argument is `catalyst` or `core`, meaning `sql/catalyst` and `sql/core`. [doc: header]
3. **Run it on CI.** `gh workflow run benchmark.yml -R vecbricks/varka -f pr=<n> -f class=<Class>
   [-f pair=true -f rounds=3 -f expected-cpu="AMD EPYC 7763"]`. Pair mode runs merge base and head
   alternately on one runner and prints a verdict per row; a results run returns the files as an
   artifact for the author to commit (`create-commit` is ignored with `pr`). [doc: `benchmarking.md`
   204-258; workflow inputs] The expected-cpu string "AMD EPYC 7763" is only in the workflow
   description and `VARKA-591.md`. For a branch on the fork, dispatch on the fork instead. [guess]
4. **What the two file kinds are.**
   * Runner results file: a committed `<Class>-jdk25-runner-epyc7763-results.txt` (and `-128bit-`)
     is the class's numbers measured on a GitHub runner pinned to an EPYC 7763; the plain
     `<Class>-jdk25-results.txt` is the laptop's. For a class that has a runner file, a CI run
     writes the plain name and `restore` splices it into the runner-named file, only when the CPU
     check passed. [doc: `dev/varka_bench_sections.py` docstring 41-47; `VARKA-599.md` 80, 151]
   * Provenance file: `<Class>-jdk25-provenance.txt` beside it, giving commit, dirty state, date,
     JDK, kernel, CPU, governor, load, canary, and the exact run command; for CI runs also the
     run URL and ref. [doc: regen.sh header 28-33; read two files]
   * How committed: regen writes them in the working tree and the author commits them with the
     change; for a CI run the author downloads the artifact (the results and a provenance file)
     and commits it. [doc: benchmarking.md 230-232]
5. **Compare two results.** `dev/varka_bench_diff.py OLD NEW`, or `--git HEAD FILE`; `--within FILE
   --ab "label A" "label B"` for pairs; `--requote` after a regeneration. It compares the rate
   column, lists controls (scalar anchors) first, marks moves above `--threshold`, always exits 0,
   and picks up `<Class>-jdk25-band.txt` when the family has one. [doc: AGENTS.md 112-133;
   script docstring] For a "no row moves" claim use `dev/varka_bench_pair.sh <module> <Class>`
   (exits 1 if any row is slower by 10% by both median and best). [doc: benchmarking.md 214-221]
6. **Rules before writing a performance claim down.** [doc: AGENTS.md 102-111, 123-144;
   `plans/m2/VARKA-14.md` 2.1]
   * at least five iterations over two-second windows;
   * on an otherwise idle machine, canary green (`dev/varka_bench_canary.sh` needs a baseline
     for this host: `dev/varka_canary/baseline-<host>.txt`, or `--record` on a known-good day);
   * a ratio under about 1.3x is re-run and compared by minimums before it is written;
   * read the controls first; a move is read against the family's band;
   * every quoted number must appear in a committed results file: `dev/varka_quote_check.py`.
   * "Smallest ratio that counts": the document gives ~1.3x for a claim of a gain; for "did not
     get slower" the pair's flag is 10% by median and best, widened by the class's own A/A; the two
     numbers are never reconciled in one place. [conflict, see log]
7. **When CI lands on another CPU.** `benchmark.yml` fails the job at the probe step with
   "CPU mismatch! Expected '...' but got '...'" when `expected-cpu` is set;
   `.github/workflows/varka-bench-cpu-retry.yml` (`dev/varka_bench_cpu_retry.py`) reruns the failed
   jobs after an hour, up to six attempts, only for CPU-check failures. With expected-cpu empty the
   run proceeds and the CPU goes into the provenance. A result from another CPU is comparable only
   with one from the same CPU; Arm results only with Arm; a runner results file refuses a run whose
   CPU check did not pass. [doc: `plans/m8/VARKA-591.md`, `VARKA-599.md`, `benchmarking.md` 864-873;
   none of it in AGENTS.md] What to do when six attempts fail: not stated. [guess: dispatch again;
   or compare against a pair on the runner you got.]
8. **Done** = a pair (or regen) whose controls held, no row slower beyond the class's band, the
   results and provenance committed, `dev/varka_bench_diff.py --requote` list empty, `dev/varka_quote_check.py`
   green, PR description naming the run URL.

### 5. The log

Counts: GOOD 8, SEARCH 6, CONFLICT 4, STALE 4, DEAD END 3, GUESS 5, OVERLOAD 3. Total 33.

#### GOOD
1. `sql/varka/AGENTS.md` 166-197: the matrix paragraph says what the matrix is, base versus
   DEFAULTS, `PinsDefaults`, `C2Code`, `skips.tsv`, and where the PR run differs. Keep.
2. `dev/varka_matrix.sh` 20-60: the header is its usage, and `-h` prints it (`usage()` finds the
   comment block). Every flag I needed is there.
3. `dev/varka_matrix_bins.py` 22-58: defines unit, bin, and the `configuration|module|suites`
   spelling; the best definition in the repository.
4. `VarkaEmitOptions.java` 300-311: states what budget 0 is for and what a small value is for.
5. `skills/emitter-and-ir.md` 868: the single-epilogue form as a reference variant, with the
   pinning rule. Exactly the case for the unit.
6. `dev/varka_emit.sh` 17-55 header and AGENTS.md 214-229: one command per question (counts,
   assembly, table, replay), hsdis pointer included.
7. `dev/varka_bench_regen.sh` 17-60: what is written, refusal on a busy machine, ending diff.
8. `skills/benchmarking.md` 204-258: pair tool, CI dispatch with the exact `gh` line, runner spread.

#### SEARCH
1. Find how a bin and a unit are named in a CI report: AGENTS.md has neither word; found in
   `dev/varka_matrix_bins.py` docstring after `grep -rn "unit"`. Pointer: AGENTS.md 187 should say
   "a CI job is a bin, a bin holds units `config|module|suite`; see `dev/varka_matrix_bins.py`".
2. Where the failing test's message is: nothing says; derived from `run_one` (matrix.sh 410-430) and
   the workflow upload list (679-700). Fix: one sentence in AGENTS.md 187-197 naming the artifact
   `varka-option-matrix-<bin>` and the files `run.log` and `junit/*.xml`.
3. Single test inside a unit: searched `varka_matrix.sh`, `testing-and-debugging.md` for a name
   filter; none. Fix: say "no filter; run the suite under sbt with `-Dvarka.matrix.config=<c>`
   or use `-z`" (whichever is true).
4. Which benchmark covers a function: no index; grep over 63 sources and 139 result files. Fix: a
   generated table "family -> benchmark class" in `docs/sql-varka.md` or `ADDING_AN_EXPRESSION.md`.
5. What a "runner results file" is: only `dev/varka_bench_sections.py` 41-47 and `VARKA-599.md`
   (searched `sql/varka/skills`, AGENTS.md, docs: no hit). Fix: define it in AGENTS.md next to line 114.
6. The rule on another CPU model: `plans/m8/VARKA-591.md` and the workflow's input description; found
   by grepping "CPU mismatch". Fix: a paragraph in `skills/benchmarking.md` near 864-873.

#### CONFLICT
1. File names: `benchmarking.md` 873 and `VARKA-599.md` 151 say runner files are
   `-runner-epyc7763-` only, but the tree holds `VarkaEmitterParityBenchmark-jdk25-runner-7763-2-results.txt`,
   `-runner-7763-128bit-1-`, `-runner-9v74-128bit-` (VARKA-599.md 152 acknowledges "other committed
   runner names"). A reader cannot tell which is current. Fix: one table of the suffixes and what each is.
2. Threshold: AGENTS.md 107 "ratio under ~1.3x is re-run and compared by minimums" versus
   `varka_bench_pair.sh` default `--threshold 10` and `benchmark.yml` `threshold: 10`, and
   `benchmarking.md` 218-221 "a claim near the noise (under about 1.3, the repo's own rule)". 1.3x
   is 30%, the pair flags 10%. Fix: say that 1.3 is for claims of a gain and 10%/band for "no regression".
3. `docs/sql-varka.md` 2838-2845 runs benchmarks with `build/sbt "sql/test:runMain ..."`, while the
   committed provenance records `build/sbt catalyst/Test/runMain ...` and AGENTS.md says
   "`build/sbt <module>/Test/compile`". Both work in sbt; the doc is the older syntax.
4. `docs/sql-varka.md` 2831-2845 says benchmarks are run with
   `SPARK_GENERATE_BENCHMARK_FILES=1` by hand, AGENTS.md 112 says regenerate only through
   `dev/varka_bench_regen.sh`. The first writes no provenance. Fix: the doc should say use the script.

#### STALE
1. `sql/varka/AGENTS.md` 175, 188 and the workflow header cite `VARKA-507` as the fan-out design;
   `ls sql/varka/plans/*/VARKA-507.md` finds no plan file (only `plans/m8/results/VARKA-507-*.txt`);
   PLAN.md says "Moved to milestone 9", so the file moved or was never written.
2. AGENTS.md 175-176 cites `m7/VARKA-248.md` 3.2: exists, but is the 2026 October design; the unit and bin
   text (VARKA-507, 617) is not in it. A reader of 248 does not learn what a bin is.
3. AGENTS.md 107-108 says `VARKA-14.md` 2.1 without the folder; the file is `plans/m2/VARKA-14.md`.
4. `docs/sql-varka.md` 2838-2845 lists seven benchmark commands; the repository has 63 Varka benchmark
   sources, 54 runner-named files; the page presents them as the benchmarks.

#### DEAD END
1. `sql/varka/AGENTS.md` 211-212 points to `dev/varka_ops/` "read its `INDEX.md` and `AGENTS.md`
   first": it answers PR gating, not "how do I read a red bin"; no tool there fetches a bin's log.
2. Asking for the emitted bytecode: `dev/varka_emit.sh` and `VarkaEmitDump` print counts, not a
   listing; no document gives a `javap` route. `skills/emitter-and-ir.md` 72 mentions "a `javap` capture" only in passing.
3. "Idle machine" for a new host: `dev/varka_bench_canary.sh` exits 2 with "no baseline for <host>";
   no document says to run `--record` on first use or who may.

#### GUESS
1. The exact module argument of `--module` for a SQL unit: `sql` or `catalyst`, written
   `catalyst` in the unit; matrix.sh header says `--module sql`, so I inferred the spelling.
2. Whether `--config methodByteBudget=0` is the right spelling of the unit's configuration: the
   table gives `Count("methodByteBudget", ..., List.of(0, 8000, 1000, 2000, 4000), ...)`; I
   assumed `name=value` from the skips file's first column.
3. Running one test in a configuration (see SEARCH 3).
4. Expected-cpu value and which repo (`vecbricks/varka` or the fork) a developer dispatches to.
5. What to do after the CPU retry gives up.

#### OVERLOAD
1. `sql/varka/AGENTS.md` is 33 KB (465 lines); half of it (Java rules, papers, process rules, master
   rules) was not needed for either half. Reading it all took one call.
2. `skills/testing-and-debugging.md` is 130 KB with 70 `##` sections in no topical order; the matrix
   rules are in three places (765-860, 1281-1335, 1694-1706).
3. `skills/benchmarking.md` is 63 KB; the regenerate-and-compare rules are in 204-260, 579, 864-890,
   the CPU rule is not there.

### 6. Five changes to the documents

1. **`sql/varka/AGENTS.md`, new short section "A red option matrix, in order"** directly after
   line 197: (1) bin = one CI job, unit = `config|module|suite` (one runner JVM), configuration =
   `option=value` from `VarkaEmitOption.TABLE`; (2) the one-line repro
   `dev/varka_matrix.sh --config <c> --module <m> --suites <S>`; (3) the message: artifact
   `varka-option-matrix-<bin>`, files `run.log` and `junit/*.xml`, locally under
   `target/varka-matrix/runs/<c>/<m>/`; (4) the fix rules in four bullets with pointers to
   `testing-and-debugging.md` 799-851. Today this takes five documents.
2. **`sql/varka/AGENTS.md` and root `AGENTS.md`, pointer.** Root `AGENTS.md` gets one line:
   "Varka work: read `sql/varka/AGENTS.md` first". Today the root says nothing of Varka, and a new
   agent finds the Varka file only by listing `sql/varka`.
3. **`sql/varka/skills/benchmarking.md`, new top section "File names, CPUs and the one rule"**: define
   results file, `-128bit-` companion, runner results file (suffix table covering `epyc7763`, `7763-N`,
   `9v74`, `arm`), provenance file, band file; say who commits what for a laptop run and for a CI
   artifact; and state the CPU rule: `expected-cpu` "AMD EPYC 7763", the CPU retry workflow (six
   attempts), what a mismatched result may be used for, and what to do after the cap. Link from
   AGENTS.md line 112.
4. **`sql/varka/AGENTS.md` 102-111, one reconciling sentence** on the two thresholds: 1.3x for a
   claimed gain; for "no regression" the pair's 10% by median and best, widened by the class's own A/A;
   and where the "no row moves" claim is made, which tool (pair) and what rounds. Add the exact
   `gh workflow run benchmark.yml ... -f pair=true` line there rather than only in a skill.
5. **`docs/sql-varka.md` "Building, testing and running benchmarks" (2831-2845)** and
   **`dev/varka_emit.sh`**: replace the seven hand-typed `runMain` lines with
   "`dev/varka_bench_regen.sh <module> <Class>`; find a class by `ls sql/*/benchmarks | grep Varka`;
   expression family -> benchmark table (generated)"; and document in the emit tool header (or add
   `--dump-class`) how to get the emitted bytecode listing, since the question has no answer today.

### 7. Did the code's structure block me?

No for (b). For (a), mildly: the 64-op cap and the name pinning are in `VarkaEmitBudget` and
`VarkaEmitOptions` (javadoc on a 600-line record, line 300), which I found only through the
grep for the option name; one more fact I could not locate is where `VarkaEmitterLongLaneSuite`'s
own premise on methods lies (not read, as the suite is large). The documents, not the code, were the
problem.

---

## Audit 3: structure and process (28 November 2026)

Repository read: `the repository worktree` (read-only; no build, no JVM, no `git` that
changes state). Task: "row 360 of milestone 8", in three halves (a) start it, (b) add an emit option
`demoOption`, (c) draw the map.

### 1. Verdicts

* **(a) Start a task: "From the repository alone I could start this task with no question: mostly."**
  Biggest obstacle: the root `AGENTS.md` (the entry file) never mentions Varka, `sql/varka/`,
  `CONTRIBUTING.md` or the `[VARKA-<n>]` flow, and points at Spark's JIRA flow, which is wrong here;
  and for row 360 the documented first command, `dev/varka_task_new.sh`, refuses, because the row
  already has a plan, with no text saying what a contributor does then.
* **(b) Add an emit option: "mostly".** One javadoc sentence names the five code edits; the
  rest (the hand lists, the generated files, what to run) had to be found by searching a sibling
  option. No document is a checklist and none says how to know nothing was missed.
* **(c) Draw the map: "mostly".** The classes and directories can be mapped from `README.md`,
  `VISION.md` and a source pass, but there is no map file; 37 of 106 `dev/varka*` entries are named
  in no document; the decline reasons come from free-text strings at about 100 call sites and no
  document says so.

Log entries by kind: GUESS 6, SEARCH 7, CONFLICT 9, STALE 6, DEAD END 3, OVERLOAD 3, GOOD 9
(43 in all).

### 2. The path I took, with bytes, up to the first action

| # | document | bytes | what it gave |
|---|---|---|---|
| 1 | `AGENTS.md` (= `CLAUDE.md`) | 7,078 | Spark's own rules; nothing about Varka; says read every `AGENTS.md` up to the root, and to read `.agents/pr-workflow.md` before a PR |
| 2 | `sql/varka/AGENTS.md` (found by `ls sql`, not pointed to) | 33,018 | the real entry: plans, task start command, gates, PR rules, option matrix |
| 3 | `plans/m8/PLAN.md` line 1222 (row 360; 900,313-byte file, found by `grep '^| 360 '`) | 4,821 | the row |
| 4 | `dev/varka_task_new.sh` | 4,432 | what a task start does, and that it refuses for 360 |
| 5 | `CONTRIBUTING.md` lines 1-140 | about 8,000 | what a row is, how status reads, the current milestone, PR title |
| 6 | `dev/varka_ops/INDEX.md` + `AGENTS.md` | 3,976 + 3,936 | the gate and push scripts |

Six files, about 65 KB, before I knew the first action; then `plans/m8/VARKA-360.md` (31,398) for the
row's own plan, `dev/varka_ops/README.md` (11,001), `skills/working-in-this-repo.md` first 97
lines (about 6,000), `.agents/pr-workflow.md` (2,356, a dead end), `.github/PULL_REQUEST_TEMPLATE`
(about 4,000) and `dev/varka_msg_check.py --help`. Total read for (a): about 130 KB in 12 files.
The 33 KB `sql/varka/AGENTS.md` holds the answer but an agent that starts at the root is never told
to open it before it greps.

### 3. Half (a): starting "row 360 of milestone 8"

#### 3.1 What I established, and from where

* **A row** is one line, starting `| <n> |`, of the numbered task table (section 3, "Task
  breakdown") of a milestone plan. Rows live in `sql/varka/plans/m<n>/PLAN.md`; row 360 is line 1222
  of `plans/m8/PLAN.md` (`CONTRIBUTING.md` "Finding work"). Task ids are written `VARKA-<n>`
  (`sql/varka/AGENTS.md` 97-100). The row's columns: task, what it is, where it came from, size.
* **Status** is the first bold marker of the row's first cell: `Done`, `Done in part`, `Started`,
  `Planned`, `Scoped`, `Built`, `Withdrawn`, `Moved to milestone <n>`; no marker means open and
  not started (`CONTRIBUTING.md` 31-35; the full rule is only in the docstring of
  `dev/varka_issues.py` 30-46, and `dev/varka_plan_lint.py` 40-50). Row 360 reads **Planned**
  (27 November 2026, baseline merged, "no lowering of this row is built").
* **Done-when** is the sentence opening `**Done when**` inside the row, repeated and corrected in
  section 9 "Done when" of `plans/m8/VARKA-360.md` (31 KB, ten sections; the row says
  "`VARKA-360.md` 10" for the outcome).
* **Plan file:** `plans/m8/VARKA-360.md` exists. Its section 5 cuts the row into parts 360a to
  360d (360a: class U at one scale and the `(20, 0)` cast, needs 359a; 360b `WideMul`; 360c after
  row 481; 360d after 359b), "five pull requests after the baseline".
* **Milestone:** a milestone is one theme, one folder `plans/m<n>/`, one `PLAN.md` once it has a task
  table (`SCOPE.md` before), closed by a last row, a tag `varka-m<n>` and a changelog entry
  (`sql/varka/AGENTS.md` 87-92; `CONTRIBUTING.md` 36-44). Which is current: `CONTRIBUTING.md` says
  "the highest-numbered `m<n>/PLAN.md`, `m8/PLAN.md` as this is written", but `plans/m9/PLAN.md`
  exists ("Opened 25 November, while milestone 8 runs"). The answer I settled on, from
  `plans/m8/PLAN.md` section 12.5 (close path 27 November) and `m9/PLAN.md` line 3: m8 is closing and
  is the one that holds row 360; m9 is planned. No document says this in one line.
* **How work starts** (`sql/varka/AGENTS.md` 208-212, `dev/varka_task_new.sh` header):
  `dev/varka_task_new.sh <n> "<title>"` creates the worktree `../varka-<n>` on branch `varka-<n>` at
  `origin/master` (`--no-track` is not used; `origin` is `vecbricks/varka`), writes the plan from
  `plans/TEMPLATE_TASK.md`, installs the hook. For row 360 it exits 1: "plan already exists on
  origin/master; this task has a plan". So my plan: `git worktree add -b varka-360a-<topic>
  ../varka-360a origin/master` and `dev/varka_precommit.sh --install-hook` (my guess; the local
  branch list shows `varka-360-decimal-add`, `varka-360a-sums`, `varka-360b-widemul`,
  `varka-360b2-rescale`, so earlier parts used `varka-<n><letter>-<topic>`).
* **Commit message:** `dev/varka_msg_check.py commit FILE` (the commit-msg hook): the only
  attribution is one `Generated-by: <tool> (<model>)` line, no `Co-Authored-By`, no session link.
  Subject style from history: `[VARKA-362][VARKA-363] Refresh the decimal baseline's runner results
  at both widths (#1797)`.
* **PR title and description:** `[VARKA-<n>] <what it achieves>`, at most 80 characters, no "Step N"
  or "Task N"; body = the five headings of `.github/PULL_REQUEST_TEMPLATE` verbatim, in order,
  soft-wrapped, the "Why" opening with the reason, not a task id; under 1000 changed
  hand-written lines; says what `/code-review high` found; test-time change stated
  (`sql/varka/AGENTS.md` 344-363; `dev/varka_msg_check.py --help`). A plan-only PR's title starts
  "Plan" (seen in history: `Plan: file row 695 ...`; no document says it).
* **Before a PR opens** (`dev/varka_ops/AGENTS.md` "Opening or fixing a pull request"):
  1. commit, worktree clean; 2. `dev/varka_ops/pre-pr-slot.sh <worktree>` as a background command
  (merge master, regen, `extra-gate.sh`, `dev/varka_push_check.py`, arm sweep) until
  `pre-pr: passed on <sha>`; 3. `/code-review high` on the branch, one triaged fix round, gate again
  if it committed; 4. `dev/varka_ops/mark-reviewed.sh <worktree> high <findings-file>`;
  5. `dev/varka_ops/open-pr.sh <worktree> <branch> /abs/body.md <lock> "<title>"`; 6.
  `start-waiter.sh <pr> <build-run-id> <branch>`, which dispatches and links the option matrix. Also
  `python3 dev/varka_msg_check.py pr --title T --body-file F` first.
* **Who pushes, who merges:** pushes go to the remote `fork` only (`MaxGekk/spark`), by the three
  scripts, never `git push` by hand (`dev/varka_ops/AGENTS.md`); `origin` is the shared repo and
  the pre-push hook refuses it. Root `AGENTS.md` adds: get the user's approval before pushing or
  opening a PR. **Who merges: no document says.** `watch-any-ready.sh` "exits when a PR is
  clean to merge"; `sql/varka/AGENTS.md` 352 speaks of "the owner's review after CI". I would
  assume the owner merges (guess).
* **How the row's record is updated when work lands:** the plan file's section 9/10 gets the
  outcome with numbers from committed files and scored predictions, never rewritten earlier text
  (`sql/varka/AGENTS.md` 72-85, `TEMPLATE_TASK.md`); the row's first bold marker changes (`Started`
  / `Done in part` / `Done`) and the row says what is left (`dev/varka_plan_lint.py` docstring, run
  by the hook and CI, `build_and_test.yml:2001`); `varka_issues.py --apply` then mirrors the row
  into a GitHub issue on merge to master. A finding made on the way becomes a new row, not a note
  (`CONTRIBUTING.md`; `skills/working-in-this-repo.md` 245-260). The generated files are
  regenerated by `dev/varka_regen_generated.sh --all`, never merged by hand.

#### 3.2 Plan for row 360 (first part, 360a), each line sourced

| step | source |
|---|---|
| Pre-flight: `git remote -v`: `origin`=vecbricks/varka, `fork`=MaxGekk/spark, `upstream`=apache/spark | `skills/working-in-this-repo.md` 11; **root `AGENTS.md` says the opposite convention** (see C2) |
| Read `VARKA-360.md` 0, 2, 5, 9, 10 and rows 359, 481 (360a "needs 359a") | `plans/m8/VARKA-360.md` 5 |
| Check 359a is merged (`git log origin/master --grep VARKA-359`) | my guess |
| `git worktree add -b varka-360a-<topic> ../varka-360a origin/master`; `dev/varka_precommit.sh --install-hook` | `dev/varka_task_new.sh` body, adapted (guess for the name) |
| Edit `VarkaDecimalCompiler.java`, `VarkaEmitOption.java`/`VarkaEmitOptions.java` (`decimalCarry`), `sql/varka/proofs/decimal128.smt2`, `VarkaProofFiles.java`, tests, coverage rows | `VARKA-360.md` 5 file table |
| Compile: `build/sbt catalyst/Test/compile` | root `AGENTS.md` "Build and Test" |
| One suite: `build/sbt 'catalyst/testOnly *VarkaEmitOptionSuite'` | root `AGENTS.md`; the Varka suites are named `*Varka*` (`CONTRIBUTING.md`) |
| Add a new expression's rows (coverage table, census) | `ADDING_AN_EXPRESSION.md`, 10 steps |
| Emitter counts: `dev/varka_emit.sh "<sql>"`, `--table --variant k=v` | `sql/varka/AGENTS.md` 214-222 |
| Regenerate: `dev/varka_regen_generated.sh --all` | `sql/varka/AGENTS.md` 18-23; script header |
| Gate and review and open: as in 3.1 | `dev/varka_ops/AGENTS.md` |
| AVX2 recheck: width audit under `JDK_JAVA_OPTIONS=-XX:UseAVX=2` if the emitter changed | `sql/varka/AGENTS.md` 161-165 |
| "Done" for 360a: its items of `VARKA-360.md` 9 (carry, borrow, sign extension proven under both solvers; coverage rows; the PR Build and the option matrix pass), and the row text set to `Done in part` naming what is left (360b to 360d) | `VARKA-360.md` 9; `varka_plan_lint.py` docstring |

#### 3.3 Tools the task needs: how I found them, and whether a document gave arguments

| tool | found by | arguments from |
|---|---|---|
| `dev/varka_task_new.sh` | `sql/varka/AGENTS.md` 208 | its header comment |
| `dev/varka_ops/*.sh` | `AGENTS.md` 229 -> `dev/varka_ops/INDEX.md` | INDEX (inputs, outputs, exit codes) |
| `dev/varka_msg_check.py` | `sql/varka/AGENTS.md` 159 | its `--help`; but `pr --help` crashes with a `KeyError` traceback |
| `dev/varka_precommit.sh` | `sql/varka/AGENTS.md` 146 | one line there |
| `dev/varka_regen_generated.sh` | `sql/varka/AGENTS.md` 22 | its header |
| `dev/varka_emit.sh` | `sql/varka/AGENTS.md` 214 | the three options named there; the rest from the script |
| `dev/varka_matrix.sh` | `sql/varka/AGENTS.md` 174 | its header; no document gives a command line |
| `dev/varka_plan_lint.py` | `ls dev` (SEARCH), then the pre-commit hook | its docstring |
| `dev/varka_gate.sh` | `CONTRIBUTING.md` | `--list`; relation to `pre-pr.sh` unstated (C4) |
| `build/sbt` | root `AGENTS.md` | root `AGENTS.md` |

### 4. Half (b): adding `demoOption` (boolean, default false)

#### 4.1 What the documents say

* `VarkaEmitOption.java` 44-50 (class javadoc): "Adding an option is the record component, its
  builder setter and `with*` method, its javadoc, its value in `VarkaEmitOptions.DEFAULTS`, and one
  entry here." This is the one complete sentence. GOOD.
* `sql/varka/AGENTS.md` 166-198: an option is fuzzed the day it lands because the fuzzer draws from
  `VarkaEmitOption.TABLE`; `VarkaEmitOptionSuite` fails until the table entry exists; the same
  entry gives it its configurations in the option matrix; a test that depends on the default's
  structure takes `PinsDefaults`; one that depends on C2 takes `C2Code`; an option read outside the
  compiler and emitter (by an evaluator as it runs) goes in `VarkaTestReach.FULL` with a reason; a
  configuration that breaks a test by design goes in `sql/varka/matrix/skips.tsv` with a reason.
* `ADDING_AN_EXPRESSION.md` lists `VarkaEmitOptions.java` in its file table as "a variant worth
  measuring both ways", and nothing more. There is no `ADDING_AN_OPTION.md`.
* `plans/TEMPLATE_TASK.md` 3.1: new behaviour goes behind an option with the old form kept as a
  live reference variant, the default flips in the last commit.

#### 4.2 The places, found by the documents first

Declare (the javadoc sentence), in
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/`:

1. `VarkaEmitOptions.java`, the `@param demoOption ...` javadoc in the record header (near line 160-290).
2. the record component `boolean demoOption`, appended in declaration order (near line 598-600); the table's
   order must equal it (`VarkaEmitOptionSuite` "the table names every option of the record once, in
   declaration order").
3. `DEFAULTS` (line 876): the positional constructor call of about 60 unlabelled values; a `false` must be added in
   the same position as the component. A swapped boolean compiles.
4. `Builder`: the field (line 1053), the setter `Builder demoOption(boolean)` (1206), the copy in
   `toBuilder()` (987) and the argument in `build()` (1407).
5. the `with*` method `withDemoOption(boolean enabled)` (1428, 1528 show the pattern).
6. `VarkaEmitOption.java`: one `new Flag("demoOption", Reason.<R>, false, VarkaEmitOptions::demoOption,
   Builder::demoOption, <Rendering>)` in `TABLE` (line 462-560), in the record's order. Reason:
   `REFERENCE`, `MACHINE`, `KNOB`, `PRICED_CHECK` or `FAULT_INJECTOR` (javadoc at 70-130; a purely
   demonstrative option would be `KNOB`, my guess). Rendering: `POSITIONAL`, or a `FlagTag` that shows in `canonical()`. The choice
   changes the shape hash, so it needs care; pick `POSITIONAL` for a default-false option so
   production hashes do not move (class javadoc, `VarkaEmitOptions` 76-80).

Found by searching a sibling option (`narrowHalfSpecies`, default false, in 14 non-plan files;
`validityByWord`; `maskFromWord`) - not by any document:

7. `VarkaEmitOptions.java`'s other mentions of an option, if an alias or validation exists
   (`VarkaEmitOptionSuite` "a pair the options' validation refuses").
8. The consumer: `VarkaBodyEmitter.java` / `VarkaVectorWalk.java` / the compiler arm that reads
   `options.demoOption()` (an option nothing reads would pass every suite and be a dead
   switch; no document says to check that its two values differ, though `Subject` and `readOnlyBy`
   on the `Flag` exist to say where they differ).
9. Hand-kept lists in tests: `VarkaMatrixRuns.C2_OPTIONS` (only if it changes what C2 compiles; test
   `VarkaMatrixSuite.scala` 186); `VarkaTestReach.FULL` (only if read at run time);
   `VarkaEmittedBytesSuite.scala` `pairGroups` (1219-1226, only if it joins a group of options that
   read one part of the emission); `VarkaIrFuzzSuite.scala` 187 `streamOptions` **must not**
   get it (a comment says that list is frozen and later options are drawn from `ownStream`).
10. `sql/varka/matrix/skips.tsv`: a line per answer test the configuration breaks by design.
    `sql/varka/matrix/*seconds.tsv` are generated timing tables; they name the option after a
    matrix run (do not hand-edit).
11. Generated: `sql/varka/emitted_bytes.json` (bytes under every option arm) by
    `dev/varka_regen_generated.sh --all`; `coverage.json`/`docs/sql-varka.md` only if the
    option changes a documented row's text. The matrix configurations themselves are derived from
    `TABLE` (`VarkaMatrixRuns.java` 242), not listed anywhere else; the `varka-option-matrix` workflow
    and `dev/varka_matrix.sh` need no edit.
12. Documentation: the `docs/sql-varka.md` option text if it is user-facing (an emit option is not a
    SQL configuration; the two surfaces are separate; a SQL config would need a binding policy,
    `varka_lint.py`), a skills section if it carries a lesson, the plan file's section 4 "Files".
13. Test for the option: a suite under the option, both values, in a `Varka...Suite` so the matrix finds it
    (`VarkaMaskFromWordSuite` is the model for a flag of a lowering).

#### 4.3 Commands

```
build/sbt 'catalyst/testOnly *VarkaEmitOptionSuite *VarkaMatrixSuite *VarkaEmittedBytesSuite'
build/sbt 'catalyst/testOnly *VarkaIrFuzzSuite'
dev/varka_emit.sh "year(d)" --options demoOption=true      # prints bytes under the variant
dev/varka_matrix.sh ...                                     # one configuration (header gives flags)
dev/varka_regen_generated.sh --all
dev/varka_ops/pre-pr-slot.sh <worktree>                     # the gate sweeps the options the diff names
```

#### 4.4 How I would know I missed none

By the documents: `VarkaEmitOptionSuite` (table equals the record, defaults equal the table,
each entry reads and sets its own component, the fuzzer has values), `VarkaEmittedBytesSuite`
(inventory), the gate's arm sweep, which picks configurations whose names appear in the diff of
`VarkaEmitOption.java`, `VarkaEmitOptions.java` or `VarkaMatrixRuns.java` (`dev/varka_ops/README.md`
"The sweep's scope"). By search: `git grep -n -w narrowHalfSpecies` (a sibling of the same type and
default) listing every non-plan file, then `git grep -n -w demoOption` must show a place for each of
those files that is not specific to that option's behaviour; the pre-PR gate's
`dev/varka_push_check.py` and the matrix on the PR are the final net. The missing piece: nothing
tests the hand lists (`C2_OPTIONS`, `FULL`, `pairGroups`) against the option's real behaviour; an
option read by C2 but absent from `C2_OPTIONS` is silent.

### 5. Half (c): the map

#### 5.1 Top-level places (paths)

| what | where |
|---|---|
| project's agent rules, plans, lessons, generated records | `sql/varka/` (`AGENTS.md`, `VISION.md`, `WALKTHROUGH.md`, `ADDING_*.md`, `HARDWARE.md`, `ISSUES.md`) |
| the engine module (Java, no parent pom; the Arrow-backed evaluators and kernels' support) | `sql/varka/engine/` (`src`, `benchmarks`, `pom.xml`) |
| compiler, IR, emitter, runtime | `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/Varka*.java` (compiler families, 12 files) and `.../codegen/varka/` (82 files, 37,363 lines: IR, emitter, options, lowerings, warm-up) |
| exec nodes and the rule that inserts them | `sql/core/src/main/scala/org/apache/spark/sql/execution/Varka{ColumnarRule,ProjectExec,FilterExec,ColumnarToRowExec,RowToColumn,InputRows}.scala`, `core/.../execution/varka/`, `VarkaFallbackPredicate.java`, `VarkaExecMetrics.java` |
| tests | `sql/catalyst/src/test/{java,scala}/.../codegen/varka/` (149 Java + 79 Scala files); sql/core tests `Varka*Suite.scala`; test helpers `VarkaMatrixRuns`, `VarkaTestReach`, fixtures |
| plans, one folder per milestone | `sql/varka/plans/m1 ... m10`, `PLAN.md`/`SCOPE.md`, `VARKA-<n>.md`, `READING_*.md`, `results/`, templates `TEMPLATE_TASK.md`, `PLAN_TASK_TEMPLATE.md` |
| lessons | `sql/varka/skills/*.md` (8 files, 546 KB), indexed by `SKILLS.md` at the root (generated) |
| generated records (never edited by hand) | `sql/varka/{coverage.json,emitted_bytes.json,width_audit.json,types.tsv,operator_census.json,emit_cost_audit.json,golden_corpus.json}`, `plans/m8/census.tsv`, `docs/sql-varka.md` blocks, `sql/varka/coverage/`, `sql/varka/census/` |
| proofs | `sql/varka/proofs/` (SMT files; `VarkaProofFiles.java` renders them) |
| option matrix data | `sql/varka/matrix/` (`skips.tsv`, timing tables) |
| fuzz, spark suites, mutation, traffic, spikes, demo | `sql/varka/{fuzz,spark_suites,mutation,traffic,spikes,demo,baselines}/` |
| CI budgets and tier data | `sql/varka/ci/` |
| benchmarks | classes in `sql/catalyst/src/test/java/org/apache/spark/sql/Varka*Benchmark.java` (23) and `sql/varka/bench/`; committed results and bands in `sql/catalyst/benchmarks/Varka*-jdk25-*.txt` |
| tools | `dev/varka_*` (106 entries), `dev/varka_ops/` (gate, push, matrix, watch), `dev/varka_canary/` (probes) |
| CI | `.github/workflows/varka-*.yml` (16) plus `build_and_test.yml` |
| public docs | `README.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `docs/sql-varka.md` (2,927 lines) |

#### 5.2 The compiler's classes, by size

Size in lines; "hard to find" says whether size made the right place hard to find for me.

| class | lines | owns | hard to find? |
|---|---|---|---|
| `varka/VarkaVectorIR` | 3,712 | the IR: a sealed interface of records, one per node | no; the class javadoc still says "a value over int32 lanes", which is stale |
| `varka/VarkaVectorWalk` | 2,789 | one output DAG's emission: `emitValue` is the dispatch over the IR | yes: three classes look like "the emitter" |
| `varka/VarkaBodyEmitter` | 2,258 | the methods of the emitted class: driver, loop methods, epilogues | yes (same) |
| `varka/VarkaLoopEmitter` | 2,040 | the class-file builder for a fused kernel | yes: its javadoc says "the loop ... six-step shape of the hand-written `DateVectorOps`", the walk and body live elsewhere |
| `varka/Analysis` | 2,008 | what is known before emitting: validation, DAG order, validity-word algebra, arm chains | no |
| `varka/VarkaChronoLowering` | 1,918 | all calendar lowerings | no |
| `varka/Slots` | 1,874 | local-variable slot planner | no |
| `varka/VarkaEmitOptions` | 1,720 | the option record and its builder (and `VarkaEmitOption` 695: the table) | no, but the 60-value positional `DEFAULTS` is a trap |
| `VarkaConditionCompiler` | 1,814 | predicate and condition families (comparisons, IN, `IF`/`CASE`/`coalesce`) | no |
| `VarkaNodeCompiler` | 1,668 | the recursive node compiler: the chain of families, the operand helpers | yes: the `switch` is not here but per family |
| `VarkaDecimalCompiler` | 1,630 | decimal arms (rows 347 to 349 and 360 land here) | no |
| `VarkaExpressionCompiler` | 1,418 | the classifier: which projection entries to compile, under what budgets; the entry point | the AGENTS.md claim of a 1,900-line `match` here is stale |
| `varka/VarkaKernelPlan` | 1,082 | the kernel's lanes decided once from roots and options | no |

The compile path: `VarkaColumnarRule` (sql/core) -> `VarkaExpressionCompiler` (classifier and
budgets) -> `VarkaNodeCompiler` and the `Varka*Compiler` families (Catalyst expression -> IR) ->
`VarkaKernelPlan`, `Analysis`, `Slots` -> `VarkaLoopEmitter`/`VarkaBodyEmitter`/`VarkaVectorWalk`
(IR -> class file) -> `CompiledVarkaProjection` and the evaluators. `coverage.json` is the lookup of
which expression class is fused.

#### 5.3 Where a decline reason comes from

At the point the compiler gives up: each `Varka*Compiler.java` calls `sink.note("<reason text>",
expr)` on the per-query `DeclineSink` (`codegen/DeclineSink.java` 378; only the first, innermost
note of an entry is kept), which hands a `VarkaDecline(reason, expr)` record
(`codegen/VarkaDecline.java`) to the exec node; the exec nodes print it in verbose `EXPLAIN` and
logs. Reasons are free-text strings at each call site (no enum, no catalogue); the per-batch runtime
reasons are a separate, fixed vocabulary, `VarkaFallbackAccounting.REASONS`
(`sql/core/.../execution/varka/`), counted as metrics and read by tests through
`VarkaFallbackReasons` and `VarkaFallbackReasonSuite`. The per-expression declines of Spark's own
suites are in `sql/varka/spark_suites/expression_declines.tsv`. I found this by search; `WALKTHROUGH.md`
mentions "why the others decline" but not the mechanism.

#### 5.4 The tools under `dev/` whose names start with `varka`

Counts are of the 106 entries `ls dev | grep ^varka`. "Indexed?" = is it named in
`sql/varka/AGENTS.md`, `CONTRIBUTING.md`, `README.md`, `SKILLS.md`, the skills files or
`dev/varka_ops/{README,INDEX}.md`. A single list of all of them exists nowhere
(`dev/README.md` is Spark's).

| group | scripts | indexed? |
|---|---|---|
| Build and regenerate | `varka_gate.sh`, `varka_regen_generated.sh`, `varka_regen_inputs.py`, `varka_generated_merge.py`, `varka_gen_coverage.sh` (+dir), `varka_toc.py`, `varka_catalogue_move.sh`, `varka_new_lane.py`, `varka_errorprone.sh`, `varka_unused_imports.py`, `varka_prove.sh`, `varka_golden_suite.sh`, `varka_platform.sh`, `varka_hsdis_build.sh` | mostly: `AGENTS.md` and CONTRIBUTING name the main four; `varka_regen_inputs.py` and `varka_catalogue_move.sh` have no document |
| Test and matrix | `varka_matrix.sh`, `varka_matrix_{archive,bins,ci,pick,report,reuse,run,scope,shards,stragglers}`, `varka_nightly.sh`, `varka_sweeps.py`, `varka_scoped_suites.sh`, `varka_suite_shards.py`, `varka_surface_shards.py`, `varka_tier_split.py`, `varka_compiled_fuzz.{py,sh}`, `varka_pit*`, `varka_series.sh`, `varka_new_tests.py`, `varka_session_lint.py`, `varka_scheduled_failure*.py`, `varka_ci_{admit,ledger,times}.py`, `varka_compile_state.py`, `varka_bin_units_test.py` | `varka_matrix.sh` yes; 12 of the group (`matrix_{archive,ci,pick,report,reuse,run,scope,shards,stragglers}`, `compiled_fuzz.*`, `scoped_suites`, `suite_shards`, `scheduled_failure*`, ...) in no document |
| Benchmark | `varka_bench_{band,canary,cpu_retry,diff,gate,ids,merge,pair,regen,repeat,sections,surface}`, `varka_bytes_moved.py`, `varka_calibration.py`, `varka_quote_check.py`, `varka_datapath.sh`, `varka_canary/` | yes in `sql/varka/AGENTS.md` 112-148 for regen, diff, band, repeat, canary, quote; `bench_cpu_retry.py`, `bench_pair.py`, `bench_gate`, `bench_ids` partly or not |
| Plans and pull requests | `varka_task_new.sh`, `varka_plan_lint.py`, `varka_issues.py`, `varka_msg_check.py`, `varka_precommit.sh`, `varka_prepush.sh`, `varka_push_check.py`, `varka_lint.py`, `varka_pr_sweep.sh`, `varka_worktree.sh`, `varka_scope.py`, `varka_post_page.py`, `varka_good_first_tasks.txt`, `varka_ops/` (gate, push, matrix link, watchers) | yes for `varka_ops/` (own `INDEX.md`), `task_new`, `msg_check`, `precommit`; `plan_lint`, `prepush`, `scope`, `good_first_tasks` have no document |
| Census and coverage | `varka_census.py`, `varka_tpc_census.sh`, `varka_trap_census.py`, `varka_word_census.sh`, `varka_deopt_census.{py,sh}` (+guard tsv), `varka_codegen_report.py` | `census.py` yes; the others no |
| JIT and assembly | `varka_emit.sh`, `varka_hsdis_build.sh`, `varka_c2_report.py`, `varka_inlining_cliff.{py,sh}`, `varka_deopt_cycle.{py,sh}`, `varka_jfr_frames.py`, `varka_jfr_ir_hash.py`, `varka_kernel_history.{py,sh}` | `emit.sh`, `hsdis_build.sh` yes; the rest in `skills/the-jit.md` by name only for some; `jfr_ir_hash`, `kernel_history` no |
| Other | `varka_bg.sh`, `varka_deadline.sh`, `varka_members.py`, `varka_callgraph.py`, `varka_traffic_snapshot.sh`, `varka_platform_report.py`, `varka_nullaway_pending.txt`, `varka_quote_allowlist.txt` | `bg.sh`, `members.py`, `callgraph.py` in AGENTS/template; others no |

Only one index exists: `dev/varka_ops/INDEX.md`, for the 15 scripts of `dev/varka_ops/`, a model of
what the other 106 lack (tool, purpose, inputs, writes and exit codes, when). A mention count over
the key documents found 37 of the 106 entries mentioned nowhere in them.

### 6. The log

Format: **kind N**, file and line, what was missing or wrong, then the one sentence or pointer that
would have fixed it.

#### GUESS (6)

* **G1** root `AGENTS.md` (whole file): nothing says that Varka lives in `sql/varka/` or that its
  rules are in `sql/varka/AGENTS.md` and `CONTRIBUTING.md`; I guessed from `ls sql`. Fix: a "Varka"
  section at the top of root `AGENTS.md`: "Varka tasks: read `sql/varka/AGENTS.md` and
  `CONTRIBUTING.md` first; the Spark sections below do not apply to vecbricks/varka".
* **G2** `sql/varka/AGENTS.md` 208-212: how to continue a row that already has a plan and
  part-branches (360a to 360d); the branch and worktree name for a part, and what to start from. Fix:
  one sentence after the `varka_task_new.sh` paragraph: "A row with a plan: create the worktree
  with `git worktree add -b varka-<n><part>-<topic> ../varka-<n><part> origin/master`".
* **G3** no document: who merges a pull request. Fix: one line in `dev/varka_ops/AGENTS.md`:
  "Agents never merge; the owner merges after the matrix link on the PR is green".
* **G4** no document: the steps that update a row and plan when a part lands (marker change, "what is
  left" text, plan section 9/10, new rows for findings, the issue mirror). Fix: a section in
  `TEMPLATE_TASK.md` or `sql/varka/AGENTS.md`, "Landing a part", of five lines.
* **G5** `VarkaEmitOption.java` 70-130: which `Reason` and `Rendering` a new option takes. Fix:
  a one-line rule per reason in the javadoc and "default-false: `POSITIONAL`".
* **G6** `plans/m8/VARKA-360.md` 5 vs `varka_task_new.sh`: that 360a, 360b... are branch parts (names
  in the plan), I assumed the merge order from "needs 359a" and checked nothing against git.
  Fix: a "State" line at the top of each row plan: "parts merged: ...; next: 360a".

#### SEARCH (7)

* **S1** finding row 360: `grep -n '^| 360 ' plans/m*/PLAN.md` through a 900 KB file; no index from a
  row number to a file and line. Fix: `dev/varka_plan_lint.py` (or a new `dev/varka_row.py <n>`)
  named in `sql/varka/AGENTS.md` "plans" as the row finder, printing the row, its plan file and its
  milestone.
* **S2** the meaning of a row's status marker: only in the docstrings of `dev/varka_issues.py` 30-46
  and `dev/varka_plan_lint.py` 40-50. Fix: a table of markers in `CONTRIBUTING.md` "Finding work".
* **S3** the hand-kept option lists (`C2_OPTIONS`, `FULL`, `pairGroups`, `streamOptions`): found only by
  grepping `narrowHalfSpecies`. Fix: an `ADDING_AN_OPTION.md` or a list in `VarkaEmitOption.java`'s
  javadoc naming them.
* **S4** `dev/varka_plan_lint.py` and its CI step: found by `ls dev`, then `varka_precommit.sh`. Fix:
  name it in `sql/varka/AGENTS.md` where plans are described.
* **S5** where decline reasons come from: found by grepping `VarkaDecline`, `DeclineSink`, `sink.note(`.
  Fix: a paragraph in `WALKTHROUGH.md` or `docs/sql-varka.md` "why a projection declines".
* **S6** a list of the `dev/varka*` tools: none; I ran a mention count to find the unindexed ones. Fix:
  `dev/varka_INDEX.md` in the shape of `dev/varka_ops/INDEX.md`, checked by a lint.
* **S7** the current milestone: read `m8/PLAN.md` 12.5 and `m9/PLAN.md` 3 to settle it. Fix: one line
  in `CONTRIBUTING.md` and `plans/README`: "In flight: m8 (closing); next: m9".

#### CONFLICT (9)

* **C1** PR title: root `AGENTS.md` -> `.agents/pr-workflow.md` 3 "`[SPARK-xxxx][COMPONENT] Title`"
  and `create_spark_jira.py`; `CONTRIBUTING.md` "`[VARKA-<id>] <what the change achieves>`";
  `skills/working-in-this-repo.md` 15 "No JIRA IDs. Titles are `[VARKA] <short summary>`". Fix: root
  `AGENTS.md` "Pull Requests": "In vecbricks/varka the title is `[VARKA-<n>] ...`; ignore
  `.agents/pr-workflow.md`"; correct `working-in-this-repo.md`.
* **C2** remotes: root `AGENTS.md` pre-flight "`origin` for the fork, `upstream` for `apache/spark`;
  DO NOT push to upstream; open PRs against `master` on the upstream repo" vs
  `working-in-this-repo.md` 11 "`origin` = `vecbricks/varka` (PR base), `fork` = `MaxGekk/spark`
  (PR head)" and `dev/varka_ops/AGENTS.md` ("A push to `origin` reaches the shared repository").
  A cold agent following the root file pushes to the shared repo. Fix: the root section from G1.
* **C3** plan template: `CONTRIBUTING.md` "a task's plan file starts from `PLAN_TASK_TEMPLATE.md`"
  vs `sql/varka/AGENTS.md` 208-212 and `varka_task_new.sh` use `TEMPLATE_TASK.md`; the two have
  different sections (1-9 vs 1-5 + more), and `VARKA-360.md` follows neither (sections 0 to 10).
  Fix: delete one, point `CONTRIBUTING.md` at the other.
* **C4** the gate: `CONTRIBUTING.md` "`dev/varka_gate.sh` is the standing gate in one command" and
  `working-in-this-repo.md` 17 "The standing gate is one command" vs `dev/varka_ops/AGENTS.md` "Gate
  before every push: `pre-pr.sh` through `pre-pr-slot.sh`, never a hand-picked subset". Fix: one
  sentence on which runs when ("`varka_gate.sh` is the laptop's full check; `pre-pr.sh` is the one
  before a push").
* **C5** current milestone: `CONTRIBUTING.md` 28-30 "the highest-numbered `m<n>/PLAN.md`" (now m9)
  and, in the same sentence, "`m8/PLAN.md` as this is written"; `varka_issues.py` and
  `varka_task_new.sh` use "highest". Fix: C5 as in S7.
* **C6** branch name: `working-in-this-repo.md` 17-18 "`varka-<topic>` ... one commit ahead per PR" vs
  `varka_task_new.sh` `varka-<n>`, and the actual local branches (`varka-360a-sums` ...). Fix: one rule.
* **C7** "take a task" first: `CONTRIBUTING.md` "open a GitHub issue with the 'Varka: take a task'
  template, so the plan is agreed first" vs `sql/varka/AGENTS.md` 208 (start with
  `varka_task_new.sh`, the issue is a mirror of the row, `varka_issues.py` 22-24 says one exists for
  each open row). Fix: say the issue is only for a task with no row.
* **C8** root `AGENTS.md` "New edits: ask the user to choose a worktree or a branch" vs
  `varka_task_new.sh` that always makes a worktree and `varka_ops/AGENTS.md` that assumes one. Fix: G1.
* **C9** `sql/varka/AGENTS.md` 303 "Error Prone and NullAway join the checks when VARKA-266 lands" vs
  `dev/varka_ops/INDEX.md` `extra-gate.sh` "lint-java, Error Prone, ...", `dev/varka_errorprone.sh`
  and `varka_nullaway_pending.txt`: Error Prone already runs. Fix: say which of the two is pending.

#### STALE (6)

* **T1** `sql/varka/AGENTS.md` 15: "the 1900-line `match` in `VarkaExpressionCompiler`";
  `wc -l` gives 1,418, and the matching is spread over twelve `Varka*Compiler` families. Fix: "the
  family compilers' matches (`VarkaNodeCompiler` chain)".
* **T2** `CONTRIBUTING.md` 30: the milestone "in flight ... `m8/PLAN.md` as this is written" (m9
  exists); "Every milestone ends with a closing task ... milestone 6's is VARKA-282" (old example).
* **T3** `dev/varka_plan_lint.py` docstring: "default file is `sql/varka/plans/m8/PLAN.md`": an m9 row
  is not checked by default.
* **T4** `dev/varka_msg_check.py pr --help`: ends with a `KeyError: '--body-file'` traceback, while
  `--help` alone prints the usage. Fix: handle `--help` after the subcommand.
* **T5** `varka/VarkaVectorIR.java` class javadoc: "A node is a value over int32 lanes" (IR now has
  long, decimal and floating nodes) and `VarkaLoopEmitter`'s "six-step shape of the hand-written
  `DateVectorOps` kernels".
* **T6** `dev/varka_ops/README.md`: `VARKA_OPS_HOME` default `~/.varka-ops`; `sql/varka/AGENTS.md`
  line 229 and my own pointers speak of `dev/varka_ops/` only; a new agent has no `~/.varka-ops`
  and the scripts do not say what `common.sh` creates on first use except in the table. Minor; and
  `VARKA_TRAILER` defaults to a model name ("Claude Opus 5.5") the agent may not be.

#### DEAD END (3)

* **D1** `varka_task_new.sh 360 "..."` -> exit 1, "this task has a plan": no document follows the
  refusal. Fix: G2.
* **D2** root `AGENTS.md` "Pull Requests" -> `.agents/pr-workflow.md`: Spark's JIRA flow; its
  instruction to create a JIRA ticket with `dev/create_spark_jira.py` cannot apply. Fix: C1.
* **D3** `dev/README.md` (Spark's script index): no mention of the 106 Varka scripts. Fix: one line
  pointing to the Varka index (S6).

#### OVERLOAD (3)

* **O1** `sql/varka/AGENTS.md` (33 KB, 465 lines): a task start needs about 15% of it (lines 72-110, 208-240,
  344-363); the benchmark methodology (102-206, 4 KB of one paragraph) comes before it. Fix: a
  10-line "Starting a task" box at the top and the rest in sections with a table of contents.
* **O2** `plans/m8/PLAN.md` (900 KB) and row 360's cell (4.8 KB, six dated layers, 21 parenthetical
  notes inside one table cell; the "Done when" opens after 3,000 characters): reading the row meant
  parsing the stack. Fix: each row's cell holds status, one line of what is left, the done-when and a
  link; the history goes to the plan file's section 10.
* **O3** `skills/working-in-this-repo.md` (26 KB): the "Repo Workflow" list mixes the live rule
  with history (a tool bug from 12 September); an agent has to read it to learn the remotes. Fix:
  move the facts of the Workflow list into the root-level Varka section (G1).

#### GOOD (9)

* **Y1** `sql/varka/AGENTS.md` 208-212: the task start command in one paragraph, with its outputs.
* **Y2** `dev/varka_task_new.sh` header: usage, side effects, refusals.
* **Y3** `dev/varka_ops/INDEX.md` and `AGENTS.md` "Opening or fixing a pull request": the six steps in
  order with exact commands, one table of inputs and exit codes.
* **Y4** `dev/varka_msg_check.py --help`: every PR text rule and the size cap in one screen.
* **Y5** `VarkaEmitOption.java` class javadoc: the five places for a new option in one sentence.
* **Y6** `sql/varka/AGENTS.md` 166-198: how a new option reaches the fuzzer and matrix by itself, and
  the tag and skip-file rules.
* **Y7** `CONTRIBUTING.md` "Finding work" and "Pull requests": what a row is and how status reads.
* **Y8** `sql/varka/ADDING_AN_EXPRESSION.md` / `ADDING_A_TYPE.md`: numbered steps each ending in
  a command (the form an `ADDING_AN_OPTION.md` should take).
* **Y9** `VarkaMatrixRuns.java` `C2_OPTIONS` and `VarkaTestReach.FULL` javadocs: each hand list
  says why its members are there.

### 7. Five changes to the documents

1. **Root `AGENTS.md`: a first section "This is vecbricks/varka".** Say that the Spark rules below
   hold for Spark's code only; for Varka read `sql/varka/AGENTS.md` and `CONTRIBUTING.md`; remotes
   are `origin` = vecbricks/varka (PR base), `fork` = personal (push); PR title `[VARKA-<n>] ...`;
   ignore `.agents/pr-workflow.md` and JIRA; the one attribution line. (Fixes G1, C1, C2, C8, D2.)
2. **`sql/varka/AGENTS.md`: a "Starting a task" box in the first 20 lines**: how to find a row
   (`grep '^| <n> |'`, or a row-finder script), the marker table, the new-task command and the
   continuing-a-row command and branch names for a part, who merges, and the five steps that
   update the row and plan when a part lands. (Fixes G2, G3, G4, S1, S2, D1, O1.)
3. **A single index of the `dev/varka*` tools**, `dev/varka_INDEX.md` (or a section of `SKILLS.md`)
   in the shape of `dev/varka_ops/INDEX.md`, grouped as in 5.4, with a lint that fails when a
   `dev/varka*` entry is not in it. (Fixes S4, S6, D3; today 37 of 106 are in no document.)
4. **`sql/varka/ADDING_AN_OPTION.md`**, in the form of `ADDING_AN_EXPRESSION.md`: the five code
   edits of the `VarkaEmitOption` javadoc with the line landmarks, how to choose `Reason` and
   `Rendering`, the hand lists (`C2_OPTIONS`, `FULL`, `pairGroups`, the frozen `streamOptions` that
   a new option must stay out of), the suites to run, the regeneration, and "done" as a
   `git grep -w <sibling>` comparison. (Fixes S3, G5.)
5. **Settle the contradictions between the process documents**: one plan template
   (`TEMPLATE_TASK.md`, deleting or pointing `PLAN_TASK_TEMPLATE.md`), one gate sentence
   (`varka_gate.sh` vs `pre-pr.sh`), one milestone-in-flight line in `CONTRIBUTING.md` kept by the
   milestone close, one title rule in `working-in-this-repo.md`, `varka_msg_check.py pr --help`
   fixed. (Fixes C3 to C7, C9, T2 to T4.)

### 8. Did the code's structure block me?

Partly, in three places I met:

* **Three classes that look like "the emitter"**: `varka/VarkaLoopEmitter.java` (2,040 lines, javadoc
  line 2: "Emits a fused vector loop for a `VarkaVectorIR` DAG"), `VarkaBodyEmitter.java` (2,258) and
  `VarkaVectorWalk.java` (2,789, `emitValue` is the dispatch over the IR). The first's name and
  javadoc lead to the wrong class for a change to what a node emits.
* **`VarkaEmitOptions.DEFAULTS`** (`VarkaEmitOptions.java` 876-886): a positional constructor
  call with about 60 untyped literals; adding `demoOption` means counting commas, and a swapped
  pair of booleans compiles. The test "the defaults hold every option at its table default" would
  catch it, only after a build.
* **The classifier and the node compiler** (`VarkaExpressionCompiler` 1,418 lines and
  `VarkaNodeCompiler` 1,668 lines plus twelve family files): no document names which file holds a
  given Catalyst expression's arm, except `coverage.json`; and `sql/varka/AGENTS.md` 15 names a
  `match` that is not there (T1).

The sizes themselves (a 3,700-line IR file) did not hide anything I looked for; the documents
were the larger problem.
