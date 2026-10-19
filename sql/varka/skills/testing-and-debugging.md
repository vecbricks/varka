# Testing and debugging

How a wrong answer here is caught, and the fixtures and oracles that decide what a test can catch.

One of Varka's lesson files; the index over all of them is
[`SKILLS.md`](../../../SKILLS.md) at the repository root, which is generated from
these files by `dev/varka_toc.py`.

## Buffer-Reuse Aliasing (UnsafeProjection)

- `UnsafeProjection` (and `GenerateUnsafeProjection`) reuses a single output
  `UnsafeRow` buffer across calls. Materializing results into an `Array` without
  copying yields an array whose elements all alias the last row's buffer: every
  output shows the last value.
- The copy belongs to whoever *materializes*, not to the operator. Spark operators
  stream reused rows deliberately: `ColumnarToRowEvaluatorFactory` is
  `input.rowIterator().asScala.map(toUnsafe)`, with no copy. `VarkaColumnarToRowExec`
  copied per row on both its kernel and fallback paths until finding 9 removed it; that
  cost an `UnsafeRow` allocation plus a memcpy per row on the hot path and bought a
  guarantee the standard path never gave. (Earlier revisions of this file prescribed the
  copy, from when the evaluator materialized with `process(...).toArray`; it streams now.)
- `QueryExecution.toRdd`'s scaladoc states the contract and names `collect()` as "one of
  known bad usage" - `RDD.collect` is `iter.toArray` per partition, so it aliases. Use
  `Dataset.collect` (which serializes each row as it iterates), or, in a test that wants
  `InternalRow`s, `toRdd.map(_.copy()).collect()`.
- Not a Varka rule: collecting a plain row-engine query that way returns 2 distinct row
  objects for 5 rows, with wrong values. `SparkContext.hadoopRDD`'s scaladoc documents
  the same hazard for reused Hadoop `Writable`s.
- Corollary for tests: a suite that materializes rows can pass for the wrong reason while
  an operator copies. `VarkaDifferentialSuite` did, and only failed once the operator
  stopped - the copy had been masking an unsound `toRdd.collect()` in the test itself.

## Alias Unwrap Is Needed at Two Layers

- A projection list is `Alias(expr, name)` at the top level, and after
  `BindReferences` the bound expression is `Alias(BoundReference(...), ...)`.
- Any code matching projection expressions against concrete types must unwrap `Alias`
  twice: once on the unbound list (`eligibleOps`) and once on the bound list
  (`buildOutputPlan`). Missing either makes eligibility silently fail (kernel never
  runs) or the plan match return `None`.
- Symptom: with both missing, fallback covers it; the kernel path never executes. The
  rule test and the `numVarkaBatches` metric reveal it once rows are correct.

## Masked Bugs

- A fallback path that returns *wrong* results can hide an entirely untouched kernel
  path. Here the "kernel results" seen early on were actually the fallback (with the
  aliasing bug). The real kernel only ran once the Alias unwraps landed, which is
  also when the stub trap surfaced. Two independent bugs masked each other.

## Debugging Method: Progressive Isolation

1. Verify the mechanism in isolation (e.g. a manual `MemorySegment.ofAddress(...).set`
   write that Arrow can read).
2. Verify the middle layer directly (call the kernel straight on Arrow buffers).
3. Instrument the deepest layer (prints inside the kernel). Prints not appearing =
   wrong class on the classpath.
Each step either pins the fault or narrows it.

## Columnar Transition Wiring (plan level)

- `ApplyColumnarRulesAndInsertTransitions` runs `preColumnarTransitions`, then
  `insertTransitions`, then `postColumnarTransitions`. A `postColumnarTransitions`
  rule sees the transitions already inserted.
- `ensureOutputsRowBased` gives a dual-mode plan (`supportsRowBased` and
  `supportsColumnar`, e.g. `InMemoryTableScanExec`) row output when its parent
  consumes rows, so above a cached scan there is often *no* `ColumnarToRowExec` to
  pattern-match on. Fuse on `child.supportsColumnar` (and switch the dual-mode
  child to columnar output), not on the presence of a transition.
- `ColumnarToRowExec` is row-only: it has no `doExecuteColumnar`, so calling
  `executeColumnar()` on it throws. A fusion node that consumes a columnar child
  must absorb the transition (`case ColumnarToRowExec(inner) => inner`) instead of
  wrapping it.
- The `ColumnarToRowTransition` tag is read by some machinery as "semantics-free
  row conversion" - `CachedBatchSerializer.convertToColumnarPlanIfPossible` strips
  a topmost transition and executes its *child* to get columnar cache input. A
  fused node wearing the tag (every Varka `*ColumnarToRowExec`) carries real work
  inside it, so every tag consumer that strips must instead convert the fused node
  to its columnar sibling (identical kernels, columnar out) - the Arrow serializer
  override does. Found in VARKA-21 as a wrong-cached-view bug latent since VARKA-6:
  every direct query stays right, and only a *cached* view materializes the
  dropped work. When adding a fused transition node, grep the tag's consumers.

## Metrics as the "did it really run" proof

- A fused plan plus correct results does not prove the kernels ran: the per-batch
  fallback also returns correct results. Prove execution with a metric
  (`numVarkaBatches`) bumped only on the kernel path.
- Read metrics *after* execution: run `checkAnswer`/`collect` first, then read.
  Reading before execution returns 0 even though every guard passed.
- The executed-plan root is often a `WholeStageCodegenExec` whose `metrics` map
  only has `pipelineTime`. Read the node's own metric via
  `plan.collectFirst { case v: VarkaColumnarToRowExec => v }`, not `plan.metrics`.

## A metric that can say "not reported": two sums in one value, rendered by a custom type

- A plain sum `SQLMetric` reads 0 when nothing added to it, so it cannot tell "none" from
  "unknown". The one way Spark gives a metric cell text is the data source V2 custom metric: an
  `SQLMetric` whose type is `CustomMetrics.buildV2CustomMetricTypeName(c)` makes the SQL listener
  load `c`'s class and show `aggregateTaskMetrics(long[])` over the tasks' values
  (`VarkaReasonMetric`, VARKA-313). The class needs a no-argument constructor; a history server
  without it shows "N/A".
- Keep "unknown" as a count, never as a sentinel. VARKA-313's first version left a metric at -1
  for "not reported" and set it at the task's end; review found three places Spark loses it:
  `DAGScheduler.updateAccumulators` drops an update whose `isZero` holds, so the event log and a
  history server never see a -1; a coalesce runs several evaluators in one task against one
  metric copy, and the first `add` turns -1 into 0; and the driver's merge skips it. A metric
  that never goes below 0 survives all three. `VarkaReasonMetric` adds exact declines as 1 and
  unattributed ones as `1L << 32`, both sums, and decodes them when it renders.
- The driver's `metric.value` is the encoded sum; decode it (`VarkaReasonMetric.exact` and
  `unattributed`, or `VarkaFallbackReasons` in the suites) rather than comparing it with a count.

## Independent Reference Evaluators as Test Oracles

- For an algebraic surface (three-valued logic, null-skipping picks, blend
  semantics), implement the semantics *twice*: the generated code, and a tiny
  interpreter over the same IR inside the test suite (`Option[Int]` values,
  `Option[Boolean]` Kleene conditions). Run matrices against it row for row. Wrong-
  in-the-same-way bugs are unlikely across two representations that share nothing.
- A fold's *association* is not its *effect order*: a monadic `foldRight` over
  CASE branches evaluated the ELSE first and registered input ordinals right-to-left.
  Where side effects assign identities (ordinals, slots), compile in source order
  explicitly, then fold the already-compiled pieces.
- The reference is held to Spark in turn (VARKA-276): `VarkaKernelCheck` asks a
  `VarkaSparkOracle` for Catalyst's `eval` of each output's own expression on every row before it
  compares the kernel, so a disagreement says whose it is - the reference's when it and Spark
  differ, the kernel's when the kernel differs from both. Two things it needs: the analysed
  expression's `RuntimeReplaceable` nodes swapped for their replacements (`extract` has no
  `eval`), and inputs inside each type's domain. The one disagreement it found was a `TIME(6)`
  drawn to the nanosecond, which Spark truncates on its first arithmetic: a harness drawing values
  a column cannot hold tests nothing about the engine.

## A second lane gets a second fuzz corpus, and its reach set comes from the constructors

From VARKA-119 (19 September 2026), which gave `VarkaIrFuzzSuite` the long lane.

- **Do not draw the new lane's shapes into the existing sequence.** `VarkaIrGrammar.fuzzSeed`'s
  shapes are also `VarkaEmittedBytesSuite`'s committed corpus; one more arm in the int draw
  reshuffles every pinned block, and the oracle can no longer say whether the int32 emitter
  changed. A second seed (`longFuzzSeed`), a second draw (`drawLongShape`) and a second
  generator (`LongShapes`) leave the first corpus byte-identical - the regeneration that added
  the long lane changed no committed hash - and the oracle pins the new sequence beside the old
  one (`fuzz_long`).
- **Derive the "reaches every node type" set from the IR, not from a list.** At the long lane the
  target is the lane-generic subset, and a hand-written list of it is a table that drifts. The
  suite asks each record type's canonical constructor whether it accepts long leaves
  (`admitsLongLanes`): the calendar nodes refuse through `requireInt`, the rest construct. A
  lane-generic node added to the IR is demanded of the long generator from the day it lands,
  with nobody editing the test - and the probe asserts two facts it must get right (`Year`
  refuses, `ConstDivide` admits) so a change to the constructors cannot silently empty the set.
- **A grammar's bounds are a contract the moment a node takes them as a range.** The int
  grammar's `TruncDate` bound was the child's, with the note that trunc "moves a date down by
  at most a year, so the child's bound holds" - true for the calendar placement, which checks
  with slack, and false for VARKA-102's range-guard arm, which wraps a subtree in a guard of
  exactly its bound. A year-start 365 days below the child's bound is a live lane the guard
  condemns, correctly, and the suite asserts a zero status. The default 300 iterations never
  drew it; iteration 847 of the committed seed did, found by this task's ten-thousand-iteration
  run. Every arm that shifts a value now adds the shift to its bound, and the lesson is the
  general one: a bound written for a check with slack is not a bound.
- **Draw a signed range with `Math.floorMod`.** `rnd.nextLong() % (2 * bound + 1) - bound` lands
  in `[-3 * bound, bound]` for a negative draw, and the first long shape of the corpus declined
  its batch on a value three bounds below zero. The int harness clamps; the long one floors. A
  decline in a null-free batch is the harness's problem before it is the emitter's.

## A fixture that fills undefined memory decides what its whole matrix can catch

- Arrow leaves the data under a null slot undefined, and the emitted loop loads every
  column unmasked, so what a test harness writes there is what stands between a
  lowering's garbage and its answer. For most of milestone 4 the suites wrote the
  *drawn value* into a null lane and `VarkaIrFuzzSuite` still did in September 2026 -
  values bounded by `columnBound` and `MONTH_ARITH_MAX_MONTHS` by construction, so a
  null lane could never reach a range guard's condemning comparison. Every guard since
  VARKA-42 ANDs its mask with the row's validity word; nothing in either suite could
  have failed if one of them stopped. Poisoning null lanes with `Int.MinValue` and
  `Int.MaxValue` is what makes that AND load-bearing, and it costs nothing: the whole
  matrix passed unchanged at both widths the day it went in, which is the evidence
  that the ANDs are all there, not merely that the tests are green.
- Key the alternation on the *null ordinal*, never on the row index. The null patterns
  are themselves index predicates - `i % 2 == 1` is one of them - so an `i & 1` poison
  silently writes one extreme in every null lane of that pattern, and a quarter of the
  matrix only ever probes one side of every bound. Bounds here are asymmetric
  (`MAKE_DATE_MIN_YEAR` against `MAKE_DATE_MAX_YEAR`; VARKA-69 widens `dayRange`
  upward only), so half a guard can go missing under a green suite.
- And poison only the slots the caller did not choose. A guard test that pins a
  boundary value at a lane it also marks null is testing that exact value; substituting
  an extreme kept it passing while quietly turning one case into a duplicate of the one
  beside it. `makeInputData`'s `poisonNulls = false` is for those, and the reason is in
  its javadoc so the next person does not undo it.

## Testing Under AQE

- Every Varka suite session disables AQE for plan determinism, which silently leaves
  the default-config path (AQE on) unpinned. It worked - but only an experiment
  proved it.
