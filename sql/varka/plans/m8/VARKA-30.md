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
once row 538 feeds it the host, which 376's census confirms per runner class (item 0.14).

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

## Build sheet (11 November 2026)

The step this sheet builds is section 5's step 1, **the baseline**, and nothing else: a benchmark of
Spark's row path over `div`, `%`, `pmod` and `try_mod` on the unchanged code, with its queries
suite, in its own pull request. Follow `BUILD_SHEETS.md` (this directory) for the shared rules. Work
in a fresh worktree off origin/master, branch `varka-30-baseline` in `../varka-30-baseline` (the
existing `varka-30-plan` branch is not a stop for `BUILD_SHEETS.md` 1), holding nothing else, and
stop at a local commit: no push, no pull request, no benchmark dispatch. Sections 0 to 9 here are
`BUILD_SHEETS.md`'s steps B0 to B9. Every fact below was read at origin/master `c62f492fc89`.

### 0. Premise re-check

Run each from the worktree root. Any line whose result differs from the expected one is a stop
(rule 7.1), except the last line, the #1486 check, which only selects a column of the classes
table.

Each command prints one count (`git grep -c` as `<path>:<count>`); the comment after it is
the expected count. A `grep -c` that counts 0 exits 1, which is not a failure here.

```sh
B=sql/core/src/test/java/org/apache/spark/sql/execution/benchmark
git grep -c 'public enum Fusion { NONE, NULL_TESTS, PARTIAL, WHOLE }' -- $B/VarkaSqlBenchmarkBase.java  # 1
git grep -c 'protected final void requireBaseline(' -- $B/VarkaSqlBenchmarkBase.java  # 1
git grep -c 'public class VarkaBigintArithmeticBaselineBenchmark extends VarkaSqlBenchmarkBase' \
  -- $B/VarkaBigintArithmeticBaselineBenchmark.java  # 1: the class to copy
git grep -c 'protected def forEachTwoLaneRefusal' \
  -- sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaSharedSessions.scala  # 1
git grep -c 'public static int varkaFused(SparkSession varka, String query)' \
  -- $B/VarkaSizeLadder.java  # 1
git grep -c 'expression\[TryMod\]("try_mod")' \
  -- sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/analysis/FunctionRegistry.scala  # 1
git ls-files 'sql/core/src/test/*VarkaIntegralDivision*' \
  'sql/core/benchmarks/*VarkaIntegralDivision*' | wc -l  # 0: nothing built yet
awk -F'\t' '$1 ~ /^(div|%|mod|pmod|try_mod)$/ && $8 == ""' sql/varka/plans/m8/census.tsv \
  | wc -l  # 5: none of the five names fuses any type (column 8, fused_today)
grep -c '^| 30 | \*\*Planned\*\*' sql/varka/plans/m8/PLAN.md  # 1
gh pr view 1486 --repo vecbricks/varka --json state --jq .state  # OPEN or MERGED: which column of 1.3 applies
```

