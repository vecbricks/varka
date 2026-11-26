# VARKA-480: Fusion where a narrow value meets another lane

## 1. Where this came from

The `/code-review max` reviews of #987 and #989 (23 October 2026, `VARKA-329.md` 9.8, findings
987-2, 987-10 and 989-13) found three places where a narrow (`TINYINT`, `SMALLINT`) value keeps a
projection from fusing as much as it can. A narrow entry first in a projection fixes the kernel's
lane, so a later `BIGINT` entry goes to the row engine instead of to a second kernel; a narrow
column read only for its validity, as in `IF(y1 IS NULL, l, l2)`, declines a `BIGINT` kernel; and
`VarkaRangeAnalysis` has no range for a narrow column, so ANSI `CAST(y1 AS INT) * CAST(y2 AS INT)`
declines although the product cannot overflow, while `arithOver`'s narrow exception is dead code
whose javadoc says the opposite. Milestone 8's goal is the new types (`m8/PLAN.md`); each of these
is a case where Varka hands a narrow-type query back to Spark's row path that it could run.

## 2. The admission check, done

What has to hold for each change to be correct, checked from the code and arithmetic (25 October
2026; paths under `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/`):

1. **A mismatched entry can run in a kernel of its own.** `VarkaExpressionCompiler.classifyKernels`
   already retries, as another kernel, the entries in `alone`, those over the budgets beside the
   others that fit them by themselves (`:337-386`, `:672`); `severalKernels` is on by default
   (`varka/VarkaEmitOption.java:447`). A lane mismatch (`VarkaKernelPlan.of`'s `LANE_MISMATCH`,
   `varka/VarkaKernelPlan.java:86-93`, `:305-318`) is refused at `admission` (`:1214-1224`) and the
   entry made residual, never put in `alone`. Each round of the loop fuses at least one entry, so
   adding mismatched entries to `alone` keeps it terminating. #987 (`a2fbcc74bb8`) did not change
   this rule - the lane-mismatch demotion predates it (VARKA-28/29) - it made narrow entries
   compile, so they now fix the lane where before they declined and the `BIGINT` entry fused. The
   int-first, long-later projection has the same gap today.
2. **Validity does not depend on the lane.** It is one bit per row
   (`varka/VarkaVectorIR.java:204-209`, `:257-259`; `varka/VarkaBodyEmitter.java:1536`), and a
   validity-only read never loads the data segment, which is sized by the input's own stride
   (`loadNarrowedSegment`, `:1518-1530`). `Analysis` already unwraps `IsNotNull(WidenLane(col))` to
   the column (`varka/Analysis.java:1417-1423`, the int-to-long form of VARKA-28) and `Slots` asks
   only for that column's word (`varka/Slots.java:1387-1390`). The refusal is `narrowRefusal`'s
   `drive == LONG` test (`varka/VarkaKernelPlan.java:273-277`), which runs before its own
   validity-only exemption (`:279-284`), and `Analysis`'s lane check (`varka/Analysis.java:1226`).
3. **A narrow column's range is its type's.** A byte lane holds a byte by representation, null rows
   included, so the bound needs no `columns.accept` and no `GuardedRange` (unlike `CONTRACT`,
   `varka/VarkaRangeAnalysis.java:125-136`). With it, `CAST(y AS INT) * CAST(y2 AS INT)` is at most
   127^2 = 16,129 for `TINYINT` and at most 32,768^2 = 1,073,741,824 for `SMALLINT`, both below
   `Integer.MAX_VALUE` = 2,147,483,647, so the multiply cannot overflow for either type and no check
   is needed. Today `range` reaches the `WidenLane` arm (`:272-273`), recurses into the narrow
   child and returns UNKNOWN at `:223`, which returns early for any non-INT node.
4. **`arithOver`'s narrow exception is dead.** `cannotOverflow` builds a candidate `IntArith` whose
   lane is the left operand's (`varka/VarkaVectorIR.java:111`), narrow for narrow operands, which
   `:223` answers UNKNOWN before any leaf; so the `!narrow &&` at `VarkaNodeCompiler.java:951-952`
   changes nothing, and the javadoc at `:929-936` ("two narrow operands always satisfy it") is
   false. The `!narrow` on the MUL decline at `:956` is live (the checked narrow multiply rests on
   it) and belongs to row 481.

