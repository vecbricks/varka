# VARKA-497: One `IN` arm for every lane

## 0. What exists now (admission check)

From master at `ef32e007d3a` (30 October 2026). Compiler paths are under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named; test
paths under `sql/catalyst/src/test/` or `sql/core/src/test/`.

1. **Four arm pairs, not three.** `condArm` in `VarkaConditionCompiler.java` matches `In` and
   `InSet` four times, each pair a value-type guard, a literal reader and a fold:
   - date and year-month interval (`:787-803`): `isDateOrInterval`, the reader `literalDays`
     (`:932-939`), then `compileInList` (`:950-985`);
   - narrow (`:807-826`): `VarkaTypes.lane(..).filter(LaneType::isNarrow)`, the reader
     `narrowValue` (`:914-924`) and, for `InSet`, an inline `Byte`/`Short` ladder, then
     `compileInList`;
   - floating (`:831-840`): `isFloating`, then `floatingIn` (`:633-671`), which keeps the elements
     by their raw bits (`floatingBits`, `:572-576`) and compiles each literal through
     `compileNode`; `InSet`'s elements are rebuilt as `Literal`s first;
   - `TIMESTAMP_NTZ` (`:844-859`): an equality on the type, an inline `Long` reader, then
     `compileLongInList` (`:991-1024`), `compileInList` with `OptionalLong`, `sink.longSlot` and a
     lane check (`:1019-1021`).
   Row 341's floating arm landed after the row was written, which is why the row counts three.
2. **The three folds are one algorithm.** Each rejects a null or non-literal element with "IN list
   has a null or non-literal element", keeps each value once in ascending order (a `TreeSet` of
   ints or longs, or a `TreeMap` keyed by the sign-extended raw bits), rejects more than
   `MAX_IN_LITERALS` (16, `VarkaNodeCompiler.java:130`) with "IN list longer than the fused cap of
   16", compiles the value, then builds `Compare(EQ, value, slot)` per element in that order and
   folds them with `balancedOr` (`:1030-1032`). The slots differ only by table: an int-table slot
   `VarkaNodeCompiler.slot(int, lane, literals)` (`VarkaNodeCompiler.java:1142-1144`) for the
   date, interval, narrow and `FLOAT` lanes (a float literal compiles to
   `slot(floatToRawIntBits, FLOAT)`, `:434-436`), and `sink.longSlot(bits, lane)`
   (`DeclineSink.java:195-210`) for `LONG` and `DOUBLE` (`VarkaNodeCompiler.java:437-439`).
3. **The literal reader per type already exists.** `VarkaTypes.Info.bits(Object)`
   (`varka/VarkaTypes.java:177-190`, row 494's registry) turns the boxed value Spark holds for
   every type on a lane into its lane bits sign-extended to a long: `Byte`, `Short`, `Integer`
   (`INT`, `DATE`, the year-month interval), `Long` (`BIGINT`, `TIME`, `TIMESTAMP_NTZ`, the
   day-time interval), and a float's or a double's raw bits. That is exactly what `In`'s
   `Literal.value()` and `InSet`'s `hset` elements hold. It throws on a value of another class, so
   the arm needs a form that answers empty instead. `nanSlot` (`VarkaNodeCompiler.java:1147-1153`)
   is already the dispatch from a lane to its table.
4. **Declined today though the chain serves them:** `BIGINT`, `TIME(p)` and the day-time
   interval, all on the long lane (`varka/VarkaTypes.java:317-350`); none matches an arm, so `In`
   falls through to "unsupported predicate". The test that pins the bigint decline is
   `VarkaExpressionCompilerSuite.scala:2664-2672`, "long arithmetic and IN stay declined: they are
   VARKA-104 and VARKA-102, not this one"; it pins `Add(l, 1L)` as well, which row 104 (planned,
   not built) still owes. The comment above the NTZ arm (`:841-843`) and the long-lane family's
   comment in `VarkaCoverageSuite.scala:513-514` ("three types, comparisons only") say the same.
5. **`INT` is declined too, and the row does not name it.** `isDateOrInterval` excludes
   `IntegerType` and the narrow guard excludes the int lane, so `i IN (1, 2)` declines;
   `coverage.json` has no `i IN` row, and the census's `in` and `in_set` lines admit `int` and do
   not fuse it (`census_curated.txt:45`, `:187`). A lane-keyed arm fuses it by construction. The
   Catalyst grammar's `($0 IN (1, 5000000000))` over `i` (`VarkaCatalystGrammar.java:253`), which
   type coercion widens to a bigint `IN`, fuses too. Question 1.
