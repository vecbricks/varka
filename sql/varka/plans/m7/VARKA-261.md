# VARKA-261: Close milestone 7

*Scoped 2 October 2026 (milestone 7 section 2.6, row 261); opened 17 October 2026, the day the
milestone's last code row closed.*

## 1. The question

Milestone 7 set out five points in `m7/PLAN.md` 1.1 - proofs of every bounded lowering, tests
that reach what they missed, a Java compiler checked by Error Prone and NullAway, CI with no
laptop in the loop, and the posts - and by 17 October 2026 every row of its table but this one
read **Done**, **Moved** or a marker that meant done. What nothing had done yet is what the rule
in `CONTRIBUTING.md` ("Finding work") asks of a milestone's last row, in the shape milestone 6's
close gave it (`m6/VARKA-282.md` 2): read 1.1 against the record point by point and score the
plan's own predictions; correct every document that names the milestone in flight; put the
lessons of the last weeks into the skills files; regenerate the oracles on the closing commit;
write the `CHANGELOG.md` entry; tag the commit; and remove the worktrees and branches the work
left. Section 2.6 adds one thing of this milestone's own: the README's account of how Varka is
checked, written from what the proofs and tests now hold and quoting nothing that does not trace
to a committed file. The README had no such account: a search of it for the proofs, the solvers,
the mutation run, the sanitizer or the fuzzers found nothing but the milestone 7 bullet.

Row 261's text also makes the close the debt sweep of section 10 and of the four items the last
rows left on 17 October: an option-matrix test with no `skips.tsv` line, the noisy plan-size
bisection test, two defects of the CI admission and check sync (row 255), and the container jobs'
engine cache restored where Maven does not read it (row 385).

The input to the scoring is a read-only audit of the repository at `9435783beb2`, 17 October
2026 (`close-audit.md` in the session's scratch area; its tables are reproduced below where they
carry the score). Nothing was built or run for this record beyond shell reads, `git`, the GitHub
API read for one step's time and `dev/varka_issues.py`; a benchmark that must not be disturbed
runs on the machine tonight, so every step that needs a JVM ran the morning of 18 October under
the heavy lock, after the night's arms; section 5 holds what each found.

## 2. The change

Nothing in the engine moves. The close has eight steps, in this order, because the tag must sit
on the commit where every row is closed and the changelog entry quotes the tag.

1. **The rows.** Row 222 reads **Closed on the measurement**, which `dev/varka_issues.py` reads
   as open (any marker not starting with Done, Withdrawn or Moved is in progress); it is re-marked
   **Done, closed on the measurement** with its sentence kept. Row 261 is marked **In progress**
   while the close runs and **Done** by the pull request that merges it.
2. **The record.** 1.1 scored point by point in section 5 below, with each caveat and its
   evidence; the predictions registered in the plan's own text scored; the practice 9.5 set
   checked.
3. **The documents.** Every sentence that names milestone 7 as in flight or carries an older
   truth: the README's status list and Contributing section, `CONTRIBUTING.md`'s "as this is
   written" pointer, `VISION.md`'s header and its section 12 note, `m8/PLAN.md`'s header and its
   "no milestone 7 row moves" decision, and three sentences in the skills files that the
   milestone's rows overtook (the date column's assumed range, what sbt does after the watchdog
   halts a fork, where Error Prone runs). The README's milestone 6 numbers and the cold-start
   headline are not edited by hand: the morning's regeneration replaces them (step 6).