- Under AQE the fused node sits inside a query stage, and a query stage is a *leaf*:
  `SparkPlan.collect`/`collectFirst` never descend into it, so a naive assertion
  reports "not fused" while the node is right there in `treeString`. Traverse with
  `AdaptiveSparkPlanHelper` in AQE tests.

## A closed `ArrowBuf` still answers `capacity()` and `memoryAddress()`

Found reviewing VARKA-59's per-task scratch buffers, and worth knowing before writing any
grow-and-reuse helper over Arrow memory.

`ArrowBuf.close()` is one line - `referenceManager.release()`. It does not touch the buffer's
own fields, and both `capacity()` and `memoryAddress()` are plain field reads (`getfield
capacity:J` and `getfield addr:J` in arrow-memory-core 19.0.0; check with `javap -c` rather
than assuming, the class is small). So a closed buffer reports the size it used to have and the
address it used to own, and a `capacity() < needed` test - the natural way to decide whether to
grow - cannot tell a live buffer from a freed one. There is no cheap liveness predicate to
substitute: the reference count lives in the ledger, not the buffer.

The consequence for a helper that replaces a buffer: **allocate the new one before closing the
old one**, never the reverse. `BufferAllocator.buffer` throws Arrow's `OutOfMemoryException`,
which is a plain `RuntimeException` - not `java.lang.OutOfMemoryError` - so `NonFatal` matches
it, `serveBatch` catches it as a per-batch failure and *the task keeps running*. Free first and
the throw leaves the caller's field pointing at a freed buffer, because the assignment that
would have replaced it never happens: the right-hand side is evaluated first. The next smaller
batch then reads the stale capacity, decides no grow is needed, and writes through a released
address; the task's cleanup closes it again and the ledger's reference count goes negative.
Allocating first costs both buffers for the width of one assignment and cannot strand a freed
one. The same ordering argument applies to any resource whose accessors survive its release.

Two things this cost that are worth copying rather than rediscovering. The regression test
cannot assert on the corruption - freed memory usually still holds its old contents, so reading
it back returns the right answer and proves nothing; assert on the allocator's own accounting
instead (`getAllocatedMemory` unchanged across the failed grow), which is exactly the invariant
and is a public API. And a release path that catches and logs, which is the right thing for a
task-completion listener, will swallow the double close that would otherwise have made the bug
loud - so hardening the cleanup and fixing the ordering have to be judged separately, or the
hardening hides the evidence for the fix.

Two more from the sweep that followed it.

**One `try` around a cleanup sequence satisfies the letter of "do not skip the allocator close"
and not its point.** The listener frees several things in turn - open batches, the derived
scratch, a subclass hook - and wrapping the lot in a single guard means a throwing batch close
still costs the two stages after it. Guard each stage separately, so every one of them runs;
the collection closes guard each element too, for the same reason one throwing element must not
strand its neighbours. On a *failure* path there is a second reason: a bare
`owned.foreach(_.close())` inside a `catch` that rethrows will replace the exception being
reported with a cleanup exception, so the error that actually matters never surfaces.

**Two idioms for one hazard is how the next person gets it wrong.** This file had two
grow-and-replace helpers. One nulled its field before closing - safe, because a throwing
allocation then leaves no dangling reference, though it destroys a usable buffer for nothing -
and the other closed first and was the bug. The second was written claiming to follow the
first's "discipline", and the claim was even in a comment. Neither the comment nor the reviewer
noticed the orders differed. Both are now acquire-then-release, which is strictly better than
either and, more to the point, is one rule rather than two.

## Arrow promises no padding: an IPC read slices a buffer to its exact length

Read for milestone 7's sanitizer (row 263) in the Arrow Java 19.0.0 sources, and worth knowing
before any code writes a whole word into a buffer it did not allocate.

Varka writes an output's validity a 64-bit word at a time, so its segment must own the whole last
word. That holds for the buffers Varka allocates: `allocateNew` sizes a validity buffer with
`BaseValueVector.roundUp8ForValidityBuffer`, `((valueCount + 63) >> 6) << 3` bytes, and the
allocator rounds every request up to a power of two (`BaseAllocator.buffer` through
`DefaultRoundingPolicy`). It does not hold for buffers that arrive from elsewhere. The format only
recommends padding (`docs/source/format/Columnar.rst` in the Arrow tree); the Java IPC reader
slices each buffer to its exact length (`MessageSerializer`, `body.slice(offset, length)`), and a
C Data Interface import sizes a bitmap to `offset + length` bits. A word written into such a
buffer runs up to seven bytes past it, silently. So a whole-word write needs the buffer's
`capacity()`, not its nominal size, and row 263's sanitizer checks every segment against the
capacity. A read cannot run past an input silently: an input's segment is sized to its nominal
bytes, so its own bounds check throws first.

## A checklist for the next node type or mode, from what three reviews found in this one

VARKA-63 (int32 arithmetic) shipped, was reviewed twice more after it shipped,
and each review found real bugs in the fixes the previous one produced. Twenty
or so findings sort into six categories, and each has a concrete habit that
would have caught its instance before a review had to. The unifying pattern:
every wrong-answer bug lived in an analysis whose own boundary was never
tested directly - only observed transitively, through a downstream matrix that
trusted the analysis to have fed it a true bound.

**1. Any bound, range or "how large can this get" computation uses checked
arithmetic and gets its own property test.** `cannotOverflow` compared against
`abs(Int.MinValue)` instead of `Int.MaxValue`, off by one; `intBound`'s
`datediff` arm assumed the date contract unconditionally, wrong the moment an
operand's own lane could wrap; nested bounds were combined with plain `+`/`*`,
so a value past `2^63` came back small and positive and "proved" anything
safe. All three were wrong answers, not declines, and none was caught by the
emitter's boundary-value matrix, because that matrix tests values, not the
bound-computation logic that decided whether a check was needed at all. Use
`Math.addExact`/`multiplyExact` by construction wherever bounds combine -
overflow computing a bound means "no bound" (`None`), never a wrapped number -
and write the property test directly: over random IR, the interval a node
reports must contain the value the reference evaluator computes, for every
lane pattern. That test does not exist for today's `intBound`; it is scoped
for the lattice that replaces it (`m5/PLAN.md` 2.15, VARKA-84), but it
should exist for any hand-written bound function before that lands.

**2. Never state one admission rule in two places.** `compileOffset` and
`requireDayOffsetShape` each independently enumerated which node kinds a day
offset may be, in two languages, and they drifted: `date_add(d, weekday(d2) +
1)` was admitted by one and refused by the other, and the refusal fired only
at emit time, as a silent per-batch fallback under an EXPLAIN that still
claimed fusion. If a rule must be checked on both sides of the compiler/emitter
boundary, put it in one rule both call - here `VarkaOperandPosition` (VARKA-86),
an exhaustive switch over the positions and the node kinds, which replaced the
shared predicate `isDayOffsetShape` - or write a test that enumerates the
positions and asserts the two accepted sets are the same set.

**3. Before extending a predicate with more than one reader, list the readers
and their exact question.** `guardedWord` was read by `planSlots` for "does
this node need a scratch local" and by `liveWords` for "must this node's word
stay alive," and VARKA-63 assumed a third guarded kind would answer both the
same way. It answered no and yes: checked arithmetic parks its own values in
`intArithTmp` and never touches the shared scratch slot, so every checked node
reserved a local nothing read. Do not add a case to a shared predicate by
inspection; check what each existing call site actually does with the answer.

**4. A changed invariant is corrected in prose everywhere it was stated, in
the same commit.** The most dangerous single finding across all three reviews
was not in code: `VARKA-63.md` still told the next editor "extend
`guardedWord`, do not add a condition beside it," which had become false and
whose failure mode had gone from a loud `emitGuardCollect` refusal to a silent
wrong date. Scaladocs and a sibling plan (`VARKA-70.md`) said the same
superseded thing. Whenever a predicate, invariant or bound rule changes, grep
the repo - docs and comments included, not just call sites - for its old
description before considering the change done. This is the same discipline
`dev/varka_quote_check.py` already enforces for numbers, just not yet for
prose that states a rule.

**5. A new mode is atomic with widening every automated net that should
exercise it.** The IR fuzzer built `IntArith`/`IntNeg` with `Overflow.WRAP`
only, so every emitter path VARKA-63 added - the FAIL-only accumulator
membership, the scratch-slot split, both arms of the mask disposal - sat
outside the project's differential oracle from the day it shipped to the day
a review noticed. A differential test that only `intercept`s an exception on
both engines is compatible with the fused path it exists to protect quietly
becoming residual; pin the plan shape (`assertFused`/`assertNotFused`)
alongside the exception, the way the sibling tests already did. And a change
that removes a dead local moves emitted bytecode exactly as a value change
does - regenerate the committed benchmark, don't reason that "it was unused so
it can't matter" (it moved one row 24.9% and left its sibling width
untouched).

**6. Call the authoritative predicate; do not reconstruct a look-alike.** The
`guardsBelow` fix for the `datediff` bound covered `last_day`, `trunc` and the
dynamic `trunc` and missed `add_months`, because the fix was reasoned from the
`Chrono` sealed interface rather than from `VarkaLoopEmitter.isChrono`, which
is `Chrono` *plus* `AddMonths` and is the actual predicate that decides
whether a producer gets VARKA-52's guard. Whenever new logic must agree with an
existing rule elsewhere in the codebase, grep for that rule's real definition
and call or copy it exactly; a hand-derived approximation of a sealed
interface's membership is not the same question as "what does the emitter
actually treat as a calendar consumer."

## Check that the place a prediction blames actually exists

VARKA-68's prediction register asked its two interval rows to land within 3% of
their int twins and added the clause that made it look rigorous: "a larger gap
is a finding about the Arrow write path, not the lane". The gap came in at 5.1%
at one width, and the argument that followed was about noise bands - how far
this benchmark's cases move between runs, whether a within-run pair gap is
bounded by a between-run diff, whether three more runs would settle it.

None of that was needed. There is no Arrow write path to have a finding about.
`VarkaKernelEvaluator.project` reads `getDataBuffer().memoryAddress()` off each
output vector and hands the address to the kernel, which writes four-byte lanes
into it; `IntVector`, `DateDayVector` and `IntervalYearVector` are all
`BaseFixedWidthVector`s of `TYPE_WIDTH` 4, so the buffers are the same size and
the same bytes land in them, and one shared `setValueCount` closes the batch
with no branch on type. The accessors differ, but a columnar sink runs none of
them. The only per-type step in the whole route is which class the constructor
makes, once per output per batch.

The lesson is about the shape of the prediction, not about Arrow. A prediction
of the form "if X differs, the cause is Y" is two claims, and the second one is
checkable at *write* time, for free, by reading Y. Doing that here would have
cost one grep and would have replaced a 3% measurement question - which VARKA-67
had already failed to answer and handed forward - with a code fact and a test
that pins it. Instead the clause was carried across two tasks as if it named a
real mechanism, and each task spent its measurement budget failing to resolve a
difference that could not exist.

Two habits follow:

- **When a plan names the place a difference would come from, open that place
  before the run, not after.** If it turns out to be empty, the prediction is
  not "unresolved pending a better instrument" - it is answered, and better than
  a measurement could answer it, because a benchmark can only ever fail to find
  a difference that is not there.
- **Prefer the invariant test to the timing whenever the claim is really about
  code.** "Admitting this type costs the kernel nothing" reads like a
  performance claim and is a structural one. As a test it is a few lines - the
  same arithmetic under two Spark types must produce byte-identical buffers from
  demonstrably different vector classes - and it fails the day the premise
  breaks, which is exactly when a benchmark's noise would hide it.

## The coverage suite compiles the analyzed form; the end-to-end suites run the optimized one

`VarkaCoverageSuite` decides whether a row counts as covered by resolving its SQL
against the columns and handing the *analyzed* expression to the compiler. A
query in `VarkaLongLaneSuite` or the coverage differential goes through the
optimizer first. The two can disagree, and when they do the symptom is precise:
the query fuses end to end and the coverage row declines.

VARKA-29 met it on `dt < INTERVAL '0' SECOND`. The analyzer types the literal
SECOND TO SECOND and casts it to the column's DAY TO SECOND; the optimizer folds
that cast into a literal of the column's type before any query runs, so the
compiler never sees it end to end - and always sees it in the coverage suite. The
year-month arms had met the same thing earlier (`CAST(ymy AS INTERVAL MONTH)`),
which is why the compiler carries relabel arms for casts that return their
operand unchanged: the MONTH end field for year-month intervals, and now the
SECOND end field for day-time ones and a widening precision for `TIME`.