6. **What else reads `In`.** `floatingDecline` lists `In` and `InSet` among the nodes a floating
   operand may sit under (`VarkaNodeCompiler.java:338-348`), `VarkaConjunctOrder.java:492` orders
   them as comparisons, and `VarkaWidthAuditShapes.scala:108` rebuilds a long list as an `InSet`.
   None depends on which arm compiled it.
7. **Where the proof lives.** `emitted_bytes.json` pins every method of the fourteen `IN` coverage
   rows (date, year-month, `TINYINT`, `SMALLINT`, NTZ, `DOUBLE`, `FLOAT`, each in its `In` and its
   eleven-literal `InSet` form) at 4 and 16 int lanes, and its `fuzz`, `fuzz_long` and `fuzz_mixed`
   block digests come from IR the fuzzers draw directly, not from this compiler. The
   `option_arms` digests hash every shape, so any new row moves them, as row 338 recorded
   (`VARKA-338.md` 10.3).
8. **The cache prunes `In` and not `InSet`** (`CachedBatchSerializer.scala:302-305` has a case for
   `In` over literals and none for `InSet`), so a short list's literals must be values the
   fixtures hold, as the NTZ rows' `ntzHeld` does (`VarkaCoverageSuite.scala:180-187`), while the
   eleven-literal rows need not. Row 499's (b) is that trap.

### What differs from the row text

- **Four arms, not three** (item 1): the floating arm joins the merge, and its reader is the
  registry's like the others'.
- **The literal reader is not new code** (item 3): the arm reads through `Info.bits`, given a form
  that declines instead of throwing.
- **`INT` joins as well** (item 5): a lane-keyed arm reads it like the others, so this plan admits
  it with its rows beside the three named types, which extends the done-when (question 1).
- **The decline test is flipped, not deleted** (item 4): it pins row 104's arithmetic as well as
  `IN`, so the row's "the test is gone" is read as "its `IN` half is gone"; the `Add` half stays,
  retitled for row 104 (3.4).

## 1. Goal and non-goal

`In` and `InSet` compile through one arm pair for every type `VarkaTypes.lane` answers, reading
each literal through the registry, so that `BIGINT`, `TIME`, the day-time interval and `INT` `IN`
fuse,
the kernels of every existing `IN` shape are byte for byte what they are today, and a new type
that reads on a lane gets `IN` with no compiler change when its registry entry can read its
literals. Not this row: the decimal reader (348), the 128-bit pair's `IN` (359), `BOOLEAN`'s `IN`
(its column is a condition, not a lane value), any change to the cap or to the fold.

## 2. Design

### 2.1 The arm

One pair in `condArm`, in place of the four, guarded on the value's type having a lane:

    case In in when VarkaTypes.lane(in.value().dataType()).isPresent() ->
        () -> compileIn(in.value(), elements of in.list(), in, ...)
    case InSet s when VarkaTypes.lane(s.child().dataType()).isPresent() ->
        () -> compileIn(s.child(), elements of s.hset(), s, ...)

The `In` side reads a `Literal` element whose type has the value's `VarkaTypeId` and whose value
is not null; anything else is empty, as today. The `InSet` side reads the raw element. Both go
through one reader, a non-throwing `Info.laneBits(Object) -> OptionalLong` beside `bits`, which
`bits` then calls and throws on empty, so the two cannot drift.

`compileIn` is `compileInList`'s body over `OptionalLong` with the same checks in the same order:
a missing element notes "IN list has a null or non-literal element"; the values go into a
`TreeSet<Long>`; an empty set notes the same; more than `MAX_IN_LITERALS` notes "IN list longer
than the fused cap of 16"; then the value compiles. Its compiled lane must have the literal table
of the registry's lane (int table for `BYTE`, `SHORT`, `INT`, `FLOAT`; long table for `LONG`,
`DOUBLE`), or the arm declines with `compileLongInList`'s reason, generalised to "an IN value off
its type's lane"; no shape reaches it today. Each value becomes
`Compare(EQ, value, bitsSlot(bits, lane))` in ascending order, folded by `balancedOr`.

`bitsSlot(long, LaneType, literals, sink)` in `VarkaNodeCompiler` is `nanSlot`'s dispatch over a
given value: `slot((int) bits, lane, literals)` for the int-table lanes, `sink.longSlot(bits,
lane)` for the long-table ones. `nanSlot` becomes `bitsSlot` of the NaN's bits.

### 2.2 Why the bytes cannot move

