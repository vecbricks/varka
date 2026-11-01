# VARKA-338: The `TIMESTAMP_NTZ` lane

## 0. What exists now (admission check)

From the code at `608686315e7` (25 October 2026; paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named).
This row comes after row 494, the type registry (`VARKA-494.md`), by the owner's decision of 25
October 2026; items 1 and 6 describe the code as it is today and what 494 changes in it.

1. **The type is declined by name, in one place.** `VarkaNodeCompiler.laneOf` (`:675`) has no arm
   for `TimestampNTZType`, its javadoc saying both timestamp types are "deliberately absent", and
   `VarkaTimeCompiler.isTimestamp` (`:225`) declines a column of either with
   `TIMESTAMP_OUT_OF_MILESTONE` ("a timestamp column is outside milestone 5"). Row 494 replaces
   both with the registry: `TIMESTAMP_NTZ` becomes an entry whose admission is `Owed("338")`,
   declining with its name and this row, and the zoned type a `Declined` entry. Every other site
   keys on the lane answer: once the NTZ entry's admission is `OnLane(LONG)`, the long-lane
   column leaf
   (`VarkaTimeCompiler.java:161`), the long-literal slot (`:168`, an NTZ literal's value is
   already a `Long`), the comparisons, `IN`, the choice nodes and the null tests take an NTZ value
   as they take a `TIME` one. No arm keys on "any long-lane type" for an operator Spark types
   differently for NTZ: `ntz - ntz` is `SubtractTimestamps`, `ntz + dt` is `TimestampAddInterval`,
   and neither `CAST(ntz AS BIGINT)` nor negation is legal Spark.
2. **Arrow.** Spark writes NTZ as `ArrowType.Timestamp(MICROSECOND, null)`
   (`ArrowUtils.scala:113`), a `TimeStampMicroVector`; the zoned type is a
   `TimeStampMicroTZVector`, a different class, so an entry in `VarkaLaneArrow.of(LONG)`
   (`sql/core/.../VarkaLaneArrow.java:107`) admits NTZ alone. `fromField` builds the destination
   from Spark's own field, as for the long lane's other three.
3. **`DATE` to `TIMESTAMP_NTZ` raises in every mode but TRY.** `Cast` evaluates it as
   `daysToMicros(d, UTC)`, which is `Math.multiplyExact(days, MICROS_PER_DAY)`
   (`SparkDateTimeUtils.scala:374`) whatever `evalMode` says; `try_cast` wraps the whole cast in
   a `catch` that returns null (`Cast.scala:1587-1596`; `canUseLegacyCastForTryCast` is false for
   a date source). The product fits a long exactly for days in `[-106,751,991, 106,751,991]`.
4. **The pieces the cast needs exist.** `WidenLane(x, LONG)` is the int-to-long sign extension
   (`varka/VarkaVectorIR.java:531`); `GuardedRange` passes its child through and sends a batch
   with a live lane outside its bounds to the row engine, which raises or nulls on the same row
   (`:479-511`). Under TRY that is a correct answer computed slower, not a wrong one: the row
   engine evaluates the same `try_cast`. A checked long multiply has no lowering (the
   checked-multiply decline, `VarkaNodeCompiler.java:956`), so the cast cannot be a `MUL` in
   `FAIL` mode; it is a `WRAP` multiply under the guard, exact by the guard's bounds, the way
   `CAST(bigint AS INT)` builds its guard directly (`VarkaNodeCompiler.narrowing`, `:601`).
   That precedent declines TRY outright, leaving its null to row 332; this row does not wait,
   since the guard's fallback already nulls on the right row, and 332 or 481 can lift
   `narrowing`'s TRY decline the same way.
