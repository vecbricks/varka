# VARKA-605: `nullif(ntz, ntz2)` settles to C2 code under the memory sanitizer

## 1. Where this came from

Milestone 8's row 605, added 11 November 2026 on the owner's word from the build of #1500 (row
157's warm-up buffer fix, branch `varka-warmup-buffers`, merged the same day). Harness work,
ranked below the type rows. #1500's description records, for `VarkaCoverageCompositionFuzzSuite`
at the nightly's seed 20261111 with `-Dvarka.fuzz.compositions=400`, that the compiled test's
tally was 8 of 8 settled on each lane without the sanitizer, and that with
`-Dvarka.sanitizeMemory=true` "the long lane's `nullif(ntz, ntz2)` stays unsettled in `warm-same`
and `quiet-null` (profile-predicate traps, its masked loop never reaching C2)", on every
sanitized run of that build. The row corrects the last clause by inference: a `profile_predicate`
trap fires from C2 code, so the loop was compiled and thrown away, again and again. The readings
are in that description only, not in a committed file. `VarkaCompiledCheck` counts an unsettled
case and still compares its batches, so nothing fails; for this kernel the sanitized compiled
test compares a lower tier than a plain run serves.

## 2. The admission check, done

Read at origin/master `e5d70012bdc` (20 November 2026). Nothing that could have removed the case
has landed, so the problem stands as the row states it; whether it still reproduces is step 1,
which is a run, and the row's own rule closes it if no run does.

1. `VarkaCompiledCheck.java` is unchanged since #1500 (`git log` on the file: #1500, then #1248),
   and `VarkaMemorySanitizer.java` since #953 (row 401). The coverage row `nullif(ntz, ntz2)` is
   in `coverage.json`.
2. Rows 383 (the long-lane `profile_predicate` releases, **Started**) and 430 (the sanitizer's
   inlined `check`, **Planned**, `VARKA-430.md`) have not landed; neither plan changed after 11
   November 2026.
