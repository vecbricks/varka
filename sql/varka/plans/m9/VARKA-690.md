# VARKA-690: A local time to an instant: `ToInstant` and `ToInstantKept`

*Planned on 27 November 2026. This plan stands alone: a builder needs this file and the
repository's `AGENTS.md` files, nothing else. Not started: milestone 9 waits for the owner's
word (`m9/PLAN.md` decision 18), and nothing of it starts before the tag `varka-m8`. The code
facts below were checked on master `84d6b2aff10`. The rows this one builds on (682, 683, 656,
678, 657, 684 and 688) were not built on that day, so every build sheet starts with greps of
their names.*

## 1. What this row is

**The row's text** (`sql/varka/plans/m9/PLAN.md`, section 3). "A local time to an instant:
`ToInstant` and `ToInstantKept`. `VARKA-657.md` 2.4 and 3.7: the records and their registries;
`CAST(ntz AS TIMESTAMP)` under the earlier offset; `CAST(d AS TIMESTAMP)` under the gap's end;
`ts + INTERVAL DAY` under the kept offset; each rule in the sweep with its kernel, its planted
faults and the there-and-back check. **Done when** the first kernel of each rule fuses with
tests whose rows straddle both changes of a year, the sweep passes in every Build under the
three rules, and each planted fault of a rule fails a named test."

**What it gives the rows after it.**

- Row 669 (the casts of `TIMESTAMP_LTZ`): `VarkaZoneCompiler.toInstant`, for the cast from
  `TIME`, and the two casts this row fuses as its models.
- Row 670 (`to_utc_timestamp`, `convert_timezone`, `make_timestamp`, `date_trunc`,
  `months_between`): `toInstant` under `EARLIER` and under `GAP_END`.
- Row 671 (the timestamp arithmetic): `toInstantKept`, and the tree of `ts + INTERVAL DAY`
  for a literal, which it widens to an interval column and to months.
- Row 689 (zone steps at two lanes): the masked steps of the three rules, to which it adds
  the steps with no mask, and the design notes of appendix A.
- Row 693 (the table of the local line): the two records, the view `VarkaBatchArgs.dataOf`
  and the refusal it lifts, `VarkaKernelPlan.ZONE_LOCAL_TABLE_REASON`.
- Row 691 measures this row's three sections; row 679's check V1 copies the first test of
  each record in `VarkaEmitterZoneSuite`.

**What must be merged before it starts.** Row 657's parts A to D, and so rows 682, 683, 656,
678 and 654. Parts e and f also need row 688's parts a to d, and parts g and h row 684;
section 5 has the order.

**The goals it serves** (`m9/PLAN.md` 1.1), each quoted, with the item of 1.2 that it moves
and how a reader checks it.

| goal, as written | item of 1.2, as written | what this row moves, and the check |
| :--- | :--- | :--- |
| "**G1. `TIMESTAMP_LTZ` at microseconds (phase 1).**" | "at least three quarters of `TIMESTAMP_LTZ`'s (entry, type) pairs fused" | none counted: the sum of a timestamp and a day-time interval stays fused in part (`ltz^`) until row 671 takes an interval column. Check: `python3 dev/varka_census.py --summary`, the line of `ltz`, is the same before and after part f |
| "**G2. Every time zone, proven.**" | "the every-zone sweep passes in CI under each rule by which Spark resolves a local time in a gap or an overlap (2.1); every kernel that reads a time zone has a test whose rows straddle both of a year's changes of offset" | both clauses, for the way back. Check: `VarkaZoneSweepSuite` in the Build's log with the tests of 4.12; `VarkaTimestampZoneSuite` with the tests of parts d, e and f. The ten-year clause is served only through row 693: here a batch of more than eight local changes declines |
| "**G3. The remaining datetime features (phase 1).**" | "at least two thirds of the microsecond temporal casts fused or declined with their reason" | two cast pairs, `ntz` to `ltz` and `date` to `ltz`, fused in the three modes. Check: `casts_fused.tsv` of milestone 9 reads `fused` three times on each line |
| "**G7. Speed where a time zone is read.**" | "every `TIMESTAMP_LTZ` section whose kernel reads the zone runs at least five times the row path's rate" | three sections are listed as fused and measured at both widths (part i). The bar is read by row 691 |
| G4, G5, G6 | items 4, 8 and 9 | only through later rows: the nanosecond casts take these conversions on their microsecond word (row 673); every part here is built at full strength (section 8); the post quotes the measurement (row 676) |

## 2. What an agent must know

### 2.1 The idea

A `TIMESTAMP` is a long: microseconds since the epoch in UTC, an *instant*. A `TIMESTAMP_NTZ`
is a long of the same unit that names a wall-clock reading, a *local time*. A zone's offset is
a step function of the instant: a sorted list of instants `T[i]`, the *changes*, and the
offset `O[i]` that holds from `T[i]` on. Row 682's node `ToLocal` adds the offset to an
instant. This row goes back: from a local time `L` to the instant that reads `L` on the wall.

Going back is not a function at two kinds of place. Where the offset grows (the clocks go
forward) a stretch of local times does not exist: a *gap*. Where it shrinks (the clocks go
back) a stretch exists twice: an *overlap*. For a change at `T` between the offsets `before`
and `after`, the stretch is the *window* `[lo, hi)` on the local line, with
`lo = T + min(before, after)` and `hi = T + max(before, after)`.

`java.time` answers a local time in a window by one of three rules, and Spark uses each.
Outside every window the three agree.

| rule | `java.time` | in a gap | in an overlap | Spark's first user here |
| :--- | :--- | :--- | :--- | :--- |
| the earlier offset, `EARLIER` | `LocalDateTime.atZone(zone)` | `L - before`: the time moves later by the gap's length | `L - before`, the earlier of the two instants | `CAST(ntz AS TIMESTAMP)` |
| the gap's end, `GAP_END` | `LocalDate.atStartOfDay(zone)` at a midnight | `T`, the change's own instant | as `EARLIER` | `CAST(d AS TIMESTAMP)` |
| the kept offset, `KEPT` | `ZonedDateTime.ofLocal(ldt, zone, preferred)` | as `EARLIER` | `L - after` if the preferred offset is `after`, else as `EARLIER` | `ts + INTERVAL DAY` |

A kernel does not look a zone up for each row. Before each call the runner finds the least
and greatest value of the node's operand in the batch, asks the zone's data for the windows
that range touches, and writes them into the kernel's long arguments. The kernel subtracts a
first offset from every row and, for each window, corrects the rows at or past it. A kernel
is emitted for a *form*: how many windows a batch may touch. A batch that touches more
declines, and Spark's row path answers it.

### 2.2 Terms

- **Instant**, **local time**, **change**, **gap**, **overlap**, **window**: 2.1.
- **Zone node**: an IR record under the sealed interface `Zoned`. **Operand**: the value whose
  range in a batch decides the node's arguments, `Zoned.operand()`. For this row's records it
  is the local time.
- **Batch argument**: an entry of the kernel's `long[] longArgs`, after the literals, that the
  runner writes before each call. **Program**: one zone node's run of batch arguments.
- **Form**: the value of the emit option `zoneForm`. `ONE`: no step. `STEPS_2`, `STEPS_8`: up
  to two or eight steps. `k` below is the form's count of steps. Row 685 adds `LADDER` (the
  evaluator takes for each batch the least form that covers it), row 686 `TABLE` and
  `TABLE_SCALAR`.
- **Step**: one window as a kernel reads it. **Padding**: a step that never fires, each
  threshold `Long.MAX_VALUE` and each difference 0.
- **Decline**: at run time the runner gives one batch to the row path
  (`VarkaKernelRunner.STATUS_BATCH_ARGS`, metric `batch-args`). **Declines by name**: the
  compiler builds no kernel for an entry and records a reason text. **Refusal**: the kernel
  plan (`VarkaKernelPlan.of`) will not emit a tree.
