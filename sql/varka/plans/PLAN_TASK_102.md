# Task 102: `TIME` expressions over the long lane

*Written 18 September 2026. Section 2.37 of `PLAN_MILESTONE_5.md` opened this
task on 15 September 2026 by the milestone's re-scope, which calls it "the
milestone's subject", and adjusted it on 17 September: comparisons and
`CASE WHEN` over a `TIME` column landed with task 29.*

## 1. Where this sits

This is the next task on the milestone's own spine - `88 -> 102 / 103 -> 105 ->
121 -> 118` - and everything after it waits: the `TIME` benchmark, the AVX2 arm,
and the closing task that writes the public post. The post is about `TIME`; this
task is what it is about.

`TimeType(p)` stores nanoseconds since midnight in a long whatever `p` is, so
every value lies in `[0, 86 399 999 999 999]`, below 2^47 - which is what makes
every division a `TIME` expression needs exact through task 88's double-lane
route with no bound to prove and nothing to decline.

*The lane is **not** task 29's unchanged, which is what an earlier draft of this
section said. Section 2.4 corrects it, and the correction reorders the task.*

## 2. What the section did not know

### 2.1 No TIME expression reaches the compiler under its own name

`HoursOfTime`, `MinutesOfTime`, `SecondsOfTime`, `SecondsOfTimeWithFraction` and
`MakeTime` are **`RuntimeReplaceable`** - and so, the correction at the end of this
section records, are the other four. Each rewrites itself into a
`StaticInvoke` on `DateTimeUtils` - `getHoursOfTime`, `getMinutesOfTime` and so
on - and the optimizer's `ReplaceExpressions` runs long before physical planning.
So a compiler arm matching `case HoursOfTime(child)` would never fire in a real
query. The compiler's existing `RuntimeReplaceable` arm unwraps to `.replacement`
and is documented as defensive, for hand-built test expressions; in production it
is the `StaticInvoke` that arrives.

**The compiler has never matched a `StaticInvoke`** - the count is zero today.
Every arm it has keys on a Catalyst expression class. So this task's first design
decision is how to recognise these five.

**Not by a hardcoded method name.** `StaticInvoke` carries `staticObject: Class[_]`
and `functionName: String`, so `(classOf[DateTimeUtils.type], "getHoursOfTime")`
identifies the target exactly - and writing that pair as a literal binds Varka to
a helper name upstream may rename without ceremony, after which fusion stops
silently and the only symptom is a benchmark that got slower. That is the failure
mode this project keeps finding in its own tooling, most recently in
`dev/is-changed.py`, which answers `false` for a module that does not exist
exactly as it does for one that did not change.

**Derive the table from the Catalyst classes instead.** The replacement is
produced by the expression itself, so the compiler can ask it rather than
guessing:

    HoursOfTime(dummy).replacement  ->  StaticInvoke(DateTimeUtils, "getHoursOfTime", ...)

Building the lookup as `{ (si.staticObject, si.functionName) -> field }` by
constructing each `RuntimeReplaceable` once and reading its own `.replacement`
means the key is generated from the same source that produces the query's
expression. **An upstream rename is then followed automatically**, because both
sides move together, and no string is written down twice.

Two ways it can still break, and both become loud rather than silent: if a
replacement stops being a `StaticInvoke` at all, the table construction has
nowhere to put it and fails at class-initialisation; and if two entries collide on
one key, the map catches it. A test still asserts end to end that the expression
Spark produces for `hour(t)` - built by running the analyzer and optimizer over
SQL, not by constructing the node by hand - is one the compiler matches.

*Corrected 18 September 2026, starting the implementation: there are no such
four. **All nine are `RuntimeReplaceable`** - `TimeTrunc`, `SubtractTimes`,
`TimeAddInterval` and `TimeDiff` too. The first draft's check read only the first
`extends` on each declaration, found `BinaryExpression` and `TernaryExpression`,
and never saw the second trait. So the `StaticInvoke` table above is not group
B's cost: it is the **prerequisite for every expression in this task**, which
makes the task simpler than planned - one mechanism, applied uniformly - and
moves the whole of it behind that one piece of work. Section 6 is re-grouped
accordingly.*

### 2.2 The reference is `LocalTime`, not Spark's own code

`getHoursOfTime` is `nanosToLocalTime(nanos).getHour` - it goes through
`java.time.LocalTime`, not through a division. Section 2.37 describes the
extracts as "a division and a `floorMod`", which is the right *lowering* and is
not what Spark computes.

Two consequences. Varka is **re-deriving** the arithmetic rather than mirroring
an implementation, so the differential oracle must be `LocalTime` itself, the way
the calendar family's oracle is `LocalDate` - a transcription of Spark's helper
would prove only that two transcriptions agree. And the exhaustive sweep is
cheap here in a way the calendar's was not: a `TIME` value has 86.4e12 possible
nanosecond values, far too many, but the *fields* change only at second
boundaries, so sweeping all 86 400 seconds of a day plus the sub-second edges
covers every distinct answer.

### 2.3 Two expressions are blocked on a representation, not on a lane

`SecondsOfTimeWithFraction` returns a `Decimal` and `TimeToSeconds` returns
`DecimalType(14, 6)`, and it is worth being exact about what stops them, because
"Varka has no decimal lane" is the wrong reason and points nowhere.

Spark's own `Decimal` holds a precision of 18 or less as an unscaled **long**, so
the *value* would sit in the lane this task already uses. What does not fit is the
**column**: `ArrowUtils` maps every `DecimalType` to
`new ArrowType.Decimal(precision, scale, 8 * 16)` - a 128-bit Arrow vector,
sixteen bytes per row, whatever the precision. A Varka output column is read and
written through that Arrow buffer, so the blocker is the representation and not
the arithmetic or the lane width.

That makes these two a **roadmap item rather than a dead end**, and it lines up
with the project's stated aim of several representations per logical type: a
Decimal128 representation, or a narrow-decimal one that keeps a long buffer and a
scale, would admit them unchanged. Until then they decline, and the decline names
the Arrow representation - the same care task 89 took over
`extract(MONTH FROM ym)`, where a reader who saw "declined" would otherwise
conclude the division was still missing.

### 2.4 The three headline extracts are mixed-width kernels

Read from `timeExpressions.scala` rather than assumed:

| expression | input | output | lane |
|---|---|---|---|
| `hour(t)`, `minute(t)`, `second(t)` | TIME, int64 | **`IntegerType`** | **mixed** |
| `make_time(h, m, s)` | int32, int32, **`DecimalType(16, 6)`** | TIME, int64 | **mixed**, and a decimal operand |
| `time_trunc(unit, t)` | TIME | `TimeType` | same |
| `t1 - t2` | TIME | `DayTimeIntervalType(HOUR, SECOND)` | same |
| `timediff(...)` | TIME | `LongType` | same |
| `t + dt` | TIME | `TimeType` | same |

So the three expressions this milestone calls its subject produce an int32 from
an int64 lane, and **nothing in Varka emits a mixed-width kernel today**. They
depend on task 28, which is planned and not built. An earlier draft of section 1
asserted the opposite.

### 2.5 A narrower way in than task 28, worth settling first

The extracts may not need task 28's full bi-lane kernel. Their computation stays
entirely in long lanes; only the **store** narrows. Driving the loop at the long
species and emitting a single `L2I` at the store is a much smaller change than
the pair representation - the loop keeps one trip count, no value is held as two
halves, and no slot pressure doubles.

It is not free, and the store path says why. Today one `s.byteOffset` is shared
by every output and the store writes at the lane's element width, so a narrow
output needs its own offset; and `L2I` part 0 leaves the quotient in the low half
of a full-width `IntVector`, so a dense store would write twice the bytes wanted
and the store must be masked to the long species' lane count.

Both are contained, and neither touches the general mixed-lane machinery. **This
is the first thing to settle**, because it decides whether the headline
expressions wait for task 28 or ship before it.

### 2.6 What each expression needs, read from the helpers

*Also 18 September 2026. The first draft grouped by lane and by matching; neither
was the operative constraint for four of the nine.*

`DateTimeUtils` says what the lowering has to do, and division is the divider:

    subtractTimes(end, start) = (end - start) / NANOS_PER_MICROS
    timeDiff(unit, start, end) = (end - start) / getNanosPerTimeUnit(unit)
    timeTrunc(level, nanos)    = truncatedTo(level), i.e. (n / u) * u
    timeAddInterval(t, iv)     = addExact(t, multiplyExact(iv, 1000)), then a range check

So three of the four the first draft called "ordinary" need a **long-lane
division** - task 88's step 3, which section 3 already brings into this task -
and the one that needs no division at all is `t + dt`, which the first draft
deferred to group C.

The real dependency map, which is what section 6 now groups by:

| needs | expressions |
|---|---|
| the `StaticInvoke` table | **all nine** |
| long-lane division (88 step 3) | `time_trunc`, `t1 - t2`, `timediff`, `hour`, `minute`, `second` |
| narrowing (2.5's question, or task 28) | `hour`, `minute`, `second` |
| widening (task 28) | `make_time` |
| an Arrow decimal representation | `second_with_fraction`, `time_to_seconds` |
| **nothing beyond the table** | **`t + dt`** |

`make_time` needs no division either - it is two multiplies and two adds - so
what holds it is the widening alone.

## 3. Where task 88 step 3 lands: here

Task 88's step 3 - the long-lane converts, the `useAVX` option field and the AVX2
magic-number form - **has no caller of its own**. The IR has no division node
that reaches the long lane, and step 3's stated test, "the `TIME` divisors'
parity at both AVX levels", needs this task's nodes. Building it separately would
produce unreachable code tested by nothing.

So it lands here, and this plan owns it. What it needs is already established and
needs no new investigation:

* `dev/varka_canary/L2DProbe.java` pins the long round trip: `L2D`/`D2L` at
  `part 0` both ways, `D2L` truncating, and the converts failing to inline under
  `-XX:UseAVX=2`.
* `dev/varka_canary/MagicProbe.java` pins the AVX2 fallback for `/3.6e12`: no
  conversion instruction at all, 0 wrong quotients over 65 536 nanos-of-day at
  4 and 8 lanes.
* `verify_double_division.py` certifies every `TIME` divisor exact under both
  double forms.

What is genuinely open is `useAVX` in the shape key. Task 88's plan noted it must
enter the key so one committed `emitted_bytes.json` can pin both hosts; the
wrinkle is that a per-host default makes `VarkaEmitOptions.DEFAULTS` host-
dependent, and `canonical()` renders empty for the defaults, so two hosts would
render the same key for different bytes - the exact bug the key exists to
prevent. The resolution is a fixed default in `DEFAULTS` with the session setting
it explicitly, so a host that differs renders a non-empty `canonical()` and gets
its own key. That is a decision to make before the AVX2 form is written, not
after.

## 4. The expressions, and what each is in the lane

*Every `TIME` expression Spark has. Section 6 gives the order: group A ships
first, B carries the width change and the `StaticInvoke` table, C waits - `t + dt`
on an upstream question and the two decimal-returning ones on an Arrow
representation.*

| expression | lowering | note | group |
|---|---|---|---|
| `hour(t)` | `/ 3.6e12` | `StaticInvoke`, 2.1 | B |
| `minute(t)` | `/ 6e10` then `floorMod 60` | `StaticInvoke` | B |
| `second(t)` | `/ 1e9` then `floorMod 60` | `StaticInvoke` | B |
| `make_time(h, m, s)` | two multiplies and adds under a range check | `StaticInvoke`; a foldable seconds argument is a constant, a decimal column declines | B |
| `time_trunc(unit, t)` | a division and a multiply, at a foldable level | `trunc(d, fmt)`'s rule for the level | A |
| `t1 - t2`, `timediff(...)` | a day-time interval, `/ 1000` | exact by range | A |
| `t + dt` | `t + micros * 1000` under a range guard | 4.1 | C |
| `time_to_seconds` etc. | multiplies and divisions by powers of ten | cheap after A; `TimeToSeconds` waits on 2.3 | C |
| `second_with_fraction` | a division and a remainder into a decimal | waits on an Arrow decimal representation, 2.3 | C |

### 4.1 `TimeAddInterval` does not wrap, and that decides its lowering

Vanilla's `timeAddInterval` is `addExact` plus a check that the result lies in
`[0, 24h)`, throwing `timeAddIntervalOverflowError` otherwise - it does **not**
take a modulo. A lane cannot throw, so this takes `make_date`'s pattern: the
guard fails the batch into the ghost fallback, which raises the identical error
on the identical row, or returns null under `TRY`.

[SPARK-57853](https://issues.apache.org/jira/browse/SPARK-57853) is open on
whether that becomes ANSI's modulo-24. If it lands, the lowering becomes a
`floorMod` by `NANOS_PER_DAY` and the guard goes away. The plan should not
pre-empt it; it should make the guard easy to delete.

The ticket is unassigned and has no patch, so milestone 5's task 146 takes
reviewing or writing one upstream. This section is where its answer is recorded:
the guard is deleted here, or kept here with the reason.

## 5. Tests

1. **The differential against `LocalTime`**, over all 86 400 seconds of a day
   plus the sub-second edges - which is every distinct answer the field extracts
   have, 2.2.
2. **The expression Spark actually produces is the one the compiler matches**,
   built by running the analyzer and optimizer over SQL rather than by hand. This
   is the guard against 2.1's silent-rename failure and is the most important
   test in the task.
3. **Both AVX levels**, since step 3's converts do not intrinsify under AVX2 and
   the magic form replaces them there.
4. **The declines name their output type**, for the two decimal-returning
   expressions.
5. **`TimeAddInterval` declines the batch** rather than wrapping or throwing.
6. **The oracle and fuzzer at `TIME`** - task 119's `TIME` arms land here, which
   is what its row says.

## 6. What is in this task, and what is not

Everything Spark has, in the end - but not in one step, and two of them wait on a
representation rather than on this task. The groups are an order, not a
shortlist.

*Re-grouped 18 September 2026 by 2.6's map, which is the dependency that
matters. The first draft grouped by lane and by matching; three of the four it
called free of blockers in fact need the long-lane division, and the one that
needs nothing was in its deferred list.*

**The prerequisite, before any expression: the `StaticInvoke` table** of 2.1.
All nine go through it, so it is not a group's cost but the task's entry fee. It
is also self-contained and testable on its own - the table is built from the
Catalyst classes' own `.replacement`, and the test is that what Spark produces
for a SQL query is what the table holds.

**Group A: `t + dt`.** The only expression needing nothing beyond the table - a
multiply by 1000, an add, and the range guard of 4.1. Its semantics may change
under [SPARK-57853](https://issues.apache.org/jira/browse/SPARK-57853), which is
an argument for building the guard so it is easy to delete, not for waiting: a
milestone whose subject is `TIME` should not have its first `TIME` kernel blocked
on an upstream ticket that may sit for a release.

**Group B: the three same-lane divisions** - `time_trunc`, `t1 - t2`,
`timediff`. They need task 88's step 3 and nothing else, so they follow it
directly and are what proves it on real expressions rather than on a probe.

**Group C: the width changes** - `hour`, `minute`, `second` narrowing, and
`make_time` widening. The extracts are the post's subject and depend on 2.5's
answer; `make_time` needs no division at all, only the widening, so it can land
whenever task 28 does.

**Group D: blocked on a representation** - `second_with_fraction` and
`time_to_seconds`, 2.3, admitted unchanged the day an Arrow decimal
representation exists. `TimeFrom*` and the remaining `TimeTo*` are ordinary
long-lane multiplies and divisions and follow group B cheaply.

**Nothing here is declined for want of a mechanism.** Every `TIME` expression
Spark has is vectorizable except group D's two, and those wait on a
representation rather than on anything about the lane.

## 6.1 Sequencing

1. The plan, and this correction.
2. **The `StaticInvoke` table**, with its guard test. Everything waits on it and
   nothing else does, so it goes first and alone.
3. **Group A**, `t + dt` - the first `TIME` kernel, needing nothing further.
4. **Task 88 step 3**'s long-lane converts, with `useAVX` in the shape key
   resolved per section 3.
5. **Group B**, the three same-lane divisions, which prove step 3 on real
   expressions.
6. **2.5's question**, then group C's extracts; `make_time` with task 28.
7. The declines, the coverage rows, and task 119's `TIME` arms.

## 7. Outcome

### 7.1 Groups B and A, 19 September 2026

Built in that order, which reverses 6.1's, and the reason is worth keeping: group
B needed no new machinery once #255 had landed the long-lane divide - three
compiler arms over nodes that already existed - while group A needed a new IR
node. The cheaper commit went first and proved the compiler path with the least
behind it; A followed on a path already known to work.

**Group B is three arms and no emitter change.** `subtractTimes`, `timeDiff` and
`timeTrunc` are matched by the `DateTimeUtils` method their `StaticInvoke` names,
through #254's table, and lower to a wrapping subtraction and a `ConstDivide` (or
a divide and a multiply for `time_trunc`). Every dividend is bounded by the type
- nanoseconds of day are below 2^47, and so is the difference of two - which is
the one place in the lane's arithmetic where `ConstDivide`'s bound is a property
of the type rather than of the data. No per-batch check is registered. A unit or
level that is not a literal declines, since the divisor is part of the kernel's
shape.

**Group A is a wrapping add under two range guards.** Spark's `timeAddInterval`
is `addExact(t, multiplyExact(dt, 1000))`, a throw if the sum leaves the day,
then a truncation to the target precision. The interval is held to a day first,
for two reasons that are one: beyond a day every sum leaves the day and Spark
throws on every row, and inside a day the multiply and the add stay under 2^48,
so wrapping arithmetic is exact. The sum is then held to the day, which is the
throw as a decline. A literal interval's guard is decided at compile time - a
literal beyond a day declines outright, one inside it folds to a slot.

**The precision step is not emitted, and the argument is code.** The time is a
multiple of 10^(9 - p), the interval's nanoseconds a multiple of 10^3 - or of a
whole minute for an end field coarser than SECOND - and the target
`TimeAddInterval.replacement` computes makes the sum already a multiple of what
the truncation would remove, in both cases. `timeAddIntervalTruncates` checks
that against the types at compile time and declines if it ever fails; a test
holds it across precisions in both directions, one case included that does
truncate so the check is not vacuous.

**The guard is `GuardedRange(child, lo, hi)`**, `GuardedDay`'s twin at whichever
lane the child is on, with the bounds inside the node so that two shapes with
different bounds cannot share a kernel. It reaches every site the day guard does.
`emitRangeGuard`'s bounds widened to `long`, which moved no int-lane byte.

**The bytes oracle's fuzz half moved, and the diff says why.** Adding a node type
adds an arm to the grammar's draw, which reshuffles its fixed-seed sample of ten
thousand shapes. Exactly two keys changed in `emitted_bytes.json`, the fuzz-block
digests at lanes 4 and 16, and no coverage row - every named shape's bytes are
identical. That is the check to run whenever a node is added: the coverage half
is the oracle for emission, the fuzz half for the grammar.

**What the coverage fixture could not hold, and what holds it instead.** The
differential's fixture reaches both ends of the day, so no non-zero constant
interval keeps every row of `t + dt` inside it - Spark raises `DATETIME_OVERFLOW`
on the row that crosses, in both engines. The coverage row therefore adds a zero
interval, on purpose and with the reason in its note, and
`VarkaTimeArithmeticSuite` carries the real tests: every second of the day with
a per-row interval that stays inside, the crossing case raising Spark's own error
under Varka rather than a wrapped time, and a `CASE` whose crossing rows sit in
the untaken arm - where the row engine does not throw, the kernel's guard fires,
and the decline shows in `numFallbackBatchesDeclined`. That last one is what
separates "declined" from "wrong" for a guard no value can show.

**Two things the decline test taught about the test, not the kernel.** The
end-to-end test that shows a declined batch in the node's metric is a conjunction
whose left side the row engine short-circuits and whose right side adds a
crossing interval. Its first form selected with `t = TIME'12:00:00'`, and the
optimizer's constant propagation rewrote the sum's `t` to the literal - a literal
noon plus any interval in the fixture stays inside the day, so the guard had
nothing to fire on and the batch was served correctly. A range on `t` is not
propagated, and with it the guard fires, the batch declines, and the row engine
answers the same row. And under `guardUnderArm` a crossing row in an untaken
`CASE` arm neither throws nor declines, which a test now pins: the `TIME` guard
rides the arm qualification the day guard does.

**On [SPARK-57853](https://issues.apache.org/jira/browse/SPARK-57853).** If
upstream adopts ANSI's modulo-24, the change here is the deletion of one
`GuardedRange` wrapper and the substitution of a floor-mod; the interval's guard
stays, since the multiply's exactness rests on it. Task 146 follows the ticket.

**Still owed:** group C's extracts, which wait on 2.5's question or task 28; group
D's decimal pair; task 119's fuzzer at the long lane, since the grammar still
generates no `LONG` node and the new guard is fuzzed only at the int lane.

## 6.2 Sequencing, as it happened

Steps 2, 4, 5 and 3 of 6.1 in that order: the table (#254), task 88 step 3
(#255), group B, group A. Step 6 is next.
