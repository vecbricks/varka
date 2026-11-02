# VARKA-348: The 64-bit decimal comparisons, choice, `IN`, null tests, negation and `abs`

## 0. What exists now (admission check)

From master at `ef32e007d3a` and from row 347's two local branches, which hold the code this row
builds on and are not merged: the leaf (step 3, `varka-347-leaf` at `d279e924df5`) and the first
reader (step 5, `varka-347-reader` at `95e8e7fba90`); Varka line numbers below are the reader
branch's (30 October 2026; Varka paths under `sql/catalyst/src/main/java/org/apache/spark/sql/
catalyst/expressions/codegen/` unless named), Spark's are master's (`sql/catalyst/.../` and
`sql/api/.../` as named). The row's text is the milestone plan's 2.8, its 4.1 ("157, 348 and 349
with 347's load land beside it") and wave F3. Many Spark facts were read for the lane pair in
`VARKA-359.md` 0; they are reused here with what differs at 64 bits said. The JDK's facts were read
in `/home/max/proj/openjdk-build/jdk25`.

1. **Two operands of one scale already compare.** Row 347's reader compiles `=`, `<>`, `<`, `<=`,
   `>`, `>=` and `<=>` of two decimals of one scale, a column or a literal on each side, and
   `IS [NOT] NULL` of a column, as the existing `Compare`, `NullSafeEqual` and `IsNotNull` over the
   unscaled longs (`VarkaDecimalCompiler`, lines 75-96 and 106-117; reached from
   `VarkaConditionCompiler.java:959-961`, `:1173-1176`, `:1233-1235`). Unequal scales decline naming
   this row (line 79), a type wider than 18 digits naming 359 (line 84), and every other operand -
   a negation, a choice, a cast that changes the scale - naming rows 348 to 350 (line 139). A
   decimal in a value position declines in `VarkaTimeCompiler.java:159-169`. The load's precision
   check, which this row's done-when names, is built and tested in 347's step 3 (the leaf condemns
   `hi != lo >> 63` and `|lo| > 10^p - 1` under `Refusal.DECIMAL_SLOT`), so that clause is met
   before this row starts, as `VARKA-347.md` 0's departures already said it would be.
2. **Spark compares by value, so unequal scales are a rescale, and coercion supplies it.** A
   comparison is `Decimal.compare` (`sql/api/.../types/Decimal.scala:482-488`): the long fields
   only when both are compact and of one scale, `BigDecimal.compare` otherwise; `=` and `IN` are
   `equals`, `compare == 0` (`:490-495`; `predicates.scala:528`, `:684`); `<=>` adds the null rule
   (`:1177-1209`). `DecimalPrecisionTypeCoercion.decimalAndDecimal` casts both sides of a
   `BinaryComparison` of two decimal types to `widerDecimalType` (`analysis/
   DecimalPrecisionTypeCoercion.scala:137-144`): scale `max(s1, s2)`, precision that plus
   `max(p1 - s1, p2 - s2)` (`:195-199`), bounded by `boundedPreferIntegralDigits` only above 38
   (`:201-207`, `DecimalType.scala:148-158`). So the common type `(P, S)` keeps every integral
   digit, and the operand of the smaller scale `s` arrives as `Cast(x, DECIMAL(P, S))` with
   `S - s = k > 0` and `P - S >= p - s`, which gives `p + k <= P`.
3. **That cast never fails and never rounds.** `Cast` of a decimal to a decimal is
   `toPrecision(..., ROUND_HALF_UP, !ansiEnabled)` (`expressions/Cast.scala:1400-1401`), which calls
   `changePrecision` (`Decimal.scala:387-470`): a larger scale multiplies a compact value by
   `POW_10(k)` when it stays under 18 digits and goes to `BigDecimal` otherwise (`:443-453`), then
   checks the precision (`:458-471`). With `|u| <= 10^p - 1` and `p + k <= P`, the value
   `u * 10^k` has at most `P` digits, so the check passes in every mode and nothing rounds. At
   `P <= 18` the product is below `10^18 < 2^63`, so a long multiply by the constant `10^k` is
   exact. This is the row text's "constant rescale proven to fit the long".
4. **The choices widen the same way.** `findWiderTypeForDecimal` gives two decimals
   `widerDecimalType` and an integral type meeting a decimal `DecimalType.forType`, `(3, 0)`,
   `(5, 0)`, `(10, 0)` or `(20, 0)` (`analysis/TypeCoercionHelper.scala:261-272`;
   `DecimalType.scala:125-137`), for `In` (`:451`), `Coalesce` (`:538`), `Greatest` (`:550`),
   `Least` (`:557`), `CaseWhen` (`:651-662`) and `If` (`:677`). `Greatest` and `Least` pick by
   `ordering.gt` and `ordering.lt`, skipping nulls (`arithmetic.scala:1458-1462`, `:1363-1367`).
   An `IN` with an integral literal over `DECIMAL(9, 2)` is therefore at `(12, 2)`, not at the
   literal's own precision.
5. **Negation and `abs` cannot fail.** `UnaryMinus` over a decimal is `unary_-`
   (`arithmetic.scala:70`), which rebuilds the value at the same precision and scale
   (`Decimal.scala:554-560`); `Abs` is `abs` (`arithmetic.scala:186-187`; `Decimal.scala:562`).
   `|-u| = |u|`, so neither leaves its precision. `failOnError` reaches neither for a decimal. The
   census note "the minimum value overflows" is false for a decimal, as `VARKA-359.md` 0 item 2
   found; at 64 bits it is also false of the lane, since `|u| <= 10^18 - 1 < 2^63`.
6. **The IR has every node this row needs, at the long lane, but `abs`.** `IntArith` in `WRAP`
   (`varka/VarkaVectorIR.java:723`), `IntNeg` (`:752`), `Compare` (`:1027`), `NullSafeEqual`
   (`:1048`), `IsNotNull` (`:1174`), `IfElse` (`:1260`), `Greatest` and `Least` (`:1293`, `:1300`)
   and `LiteralSlot` at the long lane (`:519`). `IntNeg`'s `abs` flag is the narrow lanes' only:
   the emitter throws on it at the long lane (`varka/VarkaVectorWalk.java:920-922`). The
   long-lane `IN` chain exists for `TIMESTAMP_NTZ`, `compileLongInList` (its arm at
   `VarkaConditionCompiler.java:722`), capped at `MAX_IN_LITERALS = 16`
   (`VarkaNodeCompiler.java:125`). Since VARKA-497 that method is gone: one arm, `inArm`
   (`VarkaConditionCompiler.java:800`), serves `IN` on every lane through `compileIn` (`:885`),
   and the cap is at `VarkaNodeCompiler.java:130`.
7. **The JDK.** `Op_AbsVL` is refused below AVX-512 (`cpu/x86/x86.ad:1332-1336`), so a lanewise
   `ABS` on longs is the allocating Java fallback on the EPYC 7763 and intrinsic on the laptop
   (`vabsL_reg`, `evpabsq`, `:8247-8258`). `Op_MulVL` is admitted from SSE4.1 (`:1361-1365`); below
   AVX-512DQ it is the seven-instruction emulation (`vmulL_reg`, `:6071-6093`) unless C2 sees both
   inputs fit 32 bits (`vmuldq_reg`, `vmuludq_reg`, `:6096-6117`; the patterns at
   `share/opto/vectornode.cpp:2196-2237`), which a rescaled 18-digit operand does not show. The
   laptop's census (`sql/varka/operator_census.json`) has `long.MUL`, `LT`, `NE`, `GT` intrinsic
   at 512 and 256 bits and the compares the fallback at 128; it has no `long.ABS` or `long.NEG`
   cell, and the EPYC column (`results/VARKA-376-census-epyc-7763.json`) has neither `long.MUL`
   nor the signed compares.
8. **No decimal output exists.** A decimal root declines; `VarkaLaneArrow` maps `DECIMAL` to
   `DecimalVector` with no allocator, "row 157's store" (sql/core `VarkaLaneArrow.java:117`). Every
   value form of this row - a choice, `-x`, `abs(x)` - is an output only through 157.
9. **The registry owes the conformance suite to this row**, `Owed("Decimal", "348")`
   (`varka/VarkaTypes.java:389-392`), while 359's answered question 4 (a) has 359 teach
   `VarkaTypeConformance` sixteen-byte slots and land the decimal suite over both representations.
10. **The baseline covers part of the row.** `VarkaDecimalBaselineBenchmark` (row 377) times, at
    `(9, 2)`, `(18, 4)` and `(38, 10)` on the EPYC 7763, a filter `x < y` and a projected
    `CASE WHEN x < y THEN x ELSE y END`, both at one scale: 63.4 and 62.7 ns a row at `(9, 2)`
    on the row path
    (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`). It has
    no unequal scales, `IN`, `<=>`, negation or `abs`, and no comparison as a projected value.
