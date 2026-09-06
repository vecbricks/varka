# Task 37: `weekofyear` by the Thursday rule

<!-- Rewritten on 4 September 2026 from the recipe below the line; the recipe's
     lowering (a provisional week, two year-boundary corrections and a
     weeks-in-year helper) was replaced by the Thursday rule in the datealgo-rs
     review (#97, SKILLS.md) and the milestone row, and this file had not
     followed. The old text is kept at the end as the record of what was
     planned and why it changed. -->

## 1. Where this came from

`PLAN_MILESTONE_4.md` row 37 and section 2.11: the last of the four date-field
recipe tasks (34 to 37), and the one field of the family a reader of the
closing table (task 62) will look for and not find. The first recipe for it,
below the line, lowered ISO week numbering the textbook way: a provisional
week from the January day of year and the weekday, then two corrections at
the year boundaries and a `weeksIn(year)` helper called twice, about sixty
ops and a test burden concentrated on a handful of days a year. The
`datealgo-rs` review (`SKILLS.md`, "The Thursday rule for the ISO week")
replaced it: move the day to the Thursday of its week, `t = d + 3 -
weekday0(d)` with Monday as 0; the ISO week is `(dayofyear(t) - 1) / 7 + 1`
and the ISO week-based year is `year(t)`. Both corrections and the helper
vanish by construction, because a Thursday is always in the year its week
belongs to. Task 58 (`extract(YEAROFWEEK)`, section 2.25) is `year` over the
same shifted day, which is why the shift is a node of its own here.

## 2. The admission check, done

Run on 4 September 2026 against Python's `date.isocalendar()`, which is the
same ISO 8601 rule `java.time`'s `IsoFields` implements over the same
proleptic Gregorian calendar, with the arithmetic written as the emitter will
write it (a Monday-based weekday from `(d + 3) mod 7`, since 1970-01-01 was a
Thursday; the January day of year of the shifted day; an integer division).

**The rule.** Over every one of the 3,652,059 days from 0001-01-01 to
9999-12-31, `(dayofyear(t) - 1) / 7 + 1` with `t = d + 3 - weekday0(d)`
equals the ISO week: zero mismatches, every year boundary in both directions
included. `year(t)` equals the ISO week-based year over the same days, zero
mismatches - the fact task 58 rests on. What the check would have rejected: a
rule that needed the corrections after all, which is what "week 53 or week 1"
disagreements at December 29 to January 3 would have shown.

**The shift is small.** `t - d` is `3 - weekday0(d)`, so `t` lies in
`[d - 3, d + 3]`; section 2.25 wrote `[-6, +3]`, which is safe but loose, and
task 52's range arm for the shift node is `[-3, +3]`.

**The division is one exact magic.** `x / 7` for `x = dayofyear - 1` in
`0..365` is `(x * 293) >>> 11`: exact for every `x` up to 684, product at most
106,945, far under the `2^31` bound every magic in `VarkaChrono` respects.
Two ops after the subtract, plus the add of one; no correction step, no
floorMod, because the dividend is never negative.

**The weekday can be three ops, inside the narrow range.** The review's
reciprocal form, `(((m + c) * 613566756) mod 2^32) >>> 29` with `m = d -
NARROW_MIN_DAYS` and `c = 3`, is `weekday0(d) + 1` for every one of the
16,777,216 days of `[NARROW_MIN_DAYS, NARROW_MAX_DAYS]`: zero mismatches. It is
exact only there, which is the review's point: it belongs behind the range
the calendar nodes are already held to, and it is a `FloorMod7` variant of its
own (the review: "a task of its own after the emitter settles"), not this
task's. Section 3 says which form this task ships and why.

**Verified, not asserted:** the same three checks run again in section 5 as
tests - the sweep against `DateTimeUtils.getWeekOfYear` over the whole
covered range under `-Dvarka.sweep=true`, the magic's exactness over
`0..684`, and the reciprocal's over the narrow range if it is used.

## 3. The design

### 3.1 Two nodes: the Thursday shift, and the week tail over it

**Why two.** The emitter runs a calendar node's civil-from-days prefix over
the value of `chronoChild(node)` - `emitChronoPrefixOnce` emits the child and
`emitChronoPrefix` takes the date off the operand stack - and keys prefix
sharing on that child. A single `WeekOfYear(days)` node that shifted to the
Thursday *inside* its tail would need the prefix over an internal temporary
the machinery cannot see. So the shift is a node of its own, and the week
tail is an ordinary calendar node whose child is that shift:

    weekofyear(d)  ->  WeekOfYear(ThursdayOf(ColumnRef))
    yearofweek(d)  ->  Year(ThursdayOf(ColumnRef))          (task 58, one arm)

Two calendar nodes over the same `ThursdayOf` share one prefix under
`shareChronoPrefix`, which is how task 58 becomes free; neither shares with
`year(d)` over the bare column, whose fragment child is a different node.
Row 37 already says so.

**`ThursdayOf(VarkaVectorIR days)`** - a day-typed producer beside `NextDay`
in the top-level `permits`, not a `Chrono` member: `t = d + 3 - weekday0(d)`,
`weekday0` Monday-based. Emitted on `NextDay`'s pattern, which is the one
existing node that keeps a second copy of the date across `emitFloorMod7`:

    [d]  dup             [d, d]
         emitFloorMod7   [d, floorMod(d, 7)]      12 ops under MAGIC
         emitModOffset 3 [d, weekday0]             3 ops
         swap; add 3     [weekday0, d + 3]         1 op
         swap; sub       [d + 3 - weekday0]        1 op

Seventeen `IntVector` ops under the shipped `FloorMod7.MAGIC`; the two
`dowTmp` scratch slots `planSlots` already gives `DayOfWeek`, `WeekDay` and
`NextDay`. `planWordRef` aliases its word to the date's, like `NextDay`. The
weekday form is whatever `FloorMod7` selects: the review's three-op
reciprocal is exact only inside the narrow range and is a fourth variant of
that switch in a task of its own (`SKILLS.md`), and when it lands this node
gets it with no edit, because it calls the helper. The milestone row's
"should be the reciprocal-bits form" is therefore met by construction later,
not by this PR; section 6 registers both counts so the day it lands the
saving is a number.

**`WeekOfYear(VarkaVectorIR days)`** - a `Chrono` member whose meaning is
the definition, "the ISO week of `days`", and whose lowering is
`(januaryDayOfYear(days) - 1) / 7 + 1`. The two agree exactly when `days` is
a Thursday, which the compiler guarantees by only ever building it over
`ThursdayOf`; the emitter makes that a checked contract - `Analysis.analyze`
requires the child to be a `ThursdayOf`, on `requireLiteralOffset`'s
precedent, and throws otherwise - so no other tree can reach the lowering.
The tail, after the shared prefix: `emitChronoYear`, `emitLeapFlag`,
`emitJanuaryDayOfYear` (the `DayOfYear` arm's own three calls, over
`CHRONO_PREFIX_SLOTS + 1` slots), then `sub 1`, `mul WEEK_M`, `LSHR WEEK_K`,
`add 1`: four ops. `tailReadsMarchMonth` is false, as for `DayOfYear`.

**The constants** join `VarkaChrono` under its naming rule: `WEEK_M = 293`,
`WEEK_K = 11`, javadoc with the derivation and the domain `0..684`, and a
scalar twin `VarkaChrono.weekOfYear(int days)` written over `narrowed`'s
January day of year of the shifted day, so the sweep suite can hold the
arithmetic to `DateTimeUtils.getWeekOfYear` without the emitter.

**No new `VarkaEmitOptions` switch.** The template asks for the old form as a
live reference variant; here the old form is the superseded recipe, which
was never built, and the only alternative in the lowering - the weekday - is
already behind `FloorMod7`. The A/B in section 6 is against the sibling
`dayofyear` and the per-row `DateTimeUtils` path, not against a second form.

**Task 52's analysis (#115, open).** On master the calendar arms call
`compileNode` and there is no range analysis or guard; when #115 merges,
`WeekOfYear`'s arm takes its child through `calendarInput` like the others
and `dayRange` gains `case n: ThursdayOf => shifted(n.days(), -3, 3)`.
#115 merged first, the same evening, so this branch carries those lines:
`admitCalendar`, the admission half of `calendarInput` over the built
`ThursdayOf`, and the `[-3, +3]` arm, with the boundary pinned in the
compiler suite. As written before that:
until then a `weekofyear` over a far day answers as every calendar node on
master does today. Section 2.25's `[-6, +3]` is corrected to `[-3, +3]`.

**Added while the differential ran, the same day: the int literal in a
comparison.** `WHERE weekofyear(d) = 53` declined - not at the new node but
at the `53`: `compileNode`'s literal arm accepts date literals only, so no
fused int field had ever been compared with an int literal in a kernel
(`year(d) = 2021` in the task 62 surface reached the kernel only because the
analyzer rewrites that particular shape). The predicate comparison now takes
an int literal as a literal slot on either side, in `compare` alone, so the
value leaves stay date-typed and integer arithmetic over an output remains
task 30's. Three ops in the kernel, no new IR, no emitter change; the
compiler suite pins both the new predicate and the unchanged value side.

### 3.2 What is deliberately unchanged

* `emitFloorMod7` and its three variants: the reciprocal form is its own task.
* `emitChronoPrefix`, `emitJanuaryDayOfYear`, `emitLeapFlag`: called, not
  edited; `dayofyear`'s register stays at 43.
* `DayOfYear`, `TruncDate` and their tails; task 32's grouping.
* `extract(YEAROFWEEK)`: task 58, one compiler arm over this task's node, with
  its own boundary tests; not folded in here so its row closes on its own
  evidence.
* Spark's `WeekOfYear` expression and `DateTimeUtils.getWeekOfYear`: the
  oracle, untouched.

### 3.3 Registered op counts

Dense-loop `IntVector` calls, the register test's metric, under the shipped
options; asserted in section 5 and printed with `dev/varka_emit.sh --table`
before the PR opens.

| kernel | before | after (planned) | after (emitted) |
|---|---|---|---|
| `ThursdayOf(col)` alone | - | 17 | 19 |
| `weekofyear(col)` (`WeekOfYear(ThursdayOf(col))`) | - | 64 (17 + 43 + 4) | 64 (19 + 45) |
| `dayofyear(col)` | 43 | 43 | 43 |
| `month(col)` / `dayofmonth(col)` | 35 / 36 | 35 / 36 | 35 / 36 |
| `weekday(col)` | unpinned | 15 | 17, now pinned |
| `next_day(col, 'MON')` | unpinned | 15 | 18, now pinned |

*Corrected from the register's first run, the same day:* the mod-7 fold is
14 `IntVector` calls, not the 12 the survey derived (`weekday` is 17, not 15),
so the shift is 19; and the week tail past the prefix is 45 against
`dayofyear`'s 43, not 47 - `emitMagic`'s shift is a `lanewise` call the
counter sees, so the two missing ops are the January-turn `blend` and the
`sub 1`, which the JIT-visible count folds differently than the plan
counted by hand. The total, 64, is what the plan predicted, by two errors
that cancel. Weights: `THURSDAY_OF_WEIGHT = 19`, `WEEK_OF_YEAR_WEIGHT = 55`
(`DAY_OF_YEAR_WEIGHT + 4`), both in the `weightOf` chain before the
`isChrono` fallthrough.

## 4. Files

| file | what |
|---|---|
| `VarkaVectorIR.java` | `ThursdayOf` in the top-level `permits`, `WeekOfYear` in `Chrono`'s; both `canonical` renderings |
| `VarkaChrono.java` (+ `VarkaChronoSuite`) | `WEEK_M`, `WEEK_K`, `weekOfYear(days)`; the constants over their whole domain and one past; the twin's opt-in sweep |
| `VarkaLoopEmitter.java` | the two weights; `childrenOf`, `analyze` (the Thursday-child rule), `planWordRef`, `planSlots`, `emitValue` arms; `chronoChild`, `tailReadsMarchMonth`, `emitChrono`'s tail switch; `emitChronoWeekOfYear` beside `emitChronoTrunc` |
| `VarkaReferenceEvaluator.scala` | the two oracles from `java.time`, section 5 |
| `VarkaLoopEmitterSuite.scala` | the matrix, the dense sweep, the shape rule, the register, the sharing test, both pinned fixtures re-pinned |
| `VarkaIrFuzzSuite.scala` | a generator arm for the pair, and one for `ThursdayOf` alone (bound + 3) |
| `VarkaShapeCacheSuite.scala` | the `everyNode` hash re-pinned |
| `VarkaExpressionCompiler.scala` (+ suite) | the `WeekOfYear` arm; imports |
| `VarkaDifferentialSuite.scala` (+ `VarkaSharedSessions`) | the day-by-day table, the Velox fixtures, the spellings, the filter path |
| `VarkaEmitterParityBenchmark.scala` + its three files | the `weekofyear` cases and the per-row anchor, both widths regenerated |
| `VarkaThroughputBenchmark.scala` + its three files | a `weekofyear` query row |
| `docs/sql-varka.md` | the surface bullet |
| `PLAN_MILESTONE_4.md`, this file | row 37, 2.25's range, section 9 |

## 5. Tests, and what each is for

* **Oracles, from the definition, never the lowering**
(`VarkaReferenceEvaluator`): `ThursdayOf` is
`LocalDate.ofEpochDay(v).with(DayOfWeek.THURSDAY)`, which `java.time` adjusts
within the Monday-based week; `WeekOfYear` is
`IsoFields.WEEK_OF_WEEK_BASED_YEAR` of `v`. The fuzzer and the matrices
inherit both.
* **Constants**: `WEEK_M`/`WEEK_K` exact for every `x` in `0..684` and wrong
  at 685, the `DOM_M` test's template; the leap-year and century corners of
  the twin against `DateTimeUtils.getWeekOfYear`; the twin over the whole
  narrow range under `-Dvarka.sweep=true`.
* **The matrix** (`checkMatrix`, every length and null pattern, both prefix
  forms, all three `FloorMod7` variants): the calendar boundary days plus the
  ISO corners - 2015-12-28, 2016-01-01, 2019-12-30, 2020-12-31, 2021-01-01,
  December 31 of 2004, 2009, 2015, 2020 and 2026 (week 53), and the Velox
  Spark-compatibility fixtures row 37 names: 1919-12-31 and 1969-12-31 in
  week 1, 1960-01-01 in week 53, 0001-01-01 in week 1, 9999-12-31 in week
  52, the leap years ending on Thursday, Friday and Saturday.
* **The dense sweep**: every day from 1990-12-20 to 2030-01-10 through the
  kernel, 14,631 days across forty year boundaries in both directions - the
  rule claims the boundaries are automatic, and this is the check.
* **The shape rule**: `WeekOfYear` over a bare column, an `AddDays`, or a
  `NextDay` fails at analysis with the rule's message; over `ThursdayOf` it
  emits. The failure this catches: a future compiler arm building the tail
  over the wrong child and getting a plausible wrong week.
* **The register**: the table in 3.3, and `dayofyear`/`month`/`dayofmonth`
  unmoved.
* **Sharing**: `WeekOfYear(ThursdayOf(col))` and `Year(ThursdayOf(col))` in
  one kernel cost at least twenty ops less than the two alone (the task 35
  sharing test's shape); with `Year(col)` beside them, no sharing across the
  two children.
* **Pinned fixtures**: both move by two lines and one hash, re-pinned from
  the failing output, old and new recorded in section 9.
* **The compiler**: `weekofyear(d)` compiles to
  `WeekOfYear(ThursdayOf(ColumnRef(0)))` with an `IntegerType` output;
  `extract(WEEK FROM d)` and `date_part` to the same; two `weekofyear` outputs
  share the `ThursdayOf` under CSE.
* **The differential**: a cached table of every day 1990-12-20..2030-01-10
  built from `range` (so the row engine and the kernel see the same 14,631
  rows), `weekofyear`, `extract(WEEK)`, `dayofyear` and `year` over it with
  nulls, `expectFused = true`; the Velox fixtures as literal rows; the filter
  path `WHERE weekofyear(d) = 53` counted; the two prefix forms through the
  session hook.

## 6. The measurement

`VarkaEmitterParityBenchmark`, the "year" section, on task 35's pattern: the
`weekofyear` kernel null-free and mixed nulls (ids 870, 871), `ThursdayOf`
alone null-free (872), beside the existing `dayofyear` rows as the sibling
control, and a per-row `DateTimeUtils.getWeekOfYear` anchor, "the path Spark
uses today". Both widths, one regeneration through `dev/varka_bench_regen.sh
catalyst VarkaEmitterParityBenchmark` on an idle machine.
`VarkaThroughputBenchmark` gains a `weekofyear` query row beside
`dayofweek`'s, regenerated the same way, since the closing table (task 62)
will carry the expression.

### 6.1 Predictions, registered before the run

1. The register: 17 for `ThursdayOf`, 64 for `weekofyear`, siblings unmoved.
2. `weekofyear` null-free runs at 0.6x to 0.75x of `dayofyear`'s rate at 256
   bits (64 ops against 43, some of them the cheap shift), and no lower than
   0.55x at 128 bits, where the fold's twelve ops weigh more.
3. At least 10x the per-row `DateTimeUtils.getWeekOfYear` anchor at 256 bits:
   `year` is 7x its `LocalDate` anchor, and `IsoFields` costs the row path
   more than `getYear` does.
4. The sharing test saves the prefix's full price, 20 ops or more, and the
   `Year(ThursdayOf)` pair costs under 10 ops more than `weekofyear` alone -
   the number task 58 inherits.
5. The dense sweep and the Velox rows pass on the first emitted kernel: the
   rule was checked over the whole calendar in section 2, and what is left
   to get wrong is plumbing, which the matrix catches before the sweep does.

## 7. Risks

1. **The tail over a non-Thursday.** The analysis rule (section 5, "the shape
   rule") makes it unreachable; the fuzzer generates the pair as a unit.
2. **`dowTmp` reuse.** `emitFloorMod7` clobbers both scratch slots; the date's
   second copy rides the operand stack exactly as `NextDay`'s does, and the
   matrix at every null pattern is what shows a clobbered word.
3. **The prefix over a computed child under nulls.** `planWordRef` aliases
   `WeekOfYear`'s word to `ThursdayOf`'s, which aliases to the date's; the
   mixed-null matrix rows and the differential's nulls hold it.
4. **The weights are guesses until the register runs**; 3.3 is corrected
   from the emitted bytes, not the other way round.
5. **#115's merge order.** Two lines either way (3.1); the sweep would not
   notice their absence, so the PR text names them.

## 8. Sequencing

1. This plan (sections 1-8) and the milestone row.
2. `VarkaChrono`: constants, twin, suite; the two IR nodes with renderings.
3. The emitter: weights, switches, the two arms and the tail; the reference
   oracles; the matrix, sweep, shape rule and register tests; the fuzzer
   arms; both fixtures re-pinned.
4. The compiler arm and suite; the differential and its table; the docs.
5. The benchmark cases, one regeneration at both widths, section 9, row 37.

## 9. Outcome

Built as sections 3-5 describe, with the register corrected from the emitted
bytes (3.3: `ThursdayOf` 19, `weekofyear` 64). Both benchmarks regenerated
at both widths by `dev/varka_bench_regen.sh` on the idle machine under the
`performance` governor, against the #121 baseline measured on unchanged
master under the same profile the same morning. Rates in M rows/s from the
committed files. The committed parity file is the third run on this branch
(load 0.40 at start, canary compute +0.2%, cache +0.5%, memory -0.5%): the
first, overnight, had the three `dayofweek, chunk 64/63` rows 23-37% under
both master runs; the second put them back within 3-6% but carried one
unrelated row at a fraction of its value; the third has neither, so those were
each run's JIT state, not this node - `weekofyear` itself read 1422.7, 1408.3
and 1413.8 across the three, 510.9, 510.7 and 510.8 at 128 bits.

| case | 256-bit | 128-bit |
|---|---|---|
| `weekofyear (task 37), null-free` | 1413.8 | 510.8 |
| `weekofyear (task 37), mixed nulls` | 1140.2 | 416.8 |
| `ThursdayOf alone (task 37), null-free` | 7994.9 | 3174.7 |
| `year, null-free` (the nearest sibling in the file) | 3446.0 | 1336.2 |
| per-row `DateTimeUtils.getWeekOfYear` | 53.5 | 53.4 |
| throughput `weekofyear`, varka / Janino | 267.0 / 25.7 (10.4x) | 179.2 / 25.3 (7.1x) |

**Predictions scored.**

1. *The register: 17 for `ThursdayOf`, 64 for `weekofyear`, siblings
   unmoved.* 19 and 64, siblings unmoved - the suite asserts all of it; the
   two extra ops on `ThursdayOf` are recorded against 3.3 (risk 4 said the
   weights were guesses until the register ran).
2. *`weekofyear` null-free at 0.6x-0.75x of `dayofyear` at 256 bits, no
   lower than 0.55x at 128.* Not scorable as written: the parity benchmark
   has no `dayofyear` row (task 34 never added one), which section 6 assumed.
   Against `year`, the nearest sibling in the file, the ratio is 0.41x at
   256 bits (1413.8 against 3446.0) and 0.38x at 128 - below the band even allowing that `year` is
   the lighter kernel. The fold costs more than its op count suggests;
   registered below as a leftover, not explained here.
3. *At least 10x the per-row anchor at 256 bits.* 26x (1413.8 against
   53.5); 9.6x at 128 bits, where the anchor does not slow down.
4. *The sharing test saves the prefix's full price (20 ops or more), and the
   `Year(ThursdayOf)` pair costs under 10 ops more than `weekofyear` alone.*
   Held; the suite asserts both inequalities (`unshared - shared >= 20`,
   `shared - weekAlone < 10`, `withYear - shared >= 20`).
5. *The dense sweep and the Velox rows pass on the first emitted kernel.*
   Held: no arithmetic fix-up commit follows the emitter commit on this
   branch, and the gate is green.

What moved that the plan did not list: nothing in the code. The regen tool's
default class path does not reach `VarkaThroughputBenchmark`'s package, which
cost the overnight throughput run (the corrected invocation names the class
in full), and two parity runs this morning each carried one unrelated row at a
fraction of its value - a JIT artifact that never reproduced (master's first
run: `CASE WHEN, depth-4 arms`; this branch's second run:
`year+month+day+quarter, shared decomposition (no validity write)`, 12.2
against 1534.2). The third run, committed, has neither.

Left for later, in the milestone's debt register: why the week fold's twelve
ops cost more than a proportional share of `year`'s rate at both widths
(prediction 2).

---

## The recipe before the Thursday rule (superseded, kept as the record)

# Task 37: `weekofyear`

The last and much the hardest of the four small vocabulary tasks (34-37), and
the only one where the honest advice is: **read all of section 2 before
writing any code.** ISO week numbering is not "day of year divided by seven",
and every wrong implementation of it is wrong only on a handful of days a year,
which is exactly the failure mode a thin test suite misses.

Read `PLAN_TASK_33.md` section 3 for the mechanics of adding a node type.
**Depends on tasks 26 and 34** - 26 for `emitChrono`, 34 for `emitLeapFlag`
and the January-based day-of-year.

**Task 32 may move the plumbing; it will not move the arithmetic.** A separate
task is measuring whether one shared decomposition can feed several fields,
instead of each node carrying its own copy of it. If that goes ahead, the tails
will read `doy` and `dom` from somewhere else - and every formula in section 2
will be unchanged, because what moves is where the intermediates live, not what
they are. So do not restructure `emitChrono`, do not try to share anything with
the other tails, and do not read the duplicated decomposition as a bug to fix.
Write the tail this recipe describes and let task 32 do its own job.

## 1. What you are building

`weekofyear(d)` returns the ISO-8601 week number, 1 to 53. Spark's reference:

```scala
def getWeekOfYear(days: Int): Int =
  LocalDate.ofEpochDay(days).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
```

The rule ISO applies: week 1 of a year is the week containing that year's first
Thursday, weeks run Monday to Sunday, and the first days of January can
therefore belong to the *last* week of the previous year - while the last days
of December can belong to week 1 of the next. 2016-01-01 is week 53 of 2015;
2019-12-30 is week 1 of 2020. If your implementation returns 1 for the first
and 52 for the second, it is wrong in the usual way.

`LocalDate` is exact, so no intermediate below may overflow. None can.

## 2. The lowering

Three pieces, in order. `doy` here means the **January-based** day of year that
task 34 built, not `emitChrono`'s March-based one.

```
isodow = floorMod(d + 3, 7) + 1                 // Monday = 1 ... Sunday = 7
w      = (doy - isodow + 10) / 7                // the provisional week number
weekofyear = w < 1              ? weeksIn(year - 1)
           : w > weeksIn(year)  ? 1
           : w
```

`isodow` is Varka's existing `weekday` plus one - reuse `emitFloorMod7`, do not
write a second mod-7.

The provisional `w` divides a value in `[4, 375]`, always non-negative, so the
`/ 7` is a **plain magic multiply with no correction and no floorMod** - see
`SKILLS.md` for the bound: an exact magic exists for any dividend under 46341.
Do not reuse `emitFloorMod7` here; it is the full-range version and you do not
need it.

### 2.1 `weeksIn(y)`, the part that is easy to get wrong

A year has 53 ISO weeks iff it starts on a Thursday, or is a leap year starting
on a Wednesday:

```
p(y)       = (y' + y'/4 - y'/100 + y'/400) mod 7      // y' = y + 13200
weeksIn(y) = 52 + ((p(y) == 4 || p(y - 1) == 3) ? 1 : 0)
```

The bias of 13200 is the one `PLAN_TASK_34.md` section 2.1 introduces: a
multiple of 400 so the leap structure is unchanged, and large enough that
`y - 1` stays non-negative at the bottom of `VarkaChrono`'s covered range,
which is the reason it is 13200 and not 12800.

**Do not use `M=167773` (`k=24`/`26`) for the `/100`/`/400` divisions below.**
An earlier draft of `PLAN_TASK_34.md` used exactly these constants for the
same divisors over the same biased-year range and they are wrong: they are
exact only under unbounded-precision arithmetic and overflow the
`v * M < 2^31` no-overflow bound every magic multiply in `VarkaChrono` must
respect - at the top of the range (biased year 46334), `46334 * 167773` is
over three and a half times past `2^31`. `PLAN_TASK_34.md` section 7's
Outcome records finding this the hard way, with a corrected round-down magic
(`M=41943` at `k=22` for `/100`, `k=24` for `/400`) that `VarkaChrono`
actually ships. Use those corrected constants here too - they cover the same
range with the same bias, so they carry over directly - but note one
difference from `emitLeapFlag`'s use of them: `emitLeapFlag` only needs a
**boolean** ("is the remainder 0 or the divisor"), which a round-down magic
answers directly. `p(y)` here needs the actual **quotient** `y'/100`/`y'/400`
added into a sum, and a round-down magic can undershoot the true quotient by
one - so this table's divisions need an explicit correction step
(`emitCarry`'s round-down-plus-one-correction idiom) before the quotient is
used, which the constants alone do not provide. Re-verify section 2's whole
claim ("checked against `java.time`... zero mismatches") with a simulation of
true 32-bit truncating multiplication once the correction is in place, the
way `PLAN_TASK_34.md`'s fix was - a plain-Python check without overflow
truncation is what let the wrong constants through the first time:

| divisor | M | k |
|---|---|---|
| 4 | shift by 2 | - |
| 100 | 41943 | 22 |
| 400 | 41943 | 24 |
| 7 (the outer mod) | the standard mod-7 magic, dividend is small and non-negative | - |

`weeksIn` is needed for **two** years - `year` and `year - 1` - so emit it as a
helper called twice rather than twice inline. That is about 15 ops each, and it
is why this task is roughly twice the size of the other three.

**Verified, not assumed**: the whole of section 2 was checked against
`java.time`'s `IsoFields.WEEK_OF_WEEK_BASED_YEAR` over all 3,652,059 days of
`0001-01-01..9999-12-31` during planning - zero mismatches, including every
year boundary in both directions.

Expected size: about 60 ops on top of the decomposition, which makes this the
widest node in the family by some margin. Give it a `CHRONO_WEIGHT` of its own
if the existing one looks wrong for it, and say so in the pull request.

## 3. The edits

Mechanics per `PLAN_TASK_33.md` section 3. Specifics:

* **IR**: `WeekOfYear(VarkaVectorIR days)`, rendering as `(weekOfYear <days>)`.
* **Emitter**: a chrono node - `isChrono`, the four routine cases, and a
  `case WeekOfYear n -> {...}` in `emitChrono`'s tail switch. Add
  `emitWeeksInYear` as a private helper beside `emitLeapFlag`.
* **Compiler**: `case WeekOfYear(child) => ... .map(new IRWeekOfYear(_))`.

## 4. The tests

This is the task where the test matters more than the code. In addition to the
usual four:

1. `evalValue`'s oracle must be
   `LocalDate.ofEpochDay(v.toLong).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)` -
   never your own formula, and never a hand-written ISO rule.
2. **A dense sweep, not a boundary list.** Every day from 1990-12-20 to
   2030-01-10 through `checkMatrix`, which crosses forty year boundaries in
   both directions and costs a second. The known-hard dates -
   2015-12-28 (week 53), 2016-01-01 (week 53 of 2015), 2019-12-30 (week 1 of
   2020), 2021-01-01 (week 53 of 2020), 2020-12-31 (week 53) - are all inside
   that span, so listing them individually is belt and braces rather than the
   test itself. Add them anyway; they document the rule.
3. The 53-week years specifically: 2004, 2009, 2015, 2020, 2026 start on a
   Thursday or are leap years starting on a Wednesday. Check 31 December of
   each returns 53.
4. The ends of `VarkaChrono`'s covered range, where `weeksIn(year - 1)` is
   evaluated at the very bottom - the case the 13200 bias exists for.

## 5. What to run, and what must pass

Task 33's section 4, unchanged.

## 6. Explicitly out of task 37

* **`extract(WEEK from d)`** desugars to this node; covered for free.
* **`yearofweek` / the ISO week-based year** (`DateTimeUtils.getWeekBasedYear`).
  It is the same machinery with a different tail and would be a reasonable
  follow-up, but it is not this task and the corpus asks for neither.
* **Any non-ISO week numbering.** Spark has only the ISO one here.

## 7. Outcome

Filled in when the work lands. For this task especially, record which parts of
section 2 were unclear - it is the one recipe here that asks the agent to
implement a rule rather than transcribe a formula.