What these checks would have rejected: a retry that could loop (it cannot, by 1), a validity read
that loads narrow data into a long lane (it does not, by 2), and a bound that a null row could
break (it cannot, by 3).

## 3. The design

### 3.1 Three admissions

1. **987-2, a kernel of its own.** `admission` returns the refusal's kind, not only its message; on
   `LANE_MISMATCH` the entry's position joins `alone`, so the next round of `classifyKernels` fuses
   it in another kernel, the others marked `OTHER_KERNEL` as for a budget split. The predicate path
   (`predicateOnce`) has one kernel by design and stays as it is.
2. **987-10, a validity-only narrow read in a long kernel.** `narrowRefusal` refuses the long drive
   only when `needsNarrowSpecies(root)` holds, that is when a narrow value is read; `Analysis`
   admits a narrow `ColumnRef` in a long emission when its only reader is a `WidenLane` under
   `IsNotNull`. A narrow value under a `BIGINT` kernel stays refused.
3. **989-13, the narrow column's range.** `VarkaRangeAnalysis` gives a narrow `ColumnRef` its
   type's range, above the early return at `:223` (a case inside that switch is unreachable), and
   the `WidenLane` arm carries it to the int lane. `arithOver`'s dead `!narrow &&` and its javadoc
   go; the MUL decline's `!narrow` stays for row 481.

None needs an emit option: no emitted code changes for a shape that fuses today. Every change
admits a shape that declined before, and the shape emits through the paths it already uses (a
second kernel, the validity word, an unchecked multiply).

*Correction (25 October 2026, while writing the range change):* one emitted shape that fuses today
does change. With a narrow column's range known, ANSI int arithmetic over widened narrow columns -
`CAST(y1 AS INT) + CAST(y2 AS INT)`, say - is proved unable to overflow and drops its check, so
such a row's bytes move (fewer operations, the same answers). Still no option: it is the existing
bound-removes-the-check rule applied to a bound it now has.

### 3.2 What is deliberately unchanged

The predicate path's single kernel (*correction, 25 October 2026, from the review:* its
conjuncts are not unchanged - the null test of a narrow column lifted beside a bigint conjunct now
fuses in the bigint kernel, so which conjuncts of `isnotnull(y) AND isnotnull(l) AND y = 3 AND
l > 0` fuse moves, the answers the same; a test pins it); narrow values under a `BIGINT` kernel (a value read still
declines); the checked narrow multiply and its `!narrow` MUL decline (row 481); the narrow
part-form loads (row 479).

### 3.3 Registered op counts

Not applicable: no node's lowering changes. What moves is admission, which section 5 counts.

## 4. Files

| file | what |
|---|---|
| `VarkaExpressionCompiler.java` | `admission`'s refusal kind; `LANE_MISMATCH` entries to `alone` |
| `varka/VarkaKernelPlan.java` | `narrowRefusal` refuses the long drive only for a narrow value read |
| `varka/Analysis.java` | a validity-only narrow `ColumnRef` in a long emission |
| `varka/VarkaRangeAnalysis.java` | a narrow column's type range |
| `VarkaNodeCompiler.java` | the dead narrow exception and its javadoc |
| `VarkaExpressionCompilerSuite.scala`, `varka/VarkaEmitterNarrowSuite.java`, `VarkaCoverageCompositionFuzzSuite.scala` | the pins that move (section 5) |
| `VarkaCoverageSuite.scala`, `sql/varka/coverage.json`, `docs/sql-varka.md` | two coverage rows |
| `sql/core/.../VarkaDifferentialSuite.scala` | the two-entry projection |
| `sql/varka/emitted_bytes.json`, `sql/varka/width_audit.json`, the reach report | regenerated |

## 5. Tests, and what each is for

1. **987-2:** a hand test in `VarkaDifferentialSuite` (`checkDifferential(..., expectFused = true)`,
   after the "mixed projection" test at `:1924`): a projection of a narrow entry and then a `BIGINT`
   entry fuses both, in two kernels, and answers as Spark does. A two-entry projection cannot be a
   coverage row. `VarkaExpressionCompilerSuite.scala:2583-2602` flips from RESIDUAL with
   `laneReason` to `KernelOutput`; the lane clause of `isCompositionDecline`
   (`VarkaCoverageCompositionFuzzSuite.scala:114-115`) goes.
