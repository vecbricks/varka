# VARKA-267: The evaluators to Java, beside 251's components

## 1. Where this came from

Row 267 of `m7/PLAN.md`, from the plan review (2.3) and `m10/SCOPE.md` item 74: after row 251 split
`VarkaEvaluatorBase` into six Java components, the evaluators themselves stayed Scala:
`VarkaEvaluatorBase` (559 lines, now a composition of those components), `VarkaKernelEvaluator`
(397), `VarkaFilterEvaluator` (389), `VarkaKernelPart` (60), `VarkaVectorProjection` (86),
`VarkaFusionReport` (117) and `VarkaExecMetrics` (94): 1,702 lines. The exec nodes and the columnar
rule stay thin Scala wrappers, since `SparkPlan` case classes are the interop surface the owner's
rule exempts. It also unblocks row 300 (the classifier and the data model, 217b), which can only
become Java records once their consumers, these evaluators, are Java.

## 2. The admission check, done

Read on 9 October 2026 on `master` (`363a1280790`):

* **The seams are narrow.** `VarkaKernelEvaluator` has 6 main-source consumers and 6 test files;
  `VarkaFilterEvaluator` 1 and 1; `VarkaEvaluatorBase` 3 and 2; `VarkaFusionReport` 5 and 3;
  `VarkaExecMetrics` 7 and 6 (11 construction sites in tests); `VarkaVectorProjection` 1 and 1;
  `VarkaKernelPart` 1 and none. Sixteen `new VarkaKernelEvaluator`/`new VarkaFilterEvaluator`
  sites in all.
* **What the Java inherits from the Scala is small.** The base's by-name `serveBatch[T]` (two
  closures and an `Either` per batch today) becomes two functional-interface parameters, which the
  Scala nodes pass as they pass closures now; Java code writes no lambda on the batch path, and
  allocates no `Either`. `Option`/`Seq` arguments (`classDumpDirectory`, `projectList`,
  `childOutput`) stay Scala types at the constructor and are read once in it, with `scala.jdk`
  converters, until row 300 gives their producers Java types.
* **A Java subclass of a Java base, and a Scala subclass of a Java base, both work during the
  migration**: the Scala `VarkaKernelEvaluator` can extend the Java base while the cut below is in
  flight, so each step is a PR that leaves master green.
* **A per-batch benchmark exists** (VARKA-251's `VarkaEvaluatorOverheadBenchmark`: the projection
  and filter evaluators driven over pre-built Arrow batches of 1, 16 and 1024 rows, four shapes,
  checking every batch took the kernel). It is the proof for the two steps that touch the batch
  path.
* **The throughput claim of 251 is the bar**: per batch the same as the unchanged evaluators over
  ten runs at each width, every case's range overlapping and no minimum more than 7% higher.

What the check would have rejected: a port of the base and the two evaluators in one PR (about
1,400 lines of Java in a PR that cannot be reviewed against its Scala), and carrying `Either` and
by-name closures into Java to keep the Scala shape.

## 3. The design

### 3.1 The cut: three pull requests, bottom up

1. **267a, the leaves**: `VarkaExecMetrics` as a record (the metrics a node registers, null where
   absent, with `nodeMetrics`, `projectionMetrics` and `fromNode` as static factories and a
   builder for the suites that set one or two), `VarkaSelection` as a record, `VarkaFusionReport`
   as a class of static renderers, and `VarkaVectorProjection`. None is on the per-batch kernel
   path; the vector projection is the per-row fallback and is measured on its own benchmark's
   shape.
2. **267b, the base and the filter evaluator**: `VarkaEvaluatorBase` (abstract), `VarkaKernelPart`
   and `VarkaFilterEvaluator`. `serveBatch` takes two `VarkaBatchPath<T>` interfaces. The Scala
   `VarkaKernelEvaluator` extends the Java base for this step.
3. **267c, the projection evaluator**: `VarkaKernelEvaluator`, `VarkaOwnedArrowColumnVector` and the
   weak warm-up-batch table, the allocation schedule and the logged-declines set that sit in its
   Scala companion.

Each step deletes the Scala it replaces, adapts its Scala consumers (the nodes, the rule, the
suites) and is proved on its own.

### 3.2 The Java rules applied

Records for the data carriers (the metrics, the selection); a `sealed` interface where the
fallback causes are a closed set, consumed by an exhaustive `switch`; pattern-matching `switch` over
the Arrow vector classes in `isArrowBacked` (no `default` over a sealed type; Arrow's classes are
not sealed, so the `default` there is the stated exception); no lambda, stream or boxing in the
batch path; explicit imports; 100 columns.

### 3.3 What is deliberately unchanged

