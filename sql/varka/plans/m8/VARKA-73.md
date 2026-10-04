# VARKA-73: a stopping rule for the guard walk

## 1. Where this came from

`m4/PLAN.md` row 73 and section 2.37, opened out of VARKA-70's fuzz
run, and its debt register entry "The guard walk does not stop at a node that
re-bases the day".

`Analysis.collectGuardedProducers` calls
`collectColumnOffsetProducers(chronoChild(node), ...)` for every `isChrono`
node, and that walk is six lines with no stopping rule: it adds every
`AddDays`/`SubDays` with a column offset anywhere in the subtree and then
recurses through `childrenOf` unconditionally. So a producer is guarded
against `[NARROW_MIN_DAYS, NARROW_MAX_DAYS]` even when the value the calendar
node actually decomposes is something else entirely.

The guard is not wrong. A batch whose producer leaves the range is declined
and recomputed on the row engine, so the answers are right. What is lost is
fusion, and what is paid is the guard's emitted bytes and its per-lane
compares on every batch of that shape.

**How it was found is part of what it is.** Not by reading the emitter: by
running `VarkaIrFuzzSuite` at 1.84 million iterations across twenty jobs on 7
September 2026, where every one of the twenty stopped on a shape of this
form. At the shipped budget of 300 iterations it is unreachable.

## 2. The admission check, done - and it overturns section 2.37's premise

2.37 said the task should open by asking "whether any SQL shape observes the
difference at all", and expected the answer to be no:

> Through the compiler this shape never reaches the emitter. `dayRange` has
> no rule for a mod-7 node, so it returns `Unknown`, and `checkedForCalendar`
> declines the entry at compile time [...] only a caller that builds IR
> directly - `VarkaIrFuzzSuite`, and any future planner-side rewrite -
> reaches the over-guard.

**That is false, and `make_date` is the hole.** `dayRange` gives
`IRMakeDate` a `Bounded` interval from VARKA-42's published years *without
looking at its children* - correctly, because make_date's output depends only
on its three int arguments and the node is self-guarding. So anything
underneath it is admitted, whatever it is, while the emitter's walk still
descends into it and guards what it finds.

Measured on master, `VarkaExpressionCompilerSuite`'s `fuses` and
`VarkaLoopEmitterSuite`'s body sizes:

| shape | fuses? | guard emitted? |
|---|---|---|
| `year(make_date(2020, 1, dayofweek(date_add(d, off))))` | **yes** | **yes** |
| `month(make_date(2020, 1, weekday(date_add(d, off))))` | **yes** | **yes** |
| `year(make_date(2020, 1, dayofweek(d)))` (no producer) | yes | n/a |
| `year(date_from_unix_date(dayofweek(date_add(d, off))))` | no - "day producer the calendar range analysis does not bound" | n/a |

The last row is 2.37's own example and the section was right about it: that
route declines at compile time. The first two are ordinary SQL, they fuse,
and the emitted kernel carries a guard the shape cannot need - the masked
loop grows from 1274 to 1317 bytes and the masked epilogue from 1296 to 1344,
which is the guard block and nothing else.

**Why that guard cannot be needed.** `dayofweek` and `weekday` are mod-7 over
the day, exact for every int by construction (VARKA-14's magic is a division
of a value already reduced, and the lowering has no range condition). Their
results are 1..7 and 0..6, which go into `make_date` as a day of month, and
`make_date` is self-guarding on its own year (VARKA-42). So the producer's
value never reaches a civil-from-days decomposition, and no batch of this
shape can be wrong because the producer left `[NARROW_MIN_DAYS,
NARROW_MAX_DAYS]`.

**One false lead, recorded so it is not re-followed.** The first shape tried
was `year(make_date(2020, 1, dayofmonth(date_add(d, off))))`, which also
fuses and also emits the guard - but there the guard is *correct*:
`dayofmonth` decomposes its input, so the producer's value does reach a
decomposition. The distinction that matters is not "is there a node between
the producer and the calendar node" but "does that node decompose the day or
re-base it", which is exactly the distinction the stopping rule has to encode.

**So the task is needed.** It is not the decline 2.37 expected.

## 3. What is actually wrong, stated once

Two analyses need the same fact about each node - *does this node pass a day
through to a decomposition, or does it produce a bounded quantity of its own*
- and neither has it:

* `dayRange` (`VarkaExpressionCompiler`) knows the answer for `MakeDate`,
  `ThursdayOf`, `TruncDate` and the shifts, and has no arm for the mod-7
  family, so it answers `Unknown` and declines.
* `collectColumnOffsetProducers` (`VarkaLoopEmitter.Analysis`) has no notion
  of the question at all and descends everything.

Where the first says `Bounded` and the second keeps walking, the result is an
unnecessary guard. Where the first says `Unknown`, the entry declines and the
second never runs. Both are the same missing fact, seen from the two sides of
the compiler, and 2.37 is right that the halves must move together or drift
again.

## 4. The design

### 4.1 The node set, in one place

