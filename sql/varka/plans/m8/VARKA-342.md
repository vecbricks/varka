# VARKA-342: Floating arithmetic and the exact functions

## 0. What exists now (admission check)

From the code at `7ddd8f6715e` (27 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/`), and from
open PR #1065 (branch `varka-341-semantics`, the comparisons in Spark's order), on which this row
builds. The row's text is the milestone plan's 2.7, bullets 2 and 3, and item 35 of `m9/SCOPE.md`.

1. **The lanes are read; the ordering is #1065's.** `FLOAT` and `DOUBLE` are `OnLane`
   (`varka/VarkaTypes.java:320-325`), `Lane` has both members (`varka/Lane.java:82-90`), and a
   floating literal is its bits in the integral tables (`:348-357`). #1065 lowers `Compare` at a
   floating lane in Spark's order, NaN greatest and `-0.0 = 0.0` (its `varka/VarkaVectorWalk.java:
   1370-1483`), and `isnan` as `x = NaN` (its `VARKA-341.md` 5.1). Still owed by 341 there: the IR
   fuzzer's floating sequence, the warm-up blocks and the mask crossing between a double and a long
   mask (5.1, "Still owed").
2. **`floatingDecline` is an allowlist, and every form of this row is off it.**
   `VarkaNodeCompiler.java:331-352` declines `BinaryArithmetic`, `UnaryMinus`, `Abs` and the four
   interval classes as "floating arithmetic (row 342)", and `Signum`, `Sqrt`, `ToDegrees` and
   `ToRadians` by its default; #1065 admits the comparisons by class name (its `:338-349`). A
   `RuntimeReplaceable` passes first, so `UnaryPositive` and `try_divide` are asked again as their
   replacements.
3. **The IR has integral arithmetic only.** `IntOp` is `ADD, SUB, MUL` (`varka/VarkaVectorIR.java:
   395`), `Overflow` is `WRAP, FAIL, NULL` (`:415`), `IntArith` needs its operands on one lane
   (`:672`) and `IntNeg` carries an `abs` flag (`:701`). `emitIntArith` emits `WRAP` as the bare
   lanewise op through `lane.lanewiseVV` (`varka/VarkaVectorWalk.java:639-670`), which a
   `DoubleVector` has; its `FAIL` and `NULL` checks are sign tests over `lane.integral()`, and
   they exist only while `checkIntOverflow` is on (`varka/Slots.java:806`). The range analysis
   answers `UNKNOWN` for a node off the `INT` lane (`varka/VarkaRangeAnalysis.java:234`), before
   its switch reads the kind (`:305`). No node divides
   by a column at any lane (`ConstDivide`, `:764`, is a constant integer divide), and none calls a
   math operator.
4. **The interval arms are integral.** `ym * k` takes an int32 `k` (`VarkaIntervalCompiler.java:
   345-352`), `ym / k` an integral literal (`:385-398`); no arm claims `MultiplyDTInterval` or
   `DivideDTInterval`, and a floating factor never reaches either, `floatingDecline` asking first.
5. **What Spark computes.** No form below has a mode-dependent answer except the division.
   - `+`, `-`, `*` over `DOUBLE` and `FLOAT`: the Java operator in every mode
     (`arithmetic.scala:345-353`; ANSI checks only the integral types), so infinity and NaN are
     answers, never errors. `try_add`, `try_subtract` and `try_multiply` over numbers are the same
     classes in TRY (`TryEval.scala:94`, `:233`, `:272`), with the same answers.
   - `/`: `Divide` declares `DOUBLE` or `DECIMAL` only (`arithmetic.scala:880`), and the
     analyzer's `DivisionTypeCoercion` puts a `FLOAT`, `INT` or `BIGINT` operand under a cast to
     double (`../analysis/DivisionTypeCoercion.scala:32-33`, registered at `TypeCoercion.scala:67`
     and `AnsiTypeCoercion.scala:94` in the same directory). It is `DivModLike`: the divisor is
     zero by `x == 0` (`arithmetic.scala:689`), true for `-0.0` and false for NaN; under LEGACY
     and TRY a zero divisor gives null, under ANSI it raises `DIVIDE_BY_ZERO` unless the dividend
     is null, which gives null (`:701-728`); a divisor null gives null in every mode.
     `failOnError` is ANSI alone (`:878`), so `try_divide`, which is `Divide(..., TRY)` over
     numbers (`TryEval.scala:135-143`), is LEGACY's answer. A non-zero literal divisor drops the
     check (`arithmetic.scala:696`).
   - Negation is `-(x)` (`arithmetic.scala:76-83`) and `abs` is `Math.abs` (`:198`) in every mode.
   - `signum`, `sqrt`, `degrees`, `radians` are `UnaryMathExpression`s over `DOUBLE` only
     (`mathExpressions.scala:66-86`; `ImplicitCastInputTypes` at `:67` and `inputTypes` at `:70`
     put another numeric operand under a cast to double), generated as `java.lang.Math.<fn>`; the
     interpreted path's `scala.math` functions call the same `java.lang.Math` methods
     (scala-library 2.13.18, `pom.xml:182`; its sources' `scala/math/package.scala:126`, `:135`,
     `:208`, `:239`, `:293` and `Numeric.scala:173` delegate to `java.lang.Math`). `signum` also
     takes both interval types and answers a double (`mathExpressions.scala:893-899`).
   - JDK 25's `Math` (`src/java.base/share/classes/java/lang/Math.java` in the local source):
     `toRadians` and `toDegrees` are one multiply by a private constant (`:166`, `:172`,
     `:295-313`), `abs` clears the sign bit (`:2006-2008`), `signum` returns its operand for a zero
     or a NaN and `copySign(1.0, d)` otherwise (`:2624-2626`), `sqrt` is IEEE's (`:413`).
   - The interval forms: `ym * x` is `DoubleMath.roundToInt(m * (double) x, HALF_UP)` and `dt * x`
     `roundToLong` (`intervalExpressions.scala:649-650`, `:703-704`, the codegen the same);
     `ym / x` and `dt / x` divide instead, after raising `INTERVAL_DIVIDED_BY_ZERO` on `x == 0` in
     every mode (`:747-756`, `:794`, `:873`). None of the four has an evaluation mode. Guava
     33.6.0's `roundIntermediate` raises on NaN and the infinities and takes HALF_UP as `rint`
     plus a tie test (`DoubleMath.java:54-98`); `roundToInt` and `roundToLong` then raise an
     `ArithmeticException` when the rounded value leaves the int or the long (`:130-135`,
     `:157-162`).
6. **What the JIT does with them** (HotSpot source of `jdk-25-ga`, `src/hotspot/`):
   - **No contraction to FMA.** `FmaVD` and `FmaVF` are made only from `FmaD` and `FmaF`, that is
     from `Math.fma` or the Vector API's `FMA` (`share/opto/vectornode.cpp:83-86`); aarch64's
     multiply-accumulate rules match the integral vectors only (`cpu/aarch64/aarch64_vector.ad:
     2529-2533`), its `vfmla` matches `FmaV*` only (`:2579-2582`), and no x86 rule matches an
     `AddVD` over a `MulVD`.
   - **Negation and `abs` are bit operations on both engines.** `NegVD` and `AbsVD` are an XOR
     and an AND with the sign mask (`cpu/x86/x86.ad:8297-8298`,
     `cpu/x86/c2_MacroAssembler_x86.cpp:910-923`), as `Math.abs` and `dneg` are (`NegD` at
     `cpu/x86/x86.ad:3521`, `:3533` and `cpu/aarch64/aarch64.ad:14103`, `AbsD` at `:14164`;
     `AbsVD` and `NegVD` at `cpu/aarch64/aarch64_vector.ad:1862`, `:2035`).
   - **The row engine's NaN payload is not fixed.** C2 commutes a scalar floating add
     (`share/opto/addnode.cpp:555-558`, `:604-607`), so which payload `NaN1 + NaN2` returns in
     Spark depends on C2's ordering: 2.7's NaN rule, NaN-ness for a computed NaN, is the only
     comparison Spark itself would pass.
   - C2 replaces a scalar division by a power of two with a multiply by its reciprocal
     (`share/opto/divnode.cpp:995-1007`); that reciprocal is exact, so the row engine's answer is
     still the correctly rounded quotient.
7. **The census lacks the operators.** `operator_census.json` has no `ADD`, `SUB`, `MUL`, `DIV`,
   `NEG` or `ABS` cell for either floating species. `double.SQRT` is intrinsic at every width on
   the laptop and on the EPYC 7763 (`results/VARKA-376-census-epyc-7763.json`); the double
   compares are fallbacks at 128 bits on both, so a zero test there is too; `double.D2I` is
   intrinsic and `double.D2L` a fallback at 128 and 256 bits on the EPYC.
8. **Of this row's forms, the baseline covers only the chain.** Row 377's
   `VarkaFloatingBaselineBenchmark` times a comparison, `(x + y) * z - x`, a cast and a choice
   (`VarkaFloatingBaselineBenchmark.java:93-102`); the chain is 57.9 ns a row for `DOUBLE` and
   57.3 for `FLOAT` on Spark's row path at 256 bits with no nulls
   (`VarkaFloatingBaselineBenchmark-jdk25-runner-epyc7763-results.txt` in
   `sql/core/benchmarks/`). Nothing times `/`, the unary forms or the interval forms, and the class
   throws when a section fuses (`VarkaFloatingBaselineBenchmark.java:131-137`), which its
   comparison and choice sections do once #1065 merges.
9. **The conformance suites cancel the arithmetic naming this row.** #1065's
   `VarkaDoubleConformanceSuite` and `VarkaFloatConformanceSuite` cancel the null-handling
   `x + y` and the overflow check (`VarkaTypeConformance.java:172-187`, `:236-261` on its branch)
   naming the census rows, 342 among them.

### What differs from the row text and the milestone plan

- **Most of the division and the functions over a non-`DOUBLE` column are 343's casts.** `i / j`,
  `f / g`, `sqrt(f)`, `signum(i)` and their kin are this row's nodes over casts to double (item
  0.5), and the casts cross the kinds or the lane count, which 341's 2.4 left to 343. This adds
  an edge the milestone plan lacked: the foundations table (4.1, F2) lists 343 before 342 but
  sets 343 beside 341 and 342 under 356 to 358 only, and the older wave table (4, wave 3) lists
  342 before 343. F2's text now carries it, in its own "under" sense ("343's conversions under
  342's cast and interval forms"). This row fuses them the day 343's conversions land (question 1).
- **The interval forms need 343's conversions, not 344's `rint`.** HALF_UP is built from a
  truncation and a half-step test (2.4), which 343's `D2I` and `D2L` give.
- **Three modes, two lowerings.** `try_divide` is LEGACY's division, and no other form has a mode.
- **"Bit-identical" is read through 2.7's NaN rule**: bit for bit everywhere, a computed NaN by
  NaN-ness (item 0.6). Negation, `abs` and `signum` keep their NaN's bits on both engines and are
  compared bit for bit.

## 1. Goal and non-goal

`+`, `-`, `*`, negation and `abs` over `DOUBLE` and `FLOAT`, and `/`, `try_divide`, `sqrt`,
`signum`, `degrees` and `radians` over `DOUBLE`, fuse with the row engine's bits in every mode; a
zero divisor gives null under LEGACY and TRY, and under ANSI the batch goes to the row engine,
which raises `DIVIDE_BY_ZERO`. After 343: the same forms over casts to double, and the interval
products and quotients by a floating factor. Not this row: `%`, `pmod` (346), the rounding
functions (344), the math family (345 to 358), the casts themselves (343).

## 2. Design

### 2.1 Operators, not nodes

By `VARKA-475.md` 4 rule 1, the forms take existing nodes at a floating lane:

- `IntArith(ADD | SUB | MUL, WRAP)` at `FLOAT` and `DOUBLE`, the bare lanewise op. `FAIL` and
  `NULL` are refused there: no floating `+`, `-` or `*` has a check.
- **`IntOp.DIV`**, admitted at a floating lane only (the constructor refuses it at an integral
  one, which row 30 may lift), with the mode meaning the zero divisor's disposal (2.2).
- `IntNeg(WRAP, x)` and `IntNeg(WRAP, x, abs)` at a floating lane: the lanewise `NEG` and `ABS`.
- **One new node, `MathUnary(MathFn fn, child)`**, with `MathFn.SQRT` its first member: a call of
  a lanewise math operator is a new kind of computation, and 356 to 358 add their functions to it.
- `GuardedRange` at a floating lane (2.4) holds its bounds as the lane's bits and compares by
  IEEE, a NaN lane being outside.

Two rules hold at a floating lane and are written into `IntArith`'s javadoc: **no algebraic
rewrite** (`x + 0.0` is not `x` at `-0.0`, which is what 337's canonicalisation node relies on;
`x * 0.0` is not `0.0` at NaN or infinity; `x - x` is not `0.0`), and **operands keep Spark's
order**. `checkIntOverflow` reaches no floating node: its switch is about integer overflow, and
the zero test, like a `GuardedRange`, is unconditional.

### 2.2 The division and its zero divisor

| divisor | LEGACY, TRY | ANSI |
|---|---|---|
| a column | `DIV NULL`: a lane with `y == 0.0` null | `DIV FAIL`: a live one condemns the batch |
| non-zero literal | `DIV WRAP` | `DIV WRAP` |
| literal zero, either sign | declines by name | declines by name |

The test is the IEEE `compare(EQ, 0.0)`, which is Spark's `x == 0`: both zeros, never NaN. The
`FAIL` mask is ANDed with the node's word, the operands' validity, so a null dividend over a zero
divisor is null under ANSI as in `DivModLike.eval`. Only the divisor is parked for the check,
since the test reads neither the dividend nor the quotient. A division under a `CASE` or `IF` arm
has its guard qualified by the arm (`guardUnderArm`, on by default,
`varka/VarkaEmitOption.java:400`), so `IF(y = 0, 0.0D, x / y)` fuses under ANSI over batches
holding zeros. The commonest spelling, with a `NULL` arm or a `CASE` without `ELSE`, needs the
typed null leaf of row 333 and fuses when it lands. A division on the right of an `AND` still
condemns from the left's rejected rows, which is `m9/SCOPE.md` item 96.

**Never a reciprocal.** `x / c` is a lanewise `DIV` by the broadcast `c`. A multiply by `1 / c`
is exact when `c` is a power of two, as C2's own rewrite is (item 0.6); it is not taken, the
division being cheap beside the row path and the rule kept without an exception.

### 2.3 The exact functions

- **`sqrt(x)`**: `MathUnary(SQRT, x)`, the lanewise `SQRT`, correctly rounded as `Math.sqrt` is;
  `sqrt(-0.0)` is `-0.0` and a negative `x` NaN on both.
- **`degrees(x)`, `radians(x)`**: `IntArith(MUL, WRAP, x, k)`, `k` the bits of
  `Math.toDegrees(1.0)` or `Math.toRadians(1.0)` read at compile time, never retyped digits: one
  multiply by the JDK's own constant (`Math.java:172`, `:166`). Exact while the JDK's function is
  one multiply, which a test pins (3.3).
- **`signum(x)`**: `IfElse(x = 0.0 OR isnan(x), x, IfElse(x < 0.0, -1.0, 1.0))`, in #1065's
  order: `x = 0.0` holds at both zeros, `x < 0.0` never at NaN. The naive `x > 0.0` first would
  answer `1.0` for NaN, which Spark's order puts above every number. The picks move bits, so a
  zero keeps its sign and a NaN its payload, as `Math.signum` returns its operand.
  *Corrected 30 October 2026 (5.1):* this form answers `1.0` for a null `x`, since an unknown
  condition takes the else arm and the inner else is a valid literal; the built form ends in `x`
  itself, so a null answers null.
- **Negation and `abs`**: `IntNeg`, the sign bit flipped or cleared, NaN included.

### 2.4 The interval products and quotients (after 343)

With `m` the interval's month or microsecond count and `x` the factor (a `FLOAT` widened by
343's exact `F2D`):

    p = IntArith(MUL, WRAP, toDouble(m), x)        or  IntArith(DIV, FAIL, toDouble(m), x)
    g = GuardedRange(p, lo, hi)                    every mode: Spark raises outside
    t = toIntegral(g)                              truncation, exact inside the guard
    f = IntArith(SUB, WRAP, g, toDouble(t))        exact: a double's fractional part is one
    r = t + IfElse(f >= 0.5, 1, IfElse(f <= -0.5, -1, 0))

`toDouble` and `toIntegral` are 343's `I2D` and `D2I` for a year-month interval and `L2D` and
`D2L` for a day-time one. The division's `FAIL` is unconditional here, since Spark raises on a
zero factor in every mode. The bounds are the doubles whose HALF_UP lands in range: for the int,
`(-2^31 - 0.5, 2^31 - 0.5)` exclusive, both ends exact doubles; for the long, `[-2^63, 2^63)`,
both ends integers, so no tie sits at either. NaN and the infinities fall outside, so every lane
Guava raises on condemns the batch, and the row engine raises its `ArithmeticException`. The tie,
`|f| = 0.5`, moves away from zero as Guava's `x + copySign(0.5, x)` does, and `f = 0` for every
`|p| >= 2^52`.
On a host where 343 builds `D2L` and `L2D` by the magic number below `2^51` (AVX2), a day-time
lane past that window, about 71 years, also declines; with AVX512DQ the form is whole.
`signum` over an interval is `IfElse` over the interval lane's compares with a double result,
built in the same step, since it needs the same crossing.

### 2.5 Compiler, reference, cost, census

- A family `FLOATING_ARITHMETIC` in `varka/VarkaFamily.java`, its arm in a new
  `VarkaFloatingCompiler` claiming by class and type; `floatingDecline` admits `Add`, `Subtract`,
  `Multiply`, `Divide`, `UnaryMinus`, `Abs`, `Signum`, `Sqrt`, `ToDegrees` and `ToRadians` by
  name, and the interval classes in 2.4's step. `Remainder` and `Pmod` keep declining, naming 346.
- `VarkaReference` evaluates the floating `IntArith`, `IntNeg`, `MathUnary` and the floating
  `GuardedRange` over bits with Java's operators, and the DIV modes as 2.2's table.
- `Analysis` and `Slots`: a `DIV` in `FAIL` takes the refusals of a checked node and its parked
  divisor; `NULL` marks the node as one that nulls valid inputs.
- The range analysis answers `UNKNOWN` for any node off the `INT` lane before its kind switch
  (`varka/VarkaRangeAnalysis.java:234`, item 0.3), so a node on a floating lane is `UNKNOWN`
  whatever the kind; and the family builds its nodes directly,
  never through `arithOver`, whose `cannotOverflow` would read a double's bits as an int range.
- Cost: `DIV` and `SQRT` at a floating lane are features of `VarkaEmitCostRegister`, fitted as
  every feature is; `VarkaEmitBudget` weighs them as a checked node until the fit.
  *Corrected 30 October 2026 (5.1, the review):* a weight is not a placeholder for the cost
  model. `VarkaEmitterBudgetSuite`'s register (VARKA-254) holds every node's weight equal to the
  lane calls it emits alone, and a checked integral node weighs 5 because it emits its op and the
  four of its sign test. The floating division emits its divide, and under `FAIL` and `NULL` the
  zero test's compare, so it weighs 1 and 2; `MathUnary` emits one call and weighs 1.
- The operator census gains the six missing cells per floating species (item 0.7); the warm-up
  blocks of 341's owed list gain the operators this row calls.

## 3. Tests and oracles

The oracle is Spark's own evaluation, through the reference evaluator and the differential
suites, compared bit for bit except a computed NaN, compared by NaN-ness (2.7's rule).

1. **The special cells**, a table test in each mode and at both kinds where the form exists,
   against Spark's `eval`, the reference and the kernel: 2.7's cells (`0.0 * -1.0` is `-0.0`,
   `-0.0 + 0.0` is `+0.0`, `-0.0 - 0.0` is `-0.0`, `sqrt(-0.0)` is `-0.0`, `inf - inf`, `0 * inf`
   and `inf / inf` NaN); `1.0 / -0.0`, `0.0 / 0.0` and `NaN / 0.0` null or raised, never an
   infinity or a NaN; `x / NaN` NaN; `-1.0 / inf` `-0.0`; `MAX * 2` infinity in ANSI too;
   `MIN_NORMAL / 2` subnormal and `MIN_VALUE / 2` zero (no flush to zero); `degrees(MAX)`
   infinity; `radians(MIN_VALUE)` zero; `signum` of both zeros, both infinities and three NaNs
   (bits kept); negation and `abs` of three NaNs (bits).
2. **No contraction**: `(x * y) + z` at `x = 1 + 2^-27`, `y = 1 - 2^-27`, `z = -1` is `0.0`, where
   an FMA gives `-2^-54`; and `dev/varka_emit.sh --asm` over it shows no `vfmadd` in the loop.
3. **The constants**: `Math.toDegrees(x)` and `Math.toRadians(x)` equal `x * k` bit for bit over
   the sampled regions of 3.5, so a JDK that changes either formula fails a test, not an answer.
4. **Exhaustion at 32 bits**: negation and `abs` over all 2^32 `FLOAT` patterns against Spark's
   `UnaryMinus` and `Abs`, in the gate; `+`, `-` and `*` over every `FLOAT` pattern against each
   of 16 operands - three NaNs (quiet, negative, a payload), both zeros, both infinities, the
   extremes, the smallest subnormal and the smallest normal in both signs, three normals -
   nightly.
5. **Sampled `DOUBLE` regions** for every form: each special value, the subnormal range and its
   edges, `MIN_NORMAL` and its neighbours, the overflow thresholds (`MAX / 2`, `sqrt(MAX)`,
   `degrees`' own), every widened `FLOAT` pattern for the unary forms, and a seeded draw of
   random bit patterns per exponent band; pairs over the special set's cross product and a draw
   per exponent difference, where the subtraction cancels.
6. **The division**: the four null patterns against zero, `-0.0`, NaN and normal divisors in the
   three modes; ANSI's raise through the declined batch with `DIVIDE_BY_ZERO`; `IF(y = 0, 0.0D, x
   / y)` and `CASE WHEN y <> 0 THEN x / y ELSE 0.0D END` fused under ANSI over a batch holding
   zeros, and their `NULL`-arm spellings once row 333 lands; a
   literal zero declined; a non-zero literal with no zero test (`dev/varka_emit.sh` op counts).
7. **The interval forms** (2.4's step): the rounding stated as an SMT claim in the floating-point
   theory, `sql/varka/proofs/fp_half_up.smt2`, that `t + bump` equals `roundIntermediate`'s
   HALF_UP for every double inside each guard, under Z3 and cvc5; and pinned cells: ties at
   `+-0.5`, `+-2.5` and `2^31 - 1.5`, the guard's ends and their neighbours, a NaN and an infinite
   factor, a `-0.0` divisor raising, `2^51` either side on AVX2.
8. **Conformance**: the census marking the forms fused un-cancels the two suites' null-handling
   `x + y` and overflow check (item 0.9); a floating "overflow" reaches infinity, which Spark
   answers in every mode, so each form fuses and no batch declines there.
9. **Coverage rows**, a family "floating arithmetic": each form over `x`, `x2` and over `f`, `f2`
   where it exists, a literal on either side, `try_divide`, `try_add` and the chain of the
   baseline, through the differential suite at every position; after 343, `i / j`, `f / g`,
   `sqrt(i)` and the four interval forms.
10. **The fuzzers**: the floating IR sequence (341's, built here if it has not landed) draws the
    new nodes; the Spark fuzzer's grammar gains them over its floating columns.
11. **Generated files** in `ADDING_AN_EXPRESSION.md`'s order (`coverage.json`, the docs tables,
    `width_audit.json`, `emitted_bytes.json`, `census.tsv`'s fused column), then the PR build and
    the dispatched option matrix.

## 4. Predictions and costs

The measurement reruns the baseline classes (5, step 1) with the fuse check lifted, on the
runners' EPYC 7763 at 256 and 128 bits, diffed against the committed files.

1. **No existing shape's bytes move**; `option_arms` digests move with the new shapes.
2. **The census**: the six new cells intrinsic at every width on the laptop and the EPYC; the
   column-divisor zero test at 128 bits is the double compare's fallback, recorded in
   `width_audit.json` as a refusal.
3. **The chain** at least four times faster than the row path at 256 bits, against 57.9 and 57.3
   ns a row, and at least twice at 128 bits: three loads, three ops and a store.
4. **A column division and `sqrt`** at least three times faster at 256 bits; at 128 bits the
   division within 20% either way, the fallback zero test weighing on it.
5. **Test time**: the two `FLOAT` exhaustions under 30 s together in the gate, at `VARKA-335.md`'s
   measured 7 s for a unary 2^32 sweep (the calibration table's row, ratio taken as 1); the
   nightly binary ones about 48 such sweeps, under 10 minutes; the coverage rows under 30 s, at
   `VARKA-336.md`'s ratio; each SMT claim under 60 s (no multiplication in it).

Cost: medium, as the row says. About two days for the same-lane forms after the baseline, and
two for the cast and interval forms after 343.

## 5. Order and files

1. **The baseline**, its own pull request on the unchanged code:
   `VarkaFloatingArithmeticBaselineBenchmark`, over `x / y`, `x / 3.0D`, `try_divide(x, y)`,
   `sqrt(abs(x))`, `-x`, `signum(x)`, `degrees(x)`, `ym * x` and `dt / x`, with and without nulls,
   `/` in LEGACY and ANSI; its fuse check per section, against each class's list of the sections
   a row has fused, which 377's class gains too, and lifted by a property for the measurement;
   its results files at both widths from the EPYC runners, committed before it merges.
2. **The census cells** (2.5), on the laptop and through the census workflow on both runners.
3. **The same-lane forms**: the IR and emitter (2.1), the division (2.2), the functions (2.3), the
   reference, the compiler family, tests 3.1 to 3.6 and 3.8 to 3.11.
4. **After 343**: the cast forms' rows, the interval forms and interval `signum` (2.4), test 3.7.
5. **The measurement** and its verdict.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `IntOp.DIV`, the floating rules of 2.1, `MathUnary` |
| `varka/VarkaVectorWalk.java`, `Slots.java`, `Analysis.java` | the floating arms, the zero test |
| `VarkaFloatingCompiler`, `varka/VarkaFamily.java` | the family and its arm |
| `VarkaNodeCompiler.java` | `floatingDecline`'s names |
| `VarkaIntervalCompiler.java` | the floating factor (step 4) |
| `varka/VarkaEmitBudget.java`, `VarkaEmitCostRegister.java` | the `DIV` and `SQRT` features |
| `sql/varka/proofs/fp_half_up.smt2` | 3.7's claim |
| tests | the cells, the sweeps, the division, the coverage rows, the fuzz grammar, the reference |
| benchmark | the baseline class, its results files at both widths from the EPYC runners |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `operator_census.json`, `census.tsv` |

### 5.1 Step 3 as built (30 October 2026)

Where the code departs from 2.1 to 2.5, and what is still owed:

- **`signum` is ordered so that a null answers null.** 2.3's form, `IfElse(x = 0.0 OR isnan(x),
  x, IfElse(x < 0.0, -1.0, 1.0))`, answers `1.0` for a null `x`: an unknown condition takes the
  else arm, and the inner else is a valid literal. The built form is `IfElse(x < 0.0, -1.0,
  IfElse(x > 0.0 AND NOT (x = NaN), 1.0, x))`: its last arm is `x`, so a null, both zeros and
  every NaN answer themselves, bits kept, and `x > 0.0` is excluded at NaN, which Spark's order
  puts above every number.
- **The zero test parks nothing.** 2.5 has a `FAIL` division park its divisor; the emitter keeps
  it under the quotient instead (`dup_x1`, the divide, `swap`), and the compare consumes the
  copy. A `FAIL` division declines with a refusal of its own, `DIVIDE_BY_ZERO`, and the kernel
  status bit `STATUS_DIVIDE_BY_ZERO`, never behind `checkIntOverflow`.
- **`DIV WRAP` takes any divisor**, a column or a computed value as well as a non-zero literal,
  with the IEEE quotient on a zero divisor (an infinity or a NaN): the form rows 356 to 358's
  compositions need (`1 / sin(x)`, `log(b) / log(x)`), which divide by a computed value with no
  test. Only Spark's `Divide` over a computed divisor takes `NULL` or `FAIL`.
- **Cost**: `VarkaEmitBudget` weighs a floating division 1 under `WRAP` and 2 under `FAIL` and
  `NULL`, and `MathUnary` 1: the lane calls each emits, the rule `VarkaEmitterBudgetSuite`'s
  register holds every node to (2.5's correction; this bullet said 5, as a checked node, which the
  code never weighed). `VarkaEmitCostTable` has no price for the new features, so a group holding
  one falls back to the budget and the byte budget's measurement of the built class, as an
  unpriced feature does.
- **Tests**: `VarkaEmitterFloatingArithSuite` holds the IR at both lanes and widths to the
  reference, the zero divisor in each mode, the compiled forms against Spark's `eval` in LEGACY,
  ANSI and TRY over 3.1's cells and the special values' cross product (a NaN computed from two
  NaN operands by NaN-ness), the guarded divisions under ANSI, the literal zero's decline, the
  contraction case of 3.2, the two constants of 3.3, the negation and `abs` of every `FLOAT`
  pattern, and the binary `FLOAT` sweeps of 3.4 behind `-Dvarka.sweep=true`.
  `VarkaFloatingLaneSuite` runs the forms end to end over its fixtures in both modes, and ANSI's
  `DIVIDE_BY_ZERO` through the declined batch. The coverage table gains a family, "Floating
  arithmetic", of 26 rows.
- **The NaN rule reaches the shared harness, at the nodes Java leaves open.** Java fixes no NaN
  payload for an operation over two NaN operands of different bits, and HotSpot's answer depends on
  the tier and the operator: the x86 interpreter's `dadd` and `dmul` return the right operand's
  payload (`addsd xmm0, [rsp]`, with the right operand in `xmm0`) and its `dsub` and `ddiv` the
  left's (`cpu/x86/templateTable_x86.cpp`, `TemplateTable::dop2` in the local `jdk-25-ga`
  source), and C2 commutes a scalar add or multiply (0.6). In `VarkaDoubleConformanceSuite`,
  `NaN + (-NaN)` gave the first operand's payload in the kernel and Spark and the second's in the
  reference evaluator, which ran interpreted. So the reference keeps Java's operators, and
  `VarkaKernelChecks.sameAnswer` compares two NaN answers by NaN-ness only where the row's answer
  descends from such a node (`unfixedNaN`): a floating `IntArith` over two unlike NaNs, or a
  negation, square root or pick that passes its payload on. An operation over one NaN operand
  returns it, quieted, on every tier, and is compared bit for bit, as are the picks, negation and
  `abs` everywhere else. `VarkaExpressionHarness` holds a kernel to Spark's projection by the
  same rule, per output and on the Catalyst expression; it once excused every output of a row
  with two unlike NaN inputs, a wrong pick among them. `VarkaEmitterFloatingArithSuite` and
  `VarkaFloatingLaneSuite` hold their own comparisons to the same line: a row or column of two
  unlike NaN inputs alone.
- **A shared guarded node keeps every arm it sits under.** Under ANSI, `IF(x2 = 0.0D, 0.0D, x1 /
  x2)` and `CASE WHEN x2 <> 0.0D THEN x1 / x2 ELSE x1 END` each fuse over batches holding zero
  divisors, but the two in one projection declined every batch of `VarkaFloatingLaneSuite`'s pairs
  fixture. The cause: `Analysis.assignArmChain` kept one arm chain per node and demoted a node
  whose uses sat under two different chains to none, so the shared division's guard condemned
  every live zero divisor. The integral `FAIL` guards (the checked add and negation, row 481's)
  went through the same walk and met the same collapse. The walk now keeps a set of chains,
  ORed at the guard (`Analysis.armChains`, at most four, a chain whose steps include another's
  dropped), so the guard fires only where some arm reading the node is live. A node shared by
  outputs in two groups is guarded in each by the chains that group emits, and the conditions of
  a guard's chains are emitted before the body's first output, since a shared node is computed at
  its first use: in `VarkaGuardEdgeSuite`'s two-output shape the shared sum is reached inside the
  first output, before the second output's condition, whose word the guard reads. A single chain emits as before, and a column's stored-value guard keeps a single
  chain only, so its lane-wise min and max stay. A node inside the condition of an arm one of
  its chains runs through is computed while that condition is, before its word exists, so it is
  unqualified: the long-lane fuzzer's untaken-arm variant built that shape, a class that failed
  to verify, and `VarkaGuardEdgeSuite` pins it. `VarkaGuardEdgeSuite` holds a shared checked
  add to the verdict and the kernel, and `VarkaFloatingLaneSuite` runs the two outputs in one
  projection with no batch declined. In `emitted_bytes.json`, against master, no coverage row moved but
  this row's own new ones; at both widths 4 blocks of the int fuzz sequence, 18 of the long and 3
  of the mixed moved, and the option arms' digests over them, and those are exactly the blocks holding a shape with a guard whose
  qualification the walk changed, found by running the walk as it was beside the new one over
  the three sequences: no shape without a shared guarded node moved.
- **The review's fixes (30 October 2026).** `VarkaGuardSpecs` gives the zero divisor its own
  verdict (both operands valid and the divisor's bits `0` or the sign bit), so the coverage
  harness's domains, the composition fuzzer and the compiled check's guard edges run `FAIL`
  divisions instead of throwing, and the guard-edge search sets a column divisor to `-0.0`
  directly. The fallback accounting names `STATUS_DIVIDE_BY_ZERO` `divide-by-zero`, with its
  reason metric. The conformance base asserts for a floating type that each overflow form
  reaches an infinity, that Spark neither raises nor nulls there, and that no batch declines.
  `VarkaEmitterFloatingArithSuite` is a JUnit 5 suite, as `sql/varka/AGENTS.md` asks of a new
  catalyst suite with Java helpers.
- **Planted faults (row 504).** `VarkaFloatingFault` plants three wrong lowerings, each failed by
  the suite's checks: a checked division that never tests a `-0.0` divisor (the zero-divisor
  cells and the guard verdict), negation built as `0.0 - x`, `+0.0` where Spark answers `-0.0`
  (the bit-for-bit check against Spark, at both kinds), and `degrees` multiplying by the
  constant rounded to nine digits (the check against Spark).
- **The baselines' fuse lists (31 October 2026, on merging master).** Step 1's check refuses a
  baseline run in which an unlisted section fuses, so this step lists what it fuses, read from
  each section's plan on the Varka session (`VarkaSqlBenchmarkBase.fusedPlan`) rather than from
  the coverage table: in `VarkaFloatingArithmeticBaselineBenchmark` the sixteen `DOUBLE`
  sections, the division in both modes, `x / 3.0D`, `try_divide`, `sqrt(abs(x))`, `-x`,
  `signum` and `degrees` at both densities, the four interval sections staying the row path
  until step 4; in `VarkaFloatingBaselineBenchmark` the four arithmetic chains, beside the
  comparisons and choices already listed, the casts staying the row path until 343.
- **The cost model's fitted table, refitted (31 October 2026).** `VarkaEmitCostSuite` had not
  run on this step: its feature list built an integral `IntArith` `DIV`, which the IR refuses,
  and `MathUnary` was on neither the list nor the awaiting-refit set. With both fixed, the
  fitted `VarkaEmitCostTable` differed in 104 rows, by at most 0.57: the fit reads the
  emitted classes of the fuzz sequences, whose guarded blocks the arm-chain walk above moved.
  It is regenerated with `VARKA_COST_REGEN=true`; the register is unchanged, and
  `emitted_bytes.json` holds under the new table, so no grouping moved. `emit_cost_audit.json`
  is regenerated with it: its corpus counts two methods fewer in the 500 to 1,999 byte bin and two
  more in the 2,000 to 7,999 byte one, its percentages move by at most 0.9 points, and the
  methods under-predicted past the margin fall from 1,083 to 1,076 in its smallest bin.
- **Still owed by this step:** the IR fuzz grammar's floating sequence (341's, which the grammar
  lacks: it draws no floating tree) and the Spark fuzzer's floating productions (3.10); the
  `--asm` check of 3.2 (no hsdis on the run that built this); the census cells (step 2).
- **Later work, a measured option: a cheaper `signum`.** The built form is three compares in
  Spark's order and two blends, correct by construction, and stays. The candidate ORs `x`'s sign
  bit into the bits of `1.0` and blends that where `x` is neither zero nor NaN, keeping `x`
  elsewhere. `dev/varka_emit.sh` counts the built form at 15 calls on the emitter suite's
  scale, and the candidate is predicted at 10 (`results/VARKA-342-signum-ops.txt`, a prediction
  for its pair to score). It changes speed only, so it lands as an option arm with its pair
  benchmark, never as a replacement on a reading of the op counts.

## 6. Out of this row

`%`, `pmod` and `try_mod` (346); `try_divide` and `try_multiply` over an interval, which are
`TryEval` (`TryEval.scala:141`, `:274`; no arm claims it); decimal division (out of milestone
8); the casts (343); a multiply by an exact reciprocal (2.2, not taken).

## 7. Declines that stay

A literal zero divisor; a batch with a live lane Spark raises on (a zero divisor under ANSI, an
interval result past its type or from a NaN or infinite factor, an interval divided by zero),
which the row engine answers; `try_divide` and `try_multiply` over an interval, each a
`TryEval`; a day-time interval form past `2^51` on AVX2 hosts; until 343, every form over a cast
to double.

## 8. Questions for the owner

None open. The order relative to 343 is settled by the house pattern of landing what is ready
while the rest waits, as rows 341, 347 and 27 land in steps: the same-lane forms - `+`, `-`, `*`
and `/` over two `DOUBLE` or two `FLOAT` operands (`DivisionTypeCoercion` leaves a `DOUBLE`
division uncast, `DivisionTypeCoercion.scala:30`), the zero-divisor guard with them, negation,
`abs`, and the functions over a `DOUBLE` column - land first and the row is **Done in part**; the
forms over a cast to double and the interval forms land after 343 by its conversions. Building
343's conversions here would make two rows own one piece of code, and holding the row would keep
the commonest floating shapes on the row engine for nothing.

The baseline comes first, as its own pull request (5.1): not a question, since the house rule
settles it, as it did for rows 377, 27 and 339.

## 9. Done when

Row 342's text holds: each form fuses, bit-identical to the row engine over 3.1 to 3.7 and the
fuzzer, a computed NaN compared by NaN-ness; a zero divisor null under LEGACY and TRY and raised
through the row engine under ANSI; the interval rounding proven by 3.7's claim under both solvers;
no existing shape's bytes move; the PR build and the option matrix pass; the measurement is
committed with its predictions scored.

## 10. Outcome

(Written when the work lands.)
