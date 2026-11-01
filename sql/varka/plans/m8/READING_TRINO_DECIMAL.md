# Reading for milestone 8: what Trino's pure-Java 128-bit decimals offer

Read on 27 October 2026 at the owner's request: Trino does 128-bit decimal arithmetic in plain
Java longs, the same language and the same JIT as Varka's scalar arm "S" (`VARKA-359.md`). Does
its code have anything for rows 350, 351, 359 to 364 and 377 of `m8/PLAN.md` (2.8)? Paths are
relative to `/home/max/proj/trino` at commit `15587ef846a` (16 September 2026). `SPI` is
`core/trino-spi/src/main/java/io/trino/spi/type/`, `MAIN` is
`core/trino-main/src/main/java/io/trino/`, and the tests sit beside them under `src/test`.
Spark's side was read in this fork's sources. **[V]** means the author read the code (or ran the
arithmetic in Python); **[I]** means inferred. Nothing was built or run on a JVM.
`READING_DECIMAL_ENGINES.md`, `READING_ARROW_RS.md`, `READING_RAPIDS.md` and
`READING_M8_PAPERS.md` are not repeated; they are cited where Trino agrees or differs.

## 1. What Trino is

* **Two representations, like Varka's.** A decimal of precision up to 18 is a `long`; above it
  an `Int128` object with two final longs, `high` and `low` (`SPI/Int128.java:23-43`,
  `Decimals.java:46-47`). A block stores the values interleaved in one `long[]`, **high word
  first** (`core/trino-spi/.../block/Int128ArrayBlock.java:123-133`), the opposite order to
  Arrow's low-word-first slot **[V]**.
* **Scalar only.** Trino uses the Vector API for block encodings and Parquet decoding, not for
  decimals: no file under `MAIN/simd` or `MAIN/sql/gen/columnar` names `Int128` or `Decimal`
  **[V]**. Every operator below is one call per row from generated code.
* **Two API styles in `Int128Math`.** Each primitive has an out-parameter form that writes
  `result[offset]` (high) and `result[offset + 1]` (low) and allocates nothing, and an `Int128`
  form that wraps it with a `new long[2]` and a new `Int128` (`Int128Math.java:177-232`,
  `:246-335`) **[V]**. Section 8 says what this means for S.
* **Overflow is an exception.** The primitives throw `ArithmeticException("Overflow")`
  (`Int128Math.java:1373-1376`); the operators catch it and raise `NUMERIC_VALUE_OUT_OF_RANGE`
  (`MAIN/type/DecimalOperators.java:165-167`). There is no null-on-overflow mode at all **[V]**.

## 2. Add and subtract

* **The carry is arithmetic, not a compare.** `unsignedCarry(a, b)` is
  `((a >>> 1) + (b >>> 1) + ((a & b) & 1)) >>> 63` and `unsignedBorrow` is
  `((~a & b) | (~(a ^ b) & (a - b))) >>> 63`, both from Hacker's Delight 2-13
  (`Int128Math.java:234-244`) **[V]**. The borrow bit is exactly `a <u b`. In lanes this is
  seven plain long operations and no mask, against 2.8's `ULT(lo, a.lo)`: one compare (with two
  sign flips on AVX2) and a mask **[I]**. At 512 and 256 bits the compare is intrinsic, so the
  compare should win; the arithmetic form matters only where masks fall back.
* **Signed overflow of the 128-bit sum** is `((r ^ a) & (r ^ b)) < 0` on the high words
  (`Int128Math.java:187`; subtract `:219`) **[V]**: two XORs, an AND and a sign test, all
  lanewise.
* **`addWithOverflow` returns the 129th bit as +1, 0 or -1** (`Int128Math.java:192-210`) **[V]**.
  2.8 already cites it for 360's capped shapes. Trino itself uses it only in `sum` and `avg`,
  which keep a running count of `2^128` wraps beside the sum and check it once at the end
  (`MAIN/operator/aggregation/DecimalSumAggregation.java:60-66`, `:237`, `:248-250`) **[V]**.
  The operators never use it: they throw on the 128-bit overflow instead.
