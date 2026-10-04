# VARKA-39: `date - date`, the first mixed-width kernel

A recipe for a cheap agent, in the shape VARKA-33 established, with one
difference that has to be said first.

**This recipe was written before the machinery it depends on existed.** VARKA-28 (lane-width conversion) and 29 (int64 lanes) had not started when it was
written, so every name it gives for their machinery is provisional. The
*semantics* below were checked against Spark and are not provisional; the
plumbing may be. If what VARKA-28 and VARKA-29 actually built does not look like what
section 4 assumes, **stop and say so in the pull request** rather than adapting
the recipe on the fly - the mismatch is more useful written down than worked
around.

**Depends on VARKA-28 and VARKA-29.** It does *not* depend on VARKA-30, and section 3
is why.

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

Filled in when the work lands. This recipe especially wants its outcome section
filled: it was written against machinery that did not exist, so the gap between
what section 4 assumed and what VARKA-28 and VARKA-29 actually built is the most
useful thing it can report.
