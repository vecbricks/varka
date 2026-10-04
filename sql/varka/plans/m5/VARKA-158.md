# VARKA-158: group E, the time_to_* and time_from_* conversions, and two honest declines

*Opened 20 September 2026 from `VARKA-102.md` 9.3 and done the same evening.*

## 1. What was missing

The compiler recognises a `TIME` expression by the `DateTimeUtils` method its
`RuntimeReplaceable` rewrites into, through a table built from a list of nine
expressions. `time_to_millis`, `time_to_micros`, `time_from_seconds`,
`time_from_millis` and `time_from_micros` postdate that list, so they reached
the compiler as a `StaticInvoke` the table did not hold and declined as
`unsupported expression`; and the table's completeness guard checked the table
against its own list, which could not see them. The two decimal-valued
expressions, `second(t)` with its fraction and `time_to_seconds`, declined with
"not lowered yet", which section 2.3 had already said was the wrong reason.

## 2. What was built

**The five conversions**, as `DateTimeUtils` computes them. `timeToMillis` and
`timeToMicros` are `floorDiv` of a non-negative count, so a truncating
`ConstDivide` by 10^6 and 10^3 on the long lane, returning a bigint. The three
`timeFrom*` are `multiplyExact` under the conversion's own range check, which
throws unless the result is inside the day: the count is guarded with
`GuardedRange` to `[0, (NANOS_PER_DAY - 1) / unit]`, inside which the wrapping
multiply cannot overflow and the product is a TIME, and a count outside declines
the batch to the row engine, which raises Spark's error - the same pattern as
`t + dt`'s guard. A count that is not on the long lane (an int column, a decimal,
a double) declines where its leaf does.

**The table** holds the six new entries (`time_to_seconds` included, so it
declines by name), and the guard is registry-driven: every built-in function
whose class is a `TimeExpression` must be in the suite's list, so a function
Spark adds cannot stay out of the table unnoticed.

**The declines.** The two decimal-valued expressions now say what waits: the
value is an unscaled long the lane holds and the column Arrow keeps it in is
sixteen bytes a row that no Varka output writes yet (VARKA-157).

## 3. Tests

The compiler's trees for the five and for a round trip; the decline text; the
registry guard. `VarkaTimeArithmeticSuite` agrees with the row engine over every
second of the day at three precisions for the two divisions, and for the three
multiplies from a bigint second-of-day column and from their own divisions'
results, through both consumers; a table holding 86400 and -1 raises Spark's own
error under Varka from the same rows. Four coverage rows, so the `TIME` surface's
table (VARKA-105) can carry them at group B's price.

## 4. A fixture lesson from CI

The first push carried the coverage row `time_from_seconds(l)`. The coverage
suite fused it and `VarkaTimeArithmeticSuite` agreed with the row engine over
its own second-of-day column, but `VarkaCoverageDifferentialSuite` runs every
coverage row over a fixture whose `l` spans the bigint range, and a count of
five billion seconds is not a time of day: the row engine raised Spark's error
and the test failed on the exception. The int column the fixture keeps in 1..12
is not a way out: the compiler has no widening cast from the int lane to the
long one, so `time_from_seconds(i)` declines (VARKA-28's narrowing inside a tree
has no widening twin yet). The row now reads `time_from_millis(time_to_millis(t2))`,
a count inside the day by construction, which exercises the same guarded
multiply at the millisecond unit; the seconds unit is covered by
`VarkaTimeArithmeticSuite` over its own second-of-day column. The lesson - a
coverage row is also a differential query over that fixture - is in the testing
skill.