Two habits follow. When a coverage row declines that the end-to-end suite fuses,
look for a type-coercion cast before looking for a missing lowering - the reason
string names it (`unsupported expression` over a `Cast`). And when a new type
arrives, ask what casts type coercion inserts around its literals and columns,
and whether each is the identity on the lane; an identity gets a relabel arm,
anything else gets a decline with a reason.

A third habit, from VARKA-158: a coverage row is also a differential query.
`VarkaCoverageDifferentialSuite` runs every row of the table over one generic
fixture whose columns are chosen to be extreme - `l` and `l2` span the bigint
range, `dt` runs to a hundred thousand days - and compares the two engines. A
row whose function has a domain narrower than its column's fixture values does
not fail as a mismatch; the row engine raises Spark's error and the test fails
on the exception, on CI, after the local coverage and end-to-end suites passed.
`time_from_seconds(l)` did exactly that (`5000000000` seconds is not a time of
day). Write such a row over a value that is in the domain by construction -
`time_from_millis(time_to_millis(t2))` - or over a column the fixture keeps
there (`i` stays in 1..12 for `make_date`, though an int column reaches a
long-lane function only through a widening cast the compiler does not lower
yet), and run the differential suite, not only the coverage suite, before
pushing a row: the scoped CI job runs `sql/testOnly *Varka*`, and that is the
local command that matches it.

## A fuzz campaign is sixteen JVMs, not one sbt, and it re-finds what is already on the books