4. **The README's account of how Varka is checked.** A section before "Status and roadmap":
   what "right" means, the ten methods in one line each with what each found, and links to the
   post (`m7/POST_CORRECTNESS.md`, published as
   https://vecbricks.github.io/where-the-checking-stops/) and to the files.
5. **The changelog and the lessons.** The milestone 7 entry at the top of `CHANGELOG.md`, in
   the file's style, with the six posts' addresses, the wrong answers fixed and what moved to
   milestone 8. Into the skills files: the lesson the brief named (a warm-up's `RELEASED` verdict
   makes a benchmark's "once compiled" rows measure the row engine) and the largest gaps the audit
   found (VARKA-295's mechanism, VARKA-271's golden-file facts, VARKA-255's GitHub Actions facts,
   VARKA-258 and 259's codegen-cache facts); the other candidates, about 140, are listed in
   section 7 as owed to a later sweep, by file and line.
6. **The oracles and the surfaces.** `sql/varka/emitted_bytes.json`,
   `sql/varka/emit_cost_audit.json` and `sql/varka/coverage.json` regenerated on the closing commit
   and expected unchanged; a difference is a finding and a row before the tag. The README's
   surfaces (the date and TIME tables, lines 118, 131, 138 and 203 say "milestone 6 build") and
   the cold-start headline (README line 413, `docs/sql-varka.md` line 1463: "1.8x, 17 ms against
   31 ms") regenerated on the milestone 7 build and requoted, the headline stating the spread
   between JVMs a 4-core runner shows (`m9/SCOPE.md` item 103).
7. **The debt sweep.** Five items fixed on branch `varka-261-debt` (section 5.3), merged as a
   pull request of their own before the tag; the register's other entries rewritten in the past
   tense with what the sweep found, or handed to `m9/SCOPE.md` with the reason.
8. **The tag and the cleanup.** An annotated tag `varka-m7`, its message the changelog entry's
   headline and the posts' addresses, planned for the merge commit of the close and placed, on
   the owner's word, on master with the debt sweep merged (section 4, step 8); then
   `dev/varka_worktree.sh gc` for the worktrees of merged pull requests, the local branches of
   merged pull requests, and on the fork only the branches of this repository's merged or closed
   pull requests, as `VARKA-282.md` 5 learned.

## 3. Predictions, registered before the run

1. The document check finds the fourteen stale places the audit lists and fewer than twenty in
   all; `docs/sql-varka.md` has none but the cold-start headline.
2. The three oracles regenerate byte-identical to master's on the closing commit.
3. `dev/varka_issues.py` closes exactly two issues, 222's and this row's, and changes no other.
4. The regenerated cold-start headline on the laptop stays within 20 per cent of 1.8x by best
   times, and the runner pair of `VarkaColdStartBenchmark` reads `RELEASED` on at least one shape
   in at least one of four JVMs, as #900's pairs did.
5. The cleanup removes 87 worktrees, about 113 local branches and about 248 fork branches, the
   audit's counts of 17 October; the open work's branches (`varka-260-capacity`, `varka-383`,
   `varka-388`, `varka-326`) and the owner's own stay.
6. The debt sweep's five fixes pass: the option-matrix test under `methodByteBudget=0` and
   `shareChronoPrefix=false`, the bisection test twice in one JVM, and the three workflow changes
   on their first CI runs; none adds a second to any step.

## 4. Verification

Run on 17 October 2026, from the audit and the reads of this evening:

* **Step 1, the rows.** `python3 dev/varka_issues.py` before the change listed two open
  milestone 7 rows, 222 ("Closed on the measurement", read as open) and 261 (not started); after
  the change (18 October, on this branch) the dry run lists 261 alone as open, to update, and
  would close three issues: 222's and the two moved rows' (180 and 386), which it reads as
  withdrawn.
* **Step 2, the record.** Section 5 below.
* **Step 3, the documents.** The audit's grep for `milestone in flight`, `m7/PLAN.md` and
  `in flight` over the README, `CONTRIBUTING.md`, `VISION.md`, the plans and the skills files;
  each place in the audit's part 3 table read in the file and corrected in this branch.
* **Step 4, the README's account.** Written from `m7/POST_CORRECTNESS.md` and the rows it cites;
  `dev/varka_quote_check.py` run after it, since every number with decimals in the README must
  trace to a committed results file.
* **Step 5, the changelog and the lessons.** `dev/varka_toc.py SKILLS.md --from
  sql/varka/skills` after the sections were added; the pre-commit hook checks it.
* **Step 6, the oracles and the surfaces.** The three oracles regenerated on the close branch
  at `e5b8cb0085b` the morning of 18 October, with the three regeneration switches; `git status`
  on the three files is empty, so the closing build emits the oracles the rows left. The
  surfaces: the six files the README quotes, taken the night of 17 to 18 October (section 11 of
  the plan has the arms and their times), every ratio recomputed from their wall-time rows by
  one script over the six files and requoted at the places prediction 1 lists;
  `dev/varka_quote_check.py README.md` finds 0 orphans. The stock arm's two slow interval rows,
  `-ymm` and `abs(ymm)` at 33 M rows/s against 76 on 5 October, were rerun alone in the morning
  with the five other interval entries (`DateSurface-spark-4.2.0-jdk25-ymm-recheck-results.txt`,
  canary ok): the two came back at 82 and 83 M rows/s and the five agree with the night's file
  within 4%, so the night's file stands as taken and the README states the rerun beside the
  span. Against 5 October the stock JDK 25 arm was otherwise 4 to 13% faster on its light rows
  this night, so no ratio in the README rests on a slow stock night. The stock JDK 17 date arm
  ran as well (its TIME arm was cut, since the queue's load gate cost the night two hours) and its
  file is committed next to the others; nothing in the README quotes that arm, and its lightest
  rows read 10 to 24% below 5 October, which with the JDK 25 arm's move the other way is the
  machine's spread between runs of stock Spark, not a change in either. The cold-start
  regeneration ran wide width only, gate ok; what it found is prediction 4 in 5.5, resolved by
  rows 389 and 391 before the tag moved, and the requote is from the regenerated file.
* **Step 7, the debt sweep.** Run the morning of 18 October under the lock. `VarkaEmitterChronoSuite`
  under `dev/varka_matrix.sh` in the defaults, `shareChronoPrefix=false` and `methodByteBudget=0`:
  65, 47 and 44 tests pass with no failure, the rest cancelling themselves where a configuration
  removes their premise, where the two configurations failed the option-matrix test before.
  `VarkaShapeCacheSuite` and `VarkaExpressionCompilerSuite`: 164 tests pass. `dev/varka_pit.sh
  --classes Analysis --methods pureOf`, the sample VARKA-265 started from: 86 mutants, 85 killed
  by a behavioural test, 0 by the bytes oracle alone, 1 survived and already answered in
  `notes.tsv`; PIT runs the suite once per mutant in one JVM, so that is the bisection test 86
  times in one JVM without a failure of its own, where the milestone's full run counted 87
  bytes-only kills from it. The pull request is vecbricks/varka#918. Its CI: the branch edits
  workflow files, so its own run (39103095958) is the full matrix and shows the container jobs'
  offline install. A throwaway branch with one Varka-scoped change on top of it
  (`varka-261-debt-scoped`, run 39103099821) was meant to show the scoped path, admit and
  Precompile Spark skipping, but a push's scope is its diff against the merge base with master,
  so every branch carrying the workflow change reads as `spark` until the change is on master;
  the run was cancelled and the branch left on the fork. The scoped path is first exercised by
  the first Varka-scoped push after the merge, and the check sync's change by the first rerun
  after it, since that workflow runs from the default branch on a schedule. The full run is green
  (37 jobs passed, the usual 4 skipped): the install step resolved the repository to
  `/root/.m2/repository` in the container jobs (`user.home=/root` against `HOME=/github/home`)
  and to `~/.m2/repository` on the host runners; the first job of each kind missed the cache,
  the container path being a new cache version, installed online and saved it, and every later
  job of its kind restored it and installed offline, the container jobs from the lint job's save.
* **Step 8, the tag and the cleanup.** The counts against prediction 5 are in section 5.5: 87
  worktrees by `gc` and four by hand, 108 local branches, 247 fork branches. The tag: on the
  owner's word of 18 October it went first on `793536853ab`, master with the debt sweep (#918)
  merged, at 10:37, since the close was documents only and that engine was the one every file of
  this record had measured. Prediction 4 then turned out to be a regression of the engine (row
  389), and on the owner's word of the same evening the tag moved to `ab9900cdca7`, the merge of
  the post's sources (#922) behind the fix (#921) and the four-core fix (#924), re-created with
  its message corrected: seven wrong answers where the first said three, the regression and its
  fix named, and the seventh post listed.

## 5. Outcome

### 5.1 Section 1.1, point by point

**Point 1, the proofs: held, with three caveats.** Rows 240 to 245 and 281 are done
(`sql/varka/proofs/`, the `java.smt2` prelude held to the JVM, multiply-high division exact for
every divisor in use, both long-lane forms with their exact dividend bounds, every calendar
division site with its first failing dividend pinned, `proofs/spec/STANDARD.md` with 19 SQL-92
definitions, `DELTAS.md` with eleven named departures and a test each, `refinement.smt2` with 216
checks, and the range analyses proven in four files of 306 checks, one latent unsound `SUB` rule
found and fixed). The caveats:

1. *"Run in CI in under a minute."* `VARKA-241.md` 9.3 scored its own prediction missed: the
   "Varka proofs" step took 66 s on the runner at #699, and rows 242, 245 and 281 added checks
   after it. `VARKA-245.md` restated the rule as the slowest check against the 10 s per-check
   limit, and `sql/varka/ci/step-budget.tsv` has no row for the step. Read from the GitHub API
   this evening, the step took 45 s in run 39040973769 (`varka-387`) and 47 s in run 39039091270
   (`varka-379`), both 17 October, the lint job green in each; a docs-only push skips it (0 s in
   run 39042051494). So the point holds today by the step's own time, and the proofs step should
   get a row in the budget so the next addition is read against one.
2. *"A specification of the SQL standard."* Row 243's admission check limited it to the SQL-92
   text, the one edition that could be read, which fixes 19 of the table's 46 operations; the 27
   others are in `EXTENSIONS.md` as the standard's by reduction, ISO 8601's or Spark's (row 244).
   The current edition's clause per definition is still owed, with no row; section 8 proposes it.
3. Row 245 found three things its record does not fix. The unchecked column contract was
   load-bearing: row 379 (#874) fixed it. "Three guards decline batches Spark does not need
   declined, on rows whose other operand is null" and "`EXTENSIONS.md`'s `datediff` wraps" have
   no owner; both are the shape of `m9/SCOPE.md` item 96 (a check qualified by the side that
   reads it) and section 8 names them.

**Point 2, the tests: held, with five caveats.** Rows 81, 138, 262, 269, 271, 272, 274 to 280,
284, 285, 263, 265, 246, 247, 248, 295 and 370 are done, with the findings rows (289, 293,
296 to 299, 301, 302, 304, 306 to 309, 323, 324, 326, 327, 372, 378, 379, 381, 387) done, 380 done
apart from the nightly switch and 386 moved. The numbers the rows record: 731 golden statements
with no disagreement (81); both validity fills, poisoned null lanes on 184 kernels, the partition
oracle under both optimizer settings and the ANSI form comparing condition, SQLSTATE and
parameters (138); every tree to size 5 at both lanes, 3.76 million (269); Spark's golden files
through an Arrow cache in both arms, 789 passed, 1,584 of 47,323 executions served (271); seven
JVM arms, 3,368 answers, no disagreement (272); eight batch histories with a JFR tier witness
(274); the sanitizer on in every suite, which found 292 (263); a hidden kernel failure failing its
test (275); a verdict per guarded batch (279); the reference evaluator held to Catalyst's `eval`
(276); 2,029 of 2,803 emitted classes with both bodies compared (284, 285); 39 mechanisms with a
reach report each (280); emitter 90.1 per cent and compiler 83.7 per cent branch coverage, PIT
1,760 mutants with every survivor answered (265); the flake's cause named and removed at the
preferred species, 0 releases in 2,000 against 442 (295, 370). The caveats:

1. The nightly's `sparkfuzz` step runs with `-Dvarka.sparkfuzz.nested=false` until row 386,
   moved to milestone 8 and waiting on apache/spark#59573, is settled; the nested trees of row 298
   are not drawn nightly. The milestone closes with one Varka/Spark disagreement open, reproducer
   `sql/varka/fuzz/spark/varka-386-20261017-22897.sql`, by the owner's decision that Spark's own
   answer there is under review upstream.
2. The flake's remainder: rows 373 and 374 record what stays at 4 lanes and at a 128-bit
   preferred species, with no operator setting that helps. "Recorded with what an operator can
   set" is met by `VARKA-295.md` 9.6's two JVM options and the post (371), not by a runbook entry
   in `docs/sql-varka.md`.
3. Row 274b left predictions 1, 6 and 9 to the first nightly `compiled` step. No laptop nightly
   has run since 26 September 2026: that is the only dated directory under
   `target/varka-nightly/` in any worktree on the machine on 18 October, so the step is unscored
   at the close, and the first `dev/varka_nightly.sh` after it scores the three predictions.
4. Row 271 did not build the patch-per-Spark-version form its source (Comet) uses; its admission
   check replaced it with the Arrow-cached arm over Spark's golden files, and Varka serves under
   4 per cent of those executions. The point's "Spark's own SQL suites run with Varka on" is met
   in that form.
5. Two register debts bear on the point: `VarkaTierWitnessSuite` fails under the memory sanitizer
   on master, and the planSize bisection test fills PIT's bytes-only column with noise. The
   sweep fixes the second (5.3); the first is open (5.4).

**Point 3, the code: held, with caveats.** The three family compilers, the node compiler, the
shape cache and the facade are Java (214 to 217, 300, 305: "the facade has no Scala left"); the
benchmark adapter and all 26 benchmarks (224, 322); the evaluators and six components (251, 267)
with per-batch overhead unchanged; Error Prone clean under `-Werror` and NullAway with a pending
list of 37 classes, on every pull request since #875 (266, 382); each refactor with
`emitted_bytes.json` unchanged (248, 249, 250, 290); about 290 comments in 108 files rewritten to
describe the code as it is (252); the refusals and the operand admission each stated once (83,
86); milestone 4's twenty debts owned (264); the test helpers, fuzz infrastructure and censuses
ported (268, 310, 311, 312, 320, 321). Scala stays, by recorded decision, in the exec nodes and
the columnar rule (plan 2.3, section 8), `VarkaArrowSessions` (322), the composition shrinker
(320) and most test suites, which a port touches only when a task does. The compiler entry points
without the family switches stay public (row 318's debt, 5.4).

**Point 4, the infrastructure: held, with the caveats the sweep takes.** CI reaches a verdict
with no process on a laptop and master's head runs after each merge (255: the admission job
inside the Build, pairs and results files on GitHub-hosted runners, the step budget, the new-test
rerun and the `fix` label; `dev/varka_ci_queue.sh` deleted); a benchmark run regenerates only its
own sections (256); the slow mode a kernel meets after other kernels is a number in the nightly
(257: 0 slow kernels under test in 52 forks). Row 255's two defects and row 385's container jobs
are the sweep's (5.3). The register's "the admission queue keeps dead runs" and "Re-run failed
jobs skips the queue" are open (5.4). `VarkaWideKernelBenchmark`'s pair stays on the laptop
(255a).

**Point 5, the posts: held, with one caveat.** Six posts published: "How Spark compiles a
query" (258, 17 October, https://vecbricks.github.io/how-spark-compiles-a-query/), "The class
got cheaper" (259, 16 October, https://vecbricks.github.io/the-class-got-cheaper/), "Spark's code
generator by the numbers" (260, 16 October, https://vecbricks.github.io/nine-numbers/), "Why a
warmed kernel can still allocate" (371, 16 October,
https://vecbricks.github.io/why-a-warmed-kernel-can-still-allocate/), "A large CASE WHEN, split
only where it has to be" (288, 12 October,
https://vecbricks.github.io/split-only-where-it-has-to-be/) and the milestone's own, "Where the
checking stops" (270, 17 October, recast for testers the same day in #916,
https://vecbricks.github.io/where-the-checking-stops/). Row 180, promotion, moved to milestone 8
as the cadence it is; row 260 owes the fast hash map's capacity measurement, on branch
`varka-260-capacity`, three commits ahead and pushed to the fork.

### 5.2 The plan's own predictions, scored

| Where | Prediction | Score |
| :-- | :-- | :-- |
| 2.3 | NullAway: "about 125 to 145 annotations, at the paper's rate" | **Missed, and never scored by its row.** `VARKA-266.md` 6.1 registered three other predictions (green under `-Werror`: held; a pending list of at most 25 classes: wrong, 37 today; lint under 30 s more: held, 25 s) and counted no annotations. Counted this evening on the closing branch: 102 `@Nullable` annotations in 19 of Varka's Java main source files, none in its tests. That is every annotation present, not only the ones row 266 added, so the count added is at most 102, under the 125 predicted |
| 2.1 | Row 241's proofs against the one-minute budget | **Missed on the runner** at #699 (66 s, `VARKA-241.md` 9.3); 45 to 47 s on 17 October's runs (5.1, point 1) |
| 2.2 | Row 269: "about 1,100, 27,000 and 700,000 int-lane trees" at sizes 3, 4 and 5 | **Low by three to five times**: 3,203, 101,422 and 3.16 million (row 269) |
| 2.2 | Row 274: "about eight batch histories per kernel" | **Held**: eight, as built |
| 1.1 point 1 | The proofs "run in CI in under a minute" | **Held today** by the step's time (45 and 47 s), after being missed at #699 |

**The practice 9.5 set and did not keep.** "The state against 1.1 is written as a two-line note
under this section after every five merged rows." No such note exists; about forty rows merged
after 13 October and section 9 ends at 9.5. This record is the first state written since the
halfway review, and the practice is dropped rather than back-filled: a note written at the close
about the state on 14 October would be a reconstruction, not a record.

### 5.3 The debt sweep

Five items, all fixed on branch `varka-261-debt` (five commits on `9435783beb2`), to merge as
their own pull request before the tag:

* **The option-matrix test pinned the two options it depended on.** "Inside a Spark task, the
  forms without a scratch address refuse a kernel with scratch" assumed `materializeChronoPrefix`
  alone gives a kernel scratch; a prefix is materialized only under the fragment sharing and the
  byte budget too, so under `shareChronoPrefix=false` and `methodByteBudget=0` the premise failed.
  The test's subject is the run forms, so it pins all three options.
* **The shape cache's `invalidateAll` did not clear its declined memo**, so the planSize
  bisection test, run a second time in one JVM as PIT's minions run it per mutant, found its
  probes rethrown unbuilt and failed regardless of the mutant (87 of the full run's 117 bytes-only
  kills). The hook forgets declines too, a cache suite test pins it, and the PIT adapter prints a
  failing test's message into the run's log.
* **The scope override now resets `pyspark_periodic`**, which depends on `sql` and so was set by
  a change under `sql/catalyst`; the admit job's condition read it, so a scoped push waited 67
  minutes for a full-matrix slot and precompiled Spark for a PySpark job that never ran (run
  39008460283).
* **The check sync remembers the last status it sent per check.** GitHub keeps a completed check
  completed when it is patched to `in_progress`, so while a fork run was rerun the loop sent the
  same patch every two minutes (51 and 43 times in job 117303119123); it patches now only when
  the wanted state differs from the last sent.
* **The engine-cache step restores to the JVM's `user.home`**, so the container jobs hit the
  cache: `actions/cache` expands `~` with `HOME`, `/github/home` in a container, while Maven reads
  under Java's `user.home`, `/root` there; the 12 container jobs restored a cache Maven never read
  and went online (run 39017334406). On a host runner the two agree, so the host jobs' cache
  version is unchanged.

Verified the morning of 18 October; section 4, step 7 has the runs and their counts. The pull
request is vecbricks/varka#918, opened once the local checks had passed and merged before the
close's own.

### 5.4 The register after the sweep

Of section 10's thirteen entries, four were already swept by their owners (the unexplained
mutant by 265, the calibration table by 384, the engine install by 385 for the host runners, the
worktrees by this close's step 8) and two more by 5.3 (the bisection test; 385's container jobs).
The rest stay open with a disposition each, to be written into section 10 in the past tense with
the tag: `VarkaTierWitnessSuite` under the sanitizer, the hand-ported conjunct orders, the public
compiler entry points, the dead runs in the admission queue, "Re-run failed jobs" skipping the
queue and a port's pair near the job's cap are work with no row, and section 8 proposes the first
two; `catalyst/Test/checkstyle` on master waits for upstream's fix by a sync.
Done on 18 October: each of section 10's entries ends with what the sweep found and where the
debt went, the four with no row (the public compiler entry points, the dead admission runs,
"Re-run failed jobs", a port's pair near the cap) are items 106 to 109 of `m9/SCOPE.md`, and the
plan's section 11, "The close", is written in m6's shape with the date, the tag, the posts and
the rows that moved.

### 5.5 Predictions scored

1. **Missed on the count, held on `docs/sql-varka.md`.** The audit's fourteen places and the
   README's milestone 3 bullet were found and corrected, but the regeneration touched the README
   in a dozen more: the four "milestone 6 build" lines, the headline and its section (37x to 39x,
   four places), `date_add(d, 3)`'s rate and the per-job constant (two), and the cold-start row;
   with the docs' cold-start line that is about 27 places, not under twenty. The audit counted
   the README's benchmark prose as one regeneration rather than place by place.
   `docs/sql-varka.md` had none but the cold-start headline.
2. **Held.** The three oracles regenerated on `e5b8cb0085b`, the close branch with the surfaces
   on it, are byte-identical to the committed files: the closing build emits what the rows left.
3. **Pending the merge, and already missed by one.** The dry run on this branch closes three
   issues, 222's and the two moved rows' (180 and 386), which were open and which "changes no
   other" overlooked; 261's closes with the merge, four in all against the two predicted.
4. **Missed on the laptop, held on the pair, and the pair sharpens the finding.** Two "Run
   benchmarks" dispatches of the class on GitHub-hosted runners (39107597853 on an AMD EPYC
   7763, 39107602708 on a Xeon 8370C, from the debt branch, whose engine is master's; files
   `VarkaColdStartBenchmark-jdk25-runner-7763-m7-*` and `-runner-xeon8370c-m7-*`) read
   RELEASED on 51 of 71 verdicts each, the laptop's count exactly, and the split is by shape on
   all three machines: every projection shape (`greatest(add_months(d, k), date_add(d, k),
   last_day(d))` at all nine rungs, the back-to-back and the steady-state tables) releases at
   the 60-second deadline, and only the two range-filter tables compile, 10 of 10 each. On the
   Xeon the once-compiled row of the 16-entry shape reads 64 ms against 37 ms in the file of
   28 September on the same CPU class. So this is not a machine's JIT luck: something in the
   engine or the warm-up since 28 September keeps these projection kernels from a verdict on
   every machine, and it is a row's work, not an item's. The regenerated laptop file (wide
   width only, on the owner's word; the 128-bit companion stays from 28 September and the
   provenance says so) has no 1.8x to be within 20% of: the warm-up's verdict was RELEASED at its
   60-second deadline on 51 of 71 iterations, where the committed file of 28 September, same
   machine, same eight-core pin, same JDK, had 71 COMPILED; the once-compiled rows read 22 to 50%
   below that file, and the first run of a 16-entry shape reads 136 ms with the warm-up and 241 ms
   without against stock's 113 ms. The signature, about 56,000 calls in the 60 seconds and no
   compile, is VARKA-295's; between the two files the warm-up changed in #679, #807 and #827 and
   the laptop's kernel from 7.0.0-31 to 7.0.0-38, so the cause is open and the core count is not
   the whole of it. It was a finding before the tag, and the owner held the close for it: row 389
   (`VARKA-389.md`) found the cause the same day, VARKA-295's template warm-up (#827) running after
   other vector code had taken the shared templates to C2 and deoptimising them, one decompile
   each that a wide kernel inlines six hundred times past C2's cutoff, and #921 moved the warm-up
   before any vector code; the regenerated file reads 71 of 71 COMPILED at both widths and a
   runner pair the same. The four-core coin behind it, a one-line wrapper's first compile landing
   at tier 2, is row 391 (#924), with CI's retry of listed flaky suites as row 390 (#923); item 103
   stays resolved in part until #924 has run on the pool's 4-core AVX2 runners for a while. The
   README and the docs quote the cold-start row from the regenerated file: 1.6x once the
   warm-up's kernel is in, the first run itself on the row path.
5. **Held on the fork and near on the rest.** `dev/varka_worktree.sh gc --yes` removed 87 clean
   worktrees of merged pull requests with their branches, exactly the audit's count, and four
   detached clean ones (`varka-270-qa`, `varka-fuzz-1006`, `varka-night-1012`,
   `varka-base-267c`) went by hand, since `gc` reads a branch; 21 more local branches of merged
   pull requests had no worktree, so 108 local branches went against about 113 predicted; 247
   fork branches of this repository's merged or closed pull requests were deleted against about
   248: the 248th is `varka-262`, kept with its worktree, which is three commits ahead of its
   merged pull request. The open work's branches stay (`varka-260-capacity`, `varka-383`,
   `varka-388`, `varka-326`, `varka-322c`, `varka-default-switcher`, `varka-fuzz-0930`,
   `varka-jegg-0-1-0`, `varka-task-200-plan`, and the close's two). 26 worktrees remain; 13 are
   dirty and were left for the owner, each holding one or two files: a probe suite never
   committed (`varka-278`, `varka-279`, `varka-repro-farday`), a benchmark source or results
   file (`varka-216`, `varka-251-master`, `varka-263`, `varka-263-on`, `varka-pre214`,
   `varka-night`, `varka-surfaces-m6`), a fuzz-suite edit with five untracked files
   (`varka-320-base`), a JVM replay log (`varka-291-e`), and the close's own surfaces worktree.
   The fork's other 695 branches are the owner's Apache Spark branches, untouched as
   `VARKA-282.md` 5 learned.
6. **Held on the laptop and on the one CI check that could run before the merge.** The
   option-matrix test passes
   under both configurations and the defaults; the bisection test ran 86 times in one JVM under
   PIT without a failure of its own; the matrix's three configurations took 15, 14 and 15 seconds
   for the suite, so the pinned options added nothing. Of the three workflow changes only the
   container jobs' install can run before the merge (step 7): a push's scope is its diff against
   master, so no branch carrying the change takes the scoped path, and the check sync runs from
   the default branch; "their first CI runs" was written without allowing for either.

### 5.6 What the morning filled

Collected from the placeholders the evening left, so nothing is missed:

* The README's surfaces regenerated on the milestone 7 build and relabelled: the four "milestone
  6 build" lines, the date surface's span sentence (12.3x to 37.4x, median 21.2x; filters 0.46x
  to 14.7x) now 11.4x to 53.8x with a median of 20.2x and filters 0.43x to 13.7x, with the two
  rows above 40x explained beside it; the TIME surface's (13.9x to 37.5x, median 16.2x; filters
  0.34x to 9.9x) now 13.2x to 39.2x with a median of 14.8x and filters 0.35x to 10.5x; the `WHERE
  d < d2` rows 7.7x and 0.70x now 7.1x and 0.69x; the headline 37x now 39x. Done, step 6.
* The cold-start headline: README's cold-start row and `docs/sql-varka.md`'s line. Done, after
  the finding of prediction 4 became row 389 and was fixed: the row is requoted from the
  regenerated file, 1.6x once the warm-up's kernel is in.
* The three oracles' regeneration and diff: unchanged, prediction 2.
* The debt sweep's verification and its pull request: step 7, vecbricks/varka#918.
* The nightly `compiled` step's score for row 274b's predictions 1, 6 and 9: not scored, since
  the laptop nightly has not run since 26 September (section 1.2, caveat 3).
* Section 10 rewritten and section 11 written in `m7/PLAN.md`; row 261 marked **Done** with the
  date, the tag and the pointer to section 11. Done the evening before.
* The cleanup's counts (prediction 5) and the tag `varka-m7` (step 8, after the merge).
* `CHANGELOG.md`'s date: 2 to 18 October 2026.

## 6. Explicitly out of this task

* Row 386 and the nightly's nested-tree switch, which wait on apache/spark#59573 in
  `m8/PLAN.md`.
* Row 260's hash-map capacity measurement (`varka-260-capacity`), which the owner opens or
  drops.
* The current SQL edition's clause per `STANDARD.md` definition (row 243's owed work), the three
  needless null-operand guards and `EXTENSIONS.md`'s `datediff` (row 245's findings): proposed in
  section 8, not done here.
* The register debts 5.4 leaves open, each with its disposition there.
* About 140 lesson candidates the audit found in the task records and the skills files do not
  hold, listed in section 7 for a later sweep; the close writes the five largest.

## 7. Lessons owed to a later sweep

The audit read the 107 task records' Outcome and Verification sections against the skills files
and found, beside the six the close writes (the `RELEASED` verdict, VARKA-295's mechanism,
VARKA-271's golden-file facts, VARKA-255's GitHub Actions facts, VARKA-258 and 259's
codegen-cache facts, and the load tail after a benchmark JVM exits, which the close's own
surface queue met) and the three corrections of step 3, about 140 candidates that no skills
file holds. They are listed here by the file they belong in, each with its source, so a later
sweep re-reads the source before writing it up; the audit's reads are `file:line` evidence, not
verified text. Items the audit found already recorded are left out.

**Corrections or extensions of an existing sentence**

* `VARKA-283.md:21`: ScalaTest's `assert(a === b, clue)` builds the clue on every call; nine
  divisors over 2^32 dividends spent their time there (47 s once split).
* `VARKA-293.md:6`, `VARKA-214.md:224`, `VARKA-240.md:90`: `Math.abs(Long.MIN_VALUE)` is
  negative and `(int) Math.abs(-2147483648L)` is `Integer.MIN_VALUE`; write guards as two-sided
  ranges (extends `testing-and-debugging.md`, the overflow section near line 282).
* `VARKA-216.md:175`, `VARKA-254.md:266`: a `default` arm in a switch over the sealed IR defeats
  exhaustiveness (swallowed a new node in `rearm`; gave `DayOfWeek`/`WeekDay` weight 1)
  (extends `working-in-this-repo.md` near line 100).
* `VARKA-214.md:239`, `VARKA-215.md`: `dev/varka_bench_diff.py` reports a change in rate, so minus
  is slower; two tasks read it backwards (extends `benchmarking.md` near line 204).
* `VARKA-247.md:26, 138`: an option can move nothing at the defaults because other defaults took
  its subject away; give it a `Subject` base and run its matrix arm there (extends
  `emitter-and-ir.md` near line 684).
* `VARKA-309.md:17-37`: a planted-bug test needs a seed of its own (at 11 of 32 nightly seeds
  nothing in 300 compositions reached the bug) (extends `testing-and-debugging.md` near 1131).
* `VARKA-313.md:296-301`: `exactRefusalStatus` measured 2.5 to 3.9 per cent slower in most arms
  and stays off by default (extends `emitter-and-ir.md` near line 569).
* `VARKA-372.md:46, 111`: fixture columns that can raise made a tenth of compositions compare
  error with error (extends `testing-and-debugging.md` near line 750).
* `VARKA-370.md:300-316`: C2 treats any recorded trap at a bci as too many; `PerBytecodeTrapLimit`
  is read only by the runtime (tightens `the-jit.md` near line 976).
* `VARKA-241.md:255-258`: no entry under `skills/calibration/` for its scored prediction 1 (lint
  step under a minute, measured 66 s), which the calibration section asks for.

**the-jit.md**

* `VARKA-295.md:419, 150`: covered by the close's section; the 32x rule's withdrawal is in it.
* `VARKA-326.md:154-188, 228`: every cycling kernel is NULL-mode and traps on its masked side;
  about a quarter of them cycle; the cycle stops at `PerMethodTrapLimit` after a median 6 s; it
  depends on JVM history, so `-Dvarka.fuzz.only` does not replay it.
* `VARKA-370.md:235-275, 382-400`: a JDK 25 JVM can load an agent into itself through the
  `DiagnosticCommand` MBean; redefining the Vector API classes resets their MethodData at the
  price of deoptimizing every kernel; WhiteBox and JVMCI need startup options.
* `VARKA-371.md:149-151, 177-195`: `-XX:+LogCompilation` raised the release rate (log to see
  one, count without the log); within one compile the class-check count only rises, so report
  the largest.
* `VARKA-373.md:172-176`: warming the 128-bit species beside the preferred one made releases
  worse (62 against 14 to 16); a third receiver class makes the site a virtual call.
* `VARKA-378.md:167-177`: machine load slows the trap cycle, so a count of kernels past a cut
  moves with load (8, 8, 2); a ratchet set on a loaded machine must be lowered later.
* `VARKA-274.md:402, 472, 649`: the all-null trap is an `unstable_if` that fires only where C2
  compiled the driver (use the remainder epilogue's trap); a kernel warmed on 32-row slices can
  still trap on its first real batch (2 of 14 a lane); a JVM held to three cores ran 2.6 times
  slower than the whole-machine median.
* `VARKA-263.md:61`, `VARKA-265.md:230`, `VARKA-274.md:633`: the sanitizer's branches change what
  C2 compiles; JIT-measuring suites run `--no-sanitizer`, and under the sanitizer the gate's C2
  code traps inside `VarkaMemorySanitizer.check`, so it is not production's.
* `VARKA-288.md:168, 182`: C2 refuses the whole 6049-byte `CASE WHEN` method as "hot method too
  big"; Spark's `hugeMethodLimit=8000` workaround is within 3 per cent of whole-stage off past
  the line.
* `VARKA-314.md:451-454`: a JFR dump holds every event of every open recording; stream it and
  keep only the classes you enabled.
* `VARKA-248.md:261`: building `DEFAULTS` from a table of about 80 method references cost 17 to
  26 ms at class init against 4 ms for the record; keep production defaults positional and pin
  them to the table in a test.

**benchmarking.md**

* `VARKA-322.md:326-333, 338-341, 368-372, 422-430, 449-457, 753-760, 807-810, 613-622`:
  Spark's `Benchmarks` runner called `main` statically (NPE on a Java benchmark);
  `Boolean.parseBoolean("1")` is false and `SPARK_GENERATE_BENCHMARK_FILES=1` empties the results
  file when `main` opens; a timed lambda that discards its result lets the JIT drop the work;
  `static final int` loop bounds become immediates; at 63 and 64-row chunks the harness
  dominates; `RuntimeConfig.get` returns a default, so restore through `contains`; a context a
  failed benchmark leaves running keeps its master; two loops 10 to 15 per cent slower on the
  laptop were faster on an EPYC runner.
* `VARKA-292.md:87, 90`: a pair whose regeneration refused on load produced six "results" that
  were the committed file copied; a benchmark nobody runs rots (`VarkaTimeBenchmark` had thrown
  for weeks).
* `VARKA-255.md:784, 828, 1041, 1058`: the pair script's exit trap restored files before its
  check passed; `benchmark.yml` matches the fully qualified name; one-round pairs always run the
  base first; different CPU models do not pool.
* `VARKA-251.md:30`: throughput benchmarks at 4096 rows cannot see per-batch overhead;
  `VarkaEvaluatorOverheadBenchmark` (1, 16, 1024 rows) can.
* `VARKA-214.md:245`: `VarkaEmissionBenchmark` times only `VarkaLoopEmitter.emit`; a
  family-compiler change cannot move it, `VarkaCompileBenchmark` is the instrument.
* `VARKA-216.md:170`: regenerate benchmark provenance from the final commit after a history
  rewrite.
* `VARKA-224.md:190`: the bench scripts resolved a bare class name only from a `.scala` file.

**testing-and-debugging.md**

* `VARKA-138.md:226-277, 255, 323, 332, 338, 531-533`: run every kernel check at both validity
  fills (a never-set bit fails only at 0x00, a never-cleared one only at 0xFF); a poisoned lane
  reaches only the masked body; stagger the null pattern per input; a planted fault's check must
  not reuse the predicate that chose the removal; check the bits past the last row; compare root
  cause class and message when an error has no condition.
* `VARKA-281.md:491, 442, 447, 450, 483, 485, 487, 488`: ask whether the obligation's domain is
  the code's (the symmetric-`INT` premise hid an unsound `SUB`); a `sat` neighbour can be the
  hard check; a file costs about twice the sum of its checks alone; state an `ite` witness per
  branch; an equality asserted as a premise assumes half the claim; sweep against Spark's
  definitions, not hand-rolled forms; read a bound from one place; refuse dropped samples.
* `VARKA-245.md:311, 48, 326, 385, 396`: solver time depends on how the claim is written (lemmas
  over declared quotients about 3 times faster than `div`, functions left unknown, searches
  pinned to one input, split by month); calendar rules prove a day-count formula for every year
  without a sweep; read a file's time against unchanged reference files; restate a lowering from
  the emitted form, not the scalar twin; a completeness query is vacuous where the bound is the
  guard's own.
* `VARKA-241.md:248, 258`, `VARKA-245.md:345`: refuse both integer neighbours in a `D2L` check;
  the runner takes about 2.1 times the laptop's solver time and the lint job is decided by the
  slowest check against the 10 s per-check limit.
* `VARKA-242.md:62, 58, 165, 274`: a magic multiply with an unsigned shift is exact until `v*M`
  passes 2^32, not 2^31; a reciprocal's exactness can hold only for the dividend's shape; every
  quoted bound is labelled exact or sufficient with its first failing dividend pinned.
* `VARKA-265.md:395, 409, 488, 319, 321, 328, 526`: shard mutants by position; 3 PIT minions at
  `-Xmx3g` fit a 16 GB runner, timeout factor 4; a symmetric guard cannot kill a one-endpoint
  mutant; a suite whose `afterAll` throws fails with no test event; key notes on PIT's bytecode
  identity; silent-empty pitfalls (missing class, misspelt `--only` identity).
* `VARKA-272.md:429, 83, 423`: `-XX:StressSeed=0` draws a fresh seed per compile; with
  fastdebug's verification flags no warm-up settles in 10 s; stale arm directories were read as
  this run's.
* `VARKA-274.md:674, 678, 630`: inner watchdogs end inside the outer deadline; print totals from
  `finally`; the validity-fill check halves the batch numbers at which OSR fires.
* `VARKA-289.md:15, 22`: the composition fuzzer counted declined batches without comparing them
  and skipped long-lane kernels; assert that every kernel of every lane was compared.
* `VARKA-296.md:261, 234`: forcing every output makes each projection a new shape (2 hours
  against 7 minutes); `forceResidualAt` is a fault injector, not a knob.
* `VARKA-297.md:8-9`: without a pivot 34 per cent of filtered compositions compared empty results.
* `VARKA-298.md:307, 204`, `VARKA-381.md:133, 205-214, 223`: two listed errors in two partitions
  race (whichever task fails first names the condition); rerun over one partition; a bare
  `ArithmeticException` from `MultiplyYMInterval` has no condition; a query can raise over one
  batch layout and not another.
* `VARKA-327.md:249-251` and "The 22 deadline releases": `RELEASED` also means "nothing to wait
  for" or "failed"; the release rate moves with the seed and between runs of the same
  compositions.
* `VARKA-379.md:499-506`: a guard that ignores validity meets the harness's int32-end poison in
  null lanes.
* `VARKA-300.md:173`, `VARKA-301.md:100-106`, `VARKA-308.md:134-145`, `VARKA-311.md:197-199`,
  `VARKA-314.md:363-369, 532-536, 353-356, 508-510, 465-468`, `VARKA-315.md:369-370, 341-343,
  433-437`, `VARKA-316.md:144, 191, 131, 189`, `VARKA-317.md:200-203`, `VARKA-318.md:327-330`,
  `VARKA-319.md:219-221`, `VARKA-323.md:212-230`: a Scala `Seq` is never `==` to a Java `List`;
  at batch sizes 1 or 3 asserted metrics read 0; read session settings once in the constructor;
  `withClue` does not attach to an `IllegalStateException`; Spark's `StringSubstitutor` does not
  expand inside values; an exception factory's frame breaks warn-once logic; read null counts
  from the columns after a failure; `SparkUnsupportedOperationException` takes no cause; a matrix
  cancel's message goes only to `run.log`; a pid does not tell containerised executors apart; a
  failure writer swallows `VirtualMachineError`; a one-row batch has no validity buffer; a
  run-time decline is invisible to `EXPLAIN`; no fault injector changes an answer; `intercept`
  without a type argument infers `Nothing`; a suite timed beside another hides its cold
  `@BeforeAll`; errors keyed by the outermost condition hide a different inner one; a regression
  test first checks its premise still holds.
* `VARKA-216.md:141`, `VARKA-217.md:142`: the coverage scan's Java patterns do not read a class
  written as a qualified name; a new Java compiler file must be added to the scan's list.
* `VARKA-217.md:182`, `VARKA-267.md:207`: a Java static holder whose initialiser throws once fails
  for good with a `LinkageError` (`NonFatal` lets it through); a port that marks itself resolved
  before building leaves a permanent null after an `InterruptedException`.
* `VARKA-253.md:24`: a `Utils.isTesting` guard is blind in exactly the end-to-end suites; use the
  presence of a `TaskContext`.
* `VARKA-250.md:77, 112`, `VARKA-248.md` 9.1: prove a refactor by keeping the old method beside
  the new behind a test switch, comparing bytes on every arm and width, confirming the check
  bites with a plant.
* `VARKA-248.md:267, 292`: a table-driven options test moves each entry off its default and
  checks that only its component moved; an Arrow "memory leaked" error was a failed assertion
  skipping cleanup.
* `VARKA-255.md:1311, 1266`: `rerun` dropped the runner's exit status; a second `-s` for the same
  suite replaces its report.
* `VARKA-266.md:183, 204-213`: never count from truncated output; a missing `origin/master` made
  the base `HEAD` silently; a JDK guard read `java`'s version while the pass runs `javac`.
* `VARKA-263.md:115, 141`: `TPCBase` widens `beforeAll`/`afterAll` to public; prove a flag reached
  the test JVMs by failing on a suite that cancels without it.

**emitter-and-ir.md**

* `VARKA-387.md:56-70`, `VARKA-381.md:265-268`: a whole-batch columnar filter raises a later row's
  error before Spark's row pipeline raises an earlier row's or stops at a `LIMIT`; deliver the
  rows that passed, then raise.
* `VARKA-380.md:223-224, 253-255`: a date-to-timestamp cast can raise (`daysToMicros` past year
  294247); Spark runs a filter as written when the stage exceeds `maxFields`, so ask
  `supportCodegen`.
* `VARKA-245.md:285, 293, 298`: the column contract was unchecked (`year(d)` past
  `NARROW_DECOMPOSE_MAX_DAYS`); three guards read one operand's validity; `datediff` wraps.
* `VARKA-222.md:18-45, 72`: IR `hashCode`/`equals` is 2 to 5 per cent of emission; interning is
  not worth building (`dev/varka_jfr_ir_hash.py` measures it).
* `VARKA-299.md:20`: `RowToColumnConverter` rejects `NullType`; substitute a nullable
  `BooleanType` in the fallback converters.
* `VARKA-290.md:137`: `planned && trace.builds == 1` is true only for the first emission into a
  shared trace; a loop must count its own builds.
* `VARKA-304.md:202-211`: a status constant above 5 becomes `bipush`, one byte longer, so the
  cost tables and `emit_cost_audit.json` move.
* `VARKA-306.md:112, 129-133`: an oracle's failure report must diff every value by path.
* `VARKA-265.md:508`: `IfElse` emits the else arm first while slots are planned then-arm first.
* `VARKA-254.md:296, 275`: a size-loop fallback may only close fewer groups; corpus loop-method
  counts are a cheap bug detector for a grouping change.
* `VARKA-300.md:192`: scalac cannot check a match over a Java sealed interface for
  exhaustiveness.
* `VARKA-214.md:219`: Java `Map.get` returns null where the Scala lookup threw.

**calendar-algorithms.md**

* `VARKA-241.md:84-95, 265`: the conversion form's exact bound is one less than the first
  multiple of |d| above 2^53 because L2D rounds the tie to even.
* `VARKA-244.md:41-47`: Spark's day count wraps past int32 in both modes; interval overflow
  raises `CAST_OVERFLOW` 22003 or a bare `ArithmeticException`, not 22015.

**working-in-this-repo.md**

* `VARKA-252.md:83, 167, 188`: comments describe the code as it is; dropping "used to" is not a
  rewrite; a check that fires on correct text teaches `--no-verify`.
* `VARKA-310.md:151`, `VARKA-320.md`, `VARKA-321.md`: before deleting a port's old form, compare
  old and new byte for byte in the same hour.
* `VARKA-320.md:163, 172-177`, `VARKA-321.md:139-143`, `VARKA-322.md:773, 972, 1018`,
  `VARKA-311.md:185`, `VARKA-300.md:190`, `VARKA-318.md:313-314`: a Scala object cannot share a
  name with a Java class in its package; a Java stream's `flatMap` through `iterator()` is not
  lazy; Scala named arguments to a Java constructor fail in an incremental build; porting slips
  (`getClass.getName.stripSuffix("$")`, catch-all to throwing switch, `String ==`);
  `private[pkg]` members get no static forwarder; `Duration.ZERO` prints `0 days`; `assert`
  vanishes under `-da`; a switch statement without `default` is not exhaustive for definite
  assignment; `Map.copyOf`'s order varies; an HTML comment inside a Markdown table ends it.
* `VARKA-214.md:213` and 215, 216, 224: a Java port runs 1.3 to 1.8 times the Scala's line
  count; all four line-count predictions missed.

**build-and-environment.md**

* `VARKA-267.md:145, 164`: a Scala case class is `Serializable` and a Java record is not (302
  tests failed "Task not serializable"); Scala cannot name arguments to a Java method.
* `VARKA-224.md:195-197`: Java cannot reach a Scala instance method through a static forwarder
  nor default a case class's second parameter list; checkstyle allows one statement per line.
* `VARKA-286.md:132`, `VARKA-287.md:136`: parallel suite lanes are bound by contention (load 25
  on 24 cores); weight lanes by the last run's times; two SQL JVMs on 4 vCPUs were a seventh
  faster, not slower.
* `VARKA-291.md:539`: Arrow 19 needs netty 4.1 ahead of a Spark 4.2 distribution's netty 4.2.
* `VARKA-385.md:197-202`: install offline on an exact cache hit, fall back online only on a
  missing artifact, version the key because a cache entry cannot be overwritten.
* `VARKA-378.md:240-245`, `VARKA-315.md:375`, `VARKA-248.md:322`, `VARKA-249.md:99`,
  `VARKA-252.md:140`, `VARKA-301.md:87`: under `set -e` a subshell exits before writing its exit
  file; `java` inside a `while read` loop eats the loop's stdin; Bash before 4.4 and empty arrays
  under `set -u`; sbt colours its CI output; scalastyle sorts a wrapped import by its package; a
  `{@link}` can be an import's only use; a Scala object's fields initialise in order.

## 8. Proposals for the owner

Candidates for after the close, drawn from the caveats of 5.1 and the newest items of
`m9/SCOPE.md`; none is a row until the owner picks it.

1. **Why the warm-up gives up on small runners** (`m9/SCOPE.md` item 103). The cold-start
   headline now carries a spread between JVMs on a 4-core host; the cause is unread.
2. **A date column's bound per shape** (item 102). Row 379's fix sends years 10000 to 42400 to
   the row path where the kernel computes them exactly; the mechanism already takes any interval.
3. **A check qualified by the side that reads it** (item 96), taking row 245's leftovers with it:
   the three guards that decline on a null operand and `EXTENSIONS.md`'s wrapping `datediff`.
4. **The register's two code debts no row owns**: `VarkaTierWitnessSuite` under the memory
   sanitizer, and a test holding `VarkaConjunctOrder` to Spark's `GeneratePredicateHelper`.
5. **The 2-lane long kernel, mask-free or declined** (item 101). The flake's remainder at a
   128-bit preferred species (row 374) has no fix and no operator setting; this is the fix.
6. **A post on the proofs alone** (item 104), and with it row 243's owed work: the current SQL
   edition's clause beside each SQL-92 definition in `STANDARD.md`.
7. **The NULL-mode masked loop C2 cannot settle** (items 99 and 100): which predicate it hoists
   and loses, and what `-XX:-UseProfiledLoopPredicate` costs the kernels that never cycle.
8. **A milestone close that needs no laptop** (item 110, on the owner's word at this close). This
   close spent a night and a morning of the laptop on the README's numbers; the next quotes the
   latest committed files, runs the surfaces on the runner workflow as a row of their own when
   the engine changed, and takes the cold-start row from the runner pair, spread included. The
   warm-up's laptop release of prediction 4 went to rows 389 and 391 the same day.