5. **`TIMESTAMP_NTZ` to `DATE` is a floor division the long lane cannot do exactly today.**
   Spark computes `microsToDays(t, UTC)`, the epoch day of the UTC local date, which is
   `floorDiv(t, 86,400,000,000)`. The long lane's `ConstDivide` truncates and is exact only
   under `EXACT_DIVIDEND_BOUND = 2^52` (`varka/VarkaVectorIR.java:704-722`,
   `sql/varka/proofs/long_divide.smt2`); `2^52` microseconds is 142.7 years, so a bound guard
   would send every batch holding a date before 1827 or after 2112 - a `9999-12-31` sentinel
   among them - to the row engine. Section 2.6 of the milestone plan has the exact form, placed
   under row 340: `86,400,000,000 = 2^13 * 10,546,875`, so the floor division is an arithmetic
   shift by 13 and then a floor division by 10,546,875 of a value of magnitude at most `2^50`,
   inside `ConstDivide`'s bound. The cast needs it first, so it moves to this row (section 2.3)
   and row 340 reuses it.
6. **The tests that pin the decline.** `VarkaExpressionCompilerSuite` "a timestamp column
   declines with the milestone's reason, in both forms" (`:2569`) and `VarkaLongLaneSuite` "a
   timestamp comparison stays on the row engine" (`sql/core`, `:250`) run over `ts` and `ntz`;
   after 494 they pin the registry's words (NTZ owed to this row, the zoned type's decision),
   and this row flips their NTZ halves to fused; the zoned halves stay.
   `VarkaVectorProjectionSuite` groups NTZ with the long types for its fixture already.
   `VarkaCastMatrixSuite` and `VarkaTypeConformance` name `ntz` and wait for it;
   `m8/casts_fused.tsv` moves when the three pairs of section 2.3 fuse.
7. **The date fields stay declined, as row 340 needs.** `year(ntz)` arrives as
   `Year(CAST(ntz AS DATE))` (`GetDateField` is `ImplicitCastInputTypes`), so the cast of 2.3
   sits under every date field. `VarkaChronoCompiler.calendarInput` admits a child only when
   `dayRange` bounds it inside the int32 prefix, and "an unknown producer declines"; the cast's
   `NarrowLane` has no day range (`VarkaRangeAnalysis`, the `NarrowLane` arm), so the fields
   decline until 340 gives the cast its range or a `GuardedDay`.
8. **The conformance overflow check has no NTZ form.** `VarkaTypeConformance.overflow` builds
   `x + y`, `x - y`, `x * y`, `-x`, `abs(x)` and the `try_` functions, none legal Spark over two
   NTZ values; only `BOOLEAN` is exempt today, by asserting the census lists no arithmetic for
   it.

### What differs from the row text and the milestone plan

