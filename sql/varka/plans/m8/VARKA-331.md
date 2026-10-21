# VARKA-331: `BOOLEAN` columns as conditions

*Planned 21 October 2026 against master `a429af749f4`. **[V]** = read in the code on master, **[I]**
= inferred and to be settled by the step named. Documents only: nothing here was built.*

## 0. What exists now (admission check)

* **A condition is two words.** `VarkaVectorIR.Cond` is sealed over `Compare`, `And`, `Or`, `Not`,
  `IsNotNull` and `InRanges` **[V]**. `VarkaVectorWalk.emitCond` lowers each in two forms: the
  *dense* body (every referenced input null-free) holds one `VectorMask` per node in `s.condMask`
  and `Not` is `VectorMask.not`; the *masked* body holds a known-true and a known-false `long` word
  per node (`s.kt`, `s.kf`), `And` is `kt & kt` / `kf | kf`, `Not` swaps the two slots with no code
  **[V]**. `Compare` builds `kt = cmp & validL & validR` and `kf = ~cmp & validL & validR`
  **[V]**. The masks become `VectorMask` only where `IfElse` or the root consumes them.
* **`laneType()` of a `Cond` comes from its children** (`And`: `left().laneType()`;
  `IsNotNull`: `child().laneType()`) and `requireSameLane` refuses mixing **[V]**. A leaf with
  no value has no lane to give; `And(b, Compare(long...))` therefore needs the leaf to carry one.
* **No boolean column compiles.** `VarkaConditionCompiler.condArm` has no `BoundReference` or
  `Literal` arm; `compileCond` of a bare boolean column returns `unsupported predicate`
  **[V]**. `VarkaNodeCompiler.laneOf` has no `BooleanType`, `VarkaEvaluatorBase.isArrowBacked` has
  no `BitVector` case and ends in `default -> false`, `compare` takes only int and date operands
  through `operand`, and the only literal readers take `Integer` (`literalDays`, `bound`) **[V]**.
* **`IS NULL` over a predicate exists (334).** `compileValidity` sends every `BooleanType` child
  to `compileCond(child).map(IsNotNull::new)` **[V]** (so it yields `IsNotNull(BoolColumn)` once
  the leaf exists); `Analysis` records each `IsNotNull` whose child is a `Cond` in
  `nullTestsOverCond`, and `Analysis.maskedOnly()` is `nullsFromValidInputs || guardUnderNullTest
  || prefixUnderNullTest()` **[V]**: `nullTestsOverCond` forces masked-only only together with a
  guard or a materialized prefix under the test, and the dense lowering answers an `IsNotNull`
  over a `Cond` constant true (`VarkaVectorWalk`), which is why a kernel holding one with a guard
  or prefix cannot use its dense body.
* **Choice.** `ifElse` compiles `If`, `caseWhen` folds `CASE WHEN ... ELSE`, and `CaseWhen`
  without an ELSE declines ("would break the dense body's all-valid invariant") **[V]**. All
  three compile their *branches* as values, so a `BooleanType` branch declines.
* **The runner can already hand a `BitVector` to a kernel.** `VarkaKernelRunner.fill` casts to
  `BaseFixedWidthVector` (which `BitVector` is [I]) and takes `getDataBuffer().memoryAddress()` and
  the validity address (0 when `nullCount == len`) **[V]**.
