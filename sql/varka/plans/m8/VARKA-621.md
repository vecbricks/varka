# VARKA-621: `abs` over an int fuses

## 1. Where this came from

Milestone 8's mid-course review (`PLAN.md` 12) looked at why the integral types trail `date` and
`timestamp_ntz` in the census, and read the unfused `int` pairs by name: `abs`, `ceiling`,
`positive` and `factorial` were among them with an empty reason. A probe on 20 November 2026 at
`416bb8d1a69` found that `abs(i)` over an `INT` column is not fused: a coverage row for it fails
"every documented expression compiles to a fused kernel" with "no entry fused". The neighbouring
types fuse it (`abs` over `TINYINT`, `SMALLINT`, `BIGINT`, the floating types and both intervals),
so this is the one missing arm of a family, and it moves one `int` pair of the census.

## 2. What was built

`VarkaNodeCompiler` gains `case Abs a when isInt(a)`: the int negation's node with its `abs` flag,
on the int negation's terms. Spark's `abs` is `x < 0 ? negateExact(x) : x` under ANSI and
`Math.abs` otherwise, whose `abs(Int.MinValue)` is `Int.MinValue`. The one overflowing input is the
minimum, so any bound on the operand takes the ANSI check off. There is no `try_abs` in Spark.

`VarkaVectorWalk.emitIntNeg` refused an `abs` off the narrow, floating and long lanes. The int lane
now falls through to the lanewise `ABS` after the same compare that checks the minimum. The lanewise
`ABS` on int lanes is `vpabsd`, available on an AVX2 host, which is why the long lane's
`decimalAbsLanewise` question (it needs AVX-512) does not arise here.

`VarkaCoverageSuite` gains the row `abs(i)`, with the domain `i > Int.MinValue`, and the generated
coverage table and `coverage.json` carry it.

## 3. What was found and not built

* **`positive` stays unfused.** Its census entry is plumbing: `UnaryPositive` is
  `RuntimeReplaceable`, so the compiler asks again for its child. A coverage row `positive(i)` is a
  bare column, which no entry fuses, and `positive(i) + 1` declined in the coverage harness with
  "no entry fused" for a reason not read in this row. It needs its own look, and is left open.
* **`ceiling` and `factorial`** are not touched: `ceil(i)` is credited as `Ceil` over a double by
  its cast, and `factorial` has only its baseline (row 344).

## 4. Tests

`VarkaCoverageSuite` checks that the row compiles to a fused kernel and that its domain column agrees
with the guard the kernel carries. The answers are compared by `VarkaCoverageHarnessSuite`, which
runs every coverage row on Spark's row path and on Varka's over the extremes fixture, `Int.MinValue`
included, in LEGACY and ANSI: it passes, 2595 tests with none failing. The bytes oracle and the
census are regenerated.

## 5. Done when

`abs(i)` fuses in LEGACY and ANSI with Spark's answers and error, the census credits it, and the
regenerated files are committed.