A predicate over `VarkaVectorIR`: **does this node hand a day to its parent
that its own children's day values determine?** Descend through the nodes
that do - `AddDays`, `SubDays`, `Greatest`, `Least`, `IfElse`, `NextDay`,
`ThursdayOf`, `LastDay`, `AddMonths`, `TruncDate` - and stop at the nodes
that do not: `MakeDate`, the mod-7 family (`DayOfWeek`, `WeekDay`,
`DayOfWeekIso`), `WeekOfYear`, `YearOfWeek`, `DateDiff`, and every calendar
field extraction.

`MakeDate` is in the stop set and is the one the admission check found. It
is also the one that proves the set is not arbitrary: a node stops the walk
exactly when `dayRange` can bound it without consulting its children.

### 4.2 The emitter half

`collectColumnOffsetProducers` consults the predicate before recursing. That
is the whole change, and it is the half that removes the unnecessary guard
from the shapes in the table above.

### 4.3 The compiler half, which is optional and should be weighed

`dayRange` could gain arms for the stop set - a mod-7 node is `Bounded(1, 7)`
regardless of its child - which would make
`year(date_from_unix_date(dayofweek(date_add(d, off))))` fuse instead of
declining. **This plan does not assume that is worth doing.** It is a new
fusion rather than a removed cost, its shapes are contrived, and it enlarges
what `dayRange` promises at exactly the seam milestone 5's VARKA-84 exists to
rebuild. The recommendation is to ship 4.2 and leave 4.3 to VARKA-84, with
this section as the note of what it would unlock - but the option is priced
here rather than discovered later.

### 4.4 Its relation to VARKA-84

`m5/PLAN.md` 2.15 rebuilds `dayRange` and `intBound` as one lattice
because they "disagree about what a runtime guard proves", which is where
three of VARKA-63's review bugs lived. This task is a third analysis in the
same family, and its predicate is the same fact the lattice would carry. If
84 lands first, 4.1's predicate is a query on it; if this task lands first,
84 inherits the predicate rather than inventing a third copy. Either order
works and the plans must name each other, which is what this section is.

## 5. The measurement, and what it may not claim

The change removes emitted bytes and per-lane compares from a shape that has
**no committed benchmark case**. Per `m4/PLAN.md`'s standing habit -
a baseline is committed before the improvement lands - the sequencing is:

1. A parity benchmark case for `year(make_date(2020, 1, dayofweek(date_add(d,
   off))))` at both widths, committed as its own PR, with the guard as it is
   today.
2. The stopping rule, measured against that case as an A/B in one run.

What the numbers may claim is the guard's own cost on this shape: two
compares, a mask OR and an accumulator per lane group, against a body that is
otherwise a mod-7 and a make_date. What they may **not** claim is a
whole-query speed-up, because the shape is not one anybody runs; the honest
framing is that the cost is removed where it was never needed, and the
correctness story - a batch no longer declined for a producer nothing
decomposes - is the part that matters to a user.

If the measured difference is inside the band (`dev/varka_bench_band.py`),
that is a legitimate outcome and the task ships on the decline count and the
byte count instead.

## 6. Tests

* The four shapes of section 2's table, as compiler-suite assertions with the
  fusion verdict and the decline reason pinned, including the `dayofmonth`
  false lead asserted as *still guarded* - that is the assertion that stops a
  future stopping rule from being written too aggressively.
* `VarkaLoopEmitterSuite`: the `make_date` shapes emit the same bytes with
  `guardDayProducers` on and off after the change, while
  `year(date_add(d, off))` still differs - the guard is removed where it is
  useless and kept where it is not.
* A run of `VarkaIrFuzzSuite` at the scale that found this - the plan records
  the iteration count and seed that reproduces - showing the shape no longer
  stops it.
* `VarkaDifferentialSuite`: the `make_date` shape over a fixture whose
  offsets push `d + off` outside the narrow range, asserting the batch is
  **not** declined after the change and the answers match the row engine.
  This is the user-visible half: today those batches fall back.

## 7. Verification

    dev/varka_gate.sh
    dev/varka_precommit.sh --working-tree

Task-specific: no emitted byte moves for any shape whose producer really does
reach a decomposition, asserted rather than eyeballed, since that is the way
this change could be wrong.

## 8. Risks

1. **The stopping rule stops too early**, and a producer that does reach a
   decomposition loses its guard - a wrong answer rather than a slow one.
   This is the one real risk. The `dayofmonth` false lead is the test that
   guards it, and the fuzz run at scale is what would find the arm nobody
   thought of.
2. **The node set drifts from `dayRange`'s again**, which is the defect this
   task is fixing. 4.4 is the answer: name VARKA-84 and hand it the predicate.
3. **A new node type is added later and defaults to the wrong side.** The
   predicate should be exhaustive over the sealed hierarchy rather than a
   `default -> descend`, so a new node is a compile error and not a silent
   over-guard or, worse, a silent under-guard.

## 9. Explicitly out of scope

`dayRange`'s new arms (4.3), unless the owner takes them; the lattice itself,
which is VARKA-84; VARKA-52's and VARKA-60's guards, which this does not touch;
and `VarkaIrFuzzSuite`'s own budget, which stays at 300 iterations with the
scale run a manual one.