11. **Related rows.** Row 333 (planned) owns `NullValue`, the typed null leaf `CASE` without `ELSE`,
    `nullif` and `nullifzero` need, and the long-lane `IN` widening; row 497 (planned on branch
    `varka-497-499-plan`) folds the four `IN` arm pairs - date and interval, narrow, floating and
    `TIMESTAMP_NTZ` - into one keyed on the lane, reading each literal through the registry's
    lane bits; row 503 (open, on branch `varka-503-two-lane-decline`)
    declines a long-lane condition root at two lanes on x86; 347's answered question 4 applies
    row 27's rule there instead, unless 347's step 6 reading shows a loss.

### What differs from the row text and the milestone plan

- **The precision check is already 347's** (item 1). The done-when's clause is met by the leaf;
  this row adds the tests that the check still declines under each new form (a bad slot under a
  rescale, under `IN`, under a choice arm not taken).
- **The rescale is a multiply by `10^k` on one side only, never a division** (items 2, 3): at a
  common type within 18 digits nothing rounds, so no rescale down exists in this row. 363's capped
  rescale down arises only above 38.
- **`abs` needs a lowering the IR lacks at the long lane** (items 6, 7): a compare, a negation and
  a blend by default, the lanewise `ABS` as an arm where it is intrinsic.
- **The value forms need row 157's store** (item 8). Under a comparison or a null test they fuse
  without it; as outputs they wait for 157, so the row is two pull requests (section 5).
