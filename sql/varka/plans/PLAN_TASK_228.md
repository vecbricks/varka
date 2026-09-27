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