* **Arrow's bit layout is the validity layout.** `VarkaVectorSupport.validityBitsAt(segment, row,
  lanes)` reads a `lanes`-wide group of an LSB-first bitmap, shifted so lane 0 is bit 0, touching
  only the bytes the group occupies **[V]**; the width-specialised `validityBitsAt2/4/8/16` are
  the same read. A `BitVector`'s data buffer is the same bitmap, so **the data word needs no new
  helper and no new bound argument** - the bytes read are within `(len + 7) / 8`, as for
  validity.

### What differs from the row text and 2.5

1. 2.5 says the leaf is "`VectorMask.fromLong` over its data word". That is the *dense* body only.
   The masked body, the one that matters for nullable columns, never builds a mask for the leaf:
   `kt = data & valid`, `kf = ~data & valid` are three long operations on words already loaded.
   So 375's `fromLong` question touches 331 only in the dense leaf and the existing consumers,
   not in a new place.
2. Under a null slot Spark's writer leaves the data bit arbitrary. The formulas above are right
   for that without any extra step because both words are ANDed with validity; the dense body
   cannot see a null slot at all. The test that proves it (3.2) is where the arbitrary bits are
   drawn.
3. `b IS NULL` over a *bare column* must not take 334's `IsNotNull(Cond)` route: it is the
   column's validity word, as an int column's is (2.7). (Corrected in the review of 21 October
   2026, 9.1: this text said that route "forces `maskedOnly`". `maskedOnly()` is `nullsFromValidInputs
   || guardUnderNullTest || prefixUnderNullTest()` [V, `Analysis.java` around lines 377 to 379]; a
   non-empty `nullTestsOverCond` forces it only together with a guard or a materialized prefix
   under the test. The reason to avoid the route is the special case it touches at three
   sites, listed in 2.7, not an unconditional loss of the dense body.)
4. A boolean *null literal* is not a harmless leaf. The dense body's invariant is that nothing
   makes a null of valid inputs; a `NULL` constant leaf breaks it exactly as a no-ELSE `CASE
   WHEN` does, so it sets `nullsFromValidInputs` (4.4).
5. `bit_count(bool)` (335b) and `CAST(b AS INT)` (332) need a **condition-to-value** bridge that
   does not exist: `IfElse(b, 1, 0)` makes an unknown lane `0`, valid, where Spark gives null,
   and no IR node produces a null from valid inputs except a `TRY` overflow. 4.6 adds it.

## 1. Goal and non-goal

A `BOOLEAN` column is a condition leaf, so `WHERE b`, `WHERE NOT b AND d < d2`, `b IS NULL`,
`IF(b, x, y)`, `CASE WHEN b ...` and `coalesce` and equality over booleans fuse with Spark's
three-valued answers. **Non-goal here:** a boolean *output column* (a predicate or `b` as a
projection result, packed or materialized, and the per-word selected-row count): row 27. The one
decision they share is 4.5.

## 2. Design

### 2.1 The read

New IR leaf `BoolColumn(int ordinal, LaneType lane) implements Cond` (the one-argument form is
the int lane, as `ColumnRef`'s is; the lane renders in `canonical` through `laneTag`, so the int
shape hashes are unchanged). The compiler chooses the lane: that of the sibling it meets, **including BYTE and
SHORT** (a `BoolColumn` beside a narrow `Compare` takes that IR lane, so `requireSameLane` holds
for `And(BoolColumn, Compare(BYTE, ...))`; under `VARKA-329.md`'s WIDENED arm `Analysis.laneOf`
still answers INT for it, and the bit read is independent of the stored width, `validityBitsAt`
handling 1 to 64 lanes; `onLongLane` is INT-to-LONG only, so a narrow sibling needs no case
there), the int lane when it meets none (a lone `WHERE b` then runs at the widest group, 16 rows at 512 bits).
`onLongLane` (the existing int-to-long condition conversion) gains `case BoolColumn c -> new
BoolColumn(c.ordinal(), LONG)`; the long form reads `lanes` of the long species, the same bitmap.

Lowering in `VarkaVectorWalk.emitCond`, per lane group, with `v` the column's validity word
(`wordRef`, as a `ColumnRef`'s) and `d = validityBitsAt<N>(dataSegment, row)`:

| body | known-true | known-false |
|:--|:--|:--|
| dense (null-free inputs) | `condMask = VectorMask.fromLong(species, d)` | its complement, as every dense `Cond` |
| masked | `kt = d & v` | `kf = ~d & v` |
| epilogue (partial group) | the same, through `partialValidityBitsAt`, truncated by the existing lane mask | |

`~d` has junk above `lanes`; every consumer truncates (`fromLong` reads species-length bits,
`orValidityBitsAt` applies its lane mask - the invariant `IsNotNull`'s `kf` already states).
`Slots` plans one extra local per leaf (the data word) and demands the column's data segment as
well as its validity segment, as `ColumnRef` does for its vector; whether the planner can share
`ColumnRef`'s segment locals is step 1.

Evaluator side: `isArrowBacked` gains `case BitVector v -> !derivedInput[i] && v.getValueCount()
== rows`; `VarkaNodeCompiler.laneOf` stays value-lane only (a boolean has no value lane; the leaf
has its own constructor in `VarkaConditionCompiler`). Every place that sizes an input by
`getTypeWidth()` (the sanitizer's guards, `VarkaReproduction.KernelCall.bytesOf`,
`VarkaEvaluatorBase.inputWidths`, which casts to `BaseFixedWidthVector`, calls `getTypeWidth` and
feeds `VarkaWarmupGate` [V, around lines 873 to 880 of `VarkaEvaluatorBase.java`];
`VarkaKernelRunner.fill` is correct as described in section 0) is wrong for a
bit vector [I: `BitVector`'s type width is 0]: step 1 greps each and gives the bit layout its own
size, `(len + 7) / 8`, with the sanitizer's canary test failing before the fix.

### 2.2 What a boolean column as a condition compiles to

`BoundReference` of `BooleanType` in condition position -> `BoolColumn`. The rest is the
existing nodes: `Not(BoolColumn)` is the free slot swap, `And`/`Or` are the existing word
formulas, a filter root takes `kt` (unknown is false, the root rule of `Cond`'s javadoc), and
`IfElse(BoolColumn, a, b)` is unchanged (unknown takes ELSE, as `If(null, a, b)` does in Spark).
The whole existing predicate family composes with it with no new rule, which is the point.

### 2.3 Comparisons over booleans

`EqualTo` and the four orderings over two `BooleanType` operands (false < true): one new Cond
node `BoolCompare(CompareOp op, Cond left, Cond right)`, null-intolerant. With `t_i`, `f_i` the
children's known-true and known-false words and `k = (t1|f1) & (t2|f2)`:

* `EQ`: `kt = (t1 & t2) | (f1 & f2)`, `kf = (t1 & f2) | (f1 & t2)`;
* `LT`: `kt = f1 & t2`; `LE`: `kt = f1 | t2` masked by `k`; `GT`: `kt = t1 & f2`; `GE`: `kt = t1 | f2`
  masked by `k`; for the four orderings `kf = k & ~kt`.

Not composed from `And`/`Or`: `EQ` happens to be Kleene-correct as `(a AND b) OR (NOT a AND NOT
b)`, but `LE` and `GE` are not (`Or(Not a, b)` is true for `a = NULL, b = TRUE`, Spark's `a <= b`
is null), and one node keeps all five on one tested formula. `NOT (a = b)` is `Not`. A boolean
literal operand is a `CondConst` (2.4), never a literal slot; the optimizer already turns `b =
TRUE` into `b`, and the compiler arm accepts the unsimplified form too (a test builds it by hand).
Dense body: the same formulas over `VectorMask` (`and`, `or`, `not`, `xor`).

### 2.4 Constants

`CondConst(Truth value)`, value `TRUE`, `FALSE` or `NULL`, in the node and so in the shape hash
(it is structure, as `GuardedRange`'s bounds are, not a slot): `TRUE` = `kt` all ones, `kf` zero;
`FALSE` the reverse; `NULL` both zero. `NULL` makes a null of valid inputs, so `Analysis` sets
`nullsFromValidInputs` for it (no dense body) and `IsNotNull` over it is the existing masked
`kt | kf` = 0. This is `SELECT ... WHERE x AND NULL` and `CASE WHEN p THEN b ELSE NULL`, which
the optimizer leaves in place; most other constants fold away before the compiler sees them
(the test list says which, by running the optimizer on each). It is also the row that retires the
"CASE WHEN without an ELSE" decline for boolean-typed branches (2.5), not for value branches.

### 2.5 Choice over booleans

When the branches of `If`, `CaseWhen` or `Coalesce` are `BooleanType`, they are conditions, and
the result is a condition:

* `CondIf(c, t, e)`: `kt = (kt(c) & kt(t)) | (~kt(c) & kt(e))`, `kf` likewise with `kf`
  (unknown `c` takes ELSE); `CaseWhen` folds onto it as today's right fold does, a missing ELSE
  being `CondConst(NULL)`;
* `Coalesce(p, q)`: `kt = kt(p) | (~known(p) & kt(q))`, `kf = kf(p) | (~known(p) & kf(q))`, a
  fold onto `CondIf(IsNotNull(p), p, q)` reusing 334's node over a condition - no node of its own.

This is the only place 331 and 27 meet: **a boolean-valued expression is a `Cond` tree whatever
its root is.** A filter root takes `kt`; row 27's output root packs `kt` (and `kt | kf` as the
validity) or materializes it. Row 27 builds no second boolean IR; it needs only the root arm.
Value-side `IfElse` over *numbers* under a boolean condition is already covered by 2.2.

### 2.6 The condition-to-value bridge

New value node `CondValue(Cond c, LaneType lane)`: lane value `1` where `kt`, `0` where `kf`;
validity `kt | kf`. One blend of two broadcast constants under the mask, validity read off the two
words (the same words `IsNotNull(Cond)` reads). It is what `bit_count(b)` (335b: Spark's is `1`
or `0`, an `INT`), `CAST(b AS INT)` (332) and row 27's int-materialized form all need, so 331
builds it with the leaf; 335b and 332 then add compiler arms only. Its edge: a `NULL`-producing
node, so a kernel holding it is `maskedOnly` exactly when its cond is not dense-safe (the
analysis already reads the cond's own flags; no new rule).

### 2.7 `IS NULL` and `IS NOT NULL`

Over a *bare boolean column* these are the column's validity word, by the existing `IsNotNull`
column form: `compileValidity` takes `BoundReference` of `BooleanType` to `IsNotNull(BoolColumn)`
(it already sends every `BooleanType` child through `compileCond`) and the walk reads it as a
**column-form** `IsNotNull` (word read). The special case touches three places, each needing an
exception for a `BoolColumn` child: `Analysis` (around lines 1250 to 1266: the check of the child,
and the add to `nullTestsOverCond`, which a `BoolColumn` skips); `VarkaVectorWalk` (around line
1182: `IsNotNull n when !dense && n.child() instanceof Cond` would match a `BoolColumn` before the
column arm at around line 1197, so the guard excludes it); and `Slots` (the `IsNotNull` cases
around lines 1244, 1271, 1358, 1499 and 1579). The aim is to avoid the guard/prefix masked-only
case of section 0 for `b IS NOT NULL`, which is consistent with `VARKA-336.md`'s
`And(p, IsNotNull(p))` composition over a boolean column. Over a larger boolean expression it is 334's rule unchanged.
Spark infers `isnotnull(b)` beside `b` in a filter just as for int columns (`compileValidity`'s own
comment, VARKA-122), so without this a fused `WHERE b` would keep a residual row filter.

### 2.8 Null-safe equality (336), `bit_count` (335b), casts

* **Boolean `<=>` (decided in the plan, object if wrong; review of 21 October 2026, M3).** 331
  owns the boolean leaves, so **331 builds the boolean null-safe equality**:
  `BoolNullSafeEq(Cond left, Cond right)`, a `Cond` over validity words, never null:
  `kt = (t1 & t2) | (f1 & f2) | (~k1 & ~k2)`, `kf = (t1 & f2) | (f1 & t2) | (k1 ^ k2)` (k the
  known words, `k = t | f`; the truth tables were checked by the review). It takes `Cond`
  children, so `b1 <=> b2`, `p <=> q` and `b <=> true` all compile. The compiler arm is the
  `EqualNullSafe`/`EqualNull` arm that 336 adds to `VarkaConditionCompiler`; 331 adds its boolean
  case to it, and whichever of the two rows merges second extends the arm the first created (no
  hard ordering between 331 and 336). 336 keeps `p <=> true|false` over a predicate (composed as
  `And(p, IsNotNull(p))`) and value operands, and no longer hands boolean operands to 331.
  Cross-reference: `VARKA-336.md` 2.3, `census.tsv`'s `<=>`/`equal_null` rows (owners
  336, 331, 329, 341, 338, 359) and `PLAN.md` rows 331 and 336.
* **335b** `bit_count(bool)` = `CondValue` (2.6) feeding the existing `BitCount`; the arm is 335b's.
* **332, 343, 350** boolean casts reuse `CondValue` and `BoolColumn`; not here.

## 3. Tests and oracles

### 3.1 The oracle is Spark's own evaluation

Every test compares with the row engine on the same rows (vanilla Spark is the answer):
`Filter`/`Project` with Varka off, and the existing `VarkaReference` for IR trees. `VarkaReference`
holds `Truth` for ints and longs; it gains a boolean leaf (value `0`/`1` plus `isNull`, with the
arbitrary data bit kept in the row array under a null) and the new nodes.

### 3.2 Exhausting the three-valued tables

A leaf is in one of four states: `TRUE`, `FALSE`, `NULL` with data bit 0, `NULL` with data bit 1.
A tree over `k` boolean leaves has `4^k` rows; one row per combination in one batch, so `k = 6`
is 4,096 rows (one default batch), `k = 7` 16,384 and `k = 8` 65,536 (sixteen batches), all of
which the existing batch machinery takes. The row for combination `i` is its base-4 digits; the
batch is run at every start offset `0..lanes` (rotated, so each combination lands on every lane
position and the epilogue's partial group). **JVM rule (row 410's lesson,
`skills/testing-and-debugging.md`):** `VarkaSpeciesGuard` fails an in-process test that puts a
second species of one lane type in the shared JVM, so each new suite either mixes in `VarkaOwnJvm`
(Scala) or is a forked-JVM JUnit suite like `VarkaEmitterMixedLaneSuite`; the width sweeps (both
widths of `VarkaMatrix`, every lane width of 3.3) run only there, and an in-process suite emits at
`VarkaMatrix.base` lanes.

* **Exhaustive formula set:** every `And`/`Or`/`Not` tree over three leaves up to five operators,
  deduplicated by canonical form (a few thousand), each at `k <= 3` (64 rows) - small, so the
  complete set runs on every build; every tree of up to six leaves from a fixed grammar sample
  (seeded, the seed in the failure) at `k = 6`, nightly; the `BoolCompare` (five ops), `CondIf`,
  coalesce and `IsNotNull`-over-boolean forms each over all `4^2`, `4^3` and `4^4` leaf states.
* **Mixed with comparisons:** one boolean column, one nullable int and one long column, `b AND
  (i < j)`, `NOT b OR (l = 0)`, rows drawn over all four states of `b` and the null patterns of
  the others, the long ones exercising `onLongLane`'s `BoolColumn` case.
* **Both bodies:** each shape runs null-free (dense) and with nulls (masked), and a `CondConst(NULL)`
  tree must refuse the dense body (`maskedOnly`), checked by the dispatch counters.
* **Arbitrary data under null:** the `NULL`-with-1 state above, plus a test that writes a
  `BitVector` through Arrow's `setNull` and then sets the data bit by hand; the answer must equal
  the one with the bit clear.
* **Spark level:** `VarkaCoverageDifferentialSuite` rows and a `WHERE` / `SELECT ... WHERE`
  differential over a table with nullable boolean columns written by Spark's own writer (the
  one that clears only validity), under LEGACY and ANSI (ANSI changes nothing here; the row says so
  by running both).

### 3.3 Bit-vector edges

Batch lengths 1, 7, 8, 9, 63, 64, 65 and one with a partial last byte at every lane width (in the `VarkaOwnJvm` suite of 3.2 only; the bound argument of 2.1: the data read never passes `(len + 7) / 8`), under the memory sanitizer
(`VarkaMemorySanitizer`) with the bit layout's size fixed; a `BitVector` with `valueCount` longer
than the batch declines through `isArrowBacked`, as the existing vectors do; an all-null column
(`validity` address 0) reads as `kt = kf = 0`.

### 3.4 Coverage and census

One coverage row (`coverage.json` through its tool, never by hand) for each of: a boolean column
filter, `NOT b`, `b AND p`, `b OR p`, `b = b2` (and one ordering), `b1 <=> b2` (`BoolNullSafeEq`), `b IS NULL`, `b IS NOT NULL`,
`IF(b, d, d2)`, `CASE WHEN b ...`, `coalesce(b, b2)` as a filter, a boolean `CondConst(NULL)`
leaf, and `CondValue` through a hand-built plan until 335b/332 add the arm. `And`, `Or` and `Not`
(census lines 22, 24 and 25: "fused today as a condition, not over a BOOLEAN column") gain the
`bool` pair; `dev/varka_census.py --check` holds the count; the `<=>` and `equal_null` census rows name 331
as the `bool` owner. The fuzzers draw it (352): the IR grammar gains the leaf as a row of 352, not
here (a grammar draw reprices the emit cost model, as VARKA-39's plan already said).

## 4. Predictions and costs

No speed is predicted by this row. Two cost facts are checked, not assumed:

* The emit cost model (`VarkaEmitCostTable`) has no entry for the new nodes (`BoolColumn`,
  `CondConst`, `BoolCompare`, `CondIf`, `CondValue`, `BoolNullSafeEq`): they are **unpriced until
  352's refit** (corrected in the review of 21 October 2026, 9.1: this text said each gets a
  price "from the model's own fitting", which contradicted leaving the grammar draw to 352; copied
  from `VARKA-336.md` section 6: `VarkaEmitCost.Tally.count` marks a feature without a price
  unpriced and `predicted()` returns null, the intended state). The emitted-bytes oracle
  (`VarkaEmittedBytesSuite`) is regenerated with `VARKA_BYTES_REGEN=true` **only** for the new
  shapes: every existing shape's bytes are unchanged, which is the milestone's invariant
  (1.1 item 1) and the check that a leaf added to `Cond` did not move an existing emission.
* The mask-conversion intrinsics: the dense leaf calls `VectorMask.fromLong` once per group, the
  call 375 and 376's census already cover (intrinsic and allocation-free at every width except
  the 128-bit long lane on x86). The 128-bit long form records the Java fallback like every other
  kernel; no new claim.

## 5. Order and files

1. Admission run: `flock <lock> dev/varka_emit.sh` on a two-int-compare kernel to read the segment
   and word locals `Slots` plans, then the `getTypeWidth` greps of 2.1. Settles the two [I].
2. `BoolColumn`, `CondConst`, the `isArrowBacked` case, the compiler arms, `Slots`/`Analysis`/`emitCond`,
   the sanitizer sizes; the 3.2 exhaustive set for these alone.
3. `BoolCompare`, `CondIf`/coalesce folds, the bare-column `IS NULL` rule; their rows.
4. `CondValue`; its hand-built tests. Then the coverage rows and census.

After 375 (the Arm `fromLong` row; the leaf's dense form is its consumer) and in foundations
wave F1; 27, 332 and 335b wait on it. **336 does not wait on 331** (decided in the plan, 2.8):
331 builds the boolean `<=>`, 336 follows only 334 and needs nothing from 331 for its own forms
(corrected in the review: this line once said 336 waits). Order inside 331: `BoolNullSafeEq` goes
with `BoolCompare` in step 3. Files: `VarkaVectorIR`, `VarkaConditionCompiler`,
`VarkaNodeCompiler`, `Analysis`, `Slots`, `VarkaVectorWalk`, `VarkaEvaluatorBase`,
tests and the coverage table. The sealed-switch sites a new `Cond` case is a compile error at
(copied from `VARKA-336.md` 0.7 and 4; five new nodes here, six with `BoolNullSafeEq`):
`VarkaVectorIR` (`permits`, `laneType`, `canonical`, the render, `childrenOf`, `withChildren`),
`Analysis.analyze` and the `collectRefusals` switch, `Slots` (the consumers, `bodyUses` and
liveness switches), `VarkaVectorWalk`, `VarkaOperandPosition`, `VarkaEmitBudget`'s weight,
`VarkaEmitCost.kindOf`, `VarkaChronoCompiler`'s rearm switch, `VarkaRangeAnalysis`,
`VarkaConditionCompiler.onLongLane`, `VarkaEvaluatorBase.inputWidths`, and in tests
`VarkaReference` (two switches), `VarkaShrinker` and `VarkaCanonicalIr`. `VarkaEmitCostTable` gets
no entry.

## 6. Out of this row

* Boolean output columns (row 27), including the per-word `countSet` (`VarkaSelectionBitmap`
  reads a byte at a time) and the allocation of a `BitVector` by `allocateVector`.
* Null-safe equality over value operands and `p <=> literal` (336; the boolean-boolean form is
  331's, 2.8), `bit_count(bool)` and `bit_get` (335b), boolean casts (332, 343, 350),
  the IR grammar draw (352).
* `IsTrue`/`IsFalse`/`IsUnknown` as Spark expressions (the optimizer rewrites them; if one
  survives it declines with its name and gets a row if the census says it is in scope).
* A `BitVector` that is not Arrow-backed (a row-based column), nested booleans (arrays, structs)
  and a boolean dictionary: all decline as today.

## 7. Declines that stay

A boolean expression whose children are not conditions Varka compiles (a UDF, an `InSet` over
strings, `EqualTo` over a `STRING`) declines at that child with its existing reason; `If` and
`CaseWhen` with a boolean branch that is not a condition decline (never a value branch compiled
as a condition); a batch whose boolean vector is not a `BitVector` of the batch's row count
declines through `isArrowBacked`; `lanesOverride` forms the host cannot hold decline as today.

## 8. Open questions for the owner

1. **Is the condition-to-value bridge (2.6) in this row?** Recommendation: yes. Without it 335b's
   `bit_count(bool)`, 332's boolean casts and 27's materialized form each need an IR node of
   their own, and the null behavior (the one thing `IfElse(b, 1, 0)` gets wrong) would be
   decided three times.
2. **All five boolean comparison operators, or `EQ` alone?** Recommendation: all five - one node,
   one formula table, an exhaustive test over 16 states; orderings over booleans are rare, so
   the only cost is test time, which is small.
3. **Does the lone `WHERE b` run at the int lane's width?** (The driving-lane rule is the same one `VARKA-329.md` 2.3 states for the narrow lanes: a kernel is driven at its widest value's lane and narrower values fill the low part of their species; a boolean leaf carries a lane exactly as a narrow column does, so the two plans must agree on it.) Recommendation: yes, the widest
   group; a mixed kernel takes its sibling's lane. A measured preference would be a later row.

4. **Decided in the plan, object if wrong** (the owner delegated these to the plan's
   recommendation, 21 October 2026): (a) 331 builds the boolean `<=>` as `BoolNullSafeEq` over two
   `Cond` children, since it owns the boolean leaves, and 336 does not wait on 331 (2.8, 5);
   (b) the new nodes are unpriced until 352's refit and no grammar draw is added here (4);
   (c) every new suite is `VarkaOwnJvm` or a forked-JVM JUnit suite, in-process suites emit at
   `VarkaMatrix.base` lanes (3.2); (d) a `BoolColumn` takes its sibling's lane, narrow ones
   included (2.1).

## 9. Done when

* A boolean column filters, chooses, compares and tests `IS [NOT] NULL` in kernels, the shapes
  of 3.4 each with a coverage row, the census's `And`, `Or` and `Not` pairs including `bool`.
* The 3.2 sets pass against Spark's row engine and `VarkaReference`: the small-tree set on every
  build, the larger ones nightly, every leaf state (arbitrary bits under nulls) at every start
  offset and both widths; the 3.3 edges pass under the sanitizer.
* The read is `validityBitsAt` over the data buffer, no mask conversion in the masked body;
  every existing shape's emitted bytes are unchanged (the bytes oracle regenerated for new shapes
  only).
* This plan's section 5 records what the admission run (step 1) found.
* **Last step (review of 21 October 2026, M5):** on a clean tree regenerate `coverage.json`,
  `docs/sql-varka.md`, `emitted_bytes.json` (new rows only, checked by `git diff`) and
  `sql/varka/golden_corpus.json` (`VARKA_GOLDEN_REGEN=true`; `VarkaGoldenCorpusSuite` byte-compares
  it against a run over Spark's golden SQL, where boolean columns and `<=>` occur, so verdicts
  will move); update `totalDomains` (`VarkaCoverageDifferentialSuite`) for the guarded rows added;
  add a boolean column to the differential fixtures (the table's twelve columns have none) and
  state what that does to the other rows' partitions; run `dev/varka_census.py --check` and
  `VarkaCoverageSuite`, `VarkaCoverageDifferentialSuite`, `VarkaGoldenCorpusSuite` and
  `VarkaEmittedBytesSuite` before the first push.

### 9.1 Review of 21 October 2026

An independent read-only review of this plan, applied the same day. Corrected in place and noted
where each stands: the boolean `<=>` had no owner and the 331/336 dependency was stated two ways
(2.8, 5, 8); the price statement contradicted the grammar stance (4); the `IsNotNull`/`maskedOnly`
claim was too strong and its sites are now listed (section 0, 2.7); `inputWidths` was missing from
the byte-width sites (2.1); a boolean column's lane beside a narrow sibling is now stated (2.1);
the sealed-switch sites were incomplete (5); the species-guard rule was missing (3.2); the common
regeneration step was missing (9). Nothing is left unconfirmed except the line numbers, which are
approximate and to be re-read at implementation.
