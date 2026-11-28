# VARKA-657: The session time zone in a kernel

*Planned 25 November 2026. The owner asked for a detailed plan with investigations where the
design was open, small tasks that a regular coding agent can build, each pull request under 1000
lines, refactoring tasks where the code needs them, and proofs, fuzzing, coverage and benchmarks
planned in. He read the first draft the same day and decided the four points of section 1. This
file is the design of a family of rows: row 657 and rows 682 to 692. Sections 1 to 8 are written
before any code.*

## 1. Where this came from

Row 657 of `m9/PLAN.md`, from the catalogue's items 31 and 89 (`m10/SCOPE.md` once the catalogue
has moved). Milestone 9's goals G1, G2 and G7 are decided here: every expression that reads the
session time zone waits on this family (rows 668 to 671 and the nanosecond rows 673 to 675), the
proof that a kernel equals Spark in every zone is here, and so is the first measurement of G7's
bar, five times the row path where a kernel reads the zone.

**The owner's decisions on the first draft, 25 November 2026.**

1. *The conversions are IR nodes,* `ToLocal` and `ToInstant`, as `m9/PLAN.md` 2.2 says. The draft
   proposed trees that the compiler builds from nodes the IR has. 2.9 holds the comparison.
2. *What several fields share, they share through the node,* with no second prefix mechanism
   beside the calendar's (3.9).
3. *Regular rows, not lettered parts.* The draft had 23 parts under this row. Row 657 keeps the
   first kernels end to end, and rows 682 to 692 hold the rest (section 8).
4. *Milestone 9 has not started.* While milestone 8 runs, this family is planned and not built.
   This file is the family's design. Each row's own plan stands alone and holds its parts and
   their sheets; a sheet's first step is a grep of its premises, because the code facts here
   are of this day and the rows start later.

`m9/PLAN.md` 2.2 holds the design the milestone plan chose and the first probes' numbers
(`sql/varka/spikes/zone-offset`). This plan keeps that design and makes it exact in three places,
each from an investigation of section 2:

1. **The values a batch brings are not literals.** 2.2 called them "the first slots the
   evaluator fills from the batch". A literal slot is a compile-time constant in three places of
   the compiler (2.9), so a zone node's values are arguments of their own, after the literals
   (3.2).
2. **The cache's statistics do not reach a kernel today** (2.6). 2.2 called the range free for a
   cached column. It becomes free only after a hand-off that row 687 builds, so the first range
   source is a pass over the column, which is exact and needs no second run.
3. **The form is chosen from the batch's range, not by timing and not by a second run** (2.8).
   The literature on choosing a variant for each batch dispatches on a property wherever the
   property decides; it measures only where every variant is right and the faster one is
   unknown. The range decides how many steps a batch needs, so the choice is exact.

## 2. The admission check, done

Each claim below was checked on master `176db41f369` on 25 November 2026. Code is named by class
and method; line numbers move.

### 2.1 A kernel's long arguments can change between batches without a new class

- A literal is a `VarkaVectorIR.LiteralSlot(index, lane)`; its value is not in the IR. The
  compiler keeps the values in tables (`DeclineSink.longSlot`), and `CompiledVarkaProjection`
  carries them as `literals` and `longLiterals`.
- `VarkaEvaluatorBase.newRunner` copies the tables once into `VarkaKernelRunner.scalarArgs` and
  `longArgs`, and `VarkaKernelRunner.invoke` passes those arrays to `kernel.run` on every batch.
  The arrays are per runner, and a runner is per task, so a value written between two batches is
  seen by the next call and by no other task.
- `VarkaShapeKey(outputs, numInputs, numLiterals, options, warmed)` holds the count of literals
  and not their values, and it holds the emit options. Two sessions in two zones share one
  class, and a form that is an option is part of the key (3.5).
- Three places hold a second copy of the values and must follow a per-batch value:
  `VarkaKernelWarmup`'s job clones both arrays when it starts (after `VarkaWarmupGate` has called
  `runner.fill`, so the clone holds the batch's values if `fill` writes them);
  `VarkaReproduction.write` records `compiled.literals()` and `compiled.longLiterals()`, the
  plan's values, not the arrays the kernel ran with; and the harness store keys on the session
  zone already (`VarkaHarnessStore`).
- What a bound can do today: `VarkaInputBound(inputIndex, lo, hi)` is an `int` interval fixed at
  compile time, checked in `VarkaKernelRunner.fill` by `IntRangeOps.allWithin`; a failure throws
  `VarkaBatchDeclined(STATUS_INPUT_BOUND)` and `VarkaEvaluatorBase.serveBatch` sends the whole
  batch to the row path. There is no second attempt and no long-lane form.

### 2.2 The operations of a step compile today, masked and with no mask

`dev/varka_emit.sh` on master, over a `BIGINT` column, with the offsets written as literals:

| expression | IR of the output | `loopDense0` |
| :--- | :--- | :--- |
| `l + CASE WHEN l >= 1000L THEN 3600L ELSE 0L END + CASE WHEN l >= 2000L THEN -3600L ELSE 0L END` | `(int:ADD:FAIL (int:ADD:FAIL col:0:long (if (cmp:GE col:0:long lit:0:long) lit:1:long lit:2:long)) (if (cmp:GE col:0:long lit:3:long) lit:4:long lit:2:long))` | 365 bytes, 21 `LongVector` and 4 `VectorMask` calls |
| `l + (shiftright(999L - l, 63) & 3600L) + (shiftright(1999L - l, 63) & -3600L)` | `(int:ADD:FAIL (int:ADD:FAIL col:0:long (bit:AND (bit:SHR (int:SUB:FAIL lit:0:long col:0:long) lit:1:long) lit:2:long)) (bit:AND (bit:SHR (int:SUB:FAIL lit:3:long col:0:long) lit:1:long) lit:4:long))` | 471 bytes, 31 `LongVector` and 6 `VectorMask` calls |