- **Masked step**: a compare and a masked add. **Step with no mask**: the same sum from a sign
  word, for two 64-bit lanes on x86, where C2 lowers no mask (row 689's option `zoneStepMask`).
- **Sink**: `DeclineSink`, the object every compiler arm receives: the first decline reason of
  an entry, and the literal and zone tables with their marks.
- **`M`**: `VarkaBatchArgs.MAX_OFFSET_MICROS`, 64,800,000,000: 18 hours, the bound of every
  offset (`java.time.ZoneOffset`).

### 2.3 The names this row reads

From the merged plans of the rows named; each sheet's B0 greps them.

| name | from | meaning |
| :--- | :--- | :--- |
| `sealed interface Zoned extends VarkaVectorIR permits ToLocal` with `int zone()` and `VarkaVectorIR operand()`; `record ToLocal(VarkaVectorIR instant, int zone, int hash)` | 682 | the family and its first record. `zone` is an index into `CompiledVarkaProjection.zones()`, a `List<String>` of zone ids |
| `VarkaBatchArgs`: `Program(Zoned node, int first, int count)`, `layout(outputs, numLiterals, options)`, `count(node, form)`, `operandColumn(node)`, `MAX_OFFSET_MICROS` | 682 | the layout of the batch arguments, one fixed walk that the emitter and the runner share |
| `VarkaZoneData`: `id()`, `fixed()`, `changes(least, greatest)`, `localChanges(least, greatest)`, `toLocalArgs(least, greatest, steps, args, first)`, `toInstantArgs(Zoned node, least, greatest, steps, args, first)`; `Provider.of(zoneId)`, null for a zone not served | 682 | one zone's data as a runner reads it |
| `VarkaChronoZone(String id, VarkaChronoZoneChanges data)`, `VarkaChronoZone.PROVIDER`, `VarkaChronoZone.of(zoneId)` | 678 | the production zone data. Its `toInstantArgs` is a switch over `Zoned` with one arm, which throws for a `ToLocal` |
| `VarkaChronoZoneChanges.Rule { EARLIER, GAP_END, KEPT }`, `toInstantArgs(Rule, long least, long greatest, int steps, long[] args, int first)`, `localChanges(least, greatest)` | 678, part b | the arguments of each rule (2.5), and the count of windows a local range touches: `MANY` (4096) for a range within 36 hours of an end of a long |
| `VarkaJavaTimeZoneData` | 682, tests | a reference over `java.time`. Its `toInstantArgs` throws for a `ToLocal`. This row does not use it as an oracle |
| `VarkaZoneLowering.emitToLocal(cb, n, dense, analysis, s, computed)`, `laneCalls(form)` | 682 | the lowering of `ToLocal`: the model of this row's two methods |
| `Slots.batchArgLocal` (one long local an argument), `Slots.zoneTmp` (a node's vector locals) | 682 | what a zone lowering reads |
| `VarkaKernelPlan.Kind.ZONE_OPERAND`, `ZONE_OPERAND_REASON` | 682 | the plan's refusal of a zone node over a computed operand, until row 688 |
| `VarkaZoneCompiler`: `arm`, `toLocal(whole, timeZoneId, instant, sink)`, `served(id)`, `MAX_OFFSET_MICROS`, `NO_ZONE_REASON`, `unresolvedReason(id)`, `unservedReason(id)`, `COMPUTED_OPERAND_REASON`; `VarkaFamily.ZONE`; `DeclineSink.zone(id)` | 657 | the family's compiler: the fold of a fixed zone, the zone's index, the declines by name |
| `VarkaZonedDeclines.reason(e)`, `notYet(what, row)` | 656 | the reason of an expression over a `TIMESTAMP` that no arm claims: `<what> is row N's: not fused yet` |
| `VarkaOperandRange.unsupported(operand)`, `IntRangeOps.leastAndGreatest`; `VarkaZoneCompiler.zonedPlusInterval`, `ZONED_INTERVAL_REASON`, `unrangedReason(name)` | 688, parts a to d | the range of a computed operand for a batch; the arm of `ts + dt` for a literal under a day |
| `VarkaZoneKernels` (`Rule`, `Points`, `Kernel`, `Filler`, `Spec`, `Report`, `Mismatch`, `sweep`, `production`, `TO_LOCAL`), `VarkaZoneSweepSuite`, `VarkaZonePlantedFaultSuite`, `VarkaZoneFaults`, `VarkaZoneArgsPropertySuite`, `VarkaIrGrammar.drawZoneShape` | 684 | the proof's harness and suites |
| `VarkaNodeCompiler.midnightOf(day, sink)`, `ntzMidnightDay(day)`, `compileNode` | master | the midnight of a date on the long lane, under its guard |
| `DeclineSink.declinesTwoLaneChecks()` | master | true on an x86 host whose 64-bit masks run as Java loops |

### 2.4 The names this row adds

| name | where | part | meaning |
| :--- | :--- | :--- | :--- |
| `enum Resolve { EARLIER, GAP_END }` | `VarkaVectorIR` | a | the two rules that read the local time alone |
| `record ToInstant(VarkaVectorIR local, int zone, Resolve rule, int hash)` | `VarkaVectorIR` | a | a local time to an instant by `rule` |
| `record ToInstantKept(VarkaVectorIR local, VarkaVectorIR kept, int zone, int hash)` | `VarkaVectorIR` | b | the same with the offset `kept`, in microseconds, preferred in an overlap |
| `VarkaBatchArgs.dataOf(Zoned node, VarkaZoneData zone)`, `readsLocalLine(List<Program>)`, `LOCAL_MAX_SPAN` | `VarkaBatchArgs` | b | a zone's data on the node's own time line (4.4) |
| `VarkaZoneLowering.emitToInstant`, `emitToInstantKept`, `laneCalls(Zoned node, ZoneForm form)` | `VarkaZoneLowering` | a, b, c | the lowerings (4.5 and 4.6) |
| `VarkaKernelPlan.Kind.ZONE_LOCAL_FORM`, `ZONE_LOCAL_STEPS_REASON`, `ZONE_LOCAL_TABLE_REASON` | `VarkaKernelPlan` | a | the refusal of a form no lowering of these records has yet (4.9) |
| `VarkaZoneCompiler.toInstant(whole, timeZoneId, local, rule, sink)`, `toInstantKept(whole, timeZoneId, local, kept, sink)`, `TWO_LANE_LOCAL_RULE_REASON` | `VarkaZoneCompiler` | d, e, f | the compiler's way back (4.10) |
| `VarkaLocalLineSuite` | catalyst tests, Java, JUnit 5 | a, b | the records, the counts, the view, the one-offset kernels |
| `VarkaZoneKernels.TO_INSTANT_EARLIER`, `TO_INSTANT_GAP_END`, `KEPT_AFTER`, `KEPT_BEFORE`, `KEPT_OTHER` | catalyst tests | g | the sweep's rules (4.12) |
| `VarkaLocalLineKernels` (`changesOf`, `lo`, `hi`, `localFixture`, `run`, `runLoaded`, `expected`, `filled`) | catalyst tests, Java, public | a, b | the fixture of a zone's year and one kernel run, for the suites of both modules (5.1, 5.2) |
| `ntzy`, `dy` | columns of the coverage table | d, e | a `TIMESTAMP_NTZ` and a `DATE` inside the year 2021 |

### 2.5 The arguments of a program, fixed by row 678

`VarkaChronoZoneChanges.toInstantArgs(rule, least, greatest, steps, args, first)` writes
`args[first]`, the *base*: the offset in force before the first window the range touches, in
microseconds. Then, for each window `i` in order, with `delta = after - before` and `after`
the offset from the change on, both in microseconds:

| rule | the arguments of step `i`, in order | count | padding |
| :--- | :--- | ---: | :--- |
| `EARLIER` | `hi[i]`, `delta[i]` | `1 + 2k` | `Long.MAX_VALUE`, 0 |
| `GAP_END` | `hi[i]`, `lo[i]`, `delta[i]`; for an overlap `lo[i]` is written as `hi[i]` | `1 + 3k` | `Long.MAX_VALUE`, `Long.MAX_VALUE`, 0 |
| `KEPT` | `hi[i]`, `lo[i]`, `after[i]`, `delta[i]`; for a gap `lo[i]` is written as `hi[i]` | `1 + 4k` | `Long.MAX_VALUE`, `Long.MAX_VALUE`, 0, 0 |

It throws `IllegalArgumentException` when the range is within 36 hours of an end of a long or
touches more windows than `steps`; the caller asks `localChanges` first.

**The scalar meaning,** which row 678's tests evaluate, the library's `VChrono.toInstant` and
`toInstantKept` state (`VARKA-679.md` 4.6), and this row's lowering must equal on every lane.
`offset(L)` is the base plus the `delta` of each step that fires.

- `EARLIER`: step `i` fires when `L >= hi[i]`. The instant is `L - offset(L)`.
- `KEPT`, with `kept` the preferred offset: step `i` fires when
  `L >= hi[i] || (L >= lo[i] && kept == after[i])`. The instant is `L - offset(L)`. A `kept`
  that is no offset of the zone equals no `after[i]`, so such a lane answers as `EARLIER`:
  a `kept` that is no whole second, or is past 18 hours, among them. `java.time` has no
  offset for such a value, and the reference reads it as no preference (4.2). Section 13,
  edit 10, makes row 678's sentence say the same.
- `GAP_END`: if `lo[i] <= L < hi[i]` for some `i`, the instant is `T[i]`, which is
  `hi[i] - (base + delta[0] + ... + delta[i])`. Else as `EARLIER`.

Windows do not touch (row 678's check C6: `hi[i] <= lo[i + 1]`), so the steps that fire are
always the first few, and `L - offset(L)` is `L` less one real offset of the zone.

### 2.6 What the probes measured

`sql/varka/spikes/zone-offset/results/`, on the laptop (an AMD Ryzen AI 9 HX PRO 370, JDK 25).
`resolve.txt`: over the 604 zone ids, "(a) the earlier offset, atZone: 3095758 checks, 0
mismatches", "(b) the kept offset, ofLocal: 10571032 checks, 0 mismatches", "(c) the gap's
end, atStartOfDay: 2370096 checks, 0 mismatches". The earlier offset and the gap's end differ
at a midnight on one day, 31 March 1919, in six ids (`America/Toronto` among them).
`kernels.txt`, ns a row, a year of values in no order with half near a change:

| | 8 lanes | 4 lanes, AVX2 | 2 lanes, AVX2 |
| :--- | ---: | ---: | ---: |
| `java.time`, `LocalDateTime.atZone` | 43.163 | 43.764 | 43.752 |
| to an instant, the earlier offset, 2 steps | 0.085 | 0.138 | 13.936 |
| `java.time`, `ZonedDateTime.ofLocal` | 44.484 | 42.424 | 42.319 |
| to an instant, the kept offset, 2 steps | 0.248 | 0.351 | 43.030 |

At two lanes the masked steps run as Java loops. The earlier offset still beats the row path
there; the kept offset does not (`kernels-2.txt` reads 44.333). No probe timed the gap's end.

## 3. The admission check

Paths: `V` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka`,
`C` is its parent folder, `E` is `sql/core/src/main/java/org/apache/spark/sql/execution`, `TJ`
and `TS` are the `src/test/java` and `src/test/scala` twins of `V`, `CJ` is the
`src/test/java` twin of `C`, `XJ` and `XS` are `sql/core/src/test/java` and `.../scala` under
`org/apache/spark/sql/execution`, `SC` is
`sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst` and `SA` is
`sql/api/src/main/scala/org/apache/spark/sql/catalyst/util`. Every command below was run by
a script in a worktree of master at `84d6b2aff10`, from the repository's root, and printed
the value after its `#`. Each command is one line.

**What Spark's row path calls** (the expressions' own code, not OpenJDK's).

1. `CAST(ntz AS TIMESTAMP)` is `convertTz(ts, zoneId, ZoneOffset.UTC)` in every mode, and
   `convertTz` reads the value as a local date-time and calls `atZone`. So it is the rule
   `EARLIER`.

       grep -c 'buildCast\[Long\](_, ts => convertTz(ts, zoneId, ZoneOffset.UTC))' $SC/expressions/Cast.scala   # 1
       grep -c 'getLocalDateTime(micros, toZone).atZone(fromZone)' $SA/SparkDateTimeUtils.scala   # 1

2. `CAST(d AS TIMESTAMP)` is `daysToMicros(d, zoneId)` in every mode. `daysToMicros`
   multiplies exactly where the zone is the object `ZoneOffset.UTC` and else calls
   `atStartOfDay`. So it is the rule `GAP_END` at the day's midnight.

       grep -c 'buildCast\[Int\](_, d => daysToMicros(d, zoneId))' $SC/expressions/Cast.scala   # 1
       grep -c 'daysToLocalDate(days).atStartOfDay(zoneId).toInstant' $SA/SparkDateTimeUtils.scala   # 1

3. `ts + dt` is `TimestampAddInterval(start, interval, timeZoneId)`; for a day-time interval
   its `nullSafeEval` calls `timestampAddDayTime(start, interval, zoneIdInEval)`.

       grep -c 'def timestampAddDayTime(micros: Long, dayTime: Long, zoneId: ZoneId): Long = {' $SC/util/DateTimeUtils.scala   # 1

   Its body, word for word:

       val days = dayTime / MICROS_PER_DAY
       val microseconds = dayTime - days * MICROS_PER_DAY
       val resultTimestamp = microsToInstant(micros)
         .atZone(zoneId)
         .plusDays(days)
         .plus(microseconds, ChronoUnit.MICROS)
       instantToMicros(resultTimestamp.toInstant)

   The division truncates, so `days` and `microseconds` have the sign of `dayTime`.
4. Each of the three ends in `instantToMicros`, which multiplies and adds exactly: a result
   outside a long raises `ArithmeticException` in every mode.

       grep -c 'val us = Math.multiplyExact(secs, MICROS_PER_SECOND)' $SA/SparkDateTimeUtils.scala   # 1

5. The analyzer builds the sum for `ts + dt`, and `ts - dt` as the sum of a negation that the
   optimizer folds for a literal. `d + dt`, for an interval that is not whole days, is the
   sum over `Cast(d AS TIMESTAMP)`.

       grep -c 'TimestampAddInterval(l, UnaryMinus(r' $SC/analysis/BinaryArithmeticWithDatetimeResolver.scala   # 1
       grep -c 'case (DateType, _: DayTimeIntervalType) => TimestampAddInterval(Cast(l, TimestampType), r)' $SC/analysis/BinaryArithmeticWithDatetimeResolver.scala   # 1

**What `java.time` does, stated here and held by a test through its public API.** A builder
does not open OpenJDK's sources (the owner's word of 27 November 2026).

6. `LocalDateTime.atZone(zone)` is `ZonedDateTime.ofLocal(ldt, zone, null)`. Test 6 of 5.1.
7. `ZonedDateTime.ofLocal(ldt, zone, preferred)`: with one valid offset, that offset; in a
   gap, the local time moved later by the gap's length, in the offset after; in an overlap,
   `preferred` if it is one of the two, else the earlier offset. Test 9 of 5.2.
8. `LocalDate.atStartOfDay(zone)`: for a midnight inside a gap, the gap's end, which is the
   change's instant; else `atZone` of the midnight. Test 6 of 5.1, on `America/Toronto`.
9. `ZonedDateTime.plusDays(n)` adds to the local date-time and resolves it by `ofLocal` with
   the value's own offset as `preferred`; `plus(n, ChronoUnit.MICROS)` adds to the instant.
   Held by the kernel tests of part f against Spark's own function, on rows a day before and
   a day after each overlap.

**Where Varka's code meets them.**

10. The midnight of a date exists, under a guard. Both methods are package-private, and
    `VarkaZoneCompiler` is in that package.

        grep -c 'static VarkaVectorIR ntzMidnightDay(VarkaVectorIR day) {' $C/VarkaNodeCompiler.java   # 1
        grep -c 'static VarkaVectorIR midnightOf(VarkaVectorIR day, DeclineSink sink) {' $C/VarkaNodeCompiler.java   # 1

11. No arm claims a cast to `TIMESTAMP` or a sum over one today. The chain's order is in
    `VarkaNodeCompiler.FAMILIES`; row 657 puts its link after `TIME`.

        grep -n 'TimestampType\b' $C/*.java | grep -vc NTZ   # 0

12. The lane has the calls the lowerings need. The comment of `lanewiseVIMasked` reads `V
    V.add/sub(scalar, VectorMask)`, and `LongVector.blend(long, VectorMask)` has the same
    descriptor. `MASK_BINARY` is `VectorMask.and` and `or`.

        grep -c 'final MethodTypeDesc lanewiseVIMasked;' $V/Lane.java   # 1
        grep -c 'static final MethodTypeDesc MASK_BINARY = MethodTypeDesc.of(VECTOR_MASK, VECTOR_MASK);' $V/VarkaDescriptors.java   # 1

13. The warm-up runs every call shape of 4.6 on the long lane: the scalar add, the masked
    add and the compares in one block, `blend(long, mask)`, and the masks' `and` and `or` in
    its private `masks`. `VarkaWarmupReachSuite` is in the commands of parts d to f; if it
    names a cold method, the builder adds that call to the long lane's block and says so.

        grep -c 'a.add(1L, m).lane(0)' $V/VarkaVectorTemplateWarmup.java   # 1
        grep -c 'a.blend(9L, m).lane(0)' $V/VarkaVectorTemplateWarmup.java   # 1
        grep -c 'm.or(tail)' $V/VarkaVectorTemplateWarmup.java   # 4

14. A host flag for two masked lanes reaches every arm.

        grep -c 'boolean declinesTwoLaneChecks() {' $C/DeclineSink.java   # 1

15. Rule R1 of `dev/varka_push_check.py` (`REGISTRIES`) asks for a new record's name in
    `VarkaWithChildrenSuite`, `VarkaIrFuzzSuite`, `VarkaLaneTypeSuite`,
    `VarkaEmitterBudgetSuite`, `VarkaReproductionSuite`, `VarkaEmitCostSuite`,
    `VarkaSmtNodesSuite` and `VarkaRangeAnalysisSuite`. `VarkaEmitterBudgetSuite` emits the
    node alone and counts its lane calls. So a record cannot merge before one lowering of it:
    parts a and b carry the one-offset form, and no compiler arm builds either record until
    part d, so no kernel of a query holds one and no generated file moves. For
    `VarkaRangeAnalysisSuite` the rule looks for `<name>: <Record>` as a whole word inside
    `admissible`, so each record needs an arm of its own there.

        grep -c '^REGISTRIES = {' dev/varka_push_check.py   # 1
        grep -c '"VarkaRangeAnalysisSuite": (' dev/varka_push_check.py   # 1

16. The record pattern, and how a record under a sealed sub-interface is permitted. A
    record's header wraps in the source, so the grep reads its first line. `Cond` and
    `Chrono` stand in `VarkaVectorIR`'s `permits` list; their records stand only in their
    own. So `Zoned` is in the interface's list (row 682), and this row's records join
    `Zoned`'s list alone.

        grep -c 'record HashStep(HashMix mix, VarkaVectorIR seed, VarkaVectorIR word, int hash)' $V/VarkaVectorIR.java   # 1
        grep -c 'VarkaVectorIR.NextDay, VarkaVectorIR.ThursdayOf, VarkaVectorIR.Chrono,' $V/VarkaVectorIR.java   # 1

17. The helpers the test class of 5.1 calls exist with these signatures.

        grep -c 'public static Emitted emit(List<VarkaVectorIR> roots, int numInputs, int numLiterals,' $TJ/VarkaEmitterFixtures.java   # 1
        grep -c 'public static Loaded load(Emitted emitted) {' $TJ/VarkaEmitterFixtures.java   # 1
        grep -c 'public static Col makeLongInput(Arena arena, int length, IntPredicate isNull,' $TJ/VarkaEmitterFixtures.java   # 1
        grep -c 'public static Output makeLongOutput(Arena arena, int length) {' $TJ/VarkaEmitterFixtures.java   # 1
        grep -c 'public static OptionalLong mixedValue(VarkaVectorIR node, long\[\] row, boolean\[\] isNull,' $TJ/VarkaReference.java   # 1
        grep -c 'static Source source(String schema, List<Object\[\]> rows) {' $XJ/VarkaHarnessCases.java   # 1
        grep -c 'static int sanitized(VarkaFusedKernel kernel, int length, long\[\] inStride,' $TJ/VarkaKernelChecks.java   # 1
        grep -c 'static SQLConf conf(boolean ansi) {' $XJ/VarkaCoverageHarnessSuite.java   # 1

18. No name of this row exists, and no branch of it.

        git grep -c -w 'ToInstant\|ToInstantKept\|dataOf\|VarkaLocalLineKernels' -- sql/catalyst/src sql/core/src | wc -l   # 0
        git branch --list 'varka-690*' | wc -l   # 0

**What I could not check.** No name of 2.3 but its last two rows is on master: each is read
from its row's merged plan, and no B0 line over rows 682, 678, 657, 656, 684 and 688 could
be run. Two shapes are in no plan and are printed by B0 where a sheet needs them: the place
in `sql/core` where a program takes its zone data (it moves with rows 685 and 688; section
13 edits their plans), and the home of the constant `MANY`. Rows 653, 655 and 660 are
planned beside this one (section 12). No JVM was run: the byte counts and the times of
section 7 are predictions. The census counts of 4.14 were derived from
`dev/varka_census.py`'s rule (`matrix_fused`) and `VARKA-654.md` 4.10, not printed: row
654's files do not exist yet.

## 4. The design

### 4.1 The records

In `VarkaVectorIR`, after `ToLocal`, and added to `Zoned`'s `permits` list alone.
`VarkaVectorIR`'s own list names `Zoned`, as it names `Cond` and `Chrono`, and a record
under one of them stands in no other list (fact 16):

    enum Resolve { EARLIER, GAP_END }
    record ToInstant(VarkaVectorIR local, int zone, Resolve rule, int hash) implements Zoned
    record ToInstantKept(VarkaVectorIR local, VarkaVectorIR kept, int zone, int hash)
        implements Zoned

- Each follows `ToLocal`'s pattern, which is `HashStep`'s. The compact constructor checks:
  `local` is on the long lane and is no `Cond`; `zone >= 0`; `rule` is not null; `kept` is on
  the long lane and is no `Cond`. Then it sets `hash = hashOf(local, zone, rule)`, or
  `hashOf(local, kept, zone)`, whatever was passed. Public constructors `ToInstant(local,
  zone, rule)` and `ToInstantKept(local, kept, zone)` pass 0. `hashCode()` returns `hash`.
  `operand()` returns `local` in both: the kept offset has no range to find.
- `props()`: ring `NEVER`, no int overflow check, validity `STRICT`, cost kind `"ToInstant"`
  or `"ToInstantKept"`, `FILLS_NOTHING`, `RESTRICTS_NOTHING`, `LongNarrowReader.NONE`. The
  weight is `ToLocal.props()`'s expression with `VarkaZoneLowering.laneCalls(this, form)` for
  its `laneCalls(form)`: the same form, this record's calls (4.6).
- A null operand makes a null. A record fails no row and declines no row.
- Canonical text: `(toInstant:<zone>:<RULE> <local>)` and `(toInstantKept:<zone> <local>
  <kept>)`. `VarkaCanonicalIr.node` parses them:
  `case "toInstant" -> new ToInstant(node(), Integer.parseInt(parts[1]),
  Resolve.valueOf(parts[2]))`, and for `"toInstantKept"` a block that reads `node()` twice,
  the local first, then `new ToInstantKept(local, kept, Integer.parseInt(parts[1]))`.
- Every switch arm names the record, never `Zoned`.

### 4.2 Every switch and registry a record passes

`m8/VARKA-489.md`, "Adding a node, after 489c", lists them; `VARKA-682.md` 4.1 gave `ToLocal`
an arm in each. The arm of `ToInstant t` (and of `ToInstantKept k`, where it differs):

| switch or registry, file | arm |
| :--- | :--- |
| `VarkaVectorIR.laneType` | `LONG` |
| `narrowValuesIn`, `needsNarrowSpecies`, `canonicalWith`, `canonicalShallow` | `ToLocal`'s arm with `t.local()`; for `k`, both operands, the local first |
| `childrenOf` | `{t.local()}`; `{k.local(), k.kept()}` |
| `withChildren` | `new ToInstant(children[0], t.zone(), t.rule())`; `new ToInstantKept(children[0], children[1], k.zone())` |
| `Analysis.collectRefusals` | empty |
| `Analysis.analyze` | `analyzeOp(node, false, t.local())`; `analyzeOp(node, false, k.local(), k.kept())` |
| `Slots.WordWalk.visit` | empty |
| `Slots.plan` | as `ToLocal`: the argument locals from the program's count; `zoneTmp` of one vector local for `t` and of two for `k`, in every form with steps |
| `VarkaVectorWalk.emitValue` | `VarkaZoneLowering.emitToInstant(cb, t, dense, analysis, s, computed)`; `emitToInstantKept(cb, k, ...)` |
| `VarkaKernelPlan.longNarrowRole` | joins `ToLocal` in the `NONE` list |
| `VarkaRangeAnalysis.range` | `kind != Kind.INT ? UNKNOWN : ZONE_SHIFT.over(range(t.local(), ...))`: the operand's range, 18 hours wider each way; the kept offset is not read |
| `VarkaSameAnswer` | joins the `byValueChildren` list |
| `VarkaChronoCompiler.rearm` | joins `ToLocal`'s list, `new Rebuilt(node, false, false)` |
| `VarkaBatchArgs.count` | 4.3 |
| `VarkaEmitBudget.weightOf(node, options, narrow)` | `VarkaZoneLowering.laneCalls(node, <the form ToLocal's arm reads>)` |
| `VarkaChronoZone.toInstantArgs` (row 678) | `case ToInstant t -> data.toInstantArgs(t.rule() == Resolve.EARLIER ? Rule.EARLIER : Rule.GAP_END, least, greatest, steps, args, first)`; `case ToInstantKept k -> data.toInstantArgs(Rule.KEPT, ...)` |
| `VarkaOperandRange.walk` (row 688, if merged) | the local operand's ops, then `SHIFT`, as `ToLocal`'s arm |
| `VarkaJavaTimeZoneData.toInstantArgs` (tests) | throws `UnsupportedOperationException("the java.time reference fills no local-time program: VarkaChronoZone does")` |
| `VarkaReference.value`, `longValue` (tests) | throw, as `ToLocal`'s arms do, with this record's name |
| `VarkaReference`'s `MixedRow` (tests) | the `java.time` definition, 4.3 |
| `VarkaIrGrammar.boundsOf` | throws "ToInstant has no production draw (VARKA-690, part h)" until part h |
| `VarkaGuardSpecs.fires` | joins the no-guard list |
| `VarkaWordCensus.pure` | `pure(t.local(), ...)`; for `k`, `pure(k.local(), ...) && pure(k.kept(), ...)` |
| `VarkaCanonicalIr.node` | 4.1 |
| `VarkaSmtNodes` | an entry in `REFUSED`: "offsets are batch arguments, not a function of the row" |
| `VarkaReproductionSuite` | `theRest` builds one |
| `VarkaNodePropsSuite` | the record floor rises by one a record |
| `VarkaLaneTypeSuite` | `case "ToInstant"` in `instance`, and the sets `ToLocal` is in |
| `VarkaIrFuzzSuite` | the name in `missing` and in `outOfTheLongCorpora`, until part h |
| `VarkaWithChildrenSuite` | the name in `undrawn`, and one direct rebuild test, until part h |
| `VarkaEmitCostSuite` | the name in `awaitingRefit` |
| `VarkaEmitterBudgetSuite` | a row a rule, form `ONE`: `(node, 1, 0, VarkaMatrix.base.withZoneForm(ZoneForm.ONE))`, which is the node, its inputs, its literals and its options; `(node, 2, 0, ...)` for `k`, which reads two columns |
| `VarkaRangeAnalysisSuite` | `case x: ToInstant` in `walk.value` and in `admissible.go`, as `ToLocal`'s; for `k` a second arm in each, `case x: ToInstantKept`: rule R1 reads each record's name as a whole word |

**The reference's definition** (`MixedRow`, with `zone = DateTimeUtils.getZoneId(zones.get(
n.zone()))`, `L` the local operand, and `ldt = LocalDateTime.ofEpochSecond(Math.floorDiv(L,
1_000_000L), (int) Math.floorMod(L, 1_000_000L) * 1000, ZoneOffset.UTC)`):

- `EARLIER`: `micros(ldt.atZone(zone).toInstant())`.
- `GAP_END`: `ZoneOffsetTransition t = zone.getRules().getTransition(ldt)`; if `t != null &&
  t.isGap()`, `t.getInstant().getEpochSecond() * 1_000_000L`; else as `EARLIER`.
- `ToInstantKept`, with `K` the kept operand: `preferred` is
  `ZoneOffset.ofTotalSeconds((int) (K / 1_000_000L))` when `K % 1_000_000L == 0 && Math.abs(K)
  <= 64_800_000_000L`, else null; `micros(ZonedDateTime.ofLocal(ldt, zone,
  preferred).toInstant())`.
- `micros(i)` is `i.getEpochSecond() * 1_000_000L + i.getNano() / 1000`. A null operand gives
  an empty answer, as every strict arm does.

### 4.3 The count and the order of the arguments

`VarkaBatchArgs.count(node, form)` gains two arms, with `k = form.steps()`:

    case ToInstant t -> 1 + (t.rule() == Resolve.EARLIER ? 2 : 3) * k;
    case ToInstantKept kept -> 1 + 4 * k;

So 1, 5 and 17 for `EARLIER`; 1, 7 and 25 for `GAP_END`; 1, 9 and 33 for `ToInstantKept`. The
order inside a program is 2.5's: with `a` the program's arguments, `a[0]` is the base, and
step `i` holds `hi = a[1 + 2i]`, `delta = a[2 + 2i]` under `EARLIER`; `hi = a[1 + 3i]`,
`lo = a[2 + 3i]`, `delta = a[3 + 3i]` under `GAP_END`; `hi = a[1 + 4i]`, `lo = a[2 + 4i]`,
`after = a[3 + 4i]`, `delta = a[4 + 4i]` for `ToInstantKept`. `count` is never asked a table
form or the ladder for these records: 4.9.

### 4.4 A zone's data on the node's own line: `VarkaBatchArgs.dataOf`

The runner, the ranges of row 685 and the ladder ask a program's zone data two things: how
many changes a range holds (`changes`), and its arguments (`toLocalArgs`). For this row's
records both are questions about the local line. So that no reader branches on the node, the
program of such a node is handed a view:

    public static final long LOCAL_MAX_SPAN = Long.MAX_VALUE - 2 * MAX_OFFSET_MICROS;
    public static VarkaZoneData dataOf(VarkaVectorIR.Zoned node, VarkaZoneData zone)
    public static boolean readsLocalLine(List<Program> programs)

- `dataOf` is a switch over `node` with no `default`: `zone` itself for a `ToLocal` (row
  689's part a makes it a view of the instant line, `VARKA-689.md` 4.6); for a
  `ToInstant` or a `ToInstantKept`, `new LocalLine(node, zone)`.
- `LocalLine` is a private static final nested class of `VarkaBatchArgs` that implements
  `VarkaZoneData` over two final fields:
  - `id()` and `fixed()` are the zone's;
  - `changes(least, greatest)` and `localChanges(least, greatest)` are both: `MANY` when
    `greatest > least && (greatest - least < 0 || greatest - least > LOCAL_MAX_SPAN)`, else
    `zone.localChanges(least, greatest)`. The first test of the pair catches a difference
    that wraps;
  - `toLocalArgs(least, greatest, steps, args, first)` is `zone.toInstantArgs(node, least,
    greatest, steps, args, first)`;
  - `toInstantArgs(...)` throws `IllegalArgumentException("a view of the local line fills
    its own node")`;
  - every other method the interface has on the day (row 686 adds `toLocalTable`) throws
    `IllegalStateException("no table form reads the local line (VARKA-693)")`.
- `readsLocalLine` is true when some program's node is a `ToInstant` or a `ToInstantKept`.
- **The one seam** (part b). In `sql/core`, wherever a program's zone data is taken from the
  provider
  (`VarkaEvaluatorBase.newRunner` in row 682's plan), the value kept is
  `VarkaBatchArgs.dataOf(program.node(), data)`, after the null test. The view is built once
  a runner, never for a batch.
- **What the existing checks then give.** The runner declines a batch whose operand's range
  is within 18 hours of an end of a long (`VARKA-682.md` 4.8): so `L - offset` never wraps on
  a live row. Row 678's data answers `MANY` within 36 hours of an end, and the view answers
  `MANY` for a span over `LOCAL_MAX_SPAN`: no form covers `MANY`, so both decline with
  `STATUS_BATCH_ARGS`. A batch with no live row gets the neutral arguments.
- **`LOCAL_MAX_SPAN`** is one rule for every step form (the owner's decision of 27 November
  2026, section 12): a batch that spans more than 292,000 years of local time takes the row
  path under every option. The masked steps of this row are exact for every span and do not
  need it. The steps with no mask, which row 689 builds, are exact only under it (appendix
  A); that row inherits the rule from the view and adds no decline of its own.

### 4.5 The lowering: one offset

`VarkaZoneLowering` gains, beside `emitToLocal` and with its parameters:

    static void emitToInstant(CodeBuilder cb, ToInstant n, boolean dense, Analysis analysis,
        Slots s, Set<VarkaVectorIR> computed)
    static void emitToInstantKept(CodeBuilder cb, ToInstantKept n, boolean dense,
        Analysis analysis, Slots s, Set<VarkaVectorIR> computed)
    static int laneCalls(VarkaVectorIR.Zoned node, VarkaEmitOptions.ZoneForm form)

Each reads the form by the expression `emitToLocal` reads it by, and its arguments from
`s.batchArgLocal.get(n)`, as `emitToLocal` does. Under `ONE`, for every rule:

1. `emitValue(n.local())` leaves the local vector `L` on the stack.
2. For a `ToInstantKept` only: `emitValue(n.kept())`, then `pop`. The kept operand is emitted
   in `childrenOf`'s order although `ONE` does not read it, so that a node it shares with the
   local operand is computed where `Slots` planned its first visit.
3. `lload a[0]; lneg;` then the lane's `add(long)` (`lanewiseVI`, name `add`).

The result is `L - base`. A batch under `ONE` touches no window, so the three rules agree.
The scalar is negated in bytecode and added, so the lane calls are the two `ToLocal` warms.

### 4.6 The lowering: the masked steps

`acc` is the vector on the stack; `tmp` is the node's vector local. Each compare reads `tmp`,
the local time, never `acc`. Each sum wraps.

**`EARLIER`.** Steps 1 and 3 of 4.5 with `dup; astore tmp` between them. Then, for each step
`i`: `lload delta; lneg; aload tmp; getstatic GE; lload hi;` the lane's `compare` (`compareVI`),
then `add(long, mask)` (`lanewiseVIMasked`, name `add`). On lanes: `acc = L - base`, then
`acc -= delta[i]` where `L >= hi[i]`. Lane calls `1 + 2k`; no mask call.

**`GAP_END`.** The `EARLIER` step, then the window of the same step:

1. Push the scalar `T`, the change's instant: `lload hi; lload a[0]; lsub;` then, for each
   `j` from 0 to `i`, `lload delta_j; lsub`.
2. `aload tmp; getstatic GE; lload lo;` `compare`. `aload tmp; getstatic LT; lload hi;`
   `compare`. `invokevirtual VectorMask.and` (`VarkaDescriptors.MASK_BINARY`).
3. The lane's `blend(long, mask)`: `invokevirtual` of the long vector class, name `blend`,
   descriptor `lanewiseVIMasked`.

On lanes: `acc = T[i]` where `lo[i] <= L < hi[i]`. For an overlap `lo[i]` is `hi[i]`, so the
mask is empty. A padded step compares with `Long.MAX_VALUE` twice: `L >= MAX && L < MAX` is
false for every long, a null row's storage included, so `T`'s value there is never read.
Lane calls `1 + 5k`; mask calls `k`.

**`ToInstantKept`.** `emitValue(n.local()); astore tmpL; emitValue(n.kept()); astore tmpK;
aload tmpL;` then step 3 of 4.5. For each step `i`: `lload delta; lneg;` then three compares,
`aload tmpL; getstatic GE; lload hi`, `aload tmpL; getstatic GE; lload lo` and `aload tmpK;
getstatic EQ; lload after`; then `VectorMask.and`, `VectorMask.or`, and `add(long, mask)`. On
lanes: `acc -= delta[i]` where `L >= hi[i] || (L >= lo[i] && kept == after[i])`. Lane calls
`1 + 4k`; mask calls `2k`.

`laneCalls(node, form)`: `laneCalls(form)` for a `ToLocal`; `1 + 2k`, `1 + 5k` and `1 + 4k`
for the three rules.

**Why no check.** A live `L` is 18 hours inside a long's ends (4.4). The steps that fire are
the first few (2.5), so after each of them `acc` is `L` less one offset of the zone, and an
offset is within 18 hours. `T` is `hi` less an offset. Nothing leaves a long.

### 4.7 Two lanes of 64 bits

At two 64-bit lanes on x86 this JVM compiles no mask, so every masked step runs as a Java
loop: right answers, low speed. What follows from 2.6:

- `EARLIER`: 13.936 ns a row against the row path's 43.752. It fuses there.
- `ToInstantKept`: 43.030 against 42.319. It gains nothing, so it declines by name on such a
  host (`TWO_LANE_LOCAL_RULE_REASON`, 4.11), as decision 15 of `m9/PLAN.md` asks.
- `GAP_END`: not timed. Its step holds one mask call and four lane calls where the kept
  offset's holds two and three, so it declines by the same reason until it is measured.

The declines are made by the compiler arms, from `sink.declinesTwoLaneChecks()`. The ladder
of row 685 stops at `STEPS_2` on such a host. Under UTC and a fixed offset nothing declines:
the fold holds no step. The owner decided on 27 November 2026 that both rules decline by name
before any measurement. Row 689 builds their steps with no mask (4.8), measures the 128-bit
sections with every conversion fused, and keeps or lifts the two declines by its record
(`VARKA-689.md` 4.9 and 4.10); row 691 reads again. This row does not lift them.

### 4.8 The steps with no mask are row 689's

Row 689 builds the step with no mask, for `ToLocal` and for this row's three rules, on one
design (the lead's decision of 27 November 2026; section 13, edit 6). Appendix A holds this
plan's design notes for it. Row 690 builds the masked steps of 4.6 alone, and they run under
every value of row 689's option until that row gives these records their own arm: right
answers at every width, and at two masked lanes the two slow rules decline by name (4.7).
Nothing in parts a to i reads `zoneStepMask`, and `laneCalls(node, form)` keeps its two
parameters.

### 4.9 The forms no lowering of these records has

`VarkaKernelPlan.of` refuses, by a new `Kind.ZONE_LOCAL_FORM`, a tree that holds a `ToInstant`
or a `ToInstantKept` under a form its lowering does not have. The emitter throws the same
text if asked anyway.

| case | reason | until |
| :--- | :--- | :--- |
| a form with steps, `VarkaKernelPlan.ZONE_LOCAL_STEPS_REASON` | `a local time to an instant in a form with steps: only the one-offset form of this conversion is lowered yet` | part c, which deletes the constant |
| `TABLE` or `TABLE_SCALAR`, `VarkaKernelPlan.ZONE_LOCAL_TABLE_REASON` | `the table form of a local time to an instant: the bucket table is of the instant line, and the table of the local line is row 693's` | row 693 |

The table refusal exists only once row 686 has added the forms; whichever of the two rows
builds second adds it (5.1, step 8; 5.2, step 5; section 13, edit 3). Under the ladder, the
evaluator of a
plan whose programs read the local line (`VarkaBatchArgs.readsLocalLine`) stops at
`STEPS_8`: a batch that touches more than eight windows declines with `STATUS_BATCH_ARGS`.
No SQL reaches either refusal: the arms of parts d to f come after the steps.

### 4.10 The compiler: the way back

`VarkaZoneCompiler` gains two methods, the twins of `toLocal`:

    static Optional<VarkaVectorIR> toInstant(Expression whole, Option<String> timeZoneId,
        VarkaVectorIR local, Resolve rule, DeclineSink sink)
    static Optional<VarkaVectorIR> toInstantKept(Expression whole, Option<String> timeZoneId,
        VarkaVectorIR local, VarkaVectorIR kept, DeclineSink sink)

Each is `toLocal`'s body with four differences, in `toLocal`'s order of tests:

1. The first two declines (no zone on the node; an id that does not resolve) are `toLocal`'s
   statements, copied.
2. **The fold of a fixed zone.** Under UTC the answer is `local` itself, the same object.
   Under a fixed offset of `micros` it is `new IntArith(IntOp.ADD, Overflow.WRAP, new
   GuardedRange(local, Long.MIN_VALUE + MAX_OFFSET_MICROS, Long.MAX_VALUE -
   MAX_OFFSET_MICROS), sink.longSlot(-micros))`: `toLocal`'s tree with the literal negated.
   The rule and the kept offset are not read: a fixed zone has no window.
3. The test of the operand and the test that the zone is served are `toLocal`'s statements
   as they stand on the day, copied with `local` for `instant` (row 688's part c replaces
   the first of them; section 13, edit 2).
4. Before the node is built: `if (sink.declinesTwoLaneChecks() && rule != Resolve.EARLIER)
   { return sink.decline(TWO_LANE_LOCAL_RULE_REASON, whole); }`, and in `toInstantKept` the
   same with no test of a rule. Then `new ToInstant(local, sink.zone(zone.getId()), rule)`,
   or `new ToInstantKept(local, kept, sink.zone(zone.getId()))`.

**The three first kernels.** `arm` gains two claims beside row 657's cast to `DATE`, and row
688's claim of the sum takes one more form:

| expression | claim | tree, in a zone with changes |
| :--- | :--- | :--- |
| `CAST(ntz AS TIMESTAMP)` | `Cast c when DataTypes.TimestampType.equals(c.dataType()) && VarkaTypes.isNtz(c.child().dataType())` | `ToInstant(t, zone, EARLIER)`, `t` the child compiled by `VarkaNodeCompiler.compileNode` |
| `CAST(d AS TIMESTAMP)` | `Cast c when DataTypes.TimestampType.equals(c.dataType()) && DataTypes.DateType.equals(c.child().dataType())` | `ToInstant(midnightOf(ntzMidnightDay(day), sink), zone, GAP_END)`, `day` the child compiled |
| `ts + <literal interval>` | row 688's `TimestampAddInterval` claim | below |

- The mode of a cast is not read. Spark computes both casts the same way in every mode and
  can only raise for a value near a long's end (fact 4); such a batch declines (4.4, and the
  day's guard `ntzMidnightDay`), and the row path raises, or nulls under `try_cast`.
- Under UTC `CAST(ntz AS TIMESTAMP)` is its child and `CAST(d AS TIMESTAMP)` is the tree of
  `CAST(d AS TIMESTAMP_NTZ)`: one kernel class each with the `TIMESTAMP_NTZ` twin.
- A child off its lane declines with the text the `TIMESTAMP_NTZ` arms use: "a timestamp_ntz
  operand that is not on the long lane", "a date operand that is not on the int lane".

**`ts + INTERVAL DAY`: where the kept offset comes from.** From fact 3, with `v` the
literal's microseconds, `days = v / MICROS_PER_DAY` and `rest = v - days * MICROS_PER_DAY`:
Spark reads the instant in the zone, which gives the local time `ToLocal(ts)` and the offset
the value has there; `plusDays` adds whole days to the local time and resolves it with that
offset preferred (fact 9); `plus(rest, MICROS)` adds to the instant. The offset the value has
is its local time less itself. So, with `instant` the compiled `ts`:

    local = toLocal(a, a.timeZoneId(), instant, sink)            // row 657
    moved = new IntArith(IntOp.ADD, Overflow.FAIL, local, sink.longSlot(days * MICROS_PER_DAY))
    kept  = new IntArith(IntOp.SUB, Overflow.WRAP, local, instant)
    back  = toInstantKept(a, a.timeZoneId(), moved, kept, sink)
    answer = rest == 0 ? back : new IntArith(IntOp.ADD, Overflow.FAIL, back, sink.longSlot(rest))

- `days == 0` keeps row 688's tree, `new IntArith(IntOp.ADD, Overflow.FAIL, instant,
  sink.longSlot(v))`: `plusDays(0)` resolves the same local time with its own offset.
- Under UTC `toLocal` returns `instant` itself. The arm tests `local == instant` and then
  answers row 688's tree with `v`: the `TIMESTAMP_NTZ` kernel of `ntz + dt`.
- Under a fixed offset the tree is the two folds around the sum of the days.
- `local` is one node read three times: by `moved`, by `kept` and, in a projection that also
  holds `hour(ts)`, by the field. `Slots` computes it once in a method.
- `days * MICROS_PER_DAY` cannot overflow: its size is at most `v`'s.
- A checked sum that overflows declines the batch, and the row path raises (fact 4). The
  kernel's sums are partial sums of Spark's, all of one sign, so no batch declines that the
  row path would answer, except within 36 hours of a long's end.
- An interval that is no literal, or a null literal, still declines with
  `ZONED_INTERVAL_REASON`, whose text part f changes (4.11). `ts - INTERVAL '1' DAY` reaches
  the arm as a sum with a literal once the optimizer has folded the negation; unfolded, in a
  tree resolved as written, it is no literal and declines.
- `ToInstantKept`'s operand is computed, so its range for a batch is row 688's: the range of
  `ts`, 18 hours wider each way, moved by the days.

### 4.11 What declines by name, and where each text is printed

Traced from `VarkaNodeCompiler.compileNode`: the tag `KnownFloatingPointNormalized` is not
one of these; `floatingDecline` answers only for an expression with a floating side, and
none here has one; then the chain `FAMILIES` in order. No link before `ZONE` claims a cast to
`TimestampType` or a `TimestampAddInterval` over one (fact 11): the calendar, the interval
and the time families claim by `TIMESTAMP_NTZ`, `DATE`, `TIME` and the interval types.

| expression | before its part | printed by | after its part |
| :--- | :--- | :--- | :--- |
| `CAST(ntz AS TIMESTAMP)`, `CAST(d AS TIMESTAMP)` | `CAST(TIMESTAMP_NTZ AS TIMESTAMP) is row 690's: not fused yet`, `CAST(DATE AS TIMESTAMP) is row 690's: not fused yet` | `VarkaNodeCompiler.fallback`, from `VarkaZonedDeclines.reason` (row 656) | the claim wins before the fallback; the entries of `VarkaZonedDeclines.cast` stay, unreached, and its suite judges a form only while it declines (`VARKA-656.md` 4.3) |
| `ts + INTERVAL '1' DAY` | `ZONED_INTERVAL_REASON` as row 688 wrote it | `VarkaZoneCompiler.zonedPlusInterval` | fused |

A claimed node asks its switch first: with the family off, `inFamily` prints
`VarkaFamily.ZONE.offReason()` for each of the three. A cast of a bare column is asked after
its lowering ran (`inFamily`'s `relabel`): the text is the same, but a lowering that declines
prints its own reason first, and under UTC the cast of a bare `TIMESTAMP_NTZ` column is the
column's read, which no switch declines. So the switch's test casts `coalesce(ntz, ntz)`
under a zone with changes. The declines an arm can then print, each at its `sink.decline` in
`VarkaZoneCompiler`:

| case | text |
| :--- | :--- |
| no zone on the node; an id that does not resolve; a zone not served | `toLocal`'s three texts (`NO_ZONE_REASON`, `unresolvedReason(id)`, `unservedReason(id)`) |
| a computed local time under a zone with changes | `toLocal`'s text of the day: `COMPUTED_OPERAND_REASON` before row 688's part c, `unrangedReason(name)` after it. `CAST(d AS TIMESTAMP)` and the sum have computed operands, so parts e and f need that part |
| `GAP_END` or the kept offset on a host of two masked lanes, `TWO_LANE_LOCAL_RULE_REASON` | `a local time resolved by the gap's end or by the kept offset, on an x86 host whose 64-bit species have two lanes or fewer: each step holds long-lane masks, which C2 does not lower there, so the kernel would run the Vector API's Java fallback, no faster than the row engine` |
| a child off its lane | the two texts of 4.10 |
| a day-time interval that is no literal, `ZONED_INTERVAL_REASON`, from part f on | `a TIMESTAMP plus a day-time interval that is no literal: its whole days are calendar days in the session time zone and differ by row, which is row 671's` |

Run time: `STATUS_BATCH_ARGS` for a batch that touches more windows than its form's steps,
that lies within 36 hours of a long's end, that spans more than `LOCAL_MAX_SPAN`, or whose
computed range is unknown; `STATUS_VALUE_RANGE` from the fold's guard and from the day's
guard. No new status, no new metric.

### 4.12 The proof: the sweep under three rules, the planted faults, there and back

`VarkaZoneKernels` (row 684) holds one conversion a `Rule`: its root over `ColumnRef(0,
LONG)`, its points around a change, its cuts and `java.time`'s answer. Part g adds:

- **Two inputs.** `Rule` gains `default int inputs() { return 1; }`, `default long[]
  second(ZoneOffsetTransition change, long[] points) { return null; }` and `default long
  expected(ZoneId zone, long operand, long second) { return expected(zone, operand); }`. The
  harness builds a kernel of `rule.inputs()` inputs, fills a second column from `second`
  where a rule has two, and asks the three-argument `expected`.
- **The local points of a change** at second `T` with offsets `b` and `a` (seconds), `lo = (T
  + min(b, a)) * M1` and `hi = (T + max(b, a)) * M1`, `M1 = 1_000_000L`. `EDGES`: `lo - 1`,
  `lo`, `lo + 1`, `(lo + hi) / 2`, `hi - 1`, `hi`, `hi + 1`. `SECONDS`: `lo + k * M1` for `k`
  from -7200 to `(hi - lo) / M1 + 7199`. `cuts`: `lo` and `hi`.
- **Five rules.**

| rule | root | points | `expected` |
| :--- | :--- | :--- | :--- |
| `TO_INSTANT_EARLIER` | `new ToInstant(col0, 0, Resolve.EARLIER)` | the local points | `ldt.atZone(zone)` |
| `TO_INSTANT_GAP_END` | `new ToInstant(col0, 0, Resolve.GAP_END)` | the local points; under `SECONDS` only `k` from -60 to `(hi - lo) / M1 + 59` | 4.2's `GAP_END`; and at each point that is a midnight (`Math.floorMod(L, 86_400_000_000L) == 0`) also `LocalDate.atStartOfDay(zone)` of its day |
| `KEPT_AFTER`, `KEPT_BEFORE`, `KEPT_OTHER` | `new ToInstantKept(col0, col1, 0)` | as `TO_INSTANT_GAP_END`; `second` fills the column with `a * M1`, `b * M1`, and `(b + 3600) * M1` (`(b - 3600) * M1` where `b + 3600 > 64800`) | `ZonedDateTime.ofLocal(ldt, zone, ZoneOffset.ofTotalSeconds((int) (second / M1)))` |

  The gap's end and the kept offset differ from the earlier offset only inside a window, so
  their one-second sweep is the window and a minute either side. Outside it a check with no
  oracle holds them to `EARLIER`'s kernel (test 16 below).
- **The filler.** `production(provider)` fills each program from
  `VarkaBatchArgs.dataOf(program.node(), provider.of(zoneId))`.
- **The forms a segment runs in.** Row 684's sweep runs a run of eight changes as one batch
  under `STEPS_8`, each two changes as one under `STEPS_2`, and each change's points cut at
  its `cuts` under `ONE`. A local batch that lies inside a window touches it (a window counts
  when `hi > least` and `lo <= greatest`), so `ONE` cannot answer the segment between `lo`
  and `hi`. `Rule` gains `default boolean oneOutsideCutsOnly() { return false; }`, and the
  five local rules return true. For such a rule, under `ONE`, the harness runs the segment
  below the first cut and the segment from the last cut on, and for the segment between them
  it asserts that the filler answers false: that batch declines. "In each form" below means
  `STEPS_8`, `STEPS_2`, and `ONE` so restricted.
- **There and back is a check of the kept offset alone.** The local time of an instant is
  never inside a gap, and inside an overlap the instant's own offset says which of the two
  instants it was. So `ToInstantKept(L, L - x)` with `L` the local time of `x` is `x` for
  every instant. Under `EARLIER` or `GAP_END` the way back of an instant in the later hour of
  an overlap is the earlier instant, so the check cannot hold for them: test 17 is the row's
  one there-and-back check, and no test asserts it of the other two rules.
- **New tests of `VarkaZoneSweepSuite`,** after row 684's eleven:

| # | `@DisplayName` | what it runs | forms |
| ---: | :--- | :--- | :--- |
| 12 | ToInstant under the earlier offset equals LocalDateTime.atZone two hours either side of every window before 2100 at one second, and at its edges, in each form | `sweep` of `TO_INSTANT_EARLIER`, `BOTH`, dense body | each |
| 13 | ToInstant under the gap's end equals java.time across every window before 2100, and LocalDate.atStartOfDay at every midnight it reads, in each form | `sweep` of `TO_INSTANT_GAP_END`, `BOTH` | each |
| 14 | ToInstantKept equals ZonedDateTime.ofLocal across every window before 2100 with the offset after, the offset before and a third offset kept, in each form | `sweep` of the three `KEPT` rules, `BOTH` | each |
| 15 | each rule equals java.time at the edges of every window in the body that reads a null row, and at the edges of every window from 2100 to 9999 | `EDGES` with `nullFirst` over the five rules; `EDGES` of the rule era | the first: each. The second: `STEPS_8`, and `STEPS_2` and `ONE` for 2100 to 2199 and 9900 to 9999, as row 684's test 4 |
| 16 | outside every window the three rules give the same instant | no oracle: on each run's `SECONDS` of `TO_INSTANT_EARLIER`, the rows with `L < lo` or `L >= hi` of their change, through the kernels of the five rules: equal | `STEPS_8` |
| 17 | there and back: ToInstantKept of the local time of an instant, with the offset of that instant, is the instant | no oracle: for each run, the instants of `TO_LOCAL`'s `BOTH`; `ToLocal`'s kernel gives `L`; the second kernel reads `L` and `L - x` and must give `x`. The second batch's `least` and `greatest` are the least and greatest `L`, found by a loop: `L` is not ascending across an overlap | `ToLocal` in `STEPS_8`; the second kernel in `STEPS_8` on the run and in `STEPS_2` on each two changes' rows. Not `ONE`: the local times of the instants before an overlap reach `hi - 1`, inside its window |
| 18 | a local batch that touches one window more than its form's steps is declined, one within 36 hours of an end of a long is declined, and one that spans more than LOCAL_MAX_SPAN is declined | the edges of consecutive windows: 1 window declines and 0 answers in `ONE`, 3 and 2 in `STEPS_2`, 9 and 8 in `STEPS_8`; `Long.MIN_VALUE + 2 * M` and `Long.MAX_VALUE - 2 * M`, each alone: `fill` answers true (`M` is 2.2's 18 hours), and one microsecond nearer the end it answers false; the pair `-LOCAL_MAX_SPAN / 2 - 1`, `LOCAL_MAX_SPAN / 2 + 1` is declined | `ONE`, `STEPS_2`, `STEPS_8` |

- **Planted faults,** added to `VarkaZoneFaults` and `VarkaZonePlantedFaultSuite` over row
  684's four zones. Each is a wrapper, as row 684's are; each test asserts a `Mismatch` and
  reads its fields.

| # | fault | where it is planted | the check that must fail, and where |
| ---: | :--- | :--- | :--- |
| 6 | `min` for `max` in a local threshold | a `VarkaZoneData` wrapper whose `toInstantArgs` writes `lo` where the data wrote `hi`: for `EARLIER`, each `hi[i]` becomes `hi[i] - |delta[i]|` | test 12's `sweep` with `EDGES`, at `lo` of the first zone's first window. Its `SECONDS` fails too |
| 7 | the kept offset ignored | the wrapper sets each `after[i]` of a `KEPT` program to `Long.MIN_VALUE`, which no offset equals | test 14's `sweep` of `KEPT_AFTER`, at `lo` of the first overlap; `KEPT_BEFORE` and `KEPT_OTHER` must pass: only the kept-after rows catch it |
| 8 | the gap's end not taken | the wrapper sets each `lo[i]` of a `GAP_END` program to `hi[i]` | test 13's `sweep`, at `lo` of the first gap; and nowhere outside a gap |
| 9 | the instant line's arguments for a local node | a `Filler` that fills from `provider.of(zoneId)` with no `dataOf` | test 12's `sweep`, at `lo` of the first zone's first window |

### 4.13 The fuzzers

- **The IR fuzzer** (`VarkaIrGrammar.drawZoneShape`, row 684's sequence on
  `ZONE_FUZZ_SEED`). With `zones > 0`, where row 684 wraps two column leaves in three in a
  `ToLocal`, the draw becomes: of every six column leaves, two are `ToLocal`, one is `new
  ToInstant(column, zone, EARLIER)`, one is `new ToInstant(column, zone, GAP_END)`, one is
  `new ToInstantKept(column, new IntArith(IntOp.SUB, Overflow.WRAP, new ToLocal(other, zone),
  other), zone)` with `other` a second long column, and one stays bare. Each has the bound
  `satAdd(bound, OFFSET_BOUND)`, as `ToLocal`'s. The committed corpora draw on other seeds
  and do not move; the zone sequence's own draws change, and it has no committed corpus.
  `boundsOf` gets the two arms: the local operand's bound widened by 18 hours.
- The fill of a drawn case takes `dataOf` (4.4). A case whose fill declines is counted, not
  compared. The reference is 4.2's. The reach tests' lists lose the two names and name this
  sequence, as they do for `ToLocal`.
- **The Spark fuzzer** draws the session zone since row 684. This row adds nothing to its
  grammar: its zone compositions take a projection from the coverage rows of the `ZONE`
  family, which parts d to f extend. The new columns derive from `i` and `t` by `pmod`, so
  every drawn cell stays inside 2021.
- **The property suite** (`VarkaZoneArgsPropertySuite`): one test more, "a random zone, rule
  and local range: the fill answers exactly when the form covers the windows the range
  touches, and its arguments give java.time's instant on every row", the twin of its test 1
  on the local line, by the scalar meaning of 2.5.

### 4.14 Coverage rows, census cells and cast pairs

The coverage fixtures hold dates from 1969 to 9999, so a zoned kernel over them would decline
every batch. Row 657 added the column `ltzy`, an instant of 2021. This row adds two, derived
in the same view from the same cells:

| column | type | derivation |
| :--- | :--- | :--- |
| `ntzy` | `TIMESTAMP_NTZ` | `CAST(DATE'2021-01-01' AS TIMESTAMP_NTZ) + make_dt_interval(pmod(i, 12) * 30) + (t - TIME'00:00:00')` |
| `dy` | `DATE` | `date_add(DATE'2021-01-01', pmod(i, 12) * 30)` |

Each is null where `i` (or `t`) is. The twelve days run from 1 January to 27 November 2021:
both sides of both changes of `America/Los_Angeles`. A batch touches at most two windows.

| part | coverage rows, in row 657's family "TIMESTAMP in the session time zone" | in its condition family |
| :--- | :--- | :--- |
| d | `CAST(ntzy AS TIMESTAMP)` | `CAST(ntzy AS TIMESTAMP) < ltzy` |
| e | `CAST(dy AS TIMESTAMP)` | `CAST(dy AS TIMESTAMP) <= ltzy` |
| f | `ltzy + INTERVAL '1' DAY`, `ltzy + INTERVAL '-1 02:30:00' DAY TO SECOND` | none |

**What turns fused,** counted by the census's own rule (`matrix_fused` of
`dev/varka_census.py`): `cast` and `try_cast` count a source as fused when every legal pair
from it fuses in the entry's modes (LEGACY and ANSI for `cast`, TRY for `try_cast`); a cast
alias counts a source when the one pair to its target does. A pair counts only when a
coverage row casts it, in any mode.

| part | cast pairs, in `casts_fused.tsv` and `casts.tsv` | census pairs that turn fused | the summary's fused count |
| :--- | :--- | :--- | ---: |
| d | `ntz` to `ltz`: `fused` in LEGACY, ANSI and TRY | three, all of type `ntz`: `cast`, `try_cast`, and the alias `timestamp` | +3 |
| e | `date` to `ltz`: `fused` in the three modes | two, of type `date`: `try_cast` and the alias `timestamp`. `cast` does not regain `date`: it held `date` in part before `ltz` entered (`VARKA-654.md` 4.10 names `ntz` alone for `cast`) | +2 |
| f | none | none: the entry of class `TimestampAddInterval` keeps `ltz^` (fused in part); its rows gain `690` and its note names the literal form | 0 |

`VARKA-654.md` 4.10 reads 585 pairs fused after its part b and says that three of them
"come back with row 690's two casts": those are `cast` with `ntz` and `try_cast` with `ntz`
and `date`. The alias `timestamp`, which that part adds with no fused source but its own
type, gains two more. So five pairs in all, 585 to 590 if no other row moved a cell between;
each part compares the summary before and after, so the rows between do not matter. No
`ltz` pair moves. The five implicit timestamp operators (`ntz_minus_ntz`, `datetime_sub`,
`ntz_plus_dt`, `ntz_plus_ym`, `timestampadd`) gain no cell: `ntz_plus_dt` is the entry of
part f and stays in part, and the others are row 671's. A comparison of the two timestamp
kinds, `ntz < ts`, fuses by composition over part d's cast; its cells (`<` over `ntz` and
over `ltz`) were fused by rows 338 and 656 and do not move.

The casts need no curated edit: `casts_fused.tsv` is written by `VarkaCastMatrixSuite`, and
`cast`, `try_cast` and the alias read it (`matrix`). The sum's entry is curated; part f
edits it by this script, which was run on a copy of the line in the form `VARKA-654.md` 5.2
and `VARKA-688.md` 5.3 give it:

    python3 - sql/varka/plans/m9/census_curated.txt <<'PY'
    import sys
    path = sys.argv[1]
    lines = open(path, encoding="utf-8").read().split("\n")
    hits = [i for i, l in enumerate(lines)
            if not l.startswith("#") and l.split("|")[1:2] == ["TimestampAddInterval"]]
    assert len(hits) == 1, f"{len(hits)} entries of class TimestampAddInterval"
    f = lines[hits[0]].split("|")
    assert len(f) == 9, f"{len(f)} fields"
    assert "ltz^" in f[5].split(","), f"fused field is {f[5]}: row 688's mark is missing"
    rows = f[7].split(",")
    if "690" not in rows:
        f[7] = ",".join(rows + ["690"])
    add = ("ltz: a literal of whole days fuses by the kept offset, "
           "`ltzy + INTERVAL '1' DAY` (VARKA-690); an interval column is row 671's")
    if "VARKA-690" not in f[8]:
        f[8] = f[8] + "; " + add
    lines[hits[0]] = "|".join(f)
    open(path, "w", encoding="utf-8").write("\n".join(lines))
    print(lines[hits[0]])
    PY

It stops with its assertion's text on a line it does not expect; that is a stop for the
builder, with the line reported.

### 4.15 Edge cases

| case | what happens | what holds it |
| :--- | :--- | :--- |
| a null row | the output is null where an operand is; the lane computes on whatever the storage holds, and the validity hides it; no pass reads it | the null patterns of 5.1 and 5.3; test 15 of 4.12 |
| the ends of a long | a batch within 18 hours of an end declines in the runner, within 36 hours by `MANY`; the fold's guard declines by `STATUS_VALUE_RANGE` | test 18 of 4.12; the end tests of parts d to f |
| a date past the days whose midnight is a long | `ntzMidnightDay`'s guard declines the batch; the row path raises, or nulls under `try_cast` | part e's test of far days |
| a batch with no live row | the neutral arguments; every output null | part c's test 5 (every row null); the test "one row, all rows equal and a batch with no live row are served" of each of parts d, e and f |
| two lanes of 64 bits | 4.7: the masked steps run as Java loops, and two rules decline by name | the matrix's `isa=w128`; part c's test 7; the two-lane tests of parts e and f |
| a day before 1582 or after 9999; both ends of Spark's range | values like any other: the data has no calendar | parts d and e, "both ends of Spark's range are answered"; part e reads days -719162 and 2932896 |
| an offset that is no whole minute | the offsets are whole seconds in microseconds; nothing rounds | `America/Los_Angeles` 1883 in the fixture of parts c to f (the change from -7:52:58 to -8:00); the sweep reads every such change |
| AVX2 against AVX-512 | the same IR and bytecode; the JIT picks a blend or a mask register | the Build's runners; `isa=avx2` |
| a `kept` that is no offset of the zone | answers as `EARLIER` (2.5) | `KEPT_OTHER`; part c's test 4 |
| a window that starts or ends at a midnight | `GAP_END` and `EARLIER` agree at `lo` and at `hi`; they differ only strictly inside a gap | part e's `America/Havana` and `America/Toronto` rows |
| the driver's rules | each JVM compiles for itself and a task follows its own compile (`VARKA-657-PARTS.md` 4.2) | row 684's fault 5, which part g runs under the new rules |

### 4.16 What is out of this row

| what | the row that holds it |
| :--- | :--- |
| the table of the local line, and ten years of local times in no order with no declined batch | 693 |
| every other cast of a `TIMESTAMP`, the cast from `TIME` among them | 669 |
| `to_utc_timestamp`, `convert_timezone`, `make_timestamp`, `date_trunc`, `months_between` | 670 |
| an interval column, `ts + INTERVAL MONTH`, `ts - ts`, `timestampadd` | 671 |
| the steps with no mask, of `ToLocal` and of this row's three rules; the option `zoneStepMask` and its default by host | 689 (appendix A) |
| the 128-bit bar, each form measured, the defaults by width, the two-lane declines kept or lifted | 691 |
| the production zone data and its check | 678 |
| the range of a computed operand and of an int column | 688 |
| the nanosecond forms of the three kernels | 673 to 675 |

## 5. The parts

Nine pull requests, each under 1000 counted lines (`python3 dev/varka_msg_check.py size`).
The family plan budgeted five (`VARKA-657.md` 8); rule R1 (fact 15) makes each record carry a
lowering in its first part, and the steps and the proof are parts of their own so that each
merges green alone. The owner approved ten on 27 November 2026; the lead's move of the steps
with no mask to row 689 made it nine (section 12).

| part | title | branch | needs | gives | lines |
| :--- | :--- | :--- | :--- | :--- | ---: |
| a | `[VARKA-690] Add the ToInstant node and its one-offset kernel` | `varka-690a-to-instant` | row 657, parts A to D | `Resolve`, `ToInstant`, `VarkaLocalLineKernels`; nothing a query reaches | 760 |
| b | `[VARKA-690] Add the ToInstantKept node, and fill local-time programs on the local line` | `varka-690b-kept` | a | `ToInstantKept`, `dataOf`, the seam | 620 |
| c | `[VARKA-690] Lower the steps of a local time to an instant under three rules` | `varka-690c-steps` | b | the masked steps; the steps refusal goes | 560 |
| d | `[VARKA-690] Fuse CAST(TIMESTAMP_NTZ AS TIMESTAMP) in the session time zone` | `varka-690d-ntz-cast` | c | `toInstant`; the first kernel; `ntzy`; one cast pair | 600 |
| e | `[VARKA-690] Fuse CAST(DATE AS TIMESTAMP) in the session time zone` | `varka-690e-date-cast` | d; row 688, parts a, b and d | the second kernel; `dy`; one cast pair | 450 |
| f | `[VARKA-690] Fuse TIMESTAMP plus a literal of whole days by the kept offset` | `varka-690f-plus-days` | d; row 688, parts a to c | `toInstantKept`; the third kernel | 630 |
| g | `[VARKA-690] Sweep the three rules of a local time against java.time` | `varka-690g-sweep` | c; row 684, parts 1 and 2 | the sweep's rules, the faults, there and back | 670 |
| h | `[VARKA-690] Draw local-time zone nodes in the IR fuzzer and the property suite` | `varka-690h-fuzz` | g; row 684, part 3 | the draws | 380 |
| i | `[VARKA-690] Results: the casts to TIMESTAMP and the sum of days at two widths` | `varka-690i-results` | d, e, f | the measurement, the close | 60 |

**How the estimates were counted,** file by file, for the two largest foundations. Part a:
`VarkaVectorIR` 90 (the enum, the record with its comment, seven arms); `VarkaBatchArgs` 4;
`Analysis`, `Slots`, `VarkaVectorWalk` 20; `VarkaZoneLowering` 55; `VarkaKernelPlan` 45;
`VarkaRangeAnalysis`, `VarkaSameAnswer`, `VarkaChronoCompiler`, `VarkaEmitBudget` 15;
`VarkaChronoZone` and `VarkaOperandRange` 16; the fifteen test registries 120;
`VarkaLocalLineKernels` 145; `VarkaLocalLineSuite`, seven tests, 250: about 760, and a
reviewer counted 700 to 800. Part b: the record and its arms 100; the lowering 30; the view
90; the seam and the ladder's stop 10; the registries 60; eight tests of
`VarkaLocalLineSuite` 270 and the runner test 60: about 620, and a reviewer counted 580 to
650. Part a is over 750, so its cut line is in its sheet: test 7 and the two budget rows
move to part b past 900. Part b's cut line moves tests 14 and 15 to part c.

**Which parts wait for row 688:** e and f, and so i. Parts a to d, g and h do not: their
operand is a column. **Side by side:** after c, parts d and g can be built by two agents at
once (d edits the compiler and the coverage table, g the test harness). Parts e and f both
edit `VarkaZoneCompiler.arm` and the coverage family: build them in either order, one after
the other.

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
      M9=sql/varka/plans/m9

- Wrap every `build/sbt`, `dev/varka_emit.sh`, `dev/lint-java`, `dev/lint-scala` and
  `dev/varka_errorprone.sh` call in `flock "$VARKA_HEAVY_LOCK"`. Call
  `dev/varka_regen_generated.sh` and `dev/varka_push_check.py` bare: they take the lock.
- Run B0 before any edit. A line marked `# N` must print `N`; a line marked "note" prints
  what a later step names. A count that differs is a stop: report the command and its
  output. None of the B0 lines over rows 682, 678, 657, 656, 684, 685 and 688 could be run
  on 27 November 2026; each is copied from that row's merged plan.
- A JUnit class passes when sbt prints `Passed: Total N, Failed 0, Errors 0, Passed N`. A
  ScalaTest run passes with `Tests: succeeded N, failed 0` and no `*** FAILED ***`. Where a
  sheet adds tests to a suite of another row, B0 notes the suite's count `n` and the sheet
  says `n + k`.
- A planted fault is planted after the part's commit, the named command is rerun, the named
  test must fail by its assertion, and the fault is reverted with `git checkout -- <file>`.
- Comments: read the class comment and the Javadoc of every method a step edits. A sentence
  that says `ToLocal` is the only zone node becomes "the zone nodes are `ToLocal`,
  `ToInstant` and `ToInstantKept`" (in part a, without the last). A sentence that says
  `toInstantArgs` or `localChanges` is declared for a later row becomes "read by the
  programs of `ToInstant` and `ToInstantKept`, through `VarkaBatchArgs.dataOf`".
- Before the commit: `dev/varka_precommit.sh`; `dev/varka_errorprone.sh --master "$(git
  rev-parse origin/master)"` and `dev/lint-java`; `dev/lint-scala` where Scala changed;
  `python3 dev/varka_plan_lint.py --check --offline`. After it: `python3
  dev/varka_push_check.py`, which prints the size and must exit 0.
- One local commit. Its subject is the part's title; its body ends with one attribution
  line, `Generated-by: <tool> (<model>)`. No push, no pull request, no dispatch.
- The row's line in `$M9/PLAN.md` reads `**Planned**` once this plan has merged. Part a
  makes it `**Started**` and names the parts left; part i makes it `**Done**`. Each part
  adds what it built to section 11 of this file.
- **How a test of `VarkaTimestampZoneSuite` runs** (parts d, e and f; row 657 built the
  suite so, and each part's B0 greps the three calls). The conf of a check is
  `VarkaCoverageHarnessSuite.conf(ansi)`, `ansi` true or false, with two keys set on it:
  `spark.sql.session.timeZone` to the zone id, and
  `spark.sql.execution.arrow.maxRecordsPerBatch` to the table's row count for one batch or
  to 64 for batches of 64 rows. The plan comes from `VarkaHarnessPlans.projection` inside
  `VarkaCoverageHarnessSuite.withConf`, and runs by `VarkaExpressionHarness.projection` (a
  filter by `filter`), which compares every row with Spark's interpreted and generated
  projections. The form is an emit option, not a conf: before a check,
  `VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.BASE
  .withZoneForm(form))`, and after it the same call with `VarkaMatrixRuns.BASE`. "In each
  form" is `ZoneForm.ONE`, `STEPS_2` and `STEPS_8`. A batch's outcome is read from the
  harness's `Batch.status()`: 0 is answered. No fixture value of parts d to f lies within
  18 hours of an end of a long but the ones a test of the ends names.
- **Counting tests.** A count such as `n4 + 7` is of declarations: `grep -c '@Test' <file>`,
  which counts `@TestFactory` too. sbt's `Passed: Total` counts each dynamic test of a
  factory, so for a suite with a dynamic test the sheet gives no total: the oracle is the
  declaration count and `Failed 0, Errors 0`.
- Stop also on: a test that should pass and fails; a generated file that moves where the
  sheet says it does not; a size over 900; a B0 "note" that shows a shape no step covers.

### 5.1 Part a: `ToInstant`, its registries and the one-offset kernel

**B0.**

    git branch --list 'varka-690a-to-instant' | wc -l                                         # 0
    grep -c 'record ToLocal(VarkaVectorIR instant, int zone, int hash)' $V/VarkaVectorIR.java   # 1
    grep -c -w 'ToInstant\|Resolve' $V/VarkaVectorIR.java                                     # 0
    grep -n 'sealed interface Zoned' -A1 $V/VarkaVectorIR.java                                # note: permits ToLocal
    grep -n 'static void emitToLocal(' -A14 $V/VarkaZoneLowering.java                         # note: how the form is read
    grep -n 'static int laneCalls(' $V/VarkaZoneLowering.java                                 # note: one line
    grep -n 'public static int count(' -A8 $V/VarkaBatchArgs.java                             # note: the switch over Zoned
    grep -n 'ZONE_OPERAND' $V/VarkaKernelPlan.java                                            # note: the kind, the reason and the test in of
    grep -c 'enum Rule { EARLIER, GAP_END, KEPT }' $V/VarkaChronoZoneChanges.java             # 1
    grep -n 'case VarkaVectorIR.ToLocal' $V/VarkaChronoZone.java $TJ/VarkaJavaTimeZoneData.java   # note: the two arms that throw
    grep -n 'weight\|laneCalls' $V/VarkaVectorIR.java | grep -i 'laneCalls'                   # note: ToLocal's weight expression
    ls $V/VarkaOperandRange.java 2>/dev/null | wc -l                                          # note: 1 means step 7's last sentence applies
    grep -c 'TABLE(-1)' $V/VarkaEmitOptions.java                                              # note: 1 means step 8 applies
    grep -n 'records.size() >= ' $TJ/VarkaNodePropsSuite.java                                 # note: the floor N
    grep -n '@VarkaSuite' $TJ/VarkaBatchArgsSuite.java                                        # note: the annotation to copy
    grep -n 'List<String> zones' $TJ/VarkaReference.java                                      # note: row 682's overloads of mixedValue
    git grep -n -w 'ToLocal' -- sql/catalyst/src sql/core/src | grep -v 'VarkaZoneCompiler\|Suite' | wc -l   # note: the places of step 9's rule

**Steps.**

1. `$V/VarkaVectorIR.java`: `Resolve` and `ToInstant` after `ToLocal` (4.1); `ToInstant` in
   `Zoned`'s `permits` list, and in no other; the arms of `laneType`, `narrowValuesIn`,
   `needsNarrowSpecies`, `canonicalWith`, `canonicalShallow`, `childrenOf`, `withChildren`.
2. `$V/VarkaBatchArgs.java`: the arm of `count` (4.3).
3. `$V/Analysis.java`, `$V/Slots.java`, `$V/VarkaVectorWalk.java`: the arms of 4.2.
4. `$V/VarkaZoneLowering.java`: `emitToInstant` with the form `ONE` of 4.5; under any other
   form it throws `IllegalStateException(VarkaKernelPlan.ZONE_LOCAL_STEPS_REASON)`.
   `laneCalls(node, form)`: `laneCalls(form)` for a `ToLocal`; `1 + 2k` and `1 + 5k` for the
   two rules of a `ToInstant` (4.6).
5. `$V/VarkaKernelPlan.java`: `Kind.ZONE_LOCAL_FORM` after `ZONE_OPERAND`, with the comment
   "A local time to an instant under a form its lowering does not have."; the constant
   `ZONE_LOCAL_STEPS_REASON` (4.9); in `of`, directly after the refusal of `ZONE_OPERAND`: a
   tree that holds a `ToInstant` is refused with it when the form, read as `emitToLocal`
   reads it, is not `ZoneForm.ONE`. `ToInstant` joins `ToLocal` in `longNarrowRole`.
6. `$V/VarkaRangeAnalysis.java`, `$V/VarkaSameAnswer.java`, `$C/VarkaChronoCompiler.java`,
   `$V/VarkaEmitBudget.java`: the arms of 4.2.
7. `$V/VarkaChronoZone.java`: the arm of 4.2. If `VarkaOperandRange.java` exists: its
   `walk` gets the arm of 4.2, and the line of `ToInstant` in its class comment's table.
8. Only if B0 found `TABLE(-1)`: `ZONE_LOCAL_TABLE_REASON` (4.9), and `of` refuses a tree
   that holds a `ToInstant` under `ZoneForm.TABLE` or `TABLE_SCALAR` with it, tested before
   the steps refusal.
9. The tests' registries, each as 4.2 has it: `$TJ/VarkaReference.java`,
   `VarkaJavaTimeZoneData.java`, `VarkaIrGrammar.java`, `VarkaGuardSpecs.java`,
   `VarkaWordCensus.java`, `VarkaCanonicalIr.java`, `VarkaSmtNodes.java`,
   `VarkaReproductionSuite.java`, `VarkaNodePropsSuite.java` (floor `N + 1`);
   `$TS/VarkaLaneTypeSuite.scala`, `VarkaIrFuzzSuite.scala`, `VarkaWithChildrenSuite.scala`,
   `VarkaEmitCostSuite.scala`, `VarkaEmitterBudgetSuite.scala` (two rows, one a rule),
   `VarkaRangeAnalysisSuite.scala`. The rule for a place 4.2 does not name: where a line of
   B0's last command gives `ToLocal` an arm or a list entry, `ToInstant` gets the same with
   `local()` for `instant()`; name each such place in the pull request's description.
10. `$TJ/VarkaLocalLineKernels.java`, new, whole below; `$TJ/VarkaLocalLineSuite.java`, new.

**The helper class,** in the package of `VarkaEmitterFixtures`, public, so that the suites
of `sql/core` reach it through catalyst's test jar, as they reach `VarkaJavaTimeZoneData`.
Its imports: `java.lang.foreign.Arena`, `java.lang.foreign.ValueLayout`, `java.time.Instant`,
`java.time.LocalDate`, `java.time.ZoneId`, `java.time.zone.ZoneOffsetTransition`,
`java.util.ArrayList`, `java.util.List`, `java.util.OptionalLong`, `java.util.TreeSet`.

    public final class VarkaLocalLineKernels {
      private VarkaLocalLineKernels() {}

      public static final long MICROS = 1_000_000L;
      public static final long DAY = 86_400_000_000L;

      /** The changes of {@code zone} whose instant lies in {@code year} at UTC, in order. */
      public static List<ZoneOffsetTransition> changesOf(ZoneId zone, int year) {
        long from = LocalDate.of(year, 1, 1).toEpochDay() * 86_400L;
        long to = LocalDate.of(year + 1, 1, 1).toEpochDay() * 86_400L;
        var out = new ArrayList<ZoneOffsetTransition>();
        var rules = zone.getRules();
        ZoneOffsetTransition t = rules.nextTransition(Instant.ofEpochSecond(from - 1));
        while (t != null && t.toEpochSecond() < to) {
          out.add(t);
          t = rules.nextTransition(t.getInstant());
        }
        return out;
      }

      /** The start of a change's window on the local line, in microseconds. */
      public static long lo(ZoneOffsetTransition t) {
        return (t.toEpochSecond() + Math.min(t.getOffsetBefore().getTotalSeconds(),
            t.getOffsetAfter().getTotalSeconds())) * MICROS;
      }

      /** The end of a change's window on the local line, in microseconds. */
      public static long hi(ZoneOffsetTransition t) {
        return (t.toEpochSecond() + Math.max(t.getOffsetBefore().getTotalSeconds(),
            t.getOffsetAfter().getTotalSeconds())) * MICROS;
      }

      /** Local times of a year: fifteen around each window, and one every six hours. */
      public static long[] localFixture(ZoneId zone, int year) {
        var values = new TreeSet<Long>();
        long[] before = {-7_200_000_000L, -3_600_000_000L, -1_000_000L, -1L, 0L, 1L, 1_000_000L};
        long[] after = {-1_000_000L, -1L, 0L, 1L, 1_000_000L, 3_600_000_000L, 7_200_000_000L};
        for (ZoneOffsetTransition t : changesOf(zone, year)) {
          for (long d : before) {
            values.add(lo(t) + d);
          }
          values.add(lo(t) + (hi(t) - lo(t)) / 2);
          for (long d : after) {
            values.add(hi(t) + d);
          }
        }
        long first = LocalDate.of(year, 1, 1).toEpochDay() * DAY;
        for (int i = 0; i < 1460; i++) {
          values.add(first + i * 21_600_000_000L);
        }
        return values.stream().mapToLong(Long::longValue).toArray();
      }

      /**
       * Emits {@code root} over {@code columns.length} long inputs and no literal, runs it once
       * with {@code longArgs}, and returns two arrays: the output's values, and 1 where a row
       * is valid. A null row's input storage is poison. It fails on a status other than 0.
       */
      public static long[][] run(VarkaVectorIR root, VarkaEmitOptions options, long[] longArgs,
          long[][] columns, boolean[][] nulls) {
        var loaded = VarkaEmitterFixtures.load(
            VarkaEmitterFixtures.emit(List.of(root), columns.length, 0, options));
        try {
          return runLoaded(loaded.kernel(), longArgs, columns, nulls);
        } finally {
          loaded.loader().release();
        }
      }

      /**
       * {@link #run} for a kernel that is loaded already, so that a sweep emits no class a
       * call. The kernel runs inside the memory sanitizer's window over these buffers
       * ({@code VarkaKernelChecks.sanitized}), each a column of eight bytes a row.
       */
      public static long[][] runLoaded(VarkaFusedKernel kernel, long[] longArgs,
          long[][] columns, boolean[][] nulls) {
        int n = columns[0].length;
        try (Arena arena = Arena.ofConfined()) {
          long[] data = new long[columns.length];
          long[] validity = new long[columns.length];
          int[] nullCount = new int[columns.length];
          long[] stride = new long[columns.length];
          for (int c = 0; c < columns.length; c++) {
            long[] column = columns[c];
            boolean[] isNull = nulls[c];
            var in = VarkaEmitterFixtures.makeLongInput(
                arena, n, i -> isNull[i], i -> column[i], true);
            data[c] = in.data().address();
            validity[c] = in.validityAddress(n);
            nullCount[c] = in.nullCount();
            stride[c] = 8L;
          }
          var out = VarkaEmitterFixtures.makeLongOutput(arena, n);
          long[] dst = {out.data().address()};
          long[] dstValidity = {out.validity().address()};
          int status = VarkaKernelChecks.sanitized(kernel, n, stride, new long[] {8L}, data,
              validity, dst, dstValidity, scratch -> kernel.run(data, validity, nullCount, dst,
                  dstValidity, new int[0], longArgs, n, scratch));
          if (status != 0) {
            throw new AssertionError("the kernel answered status " + status);
          }
          long[][] rows = new long[2][n];
          for (int i = 0; i < n; i++) {
            rows[0][i] = out.data().get(ValueLayout.JAVA_LONG, i * 8L);
            rows[1][i] = (out.validity().get(ValueLayout.JAVA_BYTE, i / 8L) >> (i % 8)) & 1;
          }
          return rows;
        }
      }

      /** java.time's answer for one row of {@code root}, empty where the row is null. */
      public static OptionalLong expected(VarkaVectorIR root, long[] row, boolean[] isNull,
          List<String> zones) {
        return VarkaReference.mixedValue(root, row, isNull, new int[0], new long[0], zones);
      }
    }

`expected` calls the overload of `mixedValue` that row 682 added, with the zone ids last; B0
prints it. The file starts with the license header of `VarkaEmitterFixtures.java` and that
file's `package` line. `runLoaded` calls `VarkaKernelChecks.sanitized`, which is
package-private in this package: without it the memory sanitizer checks nothing, since it
checks only a thread inside its window. Every kernel this row's catalyst suites run goes
through `run` or `runLoaded`, or through row 684's harness, which calls `sanitized` itself;
the suites of `sql/core` run kernels through the evaluator's runner. A kernel with no
literal has its first batch argument at index 0 of `longArgs`.

**Tests.** `VarkaLocalLineSuite`: JUnit 5, the annotation B0 printed, each test with its
sentence as `@DisplayName`, options `VarkaMatrixRuns.BASE.withZoneForm(ZoneForm.ONE)` unless
said. `col0` is `new ColumnRef(0, LaneType.LONG)`. Local longs are microseconds.

| # | test | inputs | expected, and its oracle | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | ToInstant refuses an int-lane operand, a condition operand, a negative zone and a null rule | `new ColumnRef(0, LaneType.INT)`; the condition that `VarkaBatchArgsSuite`'s test "the record refuses an int-lane operand, a `Cond` operand, a negative zone" passes to `ToLocal`, copied; zone -1; `new ToInstant(col0, 0, null)` | each constructor throws | a node the emitter cannot lower |
| 2 | its canonical text round-trips, and two rules or two zones are two nodes | `(toInstant:0:EARLIER col:0:long)`, `(toInstant:1:GAP_END col:0:long)` through `VarkaCanonicalIr` | the parsed node equals the built one; the four nodes of two rules and two zones are pairwise unequal with unequal hashes | two rules sharing one cached kernel |
| 3 | a program holds 1, 5 and 17 arguments under the earlier offset and 1, 7 and 25 under the gap's end | `VarkaBatchArgs.count` for each rule and each of `ONE`, `STEPS_2`, `STEPS_8`; `layout` of the two rules' roots as two outputs with `numLiterals = 3`, under `ONE` | the six counts; the programs start at 3 and at 4 | a kernel that reads past its program |
| 4 | the one-offset kernel subtracts the base, dense and with nulls, at every length | both rules' roots through `VarkaLocalLineKernels.run`; `longArgs` a `long[1]` holding 3600000000, then -28800000000, then 0; lengths `VarkaEmitterFixtures.LENGTHS` (at length 0 the column is empty and so is the answer); row `i` is `i * 1_000_003L - 5_000_000_000L`; once with no null and once with every seventh row null | `rows[0][i] == in - base` and `rows[1][i] == 1` on a live row; `rows[1][i] == 0` on a null row; a Java loop | a base added, or read from the wrong index |
| 5 | a form with steps over a ToInstant is refused by name | `VarkaKernelPlan.of(List.of(root), options)` under `STEPS_2` and `STEPS_8`; under `TABLE` too where it exists | the refusal is present, its kind `ZONE_LOCAL_FORM`, its reason `ZONE_LOCAL_STEPS_REASON` (the table reason under `TABLE`) | a form emitted with no lowering |
| 6 | the reference's ToInstant is java.time's, by its public API | `VarkaLocalLineKernels.expected(root, new long[] {L}, new boolean[] {false}, zones)`. `zones = List.of("America/Los_Angeles")`: `L` = 1615689000000000 (02:30 on 14 March 2021, in the gap) and 1636248600000000 (01:30 on 7 November 2021, in the overlap). `zones = List.of("America/Toronto")`: `L` = -1601769600000000 (the midnight of 31 March 1919, inside a gap) | `EARLIER`: 1615717800000000, 1636273800000000, -1601751600000000. `GAP_END`: 1615716000000000, 1636273800000000, -1601753400000000. A null row is empty. And, from `java.time` directly: `ldt.atZone(zone)` equals `ZonedDateTime.ofLocal(ldt, zone, null)` at the three; `LocalDate.ofEpochDay(-18539).atStartOfDay(zone)` is the `GAP_END` answer of the third | a reference that takes the earlier offset at a midnight in a gap |
| 7 | the bytes and the lane calls of the one-offset kernel are pinned | `VarkaEmitterFixtures.emit(List.of(root), 1, 0, options)` for `(toInstant:0:EARLIER col:0:long)`; `VarkaEmitterFixtures.laneOps(bytes, "loopDense0")`; tagged as `VarkaBatchArgsSuite`'s byte test is | the code length of `loopDense0` that the first run prints, pinned; the lane operations `ToLocal`'s one-offset pin has; no `VectorMask` call | a lowering that grows unseen |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaLocalLineSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaNodePropsSuite *VarkaLaneTypeSuite *VarkaIrFuzzSuite *VarkaWithChildrenSuite *VarkaEmitCostSuite *VarkaEmitterBudgetSuite *VarkaRangeAnalysisSuite *VarkaSmtNodesSuite *VarkaReproductionSuite *VarkaEmittedBytesSuite *VarkaVectorIRSharingSuite *VarkaBatchArgsSuite *VarkaEmitterZoneSuite *VarkaChronoZoneSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/Test/compile'
    dev/varka_regen_generated.sh
    git status --short
    python3 dev/varka_push_check.py

Expected: the first prints `Passed: Total 7, Failed 0, Errors 0, Passed 7`; the second
prints no failure; the third `[success]`; `git status --short` lists only the files of the
steps, no generated file (`emitted_bytes.json`, `coverage.json`, `width_audit.json`,
`docs/sql-varka.md` and the census files do not move); the push check exits 0 and its R1
lines name no missing registry.

**Planted faults.**

| fault | the test that must fail |
| :--- | :--- |
| `emitToInstant` drops the `lneg` | test 4 |
| `count` gives the gap's end two arguments a step | test 3 |
| the reference's `GAP_END` arm ignores `isGap()` | test 6, on `America/Toronto` |
| the refusal of `of` tests `ToLocal` | test 5 |

**Done when** the commands print the above, the four faults fail as named, and the size is
under 900. If it passes 900: move test 7 and the two budget rows to part b.
**Not here:** `ToInstantKept`; the view and the seam; a form with steps; a compiler arm; a
grammar draw; a coverage row; any generated file.

### 5.2 Part b: `ToInstantKept`, the view and the seam

**B0.**

    git branch --list 'varka-690b-kept' | wc -l                                               # 0
    grep -c 'record ToInstant(VarkaVectorIR local, int zone, Resolve rule, int hash)' $V/VarkaVectorIR.java   # 1
    grep -c -w 'ToInstantKept' $V/VarkaVectorIR.java                                          # 0
    grep -c 'static void emitToInstant(' $V/VarkaZoneLowering.java                            # 1
    grep -c 'dataOf' $V/VarkaBatchArgs.java                                                   # 0
    grep -c '@Test' $TJ/VarkaLocalLineSuite.java                                              # 7
    grep -rn 'int MANY' $V                                                                    # note: one line, the constant's home
    git grep -n 'zoneData()' -- sql/core/src/main                                             # note: every place a program's zone data is taken
    grep -n 'widest = ' $E/VarkaEvaluatorBase.java                                            # note: one line if row 685 merged, else nothing
    grep -c '@Test\|test(' $XS/VarkaBatchArgRunnerSuite.scala                                 # note: n1
    grep -n 'zoneData()' $XS/VarkaBatchArgRunnerSuite.scala                                   # note: where the suite's evaluator names its provider
    git grep -n -w 'ToInstant' -- sql/catalyst/src sql/core/src | grep -v 'VarkaLocalLine' | wc -l   # note: the places of step 2

**Steps.**

1. `$V/VarkaVectorIR.java`: the record (4.1), in `Zoned`'s `permits` list and in no other,
   with its arms.
2. At every place B0's last command printed where `ToInstant` has a switch arm or a list
   entry, `ToInstantKept` gets the arm or entry 4.2 gives it: the files of part a's steps 2
   to 9, among them `count` (4.3), the refusals of `VarkaKernelPlan.of`,
   `VarkaChronoZone.toInstantArgs` with `Rule.KEPT`, the reference's arm (4.2),
   `VarkaEmitterBudgetSuite`'s row `(node, 2, 0, ...)` and `VarkaRangeAnalysisSuite`'s `case
   x: ToInstantKept`. `Slots.plan` gives the node two vector locals in a form with steps.
3. `$V/VarkaZoneLowering.java`: `emitToInstantKept` with the form `ONE` of 4.5, steps 1 to 3;
   under any other form it throws as `emitToInstant` does. `laneCalls` gains the third arm,
   `1 + 4k`.
4. `$V/VarkaBatchArgs.java`: `LOCAL_MAX_SPAN`, `dataOf`, the nested `LocalLine` and
   `readsLocalLine` (4.4), after `zonesNeeded`. `MANY` is read from the home B0 printed.
5. The seam (4.4): at each line B0 printed where a `VarkaZoneData` from `zoneData()` is
   kept for a program, the value kept becomes `VarkaBatchArgs.dataOf(<the program's node>,
   <that value>)`, after its null test. If B0 printed a `widest = ` line, directly after
   it: `if (VarkaBatchArgs.readsLocalLine(<the layout's programs>)) { widest =
   Math.min(widest, VarkaZoneLadder.RUNGS.indexOf(ZoneForm.STEPS_8)); }`.
6. `$TJ/VarkaLocalLineKernels.java`: one method more, after `run`:

       /**
        * The arguments of {@code root}'s one program for the range, or null where the batch
        * declines: the range is within 18 hours of an end of a long, or it holds more changes
        * than the form has steps. {@code data} is the provider's answer; the view is taken here.
        */
       public static long[] filled(VarkaVectorIR.Zoned root, VarkaEmitOptions.ZoneForm form,
           VarkaZoneData data, long least, long greatest) {
         long m = VarkaBatchArgs.MAX_OFFSET_MICROS;
         if (least < Long.MIN_VALUE + m || greatest > Long.MAX_VALUE - m) {
           return null;
         }
         VarkaZoneData line = VarkaBatchArgs.dataOf(root, data);
         if (line.changes(least, greatest) > form.steps()) {
           return null;
         }
         long[] args = new long[VarkaBatchArgs.count(root, form)];
         line.toLocalArgs(least, greatest, form.steps(), args, 0);
         return args;
       }

7. `$TJ/VarkaLocalLineSuite.java`: eight tests more; `$XS/VarkaBatchArgRunnerSuite.scala`:
   one.

**Tests,** numbered on from part a's seven. `col1` is `new ColumnRef(1, LaneType.LONG)`;
`kept` is `new ToInstantKept(col0, col1, 0)`; `LA` is
`VarkaChronoZone.of("America/Los_Angeles")`; a *stub* is a `VarkaZoneData` written in the
test that records each call's name and arguments, answers 7 from `localChanges` and 9 from
`changes`, and throws from every other method.

| # | test | inputs | expected, and its oracle | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 8 | ToInstantKept refuses an operand off the long lane or a condition in either place, and round-trips its text | each of test 1's two bad operands as `local` and as `kept`; zone -1; `(toInstantKept:0 col:0:long col:1:long)` | each constructor throws; the parsed node equals the built one; the node with its operands exchanged is unequal | the kept offset read as the local time |
| 9 | the reference's ToInstantKept is ZonedDateTime.ofLocal, by its public API | `expected(kept, new long[] {L, K}, two falses, List.of("America/Los_Angeles"))`. `L` = 1636248600000000 (in the overlap) with `K` of -25200000000, -28800000000, 0 and 123; `L` = 1615689000000000 (in the gap) with the same four | overlap: 1636273800000000, 1636277400000000, 1636273800000000, 1636273800000000. Gap: 1615717800000000, all four. And `ZonedDateTime.ofLocal(ldt, zone, ZoneOffset.ofHours(-8))` gives the second answer | a kept offset preferred in a gap, or ignored in an overlap |
| 10 | its program holds 1, 9 and 33 arguments | `VarkaBatchArgs.count(kept, form)` in the three forms | 1, 9, 33 | a program sized as the earlier offset's |
| 11 | the one-offset kernel subtracts the base and is null where either operand is | `run(kept, options, longArgs, two columns, nulls)`; `longArgs` as test 4; column 0 as test 4's values; column 1 the constant 5; nulls at every seventh row of column 0 and every fifth of column 1 | `in0 - base` and valid where both are live; invalid where either is null | a validity word taken from one operand |
| 12 | a form with steps over a ToInstantKept is refused by name | as test 5 | as test 5 | as test 5 |
| 13 | the view of a local node counts windows, fills the node's arguments and refuses a span over LOCAL_MAX_SPAN | the stub; `dataOf(node, stub)` for `node` each of `new ToInstant(col0, 0, EARLIER)` and `kept`; `dataOf(new ToLocal(col0, 0), stub)` | the view's `changes(5, 6)` and `localChanges(5, 6)` are 7 and the stub saw `localChanges(5, 6)` twice; its `toLocalArgs(5, 6, 2, a, 1)` reaches the stub as `toInstantArgs(node, 5, 6, 2, a, 1)`; `changes(-LOCAL_MAX_SPAN / 2 - 1, LOCAL_MAX_SPAN / 2 + 1)` and `changes(Long.MIN_VALUE, Long.MAX_VALUE)` are `MANY` and reach no stub method; `changes(0, LOCAL_MAX_SPAN)` is 7; the view's `toInstantArgs` throws `IllegalArgumentException`; for the `ToLocal` the stub itself comes back (until row 689's part a, which returns a view of the instant line there and edits this clause: `VARKA-689.md` 5.1); `readsLocalLine` is true for a layout of `kept` and false for one of the `ToLocal` | a local program filled from the instant line |
| 14 | the production data fills each rule's program as that rule's arguments | `LA`; `least` = 1609459200000000, `greatest` = 1640908800000000 (the local year 2021); 2 steps; arrays filled with -1; `first` = 2 | `LA.toInstantArgs(node, ...)` into a `long[7]` (`EARLIER`), a `long[9]` (`GAP_END`) and a `long[11]` (`kept`) equals `LA.data().toInstantArgs(Rule.EARLIER, GAP_END or KEPT, ...)` into another, entry for entry; entries 0 and 1 stay -1; `args[2]` is -28800000000; `dataOf(node, LA).changes(least, greatest)` is 2; a `ToLocal` node still throws `IllegalArgumentException` | a rule's arguments under another rule's layout |
| 15 | filled arguments give the one-offset kernels java.time's answers between two windows, and a batch that touches a window is declined | `filled(root, ONE, LA, least, greatest)` and `run`, for the three roots (the kept column the constant -25200000000): the eight values `1615692600000000 + j * 2400000000L`, `j` from 0 to 7 (03:30 to 08:10 local on 14 March 2021); then the pair 1615680000000000 and 1615692600000000 (00:00 and 03:30) | the first: each row is `in + 25200000000`, which `expected` also answers; the second: `filled` returns null | arguments read as an instant's: as instants the eight lie before the change and take -8 hours |

