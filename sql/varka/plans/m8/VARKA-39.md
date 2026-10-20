# VARKA-39: `date - date`, the first mixed-width kernel

A recipe for a cheap agent, in the shape VARKA-33 established, with one
difference that has to be said first.

**This recipe was written before the machinery it depends on existed; section 0 (21 October 2026) checks it against what landed and overrides sections 2 and 4 where they differ.** VARKA-28 (lane-width conversion) and 29 (int64 lanes) had not started when it was
written, so every name it gives for their machinery is provisional. The
*semantics* below were checked against Spark and are not provisional; the
plumbing may be. If what VARKA-28 and VARKA-29 actually built does not look like what
section 4 assumes, **stop and say so in the pull request** rather than adapting
the recipe on the fly - the mismatch is more useful written down than worked
around.

**Depends on VARKA-28 and VARKA-29.** It does *not* depend on VARKA-30, and section 3
is why.

## 0. Admission check against master (21 October 2026, `a350674efe4`)

This recipe was written before rows 28, 29 and 26 existed. Read against the code that landed, the
semantics below hold and the plumbing is smaller than section 4 assumes: **no new IR node, no emitter
arm and no evaluator change are needed; `date - date` is one compiler arm that composes nodes that
already exist.** What was checked, in the code:

* **`SubtractDates` is not compiled.** Nothing in `sql/catalyst/src/main/java` names it, so today the
  expression reaches the row engine; `DateDiff` (an `int` day count, a different expression) is
  compiled by `VarkaChronoCompiler` and must stay as it is.
