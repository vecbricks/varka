# VARKA-598: No filter whose fused part is only its null tests slower than Spark's row path

## 1. Where this came from

Milestone 8's row 598, added 10 November 2026 on the owner's word from two runner results on the
EPYC 7763 at 256 bits. `VarkaBigintArithmeticBaselineBenchmark` (#1455, then on its
branch `varka-104-baseline`, since merged) times `filter l + l2 > 0, ANSI` on the varka on arm at 0.74x of the row path
without nulls and 0.82x at one row in ten null, and `VarkaDecimalBaselineBenchmark` (#1475, on
master) times `DECIMAL(15, 2) x * y < z filter` at 0.77x and 0.87x. Both sections are
`NULL_TESTS`: no lowering computes the value-reading conjunct, so the only conjuncts that fuse are
the `IS NOT NULL` tests Spark's optimizer infers for each column a null-intolerant condition
reads, and the plan is a `VarkaFilterColumnarToRowExec` of those tests under a row `FilterExec`
holding the rest. The same shape loses in every committed section of that class: the decimal
baseline's runner file notes eighteen sections as fusing only their null tests, the comparisons
and arithmetic filters of rows 348 and 349 as they stood when those sections were measured, and
every one is slower on the varka on arm, and so is the day-time baseline's
`filter extract(HOUR FROM dt) >= 12` (#1351). At 128
bits the sections tie, because the two-lane refusal (row 503) already leaves their conditions to
the row path there.

## 2. The admission check, done

The diagnosis is `VarkaNullTestFilterProbe`, over the decimal baseline's table, 2,000,000
Arrow-cached rows, on the laptop under `-XX:UseAVX=2 -XX:MaxVectorSize=32`, the runners' level and
width. Its output is `results/VARKA-598-probe.txt`; the laptop was shared with other agents'
JVMs, so each run's arms are read against each other by best time, not across runs.

The prediction, written before the first run: the kernel's pass is cheap, and the loss is the
handoff, since the to-row node copies every selected row, each column the residual reads, into an
`UnsafeRow` (its `toUnsafe` projection) before the row filter sees it, where Spark's whole-stage
filter reads the batch's vectors in place and builds an output row only for a survivor.

What the probe found, run 2 (best times, ms, row path then varka on):

| query | no nulls | one row in ten null |
|---|---|---|
| `x * y < z`, the baseline's section | 366 / 425 | 266 / 349 |
| the three `IS NOT NULL` alone, `x` out | 126 / 44 | 106 / 64 |
| the three `IS NOT NULL` alone, `x, y, z` out | 145 / 47 | 147 / 66 |
| `y IS NULL AND (x * y) IS NULL` | 29 / 25 | 75 / 44 |

* The section reproduces the loss, 0.86x and 0.76x (run 1: 0.87x and 1.0x on a busier machine).
* The varka on arm's extra time over the row path, 59 and 83 ms, is about the null-test kernel
  with all three columns copied out, 47 and 66 ms: the row filter above it costs what Spark's
  whole filter does, so the kernel spares it nothing, and the kernel's pass with the copy is the
  loss. The prediction held.
* With nothing left over the same kernel beats the row path, 2.2x to 3.1x, so the kernel and the
  conversion are not slow in themselves; what loses is a second pass in front of a row filter
  that must convert and read every row anyway.
* A selective null test is different: `y IS NULL` beside a conjunct that does not fuse passes no
  row, or one in ten, and there the kernel spares the row filter most of its rows. With no row
  passing it ties, 1.1x to 1.2x over the runs, under the 1.3x a claim needs; at one in ten it wins,
  1.4x to 1.7x.
  So the rule cannot be "null tests alone": it is the inferred `IS NOT NULL`, which passes nearly
  every row.

A cheaper handoff was considered and rejected. The copy is the conversion every row consumer of
a Varka filter pays (`VarkaFilterColumnarToRowExec`), and the only way to avoid it here is to let
the row filter read the batch in place, which is Spark's own plan; so the cheapest handoff is no
split at all.

## 3. The design

### 3.1 The decline

`VarkaExpressionCompiler.predicate` demotes every fused conjunct when all of them are
`IS NOT NULL` over a bare column and at least one conjunct stays residual
(`nullTestsAlone`). The check runs in the demotion loop beside the order rule (VARKA-380), so it
is asked again after every demotion, and a size demotion that leaves only those tests fused
declines the rest too. Nothing then fuses, `compilePredicate` is empty, and the rule leaves the
filter whole in Spark's plan, the plan the row path arm runs. The task's compile sees the same
decline, since it is the compiler's, and `explainPredicate` gives each demoted test the reason;
no Varka node is planned, so EXPLAIN shows Spark's own filter.

It is not host-dependent: the loss is a row-path cost that no lane width changes, so the rule
holds at every emit level and width, and no `VarkaAdmission` component or configuration is
added.

### 3.2 What is deliberately unchanged

* `IS NOT NULL` with nothing left over, which fuses and wins (section 2).
* `IS NULL` beside a residual, which fuses and wins.
* A split whose fused part reads a value, `isnotnull(l) AND l < 5 AND <residual>`: its kernel
  removes rows before the copy, and its cost is not measured here.
* A Varka null-test filter whole under a row projection that computes (`NULL_TESTS` in
  `VarkaSqlBenchmarkBase.Fusion` still names it); no baseline section has that shape now.

### 3.3 Registered op counts

None: no kernel's bytes change.

## 4. Files

| file | what |
|---|---|
| `VarkaExpressionCompiler.java` | the decline |
| `VarkaExpressionCompilerSuite.scala` | the rule at the unnamed and named emit levels |
| `VarkaDecimalBaselineBenchmark.java`, `VarkaDayTimeIntervalBaselineBenchmark.java`, `VarkaBigintArithmeticBaselineBenchmark.java` | the `NULL_TESTS` sections listed `NONE` |
| `VarkaDecimalBaselineQueriesSuite.scala`, `VarkaDayTimeIntervalBaselineQueriesSuite.scala`, `VarkaBigintArithmeticBaselineQueriesSuite.scala` | the classes, and the rule over the baseline's table |
| `VarkaNullTestFilterProbe.java`, `results/VARKA-598-probe.txt` | the diagnosis |

## 5. Tests, and what each is for

* `VarkaExpressionCompilerSuite`, "IS NOT NULL tests that would fuse alone beside a residual
  conjunct decline with it": at `useAVX` -1, 2 and 3, the filter declines whole with the reason
  on the null test, a value conjunct beside the tests keeps the split, tests alone fuse, and
  `IS NULL` beside a residual fuses.
* `VarkaDecimalBaselineQueriesSuite`, "a filter of which only the inferred IS NOT NULL tests would
  fuse stays whole on the row path at every emit level": over the baseline's table at both
  densities and the three levels, the section's plan has no Varka filter and both sessions answer
  alike; the existing class check holds every section's new class at both refusal settings.
* `VarkaDayTimeIntervalBaselineQueriesSuite` and `VarkaBigintArithmeticBaselineQueriesSuite`: the
  filters' classes are `NONE`.
* The BIGINT, decimal and day-time baselines' plan-only dry runs at 256 and 128 bits, after the
  merge of master that brought the BIGINT baseline.

## 6. The measurement

The probe after the change (`results/VARKA-598-probe.txt`, last run): the section's two arms
plan alike and time alike, 334 / 311 and 218 / 211 ms; the null tests alone and the `IS NULL`
arm still fuse and still win.

### 6.1 Predictions, registered before the run

1. The section's varka on arm, declined, ties the row path within the run's noise, since the
   plans are the same. It did.

## 7. Risks

1. A user-written `IS NOT NULL` over a mostly null column, beside a conjunct that does not fuse,
   would have been selective, and now stays on the row path. Plan time does not know the column's
   null count; the inferred tests are the common case.
2. A suite that reached the split through inferred null tests loses its Varka node; the suites'
   run at the defaults is the check.

## 8. Sequencing

One commit for the row, one for the change with its tests and the probe, one for the regenerated
files.

## 9. Outcome

The review (`/code-review high`) found two suites the change moves, both fixed: the 128-bit
default suite (`VarkaTwoLaneDefault128Suite`) expected `WHERE i < l` to keep the `isnotnull(i)`
kernel row 503 left beside the refused long comparison, which is exactly this shape and now stays
whole; and the golden corpus records filters of the form `(expr) IS NOT NULL` whose inferred
column tests fused under a row filter, regenerated. It also asked for the new queries test's
`PinsDefaults` tag and the `NULL_TESTS` class's doc, both done. Kept as trade-offs: the rule
reads syntax, so a user's selective `IS NOT NULL` over a mostly null column beside a residual
declines too (section 7); the decline sits in the compiler rather than the rule, so that the
planning compile, `explainPredicate` and the task's compile give the same answer and reason, as
the size and order demotions do; and a declined filter is compiled once before it is demoted, a planning
cost only.


Built 10 November 2026. The pull request's dry runs and suite runs are listed in its description.
The BIGINT baseline (#1455) merged first, so this branch, after merging master on 11 November
2026, moves its two `filter l + l2 > 0, ANSI` sections from `NULL_TESTS` to `NONE`, at both null
densities and both widths. Row 104's #1484, still open, makes those two sections fuse whole at
256 bits; whichever of #1484 and this change merges second takes #1484's classes for them.
