# Reading for milestone 8: what QuestDB's filter JIT and decimal types offer

Read on 27 October 2026 at the owner's request: does QuestDB, a Java database with a native SIMD
filter compiler and its own 64, 128 and 256-bit decimals, have anything for rows 359 to 364 of
`m8/PLAN.md` (2.8), for the tests of 352 and 504, and for the instruction-set axis of row 505?
Paths are relative to `/home/max/proj/questdb` at commit `d411ade` (26 October 2026), Apache-2.0.
Short prefixes: `c/jit/` is `core/src/main/c/share/jit/`, `J/` is
`core/src/main/java/io/questdb/`, `T/` is `core/src/test/java/io/questdb/test/`. **[V]** means
the author read the code; **[I]** means inferred. Nothing was built or run. The decimal part
was read by a helper and its load-bearing claims were re-read by the author.
`READING_DECIMAL_ENGINES.md`, `READING_ARROW_RS.md` and `m7/READING.md` section 8 are not repeated.

## 1. What QuestDB has

* **A filter JIT, not an expression JIT.** Java serializes a `WHERE` clause into a postfix IR;
  C++ lowers it with asmjit to one native function that returns matching row ids or a count
  (`J/jit/CompiledFilter.java` 35-50, `c/jit/compiler.cpp` 59-68) **[V]**. Projections,
  `CASE` and aggregates are not compiled. Decimals never reach it: `columnTypeCode` has no
  decimal arm and answers `UNDEFINED_CODE` (`J/jit/CompiledFilterIRSerializer.java` 1292-1306)
  **[V]**.
* **Decimals in plain Java**, mutable, one accumulator per function instance, per-row virtual
  calls (`J/griffin/engine/functions/math/ArithmeticDecimal128Function.java` 123-143) **[V]**.
  No SIMD and no native path for them.

## 2. How the JIT lowers a filter

1. **The IR is a stack machine.** An instruction is opcode, options and a sixteen-byte payload
   (`c/jit/common.h` 83-93); 23 opcodes, among them `Mem`, `Imm`, `Var`, the comparisons, the
   four arithmetic operators, `Neg`, `Not`, `And`, `Or`, short-circuit jumps and `Sx_I64`
   (`common.h` 56-81) **[V]**. Types are `i8` to `i64`, `f32`, `f64`, `i128` (UUID and
   `LONG128`) and three var-size headers usable only in null tests (`common.h` 36-47;
   serializer 2219-2257) **[V]**.
2. **One loop per filter, chosen at compile time.** An options word carries the widest column
   size and an execution hint (`CompiledFilter.java` 104-111; serializer 2559-2574) **[V]**.
   `SINGLE_SIZE` runs 256 bits over lanes of the widest column, `WIDE_LANE` runs four 64-bit
   lanes with narrower operands sign-extended or converted, `MIXED_SIZE` and `SCALAR` run the
   scalar backend (`compiler.cpp` 366-391; serializer 2460-2557) **[V]**. So a filter that mixes
   an `INT` and a `LONG` column without a conversion source runs scalar.
3. **The tail is a second code generator.** The AVX2 loop stops at `rows - step + 1`, then
   calls `scalar_tail`, which runs the scalar x86 backend over the same IR
   (`compiler.cpp` 467-571, tail at 569) **[V]**. There is no masked epilogue. The two backends
   must agree lane for lane, which is why their tests compare three modes (section 6).
4. **Row ids out.** For four 64-bit lanes the mask becomes four bits; `pext` on the identity
   `0x76543210` and `pdep` expand the kept indices, and one `vpermps` packs the row ids,
   stored whole (`compiler.cpp` 534-551; `c/jit/impl/avx2.h` 105-120) **[V]**. On AMD family
   23 ("Zen1, Zen1+ and Zen2") it takes the other path instead (`compiler.cpp` 490) **[V]**;
   that `pext` and `pdep` are microcoded there is `m9/SCOPE.md` item 22's finding. The other
   path writes every lane's row id unconditionally and advances the output by the lane's bit,
   branch-free (`c/jit/avx2.h` 121-136) **[V]**; both
   need slack past the output's end **[I]**. A step with no match skips the store
   (`compiler.cpp` 539-542) **[V]**. The count-only form subtracts each mask from a vector
   accumulator, `vpsubq` for 64-bit lanes and `vpmaddwd` for 16-bit ones (`compiler.cpp`
   808-821) **[V]**.
