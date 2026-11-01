# VARKA-539: An emission outside every reach window is reported and charged to its own JVM

## 0. What exists now (admission check)

From master at `239995f4fe4`, and from the fork's master Build 41294586758 (`varka-master.yml`,
head `9c9f6ef5895`), whose `varka-matrix-reach-sql-*` and `varka-scoped-junit-sql-*` artifacts
were downloaded with `gh api` GETs on 1 November 2026. Nothing was dispatched and no JVM was run.

1. **`VARKA-536` is merged.** #1171 (`9aa6ee74c63`) gave `VarkaLiteralLiftSuite` the
   `VarkaReachWindow` trait (`sql/catalyst/src/test/scala/.../varka/VarkaReachWindow.scala`).
   The trait opens the suite's window in `run` and closes it in a `finally`. The row's citations
   are to master's lines and still hold:
   - `VarkaTestReach.java:147-151`, the shutdown hook that writes `*<TAB>*<TAB>names`;
   - `:358-366`, `Recorder.note`, which puts a compile with neither a test nor a suite window
     open on the JVM-wide set `outside`;
   - `:540-544`, `Record.moving`, which adds `everywhere` to every test;
   - `:581-582`, `read`, which collects every `*<TAB>*` line of the file into `everywhere`;
   - `dev/varka_matrix_bins.py:173-174`, `read_reach`, which does the same.
2. **No live case on master.** The Build's three sql reach parts hold six `*<TAB>*` lines, two a
   file, and every one is `-`. So no configuration is charged to every test today.
3. **But `-` does not mean "no stray emission".** `Recorder.note` adds `got.moved()`, the
   configurations that change the kernel (`:362`). An emission outside every window that no
   configuration moves leaves the line `-`. So the record cannot say whether any of the five
   unwindowed suites emits. It says only that none emits a kernel a configuration moves.
4. **The five suites ran in recording JVMs.** Build 41294586758's JUnit reports place each one in
   a JVM of a job that recorded:
   - `VarkaSQLQueryTestSuite` and `VarkaGoldenExclusionsSuite` in sql 2 of 3, JVM `sql-0`;
   - `VarkaLaneArrowSuite` and `ArrowCachedBatchSerializerSuite` in sql 2 of 3, JVM `sql-1`;
   - `VarkaEvaluatorComponentsSuite` in sql 3 of 3, JVM `sql-0`.

   `VarkaSQLQueryTestSuite` runs nothing without `varka.goldenSuite.arm`
   (`VarkaSQLQueryTestSuite.scala`, the class comment), so in the Build it cannot emit.
   `VarkaEvaluatorComponentsSuite` builds `VarkaFusedKernel`s directly (`:31`). Whether that
   reaches `VarkaCompileObserver` the plan did not check (**[I]**).
5. **The two JVMs of a job share one record file, interleaved.** `dev/varka_scoped_suites.sh:117`
   runs `--split 2 -j 2` with one `-Dvarka.matrix.reachRecord` for both JVMs, and
   `VarkaTestReach.append` opens the file in append mode per line (`:232-238`). In each
   downloaded part:
   - both JVMs' `#<TAB>covers` headers are lines 1 and 2;
   - the test lines alternate between the two JVMs' suites (in sql 3 of 3,
     `VarkaGoldenCorpusSuite` and `VarkaFilterRaiseOrderSuite` alternate from line 3);
   - the two `*<TAB>*` lines fall at lines 948 and 1573 of 1573.

   So no reader can tell which JVM wrote a line. The option matrix then concatenates the jobs'
   parts (`varka-option-matrix.yml:182`), which loses the job boundary too.
6. **A third reader.** `VarkaTestUse` (row 508) writes its own JVM-wide line, `U<TAB>*<TAB>names
   <TAB>*` (`VarkaTestUse.java:98-101`). Its writer is already one file a JVM, `use-<pid>.tsv`
   in the record's directory (`:56`, `:75`), so its lines never interleave. The merge is the
   reader's: `read_use` (`dev/varka_matrix_bins.py:388-419`) reads every file into one map keyed
   by (suite, test), so each file's `U<TAB>*<TAB>...<TAB>*` line lands on the one key
   `("*", "*")`. `:499` joins that key's classes into every test, and `:502` already keeps its
   digests out. `select` joins the reach's `("*", "*")` into each test it selects (`:917`).
7. **The runner sees every suite start.** Each recording JVM runs `VarkaSuiteRunner`
   (`dev/varka_matrix.sh:346`), which attaches its own ScalaTest reporter with `-C`
   (`VarkaSuiteRunner.java:145`) and then runs the JUnit classes through the JUnit Platform
   launcher. A suite with no window still starts through one of the two, so the runner knows
   which suite is running when no window is open.
