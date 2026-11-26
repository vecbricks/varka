# VARKA-251: `VarkaEvaluatorBase` split into Java components

## 1. Where this came from

Row 251 of `m7/PLAN.md`, item 74.5 of `m10/SCOPE.md`: `VarkaEvaluatorBase` is 1079 lines of Scala
holding the kernel runner, the batch ledger, scratch, the warm-up, fallback accounting and class
dumping, four of which VARKA-190's further kernels needed hooks into. The row asks for Java
components, not smaller Scala files (`m7/PLAN.md` 2.3), and names its proof: a before-and-after
benchmark of per-batch overhead. Row 267 then ports the evaluators themselves beside the
components; this row leaves them in Scala.

## 2. The admission check, done

**What the base holds, by responsibility** (read 6 October 2026, master `cf3146327bb` and later):

| responsibility | members | lines |
|---|---|---|
| runner | `FusedRunner`, `fusedRunner`, `fillSources`, `invokeFused`, `extractMorsel`, `ofAddress`, `Morsel` | about 230 |
| batch ledger | `kernelAllocator`, `openBatches`, `ensureCleanup`, `taskAllocator`, `track`, `trackOwned`, `forwardColumns`, `release`, `closeQuietly`, `closeAllQuietly` | about 150 |
| scratch | `derivedData`, `derivedValidity`, `kernelScratch`, `derivedScratch`, `growSlot`, `kernelScratchAddress`, the three releases | about 170 |
| warm-up | `kernelReady`, `startWarmup`, `anyNullableInput`, `inputWidths`, `warmed` | about 90 |
| fallback accounting | `serveBatch`'s causes: `recordKernelFailure`, `recordRowPathFailure`, `recordDeclinedBatch`, `recordRefusedBatch`, the allocation sample | about 150 |
| class dumping | `dumpClass` and the per-JVM memo | about 30 |
| what stays | the abstract `fusedPlan` and `identityEntries`, `kernelIdentity`, `shapeKey`, `emitOptions`, `canRun`, `isArrowBacked`, `serveBatch`, `runKernel` | |

The subclasses (`VarkaKernelEvaluator`, `VarkaFilterEvaluator`, `VarkaKernelPart`) use the base
through `runKernel`, `serveBatch`, `canRun`, `fusedRunner`, `fillSources`, `invokeFused`,
`taskAllocator`, `trackOwned`, `release`, `closeQuietly`, `closeAllQuietly` and `onTaskCleanup`.

**No benchmark measured per-batch overhead.** The throughput benchmarks run 4096-row cached
batches, where the kernel's rows dominate. So the benchmark comes first, against the unchanged
code, in its own commit (the rule that a baseline is committed before the change it judges):
`VarkaEvaluatorOverheadBenchmark` drives the projection and filter evaluators directly over
pre-built Arrow batches of 1, 16 and 1024 rows, four shapes (int lane, long lane, filter, derived
input), and checks every batch took the kernel. Its first run caught a shape that had stopped
fusing (`l + 1`: bigint arithmetic is not a coverage row; `least(l, 5000000000)` replaced it).

**No test reads these loggers by name**: `VarkaProjectExecSuite` captures at the root, the other
log-capturing suites name unrelated loggers. So the components can log under their own names.

## 3. The design

### 3.1 The components

Six Java classes in `sql/core/src/main/java/org/apache/spark/sql/execution/varka/`, one per
responsibility, each a plain class over the state it owns:

- **`VarkaBatchLedger`**: the task's Arrow child allocator, the open batches, the one
  task-completion listener, `track`, `trackOwned`, `forwardColumns`, `release`, and the quiet
  closes. The listener runs, each guarded on its own, the batch closes, then the cleanup actions
  registered with it (the scratch release, a subclass's hook), then the allocator close: the order
  and guards of today's listener.
- **`VarkaKernelScratch`**: the derived inputs' buffers and the prefix scratch, the
  allocate-store-release order of `growSlot`, and their release, over the ledger's allocator.
- **`VarkaKernelRunner`**: `FusedRunner`'s shape-cache lookup, kernel and argument arrays, and
  `fillSources` and `invokeFused` as `fill` and `invoke`. `VarkaBatchDeclined` and
  `VarkaKernelFailure` move to Java with it.
- **`VarkaWarmupGate`**: `kernelReady` and `startWarmup` over the runner.
- **`VarkaFallbackAccounting`**: the four causes, the allocation sample, and the two JFR events;
  the counters as a record of the `SQLMetric`s `VarkaExecMetrics` holds, null where absent.
- **`VarkaClassDump`**: the dump and its per-JVM memo.

`VarkaEvaluatorBase` stays a Scala class, about 300 lines: it builds the components, keeps the
members subclasses call with the same names, delegating, and keeps `serveBatch`, whose by-name
paths are Scala's (row 267 decides its Java form with the evaluators).

### 3.2 What is deliberately unchanged

The evaluators (row 267), the exec nodes, the emitter and the compiler. Every behaviour: the
cleanup order and guards, the fallback causes and their metrics and events, the warm-up claim
and release, the dump memo, the log messages. The Java rules of `sql/varka/AGENTS.md` hold, in
particular no allocation, stream, lambda or boxing on the per-batch path: `fill`, `invoke`, the
causes and `canRun` stay plain loops, and the identity a fallback event renders is a supplier
built once per evaluator.

### 3.3 Registered op counts

None: no emitter change; `emitted_bytes.json` cannot move.

## 4. Files

| file | what |
|---|---|
| `VarkaEvaluatorOverheadBenchmark.scala` and its results files | the per-batch benchmark, first commit |
| `sql/core/.../execution/varka/*.java` | the six components and the two exceptions |
| `VarkaEvaluatorBase.scala` | the composition |
| `VarkaKernelEvaluator.scala`, `VarkaFilterEvaluator.scala`, `VarkaKernelPart.scala` | their references to the moved members |
| `m7/VARKA-251.md`, `m7/PLAN.md` | this plan, row 251 |

## 5. Tests, and what each is for

- The `sql` module's Varka suites at both widths: every evaluator path, including the task-end
  cleanup suites (an early stop, a release per batch, the leak checks) and the fault-injection
  hooks, which drive each fallback cause.
- `catalyst`'s suites are unaffected but run in the gate.
- The benchmark before and after, below.

## 6. The measurement

`VarkaEvaluatorOverheadBenchmark`, regenerated with `dev/varka_bench_regen.sh core` at both widths
on the laptop, before (the first commit) and after (the split).

### 6.1 Predictions, registered before the run

1. No case slower than its baseline by more than 10%, or by more than the run's own spread where
   that is wider.
2. The 1-row cases, which are nearly all per-batch machinery, are no more than 50 ns a batch
   slower; they may be faster, since the Java `fill` reads each column's addresses directly where
   `fillSources` built a `Morsel` and two `MemorySegment`s per input per batch.

## 7. Risks

1. **The cleanup order changes.** The ledger's listener is written from today's, guard by guard,
   and the task-end suites check it.
2. **A per-batch allocation creeps in through the Java form**, a lambda or a boxed counter. The
   benchmark's 1-row cases would show it, and the code review checks the per-batch methods.
3. **Scala's `private[execution]` members become public Java**: the components are public classes
   in an internal package, as Spark's own Java under `execution` is.

## 8. Sequencing

1. The benchmark and its baseline results, and this plan: the first pull request.
2. The components, one commit each with the base delegating to it, the suites green at each; then
   the benchmark regenerated and section 9 written: the second pull request.

## 9. Outcome

### 9.1 The baseline, 6 October 2026

`VarkaEvaluatorOverheadBenchmark` on `f9ce6653094`, the unchanged evaluators, regenerated with
`dev/varka_bench_regen.sh core` on the laptop at both widths (the files' provenance). Nanoseconds
a batch, wide:

| rows a batch | int lane | long lane | filter | derived input |
|---|---|---|---|---|
| 1 | 620 | 579 | 138 | 812 |
| 16 | 589 | 592 | 518 | 1352 |
| 1024 | 822 | 1313 | 1233 | 30398 |

### 9.2 The split, 6 October 2026

The six components, with `VarkaEvaluatorBase` a Scala composition of them, at `644a2faa36b`.
`VarkaEvaluatorOverheadBenchmark` ran ten times at each vector width on the split and ten times on
the unchanged evaluators (`7e8faaa2b21`), on the laptop under `dev/varka_bench_repeat.sh`, and
`VarkaEvaluatorOverheadBenchmark-jdk25-split-vs-master.txt` holds every case's minimum and maximum
on both sides. Minimums, in nanoseconds a batch, are what the table below compares, as
`sql/varka/AGENTS.md` asks of a ratio under 1.3x; each case's move is read against the band of the
unchanged code (`-band.txt`, `-128bit-band.txt`), whose median spread over ten runs is 8.5% wide and
9.2% narrow, and up to 16% and 19% in a single case.

| rows | shape | wide master | wide split | 128-bit master | 128-bit split |
|---|---|---|---|---|---|
| 1 | int lane | 490 | 522 | 513 | 525 |
| 1 | long lane | 541 | 542 | 693 | 700 |
| 1 | filter | 133 | 138 | 134 | 134 |
| 1 | derived input | 691 | 678 | 697 | 680 |
| 16 | int lane | 533 | 538 | 539 | 539 |
| 16 | long lane | 568 | 582 | 794 | 792 |
| 16 | filter | 459 | 491 | 495 | 500 |
| 16 | derived input | 1198 | 1240 | 1211 | 1203 |
| 1024 | int lane | 791 | 812 | 790 | 814 |
| 1024 | long lane | 1285 | 1295 | 11759 | 11844 |
| 1024 | filter | 1148 | 1222 | 2357 | 2364 |
| 1024 | derived input | 29751 | 29686 | 27461 | 27883 |

**The result: no measurable difference.** In all 24 cases the split's range over its ten runs
overlaps the unchanged code's. The largest rise in a minimum is 7.0% (wide, the filter at 16 rows),
against a band spread of 12% for that case; at 128 bits it is 2.9%. Some minimums are lower and
most are slightly higher, which is not a pattern the band can tell from noise.

**The scoring.**

1. *No case slower by more than 10%, or by more than the run's own spread.* Held: no minimum is
   more than 7.0% higher.
2. *The 1-row cases no more than 50 ns a batch slower.* Held: the largest rise in a 1-row minimum
   is 33 ns (the wide int lane, 490 to 522), against a band of 80 ns for that case; the other three
   move by 5 ns, 1 ns and minus 12 ns.

**What an earlier draft of this section got wrong.** It was written from one run at each width and
claimed that most cases got faster, crediting the wide 1-row int lane's 620 to 534 ns to the removed
per-input allocations. The unchanged code's ten runs put that case between 490 and 570 ns: the
baseline run of 620 had been a high outlier, so the "improvement" was the baseline's noise, and at
128 bits the one-run comparison had in fact shown eight of twelve cases slower. The code review of
the pull request found both; the band is what settles them, and the per-input allocation the
split removes is real but too small to see here.

**What else the review changed.** The ledger holds `ColumnVector[]` rather than a Java list built
per batch; the derived-input arm is an enum `switch` with no `default`; the runner takes its
bounds as a `Bound` record array; the task end closes a snapshot of the open batches; the warm-up
gate does not repeat the base's `warmed` test; `filterMask` calls the runner directly and the
`fillSources` and `invokeFused` shims are gone; and `VarkaEvaluatorComponentsSuite` drives the
components' guard paths (the task end's order and guards, a close that re-enters the ledger, a
node with no counters, the class dump's memo), each checked to fail when its guard is removed.

**Built otherwise than planned.** Section 3.1 expected a base of about 300 lines: it is 549 (275
without comments), because `serveBatch`, `canRun`, `isArrowBacked` and the identity names stay in
it. Section 8 expected a commit per component; the components are one commit. Section 4 listed
`VarkaFilterEvaluator.scala` and `VarkaKernelPart.scala` as changed: `VarkaFilterEvaluator`
changed in the review (it calls the runner directly), `VarkaKernelPart` did not, and
`VarkaKernelEvaluatorSuite.scala` and `VarkaColdPath.scala` changed instead. The evaluators and the
base itself stay Scala until row 267, which now owns `VarkaEvaluatorBase` and `VarkaKernelPart`
beside the five files it listed. The log lines of fallbacks, declines, the warm-up and the dump now
come from the components' own loggers, under `org.apache.spark.sql.execution.varka`; the
cold-path benchmark's logging switch names both.

The first gate run failed one test at both widths, `VarkaKernelEvaluatorSuite`'s capped-allocator
test: it caps the scratch by overriding `taskAllocator`, and the scratch had asked the ledger. It
grows through `taskAllocator` again, as before the split.