5. **Constants are broadcast once, before the loop**, and bind variables too
   (`c/jit/avx2.h` 45-119, `compiler.cpp` 474-476) **[V]**.
6. **The 64-bit lane multiply is seven instructions** for the low 64 bits: `vpshufd` to swap
   halves, `vpmulld` for the cross products, `vpxor` for a zero, `vphaddd` to sum them,
   `vpshufd`, then `vpmuludq` for the low product and `vpaddq` (`c/jit/impl/avx2.h` 587-611)
   **[V]**. Division has no
   vector form: the lanes are spilled to the stack and divided one by one with `idiv`
   (`impl/avx2.h` 634-737) **[V]**. So is `i64` to `f64` (four `vcvtsi2sd`, `impl/avx2.h`
   821-849) **[V]**; AVX2 has no `vcvtqq2pd`.
7. **The 128-bit lane has equality only.** `vpcmpeqq` on the two words, then `vpermq` swaps
   the words within each 128-bit half and an AND joins them (`impl/avx2.h` 341-352) **[V]**:
   the adjacent-lanes form of `VARKA-359.md` 2.1 (A), for equality. There is no 128-bit
   ordering compare; an ordering over UUID fell into `__builtin_unreachable` and crashed the JVM
   (issue 7546) until the serializer refused it (serializer 4353-4377) **[V]**.

## 3. Nulls and types, against Spark

QuestDB has no validity bitmap. A null is a sentinel value: `Long.MIN_VALUE`, `Integer.MIN_VALUE`,
NaN for floats, zero for IPv4, none at all for `BYTE` and `SHORT` (`c/jit/impl/consts.h` 31-32,
`impl/avx2.h` 122-140 and 204-224; `J/std/Numbers.java` 47-52) **[V]**. The null checks are an
options bit, on in production (`J/griffin/SqlCodeGenerator.java` 573); the off form exists for
tests only (`T/griffin/CompiledFilterRegressionTest.java` 10215-10216) **[V]**. What follows
differs from Spark, Varka's oracle, at every point:

1. **Two-valued logic.** `NULL = NULL` is true: `cmp_eq` has no null blend (`impl/avx2.h`
   322-362), `>=` is "not less, and both or neither null" (`impl/avx2.h` 425-436), and the Java
   filter agrees (`Numbers.lessThan`, `Numbers.java` 1094-1097) **[V]**. Spark drops a row whose
   predicate is null; `NOT (a < b)` over a null `a` is true in QuestDB and null in Spark **[I]**.
2. **Floating equality is a tolerance band**: `|a - b| <= 1e-10` for doubles, a wider
   `FLOAT_EPSILON` for floats (`impl/avx2.h` 290-320; `Numbers.java` 47, 846-848) **[V]**. The
   JIT and the Java filter disagree at the float boundary and the maintainer accepts it
   (`SqlCodeGenerator.java` 2493-2502) **[V]**. Spark compares exactly.
3. **NaN and infinity are null.** The non-finite test is `x - x` unordered, and a floating
   division folds a non-finite quotient to NaN (`impl/avx2.h` 188-202, 490-499, 739-746)
   **[V]**. In Spark NaN equals NaN, sorts above every number, and infinity is a value.
4. **Integer arithmetic wraps, and a result that lands on `MIN_VALUE` reads as null** (the plain
   `vpaddq` of `impl/avx2.h` 457-482, the null blend of 484-488) **[V; the collision I]**.
   Division by zero or by a null is null, tested in one instruction: `btr` clears the sign bit
   of the divisor, so zero and `Long.MIN_VALUE` both test zero (`c/jit/impl/x86.h` 320-357) **[V]**.
   Spark raises under ANSI on overflow and on division by zero.

None of this is worth taking. It is listed because a test or a fixture borrowed from QuestDB
carries these answers with it.

