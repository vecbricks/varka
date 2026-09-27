# Task 230: The columnar node's row path writes each entry into its vector once

*Scoped 27 September 2026 (milestone 6 row 230, from task 228's outcome); planned the same
day, with its admission check.*

## 1. Where this came from

While a new shape's kernel warms, and whenever a batch falls back, `VarkaProjectExec` - the
node the planner keeps under a consumer of batches, such as the noop sink every Varka benchmark
writes to - projects each row into an `UnsafeRow` with Spark's `UnsafeProjection`, and
`RowToColumnConverter` then reads that row back into the batch's writable column vectors. Task
228 gave the projection a copy of the columns it re-reads (`VarkaInputRows`), after which the
to-row node's row path runs within 10% of vanilla's row-at-a-time path and the columnar node's
stays 5 to 31% behind (`PLAN_TASK_228.md` 6 and 8). The columnar node is the one
`VarkaColdStartBenchmark` measures, and so the one behind task 181's first-query numbers.

## 2. The admission check, done

`VarkaColdPathProbe` under JFR at master `f2c91af77a7`, twenty seconds an arm, the readings in
`VarkaColumnarRowPath-jdk25-probe.txt`:

| arm | ms a query | conversion into vectors | the projection's `UnsafeRow` writes |
|---|---:|---:|---:|
| Varka columnar, no kernel, 16 entries | 59.9 | 10.9% | 7.3% |
| Varka columnar, no kernel, 54 entries | 180.7 | 13.3% | 6.3% |
| Varka to rows, no kernel, 54 entries | 151.7 | - | 9.6% |
| vanilla row-at-a-time, 54 entries | 152.6 | - | 8.5% |

At 54 entries the conversion's samples are 9.5 points of writes into the vectors
(`OnHeapColumnVector.putInt`), which columnar output needs whatever writes it, and 3.3 points of
the converter's own pass over the row; at 16 entries C2 inlines the writes into the converter,
whose 10.1% holds both. What a projection that writes each entry straight into its vector would
remove is the `UnsafeRow` write and the converter's pass: about 10% of the columnar node's time by
these readings, where the 29 ms by which it trails the to-row node at 54 entries are 16% of it. What the check rules out:
the vectors' allocation (0.5%), the scan (1 to 2%) and the copy (1 to 3%).

## 3. The design

Three ways to write the entries into the vectors directly:

* **A. Spark's `GenerateMutableProjection` into a `MutableColumnarRow` over the output vectors.**
  The mutable projection evaluates each entry and hands it to its target row's typed setter;
  `MutableColumnarRow` is a row whose setters write its columns at its `rowId`, the pattern
  Spark's vectorized hash aggregation uses. No code generation of Varka's own. It covers every
  output type the row has a typed setter for - the fixed-width types, dates, timestamps, times,
  intervals and decimals. Strings, binary and nested types reach the row's `update`, which
  rejects them, so a projection with such an output keeps today's path.
* **B. A Varka-generated projection into the vectors**: the same writes, from Varka's own
  generated class. The same effect as A over the same types, and a generator to own; built only
  if A falls short of prediction 1.
* **C. Today's `UnsafeRow`, converted column by column.** Saves the converter's dispatch, not
  the double write. Not pursued.

**A, in detail.** `VarkaProjectExec`'s fallback allocates the output vectors as today and, when
every output type has a typed setter, points one `MutableColumnarRow` at them and a mutable
projection - generated over `VarkaInputRows`' attributes, as the unsafe projection is today - at
the row, setting `rowId` before each row. A projection with any other output type keeps the
unsafe projection and the converter. Task 228's copy stays in front of both.

**The check of what C2 makes of it**: task 228 found the same projection compiled into code that
allocates or does not depending on what surrounds it, so `dev/varka_c2_report.py` reads the
mutable projection's compiled methods against the unsafe projection's, and JFR the allocation
per query, before the change is read as done.

## 4. Files

| file | what |
|---|---|
| `sql/varka/plans/PLAN_TASK_230.md` | this plan |
| `sql/core/benchmarks/VarkaColumnarRowPath-jdk25-probe.txt` | the admission check's readings |
| `.../execution/VarkaProjectExec.scala` | the fallback's direct path, and the choice between the paths |
| `.../execution/VarkaProjectExecSuite.scala`, or a suite of the new path's own | the tests of section 5 |
| `sql/core/benchmarks/VarkaColdPathBenchmark-jdk25-results.txt` | regenerated |
| `sql/varka/plans/PLAN_MILESTONE_6.md` | row 230 |

## 5. Tests, and what each is for

1. **The direct path writes what the converter writes**: for each output type with a typed
   setter, nulls included, the vectors the mutable projection fills equal the vectors the unsafe
   projection and the converter fill, over the same input batch.
2. **A projection with a string output keeps the converter**, and writes what it wrote before.
3. The fallback suites that drive `VarkaProjectExec` with no kernel (`VarkaProjectExecSuite`'s
   failure hooks, `VarkaDifferentialSuite`) pass unchanged.

## 6. The measurement

`VarkaColdPathBenchmark` regenerated on the quiet laptop against task 228's committed run: its
columnar table's "Varka columnar, no kernel" is the case; the rows table and the compiled-kernel
arms, which run no changed code, are the controls.

### 6.1 Predictions, registered before the measurement

1. The columnar node's no-kernel steady state falls by about a tenth at every rung, to within 10%
   of the same run's to-row node: about 125 ms at 54 entries, from 139, and 223 at 100, from 248.
2. The controls move by the day's noise, a median of about 1%.
3. The mutable projection's compiled methods eliminate as many allocations as the unsafe
   projection's, and the columnar node's allocation per query does not grow.

## 7. Risks

1. **A type the row cannot write** reaching the direct path: the choice is by output type, from
   the list of typed setters, and test 2 pins the fallback to the converter.
2. **The mutable projection compiles differently** from the unsafe one - its own splitting and
   subexpression elimination, a target row behind an interface - and C2 makes a worse job of it:
   the C2 report of section 3 is the check, and B the answer if it does.
3. **Appending and putting**: the converter appends to the vectors, the direct path puts at
   `rowId`. The output batch is allocated at the input's row count and read by its row count, so
   both fill the same positions; test 1 compares the vectors, not only the values read back.

## 8. Sequencing

The plan and the admission check first, in this pull request; then A, measured; B only if A
misses prediction 1.
