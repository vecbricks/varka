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
section 8's step 2, which only ending 3 builds.

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
commands and tallies (the row's rule), and section 8's step 2 is not built.

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
| `VarkaCoverageCompositionFuzzSuite.scala` | names its sample (built); ending 3: a tally test |

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
   `results/VARKA-605-readings.txt` holds each run's command and tally and section 11 names the
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

## 11. Outcome

Read on the laptop on 20 November 2026 at origin/master `0b8be5a33e4`, one JVM a run as #1500
ran it, on a machine shared with other agents (one-minute load 19 to 68 on 24 threads). Every
run's command, tally, trap counts and load are in `results/VARKA-605-readings.txt`. The ending is
**2, row 383's mechanism**, and the row's premise that the case needs the sanitizer is refuted.

### 11.1 What the readings show

1. **The seed's sample moved with the table.** At seed 20261111 the compiled test now samples 16
   rows (four lane types, 4 each, from 539 rows), and `nullif(ntz, ntz2)` is not among them. It
   holds `nullif(t, t2)`, whose emitted class is byte for byte the same kernel (`javap -c -p` of
   both classes, dumped by a class-file agent, identical once the class name is masked), so the
   sample still runs the row's kernel, under another row's name.
2. **R1, the row alone, settles**: 0 of 6 sanitized runs unsettled; each still takes one
   `profile_predicate` trap (two in one run) at `loopMasked0` bytecode 205.
3. **R2, the sample, reproduces the case, with and without the sanitizer.** Sanitized, 2 of 3
   runs left the long-tallied lane at 5 of 8 settled in both `warm-same` and `quiet-null`, the
   same three rows each time: `try_subtract(l, l2)`, `nullif(t, t2)` and
   `if(x IS NULL, x2, NULL)` (a DOUBLE row, tallied with LONG). The plain control left one to
   three of the same rows unsettled in each of its three runs (warm-same 7, 7 and 6 of 8;
   quiet-null 6, 8 and 5 of 8). The int-tallied lane settled 8 of 8 in every run.
4. **R3, the trap.** Each unsettled kernel's `loopMasked0` is at no tier at the deadline while
   its epilogue is C2 code (`levels {epilogueMasked0=4, loopMasked0=0}`), and its class takes 4
   to 24 `profile_predicate` traps, `maybe_recompile`, all at one bytecode a kernel: 392, 412 and
   362. In `nullif(t, t2)`'s class bytecode 412 is `goto 201`, the back edge of the main vector
   loop `for (i = 0; i < species.loopBound(n); i += species.length())`, whose head at 205 is
   `if_icmpge 415`. That is row 383's trap: the same reason and action, on the same construct
   (`VARKA-383.md` 9.3 found `goto 241`, the back edge, at bytecode 566). The compared calls show
   the lower tier in the readings' tally lines (a tally, not a tier read per row).
   A sixfold deadline does not settle it: at `-Dvarka.fuzz.settleMillis=60000` two of the rows
   were still unsettled with 42 to 52 back-edge traps a class, about one every 1.2 s, so it is a
   cycle and not a deadline starved by load. Alone, a row's loop traps at its head (bytecode 205)
   once or twice and never at its back edge.
5. **The sanitizer is not in the loop.** The kernel maps its buffers through
   `VarkaVectorSupport.ofAddress`, whose sanitizer check runs at bytecodes 34 to 173, before the
   loop; the loop body calls only `validityBitsAt8`, `orValidityBitsAt8` (a byte get and set on
   the validity segment, no sanitizer branch) and Vector API methods.
   So no hoisted window test can be the failing predicate, and row 430's mechanism is ruled out
   for this kernel.
6. **The case depends on what the JVM compiled before it.** `try_subtract(l, l2)` and
   `nullif(t, t2)` alone, sanitized, over `warm-same` and `quiet-null`, settled 4 of 4 each (R1b,
   two runs a row). In the sample they are the third and fourth LONG rows, after
   `timestampadd(MONTH, l, ntz)` and `CAST(ntz AS TIME(3))`. A kernel class is fresh each history,
   so what carries over is the profile of the code it inlines (`VarkaVectorSupport` and the
   Vector API), which every kernel shares; this is an inference, not a reading.
7. **R4, the flag, settles it.** With `-XX:+UnlockDiagnosticVMOptions
   -XX:-UseProfiledLoopPredicate`, sanitized, the three rows settled in 3 of 3 runs, and the logs
   hold no `profile_predicate` trap at all. One of the three (load 68) left two other rows
   unsettled in three cases (`timestampadd(MONTH, l, ntz)` in both histories, `d - ym` in
   `quiet-null`) with no `profile_predicate` trap (one `unstable_if` for `d - ym`) and their
   epilogues at no tier: a starved compile queue, not this case.

### 11.2 The ending

Ending 2: the traps sit at `loopMasked0`'s back edge, R4 clears them, and the plain control
reproduces them, which is row 383's plain long-lane release. Row 383 is **Started** in this
milestone and owns the loop shape, so this row builds no expected-unsettled table and closes on
383's rerun of this reading: R2's form, sanitized and plain, three runs each. Section 3.2's ending
3 does not fit the case either way: a table keyed by one row's SQL would print the entry as
stale in every single-row replay (R1, R1b) and miss the other rows the sample happens to hold.

### 11.3 Predictions scored

1. R1 does not reproduce, 0 of 6: **held** (0 of 6). Its reason did not: the sanitizer turned out
   not to matter, so the one-sided profile row 430 needs is beside the point.
2. R2 reproduces in at least 2 of 3 sanitized runs, the plain control settles 8 of 8: **half
   held**. 2 of 3 sanitized runs reproduced, but no plain run of three settled 8 of 8, and the
   sample R2 ran was not the one the prediction assumed.
3. `loopMasked0` traps at `profile_predicate` and R4 settles it in at least 2 of 3: **held** (3 of
   3). The bytecode did not separate 383 from 430 the way risk 2 said: C2 places a loop's parse
   predicates, the profile predicate among them, at the loop's entry (at the `if` dominating the
   loop, `Parse::maybe_add_predicate_after_if` in `parse2.cpp`, or at the head block's start in
   `parse1.cpp`), and every predicate hoisted into the loop traps there, so the trap's bytecode is
   a position in the loop whichever test failed. Why the cycling trap reports the back edge's
   `goto` rather than the head is observed, not explained. What separated 383 from 430 was the
   plain control and the place of the sanitizer's check in the bytecode.
