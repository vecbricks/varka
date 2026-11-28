# VARKA-356: Spark's math family, bit for bit: trigonometric, hyperbolic, exp, log, pow, roots

*Changed on 28 November 2026: section 12 is new (what is left of row 356 for the close, cut
into parts). Sections 0 to 11 are unchanged, word for word. Rows 357 and 358 have plans of their
own from that day, `VARKA-357.md` and `VARKA-358.md`, which hold what is left of them.*

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

*27 November 2026, by the owner's word:* `pow` is built before `hypot`, so items 4 and 5 change
places; the parts keep their names, 358f for `pow` and 358e for `hypot` (section 11, the
decision at its end).

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
   carries them (a NaN a function creates from an out-of-range input, `asin(2)` or `log(-1)`, can
   carry host-dependent bits even from `StrictMath`, `VARKA-587.md` 6.4, so such a created NaN is
   compared as any NaN while a payload passed through from the input stays compared bit for bit); and each fdlibm threshold of the function's C source. For `exp` that is
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

*Built 20 November 2026, while the owner was away, on branch `varka-357-math-baseline` off
master, as its own pull request (open point 2's (b), 345's baseline having merged with its twelve):
the nineteen sections join `VarkaMathBaselineBenchmark` after the twelve, every one `NONE` on
master. The table gains `u` in `(-1, 1)` for `asin`, `acos` and `atanh`, `c` in `(1, 3)` for
`acosh` and as `log(x2, x)`'s base (`log(c, p)`), and `q`, `p` negated at one row in ten, for
`ln(q)`, each with a null column at rows of its own; `log(2.0, x)`, `ln` and `log1p` read `p`, the
positive column, as 345's logarithms do, and `pow(x, 3.0)` reads `p` as `pow(p, 3.0)`. A section
records the rows in ten its function nulls by its domain, which the queries suite adds to the
expected nulls, one for `ln(q)`. `u` and `c` are offset by half a step so that neither can reach an
endpoint that answers infinity. `sql/Test/compile` is clean (749 s), and the queries suite passes
after the master merge of 20 November. Owed: the EPYC 7763 runs at 256 and 128 bits, whose results
replace the committed baseline files at both widths; until those land, rows 356 to 358 cannot
score their predictions against the nineteen.*

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
   `atan` and `atan2`, 356c `asin` and `acos`, 358f `pow`, 358e `hypot` (*until 27 November
   2026:* 358e before 358f).
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
   *Answered 2 November 2026 by the owner: the recommendation; `VARKA-345.md` 2.2 carries the
   dated note.*
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

### 10.1 356a1: the one-argument functions in `LANE_CALL` (20 November 2026)

Built on branch `varka-356-trig` ahead of 345b and 358a. `MathFn` gains `SIN`, `COS`, `TAN`
(`java.lang.Math`) and `ASIN`, `ACOS`, `ATAN` (`java.lang.StrictMath`), each naming the static
`(D)D` method its call invokes. The emitter unrolls a call as `v.lane(k)`, the method and
`v.withLane(k, r)` over the baked lane count (`emitMathLaneCall`); `VarkaKernelPlan` refuses a call
at a width with no baked lane count (`Kind.MATH_CALL`), and `VarkaEmitBudget` weighs it as 16 lane
ops. `VarkaMathCompiler` lowers the six to the call and `sec`, `csc`, `cot` to `1.0 / call`;
`atan2` and rows 357 and 358 decline as "... is row 35x's: not fused yet". A test-only emit option,
`VarkaEmitOptions.FloatingFault.MATH_OTHER_CLASS`, plants the other class's method.

**Tests.** `VarkaMathExactSuite` (own JVM) compares 2^24 sampled inputs a function over five draws
and fixed vectors (21 edges, the infinities, nine NaN payloads) with Spark's code as scalar Java,
by raw bits; the planted other-class call fails it for `sin`, `cos`, `tan` and their reciprocals on
x86; and a warmed kernel allocates nothing a batch. `VarkaMathCompilerSuite` holds the fused and
the declined tables. Coverage gains "Math functions" (11 rows) and one condition row. The regen
wrote coverage, docs, emitted bytes, width audit and census; no existing shape's bytes moved
(prediction 1 held). The audit's `-XX:UseAVX=2` rerun timed out under load and is owed.

**Review** (`/code-review high`): `skips.tsv` lists the math rows of the arms with no baked lane count.
**Left of 356a:** `atan2` (356a2), the IR fuzz productions and the runner measurement.

### Decisions taken while the owner was away (20 November 2026)

1. **356a before 345b and 358a**: of 345b only the `LANE_CALL` lowering.
2. **The call is unrolled over `lane` and `withLane`, not 2.2's scratch loop**: the price is a
   baked lane count; the scratch loop is the one to measure.
3. **The `PORT` arms are the next part.** Each fdlibm port (`asin`, `acos`, `atan`, `atan2`) is
   ported to `vfdlibm` from its committed C, never the JDK, any departure proven in `PROOFS.md`,
   and Varka's kernel is cross-checked lane for lane against `VFdlibm.<fn>`. Varka's side needs
   345b's `PORT` frame and the `exp` template first.

### 10.2 358a1: the exponentials, logarithms and `cbrt` in `LANE_CALL` (20 November 2026)