## 4. When the JIT refuses, and how

* **Per query, at compile time.** A serializer refusal is a `SqlException`; a backend refusal
  is `decline_filter`, an asmjit `report_error` read before `finalize`, so the function is never
  assembled (`c/jit/avx2.h` 554-561; `compiler.cpp` 991-1012) **[V]**. Either sends the whole
  filter to the Java filter (`SqlCodeGenerator.java` 5437-5450) **[V]**. Per page frame, a frame
  with column tops or type casts runs the Java filter (`J/griffin/engine/table/
  AsyncJitFilteredRecordCursorFactory.java` 407-437) **[V]**. There is no value-dependent
  decline: nothing like Varka's per-batch guard exists, because nothing the JIT computes can
  overflow into a different answer under QuestDB's wrap semantics **[I]**.
* **Fail closed, written down.** Four arms that were `__builtin_unreachable` now decline with a
  reason (many type-switch defaults still call it, `c/jit/avx2.h` 172 and 416 for two): an unknown
  opcode, a short-circuit opcode in the SIMD path, an empty value stack after the body, an
  unharmonised operand pairing (`c/jit/avx2.h` 1012-1027, 1040-1047, 1071-1083, 934-939;
  `compiler.cpp` 519-530) **[V]**. Each comment names the wrong rows or JVM crash the old arm
  allowed.
* **Width typing is the bug source.** The rule is "compute an arithmetic subtree at its own
  width, sign-extend only at the comparison boundary"
  (`J/griffin/CLAUDE.md` 374-376) **[V]**. The serializer is 6497 lines, most of them width
  bookkeeping, and its comments record a run of silent wrong-row defects: an `(i32, f64)` pair
  compared in the wrong lane width (`c/jit/avx2.h` 793-801), IPv4 and `CHAR` arithmetic typed by
  the column (serializer 1058-1110), a column-free comparison typed by the predicate where "both
  backends were wrong the same way, so parity between them never flagged it" (serializer
  1112-1173) **[V]**. Byte and short arithmetic is forced scalar (serializer 1186-1246) **[V]**.

## 5. CPU detection and instruction sets