Both are `FusedOutput`: the long lane has every operation a step needs. The counts are with
ANSI's checked `ADD` and `SUB`, which SQL text gives. A lowering emits the sums with no check,
as `VarkaNodeCompiler.microsOfDay` builds them, since an instant plus an offset cannot leave a
long (3.6), so the kernels of this family hold fewer calls than the table shows. Row 682
registers the counts of its lowering.

### 2.3 What a whole kernel costs, and the way back

The probe of whole kernels, `sql/varka/spikes/zone-offset/src/ZoneKernels.java` (`run.sh
kernels`, `results/kernels.txt`, run twice): `America/Los_Angeles`, batches of 4096, a year of
values in no order with half of them within three hours of a change, on the laptop (an AMD Ryzen
AI 9 HX PRO 370, JDK 25). Every form equals `java.time` on every row. Nanoseconds a row:

| form | 8 lanes | 4 lanes, AVX2 | 2 lanes, AVX2 |
| :--- | ---: | ---: | ---: |
| `java.time`: the hour of the day, as the row engine reads it | 26.815 | 25.409 | 25.360 |
| `hour`: 2 steps, then the hour | 0.482 | 0.715 | 15.679 |
| `hour`: 2 steps with no mask, then the hour | 0.514 | 0.906 | 1.597 |
| `hour`: one offset, then the hour | 0.477 | 0.479 | 0.947 |
| `java.time`: `LocalDateTime.atZone` | 43.163 | 43.764 | 43.752 |
| local to instant, the earlier offset, 2 steps | 0.085 | 0.138 | 13.936 |
| `java.time`: `ZonedDateTime.ofLocal` | 44.484 | 42.424 | 42.319 |
| local to instant, the kept offset, 2 steps | 0.248 | 0.351 | 43.030 |

- At four lanes the body of an `hour` kernel costs 0.7 ns a row where `java.time` pays 25, and
  the two steps are 0.236 of it, 0.715 less the 0.479 of one offset.
- The way back is where the row path is slowest, about 43 ns a row, and the kernel's step is the
  cheapest: the casts to `TIMESTAMP` and the sums gain most.
- The kept-offset rule works in vector code: a step is two compares, an AND and an OR of masks,
  and a masked add. Of the 4096 rows, 85 keep an offset that changes their instant.
