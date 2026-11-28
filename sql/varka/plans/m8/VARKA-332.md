# VARKA-332: The integral and boolean casts in the three modes

*Changed on 28 November 2026: sections 11 to 14 are new and are the plan of what is left, written
for a builder with nobody to ask; sections 0 to 10 are kept word for word as the record. Where
section 11 differs from them it wins, and section 14 lists each difference.*

## 0. What exists now (admission check)

From master at `ef32e007d3a` (30 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/`), and from row 481's plan
on the local branch `varka-481` (`0e309d43adb`, not merged). The row's text is the milestone plan's
2.2 and 2.4. Spark's facts were read in the fork's sources; nothing here was run.

1. **Spark's modes.** `ansiEnabled` is true under ANSI and under TRY
   (`expressions/Cast.scala:720-722`); `canUseLegacyCastForTryCast` exempts only string sources
   (`:1602-1612`), so every pair here runs the ANSI code inside a `catch` that answers null, in
   the interpreted path (`:1586-1597`) and in codegen (`:1706-1716`). TRY is therefore "null
   exactly where ANSI throws, ANSI's value elsewhere" for every pair of this row.
2. **Integral to integral** (`Cast.scala`, `castToLong` `:1142-1166`, `castToInt` `:1177-1208`,
   `castToShort` `:1211-1272`, `castToByte` `:1275-1338`):
   - LEGACY: `numeric.toLong(b)`, `numeric.toInt(b)`, `numeric.toInt(b).toShort`,
     `numeric.toInt(b).toByte` (`:1161`, `:1203`, `:1267`, `:1333`), in codegen `(byte) c` and its
     kin (`:2508`, `:2546`, `:2583`): Java's narrowing, the low bits kept.
   - ANSI: `LongExactNumeric.toInt` for `BIGINT` to `INT` (`types/numerics.scala:112-118`) and
     `CastUtils.shortToByteExact`, `intToByteExact`, `longToByteExact`, `intToShortExact`,
     `longToShortExact` for the narrow targets (`expressions/CastUtils.java:51-73`; dispatched at
     `Cast.scala:1243-1246`, `:1307-1312`, codegen `:2411-2440`): `v == (target) v` or throw.
   - The widenings are exact in every mode.
3. **The error.** `QueryExecutionErrors.castingCauseOverflowError(t, from, to)`
   (`sql/catalyst/src/main/scala/org/apache/spark/sql/errors/QueryExecutionErrors.scala:79-89`):
   a `SparkArithmeticException` of condition `CAST_OVERFLOW`, SQLSTATE `22003`, with four
   parameters - `value` (`toSQLValue(t, from)`, so `128` for an int, `128L` for a bigint),
   `sourceType`, `targetType` and `ansiConfig` - and the message "The value <value> of the type
   <sourceType> cannot be cast to <targetType> due to an overflow. Use `try_cast` to tolerate
   overflow and return NULL instead." (`common/utils/src/main/resources/error/
   error-conditions.json:1009-1014`). A kernel never raises: a batch holding a value Spark raises
   on declines, and the row engine raises this error on the first such row, so the error matches
   Spark's by construction, as `CAST(bigint AS INT)` under ANSI does today.
4. **Boolean to and from integral.** To an integral type, `if (b) 1 else 0` in every mode
   (`Cast.scala:1148`, `:1183`, `:1221`, `:1285`; codegen `:2497`, `:2535`, `:2571`, `:2608`);
   from one, `x != 0` (`:862-869`, codegen `:2323-2324`). Neither can fail.
5. **Interval casts with integral types** (`catalyst/util/IntervalUtils.scala:814-995`). Spark's
   legality admits every field range both ways (`Cast.scala:302-308`, `:134-135`), multi-field
   ranges included. The helpers throw `CAST_OVERFLOW` whatever the mode, so LEGACY raises too,
   ANSI raises, TRY nulls:
   - `intToYearMonthInterval` (`:814-828`): `multiplyExact(v, 12)` for a YEAR end, `v` for a
     MONTH end (`YEAR TO MONTH` ends in MONTH). Overflow past `[-178956970, 178956970]`.
   - `longToYearMonthInterval` (`:830-839`): `v.toInt` checked first, then the int rule.
   - `intToDayTimeInterval` (`:886-902`): `multiplyExact` for a DAY end (overflow past
     `[-106751991, 106751991]`), a plain product for HOUR, MINUTE and SECOND, which cannot
     overflow from an int.
   - `longToDayTimeInterval` (`:904-919`): `multiplyExact` for every end: the bounds are
     `Long.MAX_VALUE / unit`, 106751991 days, 2562047788 hours, 153722867280 minutes and
     9223372036854 seconds, symmetric (each unit has a factor 5, so none divides `2^63`).
   - A `TINYINT` or `SMALLINT` source goes through the int rule (`Cast.scala:1110-1115`,
     `:1130-1135`) and never overflows.
   - `yearMonthIntervalToInt` (`:855-860`): `v / 12` (YEAR end) or `v`; `yearMonthIntervalToShort`
     and `ToByte` (`:862-884`) check the quotient's range and raise with the interval as `value`.
     To `BIGINT` it is the int widened (`Cast.scala:1164-1165`), which cannot fail.
   - `dayTimeIntervalToLong` (`:921-928`): `v / unit`, Java's truncating division; `ToInt`,
     `ToShort`, `ToByte` (`:956-990`) check the quotient's range and raise.
6. **`TIME` to integral is a three-way split the row text does not mention.** `timeToLong` is
   `floorDiv(nanos, 10^9)` (`Cast.scala:1038-1040`); to `BIGINT` and `INT` it cannot fail
   (`:1155`, `:1197`); to `SMALLINT` and `TINYINT` an out-of-range second is **null under
   LEGACY** through `errorOrNull` (`:1168-1174`, `:1234-1242`, `:1298-1306`; codegen
   `:2354-2379`), raises under ANSI and nulls under TRY. `DATE` to a number or `BOOLEAN` is null in
   every row under LEGACY and illegal otherwise (`Cast.scala:859-861`, `:1150`, `:1185`, `:1223`).
7. **What fuses today.** The compiler's integral casts are four arms in `arithmeticArm`
   (`VarkaNodeCompiler.java:509-536`): `CAST(bigint AS INT)` (`narrowing`, `:701-718`) is
   `NarrowLane` under LEGACY, `NarrowLane(GuardedRange(x, int range))` under ANSI, and declines
   under TRY naming this row (`:707-710`); `CAST(int AS BIGINT)` is `WidenLane`; a narrow value to
   `INT` is `WidenLane(x, INT)`, no instruction (`:534-536`). The interval arms are in
   `VarkaIntervalCompiler.java:115-176`: both MONTH relabels, `CAST(i AS INTERVAL YEAR)` through
   a checked multiply that `arithOver` declines, the year-month unit relabels, `CAST(ymy AS INT)`
   as `ConstDivide(x, 12)`, and `CAST(ymm AS INT)` only for `MONTH_INTERVAL` exactly
   (`:172-174`). `VarkaTimeCompiler.java:174-195` holds the `TIME` and day-time relabels.
8. **The IR pieces** (`varka/VarkaVectorIR.java`): `Overflow { WRAP, FAIL, NULL }` (`:415`);
   `GuardedRange(child, lo, hi)` (`:536-545`), which only declines a batch; `WidenLane` and
   `NarrowLane` with a target lane (`:563-633`), which already admit a `BYTE` child under a `LONG`
   target; `ConstDivide` (`:764-860`), exact at the long lane only under a stated bound of at most
   `2^52`; `CondValue(cond, lane)` (`:1224-1237`), which refuses a narrow lane "until a row that
   needs it (a cast to TINYINT or SMALLINT) tests its lowering". `floorDivLong`
   (`VarkaNodeCompiler.java:787-810`) is exact over every long for an even divisor whose shifted
   magnitude stays under `2^52`: of the four day-time units only the DAY's (`2^13 * 10546875`)
   passes; HOUR (`2^10`), MINUTE (`2^8`) and SECOND (`2^6`) are refused (checked in Python here).
9. **A narrow value under a long kernel has no lowering.** `VarkaKernelPlan.java:297-300` refuses
   it ("a narrow value under a bigint kernel has no lowering"), and `Analysis.java:1236-1241` holds
   the same rule per node; so `byte`/`short` to `BIGINT` and back decline. `VARKA-343.md` 2.2 says
   the first of rows 332 and 343 to land builds that load, for `CAST(y AS DOUBLE)` too.
10. **The census has no direct narrow-long conversion cell.** `operator_census.json` holds `B2I`,
    `S2I`, `I2B`, `I2S`, `B2S`, `I2L`, `L2I` and the part-form byte loads, all intrinsic where
    they run on the laptop; no `B2L`, `S2L`, `L2B` or `L2S`.
11. **The null disposal of a range guard is planned twice and built nowhere.** Row 481's plan
    (local branch, section 3.1) gives `GuardedRange` a `FAIL`/`NULL` mode (the owner's choice B
    recorded there is about the `checkIntOverflow` option's scope, not about this mode);
    `VARKA-343.md` 4 builds the same disposal in 343b and says "332's plan, when written, takes
    this disposal rather than building its own". 343 is wave F2, after this row; 481 has no
    start. Row 338 took the other road for TRY: a `FAIL` guard whose declined batch the row
    engine nulls, "a correct answer computed slower" (`VARKA-338.md` 0, item 4).
12. **The cast register and the probe.** `VarkaCastMatrixSuite` compiles each form as a
    projection, and a `BOOLEAN` target as a predicate since a boolean output is row 27's
    (`VarkaCastMatrixSuite.java:155-170`). `dev/varka_census.py:766-784` gives `332` to every pair
    no other rule claims, which is why `TIME` and `DATE` pairs carry it. The conformance contract's
    cast check reads each form's answer from `casts_fused.tsv` and holds an ANSI cast only to
    declining (`VarkaTypeConformance.java:252-272`, `:344-351`), the gap the done-when's clause of
    27 October closes.
13. **No cast proof file exists**: `sql/varka/proofs/` holds `narrow_range.smt2`,
    `narrow_wrap.smt2` and `long_divide.smt2`, and the `casts.smt2` row 490 names is not built.
14. **The sweep budget is not in `PLAN.md` yet.** Row 330's first 2^32 sweep took 1106 s
    (`results/VARKA-330-sweep.txt`), but row 330 is "Started" with the budget among what is left,
    and `PLAN.md` 10.3 item 5 does not hold the number; the F1 wave row makes 332's sweeps wait on
    it.

**Coverage, from `m8/casts.tsv` and `m8/casts_fused.tsv`.** The row's text names four groups, 36
pairs and 124 forms (identities apart, which the optimizer removes and the register leaves out):

| group | pairs | forms | fused in every mode today |
|---|---:|---:|---|
| integral to integral | 12 | 12 | 3 (to `int` and `bigint`); `bigint` to `int` in LEGACY, ANSI |
| `BOOLEAN` with integral | 8 | 8 | 0 |
| integral to interval | 8 | 52 | 2 (`int` to the MONTH ends); `int` to DAY in context only |
| interval to integral | 8 | 52 | 2 (`INTERVAL YEAR` and `MONTH` to `int`) |

The register gives 332 four more groups by its fallthrough (item 12), none in the row's text:
`TIME` to integral (4 pairs, 12 forms, none fused), `TIME` narrowed (6 forms, 3 fused),
day-time to day-time (90 forms, 36 fused), `DATE` to a number or `BOOLEAN` (5 forms, LEGACY only,
none fused). That is the census tool's bookkeeping default, not an assignment: they are not this
row's (section 7).

### What differs from the row text and the milestone plan

- **`TIME` to `SMALLINT` and `TINYINT` nulls under LEGACY** (item 6), where the row's mode split
  says wrap or raise; the pairs are out (section 7), and whichever row takes them needs the null
  disposal under LEGACY too.
- **Interval casts raise under LEGACY** (item 5), as the row says, and so do the interval-to-narrow
  targets the register calls "narrower targets check".
- **Multi-field intervals cast to and from integral types** (item 5): `VarkaIntervalCompiler`'s
  comment "Only a single-field interval casts to an integral type" (`:158-161`) is wrong, and
  `CAST(INTERVAL YEAR TO MONTH AS INT)`, the MONTH-end identity, declines because the arm matches
  `MONTH_INTERVAL` alone. Both are corrected here.
- **The day-time units below a day have no exact full-range division** (item 8); 2.4 names the
  guard this plan takes first.
- **The narrow load at the long width is this row's** (item 9), as 343's plan foresaw.

## 1. Goal and non-goal

Every form of the four groups of section 0 fuses in LEGACY, ANSI and TRY with the row engine's
answers: LEGACY's wrap and the interval casts' raise, ANSI's `CAST_OVERFLOW` by a declined batch,
TRY's null on the lane, a `BOOLEAN` target as a condition. Not this row: a `BOOLEAN` value output
(27), the floating (343) and decimal (350, 364) casts, string casts, the four groups the register
gives 332 by default (section 7).

## 2. Design

### 2.1 One table of targets, one shape per cast

Every cast of the row is one of five compositions over existing nodes; the compiler builds them
from a table keyed by (source, target), so no pair has a hand-written arm:

| kind | tree | pairs |
|---|---|---|
| widen | `WidenLane(x, target)` | integral to a wider integral |
| narrow | `NarrowLane(g(x, target range), target)` | integral to a narrower integral |
| test | `Not(Compare(EQ, x, 0))` as a condition | integral to `BOOLEAN` |
| select | `CondValue(c, target lane)` | `BOOLEAN` to integral |
| scale | `IntArith(MUL, WRAP, g(x, bound), unit)` or a division, then a lane change | intervals |

`g(x, lo, hi)` is the identity under LEGACY for an integral narrowing (the wrap), and a range guard
otherwise: declining in ANSI and, for the interval casts, in LEGACY; nulling in TRY (2.3). The
ranges are the target types' `MIN_VALUE` and `MAX_VALUE` and section 0 item 5's bounds, held in
one place, `VarkaTypes.integralRange(lane)`, a function of the target's lane beside the lane
registry rather than a field of each registry entry, so rows 343 and 350 read the same numbers.

The new arms live in `VarkaIntegralCastCompiler`, a family of their own, `INTEGRAL_CAST`
(`varka/VarkaFamily.java`), with its switch, as 343 plans `FLOATING_CAST`. Row 28's four existing
arms stay in `arithmeticArm` under the int arithmetic switch, so no existing switch changes what it
turns off; `narrowing`'s TRY decline goes. Moving them is a later cleanup (section 7).

### 2.2 The kernels, per cast

| cast | Vector API operations, per lane group |
|---|---|
| `byte`/`short` to `int` | the widening load (`B2I`/`S2I`), today's |
| `byte` to `short` | WIDENED: nothing (both on the int lane); NATIVE: `B2S` |
| `byte`/`short` to `bigint` | the narrow load at the long width (2.5), then `I2L`, part 0 |
| `int` to `byte`/`short`, LEGACY | WIDENED: the shift pair, or the store alone; NATIVE: `I2B` |
| `bigint` to `int`/`short`/`byte`, LEGACY | `L2I`, part 0, then as the row above |
| `short` to `byte`, LEGACY | WIDENED: the shift pair at the int lane; NATIVE: `S2B` |
| ANSI and TRY narrowing | `compare(LT, lo)`, `compare(GT, hi)`, `or`; collected or cleared |
| integral to `BOOLEAN` | `compare(EQ, 0)`, known-true and known-false swapped |
| `BOOLEAN` to integral | `zero.add(1, kT)`, validity `kT \| kF` (`VARKA-460.md`) |
| `int` to YEAR, DAY | item 0.5's guard, `mul` by 12 (int lane) or `I2L` and `mul` by the unit |
| `int` to HOUR, MINUTE, SECOND | `I2L`, `mul` by the unit: exact for every int, no guard |
| `bigint` to an interval | item 0.5's guard (year-month: the int range, then YEAR's), `mul` |
| narrow source to an interval | the widening, `mul`: no guard |
| year-month to integral | `ConstDivide(x, 12)` (YEAR end); a narrow target's guard; `I2L` |
| day-time to integral | 2.4's truncating division, then the guard for `int`, `short`, `byte` |

The laptop's census (`operator_census.json`) reads the cells the table uses intrinsic at 512 and
256 bits: the conversions of item 0.10, `long.MUL`, `long.LT` and `long.GT`, the short and byte
operators. At 128 bits the long compares are the Java fallback (two long lanes, `m7` row 374), so
a guard over a `bigint` or day-time value there is the fallback, recorded in `width_audit.json`.
The census has no `S2B`, `B2L`, `S2L`, `L2B` or `L2S` cell, so the narrow-long forms compose
through the int lane (`B2I` and `I2L`, `S2I` and `I2L`, and `L2I` before a narrow store or
wrap), and the NATIVE `short` to `byte` needs an `S2B` cell before it is emitted. A direct `B2L`
is section 7's.

### 2.3 What declines a batch and what raises

A kernel never raises. A live lane that Spark raises on declines its batch through the guard's
collect (`VALUE_RANGE`), and the row engine raises `CAST_OVERFLOW` with Spark's parameters on the
first such row (item 0.3). A null lane never declines. Under TRY the lane outside the range becomes
null and the batch runs on, by `GuardedRange`'s `NULL` mode, the design of row 481's plan
(section 3.1 there: a mode on the node, in `canonical` and `withChildren`, owning a validity word,
`nullsFromValidInputs`, the masked body, the reference's `NULL` arm). Row 481 owns and builds the
mode; this row does not, and its second pull request waits on 481 (section 5). 338's road, a
declining guard whose batch the row engine nulls, is not taken: it is a correct answer but slower
on a batch holding one out-of-range value, and row 368 would record a declined batch where the
register says fused.

The `BOOLEAN`, widening and narrow-source interval casts cannot fail, so their three modes are one
kernel; the shape key separates the others by the guard's mode.

### 2.4 The day-time division

`dayTimeIntervalToLong` truncates over every long. For a DAY end the exact full-range form is
`floorDivLong` over a biased dividend: `trunc(x / d) = floorDiv(x + ((x >> 63) & (d - 1)), d)`,
whose bias cannot overflow (a negative `x` gains at most `d - 1`), three lane operations more
than 338's floor division and proven in `long_divide.smt2` beside it. For HOUR, MINUTE and SECOND
`floorDivLong` refuses the divisor (item 0.8), so the first form is `ConstDivide` under a
`GuardedRange` of `|x| < 2^52` microseconds (about 142 years), whose declined batch the row engine
computes, as 338 guarded before its exact form. A full-range division for those units is section
7's.

### 2.5 The narrow load at the long width

A long-driven kernel reading a `TINYINT` or `SMALLINT` column loads it widened to the int lane in
the int species of the long species' width, the low half, as an int column is (`VARKA-28.md` 9),
then `I2L`. The byte loads at four long lanes and below take 329's part form (`VARKA-329.md` 9.6):
an eight-lane 64-bit byte species converted in parts, the loop bound on whole iterations.
`VarkaKernelPlan`'s and `Analysis`' refusals (item 0.9) narrow to the forms still without a
lowering; the NATIVE arm computes such a kernel as WIDENED does, since a long value is present
(`VARKA-329.md` 2.3, third case). The narrowing store of a long value to a byte or short column
is `L2I` then the narrow store of the int lane. Both are written once for 343's
`CAST(y AS DOUBLE)`.

### 2.6 Compiler, reference, cost, warm-up

- `CondValue` admits `BYTE` and `SHORT` (its javadoc's condition): under WIDENED it emits at the
  int lane, under NATIVE at the narrow lane.
- `VarkaReference` needs the narrow `CondValue`; the guard's `NULL` arm is row 481's.
- The range analysis bounds a `CondValue` to `[0, 1]`, so `CAST(b AS TINYINT)` under a narrow
  consumer emits no shift pair.
- Cost: no new node; the guard's mode is a cost key as row 481 states. The warm-up blocks gain the
  narrow load at the long width, held by `VarkaWarmupReachSuite`.
- **What 343 and 350 build on**: the target table of 2.1, the `NarrowLane(g(x))` shape for a narrow
  target (343's `NarrowLane(ConvertLane(x, INT))` under the same guard), the guard's `NULL`
  mode (row 481's), the narrow load at the long width, and `CondValue` at every integral lane (343
  adds the floating lanes, 350 a scaled select for `BOOLEAN` to decimal).
- **What 368 needs**: Spark's cast suites build `Cast(Literal(v), to, mode)` and check it with no
  constant folding under 368's harness, so each arm accepts a `LiteralSlot` child exactly as a
  column; a TRY out-of-range literal must be nulled in the kernel, or 368 records a declined batch
  where the matrix says fused; and an ANSI out-of-range value declines with `VALUE_RANGE`, which 368
  reads apart from a compile decline. A projection or predicate that reads no input column
  never reaches a kernel (section 7), so 368's harness must give the literal a column.

## 3. Tests and proofs

1. **Exhaustion, narrow and boolean sources**: every `TINYINT` (2^8) and `SMALLINT` (2^16) value,
   one batch, through every pair and form from them in the three modes and both narrow arms, held
   to Spark's own `Cast.eval` on every row and to the reference; the `BOOLEAN` source's four leaf
   states (null with either data bit) at every offset and both widths, as 331's oracle.
2. **Exhaustion, int and year-month sources** (2^32 each), one sweep per distinct tree, the
   modes sharing a tree sharing a sweep: `int` to `byte` and `short` (wrap, decline, null) on both
   arms, 12; `int` to `BOOLEAN`, 1; `int` to YEAR and to DAY (decline, null), 4; `int` to HOUR,
   MINUTE, SECOND, 3; year-month YEAR and MONTH ends to `byte` and `short` (decline, null) on both
   arms, 16; to `bigint`, 2. **38 sweeps**, 42028 s at row 330's floor of 1106 s each (11 h 40
   min); one arm per tree is 24 sweeps, 26544 s. They wait on 330's budget in `PLAN.md` (item
   0.14), and run opt-in (`-Dvarka.sweep=true`) in the gate's sweep step (`dev/varka_gate.sh`),
   by hand in idle windows with `-Dspark.test.timeout`, the first timed before the rest; PR CI runs
   a 2^24 slice of each. No CI workflow sets `varka.sweep` today, and the nightly script's
   opt-in filter runs nothing in a JUnit suite, so no scheduled run is claimed; scheduling the
   sweeps is a separate finding, not this row's.
3. **SMT for the 64-bit sources** (`bigint`, day-time), in a new `sql/varka/proofs/casts.smt2`
   (row 490's file; whichever lands first creates it) under Z3 and cvc5, over integers as the
   proofs' README prescribes: the wrap of `L2I` and the shift pair equals reduction mod `2^w`; the
   guard's two compares hold exactly where `v != (target) v`; each interval bound is the largest
   `|v|` whose product fits (the `multiplyExact` condition); the biased floor division of 2.4
   equals truncation over every long; the `2^52` guard's `ConstDivide` equals Java's `/`
   (`long_divide.smt2`'s claim, cited). The 16-bit narrowing rules join `narrow_range.smt2`.
4. **Named cases**, each in three modes and with a null beside it: `127`, `128`, `-128`, `-129`
   to `TINYINT` from each wider source; `32767`, `32768`, `-32769` to `SMALLINT`; `2^31` and
   `-2^31 - 1` as `bigint` to `int`; `Long.MIN_VALUE` and `Long.MAX_VALUE` to every narrower target;
   178956970 and 178956971 to `INTERVAL YEAR`; 106751991 and 106751992 to `INTERVAL DAY` from
   `int` and `bigint`; each `bigint` bound of item 0.5 and the next value; `INTERVAL '-1' DAY` less
   one microsecond to `bigint` (truncation toward zero gives 0); `2^52` microseconds either side.
5. **Coverage rows**, a "casts, integral and boolean" family over the fixture's `y1`, `s1`, `i`,
   `l`, `b`, `ym` and `dt` columns: each form of section 0's four groups once, the `BOOLEAN` targets
   as filters, each naming its verification (row 490's rule): the exhaustive suite, a `casts.smt2`
   section, or `differential`. `VarkaCastMatrixSuite` regenerates `casts_fused.tsv`, and
   `dev/varka_census.py` the `fused_today` column.
6. **The conformance contract.** Each integral and interval type's cast row answers; the ANSI
   narrowing forms gain an in-range value set (`Form.over`), so an ANSI cast's answers are compared
   with Spark's and not only its declines (the done-when's clause of 27 October 2026).
7. **The fuzzers.** The IR grammar draws the five compositions of 2.1 with each guard mode, in a
   sequence of its own seed; the Spark-level fuzzer gains the in-scope pairs of `casts.tsv` as
   cast productions, each mode, leaving the rest of the register to row 352.
8. **Spark's suites (368).** A dry run of `CastWithAnsiOffSuite`, `CastWithAnsiOnSuite` and
   `TryCastSuite` through 368's harness, if it exists by then, for this row's pairs only; 368 owns
   the record and the nightly diff.
9. **The bytes oracle**: `emitted_bytes.json` adds rows and moves no existing shape, except
   `CAST(bigint AS INT)` under TRY, which becomes a kernel.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code:**
`VarkaIntegralCastBaselineBenchmark` in sql/core, on `VarkaFloatingBaselineBenchmark`'s pattern
(two arms, a fuse check, `benchmark.yml` on the EPYC 7763 at 256 and 128 bits), as built:
`CAST(... AS TINYINT)` from an `INT` in the three modes, LEGACY over `i`, ANSI over `j` and
`try_cast` over `k`, with LEGACY's `CAST(k AS TINYINT)` as the `try_cast`'s twin over the same
values; `CAST(l AS SMALLINT)` under LEGACY and `CAST(y AS BIGINT)`; `CAST(b AS INT)`; the bare
`WHERE CAST(i AS BOOLEAN)` under `spark.sql.constraintPropagation.enabled=false`;
`CAST(m AS INTERVAL YEAR)` and `CAST(dt AS BIGINT)`; each without and with one row in ten null
(`i_n` to `dt_n`). Section 5 item 1 says why each reads its column. The fuse check expects no
Varka node in those sections; a control section, `CAST(i AS BIGINT)`, already fuses and is
labelled so, its check inverted.

Predictions, registered before the build:

1. Against the baseline at 256 bits, the narrowings and the selects 3 to 10 times faster than the
   row path; the TRY forms within 1.3 times of their LEGACY twins.
2. `CAST(dt AS BIGINT)` (a guarded double division) within 1.5 times of `CAST(i AS INTERVAL
   YEAR)`.
3. At 128 bits the int-lane forms keep at least 2 times; the long-driven forms with a byte load
   tie the row path or better.
4. No existing shape's bytes move but `try_cast` of a bigint to int.

## 5. Order, files and size

1. **The baseline** (4), its own pull request.
   *31 October 2026, as built:* `VarkaIntegralCastBaselineBenchmark` and its queries suite,
   under the shared fuse check (`VarkaSqlBenchmarkBase.requireBaseline`, lifted by
   `-Dvarka.baseline.fuseCheck=false`). Section 4 reads a column per cast, not `i` alone, since a
   value out of range raises under ANSI and, for the interval cast, in every mode, and an exception
   ends the run: LEGACY's `CAST(i AS TINYINT)` reads `i`, the whole int range, so nearly every
   row wraps; ANSI's reads `j`, inside the byte range; `try_cast` reads `k`, past the byte range
   at one row in a hundred, half on each side (Spark's TRY path raises and catches an exception
   per row out of range, so a larger share would time mostly exceptions). A LEGACY `CAST(k AS
   TINYINT)` reads `k` too, at both densities, and section 4's prediction 1 is scored against that
   pair, the two modes over the same values, not against LEGACY over `i`, nearly every row of
   which is out of range. `CAST(m AS INTERVAL YEAR)` reads `m`, inside `[-178956970, 178956970]`
   and at both ends of it. `CAST(l AS SMALLINT)` runs in
   LEGACY, which wraps, over a `l` spread over every bit length. `y` is a `TINYINT` (3.5's `y1`),
   so `CAST(y AS BIGINT)` is the narrow load at the long width of 2.5, not a year-month cast.
   `dt` is an `INTERVAL DAY TO SECOND` of both signs with microseconds, so `CAST(dt AS BIGINT)`
   is the truncating division by `10^6` and a negative value with a fraction truncates toward
   zero; every value is under `2^31` seconds, inside 2.4's `2^52` microsecond guard, so 332b's
   measurement times that kernel rather than declined batches. The filter times the bare
   `WHERE CAST(i AS BOOLEAN)` at both densities, under `spark.sql.constraintPropagation.enabled`
   off on both sessions: with it on, the optimizer infers `i_n IS NOT NULL` from the cast over
   `i_n`, and that conjunct fuses into a `VarkaFilterColumnarToRow` under Spark's `Filter` while
   the cast declines. The setting changes only constraint inference, so both arms still time
   Spark's own plan of the bare cast, and both densities time one condition. `i` is zero at one
   row in eight, so the filter drops some rows. A probe planned every section on an Arrow-cached
   Varka session: each declines but the control `CAST(i AS BIGINT)`, which fuses and is the one
   section listed as fusing. The results files at both widths come from the EPYC runners and are
   committed before it merges.
2. **332a, the integral and boolean casts**: the target table, the narrow load and store at the long
   width, the narrowings in LEGACY and ANSI, the tests, the selects (`CondValue` at narrow lanes),
   the sweeps of narrow and boolean sources, the SMT sections for `bigint`, coverage rows, the
   `INTEGRAL_CAST` family and its switch, and the four default groups marked unclaimed in the
   register (`dev/varka_census.py`'s `cast_rows`, then `casts.tsv`).
3. **Row 481**, which builds `GuardedRange`'s `NULL` mode (its own plan, branch `varka-481`).
4. **332b, the intervals and TRY**, after 481: every TRY form over 481's `NULL` mode, the
   interval casts and the day-time division of 2.4, the multi-field fix, the int and year-month
   sweeps after 330's budget, the conformance in-range sets, the measurement and its verdict.

| file | what |
|---|---|
| `VarkaIntegralCastCompiler.java` (new), `VarkaNodeCompiler.java` | the table, the arms |
| `VarkaIntervalCompiler.java` | the interval casts, the multi-field arms and comment |
| `varka/VarkaVectorIR.java` | `CondValue` at narrow lanes |
| `varka/VarkaKernelPlan.java`, `Analysis.java`, `VarkaVectorWalk.java` | the long-width load |
| `varka/VarkaTypes.java` | the target ranges |
| `varka/VarkaFamily.java` | `INTEGRAL_CAST` |
| `dev/varka_census.py` | the default groups unclaimed |
| `varka/VarkaRangeAnalysis.java`, the warm-up | `CondValue`'s bound; the reach |
| `sql/varka/proofs/casts.smt2`, `narrow_range.smt2`, `long_divide.smt2` | 3.3 |
| `VarkaProofFiles.java` | 3.3's rendering |
| tests | sweeps, named cases, conformance sets, fuzz productions |
| benchmark | `VarkaIntegralCastBaselineBenchmark`, its queries suite and results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json`, `width_audit.json` |
| generated | `casts_fused.tsv`, `casts.tsv`, `census.tsv` |

**Size: medium, in three pull requests.** The baseline half a day; 332a about four days (the long
width load is most of it); 332b about four, after row 481. The sweeps
add machine time (3.2), not gate time; the gate gains the 2^24 slices and the narrow sweeps, of
the order of the narrow suites' seconds.

## 6. What it unlocks in the census

When the row lands, every form of the four groups fuses in the three modes. Of the 36 pairs, 29
fuse in no form today, 4 in part (`bigint` to `int`, `int` to year-month, year-month to `int`,
`int` to day-time in context) and 3 whole. The census's `cast` and `try_cast` entries have an empty
`fused_today` and list every type (`census.tsv`). *Inference, not checked in `dev/varka_census.py`:*
those entries credit a type only once every cast from it fuses, in which case the integral types
and `bool` also wait on 343's and 350's pairs. The count is of the row's 36 pairs only.

## 7. Out of this row

**The four groups the register gives 332 by default** (item 0.12), marked unclaimed in 332a, each
with a suggested home:

- `TIME` to integral (12 forms): no milestone 8 row owns `TIME` casts; a new row, or the scope
  catalogue (`m10/SCOPE.md`). It is `floorDiv(nanos, 10^9)` under `ConstDivide`'s bound, which a
  `TIME` value meets, and its narrow targets null under LEGACY (item 0.6).
- `TIME` narrowed to a lower precision (3 forms declined): the same home; a floor division of
  nanoseconds of day.
- Day-time to day-time at a coarser end (54 forms declined): row 103, whose day-time work holds
  the truncating quotient.
- `DATE` to a number or `BOOLEAN` (5 forms, LEGACY only): row 333's typed null leaf.

**A full-range day-time division below a day.** 2.4 guards the HOUR, MINUTE and SECOND ends at
`2^52` microseconds and declines beyond. An exact division by those constants over every long
arrives with row 30's 64-bit division family (its long-lane magic for a literal divisor); row
103's truncating day-time quotient is its other consumer.

**Moving row 28's casts into `INTEGRAL_CAST`**, a later cleanup guarded by `emitted_bytes.json`
being byte-identical. What keys on the arithmetic family's boundary today (grepped on this
commit): `coverage.json` lists the int arithmetic switch on 82 rows, 15 of them holding a `Cast`
(`CAST(i AS BIGINT)`, `i < l`, `CAST(ntz AS DATE)` and their kin), and `docs/sql-varka.md` renders
the same column; `VarkaFamilySwitchSuite` asserts that a family switched off declines exactly the
coverage rows that hold its nodes (`VarkaFamilySwitchSuite.java:266`) and names the family in a
failure test (`:370`); `VarkaFamilySwitchQuerySuite.scala:59` switches it off over `i + 1`. No
option-matrix configuration, `skips.tsv` line or `dev/varka_matrix.sh` setting names a family
switch. So the move regenerates the coverage table's switch column for those rows and changes what
`spark.sql.codegen.varka.family.intArithmetic.enabled` turns off; no matrix configuration moves.