8. **The warning has no outlet in CI as things stand.** Each JVM's stdout and stderr go to its
   `run.log` (`dev/varka_matrix.sh:364-367`), which the Build uploads in the JUnit artifact but
   does not print. A line printed by the JVM reaches nobody unless a step lifts it into an
   annotation.

### What differs from the row text

- The row says `VARKA-536` is "on branch `varka-536-reach-jvm-line` and not yet merged". It
  merged as #1171.
- Step 2's "each unwindowed suite that step 1 finds emitting" has no case found yet. The record
  cannot show one (item 3), and step 1's warning is what will. One of the five,
  `VarkaSQLQueryTestSuite`, cannot emit in the Build (item 4).
- Step 3 has to reach a third reader. `VarkaTestUse`'s record is already one file a JVM, but
  `read_use` merges every file's JVM-wide line into one key (item 6), so the fix there is in the
  reader alone.

### Corrections from the plan's review (1 November 2026)

The first draft keyed the warning by the first frame outside the codegen package alone. For the
sql suites that frame is a shared production class (`VarkaFilterEvaluator`,
`VarkaKernelEvaluator`, `VarkaColumnarToRowExec`, `VarkaProjectExec`), often on a task thread
with no test frame, so "once per call site" would have shown the first suite and hidden the rest.
2.1 now keys by the running suite and the site, and test 4 plants the task-thread case. The draft
also named each JVM's file by a `-Dvarka.matrix.jvm` leaf, which missed the option matrix's
`varka-reach.tsv` glob, and said `VarkaTestUse` had the interleaving flaw, which it does not
(item 6). 2.2 and 2.3 are rewritten for both.

## 1. Goal and non-goal

**Goal.** A compile or emission noted outside every window is reported with its thread and stack,
once per call site, where a reader of the Build sees it. A JVM-wide line charges only the tests
of the JVM that wrote it, in every reader. No sql Varka suite emits outside a window.

**Non-goal.** No change to what a test's or a suite's line records. No catalyst recording (row
518's work, which would bring catalyst's unwindowed suites into scope). Recording stays a
warning, never a failure of the recorded run.

## 2. Design

### 2.1 The warning

In `Recorder.note`, on the branch where both `current` and `suite` are null, the recorder walks
the stack with `StackWalker`. The site is the first frame whose class is outside
`org.apache.spark.sql.catalyst.expressions.codegen`, its subpackages and `VarkaTestReach`'s
observer.

The site alone cannot be the key. For a sql suite it is a production class every suite shares
(`VarkaFilterEvaluator`, `VarkaKernelEvaluator`, `VarkaColumnarToRowExec`, `VarkaProjectExec`),
and the note often comes on a Spark task thread whose stack holds no test frame. Nor can the key
take the first test-class frame on the stack: a task thread has none, and on the test thread the
first one is often a helper every suite shares, such as `QueryTest.checkAnswer`. So the key is
the pair (running suite, site). The running suite is a JVM-wide field of the recorder that
`VarkaSuiteRunner` sets (item 7): its ScalaTest reporter on each `SuiteStarting` event, and its
JUnit launcher's listener on each class's `executionStarted`. Outside the runner the field is
`-`. ScalaTest delivers reporter events from a dispatch thread, so a stray in a suite's first
moments may be keyed to the suite before it (**[I]**: the plan did not measure the lag; the line's
thread and stack still show where it came from).

On the first note of a key, the recorder writes three things:

- to stderr, `VARKA_REACH_STRAY` with the running suite, the thread's name, the site and the top
  40 frames;
- to its record, a line `#<TAB>stray<TAB><suite><TAB><site><TAB><thread><TAB><configurations or
  ->`, which every reader already skips as a `#` line;
- nothing on later notes of the same key. One shared site thus reports once for each suite that
  reaches it, and the lines a JVM writes are bounded by its suites times their sites.

The warning fires whether or not any configuration moved the kernel (item 3). Moving is what
costs the matrix today, but an unmoved stray becomes a moved one the day an option changes its
bytes.

`dev/varka_scoped_suites.sh`, after the run, prints each `VARKA_REACH_STRAY` block's first line
from each JVM's `run.log` (`target/varka-scoped/runs/*/<leaf>/run.log`) as a `::warning::`
annotation naming the suite, the site and that `run.log` for the stack. The option matrix's build
step prints each `#<TAB>stray` line of the record it reads, naming the Build run it came from, so
a stray shows on both the PR Build and the matrix.

### 2.2 One file a JVM, named by its pid