* **The operator: rescale the coarser side up, add, rescale the sum down, check `10^38`.**
  `internalAddLongLongLong` (`DecimalOperators.java:143-168`) multiplies one operand by `10^k`
  (throwing past `2^127`), adds with the throwing `add`, rescales by `resultRescale` with
  `HALF_UP`, and checks the result against `+-(10^38 - 1)` only **[V]**. The 64-bit form is
  `a * aRescale + b * bRescale` with no check at all (`:113-117`), safe because a short result
  type has precision 18 or less **[V]**.
* **The arithmetic checks only `10^38`, never `10^p`** (`DecimalOperators.java:159`, `:254`,
  `:333`, `:358`) **[V]**. The type rule makes `10^p` dead when the result precision is below
  38: the exact sum or product always fits it **[I]**. Spark's rules are the same in this
  respect: `Add` and `Subtract` give `max(p1 - s1, p2 - s2) + max(s1, s2) + 1` and `Multiply`
  gives `p1 + p2 + 1` (fork `arithmetic.scala:443-451`, `:541-549`, `:630-638`), so the exact
  result always fits an uncapped type and only a capped type (precision 38) can overflow **[I]**.

## 3. Multiply

* **128 by 128, sign-magnitude, fits-in-128 only.** `multiply` negates negative operands,
  calls `multiplyPositives`, and negates the product back (`Int128Math.java:251-281`) **[V]**.
  `multiplyPositives` (`:283-328`) takes `z1 = unsignedMultiplyHigh(aL, bL)` and `aL * bL`, the
  low halves of the two cross products `aL * bH` and `aH * bL`, and sums the high word. It
  throws when both high words are non-zero, when either cross product has a high half (two more
  `unsignedMultiplyHigh`), or when any partial or the result high word has its top bit set
  **[V]**. That is three `imulq` (low products), three `mulq` (`unsignedMultiplyHigh`) and eight
  tests per row (two high words, four signs, two cross-product highs).
* **64 by 64 to 128.** `multiply(long, long)` takes `abs` of both, then `left * right` and
  `Math.multiplyHigh`, and negates the pair when the signs differ (`Int128Math.java:342-359`)
  **[V]**. The `abs` and negate are not needed: `Math.multiplyHigh(a, b)` is already the signed
  high word of the exact product and `a * b` its low word, for every pair of longs **[I]**.
  Row 361's lane form needs `unsignedMultiplyHigh` and a sign correction because its limbs are
  unsigned; the scalar arm does not.
* **No 256-bit product, no precision-loss rounding.** A product past `2^127` throws before the
  rescale down (`DecimalOperators.java:326-342`) **[V]**. The 256-bit helpers
  `multiply256Destructive` (`Int128Math.java:365-559`, schoolbook on 32-bit limbs with a
  zero-limb skip) serve only division's dividend scaling (`:1026-1037`) **[V]**. So Trino has
  nothing for row 362's column sum or for 363's 256-bit dividend.
* **The test that pins the throw:** `12345678901234567890123456789012345678 * 9` raises, and
  the same digits at scale 38 times 9 return `1.1111111011111111101111111110111111110`
  (`TestDecimalOperators.java:420-431`) **[V]**.

## 4. Rescale and the rounding rule

* **`HALF_UP` away from zero, by sign-magnitude.** `scaleDown` negates a negative value, divides
  the magnitude, and negates back (`Int128Math.java:721-747`) **[V]**. That is Spark's
  `ROUND_HALF_UP` (`Decimal.scala` 121, 353) **[I]**. Trino has no `HALF_EVEN` anywhere in its
  decimal code (no match in `trino-main` or `trino-spi` main sources), so nothing for `bround`
  **[V]**.