- **After the registry.** `VARKA-475.md` 5 orders its type registry before this row; row 494
  builds it, and this row's admission is then one entry's change (494's 2.5): the
  `TIMESTAMP_NTZ` entry's admission from `Owed("338")` to `OnLane(LONG)`, its storage and Arrow
  vector, and the regenerated `types.tsv`.
- **Row 332 is not needed.** Section 4's order puts this row "after 28 and 332", for 332's cast
  harness. That harness is now row 486's `VarkaCastMatrixSuite` and row 484's conformance
  contract, both done; 332's own casts are the integral and interval ones, and none is on this
  row's path.
- **The floor division moves here from 340** (item 5).

## 1. Goal and non-goal

A `TIMESTAMP_NTZ` column is read, compared, chosen, tested for null and written by a kernel, and
cast to and from `DATE`, with the same answers as Spark's row engine in LEGACY, ANSI and TRY.
Not this row: NTZ arithmetic (339), the date and time fields and the casts with `TIME` (340), the
zoned `TIMESTAMP` (out of the milestone by decision).

## 2. Design

### 2.1 The lane

NTZ joins the long lane: microseconds since the epoch, signed, eight bytes a row, ordered as a
signed long. No new `LaneType`, no new `Lane` entry, no new species, so nothing in the emitter
or the warm-up changes for the value itself.

1. The `TIMESTAMP_NTZ` entry in `VarkaTypes` (row 494): its admission from `Owed("338")` to
   `OnLane(LONG)`, and `sql/varka/types.tsv` regenerated. The zoned `TIMESTAMP` keeps its
   `Declined` entry.
2. `VarkaLaneArrow` gains `TimeStampMicroVector` for the NTZ id through `fromField`, its storage
   the entry's eight bytes; its comment loses "the timestamp vectors are deliberately not here".
3. `readsColumnOf` then admits NTZ, since it asks the registry, so `VarkaArrowSource` feeds NTZ
   columns to the kernels in the `sql` suites; that is the end-to-end path the coverage rows run
   through.

### 2.2 What fuses by that alone

Comparisons (`=`, `<>`, `<`, `<=`, `>`, `>=`, `<=>`), `IN` over literals, `IS [NOT] NULL`, `IF`,
`CASE`, `COALESCE`, `NVL`, `greatest`, `least`, and the column and choice as outputs, each over
NTZ operands, by the long lane's existing nodes - a prediction from item 0.1, which step 1's
coverage rows check form by form; a form that still declines gets its reason in section 7 or a
fix in this row. A comparison of a `DATE` and an NTZ arrives as
`CAST(d AS TIMESTAMP_NTZ) <op> ntz` and fuses once section 2.3's cast does.

### 2.3 The casts with `DATE`

- **`CAST(d AS TIMESTAMP_NTZ)`**, every mode:
  `IntArith(MUL, WRAP, GuardedRange(WidenLane(d), -106751991, 106751991), slot(86400000000L))`.
  The guard bounds the day so the multiply is exact; a batch with a live day outside goes to
  the row engine, which raises under LEGACY and ANSI and nulls under TRY, on that row. When row
  481 gives `GuardedRange` its `NULL` mode, the TRY form takes it and nulls in the kernel; until
  then TRY is correct and the overflowing batch is slower.
- **`CAST(ntz AS DATE)`**, every mode (it cannot fail): `NarrowLane(q)` with
  `y = x >> 13` (`BitOp(SHR)`, a floor division by `2^13`),
  `y' = y + ((y >> 63) & (1 - 10546875))` (a negative `y` moved down by `d - 1`, so the
  truncating divide floors), and `q = ConstDivide(y', 10546875, 2^51)`. The nested floors are one
  floor because both divisors are positive integers; `|y'| < 2^50 + 2^24`, inside the bound;
  every quotient is in `[-106,751,992, 106,751,991]`, so the narrowing is exact. All of it is
  existing nodes; no new IR, lowering or emit option.
- **`CAST(ntz AS TIMESTAMP_NTZ)`** is the identity and compiles to its child, as the date and
  `TIME` identities do.

The division is a compiler helper (`floorDivLong(x, divisor)`, taking only divisors whose odd
part keeps the shifted magnitude under `2^52`), so 340's date fields call it rather than build
it again. 2.6's Granlund-Montgomery form for the second step needs a 64-bit high multiply the
Vector API does not have; it stays 340's to measure as an arm if the census finds one.

### 2.4 Proofs

- `long_divide.smt2` gains the claim, over all 64-bit `x`, that the composition of 2.3 equals
  `floorDiv(x, 86400000000)`, through `dev/varka_prove.sh` - the integer identity only, since the
  file already proves `ConstDivide` exact under its bound.
- `DATE` to NTZ is proven by exhaustion over all 2^32 day values against `Math.multiplyExact`,
  as `casts.tsv` row 85 says: in range the product, out of range the guard's decline.

## 3. Tests and oracles

The oracle is Spark's own evaluation, through the reference evaluator and the differential
suites, as for every landed type.

1. **`VarkaTimestampNtzConformanceSuite`** over row 484's contract, named in the NTZ entry's
   `conformance` field (494) in place of its owing row, values from
   `integralValues(Long.MIN_VALUE, Long.MAX_VALUE)` plus the day edges `+-106,751,991 * 86.4e9`
   and the first micros past them: load, round trip, nulls, ordering, choice and its row of the
   cast matrix. The overflow check's exemption widens from `BOOLEAN` to the types with no
   `+`, `-` and `*` (0.8), with the same assertion that the census lists none for them; NTZ's
   own arithmetic is 339's, and its overflow is tested there. The literal rendering is the one
   detail the fixture must settle (NTZ has no `%d` literal form).
2. **Coverage rows**, ADDING_A_TYPE step 11: a load, a comparison against a literal and against
   a column, `IN`, a choice, a null test, an output, both casts and a `DATE` and NTZ comparison;
   each runs through the differential suite at every position.
3. **The edges of 2.3**: `try_cast` and `CAST` of `DATE` at days `+-106,751,991` (fused) and
   `+-106,751,992` (a raise under LEGACY and ANSI, a null under TRY, from the fallback), with
   the overflowing day in one batch of several so the other batches stay fused; `CAST(ntz AS
   DATE)` at `Long.MIN_VALUE`, `Long.MAX_VALUE`, `-1`, `0`, `+-86,400,000,000` and their
   neighbours, against `java.time` (`LocalDateTime.ofEpochSecond(...).toLocalDate().toEpochDay()`)
   as well as Spark.
   And `year(ntz)` declines with its reason (0.7), pinned so that 340 sees the change it makes.
4. **The flipped tests** of 0.6: the NTZ halves assert fused and the same answers; the zoned
   halves keep their decline with its new reason.
5. **The fuzzers**: the IR grammar's long leaf already covers the lane; the Spark fuzzer's
   fixture gains an NTZ column over the conformance values.
6. **The generated files** regenerated in ADDING_A_TYPE's order: `coverage.json`, the docs table,
   `width_audit.json`, `emitted_bytes.json`, `m8/casts_fused.tsv`, `types.tsv`; only new rows
   and shapes may move.
7. **The matrix** over the new conformance suite and the changed suites (step 15).

## 4. Predictions and costs

1. No existing shape's bytes move; the new shapes are the casts and the NTZ rows.
2. `dev/varka_new_lane.py --check` passes for the long lane and for `TimestampNTZType` once the
   coverage rows exist.
3. The census counts NTZ as fused for the operators of 2.2 and the two casts, and owes the rest
   to 339 and 340.

Cost: small, as the row says, plus the moved division: about two days. No benchmark gate: the
change adds no lowering a measurement would decide between, and a benchmark of the new types is
row 377's.

## 5. Order and files

1. The lane (2.1) with the flipped tests, the conformance suite and the coverage rows of 2.2.
2. The two casts (2.3) with their proof, edges and exhaustion.
3. The generated files, the gate, the matrix over the new and changed suites.

| file | what |
|---|---|
| `varka/VarkaTypes.java` | the NTZ entry's admission |
| `VarkaNodeCompiler.java` | the casts; `floorDivLong` |
| `sql/core/.../VarkaLaneArrow.java` | `TimeStampMicroVector` |
| `sql/varka/proofs/long_divide.smt2`, `VarkaProofFiles.java` | the floor-division claim |
| tests | the conformance suite, coverage rows, the edges, the two flipped tests, the fuzz fixture |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json` |
| generated | `emitted_bytes.json`, `m8/casts_fused.tsv`, `sql/varka/types.tsv` |
| `sql/varka/plans/m8/PLAN.md` | rows 338 and 340, 2.6's note that the division moved |

## 6. Out of this row

NTZ arithmetic (339); the date and time fields, `TIME` to NTZ and NTZ to `TIME` (340); `hash`
over NTZ (337); the zoned `TIMESTAMP`; `TIMESTAMP_NTZ` as a partition or sort key in the
planner (nothing Varka-specific).

## 7. Declines that stay

`CAST(ts AS TIMESTAMP_NTZ)` and its reverse (the session zone); a zoned column anywhere; every
NTZ function of 339 and 340 until those rows, each with the reason naming the row.

## 8. Questions for the owner

None.

## 9. Done when

Row 338's text holds: each form of 2.2 and both casts fuse with coverage rows; `DATE` to NTZ is
guarded at `+-106,751,991` days, raising under LEGACY and ANSI and null under TRY, with
`try_cast` tested at `+-106,751,991` and `+-106,751,992`. And: the conformance suite passes, its
overflow check exempt as `BOOLEAN`'s is (0.8); the floor division is proven over the whole
long range and pinned at `Long.MIN_VALUE` and `Long.MAX_VALUE`; `dev/varka_census.py --check` and
`dev/varka_new_lane.py --check` pass; no existing shape's bytes move.

## 10. Outcome

Built 26 October 2026 on `varka-338`: the lane and casts, the review fixes, a merge with row 480's
master, and the two NTZ suites moved to JVMs of their own.

### 10.1 What landed

1. **The lane (2.1).** The registry's `TIMESTAMP_NTZ` entry is `OnLane(LONG)` with conformance
   `Suite("TimestampNtz")`, and `VarkaLaneArrow.of` reads it from `TimeStampMicroVector`, a class
   apart from the zoned type's `TimeStampMicroTZVector`. The zoned `TIMESTAMP` keeps its `Declined`
   entry.
2. **The forms of 2.2.** The comparisons, `<=>`, the null tests, `greatest`, `least`, `CASE`, `IF`
   and `coalesce` fused by the long lane's nodes alone, as 0.1 predicted. `IN` did not (10.2, item
   2). `d < ntz` fuses once the cast does.
3. **The casts (2.3).** `CAST(d AS TIMESTAMP_NTZ)` is `IntArith(MUL, WRAP,
   GuardedRange(WidenLane(d), -106751991, 106751991), slot(86400000000))` in all three modes;
   `CAST(ntz AS DATE)` is `NarrowLane(floorDivLong(t, 86400000000))`, a shift by 13, the move of a
   negative value down by 10,546,874 and a `ConstDivide` by 10,546,875 under the bound `2^50 +
   10,546,875`. The identity cast is its child. `casts_fused.tsv` marks both pairs fused in LEGACY,
   ANSI and TRY.
4. **Proofs (2.4).** `long_divide.smt2` adds 10,546,875 to the divisors whose `ConstDivide` is
   proven exact, and four claims over every long: the shift by 63 is the sign, the composition is
   `Math.floorDiv(x, 86.4e9)`, its dividend is under the stated bound, and its quotient fits an int.
   Both solvers hold the file (983 checks; Z3 32 s, cvc5 9 s). `verify_double_division.py` has the
   row too. `VarkaTimestampNtzCastSuite` holds the guard and factor the compiler builds to
   `multiplyExact` over every int day, runs `multiplyExact` itself over a million days at each end
   of the guard and in the middle, and runs the kernels at the guard's edges in every mode.
5. **Tests (3).** `VarkaTimestampNtzConformanceSuite` passes its six checks that apply (its cast row
   is cancelled for the casts to `TIME`, owed to 340, after the `DATE` one runs). Nineteen coverage
   rows in two families. `VarkaTimestampNtzSuite` runs the casts through queries: `try_cast` at a
   day past the guard declines one batch of three and nulls the row, `CAST` raises in LEGACY and
   ANSI, and the cast to `DATE` answers java.time's day at the long range's ends. `year(ntz)`
   declines with "day producer the calendar range analysis does not bound", pinned for 340. The
   flipped tests of 0.6 hold their NTZ halves fused and their zoned halves declined, the long-lane
   one now checking which conjunct the kernel took.
6. **Generated files (3.6).** `coverage.json`, the docs tables, `width_audit.json`,
   `emitted_bytes.json`, `casts_fused.tsv`, `casts.tsv`, `census.tsv` and `types.tsv`, regenerated
   again after the merge with row 480.

### 10.2 What differed from the plan

1. **Row 484's contract ran int-lane kernels only.** 0 and 3.1 took it as done for any type: its
   batches were `int[][]`, its kernels ran through `runLoadedNarrow` with the int literal table, and
   its lane came from a three-type ladder. `VarkaTypeConformance` now builds full-width batches and
   runs a kernel driven at the long lane through a `runLoadedLong` that also compares with Spark,
   with both literal tables; the lane is the registry's and a literal's spelling a function, since
   NTZ has no `%d` form. The three existing suites behave as before. Row 485's six suites now have
   their harness (its row says so).
2. **`IN` needed an arm.** `IN` and `InSet` compile only for the int lanes' types, and the bigint
   `IN` is left declined on purpose by a test, for the bigint operation rows. NTZ has a long-lane
   twin of the EQ chain of its own (`compileLongInList`).
3. **The overflow exemption** is the census: the check keeps only the operators the census admits
   for the type, so `BOOLEAN`, `DATE`, `TIME` and `TIMESTAMP_NTZ` pass it with no form (row 485's
   merge replaced the first `hasArithmetic` flag). The null-handling check takes the type's own
   pair form, for NTZ `x - y` over half the long range, owed to row 339 until it fuses.
4. **The new divisor's past-the-bound check.** `long_divide.smt2` also states, per divisor, the
   first dividend past `2^53` where the conversion form goes wrong. For 10,546,875 both solvers find
   it still exact there; the claim is a fact about the TIME divisors and is skipped for this one,
   whose caller states a bound near `2^50`.
5. **The exhaustion is of the lowering's arithmetic, not of a kernel over `2^32` rows.** A kernel
   run per day compared row by row would take hours; the compiled tree's guard and factor are read
   and checked over every day by the 128-bit product's high half, and `multiplyExact` itself over
   three windows. The coverage entry names it so.
6. **The coverage table's columns had five homes.** The column map in `coverage.json` was written by
   hand; it is now derived from `VarkaCoverageSuite.columns`. `VarkaEmittedBytesSuite` and
   `VarkaWidthAuditShapes` keep copies (a column the bytes suite lacked put the NTZ rows in
   `coverage_rows_skipped` silently), and the differential and Spark fuzz fixtures build the
   columns. `ADDING_A_TYPE.md` step 11 lists the five.
7. **The fixtures derive the NTZ columns** from a date and a time. In the differential suite a day
   the cast cannot take gives the epoch rather than a null: over a null `ntz` the optimizer's
   `isnotnull(ntz)` conjunct decides whether `d < ntz` is evaluated, Spark raised where Varka's
   filter answered, and the two engines need not take conjuncts in one order.
8. **`d2 - d` lost its total domain.** The new edge rows at `+-106,751,992` days put it outside its
   domain; the entry is gone and its outside partition is checked.
9. **The census doctest** counted the landed types by hand and broke on a new one; it now builds
   every suite but one.

### 10.3 Predictions (4)

1. **No existing shape's bytes move: held.** Only new shapes were added; the `option_arms` digests,
   each over every shape, moved with them. The same held after the merge with row 480.
2. **`varka_new_lane.py --check` passes for `TimestampNTZType`: held** (3 sites checked).
3. **The census counts NTZ fused for 2.2's operators and the two casts: held,** 16 of its 54
   entries, the rest owed to 337, 339, 340 and the other types' rows.

### 10.4 Verification

The gate before the merge: every step ok in 1674 s (wide 1534 s, narrow 1551 s). After the merge
with row 480: every step ok in 1777 s (wide 1613 s, narrow 1649 s).

The option matrix over the new and changed catalyst suites, all 51 configurations and the defaults,
failed five tests under `lanesOverride=4` and `narrowHalfSpecies=true`: the two NTZ suites' kernels
of both lanes need an int species of the long species' width, which the shared test JVM refuses
beside the one it holds (VARKA-246). The suites now run in JVMs of their own, as the narrow
conformance suites do; those two configurations are still to be rerun. The sql suites
(`VarkaTimestampNtzSuite`, `VarkaLongLaneSuite`, `VarkaCoverageDifferentialSuite`) ran under 29 of
the 52 configurations before the run was stopped: no NTZ test failed; under `validityByWidth=false`
two narrow rows of row 480, `CAST(y1 AS INT) * CAST(y2 AS INT)` and its `SMALLINT` twin, failed,
which this row does not touch. The rest of the matrix is owed. The review agent's findings, none of
them a wrong answer, are fixed.