- At two lanes the masked forms run as Java loops, as `sql/varka/skills/the-jit.md` says of every
  mask at two 64-bit lanes. The masked `hour` still beats `java.time` (16 against 25 ns), and the
  form with no mask costs 1.6. The masked kept-offset form gains nothing: by the minimums of two
  runs (`results/kernels.txt` and `results/kernels-2.txt`) it costs 43.030 ns a row where
  `java.time` costs 42.319. So that form needs its own steps with no mask at two lanes, or it
  declines there (the owner's decision 15 of `m9/PLAN.md`).
- The probe's hour is a quotient in doubles that holds on these rows only. A kernel takes
  `VarkaNodeCompiler.floorDivLong` and its proof; the probe prices the shape, not that lowering.

### 2.4 One pair of thresholds a change serves all three rules

Read in JDK 25's source and held by a probe over all 604 zone ids, to the year 2400
(`src/ZoneResolve.java`, `results/resolve.txt`: 18,399,068 checks, no mismatch). For a change at
instant `T[i]` between offsets `O[i]` and `O[i+1]`, let `lo[i] = T[i] + min(O[i], O[i+1])` and
`hi[i] = T[i] + max(O[i], O[i+1])` on the local time line, and `k = #{hi <= L}` for a local time
`L`:

- **(a) the earlier offset** (`LocalDateTime.atZone`, `ZonedDateTime.of`): `L - O[k]`.
- **(b) the kept offset** (`ZonedDateTime.ofLocal` with the value's own offset, which `plusDays`,
  `plusMonths`, `with...` and `truncatedTo` use): `L - O[k+1]` when `lo[k] <= L`, the change is
  an overlap and the kept offset is `O[k+1]`; else as (a).
- **(c) the gap's end** (`LocalDate.atStartOfDay`): `T[k]` when `lo[k] <= L` and the change is a
  gap; else as (a). (a) and (c) differ on one day, 31 March 1919, in six zone ids, where
  midnight is strictly inside a gap. The probe reads (c) at midnights, the only local times that
  method takes. For any other local time the rule is the same steps of `java.time`: a local
  time inside a gap moves to the gap's end, then (a).

`plusHours`, `plusMinutes`, `plusSeconds` and a time-based `plus` are sums on the instant and read
no rule. Which Spark function takes which rule (`DateTimeUtils`, `SparkDateTimeUtils`):

| Spark's function | what it calls | rule |
| :--- | :--- | :--- |
| `getHours`, `microsToDays` | `Instant.atZone` | instant to local |
| `daysToMicros` | `atStartOfDay(zone)`, skipped when the zone is the `ZoneOffset.UTC` object | (c) |
| `convertTz`, `fromUTCTime`, `toUTCTime` | local in one zone, then `atZone` in the other | instant to local, then (a) |
| `timestampAddMonths` | `atZone.plusMonths` | instant to local, (b) |
| `timestampAddDayTime` | `atZone.plusDays.plus(n, MICROS)` | instant to local, (b), a sum on the instant |
| `timestampAddInterval` | `atZone.plusMonths.plusDays.plus(n, MICROS)` | (b) twice, the second from the first's result and offset |
| `monthsBetween` | `microsToDays`, `daysToMicros` | instant to local, (c) |
| `makeTimestamp` and its forms | `atZone` | (a) |
| `truncTimestamp` | two offset lookups on a fast path, `truncatedTo` or (c) otherwise | see 3.10 |

### 2.5 What the zone data must not assume

From RFC 9636 (TZif), the tz database's theory file, `java.time`'s contracts and the committed
facts of the JDK's data (`results/facts.txt`, tzdata 2026f): an offset need not be a whole
minute (the last such ended in 1972, in `Africa/Monrovia`), though `ZoneOffset` holds every
offset within 18 hours; a change can skip or repeat a whole day (`Pacific/Apia` skipped 30
December 2011); a zone can change 5 times in 365 days (`Africa/Casablanca`); a standard offset
can change with no daylight saving (`Asia/Qostanay` 2024); and after its last stored change a
zone follows recurring rules (`ZoneRules` allows 16), periodic over 400 years, which `java.time`
works out again on every call for a year from 2100 on. `ZoneRules` does not promise that the
local windows of two changes are apart, so row 678 checks each zone when it builds it, prints
the counts that its check reads, and a zone that fails the check is not served.

### 2.6 The cache's statistics stop at the scan

`ArrowCachedBatchSerializer.collectStatistics` computes the least and greatest value of a
`TIMESTAMP` column for each cached batch (`calculateMinMaxTimestamp`) and stores them in
`ArrowCachedBatch.stats`. Their only reader is batch pruning (`InMemoryTableScanExec`).
`ArrowCachedBatchToColumnarBatchIterator.next` deserializes the Arrow data and returns
`new ColumnarBatch(selectedColumns, rowCount)`: the statistics are dropped there. Varka knows
only that its input is the Arrow cache (`VarkaColumnarRule.readsArrowCache`). No engine read for
this plan hands a block's least and greatest value to an expression kernel either; they use them
at the scan (2.8).

### 2.7 The type and the zone are not read anywhere yet

`VarkaTypes.info` declines `TIMESTAMP` ("its arithmetic is in the session time zone, which is not
lane arithmetic"); row 656 admits it on the long lane. No Varka compiler reads
`TimeZoneAwareExpression.zoneId`. The `TIMESTAMP_NTZ` arms this family reuses are
`VarkaTimeCompiler`'s `hour`, `minute` and `second` (through `ntzMicros`,
`VarkaNodeCompiler.microsOfDay` and the private `hourOf`, `minuteOf`, `secondOf`),
`VarkaNodeCompiler.dateOfNtz` for the cast to `DATE`, `floorDivLong`, and `ntzDay`, the guard on
the day that the calendar path admits. `date_trunc` has no `TIMESTAMP_NTZ` arm: Spark coerces it
to the zoned type, so it is row 670's from the start.

### 2.8 What the literature says, in short

The reading note (`m9/READING_TIME_ZONES.md`, a pull request of its own after the catalogue's
move) holds the sources and what each was checked against. What this plan takes:

- **The step is a rank among a few sorted thresholds, for many keys at once.** Every source that
  tests small counts prefers comparing against all of them and counting (Zhou and Ross 2002;
  Schulz, Broneske and Saake 2018); none gives a cheaper form for 8 thresholds or fewer.
- **Past that, a direct-address table.** A time-zone library, vtz, uses the bucket table of
  `m9/PLAN.md` 2.2 with a bucket of 16 bytes, the change and the two offsets, so a lookup is two
  loads where the first probe's is three gathers. Polychroniou, Raghavan and Ross (2015) pack
  fields for the same reason. A gather of four 64-bit lanes costs about 4 cycles on the EPYC
  7763's core.
- **No mask-free step is known but the subtract, shift and AND** of the first probe.
- **A variant is chosen from a property where the property decides.** Photon builds metadata
  for each batch and selects a kernel by it; DuckDB installs an unchecked kernel when a column's
  least and greatest value rule out overflow; Vectorwise's micro adaptivity measures, but only
  among variants that are all right, and reports that a cross-over moves with the machine. None
  runs a batch twice: a wrong guess ends on the general path.
- **Tests.** Boundary arguments find most bugs in SQL functions (Fu et al. 2025); the classes
  reported for zones are the fold of an overlap, the time after the last stored change, the time
  before the first, odd offsets and a change of the zone data between releases. No formal work
  treats time-zone rules: the proof here is the sweep over the JVM's own data.

### 2.9 A node, or a tree that the compiler builds

The first draft had the compiler build each conversion at once, as a tree of nodes the IR has
over literal slots. The owner chose nodes (decision 1). The facts on master:

- **The catalogue decided it in September.** Item 11's design input: "Make the physical form
  explicit on IR values rather than implied by the node type: a representation attribute, and
  conversion nodes as first-class IR. Then an e-graph later is a change of engine, not of
  language." An instant and a local time are two forms of one value, and `ToLocal` is the
  conversion between them.
- **A tree hides the conversion from every later pass.** A tree of `K` compares cannot be
  matched back to "a zone conversion", so no rule about zones can be written over it, and the
  form (`K` steps, or the table) is fixed in the compiler, before an extractor could price the
  alternatives (the catalogue's item 85, step 4). A layout the compiler does not build for, the
  nanosecond pair or one of Arrow's other units, has nowhere to choose its own lowering.
- **A literal slot is a compile-time constant in three places.** `DeclineSink` interns long
  literals by value (`longLiterals`, a map from a value to its slot), so two slots with one
  placeholder value would be one slot. `VarkaRangeAnalysis.range` reads a long literal as its
  point, so a placeholder's value would prove a range that the batch's value breaks. And four
  compiler classes roll the long table back (`truncateLong`), each of which would have to roll
  a program back too. The review of the draft found all three in it.
- **An emit option is the house's home for a lowering's alternatives.** `VarkaEmitOption` has
  flags, counts and choices; the options are part of `VarkaShapeKey`; the option matrix runs
  each arm, and `VarkaEmittedBytesSuite` pins each arm's bytes.
- **The reference evaluator answers a calendar node by its definition** (`java.time`,
  `DateTimeUtils`), and it does not compile until a new node has an arm (`VarkaReference`). So
  `VarkaIrFuzzSuite` checks a zone node inside random trees against `java.time`.
- **What a node costs.** `m8/VARKA-489.md`'s recipe: the record and its `props()`; arms in the
  structural switches of `VarkaVectorIR` and in `Analysis`, `Slots`, `VarkaVectorWalk`,
  `VarkaKernelPlan` and `VarkaRangeAnalysis`; a price in `VarkaEmitCostTable` and a row in the
  budget suite's register; and the fixtures (a draw in `VarkaIrGrammar`, an instance in
  `VarkaLaneTypeSuite`, a round trip in `VarkaReproductionSuite`, an encoding or a refusal in
  `VarkaSmtNodes`, an arm in `VarkaRangeAnalysisSuite`). The family adds three records where the
  draft added one, the table's.
- **Both styles are on master.** The `DATE` family is nodes with lowerings
  (`VarkaChronoLowering`); milestone 8's `TIMESTAMP_NTZ` fields are arithmetic the compiler
  builds. A field of a local time stays built arithmetic: it is an operation in one form, not a
  conversion between two.

The emitted kernel is the same either way: the node's lowering emits the forms the probes
measured. What the e-graph and a representation on IR values give is the catalogue's (items 11
and 85) and is not measured in this milestone; here the nodes pay through the other points.