**A direct `B2L`** (and `S2L`, `L2B`, `L2S`): a measured option only if a benchmark shows the
composition through the int lane costs; the census has no cell for it (item 0.10).

**Casts over a literal alone never reach a kernel.** `VarkaExpressionCompiler`'s projection pass
returns no kernel when its outputs read no input column (`VarkaExpressionCompiler.java:608`,
`outputs.isEmpty() || inputs.size() == 0`), and the predicate pass the same
(`:1010`). Spark's cast suites build `Cast(Literal(v), to, mode)` (`CastSuiteBase.scala:55-60`;
`TryCastSuite.scala:33-38`), so under constant folding's exclusion each test is a literal-only
projection and would record "declined" for every pair. Row 368's harness therefore binds the
literal's value to a one-row input column of its type (a `BoundReference` over a one-row batch,
`TryCastSuite` already passing `InternalRow(l.value)` for its error cases), so the cast reaches the
same arm a column does; 332's arms take a column and a `LiteralSlot` child alike.

Also out: a `BOOLEAN` value output (27), the floating, decimal and string casts, `try_eval`.

Declines that stay, each naming its row: an ANSI or interval batch holding a value Spark raises
on (the row engine raises it), a day-time value past `2^52` microseconds under a sub-day unit
(2.4, row 30), a narrow value in a long kernel at a lane count with no byte species (329's
refusal), and a narrow value narrowed again away from its store in a long kernel
(`CAST(CAST(s AS TINYINT) AS BIGINT)`, `CAST(s AS TINYINT) < l`: "a narrow value under a bigint
kernel has no lowering"; `VarkaKernelPlan.LongNarrowRole.NARROWING_AT_ROOT`). The last is no
design hole: the narrow column is loaded at the int lane there, so the walk's int-lane wrap
likely lowers it as it lowers an int narrowing, and admitting it is a candidate for 343's plan,
which meets the same long-width narrow values.

## 8. Questions for the owner

Context, not questions:

- **The sweeps wait on row 330's budget.** `PLAN.md` 10.3 item 5 does not yet hold the total row
  330's first sweep measured (item 0.14), and the F1 wave row makes 332's sweeps wait on it.
- **The F1 wave row's "332 ... is the casts' harness 338 builds on" is stale** (`PLAN.md` 4.1):
  the harness is rows 484 and 486, and 338 is done without this row (`VARKA-338.md` 0). It is the
  owner's text, so this plan flags it and does not edit it.

1. **The sweep budget.** Section 3.2's 38 sweeps of 2^32 are about 11 h 40 min at row 330's floor;
   one arm per tree, the cut 330's record leaves to the owner, is 24 sweeps and 7 h 22 min, with
   SMT proving the formulas for both arms. (a) Keep both arms. (b) Cut to one arm, the default.
   *Recommended: (b)*: the arms differ only in the narrowing's instruction, which `narrow_wrap.smt2`
   and `narrow_range.smt2` prove for both, and the narrow-source sweeps stay on both arms.
   *The owner chose (b) (31 October 2026):* one arm per tree for the int and year-month sweeps,
   24 sweeps, since `narrow_wrap.smt2` and `narrow_range.smt2` prove the narrowing for both arms;
   the narrow-source sweeps stay on both arms. The 2^32 sweeps are 332b's.

## 9. Done when

Row 332's text holds, with the corrections above: every form of section 0's four groups fuses in
the three modes, the `BOOLEAN` targets as conditions; the narrow, boolean, int and year-month
sources are exhausted against Spark's `Cast` under question 1's answer and the 64-bit sources
proven in `casts.smt2` under both solvers; the named cases pass; each integral and interval type's
conformance suite compares an ANSI narrowing's in-range answers with Spark's; `casts_fused.tsv`,
`casts.tsv` and the census agree with the compiler; no existing shape's bytes move but the one
named; the measurement against the baseline is committed with its predictions scored; and the PR
build and the option matrix pass.

## 10. Outcome

### 10.1 332a, the integral and boolean casts (31 October 2026, branch `varka-332-integral-casts`)

*A correction to section 0 first:* item 11 and section 2.3 say row 481 "has no start" and that its
`NULL` mode is built nowhere. Row 481's first step merged in #1176 (`GuardedRange`'s `NULL` mode,
its reference arm and its `narrow_range.smt2` claims), so 332b builds on a node that exists. 332a
still declines every `TRY` narrowing, as section 5 assigns them to 332b, so no shape's bytes move.

