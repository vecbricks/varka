# VARKA-684: The proof of the zone kernels

*Planned on 26 November 2026. The plan stands alone: a builder needs this file and the
repository's `AGENTS.md` files. The row is not started, since milestone 9 waits for the owner's
word (`m9/PLAN.md` decision 18). Its code facts were checked on master `176db41f369`. The rows it
builds on (683, 682, 678, 656 and 657) were not built on that day, so each build sheet starts
with greps of their names.*

## 1. What this row is

**The row's text** (`m9/PLAN.md` section 3). "The proof of the zone kernels. `VARKA-657.md` 5,
for `ToLocal`: `VarkaZoneSweepSuite` over every distinct set of rules, with the metamorphic
checks; `zone_step.smt2` and the planted faults of `ToLocal` and of the batch arguments; the
session zone as a drawn setting of the Spark fuzzer, zone nodes in the IR fuzzer's grammar, and
the property suite. **Done when** the sweep passes in every Build for `ToLocal` inside its time
budget, each of its planted faults fails a named test, and both fuzzers draw a zone."

**What it gives the rows after it.**

- Row 690 adds three more cases of `VarkaZoneKernels.Rule` (4.1): `ToInstant` under `EARLIER`,
  `ToInstant` under `GAP_END` and `ToInstantKept`, which are three roots with three argument
  runs. It adds two more planted faults, and the there-and-back check. It writes no harness.
- Row 689 needs part 2 merged first: its step with no mask is exact only under the bound that
  `zone_step.smt2` proves (2.4), and the sweep's wide-span test fails a kernel that ignores it.
- Rows 685 and 686 run the sweep over their forms: row 686 adds `TABLE` to the sweep's list.
- Every later zone kernel (rows 668 to 671 and 673 to 675) rests on the claim proved here: the
  batch arguments are `java.time`'s in every zone.

**What must be merged before it starts.** Row 657, and so rows 683, 682, 678, 656 and 655.

**The goals it serves** (`m9/PLAN.md` 1.1), each with the item of 1.2 that it moves.

- **G2, directly.** Item 2 asks that "the every-zone sweep passes in CI under each rule". This
  row builds the sweep and passes it for the instant-to-local direction. The three rules of the
  way back are row 690's cases; the ten-year clause of item 2 is rows 685 and 686's.
- **G1, only through later rows.** No entry fuses here and the census does not move (item 1).
- **G7, only through row 689.** Item 10 asks that no section is slower than the row path at 128
  bits. Row 689's step with no mask is how the zone kernels meet it, and its proof is here.
- **G5, not served.** All three parts are built at full strength (section 8).

## 2. What an agent must know

### 2.1 The idea

A zone's offset from UTC is a step function of the instant. `java.time` keeps a zone as
`ZoneRules`: a list of changes, each an instant `T` with the offset before and after it, and
yearly rules for the years after the last listed change. An instant `x` is a long of
microseconds since the epoch. Its local time is `x + offset(x)`, where `offset(x)` is the offset
after the last change at or before `x`. Row 682's IR node `ToLocal(instant, zone)` is that sum.

A kernel is a class the emitter writes for a projection. It answers a batch of rows a call and
holds no zone. For each batch the evaluator fills *batch arguments*, entries of the kernel's
`longArgs` after the literals: `base`, the offset at the batch's least instant, and for each
change inside the batch's range a threshold `at` and a `delta`, the offset after less the offset
before. The kernel computes `x + base + (x >= at[0] ? delta[0] : 0) + ...`, one step a change.

So a kernel is right when the arguments are `java.time`'s (the data) and the kernel adds them
as the formula says, for every long (the lowering). This row shows both, with five instruments:

1. **The sweep** runs the emitted kernel, with arguments filled by the production zone data,
   against `java.time` at every change of every zone of the JVM.
2. **The metamorphic checks** compare kernels with each other and use no oracle.
3. **The proof file** shows the step's arithmetic for every long, under two solvers.
4. **The planted faults** show that each check fails on the fault it exists for.
5. **The fuzzers and the property suite** reach trees, sessions and ranges that no test lists.

### 2.2 Terms

- **Change:** an instant `T` at which a zone's offset changes (`ZoneOffsetTransition`): a *gap*
  where the offset grows and local times are skipped, an *overlap* where it shrinks and local
  times repeat. `d` is its size, `|after - before|`.
- **Stored change:** one of `ZoneRules.getTransitions()`. **Rule era:** the instants after a
  zone's last stored change, where `java.time` works each year's changes out from
  `getTransitionRules()`.
- **Segment:** the instants between two consecutive changes. The offset is constant there.
- **Set of rules:** the zone ids whose `ZoneRules` are equal. The sweep reads one id of a set.
- **Zone data:** a `VarkaZoneData`. It counts the changes of a range and writes a batch's
  arguments. Row 678's `VarkaChronoZone` is production's.
- **Form:** how many steps a kernel holds: `ONE` none, `STEPS_2` two, `STEPS_8` eight. A form
  *covers* a batch when the changes `T` with `least < T <= greatest` are at most its steps. A
  step the batch does not need is *padded*: `at = Long.MAX_VALUE`, `delta = 0`.
- **The fill:** the call that writes a batch's arguments, or answers that the batch *declines*:
  its form does not cover the range, or an instant is within 18 hours of an end of a long. A
  declined batch takes the row path (`VarkaKernelRunner.STATUS_BATCH_ARGS`).
- **Masked step:** a compare and a masked add, row 682's. **Step with no mask:**
  `((at - 1 - x) >> 63) & delta`, row 689's, for two 64-bit lanes on x86.
- **Body:** a kernel's *dense* body runs a batch with no null, its *masked* body one with nulls.
- **The Build:** a pull request's CI run, at the emit defaults. **The option matrix** reruns the
  suites with one option changed, and on three platforms: `isa=avx2`, `isa=w128`, `isa=arm`.
- **Oracle:** what a test compares with. Here it is `ZoneRules.getOffset(Instant)`, the call
  Spark's row engine makes through `Instant.atZone`.

### 2.3 The names

Read, from rows 682, 683, 678 and 657, as `VARKA-657.md` section 3 and those rows' plans have
them:

| name | meaning |
| :--- | :--- |
| `VarkaVectorIR.ToLocal(VarkaVectorIR instant, int zone)` under `sealed interface Zoned` (`zone()`, `operand()`) | the node; `zone` indexes `CompiledVarkaProjection.zones()`, a `List<String>` of zone ids |
| `VarkaEmitOptions.zoneForm`, `enum ZoneForm { ONE, STEPS_2, STEPS_8 }` | the form, one for a kernel; rows 685 and 686 add `LADDER` and `TABLE` |
| `VarkaBatchArgs.Program(Zoned node, int first, int count)`, `layout(outputs, numLongLiterals, options)`, `count(node, form)` | a node's run of `longArgs`: for `ToLocal`, `base`, then `at[i]`, `delta[i]`: `1 + 2k` entries |
| `VarkaZoneData`: `id()`, `fixed()`, `changes(least, greatest)`, `toLocalArgs(least, greatest, steps, args, first)`; `VarkaZoneData.Provider.of(zoneId)` | the zone data, and where a zone id resolves, on the executor |
| `VarkaJavaTimeZoneData` (catalyst tests), `VarkaChronoZone` (row 678) | a reference built from `ZoneRules`, and the production zone data |
| `VarkaZoneLowering`, `VarkaZoneCompiler`, `VarkaFamily.ZONE` | the lowering, the compiler's zone arms, the family and its switch |
| `VarkaKernelRunner.STATUS_BATCH_ARGS`, `LongRangeOps.leastAndGreatest` | the runner's decline, and the range of a long column |
| `VarkaEmitterZoneSuite`, `VarkaBatchArgsSuite`, `VarkaBatchArgRunnerSuite`, `VarkaTimestampZoneSuite` | the suites of rows 682 and 657 |
| `VarkaZoneLowering.STEP_NO_MASK_MAX_SPAN = Long.MAX_VALUE` | the bound of 2.4; part 2 of this row adds it |

Added by this row:

| name | where | meaning |
| :--- | :--- | :--- |
| `VarkaZoneKernels` with `Rule`, `Points`, `Kernel`, `Filler`, `Spec`, `Report`, `Mismatch` | catalyst tests, Java | the harness of 4.1; row 690 adds rules to it |
| `VarkaZoneSweepSuite`, `VarkaZonePlantedFaultSuite`, `VarkaZoneArgsPropertySuite` | catalyst tests, JUnit 5 under `@VarkaSuite` | 4.3, 4.7 and 4.10 |
| `VarkaZoneFaults` | catalyst tests, Java | the planted faults, as wrappers |
| `zone_step.smt2`, `VarkaProofFiles.zoneStep()` | `sql/varka/proofs/`, catalyst tests | the proof of 4.6 |
| `VarkaIrGrammar.ZONE_FUZZ_SEED`, `ZONE_COLUMN_BOUND`, `ZONE_INDICES`, `drawZoneShape` | catalyst tests, Java | the IR fuzzer's zone sequence |
| `VarkaSparkFuzz.Zones`, `Case.zone`, `Reproducer.zone`, `VarkaSharedSessions.withSessionZone` | sql/core tests, Scala | the session zone of a case |
| `-Dvarka.zone.seed`, `-Dvarka.zone.cases`, `-Dvarka.zone.only` | system properties | the property suite's seed, count and replay |