- **An integral operand is a cast this row does not build** (item 4): `i < d9` arrives as
  `CAST(i AS DECIMAL(10, 0))` widened to the common type, an integral-to-decimal cast, which
  declines naming 350.

## 1. Goal and non-goal

Every comparison, `IN`, null test, choice, negation and `abs` over decimals of precision 18 or less,
at any scales whose common type stays within precision 18, fuses on the long lane with answers equal
to Spark's in every mode, the coarser operand rescaled by a constant multiply proven exact, and a
batch Spark's reader would raise on declined by 347's check. Not this row: arithmetic and the three
decimal internals (349), casts other than coercion's widening ones (350), the rounding functions
(351), any common type above 18 (359, 360, 363), the store itself (157), `hash` (337), a negative
scale.

## 2. Design

### 2.1 The value: an unscaled long and its type

`VarkaDecimalCompiler` gains a compile-time record, never an IR node:

    record Scaled(VarkaVectorIR unscaled, DecimalType type)

whose `unscaled` is a long-lane node holding `u` with value `u / 10^type.scale()` and
`|u| <= 10^type.precision() - 1` on every valid lane, and one entry point,
`value(Expression, VarkaSlotTable, VarkaSlotTable, DeclineSink) -> Optional<Scaled>`, which every
decimal consumer calls, the condition arms and the value arms alike. It answers for:

| expression | `unscaled` | type |
|---|---|---|
| a decimal column | 347's `DecimalColumn(ordinal, LONG, p)` | the column's |
| a literal (after folding) | its unscaled long in a long-table slot | the literal's |
| `Cast(x, DECIMAL(P, S))`, `S >= s`, `P - S >= p - s`, `P <= 18` | `rescale(x, S - s)` | `(P, S)` |
| `UnaryMinus(x)` | `IntNeg(WRAP, x)` | `x`'s |
| `Abs(x)` | 2.6 | `x`'s |
| `If`, `CaseWhen` with `ELSE`, `Coalesce`, `Greatest`, `Least` | 2.5 | the node's |
| `Add`, `Subtract`, `Multiply`, `MakeDecimal`, `CheckOverflow` | `VARKA-349.md` 2 | the node's |

and declines with its reason for everything else: a cast that changes the integral digits or
lowers the scale (350), an integral source (350), a type above 18 digits (359, and 360 where a
rescale is needed), a negative scale. The invariant `|u| <= 10^p - 1` is what every rule below
reads, and it holds by construction: a column by 347's check, a literal by the compile-time test
347's reader already makes (line 195), and every other form by its proof in section 3.

`rescale(Scaled x, int k)` is the one place a rescale is built: the identity for `k = 0`; for a
literal, the multiplied constant in a new slot (no runtime work); otherwise
`IntArith(MUL, WRAP, x.unscaled, slot(10^k))`. It refuses `x.type.precision() + k > 18` by
throwing: the compiler only calls it after the type rule of 2.2 has passed.

### 2.2 The rescale, and its overflow defence