* **Fast path for small non-negative values only:** `scaleFactor <= 18 && high == 0 && low >= 0`
  divides one long and rounds on `low % divisor >= divisor >> 1` (`Int128Math.java:689-704`)
  **[V]**. A negative value always takes the slow path, a performance asymmetry, not an error.
* **The slow path, "last-stage rounding".** For `10^k`, Trino picks whichever needs fewer
  32-bit-divisor steps (`Int128Math.java:734`):
  * by tens: divide by `10^9` chunks, truncating, and round on the **last** step's remainder
    only (`:778-800`). This is exact: the rounding threshold `10^k / 2` is a multiple of every
    earlier divisor, so the truncated stages cannot move the decision **[I]**;
  * by fives: divide by `5^13` chunks to `floor(n / 5^k)`, then shift right by `k` and round on
    bit `k - 1` (`:752-772`, `:802-836`). `floor(floor(n / 5^k) / 2^(k-1))` equals
    `floor(n / (5^k * 2^(k-1)))`, so this is 2.8's identity in the other order: 363 shifts first
    and divides by `5^k` second **[I]**. It is the form 2.8 credits as "Trino's last-stage
    rounding"; its soundness argument is the same one-line lemma.
* **Each chunk step is three hardware divisions.** `dividePositives(high, low, int)` divides the
  high word, then two 32-bit chunks, each `/` and `%` on a long (`Int128Math.java:1324-1343`)
  **[V]**. The divisor comes from a table, so the JIT sees no constant **[I]**. C2 turns a long
  division by a compile-time constant into a multiply-high (`transform_long_divide`,
  `long_by_long_mulhi`, `/home/max/proj/openjdk-build/jdk25/src/hotspot/share/opto/divnode.cpp`
  277, 354-433) **[V]**, so a generated S arm with the scale as a literal does not pay `idiv`
  **[I]**.
* **An unreachable edge.** `shiftRight` reads the rounding bit from `high` when `shift == 64`,
  where it lives in `low` (`Int128Math.java:819-820`); `shift` is a decimal scale, at most 38,
  so it is never reached **[I]**.
* **`floor` and `ceiling` reuse the `HALF_UP` rescale.** `ceilingLong` adds `10^s / 2 - 1` to a
  positive value (`10^s / 2` to a negative one) and rescales by `-s` with `HALF_UP`; `floorLong`
  subtracts the mirror constants (`MAIN/operator/scalar/MathFunctions.java:87-104`, `:316-334`,
  `:483-501`) **[V]**. For positive `n`, `HALF_UP((n + 10^s/2 - 1) / 10^s)` is
  `floor((n + 10^s - 1) / 10^s)`, the ceiling **[I]**. One constant add before one rounding
  division serves `round`, `floor` and `ceil`, with no remainder test. `truncate` is the
  truncating rescale (`:1134-1141`) **[V]**.

## 5. Division and remainder

Decimal division is out of milestone 8 (2.8); `%`, `mod` and `pmod` stay in.

* **Knuth's algorithm D on 32-bit limbs** (`divideUnsignedMultiPrecision`,
  `divideKnuthNormalized`, `estimateQuotient`, `Int128Math.java:1043-1145`): normalise the
  divisor by its leading zeros, estimate `qhat` from two limbs, correct it with the third, and
  add back when the multiply-subtract borrows (probability `2^-31`, its comment) **[V]**. A
  one-limb divisor takes a short division loop (`:1258-1282`) **[V]**. Every call allocates an
  `int[9]`, two `int[8]` and two `long[2]` (`:845-846`, `:892-893`, `:994-1016`) **[V]**.
* **The scale-up of an operand is `5^k` then a shift by `k`** (`shiftLeftBy5Destructive` and
  `shiftLeftMultiPrecision`, `Int128Math.java:1000-1014`), into a 256-bit limb array, so a
  rescale never overflows inside the division **[V]**.