### 2.4 The step with no mask has a bound of its own

Found while this plan was written. This pull request corrects `VARKA-657.md` (3.8 and section
5) and row 689 of `m9/PLAN.md` for it. `((at - 1 - x) >> 63) & delta`
equals `x >= at ? delta : 0` exactly where `at - 1 - x` is a long. The bounds of
`VARKA-657.md` 3.6, an instant 18 hours inside a long's ends, do not give that. With
`x = Long.MIN_VALUE + 18 h` and a change `at` one microsecond past 18:00 on 1 January 1970,
`at - 1 - x` is 2^63 and wraps, so the step adds the delta where the select adds nothing. A zone
with two changes (`Asia/Kathmandu`) lets `STEPS_2` cover such a batch, so this would be a wrong
answer and not a decline.

What holds it: a real step has `least < at <= greatest` and a row has `least <= x <= greatest`,
so `at - 1 - x` is a long while `greatest - least` is one. A padded step adds 0 for every long,
and the masked step is exact for every long. So a kernel whose steps have no mask declines a
batch whose span `greatest - least` overflows a long. The constant is
`VarkaZoneLowering.STEP_NO_MASK_MAX_SPAN = Long.MAX_VALUE`; row 689 builds the decline.

## 3. The admission check

Each fact was checked on `176db41f369` with the command under it. `V` is
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka`, `T` is the
same path under `src/test/java`, `TS` under `src/test/scala`, and `S` is
`sql/core/src/test/scala/org/apache/spark/sql/execution`.

1. **How a suite builds and runs a kernel.** `VarkaEmitterFixtures.emit` calls
   `VarkaLoopEmitter.emit(name, roots, numInputs, numLiterals, null, null, options)`, and
   `VarkaEmitterFixtures.load` defines the class and gives the `VarkaFusedKernel`. A long-lane
   kernel reads its literals from `longArgs`, the seventh argument of its `run`.

        grep -n 'public static Emitted emit\|public static Loaded load' $T/VarkaEmitterFixtures.java

2. **The calendar sweep of VARKA-281 runs no kernel.** It is `VarkaRangeRulesSuite.sweep`:
   `IntStream.rangeClosed(FIRST, LAST).parallel().filter(d -> !rule.test(d)).findFirst()` over
   the 20 million admitted days, each test asking `java.time` and `DateTimeUtils` about a day.
   The common pool splits the range over the JVM's cores and the JIT inlines the decode. Its
   eight tests took 1.5 s on the laptop, where the estimate had priced about 10 s of one core
   and doubled it (the calibration row of VARKA-281). CI's weight for the suite is 5.6 s
   (`sql/varka/ci/suite-times.tsv`), 3.7 times the laptop.

        grep -n 'parallel()' $T/VarkaRangeRulesSuite.java
        python3 dev/varka_calibration.py | grep 'VARKA-281'
        grep -n 'VarkaRangeRulesSuite' sql/varka/ci/suite-times.tsv

3. **The sweep that runs kernels in parallel** is `VarkaTimestampNtzCastSuite.sweep`: one loaded
   kernel a field, shared by every worker; batches of `SWEEP_ROWS = 1 << 16` rows under
   `IntStream.range(0, batches).parallel().forEach`; each call inside
   `VarkaKernelChecks.sanitized`, so that the memory sanitizer sees its buffers; both bodies.
   This row's harness follows it.

        grep -n 'parallel()\|SWEEP_ROWS = ' $T/VarkaTimestampNtzCastSuite.java
        grep -n 'static int sanitized' $T/VarkaKernelChecks.java

4. **`VarkaCompiledCheck` is not the sweep's tool.** It runs one drawn case over eight JIT
   histories (`History`, `cold` to `released`), a class and a warm-up of up to 10 s for each. The
   sweep runs three kernels over millions of rows: C2 compiles them within the first batches.

        grep -n 'enum History' -A9 $T/VarkaCompiledCheck.java

5. **The probe's numbers** (`sql/varka/spikes/zone-offset/results/`). `facts.txt`: 604 zone ids,
   65794 changes before 2100, the closest two of one zone 601200 s apart, 349 distinct sets of
   rules. `forms.txt`: `java.time`'s offset costs 10.293 ns a row beside eight lanes and 10.285
   beside four. The 349 is by the probe's own key, the first offset and the changes before 2100
   (`ZoneFacts.main`'s set `rules`). `ZoneRules.equals` also compares the standard offsets and
   the yearly rules, so the sets by it are at least 349.

        grep -n 'changes before 2100\|distinct sets\|closest two' sql/varka/spikes/zone-offset/results/facts.txt
        grep -n 'java.time, the offset' sql/varka/spikes/zone-offset/results/forms.txt
        grep -n 'rules.add(' sql/varka/spikes/zone-offset/src/ZoneFacts.java

6. **The oracle reads whole seconds, and keeps no rule year from 2100 on.** JDK 25's
   `ZoneRules.getOffset(Instant)` reads `instant.getEpochSecond()` alone. Past the last stored
   change it calls `findTransitionArray(year)`, which caches a year only below
   `LAST_CACHED_YEAR = 2100`. So an oracle call from 2100 on works the year's changes out again.

        grep -n 'LAST_CACHED_YEAR' /home/max/proj/openjdk-build/jdk25/src/java.base/share/classes/java/time/zone/ZoneRules.java

7. **Spark resolves a zone id** by `DateTimeUtils.getZoneId`: `ZoneId.of(id, ZoneId.SHORT_IDS)`
   after two fixes of the `(+|-)h:mm` forms. `SQLConf.SESSION_LOCAL_TIMEZONE` accepts what it
   resolves. Java calls it as a static method.

        grep -n 'def getZoneId' -A8 sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/SparkDateTimeUtils.scala

8. **Proof files.** `VarkaProofFiles.render()` maps each file name to its text. A renderer
   writes a header, `rendered(logic)` and one `check(out, what, expect, body)` a check.
   `VarkaProofFilesSuite` fails when a committed file differs, and rewrites it under
   `VARKA_PROOFS_REGEN=true`. `dev/varka_prove.sh` pins Z3 5.1.0 and cvc5 1.4.1 by SHA-256 and
   inserts `java.smt2` after the `set-logic` line. The linters' job runs `--lint`: each file
   under Z3. Both solvers run every file only in `dev/varka_nightly.sh`'s `prove` step and by
   hand. The prelude has what the step needs: `jlong.sub`, `jlong.shr`, and `jlong.and.sign`,
   whose use owes a proof that its mask is 0 or -1.

        grep -n 'static Map<String, String> render()\|static void check(StringBuilder' $T/VarkaProofFiles.java
        grep -n 'Z3_VERSION=\|CVC5_VERSION=\|^CVC5_IN_LINT=' dev/varka_prove.sh
        grep -n 'define-fun jlong.shr\|define-fun jlong.and.sign' sql/varka/proofs/java.smt2
        grep -n 'varka_prove.sh --lint\|varka_prove.sh --solver both' .github/workflows/*.yml dev/varka_nightly.sh

9. **How a suite plants a fault.** Three ways are on master. A stub on the expected side:
   `VarkaConformancePlantSuite` sets `VarkaTypeConformance.plantedAnswer`, and `failsOnThePlant`
   requires the check to fail. An emit option: `VarkaEmitOptions.FloatingFault`
   (`Reason.FAULT_INJECTOR`), with `VarkaEmitterFloatingArithSuite.failsUnder` running the same
   check method under the faulty options. A rewrite in the test: `VarkaValidityFault`, since
   "the fault needs no seam in the emitter and so cannot be reached from production".
   `VARKA-504.md` 2.1 sets the rule: a plant builds the broken input in memory and runs the real
   check on it. Its 2.4 adds that a check asserts a positive count of what it checked.

        grep -n 'plantedAnswer = fault\|private static void failsOnThePlant' $T/VarkaConformancePlantSuite.java
        grep -n 'private static void failsUnder' $T/VarkaEmitterFloatingArithSuite.java

10. **The Spark-level fuzzer.** A case is `VarkaSparkFuzz.Case`. Its setting `ansi` is drawn in
    `drawRows`, applied by `VarkaSharedSessions.withAnsi` (`withBothConf`), printed by `render`
    as `-- ansi:` and read back by `parse`. Nested trees and the wider pool draw from streams of
    their own, so a seed's compositions do not move. The suite runs in every Build at 200
    compositions. At volume it runs only on the laptop, in `dev/varka_nightly.sh`'s `sparkfuzz`
    step: 50000 compositions with the day's seed. No GitHub workflow runs it.

        grep -n 'val ansi = rnd.nextBoolean()\|-- ansi: ' $S/VarkaSparkFuzz.scala
        grep -n '^sparkfuzz=\|run_step sparkfuzz' dev/varka_nightly.sh
        git grep -c 'VarkaSparkFuzz' -- .github        # prints nothing

11. **The IR fuzzer.** `VarkaIrGrammar.LongShapes.column()` draws a long-lane leaf as a
    `Gen(node, bound)`. Later productions read the bound: a `ConstDivide` is drawn only under
    `ConstDivide.EXACT_DIVIDEND_BOUND` (2^52), a checked mode only where the bound rules an
    overflow out. `boundsOf` restates the bounds from a built tree, in one exhaustive switch.
    A new node family is drawn on a seed of its own (`UNSIGNED_FUZZ_SEED` and its kin), so no
    committed corpus moves. `VarkaLaneFuzz.drawCase` draws the batch.
    `VarkaKernelChecks.runAndCompareLong` passes the case's literals as `longArgs` and compares
    each row with `VarkaReference.longValue`, which answers a calendar node by `java.time`. The
    suite's two reach tests list the node types its corpora do not draw. The nightly workflow
    (`varka-fuzz.yml`) runs the suite in one JVM a core at 40000 iterations with the date's seeds.

        grep -n 'private Gen column()\|public static Bounds boundsOf\|UNSIGNED_FUZZ_SEED = ' $T/VarkaIrGrammar.java
        grep -n 'public static OptionalLong longValue' $T/VarkaReference.java
        grep -n "ITERATIONS: \|VarkaIrFuzzSuite" .github/workflows/varka-fuzz.yml

12. **Coverage and mutation.** `dev/varka_gen_coverage.sh` runs every Varka suite under the
    JaCoCo agent and writes `sql/varka/coverage/emitter.md`, the branches of each emitter and
    compiler source file. No workflow runs it. `dev/varka_pit.sh` mutates classes of
    `codegen.varka` and runs the catalyst ScalaTest suites only: its search greps
    `src/test/scala`, and "the JUnit 5 suites, which PIT's plugin does not reach, are left out".

        grep -n 'EMITTER_REPORT=' dev/varka_gen_coverage.sh
        grep -n 'JUnit 5 suites' dev/varka_pit.sh

13. **Two masked lanes on x86 run as Java loops.** `VarkaKernelPlan.twoLaneMasksFallBack` is
    true on x86 with two long lanes or fewer. `results/forms.txt` prices the masked steps there
    at 12.070 ns a row for two steps and 62.542 for eight, against 0.146 and 0.755 at four lanes.

**The proof sketch was run.** The 18 checks of 4.6 were written against `java.smt2` and run on
26 November 2026 under the pinned Z3 5.1.0 and cvc5 1.4.1, for their verdicts and not for their
time. Each answered as 4.6 expects.

**What could not be checked.** No name of 2.3's first table is on master
(`git grep -w -c 'ToLocal\|VarkaBatchArgs\|VarkaZoneData' -- sql/catalyst/src sql/core/src`
prints nothing). Six names that this row calls are in no merged plan yet. Each sheet's B0 prints
them, and the sheet calls each at one place:

- the fill of a program in catalyst, read here as `boolean Program.fill(VarkaZoneData data,
  ZoneForm form, long least, long greatest, long[] longArgs)`: `VARKA-657.md` 3.2 and 3.6 give
  a program a `fill` that declines;
- the constant of the 18-hour bound, called `OFFSET_BOUND` below;
- `VarkaEmitOptions.withZoneForm`;
- the production `VarkaZoneData.Provider`, `VarkaChronoZone.PROVIDER` (`VARKA-678.md` 4.10),
  called `PROD` below;
- how `VarkaReference`'s `ToLocal` arm takes the zone ids, and the kernel check that
  `VarkaEmitterZoneSuite` calls;
- the column `ts` of the Spark fuzzer's fixture, and how it is derived.

Also unchecked: the number of sets of rules by `ZoneRules.equals` and the changes they hold (no
JVM run was allowed for it; the suite prints both), and every time of section 7.

## 4. The design

### 4.1 The harness: `VarkaZoneKernels`

One final class in catalyst's tests. Every check is a function of a `Spec` and returns a
`Report`, so that the planted suite runs the real check on a broken input.

    interface Rule {                      // one conversion; row 690 adds three
      String name();                      // "ToLocal", in messages
      VarkaVectorIR root();               // over ColumnRef(0, LONG), zone index 0
      long[] points(ZoneOffsetTransition change, Points which);   // operands, ascending
      long[] cuts(ZoneOffsetTransition change);   // where the answer's offset changes
      long expected(ZoneId zone, long operand);   // java.time's answer
    }
    enum Points { SECONDS, EDGES, BOTH }
    record Kernel(Rule rule, ZoneForm form, VarkaFusedKernel kernel,
        VarkaGeneratedClassLoader loader, List<VarkaBatchArgs.Program> programs, int longArgs)
    interface Filler {                    // false: the batch declines
      boolean fill(Kernel kernel, String zoneId, long least, long greatest, long[] longArgs);
    }
    record Spec(Rule rule, List<String> zoneIds, Filler filler, List<ZoneForm> forms,
        Points points, boolean nullFirst, VarkaEmitOptions base)
    record Mismatch(String zoneId, ZoneForm form, long operand, String got, long want)
    record Report(long sets, long changes, long batches, long rows, Optional<Mismatch> first)

- `Kernel.of(rule, form, base)` emits `rule.root()` under `base.withZoneForm(form)`, one input
  and no literal, loads it by `VarkaEmitterFixtures.load`, and takes its programs from
  `VarkaBatchArgs.layout(List.of(root), 0, options)`. `base` is `VarkaMatrixRuns.BASE`, so the
  matrix reaches the kernels. One kernel a form serves every worker.
- `production(provider)` is the `Filler` that calls row 682's fill for each program, with
  `provider.of(zoneId)`. It is the only place that names that call.
- `TO_LOCAL` is this row's rule. Its root is `new ToLocal(new ColumnRef(0, LaneType.LONG), 0)`.
  With `M = 1_000_000L`, a change at second `T` and its size `d` in seconds, `SECONDS` is
  `(T + k) * M` for `k` from -7200 to 7199. `EDGES` is `(T - d) * M - 1`, `(T - d) * M`,
  `T * M - 1`, `T * M`, `T * M + 1`, `(T + d) * M - 1` and `(T + d) * M`: the change itself and
  the instants whose local times are the ends of its gap or overlap. `cuts` is `T * M`.
  `expected` is `x + M * zone.getRules().getOffset(Instant.ofEpochSecond(Math.floorDiv(x,
  M))).getTotalSeconds()`.
- `sweep(spec, from, to)` reads the changes `T` with `from <= T < to`, found by
  `ZoneRules.nextTransition`. It groups `spec.zoneIds()` by `DateTimeUtils.getZoneId(id)
  .getRules()` and runs the sets as a parallel stream: one task a set, with buffers of its own.
- A task takes its changes in runs of eight. A run's rows are each change's points, in order.
  `want[i]` is `rule.expected` of row `i`, computed once a run. `STEPS_8` runs the run as one
  batch, `STEPS_2` each two changes as one, and `ONE` each change's points cut at `cuts`.
- For a batch, `least` and `greatest` are its first and last live row. `filler.fill` must
  answer true. The kernel runs inside `VarkaKernelChecks.sanitized`. Its status must be 0 and
  every live row valid and equal to `want`. The first failure, in the order of sets, runs,
  forms (`ONE` first) and rows, is the report's `Mismatch`; `got` is a value, `null` or
  `declined`.
- A task has one `longArgs` array a kernel. Every batch reuses it and nothing clears it, as the
  runner's is. So a fill that leaves a slot unwritten shows as a wrong row.
- With `nullFirst`, each batch gains a first row that is null over storage `Long.MIN_VALUE`.
  The range skips it, the masked body runs, and its output must be null.
- Output validity is whole words, filled before each call with `ValidityFill.BOTH` in turn.

### 4.2 What the sweep reads

The ids are `ZoneId.getAvailableZoneIds()`, the keys of `ZoneId.SHORT_IDS`, and these offsets:
`UTC`, `Z`, `+00:00`, `+01:00`, `-08`, `-08:00`, `+05:45`, `-13:33:33`, `+14:00`, `-12:00`,
`+18:00`, `-18:00`, `UTC+01:30`, `GMT-03:00`. Each resolves by `DateTimeUtils.getZoneId`. `L` is
`Long.MIN_VALUE + OFFSET_BOUND` and `G` is `Long.MAX_VALUE - OFFSET_BOUND`.

| what | operands | forms and bodies |
| :--- | :--- | :--- |
| each change before 2100 (every stored change is asserted to be one) | `BOTH`: 14400 at one second, and the 7 edges | each form, dense body |
| the same changes | `EDGES`, with a null first row | each form, masked body |
| each change of the rule era, from 2100 to 9999 | `T * M - 1`, `T * M`, `T * M + 1` | `STEPS_8`; `ONE` and `STEPS_2` for 2100 to 2199 and 9900 to 9999 |
| before the first change and after the last | the first change less a day and less a microsecond; the last plus a day | each form, one row a batch |
| both ends of Spark's range | -62135596800000000 and 253402300799999999 (`VarkaFuzzTypes.NTZ_VALUES`), and -1, 0, 1 | each form, one row a batch: must be answered |
| the least and the greatest instant a batch may hold | `L`, `L + 1`, `G - 1`, `G` | each form, one row a batch: answered as `java.time`, or declined |
| within 18 hours of an end of a long | `L - 1`, `Long.MIN_VALUE`, `G + 1`, `Long.MAX_VALUE` | each form: the fill must decline |
| a wide span | `L`, the edges of each change, `G`, for a zone with no yearly rule whose changes fit the form | answered as `java.time`, or declined |

A dense window never meets a second change: with its edges it reaches at most `d + 2 h` from
`T`, and the closest two changes are 601200 s apart (`results/facts.txt`). The harness asserts
it for each change, so that newer zone data cannot break it unseen.

### 4.3 The checks of `VarkaZoneSweepSuite`

JUnit 5 under `@VarkaSuite`, in the module's shared JVM: the kernels use the JVM's own long
species alone, so `VarkaSpeciesGuard` holds. `PROD` fills in every test. Each test asserts that
its report read rows, and the suite prints one line (4.5).

| # | `@DisplayName` | what it runs |
| ---: | :--- | :--- |
| 1 | every zone id, short name and sampled offset resolves as Spark resolves it, and every id of a set of rules fills the arguments of the set's first id | the grouping; for each id, `toLocalArgs` with 8 steps over the set's first, middle and last run equals the first id's |
| 2 | ToLocal equals java.time two hours either side of every change before 2100 at one second, and at the change's edges, in each form | `sweep` with `BOTH` |
| 3 | ToLocal equals java.time at the edges of every change before 2100 in the body that reads a null row, in each form | `sweep` with `EDGES` and `nullFirst` |
| 4 | ToLocal equals java.time at the edges of every change from 2100 to 9999 | row 3 of 4.2 |
| 5 | ToLocal equals java.time before the first change, after the last and at both ends of Spark's range, and at the least and the greatest instant a batch may hold it equals java.time or declines | rows 4 to 6 of 4.2 |
| 6 | a batch that holds an instant within 18 hours of an end of a long is declined, in each form | row 7 of 4.2 |
| 7 | a batch that spans one change more than its form's steps is declined, and one that spans as many is answered | the edges of consecutive changes: 1 declines and 0 answers in `ONE`, 3 and 2 in `STEPS_2`, 9 and 8 in `STEPS_8` |
| 8 | a batch of null rows alone answers nulls whatever its storage and its arguments hold | no fill; status 0 and every row null, at lengths 1, 7 and 4097 |
| 9 | a batch that spans from the least instant to the greatest across a change is answered as java.time answers or is declined | row 8 of 4.2 |
| 10 | ToLocal rises strictly inside a segment: a second later is a second later | no oracle: for neighbours of one segment, `out[i + 1] - out[i] == x[i + 1] - x[i]` |
| 11 | every form that covers a batch gives the same rows, and a batch split in two gives the rows of the whole | no oracle: on each run's `EDGES` and a 60 s step, `STEPS_8` whole against its two halves, against `STEPS_2` and against `ONE` |

Tests 10 and 11 are the metamorphic checks: they catch a rule error that the kernel and a
wrong oracle would share. Row 690 adds the there-and-back check beside them.

### 4.4 Edge cases, and what declines

| case | what holds it |
| :--- | :--- |
| null rows | tests 3 and 8: the range skips a null row; its lane runs over `Long.MIN_VALUE` and the validity hides it |
| the ends of a long | tests 5, 6 and 9; checks 10 to 12 of 4.6 |
| a batch with no live row | test 8 |
| two lanes of 64 bits | the suite runs at the JVM's own width; the matrix's `isa=w128` and `isa=arm` and the weekly AVX2 run at 128 bits run it at two lanes. On x86 the masked steps are Java loops there (fact 13): the same rows, slower (prediction 2) |
| AVX2 against AVX-512 | the Build's runners are of both kinds, four or eight lanes; `isa=avx2` pins four |
| a zone with a fixed offset | read as a zone with no change, through `ONE`; test 1 asserts that `fixed()` is `ZoneRules.isFixedOffset()` |

This row adds no decline and no reason text. It tests two declines of row 682's fill (tests 6
and 7). It asks one of row 689: a kernel whose steps have no mask declines, with
`STATUS_BATCH_ARGS`, a batch whose span overflows a long (2.4). Test 9 fails without it.

### 4.5 The sweep's time

The suite prints, for the row to score:

    VarkaZoneSweepSuite: tzdata <version>, <n> ids, <n> sets of rules, <n> changes before 2100,
        <n> changes to 9999, <n> rows, <s> s

The arithmetic, for one core of the laptop, from `results/forms.txt` and `results/facts.txt`:

- At most 65794 x 14400 = 947,433,600 instants in the dense windows, if all 604 ids were read.
- The oracle, once a run and shared by the forms: 947,433,600 x 10.293 ns is 9.75 s.
- The three kernels at four lanes: 0.077 + 0.146 + 0.755 ns a row, 0.93 s. At eight lanes less.
- So about 11 s at most. The sets of rules are at least 349 of the 604 ids; if a set holds the
  ids' average of 109 changes, the dense part is about 6 s.
- No results file prices the rule era: about 16000 changes a zone with yearly rules, each with
  oracle calls that work its year out again (fact 6). Prediction 3 registers it.

The calibration table's nearest row is the calendar sweep. It ran at 0.15 of its one-core
price on the laptop, and CI weighs it at 3.7 times the laptop. So a runner's four cores take
about 0.56 of a one-core price, the family plan's "a half", and the dense part at most about
6 s. The next nearest row, the NTZ field sweeps of VARKA-340, warns the other way: the masked
body and a per-row validity check took them from 16 s to 65 s. So here the masked body reads
the edges only (test 3), and validity is checked a word at a time.

### 4.6 `zone_step.smt2`

Rendered by `VarkaProofFiles.zoneStep()`, logic `QF_NIA`, over `java.smt2`. Its two constants
come from the code. `H` is the 18-hour bound `OFFSET_BOUND`; the renderer requires it to equal
`ZoneOffset.MAX.getTotalSeconds() * 1_000_000L`. `SPAN` is
`VarkaZoneLowering.STEP_NO_MASK_MAX_SPAN`; the renderer requires it to equal `Long.MAX_VALUE`,
since checks 5 and 6 name 2^63. The header restates 2.4. The fragments:

    H    = 64800000000          SPAN = 9223372036854775807
    V    = (declare-const x Int) (declare-const at Int) (declare-const delta Int)
           (assert (and (jlong.in x) (jlong.in at) (jlong.in delta)))
    B    = (declare-const least Int) (declare-const greatest Int)
           (assert (and (jlong.in least) (jlong.in greatest)))
           (assert (and (<= least x) (<= x greatest) (< least at) (<= at greatest)))
    M    = (define-fun m () Int (jlong.shr (jlong.sub (jlong.sub at 1) x) 63))
    STEP = (jlong.and.sign m delta)             SEL = (ite (>= x at) delta 0)
    IN18(v)      = (assert (and (<= (+ jlong.MIN H) v) (<= v (- jlong.MAX H))))
    WITHIN(v, b) = (assert (and (<= (- b) v) (<= v b)))
    O    = (declare-const x Int) (declare-const off Int)
    NOWRAP = (assert (not (= (jlong.add x off) (+ x off))))

Each check is `(echo "<what>: expect <verdict>")`, `(push 1)`, its body, `(check-sat)`,
`(pop 1)`, as `VarkaProofFiles.check` writes it.

| # | what | expect | body |
| ---: | :--- | :--- | :--- |
| 1 | the shift of at - 1 - x by 63 is a sign word, 0 or -1, for every pair of longs | unsat | V M `(assert (not (or (= m 0) (= m (- 1)))))` |
| 2 | where at - 1 - x is a long, the step with no mask is the compare and select | unsat | V M `(assert (jlong.in (- (- at 1) x)))` `(assert (not (= STEP SEL)))` |
| 3 | a batch whose span is at most STEP_NO_MASK_MAX_SPAN: for an instant and a change of it, at - 1 and at - 1 - x are longs | unsat | V B `(assert (<= (- greatest least) SPAN))` `(assert (not (and (jlong.in (- at 1)) (jlong.in (- (- at 1) x)))))` |
| 4 | a batch whose span is at most STEP_NO_MASK_MAX_SPAN: the step with no mask is the compare and select | unsat | V B M `(assert (<= (- greatest least) SPAN))` `(assert (not (= STEP SEL)))` |
| 5 | a span one past the bound, 2^63: at - 1 - x is still a long | unsat | V B `(assert (= (- greatest least) 9223372036854775808))` `(assert (not (jlong.in (- (- at 1) x))))` |
| 6 | a span two past the bound, 2^63 + 1: at - 1 - x leaves a long and the step differs | sat | V B M `(assert (= (- greatest least) 9223372036854775809))` `(assert (not (jlong.in (- (- at 1) x))))` `(assert (not (= STEP SEL)))` |
| 7 | the bounds of the sums alone do not give the step: an instant and a change 18 hours inside a long's ends | sat | V M IN18(x) IN18(at) `(assert (not (= STEP SEL)))` |
| 8 | the witness: x = Long.MIN_VALUE + 18 h, at = 18 h + 1 us, delta = 1 h: the step adds the delta where the select adds 0 | unsat | `(define-fun x () Int (+ jlong.MIN H))` `(define-fun at () Int 64800000001)` `(define-fun delta () Int 3600000000)` M `(assert (not (and (= STEP delta) (= SEL 0))))` |
| 9 | a step that never comes, at = Long.MAX_VALUE and delta = 0, adds 0 for every long | unsat | `(declare-const x Int)` `(assert (jlong.in x))` `(define-fun at () Int jlong.MAX)` `(define-fun delta () Int 0)` M `(assert (not (and (= STEP 0) (= SEL 0))))` |
| 10 | an instant 18 hours inside a long's ends plus an offset within 18 hours does not wrap | unsat | O IN18(x) WITHIN(off, H) NOWRAP |
| 11 | one microsecond nearer the high end the sum wraps | sat | O `(assert (= x (+ (- jlong.MAX H) 1)))` WITHIN(off, H) NOWRAP |
| 12 | one microsecond nearer the low end the sum wraps | sat | O `(assert (= x (- (+ jlong.MIN H) 1)))` WITHIN(off, H) NOWRAP |
| 13 | two wrapping adds are the wrap of the whole sum, so the order of the adds does not matter | unsat | `(declare-const a Int)` `(declare-const b Int)` `(declare-const c Int)` `(assert (and (jlong.in a) (jlong.in b) (jlong.in c)))` `(assert (not (= (jlong.add (jlong.add a b) c) (jlong.wrap (+ a b c)))))` |
| 14 | the masked step: the instant plus the offset before, plus the delta where x >= at, is the instant plus the offset in force | unsat | `(declare-const x Int)` `(declare-const at Int)` `(declare-const before Int)` `(declare-const after Int)` IN18(x) `(assert (jlong.in at))` WITHIN(before, H) WITHIN(after, H) `(define-fun delta () Int (jlong.sub after before))` `(assert (not (= (jlong.add (jlong.add x before) SEL) (+ x (ite (>= x at) after before)))))` |
| 15 | > for >= is the threshold one microsecond later, the planted fault's form | unsat | V `(assert (< at jlong.MAX))` `(assert (not (= (ite (> x at) delta 0) (ite (>= x (+ at 1)) delta 0))))` |
| 16 | with > for >= the step differs at the change itself | sat | V `(assert (= x at))` `(assert (not (= (ite (> x at) delta 0) SEL)))` |
| 17 | with the threshold one microsecond earlier the step differs one microsecond before the change | sat | V `(assert (= x (- at 1)))` `(assert (not (= (ite (>= x (- at 1)) delta 0) SEL)))` |
| 18 | with the delta's sign flipped the sum differs where the step fires | sat | V IN18(x) WITHIN(delta, 129600000000) `(assert (>= x at))` `(assert (not (= (jlong.add x (jlong.neg SEL)) (jlong.add x SEL))))` |

Checks 1 to 6 are the step with no mask and the first span at which it fails, 2^63 + 1. Checks
7 and 8 show that the bounds of `VARKA-657.md` 3.6 are not enough. Check 9 is the padding.
Checks 10 to 14 are the sums that row 682's lowering emits with no check. Checks 15 to 18 tie
the planted faults of 4.7 to the lowering. The equality with `java.time` is not an SMT claim:
it is a property of the JVM's data, and the sweep holds it.

### 4.7 The planted faults

`VarkaZonePlantedFaultSuite` runs the checks of 4.1 over four zones (`Africa/Casablanca`,
`America/Los_Angeles`, `Asia/Kathmandu`, `Pacific/Apia`) with one input broken. Each fault is a
wrapper in the test (`VarkaZoneFaults`) and not an emit option. Under faults 2 and 3 the
kernel's answers are those of the faulty lowering (checks 15 and 18), and a wrapper leaves no
seam that production could reach, which is `VarkaValidityFault`'s reason. Each test asserts
that the report holds a `Mismatch` and reads its fields: no message is parsed, and the expected
instant comes from `ZoneRules`, not from the harness.

| # | fault | where it is planted | the check that must fail, and where |
| ---: | :--- | :--- | :--- |
| 0 | none: the control | - | `sweep` over the four zones finds no mismatch and reads rows in each form |
| 1 | a threshold one microsecond off | the arguments: a `VarkaZoneData` wrapper whose `toLocalArgs` subtracts 1 from each `at[i]` that is not padding | `sweep` with `EDGES` (test 2's edges), at `T * M - 1` of the first zone's first change, in `STEPS_2`. `sweep` with `SECONDS` must pass: only the edges catch it |
| 2 | `>` for `>=` | the arguments: the wrapper adds 1 to each `at[i]`, which is the lowering's `>` by check 15 | `sweep` with `SECONDS`, and with `EDGES`: at `T * M` |
| 3 | a delta's sign | the arguments: the wrapper negates each `delta[i]`, which is the lowering's subtract | `sweep`, at `T * M` |
| 4 | a batch argument not refilled | the fill: a `Filler` that fills a zone's first batch of each form and nothing after; and a wrapper whose `toLocalArgs` leaves the padding unwritten | the first: `sweep`, in the zone's second batch. The second: on one array, a batch over two changes and then one over the next change alone, in `STEPS_2`: every row of the second batch |
| 5 | the driver's rules read in place of the executor's | the provider: each of the four ids answers with the next id's data, as a driver with other rules would | `sweep`, in the first zone |

Row 690 adds two: `min` for `max` in a local threshold (a wrapper) and the kept offset ignored.

### 4.8 The session zone in the Spark-level fuzzer

- `VarkaSparkFuzz.Zones` is `UTC`, `+01:00`, `America/Los_Angeles`, `Australia/Lord_Howe`,
  `Asia/Kathmandu`, `Pacific/Apia`, `Africa/Casablanca`: no zone, a fixed offset, daylight
  saving, a half-hour change, a 45-minute offset, a skipped day, five changes in 365 days.
- `Case` gains `zone: Option[String] = None`. `drawRows` sets it to `order(iteration % 7)`,
  where `order` is `Zones` shuffled by `new Random(seed ^ ZoneSalt)`. The main stream is not
  touched, so a seed's outputs, filter, mode and pivot stay as they were. Its data stays too,
  but for the date cells of a zone composition, below.
- A *zone composition* is one whose zone has a change between 1970 and 2037 and whose
  `(iteration / 7) % 4` is 0: about 5 compositions in 28. In those the drawn dates are lost:
  every date sits within a day of one change, so a date or `TIMESTAMP_NTZ` bug that needs a
  hostile or far-apart date is not reached there, and the compositions of an existing seed
  change on those iterations. Risk 8 holds this; the other 23 in 28 keep their dates. From a
  stream of its own (`seed * 1000003L + iteration +
  ZoneSalt`) it gains one projection among the coverage rows whose switches hold the `ZONE`
  family's, and its date cells (columns `d` and `d2`; the NULLs stay, the pivot row follows)
  move to the day before, the day of and the day after one change of its zone, read from
  `ZoneRules`. Without this a batch's dates span decades, `STEPS_2` covers none, and the fuzzer
  would compare the row path with itself until rows 685 and 686.
- `withSessionZone(zone)(body)` sets `SQLConf.SESSION_LOCAL_TIMEZONE` on both sessions through
  `withBothConf`; `None` runs `body` as it is. It wraps `kindOf` and `optionsOf` in the suite,
  `rectify` and `warmedDisagreement` in `VarkaSparkDifferential`, and the replay's `verdict`.
  The fixture is cached inside it, since a view's zone is fixed when the view is analyzed.
- `Reproducer` gains `zone: Option[String] = None`, rendered as `-- zone: <id>` after the
  `-- options:` line and parsed back. A stored file with no such line replays under the
  session's own zone, as it does today.
- The differential counts the Varka nodes' `numVarkaBatches` and `numFallbackBatchesDeclined`.
  The main test reports for each zone its compositions, its zone compositions, and how many of
  those a kernel served with no declined batch. Over 20 zone compositions or more, a quarter
  must be served or the test fails: a drawn zone that only declines tests nothing.

### 4.9 Zone nodes in the IR fuzzer

- **The draw.** `VarkaIrGrammar.drawZoneShape(rnd)` is the long lane's draw with one more
  production, on `ZONE_FUZZ_SEED = 20261126L`. `LongShapes` takes `int zones`, 0 in every other
  sequence, which then draws no extra coin. With `zones > 0` a column's bound is
  `ZONE_COLUMN_BOUND = 1L << 51`, and two column leaves in three are
  `new ToLocal(column, rnd.nextInt(zones))` with the bound `satAdd(bound, OFFSET_BOUND)`.
  `ZONE_INDICES` is 2.
- **The bound.** 2^51 microseconds is about 71 years either side of 1970. A local time then
  stays under `ConstDivide.EXACT_DIVIDEND_BOUND`, so the grammar divides it, as `hour(ts)`
  does. `boundsOf` has row 682's arm: the operand's bound widened by 18 hours.
- **The operand is a column.** Until row 688 a zone node over a computed operand declines at
  compile time, so the draw wraps columns only. The tree above the node is random.
- **The case.** `VarkaIrFuzzSuite.drawZone(k)` draws the batch by `VarkaLaneFuzz.drawCase` with
  a `LaneSource` of that bound. Then, from `ownStream(zoneSeed, k, "zone")`, it draws one zone
  id an index from `VarkaZoneKernels.FUZZ_ZONES` (the seven of 4.8, which `VarkaSparkFuzz.Zones`
  reads), and a range for each column. The range's centre is a change of one of the zones
  inside the bound, or any instant inside the bound for a zone with none; its half width is
  `1L << nextInt(51)`. The range is then clamped to plus and minus `ZONE_COLUMN_BOUND`, as 4.10
  clamps its range, so that no cell leaves the bound the grammar drew the tree under. One cell in
  four is the centre, less 1, or plus 1; the others are uniform in
  the range. The options are the general draw's and `zoneForm`.
- **The fill.** For each program of `VarkaBatchArgs.layout(roots, lits.length, options)`: its
  operand's column, the least and greatest of that column's rows that are not null, then
  `PROD`'s fill into an array that holds the literals first. A column with no live row is not
  filled. A fill that answers false makes the case *declined*: counted, and not compared.
- **The comparison** is the one `VarkaEmitterZoneSuite` makes: the kernel's rows against
  `VarkaReference`, whose `ToLocal` arm is `java.time`. A failure is not shrunk, as the mixed
  lane's is not: the shrinker's case holds no zone ids. It names `-Dvarka.fuzz.seed` and
  `-Dvarka.fuzz.only`.
- The test asserts that over half of its draws hold a `ToLocal` and over half are answered. A
  second test asserts that the draw reaches a conversion under arithmetic, a division, a
  comparison and a choice, at each zone index. The two reach tests' lists keep `ToLocal` out of
  the committed corpora and name this sequence.
- `VarkaFuzzTypes`' entry of `TIMESTAMP` takes a typed leaf: the form `ToLocal(col:long)`, the
  seed and the draw above, cells `zone draw`. `sql/varka/fuzz/types.tsv` is regenerated.

### 4.10 `VarkaZoneArgsPropertySuite`

JUnit 5. Case `k` of seed `s` draws from `new Random(s * 1000003L + k)`: a zone id from all of
4.2's; a form; a range whose centre is one of the zone's changes to 9999 (three times in four)
or any instant between `L` and `G`, and whose half width is `1L << nextInt(62)`, clamped to
`L` and `G`; a length from `VarkaEmitterFixtures.LENGTHS`; rows that are, by quarters, a change
inside the range, a change plus or minus one microsecond, a change plus or minus up to two
hours, and uniform; one row in ten null.

- Test 1, "a random zone, range and instants: the fill answers exactly when the form covers the
  range, and its arguments give java.time's local time on every row". The changes in
  `(least, greatest]` are counted by `ZoneRules.nextTransition`, and the count stops at the
  form's steps plus one, since a wide range in the rule era holds hundreds of thousands. A zone
  with no change takes any range inside Spark's range. Outside Spark's range a
  decline is accepted too. A scalar loop adds `base` and each `delta[i]` where `x >= at[i]`.
- Test 2, "the same zones, ranges and instants through the kernel of the drawn form".
- Test 3, "the draws reach every form, a range with no change, a range that ends on a change,
  a range in the rule era and a range that declines".

Defaults: seed 20261126 and 20000 cases. A failure prints
`-Dvarka.zone.seed=<s> -Dvarka.zone.only=<k>`. The nightly workflow gains one step that runs
the suite at 2,000,000 cases with the date's seed.

## 5. The parts

Each sheet follows `m8/BUILD_SHEETS.md` in full: the worktree, the hooks, the lock, the
linters, one commit, no push. Each shell also exports
`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`. The row's line is in
`sql/varka/plans/m9/PLAN.md`. This file is `m9/VARKA-684.md`; the lead merges it in a plan-only
pull request before part 1, so no part counts its lines; that pull request sets the row to
`**Planned**`. The size check counts no SMT-LIB file.
`V`, `T`, `TS` and `S` are section 3's.

### 5.1 Part 1: the sweep and the metamorphic checks

Title `[VARKA-684] Sweep ToLocal against java.time over every zone of the JVM`; branch
`varka-684a`. About 740 counted lines.

**B0, the premises.** Each must print what its comment says, or the builder stops.

    grep -c 'record ToLocal(' $V/VarkaVectorIR.java                        # 1
    grep -n 'static List<Program> layout(' $V/VarkaBatchArgs.java          # one line
    grep -n 'fill(' $V/VarkaBatchArgs.java                                 # the program's fill
    grep -n 'static final long' $V/VarkaBatchArgs.java                     # OFFSET_BOUND, 18 hours
    grep -n 'void toLocalArgs(long least, long greatest, int steps' $V/VarkaZoneData.java   # one line
    grep -n 'enum ZoneForm' -A1 $V/VarkaEmitOptions.java $V/VarkaEmitOption.java   # ONE, STEPS_2, STEPS_8
    grep -n 'withZoneForm' $V/VarkaEmitOptions.java                        # one line
    git grep -l 'implements VarkaZoneData' -- sql/catalyst/src/main        # VarkaChronoZone's file
    git grep -n 'VarkaZoneData.Provider' -- sql/catalyst/src/main sql/core/src/main   # PROD
    ls $T/VarkaJavaTimeZoneData.java $T/VarkaEmitterZoneSuite.java         # both exist

If the fill's signature is not section 3's, adapt the one call in `production` and say so in
the Outcome. If `VarkaBatchArgs` has no fill, or the 18-hour bound is no constant, stop.

**Steps.**

1. Add `$T/VarkaZoneKernels.java` as 4.1 has it: the records and interfaces, `Kernel.of`,
   `production`, `TO_LOCAL`, `sweep`, a public run of one batch on a given array (fault 4 of
   4.7 needs it), `PRODUCTION = production(PROD)`, `allIds()` (4.2) and `FUZZ_ZONES` (4.8).
2. Add `$T/VarkaZoneSweepSuite.java`: the eleven tests of 4.3 under the names written there,
   and the printed line of 4.5. A test fails by `Assertions.fail`, never `throw new ...Error`.
3. Plant by hand, and revert by `git checkout -- <file>`: add 1 in `TO_LOCAL.expected`. Command
   2 must then fail tests 2 to 5 and test 9, which compares answered rows with the oracle, and
   pass tests 1, 6, 8, 10 and 11. Test 7 compares the fill's verdict only, so it passes too.
4. Edit row 684 of `m9/PLAN.md`: it opens with `**Started**` and says parts 2 and 3 are left.

**Tests.** The first three rows of section 6.

**Commands**, in order.

    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/Test/compile'
        # [success]
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaZoneSweepSuite'
        # VarkaZoneSweepSuite: tzdata ..., <n> ids, <n> sets of rules (at least 349), ...
        # [info] Passed: Total 11, Failed 0, Errors 0, Passed 11
    flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'catalyst/testOnly *VarkaZoneSweepSuite'
        # [info] Passed: Total 11, Failed 0, Errors 0, Passed 11
    flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("catalyst") / Test / javaOptions ++= Seq("-XX:UseAVX=2", "-XX:MaxVectorSize=16")' 'catalyst/testOnly *VarkaZoneSweepSuite'
        # [info] Passed: Total 11, Failed 0, Errors 0, Passed 11   (two masked lanes: note the time)
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json
        # exit 0: a test-only change moves no generated file

**Done when** the four runs print as above, the Outcome holds the printed line and the three
times, and `python3 dev/varka_push_check.py` exits 0 with a count under 900.

**Not here:** no production file, no proof, no fuzzer, no rule of the way back. Stop if test 2
takes over 30 s on the laptop: the lead decides the fallback of risk 3.

### 5.2 Part 2: the proof file and the planted faults

Title `[VARKA-684] Prove the zone step, and show that each zone check can fail`; branch
`varka-684b`. About 520 counted lines.

**B0, the premises.**

    grep -c 'class VarkaZoneKernels' $T/VarkaZoneKernels.java              # 1: part 1 has merged
    ls sql/varka/proofs/zone_step.smt2                                     # no such file
    grep -c 'STEP_NO_MASK_MAX_SPAN\|zoneStepMask' $V/VarkaZoneLowering.java $V/VarkaEmitOption.java   # 0 and 0
    grep -n 'static final long' $V/VarkaBatchArgs.java                     # OFFSET_BOUND, 18 hours
    grep -n 'define-fun jlong.and.sign' sql/varka/proofs/java.smt2         # one line
    grep -n 'files.put("int_round.smt2"' $T/VarkaProofFiles.java           # one line

If `zone_step.smt2` or `zoneStepMask` is there, row 689 merged first and carried the proof:
stop and report.

**Steps.**

1. `$V/VarkaZoneLowering.java`: add, after the class's last constant,
   `public static final long STEP_NO_MASK_MAX_SPAN = Long.MAX_VALUE;`. Its comment says what it
   bounds (2.4), that `zone_step.smt2` proves it, and that the step with no mask reads it.
   Nothing else in production changes.
2. `$T/VarkaProofFiles.java`: add `zoneStep()` after `intRound()`'s section: the header,
   `rendered("QF_NIA")` and the 18 checks of 4.6 in order, under the texts written there. It
   throws `IllegalStateException` where a constant is not what 4.6 requires. Add
   `files.put("zone_step.smt2", zoneStep());` after the `int_round.smt2` line of `render()`.
3. Render the file (command 1), and add its row to the table of `sql/varka/proofs/README.md`.
4. Add `$T/VarkaZoneFaults.java`: the wrappers of 4.7, a `VarkaZoneData` that delegates and
   then edits the run it wrote, a `Filler` and a `Provider`.
5. Add `$T/VarkaZonePlantedFaultSuite.java`: the six tests of 4.7, named "with no fault the
   sweep of the four zones finds no mismatch, and reads rows in each form"; "a threshold one
   microsecond early fails the edges one microsecond before a change, and passes the one-second
   windows without them"; "a threshold one microsecond late, which is > for >=, fails at the
   change itself"; "a delta with its sign flipped fails from the change on"; "arguments not
   refilled for a later batch fail that batch, and a fill that leaves the padding unwritten
   fails the batch after a fuller one"; "another zone's rules, as a driver's would be, fail the
   sweep".

**Tests.** The rows of the proof and of the planted suite in section 6.

**Commands**, in order.

    VARKA_PROOFS_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
    git status --short sql/varka/proofs
        # ?? sql/varka/proofs/zone_step.smt2   and   M sql/varka/proofs/README.md, nothing else
    grep -c ': expect unsat")' sql/varka/proofs/zone_step.smt2             # 11
    grep -c ': expect sat")' sql/varka/proofs/zone_step.smt2               # 7
    dev/varka_prove.sh --solver both zone_step.smt2
        # z3    zone_step.smt2    18 checks    <t> s  holds
        # cvc5  zone_step.smt2    18 checks    <t> s  holds
        # varka_prove: every proof holds under z3 cvc5
    dev/varka_prove.sh --lint
        # varka_prove: every proof holds under z3 cvc5
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaProofFilesSuite *VarkaZonePlantedFaultSuite *VarkaZoneSweepSuite'
        # [info] Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0
        # [info] Passed: Total 18, Failed 0, Errors 0, Passed 18
    git diff --exit-code -- sql/varka/emitted_bytes.json                   # exit 0

**Done when** the commands print as above and the Outcome holds both solvers' times. A check
that a solver answers `unknown`, or one that takes over 2 s, is a stop: the lead reworks the
form (the calibration row of VARKA-245).

**Not here:** no emit option, no change to what `VarkaZoneLowering` emits, no decline (row
689's), no fuzzer.

### 5.3 Part 3: the fuzzers and the property suite

Title `[VARKA-684] Draw the session zone in both fuzzers, and the zone arguments by seed`;
branch `varka-684c`. About 650 counted lines.

**B0, the premises.**

    grep -c 'class VarkaZoneKernels' $T/VarkaZoneKernels.java              # 1
    grep -c '"executable" : "hour(ts)"' sql/varka/coverage.json            # 1: row 657's row
    grep -c 'spark.sql.codegen.varka.family.zone.enabled' sql/varka/coverage.json   # 4 or more
    grep -n ' AS ts' $S/VarkaSparkFuzz.scala                               # ts, derived from d
    grep -n 'case TIMESTAMP ->' -A3 $T/VarkaFuzzTypes.java                 # row 656's entry
    grep -n '"ToLocal"' $TS/VarkaIrFuzzSuite.scala $TS/VarkaWithChildrenSuite.scala   # the lists
    grep -n 'ToLocal' $T/VarkaIrGrammar.java $T/VarkaReference.java        # the arms, and no draw
    grep -n 'VarkaKernelChecks\.' $T/VarkaEmitterZoneSuite.java            # its kernel check
    grep -c '20261126L' $T/VarkaIrGrammar.java                             # 0

If `ts` is not derived from `d`, or the zone family's switch has another key, stop. If the seed
is taken, use the build day's date.

**Steps.**

1. `$S/VarkaSharedSessions.scala`: `withSessionZone`, beside `withAnsi`.
2. `$S/VarkaSparkFuzz.scala`: `Zones`, `ZoneSalt`, `Case.zone`, the draw in `drawRows`, the
   zone composition of 4.8, `CoverageRow`'s zone flag read from `switches`, and
   `Reproducer.zone` with `render` and `parse`.
3. `$S/VarkaSparkDifferential.scala`: the two batch counters, in `Counters` too; `rectify` and
   `warmedDisagreement` inside `withSessionZone(c.zone)`.
4. `$S/VarkaSparkFuzzSuite.scala`: `kindOf` and `optionsOf` inside `withSessionZone`; the zone
   in both `Reproducer(...)` calls; the census and its guard in the main test; the fixture test
   under each case's zone; and three tests: "a reproducer with a zone parses back to what was
   rendered, and one with no zone line has none"; "every seven compositions in a row draw every
   zone, and the draw is the same on every call"; "a zone composition holds an output that
   reads the zone and dates within a day of a change of its zone".
5. `$S/VarkaSparkReproducerSuite.scala`: `verdict` inside `withSessionZone(r.zone)`.
6. `$T/VarkaIrGrammar.java`: `ZONE_FUZZ_SEED`, `ZONE_COLUMN_BOUND`, `ZONE_INDICES`,
   `drawZoneShape`, and `zones` through `drawLaneShape` and `LongShapes` (4.9).
7. `$TS/VarkaIrFuzzSuite.scala`: `zoneSeed`, `drawZone`, the run of 4.9, and two tests: "random
   long-lane IR trees with zone conversions match the reference evaluator (seed ..., ...
   iterations)" and "the zone draw reaches a conversion under arithmetic, a division, a
   comparison and a choice at each zone index, and the committed draws hold none". Update the
   two lists' comments. In `$TS/VarkaWithChildrenSuite.scala`, add the zone draw to the loop
   and take `"ToLocal"` out of `undrawn`.
8. `$T/VarkaFuzzTypes.java`: the typed leaf of 4.9. Regenerate `types.tsv` (command 2).
9. Add `$T/VarkaZoneArgsPropertySuite.java` (4.10).
10. `.github/workflows/varka-fuzz.yml`: after the step "Run the coverage composition fuzzer",
    a step "Run the zone property suite", written as that step is, with
    `-Dvarka.zone.cases=2000000 -Dvarka.zone.seed="$seed" -Dvarka.test.watchdog.minutes=45`,
    the main class `...codegen.varka.VarkaSuiteRunner`, and `-s` the suite's class.
11. Row 684 of `m9/PLAN.md` opens with `**Done**`.

**Tests.** The last three rows of section 6.

**Commands**, in order.

    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaZoneArgsPropertySuite'
        # VarkaZoneArgsPropertySuite: seed 20261126, 20000 cases, <n> declined, <s> s
        # [info] Passed: Total 3, Failed 0, Errors 0, Passed 3
    VARKA_FUZZ_TYPES_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaFuzzTypesSuite'
    git diff --stat -- sql/varka/fuzz/types.tsv
        # 1 file changed, 1 insertion(+), 1 deletion(-): the TIMESTAMP line
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaIrFuzzSuite *VarkaWithChildrenSuite *VarkaFuzzTypesSuite *VarkaEmittedBytesSuite'
        # every test passes; the zone test's line names its seed
    git diff --exit-code -- sql/varka/emitted_bytes.json
        # exit 0: no committed corpus moved
    flock "$VARKA_HEAVY_LOCK" build/sbt -Phive 'sql/testOnly *VarkaSparkFuzzSuite *VarkaSparkReproducerSuite'
        # [info] Tests: succeeded <n>, failed 0, canceled 0, ignored 0, pending 0
        # an info line a zone: its compositions, its zone compositions, those served

**Done when** the commands print as above, and the Outcome holds each zone's census line, the
share of zone draws answered, and each suite's time before and after.

**Not here:** no zone in `VarkaFuzzCase` or the shrinker, no zone sequence in the bytes oracle
or the compiled test, no new coverage row, no change to the fixture's columns.

**After the commit, by the lead**, since both start several JVMs: `dev/varka_gen_coverage.sh`
in an idle window, with the lines of `sql/varka/coverage/emitter.md` for
`VarkaZoneLowering.java`, `VarkaBatchArgs.java` and the zone data's file quoted in the pull
request and each missed branch named; and `dev/varka_pit.sh --classes
VarkaBatchArgs,VarkaZoneLowering --suites VarkaIrFuzzSuite`, with each survivor answered in
`sql/varka/mutation/notes.tsv` (section 6).

## 6. Tests, proofs, fuzzing and coverage

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaZoneSweepSuite`, tests 1 to 5 (catalyst, JUnit 5) | the emitted `ToLocal` kernel in each form, with the production zone data's arguments, against `java.time` at every change of every set of rules, to the year 9999 | zone data that differs from `java.time` in one zone |
| the same, tests 6 to 9 | the fill's two declines; null rows; a span from one end of a long to the other | a batch answered where it must decline; a step with no mask that answers past its bound |
| the same, tests 10 and 11 | the metamorphic checks | a rule error that the kernel and a wrong oracle share |
| `zone_step.smt2` (18 checks, Z3 and cvc5) | 4.6 | a step that is right on every row a test reads and wrong on a long that none reads |
| `VarkaZonePlantedFaultSuite` (catalyst, JUnit 5) | five faults and a control, each against the real check | a check that passes for another reason |
| `VarkaZoneArgsPropertySuite` (catalyst, JUnit 5) | random zones, ranges and instants by seed; the fill's verdict and its arguments | a range the sweep's windows do not hold: one that ends on a change, a wide one, a one-row one |
| `VarkaIrFuzzSuite`, the zone sequence (catalyst, ScalaTest) | `ToLocal` over a column under random long-lane trees, with drawn zones, ranges and forms, against `VarkaReference` | a conversion that is wrong under a parent no written test has |
| `VarkaSparkFuzzSuite` and `VarkaSparkReproducerSuite` (sql/core, ScalaTest) | the session zone as a drawn setting, printed by a reproducer; zone compositions on rows that straddle a change | a zone-dependent answer in a shape no suite lists; a kernel or a cache entry that kept another session's zone |