2. **987-10:** a coverage row `if(y1 IS NULL, l, l2)` (and the `SMALLINT` twin) through
   `VarkaCoverageDifferentialSuite`; `VarkaEmitterNarrowSuite.java:408-409` (the lifted form) flips
   to admitted, while `:410-411` (the value form), `:374` and
   `VarkaExpressionCompilerSuite.scala:2062` (`by > 0`) stay refused, which is the test that a value
   read still declines.
3. **989-13:** a coverage row ANSI `CAST(y1 AS INT) * CAST(y2 AS INT)` for each narrow type, fused
   and unchecked; `VarkaEmitterNarrowArithSuite.java:191` (`127Y + 1Y` raises under ANSI) stays as
   it is, the test that the bound does not reach the narrow lane's own arithmetic.
4. Both widths, through the defaults gate and the matrix's scoped configuration.

## 6. The measurement

No speed claim is made, so no benchmark is needed: the change moves shapes from Spark's row path
to fused kernels and admission is what is measured. The reach report
(`sql/varka/coverage/reach/VarkaCoverageSuite.md`) and the coverage census record it, regenerated.

### 6.1 Predictions, registered before the run

1. The two new 989-13 rows and the two new 987-10 rows fuse; no existing coverage row stops fusing.
2. The coverage suite's composed projection gains kernels (its "several kernels" count rises),
   because it compiles every projection row as one projection and mixes lanes.
3. The narrow rows' domain predicates (`VarkaCoverageSuite.scala:272-276`, for example
   `CAST(y1 AS INT) * CAST(y2 AS INT) BETWEEN -128 AND 127`) fuse where they declined; the
   differential suite's filters over them then run fused, which is a change in what those runs
   compare, recorded in section 9.

## 7. Risks

1. A second kernel reloads the columns and recomputes the subexpressions the two share. That is
   the cost `severalKernels` already accepts for a budget split; test 1 checks the answer, and the
   reach report shows how many projections take it.
2. The body emitter could walk the admitted `WidenLane` under `IsNotNull` as a value and read narrow
   data into a long lane. Test 2 runs it differentially over null patterns, and the emitter suite's
   long-lane validity tests pin the word-only read.
3. The proofs (`sql/varka/proofs/range_analysis.smt2`, `narrow_range.smt2`) may want the new bound
   transcribed; checked when the range change lands, owed to row 490's register if not.

## 8. Sequencing

1. 989-13: the range and the dead exception, with its coverage rows (the smallest, and independent).
2. 987-10: the validity-only admission, with its rows and the flipped pins.
3. 987-2: the retry in a kernel of its own, the differential test, the flipped pins, the reach
   report.
4. The generated files regenerated once, the gate, the plan row.

## 9. Outcome

Built 25 October 2026, the three admissions as section 3.1 describes, with two corrections recorded
in 3.1 and 3.2 (ANSI int arithmetic over widened narrow columns drops its check; a narrow null test
beside a bigint conjunct now fuses in a predicate's kernel).

Predictions, scored:

1. The four new coverage rows fuse (`CAST(y1 AS INT) * CAST(y2 AS INT)`, its `SMALLINT` twin,
   `if(y1 IS NULL, l, l2)` and its twin), and no existing row stopped fusing: `VarkaCoverageSuite`
   passes, which fails a row that declines without a declared reason. Held.
2. The composed projection gains a kernel: the regenerated reach report reads "1 of 138 projection
   compilations needed more than one kernel". Held. (The report had been stale since it covered 65
   projection rows; it now covers 137, which is most of its diff.)
3. The narrow rows' domain predicates fuse where they declined: the differential suite passes with
   them. One position does not take the new product rows, a later `COALESCE` argument, where
   Varka compiles no bare int column as a value (`m10/SCOPE.md` item 55); the two rows join the
   ANSI form's list of int-valued rows that decline there.

Tests: the catalyst suites pass (219 on the first run, 183 on the rerun of the touched ones), the sql
suites pass (925), the gate and the linters are in the PR. Not done: the proofs' transcription of the
narrow column bound (risk 3), owed to row 490's register.