`VarkaBatchArgRunnerSuite`, one test: "a ToInstant program is filled on the local line: a
batch after a gap takes the offset after it, and one that touches the gap under ONE is
declined". Copy the suite's first test over `(toLocal:0 col:0:long)` and change four things:
the root is `(toInstant:0:EARLIER col:0:long)`; the zones are `Seq("America/Los_Angeles")`;
the evaluator's `zoneData()` (the line B0 printed) returns `VarkaChronoZone.PROVIDER`; the
form is `ONE`. Batch 1 is test 15's eight values: answered, each `in + 25200000000`. Batch 2
is test 15's pair: declined, the metric `batch-args` is 1, and the rows come from the row
path. It fails where the seam is not wrapped.

**Commands.** Part a's six, with `'sql/testOnly *VarkaBatchArgRunnerSuite
*VarkaTimestampZoneSuite'` for its third. Expected: the first prints `Passed: Total 15,
Failed 0, Errors 0, Passed 15`; `VarkaBatchArgRunnerSuite` runs `n1 + 1` tests; the rest as
in part a; no generated file moves.

**Planted faults.**

| fault | the test that must fail |
| :--- | :--- |
| `dataOf` returns `zone` for a `ToInstant` | tests 13 and 15, and the runner suite's new test |
| the seam left unwrapped | the runner suite's new test |
| `Analysis.analyze` passes `k.local()` alone | test 11, on a row where only column 1 is null |
| the reference prefers `kept` in a gap too, answering `L - kept` there | test 9 |
| `count` returns `1 + 3 * k` for the record | test 10 |

**Done when** the commands print the above and the five faults fail as named. If the size
passes 900: move tests 14 and 15 to part c.
**Not here:** a form with steps; a compiler arm; any generated file.

### 5.3 Part c: the masked steps of the three rules

**B0.**

    git branch --list 'varka-690c-steps' | wc -l                                              # 0
    grep -c 'ZONE_LOCAL_STEPS_REASON' $V/VarkaKernelPlan.java $V/VarkaZoneLowering.java       # note: each file's count, at least 1
    grep -c 'static void emitToInstantKept(' $V/VarkaZoneLowering.java                        # 1
    grep -n 'ZONE_LOCAL_STEPS_REASON\|form with steps' -r sql/catalyst/src/test sql/core/src/test docs   # note: every holder of the refusal
    grep -c 'public static long\[\] filled(' $TJ/VarkaLocalLineKernels.java                     # 1: part b merged
    grep -c '@Test' $TJ/VarkaEmitterZoneSuite.java                                            # note: n2
    grep -n 'ToInstant' $TS/VarkaEmitterBudgetSuite.scala                                     # note: three rows under ONE, of parts a and b

**Steps.**

1. `$V/VarkaZoneLowering.java`: the steps of 4.6 in `emitToInstant` (both rules) and
   `emitToInstantKept`; the throws of parts a and b go.
2. `$V/VarkaKernelPlan.java`: delete `ZONE_LOCAL_STEPS_REASON` and its test in `of`. Delete
   `Kind.ZONE_LOCAL_FORM` too unless `ZONE_LOCAL_TABLE_REASON` exists.
3. `$TJ/VarkaLocalLineSuite.java`: tests 5 and 12 keep their table cases where `TABLE`
   exists and are deleted where it does not; every other holder B0 printed is removed.
4. `$TS/VarkaEmitterBudgetSuite.scala`: beside each of the three rows B0 printed, the same
   row under `VarkaMatrix.base.withZoneForm(ZoneForm.STEPS_2)` and under `STEPS_8`: `(node,
   1, 0, options)` for the two rules of a `ToInstant`, `(node, 2, 0, options)` for a
   `ToInstantKept`. The suite holds each row's lane calls to `weightOf`, which reads
   `laneCalls(node, form)`: 5 and 17; 11 and 41; 9 and 33.
5. `$TJ/VarkaEmitterZoneSuite.java`: seven tests (below). They call
   `VarkaLocalLineKernels.localFixture`, `changesOf`, `lo`, `hi`, `filled`, `run` and
   `expected`, and no helper of the suite itself. A batch is run as
   `run(root, VarkaMatrixRuns.BASE.withZoneForm(form), args, columns, nulls)` with `args =
   filled(root, form, VarkaChronoZone.of(id), least, greatest)`, `least` and `greatest` the
   least and greatest live value of column 0; a null `args` means the form does not cover
   the batch. Each live row must be valid and equal `expected(root, row, isNull,
   List.of(id)).getAsLong()`; each null row must be invalid.

**The local fixture of a zone and a year** is `VarkaLocalLineKernels.localFixture(zone,
year)` (5.1): fifteen local times around each window whose change lies in the year at UTC,
and one value every six hours from the year's first local midnight, ascending.
The zones and years: `America/Los_Angeles` 2021, `Australia/Lord_Howe` 2021,
`Europe/Podgorica` 2021, `America/Havana` 2021, `Africa/Casablanca` 2017, `Pacific/Apia`
2011, `Asia/Kathmandu` 1985 (its change of 1 January 1986 is at 18:30 UTC the day before),
`America/Toronto` 1919 and `America/Los_Angeles` 1883 (the change from the local mean time
of -7:52:58, an offset that is no whole minute). Each test first asserts that `changesOf`
is not empty for each of the nine. Three kinds of batch are cut from it:
*between* (the six-hour values strictly between two consecutive windows), *around* (a
window's fifteen points and the six-hour values within three days of it) and *year* (all).
A batch is run in a form only where `filled` answers for it, which is where the range
touches no more windows than the form has steps, and each test asserts that it ran a batch
of each kind in at least one form. Every seventh row is null; each batch runs ascending and
in the order `(i * 7919) % n`.

| # | test | inputs | expected, and its oracle | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | ToInstant under the earlier offset equals LocalDateTime.atZone across every window of a year, in each form | the fixture, each zone | each live row equals the reference of 4.2 | a threshold taken from the instant line |
| 2 | ToInstant under the gap's end equals java.time across every window and at every midnight of a year, in each form | the fixture plus the year's 365 local midnights | the reference; on `America/Toronto` the row -1601769600000000 answers -1601753400000000 | the earlier offset taken at a midnight in a gap |
| 3 | ToInstantKept equals ZonedDateTime.ofLocal across every window of a year with each offset kept, in each form | the fixture; the second column of row `r` is, by `r % 4`: the offset after the nearest window, the offset before it, 0, the offset before plus 3600000000 (the nearest window by the distance from `lo`; microseconds) | the reference | a kept offset compared with the wrong argument |
| 4 | a kept offset that no window names answers as the earlier offset | the fixture; the second column cycles 1, `Long.MIN_VALUE`, `Long.MAX_VALUE` | equals the kernel of test 1 on the same rows | a wrap in the kept compare |
| 5 | hand-set steps: each rule equals the scalar meaning at each threshold and one microsecond either side | `STEPS_2`; base 1000; step 0 a gap, `lo` 4000, `hi` 5000, delta 1000; step 1 an overlap, `lo` 19000, `hi` 20000, delta -1000, after 1000; written in each rule's order (2.5); values 3999, 4000, 4999, 5000, 5001, 18999, 19000, 19999, 20000, 20001; for the kept rule each with `kept` of 2000, 1000 and 0. Then two windows that touch: base 1000; step 0 a gap, `lo` 4000, `hi` 5000, delta 1000; step 1 a gap, `lo` 5000, `hi` 6000, delta 1000; values 4999, 5000, 5999, 6000. Then every step padded, with the value `Long.MAX_VALUE - 64800000000` among them. Then the first case again with every row null | a scalar loop of 2.5's three formulas, written in the test. The touching windows: `EARLIER` and the kept rule answer 3999, 3000, 3999, 3000; `GAP_END` answers 3000 four times. All padded: `in - base`. Every row null: every output row is invalid | `>` for `>=`; a window closed at `hi`; a window's test that reads the running answer; padding that fires |
| 6 | the bytes, the lane calls and the mask calls of each rule and form are pinned | `loopDense0` of the three roots under `STEPS_2` and `STEPS_8` | the lane operations (`VarkaEmitterFixtures.laneOps`) are each root's own one-offset count plus 4 and 16; 10 and 40; 8 and 32; the `VectorMask` calls of `and` and `or` are 0 and 0; 2 and 8; 4 and 16; the code lengths the first run prints are pinned | a form that changes size unseen |
| 7 | the three rules at four lanes and at two | tests 1 to 3's `America/Los_Angeles` batches under `lanesOverride`, run only where `VarkaSpeciesGuard` allows, else emitted and verified, as `ToLocal`'s test of the widths does | as tests 1 to 3 | a width-specific slip in a masked add or a blend |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaEmitterZoneSuite *VarkaLocalLineSuite *VarkaEmitterBudgetSuite *VarkaNodePropsSuite *VarkaEmittedBytesSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'catalyst/testOnly *VarkaEmitterZoneSuite'
    dev/varka_regen_generated.sh
    git status --short
    python3 dev/varka_push_check.py

Expected: no failure; `grep -c '@Test' $TJ/VarkaEmitterZoneSuite.java` prints `n2 + 7`, and
`grep -c '@Test' $TJ/VarkaLocalLineSuite.java` prints 15 where `TABLE` exists and 13 where it
does not (step 3); the second command runs the same tests inside the sanitizer's window
(`runLoaded`); no generated
file moves; the push check exits 0.

**Planted faults.**

| fault | the test that must fail |
| :--- | :--- |
| `GT` for `GE` in the step's compare | test 5, at 5000 and at 20000 |
| the window's second compare reads `lo` for `hi` | test 2, on `America/Toronto`; test 5 at 4999 |
| `T` sums the deltas to `i - 1` | test 2, on `America/Toronto` |
| the kept compare reads `tmpL` | test 3 |
| `or` and `and` exchanged in the kept step | test 5, at 5000 with `kept` 0 |

**Done when** the commands print the above and the five faults fail as named.
**Not here:** a compiler arm; a step with no mask; a coverage row; any generated file.

### 5.4 Part d: `CAST(ntz AS TIMESTAMP)`

**B0.**

    git branch --list 'varka-690d-ntz-cast' | wc -l                                           # 0
    grep -c 'ZONE_LOCAL_STEPS_REASON' $V/VarkaKernelPlan.java                                 # 0: part c merged
    grep -n 'static Optional<VarkaVectorIR> toLocal(' -A30 $C/VarkaZoneCompiler.java          # note: the body to copy from
    grep -n 'case Cast c when' $C/VarkaZoneCompiler.java                                      # note: one line, the cast to DATE
    grep -c 'toInstant' $C/VarkaZoneCompiler.java                                             # 0
    grep -n 'covers\|"zone"' $V/VarkaFamily.java                                              # note: ZONE's covers text
    git grep -n 'ltzy' -- sql/catalyst/src/test sql/core/src/test | grep -v 'Row(\|"SELECT'   # note: where the column is declared, derived and filtered
    grep -n 'hour(ltzy)' $TS/VarkaCoverageSuite.scala                                         # note: the two families
    grep -P '^ntz\tltz\t' $M9/casts.tsv $M9/casts_fused.tsv                                   # note: legal in three modes, not fused
    grep -c '@Test' $CJ/VarkaZoneCompilerSuite.java; grep -c '@Test' $XJ/VarkaTimestampZoneSuite.java   # note: n3, n4
    grep -c 'setEmitOptionsForTesting' $XJ/VarkaTimestampZoneSuite.java                       # at least 1: 5.0's way of setting a form
    grep -c 'spark.sql.session.timeZone\|maxRecordsPerBatch' $XJ/VarkaTimestampZoneSuite.java   # at least 2: 5.0's two keys
    grep -c 'setProviderForTesting' $C/VarkaZoneCompiler.java                                 # at least 1: the planted provider of the compiler suite
    grep -c -- '--zone' dev/varka_emit.sh                                                     # at least 1: row 657's argument
    grep -n 'AS TIMESTAMP)' $XJ/benchmark/Varka*BaselineBenchmark.java                        # note: row 655's sections of this cast, or nothing
    python3 dev/varka_census.py --summary > target/census-before-690d.txt; echo $?             # 0

**Steps.**

1. `$C/VarkaZoneCompiler.java`: `toInstant` (4.10) after `toLocal`; the claim of 4.10's
   table after the cast to `DATE`, calling a new private method:

       private static Optional<VarkaVectorIR> instantOfNtz(
           Cast c, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
         return VarkaNodeCompiler.compileNode(c.child(), inputs, literals, sink).flatMap(t -> {
           if (t.laneType() != LaneType.LONG) {
             return sink.decline("a timestamp_ntz operand that is not on the long lane", c);
           }
           return toInstant(c, c.timeZoneId(), t, Resolve.EARLIER, sink);
         });
       }

   `TWO_LANE_LOCAL_RULE_REASON` (4.11) is declared here, beside the other reasons.
2. `$V/VarkaFamily.java`: `ZONE`'s `covers` gains, after its clause on the cast to `DATE`,
   "the cast of a TIMESTAMP_NTZ to TIMESTAMP".
3. The column `ntzy` (4.14): at each place B0 printed where `ltzy` is declared
   (`AttributeReference`), derived (the fixture view) or named by a filter
   (`readsZoned`), `ntzy` gets the same line with its type and derivation. Then the two
   coverage rows of 4.14, the first with the note "the local time less the zone's offset
   under the earlier-offset rule: one sum under UTC or a fixed offset, up to the form's
   steps of windows a batch in a zone with changes; a batch that touches more goes to the
   row engine".
4. Row 655's class: each section B0 printed is listed as fused by the table of
   `VARKA-657-PARTS.md` 5.2, step 6: `KERNEL` under UTC, a fixed offset, and a
   daylight-saving zone over a week or a year; `ALL_FALL_BACK` over ten years in no order.
   If B0 printed nothing, add no section and say so in the description.
5. `docs/sql-varka.md`, "Timestamps in the session time zone": "A cast of a `TIMESTAMP_NTZ`
   to `TIMESTAMP` fuses: it subtracts the zone's offset, taking the earlier offset where a
   local time is repeated and moving one that was skipped later, as Spark does."
6. `$CJ/VarkaZoneCompilerSuite.java`, `$XJ/VarkaTimestampZoneSuite.java`,
   `$XS/VarkaTimestampZoneQuerySuite.scala`: the tests below.
7. `dev/varka_regen_generated.sh --all`; `python3 dev/varka_census.py`.

**Tests.** `VarkaZoneCompilerSuite`, five:

| test | inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- |
| under UTC the cast of a TIMESTAMP_NTZ to TIMESTAMP is its operand, in every spelling | `UTC`, `Z`, `Etc/UTC`, `GMT`, `+00:00` | the column's own IR, no literal, no zone | a fold that subtracts a zero |
| under a fixed offset it is the operand less the offset under the 18-hour guard | `+01:00`, `-08:00`, `+05:45`, `+18:00`, `-18:00` | the tree of 4.10, the literals -3600000000, 28800000000, -20700000000, -64800000000, 64800000000, the guard's two bounds; two offsets give equal shapes | a sign, or bounds that depend on the offset |
| in a zone with changes it is a ToInstant under the earlier offset over the column | `America/Los_Angeles`, `PST`, `Asia/Kathmandu` | `new ToInstant(col, 0, EARLIER)`; `PST` shares the index | the gap's-end rule on a cast from TIMESTAMP_NTZ |
| the way back declines what the way there declines, by the same text | the operand IRs `greatest(col0, col1)` and `col0`; a missing zone; `Mars/Olympus`; a provider that answers null for every id, planted by `VarkaZoneCompiler.setProviderForTesting` and restored with null | for each, `toInstant`'s outcome is `toLocal`'s: both build, or both decline with one reason | a test of the operand copied wrongly |
| with the family off the cast declines naming the switch | `CAST(coalesce(ntz, ntz) AS TIMESTAMP)` | `VarkaFamily.ZONE.offReason()` | a claim under no switch |

`VarkaTimestampZoneSuite`, seven. A table is `VarkaHarnessCases.source("rid INT, ntz
TIMESTAMP_NTZ", rows)`, each row `new Object[] {i, value}` with `i` an `Integer` and `value`
a `Long` of `VarkaLocalLineKernels.localFixture(zone, year)`, or null at every seventh row.
A check runs as 5.0 says. The expectation of a batch is computed, with `node = new
ToInstant(new ColumnRef(0, LaneType.LONG), 0, Resolve.EARLIER)`: answered (status 0) when
the zone is fixed (`ZoneId.of(id).getRules().isFixedOffset()`), or when
`VarkaBatchArgs.dataOf(node, VarkaChronoZone.of(id)).changes(least, greatest)` over the
batch's live values is at most the form's steps; else declined with `STATUS_BATCH_ARGS`.
Every row of every batch equals Spark's two projections; the harness compares them.

| test | inputs | the failure only it catches |
| :--- | :--- | :--- |
| `<zone>`: CAST and try_cast of a TIMESTAMP_NTZ to TIMESTAMP equal the row engine across every window of a year, in time order and in no order, in each form, with ANSI on and off (one dynamic test a zone) | part c's nine zones and years, and `UTC`, `+01:00`, `Etc/GMT+5` over 2021; the fixture as one batch and as batches of 64 rows | a kernel that agrees with Spark only outside a window |
| a local batch is served exactly when it touches no more windows than its form has steps | `America/Los_Angeles` and `Africa/Casablanca`: for each form, the edges of `K` and of `K + 1` consecutive windows | a form that serves a batch it does not cover |
| a local time within 36 hours of an end of a long declines and the row engine answers | `America/Los_Angeles`: `Long.MIN_VALUE + 1` alone and `Long.MIN_VALUE + 129599999999` alone decline; `Long.MIN_VALUE + 129600000000` alone is answered. `Asia/Kathmandu`: `Long.MAX_VALUE - 1` and `Long.MAX_VALUE - 129599999999` decline; `Long.MAX_VALUE - 129600000000` is answered | a subtraction that wraps |
| both ends of Spark's range are answered | -62135596800000000 and 253402300799999999, each alone, `America/Los_Angeles` | a decline inside Spark's range |
| one row, all rows equal and a batch with no live row are served | three batches of the 2021 fixture's first value | a fill that declines a batch it need not |
| a filter on the cast is served by the filter evaluator | `CAST(ntz AS TIMESTAMP) < ts` over a table `rid INT, ntz TIMESTAMP_NTZ, ts TIMESTAMP` whose `ts` holds the same long as `ntz`, under `America/Los_Angeles` 2021 | arguments that only the projection's runner fills |
| what composes over the cast answers as Spark does, fused or not | `hour(CAST(ntz AS TIMESTAMP))`, `CAST(CAST(ntz AS TIMESTAMP) AS DATE)`, `ntz < ts`, `greatest(CAST(ntz AS TIMESTAMP), ts)`, over the table of the filter's test: answers only | a consumer that fuses over the cast and is wrong |

`VarkaTimestampZoneQuerySuite`, one: "a cached TIMESTAMP_NTZ column's cast runs on the
kernel, and a batch that touches three windows goes to the row engine alone": three cached
batches under `America/Los_Angeles`, the form pinned to `STEPS_2`, as the suite's test of
`hour` does: two inside 2021 and one from March 2021 to March 2022. `numVarkaBatches` is 2,
`numFallbackBatchesDeclined` is 1.

**Commands.**

    dev/varka_emit.sh "CAST(t AS TIMESTAMP)" --columns t:timestamp_ntz --zone America/Los_Angeles
    dev/varka_emit.sh "CAST(t AS TIMESTAMP)" --columns t:timestamp_ntz --zone UTC
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaZoneCompilerSuite *VarkaZonedDeclinesSuite *VarkaTimestampTwinSuite *VarkaCastMatrixSuite *VarkaCoverageSuite *VarkaFamilySwitchSuite *VarkaFamilyChainSuite *VarkaTimestampNtzConformanceSuite *VarkaWarmupReachSuite *VarkaWidthAuditSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaTimestampZoneSuite *VarkaTimestampZoneQuerySuite *VarkaCoverageHarnessSuite *VarkaCoverageDifferentialSuite *VarkaCastWithAnsiOffSuite *VarkaCastWithAnsiOnSuite *VarkaTryCastSuite *VarkaSparkFuzzSuite'
    python3 dev/varka_census.py --check
    python3 dev/varka_census.py --summary | diff target/census-before-690d.txt -
    git status --short
    python3 dev/varka_push_check.py

Expected: the first prints `FusedOutput(0)`, `zones: [America/Los_Angeles]` and an IR line
that holds `(toInstant:0:EARLIER col:0:long)`; the second an output IR that is `col:0:long`
and `zones: []`; the suites print `Failed 0, Errors 0`; the declarations are `n3 + 5` in
`VarkaZoneCompilerSuite` and `n4 + 7` in `VarkaTimestampZoneSuite` (5.0); the conformance
suite's test "the cast to
TIMESTAMP, in LEGACY, ANSI and TRY" passes and is no longer cancelled; the census check
exits 0; the summary's difference is the prediction of 4.14 for part d, or is copied into
the description with what differs; `git status --short` shows, beside the steps' files,
`sql/varka/coverage.json`, `emitted_bytes.json`, `width_audit.json`, `$M9/casts_fused.tsv`,
`$M9/casts.tsv`, `$M9/casts_suites.tsv`, `$M9/census.tsv` and `docs/sql-varka.md`.

**Planted faults.**

| fault | the test that must fail |
| :--- | :--- |
| `instantOfNtz` passes `Resolve.GAP_END` | "in a zone with changes it is a ToInstant under the earlier offset" |
| the fold adds `micros` in place of `-micros` | `+01:00` of the dynamic test |
| `toInstant` skips the served test | "the way back declines what the way there declines" |

**Done when** the cast fuses under each zone of the suite and equals Spark across every
window; the pair `ntz` to `ltz` reads fused in three modes; the faults fail as named.
**Not here:** the cast from `DATE`; the sum; the emitter, the lowering, `VarkaBatchArgs`.

### 5.5 Part e: `CAST(d AS TIMESTAMP)`

**B0.**

    git branch --list 'varka-690e-date-cast' | wc -l                                          # 0
    grep -c 'private static Optional<VarkaVectorIR> instantOfNtz(' $C/VarkaZoneCompiler.java   # 1: part d merged
    grep -c 'local instanceof ColumnRef' $C/VarkaZoneCompiler.java                            # 0: row 688's part c replaced the operand's test in toInstant too
    grep -c 'static boolean leastAndGreatest(' $V/IntRangeOps.java                            # 1: row 688's part d
    grep -c 'static VarkaVectorIR ntzMidnightDay(VarkaVectorIR day) {' $C/VarkaNodeCompiler.java   # 1
    grep -n 'ntzy' $TS/VarkaCoverageSuite.scala | head -3                                     # note: part d's column
    grep -P '^date\tltz\t' $M9/casts.tsv $M9/casts_fused.tsv                                  # note: not fused
    grep -c '@Test' $CJ/VarkaZoneCompilerSuite.java; grep -c '@Test' $XJ/VarkaTimestampZoneSuite.java   # note: n3, n4
    python3 dev/varka_census.py --summary > target/census-before-690e.txt; echo $?             # 0

If the third line prints 1, `toInstant` still holds the operand's test that row 688's part c
replaced in `toLocal`: first replace it in `toInstant` by the statements `toLocal` holds now,
with `local` for `instant`.

**Steps.**

1. `$C/VarkaZoneCompiler.java`: the claim of 4.10's table after part d's, calling

       private static Optional<VarkaVectorIR> instantOfDate(
           Cast c, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
         return VarkaNodeCompiler.compileNode(c.child(), inputs, literals, sink).flatMap(d -> {
           if (d.laneType() != LaneType.INT) {
             return sink.decline("a date operand that is not on the int lane", c);
           }
           VarkaVectorIR midnight =
               VarkaNodeCompiler.midnightOf(VarkaNodeCompiler.ntzMidnightDay(d), sink);
           return toInstant(c, c.timeZoneId(), midnight, Resolve.GAP_END, sink);
         });
       }

2. `$V/VarkaFamily.java`: `covers` gains "and of a DATE" after part d's clause.
3. The column `dy` and the two coverage rows of 4.14, as part d's step 3; the first row's
   note: "the day's midnight less the zone's offset; a midnight that the clocks skipped is
   the instant of the change, as Spark's daysToMicros has it".
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
   first instant in the zone."), and the regenerations, as part d's steps 4, 5 and 7.
5. The tests below.

**Tests.** `VarkaZoneCompilerSuite`, four: "under UTC the cast of a DATE to TIMESTAMP is the
tree of its cast to TIMESTAMP_NTZ" (the IR of `CAST(d AS TIMESTAMP)` under `UTC` equals the
IR of `CAST(d AS TIMESTAMP_NTZ)`); "under a fixed offset it is the midnight less the offset
under the 18-hour guard" (`+01:00`, `-08:00`: the tree of 4.10 over the midnight); "in a
zone with changes it is a ToInstant under the gap's end over the day's midnight"
(`America/Los_Angeles`: `new ToInstant(midnight, 0, GAP_END)` with `midnight` the
`IntArith` of `MUL` over `GuardedRange(WidenLane(col), -106751991, 106751991)`); "on a host
of two masked lanes the cast of a DATE declines by name in a zone with changes and fuses
under UTC" (a sink built with `new VarkaAdmission(Set.of(), true)`).

