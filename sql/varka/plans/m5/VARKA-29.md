# VARKA-29: the long lane, reachable from SQL

*Milestone 5, section 2.3. Opened 17 September 2026, after VARKA-85's step 4, and
narrowed the same day on the owner's decision to three types and comparisons.*

## 1. Where this came from

Milestone row 29, and the spine section 1.1 sorts to: `117 -> 84 -> 85 -> 29,
with 28 and 92 beside it -> 88 -> 102, 103, 104 -> 105`. VARKA-85 gave the
emitter a second lane and proved it computes what the reference says at both its
widths; nothing can reach it. No compiler arm admits a column of any 64-bit type,
so every `bigint`, `TIME` and `INTERVAL DAY TO SECOND` query runs on the row
engine, and will keep doing so however much of the milestone is built above it.

Two tasks handed this one its starting line. `VARKA-116.md` 2, having proved
a `TIME(p)` column survives Varka's Arrow cache and maps as eight-byte lanes,
names `isArrowBacked`'s vector-class allowlist as "the first line VARKA-29
changes" and deliberately did not touch it. `VARKA-142.md` 9 committed what
the lane costs per shape, so the number this task's admission rule calls for -
"the halved-headroom number committed rather than discovered" - is on disk
before the work starts: 1.5x to 2.0x in cache, 2.11x to 2.20x out of it.

**What this task is, after the narrowing.** The plumbing that lets a column of
the milestone's three long types - `bigint`, `TIME`, day-time intervals - reach
a kernel, and comparisons over them, because comparisons are the smallest
operation that proves the plumbing end to end. Nothing else. The first draft of
this plan covered all five `PhysicalLongType` types and arithmetic on two of
them; the owner's reading was that "one task covers too much things ... almost
entire milestone and even more", and that `TIMESTAMP_NTZ` was never planned for
this milestone. Section 2.1 records the narrowing and where each piece went.

## 2. The admission check, done

Read in the tree on 17 September 2026, at master `a6c2c373089` with #236 applied.
The question is which layers already carry a 64-bit column and which refuse it,
because that decides whether this task is a compiler change or an Arrow change.

**The emitter is done, and that is measured rather than assumed.** Twelve node
types build and verify at `LaneType.LONG` - `ColumnRef`, `LiteralSlot`,
`IntArith`, `IntNeg`, `Greatest`, `Least`, `Compare`, `And`, `Or`, `Not`,
`IsNotNull`, `IfElse` - and `VarkaLaneTypeSuite` asserts that set exactly. The
long matrix drives all of them against `evalLong` at 2 and 8 lanes over four
batch lengths and every null-pattern combination. Comparisons need `Compare`,
the three logical nodes and `IsNotNull`, all of which are in that set. **So this
task should need no emitter change at all.** That is prediction 6.1.1, and it is
the cheapest one to falsify.

**The IR already keeps a kernel single-lane, without anyone asking it to.** A
node's constructor refuses operands whose lanes disagree, `laneOf` refuses output
roots that disagree, and `VarkaLoopEmitter.fitsBudgets` - which the compiler
consults before letting an entry join the kernel - refuses a mixed output list.
The compiler's admission loop therefore already does the right thing: the first
fused entry fixes the lane and an entry of the other lane is demoted to residual.
What it does not do is say so. The decline it records is "exceeds the emitter's
fused budget", because that is the branch `fitsBudgets` returning false lands in.
The fix is small but it is not one string: `fitsBudgets` answers a boolean, so
the compiler cannot tell a lane mismatch from a budget breach, and four
assertions in `VarkaExpressionCompilerSuite` pin the budget reason for cases
that really are budget breaches. The compiler checks the lane itself, before
`fitsBudgets`, and records its own reason. That is the only compiler behaviour
this task changes rather than adds.

**The Arrow path is width-agnostic already.** `extractMorsel` takes a
`BaseFixedWidthVector` and maps its data and validity buffers by address, so an
eight-byte vector maps through the same call as `DateDayVector`; VARKA-116 proved
the composition end to end for `TimeNanoVector` and `DurationVector`, including
the all-null case that passes no validity segment at all. Nothing in the cache,
the serializer or the column vector needs a change for this task.