### 2.10 How two readers of one value share it today

- `Slots` counts how often a body visits each node (VARKA-223): "a node visited twice is
  computed once into its slot and loaded after". Nodes are records with structural equality and
  a cached hash (VARKA-559), so two equal `ToLocal` nodes are one node.
- The calendar prefix is shared another way, by a fragment key
  (`VarkaChronoLowering.emitChronoPrefixOnce`), because the civil fields that `Year` and `Month`
  share are not an IR value: each node's lowering computes them. The catalogue's item 11, in its
  note of 27 September 2026, records that as a form to make explicit.
- Across method groups a shared node is computed again in each group. The calendar prefix alone
  has a store and a load for that case (VARKA-198, `materializeChronoPrefix`), and a weight in
  the grouping budget (`VarkaEmitBudget.CHRONO_PREFIX_WEIGHT`).

## 3. The design

### 3.1 The nodes

Three records under a sealed `Zoned` interface in `VarkaVectorIR`, each on the long lane:

| record | what it is | its definition in `java.time` |
| :--- | :--- | :--- |
| `ToLocal(instant, zone)` | an instant to the local time in the zone | the instant plus `ZoneRules.getOffset` of it |
| `ToInstant(local, zone, rule)`, the rule `EARLIER` or `GAP_END` | a local time to an instant | `LocalDateTime.atZone`; under `GAP_END` a local time inside a gap first moves to the gap's end, as in `LocalDate.atStartOfDay` |
| `ToInstantKept(local, kept, zone)` | the same, with the offset `kept` preferred where the local time is in an overlap | `ZonedDateTime.ofLocal` |

- `zone` is an index into the compiled projection's list of zone ids, as an ordinal is an index
  into the inputs. The id is outside the IR, so two sessions in two zones share a shape and a
  class. The session zone is index 0; a literal zone argument is another index.
- A fixed zone is no node. Under UTC the compiler emits the operand itself, and under a fixed
  offset a sum with a literal slot, so the kernel of `hour(ts)` under UTC is the
  `TIMESTAMP_NTZ` kernel. The compile runs on the executor (`VarkaKernelEvaluator.compiled`), so
  "fixed" is read from the executor's rules.
- A null operand makes a null (`Validity.STRICT`). A node fails no row and declines no row.
- The compiler's arms are the `TIMESTAMP_NTZ` arms over the node: `hour(ts)` is
  `hourOf(microsOfDay(ToLocal(ts)))`. So a field needs no kernel of its own, as the milestone
  plan said. A new compiler class in Java, `VarkaZoneCompiler`, holds what the arms share: the
  zone's index, the fold of a fixed zone and the declines by name.

### 3.2 Batch arguments: the values a zone node reads

A zone node's lowering reads scalars that the evaluator computes for each batch: an offset, the
change points, the deltas. They are **batch arguments**: entries of `longArgs` after the
literals.

- **The layout** is a function of the final IR and the options,
  `VarkaBatchArgs.layout(outputs, numLiterals, options)`: one run of arguments for each distinct
  zone node, in the order of one fixed walk. The emitter calls it to number a node's arguments
  and the runner calls it to fill them, so the two cannot disagree. Nothing enters the literal
  table: there is no interning, no rollback, and no value for the range analysis to read (2.9).
- **A program** is what the layout gives the runner for a node: the zone, where the range of the
  node's operand comes from (3.3), the first argument and the count. Its `fill` writes the
  values for a range, or answers that the kernel's form does not cover that range (3.5).
- `VarkaKernelRunner.fill` gains one step after the input bounds: for each program, the range of
  its operand, then the program's `fill`. The arrays are the runner's, so nothing allocates and
  no task sees another's values (2.1).
- A kernel reads a batch argument as it reads a literal: a scalar local, broadcast once before
  the loop.
- The copies of 2.1 follow: the warm-up clones the arrays after the fill, and a reproduction
  records the arrays the kernel ran with, and the zone ids.

### 3.3 The range of a node's operand

- **A column** (through value-preserving casts): `LongRangeOps.leastAndGreatest`, a new sibling
  of `IntRangeOps` (2.1): lane-wise `min` and `max`, null rows skipped by the validity words. The
  first probe priced the pass at 0.058, 0.232 and 0.393 ns a row at eight, four and two lanes
  (`results/forms.txt`). It is exact, so no batch runs twice (row 683).
- **A cached column:** the cache has the two values already; row 687 hands them to the evaluator
  and the pass is skipped. A null row's storage is not read by either source, but its lane
  still runs in the kernel with whatever the storage holds. The steps give such a lane a value
  that the output's validity hides; the table form must not index with it (3.5).
