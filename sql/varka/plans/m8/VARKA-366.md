# VARKA-366: The exact decimal-to-floating arm

## 0. What exists now (admission check)

From master at `6e207c226fb` (30 October 2026; paths as `VARKA-350.md` 0 gives them), the JDK 25
sources in `/home/max/proj/openjdk-build/jdk25`, and the census files. The row's text is the
milestone plan's 2.2 (owner's decision A4), its section 9 entry on round-to-odd, and wave F3 ("366
under 350's three double-rule casts and 364's two-pass arm"). The numbers marked **[A]** are from
`results/VARKA-350-admission.txt`, shared with `VARKA-350.md`; nothing else was run.

**Spark's behaviour per (from, to, mode), within precision 18.** This row computes one value for
two pairs and for three more by composition:

| from | to | LEGACY | ANSI | TRY |
|---|---|---|---|---|
| `DECIMAL(p, s)` | `DOUBLE` | `BigDecimal.doubleValue` of `u * 10^-s` | the same | the same |
| `DECIMAL(p, s)` | `FLOAT` | `BigDecimal.floatValue`, rounded once | the same | the same |
| `DECIMAL(17 or 18, s)` | `INT`, `SMALLINT`, `TINYINT` | not this row (exact) | the double rule over this row's `DOUBLE` value (`VARKA-350.md` 2.3) | the same, null where ANSI raises |

1. **The two casts never fail.** `Decimal.toDouble` and `toFloat` are `toBigDecimal.doubleValue`
   and `floatValue` (`sql/api/.../types/Decimal.scala:245-247`), generated as `$c.toDouble()` and
   `$c.toFloat()` in every mode (`expressions/Cast.scala:2656-2657`, `:2693-2694`); the
   interpreted path goes through `DecimalIsFractional.toDouble` (`Decimal.scala:742`), the same
   call. No error class applies; TRY is LEGACY's answer. The backing does not matter: a
   long-backed value's `toBigDecimal` is `BigDecimal(longVal, scale)` (`Decimal.scala:193-199`)
   and a `BigDecimal`-backed one's is its own, the same exact value either way.
2. **JDK 25 rounds both correctly from the exact value.** `BigDecimal.doubleValue`
   (`src/java.base/share/classes/java/math/BigDecimal.java:3825-3862`) returns `(double)
   intCompact` at scale 0, `v / DOUBLE_10_POW[scale]` when `(long) v == intCompact` and `0 <
   scale <= 22` (one rounding of exact operands; its comment argues `Long.MAX_VALUE` comes out
   right too), and otherwise `fullDoubleValue`, an exact quotient with a round and a sticky bit
   (`:3864` on). `floatValue` (`:3742-3764`) is the same with `FLOAT_10_POW` up to `1e10`
   (`:4093-4096`) and `fullFloatValue` (`:3766-3805`), which rounds once from the exact value, not
   through a double. So the target is `RN64(u * 10^-s)` and `RN32(u * 10^-s)`.
3. **The domain below precision 19.** `abs(u) <= 10^18 - 1 < 2^60` by 347's load check, `0 <= s <=
   18`, so a nonzero value lies in `[10^-18, 10^18)`: no subnormal and no overflow in either
   format, no `Long.MAX_VALUE`, and `u = 0` gives `+0.0` (a `BigDecimal` zero has signum 0). An
   exact midpoint between two neighbours of the target exists only at scales 0 to 2 for binary64
   and 0 to 15 for binary32 **[A]** - an odd 54-bit (25-bit) significand times `5^s` must stay
   below `10^18` - so those are the tie windows.
4. **Where row 350 needs this row [A].** By `VARKA-350.md` 2.4, the quotient is correctly rounded on
   every lane for `DOUBLE` at `p <= 15` and the float quotient for `FLOAT` at `p <= 7`, and `FLOAT`
   between 8 and 15 is round-to-odd over the double quotient, so this arm is needed only at `p` 16,
   17 and 18 for both targets, and for the three narrow casts' double rule at 17 and 18. The guard
   `abs(u) <= 2^53` passes on 0.0090 of a uniform `DECIMAL(18, s)` lane, 0.0901 at 17 and 0.9007
   at 16.
5. **What the algorithm needs from the JDK.** A per-lane leading-zero count:
   `VectorOperators.LEADING_ZEROS_COUNT` on longs is matched with `UseAVX >= 2`
   (`src/hotspot/cpu/x86/x86.ad:1905-1909`), as `evplzcntq` with AVX512CD (`:2077-2080`) and as
   a macro sequence on AVX2 (`cpu/x86/c2_MacroAssembler_x86.cpp:6284`); the census has no cell for
   it. A 64 by 64 to 128-bit product: no lane multiply-high exists, so it is built from 32-bit
   limbs, one `vpmuludq` each when both inputs are visibly 32 bits (the milestone plan's 2.8,
   HotSpot's `vectornode.cpp` 2189 to 2237); a constant multiplier's limbs are broadcasts written
   `broadcast(c).and(0xFFFFFFFF)`. Unsigned compares for the carries are `ULT` and its kin,
   intrinsic at 512 and 256 bits and the fallback at two long lanes on x86 (`VARKA-359.md` 0 items
   9 and 10). Variable shifts are `BitOp(SHL | USHR)` with a vector count (`varka/VarkaVectorIR.
   java:401`, `:953`), for which the census has no cell (`VARKA-343.md` 0 item 5).
6. **No leading-zero or reinterpretation node exists**, and no unsigned compare (359 adds them) or
   128-bit product (360b's `WideMul` for a constant multiplier, 361's for a variable one).
7. **The sources, transcribed** (merged 30 October 2026 with the owner's approval): Lemire 2021,
   "Number Parsing at a Gigabyte per Second"
   (`sql/varka/papers/lemire-2021-number-parsing-gigabyte-per-second.md`, arXiv 2101.11408, CC BY
   4.0), and Mushtak and Lemire 2023, "Fast Number Parsing Without Fallback"
   (`sql/varka/papers/mushtak-lemire-2023-fast-number-parsing-without-fallback.md`, arXiv
   2212.06644, CC BY 4.0). The row's "Theorem 1, Corollary 2 and Lemma 3" are Lemire's section 9
   (Theorem 1 and Corollary 2 on p. 16, Lemma 3 on p. 17), and "no fallback while `s <= 27`" is
   section 9.1 (p. 17) with Algorithm 1 line 6's no-abort window `q` in `[-27, 55]` (p. 8).
   Algorithm 1 is "just one algorithm for both the 32-bit and 64-bit cases" (p. 8). Mushtak and
   Lemire prove the abort never fires at any exponent (section 4, pp. 3-5, Proposition 4, p. 4).
8. **The working-precision round-to-odd** of the milestone plan's section 9 is "a sketch, sampled
   here at 200,000 values with no miss, unproven where `r + ul` must be exact and uncosted".
9. **The baseline** has no decimal-to-floating section (`VARKA-350.md` 0 item 17).

### What differs from the row text and the milestone plan

*Reworked 30 October 2026 with the lead's settlements of this plan's four open points.*

- **The arm runs at precision 16 to 18 only, on every lane** (item 4): below 16 row 350's quotients
  are exact on every lane, and at 16 to 18 the kernel takes this arm with no guard and no runtime
  switch, one body. The row's "taken per batch when the guard mask of 350 is not all true" is not
  built: a batch takes a guarded quotient only if every lane passes, which at `p = 16` and 0.9007 a
  lane almost never holds over a batch of thousands of rows, so a per-batch form would pay both
  arms on nearly every uniform batch. It stays a possible measured option arm (section 7), built
  only if this arm's measured cost shows it matters.
- **The arm is composed from long-lane nodes** (2.3), following 359's precedent, its two 128-bit
  products row 360b's `WideMul`; no node of its own.
- **`FLOAT` rests on the binary64 arm** (2.2): round-to-odd at 53 bits, then one narrowing, the
  argument 350 already relies on from precision 8 to 15. The published algorithm covers binary32
  directly (Lemire, Algorithm 1, p. 8), so the direct binary32 arm is an option, not the design.
- **`s >= 28` declines by Varka's choice, not the paper's** (2.5): Mushtak and Lemire show the
  abort never fires at any exponent. The milestone plan's wording is flagged in section 8.
- **The two-pass form above 18 is 364's composition** over this row's sequence, which exposes the
  entry 364 needs (2.5).

## 1. Goal and non-goal

`CAST(d AS DOUBLE)` and `CAST(d AS FLOAT)` over a `DECIMAL(p, s)` with `16 <= p <= 18` give
`BigDecimal.doubleValue` and `floatValue` bit for bit on every lane, with no fallback and no
decline, by Eisel and Lemire's algorithm with the scale a per-kernel constant; the same `DOUBLE`
value feeds 350's double rule for the three narrow casts at precision 17 and 18. Not this row: the
quotients below 16 and the round-to-odd below 16 (350), the casts' composition and their tests in
the three modes (350), precision above 18 and the two-pass form (364), `s >= 28` (declined by
Varka's choice in 364, 2.5),
floating to decimal (346), a general FMA node (350's open point 8).

## 2. Design

### 2.1 The algorithm at a constant scale, binary64

For `x = u * 10^q` with `q = -s` a compile-time constant, `s >= 1` (scale 0 is 350's conversion
alone, `VARKA-350.md` 2.4), per lane, over `w = abs(u)`:

1. `w = 0` gives `+0.0` (a blend at the end).
2. `l = LEADING_ZEROS_COUNT(w)`; `w' = w << l`, bit 63 set.
3. `T = (T_hi, T_lo)`, two longs in the kernel's long table: for `s = 1..27` the 128-bit
   reciprocal **rounded up**, `ceiling(2^b / 5^s)` with `b = 127 + ceiling(log2(5^s))`, which
   Corollary 2 needs (Lemire section 9.1, p. 17; Table 4, p. 18); a truncated table is wrong on a
   large share of inputs (the lead's simulation of 30 October 2026). Scale 0 would be Table 3's `q =
   0` row (p. 13), `8000000000000000` with a second word of zero, though 350 serves scale 0 by the
   conversion. The table is computed at compile time from `BigInteger` and a unit test compares it
   with Table 4's 27 rows.
4. `z = w' * T_hi` as 128 bits: row 360b's `WideMul` by the constant `T_hi`; this row builds no
   product of its own.
5. The second product `w' * T_lo`'s high word, a second `WideMul`, added into `z.lo` with its carry
   into `z.hi` (an unsigned compare); the algorithm takes it only when the low nine bits of `z.hi`
   are all ones, the stopping rule of section 9.1 (p. 17: the top 55 bits must be exact, and `64 -
   55` is 9; 38 for binary32), and the lanes compute it always and blend it in under that mask.
6. `b = z.hi >>> 63`; `m = z.hi >>> (b + 9)`, a variable shift, the top 54 bits (Algorithm 1 lines
   7 and 8, p. 8); the binary exponent `p = ((217706 * q) >> 16) + 63 - l + b` (Algorithm 1 line 9,
   p. 8; section 10, p. 22), its first term a compile-time constant at a fixed scale.
7. **The tie, by the paper's rule** (Algorithm 1 lines 16 and 17, p. 8): where `z.lo <= 1`, `m` is
   odd, `m / 2` is even and `q` lies in `[-4, 23]` (a compile-time test, so the step is emitted at
   scales 1 to 4 only), and where also `z.hi / m` is a power of two - that is, `z.hi`'s low `b + 9`
   bits, those step 6 dropped, are all zero - `m <- m - 1`, so step 8 does not round up. `z.lo <= 1`
   rather than `== 0` is the paper's (line 16; Technical Remark 2, p. 15; section 9.1, p. 17, where
   the product's lowest bit is ignored). **Both tests are needed**: with the power-of-two test
   dropped, `CAST(CAST(0.01 AS DECIMAL(16, 2)) AS DOUBLE)` would answer one ulp below `0.01`'s
   correctly rounded double, and the lead's simulation with exact rational rounding found many such
   answers inside the tie windows for a rule without it and none for the paper's (sampled, not
   proven).
8. `m += m & 1; m >>>= 1`; where `m == 2^53`, `m = 2^52` and `p` gains one (Algorithm 1 lines 19 and
   20).
9. The value `m * 2^(p - 52)` (Algorithm 1, p. 8): the bits `(m & (2^52 - 1)) | (p + 1023) << 52`,
   the sign of `u` ORed in, reinterpreted as a double; no subnormal or infinity branch, by item
   0.3.

**The citations.** Every constant above is the paper's
(`sql/varka/papers/lemire-2021-number-parsing-gigabyte-per-second.md`, cited by section and page).
That is eight limb multiplies (inside the two `WideMul`s) and about forty other lane operations, the
count the milestone plan gives, to be registered by `dev/varka_emit.sh` when built. The exactness of
the two-product truncated result rests on Theorem 1 and Corollary 2 (section 9, p. 16), which make
the rounded-up reciprocal multiply exact; Lemma 3 (p. 17), which makes the zero-remainder (tie) test
exact; the nine-bit stopping rule (section 9.1, p. 17); Technical Remark 1 (p. 14), which shows the
low word's all-ones test enough after the second product; and Algorithm 1 line 6's no-abort window,
`q` in `[-27, 55]` (p. 8). Within precision 18 the scale is at most 18, inside that window.

### 2.2 `FLOAT`: the binary64 arm, round-to-odd, one narrowing

`FLOAT` at 16 to 18 runs 2.1's steps 1 to 6 and replaces steps 7 and 8 by round-to-odd at 53 bits:
`m`'s last bit ORed with the sticky bit - `z.lo > 1` or a bit set among those step 6 dropped, the
form that follows from the paper's zero-remainder test (Lemma 3, p. 17; section 9.1, p. 17, which
ignores the product's lowest bit) - then the double assembled as in step 9 and one
`ConvertLane(FLOAT)`. Round-to-odd at 53 bits followed by one rounding to 24 is one correct rounding
(Boldo and Melquiond's Theorem 3, 53 against 24 + 2), the argument 350 already relies on from
precision 8 to 15 (`VARKA-350.md` 2.4), so `FLOAT` needs no second set of constants and no tie
step. The published algorithm covers binary32 directly ("We give just one algorithm for both the
32-bit and 64-bit cases", Algorithm 1, p. 8: a 38-bit mask, a shift of `b + 38`, the tie window `q`
in `[-17, 10]`), so the direct binary32 arm's condition is met; it stays an option arm, measured
against this design, which shares 2.1's proof and constants.

### 2.3 Composed from long-lane nodes

The arm is a tree the compiler builds in `VarkaDecimalCastCompiler`, as 359 composes a pair from
words: `BitOp` (`AND`, `OR`, `SHL`, `USHR`, the last two with a vector count for steps 2 and 6),
`IntArith` in `WRAP`, `IfElse` blends, 359's unsigned `Compare`s for the carry, 360b's `WideMul` for
the two products, the long table's slots for `T` and the exponent constant, and two nodes this row
adds:

- **`LeadingZeros(child)`** at the long lane, `lanewise(LEADING_ZEROS_COUNT)`, evaluated by the
  reference with `Long.numberOfLeadingZeros`;
- **`ReinterpretLane(child, target)`** between the long and the double lane, the bits unchanged,
  evaluated with `Double.longBitsToDouble`; 343's AVX2 forms (`VARKA-343.md` 2.3) reinterpret both
  ways inside their lowering, and if 343 lands first with such a node this row uses it instead.

No C2 lowering constraint was found that forces one node: the only one of 361's kind, a constant
multiplier's limbs written `broadcast(c).and(0xFFFFFFFF)` so that C2 selects `vpmuludq`, lives
inside 360b's `WideMul`, not in this tree. The cost of composing is size - about fifty nodes for one
cast, each in the budget, the cost model and the fuzzer's grammar - and the budget's group split may
cut the tree; `VarkaEmitBudget` weighs it as it weighs any tree.

**The oracle is not the algorithm.** The reference evaluator evaluates the nodes; the coverage
oracle and the sampled suite compare with `BigDecimal.valueOf(u, scale).doubleValue()` (or
`floatValue()`), Spark's own call. A scalar Java twin of the sequence, `ExactToFloatingScalar` in
the test tree, on `Math.unsignedMultiplyHigh` and `Long.numberOfLeadingZeros`, runs the same steps
for the exhaustion of 3.2 and is the scalar reference arm.

### 2.4 Where it runs

One body. At `p` 16 to 18 and `s >= 1`, 350's `toDouble` is this tree for `DOUBLE` and 2.2's tree
for `FLOAT`, on every lane; below 16 it is 350's quotient or round-to-odd. The double rule of 350's
three narrow casts at 17 and 18 takes its `DOUBLE` value from `toDouble` unchanged.

### 2.5 What 364 takes from it

364 runs the sequence twice on the top 64 bits of a 128-bit magnitude, `w'` and `w' + 1`, with the
shift that took them as an exponent offset, and accepts a lane where both give one value. So the
compiler's builder takes an already normalised `w'` (bit 63 set, unsigned) and a per-lane exponent
offset, which this row calls with `l` from step 2 and an offset of zero; `w' + 1` overflowing 64
bits is 364's case to handle. **`s >= 28` declines by Varka's choice, not the paper's**: Lemire's
algorithm aborts outside `[-27, 55]` (Algorithm 1 line 6, p. 8), but Mushtak and Lemire show the
abort never fires at any exponent in `(-342, 308)` for a 64-bit significand (section 4, pp. 3-5;
Proposition 4, p. 4), so a scale of 28 or more could be served by the same sequence and a table
entry more. Declining it keeps 364's exactness argument on Lemire's window alone; section 8 flags
the milestone plan's wording.

### 2.6 The working-precision round-to-odd, an option arm for `DOUBLE`

The row asks for section 9's form - `u = uh + ul`, `q = RN(uh / 10^s)`, `r = fma(-q, 10^s, uh)`, the
answer `RN(q + odd((r + ul) / 10^s))` - as an option arm, a `Flag` `decimalToDoubleWorkingOdd`
(`Reason.MACHINE`, off). It is built only after the claim it lacks, that `r + ul` is exact, is
shown: first by a reduced-width twin exhausted in scalar Java (the same construction from binary32
to binary16 over every 32-bit `u`, as 343's twins are), then stated with the argument; and only once
a row has built the FMA node it needs (350's open point 8, row 342 its natural owner); this row
builds no general FMA node. If the twin finds a miss, the arm is not built and section 10 records
the witness.

### 2.7 At 128 bits, cost, warm-up

At two long lanes on x86 the unsigned compares and blends are the fallback; the rule is 347's
answered question 4, as `VARKA-350.md` 2.6 applies it, and the measurement reports it. The cost
model gains features for `LeadingZeros` and `ReinterpretLane`, on row 352's awaiting-refit list. The
warm-up's long block gains `LEADING_ZEROS_COUNT`, the variable shifts and the reinterpretation, held
by `VarkaWarmupReachSuite`.

## 3. Tests and proofs

1. **The paper's results, cited.** The exactness claim of 2.1 cites Theorem 1, Corollary 2 and
   Lemma 3 (Lemire section 9, pp. 16-17), the nine-bit stopping rule (section 9.1, p. 17), Technical
   Remark 1 (p. 14) and Algorithm 1 line 6's no-abort window (p. 8), each beside the code that
   relies on it with its precondition (the scale window, `w < 2^64`, the table rounded up).
2. **Exhaustion against the proven quotient.** For every `u` with `abs(u) < 2^32` and every scale
   1 to 18, the scalar twin and the emitted kernel give the bits of `(double) u / 10^s` - correctly
   rounded there by the JDK's own argument (item 0.2: exact operands, one rounding) - and for
   `FLOAT` the bits of 350's round-to-odd, correct by Boldo and Melquiond's theorem: 2^33 values per
   scale and target, opt-in (`-Dvarka.sweep=true`) in the gate's sweep step with a 2^24 slice in PR
   CI, the first sweep timed before the rest (milestone plan 10.3 item 5). The full sweeps run by
   hand in that step: no CI workflow sets `varka.sweep` today, so no nightly run is claimed.
3. **Sampled regions against `BigDecimal`**, for `p` 16 to 18 at every scale: a seeded draw per
   binade of `[2^53, 10^18)`; `+-2^53` and its neighbours; every exact midpoint of the tie windows
   (an odd 54-bit or 25-bit significand times `5^s` times a power of two) and the values one unit
   either side; Trino's vectors (`READING_TRINO_DECIMAL.md` 10 item 5: `9007199791611905`,
   `960000084148223999` at scale 2, `912057769880088000` at scale 3, `576460752303480000` at scale
   2, `83886085` and `83886095` at scale 1 to `FLOAT`, `300315360073` at scale 14), each at every
   scale 0 to 18; the second-product cases - values where the low nine bits of `z.hi` are all ones -
   found by a search in the scalar twin and tested by name; and `0.01` at `DECIMAL(16, 2)` to
   `DOUBLE`, the witness that the tie rule's power-of-two test is needed (2.1 step 7), with the
   `z.lo` values 0 and 1 both reached among the tie cases.
4. **No vacuous pass, for row 504.** The sampled suite counts the lanes that took the second
   product, the tie step and a set sticky bit, and fails if any count is zero; under one test flag
   the tree drops the second product, under another the tie rule's power-of-two test, and under a
   third the table is truncated rather than rounded up; the suite must fail under each.
5. **The table.** A unit test recomputes `T` for `s = 1..27` from `BigInteger`, rounded up as
   section 9.1 specifies, and compares it with Table 4 (p. 18), and `s = 0` with Table 3's `q = 0`
   row (p. 13).
6. **Coverage rows** in 350's "DECIMAL casts" family: `CAST(d16s9 AS DOUBLE)`, `CAST(d18 AS
   DOUBLE)`, `CAST(d18 AS FLOAT)`, `CAST(d18s14 AS DOUBLE)` and `d18 < x` over a double column
   (coercion's cast), and, after 350c, `CAST(d18s14 AS INT)` under ANSI and TRY; each names
   `differential` or the sampled suite for row 490's check.
7. **The fuzzers**: the IR grammar draws `LeadingZeros` and `ReinterpretLane` in their own sequence
   and the composed tree at scales 1 to 18 and both targets in the decimal sequence; the Spark-level
   fuzzer's decimal columns at precision 16 to 18 cast to both types.
8. **The bytes oracle**: `emitted_bytes.json` adds rows and moves no existing shape.

## 4. The baseline, and predictions

**The baseline is 350's addition** (`VARKA-350.md` 4), one pull request on the unchanged code:
`CAST(x AS DOUBLE)` and `CAST(x AS FLOAT)` at `(9, 2)`, `(18, 4)` and `(18, 14)`. `benchmark.yml` on
the EPYC 7763 at 256 and 128 bits.

Predictions, registered before the build:

1. **The arm against the row path at 256 bits on the EPYC 7763**, at `(18, 4)` and `(18, 14)`: 4 to
   10 times faster; the row path builds a `BigDecimal` per value and divides in `fullDoubleValue`
   wherever the fast path fails.
2. **The arm against 350's quotient at `(9, 2)`**, per row: 1.5 to 3 times slower at 256 bits on
   both hosts; this is the done-when's "cost measured against the guarded division", and it decides
   whether the per-batch option of section 7 is worth building.
3. **`FLOAT` costs the same as `DOUBLE`** within a tenth: one narrowing in place of the tie step.
4. **At 128 bits on x86** the arm is the fallback (two long lanes); it still beats the row path by
   2 to 4 times, an assumption with no measurement behind it, to be read beside 359's spike.
5. **No existing shape's bytes move.**

## 5. Order, files and size

1. **The papers are in** (step done, 30 October 2026, the owner's approval): both transcriptions
   under `sql/varka/papers/`, cited by section and page above.
2. **The census cells**: `long.LEADING_ZEROS_COUNT`, `long.LSHL` and `long.LSHR` by a vector count,
   the long-to-double reinterpretation, on the laptop and through `varka-width-audit.yml`.
3. **The scalar twin and its harness** (3.2, 3.3, 3.5), test side only, before any emitter code: it
   validates the constants, finds the second-product cases and times the exhaustion.
4. **366a, the arm**, after 360b's `WideMul` and 359's unsigned compares (or adding them if 359 is
   later): `LeadingZeros`, `ReinterpretLane`, the composed tree for both targets wired into 350's
   `toDouble` and `toFloat` at `p >= 16`, tests 3.2 to 3.8, the measurement against the baseline at
   both widths and 512 bits on the laptop, scored.
5. **366b, if built**: `decimalToDoubleWorkingOdd` after its twin and an FMA node (2.6); the direct
   binary32 option arm, measured against 2.2's design.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `LeadingZeros`, `ReinterpretLane`, canonical forms |
| `varka/VarkaVectorWalk.java` | their lowerings |
| `VarkaDecimalCastCompiler.java` | the composed tree, 350's `toDouble` and `toFloat` at `p >= 16` |
| `VarkaReference`, `VarkaCanonicalIr` | the two nodes' arms |
| `varka/VarkaEmitBudget.java`, the cost register, the warm-up | weights, features, reach |
| tests | the scalar twin, the exhaustion, the sampled suite, the table test, coverage rows |
| benchmark | 350's baseline sections, results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |

**Size: medium, in one pull request after the baseline** (366b only if its conditions are met). The
cells half a day; the twin and its harness a day and a half; 366a three days.
Test time: the exhaustion is 2^33 values per scale and target, of the order of a minute per scale in
the kernel and opt-in; the sampled suite tens of thousands of `BigDecimal` calls a scale, seconds.
It rests on 347 (the load), 360b's `WideMul` (both products), 350b (`toDouble`, `toFloat` and their
below-16 forms), 343 (`ConvertLane(FLOAT)`), 359's unsigned compares if 359 lands first, and 157 for
the outputs.

## 6. What it unlocks in the census

Nothing on its own: `cast` and `try_cast` over a decimal count with 364 at every precision
(`VARKA-350.md` 6). It closes the last decline of 350's decimal-to-floating pairs at precision 16 to
18 and of the three narrow ANSI and TRY casts at 17 and 18, so `casts_fused.tsv`'s `DECIMAL(10, 2)`
forms need nothing more from it; the `fused_forms` column's precision-16-to-18 claim rests on this
row.

## 7. Out of this row, and declines that stay

Out: the quotients and round-to-odd below precision 16 and the casts' composition (350); the
two-pass form above 18 and its `s >= 28` decline, Varka's choice (364, 2.5); a general FMA node
(350's open point 8); Trino's `FLOAT` midpoint-margin test, which would keep more lanes on the
quotient but needs its own bound proven (`READING_TRINO_DECIMAL.md` 7); Schubfach and the
shortest-digits path, which are 346's direction (`m9/SCOPE.md` item 97).

**The per-batch guarded form, a possible measured option arm.** The row's text has the arm "taken
per batch when the guard mask of 350 is not all true": a guard `abs(u) <= 2^53` collected over the
batch, the quotient where it holds on every valid lane and this arm where it does not. It needs a
kernel of two bodies or a runner rerun - neither exists; Varka's bodies are straight-line and
`anyTrue` appears only in the status fold (`varka/VarkaBodyEmitter.java:645-661`) - and it helps
only a column whose values all lie below `2^53` (item 0.4). It is built as an option arm only if
prediction 2's measured cost of the arm against the quotient shows it matters, and then its form is
decided with that number.

Declines that stay: none at precision 16 to 18; a decimal in an int-driven kernel (347's leaf rule).

## 8. Questions for the owner

The question of fetching the papers is answered (the owner approved it; both are transcribed).

1. **Flagged, the owner's text: the abort below `s = 28`.** The milestone plan's 2.2 says "the abort
   case exists only for `s >= 28`, which must decline" (`PLAN.md` 2.2, the decimal-to-floating
   paragraph). Mushtak and Lemire prove the abort never fires at any exponent (section 4, pp. 3-5,
   Proposition 4), so the abort does not exist at `s >= 28` either; declining there is Varka's
   choice (2.5), not a necessity. The wording needs the owner's rewording, and it bears on row 364,
   whose text and two-pass arm inherit the decline.

### Open points for the lead

Settled by the lead on 30 October 2026, kept as the record: (1) one body, the arm at the precisions
that need it and the quotient below, the guarded form a possible option arm (section 7); (2) a
composition from long-lane nodes with 360b's `WideMul`, no C2 constraint forcing one node being
known (2.3); (3) the papers fetched with the owner's yes and cited by section and page; (4) `FLOAT`
by the binary64 arm, round-to-odd and one narrowing, the direct binary32 arm an option.
None is open.

## 9. Done when

Row 366's text holds, with the corrections above: at precision 16 to 18 the 64-bit form fuses every
value for `DOUBLE` and `FLOAT`, bit-identical to `BigDecimal.doubleValue` and `floatValue` over the
exhaustion of 3.2, the sampled regions and tie windows of 3.3 (the `0.01` witness among them) and
the fuzzer, with Lemire's Theorem 1, Corollary 2 and Lemma 3, the nine-bit stopping rule, Technical
Remark 1 and Algorithm 1 line 6's window cited for the exactness claim; the table rounded up and
checked against Table 4; the vacuity counts and the planted faults of 3.4 hold; 350's three narrow
casts at precision 17 and 18 take their double from it; its cost is measured against 350's quotient
and the row path at both widths; the working-precision option arm is built or its witness recorded
(2.6); no existing shape's bytes move; and the PR build and the option matrix pass.

## 10. Outcome

(Written when the work lands.)
