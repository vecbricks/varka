# VARKA-344: The rounding functions

## 0. What exists now (admission check)

From master at `6e207c226fb` (30 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/`, `mathExpressions.scala`
meaning `expressions/mathExpressions.scala`), and from the plans of the rows it builds on:
`VARKA-341.md` (started, its section 5.1 the code as built), `VARKA-342.md` and `VARKA-343.md`
(planned). The JDK was read in `/home/max/proj/openjdk-build/jdk25` (`jdk-25-ga`; `src/` below).
The row's text is the milestone plan's 2.7, "The rounding functions (344)", and items 35 and 36
of `m9/SCOPE.md`.

1. **Every form declines today.** `floatingDecline` admits a floating expression only by an
   allowlist of class names - the six comparisons, `Greatest`, `Least`, `In`, `InSet`, `IsNaN`,
   `NaNvl`, the choices and the null tests (`VarkaNodeCompiler.java:338-349`) - and answers
   anything else touching a floating type "an operation over floating values that no floating
   lowering serves" (`:356`), so `rint`, `floor` and `ceil` over a double and `width_bucket`
   decline there. No family names `Floor`, `Ceil`, `Rint`, `Round`, `BRound`, `Truncate`,
   `Factorial` or `WidthBucket` (none of the class names occurs under the codegen tree), so the
   integral forms decline at the fallback.
2. **`floor` and `ceil` (`mathExpressions.scala:275-314`, `:601-640`).** The result is `BIGINT`
   for a `DOUBLE` or `BIGINT` operand and a decimal of scale 0 for a decimal one (`:277-282`,
   `:603-608`); the inputs are `TypeCollection(DoubleType, DecimalType, LongType)` (`:288-289`).
   Over `BIGINT` both are the identity (`:292`, `:304`). Over `DOUBLE` the generated code is
   `(long) Math.floor(c)` without ANSI (`:310`, `:636`) and `MathUtils.doubleToLong(Math.floor(c),
   ctx)` with it (`:305-308`, `:631-634`), the interpreted path the same (`:293-295`). Spark's
   `doubleToLong` (`sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/MathUtils.scala:
   133-139`) raises `ARITHMETIC_OVERFLOW` when the rounded value is not NaN and `< -2^63` or
   `>= 2^63`, and otherwise returns `value.toLong`, so:
   - **LEGACY** saturates at `Long.MIN_VALUE` and `Long.MAX_VALUE` (the infinities included) and
     gives 0 for NaN, Java's `(long)` cast.
   - **ANSI** raises at `2^63` itself, where the ANSI cast of `VARKA-343.md` 2.4 passes it and
     saturates; it passes `-2^63`, and it passes NaN to 0, never raising on it; the infinities
     raise. On the rounded value the passing window is `[-2^63, 2^63 - 1024]`, and because
     `-2^63` and `2^63` are integers and no double lies strictly between `2^63 - 1024` and `2^63`,
     the same window on the operand holds for `floor` and `ceil` alike.
   - The mode is `failOnError`, captured when the expression is built
     (`Ceil(child, failOnError = SQLConf.get.ansiEnabled)`, `:275`, `:601`), so the compiler reads
     it from the node, never from the session at compile time (Comet's bug #4124, milestone plan
     2.8). There is no `try_` form of any function of this row, so no TRY.
   - The two-argument forms are `RoundCeil` and `RoundFloor` over a decimal only (`:372-382`,
     `:675-685`): not this row.
3. **An integral or `FLOAT` operand of `floor` and `ceil` is cast to `DOUBLE`, except `BIGINT`.**
   The implicit cast to a `TypeCollection` takes the first member the input casts to
   (`analysis/TypeCoercion.scala:253-254`, `analysis/AnsiTypeCoercion.scala:217-218`), and `INT`,
   `SMALLINT`, `TINYINT` and `FLOAT` cast to `DOUBLE`, the first; `BIGINT` is accepted as it is.
   So `floor(i)` is `FLOOR(CAST(i AS DOUBLE))`, read from the rule; the first test confirms it.
4. **`rint`** is `UnaryMathExpression(math.rint, "ROUND")` with `funcName` `rint`
   (`mathExpressions.scala:871-875`): `java.lang.Math.rint` over a `DOUBLE`, a `DOUBLE` result,
   one answer in every mode (the codegen `:84-86`, the input type `:70`). Its doc names it the
   closest mathematical integer (`:857`); JDK 25's `StrictMath.rint` resolves a tie to even and
   keeps the sign of a zero (`src/java.base/share/classes/java/lang/StrictMath.java:498-520`).
5. **`round`, `bround` and `truncate` over an integral type** are `RoundBase` with `HALF_UP`,
   `HALF_EVEN` and `DOWN` (`mathExpressions.scala:1925-1938`, `:1965-1978`, `:2005-2023`). The
   result type is the operand's (`:1724`, `case t => t`). At a scale `>= 0` the codegen is
   `ev.value = ce.value`, the identity (`:1843-1845`); at a negative scale it is
   `new BigDecimal(v).setScale(scale, mode).intValue()` (or `byte`, `short`, `long`), the low bits
   of the exact rounded integer, without ANSI, and `intValueExact()` inside `withOverflowCode`,
   raising `ARITHMETIC_OVERFLOW`, with it (`:1828-1842`; the interpreted path `:1784-1807`). The
   mode is the node's `ansiEnabled`, captured at construction. `Truncate`'s own comment says DOWN
   never overflows (`:2008-2009`), since it cannot increase a magnitude.
6. **`factorial`** takes `INT` (`mathExpressions.scala:736`), gives `BIGINT` (`:738`), is null
   outside `[0, 20]` and otherwise a 21-entry table (`:687-715`, `:745-749`, `:753-760`).
7. **`width_bucket`** (`mathExpressions.scala:2025-2066`, `:2128-2192`): null when `numBucket <= 0`,
   `numBucket == Long.MaxValue`, the value is NaN, `min == max`, or either bound is NaN or
   infinite (`:2036-2043`); otherwise 0 below the range, `numBucket + 1` above it, and else
   `(numBucket.toDouble * (value - lower) / (upper - lower)).toLong + 1L`, left to right, with the
   comparisons mirrored when `min > max` (`:2044-2066`). The value and the bounds are `DOUBLE`, a
   year-month or day-time interval or a `TIME`, read through `Number.doubleValue()` (`:2163-2168`),
   `numBucket` a `BIGINT`.
8. **No rounding operator is reachable from the Vector API, on any host.** `VectorOperators`'
   unary operators (`src/jdk.incubator.vector/share/classes/jdk/incubator/vector/
   VectorOperators.java:451-542`) hold no rint, floor, ceil or round, and `VectorSupport.java` has
   no operation id for one. HotSpot has the vector node: `RoundDoubleModeV`, matched by
   `vroundpd` below eight lanes and `vrndscalepd` at eight on x86 (`src/hotspot/cpu/x86/
   x86.ad:3830-3874`, refused without AVX at `:1497-1501`) and by `frintn`, `frintm`, `frintp` on
   NEON and SVE (`src/hotspot/cpu/aarch64/aarch64_vector.ad:6525-6565`). But only SuperWord makes
   it, from the scalar `RoundDoubleMode` (`src/hotspot/share/opto/vectornode.cpp:163-164`), which
   C2 builds from the `Math.rint`, `floor` and `ceil` intrinsics
   (`src/hotspot/share/opto/library_call.cpp:1758-1760`, `:1907-1909`). A Varka kernel cannot
   reach it, AVX-512 included, so the construction of 2.3 is the form on every host.
9. **The bitwise operators refuse a floating lane.** `AND`, `OR` and `XOR` are `VO_NOFP`
   (`VectorOperators.java:562-571`), so "the sign restored by OR" of 2.7 needs
   `viewAsIntegralLanes`, a reinterpretation. `IS_NEGATIVE` is `VO_ALL` (`:642`), but
   `DoubleVector` implements it as a long compare of the reinterpreted bits (`DoubleVector.java:
   1835-1840`) whose mask then crosses to the double species: a same-count crossing between the
   kinds, which `VARKA-341.md` 0, item 5 allows only by `VectorMask.cast` and which 343a builds.
10. **The row engine's own `rint`, `floor` and `ceil` depend on the JIT tier for one input.**
    `Math.ceil`, `floor` and `rint` delegate to `StrictMath`'s Java (`src/java.base/share/classes/
    java/lang/Math.java:502`, `:526`, `:550`). Neither C1's intrinsic list
    (`src/hotspot/share/c1/c1_Compiler.cpp:111-176`) nor the interpreter's math entries
    (`src/hotspot/share/interpreter/abstractInterpreter.hpp:72-83`) has them, so the interpreter
    and C1 run that Java; C2 replaces them by `RoundDoubleMode` where the match rule exists,
    `roundsd` under SSE4.1 (`x86.ad:1491-1495`, `:3805`) and `frint*` on aarch64
    (`aarch64.ad:14241-14242`). `StrictMath.rint` takes `abs`, then returns `sign * a`
    (`StrictMath.java:509-519`): for a `-NaN` the multiply returns its NaN operand, whose sign the
    `abs` cleared, so the Java answers `+NaN`, while `roundsd` and `frintn` return the input NaN,
    `-NaN` **[I]**, derived from the source. That is the `rint(-NaN)` probe the milestone plan's
    2.7 and `SCOPE_STANDARD_MODE.md` 3.5 give this row. `floor` and `ceil` return `a` itself for
    NaN in the Java (`StrictMath.java:458-462`), so only `rint` has the dependence, and only in a
    NaN's sign, which the `BIGINT` results of `floor` and `ceil` never show.
11. **The census lacks most of the cells the constructions call.** `operator_census.json` and the
    two runners' files (`results/VARKA-376-census-epyc-7763.json`,
    `results/VARKA-376-census-neoverse-n2.json`) have no double `ADD`, `SUB`, `GT` or `GE`, no
    reinterpretation and no masked operation; 342 adds the first two (its item 0.7) and 343 the
    reinterpretations (its item 0.5). Of what they have: `double.D2L` is intrinsic at every width
    on the laptop and the Neoverse N2 and a fallback at 256 and 128 bits on the EPYC 7763 (the
    AVX512DQ rule, `VARKA-343.md` 0, item 5); the double compares are fallbacks at 128 bits on
    both x86 hosts (two lanes); `long.MUL` is intrinsic at every width on the laptop, and the EPYC
    file has no long cells.
12. **`ConstDivide` truncates and is bounded at the long lane.** It is Java's division by a
    constant, truncating toward zero (`varka/VarkaVectorIR.java:709`, `:723-730`), exact for
    every int32 dividend and, at the long lane, only for a dividend the caller has proven below
    `EXACT_DIVIDEND_BOUND`, `2^52` (`:735-764`, `:783`). A checked multiply is refused at the int
    and long lanes (`varka/VarkaVectorWalk.java:609-614`, `:661-663`), so no ANSI form here may
    rest on `IntArith(MUL, FAIL)`.
13. **343's settled AVX2 forms.** `VARKA-343.md` 8, question 1: on AVX2, `D2L` is the exponent
    assembly over the whole range by default and the truncated magic number, declining above
    `2^51`, is the reference arm of the option `wideConversionForm`; `convertsFallBack()`
    (`varka/VarkaEmitOptions.java:594-621`) is the host, already in the shape key. Its floating
    `GuardedRange` holds IEEE-inclusive bounds with NaN outside (its 2.4), and gains the `NULL`
    disposal in 343b.
14. **No baseline times any form.** Row 377's `VarkaFloatingBaselineBenchmark` times a
    comparison, a chain, a cast and a choice (`VarkaFloatingBaselineBenchmark.java:95-103`); the
    nearest is `CAST(x AS BIGINT)` at 39.8 ns a row on Spark's row path at 256 bits, no nulls
    (`VarkaFloatingBaselineBenchmark-jdk25-runner-epyc7763-results.txt`). Nothing times a rounding
    function, an integral `round`, `factorial` or `width_bucket`.
15. **The ULP contract does not reach this row's functions.** Every form here is exact: an
    integer-valued double is its own correct rounding, and the integral forms are integer
    arithmetic. The open question 2 of the milestone plan's section 7 is about a contracted value
    *under* one of them (the first item of the review's list of what the rows should not
    assume names `floor`, `ceil` and `round` among the discrete consumers), not about their own
    answers (2.9).

### What differs from the row text and the milestone plan

- **On AVX2 the whole range is the default, and "declining above `2^51`" is the reference arm.**
  The row predates `VARKA-343.md`'s answered question 1: there the exponent assembly is the
  default `D2L` and the magic number the option's reference arm. `floor` and `ceil` to `BIGINT`
  follow it (2.3): the construction and 343's assembly by default; the fused form the row
  describes - `rint`, the correction and the magic conversion - as the `MAGIC` arm, declining
  above `2^51`.
- **Two cores, not one.** `rint` needs the sign-restoring construction over `|x|` (window
  `|x| < 2^52`); `floor` and `ceil` to `BIGINT` never show a zero's sign and, on the `MAGIC` arm,
  take the single-add form over `x` (window `|x| < 2^51`).
- **The integral rounding is the signed-remainder form, not the add-then-floor form.** The row
  names Lemire, Bartlett and Kaser's section 7 (add `d / 2`, floor, subtract one when divisible
  and odd). That form is stated for an unsigned dividend, and its add overflows the int lane at
  `|x|` near `2^31`; the remainder of `ConstDivide`'s truncating quotient needs no add, no `abs`
  and no unsigned reading (2.5). It is proven on its own (3.5, 3.6), so it rests on no paper;
  the row's citation is the owner's text and stays as written (open point 2).
- **`BIGINT` at a small negative scale has a declined band** (2.5): `ConstDivide`'s `2^52` bound
  (item 0.12) holds after a pre-shift for `k >= 12`, and for `k <= 11` a guard declines the
  batch beyond it, the pattern of 30's `ConstDivide` bound and 332's `2^52` guard; the full-range
  form is a widening multiply by a constant, row 360's `WideMul` (361 adds the general
  column-by-column product).
- **`floor(i)` over an `INT` fuses without 343** (2.4): `FLOOR(CAST(i AS DOUBLE))` is `i`
  widened to the long, exactly, and is compiled as that. A `TINYINT` or `SMALLINT` operand takes
  the same form but needs the narrow column load in a `BIGINT` kernel, which row 332 builds (its
  plan, on branch `varka-332-plan`); 344 does not build it.
- **The ANSI guard of `floor` and `ceil` lets NaN through** (item 0.2), which 343's floating
  `GuardedRange` does not (item 0.13), so the guard reads the operand with NaN blended to `0.0`.
- **Large, in three parts, by what each part waits for** (section 5), against the row's medium.

## 1. Goal and non-goal

`rint` over a `DOUBLE`; `floor`, `ceil` and `ceiling` over `DOUBLE`, `BIGINT` and the narrower
integral types, giving `BIGINT`; `round`, `bround` and `truncate` over `TINYINT`, `SMALLINT`,
`INT` and `BIGINT` at every constant scale; `factorial`; and `width_bucket` over `DOUBLE` and,
after 343, over the interval types and `TIME` - each fused with the row engine's answer to the
bit in each mode, a lane Spark raises on declining its batch to the row engine. The
`rint(-NaN)` probe is run and recorded. Not this row: `round`, `bround` and `truncate` over
`FLOAT` and `DOUBLE` (346), every decimal form including the two-argument `floor` and `ceil`
(351, 363), the casts (343), `floor(f)` over a `FLOAT` until 343a's `F2D`.

## 2. Design

### 2.1 The premise

No host offers a lane rounding instruction through the Vector API (item 0.8), so the rounding is
built from IEEE addition and subtraction, which are correctly rounded in a lane exactly as in the
scalar code, and C2 neither reassociates them nor contracts them (`VARKA-342.md` 0, item 6). The
constructions below are exact by IEEE 754's rules, not within a bound; section 3 proves it.

### 2.2 The IR: one rounding node

**`RoundToIntegral(RoundMode mode, VarkaVectorIR child, LaneType result)`**, the child at the
`DOUBLE` lane; `mode` is `RINT`, `FLOOR` or `CEIL`; `result` is `DOUBLE` (an integer-valued double)
or `LONG` (Java's `(long)` of it). `rint` is `RINT` to `DOUBLE`; `floor` and `ceil` over a double
are `FLOOR` and `CEIL` to `LONG`. The constructor refuses `RINT` to `LONG`, which no function of
this row needs, and `FLOOR` or `CEIL` to `DOUBLE`, which has no reader until 346 (section 7).

Why a node, by `VARKA-475.md` 4 rule 1: rounding to an integral value is a new kind of
computation, not an existing one at a new type. The two alternatives are worse. Composing it from
342's `IntArith` at the floating lane, `Compare` and `IfElse` spreads about ten nodes per call
through the shape key, the budget and the fuzzers, and makes the reference evaluator check the
construction against itself instead of against `StrictMath`. Adding it to 342's `MathUnary` as a
`MathFn` misplaces it: `MathUnary` is a call of a lanewise math operator, which 345's contract
gates, and there is no rounding operator to call. And `ConvertLane` with a rounding parameter
would put a floating `rint` in a conversion node. One node keeps one meaning on every host and
leaves the lowering per host, as 343's `ConvertLane` does. Its lowering lives in a
`VarkaRoundingLowering` beside 343's `VarkaConversionLowering`, whose AVX2 `D2L` arms it calls.

### 2.3 The constructions, per host

With `C = 2^52`, `M = 0x1.8p52` (`1.5 * 2^52`), `MB = 0x4338000000000000` its bits, `S` the sign
bit, and `b` the operand's bits by `viewAsIntegralLanes` (343's reinterpretation cell):

**`RINT` to `DOUBLE`**, on every host:

    s  = b & S;  ab = b ^ s                    the sign, and |x| as bits
    a  = bitsToDouble(ab)
    r  = (a + C) - C                           |x| rounded to an integer, ties to even
    out = ab < bits(C) ? bitsToDouble(bits(r) | s) : x

The conditions, as 2.7 asks them written: for `0 <= a < 2^52`, `a + C` lies in `[2^52, 2^53]`,
where the spacing of doubles is 1, so the add rounds `a` to the nearest integer and a tie goes to
the even sum, which is the even integer since `C` is even; the subtraction is exact by Sterbenz's
lemma, `C / 2 <= a + C <= 2C`. A subnormal or zero `a` gives `+0.0`. At `a >= 2^52` every double
is an integer or not a number, so the keep arm returns `x` with its bits, which also catches NaN
and the infinities: their `ab` is above `bits(C)`, and `ab` is a non-negative long, so the signed
compare orders it as the value. The OR puts the sign back, so `rint(-0.3)` is `-0.0`, which an
absent OR would give as `+0.0`, and `rint(-0.0)` is `-0.0`. Every compare and pick is on the
long view, so no mask crosses between the kinds (item 0.9); about ten lane operations, two of
them floating.

**`FLOOR` and `CEIL` to `LONG`.** A zero's sign does not survive the conversion, so the forms
work on `x` directly:

- **AVX512DQ (laptop), NEON and SVE (Neoverse N2):** `r = RINT(x)`; for the floor `r - 1.0`
  where `r > x`, for the ceiling `r + 1.0` where `r < x`; then `convertShape(D2L)`.
- **AVX2 (EPYC 7763), `wideConversionForm = SPLIT`, the default:** the same `r` and correction,
  then 343's exponent assembly.
- **AVX2, `wideConversionForm = MAGIC`:** `t = x + M`; `r = t - M`; `n = bits(t) - MB`; for the
  floor `n - [r > x]`, for the ceiling `n + [r < x]`; under the guard `|x| < 2^51`.

The correction is exact: `r` is an integer of magnitude at most `2^52`, one more or less of which
is a double. `r > x` holds exactly when the nearest integer lies above `x`, so the floor is one
less; `r < x` the reverse. At `|x| >= 2^52` the keep arm gives `x`, already integral, so the
correction compares equal and the conversion saturates the infinities and sends NaN to 0, Java's
`(long)`: `convertShape` by HotSpot's fix-up and 343's assembly by its blend for an exponent past
62. On the `MAGIC` arm, for `|x| < 2^51` the sum `x + M` lies in `[2^52, 2^53)`, spacing 1, so it
is `M + rint(x)` exactly and its bits less `MB` are `rint(x)` as a long; `r` is exact by Sterbenz;
the correction's mask stays on the double lane by picking between the bit patterns 0 and 1 as
doubles (`0.0` and `Double.MIN_VALUE`, reinterpreted), so it too crosses no kind. That arm is five
operations against the default's roughly ten plus 343's assembly of about fifteen, which is what
the option measures (prediction 4.4).

`floor` and `ceil` to `LONG` also need the kernel to hold a `DOUBLE` child under a `LONG` root,
which `Analysis` refuses today and 343a's width rule admits (`VARKA-343.md` 0, item 3 and 2.2).

### 2.4 The modes, the guard and the integral shortcut

For `floor`, and `ceil` with `CEIL`:

- **A `DOUBLE` operand**, LEGACY: `RoundToIntegral(FLOOR, x, LONG)`. ANSI: the same over
  `GuardedRange(nanvl(x, 0.0), -2^63, 2^63 - 1024, FAIL)`.
- **A `DOUBLE` operand on the `MAGIC` arm**, both modes: the same over
  `GuardedRange(nanvl(x, 0.0), nextUp(-2^51), nextDown(2^51), FAIL)`.
- **A `BIGINT` operand**, both modes: the child.
- **`CAST(i AS DOUBLE)`, `i` an `INT`**, both modes: `WidenLane(i, LONG)`. The same for a
  `SMALLINT` or `TINYINT` `i` once row 332's narrow load in a `BIGINT` kernel exists.
- **`CAST(f AS DOUBLE)`, `f` a `FLOAT`**, after 343a: the `DOUBLE` form over
  `ConvertLane(f, DOUBLE)`.

`nanvl` is 341's `IfElse` over `x = NaN` (`VARKA-341.md` 5.1): a NaN lane becomes `0.0`, which
the guard admits and which rounds and converts to 0, Spark's answer in both modes, while 343's
guard alone would condemn it. The `MAGIC` guard's window lies inside ANSI's, so one guard serves
both modes there. The integral shortcut is exact: an `INT` converts to a double exactly, the
floor and ceiling of an integer are itself, and `doubleToLong` never raises inside the `INT`
range, so Spark's answer is the operand widened; the compiler matches the cast by its source type
and builds 330's existing `WidenLane`. `ceiling` is `ceil` by the builder (`CeilExpressionBuilder`,
`mathExpressions.scala:365-370`).

### 2.5 `round`, `bround` and `truncate` over the integral types

A scale `>= 0` compiles to the child (item 0.5). A scale `-k` rounds to a multiple of `d =
10^k`. On the operand's computing lane (the int lane for `TINYINT`, `SMALLINT` and `INT`, the long
lane for `BIGINT`):

    q0 = ConstDivide(x, d)                     truncating, so q0 has x's sign
    r  = x - q0 * d                            exact, |r| < d, r has x's sign
    h  = d / 2
    HALF_UP:   q = q0 + [r >= h] - [r <= -h]
    HALF_EVEN: q = q0 + [r > h  or (r == h  and q0 odd)]
                      - [r < -h or (r == -h and q0 odd)]
    DOWN:      q = q0
    out = q * d                                IntArith(MUL, WRAP)

Rounding half away from zero and half to even are symmetric in the sign, which is why the
remainder's sign picks the direction. The final multiply wraps, and its low bits are exactly
`BigDecimal.intValue()`'s (or `longValue()`'s), the low bits of the exact rounded integer, so
LEGACY needs nothing more; a `TINYINT` or `SMALLINT` result is `NarrowLane` of the int result,
the wrap `byteValue()` and `shortValue()` give. Every node is an existing one: `ConstDivide`,
`IntArith` in `WRAP`, `Compare`, `CondValue`, `BitOp(AND)` for the odd test.

**ANSI** cannot use a checked multiply (item 0.12), and needs none: the rounding is monotone in
`x`, so the inputs whose result leaves the type are two tails, and ANSI is the LEGACY form over
`GuardedRange(x, lo, hi, FAIL)`, with `lo` and `hi` per type, `k` and mode computed at compile
time from `BigDecimal` and pinned by the exhaustion. For `INT` at `k = 1` they are
`[-2147483644, 2147483644]` for `HALF_UP` and `[-2147483645, 2147483645]` for `HALF_EVEN`; at
`k` = 2, 4, 6, 8, 9 and 10 no `INT` overflows, so there is no guard. `DOWN` never needs one.

**The constants past the type.** For `INT`, `k >= 10` gives 0 for every operand; for `BIGINT`,
`k >= 20` gives 0. `BIGINT` at `k = 19`: `10^19` is not a long, so `q0 = 0`, `r = x`, `q` is
-1, 0 or 1 by the comparisons with `h = 5 * 10^18`, and `out` is `q` times `10^19` wrapped,
`-8446744073709551616`, which is `longValue()` of `+-10^19`; ANSI's guard declines every lane with
`q != 0`.

**`BIGINT` and `ConstDivide`'s bound.** The long-lane `ConstDivide` needs `|dividend| < 2^52`.
The route is the milestone plan's 2.8 for 350: divide `|x| >>> k` by `5^k`, which is floor
division by `10^k` of a magnitude, and keeps the dividend below `2^52` for every long when
`k >= 12`; the sign is restored after the rounding. For `k <= 11` the batch is guarded to
`|x| < 2^(52 + k)` - for `k = 1`, `2^53`, about nine thousand million million - and a lane past
it declines, the same pattern as 30's `ConstDivide` bound and 332's `2^52` guard. 344 builds the
pre-shift and its `5^k` entries in the long-lane proof file. The band goes only with an exact
full-range division, which needs a 64 by 64 to 128-bit product by a constant multiplier: row
360's `WideMul` with constant multipliers (its plan on branch `varka-360-361-plan`), row 361
adding the general column-by-column product, which this row does not need. Until then it is a
recorded decline.

### 2.6 `factorial`

`GuardedRange(i, 0, 20, NULL)` - 343b's `NULL` disposal, which clears the validity of a live lane
outside the bounds - widened to the long lane, then the table as a balanced tree of `IfElse` over
`Compare(LT)` against the 21 literals: depth five, so the critical path is five blends rather than
twenty, and every node exists. A gather from the table (`LongVector.fromArray` with an index map,
which takes the indices from an `int[]`, so a store and a `LoadVectorGather`) is the competing
form, built as an option arm, `factorialForm` (`TREE`, `GATHER`), by the rule to keep competing
designs behind options. The first step is a census cell for the gather at every width on the
laptop and the EPYC 7763; it decides the per-host default, `GATHER` only where the cell reads
intrinsic, since a fallback gather allocates per call. The losing arm stays and runs in the
option matrix.

### 2.7 `width_bucket`

With the value, the bounds and `numBucket` as Spark's `doubleValue()` gives them:

    nulls:  numBucket <= 0, numBucket == Long.MaxValue, isnan(v), min == max,
            min or max NaN or infinite                       the validity cleared (NULL)
    lo = least(min, max);  hi = greatest(min, max)           Math.min, Math.max over non-NaN
    min < max:  v < lo -> 0;  v >= hi -> n + 1;
                else ConvertLane(((double) n * (v - lo)) / (hi - lo), LONG) + 1
    min > max:  v > hi -> 0;  v <= lo -> n + 1;
                else ConvertLane(((double) n * (hi - v)) / (hi - lo), LONG) + 1

The arithmetic is 342's `IntArith` at the `DOUBLE` lane in Spark's order, multiply before divide;
the `+ 1` wraps as Spark's `toLong + 1L` does, so `Long.MAX_VALUE` from an infinite quotient
(`n * (v - lo)` overflowing, `hi - lo` finite) answers `Long.MIN_VALUE`, and `inf / inf` is NaN,
answering 1; both are cells (3.1). With literal bounds and `numBucket` - the common spelling - the
null conditions but `isnan(v)` fold at compile time. `Math.min` and `Math.max` differ from 341's
`least` and `greatest` only on NaN and on the zeros: NaN is nulled before, and `min == max` is
null, so a `-0.0` against `0.0` never reaches them. The interval and `TIME` forms take 343's
`I2D` (a year-month interval's months) or `L2D` (a day-time interval's microseconds, a `TIME`'s
nanoseconds), exactly `Number.doubleValue()`'s conversion; `numBucket` from a column takes `L2D`.
The conversion of the quotient sees a value in `[0, n]` or NaN, so on AVX2 343's default
assembly is whole and the `MAGIC` arm declines only for `n >= 2^51` and for a NaN quotient (the
`inf / inf` cell).

### 2.8 Compiler, reference, cost, census, warm-up

- A family `ROUNDING` in `varka/VarkaFamily.java` and its arm, `VarkaRoundingCompiler`, claiming
  `Rint`, `Floor`, `Ceil`, `Round`, `BRound`, `Truncate`, `Factorial` and `WidthBucket` by class
  and operand type; `floatingDecline` admits `Rint`, `Floor`, `Ceil` and `WidthBucket` by name,
  and keeps declining `Round`, `BRound` and `Truncate` over a floating operand, naming 346.
- `VarkaReference` evaluates `RoundToIntegral` by `StrictMath.rint`, `floor` and `ceil` and Java's
  `(long)` over the bits, never by the construction, so the reference is independent of the
  lowering it checks.
- The range analysis answers `UNKNOWN` for a node off the int lane (`VARKA-342.md` 2.5), so the
  new node and its long result are `UNKNOWN`; the compiler builds the integral forms' nodes
  directly, deriving `ConstDivide`'s stated bound from the guard it builds.
- Cost: a `VarkaEmitCostRegister` feature per lowering of 2.3, fitted as every feature is;
  `VarkaEmitBudget` weighs each by its operation count until the fit.
- The census cells (5, step 2): double `GT`, `GE` and the masked `ADD` and `SUB`, the long
  compares and `MUL` on the EPYC 7763, and the gather of 2.6, at every width each host runs;
  342's arithmetic cells and 343's reinterpretations are theirs, run here if not yet landed.
- The warm-up's double block gains the calls the constructions make (`VarkaWarmupReachSuite`).

### 2.9 The ULP contract and question 2

`rint`, `floor` and `ceil` are exact (2.3), the integral forms are integer arithmetic, and
`width_bucket` is Spark's IEEE operations in Spark's order, so every form is compared bit for bit,
a computed NaN by NaN-ness (`SCOPE_STANDARD_MODE.md` 3.5): `rint`'s NaN is computed, since its
sign is the implementation's (item 0.10). None of them is under the contract of 2.7.1, and the
configuration `spark.sql.codegen.varka.math.ulpContract.enabled` does not gate them.

*30 October 2026, by the owner's word:* that configuration is an opt-in mode, off by default, since
every math function is now bit-exact to Spark by default (`VARKA-345.md` 2.1, 2.6); it does not gate
these functions in either state. Question 2 is answered (a) for that mode only, in the form
`VARKA-345.md` 2.7 builds: a discrete consumer over a library node takes the function's exact form
rather than declining, so a rounding node over a math function compiles as over any exact child, and
this row's nodes still change in no byte.

The milestone plan's question 2, still open, is about a contracted value *under* these functions:
`floor(sin(x))` turns a one-step difference into a different `BIGINT`. Its option (a) would make
a rounding node over a contracted child decline. Until 345 lands no contracted node exists, so
this row is unaffected by the answer; whichever it is, the check sits where 345 builds the
contract, keyed on the consumer's kind, and this row's nodes change in no byte (open point 3).

## 3. Tests and proofs

The oracle is Spark's own `Floor`, `Ceil`, `Rint`, `Round`, `BRound`, `Truncate`, `Factorial` and
`WidthBucket`, through the reference evaluator and the differential suites, in both modes. A batch
declines exactly when Spark raises on one of its live rows.

1. **The cells**, a table test per mode against Spark's `eval`, the reference and the kernel:
   `rint(-0.3)` is `-0.0`; `rint(-0.0)`, `rint(0.5)` and `rint(-0.5)` keep the sign and give 0;
   `rint(1.5)` and `rint(2.5)` are 2; `rint` of `2^52 - 0.5` is `2^52` and of `2^52 - 1.5` is
   `2^52 - 2`; `2^52`, `2^52 + 1`, `Double.MAX_VALUE`, both infinities and three NaN payloads pass
   through; `Double.MIN_VALUE` and `-Double.MIN_VALUE` give `+0.0` and `-0.0`. `floor` and `ceil`
   of `+-0.3`, `+-0.5`, `+-0.7`, `+-1.5`, `-0.0`; of `+-(2^51 - 0.5)`, `+-2^51` and their
   neighbours (the `MAGIC` window's edge), `+-(2^52 - 0.5)`, `+-2^52`; of `2^63 - 1024` (passes
   ANSI), `2^63` (raises ANSI, saturates LEGACY), `-2^63` (passes both), `nextDown(-2^63)`
   (raises ANSI); of NaN (0 in both modes) and the infinities (saturate, raise). The integral
   cells of 2.5: `round(2147483645, -1)` gives -2147483646 under LEGACY and raises under ANSI,
   where `bround` gives 2147483640 in both; `round(5000000000000000000, -19)` gives
   -8446744073709551616 and `bround` of it 0, `bround` of one more -8446744073709551616;
   `Long.MIN_VALUE` and `Int.MIN_VALUE` at `k = 1`, `k = 10` and `k = 19`; every scale `>= 0` the
   identity. `factorial` of -1, 0, 20 and 21. `width_bucket` of Spark's four examples, both
   orientations, the four null conditions, `v` at `lo` and at `hi`, the infinite-quotient and
   NaN-quotient cells of 2.7.
2. **The SMT claim**, `sql/varka/proofs/fp_round.smt2` under Z3 and cvc5 by
   `dev/varka_prove.sh`, in the theory of floating point, which both decide completely: for every
   `x` not NaN, the construction of `RINT` equals `fp.roundToIntegral RNE x`, and the `r` and
   correction of `FLOOR` and `CEIL` equal `fp.roundToIntegral RTN x` and `RTP x`, signed zeros
   included (SMT-LIB's `=` separates them); NaN maps to NaN (its bits are 3.1's). Stated at
   `binary16` and `binary32` first, with the constant `2^(p - 1)` of the format, then attempted at
   `binary64` under a ten-minute bound. The solver time is priced from the spiked form, not the
   claim (VARKA-245's lesson in the calibration table). The `MAGIC` identity is an encoding claim
   over integers, with no floating theory: for `|x| <= 2^51` the bits of `M + rint(x)` are
   `MB + rint(x)`.
3. **The `binary32` twin, exhausted**: the same constructions in `float` with `2^23` and
   `0x1.8p23`, over all 2^32 bit patterns, against `(float) StrictMath.rint((double) f)` and its
   `floor` and `ceil`, which are exact for every float. A construction that is right at one
   precision by the argument of 2.3 is right at another, and this checks the argument on every
   input of a whole format. Run by hand in the gate's sweep step (below, item 5).
4. **The structured `binary64` sweep**, every exponent from the subnormals to `2^1023` in both
   signs, at each the band's ends, every half-integer tie and its neighbours below `2^52`,
   `k +- ulp` for integers `k` near the band's top, and a seeded draw per band, through every host
   form - the laptop's, and under `useAVX=2` both arms of `wideConversionForm` - against
   `Math.rint`, `floor`, `ceil` and `doubleToLong`.
5. **The integral rounding by exhaustion at 32 bits**, behind `-Dvarka.sweep=true`: every
   `INT` through `round`, `bround` and `truncate` at each `k` from 1 to 10, LEGACY's value and
   ANSI's raise in one pass, against Spark's own `Round`, `BRound` and `Truncate` - 30 sweeps;
   `TINYINT` and `SMALLINT` at every scale that is not constant, and `factorial` over every
   `INT`. No CI workflow runs a 2^32 sweep today: none sets `varka.sweep`, and a `-z` filter
   selects nothing in a JUnit suite. So these sweeps and 3.3's twin run in the gate's sweep step,
   by hand, until a row schedules them; the PR build runs the cells and the sampled regions only.
   ANSI throws only in 2.5's tails, a few hundred values a sweep, so Spark's `eval` runs on every
   input.
6. **The long lane by SMT**, in the long-lane proof file 2.8 names: the remainder form of 2.5
   equals `HALF_UP`, `HALF_EVEN` and `DOWN` of `x / 10^k` over integers for every `|x| < 2^63`
   and `k` from 1 to 19, the pre-shift's floor identity for `k >= 12`, and the guard bounds; the
   wrap stated by `mod 2^64`.
7. **The `rint(-NaN)` probe**: Spark's `Rint` over `-NaN` and a payload NaN, through `eval` and
   the generated code, under `-Xint`, `-XX:TieredStopAtLevel=1` and C2 after a warm-up, on the
   laptop, the EPYC 7763 and the Neoverse N2 (the census workflow's runners), recording the bits
   per tier in `results/VARKA-344-rint-nan-probe.txt`. It asserts NaN-ness only, as 3.5 has it.
   Prediction, from item 0.10: `+NaN` under the interpreter and C1, `-NaN` under C2, on both
   architectures.
8. **No folding**: `dev/varka_emit.sh --asm` over `rint(x)` shows the add and the subtract of `C`
   in the loop (Java's floating point forbids the fold; this shows C2 kept it), and no `vfmadd`.
9. **Coverage rows**, a family "rounding": `rint(x)`, `floor(x)` and `ceil(x)` as values and in a
   filter (`floor(x) > 3`), `ceiling(x)`, `floor(l)`, `floor(i)`, `round`, `bround` and
   `truncate` over `i`, `l` and a narrow column at scales 0, -1, -2 and -19, `factorial(i)`,
   `width_bucket` over `x` with literal and column bounds, in both modes; after 343, `floor(f)`,
   `floor(y)` over a `SMALLINT` (or after 332, whichever builds the narrow long load) and the
   interval and `TIME` forms of `width_bucket`.
10. **The fuzzers**: the floating IR sequence draws `RoundToIntegral`; the Spark fuzzer's grammar
    gains the functions over its floating and integral columns.
11. **Generated files** in `ADDING_AN_EXPRESSION.md`'s order, then the PR build and the option
    matrix dispatched with `useAVX=2` and `wideConversionForm`.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code:**
`VarkaRoundingBaselineBenchmark`, a class of its own for the family, over `rint(x)`, `floor(x)`
and `ceil(x)` in LEGACY and ANSI, `floor(i)`, `round(i, -2)`, `bround(i, -2)`, `truncate(l, -3)`,
`round(l, -3)` in ANSI, `factorial(i)` over values in `[-2, 22]`, and `width_bucket(x, lo, hi, 10)`
with literal bounds, with and without nulls, on the EPYC 7763 at 256 and 128 bits; its fuse check
behind a property, as 342 and 343 give theirs.

Predictions, registered before the build, measured on the runners and diffed against the
committed files:

1. **No existing shape's bytes move**; the `option_arms` digests move with the new shapes.
2. **The census**: the new double cells intrinsic at 512 and 256 bits on the laptop and at 256
   on the EPYC 7763, fallbacks at 128 bits on both x86 hosts, intrinsic on the Neoverse N2; the
   gather a fallback on the EPYC.
3. **Against the row path at 256 bits**: `rint` and the integral `round` at least four times
   faster; `floor` to `BIGINT` under ANSI on the EPYC's default arm at least three times faster
   than the row path, whose `CAST(x AS BIGINT)` is 39.8 ns a row
   (`VarkaFloatingBaselineBenchmark-jdk25-runner-epyc7763-results.txt`), a floor with its call no
   cheaper; `factorial` and `width_bucket` at least three times.
4. **The AVX2 arms**: the `MAGIC` arm faster than the default by 1.5 to 2.5 times on `floor` to
   `BIGINT`, by minimums; 343's rule keeps the whole-range arm the default unless it is more than
   1.3 times slower, so this prediction says that rule would pick `MAGIC` for this node. Both
   arms are built under 343's option either way; what follows from the number is section 7's.
5. **At 128 bits on x86** every floating form is the Java fallback (two lanes) and slower than
   the row path, recorded in `width_audit.json` as 341 records its compares; the integral forms
   at the int lane are at least twice as fast.
6. **Test time**, at the calibration table's observed rate: VARKA-335's unary 2^32 sweep took
   about 7 s on the laptop's cores; Spark's `BigDecimal` path is priced at four times that, about
   30 s, so the 30 sweeps of 3.5 take about 15 minutes in the gate's sweep step, an assumption
   the first sweep scores. The `binary32` twin's five sweeps, the construction in plain Java,
   about 7 s each. The SMT file's time is predicted after its spike (3.2). The coverage rows
   under 30 s, at `VARKA-336.md`'s ratio.

## 5. Order, files and size

1. **The baseline** (4), its own pull request.
2. **The census cells** (2.8), on the laptop and through the census workflow on both runners,
   the gather's cell among them, which sets `factorialForm`'s per-host default (2.6).
3. **344a, after 341 only**: `RoundToIntegral` with `RINT`, its lowering, the reference, the
   family; the integral rounding of 2.5 and `floor` and `ceil` over `BIGINT` and `INT` (2.4);
   tests 3.1 to 3.3 and 3.5 to 3.11 for these forms; the SMT spike first, then the file.
4. **344b, after 343b**: `FLOOR` and `CEIL` to `LONG` on the three host forms, the ANSI guard,
   the `MAGIC` arm under 343's option, 3.4's sweep; `factorial`'s two arms on 343b's `NULL`
   disposal.
5. **344c, after 342's same-lane forms, 343 and 332**: `width_bucket`, then its interval and
   `TIME` forms; `floor(f)` once 343a's `F2D` is in; `floor` and `ceil` over `SMALLINT` and
   `TINYINT` once 332's narrow load in a `BIGINT` kernel is in.
6. **The measurement** against the baseline at both widths, and the verdict on 4.4.

| form | needs | part |
|---|---|---|
| `rint(x)` | 341's lane; the reinterpretation cell | 344a |
| integral `round`, `bround`, `truncate` | existing nodes; the pre-shift, unless 350 | 344a |
| `floor`, `ceil` over `BIGINT`, `INT` | `WidenLane` | 344a |
| `floor`, `ceil` over `SMALLINT`, `TINYINT` | 332's narrow load in a `BIGINT` kernel | 344c |
| `floor`, `ceil` over `DOUBLE` | 343a's width rule (after 332); 343b's `D2L`, AVX2 arms | 344b |
| `factorial` | 343b's `NULL` disposal of `GuardedRange` | 344b |
| `width_bucket` over `DOUBLE` | 342's `SUB`, `MUL`, `DIV`; 343b's `D2L`, `L2D` | 344c |
| `width_bucket` over intervals, `TIME`; `floor(f)` | 343's `I2D`, `L2D`, `F2D` | 344c |

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `RoundToIntegral`, `RoundMode` |
| `varka/VarkaRoundingLowering.java` | 2.3's constructions per host |
| `varka/VarkaVectorWalk.java`, `Slots.java`, `Analysis.java` | the node's emission and slots |
| `VarkaRoundingCompiler.java`, `varka/VarkaFamily.java` | the family and its arm |
| `VarkaNodeCompiler.java` | `floatingDecline`'s names |
| `varka/VarkaEmitBudget.java`, `VarkaEmitCostRegister.java` | the rounding features |
| `sql/varka/proofs/fp_round.smt2`, the long-lane proof file, `VarkaProofFiles.java` | 3.2, 3.6 |
| tests | cells, twin, sweep, exhaustion, probe, coverage rows, fuzz grammar, reference |
| benchmark | the baseline class, its results files at both widths |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `operator_census.json`, `census.tsv` |

**Size: large, against the row's medium.** The baseline half a day; the census cells half a day;
344a about four days (the node and its lowering one, the integral rounding with its guards and
pre-shift one and a half, the SMT file and the twin one, the rows and generated files half);
344b about two and a half (three host forms, the guard, the arm, the sweep, `factorial`); 344c
about two. About ten days, in three pull requests after the baseline, each with its own
measurement against it (open point 1).

## 6. What it unlocks in the census

`census.tsv` names 344 for nine entries. When each fuses, by type:

- **`rint`** (`double`), all of it, in 344a.
- **`round`, `bround`, `truncate`**: `byte`, `short`, `int`, `bigint` in 344a; `float` and
  `double` are 346's, `dec` 351's and 363's.
- **`floor`, `ceil`, `ceiling`**: `int`, `bigint` in 344a, `double` in 344b, `byte`, `short`
  and `float` in 344c after 332 and 343a; `dec` is 351's and 363's.
- **`factorial`** (`int`) in 344b; **`width_bucket`**: `double` in 344c, `time`, `ym` and `dt`
  with 343's conversions.

The census marks an entry's type fused when its coverage rows pass in every mode; `rint` and
`factorial` become complete entries, and the others stay partial until 346, 351 and 363.

## 7. Out of this row, and declines that stay

Out: `round`, `bround` and `truncate` over `FLOAT` and `DOUBLE` (346); every decimal form,
`RoundFloor` and `RoundCeil` among them (351, 363); the casts (343); the narrow column load in a
`BIGINT` kernel (332); a `TRUNC` mode and a double-valued `FLOOR` or `CEIL`, which are row 346's,
added to `RoundToIntegral` when it needs them (the milestone plan's G2 row: "346 after 344 and
350"; `TRUNC` is `FLOOR` of `|x|` with the sign restored, the magnitude form of 2.3's `RINT`);
the hardware rounding a later JDK might expose (2.2).

**The `MAGIC` arm's default.** Both arms of `floor` and `ceil` to `BIGINT` are built under 343's
`wideConversionForm`, whose default 343 chose for one IR on every host. If prediction 4.4 holds,
343's 1.3 rule would pick `MAGIC` for this node while the whole-range arm stays `D2L`'s default;
a per-function default would split 343's one option in two. That is a question only once the
measurement exists, and this row keeps the option's one default until then.

Declines that stay: a batch with a live lane Spark raises on (ANSI's `floor` and `ceil` past the
long, an integral rounding past its type); on the `MAGIC` arm, a lane with `|x| >= 2^51`;
a `BIGINT` rounding at `k <= 11` with a lane at or past `2^(52 + k)`; at the forced 128-bit
species on x86, the floating forms' recorded refusals; until 343a, `floor(f)`, and `rint(i)` or
`rint(f)`, which arrive under a cast to double; until 332's narrow load in a `BIGINT` kernel,
`floor` and `ceil` over `SMALLINT` and `TINYINT`; until 343b, `floor` and `ceil` over a double
and `factorial`; until 342 and 343, `width_bucket`.

## 8. Open points for the lead

1. **The size: a question for the owner.** (a) Three pull requests as section 5 has them, each
   landing what its dependencies allow and the row **Done in part** between them, as 342 and 343
   land; (b) one pull request after 343b, 342 and 332. *Recommended: (a)*: 344a needs nothing
   unbuilt, and the integral rounding is most of the census movement. Either way the row is
   large, about ten days, against its medium.
2. **The Lemire, Bartlett and Kaser citation, as context.** The row text, the owner's, names
   their section 7 form for `bround`; the form planned is the signed remainder of 2.5, for the
   reason "What differs" gives, and is proven by 3.5 and 3.6 without the paper. The row is left
   as written; the owner may wish to know the citation describes a form not built.
3. **Question 2's check.** (a) 345 owns the decline of a discrete consumer over a contracted
   child, keyed on the node's kind, and this row only names its nodes as discrete consumers in
   their javadoc; (b) this row builds the hook now. *Recommended: (a)*: no contracted node exists
   until 345, and the hook's shape depends on the answer to question 2.

Settled with the lead on 30 October 2026, and so not questions: the `BIGINT` band at `k <= 11`
declines beyond its guard, the full-range form being row 360's constant-multiplier `WideMul`
(2.5); the
double-valued modes are 346's (section 7); `factorial`'s gather is an option arm whose per-host
default its census cell sets (2.6).

The baseline comes first, as its own pull request (5.1): not a question, since the house rule
settles it, as it did for rows 377, 342 and 343.

## 9. Done when

Row 344's text holds, with the corrections above: each form fuses with coverage rows in both
modes; the double forms are proven by 3.2's claims at `binary16` and `binary32` (and `binary64`
if it decides), exhausted through the `binary32` twin, and checked over every boundary region of
3.4 on every host form, `rint(-0.3)` giving `-0.0`; the integral ones by exhaustion at 32 bits
(3.5) and the long lane by 3.6; the `rint(-NaN)` probe recorded per tier and host; no existing
shape's bytes move; the PR build and the option matrix pass; the measurement is committed with its
predictions scored.

## 10. Outcome

(Written when the work lands.)