**Four places refuse a 64-bit column, and all four are lists.**

| where | what it is today | why it refuses |
|---|---|---|
| `VarkaExpressionCompiler` type gates | `dataType == DateType`, `== IntegerType`, `isInstanceOf[YearMonthIntervalType]` | no arm builds a `LONG` leaf |
| `VarkaKernelEvaluator.isArrowBacked` | allowlist of `DateDayVector`, `IntVector`, `IntervalYearVector`, `VarCharVector` | a `BigIntVector` input declines the batch |
| `VarkaKernelEvaluator.allocateVector` | `DateType`, `IntegerType`, `YearMonthIntervalType` | no destination vector for a long output |
| `VarkaKernelEvaluator.invokeFused` | calls the seven-argument `run` with `Array[Int]` literals | a long kernel throws `UnsupportedOperationException` by design |

Each is short and each is a list rather than a computation, which is what makes
this task plumbing rather than a redesign, and what makes the wrong move fail
loudly: `fitsBudgets`'s lane refusal and the module map of VARKA-141 both bite
before a wrong kernel runs.

**What the check would have rejected.** If `extractMorsel` had baked a four-byte
stride, or the serializer had stored `TIME` as anything but a fixed-width vector,
this task would have started in Arrow rather than in the compiler and would have
been three times the size. VARKA-116 exists precisely so that this paragraph is a
fact rather than a hope.

**The three types are three of five, and the other two are out by decision.**
`PhysicalDataType` answers `PhysicalLongType` for `LongType`, `TimestampType`,
`TimestampNTZType` and `DayTimeIntervalType` in its default switch, and for
`TimeType` through the `TypeOps` registry (`TimeTypeOps.getPhysicalType`,
nanoseconds since midnight). Nothing else in the tree does; the nanosecond
timestamps `TimestampNTZNanosType` and `TimestampLTZNanosType` have a sixteen-byte
physical type of their own and are stored by `ArrowWriter` as a `StructVector`,
so they are not one lane of anything. Of the five, the two timestamp types share
the lane physically and are outside this milestone (section 8 of the milestone
plan, `m8/SCOPE.md` item 31): the compiler declines them with a reason
that says so, and section 3.2 records the semantic finding the next milestone
inherits with them.

**One thing the check found that is not about this task.** When a Varka filter
passes a batch on, every column is compacted to the selected rows, and the
vectorised `compress` path (`VarkaKernelEvaluator.scala:1304`) is a width check
serving four-byte vectors only; an eight-byte column takes `compactFixed`'s
per-row `copyFromSafe`. That is the case *today*, for every `bigint` column that
merely sits in a filtered table, whatever the filter is on. It is VARKA-128's
subject - 64-bit compaction - and is recorded there.

### 2.1 What review and the owner's reading corrected, 17 September 2026

The first draft admitted all five `PhysicalLongType` types, with comparisons on
each and, for `TIMESTAMP_NTZ`, subtraction and interval addition. Review against
the tree found two of its claims wrong in the direction that matters: it said
differences on zoned `TIMESTAMP` were safe because instants subtract
identically in any zone, and Spark's `SubtractTimestamps` subtracts *local*
date-times in the session zone; and it admitted `ts + INTERVAL` without
restricting the type, when `timestampAddDayTime` adds calendar days in the zone.
Either would have produced a kernel wrong by an hour on the rows that cross a
DST transition and right everywhere else. The draft also overstated three
things: boolean projection outputs, which no lane supports; the lane-mismatch
decline as "one string"; and the compaction finding, which is every forwarded
eight-byte column today and belongs to VARKA-128, not to a new row (a row 144 was
opened for it and withdrawn the same day).

The owner then read the corrected plan and narrowed it: one task should not
carry the whole lane, and the timestamp types were never in this milestone. So:
the two timestamp types leave the milestone (`m8/SCOPE.md` item 31,
carrying the DST finding as the argument they arrive with); `bigint`
arithmetic, `bi + 1` included, is VARKA-104's; every `TIME` operation beyond a
comparison is VARKA-102's; every interval operation beyond a comparison,
`interval - interval` included, is VARKA-103's. What is left is section 3.