* **`divideRoundUp` rounds `HALF_UP` by `2 * remainder >= divisor`**, the doubled remainder
  compared unsigned (`Int128Math.java:882-932`) **[V]**. The remainder is below the divisor,
  itself below `2^127`, so doubling cannot wrap: rapids' remainder-doubling hazard
  (`READING_RAPIDS.md` 1) does not arise for a 128-bit divisor **[I]**.
* **`remainder` takes its sign from the dividend** (`Int128Math.java:953-985`) **[V]**, which is
  `BigDecimal.remainder`, Spark's `%` **[I]**. Its result type,
  `min(p2 - s2, p1 - s1) + max(s1, s2)` at scale `max(s1, s2)` (`DecimalOperators.java:562-563`),
  is Spark's `Remainder` rule before the precision cap (fork `arithmetic.scala:1070-1078`)
  **[V]**. Both operands are rescaled to the common scale inside the 256-bit arrays, so a
  `DECIMAL(38, 0) % DECIMAL(38, 38)` needs no overflow path **[I]**.
* **Test vectors:** Hacker's Delight's `divmnu64` cases, the `qhat` off-by-one and off-by-two
  cases, add-back after multiply-subtract, and "with rescale" pairs up to scale 37
  (`TestInt128Math.java:293-400`), all in four sign pairs **[V]**.

## 6. Comparisons and the precision check

* **Compare: high words signed, then low words unsigned** (`Int128.compare`,
  `Int128.java:220-228`), the same as 359's plan **[V]**. Equality is both words equal
  (`:148-159`); the long-decimal type's comparison and hash operators read the two words straight
  from the block (`SPI/LongDecimalType.java:186-250`) **[V]**.
* **`10^38` check: two signed 128-bit compares against `+-(10^38 - 1)`**
  (`Decimals.overflows(long, long)`, `Decimals.java:319-323`) **[V]**, arrow-rs's table form
  (`READING_ARROW_RS.md` 3.5). For `p < 38` it is `abs` then one compare against `10^p`
  (`:325-336`), the `abs` allocating a new `Int128` for a negative value **[V]**. Both forms are
  open to 359; the two-sided one needs no negate with borrow in lanes **[I]**.
* **`compareAbsolute` does not take the absolute value.** It compares the raw high words
  unsigned (`Int128Math.java:561-593`), so every negative value is "larger" than
  `MAX_EXACT_DOUBLE`, and the decimal-to-double and decimal-to-float fast paths
  (`DecimalConversions.java:81`, `:125`) send every negative value to the string path **[V]**.
  The answer stays right; only negatives are slow. A name to avoid copying.

## 7. Casts

* **Decimal to integral rounds `HALF_UP`.** `shortDecimalToBigint` is
  `(decimal + tenToScale / 2) / tenToScale` by sign, and the `INTEGER`, `SMALLINT` and `TINYINT`
  forms are the same plus a range check (`MAIN/type/DecimalCasts.java:262-281`, `:313-337`,
  `:369-394`, `:426-450`); the tests expect `CAST(DECIMAL '2.5' AS BIGINT)` to be 3 and `-2.5`
  to be -3 (`TestDecimalCasts.java:515-553`, `:600-634`) **[V]**. Spark truncates toward zero
  (`Decimal.toLong`, `Decimal.scala:251-257`) and above that takes the double rule for the three
  narrow types (`roundToNumeric`, `:294-319`) **[V]**. **Nothing of Trino's integral casts can be
  taken**; their tests' expected values are wrong for Spark.
* **Integral to decimal** multiplies by `10^s` with `multiplyExact` or the 128-bit multiply,
  then checks `10^p` (`DecimalCasts.java:283-311`) **[V]**: 360's widening casts, nothing new.
* **Decimal to decimal: rescale with `HALF_UP`, then check `10^p`** (`DecimalConversions.java:
  210-283`) **[V]**. Its static "never fails" test, target integral digits at least the source's
  plus one when the scale shrinks (`MAIN/type/DecimalToDecimalCasts.java:41-48`), is arrow-rs's
  "infallible" rule (`READING_ARROW_RS.md` 1) found independently **[V]**.