From the 22 September 2026 campaign run while CI was busy (milestone 5 rows 166
and 167, the knob in #316).

- **Run the fuzzer as plain JVMs.** `VarkaIrFuzzSuite` needs nothing from sbt
  but a classpath: `build/sbt -batch "export catalyst/Test/fullClasspath"` once,
  then `java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED
  -Dspark.testing=1 -Dvarka.fuzz.iterations=N -Dvarka.fuzz.seed=S -cp "$CP"
  org.scalatest.tools.Runner -oW -s ...VarkaIrFuzzSuite` per process. One JVM
  does about two hundred iterations a second per lane; sixteen of them with
  distinct seeds on the 24-core laptop did eight million per lane in
  twenty-five minutes. Under sbt the same campaign is one core and a day. Give
  each JVM its own seed and its own log, and let a driver script wait on the
  process ids, which exit on every path.
- **Six arms, one finding, and it was row 87.** Twenty-nine million int-lane
  and twenty-nine million long-lane trees, with every emit option randomised
  per iteration and `useAVX` over unknown, 0, 2 and 3: the default tree; the
  long-column bound raised from 2^46 to 2^52 - 1 on VARKA-147's node, so the
  divisions state their bound and the guard-versus-claim check runs live; and
  both again under `-XX:MaxVectorSize=16` and `=32`. The long lane never
  failed, in 14.4 million trees at the raised bound across three widths - the
  region row 166 names as never visited. The only failures were two int-lane
  seeds hitting the 65535-byte epilogue cap that row 87 already records and
  the owner asked to keep for a future fix: `-Dvarka.fuzz.seed=20260922006
  -Dvarka.fuzz.only=35105` (66102 bytes) and `-Dvarka.fuzz.seed=20260922008
  -Dvarka.fuzz.only=94624` (73013 bytes), both `epilogueMasked`, both nested
  `make_date` trees at `groupBudget` 400.
- **Arms that vary only the long lane repeat the int lane.** The bound knob
  and the width flag change nothing about the int-lane sequence, so the same
  two seeds failed identically in every arm. Harmless, but half of each later
  arm was a rerun; a campaign that means to vary the long lane alone should
  give the int lane fresh seeds or skip it with `-Dvarka.fuzz.only`.
- **A negative campaign is evidence, not the deliverable.** Fourteen million
  random trees near the bound saying nothing is worth knowing before the post,
  and row 166 still asks for the deterministic test that drives both forms
  over `[2^51, 2^53)` and asserts the shape of each failure - a random draw
  cannot promise it visited the last bit below the bound, and a test can.

## `VarkaIrFuzzSuite` caps each test at twenty minutes, so a big `-Dvarka.fuzz.iterations` makes every JVM report failure

From the campaign of 22 September 2026: 24 JVMs, 113.6 CPU-hours, seeds
20260923001 to 024, the long-column bound raised to just under 2^52 on VARKA-147's tree, eight JVMs each at the default width, `MaxVectorSize=16` and `=32`.

**Set the iteration count to what fits the cap, or the pass/fail signal is
worthless.** Each of the suite's tests is wrapped in `failAfter(20 minutes)`.
Six million iterations is hours of work, so every test reported

    The code passed to failAfter did not complete within 20 minutes.

on all 24 JVMs - 47 of the campaign's 48 "failures". The work still happened:
`failAfter` records the timeout but does not stop the thread, so each JVM
fuzzed for about five hours and then reported failure anyway. Nothing was lost
except the signal, and the campaign had to be summarised by grepping the logs
for a message that names a seed and an iteration rather than by exit status. A
campaign meant to run for hours should either raise the cap or - simpler - run
many shorter JVMs, which also spreads the seeds wider.

**What it found, which is the same thing the last one found.** No correctness
mismatch anywhere: not one disagreement with `VarkaReferenceEvaluator`, on
either lane, at any of the three widths, including the long lane with its
column bound raised into the six bits the fuzzer used to stop short of. The
single real failure is row 87's epilogue cap again -
`-Dvarka.fuzz.seed=20260923021 -Dvarka.fuzz.only=88962` at
`MaxVectorSize=32`, a deeply nested `makeDate` tree whose `epilogueMasked`
renders 79645 bytes against the JVM's 65535 limit. Two campaigns a fortnight
apart have now found that shape family and nothing else, which is worth knowing
before a public post and is not a reason to run a third.

## A forced masked batch of length 1 is all-null, and the kernel is right to say so

`VarkaEmitterTestBase.checkMatrix(forceMasked = true)` reaches the masked body
by reporting one null over a full-set bitmap, since the dispatcher tests only
`nullCount != 0`. That is a lie the kernel does not check, and at length 1 it
is a different lie than at length 16: one null is then the whole batch, the
prologue marks the input dead because `nullCount == length`, and every output
computed from it is null. The reference evaluator sees no nulls and disagrees,
and the failure reads exactly like a masked-epilogue bug on the shortest batch -
which is what VARKA-87's step 3a spent a run believing, on a shape the legacy
emission answered the same way.

So a forced-masked matrix starts at a length above one, and length 1 is covered
by a null pattern that puts a real null in the batch, where the reference and
the kernel agree on what is null. The general form of the lesson: a harness
trick that fakes a count must be checked against every length the matrix
runs, because a count that is a small fraction of one batch is the whole of
another.

## A plan change tested only with adaptive execution off is untested in Spark's default

The shared Varka sessions turn adaptive execution off for deterministic plans, and VARKA-185's wide
cache scan passed every test that way while doing nothing in Spark's default configuration: with
adaptive execution on, a query with a sort, an aggregate, a join, a window or a subquery has its
cache scan wrapped in a `TableCacheQueryStageExec` before the columnar rules run, and a rule that
matches a bare `InMemoryTableScanExec` never sees one (`VARKA-185.md` 8.5). So a change to
what the rule matches, or to the plan below a Varka node, gets at least one test with adaptive
execution on and a query that makes a stage - `ORDER BY`, `GROUP BY` - as `VarkaDifferentialSuite`'s
"under AQE" tests do.

The same review found the second way a plan-shape change goes wrong: a leaf that wraps another
node as a field hides it from every piece of Spark that walks `children` to find it - observed
metrics, the pipelined-shuffle eligibility check, subquery reuse, EXPLAIN and the SQL UI. Changing
the wrapped node's own decision, where one line does it, beats wrapping it. And a fact that cost a
run: Spark's default cache serializer produces batches only for primitive numeric columns, so a
benchmark of the cache's columnar path over a table with a date column measures rows on that
serializer.

## Predict immunity from what the scan produces, and pin both arms of a reproducer

Three findings in milestone 6 were one mistake. `m6/PLAN.md` 2.11 predicted Varka
immune to `spark.sql.codegen.maxFields`, because Varka reads batches and the limit is about
generated code; but `InMemoryTableScanExec.supportsColumnar` counts the whole cached schema, so a
cached table of more than a hundred columns produces no batches whatever the query reads, and
Varka had nothing to fuse (`VARKA-185.md`). The same plan called the 8000-byte cliff silent;
Spark logs it at INFO (`VARKA-188.md` 5). And VARKA-192's first benchmark measured Spark's
default cache serializer, not Arrow's, so its "vanilla" arm was never columnar
(`VARKA-192.md` 9.4). In each case the claim was written from the operator Varka replaces,
and the answer lived one node below it, in what the scan hands up.

So a prediction about a cliff, a fallback or an immunity is checked at the input path first:
which node produces the batches, under which config, for which serializer, and what it counts.
`EXPLAIN` shows the node; the fusion report and the INFO line say why a projection or filter was
left to Spark (`VARKA-185.md` 8.4); and a benchmark's provenance names the serializer, since
"the cache" is two different inputs.

The reproducer that pins such a finding has two arms, and both are asserted. A test that makes
vanilla give up (`VarkaCodegenGiveUpSuite`) proves the cliff; the claim the project makes is
what Varka does at that cliff, which is a second assertion on the same query: the Varka node
present in the plan, or a decline with the recorded reason. A reproducer with only the vanilla
arm pins the half of the claim nobody disputes.

## A reproducer past 64KB is sized near the smallest count that crosses it

Janino's heap grows much faster than the method it compiles. `VarkaCodegenGiveUpSuite`'s G32
compiles one projection method past 64KB so that the factory falls back to the interpreter, and
its live heap after collection was measured at 363MB for 600 entries (still under 64KB), 473MB
for 1000, 853MB for 1500 and about 3GB for 3000. Alone, 3000 entries pass. In CI's
`sql - other tests` job every sql suite shares one 4GB test JVM, and a pull request that touches
the build workflow runs that job; there G32 at 3000 ran the heap out, aborted the suite and hung
the job until its timeout. The Varka suites job runs the suite in a JVM of its own, which is why
the pull request that added the test passed.

So a test that has to cross a code-size limit uses a count near the smallest one that crosses
it, checked with `-Xlog:gc:file=<path>` on `Test/javaOptions` and a `-z` run of the one test.
The other reproducers in that suite peak between 112MB and 638MB each, and the suite as a whole
at 731MB.

## A Scala `List` handed to Java through `asJava` is not random access

`fused.outputs` is a Scala `List` - `ArrayBuffer.toSeq` builds one - and `asJava` wraps it without
copying, so `get(i)` walks from the head. Java code that indexes such a list in a loop turns linear
work quadratic, and nothing in the types says so. VARKA-191's admission check profiled the emitter
through `VarkaEmitDump`, which passed exactly that list, while production passed the shape cache's
copy: the probe read an emission three times slower than the benchmark and charged the difference
to the slot planner, and the planner's loop headers held samples a loop over arrays could not have
earned (`VARKA-191.md` 9). The emitter now copies its outputs on entry. The general rule: a
Java method that indexes a list it was given copies it first (`List.copyOf`, free for a list that
is already immutable), and a probe that disagrees with its benchmark about the same code is
measuring its own harness until shown otherwise.

## A probe's marker can share a line with a compiler thread's output

A suite that forks a JVM under `-XX:+PrintCompilation` or `PrintInlining` and reads the child's
stdout for markers (`VARKA_LADDER_DONE`, `VARKA_AUDIT_PREFERRED_BITS=256`) is reading a stream
that HotSpot's compiler threads and log4j write to as well, and they do not coordinate with the
probe's `System.out`. A marker can therefore share a line with a compile record, on either
side of it, and a reader that requires the marker on a line of its own (`startsWith`, `==`
after trimming) misses it: the probe exits 0, having printed the marker, and the test fails on
"done was false" with forty compile lines as its evidence. It happened once in about a hundred
runs of `VarkaSizeLadderJitSuite` on the fork CI (#473, 27 September 2026).

* **Read markers through `VarkaProbeOutput`**: `has` finds a marker anywhere in the line;
  `longAfter` reads the digits after a prefix, `tokenAfter` a name up to the next whitespace,
  `between` a name that holds spaces up to the end mark the probe prints after it, and `after`
  a payload of `key=value` fields as the rest of the line. HotSpot pads a record's
  timestamp with spaces, which is what keeps a number's digits apart from a record glued after
  them.
* **Read every line for both**: a line that carries a marker may carry a compile record too, so
  the marker check and the record parser are two `if`s, not an `if`/`else`.
* **The Python readers already did this** (`dev/varka_inlining_cliff.py`, `dev/varka_deopt_cycle.py`
  use `in` and `split(MARKER, 1)`); the Scala suites had each written their own stricter loop.
  `VarkaProbeOutputSuite` is the parser test to extend when a new marker shape appears.

## `failAfter` without a signaler cannot stop a test, so a hang holds the CI slot

`SparkFunSuite` wraps every test in ScalaTest's `failAfter(20 minutes)`, and it reads as a cap.
It is not one: without a `Signaler`, `failAfter` only checks the clock after the body returns,
and a body that never returns is never reported. A compiler loop that did not terminate (VARKA-219's demotion loop, on #456) held the fork's one Build slot for the job's whole limit, printing
only ScalaTest's slowpoke "still running", and named no test. Interrupting the test thread would
not have ended it either: a CPU-bound loop checks no interrupt.

* **`VarkaTestWatchdog`**, mixed into every Varka suite, runs each test beside a watchdog thread.
  At the cap (`varka.test.watchdog.minutes`, ten) it interrupts the test thread, which ends a body
  that blocks or checks interrupts; thirty seconds later, if the test still runs, it writes every
  thread's stack under the test's name and halts the JVM; the log's last lines say which test
  and what it was doing. `Thread.stop` is gone since JDK 20, so a halt is what remains for a
  loop that will not stop. sbt does not report the halted suite aborted: it waits on the forked
  JVM's socket indefinitely, printing "Unable to read from client" (VARKA-283, which found the
  gate's sweep step had not passed since the watchdog landed), so every gate and nightly step
  runs under `dev/varka_deadline.sh`, which waits on the process it started, never on a name,
  and kills the process group at its deadline.
* **Size the cap from the slowest test on record**, not the job's limit: the width audit's census
  takes about two minutes, and the suites' CI jobs finish in eleven to seventeen minutes whole,
  so a single test past ten is a hang. A suite meant to run longer overrides `watchdogMinutes`.
* **Test the mechanism with its effects as parameters.** `VarkaTestWatchdogSuite` passes a
  report sink and a halt stub, so the three outcomes take under a second each and the JVM stays.

## A fuzz campaign finds what the gate's count cannot, once every failure names its shape

The IR fuzzer runs 300 iterations in the gate and 10,000 in the nightly. On the night of 29 to 30
September 2026 twenty workers ran it at 200,000 iterations a run, and three runs in four failed on
one emitter self-check that the default counts meet about once in 150,000 shapes (VARKA-234). Two
habits follow:

* **After a change to grouping, liveness or a shared fragment, fuzz at campaign scale** before
  trusting the gate. Twenty workers on one laptop run it as plain `java` processes on the test
  classpath exported once from sbt, each with a 1 GB heap, a seed of its own, and the per-test
  watchdog raised past the run's length; twenty sbt invocations in one worktree contend on its
  lock.
* **Every way a fuzz case can fail must print its context.** The suite named its seed and iteration
  for a rejection or a decline but not for an `IllegalStateException` from one of the emitter's own
  checks, so the first failing shape had to be found with a local patch. `emitOrSkip` now names it.

## A skip list without reasons rots; a marker that fails when its claim stops being true cannot

From the survey of thirteen engines' testing for milestone 7 (`m7/READING.md` 2 and 3).
Every engine that holds a fast path to a slower one reruns one corpus under many configurations,
and the ones whose lists stay honest share two habits. DuckDB reruns its whole suite under
dozens of configurations (`test/configs`), and every skipped test carries a reason, so the skip
lists double as the record of known divergences. Druid runs every SQL test with vectorization off
and forced on, and a test marked `cannotVectorize()` fails the moment it vectorizes; Gluten's
Delta gate fails on a new failure and also when an excluded test starts passing. The first habit
makes a skip reviewable; the second makes it expire. A list with neither is a place where
regressions hide once the bug that justified an entry is fixed. Varka's matrices - the options
table's configurations (row 248), Spark's own suites with Varka on (row 271) - take both: a
reason per skipped entry, and a marker that fails when its shape starts to fuse or its test starts
passing.

## A long opt-in run needs the suite timeout raised, or it fails as a hang

- `SparkFunSuite` fails any test body past twenty minutes, and `VarkaTestWatchdog` halts the
  JVM at ten. Both are right for a suite and wrong for a deliberate long run: on 3 October
  2026 the overnight fuzz jobs for SPARK-33301 (tens of thousands of queries a job) all "failed"
  at the twenty-minute mark with nothing wrong in them, and the gate's sweep step halted on VARKA-149's exhaustive division sweep, which needs hours (row 283 of milestone 7).
- A long run passes its own cap: `-Dspark.test.timeout=<minutes>` for `SparkFunSuite`'s and
  `-Dvarka.test.watchdog.minutes=<minutes>` for the watchdog's, set by the script that launches
  the run, with the expected duration written beside it. A run that will take hours is also
  better split by its natural unit - a divisor, a seed - so each piece stays inside the caps.

## A fuzz failure arrives shrunk, so read the shrunk case and replay by seed

`VarkaIrFuzzSuite` shrinks its own failures (VARKA-277): the test fails with the drawn case and
then `shrunk to lane=int roots=[...] options={...} length=1 ...`, with the signature (the kind of
failure and its message with the numbers taken out) and the number of runs it cost. Read the
shrunk line first; the options named are the delta from the defaults and the roots are the
smallest tree that still fails the same way. Replay the drawn case with `-Dvarka.fuzz.seed` and
`-Dvarka.fuzz.only`, as before; `-Dvarka.fuzz.shrink=false` leaves a failure as drawn.

* A result is **1-minimal under the moves, not the smallest case there is**: a condition root stays
  a condition, and the two operands whose domain the grammar constrains (the month count of
  `add_months`, the level of a dynamic truncation) are left alone, so a shrunk tree can be larger
  than the one you would write by hand.
* **Any `Throwable` is a failure.** An error out of the generated class (`NoSuchMethodError`,
  `VerifyError`) used to end the suite as an uncaught error and is now a failure of one test
  with its own signature.
* A failure that is known goes in `sql/varka/fuzz/known_failures.tsv` with the lane, seed,
  iteration, signature and the row that owns it; the run then does not fail on it, and the list
  fails when an entry stops reproducing. Prove a change to the shrinker on the planted bugs
  (`misdescribeWordLiveness`, `misdescribeAdd`), not on a bug that may be fixed next week.

The composition fuzzer (`VarkaCoverageCompositionFuzzSuite`, VARKA-294) does the same for its
three tests: a failure is shrunk to `shrunk to kind=wide options={...} entries=N:` with one
expression per line, and the lane of a known-failure line is `projection`, `predicate` or `wide`.
The moves are the entries (ddmin), each entry's expression (a node replaced by a child of its own
data type) and the options. Two things to know when reading one. The first line of a finding is
what groups it, so a finding's message puts the case on the lines after it. And the wide test
draws its batches from a seed taken after the picks, so a case replays on the batches it failed
on; a wide failure found before VARKA-294 replays its picks, copies and options but not its
batches. Prove a change to this shrinker on `misdescribeAdd` through a wide case, which shrinks
a draw of two hundred entries to one.

## A suite that needs another vector width runs in a JVM of its own, and a guard says when one does not

One species per lane type is what keeps the Vector API's templates monomorphic in a JVM
(`vector-api-and-width.md`), so the shared test JVM must not define a kernel at a width beside the
one it has used (VARKA-246). `VarkaSpeciesGuard` reads the emitted bytes and throws, at
`VarkaEmitterTestBase.load` and `VarkaKernelCheck`'s runners, where a class would add a second size
to a vector class; it compares with the sizes already used and not with the preferred one, so the
option matrix's `lanesOverride=4` configuration (every kernel at 128 bits) is consistent. Two modes
of the guard: `-Dvarka.speciesGuard=report -Dvarka.speciesGuard.report=<file>` appends every class
that names a non-preferred species (suite, class, species) and lets the test run, which is how to
take a census; `-Dvarka.speciesGuard=off` disables it.

A suite that must run at another width mixes in `VarkaOwnJvm`, last. In a shared JVM its tests keep
their names and tags, and the first to run starts a child JVM running the suite and reading its
JUnit report; the others take their result from it. A name filter (`-z`) selects the tests it
names, but the child runs the whole suite. To run one test directly, set
`-Dvarka.ownJvm=true` (the guard is off there). That is also the only way to time one: in the
replay every test reads a millisecond or so and the first carries the child's whole run, which put
VARKA-240's eleven exhaustive sweeps at a millisecond each in the nightly's log. The child is ended after the shortest per-test cap
less a minute (`-Dvarka.ownJvm.timeoutMinutes` overrides), so a hung child is reported before a cap
halts the parent. That budget is one test's cap for the whole suite, and the parent's waiting test
has the same cap, so a slow suite cannot be given more room there: `dev/varka_matrix.sh` (the
option matrix, the gate and CI's Varka jobs) gives each such suite a runner JVM of its own that
runs it directly, each test under its own cap, since `VarkaIrFuzzSuite` takes about the nine
minutes on a runner under any configuration and ran past them in two (VARKA-394), as the nightly
fuzz workflow does for its JVMs (VARKA-392). Run a new width-dependent test at all three widths a runner may have
(`-XX:MaxVectorSize=64`, `32`, `16`): a 16-lane override is the preferred width at 512 bits and a
second species at the others.

## The random differential against Spark: read the shrunk reproducer, check the pool, mind the fixture

`VarkaSparkFuzzSuite` (VARKA-262) composes random outputs and filters from the rows of
`sql/varka/coverage.json`, runs them over random data with Varka off and on, ANSI off and on, and
compares the answers, or the class of the error when a side throws. A disagreement is shrunk
(the outputs, the conjuncts, the rows of data) and written to `sql/core/target/varka-sparkfuzz/`
as a file of three parts that `spark-sql` can run: a header, the fixture, the query. Read that
file first; it is the whole case.

* **Keep a reviewed reproducer** in `sql/varka/fuzz/spark/`, marked `status: regression` once the
  bug is fixed (the replay suite then requires agreement) or `status: known <row>` while it is
  open (the replay requires the disagreement to remain, so the file cannot outlive the bug).
* **The data pool matters more than the count.** A pool of hostile values (far dates, the bounds
  of an int or a long) made 70% of the cases fail on *both* engines, which compares the class of
  an error and says nothing about the other rows. Half the cases draw only safe rows, so that the
  ANSI-on arm compares values; the other half add up to two hostile rows. Read the suite's
  `info` lines: a pool whose both-errored count is above a third has stopped comparing values.
* **A fixture that omits a column the table names fails every row that reads it, identically on
  both sides**, and reads as agreement. The first harness of the task lacked the three interval
  columns (`ymm`, `ymy`, `ym`) and counted every row using them as a double error; the numbers
  looked plausible and the harness was blind to a quarter of the table. Build the fixture with
  the table's own column list, and count the errors by cause.
* **Stratify the draw.** A uniform draw over the 92 rows found a planted bug in one row after 254
  of 300 compositions; forcing row `k mod 92` into composition `k` finds it in the second pass.

## The differential with the warm-up on: both sides of the handover compared, a release reported

The shared sessions turn the kernel warm-up off, so every other Spark-level suite's kernels serve a
shape's first batch, interpreted, and none of them meets code C2 compiled from the warm-up's copy of
a batch. `VarkaSparkFuzzSuite`'s warmed arm (VARKA-327, "random compositions agree with the row
engine on both sides of the warm-up handover") turns it on: per composition, from an empty shape
cache, the query on the row path while its kernel warms, again after `awaitIdle` until no node
waited (a projection over a Varka filter claims its warm-up only once the filter serves Arrow
batches, so up to four runs), then the warmed shape over other data with the same nulls
(`laterData`), every run against the row engine. `-Dvarka.sparkfuzz.warmupIterations` (8 by
default, 2,000 in the nightly's `sparkwarm` step), `-Dvarka.sparkfuzz.warmupOnly=<it>` replays one.

* **The kind names the side of the switch**: "on the row path while the kernel warms", "after the
  handover to the kernel", "over other data after the handover". A reproducer is not shrunk -
  each smaller case is a new warm-up - and replays by its note's flags. Its status is `warmed`,
  which `VarkaSparkReproducerSuite` refuses: that suite replays with the warm-up off, where the
  case's path is not taken, so a warmed case kept in `sql/varka/fuzz/spark/` would pass for the
  wrong reason.
* **A release is evidence, never a failure.** About one small kernel in fifteen never gets its
  verdict (`VARKA-327.md` 2), so the arm sets the deadline to 10 s
  (`-Dvarka.sparkfuzz.warmupDeadlineSeconds`) where production waits 60, and reports each release
  in the test's output: the outcome, the JIT state at the release (queue, code list, methods with
  no code) and the `VarkaJitEvidence` account. Read it for VARKA-295: an empty C2 queue with the
  kernel's loops compiled and the probe still allocating is its signature; `profile_predicate`
  traps in a masked loop are VARKA-326's cycle; a busy queue means the deadline was too short.
* **Read the counts before trusting a pass.** Three in four compositions hand over; the rest have
  no Varka node, raise on both sides, or have every cached batch pruned by the scan's statistics
  (`numInputBatches` 0); the counts name each. At 50 or more the test fails if fewer than half
  hand over.


## A decline is the entry deleted, and only a forced decline reaches the branch that has to make it so

A projection entry that declines after it compiled has to leave the compiler's shared tables -
inputs, literals, the long table, the bounds - exactly as if it had never been compiled, because the
kernel the other entries make is built from them. Most mistakes in that rollback change no answer:
a stale input is a column read for nothing, a stale bound a batch declined for nothing. The answer
differentials cannot see them, and VARKA-296's admission check found that natural declines never
reach the branch that rolls back a compiled entry at all: every one of 21,726 at the compiler level
was the one-lane rule, which has a rollback of its own.

So VARKA-296 forces one (`VarkaEmitOptions.forceResidualAt`, a fault injector) and holds the
compiler to a property no oracle is needed for: the projection with entry k forced to decline equals
the projection with entry k deleted - the fused sub-projection, the further kernels, the specs past
k and the declines shifted past it (`VarkaCoverageCompositionFuzzSuite`, "a forced decline leaves
the projection the entry was never in"). The Spark differential forces an output too and compares
the answer, counted only where the executed plan's `numResidualEntries` rose by exactly one, since a
force that lands on an entry already residual, or leaves no Varka node, moves nothing.

Two things to carry. When a failure is reported as "a forced decline differs from the entry deleted
on: inputOrdinals", the rollback of `classifyOnce`'s last branch left a table behind; the planted
faults of `misdescribeRollback` (1 keeps the inputs, 2 the bounds, 3 truncates one too far) are what
the check looks like when it works. And a hand-built case for such a check has to give the fault
somewhere to show: a stale input is invisible when a later entry reads the same column, and an
over-truncation is invisible when the dropped column is registered again at the same slot.

## Under test a failure fallback fails its task, unless the test declared it

The ghost fallback answers a kernel failure, a failure of the row machinery beside the kernel and
an emission failure with the row engine's right answers, so a test that checks answers cannot see
a broken kernel. Since VARKA-275, under test (`Utils.isTesting`) each of those three throws an
`IllegalStateException` naming VARKA-275 instead, with the failure as its cause, after it has been
counted and logged as before. A test that causes one on purpose declares it: the failure hooks
`setFailKernelForTesting` and `setFailEmissionForTesting` do so by being set, and anything else - a
planted emitter option, an expression made to throw - sets
`VarkaColumnarToRowExec.setFailureFallbackExpectedForTesting(true)` and resets it in a `finally`.
Declines, non-Arrow batches, warm-up batches, the emitter's over-budget decline and failures in a
task already being killed are not failures and need nothing.

If a test you did not expect fails with that message, the fallback was hiding something: read the
cause, which is the kernel's own exception. Before the rule, two tests caused failures without a
hook and passed by accident, one because its `intercept[Throwable]` caught whatever was thrown;
`intercept` the specific outcome you mean.

## The coverage of emitted classes: one JaCoCo builder per class, and the agent into own-JVM suites

`dev/varka_gen_coverage.sh` (VARKA-285) runs the Varka suites under the JaCoCo agent and writes
`sql/varka/coverage/generated.md`. Four things it took to make the numbers mean what they say:

* **`inclnolocationclasses=true` and `classdumpdir`**: an emitted class has no code source, and
  its bytes exist nowhere but in the dump.
* **One `CoverageBuilder` per class**: the suites emit one name with different bytes, which
  JaCoCo's own report refuses.
* **JaCoCo's agent must reach `VarkaOwnJvm`'s children.** The child drops every `-javaagent` from
  its command line but JaCoCo's; without that, both fuzzers and eight other suites are missing,
  and the first run counted 907 classes run where there were 2,803.
* **"Ran" is a kernel method entered**, not any covered instruction: a test that builds a kernel
  and calls the wrong overload covers only the constructor.

## How well the tests test: the emitter's branches, and PIT through ScalaTest test by test

VARKA-265. `dev/varka_gen_coverage.sh` also writes `sql/varka/coverage/emitter.md`: the branches
of the emitter's and the compiler's own classes under every Varka suite, from the execution file
the generated-code report reads. `dev/varka_pit.sh` mutates emitter classes (default `Slots` and
`Analysis`) with PIT and gives each mutant one of three verdicts - killed by a behavioural test,
killed only by `VarkaEmittedBytesSuite`, survived - with each survivor answered in
`sql/varka/mutation/notes.tsv`. What it took:

- **PIT reaches ScalaTest with no third-party plugin.** PIT 1.30.0 (`org.pitest`, Maven Central)
  ships JUnit 4 support only; its `TestPluginFactory` SPI takes a plugin of about 220 lines
  (`dev/varka_pit/VarkaPitScalaTest.java`) that makes each test of a suite one unit, run alone by
  `run(Some(name), args)` on a fresh instance. Give its configuration a priority under 10, or
  PIT's JUnit finder is asked first. ScalaTest's `Stopper.default()` and `Filter.default()` are
  named with a Java keyword, so Java reaches them by reflection.
- **Empty the shape cache before every test.** A kernel cached by an earlier test was emitted by
  the code before the mutant was swapped in, and hides the mutant from the next test.
- **Spark's sbt exports catalyst as a jar**, so PIT finds no mutable code ("No mutations found",
  `codePaths=[]` in its verbose log) until the jar on the minions' classpath gives way to
  `sql/catalyst/target/scala-2.13/classes` and `--mutableCodePaths` names it. `pitest-entry` also
  needs Commons Text in the launching JVM, which Spark's test classpath already has.
- **PIT makes one work unit of a class**, which one minion runs alone however many threads are
  given; `--mutationUnitSize` splits it.
- **Run own-JVM suites inline** (`-Dvarka.ownJvm=true`): a child JVM loads the unmutated classes.
  Leave out what answers to the JIT or the machine; `VarkaWidthAuditSuite` fails under PIT's
  agent, and the IR fuzzer's C2 test (482 s of an 869 s coverage phase) runs with no draws.
- **The bytes oracle kills nearly everything a behavioural suite misses**, equivalent mutants
  included - in the first probe, 45 of the 48 one suite left - so its kill is read as a survivor,
  and its run gets only the first run's leftovers (`dev/varka_pit/VarkaPitOnly.java`, PIT's
  interceptor SPI); its pinned-shapes test took 48 s under load. The same holds for every test
  tagged `PinsDefaults` (a grouping, an op count pinned at the defaults), which the plugin moves
  from the first run to the second by its tag.
- **Never answer a mutant from a narrow run, nor from one kill.** Against one suite, removing
  javac's `requireNonNull` on `pureOf`'s pattern switch survived; against all 40 suites in the
  sample it read as killed. The selector is never null and the mutant is equivalent: the kill was
  a test failing under PIT for reasons of its own ("Read the killing test" below).
- **A survivor costs the suites; a kill costs a test.** In the two-method sample a killed mutant
  ran one test (the median) and the one survivor ran 405, so a full run's length is its survivors'
  - the bytes-only ones included, since the first run does not kill them either.
- **The free jar's bitwise mutators are `MATH`'s** (AND and OR exchanged, XOR to AND, the shifts
  exchanged), one of the defaults; no other bitwise set ships free.
- **Do not edit a shell script while it runs**: bash reads it as it goes, and the edited tail ran
  as a syntax error after the PIT runs it had launched had finished.
- **Read which branch a conditional mutant took before answering it.** PIT's index is the ASM
  tree node index plus one (labels, line numbers and frames count), so the mutated instruction is
  node `index - 1` of the method read with `ClassReader.EXPAND_FRAMES`. `RemoveConditional`'s
  `EQUAL_IF` ("replaced equality check with true") deletes the jump, so the code after it always
  runs; `EQUAL_ELSE` ("with false") makes it unconditional. Which source test that is depends on
  how javac laid the condition out: `a != b` compiles to a jump on equality, so "with true" there
  keeps the body, and a first reading of `collectRefusals`' MUL test from the description alone
  had it backwards (VARKA-265.md 9.8).
- **Read the killing test of a bytes-only kill.** In the full run 87 of the 117 were one test,
  `VarkaExpressionCompilerSuite`'s planSize bisection, killing removed `requireNonNull` calls
  among others; rerun on the laptop it killed one of three such mutants. A test that fails for
  reasons of its own in a mutant minion turns survivors into bytes-only kills, and in the
  behavioural run into behavioural ones.
- **A `PinsDefaults` test that also checks answers hides behavioural kills.** The tag moves the
  whole test to the bytes run, so a `checkMatrix` inside it reads as the oracle; 11 of the full
  run's bytes-only kills were one such test's answers. Split it: the pin keeps the tag, the
  answers do not.
- **Check that a new test kills its mutants with `--only`**: `dev/varka_pit.sh --only FILE
  --suites ...` mutates only the identities FILE lists, one a line as `--keep` writes them, and
  runs the named suites against them - minutes where the full run is hours.

## A solver proves an arithmetic lowering in integers, and its verdicts are checked like output

VARKA-240 proves the int lane's multiply-high division exact with SMT solvers
(`sql/varka/proofs/`, run by `dev/varka_prove.sh`), VARKA-241 the long lane's two forms, and
VARKA-242 the calendar's divisions, `floorMod7` and the leap hash. What the next proof needs:

* **State Java's arithmetic over integers, not bit-vectors.** "For every int n, the lanes compute
  n / 12" over bit-vectors ran past five minutes under Z3, cvc5 and Bitwuzla alike, with Java's `/`
  as `bvsdiv` or by its JLS definition, and under Z3's `intblast` and `polysat` engines too:
  bit-blasting makes it the equivalence of two multiplier circuits, which a CDCL solver does not
  prove. Over integers, with Java's wrapping stated by `mod` in the prelude `java.smt2`, every
  product has a constant factor and the statement is linear, and both solvers prove it in under a
  second. cvc5's `--solve-bv-as-int=sum` decides the bit-vector form as well, but Z3 has nothing
  that does, and two solvers agreeing is the point. A counterexample, when there is one, every
  encoding finds in milliseconds, so a fast `sat` says nothing about how hard the `unsat` will be.
* **Hold the model to the JVM.** A prelude definition with Java's name and another meaning makes a
  proof prove the wrong thing. `java_check.smt2` evaluates each definition over boundary operands
  against values Java computed while rendering, and it caught all five faults planted in the
  prelude, two of which - an `L2I` that saturates, and `Integer.MIN_VALUE / -1` left unwrapped - the
  multiply-high proof alone went on proving.
* **A verdict is output, and has to meet an expectation.** Z3 reports an error, drops the assertion
  it could not read and goes on answering: declared `QF_LIA`, it rejected the prelude's multiplying
  definition and then answered `sat`, which a check expecting `sat` would have taken. So every check
  carries its expectation in an `(echo ...)`, and the script fails on any line that is neither an
  expectation nor a verdict, on `unknown` (which a check past its limit answers), and on a missing
  verdict; `--self-test` plants each failure and fails unless every one is caught. cvc5 needs
  `--incremental` for `push`, while Z3 answers `(set-option :incremental true)` with an error, so
  the option goes on the command line; Z3 prints an echo bare and cvc5 quoted.
* **Count before asserting "for every".** Granlund and Montgomery's Theorem 5.1 inequality fails for
  327,741,950 of the multipliers Hacker's Delight's `magic` derives, the first for 196611, although
  each is exact by the book's own sharper condition: the theorem's hypothesis is sufficient, not
  necessary. Deriving all 2^31 divisors, a minute on 24 threads, is what found it; `signedMagic`
  now raises the shift until the theorem holds, which moved no divisor in use.
* **Doubles too: state the rounding, not the floating-point theory** (VARKA-241). The long lane's
  division forms in SMT-LIB's floating-point theory took Bitwuzla eight minutes for divisor 1000
  and past 25 for 10^9, and Z3 and cvc5 longer; slicing the dividend, Bitwuzla's options and an
  encoding with no divider circuit did not help, because the arithmetic, not the divider, is what
  CDCL cannot do. In integers, a double as `M * 2^E` and rounding to nearest stated by the
  midpoints to its neighbours (`jdouble.rne`), one check per binade so each is linear, both forms
  for seven divisors take 28 s under Z3 and 8 s under cvc5, and 59 s under Z3 on the CI runner,
  so the linters' job proves that file under cvc5 (`dev/varka_prove.sh --lint`). Two things this
  needs: a check that the binades listed cover every value of the domain, without which a check
  passes by leaving a value out; and the JVM check refusing a double's neighbours across a binade's
  edge as well as inside it - the neighbour inside the binade alone let a wrong midpoint at the
  bottom of a binade through every check until that was added.
* **cvc5's `unknown` depends on the declared logic, the encoding, and what ran before**
  (VARKA-242). `floorMod7`'s shipped form, stated directly over the prelude, takes Z3 3 s and cvc5
  past 300 s, over bit-vectors both past 300 s. Stated by lemmas over each fold's digits
  (`u = 2^k * a + b`), every step is linear over a few small variables, and both solvers decide it
  in milliseconds - but cvc5 only under `(set-logic ALL)`: under `QF_NIA` it left six of the same
  checks unknown after 60 s. And a check that holds alone can come back unknown after the file's
  earlier checks: three times a rewrite fixed one check and moved the `unknown` to another. What
  made the file hold whole and check by check was writing residues as 7 times a declared integer
  instead of through `mod`, naming the truncated quotient instead of asking for `jint.div`, and one
  wrapping operation per check. No cvc5 option was reliable: three fixed the file and each failed
  an earlier variant. So a new proof file is run whole and each check alone, under both solvers.
* **A site's theorem is not the decomposition's** (VARKA-242). With `YEAR_M` raised by one, the
  divide by 365 first fails at 22994, inside its range of 36524, and still every sweep of the
  century-then-year form agrees with `LocalDate` over a whole era: the step after it, which gives a
  day back when the year overshoots, absorbs the error. The proof of each `ChronoDivide` site and
  the pinned first failing dividends see it; an end-to-end sweep cannot. So a constant's comment
  states its site's bound, and the proof holds the site to it.
* **An interval analysis over wrapping lanes is sound as a congruence, not a containment**
  (VARKA-281). `VarkaRangeAnalysisSuite`'s property test asserts that the interval contains the
  lane value, and passes only because the grammar's literals are small: `date_add(d, 2147483647)`
  carries the contract's interval past int32 while the lanes wrap, and a second shift back lands
  both inside again, correctly. The theorem is the witness form - a member of the interval
  congruent modulo 2^32 to the lane value - which every consumer turns into containment by reading
  an interval only when it lies within int32. Stated that way each rule is local, quantifier-free
  once the witness is written out, and linear; the two `sat` checks that keep the edge visible are
  in `range_analysis.smt2`.
* **The two solvers are complementary on nonlinear claims, and the reals are a way out**
  (VARKA-281). Over the integers cvc5 proves a wrapped product's congruence in a fifth of a second
  (its incremental linearization, which Zohar et al.'s int-blasting leans on) and Z3 leaves it
  unknown, while Z3 proves division by a symbolic divisor monotone and cvc5 leaves that unknown.
  The nightly needs both, so: a claim true over the reals - the four-corner product theorem, a
  symmetric magnitude - is stated over `Real` sorts, where nlsat decides it instantly and every
  integer instance follows; a congruence goes in Fiat's two layers, the ring identity as a
  polynomial and the wrap's periodicity as a linear lemma; a symbolic divisor is instantiated per
  divisor in use.
* **Split a slow Presburger check by sign** (VARKA-281). `add_months` by a symbolic month count
  over `java.time`'s closed forms took Z3 12 s as one check, past the ten seconds a check has on
  the runner, and cvc5 could not decide it under `QF_NIA`; split by the signs of the count, the
  year and the target year, each of the eight cases decides in under 2 s under either solver, and
  the transcription of `toEpochDay`, with its own branch on the year's sign, is pinned to the JVM
  at the branch edges.

## Every small tree, counted in grammar productions, beside the random fuzzer

VARKA-269. `VarkaIrSeries` enumerates every tree `VarkaIrGrammar` could draw up to a size, arm by
arm with the grammar's own `boundsOf` for its gates, and `VarkaSmallTreeSuite` runs each through
the emitter against the reference evaluator. Three things decided how it is built.

- **Size is grammar productions, not IR nodes or depth.** `make_date` over a date's fields is one
  arm whose IR repeats the date three times; counted in nodes it never appears at a size that runs.
  And the condition leaf matters: with `IsNotNull` over a column counted as two, no `IfElse` exists
  below size 5; as one, it appears at 4, the size the nightly jobs and the weekly option matrix run.
- **The counts grow about thirty-fold a size** (3,203 trees to size 3, 101,422 to 4, 3.26 million to
  5 at the int lane), so each tier is one size: 3 in the gate (4 until it cost the gate about 400
  seconds a width; no `IfElse` exists below 4, so the gate's series has none and
  `VarkaIrFuzzSuite`'s draws carry it), 4 at the narrow width nightly and under every emitter
  configuration weekly, 5 nightly, 6 in an idle window, sliced across JVMs
  (`-Dvarka.series.slice=k/n`, `dev/varka_series.sh`) because the emitter is not shown thread-safe.
  A larger tree costs more to emit, so a size's time is not its count times the smaller size's rate:
  size 5 took three times the estimate.
- **The gates are checked from the other side.** The same arms without the magnitude gates give
  a series whose extra trees are each either guarded by `Analysis` (a decline the reference
  cannot spell) or outside the contract (a calendar node over a product of columns, which the
  compiler never builds); one the emitter refused would be a tree the gates hide from every
  fuzzer.

To size 5 nothing failed, at either lane. A planted bug (`misdescribeWordLiveness`) fails it by
size 3, which is what shows a green run means something.

## A tree in other company: variants whose answers follow from its own

VARKA-278. `VarkaVariantSuite` emits every small tree that passes alone again in eight variants
(`VarkaVariants`: beside a child, permuted, duplicated, copied, beside a stranger, literal to
column, columns renumbered, untaken arm replaced), and `VarkaIrFuzzSuite` does the same with its
passing draws; each variant is compared with the reference evaluator, never with the original's
output. What building them taught:

- **A variant that holds for one batch's nulls must not meet the other body.** The untaken-arm
  variant replaces an `IfElse` arm no row of the batch takes. `VarkaKernelCheck` then runs the
  batch again through the other body, which for a batch with nulls clears every null - and a
  condition such as `isNotNull(c0)` over an all-null column turns true, the replaced arm is
  taken, and its values leave the range an enclosing `GuardedRange` was drawn for, so the kernel
  rightly declines. The fuzzer's first run found it, and review found the same at one row, where
  a null-free batch's other body is every column null. So the arm oracle reads each condition
  over both batches the check runs, and an arm counts as untaken only if neither takes it.
- **"Duplicated" and "copied" reach the same sharing today.** The IR nodes are records and the
  emitter keys every map on equality, with no `IdentityHashMap`, so one node object twice and two
  equal objects look alike to it. Both stay, so that a future identity-keyed map meets both.
- **A decline is allowed only by the guards a variant adds, found by position, not identity.** A
  rewrite rebuilds every ancestor of what it changes, so by identity a guard the original already
  had on that path looks new; `addedRefusalBits` pairs the variant's nodes with the original's
  and counts a refusal only where the original's node lacks it. The kernel is emitted again
  under `exactRefusalStatus` to read which guards fired. Moving a literal month
  count into a column is the case that needs it: the column count carries the month-count guard.
  The untaken-arm variant needs the rule too, though no row takes the replaced arm: the
  replacement can bring a guard of its own, unqualified where the arm does not mask it, and a
  node it puts under a calendar node gains the producer guard, which then covers every use of the
  node, including a live one elsewhere. A fuzz campaign of 24 seeds found all three cases.
- **The cost is about four draws' worth a draw.** The gate's size 3 is about 20 seconds a width
  at one batch per tree by rotation; size 4 is under four minutes over 24 JVMs on the laptop; the
  fuzzer's 300 draws a lane go from about a second to about four, so the nightly's forty thousand
  take the variants of every tenth.

To size 4 nothing failed and nothing declined, at either lane. Under the planted
`misdescribeWordLiveness`, beside a stranger and literal to column fail trees the plain series
passes, which is what shows the variants reach something the series alone does not.

## What the emitter reached, and steering a fuzzer toward the rest

VARKA-280. `VarkaEmitMechanism` lists 39 things the emitter decides about a whole shape - the
grouping, what a computation is shared with, the validity words, the guards, the division form,
the size loop's reactions, the declines - and the emitter notes each where it emits it into the
emission's `Analysis.reached`, handed to the `VarkaEmitTrace` when the emission ends. A suite
attaches a `VarkaEmitReach` to its trace and prints what it reached and never did; with
`VARKA_REACH_DUMP=<dir>` it writes the table under `sql/varka/coverage/reach/`. What it taught:

- **Note a mechanism where its bytes are emitted, not where a slot is planned.** `Slots.plan`
  plans slots for bodies no build emits (a dense body of a kernel that nulls valid inputs) and,
  without group-local frames, for other groups' nodes; a note there reports what was never built.
- **Report an emission's last pass, not the union of its builds.** The size loop rebuilds; a
  union over its passes reports a one-group class halved on bytes as one group and several, and
  pairs the last grouping with what earlier builds threw away. Each pass starts the grouping and
  body mechanisms afresh; only the loop's reactions and declines span passes.
- **A decline is an emission too.** The size loop hands the set over in a `catch` of
  `VarkaEmitDeclined` as well as on return, so a run whose shapes decline still says what they
  reached first; any other failure is not an emission and is not noted.
- **The suites' fixed seeds miss the size loop's corners.** The IR fuzzer's two lanes miss ten
  mechanisms: seven that only its wide compositions reach (stages, rollbacks, both grouping
  switches dropped, a class-wide decline) and three that nothing in it reaches (the plan's decline,
  the class-file cap's refusal, the constant pool's decline). The coverage table misses every size
  reaction and every decline: its widest shape is one kernel that fits. Count a report's misses
  from its last column, not by searching for "never": a mechanism's sentence can hold the word.
- **Steering does not pay at the gate's budget; at the nightly's it only gains.** Over 16
  seed-lanes at 300 emissions an arm, keeping a draw that reached a new set of mechanisms (QPG's
  rule without the trees) or a rare mechanism and mutating it - an option changed, a variant -
  did no better: the better of the two arms reached more than the plain draws in 7 and fewer in
  4, and both reached the common mechanisms at about twice the emission. At 3000 the steered arms
  reached fewer in none and more in 15 of 32 seed-lanes, among them the call-site rollback and
  both grouping switches dropped, which plain draws never reach: the growing variants, applied
  again to a case kept for growing, make it wide enough. Random draws reach a new mechanism set
  at most steps (185 of 300), so set novelty keeps nearly everything; rarity keeps less and did no
  better at 300.

## Every guard at its bound: a verdict from the emitter's own rule

VARKA-279. `VarkaGuardSpec` says per batch which guard bits a kernel must decline with, which it
may, or that it must answer, and `VarkaGuardEdgeSuite` holds sixteen small kernels to it, every
guard at `lo - 1`, `lo`, `hi` and `hi + 1`. What it took:

- **Read the qualification from `Analysis`, do not reconstruct it.** Under an `IfElse` arm a
  guard is masked by the arm only where every use of its node agrees on one arm chain; a node also
  used outside the arm, or inside a condition, condemns every lane. `guardsForTest` returns each
  refusing node's reason with its `armChainOf`, so the verdict applies exactly the rule the emitter does.
- **A bound in a `static final` field cannot be moved from a test.** The narrow range and the
  month limits are constants javac inlines into the emitter; the `make_date` years are computed
  when `VarkaChrono` loads; neither can change under a running suite. The planted wrong bound
  therefore moves the verdict's bound by one instead, which proves the same thing: the edge
  inputs sit exactly on each bound and one past it.
- **Hold each body to its own verdict.** A status from `runAndCompareStatus` does not say which
  body returned it, so a decline the first body must not make can hide behind one the second
  must; `runEachBodyStatus` runs both and returns both. And force the masked body on a valid edge,
  or a decline in the dense body stops the check before the masked body sees past the bound.
- **One batch covers the null edge and the valid edge.** `VarkaKernelCheck` poisons a null lane,
  and the other body clears the nulls, so the same data then makes the edge row valid.
- **Force the guards a configuration can switch off.** Under the option matrix a configuration may
  turn off `checkIntOverflow` or `guardDayProducers`, and the kernel would lose the very guard
  under test; the suite sets both on over the matrix's options.

To date every guard's edges hold at both lanes, in a full vector and the epilogue, through both
bodies.

## A planted bug has a prerequisite, and the test that plants it sets it over the matrix's base

VARKA-269's follow-up, 14 October 2026. `misdescribeWordLiveness` inverts the word-liveness
verdict, and `Slots.plan` asks for a verdict only in a masked loop or epilogue body under
`validityByBitmap`: off, every word is live, no verdict is computed, the inversion never runs,
and the kernel emits as if nothing were planted (the option's Javadoc says so;
`VarkaEmitterValiditySuite` pins it on `year(d)`). The two tests that hold the
series and the variants to catching the planted bug planted it over `VarkaMatrix.base`, so under
the matrix's `validityByBitmap=false`, and the configuration that turns off the three validity
passes, the bug was inert and the series passed every tree: "the planted bug failed no tree to
size 3". The first run of the option matrix on master (VARKA-255) failed the series' test; the
variants' had met the same two configurations in VARKA-278's local run of all fifty, been read as
the injector reaching a different set of words, and been listed in the skip list.

- **Plant the bug over the options it needs**: `VarkaMatrix.base.withValidityByBitmap(true)`, as
  `VarkaGuardEdgeSuite` sets the guards it tests and `VarkaReproductionEndToEndSuite` plants this
  one. A skip-list line records a dependency, but cancels the test in two configurations and keeps
  whatever mechanism its author read into it; the forced option keeps the test asserting
  everywhere, at no cost.
- **Count only the planted bug's own failure.** `failures.count > 0` is met by any failure, so
  with the bug inert one regression, or the forced option beside a configuration's others, would
  pass the test as a catch. Each failure counted is held to the invariant's refusal, "stored but
  never loaded" or "declared dead is loaded" (`VarkaKernelChecks.isWordLivenessRefusal`), which
  the review of the fix asked for.
- **The fuzzer's planted tests survived the same run** because they search the first 200 draws,
  whose options vary over the table, so a draw turns the pass back on; a test over one fixed
  options record has to set the prerequisite itself.
- **The injector stays inert without the pass.** Asking for the verdict under the injector too, or
  refusing the combination in the options builder, would make a third inert plant fail loudly;
  both change the emitter or the behaviour `VarkaEmitterValiditySuite` pins for a test-only gain,
  so the review's proposal is recorded and not taken. A third planting is the moment to reconsider.
- **Green under a pull request's one configuration says nothing about the other configurations.**
  The run after each merge draws a configuration from the commit's hash and the weekly matrix
  runs them all; a test that leans on an option the base can switch off is checked before the
  merge with `dev/varka_matrix.sh --module catalyst --config <option>=<other value>`.
- **The premise need not be a planted bug.** A test that reads a real refusal needs the option
  that makes the refusal; `VarkaFallbackReasonSuite`'s two day-producer tests and
  `VarkaProjectExecSuite`'s exact-status test (VARKA-313) leaned on `guardDayProducers`, which
  `guardDayProducers=false` removes (no mask refusal in the method, so the batch runs on the
  kernel and the status holds one bit), and failed under that configuration on master. They now
  force the option on; the sql module's matrix step is where such a premise shows, not the
  catalyst one. A test that waits on work another thread does after the job has failed (the
  reproduction file of VARKA-315) polls for it instead of listing a directory once (a leftover task of the previous test that
  claims the shape after the reset is not covered; it showed once in 300 back-to-back repeats).
- **Sweep, do not wait for the next failure.** This premise failed master in four rounds (the
  validity bitmap, exact grouping, the day-producer guard with the group budget, and
  `checkIntOverflow` for the two-refusal-reasons test). On 15 October 2026 the 23 suites that the
  previous two days' merges had added or changed (13 catalyst, 10 sql) ran over all 50
  configurations and the defaults, 1173 runs in about two hours on six JVMs at a time, and found
  no failure beyond the fourth. A new suite is swept before its merge:
  `dev/varka_matrix.sh --module <m> --suites <Suite> --all -j 4`.

## The emitter is called from several threads, so a test holds it to that

VARKA-307. `VarkaEmittedBytesSuite` hashes every shape twenty times, once per pinned arm and
width, and every hash is a function of the shape and the options alone, so the passes split by
shape over a pool and join in index order: the pinning test went from 2 minutes to 21 seconds on
the laptop, `emitted_bytes.json` unchanged, and `-Dvarka.bytes.threads=1` gives the serial suite.

The split rests on the emit path keeping no state between emissions: no static field that is not
final and no static collection filled after class initialization; the mutable statics in the
package are the runtime's (the shape cache, the compile directive, the warmup worker). That is
not only the suite's concern: the compiler emits at planning on the Spark driver, where
concurrent queries plan on several threads. So a test emits the coverage rows and two thousand
fuzz shapes on every thread at once and compares each with the shape emitted alone. A planted
static scratch list shared between emissions failed it at once, with another emission's outputs
in the shape; a test that never saw a race would say nothing about one.

Two things to keep when adding to the suite: a task must not wait on the pool it runs in (the map
is called from the test's thread only), and ScalaTest evaluates `assert`'s message even when the
assertion holds, so a message that indexes into an empty result throws on success.

## Error Prone and NullAway run beside the build, and the pending list only shrinks

VARKA-266. `dev/varka_errorprone.sh` runs Error Prone and NullAway as a javac plugin over Varka's
Java alone, against sbt's exported classpath, writing class files nothing reads; the gate's lint
step runs it, and since VARKA-382 so does every pull request's Build, after a finding merged
between two green pull requests while only the gate looked (`VARKA-382.md` 9.1). Four things
cost a run each to learn:

- Error Prone's with-dependencies jar does not bundle the Checker Framework dataflow its analyses
  load (`io.github.eisop:dataflow-errorprone`); without it the plugin dies on
  `NoClassDefFoundError` mid-run. NullAway refuses to start without `AnnotatedPackages`.
- The plugin needs ten `--add-exports` and two `--add-opens` of `jdk.compiler`, as `-J` flags,
  and they follow javac's internals: the script refuses any JDK but 25 by name.
- Under `-Werror` javac stops at the first warning, so to read every finding at once run without
  it. The incubating-module notice is a warning too (`-Xlint:-incubating`).
- A check that "a suppression has a comment above it" passes on the `*/` of the Javadoc above
  every annotated method; the reason has to be a `//` line comment.

NullAway is staged by class: the classes in `dev/varka_nullaway_pending.txt` are treated as
unannotated and every other Varka class is checked; a class leaves the list when its nullness is
stated (`javax.annotation.Nullable`, already on Spark's classpath) and the pass is green. The
script fails a list longer than the merge base's, or a new name that does not replace a removed
one (a rename keeps its place; a split does not), and a NullAway suppression over a whole class.
It also checks a class master has taken off the list since the branch began, so a branch behind
master fails before the merge does. An intended finding is suppressed at its site with a `//`
reason; no check is turned off for the whole pass.

## A Varka suite in JUnit 5: `@VarkaSuite`, a sentence per test, and ScalaTest's lines

VARKA-312. A catalyst suite can be a JUnit 5 class, and since then a new one whose helpers are
Java is one (`sql/varka/AGENTS.md`). What to know when writing and running one:

- **The class takes `@VarkaSuite`, each test a `@DisplayName` sentence.** The annotation registers
  `VarkaSuiteExtension`, the JUnit face of what `VarkaTestWatchdog` gives a ScalaTest suite: the
  matrix's skip list around each test, the watchdog inside it, the Arrow allocator check around
  the class. `@Tag(VarkaSuite.PINS_DEFAULTS)` is `PinsDefaults`; `@VarkaSuite(watchdogMinutes = 0)`
  runs with no watchdog and `@VarkaSuite(ownJvm = true)` is `VarkaOwnJvm`. The sentence is the
  test's name in the printed lines, the JUnit XML, `skips.tsv` and `fused.tsv`, and the suite is
  the class's simple name, so a skip-list line is written as for a ScalaTest test. The extension
  fails the class before its tests run (an aborted suite) when two tests share one name, as
  ScalaTest refuses them; when a test is not a plain `@Test` (`@ParameterizedTest`,
  `@RepeatedTest`, `@TestFactory`: JUnit runs those past the interceptor that applies the skip list
  and the watchdog, and names their invocations `[1] ...`); when the class holds `@Nested` classes;
  and when it takes `@VarkaSuite` from a superclass only, since `dev/varka_matrix.sh` finds a Java
  suite by the annotation in its own file.
- **An own-JVM class.** In the shared JVM each test takes the child's result as it is: the skip
  list, `PinsDefaults` and the watchdog act in the child only, a child's cancel keeps the child's
  reason, and a class-level abort in the child (or a non-zero exit with no failure in its report)
  fails the parent's tests. The child is limited to the class's `watchdogMinutes` less one, and
  not at all for `0`.
- **What prints the lines.** `VarkaSuiteListener`, registered in catalyst's test resources under
  `META-INF/services`, prints `- <sentence>`, `*** FAILED ***`, `!!! CANCELED !!!` and
  `!!! IGNORED !!!` for `@VarkaSuite` classes only and nothing for any other JUnit class. Under sbt
  `jupiter-interface`'s `Test <class>#<method>() started` lines stay beside them, and sbt's
  `Passed: Total N` summary replaces ScalaTest's. JUnit orders a class's tests by method name, not
  by declaration as ScalaTest does, so the lines come in that order.
- **How it runs outside sbt.** `dev/varka_matrix.sh` (so the gate and CI's scoped jobs) finds a
  `@VarkaSuite` class named `Varka...Suite` in `src/test/java` and starts `VarkaSuiteRunner`: it
  runs the ScalaTest suites through ScalaTest's Runner, then the JUnit classes through the Launcher,
  writes `TEST-<class>.xml` for both, and prints one `Suites:` and one `Tests:` line over both,
  last. The matrix's report reads the last of each, so a line printed after them would be read
  instead. A `-z <text>` or `-t <name>` after a `-s` filters that suite's tests, as ScalaTest's
  Runner does; for a JUnit class it is matched against the display names.
- **A cancel is thrown, not decided.** The extension cancels by throwing opentest4j's
  `TestAbortedException` from its interceptor; an `ExecutionCondition` would report the test
  disabled, which is ScalaTest's "ignored". JUnit's failure, `AssertionFailedError`, is an `Error`,
  so `VarkaMatrixRuns.Outcomes` takes factories of `Throwable`; Spark's checkstyle rejects
  `throw new ...Error(`, so test code fails with `Assertions.fail(...)`.
- **sbt runs nested classes alone.** It discovers every class with `@Test` methods, nested static
  ones too, and Spark's catalyst job runs them. `VarkaSuiteHarnessSuite`'s fixtures therefore pass
  when run alone and plant their failures only while a harness test runs them, through a Launcher
  of its own with the `META-INF/services` listeners off, so the fixtures' lines and counts do not
  reach the JVM's own.

## A Varka node in the plan is not a kernel evaluating the expression

*Added on 13 October 2026 from VARKA-138b (`m7/VARKA-138.md` 10.3 and 10.4).* A test that means
to check what a kernel does with an expression has to show the kernel evaluated it. Three weaker
signs each passed a check that compared Spark with itself:

- **A Varka node in the plan.** The node can decline every batch, at run time, to the row path.
- **`numVarkaBatches > 0`.** The rule splits a filter's conjuncts: in
  `WHERE rid < 0 AND (year(d)) IS NOT NULL` the Varka filter held `rid < 0`, served its batch, and a
  row `FilterExec` above it evaluated `isnotnull(year(d))` - Varka fuses no `IS NOT NULL` over a
  computed value. 61 of 65 coverage rows looked served that way. A projection's equivalent is an
  entry in `numResidualEntries`.
- **A guard over a cached fixture.** `WHERE rid < 0` over an Arrow-cached view lets the in-memory
  scan drop every batch on its column statistics before any Varka node runs, so the guarded side
  meets no kernel; set `spark.sql.inMemoryColumnarStorage.partitionPruning=false` on both sessions.

`VarkaCoverageDifferentialSuite.kernelServed` is the check: batches served, no residual projection
entry, no row `FilterExec` in the plan. And `EXPLAIN`'s first line above the Varka node says at a
glance whether Spark kept part of the query.

## A sql/core suite that collects dates makes its session active first

*Added on 16 October 2026 from VARKA-244 (`m7/VARKA-244.md` 9).* A DataFrame chooses its row
encoder from the *active* session's settings when it is built, not from the session that ran it,
and `VarkaSharedSessions` clears the active and default sessions when it builds `varkaSpark`. So
`spark.sql.datetime.java8API.enabled` set on `spark` and `varkaSpark` - once in `beforeAll`, or
around each query with `withBothConf` - is ignored, and `collect()` returns `java.sql.Date`, which
prints year 10000 as `0000` and year 0 as `0001` through the hybrid calendar: `checkAnswer` then
fails on rows that look equal. Set the queried session active around the query
(`SparkSession.setActiveSession`, restored after), as `VarkaStandardDeltasSuite.onSession` does,
before expecting `LocalDate`.

## A kernel failure leaves a file you can replay, and where each failure first surfaces

*Added on 14 October 2026 by VARKA-315.* A kernel class that fails to verify or define, an
exception inside a kernel and a memory-sanitizer violation each write a reproduction file
(`VarkaReproduction`), and `dev/varka_emit.sh --replay <file>` replays it to the same
`VarkaFailureSignature`. What the work found, which holds beyond the file:

- **The canonical IR is a complete serialization of a kernel.** The shape hash is SHA-256 over
  the outputs' canonical renderings, the counts and the options, and the cache serves one class
  per hash, so the rendering already carries everything the bytes depend on.
  `VarkaCanonicalIr.parse` reads it back and refuses any string its tree does not render to
  again. Two components are not rendered on purpose: `ConstDivide`'s dividend bound (no byte
  depends on it; the parser gives a long-lane division `EXACT_DIVIDEND_BOUND`, and the round-trip
  test compares bytes to hold it to that) and `BoundedDivide`'s pair (`BoundedDivide.of` derives
  it). A new node type fails that test until the parser has a case for it.
- **A class the verifier rejects fails in two places.** Under test the plan-time admission
  verifies the bytes it builds (`VarkaShapeCache.admit`, `verify = Utils.isTesting()`), and the
  `VerifyError` is not `NonFatal`, so it leaves `VarkaSizeAdmission` and the compiler's
  `catch (RuntimeException)` and fails the query on the driver. In production nothing is verified
  at planning; `getConstructor()` in the cache's `emit` links the class and the error reaches the
  evaluator's emission catch, an `emission-failure` fallback. A test of the production path cannot
  get there through a query; `VarkaReproductionSuite` drives the definition directly.
- **A sanitizer violation passes every catch.** `isCatchable` excludes it, so the runner rethrows
  it unwrapped and `serveBatch` rethrows it before building any record. The one place it can be
  written down is `runKernel`'s own `catch`, inside the window it came from.
- **Plant a failure the file can replay with a fault-injector option, not a hook.** A test hook
  (`setFailKernelForTesting`) is not in the shape key, so a file of its failure replays clean.
  `misdescribeAdd` (a link failure), `misdescribeDataBytes` (negative: an
  `IndexOutOfBoundsException` in the loop; positive: a violation under the sanitizer) and
  `dropStackMaps` (a `VerifyError`) ride the key.
- **Fill a replay's buffers before opening the sanitizer's window.** `VarkaSegments.map` is
  checked inside a window, so a harness that registers its buffers and then writes their contents
  through a mapping reports its own write as a violation.

## A guard's domain is tested on partitions Spark cuts, with the status read off the JFR event

*Added on 14 October 2026 from VARKA-279's second half (`m7/VARKA-279.md` 9.2).* Each coverage
row whose kernel can decline a batch names its domain in `coverage.json`, and
`VarkaCoverageDifferentialSuite` runs it under the domain, its negation and `IS NULL`, holding
the kernel's status to `VarkaGuardSpec`'s verdict on every batch. What it took:

- **Cut the partitions as cached views, not as `WHERE` clauses on the query.** A verdict is per
  batch, so a batch has to lie wholly inside or wholly outside the domain. A `WHERE` over the
  same scan does not give that: the kernel computes the guarded expression on the lanes the
  filter rejects too (the right side of an AND is not qualified by its left, `m9/SCOPE.md` item
  96), and a domain predicate Varka cannot fuse would leave the whole projection to Spark. A
  partition materialized once by Spark into its own cached view is what a kernel then reads,
  and the three views are checked to cut the fixture by `rid`.
- **Read a declined batch's status off the JFR fallback event.** Where the row path then raises,
  as it does outside most domains under ANSI, the task fails and its SQL metrics are never
  merged; the evaluator commits `VarkaFallbackEvent` with the status before the row path runs.
  `VarkaProjectExecSuite` read its decline that way; the pattern is now
  `VarkaJfrTestSupport.withJfrRecording` around the one Varka query. Set
  `spark.sql.codegen.varka.emit.exactRefusalStatus` on the session, so that a status is exactly
  the verdict's must bits and the comparison is an equality, not a subset test.
- **Materialize every view before recording.** A cached view built lazily inside the recording
  runs its own filter kernel, whose events then sit beside the query's.
- **Give the verdict the kernel's view of the rows.** Compile the node's own expressions, the
  optimized form, against the scan's pruned output under the session's emit options, and read
  the rows with `executeCollect` on exactly those columns - epoch days, month counts,
  nanoseconds - cut into batches of the session's Arrow batch size in the cached order. The
  evaluator's input bound is applied first, as the runner applies it: a live lane outside it
  declines with `STATUS_INPUT_BOUND` and the kernel's guards never run.
- **Some domains are total.** Four of the fifteen guards no value of the column type can trip:
  the time conversions' range guards, whose bound is the day the type already lies in, and the
  checked subtract after `extract(YEAR FROM ym)`, whose quotient is within +/-178956970. Their
  domains are still written, their outside partition is empty by construction, and a list with
  reasons polices the emptiness both ways: an unlisted empty partition fails, and a listed one
  with rows fails as stale.
- **Run the small Arrow batch sizes; one batch per partition hides a per-row disagreement.** A
  batch's verdict is the OR of its rows', so a row whose own verdict disagrees with the kernel
  passes as long as another row in the batch supplies the missing bit. The matrix's
  `arrowBatchSize=1` found what the defaults could not: above an overflowed FAIL negation the
  kernel's lane holds the wrapped value, stays valid, and the month count fires on it, while the
  reference evaluator has no value for the lane and the verdict said nothing. `VarkaGuardSpecs`
  now allows a guard above an overflowed FAIL node to fire (`overflowedBelow`), through the taken
  arm only. And under ANSI the first declined batch's row path raises and the task stops, so the
  events are a prefix of the per-batch verdicts; the check accepts the prefix only after a raise.

To date every guarded row answers inside its domain and where it is unknown with nothing
declined, and declines every batch outside with exactly the status the verdict calls for, in a
projection and in a filter, at one batch per partition and at one and three rows a batch.

## Spark's golden files under Varka: what the arm may touch, and what reaches a kernel

From VARKA-271 (13 October 2026, `VARKA-271.md` 9 and 10). The suite runs Spark's golden SQL
files with Varka on, through an Arrow cache, beside a cache-only arm, and compares the two.

* **The Varka arm must never write golden files.** With `SPARK_GENERATE_GOLDEN_FILES=1` set the
  parent suite rewrites every `.sql.out` from the answers it gets, which here are Varka's; the
  suite refuses to start with the variable set, shown by a run that aborted in 3 s with no file
  changed.
* **A planted fault that reaches nothing proves nothing.** Every `date_add` in the golden files
  is over literals with no `FROM`, so a misdescribed `date_add` failed no test of fourteen files;
  before the literal lift, the one fault the suite could be shown to reach was the one under
  which every emission raises. The lift (`VarkaArrowSource.LiftRule`) turns a `SELECT` with no
  `FROM` into one over a 17-row relation: equal values of different types are two columns, an
  entry stays only if every node still passes Spark's `checkInputDataTypes` (the
  constant-argument rules live there), and nothing under a `try_` function is lifted.
* **Served counts move run to run** (`int4.sql` served 23 and 24, `select_having.sql` 1 and 2,
  alone as in the suite), so `served.json` is a floor: a test that served a batch fails when it
  serves none, not when its count differs.
* **A nested deadline must reach the command.** `dev/varka_deadline.sh` ran its command in a
  session of its own, so an outer deadline's TERM stopped the script and left the 6 GB test JVM
  running; it now forwards TERM and INT to the command's group.

## Cost predictions run optimistic: the calibration table

*Added on 13 October 2026 from milestone 7's halfway review (`m7/PLAN.md` 9.2 and 9.5).* A plan
predicts what its own checks will cost before it builds them, and the predictions scored so far
in milestone 7 mostly came in low. One entry per scored cost prediction, so the next plan can state
its estimate at the ratio observed rather than at its hope; a ratio above 1 means it cost more than
predicted. Add the entry when the prediction is scored, whichever way it went.

Read the table with `dev/varka_calibration.py`, which prints it as Markdown: task (with the
prediction it scores), predicted, measured, ratio, note, in the order the entries were added. Each
entry is a file of its own under `sql/varka/skills/calibration/`, named after its task cell
(`VARKA-274a (6.1 #8)` is `VARKA-274a-6.1-8.md`), and the table is printed rather than committed
(VARKA-384): while it was a table here every task appended a row after the same last line, so each
merge conflicted with every open branch that had added one. To add an entry, paste the row you
would have added to `dev/varka_calibration.py --add-row '| VARKA-<n> (6.1 #k) | ... |'`, in single
quotes since rows hold backticks, which writes the file dated today (`--added YYYY-MM-DD` for
another day), or write the file by hand in the layout `dev/varka_calibration.py --help` shows;
`dev/varka_calibration.py --check`, which the pre-commit hook runs, rejects a malformed one.

What the table says so far: a check that adds a run per batch or a read per call costs more than
the plan's arithmetic, because the arithmetic counts the kernel's work and not the harness's
(loading, copying, reading the JIT's state); predict that overhead from a probe, not from the
kernel's share.


## A forked JVM proves its own tier, and the default JVM's expectations are not that proof

From VARKA-272 (14 October 2026), which forks the IR fuzzer's compiled test under other JVM flags
(`dev/varka_platform.sh`).

- The compiled histories' default rules (VARKA-274a) say what the default JVM shows: `cold`
  interpreted, a settled warm-up all C2, `released` entered by an OSR at level 3 or 4. Under
  `-XX:TieredStopAtLevel=1` the released OSR is at level 1, which the report's kinds do not name
  ("some mixed"), and under `-Xcomp` nothing is ever interpreted: a `cold` call is compiled code
  that traps at once on a class the compile found unloaded (`unloaded`, reinterpret), and no loop
  is entered by an OSR. Both arms failed `-Dvarka.fuzz.requireTiers=true` for the wrong reason
  until each tier got rules of its own (`VarkaCompiledCheck.Tier`, `-Dvarka.fuzz.tier`), and the
  JVM's own flags are read back first (`VarkaPlatformFacts`), so an arm whose command line lost a
  flag fails as "not the tier it claims", never as a kernel's answer.
- A rule that asks for "no settled case that is not all C2" passes a lane where nothing settled:
  the intrinsics-off arm's int lane read "0 history(ies) not reached" with every warm-up at level
  0 at its deadline. A lane with warmed runs and no settled one has not reached the history, and
  the rule says so now.
- `EnableVectorSupport` reads `false {default}` under `-XX:+PrintFlagsFinal` on every JDK, with
  and without `--add-modules jdk.incubator.vector`: `modules.cpp` turns it on when the module is
  defined, after the flags print. Read it from inside through `HotSpotDiagnosticMXBean`'s
  `getVMOption`, which needs `-XX:+UnlockExperimentalVMOptions` (locked, the flag "does not
  exist"), or off `-Xlog:compilation=info`'s startup line.
- A crashed JVM keeps JFR's emergency dump `hs_err_pid<pid>.jfr` in its working directory
  whenever a recording is active, the tier witness's stream included; a file recording's
  `dumponexit` writes nothing on a crash; a replay file (`DumpReplayDataOnError`, on by default)
  appears only when a compiler thread crashed. So an arm runs with its directory as the working
  directory and `-XX:ErrorFile` and `-XX:ReplayDataFile` pointed there with `%p`.
- Never recompile the test classes while forked JVMs run on them: a `NoClassDefFoundError` for
  `VarkaKernelChecks$LongBatch` in a stress arm's `cold` history read as a finding for ten
  minutes, and was sbt rewriting the class files under the running JVM.
- A tier proof that asks for a JIT outcome from one draw is a flaky test on a loaded runner: C1's
  `released` history was not reached in two runs of three at one compiled draw a lane on two
  shared cores, from a body with no loop method or a starved C1 queue. Smoke only the arm whose
  proof is a negative (`-Xint`: nothing compiles) and ask the others for a draw count.
