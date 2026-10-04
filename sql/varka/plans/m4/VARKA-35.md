# VARKA-35: `trunc(date, 'YEAR' | 'MONTH' | 'QUARTER')`

**Status: done; section 8 is the outcome.** Sections 1-7 are the recipe and its
re-plan as they were handed over.

One of four small vocabulary tasks (34-37) written as recipes for a cheap
agent. Read `VARKA-33.md` section 3 for the mechanics of adding a node
type, and `VARKA-34.md` section 2.1 for the leap flag this task needs.

**Depends on VARKA-26 and VARKA-34** - 26 for `emitChrono`, 34 for `emitLeapFlag`
and for the January-based day-of-year the YEAR and QUARTER forms are built on.
If 34 has not landed, do not reimplement its pieces; wait, or say so.

**VARKA-32 may move the plumbing; it will not move the arithmetic.** A separate
task is measuring whether one shared decomposition can feed several fields,
instead of each node carrying its own copy of it. If that goes ahead, the tails
will read `doy` and `dom` from somewhere else - and every formula in section 2
will be unchanged, because what moves is where the intermediates live, not what
they are. So do not restructure `emitChrono`, do not try to share anything with
the other tails, and do not read the duplicated decomposition as a bug to fix.
Write the tail this recipe describes and let VARKA-32 do its own job.

## 1. What you are building

`trunc(d, fmt)` rounds a date down to the start of a period and returns a
**date**, not a number - the first output in this family whose type is
`DateType`, which matters because it can then feed further date arithmetic in
the same fused chain.

Spark's reference is `DateTimeUtils.truncDate`:

```scala
case TRUNC_TO_MONTH   => days - getDayOfMonth(days) + 1
case TRUNC_TO_YEAR    => days - getDayInYear(days) + 1
case TRUNC_TO_QUARTER => first day of the quarter's first month
```

All three go through `LocalDate`, which is exact and never wraps, so the
ordinary rule applies: **no intermediate may overflow.** None below can.

**Scope**: `YEAR`, `MONTH` and `QUARTER` only, and only when the format
argument is a foldable string literal. `WEEK`, and every timestamp-level
format, decline. `YEAR` also has the spellings `YYYY` and `YY`, `MONTH` has
`MM` and `MON`, `QUARTER` has `QTR`; accept whatever
`DateTimeUtils.parseTruncLevel` accepts for those three levels and decline the
rest.

## 2. The lowerings

`emitChrono` already has `dom` (day of month) and, after VARKA-34, the
January-based `dayofyear` and the leap flag `L`. Each format is a separate
lowering chosen at compile time - there is no runtime branch on the format.

```
MONTH:    d - dom + 1
YEAR:     d - dayofyear + 1
QUARTER:  d - dayofyear + qstart, where qstart is selected by quarter:
              Q1 -> 1        Q2 -> 91 + L      Q3 -> 182 + L     Q4 -> 274 + L
```

The quarter constants are the January-based day-of-year of 1 January, 1 April,
1 July and 1 October. `quarter` is already computed by `emitChrono`; select
with three compares and three masked adds over a starting value of 1, or with
a blend chain - either is fine, they are the same op count.

**Verified, not assumed**: all three were checked against `java.time` over all
3,652,059 days of `0001-01-01..9999-12-31` during planning - zero mismatches.

`MONTH` is much the cheapest: it needs `dom` and nothing else, so it does not
need VARKA-34 at all. If you want to land something early, land `MONTH` first
as its own commit.

## 3. The edits

Mechanics per `VARKA-33.md` section 3. What is specific here:

* **One IR node, not three.** `TruncDate(VarkaVectorIR days, int level)` with
  the level as a **record field**, not a literal slot - and this is the one
  place in these four tasks where a constant belongs in the IR rather than in
  `scalarArgs`. The reason: the level chooses which *code* is emitted, not
  which value is used, so two levels are two shapes and must hash differently.
  VARKA-33's rule ("a chain's identity is its shape, not its constants") points
  the same way here, in the opposite direction, for the same reason. Render as
  `(trunc:<LEVEL> <days>)` in both `canonical` and `canonicalShallow`, with the
  level spelled out so the pinned renderings distinguish them.
* **Output type.** `TruncDate.dataType` is `DateType`, so the compiler's
  `outputTypes` picks that up automatically - but check it in the compiler
  test, because every other node this family added is `IntegerType` and a
  wrong type here means the evaluator allocates the wrong Arrow vector.
* **The emitter**: `TruncDate` is a chrono node - add it to `isChrono`, give it
  the four routine cases, and add a `case TruncDate n -> switch (n.level())
  {...}` inside `emitChrono`'s tail switch.