`VarkaTimestampZoneSuite`, six. A table is `VarkaHarnessCases.source("rid INT, d DATE",
rows)`, each row `new Object[] {i, day}` with `day` an `Integer`, or null at every seventh
row. The days of a year are `LocalDate.of(year, 1, 1).toEpochDay()` and the 364 after it.

| test | inputs | the failure only it catches |
| :--- | :--- | :--- |
| `<zone>`: CAST and try_cast of a DATE to TIMESTAMP equal the row engine on every day of a year, in order and in no order, in each form, with ANSI on and off | part c's nine zones and years and the three fixed zones; every day of the year, every seventh null; one batch and batches of 64. The expectation as part d's, with `node = new ToInstant(new ColumnRef(0, LaneType.LONG), 0, Resolve.GAP_END)` and `least` and `greatest` the batch's least and greatest live day times 86400000000 | the earlier offset at a midnight in a gap |
| a midnight inside a gap is the gap's end, and one that starts a gap or is repeated is the earlier offset's | `America/Toronto`: day -18539 answers -1601753400000000, and the suite asserts that this differs from -1601751600000000. `America/Havana`: days 18700 (the gap starts at midnight) and 18938 (midnight is repeated) | a rule that only the 1919 rows tell apart |
| the last day whose midnight is a long declines its batch at the low end and the row engine answers | day -106751991 alone: under `America/Los_Angeles` declined with `STATUS_BATCH_ARGS`, under `UTC` answered by the kernel | a wrap at the guard's edge |
| what composes over the cast answers as Spark does, fused or not | `d < ts`, `CAST(d AS TIMESTAMP) = ts`, `hour(CAST(d AS TIMESTAMP))` over a table `rid INT, d DATE, ts TIMESTAMP` whose `ts` is the day times 86400000000, `America/Los_Angeles` 2021: answers only | a comparison fused over a wrong midnight |
| both ends of Spark's range are answered | days -719162 (0001-01-01, before the calendar of 1582) and 2932896 (9999-12-31), each alone, `America/Los_Angeles`, in each form | a decline, or a wrong offset, outside the years a fixture holds |
| one row, all rows equal and a batch with no live row are served | day 18700 alone; day 18700 eight times; eight null rows; `America/Los_Angeles` | a fill that declines a batch it need not |

