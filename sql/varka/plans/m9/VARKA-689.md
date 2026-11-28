# VARKA-689: Zone steps at two lanes

*Planned on 27 November 2026, and revised on 28 November 2026 after its two reviews. This
plan stands alone: a builder needs this file and the repository's `AGENTS.md` files, nothing
else. Not started: milestone 9 waits for the owner's word (`m9/PLAN.md` decision 18), and
nothing of it starts before the tag `varka-m8`. The code facts below were checked on master
`9e7b1612402`. The rows this one builds on (682, 683, 656, 678, 657, 684, 685, 688, 690 and
655) were not built on that day, so every build sheet starts with greps of their names. Row
697 of milestone 8 (pull request #1798, open on that day) adds a component to
`VarkaSessionEmit`; part a is written for master with it and without it.*

## 1. What this row is

**The row's text** (`sql/varka/plans/m9/PLAN.md`, section 3). "Zone steps at two lanes.
`VARKA-657.md` 3.8: each step as a subtract, a shift and an AND, for `ToLocal` and for the
three rules of a local time to an instant, whose records and masked steps are row 690's
(`VARKA-690.md`, appendix A, holds the design notes), an option arm whose default follows the
host; a recorded refusal where a form is slower than the row path; and a decline of a batch
whose span does not fit a long, where the step with no mask is not exact. **Done when** at 128
bits each zoned kernel is faster than the row path or declines by a recorded refusal, and a
batch of a wide span declines."

**The owner's decision it rests on** (`m9/PLAN.md` section 1, decision 15). "At two 64-bit
lanes on x86 a section that would run slower than the row path declines there, as G7 has it.
The catalogue's lowering without masks for every long-lane kernel (its item 101) is not this
milestone's; row 689 builds that form for the zone steps alone: `ToLocal`'s, and those of the
three rules of a local time to an instant."

**What it gives the rows after it.**

- Row 691 (the zone kernels measured): the option `zoneStepMask` and its session setting, so
  that its A/B arm "masked against no mask at 128 bits" has both arms; the record of 4.9 and
  its tool, which row 691 runs again on its own reading; the first 128-bit reading of the
  seven first kernels with every conversion fused.
- Row 680 (never slower than Spark): the zone conversions that lose at 128 bits decline by
  name there, so their coverage rows time the row path on both arms.
- Rows 668 to 671 and 673 to 675 (every later zoned kernel): their conversions go through
  `VarkaZoneCompiler.toLocal`, `toInstant` and `toInstantKept`, so each inherits the verdict
  of its conversion with no code of its own.
- Row 685 (the ladder): its cap at two steps on a two-lane host lifts where the steps have no
  mask (4.2).

**What must be merged before it starts.** Row 657's parts A to D (and so rows 682, 683, 656,
678 and 654); row 684's part 2 (the file `zone_step.smt2` and the constant
`STEP_NO_MASK_MAX_SPAN`); row 690's part b for part a, its part c for part b, and its parts d,
e and f for part c; row 655's fields, casts and arithmetic classes for parts c and d. Section
5 has the order.

**The goals it serves** (`m9/PLAN.md` 1.1), each quoted, with the item of 1.2 that it moves
and how a reader checks it.

| goal, as written | item of 1.2, as written | what this row moves, and the check |
| :--- | :--- | :--- |
| "**G7. Speed where a time zone is read.** ... No fused shape is slower than the row path on a measured host; a shape that would be declines there." | "no section is slower than the row path at 256 or at 128 bits" | the 128-bit half, for the seven first kernels of rows 657 and 690. Check: `python3 dev/varka_zone_two_lane.py --check` prints `the record holds`, and in each class's committed 128-bit file every section of a kept form reads at least 0.95 of the row path's rate and every section of a refused form times the row path on both arms |
| "**G2. Every time zone, proven.**" | "the every-zone sweep passes in CI under each rule" | nothing new is counted. The sweep and the kernel suites run under the matrix configuration `zoneStepMask=false`, so the steps with no mask are held to `java.time` by the same tests. Check: the matrix job of that configuration is green |
| G1, G3 | items 1 and 3 | no census cell moves at the emit defaults: `python3 dev/varka_census.py --summary` is the same before and after each part. A refused conversion stays `fused` in the census, which is read at the defaults and not on a two-lane host |
| G4, G5, G6 | items 4, 8 and 9 | only through later rows: a nanosecond kernel takes these steps on its microsecond word (rows 673 to 675); every part here is built at full strength (section 8); the post quotes row 691's measurement |

## 2. What an agent must know

### 2.1 The idea

A `TIMESTAMP` is a long of microseconds since the epoch in UTC, an *instant*. A zone's offset
is a step function of the instant. Row 682's IR node `ToLocal` adds the offset: a kernel adds a
first offset, `base`, to every row, and for each change of offset inside the batch's range it
adds a difference, `delta`, to the rows at or past the change, `at`. That is a *step*. Row
690's nodes `ToInstant` and `ToInstantKept` go back from a local time to an instant by three
rules, and their steps test one or two thresholds a window (2.5).

The steps of rows 682 and 690 are *masked*: a compare makes a vector mask, and a masked add
uses it. On x86, HotSpot's C2 compiles no vector mask at two 64-bit lanes, whatever the
instruction set. Every masked call then runs as a Java loop that allocates. So at 128 bits (an
AMD EPYC 7763 under `-XX:MaxVectorSize=16`, or any SSE-only host) a masked step costs about a
hundred times what it costs at four lanes (2.6).

This row writes each step with no mask. For a threshold `a` and a value `x`, the *sign word*
`((a - 1 - x) >> 63)`, with an arithmetic shift, is -1 in a lane where `x >= a` and 0
elsewhere. An AND of the sign word with `delta` is the difference to add. It is a subtract, a
shift and an AND, three operations that C2 compiles at every width.

The sign word is right only where `a - 1 - x` does not wrap. It does not wrap while the
batch's *span*, its greatest value less its least, fits a long. So a batch of a wider span is
declined, and Spark's row path answers it.

No probe has timed a whole SQL kernel at two lanes with these steps. Kernels on two 64-bit
lanes lose to the row path for other reasons too (2.6). So the row ends in a measurement, and
a rule with no judgement in it turns the reading of each conversion into *kept* (it fuses at
two lanes) or *refused* (it declines by name there). The outcome is committed as a record,
and a check fails when the record and a reading disagree.

### 2.2 Terms

- **Instant**, **local time**, **change**, **step**: 2.1. **Window**: for a change at `T`
  between the offsets `before` and `after`, the local times `[lo, hi)` with `lo = T +
  min(before, after)` and `hi = T + max(before, after)`. In a *gap* (the offset grows) they
  do not exist; in an *overlap* (it shrinks) they exist twice.
- **Zone node**: an IR record under the sealed interface `Zoned`: `ToLocal`, `ToInstant`,
  `ToInstantKept`. **Operand**: the value whose range in a batch decides the node's
  arguments. **Zoned kernel**: a kernel whose IR holds a zone node.
- **Batch argument**: an entry of the kernel's `long[] longArgs`, after the literals, that
  the runner writes before each call. **Program**: one zone node's run of batch arguments.
- **Form** (of the option `zoneForm`): `ONE` has no step; `STEPS_2` and `STEPS_8` have up to
  two and eight; `k` is the count. **Padding**: a step that never fires, each threshold
  `Long.MAX_VALUE` and each difference 0.
- **Masked step**, **step with no mask**, **sign word**, **span**: 2.1.
- **Two-lane host**: an x86 JVM whose preferred long species has two lanes or fewer:
  `VarkaKernelPlan.twoLaneMasksFallBack(host)` is true. An aarch64 JVM at two lanes is not
  one: its C2 compiles masks there.
- **The two-lane refusal**: the session setting
  `spark.sql.codegen.varka.declineTwoLaneConditions`. Unset, it is true on a two-lane host
  and false elsewhere. `DeclineSink.declinesTwoLaneChecks()` is its value in a compile.
- **Decline**: at run time the runner gives one batch to the row path
  (`VarkaKernelRunner.STATUS_BATCH_ARGS`, metric `batch-args`). **Declines by name**: the
  compiler builds no kernel for an entry and records a reason text, which `EXPLAIN` shows.
- **Conversion** (in the record): one of five things the zone compiler builds, 4.9:
  `TO_LOCAL`, `EARLIER`, `GAP_END`, `KEPT`, `FIXED`.
- **Reading**: two runs of a benchmark class at one width on one CPU model. **Section**: one
  timed table of a results file, with the arms `spark row path (varka off)` and `varka on`.
- **`M`**: `VarkaBatchArgs.MAX_OFFSET_MICROS`, 64,800,000,000 microseconds: 18 hours.
- **The lead**: the agent that coordinates the row; it pushes, opens pull requests,
  dispatches workflows and merges. **The builder**: the agent with this plan; it stops at a
  local commit.

### 2.3 The names this row reads

From the merged plans of the rows named; each sheet's B0 greps them.