**Where each runs.** The Build: every suite at its default size, at the runner's width. The
option matrix: each configuration and the three platforms. The nightly workflow
(`varka-fuzz.yml`): the IR fuzzer at 40000 iterations a sequence with the date's seeds, which
the zone sequence takes as the others do, and the property suite's new step. The weekly sweeps
workflow: the suites under AVX2 at 128 bits. The laptop's `dev/varka_nightly.sh`: the Spark
fuzzer at 50000 compositions, and every proof under both solvers.

**The proof file** runs under Z3 in the linters' job of every Build, and under both solvers in
part 2's commands and in the laptop's nightly. `VarkaProofFilesSuite` holds it to its two
constants.

**Coverage.** This row adds no production code but one constant. After part 3 the JaCoCo
report shows every branch of `VarkaZoneLowering.java`, `VarkaBatchArgs.java` and the zone
data's file entered, or the pull request names each missed branch with its reason.

**Mutation.** `dev/varka_pit.sh` reaches ScalaTest suites only (fact 12), and three of this
row's four suites over the zone classes are JUnit. So the run's killing tests are the IR
fuzzer's zone sequence, at the 30 iterations a sequence that the tool gives the fuzzer. A
mutant it leaves is answered in `notes.tsv`: by the JUnit test that kills it, shown by planting
the mutant by hand, or as equivalent with its reason. Whether the tool should reach JUnit
suites is the owner's to decide; this row does not build it.