**Commands.** Part d's, with `"CAST(d AS TIMESTAMP)" --columns d:date` in the two emits,
`VarkaDateConformanceSuite` for the conformance suite, and `target/census-before-690e.txt`.
Expected: the first emit's IR holds `(toInstant:0:GAP_END `; the second's holds no
`toInstant`; `n3 + 4` and `n4 + 6` declarations; the pair `date` to `ltz` reads fused three times;
the summary's difference is 4.14's for part e; `git status --short` as part d's.

**Planted faults.**

| fault | the test that must fail |
| :--- | :--- |
| `instantOfDate` passes `Resolve.EARLIER` | "a midnight inside a gap is the gap's end", on day -18539 |
| the midnight built with no `ntzMidnightDay` | "in a zone with changes it is a ToInstant under the gap's end over the day's midnight" |
| the two-lane test dropped from `toInstant` | "on a host of two masked lanes the cast of a DATE declines by name" |

**Done when** the cast fuses and equals Spark on every day of each year, the 1919 day
included; the pair reads fused; the faults fail as named.
**Not here:** the sum; `DATE` plus an interval as a kernel of its own; any other cast.

### 5.6 Part f: `ts` plus a literal of whole days

**B0.**

    git branch --list 'varka-690f-plus-days' | wc -l                                          # 0
    grep -n 'private static Optional<VarkaVectorIR> zonedPlusInterval(' -A24 $C/VarkaZoneCompiler.java   # note: row 688's method, whole
    grep -c 'static Optional<VarkaVectorIR> toInstant(' $C/VarkaZoneCompiler.java             # 1: part d merged
    grep -c 'toInstantKept' $C/VarkaZoneCompiler.java                                         # 0
    git grep -n 'no literal of under a day' -- sql docs                                       # note: every holder of the old text
    grep -n 'TimestampAddInterval' $M9/census_curated.txt                                     # note: one line, with ltz^ among its fused types
    grep -c '@Test' $CJ/VarkaZoneCompilerSuite.java; grep -c '@Test' $XJ/VarkaTimestampZoneSuite.java   # note: n3, n4
    grep -n "INTERVAL '1' DAY\|INTERVAL '1' HOUR" $XJ/benchmark/Varka*BaselineBenchmark.java   # note: row 655's sections of the sum

