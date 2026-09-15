# Task 116: a `TIME` column through Varka's Arrow cache, proven

*Milestone 5, section 2.51. Opened 15 September 2026 at the owner's request; planned
and implemented the same day. Wave 0: before any lane code.*

## 1. Where this came from

Milestone 5's subject is `TIME`, and everything it plans to emit assumes a `TIME`
column arrives in a Varka morsel as eight-byte lanes with a correct validity
word. Every piece of that path exists - Spark's `ArrowUtils` admits `TimeType`
and tags the field with its precision (SPARK-57661), `ArrowWriter` has a
`TimeNanoVector` writer and a `DurationVector` writer, `ArrowColumnVector` has the
accessors, `ArrowCachedBatchSerializer` has `LongColumnStats` arms for both types,
and `VarkaKernelEvaluator.extractMorsel` maps any `BaseFixedWidthVector`'s buffers
as `MemorySegment`s - and no test composes them along Varka's path. A milestone
whose subject cannot be cached has no benchmark, so this is the first task, and it
is a test.

An earlier draft of the milestone plan opened this task on the belief that the
serializer's stats switch lacked the two arms. It does not; 1.1 there records the
correction, and the task survived it in this honest form: prove the composition,
do not assume it.

## 2. The admission check, done

Read rather than assumed, in the tree on 15 September 2026:

* **Write side.** `ArrowUtils.toArrowField` routes `TimeType` through
  `toPrecisionTaggedArrowField`, storing the precision in field metadata under
  `SPARK::time::precision`; `ArrowWriter` matches `TimeNanoVector` for it and
  `DurationVector` for `DayTimeIntervalType`; `ArrowCachedBatchSerializer` builds
  its schema with `losslessInternalTypes = true`, and `createColumnStats` has
  `case _: TimeType => new LongColumnStats` and the same for the interval.
* **Read side.** The serializer rebuilds vectors from the Spark schema
  (`DataTypeUtils.fromAttributes`), so precision reaches a consumer through
  Catalyst's `DataType`, not through the Arrow metadata; `ArrowColumnVector`
  dispatches `TimeNanoVector` to `TimeNanoAccessor` and `DurationVector` to
  `DurationAccessor`.
* **Varka's side.** `extractMorsel(v: BaseFixedWidthVector, len)` requires
  `v.getValueCount == len` and maps the data buffer, and the validity buffer
  unless every row is null, with `MemorySegment.ofAddress(buf.memoryAddress())
  .reinterpret(buf.capacity())`. `TimeNanoVector` and `DurationVector` are both
  `BaseFixedWidthVector`s of width eight, so the mapping is the same call the date
  columns take.
* **The gate that does not yet open.** `isArrowBacked` admits input columns by
  vector class - `DateDayVector`, `IntVector`, `IntervalYearVector`, and
  `VarCharVector` for derived leaves - so today a `TIME` input makes the evaluator
  decline the batch to the row engine. That is correct behaviour, not a defect,
  and it is the first line task 29 changes. This task does not touch it: the
  proof is that the batch *would* map, one call below that gate.

So the prediction is that the composition works and the test passes on the first
run. If it does not, the failure is a real bug in the path and becomes the
milestone's first fix; either way the answer is known before 84 and 85 build on
it.

## 3. The design

### 3.1 The mechanism

One new suite, `VarkaTimeArrowCacheSuite` in `sql/core`, on `VarkaSharedSessions`
(which sets the Arrow serializer and the vectorized cache reader). No production
code changes. Four fixtures: `TIME(p)` for `p` in {0, 3, 6, 9}, and
`INTERVAL DAY TO SECOND`, each over three null patterns the date fixtures use -
every 31st row null, no nulls, all nulls - built from local rows
(`java.time.LocalTime` and `java.time.Duration`, which `CatalystTypeConverters`
already converts), with the `LocalTime` values truncated to `p` digits so the
precision question cannot hide behind a value the type would round.

Four assertions per fixture:

1. **Round trip.** The cached DataFrame answers exactly what the uncached one
   does, and the cached scan's output type is `TimeType(p)` with the same `p`.
2. **The vector class.** Reaching the `ArrowCachedBatch` the way the serializer
   suite does - `InMemoryTableScanExec.relation.cacheBuilder.cachedColumnBuffers`
   - and converting it with the serializer's own
   `convertCachedBatchToColumnarBatch`, the column is an `ArrowColumnVector`
   over a `TimeNanoVector` (or `DurationVector`) with `getValueCount` equal to
   the batch's rows.
3. **The lanes.** The data buffer mapped exactly as `extractMorsel` maps it,
   `MemorySegment.ofAddress(...).reinterpret(capacity)`, read as `rows` longs at
   stride eight, equals the expected nanoseconds-of-day (or microseconds) at
   every non-null row.
4. **The word.** `getNullCount` equals the pattern's null count; for the all-null
   pattern it equals `rows`, which is the case `extractMorsel` handles by passing
   no validity segment; otherwise the validity buffer mapped the same way has
   exactly the pattern's bits.

### 3.2 What is deliberately unchanged