With `-Dvarka.matrix.reachRecord=<dir>/varka-reach.tsv`, the recorder writes
`<dir>/varka-reach-<pid>.tsv`, the naming `VarkaTestUse` already uses for its own record (item 6).
It opens the file with `#<TAB>jvm<TAB><pid>` ahead of its `#<TAB>covers` line. The two JVMs of a
job then write two files and never interleave (item 5). Without the property nothing is written,
as today.

The header is what keeps the files apart once they are joined. Downstream of the Build, the
matrix keeps one `reach.tsv`: the bins' `--reach` (`varka-option-matrix.yml:224`), the bins' JVMs'
`-Dvarka.matrix.reachFile` (`:354`), the archive (`dev/varka_matrix_archive.sh:47-49`), the
uploaded record (`:250`) and `times --reach` (`:459`) all read that one file. So the matrix still
concatenates, and a reader opens a new segment at each `#<TAB>jvm` line, whatever pid it names,
so two runners' equal pids do not merge. Two lines change outside the recorder:

- the Build's upload path (`build_and_test.yml:1717`) becomes `target/varka-reach-*.tsv`.
  `VARKA_REACH_RECORD` keeps its value, since the recorder derives the file names from it;
- the option matrix's build step (`varka-option-matrix.yml:180-182`) tests and concatenates
  `target/varka-reach/*/varka-reach*.tsv`. That glob matches a new Build's per-JVM files and an
  older Build's single `varka-reach.tsv` alike, so a matrix run against a Build from before the
  change reads it as one segment.

This is simpler than the first draft's `-Dvarka.matrix.jvm=<leaf>` and `VARKA_REACH_JOB`. The pid
needs no property, no script change and no workflow variable. It is also simpler than a directory
of files, as `VarkaTestUse` writes, since a directory would change all five consumers above.

### 2.3 The readers scope the JVM-wide line by JVM

`VarkaTestReach.read`, `read_reach` and `select`'s joins each keep, for each `#<TAB>jvm` segment,
its tests and its own `everywhere`. A test is joined with its own segment's line. A record with
no `#<TAB>jvm` line is one segment, which is today's meaning, so a laptop record and an old
artifact read as before. A test whose lines fall in two segments takes the union of both
segments' JVM-wide lines. That case arises when the same suite runs in two JVMs (**[I]**: the
plan did not find one, since a suite runs in one JVM of one job).

`VarkaTestUse`'s writer does not change (item 6). `read_use` keeps each file's
`U<TAB>*<TAB>...<TAB>*` line with that file's tests rather than under the one `("*", "*")` key,
and `:499` joins a test with its own file's line (question 2).

### 2.4 The five suites

Once 2.1 is on the PR Build, the PR's own Build shows which suites emit outside a window. Each
suite it names mixes in `VarkaReachWindow`, in the same pull request. If none is named, none is
changed, and this plan's outcome says so (question 3).

## 3. Tests and proofs

1. `VarkaTestReachSuite`, the existing JUnit suite of the record (the recorder made
   package-visible for it): with the running suite set to `A`, two notes from one site with no
   window open write one `#<TAB>stray` line and one stderr block, and a second site writes a
   second. With the running suite then set to `B`, a note from the first site writes a third, so
   a shared site is reported for each suite. With no running suite the line names `-`. A note
   inside a test's or a suite's window writes none.
2. The same suite: `read` over a two-segment record. A JVM-wide line in segment A joins A's tests
   only, and a record with no header joins every test, as today.
3. `dev/varka_matrix_bins.py --selftest`: the same two cases for `read_reach`, over a
   concatenation of two per-JVM files and over a header-less file. For `read_use`, a directory of
   two `use-<pid>.tsv` files whose `U<TAB>*` lines differ joins each file's line to that file's
   tests only, and `select`'s joins of both records do the same.
4. A planted stray on a task thread, end to end, in a new `VarkaReachStraySuite`: a forked JVM
   (`@VarkaSuite(ownJvm = true)`, with the record set) runs `VarkaSuiteRunner` over two fixture
   suites, `A` and `B`, which the build's own discovery does not run (**[I]**: nested classes
   handed to the runner by name; how they are kept out is the build's to settle). Each fixture
   compiles with every window closed, twice, through one shared helper class that stands in for
   `VarkaFilterEvaluator`, on a worker thread from a plain executor whose stack has no test
   frame. The record holds two stray lines, `(A, helper)` and `(B, helper)`, each naming the
   worker thread, and the child's stderr holds two stack blocks. The second compile of each
   fixture adds nothing.
5. The PR's own Build: each sql job uploads two `varka-reach-<pid>.tsv` files, each with one
   `#<TAB>jvm` header, their `*<TAB>*` lines are `-`, and the jobs print no stray annotation,
   unless 2.4 finds a suite, which the pull request then fixes.
