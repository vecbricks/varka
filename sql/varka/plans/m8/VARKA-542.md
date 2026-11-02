# VARKA-542: A filter's fusable conjunct after a decimal cast that cannot raise fuses

## 1. Where this came from

Milestone row 542 (`m8/PLAN.md`, added 1 November 2026 by the owner's word), from row 365's
read-path suite (`VARKA-365.md` 11.5, open point 2, on branch `varka-365-reader-casts`): over a
cached table, `WHERE CAST(a AS INT) IN (0, 2147483647) AND date_add(d, i) > DATE'2000-01-01'`
fused no conjunct, so the whole filter ran on Spark's row path, though a projection fuses
`date_add(d, i)` over the same table. A draft that moved the `date_add` conjunct first and gave it a
literal offset fused it, and which of the two changes decided was not known. There `a` is a
`DECIMAL(18, 18)`.

## 2. The admission check, done

The shape and its variants ran at master (`5c660201b35`) on `VarkaSharedSessions`' two sessions,
under the session default, ANSI on (the same run's `CAST_OVERFLOW` shows it), over a cached table
with `a DECIMAL(10, 2)`, `d DATE`, `i INT`, `f DOUBLE` and `s STRING`. Each line is
`VarkaFusionReport.predicateLines` of the row `FilterExec`'s condition, or the Varka node's
verbose `EXPLAIN` where one was planned, shortened to the conjunct and its verdict:

| `WHERE` | `CAST(a AS INT) IN (0, 2147483647)` | the other conjunct |
|---|---|---|
| cast first, `date_add(d, i)` | residual (unsupported predicate) | residual: "Spark evaluates it after (CAST(a AS INT) IN ...), which can raise an error; fused, it would run out of Spark's order" |
| `date_add(d, i)` first | residual (unsupported predicate) | fused |
| cast first, `date_add(d, 3)` | residual | residual, the same order reason |
| `date_add(d, 3)` first | residual | fused |
| cast first, `d > DATE'2000-01-01'` | residual | residual, the same order reason |
| `date_add(d, i)` alone, `date_add(d, 3)` alone | - | fused |
| `s = 'x'` first | residual (string column) | fused |
| `f / 0 > 1` first | residual (division by a literal zero) | residual, the order reason |
| `CAST(s AS INT) = 3` first | residual (unsupported expression) | residual, the order reason |

So **the order decides, and the offset does not**: a column and a literal offset behave alike in
both orders, and even a bare date comparison is demoted behind the cast. The cause is neither the
filter compiler's admission of `date_add` nor its column offset. It is `VarkaConjunctOrder`
(VARKA-380): the cast conjunct does not fuse, and `canRaise` counts it as able to raise, because a
decimal-to-integer cast is not one of `Cast.canUpCast`'s widenings, so `behindRaising` demotes
every fused conjunct Spark evaluates after it.

Whether the demotion is required depends on whether the cast can raise. Spark's ANSI cast of a
decimal to an integer type truncates toward zero and raises only when the integer part does not
fit (`Decimal.roundToInt`, `roundToShort`, `roundToByte` through `roundToNumeric`, and
`roundToLong` through `longValueExact`). A `DECIMAL(p, s)` holds at most `p - s` integer digits,
so the cast cannot raise into a `TINYINT` at `p - s <= 2`, a `SMALLINT` at 4, an `INT` at 9 and a
`BIGINT` at 18. Row 365's `DECIMAL(18, 18)` has none, so its cast is always 0, and this repro's
`DECIMAL(10, 2)` has eight. Outside ANSI none raises: LEGACY wraps (`Decimal.toInt` and its
siblings), and TRY either takes the legacy cast or catches the error and answers null
(`Cast.ansiEnabled`, `canUseLegacyCastForTryCast`). The demotion was therefore conservative and
not required for these types. For a decimal with more integer digits than the type holds it is
required, and stays: row 365's `b`, a `DECIMAL(18, 8)` with ten, overflows an `INT`.

What the check would have rejected: had the column-offset shape declined with `date_add` first,
or with the cast conjunct absent, the cause would have been the compiler's `date_add` admission
in a filter, and the fix would have been there.

## 3. The design

### 3.1 The rule

`VarkaConjunctOrder.errorFreeNode` decides a `Cast` of a decimal into an integer type by the
digit bound alone (`decimalTruncationFits`): any mode but ANSI, and under ANSI a `p - s` within
the type's digits. Every other `Cast` passes only if it widens (as before). The decimal case must
not consult `Cast.canUpCast` first: `DecimalType.isTighterThan` admits a decimal into an integer
type at `precision < ` the type's decimal precision and scale 0, which for a `BIGINT` (a
`DECIMAL(20, 0)`) admits a `DECIMAL(19, 0)`, whose `9999999999999999999` overflows under ANSI
(`Decimal.roundToLong`, through `longValueExact`). Taken as a widening, that cast counted as
unable to raise, and a fused conjunct after it dropped a row Spark raises on; this was wrong at
master too, from VARKA-380. For the other three types `canUpCast`'s bound and the digit bound
agree. `canRaise` is called only by `behindRaising`, so the change otherwise only keeps a fused
conjunct fused where it was demoted. It changes no kernel, no emitted byte and no answer on
well-formed data. A corrupt slot holding more digits than its precision is outside the rule, as
the owner settled for row 365 (`VARKA-365.md` 13). The bound trusts the declared type: a
`Decimal` value wider than its type, such as an oversized internal `Literal` or an unvalidated
connector row, is an input Spark's own writers cannot produce, which Varka need not reproduce
vanilla on (the owner's rule of 1 November 2026).

### 3.2 What is deliberately unchanged

* The cast conjunct itself stays residual ("unsupported predicate"): fusing a decimal cast in a
  filter is row 365's reader work, not this row's.
* Other narrowing numeric casts (a `BIGINT` or a `DOUBLE` to an `INT`) still count as raising
  under every mode. LEGACY integral narrowing wraps too, but no finding asks for it, and it
  would be a separate case of the same list.
* `f / 0 > 1` and `CAST(s AS INT) = 3` can raise under ANSI, and the demotion behind them is
  required: Spark evaluates them first and raises on a row the date conjunct would reject.

### 3.3 Registered op counts

None: the change moves which conjuncts the compiler hands the kernel, never what a kernel is
emitted from for a given set, so no op count or `emitted_bytes.json` entry moves.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/.../codegen/VarkaConjunctOrder.java` | `decimalTruncationFits`, and the cast case of `errorFreeNode` |
| `sql/catalyst/.../codegen/VarkaConjunctOrderSuite.java` | the cast boundaries per type and mode, and the shape's variants through `explainPredicate` |
| `sql/core/.../execution/VarkaDifferentialSuite.scala` | the shape end to end against Spark, both orders, both offsets, a cast that can raise |
| `sql/varka/plans/m8/PLAN.md` | row 542 |

## 5. Tests, and what each is for

* `VarkaConjunctOrderSuite`, "a decimal cast to an integer type cannot raise when the type holds
  its integer digits, or outside ANSI": for each of `TINYINT`, `SMALLINT`, `INT` and `BIGINT`,
  `DECIMAL(held, 0)` and `DECIMAL(held + 2, 2)` cannot raise and `DECIMAL(held + 1, 0)` and
  `DECIMAL(held + 3, 2)` can, under ANSI (so `DECIMAL(19, 0)` into a `BIGINT` can); neither can
  under LEGACY or TRY; `CAST(DECIMAL(18, 18) AS INT) IN (...)` cannot and `DECIMAL(18, 8)`'s
  can; a decimal-to-decimal cast still can. It catches an off-by-one digit bound.
* The same suite, "a residual decimal cast that cannot raise leaves the date conjunct after it
  fused, ...": the shape's four orders and offsets through `explainPredicate`, fused as
  `[false, true]` and `[true, false]`; with the `DECIMAL(18, 8)` cast first, `[false, false]` and
  the reason carries `OUT_OF_ORDER`, and so with a `DECIMAL(19, 0)` cast to `BIGINT` first. At
  master the first case was `[false, false]` (section 2), and the `DECIMAL(19, 0)` one
  `[false, true]`.
* `VarkaDifferentialSuite`, "a residual decimal cast that cannot raise leaves the conjuncts after
  it fused, ...": a one-partition cached table with `a DECIMAL(18, 18)`, `b DECIMAL(18, 8)`, a
  nullable `d` (so the optimizer adds the null tests codegen moves) and `i`, under ANSI. With
  column and literal offsets, in both orders, the `a` shape plans the date conjunct in a Varka
  filter and answers Spark's rows; the `b` cast first keeps the date conjunct in the row filter
  under the order rule's reason and raises Spark's `CAST_OVERFLOW` on the row the date conjunct
  would reject, and so does `CAST(c AS BIGINT) > i` over a `c DECIMAL(19, 0)` holding
  `9999999999999999999` on that row (compared with a column, since the optimizer unwraps that
  upcast against literals: `CAST(c AS BIGINT) IN (0, 7)` plans as `c IN (0, 7)`, which cannot
  raise); the `b` cast written second fuses and answers Spark's rows. This one is the proof
  that the boundary still protects an error.

## 6. The measurement

None. The row is a fusion decision, and its effect is the plan shape the tests pin: no benchmark
measures a filter beside an unfused decimal cast, and none is owed.

## 7. Risks

1. A Spark decimal cast that raises within the digit bound. Read against `Decimal.scala`'s
   `roundToNumeric` and `roundToLong` (section 2); the differential test would show a disagreement.
2. A future `Cast` mode that raises outside ANSI. The rule reads `evalMode` and passes every
   mode but ANSI; a new raising mode would need its own case.

## 8. Sequencing

One commit: the plan, the rule, its tests and the row.

## 9. Outcome

Built as designed. The shape `WHERE CAST(a AS INT) IN (0, 2147483647) AND date_add(d, i) >
DATE'2000-01-01'` now fuses the date conjunct beside the residual cast, in either order and with
either offset, and a cast that can overflow keeps the demotion. After the fix the shape and its
variants (section 2's run with an overflowing `DECIMAL(29, 8)` cast added) fused the date
conjunct beside every `a` cast and kept it behind the overflowing one.

Suites, on the laptop at the defaults, all green: catalyst `VarkaExpressionCompilerSuite`,
`VarkaCoverageSuite`, `VarkaEmittedBytesSuite`, `VarkaWidthAuditSuite`,
`VarkaCoverageCompositionFuzzSuite` and `VarkaConjunctOrderSuite` (209 passed, 3 opt-in audits
cancelled as always); sql/core `VarkaFilterExecSuite`, `VarkaCoverageHarnessSuite`,
`VarkaCoverageDifferentialSuite`, `VarkaSparkFuzzSuite` and `VarkaDifferentialSuite` (1527
passed). `coverage.json`, `emitted_bytes.json` and `width_audit.json` did not move, as 3.3 says.

The review (1 November 2026) found the cast case asked `Cast.canUpCast` first, which admits a
`DECIMAL(19, 0)` into a `BIGINT` that overflows (3.1); a decimal into an integer type is now
decided by the digit bound alone, and the suites pin the scale-0 boundary per type and the
`DECIMAL(19, 0)` cast end to end.

Left for later: fusing the cast conjunct itself is row 365's reader work. Narrowing casts other
than a decimal into an integer type (3.2) still count as raising; no finding asks for them.