For each existing arm the new one interns the same slots in the same order and builds the same
tree:

- **Order.** Sign-extended to a long, ints sort as ints, and a float's raw bits as the int
  `floatingBits` already widens; a double's and a long's are already longs. So the `TreeSet<Long>`
  orders every lane as its arm's set or map did.
- **Duplicates.** The integral arms keep a value once and the floating arm a bit pattern once;
  over bits these are the same rule, and two NaNs with different payloads stay two elements, as
  they are today.
- **Slots.** The integral arms intern `slot(v, compiledLane)`; the floating arm reached
  `slot(intBits, FLOAT)` and `longSlot(bits, DOUBLE)` through `compileNode`; NTZ called
  `longSlot(v)`, which is `longSlot(v, LONG)`. `bitsSlot` makes each of these calls, after the
  value's compilation as before, so the literal tables are interned in the same order.
- **Declines.** The strings and their order are the four arms' common ones, which
  `VarkaExpressionCompilerSuite.scala:1792`, `:1798` and `VarkaKernelEvaluatorSuite.scala:442`
  pin. Two forms decline that the narrow and date readers would have accepted by class alone: a
  literal of another type in the list, which type coercion does not leave.

### 2.3 The new types

`BIGINT`, `TIME` and the day-time interval need nothing beyond the arm: their column leaf and
their literals are `VarkaTimeCompiler`'s long-lane cases (`VarkaTimeCompiler.java:159-166`), and
their `IN` is the chain NTZ already runs. A `TIME` literal of another precision reaches the column
through `IN`'s coercion; if the cast lands on the column rather than folding into the literal, it
is a narrowing and declines (VARKA-88), so the coverage rows use `TIME(6)` literals, and step 1
checks the shape with `dev/varka_emit.sh`. The 128-bit width runs the long lane at two lanes, which
`width_audit.json` records for the comparisons already; the new rows add entries of the same kind.

### 2.4 What row 348 gets from this

Row 348 (decimal `IN` within precision 18, planned elsewhere) then needs no arm of its own: the
decimal type's admission on the long lane routes `IN` here, and 348 adds one case to `laneBits`,
a `Decimal` whose scale is the value type's read as its unscaled long, with coverage rows. What
stays 348's is the scale: `IN`'s coercion takes every element and the value to one decimal type,
so a column of a smaller scale arrives under a cast, which is 348's constant rescale, not this
arm's. The 128-bit pair (359) is not served: its equality is two word compares under an AND, and
its key is wider than a long, so 359b extends `compileIn`'s leaf and key when it builds the pair,
and this row builds no hook for it.

## 3. Tests and proofs

1. **The bytes oracle, before anything is added.** The merge alone, with no new row, regenerates
   `emitted_bytes.json`, `coverage.json` and `width_audit.json` and all three are byte-identical,
   the `option_arms` digests included; this is the commit that proves the refactor and is kept
   apart from the admission.
2. **The order test**, new in `VarkaExpressionCompilerSuite`: for each lane's type, an `In` with
   its literals in two orders and with a duplicate, and the `InSet` of the same values, compile
   to one canonical IR string, the same as the master's for the existing types, including
   `-0.0` and `0.0` and two NaN payloads on the floating lanes.
3. **Coverage rows**, in "Long-lane predicates": `l IN (5000000000, 42, 0)`,
   `t IN (TIME'12:00:00', TIME'06:00:00')` at precision 6, `dt IN (INTERVAL '2 00:00:00' DAY TO
   SECOND, INTERVAL '1 00:00:00' DAY TO SECOND)`, each a value the differential fixtures hold
   (`VarkaCoverageDifferentialSuite.scala:395-470`) because the cache prunes `In` (0.8), and each
   with its eleven-literal `InSet` twin, a row of the same note as the existing ones; and
   `i IN (3, 1, 12)` with its twin in the "Predicates" family (question 1). Each row names
   its verification for row 490's check: `differential`, as the other `IN` rows do.
4. **The flipped test.** `VarkaExpressionCompilerSuite.scala:2664-2672` keeps its `Add` half under
   a title that names row 104 alone, and its `IN` half becomes an assertion that `In(l, ...)`
   beside a date comparison fuses both conjuncts at the long lane; its title becomes "long
   arithmetic stays declined: it is VARKA-104's".
