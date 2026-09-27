# Task 228: A cold shape's row path as fast as Spark's

*Opened 27 September 2026 (milestone 6 row 228); admission check done the same day.*

## 1. The problem

While a new shape's kernel warms, and whenever a batch falls back, a Varka node
evaluates its projection row by row, with Spark's `UnsafeProjection` over each
batch's `ColumnarBatchRow`. Under a consumer of rows the node is
`VarkaColumnarToRowExec`, which hands the rows on; under a consumer of batches, such
as the noop sink the Varka benchmarks write to, it is `VarkaProjectExec`, which writes
them back into column vectors with `RowToColumnConverter`. Vanilla Spark reads the
same Arrow cache through the cache's row reader, which writes each row's fields into
an `UnsafeRow`, and projects in one whole-stage method or, with whole-stage codegen
off, row by row with the same `UnsafeProjection` Varka uses.

## 2. The admission check, done

`VarkaColdPathBenchmark` (`VarkaColdPathBenchmark-jdk25-results.txt`, the quiet
laptop, 27 September 2026), steady state over a hundred thousand Arrow-cached rows,
Varka's nodes with no kernel (the evaluator's emission-failure test hook, so no
warm-up runs beside them):

| entries | vanilla whole-stage | vanilla whole-stage off | Varka to rows | Varka columnar |
|---:|---:|---:|---:|---:|
| 16 | 39 | 42 | 70 | 67 |
| 52 | 90 | 118 | 176 | 189 |
| 54 | 412 | 114 | 187 | 201 |
| 100 | 808 | 209 | 341 | 372 |

1. **Varka's row path is 1.5 to 1.9 times Spark's own row path at every rung, on both
   nodes.** The columnar node's conversion back into vectors is not the gap: the
   to-row node, which does no conversion, is as slow.
2. **Whole-stage code is a small lever.** Below the cliff it is worth 8 to 31% over
   vanilla's row path; past it, vanilla's row path is 3.5 to 3.9 times faster than
   vanilla's uncompiled whole-stage method, and Varka's two are 2.0 to 2.4 times.
3. **Janino compiles vanilla's whole-stage class in 3 to 20 ms** from 16 to 100
   entries, and its largest method crosses 8000 bytes between 52 entries (7882) and
   54 (8272).

## 3. How the diagnosis moved

Row 228's first draft read the gap as the projection's reads through
`ArrowColumnVector`, about 33 per row at 16 entries, since subexpression elimination
does not share a plain column reference, and proposed copying the referenced columns
into an `UnsafeRow` once per row before projecting (`VarkaInputRows`). Its first
measurement said nothing about that: the benchmark then timed only the noop sink, whose
node is `VarkaProjectExec`, while the change was in `VarkaColumnarToRowExec`. A JFR
profile of the columnar node at 16 entries, from a scratch probe, then showed:

* no sample in the reads through `ArrowColumnVector`: they are inlined, and cheap;
* the executor thread allocating about 2.6 times what vanilla's row path allocates over
  the same queries: the output vectors, which the columnar node needs, and about one
  `Integer` and one closure per row from `add_months`' overflow check
  (`DateTimeUtils.dateAddMonths` -> `localDateToDays` -> `MathUtils.toIntExact`, whose
  `withOverflow` takes a closure and returns a boxed value), which escape analysis
  removes entirely on vanilla's path;
* that allocation concentrated in one of the projection's sixteen generated `greatest`
  methods, which held more than three times the samples of each of the others.

The benchmark then gained the rows table, which is how point 1 of section 2 was found.
Its first full run also showed the two sinks warming two kernels for one shape, because
a query outside an SQL execution gets another class loader and the loader is part of
the shape cache's key; the rows sink now runs inside an SQL execution, as a Dataset
action does.

## 4. The design

**The lead**: the reads through the vector are cheap but not small. Inlined into the
projection's generated methods, each read is a chain of calls - `ColumnarBatchRow`,
`ArrowColumnVector`, its accessor, the Arrow vector's validity and value reads - and
thirty of them spend C2's inlining budget for the method, leaving the overflow check
of some `add_months` un-inlined, and so allocating, where vanilla's `UnsafeRow` reads
leave room. The test is the change the first draft proposed, for a different reason:
`VarkaInputRows` copies the referenced columns into an `UnsafeRow` once per row, one
chain per column, and the projection reads the copy, as vanilla's does. Applied to both
nodes' fallbacks; both nodes measured through their own sinks.

**Only where a column is read more than once.** A column read once is read once either
way, and copying it - a string's bytes, say - would be pure cost, so `VarkaInputRows`
copies only when some referenced column is referenced more than once, and otherwise hands
the projection the batch's own rows, exactly as before. The ladder's projections, which
read `d` three times an entry, always copy.

**The check of the mechanism**, from the JVM's own output: C2's inlining decisions
for the projection's generated methods, with and without the copy, read from
`-XX:+LogCompilation`, which records each compilation's inlining whole where the
compiler threads' `PrintInlining` lines interleave; and the allocation per query from
JFR. A committed probe runs one arm of the benchmark in a loop for either.