6. The option-matrix dispatch on the PR's head (section 5's order): its build step finds the
   Build's per-JVM files through the new glob, and its `tests` count equals the files' non-`#`
   lines summed. The glob's match of an older Build's single `varka-reach.tsv` rests on tests 2
   and 3's header-less case, since a dispatch reuses only the Build of its own commit.

## 4. Predictions and costs, registered before the build

- P1: the PR Build prints no stray from four of the five suites. `VarkaEvaluatorComponentsSuite`
  is the one the plan cannot call (item 4, **[I]**).
- P2: the recording's wall is unchanged within the laptop's run-to-run spread. The walk runs on
  each note outside every window, which only the unwindowed suites make, and stops at the first
  frame outside the codegen package. The stack block is built once per key. `VARKA-536`
  measured 281 s before its fix and 286 s after, four JVMs
  (`m8/results/VARKA-536-reach-jvm-line.txt`).
- P3: the matrix's skipped share per configuration is unchanged on master, since every JVM-wide
  line is `-` today (item 2). The change pays only when a stray recurs. It then costs that JVM's
  tests rather than every sql test: about a sixth of them, at six JVMs a Build.
- P4: tests 1 to 3 add under 5 s. They are in-memory records with no session, at the table's
  ratio for a session-less JUnit class (VARKA-328, about 0.03). Test 4 adds one forked JVM,
  under 30 s at VARKA-397's observed 15 to 28 s for a forked catalyst suite.

## 5. Order, files and size

| File | Change |
|---|---|
| `.../varka/VarkaTestReach.java` | stray key, warning and line; per-pid file; `read`'s segments |
| `.../varka/VarkaSuiteRunner.java` | the running suite, from its reporter and JUnit listener |
| `.../varka/VarkaTestReachSuite.java` | tests 1 and 2, beside its existing tests |
| `.../varka/VarkaReachStraySuite.java` | test 4, new, `ownJvm = true` with the record set |
| `dev/varka_matrix_bins.py` | segmented `read_reach` and `select`, per-file `read_use`, selftests |
| `dev/varka_scoped_suites.sh` | the stray annotations from each JVM's `run.log` |
| `build_and_test.yml` | the upload path `target/varka-reach-*.tsv` |
| `varka-option-matrix.yml` | the reach glob at `:180-182`, the stray annotations |
| `sql/varka/skills/testing-and-debugging.md` | a stray line; the `varka-reach-<pid>.tsv` names |
| whichever of the five suites 2.4 names | `VarkaReachWindow` |

Order: the recorder and the readers with their tests first, so the format and both readers move
together. Then the scripts and workflows. Then the pull request's own Build and an option-matrix
dispatch, whose report must show the same skips per configuration as master's last run.

Size: small, as the row says.

## 6. Out of this row

- Catalyst's recording, and its unwindowed suites (row 518).
- Failing the recorded run on a stray. That would let a recording fail a pull request's Build,
  which the recorder's `Guarded` wrapper exists to prevent.
- Windows for suites that emit only inside a test of another suite. Those are already that test's.

## 7. Questions for the owner

1. **A JVM id on each line, or one file a JVM.** The row leaves it to the plan. Recommended: one
   file a JVM, named by its pid, with a header line (2.2). It keeps every line's format, an old
   record reads as one segment, and two JVMs stop interleaving in one file (item 5). An id
   column would change every line, and three readers would have to parse it.
2. **`VarkaTestUse`'s line too.** Recommended: yes, in this row, in `read_use` alone. Its files
   are already one a JVM, but the reader merges their JVM-wide lines into one key (item 6), which
   charges one JVM's stray classes to every test row 508's selective matrix reads.
3. **Windows only where found, or for all five.** The row says the suites step 1 finds.
   Recommended: as the row says. `VarkaReachWindow` on a suite that emits nothing changes nothing
   but adds a `suite<TAB>*` line, so adding it pre-emptively hides nothing either. The
   alternative is cheap if the owner prefers it.

## 8. Done when

- a planted emission outside every window writes the stray line and prints the warning with its
  stack (test 4), and a CI job lifts it into an annotation;
- the PR Build's recording of the sql suites at the defaults prints no stray, and writes a `-`
  JVM-wide line per JVM;
- a JVM-wide line planted in one JVM's segment joins only that JVM's tests, in `read`,
  `read_reach` and `select` (tests 2 and 3).

## 9. Open points for the lead

1. Row 518's catalyst record will bring catalyst's unwindowed suites into the same question, and
   its plan should take 2.1's warning as given.
2. The option matrix's concatenation (`varka-option-matrix.yml:182`) stays a `cat`, now over the
   per-JVM files. If row 508's selective matrix starts merging records by key rather than whole,
   it has to keep the `#<TAB>jvm` segments.

## 10. Outcome
