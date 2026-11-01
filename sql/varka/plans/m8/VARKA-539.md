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
   <TAB>*` (`VarkaTestUse.java:98-101`). `dev/varka_matrix_bins.py:499` joins its classes into
   every test, and `:502` already keeps its digests out. `select` joins the reach's `("*", "*")`
   into each test it selects (`:917`).
7. **The warning has no outlet in CI as things stand.** Each JVM's stdout and stderr go to its
   `run.log` (`dev/varka_matrix.sh:364-367`), which the Build uploads in the JUnit artifact but
   does not print. A line printed by the JVM reaches nobody unless a step lifts it into an
   annotation.

### What differs from the row text

- The row says `VARKA-536` is "on branch `varka-536-reach-jvm-line` and not yet merged". It
  merged as #1171.
- Step 2's "each unwindowed suite that step 1 finds emitting" has no case found yet. The record
  cannot show one (item 3), and step 1's warning is what will. One of the five,
  `VarkaSQLQueryTestSuite`, cannot emit in the Build (item 4).
- Step 3 has to reach a third format: `VarkaTestUse`'s JVM-wide line has the same flaw (item 6).

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
observer. On the first note from a site, the recorder writes three things:

- to stderr, `VARKA_REACH_STRAY` with the thread's name, the site and the top 40 frames;
- to its record, a line `#<TAB>stray<TAB><site><TAB><thread><TAB><configurations or ->`, which
  every reader already skips as a `#` line;
- nothing on later notes from the same site.

The warning fires whether or not any configuration moved the kernel (item 3). Moving is what
costs the matrix today, but an unmoved stray becomes a moved one the day an option changes its
bytes.

`dev/varka_scoped_suites.sh`, after the run, prints each `#<TAB>stray` line of the record as a
`::warning::` annotation, naming the JVM's `run.log` for the stack. The option matrix's build
step does the same for the record it reads, so a stray shows on both the PR Build and the
matrix.

### 2.2 One file a JVM, each opened by its JVM's id

`dev/varka_matrix.sh` passes each JVM `-Dvarka.matrix.jvm=<leaf>`, which is `sql-0`, `sql-1` and
so on, its run directory's own name (`:348-352`). With the property set, the recorder writes to
`<record>` with `-<leaf>` before its extension, and opens the file with
`#<TAB>jvm<TAB><job>/<leaf>`. The job part comes from `VARKA_REACH_JOB`, which the Build's sql
job exports as its artifact's slug. Without the property it writes `<record>` with no header, as
today. The Build's upload path becomes `target/varka-reach*.tsv`, and the option matrix
concatenates the files whole, so each `#<TAB>jvm` line opens a segment.

### 2.3 The readers scope the JVM-wide line by segment

`VarkaTestReach.read`, `read_reach` and `select`'s joins each keep, for each `#<TAB>jvm` segment,
its tests and its own `everywhere`. A test is joined with its own segment's line. A record with
no `#<TAB>jvm` line is one segment, which is today's meaning, so a laptop record and an old
artifact read as before. A test whose lines fall in two segments takes the union of both
segments' JVM-wide lines. That case arises when the same suite runs in two JVMs (**[I]**: the
plan did not find one, since a suite runs in one JVM of one job). `VarkaTestUse`'s writer and
`dev/varka_matrix_bins.py`'s reading of its `U` lines get the same header and the same scoping
(question 2).

### 2.4 The five suites

Once 2.1 is on the PR Build, the PR's own Build shows which suites emit outside a window. Each
suite it names mixes in `VarkaReachWindow`, in the same pull request. If none is named, none is
changed, and this plan's outcome says so (question 3).

## 3. Tests and proofs

1. `VarkaTestReachSuite`, the existing JUnit suite of the record (the recorder made
   package-visible for it): a note with no window
   open writes one `#<TAB>stray` line and one stderr block for two notes from one site, and two
   of each for two sites. A note inside a test's or a suite's window writes none.
2. The same suite: `read` over a two-segment record. A JVM-wide line in segment A joins A's tests
   only, and a record with no header joins every test, as today.
3. `dev/varka_matrix_bins.py --selftest`: the same two cases for `read_reach`, and for `select`'s
   joins of the reach and of `VarkaTestUse`'s `U` lines.
4. A planted stray, end to end, in a new `VarkaReachStraySuite`: a forked JVM
   (`@VarkaSuite(ownJvm = true)`, with the record and the JVM id set) whose test compiles with
   the window closed. The record holds the stray line,
   and stderr holds the stack with the planting test's frame.
5. The PR's own Build: its sql reach files have one `#<TAB>jvm` header each, their `*<TAB>*`
   lines are `-`, and the jobs print no stray annotation, unless 2.4 finds a suite, which the
   pull request then fixes.

## 4. Predictions and costs, registered before the build

- P1: the PR Build prints no stray from four of the five suites. `VarkaEvaluatorComponentsSuite`
  is the one the plan cannot call (item 4, **[I]**).
- P2: the recording's wall is unchanged within the laptop's run-to-run spread. The walk runs once
  per site, and `VARKA-536` measured 281 s before its fix and 286 s after, four JVMs
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
| `.../varka/VarkaTestReach.java` | the stray warning and line, the JVM file, `read`'s segments |
| `.../varka/VarkaTestUse.java` | the same header and file |
| `.../varka/VarkaTestReachSuite.java` | tests 1 and 2, beside its existing tests |
| `.../varka/VarkaReachStraySuite.java` | test 4, new, `ownJvm = true` with the record set |
| `dev/varka_matrix.sh` | `-Dvarka.matrix.jvm=<leaf>` |
| `dev/varka_matrix_bins.py` | segmented `read_reach` and `select`, selftests |
| `dev/varka_scoped_suites.sh` | the stray annotations |
| `build_and_test.yml`, `varka-option-matrix.yml` | upload glob, `VARKA_REACH_JOB`, annotations |
| `sql/varka/skills/testing-and-debugging.md` | reading a stray line |
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
   file a JVM with a header line (2.2). It keeps every line's format, an old record reads as one
   segment, and two JVMs stop interleaving in one file (item 5). An id column would change every
   line, and three readers would have to parse it.
2. **`VarkaTestUse`'s line too.** Recommended: yes, in this row. It is the same flaw in the
   record row 508's selective matrix reads (item 6), and one format for both records is simpler
   than two.
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
2. The option matrix's job-level concatenation (`varka-option-matrix.yml:182`) stays a `cat`. If
   row 508's selective matrix starts merging records by key rather than whole, it has to keep
   the `#<TAB>jvm` segments.

## 10. Outcome
