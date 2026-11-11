# VARKA-578: the null family over the floating, boolean and TIMESTAMP_NTZ types

Row 578 of `PLAN.md`. Found on 7 November 2026 from the census, after row 329 fused the family
over `TINYINT` and `SMALLINT`.

## 0. What exists now (admission check)

1. **The census.** `nvl`, `ifnull`, `nvl2`, `nullif`, `between`, `equal_null`, `nullifzero` and
   `zeroifnull` list `FLOAT`, `DOUBLE`, `BOOLEAN` and `TIMESTAMP_NTZ` among the types they accept,
   and none of them as fused. Their building blocks already fuse over those types: `coalesce`,
   `if`, `CASE WHEN`, the comparisons and `<=>`, with coverage rows, from rows 331, 338 and 341.
2. **What each rewrite is.** The optimizer turns every one of them into those blocks: `nvl(a, b)`
   and `ifnull` into `coalesce(a, b)`, `nvl2(a, b, c)` into `if(a IS NOT NULL, b, c)`,
   `nullif(a, b)` into `if(a = b, NULL, a)`, `between` into two comparisons and an `AND`,
   `equal_null` into `<=>`. So a pair needs new code only where the rewrite holds something the
   blocks do not: the `NULL` of `nullif` (and a `CASE WHEN` without `ELSE`), a value that is null
   on every row.
3. **The null value.** `NullValue` (the IR leaf for that `NULL`) carried the integral lanes
   and refused the floating ones in its constructor, and the compiler's null leaf declined
   `FLOAT`, `DOUBLE` and `TIMESTAMP_NTZ` by name. The emission is lane-generic, a broadcast
   zero in the lane's species with a clear validity word, so the floating lanes need only a zero
   of their own species (`Lane.pushZero`; `pushScalar` is integral by contract). An NTZ is the
   long lane's null. A `BOOLEAN` choice is a condition, whose null `CondConst` already is.
4. **Spark's equality.** `nullif` and `between` over floating columns compare under Spark's
   order: NaN equals NaN and is above every other value, `-0.0` equals `0.0`. The comparison nodes
   are the ones row 341 built and proved, so the family inherits them; the coverage differential
   (`VarkaCoverageDifferentialSuite`) runs every new row against Spark over fixtures that hold
   both NaN payloads and both zeros.

## 1. What was built

* `NullValue` admits every lane; the compiler's null leaf answers `FLOAT`, `DOUBLE` and
  `TIMESTAMP_NTZ`; `Lane.pushZero` is the zero scalar of any lane.
* 37 coverage rows in six families (`VarkaCoverageSuite`), their generated files, and the
  census claims of the eight entries.
* The null at the lanes the IR fuzzer's nulling draw does not reach: a null-lane draw of its own
  (`drawNullingLaneShape`, `NULLING_LANE_FUZZ_SEED`) over `TINYINT`, `SMALLINT`, `FLOAT` and
  `DOUBLE` columns through the cast draw's harness, so no committed corpus moves; and `nullif`
  and a `CASE` without `ELSE` in every `VarkaTypeConformance` suite's choice check. An NTZ's
  null is the long lane's, which the nulling draw already reaches.
* The decimal declines that named row 333 for a null it built now name rows 348 and 359.

## 2. Declines that stay

* **`nullifzero(f)` and `zeroifnull(f)` over `FLOAT`.** Type coercion compares or coalesces a float
  with the `INT` literal `0` in `DOUBLE`, so the analyzed tree is
  `IF(CAST(f AS DOUBLE) = CAST(0 AS DOUBLE), CAST(NULL AS FLOAT), f)`: a `DOUBLE` condition over a
  `FLOAT` value, which mixes the two floating lanes in one kernel, and
  `coalesce(CAST(f AS DOUBLE), 0.0D)`, whose operand before the last is a conversion and not a bare
  column. Both are pinned in `VarkaExpressionCompilerSuite`. Closing them is the floating
  conversions' work (row 343), not this row's: the condition `CAST(f AS DOUBLE) = 0.0` is
  `f = 0.0F` for every `FLOAT` (the widening is exact), but proving and using that is a rewrite of
  its own.
* **Every `DECIMAL` pair**: rows 348 and 359 own them.

## 3. Found on the way

The FLOAT decline fires in the choice's `sameLane` check, before any lane crossing (row 343) is
considered: a choice whose condition is a widened compare (`CAST(f AS DOUBLE) = 0.0D`) is refused
though the condition equals `f = 0.0F` for every FLOAT. A rewrite of that compare to the column's
own lane, or a conversion arm in `coalesce` beside the narrow-column widening arm, would close
both; it is not on `VARKA-343.md` 7's list of declines that stay, so it deserves a row of its own.

*9 November 2026, correcting sections 2 and 3:* `nullifzero(f)` was not refused in a session. The
pinned tree was the analyzed one; the optimizer's `UnwrapCastInBinaryComparison` moves a widening
compared with a literal to the literal's side wherever the cast is an up-cast, and FLOAT to
DOUBLE is one, so a session hands Varka `IF(f = 0.0F, NULL, f)`, on the float lane. Only
`zeroifnull(f)` needs the conversion arm, and section 4 leaves it declined.

