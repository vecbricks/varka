# VARKA-122: a comparison over a bare int column

*Milestone 5, section 2.57. Opened 16 September 2026 by VARKA-120; implemented
17 September 2026.*

## 1. Where this came from

VARKA-120 taught `VarkaCoverageSuite` to require *every* conjunct of a predicate
row to fuse, and the table's `year(d) = 2021 AND i > 0` stopped qualifying. Run
end to end its plan was a row `Filter (i > 0)` above
`VarkaFilterColumnarToRow (year(d) = 2021)`: `compare` sent every non-literal
operand through `compileNode`, whose value leaves are date columns and fused int
fields, so a bare `IntegerType` column declined with "not a date column" while
`month(d) > 6` fused. The int32 lane already owned the column - VARKA-63's
arithmetic reads it through `intOperand` - so this was one operand rule.

## 2. The admission check, done

**The rule is one line, and the guard question is nil.** A comparison produces a
mask rather than a value, so nothing can leave the int range; VARKA-63 needed an
overflow mode for the same lane because its result is a value.

**The emitter needs nothing.** `Compare`'s operands are already `VarkaVectorIR`
values and a `ColumnRef` is a `ColumnRef` whatever its Spark type; the lane is
the same int32 one the date columns use.

**What the first run of the differential found, and it is the half this task
would have shipped broken.** Spark's optimizer infers `isnotnull(i)` beside any
null-intolerant predicate on `i`. With the comparison admitted and the validity
predicate still refused, `i > 0` produced a fused comparison with a *row*
`Filter isnotnull(i)` above it - the kernel doing the compare and the row engine
still visiting every row to check the null. `compileValidity` accepts a bare
`IntegerType` column too, for the same reason and with the same one-line shape:
it is the same column, the same lane and the same per-lane-group validity word
that a date column's `IS NOT NULL` already reads.

## 3. The design

### 3.1 The mechanism

`compare`'s `operand` gains one case - a `BoundReference` of `IntegerType`
becomes the int column leaf - beside the int-literal case that was already
there. `compileValidity` gains the same case ahead of its `compileNode`
call, keeping its "must be a bare column" rule and widening only the type.

### 3.2 What is deliberately unchanged

`compileNode`'s value leaves stay `DateType`: a bare int has no meaning as a
*date* operand, and widening there would admit `date_add(d, i)`'s offset as a
date. Narrower columns stay out - a `ShortType` or `ByteType` column is a lane
the kernel does not read, and admitting one would compare whatever the evaluator
placed in the int column beside it.

## 4. Files

* `VarkaExpressionCompiler.scala` - the two operand rules.
* `VarkaExpressionCompilerSuite.scala` - the new cases, and two existing tests
  updated (see section 5).
* `VarkaCoverageSuite.scala`, `sql/varka/coverage.json`, `docs/sql-varka.md` -
  five rows: the conjunct restored, `i > 0`, `i = 5`, `month(d) > i`,
  `i IS NOT NULL`.
* `sql/varka/emitted_bytes.json` - regenerated for the table's new rows.
* `sql/varka/plans/m5/PLAN.md` - row 122.

## 5. Tests, and what each is for

* **The comparison, six spellings**: column against literal both ways, two int
  columns, and a column against a fused int field both ways - the shapes that
  differ in which side the new rule fires on.
* **The conjunct**: `year(d) = 2021 AND i > 0` fuses whole, with two specs and
  two fused conjuncts, so nothing is left for a row filter.
* **The validity predicate**, alone and in the shape the optimizer produces.
* **The refusals that remain**: a `ShortType` and a `ByteType` column still
  decline, and a validity predicate over a computed node still declines.
* **Six existing tests were updated rather than deleted**, two in the compiler
  suite and four in `VarkaFilterExecSuite`. All six used `i > 5` as the example
  of a conjunct that cannot fuse - in the split between fused and residual
  conjuncts, in the fusion report's EXPLAIN line, and in the columnar rule's
  rewrites. They now use a `ShortType` column, which is still out of the lane's
  reach. A test that passes only because a feature is missing has to be
  re-pointed when the feature lands, or it silently stops testing what its name
  says. The `sql/core` four needed a column list of their own rather than a
  wider shared one: several tests in that suite number output ordinals against
  `Seq(attrD, intAttr)`, and widening it in place broke three of them - the
  shared-fixture mistake, made and undone here.
* **The standing oracles**: the coverage suite's three checks, the emitted-bytes
  oracle (regenerated for four new rows and one renamed, with **no fuzz block
  moved** - the emitter is untouched), and `VarkaCoverageDifferentialSuite`,
  which runs all 61 rows through both engines under both consumers.

## 6. The measurement

None. No committed number moves: the emitter is not touched, and the fuzz
digests confirm it.

## 7. Risks

* **A narrower column admitted by accident.** The rule names `IntegerType` and
  the tests pin `ShortType` and `ByteType` as still declining.
* **A date column compared with an int one.** The analyzer does not produce it;
  a cast would arrive as a `Cast` node and go through `compileNode` as before.

## 8. Sequencing

1. The two rules. 2. The suites. 3. The table and the regenerations. 4. This
plan and row 122. 5. The gate.

## 9. Outcome

Done, 17 September 2026. Four new coverage rows and one restored, 61 rows
through the differential under both consumers, and the emitter untouched.

**The differential earned its keep on the first run.** It found the
`isnotnull(i)` residual described in section 2 - a shape that fuses in the
compiler's own suite and still leaves a row filter in a real plan - which is
exactly the gap between "the conjunct compiles" and "the query has no row filter
above the kernel" that VARKA-120 built it to close.

**The gate caught what the catalyst suites did not.** Four `sql/core` tests
failed on the first full run for the same reason the two compiler ones did, and
they are the tests that read an EXPLAIN line and a columnar rewrite - the places
where a residual conjunct is visible to a user. Running the narrow suites alone
would have shipped them red.

**The emitted-bytes oracle reported the removed row, not only the new ones.**
Its failure named `row gone year(d) = 2021 AND month(d) > 6` beside the four
`new row` lines. That two-direction diagnostic was added on 17 September after a
review found the suite walked only the newly generated document; this is the
first change to exercise it, and without it the reader would have seen four
additions and no sign that a row had left the table.
