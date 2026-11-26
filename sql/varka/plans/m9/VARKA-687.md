# VARKA-687: The cache's statistics reach the evaluator

*Planned on 26 November 2026. This plan stands alone: a builder needs this file and the
repository's `AGENTS.md` files, nothing else. Not started: milestone 9 waits for the owner's
word (`m9/PLAN.md` decision 18). The code facts below were checked on master `325511d01a3`.*

## 1. What this row is

Row 687 of `sql/varka/plans/m9/PLAN.md`: "The cache's statistics reach the evaluator. [...] a
cached batch's least and greatest value handed to the evaluator, in place of the pass. **Done
when** a zoned kernel over a cached column runs no pass, and the difference is measured."

Before this row a kernel that reads the session time zone finds the range of its timestamp
column by a pass over the column, once a batch. Spark's Arrow cache computed the same two
values when it wrote the batch, and the scan throws them away. After this row the scan hands
them over with the column, and the evaluator reads them where they are present.

What it gives the rows after it: row 688 (the range of a computed operand) starts its interval
arithmetic from a column's range, and takes it from the same call; row 690's `ToInstant` over a
`TIMESTAMP_NTZ` column gets its range the same way with no further work; row 691 measures the
forms with this hand-off in place.

What must be merged before it starts: row 657's parts A to D (the zoned kernels `hour(ts)`,
`minute(ts)`, `second(ts)` and `CAST(ts AS DATE)`, their suites and their documentation), and so
rows 682, 683, 656, 678 and 655. Row 685 is not needed. If its part b has merged, the one call
this row edits has moved from the runner to `VarkaBatchRanges`; part a's B0 finds it in either
place.

The goals it serves (`m9/PLAN.md` 1.1), each with the item of 1.2 that it moves:

- G7, item 10 (every `TIMESTAMP_LTZ` section whose kernel reads the zone at least five times
  the row path's rate, none slower than it): the baseline sections read an Arrow-cached table, so
  each zoned section loses the pass. Row 691 reads the bar; this row moves the numbers under it.
- G1, G2, G3, G4, G5, G6: not served. The row fuses no entry, proves no zone, and is built at
  full strength.

## 2. What an agent must know

**The idea.** A `TIMESTAMP` is a count of microseconds since the epoch in UTC, in a long. Local
time is that count plus the zone's offset, and the offset changes at a few instants. A zoned
kernel adds one offset to every row and then, for each change the batch spans, one difference to
the rows at or after the change. The offset and the changes are not in the kernel's bytes:
before each call the evaluator writes them into the kernel's `long[] longArgs`. To know which
changes a batch spans it needs the least and the greatest live value of the column: the
batch's **range**.

Today the range comes from `LongRangeOps.leastAndGreatest`, a vector pass over the column's
data that skips null rows. On the laptop the probe priced that pass at 0.232 ns a row at four
64-bit lanes and 0.393 at two, where two steps of a kernel cost 0.148
(`sql/varka/spikes/zone-offset/results/forms.txt`): the pass costs more than the work it
prepares.

Spark's Arrow cache stores a table as cached batches. For each batch it keeps, beside the Arrow
bytes, a row of statistics: for every column the lower bound, the upper bound, the null count,
the row count and the size. Spark reads the bounds for one purpose, to skip a whole batch that a
filter cannot match. When the scan turns a cached batch back into a `ColumnarBatch` it reads the
bytes and drops the statistics.

This row carries the two bounds of a timestamp column from the cached batch to the evaluator, on
the column vector itself. A column vector that knows its range is a subclass of Spark's
`ArrowColumnVector`. Varka's columnar nodes forward a child's vector untouched, so the range
travels with exactly the rows it describes; a vector that Varka built itself (a filter's
compacted column) is a plain vector and has no range. The evaluator asks one new method for a
column's range: it answers from the vector where it can and runs the pass where it cannot.

**Terms.**

- *Instant*: microseconds since the epoch in UTC. *Live row*: a row that is not null. *Range*:
  the least and the greatest live value of a column in one batch. *Empty range*: least
  `Long.MAX_VALUE`, greatest `Long.MIN_VALUE`, which is what `LongRangeOps` writes for no live
  row.
- *Cached batch*: `ArrowCachedBatch(numRows, arrowData, stats)`, one batch of a cached table.
  *Statistics row*: its `stats`, five fields a column of the cached table, in the cache's column
  order: lower bound, upper bound, null count, row count, size in bytes. *Cache ordinal*: a
  column's position in the cached table, so its lower bound is field `5 * ordinal` and its upper
  bound field `5 * ordinal + 1`.
- *The scan*: `InMemoryTableScanExec`, which reads cached batches through
  `ArrowCachedBatchToColumnarBatchIterator`. *Prefetch*: with
  `spark.sql.execution.arrow.cache.prefetch.enabled`, that iterator decodes the next cached batch
  on another thread while the consumer reads the current one.
- *Zone node*: the IR record `ToLocal(instant, zone)`. *Batch argument*: an entry of `longArgs`
  that the evaluator writes before each call. *Program*: one zone node's run of batch arguments
  and the kernel input its operand column is.
- *The pass*: one call of `LongRangeOps.leastAndGreatest` that reads rows. *The site*: the one
  place in `sql/core` main code that calls it: `VarkaKernelRunner.fill` after row 682, or
  `VarkaBatchRanges.measure` once row 685's part b has merged.
- *Row path*: Spark's own evaluation, row by row. *A batch declines*: the evaluator gives it to
  the row path. *Sanitizer*: `-Dvarka.sanitizeMemory=true`, the checking mode every matrix run
  uses.

**The names.** "Reads" is on master or comes from row 682, 683, 685 or 657; "adds" is this
row's.

| Name | Reads or adds | Meaning |
|---|---|---|
| `ArrowCachedBatch.stats` | reads (master) | the statistics row of one cached batch |
| `ArrowCachedBatchToColumnarBatchIterator.next` | reads (master), edits | turns a cached batch into a `ColumnarBatch`; drops the statistics today |
| `LongRangeOps.leastAndGreatest(srcData, srcValidity, srcNullCount, length, out)` | reads (row 683) | the pass: least to `out[0]`, greatest to `out[1]`; false, and the empty range, when no row is live |
| `VarkaKernelRunner.fill(input, len)`; `VarkaBatchRanges.measure(batch, len, inputOrdinals)` | reads (rows 682, 685) | where the pass is called for each program |
| `VarkaMemorySanitizer.ENABLED`, `VarkaMemoryViolation` | reads (master) | the checking mode, and the one throwable that no fallback catches |
| `VarkaRangedArrowColumnVector(vector, least, greatest)`, `least()`, `greatest()`, `of(vector, stats, cacheOrdinal)` | adds, `sql/core`, Java | an Arrow column vector that carries the range of its rows; `of` builds one from a statistics row, or a plain `ArrowColumnVector` |
| `VarkaColumnRange.of(column, srcData, srcValidity, srcNullCount, length, out)`, `fromStatistics()`, `passes()` | adds, `sql/core`, Java | a column's range, from the vector or by the pass; two JVM-wide counts of which source answered |
| `spark.sql.codegen.varka.cachedRange.enabled`, `SQLConf.VARKA_CACHED_RANGE_ENABLED`, `varkaCachedRangeEnabled` | adds | internal, default true: false makes the scan hand plain vectors, as today |
| `VarkaCachedRangeSuite` | adds, `sql/core`, ScalaTest | the hand-off, the reader and a zoned query over a cached table |
| `VarkaCachedRangeBenchmark` | adds, `sql/core`, Java | the statistics against the pass, section by section |

## 3. The admission check

Every fact was read on master `325511d01a3`. The paths `A`, `E`, `V`, `S`, `XS`, `XJ` and `B`
are the shell variables of section 5. Each fact names its commands; they are listed after the
facts, each with the number it prints.

**Where the statistics are computed.**

1. **Two writers make cached batches, and both compute the bounds over the rows they store.**
   From columnar input, `ColumnarBatchToArrowCachedBatchIterator` (both of its paths,
   `convertArrowBatchZeroCopy` and `convertToArrowBatch`) calls
   `ArrowCachedBatchSerializer.collectStatistics` on the Arrow vectors it is about to store.
   From rows, `InternalRowToArrowCachedBatchIterator.next` feeds each row it writes to one
   `ColumnStats` collector a column and ends with `buildStatisticsFromCollectors`.
   Commands 1a and 1b.
2. **For a `TIMESTAMP` the bounds are the least and greatest live value, exactly.**
   `collectStatistics` sends a `TimestampType` column to `calculateMinMaxTimestamp`, a loop over
   the batch's rows that skips `isNull` rows and compares `TimeStampMicroTZVector.get(i)`. A
   `TimestampNTZType` column goes to `calculateMinMaxTimestampNTZ`, the same loop over a
   `TimeStampMicroVector`. The row writer's collector for both types is `LongColumnStats`, whose
   `gatherStats` compares `row.getLong(ordinal)` of every non-null row. No row is sampled and no
   bound is rounded, so the bounds are tight: each is the value of a live row.
   Commands 2a and 2b.
3. **The bounds are in the column's own unit.** The cache's Arrow type for both timestamp types
   is `Timestamp(MICROSECOND, ...)` (`ArrowUtils.toArrowType`), and `ArrowWriter`'s
   `TimestampWriter` and `TimestampNTZWriter` store `input.getLong(ordinal)` unchanged. So the
   long a bound holds, the long `get(i)` returns and the eight bytes the kernel reads from the
   data buffer are one value: microseconds, as Spark's own long.
   Commands 3a and 3b.
4. **A batch with no live row has two encodings, and neither is a range.** The columnar writer
   returns `(null, null)`: every `calculateMinMax...` method ends
   `if (hasValue) (min, max) else (null, null)`. The row writer returns its start values, lower
   `Long.MaxValue` and upper `Long.MinValue`, because `LongColumnStats.collectedStatistics`
   returns its fields whether or not a value came. `SimpleMetricsCachedBatch` documents a null
   bound as "not provided". So a reader must treat a null bound and a lower above its upper as
   no range.
   Commands 4a and 4b.

**Where they stop.**

5. **The bounds' only reader is batch pruning.** `SimpleMetricsCachedBatchSerializer.buildFilter`
   evaluates a filter over `cachedBatch.stats` and drops whole cached batches; it changes no
   batch it keeps. `SimpleMetricsCachedBatch.sizeInBytes` reads every fifth field, the sizes.
   Command 5.
6. **`ArrowCachedBatchToColumnarBatchIterator.next` drops them**, as the row's line says. It
   takes a cached batch from `batchIter`, passes it to `deserializeToRoot`, which reads
   `arrowData` only, wraps each Arrow vector in `new ArrowColumnVector(...)` and returns
   `new ColumnarBatch(selectedColumns, root.getRowCount)`. The word `stats` is not in the file.
   Commands 6a and 6b.
7. **With prefetch the cached batch is taken one call early.** `submitPrefetch` takes the next
   cached batch from `batchIter` on the consumer's thread, at the end of `next`, and hands it to
   the worker; the next call of `next` receives only the decoded root from the future. So the
   statistics of a prefetched batch must be kept from one call to the next.
   Command 7.
8. **The scan reads all of a cached batch's rows, and a selected column's place is not its
   cache ordinal.** `columnIndices(i)` is the cache ordinal of selected column `i`
   (`CachedColumnIndices`). When every selected column is in the cache the root holds only the
   selected columns, in `columnIndices` order; otherwise it holds every cache column and
   `columnIndices.map(allColumns(_))` picks. Either way no row is dropped inside a batch.
   Command 8.
9. **Two callers build the iterator:** the serializer's `convertCachedBatchToColumnarBatch`
   and the session-free test harness, `VarkaCacheBatches.each`, which is Java and so passes
   every constructor argument by position. Command 9.

**From the scan to the evaluator.**

10. **Nothing between the scan and a Varka node changes a vector.**
    `InMemoryTableScanExec.doExecuteColumnar` counts rows and returns the same batch.
    `VarkaColumnarRule.readsArrowCache` walks from a Varka node to the scan through query stages
    and through `VarkaProjectExec` and `VarkaFilterExec` only. A Varka projection forwards a bare
    column as `input.column(ordinal)` itself. A Varka filter forwards every column when every
    row is selected, and otherwise builds new vectors (`VarkaOwnedArrowColumnVector`) for the
    rows it keeps.
    Commands 10a and 10b.
11. **A kernel runs only on a vector that holds exactly the batch's rows.**
    `VarkaEvaluatorBase.canRun` asks `isArrowBacked`, which wants each kernel input to be an
    `ArrowColumnVector` (by `instanceof`, so a subclass passes) whose
    `getValueCount()` equals the batch's row count. So wherever a kernel runs on a vector of the
    scan, the vector's rows are the cached batch's rows, and its bounds are the batch's range.
    Command 11.
12. **A subclass of `ArrowColumnVector` is safe for the scan's other readers.** The serializer's
    `vectorTypes` names `ArrowColumnVector`; `ColumnarToRowExec`'s generated code casts
    `batch.column(i)` to that name, which a subclass passes; `VarkaColumnarRule.arrowFriendly`
    compares the declared names, which do not change. Two subclasses exist already
    (`VarkaOwnedArrowColumnVector`, `ReusedArrowColumnVector`), and each overrides
    `closeIfFreeable` only.
    Command 12.
13. **A wrong range is a wrong answer.** The fill writes the offset at the least value and one
    step for each change up to the greatest. A range that leaves out a live row gives that row
    another row's offset, and no status reports it. A range wider than the rows is still right:
    it may only ask for more steps than the rows need.
14. **One throwable passes every fallback.** `VarkaKernelRunner.isCatchable` excludes
    `VarkaMemoryViolation`, so a test that raises it fails where any other exception would send
    the batch to the row path and pass. `VarkaMemorySanitizer.checked()` is a JVM-wide count
    that tests read: a counter of that kind has a precedent.
    Command 14.
15. **The baselines read the Arrow cache.** `VarkaTimestampNtzFieldsBaselineBenchmark` caches
    its view with `VarkaArrowSessions.cache` and titles each section "over ... Arrow-cached
    rows"; row 655's `TIMESTAMP` classes are built the same way. So this row moves every zoned
    section's time, and a benchmark can switch the hand-off with
    `VarkaSqlBenchmarkBase.withConf`.
    Command 15.

**The commands.**

    grep -c 'ArrowCachedBatchSerializer.collectStatistics(root, schema)' $A/ColumnarBatchToArrowCachedBatchIterator.scala   # 1a: 2
    grep -c 'statsCollectors(i).gatherStats(row, i)' $A/InternalRowToArrowCachedBatchIterator.scala   # 1b: 1
    grep -c 'case TimestampType => calculateMinMaxTimestamp(vector, rowCount)' $A/ArrowCachedBatchSerializer.scala   # 2a: 1
    grep -c 'case TimestampType => new LongColumnStats' $A/ArrowCachedBatchSerializer.scala   # 2b: 1
    grep -c 'case TimestampType => new ArrowType.Timestamp(TimeUnit.MICROSECOND, timeZoneId)' sql/api/src/main/scala/org/apache/spark/sql/util/ArrowUtils.scala   # 3a: 1
    grep -c 'valueVector.setSafe(count, input.getLong(ordinal))' sql/catalyst/src/main/scala/org/apache/spark/sql/execution/arrow/ArrowWriter.scala   # 3b: 5
    grep -c 'if (hasValue) (min, max) else (null, null)' $A/ArrowCachedBatchSerializer.scala   # 4a: 15
    grep -c 'protected var lower = Long.MaxValue' $A/ColumnStats.scala                         # 4b: 1
    grep -c 'cachedBatch.stats' sql/core/src/main/scala/org/apache/spark/sql/columnar/CachedBatchSerializer.scala   # 5: 2
    grep -c 'stats' $A/ArrowCachedBatchToColumnarBatchIterator.scala                           # 6a: 0
    grep -c 'new ArrowColumnVector(' $A/ArrowCachedBatchToColumnarBatchIterator.scala          # 6b: 2
    grep -c 'val nextCachedBatch = batchIter.next().asInstanceOf\[ArrowCachedBatch\]' $A/ArrowCachedBatchToColumnarBatchIterator.scala   # 7: 1
    grep -c 'columnIndices.map(allColumns(_))' $A/ArrowCachedBatchToColumnarBatchIterator.scala   # 8: 1
    grep -rl 'new ArrowCachedBatchToColumnarBatchIterator(' sql/core/src | wc -l               # 9: 2
    grep -c 'numOutputRows += cb.numRows()' $A/InMemoryTableScanExec.scala                     # 10a: 1
    grep -c 'forwarded\[j\] = input.column(j);' $E/VarkaFilterEvaluator.java                   # 10b: 1
    grep -c 'vector.getValueCount() == rows' $E/VarkaEvaluatorBase.java                        # 11: 1
    grep -c 'classOf\[ArrowColumnVector\].getName' $A/ArrowCachedBatchSerializer.scala         # 12: 1
    grep -c 'private static final LongAdder CHECKED' $V/VarkaMemorySanitizer.java              # 14: 1
    grep -c 'protected static void withConf(' $B/VarkaSqlBenchmarkBase.java                    # 15: 1

Fact 13 is read from `VARKA-682.md` 4.8 and has no command on this master.

**Not checked.** The code of rows 682, 683, 685, 656, 678 and 657 is not built: the site, the
zoned kernels and their suites are known from `VARKA-682.md`, `VARKA-683.md`, `VARKA-685.md` and
`VARKA-657-PARTS.md` only, and part a's B0 greps them. Row 655's `TIMESTAMP` baseline classes do
not exist yet; fact 15 is read from their `TIMESTAMP_NTZ` sibling. What the pass costs inside a
query, where the kernel reads the same column straight after it, is not measured: section 7
registers it. No JVM was run for this plan.

## 4. The design

### 4.1 The carrier

`E/varka/VarkaRangedArrowColumnVector.java`, new:

    public final class VarkaRangedArrowColumnVector extends ArrowColumnVector {
      public VarkaRangedArrowColumnVector(ValueVector vector, long least, long greatest)
      public long least()
      public long greatest()
      public static ArrowColumnVector of(ValueVector vector, @Nullable InternalRow stats, int cacheOrdinal)
    }

- The constructor throws `VarkaErrors.illegalArgument("a ranged column needs least <= greatest,
  got " + least + " and " + greatest)` before `super(vector)` when `least > greatest`. So a
  ranged vector always claims a live row, and the empty range never travels.
- It overrides nothing. `closeIfFreeable` stays `ArrowColumnVector`'s: the scan's vectors are
  freed as they are today.
- `of` is the only reader of the statistics row's layout. With `lower = 5 * cacheOrdinal`, it
  returns `new ArrowColumnVector(vector)` when `stats` is null, when `stats.numFields() <= lower
  + 1`, when `stats.isNullAt(lower)` or `stats.isNullAt(lower + 1)`, or when
  `stats.getLong(lower) > stats.getLong(lower + 1)`; otherwise the ranged vector. The 5 is a
  private constant `STATS_PER_COLUMN` whose comment names `ColumnStatisticsSchema.schema`.
- `of` runs once a ranged column a batch, on the scan's thread. It allocates the one vector
  object that the scan allocated before.

### 4.2 The scan

`A/ArrowCachedBatchToColumnarBatchIterator.scala`:

- The constructor gains a last parameter, `rangesEnabled: Boolean = false`.
- A new field, computed once: `private val ranged: Array[Boolean]`, one entry a cache column,
  true where `rangesEnabled` and the column's type in `cacheSchema` is `TimestampType` or
  `TimestampNTZType`. These are the two types a program's operand column can have (a
  `TIMESTAMP` for `ToLocal`, a `TIMESTAMP_NTZ` for row 690's `ToInstant`), and for both the
  bound is a long in the column's unit (facts 2 and 3).
- A new field `private var prefetchStats: InternalRow = _`. `submitPrefetch` sets it to
  `nextCachedBatch.stats` beside the submit (fact 7).
- `next` keeps the statistics of the batch it returns in a local `stats`: in the prefetch
  branch `prefetchStats`, which it then sets to null; in the other branch the `stats` of the
  cached batch it takes from `batchIter` before `deserializeToRoot`.
- A private method builds each column:
  `private def column(vector: FieldVector, cacheOrdinal: Int, stats: InternalRow): ColumnVector`
  returns `VarkaRangedArrowColumnVector.of(vector, stats, cacheOrdinal)` where
  `ranged(cacheOrdinal)`, and `new ArrowColumnVector(vector)` otherwise. In the branch that
  loaded only the selected columns, vector `i` has cache ordinal `columnIndices(i)`; in the
  other branch vector `j` has cache ordinal `j` (fact 8).

`A/ArrowCachedBatchSerializer.scala`, `convertCachedBatchToColumnarBatch`: beside
`val prefetchEnabled = conf.arrowCachePrefetchEnabled`, add
`val rangesEnabled = conf.varkaCachedRangeEnabled`, and pass it to the iterator. `vectorTypes`
does not change (fact 12). `convertCachedBatchToInternalRow`'s fallback calls the same method and
so gets ranged vectors; it reads them as rows, which a subclass does not change.

`XJ/columnar/VarkaCacheBatches.java`, `each`: pass `conf.varkaCachedRangeEnabled()` as the new
last argument (fact 9). The session-free harness then feeds the evaluators what the cache
would, as its comment promises, and under the sanitizer every zoned check of row 657's
`VarkaTimestampZoneSuite` compares the statistics with the pass (4.4).

### 4.3 The conf

`S` (`SQLConf.scala`), directly after `VARKA_WARMUP_ENABLED`:

    val VARKA_CACHED_RANGE_ENABLED = buildConf("spark.sql.codegen.varka.cachedRange.enabled")
      .internal()
      .doc("When true, a scan of the Arrow cache hands the least and greatest value of each" +
        " TIMESTAMP and TIMESTAMP_NTZ column of a cached batch, from the batch's statistics," +
        " to the Varka kernels that need a batch's range, in place of a pass over the column." +
        " When false, the scan hands plain column vectors and such a kernel runs the pass.")
      .version(<the version string VARKA_WARMUP_ENABLED has>)
      // Chooses which of two equal-answer sources gives a batch's range.
      .withBindingPolicy(ConfigBindingPolicy.NOT_APPLICABLE)
      .booleanConf
      .createWithDefault(true)

and `def varkaCachedRangeEnabled: Boolean = getConf(VARKA_CACHED_RANGE_ENABLED)` directly after
`varkaWarmupEnabled`. The conf is read on the driver when the scan builds its RDD, as
`arrowCachePrefetchEnabled` is, and travels in the iterator's constructor. It has two uses: the
benchmark's A/B arm, whose label outlives the default, and a switch back to today's behaviour.
It is not an emit option: it changes no kernel's bytes and no shape key, so the option matrix
gains no configuration.

### 4.4 The reader

`E/varka/VarkaColumnRange.java`, new, a final class of statics:

    public static boolean of(ColumnVector column, long srcData, long srcValidity,
        int srcNullCount, int length, long[] out)
    public static boolean of(ColumnVector column, long srcData, long srcValidity,
        int srcNullCount, int length, long[] out, boolean check)
    public static long fromStatistics()
    public static long passes()

The six-argument method returns the seven-argument one with `VarkaMemorySanitizer.ENABLED` as
`check`. The seven-argument form exists for the suite, as `LongRangeOps`'s own longer form
does. Its body, in this order:

1. If `length > 0 && srcNullCount < length`:
   - if `column instanceof VarkaRangedArrowColumnVector ranged`: when `check`, call
     `verify(ranged, srcData, srcValidity, srcNullCount, length, out)`; then
     `out[0] = ranged.least(); out[1] = ranged.greatest();`, increment the statistics count
     and return true;
   - otherwise increment the pass count.
2. `return LongRangeOps.leastAndGreatest(srcData, srcValidity, srcNullCount, length, out);`

`verify` is private. It runs `LongRangeOps.leastAndGreatest` into `out` and throws
`new VarkaMemoryViolation("the cached batch's range [" + ranged.least() + ", " +
ranged.greatest() + "] is not the column's own, [" + out[0] + ", " + out[1] + "]")` unless
the pass returned true and both ends are equal. The two counts are `LongAdder`s; `verify`'s
pass counts as neither.

What follows from that body:

- **A batch with no live row** (`length == 0`, or every row null) never reads the carrier. The
  vector's own null count decides, and `LongRangeOps` returns false with the empty range
  without reading a row. So the caller's branch for no live row (`VarkaBatchArgs.neutral`) is
  reached as before.
- **Null rows.** The writers skip them (fact 2), as the pass does. A null row's storage is in
  neither source's range and still runs through the kernel, as `VARKA-657.md` 3.3 has it.
- **The ends of a long.** A bound of `Long.MIN_VALUE` or `Long.MAX_VALUE` is a value like any
  other: with a live row at each end the range is the whole of a long, and the caller's test of
  18 hours from an end declines the batch as it does after a pass. The row writer's start
  values are the one pair with least above greatest, and 4.1 keeps it out.
- **Exactness.** Under `check` the two sources must agree to the microsecond. Equality is more
  than fact 13 needs, and it is what facts 2, 8 and 11 promise; it also means the fill writes
  the same arguments from either source, so no form and no answer depends on the source.
- **Width.** The reader is scalar code and the same at every vector width. What differs by
  width is what it saves: the pass costs more at two 64-bit lanes than at four (section 2).
- **Per batch,** the reader is one type test, two loads and one counter. It allocates
  nothing, and holds no stream, lambda or boxing.

`VarkaMemoryViolation` is used for a finding that is not about memory. The reason is fact 14:
it is the one throwable that reaches a test. Its message says what was compared.

### 4.5 The site

One statement changes. At the site, the call `LongRangeOps.leastAndGreatest(d, v, n, len, out)`
becomes `VarkaColumnRange.of(c, d, v, n, len, out)`, where `c` is the `ColumnVector` whose data
buffer's address `d` is: in `VarkaKernelRunner.fill`, where `d` is `srcData[k]`, it is
`input.column(inputOrdinals[k])` with the same `k`; in `VarkaBatchRanges.measure`, it is the
`batch.column(...)` expression the method reads the vector from. Nothing else at the site
changes: the buffers are registered with the sanitizer as before, and the caller's handling of
false, of the 18 hours and of the count of changes reads `out` as before.

The warm-up and a reproduction need nothing. `VarkaWarmupGate` calls `fill` on the real batch
on the task's thread, and both keep the arguments as filled (`VARKA-682.md` 4.8).

### 4.6 What gets a range, and what does not

| Input of the kernel | Range from |
|---|---|
| a `TIMESTAMP` or `TIMESTAMP_NTZ` column of an Arrow-cached table, read by the scan | the statistics |
| the same through a Varka projection that forwards it, or a Varka filter that keeps every row of the batch | the statistics: the vector is the scan's |
| the same after a Varka filter that dropped a row | the pass, over the new vector: its range may be narrower than the cached batch's |
| a cached batch with no live row in the column, from either writer | neither: no live row |
| a cached table read with `spark.sql.codegen.varka.cachedRange.enabled` false | the pass |
| any other Arrow input: an Arrow data source, a Python UDF's output, another Varka node's output | the pass |
| a column of another type (`BIGINT`, `DATE`, the nanosecond timestamps, `TIME`, intervals) | not handed over; no program reads them today |
| a computed operand | row 688 |

Nothing declines by name in this row: it adds no decline, no status and no reason text. A
`DATE` column's bounds are ints and a nanosecond timestamp's are `TimestampNanosVal` objects, so
`of` as written must not be asked for them; row 688 or 673 extends `ranged` and `of` together if
it needs them.

## 5. The parts

Two pull requests. `VARKA-657.md` 8 gave the row one; the measurement is a part of its own
because its results come from the runners after the code has merged, as in rows 682 and 685.
Each sheet follows `sql/varka/plans/m8/BUILD_SHEETS.md`: the exports, the lock, the linters, one
local commit, no push. No branch is named `varka-687`, which held this plan. Part a's last step
makes row 687's line in `sql/varka/plans/m9/PLAN.md` read `**Started**` where its status word
is (`**Filed**` or `**Planned**`); part b's makes it `**Done**`. Every shell starts:

    export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
    A=sql/core/src/main/scala/org/apache/spark/sql/execution/columnar
    E=sql/core/src/main/java/org/apache/spark/sql/execution
    V=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    C=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen
    S=sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala
    XS=sql/core/src/test/scala/org/apache/spark/sql/execution
    XJ=sql/core/src/test/java/org/apache/spark/sql/execution
    B=sql/core/src/test/java/org/apache/spark/sql/execution/benchmark

| Part | Title | Branch | Counted lines, estimate |
|---|---|---|---:|
| a | `[VARKA-687] Hand a cached batch's range to the zoned kernels, in place of the pass` | `varka-687a-range` | 640 |
| b | `[VARKA-687] Measure the cache's statistics against the pass` | `varka-687b-measure` | 280 |

A ScalaTest run passes with `failed 0` and no `*** FAILED ***`; a JUnit class prints sbt's
`Passed: Total N, Failed 0, Errors 0, Passed N`.

### 5.1 Part a: the hand-off

**B0, the premises.** Each line prints the number after it, or the builder stops.

    git branch --list 'varka-687a-range' | wc -l                                                   # 0
    grep -c 'new ColumnarBatch(selectedColumns, root.getRowCount)' $A/ArrowCachedBatchToColumnarBatchIterator.scala   # 1
    grep -c 'new ArrowColumnVector(' $A/ArrowCachedBatchToColumnarBatchIterator.scala              # 2
    grep -c 'stats' $A/ArrowCachedBatchToColumnarBatchIterator.scala                               # 0
    grep -c 'prefetchEnabled: Boolean = false) extends Iterator\[ColumnarBatch\]' $A/ArrowCachedBatchToColumnarBatchIterator.scala   # 1
    grep -c 'val nextCachedBatch = batchIter.next().asInstanceOf\[ArrowCachedBatch\]' $A/ArrowCachedBatchToColumnarBatchIterator.scala   # 1
    grep -c 'val prefetchEnabled = conf.arrowCachePrefetchEnabled' $A/ArrowCachedBatchSerializer.scala   # 2
    grep -rl 'new ArrowCachedBatchToColumnarBatchIterator(' sql/core/src | wc -l                   # 2
    grep -c 'if (hasValue) (min, max) else (null, null)' $A/ArrowCachedBatchSerializer.scala       # 15
    grep -c 'protected var lower = Long.MaxValue' $A/ColumnStats.scala                             # 1
    grep -c 'cachedRange' $S                                                                       # 0
    grep -c 'val VARKA_WARMUP_ENABLED = buildConf("spark.sql.codegen.varka.warmup.enabled")' $S    # 1
    grep -c 'def varkaWarmupEnabled: Boolean = getConf(VARKA_WARMUP_ENABLED)' $S                   # 1
    grep -c 'public final class VarkaMemoryViolation extends RuntimeException' $V/VarkaMemoryViolation.java   # 1
    grep -c 'public static boolean leastAndGreatest(' $V/LongRangeOps.java                         # 1 (row 683)
    grep -rl 'LongRangeOps.leastAndGreatest(' sql/core/src/main | wc -l                            # 1 (rows 682, 685)
    SITE=$(grep -rl 'LongRangeOps.leastAndGreatest(' sql/core/src/main); echo "$SITE"; grep -c 'LongRangeOps.leastAndGreatest(' "$SITE"   # one path, then 1
    ls $C/VarkaZoneCompiler.java $XS/VarkaTimestampZoneQuerySuite.scala | wc -l                    # 2 (row 657)
    grep -c 'protected var varkaSpark: SparkSession' $XS/VarkaSharedSessions.scala                 # 1
    grep -c 'protected var disabledSpark: SparkSession' $XS/VarkaSharedSessions.scala              # 1
    grep -c '| `spark.sql.codegen.varka.warmup.enabled` |' docs/sql-varka.md                       # 1
    grep -c 'Timestamps in the session time zone' docs/sql-varka.md                                # at least 1 (row 657)
    ls $E/varka/VarkaColumnRange.java $E/varka/VarkaRangedArrowColumnVector.java $XS/VarkaCachedRangeSuite.scala 2>/dev/null | wc -l   # 0

`SITE` is `$E/varka/VarkaKernelRunner.java` or `$E/varka/VarkaBatchRanges.java`. If
`LongRangeOps` lives in another folder, find it with `git grep -l 'class LongRangeOps'` and use
that path. If the site's call does not make plain which column its addresses came from, stop.

**Steps.**

1. `S`: the conf and its accessor, as 4.3. Copy the `.version(...)` line of
   `VARKA_WARMUP_ENABLED`.
2. `E/varka/VarkaRangedArrowColumnVector.java`: new, as 4.1. Imports by name:
   `javax.annotation.Nullable`, `org.apache.arrow.vector.ValueVector`,
   `org.apache.spark.sql.catalyst.InternalRow`, `VarkaErrors`,
   `org.apache.spark.sql.vectorized.ArrowColumnVector`. The class comment says what the range
   is (the least and greatest live value of the vector's rows, both of them values of live
   rows), who builds it (the Arrow cache's scan, from the cached batch's statistics) and who
   reads it (`VarkaColumnRange`).
3. `E/varka/VarkaColumnRange.java`: new, as 4.4. A private constructor; two
   `private static final LongAdder` fields; the comment of `passes()` and `fromStatistics()`
   says that a test and a benchmark's check read them, as `VarkaMemorySanitizer.checked()` is
   read.
4. `A/ArrowCachedBatchToColumnarBatchIterator.scala`, as 4.2: the parameter, `ranged`,
   `prefetchStats`, the local `stats` in `next`, the method `column`, and both `new
   ArrowColumnVector(...)` expressions replaced by calls of `column`. Add the imports
   `org.apache.arrow.vector.FieldVector`, `org.apache.spark.sql.catalyst.InternalRow` and
   `org.apache.spark.sql.execution.varka.VarkaRangedArrowColumnVector`. Do not move
   `submitPrefetch()`: it stays the last statement before `batch`.
5. `A/ArrowCachedBatchSerializer.scala`, `convertCachedBatchToColumnarBatch`: read the conf on
   the driver and pass it, as 4.2.
6. `XJ/columnar/VarkaCacheBatches.java`, `each`: the new last argument,
   `conf.varkaCachedRangeEnabled()`.
7. `SITE`: the one statement of 4.5, and its import. Add one sentence to the method's comment:
   a column of the Arrow cache brings its range with it and is not read here.
8. `XS/VarkaCachedRangeSuite.scala`: new (below).
9. `docs/sql-varka.md`, by hand: one row in the table of section "Configuration", directly
   after the row of `spark.sql.codegen.varka.warmup.enabled`: the key, `true`, and the conf's
   `doc` text in one sentence; and one sentence at the end of the section "Timestamps in the
   session time zone": over the Arrow cache a batch's range comes from the cached batch's
   statistics, and over any other input from a pass over the column.
10. `sql/varka/plans/m9/PLAN.md`: row 687's status word becomes `**Started**`.
    `sql/varka/plans/m9/VARKA-687.md` section 11: what part a built.

**The tests.** `VarkaCachedRangeSuite extends QueryTest with VarkaSharedSessions with
VarkaTestWatchdog`, in package `org.apache.spark.sql.execution`. Its helpers:

- `ranges(serializer, cached, cacheAttrs, selectedAttrs, conf)`: reads `cached` through
  `convertCachedBatchToColumnarBatch` and, inside `mapPartitions`, returns for each batch and
  each column a tuple: whether the column is a `VarkaRangedArrowColumnVector`, whether its
  class is exactly `ArrowColumnVector`, its `least()` and `greatest()` (0 where plain), and the
  least and greatest live value read back row by row with `isNullAt` and `getLong`, with the
  count of live rows. The serializer is `new ArrowCachedBatchSerializer`; the conf is a clone
  of `spark.sessionState.conf` on which the test sets what it needs with `setConfString`, so no
  test depends on the matrix's batch size.
- `withVector(values, isNull)(body)`: a child allocator of `ArrowUtils.rootAllocator`, a
  `TimeStampMicroTZVector("ts", allocator, "UTC")` with `allocateNew`, `set(i, values(i))` for
  every row, then `setNull(i)` for the null rows (so a null row's storage keeps its value), then
  `setValueCount`; the vector and the allocator are closed in a `finally`.
- `range(column, vector, check)`: calls the seven-argument `VarkaColumnRange.of` with the
  vector's data address, validity address, null count and value count, and returns the answer
  with a copy of `out`.
- `withZone(zone)(body)`: sets `SQLConf.SESSION_LOCAL_TIMEZONE` on `varkaSpark` and
  `disabledSpark` and restores both.

| # | Test | What it holds | The failure only it catches |
|---|---|---|---|
| 1 | a column written from rows carries each batch's own range | 95 rows of one partition, 10 rows a batch, every seventh null, values in no order: ten batches, each ranged, each range equal to the least and greatest live value read back | the range of the partition, or of another batch |
| 2 | a column written from columnar batches carries each batch's own range | three input batches built with `TimeStampMicroTZVector`, null rows whose storage holds `Long.MinValue`: each range equals the live rows' | a null row's storage counted as a value |
| 3 | a batch with no live row carries no range, from either writer | all rows null, written from rows and from a columnar batch: the column's class is exactly `ArrowColumnVector` | the row writer's start values handed over as a range |
| 4 | the range follows its column through a selection of the cache's columns | cache columns `(i INT, ts TIMESTAMP, l BIGINT, n TIMESTAMP_NTZ)` with different ranges; selected `(n, ts)`: each ranged with its own; selected `(l, i)`: both plain | a bound read at the selected position, not at the cache ordinal |
| 5 | with prefetch each batch carries its own range | prefetch on, five batches with disjoint ranges, a 20 ms wait before each read: each range equals its own rows' | the prefetched batch's statistics on the batch in hand, or none |
| 6 | with the conf off the scan hands plain vectors | `cachedRange.enabled` false in the conf passed: no column is ranged | a switch that switches nothing |
| 7 | the ends of a long are a range | live `Long.MinValue` and `Long.MaxValue` in one batch: ranged, with those ends; one live row of `Long.MaxValue` beside nulls: ranged, both ends `Long.MaxValue` | an end of a long taken for the sign of no value |
| 8 | the range survives a cached batch stored on disk | `persist(StorageLevel.DISK_ONLY)`, count, read: the ranges of test 1 | statistics lost or changed by serialization |
| 9 | `of` reads a statistics row as the cache wrote it | a null row, a row too short, a null lower, a null upper, lower above upper: plain; equal bounds: ranged; ordinal 1 reads fields 5 and 6 | each branch of the layout's one reader |
| 10 | a ranged vector refuses least above greatest | the constructor throws, the message holds both numbers | an empty range that travels |
| 11 | a ranged column answers from its bounds and reads no row | data 1, 2, 3, 4 and a ranged vector of (5, 9), `check` false: true, `out` is (5, 9), `fromStatistics()` rose by one and `passes()` did not move | a reader that still runs the pass |
| 12 | a plain column takes the pass | the same data in a plain `ArrowColumnVector`: true, (1, 4), `passes()` rose by one | a reader that answers nothing for a plain column |
| 13 | no live row is no range, whatever the vector claims | a ranged vector of (5, 9) with every row null, and with length 0: false, `out` is the empty range, neither count moved | the carrier trusted over the vector's own null count |
| 14 | under the check, bounds that are the column's own pass | data `Long.MinValue` (null), 3, 7 and a ranged vector of (3, 7), `check` true: true, (3, 7) | a check that reads a null row's storage |
| 15 | under the check, a bound that is not the column's own throws | data 1 to 4 with ranged (1, 9), then with ranged (0, 4), `check` true: each throws `VarkaMemoryViolation` whose message holds all four numbers | a check that compares one end, or nothing |
| 16 | a zoned kernel over cached columns runs no pass and answers as the row engine | below | a site that hands the reader another column, or none |
| 17 | with the conf off the same query runs the pass and answers the same | test 16's query with `cachedRange.enabled` false on `varkaSpark`: `passes()` rose, `fromStatistics()` did not move, the rows are equal | a pass that the hand-off broke |

Test 16, under `withZone("America/Los_Angeles")`. The table `varka_cached_range` has columns
`(id BIGINT, a TIMESTAMP, b TIMESTAMP)` and 600 rows in two partitions of 300, built with
`createDataFrame(sparkContext.parallelize(rows, 2), schema)` and cached with
`catalog.cacheTable` and a `count()`, on `varkaSpark` and on `disabledSpark`. In partition 0
`a` lies in July 2024 and `b` in December 2024; in partition 1 `a` lies in December and `b` in
July; every seventh `a` is null; the values are `java.sql.Timestamp.from(Instant)`. So every
batch of every batch size holds each column inside one offset, and every form of a zoned
kernel covers it. The query is `SELECT id, hour(a), hour(b) FROM varka_cached_range`. The test
reads both counts, collects the query on `varkaSpark`, and checks: the rows equal
`disabledSpark`'s, sorted by `id`; `passes()` did not move; `fromStatistics()` rose;
`assertKernelsRan` on the executed plan; and the Varka node's metric
`numFallbackBatchesDeclined` is 0. The column `id` is in the batch before `a` and `b`, so the
kernel's inputs are not the batch's first columns.

**The commands, in order.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaCachedRangeSuite *ArrowCachedBatchSerializerSuite *VarkaTimeArrowCacheSuite *VarkaBatchArgRunnerSuite *VarkaTimestampZoneQuerySuite *VarkaTimestampZoneSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *VarkaCachedRangeSuite *VarkaBatchArgRunnerSuite *VarkaTimestampZoneQuerySuite *VarkaTimestampZoneSuite'
    grep -c 'LongRangeOps.leastAndGreatest(' "$SITE"; grep -c 'VarkaColumnRange.of(' "$SITE"
    git diff --stat $(git merge-base origin/master HEAD) -- sql/catalyst/src/main/java sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json | wc -l
    python3 dev/varka_push_check.py

Expected: in both runs `failed 0`, no `*** FAILED ***`, `Failed 0, Errors 0` on the JUnit
line, none canceled, and `VarkaCachedRangeSuite` reports 17 tests; the greps print 0 and then
1; the diff prints 0 (the part touches Scala in `sql/catalyst` and no Java there, and moves no
generated file); the push check exits 0. The second run is the differential check of the whole
hand-off: with the sanitizer on, every zoned kernel that reads a ranged column compares the
statistics with the pass, and a difference fails its test.

**Planted faults.** Commit first; plant one, run the first command, see the named test fail,
and put the file back.

| Fault | The test that must fail |
|---|---|
| in `next`, call `column(v, i, stats)` with the position `i` for `columnIndices(i)` | 4 |
| in `VarkaRangedArrowColumnVector.of`, drop the test of lower above upper | 3 (the constructor throws) |
| in `submitPrefetch`, do not set `prefetchStats` | 5 |
| build `ranged` without `rangesEnabled` | 6 |
| in `VarkaColumnRange.of`, skip the `instanceof` branch | 11, and 16 |
| in `VarkaColumnRange.of`, test `length > 0` only | 13 |
| in `verify`, compare the least ends only | 15 (its first case) |
| at the site, pass `input.column(k)` for `input.column(inputOrdinals[k])`, or the same slip in `VarkaBatchRanges` | 16: `passes()` moves (the column is `id`), or the rows differ |

**Done when** the commands print the above and each fault fails its test. **Stop** on any of
`BUILD_SHEETS.md` 8, and when test 16 finds that row 657's kernel does not fuse `hour(a)` under
the shared sessions.

**Not here:** no change to `collectStatistics`, to a `ColumnStats` class or to what a cached
batch stores; no range for another type; no metric, no status, no decline text; no change to
`vectorTypes`, to `VarkaColumnarRule` or to an emit option; no benchmark.

### 5.2 Part b: the measurement and the close

**B0.**

    git branch --list 'varka-687b-measure' | wc -l                                                 # 0
    grep -c 'public static long passes()' $E/varka/VarkaColumnRange.java                           # 1 (part a)
    grep -c 'protected static void withConf(' $B/VarkaSqlBenchmarkBase.java                        # 1
    grep -c 'public static void requireKernel(SparkSession varka, String title, String query)' $B/VarkaSqlBenchmarkBase.java   # 1
    grep -c 'def cache(session: SparkSession, view: String): Unit' sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark/VarkaArrowSessions.scala   # 1
    ls $B/VarkaCachedRangeBenchmark.java 2>/dev/null | wc -l                                       # 0
    grep -l 'America/Los_Angeles' $B/Varka*BaselineBenchmark.java                                  # row 655's zoned classes: at least one path

**Steps.**

1. `B/VarkaCachedRangeBenchmark.java`: new, Java, on `VarkaSqlBenchmarkBase`, its
   `runBenchmarkSuite` modelled on `VarkaTimestampNtzFieldsBaselineBenchmark`'s: two sessions
   from `VarkaArrowSessions.createSession` (Varka off and on, `local[1]`), each with
   `SQLConf.SESSION_LOCAL_TIMEZONE` set to `America/Los_Angeles` before the view is cached.
   - The view `varka_cached_range_bench`, 2,000,000 rows, from `range`: `wk` is
     `timestamp_micros(1717200000000000 + (id * 7919 % 604800) * 1000000)`, a week from 1 June
     2024 in no order, with no change of offset; `yr` is
     `timestamp_micros(1704067200000000 + (id * 7919 % 31536000) * 1000000)`, the year 2024 in
     no order, with two changes; `wk_n` and `yr_n` are the same with `IF(id % 10 = 3, NULL,
     ...)`.
   - Four sections: `hour(wk)`, `hour(yr)`, `hour(wk_n)`, `hour(yr_n)`, titled "hour(ts), a
     week, no nulls", "hour(ts), a year in no order, no nulls" and the two with ", one row in
     ten null".
   - Three cases a section, in this order: `spark row path (varka off)`; `varka on, range from
     the cache's statistics`; `varka on, range by a pass`, whose query runs inside
     `withConf(varka, Map.of(SQLConf.VARKA_CACHED_RANGE_ENABLED().key(), "false"), ...)`.
   - Before any timing, for each section: `requireKernel(varka, title, query)`; then one run of
     each Varka case with `VarkaColumnRange.passes()` and `fromStatistics()` read before and
     after. The statistics case must leave `passes()` where it was and raise the other; the
     pass case the reverse. Otherwise the class throws `IllegalStateException` with the
     section's title: an arm that measures the wrong source is worse than no arm.
2. The dry run on the laptop (below). The lead dispatches the timed runs on the EPYC 7763
   runners, at 256 bits and with `-XX:MaxVectorSize=16`, for `VarkaCachedRangeBenchmark` and
   for each class that B0's last line printed, on the pull request's head:

       gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> \
         -f create-commit=false -f class=<Class> -f jdk=25 -f expected-cpu="AMD EPYC 7763"

   and the same with `-f extra-java-options=-XX:MaxVectorSize=16`. A run on another CPU is
   dispatched again. The builder commits the results and provenance files of both widths from
   the runs' artifacts.
3. For each of row 655's classes, before the commit: `python3 dev/varka_bench_diff.py --git
   HEAD <file>`, controls first, then the same with `--requote`, which must list nothing, or
   the quoting line is requoted from this run.
4. `sql/varka/skills/testing-and-debugging.md`: one `##` section: a value that a batch's
   producer already knows travels on the column vector, and a second source of a value a
   kernel depends on is compared with the first under the sanitizer. Then
   `python3 dev/varka_toc.py SKILLS.md --from sql/varka/skills`.
5. This plan's section 11 and row 687's line in `PLAN.md` (`**Done**`): each prediction of
   section 7 scored, each scored cost prediction added with
   `python3 dev/varka_calibration.py --add-row`.

**The commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" 'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaCachedRangeBenchmark" 'set Test/javaOptions += "-XX:MaxVectorSize=32"' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaCachedRangeBenchmark" 'set Test/javaOptions += "-XX:MaxVectorSize=16"' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaCachedRangeBenchmark" > target/dry-687.log 2>&1; echo $?
    grep -c 'Exception' target/dry-687.log
    python3 dev/varka_plan_lint.py --check --offline
    python3 dev/varka_push_check.py

Expected: `0`; then `0`; the lint and the push check exit 0.

**Done when** the dry run passes at three widths, the results of both widths are committed
for the new class and for row 655's zoned classes, and every prediction has a score. **Not
here:** no timed run on the laptop beside other agents; no change to main code; no number in a
document that is not in a results file; no change of a default.

## 6. Tests, proofs, fuzzing and coverage

| Suite | Language, module | Holds | The failure only it catches |
|---|---|---|---|
| `VarkaCachedRangeSuite` (new) | Scala, sql/core | the scan's hand-off from both writers; the carrier; the reader and its check; a zoned query with no pass | a range that is not its batch's, which is a wrong answer no fallback catches |
| `VarkaTimestampZoneSuite` (row 657), with the sanitizer | Java, sql/core | every zoned check of the session-free harness now reads ranged vectors (4.2) and compares them with the pass | statistics that differ from the rows on a fixture this row did not write |
| `VarkaTimestampZoneQuerySuite` (row 657), with the sanitizer | Scala, sql/core | the same through sessions and cached views | the same on a path only a session has |
| `VarkaBatchArgRunnerSuite` (rows 682, 685) | Scala, sql/core | hand-built plain vectors: the pass still fills | a reader that broke the plain path |
| `ArrowCachedBatchSerializerSuite`, `VarkaTimeArrowCacheSuite` (existing) | Scala, sql/core | Spark's own cache tests and the lane layout, unchanged | a subclass or a constructor change that moved the cache's behaviour |

- **Unchanged suites.** No existing test is edited. `VarkaCacheBatches.each` is a helper, and
  its edit is one argument.
- **Proofs.** No lowering is added, so no proof file. The claim the row rests on, that the
  statistics are the pass's answer, is a fact of the cache's writers; the check of 4.4 holds it
  in every sanitizer run, which is every matrix run.
- **Fuzzing.** `VarkaSparkFuzzSuite` runs sessions over cached data, so from part a on its
  timestamp columns arrive ranged, and under the sanitizer each batch is compared. Nothing new
  is drawn.
- **Coverage.** `dev/varka_gen_coverage.sh` after part a: every line and branch of
  `VarkaRangedArrowColumnVector` and `VarkaColumnRange` reached. One branch of the iterator is
  not reached by any test: the one that loads every cache column, which master takes only when
  a selected attribute is absent from the cache (fact 8). The pull request says so.
- **Mutation.** `dev/varka_pit.sh` mutates classes of `codegen/varka`; the two new classes are
  in `sql/core`. The planted faults of 5.1 stand in for it.
- **The matrix.** No configuration is added (4.3). Test 16 holds under every form of a zoned
  kernel and every batch size by its data, and needs no line in `sql/varka/matrix/skips.tsv`.

## 7. Measurement

`VarkaCachedRangeBenchmark` (part b) runs on the AMD EPYC 7763 runners at 256 and at 128 bits;
the laptop adds 512 when idle. Its A/B arms are named by the source of the range, so the labels
outlive the default. The control of each section is the row path. Row 655's zoned classes are
run again on the same head, and diffed against the files row 657 committed. A ratio under 1.3
is run again and compared by minimums.

What the predictions rest on: the probe's pass, 0.232 ns a row at four lanes and 0.393 at two
on the laptop (`sql/varka/spikes/zone-offset/results/forms.txt`); `VARKA-683.md` 7.1, which
scales it to the runner; and the calibration table's two rows for VARKA-379, where a pass of
its own ahead of a cheap kernel cost 14 to 17 percent of a section by medians.

**Predictions, registered on 26 November 2026, before any run.**

| # | Prediction | Scored by |
|---|---|---|
| P1 | "hour(ts), a year in no order, no nulls" at 256 bits: the statistics arm takes at least 150 and at most 450 picoseconds a row less than the pass arm | part b, by minimums of two runs |
| P2 | the same section at 128 bits: at least 300 and at most 750 picoseconds a row less | part b |
| P3 | "hour(ts), a week, no nulls" at 256 bits: the statistics arm's rate is between 1.04 and 1.2 times the pass arm's | part b, by minimums |
| P4 | with one row in ten null, the statistics arm saves at least what it saves with no nulls, in each of the two data sets at 256 bits: yes | part b |
| P5 | the row path's case does not move: in each section of row 655's zoned classes, `spark row path (varka off)` is within its band, or within 5 percent where the class has no band, of the file row 657 committed | step 3's diff |
| P6 | `VARKA-657.md` 6.1 prediction 2, second half: the zone's price over the `TIMESTAMP_NTZ` twin at 256 bits is under 300 picoseconds a row with the cache's statistics | row 655's fields class, `hour(ts)` under `America/Los_Angeles` over a year in no order, less its twin |
| P7 | no answer and no emitted byte moves: part a's diff of the generated files prints 0: yes | part a's commands |
| P8 | with the sanitizer on, no suite of the gate or the matrix reports a range that is not its column's own: yes | part a's second command, and the first matrix run of its pull request |
| P9 | `VarkaCachedRangeSuite` takes under 15 s of test time | the suite's reported time |
| P10 | two parts, part a under 800 counted lines and part b under 400 | `dev/varka_msg_check.py size` |

P9 was set after reading `python3 dev/varka_calibration.py`. Its nearest rows: nine tests over
one-row cached views took 5 s, the suite 8.4 s (VARKA-365); short queries over one cached view
took 3.7 s (VARKA-318); and a new sql/core suite ran at about twice its estimate when the
estimate left out its sessions (VARKA-243). Seventeen short tests, two of them queries: about
7 s, doubled.

## 8. Who builds it

Both parts at full strength: `m9/PLAN.md` 1.4 has rows 656, 657 and 682 to 692 so. Part a edits
the scan of Spark's cache and the value every zoned kernel's arguments are made from, where a
slip is a wrong answer; part b's check of its own arms is what makes its numbers mean what
their labels say. The row moves nothing of G5.

## 9. Risks

1. **A range that is not its rows' is a wrong answer** that no fallback catches (fact 13). The
   check of 4.4 compares the two sources in every sanitizer run; tests 1 to 8 hold the scan;
   the conf turns the hand-off off without a build.
2. **A vector forwarded over fewer rows.** Today a kernel runs only on a vector that holds
   exactly the batch's rows (fact 11), and a filter that drops a row builds new vectors (fact
   10). A later change that forwards the scan's vector over a subset of its rows would keep the
   answers right (fact 13) and fail the check, which asks for equality. That failure is the
   signal to decide then between containment in the check and a plain vector for such a batch.
3. **The site moves.** Row 685's part b moves the pass from the runner to `VarkaBatchRanges`.
   B0 finds the one call wherever it is. If the two rows are built side by side, the second to
   merge moves or keeps a one-line call, and test 16 fails if it is lost.
4. **Spark's cache files are shared with upstream.** The upstream sync (row 663) merges
   changes to `ArrowCachedBatchToColumnarBatchIterator`. The edit there is one parameter, two
   fields and one small method; the layout's reader and the range's logic are in Varka's own
   two classes.
5. **Two JVM-wide counts.** Tests 11, 12, 13, 16 and 17 read differences of them, which holds
   while one test runs at a time in a JVM, as the sql module's suites do. A warm-up job calls no
   reader: `VarkaWarmupGate` fills on the task's thread.
6. **The row path over a cached timestamp.** Every reader of the scan now meets a subclass for
   the two timestamp types. It overrides nothing, so a call's target does not change. P5 reads
   the row path's case against its committed file.
7. **The saving is smaller than the pass.** After a pass the kernel finds the column in the
   cache; without it the kernel pays that miss itself. P1 to P3 are set below the probe's
   stand-alone cost for that reason, and the benchmark, not the probe, scores them.
8. **The plan ages before it is built.** Rows 682, 683, 685, 655 and 657 are not built. Each
   B0 greps their names, and a premise that fails stops the part.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md` and `BUILD_SHEETS.md`, the rules a builder here is likeliest to
break:

- "No allocation, streams, lambdas or boxing on a path that runs per batch or per row."
  `VarkaColumnRange.of` runs for every batch; `VarkaRangedArrowColumnVector.of` runs for every
  ranged column of every batch and allocates the one vector the scan allocated before.
- "Anything that can return a *wrong* answer rather than fail ... gets its own differential
  coverage, because the fallback cannot catch it." The range is such; 4.4's check is its
  coverage.
- "A comment describes the code as it is": no "used to", no "the old", no "since VARKA-687".
  The Varka code carries no `TODO` or `FIXME`.
- "A Varka conf without a binding policy" fails the lint: the new conf keeps its
  `.withBindingPolicy` line.
- "Explicit imports", lines within 100 columns, ASCII outside string literals, and
  `dev/lint-java` and `dev/lint-scala` pass. New production code is Java; the scan's file and
  the suite stay Scala, since Spark's cache and its sessions are Scala.
- "Any performance claim ... traces to a committed benchmark result file": part a's
  description quotes no speed; part b quotes its own results files.
- Generated files are never edited by hand, and none moves here. A pull request stays under
  1000 counted lines. One commit, with one `Generated-by:` line and no other trailer.

Do not: change what the cache stores or how it computes a bound; hand a range for a type this
plan does not name; relax the check to pass a test; add a metric, a status or a decline; make
the conf an emit option or add a matrix configuration; read the conf on an executor; time
anything on the shared laptop; push or open a pull request.

## 11. Outcome

To be written when the row closes.