## 7. Measurement

This row adds no benchmark section and no A/B arm: it changes no emitted byte. What it measures
is test time and proof time. The calibration table was read on 26 November 2026
(`python3 dev/varka_calibration.py`); 4.5 has the ratios taken from it.

**Predictions, registered before the run.**

1. *The sweep's time in the Build.* `VarkaZoneSweepSuite` takes between 3 and 9 s on a runner's
   four cores, at four or eight lanes. So the family plan's prediction 9, under 10 s for
   `ToLocal`, holds: yes. I agree with it on one condition, which the design takes: ids with
   equal rules are read once, and the oracle is computed once a run and not once a form.
   Without both, the arithmetic of 4.5 passes 10 s. Scored from the Build's JUnit report.
2. *Two masked lanes on x86.* Under `isa=w128` and in the weekly AVX2 run at 128 bits the suite
   takes under 60 s on a runner while the steps are masked: `results/forms.txt` prices them at
   12.070 and 62.542 ns a row, about 71 s of one core. After row 689: under 10 s.
3. *The rule era.* Test 4 alone takes under 3 s on a runner.
4. *The proof file.* `zone_step.smt2`'s 18 checks take under 1 s under Z3 and under 1 s under
   cvc5 on the laptop, and add under 2 s to the linters' "Varka proofs" step. The nearest
   calibration row is the two bitmap claims, six linear checks: 0.03 s under Z3 and 0.07 s
   under cvc5. No check needs the `ALL` logic: yes.