* **A wrap in the 64-bit scale-up** (since 2019, `git blame`). `shortToShortCast` computes
  `value * scalingFactor` with no overflow check and only then checks `10^p`
  (`DecimalConversions.java:219-239`) **[V]**. `DECIMAL(18, 0)` value `184467440737095517` cast to
  `DECIMAL(18, 2)` gives `18446744073709551700`, which wraps to `84`; `84` passes the `10^18`
  check, so the cast would answer `0.84` where it must overflow **[V for the arithmetic, I for
  the planner reaching it; not run]**. Row 350's rescale-up must check before it multiplies, or
  bound the operand by a guard; this value is the fixture.
* **Decimal to `DOUBLE`.** One division `(double) u / 10^s` when `|u| <= 2^53` (and `s <= 22`
  above precision 18), otherwise `BigDecimal` or `parseDouble` of the exact string
  (`DecimalConversions.java:70-87`) **[V]**. This is 2.2's double guard; 2.2's
  `(long) (double) u == u` is the wider test. The guard went in only in June 2026 (commit
  `88c3aa559ca`, "Fix short decimal cast to double rounding error"); until then Trino divided
  every short decimal in double **[V]**.
* **Decimal to `FLOAT`: a midpoint test on the double quotient** (commit `160685cfefb`, 30 June
  2026, `DecimalConversions.java:89-120`) **[V]**:
  1. `s == 0`: `(float) u`, correctly rounded at any magnitude (`:91-93`);
  2. `q = (double) u / 10^s`; if the 29 bits a float drops are more than 8 double ulps away from
     the midpoint pattern `2^28`, narrow `q` (`:95-99`);
  3. else, if `|u| <= 2^53`, `q` is correctly rounded, so only an exact midpoint can misround:
     the sign of `fma(q, 10^s, -u)` says which way the exact value lies, and `q` moves to
     `nextUp` or `nextDown` before narrowing (`:100-114`);
  4. else `BigDecimal` (`:115-119`).
  Step 3 is the double-rounding argument behind 2.2's round-to-odd, applied only at the midpoint
  **[I]**. Step 2 is new: for `|u| > 2^53` the double quotient is within two ulps (the code's
  comment, `:62-63`), so a quotient far from a float midpoint narrows correctly. 2.2's `FLOAT`
  guard (`(long) (float) u == u` and `s <= 10`) sends every such lane to 366's exact arm; a bit
  test would keep all but about `17 / 2^29` of them on the fast path **[I; the bound needs its
  own proof, Trino has only tests]**. Step 1 is also wider than 2.2's guard, which does not name
  `s == 0` for `FLOAT`.
* **`DOUBLE` to decimal** is `BigDecimal.valueOf(d).setScale(s, HALF_UP)`
  (`DecimalConversions.java:151-172`), Spark's `Decimal(double)` then `changePrecision` **[I]**;
  NaN raises `INVALID_CAST_ARGUMENT` and infinity `NUMERIC_VALUE_OUT_OF_RANGE` (`:153-158`), where
  Spark returns null in every mode (the `NumberFormatException` is caught, fork
  `Cast.scala:1404-1410`) **[V]**.
* **`REAL` to decimal goes through the float's own shortest string**,
  `new BigDecimal(String.valueOf(f))` (`DecimalConversions.java:187-208`) **[V]**. Spark widens
  the float to a double first (`Cast.scala:1407`, `fractional.toDouble`) **[V]**, as
  `READING_RAPIDS.md` 2 item 5 also found: `0.1f` to `DECIMAL(20, 10)` is `0.1000000000` in
  Trino and `0.1000000015` in Spark **[V, Python]**. Not to be taken.
* **`BOOLEAN`**: decimal to boolean is `u != 0`; true to a decimal is `10^s`, raising when
  `p == s` (`DecimalCasts.java:232-260`) **[V]**, Spark's answer.