What was built, per item of section 5 step 2:

- **The target table** (2.1): `VarkaIntegralCastCompiler`, one `Kind` per pair (`WIDEN`, `NARROW`,
  `TEST`, `SELECT`) chosen from the two types, no arm per pair; the ranges in
  `VarkaTypes.integralRange`, beside the lane, a function of the lane rather than a component of
  each registry entry (open point 1). Row 28's three arms stay in `arithmeticArm`, and the family
  claims none of them.
- **The narrow load and store at the long width** (2.5): a long kernel emits a narrow value at
  the int lane, in the int species of the long width, low half, as it emits an int one.
  `VarkaKernelPlan.longNarrowRefusal` admits the narrow nodes that need nothing narrow-specific
  there - a narrow column, an int or long value narrowed to a narrow type, and a comparison of
  them, read by a widening, a null test, a comparison or a narrowing, and a narrow-typed root
  narrowing a narrow value - and declines every other narrow node: a narrow literal (open point
  3), narrow arithmetic, a narrow choice beside a bigint. The int narrowing away from a store
  (`CAST(i AS TINYINT) < l`, `CAST(CAST(i AS SMALLINT) AS BIGINT)`) is the shift pair's wrap at
  the int lane, the lowering the walk already had; review finding 4 of 1 November admitted it.
  The load reads twice the long lanes of the narrow type into the int species, then `I2L`; the
  kernel is a mixed one, so its loop bound already covers the whole int vector the load reads. A
  narrowing at a root converts with `L2I` and stores through the narrow species of the int
  width; where that species is exactly the int group of the long width, a narrow store makes the
  kernel a mixed one too, so the store is whole in the loop body and masked only in the epilogue
  (review finding 5: AVX2 has no masked byte or short store, and the masked form scalarised the
  loop). Where it is not - a byte at two long lanes, whose species would be 32 bits, or a width
  with no baked lane count - the store is masked throughout and the kernel stays single-lane, so
  it pays no lane conversions and a sibling `CAST(l AS INT)` keeps its half-width species. Away
  from a root it is `L2I` and the shift pair's wrap. The part
  form at the long width is not built: a byte column at two long lanes (128 bits) declines by name
  ("a BYTE column has no load at 2 long lanes"), the decline section 7 lists.
- **The narrowings in LEGACY and ANSI**: `NarrowLane(g(x), target)`, `g` a `FAIL` range guard under
  ANSI; a `BIGINT` source is one `NarrowLane` to the narrow target (not `L2I` then a second
  narrowing node), its `L2I` emitted at the store.
- **The selects**: `CondValue` admits the narrow lanes (a floating lane is refused); a condition of
  boolean leaves alone is moved to the target's lane, so `CAST(b AS TINYINT)` runs in the byte lane
  under the native arm; any other condition is valued at its own lane and converted.
- **The tests**: `VarkaEmitterIntegralCastSuite` - every `TINYINT` and `SMALLINT` value through
  every target in the three modes on both arms (3.1), `BOOLEAN` to each integral type at every
  length to 129 with both data bits under a null, the named cases of 3.4 from `SMALLINT`, `INT` and
  `BIGINT` each beside a null, and the long-width load and store at 2, 4 and 8 long lanes. The
  conformance suites take the new `casts_fused.tsv` answers: a fused LEGACY or TRY cast form is
  compared with Spark, an ANSI one still only held to declining over the shared fixture, until
  332b's in-range value sets (3.6) close the done-when's clause of 27 October.
- **The sweeps of narrow and boolean sources**: the exhaustive tests above; at 2^16 rows they are
  ordinary tests, not opt-in sweeps.
- **The SMT sections for `bigint`**: `sql/varka/proofs/casts.smt2` (18 checks; Z3 and cvc5 each
  under a second): the `L2I`-and-store wrap is reduction mod `2^w` for every long, and the guard
  holds exactly where Spark raises, for `INT`, `SMALLINT` and `TINYINT`. `narrow_range.smt2`'s int
  claim already covers the 16-bit sources, every short being an int, so it is unchanged.
- **Coverage rows**: the families "Integral casts" and "Integral cast predicates", 20 rows, two
  of them casts of a computed value (`CAST(i + 1 AS TINYINT)`, `CAST(i < 5 AS TINYINT)`), which
  the family switch suite needs to see the family's switch name a failure.
- **The family**: `VarkaFamily.INTEGRAL_CAST`, switch `integralCast`; its condition claim
  (`VarkaNodeCompiler.castConditions`) is reached from `compileCond`.
- **The default groups**: `dev/varka_census.py`'s `cast_rows` marks `TIME` to an integral type,
  `TIME` to `TIME` and `DATE` to a number or `BOOLEAN` `unclaimed` (`dt` to `dt` was already 103's).
- **The baseline's fusion classes**: a probe of every section of
  `VarkaIntegralCastBaselineBenchmark` on this branch finds the LEGACY and ANSI narrowings,
  `CAST(y AS BIGINT)`, `CAST(b AS INT)` and the filter's `CAST(i AS BOOLEAN)` `WHOLE` at both
  densities, beside the control; the `try_cast`, `CAST(m AS INTERVAL YEAR)` and
  `CAST(dt AS BIGINT)` stay `NONE`. The benchmark declares those classes, as its fuse check needs.

- **The IR fuzzer** (3.7): `VarkaIrGrammar.drawCastShape`, a sequence of its own seed
  (`CAST_FUZZ_SEED`), draws the compositions - a widening, a narrowing bare or under a `FAIL` or a
  `NULL` range guard, the test and the select - over a column of each integral type and a
  `BOOLEAN` one, at every lane, and `VarkaIrFuzzSuite` runs it against the reference with the
  other corpora's lengths, null patterns and options; a reach test holds every composition, mode
  and lane drawn. Since review finding 2 it also draws a narrowing read by another node (widened
  back, compared, or under a connective), and the run bounds each shape class: a draw is refused
  for its width alone, and each class of 20 draws or more fuses at least 15% of them. Its first
  run found a real fault: a `CondValue` root at a narrow lane over a
  boolean column reads no narrow column, so the plan's narrow refusal, which asked only whether a
  tree reads a narrow value, let it through at a width with no baked lane count, and its store
  then named the preferred narrow species and wrote past the batch. A narrow-typed value root now
  counts as needing the narrow species.

Not built in 332a: the Spark-level fuzzer's cast productions (3.7's second half), left to row 352,
which the plan already names for the rest of the register. The warm-up (2.6): the
long-width load names the species and conversions the widened int-driven load already warms, so
it needed nothing; what `VarkaWarmupReachSuite` found missing was the narrow and long
`CondValue`'s masked scalar add (`ByteVector.add(byte, VectorMask)` and its short and long kin),
now one call each in the lane blocks of `VarkaVectorTemplateWarmup`.

Bytes: every existing coverage and fuzz shape keeps its bytes; `emitted_bytes.json` gains the new
rows' entries, and only the option-arm digests that hash every shape move, as the coverage set grew.

Open points for the lead:

1. The target ranges are `VarkaTypes.integralRange(lane)`, derived from the lane, not a field of
   each registry entry; the lead kept the function (1 November 2026), and 2.1 now says so.
2. `SMALLINT` to `TINYINT` under the native arm drives at the int lane (two narrow lanes in one
   tree), so no `S2B` is emitted, as 2.2 expects until a census cell exists.
3. Two declines section 7 does not list, both in a kernel driven at the long lane, and recorded in
   the ANSI form's declined list (`VarkaAnsiForm.DECLINED`): a narrow choice beside a bigint
   (`coalesce(y2, CAST(l AS TINYINT))`), and a narrow literal the predicate compiler lifts to the
   long lane (`y1 = 5Y` as a `CASE` condition between bigint arms), which declines as it did
   before 332a, since the long kernel's slots name no int-lane broadcast for it. Neither is a
   cast; a narrow choice and literal at the long width would sit with 343's floating casts, which
   meet the same long-width narrow values, or a row of their own. Suggested: 343's plan decides.
4. `java.util.Random`'s first draw over consecutive seeds varies only in its low bits, so a draw
   of `nextInt(4)` first took two of four compositions over a thousand seeds; the cast grammar
   discards one value first. A lesson for `skills/testing-and-debugging.md`, not written here.

*A correction, 2 November 2026:* the narrow-store bullet above says review finding 5 removed the
AVX2 scalarisation. It removed it from the loop body only. The epilogue still loaded a narrow
column, and stored a narrow root, as a masked byte or short vector, and the PR Build's width
audit on the runners' EPYC 7763 (AVX2, 256 bits, job 125064823790) refused six shapes for it:
`loadMasked vlen=8 etype=short` in `CAST(s1 AS BIGINT)` and `s1 < l`, `etype=byte` in
`CAST(y1 AS BIGINT)` and `greatest(y1, l)`, and `storeMasked` of eight shorts and eight bytes in
`CAST(l AS SMALLINT)` and `CAST(l AS TINYINT)`. HotSpot's x86 matcher has no masked load or store
of a subword type without AVX512BW (`Op_LoadVectorMasked` and `Op_StoreVectorMasked` in
`x86.ad`). A masked load whose whole vector lies inside the segment is a plain load and a blend
there, but the kernel's loop stops a long lane group early, so every batch's last group runs in
the epilogue, and its eight-lane load runs past the end of a segment mapped at exactly the
batch's rows. The laptop's AVX-512 lowers both, so only the runners saw it; under
`-XX:UseAVX=2` the laptop's audit refused the same six shapes.

The epilogue of a kernel driven at the long lane with a baked lane count now moves the narrow
rows one at a time: the load builds the int vector with `withLane` at constant indices from
`VarkaVectorSupport.byteAt` or `shortAt`, zero past the remainder, and the store reads each lane
with `lane(k)` and writes it with `putByteAt` or `putShortAt`, both at most the long lane count
of rows and both lowered on every x86 with SSE4.1. No vector meets another at a join: the tests
are on scalars. Without a baked count the masked form stays, as the loop's masked store does
there. The emitted bytes move in the epilogue methods of those six shapes alone, at both widths
the record keeps (four of them at four int lanes, where the byte column declines), and in the
option-arm digests that hash every shape. The warm-up calls `withLane` once a round now.
Checked on the laptop: the catalyst Varka suites at the defaults pass, the width audit's six
included, and so do the five sql/core suites over the casts and the coverage rows; under
`-XX:UseAVX=2 -XX:MaxVectorSize=32` the same catalyst run passes the preferred-width audit, and
fails only the two censuses pinned to the laptop's CPU model, `width_audit.json` and
`operator_census.json`, which measure AVX-512 there and are not compared on the runners.

Open point for the lead, from the same reading: master's narrow kernels driven at the int lane,
and those driven at a narrow lane, load and store under the same masked subword forms in their
epilogue. The audit never sees it, because the probe's 256 rows divide every int and narrow lane
count, so that epilogue never runs there; a batch whose length the lane count does not divide
runs it once, as the Vector API's Java loop on AVX2. Whether the one-row-at-a-time form,
unrolled to that lane count, should replace it there is the lead's call; 332a does not move
master's bytes for it. The same `UseAVX=2` run shows one case of it inside a loop body, at a
width no AVX2 host prefers: at 128 bits an int kernel's `TINYINT` root (`CAST(i AS TINYINT)`)
stores an eight-lane byte species wider than its four-lane group, so under the mask throughout.

