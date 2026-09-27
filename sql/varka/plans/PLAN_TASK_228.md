# Task 228: A cold shape's row path reads each column once

*Opened 27 September 2026 (milestone 6 row 228); admission check done the same day.*

## 1. The problem

While a new shape's kernel warms, and whenever a batch falls back, a Varka node serves
the batch row by row: an `UnsafeProjection` applied to each `ColumnarBatchRow`. A
projection's references to an input column are not shared by subexpression
elimination, which leaves plain references alone, so each one is its own read through
`ArrowColumnVector` and its accessor: about 33 reads of `d` per row for the size
ladder's 16 entries. Vanilla Spark reads the same Arrow cache through the cache's row
reader, which writes each row's fields into an `UnsafeRow` once, and projects over that.

## 2. The admission check, done

`VarkaColdPathBenchmark` (`VarkaColdPathBenchmark-jdk25-results.txt`, the quiet
laptop, 27 September 2026), steady state over a hundred thousand Arrow-cached rows:
Varka's row path alone - no kernel, no warm-up - takes 69 ms at 16 entries against 48
for vanilla with whole-stage codegen off and 43 with it on; 200 against 120 and 427 at
54 entries. Vanilla's row-at-a-time projection is 1.4 to 1.8 times faster than Varka's
row path at every rung, and within 10 to 20% of its own whole-stage code below the
cliff, so the gap is the reads, not whole-stage code. The cold-start benchmark's
back-to-back queries beside the warm-up take the same time as the row path alone, so
the warm-up's compiles are not the cause either. Row 228 records the numbers.

## 3. The design

A row read once: before a row-by-row evaluation over a columnar batch, a copy
projection writes the columns the evaluation references into an `UnsafeRow`, and the
evaluation's projection is bound to that row instead of to the batch's row. One read
per referenced column per row, then cheap `UnsafeRow` reads, which is what vanilla's
row-at-a-time path does. The copy takes only the referenced columns, so a wide input
with one referenced column copies one.

Step 1 applies it to `VarkaColumnarToRowExec`'s fallback, the path the benchmark
measures. Step 2, if step 1 holds, applies it to every other row loop over a batch:
`VarkaProjectExec`'s fallback, both of `VarkaFilterExec`'s, the kernel evaluator's
residual columns, the filter evaluator's generic entries and the merge-at-row
projection's input side.

The alternative kept in reserve is the Arrow cache's typed row reader
(`ArrowColumnReader`), which skips `ArrowColumnVector`; built only if the copy
projection falls short of prediction 1.

## 4. Predictions, registered before the measurement

1. Varka's row path alone within 10% of vanilla's whole-stage-off numbers at every
   rung, steady state: about 48 ms at 16 entries and 120 at 54, from 69 and 200.
2. Its first run within 10% of vanilla's whole-stage-off first run at every rung.
3. Nothing else in the benchmark moves beyond the day's noise.

## 5. Step 1, measured: refuted, and the measurement was of the other node

*27 September 2026.* Step 1 (`VarkaInputRows` in `VarkaColumnarToRowExec`'s fallback)
ran through the whole benchmark (`VarkaColdPathBenchmark-jdk25-step1-results.txt`):
Varka's row path alone went from 69 to 76 ms at 16 entries and from 200 to 210 at 54,
still 1.7 to 1.9 times vanilla's whole-stage-off path. Prediction 1 is refuted; 2 too.

A JFR profile of 16 entries, thirty seconds per arm (a scratch probe looping the
benchmark's query, not committed), says why the change could not move it:

* **The benchmark's Varka arm is `VarkaProjectExec`, not `VarkaColumnarToRowExec`.**
  The harness's `noop()` sink accepts columnar batches, so the planner keeps the
  columnar sibling, and its fallback projects each row and converts it back into
  writable column vectors (`RowToColumnConverter.convert`, 7.7% of the executor's
  samples on its own). Step 1 changed only the to-row node's fallback.
* **The reads through `ArrowColumnVector` do not show in the profile at all.** They are
  inlined and cheap; section 1's premise was wrong.
* **Allocation is the difference.** Over the run the executor thread allocated 6.1 GB
  on Varka's path against 2.3 GB on vanilla's: 2.8 GB of `int[]` for the fallback's
  output vectors, and about 1.1 GB of `Integer` boxes and `MathUtils` lambdas from
  `DateTimeUtils.dateAddMonths` -> `localDateToDays` -> `toIntExact`'s `withOverflow`,
  which escape analysis removes entirely on vanilla's path. `dateAddMonths` holds 620
  of 2108 samples on Varka's path and 8 of 1968 on vanilla's.
* **Not type pollution:** `PrintInlining` shows the closure inlined on both paths. What
  keeps the allocations is not settled; the compile log shows some of the generated
  `Greatest_N` methods only at tier 3 on Varka's path in a fifteen-second run, and C1
  does no escape analysis, but the log is interleaved and that reading is unconfirmed.

What follows for the design: the cold path's cost depends on the node. A columnar
consumer pays for rebuilding vectors from rows; a row consumer does not. The benchmark
has to measure both nodes before a fix is chosen.