* **The compiler arm** must evaluate the foldable format, map it through
  `DateTimeUtils.parseTruncLevel`, accept exactly the three date-level values,
  and `sink.note` a reason for everything else - a non-foldable format, an
  unrecognized string, and the levels this task does not cover, each with its
  own message.

## 4. The tests

1. `evalValue` gains a `TruncDate` arm whose oracle calls
   `DateTimeUtils.truncDate(v, level)` directly - the definition, not your
   formula.
2. A boundary test per level: 1 January, 31 December, 28/29 February, 1 March,
   the first and last day of each quarter, in a leap year, a common year, a
   century non-leap year (1900) and a century leap year (2000), plus the ends
   of `VarkaChrono`'s covered range.
3. The two pinned fixtures, extended and re-pinned. Put **one** level in the
   fixture, not three - the fixture exists to pin renderings, and one
   `(trunc:YEAR ...)` proves the rendering shape.
4. A compiler test per accepted level plus one decline test per rejected shape.
5. A differential test with all three levels in one query, and one asserting
   that `trunc(d, 'WEEK')` does **not** fuse.

## 5. What to run, and what must pass

VARKA-33's section 4, unchanged, plus: the differential must show the
`DateType` output surviving a second operation - something like
`date_add(trunc(d, 'MONTH'), 5)` - because a wrong output type will pass a
single-column test and fail here.

## 6. Explicitly out of VARKA-35

* **`trunc(d, 'WEEK')`**, which Spark defines as
  `getNextDateForDayOfWeek(days - 7, MONDAY)` - i.e. exactly VARKA-33's
  machinery over `d - 7`. It is a genuinely easy follow-up **once VARKA-33 has
  landed**, and it is left out here only to keep the two tasks independent.
  Say in the pull request if you want it; do not add it unasked.
* **`date_trunc`** at timestamp level - it needs int64 lanes, VARKA-29.
* **Every other `trunc` level.**

## 7. Re-planned against master, and the two scope decisions

Sections 1-6 are the recipe as it was handed over, and they are left as
written. This section is what a reader needs on top of them to execute the
task today: three of the recipe's premises have moved since it was written,
one of its factual claims is wrong, and the two questions it deliberately left
to the owner have been answered. The arithmetic in section 2 is untouched -
it was re-run during this re-plan and is still exact (7.1, item 5).

### 7.1 What moved under the recipe

1. **The leap flag no longer comes from VARKA-34.** VARKA-40 (`VARKA-40.md`,
   merged) shipped `emitLeapFlag` to master for `emitAddMonths`'s own February
   case:

   ```java
   emitLeapFlag(cb, y, scratch1, scratch2, remScratch, maskA, maskB, carryMask)
   ```

   It takes the **plain reported year**, adds `VarkaChrono.YEAR_BIAS` itself,
   and leaves the flag as a mask on the operand stack rather than in an out
   parameter. So the recipe's "depends on VARKA-26 and VARKA-34 ... if 34 has not
   landed, do not reimplement its pieces; wait, or say so" is stale: this task
   depends on nothing unmerged, and should not wait for PR #64.

   What VARKA-34 still uniquely owns is the January-based day-of-year
   *conversion* - `doy >= 306 ? doy - 305 : doy + 60 + L`, four ops - which PR
   #64 has inline in its `DayOfYear` arm rather than as a callable helper.
   Write it here as `emitJanuaryDayOfYear`; if #64 lands first, factor its
   copy out under that name and call it, rather than leaving two.

   **PR #64 currently carries a second `emitLeapFlag` overload** of its own
   (`(cb, biasedYear, remScratch)`), written before VARKA-40 merged and kept
   through the merge, so master will hold two once #64 lands. That is the
   collision `VARKA-36.md` section 7 predicted and its section 8 resolved
   for `last_day`. This task calls **VARKA-40's** (the seven-parameter one, on
   master today) so the duplicate does not acquire a third caller.