The medium review of that fix (2 November 2026) found no wrong answer and left two gaps,
recorded here rather than built in 332a:

- At a long lane count of two (128 bits) or with no baked lane count, the loop body of a long
  kernel still stores a narrow root under a masked byte or short store, so on AVX2 every group
  runs the Vector API's Java loop. No AVX2 host prefers that width, which is why the audit does
  not see it; the row-at-a-time store the epilogue uses, or a store no wider than the group,
  would serve it.
- `CAST(y AS BOOLEAN)` and `CAST(b AS TINYINT)` beside a long column decline in a long kernel:
  the zero literal and the boolean's value are built on the narrow lane, which a long kernel
  does not lower. Built on the int lane, they would use the forms it already lowers.

### 10.2 The measurement on Arm (6 November 2026)

Section 4's predictions need the EPYC 7763 at 256 bits for the first two, which no fused run has
timed; the Arm runner's 128 bits (run 42177946163, `results/VARKA-332-arm-measurement.txt`)
answers the third and says what the first two would meet:

- **Prediction 3 holds on Arm.** The int-lane narrowings keep 3.7 to 4.2 times without nulls and
  5.4 to 6.1 with one row in ten null, against the 2 times predicted, and the long-driven
  `CAST(y AS BIGINT)`, whose byte load is row 575's, runs 4.2 and 5.3 times the row path, past a tie.
- **Predictions 1 and 2 are not scored.** Prediction 1 names 256 bits, and its TRY clause compares
  `try_cast` with its LEGACY twin, but the class lists `try_cast(k AS TINYINT)` as `NONE`, and it
  does not fuse: 107.6 against 108.3 ns a row. Prediction 2 compares two interval casts, and
  `CAST(m AS INTERVAL YEAR)` and `CAST(dt AS BIGINT)` are `NONE` too, at 1.0 times.
- **What the run adds to what is left:** the `try_cast` of an int to a narrower type and the two
  interval casts still run on the row path on that host; the three sections of the class are where
  a row that fuses them sets the new class.

### 10.2 332b, the TRY narrowings (6 November 2026, branch `varka-332d-try-cast`)

The six integral narrowings (`SMALLINT`, `INT` and `BIGINT` to a narrower type) no longer decline
under TRY. `VarkaIntegralCastCompiler.narrow` and `VarkaNodeCompiler.narrowing` wrap the operand in
`GuardedRange` over the target's range in NULL mode, which row 481's first step built, and then
`NarrowLane`; LEGACY stays the bare wrap and ANSI the FAIL-mode guard. A lane out of range is
nulled in the validity word and the batch runs on, where the row engine used to answer it.

- `casts.tsv` and `casts_fused.tsv` list the six pairs as fused in `LEGACY,ANSI,TRY`.
- `VarkaEmitterIntegralCastSuite` no longer asserts a TRY decline: TRY now must answer every batch,
  and its values are compared with the row engine's over the exhaustive and boundary sets.
- Six `try_cast` rows join the coverage family of the integral casts, with `coverage.json` and the
  docs table regenerated.
- The baseline class sets `try_cast(k AS TINYINT)` to `WHOLE`.

Not done here: the Arm and 256-bit runs of the new section and the ANSI in-range value sets (the
two interval casts are 10.3).

### 10.3 The two interval casts that move numbers (6 November 2026, branch `varka-332e-interval-casts`)

`CAST(i AS INTERVAL YEAR)` and `CAST(dt AS BIGINT)`, the two sections of the baseline that stayed
`NONE`, fuse in LEGACY, ANSI and TRY. Section 5's step 4 assigned them to 332b behind row 481's
`NULL` guard; that guard has merged (#1176), so neither needs more than a node that exists.

- **`CAST(i AS INTERVAL YEAR)`** (`VarkaIntervalCompiler.yearsToMonths`). Spark multiplies with
  `Math.multiplyExact` in every mode (item 0.5), so the year count is guarded by
  `[-178956970, 178956970]` (`Int.MaxValue / 12`, symmetric since 12 does not divide `2^31`) and
  the multiply by twelve wraps, exact inside the guard. The guard is `FAIL` under LEGACY and ANSI,
  declining the batch so the row engine raises `CAST_OVERFLOW` on the row, and `NULL` under TRY.
  An operand whose bounds already prove the product fits (`year(d)`, the calendar family's
  `add_months` count) keeps the bare wrapping multiply, so no existing shape's bytes move. The
  same function serves `add_months`' month-count position, where a bare `CAST(i AS INTERVAL
  YEAR)` declined before and now fuses. A `TINYINT` or `SMALLINT` source is a different cast
  (`intToYearMonthInterval` on a widened value, which cannot overflow) and is not claimed here.
- **`CAST(dt AS BIGINT)`** (`VarkaTimeCompiler.dayTimeToLong`, for every field range). The
  quotient is `v / unit` of the end field, Java's truncating division, and it cannot fail, so the
  three modes are one kernel. A DAY end is `VarkaNodeCompiler.truncDivLong`, the plan's 2.4: the
  dividend moved up by `(v >> 63) & (d - 1)` and floor-divided by `floorDivLong`, exact over
  every long (`long_divide.smt2` proves the bias cannot overflow, that the floor division of the
  biased dividend is `x / d` for every long, and that without the bias it is not). HOUR, MINUTE
  and SECOND have no exact full-range form (item 0.8), so their dividend is guarded to
  `|v| < 2^52` microseconds (about 142 years) and divided by `ConstDivide`; a longer interval
  declines the batch and the row engine divides it, in every mode alike. Their divisors,
  `3,600,000,000` and `60,000,000` (and `10^6` and the day's odd part, already there), join
  `long_divide.smt2`, which `VarkaDivisorProofSuite` holds to what the cast matrix compiles.
- **Decided where the plan left it open.** The plan's table (2.1) puts the interval casts in the
  `INTEGRAL_CAST` family; they stay in the interval and time families whose columns they read,
  as the existing MONTH and relabel arms do, so no family switch moves. A declined sub-day
  batch under TRY is not nulled: Spark's answer is the quotient, so the row engine's is the right
  one ("answers follow vanilla Spark exactly").
- **Known limits.** A sub-day interval of 2^52 microseconds (about 142 years) or more sends its
  whole batch to the row engine, so a column with a sentinel such as `Long.MAX_VALUE` loses fusion
  for every batch holding one; the cliff is silent and no benchmark measures it. The year cast
  and `CAST(dt AS BIGINT)` are gated by the interval and time switches, not `integralCast`, so
  turning `integralCast` off leaves them fused.
  A value that overflows only in a null row still declines a LEGACY or ANSI batch, because the
  guard reads the stored slot; and under TRY in `add_months`' month-count position the nulled
  count keeps its wrapped product, which the month-count guard declines, so that shape fuses only
  while no count overflows. Both are declines, which the row engine answers; the suite holds
  that no answer differs from Spark's. `verify_double_division.py` does not yet cover
  the three sub-day divisors (the SMT file does).
- **Tests.** `VarkaEmitterIntervalCastSuite`: the year guard against `multiplyExact` over every
  int, the kernel's answers, declines and nulls around the guard's ends and over a spread of
  counts in the three modes, and the division for all ten day-time field ranges in the three
  modes at the unit's multiples, the `2^52` guard's ends, the long's ends and a spread, each row
  held to Spark's `Cast.eval`. Coverage rows `CAST(i AS INTERVAL YEAR)` and `CAST(dt AS BIGINT)`.
  `casts_fused.tsv` flips 11 forms: `int` to `INTERVAL YEAR` and the ten `dt` to `BIGINT`
  forms. The baseline's two sections are now `WHOLE`.
- **What the checks found.** The bytes oracle moves no existing shape: `emitted_bytes.json` gains
  the two new rows and only the option-arm digests that hash every shape change. The harness'
  ANSI form lists gain the two rows under `FALSE_AND` (`VarkaAnsiForm`), like the other guarded
  casts: the guard condemns a batch from a lane the left side of the AND rejected. The day-time
  conformance suite checks its `BIGINT` cast over values inside the `2^52` guard
  (`VarkaTypeConformance.castValues`), since its type's extremes decline by design. The
  compiler suite's expectation that a bare column's `CAST(i AS INTERVAL YEAR)` declines is now
  the guarded tree in the three modes, and in `add_months`' count position. Under
  `-XX:UseAVX=2` the preferred-width audit passes; the host-pinned `width_audit.json` check
  fails there as 10.1 recorded.
- **Not measured.** The two sections' speed against the row path waits for a runner dispatch
  (the baseline's results files are the row path's), so the predictions of section 4 stay
  unscored for these two casts.
- **Still declining**, each by its own reason: `BIGINT` to an interval, `INT` to the day-time
  ends other than in a date offset, year-month to the narrow targets and to `BIGINT`, and the
  day-time interval to `INT`, `SMALLINT` and `TINYINT`, which need the narrow range checks over
  a quotient; TRY's integral narrowings (row 481's guard, wired by the `try_cast` PR); a
  `YEAR TO MONTH` source to `INT` (the multi-field fix of item 0.5).

## 11. What is left, cut into parts a Sonnet-class agent can build (28 November 2026)

*New on 28 November 2026. Code facts were read on master `dbaa94ff498` and on the local branch
named below, in `/home/max/proj/vecbricks-varka`, with no JVM. This section stands alone: a
builder needs it, the `AGENTS.md` files and `sql/varka/ADDING_AN_EXPRESSION.md`. Where it differs
from sections 0 to 10 it wins, and section 14 lists each difference. Short names: `C` is
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen`, `CT` the same
path under `src/test/java`, `CS` under `src/test/scala`, `S` is
`CT/varka/VarkaEmitterIntegralCastSuite.java`, `PF` is `CT/varka/VarkaProofFiles.java`, and `M8`
is `sql/varka/plans/m8`. Every command runs from the root of the part's worktree.*

### 11.1 The state on 28 November 2026

**What `PLAN.md` 1.1 item 3 asks of this row.** Every legal pair of `M8/casts.tsv` that names
row 332 fuses in the modes it admits, or declines with its reason, and is proven by exhaustion
where the source has at most 32 bits and by SMT where it has 64. `PLAN.md` 12.5 lists row 332 as
a gate under item 3.

**On master**, each with its command:

| what | command | prints |
|---|---|---|
| the baseline (#1247) | `git log --oneline -1 f1bd3d23b99` | `f1bd3d23b99 [VARKA-332] Benchmark the integral casts on Spark's row path before they fuse (#1247)` |
| the pairs of `casts.tsv` naming 332 | `awk -F'\t' 'NR>1 && $10 ~ /332/' M8/casts.tsv \| wc -l` | `43` |
| of them, fused in no form (`int` to `dt` fuses only as `date_add`'s offset) | `awk -F'\t' 'NR>1 && $10 ~ /332/ && $9=="" && $6!="identity"' M8/casts.tsv \| wc -l` | `13` |
| fused in part (`ym` to `int`, 2 of 3 forms) | `awk -F'\t' 'NR>1 && $10 ~ /332/ && $9!="" && $11!="all"' M8/casts.tsv \| wc -l` | `1` |
| the identities, which the census counts fused (`dev/varka_census.py`, `pair_credit`) | `awk -F'\t' 'NR>1 && $10 ~ /332/ && $6=="identity"' M8/casts.tsv \| wc -l` | `6` |
| the bigint narrowings' proof | `grep -c '(check-sat)' sql/varka/proofs/casts.smt2` | `18` |
| no interval cast class yet | `ls C/VarkaIntervalCastCompiler.java` | an error ending `No such file or directory` |
| the weekly sweep list has no cast row | `grep -c 'IntegralCast' sql/varka/ci/sweeps.tsv` | `0` |

The thirteen: `byte`, `short` and `bigint` to `ym`; `byte`, `short`, `int` and `bigint` to `dt`;
`ym` to `byte`, `short` and `bigint`; `dt` to `byte`, `short` and `int`. With `ym` to `int`'s
third form (`YEAR TO MONTH`), they are the fourteen the branch below fuses.

**On local branches, never pushed.** `git branch --list 'varka-332*'` prints one:

| branch | tip, date | holds | verdict |
|---|---|---|---|
| `varka-332-integral-casts` | `8f8f2ec47ea`, 20 November | one commit on master `767dcddce50`: `VarkaIntervalCastCompiler` (every remaining cast between an integral type and an interval, three modes), the conformance twins over the values Spark answers (3.6's clause of 27 October), seventeen coverage rows, the record 10.4 | **part 332c starts from it**, one cherry-pick; 648 counted lines (`python3 dev/varka_msg_check.py size --base 767dcddce50 --head varka-332-integral-casts`) |

What master changed under the branch's files since `767dcddce50` (`git log --oneline
767dcddce50..origin/master -- <file> | wc -l`): `CS/varka/VarkaCoverageSuite.scala` 19 commits,
`sql/core/.../VarkaAnsiForm.java` 6, `CT/varka/VarkaTypeConformance.java` 6,
`C/VarkaIntervalCompiler.java` 3 (rows 612, 103 and 359), `C/VarkaTimeCompiler.java` 2,
`dev/varka_census.py` 8, `sql/core/.../VarkaDomains.java` 2. The class itself and `S` did not move.

### 11.2 The parts

| part | what | counted lines | builder | starts from | waits on |
|---|---|---|---|---|---|
| 332c | the remaining integral-interval casts and the in-range conformance twins | about 650 | Sonnet-class | master, one cherry-pick | nothing |
| 332p | `casts.smt2`'s twenty checks of the 64-bit sources' interval guards | about 110 | Sonnet-class | master after 332c | 332c |
| 332s | the 24 sweeps of 2^32 of the 32-bit sources, opt-in, on the weekly sweep job, with a 2^24 slice of each on every build | about 420 (11.6's code is 406 lines) | Sonnet-class | master after 332c | 332c |
| 332m | the baseline's sections measured fused on the EPYC 7763 at 256 and 128 bits, the predictions scored, the row's record | about 120, results files apart | Sonnet-class; the lead dispatches | master after 332p and 332s | 332p, 332s |

**No part is full strength**, and none is deferred by the owner's decision E1 of 28 November
2026. The one proof part, 332p, has every check written out below as SMT-LIB and as the Java that
renders it, and both solvers were run on the rendered text here: `z3: 20 verdicts, 20 as
expected, ALL HOLD` and `cvc5: 20 verdicts, 20 as expected, ALL HOLD`
(`/home/max/varka-ops/m8-row-plans/scratch-332/casts_interval_smt.out`). The sweeps of 332s
compare against the reference and against Spark's `Cast.eval`, both existing oracles, with the
answered intervals written out and checked in Python (11.6).

**The order and its reason.** 332c first: the other parts prove and sweep its casts. 332p and
332s do not touch the same files (`PF` and `casts.smt2`; `S` and `sweeps.tsv`) and may be built
side by side. 332m last: it measures the code as it lands.

**Landing the plan.** The new text of this file (sections 11 to 14 and the note at the top) goes
to master first, in a plan-only pull request titled `[VARKA-332] Plan: land, prove and sweep the
integral-interval casts`, which the lead opens.

### 11.3 Decisions taken here, each with its evidence

1. **The branch's design stands.** Its decision 1 (the interval casts in their families, not in
   `INTEGRAL_CAST`) and decision 2 (the in-range sets derived, not listed) are kept; its decision
   3 (no range-analysis removal of the bigint-to-year-month guard) is a missed speed-up, not a
   wrong answer.
2. **The 64-bit sources are proven by twenty new checks in `casts.smt2`** (332p), the file the
   bigint narrowings' proof already is: the `INTERVAL YEAR` guard of a bigint, and the guard of
   each day-time unit, each held exactly to Spark's overflow condition, its product exact inside
   it, and both neighbours of each bound wrong. The month-end cast of a bigint is guarded by the
   int range, which the existing check "the int guard holds exactly where the bigint is not its
   narrowing" proves; the day-time interval's narrowings to `INT`, `SMALLINT` and `TINYINT` are
   the target-range guard over the quotient, which the existing narrowing checks prove for any
   long, over `long_divide.smt2`'s quotient. So no new check is needed for those.
3. **The 32-bit sources are exhausted by 24 sweeps**, the owner's cut (b) of question 1 (31
   October 2026): one arm per tree. They run in the weekly sweep job, `varka-sweeps.yml`, as the
   `SMALLINT` pair sweeps of row 330 do (`sql/varka/ci/sweeps.tsv`), never in a pull request's
   Build, which runs a 2^24-row slice of each, as 3.2 planned.
4. **The two gaps of the review of 2 November 2026 leave the row.** Both are declines with their
   reasons, not wrong answers: a long kernel's narrow root stored under a masked store at two
   long lanes or with no baked lane count (the Vector API's Java loop on AVX2), and
   `CAST(y AS BOOLEAN)` or `CAST(b AS TINYINT)` beside a long column, which decline. Item 3 of
   1.1 accepts a decline with its reason. 332m adds one line for each to `m10/SCOPE.md` item 140
   (question 1 of section 12).
5. **The Spark-level fuzzer's cast productions are row 352's** (3.7, the row's note), and row 352's
   plan moves them on to `m10/SCOPE.md` item 140 by default (`VARKA-352.md` 12).
6. **The newly fused sections are not added to the baseline class.** A baseline is measured on
   the unchanged code first (`sql/varka/AGENTS.md`, "Measurements, not adjectives"), and the
   casts of 332c are on master once it merges. 332m measures the sections the class has; the new
   casts' speed is a line of `m10/SCOPE.md` item 140.
7. **Row 332 is marked Done by 332m**, its done-when read so: every pair fuses (332c), the 64-bit
   sources are proven (332p), the 32-bit ones swept (332s), the conformance twins compare ANSI's
   in-range answers (332c), and the measurement is committed with its predictions scored (332m).

### 11.4 Part 332c: the remaining integral-interval casts

**Title:** `[VARKA-332] Fuse the integral-interval casts and compare in-range cast answers`.
**Branch:** `varka-332c-interval-casts`, from master (`varka-332-integral-casts` exists).
**Builder:** Sonnet-class.

**B0.** On master, every line must print what it says, or the builder stops and reports:

    git log --oneline -1 8f8f2ec47ea   -> 8f8f2ec47ea [VARKA-332] Fuse the integral-interval casts and compare in-range cast answers
    ls C/VarkaIntervalCastCompiler.java                -> an error: No such file or directory
    grep -c 'static final long MAX_YEARS = Integer.MAX_VALUE / 12;' C/VarkaIntervalCompiler.java  -> 1
    grep -c 'static Optional<VarkaVectorIR> dayTimeToLong' C/VarkaTimeCompiler.java             -> 1

The last line may print `0` if master named the method otherwise; then `grep -n 'dayTimeToLong'
C/VarkaTimeCompiler.java` must print at least one line, or stop.

**Step 1, the pick.** `git cherry-pick 8f8f2ec47ea`.

**Step 2, conflicts.** *Rewritten in the fix round of 28 November 2026 (review 1, H3), in
master's names as of `dbaa94ff498`.* `git apply --check` of the commit against master (the review
ran it) fails on `CT/varka/VarkaTypeConformance.java`, `sql/core/.../VarkaAnsiForm.java`,
`dev/varka_census.py` and the generated files; every other file applies. For each conflicted
file: take master's side whole (`git checkout --ours <file>`), then apply the commit's hunks for
that file by hand as written below (`git show 8f8f2ec47ea -- <file>` shows them). Per file:

- Generated files (`docs/sql-varka.md`, `sql/varka/coverage.json`, `sql/varka/emitted_bytes.json`,
  `sql/varka/width_audit.json`, `M8/casts.tsv`, `M8/casts_fused.tsv`, `M8/census.tsv`): take
  master's side and apply nothing; step 4 regenerates them.
- `M8/PLAN.md`, `M8/VARKA-332.md`: take master's side and apply nothing; step 5 writes the record.
- `CT/varka/VarkaTypeConformance.java`. Master split the commit's `castRow` test into one test a
  census target, `castTo(String to)`, so the commit's loop hunk has no context. Take master's
  file, then make these five edits:
  1. Imports: add `import java.util.LinkedHashSet;` after `import java.util.HashMap;`;
     `import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes.IntegralRange;`
     after the `VarkaKernelChecks.SparkAnswers` import; `import
     org.apache.spark.sql.types.IntegralType;` after `import
     org.apache.spark.sql.types.DecimalType;`.
  2. In `castTo`, the loop body's last statement
     `forms.add(new Form(sql, toBoolean ? Kind.CONDITION : Kind.VALUE, "cast:" + to, mode,
     false).over(castValues(to)));` becomes:

         Form f = new Form(sql, toBoolean ? Kind.CONDITION : Kind.VALUE, "cast:" + to, mode,
             false).over(castValues(to));
         // An ANSI narrowing raises on the type's values, and so does an interval cast under
         // LEGACY too; TRY never raises.
         Optional<Form> twin = mode == Mode.TRY ? Optional.empty()
             : inRange(f, DataType.fromDDL(target));
         forms.add(twin.isPresent() ? f.raising() : f);
         twin.ifPresent(forms::add);

  3. After `castTo`, before the Javadoc of `overflow()`, add the commit's method `private
     Optional<Form> inRange(Form f, DataType target)` with its Javadoc, word for word from
     `git show 8f8f2ec47ea:CT/varka/VarkaTypeConformance.java` (it calls `type().values()`,
     `type().spark()`, `type().lane()`, `integralValues`, `resolve`, `columns` and `spark`, all
     on master under those names: `grep -c 'private Expression resolve(Form f)\|private
     List<Attribute> columns()\|protected static long\[\] integralValues' CT/varka/VarkaTypeConformance.java`
     prints `3`).
  4. The record `Form`: the header becomes `private record Form(String sql, Kind kind, String
     register, Mode mode, boolean overflowing, boolean raises, boolean inRange, long[] values)`;
     the five-argument constructor calls `this(sql, kind, register, mode, overflowing, false,
     false, null)`; each of `in`, `reachingOverflow`, `over` and `asValue` passes `raises,
     inRange` before its values argument, unchanged otherwise (for example `in` returns `new
     Form(sql, kind, register, m, overflowing, raises, inRange, values)`); after `over` add the
     commit's two methods `inRange(long[] v)` (returns `... overflowing, false, true, v)`) and
     `raising()` (returns `... overflowing, true, inRange, values)`), with their Javadoc.
  5. In `Form`, `meantToRaise()` returns `overflowing || raises`, with the commit's Javadoc, and
     `toString()` appends `(inRange ? ", in range" : "")` before the closing `")"`, as the commit
     has it. No other `new Form(` call changes: `condition` and `value` use the five-argument
     constructor (`grep -c 'new Form(' CT/varka/VarkaTypeConformance.java` prints `7` on master
     and `9` after this edit, as on the branch: the two new methods).
- `sql/core/src/test/java/org/apache/spark/sql/execution/VarkaAnsiForm.java`: take master's file.
  In the list whose loop puts `sql + "\t" + FALSE_AND`, append after its last element
  `"CAST(CAST(d9 AS DECIMAL(9, 4)) AS BIGINT)"` the commit's nine: `"CAST(i AS INTERVAL DAY)",
  "CAST(l AS INTERVAL MONTH)", "CAST(l AS INTERVAL YEAR)", "CAST(l AS INTERVAL SECOND)",
  "CAST(ymy AS SMALLINT)", "CAST(ym AS TINYINT)", "CAST(dt AS INT)", "CAST(dt AS SMALLINT)",
  "try_cast(dt AS TINYINT)"`. In the list whose loop puts `sql + "\t" + COALESCE_TAIL` (the one
  under `String narrowChoice`), append after `"getbit(l, 63)"` the commit's two:
  `"CAST(dt AS SMALLINT)", "try_cast(dt AS TINYINT)"`, and in the comment above `narrowChoice`
  change "A bigint narrowed to a narrow type, or the TINYINT bit of a bigint," to "A bigint, or a
  day-time interval's whole units, narrowed to a narrow type, or the TINYINT bit of a bigint,".
- `dev/varka_census.py`: in `CONTEXT_CASTS`, delete the one line `("int", "dt"): "only as
  date_add's offset: d + CAST(i AS INTERVAL DAY)",` and keep master's `("dec", "dec")` entry
  with its comment as they are.
- `C/VarkaIntervalCompiler.java`, `C/VarkaTimeCompiler.java`: the commit's hunks apply on master
  as they are (`git apply --check` reports no failure for them). What they do, so that a builder
  can check the result: in `VarkaIntervalCompiler`, the constant `MONTH_INTERVAL` and its Javadoc
  are deleted (`grep -c MONTH_INTERVAL C/VarkaIntervalCompiler.java` prints `0` after); the
  comment above the `YEAR`-end `CAST(... AS INT)` arm is reworded; the next arm's guard
  `c.child().dataType().equals(MONTH_INTERVAL)` becomes `c.child().dataType() instanceof
  YearMonthIntervalType ym && ym.endField() == YearMonthIntervalType.MONTH()` (so `YEAR TO MONTH`
  relabels too); and one arm `case Cast c when VarkaIntervalCastCompiler.claims(c) && (...
  YearMonthIntervalType ...) -> () -> VarkaIntervalCastCompiler.compile(c, inputs, literals,
  sink);` is added after it, before the comment "Group A: the year-month interval algebra". In
  `VarkaTimeCompiler`, `unitMicros` and `dayTimeToLong` lose `private` (package-private), and one
  arm of the same shape over `DayTimeIntervalType` is added after the `dayTimeToLong` arm, before
  the comment "A TIME expression, which arrives as the StaticInvoke". Master's row 612 wraps some
  day-time arms in `twoLaneMeasured`; these arms are casts, which that list does not hold, so
  they go in as the commit wrote them.
- `CS/varka/VarkaCoverageSuite.scala`: it applied cleanly in the review's check; if the pick
  conflicts there anyway, keep master's rows and add the commit's seventeen rows to the family
  "Integral casts", each with its domain as the commit has it.
- **Any file not listed here:** take master's side whole, then apply the commit's hunks for it by
  hand. A file whose hunk context no longer exists on master is a stop: commit nothing, and report
  the file with the output of `git show 8f8f2ec47ea -- <file>`.

After step 2, `git diff --name-only --diff-filter=U` prints nothing, and
`grep -c 'Optional<Form> inRange(Form f, DataType target)' CT/varka/VarkaTypeConformance.java`
prints `1`.

**Step 3, compile and the suites.** With `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
SPARK_LOCAL_IP=127.0.0.1`:

    build/sbt catalyst/Test/compile sql/Test/compile                   -> [success]
    build/sbt 'catalyst/testOnly *VarkaEmitterIntegralCastSuite *VarkaEmitterIntervalCastSuite'
        -> Passed: Total N, Failed 0, Errors 0   (N is printed; record it)
    build/sbt 'catalyst/testOnly *ConformanceSuite *VarkaExpressionCompilerSuite *VarkaFamilySwitchSuite *VarkaCastMatrixSuite'
        -> every suite passes
    build/sbt 'sql/testOnly *VarkaCoverageHarnessSuite *VarkaHarnessParitySuite *VarkaCoverageDifferentialSuite'
        -> every suite passes

**The stop rule.** A failure whose message holds two different answers, Varka's against Spark's,
is a wrong answer: stop, commit nothing more, report it. A conformance twin that finds no
in-range value for a form fails by name; record the form and report it, do not drop the twin.

**Step 4, the generated files.**

    dev/varka_regen_generated.sh --all                                 -> exits 0
    python3 dev/varka_census.py --check                                -> exits 0
    python3 dev/varka_bytes_moved.py origin/master                     -> prints nothing, or only keys under option_arms
    awk -F'\t' 'NR>1 && $10 ~ /332/ && $9=="" && $6!="identity"' M8/casts.tsv | wc -l   -> 0

**Commit what the regeneration wrote** (fix round of 28 November 2026, review 1, M3). After the
regeneration, `git status --short` lists only this part's own edits and generated files (`python3
dev/varka_generated_merge.py --list` prints every generated path); `git add` all of them and
commit them in the part's commit, never editing a generated file by hand. Then run the
regeneration line once more: it changes nothing, and `git status --short` prints nothing. A
generated file that changes on the second run, or a path in `git status --short` that is neither
this part's edit nor generated, is a stop: report it.

**Step 5, the record.** Append to section 10, after 10.3, the branch's 10.4:

    git show 8f8f2ec47ea:sql/varka/plans/m8/VARKA-332.md | sed -n '/^### 10.4 /,$p'

and one dated line: "*Landed on 28 November 2026 or later by cherry-pick onto master
(`VARKA-332.md` 11.4).*"

**The gate.** `dev/varka_ops/AGENTS.md`, "Opening or fixing a pull request", steps 1 to 5; the
lead opens the pull request and dispatches the option matrix on it.

**Done when** step 3 and step 4 print what they say, the gate passes and the option matrix is
green. **Not here:** a proof file, a sweep, `PLAN.md`, and the Spark-level fuzzer: until row
352's part 352b lands that fuzzer leaves the narrow columns' rows out (`VarkaSparkFuzz.leftOut`);
352b's run meets these seventeen rows and its stop rule handles them (`VARKA-352.md` 11.4).

### 11.5 Part 332p: the 64-bit sources' interval guards in `casts.smt2`

**Title:** `[VARKA-332] Prove the bigint casts to intervals in casts.smt2`.
**Branch:** `varka-332p-interval-proof`, from master after 332c merged. **Builder:** Sonnet-class.

**B0.**

    ls C/VarkaIntervalCastCompiler.java                  -> the file (332c merged)
    grep -c '(check-sat)' sql/varka/proofs/casts.smt2    -> 18
    grep -c 'static String casts()' PF                   -> 1
    grep -c 'Long.MAX_VALUE / unit' C/VarkaIntervalCastCompiler.java   -> 1 or more

**Step 1, the bound is read from the compiler.** In `C/VarkaIntervalCompiler.java`, the line

    static final long MAX_YEARS = Integer.MAX_VALUE / 12;

becomes `public static final long MAX_YEARS = Integer.MAX_VALUE / 12;` (`PF` is in the package
`codegen.varka` and already imports `VarkaIntervalCompiler`). The day-time bound is
`Long.MAX_VALUE / unit` in `VarkaIntervalCastCompiler.toInterval`, and the renderer computes it
the same way from `DateTimeConstants`, which `PF` imports.

**Step 2, the renderer.** In `PF`, beside `CAST_TARGETS`, add:

    /** The day-time units a bigint casts to, whose guards {@code casts.smt2} states. */
    private static final List<Map.Entry<String, Long>> DAY_TIME_UNITS = List.of(
        Map.entry("day", DateTimeConstants.MICROS_PER_DAY),
        Map.entry("hour", DateTimeConstants.MICROS_PER_HOUR),
        Map.entry("minute", DateTimeConstants.MICROS_PER_MINUTE),
        Map.entry("second", DateTimeConstants.MICROS_PER_SECOND));

    /** {@code x} outside {@code [-b, b]}: an interval cast's guard on its bigint operand. */
    private static String outside(long b) {
      return "(or (< x " + lit(-b) + ") (> x " + lit(b) + "))";
    }

In `casts()`, append to the header text block, after its last line ("outside; the neighbours
move a compare's end by one, which is wrong there."), these lines:

        ;
        ; The interval casts of a bigint (VARKA-332.md 10.4, VarkaIntervalCastCompiler): Spark
        ; raises CAST_OVERFLOW where toIntExact or multiplyExact overflows
        ; (IntervalUtils.longToYearMonthInterval, longToDayTimeInterval), in every mode. The
        ; kernel guards the operand to [-b, b] and multiplies with wrapping: b is MAX_YEARS
        ; (VarkaIntervalCompiler) for a YEAR end, and Long.MAX_VALUE / unit for a day-time end.
        ; The claims: the guard holds exactly where Spark raises, the wrapped product is exact
        ; inside it, and either bound moved by one is wrong. A MONTH end's guard is the int range,
        ; which the int guard above proves.

and after the `for (VarkaVectorIR.LaneType target : CAST_TARGETS)` loop, before
`return out.toString();`, this block:

    String decl = "(declare-const x Int)";
    String in = "(assert (jlong.in x))";
    long years = VarkaIntervalCompiler.MAX_YEARS;
    String yearRaise = "(or (not (jint.in x)) (not (jint.in (* 12 x))))";
    check(out, "casts: a bigint's INTERVAL YEAR guard holds exactly where Spark raises", "unsat",
        List.of(decl, in, "(assert (not (= " + outside(years) + " " + yearRaise + ")))"));
    check(out, "casts: inside the guard the wrapped int product by 12 is the exact month count",
        "unsat", List.of(decl, in, "(assert (not " + outside(years) + "))",
            "(assert (not (= (jint.wrap (* 12 (l2i x))) (* 12 x))))"));
    check(out, "casts: with the year bound one higher the guard is wrong", "sat",
        List.of(decl, in, "(assert (not (= " + outside(years + 1) + " " + yearRaise + ")))"));
    check(out, "casts: with the year bound one lower the guard is wrong", "sat",
        List.of(decl, in, "(assert (not (= " + outside(years - 1) + " " + yearRaise + ")))"));
    for (Map.Entry<String, Long> e : DAY_TIME_UNITS) {
      long unit = e.getValue();
      long b = Long.MAX_VALUE / unit;
      String raise = "(not (jlong.in (* x " + unit + ")))";
      check(out, "casts: a bigint's day-time " + e.getKey() + " guard holds exactly where Spark "
          + "raises", "unsat",
          List.of(decl, in, "(assert (not (= " + outside(b) + " " + raise + ")))"));
      check(out, "casts: inside the " + e.getKey() + " guard the wrapped long product is the "
          + "exact one", "unsat", List.of(decl, in, "(assert (not " + outside(b) + "))",
              "(assert (not (= (jlong.wrap (* x " + unit + ")) (* x " + unit + "))))"));
      check(out, "casts: with the " + e.getKey() + " bound one higher the guard is wrong", "sat",
          List.of(decl, in, "(assert (not (= " + outside(b + 1) + " " + raise + ")))"));
      check(out, "casts: with the " + e.getKey() + " bound one lower the guard is wrong", "sat",
          List.of(decl, in, "(assert (not (= " + outside(b - 1) + " " + raise + ")))"));
    }

The names `decl` and `in` are declared inside the loop above too, in its own scope, so they do
not clash. `jint.in`, `jlong.in`, `jint.wrap`, `jlong.wrap` and `l2i` are `java.smt2`'s.

**What the block renders**, for checking the regenerated file by eye: the bounds are
`178956970` (years), `106751991` (day), `2562047788` (hour), `153722867280` (minute) and
`9223372036854` (second), each checked in Python against Spark's rules over the long's ends and
a sample of 400041 longs (`scratch-332/bounds_check.out`: `bounds_check: 400041 longs and 440017
ints agree with Spark's rules`). The first check renders as

    (echo "casts: a bigint's INTERVAL YEAR guard holds exactly where Spark raises: expect unsat")
    (push 1)
    (declare-const x Int)
    (assert (jlong.in x))
    (assert (not (= (or (< x (- 178956970)) (> x 178956970)) (or (not (jint.in x)) (not (jint.in (* 12 x)))))))
    (check-sat)
    (pop 1)

**Step 3, commands.**

    build/sbt catalyst/Test/compile                                          -> [success]
    VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'  -> passes, rewrites casts.smt2
    grep -c '(check-sat)' sql/varka/proofs/casts.smt2                        -> 38
    grep -c 'expect unsat' sql/varka/proofs/casts.smt2                       -> 16
    build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'                      -> passes (no regeneration)
    dev/varka_prove.sh --solver both casts.smt2                              -> exits 0

The unsat count: the 18 checks on master hold 6 unsat (two for each of three targets), and the
block adds 2 for the year and 2 for each of four units, 10: 16 in all. If a solver is missing,
`dev/varka_prove.sh --install-shared both` installs the pinned ones.

**The proofs' README row** (fix round of 28 November 2026, review 1, L5). In
`sql/varka/proofs/README.md`, the table row that starts `` | `casts.smt2` | ``
names the bigint narrowings only. In its second cell, after "and the int lane's guard is
`narrow_range.smt2`'s", add "; and the interval casts of a `BIGINT` (VARKA-332,
`VarkaIntervalCastCompiler`): the guard of `INTERVAL YEAR` (bound `MAX_YEARS`) and of each
day-time unit (bound `Long.MAX_VALUE / unit`) holds exactly where Spark raises `CAST_OVERFLOW`,
the wrapped product is exact inside it, and each bound moved by one is wrong".

**Commit what the regeneration wrote** (review 1, M3). `casts.smt2` is a generated file,
rewritten by the `VARKA_PROOFS_REGEN=true` run above. `git status --short` then lists exactly
`PF`, `C/VarkaIntervalCompiler.java`, `sql/varka/proofs/casts.smt2`,
`sql/varka/proofs/README.md` and, after step 4, `M8/VARKA-332.md`; commit them all. Running the
`VARKA_PROOFS_REGEN=true` line once more changes nothing (`git status --short` prints nothing).
Any other path in the list is a stop: report it.

**Step 4.** `M8/casts.tsv`'s `proof` column already names SMT for `bigint` to `ym` and `dt` and
for `dt` to the integral types: nothing to change. Add to section 10 a subsection 10.5 "The
64-bit sources' proof": the twenty checks, the two solvers' verdicts, and which existing checks
cover the month end and the day-time narrowings (11.3 decision 2).

**Done when** step 3 prints what it says and the gate passes. **Not here:** any compiler change
but the one `public`.

### 11.6 Part 332s: the 32-bit sources, exhausted

*Rewritten in the fix round of 28 November 2026 (review 1, findings H1, H2, M3, M4, M5): the
sweeps are 24 `@Test` methods, the slice a template, a timing command that selects one sweep in
the test JVM, an in-range pass for the guarded sweeps, and the native arm named.*

**Title:** `[VARKA-332] Sweep every int and year-month count through the integral casts`.
**Branch:** `varka-332s-sweeps`, from master after 332c merged. **Builder:** Sonnet-class.

**B0.**

    ls C/VarkaIntervalCastCompiler.java                       -> the file (332c merged)
    grep -c 'private static Object compile(Expression expr' S -> 1
    grep -c 'private static int runAll(Kernel k' S            -> 1
    grep -c 'private static boolean widthDecline(String reason)' S   -> 1
    grep -c 'new Arm("the native arm"' S                      -> 1
    grep -c 'opt-in' S                                        -> 0
    grep -c 'VarkaEmitterNarrowArithSuite' sql/varka/ci/sweeps.tsv   -> 4
    python3 dev/varka_sweeps.py --check   -> varka_sweeps: 18 rows listed, none missing

**The 24 sweeps** (the owner's cut (b) of question 1, one arm per tree: the native arm,
`ARMS.get(0)` of `S`, which is `VarkaMatrixRuns.BASE` with `narrowLanes(true)`, so a sweep
follows the option matrix's configuration as every other test of `S` does). The last column is
`[lo, hi]`: Spark raises on a valid row exactly when its value is outside it ("all": never).

| # | source column | target | modes swept (one tree each) | `[lo, hi]` of the source |
|---|---|---|---|---|
| 1 to 3 | `INT` | `TINYINT` | LEGACY (wrap), ANSI (decline), TRY (null) | ANSI `[-128, 127]`; else all |
| 4 to 6 | `INT` | `SMALLINT` | LEGACY, ANSI, TRY | ANSI `[-32768, 32767]`; else all |
| 7 | `INT` | `BOOLEAN` | LEGACY | all |
| 8, 9 | `INT` | `INTERVAL YEAR` | ANSI (decline; LEGACY's tree is the same), TRY | ANSI `[-178956970, 178956970]`; TRY all |
| 10, 11 | `INT` | `INTERVAL DAY` | ANSI, TRY | ANSI `[-106751991, 106751991]`; TRY all |
| 12 to 14 | `INT` | `INTERVAL HOUR`, `MINUTE`, `SECOND` | LEGACY (no guard) | all |
| 15, 16 | `INTERVAL YEAR` | `TINYINT` | ANSI, TRY | ANSI `[-1547, 1535]` (months); TRY all |
| 17, 18 | `INTERVAL YEAR` | `SMALLINT` | ANSI, TRY | ANSI `[-393227, 393215]`; TRY all |
| 19, 20 | `INTERVAL MONTH` | `TINYINT` | ANSI, TRY | ANSI `[-128, 127]`; TRY all |
| 21, 22 | `INTERVAL MONTH` | `SMALLINT` | ANSI, TRY | ANSI `[-32768, 32767]`; TRY all |
| 23 | `INTERVAL YEAR` | `BIGINT` | LEGACY | all |
| 24 | `INTERVAL MONTH` | `BIGINT` | LEGACY | all |

The intervals were checked in Python against Spark's rules (`IntervalUtils`'s truncating
`v / 12`, `multiplyExact`, the narrowings' range checks) over every int within 30 of each end and
100000 others: `sweep_intervals: 8 intervals hold against Spark's rules`
(`scratch-332/sweep_intervals.out`). A year-month column's value is its month count, an int, so
an `INTERVAL YEAR` column is swept over every int too: a superset of the values Spark writes.

**Why an in-range pass.** The main pass runs 2^16 batches of 2^16 consecutive ints, aligned to
2^16. Under ANSI the in-range values of a narrowing to `TINYINT` or `SMALLINT` share each batch
with values Spark raises on, so every main-pass batch declines and no answer is compared
(`scratch-332/sweep_tests.out`: `every INT value casts to TINYINT in ANSI: full 65536 batches, 0
answered`). A guarded sweep therefore also runs `[lo, hi]` alone, in batches of up to 2^16 from
`lo`, each of which must answer and is compared with the reference.

**Step 1, the code in `S`.** Add these imports to `S` beside its others (`S` already imports
`YearMonthIntervalType`, `DayTimeIntervalType`, `DisplayName`, `Test`, `assumeTrue`, `fail`):

    import java.util.Arrays;
    import java.util.Set;
    import java.util.TreeSet;
    import java.util.stream.Stream;
    import org.junit.jupiter.params.ParameterizedTest;
    import org.junit.jupiter.params.provider.MethodSource;

Then add, at the end of the class, after the test `integralIntervalCasts` that 332c brought, this
section exactly (it reuses `Type`, `type(...)`, `Mode`, `LEGACY`, `ANSI`, `TRY`, `TINYINT`,
`SMALLINT`, `INT`, `BIGINT`, `BOOLEAN`, `ARMS`, `Kernel`, `compile`, `cast`, `widthDecline`,
`answers` and `intBatch`, all of `S` after 332c):

      // -------------------------------------------------------------------------------------------
      // The 32-bit sources, exhausted (opt-in)
      // -------------------------------------------------------------------------------------------

      /** The sweeps' interval targets; a target's column is never read. */
      private static final Type TO_YEAR = type("INTERVAL YEAR", new YearMonthIntervalType(
          YearMonthIntervalType.YEAR(), YearMonthIntervalType.YEAR()), "swty");
      private static final Type TO_DAY = type("INTERVAL DAY", new DayTimeIntervalType(
          DayTimeIntervalType.DAY(), DayTimeIntervalType.DAY()), "swtd");
      private static final Type TO_HOUR = type("INTERVAL HOUR", new DayTimeIntervalType(
          DayTimeIntervalType.HOUR(), DayTimeIntervalType.HOUR()), "swth");
      private static final Type TO_MINUTE = type("INTERVAL MINUTE", new DayTimeIntervalType(
          DayTimeIntervalType.MINUTE(), DayTimeIntervalType.MINUTE()), "swtm");
      private static final Type TO_SECOND = type("INTERVAL SECOND", new DayTimeIntervalType(
          DayTimeIntervalType.SECOND(), DayTimeIntervalType.SECOND()), "swts");

      /** The sweeps' year-month sources: a column of month counts, each an int. */
      private static final Type YEARS = type("INTERVAL YEAR", new YearMonthIntervalType(
          YearMonthIntervalType.YEAR(), YearMonthIntervalType.YEAR()), "swfy");
      private static final Type MONTHS = type("INTERVAL MONTH", new YearMonthIntervalType(
          YearMonthIntervalType.MONTH(), YearMonthIntervalType.MONTH()), "swfm");

      /**
       * One sweep: a cast tree over every value of a 32-bit source. Spark raises on a valid row
       * exactly when its value is outside {@code [lo, hi]}; a sweep whose interval is the whole int
       * range is unguarded.
       */
      private record Sweep(Type from, Type to, Mode mode, long lo, long hi) {
        String name() {
          return "every " + from.sql() + " value casts to " + to.sql() + " in " + mode.name();
        }

        boolean raises(long v) {
          return v < lo || v > hi;
        }

        boolean guarded() {
          return lo > Integer.MIN_VALUE || hi < Integer.MAX_VALUE;
        }

        @Override
        public String toString() {
          return name();
        }
      }

      private static final List<Sweep> SWEEPS = sweeps();

      private static List<Sweep> sweeps() {
        long all = Integer.MIN_VALUE;
        long top = Integer.MAX_VALUE;
        return List.of(
            new Sweep(INT, TINYINT, LEGACY, all, top), new Sweep(INT, TINYINT, ANSI, -128, 127),
            new Sweep(INT, TINYINT, TRY, all, top),
            new Sweep(INT, SMALLINT, LEGACY, all, top), new Sweep(INT, SMALLINT, ANSI, -32768, 32767),
            new Sweep(INT, SMALLINT, TRY, all, top),
            new Sweep(INT, BOOLEAN, LEGACY, all, top),
            new Sweep(INT, TO_YEAR, ANSI, -178956970, 178956970),
            new Sweep(INT, TO_YEAR, TRY, all, top),
            new Sweep(INT, TO_DAY, ANSI, -106751991, 106751991), new Sweep(INT, TO_DAY, TRY, all, top),
            new Sweep(INT, TO_HOUR, LEGACY, all, top), new Sweep(INT, TO_MINUTE, LEGACY, all, top),
            new Sweep(INT, TO_SECOND, LEGACY, all, top),
            new Sweep(YEARS, TINYINT, ANSI, -1547, 1535), new Sweep(YEARS, TINYINT, TRY, all, top),
            new Sweep(YEARS, SMALLINT, ANSI, -393227, 393215),
            new Sweep(YEARS, SMALLINT, TRY, all, top),
            new Sweep(MONTHS, TINYINT, ANSI, -128, 127), new Sweep(MONTHS, TINYINT, TRY, all, top),
            new Sweep(MONTHS, SMALLINT, ANSI, -32768, 32767),
            new Sweep(MONTHS, SMALLINT, TRY, all, top),
            new Sweep(YEARS, BIGINT, LEGACY, all, top), new Sweep(MONTHS, BIGINT, LEGACY, all, top));
      }

      private static final int SWEEP_ROWS = 1 << 16;
      private static final int SWEEP_BATCHES = 1 << 16;

      /** {@code length} consecutive values from {@code start}; every seventh row null if masked. */
      private static LongBatch consecutive(long start, int length, boolean masked) {
        var data = new long[1][length];
        var nulls = new boolean[1][length];
        for (int i = 0; i < length; i++) {
          data[0][i] = start + i;
          nulls[0][i] = masked && i % 7 == 3;
        }
        return new LongBatch(length, nulls, data, false);
      }

      /** Batch {@code k} of the main pass: 2^16 ints from MIN_VALUE + k * 2^16; odd ones masked. */
      private static LongBatch sweepBatch(int k) {
        return consecutive(Integer.MIN_VALUE + (long) k * SWEEP_ROWS, SWEEP_ROWS, (k & 1) == 1);
      }

      /**
       * The batches of a guarded sweep's in-range pass, 0 for an unguarded one. The main pass's
       * batches are aligned to 2^16, so under ANSI a narrowing's in-range values share each batch
       * with values Spark raises on and are never answered there; this pass answers them.
       */
      private static int inRangeBatches(Sweep s) {
        return s.guarded() ? (int) ((s.hi() - s.lo()) / SWEEP_ROWS + 1) : 0;
      }

      /** Batch {@code j} of the in-range pass: up to 2^16 values from lo + j * 2^16, to hi. */
      private static LongBatch inRangeBatch(Sweep s, int j) {
        long start = s.lo() + (long) j * SWEEP_ROWS;
        return consecutive(start, (int) Math.min(SWEEP_ROWS, s.hi() - start + 1), (j & 1) == 1);
      }

      /** The main-pass batches that hold lo - 1, lo, hi or hi + 1, where those are ints. */
      private static Set<Integer> endBatches(Sweep s) {
        var ks = new TreeSet<Integer>();
        for (long v : new long[] {s.lo() - 1, s.lo(), s.hi(), s.hi() + 1}) {
          if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
            ks.add((int) ((v - Integer.MIN_VALUE) / SWEEP_ROWS));
          }
        }
        return ks;
      }

      /**
       * One batch of {@code s} through the loaded kernel, compared row by row with the reference,
       * and with Spark when {@code spark}. It must decline when a valid row holds a value Spark
       * raises on, and answer when no row holds one; a batch where only a null row does may do
       * either (the guard reads stored slots, `VARKA-332.md` 10.3). Returns the kernel's status.
       */
      private static int sweepOne(Sweep s, Kernel k, VarkaEmitterFixtures.Loaded loaded,
          LongBatch batch, String where, boolean spark) {
        boolean must = false;
        boolean may = false;
        for (int i = 0; i < batch.length(); i++) {
          boolean out = s.raises(batch.data()[0][i]);
          may |= out;
          must |= out && !batch.nulls()[0][i];
        }
        String ctx = s.name() + " " + where;
        SparkAnswers answers = spark ? answers(k.oracle()) : null;
        int status = k.longLane()
            ? VarkaKernelChecks.runLoadedLong(ctx, loaded.kernel(), k.roots(), k.numInputs(),
                k.longLits(), k.lits(), batch, may, answers, CallHook.NONE)
            : VarkaKernelChecks.runLoadedNarrow(ctx, loaded.kernel(), k.roots(), k.numInputs(),
                k.lits(), intBatch(batch), may, answers, CallHook.NONE);
        if (must) {
          assertTrue(status != 0, ctx + ": answered a batch holding a value Spark raises on");
        }
        if (!may) {
          assertEquals(0, status, ctx + ": declined a batch Spark answers");
        }
        return status;
      }

      /**
       * Runs {@code s} at the native arm, every batch when {@code full}, else the slice: every
       * 256th main-pass batch, the last one and the end batches, and every 256th in-range batch
       * with the first and the last. Spark checks the end batches, the in-range pass's first and
       * last, and every 256th batch of a full sweep or every 16384th of a slice. Prints one line.
       */
      private static void sweep(Sweep s, boolean full) {
        Object compiled = compile(cast(s.from().column(), s.to(), s.mode()),
            List.of(s.from().column()), ARMS.get(0).options());
        if (compiled instanceof String reason) {
          // A configuration that cannot load the column at its width cancels, as this class's
          // other tests do; any other decline is a failure.
          assumeTrue(!widthDecline(reason), s.name() + ": " + reason);
          fail(s.name() + " declined: " + reason);
        }
        Kernel k = (Kernel) compiled;
        Set<Integer> ends = endBatches(s);
        int sparkEvery = full ? 256 : 16384;
        var loaded = VarkaEmitterFixtures.load(new VarkaEmitterFixtures.Emitted(k.name(),
            k.bytes()));
        long started = System.nanoTime();
        long sparkBefore = VarkaKernelChecks.SPARK_COMPARISONS.get();
        int run = 0;
        int answered = 0;
        int inRange = 0;
        try {
          for (int b = 0; b < SWEEP_BATCHES; b++) {
            boolean end = ends.contains(b);
            if (!full && b % 256 != 0 && !end && b != SWEEP_BATCHES - 1) {
              continue;
            }
            boolean spark = end || b % sparkEvery == 0;
            int status = sweepOne(s, k, loaded, sweepBatch(b), "batch " + b, spark);
            run++;
            answered += status == 0 ? 1 : 0;
          }
          int last = inRangeBatches(s) - 1;
          for (int j = 0; j <= last; j++) {
            boolean edge = j == 0 || j == last;
            if (!full && j % 256 != 0 && !edge) {
              continue;
            }
            boolean spark = edge || (full && j % 256 == 0);
            sweepOne(s, k, loaded, inRangeBatch(s, j), "in-range batch " + j, spark);
            inRange++;
          }
        } finally {
          loaded.loader().release();
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        long rows = VarkaKernelChecks.SPARK_COMPARISONS.get() - sparkBefore;
        // Printed rather than logged: an own-JVM class's child prints to the parent's output.
        System.out.println(s.name() + ": " + run + " batches, " + answered + " answered, "
            + inRange + " in-range batches, in " + seconds + " s, " + rows
            + " rows held to Spark");
      }

      /** Whether {@code -Dvarka.sweep.filter} is unset or holds a text {@code name} contains. */
      private static boolean selected(String name) {
        String filters = System.getProperty("varka.sweep.filter");
        return filters == null || Arrays.stream(filters.split(",")).anyMatch(name::contains);
      }

      /** The opt-in sweep named {@code name}, over every value of its source. */
      private static void sweepAll(String name) {
        assumeTrue("true".equals(System.getProperty("varka.sweep")),
            "set -Dvarka.sweep=true to sweep every value of the source");
        assumeTrue(selected(name), "-Dvarka.sweep.filter does not name '" + name + "'");
        Sweep s = SWEEPS.stream().filter(w -> w.name().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("no sweep is named '" + name + "'"));
        sweep(s, true);
      }

      static Stream<Sweep> sweepCases() {
        return SWEEPS.stream();
      }

      @ParameterizedTest(name = "{0}")
      @MethodSource("sweepCases")
      @DisplayName("a 2^24-row slice:")
      public void slice(Sweep s) {
        sweep(s, false);
      }

      @Test
      @DisplayName("each sweep has one full-sweep test, named by the sweep")
      public void eachSweepHasOneFullSweepTest() {
        String tail = ", all 2^32 (opt-in: -Dvarka.sweep=true)";
        List<String> tests = Arrays.stream(VarkaEmitterIntegralCastSuite.class.getDeclaredMethods())
            .map(m -> m.getAnnotation(DisplayName.class))
            .filter(d -> d != null && d.value().endsWith(tail))
            .map(d -> d.value().substring(0, d.value().length() - tail.length()))
            .sorted().toList();
        List<String> names = SWEEPS.stream().map(Sweep::name).sorted().toList();
        assertEquals(names, tests);
      }

      // The 2^32 sweeps stay one method each: dev/varka_sweeps.py's shards select an opt-in test by
      // the display name in the source, which a template's generated names are not.

and after it the 24 opt-in tests, one a sweep, in this order and with these texts exactly:

      @Test
      @DisplayName("every INT value casts to TINYINT in LEGACY, all 2^32 (opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToTinyintInLegacyAll() {
        sweepAll("every INT value casts to TINYINT in LEGACY");
      }

      @Test
      @DisplayName("every INT value casts to TINYINT in ANSI, all 2^32 (opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToTinyintInAnsiAll() {
        sweepAll("every INT value casts to TINYINT in ANSI");
      }

      @Test
      @DisplayName("every INT value casts to TINYINT in TRY, all 2^32 (opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToTinyintInTryAll() {
        sweepAll("every INT value casts to TINYINT in TRY");
      }

      @Test
      @DisplayName("every INT value casts to SMALLINT in LEGACY, all 2^32 (opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToSmallintInLegacyAll() {
        sweepAll("every INT value casts to SMALLINT in LEGACY");
      }

      @Test
      @DisplayName("every INT value casts to SMALLINT in ANSI, all 2^32 (opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToSmallintInAnsiAll() {
        sweepAll("every INT value casts to SMALLINT in ANSI");
      }

      @Test
      @DisplayName("every INT value casts to SMALLINT in TRY, all 2^32 (opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToSmallintInTryAll() {
        sweepAll("every INT value casts to SMALLINT in TRY");
      }

      @Test
      @DisplayName("every INT value casts to BOOLEAN in LEGACY, all 2^32 (opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToBooleanInLegacyAll() {
        sweepAll("every INT value casts to BOOLEAN in LEGACY");
      }

      @Test
      @DisplayName("every INT value casts to INTERVAL YEAR in ANSI, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToIntervalYearInAnsiAll() {
        sweepAll("every INT value casts to INTERVAL YEAR in ANSI");
      }

      @Test
      @DisplayName("every INT value casts to INTERVAL YEAR in TRY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToIntervalYearInTryAll() {
        sweepAll("every INT value casts to INTERVAL YEAR in TRY");
      }

      @Test
      @DisplayName("every INT value casts to INTERVAL DAY in ANSI, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToIntervalDayInAnsiAll() {
        sweepAll("every INT value casts to INTERVAL DAY in ANSI");
      }

      @Test
      @DisplayName("every INT value casts to INTERVAL DAY in TRY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToIntervalDayInTryAll() {
        sweepAll("every INT value casts to INTERVAL DAY in TRY");
      }

      @Test
      @DisplayName("every INT value casts to INTERVAL HOUR in LEGACY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToIntervalHourInLegacyAll() {
        sweepAll("every INT value casts to INTERVAL HOUR in LEGACY");
      }

      @Test
      @DisplayName("every INT value casts to INTERVAL MINUTE in LEGACY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToIntervalMinuteInLegacyAll() {
        sweepAll("every INT value casts to INTERVAL MINUTE in LEGACY");
      }

      @Test
      @DisplayName("every INT value casts to INTERVAL SECOND in LEGACY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntValueCastsToIntervalSecondInLegacyAll() {
        sweepAll("every INT value casts to INTERVAL SECOND in LEGACY");
      }

      @Test
      @DisplayName("every INTERVAL YEAR value casts to TINYINT in ANSI, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntervalYearValueCastsToTinyintInAnsiAll() {
        sweepAll("every INTERVAL YEAR value casts to TINYINT in ANSI");
      }

      @Test
      @DisplayName("every INTERVAL YEAR value casts to TINYINT in TRY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntervalYearValueCastsToTinyintInTryAll() {
        sweepAll("every INTERVAL YEAR value casts to TINYINT in TRY");
      }

      @Test
      @DisplayName("every INTERVAL YEAR value casts to SMALLINT in ANSI, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntervalYearValueCastsToSmallintInAnsiAll() {
        sweepAll("every INTERVAL YEAR value casts to SMALLINT in ANSI");
      }

      @Test
      @DisplayName("every INTERVAL YEAR value casts to SMALLINT in TRY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntervalYearValueCastsToSmallintInTryAll() {
        sweepAll("every INTERVAL YEAR value casts to SMALLINT in TRY");
      }

      @Test
      @DisplayName("every INTERVAL MONTH value casts to TINYINT in ANSI, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntervalMonthValueCastsToTinyintInAnsiAll() {
        sweepAll("every INTERVAL MONTH value casts to TINYINT in ANSI");
      }

      @Test
      @DisplayName("every INTERVAL MONTH value casts to TINYINT in TRY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntervalMonthValueCastsToTinyintInTryAll() {
        sweepAll("every INTERVAL MONTH value casts to TINYINT in TRY");
      }

      @Test
      @DisplayName("every INTERVAL MONTH value casts to SMALLINT in ANSI, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntervalMonthValueCastsToSmallintInAnsiAll() {
        sweepAll("every INTERVAL MONTH value casts to SMALLINT in ANSI");
      }

      @Test
      @DisplayName("every INTERVAL MONTH value casts to SMALLINT in TRY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntervalMonthValueCastsToSmallintInTryAll() {
        sweepAll("every INTERVAL MONTH value casts to SMALLINT in TRY");
      }

      @Test
      @DisplayName("every INTERVAL YEAR value casts to BIGINT in LEGACY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntervalYearValueCastsToBigintInLegacyAll() {
        sweepAll("every INTERVAL YEAR value casts to BIGINT in LEGACY");
      }

      @Test
      @DisplayName("every INTERVAL MONTH value casts to BIGINT in LEGACY, all 2^32 "
          + "(opt-in: -Dvarka.sweep=true)")
      public void everyIntervalMonthValueCastsToBigintInLegacyAll() {
        sweepAll("every INTERVAL MONTH value casts to BIGINT in LEGACY");
      }

What each piece is for, so that a builder who must adapt one knows what may not change:

- The `@DisplayName` literals of the 24 tests are what `dev/varka_sweeps.py --check` reads
  (its `DISPLAY` pattern, joined over `+`): each must hold `opt-in`, and the text before
  `, all 2^32` must equal the sweep's `name()`. The test `eachSweepHasOneFullSweepTest` holds that
  second rule in every build. No other display name of `S` may hold `opt-in` (that check would
  then ask a shard to select it).
- The literal `System.getProperty("varka.sweep")` in `sweepAll` is what the check's `READS`
  pattern finds; it must stay in that form.
- `slice` is one template: its key is `a 2^24-row slice: ` and the sweep's name (the house rule
  for template keys, `sql/varka/skills/testing-and-debugging.md`, "Generated tests"); its
  argument renders by `Sweep.toString`. The weekly shards' `-z` texts also select the slices
  whose names they contain, which adds the slices' few seconds to a shard's run; that is accepted.
- A width decline cancels, as the other tests of `S` do, so the option matrix stays green on a
  configuration that cannot load a column at its width; any other decline fails.
- `selected` reads `-Dvarka.sweep.filter`, as `VarkaEmitterNarrowArithSuite.sweeps` does: the
  one way to run a single 2^32 sweep (step 4).

If `runLoadedLong` or `runLoadedNarrow` takes other arguments on master than `runAll` passes in
`S`, change `sweepOne`'s two calls to `runAll`'s form, keeping only the batch, the decline flag
(`may`) and the Spark answers (`answers`, or `null`) as above.

**What each sweep must print**, computed in Python from the batch layout (no JVM;
`scratch-332/sweep_tests.py`, output `scratch-332/sweep_tests.out`; no batch of any sweep is
ambiguous, that is, holds a value Spark raises on in null rows only, so every number is exact).
The line reads `<name>: <run> batches, <answered> answered, <in-range> in-range batches, in <t>
s, <r> rows held to Spark`:

| sweep | full: run, answered, in-range | slice: run, answered, in-range |
|---|---|---|
| every unguarded sweep (LEGACY and TRY, 16 of them) | 65536, 65536, 0 | 257, 257, 0 |
| `INT` to `TINYINT` or `SMALLINT` in ANSI; `INTERVAL MONTH` to either in ANSI; `INTERVAL YEAR` to `TINYINT` in ANSI | 65536, 0, 1 | 258, 0, 1 |
| `INT` to `INTERVAL YEAR` in ANSI | 65536, 5460, 5462 | 259, 21, 23 |
| `INT` to `INTERVAL DAY` in ANSI | 65536, 3256, 3258 | 259, 13, 14 |
| `INTERVAL YEAR` to `SMALLINT` in ANSI | 65536, 12, 13 | 260, 2, 2 |

**Step 2, the weekly job.** Four rows in `sql/varka/ci/sweeps.tsv`, right after the last
`VarkaEmitterNarrowArithSuite` row, tab separated:

    VarkaEmitterIntegralCastSuite	java	varka.sweep	shard	every INT value casts to TINYINT|every INT value casts to SMALLINT	varka-sweeps	INT to TINYINT and SMALLINT in three modes: 6 sweeps of 2^32; not yet timed
    VarkaEmitterIntegralCastSuite	java	varka.sweep	shard	every INT value casts to BOOLEAN|every INT value casts to INTERVAL	varka-sweeps	INT to BOOLEAN and to five intervals: 8 sweeps of 2^32; not yet timed
    VarkaEmitterIntegralCastSuite	java	varka.sweep	shard	every INTERVAL YEAR value	varka-sweeps	INTERVAL YEAR to TINYINT, SMALLINT and BIGINT: 5 sweeps of 2^32; not yet timed
    VarkaEmitterIntegralCastSuite	java	varka.sweep	shard	every INTERVAL MONTH value	varka-sweeps	INTERVAL MONTH to TINYINT, SMALLINT and BIGINT: 5 sweeps of 2^32; not yet timed

These rows and the 24 tests above were run through `dev/varka_sweeps.py`'s own `check` (the
module imported from the reading worktree, over a copy of every test source that reads a sweep
property with `S` replaced by the branch's `S` plus this part): no finding, and the line `22 rows
listed`; with the fourth row left out it finds the 5 `INTERVAL MONTH` tests "selected by no
shard"; with the `BOOLEAN` test left out it finds the text `every INT value casts to BOOLEAN`
"selects no opt-in test" (`scratch-332/sweep_tests.out`).

**Step 3, commands.** With `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
SPARK_LOCAL_IP=127.0.0.1`:

    build/sbt catalyst/Test/compile                                       -> [success]
    build/sbt 'catalyst/testOnly *VarkaEmitterIntegralCastSuite'
        -> Failed 0, Errors 0 (record the total; 49 tests are new: 24 slices, the naming
           test, and the 24 opt-in tests, which are cancelled without -Dvarka.sweep=true),
           and 24 lines holding ' in-range batches, in ', one a slice, with the slice
           numbers of the table above
    python3 dev/varka_sweeps.py --check   -> varka_sweeps: 22 rows listed, none missing
    grep -c 'opt-in: -Dvarka.sweep=true' S                                -> 25   (the 24 display names and the naming test's tail string)
    python3 dev/varka_sweeps.py --matrix | grep -o 'VarkaEmitterIntegralCastSuite: ' | wc -l   -> 4

**The slices' cost, and its stop rule.** A registered prediction: the 24 slices add under 120 s
to the suite on the laptop. Its ground: a slice runs 257 to 260 batches of 2^16 rows through the
kernel and the reference and Spark-checks 4 of them plus the end batches, the shape of
`VarkaEmitterNarrowArithSuite`'s 18 slices (Spark on every 64th of 256 operand batches), whose
whole suite weighs 52.4 s in `sql/varka/ci/suite-times.tsv`. Record the suite's time before and
after this part in the pull request (`sql/varka/AGENTS.md`, "A change that adds test time says
so"). If it grows by more than 120 s, change `16384` in `sweep` to `65536` (Spark on batch 0 and
the end batches only) and run again; if it still grows by more than 120 s, stop and report both
times.

**Step 4, one sweep at full size, timed.** The lead runs this in an idle window, alone. sbt does
not pass a `-D` given to `build/sbt` to the forked test JVM (only `spark.*` properties,
`project/SparkBuild.scala`), so the properties go into `Test/javaOptions`, the form
`dev/varka_sweeps.py --nightly` prints and `dev/varka_nightly.sh` runs. `-Dvarka.ownJvm=true`
runs the class's tests in that JVM instead of a child (`VarkaChildJvm.PROPERTY`), so each test
is timed by itself, and `-Dvarka.sweep.filter` leaves the other 23 sweeps cancelled. sbt's `-z`
selects no test of a JUnit suite (`dev/varka_sweeps.py`, `nightly`), so it is not used.

    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1 build/sbt -batch \
      "project catalyst" \
      'set Test/javaOptions ++= Seq("-Dvarka.sweep=true", "-Dvarka.sweep.filter=every INT value casts to TINYINT in TRY", "-Dvarka.ownJvm=true", "-Dspark.test.timeout=300", "-Dvarka.test.watchdog.minutes=300")' \
      "testOnly *VarkaEmitterIntegralCastSuite" 2>&1 | tee ~/varka-ops/varka-332s-timed.log

Done means both: sbt prints `Failed 0, Errors 0`, and exactly one line of the form
`every INT value casts to TINYINT in TRY: 65536 batches, 65536 answered, 0 in-range batches, in
T s, R rows held to Spark` (`grep -c ': 65536 batches' ~/varka-ops/varka-332s-timed.log` prints
`1`). `R` is expected to be 16842752 (257 Spark-checked batches of 2^16 rows, every one
answered); `R = 0` is a stop. If the
first count is `0` (the filter did not reach the JVM) or `24`, stop and report the log. This TRY
sweep is the costliest kind (every batch answered and compared), so `T` bounds every sweep.

**The shard rule, as numbers.** A runner is at most 3.7 times slower than the laptop on any sweep
timed (`.github/workflows/varka-sweeps.yml`, its header, the calibration of 6 November 2026), and
a sweep job has 300 minutes per test and 350 per job (`SWEEP_MINUTES` in `dev/varka_sweeps.py`;
`timeout-minutes` of the `sweep` job). The longest shard holds 8 sweeps, so:

- `T <= 600`: keep the four rows; replace each "not yet timed" by "about <8 x T or 6 x T or
  5 x T> s on the laptop, from one sweep of <T> s" with that row's sweep count.
- `600 < T <= 1200`: replace the four rows by these eight (at most 4 sweeps a shard; checked
  with the same script: no finding, `26 rows listed`), each note "<k> sweeps of 2^32, about
  <k x T> s on the laptop":

      every INT value casts to TINYINT
      every INT value casts to SMALLINT
      every INT value casts to BOOLEAN|every INT value casts to INTERVAL YEAR
      every INT value casts to INTERVAL DAY|every INT value casts to INTERVAL HOUR
      every INT value casts to INTERVAL MINUTE|every INT value casts to INTERVAL SECOND
      every INTERVAL YEAR value casts to TINYINT|every INTERVAL YEAR value casts to SMALLINT
      every INTERVAL YEAR value casts to BIGINT|every INTERVAL MONTH value casts to BIGINT
      every INTERVAL MONTH value casts to TINYINT|every INTERVAL MONTH value casts to SMALLINT

  (each a `select` cell; the other six cells as in step 2), then rerun `python3
  dev/varka_sweeps.py --check` -> `varka_sweeps: 26 rows listed, none missing`.
- `T > 1200`: stop and report `T`; the lead re-plans the shards.

**After regeneration** (M3): this part regenerates nothing, but run `git status --short` before
the commit: it lists only `S` and `sql/varka/ci/sweeps.tsv`. Commit both; then `git status
--short` prints nothing.

**Step 5, the record.** Section 10 gains 10.6, "The 32-bit sources, exhausted": the 24 sweeps,
the in-range pass and why, the slice and its measured cost, the timed sweep's `T` and `R`, the
shards. The first scheduled run of `varka-sweeps.yml` after the merge is read by the lead
(`sql/varka/AGENTS.md`, "Master stays green"), and its jobs' results go into 10.6 by a
results-only pull request.

**Done when** step 3 prints what it says, the gate passes, and 10.6 names the timed sweep. **Not
here:** a sweep of a 64-bit source (332p proves those), the other arm (the owner's cut).

### 11.7 Part 332m: the measurement and the row's record

**Title:** `[VARKA-332] Measure the fused integral casts on the EPYC 7763 and score the plan`.
**Branch:** `varka-332m-measure`. **Builder:** Sonnet-class. The lead dispatches; the builder
splices, scores and writes.

*Steps 2 and 3 rewritten in the fix round of 28 November 2026 (review 1, M7, L4).*

1. The lead dispatches `benchmark.yml` for `VarkaIntegralCastBaselineBenchmark` at 256 bits and
   at 128 bits, as `VARKA-597.md` 11.5 step 10 writes the command (`-f class=...
   -f expected-cpu="AMD EPYC 7763"`, and `-f extra-java-options=-XX:MaxVectorSize=16` for the
   second), on master's head after 332p and 332s merged, with no `sections` and no
   `create-commit` (its default, `false`), so each run returns its files as an artifact.
2. **Where the files come from.** The lead downloads each run's artifact (`gh run download
   <run-id> -R vecbricks/varka`) and puts its results file, which a CI run writes under the plain
   name `VarkaIntegralCastBaselineBenchmark-jdk25-results.txt`, into the part's worktree. The
   builder commits the two runs as new files beside the baseline, which stays (the house's names,
   `sql/varka/skills/benchmarking.md`, "A number or a tag before `-results.txt`: one more stored
   run of that class on that CPU"; "Do not rename a committed file"):

       sql/core/benchmarks/VarkaIntegralCastBaselineBenchmark-jdk25-runner-epyc7763-2-results.txt          (256 bits)
       sql/core/benchmarks/VarkaIntegralCastBaselineBenchmark-jdk25-runner-epyc7763-128bit-2-results.txt   (128 bits)

   each with a provenance file of the same name ending `-2-provenance.txt`, in the fields of the
   baseline's `VarkaIntegralCastBaselineBenchmark-jdk25-runner-epyc7763-provenance.txt`
   (`benchmark`, `where`, `run`, `commit`, `date`, `inputs`, `jdk`, `kernel`, `cpu`, `datapath`,
   `pinned to`), read from the run's page and log. The four baseline files stay as they are: `git
   status --short` before the commit lists only the four new files and `M8/VARKA-332.md`,
   `M8/PLAN.md`, `sql/varka/plans/m10/SCOPE.md`. A run whose `cpu` line is not `AMD EPYC 7763
   64-Core Processor` is not committed; the lead dispatches again.
3. **Scoring.** Section 4's predictions are scored in a new subsection, the last of section 10, on
   the `Relative` column of each section's `varka on` line (the row path being `1.0X`), and on `Best
   Time(ms)` for the ratios. Quote, for each rule, every line it reads, from the new file, beside
   the same line of the baseline file. The section titles below are the class's, as the baseline
   file prints them (each comes twice: `, no nulls` and `, one row in ten null`; "both densities"
   means both):
   - prediction 1 holds if, at 256 bits, each of `INT CAST(i AS TINYINT), LEGACY`, `INT CAST(j AS
     TINYINT), ANSI`, `INT try_cast(k AS TINYINT)`, `INT CAST(k AS TINYINT), LEGACY`, `BIGINT
     CAST(l AS SMALLINT), LEGACY` and `BOOLEAN CAST(b AS INT)` reads 3.0X or more at both
     densities, and `INT try_cast(k AS TINYINT)`'s best time is at most 1.3 times `INT CAST(k AS
     TINYINT), LEGACY`'s, at both densities;
   - prediction 2 holds if, at 256 bits, `INTERVAL DAY TO SECOND CAST(dt AS BIGINT)`'s best time
     is at most 1.5 times `INT CAST(m AS INTERVAL YEAR)`'s, at both densities;
   - prediction 3, at 128 bits on the EPYC 7763 (the Arm half is section 10's "The measurement on
     Arm"), holds if each of the
     four `INT ... TINYINT` sections named in prediction 1 and `BOOLEAN CAST(b AS INT)` reads 2.0X
     or more at both densities, and `TINYINT CAST(y AS BIGINT)` reads 1.0X or more at both;
   - prediction 4 is scored by 332c's `dev/varka_bytes_moved.py` line (step 4 of part 332c), quoted.
   A section missing from the new file is a stop: report it.
4. `sql/varka/plans/m10/SCOPE.md` item 140 gains these three lines, inserted in its table in task
   order (after the last line whose task is below 332, before the first above it), each exactly:

       | 332 | 332's first gap of the review of 2 November 2026: a `BIGINT` narrowed to `TINYINT` or `SMALLINT` at the root of a long kernel, stored under a masked store at two long lanes or with no lane count baked, takes the Vector API's Java loop on AVX2 or declines with its reason | `m8/VARKA-332.md` 11.3 decision 4 | a decline or slow path with its reason, which item 3 of `m8/PLAN.md` 1.1 accepts |
       | 332 | 332's second gap of the review of 2 November 2026: `CAST(y AS BOOLEAN)` or `CAST(b AS TINYINT)` beside a `BIGINT` column declines the kernel, with its reason | `m8/VARKA-332.md` 11.3 decision 4 | a decline with its reason, which item 3 of `m8/PLAN.md` 1.1 accepts |
       | 332 | The speed of the integral-interval casts 332c fused, which no section of `VarkaIntegralCastBaselineBenchmark` times: a baseline of them on code before 332c, then the fused run | `m8/VARKA-332.md` 11.3 decision 6 | item 3 asks that the pairs fuse and are proven, not a speed |

   and its opening paragraph gains, at its end, the sentence "Row 332's three lines are parts of
   a row that closed in milestone 8 (`m8/VARKA-332.md` 11.7)." If owner question 1 (section 12)
   is answered (b), the first two lines are left out and the row stays open for them.
5. `PLAN.md`: row 332 opens with `**Done**`, naming what moved: the two review gaps and the new
   casts' measurement to `m10/SCOPE.md` item 140, the Spark-level fuzzer's cast productions to row
   352.

**Done when** the runner files are committed with their runs named, 10.7 holds the scored
predictions, `python3 dev/varka_plan_lint.py --offline` prints `0 finding(s)`, and the Build is
green.

### What only a JVM can settle, and the command for each

| claim | command | settles |
|---|---|---|
| 332c's pick meets context conflicts only | `git cherry-pick 8f8f2ec47ea`, then `git diff --name-only --diff-filter=U` | the list 11.4 step 2 resolves |
| the renderer's block of 332p renders the twenty checks as verified here | 11.5 step 3's `grep -c` lines after the regeneration | `38` and `16` |
| the slices fit the suite's budget | 11.6 step 3's run | the printed times, against the stop rule of 11.6 |
| the sweeps compile and print the counts of 11.6's table | 11.6 step 3's run | the 24 slice lines |
| `-Dvarka.sweep.filter` set through `Test/javaOptions` reaches the test JVM and leaves one sweep | 11.6 step 4's command | one line `: 65536 batches`, and `T`, which picks the shard rows |

### Risks of section 11

1. **332c's pick meets the 19 commits of `VarkaCoverageSuite.scala`.** Held by step 2's rule
   (master's side, the commit's rows added) and by the coverage suite, which fails on a row it
   cannot compile or render.
2. **A sweep's decline rule is too strict where the guard reads null slots.** Held by the `may`
   flag, which allows a decline only where a stored value is outside the interval, and by the
   assertion that a batch with every value inside is answered.
3. **The weekly job's budget.** Held by step 4's timed sweep before the shards are trusted.

## 12. Questions for the owner (28 November 2026)

*Rewritten in the fix round of 28 November 2026 (review 1, L6) to stand alone.*

1. **Do two known slow-or-declining cases of the integral casts move to milestone 10?** The
   review of 2 November 2026 found two cases that give correct answers but no fast kernel:
   (1) a `BIGINT` narrowed to `TINYINT` or `SMALLINT` at a kernel's output, inside a kernel that
   writes its output under a mask at two 64-bit lanes (AVX2 machines) or with no lane count fixed
   at emit time, runs the Vector API's slow Java fallback or declines to the row engine; (2)
   `CAST(y AS BOOLEAN)` or `CAST(b AS TINYINT)` in the same projection as a `BIGINT` column
   declines the whole kernel to Spark's row engine. Options: (a) move both to milestone 10's
   catalogue (`m10/SCOPE.md` item 140) and close row 332 without them; (b) build both in
   milestone 8 before row 332 closes. *Recommended: (a)*: both give Spark's answers, the
   milestone's done-when list (item 3 of `m8/PLAN.md` 1.1) accepts a decline that states its
   reason, and neither is on a machine or a query shape the project optimises for. **Default
   applied: (a)** (11.3 decision 4; part 332m writes the two lines).

## 13. The house rules that bind every part of section 11

Each is written in the file named; read it there.

- Results equal vanilla Spark's under both ANSI settings; a kernel never raises, a batch Spark
  raises on declines (section 0 item 3 of this plan; `sql/varka/VISION.md`).
- A new bounded lowering comes with its proof file, rendered from the code's constants:
  `sql/varka/AGENTS.md`, "House rules that bite here specifically"; 332p is that file's addition.
- Every exhaustive sweep is listed once, the way a job selects it: `dev/varka_sweeps.py`'s header.
- The option matrix must pass under every configuration: the paragraph of
  `sql/varka/skills/testing-and-debugging.md` that opens "A test that must hold under every
  configuration".
- A pull request under 1000 counted lines, reviewed by its author before it opens:
  `sql/varka/AGENTS.md`, "Pull requests"; branches, titles, who merges:
  `sql/varka/.agents/pull-requests.md`; the gate: `dev/varka_ops/AGENTS.md`.
- What not to do: do not edit a generated file by hand (`casts.tsv`, `casts_fused.tsv`,
  `census.tsv`, `coverage.json`, `emitted_bytes.json`, `casts.smt2`); do not run the 2^32 sweeps
  beside another heavy job; do not push or open a pull request (the lead does).

## 14. Stale against the tree, found while planning (28 November 2026)

1. **The row's note in `PLAN.md`** lists as left "332b's in-range value sets" and "the other
   interval casts": both are built on `varka-332-integral-casts` (11.1), not merged.
2. **Section 10 has two subsections numbered 10.2** ("The measurement on Arm" and "332b, the TRY
   narrowings"). History, kept as written; the new subsections are 10.4 (the branch's) to 10.7.
3. **Section 0 item 14 and question 1's context**: the sweep budget "is not in `PLAN.md` yet"; row
   330 is Done and its budget is in `PLAN.md` 10.3 item 5 (row 330's note).
4. **The triage of 27 November** (`m8-triage/part-1.md`, row 332) counts "six identity pairs
   unmarked": the census credits an identity as fused (`dev/varka_census.py`, `pair_credit`), so
   they are not a gap.
5. **10.3's "Still declining" list** is answered by 332c, which fuses every pair it names.
6. **The branch's conformance hunk** (found in the fix round of 28 November 2026). The branch
   edits a test `castRow`, one loop over every cast of a type; master has split it into one test
   a census target, `castTo(String to)` (`grep -c 'private void castTo(String to)'
   CT/varka/VarkaTypeConformance.java` prints `1`), so the hunk is applied by hand (11.4 step 2).
7. **The first 11.6** (found in the fix round): with 2^16-aligned batches an ANSI narrowing to
   `TINYINT` or `SMALLINT` answers no batch, so the sweep compared no ANSI answer; 11.6 now adds
   the in-range pass.