3. Where the case is reachable: `dev/varka_matrix.sh` sets `-Dvarka.sanitizeMemory=true` in every
   test JVM; neither `.github/workflows/varka-fuzz.yml` nor `dev/varka_compiled_fuzz.sh` sets it.
   The compiled test is `VarkaMatrix.C2Code`, cancelled where `VarkaMatrixRuns.changesC2` is
   false. `C2_OPTIONS` gained `narrowGroupStore` on 20 November 2026 (#1546, row 603) beside
   `lanesOverride`, `narrowHalfSpecies` and `useAVX`; `sql/varka/matrix/config-suite-seconds.tsv`
   still lists the suite under 49 configurations, of which `defaults`, `lanesOverride=4`,
   `lanesOverride=16`, `narrowHalfSpecies=true` and `useAVX=2` run it. So the row's count of five
   sanitized configurations stands until the matrix lists a `narrowGroupStore` one.
4. The replay knobs exist: `-Dvarka.fuzz.coverageRow`, `-Dvarka.fuzz.history` and
   `-Dvarka.fuzz.coverageCompiled` (`VarkaCoverageCompositionFuzzSuite`, `VarkaCompiledCheck`),
   which #1500 used to replay `time_to_seconds(t)` alone.
5. The probe to extend exists: `VarkaSanitizedWarmupSuite` and `VarkaSanitizedWarmupProbe` warm
   a kernel in a JVM of their own under the sanitizer and read `-Xlog:deoptimization=debug`.

What the check would have rejected: a landed fix of 383 or 430, or a sanitizer change since
#1500. None is on master.

## 3. The design

This row is a diagnosis with two possible endings, as its done-when says. No code changes until
step 4.

### 3.1 The readings

All on the laptop, `SPARK_LOCAL_IP=127.0.0.1`, under the heavy-JVM lock, plain `java` on the
catalyst test classpath as #1500 ran it, each run's command and tally committed to
`sql/varka/plans/m8/results/VARKA-605-readings.txt`.

* **R1, the narrow replay.** Sanitized, seed 20261111,
  `-Dvarka.fuzz.coverageRow="nullif(ntz, ntz2)"`, once with `-Dvarka.fuzz.history=warm-same` and
  once with `quiet-null`, `-z` on the compiled test, three runs each. Reproduces if a run tallies
  the case unsettled.
* **R2, the whole suite.** Only if R1 does not reproduce: the suite at seed 20261111 and
  `-Dvarka.fuzz.compositions=400`, sanitized, three runs, and one plain run as the control. A
  case that R2 reproduces and R1 does not depends on what the JVM ran before it, which points at
  row 430's two-sided profile.
* **R3, the trap.** The reproducing form with `-Xlog:deoptimization=debug` and the tier witness:
  the trapping method, bytecode index and reason of each trap on the kernel's class, and which
  tier served the compared calls.
* **R4, the loop predicate.** The reproducing form with `-XX:-UseProfiledLoopPredicate`, three
  runs: whether the case settles.

If neither R1 nor R2 reproduces in its three runs, the row closes as not reproducible, with the
commands and tallies (the row's rule), and steps 4 and 5 are not built.

### 3.2 The endings

From R3 and R4, one of three:

1. **Row 430's mechanism**: the traps sit at `VarkaMemorySanitizer.check`'s window test inlined
   in the masked loop. Row 430 owns the fix; this row records the reading and closes on 430's
   rerun, as the row says.
2. **Row 383's mechanism**: the traps sit at `loopMasked0`'s back edge and R4 settles them. Row
   383 owns the loop shape; this row closes on its rerun.
3. **Neither**, or the owning row is not scheduled in this milestone: the compiled test names
   the case. `VarkaCompiledCheck` gains a small table of expected-unsettled cases, keyed by the
   coverage row's SQL, the tallied lane and the history, read only when the sanitizer is on; a
   tallied case in it prints as `unsettled (expected under the sanitizer: <reason>)` and is
   counted apart from the unexpected ones, and an entry that settles prints as stale. No failure
   is added either way: the row says the defaults, the four configurations and the AVX2 128-bit
   sweep keep counting the case.

Endings 1 and 2 leave the sanitized runs as they are until the owning row lands, which the
done-when's "or" allows; ending 3 is the only one this row builds code for. Question 1 asks which
the owner wants if R3 shows 430's mechanism but 430 stays unscheduled.

### 3.3 What is deliberately unchanged

* The sanitizer, the warm-up and the kernel's emission.
* The nightly: it keeps running the compiled test unsanitized, as #1500 decided.
* The tally's verdict: no case fails the test.

### 3.4 Registered op counts

None.

## 4. Files

| file | what |
|---|---|
| `results/VARKA-605-readings.txt` | R1 to R4, commands and tallies |
| `VarkaCompiledCheck.java` | ending 3 only: the expected-unsettled table and its tally line |
| `VarkaCoverageCompositionFuzzSuite.scala` | ending 3 only: a test of the table's tally lines |

## 5. Tests, and what each is for

* Endings 1 and 2: none; the readings are the deliverable.
* Ending 3: a tally test that a planted entry for a case that settles prints as stale, and that a
  listed case prints with its reason and is not counted among the unexpected; run sanitized and
  plain, since the table is read only under the sanitizer.
* All endings: a sanitized `dev/varka_matrix.sh --defaults` run of the suite at the reproducing
  seed, its tally quoted, which is the done-when's second run.

## 6. The measurement

The readings are the measurement. The laptop is shared with other agents' JVMs, and row 383
found that load does not explain its releases but row 274's band moved with load
(`skills/calibration`'s VARKA-274 and VARKA-378 rows), so each reading records the one-minute
load beside it and R1 to R4 interleave plain and sanitized runs.

### 6.1 Predictions, registered before the run

1. R1 does not reproduce (0 of 6 unsettled): the follow-up sanitized run of 11 November 2026, of
   the compiled test alone at seed 20260925, settled 8 of 8, and a case alone in its JVM has the
   one-sided profile row 430 needs to be absent.
2. R2 reproduces in at least 2 of 3 sanitized runs, and the plain control settles 8 of 8.
3. R3's traps name the kernel's `loopMasked0` with reason `profile_predicate`, and R4 settles the
   case in at least 2 of 3 runs. Confidence is low, about even: the predicate that traps may be
   the hoisted `check` branch (430's) rather than the loop exit (383's), and R4 clears both.
4. Time: R1 about 1 minute a run; R2 about 3 minutes a run (#1500's 181 s sanitized on an idle
   laptop, at the VARKA-274b ratio of 1.1 to 1.8 under load, so 3 to 6 minutes); the whole set of
   readings under 45 minutes of machine time.

## 7. Risks

1. The case depends on timing and does not reproduce on a loaded laptop, or reproduces only
   there: three runs a form, the load recorded, and the row's not-reproducible ending.
2. R4's flag clears both candidate mechanisms, so R4 alone cannot separate them; R3's bytecode
   index does: the window test's branch is in `check`'s inlined body, the back edge is in the
   loop.
3. An expected-unsettled table hides a later regression of the same case: the stale line, and
   the table read only under the sanitizer.

## 8. Sequencing

1. The readings, R1 to R4, committed with this file's outcome: done when
   `results/VARKA-605-readings.txt` holds each run's command and tally and section 10 names the
   ending. About 120 counted lines with the plan's outcome.
2. Ending 3 only: the table and its test, about 80 lines, in the same pull request if the
   readings are short, otherwise a second.

Both under the 1000-line cap. Nothing here waits on another row; endings 1 and 2 hand the case to
rows 430 and 383.

## 9. Questions for the owner

1. **If R3 shows row 430's mechanism, wait for 430 or name the case now?** *Recommendation:*
   name it now (ending 3), with the stale line removing the entry once 430 lands, because 430 is
   planned with no date and the sanitized matrix keeps reporting an unexplained unsettled case in
   the meantime. I cannot decide it because the row offers both and the ordering of row 430 is the
   owner's.

## 10. Done when

A sanitized run that reproduces the unsettled case (R1, or R2 shown to reproduce it) is read with
`-Xlog:deoptimization=debug` and the tier witness, recording the trapping method, bytecode and
reason, whether `-XX:-UseProfiledLoopPredicate` settles it, and which tier the compared calls
ran, with the commands; and then either the kernel settles under the sanitizer in both histories
in that run and in a sanitized `dev/varka_matrix.sh --defaults` run of the suite at that seed, or
the compiled test names the case as expected to stay unsettled under the sanitizer, with the
measured reason, in its tally. If no sanitized run at that seed reproduces it, the row closes as
not reproducible, with the runs' commands and tallies. No failure on unsettled cases is added;
if row 383's loop shape or row 430's fix settles it, this row closes on that rerun.