| name | from | meaning |
| :--- | :--- | :--- |
| `sealed interface Zoned` with `int zone()` and `VarkaVectorIR operand()`; `record ToLocal(VarkaVectorIR instant, int zone, int hash)` | 682 | the family and its first record |
| `enum Resolve { EARLIER, GAP_END }`; `record ToInstant(VarkaVectorIR local, int zone, Resolve rule, int hash)`; `record ToInstantKept(VarkaVectorIR local, VarkaVectorIR kept, int zone, int hash)` | 690, parts a and b | a local time to an instant by a rule; the same with the offset `kept` preferred in an overlap |
| `VarkaEmitOptions.zoneForm`, `enum ZoneForm` with `int steps()`, `withZoneForm` | 682; 685 adds `LADDER`, 686 `TABLE` and `TABLE_SCALAR` | the form, one for a kernel |
| `VarkaZoneLowering.emitToLocal(cb, n, dense, analysis, s, computed)`, `emitToInstant`, `emitToInstantKept`, `laneCalls(Zoned node, ZoneForm form)` | 682; 690, parts a to c | the lowerings and their counts of lane calls |
| `Slots.batchArgLocal` (one long local an argument), `Slots.zoneTmp` (a node's vector locals: one for a `ToLocal` and for a `ToInstant`, two for a `ToInstantKept`, in a form with steps) | 682, 690 | what a zone lowering reads |
| `VarkaZoneLowering.STEP_NO_MASK_MAX_SPAN = Long.MAX_VALUE`; `sql/varka/proofs/zone_step.smt2`, 18 checks; `VarkaProofFiles.zoneStep()` | 684, part 2 | the bound of a `ToLocal` step with no mask, and its proof |
| `VarkaBatchArgs.dataOf(Zoned node, VarkaZoneData zone)`, its nested `LocalLine`, `LOCAL_MAX_SPAN = Long.MAX_VALUE - 2 * MAX_OFFSET_MICROS`, `MAX_OFFSET_MICROS`, `count(node, form)` | 690, part b; 682 | a zone's data as a program of the node reads it. For a local node it answers `MANY` for a span over `LOCAL_MAX_SPAN`, under every option. For a `ToLocal` it returns the zone itself, until this row |
| `VarkaZoneData`: `changes(least, greatest)`, `localChanges`, `toLocalArgs`, `toInstantArgs`; `VarkaChronoZoneChanges.MANY` (4096); `VarkaChronoZone.of(id)` | 682, 678 | the zone data; `MANY` is more changes than any form covers |
| `VarkaZoneCompiler.toLocal(whole, timeZoneId, instant, sink)`, `toInstant(whole, timeZoneId, local, rule, sink)`, `toInstantKept(whole, timeZoneId, local, kept, sink)`, `TWO_LANE_LOCAL_RULE_REASON` | 657; 690, parts d to f | the zone compiler. Each folds a fixed zone, tests the operand and the zone, then builds the node. `toInstant` under `GAP_END` and `toInstantKept` decline by name on a two-lane host with `TWO_LANE_LOCAL_RULE_REASON` |
| `DeclineSink.declinesTwoLaneChecks()` | master | 2.2 |
| `VarkaKernelPlan.twoLaneMasksFallBack(VarkaHostIsa.Reading host)` and its overload with `int preferredLongLanes` | master | whether `host` is a two-lane host |
| `VarkaSessionEmit` (a record: `useAVX`, `exactRefusalStatus`, `familiesOff`, `declineTwoLaneConditions`, `mathUlpContract`), its `of(conf)`, `of(conf, host)`, `of(conf, host, preferredLongLanes)`, the private `bind`, and `apply(base)` | master | a session's emit settings, resolved once on the driver and laid over the base options |
| `VarkaZoneLadder.widest(boolean maskedTwoLanes)`; in `VarkaEvaluatorBase`, `widest = VarkaZoneLadder.widest(sessionEmit.declineTwoLaneConditions())` | 685, part c | the ladder's last rung: `STEPS_2` on a two-lane host with masked steps |
| `VarkaLocalLineKernels` (`localFixture`, `changesOf`, `lo`, `hi`, `filled`, `run`, `expected`), `VarkaEmitterZoneSuite`, `VarkaLocalLineSuite`, `VarkaZoneSweepSuite`, `VarkaZoneKernels` | 682, 684, 690 | the kernel fixtures and suites |
| `VarkaTimestampBaselineBase` (`Timed`, `Form`, `grid`, `DST`, `FIXED`, `timed(boolean declinesLongConditions)`), `VarkaTimestampFieldsBaselineBenchmark`, `VarkaTimestampCastsBaselineBenchmark`, `VarkaTimestampArithmeticBaselineBenchmark`, their queries suites | 655 | the baselines: a section is a form in a cell; a queries suite plans every section with the two-lane refusal off and on |

### 2.4 The names this row adds

| name | where | part | meaning |
| :--- | :--- | :--- | :--- |
| `zoneStepMask`, a flag of `VarkaEmitOptions`, default true; `withZoneStepMask(boolean)`; its entry in `VarkaEmitOption.TABLE` | catalyst, `codegen/varka` | a | true: a step is a compare and a masked add. False: a sign word and an AND |
| `spark.sql.codegen.varka.emit.zoneStepMask` (`SQLConf.VARKA_EMIT_ZONE_STEP_MASK`, `varkaEmitZoneStepMask`), an optional boolean | `SQLConf` | a | a session's value of the option. Unset: false on a two-lane host, the base options' value elsewhere |
| `VarkaSessionEmit.zoneStepMask`, a sixth component, `@Nullable Boolean` | sql/core | a | the session's value, null to leave the base options |
| `VarkaZoneLowering.laneCalls(Zoned node, ZoneForm form, boolean stepMask)` | `VarkaZoneLowering` | a, b | the lane calls of a node under the option |
| `VarkaBatchArgs.InstantLine`, a private nested class; `dataOf` returns it for a `ToLocal` | `VarkaBatchArgs` | a | the span rule of the instant line (4.6) |
| checks 19 and 20 of `zone_step.smt2`; `zone_local_step.smt2` with 12 checks, `VarkaProofFiles.zoneLocalStep()` | `sql/varka/proofs/`, catalyst tests | a, b | the proofs of 4.7 |
| `VarkaZoneCompiler.TwoLaneForm { TO_LOCAL, EARLIER, GAP_END, KEPT, FIXED }`, `TWO_LANE_REFUSED`, `twoLaneReason(TwoLaneForm)` | `VarkaZoneCompiler` | c | the conversions a two-lane host refuses, and the reason each prints |
| `VarkaTimestampBaselineBase.TWO_LANE_REFUSED_DST`, `TWO_LANE_REFUSED_FIXED`, `refusing(List<Timed>, boolean)` | sql/core tests | c (or row 690's parts e and f, section 13 edits 4 and 9) | a baseline's sections as a session that refuses them plans them |
| `sql/varka/plans/m9/results/VARKA-689-two-lanes.txt` | new folder | c, d | the record |
| `dev/varka_zone_two_lane.py` (`--render`, `--check`, `--ratios`, `--selftest`) | `dev/` | c | renders the record from results files and holds it to them; refuses runs that are no reading (appendix A) |

### 2.5 The steps, masked and with no mask

The arguments of a program are fixed by rows 682 and 678. `a[0]` is `base`. Step `i` holds,
in order: for a `ToLocal`, `at`, `delta`; for `ToInstant` under `EARLIER`, `hi`, `delta`;
under `GAP_END`, `hi`, `lo`, `delta` (for an overlap `lo` is written as `hi`); for a
`ToInstantKept`, `hi`, `lo`, `after`, `delta` (for a gap `lo` is written as `hi`). Padding is
`Long.MAX_VALUE` for each threshold and 0 for `after` and `delta`.

**The scalar meaning**, which the masked steps compute and the steps with no mask must equal
on every live lane. Every sum wraps.

- `ToLocal`: `x + base + (sum of delta[i] where x >= at[i])`.
- `EARLIER`: `L - base - (sum of delta[i] where L >= hi[i])`.
- `GAP_END`: if `lo[i] <= L < hi[i]` for some `i`, the instant `T[i] = hi[i] - (base +
  delta[0] + ... + delta[i])`; else as `EARLIER`.
- `ToInstantKept` with the kept offset `K`: `L - base - (sum of delta[i] where L >= hi[i] ||
  (L >= lo[i] && K == after[i]))`.

**With no mask.** `S(a)` is the sign word of the threshold `a` against the node's operand
`v`: `((a - 1 - v) >> 63)`, computed as `((-v) + (a - 1)) >> 63` with `-v` kept in a vector
local. In a lane it is -1 where `v >= a` and 0 elsewhere, under the bound of 4.6.

- `ToLocal`: `acc = x + base`; for each step, `acc += S(at[i]) & delta[i]`.
- `EARLIER`: `acc = L - base`; for each step, `acc -= S(hi[i]) & delta[i]`.
- `GAP_END`: the `EARLIER` step; then, with the *window word* `w = S(lo[i]) - S(hi[i])`,
  which is -1 inside the window and 0 elsewhere, `acc += ((-acc) + T[i]) & w`.
- `ToInstantKept`: with `z = K + (-after[i])` and the *difference word* `e = (z | -z) >>
  63`, which is 0 where `K == after[i]` and -1 elsewhere, and `q = S(lo[i])`: `acc -=
  ((q - (e & q)) | S(hi[i])) & delta[i]`.

These were run lane by lane in Python against the scalar meaning, over the ends of a long,
the edges of each threshold and seeded random batches, at every span through the bound and
one step past it (`steps.py`, appendix B). Every case agreed inside the bound.

### 2.6 What was measured, and what was not

`sql/varka/spikes/zone-offset/results/`, on the laptop (an AMD Ryzen AI 9 HX PRO 370, JDK
25), nanoseconds a row. `forms.txt` (a batch of 4096 instants, `instant + offset` alone):

| form | 8 lanes | 4 lanes, AVX2 | 2 lanes, AVX2 |
| :--- | ---: | ---: | ---: |
| `java.time`: the offset | 10 | 10 | 11 |
| one offset | 0.078 | 0.077 | 0.088 |
| steps written out, 2 changes | 0.086 | 0.148 | 13 |
| steps written out, 8 changes | 0.289 | 0.755 | 63 |
| 2 steps with no mask, checked by an OR | 0.139 | 0.346 | 0.685 |

`kernels.txt` (whole probe kernels, a year of values in no order, half near a change):

| form | 8 lanes | 4 lanes, AVX2 | 2 lanes, AVX2 |
| :--- | ---: | ---: | ---: |
| `java.time`: the hour of the day | 26.815 | 25.409 | 25.360 |
| `hour`: 2 steps, then the hour | 0.482 | 0.715 | 15.679 |
| `hour`: 2 steps with no mask, then the hour | 0.514 | 0.906 | 1.597 |
| `java.time`: `LocalDateTime.atZone` | 43.163 | 43.764 | 43.752 |
| `java.time`: `ZonedDateTime.ofLocal` | 44.484 | 42.424 | 42.319 |
| to an instant, the kept offset, 2 masked steps | 0.248 | 0.351 | 43.030 |

Not measured by any probe: a local rule with no mask; the gap's end in any form; any SQL
kernel of rows 657 and 690 on a runner; anything on the Arm runner.

What warns against assuming a gain: in
`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`
12 of the 180 `varka on` lines read 0.2X to 0.4X of the row path. A kernel around a zone step
holds more than the step: the field's own arithmetic, the fold's range guard under a fixed
offset, the load and the store. The probe's `hour` is a quotient in doubles; a kernel's is
`VarkaNodeCompiler.floorDivLong`. So a refusal of every conversion is a likely outcome, and
4.12 says what the row has delivered then.

## 3. The admission check

Paths: `V` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka`,
`C` is its parent folder, `E` is `sql/core/src/main/java/org/apache/spark/sql/execution`, `TJ`
and `TS` are the `src/test/java` and `src/test/scala` twins of `V`, `CJ` is the
`src/test/java` twin of `C`, `XJ` and `XS` are `sql/core/src/test/java` and `.../scala` under
`org/apache/spark/sql/execution`, `SQ` is
`sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala`, `B` is
`$XJ/benchmark`, `M9` is `sql/varka/plans/m9`. Every command below was run in a worktree of
master at `9e7b1612402`, from the repository's root, and printed the value after its `#`.

1. **No name of this row exists, and no branch of it.**

       grep -c 'zoneStepMask' $V/VarkaEmitOptions.java $V/VarkaEmitOption.java $E/VarkaSessionEmit.java $SQ   # 0, four times
       ls dev/varka_zone_two_lane.py $M9/results 2>/dev/null | wc -l                             # 0
       git branch --list 'varka-689*' | wc -l                                                    # 0

2. **A flag is one entry of the options table, and a tag keeps the defaults' rendering.**
   `VarkaEmitOption.Flag` has the eight-argument canonical constructor `Flag(String name,
   Reason reason, boolean defaultValue, Predicate<VarkaEmitOptions> get, BiConsumer<Builder,
   Boolean> set, Rendering rendering, Subject subject, Optional<Class<? extends
   VarkaVectorIR>> readOnlyBy)`. `FlagTag(String token, boolean when)` renders `|token` when
   the value is `when` and nothing otherwise, so a flag whose tag shows at its non-default
   value leaves `VarkaEmitOptions.canonical()` of every other variant as it was. The shape
   key holds the options, so no committed hash of `sql/varka/emitted_bytes.json` moves.

       grep -c 'record FlagTag(String token, boolean when) implements Rendering {}' $V/VarkaEmitOption.java   # 1
       grep -c 'new FlagTag("decimalAbsLanewise", true), Subject.DEFAULTS,' $V/VarkaEmitOption.java   # 1

3. **The matrix runs an option that one node kind reads only if the compiler builds that
   kind, and on master `9e7b1612402`, on 27 November 2026, no approved plan said who adds
   `Zoned`.** `VarkaMatrixRuns.configurations()`
   skips an option that is not `drawnInGeneral` unless `compiledNodeReads(option)`: the
   class its `readOnlyBy` names is an element of the set `COMPILED_NODE_KINDS`, by
   `Set.contains`, so the set must hold `VarkaVectorIR.Zoned.class` itself, the class that
   `zoneForm` and `zoneStepMask` name. On master the set holds `WidenDecimal` and
   `ConvertLane`. Two things read the predicate: `configurations()`, and
   `VarkaMatrixSuite`'s test "the configurations cover every option the fuzzer draws", which
   computes both of its sides from it and so cannot see a missing kind. `VARKA-685.md`
   (its admission check, fact 10) says "row 657 adds `Zoned`", and row 657's part C
   expects the matrix to list `zoneForm=ONE` and `zoneForm=STEPS_8` in its B0; but on that
   master no step of `VARKA-657-PARTS.md` added the kind, and `VARKA-682.md` forbids it in
   its own parts, rightly: no compiler arm builds a `ToLocal` there. Edit 8 of section 13
   is what changes this: the pull request that adds this plan gives the step to row 657's
   part B, the first part whose compiler builds the node. So from that merge on
   `VARKA-657-PARTS.md` does say who adds `Zoned`, and the fifth command below prints 0 and
   1. With the kind in the set, `zoneStepMask` adds one configuration,
   `zoneStepMask=false`; without it, none. Part a's B0 stops if the kind is absent.

       grep -c 'static boolean compiledNodeReads(VarkaEmitOption option) {' $TJ/VarkaMatrixRuns.java   # 1
       grep -c '      Set.of(VarkaVectorIR.WidenDecimal.class, VarkaVectorIR.ConvertLane.class);' $TJ/VarkaMatrixRuns.java   # 1
       grep -c '.map(COMPILED_NODE_KINDS::contains).orElse(false);' $TJ/VarkaMatrixRuns.java   # 1
       grep -c 'VarkaMatrixRuns.compiledNodeReads' $TS/VarkaMatrixSuite.scala                     # 1
       grep -c 'COMPILED_NODE_KINDS' $M9/VARKA-657.md $M9/VARKA-657-PARTS.md                      # 0, twice, on that master; 0 and 1 once edit 8 of section 13 has merged
       grep -c 'public static final String PROPERTY = "varka.matrix.config";' $TJ/VarkaMatrixRuns.java   # 1

4. **A session's emit setting is a nullable component laid over the base options.**
   `VarkaSessionEmit.bind` reads each conf once; `apply(base)` returns `base` itself when
   nothing is set. The host's two-lane answer is asked through a `BooleanSupplier` named
   `twoLaneMasksFallBack`, only where the session does not set the refusal.

       grep -c 'boolean declineTwoLaneConditions, @Nullable Boolean mathUlpContract)' $E/VarkaSessionEmit.java   # 1
       grep -c 'Option<Object> contract = conf.varkaMathUlpContract();' $E/VarkaSessionEmit.java   # 1
       grep -c 'contract.isDefined() ? (Boolean) contract.get() : null);' $E/VarkaSessionEmit.java   # 1
       grep -c '        && mathUlpContract == null) {' $E/VarkaSessionEmit.java                    # 1
       grep -c 'private static final long serialVersionUID = 4L;' $E/VarkaSessionEmit.java       # 1
       grep -c 'this(useAVX, exactRefusalStatus, familiesOff, declineTwoLaneConditions, null);' $E/VarkaSessionEmit.java   # 1

   These six lines are today's master: five components, serial version 4. Row 697's pull
   request #1798 (branch `varka-long-lane-chain-cap`, read at `a165fe6965a`) adds a sixth
   component, `int longBits`, after `mathUlpContract`, makes the serial version 5, gives
   `bind` a fourth parameter, and adds a Java serialization round trip to
   `VarkaEvaluatorComponentsSuite`. Part a's B0 prints which form master has on the build
   day, and each edit of that record and of that suite is given by rule and worked for both.
   `of(conf)` and `of(conf, reading)` ask `VarkaKernelPlan.twoLaneMasksFallBack(reading)`,
   which reads the reading's architecture and this JVM's own long lanes; `of(conf, reading,
   lanes)` takes the lanes stated. So an expected value built from the first two depends on
   the JVM that runs the suite.

       grep -c 'return host.x86() && twoLaneMasksFallBack(host, Lane.LONG.preferredLanes);' $V/VarkaKernelPlan.java   # 1
       grep -c 'ObjectOutputStream' $XS/VarkaEvaluatorComponentsSuite.scala                        # 0

5. **An optional Varka conf has this form**, and its accessor is an `Option[Boolean]`.

       grep -c 'val VARKA_DECLINE_TWO_LANE_CONDITIONS =' $SQ                                     # 1
       grep -c 'def varkaDeclineTwoLaneConditions: Option\[Boolean\] = getConf(VARKA_DECLINE_TWO_LANE_CONDITIONS)' $SQ   # 1
       grep -c 'withBindingPolicy(ConfigBindingPolicy.NOT_APPLICABLE)' $SQ                        # at least 6

   The config audit is `SparkConfigBindingPolicySuite` in
   `sql/hive/src/test/scala/org/apache/spark/sql/configaudit/`: it fails a conf with no
   binding policy.
6. **The lane has every call a step with no mask needs, and the warm-up runs each.** A
   vector and a long: `add`, `and` (`lanewiseVI`). Two vectors: `add`, `sub`, `and`, `or`
   (`lanewiseVV`). A shift by a count: `lanewise(VectorOperators.ASHR, long)`
   (`integral().lanewiseBinaryI()`, as `VarkaHashLowering.shift` emits it). A negation:
   `lanewise(VectorOperators.NEG)` (`integral().lanewiseUnary()`). The warm-up's long rounds
   call each unary of `UNARY`, each binary of `BINARY` with a vector and with a long, and
   each shift of `SHIFTS` with a long.

       grep -c 'private static void shift(CodeBuilder cb, Lane lane, String op, int count) {' $V/VarkaHashLowering.java   # 1
       grep -c 'private static final List<Unary> UNARY = List.of(VectorOperators.NEG, VectorOperators.ABS,' $V/VarkaVectorTemplateWarmup.java   # 1
       grep -c 'private static final List<Binary> SHIFTS = List.of(VectorOperators.ASHR,' $V/VarkaVectorTemplateWarmup.java   # 1
       grep -c 'a.lanewise(op, 3L).lane(0)' $V/VarkaVectorTemplateWarmup.java                     # 1

7. **Two masked lanes on x86 run as Java loops, whatever the instruction set**
   (`VarkaKernelPlan.twoLaneMasksFallBack`'s comment; `sql/varka/skills/the-jit.md`).

       grep -c 'return host.x86() && preferredLongLanes <= 2;' $V/VarkaKernelPlan.java            # 1

8. **How the house records a two-lane refusal today.** A compiler arm declines by name where
   `sink.declinesTwoLaneChecks()` holds and a measurement put the loss
   (`VarkaIntervalCompiler.twoLaneMeasured`, VARKA-612). `VarkaCoverageSuite`'s test "the
   two-lane census of the value rows is the committed one" renders every coverage value row
   that such a host refuses into `sql/varka/plans/m8/results/VARKA-612-refused.txt`, one line
   a row: the family, the row's SQL and the reason, separated by tabs. A baseline's queries
   suite plans every section with the refusal off and on
   (`VarkaSharedSessions.forEachTwoLaneRefusal`). No file says which reading decided a
   refusal, and no check reads one: that is what 4.9 adds.

       grep -c 'private static Optional<VarkaVectorIR> twoLaneMeasured(' $C/VarkaIntervalCompiler.java   # 1
       grep -c 'test("the two-lane census of the value rows is the committed one") {' $TS/VarkaCoverageSuite.scala   # 1
       grep -c 'protected def forEachTwoLaneRefusal(body: Boolean => Unit): Unit =' $XS/VarkaSharedSessions.scala   # 1

9. **A results file's shape**, as `dev/varka_zone_two_lane.py` parses it: a rule of `=`, the
   section's title, a rule; then one line an arm that ends in six numbers and `X`, the first
   of them `Best Time(ms)`. The tool's parser read the file of the command below and found
   its 14 sections, each with both arms.

       grep -c '^=\{20,\}' sql/core/benchmarks/VarkaTimestampNtzFieldsBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt   # 28

10. **The benchmark workflow takes a width, a runner and JVM options.**
    `.github/workflows/benchmark.yml` has the inputs `class`, `jdk`, `expected-cpu`, `pr`,
    `extra-java-options` and `runner` (`ubuntu-latest`, or `ubuntu-24.04-arm`, "a Neoverse
    N2 with 128-bit vectors only"). It starts by dispatch only, and so does the surface's
    workflow: no workflow of the repository runs a benchmark on a schedule.

        grep -c "ubuntu-24.04-arm, a Neoverse N2 with 128-bit vectors only" .github/workflows/benchmark.yml   # 1
        grep -c 'schedule:' .github/workflows/benchmark.yml .github/workflows/varka-surface-benchmark.yml   # 0, twice

    **What a run's provenance file says of its CPU.** The workflow writes the line `cpu:`
    from the `model name` of `/proc/cpuinfo`. An aarch64 `/proc/cpuinfo` has no such field,
    so on the Arm runner the line is empty. What an Arm run does record: the probe
    `dev/varka_datapath.sh` reads the model there from `lscpu`, fails the job when it is not
    the `expected-cpu`, and writes `n/a` for its three numbers, on aarch64 alone. The
    committed Arm file `A`,
    `sql/core/benchmarks/VarkaFloatingCastBaselineBenchmark-jdk25-runner-arm-128bit-provenance.txt`,
    holds these two lines (the first is the word `cpu:` and then spaces alone):

        cpu:
        datapath:    n/a lane-ops/ns at 128 / 256 / 512 bits; 256:128 = n/a (control), 512:256 = n/a (dev/varka_datapath.sh)

        grep -c "echo \"cpu:         \$(grep -m1 'model name' /proc/cpuinfo" .github/workflows/benchmark.yml   # 1
        grep -c '^cpu: *$' $A                                                                       # 1
        grep -c '^datapath: *n/a lane-ops/ns' $A                                                    # 1
        grep -c '^cpu: *AMD EPYC 7763' sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-provenance.txt   # 1
        grep -c -F '{ echo "cpu=$cpu_model"; echo "lanes=n/a"; echo "control=n/a"; echo "ratio=n/a"; } >> "$GITHUB_OUTPUT"' dev/varka_datapath.sh   # 1

11. **How the two-lane refusal is turned off for a benchmark's JVM: two routes, and one
    check that says which holds.** `VarkaArrowSessions.createSession` sets no two-lane
    conf. A baseline class reads the refusal from its Varka session,
    `VarkaSessionEmit.of(varka.sessionState().conf()).declineTwoLaneConditions()`, and lists
    its sections by it; the session plans by the same conf. So the class's list and the
    plan cannot disagree, and the one question is whether a JVM option reaches that conf.
    - *Route A.* A `SparkConf` loads every `spark.*` system property, and a session's conf
      is merged from it (`SQLConf.mergeSparkConf`), so
      `-Dspark.sql.codegen.varka.declineTwoLaneConditions=false` on the benchmark's JVM
      reaches both sessions, and no code is added. Read in the code, not run.
    - *Route B.* If it does not reach, the base class of the timestamp baselines reads a
      property of its own, `varka.baseline.twoLaneRefusal`, and sets the conf on both
      sessions (5.3, step 3b, gives the block).

    The check is one dry run of a class that exists today, at two lanes, with route A's
    property (section 14, item 2, and part c's B0). At two lanes that class lists its filter
    `NONE` under the refusal, and prints nothing for it; with the refusal off it lists it
    `WHOLE` and prints the note `TIMESTAMP_NTZ comparison, no nulls: fuses whole on the
    varka on arm, so it times a kernel there, not the row path`. The command ends by
    printing `route A` or `route B` from that line. Part c builds the route it prints; part
    d reads which route part c built from the code.

        grep -c 'declineTwoLane\|DECLINE_TWO_LANE' sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark/VarkaArrowSessions.scala   # 0
        grep -c 'VarkaSessionEmit.of(varka.sessionState().conf()).declineTwoLaneConditions());' $B/VarkaTimestampNtzBaselineBenchmark.java   # 1
        grep -c 'out.add(new Section("TIMESTAMP_NTZ comparison, " + density,' $B/VarkaTimestampNtzBaselineBenchmark.java   # 1
        grep -c 'note(title + ": fuses whole on the varka on arm, so it times a kernel there, "' $B/VarkaSqlBenchmarkBase.java   # 1
        grep -c 'SQLConf.mergeSparkConf(conf, sharedState.conf)' sql/core/src/main/scala/org/apache/spark/sql/classic/SparkSession.scala   # 1

12. **The checks that the pre-commit and CI run by name.** `dev/varka_precommit.sh` runs a
    tool's `--selftest` when the tool changed, for the tools of one list.
    `.github/workflows/build_and_test.yml` runs `./dev/varka_census.py --check` in its
    documents job.

        grep -c 'for tool in varka_bench_diff varka_bench_gate varka_lint varka_msg_check varka_matrix_shards \\' dev/varka_precommit.sh   # 1
        grep -c '      run: ./dev/varka_census.py --check' .github/workflows/build_and_test.yml   # 1

**The arithmetic was run.** `steps.py` (appendix B) holds each lowering of 4.3 and 4.4 lane
by lane in wrapping 64-bit arithmetic and printed `ok` for its 18 checks; `steps8.py` adds
the cases of eight real steps. The 14 new proof checks of 4.7 were written against
`java.smt2` and run on 28 November 2026 under the pinned Z3 5.1.0 and cvc5 1.4.1, for their
verdicts and not for their time. Each answered as 4.7 expects. `Asia/Kathmandu`'s two
changes, -1577943676 s and 504901800 s, were read from the laptop's zone data through
Python's `zoneinfo`.

**What I could not check.** Form L of `VarkaSessionEmit` and of its suite was read from the
branch of the open pull request #1798 at `a165fe6965a`; it may change before it merges, and
part a's B0 prints the header it then has. No name of 2.3 but its three rows marked
"master" is on master: each is read from its row's merged plan, and no B0 line over rows
682, 684, 685, 690 and 655 could be run. No JVM was run: every lane-call count is from
`steps.py`'s count of the calls this plan writes, no code length is pinned as a number, and
every time of section 7 is a prediction. Section 14 lists what only a JVM can settle. Row
662 is planned beside this one (section 12).

## 4. The design

### 4.1 The option

`VarkaEmitOptions` gains a boolean component `zoneStepMask`, after the last component the
record has on the day, with every site an option has: its `@param` line, its value `true` in
`DEFAULTS`, the copy in `toBuilder`, the `Builder`'s field and setter, the argument of
`build()`, and `public VarkaEmitOptions withZoneStepMask(boolean mask)`. The `@param` text:

    @param zoneStepMask whether a step of a zone node is a compare and a masked add, or, off,
           a sign word and an AND, which holds no mask (VARKA-689). On an x86 host whose
           64-bit species have two lanes C2 lowers no mask, so there the session turns it
           off ({@code VarkaSessionEmit}). The form with no mask is exact only for a batch
           whose span fits a long; {@code VarkaBatchArgs.dataOf} declines any other.

`VarkaEmitOption.TABLE` gains, last:

    // A machine choice (VARKA-689): the masked step is the cheaper one wherever C2 lowers a
    // 64-bit mask, and the step with no mask is the form for an x86 host of two 64-bit
    // lanes, where it does not. Only a zone node reads it. Its tag keeps the default's
    // rendering empty.
    new Flag("zoneStepMask", Reason.MACHINE, true,
        VarkaEmitOptions::zoneStepMask, Builder::zoneStepMask,
        new FlagTag("zoneStepNoMask", false), Subject.DEFAULTS,
        Optional.of(VarkaVectorIR.Zoned.class))

- **Values.** Two: `true` (masked, the default) and `false`.
- **The shape key.** `canonical()` of the defaults is unchanged: the tag shows only at
  `false`, as `|zoneStepNoMask`. So no committed hash moves, and a kernel with no mask keys
  and caches apart from the masked one.
- **The matrix.** One configuration more, `zoneStepMask=false` (fact 3). Every Varka suite
  then runs with the steps with no mask on CI's wide hosts, where they are slower and must
  give the same rows. `VarkaEmittedBytesSuite`'s `pinnedArms` is not touched, so
  `emitted_bytes.json` does not move. `VarkaTestReach.FULL` gets no entry: the option
  changes a kernel's bytes, which the reach record hears, so the configuration runs the
  tests whose kernels hold a zone node and skips the rest. `VarkaMatrixRuns.C2_OPTIONS` gets
  none either: the option changes which calls a kernel makes, not the species or the
  instructions.
- **The fuzzers.** The option is read by one node kind, so the general draws leave it out
  (`drawnInGeneral` is false). Row 684's zone sequence of the IR fuzzer draws its options
  from `VarkaMatrixRuns.BASE`, so the matrix configuration reaches it.
- **Under `ONE`** the option changes no byte: a kernel with no step has no mask.

### 4.2 The default follows the host: the session's setting

The emitter never reads the host: an option's value is in the shape key, and the same
options give the same bytes on every machine. The host is read where a session's settings
are bound, as `useAVX` is.

- `SQLConf` gains, after `VARKA_MATH_ULP_CONTRACT`:

      val VARKA_EMIT_ZONE_STEP_MASK =
        buildConf("spark.sql.codegen.varka.emit.zoneStepMask")
          .internal()
          .doc("Whether a Varka kernel that reads a time zone applies each change of the zone's" +
            " offset with a vector mask (true) or with a sign word and an AND (false). Every" +
            " answer is the same. Unset, the host's answer: false on an x86 host whose 64-bit" +
            " vector species have two lanes or fewer, where the JIT lowers no vector mask and a" +
            " masked step runs slower than the row engine; elsewhere the emitter's own" +
            " setting, which is true. With false, a batch whose timestamps span more than a" +
            " long of microseconds is left to the row engine. The setting is part of the" +
            " shape hash, so a session that sets it emits and caches its own classes.")
          .version("5.0.0")
          // An emitter lowering choice: it changes how a kernel computes a value, never what
          // a view body resolves to.
          .withBindingPolicy(ConfigBindingPolicy.NOT_APPLICABLE)
          .booleanConf
          .createOptional

  and, after `varkaDeclineTwoLaneConditions`,
  `def varkaEmitZoneStepMask: Option[Boolean] = getConf(VARKA_EMIT_ZONE_STEP_MASK)`. The
  version is the one the neighbouring Varka confs carry; B0 prints it.
- `VarkaSessionEmit` gains a sixth component, `@Nullable Boolean zoneStepMask`, with the
  `@param` text "whether a zone node's steps are masked
  ({@code spark.sql.codegen.varka.emit.zoneStepMask}, VARKA-689): the conf's value where it
  is set; unset, false on a host whose 64-bit masks fall back
  ({@link VarkaKernelPlan#twoLaneMasksFallBack(VarkaHostIsa.Reading)}, whatever the two-lane
  refusal is set to), and null, which leaves the base options' setting, on any other. An
  emit option, since it changes a kernel's bytes."
  - The component is the record's last, after every component it has on the build day
    (after `mathUlpContract` on today's master; after `longBits` once row 697 has merged).
  - A constructor of the old canonical shape is added: the components the record had before
    this part, in order, passing `null` for the new one. So no caller of the old canonical
    constructor changes, in production or in a test.
  - `serialVersionUID` goes up by one: the record is `Serializable` and the plan carries it
    to the executors. 4 becomes 5 on today's master; 5 becomes 6 once row 697 has merged.
  - `bind` asks the host's answer once and keeps it:

        boolean hostFallsBack = twoLaneMasksFallBack.getAsBoolean();
        Option<Object> stepMask = conf.varkaEmitZoneStepMask();

    The record's fourth argument becomes `twoLane.isDefined() ? (Boolean) twoLane.get() :
    hostFallsBack`, and its last, new, argument is `stepMask.isDefined() ? (Boolean)
    stepMask.get() : (hostFallsBack ? Boolean.FALSE : null)`. The supplier is cheap and
    pure, so asking it where the refusal is set changes nothing else.
  - `apply(base)`: the early return also tests `zoneStepMask == null`, and the builder gets
    `if (zoneStepMask != null) { options.zoneStepMask(zoneStepMask); }`.
- **What reaches which kernel.**

| session, host | `zoneStepMask` the kernel is emitted under |
| :--- | :--- |
| conf unset, a wide x86 host or aarch64 | the base options': true, or false under the matrix configuration |
| conf unset, a two-lane host | false |
| conf set | the conf's value, on any host |
| an evaluator built with `VarkaSessionEmit.DEFAULTS` (a suite) | the base options' |

  On a two-lane host a session cannot be given masked steps through the test hook
  (`VarkaColumnarToRowExec.setEmitOptionsForTesting`): the host's answer is laid over it.
  The conf set to true gives them. The catalyst suites emit without a session and are not
  touched by any of this.
- **A side effect, on a two-lane host only.** The tag renders by the option's value, not by
  the shape's nodes. So there every kernel of a session, one with no zone node included,
  keys with `|zoneStepNoMask` and caches apart from the same kernel of a suite that emits at
  the defaults. The bytes are the same; the cost is a second class of the same shape where
  both are asked for in one JVM, which a production session never does.
- **A test that binds a session from the JVM's own host** sees the host's answer in the
  record and in what `apply` returns. `VarkaEvaluatorComponentsSuite` has such assertions;
  part a edits each so that it holds on a two-lane JVM and on a wide one (5.1).
- **The ladder's cap** (row 685's part c). `widest` is computed from
  `sessionEmit.declineTwoLaneConditions()`. Its argument becomes
  `sessionEmit.declineTwoLaneConditions() && emitOptions.zoneStepMask()`, as
  `VARKA-685.md` 4.4 says: with no mask, eight steps are no Java loop, and the cap has lost
  its reason. Part a's B0 prints the line; with no such line (row 685's part c not merged)
  nothing is done, and that row's own sheet writes the second form when it finds the option.
- **The debt.** The setting is resolved on the driver, as the two-lane refusal is (the
  catalogue's item 127). An executor of another width emits the driver's form: right
  answers either way, and speed only. This row adds a reader and does not move the choice.

### 4.3 The lowering of `ToLocal` with no mask

`VarkaZoneLowering.emitToLocal` reads the flag from the options object it reads the form
from: where its form is `<X>.zoneForm()` or `VarkaZoneLadder.emitted(<X>)`, the flag is
`<X>.zoneStepMask()`. With the flag true, or in a form with no step, it emits what it emits
today, byte for byte. With the flag false and `k > 0` steps, `tmp` being the node's one
vector local (`Slots.zoneTmp`) and `a` its argument locals (`Slots.batchArgLocal`):

| # | bytecode | stack after |
| ---: | :--- | :--- |
| 1 | `emitValue(n.instant())` | `x` |
| 2 | `dup`; `getstatic VectorOperators.NEG`; `invokevirtual lanewise` (`integral().lanewiseUnary()`); `astore tmp` | `x`; `tmp` holds `-x` |
| 3 | `lload a[0]`; `invokevirtual add` (`lanewiseVI`) | `acc = x + base` |
| 4, each step `i` | `aload tmp`; `lload at`; `lconst_1`; `lsub`; `invokevirtual add` (`lanewiseVI`) | `acc`, `(-x) + (at - 1)` |
| 5 | `getstatic VectorOperators.ASHR`; `ldc2_w 63L`; `invokevirtual lanewise` (`integral().lanewiseBinaryI()`) | `acc`, `S(at)` |
| 6 | `lload delta`; `invokevirtual and` (`lanewiseVI`) | `acc`, `S(at) & delta` |
| 7 | `invokevirtual add` (`lanewiseVV`) | `acc` |

- Step 5 is `VarkaHashLowering.shift(cb, lane, "ASHR", 63)`'s three instructions; that
  method is private, so the lowering writes them, with `lane.pushScalar(cb, 63)` for the
  count as `shift` does.
- Lane calls: one negation, one add of the base, four a step: `2 + 4k`, so 10 at two steps
  and 34 at eight, where the masked form has `1 + 2k`, 5 and 17. No `VectorMask` call and no
  compare.
- `at - 1` is computed in scalar code. A real `at` is above the batch's least value and a
  padded one is `Long.MAX_VALUE`, so it never wraps.
- A padded step adds `S & 0`, which is 0 for every long: a null row's storage included.

### 4.4 The lowering of the three local rules with no mask

`emitToInstant` and `emitToInstantKept` read the flag as 4.3 says. With the flag true or no
step they emit row 690's bytes. With the flag false and `k > 0`, each node has one vector
local more than its masked form: two for a `ToInstant` (either rule; `EARLIER` uses the
first only) and three for a `ToInstantKept`. `Slots.plan` plans them from the same options.
`nl` is the local that holds `-L`; `S(a)` below is the five instructions `aload nl`; `lload
a`; `lconst_1`; `lsub`; `add` (`lanewiseVI`), then the shift of 4.3's step 5: two lane calls.

**`EARLIER`.** `emitValue(n.local())`; `dup`; negate; `astore nl`; `lload a[0]`; `lneg`;
`add` (`lanewiseVI`): `acc = L - base`. Then, for each step: `S(hi)`; `lload delta`; `and`
(`lanewiseVI`); `sub` (`lanewiseVV`): `acc -= S(hi) & delta`. Lane calls `2 + 4k`: 10 and 34.

**`GAP_END`.** The same start. Then, for each step `i`, with `sh` the node's second local:

| # | bytecode | stack after |
| ---: | :--- | :--- |
| 1 | `S(hi)`; `dup`; `astore sh` | `acc`, `S(hi)` |
| 2 | `lload delta`; `and` (`lanewiseVI`); `sub` (`lanewiseVV`) | `acc` |
| 3 | `dup`; negate | `acc`, `-acc` |
| 4 | the scalar `T`: `lload hi`; `lload a[0]`; `lsub`; then for each `j` from 0 to `i`, `lload delta_j`; `lsub`. Then `add` (`lanewiseVI`) | `acc`, `T - acc` |
| 5 | `S(lo)`; `aload sh`; `sub` (`lanewiseVV`) | `acc`, `T - acc`, `w` |
| 6 | `and` (`lanewiseVV`); `add` (`lanewiseVV`) | `acc` |

Lane calls a step: 2, 2, 1, 1, 3, 2: eleven. `2 + 11k`: 24 and 90, where the masked form has
`1 + 5k`, 11 and 41. For an overlap and for padding `lo` is `hi`, so `w` is the difference of
one sign word with itself: 0 for every long.

**`ToInstantKept`.** `emitValue(n.local())`; `astore tl`; `emitValue(n.kept())`; `astore
tk`; `aload tl`; `dup`; negate; `astore tl`; `lload a[0]`; `lneg`; `add` (`lanewiseVI`). The
first local holds `L` only until the kept operand is emitted, as in the masked form, and
`-L` after it. Then, for each step, with `q` the node's third local:

| # | bytecode | stack after |
| ---: | :--- | :--- |
| 1 | `S(lo)`; `astore q` | `acc` |
| 2 | `aload tk`; `lload after`; `lneg`; `add` (`lanewiseVI`) | `acc`, `z` |
| 3 | `dup`; negate; `or` (`lanewiseVV`); the shift of 4.3's step 5 | `acc`, `e` |
| 4 | `aload q`; `and` (`lanewiseVV`); `aload q`; `swap`; `sub` (`lanewiseVV`) | `acc`, `q - (e & q)` |
| 5 | `S(hi)`; `or` (`lanewiseVV`) | `acc`, `f` |
| 6 | `lload delta`; `and` (`lanewiseVI`); `sub` (`lanewiseVV`) | `acc` |

Lane calls a step: 2, 1, 3, 2, 3, 2: thirteen. `2 + 13k`: 28 and 106, where the masked form
has `1 + 4k`, 9 and 33, and `2k` mask calls. `swap` exchanges two vector references, each
one stack slot. `-after` is right for every long: `K + (-after)` is `K - after` in wrapping
arithmetic, and it is 0 exactly where the two are equal.

**Why no check.** As for the masked steps (`VARKA-690.md` 4.6): a live `L` is 18 hours
inside a long's ends, the steps that fire are the first few, and each leaves `acc` as `L`
less one offset of the zone. `T - acc` is a difference of two instants a few hours apart.

### 4.5 The counts, and the grouping budget

`VarkaZoneLowering` gains `static int laneCalls(VarkaVectorIR.Zoned node,
VarkaEmitOptions.ZoneForm form, boolean stepMask)`. With `stepMask`, or a form with no step,
it returns `laneCalls(node, form)`. Else, with `k = form.steps()`:

| node | masked, `laneCalls(node, form)` | with no mask | at 2 and at 8 steps |
| :--- | :--- | :--- | :--- |
| `ToLocal` | `1 + 2k` | `2 + 4k` | 10, 34 |
| `ToInstant`, `EARLIER` | `1 + 2k` | `2 + 4k` | 10, 34 |
| `ToInstant`, `GAP_END` | `1 + 5k` | `2 + 11k` | 24, 90 |
| `ToInstantKept` | `1 + 4k` | `2 + 13k` | 28, 106 |

`VarkaEmitBudget.weightOf(node, options, narrow)` is the one caller that passes the option:
where its arm for a zone node calls `laneCalls(<node>, <form>)`, it calls `laneCalls(<node>,
<form>, options.zoneStepMask())`. A record's `props()` is not touched: it has no options and
states the default's weight. Part a gives the `ToLocal` row; until part b the two local
records return their masked counts under either value, and their lowering emits the masked
steps under either value.

### 4.6 The span rule, and what a wide batch does

**The bound, in numbers.** On the instant line a real step has `least < at <= greatest` and
a live row `least <= x <= greatest`. So `at - 1 - x` lies between `-(greatest - least)` and
`greatest - least - 1`: it is a long while the span is at most 2^63,
9,223,372,036,854,775,808 microseconds. The rule is one microsecond inside that:
`VarkaZoneLowering.STEP_NO_MASK_MAX_SPAN`, `Long.MAX_VALUE`. "Does not fit a long" means
`greatest - least > 9,223,372,036,854,775,807`: more than about 292,271 years of instants in
one batch. The first span at which the step is wrong is 2^63 + 1 (`zone_step.smt2`, check 6).

On the local line a filled window touches the range, so `least - 2M < lo` and `hi <=
greatest + 2M`: the bound is `VarkaBatchArgs.LOCAL_MAX_SPAN`, `Long.MAX_VALUE - 2M`,
9,223,371,907,254,775,807. The steps are exact through `LOCAL_MAX_SPAN + 1` and first wrong
at `LOCAL_MAX_SPAN + 2` (4.7, checks 3 and 4 of the new file).

**How the span is known.** Nothing new is measured. Before every call the runner has the
least and greatest live value of each program's operand: the pass
`LongRangeOps.leastAndGreatest` (row 683), the cache's statistics (row 687), or interval
arithmetic for a computed operand (row 688). It asks the program's zone data
`changes(least, greatest)` and declines the batch with `STATUS_BATCH_ARGS` when the answer is
more than the kernel's steps. The data a program holds is `VarkaBatchArgs.dataOf(node,
zone)` (row 690's part b). The span rule lives there, so it holds in the runner, in the
ladder, in the ranges of row 688 and in every test filler, with no reader changed.

**One rule for both step forms** (question 2 of section 15; the plan applies it). Row 690's
view of the local line already answers `MANY` for a span over `LOCAL_MAX_SPAN` under every
option, by the owner's decision of 27 November 2026 (`VARKA-690.md` 12, decision 3). This row
does the same for the instant line. `dataOf`'s arm for a `ToLocal` becomes `new
InstantLine(zone)`:

    /**
     * A zone's data as a program of a ToLocal reads it: the zone's own, except that a range
     * whose span does not fit a long, and that holds a change, counts as more changes than
     * any form covers. The step with no mask is exact only inside that span
     * (VarkaZoneLowering.STEP_NO_MASK_MAX_SPAN, zone_step.smt2); the masked step would
     * answer such a batch, and it declines too, so that one rule holds under every option.
     */
    private static final class InstantLine implements VarkaZoneData {
      private final VarkaZoneData zone;

      InstantLine(VarkaZoneData zone) {
        this.zone = zone;
      }

      @Override
      public int changes(long least, long greatest) {
        int changes = zone.changes(least, greatest);
        // greatest >= least here, so a negative difference is a span of 2^63 or more.
        return changes > 0 && greatest - least < 0 ? VarkaChronoZoneChanges.MANY : changes;
      }
      // id, fixed, localChanges, toLocalArgs, toInstantArgs and every other method the
      // interface has on the day: return or call the same method of zone, same arguments.
    }

- The test is `greatest - least < 0` in wrapping arithmetic: for `least <= greatest` it is
  true exactly where the span is over `STEP_NO_MASK_MAX_SPAN` (4.7, check 20).
- A range with no change needs no step, so it is answered at any span.
- `MANY` is written as `LocalLine` writes it in the same file; B0 prints that line.
- No batch that Spark's own range can hold is touched: years 0001 to 9999 span under
  3.2e17 microseconds.

**What the kernel does then.** Nothing: it is not called. Traced from the top of the run
path, as rows 682 and 685 have it: `VarkaEvaluatorBase.serveBatch` asks for the batch's
runner; the input bounds are checked first (`STATUS_INPUT_BOUND`: none for a plain timestamp
column); then each program's range. A batch with no live row gets the neutral arguments and
runs. A least or greatest value within 18 hours of an end of a long declines first, with the
same status. Then `changes(least, greatest)`: `MANY` is over every form's steps, and under
the ladder `VarkaZoneLadder.rung(MANY, widest)` is -1. Either way the batch is declined with
`VarkaKernelRunner.STATUS_BATCH_ARGS`. What prints: the metric
`numFallbackBatchesDeclined` and the reason metric of the name `batch-args` (row 682 added
the name to `VarkaFallbackAccounting.STATUS_NAMES`). No new status, no new metric, no new
text. The row path answers every row of the batch.

### 4.7 The proofs

The house rule: "A new bounded lowering comes with its proof file". Both files are rendered
by `VarkaProofFiles` under logic `QF_NIA` over `java.smt2`, and run under both solvers.

**`zone_step.smt2` gains two checks** (part a), after row 684's eighteen, in `zoneStep()`:

| # | what | expect | body |
| ---: | :--- | :--- | :--- |
| 19 | the emitted order, the negated instant plus the threshold less one, is the wrapped at - 1 - x, for every pair of longs | unsat | `(declare-const x Int) (declare-const at Int)` `(assert (and (jlong.in x) (jlong.in at)))` `(assert (not (= (jlong.add (jlong.neg x) (jlong.sub at 1)) (jlong.sub (jlong.sub at 1) x))))` |
| 20 | the span test: for least <= greatest the wrapped difference is negative exactly where the span is over STEP_NO_MASK_MAX_SPAN | unsat | `(declare-const least Int) (declare-const greatest Int)` `(assert (and (jlong.in least) (jlong.in greatest) (<= least greatest)))` `(assert (not (= (< (jlong.sub greatest least) 0) (> (- greatest least) 9223372036854775807))))` |

Check 19 ties the bytecode of 4.3 to the sign word that checks 1 to 6 prove. Check 20 ties
`InstantLine.changes` to the bound. The number in check 20 is rendered from
`VarkaZoneLowering.STEP_NO_MASK_MAX_SPAN`.

**`zone_local_step.smt2`** (part b), rendered by a new `VarkaProofFiles.zoneLocalStep()`,
from the code's constants: `M2 = 2 * VarkaBatchArgs.MAX_OFFSET_MICROS = 129600000000`, `SPAN
= VarkaBatchArgs.LOCAL_MAX_SPAN = 9223371907254775807`, `H = 64800000000`. The renderer
throws `IllegalStateException` unless `SPAN + M2 == Long.MAX_VALUE`. The frame `F`:

    (declare-const L Int) (declare-const least Int) (declare-const greatest Int)
    (declare-const lo Int) (declare-const hi Int)
    (assert (and (jlong.in L) (jlong.in least) (jlong.in greatest) (jlong.in lo) (jlong.in hi)))
    (assert (and (<= least L) (<= L greatest) (<= lo hi)))
    (assert (and (< (- least 129600000000) lo) (<= hi (+ greatest 129600000000))))
    (define-fun S ((a Int)) Int (jlong.shr (jlong.add (jlong.neg L) (jlong.sub a 1)) 63))
    (define-fun GE ((a Int)) Int (ite (>= L a) (- 1) 0))

`IN` is `(assert (<= (- greatest least) 9223371907254775807))`. `S` is the sign word in the
emitted order. Each check is its frame, then its asserts, then `(check-sat)`, inside the
`(push 1)` and `(pop 1)` that `VarkaProofFiles.check` writes.

| # | what | expect | asserts |
| ---: | :--- | :--- | :--- |
| 1 | inside LOCAL_MAX_SPAN, a threshold less one less a local time is a long | unsat | `F`; `IN`; `(assert (not (and (jlong.in (- (- lo 1) L)) (jlong.in (- (- hi 1) L)))))` |
| 2 | so the sign word of each threshold, in the emitted order, is the compare | unsat | `F`; `IN`; `(assert (not (and (= (S lo) (GE lo)) (= (S hi) (GE hi)))))` |
| 3 | one microsecond past the bound it still is | unsat | `F`; `(assert (= (- greatest least) 9223371907254775808))`; `(assert (not (and (= (S lo) (GE lo)) (= (S hi) (GE hi)))))` |
| 4 | two past, the sign word of a window's start differs | sat | `F`; `(assert (= (- greatest least) 9223371907254775809))`; `(assert (not (= (S lo) (GE lo))))` |
| 5 | the window word is -1 exactly inside the window | unsat | `F`; `IN`; `(assert (not (= (jlong.sub (S lo) (S hi)) (ite (and (>= L lo) (< L hi)) (- 1) 0))))` |
| 6 | the select by a window word, in the emitted order, is T inside and acc outside, for every long | unsat | no frame; `(declare-const acc Int) (declare-const T Int) (declare-const w Int)`; `(assert (and (jlong.in acc) (jlong.in T) (or (= w 0) (= w (- 1)))))`; `(assert (not (= (jlong.add acc (jlong.and.sign w (jlong.add (jlong.neg acc) T))) (ite (= w (- 1)) T acc))))` |
| 7 | the difference word of two longs is 0 exactly where they are equal and -1 elsewhere | unsat | no frame; `(declare-const k Int) (declare-const after Int)`; `(assert (and (jlong.in k) (jlong.in after)))`; `(define-fun z () Int (jlong.add k (jlong.neg after)))`; `(define-fun e () Int (ite (or (< z 0) (< (jlong.neg z) 0)) (- 1) 0))`; `(assert (not (= e (ite (= k after) 0 (- 1)))))` |
| 8 | the kept step with no mask subtracts the delta exactly where the masked step does | unsat | `F`; `IN`; `(declare-const kept Int) (declare-const after Int) (declare-const delta Int)`; `(assert (and (jlong.in kept) (jlong.in after) (jlong.in delta)))`; `(define-fun e () Int (ite (= kept after) 0 (- 1)))`; `(define-fun q () Int (S lo))`; `(define-fun g () Int (jlong.sub q (jlong.and.sign e q)))`; `(define-fun fire () Int (ite (or (= g (- 1)) (= (S hi) (- 1))) (- 1) 0))`; `(assert (not (= (jlong.and.sign fire delta) (ite (or (>= L hi) (and (>= L lo) (= kept after))) delta 0))))` |
| 9 | a local time 18 hours inside a long's ends less an offset within 18 hours does not wrap | unsat | no frame; `(declare-const L Int) (declare-const off Int)`; `(assert (and (<= (+ jlong.MIN 64800000000) L) (<= L (- jlong.MAX 64800000000))))`; `(assert (and (<= (- 64800000000) off) (<= off 64800000000)))`; `(assert (not (= (jlong.sub L off) (- L off))))` |
| 10 | a window's start taken for its end, the planted fault's form, differs at the window's start | sat | no frame; `(declare-const L Int) (declare-const lo Int) (declare-const hi Int)`; `(assert (and (jlong.in L) (jlong.in lo) (jlong.in hi) (< lo hi) (= L lo)))`; `(assert (not (= (ite (>= L lo) (- 1) 0) (ite (>= L hi) (- 1) 0))))` |
| 11 | a padded step, as the fill writes it (each threshold Long.MAX_VALUE, after and delta 0), leaves the running answer of each rule as it was, for every long | unsat | the frame `P`; `(assert (and (= plo jlong.MAX) (= phi jlong.MAX) (= pafter 0) (= pdelta 0)))`; the body `PB` |
| 12 | a padded window whose start is not its end changes the gap's end rule | sat | the frame `P`; `(assert (and (= phi jlong.MAX) (= pafter 0) (= pdelta 0)))`; the body `PB` |

The frame `P` of checks 11 and 12 declares a local time, a running answer, a change's
instant, a kept offset, and the four arguments of one step:

    (declare-const L Int) (declare-const acc Int) (declare-const T Int) (declare-const kept Int)
    (declare-const plo Int) (declare-const phi Int) (declare-const pafter Int) (declare-const pdelta Int)
    (assert (and (jlong.in L) (jlong.in acc) (jlong.in T) (jlong.in kept)))
    (assert (and (jlong.in plo) (jlong.in phi) (jlong.in pafter) (jlong.in pdelta)))

The body `PB` writes one step of each rule as 4.4 emits it, over those arguments, and denies
that all three leave `acc` as it was:

    (define-fun S ((a Int)) Int (jlong.shr (jlong.add (jlong.neg L) (jlong.sub a 1)) 63))
    (define-fun earlier () Int (jlong.sub acc (jlong.and.sign (S phi) pdelta)))
    (define-fun w () Int (jlong.sub (S plo) (S phi)))
    (define-fun gapEnd () Int (jlong.add earlier (jlong.and.sign w (jlong.add (jlong.neg earlier) T))))
    (define-fun e () Int (ite (= kept pafter) 0 (- 1)))
    (define-fun g () Int (jlong.sub (S plo) (jlong.and.sign e (S plo))))
    (define-fun fire () Int (ite (or (= g (- 1)) (= (S phi) (- 1))) (- 1) 0))
    (define-fun keptStep () Int (jlong.sub acc (jlong.and.sign fire pdelta)))
    (assert (not (and (= earlier acc) (= gapEnd acc) (= keptStep acc))))

**What check 11 holds, and what it does not.** It holds the padding to the two facts the
steps need of it: a delta of 0, and a window whose start is its end. It can fail. With the
start of the padded window left free (check 12, which is check 11 less `(= plo jlong.MAX)`)
both solvers answer sat: the window word is then -1 for some local time, and the gap's end
rule replaces the running answer by `T`. With `(= pdelta 1)` in place of `(= pdelta 0)` both
answer sat too: the sign word of a padded threshold is -1 at `L = Long.MAX_VALUE`, and, by
the wrap, at `L = Long.MIN_VALUE`, which a null row's storage may hold. Both were run on 28
November 2026 (appendix B). The check does not show that the sign word of a padded threshold
is 0: it is not, at those two values. It shows that the step is a no-op whatever that word
is. With every threshold free and a delta of 0 the answer stays unsat for `EARLIER` and for
the kept offset; only the gap's end rule needs the window's two ends equal.

Two things the file models and does not prove, said in its header: the sign of `z | -z` is
the OR of the two signs (check 7 writes the OR of the signs), and the OR of two sign words is
a sign word (checks 8, 11 and 12 write it as an `ite`). Both are facts of two's complement,
and part b's kernel tests hold the bytecode to them at the ends of a long. The file adds no
constant: `LOCAL_MAX_SPAN` is row 690's.

All fourteen checks were run under both solvers on 28 November 2026 (section 3). That a
kernel equals `java.time` in every zone is no SMT claim: the sweep holds it, and under the
matrix configuration `zoneStepMask=false` it holds it for these steps.

### 4.8 What an existing test must pin

The matrix configuration `zoneStepMask=false` changes the bytes of every kernel with steps.
A test that asserts the masked form itself must say so, as the house rule on pinned configs
asks. The rule, applied in part a to row 682's and row 684's tests and in part b to row
690's, is closed, so that no hit of a grep is a judgement:

- A test method gets the pin when its body, from its `@Test` to the next `@Test`, holds
  both `ZoneForm.STEPS_2`, `ZoneForm.STEPS_8` or `ZoneForm.LADDER`, and a call of
  `invocationCount(` or of `codeSize(`. So does row 685's test of the ladder's cap at two
  steps. The pin is `.withZoneStepMask(true)` appended to each options expression of that
  body that holds `withZoneForm(`.
- A hit of B0's grep that is not inside a test method, or whose method holds no form with
  steps, gets nothing.
- The second catalyst run of each sheet, under `-Dvarka.matrix.config=zoneStepMask=false`,
  is the check. A test that fails there on a count of calls or on a code length gets the
  pin by the first rule and is reported. A test that fails there on a row's value is a
  stop: that is a wrong step, never a missing pin.

No test is added to `sql/varka/matrix/skips.tsv`. This plan's own tests pin no code length
as a number: a length taken from the first run would be an oracle read from the code under
test. They assert a length only against another length (5.1, test 2; 5.2, test 4).

### 4.9 The recorded refusal

**The conversions.** Every zoned kernel is built through three methods of
`VarkaZoneCompiler`. Each first folds a fixed zone and else builds a node. That gives five
things a two-lane host can keep or refuse:

| conversion | where it is built | the first kernels that decide it |
| :--- | :--- | :--- |
| `TO_LOCAL` | `toLocal`, a zone with changes | `hour(ts)`, `minute(ts)`, `second(ts)`, `CAST(ts AS DATE)` |
| `EARLIER` | `toInstant` with `Resolve.EARLIER`, a zone with changes | `CAST(ntz AS TIMESTAMP)` |
| `GAP_END` | `toInstant` with `Resolve.GAP_END`, a zone with changes | `CAST(d AS TIMESTAMP)` |
| `KEPT` | `toInstantKept`, a zone with changes | `ts + INTERVAL '1' DAY` |
| `FIXED` | the fold of a fixed offset that is not UTC, in any of the three | the seven kernels above under `+01:00` |

Under UTC a zoned kernel is its `TIMESTAMP_NTZ` twin: it holds no node and no fold of this
family, and it is not in the record. `ts + INTERVAL '1' DAY` holds a `ToLocal` and a
`ToInstantKept`; its sections are counted under `KEPT`, and it declines when either is
refused.

**In the compiler** (part c). `VarkaZoneCompiler` gains:

    /** A zone conversion as the two-lane record names it (VARKA-689). */
    enum TwoLaneForm {
      TO_LOCAL("a TIMESTAMP read in a time zone with changes"),
      EARLIER("a local time resolved to an instant by the earlier offset"),
      GAP_END("a local time resolved to an instant by the gap's end"),
      KEPT("a local time resolved to an instant by the kept offset"),
      FIXED("a time zone conversion under a fixed offset");

      private final String what;

      TwoLaneForm(String what) {
        this.what = what;
      }
    }

    /**
     * The conversions an x86 host of two 64-bit lanes leaves to the row engine: the lines
     * `refused` of sql/varka/plans/m9/results/VARKA-689-two-lanes.txt, which
     * VarkaZoneCompilerSuite holds this set to.
     */
    static final Set<TwoLaneForm> TWO_LANE_REFUSED =
        Collections.unmodifiableSet(EnumSet.of(TwoLaneForm.GAP_END, TwoLaneForm.KEPT));

    /** The reason a refused conversion records. */
    static String twoLaneReason(TwoLaneForm form) {
      return form.what + ", on an x86 host whose 64-bit species have two lanes or fewer: "
          + "refused there, since its kernel was not measured faster than the row engine "
          + "(VARKA-689)";
    }

    private static boolean twoLaneRefuses(TwoLaneForm form, DeclineSink sink) {
      return sink.declinesTwoLaneChecks() && TWO_LANE_REFUSED.contains(form);
    }

- The enum, the set and `twoLaneReason` take the modifiers that `TWO_LANE_LOCAL_RULE_REASON`
  has on the day (B0 prints its declaration): a suite of `sql/core` reads the reason.
- The initial set is the owner's decision of 27 November 2026 (`VARKA-690.md` 12, decision
  1): the gap's end and the kept offset decline before any measurement. So part c changes
  which text prints and nothing else.
- Row 690's two statements `if (sink.declinesTwoLaneChecks() && ...) { return
  sink.decline(TWO_LANE_LOCAL_RULE_REASON, whole); }` become, in `toInstant`, `if
  (twoLaneRefuses(rule == Resolve.EARLIER ? TwoLaneForm.EARLIER : TwoLaneForm.GAP_END,
  sink)) { return sink.decline(twoLaneReason(<the same form>), whole); }`, and in
  `toInstantKept` the same with `TwoLaneForm.KEPT`. `toLocal` gains the statement with
  `TwoLaneForm.TO_LOCAL` at the same place: after its test that the zone is served, before
  the node is built. In each of the three methods the branch that folds a fixed offset
  other than UTC starts with the statement for `TwoLaneForm.FIXED`. The UTC branch is not
  touched. `TWO_LANE_LOCAL_RULE_REASON` is deleted.

**In the baselines** (part c, or row 690's part e: section 13, edit 4).
`VarkaTimestampBaselineBase` gains, after `grid`:

    /**
     * The forms a session that refuses two-lane conditions leaves to the row path, as their
     * lines of forms() write them, by the zone they are refused under (VARKA-689). The
     * queries suites hold both lists to VarkaZoneCompiler.TWO_LANE_REFUSED: they plan every
     * section with the refusal on.
     */
    static final List<String> TWO_LANE_REFUSED_DST =
        List.of("CAST(d@ AS TIMESTAMP)", "ts@ + INTERVAL '1' DAY");
    static final List<String> TWO_LANE_REFUSED_FIXED = List.of();

    /**
     * {@code all} as a session plans it that refuses two-lane conditions where
     * {@code declines}: a section of a form of {@link #TWO_LANE_REFUSED_DST} under
     * {@link #DST}, or of {@link #TWO_LANE_REFUSED_FIXED} under {@link #FIXED}, is the row
     * path's.
     */
    static List<Timed> refusing(List<Timed> all, boolean declines) {
      if (!declines) {
        return all;
      }
      var out = new ArrayList<Timed>();
      for (Timed t : all) {
        List<String> forms = t.zone().equals(DST) ? TWO_LANE_REFUSED_DST
            : t.zone().equals(FIXED) ? TWO_LANE_REFUSED_FIXED : List.<String>of();
        boolean refused = false;
        for (String select : forms) {
          String shown = select.replace("@", "").replace("#", "");
          refused |= t.title().startsWith("TIMESTAMP " + shown + ", " + t.zone() + ", ");
        }
        out.add(refused
            ? new Timed(t.zone(), t.title(), t.query(), Fusion.NONE, BatchCheck.KERNEL) : t);
      }
      return out;
    }

and each class whose `timed` is `return grid(VIEW, forms());` returns `refusing(grid(VIEW,
forms()), declinesLongConditions)`. A twin's title starts with `TIMESTAMP_NTZ twin`, so no
twin is touched.

**The record.** `sql/varka/plans/m9/results/VARKA-689-two-lanes.txt`: comment lines that
start with `#`, then one line a conversion, in the order `TO_LOCAL`, `EARLIER`, `GAP_END`,
`KEPT`, `FIXED`, of five fields separated by tabs:

    <form> TAB <kept|refused> TAB <sections> TAB <least ratio> TAB <the section of the least ratio>

Before the measurement (part c) it names no run, and each line's last three fields are `0`,
`-` and a text that starts with `not measured`. After it (part d) it is what
`dev/varka_zone_two_lane.py --render` prints from the runs. Each run has two comment lines
there: `# runs: <path of its results file>`, and under it `#   on <the CPU>, <the JVM
options>`, both read from the run's provenance file. Part c commits this text, tabs between
the fields:

    # The zone conversions at two 64-bit lanes on x86 (VARKA-689): kept as kernels, or
    # refused and left to the row path. Rendered by dev/varka_zone_two_lane.py --render
    # from the runs named below, and held to them by its --check. A form is refused when
    # a section of it reads under 0.95 times the row path's rate, by the least best
    # time of each arm over the runs. A verdict is of the runs named here and of their
    # day: nothing measures again on a schedule, so `refused` does not say that a form
    # loses today (VARKA-689.md 4.10).
    # form	verdict	sections	least ratio	the section of the least ratio
    TO_LOCAL	kept	0	-	not measured: fuses there since VARKA-657
    EARLIER	kept	0	-	not measured: fuses there since VARKA-690
    GAP_END	refused	0	-	not measured: the owner's decision of 27 November 2026 (VARKA-690)
    KEPT	refused	0	-	not measured: the owner's decision of 27 November 2026 (VARKA-690)
    FIXED	kept	0	-	not measured: fuses there since VARKA-657

**Three holders, three checks.**

| what must agree | the check | where it runs |
| :--- | :--- | :--- |
| the compiler's set and the record's `refused` lines | `VarkaZoneCompilerSuite`, "the conversions the compiler refuses at two lanes are the record's" | every Build |
| the baselines' two lists and the compiler's set | each class's queries suite, "each section of ... fuses as its class lists it", which plans every section with the refusal on | every Build |
| the record and the reading it names | `python3 dev/varka_zone_two_lane.py --check`: it renders the record again from the runs it names and compares, and it refuses runs that are no reading (4.10) | `dev/varka_precommit.sh` and the documents job of CI |

### 4.10 The rule that turns a reading into a verdict

- **The reading.** Two runs of each of the three classes
  (`VarkaTimestampFieldsBaselineBenchmark`, `VarkaTimestampCastsBaselineBenchmark`,
  `VarkaTimestampArithmeticBaselineBenchmark`) on an AMD EPYC 7763 with
  `-XX:MaxVectorSize=16` and the two-lane refusal turned off: by
  `-Dspark.sql.codegen.varka.declineTwoLaneConditions=false` on route A, by
  `-Dvarka.baseline.twoLaneRefusal=false` on route B (section 3, fact 11; part c's B0 says
  which route master has). With the refusal off nothing declines by name, and on that host
  the session lays `zoneStepMask` false (4.2): every conversion is fused with its steps with
  no mask. The class's fuse check agrees: it lists the classes of a session that refuses
  nothing.
- **What the tool refuses as no reading.** `--render`, and `--check` through it, stop with
  a message and exit 1 unless: each run has its provenance file beside it (`-results.txt`
  becomes `-provenance.txt`); that file's `cpu:` line holds `AMD EPYC 7763`; its `inputs:`
  line holds `-XX:MaxVectorSize=16` and one of the two properties above; its `run:` line
  names a workflow run, and no two files name the same one; the runs hold exactly 32, 8,
  8, 8 and 14 sections of the five conversions; and every one of those 70 sections is in
  at least two runs. So a record made from one run, from two copies of one run, from a run
  that lost a section, from a wide run or from a run with the refusal on cannot be
  committed. `--ratios`
  prints the five lines of any runs and holds them to nothing: it is how the Arm run is
  read (5.4, step 8), and it writes no record.
- **The sections.** A section belongs to a conversion by its title: `TIMESTAMP <kernel>,
  America/Los_Angeles, ...` to the kernel's conversion of 4.9's table, `TIMESTAMP <kernel>,
  +01:00, ...` to `FIXED`. A lead form has four cells under `America/Los_Angeles` and one
  under `+01:00`, each with and without nulls. So `TO_LOCAL` has 32 sections, `EARLIER`,
  `GAP_END` and `KEPT` 8 each, and `FIXED` 14: 70 in all. Every other section is not read.
- **The ratio** of a section: the least `Best Time(ms)` of `spark row path (varka off)` over
  the two runs, times 100, divided by the least `Best Time(ms)` of `varka on` over the two
  runs, rounded down to a whole percent. The house rule's "run again and compared by
  minimums", applied to every section so that no one chooses which to run again.
- **The verdict.** A conversion is `refused` when one of its sections reads under 95
  percent, and `kept` otherwise. Nothing else is weighed.
- **Why 95 and not 100** (question 3 of section 15). A section whose every batch declines at
  run time (the ten-year cell, until rows 686 and 693) times the row path on both arms, and
  two runs leave a few percent of noise. At 100 such a section would refuse a conversion by
  a coin flip. The house reads a tie as within 1.05 (`VARKA-690.md` 7, predictions 4 and
  6). Such a section's true ratio is a little under 1, not 1: its `varka on` arm also pays
  the range pass and the decline of each batch. Eight of `TO_LOCAL`'s 32 sections are such
  cells, and two of each of `EARLIER`, `GAP_END` and `KEPT`. If one of them reads 93 or 94,
  the rule refuses the whole conversion for it, with no judgement; prediction 11 says they
  read 95 to 105, and the owner sees the risk at question 3.
- **What follows a verdict**, mechanically (part d): the record is rendered; the compiler's
  set holds exactly the `refused` forms; the baselines' lists hold exactly the forms whose
  sections the queries suites then report as `listed as WHOLE but plans as NONE`; the three
  checks of 4.9 pass.
- **When a later reading disagrees.** Any row that measures again (row 691 does) renders the
  record from its own two runs and replaces the `# runs:` lines. If a verdict moves, the
  check of the compiler's set fails until the set follows. A refused conversion can only be
  seen to win in a reading of this configuration, because under the refusal it times the
  row path: so a reading that could flip a verdict is always this one, and it is one
  command (5.4).
- **What reads the record again, and what does not.** That a refused form has started to
  win is a measurement, not a property of the code: no unit test can see it, and this plan
  adds none that pretends to. The tool's self-test holds the mechanism (a record whose
  runs now read a refused form as winning fails `--check`), on made-up times. In the
  repository nothing measures on a schedule: `benchmark.yml` and the surface's workflow
  start by dispatch only (section 3, fact 10), and the weekly and nightly workflows run
  suites, the option matrix, the fuzzers and the sweeps, never a benchmark. Three things
  bring a verdict back to a reading:
  - row 691 runs this reading again, with every form, and renders the record from it;
  - row 680, filed and not planned, is the row that would add a nightly: its gate times
    each coverage row at 128 and 256 bits on the EPYC 7763 as a session plans it. As
    filed it sees a kept conversion start to lose, since a kept row is a kernel there. It
    cannot see a refused one start to win: under the refusal that row times the row path
    on both arms and reads 1. An arm of that nightly with the refusal off, over the rows
    of the refused conversions, would close the gap; no row and no catalogue item holds
    it today (question 6 of section 15);
  - a row that changes what a refused conversion's kernel holds runs the reading again as
    part of its own measurement: `m10/SCOPE.md` item 101 (the lowering without masks for
    every long-lane kernel) is the one filed today. Nothing enforces this but that row's
    plan.

  So a reader of the record must not conclude from a line `refused` that the form is
  slower than the row path today, on another CPU model, or after a later row changed its
  kernel; nor from `kept` that it still wins. A line says what the named runs read on
  their day, at the commit their provenance files name. The record's header says so.

### 4.11 What declines by name, and where each text prints

Traced from `VarkaNodeCompiler.compileNode`, as `VARKA-690.md` 4.11 traces it: the tag
`KnownFloatingPointNormalized` is not one of these; `floatingDecline` answers only for an
expression with a floating side; then the chain `FAMILIES` in order, in which no link before
`ZONE` claims a field or a cast over a `TimestampType` or a `TimestampAddInterval` over one.
`VarkaZoneCompiler.arm` claims; `inFamily` prints the family's off reason if the switch is
off. Then the method of 4.9, whose tests run in this order: no zone on the node
(`NO_ZONE_REASON`); an id that does not resolve; then, for a fixed zone, the fold, which
returns and so ends the method: under UTC the operand itself, under any other fixed offset
the `FIXED` statement first and then the sum. Only a zone with changes goes on: the
operand's test (`COMPUTED_OPERAND_REASON`, or row 688's `unrangedReason(name)`); the zone
served (`unservedReason(id)`); and then the two-lane test of this row for `TO_LOCAL`,
`EARLIER`, `GAP_END` or `KEPT`. So those four texts print only for a zone with changes that
is served and whose operand has a range. `twoLaneReason(FIXED)` prints under a fixed offset
other than UTC for any operand, a computed one included: the fold asks for no range, so no
operand's test stands before it.

| expression, on a session with the two-lane refusal on | zone | text |
| :--- | :--- | :--- |
| `CAST(d AS TIMESTAMP)` | `America/Los_Angeles` | `twoLaneReason(GAP_END)`: `a local time resolved to an instant by the gap's end, on an x86 host whose 64-bit species have two lanes or fewer: refused there, since its kernel was not measured faster than the row engine (VARKA-689)` |
| `ts + INTERVAL '1' DAY` | `America/Los_Angeles` | `twoLaneReason(KEPT)`, unless `TO_LOCAL` is refused: then `twoLaneReason(TO_LOCAL)`, since the arm calls `toLocal` first |
| `hour(ts)`, `CAST(ts AS DATE)`, `CAST(ntz AS TIMESTAMP)` | `America/Los_Angeles` | none while `TO_LOCAL` and `EARLIER` are kept: fused |
| any of the seven, and the same over a computed operand such as `hour(greatest(ts, ts2))` | `+01:00` | none while `FIXED` is kept; else `twoLaneReason(FIXED)` |
| any of the seven | `UTC` | none: the twin's kernel |

At run time: `STATUS_BATCH_ARGS` for a batch of a wide span (4.6). Nothing else is new.

### 4.12 If every conversion is refused

Then at 128 bits on x86 every zoned kernel under a zone with changes or a fixed offset
declines by name, and its sections time the row path on both arms. The row has still
delivered, and its done-when holds:

- the reading that says so, committed, with the one command that repeats it;
- the record, and the three checks that hold the compiler, the baselines and the reading
  together, so that a later gain (row 691's forms, a faster field) flips a line and not a
  design;
- the steps with no mask behind an option, proved and tested on every host, which the Arm
  runner and the matrix run, and which the nanosecond rows inherit;
- the span rule, under every option;
- G7's 128-bit clause for these kernels, by decline (decision 15).

Under UTC nothing of this row acts: a UTC section that loses at 128 bits is its
`TIMESTAMP_NTZ` twin's loss, and part d files it for the lead with its number.

### 4.13 Edge cases

| case | what happens | what holds it |
| :--- | :--- | :--- |
| a null row | the lane computes on whatever the storage holds; a sign word there may be wrong, and the output's validity hides it. No pass reads it and no step can trap | part a's test 1 and part b's test 1 (null rows over `Long.MIN_VALUE` and `Long.MAX_VALUE`, in either operand of the kept rule); the sweep's null-first body |
| every step of the form real | eight changes in one batch under `STEPS_8`: the form's full width, no padded step | part a's test 1 and part b's test 1, their cases of eight steps |
| the ends of a long | a batch within 18 hours of an end (36 on the local line) declines before any step, as for the masked form | row 684's test 6 and row 690's test 18, under the matrix configuration |
| a batch with no live row | the neutral arguments: every step padded; every output null | part a's test 1 and part b's test 1, their all-null batches |
| a wide span | 4.6: declined with `STATUS_BATCH_ARGS` | part a's tests 4 and 6; part b's test 3 |
| two lanes of 64 bits | the row's subject: no mask call is emitted; the suites run there through `isa=w128` and the gate's 128-bit step | part a's test 2 (no `VectorMask` call) |
| AVX2 against AVX-512 | the same bytes; with no mask there is no mask register to choose | the Build's runners |
| aarch64 at two lanes | the default stays masked: C2 lowers masks there. The option's `false` arm runs there under the matrix | `isa=arm`; part d's Arm reading |
| a kept offset that is no offset of the zone, `Long.MIN_VALUE` among them | the difference word is -1, so the step answers as `EARLIER` | part b's test 2 |
| windows that touch | each window's words read the local time, never the running answer | part b's test 1, the touching gaps |

### 4.14 What is out of this row

| what | the row that holds it |
| :--- | :--- |
| the A/B arms of the forms, masked against no mask at each width, the defaults by width, the surface run with the kernels on | 691 |
| a lowering without masks for any other long-lane kernel: a field's arithmetic, the fold's range guard, a checked sum | `m10/SCOPE.md` item 101 |
| a table form with no mask, `TABLE_SCALAR` | 686 |
| the gate over every coverage row at each width | 680 |
| a refusal by lane count on aarch64 | none: section 15, question 4 |
| the masked steps, the records, the view `dataOf` | 682, 690 |
| the nanosecond forms | 673 to 675 |

## 5. The parts

Four pull requests, each under 1000 counted lines (`python3 dev/varka_msg_check.py size`).
The family plan budgeted one (`VARKA-657.md` 8); the local rules, the record and the
measurement are parts of their own so that each merges green and useful alone.

| part | title | branch | needs | gives | lines |
| :--- | :--- | :--- | :--- | :--- | ---: |
| a | `[VARKA-689] Add option zoneStepMask and ToLocal's steps with no mask` | `varka-689a-step-mask` | row 657, parts A to D (part B with section 13's edit 8); row 684, part 2; row 690, part b | the option, the conf, the session's default, `ToLocal`'s steps, the span rule of the instant line | 670 |
| b | `[VARKA-689] Lower the local rules' steps with no mask` | `varka-689b-local-steps` | a; row 690, part c | the three local lowerings, `zone_local_step.smt2` | 530 |
| c | `[VARKA-689] Record which zone conversions a two-lane host refuses` | `varka-689c-record` | b; row 690, parts d, e and f; row 655, parts B and C | `TwoLaneForm`, the set, the reasons, the baselines' lists, the record, the tool, its checks | 760 |
| d | `[VARKA-689] Results: the zone conversions at two lanes, kept or refused` | `varka-689d-results` | c | the reading, the verdicts, the close | 80 |

**How the estimates were counted,** file by file. Part a: `VarkaEmitOptions` 30,
`VarkaEmitOption` 12, `SQLConf` 22, `VarkaSessionEmit` 35, `VarkaZoneLowering` 45,
`VarkaEmitBudget` 4, `VarkaBatchArgs` 50, `VarkaEvaluatorBase` 2, `VarkaProofFiles` 25, the
docs 6; `VarkaEmitOptionSuite` 14, `VarkaEmitterZoneSuite` 170, `VarkaBatchArgsSuite` 45,
`VarkaLocalLineSuite` 45, `VarkaEmitterBudgetSuite` 4, `VarkaEvaluatorComponentsSuite` 85
(the helper, the edited assertions, the new test and the round trip),
`VarkaTimestampZoneSuite` 45, the pins 12: about 670. Part b: `VarkaZoneLowering` 130,
`Slots` 10, `VarkaProofFiles` 135, the proofs' README 1, `VarkaEmitterBudgetSuite` 12,
`VarkaEmitterZoneSuite` 180, `VarkaLocalLineSuite` 60: about 530. Part c: `VarkaZoneCompiler`
60, the baseline base and its three classes 50 (58 on route B), the tool 514, the
pre-commit and the workflow 11, `VarkaZoneCompilerSuite` 70, the suites that named the old
constant 15, the docs 10, this file 30: about 760; the record is under `results/` and is
not counted. Part d: the set and the lists 10, this file's outcome 60, `SKILLS.md` 10:
about 80; results files are not counted. Parts a, b and d are under 750. Part c is over
it, and the tool is two thirds of it, so its sheet has a cut line (5.3).

**Side by side.** None: each part needs the one before it. Part a can start before row
690's part c, and part d's dispatches are the lead's.

### 5.0 What every sheet shares

- Work in the worktree and on the branch the lead names. Each part starts from master after
  the parts it needs have merged.
- Every shell that builds or tests starts with:

      export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
      export SPARK_LOCAL_IP=127.0.0.1
      export VARKA_HEAVY_LOCK=<the lock file the lead names>
      V=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
      C=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen
      E=sql/core/src/main/java/org/apache/spark/sql/execution
      TJ=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
      CJ=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen
      TS=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka
      XS=sql/core/src/test/scala/org/apache/spark/sql/execution
      XJ=sql/core/src/test/java/org/apache/spark/sql/execution
      B=$XJ/benchmark
      SQ=sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala
      M9=sql/varka/plans/m9

- Wrap every `build/sbt`, `dev/varka_emit.sh`, `dev/varka_matrix.sh`, `dev/lint-java`,
  `dev/lint-scala` and `dev/varka_errorprone.sh` call in `flock "$VARKA_HEAVY_LOCK"`. Call
  `dev/varka_regen_generated.sh` and `dev/varka_push_check.py` bare: they take the lock.
- Run B0 before any edit. A line marked `# N` must print `N`; a line marked "note" prints
  what a later step names. A count that differs is a stop: report the command and its
  output. A line that ends in `&& echo ... || echo "STOP: ..."` prints one of its two
  texts: the second is a stop, and its text says why. The B0 lines over master's own files
  were run on 28 November 2026 and printed what they say. Those over rows 682, 684, 685,
  690 and 655 could not be run: each is copied from that row's merged plan.
- A JUnit class passes when sbt prints `Passed: Total N, Failed 0, Errors 0, Passed N`. A
  ScalaTest run passes with `Tests: succeeded N, failed 0` and no `*** FAILED ***`. Where a
  sheet adds tests to a suite of another row, B0 notes the suite's count of declarations
  `n` (`grep -c '@Test' <file>`) and the sheet says `n + k`.
- A planted fault is planted after the part's commit, the named command is rerun, the named
  test must fail by its assertion, and the fault is reverted with `git checkout -- <file>`.
- Comments: read the class comment and the Javadoc of every method a step edits. A sentence
  that says a zone step is a compare and a masked add becomes "a compare and a masked add,
  or, where `zoneStepMask` is off, a sign word and an AND". A sentence that says `dataOf`
  returns the zone itself for a `ToLocal` becomes "a view of the instant line, which counts
  a span that does not fit a long as more changes than a form covers".
- **Uncompiled Java and Scala in this plan,** and what a builder may change in it. The
  blocks of 4.6 (`InstantLine`), 4.9 (`TwoLaneForm`, `refusing`), 5.1 (the helper and the
  round trip of `VarkaEvaluatorComponentsSuite`) and 5.3 (route B's block) were not
  compiled. What they name:

| name | what it is | where it is defined (a line number is master `9e7b1612402`'s) |
| :--- | :--- | :--- |
| `VarkaZoneData` and its methods | the interface `InstantLine` implements | not on master: `VARKA-682.md` 4.7; row 686 may have added `toLocalTable` |
| `VarkaChronoZoneChanges.MANY` | an `int`, 4096 | not on master: `VARKA-678.md` 5.2; B0 prints how `LocalLine` names it |
| `DeclineSink.declinesTwoLaneChecks()` | package-private, `boolean` | `$C/DeclineSink.java`, line 163 |
| `VarkaAdmission(Set<VarkaFamily> familiesOff, boolean declineTwoLaneConditions)` | a public record | `$V/VarkaAdmission.java`, line 45 |
| `Timed(String zone, String title, String query, Fusion fusion, BatchCheck check)`, `DST`, `FIXED`, `grid` | the base's record, two zone ids, the grid | not on master: `VARKA-655.md` 4.2 |
| `Fusion.NONE`, `BatchCheck.KERNEL` | public nested enums of `VarkaSqlBenchmarkBase`, in scope by inheritance | `$B/VarkaSqlBenchmarkBase.java`, lines 334 and 345 |
| `VarkaEmitOption.Flag`, `FlagTag`, `Subject.DEFAULTS`, `Reason.MACHINE` | the options table's records | `$V/VarkaEmitOption.java`, lines 239, 127, 149 and 73 |
| `VarkaSessionEmit.bind`, its `BooleanSupplier twoLaneMasksFallBack` | the binding of 4.2 | `$E/VarkaSessionEmit.java`, line 188 |
| `VarkaEmitterFixtures.emit(List<VarkaVectorIR>, int, int, VarkaEmitOptions)` and its `Emitted.bytes()`; `VarkaEmitterTestSupport.invocationCount(byte[], String, String)`, `codeSize(byte[], String)` | public test helpers | `$TJ/VarkaEmitterFixtures.java`, lines 92 and 83; `$TJ/VarkaEmitterTestSupport.java`, lines 189 and 118 |
| `VarkaKernelPlan.twoLaneMasksFallBack(VarkaHostIsa.Reading host)` | public static, `boolean`; the suite imports the class already | `$V/VarkaKernelPlan.java`, line 267 |
| `VarkaHostIsa.Reading(String arch, String features, String failure, int longBits)`; `VarkaHostIsa.host()` | a public record and the JVM's own reading; the suite builds both already | `$V/VarkaHostIsa.java`, line 78 |
| `java.io.ObjectOutputStream`, `ObjectInputStream`, `ByteArrayOutputStream`, `ByteArrayInputStream`, `ObjectStreamClass.lookup(Class).getSerialVersionUID()` | the JDK's; written with their full names, so no import | - |
| `SparkSession.conf().set(String, String)`; `SQLConf.VARKA_DECLINE_TWO_LANE_CONDITIONS().key()` | route B's block, in Java: the session's runtime conf, and the conf's entry | `sql/api/src/main/scala/org/apache/spark/sql/RuntimeConfig.scala`, line 37; `$SQ`, line 3609; the base imports `SQLConf` and `SparkSession` already (`VARKA-655.md` 4.2) |
| imports | `java.util.Collections`, `java.util.EnumSet`, `java.util.Set` in `VarkaZoneCompiler`; `java.util.ArrayList` and `java.util.List` are imported by the base already (`VARKA-655.md` 4.2) | - |

  The repair procedure, if a block does not compile: a builder may add an import, add a
  delegating method to `InstantLine` for a method the interface has on the day, rename a
  local, and write `MANY` as B0 printed it. It may not change a signature this plan gives,
  a constant's value, a reason's text, the order of the enum, or the test in
  `InstantLine.changes`. Anything else is a stop, with the compiler's message.
- Before the commit: `dev/varka_precommit.sh`; `dev/varka_errorprone.sh --master "$(git
  rev-parse origin/master)"` and `dev/lint-java`; `dev/lint-scala` where Scala changed;
  `python3 dev/varka_plan_lint.py --check --offline`. After it: `python3
  dev/varka_push_check.py`, which prints the size and must exit 0.
- One local commit a part (two in part d). Its subject is the part's title; its body ends
  with one attribution line, `Generated-by: <tool> (<model>)`. No push, no pull request, no
  dispatch: those are the lead's.
- The row's line in `$M9/PLAN.md` reads `**Planned**` once this plan has merged. Part a
  makes it `**Started**` and names the parts left; part d makes it `**Done**`. Each part
  adds what it built to section 11 of this file.
- Stop also on: a test that should pass and fails; a generated file that moves where the
  sheet says it does not; a size over 900; a B0 "note" that shows a shape no step covers.

### 5.1 Part a: the option, the session's default and `ToLocal`'s steps

**Expectations.** After it: the option exists and renders as 4.1 says; a session on a
two-lane host emits `ToLocal`'s steps with no mask; `dataOf` gives a `ToLocal` the view of
4.6; `zone_step.smt2` holds 20 checks. Nothing changes at the defaults: no census cell, no
byte of `emitted_bytes.json`, `coverage.json` or `width_audit.json`.

**B0.**

    git branch --list 'varka-689a-step-mask' | wc -l                                          # 0
    grep -c 'zoneStepMask' $V/VarkaEmitOptions.java $V/VarkaEmitOption.java $E/VarkaSessionEmit.java $SQ   # 0, four times
    grep -c 'STEP_NO_MASK_MAX_SPAN = Long.MAX_VALUE;' $V/VarkaZoneLowering.java               # 1: row 684's part 2
    grep -c ': expect unsat")' sql/varka/proofs/zone_step.smt2                                # 11
    grep -c ': expect sat")' sql/varka/proofs/zone_step.smt2                                  # 7
    grep -c 'public static VarkaZoneData dataOf(' $V/VarkaBatchArgs.java                      # 1: row 690's part b
    grep -n 'MANY' $V/VarkaBatchArgs.java                                                     # note: how LocalLine names the constant
    grep -n 'case .*ToLocal' $V/VarkaBatchArgs.java                                           # note: the arms of count and of dataOf
    grep -n 'static void emitToLocal(' -A14 $V/VarkaZoneLowering.java                         # note: how the form is read
    grep -n 'static int laneCalls(' $V/VarkaZoneLowering.java                                 # note: the signatures
    grep -n 'laneCalls(' $V/VarkaEmitBudget.java                                              # note: weightOf's calls
    grep -n 'widest = ' $E/VarkaEvaluatorBase.java                                            # note: one line if row 685's part c merged, else nothing
    grep -n '^    [A-Za-z.<>]* [a-zA-Z]*) {$' $V/VarkaEmitOptions.java | head -1              # note: the record header's last component
    grep -q 'VarkaVectorIR.Zoned.class' $TJ/VarkaMatrixRuns.java && echo "Zoned is a compiled kind" || echo "STOP: COMPILED_NODE_KINDS lacks VarkaVectorIR.Zoned.class (VARKA-657-PARTS.md 5.2, step 8), so zoneStepMask=false would be no matrix configuration"
    grep -q 'int longBits' $E/VarkaSessionEmit.java && echo "form L" || echo "form M"         # note: form M is five components (master of 28 November 2026); form L has row 697's longBits, six
    sed -n '/^public record VarkaSessionEmit(/,/implements Serializable/p' $E/VarkaSessionEmit.java   # note: the header; it ends `@Nullable Boolean mathUlpContract)` in form M and `@Nullable Boolean mathUlpContract, int longBits)` in form L. Any other end is a stop
    sed -n 's/^  private static final long serialVersionUID = \([0-9]*\)L;$/\1/p' $E/VarkaSessionEmit.java   # note: N, one number: 4 in form M, 5 in form L
    grep -c 'Option<Object> contract = conf.varkaMathUlpContract();' $E/VarkaSessionEmit.java # 1
    grep -n 'twoLane.isDefined() ? (Boolean) twoLane.get() : twoLaneMasksFallBack.getAsBoolean(),' $E/VarkaSessionEmit.java   # note: one line, bind's fourth argument
    grep -n 'contract.isDefined() ? (Boolean) contract.get() : null' $E/VarkaSessionEmit.java # note: one line, the end of bind's record: `: null);` in form M, `: null, longBits);` in form L
    grep -n '&& mathUlpContract == null' $E/VarkaSessionEmit.java                             # note: one line, the end of apply's early return
    grep -n '^    this(useAVX, exactRefusalStatus, familiesOff, declineTwoLaneConditions, null' $E/VarkaSessionEmit.java   # note: one line, the four-argument constructor's call of the canonical one
    grep -n '=== new VarkaSessionEmit(\|=== VarkaSessionEmit.DEFAULTS)\|assert(session ===$\|assert(planned ===\|\.apply([a-zA-Z]*) ===\|\.apply([a-zA-Z]*) eq ' $XS/VarkaEvaluatorComponentsSuite.scala   # note: the whole-value assertions: seven lines in form M, nine in form L
    grep -c 'ObjectOutputStream' $XS/VarkaEvaluatorComponentsSuite.scala                      # note: 0 in form M, 1 in form L
    grep -c '  test("' $XS/VarkaEvaluatorComponentsSuite.scala                                # note: n6
    grep -c 'val VARKA_MATH_ULP_CONTRACT =' $SQ                                               # 1
    grep -n -A12 'val VARKA_MATH_ULP_CONTRACT =' $SQ | grep 'version'                         # note: the version the new conf takes
    grep -c 'Seq("decimalAbsLanewise" -> classOf\[VarkaVectorIR.DecimalColumn\],' $TS/VarkaEmitOptionSuite.scala   # 1
    grep -n '"zoneForm"' $TS/VarkaEmitOptionSuite.scala                                       # note: the two lists zoneForm is in
    grep -n '@Test\|invocationCount(\|codeSize(\|ZoneForm.STEPS_\|ZoneForm.LADDER' $TJ/VarkaBatchArgsSuite.java $TJ/VarkaEmitterZoneSuite.java $TJ/VarkaLocalLineSuite.java   # note: the test methods, and which hold a form with steps and a count: 4.8's rule picks the pins from this list
    grep -n 'withZoneForm' $TJ/VarkaEmitterZoneSuite.java                                     # note: the options of each kernel test
    grep -n 'the stub itself\|assertSame' $TJ/VarkaLocalLineSuite.java                        # note: test 13's clause on a ToLocal
    grep -c 'dataOf' $TJ/VarkaZoneKernels.java $TJ/VarkaLocalLineKernels.java                 # note: the fillers that take the view
    grep -n 'Long.MIN_VALUE\|Long.MAX_VALUE' $XJ/VarkaTimestampZoneSuite.java                 # note: the tests of the ends
    grep -c '@Test' $TJ/VarkaEmitterZoneSuite.java $TJ/VarkaBatchArgsSuite.java $TJ/VarkaLocalLineSuite.java $XJ/VarkaTimestampZoneSuite.java   # note: n1, n2, n3, n4
    grep -n 'zoneForm' docs/sql-varka.md                                                      # note: where the docs name the form, or nothing

**Steps.**

1. `$V/VarkaEmitOptions.java`: the component `boolean zoneStepMask` after the record's last
   component, and at each site that component has (its `@param`, `DEFAULTS`, `toBuilder`,
   the `Builder`'s field and setter, `build()`, its `with` method) the twin line for
   `zoneStepMask` directly after it, as 4.1 gives them. In `DEFAULTS` the value is `true`.
2. `$V/VarkaEmitOption.java`: the entry of 4.1, last in `TABLE`.
3. `$SQ`: the conf and its accessor (4.2), with the version B0 printed.
4. `$E/VarkaSessionEmit.java`: 4.2, by these rules, each worked for the two forms B0 can
   print. `N` is the serial version B0 printed.

| what | the rule | form M (today's master) | form L (row 697 merged) |
| :--- | :--- | :--- | :--- |
| the header | the new component last, on a line of its own: the line before it ends in a comma | `    boolean declineTwoLaneConditions, @Nullable Boolean mathUlpContract,` and on the next line `    @Nullable Boolean zoneStepMask)` | `    boolean declineTwoLaneConditions, @Nullable Boolean mathUlpContract, int longBits,` and on the next line `    @Nullable Boolean zoneStepMask)` |
| the serial version | `N + 1` | `private static final long serialVersionUID = 5L;` | `private static final long serialVersionUID = 6L;` |
| a new constructor of the old canonical shape, public, placed after the four-argument one, with the Javadoc "The settings with a zone node's step form left to the base options." | its parameters are the old header's, its body one call of the new canonical constructor with `null` last | `this(useAVX, exactRefusalStatus, familiesOff, declineTwoLaneConditions, mathUlpContract, null);` | `this(useAVX, exactRefusalStatus, familiesOff, declineTwoLaneConditions, mathUlpContract,` and on the next line `        longBits, null);` |
| the four-argument constructor | not edited: the line B0 printed now calls the new constructor of the old shape | - | - |
| `bind` | the two statements of 4.2 before its `return`; the fourth argument takes `hostFallsBack`; the new last argument before the closing `);` of the line B0 printed | `contract.isDefined() ? (Boolean) contract.get() : null,` and on the next line `        stepMask.isDefined() ? (Boolean) stepMask.get() : (hostFallsBack ? Boolean.FALSE : null));` | `contract.isDefined() ? (Boolean) contract.get() : null, longBits,` and the same next line |
| `apply` | ` && zoneStepMask == null` at the end of the early return's condition, before `) {`; and the `if` of 4.2 before `return options.build();` | `        && mathUlpContract == null && zoneStepMask == null) {` | `        && mathUlpContract == null && longBits == 0 && zoneStepMask == null) {` |
| the `@param` | 4.2's text, after the last `@param` of the class comment | - | - |
| `bind`'s Javadoc, which the edit makes false | its sentence becomes: "The binding itself. The host's two-lane answer is asked once: it is the refusal's default where the session does not set the refusal, and the zone step form's where the session does not set that." | - | - |

   Every edited line stays within 100 columns: the longest is the new constructor's call
   in form M, 99.
5. `$V/VarkaZoneLowering.java`: `emitToLocal` as 4.3; `laneCalls(node, form, stepMask)` as
   4.5, with the `ToLocal` row and, for the two local records, `laneCalls(node, form)`.
6. `$V/VarkaEmitBudget.java`: at each call B0 printed in `weightOf(node, options, narrow)`,
   the third argument `options.zoneStepMask()`.
7. `$V/VarkaBatchArgs.java`: `InstantLine` (4.6) after `LocalLine`; `dataOf`'s arm for a
   `ToLocal` returns `new InstantLine(zone)`. `$V/VarkaZoneLowering.java`: the comment of
   `STEP_NO_MASK_MAX_SPAN` becomes "The greatest span of a batch, its greatest instant less
   its least, for which a ToLocal step with no mask is exact ({@code zone_step.smt2}).
   {@code VarkaBatchArgs}'s view of the instant line declines a wider batch that holds a
   change: its test, a wrapped difference below zero, is this bound (check 20)."
8. `$E/VarkaEvaluatorBase.java`: if B0 printed a `widest = ` line, its argument becomes
   `sessionEmit.declineTwoLaneConditions() && emitOptions.zoneStepMask()`.
9. `$TJ/VarkaProofFiles.java`: checks 19 and 20 of 4.7 at the end of `zoneStep()`, the
   number of check 20 from the constant. Then command 1 renders the file.
10. If B0's last line printed a place: after the sentence that names the form, "On an x86
    host whose 64-bit vectors have two lanes, where the JIT compiles no vector mask, a
    kernel applies each change with a sign word in place of a mask
    (`spark.sql.codegen.varka.emit.zoneStepMask`); a batch whose timestamps span more than
    a long of microseconds goes to the row engine." If it printed nothing, no docs edit.
11. The pins (4.8): each test B0 printed that asserts a count of lane calls, a count of
    `VectorMask` calls or a code length, and row 685's test of the cap at two steps if
    `$XS` holds it (`grep -rn 'stops at two steps' $XS $XJ`), gets `.withZoneStepMask(true)`
    on its options. Which tests those are is 4.8's closed rule over B0's list, not a
    reading of each hit.
12. If `$TJ/VarkaZoneKernels.java` printed 0 for `dataOf`: its `production(provider)` fills
    from `VarkaBatchArgs.dataOf(program.node(), provider.of(zoneId))`. Row 690's part g
    makes the same edit; whichever is built second finds it done.
13. The tests below.

**The test plan.**

`VarkaEmitterZoneSuite` (Java, JUnit 5, catalyst; row 682's), five tests. `root` is `new
ToLocal(new ColumnRef(0, LaneType.LONG), 0)`. `noMask(form)` is
`VarkaMatrixRuns.BASE.withZoneForm(form).withZoneStepMask(false)` and `masked(form)` the same
with `true`. A batch runs as `VarkaLocalLineKernels.run(root, options, args, new long[][]
{values}, new boolean[][] {nulls})`, which returns the output's values and, in its second
array, 1 where a row is valid.

| # | test | inputs | expected, and its oracle | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | ToLocal with no mask equals the masked steps on hand-set steps, at each threshold and one microsecond either side, with nulls, and with every row null | `STEPS_2`; `args = {1000, 5000, 3600, 20000, -3600}`; values 4999, 5000, 5001, 19999, 20000, 20001, no nulls. Then the same values with rows 1 and 4 null, the storage under them `Long.MIN_VALUE` and `Long.MAX_VALUE`. Then every row null. Then `STEPS_8` with the same two steps and six padded ones (`Long.MAX_VALUE`, 0), and the values `Long.MAX_VALUE - 64800000000` and `Long.MIN_VALUE + 64800000000` added. Then `STEPS_8` with eight real steps: `args = {1000, 10000, 3600, 20000, -3600, 30000, 1800, 40000, -1800, 50000, 900, 60000, -900, 70000, 60, 80000, -60}`, and the 24 values `at - 1`, `at`, `at + 1` for each of the eight `at`, in that order | no nulls: 5999, 9600, 9601, 24599, 21000, 21001, written in the test, and equal to `masked(form)`'s rows. A null row is invalid and every other row keeps its value. All null: every row invalid. `STEPS_8`, two steps: the six values as before, and the two far values plus 1000. `STEPS_8`, eight steps: each row equals a scalar loop of 2.5's formula written in the test, and the 24 rows, written in the test too, are 10999, 14600, 14601, 24599, 21000, 21001, 30999, 32800, 32801, 42799, 41000, 41001, 50999, 51900, 51901, 61899, 61000, 61001, 70999, 71060, 71061, 81059, 81000, 81001 | a threshold one microsecond late; a logical shift; a delta's sign; padding that fires; a step past the second that reads another step's argument |
| 2 | ToLocal with no mask holds no mask call, and four lane calls a step and one more than the masked form's base | `bytes = VarkaEmitterFixtures.emit(List.of(root), 1, 0, options).bytes()` under `noMask(STEPS_2)`, `noMask(STEPS_8)`, `noMask(ONE)` and `masked(ONE)`; lane calls are `VarkaEmitterTestSupport.invocationCount(bytes, "loopDense0", "jdk.incubator.vector.LongVector")`, mask calls the same with `"jdk.incubator.vector.VectorMask"`, the code length `VarkaEmitterTestSupport.codeSize(bytes, "loopDense0")` | lane calls of `loopDense0`: the count under `masked(ONE)` plus 9 and plus 33; under `noMask(ONE)` the count and the code length of `masked(ONE)`; `VectorMask` calls 0; the code length under `noMask(STEPS_2)` is above `masked(ONE)`'s and that under `noMask(STEPS_8)` above `noMask(STEPS_2)`'s. No length is pinned as a number | a mask left in the form; an option that changes a kernel with no step |
| 3 | the shape keys of the masked form and the form with no mask differ, and the defaults' canonical text holds no tag | `masked(STEPS_2).canonical()`, `noMask(STEPS_2).canonical()`, `VarkaEmitOptions.DEFAULTS.canonical()` | the second ends with `\|zoneStepNoMask)`; the first and the third hold no `zoneStep` | two forms sharing one cached kernel; a tag that moves every committed hash |
| 4 | at a span of Long.MAX_VALUE the steps with no mask equal java.time on every row | `Asia/Kathmandu`; `least = -4611686018427387904`, `greatest = 4611686018427387903`; `args = VarkaLocalLineKernels.filled(root, ZoneForm.STEPS_2, VarkaChronoZone.of("Asia/Kathmandu"), least, greatest)`; the rows `least`, -1577943676000001, -1577943676000000, 504901799999999, 504901800000000, `greatest`; under `noMask(STEPS_2)` | `args` is not null; each row equals `VarkaLocalLineKernels.expected(root, new long[] {x}, new boolean[] {false}, List.of("Asia/Kathmandu")).getAsLong()`; the four middle rows are -1577923200000001, -1577923876000000, 504921599999999 and 504922500000000, written in the test | a sign word that wraps inside the bound |
| 5 | a batch whose span does not fit a long is declined, with a change in it and under either value of the option | `filled(root, form, VarkaChronoZone.of("Asia/Kathmandu"), least, greatest)` for `form` `STEPS_2` and `STEPS_8` and the pairs (-4611686018427387904, 4611686018427387904), (-9222867135054775809, 504901800000000) | null for each: the batch declines | a kernel with no mask answering the second pair, whose first row would be 900000000 too great |

`VarkaLocalLineSuite` (Java, JUnit 5, catalyst; row 690's), one test more and one edit. A
*stub* is the suite's own, as its test 13 has it: a `VarkaZoneData` that records each call,
answers 9 from `changes` and throws from every other method but the ones the test names.

| # | test | inputs | expected | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| new | the view of a ToLocal counts a span that does not fit a long as MANY where the range holds a change, and passes every other question to the zone | `v = VarkaBatchArgs.dataOf(new ToLocal(col0, 0), stub)`; then a second stub that answers 0 from `changes` | `v.changes(5, 6)` is 9; `v.changes(-4611686018427387904, 4611686018427387903)` is 9; `v.changes(-4611686018427387904, 4611686018427387904)` and `v.changes(Long.MIN_VALUE, Long.MAX_VALUE)` are `VarkaChronoZoneChanges.MANY`; the stub saw `changes` four times; with the second stub the last two calls answer 0; `v.toLocalArgs(5, 6, 2, a, 1)` reaches the stub with the same five arguments | a span rule that declines a batch with no step to get wrong; a rule that reads the unwrapped difference |
| edit of test 13 | - | - | the clause "for the `ToLocal` the stub itself comes back" becomes: `dataOf(new ToLocal(col0, 0), stub)` is not the stub, and its `changes(5, 6)` is the stub's 9 | - |

`VarkaEmitOptionSuite` (Scala, catalyst): in the test that holds `"zoneForm" ->
classOf[VarkaVectorIR.Zoned]`, the pair `"zoneStepMask" -> classOf[VarkaVectorIR.Zoned]`
after it; `"zoneStepMask"` in the list of `MACHINE` options that are not drawn in general,
after `"zoneForm"`; and one assertion,
`assert(VarkaMatrixRuns.configurations().asScala.contains("zoneStepMask=false"))`. The
assertion holds only with `VarkaVectorIR.Zoned.class` among `COMPILED_NODE_KINDS`, which
B0's line on it checked; this part does not add the kind (section 13, edit 8, does, in row
657's part B). The test's comment that counts the options one node kind reads ("Five
options ...", or what row 682 made of it) takes the count the list then has.

`VarkaEmitterBudgetSuite` (Scala, catalyst): beside each row of a `ToLocal` under `STEPS_2`
and `STEPS_8`, the same row with `.withZoneStepMask(false)` on its options. The suite holds
each row's lane calls to `weightOf`: 10 and 34.

`VarkaEvaluatorComponentsSuite` (Scala, sql/core): edits of its existing assertions, and
two tests more.

*The edits.* After part a, a session bound by `of(conf)` or by `of(conf, reading)` carries
the step form this JVM's long lanes give that reading (section 3, fact 4). On a wide JVM it
is null and nothing shows; on a JVM of two long lanes (the gate's 128-bit step, the matrix's
`isa=w128`) it is `FALSE`, and `apply` lays `zoneStepMask=false` over the base. So every
assertion B0 listed that compares a whole record or whole options of such a session gets its
expected value from the same fact, by two helpers added to the suite's class body, before
its first test:

      // The zone step form a session bound from `reading` carries where its conf sets none
      // (VARKA-689): no mask where this JVM's long species makes that host's masks fall back.
      private def stepOf(reading: VarkaHostIsa.Reading): java.lang.Boolean =
        if (VarkaKernelPlan.twoLaneMasksFallBack(reading)) java.lang.Boolean.FALSE else null

      // `options` with the step form such a session lays over them.
      private def stepped(
          options: VarkaEmitOptions, reading: VarkaHostIsa.Reading): VarkaEmitOptions =
        if (VarkaKernelPlan.twoLaneMasksFallBack(reading)) options.withZoneStepMask(false)
        else options

The rule, for each line B0 listed:

- The line of `VarkaSessionEmit.DEFAULTS.apply(base) eq base` is not edited: `DEFAULTS` is
  bound from no host.
- An expected record (`=== new VarkaSessionEmit(...)`, `=== VarkaSessionEmit.DEFAULTS`)
  becomes the canonical constructor with every component written and `stepOf(r)` last, `r`
  being the reading the session was bound from: `VarkaHostIsa.host()` for a bare
  `of(conf)`, the planted reading under `setHostForTesting`, the reading passed to
  `of(conf, reading)`.
- Expected options (`.apply(x) === y`) become `=== stepped(y, r)`.
- `.apply(x) eq x` over a bound session becomes two assertions: `=== stepped(x, r)`, and
  `VarkaKernelPlan.twoLaneMasksFallBack(r) || (<the same apply> eq x)`.
- Where the reading is built inside a `def`, `r` is a reading of the same architecture,
  named once in the test: the answer reads only the architecture and this JVM's lanes.
- No assertion on one component is edited, and no call of a constructor in `intercept`.

Worked, form M (today's master; the test "a session's emit settings are bound from its conf
once ..." and the two tests after it). After `val host = ...`, add `val here =
VarkaHostIsa.host()` and `val step = stepOf(here)`. Then, old line by old line:

| the old text, as master has it | the new text |
| :--- | :--- |
| `VarkaEmitOptions.USE_AVX_UNKNOWN, null, java.util.Set.of(), host))` | `VarkaEmitOptions.USE_AVX_UNKNOWN, null, java.util.Set.of(), host, null, step))` |
| `assert(session === new VarkaSessionEmit(2, java.lang.Boolean.TRUE, java.util.Set.of(), host))` | `assert(session ===` and on the next line `  new VarkaSessionEmit(2, java.lang.Boolean.TRUE, java.util.Set.of(), host, null, step))` |
| `assert(session.apply(base) === base.withUseAVX(2).withExactRefusalStatus(true))` | `assert(session.apply(base) === stepped(base.withUseAVX(2).withExactRefusalStatus(true), here))` |
| `assert(VarkaSessionEmit.of(conf).apply(exactBase) === exactBase.withExactRefusalStatus(false)` and its next line `.withUseAVX(2))` | `assert(VarkaSessionEmit.of(conf).apply(exactBase) ===` and on the next line `  stepped(exactBase.withExactRefusalStatus(false).withUseAVX(2), here))` |
| `assert(VarkaSessionEmit.of(conf, unread) === VarkaSessionEmit.DEFAULTS)` | `assert(VarkaSessionEmit.of(conf, unread) === new VarkaSessionEmit(` and on the next line `  VarkaEmitOptions.USE_AVX_UNKNOWN, null, java.util.Set.of(), false, null, stepOf(unread)))` |
| `VarkaSessionEmit.setHostForTesting(new VarkaHostIsa.Reading("amd64", avx2, null, 128))` | `val planted = new VarkaHostIsa.Reading("amd64", avx2, null, 128)` and on the next line `VarkaSessionEmit.setHostForTesting(planted)` |
| `assert(VarkaSessionEmit.of(conf) === VarkaSessionEmit.DEFAULTS)` | `assert(VarkaSessionEmit.of(conf) === new VarkaSessionEmit(` and on the next line `  unknown, null, java.util.Set.of(), false, null, stepOf(planted)))` |

Two comments of the suite are made false, in both forms, and get this text. Each is two
lines, quoted as the file has them. The first, old and then new:

      // The two-lane refusal is resolved from the same reading (VARKA-503), and an x86 reading on a
      // two-lane JVM would set it; set off here, the records compare alike on every host.

      // The two-lane refusal is resolved from the same reading (VARKA-503), and an x86 reading on a
      // two-lane JVM would set it; it is set off here. The zone step form follows that reading
      // whatever the refusal is (VARKA-689), so a whole record is compared with stepOf of it.

The second, old and then new:

      // The two-lane refusal reads this JVM's lanes for a planted x86 host (VARKA-503); set off,
      // the record compares with DEFAULTS under -XX:MaxVectorSize=16 too.

      // The two-lane refusal reads this JVM's lanes for a planted x86 host (VARKA-503), and so
      // does the zone step form (VARKA-689): the refusal is set off, and the record is compared
      // with stepOf of the planted host, so it holds under -XX:MaxVectorSize=16 too.

Worked, form L (row 697 merged; its tests hold `bits`, `driver` and `planned`). The seven
edits above apply with the record's sixth component kept before the new last one: `host,
null, bits, step))` in the first two, `false, null, 256, stepOf(unread)))` for the unread
host, and `unknown, null, java.util.Set.of(), false, null, planned.longBits,
stepOf(planted)))` for the planted one, whose old text is there `assert(planned === new
VarkaSessionEmit(` with its next line. The two `apply` lines keep their
`.withChainCapLongBits(bits)` inside `stepped(..., here)`; the second would then pass 100
columns, so it is written `assert(VarkaSessionEmit.of(conf).apply(exactBase) === stepped(`
and, on the next line,
`  exactBase.withExactRefusalStatus(false).withUseAVX(2).withChainCapLongBits(bits), here))`.
Two more lines, in the test of
the chain cap, with `val x86 = new VarkaHostIsa.Reading("amd64", "avx, avx2, fma", null,
256)` added before its loop:

| the old text, as #1798 has it | the new text |
| :--- | :--- |
| `assert(driver(bits).apply(executor) === executor.withChainCapLongBits(bits))` | `assert(driver(bits).apply(executor) === stepped(executor.withChainCapLongBits(bits), x86))` |
| `assert(VarkaSessionEmit.of(conf, unreadWidth).apply(base) eq base)` | `val unlaid = VarkaSessionEmit.of(conf, unreadWidth).apply(base)`, then `assert(unlaid === stepped(base, unreadWidth))`, then `assert(VarkaKernelPlan.twoLaneMasksFallBack(unreadWidth) \|\| (unlaid eq base))` |

That test's `fuses(...)` assertions read one output's class and are not edited. Its round
trip of `driver(256)` compares the record with itself and is not edited. Each new line is
within 100 columns; the longest, the unread host's in form L, is 100.

A form B0 did not name (another row's component after `longBits`) is built by the same
rule: every component written, in the header's order, the step form last.

*The first new test,* "a session's zone step form is the host's answer unless the session
sets it". It states the lanes, as the suite's test of the two-lane default does, so each
answer is a constant on every JVM:

| inputs | expected |
| :--- | :--- |
| `VarkaSessionEmit.of(conf, new VarkaHostIsa.Reading("amd64", "avx, avx2, fma", null, 128), 2)`, conf with `VARKA_EMIT_USE_AVX` at `VarkaEmitOptions.USE_AVX_UNKNOWN` and nothing else | `zoneStepMask` is `FALSE`; `apply(VarkaEmitOptions.DEFAULTS).zoneStepMask()` is false |
| the same conf and reading with 4 lanes; and `new VarkaHostIsa.Reading("aarch64", "fp, asimd, sve", null, 128)` with 2 lanes | `zoneStepMask` is null; `apply(base).zoneStepMask()` is true, the base's; `apply(base.withZoneStepMask(false)).zoneStepMask()` is false. In form M alone, also `apply(base) eq base`: in form L the stated lanes give the record a width, which `apply` lays over the base |
| the first reading, 2 lanes, the conf `VARKA_EMIT_ZONE_STEP_MASK` set to `"true"`; then `"false"` on the 4-lane reading | `TRUE`, and `apply(base.withZoneStepMask(false)).zoneStepMask()` is true; `FALSE`, and `apply(base).zoneStepMask()` is false |
| the first reading, 2 lanes, `VARKA_DECLINE_TWO_LANE_CONDITIONS` set to `"false"`, the step conf unset | `declineTwoLaneConditions` is false and `zoneStepMask` is `FALSE`: the step form follows the host, not the refusal |
| the aarch64 reading planted by `VarkaSessionEmit.setHostForTesting` (and put back to null in a `finally`), the bare `VarkaSessionEmit.of(conf)`, both confs unset | `zoneStepMask` is null on every JVM: the binding the planner calls reads the planted host |

*The second new test,* "a session's zone step form survives the trip to an executor, and
the record's serial version moved". The record is built by its canonical constructor, so
the test reads no host. `<N + 1>` is the number step 4 wrote: 5 in form M, 6 in form L. In
form L the constructor takes `256` before `step`.

      for (step <- Seq[java.lang.Boolean](null, java.lang.Boolean.TRUE, java.lang.Boolean.FALSE)) {
        val sent = new VarkaSessionEmit(2, null, java.util.Set.of(), true, null, step)
        val bytes = new java.io.ByteArrayOutputStream
        val out = new java.io.ObjectOutputStream(bytes)
        out.writeObject(sent)
        out.close()
        val in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray))
        val back = in.readObject().asInstanceOf[VarkaSessionEmit]
        assert(back === sent)
        assert(back.zoneStepMask === step)
        assert(back.apply(VarkaEmitOptions.DEFAULTS).zoneStepMask() === (step == null || step))
      }
      assert(java.io.ObjectStreamClass.lookup(classOf[VarkaSessionEmit]).getSerialVersionUID ===
        <N + 1>L)

It catches a step form that an executor would not receive, and a serial version left at
`N`. A record's serial version is its declared constant, which `ObjectStreamClass` returns.

`VarkaTimestampZoneSuite` (Java, sql/core; row 657's), one test: "a batch whose instants
span more than a long goes to the row engine with batch-args in every form, and one a
microsecond inside does not". The table is `VarkaHarnessCases.source("rid INT, ts
TIMESTAMP", rows)`; the zone `Asia/Kathmandu`; the expression `hour(ts)`; one batch; the
form `STEPS_2` and then `STEPS_8`. A check runs as the suite's other tests run one
(`VARKA-690.md` 5.0): the conf is `VarkaCoverageHarnessSuite.conf(ansi)` with
`spark.sql.session.timeZone` set to the zone and
`spark.sql.execution.arrow.maxRecordsPerBatch` to the row count; the form is set by
`VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.BASE
.withZoneForm(form))` before the check and put back to `VarkaMatrixRuns.BASE` after it; the
plan comes from `VarkaHarnessPlans.projection` inside `VarkaCoverageHarnessSuite.withConf`
and runs by `VarkaExpressionHarness.projection`; a batch's outcome is the harness's
`Batch.status()`. B0's note on the suite prints a test to copy these calls from.

| rows | expected | the failure only it catches |
| :--- | :--- | :--- |
| `{0, -9222867135054775809L}`, `{1, 504901800000000L}` | the batch's status is `VarkaKernelRunner.STATUS_BATCH_ARGS`; every row equals Spark's two projections (the harness compares them) | a program of a `ToLocal` that takes its zone data without the view, at run time |
| `{0, -4611686018427387904L}`, `{1, 4611686018427387903L}` | the status is not `STATUS_BATCH_ARGS`; every row equals Spark's | a span rule one microsecond too strict |

If B0's note on the suite's tests of the ends shows a test that computes "answered" from
`changes` of the bare zone data for a batch of more than one row, its expectation is
computed through `VarkaBatchArgs.dataOf(node, data).changes(...)` in its place.

**Commands,** in order.

    VARKA_PROOFS_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaProofFilesSuite'
    git status --short sql/varka/proofs                                # M sql/varka/proofs/zone_step.smt2, nothing else
    grep -c ': expect unsat")' sql/varka/proofs/zone_step.smt2         # 13
    dev/varka_prove.sh --solver both zone_step.smt2                    # two lines "20 checks ... holds", then "every proof holds under z3 cvc5"
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaEmitterZoneSuite *VarkaBatchArgsSuite *VarkaLocalLineSuite *VarkaEmitOptionSuite *VarkaMatrixSuite *VarkaEmitterBudgetSuite *VarkaEmittedBytesSuite *VarkaNodePropsSuite *VarkaWarmupReachSuite *VarkaZoneSweepSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.matrix.config=zoneStepMask=false"' 'catalyst/testOnly *VarkaEmitterZoneSuite *VarkaBatchArgsSuite *VarkaLocalLineSuite *VarkaZoneSweepSuite *VarkaZonePlantedFaultSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaEvaluatorComponentsSuite *VarkaTimestampZoneSuite *VarkaBatchArgRunnerSuite'
    flock "$VARKA_HEAVY_LOCK" dev/varka_matrix.sh --defaults --module sql --suites VarkaEvaluatorComponentsSuite --jvm-arg -XX:MaxVectorSize=16; echo "exit $?"   # exit 0: the suite on a JVM of two long lanes, as the gate's narrow step runs it
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch -Phive 'hive/testOnly *SparkConfigBindingPolicySuite'
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json
    python3 dev/varka_census.py --check
    python3 dev/varka_push_check.py

Expected: no failure in any run; `grep -c '@Test'` prints `n1 + 5`, `n2`, `n3 + 1` and `n4 +
1`; `grep -c '  test("' $XS/VarkaEvaluatorComponentsSuite.scala` prints `n6 + 2`; `sed -n
's/^  private static final long serialVersionUID = \([0-9]*\)L;$/\1/p'
$E/VarkaSessionEmit.java` prints `N + 1`; `VarkaWarmupReachSuite` names no cold method (if
it names one, the builder adds that call to the long lane's block of
`VarkaVectorTemplateWarmup` and says so); the `dev/varka_matrix.sh` command prints `exit 0`
and no `*** FAILED ***` line; the second
catalyst run, under the matrix configuration, passes with the pins of step 11; the diff
exits 0; the census check and the push check exit 0. `git status --short` shows the steps'
files and `sql/varka/proofs/zone_step.smt2`, and no other generated file.

**Planted faults.**

| fault | the test that must fail |
| :--- | :--- |
| step 4 of 4.3 without `lconst_1; lsub` | test 1, at 5000 and at 20000 |
| `LSHR` for `ASHR` in step 5 | test 1, at 5000 |
| `InstantLine.changes` returns `changes` | the new test of `VarkaLocalLineSuite`, at its third call; test 5; the first row of the `sql/core` test |
| `bind` passes `Boolean.TRUE` where the host falls back | the first row of the first new test of `VarkaEvaluatorComponentsSuite` |
| the tag written `new FlagTag("zoneStepNoMask", true)` | test 3, on the defaults' canonical text |
| the serial version left at `N` | the second new test of `VarkaEvaluatorComponentsSuite`, on its last assertion |
| `stepped` returns `options` whatever the reading | the suite's run at two lanes (the `dev/varka_matrix.sh` command: it exits 1 and prints the test's `*** FAILED ***` line), on `session.apply(base) ===`; the wide run passes, which is why that command is in the sheet |

**Done when** the commands print the above and the seven faults fail as named.
**Not here:** a local rule's lowering; a compiler arm; a reason text; a benchmark; an entry
in `pinnedArms` or in the skip list; a default of any other option.

### 5.2 Part b: the local rules' steps with no mask

**Expectations.** After it: `ToInstant` and `ToInstantKept` emit the steps of 4.4 under
`zoneStepMask=false`; `zone_local_step.smt2` exists with 12 checks and holds under both
solvers. Nothing changes at the defaults, and the compiler's two-lane declines of row 690
stay.

**B0.**

    git branch --list 'varka-689b-local-steps' | wc -l                                        # 0
    grep -c 'boolean stepMask' $V/VarkaZoneLowering.java                                      # 1: part a
    grep -c 'ZONE_LOCAL_STEPS_REASON' $V/VarkaKernelPlan.java                                 # 0: row 690's part c merged
    grep -n 'zoneTmp' $V/Slots.java                                                           # note: where the vector locals of a zone node are planned
    grep -n 'static void emitToInstant(' -A8 $V/VarkaZoneLowering.java                        # note: how the form is read
    ls sql/varka/proofs/zone_local_step.smt2 2>/dev/null | wc -l                               # 0
    grep -c 'public static final long LOCAL_MAX_SPAN = Long.MAX_VALUE - 2 \* MAX_OFFSET_MICROS;' $V/VarkaBatchArgs.java   # 1
    grep -n 'files.put("zone_step.smt2"' $TJ/VarkaProofFiles.java                             # note: one line
    grep -n 'zone_step.smt2' sql/varka/proofs/README.md                                       # note: one row of the table
    grep -n '@DisplayName' $TJ/VarkaEmitterZoneSuite.java | grep -i 'ToInstant\|kept\|hand-set'   # note: row 690's tests 1 to 7
    grep -n 'ToInstant' $TS/VarkaEmitterBudgetSuite.scala                                     # note: nine rows, three records in three forms
    grep -c '@Test' $TJ/VarkaEmitterZoneSuite.java $TJ/VarkaLocalLineSuite.java               # note: n1, n3

**Steps.**

1. `$V/Slots.java`: where a zone node's vector locals are planned, a `ToInstant` gets two
   and a `ToInstantKept` three when the options' `zoneStepMask()` is false and the form has
   steps; else what they get today.
2. `$V/VarkaZoneLowering.java`: the three lowerings of 4.4; `laneCalls(node, form,
   stepMask)` gains the three rows of 4.5.
3. `$TJ/VarkaProofFiles.java`: `zoneLocalStep()` after `zoneStep()`: a header that restates
   4.6's second paragraph, 4.7's two modelled facts and what check 11 holds and does not,
   `rendered("QF_NIA")`, and the 12 checks of 4.7 in order under the texts written there;
   `files.put("zone_local_step.smt2", zoneLocalStep());` after the line B0 printed. Command
   1 renders the file.
4. `sql/varka/proofs/README.md`: after the row B0 printed, `` | `zone_local_step.smt2` | the
   steps with no mask of a local time to an instant (VARKA-689): each sign word is its
   compare inside `LOCAL_MAX_SPAN` and first fails two past it; the window word, the
   difference word and the kept step equal the masked forms | `VarkaProofFiles` | ``.
5. `$TJ/VarkaEmitterZoneSuite.java`: row 690's tests 1 to 5 (B0 printed them) run each body
   once under `.withZoneStepMask(true)` and once under `.withZoneStepMask(false)` on the
   options they pass to `run`; tests 6 and 7 get `.withZoneStepMask(true)` (4.8). Then the
   tests below.
6. `$TS/VarkaEmitterBudgetSuite.scala`: beside each of the six rows B0 printed under
   `STEPS_2` and `STEPS_8`, the same row with `.withZoneStepMask(false)`: 10 and 34, 24 and
   90, 28 and 106.

**The test plan.** `VarkaEmitterZoneSuite`, four tests more. `e`, `g` and `kept` are `new
ToInstant(col0, 0, Resolve.EARLIER)`, `new ToInstant(col0, 0, Resolve.GAP_END)` and `new
ToInstantKept(col0, col1, 0)`; `noMask(form)` as in part a.

| # | test | inputs | expected, and its oracle | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | each local rule with no mask equals the scalar meaning on hand-set steps, two touching gaps among them | `STEPS_2`, `noMask`. First: base 1000; step 0 a gap, `lo` 4000, `hi` 5000, delta 1000, after 2000; step 1 an overlap, `lo` 19000, `hi` 20000, delta -1000, after 1000; written in each rule's order (2.5); values 3999, 4000, 4999, 5000, 5001, 18999, 19000, 19999, 20000, 20001; for `kept` each with the second column 2000, 1000 and 0. Second: base 1000; two gaps, `lo` 4000 and `hi` 5000, `lo` 5000 and `hi` 6000, each delta 1000, afters 2000 and 3000; values 4999, 5000, 5999, 6000; for `kept` the second column 0. Third, nulls, on the first case's steps and values, for each of `e`, `g` and `kept` (second column 1000): rows 1 and 4 of the first column null, the storage under them `Long.MIN_VALUE` and `Long.MAX_VALUE`; then, for `kept` alone, row 2 of the second column null over `Long.MIN_VALUE` and row 7 over `Long.MAX_VALUE`; then every row of the first column null. Fourth, `STEPS_8` with eight real windows, `noMask(STEPS_8)`: base 1000; window `i` from 0 to 7 has `lo = 10000 * (i + 1)` and `hi = lo + 1000`, a gap with delta 1000 for an even `i` and an overlap with delta -1000 for an odd one, so `after` is 2000 after a gap and 1000 after an overlap; written in each rule's order (2.5), which gives for `g` the arguments `{1000, 11000, 10000, 1000, 21000, 21000, -1000, 31000, 30000, 1000, ...}` and for `kept` `{1000, 11000, 11000, 2000, 1000, 21000, 20000, 1000, -1000, ...}`; the 32 values `lo - 1`, `lo`, `hi - 1`, `hi` of each window in order; for `kept` the second column 2000, then 1000, then 0 | a scalar loop of 2.5's formulas written in the test. First case, written in the test too: `g` answers 2999, 3000, 3000, 3000, 3001, 16999, 17000, 17999, 19000, 19001, and `kept` with 1000 answers 2999, 3000, 3999, 3000, 3001, 16999, 18000, 18999, 19000, 19001. Second case: `e` and `kept` answer 3999, 3000, 3999, 3000; `g` answers 3000 four times. Third: a row is invalid exactly where an operand of it is null, every other row keeps the first case's value, and with every row null every row is invalid. Fourth: each row equals the scalar loop, and these rows are written in the test: `e` and `kept` with 2000 or 0 answer 8999, 9000, 9999, 9000 in the first window and 17999, 18000, 18999, 20000 in the second; `g` answers 8999, 9000, 9000, 9000 and 17999, 18000, 18999, 20000; `kept` with 1000 answers 8999, 9000, 9999, 9000 and 17999, 19000, 19999, 20000; and each later pair of windows is the first pair plus 20000 for each pair before it | a window word that reads the running answer; `sub` for `add` in the select; the `or` and the `sub` of the kept step exchanged; a null row's storage that traps or leaks into a live row; a `T` of a later window computed from the wrong prefix of deltas |
| 2 | a kept offset that no window names, the ends of a long among them, answers as the earlier offset with no mask | the first case of test 1 for `kept`, the second column cycling 1, `Long.MIN_VALUE`, `Long.MAX_VALUE` | equals `e`'s rows under `noMask(STEPS_2)` on the same values | a difference word that is wrong where the subtraction wraps |
| 3 | with no mask a local batch of span LOCAL_MAX_SPAN is answered and one a microsecond wider declines | `Asia/Kathmandu`, `STEPS_2`, each of `e`, `g`, `kept`; `args = VarkaLocalLineKernels.filled(root, ZoneForm.STEPS_2, VarkaChronoZone.of("Asia/Kathmandu"), 0, 9223371907254775807L)`; the rows 0, 504921599999999, 504921600000000, 504922499999999, 504922500000000, 9223371907254775807; for `kept` the second column 20700000000. Then `filled(...)` for the pair (-1, 9223371907254775807) | `args` is not null and each row equals `VarkaLocalLineKernels.expected(root, row, isNull, List.of("Asia/Kathmandu")).getAsLong()`; the second `filled` is null | a sign word of a local threshold that wraps inside the bound |
| 4 | each local rule with no mask holds no mask call and the lane calls of its form | the three roots under `noMask(STEPS_2)`, `noMask(STEPS_8)` and `masked(ONE)`, emitted and counted as part a's test 2 does (`emit(List.of(root), 1, 0, options)`, and `2` inputs for `kept`; `invocationCount` over `LongVector` and over `VectorMask`; `codeSize`) | lane calls: each root's count under `masked(ONE)` plus 9 and 33, plus 23 and 89, plus 27 and 105; `VectorMask` calls 0; each root's code length under `noMask(STEPS_8)` is above its length under `noMask(STEPS_2)`, and that above its length under `masked(ONE)`. No length is pinned as a number | a second sign word computed twice; a mask left in a form |

In test 3 the window of `Asia/Kathmandu`'s change of 1986 is a gap of 15 minutes: `lo` is
504921600000000 and `hi` 504922500000000 on the local line. That is four tests; `n1 + 4`.

**Commands,** in order.

    VARKA_PROOFS_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaProofFilesSuite'
    git status --short sql/varka/proofs                                # ?? sql/varka/proofs/zone_local_step.smt2 and M sql/varka/proofs/README.md
    grep -c ': expect unsat")' sql/varka/proofs/zone_local_step.smt2   # 9
    grep -c ': expect sat")' sql/varka/proofs/zone_local_step.smt2     # 3
    dev/varka_prove.sh --solver both zone_local_step.smt2              # two lines "12 checks ... holds", then "every proof holds under z3 cvc5"
    dev/varka_prove.sh --lint
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaEmitterZoneSuite *VarkaLocalLineSuite *VarkaEmitterBudgetSuite *VarkaEmittedBytesSuite *VarkaNodePropsSuite *VarkaWarmupReachSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.matrix.config=zoneStepMask=false"' 'catalyst/testOnly *VarkaEmitterZoneSuite *VarkaLocalLineSuite *VarkaZoneSweepSuite *VarkaZonePlantedFaultSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'catalyst/testOnly *VarkaEmitterZoneSuite'
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json
    python3 dev/varka_push_check.py

Expected: no failure; `n1 + 4` declarations; the run under the matrix configuration holds
the sweep's rules of row 690's part g, where that part has merged, to `java.time` with no
mask; the diff exits 0; the push check exits 0. A check that a solver answers `unknown`, or
one that takes over 2 s, is a stop.

**Planted faults.**

| fault | the test that must fail |
| :--- | :--- |
| the window word built as `S(hi) - S(lo)` | test 1, `g` at 4999, and at no other value |
| step 6 of `GAP_END` ends in `sub` for `add` | test 1, `g` at 4999 |
| the difference word without its negation, `z >> 63` | test 1, `kept` at 19000 and 19999 with the second column 2000, and with no other |
| `S(lo)` computed from `tk` in the kept step | test 1, `kept` at 19000 and 19999 with the second column 1000 |
| `laneCalls(node, form, false)` returns the masked count for `GAP_END` | `VarkaEmitterBudgetSuite`, on the row of `GAP_END` under `STEPS_2` with no mask: 24 calls against a weight of 11 |
| the scalar `T` of step 4 of `GAP_END` subtracts the deltas `j` from 0 to `i - 1`, one too few | test 1, its fourth case: `g` at 10000 and 10999 answers 10000, not 9000. The first case fails too, at 4000 and 4999 |
| the renderer writes check 11's padding with `(= pdelta 1)` | `dev/varka_prove.sh --solver both zone_local_step.smt2`: check 11 answers sat where the file expects unsat, under both solvers. Regenerate the file after the revert |

**Done when** the commands print the above and the seven faults fail as named.
**Not here:** `VarkaZoneCompiler`; lifting a two-lane decline; a benchmark; a new option.

### 5.3 Part c: the record

**Expectations.** After it: the compiler refuses by a set and prints a reason a conversion;
the record, the tool and its two hooks exist; the baselines list their sections under the
refusal from two lists; the route by which a benchmark's JVM turns the refusal off is
known, and built where it needs code. No kernel changes: the set is row 690's two declines,
so only the text of a reason moves.

**The cut line.** The estimate is 760, of which the tool is 514. If `python3
dev/varka_push_check.py` prints a size over 900, the part is cut in two commits on two
branches, by meaning. The builder first keeps the whole build under another name, `git
branch -m varka-689c-record varka-689c-whole`, and takes each half's files from it. Each
half has its own sheet:

- *The first half, the tool* (about 525 counted lines). Branch `varka-689c-tool`, from the
  master the whole build started from; title `[VARKA-689] Add the tool that renders the
  two-lane record`. Its B0 is the five lines of B0 below that name
  `dev/varka_zone_two_lane.py`, `dev/varka_precommit.sh`, `build_and_test.yml` or `ruff`,
  each as written, and `git branch --list 'varka-689c-tool' | wc -l`, which prints 0. Its
  steps are 4 to 7. Its commands are the first four, then `git status --short` (the four
  files of steps 4 to 7) and `python3 dev/varka_push_check.py`. Its planted faults are the
  four of the tool. It merges green alone: the record names no run, and `--check` holds it.
- *The second half, the record's holders* (about 235). Branch `varka-689c-record`, from
  master after the first half has merged; the part's title. Its B0 is B0 below, the dry
  run of the route included, with two lines replaced, because the first half's files must
  now be present. The line `ls dev/varka_zone_two_lane.py $M9/results ...`, which expects
  0, and the line `grep -c -F '    varka_session_lint; do' ...`, which expects 1, are
  not run. These five are run in their place:

      ls dev/varka_zone_two_lane.py $M9/results/VARKA-689-two-lanes.txt 2>/dev/null | wc -l   # 2: the tool and the record
      grep -c -F '    varka_session_lint varka_zone_two_lane; do' dev/varka_precommit.sh    # 1: step 6, its first edit
      grep -c -F "two_lane_inputs='^(dev/varka_zone_two_lane" dev/varka_precommit.sh         # 1: step 6, its block
      grep -c '      run: ./dev/varka_zone_two_lane.py --check' .github/workflows/build_and_test.yml   # 1: step 7
      python3 dev/varka_zone_two_lane.py --check                                             # varka_zone_two_lane: the record holds

  The other lines of B0 print what they say on that master too: the two lines that steps
  6 and 7 insert beside still occur once each. Its steps are 1 to 3b, 8 and 9; steps 4 to 7
  are not done again. Its commands are all of them, as written: the first four pass on the
  merged tool. Its planted faults are the four that are not the tool's.

The five lines of the second half were run on 28 November 2026 on a copy of master's two
files with steps 4 to 7 applied, and printed what they say (appendix B, `fix2/half2`).

**B0.** For the whole part, and, with the changes above, for each half of a cut.

    git branch --list 'varka-689c-record' | wc -l                                             # 0
    grep -n 'TWO_LANE_LOCAL_RULE_REASON' -r $C sql/catalyst/src/test sql/core/src/test docs   # note: its declaration (its modifiers), its two uses, and every test and document that names it
    grep -c 'static Optional<VarkaVectorIR> toInstantKept(' $C/VarkaZoneCompiler.java         # 1: row 690's part f
    grep -n 'static Optional<VarkaVectorIR> toLocal(' -A40 $C/VarkaZoneCompiler.java          # note: the order of its tests, and its fold branch
    grep -c 'TWO_LANE_REFUSED\|refusing(' $B/VarkaTimestampBaselineBase.java                  # note: 2 or more if row 690's part e added them (section 13, edit 4), else 0
    grep -n 'return grid(VIEW, forms());' $B/VarkaTimestamp*BaselineBenchmark.java            # note: the classes whose timed takes the lists
    grep -n "Form.lead(" $B/VarkaTimestampFieldsBaselineBenchmark.java $B/VarkaTimestampCastsBaselineBenchmark.java $B/VarkaTimestampArithmeticBaselineBenchmark.java   # note: seven lead forms, each Fusion.WHOLE
    ls dev/varka_zone_two_lane.py $M9/results 2>/dev/null | wc -l                              # 0
    grep -c -F '    varka_session_lint; do' dev/varka_precommit.sh                            # 1
    grep -c -F '# Milestone 8 counts its progress in the census and the cast matrix, rendered from the registry,' dev/varka_precommit.sh   # 1: the line step 6 inserts before
    grep -c '      run: ./dev/varka_catalogue_move.sh --check 9 10' .github/workflows/build_and_test.yml   # 1: the line step 7 inserts after
    grep -c 'local time resolved by' sql/varka/plans/m8/results/VARKA-612-refused.txt         # note: the coverage rows the census lists with the old reason
    grep -c '@Test' $CJ/VarkaZoneCompilerSuite.java                                           # note: n5
    grep -n -B12 'new VarkaAdmission(Set.of(), true)' $CJ/VarkaZoneCompilerSuite.java         # note: row 690's two-lane tests: how the suite resolves an expression under a zone and compiles it with an admission
    grep -c 'varka.baseline.twoLaneRefusal' $B/VarkaTimestampBaselineBase.java                # 0
    grep -c 'settings().forEach((key, value) -> session.conf().set(key, value));' $B/VarkaTimestampBaselineBase.java   # 1: the loop route B's block follows
    $HOME/.venv-ruff/bin/ruff --version                                                       # ruff 0.14.8 (the version dev/requirements.txt pins)
    grep -c 'out.add(new Section("TIMESTAMP_NTZ comparison, " + density,' $B/VarkaTimestampNtzBaselineBenchmark.java   # 1: the section the route's check reads
    grep -c 'note(title + ": fuses whole on the varka on arm, so it times a kernel there, "' $B/VarkaSqlBenchmarkBase.java   # 1: the note it greps for

Then the one run that says by which route a benchmark's JVM turns the refusal off (section
3, fact 11). It runs a class that master has, at two lanes, with route A's property, and
ends by printing the route:

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" 'set Test/javaOptions ++= Seq("-Dvarka.bench.dryRun=true", "-XX:UseAVX=2", "-XX:MaxVectorSize=16", "-Dspark.sql.codegen.varka.declineTwoLaneConditions=false")' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaTimestampNtzBaselineBenchmark" > target/dry16-prop.log 2>&1; echo "exit $?"; grep -q 'TIMESTAMP_NTZ comparison, no nulls: fuses whole on the varka on arm, so it times a kernel there' target/dry16-prop.log && echo "route A: the spark.sql property reaches the session" || echo "route B: it does not reach; the baselines read varka.baseline.twoLaneRefusal"

| it prints | what it means | what the builder does |
| :--- | :--- | :--- |
| `exit 0`, then `route A: the spark.sql property reaches the session` | the property reached the Varka session: the class listed its filter `WHOLE`, and the session planned and ran it as a kernel | step 3b is skipped. `<OFF>` below is `-Dspark.sql.codegen.varka.declineTwoLaneConditions=false` |
| `exit 0`, then `route B: it does not reach; the baselines read varka.baseline.twoLaneRefusal` | the session kept the host's refusal: the class listed the filter `NONE` and printed no note for it | step 3b is built. `<OFF>` below is `-Dvarka.baseline.twoLaneRefusal=false` |
| `exit <n>` with `<n>` not 0, then either of the two lines | the run failed for another cause, and the route's line means nothing | a stop: report the last 40 lines of `target/dry16-prop.log` |

Each line of the first column is whole, as the command prints it: `exit` and the status,
then one line that the last `echo` writes.

The builder writes the route it built in section 11 of this file, in the words `route A`
or `route B`.

**Steps.**

1. `$C/VarkaZoneCompiler.java`: 4.9's block, and the five statements it describes.
   `TWO_LANE_LOCAL_RULE_REASON` goes.
2. Every test B0 printed that names the old constant names `VarkaZoneCompiler.twoLaneReason(
   VarkaZoneCompiler.TwoLaneForm.GAP_END)` where its expression is the cast of a `DATE`, and
   `...KEPT)` where it is the sum. Every document B0 printed gets the new text of 4.11.
3. `$B/VarkaTimestampBaselineBase.java`: if B0 printed 0, the block of 4.9 after `grid`, and
   in each class B0 printed, `return refusing(grid(VIEW, forms()), declinesLongConditions);`.
   If B0 printed 2 or more, nothing: row 690 added them.

   3b. On route B alone. In the same file, in `runBenchmarkSuite`, directly after the loop
   `for (SparkSession session : sessions) { settings().forEach(...); }` and before the
   statement that calls `timed(...)`:

       // The two-lane refusal as a reading asks for it (VARKA-689): a benchmark's JVM cannot
       // set a session's conf by a spark.sql system property, so the baselines read their own.
       String refusal = System.getProperty("varka.baseline.twoLaneRefusal");
       if (refusal != null) {
         for (SparkSession session : sessions) {
           session.conf().set(SQLConf.VARKA_DECLINE_TWO_LANE_CONDITIONS().key(), refusal);
         }
       }

   and one sentence at the end of the class comment: "A run with
   {@code -Dvarka.baseline.twoLaneRefusal=false} turns the two-lane refusal off on both
   sessions, so that a 128-bit reading times every conversion fused (VARKA-689)." The class
   reads the refusal from the Varka session after this block, so its list follows. On
   route A no line of this step is written.
4. `dev/varka_zone_two_lane.py`: appendix A, whole and unchanged, executable (`chmod +x`).
   The text of appendix A is what `ruff format` of the pinned version gives; a builder
   does not reformat it by hand.
5. `$M9/results/VARKA-689-two-lanes.txt`: the text of 4.9, tabs between the fields.
6. `dev/varka_precommit.sh`, two edits. The line `    varka_session_lint; do` becomes
   `    varka_session_lint varka_zone_two_lane; do`. And a block goes in as a whole, before
   the comment of the census block and not inside it: that comment's five lines and the
   code under them (`census_inputs=...`) stay together. Old, one line, which occurs once
   and follows an empty line:

       # Milestone 8 counts its progress in the census and the cast matrix, rendered from the registry,

   New, the block, one empty line, and the old line again:

       # The two-lane record of the zone conversions is what its named runs render (VARKA-689).
       two_lane_inputs='^(dev/varka_zone_two_lane\.py|sql/varka/plans/m9/results/VARKA-689-)'
       if printf '%s\n' "${files[@]}" | grep -qE "$two_lane_inputs"; then
         out="$(python3 dev/varka_zone_two_lane.py --check 2>&1)" || {
           echo "$out" | sed 's/^/two-lane record: /'
           findings=$((findings + 1))
         }
       fi

       # Milestone 8 counts its progress in the census and the cast matrix, rendered from the registry,

7. `.github/workflows/build_and_test.yml`: a step goes in after the two steps that share
   one comment (the census's and the catalogue's), and before the next comment, so that it
   splits neither. Old, one line, which occurs once:

             run: ./dev/varka_catalogue_move.sh --check 9 10

   New, that line and two more:

             run: ./dev/varka_catalogue_move.sh --check 9 10
           - name: The two-lane record of the zone conversions matches its runs
             run: ./dev/varka_zone_two_lane.py --check

   In the file `- name` has four spaces before it and `run:` six, as the steps around it.
8. `$CJ/VarkaZoneCompilerSuite.java`: the tests below.
9. `dev/varka_regen_generated.sh --all`.

**The test plan.** `VarkaZoneCompilerSuite` (Java, JUnit 5, catalyst; row 657's), three
tests. A two-lane sink is one built with `new VarkaAdmission(Set.of(), true)`; a sink that
refuses nothing with `new VarkaAdmission(Set.of(), false)`. An expression is resolved under
a zone and compiled with an admission by the calls of row 690's two-lane tests, which B0's
last line printed: the new tests use those calls and no other helper.

| # | test | inputs | expected | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | the conversions the compiler refuses at two lanes are the record's | the file `sql/varka/plans/m9/results/VARKA-689-two-lanes.txt` under `System.getProperty("spark.test.home", System.getenv("SPARK_HOME"))`, read as lines; the lines that do not start with `#`, split at tabs | five lines; their first fields are the names of `TwoLaneForm.values()` in order; the set of forms whose second field is `refused` equals `VarkaZoneCompiler.TWO_LANE_REFUSED`; every second field is `kept` or `refused` | a verdict changed in the record and not in the compiler, or the other way |
| 2 | on a two-lane host each refused conversion declines by its own reason, each kept one fuses, and UTC is never refused | for each of the seven kernels of 4.9 under `America/Los_Angeles`, `+01:00` and `UTC`, compiled with a two-lane sink and with a sink that refuses nothing | with the two-lane sink: under `America/Los_Angeles`, `CAST(d AS TIMESTAMP)` declines with `twoLaneReason(GAP_END)` and `ts + INTERVAL '1' DAY` with `twoLaneReason(KEPT)`, the other five build; under `+01:00` and `UTC` all seven build. With the other sink all 21 build. The expectation is computed from `TWO_LANE_REFUSED`, not written per kernel: a kernel declines exactly when the set holds its conversion of 4.9 for that zone, or `TO_LOCAL` for the sum | a refusal that reaches a fold or UTC; a kept conversion that declines |
| 3 | each conversion's reason names it and the host | `twoLaneReason(form)` for the five forms | the five texts are distinct; each ends with `on an x86 host whose 64-bit species have two lanes or fewer: refused there, since its kernel was not measured faster than the row engine (VARKA-689)`; the text for `GAP_END` is 4.11's, written whole in the test | two conversions sharing a reason, so that `EXPLAIN` cannot tell them apart |

**Commands,** in order.

    $HOME/.venv-ruff/bin/ruff check dev/varka_zone_two_lane.py         # All checks passed!
    $HOME/.venv-ruff/bin/ruff format --check dev/varka_zone_two_lane.py   # 1 file already formatted
    python3 dev/varka_zone_two_lane.py --selftest                      # varka_zone_two_lane selftest: ok
    python3 dev/varka_zone_two_lane.py --check                         # varka_zone_two_lane: the record holds
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaZoneCompilerSuite *VarkaCoverageSuite *VarkaWidthAuditSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaTimestampZoneSuite *VarkaTimestampZoneQuerySuite *VarkaTimestampFieldsBaselineQueriesSuite *VarkaTimestampCastsBaselineQueriesSuite *VarkaTimestampArithmeticBaselineQueriesSuite *VarkaTwoLaneDefault128Suite'
    git status --short
    git diff -U0 -- docs/sql-varka.md sql/varka/width_audit.json sql/varka/plans/m8/results/VARKA-612-refused.txt | grep '^[-+][^-+]' | grep -vc 'two lanes or fewer'   # 0
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json
    python3 dev/varka_census.py --check
    python3 dev/varka_push_check.py

Expected: the four tool lines as written; no failure; `n5 + 3` declarations; `git status
--short` shows the steps' files and, of generated files, at most the three of the eighth
command, each changed only in lines that hold a two-lane reason; the two diffs print 0 and
exit 0. On route B alone, one command more, after the others, shows that the block of step
3b works: a dry run of the casts class with the baselines' property.

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" 'set Test/javaOptions ++= Seq("-Dvarka.bench.dryRun=true", "-XX:UseAVX=2", "-XX:MaxVectorSize=16", "-Dvarka.baseline.twoLaneRefusal=false")' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaTimestampCastsBaselineBenchmark" > target/dry16-route-b.log 2>&1; echo "exit $?"; grep -c 'TIMESTAMP CAST(d AS TIMESTAMP), America/Los_Angeles, a week, no nulls: fuses whole' target/dry16-route-b.log

It must print `exit 0` and then `1`: the cast that master refuses at two lanes fused, so
the property reached both sessions. `exit 0` and then `0` means the block did not act (the
class then lists the cast `NONE`, and plans it so): a stop.

**Planted faults.**

| fault | the test that must fail |
| :--- | :--- |
| `TWO_LANE_REFUSED` without `KEPT` | test 1, on the set; and the arithmetic class's queries suite, on the sum's sections with the refusal on |
| the `FIXED` statement placed in the UTC branch too, with `FIXED` added to the set | test 2, under `UTC` |
| the record's line of `GAP_END` reads `kept` | test 1 |
| `refusing` returns `all` whatever `declines` is | the casts class's queries suite, with the refusal on: `TIMESTAMP CAST(d AS TIMESTAMP), America/Los_Angeles, a week, no nulls is listed as WHOLE but plans as NONE` |
| in the tool, `RUNS = 1` | `--selftest` prints `selftest: one run was rendered` among four lines, then `varka_zone_two_lane selftest: failed`, and exits 1 |
| in the tool, the test `if len(rows) != SECTIONS[form]:` made `if False:` | `--selftest` prints `selftest: two runs without a section was rendered`, then `failed`, and exits 1 |
| in the tool, the test of `REFUSAL_OFF` in `measured` made `if False:` | `--selftest` prints `selftest: the refusal left on was rendered` and `selftest: runs with the refusal on passed the check`, then `failed`, and exits 1 |
| in the tool, the test `if run in named:` in `render` made `if False:` | `--selftest` prints `selftest: two copies of one run was rendered` and `selftest: a record of two copies of one run passed the check`, then `failed`, and exits 1 |

The four faults of the tool were planted in copies on 28 November 2026 and printed these
lines (appendix B).

**Done when** the commands print the above and the eight faults fail as named.
**Not here:** a verdict that differs from row 690's two declines; a benchmark run; a change
to any lowering.

### 5.4 Part d: the reading, the verdicts and the close

The lead dispatches; a builder commits what comes back. The reading is of master as part c
left it, so it needs no commit of this part. Two commits: the reading with its verdicts,
then the 128-bit files of the head that holds them.

**B0.**

    git branch --list 'varka-689d-results' | wc -l                                            # 0
    python3 dev/varka_zone_two_lane.py --check                                                # varka_zone_two_lane: the record holds
    grep -c '^# runs: ' $M9/results/VARKA-689-two-lanes.txt                                   # 0: not measured yet
    grep -c 'Form.lead(' $B/VarkaTimestampFieldsBaselineBenchmark.java $B/VarkaTimestampCastsBaselineBenchmark.java $B/VarkaTimestampArithmeticBaselineBenchmark.java   # 3, 3 and 1
    grep -q 'varka.baseline.twoLaneRefusal' $B/VarkaTimestampBaselineBase.java && echo "route B" || echo "route A"   # note: the route part c built; section 11 of this file names the same one, else stop

The route decides one thing, the JVM option that turns the two-lane refusal off, written
`<OFF>` in the steps below:

| B0 printed | `<OFF>` | where it is read |
| :--- | :--- | :--- |
| `route A` | `-Dspark.sql.codegen.varka.declineTwoLaneConditions=false` | a `SparkConf` loads it, and each session's conf is merged from that |
| `route B` | `-Dvarka.baseline.twoLaneRefusal=false` | `VarkaTimestampBaselineBase.runBenchmarkSuite`, which sets the conf on both sessions (5.3, step 3b) |

**Steps.**

1. The narrow dry run of each of the three classes `<C>`, with the refusal off, on the
   laptop under AVX2 at two lanes. `P` is `org.apache.spark.sql.execution.benchmark`. On
   route A:

       flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" 'set Test/javaOptions ++= Seq("-Dvarka.bench.dryRun=true", "-XX:UseAVX=2", "-XX:MaxVectorSize=16", "-Dspark.sql.codegen.varka.declineTwoLaneConditions=false")' "Test/runMain $P.<C>" > target/dry16-fused-<C>.log 2>&1; echo "exit $?"

   On route B:

       flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" 'set Test/javaOptions ++= Seq("-Dvarka.bench.dryRun=true", "-XX:UseAVX=2", "-XX:MaxVectorSize=16", "-Dvarka.baseline.twoLaneRefusal=false")' "Test/runMain $P.<C>" > target/dry16-fused-<C>.log 2>&1; echo "exit $?"

   It must print `exit 0`: every section planned as its class lists it for a session that
   refuses nothing, and no `WHOLE` section of a kept cell handed a batch to the row path.
   Then, for the casts class:

       grep -c 'TIMESTAMP CAST(d AS TIMESTAMP), America/Los_Angeles, a week, no nulls: fuses whole' target/dry16-fused-VarkaTimestampCastsBaselineBenchmark.log   # 1

   It must print 1: a conversion that master refuses fused, so the option reached the
   sessions. If it prints 0, the option did not reach them, and a dispatch would time the
   row path on both arms of every refused section: stop and report the route and the
   log's last 40 lines. (If the record already keeps `GAP_END`, which a second reading by
   a later row may find, the same check is made on a title of a form the record refuses;
   with no refused form it is skipped, since the two routes then plan alike.)
2. The builder hands the lead the three class names and the route. The lead dispatches on
   master, with no `pr` input, for each class `<C>`. On route A:

       gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f create-commit=false -f class=org.apache.spark.sql.execution.benchmark.<C> -f jdk=25 -f expected-cpu="AMD EPYC 7763" -f extra-java-options="-XX:MaxVectorSize=16 -Dspark.sql.codegen.varka.declineTwoLaneConditions=false"

   On route B:

       gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f create-commit=false -f class=org.apache.spark.sql.execution.benchmark.<C> -f jdk=25 -f expected-cpu="AMD EPYC 7763" -f extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.twoLaneRefusal=false"

   twice (the two runs of the reading), and once, on either route:

       gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f create-commit=false -f class=org.apache.spark.sql.execution.benchmark.<C> -f jdk=25 -f runner=ubuntu-24.04-arm -f expected-cpu="Neoverse-N2"

   Nine dispatches. A run that fails its CPU check is dispatched again; nothing else is
   relaxed. From each run's artifact the lead takes `<C>-jdk25-results.txt` and
   `<C>-jdk25-provenance.txt` and names them `VARKA-689-<C>-128bit-fused-run1-results.txt`
   and `-provenance.txt`, the same with `run2`, and `VARKA-689-<C>-arm-results.txt` and
   `-provenance.txt`: eighteen files, handed to the builder in one directory `$IN`.
3. The builder checks each file before it commits it. `W` is a results file, `Q` its
   provenance file, `N` the class's section count: 88 for the fields, 52 for the casts, 40
   for the arithmetic (`VARKA-655.md` 4.9).

| check | command | must print |
| :--- | :--- | :--- |
| the CPU, an EPYC file | `grep -c "^cpu: *AMD EPYC 7763" $Q` | `1` |
| the CPU, an Arm file | `grep -c '^cpu: *$' $Q; grep -c '^datapath: *n/a lane-ops/ns' $Q` | `1`, `1` |
| the options | `grep "^inputs:" $Q` | for a `fused` file, ends `extra-java-options=-XX:MaxVectorSize=16 <OFF>`, with `<OFF>` the route's option written out; for an Arm file, `extra-java-options=<none>` |
| every section ran | `grep -c "^=\{20,\}" $W` | `2 * N` |
| both arms | `grep -c "^spark row path (varka off)" $W; grep -c "^varka on" $W` | `N`, `N` |

   An Arm run's provenance names no model: its `cpu:` line is empty, since the workflow
   reads a field that an aarch64 `/proc/cpuinfo` does not have (section 3, fact 10). What
   holds the CPU there is the dispatch's `expected-cpu="Neoverse-N2"`: the probe reads the
   model from `lscpu` and fails the job on any other, so a run that left an artifact ran on
   that model; and the `n/a` line is written by the probe's aarch64 branch alone, after
   that check. Both greps print 1 on the committed Arm file
   `sql/core/benchmarks/VarkaFloatingCastBaselineBenchmark-jdk25-runner-arm-128bit-provenance.txt`,
   and the first of the EPYC row prints 0 on it. A file that fails a check is not
   committed: the builder reports it, and the lead dispatches again.
4. `cp $IN/VARKA-689-* $M9/results/`, then render the record from the six `fused` files:

       python3 dev/varka_zone_two_lane.py --render $M9/results/VARKA-689-*-128bit-fused-run?-results.txt > target/two-lanes.txt && cp target/two-lanes.txt $M9/results/VARKA-689-two-lanes.txt
       grep -c '^# runs: ' $M9/results/VARKA-689-two-lanes.txt        # 6
       awk -F'\t' '!/^#/ {print $1, $2, $3}' $M9/results/VARKA-689-two-lanes.txt

   The last command must print five lines whose third fields are 32, 8, 8, 8 and 14. The
   tool holds the same: `--render` prints one line and exits 1, and writes no record, where
   a count differs (`the runs hold <n> sections of <form>, not <m>: measure every class`),
   where a section is in one run alone (`1 run holds the section <title>, not 2: measure
   its class again`), where a run's provenance names another CPU, no
   `-XX:MaxVectorSize=16` or neither property that turns the refusal off (`<path>: the
   two-lane refusal was not turned off: <options>`, and alike), or where two files name
   one workflow run in their `run:` lines, as a file copied under a second name does
   (`<path> and <path> are one run, <the run's address>: measure again`). Each is a stop:
   the builder reports the line, and the lead dispatches the class again.
5. Apply the verdicts, with no judgement:
   - `VarkaZoneCompiler.TWO_LANE_REFUSED` is `EnumSet.of(...)` of exactly the forms the
     record calls `refused`, in the enum's order, or `EnumSet.noneOf(TwoLaneForm.class)`.
   - Run the queries suites of command 2 below. For each failure `<title> is listed
     as WHOLE but plans as NONE`, the form of that title (its `Form.lead` or `Form.one` line
     in the class) joins `TWO_LANE_REFUSED_DST` when the title's zone is
     `America/Los_Angeles` and `TWO_LANE_REFUSED_FIXED` when it is `+01:00`. For each
     failure `is listed as NONE but plans as`, the form leaves its list. Repeat until the
     suites pass. A class whose `timed` does not call `refusing` and whose suite fails so
     gets the call (5.3, step 3's form).
   - `VarkaZoneCompilerSuite`'s test 2 computes its expectation from the set and needs no
     edit. Row 690's two tests that name `twoLaneReason(GAP_END)` or `(KEPT)` on a two-lane
     sink (5.3, step 2): where the record now keeps that form, the test asserts that the
     expression builds on a two-lane sink, under its old name with "declines by name"
     replaced by "fuses"; the query suite's `EXPLAIN` test asserts `fused` for it.
   - `dev/varka_regen_generated.sh --all`.
6. The first commit, after the commands below pass: the eighteen files, the record, the
   set, the lists and what the regeneration moved. The lead pushes it and opens the pull
   request `<n>` as a draft.
7. The lead dispatches each class once more at 128 bits with the defaults, on that head:

       gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> -f create-commit=false -f class=org.apache.spark.sql.execution.benchmark.<C> -f jdk=25 -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-XX:MaxVectorSize=16

   and renames the files to `<C>-jdk25-runner-epyc7763-128bit-results.txt` and
   `-provenance.txt`. The builder checks them as step 3 does (the options line ends
   `extra-java-options=-XX:MaxVectorSize=16`), copies them over the committed ones in
   `sql/core/benchmarks`, and runs `python3 dev/varka_bench_diff.py --git HEAD <file>` for
   each, and again with `--requote`, which must list nothing. They are the second commit,
   with steps 8 and 9.
8. Section 11 of this file: the record's five lines; for each refused form, the section of
   its least ratio; the Arm reading, as `python3 dev/varka_zone_two_lane.py --ratios
   $M9/results/VARKA-689-*-arm-results.txt` prints it (five lines of one run, which
   `--render` would refuse; its verdicts are reported and not applied: question 4); each
   UTC section of the seven kernels under 95 percent in the 128-bit files of step 7, by
   title, "filed for the lead as its twin's"; each prediction of section 7 scored.
9. The row's line in `$M9/PLAN.md` reads `**Done**`. `sql/varka/SKILLS.md` gains one entry:
   what the row learned of two lanes (the sign word and its bound; that the step form
   follows the host and the refusal follows the record; the command that repeats the
   reading).

**Commands,** after step 5.

    python3 dev/varka_zone_two_lane.py --check                         # varka_zone_two_lane: the record holds
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaTimestampFieldsBaselineQueriesSuite *VarkaTimestampCastsBaselineQueriesSuite *VarkaTimestampArithmeticBaselineQueriesSuite *VarkaTimestampZoneFunctionsBaselineQueriesSuite *VarkaTimestampBaselineQueriesSuite *VarkaTimestampZoneSuite *VarkaTimestampZoneQuerySuite *VarkaTwoLaneDefault128Suite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaZoneCompilerSuite *VarkaCoverageSuite *VarkaWidthAuditSuite'
    python3 dev/varka_quote_check.py
    python3 dev/varka_census.py --check
    python3 dev/varka_push_check.py

Expected: the tool's line as written; no failure; the quote check reports 0 orphans; the
census check exits 0 (the census is read at the defaults and does not move).

**The planted faults of the close.** In a copy of the record under `target/`, exchange
`kept` and `refused` on one line and run `python3 dev/varka_zone_two_lane.py --check
target/<copy>`: it must exit 1 and print the line twice, as the record has it and as the
runs give it. In a second copy, delete the lines `# runs:` and `#   on` of every `run2`
file: the same command must exit 1 and print a line that starts `varka_zone_two_lane: 1
run holds the section`.

**Done when** the record names six runs and holds; the compiler's set, the baselines' lists
and the record agree in the Build; the three 128-bit files are committed from the first
commit's head, with every section of a refused form at the row path on both arms; the Arm
files are committed; each prediction is scored.
**Not here:** an A/B arm; the 256-bit files (no kernel changes at 256 bits); a default of
`zoneForm`; a refusal on aarch64; a fix of a UTC section.

## 6. Tests, proofs, fuzzing and coverage

Every suite runs at both vector widths through the gate, under each option configuration
through the matrix, and with the memory sanitizer on.

| suite | language, module | what it holds for this row | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `VarkaEmitterZoneSuite` | Java, JUnit 5, catalyst (row 682's) | `ToLocal` and the three local rules with no mask: hand-set steps at each threshold, nulls, padding, the spans at the bound; rows 682's and 690's kernel tests under both values of the option | a step with no mask that differs from the scalar meaning |
| `VarkaLocalLineSuite` | Java, JUnit 5, catalyst (row 690's) | the view of the instant line | a span rule that is off by one, or declines a batch with no step |
| `VarkaBatchArgsSuite`, `VarkaEmitterBudgetSuite` | catalyst (row 682's, master's) | the pins of the masked form, held under the option; the weights with no mask | a kernel grouped by a weight that is not its count |
| `VarkaEmitOptionSuite`, `VarkaMatrixSuite` | Scala, catalyst | the option's reason, its reader, its tag, its matrix configuration | an option that moves the defaults' key, or that no configuration runs |
| `VarkaProofFilesSuite` | Scala, catalyst | `zone_step.smt2` and `zone_local_step.smt2` are what the code renders | a bound changed in the code and not in the proof |
| `VarkaEvaluatorComponentsSuite` | Scala, sql/core | the session's step form: the host's answer, the conf's value, apart from the refusal | a two-lane host left with masked steps, or a wide host given none |
| `VarkaTimestampZoneSuite` | Java, sql/core (row 657's) | a wide batch declines at run time with `batch-args` and Spark answers | a program that takes its zone data without the view |
| `VarkaZoneCompilerSuite` | Java, JUnit 5, catalyst (row 657's) | the set against the record; each refused conversion's decline and reason; the folds and UTC | a verdict in one holder and not the other |
| the three baselines' queries suites | Scala, sql/core (row 655's) | every section's fuse class with the refusal off and on | a baseline that would time the row path as a kernel at 128 bits |
| `VarkaTimestampZoneQuerySuite`, `VarkaTwoLaneDefault128Suite` | sql/core | the reason in `EXPLAIN`; the refusal at its production default in a JVM of two lanes | a path only a session, or only that host, has |
| `SparkConfigBindingPolicySuite` | Scala, hive | the new conf has a binding policy | the config audit's own failure |
| `VarkaZoneSweepSuite`, `VarkaZonePlantedFaultSuite`, `VarkaIrFuzzSuite` | existing (rows 684 and 690) | under the matrix configuration `zoneStepMask=false`: every zone of the JVM, each rule, the planted faults, random trees | a step with no mask that is wrong in a zone no fixture holds |
| `dev/varka_zone_two_lane.py --selftest` and `--check` | Python | the rule on synthetic runs of all 70 sections; a kept form that starts to lose and a refused one that starts to win; a record of one run, two copies of one run, runs that lost a section, a run on another CPU, at the full width or with the refusal on, each refused; the record against its runs | a record that no reading supports |
| `VarkaEvaluatorComponentsSuite`, its run at two long lanes | part a's `dev/varka_matrix.sh` command; the gate's narrow step and the matrix's `isa=w128` after it | the suite's whole-value assertions on a two-lane JVM | an expected value that holds on a wide host alone |

**Proofs.** 4.7: two checks more in `zone_step.smt2` and the new `zone_local_step.smt2` of
12 checks, both under both solvers in the nightly and in `dev/varka_prove.sh --solver both`.

**Fuzzing.** No new production. The IR fuzzer's zone sequence (row 684, row 690's part h)
and the Spark fuzzer's zone compositions run under the matrix configuration. The option is
not drawn in general, since it changes no kernel without a zone node.

**Coverage and mutation.** After parts a and b the lead runs `dev/varka_gen_coverage.sh` and
reports the branches of `VarkaZoneLowering` and `VarkaBatchArgs` from
`sql/varka/coverage/emitter.md` in the pull request: every branch reached, or the test that
reaches it added. `dev/varka_pit.sh` mutates `codegen.varka` against ScalaTest suites only,
so it reaches these lowerings through `VarkaIrFuzzSuite`; the planted faults of each sheet
stand in for it.

**What runs only in CI.** The option matrix (the new configuration `zoneStepMask=false`,
and `isa=w128`, `isa=avx2`, `isa=arm`), the width audit, both solvers over every proof file.
Locally a builder runs each sheet's commands, of which two run the zone suites under
`-Dvarka.matrix.config=zoneStepMask=false`.

**Test time,** read against `python3 dev/varka_calibration.py`: section 7, prediction 9.

## 7. Measurement

**The sections** are row 655's: the seven lead forms of 4.9 in
`VarkaTimestampFieldsBaselineBenchmark`, `VarkaTimestampCastsBaselineBenchmark` and
`VarkaTimestampArithmeticBaselineBenchmark`, each under `UTC`, `+01:00` and
`America/Los_Angeles`, the last over a week, a year in time order, a year in no order and
ten years in no order, with no nulls and with one row in ten null. 70 of them decide
(4.10).

**Where.** An AMD EPYC 7763 runner under `-XX:MaxVectorSize=16`: twice with the refusal off
(the reading that decides), once at the defaults after the verdicts (the committed 128-bit
files). The Arm runner (`ubuntu-24.04-arm`, a Neoverse N2, 128-bit vectors) once at its
defaults: masked steps, nothing refused. Nothing at 256 bits: no kernel changes there. The
A/B arm "masked against no mask" at each width is row 691's.

**The threshold and the rule:** 4.10. A ratio is whole percents of the row path's rate by
the least best time of each arm over the two runs; under 95 a conversion is refused.

**Predictions, registered on 27 November 2026, before any run.** Each names the file or the
pin that scores it.

1. Lane calls with no mask over the one-offset form: 9 and 33 for `ToLocal` and `EARLIER`,
   23 and 89 for `GAP_END`, 27 and 105 for `ToInstantKept`; no `VectorMask` call in any.
   Parts a and b, the pins.
2. No existing shape's bytes move in parts a, b and c: `emitted_bytes.json` and
   `coverage.json` are identical after each: yes. Each part's `git diff --exit-code`.
3. `TO_LOCAL` is kept: yes. Its least ratio is at least 1.30. The record.
4. `EARLIER` is kept: yes. The record.
5. `GAP_END` is refused: yes. Its kernel holds the day's range guard on the long lane,
   which is a mask at two lanes whatever the step is. The record.
6. `KEPT` is refused: yes. Its kernel holds two checked sums on the long lane. The record.
7. `FIXED` is refused: yes. The fold sits under a range guard on the long lane, and it
   holds no step that this row could change. The record.
8. On the Arm runner none of the 70 sections reads under 95 percent: yes. The Arm files.
9. Test time. Part a adds under 5 s to `VarkaEmitterZoneSuite` (five tests, at most a dozen
   small kernels; a JUnit class of VARKA-328 took 0.15 s, and the emitter tests of VARKA-460
   2.0 s for five). Part b's second pass over row 690's five kernel tests adds what they
   cost, under 20 s by `VARKA-690.md` 7, prediction 9, and its own four tests under 5 s.
   Part c's three compiler tests add under 2 s. No test is added to the sweep. The cases
   added on 28 November 2026 (eight real steps, the nulls of the local rules, the round
   trip) are a dozen small kernels and three serializations: inside the same bounds. The
   sheets' suite logs.
10. Each of the 14 new proof checks answers in under 2 s under each solver: yes. The
    prover's lines of parts a and b.
11. The ten-year sections of the reading read from 95 to 105 percent where every batch
    declines. The six `fused` files.
12. Four pull requests, none over 1000 counted lines, each within half of its estimate.

Predictions 5 to 7 say that three of the five conversions are refused. If they hold, the
row closes with `TO_LOCAL` and `EARLIER` kept at 128 bits and the rest declined by name.

## 8. Who builds it

`m9/PLAN.md` 1.4 has rows 656, 657 and 682 to 692 built at full strength, and so is every
part of this row:

| part | builder | why |
| :--- | :--- | :--- |
| a | full strength | an option through seven files, a session setting whose default reads the host, and bytecode whose slip is a wrong answer at a threshold only |
| b | full strength | three lowerings in bytecode with three stack shapes; a proof file |
| c | full strength | three holders of one verdict must fail each other's lies; a tool whose check is the row's oracle |
| d | the lead dispatches; a builder at full strength applies the rule | the verdicts are mechanical, and the files are the milestone's 128-bit evidence |

No part moves goal G5. A cheaper model takes a part only on the owner's word, recorded with
its date; part d's builder work is then the nearest to a fan-out task.

## 9. Risks

1. **A sign word that wraps is a wrong answer that no fallback catches.** The bound is
   proved (4.7), the view declines past it under every option (4.6), part a's tests 4 and 5
   and part b's test 3 hold both sides of it, and the planted fault "the view returns the
   count" fails three tests.
2. **A reader that bypasses the view.** A test filler or a later row's code that asks the
   bare zone data would run a kernel with no mask past its bound. Row 690's part b put the
   view at the one seam in `sql/core`; part a's `sql/core` test fails a seam that is bare
   for a `ToLocal`; step 12 of part a wraps the sweep's filler.
3. **One span rule under every option** declines a batch the masked steps would answer: more
   than 292,271 years of instants in one batch, in a zone with a change in it. Question 2.
4. **A whole conversion is refused for one losing kernel.** `hour` could lose while the cast
   to `DATE` wins, and both are `TO_LOCAL`. The record prints the section of the least
   ratio, so the loser is named; a finer unit is row 691's, with its own measurement.
5. **A later kernel loses while its conversion is kept** (a field of row 668 over
   `ToLocal`). This row reads the seven first kernels alone. Row 680's gate times every
   coverage row at 128 bits and owns that case.
6. **The reading's property does not reach the session** (fact 11: route A was read, not
   run). Both routes are written out; part c's B0 runs the check that picks one and builds
   it, and part d's first step is a dry run that fails when the refused cast does not fuse.
   A dispatch with an option that did not reach would give files whose refused sections
   time the row path on both arms and read 1: the tool cannot tell that from a tie, so the
   dry run is the guard, and the builder does not skip it.
7. **The matrix gains a configuration**, and with it the suites' time on CI. It is the only
   way the every-zone sweep runs the steps with no mask on wide hosts. Tests that pin the
   masked form are pinned in parts a and b (4.8); one that was missed fails that
   configuration's job and gets the pin, never a skip line.
8. **Row 690's tests name its two-lane constant.** Part c's B0 prints every holder, and its
   step 2 renames each. A holder in a suite that B0's paths do not cover fails to compile,
   which is a stop that names the file.
9. **The baselines cannot say a zone's class under the refusal before this row.** Row 690's
   parts e and f decline at two lanes while the classes list the form `WHOLE` in every
   zone, so the queries suite's fuse test fails with the refusal on. Section 13, edits 4
   and 9, give row 690's parts e and f the two lists and `refusing`; whichever row builds
   second finds them.
10. **Driver and executor of two widths** (4.2, the debt): speed only.
11. **The plan ages.** Rows 682 to 690 and 655 are not built, and rows 653 and 646 move
    shared lists before this row starts. Each B0 prints the shape a step needs, and a shape
    no step covers is a stop.
12. **Noise at the threshold.** A section near 95 percent can read either side. The rule
    takes the least of two runs, the record prints the ratio it compared, and a later
    reading that disagrees fails a check and does not silently flip a kernel.
13. **The width audit reads the defaults.** `VarkaWidthAuditSuite` emits without a session,
    so at its forced 128-bit species the zone rows keep the masked steps and read
    `per-lane`, while a session on such a host emits no mask. `width_audit.json` does not
    move in this row; whether the audit follows the session's form is row 691's, with the
    defaults by width.
14. **The surface at 128 bits** (row 655's 4.10, run by row 691) expects every turned entry
    to fuse. An entry of a refused conversion does not on a two-lane runner. Section 13,
    edit 7, says so in that plan.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md` and `m8/BUILD_SHEETS.md`, the rules a builder of this row is most
likely to break:

- "**A new bounded lowering comes with its proof file**": checks 19 and 20 with part a,
  `zone_local_step.smt2` with part b, each in the pull request of its lowering.
- "A test starts from `VarkaMatrix.base`, not `VarkaEmitOptions.DEFAULTS`", and a test
  that asserts the masked form pins it with `.withZoneStepMask(true)` (4.8).
- An option's losing arm is kept: winners depend on the machine. No part deletes the
  masked steps or the steps with no mask, whatever the reading says.
- "any claim resting on a ratio under ~1.3x is re-run and compared by minimums": the rule of
  4.10 takes two runs for every section.
- "**The Varka code carries no `TODO` or `FIXME` comments, and should not start.**"
- "**A comment describes the code as it is.**" No "used to", "the old" or "since VARKA-689".
- "No allocation, streams, lambdas or boxing on a path that runs per batch or per row":
  `InstantLine.changes` is one call, one compare and one subtraction; the view is built
  once a runner.
- A switch over a sealed type has no `default`. Name each record in its arm, never `Zoned`.
- "**A pull request stays under 1000 changed lines** of hand-written files."
- "**Review your own diff before opening the pull request**" with `/code-review high`.
- "**Never kill by name pattern**", "**Never `git stash`**", "**Write only inside your own
  worktree and scratch area.**"

Do not: edit a generated file by hand; change a verdict by hand (render the record); add a
test to the matrix's skip list; read the host in the emitter or in a lowering; lift or add
a refusal that the record does not hold; change `zoneForm`'s default or the ladder's rungs;
time anything on the shared laptop; read OpenJDK's `java.time` sources; print the
environment; open `/home/max/proj/runtime-master`; push, open a pull request or dispatch a
workflow.

## 11. Outcome

*To be written when the row closes: the four pull requests, the record's five lines with
the section of each least ratio, the Arm reading, the UTC sections filed for the lead, and
each prediction of section 7 scored.*

## 12. Seams with the plan written beside this one

The plan of row 662 (the nanosecond timestamps' layout) is written beside this one and could
not be read.

| row | the seam this plan assumes | where it is checked |
| :--- | :--- | :--- |
| 662 | it adds no zone node, no step and no reader of `zoneStepMask`: its done-when fuses "a field over the microsecond word", and a zone conversion over a nanosecond value is on that word, in microseconds, through `VarkaZoneCompiler.toLocal`. So the steps, the span rule and the record of this row apply to it unchanged | part c's B0 prints `toLocal`'s body; a second caller that builds a `ToLocal` outside it is a stop |
| 662 | if its one-long layout (nanoseconds since the epoch) ever feeds a zone node directly, the thresholds are in that unit and a batch can span the whole long: the span rule of 4.6 still declines it, since it reads no unit | none here: rows 673 to 675 own a zone node over a nanosecond long |
| 662 | it adds its option arm for the two layouts to `VarkaEmitOptions`; whichever row builds second puts its component after the other's. Part a's B0 prints the record's last component | part a's B0 |

**Rows with no plan yet.** Row 691 reads the option, the conf, the record and the tool.
Row 680's gate meets the refused conversions as coverage rows that time the row path at 128
bits. Row 693 (the table of the local line) holds no step and is not touched by the option.

**Deviations from the shared names.** None in the option's name, its default, its two
values or `VarkaZoneLowering` as their home. Additions that the family design does not
name: the conf and the session component (the design said only that the default follows the
host flag); `InstantLine` (the design had "a kernel of this arm declines"; the plan
declines under every option, as row 690 does on the local line); `laneCalls`'s third
parameter; `TwoLaneForm`, the set and the record's tool (the design said "a recorded
refusal"); `refusing` in the baselines.

## 13. Edits to other plans, made by this plan's own pull request

The pull request that adds this file to `sql/varka/plans/m9/` also makes these edits: ten
numbered edits, of which the first has two places, so eleven old texts in five files. Each
is an exact old text and its new text; a block is indented by seven spaces, and what follows
those seven is the file's own text. Each old text was matched exactly once, by a script
(`check_edits.py`, appendix B), in its file on master `9e7b1612402`.

1. `m9/PLAN.md`, row 689's line, two places. Old:

       | 689 | Zone steps at two lanes. **Filed** (25 November 2026, from `VARKA-657.md`; the admission check:

   New:

       | 689 | Zone steps at two lanes. **Planned** (`VARKA-689.md`, 27 November 2026: four parts, the option `zoneStepMask` with `ToLocal`'s steps and the span rule, the three local rules' steps, the record of what a two-lane host refuses with its tool, the reading and the verdicts; filed 25 November 2026, from `VARKA-657.md`; the admission check:

   Old:

       | `VARKA-657.md`; decision 15; row 657 first, row 684's proof file, and row 690's part c for the local rules | small to medium |

   New:

       | `VARKA-657.md`; decision 15; row 657 first, row 684's proof file, row 690's part b for the span rule's view, its part c for the local rules and its parts d to f for the record; row 655's classes for the reading | small to medium |

2. `VARKA-657.md`, section 8, row 689's line. Old:

       | 689 | steps with no mask at two lanes; the recorded refusal where a form loses | 657 | 1 |

   New:

       | 689 | steps with no mask at two lanes; the recorded refusal where a form loses | 657 | 4 |

3. `VARKA-690.md`, 4.4, the sentence on a `ToLocal`. Old:

       - `dataOf` is a switch over `node` with no `default`: `zone` itself for a `ToLocal`; for a

   New:

       - `dataOf` is a switch over `node` with no `default`: `zone` itself for a `ToLocal` (row
         689's part a makes it a view of the instant line, `VARKA-689.md` 4.6); for a

4. `VARKA-690.md`, 5.5, step 4: the baselines under the two-lane refusal. Old:

       4. Row 655's class, the docs' sentence ("A cast of a `DATE` to `TIMESTAMP` fuses: the day's

   New:

       4. The class's sections under the two-lane refusal: the cast declines by name there in a
          zone with changes (4.7), and the queries suite plans every section with the refusal
          on, so the form's sections under `America/Los_Angeles` are `Fusion.NONE` in such a
          session. If `VarkaTimestampBaselineBase` has no `refusing` (`VARKA-689.md` 4.9 gives
          `TWO_LANE_REFUSED_DST`, `TWO_LANE_REFUSED_FIXED` and `refusing` whole), add the three
          with `TWO_LANE_REFUSED_DST = List.of("CAST(d@ AS TIMESTAMP)")`, and make the casts
          class's `timed` return `refusing(grid(VIEW, forms()), declinesLongConditions)`. Part
          f adds `"ts@ + INTERVAL '1' DAY"` to the list and the same call to the arithmetic
          class. Then:
          row 655's class, the docs' sentence ("A cast of a `DATE` to `TIMESTAMP` fuses: the day's

5. `VARKA-690.md`, 4.7, who keeps or lifts the two declines. Old:

       before any measurement. Row 689 builds their steps with no mask (4.8), and row 691 reads the
       128-bit sections and keeps or lifts the two declines. This row does not lift them.

   New:

       before any measurement. Row 689 builds their steps with no mask (4.8), measures the 128-bit
       sections with every conversion fused, and keeps or lifts the two declines by its record
       (`VARKA-689.md` 4.9 and 4.10); row 691 reads again. This row does not lift them.

6. `VARKA-690.md`, appendix A, its last paragraph. Old:

       lifted once the steps with no mask exist: row 691 measures the 128-bit sections and decides.

   New:

       lifted once the steps with no mask exist: row 689's part d measures the 128-bit sections and
       its rule decides (`VARKA-689.md` 4.10); row 691 measures again with every form.

7. `VARKA-655.md`, 4.10, the surface's runs with the kernels on. Old:

           fails the run if a turned entry does not fuse or hands a batch to the row path;

   New:

           fails the run if a turned entry does not fuse or hands a batch to the row path. At 128
           bits an entry whose conversion the record of `VARKA-689.md` 4.9 calls `refused` does
           not fuse on the runner, by design: row 691's plan says how the 128-bit dispatch
           expects those entries (the row path), and it does not turn the refusal off for the
           surface, since a user's session would not;

8. `VARKA-657-PARTS.md`, 5.2, part B's steps: who adds `Zoned` to the matrix's compiled
   kinds (section 3, fact 3). Part B is the first part whose compiler builds a zone node.
   Old:

       7. The suites of the next paragraph. Regenerate the docs and the proof file.

   New:

       7. The suites of the next paragraph. Regenerate the docs and the proof file.
       8. `<gent>/varka/VarkaMatrixRuns.java`: `COMPILED_NODE_KINDS` gains
          `VarkaVectorIR.Zoned.class`, the class that `zoneForm`'s `readOnlyBy` names: its value
          becomes `Set.of(VarkaVectorIR.WidenDecimal.class, VarkaVectorIR.ConvertLane.class,` and,
          on the next line, `VarkaVectorIR.Zoned.class);`. Its Javadoc gains, at its end: "A
          {@code Zoned} node is what a field of a {@code TIMESTAMP} compiles to in a zone with
          changes (VARKA-657), so {@code zoneForm}'s other arms run." From this part on the
          compiler builds a `ToLocal`, so the option matrix runs the options that only a zone
          node reads. Check: `dev/varka_matrix.sh --list | grep zoneForm` prints `zoneForm=ONE`
          and `zoneForm=STEPS_8`, the two lines part C's B0 expects; `VarkaTimestampZoneSuite`
          sets its form itself and is not moved by them. Row 689's `zoneStepMask=false` joins
          them with its part a (`VARKA-689.md` 3, fact 3).

9. `VARKA-690.md`, 5.6, step 5: part f's sections under the two-lane refusal. Edit 4 fixes
   part e alone; a builder who reads only part f's sheet would list the sum `WHOLE` in
   every zone, and its queries suite would fail with the refusal on. Old:

       5. Row 655's class (as part d's step 4, with the sections B0 printed for a literal of whole
          days), the docs' sentence ("A `TIMESTAMP` plus a literal interval of whole days fuses: the

   New:

       5. Row 655's class (as part d's step 4, with the sections B0 printed for a literal of whole
          days). The sum declines by name on a two-lane host in a zone with changes (4.7), and
          the queries suite plans every section with the refusal on, so the form's sections under
          `America/Los_Angeles` are `Fusion.NONE` in such a session:
          `VarkaTimestampBaselineBase.TWO_LANE_REFUSED_DST` gains `"ts@ + INTERVAL '1' DAY"` after
          the cast's form, and the arithmetic class's `timed` returns `refusing(grid(VIEW,
          forms()), declinesLongConditions)`. Part e's step 4 added the two lists and `refusing`;
          if the base has none (`grep -c 'refusing(' <the base's file>` prints 0), add the three
          from `VARKA-689.md` 4.9, whole. Then the docs' sentence ("A `TIMESTAMP` plus a literal
          interval of whole days fuses: the

10. `VARKA-690.md`, 5.2, test 13: the clause on a `ToLocal`, which row 689's part a makes
    false and edits in the code. The clause sits inside the test's table row, so the new
    text adds to that row and wraps nothing. Old:

       for the `ToLocal` the stub itself comes back

    New:

       for the `ToLocal` the stub itself comes back (until row 689's part a, which returns a view of the instant line there and edits this clause: `VARKA-689.md` 5.1)

## 14. What only a JVM can settle

Each item is a command the lead can run on master today, or on the day its row is built,
and the output that decides it.

1. **That C2 compiles the calls of a step with no mask at two lanes without a Java loop.**
   The probe's row "2 steps with no mask" (0.685 ns a row at two lanes, 2.6) shows the
   shift, the AND and the adds compiled there over broadcast vectors. The plan's form takes
   scalar operands and a negation. Today, on master, the nearest shape with no checked sum
   in it (a checked sum is a mask at two lanes and would hide the answer):

       cd <a worktree of master> && JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1 flock /home/max/proj/.varka-locks/heavy.lock dev/varka_emit.sh "(shiftright(l, 63) & 3600L) | (shiftright(l2, 63) & l)" --columns l:bigint,l2:bigint --asm --width=16 --jvm-arg=-XX:UseAVX=2

   It decides: the output is `FusedOutput(0)`, and the mnemonic frequencies of `loopDense0`
   hold packed shifts and ANDs and neither `prefetchnta` nor a mark-word store, which the
   tool's header names as the sign of a boxed vector. With either, a call of 4.3 falls back
   at two lanes: the lead reports which, and the plan changes before part a starts. The
   negation (`lanewise(NEG)`) cannot be written in SQL without a checked sum; part a's
   `VarkaWarmupReachSuite` and row 691's reading are its checks.
2. **By which route a benchmark's JVM turns the two-lane refusal off** (fact 11). Today, on
   master, with a class that exists; the command ends by printing the route:

       cd <a worktree of master> && JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1 flock /home/max/proj/.varka-locks/heavy.lock build/sbt -batch "project sql" 'set Test/javaOptions ++= Seq("-Dvarka.bench.dryRun=true", "-XX:UseAVX=2", "-XX:MaxVectorSize=16", "-Dspark.sql.codegen.varka.declineTwoLaneConditions=false")' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaTimestampNtzBaselineBenchmark" > target/dry16-prop.log 2>&1; echo "exit $?"; grep -q 'TIMESTAMP_NTZ comparison, no nulls: fuses whole on the varka on arm, so it times a kernel there' target/dry16-prop.log && echo "route A: the spark.sql property reaches the session" || echo "route B: it does not reach; the baselines read varka.baseline.twoLaneRefusal"

   It decides: `exit 0` and then `route A: the spark.sql property reaches the session`
   means the property reached the Varka session, which then listed its comparison (`ntz <
   ntz2`, a filter) `WHOLE`, planned it as a kernel and printed the note that the `grep`
   finds. `exit 0` and then `route B: it does not reach; the baselines read
   varka.baseline.twoLaneRefusal` means the session kept the host's refusal: the class
   listed the filter `NONE` and printed no note for it. The class reads the refusal from
   the session's own conf, so its list and the plan cannot disagree, and any exit that is
   not 0 is another failure: read
   the log's last 40 lines. Part c's B0 runs the same command on the build day and builds
   the route it prints (5.3); part d reads the route from the code (5.4). No sheet waits
   for a word on it. The answer today only tells the lead in advance whether part c will
   hold route B's eight lines.
3. **The lane calls of each form** (4.5): asserted by part a's test 2 and part b's test 4.
   A count that differs from 4.5 is a stop. No code length is pinned as a number.
4. **Whether catalyst's coverage rows of the zone family decline under a two-lane
   admission**, and so whether `VARKA-612-refused.txt` gains lines with rows 690's parts e
   and f and changes text with part c. On the day part c is built:

       grep -c 'two lanes or fewer' sql/varka/plans/m8/results/VARKA-612-refused.txt

   before and after `dev/varka_regen_generated.sh --all`. Part c's eighth command accepts
   either answer.
5. **The verdicts.** Only part d's nine dispatches say which conversions are kept.

## 15. The questions only the owner can answer

Each has its options, the recommendation first, and the default this plan applies until he
says otherwise.

1. **A conf of its own for the step form.** (a) `spark.sql.codegen.varka.emit.zoneStepMask`,
   optional, unset is the host's answer: recommended, and applied. Row 691's A/B arm at 128
   bits needs a session with masked steps on a two-lane host, and a flag has no value that
   means "the host's". (b) No conf: the step form follows the host alone, and row 691 adds
   what it needs. It saves about 40 lines and the config audit's run.
2. **The span rule on the instant line under every option.** (a) One rule for both step
   forms, as he decided for the local line on 27 November 2026: recommended, and applied.
   The masked form then declines a batch of more than 292,271 years of instants that holds
   a change, which it could answer. (b) The rule only where the kernel's steps have no
   mask: `dataOf` then takes the kernel's options, and every reader of row 690's seam
   changes with it.
3. **The threshold.** (a) 95 percent of the row path's rate, by the least of two runs:
   recommended, and applied. (b) 100 percent, as G7's words read: a section whose every
   batch declines then refuses its conversion by noise. (c) 100 percent over the sections
   whose batches a kernel answers, and no reading of the others: it needs the batch check
   of each section in the tool. A risk of (a) that he should know: the cells whose every
   batch declines (ten years in no order: 8 sections of `TO_LOCAL`, 2 of each of `EARLIER`,
   `GAP_END` and `KEPT`) pay the range pass and the decline on the `varka on` arm, so their
   true ratio is a little under 1. If one reads 93 or 94, (a) refuses its whole conversion,
   `TO_LOCAL` included, on that cell alone. (c) would not.
4. **A zoned kernel that loses on aarch64 at two lanes.** The refusal keys on the x86
   two-lane flag, and decision 15 names x86. (a) Report the Arm reading and file what loses
   for row 691: recommended, and applied. (b) Extend the refusal to aarch64 by lane count:
   a second setting, since the two-lane refusal's own default is false there.
5. **A fold that loses** (`FIXED`). (a) Refuse it by the record, as the plan does: at 128
   bits a zoned expression under a fixed offset then runs on the row path, UTC excepted.
   Recommended, and applied. (b) Leave the fold out of the record and file its loss for
   `m10/SCOPE.md` item 101, the lowering without masks for every long-lane kernel: the
   section then stays slower than the row path at 128 bits, against G7's words.
6. **Who reads a refused verdict again** (added on 28 November 2026; 4.10). Nothing
   measures on a schedule, and row 680's nightly, as filed, times a refused conversion's
   rows on the row path on both arms, so it cannot see one start to win. (a) Leave it to
   the rows that measure: row 691 reads again, and a row that changes a refused
   conversion's kernel runs the reading as part of its own measurement; the record's
   header says that a verdict is of its runs' day. Recommended, and applied: it costs
   nothing now, and a refusal that outlives its cause costs speed at 128 bits only, never
   an answer. (b) Row 680's nightly gains an arm with the refusal off over the three
   timestamp classes, and renders the record from its last two runs: a verdict then follows
   the code within two nights, at the price of three more benchmark jobs a night and a
   record that a nightly commits. That is row 680's plan to write, not this row's.

## Appendix A. `dev/varka_zone_two_lane.py`, whole

The file below, 514 lines, was run on 28 November 2026 as it stands here: `ruff check` and
`ruff format --check` of the pinned ruff 0.14.8 pass on it under the repository's
`pyproject.toml`, `--selftest` prints `varka_zone_two_lane selftest: ok`, and its parser
read a committed results file (section 3, fact 9). It is Python's standard library alone.
Part c adds it executable, byte for byte: the block is indented by four spaces, the file's
own text starts after them, and an empty line of the block is an empty line of the file.

    #!/usr/bin/env python3
    #
    # Licensed to the Apache Software Foundation (ASF) under one or more
    # contributor license agreements.  See the NOTICE file distributed with
    # this work for additional information regarding copyright ownership.
    # The ASF licenses this file to You under the Apache License, Version 2.0
    # (the "License"); you may not use this file except in compliance with
    # the License.  You may obtain a copy of the License at
    #
    #    http://www.apache.org/licenses/LICENSE-2.0
    #
    # Unless required by applicable law or agreed to in writing, software
    # distributed under the License is distributed on an "AS IS" BASIS,
    # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    # See the License for the specific language governing permissions and
    # limitations under the License.
    #
    # The record of which zone conversions an x86 host with two 64-bit lanes keeps
    # as kernels and which it leaves to the row path (VARKA-689).
    #
    #   dev/varka_zone_two_lane.py --render RESULTS...   # print the record of these runs
    #   dev/varka_zone_two_lane.py --check [RECORD]      # the record is what its runs give
    #   dev/varka_zone_two_lane.py --ratios RESULTS...   # the five lines of any runs, no record
    #   dev/varka_zone_two_lane.py --selftest
    #
    # A run is a results file of one of the timestamp baselines, measured at 128 bits
    # with every conversion fused, and the provenance file beside it, which must name
    # its workflow run, the CPU, the width and the two-lane refusal turned off. Two
    # files that name one workflow run are one run, so --render refuses them: a copy
    # of a run is no second run. A section belongs to a
    # form by its title. A section's ratio is the least best time of `spark row path
    # (varka off)` over the least best time of `varka on`, each taken over the runs
    # that hold the section. A form is refused when one of its sections reads under
    # THRESHOLD percent, and kept otherwise: no other input, and no judgement. The
    # ratio is whole percents, rounded down, so the number printed is the number
    # compared.
    #
    # A record is of RUNS runs or more of every section, and of exactly the sections
    # of SECTIONS: --render refuses anything else, and so does --check, which renders
    # the record again from the runs it names and compares. A reading that changes a
    # verdict fails the check until the record, the compiler's set
    # (VarkaZoneCompiler.TWO_LANE_REFUSED) and the baselines' lists follow it.
    # --ratios prints the same five lines for any runs, one run of an Arm host
    # included, and holds them to nothing.

    import pathlib
    import re
    import sys

    THRESHOLD = 95  # percent of the row path's rate; under it a form is refused
    RUNS = 2  # the least count of runs that hold each section of a record
    RECORD = "sql/varka/plans/m9/results/VARKA-689-two-lanes.txt"
    DST = "America/Los_Angeles"
    FIXED = "+01:00"
    # The first kernels of each conversion (VARKA-657, VARKA-690), as a section's title shows them.
    FORMS = {
        "hour(ts)": "TO_LOCAL",
        "minute(ts)": "TO_LOCAL",
        "second(ts)": "TO_LOCAL",
        "CAST(ts AS DATE)": "TO_LOCAL",
        "CAST(ntz AS TIMESTAMP)": "EARLIER",
        "CAST(d AS TIMESTAMP)": "GAP_END",
        "ts + INTERVAL '1' DAY": "KEPT",
    }
    # The sections of each form: a kernel in four data sets under DST and in one under FIXED,
    # each with and without nulls (VARKA-655).
    SECTIONS = {"TO_LOCAL": 32, "EARLIER": 8, "GAP_END": 8, "KEPT": 8, "FIXED": 14}
    ORDER = list(SECTIONS)
    NOT_MEASURED = "not measured"
    # What a run's provenance file must say: the CPU, the width, and the refusal turned off by
    # the session's own property or by the baselines' (VARKA-689.md 5.3).
    CPU = "AMD EPYC 7763"
    WIDTH = "-XX:MaxVectorSize=16"
    REFUSAL_OFF = (
        "-Dspark.sql.codegen.varka.declineTwoLaneConditions=false",
        "-Dvarka.baseline.twoLaneRefusal=false",
    )
    RULE = re.compile(r"^={20,}\s*$")
    ROW = re.compile(r"^(.*?)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)X\s*$")
    OFF, ON = "spark row path (varka off)", "varka on"
    HEADER = [
        "# The zone conversions at two 64-bit lanes on x86 (VARKA-689): kept as kernels, or",
        "# refused and left to the row path. Rendered by dev/varka_zone_two_lane.py --render",
        "# from the runs named below, and held to them by its --check. A form is refused when",
        "# a section of it reads under 0.%02d times the row path's rate, by the least best" % THRESHOLD,
        "# time of each arm over the runs. A verdict is of the runs named here and of their",
        "# day: nothing measures again on a schedule, so `refused` does not say that a form",
        "# loses today (VARKA-689.md 4.10).",
    ]
    COLUMNS = "# form\tverdict\tsections\tleast ratio\tthe section of the least ratio"


    def form_of(title):
        """The form a section's title decides, or None.

        >>> form_of("TIMESTAMP hour(ts), America/Los_Angeles, a week, no nulls")
        'TO_LOCAL'
        >>> form_of("TIMESTAMP CAST(d AS TIMESTAMP), +01:00, a year in no order, no nulls")
        'FIXED'
        >>> form_of("TIMESTAMP hour(ts), UTC, a year in no order, no nulls") is None
        True
        >>> form_of("TIMESTAMP_NTZ twin hour(ntz), a year in no order, no nulls") is None
        True
        >>> form_of("TIMESTAMP year(ts), America/Los_Angeles, a year in no order, no nulls") is None
        True
        """
        if not title.startswith("TIMESTAMP "):
            return None
        for shown, form in FORMS.items():
            if title.startswith("TIMESTAMP " + shown + ", " + DST + ", "):
                return form
            if title.startswith("TIMESTAMP " + shown + ", " + FIXED + ", "):
                return "FIXED"
        return None


    def sections(text):
        """{title: (best off, best on)} of a results file's text, in milliseconds."""
        out, lines = {}, text.splitlines()
        i = 0
        while i + 2 < len(lines):
            if RULE.match(lines[i]) and RULE.match(lines[i + 2]):
                title = lines[i + 1].strip()
                best = {}
                j = i + 3
                while j < len(lines) and not RULE.match(lines[j]):
                    m = ROW.match(lines[j])
                    if m and m.group(1).strip() in (OFF, ON):
                        best[m.group(1).strip()] = int(m.group(2))
                    j += 1
                if OFF in best and ON in best:
                    out[title] = (best[OFF], best[ON])
                i = j
            else:
                i += 1
        return out


    def measured(provenance):
        """The line a record holds for a run's provenance text; ValueError if it is no reading.

        >>> ok = "cpu:   AMD EPYC 7763 64-Core Processor\\ninputs: jdk=25 extra-java-options="
        >>> measured(ok + "-XX:MaxVectorSize=16 -Dvarka.baseline.twoLaneRefusal=false\\n")
        'AMD EPYC 7763 64-Core Processor, -XX:MaxVectorSize=16 -Dvarka.baseline.twoLaneRefusal=false'
        >>> measured(ok + "-XX:MaxVectorSize=16\\n")
        Traceback (most recent call last):
        ...
        ValueError: the two-lane refusal was not turned off: -XX:MaxVectorSize=16
        """
        cpu, options = "", ""
        for line in provenance.splitlines():
            if line.startswith("cpu:"):
                cpu = line[len("cpu:") :].strip()
            if line.startswith("inputs:") and "extra-java-options=" in line:
                options = line.split("extra-java-options=", 1)[1].strip()
        if CPU not in cpu:
            raise ValueError("the CPU is not an %s: %s" % (CPU, cpu or "none named"))
        if WIDTH not in options.split():
            raise ValueError("the width is not %s: %s" % (WIDTH, options or "no options"))
        if not any(off in options.split() for off in REFUSAL_OFF):
            raise ValueError("the two-lane refusal was not turned off: " + options)
        return cpu + ", " + options


    def run_of(provenance):
        """The workflow run a provenance text names; ValueError if it names none.

        >>> run_of("where: a runner\\nrun:         https://github.com/o/r/actions/runs/7\\n")
        'https://github.com/o/r/actions/runs/7'
        >>> run_of("run:\\ncpu: AMD EPYC 7763\\n")
        Traceback (most recent call last):
        ...
        ValueError: the provenance names no run
        """
        for line in provenance.splitlines():
            if line.startswith("run:") and line[len("run:") :].strip():
                return line[len("run:") :].strip()
        raise ValueError("the provenance names no run")


    def ratios(texts):
        """{form: [(percent, title, runs that hold the title)], least first} of results texts."""
        best, held = {}, {}
        for text in texts:
            for title, (off, on) in sections(text).items():
                old = best.get(title)
                best[title] = (off, on) if old is None else (min(old[0], off), min(old[1], on))
                held[title] = held.get(title, 0) + 1
        forms = {f: [] for f in ORDER}
        for title, (off, on) in best.items():
            form = form_of(title)
            if form is not None:
                forms[form].append(((100 * off) // max(on, 1), title, held[title]))
        return {f: sorted(rows) for f, rows in forms.items()}


    def form_lines(forms):
        """One line a form of ORDER; a form with no section reads `-`."""
        lines = []
        for form in ORDER:
            rows = forms[form]
            if not rows:
                lines.append("\t".join([form, "-", "0", "-", "no section"]))
                continue
            percent, title, _ = rows[0]
            verdict = "refused" if percent < THRESHOLD else "kept"
            shown = "%d.%02d" % divmod(percent, 100)
            lines.append("\t".join([form, verdict, str(len(rows)), shown, title]))
        return lines


    def render(runs):
        """The record's text for {path: (results text, provenance text)} runs, in the order given.

        ValueError where the runs are no reading: a run on another CPU or with other options, two
        files of one workflow run, a form whose count of sections is not SECTIONS', a section that
        fewer than RUNS runs hold.
        """
        lines, named = list(HEADER), {}
        for path, (_, provenance) in runs.items():
            try:
                lines += ["# runs: " + path, "#   on " + measured(provenance)]
                run = run_of(provenance)
            except ValueError as e:
                raise ValueError("%s: %s" % (path, e)) from None
            if run in named:
                raise ValueError("%s and %s are one run, %s: measure again" % (named[run], path, run))
            named[run] = path
        forms = ratios([text for text, _ in runs.values()])
        for form in ORDER:
            rows = forms[form]
            if len(rows) != SECTIONS[form]:
                raise ValueError(
                    "the runs hold %d sections of %s, not %d: measure every class"
                    % (len(rows), form, SECTIONS[form])
                )
            for _, title, count in rows:
                if count < RUNS:
                    raise ValueError(
                        "%d run holds the section %s, not %d: measure its class again"
                        % (count, title, RUNS)
                    )
        return "\n".join(lines + [COLUMNS] + form_lines(forms)) + "\n"


    def provenance_of(results):
        """The provenance file beside a results file."""
        name = results.name
        if not name.endswith("-results.txt"):
            raise ValueError("not a results file: " + name)
        return results.with_name(name[: -len("-results.txt")] + "-provenance.txt")


    def read_run(results):
        """(results text, provenance text) of a results file; ValueError if either is missing."""
        provenance = provenance_of(results)
        for file in (results, provenance):
            if not file.is_file():
                raise ValueError("no such file: %s" % file)
        return results.read_text(encoding="utf-8"), provenance.read_text(encoding="utf-8")


    def check(record_path, root):
        """The differences between the record and what its runs render; empty when it holds."""
        text = record_path.read_text(encoding="utf-8")
        lines = text.splitlines()
        named = [line[len("# runs: ") :].strip() for line in lines if line.startswith("# runs: ")]
        rows = [line.split("\t") for line in lines if line and not line.startswith("#")]
        problems = []
        if [r[0] for r in rows] != ORDER:
            problems.append("the record's forms are %s, not %s" % ([r[0] for r in rows], ORDER))
        for r in rows:
            if len(r) != 5 or r[1] not in ("kept", "refused"):
                problems.append("not a line of five fields with a verdict: " + "\t".join(r))
        if not named:
            for r in rows:
                if len(r) == 5 and (r[2] != "0" or not r[4].startswith(NOT_MEASURED)):
                    problems.append("a record that names no run is not measured: " + "\t".join(r))
            return problems
        runs = {}
        for path in named:
            try:
                runs[path] = read_run(root / path)
            except ValueError as e:
                problems.append("the record names a run that is not committed: %s" % e)
        if problems:
            return problems
        try:
            again = render(runs)
        except ValueError as e:
            return [str(e)]
        if again != text:
            want = again.splitlines()
            for a, b in zip(lines + [""] * len(want), want + [""] * len(lines)):
                if a != b:
                    problems.append("record: %s\n  runs:   %s" % (a, b))
        return problems


    def selftest():
        import doctest
        import tempfile

        failed = doctest.testmod()[0]
        rule = "=" * 96
        data = ["a week", "a year in time order", "a year in no order", "ten years in no order"]
        titles = []
        for shown in FORMS:
            cells = [(DST, d) for d in data] + [(FIXED, "a year in no order")]
            for zone, d in cells:
                for nulls in ("no nulls", "one row in ten null"):
                    titles.append("TIMESTAMP %s, %s, %s, %s" % (shown, zone, d, nulls))
        titles.append("TIMESTAMP hour(ts), UTC, a year in no order, no nulls")
        titles.append("TIMESTAMP_NTZ twin hour(ntz), a year in no order, no nulls")
        cast = "TIMESTAMP CAST(d AS TIMESTAMP), " + DST + ", a week, no nulls"
        plus = "TIMESTAMP ts + INTERVAL '1' DAY, " + DST + ", a week, no nulls"
        fixed = "TIMESTAMP hour(ts), +01:00, a year in no order, no nulls"

        def run(times, leave_out=()):
            parts = []
            for title in titles:
                if title in leave_out:
                    continue
                off, on = times.get(title, (300, 100))
                head = "%s over 2000000 Arrow-cached rows:  Best Time(ms)   Avg Time(ms)" % title
                parts += [rule, title, rule, "", "OpenJDK 64-Bit Server VM", head, "-" * 80]
                parts += ["%s  %d  %d  3  12.4  80.5  1.0X" % (OFF, off, off + 9)]
                parts += ["%s  %d  %d  5  12.2  81.6  1.0X" % (ON, on, on + 9), "", ""]
            return "\n".join(parts)

        def prov(options, cpu=CPU + " 64-Core Processor", run=2):
            head = "run:         https://github.com/vecbricks/varka/actions/runs/%d\n" % run
            rest = "cpu:         %s\ninputs:      jdk=25 extra-java-options=%s\n" % (cpu, options)
            return head + rest

        good = prov(WIDTH + " " + REFUSAL_OFF[0], run=1)
        again = prov(WIDTH + " " + REFUSAL_OFF[0])
        hour = "TIMESTAMP hour(ts), " + DST + ", a week, no nulls"
        one = run({hour: (290, 100), cast: (100, 301), plus: (189, 200), fixed: (95, 100)})
        two = run({hour: (295, 102), cast: (110, 291), plus: (195, 198), fixed: (96, 101)})

        def lines_of(text, prefix):
            return [line for line in text.splitlines() if line.startswith(prefix)]

        def refuses(what, runs, message):
            try:
                render(runs)
            except ValueError as e:
                if message in str(e):
                    return 0
                print("selftest: %s was refused with %s" % (what, e))
                return 1
            print("selftest: %s was rendered" % what)
            return 1

        both = {"a-results.txt": (one, good), "b-results.txt": (two, again)}
        got = [line for line in render(both).splitlines() if not line.startswith("#")]
        ntz = "TIMESTAMP CAST(ntz AS TIMESTAMP), " + DST + ", a week, no nulls"
        want = [
            "TO_LOCAL\tkept\t32\t2.90\t" + hour,
            "EARLIER\tkept\t8\t3.00\t" + ntz,
            "GAP_END\trefused\t8\t0.34\t" + cast,
            "KEPT\tkept\t8\t0.95\t" + plus,
            "FIXED\tkept\t14\t0.95\t" + fixed,
        ]
        if got != want:
            print("selftest: rendered\n  " + "\n  ".join(got) + "\nwanted\n  " + "\n  ".join(want))
            failed += 1
        # One run alone reads the sum at 189 / 200, 94 percent, under the threshold: --ratios
        # says refused. Both runs read it at 189 / 198, 95 percent: kept, as it is compared.
        alone = [line for line in form_lines(ratios([one])) if line.startswith("KEPT")]
        if alone != ["KEPT\trefused\t8\t0.94\t" + plus]:
            print("selftest: one run alone read " + repr(alone))
            failed += 1
        # What is no reading is refused: one run, two copies of one run, a run that lacks a
        # section, a form with a section too many, another CPU, the full width, the refusal left
        # on, a provenance that names no run.
        failed += refuses("one run", {"a-results.txt": (one, good)}, "1 run holds the section")
        failed += refuses(
            "two copies of one run",
            {"a-results.txt": (one, good), "b-results.txt": (one, good)},
            "a-results.txt and b-results.txt are one run",
        )
        short = run({}, leave_out=(hour,))
        failed += refuses(
            "a run without a section",
            {"a-results.txt": (one, good), "b-results.txt": (short, again)},
            "1 run holds the section " + hour,
        )
        failed += refuses(
            "two runs without a section",
            {"a-results.txt": (short, good), "b-results.txt": (short, again)},
            "the runs hold 31 sections of TO_LOCAL, not 32",
        )
        for what, bad, message in [
            ("another CPU", prov(WIDTH + " " + REFUSAL_OFF[1], "Neoverse-N2"), "the CPU is not"),
            ("an Arm file's empty CPU", prov(WIDTH + " " + REFUSAL_OFF[0], ""), "the CPU is not"),
            ("the full width", prov(REFUSAL_OFF[0]), "the width is not"),
            ("the refusal left on", prov(WIDTH), "the two-lane refusal was not turned off"),
            ("a run that names no run", again.split("\n", 1)[1], "the provenance names no run"),
        ]:
            runs = {"a-results.txt": (one, good), "b-results.txt": (two, bad)}
            failed += refuses(what, runs, "b-results.txt: " + message)
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)

            def write(first, second, provenance=again):
                for name, text, named in (("a", first, good), ("b", second, provenance)):
                    (root / (name + "-results.txt")).write_text(text, encoding="utf-8")
                    (root / (name + "-provenance.txt")).write_text(named, encoding="utf-8")

            write(one, two)
            record = root / "record.txt"
            record.write_text(render(both), encoding="utf-8")
            if lines_of(record.read_text(encoding="utf-8"), "# runs: ") != [
                "# runs: a-results.txt",
                "# runs: b-results.txt",
            ]:
                print("selftest: the record does not name its two runs")
                failed += 1
            if check(record, root):
                print("selftest: a rendered record does not hold: %s" % check(record, root))
                failed += 1
            # A later reading in which a kept form loses, or a refused form wins, fails the check.
            write(one, two.replace("  195  204", "  150  159"))
            if not any("KEPT\trefused" in p for p in check(record, root)):
                print("selftest: a kept form that starts to lose passed the check")
                failed += 1
            write(one.replace("  301  310", "  90  99"), two.replace("  291  300", "  91  100"))
            if not any("GAP_END\tkept" in p for p in check(record, root)):
                print("selftest: a refused form that starts to win passed the check")
                failed += 1
            # So do a record whose second run is a copy of its first, whose runs lost a section,
            # one of whose runs is gone, runs measured with the refusal on, and a record made of
            # one run.
            write(one, one, good)
            if not any("are one run" in p for p in check(record, root)):
                print("selftest: a record of two copies of one run passed the check")
                failed += 1
            write(one, short)
            if not any("1 run holds the section" in p for p in check(record, root)):
                print("selftest: runs that lost a section passed the check")
                failed += 1
            write(one, two, prov(WIDTH))
            if not any("was not turned off" in p for p in check(record, root)):
                print("selftest: runs with the refusal on passed the check")
                failed += 1
            write(one, two)
            (root / "b-provenance.txt").unlink()
            if not any("is not committed" in p for p in check(record, root)):
                print("selftest: a run without its provenance passed the check")
                failed += 1
            write(one, two)
            text = record.read_text(encoding="utf-8")
            kept = [x for x in text.splitlines() if not x.startswith(("# runs: b-", "#   on "))]
            record.write_text("\n".join(kept) + "\n", encoding="utf-8")
            if not any("1 run holds the section" in p for p in check(record, root)):
                print("selftest: a record made of one run passed the check")
                failed += 1
            unmeasured = [
                "\t".join([f, "kept", "0", "-", NOT_MEASURED + ": fuses there"]) for f in ORDER
            ]
            record.write_text("\n".join(HEADER + [COLUMNS] + unmeasured) + "\n", encoding="utf-8")
            if check(record, root):
                print("selftest: an unmeasured record does not hold: %s" % check(record, root))
                failed += 1
        print("varka_zone_two_lane selftest: %s" % ("failed" if failed else "ok"))
        return 1 if failed else 0


    def main(argv):
        root = pathlib.Path(__file__).resolve().parent.parent
        if argv[:1] == ["--selftest"]:
            return selftest()
        if argv[:1] == ["--render"] and len(argv) > 1:
            try:
                runs = {}
                for name in argv[1:]:
                    path = pathlib.Path(name).resolve()
                    if not path.is_relative_to(root):
                        raise ValueError("a run is a file of this repository: %s" % name)
                    runs[str(path.relative_to(root))] = read_run(path)
                sys.stdout.write(render(runs))
            except ValueError as e:
                print("varka_zone_two_lane: %s" % e)
                return 1
            return 0
        if argv[:1] == ["--ratios"] and len(argv) > 1:
            texts = []
            for name in argv[1:]:
                path = pathlib.Path(name)
                if not path.is_file():
                    print("varka_zone_two_lane: no such file: %s" % name)
                    return 1
                texts.append(path.read_text(encoding="utf-8"))
            print("\n".join(form_lines(ratios(texts))))
            return 0
        if argv[:1] == ["--check"] and len(argv) <= 2:
            record = pathlib.Path(argv[1]).resolve() if len(argv) == 2 else root / RECORD
            problems = check(record, root)
            for p in problems:
                print("varka_zone_two_lane: " + p)
            if not problems:
                print("varka_zone_two_lane: the record holds")
            return 1 if problems else 0
        print(
            "usage: varka_zone_two_lane.py --render RESULTS... | --check [RECORD]"
            " | --ratios RESULTS... | --selftest"
        )
        return 2


    if __name__ == "__main__":
        sys.exit(main(sys.argv[1:]))

## Appendix B. What was run for this plan

The scripts are in the planning scratch folder, `scratch-689` beside the row plans' drafts,
the files of the revision of 28 November 2026 in its folder `fix1`, and those of the
second review's five fixes in `fix2`; they are not committed. A reviewer can run each
again, with `python3 -I`.

| file | what it is | what it printed |
| :--- | :--- | :--- |
| `steps.py` | the four lowerings of 4.3 and 4.4 lane by lane in wrapping 64-bit arithmetic, with a counter of lane calls, against the scalar meaning of 2.5: the ends of a long, each threshold and one either side, 4000 seeded instant batches and 4928 seeded local batches at spans through the bound, the wide batch and the witness of part a's tests, and row 690's hand-set steps | 18 lines `ok`, on 27 and again on 28 November 2026: the counts 10, 10, 24, 28 and 34, 34, 90, 106; equality at every span through `Long.MAX_VALUE` and through `LOCAL_MAX_SPAN + 1`; the witness's error of 900000000 |
| `fix1/steps8.py` | the cases of eight real steps of part a's test 1 and part b's test 1, through the same lowerings and meanings; a null row's storage at the ends of a long | the arguments, the values and the rows that the two tests write; `ok` for each rule; the lane calls 34, 34, 90, 106 |
| `faults.py`, `fix1/tfault.py` | each planted fault of part b's sheet against the same meaning; the gap's end instant computed from one delta too few | the rows each fault fails at, which the sheet names |
| `fix1/checks.smt2`, `fix1/run.smt2` | the 14 proof checks of 4.7 over `java.smt2` | under Z3 5.1.0 and under cvc5 1.4.1 (with `--incremental`): unsat, unsat; unsat, unsat, unsat, sat, unsat, unsat, unsat, unsat, unsat, sat, unsat, sat |
| `fix1/plant-*.smt2` | check 11 with one padding fact changed | `(= plo jlong.MAX)` dropped: sat. `(= pdelta 1)`: sat. Both thresholds 0 with a delta of 0, and `pafter` free: unsat. The same under both solvers |
| `fix2/repo/dev/varka_zone_two_lane.py` | appendix A, byte for byte | `ruff check`: `All checks passed!`; `ruff format --check`: `1 file already formatted`; `--selftest`: `varka_zone_two_lane selftest: ok` |
| `fix2/plant/*.py` | the tool with one fault each: `RUNS = 1`; the count test off; the refusal test off; the test of two files of one run off; `<=` for `<` at the threshold | each `--selftest` exits 1 and prints the lines part c's sheet names; the last prints `selftest: rendered` with the two lines at 0.95 refused |
| `fix2/e2e/` | a made-up repository of six runs with their provenance files, each naming a run of its own: `--render`, `--check`, the two planted faults of the close, a run outside the repository, `--ratios`; then one run's two files copied over another's, and a provenance file with no `run:` line | the record of 4.9's shape with 32, 8, 8, 8 and 14 sections; `the record holds`; exit 1 with the line twice; `a run is a file of this repository`; five lines; `... are one run, ...: measure again` and exit 1 from `--render` and from `--check`; `the provenance names no run` and exit 1 |
| `fix2/half2/` | master's `dev/varka_precommit.sh` and `build_and_test.yml` with steps 4 to 7 of part c applied as the sheet writes them: the old text of each insertion, the five B0 lines of a cut's second half, `bash -n`, and a YAML parse of the workflow | each old text once; 2, 1, 1, 1 and `the record holds`; no syntax error; the new step between the catalogue's step and the sweeps' |
| `fix1/check_edits.py`, `fix1/b0.py` | every old text of section 13 against master `9e7b1612402`; every command of section 3 and every B0 line over master's own files | 1 for each old text; the value after each `#` |
