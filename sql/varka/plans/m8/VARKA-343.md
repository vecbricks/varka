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
     it (`varka/VarkaDivisionLowering.java:361`). The `useAVX` option sets it in a test, and a
     session sets it from the driver's JVM once row 538 feeds it the host.
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
joins roots of one width (343a3 builds the crossing within a root's tree; the join of roots of
two kinds is part 343a3b's, 3 November 2026). A mask crosses to a lane of another count through
the word, as
`emitToDrivingLane` does, and to one of the same count by `VectorMask.cast`. #1065 leaves that
crossing owed (its `VARKA-341.md` 5.1); 343a3 builds it, so it closes 341's owed item, and 341's
record names 343a3 when this lands. A narrow column in a 64-bit kernel - `CAST(y AS DOUBLE)` -
needs the narrow load at the long width that 332's `CAST(y AS BIGINT)` needs too; the first of the
two rows to land builds it.

#### 2.2.1 The root-level crossing (part 343a3b, planned 4 November 2026)

343a3 crosses a mask between the kinds inside one root's tree; what it left is the meeting of
conditions of two kinds, which every connective, boolean choice and filter makes, since `And`,
`Or`, `CondIf` and `BoolCompare` refuse operands of two lanes. Two designs meet them: one root a
kind, the filter's clauses ANDing the selection bitmaps after the kernel (`CompiledVarkaPredicate`
already ANDs a split predicate's clauses), or a crossing inside the kernel. The first leaves the
connectives and the boolean choices of one tree unserved, needs a second bitmap a batch and a
split that keeps the kinds apart, and joins cross-width roots that the projection side does not;
so the crossing is the one built.

- **The node.** `CrossLane(Cond child, LaneType lane)`: the child's three-valued answer read at
  another lane, refusing a narrow lane and the child's own. In the masked body it is free - the
  known-true and known-false words are a bit a row whatever the lane, so its words alias the
  child's, as `Not`'s swap does. In the dense body it is the child's mask as one of the other
  lane's species: by `VectorMask.cast` between lanes of one width, which have one lane count, and
  by the word, `toLong` then `fromLong`, between the widths, as `emitToDrivingLane` crosses a
  32-bit mask to a 64-bit drive (a 32-bit mask's low lanes are the group's rows; a 64-bit mask's
  word read by a 32-bit species sets only its low half). Neither pair is the one C2 miscompiles,
  which is `fromLong` of `toLong` at one lane count (VARKA-396). The reference and the range
  analysis read it as its child; the cost model gives it a feature of its own,
  `CrossLane/<from>/<to>` (`VarkaEmitCost.kindOf`), unpriced until the refit (VARKA-352).
- **Where the compiler crosses.** One meeting rule for a list of conditions, `meetAll` for the
  connectives and boolean choices and the filter's conjunct fold alike. Conditions of the
  integral kind alone meet as before (narrow to `INT`, `INT` to `LONG` by `onLongLane`), so no
  existing shape moves. Where a floating condition is among them, they meet at one lane by the
  width rule of 2.2 read over the conditions' lanes - `LONG` if one is, else `DOUBLE`, else `INT`,
  else `FLOAT` - the integral ones lifted among themselves first; each condition still off it is
  crossed, a boolean column or constant relabelled instead (its bit does not depend on the lane).
  So `frid < 0 AND CAST(i AS FLOAT) = f2 AND isnotnull(i)` is one `INT` root with the float
  conjuncts cast across, and `xrid < 0 AND CAST(i AS DOUBLE) = x2 AND isnotnull(i)` one `DOUBLE`
  root with the int null test crossed by its word. `floatingConjunctRefusal` and its reason go.
- **The projection's roots.** `lanesShare` joins roots of one width: `INT`, `FLOAT` and the narrow
  lanes at `INT`, `LONG` and `DOUBLE` at `LONG`. A 32-bit root beside a 64-bit one keeps a kernel
  of its own, as an `INT` root beside a `LONG` one does (VARKA-480): each root's tree chooses its
  own drive lane (`VarkaKernelPlan.emissionLane`), so a root with no 64-bit node never joins a
  64-bit kernel, though such a kernel stores the 32-bit roots its converting trees give from the
  low half of their species (`VarkaBodyEmitter.emitLowHalfStore`).
- **The epilogue** needs nothing new: a crossed mask is computed from loads the epilogue already
  masks, and every store and selection word is truncated to the group's rows.
- **The value choice** (*4 November 2026, the owner's choice of open point 1*): an `IfElse`
  whose condition is of the other kind than its values - `CASE WHEN f > 1.5 THEN i END`,
  `IF(i > 3, x, x2)` - crosses that condition to the values' lane with the same `CrossLane`, a
  narrow one lifted to `INT` first (`VarkaConditionCompiler.onValueKind`); nothing new in the IR.
  A `LONG` condition is of the integral kind, so over floating values it crosses too:
  `IF(l > 5, f, f2)` reads the long mask's word at the float lane. A boolean column condition
  reads no lane and is relabelled to the values' lane instead: `IF(b, x, x2)`,
  `CASE WHEN b THEN f ELSE f2 END`. Two integral kinds meet as before, so
  `CASE WHEN l > 5 THEN i END` still declines: the crossing is only between the kinds, and the
  integral lift goes to `LONG` only where the values are long.
- **Still declines:** a choice of narrow values under a floating condition; a floating condition
  over values of the other floating width, `IF(f > 1.5F, x, x2)` and `IF(x > 1.5D, f, f2)`: one
  kind, so not crossed, and Spark's typing does not widen it; any combination the
  kernel plan refuses (a narrow node under a `DOUBLE` kernel but a
  column read as a value, among them a narrow null test there); and on a two-lane x86 host a
  condition in a 64-bit kernel, by the host refusal (VARKA-503).
- **Tests:** `VarkaEmitterFloatingCastSuite` holds kernels joining conditions of two kinds - each
  pair of lanes, both widths, both the cast and the word - to the reference at three widths, the
  ten lengths, four null patterns and both bodies, and the projection kernels of an `INT` and a
  `FLOAT` root and a `LONG` and a `DOUBLE` one; the compiler suite states the meeting (one root,
  where `CrossLane` sits, the relabelled boolean column); coverage rows for the filter forms now
  fused, `CAST(CAST(l AS INT) AS FLOAT)` among them; the freed `VarkaAnsiForm.DECLINED` entries
  removed, which the strict check enforces.

*Open points for the lead* (4 November 2026): whether the value choice of the other kind
(`CASE WHEN f > 1.5 THEN i END`) belongs in this part (yes, by the owner's word: above); and
whether the projection side should join 32-bit and 64-bit roots,
which needs one drive chosen across the roots.

### 2.3 The lowering per host

| conversion | AVX512DQ (laptop) | AVX2 (EPYC 7763) | NEON and SVE (Neoverse N2) |
|---|---|---|---|
| `I2F`, `I2D`, `F2I`, `D2I`, `F2D`, `D2F` | `convertShape` | `convertShape` | `convertShape` |
| `L2D` | `convertShape` | split (below), or the bounded magic | `convertShape` |
| `L2F` | `convertShape` | round-to-odd (below) | `convertShape` |
| `D2L` | `convertShape` | assembly (below), or the truncated magic | `convertShape` |
| `F2L` | `convertShape` | `F2D`, then `D2L`'s form | `convertShape` |

The host is `convertsFallBack()` (item 0.5), already in the shape key, once row 538 feeds it the
host. The AVX2 forms live in a
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
  2008). A seeded draw of the whole range agrees with the condition.
  *4 November 2026, read from the PDF* (`READING_ROUNDING_TO_ODD.md`; the deposit's terms permit
  no transcription, 5 step 4): **Theorem 3 (To_Odd_Even_is_Even)**, "Assuming p >= 2, k >= 2,
  and E_e >= 2 + E_w, for all x in R, round_p(odd_{p+k}(x)) = round_p(x)", the extended format
  being `(p + k, E_e)` and the working one `(p, E_w)`, `2^-E` a format's smallest positive
  number. The precision condition is `k >= 2`. Here `p = 24`; for `m` in `[2^j, 2^(j+1))`,
  `j` from 52 to 63, the 12-bit truncation with its sticky bit is rounding to odd at `j - 11`
  bits (section III-B's construction), so `k = j - 35 >= 17`; `E_e = 1074 >= 2 + 149`, and the
  values are integers far from either format's subnormals. Since `k` moves with the binade,
  3.3's proof states the instance per binade.
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
   *As built (4 November 2026, 343b1's groundwork, section 10):* `round_to_odd.smt2`, rendered by
   `VarkaProofFiles.roundToOdd`, states the claims over SMT-LIB's floating-point theory rather
   than a rounding written by hand in bit vectors, so the reference is IEEE 754's own conversion
   (`to_fp` under `RNE`, JLS 5.1.2) and no hand-written rounding can be wrong with it: the
   rounding of `t << 12` equals the rounding of `m` per binade (Theorem 3's instance), and beside
   it the whole `L2F` form of 2.3, magnitude and sign, is `(float) v`. The split is three lemmas;
   its last step, the one rounding of the exact `dh + dl`, is IEEE addition's definition, not a
   check (stated whole, Z3 passed 30 s and cvc5 took about 17 s). Each check runs after a
   `(reset)`, not between `push` and `pop`: once a file pushes, Z3 answers from its incremental
   core, where one 64-bit `fp.sub` took about 6 s and four checks passed the 10 s limit (the
   whole file about two minutes), while the same checks in fresh contexts take the file about
   5 s under each solver on the laptop, the slowest check about 2 s.
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

It lands in pull requests of under 1000 changed lines each of hand-written source and plan
text; generated files are not counted (*3 November 2026, the owner's word*: this replaces the
two parts, 343a and 343b, of 27 October). The work built for 343a on #1303 is cut into the first
five; each is taken from that branch's final tree, not by replaying its commits, and carries its
own tests and its own regenerated files. The sizes in the table include the generated files, so
they are upper bounds.

| part | what | depends on | lines, about |
|---|---|---|---|
| 343a1 | `CAST(x AS BOOLEAN)` over a `FLOAT` or `DOUBLE` (2.5): the lane's `EQ` against zero, negated, in the condition compiler; its coverage rows | master | 200 |
| 343a2 | `ConvertLane` (2.1) with its refusals, the reference's casts and the range analysis, the `convertShape` lowering, and the compiler arm for the one-count pair, `INT` to `FLOAT`; the reference cells and the refusal tests | master | 750 |
| 343a3 | the kernel of two lane counts (2.2): the width rules in the kernel plan, `Analysis`, `Slots` and the walk, the warm-up blocks, and the pairs it carries, `INT` to `DOUBLE`, `FLOAT` with `DOUBLE`, the narrow sources, a condition as a value and the mixed trees (`x + i`, `f * i`, `f + x`); the three failures #1303's last matrix found are fixed here, the `INTERNAL_ERROR` under `validityByWidth=false` as a defect, not a skip | 343a2 | 950 |
| 343a3b | the root-level crossing (2.2): roots of two kinds share one kernel (`lanesShare` joins roots of one width), so a filter's floating conjunct beside an integral one - the null test the optimizer infers on a cast's source among them - no longer declines (`floatingConjunctRefusal`), and an integral and a floating output take one kernel; the forms `VarkaAnsiForm.DECLINED` lists for it fuse, and the coverage row `CAST(CAST(l AS INT) AS FLOAT)` is added, whose domain check's filter form needs this join. Added by the owner's word of 3 November 2026, when 343a3 stayed under the cap without it. *Owed by 343a3 (10):* `CompiledVarkaProjection`'s long-literal check plans under `VarkaEmitOptions.DEFAULTS`, so a 128-bit host with `lanesOverride=4` fails `CAST(y1 AS DOUBLE) / 2.0D` with `INTERNAL_ERROR`; check under the session's options, as row 332's load needs too. *Sized 4 November 2026 (2.2.1):* a `CrossLane` node, the meeting rule and `lanesShare`'s width join, about 600 hand-written lines with the tests; one part. *Added 4 November 2026 by the owner's choice:* the value choice whose condition is of the other kind, its condition crossed to the values' lane | 343a3 | 600 |
| 343a4 | `BIGINT` to `FLOAT` and `DOUBLE` where the host has AVX512DQ: level 3 resolved from the host, the unknown level declining them, the option matrix's level-3 runs and their Spark check | 343a3b | 450 |
| 343a5 | *(built, 10)* the fuzz grammar's conversions, the 2^32 sweeps of 3.1 and the harness check of each fused cast on both lanes. *Owed by 343a3b (10):* the false-AND forms of `CAST(y1 AS DOUBLE)` and `CAST(s1 AS DOUBLE)`, which still decline: the narrow null test the optimizer infers on the source has no lowering under a double kernel, as their reason in `VarkaAnsiForm.DECLINED` names; a narrow column's null test at the long width, or the test kept on its own kernel, frees them | 343a4 | 400 |
| 343b1 | the paper and the AVX2 forms (2.3, step 4): `L2D` by the exact split, `L2F` by round-to-odd, behind `wideConversionForm`, the SMT claims and the failing case. *4 November 2026:* the groundwork - the paper's reading notes (5 step 4) and `round_to_odd.smt2` (3.3) - is built on branch `varka-343b1-proofs`, touching no kernel, emitter or compiler code; the forms, the option and the failing case are owed (section 10) | 343a5 | 900 |
| 343b2 | floating to integral in the three modes (2.4, step 5), with `GuardedRange`'s floating bounds and `NULL` disposal; `D2L`'s assembly on AVX2 | 343b1 | 900 |
| 343b3 | `time_from_seconds` (2.6, step 7), after 342's first pull request, and the measurement against the baseline (step 8) | 343b2 | 500 |

343a3 is the one nearest the cap: if it passes 1000 lines, the mixed trees and the condition as a
value move to a part of their own after it. *As built (3 November 2026, section 10):* it stayed
under the cap whole, and it also carries `TINYINT` and `SMALLINT` to `DOUBLE`, since row 332
had built the narrow load at the long width. 343a1 needs none of the others and may land first.
Only a real code dependency chains one part's matrix on another's. A part over the cap is split
again before it is pushed, never merged over it.

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
   *As built (4 November 2026, 343b1's groundwork):* the HAL deposit (`inria-00080427v2`, the
   authors' manuscript) carries the HAL Authorisation v1 - read, consult and cite, and ask the
   author before reproducing - so no transcription is kept: `READING_ROUNDING_TO_ODD.md` holds the
   reading notes and the citation, and `papers/README.md` says so in a paragraph, with no row in
   its contents. The theorem is quoted at 2.3 and in `round_to_odd.smt2`'s header.
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
| `READING_ROUNDING_TO_ODD.md`, a paragraph in `papers/README.md` | step 4's paper: notes and the citation, the deposit's terms permitting no transcription (*4 November 2026*; planned as `papers/boldo-melquiond-2008-rounding-to-odd.md`) |
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
until 343a4, every `BIGINT` source; until 343b1, `L2D` and `L2F` wherever `convertsFallBack()`
holds.

## 8. Questions for the owner

None open; both were settled with an advisor's review on 27 October 2026.

1. **The AVX2 form past `2^51`: the whole-range split and assembly are the default arms,** the
   magic-number form the option's reference arm, the default by 4's rule. The rule to keep losing
   arms behind options settles it, and the row's "the host named" clause asks for one IR on every
   host: the magic form alone would decline a lane past `2^51` on AVX2 and not on AVX-512, and
   microsecond timestamps held as `BIGINT` sit near `2^50.6` today.
2. **`time_from_seconds` follows 342's first pull request.** Its lowering (2.6) is a `DOUBLE`
   multiply by the literal `1e9`, a same-lane form of 342's first part, then this row's own `D2L`
   (343b2); so it waits for 342's first part, not all of 342. Admitting the multiply here would
   make two rows own one node.

The size, large, was confirmed by the owner on 27 October 2026, in two parts; on 3 November 2026
he set a cap of 1000 changed hand-written lines per pull request, so the row lands in the parts
of 4: eight then, nine since 343a3b was added the same day.

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

### 343a3 as built (3 November 2026)

Cut from #1303's final tree, without its `BIGINT` conversions (343a4), sweeps and fuzz (343a5).

- **The width rules** (2.2): `emissionLane` drives a tree holding a `LONG` node at the long lane,
  one holding a `DOUBLE` node otherwise at the double lane, and a 32-bit tree at the int lane
  over the float one; `Analysis`' fit rule is by width. A 32-bit value of a 64-bit kernel takes
  the low half of its species of the drive's width, a value of the other kind of that width its
  whole species. The plan admits `I2F`, `I2D`, `F2D` and `D2F` (`convertShape`) and refuses a
  `BIGINT` source (343a4) and a floating one to an integral lane (343b2), `Kind.CONVERSION`.
- **The pairs**: `INT`, `TINYINT`, `SMALLINT` and `BOOLEAN` to both floating types, `FLOAT` with
  `DOUBLE`, a bigint narrowed to `INT`, and type coercion's mixed trees (`x + i`, `f * i`,
  `f + x`, `i / 2.0D`). A condition converts as `CondValue` at the int lane, a long one narrowed
  first, so none needs `L2F` or `L2D`. *Departure from 2.2:* `CAST(y AS DOUBLE)` takes row 332's
  load at the long width (#1303 declined it); any other narrow node there declines.
- **The mask crossing at one lane count** #1065 left owed (`VARKA-341.md` 5.1): a guard's mask,
  an arm's condition and the epilogue's bounds mask cross between the kinds of the drive's width
  by `VectorMask.cast`, both ways at both widths; the warm-up gains `F2D`, `D2F` and the four
  casts. *Not built:* roots of two kinds share no kernel (`lanesShare`) and a filter's floating
  conjunct beside an integral one declines: part 343a3b's (4); `VarkaAnsiForm.DECLINED` lists it.
- **Tests**: `VarkaEmitterFloatingCastSuite` holds each conversion and the two-lane kernels to
  the reference at three widths, ten lengths, four null patterns and both bodies, and a zero
  divisor's decline to its row's validity; the "Floating casts" coverage rows, a long-driven
  kernel of float and double values among them, are checked against Spark by the harness.
- **The three failures of #1303's last option matrix** (run 41572066746):
  1. *Every configuration*: a test said a narrow value in a two-lane 64-bit kernel declines; row
     332 built that load, so it fuses where a long lane count is baked, else the plan declines it.
  2. *`lanesOverride=4` and `16`*: the `FLOAT`, `DOUBLE` and `BOOLEAN` conformance suites' casts
     fuse two-width kernels whose int species is not the 32-bit suites', which the shared JVM's
     guard refused (at 16 the long lane has none; int values take the preferred, 256 bits on EPYC,
     beside `SPECIES_512`): `ownJvm` now.
  3. *`validityByWidth=false`*: an emitter test handed the emitter a plan that refuses it, which
     threw `INTERNAL_ERROR`; it now asserts the refusal. The SQL path declined cleanly all along.

  Each passed on the laptop under its configuration (`dev/varka_matrix.sh`). The six new
  `skips.tsv` lines fix none: they expect the new narrow-column rows to decline where no lane
  count is baked,
  `validityByWidth=false` and, for `DOUBLE`, `lanesOverride=16`.
- **A known limit**, shared with row 332: `CompiledVarkaProjection` checks a long literal table
  against the drive under `VarkaEmitOptions.DEFAULTS`, which refuse what the session admits at
  128 bits with `lanesOverride=4`, `CAST(y1 AS DOUBLE) / 2.0D`: planning raises `INTERNAL_ERROR`.
  No CI step pairs them (the matrix sets it only on x86 of 256+ bits); no SQL conf sets it. Owed:
  343a3b.
- **Prediction 4.1** holds: only the new rows' shapes and the `option_arms` digests move.

### 343a3b as built (4 November 2026)

- **As planned in 2.2.1:** `CrossLane(Cond, LaneType)`, free in the masked body (its words alias
  the child's) and in the dense body `VectorMask.cast` at one width, `toLong` then `fromLong`
  across the widths (`VarkaVectorWalk.emitMaskAcross`); one meeting rule,
  `VarkaConditionCompiler.acrossKinds`, for `meetAll` and the filter's conjunct fold, a boolean
  column or constant relabelled rather than crossed; `floatingConjunctRefusal` and its `Kind`
  removed; `lanesShare` joins `INT`, `FLOAT` and the narrow lanes at `INT` and `LONG` with
  `DOUBLE`. Conditions of the integral kind alone meet as before; prediction 4.1 holds - the
  bytes file gains the new rows' shapes and moves only `option_arms` digests.
- **Now fused:** a filter's floating conjunct beside an integral one, the optimizer's inferred null
  tests among them, and the boolean column beside a floating cast's test; a value choice whose
  condition is of the other kind (below), `IF(l > 5, f, f2)`'s `LONG` condition over floating values
  among them; and a boolean column, relabelled to the values' lane, choosing floating values: `IF(b,
  x, x2)`, `CASE WHEN b THEN f ELSE f2 END`. Freed from `VarkaAnsiForm.DECLINED`: the six
  boolean-column positions of `CAST(x AS BOOLEAN)` and `CAST(f AS BOOLEAN)`, and 17 of the 19
  floating casts' false-AND positions. *Still declined:* `CAST(y1 AS DOUBLE)` and `CAST(s1 AS
  DOUBLE)` there: their inferred narrow null test has no lowering under a double kernel (2.2.1).
- **Coverage:** the family "Floating casts as conditions", six rows, and the owed
  `CAST(CAST(l AS INT) AS FLOAT)`, whose false-AND form is served and declines its batch on the
  extremes row, the right side of an AND unqualified like the integral casts' (m9 item 96).
- **The owed literal check:** `CompiledVarkaProjection.requireLiteralTables` plans under the
  compile's options, called from the compiler's tables; a plan they refuse is no kernel to check.
  The compiler suite checks it under options admitting and refusing `CAST(y1 AS DOUBLE) / 2.0D`.
- **Beside it:** `VarkaFloatingCastBaselineBenchmark`'s boolean filter, `coalesce(CAST(x AS
  BOOLEAN), false)`, fuses now (its `false` relabelled to the cast's lane), so its class is `WHOLE`,
  as the class's own rule asks; the long harness hands a kernel holding a crossing the int table
  too; the cost model and the IR fuzz grammar leave `CrossLane` out until the refit (VARKA-352) and
  row 343's fuzz sequence (343a5); `VarkaCanonicalIr` reads `(cross:LANE ...)` back, which the round
  trip of every node type holds. The touched catalyst suites and the sql harness, differential,
  floating-lane, filter and baseline suites passed under the defaults, `lanesOverride=4`,
  `lanesOverride=16` and `validityByWidth=false` on the laptop (`dev/varka_matrix.sh`, 4 November
  2026); each leg's one rewritten test expects a date-guarded floating choice to fuse now.
- **Known gaps:** `VarkaCanonicalIr` still reads no boolean node (`BoolColumn`, `CondConst`,
  `BoolCompare`, `CondIf`, `BoolNullSafeEq`; `VarkaReproductionSuite.NOT_PARSED_YET`). The
  `option_arms` digests hash every shape per option arm and lane count, so their moving cannot show
  that no shape changed under a non-default option; only the defaults' bytes are compared per shape.
- **The value choice of the other kind**, by the owner's choice: its condition crossed to the
  values' lane, both directions and both widths; `VarkaEmitterFloatingCastSuite.crossingShapes`
  holds it to the reference, beside a crossing under a crossing, a range set crossed to `LONG`,
  a choice and a `CondValue` over a mixed `AND`, `NOT` and `OR` both under and over a 64-to-32
  crossing, a double null test crossed down and `IF(l > 5, f, f2)`'s long condition; nine
  coverage rows in "Floating casts" and two in its conditions' family. Its forms make
  `IF(x <=> x, d2, ...)` fuse, which the compiler suite now states.
- **Open points:** 32-bit beside 64-bit roots stay open, `m9/SCOPE.md` item 15 (the owner's word).

### 343a4 as built (6 November 2026, branch `varka-343a4-level3`)

Cut from #1303's final tree and rebuilt on master, since master had moved under the files: the
`BIGINT` to `FLOAT` and `DOUBLE` casts fuse where the emit level assures AVX512DQ.

- **The admission.** `VarkaFloatingCastCompiler.admits` takes a `BIGINT` source, and
  `VarkaKernelPlan.conversionRefusal` reads the options: `VarkaEmitOptions.convertsMayFallBack()`
  (a level below 3, and the unknown one) declines `L2F` and `L2D` by name; a floating source
  converted to an integral lane still declines at every level. The emitter lowers both through
  `convertShape`, part 0.
- **Why a second predicate.** `convertsFallBack()` picks the division's form and is optimistic at
  the unknown level, where a wrong guess costs nothing. Whether a kernel exists is the opposite
  error, a scalar Java loop inside a vector kernel, so the unknown level, the defaults' and an
  unset session's, declines.
- **The session.** `VarkaSessionEmit.resolve` maps an x86 host whose feature list was read and holds
  AVX512DQ to level 3, a host without it to level 2 as before, and everything else (aarch64, an
  unreadable list) to the unknown level; `SQLConf` and `VarkaFamily` say so. Level 3 keys apart
  from the unknown level, so such a session caches its classes apart, with the same bytes.
- **The matrix.** `VarkaMatrixRuns.emissionKey` has three classes, and `useAVX=3` runs as its own
  configuration, the only one whose answer tests reach the kernel. `VarkaTypeConformance` takes
  the `BIGINT` pairs from the level, not the register, which is written at the unknown level.
- **Tests.** `VarkaEmitterFloatingCastSuite` holds five `BIGINT` shapes (to each type, beside a
  double column and a float one, under an ANSI division) at every width, and the refusals at the
  unknown level and below 3; `VarkaDifferentialHarnessSuite` compares eighteen extremes and
  rounding cells with the row engine at level 3. No emitted-bytes, census or coverage record moves.
- **Not here:** the AVX2 forms (343b1), which make the casts fuse at every level, and the fuzz
  grammar and sweeps (343a5).

### 343b1's groundwork as built (4 November 2026)

On branch `varka-343b1-proofs`, from master; no kernel, emitter or compiler code is touched.

- **The paper** (5 step 4): its HAL deposit carries the HAL Authorisation v1, which permits
  reading and citing and not a copy, so `READING_ROUNDING_TO_ODD.md` holds the notes and the
  citation and `papers/README.md` says why; Theorem 3 is quoted at 2.3 and in the proof's header.
- **The proof** (3.3), `round_to_odd.smt2`, 45 checks, 5.0 s under Z3 and 5.2 to 5.3 s under
  cvc5 on the laptop (`dev/varka_prove.sh --solver both`, two runs), 5.3 s of the `--lint` run,
  whose whole folder took 30 s on the laptop. What it proves, with `M = 0x1.8p52` and `MB` its
  bits:
  1. *`L2D` split*: for every int `hi`, `(bitsToDouble(sext(hi) + MB) - M) * 0x1p32` is exactly
     the double of `hi * 2^32` (equal to its conversions toward both infinities, which differ
     on every inexact value); for every unsigned int `lo`, `bitsToDouble(lo | bits(0x1p52)) -
     0x1p52` is exactly the double of `lo`; and `(v >> 32) * 2^32 + (v & 0xFFFFFFFF) = v` over 66
     bits for every long. So `d = dh + dl` is one rounding of `v`, `(double) v`. Refuted: `MB + 1`
     and the low word's magic `+ 1` (the whole form against `(double) v`), and a shift of 31.
  2. *`L2D` bounded magic*: `bitsToDouble(v + MB) - M` is `(double) v` for every `|v| <= 2^51`,
     and is not at `2^51 + 1` or `-(2^51 + 1)`. The window is closed: 2.3's guard `|v| < 2^51` is
     one narrower than it needs to be at either end, which costs nothing but a decline at the
     two ends.
  3. *`L2F` round-to-odd*: `(float) v` is the rounding of `m = |v|` read unsigned, negated where
     `v < 0`, for every long, and is not with `m = v ^ s`, the `- s` dropped; below `2^52` the
     form (`d0 = bitsToDouble(m | bits(0x1p52)) - 0x1p52`, then `D2F`) is the rounding of `m`;
     on each binade `[2^j, 2^(j+1))`, `j` from 52 to 63, the form (`t = (m >>> 12) | ((m & 0xFFF) != 0 ? 1 : 0)`, `d = (bitsToDouble(t |
     bits(0x1p52)) - 0x1p52) * 0x1p12`, then `D2F`) is the rounding of `m`, and so is the rounding
     of `t << 12` to 24 bits, the theorem's instance at `k = j - 35`. Every 64-bit `m` from `2^52`
     up is covered, a superset of the magnitudes, which reach `2^63`. Refuted: no sticky bit, two
     roundings to nearest (to a double, then to a float), a shift of 11 (t reaches `2^52` on the
     top binade) and the small arm up to `2^53`. The bound is tight: rounding to odd at 26 bits
     then to nearest at 24 is one rounding on `[2^63, 2^64)`, at 25 bits it is not. A shift of
     13 still holds (k = 16 at `2^52`), the theorem's slack, kept as an `unsat` check.
- **A one-off check not committed**: the whole split form against `(double) v`, stated per
  binade of `v` (63 positive, 63 negative), was `unsat` on all 126 under both solvers, about two
  minutes each, which is past the linters' budget; it confirms the composition the file states by
  IEEE addition's definition. The whole `L2F` form per binade of signed `v` was likewise `unsat`.
- **Open points for the lead**:
  1. The constants (`M`, `0x1p52`, `0x1p32`, the shift 12, the small arm's 52 bits, the window
     `2^51`) are the plan's, held in `VarkaProofFiles`; when `VarkaConversionLowering` lands, the
     generator reads them from it, as the other proof files read theirs.
  2. 2.3 writes `sticky(m & 0xFFF)` without its lowering; the proof states it as `(m & 0xFFF) !=
     0 ? 1 : 0`, a compare and a blend. A lowering by another route (`((m & 0xFFF) + 0xFFF) >>>
     12`, say) must be shown equal to it, or the proof restated over it.
  3. 2.3's scale is proven as a blend of `d0 * 0x1p12` and `d0`, and the sign as `fp.neg` of the
     float (its sign bit flipped); a lowering that multiplies by a blended scale of `1.0` or
     `0x1p12`, or flips the bit by an `XOR` of the float's bits, is the same value but is not the
     proven form.
  4. The split's final addition is stated by IEEE semantics, not checked whole, within the
     linters' budget (the one-off above checked it).
  5. The form whose arms are the `wideConversionForm` option, the reduced-width twins (3.2) and
     the named failing case (3.4) are the rest of 343b1.


### 343a5 as built (6 November 2026, branch `varka-343a5-fuzz`)

Cut from #1303's final tree and rebuilt on 343a4 (#1385), whose files it leaves alone:

- **The fuzz grammar.** `VarkaIrGrammar.drawFloatingCastShape` draws one to three roots of one
  kernel over an int, a bigint, a float and a double column and both literal tables: a leaf, a
  `ConvertLane` into a floating lane from an integral lane or the other floating one, a wrapping
  `+`, `-` or `*`, a pick, and a choice over a comparison at the same lane or at another, joined
  by a `CrossLane` (343a3b's crossing, which #1303 predates). Its seed, `FLOATING_CAST_FUZZ_SEED`,
  is its own, so no committed corpus moves. `VarkaIrFuzzSuite` runs it against `mixedValue` at
  the drawn options, half the draws held at emit level 3, where a bigint conversion is admitted;
  a plan that refuses the draw (a bigint conversion below level 3) is counted and skipped, and the
  test fails if half the draws are skipped. A reach test asserts all six conversions, the four
  drives, a 32-bit root of a 64-bit kernel and a crossing between and within the widths.
  `ConvertLane` and `CrossLane` stay out of the older corpora, which the reach test lists.
- **The sweeps** of 3.1 that belong to the to-floating half: every `INT` to `FLOAT` and `DOUBLE`,
  every `FLOAT` pattern to `DOUBLE` and to `BOOLEAN` (the condition `NOT (f = 0.0F)` as its one or
  zero), in `VarkaEmitterFloatingCastSuite`, each row held to Java's cast bit for bit. Every
  256th chunk of the 2^32 runs on every build; the whole, under `-Dvarka.sweep=true`, is a `class`
  row of `sql/varka/ci/sweeps.tsv` for the `varka-sweeps` job. *Not timed:* only the slice ran
  here. The `TINYINT`, `SMALLINT` and `BOOLEAN` sweeps stay the coverage rows' fixtures (343a),
  the floating-to-integral and `time_from_seconds` sweeps are 343b2 and 343b3, and the two AVX2
  arms 343b1.
- **The owed false-AND forms.** `CAST(y1 AS DOUBLE)` and `CAST(s1 AS DOUBLE)` fuse now. The plan
  refused every null test over a narrow column under a double kernel, because only the long
  drive marked such a column as read for its word. The word is a bit per row at every lane and
  the double emission already loads a narrow column as the long one does, so
  `VarkaKernelPlan.narrowColumnsOnly` admits `IS NOT NULL` of a narrow column, bare or widened to
  the int lane, and nothing else narrow. `CAST(y IS NOT NULL AS DOUBLE)` fuses with them. Both
  came off `VarkaAnsiForm.DECLINED`. `VarkaReference.flatten` stood a widening in as a long
  column whatever its target; it keeps the target's lane, so a `CondValue` at the int lane over
  such a test rebuilds.
- **Tests.** `VarkaFloatingLaneSuite` plants an AVX-512 host without DQ and one with it, and
  checks the bigint cast declines at the level the first resolves to, fuses at the second's
  level 3, declines at the unknown level, and that `CAST(l AS DOUBLE) + 1.5D` compiles at level 3;
  and that a smallint's null test fuses as `DOUBLE` and `FLOAT`. 343a4 already holds the
  differential harness's level-3 cells, the conformance rows and the emitter suite's refusals.

### 343b1 as built (8 November 2026, branch `varka-343b1-forms`)

`BIGINT` to `DOUBLE` and `FLOAT` fuse at every named emit level: one `convertShape` at level 3,
and below it the AVX2 forms of 2.3 in `VarkaConversionLowering`, chosen by
`convertsFallBack()`. The reduced-width twins (3.2) are not built; the SMT file proves the
forms over every long, which the twins would only sample at half width.

- **The level.** `conversionRefusal` declines a long source at the unknown level alone
  (`VarkaEmitOptions.levelUnknown()`), by 2.3's own rule: the forms are read only where
  `convertsFallBack()` holds, which is false there, and `convertShape` at that level would be the
  Java loop on an AVX2 host. So the defaults still decline, no coverage row can hold the casts
  (the coverage suite compiles at the defaults), and an aarch64 session, which resolves to the
  unknown level, declines them although 2.3's table says `convertShape` there.
- **`L2D`.** The split as 2.3 states it, and under `wideConversionForm=MAGIC` the bounded magic
  behind a `VALUE_RANGE` refusal of the `ConvertLane` itself over `[-2^51, 2^51]`, the proof's
  closed window rather than 2.3's open one; the guard compares the long child
  (`VarkaVectorWalk.guardLane`). No IR node is added: the compiler reads no option.
- **`L2F` without a mask.** At two 64-bit lanes C2 on x86 lowers no mask, so the form settles the
  groundwork's open points 2 and 3 by arithmetic: the sticky bit is `((m & 0xFFF) + 0xFFF) >>> 12`,
  the arm a blend by `sel = -(((m >>> 52) + Long.MAX_VALUE) >>> 63)` in logic, the scale a magic
  number of `2^64` whose ulp is `2^12`, and the sign `v`'s sign bit set in that magic number.
  `round_to_odd.smt2` is restated over these operations, read from the class's constants (open
  point 1): 50 checks, 7.8 s under Z3 and 6.8 s under cvc5 on the laptop. The new ones: the
  sticky bit is Theorem 3's rounding to odd, the sign bit is set exactly where `v < 0`, each arm's
  signed magic gives the negated double for every `t` from 1, and `D2F` commutes with negation.
- **The option.** `wideConversionForm` (`SPLIT`, `MAGIC`) reads only a `ConvertLane` and only
  below level 3, so a `Choice` now carries a `Subject` as a `Flag` does, `useAVX=2`, and the matrix
  runs `useAVX=2,wideConversionForm=MAGIC` (`ConvertLane` joins `COMPILED_NODE_KINDS`).
- **Tests.** `VarkaEmitterWideConversionSuite` holds both forms and both arms at three widths, the
  lengths and null patterns to Java's casts at the cells (the arms' boundary at `2^52`, the float
  ties `2^52 + 2^28` and `2^62 + 2^38` and one sticky bit above them, the double ties past
  `2^53`, `2^53 + 2^29 + 1`, the extremes, every negation) and over a seeded draw of 512 values a
  binade of both signs with their float and double ties, the magic's decline past `2^51` and not
  under a null, and 3.4's case: the split-and-add form gives 4275361280 for 4275361147 and misrounds
  between 16% and 17% of a seeded draw of `[2^31, 2^32)`, the kernel 4275361024.
  `VarkaDifferentialHarnessSuite` runs the level-3 cells at level 2 in both arms against Spark.
  The budget weighs a long conversion by its AVX2 form, 10 lane calls for the split and 24 for
  `L2F` (the magic is 5).
- **C2 at `-XX:UseAVX=2`** on the laptop (`dev/varka_emit.sh --asm`, `JDK_JAVA_OPTIONS`): both
  forms are 256-bit `ymm` code with no call but the traps and no `vcvtqq2pd`; the split is
  `vpand`, `vpor`, `vsubpd`, the arithmetic shift as `vpsrlq`, `vpxor` and `vpsubq` over a
  sign-mask load, `vpaddq`, `vsubpd`, `vmulpd`, `vaddpd`; `L2F` ends in `vcvtpd2ps ymm, xmm`.
  The tool's own timing, not a benchmark, read 322 ns a round of 1024 rows for the split, 218 for
  the magic and 542 for `L2F`: the split is past prediction 4.4's 1.3 times there, which the
  measurement on the EPYC runners (step 8) decides.