* **To and from `VARCHAR`**: plain digits, never an exponent (`Decimals.java:181-212`), which
  matches Spark's `toPlainString` (fork `ToStringBase.scala:200`); parsing is `BigDecimal` with
  `HALF_UP` (`DecimalCasts.java:614-662`) **[V]**. Strings are out of milestone 8.

## 8. Allocation per value, and what it says about S

* **The operators allocate on every row.** Each long-decimal add, subtract and multiply creates
  a `long[2]` and returns a new `Int128` (`DecimalOperators.java:147`, `:163`, `:242`, `:329`,
  `:337`, `:354`) **[V]**; a division adds the five arrays of section 5 **[V]**; `Decimals.
  overflows(Int128, p)` and `negateExact` allocate for negative values (`Decimals.java:335`,
  `Int128Math.java:649-654`) **[V]**. Whether C2 removes the `long[2]` depends on inlining
  `rescale`, which is large; the returned `Int128` escapes into the block builder either way
  **[I]**. Trino commits no numbers for this: `BenchmarkInt128` times single calls, with a setup
  that leaves both high words zero (`leftHigh = leftHigh >> 63`, `BenchmarkInt128.java:83`)
  **[V]**, and no results file.
* **The out-parameter primitives are the right shape for S.** `add`, `subtract`, `multiply` and
  `rescale` with `(long[] result, int offset)` allocate nothing (`Int128Math.java:107-119`,
  `:177-225`, `:251-281`) **[V]**. A kernel's S arm can write the high and low words straight into
  its output words or two locals, so no object per value is needed **[I]**. What S cannot borrow
  from Trino is the control flow: overflow is an exception and every primitive branches on sign.
  In a kernel S must set a status lane instead, and the sign-magnitude multiply's six tests are
  data-dependent branches, predictable only while no row overflows **[I]**.
* **So Trino is the floor for S, not its form.** It shows a pure-Java 128-bit decimal is
  practical with `Math.multiplyHigh`, `unsignedMultiplyHigh` and the out-parameter style, and it
  shows the cost S must avoid: table-driven `idiv`, a `long[2]` and an `Int128` per row. The
  row path Varka is measured against (`VarkaDecimalBaselineBenchmark`, row 377) allocates a
  `BigDecimal` per value instead, so Trino's numbers, had there been any, would not transfer.

## 9. Where Trino's answers are not Spark's

Spark is the oracle; every Trino fixture must have its expected value recomputed.

1. **Result types.** Add and subtract: Trino keeps the integral digits and cuts the scale with
   no floor, `min(raw_scale, 38 - integral)` (`DecimalOperators.java:86-93`); Spark keeps one
   more integral digit and floors the scale at `min(scale, 6)` (`DecimalType.adjustPrecisionScale`,
   fork `DecimalType.scala:175-200`) **[V]**. `DECIMAL(38, 0) + DECIMAL(38, 38)` is `(38, 0)` in
   Trino and `(38, 6)` in Spark **[I]**. Multiply: Trino's raw precision is `p1 + p2`, Spark's
   `p1 + p2 + 1` (`DecimalOperators.java:279-291`) **[V]**; `DECIMAL(19, 19) * DECIMAL(19, 19)`
   is `(38, 38)` in Trino and `(38, 37)` in Spark **[I]**. A legacy flag selects an older rule
   (`:81-84`, `:273-276`) **[V]**.
2. **A capped product past `2^127` raises in Trino and rounds in Spark** (section 3). For
   `DECIMAL(38, 18) * DECIMAL(38, 18)` both engines give `(38, 6)`, and arrow-rs's
   `123456789.555555555555555555 * 11.222222222222222222` (`READING_ARROW_RS.md` 3.2) raises in
   Trino, the exact product being about `10^45` unscaled **[I]**.