5. **The differential**, unchanged in kind: the coverage harness (`VarkaCoverageHarnessSuite`) over
   every fixture, the end-to-end suite's first row a family, the long-lane, floating and NTZ
   suites, and the two fuzzers that reach the compiler: `VarkaSparkFuzzSuite`, whose grammar's
   widened `IN` now fuses and is compared with Spark, and `VarkaCoverageCompositionFuzzSuite`.
   Both draw from the coverage table, so their seeded draws move with the new rows; row 499's (c)
   is that trap.
6. **The generated files**, in `ADDING_AN_EXPRESSION.md`'s order: `coverage.json`,
   `docs/sql-varka.md`, `emitted_bytes.json` (new rows only), `width_audit.json`, and the census:
   `census_curated.txt`'s `in` and `in_set` lines add `int`, `bigint`, `time` and `dt` to the
   types fused today, and `census.tsv` is regenerated.

## 4. Predictions

1. Step 1's three generated files are byte-identical to master's.
2. The new rows add shapes and move none: in `emitted_bytes.json` only their own entries and the
   `option_arms` digests change, and `width_audit.json` gains entries of the kinds the long lane's
   comparisons already have.
3. The census's fused pair count moves by 8 (`in` and `in_set` for four types).
4. The added test time is under a second: six to eight coverage rows at the observed cost of
   VARKA-317's row in the calibration table (its added rows came in at a small fraction of a
   second against a 10 s bound), plus one compile-only order test.

## 5. Order, files and size

1. **The merge** (2.1, 2.2): one arm, `laneBits`, `bitsSlot`, the four arms and their readers
   deleted, the order test (3.2); the three generated files byte-identical (3.1). Its own commit.
   The arm's guard still excludes the four types that decline today, `INT`, `BIGINT`, `TIME` and
   the day-time interval, so the commit changes no answer and no decline either, and the proof is
   of the refactor alone.
2. **The admission** (2.3): the exclusion removed, the coverage rows of all four types, the
   flipped test, the stale comments rewritten, the generated files and the census. Its own commit;
   the two go in one pull request.
3. **Row 499's (c) lands with this row or before it**: the new coverage rows move the seeded
   fuzz draws (3.5), and without the `draws` skip kind a by-design `checkIntOverflow=false`
   failure has no line that can hold it.

| file | what |
|---|---|
| `VarkaConditionCompiler.java` | the arm pair, `compileIn`; the four pairs and readers deleted |
| `VarkaNodeCompiler.java` | `bitsSlot`; `nanSlot` over it |
| `varka/VarkaTypes.java` | `Info.laneBits`, and `bits` over it |
| `VarkaExpressionCompilerSuite.scala` | the order test; the flipped test |
| `VarkaCoverageSuite.scala` | the new rows; the long-lane family's comment |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json`, `width_audit.json` |
| generated | `census.tsv` |
| census | `census_curated.txt`'s `in` and `in_set` lines |

**Size: small**, as the row says: about a day. The merge deletes more than it adds.

## 6. Out of this row

The decimal reader and rows (348), the pair's `IN` (359), `BOOLEAN`'s `IN` (a condition, owned by
333's census entry), the cap, and an `IN` whose elements are not literals.

Row 348 builds no `IN` arm of its own: decimal `IN` within precision 18 comes through this one, and
348 adds one case to `laneBits` (a `Decimal` at the value type's scale read as its unscaled long),
its coverage rows and its census entries, as 2.4 says; the scale of a column under `IN`'s
coercion stays 348's rescale.

## 8. Questions for the owner

1. **`INT`.** The arm fuses `i IN (...)` unless it excludes the int type, and the done-when names
   only `BIGINT`, `TIME` and the day-time interval. (a) `INT` joins, with its coverage pair and its
   census entries. (b) The arm excludes `IntegerType`, which stays declined, naming the row that
   owns it. *Recommended: (a)*: the row's goal is that "one arm keyed on the registry's lane
   compiles every type a lane reads", and an exclusion would be the kind of per-type gate the row
   removes; `INT` comparisons already fuse. The plan is written for (a); under (b) the merge
   commit's exclusion of `IntegerType` stays and the `i IN` rows are dropped.

## 9. Done when

One `In` and `InSet` arm pair remains in `VarkaConditionCompiler`, reading literals through the
registry; the merge's commit leaves `emitted_bytes.json`, `coverage.json` and `width_audit.json`
byte-identical; the `BIGINT`, `TIME`, day-time interval and `INT` `IN` (question 1) fuse
with coverage rows in both forms and their census entries; the bigint `IN` decline is no longer
pinned; the order test, the coverage harness, the fuzzers and the PR build pass, and the option
matrix is dispatched on the branch.

## 10. Outcome

(Written when the work lands.)