* **The long lane carries the result type already.** `VarkaNodeCompiler.laneOf` maps
  `DayTimeIntervalType` to the long lane beside `bigint` and `TIME`, and
  `VarkaKernelEvaluator.allocateVector` allocates a long-lane vector for it, so the output side
  (section 4's "eight bytes per row", the `DurationVector`) exists.
* **The width conversion exists** (row 28): `WidenLane(child)` takes an `INT` child and is exact in
  every mode; a kernel holding both lanes is driven at the long lane with its int values in the low
  half of the int species, so `date` columns read under their low-half masks.
* **`IntArith` works on either lane** (`requireSameLane`): `SUB` with `WRAP` over two widened
  dates is a long subtraction that cannot overflow, since both operands are within the int range.
* **A checked multiply has no vector overflow test at any lane yet** (`arithOver` declines it;
  `VarkaVectorWalk` throws on one). So the recipe's second overflow test (section 3,
  `multiplyExact`) cannot be a `FAIL` multiply. It is a **range guard first, then a `WRAP`
  multiply**: `GuardedRange(child, lo, hi)` serves both lanes, declines the batch when a live lane
  falls outside `[lo, hi]` and leaves the row engine to raise Spark's error on the row. `TIME +
  INTERVAL` (`VarkaTimeCompiler`) is the precedent for a decline-by-guard of a value the row
  engine would throw on. The guard's bounds are part of the IR, so two plans that differ in them
  never share a kernel.
* **The first overflow test is not needed.** In section 3's table the int subtraction `l - r` wraps
  only in int lanes; computed in the long lane after the widening it is exact, and the only
  remaining failure is `multiplyExact`, which the range guard on the difference covers exactly.
  Both failures decline the batch and the row engine raises Spark's own error for each, so one
  guard is enough; the differential test still compares the exception against the row engine's for
  each of the two input pairs (section 5, test 2), so whichever error Spark raises for each is what
  Varka's session raises.
* **Long literals exist**: `sink.longSlot(long)` (used by the bitwise family), so
  `MICROS_PER_DAY` enters as a long literal slot, from `DateTimeConstants`, never as a spelled
  number.

* **`SubtractDates` in this fork** is `SubtractDates(left, right, legacyInterval: Boolean)`,
  `nullIntolerant`, with no `failOnError` flag (`datetimeExpressions.scala:4859`).
* **The guard tests valid lanes only**, read in `VarkaVectorWalk` (`case GuardedRange`): the value
  passes through and the guard runs under the child's validity word, except over a column's stored
  values, where a null row's lane is checked on purpose (`Analysis.guardsStoredValues`; not this
  use). A null date therefore does not decline a batch; the emitter test must still include a null
  beside a value at each edge, as the proof.
* **A long-lane `WRAP` multiply is not new** (corrected 20 October 2026, see section 7): the SQL
  arithmetic arms are `isInt` only, but `VarkaTimeCompiler` already builds long `MUL` with `WRAP` for
  the TIME conversions; a unit test still confirms that a long `MUL` emits and answers right at both
  widths.

**The lowering, revised.**

```
diff  = IntArith(SUB, WRAP, WidenLane(end), WidenLane(start))        // long lane, cannot overflow
safe  = GuardedRange(diff, -106751991, 106751991)                     // declines the batch outside
out   = IntArith(MUL, WRAP, safe, longSlot(MICROS_PER_DAY))           // long lane, in range by the guard
```

`106751991 = Long.MAX_VALUE / MICROS_PER_DAY`; the bounds are the exact edges of the section 3
boundary test (106751991 succeeds, 106751992 declines), and `Math.abs` is still not used.
Validity is the AND of both operands', exactly as `DateDiff`'s, since `IntArith` and `WidenLane`
already carry it; `SubtractDates` is null-intolerant.

**The edits, revised (replaces section 4's IR, emitter, buffer and evaluator bullets).**

* **Compiler**, in `VarkaChronoCompiler` beside `DateDiff`: `case SubtractDates(l, r, false)`
  building the three nodes above over the two date operands, each compiled as `DateDiff`'s are;
  `case SubtractDates(_, _, true)` declines with `legacy CalendarInterval subtraction`.
* **Coverage**: the coverage table and its machine-readable twin list every expression the compiler
  admits (`VarkaCoverageSuite` checks "the table documents every expression the compiler admits"),
  so `date - date` needs its row; both files are regenerated by their tools, never edited.
* **Nothing else.** If a step below needs an IR node, an emitter arm or an evaluator change after
  all, stop and say why in the pull request; the check above is the reason the plan has none.

**Open checks the work must settle** (none changes the lowering; each is a place a surprise would
show):

1. Whether `Analysis` or the range analysis refuses a `WRAP` multiply under a guard on the long lane,
   or declines the kernel for another reason; the first thing to run is `dev/varka_emit.sh` on
   `d1 - d2` and read the shape.
2. Whether the guard's decline reaches the row engine with the right exception on the long lane at
   both widths (at 128 bits an int64 lane holds two rows, so the conversion's lane-count handling is
   exercised differently; section 5 already runs both).
3. Whether a kernel of this shape lands among the fuzz grammar's draws: do **not** add a grammar draw
   here (it would reprice the emit cost model); the differential and emitter suites cover it, and a
   grammar draw is a row of its own if the fuzzers should reach it.
4. The expected speed relative to the row path is a benchmark of its own, in its own class and its own
   pull request (the repository's rule for a feature family); this row proves correctness and fusion.

**Done when**: `SELECT d1 - d2` over date columns fuses to a kernel on the long lane at both widths
(`dev/varka_emit.sh` output committed in the pull request description), section 5's five tests pass,
the overflow boundary behaves as section 3 says in both directions, `datediff` is unchanged, the legacy
variant declines with its reason, the coverage table and its twin carry the row, and this plan's
section 7 records what was found.

## 1. What you are building

`d1 - d2` where both sides are dates. This is **not** `datediff`, which Varka
already compiles and which returns an `IntegerType` day count. Since Spark 3.2
the `-` operator between two dates returns a **`DayTimeIntervalType(DAY)`**,
whose physical type is `long` **microseconds**. It reaches the compiler as
`SubtractDates` (`BinaryArithmeticWithDatetimeResolver:152`).

Spark's reference, from `SubtractDates.evalFunc`:

```scala
Math.multiplyExact(Math.subtractExact(leftDays, rightDays), MICROS_PER_DAY)
```

Two things about that line matter more than anything else in this task:

* **It throws, and not only under ANSI.** `SubtractDates` has no `failOnError`
  flag; the `*Exact` calls are unconditional. Whatever this kernel does about
  overflow, it must do always, not only when ANSI mode is on.
* **`legacyInterval` is a different expression.** With
  `spark.sql.legacy.interval.enabled=true` the same node returns a
  `CalendarIntervalType`, which is out of scope for the whole engine. Decline
  that variant explicitly rather than assuming nobody sets the flag.

So this is the engine's first kernel with int32 inputs and an int64 output, one
width conversion, and an error path. That is exactly the shape VARKA-28 and VARKA-29
were built for, on the smallest possible expression - which is why it is worth
doing first among their consumers.

## 2. The lowering

```
diff = l - r                              // int32, allowed to wrap; see below
wide = (int64) diff                       // the width conversion, VARKA-28
out  = wide * 86400000000L                // MICROS_PER_DAY, int64 multiply
```

`MICROS_PER_DAY` is in
`common/unsafe/.../DateTimeConstants.java`; use the constant, do not spell the
number in the emitter.

The `-` wraps rather than throwing, and the overflow is detected separately -
section 3 - because a vector lane cannot throw.

## 3. Overflow, and why this task does not need VARKA-30

Both `*Exact` calls can fail, on different inputs:

| what overflows | when | test |
|---|---|---|
| `subtractExact(l, r)` | the int subtraction wraps | `((l ^ r) & (l ^ diff)) < 0` |
| `multiplyExact(diff, MICROS_PER_DAY)` | `diff` is more than about 106.75 million days | `diff > 106751991 \|\| diff < -106751991` |

The multiply bound is `Long.MAX_VALUE / MICROS_PER_DAY = 106751991`, and it is
exact at the boundary in both directions: 106751991 days is representable,
106751992 is not. **Do not write it as `Math.abs(diff) > ...`** - `diff` can be
`Integer.MIN_VALUE`, whose absolute value is itself.

Neither test needs to reproduce Spark's exception, because **the kernel does
not have to throw. It has to decline.** VARKA-26 built exactly this channel: OR
the two conditions into the body's guard mask, return the status bit from
`run`, and `VarkaBatchDeclined` routes the batch to the row engine - which then
performs the same subtraction in Scala and throws the identical exception,
attributed to the identical row, because it *is* the row engine. The kernel's
only obligation is to notice.

That is the whole reason this task does not depend on VARKA-30. VARKA-30 exists
for expressions where declining is too expensive because overflow is common;
here it is astronomically rare - a date range of 292,000 years - so the batch
fallback costs nothing anyone will measure. If VARKA-30 has landed by the time
you start, **do not use its machinery anyway**; say in the pull request that
you did not need it.

Reuse VARKA-26's guard rather than adding a second mechanism. If the guard as
built is specific to the chrono nodes, generalising it is in scope, and saying
so in the pull request is better than copying it.

## 4. The edits

Mechanics per `VARKA-33.md` section 3. What is specific, and provisional
where marked:

* **IR**: `SubtractDatesMicros(VarkaVectorIR end, VarkaVectorIR start)` - a
  binary node like `DateDiff`, rendering as `(subDatesMicros <end> <start>)`.
  Its lane type is **not** `INT`: whatever VARKA-29 named the int64 lane type,
  this node returns it while both children return `INT`. That mismatch is the
  first thing to check against what 29 actually built.
* **Emitter**: the four routine cases plus an `emitValue` arm. Validity is
  `andRef` over both children, exactly as `DateDiff` does - copy that line.
* **The output buffer is eight bytes per row, not four.** `emitBody` computes
  `dataBytes = (long) length * 4`. If VARKA-29 did not already make that
  per-output, this task cannot proceed without doing so - and that is a change
  to shared machinery, so raise it rather than making it silently.
* **Compiler**: `case SubtractDates(l, r, false) => ...` producing the node,
  and `case SubtractDates(_, _, true) => sink.note("legacy CalendarInterval
  subtraction", ...)` declining the legacy variant.
* **Evaluator**: `allocateVector` needs an arm for `DayTimeIntervalType`, whose
  Arrow vector is a `DurationVector` with microsecond units
  (`ArrowUtils:117`), eight bytes wide. `isArrowBacked` is unaffected - the
  *inputs* are still dates.

## 5. The tests

1. **Emitter suite**: the usual matrix over every null pattern, with an oracle
   of `(l.toLong - r.toLong) * 86400000000L` on the in-range cases. Note the
   oracle must be computed in `Long`, or it will reproduce your bug rather than
   catch it.
2. **The two overflow cases, separately**, because they are different code
   paths: a pair whose difference overflows int (`Int.MaxValue` and
   `Int.MinValue`), and a pair whose difference is in range but beyond
   106751991 days. Both must set the status bit; neither may produce a value.
   Test the boundary in both directions: 106751991 must succeed, 106751992
   must decline.
3. **Differential**: `SELECT d1 - d2 FROM ...` over the existing date-pair
   fixture, with nulls; and an overflow query asserting that Varka's session
   raises **the same exception as the row engine's**, compared by running both
   rather than by naming an exception class.
4. **A decline test** with `spark.sql.legacy.interval.enabled=true`.
5. `datediff(d1, d2)` must still compile to `DateDiff` and be unaffected. It is
   a different expression with a different type and the two must not be
   confused - in the code or in the tests.

Then VARKA-33's section 4's command block, unchanged, at both widths. At the
narrow width an int64 lane holds two rows, so the conversion's lane-count
handling is exercised differently there; that is the point of running both.

## 6. Explicitly out of VARKA-39

* **`timestamp - timestamp`** (`SubtractTimestamps`) - the same shape one type
  up, and a reasonable follow-up, but it is int64 in as well as out and brings
  the timezone question with it.
* **Reproducing the exception in vector code.** Section 3.
* **The legacy `CalendarInterval` result.**
* **Any other interval-typed expression.** `DayTimeIntervalType` becoming a
  readable *input* type is a bigger door than this task needs.

## 7. Outcome

*20 October 2026.* `date - date` is fused: one compiler arm in `VarkaChronoCompiler`, exactly the
lowering of section 0, and nothing else in the engine moved.

```
(int:MUL:WRAP (guardedRange (int:SUB:WRAP (widen col:0) (widen col:1)) -106751991 106751991) lit:0:long)
```

`dev/varka_emit.sh "d1 - d2" --columns d1:date,d2:date` fuses it to `FusedOutput(0)` on the long
lane, 8 lines, 337 bytes dense epilogue and 526 masked, and the output at `--width=16` is identical
(the op counts do not depend on the width; the lane count is a run-time property of the kernel).
`SubtractDates(_, _, true)` declines with `legacy CalendarInterval subtraction`.

**What the tests settled.**

* The guard does what section 0 says at both widths: 106751991 days answers, 106751992 and
  `Integer.MIN_VALUE`/`MAX_VALUE` differences decline with the value-range status, in both
  directions; a pair whose int difference overflows (`MAX - MIN`) declines through the same guard,
  since the widened subtraction is exact; a null beside a value at each edge does not decline
  (the guard reads valid lanes only), and a valid overflow beside a null still does.
* The differential suite runs both engines on five overflow tables and compares what each did
  (`VarkaMaybeThrows`), so the error a user sees is the row engine's by construction.
* Open check 1 (does `Analysis` refuse a `WRAP` multiply under a guard on the long lane): no.
  Open check 2 (the decline reaching the row engine at both widths): yes, the emitter suite runs
  both lane counts. Open check 3: no grammar draw added.

**The gap between section 4 and what was built.** Section 4 assumed a `SubtractDatesMicros` IR node
with an `INT`-children, `LONG`-result lane mismatch, four emitter routines and an `emitValue` arm,
a per-output buffer width in `emitBody` and a `DayTimeIntervalType` arm in `allocateVector`. Rows 28
and 29 had built all of the plumbing generically: `WidenLane` for the mismatch, `IntArith` on either
lane, a lane-typed output and `allocateVector` for `DayTimeIntervalType`; so the row is a composition
of existing nodes. Section 3's first overflow test (`subtractExact`) disappears: the subtraction runs
widened and cannot wrap, and the range guard on the count is the only test, being the multiply's own
range. Section 3's "multiplyExact" test could not be a checked multiply (none has a vector overflow
test), hence guard then `WRAP`.

**Not this row's first long-lane multiply.** The TIME conversions (`VarkaTimeCompiler`) already emit
long `WRAP` multiplies by a nanosecond constant, so this row is the first date kernel that uses one,
not the first emission; `VarkaEmitterMixedLaneSuite.longMultiply` now also covers it by a unit test at
both widths.

**Not done.** The width audit (`width_audit.json`, which is why the documentation's last column reads
"not audited" for the row) was not run for it; it is regenerated by its own tool and the row's kernel
uses only nodes the audit already covers. `--width=16` above is `dev/varka_emit.sh`, not that audit.

**Two things the surrounding guards required.** `sql/varka/emitted_bytes.json` pins every coverage
row's bytes and so changed (regenerated with `VARKA_BYTES_REGEN=true`; the new row plus the option
arms' aggregate hashes). `VarkaCoverageDifferentialSuite` wants every guarded row to have rows outside
its domain or to be listed as a total domain: the fixture's dates are within the calendar, under three
million days apart, so `d2 - d` is listed as one, with `VarkaDateMinusDateSuite` taking the dates past
the bound. Adding a guarded coverage row therefore touches `coverage.json`, `docs/sql-varka.md`,
`emitted_bytes.json` and that list.