## 3. The design

### 3.1 The mechanism

Five changes, in the order a batch meets them.

**(a) One mapping from a Spark type to a lane, in one place.** A private
`laneOf(dataType): Option[LaneType]` in `VarkaExpressionCompiler`: `IntegerType`,
`DateType` and `YearMonthIntervalType` to `INT`; `LongType`, `TimeType` and
`DayTimeIntervalType` to `LONG`; the two timestamp types to `None` with the
decline reason "a timestamp column is outside milestone 5", so a reader of
EXPLAIN learns it is a decision and not a gap; everything else `None` with the
existing reason. Every leaf-building arm asks this instead of testing a type,
so admitting a type later is one line here and nothing else.

**(b) The lane's own literal table.** `CompiledVarkaProjection.literals` is
`Seq[Int]` and the evaluator passes it as `scalarArgs`. A long shape's literals
are 64 bits - `bi > 5000000000`, `t < TIME '12:00'`, `dt > INTERVAL '1' DAY` -
so the plan gains `longLiterals: Seq[Long]` beside it and the evaluator fills
`longArgs`. `LiteralSlot.index` addresses the table of its own lane, which is
well defined because a kernel is single-lane: exactly one of the two tables is
ever read by a given kernel. The compiler interns into the table its leaf's
lane names.

**(c) The gate.** `isArrowBacked` admits `BigIntVector`, `TimeNanoVector` and
`DurationVector` by class, exactly as it admits `IntervalYearVector` - by class
rather than by Spark type, since the buffer layout is what the kernel reads.

**(d) The destination.** `allocateVector` gains the matching arms: `BigIntVector`
for `LongType`, `TimeNanoVector` for `TimeType`, `DurationVector` for
`DayTimeIntervalType` - the classes `ArrowWriter` already pairs with those
types, so the row path reads them back through the accessors
`ArrowColumnVector` already has. A destination is needed even though this task
adds no arithmetic: `greatest`, `least` and `CASE WHEN` produce a long column.

**(e) The call.** `invokeFused` picks the overload from the compiled plan's lane:
the seven-argument `run` at `INT`, the eight-argument one at `LONG`. The plan
carries the lane rather than the evaluator deriving it per batch.

**The SQL this admits.** Over `bigint`, `TIME(p)` and `INTERVAL DAY TO SECOND`
columns: the five comparisons `=`, `<`, `<=`, `>`, `>=` against a column or a
literal, as a filter's conjuncts and as the condition of a `CASE WHEN`; `IS NULL`
and `IS NOT NULL`; `AND`, `OR`, `NOT` over those; `greatest` and `least`; and
`CASE WHEN ... THEN col ELSE literal END`. All of it is the twelve node types
with no arithmetic node among them. A bare column (`SELECT bi`) is forwarded
zero-copy as today and is not an operation.

### 3.2 What is deliberately unchanged

* **The emitter.** If this task changes a byte of `VarkaLoopEmitter`, prediction
  6.1.1 was wrong and the reason belongs in section 9.
* **The timestamp types**, both of them, declined with the milestone reason.
  The finding that goes with them, for whoever admits them: on a zoned
  `TIMESTAMP`, Spark's `SubtractTimestamps` evaluates
  `ChronoUnit.MICROS.between(localStart, localEnd)` in the session zone and
  `TimestampAddInterval` evaluates `.atZone(zoneId).plusDays(days)`, so across
  a DST transition neither equals the instant arithmetic a long kernel would do;
  only comparisons are zone-independent. The `TIMESTAMP_NTZ` family is evaluated
  in UTC (`zoneIdForType`), which makes its arithmetic plain, exact and checked.
  Both facts were read in `datetimeExpressions.scala` and `DateTimeUtils.scala`
  on 17 September 2026.
* **All arithmetic**, at every one of the three types: `bigint` under the 2.15
  lattice is VARKA-104, whose whole subject is the overflow-mode decision that
  admitting even `bi + 1` here would make by accident; `interval - interval` and
  interval scaling are VARKA-103; every `TIME` operation - `hour(t)`, `time_trunc`,
  `make_time`, `t + interval` - is VARKA-102, and the divisions those need are 88.
