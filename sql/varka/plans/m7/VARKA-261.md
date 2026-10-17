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
runs on the machine tonight, so every step that needs a JVM is the morning's and is marked
`[MORNING: ...]` below.

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
6. **The oracles and the surfaces** `[MORNING]`. `sql/varka/emitted_bytes.json`,
   `sql/varka/emit_cost_audit.json` and `sql/varka/coverage.json` regenerated on the closing commit
   and expected unchanged; a difference is a finding and a row before the tag. The README's
   surfaces (the date and TIME tables, lines 118, 131, 138 and 203 say "milestone 6 build") and
   the cold-start headline (README line 413, `docs/sql-varka.md` line 1463: "1.8x, 17 ms against
   31 ms") regenerated on the milestone 7 build and requoted, the headline stating the spread
   between JVMs a 4-core runner shows (`m9/SCOPE.md` item 103).
7. **The debt sweep.** Five items fixed on branch `varka-261-debt` (section 5.3), merged as a
   pull request of their own before the tag; the register's other entries rewritten in the past
   tense with what the sweep found, or handed to `m9/SCOPE.md` with the reason.
8. **The tag and the cleanup** `[MORNING]`. An annotated tag `varka-m7` on the merge commit of
   the close, its message the changelog entry's headline and the posts' addresses; then
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
  the change `[MORNING: the dry run's count of open m7 rows, expected 261 alone]`.
* **Step 2, the record.** Section 5 below.
* **Step 3, the documents.** The audit's grep for `milestone in flight`, `m7/PLAN.md` and
  `in flight` over the README, `CONTRIBUTING.md`, `VISION.md`, the plans and the skills files;
  each place in the audit's part 3 table read in the file and corrected in this branch.
* **Step 4, the README's account.** Written from `m7/POST_CORRECTNESS.md` and the rows it cites;
  `dev/varka_quote_check.py` run after it, since every number with decimals in the README must
  trace to a committed results file.
* **Step 5, the changelog and the lessons.** `dev/varka_toc.py SKILLS.md --from
  sql/varka/skills` after the sections were added; the pre-commit hook checks it.
* **Step 6, the oracles and the surfaces.** `[MORNING: the three oracles' regeneration on the
  closing commit and the diff, expected empty; the surface runs and the requote, with
  dev/varka_bench_diff.py --requote empty or every line left saying why.]`
* **Step 7, the debt sweep.** `[MORNING: the matrix configurations the option-matrix test
  names, the bisection test run twice in one JVM, and the first CI runs of the three workflow
  changes - a scoped push whose admit and Precompile Spark skip, a rerun whose check is patched
  once, a container job whose offline install downloads nothing.]`
* **Step 8, the tag and the cleanup.** `[MORNING: the tag's commit; the counts of worktrees,
  local branches and fork branches removed, against prediction 5.]`

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
3. Row 274b left predictions 1, 6 and 9 to the first nightly `compiled` step.
   `[MORNING: whether the nightly of 16 to 17 October ran that step and what it scored.]`
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

`[MORNING: the sweep's verification - the matrix configurations, the bisection test twice in one
JVM, the first CI runs of the three workflow changes - and the pull request's number.]`

### 5.4 The register after the sweep

Of section 10's thirteen entries, four were already swept by their owners (the unexplained
mutant by 265, the calibration table by 384, the engine install by 385 for the host runners, the
worktrees by this close's step 8) and two more by 5.3 (the bisection test; 385's container jobs).
The rest stay open with a disposition each, to be written into section 10 in the past tense with
the tag: `VarkaTierWitnessSuite` under the sanitizer, the hand-ported conjunct orders, the public
compiler entry points, the dead runs in the admission queue, "Re-run failed jobs" skipping the
queue and a port's pair near the job's cap are work with no row, and section 8 proposes the first
two; `catalyst/Test/checkstyle` on master waits for upstream's fix by a sync.
`[MORNING: section 10 rewritten accordingly, and the plan's section 11, "The close", written in
m6's shape with the date, the tag, the posts and the rows that moved.]`

### 5.5 Predictions scored

1. `[MORNING: prediction 1, the document count, once the morning's grep over the regenerated
   files is done; this evening's count is the audit's fourteen places plus the README's milestone
   3 bullet, which said "in progress" since September.]`
2. `[MORNING: prediction 2, the oracles.]`
3. `[MORNING: prediction 3, the issue mirror, scored on the merge.]`
4. `[MORNING: prediction 4, the cold-start headline and the runner pair's verdicts.]`
5. `[MORNING: prediction 5, the cleanup counts.]`
6. `[MORNING: prediction 6, the sweep's checks.]`

### 5.6 What the morning fills

Collected from the placeholders above, so nothing is missed:

* The README's surfaces regenerated on the milestone 7 build and relabelled: lines 118, 131, 138
  and 203 ("milestone 6 build", "`varka-m6`"), the date surface's "45 projection rows span 12.3x
  to 37.4x with a median of 21.2x; 18 filter rows span 0.46x to 14.7x" and the TIME surface's "15
  projection rows span 13.9x to 37.5x with a median of 16.2x; 28 filter rows span 0.34x to 9.9x",
  and the `WHERE d < d2` rows quoted at 7.7x and 0.70x.
* The cold-start headline: README line 413 ("1.8x - a fresh shape misses the class cache by
  design") and `docs/sql-varka.md` line 1463 ("1.8x - 17 ms vs 31 ms best, 22 ms vs 37 ms
  average"), regenerated and stating the spread between JVMs a 4-core runner shows.
* The three oracles' regeneration and diff.
* The debt sweep's verification and its pull request.
* The nightly `compiled` step's score for row 274b's predictions 1, 6 and 9.
* Section 10 rewritten and section 11 written in `m7/PLAN.md`; row 261 marked **Done** with the
  date, the tag and the pointer to section 11.
* The cleanup's counts and the tag `varka-m7`.
* `CHANGELOG.md`'s date, left as `[MORNING: tag date]`.

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
