# VARKA-343: The floating casts

## 0. What exists now (admission check)

From the code at `7ddd8f6715e` (27 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/`), from open PR #1065
(branch `varka-341-semantics`, the floating comparisons) and from row 342's draft plan (branch
`varka-342-plan`). The row's text is the milestone plan's 2.2 and 2.7, "The conversions (343)".

1. **Every floating cast declines by name, and 26 pairs are this row's.** `floatingDecline`
   answers "a cast to or from a floating type (row 343)" for every `Cast` touching a floating type
   (`VarkaNodeCompiler.java:331-352`, `:341`). `m8/casts.tsv` names 343 for 26 pairs: the five
   integral and boolean sources to `FLOAT` and `DOUBLE` (10), the two floating sources to
   `BOOLEAN`, the four integral types and the other floating type (12), the two identities, and
   `DATE` to both (LEGACY only, always null). `casts_fused.tsv` holds 24 of them, the identities
   absent by row 328's rule (2.5): the 22 others `declined` in every mode, the `DATE` pairs
   `declined` under LEGACY and `illegal` under ANSI and TRY.
2. **The integral casts are the structure to share, and their TRY arm is unbuilt.** `BIGINT` to
   `INT` is `NarrowLane` under LEGACY and `NarrowLane(GuardedRange(x, int range))` under ANSI;
   TRY declines naming row 332 (`VarkaNodeCompiler.java:692-711`). `INT` to `BIGINT` is
   `WidenLane` and a narrow source widens to the int lane at its load (`:505-528`). `GuardedRange`
   (`varka/VarkaVectorIR.java:536`) only declines a batch and holds `long` bounds; the nodes that
   null a valid lane are `MakeDate` with `failOnError` false (`:1352-1356`) and `IntArith` in
   `Overflow.NULL` (`:672`, the enum at `:415`). Row 332, the integral casts
   in three modes, has no plan yet, and `byte` and `short` to `bigint` decline today.
3. **No node converts between the kinds, and no kernel holds two lane counts across them.**
   `LaneType.isWiderThan` orders the integral lanes only (`varka/VarkaVectorIR.java:86-130`), so
   `WidenLane` (`:563`) and `NarrowLane` (`:605`) refuse a floating side. `emitShapeConversion`
   emits `I2L` and `L2I`, part 0, and throws on any other pair (`varka/VarkaVectorWalk.java:
   524-541`). `Analysis` refuses a floating node in an integral emission and the reverse
   (`varka/Analysis.java:1236-1241`); `emissionLane` drives a floating tree at its own lane and
   `lanesShare` gives a floating root a kernel of its own (`varka/VarkaKernelPlan.java:170-228`).
   #1065 leaves the same-width mixed kernel and the `VectorMask.cast` crossing owed (its
   `VARKA-341.md`, section 5.1, "Still owed").
4. **What Spark computes** (`expressions/Cast.scala`):
   - **To a floating type**: `(float) c` and `(double) c` (codegen `:2626-2700`; interpreted
     `numeric.toFloat` and `toDouble`, `:1426-1470`), with no error in any mode, so TRY is
     LEGACY's answer. JLS 5.1.2 rounds `int` to `float` and `long` to either to nearest from the
     exact value; `float` to `double` is exact. `BOOLEAN` gives `1.0` or `0.0`; `DATE` is null.
   - **To an integral type**: LEGACY is `(int) c`, `(long) c`, and `(byte) c` and `(short) c`,
     which JLS 5.1.3 defines as the `int` conversion then the wrap: NaN to 0, saturation at the
     `int` or `long` range. ANSI calls `FloatExactNumeric` and `DoubleExactNumeric`
     (`types/numerics.scala:121-185`) or `CastUtils.floatToByteExact` and its kin
     (`CastUtils.java:79-97`; `Cast.scala:2437-2476`): `floor(v) <= MAX && ceil(v) >= MIN` in
     double arithmetic, else `CAST_OVERFLOW`, NaN failing both. `MAX` for `BIGINT` is
     `Long.MaxValue.toDouble`, `2^63`, so `2^63` passes and saturates. TRY sets `ansiEnabled`
     (`:720-722`; the legacy exemption is for string sources only, `:1602-1612`) and wraps the
     cast in a catch that answers null (`:1586-1597`, `:1706-1716`).
   - **To `BOOLEAN`**: `x != 0` (`:871-874`): NaN true, `-0.0` false.
   - **`time_from_seconds`** is a `StaticInvoke` of `DateTimeUtils.timeFromSeconds`
     (`expressions/timeExpressions.scala:880`); a `FLOAT` argument resolves to the `Double`
     overload by commons-lang3's widening match (`expressions/objects/objects.scala:206-207`).
     NaN and the infinities raise, then `(long) (s * 1e9)`, then a raise unless `0 <= nanos <
     NANOS_PER_DAY` (`util/DateTimeUtils.scala:1119-1135`, `:1161-1166`), in every mode.
5. **What the JDK does** (`jdk-25-ga` source, local):
   - `VectorOperators` declares all ten conversions (`VectorOperators.java:700-732`); the Java
     fallback casts lane by lane (`AbstractVector.java:619`, `:669`), so a fallback is the JVM's
     answer, slow and allocating, never a wrong one.
   - x86 refuses `L2F`, `L2D`, `F2L` and `D2L` without AVX512DQ (`cpu/x86/x86.ad:1757-1777`).
     `F2I` and `D2I` on AVX2 convert, then fix NaN to 0 and saturate
     (`cpu/x86/c2_MacroAssembler_x86.cpp:5040-5075`, `:5186`), and a sub-word target masks and
     packs (`:5227-5249`), which is Java's `(byte) (int) x`.
   - aarch64 converts `long` to `float` lane by lane with the scalar `scvtf` on NEON and with
     SVE's `scvtf` from D to S (`cpu/aarch64/aarch64_vector.ad:4468-4495`): one rounding each.
     `D2L` is `fcvtzs`, saturating with NaN to 0 (`:4627-4640`), Java's answer.
   - The census: the laptop reads every conversion intrinsic at 512, 256 and 128 bits
     (`operator_census.json`); the EPYC 7763 reads `L2D`, `D2L` and `L2F` as fallbacks at 256 and
     128 and the rest intrinsic; the Neoverse N2 reads all intrinsic
     (`results/VARKA-376-census-epyc-7763.json`, `results/VARKA-376-census-neoverse-n2.json`).
     There is no `F2L` cell, no floating `ADD`, `SUB` or `MUL` (342's item 0.7), no
     reinterpretation and no `long` shift by a vector count.
   - The host gate exists: `VarkaEmitOptions.convertsFallBack` answers `UseAVX < 3` and reaches
     the shape key (`varka/VarkaEmitOptions.java:594-621`); the 64-bit constant division reads
     it (`varka/VarkaDivisionLowering.java:361`). The `useAVX` option sets it in a test.
6. **Two exactness traps, checked here.** `BIGINT` to `FLOAT` through `DOUBLE` rounds twice:
   `2^53 + 2^29 + 1` gives `2^53` against `2^53 + 2^30` (2.7, checked again in Python). And the
   magic number rounds where the cast truncates: `rawbits(x + 0x1.8p52) - 0x4338000000000000` is
   `(long) rint(x)`, right for 344 after its `rint` and wrong for a cast at `x = 0.7` (1 against
   0) and `x = 1.5` (2 against 1); `0.5` is no counterexample, since `rint(0.5)` is 0.
7. **The baseline covers two pairs.** `VarkaFloatingBaselineBenchmark` times `DOUBLE` to `BIGINT`
   at 39.8 ns a row and `FLOAT` to `DOUBLE` at 38.4 on Spark's row path, 256 bits, no nulls, in
   the session's default mode, ANSI (`VarkaFloatingBaselineBenchmark-jdk25-runner-epyc7763-
   results.txt` in `sql/core/benchmarks/`). Nothing times `BIGINT` to `FLOAT`, `INT` to `DOUBLE`,
   `DOUBLE` to `INT`, TRY, a boolean cast or `time_from_seconds`.
8. **What 342 waits for** (its section 0 and 2.4): `I2D` for `i / j` and the functions over an
   integral column, `F2D` for `f / g` and the functions over a `FLOAT`, `L2D` for a `BIGINT`
   division and the day-time interval, `D2I` and `D2L` for the interval rounding, a
   `GuardedRange` at a floating lane, and a kernel of two lane counts across the kinds.
9. **The boolean and date pieces are other rows'.** `CondValue` takes the int and long lanes
   (`varka/VarkaVectorIR.java:1224`); a `BOOLEAN` output is row 27's (planned); a typed null leaf,
   which an always-null `DATE` cast is, is row 333's (`VARKA-333.md`, planned).

### What differs from the row text and the milestone plan

- **NEON needs no round-to-odd** (item 0.5): C2's `L2F` on aarch64 rounds once. Round-to-odd is
  the AVX2 form only.
- **The cast truncates, the magic number rounds** (item 0.6): the AVX2 `D2L` truncates first.
- **AVX2 can be whole-range** with exact pieces: `L2D` by a split whose parts are exact, so only
  the final add rounds, and `D2L` by assembling the integer from the exponent. The row's bounded
  form stays as the option's other arm (question 1).
- **The floating `GuardedRange` and its null disposal are built here**, before 342, and are the
  node 332's TRY narrowing needs.
- **`time_from_seconds` needs 342's floating multiply**, so it is the last step (question 2).
- **No `D2B` or `D2S` operator.** 2.7 has them match LEGACY's `(byte) (int)` wrap in one
  operator; here a narrow target is `NarrowLane` over the `INT` conversion (2.1), the wrap the
  narrow lanes already emit, so no narrow-target conversion needs a census cell or a lowering.

## 1. Goal and non-goal

The 26 pairs of item 0.1 and `time_from_seconds` over a `FLOAT` or `DOUBLE` fuse with the row
engine's answers to the bit in every mode Spark allows; a lane Spark raises on declines its batch
to the row engine, and a lane TRY nulls is null. Not this row: the decimal casts (346, 350, 364,
366), string and zoned `TIMESTAMP` casts, rounding (344), arithmetic (342).

## 2. Design

### 2.1 One conversion node

`ConvertLane(child, target)`: Java's primitive cast from the child's lane to `target`, where one
side is floating - `I2F`, `I2D`, `L2F`, `L2D`, `F2I`, `F2L`, `D2I`, `D2L`, `F2D`, `D2F`. The
constructor refuses an integral pair (those are `WidenLane` and `NarrowLane`) and a narrow side: a
`TINYINT` or `SMALLINT` source is the `INT` value its load widens, and a narrow target is
`NarrowLane` over the `INT` result, which is JLS 5.1.3's wrap. The node has one meaning on every
host; its lowering is per host (2.3). A conversion between equal widths keeps the lane count; one
that widens reads part 0 of the source, as `I2L` does, and one that narrows writes the low half of
the target species, as `L2I` does.

### 2.2 The kernel of two lane counts

The rules of `VarkaKernelPlan` change from lanes to widths: a tree holding a 64-bit node (`LONG`
or `DOUBLE`) drives at 64 bits, at `LONG` if it holds one and at `DOUBLE` otherwise; a tree of
32-bit nodes only drives at `INT` if it holds one, else at `FLOAT`. A 32-bit value in a 64-bit
kernel fills the low half of its species of the drive's width, as an int value does in a long
kernel (`VARKA-28.md` 9). `Analysis`' fit rule (item 0.3) becomes the width rule, and `lanesShare`
joins roots of one width. A mask crosses to a lane of another count through the word, as
`emitToDrivingLane` does, and to one of the same count by `VectorMask.cast`. #1065 leaves that
crossing owed (its `VARKA-341.md` 5.1); 343a builds it, so it closes 341's owed item, and 341's
record names 343a when this lands. A narrow column in a 64-bit kernel - `CAST(y AS DOUBLE)` -
needs the narrow load at the long width that 332's `CAST(y AS BIGINT)` needs too; the first of the
two rows to land builds it.

### 2.3 The lowering per host

| conversion | AVX512DQ (laptop) | AVX2 (EPYC 7763) | NEON and SVE (Neoverse N2) |
|---|---|---|---|
| `I2F`, `I2D`, `F2I`, `D2I`, `F2D`, `D2F` | `convertShape` | `convertShape` | `convertShape` |
| `L2D` | `convertShape` | split (below), or the bounded magic | `convertShape` |
| `L2F` | `convertShape` | round-to-odd (below) | `convertShape` |
| `D2L` | `convertShape` | assembly (below), or the truncated magic | `convertShape` |
| `F2L` | `convertShape` | `F2D`, then `D2L`'s form | `convertShape` |

The host is `convertsFallBack()` (item 0.5), already in the shape key. The AVX2 forms live in a
`VarkaConversionLowering` beside `VarkaDivisionLowering`; `M = 0x1.8p52`, `MB` its bits:

- **`L2D`, split** (whole range): `hi = v >> 32`, `lo = v & 0xFFFFFFFF`; `dh = (bitsToDouble(hi +
  MB) - M) * 0x1p32` and `dl = bitsToDouble(lo | 0x4330000000000000) - 0x1p52`, both exact (`|hi|
  <= 2^31`, `lo < 2^32`); `d = dh + dl`, one rounding of the exact `v`. Eight lane operations,
  which is not the instruction count: AVX2 has no 64-bit arithmetic right shift, so C2 emits
  `v >> 32` (and the `>> 63` of the two forms below) as four instructions and a sign-mask load
  (`cpu/x86/x86.ad:6923-6949`); the fitted cost feature of 2.7 absorbs the difference.
- **`L2D`, bounded magic**: `bitsToDouble(v + MB) - M`, exact for `|v| < 2^51`, under a
  `GuardedRange` of that window that declines the batch outside it.
- **`L2F`, round-to-odd** (whole range, one form): `s = v >> 63`, `m = (v ^ s) - s` read unsigned
  (`Long.MIN_VALUE` gives `2^63`); where `m >>> 52 != 0`, `t = (m >>> 12) | sticky(m & 0xFFF)` and
  the scale `2^12`, else `t = m` and the scale 1; `d = (bitsToDouble(t | 0x4330000000000000) -
  0x1p52) * scale`, exact; then `D2F`, and the sign flipped where `s` is set. Below `2^52`, `d` is
  `m` and `D2F` rounds once; above, `d` is `m` rounded to odd with at least 41 bits kept, and
  `D2F` of it equals one rounding of `m`: round-to-odd at `p` bits then to nearest at `q` bits
  is one rounding to nearest when `p >= q + 2`, here 41 >= 26 (Boldo and Melquiond, IEEE TC
  2008; the theorem's number and statement are quoted from its transcription, 5 step 4, not
  from memory). A seeded draw of the whole range agrees with the condition.
- **`D2L`, assembly** (whole range): from the bits, `e = ((b >>> 52) & 0x7FF) - 1075` and `m =
  (b & 0xFFFFFFFFFFFFF) | 2^52`; the magnitude is `m << e` for `0 <= e <= 10` and `m >>>
  min(-e, 63)` for `e < 0` (the Vector API takes a shift count modulo 64, so the count is clamped);
  the sign applied by `(r ^ s) - s`; `e >= 11` (`|x| >= 2^63`, the infinities, NaN) blends to
  `Long.MAX_VALUE`, `Long.MIN_VALUE` or 0. About fifteen `long` operations, `vpsllvq` and
  `vpsrlvq` being AVX2.
- **`D2L`, truncated magic**: `r = (x + M) - M`, the nearest integer, exact for `|x| < 2^51`;
  moved one toward zero where `|r| > |x|`; then `rawbits(r + M) - MB`, under a `GuardedRange` of
  `|x| < 2^51`.

The split and the assembly are the default arms and the bounded ones the reference arms of an
option `wideConversionForm` (`SPLIT`, `MAGIC`), read only where `convertsFallBack()` holds and
fuzzed on the laptop under `useAVX=2` (question 1). At the forced 128-bit species on x86 the
64-bit masks are fallbacks (`VARKA-376.md` 9), recorded in `width_audit.json` as 341 records its
compares.

### 2.4 The modes and the boundary cells

To a floating type, every mode is `ConvertLane`, TRY included (item 0.4). From one:

| target | LEGACY | ANSI | TRY |
|---|---|---|---|
| `INT`, `BIGINT` | `ConvertLane` | `ConvertLane(GuardedRange(x, lo, hi, FAIL))` | in `NULL` |
| `SMALLINT`, `TINYINT` | `NarrowLane(ConvertLane(x, INT))` | the same over the guard | in `NULL` |

`GuardedRange` gains a disposal: `FAIL`, today's decline, or `NULL`, which clears the validity of
a live lane outside the bounds and marks the kernel as one that nulls valid inputs (the masked
body, as `IntArith` in `NULL` does). At a floating lane its bounds are the lane's own bits,
inclusive, compared by IEEE, so NaN is outside. The bounds are Spark's `floor` and `ceil` test
rewritten as one interval, `(MIN - 1, MAX + 1)` open, closed at the nearest value of the source
type inside it:

| target | `DOUBLE` source | `FLOAT` source |
|---|---|---|
| `INT` | `[nextUp(-2^31 - 1), nextDown(2^31)]` | `[-2^31, 2147483520]` |
| `BIGINT` | `[-2^63, 2^63]` | `[-2^63, 2^63]` |
| `SMALLINT` | `[nextUp(-32769.0), nextDown(32768.0)]` | the float neighbours of the same |
| `TINYINT` | `[nextUp(-129.0), nextDown(128.0)]` | the float neighbours of the same |

The cells, each a named test in all three modes where it differs: each bound and its outer
neighbour (`2^63` passes ANSI and saturates to `Long.MAX_VALUE`, `nextUp(2^63)` raises;
`2^31 - 0.5` gives `Int.MaxValue`, `2^31` raises; `-2^31` from a `FLOAT` passes); NaN (0, raise,
null); both infinities (saturate, raise, null); `-0.0` to 0; LEGACY's wrap: `(byte) 128.0` is
-128, `(byte) 300.7` is 44, `(byte) 1e10` is -1 (`Int.MaxValue` wrapped); the truncation, on
every host's form and both AVX2 arms: `+-0.7` to 0 and `+-1.5` to `+-1`, where a form that
rounded instead of truncating gives `+-1` and `+-2` (item 0.6). To a floating type:
`2^24 + 1` to `FLOAT`, `Int.MaxValue` to `2^31`, `2^53 + 1` to `DOUBLE`, `2^53 + 2^29 + 1` to
`FLOAT` (`2^53 + 2^30`, item 0.6), `+-Long.MAX_VALUE` and `Long.MIN_VALUE`, 4275361147 to
`FLOAT` (4275361024, 2.6's named case); `D2F` at `Float.MAX_VALUE`'s half-ulp tie (to infinity)
and below it, at `Double.MIN_VALUE` (`0.0f`, and `-0.0f` for its negation), across the float
subnormals; NaN through `F2D` and `D2F` compared by NaN-ness (2.7's rule for a computed NaN).

### 2.5 Booleans and `DATE`

- `CAST(b AS FLOAT)` and `AS DOUBLE`: `CondValue` at the floating lane, `0.0` with `1.0` added
  under the known-true mask, its validity `kT | kF`; the constructor admits the two lanes.
- `CAST(x AS BOOLEAN)`: the condition `x <> 0.0`, IEEE's not-equal, which over `0.0` is #1065's
  answer too: NaN is unequal, both zeros are equal. It fuses wherever a condition does; as an
  output value it is row 27's.
- `CAST(d AS FLOAT)` and `AS DOUBLE` under LEGACY: row 333's typed null leaf at the target lane,
  as 332's `DATE` pairs; the step waits for 333.
- The identities `FLOAT` to `FLOAT` and `DOUBLE` to `DOUBLE` compile to the child where one
  reaches the compiler; the optimizer removes them, so they have no coverage row (row 328's rule).

### 2.6 `time_from_seconds` over a floating value

    s = x, or ConvertLane(f, DOUBLE)
    p = IntArith(MUL, WRAP, s, 1e9)                       342's floating multiply
    g = GuardedRange(p, nextUp(-1.0), nextDown(8.64e13), FAIL)    every mode
    n = ConvertLane(g, LONG)

`(long) p` is in `[0, NANOS_PER_DAY)` exactly when `-1 < p < 8.64e13`, and NaN and the infinities
fall outside, so the guard is Spark's two raises in one; inside it `|p| < 2^47`, where both AVX2
`D2L` arms are exact and the bounded one never declines. Cells: `-1e-9` raises (`p = -1.0`), its
`nextUp` gives 0; `86399.99999999999` gives 86399999999999; 86400 raises; the float below 86400
gives 86399992187500; `-0.0` gives 0.

### 2.7 Compiler, reference, cost, census

- A `VarkaFloatingCastCompiler` arm in a family `FLOATING_CAST` (`varka/VarkaFamily.java`), so the
  family switch turns it off; `floatingDecline` admits a `Cast` whose pair is in this row's table,
  and a `StaticInvoke` of `timeFromSeconds` over a floating argument after step 7.
- `VarkaReference` evaluates `ConvertLane` by Java's casts over the bits and `GuardedRange` at a
  floating lane and in `NULL`.
- The range analysis answers `UNKNOWN` for a node with a floating source, as 342's do.
- Cost: a `VarkaEmitCostRegister` feature per lowering of 2.3, fitted as every feature is;
  `VarkaEmitBudget` weighs an AVX2 form by its operation count until the fit.
- The warm-up blocks gain the conversions and the AVX2 forms' operators
  (`VarkaWarmupReachSuite`).

## 3. Tests and proofs

The oracle is Spark's own `Cast` and `TimeFromSeconds`, through the reference evaluator and the
differential suites. ANSI and TRY are checked per batch: a batch declines exactly when Spark raises
on one of its live rows, and TRY's nulls are Spark's. In the sweeps the raise set is Spark's own
predicate evaluated without a throw - `Math.floor(v) <= MAX && Math.ceil(v) >= MIN`, the
`FloatExactNumeric` and `CastUtils` test - and `Cast.eval` runs on the rows it admits, since a
thrown `CAST_OVERFLOW` costs microseconds and a large share of the float patterns raise.

1. **Exhaustion at 32 bits**, opt-in under `-Dvarka.sweep=true` and in the nightly, PR CI a slice:
   19 sweeps of 2^32: every `FLOAT` bit pattern to `BOOLEAN` as a condition (1), to the four
   integral types in LEGACY, ANSI and TRY (12), to `DOUBLE` (1, one answer in every mode) and
   through `time_from_seconds` (1); every `INT` to `FLOAT` and to `DOUBLE` (2); `FLOAT` to
   `BIGINT` under LEGACY again under `useAVX=2`, once per arm of `wideConversionForm` (2). Every
   `TINYINT`, `SMALLINT` and `BOOLEAN` value to both floating types besides, which are not 2^32
   sweeps. The 19 wait, as the milestone plan's 2.10 and `VARKA-329.md` 4.4 have it, for row
   330's first timed sweep and the milestone's budget total.
2. **The reduced-width twins**, scalar Java over 2^32 inputs, each the same construction at half
   width against Java's own cast: the split as `int` to `float` by 16-bit halves; the assembly as
   `float` to `int` from the exponent; the truncated magic as `float` to `int` with `0x1.8p23`
   below `2^22`; round-to-odd as unsigned 32-bit to `binary16` through a `binary32` intermediate
   with a 9-bit shift above `2^23`, against one rounding computed in integers - 2.7's
   "exhausted through `binary16`".
3. **The SMT claim**, `sql/varka/proofs/round_to_odd.smt2` under Z3 and cvc5 by
   `dev/varka_prove.sh`: for every 64-bit `m >= 2^52`, rounding `m` to 24 bits equals rounding `t
   << 12` to 24 bits, the rounding written in bit vectors (leading-zero count, guard and sticky
   bits). And the split's exactness, `(hi << 32) + lo = v` with each part inside its exact window.
4. **The named failing case** (`READING_0X80.md` item 3): the split-and-add form, the low 31 bits
   converted and `2^31` added, gives 4275361280 for 4275361147 against 4275361024, and misrounds
   about 16.5% of a seeded draw of `[2^31, 2^32)`; the test asserts that it fails and that the
   kernel's `L2F` gives 4275361024.
5. **Sampled `DOUBLE` and `BIGINT` regions** for every pair: both zeros, three NaNs, the
   infinities, the subnormals, every bound of 2.4 and its neighbours, `+-2^31`, `+-2^51`, `+-2^52`,
   `+-2^53`, `+-2^63` and their neighbours, and a seeded draw per exponent band, on the laptop's
   forms and under `useAVX=2` in both arms; 2.4's and 2.6's cells as a table test.
6. **The mixed-width kernels** in `VarkaEmitterMixedLaneSuite`: `INT` beside `DOUBLE`, `FLOAT`
   beside `LONG`, `FLOAT` beside `DOUBLE`, a narrow column beside each, at every width, the four
   null patterns, both bodies, in a JVM of their own (row 499 (a)).
7. **Coverage rows**, a family "floating casts": every pair of item 0.1 in each legal mode over a
   column and a literal, as a value and under a filter, `try_cast` among them, and
   `time_from_seconds` over `x` and `f`; `casts_fused.tsv` regenerated from them.
8. **Conformance**: the cast rows #1065's `DOUBLE` and `FLOAT` suites cancel naming 343 run.
9. **The fuzzers**: the IR grammar draws `ConvertLane` between its floating and integral
   sequences; the Spark fuzzer's grammar casts its floating columns.
10. **Generated files** in `ADDING_AN_EXPRESSION.md`'s order, then the PR build and the option
    matrix dispatched with `wideConversionForm` and `useAVX=2`.

## 4. Predictions and costs

The measurement reruns the baselines (5, step 1) with the fuse check lifted, on the runners' EPYC
7763 at 256 and 128 bits, diffed against the committed files.

1. **No existing shape's bytes move**; the `option_arms` digests move with the new shapes.
2. **The census**: `F2L` intrinsic on the laptop and the Neoverse N2 and a fallback on the EPYC
   (the AVX512DQ rule); the double `ADD`, `SUB`, `MUL`, the reinterpretations and the `long` shifts
   by a vector count intrinsic at each width each host's census reads: 512, 256 and 128 bits on
   the laptop, 256 and 128 on the EPYC 7763, 128 on the Neoverse N2.
3. **Against the row path at 256 bits**: `FLOAT` to `DOUBLE` at least five times faster than 38.4
   ns a row; `DOUBLE` to `BIGINT` under ANSI, two compares and the assembly, at least three times
   faster than 39.8; in the new class, `INT` to `DOUBLE` at least five times, `BIGINT` to `FLOAT`
   by round-to-odd and `time_from_seconds` at least three times.
4. **The AVX2 arms**: the split within 1.3 times the bounded magic's time, the assembly within 1.5
   times the truncated magic's, by minimums. The rule: the whole-range arm is the default unless
   it is more than 1.3 times slower, since the bounded arm declines what it cannot convert.
5. **Test time**: the 19 sweeps about 2.2 minutes nightly (19 x 7 s = 133 s), at `VARKA-335.md`'s
   7 s for a unary 2^32 sweep (ratio taken as 1), which holds only with the raise set taken
   without a throw (3). With `Cast.eval` throwing on every raising pattern it would be two to
   three orders of magnitude more: 38% of the `FLOAT` patterns raise for `INT` (biased exponent
   158 and above, 98 of 256) and 48% for `TINYINT` (134 and above, 122 of 256), so at a
   microsecond a throw one `INT` sweep alone costs about 1600 s against 7; the
   four twins under 40 s; the SMT claims under 60 s (shifts and
   compares, no multiply); the coverage rows under 60 s, at `VARKA-336.md`'s ratio.

Cost: large, against the row's medium. Row 341's lane split alone was large at five days; this
row, step by step after the baseline (5): the census cells half a day; `ConvertLane` and the width
rules across the kernel plan, `Analysis`, `Slots` and the walk, with the mixed-width kernels and
the first sweeps, three days; the paper, the four AVX2 forms, the option, the twins, the SMT
claims and the failing case, three and a half; the floating `GuardedRange` and its `NULL`
disposal through the masked body, with the three modes' cells and sweeps, two; the booleans half
a day; `time_from_seconds` one; the generated files and the measurement one. About eleven days.

It lands as two pull requests after the baseline, each with its own measurement against it:

- **343a, the conversions to a floating type** (steps 2, 3 and 6): `ConvertLane` with
  `convertShape`, the width rules, the to-floating pairs, `FLOAT` with `DOUBLE`, the booleans;
  `L2D` and `L2F` decline where `convertsFallBack()` holds; and the same-count `VectorMask.cast`
  crossing #1065 leaves owed, without which `DOUBLE` beside `BIGINT` shares no kernel. About five
  days, the crossing half a day of them. It unblocks 342's
  `i / j`, `f / g` and the functions over a cast on every host, and those over a `BIGINT` on the
  laptop and the Neoverse N2.
- **343b, the conversions from one and the AVX2 forms** (steps 4, 5 and 7): the paper, the four
  forms, floating to integral in three modes with the `NULL` disposal, `time_from_seconds`. About
  six days. It unblocks 342's interval forms, the `BIGINT` forms on the EPYC runners, and the
  disposal that 332's TRY narrowing reuses: 332's plan, when written, takes this disposal rather
  than building its own.

The `DATE` pairs follow 333 in either.

## 5. Order and files

1. **The baseline**, its own pull request on the unchanged code:
   `VarkaFloatingCastBaselineBenchmark` over `CAST(l AS FLOAT)`, `CAST(l AS DOUBLE)`,
   `CAST(i AS DOUBLE)`, `CAST(w AS INT)` in LEGACY, `CAST(x AS INT)` in ANSI,
   `try_cast(w AS BIGINT)`, `CAST(x AS FLOAT)`, `coalesce(CAST(x AS BOOLEAN), false)` under a
   filter and `time_from_seconds(s)`, with and without nulls, under the shared fuse check.
   *31 October 2026, as built:* the fuse check is the per-section one row 342's baseline built
   (`VarkaSqlBenchmarkBase.requireBaseline`, lifted by `-Dvarka.baseline.fuseCheck=false`),
   which 377's class already has, so this step adds none. The `try_cast` reads its own column
   `w`, about one row in a hundred past the long range (at one in ten the section timed mostly
   Spark's TRY path raising and catching an exception per such row) and one row in a thousand
   each NaN, `+Infinity` and `-Infinity`, and LEGACY's `CAST(w AS INT)` reads it too, so its
   saturation runs, about six rows in seven being past the int range; `time_from_seconds` reads
   its own `s`, inside the day: an ANSI cast or a `time_from_seconds` with a value out of range
   raises and ends the run, so `x`, which ANSI's `CAST(x AS INT)` reads, stays inside the int
   range. The filter's condition is
   `coalesce(CAST(x AS BOOLEAN), false)`: from the bare cast the optimizer infers
   `x IS NOT NULL`, and that conjunct alone fuses into a `VarkaFilterColumnarToRow` under
   Spark's `Filter`, so the fuse check could not see this row start fusing the cast; `coalesce`
   infers nothing. No section is listed as fusing, and none plans a Varka node (checked by the
   class's dry run, 31 October 2026). The results files at both widths come from the EPYC
   runners and are committed before it merges.
2. **The census cells** (item 0.5), on the laptop and through the census workflow on both runners.
3. **`ConvertLane` with `convertShape`** and the kernel rules (2.1, 2.2), with the same-count
   `VectorMask.cast` crossing #1065 leaves owed, built here first: the to-floating pairs and
   `FLOAT` with `DOUBLE`, tests 3.1, 3.6 and 3.7 for them.
   This step unblocks 342's `i / j`, `f / g` and the functions over a cast.
4. **The round-to-odd paper, then the AVX2 forms** (2.3) behind `wideConversionForm`, with 3.2
   to 3.5. First Boldo and Melquiond, "Emulation of a FMA and correctly-rounded sums: proved
   algorithms using rounding to odd" (IEEE Transactions on Computers 57(4), 2008), into
   `sql/varka/papers/` as its `README.md` says: from the open-access deposit by `pdftotext
   -bbox-layout`, the header naming this row, and its row in the README's contents; if the
   deposit's terms do not permit a copy, the reading notes and the citation instead. The theorem
   the `L2F` form rests on is then quoted, with its number and its precision condition, at 2.3
   and in 3.3's proof file, read from the PDF. Nothing is downloaded before this step.
5. **Floating to integral** in the three modes (2.4), the `GuardedRange` disposal with it: 342's
   interval forms are unblocked here.
6. **The booleans** (2.5), and the `DATE` pairs once 333 lands.
7. **`time_from_seconds`** (2.6), after 342's same-lane pull request; its first test is that
   Spark analyzes and evaluates `time_from_seconds(f)` over a `FLOAT` column, since item 0.4's
   overload resolution is read from the matcher's rule, not observed.
8. **The generated files, the measurement** and its verdict.

| 342's form | what it needs from this row | step |
|---|---|---|
| `i / j`, `y / z`, `sqrt(i)`, `signum(i)`, `degrees(i)` | `I2D`, the narrow load at 64 bits | 3 |
| `l / m` and the functions over a `BIGINT` | `L2D` | 3, 4 on AVX2 |
| `f / g`, `sqrt(f)`, a `FLOAT` interval factor | `F2D` | 3 |
| `ym * x`, `ym / x` | `I2D`, `D2I`, the floating guard | 3, 5 |
| `dt * x`, `dt / x` | `L2D`, `D2L`, the guard; whole range on AVX2 under question 1 (b) | 4, 5 |

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `ConvertLane`; `GuardedRange`'s disposal and floating bounds |
| `varka/VarkaConversionLowering.java` | the AVX2 forms of 2.3 |
| `varka/VarkaVectorWalk.java`, `Slots.java`, `Analysis.java` | conversions, widths, null disposal |
| `varka/VarkaKernelPlan.java` | the drive and sharing rules of 2.2 |
| `varka/VarkaEmitOption.java`, `VarkaEmitOptions.java` | `wideConversionForm` |
| `VarkaFloatingCastCompiler.java`, `varka/VarkaFamily.java` | the arm and its family |
| `VarkaNodeCompiler.java`, `VarkaTimeCompiler.java` | `floatingDecline`, `time_from_seconds` |
| `varka/VarkaEmitBudget.java`, `VarkaEmitCostRegister.java` | the conversion features |
| `sql/varka/proofs/round_to_odd.smt2`, `VarkaProofFiles.java` | 3.3's claims |
| `sql/varka/papers/boldo-melquiond-2008-rounding-to-odd.md`, its `README.md` | step 4's paper |
| tests | sweeps, twins, cells, mixed-width kernels, coverage rows, failing case, fuzz grammar |
| benchmark | the baseline class, its results files at both widths from the EPYC runners |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `operator_census.json`, `census.tsv`, `casts_fused.tsv` |

## 6. Out of this row

The decimal pairs (346, 350, 364, 366); string, zoned `TIMESTAMP` and interval casts with a
floating side, which Spark does not allow for intervals; `try_eval` around a cast; 344's rounding,
which reuses the truncated magic's first two operations; the `BOOLEAN` output (row 27).

## 7. Declines that stay

A batch with a live lane Spark raises on (ANSI's range, `time_from_seconds` outside the day);
under question 1 (a), a `BIGINT` or `DOUBLE` lane past `2^51` on AVX2; the `DATE` pairs until 333;
between the two pull requests, `L2D` and `L2F` wherever `convertsFallBack()` holds.

## 8. Questions for the owner

None open; both were settled with an advisor's review on 27 October 2026.

1. **The AVX2 form past `2^51`: the whole-range split and assembly are the default arms,** the
   magic-number form the option's reference arm, the default by 4's rule. The rule to keep losing
   arms behind options settles it, and the row's "the host named" clause asks for one IR on every
   host: the magic form alone would decline a lane past `2^51` on AVX2 and not on AVX-512, and
   microsecond timestamps held as `BIGINT` sit near `2^50.6` today.
2. **`time_from_seconds` follows 342's first pull request.** Its lowering (2.6) is a `DOUBLE`
   multiply by the literal `1e9`, a same-lane form of 342's first part, then this row's own `D2L`
   (343b); so it waits for 342's first part, not all of 342. Admitting the multiply here would
   make two rows own one node.

The size, large and in two parts (4), was confirmed by the owner on 27 October 2026: one row
landing in two pull requests, 343a and 343b.

The baseline comes first, as its own pull request (5.1): not a question, since the house rule
settles it, as it did for rows 377, 27, 339 and 342.

## 9. Done when

Row 343's text holds, with the corrections above: every pair of item 0.1 fuses in each mode
Spark allows it, the `DATE` pairs through 333's leaf; a `FLOAT` source proven over all 2^32 bit
patterns and an `INT` source over all 2^32 values (3.1); the round-to-odd truncation stated as
3.3's bit-vector claim under both solvers and exhausted through `binary16` (3.2); the split-and-add
form kept as 3.4's named failing case; the `DOUBLE` and `BIGINT` sources over 3.5's regions and
the fuzzer, on both hosts' forms; 2.4's and 2.6's cells pass; no existing shape's bytes move; the
PR build and the option matrix pass; the measurement is committed with its predictions scored.

## 10. Outcome

(Written when the work lands.)