Open pull requests on these files: #1486 and #1484 (row 104) edit the classes this sheet
copies, so copy them as `git show c62f492fc89:<path>` prints them; #1486 also changes classes
(1.3's last column), #1484 none of these sections'. `PLAN.md` is edited by #1425, #1428, #1461,
#1462, #1474, #1478, #1484, #1486, #1508, #1509 and #1510: `BUILD_SHEETS.md` 8 decides when one has merged. Copy from `c62f492fc89` even when origin/master has moved.

### 1. Scope

**1.1 Files.** Add two files and edit two:

| file | change |
|---|---|
| `sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaIntegralDivisionBaselineBenchmark.java` | new: the benchmark |
| `sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark/VarkaIntegralDivisionBaselineQueriesSuite.scala` | new: its queries suite |
| `sql/varka/plans/m8/PLAN.md` | row 30's cell: the state, 1.5 |
| `sql/varka/plans/m8/VARKA-30.md` | section 10: the paragraph of 1.5 |

**1.2 The benchmark class.** Copy `VarkaBigintArithmeticBaselineBenchmark.java` to the new name
and change exactly these members; everything else (the `Section` record, `runBenchmarkSuite`'s
two sessions, the fuse check before any timing, the two arms `spark row path (varka off)` and
`varka on`, the session names' pattern) stays as the copy has it.

* Session names: `varka-integral-division-baseline-off` and `varka-integral-division-baseline-on`.
* `NUM_ROWS = 2_000_000`; `VIEW = "varka_integral_division"`.
* `tableSql(int rows)`, verbatim:

```java
  static String tableSql(int rows) {
    return """
        select i, j, l, l2, s, s2,
               case when id %% 10 = 1 then null else i end as i_n,
               case when id %% 10 = 1 then null else l end as l_n,
               case when id %% 10 = 1 then null else s end as s_n
        from (select id, i, l, s,
                     case when j0 = 0 then 1 else j0 end as j,
                     case when l20 = 0 then 1 else l20 end as l2,
                     cast(case when s20 = 0 then 1 else s20 end as smallint) as s2
              from (select id,
                           hash(id, 1) as i,
                           shiftright(hash(id, 2), cast(id %% 31 as int)) as j0,
                           shiftright(xxhash64(id, 3), cast(1 + id %% 47 as int)) as l,
                           shiftright(xxhash64(id, 4), cast(id %% 61 as int)) as l20,
                           cast(pmod(hash(id, 5), 65536) - 32768 as smallint) as s,
                           shiftright(pmod(hash(id, 6), 65536) - 32768, cast(id %% 15 as int))
                             as s20
                    from range(0, %d)))""".formatted(rows);
  }
```

  Its javadoc states, as the copy's does, each column's region: `j`, `l2`, `s2` never zero,
  both signs, every bit length, `1` and `-1` included; `|l| <= 2^62`, so no `Long.MIN_VALUE div
  -1`; the `_n` dividends null at one row in ten. So no section raises under ANSI.
* `sections(boolean declinesLongConditions)`, verbatim (the classes are 1.3's):

```java
  static List<Section> sections(boolean declinesLongConditions) {
    List<Section> out = new ArrayList<>();
    Map<String, String> legacy = Map.of(SQLConf.ANSI_ENABLED().key(), "false");
    Map<String, String> ansi = Map.of(SQLConf.ANSI_ENABLED().key(), "true");
    // Spark's IS NOT NULL tests inferred for a filter fuse on their own as a null-test kernel
    // under Spark's Filter: at the int lane always, at the long lane unless the session refuses
    // a 64-bit condition (VARKA-503).
    Fusion intNullTests = Fusion.NULL_TESTS;
    Fusion longNullTests = declinesLongConditions ? Fusion.NONE : Fusion.NULL_TESTS;
    for (String nulls : new String[] {"", "_n"}) {
      String density = ", " + (nulls.isEmpty() ? "no nulls" : "one row in ten null");
      String from = " AS a FROM " + VIEW;
      String[][] types = {{"INT", "i", "j"}, {"BIGINT", "l", "l2"}};
      for (String[] t : types) {
        String type = t[0] + " ";
        String a = t[1] + nulls;
        String b = t[2];
        String an = t[1];
        Fusion nullTests = "INT".equals(t[0]) ? intNullTests : longNullTests;
        out.add(new Section(type + an + " div " + b + ", LEGACY" + density,
            "SELECT " + a + " div " + b + from, legacy, Fusion.NONE));
        out.add(new Section(type + an + " div " + b + ", ANSI" + density,
            "SELECT " + a + " div " + b + from, ansi, Fusion.NONE));
        out.add(new Section(type + an + " % " + b + ", LEGACY" + density,
            "SELECT " + a + " % " + b + from, legacy, Fusion.NONE));
        out.add(new Section(type + an + " % " + b + ", ANSI" + density,
            "SELECT " + a + " % " + b + from, ansi, Fusion.NONE));
        out.add(new Section(type + "try_mod(" + an + ", " + b + ")" + density,
            "SELECT try_mod(" + a + ", " + b + ")" + from, Map.of(), Fusion.NONE));
        out.add(new Section(type + "pmod(" + an + ", " + b + "), LEGACY" + density,
            "SELECT pmod(" + a + ", " + b + ")" + from, legacy, Fusion.NONE));
        out.add(new Section(type + "pmod(" + an + ", " + b + "), ANSI" + density,
            "SELECT pmod(" + a + ", " + b + ")" + from, ansi, Fusion.NONE));
        out.add(new Section(type + an + " % 7, LEGACY" + density,
            "SELECT " + a + " % 7" + from, legacy, Fusion.NONE));
        out.add(new Section(type + an + " % 7, ANSI" + density,
            "SELECT " + a + " % 7" + from, ansi, Fusion.NONE));
        out.add(new Section(type + "filter " + an + " div " + b + " > 0, ANSI" + density,
            "SELECT " + a + from + " WHERE " + a + " div " + b + " > 0", ansi, nullTests));
        out.add(new Section(type + "filter " + an + " % " + b + " > 0, ANSI" + density,
            "SELECT " + a + from + " WHERE " + a + " % " + b + " > 0", ansi, nullTests));
        out.add(new Section(type + "filter pmod(" + an + ", " + b + ") > 0, ANSI" + density,
            "SELECT " + a + from + " WHERE pmod(" + a + ", " + b + ") > 0", ansi, nullTests));
        // Over the INT column with no null the optimizer's null test is absent and nothing fuses.
        Fusion bySeven = "INT".equals(t[0]) && nulls.isEmpty() ? Fusion.NONE : nullTests;
        out.add(new Section(type + "filter " + an + " % 7 = 0, ANSI" + density,
            "SELECT " + a + from + " WHERE " + a + " % 7 = 0", ansi, bySeven));
      }
      String s = "s" + nulls;
      out.add(new Section("SMALLINT s % s2, LEGACY" + density,
          "SELECT " + s + " % s2" + from, legacy, Fusion.NONE));
      out.add(new Section("SMALLINT s % s2, ANSI" + density,
          "SELECT " + s + " % s2" + from, ansi, Fusion.NONE));
      out.add(new Section("SMALLINT try_mod(s, s2)" + density,
          "SELECT try_mod(" + s + ", s2)" + from, Map.of(), Fusion.NONE));
      out.add(new Section("SMALLINT filter s % s2 > 0, ANSI" + density,
          "SELECT " + s + from + " WHERE " + s + " % s2 > 0", ansi, intNullTests));
      out.add(new Section("INT i ^ j, control, fuses today" + density,
          "SELECT i" + nulls + " ^ j" + from, ansi, Fusion.WHOLE));
    }
    return out;
  }
```

  That is 31 sections at each null density, 62 in all, in this order.
* The class javadoc follows the copy's paragraphs, rewritten for this class: what is timed (the
  operators and modes above, why the data never raises), the two arms, the fuse check and its
  classes as 1.3 states them, and the two `gh workflow run benchmark.yml` blocks with
  `-f class=VarkaIntegralDivisionBaselineBenchmark`. Name VARKA-30 as the row it serves.
* `runBenchmarkSuite` is the copy's, `VarkaSizeLadder.varkaFused` on the `WHOLE` control
  included.

**1.3 The classes, as the dry run planned them.** Obtained on origin/master `c62f492fc89` two ways: the
benchmark's own dry run, this sheet's verbatim code compiled as a scratch class, at the laptop's
default width, 256 and 128 bits (refusal off at the first two, on at 128), and a scratch suite
planning each section on the shared sessions at 2000 rows with the refusal set each way:

| sections | refusal off | refusal on | after #1486 merges |
|---|---|---|---|
| every projection but the control (`div`, `%`, `try_mod`, `pmod`, `% 7`), all three types | `NONE` | `NONE` | `NONE` |
| `INT` filters: `div`, `%`, `pmod` at both densities; `% 7 = 0` one row in ten null | `NULL_TESTS` | `NULL_TESTS` | `NONE` |
| `INT filter i % 7 = 0, ANSI, no nulls` | `NONE` | `NONE` | `NONE` |
| `BIGINT` filters, all four, both densities | `NULL_TESTS` | `NONE` | `NONE` |
| `SMALLINT filter s % s2 > 0`, both densities | `NULL_TESTS` | `NULL_TESTS` | `NONE` |
| `INT i ^ j, control, fuses today` | `WHOLE` | `WHOLE` | `WHOLE` |

Open PR #1486 (row 598) declines a filter whose only fused conjuncts are the optimizer's inferred
`IS NOT NULL` tests, and so turns every `NULL_TESTS` above to `NONE`. If premise 10 printed `MERGED`,
#1486 has merged: write `intNullTests` and `longNullTests` as `Fusion.NONE`, write the `bySeven`
line as `Fusion bySeven = nullTests;` and delete the comment above it, drop the comment above
the two variables, and
state in the javadoc that every section but the control plans no Varka node. Use master's
behaviour as the dry run shows it either way; the table is what it showed on `c62f492fc89`.

**1.4 The queries suite.** Copy `VarkaBigintArithmeticBaselineQueriesSuite.scala` (its
`withSettings` and `withTable` helpers unchanged but for the class name) and write two tests:

1. `"every section of the integral division baseline runs, and both sessions answer alike"`.
   First one query over the view on `spark` (one job), then these assertions, each the region
   the javadoc states, with the counts the fixture gave at 2000 rows in brackets for orientation
   (assert the inequality, not the bracketed number):
   `count_if(j = 0) = 0`, `count_if(l2 = 0) = 0`, `count_if(s2 = 0) = 0`;
   `count_if(j = -1) > 0` (34), `count_if(j = 1) > 0` (65), `count_if(l2 = -1) > 0` (5);
   `count_if(abs(j) < 256) > 0` (577), `count_if(abs(l2) < 256) > 0` (231),
   `count_if(abs(s2) < 16) > 0` (664); `min(i) < 0 < max(i)`, `min(l) < 0 < max(l)`,
   `min(s) < 0 < max(s)`; `count_if(abs(l) > 2147483647) > 0` (1278),
   `count_if(abs(l2) > 2147483647) > 0` (1020); `max(abs(l)) <= 4611686018427387904` (`2^62`);
   `count_if(i % 7 = 0) > 0` (289); `count_if(i_n is null) = rows / 10` (200), and the same
   for `l_n` and `s_n`.
   Then assert the classes by count: `sections(false)` has 62 sections, 17 of them
   `NULL_TESTS`, 2 `WHOLE` (the controls) and 43 `NONE`; `sections(true)` has 9 `NULL_TESTS`,
   2 `WHOLE` and 51 `NONE` (after #1486 both have 0 `NULL_TESTS`, 2 `WHOLE`, 60 `NONE`).
   Which section has which class is test 2's.
   Then for each section, under `withClue(s.title)` and `withSettings(s)`: collect it on
   `spark`; for a projection assert the schema is one column named `a` whose type is
   `LongType` when the query holds ` div `, else `IntegerType` for a title starting `INT `,
   `LongType` for `BIGINT `, `ShortType` for `SMALLINT `; for a filter (the query holds
   `WHERE`) assert the one column has the dividend's type by the title prefix alone (the ` div ` rule
   is the projections'); then
   `checkAnswer(varkaSpark.sql(s.query), collected)`; then the nulls: a projection over the
   columns with no null answers no null, one over a column with one row in ten null answers
   exactly `rows / 10` nulls, a filter answers no null and keeps more than 0 and fewer than
   `rows` rows. The copy's exact filter count (`kept`, `keptNull`) is deleted: it is the bigint
   filter's. The region query runs under the session's defaults, as the copy's does.
2. `"each section of the integral division baseline fuses as its class lists it"`, tagged
   `PinsDefaults`, the copy of `VarkaDayTimeIntervalBaselineQueriesSuite`'s second test (its `requireKernel` call replaced by `VarkaSizeLadder.varkaFused(varkaSpark, s.query)`, its result unused,
   as the bigint suite calls it: it throws when a batch falls back): the
   `assume(VarkaSqlBenchmarkBase.fuseCheck(), ...)`, then `forEachTwoLaneRefusal { declines =>
   ... sections(declines) ... }`, asserting `VarkaSqlBenchmarkBase.fusion(plan) ===
   Optional.of(s.fusion)` under `withClue(s"${s.title}, refusal $declines")` and, for the
   `WHOLE` control, `VarkaSizeLadder.varkaFused(varkaSpark, s.query)`.

The class comment says what the bigint suite's says, for this class (VARKA-30 4).

**1.5 The record.** In `PLAN.md`, row 30's cell starts `**Planned** (`; insert before it the text in
this fence, joined into one line and followed by one space, keeping `**Planned** (` and the rest
of the cell after it, as row 104's cell does (the plan lint requires a row with a work commit to
open with its state and say what is left). `<date>` here and below is the day of the commit,
in the form `11 November 2026`:

```text
**Started** (the baseline built <date>: `VarkaIntegralDivisionBaselineBenchmark` and its queries
suite, 62 sections, every projection but the control `NONE`; its EPYC 7763 results at 256 and
128 bits owed by the owner's dispatch; left: the census extension, 30a and 30b, `VARKA-30.md`
5).
```

In this file, under `## 10. Outcome`, below its placeholder line,
add a paragraph `**The baseline** (<date>):` naming the two files, the section count, the
classes as the dry run gave them at each width (as its notes printed them) and that the runner
results are the owner's; section 4's predictions are the kernel's, scored by 30a and 30b.

**1.6 Out of scope.** The census extension (5 step 2), `VarkaIntegralDivisionBenchmark` and every
kernel, the `%` shapes row 95 adds (`i % 16`, `try_mod(i, 7)`, `try_mod(i, 16)`, `filter i % 16 =
0`, `VARKA-95.md`'s build sheet), any zero or overflowing divisor section, `TINYINT`, results
files, `suite-times.tsv`, any change to `VarkaSqlBenchmarkBase` or another baseline, and any
benchmark dispatch.

### 2. Tests

| test | asserts | oracle |
|---|---|---|
| suite test 1, regions | the fixture reaches 1.4's regions | SQL aggregates on Spark's row session, independent of the class's sections |
| suite test 1, sections | each section runs, has its type, both sessions agree, nulls exactly where the dividend is null | Spark's row path on `spark` (Varka off) and the types Spark's analyzer gives (`VARKA-30.md` 0 items 1 and 2) |
| suite test 2 | each section's plan has its listed class at both refusals; the control runs every batch as a kernel | `VarkaSqlBenchmarkBase.fusion`, master's classifier, which this change does not touch |

### 3. Planted faults

After the local commit of 4.6, plant each, run the named test, see it fail as stated, then
`git checkout --` the file it edits and confirm `git status --short` is empty.

1. In `tableSql`, replace `case when j0 = 0 then 1 else j0 end as j` by `j0 as j`. Run 4.2: test
   1 fails on the assertion `count_if(j = 0) = 0`.
2. In `sections`, change the `INT i ^ j, control` section's `Fusion.WHOLE` to `Fusion.NONE`.
   Run 4.2: test 1 fails on its class counts (2 `WHOLE`) and test 2 under the clue `INT i ^ j,
   control, fuses today, no nulls, refusal false`.

### 4. Commands, in order

1. The premise table of 0.
2. The queries suite, with `BUILD_SHEETS.md` 2's exports set:

   ```sh
   flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" \
     "testOnly *VarkaIntegralDivisionBaselineQueriesSuite"
   ```

   Expected: `Tests: succeeded 2, failed 0`.
3. The dry run at the laptop's default width, then at 256 and at 128 bits, in one sbt call
   (each `set` adds a flag, the last `MaxVectorSize` wins; under the property
   `VarkaBenchmarkBase.runCases` times nothing, while the fuse checks still run):

   ```sh
   flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" \
     'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' \
     "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaIntegralDivisionBaselineBenchmark" \
     'set Test/javaOptions += "-XX:MaxVectorSize=32"' \
     "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaIntegralDivisionBaselineBenchmark" \
     'set Test/javaOptions += "-XX:MaxVectorSize=16"' \
     "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaIntegralDivisionBaselineBenchmark" \
     > target/dry-30.log 2>&1; echo $?
   ```

   Expected: `0`; in `target/dry-30.log`, `grep -c 'is listed as'` is `0`,
   `grep -c 'fuses whole on the varka on arm'` is `6` (the control, twice a run) and `grep -c
   'fuses only its null tests'` is `43`: 17 at the default width and at 256 bits, where the
   session leaves 64-bit conditions in kernels, and 9 at 128 bits, where it refuses them and the
   `BIGINT` filters are `NONE` (0 at every width after #1486). Any other count is a stop (rule
   7.2); the log's three `[success]` lines end the three runs. Write the per-width counts into the section 10 paragraph of 1.5.
4. No regeneration: no generated file reads these sources, and `BUILD_SHEETS.md` 5 asks for one
   only after a merge or a rebase.
5. `BUILD_SHEETS.md` 6, items 1 to 5 (pre-commit, Error Prone, the two linters, the plan lint).
6. Commit locally: subject `[VARKA-30] Benchmark integral division on Spark's row path before it
   fuses`, body and attribution as `BUILD_SHEETS.md` says.
7. The planted faults of 3, each reverted.
8. `BUILD_SHEETS.md` 6 item 6, `python3 dev/varka_push_check.py`, which reads the commits and so
   runs once they hold the final tree. Expected: exit 0, its size line under 900.

### 5. Done when

`testOnly *VarkaIntegralDivisionBaselineQueriesSuite` reports `succeeded 2, failed 0`; the dry
run of 4.3 exits 0 at all three widths with no `is listed as` line; `dev/varka_push_check.py`
exits 0; `git status --short` is empty and `git log -1 --format=%s` is 4.6's subject.

### 6. Size

About 270 lines of benchmark, 190 of suite, 8 of plans: about 470 counted, under the cap.

### 7. Stop rules

Stop and report, without working around it, when:

1. a premise line of 0 differs from its expected result;
2. the dry run or suite test 2 plans a section at a class that neither 1.3's column for its
   refusal nor, after #1486, its last column gives;
3. a section raises (an ANSI `DIVIDE_BY_ZERO`, `REMAINDER_BY_ZERO` or `ARITHMETIC_OVERFLOW`) or
   the two sessions answer it differently;
4. a region assertion of 1.4 fails on the verbatim `tableSql`;
5. 4.4 changes a generated file, or the push check fails on a rule this change cannot reach;
6. `VarkaIntegralDivisionBaselineBenchmark` exists on master when you start (row 95's sheet
   creates nothing, so this means someone else built it).

### 8. Decisions already taken

* The lead, 11 November 2026: "30's baseline first (it creates
  VarkaIntegralDivisionBaselineBenchmark); 95's baseline after it, adding its `%` shapes to 30's
  class as VARKA-95.md 4 says".
* The lead, 11 November 2026: "each section's expected Fusion class must be determined by the
  laptop dry run against origin/master; open PR #1486 (row 598) changes filters whose only fused
  part is an inferred IS NOT NULL from NULL_TESTS to NONE: the sheet states which classes change
  if #1486 merges first and that the agent then uses master's behaviour at build time."
* The lead, 11 November 2026: "EPYC 7763 runs at 256 and 128 bits are the owner's dispatches";
  the agent stops after the laptop dry run and the queries suite pass.
* Section 4 of this plan: the operators and types, "as filters and projections".

### 9. What the owner must do afterwards

Review and push the branch and open its pull request; then dispatch, on vecbricks/varka,
`gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<PR> -f
'class=*.VarkaIntegralDivisionBaselineBenchmark' -f jdk=25 -f scala=2.13 -f num-splits=1 -f
create-commit=false -f expected-cpu="AMD EPYC 7763"`, and the same with `-f
extra-java-options=-XX:MaxVectorSize=16`; commit the two artifacts as
`VarkaIntegralDivisionBaselineBenchmark-jdk25-runner-epyc7763-results.txt` and
`VarkaIntegralDivisionBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt` with their
provenance files under `sql/core/benchmarks/`, before the pull request merges. If #1486 merges after
this class, it must list this class's `NULL_TESTS` sections as `NONE` too, or master fails.