* **Mixed-lane trees.** `cast(int AS long) + long` stays declined; the width
  conversion and its loop-shape measurement are VARKA-28. The decline gets an
  honest reason here, which is what makes VARKA-28's arrival visible as a change
  in behaviour rather than a change in speed.
* **Boolean projection outputs** (`SELECT bi > 5`) are declined at every lane
  today and stay so; they are milestone 6's item 27, a destination-vector
  question rather than a lane one.
* **The width-8 compaction path** is VARKA-128's, section 2's finding folded into
  its row: it affects every eight-byte column forwarded through any Varka
  filter today, so it is neither this task's to fix nor this task's to price.
* **The calendar family**, which stays `INT` and keeps `Lane.requireInt`.

### 3.3 Registered op counts

Not applicable, and deliberately so: no emitted method changes. The standing
oracle (`VarkaEmittedBytesSuite`, `sql/varka/emitted_bytes.json`) must stay green
without regeneration, which is this task's equivalent of an op count.

## 4. Files

| file | what |
|---|---|
| `VarkaExpressionCompiler.scala` | `laneOf`, the three leaf arms, the long literal table, the lane-mismatch and the timestamp decline reasons |
| `VarkaKernelEvaluator.scala` | `isArrowBacked`, `allocateVector`, `invokeFused`, the `longArgs` array on `FusedRunner` |
| `VarkaLongLaneSuite.scala` (new, `sql/core`) | the end-to-end suite of section 5 |
| `VarkaExpressionCompilerSuite.scala` | the compiler-level admissions and declines |
| `sql/varka/coverage.json` | the long columns - `t`, `t2`, `l`, `dt` as milestone section 2.37 names them, plus `l2` and `dt2` for column-to-column comparisons - and their rows, regenerated |
| `sql/varka/plans/m5/PLAN.md` | row 29 |

## 5. Tests, and what each is for

1. **The differential, per type.** For each of the three types, a fixture table
   with the three null patterns the date suites use, cached through the Arrow
   serializer, running each admitted comparison shape with Varka on and off and
   comparing rows. Catches a lane that reads the right bits in the wrong unit -
   a `TIME(3)` column compared against a literal Catalyst cast from another
   precision - which no kernel test can catch, because the kernel never sees the
   unit. `TIME` runs at `p` in {0, 3, 6, 9} with literals of a different `p`.
2. **The fused-not-declined assertion.** Each of those queries asserts it
   actually fused (the decline map is empty and the plan shows the Varka node),
   because a differential passes trivially when everything falls back.
3. **A mixed-lane projection.** `SELECT greatest(d, d2), greatest(l, l2)` fuses
   one lane, leaves the other residual, and the decline reason names the lane
   rather than the budget.
4. **A timestamp comparison declines with the milestone reason**, on both
   `TIMESTAMP` and `TIMESTAMP_NTZ`, and answers what the row engine answers -
   the "demonstrably declined, not wrong" half of the row's admission rule, in
   the form the narrowing leaves it.
5. **A long filter**, where the surviving rows go through the per-row compaction
   of section 2, over a batch with nulls and a `bigint` column the filter does
   not read: proves the fallback is correct even though it is not fast.
6. **Both vector widths**, by running the new suite under the narrow width the
   gate already uses, since a long lane at 128 bits is two lanes and the epilogue
   does more of the work.
7. **The standing oracles unchanged**: `VarkaEmittedBytesSuite` green without
   regeneration, and `VarkaCoverageSuite` regenerated with the new rows reviewed
   in the diff.

## 6. The measurement

**The per-shape lane cost is already committed** - `VARKA-142.md` 9 and
`VarkaLongLaneBenchmark-jdk25-results.txt` - so this task does not re-measure it
at the kernel. The first end-to-end number on the lane belongs to VARKA-105's
`TimeSurfaceBenchmark`, the file the public message quotes. This task therefore
runs no new benchmark and commits no new results file; it re-runs the parity
gates at both widths, which is a correctness gate rather than a measurement.

### 6.1 Predictions, registered before the work

