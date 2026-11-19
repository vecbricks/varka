# VARKA-614: The spike benchmarks settle through the shared helper

Row 614 of `PLAN.md`, added on 14 November 2026 from #1531's review of row 442. Row 442 gave the
benchmarks that time an emitted kernel one settling loop, `VarkaEmitterTestSupport.Settle.drive`,
which confirms each loop at C2 through the tier witness. Three hand-written spike benchmarks kept
their own loops, which settle on the code list alone, and `VARKA-442.md` 6 and the settling bullet
of `sql/varka/skills/the-jit.md` name them as the exceptions.

## 1. Where this came from

The review of row 442 (#1531) found that `VARKA-442.md` 6 described the three spike benchmarks
wrongly: it said they "call `awaitC2Idle` alone and read no levels", but each drives until its
own `levelInUse` reads level 4 in `Compiler.codelist`. That is the condition row 442 replaced for
the emitted-kernel benchmarks, because the code list does not mark an on-stack-replacement (OSR)
version and never shows a loop C2 inlined into its caller. The section was corrected on 14
November 2026, and the skill's bullet says the three "keep their own loops until their row".
This is that row.

## 2. The admission check, done

Read on master at `e5d70012bdc` (paths under `sql/catalyst/src/test/java/org/apache/spark/sql/`).
Nothing was run for this check: it is a reading of code that has not changed since the row was
written.

1. **The three loops still stand, each the same.** `VarkaNarrowLaneBenchmark.driveToCompiledCode`
   (`:705`), `VarkaDecimalPairSpikeBenchmark.driveToCompiledCode` (`:1339`) and
   `VarkaDecimalRemainderSpikeBenchmark.driveToCompiledCode` (`:1335`) each settle on: at least
   `MIN_WARM_DRIVES` (8) drives, C2 idle after each (`awaitC2Idle`, 10 s), the last two drives
   within `STEADY_FRACTION` (0.3), and `levelInUse(arm.method()) == 4`, inside
   `WARM_WAIT_MILLIS` (30 s). Each has its own `levelInUse`, a split of the code list's lines
   with a fold of the highest level in use (state 0), and its own `codeList()` over the
   `DiagnosticCommand` MBean, which `requireHarness` also calls to refuse a JVM without it.
2. **The blind spot is real in these classes, not only in principle.** The remainder spike's own
   Javadoc on `levelInUse` says so: "a level-4 OSR compile ... also reads as 4: at the rungs past
   L2 a drive is four calls or one, and a loop whose method code was made not entrant there may
   be timed in it." The narrow and pair spikes' Javadocs say from the second rung on the level
   "is true on arrival", so only the drive times settle those rungs.
3. **The helper fits them as it is.** `Settle` takes a kernel class, a set of loop names, extra
   methods, a `Runnable` drive, the bounds and whether C2 is required. In the spikes every loop
   is a static method of the benchmark class itself, kept out of line by the required
   `dontinline` command, so the kernel class is the benchmark class and the loop set is
   `Set.of(arm.method())`. The three classes' bounds (8 drives, 0.3, 30 s) are the helper's
   `Bounds` record's three fields, and the helper's own idle wait is the spikes' 10 s.
4. **What the helper needs that the spikes do not do yet.** The caller must start the witness
   (`VarkaTierWitness.get()`) and `watch` the class before the first call of any loop, or the
   helper throws (`Settle.drive`'s check, added by #1531's review). Each spike calls its loops
   before timing (the agreement check, and the remainder spike's 20,000 priming calls), so the
   witness starts at the top of `runBenchmarkSuite`, after `requireHarness`.
5. **The recording during timed cells was priced for the emitted benchmarks.** Row 442's open
   question 2 asked whether the open JFR recording moves timed rows. Its pairs, benchmark run
   43575854826 on an EPYC 7763, were green for all three classes it moved (`VARKA-442.md` 10).
   The spikes' timed cells are loops of the same kind, so the same is expected here (6.1).

The check would have rejected the row if any of the three had already moved to the helper or if
the helper could not express a hand-written static loop. Neither holds, so the row stands.

### What differs from the row text

Nothing in substance. The row's "its own parser and in-use fold are gone" also takes each
class's private `codeList()`: `requireHarness` reads the list through
`VarkaTierWitness.readCodeList()` instead, which throws the same `IllegalStateException` kind
the helper already handles.

## 3. The design

### 3.1 The change, per class

In each of the three classes:

- `runBenchmarkSuite` starts the witness after `requireHarness` and before the first rung,
  `watch`es the benchmark class's name, and closes it (`VarkaTierWitness.close()`) in a
  `finally`, as `VarkaNarrowEmitBenchmark` does (`:797` to `:814`).
- `driveToCompiledCode` keeps whatever runs before its loop (the remainder spike's priming calls)
  and replaces its loop with one `Settle.drive(new Settle(<class>.class,
  Set.of(arm.method()), Map.of(), () -> drive(...), BOUNDS, true))`, `BOUNDS` a
  `Settle.Bounds(MIN_WARM_DRIVES, STEADY_FRACTION, WARM_WAIT_MILLIS)` constant.
- The console line keeps its form. Settled: "settled after N drives, the loop confirmed at C2".
  Not settled: "did not settle" followed by `TierState.why()`, which carries the bound, the last
  two drives, C2's state and the reason.
- `levelInUse` and `codeList()` go, and so do the constants only they used (`IDLE_WAIT_MILLIS`).

### 3.2 Alternatives considered

- **Keep the loops and fix their reading** with `VarkaTierWitness.parse` and the OSR test. That
  is a fourth copy of what `Settle.drive` holds, which is what row 442 removed.
- **A spike base class** holding the witness and the settle. Three classes with three different
  harness shapes would share four lines; not worth a type (the owner's rule against
  over-engineering a refactor).

### 3.3 What is deliberately unchanged

The arms, their loops, the agreement checks, the remainder spike's intrinsic check in child
JVMs, the rungs and the results files. Row 442's other benchmarks and the helper itself.

### 3.4 Registered op counts

None: no emitted kernel changes, and these classes emit nothing.

## 4. Files

| file | what |
|---|---|
| `VarkaNarrowLaneBenchmark.java` | the witness and `Settle.drive`; its own code-list reading gone |
| `VarkaDecimalPairSpikeBenchmark.java` | the same |
| `VarkaDecimalRemainderSpikeBenchmark.java` | the same, its priming calls kept before the settle |
| `sql/varka/skills/the-jit.md` | the settling bullet names no exception |
| `sql/varka/plans/m8/VARKA-614.md`, `PLAN.md` row 614 | the record |

## 5. Tests, and what each is for

No new test: the helper's policy is held by `VarkaEmitterTestSupportSuite` and
`VarkaVectorTemplateWarmupSuite` (row 442). The checks are the classes' own runs:

1. `build/sbt catalyst/Test/compile` - the three classes compile against the helper.
2. Each class's dry run, `-Dvarka.bench.dryRun=true` (all three honour it): every arm is still
   built and no loop reference names a missing method.
3. One short arm of each on the laptop, at 256 bits, with the class's required compile commands,
   to see the settle line: `VarkaNarrowLaneBenchmark` with `-Dvarka.narrow.arms=scalar`;
   `VarkaDecimalPairSpikeBenchmark` with `-Dvarka.pair.forms=p -Dvarka.pair.rungs=l2`;
   `VarkaDecimalRemainderSpikeBenchmark` with `-Dvarka.remainder.rungs=1
   -Dvarka.bench.only="split F"`. Each run's output is kept in the pull request's description,
   not committed: these are smoke checks, and a laptop shared with other agents times nothing.
4. A JVM without the code list cannot be had on the laptop; the policy for it is the helper's,
   already tested through its reader seam.

## 6. The measurement

None that moves a committed number. The spikes' results files record decisions already taken
(rows 329, 359 and 577) and are not regenerated: the timed loops do not change, only how an arm
is declared settled before timing. A pair on the runner is not asked for (question 1).

### 6.1 Predictions, registered before the run

1. Every smoke arm of 5.3 prints a settled line with its loop confirmed at C2. An arm that does
   not would be the OSR case of 2.2, and it then says so with the reason instead of being timed
   as level 4 in silence.
2. Each settle takes about one JFR delivery longer, about a second an arm, as row 442's P1
   registered for the emitted benchmarks; for the remainder spike's full ladder of 24 arms at
   three rungs that is about a minute on a run of tens of minutes.
3. The open recording moves no spike's timed loop: row 442's three pairs were green with it
   (2.5). Not measured here (6).

## 7. Risks

1. **An arm that settled before may now time out.** Where a loop is only ever OSR-compiled (the
   remainder spike's rungs past L2), the witness does not confirm it and the arm is timed after
   the 30 s bound with the reason. That is the correction the row asks for, but it costs 30 s an
   arm and a later spike reading would show "did not settle" lines; 5.3's smoke at the first
   rung would not see it, so the pull request says so.
2. **The witness started too late.** A loop called before `watch` makes `Settle.drive` throw; the
   dry run does not reach it (it returns before `requireHarness`), so 5.3 is the check.

## 8. Sequencing

One pull request, small: the three classes, the skill's bullet, this plan's outcome and the row.
About 90 lines removed and 50 added in the classes. One commit for the plan (this one), one for
the change.

## 9. Outcome

*To be written when the work lands.*

## 10. Questions for the owner

1. **A runner reading of the spikes.** Should the change carry one dispatch of each spike on the
   EPYC 7763 to show the arms still settle at full length, or are 5.3's laptop smoke runs enough?
   Recommended: the smoke runs. The results are records of decisions taken, the timed loops are
   unchanged, and the full remainder ladder costs most of an hour of a runner; the decision is
   the owner's because he controls every dispatch.

## 11. Done when

- each of the three classes settles through `VarkaEmitterTestSupport.Settle.drive`, with the
  witness started and the class watched before its first loop call;
- their own `levelInUse`, its code-list parser and in-use fold, and their private `codeList()`
  are gone;
- the settling bullet of `sql/varka/skills/the-jit.md` names no exception.