5. *The planted suite* takes under 5 s in the Build, and each of its five faults fails its
   check at the first attempt: yes.
6. *The property suite* takes under 5 s at 20000 cases in the Build, and under 2 minutes at
   2,000,000 cases in the nightly, with the count of changes stopped at the form's steps plus
   one: at most 9 calls of `nextTransition` a case.
7. *The fuzzers.* The zone sequence adds under 15 s to `VarkaIrFuzzSuite` at 300 iterations.
   The zone adds nothing measurable to `VarkaSparkFuzzSuite`: under 5% by the minimums of three
   runs, as the calibration rows of VARKA-298 and VARKA-381 found for their additions.
8. *The counts.* The sets of rules by `ZoneRules.equals` are at least 349 and under 400.
9. *Served zone compositions.* Over half of the zone compositions of a 200-composition run are
   served by a kernel with no declined batch.
10. *Size.* Three pull requests, none over 900 counted lines.

## 8. Who builds it

All three parts are built at full strength. The row is foundation and not fan-out: no part is
a sibling of a merged kernel, and `m9/PLAN.md` 2.7's generated plan has no template for it.

- **Part 1:** G2's claim rests on this harness, and row 690 extends it. Its sheet reads six
  names of rows that were not built when it was written; a failed premise needs judgment.