The exec nodes, the columnar rule, `VarkaInputRows` and `VarkaRowToColumn` (Scala helpers of the
nodes), the six components of 251, and the compiler's data model, which row 300 ports; the
evaluators read it through its Scala accessors until then.

### 3.4 Registered op counts

None.

## 4. Files

Per step, in `sql/core/src/main/java/org/apache/spark/sql/execution/` (the evaluators beside
`varka/`, which holds the components), with the Scala files deleted and the nodes and suites
adapted. The list is written with each step's section 9.

## 5. Tests, and what each is for

The suites that exist are the proof, because the port is meant to change nothing:
`VarkaKernelEvaluatorSuite`, `VarkaFilterExecSuite`, `VarkaProjectExecSuite`,
`VarkaColumnarToRowExecSuite`, `VarkaDifferentialSuite`, `VarkaWarmupEndToEndSuite`, the
sanitizer's end-to-end suite and the rest of the 36 sql Varka suites, plus `emitted_bytes.json`
and `coverage.json` byte-identical (no emitter or compiler change), and the per-batch benchmark
for 267b and 267c.

## 6. The measurement

`VarkaEvaluatorOverheadBenchmark` before and after each of 267b and 267c, interleaved on the idle
laptop (`dev/varka_bench_pair.sh` once #683 has merged), both widths.

### 6.1 Predictions, registered before the runs

1. 267a changes no committed number: the leaves are off the kernel path, and the row-path
   benchmark's cases move within their noise.
2. 267b and 267c: every case of the overhead benchmark within 5% of the Scala either way, none
   more than 7% slower by minimums, and the per-batch allocation at or below the Scala's (no
   `Either`, no `Option`, no tuple per batch).
3. The Java is shorter than the Scala by no more than a fifth and longer by no more than a half;
   the Scala was dense with `Option` and `Seq` plumbing that records and `switch` replace.

## 7. Risks

1. **Per-batch allocation.** A boxed `Integer`, a `Supplier` created in Java or an iterator over a
   Scala `Seq` on the batch path undoes the hot path's discipline; the benchmark's allocation read
   and a review of every call in the batch path are the checks.
2. **Scala subclass of a Java base** (267b): protected access across the language boundary and
   the abstract methods' `Option` return types; the 267b compile is the check.
3. **The nodes' by-name closures** become `VarkaBatchPath` arguments; the four nodes' call sites
   and the suites' direct uses change together in one commit.
4. **A suite that reaches into a private member** through Scala's `private[execution]` (several
   do, for `kernelIdentity`, `emittedClassBytes`, `partialPlan`): those become package-private
   Java members, which Scala in the same package reads.

## 8. Sequencing

267a, then 267b, then 267c; this plan is the first commit of 267a and the rows 267b and 267c are
added to `m7/PLAN.md` as they start.

## 9. Outcome

### 9.1 Step 267a, the leaves

Done on 9 October 2026. `VarkaExecMetrics` (a `Serializable` record with `NONE`, a builder, and
the static `nodeMetrics`, `projectionMetrics`, `fromNode` and a null-safe `inc`), `VarkaSelection`
(a record), `VarkaFusionReport` (static renderers over the Scala data model) and
`VarkaVectorProjection` are Java; the four Scala files they replace are deleted. The nodes, the
evaluators' call sites and nine suites are adapted: 13 `foreach(_ += 1)` sites became `inc`, the
node metric maps convert with `asScala.toMap`, and the 11 construction sites in tests became
`NONE` or the builder.

**The proof held.** The 38 sql Varka suites pass (440 tests, 12 cancelled as before), scalastyle and
checkstyle are clean; no emitter or compiler file changed, so `emitted_bytes.json` and
`coverage.json` are untouched.

**One thing the port broke and the suites caught.** The first run failed 302 tests with `Task not
serializable: ... VarkaExecMetrics`: a Scala case class is `Serializable` and the exec nodes'
evaluator factories carry the bundle to the executors, a property a Java record does not have until
it says so. The record implements `Serializable` now, and says why.

**Predictions scored.** 1 (no committed number moves): held by construction, no benchmark file
changed, and not otherwise measured: the leaves are off the kernel path, and the vector
projection's loop is the same call sequence as the Scala's (an input-rows copy, the mutable
projection, a row id), which the per-batch benchmark of 267b and 267c does not exercise. The row
path's cost is therefore *not measured here* and rests on that identity; `VarkaVectorProjectionSuite`
pins its output.

### 9.2 Step 267b, the base, the filter evaluator and the kernel part