3. **A capped add whose operand rescale passes `2^127`.** Trino's test
   `17014000000000000000000000000000000000 + (-7014000000000000000000000000000000000.1)` passes
   and `17015...` (same digits otherwise) raises, because `17015 * 10^34` times 10 passes `2^127`
   (`TestDecimalOperators.java:162-170`; subtract `:276-282`) **[V]**. In Spark both are
   `DECIMAL(38, 1)` and both answer 38 nines at scale 1 **[V, Python]**; Trino answers `10^37`
   at scale 0 for the first. These are capped shapes whose scale is **not** cut (`s = 1 <= 6`),
   so no division is needed, yet the rescaled operand needs bits past 128 **[I]**.
4. **No null mode**: every overflow raises (section 1); Spark's LEGACY and TRY give null.
5. **Decimal to integral rounds instead of truncating**, and `REAL` to decimal skips the double
   widening (section 7).

## 10. Fixtures worth taking

Expected values are to be recomputed with the fork's Spark, not copied from Trino.

1. **Rescale and `HALF_UP`** (`TestInt128Math.java:130-177`): `15`, `-15`, `5` down by 1
   (`2`, `-2`, `1`); `150000000000000000000` and `50000000000000000000` down by 20 (`2`, `1`);
   `150500000000000000000` down by 18 (`151`); `2^62` and `2^63` down by 18 (`5`, `9`);
   `10^38 - 1` down by 1 (`10^37`, a carry into a new digit); `-(10^38 - 1)` down by 10; `1` up
   by 37 and by 39 (overflow). For 350, 351 and 363.
2. **The 128-bit multiply** (`TestInt128Math.java:201-269`): `(2^64 - 1) * (2^56 - 1)`,
   `0xFFFFFF0096BFB800 * 0x39003539D9A51600`, `99999999999999 * -10^24`, and the overflow
   corners `2^64 * 2^64`, `(2^64 - 1)^2`, `2^126 * 2` in both signs; `multiply256` of
   `(10^38 - 1)^2` with its eight expected limbs. For 361 and 362, beside arrow-rs's 29 corners.
3. **Add and subtract at the edge** (`TestDecimalOperators.java:143-175`, `:258-286`): 38 nines
   plus `1`, plus `.1`, plus itself, in both signs; the two capped-add vectors of section 9
   item 3; `10 + 10^-37`, which rounds back to 10 (Trino at scale 36, Spark at scale 35 **[I]**).
   For 360 and 363.
4. **The 64-bit scale-up wrap** of section 7: `DECIMAL(18, 0)` `184467440737095517` to
   `DECIMAL(18, 2)` must overflow, not answer `0.84`. For 350.
5. **Decimal to floating** (`TestDecimalConversions.java:99-147`, `TestDecimalCasts.java:
   1187-1204`, `:1427-1466`): the cutoffs `2^24 +- 4` and `2^53 +- 4` in both signs;
   `9007199791611905`; `391462049447` at scale 14 (double-rounds to `FLOAT` while far below
   `2^53`); `960000084148223999` at scale 2 (above `2^53`, near a float midpoint);
   `83886085` and `83886095` at scale 1 (exact float midpoints, ties down and up to even);
   `300315360073` at scale 14 (inside the margin, not a midpoint); unscaled `912057769880088000`
   at scale 3 and `576460752303480000` at scale 2 (unscaled past `2^53`, value exact); `16777217`
   at scale 0 to `FLOAT`; each tested at every scale 0 to 38 against `BigDecimal`. For 350, 364
   and 366.
6. **Division and remainder** (`TestInt128Math.java:293-400`): Hacker's Delight's cases and the
   Knuth corner cases, for whichever row takes decimal `%`.

## 11. For Varka

* **Row 359.** The compare (high signed, low unsigned) and the two-sided `+-(10^38 - 1)` check
  are confirmed a third time (section 6); nothing changes. For the spike's S arm: write it in
  Trino's out-parameter style with no object per row and a status lane instead of an exception,
  and put the scale in as a literal so C2 compiles `/ 10^k` to a multiply (sections 4, 8). Trino
  gives no measurement to borrow. The arithmetic carry of section 2 is an option for S and for
  any shape where masks fall back, not a change to the lane plan.