**Steps.**

1. `$C/VarkaZoneCompiler.java`: `toInstantKept` (4.10) after `toInstant`. In
   `zonedPlusInterval`, the test of the literal becomes: unless `a.interval()` is a `Literal`
   whose value is a non-null `Long v`, `return sink.decline(ZONED_INTERVAL_REASON, a)`. After
   the start is compiled to `instant` and its lane tested, as the method does today:

       long days = v / DateTimeConstants.MICROS_PER_DAY;
       long rest = v - days * DateTimeConstants.MICROS_PER_DAY;
       if (days == 0) {
         return Optional.of(new IntArith(IntOp.ADD, Overflow.FAIL, instant, sink.longSlot(v)));
       }
       Optional<VarkaVectorIR> local = toLocal(a, a.timeZoneId(), instant, sink);
       if (local.isEmpty()) {
         return local;
       }
       if (local.get() == instant) {
         return Optional.of(new IntArith(IntOp.ADD, Overflow.FAIL, instant, sink.longSlot(v)));
       }
       VarkaVectorIR moved = new IntArith(IntOp.ADD, Overflow.FAIL, local.get(),
           sink.longSlot(days * DateTimeConstants.MICROS_PER_DAY));
       VarkaVectorIR kept = new IntArith(IntOp.SUB, Overflow.WRAP, local.get(), instant);
       return toInstantKept(a, a.timeZoneId(), moved, kept, sink).map(back -> rest == 0
           ? back : new IntArith(IntOp.ADD, Overflow.FAIL, back, sink.longSlot(rest)));

   The method's comment gains: "A literal of whole days moves the local time and comes back
   by the kept offset, as `DateTimeUtils.timestampAddDayTime` does with `plusDays`; the rest
   of the literal is added to the instant." `ZONED_INTERVAL_REASON` takes the text of 4.11;
   every holder B0 printed takes it too.
2. `$V/VarkaFamily.java`: `covers` gains "a TIMESTAMP plus a literal day-time interval".
3. The two coverage rows of 4.14, the first with the note "the local time moved by whole
   days and resolved with the offset the value had, as ZonedDateTime.plusDays does; the rest
   of the literal is added to the instant".
4. The census: the script of 4.14, then `python3 dev/varka_census.py`.
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
   days are calendar days in the zone, and a result in a repeated hour keeps the offset the
   value had."), and `dev/varka_regen_generated.sh --all`.
6. The tests below. Row 688's test "a literal of a day, of minus two days, a null literal
   and an interval column decline with `ZONED_INTERVAL_REASON`" keeps its last two cases;
   its first two move to the first test below.

**Tests.** `VarkaZoneCompilerSuite`, five. `ts` is the column; `D` is 86400000000.

| test | inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- |
| a TIMESTAMP plus a literal of whole days is the kept-offset tree over one local time | `ts + INTERVAL '1' DAY`, `ts + INTERVAL '-2' DAY` under `America/Los_Angeles` | `new ToInstantKept(new IntArith(ADD, FAIL, tl, lit(n * D)), new IntArith(SUB, WRAP, tl, col), 0)` with `tl = new ToLocal(col, 0)` one object in both places, `n` 1 and -2 | a kept offset read after the days are added |
| the rest of the literal is added to the instant, with the literal's sign | `ts + INTERVAL '1 02:30:00' DAY TO SECOND`, `ts + INTERVAL '-1 02:30:00' DAY TO SECOND` | the tree above under `new IntArith(ADD, FAIL, back, lit(r))`, with `n * D` and `r` of `D` and 9000000000, then `-D` and -9000000000 | the rest added to the local time |
| under UTC and under a day the sum is one checked add | `ts + INTERVAL '1' DAY` under `UTC`; `ts + INTERVAL '23:59:59' HOUR TO SECOND` under `America/Los_Angeles` | `new IntArith(ADD, FAIL, col, lit(v))`, no zone | a zone node where no window can matter |
| under a fixed offset it is the two folds around the days | `ts + INTERVAL '1' DAY` under `+01:00` | `toLocal`'s fold, the sum of `D`, `toInstant`'s fold; no zone node | a fold that keeps a `ToInstantKept` |
| a null literal, an interval column and, on a host of two masked lanes, a literal of whole days decline by name | `ts + CAST(NULL AS INTERVAL DAY)`, `ts + dt`; `ts + INTERVAL '1' DAY` with `new VarkaAdmission(Set.of(), true)` | `ZONED_INTERVAL_REASON` twice; `TWO_LANE_LOCAL_RULE_REASON`; and on that host a literal under a day still fuses | a decline text that no longer says what declines |

`VarkaTimestampZoneSuite`, seven. A table is `VarkaHarnessCases.source("rid INT, ts
TIMESTAMP", rows)`, each row `new Object[] {i, instant}` with `instant` a `Long`, or null at
every seventh row. **The fixture of the sum,** with `D` 86400000000: for each change of
`VarkaLocalLineKernels.changesOf(zone, year)`, with `T` its `toEpochSecond() * 1000000`, and
for each of the five centres `T`, `T - D`, `T + D`, `T - 7 * D` and `T + 7 * D`: the centre
plus -7200000000, -3600000000, -1800000000, -1000000, -1, 0, 1, 1000000, 1800000000,
3600000000 and 7200000000; and one instant every six hours of the year, 1460 from
`LocalDate.of(year, 1, 1).toEpochDay() * D`. Sorted ascending, and also in the order `(i *
7919) % n`.
The expectation of a batch under a literal of `n` days, over its live rows' `least` and
`greatest`, with `P = VarkaChronoZone.of(id)`, `node = new ToInstantKept(col, col, 0)` for
`col = new ColumnRef(0, LaneType.LONG)`, and `K` the form's steps: answered when the
zone is fixed, or when both `P.changes(least, greatest) <= K` and
`VarkaBatchArgs.dataOf(node, P).changes(least - 64800000000 + n * D, greatest +
64800000000 + n * D) <= K`; else declined with `STATUS_BATCH_ARGS`.

