# VARKA-30: The integral division family

## 0. What exists now (admission check)

From master at `ef32e007d3a` (30 October 2026; Varka paths under `sql/catalyst/src/main/java/org/
apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark's under `sql/catalyst/src/main/
scala/org/apache/spark/sql/catalyst/` unless named, `arithmetic.scala` being `expressions/
arithmetic.scala`). The row's text is the milestone plan's row 30, its 2.9 bullet "30." and its 10.1
item 4; the G1 wave row puts it after 104, 28, 329 and 341, its form per host by 343 and 376. The
JDK was read in `/home/max/proj/openjdk-build/jdk25`, the paper in its transcription beside the PDF
(`READING_M8_PAPERS.md` 1), checked against the PDF where an operation decides something.

**Spark's answers.** Each is what the kernel reproduces.

1. **`div` returns `BIGINT`, over two widening casts.** `IntegralDivide.dataType` is `LongType`
   (`arithmetic.scala:973`) and its `inputType` is `LongType`, decimal and the two interval types
   (`:970-971`); `IntegralDivisionTypeCoercion` casts a `TINYINT`, `SMALLINT` or `INT` operand to
   `LongType` (`analysis/IntegralDivisionTypeCoercion.scala:29-38`). So `int div int` is
   `CAST(a AS BIGINT) div CAST(b AS BIGINT)`, and `Int.MinValue div -1` is `2147483648`, a long.
   The overflow check is armed on `left.dataType == LongType` under `failOnError`
   (`arithmetic.scala:965-968`) and tests `input1 == Long.MinValue` (`:721-723`, codegen
   `:784-790`), which a widened int never is.
2. **`%` keeps the dividend's sign and its type.** `Remainder.dataType` is the left operand's type
   (`BinaryArithmetic.internalDataType`, `arithmetic.scala:218-222`, with no coercion of its own),
   so `int % int` is an `INT` and `tinyint % tinyint` a `TINYINT`. The integral arm is
   `integral.rem` (`:1101-1103`), Scala's `Integral[Long]` and kin (`types/PhysicalDataType.scala:
   269`), and codegen is `(javaType)(a % b)` (`:784`): Java's truncated remainder, so
   `-7 % 2 = -1`, `7 % -2 = 1`, and `MIN % -1 = 0` at every width.
3. **`pmod` is `MathUtils.pmod`, wrap included.** `Pmod` extends `BinaryArithmetic` directly
   (`arithmetic.scala:1140-1144`), its integral arms call `MathUtils.pmod` (`:1183-1189`), which is
   `r = a % n; if (n > 0) r + (n & (r >> 31)) else if (r >= 0) r else (r + n) % n` for `Int` and
   the same with `>> 63` for `Long`, `Byte` and `Short` through `Int` and narrowed back
   (`sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/MathUtils.scala:105-117`). The
   comment above it (`:97-103`) says the `n < 0` path can wrap at `r + n` and names
   `pmod(-1, Int.MinValue) = Int.MaxValue`, tracked by SPARK-59934: `r = -1`, `r + n` wraps to
   `Int.MaxValue`, and `Int.MaxValue % Int.MinValue` is `Int.MaxValue`. A negative `n` gives a
   non-positive result (`pmod(-10, -3) = -1`). Varka reproduces all of it.
4. **A zero divisor is null under LEGACY and TRY, and raises under ANSI; the error class
   differs by operator.** `DivModLike.eval` (`arithmetic.scala:701-727`) returns null for a null
   divisor, or for a zero one when `!failOnError`; otherwise it evaluates the dividend, returns
   null for a null one, and only then raises on a zero divisor - so **a null dividend over a zero
   divisor is null in every mode**. `Pmod.eval` is the same order (`:1194-1211`). `div` raises
   `DIVIDE_BY_ZERO` (`errors/QueryExecutionErrors.scala:210-216`), whose template is "Division by
   zero. Use `try_divide` to tolerate divisor being 0 and return NULL instead. If necessary set
   <config> to \"false\" to bypass this error.", SQLSTATE 22012 (`common/utils/src/main/
   resources/error/error-conditions.json:2475-2480`); **`%` and `pmod` raise
   `REMAINDER_BY_ZERO`** (`QueryExecutionErrors.scala:218-224`), "Remainder by zero. Use `try_mod`
   to tolerate divisor being 0 and return NULL instead. If necessary set <config> to \"false\" to
   bypass this error.", SQLSTATE 22012 (`error-conditions.json:7054-7059`). Each template has one
   parameter, `<config>`, which both constructors fill with `toSQLConf("spark.sql.ansi.enabled")`,
   beside the query context.
5. **TRY exists for `%` only.** `failOnError` is `ANSI | TRY` in `BinaryArithmetic`
   (`arithmetic.scala:224-228`); `Remainder` overrides it to `ANSI` only (`:1058`), and `try_mod`
   is `Remainder(_, _, TRY)` (`expressions/TryEval.scala:179-191`), so `try_mod` behaves as LEGACY:
   null on a zero divisor. No SQL function builds `IntegralDivide` or `Pmod` in TRY mode (`grep`
   of the catalyst sources); if a plan did, the inherited `failOnError` would make it raise. The
   compiler reads the node's `failOnError` rule, not its mode's name, so it follows Spark either
   way.
6. **`Long.MIN_VALUE div -1`** is `Long.MIN_VALUE` under LEGACY (Java's wrap through
   `Integral[Long].quot`, `arithmetic.scala:990-1011`), and under ANSI raises
   `ARITHMETIC_OVERFLOW` through `overflowInIntegralDivideError` (`QueryExecutionErrors.scala:
   344-346`), whose template is "<message>.<alternative> If necessary set <config> to \"false\" to
   bypass this error.", SQLSTATE 22003 (`error-conditions.json:129-134`), its three parameters
   filled as `message` = `Overflow in integral divide`, `alternative` = ` Use 'try_divide' to
   tolerate overflow and return NULL instead.` and `config`
   (`sql/api/src/main/scala/org/apache/spark/sql/errors/ExecutionErrors.scala:124-150`). TRY is
   unreachable (item 5). `Long.MIN_VALUE % -1` and `pmod(Long.MIN_VALUE, -1)` are 0 in every mode.

**Varka today.**

7. **Nothing divides by a column, and nothing fuses `div`, `%` or `pmod`.** The census lists all
   four names unfused, needing "semantics" (`m8/census.tsv`, rows `div`, `%`, `mod`, `pmod`,
   `try_mod`). The IR divides only by a constant: `ConstDivide` (`varka/VarkaVectorIR.java:764`)
   and `BoundedDivide` (`:886`), both built by the time and interval compilers only.
8. **`ConstDivide` is exact where it is admitted, and its long-lane contract is a bound.** At the
   int lane its lowering is the multiply-high through long lanes (`varka/VarkaDivisionLowering.
   java:143-176`, `:309`; proof `sql/varka/proofs/int_mulhi_divide.smt2`), exact for every int;
   at the long lane it is the conversion form, or the magic form where `convertsFallBack()`
   (`:344-362`), and the node refuses a dividend bound above `EXACT_DIVIDEND_BOUND = 2^52`
   (`VarkaVectorIR.java:783`, `:813-860`), refuses a divisor of 0 and of `Long.MIN_VALUE`
   (`:814-826`), and handles `-1` nowhere (the lowering's javadoc: the analysis refuses it). So
   "reuse `emitConstDivide`" over an unbounded `BIGINT` dividend needs a bound the caller proves,
   or a `GuardedRange` that declines a batch past `2^52`.
9. **`BoundedDivide` is two operations over a non-negative bound** (`VarkaVectorIR.java:867-
   895`): a magic pair exists for a dividend under about 46341 (`VarkaChrono.java:46`), so a
   `TINYINT` or `SMALLINT` magnitude takes it.
10. **A mode's null and a mode's raise both have mechanisms.** A NULL-mode `IntArith` narrows its
    own validity word rather than refusing (`varka/Analysis.java:324-331`); a raising lane folds
    into the body's status through `Refusal` (`:303-341`), the last bit `STATUS_VALUE_RANGE = 1 <<
    5` (`varka/VarkaFusedKernel.java:87`; row 347's unmerged branch takes the next one for the
    decimal slot, `VARKA-359.md` 0 item 5). A declined batch is evaluated by the row engine, which
    raises Spark's error. Row 414 found one exception: a batch declined by `IntNeg` FAIL raised an
    error without Spark's `== SQL ==` block (`VARKA-89.md`, "What differed from the plan"; row
    414, open).
11. **The prerequisites.** 28 is done (`WidenLane`, a kernel over both lanes). 104 is planned (the
    long arithmetic arms and the lattice's long half; division moved to this row). 329 has its
    lanes and arms merged (`narrowLanes`, `WIDENED` computing a narrow value on the int lane).
    341 has the floating lane types merged (`VarkaVectorIR.java:86-90`: `FLOAT`, `DOUBLE`) with
    comparisons and choice; floating arithmetic is 342's and the conversion node 343's, both
    planned, unbuilt.

**The JDK.**

12. **Integer vector division is never intrinsic, on any host.** `VectorNode::opcode` maps only
    `DivHF`, `DivF` and `DivD` to vector nodes (`src/hotspot/share/opto/vectornode.cpp:95-100`)
    and returns 0 for anything else (`:287-291`), so `inline_vector_nary_operation` refuses
    `DivI` and `DivL` ("operation not supported", `vectorIntrinsics.cpp:368-372`). `IntVector`'s
    `lanewise(DIV)` then tests for a zero lane and throws (`jdk.incubator.vector/IntVector.java:
    789-794`, masked `:838-844`) and divides lane by lane in Java (`:868-869`); `LongVector` is the
    same (`LongVector.java:747`, `:826`). So the kernel never calls the Vector API's integer
    `DIV`, and every form below divides in floating point or by multiplication.
13. **Floating division, `FMA` and the 32-bit conversions are intrinsic everywhere the census
    reads.** `DivVF`, `DivVD` lower on x86 (`x86.ad:6193-6249`); the census cells `I2D`, `D2I`,
    `I2F`, `F2I`, `D2F`, `F2D` and float and double `FMA` read `I` at 256 and 128 bits on the EPYC
    7763 and at 128 on the Neoverse N2 (`results/VARKA-376-census-epyc-7763.json`,
    `-neoverse-n2.json`).
14. **The 64-bit conversions are the AVX2 gap.** `x86.ad:1757-1777` refuses `VectorCastL2X` to a
    floating type and `VectorCastD2X` to long without AVX512DQ, and the census reads `L2D` and
    `D2L` `F` at 256 and 128 bits on the EPYC 7763 (56 and 80 bytes a row allocated) and `I` on
    the N2. The 64-bit multiply is not a gap: on AVX2 C2 emits `MulVL` as a `vpmuludq` sequence
    (`x86.ad:6071` `vmulL_reg`, `:6096` `vmuludq_reg` for unsigned 32-bit inputs), with no Java
    fallback, and on NEON without SVE it is a per-lane scalar multiply inside the vector code
    (`aarch64_vector.ad:1041-1051`).
15. **The census has no division cell.** It has no `int.DIV`, `float.DIV`, `double.DIV` or
    `long.MUL` cell (`VARKA-376-census-epyc-7763.json`, 85 keys at 256 bits), so a census
    extension comes first, as 329's did.
16. **The paper.** Monniaux and Pain's Algorithm 2 (transcription lines 146-148) is unsigned:
    `bd = (double) b`, `bs = (float) bd`, `invbs0 = 1 / bs` in float, `invbd0 = (double) invbs0`,
    `alpha = fma(-bd, invbd0, 1)`, `invbd = fma(alpha, invbd0, invbd0)`, `q1 = lround(ad *
    invbd0)` unsigned (note `invbd0`, not `invbd`), `r1 = a - b * q1`, `q3 = lround((double) r1 *
    invbd)` signed, `r3 = r1 - b * q3`, `q2 = r3 < 0 ? q3 - 1 : q3`, `q = q1 + q2`, and `b <= 1`
    gives `a`, `b >= 2^63` gives `a >= b`, by conditional moves. Every operation rounds to nearest,
    and its proof splits at `b <= 2^42` (lines 187-189). Three of its operations are not Java's:
    the unsigned `(double) a`, the unsigned and the signed `lround`, and the float reciprocal at
    the double lane's lane count.

### What differs from the row text and the milestone plan

- **`%` and `pmod` raise `REMAINDER_BY_ZERO`, not `DIVIDE_BY_ZERO`** (item 4); only `div` raises
  `DIVIDE_BY_ZERO`. The coverage rows hold each class.
- **TRY is `try_mod` alone** (item 5); `div` and `pmod` have LEGACY and ANSI rows only.
- **The `INT` double route has one unrepresentable quotient.** `Int.MinValue div -1` is `2^31`,
  which `D2I` saturates and `D2L` cannot produce on AVX2 (item 14); the same saturated quotient
  makes `MIN % -1` come out `-1`. Both need a fix-up (2.2).
- **A literal divisor over an unbounded `BIGINT` dividend is not `emitConstDivide` alone** (item
  8): it needs the lattice's bound or a guard past `2^52`. Both of the row's forms are built as
  option arms, the guarded `ConstDivide` the default and the four-`vpmuludq` form, the only
  whole-range one of the two, measured against it (2.4).
- **The paper's three non-Java operations are the row's lemmas** (item 16), not only the
  rounding of the row text.
- **The AVX2 full-range conversions already have a plan.** 10.1 item 4's "a full-range conversion
  built from 32-bit halves with its own rounding lemma" is 343's split `L2D` and assembly `D2L`
  (`VARKA-343.md` 2.3), whose exactness is 343's proof; this row adds the round-to-nearest step
  in front of the assembly.
- **`l div -1` under ANSI is a negation, which row 414 has open**; it declines until 414 closes.
- **A census extension comes first** (item 15).

## 1. Goal and non-goal

`div`, `%` (`mod`) and `pmod`, and `try_mod`, over `INT` and `BIGINT` operands, and over
`TINYINT` and `SMALLINT`, which the row's text and the census name and for which the G1 wave row
makes this row wait on 329, fuse in each mode the
operator has, with Spark's answers and error classes: a constant divisor and a column divisor, a
zero divisor null or the batch declined so the row engine raises, `Long.MIN_VALUE div -1` wrapped or
declined, `pmod`'s wrap reproduced. The column divisor is exact over the whole range of every type
on every host where its conversions are intrinsic; on AVX2 the `BIGINT` column form is chosen
statically by measurement. Not this row: the decimal and interval forms (`div` over decimals is
out of the milestone by the owner's decision of 14 October 2026; 103, 89 and 349 have the
intervals), floating `%` and `pmod` (346), `try_divide` (342), the column multiplier (95).

## 2. Design

### 2.1 One node, three operators

`IntDivide(Kind kind, VarkaVectorIR dividend, VarkaVectorIR divisor, ZeroDivisor zero,
boolean checkMinOverMinusOne)` on the lane of its operands, `Kind` being `QUOT`, `REM` and `PMOD`,
`ZeroDivisor` being `NULL` (LEGACY, `try_mod`) or `FAIL` (ANSI). It is one node, not a composition
of 342's floating arithmetic and 343's conversions, for two reasons: its proof is one sequence of
operations, which the paper's argument needs kept whole, and the work that depends only on the
divisor - the zero test, the signs, the reciprocal - is shared by the three kinds over one divisor,
which the walk's common subexpressions give for free once the divisor's part is its own node,
`DivisorPrep(divisor)`, parked in a local like any shared node. A constant divisor keeps
`ConstDivide` and the forms of 2.4. The reference evaluator gets the node's semantics in Java's
own operators, which is what the oracles compare against.

The compiler strips the widening casts of item 1 and of `ImplicitTypeCasts` the way 28's
promotions are read: `CAST(a AS BIGINT) div CAST(b AS BIGINT)` over two `INT` columns divides on the
int route and widens the quotient, and over two narrow columns on the float route.

### 2.2 The column divisor, per type

**`TINYINT` and `SMALLINT`: the `float` lane at the int lane's count.** Under 329's `WIDENED` arm a
narrow value is an int in the int lane, so `I2F`, a lanewise float `div`, and `F2I` convert at one
width with no halves. `trunc(a / b)` in binary32 is exact for every `|a| < 2^24`
(`READING_0X80.md` item 2, by the dividend bound of `skills/vector-api-and-width.md`), which every
narrow dividend meets, and `MIN / -1` is 128 or 32768, representable. The `NATIVE` arm widens to
the int lane for the division (a narrow lane has no float of its width), and narrows back.

**`INT`: the double route.** Two halves through `I2D`, a lanewise double `div`, `D2I`, rejoined by
an `or`, as `emitDoubleDivide` does for a constant (`VarkaDivisionLowering.java:495-509`): exact for
every pair, the quotient being under `2^31` in magnitude except for `MIN / -1`. That one pair:

- for `div`, the result is a long: the int quotient widens (`WidenLane`), and a lane where
  `a == MIN & b == -1` adds one to the saturated `Int.MaxValue`, giving `2^31`;
- for `%` and `pmod`, a lane with `b == -1` divides by 1 instead (`a % -1 == a % 1 == 0`), one
  compare and one blend on the divisor, so no saturated quotient reaches `r = a - q * b`, computed
  wrapping in the int lane.

**`BIGINT`: Monniaux and Pain's Algorithm 2 over the magnitudes**, the signs taken off by masked
`NEG` (the 64-bit `abs` has no AVX2 encoding, `VarkaDivisionLowering.java`'s magic form), the
quotient's sign put back as `sign(a) xor sign(b)`, the remainder `a - q * b` wrapping. `b = 1`
(after the signs, `|b| = 1`) and `|b| >= 2^63` (`b = Long.MIN_VALUE`) are the paper's two blends,
so `Long.MIN_VALUE div -1` is `2^63` read unsigned, re-signed to `Long.MIN_VALUE`, Java's wrap. The
three operations of item 0.16, each a lemma of the proof file (3.3):

1. **The unsigned conversion of the dividend.** `|a| = 2^63` (a `Long.MIN_VALUE` dividend) is
   negative as a signed long, so `ad = L2D(|a|)` plus `2^64` where `|a| < 0`, exact; the divisor
   needs none, since `|b| >= 2^63` is the blended case and the main path's value is discarded.
2. **The round-to-nearest conversions.** `q1d` is non-negative: below `2^52`, `(x + 2^52) - 2^52`
   rounds it to nearest, above it is an integer, a blend chooses; `q3d` is signed and small, and
   `(x + 3 * 2^51) - 3 * 2^51` rounds it for `|x| < 2^51`. Then Java's truncating `D2L` of an
   integer-valued double is exact. The lemma states each site's domain, which the paper's bounds
   give, and the sequence of operations stays the paper's, so its proof carries over.
3. **The float reciprocal.** `bs = (float) bd` and `1 / bs` in binary32 need a float vector of
   the double vector's lane count, the kernel of two lane counts that 343 builds (`VARKA-343.md`
   2.2). The kernel takes it, so the paper's binary32 operations are kept as the proof states
   them; the fallback, should 343 slip, is section 7's.

`div`, `%` and `pmod` over one divisor share `bd`, `invbd0` and `invbd`. A constant divisor folds
them to constants (2.4).

### 2.3 The lowering per host

The host is `convertsFallBack()` (`varka/VarkaEmitOptions.java:619-621`), already in the shape key,
which 376's census confirms per runner class (item 0.14).

| form | AVX512DQ (laptop) | AVX2 (EPYC 7763) | NEON and SVE (Neoverse N2) |
|---|---|---|---|
| narrow, float route | `I2F`, `div`, `F2I` | the same | the same |
| `INT`, double route | `I2D`, `div`, `D2I`, two halves | the same | the same |
| `BIGINT`, the paper | `convertShape` | 343's split and assembly, measured | `convertShape` |

On AVX2 the `BIGINT` column form (343's split `L2D` and assembly `D2L`, the rounding of 2.2 item
2 in front of the assembly) has three arms behind an option `longColumnDivide` (`PAPER`,
`BOUNDED`, `DECLINE`), read only where `convertsFallBack()` holds and fuzzed on the laptop under
`useAVX=2`: `PAPER` as in the table; `BOUNDED`, the double route `a - trunc(a / b) * b` with 343's
bounded magic conversions under a `GuardedRange` of `|a| < 2^51` and `|b| < 2^51` that declines a
batch outside it; and `DECLINE`, the shape refused with its reason. The default on AVX2 is the
arm the measurement of 4 puts ahead of the row path, and `DECLINE` if neither is; the losing arms
stay, under the house rule on option arms. On the other hosts the option is accepted and ignored.
At 128 bits on x86 a long lane pair's masks are the Java fallback (`VARKA-376.md` 9), so the
`BIGINT` forms fuse as values and are measured there; a division under a condition root falls
under row 503's two-lane rule as it stands (`VARKA-359.md` 0 item 11).

### 2.4 The constant divisor

- **`INT` and narrow dividends:** `ConstDivide` at the int lane (multiply-high, exact over the
  lane), and `BoundedDivide` over the magnitude with the sign restored for a narrow dividend
  (item 0.9); `%` is `a - q * c` wrapping; `pmod` by `c > 0` is `r + (c & (r >> 31))`, by a power
  of two `a & (c - 1)`, by `c < 0` MathUtils' sequence with a second `ConstDivide` by `c`.
- **`BIGINT` dividends:** `ConstDivide` at the long lane where the lattice (104) proves `|a| <
  2^52`, a widened `INT` among them. Otherwise the row's two forms are both built, as the arms of
  an option `longConstDivide`: `GUARDED`, the default, `ConstDivide` under a `GuardedRange` of
  that bound, which declines a batch past it; and `MULHI`, the four-`vpmuludq` long magic, a
  `MulHiDivide` sibling of VARKA-149 exact over the whole range (intrinsic on every x86 level,
  item 0.14). Both are measured (4), and the losing arm stays fuzzed in the option matrix.
- **Edges:** `c = 1` is the dividend, `% 1` and `pmod(_, 1)` are 0; `c = -1` is a negation for
  `div` (`IntNeg` WRAP under LEGACY; under ANSI at `BIGINT` declines at compile time until row 414
  closes, item 0.10) and 0 for `%` and `pmod`; `c = Long.MIN_VALUE`, which `ConstDivide` refuses,
  is `IfElse(EQ(a, MIN), 1, 0)` for `div` and `IfElse(EQ(a, MIN), 0, a)` for `%`, and MathUtils'
  sequence over that for `pmod`; a literal zero declines at compile time with its reason (all
  null or all raising, a shape not worth a kernel).

### 2.5 What declines and what raises

Nothing raises inside a kernel; a batch that would raise declines, and the row engine raises
Spark's error on the same row.

- **Per batch, ANSI:** a lane with both operands valid and `b == 0` (`div`: `DIVIDE_BY_ZERO`; `%`,
  `pmod`: `REMAINDER_BY_ZERO`), and for `BIGINT` `div` a valid lane with `a == Long.MIN_VALUE &
  b == -1` (`ARITHMETIC_OVERFLOW`). One new `Refusal`, `DIVIDE` (`Family.SELF`, never behind an
  option, since `CHECKED` is gated by `checkIntOverflow`, `Analysis.java:677-683`), with the next
  free status bit at the time it lands. A null dividend over a zero divisor does not decline.
- **Per batch, any mode:** the `BOUNDED` arm's guard on AVX2 (2.3) and the long `ConstDivide`'s
  guard (2.4), as `VALUE_RANGE`.
- **Never a decline, LEGACY and `try_mod`:** a zero divisor narrows the node's validity word by
  `b != 0`, as a NULL-mode `IntArith` narrows its own (item 0.10).
- **At compile time:** a literal zero divisor; `BIGINT div -1` under ANSI until 414; the
  `DECLINE` arm on AVX2; the decimal and interval operands, naming their rows.

### 2.6 Compiler, reference, cost, warm-up, census

`VarkaNodeCompiler` gets the three arms beside the arithmetic ones, reading `failOnError` from the
node (item 0.5); the reference evaluator, `IntDivide` and `DivisorPrep` in Java operators; the emit
cost model a feature per kind and route, on row 352's awaiting-refit list, and the budget a weight
row per route; the warm-up's blocks the float and double divide, `FMA`, the half conversions and
the two-lane-count float of the `BIGINT` form, held by `VarkaWarmupReachSuite`. The census gains
`div`, `%`, `mod`, `pmod` and `try_mod` for `byte`, `short`, `int` and `bigint` as this row lands.

## 3. Tests and proofs

1. **The census extension first** (item 0.15): `int.DIV`, `long.DIV` (to record the fallback),
   `float.DIV`, `double.DIV`, `long.MUL`, and the two-lane-count `D2F` and `F2D` of 2.2 item 3, at
   every width, on the laptop and by `varka-width-audit.yml` on both runner classes.
2. **Exhaustion at 32 bits.** Every `SMALLINT` pair (2^32 pairs, `TINYINT` a subset) through the
   float route, both arms, `div`, `%` and `pmod` against Spark's own expressions; every `INT`
   dividend against a fixed divisor list holding the corners - 0, `+-1`, `Int.MinValue`,
   `Int.MaxValue`, `+-2^k` and `+-(2^k - 1)` and `+-(2^k + 1)` for every `k` that fits, and `+-3`
   and `+-7` - and every `INT` divisor against the same list as dividends, through the double
   route, beside a seeded random draw of pairs; the constant forms by the existing `ConstDivide`
   sweeps and a `pmod` sweep by constant. `INT` pairs are 2^64, so the pairs themselves are the
   SMT file's (3.3). Each sweep is a parallel stream. No CI workflow runs such sweeps today: none
   sets `varka.sweep`, and the gate's `-z opt-in` filter selects nothing in a JUnit suite, so they
   run by hand in the gate's sweep step until a row schedules them.
3. **SMT, `sql/varka/proofs/int_divide.smt2`**, rendered by `VarkaProofFiles` from the code's
   constants: over Java's integers (`java.smt2`) the sign wrapper (`trunc` odd, the remainder's
   sign, `Long.MIN_VALUE` read unsigned), the two blends, the INT fix-ups of 2.2, MathUtils'
   `pmod` sequence with the `r + n` addition wrapping (`pmod(-1, Int.MinValue)` a named check), and
   the power-of-two `pmod`; over the doubles' rounding predicate (`jdouble.rne`, as
   `long_divide.smt2` states it) the three lemmas of 2.2. The paper's own error argument is not
   re-proved: the kernel follows its sequence operation for operation and cites `FPDivision64.v`.
4. **The paper's corner cases**, through the kernel and the reference: `b = 1`, `|b| >= 2^63`,
   `b` either side of `2^42`, `Long.MIN_VALUE` against `-1` and `1`, the dividend at `2^52` and
   `2^63`, plus a seeded draw of ten million pairs a mode, half of them with the divisor drawn by
   magnitude class, on each arm.
5. **Coverage rows**, a family "integral division": each operator over each type pair, a column
   and a literal divisor, `MIN` against `-1` in each mode, a zero divisor under each mode with a
   null and a valid dividend, `pmod(-1, Int.MinValue)`, each naming its verification for row 490's
   check (`int_divide.smt2`'s sections, a sweep, or `differential`).
6. **The fuzzers**: the IR fuzzer's grammar draws `IntDivide` in a sequence of its own seed, its
   divisor from a distribution that hits zero, `-1` and `MIN`; the Spark-level fuzzer's columns
   draw the operators; 352's cross-mode property "TRY is null exactly where ANSI raises" for
   `try_mod`.
7. **The differential and the matrix**: the coverage differential, `VarkaMaybeThrows` over the
   raising rows (the error with its context, item 0.10), and the option matrix dispatched on the
   branch with `useAVX=2` among its configurations. `emitted_bytes.json` adds rows and moves none.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code:**
`VarkaIntegralDivisionBaselineBenchmark` (sql/core, the row path, as 377's classes), over `a div b`,
`a % b`, `pmod(a, b)` and `a % 7` for `INT` and `BIGINT`, and `a % b` for `SMALLINT`, as filters
and projections, run on the EPYC 7763 at 256 and 128 bits and committed beside the class.

**The kernel benchmark:** `VarkaIntegralDivisionBenchmark` (catalyst, Java on `VarkaBenchmarkBase`),
its arms the routes of 2.2 to 2.4 and a scalar Java loop control as `VarkaNarrowLaneBenchmark`
keeps (`:399-439`), every arm checked against the scalar loop before it is timed; at 512, 256 and
128 bits on the laptop (512 double-pumped, labelled), and by `benchmark.yml` on the EPYC 7763 at
256 and 128, the AVX2 host of the row's done-when, with the three `longColumnDivide` arms there.

Predictions, registered before the build:

1. The float route for narrow types beats the scalar loop by 4 to 12 times in L2 at 256 bits, on
   both x86 hosts.
2. The `INT` double route beats it by 2 to 5 times at 256 bits; at 128 bits by 1 to 2 times.
3. The `BIGINT` paper's form at 512 bits on the laptop beats the scalar loop by 2 to 4 times, at
   256 bits by 1 to 2 times.
4. On AVX2 `PAPER` comes within 2 times of the scalar loop either way and `BOUNDED` beats it by
   up to 2 times; the default is decided by the measurement, not this guess.
5. Every fused form beats the row path at 256 bits, which pays a per-row `idiv` and its zero test.
6. No existing shape's bytes move.

**Test time**, at the calibration table's ratios: each 2^32 sweep about 15 to 30 s on the laptop's
cores (VARKA-335's sweeps took seven to sixteen seconds each, about an eighth of their estimate),
about two minutes for the five sweeps; the proof file priced from its spiked form at VARKA-245's
observed ratio, under ten seconds per solver; the new coverage rows and their differential about
20 s at VARKA-334's ratio. The step budget (`sql/varka/ci/step-budget.tsv`) is read before the
pull request says so.

## 5. Order, files and size

1. **The baseline** (4), its own pull request.
2. **The census extension** (3.1), its own pull request or in the baseline's.
3. **30a, the 32-bit routes:** `IntDivide` and `DivisorPrep` at the int lane, the float and
   double routes, the constant forms of 2.4 for `INT` and narrow dividends, `Refusal.DIVIDE`, the
   null narrowing, the sweeps, the proof file's int sections, the coverage rows. Needs 342's
   lanewise `div` or its own call of it, and nothing of 343.
4. **30b, `BIGINT`:** the paper's form, its lemmas, the long constant forms and both
   `longConstDivide` arms, the `longColumnDivide` arms on AVX2 over 343's conversions, the corner
   cases and the draw, the measurement on both hosts and the verdict. After 104, and after 343a
   and 343b, which between them build the kernel of two lane counts the reciprocal needs, the
   AVX2 split `L2D` and assembly `D2L`, and the conversions from a floating type (`VARKA-343.md`
   5, steps 3 to 5; 343a holds the conversions to a floating type), the edge the G1 wave row
   states, "its form per host by 343 and 376" (`PLAN.md` 4.1, line 1214). The conversions are
   343's and are not rebuilt here.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `IntDivide`, `DivisorPrep`, canonical form |
| `varka/VarkaDivisionLowering.java` | the routes, the fix-ups, the paper's sequence |
| `varka/Analysis.java`, `VarkaFusedKernel.java`, `Slots.java` | `DIVIDE`, its bit, temporaries |
| `VarkaNodeCompiler.java` | the arms, the cast stripping, the constant edges |
| `varka/VarkaEmitOptions.java`, `VarkaEmitOption` table | `longColumnDivide`, `longConstDivide` |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weights, features, reach |
| reference evaluator, IR fuzz grammar, Spark-level fuzzer | the node, its draw |
| `sql/varka/proofs/int_divide.smt2`, `VarkaProofFiles.java` | 3.3 |
| tests | sweeps, corner cases, coverage rows, census cells |
| benchmark | the baseline, `VarkaIntegralDivisionBenchmark`, their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `census.tsv` |
| generated | `emitted_bytes.json`, `width_audit.json` |

**Size: large, against the row's medium**, in four pull requests: the baseline and census about
a day, 30a about four days, 30b about five, its AVX2 arms the most of it.

## 6. What it unlocks in the census

The pairs `div`, `%`, `mod`, `pmod` and `try_mod` gain `byte`, `short`, `int` and `bigint`
(`m8/census.tsv`): 30a fuses the first three types of each; 30b adds `bigint`. `div` then has
every type it takes but the intervals (103, 89) and the declined decimal.

## 7. Out of this row, and declines that stay

Out: floating `%` and `pmod` (346), decimal `%` and `pmod` (363), intervals (89, 103), the column
multiplier (95), `try_divide` (342).

The boundary with row 95: this row owns `%` as an operator, over a column divisor and a literal
one alike, with its modes and its zero divisor. Row 95's `i % k` is the constant-divisor
specialisation that this row's kernel takes for a literal (2.4); its line is not changed here.

Kept for later, not built: the reciprocal rounded to 24 bits inside the double lane by a Veltkamp
split, one lane count instead of two, the fallback for 2.2 item 3 if 343a slips, at the cost of a
lemma that the split equals binary32's rounding to nearest over the reciprocal's range; and the
paper's form with a literal divisor's reciprocals folded to constants, a third whole-range literal
arm beside 2.4's two.

Declines that stay: a literal zero divisor; `BIGINT div -1`
under ANSI until 414; on AVX2 the `DECLINE` arm if the measurement chooses it; the batch guards of
2.5.

## 8. Questions for the owner

1. **The size.** The row says medium; 2.2's three routes, the AVX2 arms and the lemmas make it
   large, in four pull requests (5). (a) Accept large, as planned. (b) Split 30b into a row of its
   own. *Recommended: (a)*: the three routes share one node, one proof file and one benchmark, and
   a row of its own for `BIGINT` would only move the edge between them.

## 9. Done when

Row 30's text holds, with the corrections of section 0: each operator fuses in each mode it has
with Spark's error classes (`DIVIDE_BY_ZERO` for `div`, `REMAINDER_BY_ZERO` for `%` and `pmod`,
`ARITHMETIC_OVERFLOW` for `Long.MIN_VALUE div -1`), the sweeps of 3.2 pass, `int_divide.smt2`
proves 3.3's claims under both solvers, the `BIGINT` column divisor passes the paper's corner
cases and the draw, the census cells and the measurement are committed at both widths on the
EPYC 7763 with the AVX2 arm chosen and its predictions scored, the bytes oracle unchanged for every
existing shape, and the PR build and the option matrix pass.

## 10. Outcome

(Written when the work lands.)