## 4. The second part (9 November 2026, branch `varka-578-rest`)

The row's status listed two pieces left: `nullifzero` and `zeroifnull` over `FLOAT`, and every
decimal pair. Section 2 said both stay; the row's status said they were left. The conservative
reading taken: build what needs no decision of another row, and pin the rest.

**Built.**

* **The decimal null value.** `VarkaDecimalCompiler.literal` compiles a null constant to the long
  lane's `NullValue` at the constant's type, a `CASE` without an `ELSE` takes that null as its
  `ELSE`, and `valid` answers false for it (it answered true for every constant, which was safe
  only while a null constant declined). Nothing else was missing: coercion gives every operand of
  `nvl`, `ifnull`, `nvl2`, `nullif`, `between` and `equal_null` the wider decimal type, whose
  rescales, choices, comparisons and null tests row 348 built. The int zero of `nullifzero` is
  `DECIMAL(1, 0)` (`literalPickMinimumPrecision`, on by default), so its compare stays within the
  column's type wherever the column has an integral digit (a `DECIMAL(p, p)` compares at
  `DECIMAL(p + 1, p)`, which a session's `UnwrapCastInBinaryComparison` moves back to the
  column's type); that of `zeroifnull` widens as `DECIMAL(10, 0)`, so its comparison fuses to
  scale 8, and its null test at every scale, past 18 digits as the next item says.
* **The null tests past 18 digits** (added after the review, 10 November 2026). A null test of a
  wide value reads its validity alone (`wideProbe`, then `valid`), and needs no words for the
  `NULL` of `nullif` and `nullifzero` or for a missing `ELSE`: the probe passes both and `valid`
  answers false for them, as it does within 18 digits. So `nullif(c, d) IS NULL`,
  `nullifzero(c) IS NULL` and `CASE WHEN c < d THEN c END IS NULL` fuse over `DECIMAL(20, 2)`,
  and under a `coalesce`, a choice or a pick too. `valid` folds a nullable constant through the
  same guarded evaluation `literal` uses, declining where it fails, and reads a non-nullable one
  unevaluated.
* **Coverage rows.** `nullifzero(f)`, from the tree the optimizer hands on (the row's built tree
  is `VarkaSqlResolve.optimizeIn` over the resolved call, and the other suites run its SQL,
  `if(f = 0.0F, NULL, f)`); and 17 rows in a family of their own over the decimal columns,
  under a comparison or a null test, since a decimal value is no output before 348b.
* **Tests.** `VarkaDecimalValueSuite` holds 14 forms over `DECIMAL(9, 2)` and `DECIMAL(9, 4)`
  columns to Spark's `eval`, over values equal after the rescale, one unit apart, zeros, the
  precision's bounds and nulls; and pins, per form, which past 18 digits fuse and which decline
  naming 359 or 360. `VarkaDecimalPairSuite` holds 13 null tests of the family over
  `DECIMAL(20, 2)` beside `DECIMAL(20, 2)` and `DECIMAL(38, 2)` to Spark's `eval`, on the word
  edges at every width.
  `VarkaExpressionCompilerSuite` pins the unwrap of `nullifzero(f)` and keeps `zeroifnull(f)`'s
  decline.
* The census notes of `nullif`, `nullifzero` and `zeroifnull`, and `float` among `nullifzero`'s
  fused types. No entry gains `dec`: the milestone's rule (`PLAN.md` 2.8) counts a decimal entry
  fused once rows 359, 360 and 363 land.

**Declines that stay.**

* **`zeroifnull(f)` under ANSI**: `coalesce(CAST(f AS DOUBLE), 0.0D)`, a `DOUBLE` choice whose
  first operand is a conversion (`AnsiTypeCoercion` takes `DOUBLE` for a float beside an int; the
  legacy coercion keeps `FLOAT`, so there the call is `coalesce(f, 0.0F)`, the shape of the other
  floating rows). Its null test would be the float column's, crossed to the double lane, which
  is a crossing of one kind; `VARKA-343.md` 2.2.1 leaves those out by decision ("one kind, so not
  crossed"), and changing that is row 343's call, not this row's. Section 3's conversion arm is
  the way, and it stays a finding for a row of its own.
* **A decimal past 18 digits as a compared value**: `nvl(c, d) > 0.00`, `zeroifnull(c) = d`,
  `nullif(c, d) <=> c` and `nullif(c, d) IN (c, d)` over `DECIMAL(20, 2)`, and a null constant
  compared at a wide type. Row 359's pair (#1413, merged) compares and null-tests columns,
  widening casts and constants, but holds no choice and no null as a value: those are its value
  half, part 359b. Each declines naming 359. A rescale into the wide type, as coercion gives
  `nullif(a, e)` of a `DECIMAL(18, 2)` and a `DECIMAL(16, 9)` at `DECIMAL(25, 9)`, is row 360's
  as well and names both.
* **A decimal null family value as a projection's output**: row 348's part 348b.

