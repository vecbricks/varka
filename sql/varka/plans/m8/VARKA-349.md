# VARKA-349: The 64-bit decimal arithmetic, `CheckOverflow`, `MakeDecimal` and `UnscaledValue`

## 0. What exists now (admission check)

From the same code as `VARKA-348.md` 0: master at `ef32e007d3a` for Spark's sources and the plans,
row 347's local branches for the Varka code it builds on (line numbers from `varka-347-reader` at
`95e8e7fba90`), the JDK in `/home/max/proj/openjdk-build/jdk25` (30 October 2026). The row's text
is the milestone plan's 2.8 ("Semantics, ANSI and LEGACY") and its 4.1. This row and 348 share one
design of the 64-bit decimal value, which `VARKA-348.md` 2 states; this plan builds on it and does
not restate it.

1. **Spark computes the result type in the node, and never casts the operands.**
   `BinaryArithmetic` accepts two decimals of any precisions and scales (`expressions/
   arithmetic.scala:231-236`) and takes its type from `resultDecimalType` (`:218-222`):
   - `Add` and `Subtract`: scale `S = max(s1, s2)`, precision `max(p1 - s1, p2 - s2) + S + 1`
     (`:443-450`, `:541-548`);
   - `Multiply`: scale `s1 + s2`, precision `p1 + p2 + 1` (`:630-637`);
   each through `adjustPrecisionScale` under `allowPrecisionLoss` and `bounded` otherwise, both the
   identity at precision 38 or less (`DecimalType.scala:144-146`, `:175-200`). Type coercion casts
   only an integral or floating operand (`analysis/DecimalPrecisionTypeCoercion.scala:150-188`), and
   `PromotePrecision` no longer exists (the milestone plan's 2.1). So `d9 + d9s4` reaches the
   compiler as an `Add` of `DECIMAL(9, 2)` and `DECIMAL(9, 4)`, typed `DECIMAL(12, 4)`.
2. **The three modes, as the code has them.** The generated code is
   `changePrecisionExact(e1.$plus(e2), p, s)` under ANSI and TRY and `changePrecisionOrNull` under
   LEGACY (`arithmetic.scala:294-310`; `failOnError` is true for ANSI and TRY, `:224-229`), and TRY
   wraps it in a `catch` that sets the result null (`:359-371`; interpreted, `:384-391`).
   `try_add`, `try_subtract` and `try_multiply` over two numerics are the same nodes in `TRY`
   (`expressions/TryEval.scala:94`, `:233`, `:272`). So LEGACY nulls, ANSI raises
   `NUMERIC_VALUE_OUT_OF_RANGE`, and TRY nulls, each only where the exact result has more than
   `p - s` integral digits.
3. **The arithmetic is exact before that check.** `Decimal.+` and `-` add the long fields when both
   values are compact and of one scale, and `BigDecimal`s otherwise (`sql/api/.../types/
   Decimal.scala:506-520`); `*` multiplies `BigDecimal`s under `MathContext(39, DOWN)`
   (`:523-524`, `:597`), exact for a product of 38 digits or fewer. Values read through
   `ArrowColumnVector` are `BigDecimal`-backed (`VARKA-347.md` 0, item 3), so the row path takes
   the `BigDecimal` branch; the answer is the exact value either way.
4. **Within precision 18 no mode can differ.** With the result precision `P <= 18` the exact
   result fits the type, so `changePrecision` succeeds (`Decimal.scala:458-471`), LEGACY's null,
   ANSI's raise and TRY's catch are dead, and the three modes answer the same: for `+` and `-` each
   operand rescaled to `S` has at most `P - 1` digits and their sum or difference at most `P`; for
   `*` the product of values below `10^p1` and `10^p2` is below `10^(p1 + p2) = 10^(P - 1)`. This is
   the milestone plan's "for the 64-bit form with a result within precision 18 that cannot happen".
   It is **not** true of `MakeDecimal` and `CheckOverflow` (items 5, 6), which can overflow at any
   precision below 19.
5. **`UnscaledValue` and `MakeDecimal`** (`expressions/decimalExpressions.scala:35-48`, `:56-100`).
   `UnscaledValue` is `toUnscaledLong` (`Decimal.scala:225-231`), `longValueExact` of a
   `BigDecimal`-backed value, exact for a precision of 18 or less. `MakeDecimal(c, p, s,
   nullOnOverflow)`, `nullOnOverflow` defaulting to `!ansiEnabled` (`:62-64`), is
   `setOrNull(c, p, s)` or `set(c, p, s)` (`:71-79`): for `p <= 18`, null or
   `NUMERIC_VALUE_OUT_OF_RANGE` where `|c| >= 10^p` (`Decimal.scala:94-115`, `:83-88`); for
   `p >= 19`, never, since every long is below `10^19`. It is a range guard with a disposal, not a
   relabel as the census note has it.
6. **`CheckOverflow(x, (p, s), nullOnOverflow)`** is `toPrecision(p, s, ROUND_HALF_UP,
   nullOnOverflow)` (`decimalExpressions.scala:115-150`): a rescale up or a `HALF_UP` rescale down,
   then the precision check, null or raise. Its rescale up is where Trino's `shortToShortCast`
   wraps (`READING_TRINO_DECIMAL.md` 7): `184467440737095517` at `(18, 0)` times 100 wraps to 84,
   which passes a check of `10^18`.
7. **Where the three are built, and where they run.** `UnscaledValue` and `MakeDecimal` are built
   only by `DecimalAggregates` (`optimizer/Optimizer.scala:3200-3270`): a `Sum` over a decimal of
   precision `p` with `p + 10 <= 18` becomes `MakeDecimal(Sum(UnscaledValue(e)), p + 10, s)`
   (`:3250-3252`), a `Sum` over a scale-keeping widening cast `MakeDecimal(...,
   bounded(p' + 10, s))`, up to precision 38 (`:3243-3248`), the window arm the same over a
   `WindowExpression` (`:3221-3224`), and the `Average` arms `UnscaledValue` under a `Divide` by a
   double and a cast (`:3226-3232`, `:3261-3273`), 350's double cast. `CheckOverflow` is built
   only by `SerializerBuildHelper` (`SerializerBuildHelper.scala:210-228`), for a Dataset encoder
   of a `BigDecimal`. So `UnscaledValue` is evaluated inside an aggregate function's input, by the
   aggregate operator; `MakeDecimal` over an `AggregateExpression` in the aggregate's result
   expressions, and over a `WindowExpression` in `WindowExec`'s result projection
   (sql/core `window/WindowEvaluatorFactoryBase.scala:72-82`); `CheckOverflow` in
   `SerializeFromObject`'s serializer. Varka replaces only `ProjectExec` and `FilterExec`
   (`VarkaProjectExec`, `VarkaFilterExec`). **[V] for every construction site in
   `sql/catalyst`, `sql/core` and `sql/api` main sources; [I] that no plan moves one into a
   `Project` or `Filter`**: step 1 checks it with one optimized and physical plan print per site.
8. **The IR has the arithmetic, the compiler does not.** `IntArith` in `WRAP` at the long lane is
   emitted lane-generically (`VARKA-104.md` 2.1; `varka/VarkaVectorWalk.java:746-768`), refusing
   only a checked multiply off the narrow lanes (`:768`); the compiler's arithmetic arms are the int
   and narrow ones (`VarkaNodeCompiler.java:482-498`), row 104's long arms being planned. A decimal
   reaches none of them, since `isInt` and `isNarrow` read the lane of a non-decimal type.
   `GuardedRange` (`varka/VarkaVectorIR.java:587`) declines a batch. Its `NULL` mode, which
   clears a live lane's validity, is row 481's: its plan (branch `varka-481`, section 3.1) gives
   the node a mode, `FAIL` or `NULL`, rendered in the shape key, with the reference evaluator's
   arm and the fuzz grammar's draw. Not built yet. Row 343's plan describes the same disposal for
   its floating guard (`VARKA-343.md` 2.4); the two should be one mode.
9. **The JDK's long multiply.** `vpmullq` with AVX-512DQ; below it the seven-instruction
   emulation (`cpu/x86/x86.ad:6071-6093`), unless C2 sees both inputs fit 32 bits, in which case
   one `vpmuldq` (`vmuldq_reg`, `:6108-6117`): an input is seen to fit when it is an `I2L`
   conversion or an arithmetic right shift by 32 or more (`share/opto/vectornode.cpp:2213-2232`).
   A decimal of precision 9 or less has `|u| <= 10^9 - 1 < 2^31`, so `(u << 32) >> 32` is `u` and
   shows the pattern; 347's int-lane arm (`decimalIntLane`) gives the `I2L` form for free.
10. **The baseline does not reach this row.** `VarkaDecimalBaselineBenchmark`'s arithmetic chain
    is `(x + y) * z - x`: at `(9, 2)` the sum is `(10, 2)` and the product `(20, 4)`, at `(18, 4)`
    the sum is already `(19, 4)`, so both are 360 and 361's shapes. At `(18, 4)` the row path
    pays 388.8 ns a row for it
    (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt`).

### What differs from the row text and the milestone plan

- **"Every operand pair whose result stays within precision 18" is a formula, and a narrow one.**
  `+` and `-` fuse when `max(p1 - s1, p2 - s2) + max(s1, s2) + 1 <= 18`, `*` when
  `p1 + p2 + 1 <= 18`. So a `DECIMAL(18, s)` column never fuses in this row's `+` or `-`, and
  `DECIMAL(9, 2) * DECIMAL(9, 2)`, `(19, 4)`, is 361's.
- **`CheckOverflow` is dead for this row's arithmetic** (item 4), so the row text's "with
  `CheckOverflow`" is two things: the proof that the arithmetic's check never fires, and the
  `CheckOverflow` node itself, a guarded rescale (item 6). The `MakeDecimal` guard is real too
  (item 5).
- **The three internals may reach no fused node** (item 7). If step 1 confirms it, what fuses them
  is a question for the owner (section 8). *1 November 2026, by the owner's word (section 8,
  question 1 (b)):* step 1 confirmed it, and the three are out of the census and not built by this
  row.
- **The arithmetic is mode-free; the internals are not.** "LEGACY and ANSI giving the same bytes"
  holds for `+`, `-`, `*` and their `try_` forms. `MakeDecimal` and `CheckOverflow` carry their own
  `nullOnOverflow`, so their two dispositions are two kernels.

## 1. Goal and non-goal

Decimal `+`, `-` and `*` of two operands of precision 18 or less whose result type is within
precision 18 fuse on the long lane, in every mode and with their `try_` forms, as one kernel per
shape whatever the mode, the coarser operand rescaled by 348's constant multiply, the no-overflow
claim and the rescales proven by SMT over every precision pair; and `MakeDecimal`, `UnscaledValue`
and `CheckOverflow` fuse within precision 18 with their guards, as section 8's answer decides. Not
this row: a result above precision 18 (360 for `+` and `-`, 361 and 362 for `*`), a rescale down
(350's casts and 363), division (out of the milestone), `%`, `mod` and `pmod`, the interval
products and quotients, an integral operand's cast (350), the store (157).

*1 November 2026, by the owner's word (section 8, question 1 (b)):* `MakeDecimal`, `UnscaledValue`
and `CheckOverflow` are not this row's goal any more. The census records them out with the reason
"evaluated by an operator Varka does not fuse" (`HashAggregateExec`, `WindowExec`,
`SerializeFromObjectExec`), and none is built; `CheckOverflow`'s guard-the-inputs form (2.5) stays
specified for rows 350 and 360. The row is the arithmetic, 349a and 349c.

## 2. Design

### 2.1 The value, from 348

Every operand is a `Scaled(unscaled, type)` from `VarkaDecimalCompiler.value()` (`VARKA-348.md`
2.1), whose invariant `|u| <= 10^p - 1` this row's nodes keep, so a sum can be compared, chosen,
negated or tested for null by 348's forms with no change to them, and one of 348's values can be an
operand here. The rescale is 348's `rescale(x, k)` (`VARKA-348.md` 2.2) with its defence,
guard-the-inputs by the operand's bound.

### 2.2 `+` and `-`

    S = max(s1, s2);   P = max(p1 - s1, p2 - s2) + S + 1   (Spark's resultDecimalType)
    fuse when P <= 18:  IntArith(ADD | SUB, WRAP, rescale(x, S - s1), rescale(y, S - s2))

in `WRAP` in every mode, typed `(P, S)`. The bound: each rescaled operand has at most `P - 1`
digits, so `|a| + |b| <= 2 * (10^(P - 1) - 1) < 10^P - 1 < 2^63`. A literal operand is rescaled at
compile time (348's rule). `try_add`, `try_subtract` and the `Add`, `Subtract` in each `EvalMode`
are this one node; the mode is read and not used, which section 3 item 3 holds. Decimal
arithmetic runs outside the family switches, as 348's negation and `abs` do, while a decimal
`greatest` or `least` stays under the int arithmetic's switch and `IF`, `CASE` and `COALESCE`
under the condition family's.

### 2.3 `*`

    P = p1 + p2 + 1;   S = s1 + s2
    fuse when P <= 18:  IntArith(MUL, WRAP, x, y)

with no rescale, since the scales add: the unscaled product at scale `s1 + s2` is the product's
unscaled value. The bound is `|x * y| <= (10^p1 - 1)(10^p2 - 1) < 10^17`. The multiply is the
host's `MulVL` (item 0.9). **An arm, `decimalMulIntInputs`** (`Flag`, `Reason.MACHINE`, off by
default): where both operands have precision 9 or less, each is written as
`(x << 32) >> 32` before the multiply, the identity on a value below `2^31` in magnitude, so that C2
can select one `vpmuldq` in place of the emulation. Whether GVN keeps the two shifts, and so
whether the pattern survives, is read by `dev/varka_emit.sh --asm` on the AVX2 runner before the arm
is measured; if C2 folds them away the arm is dropped and the reading recorded. On a host with
AVX-512DQ the arm can only lose two shifts, and stays off there.

### 2.4 `UnscaledValue` and `MakeDecimal`

- **`UnscaledValue(x)`** with `x` of precision 18 or less is `x.unscaled` as a `BIGINT` value, no
  node; above 18 it declines naming 359 (Spark itself never builds one there, item 0.7).
- **`MakeDecimal(c, p, s, nullOnOverflow)`** over a `BIGINT` value `c`: for `p <= 18`,
  `GuardedRange(c, -(10^p - 1), 10^p - 1, d)` typed `(p, s)`, with the disposal `d` `NULL` where
  `nullOnOverflow` and `FAIL` where not, row 481's mode; for `p >= 19`, `c` itself typed `(p, s)`,
  no guard (item 0.5). The second writes the bytes of row 157's `WidenDecimal` store, a long and its
  sign extension, but 157's plan declines a decimal output above precision 18, naming 359; so it
  declines naming 359 unless 157 admits an output above 18 whose value is a long. As an operand of a
  wider operator it is 359's sign extension (`VARKA-359.md` 2.2). This row does not build the
  `NULL` mode; 349b waits for 481.

*1 November 2026, by the owner's word (section 8, question 1 (b)):* neither form is built and 349b
is dropped, so question 2, `MakeDecimal` above precision 18, lapses. The text above stays as the
design a later milestone that fuses an aggregate or a window would start from.

### 2.5 `CheckOverflow`

`CheckOverflow(x, (p, s), nullOnOverflow)` with `k = s - x.type.scale()`:

- **`k >= 0` and `p <= 18`**: guard-the-inputs, exactly: `GuardedRange(x, -(10^(p - k) - 1),
  10^(p - k) - 1, d)`, then `rescale(x, k)`, typed `(p, s)`. Since `|x * 10^k| <= 10^p - 1` holds
  exactly when `|x| <= floor((10^p - 1) / 10^k) = 10^(p - k) - 1`, the guard is Spark's precision
  check moved before the multiply, and the multiply only computes a value that fits. Where
  `p - k <= 0` only zero passes. A lane the `NULL` disposal clears still runs the multiply, on a
  value whose result nothing reads; the `FAIL` disposal declines the batch first.
- **`k < 0`** rounds `HALF_UP`, and declines naming 350, whose rescale down this is.
- **`p > 18`** declines naming 359 and 360.

The order matters and is tested: Trino's fixture, `184467440737095517` at `(18, 0)` checked into
`(18, 2)`, must be null under `nullOnOverflow` and raise without it. The planted fault for row 504
is the multiply-then-check order (the rescale first, then the guard at `10^p - 1`) under a test
flag, refuted by the proof and failed by the slot table and the differential.

*1 November 2026, by the owner's word (section 8, question 1 (b)):* this row does not build
`CheckOverflow`. The form above, its order, Trino's fixture and the planted fault stay specified
here for rows 350 and 360, which need the same rescale guard; whichever builds the guard takes them
with it.

### 2.6 Where the 64-bit form stops

| shape | declines naming |
|---|---|
| `+`, `-` with `max(p1 - s1, p2 - s2) + max(s1, s2) + 1` from 19 to 38 | 360 |
| `+`, `-` above 38, capped (`adjustPrecisionScale`) | 360 and 363 |
| `*` with `p1 + p2 + 1` from 19 to 38 and both operands within 18 digits | 361 |
| `*` above 38, or an operand above 18 digits | 362 and 363 |
| an operand above 18 digits in `+` or `-` | 359 and 360 |
| `CheckOverflow` to a smaller scale | 350 |
| `CheckOverflow` above precision 18 | 359 and 360 |
| an integral operand (`CAST(i AS DECIMAL(10, 0))`) | 350; a `BIGINT`'s `(20, 0)` widens the node past 18 digits, which names the wide operand's rows above |
| a cast, a negation, a choice or a pick over a sum or product above 18 digits | the node's row (360 or 361 and above), or only 359 and 360 where a rescale into the wide type is needed, that reason winning over the arithmetic rows (`IF(p, a * b, x)` with `a`, `b` at `(9, 2)` and `x` at `(18, 2)` rescales `x` into `(20, 4)` and names no 361); over several wide values, the union of their rows in ascending order (a wide value with no arithmetic under it names 359, so `IF(a > b, x + y, x)` names 359 and 360, as `COALESCE(d20s2, x + y)` does), with 350 for a cast that lowers the scale or the integral digits, beside 359 where nothing arithmetic is under it (`CAST(d20 AS DECIMAL(19, 1))`) |
| `/`, `div`, `try_divide` over decimals | the owner's decision of 14 October 2026 |

A cast that lowers the scale or the integral digits over a wide value is owed a home: row 364's
plan claims every rescale down into a wider type and every cast from 128 bits down to 64
(`VARKA-364.md` 0), so row 364 decides between rows 350 and 364, and the decline text names 350
until it does.

### 2.7 At 128 bits, the compiler, the reference

At two long lanes on x86 the rule is 348's (`VARKA-348.md` 2.8). The decimal arms go in
`VarkaDecimalCompiler`, reached from `VarkaNodeCompiler`'s `Add`, `Subtract` and `Multiply` ahead
of the int arms, beside which row 104 adds the `BIGINT` arms (adjacent switch arms; whichever merges
second rebases), and from new arms for the three internals (none, since the owner's answer of
1 November 2026, section 8). The reference evaluator has `IntArith`
and `GuardedRange`, and gets the `NULL` mode with 481. The cost model and the budget
need nothing new; the warm-up's long block gains the multiply's forms if `VarkaWarmupReachSuite`
finds them missing.

## 3. Tests and proofs

1. **The type rule against Spark's own.** A unit test runs Spark's `resultDecimalType` of `Add`,
   `Subtract` and `Multiply` over every `(p1, s1, p2, s2)` with `1 <= p <= 18`, `0 <= s <= p`, and
   holds the compiler's fuse-or-decline decision to `P <= 18` for each, its reason naming the row
   of 2.6; a sample of each class is compiled and run. The function is cheap and the sweep
   complete; it is what ties the SMT lemmas below to every pair.
2. **The edges**, against `BigDecimal`: `DECIMAL(17, 2) + DECIMAL(17, 2)` and `-` at
   `+-(10^17 - 1)` in every sign pair (`P = 18`); `DECIMAL(9, 2) + DECIMAL(16, 9)` (a rescale of 7,
   `P = 17`); `DECIMAL(8, 2) * DECIMAL(9, 2)` at `+-(10^8 - 1)` and `+-(10^9 - 1)` (`P = 18`);
   `DECIMAL(9, 2) * DECIMAL(9, 2)`, which declines naming 361; a literal operand at each side.
3. **One kernel in every mode.** For each `+`, `-`, `*` and `try_` coverage row,
   `VarkaEmittedBytesSuite` compiles the expression under LEGACY, ANSI and TRY (and both values of
   `allowPrecisionLoss`) and asserts one shape hash and identical bytes: the row text's "LEGACY and
   ANSI giving the same bytes".
4. **The guards' tables** (`VarkaDecimalLoadSuite`'s internals half): `MakeDecimal` at `p` = 1, 9,
   17 and 18 with `+-(10^p - 1)` passing and `+-10^p` and the long's extremes null under
   `nullOnOverflow` and declining with `STATUS_VALUE_RANGE` without it; `CheckOverflow` at `k` = 0,
   1, 2 and 9 the same at `10^(p - k) - 1` and one past, and Trino's fixture; every case valid and
   then null, in the first group, a middle one and the last row, both bodies.
5. **The proofs**, in 348's `sql/varka/proofs/decimal64.smt2`, rendered by `VarkaProofFiles` from
   the code's constants, over integers, linear:
   - `+` and `-`: for every result precision `P` from 2 to 18, operands `|a|, |b| <= 10^(P - 1) - 1`
     give `ladd(a, b) = a + b` and `lsub(a, b) = a - b` with `|a +- b| <= 10^P - 1`, `unsat`; with
     the operands one digit wider the wrap or the overflow has a witness, `sat`. Test item 1's
     sweep checks that every pair's rescaled operands have at most `P - 1` digits, which with
     348's rescale lemma covers every precision pair, the reduction "every site fed only inputs
     its proof covers" the refinement file already uses for the calendar.
   - `*`, in 2.8's two layers: the ring layer is `value_range.smt2`'s symmetric multiply lemma
     over the reals (`|x| <= m1` and `|y| <= m2` give `|x * y| <= m1 * m2`), instantiated per
     `p1 + p2` from 2 to 17 at `m = 10^p - 1`; the wrap layer takes the product as a free variable
     `z` with `|z| <= (10^p1 - 1)(10^p2 - 1)` and proves `lmul`'s reduction the identity on it, so
     no query multiplies two unknowns.
   - `MakeDecimal`: per `p` from 1 to 18, the guard's interval is exactly `setOrNull`'s accepted
     set. `CheckOverflow`: per `(p, k)`, `|x| <= 10^(p - k) - 1` exactly when
     `|x * 10^k| <= 10^p - 1`, over every `|x| <= 10^18 - 1`; and the multiply-then-check order
     has a witness, Trino's value at `(18, 2)`, `sat`.
   The coverage rows' five `refinement.smt2` checks follow in Spark mode through
   `VarkaRefinementFile` (row 490), the mode's three dispositions among them: check 2 a decline
   where ANSI raises (`MakeDecimal`, `CheckOverflow`), check 3 the validity where LEGACY and TRY
   null. `spec/STANDARD.md` 4.1's decimal sentence (348's) gives `+`, `-` and `*` their result
   scales from SQL-92 6.12 and the exact results; `EXTENSIONS.md` gains the three internals as
   Spark's. Row 506's printer is not this row's prerequisite.
6. **Coverage rows**, a "DECIMAL arithmetic" family over 348's fixtures and one more, `d8`
   (`DECIMAL(8, 2)`): predicates `d9 + d9b < d18`, `d9 - d9s4 > 0`, `d8 * d9 <= d18`,
   `try_multiply(d8, d9) IS NULL`, `-(d9 + d9b) = d9s4`; after 157, projections `d9 + d9b`,
   `d9 - d9s4`, `d9 + d16s9`, `d8 * d9`, `d9 * 3`, `try_add(d9, d9b)`. The internals, as section
   8 answers: `UnscaledValue(d9)`, `MakeDecimal(l, 18, 2)` in both dispositions,
   `MakeDecimal(l, 38, 2)` as 2.4 says, `CheckOverflow(d9, (5, 2))` in both and
   `CheckOverflow(d9, (11, 4))`, as `built` rows, since no SQL spells them (the `InSet` rows'
   precedent).
7. **The differential, the matrix, the fuzzers**: the coverage differential, the golden suite's
   Varka arm and the option matrix (where `decimalMulIntInputs` gets its configuration); the IR
   fuzzer's decimal sequence (348's) gains the three operators and the two guards.
8. **The generated files** in `ADDING_A_TYPE.md`'s order, no existing shape's bytes moving.

*1 November 2026, by the owner's word (section 8, question 1 (b)):* the internals' parts of this
section are not built by this row: item 4's guards' tables, item 5's `MakeDecimal` and
`CheckOverflow` lemmas and the refinement check 2 declines they bring, `EXTENSIONS.md`'s three
internals, item 6's `built` rows and item 7's two guards in the IR fuzzer. `CheckOverflow`'s table,
lemma, fixture and planted fault go with its form to rows 350 and 360 (2.5).

## 4. The baseline, and predictions

**The baseline first, on the unchanged code, in the same pull request as 348's baseline
addition**, since both add sections to row 377's class: at `(9, 2)`, as filters, `x + y < z`,
`x - w > 0` (`w` a `DECIMAL(9, 4)`) and `x8 * y < z` (`x8` a `DECIMAL(8, 2)`, the product
`(18, 4)`), and the same three as projections. `benchmark.yml` on the EPYC 7763 at 256 and 128
bits. No section for the internals unless section 8 has them fuse in a SQL plan.

Predictions, registered before the build:

1. **The filters at 256 bits on the EPYC 7763** run 3 to 10 times faster than the row path, which
   allocates a `BigDecimal` per operand and per result.
2. **The projections, after 157**, 2 to 6 times faster.
3. **The multiply**: on the EPYC 7763 the emulated `MulVL` makes `x8 * y < z` slower than
   `x + y < z` by up to a half; the `decimalMulIntInputs` arm wins that back in part where C2 keeps
   the pattern; on the laptop the arm loses by its shifts.
4. **One kernel per shape in every mode**, so the LEGACY, ANSI and TRY timings of one shape are one
   measurement.
5. **At 128 bits on x86**, as 348's prediction 5.
6. **No existing shape's bytes move**; the `option_arms` digests move with the new flag.

## 5. Order, files and size

1. **The reachability reading** (item 0.7): the optimized and physical plans of `sum(d8)`,
   `sum(CAST(d8 AS DECIMAL(30, 2)))`, `avg(d8)`, `sum(d8) OVER (...)` and a Dataset of
   `BigDecimal`, printed on the laptop under a heavy slot, recorded in section 10 with the node that
   evaluates each internal; then section 8 goes to the owner.
2. **The baseline addition** (4), with 348's.
3. **349a, the arithmetic:** the arms of 2.2 and 2.3, the type rule and its sweep, the edges, the
   one-kernel test, the `+`, `-` and `*` lemmas and refinement rows, the predicate coverage rows.
4. **349b, the internals**, after row 481's `NULL` mode, as section 8 answers. *Dropped on
   1 November 2026 by the owner's answer to section 8, question 1 (b).*
5. **349c, after 157:** the projections' rows, the `decimalMulIntInputs` reading and measurement,
   the measurement against the baseline at both widths and 512 bits on the laptop, scored.

| file | what |
|---|---|
| `VarkaDecimalCompiler.java`, `VarkaNodeCompiler.java` | the arms of 2.2 to 2.5 |
| `varka/VarkaVectorWalk.java` | the `decimalMulIntInputs` arm's shifts |
| `varka/VarkaEmitOption.java`, `varka/VarkaEmitOptions.java` | `decimalMulIntInputs` |
| `sql/varka/proofs/decimal64.smt2`, `VarkaProofFiles.java` | section 3 item 5's lemmas |
| `VarkaRefinementFile`, `spec/STANDARD.md`, `spec/EXTENSIONS.md` | the refinement rows |
| tests | the type sweep, edges, one-kernel test, guards' tables, coverage rows, fuzz forms |
| benchmark | the baseline sections and their results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `census.tsv`, `census_curated.txt`, `sql/varka/proofs` |

**Size: medium, in three pull requests after the reading.** The reading an hour; 349a about three
days, the proofs the larger part; 349b one or two, after 481; 349c one after 157. Test time:
the type sweep is a pure function over about thirty thousand pairs, under a second; the lemmas add
some sixty linear checks to `decimal64.smt2`, priced at VARKA-245's observed ratio in the
calibration table. It rests on 347, on 348 (its `value()` and `decimal64.smt2`), 157 for 349c and
481 for the `NULL` mode.

*1 November 2026, by the owner's word (section 8, question 1 (b)):* with 349b dropped the row is
medium in two pull requests after the reading, 349a about three days and 349c one after 157; the
file table's "the arms of 2.2 to 2.5" is the arms of 2.2 and 2.3, and the row no longer rests on
481, whose `NULL` mode only the internals used.

349a lands in pull requests of under 1000 changed lines each, counted by
`dev/varka_msg_check.py size` against the part's base over hand-written source, main and tests,
plan text and hand-curated data such as `census_curated.txt`; the generated files
(`coverage.json`, `emitted_bytes.json`, `width_audit.json`, `census.tsv`, the rendered `.smt2`
proofs, the generated blocks of `docs/sql-varka.md`) are not counted (*3 November 2026, the
owner's word*: this replaces 349a as the single pull request #1305). Each part is cut from
#1305's tree merged with 348a's, not by replaying its commits, and carries its own tests and its
own regenerated files.

| part | what | depends on | lines |
|---|---|---|---|
| 349a1 | the arms of 2.2 and 2.3 in `VarkaDecimalCompiler`, `try_` forms read through their replacement, the declines naming rows 359 to 363, the null test over a sum, a difference and a product; `VarkaDecimalArithmeticSuite`'s type-rule sweep, edges, samples and one tree in every mode; the baseline's three filters fusing whole; the census notes; this table | 348a3 | 999 |
| 349a2 | the sweep's check of the lemmas' reduction from the IR; the `+`, `-` and `*` lemmas in `decimal64.smt2`; the "DECIMAL arithmetic" coverage family over a new `d8`, each row refined in `refinement.smt2`, and `STANDARD.md`'s sentence; the one-kernel bytes test; the IR fuzzer's sum, difference and product; the outcome | 349a1 | 756 |

349c, the values, still follows 157. A part over the cap is split again before it is pushed,
never merged over it.

## 6. Census unlock

Under the milestone plan's 2.8 and 359's answered question 2 (a), an arithmetic pair counts only
when it fuses at every precision to 38. So `+`, `-`, `*` and their three `try_` forms move nothing
when this row lands; they count with 360, 361 and 362. `check_overflow` counts with 359, 360 and
363. `unscaled_value`, whose input Spark only builds at precision 8 or less, is the one pair this
row could count alone; `make_decimal`'s output reaches precision 38, past what 157's store admits,
so it counts with 359's store or with 157 admitting a long-valued output above 18. Whether either
counts is section 8's question. The census's `make_decimal` note, "a relabel of an unscaled long",
is corrected to name its guard. Seven more entries name 349 in their `rows` column - `%`, `mod`,
`pmod` and the four interval products and quotients by a decimal - and are not this row's work;
349a marks them unclaimed with a suggested home (section 7).

*1 November 2026, by the owner's word (section 8, question 1 (b)):* none of the three counts. They
are recorded out of the census in `census_curated.txt`'s `[implicit excluded]` section with the
reason "evaluated by an operator Varka does not fuse (HashAggregateExec, WindowExec,
SerializeFromObjectExec)", which `dev/varka_census.py` writes into `census.tsv`'s reason column, as
a constant folded before Varka sees it is out; the in-scope count falls from 183 entries to 180 and
the entry-and-type pairs from 881 to 878. The `make_decimal` note's correction lapses with the
entry, and `check_overflow`'s claim by 359 and 363 leaves the census with it.

## 7. Out of this row, and declines that stay

Out: the shapes of 2.6's table; the casts (350); the store (157). The row is built to its text,
`+`, `-` and `*` with the three internals; the census entries that name 349 beyond it are not
this row's work, and 349a marks each unclaimed in the census register (`census_curated.txt`'s
`rows` column loses 349 and the note names the suggested home), for the owner to place:

- decimal `%`, `mod`, `pmod` and `try_mod`: a remainder by a variable divisor, for which the long
  lane has no vector division; suggested home row 30.
- `MultiplyYMInterval` and `MultiplyDTInterval` by a decimal: the count times `u`, then `HALF_UP` by
  `10^s` (`expressions/intervalExpressions.scala:651-652`, `:700-702`), a product past 64 bits and a
  rounding division; suggested home rows 361 and 363.
- `DivideYMInterval` and `DivideDTInterval` by a decimal: the count over the decimal, rounded
  `HALF_UP` (`:791-793`), a division by a variable; row 363 claims them and declines them
  until a division by a variable is planned, since its division by `10^k` does not serve them.
- `try_eval` over a decimal subtree: it catches what the subtree raises, and 349's own nodes raise
  only in `MakeDecimal` and `CheckOverflow`; suggested home the rows whose decimal nodes raise
  (350).

An integral operand (`d9 + i`) declines naming 350. Rows 350 and 360 both claim the
integral-to-decimal casts at 64 bits in their text; that overlap is the owner's row text and is
flagged here, as in `VARKA-348.md` 7, not resolved.

Declines that stay, each naming its row: the table of 2.6; the internals as section 8 answers.
*Since 1 November 2026 (section 8):* the internals are out of the census and not built, so none
of them is a decline of this row.

## 8. Questions for the owner

Answered by the owner on 1 November 2026: **(b)** to question 1 - `UnscaledValue`, `MakeDecimal`
and `CheckOverflow` are recorded out of the census with the reason "evaluated by an operator Varka
does not fuse" (`HashAggregateExec`, `WindowExec`, `SerializeFromObjectExec`), none of them is
built now, 349b is dropped, and the guard-the-inputs `CheckOverflow` form of 2.5 stays specified
for rows 350 and 360. Question 2 lapses under (b). The census record is `census_curated.txt`'s
`[implicit excluded]` section (section 6).

1. **The three decimal internals and the census.** Step 1 confirms item 0.7 (10.1,
   `results/VARKA-349-reachability.txt`): in every plan printed, `UnscaledValue` is evaluated in
   the input of `HashAggregateExec`'s or `WindowExec`'s aggregate, `MakeDecimal` in
   `HashAggregateExec`'s result expressions or `WindowExec`'s window expression, and
   `CheckOverflow` in `SerializeFromObjectExec`'s serializer over a JVM object, at
   `DECIMAL(38, 18)` for a `BigDecimal` Dataset and within precision 18 only for a `Dataset[Row]`
   map. None reaches a `ProjectExec` or a `FilterExec`, and the trees are the same with Varka off.
   The decimal adds Spark itself evaluates inside `sum` and `avg` are not this row's either: a
   `BIGINT` or `DOUBLE` add after the rewrite, a decimal add of precision 19 or more without it.
   So the question stands; it is not withdrawn. The options:
   - (a) Build the three as section 2 has them, with `built` coverage rows (349b, after row
     481's `NULL` mode, one or two days), and count `unscaled_value` fused when this row lands,
     `make_decimal` with a store above 18. The census moves; no query runs faster until a later
     milestone fuses an aggregate, a window or a serializer.
   - (b) Record the three out of the census with the reason "evaluated by an operator Varka does
     not fuse" (`HashAggregateExec`, `WindowExec`, `SerializeFromObjectExec`), as a constant folded
     before Varka sees it is out of it, and build none of them now; 349b is dropped, and the
     guard-the-inputs `CheckOverflow` form of 2.5 stays specified here for 350 and 360, which
     need the same rescale guard.
   - (c) Build them as in (a) and leave them out of the count: tested through the `built` rows,
     which compile an expression wherever Spark would place it, as `InSet`'s are, at (a)'s cost
     and with (b)'s census.

   *Recommended: (b)*: a census pair should stand for a query that runs faster, and no query
   would; (a) moves the count with no user-visible change, (c) spends 349b's days on kernels no
   plan calls, and the work stays cheap to do when a later milestone fuses aggregates. Under any
   answer, 10.1 records two findings for the rows that own them: an arithmetic inside an
   aggregate's input (`sum(d8 * d9)`) stays in the aggregate, so 349a fuses it only where the
   user's query puts it in a filter or a projection; and a widened window sum's cast reaches a
   `ProjectExec` (360's shape).
2. **`MakeDecimal` above precision 18** (2.4) depends on question 1: if the internals are not
   built, it does not arise; if they are, it declines naming 359 unless row 157 admits a
   long-valued output above 18. Step 1 shows Spark builds it at `DECIMAL(38, 2)` for
   `sum(CAST(d8 AS DECIMAL(30, 2)))`.

## 9. Done when

Row 349's text holds, with the corrections above: `+`, `-`, `*` and their `try_` forms fuse for
every operand pair whose result type is within precision 18, as conditions in 349a and outputs after
157, and every other pair declines naming its row; the type sweep holds the decision to Spark's
`resultDecimalType` over every pair; one kernel serves LEGACY, ANSI and TRY, byte for byte;
`decimal64.smt2` proves the no-overflow claim for every result precision, the rescales by 348's
lemma, and the guards under both solvers, with refinement sections for each new coverage row; the
internals stand as section 8 answers, with Trino's fixture and the planted fault where
`CheckOverflow` is built (*since 1 November 2026, section 8 (b): out of the census and not built,
the fixture and the fault going with 2.5's form to 350 and 360*); the measurement against the
baseline is committed at both widths with its predictions scored; the bytes oracle is unchanged for
every existing shape; and the PR build and the option matrix pass.

## 10. Outcome

### 10.1 Step 1: the reachability reading, on the laptop

Printed on 31 October 2026 at `f0c1ab11c45` in a Varka session built as the benchmarks build it
(`VarkaArrowSessions.createSession(name, true, false, "local[1]")`, the table Arrow-cached),
every physical tree compared with the same session's tree under Varka switched off; the trees,
the aggregate buffers, update, merge and result expressions, the suite and the command are in
`results/VARKA-349-reachability.txt`. Item 0.7 holds: no internal reaches a `ProjectExec` or a
`FilterExec`, and every tree but one (X5 below) is the same with Varka off. One node per
internal:

- **`sum(d8)`** (Q1, Q1g, Q1p): `DecimalAggregates` makes it `MakeDecimal(sum(UnscaledValue(d8)),
  18, 2)`. `UnscaledValue` is evaluated in `HashAggregateExec`'s update expression, inside the
  aggregate's own input, and the sum is a `BIGINT` add (`coalesce(sum, 0L) + unscaledvalue(d8)`,
  merge the same); `MakeDecimal` to `DECIMAL(18, 2)` is in `HashAggregateExec`'s result
  expressions, the final aggregate's when the plan has a partial and a final one.
- **`sum(CAST(d8 AS DECIMAL(30, 2)))`** (Q2): the cast is peeled, the same `BIGINT` sum, and
  `MakeDecimal` to `DECIMAL(38, 2)` in the result expressions: the unguarded `p >= 19` form of
  2.4, also in `HashAggregateExec`.
- **`avg(d8)`** (Q3): `Average` over `UnscaledValue`, whose buffer is a `DOUBLE` sum and a
  `BIGINT` count, so its adds and its quotient are floating; the result expressions divide by
  the double `10^2` and cast to `DECIMAL(12, 6)`, in `HashAggregateExec`. No decimal arithmetic
  is left in it.
- **`sum(d8) OVER (PARTITION BY k ORDER BY rid ROWS ...)`** (Q4; `avg` as Q4b):
  `MakeDecimal(sum(UnscaledValue(d8)) OVER (...), 18, 2)` sits whole in `WindowExec`'s window
  expression, the `UnscaledValue` evaluated by the window's aggregate and the `MakeDecimal` by
  its result projection; `avg`'s divide and cast to `DECIMAL(12, 6)` the same.
- **The widened sum as a window** (Q4c, added): the window arm does not peel the cast, so
  `CAST(d8 AS DECIMAL(30, 2))` is hoisted into a `ProjectExec` under the window's sort, a node
  Varka's rule sees, and the window sums a `DECIMAL(38, 2)`. That cast is 360's, not this row's.
- **A Dataset of `BigDecimal`** (D1, D2): `CheckOverflow` to `DECIMAL(38, 18)`, the encoders'
  `SYSTEM_DEFAULT`, over `Decimal.apply` of the JVM object, in `SerializeFromObjectExec`'s
  serializer; the deserializer reads `d8` with `toJavaBigDecimal` or `toBigDecimal`. A
  `Dataset[Row]` map (D5, added) is the only path to a `CheckOverflow` within precision 18,
  `DECIMAL(8, 2)` from the schema (`RowEncoder`), and its input is likewise a field of an
  external `Row`, never a column.
  A local `Seq(...).toDS()` (D3, D4) encodes on the driver into a `LocalTableScan`, `value +
  value` folded with it.

What Spark's own aggregates add is never this row's shape. `Sum` keeps a decimal of precision
`p + 10`, so for `p <= 8` it is the `BIGINT` sum above and for `p >= 9` its add is at precision 19
or more (X1, `sum(d18)`: a `DECIMAL(28, 2)` add in the update and merge expressions); `Average`
is the double form for `p <= 11` and otherwise a decimal sum of precision 22 or more and
`DecimalDivideWithOverflowCheck` (X2, `avg(d18)` to `DECIMAL(22, 6)`). Every such add is
evaluated in `HashAggregateExec` and would be 360's.

Where the user writes the arithmetic, it reaches the fused nodes outside an aggregate's input
and not inside it: `sum(d8 * d9)` (X3) keeps its `DECIMAL(18, 4)` multiply, a 349a shape, in the
aggregate's update expression, with no `Project` pulled out; `d8 + d9` as a grouping key (X4) is
pulled into a `ProjectExec` (349c's projection, after 157); and `WHERE d8 * d9 > 0` (X5) reaches
a `FilterExec`, of which Varka fuses today only the null tests, leaving the multiply in a row
filter above its `VarkaFilterColumnarToRow`, the one tree that differs with Varka off.

### 10.2 Step 3: 349a, the arithmetic

*1 November 2026, on branch `varka-349-arith` from row 348a's foundation commit (`c96259f3825`,
`Scaled`, `value()`, `rescale` and `decimal64.smt2`), with this plan's branch merged in, and
`varka-348-conditions` merged again at `6643a4f6765` for 348a's negation, null tests over values,
`d9s4` and `d16s9` fixtures and refinement section.*

**What was built.** `VarkaDecimalCompiler.value()` gains three arms and an unwrap: `Add` and
`Subtract` rescale both operands to the result's scale `S` with 348's `rescale` and build one
`IntArith(ADD | SUB, WRAP)`; `Multiply` builds one `IntArith(MUL, WRAP)` over the unscaled values,
with no rescale; a `RuntimeReplaceable` is read through its replacement, so `try_add`,
`try_subtract` and `try_multiply` reach the same arms as `Add`, `Subtract` and `Multiply` in TRY
mode. The result's `Scaled` type is the node's own `dataType()`, Spark's `resultDecimalType`; the
compiler recomputes the exact precision only to decide and to name the decline: past 18 digits a
sum or difference declines naming row 360 (360 and 363 past 38), a product row 361 (362 and 363
past 38), an operand past 18 digits rows 359 and 360 under `+` and `-` and rows 362 and 363 under
`*`, and a node typed other than Spark's rule within 18 digits, which no analysed tree holds,
declines rather than computing at a type it does not have. A two-literal tree folds to its
constant. The arms are reached only through `value()`, so they fuse under a comparison, the only
decimal consumer before row 157's store; `VarkaNodeCompiler` gains no arm.

**Tests.** `VarkaDecimalArithmeticSuite` (JUnit) holds the decision to Spark's own result type
over every pair of operand types with `1 <= p <= 18` and `0 <= s <= p`, 189 types and 35721 pairs
per operator, and checks on every fused pair the reduction the lemmas below rest on; runs kernels
over the edges of section 3 item 2 and over 40 random fused type pairs per operator, at the
operands' bounds, zero, one and random values, each against `BigDecimal`; checks that the ANSI
and `try_` forms compile to the LEGACY form's tree, and runs `IS NULL` and `IS NOT NULL` over a
sum, a difference and a product, which `value()`'s companion `valid()` reads as the AND of the
operands' validity. The IR fuzzer's decimal sequence (348's, its own seed, so no pinned shape moves)
draws a sum, a difference and a product wherever their digits stay within 18, and its reach test
asserts all three are drawn. `VarkaEmittedBytesSuite`'s new test compiles
each "DECIMAL arithmetic" coverage row in six spellings (ANSI off and on, precision loss on and
off, each also with every decimal operator in TRY mode) and asserts one set of method hashes at
both widths.

**Proofs.** `decimal64.smt2` gains, in `VarkaProofFiles.decimal64Arithmetic`: per result
precision `P` from 2 to 18, `+` and `-` of operands within `P - 1` digits exact and within `P`
digits, with a `sat` witness that operands of `P` digits leave the type; per operand precision
pair with `p1 + p2 <= 17`, the product bounded by `10^(p1 + p2) - 1` (the ring layer, a product of
two unknowns with constant bounds); per `n` from 2 to 17, any integer within `10^n - 1` its own
wrap and within `n + 1` digits (the wrap layer, linear); and a `sat` witness that two ten-digit
operands' product can wrap. With the high review's neighbours (*3 November 2026*: `a - b` leaving
the type at `P = 18`, one less than the product's largest value at `p1 = 8, p2 = 9` refuted, and
the wrap's identity holding within `Long.MAX_VALUE` and failing one past it), `decimal64.smt2`
holds 554 checks, 208 of them this row's. Before the four, on the laptop Z3 held its 550 in 0.14 s
and cvc5 in 1.72 s, against 0.15 s and 0.47 s for 348's 346 checks; with the four, after the
replay onto master (*4 November 2026*), Z3 held the 554 in 0.21 s and cvc5 in 1.84 s.
`refinement.smt2` gains a section beside 348a's that refines each coverage row below whole, its
comparison included, through the five queries in Spark mode ANSI with standard mode (raised past
the result's precision) and in LEGACY and TRY (null there), the `try_add` row in TRY alone, a
product's exact value a free `z` bounded by the ring layer. Its 90 checks take the file from 276
to 366; its 95 before the high review dropped the `try_add` row's ANSI query took it from 3.04 s
to 3.10 s under Z3 and from 4.55 s to 4.74 s under cvc5 on the laptop.

**Coverage rows.** A "DECIMAL arithmetic" family of ten predicates over 347's and 348's columns
and a new `d8` (`DECIMAL(8, 2)`, derived from `l2` in the differential's fixture):
`d9 + d9b < d18`, `d9 - d8 >= d9b`, `d8 * d9 <= d9b`, `try_add(d9, d9b) > d18`,
`d9 * 0.5 > d9b`, `d9 + 1.5 < d9b`, `d9 - d9s4 > 0.00`, `d9 + d16s9 > d9s4`,
`-(d9 + d9b) = d9s4` and `try_multiply(d8, d9) IS NULL`, each verified by its refinement.

**Census.** No pair moves (section 6). The notes of `+`, `-`, `*` and their `try_` forms say the
64-bit form fuses within 18 digits; `mod` and `%` lose 349 with the suggested home row 30,
`ym_times_num` with 361 and 363, `ym_div_num` passes to 363's claim, and `try_eval` loses 349 with
the suggested home 350 (section 7). `dt_times_num`, `dt_div_num`, `pmod` and `try_mod` did not
name 349.

**The specification.** `spec/STANDARD.md` 4.1 gains, after 348's sentence on the value of a
`DECIMAL`, the result scales of `+`, `-` and `*` from SQL-92 6.12 SR 2 and their exact values,
which the refinement section restates.

**Test time, on the laptop.** `VarkaDecimalArithmeticSuite` took 4.8 s when first measured, and its
12 tests since the high review 9.0 s in one run beside other JVMs, the sweep among them; the bytes
oracle's new test under a second; the IR fuzzer's own JVM ran its decimal sequence's 300 draws in 2
minutes 47 seconds with the three arithmetic picks, against 348a's 2 minutes 45 seconds for the
whole suite's JVM, a run each, so no cost is read from the pair.

**Prediction 6, scored.** No existing shape's bytes moved: against 348a's branch the oracle's 22
changed entries are all `option_arms` digests and nothing was removed. The digests moved with the
new coverage rows, which each digest covers, and not with a flag, since none was added.

**Left for the merge and for later.**

- `VarkaDecimalBaselineQueriesSuite` on 348's baseline branch states a fusion class per section,
  and this row's three filters there (`x + y < z`, `x - w > 0`, `x8 * y < z`) are `NULL_TESTS` on
  the unchanged code; 349a fuses them whole, so whichever of the baseline pull request and 349a
  merges second moves those three sections to `WHOLE`.
  *Done 1 November 2026:* the baseline reached this branch with the merge of
  `varka-348-conditions`, and the three filter sections are `WHOLE` there now.
- `VarkaConjunctOrder.errorFreeNode` still counts decimal `+`, `-` and `*` as able to raise.
  Within 18 digits none can, but only a residual conjunct beside a fused one reads it, and the
  conservative answer orders such a filter as Spark's codegen would; left unchanged.

### Departures (1 November 2026)

- **`d8 * d9 <= d18` cannot fuse.** The product is `DECIMAL(18, 4)` and `d18` is
  `DECIMAL(18, 2)`, so coercion compares at `DECIMAL(20, 4)` and the cast declines naming 359.
  Section 3 item 6 checked the arithmetic's result type and not the comparison's common type; the
  row is `d8 * d9 <= d9b`, compared at `DECIMAL(18, 4)`.
- **`d9 - d9s4 > 0` compares with `0.00`.** An integral literal is the cast the next bullet
  describes, so the row's zero is a decimal literal. The row and `-(d9 + d9b) = d9s4` and
  `try_multiply(d8, d9) IS NULL` came with the second merge of `varka-348-conditions`, which
  brought `d9s4`, the negation and the null test over a value; `d9 - d8 >= d9b` and
  `try_add(d9, d9b) > d18`, written before it, stay.
- **The literal row multiplies by `0.5`, not `3`.** An integral literal reaches the compiler as
  `CAST(3 AS DECIMAL(1, 0))`, which `value()` folds into a slot, so `d9 * 3 > d9b` fuses; but the
  census registers the integral-to-decimal cast as row 350's and never fused, and its lint
  refuses a coverage row that holds one. The row is `d9 * 0.5 > d9b`, and `d9 * 3` waits for 350
  (section 7 already names 350 for an integral operand).
- **The refinements are per row, not per operator.** Section 3 item 5 speaks of the arithmetic's
  refinement rows; 348a refines each of its rows whole, comparison included, and these follow it,
  so that every row names a refinement under row 490's rule rather than `differential`.
- **`decimalMulIntInputs` is not built here.** Section 2.3's arm is measured and read in 349c
  (section 5 item 5); with no reading on the AVX2 runner it would be an option with nothing to
  decide it, so 349a builds the arithmetic without it and the flag lands with its reading.
- **The one-kernel test also covers precision loss and lives beside the oracle.** It is a test
  of `VarkaEmittedBytesSuite` over the family's rows, as section 3 item 3 says; the oracle pins
  each row once.
- **A literal operand of `+` or `-` folds in `sum()`, not in `rescale()`.** Section 2.2 and "What
  was built" above say a literal operand is rescaled at compile time by 348's `rescale`; 348a's
  review took that branch out of `rescale()`, since a comparison's or a choice's literal folds
  under coercion's cast before it gets there. An arithmetic operand has no such cast, so the
  third merge of `varka-348-conditions` (`cd54af3d775`) gave `sum()` its own fold, `atScale`,
  which takes the literal's unscaled value at the result's scale into one slot. The old branch
  had also left the literal's own slot in the long table unused; the fold does not, which moved
  the emitted bytes of `d9 + 1.5 < d9b`.
- **The 18-digit declines of `+`, `-` and `*` are named in `wideReason`.** 348a's review put one
  width check at the top of `value()`, ahead of every arm, so the arms' own checks past 18 digits
  could no longer be reached; the merge moved their reasons, rows 359 to 363 as listed above,
  into `wideReason`, and the arms keep only the check that a node is typed by Spark's rule.

### The second review of 349a (1 November 2026)

- **One constant fold.** `value()` folded a literal under casts, and `sum()` and `product()`
  folded a tree of literals; a decimal constant of any shape, Spark's `foldable` (a superset of a
  literal under casts, since a cast is foldable where its child is), now folds once at the top of
  `value()`, and `valid()` reads the same test. `atScale` keeps its fold, which takes the
  operand's value at the result's scale. `emitted_bytes.json` and `coverage.json` did not move;
  `VarkaDecimalArithmeticSuite` holds `abs(-1.25)` and `1.5 + 2.25` to one slot each.
- **The type rule is one record**, `ResultType`, read by `sum`, `product` and their wide
  reasons, and the mistyped check renders the rule's type from its two ints: past 38 digits
  `DecimalType` throws, which a spied product typed `DECIMAL(10, 0)` over two `DECIMAL(20, 0)`
  columns, rule 41 digits, now declines instead.
- The default decline names row 351 and says a quotient or a remainder is no row's in this
  milestone, and the time compiler's reason for a decimal column as a value names 157 for an
  output and 350 and 366 for its casts.

### The test-oracle review of 349a (2 November 2026)

- **The type-rule sweep's reduction check read the IR.** The sweep checked that a sum's rescaled
  operands have at most `P - 1` digits, and a product's two operands as many between them, by
  recomputing them from Spark's type rule, the formula that gives `P - 1`, so it could not fail.
  It now compiles every fused pair and reads the digits from the IR: a `DecimalColumn`'s
  precision plus `k` for each `IntArith(MUL, x, LiteralSlot)` whose slot holds `10^k`, held to
  `P - 1` at every `ADD`, `SUB` and two-operand `MUL`; a pair whose IR has no such site fails.
- **Departure: the product's ring layer multiplies two unknowns.** Section 3 item 5 planned the
  bound on an exact product as an instance of `value_range.smt2`'s multiply lemma over the reals, so
  that no query multiplies two unknowns. `decimal64.smt2` asks it directly instead, one check per
  `p1 + p2` below 18 (136 of them) over two unknown `Int`s under `QF_NIA`: `|x| <= m1` and
  `|y| <= m2` give `|x * y| <= m1 * m2`. Both pinned solvers, Z3 5.1.0 and cvc5 1.4.1, proved the
  file's 550 checks in under two seconds, before the review's four neighbours. The risk is that
  nonlinear integer arithmetic has no decision procedure, so a later solver version may answer
  `unknown`; `dev/varka_prove.sh` fails on any verdict other than the expected one, so that would
  show as a failed proof, not a silent pass, and the fix is the planned instantiation of the
  lemma.