2. **VARKA-32 step B1 has landed, so `emitChrono` is already split.** The
   recipe's "do not restructure `emitChrono`" was written when the whole
   decomposition was one method. Today it is `emitChronoPrefix` plus per-field
   tails (`emitChronoYear`, `emitChronoMonth`, `emitChronoDayOfMonth`,
   `emitMonthStart`), with `emitChronoPrefixOnce` sharing the prefix between
   siblings over one date. Obeying that instruction now means writing an
   `emitChronoTrunc` tail method called from `emitChrono`'s switch - exactly
   what `emitChronoLastDay` does (PR #78) - and *not* writing anything inline.
   `VARKA-32.md`'s own file list records that paragraph as deliberately
   not actioned because at the time it was still exactly right; step B1 has
   since landed, so it is stale now, and this is where it is closed.

3. **The range guard is gone** (VARKA-51, PR #73). Every ancestor of this
   recipe assumed `emitEra` carried a guard and that an out-of-range day
   declined the batch. Do not write one, and do not write a test asserting
   that a day past `VarkaChrono.NARROW_MAX_DAYS` declines - it is computed
   now. Both PR #64 and PR #78 tripped over exactly this while merging past
   #73; there is no reason for a third task to.

4. **`QTR` is not a spelling `parseTruncLevel` accepts.** Section 1 says
   "`QUARTER` has `QTR`". It does not: `DateTimeUtils.parseTruncLevel` accepts
   `QUARTER` alone. `YEAR|YYYY|YY` and `MON|MONTH|MM` are as the recipe says,
   and `WEEK` is the only other date-level spelling. A test asserting
   `trunc(d, 'QTR')` fuses would fail; in the row engine that spelling returns
   NULL, like any other unrecognized format.

5. **The arithmetic re-verified on this checkout.**
   `python3 sql/varka/plans/verify_chrono_tails.py`: 3,652,059 days
   (`0001-01-01..9999-12-31`), zero mismatches for `ty`, `tm` and `tq` - this
   task's three forms - alongside `doy`, `last`, `woy` and `leap`. One caveat
   to carry: that script's own `leap()` uses `M = 167773`, the magic pair
   VARKA-34 and VARKA-36 each independently proved overflows a signed 32-bit lane
   product. It is harmless there, because Python's integers do not wrap, but
   it means **the script is an arithmetic oracle, not a constants reference**.
   The emitter's leap test must come from `emitLeapFlag`, whose constants are
   `VarkaChrono.YEAR_CENTURY_M = 41943` plus an `emitCarry` correction.

6. **VARKA-38 (PR #62) landed while this was being written**, and changes
   nothing here, which is worth saying rather than leaving to be rediscovered:
   it lets `AddDays`/`SubDays` take an `IntegerType` *column* offset, so
   `planWordRef` now ANDs both children's validity words through `andRef`.
   `TruncDate` has no offset operand at all, and the `WEEK` rewrite in 7.2
   builds `SubDays(d, lit 7)` with a literal, where `andRef` degenerates back
   to the date's own word. The one thing to carry from it: `analyze`'s
   `requireLiteralOffset` is no longer the blanket rule this recipe's
   contemporaries were written against, so copy `AddDays`'s *current* arm
   rather than one remembered from an older reading of the file.

### 7.2 The two decisions, settled by the owner

**`WEEK` is in, as a compiler rewrite with no emitter code.** Section 6 left
this to the owner ("say in the pull request if you want it; do not add it
unasked"), and VARKA-33 has since landed, which is the condition it named.
Spark defines `truncDate(days, TRUNC_TO_WEEK)` as
`getNextDateForDayOfWeek(days - 7, MONDAY)` with `MONDAY = 4`, and
`getNextDateForDayOfWeek(s, k) = s + 1 + floorMod(k - 1 - s, 7)`. VARKA-33's
compiler arm stores `dayOfWeek - 1` in the literal slot and its emitted form
is `d + 1 + floorMod(k - d, 7)` - the same expression. So

```
trunc(d, 'WEEK')  ==>  IRNextDay(SubDays(d, lit 7), lit 3)
```

over nodes that already exist: no IR node, no emitter arm, no new pinned
rendering, and no new weight. It inherits VARKA-33's wrap-for-wrap fidelity
with the row engine, which is what makes this an exact rewrite rather than an
approximately-right one. This is the `unix_date` pattern (VARKA-41): a compiler
arm that retires an expression by rewriting it onto existing nodes.

**Both `YEAR`/`QUARTER` lowerings ship, and the default is chosen on a
measured number**, per `FloorMod7` (three lowerings, one shipped, the rest
live reference variants) and VARKA-26 (`TOTAL` and `NARROWED` measured before
the default was picked). The two are close enough - roughly 27 against 38
emitted ops - that arguing it would be guessing.

### 7.3 The lowerings, restated for today's emitter

> **Updated by VARKA-53.** This section described the emitter before the
> Neri-Schneider month block landed, and following it as written would emit the
> 0-based form against a slot that no longer holds a month - which compiles, and
> produces plausible wrong dates. Both the slot layout and the `MONTH` lowering
> below are the corrected ones; `VARKA-53.md` 3.1 and 3.3 are the source.

The prefix leaves `days` in `t[0]`, `era` `t[1]`, `rem` (the March-based day
of year) `t[2]`, `century` `t[3]`, `yearOfCentury` `t[4]`, and two carry-scratch
masks in `t[6..7]` that no tail reads, so a tail may reuse them.

`t[5]` holds the **month numerator**, not a month: `num = 2141 * rem + 197913`,
whose high half is the month index on Neri-Schneider's 3-based axis (March = 3,
February = 14) and whose low half divided by 2141 is the *zero-based* day of
month. Under `VarkaEmitOptions.neriSchneiderMonth(false)` - the reference
variant - it holds `marchMonth` (`mp`) on the 0-based axis instead, so a tail
that reads it has to take the axis from the option rather than assuming one.

**`MONTH`, one form only, and it is now a single subtraction.** The recipe
writes `d - dom + 1`. The numerator's low half already *is* the zero-based day
of month, `dom0 = dom - 1`, so

```
trunc(d,'MONTH') = d - dom0
```

with no `+ 1` to undo and no month start to run forwards. That is the whole
tail: the day-of-month extraction this shares with `DayOfMonth` ends with a
`+ 1` that this lowering would only subtract again, so `trunc` reads the
numerator one step earlier and stops. Two ops on top of the prefix, no leap
flag, no variant.

On the 0-based reference axis the old derivation still holds and is worth
keeping, because the differential runs both: `emitChronoDayOfMonth` is
`rem - monthStart(mp) + 1` there, so `d - dom + 1` is identically
`d - rem + monthStart(mp)` - three ops, exact for every `mp` in `[0, 11]`.

**`SUBTRACT` variant (`YEAR`, `QUARTER`).** Section 2's forms:

```
L       = emitLeapFlag(reported year)                     // ~19 ops
jdoy    = rem >= 306 ? rem - 305 : rem + 60 + L            // ~4 ops
YEAR    = d - jdoy + 1
QUARTER = d - jdoy + qstart,   qstart = [1, 91+L, 182+L, 274+L][quarter - 1]
```

`quarter` comes from the existing `Quarter` tail (`(month + 2) / 3` through
`QUARTER_M`/`QUARTER_K`) over `emitChronoMonth`'s January-based month - see
risk 1. That tail is unchanged by VARKA-53: `emitChronoMonth` produces the same
January-based month on either axis, so everything downstream of it is axis-blind
and this variant needs no rework. The four-way select is three compares and three masked adds over a
starting value of 1, or a blend chain; they are the same op count. (A closed
form exists if the chain reads badly: with `q0 = quarter - 1`, the offsets
`{0, 90, 181, 273}` are `q0 * (179 + q0) / 2`, whose product is always even so
the halving is a shift. It buys two ops and costs a paragraph of explanation;
take it only if the measurement says the two ops matter.)

**`RECOMPOSE` variant (`YEAR`, `QUARTER`, and `MONTH` free as a cross-check).**

```
YEAR    = emitDaysFromCivil(year, 1, 1)
MONTH   = emitDaysFromCivil(year, month, 1)
QUARTER = emitDaysFromCivil(year, 3 * quarter - 2, 1)
```

`year` from `emitChronoYear`, `month` from `emitChronoMonth`. No leap flag
anywhere: `emitDaysFromCivil` does its own era arithmetic. Its independent
value beyond the measurement is that it gives VARKA-40's helper a **second
caller** - today only `emitAddMonths` calls it, so a defect in it that
`add_months`'s own day clamp happens to mask is currently invisible.

### 7.4 The IR node, and the level as a shape-bearing field

```java
record TruncDate(VarkaVectorIR days, TruncLevel level) implements Chrono {}

enum TruncLevel { YEAR, MONTH, QUARTER }
```

* `Chrono` membership is what makes `isChrono`, `weightOf`, `planWordRef`'s
  word aliasing and the prefix fragment work with no edit to any of them - the
  sealed family's own javadoc says as much. `LastDay` is the precedent.
* `level` is a record component, so it reaches `canonical` and
  `canonicalShallow` and therefore the shape hash: two levels emit different
  code, so they are two shapes and must not share a cached class. `Compare`'s
  `CompareOp` is the existing precedent for a shape-bearing enum field, and
  section 3 of this file already argued the point. Render `(trunc:YEAR <days>)`
  and `(trunc:YEAR 1)`.
* `WEEK` is deliberately **not** a `TruncLevel`: per 7.2 it never becomes a
  `TruncDate` node at all.
* An enum, not the recipe's `int level` - the house style for a shape-bearing
  constant, and it makes the emitter's inner switch exhaustive.

### 7.5 Files, and the edit sites

Mechanics per `VARKA-33.md` section 3; the switches are exhaustive, so
each omission is a compile error rather than a silent decline.

| file | what |
|---|---|
| `VarkaVectorIR.java` | the record, the `Chrono` permits clause, `TruncLevel`, and the two renderings |
| `VarkaLoopEmitter.java` | `childrenOf`, `analyze`, `planWordRef` (alias the child's word), `planSlots` (`TRUNC_TMP_COUNT`), `emitValue` (`case TruncDate n -> emitChrono(...)`), `chronoChild`, `weightOf`, and the new `emitChronoTrunc` plus `emitJanuaryDayOfYear`; once VARKA-48 lands, `tailReadsMarchMonth` too (`level != YEAR`) |
| `VarkaEmitOptions.java` | a `TruncDate { SUBTRACT, RECOMPOSE }` field, its `with...`, and its place in `canonical()` - which stays empty for `DEFAULTS`, so no production hash moves |
| `VarkaExpressionCompiler.scala` | the `TruncDate` arm, `foldTruncLevel`, and the `WEEK` rewrite |
| `VarkaLoopEmitterSuite.scala` | the `evalValue` arm, the boundary matrix, the variants-agree test, the opt-in sweep, both pinned fixtures |
| `VarkaShapeCacheSuite.scala` | the `everyNode` hash |
| `VarkaExpressionCompilerSuite.scala` | one compile test per level, the `WEEK` rewrite's shape, and one decline test per rejected shape |
| `VarkaDifferentialSuite.scala` | all four levels in one query, and the `DateType`-survives case |
| `VarkaEmitterParityBenchmark.scala` + its committed results | 7.7 |
| `m4/PLAN.md`, this file | the status row, and section 8 |

`TRUNC_TMP_COUNT` follows `ADD_MONTHS_TMP_COUNT`'s precedent - fresh named
slots rather than clever reuse of prefix scratch, which is the lesson PR #78
recorded after doing it the other way first. `SUBTRACT` needs the prefix's
eight plus `jdoy`, `qstart` and `emitLeapFlag`'s three scratch (with `t[6]`
doubling as its `maskA`, as `emitAddMonths` does); `RECOMPOSE` needs the
prefix's eight plus the twelve locals and two masks `emitDaysFromCivil` takes.
`planSlots` already holds the options, so allocate per variant.

**`weightOf`.** `MONTH`'s tail is about five ops on top of the roughly forty
the prefix costs; `YEAR`'s is about twenty-seven (the leap flag alone is
nineteen); `QUARTER`'s is thirty-five under `SUBTRACT` and forty under
`RECOMPOSE`. `CHRONO_WEIGHT` (50) is honest for `MONTH` and understates the
other two, the way it understated `DayOfYear` before PR #64 gave that its own
`DAY_OF_YEAR_WEIGHT = 70`. Nothing about today's grouping changes either way,
since all of them already exceed `GROUP_BUDGET` (16) - but per #64's own
review finding the number should be honest rather than convenient, so read the
level: `MONTH` weighs `CHRONO_WEIGHT`, `YEAR` and `QUARTER` weigh 75.

**The compiler arm** evaluates the foldable format through
`DateTimeUtils.parseTruncLevel` - the definition, never a re-implementation of
its aliases and case folding - and maps `TRUNC_TO_YEAR`/`TRUNC_TO_MONTH`/
`TRUNC_TO_QUARTER` to the three levels, `TRUNC_TO_WEEK` to the 7.2 rewrite,
and everything else to a decline with its own `sink.note` reason. It copies
`foldWeekday`'s shape exactly, including the lesson that evaluating a
foldable-but-computed expression can throw for reasons unrelated to the
format: that declines, it does not crash planning.

**Why an unsupported format declines rather than answering.** The row engine
returns NULL for the whole column when `parseTruncLevel` is below
`MIN_LEVEL_OF_DATE_TRUNC` - an unrecognized string, a timestamp-only level
like `'DAY'`, or a null format. Varka has no null-literal IR node, so it
cannot produce that column and declines instead. That is a decline for
correctness, not a gap to paper over, and each case gets a distinct reason so
VARKA-16's report says which one fired.

### 7.6 Tests

Section 4's list stands, with these changes:

* **No decline test for an out-of-range day** - 7.1, item 3.
* **`WEEK`**: a compiler test asserting it compiles to
  `IRNextDay(SubDays(col, lit), lit)` with literals 7 and 3 (the shape is the
  assertion - if the rewrite is wrong, this is where it shows), and a
  differential over a Sunday, a Monday, and a date in the first week of
  January, where the Monday of the week belongs to the previous year.
* **The two variants agree** on every level and every shape the emitter suite
  drives, at both widths, the way the `FloorMod7` reference-variant test does.
* **An opt-in exhaustive sweep** (`-Dvarka.sweep=true`) of each level against
  `DateTimeUtils.truncDate` over all 16,777,216 days `VarkaChrono` covers,
  under both variants. This is the gate that catches a wrong magic constant at
  the top of the range, which is the failure mode this family has now shipped
  twice (VARKA-34 and VARKA-36) and which no boundary list found either time. Note
  for whoever runs it: the property does not reach a forked test JVM through
  `build/sbt -D...`; use
  `build/sbt 'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.sweep=true"' ...`.
* **The pinned fixtures** take one level, not three - section 4 is right that
  one `(trunc:YEAR ...)` proves the rendering - and both are re-pinned from
  the values the failing assertion prints, never from arithmetic done by hand.
* **The `DateType` output** survives a second operation
  (`date_add(trunc(d, 'MONTH'), 5)`), as section 5 asks. `last_day` (PR #78)
  has since proved that path, so this is a regression check rather than a
  first proof.
* **The prefix fragment fires**: `SELECT trunc(d,'MONTH'), year(d)` emits one
  prefix, not two, asserted through the emitted method's byte count the way
  VARKA-32's own tests do.

### 7.7 The measurement that picks the default

A `trunc` section in `VarkaEmitterParityBenchmark`, shaped like the existing
calendar sections: the three levels under both variants, plus the Janino row
path, over 4096-row batches, at both vector widths, with the committed results
file regenerated in one run on an idle machine. The owner picks the default
from that table and the choice is recorded in section 8 with the numbers
behind it. Any ratio under 1.3x is re-checked by minimums on an interleaved
A/B before it is written down, per the standing rule.

### 7.8 Predictions, registered before the measurement

1. `SUBTRACT` beats `RECOMPOSE` on `YEAR` and `QUARTER` by 1.10x to 1.35x at
   AVX-512, and by less at 128-bit, because both are dominated by the shared
   prefix that neither of them changes.
2. `MONTH` lands within noise of `dayofmonth` itself - it is that tail with
   two ops changed - and beats the Janino row path by 4x to 8x.
3. The two variants agree bit for bit on every day of the covered range. If
   they disagree anywhere, the bug is in `emitDaysFromCivil` rather than in the
   subtract form, because the subtract form is the one
   `verify_chrono_tails.py` swept.
4. No committed number for an existing shape moves, and no emitted byte for
   `year`/`month`/`dayofmonth`/`quarter`/`add_months`/`last_day` changes. The
   two pinned fixtures move, once, for the new node type.
5. `WEEK` costs what `next_day` costs to within noise, since it is the same
   emitted code with one extra literal subtract in front of it.
6. `TruncDate` needs no new `FragmentKind`: it shares the existing chrono
   prefix through the key B1 already built.

### 7.9 Risks

1. **The quarter is January-based; `mp` is not.** `quarter` must come from
   `emitChronoMonth`'s January-based month, never from `mp` directly. Taking
   `mp` would pass on April-through-December dates and fail on
   January-through-March, which is exactly the shape of bug a boundary list
   built around February finds late.
2. **`emitLeapFlag` biases its own input.** It expects the plain reported year
   and adds `YEAR_BIAS` internally. Pre-biasing it - which is what this file's
   own section 2 invites by analogy with `last_day`'s first draft - gives a
   wrong flag only for far-future and far-past years, silently. That is the
   defect VARKA-36 shipped once; the sweep in 7.6 is what catches it.
3. **Two `emitLeapFlag` overloads** will exist on master if PR #64 lands
   first. Call VARKA-40's seven-parameter one, and say so in the commit.
4. **The `DateType` output** allocates a different Arrow vector in the
   evaluator than every `IntegerType` field in this family. `last_day` proved
   that path, so the risk is smaller than section 3 assumed - but the
   differential still has to chain a second date operation on top.
5. **Numbers moving under the task's own feet.** Commits 2 through 4 all touch
   emitted bytes; regenerate the parity file once, in commit 5.

### 7.10 Sequencing

Five commits, each green on its own:

1. **This re-plan**, the document alone.
2. **`MONTH` end to end**: the IR node with its level field, the compiler arm,
   `emitChronoTrunc`'s `MONTH` tail, both pinned fixtures re-pinned, and the
   compiler, emitter and differential tests. No leap flag and no variants -
   the smallest change that proves the node's plumbing, which is what section
   2 recommends landing first.
3. **`YEAR` and `QUARTER` under both variants**, the `VarkaEmitOptions`
   switch, the variants-agree test and the opt-in sweep.
4. **`WEEK`'s compiler rewrite** and its tests.
5. **The measurement**: the benchmark section, one regeneration run, and the
   owner's chosen default recorded in section 8.

### 7.11 Still out of scope

* **`date_trunc` at timestamp level** - int64 lanes, VARKA-29.
* **Reproducing the row engine's NULL** for an unsupported format, which needs
  a null-literal IR node the IR does not have. Declining instead - 7.5.
* **A non-foldable format**, per VARKA-33's rule for `next_day`'s weekday.

## 8. Outcome

Built in September 2026 against master at 0289e503b62, after VARKA-48, VARKA-51 to VARKA-55
and the tooling PRs had landed. The arithmetic in section 2 was right on the
first run under every combination the suites drive: both lowerings, both
prefix forms, both month axes, every boundary, and the whole covered range
through the emitted kernels (sweep step of the gate, zero mismatches on
16,777,216 days times three levels times eight variants). What needed
correcting was the recipe's picture of the emitter, and section 8.1 is that
list, in the order it bit.

### 8.1 Where the recipe misled, and what held

1. **`emitLeapFlag` is one slot in, one mask out.** Section 7.1 describes VARKA-40's seven-parameter helper. VARKA-34's follow-up replaced it with Huffner's
   perfect hash: `emitLeapFlag(cb, yearSlot)` takes the plain reported year,
   biases it itself, uses no scratch, and leaves a `VectorMask` on the stack.
   The `YEAR` and `QUARTER` tails call that; there is no second overload on
   master to avoid.
2. **There was no `emitJanuaryDayOfYear`.** VARKA-34's conversion sat inline in
   `emitChrono`'s `DayOfYear` arm. It is now a helper, factored out
   instruction for instruction, and the register test pins `dayofyear` at 43
   dense-loop `IntVector` calls before and after - the extraction's bytes did
   not move, which prediction 4 required.
3. **`tailReadsMarchMonth` throws on an unknown node** (VARKA-48), so the node
   had to declare which levels read the month numerator: `MONTH` and
   `QUARTER` do, `YEAR` does not under either form - the recompose form's
   January month is a constant. Section 7.5's "once VARKA-48 lands" clause was
   the right prediction of exactly this edit.
4. **The weights were 40 and 51, not 50 and 70.** Section 7.5's `CHRONO_WEIGHT`
   and `DAY_OF_YEAR_WEIGHT` figures predate VARKA-48's month elision and the
   perfect-hash leap flag. The shipped tails count 45 (`YEAR`), 36 (`MONTH`)
   and 62 (`QUARTER`) `IntVector` calls under the subtract form against
   `dayofmonth`'s 36 and `dayofyear`'s 43, so `MONTH` weighs `CHRONO_WEIGHT`
   and the other two carry the same eight-op mask allowance the day-of-year
   weight does: 53 and 70. The recompose form is 70, 74 and 79; a weight is a
   shape property and does not follow the option.
5. **`QTR` is indeed not a spelling** (7.1 item 4 was right), and the compiler
   suite pins it as "trunc with an unrecognized format". The four decline
   reasons are each pinned by string, the way VARKA-16's report needs them.
6. **The fuzzer's node list is hand-maintained** (`VarkaIrFuzzSuite`, PR
   #110), which no earlier recipe could have known: a new node type goes
   unfuzzed until its generator arm is added. Added, with the child's bound.
7. **VARKA-52 was not on master** when this was built (its PR #115 was open),
   so the compiler arm is the plain two-liner and `dayRange` - VARKA-52's
   interval analysis - has no `TruncDate` arm yet. It needs one: a truncated
   date lies in the child's interval shifted by `[-365, 0]`. Whichever of the
   two PRs merges second carries that arm; until then a calendar function over
   a `trunc` output would decline as an unknown producer rather than answer
   wrongly, which is VARKA-52's designed failure mode.
8. **`TRUNC_DATE_TMP_COUNT` is 24**, one size for both forms: the prefix's
   eight, the reported year, the month, the day, the day of year and the
   quarter, then the eleven scratch locals `emitDaysFromCivil` takes. Fresh
   named slots per `VARKA-36.md`'s lesson; the prefix's two carry masks
   `t[6..7]` are reused as `DayOfYear` reuses them.
9. **What held as written.** Section 2's three formulas; the level as a record
   component (both pinned fixtures moved once, for the new node, and the
   `DEFAULTS` hash did not); `emitChronoTrunc` as a tail method called from
   the switch, `emitChronoLastDay`'s shape; the `WEEK` rewrite onto
   `IRNextDay(SubDays(d, 7), 3)`, exact against the row engine on the Sunday,
   Monday and first-week-of-January rows; and the `DateType` output feeding
   `date_add` in the same chain, at the IR level and through SQL.

### 8.2 The MONTH form under the numerator

Section 7.3's single-subtraction `MONTH` is what shipped: `emitZeroBasedDayOfMonth`
is `emitChronoDayOfMonth` one step before its increment, and `trunc(d, 'MONTH')`
is `d` minus that. 36 `IntVector` calls against `dayofmonth`'s 36 - the increment
became the subtraction. Under the 0-based reference axis the same helper is
`rem - monthStart(mp)`, and the differential runs both axes.

### 8.3 The measurement, and the default

`VarkaEmitterParityBenchmark`, "year" section, regenerated at both widths by
`dev/varka_bench_regen.sh` on an idle machine (load 0.16 at start, canary within
1.2% on all three axes, governor `performance`). The A/B pairs are adjacent
cases in one run; every ratio is above 1.3x, so the standing rule's
minimum-based rerun was not needed. Rates in M rows/s.

| case | 256-bit, subtract / recompose | 128-bit, subtract / recompose |
|---|---|---|
| `trunc YEAR`, null-free | 2314.8 / 1585.4, **1.46x** | 872.2 / 547.8, **1.59x** |
| `trunc MONTH`, null-free | 3380.0 / 1201.7, **2.81x** | 1268.7 / 419.9, **3.02x** |
| `trunc QUARTER`, null-free | 1575.3 / 1086.2, **1.45x** | 565.6 / 371.1, **1.52x** |
| `trunc YEAR`, mixed nulls | 1809.9 / 1255.4, **1.44x** | 633.4 / 456.7, **1.39x** |
| `trunc QUARTER`, mixed nulls | 1316.7 / 952.9, **1.38x** | 502.9 / 354.1, **1.42x** |
| per-row `DateTimeUtils.truncDate` (the row path) | 192.8 | 192.7 |

For scale, `dayofmonth` (Neri-Schneider, null-free) is 3395.4 and 1257.1 at the
two widths and `year` is 3457.5 and 1334.4 in the same run.

**The default: `SUBTRACT`**, at every level. It was the provisional default
while the code was built and the numbers confirm it without a close call:
the recompose form pays a whole second era decomposition through
`emitDaysFromCivil` where the subtract form pays a leap flag and a handful of
lane ops, and the gap is widest exactly where the subtract form is cheapest
(`MONTH`, two ops on the prefix against a full recomposition). `RECOMPOSE`
stays a live reference variant: the sweep and the boundary matrix run it, and
it is `emitDaysFromCivil`'s second caller, which was half the reason to build
it.

**Predictions scored** (section 7.8).

1. *Subtract beats recompose by 1.10x to 1.35x at AVX-512, less at 128-bit.*
   Direction right, size wrong twice over: 1.38x to 1.46x at 256 bits, and
   *more* at 128 bits (1.39x to 1.59x), not less. The prediction assumed both
   forms were dominated by the shared prefix; the recompose form's own
   arithmetic (`emitDaysFromCivil`, twenty-odd ops with two divisions) is not
   small against a forty-op prefix, and at 128 bits every op costs twice the
   lane groups, so the extra work shows more, not less.
2. *`MONTH` within noise of `dayofmonth`, 4x to 8x over the row path.*
   3380.0 against 3395.4 (0.5%) and 1268.7 against 1257.1 (0.9%): confirmed.
   Against the row path it is 17.5x, past the predicted band - the band was
   guessed off `dayofweek`'s 8.8x over `LocalDate`, but `DateTimeUtils.truncDate`
   is slower per row than a bare `LocalDate.getYear` (192.8 against 481.5 M
   rows/s here), so the ratio is larger.
3. *The two variants agree bit for bit over the covered range.* Confirmed by the
   gate's sweep step, both forms under all four prefix variants.
4. *No committed number for an existing shape moves; no emitted byte of the
   other calendar nodes changes; the two pinned fixtures move once.* The byte
   claim is asserted (`dayofyear` at 43 before and after the refactor, the
   sharing test's byte-for-byte check); the calendar rows are unchanged
   (`year` null-free 3452.2 to 3457.5); the non-calendar rows moved 3-20% in
   both directions with every control within 0.1%, the machine-day variance
   `VARKA-54.md` 9.2 and `SKILLS.md` record for the memory-bound rows.
   The fixtures moved once each.
5. *`WEEK` costs what `next_day` costs.* Not measured separately: it is
   `next_day`'s own emitted code behind one literal subtract, and the
   compiler test pins that shape. A number would be `next_day`'s number.
6. *No new `FragmentKind`.* Confirmed: the prefix-sharing test passes on
   `trunc(d, 'MONTH')` beside `year(d)` with no change to `fragmentKey`.
