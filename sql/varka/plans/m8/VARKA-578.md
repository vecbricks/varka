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