| test | inputs | the failure only it catches |
| :--- | :--- | :--- |
| `<zone>`: a TIMESTAMP plus and minus a literal of days equals the row engine across every change of a year, in each form | part c's nine zones and years and the three fixed zones over 2021; `ts + INTERVAL '1' DAY`, `ts + INTERVAL '-1' DAY`, `ts + INTERVAL '7' DAY`, `ts + INTERVAL '-7' DAY`, `ts + INTERVAL '1 02:30:00' DAY TO SECOND`, `ts + INTERVAL '-1 02:30:00' DAY TO SECOND`, `ts - INTERVAL '1' DAY`; in time order and in no order; one batch and batches of 64 | a calendar day added as 86400 seconds |
| a day added before a repeated hour keeps the earlier offset, a day taken after it keeps the later one, and a day into a skipped hour moves later | `America/Los_Angeles`, three rows: 1636187400000000 plus a day is 1636273800000000; 1636363800000000 minus a day is 1636277400000000; 1615631400000000 plus a day is 1615717800000000. The suite asserts the three literals and the harness holds them to Spark | the kept offset ignored, which every other row of the year agrees with |
| under the one-offset form a week with no change is served | `ts + INTERVAL '1' DAY`, `zoneForm=ONE`, eight rows of June 2021 | the kept operand left unemitted, so that a shared node is read before it is stored |
| near an end of a long the sum's batch declines and the row engine answers | `ts + INTERVAL '1' DAY`. `America/Los_Angeles`: `Long.MIN_VALUE + 1` alone declines with `STATUS_BATCH_ARGS` (the first conversion's range is within 18 hours of the end). `Asia/Kathmandu`: `Long.MAX_VALUE - 172800000000` alone declines with `STATUS_BATCH_ARGS` (the moved local time's range is). The row engine answers both: neither sum leaves a long | a conversion or a checked sum that wraps |
| three readers of one local time are one kernel | `hour(ts)`, `ts + INTERVAL '1' DAY` and `CAST(ts AS DATE)` in one projection | a local time computed once and read wrongly |
| what composes over the sum answers as Spark does, fused or not | `d + INTERVAL '1 02:00:00' DAY TO SECOND` over `(rid, d)`, `hour(ts + INTERVAL '1' DAY)`: answers only | a zone node over a zone node with a wrong range |
| one row, all rows equal and a batch with no live row are served | `ts + INTERVAL '1' DAY`; 1623153600000000 alone; the same eight times; eight null rows; `America/Los_Angeles` | a fill that declines a batch it need not |

`VarkaTimestampZoneQuerySuite`, one: "the verbose EXPLAIN names the two-lane decline of a
sum of days, and under UTC the sum fuses there": with
`spark.sql.codegen.varka.declineTwoLaneConditions=true` on both sessions, `ts + INTERVAL '1'
DAY` is `residual` with `TWO_LANE_LOCAL_RULE_REASON` under `America/Los_Angeles` and
`fused` under `UTC`.

**Commands.** Part d's, with `"ts + INTERVAL '1' DAY" --columns ts:timestamp` in the two
emits, no conformance suite, and `target/census-before-690f.txt`. Expected: the first
emit's IR holds `(toInstantKept:0 `; the second's is `(int:ADD:FAIL col:0:long lit:0:long)`;
`n3 + 5` and `n4 + 7` declarations; the census check exits 0 and the summary does not differ; `git
status --short` shows, beside the steps' files, `sql/varka/coverage.json`,
`emitted_bytes.json`, `width_audit.json`, `$M9/census_curated.txt`, `$M9/census.tsv` and
`docs/sql-varka.md`.

**Planted faults.**

| fault | the test that must fail |
| :--- | :--- |
| `kept` built as `moved - instant` | "a day added before a repeated hour keeps the earlier offset", its second row |
| `rest` added to `moved` | "the rest of the literal is added to the instant"; and the dynamic test on `America/Los_Angeles` |
| `toInstantKept` builds a `ToInstant` under `EARLIER` | the second row of the three-row test |
| `days` computed by `Math.floorDiv` | "the rest of the literal is added to the instant, with the literal's sign", on the negative literal |

**Done when** the sum fuses for a literal of whole days in both signs and equals Spark
across every change; the three literal rows hold; the faults fail as named.
**Not here:** an interval column; months; `ts - ts`; `timestampadd`; lifting a two-lane
decline.

### 5.7 Part g: the sweep under three rules, the planted faults, there and back

**B0.**

    git branch --list 'varka-690g-sweep' | wc -l                                              # 0
    grep -c 'class VarkaZoneKernels' $TJ/VarkaZoneKernels.java                                # 1: row 684's part 1
    grep -n 'interface Rule' -A8 $TJ/VarkaZoneKernels.java                                    # note: the five methods of row 684's 4.1
    grep -n 'record Kernel(' -A2 $TJ/VarkaZoneKernels.java                                    # note: its components kernel and programs
    grep -n 'boolean fill(Kernel kernel, String zoneId, long least, long greatest, long\[\] longArgs);' $TJ/VarkaZoneKernels.java   # one line: Filler
    grep -c 'public static long\[\]\[\] runLoaded(' $TJ/VarkaLocalLineKernels.java               # 1: part a
    grep -c 'PRODUCTION = \|static Kernel of(' $TJ/VarkaZoneKernels.java                       # at least 2: row 684's part 1
    grep -n 'points(\|expected(\|numInputs\|, 1, 0' $TJ/VarkaZoneKernels.java                 # note: every place a rule's points, answer and input count are read
    grep -n 'static .*production(' -A12 $TJ/VarkaZoneKernels.java                             # note: the one place that calls the fill
    grep -c '@Test' $TJ/VarkaZoneSweepSuite.java; grep -c '@Test' $TJ/VarkaZonePlantedFaultSuite.java   # 11 and 6
    ls $TJ/VarkaZoneFaults.java | wc -l                                                       # 1: row 684's part 2
    grep -c 'static void emitToInstantKept(' $V/VarkaZoneLowering.java                        # 1
    grep -c 'ZONE_LOCAL_STEPS_REASON' $V/VarkaKernelPlan.java                                 # 0: part c merged

**Steps.**

1. `$TJ/VarkaZoneKernels.java`: the four default methods of `Rule` (4.12: `inputs`, `second`,
   the three-argument `expected`, `oneOutsideCutsOnly`); where the harness cuts a change's
   points at `cuts` under `ONE`, the rule of 4.12 for a rule that answers true; at each place B0
   printed, the second column beside the first, the rule's `inputs()` for the input count,
   and the three-argument `expected`; a `Mismatch` keeps the first operand. `production`
   wraps the zone data in `VarkaBatchArgs.dataOf(program.node(), ...)`. The five rules, whose
   points read `VarkaLocalLineKernels.lo(change)` and `hi(change)`. A public static
   `leastAndGreatest(long[] values, boolean[] nulls)` returning a `long[2]`, for test 17.
   **Test 17 emits no class a call.** It takes two harness kernels, built once a form by
   `Kernel.of` as the sweep's are: `TO_LOCAL`'s in `STEPS_8`, and `KEPT_AFTER`'s (its root
   is the two-input `ToInstantKept`) in `STEPS_8` and in `STEPS_2`. For a run it fills each
   kernel's arguments by `PRODUCTION.fill(kernel, zoneId, least, greatest, args)`, which must
   answer true, and runs it by `VarkaLocalLineKernels.runLoaded(kernel.kernel(), args,
   columns, nulls)`, which returns the rows: the first with the column `x`, the second with
   the columns `L` and `L - x`. No row is null.
   **Test 18 runs no kernel.** It calls `PRODUCTION.fill(kernel, zoneId, least, greatest,
   args)` directly and reads its boolean, as row 684's test 7 does.
2. `$TJ/VarkaZoneSweepSuite.java`: tests 12 to 18 of 4.12, under the names written there.
   The suite's printed line gains `, <n> local rows, <s> s for the rules`.
3. `$TJ/VarkaZoneFaults.java`, `$TJ/VarkaZonePlantedFaultSuite.java`: faults 6 to 9 of 4.12,
   one test each: "a local threshold with min for max fails at the window's start"; "the
   kept offset ignored fails the rows that keep the later offset, and no others"; "the
   gap's end not taken fails inside a gap, and nowhere else"; "the instant line's arguments
   for a local node fail the sweep". Row 684's fault 5 (another zone's rules) gains a run
   under `TO_INSTANT_EARLIER`.
4. Plant by hand and revert: add 1 to the answer of `TO_INSTANT_EARLIER.expected`. Tests 12
   and 15 must fail; test 16 and test 17, which read no oracle, must pass.

**Tests.** The rows of 4.12. What each case reads: the zone ids of row 684's sweep (every
id of the JVM, `ZoneId.SHORT_IDS` and its sampled offsets), each set of rules once; the
changes before 2100 for the one-second sweeps; the changes to 9999 for the edges.

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaZoneSweepSuite *VarkaZonePlantedFaultSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'catalyst/testOnly *VarkaZoneSweepSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("catalyst") / Test / javaOptions ++= Seq("-XX:UseAVX=2", "-XX:MaxVectorSize=16")' 'catalyst/testOnly *VarkaZoneSweepSuite'
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json
    python3 dev/varka_push_check.py

Expected: `Passed: Total 28, Failed 0, Errors 0, Passed 28` for the first (18 and 10); 18
in the two runs of the sweep alone; the diff exits 0; the push check exits 0. The Outcome
takes the printed line and the three times.

**Test 17's work.** No oracle call. At most 65794 changes in runs of eight are 8,225 runs,
each three kernel passes over its rows (`ToLocal` once, the kept kernel in two forms, the
`STEPS_2` pass as four calls): about 49,000 kernel calls over at most 947 million rows a
pass. At under a nanosecond a row for the three passes (`results/kernels.txt`: 0.351 for the
kept offset at four lanes) and about as much again to copy the rows into the buffers, it is
a few seconds of one core; prediction 10 of section 7 holds it inside the sweep's budget.

**The time rule.** If tests 12 to 14 together take over 30 s in the first run on the
laptop with no other load, the one-second sweep of tests 13 and 14 keeps one change in
four of each set of rules (those whose index in the set is a multiple of four) and the
nightly's step of row 684 runs all of them under `-Dvarka.zone.dense=true`; the edges stay
whole. The builder applies it and reports both times.

**Done when** the commands print the above, each of faults 6 to 9 fails its named test,
and the hand plant fails tests 12 and 15 alone.
**Not here:** a production file; a fuzzer; a step with no mask.

### 5.8 Part h: the IR fuzzer's draws and the property suite

**B0.**

    git branch --list 'varka-690h-fuzz' | wc -l                                               # 0
    grep -n 'drawZoneShape\|ZONE_FUZZ_SEED\|new ToLocal(' $TJ/VarkaIrGrammar.java             # note: row 684's production
    grep -n 'ToInstant' $TJ/VarkaIrGrammar.java $TS/VarkaIrFuzzSuite.scala $TS/VarkaWithChildrenSuite.scala   # note: the throwing arm and the list entries of parts a and b
    grep -n 'ToLocal' $TS/VarkaIrFuzzSuite.scala                                              # note: the zone sequence's fill and its reach tests
    grep -c '@Test' $TJ/VarkaZoneArgsPropertySuite.java                                       # 3
    grep -c 'TO_INSTANT_EARLIER' $TJ/VarkaZoneKernels.java                                    # at least 1: part g merged

**Steps.**

1. `$TJ/VarkaIrGrammar.java`: the draw of 4.13 in row 684's production: where it draws a
   coin of three for a column leaf, it draws one of six. `boundsOf`'s two throwing arms
   become the bound of 4.13.
2. `$TS/VarkaIrFuzzSuite.scala`: the zone sequence's fill takes `dataOf`; the two names
   leave `missing` and `outOfTheLongCorpora` as `ToLocal` left them; one test more, "the zone
   sequence draws each local-time record under arithmetic and under a comparison, and a
   third of its local draws are answered": over the sequence's default count of draws, each
   of `ToInstant` under each rule and `ToInstantKept` appears under an `IntArith` and under
   a comparison at least once, and over a third of the draws that hold one are answered.
   `$TS/VarkaWithChildrenSuite.scala`: the names leave `undrawn`.
3. `$TJ/VarkaZoneArgsPropertySuite.java`: the test of 4.13. Case `k` of seed `s` draws, from
   `new Random(s * 1000003L + k + 690)`: a zone id and a form as test 1 does; a rule among
   the five of part g; a local range whose centre is `lo` of one of the zone's windows to
   9999 (three times in four) or any long inside the clamp, with a half width of `1L <<
   nextInt(62)`, clamped to `Long.MIN_VALUE + 129600000000` and `Long.MAX_VALUE -
   129600000000`; rows that are, by quarters, a threshold in the range, a threshold plus or
   minus one microsecond, a threshold plus or minus up to two hours, and uniform. The
   oracle is the rule's `expected`; the count of windows is by `ZoneRules`, stopped at the
   form's steps plus one. 20000 cases by default, as the suite's own.

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaIrFuzzSuite *VarkaWithChildrenSuite *VarkaZoneArgsPropertySuite *VarkaTestReachSuite *VarkaFuzzTypesSuite'
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/fuzz
    python3 dev/varka_push_check.py

Expected: no failure; `VarkaZoneArgsPropertySuite` runs 4 tests; no committed corpus and no
generated file moves; the push check exits 0.

**Planted faults.**

| fault | the suite and test that must fail |
| :--- | :--- |
| the draw of six gives the bare column where it should give a `ToInstantKept` | `VarkaIrFuzzSuite`, "the zone sequence draws each local-time record under arithmetic and under a comparison, and a third of its local draws are answered" |
| the zone sequence's fill takes the provider's answer with no `dataOf` | `VarkaIrFuzzSuite`, row 684's comparison test of the zone sequence (the test B0's fourth line prints the fill in): a kernel row differs from the reference on the first case whose range touches a window |
| `LocalLine.changes` answers `zone.changes`, the instant line's count | `VarkaZoneArgsPropertySuite`, "a random zone, rule and local range: the fill answers exactly when the form covers the windows the range touches, and its arguments give java.time's instant on every row" |

**Done when** the commands print the above. **Not here:** the Spark fuzzer's grammar; a
committed corpus.

### 5.9 Part i: the measurement and the close

The lead dispatches; a builder commits what comes back.

**B0.**

    git branch --list 'varka-690i-results' | wc -l                                            # 0
    grep -ln 'AS TIMESTAMP)' $XJ/benchmark/Varka*BaselineBenchmark.java                       # note: the classes that hold a section of either cast
    grep -ln "+ INTERVAL '1' DAY" $XJ/benchmark/Varka*BaselineBenchmark.java                  # note: the classes that hold a section of the sum
    grep -c 'toInstantKept' $C/VarkaZoneCompiler.java                                         # at least 1: part f merged

`<Class>` below is the base name of each file the two notes printed that reads a `TIMESTAMP`
column: row 655's classes. A class that B0 does not print is not dispatched.

1. For each `<Class>`, on the pull request's head, at 256 and at 128 bits:

       gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> -f create-commit=false -f class=<Class> -f jdk=25 -f expected-cpu="AMD EPYC 7763"
       gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> -f create-commit=false -f class=<Class> -f jdk=25 -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-XX:MaxVectorSize=16

   Without `-f pr=<n>` the workflow measures the default branch. A run on another CPU is
   dispatched again.
2. Before the commit: `python3 dev/varka_bench_diff.py --git HEAD <file>` for each results
   file, controls first, and again with `--requote`, which must list nothing.
3. Score the predictions of section 7 in section 11. A section slower than the row path at
   128 bits is a finding for row 691, named with its number. A section of the three that no
   class holds is not measured here: section 11 lists it by its SQL, the prediction that
   reads it is scored "not measured: no baseline section", and row 691, whose done-when
   reads row 655's classes, owes it.
4. Row 690's line in `$M9/PLAN.md` reads `**Done**`; `sql/varka/SKILLS.md` gains one entry:
   what the row learned of the local line (the window and its two thresholds, the view, why
   the kept offset is the local time less the instant).

**Done when** the results of every class B0 printed are committed at both widths, and each
prediction is scored or marked as step 3 says. With no class printed, the part commits the
scores and the close alone.
**Not here:** a default that changes; an A/B arm of the forms (row 691); a new benchmark
class.

## 6. Tests, proofs, fuzzing and coverage

Every suite runs at both vector widths through the gate, under each option configuration
through the matrix, and with the memory sanitizer on.