The JIT reads asmjit's `CpuInfo::host()` when it compiles a filter and vectorizes only when AVX2
is present (`compiler.cpp` 368-386) **[V]**. It has no AVX-512 code (no match for `avx512` under
`c/jit/`) and on aarch64 it is scalar only, "no SIMD yet" (`compiler.cpp` 85-91) **[V]**.
`JitUtil.isJitSupported` is x86-64 or aarch64 (`J/jit/JitUtil.java` 119-121) **[V]**. AVX-512
exists only in QuestDB's ahead-of-time aggregates, compiled four times (AVX-512, AVX2, SSE4.1,
plain) and picked once at load by Agner Fog's `instrset_detect` (`core/src/main/c/share/
dispatcher.h` 38-51) **[V]**. No test forces an instruction set; and CI tests the committed
native binaries, not the sources, until a release workflow rebuilds them
(`J/griffin/CLAUDE.md` 464-467) **[V]**.

## 6. How the JIT is tested

1. **Three modes against the Java oracle.** `assertJitScalarAndVectorMatchJava` runs the query
   with the JIT off, then `FORCE_SCALAR`, then vectorized; it asserts that the compiled filter
   was or was not used in each run, that the Java answer has rows (or declares it empty), that
   it equals a written expected answer, and that both JIT modes print the same rows
   (`CompiledFilterRegressionTest.java` 10150-10199) **[V]**.
2. **Fixtures longer than the vector, with a tail.** `N_SIMD = 512`, and fixtures use
   `N_SIMD + 3` rows so both the body and the tail run (`CompiledFilterRegressionTest.java`
   203-204) **[V]**; a small table runs only the tail and leaves the AVX2 code untested
   (`J/griffin/CLAUDE.md` 457-460) **[V]**.
3. **A batch-length sweep, 0 to 9 rows**, with nulls and extremes in the first rows, and a check
   at the end that some iteration returned rows (`CompiledFilterRegressionTest.java` 4790-4810,
   9883-9898) **[V]**.
4. **Parity is not enough, said in the test code.** Shapes whose right answer is empty pin
   the empty answer, because "parity alone would pass even if BOTH paths shared the same bug"
   (`CompiledFilterRegressionTest.java` 9870-9881, 9900-9904) **[V]**.
5. **Exhaustive small grammars.** `FilterGenerator` takes the cartesian product of operand,
   operator, negation and `NOT` lists (Knuth's n-tuple generation) and runs every filter in
   both JIT modes and as a count (`CompiledFilterRegressionTest.java` 9588-9607, 10426-10502)
   **[V]**.
6. **A fuzzer with a JIT axis, and a test that the fuzzer still reaches old bugs.** The query
   fuzzer runs each query JIT on and JIT off against one pivot
   (`T/griffin/fuzz/QueryRunner.java` 91-110) **[V]**. `FilterShapeCoverageTest` draws 20 000
   predicates from the fixed-seed generator and asserts that each shape behind a past JIT defect
   (UUID ordering, IPv4 above `128.0.0.0`, a high `CHAR`, a comparison nested in a comparison)
   is still produced, and that the table factory deals every registered type
   (`T/griffin/fuzz/FilterShapeCoverageTest.java` 54-90, 210-236) **[V]**.
7. **Native unit tests, including a guard page.** `jittests` checks single emitters against
   expected values (`core/src/test/c/jittests/test_app.cpp` 2455-2488) **[V]**.
   `WideLaneInt32GuardPageLoad` puts a column's last bytes at the end of a mapped page with the
   next page `PROT_NONE`, so an over-wide load faults instead of reading garbage
   (`test_app.cpp` 2345-2402) **[V]**.

## 7. The decimal types

1. **Six storage widths.** Precision 1-2 in one byte, 3-4 in two, 5-9 in four, 10-18 in eight,
   19-38 in sixteen, 39-76 in thirty-two (`J/std/Decimals.java` 31-45) **[V]**. The null is
   the width's minimum, for 128 bits `high = Long.MIN_VALUE, low = 0` (`Decimals.java` 54-63)
   **[V]**: every sentinel lies outside `10^p`, so it never collides with a value **[I]**.
2. **`Decimal128` is two signed longs, the high word first in memory** (`J/std/Decimal128.java`
   209-212, 520-527) **[V]**, the reverse of Arrow's low-word-first slot. **`Decimal256`** is
   four words, highest first, up to `10^76 - 1` (`J/std/Decimal256.java` 33, 236-241) **[V]**.
3. **Add: the unsigned carry compare and an exact high word.** `hasCarry` is
   `sum + MIN < a + MIN`, then `Math.addExact` on the high words, then a check against
   `+-(10^38 - 1)` (`Decimal128.java` 1315-1328, 1730-1740) **[V]**. The same carry as 360's.
4. **Multiply on magnitudes, from 32-bit limbs.** The operands are negated to magnitudes first
   (`Decimal128.java` 1450-1465), then multiplied by 32-bit limbs and column sums: 64 by 64, 128
   by 64, 128 by 128 (`Decimal128.java` 1486-1567, 1612-1647) **[V]**. No `multiplyHigh`; Varka's
   plan avoids the `abs` with the `unsignedMultiplyHigh` sign correction (361).
5. **Two carry defects in those multiplies** (section 9).
6. **Division is Knuth's Algorithm D on 32-bit limbs**, with the qhat correction and add-back
   (`J/std/DecimalKnuthDivider.java` 58-155) **[V]**. A rescale down divides by `10^k` through
   it; a TODO says "replace the division by a multiply + shift" (`Decimal256.java` 1711) **[V]**.
   So QuestDB agrees with 2.8's "all use long division", and names 363's idea as owed.
7. **The precision check is a table compare**: per-precision `+-(10^p - 1)` thresholds in four
   words (`Decimal256.java` 224-233, `comparePrecision` 1014-1038) **[V]**, as 359 plans.
8. **SQL typing is not Spark's.** `+` is `max(p - s) + max(s) + 1` capped at 76
   (`J/griffin/engine/functions/math/AddDecimalFunctionFactory.java` 76-84); `*` is always
   `DECIMAL(76, s1 + s2)` on `Decimal256` (`MulDecimalFunctionFactory.java` 55-59); `/` is scale
   `s1 + s2`, `HALF_EVEN` (`DivDecimalFunctionFactory.java` 58-60, 75-78) **[V]**. No cap at 38,
   no precision-loss rule, no `MathContext(39, DOWN)`. Overflow raises, never null
   (`ArithmeticDecimal128Function.java` 136-141) **[V]**. `NULL = NULL` is true
   (`J/griffin/engine/functions/eq/EqDecimalFunctionFactory.java` 185-190) **[V]**. A cast to a
   smaller scale raises unless exact (`Decimal256.java` 1657-1665;
   `J/griffin/engine/functions/cast/CastDecimalToDecimalFunctionFactory.java` 268-278) **[V]**,
   where Spark rounds `HALF_UP`. Double to decimal truncates the extra digits
   (`Numbers.java` 795-810) **[V]**.
9. **Tests.** Each class has a 10 000-draw comparison with `BigDecimal` per operator
   (`T/std/Decimal128Test.java` 71, 552, 787, 855, 1230, 1680) **[V for 71 and 552; the rest
   from the helper's list]**. The generator is weak: its "small" and "medium" cases set `high = 0`
   with a possibly negative `low`, which is a large positive value, and its "large negative"
   case sets `high = MIN | x`, below `-10^38` (`J/std/Rnd.java` 126-145) **[V]**. The helper
   also found overflow tests that throw in the constructor before the operation runs:
   `testAddOverflow` builds `new Decimal128(Long.MAX_VALUE, 0, 0)`, which the constructor
   rejects (`Decimal128Test.java` 47-52; `Decimal128.java` 237-245) **[V]**, and two multiply
   tests at 895 and 901 likewise **[I, not re-read]**.

## 8. Agreements and differences with what Varka has read

* **Agrees with 2.8**: no engine divides by a power of ten with a magic multiply (now four
  engines and arrow-rs); the precision check is a `10^p` table compare (Velox, arrow-rs, now
  QuestDB); the carry is an unsigned compare.
* **Agrees with `m9/SCOPE.md` items 19.2 and 22.2**: `pext` and `pdep` are avoided on AMD before
  Zen 3, here by CPU family rather than vendor (`compiler.cpp` 490). A third engine with the
  same rule.
* **Agrees with item 29 (StarRocks)**: a JIT chosen per query, with the interpreter as the
  fallback.
* **Differs from Varka's decline model**: QuestDB declines per query, never per batch, and has
  no guard, because its semantics give it nothing to guard.
* **Agrees with 2.8 on the 64-bit multiply**: QuestDB's low-64 multiply is the seven-instruction
  sequence 2.8 cites for C2's emulation (2.6). Neither computes the high half 361 needs.

## 9. Two carry defects, by arithmetic

1. **`Decimal128.multiplyBy64Bit` loses a carry** **[V for the code; the SQL answer I, not
   run]**. `p1 = a0 * m1 + a1 * m0` sums two products unmasked (`Decimal128.java` 1628, with
   `p2` and `p3` alike at 1629-1630). For `a = 2^64 - 1` and `m = 2^34 - 1`, `p1 = 2^64 + 2^32 -
   2`, which wraps: the product comes out short by `2^96` and passes the check at 1641. The path
   is taken only for a multiplier in `[0, 2^63)`, a larger one going to `multiplyBy128Bit`
   (1458-1465), and
   `modulo` multiplies the quotient by the divisor through it (`Decimal128.java` 913-916); `%`
   takes `Decimal128` for a result precision of 19 to 38
   (`J/griffin/engine/functions/math/RemDecimalFunctionFactory.java` 67-71) **[V]**. So
   `(2^64 - 1)(2^34 - 1) % (2^34 - 1)` answers `2^96` where 0 is right **[I, not run]**. The
   fuzz generator of 7.9 cannot reach the case. It is the same class as StarRocks's
   `multiply_128x128` (`READING_DECIMAL_ENGINES.md` 4): a sum of two limb products near
   `2^64`. The multiply itself was replayed on 27 October 2026 in Python with Java's 64-bit
   wrapping, line for line from 1612 to 1647: for `a = 2^64 - 1`, `m = 2^34 - 1` it returns
   `a * m - 2^96` and raises no overflow **[V, by replay; the SQL path still not run]**. The
   function alone loses the carry for `a = m = 11529215050363437055` too, but that `m` is past
   `2^63`, so the dispatch never sends it here.
2. **`multiplyBy128Bit` does not check the top limb** **[V]**. The overflow test reads `r3` to
   `r6` (`Decimal128.java` 1561), but `p33 >>> 32` belongs to a seventh word never computed;
   `2^112 * 2^112` gives 0 with no exception. Probably unreachable from SQL **[I]**.

## 10. For Varka

**Worth taking.**

1. **Prove every partial-sum column; do not sample it** (9.1). QuestDB is the second engine with
   a lost limb carry, at `a0 * m1 + a1 * m0`. Rows 361 and 362: name both defects as the case
   for the carry-network layer, and add `(2^64 - 1) * (2^34 - 1)` and `2^112 * 2^112` to the
   random `BigInteger` cross-check.
2. **A decimal generator that reaches the hard values** (7.9): true negatives, low words at or
   above `2^63`, limbs near `2^32`, and values at `+-(10^p - 1)`. QuestDB's misses all four.
   Row 352, the decimal-shape sweep; row 504, as a completeness assertion on the draws.
3. **Fixtures longer than the vector, and a 0-to-9 batch-length sweep** (6.2, 6.3), with a final
   check that some iteration returned rows. Row 359: the pair's fixtures cover the epilogue at
   every width; row 504.
4. **Pin an empty answer, and assert which path ran in each mode** (6.1, 6.4). Row 504 states
   the same thesis; Varka's differential suites already assert fused or declined.
5. **A test that the fuzzer still reaches each past defect** (6.6): one assertion per fixed
   fuzzer finding that the generator still draws its shape. Rows 352 and 504.
6. **A guard-page load test** (6.7): the last slot at the end of a mapped page, the next page
   `PROT_NONE`. Row 359 and 347's leaf: the de-interleave's two loads in the epilogue over the
   `length * 16` mapping.
7. **128-bit equality on adjacent lanes is three instructions** (2.7): `vpcmpeqq`, `vpermq`,
   AND. Row 359's spike, arm A: the cost of `=` without a de-interleave.

**Citations, no new work.**

8. The per-precision threshold table for `|u| < 10^p` (7.7): row 359 already plans it.
9. The TODO to replace the `10^k` division by a multiply and shift (7.6): row 363, the idea
   owed in a second codebase.
10. `pext` and `pdep` avoided on Zen 1 and 2 by CPU family (2.4): `m9/SCOPE.md` item 22.
11. Instruction sets picked at load, no test that forces one, CI on committed binaries (5):
    row 505. QuestDB tests scalar against vector but never AVX2 against AVX-512, the gap 505
    closes for Varka.
12. Filters compiled scalar only on aarch64 (5): `m9/SCOPE.md` item 67.
13. Fail-closed arms with a reason in place of `__builtin_unreachable` (4): row 504; Varka's
    refusals already carry a reason.

**Not worth taking.**

14. A scalar tail as a second code generator (2.3): Varka's masked epilogue has none, and the
    three-mode tests exist to cover QuestDB's.
15. Sentinel nulls, two-valued comparisons, float tolerance, NaN as null, wrap and division by
    zero as null (3); decimals to 76 digits with no precision-loss rule, overflow that raises,
    narrowing casts that raise and double casts that truncate (7.8). Spark's answers are the
    oracle, so no QuestDB fixture is ported with its expected answers.
16. Knuth division and `HALF_EVEN` decimal division (7.6, 7.8): decimal division is out of
    milestone 8.

Nothing here contradicts a row. Read and not read: the x86 and aarch64 scalar backends beyond
division, the IR serializer's 6497 lines beyond the parts cited, `CompiledFilterIRSerializerTest`,
the vectorized aggregates, and every decimal SQL function beyond add, multiply, divide, equality
and the decimal cast.