- **Part 2:** a proof's time is priced from its spiked form and not from its claim (the
  calibration row of VARKA-245), and a check that comes back `unknown` needs a new form.
- **Part 3:** it changes two fuzzers whose seeds' draws must not move, and the grammar's
  registries. If the lead wants a cheaper model on this row, the property suite alone is the
  candidate, as a part of its own after part 1: one new file and one oracle command.

## 9. Risks

1. **The plan was written before the rows it builds on.** B0 holds each name, a premise that
   fails stops the part, and section 3 lists the six names that no merged plan holds yet.
2. **The oracle shares a bug with the zone data.** Both read `ZoneRules`. The oracle is
   `getOffset` on every instant, the call Spark's row engine makes. Tests 10 and 11 use no
   oracle, and the property suite counts changes on its own.
3. **The sweep's time.** Prediction 1. If the Build's time is over 10 s, the dense test's
   one-second step reads `-Dvarka.sweep` and takes a row in `sql/varka/ci/sweeps.tsv` for the
   weekly sweeps, and the Build keeps a 60 s step and the edges. The lead decides it.
4. **Zone data differs between JDK builds.** The suite pins no count, no date and no id but
   the fuzzers' seven. It prints the tzdata version and asserts what it leans on (4.2).
5. **A planted fault passes for another reason.** Each fault runs beside the control, reads
   the mismatch as fields, and takes its expected instant from `ZoneRules`.