| suite | language, module | what it holds for this row | the failure only it catches |
| :--- | :--- | :--- | :--- |
| the registry suites of fact 15, `VarkaNodePropsSuite`, `VarkaVectorIRSharingSuite` | existing, catalyst | both records in every registry | a pass that meets a record it has no answer for |
| `VarkaLocalLineSuite` | Java, JUnit 5, catalyst | the records, the counts, the view, the one-offset kernels, the reference against `java.time`'s public API | a local program filled or sized as an instant's |
| `VarkaEmitterZoneSuite` | Java, catalyst (row 682's) | each rule in each form across every window of nine zones' years; hand-set thresholds, two touching windows among them | a step whose lowering differs from the scalar meaning |
| `VarkaBatchArgRunnerSuite` | Scala, sql/core (row 682's) | the seam: a local program filled on the local line at run time | arguments of the instant line under a local node, which no fallback catches |
| `VarkaZoneCompilerSuite` | Java, catalyst (row 657's) | the folds, the three trees, the declines by name, the two-lane host | a wrong tree for a zone class, which an answer test of one zone would miss |
| `VarkaTimestampZoneSuite` | Java, sql/core, no session (row 657's) | each kernel against Spark's two projections; rows that straddle both changes of a year; `K` and `K + 1` windows; the ends | a kernel that agrees with Spark only outside a window |
| `VarkaTimestampZoneQuerySuite` | Scala, sql/core (row 657's) | the metrics of a declined batch; the two-lane text in `EXPLAIN` | a path only a session has |
| `VarkaZoneSweepSuite`, `VarkaZonePlantedFaultSuite`, `VarkaZoneArgsPropertySuite` | Java, catalyst (row 684's) | the three rules in every zone of the JVM; four planted faults; there and back; random local ranges | a zone whose local windows differ from `java.time`'s; a check that cannot fail |
| `VarkaIrFuzzSuite` through `VarkaIrGrammar` | existing | the records under random parents in the zone sequence | a record that is wrong under a parent no test writes |
| `VarkaCastMatrixSuite`, the `DATE` and `TIMESTAMP_NTZ` conformance suites, Spark's three cast suites under Varka, the coverage suites | existing | the two pairs are fused as the registers say, and Spark's own tests of them pass through the kernel | a register that says fused where the compiler declines |

**The tests whose rows straddle both changes of a year** (the row's done-when): the dynamic
test of each of parts d, e and f in `VarkaTimestampZoneSuite`, over `America/Los_Angeles`
2021 among nine zones and years, with rows inside, at the edges of and either side of the gap of 14
March and the overlap of 7 November.

**Proofs.** No proof file of this row's own. The masked steps hold one bounded sum, a local
time less an offset, which is `zone_step.smt2`'s check 10 (row 684) with the offset negated:
an offset's negation is within the same 18 hours. The span rule needs no proof for the
masked steps; its proof file comes with the steps it guards, in row 689 (appendix A). That
a kernel equals `java.time` in every zone is no SMT claim: the sweep holds it.

**Fuzzing.** Part h for the IR fuzzer and the property suite. The Spark fuzzer reaches the
three kernels through the coverage rows of parts d to f, from row 684's zone compositions.

**Coverage and mutation.** After parts c and f the lead runs `dev/varka_gen_coverage.sh`
and reports the branches of `VarkaZoneLowering`, `VarkaBatchArgs` and `VarkaZoneCompiler`
from `sql/varka/coverage/emitter.md` in the pull request: every branch reached, or the test
that reaches it added. `dev/varka_pit.sh` mutates `codegen.varka` against ScalaTest suites
only, so it reaches this row through `VarkaIrFuzzSuite` after part h; the planted faults of
each sheet stand in for it before.

**What runs only in CI.** The option matrix (`zoneForm`'s arms, `isa=avx2`, `isa=w128`,
`isa=arm`), the width audit and both solvers over every proof file (the nightly). Locally a
builder runs each sheet's commands, of which part g's third runs at two lanes under AVX2.

**Test time,** read against `python3 dev/varka_calibration.py`: section 7, predictions 8 to
10.

## 7. Measurement

**The sections** are row 655's: `CAST(ntz AS TIMESTAMP)`, `CAST(d AS TIMESTAMP)` and `ts +
INTERVAL '1' DAY`; under UTC, `+01:00` and `America/Los_Angeles`; over a week, a year in
time order, a year in no order and ten years in no order; with no nulls and with one row in
ten null. The control of each is its `TIMESTAMP_NTZ` twin (`CAST(d AS TIMESTAMP_NTZ)`, `ntz +
INTERVAL '1' DAY`; the cast from `TIMESTAMP_NTZ` has none and is read against its row
path). The numbers are the AMD EPYC 7763's at 256 and at 128 bits; the laptop adds 512
where the owner asks. No A/B arm here: the forms are row 691's, and `zoneStepMask` rows 689's
and 691's.

**Predictions, registered on 27 November 2026, before any run.** Parts name the pin or the
file that scores each. A ratio under 1.3 is run again and read by minimums.

1. `CAST(ntz AS TIMESTAMP)` under `America/Los_Angeles`, a year in no order, at 256 bits on
   the EPYC 7763: at least fifteen times the row path's rate (`VARKA-657.md` 6.1, prediction
   3, kept). Part i.
2. `CAST(d AS TIMESTAMP)`, the same section: at least eight times the row path. Part i.
3. `ts + INTERVAL '1' DAY`, the same section: at least eight times the row path. The kernel
   holds two conversions and two checked sums. Part i.
4. Ten years in no order under `America/Los_Angeles`: every batch of the three sections
   declines and the time is within 1.05 of the row path's, until row 693. Part i.
5. Under UTC each of the three costs what its `TIMESTAMP_NTZ` twin costs, within 1.1; the
   cast from `TIMESTAMP_NTZ` under UTC costs what a projection of the column costs. Part i.
6. At 128 bits `CAST(ntz AS TIMESTAMP)` is faster than the row path: yes. The other two
   decline there by name, so their sections tie the row path within 1.05. Part i.
7. No existing shape's bytes move in parts a, b, c, g and h: `emitted_bytes.json`,
   `coverage.json` and `width_audit.json` are identical: yes. Each part's `git status`.
8. Lane calls of the masked steps over the load and store: 5 and 17, 11 and 41, 9 and 33;
   mask calls 0, 2 and 8, 4 and 16. Part c's pins.
9. Test time. `VarkaLocalLineSuite` under 5 s (fifteen tests, each one small kernel; a JUnit
   class of VARKA-328 took 0.15 s and the emitter tests of VARKA-460 2.0 s for five). Part
   c's seven tests add under 20 s to `VarkaEmitterZoneSuite`: nine zones, about 1,500 rows
   each, three rules, three forms, two orders: under a million reference calls at about 43
   ns, and about 150 kernels emitted. The tests of parts d, e and f add under 15 s each to
   `VarkaTimestampZoneSuite`: about 130 harness checks a part, against the 350 that
   `VARKA-657-PARTS.md` 7 priced at 8 s and doubled.
10. The sweep's tests 12 to 18 add under 30 s to a Build's bin on a runner's four cores, and
    under 30 s on the laptop (part g's time rule). The arithmetic, for one core, from
    `results/facts.txt` (65794 changes over 604 ids, at least 349 sets of rules) and
    `results/kernels.txt` (43.163 and 44.484 ns for the two oracle calls): test 12 reads
    about 65794 x 14400 x 349 / 604 = 547 million local times, 24 s; tests 13 and 14 read a
    window and a minute either side, about 3,720 points for an hour's window, the kept
    offset three times: 65794 x 3720 x 4 x 349 / 604 = 566 million, 25 s. So about 50 s of
    one core, and a runner's four cores take about 0.56 of a one-core price
    (`VARKA-684.md` 4.5). `VARKA-657.md` 6.1, prediction 9, registered under 60 s for the
    three rules.
11. Nine pull requests, none over 1000 counted lines, each within half of its estimate.

## 8. Who builds it

`m9/PLAN.md` 1.4 has rows 656, 657 and 682 to 692 built at full strength, and so is every
part of this row:

| part | builder | why |
| :--- | :--- | :--- |
| a, b | full strength | thirty files and every registry; a slip is a red master |
| c | full strength | three lowerings in bytecode; a wrong step is a wrong answer in a window only |
| d, e, f | full strength | the compiler's trees decide which rule a query gets; part f derives the kept offset |
| g, h | full strength | the proof must be able to fail: the harness's second input and the faults |
| i | the lead | dispatches and results |

No part moves goal G5. A cheaper model takes a part only on the owner's word, recorded with
its date; parts d and e are then the nearest to a fan-out task.

## 9. Risks

1. **A local program filled from the instant line** is a wrong answer near a change that no
   fallback catches. One view (`dataOf`) at one seam; part b's runner test and its tests 13
   and 15, and fault 9 of the sweep, hold it.
2. **The wrong rule for an expression.** Each rule differs from the others only inside a
   window. The trees are pinned in `VarkaZoneCompilerSuite`; `America/Toronto` 1919 tells
   the gap's end from the earlier offset, and the three literal rows of part f tell the kept
   offset from it.
3. **The seam moved, or a later row leaves it bare.** Rows 685 and 688 move where a program
   takes its zone data. If they are built first, part b's B0 prints every such place and its
   runner test fails on one that is missed. If they are built after, their own plans now say
   to wrap the value (section 13, edits 7 to 9), and the runner test of part b fails their
   Build on a bare one.
4. **Row 688 merges between part d and part e** and does not see `toInstant`. Section 13,
   edit 2, tells its builder; part e's B0 checks, and its first step repairs it if needed.
5. **Row 686 merges before or after parts a and b.** The table refusal and the ladder's
   stop are added by whichever row builds second (4.9; section 13, edit 3). **Row 689**
   may merge at any time: this row reads nothing of it, and the masked steps run under both
   values of its option (4.8).
6. **A call shape runs cold.** The warm-up runs each shape of 4.6 today (fact 13).
   `VarkaWarmupReachSuite` is in the commands of parts d to f and names one that is left.
7. **The sweep's time.** Prediction 10 and part g's time rule.
8. **The harness of row 684 is not built.** Part g's sheet states what it needs of `Rule`
   and the places it edits; its B0 prints them. A harness whose shape allows no second
   input column is a stop.
9. **The census deltas of 4.14 are derived, not printed.** Each part prints the summary's
   difference, and `python3 dev/varka_census.py --check` is the oracle.
10. **A window that touches the next** (`hi[i] == lo[i + 1]`): row 678's check C6 allows it.
    The single pass of 4.6 equals the scalar meaning there, since each window's compares
    read the local time and not the running answer. Part c's test 5 holds two touching
    windows with their expected values.
11. **Nine parts where the family plan budgeted five.** Section 13, edit 5; the owner
    approved the count.
12. **The plan ages.** Rows 653, 646 and 654 move shared lists before this row starts. B0
    prints each list's home.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md` and `m8/BUILD_SHEETS.md`, the rules a builder of this row is most
likely to break:

- A switch over a sealed type has no `default`. Name each record in its arm, never `Zoned`.
- "**The Varka code carries no `TODO` or `FIXME` comments, and should not start.**" The
  refusal of part a declines by name and carries no marker; part c deletes it.
- "**A comment describes the code as it is.**" No "used to", "the old" or "since VARKA-690".
- "No allocation, streams, lambdas or boxing on a path that runs per batch or per row". The
  view is built once a runner; its methods are plain calls.
- "A new catalyst suite whose helpers are Java is a JUnit 5 class under `@VarkaSuite`", each
  `@Test` with its sentence as a `@DisplayName`. A `sql/core` session suite stays ScalaTest.
- "A test starts from `VarkaMatrix.base`, not `VarkaEmitOptions.DEFAULTS`".
- "**A new bounded lowering comes with its proof file**": the masked steps add no bound of
  their own (section 6); the steps with no mask and their file are row 689's.
- "The ghost fallback is a correctness contract ... Anything that can return a *wrong*
  answer rather than fail ... gets its own differential coverage."
- "**A pull request stays under 1000 changed lines** of hand-written files."
- "**Review your own diff before opening the pull request**" with `/code-review high`.
- "**Never kill by name pattern**", "**Never `git stash`**", "**Write only inside your own
  worktree and scratch area.**"

Do not: edit a generated file by hand; read OpenJDK's `java.time` sources (section 3 states
what the row rests on, and a test holds each); assert that `localChanges` equals the
reference's (`VARKA-678.md` 9, risk 5); use `VarkaJavaTimeZoneData` as an oracle for a local
node; add an emit option or change a default; lift a two-lane decline; add a coverage row
this plan does not name; time anything on the shared laptop; print the environment; open
`/home/max/proj/runtime-master`; push, open a pull request or dispatch a workflow.

## 11. Outcome

*To be written when the row closes: the nine pull requests, the sweep's printed line, the
census differences of parts d and e, and each prediction of section 7 scored.*

## 12. Seams with the plans written beside this one

Plans for rows 653, 655 and 660 are written beside this one and could not be read.

| row | the seam this plan assumes | where it is checked |
| :--- | :--- | :--- |
| 655 (the baselines) | its casts class holds sections of `CAST(ntz AS TIMESTAMP)` and `CAST(d AS TIMESTAMP)`, and its arithmetic class a section of `ts + INTERVAL '1' DAY`, each over a `TIMESTAMP` under UTC, a fixed offset and a daylight-saving zone, with a fuse check as row 377's classes have (`Fusion.WHOLE`, a `BatchCheck`) | B0 of parts d, e, f and i prints the sections; with none, a part lists none and says so, part i marks the prediction as not measured, and row 691 owes the section |
| 653 (the shared files) | it may move the coverage rows or the curated census to one file an entry. The content of a step is the same and its home is the one B0 prints | B0 of parts d to f |
| 660 (how precisions 7 to 9 enter) | nothing: this row reads the microsecond types alone. A nanosecond cast takes these conversions on its microsecond word in rows 673 to 675 | - |

**Rows with no plan yet.** Row 689 takes the steps with no mask of this row's records
(section 13, edit 6; appendix A). Rows 693, 669, 670, 671 and 691 read this row's names;
section 1 lists what each takes.

**Rows whose merged plans this row's seam changes.** Rows 682, 685 and 688 each say where a
program takes its zone data. From part b on that value is wrapped by
`VarkaBatchArgs.dataOf`. Section 13, edits 7 to 9, put the sentence in each of the three
plans, so that a builder of any of them who starts after part b writes the wrapped form; and
part b's B0 and its runner test catch a place that a row built before it left bare.

**Deviations from the shared names.** None in the records, the rule enum, the counts or the
argument order. Three additions that the family design does not name:
`VarkaBatchArgs.dataOf` with `readsLocalLine` and `LOCAL_MAX_SPAN` (the family design had
the runner call `toInstantArgs` for a local node; a view leaves every reader of a program
unchanged across rows 685 and 688); `VarkaKernelPlan.Kind.ZONE_LOCAL_FORM`; and
`TWO_LANE_LOCAL_RULE_REASON`, a decline in the compiler where the family design had a
recorded refusal of row 689.

**Decided by the owner, 27 November 2026.** Each was a question of this plan's first draft;
he answered each yes, as recommended.

1. The gap's end and the kept offset decline by name at two masked lanes before any
   measurement (4.7). Row 691 keeps or lifts that.
2. The row is as many pull requests as the size cap needs. He approved ten; the lead's move
   of the steps with no mask to row 689 made it nine.
3. One span rule for both step forms: a local batch that spans more than `LOCAL_MAX_SPAN`
   declines under every option (4.4). Row 689 inherits it.
4. A literal with a rest (`INTERVAL '1 02:30:00' DAY TO SECOND`) is in the first kernel of
   the sum (4.10).

**Decided by the lead, 27 November 2026.** The steps with no mask of the local rules are
row 689's work, built there with `ToLocal`'s on one design (4.8). Nothing is open for the
owner.

## 13. Edits to other plans, made by this plan's own pull request

The pull request that adds this file to `sql/varka/plans/m9/` also makes these edits. Each
is an exact old text and its new text; a block is indented by seven spaces, and what
follows those seven is the file's own text. Each old text was matched exactly once, by a
script, in its file on master `84d6b2aff10`.

1. `m9/PLAN.md`, row 690's line, two places. Old:

       | 690 | A local time to an instant: `ToInstant` and `ToInstantKept`. **Filed** (25 November 2026, from `VARKA-657.md`; the admission check:

   New:

       | 690 | A local time to an instant: `ToInstant` and `ToInstantKept`. **Planned** (`VARKA-690.md`, 27 November 2026: nine parts, the two records with their one-offset kernels and the fill on the local line, the masked steps of the three rules, the three first kernels, the sweep under three rules, the fuzzers' draws, the results; the steps with no mask are row 689's; filed 25 November 2026, from `VARKA-657.md`; the admission check:

   Old:

       | `VARKA-657.md`; row 657 first, and row 688 for the last two kernels | large |

   New:

       | `VARKA-657.md`; row 657 first; row 688's parts a to d for the last two kernels; row 684 for the sweep's parts | large |

2. `VARKA-688.md`, 5.3, step 1. Old:

          `COMPUTED_OPERAND_REASON` goes; `static String unrangedReason(String node)` returns 4.11's
          first text with the name in place of `<name>`.

   New:

          `COMPUTED_OPERAND_REASON` goes; `static String unrangedReason(String node)` returns 4.11's
          first text with the name in place of `<name>`. If the file holds `toInstant` (row 690's
          part d merged first), its copy of the same statement, with `local` for `instant`, takes
          the same replacement (`VARKA-690.md` 4.10).

3. `VARKA-686.md`, 4.9. Old:

       - If `ToInstant` exists when part b starts, its B0 stops and the lead adds the refusal of a
         table over it. If not, `VarkaBatchArgs.count`'s switch has the `ToLocal` arm alone, and row
         690's records fail to compile there until that row decides.

   New:

       - If `ToInstant` exists when part b starts, its B0 stops and the lead adds the refusal of a
         table over it as `VARKA-690.md` 4.9 gives it (5.1, step 8, and 5.2, step 5): the plan
         refuses a tree that holds a `ToInstant` or a `ToInstantKept` under a table form
         (`VarkaKernelPlan.ZONE_LOCAL_TABLE_REASON`), and the ladder of a plan whose programs read
         the local line (`VarkaBatchArgs.readsLocalLine`) stops at `STEPS_8`. If not,
         `VarkaBatchArgs.count`'s switch has the `ToLocal` arm alone, and row 690's parts a and b
         add that refusal and that stop when they find the table forms. Where
         `VarkaBatchArgs.LocalLine` exists (row 690's part b) when part a adds `toLocalTable`
         to `VarkaZoneData`, that class gets the method too, and it throws
         `IllegalStateException("no table form reads the local line (VARKA-693)")`.

4. `VARKA-684.md`, 4.7, its last line. Old:

       Row 690 adds two: `min` for `max` in a local threshold (a wrapper) and the kept offset ignored.

   New:

       Row 690 adds four (`VARKA-690.md` 4.12): `min` for `max` in a local threshold (a wrapper), the
       kept offset ignored, the gap's end not taken, and the instant line's arguments for a local
       node.

5. `VARKA-657.md`, section 8, row 690's line. Old:

       | 657, and 688 for the last two | 5 |

   New:

       | 657, and 688 for the last two | 9 |

6. `m9/PLAN.md`, row 689's line, two places, and decision 15. Old:

       each step as a subtract, a shift and an AND, an option arm whose default follows the host;

   New:

       each step as a subtract, a shift and an AND, for `ToLocal` and for the three rules of a local time to an instant, whose records and masked steps are row 690's (`VARKA-690.md`, appendix A, holds the design notes), an option arm whose default follows the host;

   Old:

       | `VARKA-657.md`; decision 15; row 657 first, and row 684's proof file | small |

   New:

       | `VARKA-657.md`; decision 15; row 657 first, row 684's proof file, and row 690's part c for the local rules | small to medium |

   Old:

           item 101) is not this milestone's; row 689 builds that form for the zone step alone.

   New:

           item 101) is not this milestone's; row 689 builds that form for the zone steps alone:
           `ToLocal`'s, and those of the three rules of a local time to an instant.

7. `VARKA-682.md`, 4.8. Old:

         lands in `fusedRunner`'s catch, which is the emission-failure fallback.

   New:

         lands in `fusedRunner`'s catch, which is the emission-failure fallback. Once
         `VarkaBatchArgs.dataOf` exists (row 690's part b), the data a program keeps is
         `VarkaBatchArgs.dataOf(program.node(), data)`: a local-time node reads the local line
         through it, and the runner itself never calls `toInstantArgs` (`VARKA-690.md` 4.4).

8. `VARKA-685.md`, 5.2, step 3. Old:

            layout is not empty (`input[p]` from `VarkaBatchArgs.operandColumn`, `zone[p]` from
            `zoneData()`, a missing zone throwing as row 682 has it); set `kernel.steps` to the steps

   New:

            layout is not empty (`input[p]` from `VarkaBatchArgs.operandColumn`, `zone[p]` from
            `zoneData()`, a missing zone throwing as row 682 has it, and, once
            `VarkaBatchArgs.dataOf` exists, wrapped as `VarkaBatchArgs.dataOf(<the program's
            node>, data)`, `VARKA-690.md` 4.4); set `kernel.steps` to the steps

9. `VARKA-688.md`, 4.6. Old:

       `VarkaEvaluatorBase.newRunner` builds each program's `VarkaOperandRange` where row 685 took
       `VarkaBatchArgs.operandColumn`, with the literal tables of the compiled projection (fact 14).

   New:

       `VarkaEvaluatorBase.newRunner` builds each program's `VarkaOperandRange` where row 685 took
       `VarkaBatchArgs.operandColumn`, with the literal tables of the compiled projection (fact 14).
       Each `zone[p]` stays what row 685 kept: once `VarkaBatchArgs.dataOf` exists (row 690's part
       b), the provider's answer wrapped by it, never the bare answer (`VARKA-690.md` 4.4).

10. `VARKA-678.md`, 4.5 and two lines of 5.2 that state the same oracle. Old:

         hi[i] || (L >= lo[i] && kept == after[i])`. It is `ZonedDateTime.ofLocal(ldt, zone,
         ZoneOffset.ofTotalSeconds(kept / MICROS))`. For a gap `lo[i]` is `hi[i]`, so no local time

    New:

         hi[i] || (L >= lo[i] && kept == after[i])`. It is `ZonedDateTime.ofLocal(ldt, zone,
         ZoneOffset.ofTotalSeconds(kept / MICROS))` for a `kept` that is a whole second within 18
         hours; any other `kept` equals no `after[i]` and answers as `EARLIER`, with no preferred
         offset (`VARKA-679.md` 4.6; `VARKA-690.md` 2.5). For a gap `lo[i]` is `hi[i]`, so no local time

    Old:

            oracle's `ZoneOffset` is `ZoneOffset.ofTotalSeconds((int) (keptMicros / 1_000_000L))`.

    New:

            oracle's `ZoneOffset` is `ZoneOffset.ofTotalSeconds((int) (keptMicros / 1_000_000L))`
            for a `keptMicros` that is a whole second within 18 hours, and null, no preference,
            for any other.

    Old:

       (keptMicros / 1000000))).toInstant())`; *gap's end* is, with `t = rules.getTransition(ldt)`,

    New:

       (keptMicros / 1000000))).toInstant())`, with a null offset for a `keptMicros` that is no
       whole second within 18 hours; *gap's end* is, with `t = rules.getTransition(ldt)`,

11. `VARKA-684.md`, 4.1 and part 1's B0. Old:

         answer true. The kernel runs inside `VarkaKernelChecks.sanitized`. Its status must be 0 and

    New:

         answer true, except under `ONE` for the segment between the cuts of a rule whose
         `oneOutsideCutsOnly()` is true, where it must answer false (`VARKA-690.md` 4.12). The
         kernel runs inside `VarkaKernelChecks.sanitized`. Its status must be 0 and

    Old:

           git grep -l 'implements VarkaZoneData' -- sql/catalyst/src/main        # VarkaChronoZone's file

    New:

           git grep -l 'implements VarkaZoneData' -- sql/catalyst/src/main        # VarkaChronoZone's file; VarkaBatchArgs.java too once row 690's part b has merged (its nested LocalLine)

12. `VARKA-678.md`, 5.4, the B0 line that names the production data's file. Old:

           git grep -l 'implements VarkaZoneData' -- sql/catalyst/src/main                              # $V/VarkaChronoZone.java

    New:

           git grep -l 'implements VarkaZoneData' -- sql/catalyst/src/main                              # $V/VarkaChronoZone.java; $V/VarkaBatchArgs.java too once row 690's part b has merged (its nested LocalLine)

## Appendix A. For row 689's plan: the local rules with no mask

*Design notes, written on 27 November 2026. They bind no builder of row 690: no part of
this row builds, tests or reads anything below. Row 689's plan takes them, checks them and
owns them.*

**What row 689 finds.** `ToInstant` and `ToInstantKept` with their masked steps (4.6), which
ignore `zoneStepMask`; `VarkaZoneLowering.laneCalls(Zoned node, ZoneForm form)`; the view
`VarkaBatchArgs.dataOf` with its span rule; the compiler's two-lane declines of the gap's
end and the kept offset (4.7); and the tests of part c and the sweep's tests 12 to 18, which
run the masked steps.

**The two-lane facts** (`sql/varka/spikes/zone-offset/results/kernels.txt` and
`kernels-2.txt`, the laptop, two 64-bit lanes under AVX2, ns a row): the masked earlier
offset 13.936 against `LocalDateTime.atZone`'s 43.752; the masked kept offset 43.030 and
44.333 against `ZonedDateTime.ofLocal`'s 42.319. No probe timed the gap's end or any local
rule with no mask.

**The lowering.** With `s(a) = (a - 1 - L) >> 63`, an arithmetic shift: the sign word of a
threshold, -1 where `L >= a` and 0 elsewhere, as `ToLocal`'s step with no mask computes it
for an instant.

- `EARLIER`: `acc -= s(hi[i]) & delta[i]`.
- `GAP_END`: that, then with `w = s(lo[i]) - s(hi[i])`, which is -1 inside the window and 0
  elsewhere since `lo[i] <= hi[i]`: `acc += (T[i] - acc) & w`, each sum wrapping. It is
  `T[i]` where `w` is -1 and `acc` where it is 0, for every long.
- `ToInstantKept`: with `z = kept - after[i]`, wrapping, and `e = (z >> 63) | (-z >> 63)`,
  which is 0 where the two are equal and -1 elsewhere for every pair of longs; and `q =
  s(lo[i])`: `acc -= (s(hi[i]) | (q - (q & e))) & delta[i]`.

**Reusing a sign word.** `GAP_END` reads `s(hi[i])` twice in a step and `ToInstantKept`
reads `s(lo[i])` twice. The masked form plans one vector local for a `ToInstant` and two
for a `ToInstantKept` (`Slots.zoneTmp`); a form with no mask needs one more for each, to
hold the word that is read twice, stored when it is first computed. With `c` the lane calls
of one sign word, the counts are then `1 + k(c + 2)` for `EARLIER`, `1 + k(2c + 7)` for
`GAP_END` (the two words; `and`, `sub`; `sub` for `w`; negate, add `T`, `and`, add) and `1 +
k(2c + 10)` for `ToInstantKept` (the two words; subtract `after`, shift, negate, shift,
`or`; `and`, `sub`, `or`; `and`, `sub`). Without the extra local each word is computed
again and the counts are `1 + k(3c + 7)` and `1 + k(3c + 10)`. `laneCalls` then needs the
option beside the form: a third parameter, or the options whole.

**The bound.** A threshold of a filled step belongs to a window that the range touches, so
`least - 2M < lo[i]` and `hi[i] <= greatest + 2M`, and a live `L` is in `[least,
greatest]`. So `a - 1 - L` is a long while `greatest - least + 2M` is at most `2^63`, which
holds through a span of `LOCAL_MAX_SPAN + 1` and first fails at `LOCAL_MAX_SPAN + 2`. The
view declines a span over `LOCAL_MAX_SPAN` under every option (the owner's decision 3 of
section 12), so row 689 adds no decline of its own for a local program: it inherits the
rule. A padded step is exact for every long (`zone_step.smt2`, check 9).

**The proof file,** `zone_local_step.smt2`, logic `QF_NIA` over `java.smt2`, rendered by
`VarkaProofFiles` from the code's constants: `M2 = 2 * VarkaBatchArgs.MAX_OFFSET_MICROS =
129600000000`, `SPAN = VarkaBatchArgs.LOCAL_MAX_SPAN = 9223371907254775807`, `H =
64800000000`; the renderer requires `SPAN + M2 == Long.MAX_VALUE`. The frame `F` of a
check:

    (declare-const L Int) (declare-const least Int) (declare-const greatest Int)
    (declare-const lo Int) (declare-const hi Int)
    (assert (and (jlong.in L) (jlong.in least) (jlong.in greatest) (jlong.in lo) (jlong.in hi)))
    (assert (and (<= least L) (<= L greatest) (<= lo hi)))
    (assert (and (< (- least 129600000000) lo) (<= hi (+ greatest 129600000000))))
    (define-fun S ((a Int)) Int (jlong.shr (jlong.sub (jlong.sub a 1) L) 63))
    (define-fun GE ((a Int)) Int (ite (>= L a) (- 1) 0))

`IN` is `(assert (<= (- greatest least) 9223371907254775807))`. Each check is its frame,
then the asserts of its row, then `(check-sat)`.

| # | what | expect | asserts after the frame |
| ---: | :--- | :--- | :--- |
| 1 | inside LOCAL_MAX_SPAN, a threshold less one less a local time is a long | unsat | `F`; `IN`; `(assert (not (and (jlong.in (- (- lo 1) L)) (jlong.in (- (- hi 1) L)))))` |
| 2 | so the sign word of each threshold is the compare | unsat | `F`; `IN`; `(assert (not (and (= (S lo) (GE lo)) (= (S hi) (GE hi)))))` |
| 3 | one microsecond past the bound it still is | unsat | `F`; `(assert (= (- greatest least) 9223371907254775808))`; `(assert (not (and (= (S lo) (GE lo)) (= (S hi) (GE hi)))))` |
| 4 | two past, the sign word of a window's start differs | sat | `F`; `(assert (= (- greatest least) 9223371907254775809))`; `(assert (not (= (S lo) (GE lo))))` |
| 5 | the window word is -1 exactly inside the window | unsat | `F`; `IN`; `(assert (not (= (- (S lo) (S hi)) (ite (and (>= L lo) (< L hi)) (- 1) 0))))` |
| 6 | the select by a sign word is T inside and acc outside, for every long | unsat | no frame; `(declare-const acc Int) (declare-const T Int) (declare-const w Int)`; `(assert (and (jlong.in acc) (jlong.in T) (or (= w 0) (= w (- 1)))))`; `(assert (not (= (jlong.add acc (jlong.and.sign w (jlong.sub T acc))) (ite (= w (- 1)) T acc))))` |
| 7 | the difference word of two longs is 0 exactly where they are equal and -1 elsewhere | unsat | no frame; `(declare-const x Int) (declare-const y Int)`; `(assert (and (jlong.in x) (jlong.in y)))`; `(define-fun z () Int (jlong.sub x y))`; `(define-fun e () Int (ite (or (= (jlong.shr z 63) (- 1)) (= (jlong.shr (jlong.neg z) 63) (- 1))) (- 1) 0))`; `(assert (not (= e (ite (= x y) 0 (- 1)))))` |
| 8 | the kept step with no mask subtracts the delta exactly where the masked step does | unsat | `F`; `IN`; `(declare-const kept Int) (declare-const after Int) (declare-const delta Int)`; `(assert (and (jlong.in kept) (jlong.in after) (jlong.in delta)))`; `(define-fun e () Int (ite (= kept after) 0 (- 1)))` (check 7's word); `(define-fun q () Int (S lo))`; `(define-fun fire () Int (ite (or (= (S hi) (- 1)) (= (- q (jlong.and.sign e q)) (- 1))) (- 1) 0))`; `(assert (not (= (jlong.and.sign fire delta) (ite (or (>= L hi) (and (>= L lo) (= kept after))) delta 0))))` |
| 9 | a local time 18 hours inside a long's ends less an offset within 18 hours does not wrap | unsat | no frame; `(declare-const L Int) (declare-const off Int)`; `(assert (and (<= (+ jlong.MIN 64800000000) L) (<= L (- jlong.MAX 64800000000))))`; `(assert (and (<= (- 64800000000) off) (<= off 64800000000)))`; `(assert (not (= (jlong.sub L off) (- L off))))` |
| 10 | a window's start taken for its end, the planted fault's form, differs at the window's start | sat | `F`; `(assert (< lo hi))`; `(assert (= L lo))`; `(assert (not (= (GE lo) (GE hi))))` |

The verdicts were reasoned by hand and not run under a solver; a reviewer checked those of
1 to 6. `jlong.in`, `jlong.sub`, `jlong.add`, `jlong.neg`, `jlong.shr` and `jlong.and.sign`
are in `java.smt2` on master.

**Tests row 689 can take as they stand.** Part c's tests 1 to 5 and the sweep's tests 12 to
15 and 17 run under any emit options: a loop over the two values of `zoneStepMask` around
each body covers the new arm. One test more is owed: under the form with no mask, the pair 0
and `LOCAL_MAX_SPAN` is answered (`Asia/Kathmandu`, `STEPS_2`) and the pair -1 and
`LOCAL_MAX_SPAN` is declined by the view.

**What stays undecided for row 689 and row 691.** Whether the two-lane declines of 4.7 are
lifted once the steps with no mask exist: row 689's part d measures the 128-bit sections and
its rule decides (`VARKA-689.md` 4.10); row 691 measures again with every form.