1. **The emitter diff is zero lines.** Comparisons, the logical nodes,
   `IsNotNull`, the hull ops and `IfElse` are all in the twelve.
2. **A `bigint` comparison filter over an Arrow-cached table fuses and declines
   no batch**, and its end-to-end throughput is between 0.45x and 0.60x the same
   filter over an `int` column of the same row count. VARKA-142's kernel ratio is
   2.11x to 2.20x out of cache, and end to end the cached column's bytes double
   as well while the per-batch fixed costs do not; the band holds both effects
   and still catches a lane that costs three times, which is what the
   single-rung measurement first said.
3. **The first failure will be in the literal table**, not in the lane: two
   tables under one index space is the one place in this design where a wrong
   answer is silent rather than loud. Test 1 is what catches it.

## 7. Risks

1. **A literal interned into the wrong table** reads a neighbouring slot's value
   and answers plausibly wrong. Test 1 over a fixture whose literals differ per
   row-group catches it; a `require` that the plan's two tables are never both
   non-empty makes it structural, since a kernel is single-lane.
2. **Admitting at the gate what the compiler will not fuse.** Widening
   `isArrowBacked` before the compiler builds long leaves would decline batches
   one layer later and slower (VARKA-116's warning). Both move in the same commit.
3. **`TIME(p)` and precision.** Every precision is nanoseconds in the lane and in
   the literal - `TimeType`'s physical value is already nanos - but Catalyst
   inserts casts between precisions, and a comparison of a `TIME(3)` column with
   a `TIME(6)` literal reaches the compiler through one; the compiler must see
   through it or decline it, never drop it. Test 1's mixed-precision literals
   hold it.
4. **The coverage fixture's ordinals.** Adding the long columns to the
   shared `childOutput` renumbers every ordinal after them, which VARKA-122's
   review found breaks tests that assert an ordinal; the new columns go at the
   end, and any test that needs its own layout builds its own list.
5. **The epilogue's method size.** A long lane halves the lanes per group, so a
   batch takes twice the groups and the epilogue grows; the 64KB method limit is
   a known open case (milestone 5's register, at the owner's request). A wide
   long projection is the first shape likely to reach it, and the failure is
   loud.
6. **This plan assumes #236.** The single-lane demotion in section 2 rests on
   `fitsBudgets` refusing a lane mix, which VARKA-85's step 4 added; nothing in
   section 8 starts before it merges.

## 8. Sequencing

After #236 (VARKA-85, step 4) merges, and not before - risk 6. Each commit green
on its own, and each one alone is a working decline:

1. `laneOf`, the three long leaves and the timestamp reason in the compiler,
   with every long expression still declining for want of a gate - the compiler
   suite's admissions and declines only.
2. The long literal table through the plan and the evaluator, with the
   eight-argument call; still no gate, so nothing runs it yet.
3. The gate and the destination vectors, both in one commit (risk 2): the first
   commit where a `bigint` filter fuses end to end.
4. The lane-mismatch decline reason, the coverage table regenerated, the
   milestone row, section 9.

## 9. Outcome

Landed 17 September 2026, in the three steps section 8 asked for plus this
section: the compiler (`d021820890a`), the evaluator with the end-to-end suite
(`7b209b8d012`), the coverage table (`c9ae233cbf9`). A `bigint`, `TIME(p)` or
day-time interval column read from the Arrow cache now reaches a kernel, and
the comparisons, hull ops and `CASE WHEN` over it answer what the row engine
answers - 34 end-to-end shapes over three null patterns under both consumers,
16 new coverage rows through the differential corpus (78 tests, from 58), and
104 compiler tests, from 95.

**The four lists were the whole task, as section 2 said.** `laneOf` in the
compiler, three vector classes in `isArrowBacked`, three destinations in
`allocateVector` built from Spark's own Arrow field for the type, and the
eight-argument `run` behind a branch on the plan's lane. The long literal
table lives in the sink beside the bounds and follows their rollback, and the
compiled plan carries it with a `require` that at most one of its two tables
is populated.

### 9.1 The predictions, scored

1. **Right.** The emitter diff is zero lines: `git diff origin/master --stat
   -- sql/catalyst/src/main/java` is empty. Every shape this task admits is the
   twelve node types VARKA-85 shipped.
2. **Scored on 18 September 2026, and wrong** (`VARKA-144.md` 9). The band
   was 0.45x to 0.60x for a `bigint` comparison filter against its int twin. No
   case meets it. At two million rows the lane costs 0.73x to 0.96x, because the
   Arrow cache read, the batch machinery and the filter's plumbing are most of
   the work and none of them doubles with the lane; at twenty million rows one
   filter stays at 0.97x while another falls to 0.31x, below the band and below
   the kernel ratio. The prediction's error was its model, not its arithmetic: it
   carried VARKA-142's kernel ratio up a layer as though the rest of the query
   scaled with it, and the end-to-end cost turns out to depend on the shape by a
   factor of three. What the shapes differ in is a forwarded column, which is
   VARKA-145.
3. **Wrong, and in an instructive direction.** The literal table never
   failed. The first failure was in the suite's own SQL (a literal prefix), and
   the second was a shape the plan did not list at all: `dt < INTERVAL '0'
   SECOND` fused end to end and declined in the coverage suite, because the
   coverage suite compiles the *analyzed* form, in which the SECOND-typed
   literal sits behind a `Cast` to the column's DAY TO SECOND that the
   optimizer folds before a query runs. The place a wrong answer could hide
   silently was guarded well enough that nothing hid there; the place a
   *decline* hid was one the plan had not thought to look.

### 9.2 What moved that the plan did not list

* **The day-time interval unit relabel.** `Cast.castToDayTimeInterval` keeps
  the microseconds whole for a SECOND end field
  (`SparkIntervalUtils.durationToMicros`: `case DT.SECOND => micros`) and
  truncates to the unit for a coarser one. The compiler sees through the first
  as the twin of the year-month MONTH relabel and declines the second with its
  reason. Found by the coverage suite, pinned by a compiler test both ways.
* **The lane guard inside one expression.** Section 3.1 planned the
  entry-versus-kernel check; the IR's constructors also refuse a mix *inside*
  an expression, and `CASE WHEN l > 0 THEN d ELSE d2` type-checks with its
  condition on one lane and its branches on the other. `sameLane` asks before
  `IfElse`, `And` and `Or` are built and records its own reason, so a
  well-typed query declines instead of throwing in the planner.
* **`columnRef` takes the lane** rather than the plan's separate
  `longColumnRef`; and the coverage fixtures gained `l2` and `dt2` beside the
  `t`, `t2`, `l`, `dt` the milestone named, because a column-to-column
  comparison needs two of each type.
* **The int arms kept their own type tests.** Section 3.1 (a) said every
  leaf-building arm would ask `laneOf`; the date, int and year-month arms
  carry distinctions `laneOf` flattens (a date is not an int operand), so they
  were left as they are and `laneOf` names the lane for the long arms, the
  timestamp refusal and the mismatch messages. Recorded rather than done
  silently.
* **The emitted-bytes oracle lists the new rows as skipped.** `VarkaEmittedBytesSuite`
  walks the coverage table and pins the int32 shapes; the sixteen long-lane rows
  are not int32 shapes, and a newly skipped row counts as a change, so
  `sql/varka/emitted_bytes.json` was regenerated. The whole diff is that one
  list, `coverage_rows_skipped`, from empty to the sixteen: no int32 hash
  moved, which is section 3.3's op-count equivalent holding.
* **`coalesce` over long columns fuses**, though section 3.1's list did not
  name it: it is `IfElse(IsNotNull(a), a, b)` over the same leaves, and
  refusing it would have taken an arm whose only purpose was to refuse
  correct code. Not added to the coverage table, since the table lists what
  the task set out to cover.

### 9.3 What this leaves for later

The end-to-end number (VARKA-105); `IN` over a long column and every `TIME`
operation beyond a comparison (VARKA-102); interval arithmetic (103); `bigint`
arithmetic, `l + 1` included (104); the width-8 compaction path a long filter's
survivors take (128); and the two timestamp types (`m8/SCOPE.md` item
31). The lesson about the two forms the suites compile is in `SKILLS.md`.