6. **A step with no mask answers past its bound.** Check 6 and test 9; row 689 needs part 2.
7. **The Spark fuzzer's zone only declines.** The zone compositions, and the census's guard.
8. **A draw moves a committed corpus.** The zone has a seed, a salt and streams of its own. A zone
   composition replaces its dates (4.8): 5 compositions in 28 lose the drawn dates, by design, and
   the plan says what they no longer reach.
   `emitted_bytes.json` must not move in any part, and a test holds the main stream.
9. **The mutation run cannot use the sweep.** Fact 12. Section 6 says what the run still shows.
10. **A second species in the shared JVM.** The sweep emits at the JVM's own lanes only.
11. **Row 690 needs more of `Rule`.** `ToInstantKept` has two operands, so `points` then
    returns rows. That change is row 690's; the harness keeps one input until then.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md`:

- "A new catalyst suite whose helpers are Java is a JUnit 5 class under `@VarkaSuite`", each
  `@Test` with its sentence as a `@DisplayName`.
- "A test starts from `VarkaMatrix.base`, not `VarkaEmitOptions.DEFAULTS`, so the matrix
  reaches its kernels" (`VarkaMatrixRuns.BASE` in Java).
- "A new bounded lowering comes with its proof file", "rendered from the code's own constants
  by `VarkaProofFiles`".
- "The suites run with the memory sanitizer on": a kernel call goes through
  `VarkaKernelChecks.sanitized`.
- "A change that adds test time says so", read against the committed budget.
- "A comment describes the code as it is"; no `TODO` and no `FIXME`.
- "Never kill by name pattern"; "Never `git stash`"; write only in your own worktree.
- "A pull request stays under 1000 changed lines"; review your own diff before it opens.

Do not:

- edit `zone_step.smt2`, `types.tsv` or any generated file by hand: render or regenerate it;
- add a draw to a committed sequence, or a coin to the Spark fuzzer's main stream;
- restate the fill's logic in the harness: call row 682's;
- pin a count of zones, sets or changes, or the date of a change, in a test;
- add an emit option, a conf or a decline: this row has none;
- run a benchmark, or quote a time that no command of the sheet printed.

## 11. Outcome

*To be written when the row closes.*