**If the copy closes the gap only partly**, the next candidates are the columnar
node's conversion (writing the projection straight into the vectors, rather than into
an `UnsafeRow` that `RowToColumnConverter` then reads), and, below the cliff, vanilla's
whole-stage code as the cold path.

## 5. Predictions, registered before the measurement

1. With the copy, both nodes' no-kernel steady state within 10% of vanilla's
   whole-stage-off path at every rung: about 42 to 46 ms at 16 entries and 114 to 125
   at 54, from 70 and 187 (to rows) and 67 and 201 (columnar).
2. Varka's first run to rows within 10% of vanilla's whole-stage-off first run at every
   rung.
3. The vanilla arms and the compiled-kernel arms unchanged within the day's noise.
4. The columnar node's allocation per query, no kernel, at vanilla's plus the output
   vectors: no `Integer` or closure per row.

## 6. The measurement, 27 September 2026

`VarkaColdPathBenchmark` with the copy in both nodes (commit `b7d95f933fd`), against the
admission check's run (`d91e2fd966b`), both on the quiet laptop the same afternoon. The
committed results file is the run with the copy; the admission check's is its previous
version.

**The controls held**: the vanilla arms, the compiled-kernel arms and Janino's compiles
moved by a median of 1.2% over 81 cases, with single cases further: vanilla's
whole-stage-off path to rows at 16 entries (42 to 54 ms), the columnar compiled kernel at
100 (33 to 47), vanilla's whole-stage first run at 32 (153 to 119).

**The copy takes about a third off both nodes' row path at every rung**: to rows 70 to 56
ms at 16 entries, 176 to 113 at 52, 187 to 126 at 54, 341 to 218 at 100; columnar 67 to 45,
189 to 126, 201 to 139, 372 to 248.

1. **Held for the to-row node at seven rungs of nine**, within 10% of the same run's
   vanilla whole-stage-off path (1.00 to 1.10); missed at 80 entries (190 against 161).
   At 16 entries it is 1.04 against the same run's control, whose reading moved 29%, and
   1.33 against the admission check's, so that rung is not decided. **Refuted for the
   columnar node**: 1.05 to 1.31 times vanilla's row path. What it has left is its own
   conversion into vectors (8).
2. **Held**: Varka's first run to rows is 0.95 to 1.03 times vanilla's whole-stage-off
   first run at every rung. Against vanilla's whole-stage first run it is 106 against 104
   ms at 16 entries and faster from 48 (193 against 213).
3. **Held**, in the median; the single moves are above.
4. **Held** (7): with the copy the columnar node allocates no `Integer` and no closure per
   row.

Below the cliff Varka's row path to rows is now 1.30 to 1.44 times vanilla's whole-stage
code (56 against 39 ms at 16 entries, 113 against 87 at 52), from 1.79 to 2.06; past it,
0.27 to 0.31 times.

## 7. The mechanism, from the JVM

`VarkaColdPathProbe` looped each arm for twenty seconds at 16 entries under JFR and
`-XX:+LogCompilation`, with the copy and with the two nodes' sources as the admission check
had them; the readings are `VarkaColdPathProbe-jdk25-probe.txt`, and
`dev/varka_c2_report.py` reads the compile logs.

* **Allocation.** Without the copy the to-row node allocated 40.74 MB a query on the
  executor thread, 33.32 of it closures from `MathUtils.toIntExact`'s `withOverflow`, one
  for almost every `toIntExact` the projection calls; with it, 5.20, against 4.60 on
  vanilla's row path. The columnar node: 16.86 without, including 2.09 of closures and
  1.36 of `Integer`, and 13.10 with, of which 6.37 is its output vectors.
* **What C2 made of the projection.** In every run C2 compiles each of the sixteen
  generated `greatest` methods on its own and inlines two callers, `writeFields_0_0$` and
  `writeFields_0_1$`, up to `DesiredMethodLimit`. With the copy, and on vanilla's path,
  each `greatest` compile eliminates three allocations and each caller twelve, the callers
  having inlined 8811 bytes. Without it, the callers inlined 9260 bytes (to rows) and 9254
  (columnar) - the reads through the vector - and eliminated six and nine; on the to-row
  node each `greatest` compile eliminated two. The inlining at `withOverflow`'s call to
  its closure is the same in every run, a predicted call inlined, so what differs is
  escape analysis over the larger graph, not a refused inline.

Why escape analysis gives up on the larger graph is not taken further: the copy gives C2
vanilla's shape, and the probe and the report make the check repeatable.

## 8. What the task leaves

* **The columnar node's own conversion.** `VarkaProjectExec`'s row path projects into an
  `UnsafeRow` that `RowToColumnConverter` then reads back into vectors, and stays 5 to 31%
  behind vanilla's row path. Writing the projection straight into the vectors is the
  candidate.
* **The other row loops over a batch.** Both of `VarkaFilterExec`'s fallbacks, the kernel
  evaluator's residual columns, the filter evaluator's generic entries and the merge-at-row
  projection read the batch's rows in the same way; the merge-at-row projection runs on the
  kernel path of every mixed projection, not only while cold. Each needs its own
  measurement before the copy goes in.
* **The first query**, as `VarkaColdStartBenchmark` and task 181's post measure it: the
  committed cold-start results predate the copy, and that benchmark writes to the noop sink,
  where the node is the columnar one.