4. Time, R1 about 1 minute a run, R2 3 to 6 minutes, all under 45 minutes: **held for R1**
   (45 to 60 s), **R2 within its range** (2.5 to 5.7 minutes); the whole set took about 70 minutes
   of machine time with R1b, the class dumps and the extra controls.

### 11.4 What the plan did not list

* The sample's drift: a seed names a sample of the table as it stands, so a tier fact read off a
  seed is replayable only at its commit. The compiled test now names the rows it sampled (not
  the whole table), which is the one code change here.
* The tally prints no lane line when every case ran one history (`Tally.report` counts a case's
  compiled time only when it ran more than one history), so a single-history replay shows only
  its `unsettled:` lines. Left as it is; noted for whoever next reads a single-history replay.

## Decisions taken while the owner was away (20 November 2026)

1. **R2 ran the compiled test alone (`-z`), not the whole suite.** The compiled test is the
   suite's first registered test, so the whole suite's JVM runs nothing before it; `-z` is the
   same JVM history up to the test's end at a third of the time.
2. **R2 read today's sample, not #1500's.** Rebuilding #1500's commit to recover its sample would
   cost a second build, and today's sample holds the same kernel (11.1, item 1).
3. **`-Xlog:deoptimization=debug` was on in every run**, so R3 is read off R2's own runs; the log
   writes to a file and does not change what C2 compiles.
4. **The compiled test names its sample.** A code change before the plan's step 2, which the plan
   did not list; without it the readings could not say which rows were unsettled at a seed.
5. **Ending 2, with no table.** Question 1 asked about row 430's mechanism, which the readings rule
   out; for row 383's the plan's own ending 2 applies, since 383 is Started in this milestone.
