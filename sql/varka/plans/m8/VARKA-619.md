# VARKA-619: A narrow column's null test fuses through any chain of widenings

## 1. Where this came from

Milestone 8's row 619, found on 20 November 2026 by row 352's Spark-level fuzzer the first time it
drew the `TINYINT`, `SMALLINT` and `BOOLEAN` fixture columns (`VARKA-352.md` 10.3, on the local
branch `varka-352-fuzz-types`). At seed 20261008, iteration 153, ANSI on, the filter

    NOT (coalesce(b, b2)) OR (y1 IS NOT NULL) OR (dt IN (INTERVAL '2' DAY, INTERVAL '1' DAY))

over a `TINYINT` column `y1` and a day-time interval `dt` made Varka raise `INTERNAL_ERROR` where
the row engine answers; a run of 700 at the same seed found it again at iteration 568, over the
`SMALLINT` column `s2`. The shrunk reproducer is `sql/varka/fuzz/spark/varka-619-20261008-153.sql`.
A user reaches it with any such filter: the failure is at plan time, so the failure-fallback
contract (VARKA-275) raises instead of degrading.

## 2. The cause

The condition compiler lifts a narrow condition to the int lane beside an int one
(`VarkaConditionCompiler.onIntLane`, VARKA-329), where `y1 IS NOT NULL` becomes
`isNotNull(widen:INT col:byte)`, and lifts an int condition to the long lane beside a bigint one
(`onLongLane`, VARKA-28), which widens the test's child once more. A narrow null test inside an int
disjunct of a long-lane filter is therefore `isNotNull(widen:LONG (widen:INT col:byte))`.

`Analysis.analyze`'s `IsNotNull` case peeled one `WidenLane` to find the column. It found
`widen:INT col:byte`, which is no column, and threw `IsNotNull child must be a ColumnRef, a widened
one, ...` (`IllegalArgumentException`, raised as `INTERNAL_ERROR`); past that check, it would not
have marked the byte column as read for its validity word alone (`validityOnlyNarrow`), and the
lane rule would have refused the byte column as a narrow node in a long emission. The brief and
row 619 named the second; the compiler test's red run met the first.
The plan (`VarkaKernelPlan.refusalOf`) had admitted the tree, because its walks
(`needsNarrowSpecies`, `narrowValuesIn`) stop at a null test over a value whatever lies below.

## 3. The fix

`Analysis.unwidened` peels every widening of a chain, and the `IsNotNull` case reads the column
through it; at the long lane it marks every narrow node of the chain as validity-only, the column
and any narrow widening above it (a byte widened to the short lane first, which the IR admits and
the compiler does not build today). The emitter needed nothing else: the word reference of a
widening is its child's (`Slots`, `Analysis.wordOwner`), recursively, so the test reads the
column's word at any depth.

`VarkaKernelPlan.readsColumnWord`, the double kernel's admission of a widened null test, peeled one
level the same way; it reads through `unwidened` too, so the plan and the analysis keep agreeing.

### 3.1 The other sites that peel one widening

Every `WidenLane` match in the emitter and the plan was read for the same mistake:

| site | what it does | verdict |
|---|---|---|
| `Analysis.analyze`, `IsNotNull` | the column under the test | the bug; fixed |
| `VarkaKernelPlan.readsColumnWord` | the double kernel's widened null test | same mistake; fixed (it declined, never crashed) |
| `VarkaOperandPosition.admits` | `date_add(d, tinyint_column)`: a narrow column widened to the int lane is a column at a day-offset position | one level on purpose; a deeper chain (`CAST(y AS SMALLINT)` as the offset) is refused, which declines the expression to the row engine; recorded, not changed |
| `Analysis.wordOwner`, `pureWord`, `Slots.wordRef`, the demand pass, `VarkaRangeAnalysis.range`, `VarkaVectorWalk.emitValue` | each widening forwards its child's entry or value | recursive per node; any depth already |
| `VarkaKernelPlan.loweredAtLong` | a narrow child of a widening must be a column or a narrowing | a three-widening chain under a long kernel that also needs a narrow species is refused there; the compiler builds no such chain; a decline, recorded |

## 4. Tests

- `VarkaEmitterNarrowSuite`, "a narrow column's null test read through a chain of widenings emits
  in a bigint kernel and gives the reference's answer": the byte and the short column through two
  widenings, and a byte through three (via the short lane), each alone and in an `OR` with a bigint
  comparison, both arms, against `VarkaReference`, dense and masked bodies.
- `VarkaExpressionCompilerSuite`, "a narrow column's null test in an int disjunct beside a bigint
  conjunct emits": `ll > 0 AND (by IS NOT NULL OR i < 3)` and the same over `sh` compile to the
  two-widening chain, both conjuncts fuse, and the kernel emits.
- The reproducer, now `status: regression`, replayed by `VarkaSparkReproducerSuite`.

## 5. Outcome