Done on 9 October 2026. `VarkaEvaluatorBase` (abstract), `VarkaFilterEvaluator` and
`VarkaKernelPart` are Java, with `VarkaBatchPath<T>` for `serveBatch`'s two paths; the three
Scala files are deleted, the warm-up batch table, the allocation schedule and the logged declines
moved into the base, and the Scala projection evaluator extends the Java base (owning its
`java.util.ArrayList` of owned vectors now). The three nodes' four call sites pass closures as
`VarkaBatchPath`, and two suites pass positional constructor arguments, since Scala cannot name a
Java method's parameters. The 38 sql Varka suites pass (440 tests, 12 cancelled as before);
scalastyle and checkstyle are clean.

**The measurement.** `VarkaEvaluatorOverheadBenchmark`, five interleaved rounds of the Scala
evaluators (master's) and the Java ones, each side's tree rebuilt, through `dev/varka_bench_pair.sh`
(#683's tool, run from its branch), wide width, on the idle laptop. By minimums over the five
runs the Java is faster in all twelve cases:

| rows a batch | case | Scala min ns | Java min ns | change |
|---|---|---:|---:|---:|
| 1 | date_add | 534.3 | 503.3 | +6.2% |
| 1 | least (long) | 542.6 | 517.0 | +5.0% |
| 1 | filter | 139.9 | 116.5 | +20.1% |
| 1 | trunc | 760.0 | 658.2 | +15.5% |
| 16 | date_add | 559.7 | 513.8 | +8.9% |
| 16 | least (long) | 584.8 | 558.2 | +4.8% |
| 16 | filter | 504.6 | 459.8 | +9.7% |
| 16 | trunc | 1300.0 | 1161.3 | +11.9% |
| 1024 | date_add | 819.1 | 776.2 | +5.5% |
| 1024 | least (long) | 1325.7 | 1292.3 | +2.6% |
| 1024 | filter | 1215.7 | 1184.3 | +2.7% |
| 1024 | trunc | 30972.4 | 30759.0 | +0.7% |

The pair tool's own verdict by medians is one case slower by 10% (1 row, `date_add`, -11.7%) and
three faster; that case has a spread over its five runs of 27% on the Scala side and 35% on the
Java side, with the two sides' values trending in opposite directions across the rounds (the
Scala 680, 545, 549, 534, 539 ns, the Java 503, 556, 679, 617, 643), which is a machine drifting
between rounds and not a difference between the code. The repository's rule for ratios near the
noise is minimums, which say the Java is faster everywhere, by most at one row (a batch's fixed
cost: the Scala asked a `Map` for each input's derived note and built an `Either` and a
`Some` per batch).

**Predictions scored (6.1), as corrected in 9.3.** 2: *the time half held and is better than
predicted; the allocation half was not measured.* No case is slower by minimums (the bar was 7%),
and eight are faster by 5% or more, up to 20%, so "within 5% either way" was refuted on the fast side;
"per-batch allocation at or below the Scala's" has no reading (see 9.3). 3: the Java files total
1,366 lines (base 807, filter evaluator 447, part 80, path 32), against the 1,001 Scala lines they
replace (559, 382, 60): 1.36 times, inside the "no more than half again" band, so prediction 3
held.

### 9.3 Review of #689 (`/code-review high`), 9 October 2026

Fixed in the PR: `fusedRunner()` and the filter evaluator's predicate marked themselves resolved
before building, so a throw that is not answered by the fallback (an `InterruptedException`, an
`Error` from the shape cache) left the evaluator with a permanent null that read as "emission
failed" and fell back silently on every later batch, where the Scala `lazy val` retried; they mark
after a success or a failure the fallback answers, and `plan()` after the compile returns. Three
accessors nothing called (`compiledPlan`, `evaluatorMetrics`, `fallbackAccounting`) are deleted;
the integer and long literal tables are read through `Number`, so a boxed type that is not
exactly `Integer` or `Long` cannot throw `ClassCastException`; a doc comment named
`VarkaKernelEvaluator.allocationSchedule`, which moved to the base. The line counts above replace
the ones in 9.2, which did not match the files.

Not fixed, said plainly. **The allocation claim is a reading, not a measurement:** that the Java
allocates less than the Scala per batch (no `Either`, no `Some` from `derivedAt`, no `Map` lookup)
comes from reading the code; the overhead benchmark reports time, and no reading of allocation was
taken on either side, so prediction 2's allocation half stands unscored. **The two closures per batch
move to the call site:** each `serveBatch` call builds two closures in the Scala nodes, as the
by-name thunks it replaces were built, so the rule against allocation on the batch path is met by
the Java and not by the path as a whole; a per-node serve method removes them and is a later step.
**1-row `date_add` is not cleanly drift:** its five Scala runs (680, 545, 549, 534, 539 ns) and five
Java runs (503, 556, 679, 617, 643) move in opposite directions across rounds, and the order
alternates, which a real regression in the later rounds would also produce. The minimum says faster
and the median says slower; a second run settles it, recorded in 9.4.

### 9.4 The second pair run, 9 October 2026

Five more interleaved rounds on the same machine, committed whole in
`sql/core/benchmarks/VarkaEvaluatorOverheadBenchmark-jdk25-267b-pair2-results.txt`. The pair
tool's verdict by medians: twelve cases, none slower, two faster by 10% or more (the 1-row filter
by 15.6% and the 1-row `least` by 16.1%).