- **A computed operand,** `hour(ts + INTERVAL '1' HOUR)` or the local time that `ToInstant`
  reads: its range for a batch is interval arithmetic over the ranges of the batch's columns,
  with the transfer functions `VarkaRangeAnalysis` has and one for each zone node (row 688).
  Until that row a zone node over a computed operand declines by name, and row 690's first
  kernel is the one whose operand is a column, `CAST(ntz AS TIMESTAMP)`.

### 3.4 The zone data is row 678's; what this family reads from it

`VarkaChronoZone`, built once a zone in each JVM from that JVM's `ZoneRules`. A program holds the
zone's id, not its data, and resolves it on the executor. The family needs from it:

| what | used by |
| :--- | :--- |
| whether the zone is served (its build-time check passed) and whether it is fixed | the compiler, to fold a fixed zone or to decline by name |
| the count of changes with `least < T <= greatest` | the choice of a form |
| `base`, `at[i]`, `delta[i]` for a range of instants, padded to `K` with a change that never comes | `ToLocal` |
| `base`, `hi[i]`, `lo[i]`, the offset after, for a range of local times | `ToInstant`, `ToInstantKept` |
| the instants from which every offset is a whole minute and a whole hour | `second` and `minute` with no offset |
| the bucket table | the table form |

### 3.5 Forms: an emit option, and a kernel for each batch

A zone node has forms: one offset, 2 steps, 8 steps, the table. The form is an emit option, a
choice (`zoneForm`), one for a kernel: every zone node of the kernel takes it. So it is part of
the shape key, the matrix runs each arm, and each arm's bytes are pinned (2.9).

The option's default is the ladder. The evaluator holds a kernel for each form a batch has
needed, compiled when first needed, and takes for each batch the least form that covers the
range of every program. The choice is exact; nothing is timed and no batch runs twice (2.8). A
forced form, a matrix arm, sends a batch that it does not cover to the row path.

