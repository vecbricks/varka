# VARKA-356: Spark's math family, bit for bit: trigonometric, hyperbolic, exp, log, pow, roots

This file plans three rows of the milestone table: **356**, the trigonometric functions; **357**,
the hyperbolic functions; **358**, the exponential, logarithmic, power and root functions. They
share one design, row 345's (`VARKA-345.md`), and one kernel shape, so they are planned together;
each section says which row owns what.

## 0. What exists now (admission check)

From master at `fa698652fd2` (30 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named;
`mathExpressions.scala` means `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/
expressions/mathExpressions.scala`, `ExpressionImplUtils` the Java file beside it). The plans this
builds on are `VARKA-345.md` (merged as a plan, nothing built), `VARKA-342.md` and `VARKA-343.md`
(planned) and `VARKA-341.md` (started). The JDK was read in `/home/max/proj/openjdk-build/jdk25`
(`jdk-25-ga`) for understanding only. Sun's fdlibm 5.3 `e_exp.c` and `fdlibm.h` were fetched from
netlib with the owner's approval into a scratch directory, read to plan the first port, and not
committed. One admission program ran on the laptop under the heavy slot:
`results/VARKA-356-admission.txt` (the constants, the high-word compares, the scale's edge, `Math`
against `StrictMath` on this host, the compositions' special values).

1. **What Spark computes, by function.** Every class is `nullIntolerant`, takes `DOUBLE` only
   (`ImplicitCastInputTypes`, so an integral or `FLOAT` operand arrives under a cast to double) and
   has one answer in every mode: none reads ANSI. A `UnaryMathExpression` generates
   `java.lang.Math.<fn>(c)` (`mathExpressions.scala:84-86`); a `UnaryLogExpression` is null at
   `c <= yAsymptote` and otherwise `java.lang.StrictMath.<fn>(c)` (`:99-114`), `yAsymptote`
   being `0.0` (`:97`) and `-1.0` for `log1p` (`:851`); a `BinaryMathExpression` generates
   `java.lang.Math.<fn>(c1, c2)` (`:142-145`).

| function | class, line | Spark's generated code | null beyond the operands' |
|---|---|---|---|
| `sin`, `cos`, `tan` | `Sin` `:916`, `Cos` `:401`, `Tan` `:1024` | `Math.sin(c)`, `cos`, `tan` | no |
| `asin`, `acos`, `atan` | `Asin` `:232`, `Acos` `:209`, `Atan` `:253` | `Math.asin(c)`, `acos`, `atan` | no |
| `atan2(y, x)` | `Atan2` `:1402` | `Math.atan2(c1 + 0.0, c2 + 0.0)` (`:1410-1412`) | no |
| `sec`, `csc`, `cot` | `Sec` `:421`, `Csc` `:936`, `Cot` `:1044` | `1 / Math.cos(c)`, `sin`, `tan` (`:424`, `:939`, `:1047`) | no |
| `sinh`, `cosh`, `tanh` | `Sinh` `:960`, `Cosh` `:446`, `Tanh` `:1069` | `Math.sinh(c)`, `cosh`, `tanh` | no |
| `asinh` | `Asinh` `:980` | `ExpressionImplUtils.asinh(c)` (`ExpressionImplUtils.java:417-433`) | no |
| `acosh` | `Acosh` `:468` | `ExpressionImplUtils.acosh(c)` (`:441-454`) | no |
| `atanh` | `Atanh` `:1091` | `0.5 * (StrictMath.log1p(c) - StrictMath.log1p(- c))` (`:1096-1097`) | no |
| `exp`, `expm1` | `Exp` `:573`, `Expm1` `:594` | `StrictMath.exp(c)`, `expm1` (`:575`, `:596`) | no |
| `ln`, `log10` | `Log` `:783`, `Log10` `:832` | `StrictMath.log(c)`, `log10` | `c <= 0.0` |
| `log1p` | `Log1p` `:850` | `StrictMath.log1p(c)` | `c <= -1.0` |
| `log2` | `Log2` `:802` | `StrictMath.log(c) / StrictMath.log(2)` (`:810`) | `c <= 0.0` |
| `log(b, x)` | `Logarithm` `:1634` | `StrictMath.log(c2) / StrictMath.log(c1)` (`:1669`) | `c1 <= 0.0 or c2 <= 0.0` |
| `log(x)` | `Logarithm` with `EulerNumber` left | `StrictMath.log(c2)` alone (`:1653-1662`) | `c2 <= 0.0` |
| `pow`, `power` | `Pow` `:1434` | `StrictMath.pow(c1, c2)` (`:1437`) | no |
| `hypot` | `Hypot` `:1605` | `Math.hypot(c1, c2)` (the base class's) | no |
| `cbrt` | `Cbrt` `:271` | `Math.cbrt(c)` | no |

   The registry names these 27 (`analysis/FunctionRegistry.scala:457-505`; `log` is `Logarithm`
   and `ln` is `Log`, `:481`, `:485`; `pow` and `power` are both `Pow`, `:491-492`), which are the
   27 census entries whose rows column names 356, 357 or 358 (`census.tsv`), each with need `ulp`
   and rows `367,<row>`, written under the earlier decision.
2. **The details that decide bits.**
   - **The log family's null is value-dependent and IEEE**: `c <= 0.0` is true at `-0.0` and
     false at NaN, so `ln(-0.0)` is null and `ln(NaN)` is `StrictMath.log(NaN)`, a NaN.
   - **`atanh` calls the bare `log1p`**, with no null: `atanh(1)` is `+inf`, `atanh(-1)` `-inf`,
     `atanh(2)` NaN (`results/VARKA-356-admission.txt` part 6). Its `- c` is Java's negation.
   - **`atan2`'s `+ 0.0` changes answers**: `atan2(-0.0, -0.0)` is `-pi` bare and `0.0` as Spark
     writes it; `atan2(-0.0, -1.0)` is `-pi` bare and `pi` in Spark (part 6).
   - **`csc` and `cot` divide by a zero**: `1 / Math.sin(0.0)` is `+inf`, at `-0.0` `-inf`; the
     divide is Java's `/` on doubles, not Spark's `Divide`, so no zero-divisor null (part 6).
   - **`log(x)` with one argument never divides.** Its interpreted path divides by
     `StrictMath.log(Math.E)`, which is exactly `1.0` on this JDK (part 1), so the two paths agree;
     the generated code is the oracle either way.
   - **`log2`'s divisor is a constant**: `StrictMath.log(2)` is `0x3fe62e42fefa39ef` (part 1).
   - **`asinh` and `acosh` are Spark's own branches**, not fdlibm's `s_asinh.c` and `e_acosh.c`
     though they follow them: `asinh` tests NaN and infinity first, then `ax < 2^-28`,
     `ax > 2^28`, `ax > 2.0`, and ends in `Math.copySign(w, x)`; `acosh` tests `x < 1.0` (NaN),
     `x >= 2^28`, `x == 1.0`, `x > 2.0`. A NaN `x` reaches `acosh`'s last branch, so its answer
     is `log1p` of a NaN, its payload carried. `Math.abs` and `Math.copySign` are raw-bit
     operations in JDK 25 (`src/java.base/share/classes/java/lang/Math.java:2006-2010`,
     `:2873-2879`), so their lane forms on the long view are exact.
3. **Which `Math` methods are fdlibm on this host, measured.** Over 2^20 inputs a function, drawn
   four ways, `Math.<fn>` and `StrictMath.<fn>` differ by raw bits on this x86 laptop for `sin`,
   `cos`, `tan`, `tanh`, `cbrt`, `exp`, `log`, `log10` and `pow` (thousands to over a hundred
   thousand lanes each) and on none for `asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh`, `hypot`,
   `expm1` and `log1p`, cold and hot (part 5). That is `VARKA-345.md` item 0.3's list as a
   measurement: the per-lane call must call the class Spark calls, or the method it delegates to,
   and a call of the other class is a planted fault the sampled differential catches on x86.
4. **What 345 provides, and what it does not yet.** `VARKA-345.md` 2.1 sets each function's
   first exact form and 2.2 the three forms of `MathUnary` and `MathBinary`; 345b (its section 5,
   step 3) builds the forms, the `LANE_CALL` lowering, the `mathPort` option, the test hook,
   `VarkaMathExactSuite`'s harness and the vectors format; its step 4 builds `vfdlibm`'s skeleton
   and `exp` as the template, in the library and as Varka's `PORT` expansion. None of it is
   built: `VarkaVectorIR.java` has no `MathUnary` (342's), every math class declines in
   `floatingDecline` (`VarkaNodeCompiler.java:331-356`), and the IR fuzzer draws no floating lane
   (`VarkaIrGrammar.java:473-474`; 341 owes the floating sequence). 345 does not define how the
   log family's value-dependent null is carried (2.3, open point 1).
5. **`e_exp.c`, read.** The file is `@(#)e_exp.c 1.6 04/04/22`, Copyright 2004 Sun
   Microsystems, with a notice permitting use, copying, modification and distribution provided it
   is preserved (digest in the results file). Its eleven constants parse from the source's
   decimal text to the hexadecimal its comments give (part 2). Its five high-word compares, read
   as compares of `|x|` against the double whose bits are the word shifted left 32 (345's 2.2
   helper), agree with the word compares on every one of 7860480 pairs tried (part 3), a NaN
   failing every double compare, so the filter's mask must be the negation of a `<` to keep NaN
   in it. The reduction's integer `k` is `+-1` at the far branch's lower edge, so `k` is never 0
   off the primary range, and it is 1024 at the overflow threshold and -1075 at the underflow
   one; the scale-back takes its subnormal arm from `x = -708.0498449419841` down (part 4).
6. **The vfdlibm repository** (https://github.com/vecbricks/vfdlibm, read with `gh api`): a
   `pom.xml`, `LICENSE`, `NOTICE` and a `PLAN.md` with no function yet. Its plan names the class
   `VFdlibm` and says a masked variant "leaves inactive lanes untouched" (its section 5), where
   `VARKA-345.md` 2.2 names it `VectorFdlibm` and returns `0.0` in dead lanes (open point 4).
7. **No benchmark times a math function.** `VarkaMathBaselineBenchmark` is 345's step 1 and does
   not exist; `VarkaFloatingBaselineBenchmark` times a comparison, a chain, a cast and a choice.

### What differs from the row text and the milestone plan

- **Exact by default** (the owner, 30 October 2026, `VARKA-345.md` 1): the rows' "under the
  contract", "within its stated bound" and "only after 367 has measured it" apply to the opt-in
  `LIBRARY` form only (2.7); the default forms are bit for bit and wait for no probe.
- **357's "fdlibm's branch blends of three math calls per lane"** becomes Spark's branches with
  **one call a lane**: each branch's call is made only on the lanes that take it (2.4).
- **358's `pow` guard, exact nodes and SLEEF output guard** concern the opt-in form; the exact
  `pow` is `StrictMath.pow`, weak branch included (`VARKA-345.md` 8, point 6).
- **`exp`'s port is 345's template** (its section 5, step 4); this file lays out its branches
  (2.5) and owns the measurement that decides whether it becomes `exp`'s default (358).
- **The log family needs a carrier for its null** that 345 does not define (2.3, open point 1).

## 1. Goal and non-goal

Every one of the 27 functions fuses in its exact form, bit-identical to vanilla Spark's generated
code on every host CI runs: first as a per-lane call (`LANE_CALL`) of the method Spark's code
calls, or for the seven `Math` methods that delegate, of the `StrictMath` method they delegate to;
the compositions as Spark writes them over those calls; and for the thirteen fdlibm functions a
`PORT` arm each, a lane-wise port of fdlibm 5.3 emitted as IR and paired with a `vfdlibm` port,
made the default only where it measures faster on the EPYC runners. Not here: the configuration,
the forms and their lowerings, the harness and the vectors format (345); the probe and every
opt-in bound (367); a port of `sin`, `cos`, `tan`, `tanh` or `cbrt` (`VARKA-345.md` 8, point 11);
`MathUnary` itself, `SQRT` and the lane divide (342); the conversions into `DOUBLE` (343).

## 2. Design

### 2.1 The functions on 345's nodes

Each one-call function is a `MathFn` member (`VARKA-345.md` 2.2), naming its Spark method, the
method its `LANE_CALL` calls and its `VectorOperators` constant; the compositions are trees over
those members and 342's lane arithmetic, with operands in Spark's order and no rewrite.

| row | function | IR | `LANE_CALL` calls | `PORT` arm from |
|---|---|---|---|---|
| 356 | `sin`, `cos`, `tan` | `MathUnary(SIN, ...)` | `Math.sin`, `cos`, `tan` | none |
| 356 | `asin`, `acos`, `atan` | `MathUnary(ASIN, ...)` | `StrictMath.asin`, `acos`, `atan` | `e_asin.c`, `e_acos.c`, `s_atan.c` |
| 356 | `atan2(y, x)` | `MathBinary(ATAN2, y + 0.0, x + 0.0)` | `StrictMath.atan2` | `e_atan2.c` |
| 356 | `sec`, `csc`, `cot` | `DIV WRAP(1.0, MathUnary(COS))`, `SIN`, `TAN` | as `cos`, `sin`, `tan` | none |
| 357 | `sinh`, `cosh` | `MathUnary(SINH, ...)` | `StrictMath.sinh`, `cosh` | `e_sinh.c`, `e_cosh.c` |
| 357 | `tanh` | `MathUnary(TANH, ...)` | `Math.tanh` | none |
| 357 | `atanh` | `0.5 * (LOG1P(x) - LOG1P(NEG x))` | as `log1p` | by `log1p`'s |
| 357 | `asinh`, `acosh` | Spark's branches (2.4) | one `LOG` or `LOG1P` a lane | by `log`'s and `log1p`'s |
| 358 | `exp`, `expm1` | `MathUnary(EXP, ...)` | `StrictMath.exp`, `expm1` | `e_exp.c` (345), `s_expm1.c` |
| 358 | `ln`, `log10`, `log1p`, `log(x)` | `MathUnary(LOG, ...)` under the domain null | `StrictMath.log`, `log10`, `log1p` | `e_log.c`, `e_log10.c`, `s_log1p.c` |
| 358 | `log2` | `DIV WRAP(LOG(x), 0x3fe62e42fefa39ef)` | as `ln` | by `log`'s |
| 358 | `log(b, x)` | `DIV WRAP(LOG(x), LOG(b))`, each under its null | as `ln` | by `log`'s |
| 358 | `pow` | `MathBinary(POW, x, y)` | `StrictMath.pow` | `e_pow.c` |
| 358 | `hypot` | `MathBinary(HYPOT, x, y)` | `StrictMath.hypot` | `e_hypot.c` |
| 358 | `cbrt` | `MathUnary(CBRT, ...)` | `Math.cbrt` | none |

- **The divide in `sec`, `csc`, `cot`, `log2` and `log(b, x)`** is 342's `DIV` in `WRAP`, the
  bare IEEE quotient: Spark's code is Java's `/`, never `Divide`, so a zero divisor gives an
  infinity and is no null (item 0.2). 342's table puts `WRAP` at a non-zero literal divisor; here
  a column divisor takes it too, which its constructor must admit (section 7 lists it as 342's).
- **`atan2`'s `+ 0.0`** is `IntArith(ADD, WRAP, operand, 0.0)` on each side, never dropped (342's
  no-rewrite rule).
- **`atanh`'s negation** is `IntNeg(WRAP, x)`, the sign flip Java's `- c` is.
- **`log2`'s divisor** is the bits of `StrictMath.log(2)` read at compile time; `log(b, x)` with
  a literal `b > 0` folds `StrictMath.log(b)` the same way, the same double Spark computes a row;
  a literal `b <= 0` makes every row null and declines by name (section 7).
- **`log(x)` and `ln(x)`** are one node: the compiler matches `Logarithm` with an `EulerNumber`
  left, as Spark's code generator does, and never divides.

### 2.2 The per-lane call in the kernel

`LANE_CALL` is 345's lowering (`VARKA-345.md` 2.2, 2.9); this is its shape for these functions.
Within one vector of the body, for a node `MathUnary(fn, LANE_CALL, a)`:

```java
DoubleVector a = ...;                         // the operand, vectorised as ever
a.intoMemorySegment(scratch, off, LE);        // one store to the node's lane slots
// dense body (no null, no arm, no domain null): every lane
for (int i = 0; i < VLEN; i++) {
  long o = off + 8L * i;
  scratch.set(D, o, StrictMath.exp(scratch.get(D, o)));
}
// masked body, an arm, the tail, or a domain null: the live lanes only
long w = live.toLong();                       // validity & arm & in range & !domain
for (; w != 0; w &= w - 1) {
  long o = off + 8L * Long.numberOfTrailingZeros(w);
  scratch.set(D, o, StrictMath.exp(scratch.get(D, o)));
}
DoubleVector r = DoubleVector.fromMemorySegment(S, scratch, off, LE)
    .blend(0.0, live.not());                  // a dead lane reads 0.0 (2.9)
```

- **The lanes called are exactly the lanes Spark's code calls.** A null operand lane, a lane an
  arm masks off, a lane past the batch's end and a lane the log family nulls are not called, so
  a dead lane holding `1e300` or a NaN costs nothing; the dense body, which has none of them, has
  no word and no test. A binary node stores both operands and calls `fn(x, y)` per live lane.
- **Domain nulls are computed before the call.** For the log family the mask is the IEEE
  `a.compare(LE, yAsymptote)`, NaN never in it; the node's validity is the operand's with that
  mask cleared, and the call's live word excludes it. A null from the domain is a null like an
  operand's, which any consumer above reads through the validity as ever.
- **No allocation a batch**: the slots are the kernel's scratch, the loop a counted loop or a
  bit walk; `VarkaAllocationSampler` holds every function's kernel to zero, as 345's test 3.3
  does for the hook's five.
- **The cost is the call**: `VARKA-345.md` item 0.6 measured the per-lane `Math.sin` loop at about
  the scalar loop's time. What a `LANE_CALL` kernel gains is everything around the call.

### 2.3 The log family's null: a property of the call node

`MathUnary` gains `nullAtOrBelow`, an optional double: absent for every function but `LOG`,
`LOG10` and `LOG1P` under the classes that null, where it is `0.0` or `-1.0`. The lowering is
2.2's: the compare, the cleared validity, the narrowed live word. The same `MathUnary(LOG1P)` with
no threshold is `atanh`'s bare call, and `asinh`'s and `acosh`'s calls are bare too. So
`log(b, x)` is `DIV WRAP(LOG(x, <= 0), LOG(b, <= 0))`: either operand's null nulls the quotient
through ordinary null propagation, which is Spark's `c1 <= 0.0 || c2 <= 0.0`, and the bits are
`StrictMath.log(x) / StrictMath.log(b)` wherever both are live. The precedent is 342's `DIV NULL`,
a value-dependent null held by the node that causes it (`VARKA-342.md` 2.2). The alternative, a
general `NullWhen(cond, value)` node above a bare call, needs the call to learn its consumer's
mask to skip the null lanes; it is open point 1.

The field is part of the node, so it reaches the shape key and the reference evaluator, which
evaluates the node as `x <= a ? null : StrictMath.<fn>(x)`.

### 2.4 `asinh` and `acosh`: Spark's branches, one call a lane

Each branch of `ExpressionImplUtils` becomes a lane mask, in Spark's order, every mask excluding
the lanes an earlier test took. Each branch's call is a `LANE_CALL` under the arm of its branch,
so a lane calls exactly the one function Spark's code calls for it and none for the branches that
return without one:

| `asinh` branch (Spark's order) | lanes | value of `w` |
|---|---|---|
| NaN or infinite `ax` | `ax != ax` or `ax == inf` | `ax` |
| `ax < 2^-28` | the rest below | `ax` |
| `ax > 2^28` | the rest above | `LOG(ax) + c2`, `c2` the bits of `StrictMath.log(2.0)` |
| `ax > 2.0` | the rest above | `LOG(2.0 * ax + 1.0 / (SQRT(x * x + 1.0) + ax))` |
| otherwise | the rest | `LOG1P(ax + t / (1.0 + SQRT(1.0 + t)))`, `t = x * x` |

then `copySign(w, x)` on the long view. The two `LOG` branches feed one node: its argument is the
blend of `ax` and the `ax > 2.0` branch's argument, its live word the union of the two branches,
and the `+ c2` is added in the `ax > 2^28` lanes only, by a blend rather than an add of `0.0`
elsewhere. `acosh` is the same with `x < 1.0` giving the NaN `0x7ff8000000000000`, `x >= 2^28`
`LOG(x) + c2`, `x == 1.0` giving `0.0`, `x > 2.0` `LOG(2.0 * x - 1.0 / (x + SQRT(x * x - 1.0)))`
and otherwise `LOG1P(t + SQRT(2.0 * t + t * t))`, `t = x - 1.0`; a NaN `x` fails all four tests and
takes the last branch, as in Spark (item 0.2). `x * x` is Spark's operand (`asinh` squares `x`,
not `ax`). `SQRT` is 342's lanewise operator, IEEE's correctly rounded root as `Math.sqrt` is.
Every intermediate value is computed in every lane and blended; only the calls are narrowed.

`atanh` makes two calls a lane, `LOG1P(x)` and `LOG1P(-x)`, as Spark's code does.

### 2.5 The first port: `exp`, laid out from `e_exp.c`

345 builds `exp` as the template (`VARKA-345.md` 5, step 4), once in `vfdlibm` and once as
Varka's `PORT` expansion; `sinh` and `cosh` reuse its sequence, and `pow` its scale-back
technique. This is the layout both
are written to, from the C source; `hx` is the high word of `|x|` and every `hx` compare is a
compare of `ax = |x|` against `fromHi(H)`, the double with bits `H << 32` (item 0.5).

| `e_exp.c` branch (lines) | lanes | result | form |
|---|---|---|---|
| filter, non-finite (117-122) | `!(ax < fromHi(0x40862E42))` and `ax` NaN or infinite | `x + x` for NaN; `x` for `+inf`; `0.0` for `-inf` | finished per lane |
| filter, overflow (123) | the filter and `x > o_threshold` | `huge * huge` | finished per lane |
| filter, underflow (124) | the filter and `x < u_threshold` | `twom1000 * twom1000` | finished per lane |
| filter, falls through (117-125) | the filter's other lanes | the far reduction below | finished per lane |
| near reduction (128-130, 137) | `ax >= fromHi(0x3fd62e43)` and `ax < fromHi(0x3FF0A2B2)` | `hi = x - ln2HI[s]`, `lo = ln2LO[s]`, `k = 1 - 2s` | blended |
| far reduction (131-137) | `ax >= fromHi(0x3FF0A2B2)` | `k = (int) (invln2 * x + halF[s])`, `hi = x - k * ln2HI[0]`, `lo = k * ln2LO[0]` | blended |
| tiny (139-141) | `ax < fromHi(0x3e300000)` | `one + x` | blended |
| primary (142, 145-147) | the rest | `one - ((x * c) / (c - 2.0) - x)` | blended |
| reduced result (145-148) | either reduction | `y = one - ((lo - (r * c) / (2.0 - c)) - hi)`, `r = hi - lo` | blended |
| scale, normal (149-151) | `k >= -1021` | `y`'s bits plus `k << 52` | blended |
| scale, subnormal (152-155) | `k < -1021`, `x <= -708.0498449419841` | bits plus `(k + 1000) << 52`, times `twom1000` | finished per lane |

`s` is the sign bit of `x`, read from the long view as the C reads `hx >> 31`; `c` is the
polynomial `r - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))))`, `t = r * r`, evaluated in
every lane in the source's order, each constant as the source writes it (item 0.5 checks they
parse to its hex), no FMA, no reassociation.

**Which lanes finish per lane.** One interval decides it: a lane is computed by the vector body
when `x > -708.0498449419841` and `x < fromHi(0x40862E42)`, which holds every finite lane of the
two reductions, the tiny branch and the primary range and no lane of the filter or the subnormal
scale; every other live lane - NaN, the infinities, overflow, underflow, the band the filter lets
through and the subnormal results - is finished after the body by a per-lane `StrictMath.exp`,
which is exact. One compare pair and one `anyTrue` a vector replace the filter's three blends and
the subnormal arm's compare, blends and multiply. On ordinary data the finish never runs: over
`[-2, 2]` the filter took 0 of 2^20 inputs (`results/VARKA-345-exact-admission.txt` part 2). Its
worst case, a column of NaNs or of values beyond 709, costs the `LANE_CALL` form's time per lane
and not more, so the layout never loses badly. **The measured alternative** is the prototype's,
every branch blended (`VARKA-345.md` item 0.5), which matched `StrictMath.exp` on every vector
and sample; 345's 2.4 rule picks between them on the library's benchmark, by minimums, and the
choice is recorded in the `vfdlibm` pull request.

**The integer `k`, three exact ways.** The far reduction converts `invln2 * x + halF[s]` to an
int by truncation, and the scale shifts `k` into the exponent. Each of these gives the same
integer, so each is exact, and the library times them before one is emitted: (i) 343's `D2I`
conversion, intrinsic on AVX2 (`VARKA-343.md` item 0.5), with `t = k` back by `I2D` and the
exponent's `k << 52` from the int lanes widened, which crosses lane counts (`VARKA-343.md` 2.2);
(ii) all in 64-bit lanes: the truncation by 344's `floor` and `ceil` construction, and
`k << 52` as the long view of `t + 0x1.8p52` shifted left 52, exact because the magic's low
twelve bits are zero and `|k| < 2^51`; (iii) 343's `D2L`, intrinsic only under AVX512DQ and a
fallback on the EPYC 7763 (`VARKA-343.md` item 0.5), so (iii) is not a candidate there.

**Size against the budget.** The expansion is inline in the kernel's method; the `PORT`'s node
count, printed by `dev/varka_emit.sh`, is checked against `VarkaEmitBudget` in 345's step-4 pull
request, and a kernel whose method the port would push over a limit keeps `LANE_CALL`.

**The `vfdlibm` pull request it pairs with**: *"Port exp from fdlibm 5.3's e_exp.c"*, the
library's first: the class and its API (open point 4), the lane helpers (`hi`, `lo`, `withHi`,
`fromHiLo`, the high-word compare, the exponent add) with their raw-bit tests, the masked form,
the per-lane finish, `exp` with `e_exp.c`'s Sun notice in its source header, its tests against
`StrictMath` (2.6 tests 1, 2 and 3 run in the library), the planted-fault flag of its `PLAN.md`
section 4, and its JMH benchmark against the scalar `StrictMath.exp` loop at 128, 256 and 512
bits. Varka's pull request, 345's step 4, emits the same layout and holds it to the library lane
for lane and to `StrictMath`.

### 2.6 The other ports, in dependency order

Each is a pair of pull requests, `vfdlibm` and Varka, as 2.5's, and each starts by fetching its C
file from netlib (open point 3). The order follows what each function calls, so a port reuses the
IR builders of the ones before it. The calls were read in the JDK's `FdLibm.java`, for
understanding only (its `Sinh` and `Cosh` call `StrictMath.exp` and `expm1`, `Log10` calls `log`,
`Atan2` calls `atan`, `Asin`, `Acos` and `Hypot` call `Math.sqrt`, and `Pow` calls none), since
only `e_exp.c` is fetched; each port's pull request confirms its edges in the C file it fetches:

1. `expm1` (`s_expm1.c`), 358; then `sinh` and `cosh` (`e_sinh.c`, `e_cosh.c`), 357, over the
   `exp` and `expm1` sequences inline.
2. `log` (`e_log.c`), then `log10` (`e_log10.c`, which calls `log`), then `log1p` (`s_log1p.c`),
   358; each brings `log2`, `log(b, x)`, `atanh`, `asinh` and `acosh` its form, which they follow
   (`VARKA-345.md` 2.1).
3. `atan` (`s_atan.c`), then `atan2` (`e_atan2.c`, which calls `atan`), then `asin` and `acos`
   (`e_asin.c`, `e_acos.c`, over `SQRT`), 356.
4. `hypot` (`e_hypot.c`, over `SQRT`), 358.
5. `pow` (`e_pow.c`), 358, last: the largest (about a fifth of the thirteen's code,
   `results/VARKA-345-exact-admission.txt`), the one most likely to meet the method budget, and
   the one whose branch layout (the weak branch included) gains most from the others' lessons.

A port's own branch layout is planned in its pull request by 2.5's method: its branches as the C
source has them, the rare ones finished per lane by the interval or mask that bounds them, the
alternative blended, the integer and exponent operations by the helpers 2.5 picked.

### 2.7 The opt-in form, per function

`LIBRARY` (`VARKA-345.md` 2.5) is enabled for a function by its row only where 367 has committed a
line for the binding and the function has a contract entry; until then the opt-in mode computes
the exact form. The rows' existing text carries the opt-in details: `tanh` is never bound on SLEEF
hosts (`VARKA-345.md` item 0.7), so it stays exact there under the mode too; `pow` is unsupported by
SVML below 512 bits and takes owner's decision A3's guard and the SLEEF output guard in the mode
only; `LOG`, `LOG10` and `POW` at 512 bits need AVX512DQ. A composition over a `LIBRARY` node is
checked by 345's library reference (`VARKA-345.md` 2.8). The opt-in entries are the last step of
each row (section 5) and carry no default answer.

### 2.8 Compiler, reference, cost, census, warm-up

- **The arm**: `VarkaMathCompiler` (345's) replaces each class's "row 35x's" decline by its
  lowering, by class and operand type; an operand under a cast to double fuses once 343's
  conversion does and declines naming 343 until then.
- **The reference**: `VarkaReference` evaluates each node by Spark's method (345's 2.8), with
  2.3's threshold, never by the port it checks and never by the Vector API.
- **The cost**: each `LANE_CALL` is a feature of `VarkaEmitCostRegister` per function (345's
  2.11), weighed as a scalar call times the live lanes; `asinh`, `acosh` and `atanh` weigh their
  calls and their arithmetic.
- **The census**: no cell is new for `LANE_CALL`, which is a store, a scalar call and a load; a
  port's cells are 343's reinterpretations and conversions and 341's long shifts.
- **The warm-up** (`VarkaWarmupReachSuite`): each function's call loop in both bodies, and each
  port's expansion once built.

## 3. Tests and proofs

1. **The sampled differential**, `VarkaMathExactSuite` (345's harness), one cell per function and
   form: the kernel's lanes against Spark's generated expression on the same JVM - the method,
   or the composition as Spark writes it, evaluated by scalar Java - by `doubleToRawLongBits`,
   over at least 2^24 inputs a function, the four draws of `VARKA-345.md` 2.3 plus the function's
   own: arguments past `2^20` and up to `1e300` for `sin`, `cos`, `tan` and their compositions,
   where the reduction is hardest; `[-1, 1]` and its edges for `asin`, `acos`, `atanh`; `[1, 3]`
   and `2^28`'s neighbourhood for `acosh`; subnormals, `[0.5, 2]` and the non-positive half for
   the log family; for `pow`, integral `y`, `x` within `2^-20` of 1 with `|y|` above `2^31` (the
   weak branch), negative `x` with integral and non-integral `y`; for `atan2` and `hypot`, every
   pairing of zeros, infinities and NaN with the other draws. Null lanes and arms in both bodies,
   at each width the host runs; a `PORT` cell is held to the library's lanes as well. A
   domain-null lane is checked as null, never as a value. The suite runs in the PR build (x86) and
   in the census workflow on the Arm runner, since `Math`'s answer is per host (item 0.3).
2. **The fixed vectors**, `sql/varka/math/<fn>.vectors` (345's format), each with 64 neighbours
   each way of every edge: SLEEF's special-value list (`VARKA-345.md` 2.3); Spark's own edges
   (`0.0`, `-0.0`, `-1.0` and `1.0` for the log family, `atanh` and `acosh`; `2^-28`, `2.0` and
   `2^28` for `asinh` and `acosh`; the zero and sign cells of item 0.2 for `atan2`, `csc`, `cot`);
   NaN payloads of both signs, quiet and signalling, through every function, since Spark's answer
   carries them; and each fdlibm threshold of the function's C source. For `exp` that is
   `o_threshold`, `u_threshold`, `+-fromHi(H)` and `+-fromHi(H + 1)` for each of the five words of
   item 0.5, and the subnormal edge `-708.0498449419841`; the other functions' thresholds come
   with their C files (open point 3).
3. **Through the engine**: coverage rows (item 6) and the coverage differential, bit for bit, the
   Spark oracle and the Spark-level fuzzer over the functions.
4. **Planted faults** (row 504's rule: each check above proves it can fail), each a test-only
   switch the suite turns on and must fail under:
   - the other class's method in a `LANE_CALL` (`StrictMath.sin` for `Math.sin`, `Math.exp` for
     `StrictMath.exp`): caught by test 1 on x86 for the nine functions item 0.3 measured
     differing, and asserted only there, since the classes agree on Arm for several;
   - the log family's `<=` as `<`: `ln(0.0)` gives `-inf` against null, caught by test 2;
   - `atan2` without its `+ 0.0`: `atan2(-0.0, -1.0)` gives `-pi` against `pi`, caught by test 2;
   - the call loop ignoring its live word: caught by a recording target the test hook installs,
     which fails when a null, masked or domain-null lane is called;
   - `asinh`'s and `acosh`'s `2^28` test moved one double: caught by test 2 at the edge, or the
     cell replaced by one whose two branches differ there, so the plant is seen to fail;
   - for every port, the three of `vfdlibm`'s `PLAN.md` section 4: a constant one step off
     (`P5`), a dropped correction term (`exp`'s `lo`), a threshold moved one word
     (`0x3FF0A2B2`), each caught by tests 1 and 2 in Varka and in the library.
   A plant that no test catches is a finding: the vectors gain the input that catches it.
5. **Completeness** (row 504): the suite fails when a `MathFn` member lacks its cell, its vectors
   file, its coverage row, its reference case, its fuzz production or, for a built port, its
   plants.
6. **Coverage rows**, a "math" family over `x` and `x2`: each of the 27 functions over a column;
   `log(x)`, `ln(x)`, `log(2.0, x)` (the folded base), `log(x2, x)`, `log2(x)` over a fixture with
   zeros, `-0.0` and negatives, so the domain null is exercised; `power(x, x2)` and `pow(x, 2.0)`;
   `exp(x) * 2.0 + 1.0`, `sin(x) < 0.5` as a filter and `sqrt(x * x + 1.0) + asinh(x)` as
   compositions; `sin(i)` over an `INT` once 343 lands. Each says `"verified": "differential"`
   and names its form ("exact: per-lane `Math` call" and the rest, `VARKA-345.md` 2.10).
7. **IR fuzz productions** in the floating sequence of `VarkaIrGrammar` (341's): `MathUnary` over
   each unary member and `MathBinary` over `ATAN2`, `POW` and `HYPOT` in `LANE_CALL`, with and
   without 2.3's threshold, under arms and over nulls; `PORT` for each built port, drawn through
   `mathPort`; the floating column bound drawing NaN payloads, both zeros, the infinities,
   subnormals and values past each function's thresholds. The reference evaluates by Spark's
   method; a failure replays by seed and iteration as ever.
8. **The generated files** in `ADDING_AN_EXPRESSION.md`'s order: `coverage.json`,
   `docs/sql-varka.md`, `emitted_bytes.json` gaining the new shapes and moving no existing one.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code.** `VarkaMathBaselineBenchmark`
is 345's (its section 4: `sin`, `tan`, `cbrt`, `exp`, `log`, `log10`, `pow`, `atan2`, `sinh`,
`hypot`, `sec`, `exp(x) * 2 + 1`). These rows add the functions it lacks, in the same class:
`cos`, `asin`, `acos`, `atan`, `csc`, `cot`, `cosh`, `tanh`, `asinh`, `acosh`, `atanh`, `expm1`,
`ln`, `log1p`, `log2`, `log(2.0, x)`, `log(x2, x)`, `ln(x)` over a column with one row in ten
non-positive, and `pow(x, 3.0)`; each with and without nulls, as the floating class does. Run on
the EPYC 7763 at 256 and 128 bits through `benchmark.yml`, `expected-cpu="AMD EPYC 7763"`; a
laptop run at 512 bits is labelled laptop.

**Each fusing pull request** reruns the class with the fuse check lifted and diffs against the
committed baseline at both widths (`dev/varka_bench_diff.py`, a family band once a move must be
read). **Each port** adds its arm as an A/B pair in the same class (`--within --ab`, `mathPort`
on and off), on the EPYC runners at 256 and 128 bits by minimums, which is 2.1's rule of
`VARKA-345.md`.

Predictions, registered before the build:

1. **No existing shape's bytes move.**
2. **A lone `LANE_CALL` gains nothing on the call**: each one-call function over a column within
   0.8 to 1.3 times the row path's time a row, 345's prediction 2, at both widths.
3. **The compositions sit in the same band**: `sec`, `csc`, `cot`, `log2`, `log(b, x)` with a
   literal base, `asinh` and `acosh` (one call a lane, 2.4), `atanh` (two, as the row path).
4. **Work around the call is where the gain is**: `exp(x) * 2.0 + 1.0` faster than the row path,
   by the arithmetic's share; direction only.
5. **The domain null costs the bit walk only**: `ln(x)` over the column with non-positive rows
   within the same band as over positive ones.
6. **Each port against `LANE_CALL`**: no direction is registered, as 345's prediction 3 says;
   the first measurement is `exp`'s.
7. **The tests' time**: 345's prediction 5 per function, 2^24 reference calls about 0.1 s each,
   under 30 s for the 27 with the kernels and the sanitizer at the calibration table's new-suite
   ratio; the Arm run the same.

## 5. Order, files and size

0. **The baseline addition** (4), its own pull request, after or with 345's step 1 (open point 2).
1. **358a, after 345b**: `exp`, `expm1`, `ln`, `log(x)`, `log10`, `log1p`, `log2`, `log(b, x)`,
   `pow`, `hypot`, `cbrt` in `LANE_CALL`, with 2.3's threshold, their cells, vectors, coverage
   rows, productions and plants. First because it builds the domain null, and because 345's
   step 4 times the `exp` port against this form.
2. **356a**: `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `sec`, `csc`, `cot`, the same
   set of tests.
3. **357a, after 358a**: `sinh`, `cosh`, `tanh`, `atanh`, `asinh`, `acosh`, the last three over
   358a's `LOG` and `LOG1P` and 342's `SQRT`; the narrowed calls of 2.4.
4. **358b, after 345's step 4**: `exp`'s `PORT` measured against its `LANE_CALL` on the EPYC
   runners, and `mathPort`'s default holding `EXP` only if the port is faster there.
5. **The ports in 2.6's order**, each a `vfdlibm` and Varka pair, each with its measurement and
   default decision: 358c `expm1`, 357b `sinh` and `cosh`, 358d `log`, `log10`, `log1p`, 356b
   `atan` and `atan2`, 356c `asin` and `acos`, 358e `hypot`, 358f `pow`.
6. **The opt-in entries** (2.7), per row, after 367 commits a host's lines.

| file | what |
|---|---|
| `VarkaMathCompiler.java` | the classes' lowerings, the compositions, `EulerNumber`'s match |
| `varka/VarkaVectorIR.java` | the `MathFn` members, `nullAtOrBelow` |
| `varka/VarkaVectorWalk.java`, `Analysis.java` | the domain null's validity and live word |
| `varka/VarkaMathPort.java` | each port's expansion after `exp`'s |
| `vfdlibm` | each port, its tests, plants and JMH arm |
| tests | `VarkaMathExactSuite` cells, plants, completeness, `VarkaReference`, the grammar |
| `sql/varka/math/` | one vectors file a function |
| benchmark | the baseline addition and each fusing and port run |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json`, `census.tsv` |

**Size: large, against three rows of medium.** The baseline addition half a day; 358a three days
(the domain null and eleven functions); 356a two; 357a two and a half (2.4's narrowed calls); 358b
one; the ports about two days each for the library and the IR (`expm1`, `log`, `log10`, `log1p`,
`atan`, `atan2`, `asin`, `acos`, `hypot`), `sinh` and `cosh` together two and a half, `pow` four:
about thirty-three days for the three rows in all, three quarters of it ports that are option arms
and may stay arms. The `LANE_CALL` half, which delivers every function bit for bit, is about eight
days with the baseline.

## 6. What it unlocks in the census

The 27 entries of item 0.1 count fused when their `LANE_CALL` form lands, at `DOUBLE`, their only
type: 11 with 358a, 10 with 356a, 6 with 357a. The ports move no count; they are speed. The
entries' need column reads `ulp` and their rows `367,<row>`, both from the earlier decision: under
the exact default the need is the lowering and 367 is the opt-in mode's only, so 358a's pull
request corrects the 27 lines of `census_curated.txt` it renders from (open point 5).

## 7. Out of this row, and declines that stay

| piece | owner |
|---|---|
| the configuration, the forms, the `LANE_CALL` lowering, the harness, the vectors format, the test hook, the baseline class, `vfdlibm`'s skeleton and `exp`'s port in both places | 345 |
| `MathUnary`, `SQRT`, the lane `DIV` (in `WRAP` over a column divisor too) | 342 |
| `D2I`, `I2D`, the reinterpretations, an operand cast to double | 343 |
| `floor` and `ceil` for 2.5's integer arm (ii) | 344 |
| the floating sequence of the IR grammar | 341 |
| the probe, every opt-in bound, `pow`'s guarded domain | 367 |
| the user support table | 353 |
| a port of `sin`, `cos`, `tan`, `tanh`, `cbrt` | not planned (`VARKA-345.md` 8, point 11) |

Declines that stay: `log(b, x)` with a literal `b <= 0` (every row null; a null-literal leaf is
row 333's), and until 343 an operand under a cast to double.

## 8. Open points for the lead

1. **The carrier of the log family's null.** (a) `nullAtOrBelow` on `MathUnary` (2.3): the node
   that causes the null holds it, as 342's `DIV NULL` does; the call skips the lanes by itself;
   one field on a node 345 defines. (b) A general `NullWhen(cond, value)` node over a bare call:
   reusable by other value-dependent nulls, but the call's lowering must learn the mask of the
   node above it to skip those lanes, a cross-node rule nothing has yet. *Recommended: (a).*
   What changes with (b): 2.3, and a new node with its own production and reference case.
2. **The baseline's cases.** (a) Ask 345's step-1 pull request to carry the nineteen cases of 4
   too, one pull request on unchanged code. (b) A second baseline pull request from these rows.
   *Recommended: (a)* if 345's baseline is not yet opened; it costs one run instead of two.
3. **Fetching the other fdlibm files.** The owner's approval of 30 October covers the first port,
   `e_exp.c` and its header. Each later port needs its C file, and the fixed vectors of every
   function's `LANE_CALL` cell want its thresholds (3 test 2). (a) One approval for the twelve
   remaining files of 2.6, fetched by each port's pull request with its digest. (b) An approval
   per port. Until then, the `LANE_CALL` cells' vectors hold the special values and Spark's
   edges only, and gain the thresholds with the port. The owner's question.
4. **`vfdlibm`'s class name and masked contract.** `vfdlibm`'s `PLAN.md` says `VFdlibm` and "leaves
   inactive lanes untouched"; `VARKA-345.md` 2.2 says `VectorFdlibm` and `0.0` in dead lanes. Varka
   holds its kernel to the library on live lanes only, so either contract serves; the name and
   the dead-lane value must be one before the first `vfdlibm` pull request. *Recommended: the
   library's own plan, `VFdlibm` and untouched lanes, with 345's text corrected by a dated note.*
5. **The census correction**: the 27 lines of `census_curated.txt` read need `ulp` and rows
   `367,<row>`. (a) 358a's pull request rewrites them to the exact default's need and drops 367
   from the rows. (b) The lead's records pull request, with the rows' text. *Recommended: (a)*,
   the pull request that makes them true.
6. **Ownership of `exp`'s port.** `VARKA-345.md` 5 step 4 and its 7 give the port to 345 and the
   default decision to 358; this file lays out the branches (2.5) and keeps that split. If the
   lead prefers 358 to build it, 345's step 4 shrinks to the library's skeleton, and 358b grows
   by about a day and a half.
7. **The size**: about thirty-three days, against three rows of medium (5); the ports are the
   bulk, and each may stay an arm.
8. **The status lines for the three rows**, to apply after the batch plan-text pull request
   merges (this branch does not touch `PLAN.md`):
   - **356**: "**Planned** (`VARKA-356.md`, 30 October 2026, with 357 and 358; the admission
     check: Spark's code calls `Math` for every function of the row, which on this x86 host
     differs from `StrictMath` for `sin`, `cos` and `tan` and agrees for `asin`, `acos`, `atan`
     and `atan2`, so the first form is a per-lane call of the method Spark's code reaches;
     `atan2`'s `+ 0.0` and the zero divisors of `csc` and `cot` change answers and are kept
     operation for operation; `results/VARKA-356-admission.txt`; the ports of `atan`, `atan2`,
     `asin` and `acos` are measured option arms after `exp`'s; 356a and two port pull request
     pairs)."
   - **357**: "**Planned** (`VARKA-356.md`, 30 October 2026, with 356 and 358; the admission
     check: `asinh` and `acosh` are Spark's own branches in `ExpressionImplUtils`, fused with one
     call a lane, each branch's `log` or `log1p` made only on the lanes that take it; `atanh`
     calls the bare `log1p`, with no null; `sinh` and `cosh` are fdlibm on every host and their
     ports reuse `exp`'s and `expm1`'s sequences; `tanh` is a per-lane `Math.tanh`;
     `results/VARKA-356-admission.txt`; 357a after 358a, and one port pull request pair)."
   - **358**: "**Planned** (`VARKA-356.md`, 30 October 2026, with 356 and 357; the admission
     check: the log family's null at `<= 0` (`<= -1` for `log1p`) is value-dependent and IEEE,
     null at `-0.0`, not at NaN, carried by the call node, which skips those lanes; `log(x)` with
     one argument never divides; `log2` divides by the folded bits of `StrictMath.log(2)`;
     `e_exp.c` was fetched with the owner's approval and its branches laid out for 345's
     template, every finite lane in `(-708.0498449419841, fromHi(0x40862E42))` computed in the
     vector body and the rest finished per lane; `results/VARKA-356-admission.txt`; 358a first,
     then 358b's measurement of `exp`'s port, then five port pull request pairs)."

## 9. Done when

Every one of the 27 functions fuses over `DOUBLE` in its `LANE_CALL` form, bit-identical to
Spark's generated code by test 1 on the x86 PR build and the Arm runner, by test 2's vectors and
through the engine (test 3), with the log family's null at Spark's thresholds and `asinh` and
`acosh` making one call a lane; every planted fault of test 4 fails its check and the completeness
assertion of test 5 holds; the coverage rows and productions exist; the baseline addition is
committed and each fusing pull request's run against it is committed at 256 and 128 bits on the
EPYC 7763 with the predictions scored; each built port matches `StrictMath` and its `vfdlibm`
twin bit for bit, with its speed against `LANE_CALL` recorded on the EPYC runners and `mathPort`'s
default holding exactly the ports that measured faster there; and the PR build and the option
matrix with `mathPort` drawn pass.

## 10. Outcome

(Written when the work lands.)