* **Row 360.** Two proof notes and a sharpening. (a) Section 2: for an uncapped shape the
  `10^p` check is provably dead, since the exact result fits the type; for a capped shape the
  check is `10^38` alone. The done-when's "`CheckOverflow`'s `10^p` check" can be stated that
  way, the elision as an SMT line **[I]**. (b) The signed-overflow test `((r ^ a) & (r ^ b)) < 0`
  is the 129th bit in four lane operations. (c) Section 9 item 3: a capped add whose scale is
  not cut needs bits past 128 in the operand rescale but no division, so it could land with 360
  rather than wait for 363; its two vectors are fixtures either way. The owner decides whether
  (c) moves a shape.
* **Row 361.** The scalar 64 by 64 product is `Math.multiplyHigh(a, b)` and `a * b`, exact with
  no `abs` and no sign correction (section 3); Trino's `abs` and negate are not worth copying.
* **Row 362.** Nothing to take: Trino has no 256-bit product and raises instead (section 3). Its
  fits-in-128 `multiplyPositives` is the S arm for the static shortcut `p1 + p2 + 1 <= 38`, where
  even its overflow tests are dead, so the wrapping form (three `imulq`, one `mulq`) suffices
  **[I]**.
* **Row 363.** Trino's "by fives" path is the same identity as 363's pre-shift, in the other
  order (section 4); its "by tens" path shows rounding on the last remainder alone is exact,
  worth a line in the lemma. The rescale fixtures of section 10 item 1 join the named tests.
  `floor` and `ceil` as a signed constant add before the one `HALF_UP` division (section 4)
  serve the rounding functions 363 names, with no remainder test.
* **Row 350.** Two additions. The scale-up must not wrap: Trino's 64-bit `shortToShortCast` does
  (section 7, fixture 4 of section 10). And the `FLOAT` target: Trino's step 1 (`s == 0` at any
  magnitude) and step 2 (the midpoint-margin test on the double quotient for `|u| > 2^53`)
  would widen 2.2's `FLOAT` guard and leave fewer lanes to 366; step 2 needs its own error
  bound proven before it is used **[I]**. Option arms if the owner wants them.
* **Row 351.** The `floor` and `ceil` construction of section 4 over the 64-bit form too.
* **Row 364.** The decimal-to-`DOUBLE` and `FLOAT` fixtures of section 10 item 5 at every scale;
  the decimal-to-decimal "never fails" test agrees with arrow-rs. **Not worth taking**: Trino's
  decimal-to-integral casts round `HALF_UP` where Spark truncates (section 7).
* **Row 366.** Trino's float method is a second implementation of the double-rounding argument
  behind 2.2's round-to-odd, tested but not proven; its vectors are fixtures.
* **Row 377.** Nothing: Trino commits no decimal benchmark results, and its row path allocates
  differently from Spark's (section 8).
* **Decimal `%`, `mod`, `pmod`.** Trino's remainder (Knuth D, sign of the dividend, operands
  rescaled in 256-bit limbs) matches Spark's rule; no row of 359 to 364 names decimal `%`
  although 2.8 keeps it in. Its tests are fixtures for whichever row takes it.
* **Decimal division**: `m9/SCOPE.md` item 2. `divideRoundUp`'s `5^k`-then-shift scale-up and its
  doubled-remainder `HALF_UP` are the reference when that item is planned.
* **Not worth taking**: the result-type rules, the no-null overflow model, `REAL` to decimal
  through the float's string, NaN and infinity raising (all section 9); `compareAbsolute`'s
  name; sign-magnitude multiply in lanes (arrow-rs's two's complement form is cheaper,
  `READING_ARROW_RS.md` 4).

Nothing here contradicts 2.8. Not read: Trino's decimal aggregations beyond `sum`'s overflow
counter, the JSON and `VARIANT` casts, the `NUMBER` type, and the Parquet and ORC decimal
readers.