Everything. `isArrowBacked`'s admission list in particular: widening it without a
kernel that reads the lane would admit batches the emitter then refuses at line
1855, which is a slower way to decline than declining at the gate.

### 3.3 Registered op counts

Not applicable.

## 4. Files

* `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaTimeArrowCacheSuite.scala`
  - new.
* `sql/varka/plans/PLAN_MILESTONE_5.md` - row 116.

## 5. Tests, and what each is for

The suite is the deliverable; section 3.1 says what each assertion proves. The
per-precision fixtures exist because precision is the one property of `TIME` the
date path never had to carry, and the all-null pattern because it is the branch of
`extractMorsel` that returns no validity segment at all.

## 6. The measurement

None.

### 6.1 Predictions, registered before the run

1. All four assertions pass for all five fixtures and all three null patterns on
   the first run.
2. The cached scan's output type carries `p` unchanged - because it comes from the
   Spark schema, not the Arrow field metadata.
3. The data buffer's capacity is at least `8 * rows` bytes and the longs at
   stride eight are the values, with no per-precision scaling: `TimeNanoVector`
   stores nanoseconds whatever `p` is.

## 7. Risks

* **The test proving less than it says.** Reading the buffer through
  `ArrowColumnVector`'s accessor would prove Spark's path, not Varka's; the test
  maps the raw buffer with the same call `extractMorsel` uses, on purpose.
* **Partitioning.** A `LocalRelation` may split across partitions, so the fixture
  is built as a single-slice RDD, as the serializer suite does, and the test reads
  every cached batch rather than the first.

## 8. Sequencing

1. This plan. 2. The suite. 3. Run it; section 9 from the result. 4. The row.

## 9. Outcome

Done, 15 September 2026, on the fork's master after the sync (task 117). The
suite is `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaTimeArrowCacheSuite.scala`,
fifteen tests, and it passes:

    build/sbt -batch "sql/testOnly *VarkaTimeArrowCacheSuite"
    Tests: succeeded 15, failed 0, canceled 0, ignored 0, pending 0

**What the cache does with a `TIME` column, now proven rather than read.** For
`TIME(p)` at every `p` in {0, 3, 6, 9} and for `INTERVAL DAY TO SECOND`, at each
of the three null patterns:

1. the cached frame answers what the uncached one did, and the relation the cache
   manager holds for it carries `TimeType(p)` with `p` unchanged (prediction 2
   held: the type comes from the Spark schema);
2. the serializer's own `convertCachedBatchToColumnarBatch` yields an
   `ArrowColumnVector` over a `TimeNanoVector` (`DurationVector` for the
   interval) whose value count is the batch's row count;
3. the data buffer, mapped with the same `MemorySegment.ofAddress(...).reinterpret`
   call `extractMorsel` uses, read as longs at stride eight, is the nanoseconds of
   day (microseconds for the interval) at every non-null row, with no
   per-precision scaling (prediction 3 held: `TimeNanoVector` stores nanoseconds
   whatever `p` is);
4. `getNullCount` is the pattern's count, `rows` for the all-null pattern, and the
   validity buffer mapped the same way has exactly the pattern's bits.

So the day the emitter admits an eight-byte lane (tasks 85 and 29), the bytes
`isArrowBacked` would hand it are already the right bytes. Nothing in production
code changed, as section 3.2 required.

**Prediction 1 was wrong in the letter and right in the substance.** The first run
failed all fifteen tests, and not one failure was about the cache. Section 3.1
said the test would reach the relation through `InMemoryTableScanExec` in
`df.queryExecution.executedPlan`; but a `Dataset` memoises its `queryExecution`,
and each test collects the frame *before* caching it, so the executed plan the
test inspected was the uncached one forever and held no such scan. The two
symptoms were `None did not equal Some(TimeType(0))` on the type assertion and
"no InMemoryTableScanExec in the cached plan" on the lane check. The fix is the
lookup the cache manager itself performs,
`spark.sharedState.cacheManager.lookupCachedData(df).cachedRepresentation`,
which is also the stronger witness: it says the cache holds *this* plan, not that
some scan appears somewhere. The second attempt did not compile - that lookup
takes `classic.Dataset`, and the helpers were typed with the API `DataFrame` - and
the third run passed all fifteen. Both are the harness, not the cache; the cache
facts held on the first run that reached them.

**What the test does not prove, so no one reads more into it.** It does not
prove Varka evaluates over the column: `isArrowBacked` still admits only the
int32 and string vectors, and the emitter's `LaneType` still has one member.
That is task 85's and task 29's work, and this suite is the fixture they will
widen rather than write.

**Upstream.** `ArrowCachedBatchSerializer` is upstream code (SPARK-57268), and
upstream's `ArrowCachedBatchSerializerSuite` already round-trips a `TIME(6)`
column, checks its `LongColumnStats`, and writes a `TimeNanoVector` in its
schema-to-vector test. What this suite adds over that - every precision, both
signs of interval, the three null patterns, and the raw-buffer reading - is
worth offering upstream only in its first three parts; the raw
`MemorySegment` mapping is Varka's contract with its own evaluator and means
nothing to a reader of vanilla Spark.