Built on branch `varka-358-math-family`, stacked on `varka-356-trig` (356a1's `LANE_CALL`
lowering), while the owner was away; row 358's decisions are listed below, apart from 356's.

**As built.** `MathFn` gains `EXP`, `EXPM1`, `LOG`, `LOG10`, `LOG1P` (`java.lang.StrictMath`, the
class Spark's code names) and `CBRT` (`java.lang.Math`); each lowers through 356a1's unrolled
call with no emitter change beyond the operator switch. The log family's null (2.3) is
`VarkaMathDomain.nullAtOrBelow`: `IfElse(Compare(IEEE_LE, x, t), NullValue(DOUBLE),
MathUnary(fn, x))`, `t` a literal slot holding `0.0`, or `-1.0` for `log1p`. `IEEE_LE` is Java's
`<=`, so `-0.0` is null and NaN reaches the call, as in Spark (item 0.2). `VarkaMathCompiler`
lowers `exp`, `expm1` and `cbrt` to the call, and `ln`, `log10` and `log1p` to the call under the
null. `log2` is `DIV WRAP(ln, StrictMath.log(2))`. `log` with `EulerNumber` on the left is `ln`,
with no divide, as Spark's code generator writes it. With a foldable base it is `ln(x)` divided
by `StrictMath.log(b)` folded, and a base at or below `0.0`, or null, declines by name. That
includes the optimizer's `Literal(Math.E)`, which Spark's code then divides by too. With a column
base it is `DIV WRAP(ln(x), ln(b))`, null where either is. `pow` and `hypot` still decline as
"... is row 358's: not fused yet". A test-only `FloatingFault.MATH_DOMAIN_LT` plants `<` for
`<=`.

**Tests written.** `VarkaMathExactSuite` covers the 18 functions and compositions of rows 356 and
358. It now reads the output validity in both checks: a lane Spark nulls must be null, every
other lane valid with Spark's bits. It gains a second column for `log(b, x)`, Spark's order
`log(x) / log(b)`. Its own draws are `[-750, 712]` for the exponentials, and for the log family
subnormals, `[0.5, 2]`, the non-positive half and both zeros, with the same about `-1` for
`log1p`. The fixed vectors gain `exp`'s overflow, subnormal and underflow edges, `2.0` and `0.5`.
The other-class plant is expected to fail for `exp`, `ln`, `log10`, `cbrt` and the compositions
over `ln`, and to change nothing for `expm1` and `log1p` (item 0.3). The new `MATH_DOMAIN_LT`
plant must fail the fixed vectors for every function with the domain null and move no other
function. `VarkaMathCompilerSuite`'s fused table gains the nine functions, `log(2.0D, x)`, `log(y, x)` and
`exp(i)`, and a constant base at or below `0.0` declines by name. Coverage gains 11 "Math
functions" rows. `VarkaMathBaselineBenchmark` marks `cbrt`, `exp`, `expm1`, `log`, `log10`,
`ln`, `log1p`, `log2`, `log(2.0, p)`, `log(c, p)`, `ln(q)` and `exp(x) * 2 + 1` `WHOLE`; 358's eight census lines wait for the regeneration (decision 5).

**Run in the window**: `catalyst/Test/compile` and `sql/Test/compile`, both clean, and one
targeted run of `VarkaMathExactSuite` and `VarkaMathCompilerSuite`: 11 tests, all passed
(the exact suite's five in 443 s, the compiler suite's six in 20 s, at a load average of 31
from other worktrees' jobs). Owed:
`dev/varka_regen_generated.sh --all` (coverage, docs, emitted bytes, width audit, census), the
`-XX:UseAVX=2` width audit, the coverage and Spark-level differentials, the fuzz, the option
matrix, the Arm runner's cell and every runner measurement of section 4.

**Predictions.** 1 (no existing shape's bytes move) holds by construction: no emitter path
changed, and only new shapes are added. It is unchecked until the regeneration. 2 to 6 wait for
the runner. 7, the suite's time, was scored at 33 s for nine functions in 356a1. For 18 it is
predicted at 70 s, twice that, since the sampled differential is linear in the functions.
Measured: 443 s on a laptop at a load average of 31, so the prediction missed by six times. The
load explains some of it: 356a1's 33 s was measured on a quieter machine. The other-class plant
also reruns the sampled differential for all 18 functions. A quiet rerun is owed before this
joins the calibration table.

**Left of 358a.** `pow` and `hypot` (358a2: the `MathBinary` node, which 356's `atan2` needs
too, with the registry suites a new node owes); the IR fuzz productions; the vectors files; the
warm-up reach and cost register entries; the runner measurement.

**Rebuilt on master (22 November 2026).** 356 merged as #1567 in a different form from the
branch 358a1 stood on, so 358a1 was rebuilt on master as branch `varka-358a-r`: the plant moves
into `VarkaEmitOptions.FloatingFault.MATH_DOMAIN_LT`, read from the compiler's options, since
master has no `VarkaMathFault`; the coverage rows carry master's math cells; the benchmark's
newer sections over these functions turn `WHOLE`; `skips.tsv` gains the 11 rows under
`validityByWidth=false` and `lanesOverride=16`. The high review found that a constant base
whose evaluation throws failed the query at planning; it now declines. Owed: the regeneration,
the targeted suites, the gate and every runner measurement.

### Decisions taken while the owner was away (20 November 2026), row 358

1. **358a1 is stacked on 356a1, not on 345b.** The 345b branches add the bit view and `exp`'s
   `PORT` expansion (`VarkaMathPort`) with no `MathFn` member and no compiler arm. 358a's
   `LANE_CALL` form needs only 356a1's lowering, so the stacks are disjoint. 358b, which measures
   the port against this form, needs both.
2. **The log family's null is composed from existing nodes, not the `nullAtOrBelow` field of
   open point 1 (a).** That recommendation rested on the call skipping null lanes by itself, and
   on (b) needing a new node with its own production and reference case. Neither holds as built.
   356a1's lowering calls every lane (`emitMathLaneCall`), so the field would skip
   nothing. And `IfElse`, the IEEE
   `<=` and `NullValue` exist already, so the composition adds no node, no emitter path, no
   reference case and no shape-key field. Admission is the same at two lanes: the two-lane
   refusal is a filter root's (`VarkaKernelPlan.hostRefusal`), and a projection over a
   `NullValue` fuses there, as `if(x IS NULL, x2, NULL)` does. If the live-word narrowing of 345b
   (2.9) lands, it can read the `IfElse`'s mask, or the field can replace the composition then.
3. **`pow` and `hypot` are the next part, 358a2**, on a branch stacked on this one: `MathBinary`
   is a new sealed record, which every exhaustive switch over the IR and the registry suites
   must take, and this part's size leaves no room for it.
4. **A foldable base is folded by Catalyst's own `eval`** at compile time, the double Spark
   computes a row, and a null base declines with every row null. 2.1's rule covers the literal
   base; the generalisation covers `log(2, x)`, whose int literal arrives under a cast.
5. **The census lines' rewrite to `done` waits for the regeneration** (open point 5). The
   pre-commit hook runs `dev/varka_census.py --check`, which fails a curated `done` line until
   `coverage.json` holds a row with the class, and regeneration is not run in this window. The
   eight lines are written (`double|done|358` with the form named) and are committed with the
   regenerated files.

## 11. What `vfdlibm` 0.2.0 to 0.3.1 fixes and measures, as constraints on the ports (27 November 2026)

`vfdlibm` released 0.2.0 on 22 November, 0.3.0 on 25 November and 0.3.1 on 27 November 2026, and
holds all thirteen functions. Each item below is a fix or a measurement of the library that a
port of 2.6 takes in. A port's pull request cites the item it follows. The commit and file names
are `vfdlibm`'s, at tag `v0.3.1`.

1. **`hypot` orders its arguments by magnitude; the C alone is not `StrictMath`** (358e;
   vecbricks/vfdlibm#173 and #174, vecbricks/varka#1691). Line 60 of `e_hypot.c` swaps the
   arguments by their high words alone. Where the high words are equal and `|y| > |x|`, the C
   keeps `a = x`, and lines 101 to 108 split the low words the other way round. `StrictMath.hypot`
   on JDK 17, 21 and 25 equals the C called with the larger magnitude first. The library's
   nightly fuzz found the first pair after about 5 * 10^9 draws: `hypot(0x5f201b45bbc5eac3,
   0x5f201b45fffffffe)` is `0x5f26c7304042d063` by `StrictMath` and `...d062` by the C as written.
   So the port compares the sign-cleared 64-bit patterns as signed longs and swaps on that; every
   line after the swap is the C's. Lemma L21 of `PROOFS.md` proves that the two swaps agree where
   the high words differ; that `StrictMath` equals the C on `(max, min)` is observed, not proven.
   The port's tests take `hard-cases/hypot-order.txt` (19 base pairs, 152 lines), a planted fault
   that puts the high-word compare back, and a draw of pairs of one high word with both low words
   random, since a patterned draw finds none (18 pairs in 9 * 10^8).
2. **`hypot` has no lane finished by a call** (358e; #186 and #187). The lanes with an infinity or
   a NaN are lines 66 to 70 as blends over line 63's `a + b`: `a` where `a` is an infinity, then
   `b` where `b` is one, in the C's order. Lemma L22 proves the selection, and that under the
   magnitude order only line 69's rule (a NaN beside an infinite `b`) can change a lane. Which NaN
   a vector sum keeps is not specified and is recorded as observed, so the port's test compares
   NaN lanes with `StrictMath` in a JVM per JIT mode at every width, as
   `HypotTest.nanLanesAreStrictMathInEveryJitMode` does, and a lane of two NaNs with different
   bits is compared with the two candidates and not bit for bit. The measurement behind it: with
   the per-lane finish, one pair in 16 special made the library 0.70x to 0.75x of `StrictMath` on
   the EPYC 7763; as blends it reads 1.38x to 1.41x there
   (`bench/results/hypot-epyc7763-2026-11-26.txt`). This is `VARKA-345.md` 2.4's rule with a measured case: a branch that is
   cheap in lanes is blended even when it is rare.
3. **`expm1` and `log1p`: the finish arm is the library's default from 0.3.0** (358c, 358d; #165).
   On the Xeon 8573C and the EPYC 7763, a run and a re-run each, neither the guarded nor the
   blended arm reads lower than the finish by more than 1.3x at any species, and the library's
   rule gives such a tie to the finish. Varka's `expm1` port (#1629) and its `log1p` port emit the
   blended arm, chosen on 20 November because `LANE_CALL` was not on master (`VARKA-345.md` 10.2,
   decision 1). `LANE_CALL` is on master since #1567, so the finish form of both is now buildable
   as the measured alternative. The library's tie says that neither form is expected to win by
   1.3x; the port keeps the blended arm unless Varka's own pair on the EPYC runners says other.
4. **`pow` computes the common path only** (358f; #160). The vector holds lines 210 to 307 of
   `e_pow.c` for a positive normal `x` that is not 1 and a finite `y` with `iy <= 0x41e00000` that
   is not 0, +-1, 2 or 0.5, with `z` inside lines 266 and 272 and a normal result; the C's choices
   (`bp[k]`, `dp_h[k]`, `dp_l[k]`, line 220's `n += 1`, lines 285 to 293 and line 291's sign) are
   blends. Every other lane is finished by `StrictMath.pow`: a special, negative or subnormal `x`,
   a special or huge `y`, overflow, underflow and line 306's subnormal results. Lemmas L19 (the
   guard admits only lanes that take no early return) and L20 carry it. For the port this means
   (a) the guard is L19's, and its two redundant parts are named there; (b) the weak branch of
   owner's decision A3 (`|y| > 2^31`) is outside the vector, on the `LANE_CALL` finish, so the
   port follows the running JDK there and JDK-8326547 does not reach its vector code; (c) the
   exact exponents `y == 2`, `0.5` and `+-1` stay Varka's exact nodes before the port; (d) the
   port's tests take `hard-cases/pow.txt` (4,168 pairs) and the three planted faults
   (`pow.guardAdmitsNegativeX`, `pow.guardMissesSubnormal`, `pow.dropT2`).
5. **The finish writes its first eight lanes with constant indices** (every port with a finish;
   0.2.0). At `DoubleVector.SPECIES_MAX` a finish at a variable index allocated 30,432 to 36,992
   B/op on mixed data in the library; with constant indices every row reads under 0.1 B/op. The
   kernel's `LANE_CALL` finish is checked for the same thing: the port's benchmark records the
   allocation of a warmed pass on mixed data (`VarkaMathPortBenchmark`'s note), and a finish
   that allocates is a finding of its pull request.
6. **Words in long lanes are proven per function** (every port; #133, #147, #155). The library
   keeps the C's int words in int lanes, and Varka's kernel keeps them in long lanes with signed
   compares of the raw bits. `PROOFS.md` has a lemma for that form of `expm1` (L15), `log10`
   (L17) and `log1p` (L18, with hypotheses H19 and H20 on the lanes that must be blended over).
   Each later port whose kernel departs from int words asks for its lemma in the paired `vfdlibm`
   pull request, before the Varka pull request merges.
7. **The C2 code of every arm is asserted in the library** (#139, #153): the lane width, no FMA,
   no scalar arithmetic and no call, at 128, 256 and 512 bits. A port's kernel is read the same
   way once, by `dev/varka_emit.sh --asm`, and its pull request states the result.
8. **Hard cases from CORE-MATH** (358e, 358f, and row 367; #144, #167).
   `hard-cases/hypot-coremath.txt` (20 source pairs, 160 lines) and `hard-cases/pow-coremath.txt` (848 pairs) are
   selected from CORE-MATH's published worst-case lists (MIT). The whole lists match `StrictMath`
   in the library, so they are fixed vectors and not a fix. The ports' vectors files
   (`sql/varka/math/`) take them with their headers, source and licence notice.
9. **Speed, as a prior and not a result.** The library's own table for 0.3.1, `StrictMath` once a
   lane over the default arm at the preferred species, common and mixed data:

   | function | Xeon 8573C (AVX-512) | EPYC 7763 (AVX2) |
   |---|---|---|
   | `expm1` | 4.03x / 3.03x | 1.90x / 1.74x |
   | `log` | 4.12x / 2.38x | 2.44x / 1.97x |
   | `log10` | 4.41x / 2.76x | 2.30x / 1.86x |
   | `log1p` | 3.39x / 2.81x | 1.95x / 1.56x |
   | `sinh` | 3.53x / 2.74x | 2.12x / 1.68x |
   | `cosh` | 3.49x / 1.84x | 2.20x / 1.71x |
   | `asin` | 2.31x / 2.08x | 2.07x / 1.52x |
   | `atan` | 3.48x / 2.30x | 1.77x / 1.51x |
   | `acos` | 2.21x / 1.90x | 1.99x / 1.36x |
   | `atan2` | 2.72x / 2.08x | 1.72x / 1.55x |
   | `hypot` | no file of this code | 1.33x / 1.38x |
   | `pow` | 11.79x / 7.43x | 4.88x / 4.28x |

   These time the library's loop with its bodies inlined, at four or eight lanes. They do not
   time a kernel, and at two lanes row 635 measured both the library and the kernel several
   times slower than `StrictMath` (row 636). Each port still registers its own prediction and is
   the default only where its kernel measures faster on the EPYC runners. What the table changes
   is the expected value of each port: `pow` has the largest gain on both hosts and is last in
   2.6's order, and `hypot` and `acos` have the smallest on AVX2.

**The order of `pow` and `hypot`, the owner's decision of 27 November 2026.** 2.6 put `pow` last
because it is the largest port and gains most from the others' lessons. The library is done, so
those lessons exist, and `pow` is the port with the largest measured gain. The owner decided:
`atan` to `acos` keep their order, and the `pow` port (358f) is built before the `hypot` port
(358e).

## 12. What is left of row 356 for the close, and how it is built (new, 28 November 2026)

*This section is new and stands alone for a builder. Rows 357 and 358 now have plans of their
own, `VARKA-357.md` and `VARKA-358.md`; sections 0 to 11 above stay as the three rows' shared
design and record. The code facts below were checked on master `6dafb8bd2f6`. No JVM was started
for this section.*

*Revised the same day after review 1: 12.1's table (356b waits on nothing), 12.5 (step 4's
`operands`, step 6's line, command 2), 12.6 (356c's oracle, 356b's start), 12.7 and 12.8.*

### 12.1 What the close asks of row 356

Item 5 of `m8/PLAN.md` 1.1 is read by the close path (12.5, decision 3 of 27 November 2026) as
met by the per-lane calls and the ports that exist, and "the per-lane calls the item asks for
and master lacks stay owed here: `atan2` (356)". Nine of the row's ten functions fuse since
#1567. So the row owes:

| part | what | counted lines | builder | waits on |
| :--- | :--- | ---: | :--- | :--- |
| 356a2 | `atan2` as `Math.atan2(y + 0.0, x + 0.0)` over the two-operand call node | about 300 | Sonnet-class | 358a2 of `VARKA-358.md`, which adds `MathBinary` |
| 356c | the exact suite on the Arm runner | about 25 | Sonnet-class | 356a2 |
| 356b | `asin`, `acos` and `atan` call `Math`, as Spark's code does | about 40 | Sonnet-class | nothing: it starts from master, built by the default of 12.7, question 1 |

The runner measurement of `atan2`'s section is part 358r of `VARKA-358.md`.

Not owed for the close and not built from this section (defaults; 12.7 asks the owner): the
`PORT` arms of `atan`, `atan2`, `asin` and `acos` (`m10/SCOPE.md` item 140, the owner's decision
3); the call that skips null lanes ("the live-word narrowing"); the IR fuzz productions and the
vectors files (`VARKA-358.md` section 10, questions 3 and 4); the opt-in entries, which wait on
row 367 in milestone 10.

### 12.2 What a builder must know

Spark's `atan2(y, x)` generates `java.lang.Math.atan2(c1 + 0.0, c2 + 0.0)`, and evaluates the
same interpreted. It is null only where an operand is null. The `+ 0.0` changes answers: it
turns `-0.0` into `+0.0` before the call, so `atan2(-0.0, -1.0)` is `pi` in Spark and `-pi` by
the bare method, and `atan2(-0.0, -0.0)` is `0.0` and not `-pi`
(`results/VARKA-356-admission.txt` part 6). A kernel must add the zero, never drop it.

The names the part reads, all on master once 358a2 is merged:

| name | where | meaning |
| :--- | :--- | :--- |
| `MathBinary(MathFn fn, VarkaVectorIR left, VarkaVectorIR right)` | `VarkaVectorIR` | a two-operand per-lane call; null where either operand is |
| `MathFn.binary()` | `VarkaVectorIR` | true for a member a `MathBinary` takes |
| `IntArith(IntOp.ADD, Overflow.WRAP, a, b)` | `VarkaVectorIR` | the IEEE sum at the double lane |
| `VarkaMathDomain` | catalyst main | a tree builder the compiler and the suite share |
| `VarkaSameAnswer`, `Step.Arith(left, right, true)` | catalyst main | a NaN made from two NaN operands of different bits may carry either payload |
| `FloatingFault`, `DeclineSink.floatingFault()` | `VarkaEmitOptions`, `DeclineSink` | the planted faults, read by the compiler |

A node holds no literal value, only a literal slot's index, so the emitter cannot fold `v + 0.0`
away: it does not know the slot holds a zero.

### 12.3 The admission check

1. `grep -c 'Map.entry(Atan2.class, new Owner("atan2", 356))'` on `VarkaMathCompiler.java`
   prints `1`: `atan2` declines today.
2. `grep -c 'record MathBinary'` on `VarkaVectorIR.java` prints `1`. On master `6dafb8bd2f6`
   it prints `0`: 358a2 is not merged, and this part waits.
3. `grep -c -F 'java.lang.Math.atan2($c1 + 0.0, $c2 + 0.0)'` on `mathExpressions.scala` prints `1`.
4. On JDK 25 `Math.atan2` is `StrictMath.atan2` at every tier: the intrinsic's name exists and
   C2 refuses it.

        grep -n -B1 -A1 'case vmIntrinsics::_datan2:' /home/max/proj/openjdk-build/jdk25/src/hotspot/share/opto/library_call.cpp | grep -c 'not yet correctly implemented\|return false'

   prints `2` (the comment and the `return false` of the second site).
5. `git branch --list 'varka-356a2*' 'varka-356b*' 'varka-356c*'` prints nothing. No branch
   holds `atan2`: `varka-356-trig` is the merged #1567's branch, 27 commits that master has
   in squashed form, and no part starts from it.

### 12.4 The design of 356a2

**The member.** `ATAN2("java/lang/Math", "atan2", true)`, after `HYPOT`. It names the class
Spark's generated code names (`VARKA-358.md` 4.1 gives the reason and the evidence: a method
that delegates to `StrictMath` on JDK 25 can be a host routine on the next JDK).

**The tree.** In `VarkaMathDomain`, beside `nullAtOrBelow`, shared by the compiler and the suite:

    /**
     * Spark's {@code atan2}: {@code Math.atan2(y + 0.0, x + 0.0)}. {@code zero} is a
     * {@code DOUBLE} literal slot holding {@code 0.0}; the sum turns {@code -0.0} into
     * {@code +0.0} before the call, as Spark's code does, and
     * {@link FloatingFault#MATH_ATAN2_BARE} leaves it out.
     */
    public static VarkaVectorIR atan2(VarkaVectorIR y, VarkaVectorIR x, VarkaVectorIR zero,
        FloatingFault fault) {
      if (fault == FloatingFault.MATH_ATAN2_BARE) {
        return new MathBinary(MathFn.ATAN2, y, x);
      }
      return new MathBinary(MathFn.ATAN2, new IntArith(IntOp.ADD, Overflow.WRAP, y, zero),
          new IntArith(IntOp.ADD, Overflow.WRAP, x, zero));
    }

**The compiler arm**, in `VarkaMathCompiler.arm`:

    case Atan2 f -> () -> atan2(f, inputs, literals, sink);

    /** Spark's {@code atan2(y, x)}: the per-lane call over each operand plus {@code 0.0}. */
    private static Optional<VarkaVectorIR> atan2(Atan2 f, VarkaSlotTable inputs,
        VarkaSlotTable literals, DeclineSink sink) {
      Optional<VarkaVectorIR> y = operand(f.left(), inputs, literals, sink);
      if (y.isEmpty()) {
        return y;
      }
      return operand(f.right(), inputs, literals, sink).map(x -> VarkaMathDomain.atan2(y.get(),
          x, VarkaNodeCompiler.doubleSlot(0.0, sink), sink.floatingFault()));
    }

**The special cases** (each is the method's own answer once the zeros are added; the pair test
of 12.5 holds each):

| `y`, `x` | Spark's answer | what goes wrong without the `+ 0.0` |
| :--- | :--- | :--- |
| `-0.0`, `-1.0` | `pi` | `-pi` |
| `-0.0`, `-0.0` | `0.0` | `-pi` |
| `+0.0`, `-0.0` | `0.0` | `pi` |
| `-0.0`, `+1.0` | `+0.0` | `-0.0` |
| `+-1.0`, `+-0.0` | `+-pi/2` | the same |
| `+-inf`, `+-inf` | `+-pi/4` or `+-3pi/4` | the same |
| a NaN and a number | the NaN, quieted by the sum | the same payload |
| two NaNs of different bits | a NaN, either payload | 12.4's last paragraph |
| a null in either operand | null | |

**Two NaN operands.** fdlibm's `atan2` answers `x + y` where either operand is a NaN. With two
different NaNs the payload of that sum is the processor's rule over the order the JIT gave the
operands, and the interpreter and C2 differ (`VARKA-358.md` 4.4, measured for `hypot`). The IR
front of `VarkaSameAnswer` already reads every `MathBinary` as `Step.Arith(left, right, true)`.
This part adds `Atan2` to the Catalyst front beside `Pow` and `Hypot`, so that the coverage
differential opens the same lanes.

### 12.5 The build sheet of 356a2

- **Title:** `[VARKA-356] Fuse atan2 bit for bit, with Spark's + 0.0 on both operands`
- **Branch:** `varka-356a2-atan2`, from master after 358a2 merged.

**B0.** 12.3's lines 1 to 3 print `1`, and line 5 prints nothing.

Every sbt line is run as

    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1 build/sbt '<task>'

**Steps.**

1. `VarkaVectorIR.MathFn`: the member of 12.4; the enum's Javadoc gains a sentence on it.
   `VarkaEmitOptions.FloatingFault`: `MATH_ATAN2_BARE`, with the comment "Spark's `atan2`
   without its `+ 0.0`: `atan2(-0.0, -1.0)` answers `-pi` where Spark answers `pi`."
2. `build/sbt catalyst/Test/compile` names the switches that lack `ATAN2`:
   `VarkaVectorWalk.emitMathUnary`'s arm that throws "is a per-lane call", and in
   `VarkaReference` the two-operand evaluator 358a2 added (`case ATAN2 -> Math.atan2(a, b);`,
   by name) and the two one-operand switches' throwing arms.
3. `VarkaMathDomain.atan2` and the compiler arm (12.4); `VarkaSameAnswer`'s Catalyst front
   (`if (n instanceof Atan2 a) { return new Step.Arith<>(a.left(), a.right(), true); }`).
4. `VarkaMathExactSuite`:
   - the cell, in `functions(fault)` after `hypot`:

            new Fn("atan2", VarkaMathDomain.atan2(X, B, lit(0), fault), bits(0.0), 2,
                (c, b) -> Math.atan2(c + 0.0, b + 0.0), Domain.PLAIN, false, false),

     `X` is `y` and `B` is `x`, Spark's order.
   - the row helper `operands`. Part 358a2 added it to this suite, directly after `mismatch`;
     its whole text is `VARKA-358.md` 5.1, step 7, piece f. Its signature is
     `private static VarkaSameAnswer.IrRow operands(VarkaVectorIR root, double want, double x,
     double b)`: it returns the row `VarkaSameAnswer` reads, an object with two methods,
     `OptionalLong value(VarkaVectorIR node)` and `boolean holds(VarkaVectorIR.Cond cond)`.
     As 358a2 leaves it, `value` answers only the root and the two columns. `atan2`'s operands
     are the two sums, so `value` becomes (the two `if` statements are new, the `return` is
     358a2's):

            @Override public OptionalLong value(VarkaVectorIR node) {
              if (node instanceof IntArith sum && sum.left().equals(X)) {
                return OptionalLong.of(Double.doubleToRawLongBits(x + 0.0));
              }
              if (node instanceof IntArith sum && sum.left().equals(B)) {
                return OptionalLong.of(Double.doubleToRawLongBits(b + 0.0));
              }
              return node.equals(root) ? OptionalLong.of(Double.doubleToRawLongBits(want))
                  : node.equals(X) ? OptionalLong.of(Double.doubleToRawLongBits(x))
                  : node.equals(B) ? OptionalLong.of(Double.doubleToRawLongBits(b))
                  : OptionalLong.empty();
            }

     `holds` stays as it is. B0 of this part already asks that `MathBinary` is on master; the
     helper came with it (`grep -c 'private static VarkaSameAnswer.IrRow operands'` on the
     suite's file prints `1`).
   - a test of the zero and sign cells, which the reversed second column of the fixed vectors
     never pairs. It runs these 22 pairs `(y, x)` through the kernel at 2 and 8 lanes
     (`VarkaMatrixRuns.BASE.withLanesOverride(lanes)`, the columns built as
     `fixedVectorFailures` builds them) and asserts `mismatch(...)` is null for each: every
     pair of `{+0.0, -0.0}` with `{+0.0, -0.0, +1.0, -1.0, +inf, -inf}` (12 pairs), the same
     with the operands swapped for the four non-zero values (8 pairs), and `(+inf, -inf)` and
     `(-inf, -inf)`. Its display name: "atan2 answers Spark's bits on every pairing of the
     zeros with the zeros, the ones and the infinities".
   - a test of the plant, in the form of `domainAtLessThanIsCaught`: the same pairs through
     `functions(FloatingFault.MATH_ATAN2_BARE)`'s `atan2` cell must give at least one
     mismatch, and the first must name `atan2(-0.0` .
5. `VarkaMathCompilerSuite`: `atan2(y, x)` moves from `FUNCTIONS` to `FUSED`.
   `VarkaCoverageSuite`, family "Math functions":

        Row("atan2(x, x2)", "Math.atan2(x + 0.0, x2 + 0.0) as Spark writes it: the sums make "
          + "a -0.0 operand +0.0 before the call",
          math = Some(MathCell(exactForms(2), operator = Some(VarkaMathBinding.Operator.ATAN2)))),

6. `skips.tsv`: two lines for the row, copied from the lines of
   `Math functions: expm1(x) matches the row engine under both consumers`, the function list
   `(atan2)` and the row `(VARKA-356)`. The file is tab separated, five fields a line; the
   first of the two lines, with `<TAB>` standing for one tab character:

        validityByWidth=false<TAB>VarkaCoverageHarnessSuite<TAB>Math functions: atan2(x, x2) matches the row engine under both consumers<TAB>fails<TAB>validityByWidth=false leaves no baked lane count, and a per-lane math call (atan2) is unrolled over the baked double lanes, so the compiler declines the row (VARKA-356); the row engine answers it

   `VarkaMathBaselineBenchmark`: `atan2(Y, X)` becomes
   `Fusion.WHOLE`. `census_curated.txt`:

        atan2|double|double|double|done|356|exact: a per-lane Math.atan2 call over y + 0.0 and x + 0.0, as Spark writes it (VARKA-356)

7. This file: 12.8's entry; `PLAN.md`: row 356's text opens as 12.8 gives it.

**The commands, in order.**

1. `catalyst/Test/compile`, then `sql/Test/compile`: `[success]`.
2. `catalyst/testOnly *VarkaMathExactSuite *VarkaMathCompilerSuite
   *VarkaSameAnswerContractSuite`: `Failed 0, Errors 0`; the exact suite runs its count before
   the part plus 2 (step 4's two tests), the other two suites their counts before the part.
   The count before is `grep -c '^  @Test$' <the suite's file>`, read before the first edit;
   it depends on which parts of rows 357 and 358 merged first, so no number is written here.
3. `catalyst/testOnly *VarkaCoverageSuite`: `All tests passed.`
4. `dev/varka_matrix.sh --config validityByWidth=false --config lanesOverride=16 --suites
   VarkaCoverageHarnessSuite,VarkaCoverageDifferentialSuite`: no failing test, no stale line.
5. `dev/varka_ops/pre-pr-slot.sh <worktree>`: `pre-pr: passed on <sha>`; in the regenerated
   files only `atan2`'s row, shape and census line move.

**Done when** merged with a green Build and a green option matrix; `VarkaMathCompilerSuite`'s
`FUNCTIONS` no longer holds `atan2`; the census reads one more entry fused.

**Not here.** No port of `atan2`. No change to `ASIN`, `ACOS`, `ATAN` (356b). No fuzz draw.

### 12.6 The build sheets of 356c and 356b

**356c, `[VARKA-356] Run the math family's exact suite on the Arm runner`** (branch
`varka-356c-arm`). `Math`'s answer is per host, and section 9 asks for the sampled differential
on the Arm runner; today that job runs `VarkaMathBindingSuite` alone.

- B0: `grep -c 'VarkaMathExactSuite' .github/workflows/varka-width-audit.yml` prints `0`.
- Step 1: in `.github/workflows/varka-width-audit.yml`, after the step "Check the math library
  binding", a step "Check the math family's exact forms" with the same `if:` line, a comment
  saying that the kernel's per-lane calls are held to Spark's code on this host and that a
  failure is a finding, and the run lines `set -o pipefail` and
  `./build/sbt "catalyst/testOnly *VarkaMathExactSuite" 2>&1 | tail -60`.
- Step 2: under `on: push: paths:`, add the paths of `VarkaMathExactSuite.java`,
  `VarkaMathDomain.java` and `VarkaMathHyperbolic.java`, so that a change to them runs the job.
- The oracle: the workflow run of the pull request's push, job "Width audit on aarch64", whose
  step prints the JUnit summary. Two things must hold, and the second is what makes the first
  mean anything. First, `Failed 0, Errors 0`. Second, the count of tests that ran: with N the
  number `grep -c '^  @Test$'` prints on `VarkaMathExactSuite.java` at the pull request's head
  (`5` on master `6dafb8bd2f6`, more after the parts of rows 356 to 358), the summary shows N
  tests in total, N minus 1 passed and 1 skipped. The one skip is `otherClassIsCaught`, by its
  own `assumeTrue` on x86. Every other test opens with `assumeTrue(Lane.emitLanes(...) != 0)`,
  so on a runner whose width bakes no lane count the whole suite skips and the step is green
  with nothing checked: any skip beyond the one is red for this part. The one more skip that
  is not red is `noAllocationPerBatch` saying "thread allocation accounting unavailable";
  name it in 12.8 if it shows. Write N and the three numbers into 12.8.
- Registered before the run: the step takes under 30 minutes of the job's 90.
- If the step is red, do not edit the suite: copy the failing lines into 12.8 as a finding and
  report. A red step on Arm is a result about that host, and what to do with it is the lead's.
- Not here: no change to the suite, no new runner.

**356b, `[VARKA-356] Call Math for asin, acos and atan, as Spark's code does`** (branch
`varka-356b-math-owner`, from master; it waits on no other part and on no answer: 12.7,
question 1, applies the default that it is built).

- B0: `grep -c 'ASIN("java/lang/StrictMath", "asin")'` on `VarkaVectorIR.java` prints `1`.
- Steps: the three members' owner becomes `java/lang/Math`; the enum's Javadoc and
  `VarkaMathCompiler`'s say that the call names Spark's class, which is fdlibm's on JDK 25; the
  three coverage rows' notes read "exact: a per-lane Math.asin call, as Spark's code makes"
  (and `acos`, `atan`) with `exactForms(2)`; the three `census_curated.txt` notes the same.
  `VarkaMathExactSuite`'s cells stay as they are: their `classesDiffer` is `false` either way.
- Commands: `catalyst/testOnly *VarkaMathExactSuite *VarkaMathCompilerSuite
  *VarkaCoverageSuite`, then the gate. In the regenerated `emitted_bytes.json` exactly the
  shapes that hold `asin`, `acos` or `atan` move (the class name in their constant pool), and
  no other; say so in the pull request, since "no existing shape's bytes move" is broken here
  on purpose.
- Not here: `SIN`, `COS`, `TAN` and the rest already name Spark's class.

### 12.7 Questions for the owner

1. **Should `asin`, `acos` and `atan` call `Math`, as Spark's code does?** This is one case of
   `VARKA-358.md` section 10, question 1 (which class a call names where Spark calls `Math` and
   JDK 25 delegates to `StrictMath`); the question is asked once, there, and this entry only
   says what it means for this row. (a) `Math`, part 356b: exact on every JDK. (b) Keep
   `StrictMath` and rely on the build requiring JDK 25. Evidence for (a): the JDK 26 runtime
   on this laptop holds an intrinsic for `Math.sinh` that JDK 25 lacks (`VARKA-358.md` 4.1),
   so delegation is a fact of one JDK; and JDK 25's own source already names an `atan2`
   intrinsic it does not yet implement (12.3, line 4). *Recommended: (a).* **Answered on 28 November
   2026: (a). The new member `ATAN2` names `Math`, and 356b is built.**
2. **Is the Arm runner's cell (356c) part of the close?** Section 9 asks it; item 5 of 1.1 does
   not name a host. (a) Yes, 356c. (b) No, a milestone 10 row. *Recommended: (a)*: it is one
   workflow step, and `tan`, `tanh` and `cbrt` are other routines on aarch64. **Default applied:
   (a).**
3. **The call that skips null and masked lanes** (2.2's live word). *Recommended: not in this
   milestone*; the bits do not depend on it. **Default applied: not built.**

### 12.8 Outcome of the parts of this section

To be written by each part. 356a2 opens row 356's text in `PLAN.md` with: "**Done in part**
(*<date>*: 356a2 merged as #<n>: `atan2` fuses as `Math.atan2(y + 0.0, x + 0.0)` over the
two-operand call node, bit for bit, a lane of two different NaNs open; all ten functions of the
row fuse; left: 356c, the Arm runner's cell, 356b unless it merged first, and the runner
measurement, part 358r of `VARKA-358.md`; moved to `m10/SCOPE.md` item 140: the four ports)".
356c writes here the test counts its oracle asks for. 356b writes here which shapes' bytes
moved.