**1-row `date_add` is settled: the Java is faster.** Its five runs, in nanoseconds a row, were
544, 638, 567, 562, 538 for the Scala and 515, 611, 514, 471, 479 for the Java: median 562 against
514 (the Java 9.5% faster by rate) and minimum 538 against 471 (12% faster), with the Java ahead
in every round whichever side ran first. The first pair's median reading of 11.7% slower was the
drift it was said to be: the Scala's values there ranged 534 to 680 across rounds, the Java's 503
to 679, and this run's spread is 19% on the Scala side. By medians in the second pair eleven of
the twelve cases are faster by rate and one (1,024-row `trunc`) is 0.5% slower, inside its spread.

### 9.5 Step 267c, the projection evaluator

Done on 9 October 2026. `VarkaKernelEvaluator` and `VarkaOwnedArrowColumnVector` are Java; the Scala
file is deleted. The two nodes pass `operatorName` positionally (Scala cannot name a Java
parameter), the evaluator suite's seven construction sites were adapted, and a four-argument
constructor keeps the defaults a suite or diagnostic builds with. What changed in shape: the output
batch's layout (fused, kernel, forwarded or residual, and the index in each) is read once from the
classification into three arrays that `project` walks per batch, where the Scala built a list of
columns through `map` and `toArray` on every batch; the further kernels are an array of
`VarkaKernelPart`, asked in a loop with the decline or failure of one rethrown naming it, where the
Scala used a by-name `blamed`; and the functional object that hands the kernel its output vectors is
made once. The Scala data model is still read through its accessors, with the one instanceof ladder
(ending in a throw) that row 300 turns into a `switch`.

**The proof held.** The 37 Varka suites of `sql` pass at the default width (526 tests, none
failed, six cancelled as opt-in), `scalastyle` on main and test is clean, and no emitter or
compiler file changed, so `emitted_bytes.json` and `coverage.json` are untouched. The 128-bit
companion run is left to CI.

**The measurement.** Five interleaved rounds of `VarkaEvaluatorOverheadBenchmark`, the Scala
evaluator on `master` against the Java one, committed whole in
`sql/core/benchmarks/VarkaEvaluatorOverheadBenchmark-jdk25-267c-pair-results.txt`. By the pair
tool's medians, twelve cases: none slower by the tool's 10% (the worst is the 1-row filter, 3% slower),
six faster by 10% or more. The projection cases moved most -
`date_add` at 1, 16 and 1,024 rows by 20%, 22% and 14%, `least` at 1 and 16 rows by 22% and 18%, `trunc`
at 1 row by 13% - and the filter cases, which 267b already moved, stayed within 3% of the Scala.

**Predictions scored (6.1).** 2: *the time half held and was better than predicted, as in 267b*:
no case is slower beyond 3%, and "within 5% either way" was refuted on the fast side. The allocation half is
still unmeasured, so it stands unscored. 3: **refuted**: the Java files are 605 lines against the
Scala's 358 (1.7 times, where the band was no more than half again); the Java carries the same
documentation and a layout table, an explicit constructor pair, and the plumbing Scala hid in
`lazy val` and pattern matches. Row 267 is done.

**Review of #694 (`/code-review high`).** Fixed in the PR, none of it a wrong answer: the cold-path
benchmark silenced a logger the emission warning no longer uses (it moved to the base in 267b);
`project` allocated an owned list, a `List` view, a sized-zero `toArray` and the kernels' column
array per batch, and now reuses the list and the array, so a batch allocates its output columns, its
batch and the owned array its tracking keeps; the classification was walked three times with three
copies of the spec test, and `layout` now reads it once with a pattern `switch` (its `default` the one
row 300 removes) and hands the further kernels' entries and the residual entries to the two other
readers; the four settings the subclass copied from the base to build a further kernel are now the
base's own `kernelPart` factory, so the first kernel and the further ones cannot drift apart; a further
kernel names itself in a decline or failure (`VarkaKernelPart` overrides `kernelReady` and
`runKernel`) instead of every caller wrapping it; and the residual expressions are a local, so the
unchecked cast helper is gone, which leaves the Java at 586 lines (1.6 times the Scala). The 37 suites pass
again (526 tests). The benchmark pair above ran
before these changes; the per-batch path only lost allocations, so it was not rerun.