The 28 October rule of rows 350 and 360 asks every rescale-up site to name its defence. Here it is
**guard-the-inputs, the guard being 347's precision check**: the leaf declines any batch with a
valid `|u| >= 10^p`, so the multiply only ever sees `|u| <= 10^p - 1`, and with `p + k <= P <= 18`
(item 0.2), `|u * 10^k| <= 10^18 - 10^k < 2^63`. No check reads a value that may have wrapped,
because no value reaches the multiply that could. For a computed operand (a negation, a choice,
349's arithmetic) the guard is the operand's own proven bound, the same inequality. The test at the
first value the guard rejects is the slot `10^p` under a rescale (section 3, item 1), and the
compile-time edge is `P = 19`, which declines (2.7).

Since `k` is at most 17 and `10^k` a broadcast constant, the multiply's cost is the host's
`MulVL`: one `vpmullq` with AVX-512DQ, the emulation on the EPYC 7763 (item 0.7). Its emitted mix
is read by `dev/varka_emit.sh --asm` on both and recorded.

### 2.3 Comparisons, `IN` and the null tests

- **A comparison** of two decimal operands is `value()` on each; coercion has already given them one
  type, so the existing `Compare` or `NullSafeEqual` over the two `unscaled` nodes is Spark's
  answer, the rescale sitting inside whichever operand was cast. `<>` stays the `NOT` of the
  equality, as 347's reader builds it. `operands()` keeps its hand-built-tree guard: two
  different types after `value()` decline.
- **`IN` and `InSet`** of a decimal value: no arm of its own. Row 497's single arm keyed on the
  lane gains one reader case, a decimal element's unscaled long at the `In`'s type (`setScale`
  exact, as 347's literal reader does, an inexact or wider one declining), and the value is
  `value()`'s `unscaled`; the dedup, sort, cap of 16 and balanced fold are 497's. A null element
  declines, as for every lane. So the `IN` forms land after 497, and no fifth arm is written.
- **`IS [NOT] NULL`** over any `Scaled` value is `IsNotNull(unscaled)`, widening 347's column-only
  arm: a negation, a choice or a rescale of a column is null exactly where Spark's is, and every
  decimal leaf beneath still arms its slot check, the rule 347's step 5 settled for the column
  (Spark's `IS NULL` evaluates its child).

### 2.4 Under a condition and as a value

Every form of this row is a condition or a long-lane value. A value consumed by a comparison, `IN`
or a null test needs no store, so `-d9 < d9b`, `abs(d9) < d9b`, `CASE WHEN b THEN d9 ELSE d9b END
< d18` and `coalesce(d9, d9b) IS NULL` fuse in the first pull request. A decimal value as an output
root needs row 157's store, which writes the value word and its sign extension into a
`DecimalVector`'s sixteen-byte slots; its plan, written beside this one, makes it a root-only node,
`WidenDecimal(child)`, carrying no precision or scale, which the evaluator takes from the output's
type. The compiler's decimal root is then `WidenDecimal(Scaled.unscaled)`, the output's type being
the `Scaled` type `value()` computed, at precision 18 or less. Since every value this row builds is
within its precision, the store needs no check.

### 2.5 The choices

`If`, `CaseWhen` with an `ELSE`, `Coalesce`, `Greatest` and `Least` take their branches through
`value()` and build the existing long-lane node over the `unscaled` nodes: after coercion every
branch has the node's type, so no rescale is added here (a branch that was cast carries its rescale
inside). A branch of another type in a hand-built tree declines. The `Greatest` and `Least` nodes'
substitute-then-max is Spark's null-skipping pick (item 0.4) over unscaled values of one scale,
which order as the values do. `CASE` without `ELSE`, `nullif` and `nullifzero` need row 333's
`NullValue` and decline naming 333 until it lands; `nvl`, `ifnull` and `zeroifnull` arrive as
`Coalesce`, `nvl2` as `If`, and `between` as two comparisons, so they fuse with no arm of their own.

### 2.6 Negation and `abs`

`-x` is `IntNeg(WRAP, x)` in every mode: its one overflowing input, `Long.MIN_VALUE`, is outside
`|u| <= 10^18 - 1`, so the check `FAIL` would build is dead, and the node's javadoc gains the
sentence that a decimal negation is `WRAP` by its bound. `abs(x)` is, by default,

    IfElse(Compare(LT, x, slot(0)), IntNeg(WRAP, x), x)

a compare, a negation and a blend; the compare and the blend are intrinsic at 512 and 256 bits on
x86 by the census, and the negation has no cell yet, which step 1 adds (`long.NEG`). The arm is
`IntNeg(WRAP, x, abs = true)` at the long lane, the emitter's refusal at
`VarkaVectorWalk.java:920-922` lifted for `WRAP`: one `lanewise(ABS)`, intrinsic only with AVX-512
(item 0.7). It sits behind a `Flag` `decimalAbsLanewise` (`Reason.MACHINE`, off by default), kept
as the house keeps a losing arm, and is measured against the blend at 512 bits on the laptop; on
the EPYC 7763 it is the fallback and is not the default there whatever the laptop says.

### 2.7 Where the 64-bit form stops

The form stops where the common type passes 18 digits: `max(s1, s2) + max(p1 - s1, p2 - s2) > 18`
for a comparison, `IN` or choice. Each decline names its row:

| shape | declines naming |
|---|---|
| a common type of 19 to 38 digits, one scale | 359 |
| a common type of 19 to 38 digits, a rescale up | 359 and 360 (the 128-bit rescale) |
| `DECIMAL(18, 0) < DECIMAL(18, 2)`: `(20, 2)` | 359 and 360 |
| a common type capped at 38, its scale cut | 363 |
| an integral operand (`CAST(i AS DECIMAL(10, 0))`) | 350 (and 360 for `(20, 0)`) |
| a negative scale | the milestone plan's 2.8 |
| a decimal in an int-driven kernel | 347's leaf rule |

### 2.8 At 128 bits

At two long lanes on x86 every compare, blend and mask of this row's kernels is the fallback
(item 0.7; 347's step 5 reading of `width_audit.json`). The rule is 347's answered question 4: a
decimal kernel fuses at two long lanes as the long filters do, unless 347's step 6 reading shows a
loss they do not have; if row 503 lands, its decline applies to these condition roots as to every
long-lane one, and the projections fuse and are measured. On aarch64 every operator is intrinsic
at 128 bits.

### 2.9 Compiler, reference, cost, warm-up

`VarkaDecimalCompiler` grows from the operands of 347's forms to `value()` and the forms above;
`VarkaTimeCompiler`'s decimal arm hands a decimal value to it and keeps declining what `value()`
declines. The reference evaluator, the cost model and the budget need nothing: every node is an
existing long-lane node. The warm-up's long block gains the multiply by a broadcast constant if
`VarkaWarmupReachSuite` finds it missing, and `ABS` with the arm.

## 3. Tests and proofs

1. **The slot table under the new forms** (`VarkaDecimalLoadSuite`), at 2, 4 and 8 long lanes,
   both bodies, first group, middle and last row: a valid `10^p`, `-10^p` and a wrong high word
   under a rescale (`DECIMAL(9, 2)` against `DECIMAL(9, 4)`), under `IN`, under `IS NULL` of a
   negation and under a `CASE` arm the batch never takes all decline with `STATUS_DECIMAL_SLOT`;
   the same slots null do not. The planted fault, for row 504: under a test flag the leaf drops
   its precision bound, and the rescale of `10^17` at `k = 2` wraps; the table and the refinement
   check 2 below must both fail with the flag on.
2. **The rescale edges**, against `BigDecimal`: `p + k = 18` at its extremes (`DECIMAL(9, 0)`
   against `DECIMAL(18, 9)`, `u = +-(10^9 - 1)`, `k = 9`), operands equal after the rescale and one
   unit apart (`0.10` against `0.100` and `0.101`), and `p + k = 19`, which declines at compile
   time naming 359 and 360.
3. **The proofs.** A new file, `sql/varka/proofs/decimal64.smt2`, written by `VarkaProofFiles`
   from the code's own constants (the powers of ten and `DecimalColumn.MAX_LONG_PRECISION`), over
   integers under `java.smt2`'s operators, all linear since every multiplier is a constant:
   - for every `(p, k)` with `1 <= p` and `p + k <= 18`: `|u| <= 10^p - 1` gives
     `lmul(u, 10^k) = u * 10^k` (no wrap), `unsat`; and at `p + k = 19` the wrap has a witness,
     `sat`, so the bound is the tight one;
   - the comparison lemma: for scales `s1 < s2`, `k = s2 - s1`, each of `<`, `<=`, `=` over
     `u1 * 10^k` and `u2` is the comparison of the rationals `u1 / 10^s1` and `u2 / 10^s2`;
   - negation: `|u| <= 10^18 - 1` gives `lneg(u) = -u`; `abs`'s blend and the lanewise form both
     give `|u|`.
   Each coverage row of section 3 item 4 then gets its five `refinement.smt2` checks in Spark
   mode through `VarkaRefinementFile`, as row 490 requires of every new row: the precondition
   `|u| <= 10^p - 1` per valid column lane, check 2 the decline where the specification raises
   (a slot outside the precision), check 4 the value. `spec/STANDARD.md` 4.1 and 4.2 speak of exact
   numerics through C4's int32 and int64; they gain one sentence each giving a `DECIMAL(p, s)` the
   algebraic value `u / 10^s` and the range `|u| <= 10^p - 1`, an addition to the definition and
   not a delta, and `EXTENSIONS.md` gains `abs`, `greatest` and `least` over a decimal. Row 506's
   printer renders the kernels of 359 to 364 and is not this row's prerequisite; its first users
   are 361 and 362. Solver times are stated before and after, against risk 5.
4. **Coverage rows** in the "DECIMAL columns" family, with two fixture columns added to
   `VarkaCoverageSuite` and the differential's derivation: `d9s4` (`DECIMAL(9, 4)`, from `l`'s last
   nine digits) and `d16s9` (`DECIMAL(16, 9)`). Predicates: `d9 < d9s4`, `d9 = d9s4`, `d9 <=> d9s4`,
   `d18 > 1.5`, `d9 = 0.425` (the literal at the larger scale), `d9 IN (1.25, 2.5, 3)`, the
   eleven-literal `InSet` row, `-d9 < d9b`, `abs(d9) >= d9b`, `greatest(d9, d9s4) > d9b`,
   `coalesce(d9, d9b) IS NULL`, `(-d18) IS NOT NULL`, and `CASE WHEN d9 < d9b THEN d9 ELSE d9s4
   END > 0`. Projections, after 157: `CASE WHEN d9 < d9b THEN d9 ELSE d9b END`, `if(b, d18,
   d18b)`, `coalesce(d9, d9s4)`, `greatest(d18, d18b)`, `least(d9, d9s4)`, `-d18`, `abs(d18)`.
   Each names its verification for row 490's check: its `refinement.smt2` section, or
   `differential` where the refinement is owed with a reason.
5. **The differential and the matrix**: the coverage differential over every fixture under both
   consumers, the golden suite's Varka arm, and the option matrix dispatched on the branch, where
   `decimalAbsLanewise` gets its configuration from `VarkaEmitOption.TABLE`.
6. **The fuzzers.** The IR fuzzer's decimal sequence (`VarkaIrGrammar`, its own seed, so no
   existing hash moves) draws the forms of 2.1 over in-precision values, the bounds `+-(10^p - 1)`,
   a rescale at every `k` the type allows, and garbage under nulls; the Spark-level fuzzer's
   decimal columns are row 352's, and this row adds the shapes to its list.
7. **The generated files** in `ADDING_A_TYPE.md`'s order: `coverage.json`, `docs/sql-varka.md`,
   `emitted_bytes.json` (new rows only, no existing shape moving), `width_audit.json`,
   `census.tsv`.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code**, in row 377's class
`VarkaDecimalBaselineBenchmark`, whose two arms are named so the labels survive the lane landing:
at `(9, 2)` and at `(18, 4)`, as filters, `x < w` with `w` at another scale inside 18 digits
(`DECIMAL(9, 4)` at the first, `DECIMAL(10, 2)` at the second, whose common type stays `(18, 4)`),
`x IN (c1, c2, c3)`, `x <=> y`, `-x < y`, `abs(x) < y`; and `x < y` as a projected value. Run by
`benchmark.yml` on the EPYC 7763 at 256 and 128 bits with `expected-cpu`, committed beside the
class's files; the projected choice is already there.

Predictions, registered before the build:

1. **The filters at 256 bits on the EPYC 7763** run 3 to 8 times faster than the row path, which
   builds a `BigDecimal` per value: the fused date filter of `VarkaBooleanOutputBenchmark` runs at
   15.2 ns a row against the row path's 50.3
   (`sql/core/benchmarks/VarkaBooleanOutputBenchmark-jdk25-runner-epyc7763-results.txt`), and a
   decimal row costs the row path more.
2. **The rescaled comparison** costs within a quarter of the one-scale comparison on the laptop
   (one `vpmullq`) and within a half on the EPYC 7763 (the emulated multiply), both reading
   sixteen bytes a row.
3. **`abs`**: the lanewise arm beats the blend by less than a tenth on the laptop and is the
   fallback on the EPYC 7763, so the default stays the blend.
4. **The projections**, after 157, 2 to 5 times faster than the row path at 256 bits.
5. **At 128 bits on x86** the kernels are the fallback and lose to the row path as filters, as
   359's prediction 5 says of the pair; the reading goes to 347's question 4 rule.
6. **No existing shape's bytes move**; the `option_arms` digests move with the new flag.

## 5. Order, files and size

1. **The census cells**: `long.ABS`, `long.NEG` and `long.MUL` by a broadcast constant in
   `VarkaOperatorCensusCatalog`, the laptop's column, then a `varka-width-audit.yml` dispatch for
   the EPYC and Neoverse columns.
2. **The baseline addition** (4), its own pull request.
3. **Row 497**, the single `IN` arm, before 348a's `IN` forms; if 497 is late, 348a lands without
   `IN` and `InSet` and a small follow-up adds the reader case.
4. **348a, the conditions:** `Scaled`, `value()` and `rescale`, the unequal-scale comparisons,
   `IN` and `InSet`, the null tests over values, negation, `abs` and the choices under a
   condition, `decimal64.smt2` and the refinement sections, the slot table and edges, the
   predicate coverage rows, the IR fuzzer's sequence.
5. **348b, the values, after 157:** the projections' coverage rows, the `abs` arm's measurement,
   the measurement against the baseline at both widths and 512 bits on the laptop, scored.

| file | what |
|---|---|
| `VarkaDecimalCompiler.java` | `Scaled`, `value()`, `rescale`, the forms of 2.3 to 2.6 |
| `VarkaConditionCompiler.java`, `VarkaTimeCompiler.java` | 497's arm's decimal reader; hand-off |
| `varka/VarkaVectorIR.java`, `varka/VarkaVectorWalk.java` | `IntNeg`'s javadoc; the `abs` arm |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | `decimalAbsLanewise` |
| `sql/varka/proofs/decimal64.smt2`, `VarkaProofFiles.java` | section 3 item 3's lemmas |
| `VarkaRefinementFile`, `spec/STANDARD.md`, `spec/EXTENSIONS.md` | the refinement rows |
| tests | slot table, edges, coverage rows and fixtures, fuzz sequence, census cells |
| benchmark | the baseline addition and its results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `census.tsv`, `census_curated.txt`, `sql/varka/proofs` |

**Size: medium, in three pull requests after the census cells.** The baseline addition half a day;
348a about three days (the proof file and the refinement rows the larger part); 348b one day once
157 has merged. Test time: the slot table's new cases are of the size of 347's, seconds; the proof
file is priced from its spiked form at VARKA-245's observed ratio in the calibration table, under
ten seconds per solver. It rests on 347 (its steps 4 and 6 open, its branches unmerged), 497 for
`IN`, 157 for 348b, and 333 for the three null-branch choices.

## 6. Census unlock

Under the milestone plan's 2.8 and 359's answered question 2 (a), a decimal pair counts fused only
when it fuses at every precision up to 38 and every form a query can give it, unequal scales
included. **So this row moves the fused count by 0 when it lands.** What it gives the count:

- **The 64-bit half of `negative` and `abs`**, two of the four pairs that count when 359 lands.
- **The 64-bit half of the 22 comparison and choice pairs** 359 lists (`=`, `==`, `<`, `<=`, `>`,
  `>=`, `<=>`, `equal_null`, `in`, `in_set`, `between`, `coalesce`, `if`, `when`, `ifnull`, `nvl`,
  `nvl2`, `nullif`, `nullifzero`, `zeroifnull`, `greatest`, `least`), the unequal scales below 19
  among it; `nullif`, `nullifzero` and the `CASE` without `ELSE` behind 333.

The census's notes on those entries are rewritten in 348a to say that the 64-bit form fuses at
every scale and the pair counts with 359, 360 and 363, matching 359a's correction; the `rows`
column of `when`, `ifnull`, `nvl`, `nvl2` and `zeroifnull` gains 348, which the curated file omits
today although the row's "choice" covers them.

## 7. Out of this row, and declines that stay

Out: decimal arithmetic, `MakeDecimal`, `UnscaledValue` and `CheckOverflow` (349); the casts,
integral operands among them (350); the rounding functions (351); every common type above 18
(359, 360, 363); the store (157); `hash` and `xxhash64` (337); a NEON host with no SVE; a
big-endian host. The decimal conformance suite is 359's by its answered question 4 (a), which
governs; 348a, which lands first in F3, renames the registry's `Owed("Decimal", "348")` to name 359,
a one-line change citing that answer.

An integral operand (`i < d9`, `CAST(i AS DECIMAL(10, 0))`) declines naming 350. Rows 350 and 360
both claim the integral-to-decimal casts at 64 bits in their text (350 "to and from integral
types", 360 the widening casts to `(3, 0)`, `(5, 0)`, `(10, 0)` and `(20, 0)`); that overlap is in
the owner's row text and is flagged here, not resolved.

Declines that stay, each naming its row: the table of 2.7; `CASE` without `ELSE`, `nullif` and
`nullifzero` (333); a decimal output root until 157; an `IN` list past 16 literals or with a null
element; at two long lanes on x86, whatever 347's question 4 reading and row 503 decline.

## 8. Questions for the owner

None. The points this plan could not settle from the code and the readings were reported to the
lead on 30 October 2026; any that become the owner's are added here with the answer.

## 9. Done when

Row 348's text holds, with the corrections above: every comparison, `<>`, `<=>`, `IN`, `InSet`,
null test, choice, negation and `abs` over decimals whose common type is within precision 18
fuses at every scale with a coverage row, as a condition in 348a and as an output after 157 in
348b; a common type above 18 and the other shapes of 2.7 decline naming their rows; the slot table
shows 347's precision check declining under every new form, the planted fault failing it;
`decimal64.smt2` proves the rescale, the comparison lemma, negation and `abs` under both solvers,
and each new coverage row has its refinement sections; the bytes oracle is unchanged for every
existing shape; the measurement against the baseline is committed at both widths with its
predictions scored; and the PR build and the option matrix pass.

## 10. Outcome

(Written when the work lands; the readings of the steps built so far are below.)

### 10.1 Step 1: the census cells, on the laptop

*31 October 2026, at `f0c1ab11c45`, before any of the row's code. The laptop column of
`sql/varka/operator_census.json` (AMD Ryzen AI 9 HX PRO 370, JDK 25.0.4.1, species of 512, 256 and
128 bits); the EPYC 7763 and Neoverse N2 columns wait on a `varka-width-audit.yml` dispatch on the
branch.*

**What was built.** Three cells in `VarkaOperatorCensusCatalog`, in the long lanewise family: two
on a new loop, a lanewise unary operator on long arrays, and one on a loop that multiplies a long
column by a vector broadcast once before the loop from an array element, as the emitter loads a
literal slot's broadcast, so that C2 sees neither a literal nor a 32-bit input. The multiply's cell
is named `long.MUL_broadcast`, apart from `long.MUL`, the product of two columns.

| cell | what the loop does | 512 | 256 | 128 | bytes a row |
| :-- | :-- | :-- | :-- | :-- | :-- |
| `long.NEG` | `lanewise(NEG)`, 2.6's negation | I | I | I | 0 |
| `long.ABS` | `lanewise(ABS)`, 2.6's lanewise arm | I | I | I | 0 |
| `long.MUL_broadcast` | `lanewise(MUL, scale)`, 2.2's rescale by `10^k` | I | I | I | 0 |

**What it says.** On the laptop all three are intrinsic and allocation-free at every width, the
128-bit column included: none makes a mask, so the two-lane fallback that the long compares read
there (`NE`, `GT`, `LT` and the four unsigned compares) does not reach them. No existing cell's
verdict moved. The laptop has AVX-512, where `x86.ad` admits `AbsVL` (`evpabsq`) and matches
`MulVL` as one `vpmullq`; item 0.7's reading of the JDK is that the EPYC 7763 has neither, so its
`ABS` cell is expected to read `F` and its multiply `I` as the emulation, which only the runner
columns and the assembly can confirm.

### 10.2 Step 2: the baseline addition

*31 October 2026, on the unchanged code at `f0c1ab11c45`. The runner results at 256 and 128 bits
come from `benchmark.yml` on the EPYC 7763 after the pull request, spliced into the class's
committed files.*

**What was built.** `VarkaDecimalBaselineBenchmark` gains 36 sections after its 52, each with no
nulls and with one row in ten null: section 4's six shapes at `(9, 2)` and `(18, 4)`, and
`VARKA-349.md` 4's three filters and three projections at `(9, 2)`, which that plan lands in this
addition. The table gains `w9`, a `DECIMAL(9, 4)`, `w18`, a `DECIMAL(10, 2)`, and `x8`, a
`DECIMAL(8, 2)`, after its existing columns. `VarkaDecimalBaselineQueriesSuite` checks the new
columns' types, the 88 result types, that the rescaled comparisons' common types are
`DECIMAL(11, 4)`, both sides cast, and `DECIMAL(18, 4)`, only `w18` cast, that 349's three
filters compare at `DECIMAL(10, 2)`, `DECIMAL(12, 4)` and `DECIMAL(18, 4)`, that none of the
eight `IN` filters casts its column, and that no arithmetic operand of a filter over the columns
with no null answers a null, and runs every section on both sessions. 349's projections are its
arithmetic as decimal values (`x + y`, `x - w`, `x8 * y`), the forms its prediction 2 times after
157.

**What the probe found.** Each section's fusion class is set from a plan of it on an
Arrow-cached Varka session. Of the 36, eight fuse whole on the unchanged code: `x <=> y` at both
precisions, which 347's reader compiles at one scale, and the projected `x < y`, through row 27's
boolean output. Eighteen fuse in part: the rescaled comparison, `-x < y`, `abs(x) < y` and 349's
three filters. Spark infers `IS NOT NULL` of every column those filters read, 347's reader fuses the
null tests as a filter of their own, and Spark's `Filter` applies the decimal condition above
it, so those sections' varka on arm times a null-test kernel under the row path's condition, and
the spark row path arm is their baseline. The four `IN` filters infer no null test and the six
projections write a decimal, so those ten run on the row path. Section 4's sentence that the
baseline is "on the unchanged code" holds for the varka off arm only.

**The three-valued fuse check.** A section counted as fused when its plan held any Varka node,
so the eighteen above would have read as fused and 348a's fusing of the condition itself would
have moved no flag. `VarkaSqlBenchmarkBase` now has a `Fusion` class per section: `NONE`, no
Varka node; `NULL_TESTS`, Varka filters holding only `IS NOT NULL` and `IS NULL` conjuncts while
a row `FilterExec`, or a `ProjectExec` that computes, evaluates the rest; and `WHOLE`, a Varka
node and no row operator evaluating any of the section. A plan that is none of the three, Varka
fusing more than null tests under a row operator that still computes, has no class and fails
the check whatever the section expects. The decimal class states a class for each of its 88
sections, from the probe's plans: 12 `WHOLE` (the four equal-scale comparisons at precisions 9
and 18 that row 347 fused, and the eight above), 18 `NULL_TESTS` and 58 `NONE`. The run fails
when a plan's class differs from the section's, so 348a moves the eighteen to `WHOLE` as it
fuses them, and the results file notes the class of each section that fuses.

**The fuse check after review.** A row operator is now every operator outside Varka except a
leaf (the cached scan), an exchange, a transition between columns and rows, the whole-stage
codegen node and its input adapter, and a `ProjectExec` that only passes columns through or
renames them; so an aggregate, a sort, a window, an expand, a generate or a join above a Varka
node takes a plan out of `WHOLE`, and an operator nobody listed fails the check rather than
reading as fused. A fourth class, `PARTIAL`, is a plan with no row operator but a Varka
projection that leaves an entry to its per-row fallback: the check compiles the projection again
as the node does, since the node keeps its classification private, and finds the entries its
kernel declined. No baseline section is `PARTIAL` today. A batch the kernel cannot serve and
hands to the row path is decided when the plan runs, so no plan class shows it. The floating,
floating arithmetic and both `TIMESTAMP_NTZ` baselines now state a class per section too, the
boolean overload is gone, and a probe on an Arrow-cached Varka session found each of their
sections where its flag had it: the sections flagged as fusing are `WHOLE` and the rest `NONE`,
so none of them hid a null-test fusion. `VarkaDecimalBaselineQueriesSuite` asserts each
section's class in every build under the emitter's default options.

**The fuse check after the second review.** It corrects four points of the paragraph above. The
check no longer compiles a Varka projection again: the node's own classification, the compile that
serves its EXPLAIN, is visible to `sql` and read in place, so the check records no compile under
the compile observer. A Varka projection that only forwards or renames columns, which the rule's
pre stage plans over a Varka filter whose output it narrows, compiles to no kernel and now counts
as computing nothing, where its empty classification had read as `PARTIAL`. Under adaptive
execution the plan's wrappers are leaves and the columnar rules run as each stage is created, so
the search had read every adaptive plan as `NONE`; it now reads an `AdaptiveSparkPlanExec` through
its final plan and a query stage through its plan, and exempts adaptive execution's shuffle read.
And a row operator decides first: with one, a plan is `NULL_TESTS` only when every Varka node is a
null-test filter, so a Varka projection beside a row operator gives no class, never `PARTIAL`. The
floating, floating arithmetic and `TIMESTAMP_NTZ` suites now assert each section's class in every
build too, under `PinsDefaults` as the decimal and `TIMESTAMP_NTZ` arithmetic suites do, and every
listed class matched its plan.

**Two more baselines, from master.** The floating cast and integral cast baselines (VARKA-343,
VARKA-332) came in with merges of master still stating a boolean flag per section, which the
removed overload no longer took, so the branch's `sql` tests did not compile after the first of
those merges. Both now state a class per section, every one `NONE` but the integral cast
baseline's control, `WHOLE`, and their suites assert each section's class under `PinsDefaults` as
the other five do; every listed class matched its plan. The merge of VARKA-339 also made the
arithmetic chain of both `TIMESTAMP_NTZ` baselines and the arithmetic baseline's interval column
sum fused sections, and their class is `WHOLE`, which their suites' checks confirm.

**The fuse check after the third review.** A Varka projection that computes but has an empty
classification, which the rule never plans, now gives a plan no class rather than `PARTIAL`, a
class a section could list. The search reads each operator's subqueries and reads a reused
exchange or subquery through what it reuses, and a limit, a union and a coalesce now only move
rows, as the list of exempt operators always meant; a range stays an exempt leaf, a source both
arms read alike, and `TakeOrderedAndProjectExec`, which sorts, stays row work. The
`TIMESTAMP_NTZ` arithmetic baseline holds only its `WHOLE` sections to the batch check of
`VarkaSizeLadder.varkaFused`, which finds a Varka projection and its metrics; every class still
matched its plan.