An emission that names no form, under the default, takes the eight-step form: the tests and the
compiler emit under the default options in many places, so a refusal there would make each of
them name a form by hand (the owner's word of 26 November 2026; `VARKA-685.md` 4). At two
64-bit lanes with masked steps the ladder stops at 2 steps until row 689.

The table form reads a bucket by an index made from the operand, and a null row's storage can
hold any long. Its lowering clamps the index into the table, and row 686's fixtures hold null
rows over storage outside the table's range.

Only one threshold is a cost question: from how many changes the table beats the steps. The
first probe put it near nine at four lanes and near twenty at eight. Row 691 measures it in
kernels on the runners and sets it per width, which the house allows since row 600.

**What needs no offset** (`m9/PLAN.md` 2.2). `second` reads the instant alone where every offset
in the batch's range is a whole minute, and `minute` where each is a whole hour. With nodes the
saving has two forms, a grain on the node that lets its program fill one offset of zero, or a
rewrite under a batch condition. Either one competes with sharing one full `ToLocal` among the
fields of a projection (3.9), so row 685 builds it last, and only if row 691's numbers show a
gain.

### 3.6 Ranges and overflow

An offset is within 18 hours (`ZoneOffset`'s contract), so an instant plus an offset leaves a
long only within 18 hours of a long's ends. Spark's own range, years 0001 to 9999, ends far
inside, but nothing holds a stored value to it: `timestamp_micros` takes any long, and the row
path answers such a value without a wrap.

- A program's `fill` declines a batch whose least or greatest value is within 18 hours of an
  end. It has the range already, and the batch takes the row path.
- The sum of a fixed offset has no program. It sits under a `GuardedRange` over the same
  bounds, which declines the batch (its `FAIL` mode).
- With those two the lowering emits its sums with no check. The sweep's fixtures hold both ends
  of a long, beside both ends of Spark's range.

`VarkaRangeAnalysis` gets one arm for each record: the operand's range widened by 18 hours each
way. It reads no batch value. Where an arm's path holds a guard on the local value, as the
calendar path's `ntzDay` does, a batch outside it declines as it does for `TIMESTAMP_NTZ`.

### 3.7 To an instant: the three rules

From 2.4, with the batch arguments `base`, `hi[i]`, `lo[i]`, `after[i]` and `delta[i]`:

- **`EARLIER`:** `L - (base + sum of (L >= hi[i] ? delta[i] : 0))`: the steps of `ToLocal` on
  the local thresholds.
- **`ToInstantKept`:** the same with the condition `L >= hi[i] OR (L >= lo[i] AND kept ==
  after[i])`, where `lo[i]` is `hi[i]` for a gap, so that no local time is inside. `kept` is an
  operand: the compiler passes `ToLocal(x) - x` of the value the local time was made from, a
  node the body already holds (2.10).
- **`GAP_END`:** `L` moves to `hi[i]` where `lo[i] <= L < hi[i]`, with `lo[i]` set to `hi[i]`
  for an overlap, then the steps of `EARLIER`. It holds for every local time; Spark reaches it
  at midnights only, through `daysToMicros`.

The first kernels of each: `CAST(ntz AS TIMESTAMP)` (`EARLIER`), `CAST(d AS TIMESTAMP)`
(`GAP_END`) and `ts + INTERVAL DAY` (`ToInstantKept`, then a sum on the instant).

### 3.8 Two lanes

At two 64-bit lanes on x86 the lowering emits each step as a subtract, a shift and an AND, with
no mask (2.2). That is an option arm of its own, whose default follows the host flag that row
503's refusal reads (`VarkaKernelPlan.twoLaneMasksFallBack`). That flag is the driver's, the debt
the catalogue's item 127 records; this family adds a reader and does not move the choice. A
form that is slower than the row path there declines by a recorded refusal (the owner's decision
15 of `m9/PLAN.md`); the kept-offset form is the known candidate (2.3).

The step with no mask is exact only where the span of a batch fits a long (section 5), so a
kernel of this arm declines a batch whose span does not. Row 689 builds that decline and needs
the proof file of row 684 first.

### 3.9 What several fields share

- **Inside a method** `hour(ts)`, `minute(ts)` and `CAST(ts AS DATE)` hold one `ToLocal(ts)`,
  computed once into its slot (2.10). `year(ts)` and `month(ts)` then share the calendar prefix
  over the local day, as they do for a `TIMESTAMP_NTZ`. The kept-offset rule's `ToLocal(x) - x`
  reads the same node.
- **Across method groups** the node is computed again in each group. At four lanes two steps
  cost 0.148 ns a row and eight cost 0.755 (`results/forms.txt`), so a store and a load can pay
  for the dear forms only. Row 692 measures it, and builds the store and load of VARKA-198 for a
  zone node only if it pays. The node's weight in the grouping budget is its form's, from its
  `props()` (row 682).
- **No second prefix mechanism.** The calendar's prefix needs its own because the civil fields
  are not an IR value. The local time is one.

### 3.10 What is deliberately unchanged, and what declines by name

- The `TIMESTAMP` lane, its load and output and its zone-free entries: row 656.
- The zone data and its build-time check: row 678. The library's forms: row 679.
- Every field but `hour`, `minute`, `second` and the cast to `DATE`: row 668. The other casts:
  row 669. `date_trunc`, `from_utc_timestamp`, `to_utc_timestamp`, `convert_timezone`,
  `months_between`, `make_timestamp`: row 670. The sums but `ts + INTERVAL DAY`: row 671.
- **A finding for row 670 and for the register of row 681.** `truncTimestamp` has a fast path
  (SPARK-56769) that truncates in the value's own offset and keeps the result when the offset
  there is the same; above `DAY` its other path is `daysToMicros` of the truncated date, rule
  (c). They differ where the truncated day's midnight repeats. Run on master on 25 November
  2026 over Spark's own functions: under `America/Havana`,
  `DateTimeUtils.truncTimestamp(1605891600000000L, TRUNC_TO_MONTH, zone)`, the month of
  2020-11-20 17:00 UTC, is 2020-11-01 05:00 UTC, the second midnight, where
  `daysToMicros(truncDate(microsToDays(...), TRUNC_TO_MONTH), zone)` gives 04:00 UTC; an instant
  in the month's first hour gives 04:00 UTC on both. So one month truncates to two instants.
  `QUARTER`, `YEAR` and `WEEK` take the same path. At `DAY` the two paths agree, since the other
  path there is `truncatedTo`, rule (b). Row 670's kernel must equal what Spark does, row 681
  commits the reproducer, and the owner decides whether it is reported upstream.
- Declines by name until their row: a zone node over a computed operand (688), a batch that
  spans more changes than the widest form built so far (685, 686), a zone the data does not
  serve.

## 4. Files

| file | what | row |
| :--- | :--- | :--- |
| `sql/catalyst/.../codegen/varka/VarkaVectorIR.java` and the files of `m8/VARKA-489.md`'s recipe | the three records under `Zoned` | 682, 690 |
| `sql/catalyst/.../codegen/varka/VarkaBatchArgs.java` | new: the layout and the programs | 682 |
| `sql/catalyst/.../codegen/varka/VarkaZoneLowering.java` | new: one offset, the steps masked and with no mask, the table, for each record | 682, 686, 689, 690 |
| `sql/catalyst/.../codegen/varka/VarkaEmitOption.java`, `VarkaEmitOptions.java` | the choice `zoneForm` and the arm with no mask | 682, 689 |
| `sql/catalyst/.../codegen/varka/LongRangeOps.java` | new: the least and greatest live value of a long column | 683 |
| `sql/catalyst/.../codegen/VarkaZoneCompiler.java` | new: the zone's index, the fold of a fixed zone, the declines | 657 |
| `sql/catalyst/.../codegen/VarkaTimeCompiler.java`, `VarkaNodeCompiler.java`, `CompiledVarkaProjection.java` | the `TIMESTAMP_NTZ` arms take a `TIMESTAMP` through `ToLocal`; the list of zone ids | 657 |
| `sql/catalyst/.../codegen/varka/VarkaFamily.java`, `SQLConf.scala` | the family `ZONE` and its switch | 657 |
| `sql/core/.../execution/varka/VarkaKernelRunner.java`, `VarkaEvaluatorBase.java` | the fill, the status, the kernels by form | 682, 685 |
| `sql/core/.../execution/columnar/ArrowCachedBatchToColumnarBatchIterator.scala` | the statistics hand-off | 687 |
| `sql/varka/proofs/` | `zone_step.smt2`, `zone_bucket.smt2` | 684, 686 |
| `sql/varka/spikes/zone-offset/` | the probes of whole kernels and of the three rules, with their results | this plan |

## 5. Tests, and what each is for

Every suite runs at both vector widths through the matrix, and with the memory sanitizer on.

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| the node registries of `m8/VARKA-489.md`'s recipe | each record's `props()`, draw, instance, round trip, encoding or refusal, range arm, price and budget row | a pass that meets a zone node it has no answer for |
| `VarkaBatchArgsSuite` (catalyst, JUnit) | the layout for each form: one run for each distinct node, the same from the emitter and from the runner; no literal slot moves | an emitter and a runner that number the arguments differently |
| `VarkaLongRangeOpsSuite` (catalyst, JUnit) | the pass equals a scalar loop over random columns, null patterns, lengths around a vector and the long extremes | a null row's storage read as a value |
| `VarkaBatchArgRunnerSuite` (sql/core) | two batches with two values give two answers from one class; two tasks do not see each other's values; a warm-up job and a reproduction hold the batch's values | a stale argument, which is a wrong answer no fallback catches |
| `VarkaEmitterZoneSuite` (catalyst, JUnit) | each record in each form, masked and with no mask, against `VarkaReference` over fixture zones and null patterns | a form whose lowering differs from the node's definition |
| `VarkaZoneSweepSuite` (catalyst, JUnit) | every distinct set of rules of the JVM (349 for its 604 ids), Spark's short names and a sample of fixed offsets: each change, two hours either side at one second, the edges of every gap and overlap, the first and last stored change, the edges of each change of the rule era to the year 9999, both ends of Spark's range and of a long; the emitted kernel with its filled arguments against `java.time`, for `ToLocal` and for each rule as its row lands | a zone whose data differs from `java.time` |
| `VarkaTimestampZoneSuite` (sql/core) | each kernel against Spark's row engine under UTC, a fixed offset and the zones of 2.5; a fixture whose rows straddle both changes of a year, in time order and in no order, with nulls; batches that span 0, 1, `K` and `K + 1` changes, a range that ends on a change, one row, all rows equal | a kernel that agrees with Spark on rows that cross no change and on no others |
| the metamorphic checks, in the sweep | `ToLocal` rises strictly inside a segment; every form that covers a batch gives the same rows; and, with row 690, `ToInstantKept` of `ToLocal(x)` with the offset of `x` is `x` for every instant | a rule error both the kernel and a wrong oracle would share |
| `VarkaZonePlantedFaultSuite` (catalyst, JUnit) | each planted fault fails a named test: a threshold one microsecond off, `>` for `>=`, a delta's sign, an argument not refilled, the driver's rules read; and, with row 690, `min` for `max` in a local threshold and the kept offset ignored | a test that passes for another reason |
| `VarkaIrFuzzSuite`, through `VarkaIrGrammar` | zone nodes drawn inside random trees, with a drawn zone and drawn ranges, against `VarkaReference` | a zone node that is wrong under a parent no written test has |
| `VarkaSparkFuzzSuite` | the session zone is a drawn setting of a case, from UTC, a fixed offset, `America/Los_Angeles`, `Australia/Lord_Howe`, `Asia/Kathmandu`, `Pacific/Apia` and `Africa/Casablanca`; a reproducer prints it | a zone-dependent answer in a shape no suite lists |
| `VarkaZoneArgsPropertySuite` (catalyst, JUnit) | a random zone, a random range, random instants biased to the changes: the filled arguments against `java.time`, by seed | a range the sweep's fixed windows do not hold |

The sweep's time is a budget. Its stored changes are 65,794 before 2100 over the 604 ids
(`results/facts.txt`), fewer with each of the 349 sets of rules read once, and two hours either
side at one second is 14,400 instants a change: at most 947 million instants. `java.time`'s
offset costs 10 ns (`results/forms.txt`) and a rule about 43 (2.3), so one core pays about 10 s
for `ToLocal` and about 120 s for the three rules; the kernel's side is under a tenth of that.
The suite runs the sets of rules as a parallel stream, as the calendar sweep of VARKA-281 does.
Prediction 9 registers the time; over it, the one-second step moves to the nightly and the
Build keeps the edges.

Coverage and mutation: JaCoCo over `VarkaZoneLowering`, `VarkaBatchArgs`, `VarkaZoneCompiler`
and `LongRangeOps` with every branch reached, reported in the pull request that adds each; the
mutation run of `sql/varka/mutation` over the programs' `fill`, with each surviving mutant
listed or killed.

Proofs (`sql/varka/proofs/`, rendered by `VarkaProofFiles`, both solvers):

- `zone_step.smt2`: the step with no mask equals the compare and select where `at - 1 - x` is
  a long. The bounds of 3.6 do not give that: it holds where the span of the batch, greatest
  less least, fits a long (`VARKA-684.md` 2.4, with a witness). The file proves it over that
  bound and finds where it first fails. The masked step is exact for every long.
- `zone_bucket.smt2`: the bucket index of an instant inside the table's range is inside the
  table, and the packed bucket's two offsets unpack to the values that were packed.
- The equality with `java.time` is not an SMT claim: it is a property of the JVM's data, held by
  the sweep in every Build, as the milestone plan's 2.2 says.

## 6. The measurement

Baselines first, on the unchanged engine, in row 655's classes. The family's sections:
`hour(ts)`, `minute(ts)`, `second(ts)`, `CAST(ts AS DATE)`, `CAST(ntz AS TIMESTAMP)`,
`CAST(d AS TIMESTAMP)` and `ts + INTERVAL '1' DAY`; each under UTC and `+01:00` over a year in
no order, and under `America/Los_Angeles` over a week, a year in time order, a year in no order
and ten years in no order (`VARKA-655.md` 4.3); with no nulls and with one row in ten null. The
control of a section is its `TIMESTAMP_NTZ` twin where that twin fuses (`VARKA-655.md` 4.7; the
two casts to a `TIMESTAMP` have none): the difference is the zone's price. Numbers are the EPYC
7763's at 256 and
128 bits; the laptop adds 512.

The A/B arms, each named so that the label outlives a default: 2 steps against 8 against the
table on one data set; the pass against the cache's statistics; masked against no mask at 128
bits; the table of three gathers against the packed one; three fields of one timestamp in one
method group and in two.

### 6.1 Predictions, registered before the run

1. `hour(ts)` under `America/Los_Angeles`, a year in no order, at 256 bits on the EPYC 7763:
   at least eight times the row path. The kernel's body is 0.7 ns a row against 25 (2.3); the
   columnar floor around a kernel, about 2.5 ns a row (the catalogue's item 14), bounds the
   ratio.
2. The zone's price over the `TIMESTAMP_NTZ` twin at 256 bits: under 0.5 ns a row with the pass,
   and under 0.3 with the cache's statistics.
3. `CAST(ntz AS TIMESTAMP)` gains more than `hour(ts)`: at least fifteen times the row path at
   256 bits.
4. At 128 bits the masked `hour` is faster than the row path and the form with no mask is at
   least five times it; the masked kept-offset sum is not faster than the row path.
5. The packed table costs under 0.7 ns a row at 256 bits, where three gathers cost 0.9.
6. The table beats the steps from nine changes a batch at 256 bits on the runner, within two of
   the laptop's cross-over.
7. Ten years in no order under `America/Los_Angeles` decline no batch once row 686 has merged,
   and before row 685 they decline every batch.
8. The family takes 12 rows in at most 32 pull requests, none over 1000 counted lines; a
   part that would be larger is split by meaning.
9. `VarkaZoneSweepSuite` adds under 10 s of test time to a Build's bin for `ToLocal` (row 684)
   and under 60 s with the three rules (row 690), on a runner's four cores. One core would pay
   about 10 s and 120 s (section 5). The calibration table's nearest row is the calendar sweep
   of VARKA-281, which ran at 0.08 of its one-core price on the laptop's cores; a runner has
   four, so this takes a half of the one-core price and not 0.08 of it.

## 7. Risks

1. **A stale argument is a wrong answer.** The fallback cannot catch it.
   `VarkaBatchArgRunnerSuite` and the planted fault "an argument not refilled" hold it, and the
   runner fills before every call, the warm-up's included.
2. **The emitter and the runner number the arguments apart.** One layout function serves both,
   and `VarkaBatchArgsSuite` runs it from both sides for each form.
3. **The driver's rules.** A program must resolve its zone on the executor. The planted fault
   "the driver's rules read" plants a second zone data on the compile side.
4. **Zone data newer or older than the oracle's.** The data is built from the running JVM, so a
   kernel and the row engine read one version; the sweep names its tzdata version.
5. **The pass costs what the steps cost** on the cheapest kernels: 0.232 ns a row at four lanes
   (`results/forms.txt`) against the 0.236 of two steps (2.3). Prediction 2 bounds it; row 687
   removes it for a cached column.
6. **A zone with many changes in a year** (`Africa/Casablanca`, 5 in 365 days) needs 8 steps or
   the table from the first batch; the fixture list holds it.
7. **The rule era.** Past the last stored change the data comes from recurring rules. The sweep
   reads every year to 9999, and the table covers 1900 to 2100: an instant outside declines to
   the steps, and a batch of such instants in no order to the row path.
8. **Three records touch every registry.** Rows 682 and 690 follow `m8/VARKA-489.md`'s recipe
   and run every suite that enumerates node types before a push.
9. **The plan ages before it is built** (decision 4). The code facts of section 2 are master's
   of 25 November 2026. Each row's sheet starts with greps of its premises, and a premise that
   fails stops the row.
10. **The sweep's time** (prediction 9): each set of rules is read once, and the one-second step
    moves to the nightly if the budget fails.
11. **The nodes' gain for an e-graph is not measured here.** In this milestone they pay through
    the batch arguments, the forms as options, the range arm and the fuzzer's reference (2.9).

## 8. The rows

Row 657 keeps the first kernels end to end. Each other piece is a row of `m9/PLAN.md` with its
own done-when, so that a row which needs one piece names it by number. "Needs" means that the
named row's kernels have merged. A row is one to five pull requests; none starts before the
owner's word (decision 4).

| row | what it builds | needs | pull requests |
| :--- | :--- | :--- | ---: |
| 657 | this plan and its probes (two pull requests, now); then the first kernels end to end: the zone's index and the fold of a fixed zone, `hour`, `minute`, `second` and `CAST(ts AS DATE)`, the zone programs over row 678's data, `VarkaTimestampZoneSuite`, the coverage rows, census cells, docs and benchmark sections | 682, 683, rows 656, 678 and 655 | 2 and 4 |
| 682 | `ToLocal` and its batch arguments: the record and its registries; the layout, the emitter's read and the runner's fill; the lowering of one offset and of the steps | 683, for the runner's fill | 4 |
| 683 | `LongRangeOps`: the range of a long column | - | 1 |
| 684 | the proof, for `ToLocal`: the sweep and the metamorphic checks; `zone_step.smt2` and the planted faults; the fuzzers' zone and the property suite | 657 | 3 |
| 685 | a kernel for each batch: the evaluator owns a list of kernels, a refactoring that moves no emitted byte; then the ladder; last, the fields that need no offset, if they gain (3.5) | 657 | 2 |
| 686 | the table form: its lowering with a packed bucket and a clamped index, `zone_bucket.smt2`, and the form as the ladder's last | 685 | 3 |
| 687 | the cache's statistics reach the evaluator and replace the pass | 657 | 1 |
| 688 | the range of a computed operand for each batch | 657 | 2 |
| 689 | steps with no mask at two lanes; the recorded refusal where a form loses | 657 | 4 |
| 690 | `ToInstant` and `ToInstantKept`: the records and their registries; `CAST(ntz AS TIMESTAMP)`; `CAST(d AS TIMESTAMP)`; `ts + INTERVAL DAY`; each rule in the sweep, with its planted faults and the there-and-back check | 657, and 688 for the last two | 9 |
| 691 | each form measured in kernels at 256 and 128 bits; the defaults by width; G7 for `hour` and the cast to `DATE`; the family's outcome, section 9 | 685 to 690 | 1 |
| 692 | a conversion shared across method groups: measured, and the store and load built only if it pays | 691 | 2 |

The paths that set the length: 683, 682, 657, then the rows that need only 657 (684 to 690) side
by side, then 691. Row 683 needs no other row, and row 682 needs only row 683. Row 669's casts,
row 670's zone functions and row 671's sums wait for row 690, and row 668's fields for row 657.

The refactorings are parts of their rows: the evaluator's list of kernels (685), `hourOf`,
`minuteOf` and `secondOf` taking a local value (657), and a reproduction that records the arrays
a kernel ran with (682).

## 9. Outcome

*To be written when the measurement lands.*
