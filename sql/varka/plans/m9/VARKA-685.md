# VARKA-685: A kernel for each batch

*Planned on 26 November 2026. This plan stands alone: a builder needs this file and the
repository's `AGENTS.md` files, nothing else. Not started: milestone 9 waits for the owner's
word (`m9/PLAN.md` decision 18). The code facts below were checked on master `176db41f369`.*

## 1. What this row is

Row 685 of `sql/varka/plans/m9/PLAN.md`: "the evaluator owns a list of kernels by form, a
refactoring that moves no emitted byte; then the ladder, the least form that covers a batch's
range; last, `second` and `minute` with no offset, if row 691's numbers show a gain. **Done
when** a year of instants in no order under `America/Los_Angeles` declines no batch, each batch
runs the least form that covers it, and no batch runs twice."

Before this row a zoned kernel has one form for the task, two steps by default: a batch that
spans three changes of offset goes to Spark's row path, and a batch that spans none still pays
for two steps. After it the evaluator holds one kernel for each form a batch has needed, and
each batch runs the cheapest form that covers it.

What it gives the rows after it: row 686 adds the form `TABLE` as one more entry of
`VarkaZoneLadder.RUNGS`; rows 687 and 688 fill `VarkaBatchRanges` from the cache's statistics
and for a computed operand; row 689 lifts this row's cap at two lanes (4.4); row 691 measures
each form in a kernel.

What must be merged before it starts: row 657's parts A to D (the zoned kernels, their suites
and coverage rows), and so rows 682, 683, 656, 678 and 655. Part e waits for row 691's results.

The goals it serves (`m9/PLAN.md` 1.1), each with the item of 1.2 that it moves:

- G7, item 10 (every `TIMESTAMP_LTZ` section whose kernel reads the zone at least five times
  the row path, none slower than it): a batch that spans three to eight changes runs a kernel
  where it took the row path, and one that spans none runs one offset. Row 691 reads the bar.
- G2, item 2 (over ten years of instants in no order the kernels decline no batch): only
  through row 686. After this row ten years still decline: twenty changes, eight steps.
- G1, G3, G4, G5, G6: not served. The row fuses no entry, and it is built at full strength.

Said plainly: the done-when's first item holds before this row, since a year under
`America/Los_Angeles` has two changes and row 682's default, `STEPS_2`, covers them. The
row's own gain is the batch with no change and the batch with three to eight.

## 2. What an agent must know

**The idea.** A `TIMESTAMP` is a count of microseconds since the epoch in UTC, in a long. Local
time is that count plus the zone's offset, and the offset changes at a few instants (a
daylight-saving step, a law). A zoned kernel adds one offset to every row and then, for each
change the batch spans, one difference to the rows at or after the change. The offset and the
changes are not in the kernel's bytes: before each call the runner writes them into the
kernel's `long[] longArgs`, after the literals. A pass over the column finds its least and
greatest live value, and so the changes the batch spans.

A kernel is emitted for a fixed number of steps, its **form**. A kernel of two steps cannot
serve a batch that spans three changes; one of eight steps can, but it costs more: at four
64-bit lanes on the laptop the probe measured 0.077 ns a row for one offset, 0.148 for two
steps and 0.755 for eight (`sql/varka/spikes/zone-offset/results/forms.txt`).

This row makes the form a choice for each batch. The evaluator measures the batch's range
once, asks the zone's data how many changes lie in it, and runs the kernel of the least form
that covers that count. The count decides, so the choice is exact: nothing is timed, and no
batch runs twice.

**Terms.**

- *Instant*: microseconds since the epoch in UTC. *Change*: an instant at which a zone's offset
  moves. *Fixed zone*: a zone with one offset for ever; the compiler folds it and builds no node.
- *Zone node*: the IR record `ToLocal(instant, zone)`; `zone` is an index into the compiled
  projection's list of zone ids. *Batch argument*: an entry of `longArgs` that the runner
  writes before each call. *Program*: one zone node's run of batch arguments.
- *Form*: the value of the emit option `zoneForm`: `ONE` has no step, `STEPS_2` two, `STEPS_8`
  eight. *Rung*: a form the ladder may take. *Ladder*: the value `LADDER`: the evaluator takes
  a rung for each batch. *Forced form*: any other value; a matrix arm or a test sets it.
- *Need* of a batch: the greatest count of changes over the kernel's programs.
- *Kernel*: one emitted class and its runner. *Evaluator*: `VarkaEvaluatorBase`, the object of
  one task that serves the batches of one projection or filter. *Row path*: Spark's own
  evaluation, row by row. *A batch declines*: the evaluator gives it to the row path and
  counts the reason.
- *Shape key*: `VarkaShapeKey(outputs, numInputs, numLiterals, options, warmed)`, the key of
  the JVM-wide cache of kernel classes. The options are part of it, so each form is a class.
- *Warm-up*: with `spark.sql.codegen.varka.warmup.enabled`, a new kernel runs on a copy of a
  batch on another thread until C2 has compiled it; meanwhile its batches take the row path.

**The names.** "Reads" is on master or comes from row 682, 683 or 657; "adds" is this row's.

| Name | Reads or adds | Meaning |
|---|---|---|
| `VarkaEmitOptions.ZoneForm { ONE(0), STEPS_2(2), STEPS_8(8) }`, `steps()`, `withZoneForm` | reads (682) | the forms; the default is `STEPS_2` until part c |
| `ZoneForm.LADDER` | adds | no form of a kernel: `steps()` throws on it |
| `VarkaZoneLadder` (`RUNGS`, `ADMISSION`, `emitted`, `widest`, `rung`, `LADDER_REASON`) | adds, catalyst main | the rungs in order and the three rules of 4.3 and 4.4 |
| `VarkaBatchArgs.layout(outputs, numLiterals, options)`, `Program(node, first, count)`, `operandColumn(node)`, `zonesNeeded(outputs)`, `neutral(program, args)`, `MAX_OFFSET_MICROS` | reads (682) | the batch arguments of a kernel; one program a distinct zone node |
| `VarkaZoneData.changes(least, greatest)` | reads (682) | the changes `c` with `least < c <= greatest` |
| `VarkaZoneData.toLocalArgs(least, greatest, steps, args, first)` | reads (682) | writes the offset at `least`, the steps, then padding; throws when the changes exceed `steps` |
| `VarkaEvaluatorBase.zoneData()` | reads (682, 657) | the provider of zone data on the executor |
| `LongRangeOps.leastAndGreatest(srcData, srcValidity, srcNullCount, length, out)` | reads (683) | the least and greatest live value; false when no row is live |
| `VarkaKernelRunner.STATUS_BATCH_ARGS`, reason `batch-args` | reads (682) | the decline of a batch whose arguments cannot be filled |
| `VarkaKernelRunner.BatchProgram(node, input, zone, steps, first)` | reads (682) | a program as the runner holds it |
| `VarkaBatchRanges` | adds, sql/core | the range of each program's operand for the current batch |
| `VarkaEvaluatorBase.Kernel` | adds, private | one kernel of the evaluator: its options, runner, gate, names |
| `VarkaExecMetrics.servedByZoneForm`, key `numZoneFormBatches[<form>]` | adds | batches served, by the form of the kernel that served them |
| `VarkaZoneLadderSuite`; `VarkaZoneLadderBenchmark` | adds: catalyst, Java, JUnit 5; sql/core, Java | the rules of `VarkaZoneLadder`; the ladder against each forced form |

## 3. The admission check

The paths `V`, `C`, `E`, `TJ`, `TS`, `XS` and `XJ` are the shell variables of section 5. Every
fact was read in `/home/max/proj/varka-657-plan-w`, whose main sources equal the base's.

1. **The evaluator holds one kernel, in six fields.** `VarkaEvaluatorBase` has
   `runnerResolved`, `runner`, `warmupGate`, `kernelIdentity`, `kernelShapeHash` and
   `renderedOptions`. `fusedRunner()` builds the runner once through `newRunner(compiled)` and
   answers every failure; `shapeKey(compiled)` reads `emitOptions()` and `warmed()`.
   Command: `grep -c 'private boolean runnerResolved;' $E/VarkaEvaluatorBase.java` prints 1.
2. **A batch meets the gate before the fill.** `serveBatch` calls `canRun` (which forces
   `fusedRunner()`), then `kernelReady`, then the caller's kernel path. The fill is inside that
   path: `runKernel` calls `kernel.fill(input, len)` and then `kernel.invoke(len)`.
   Command: `grep -c 'kernel.fill(input, len);' $E/VarkaEvaluatorBase.java` prints 1.
3. **Three sites call `fill`:** `runKernel`, `VarkaFilterEvaluator.filterMask` and
   `VarkaWarmupGate.startWarmup`.
   Command: `grep -c 'runner.fill(input, len);' $E/varka/VarkaWarmupGate.java` prints 1.
4. **A projection can hold several kernels.** `VarkaKernelPart extends VarkaEvaluatorBase` is
   one further kernel; `VarkaKernelEvaluator` overrides `canRun`, `kernelReady` and
   `emissionFailed` to ask its parts. Only `kernelReady` asks every part; the other two stop
   at the first that answers. So the list of kernels by form belongs in `VarkaEvaluatorBase`,
   and each part gets its own.
   Command: `grep -c 'all &= part.kernelReady(input);' $E/VarkaKernelEvaluator.java` prints 1.
5. **Tests call the kernel path with no `serveBatch`.** `VarkaKernelEvaluatorSuite` calls
   `kernels.project(input)` after `canRun` alone. So a step that must run once a batch cannot
   live in `serveBatch` only.
   Command: `grep -c 'kernels.project(input)' $XS/VarkaKernelEvaluatorSuite.scala` prints 10.
6. **The warm-up is per shape.** `VarkaWarmupGate.kernelReady` claims a cold shape
   (`VarkaKernelWarmth.tryClaim`), calls `runner.fill`, and queues `VarkaKernelWarmup.start`,
   which clones `longArgs`. The warmth is a component of `VarkaShapeEntry`, keyed by the shape
   key. A warmed kernel has a class name of its own (`VarkaShapeKey.warmed`), which
   `VarkaKernelCompileDirective` matches to keep C1 off it. A shape with no verdict after
   `DEADLINE_SECONDS`, 60, is released. So a second form of one projection is a second shape,
   with its own class, warmth and warm-up, and no code change.
   Command: `grep -c 'warmth.tryClaim()' $E/varka/VarkaWarmupGate.java` prints 1.
7. **The class cache holds 100 shapes by default** and releases the least recently used
   (`StaticSQLConf.VARKA_CACHE_MAX_ENTRIES`). An entry keeps the class, its bytes and its loader.
8. **Emitting and defining a class costs under a tenth of a millisecond on the laptop:** 71842.8
   ns for `year(d)` (`sql/catalyst/benchmarks/VarkaEmissionBenchmark-jdk25-results.txt`).
9. **Suites and tools hand the default options straight to the emitter.** 84 test files of
   catalyst call `VarkaLoopEmitter` directly, and the suites that emit every coverage row do
   it under `VarkaEmitOptions.DEFAULTS` (`VarkaCoverageSuite`, `VarkaEmittedBytesSuite`,
   `VarkaWidthAuditProbe`, `VarkaWarmupReach`, `VarkaGroupingBoundSuite`). The compiler does
   the same at plan time: `VarkaExpressionCompiler` calls `VarkaLoopEmitter.fitsBudgets` with
   the session's options at three sites. So an emission under the default needs an answer (4.3).
   Command: `grep -rl 'VarkaLoopEmitter\.' sql/catalyst/src/test | wc -l` prints 84.
10. **An option's arms are its constants.** `VarkaEmitOption.Choice.arms()` gives one arm a
    constant; `VarkaMatrixRuns.configurations()` drops the arm that equals the defaults, and
    runs an option that one node kind reads only if that kind is in `COMPILED_NODE_KINDS`
    (row 657 adds `Zoned`). `grep -c 'COMPILED_NODE_KINDS' $TJ/VarkaMatrixRuns.java` prints 3.
11. **The matrix's reach record hears every class looked up.** `VarkaShapeCacheImpl` calls
    `VarkaCompileObserver.emitted` with the key's options, and a configuration reruns a test
    where its values move what the test looked up (`VarkaTestReach`).
    Command: `grep -c 'VarkaCompileObserver.emitted' $V/VarkaShapeCacheImpl.java` prints 1.
12. **Counts by name are a list of metrics.** `VarkaExecMetrics.declinedByReason` is parallel
    to `VarkaFallbackAccounting.REASONS`; `nodeMetrics` registers one metric a reason under
    `numDeclinedBatches[<reason>]`, and `fromNode` requires each key. The record is built at
    three sites, all in its own file.
    Command: `grep -c 'new VarkaExecMetrics(' $E/VarkaExecMetrics.java` prints 3.
13. **At two lanes eight masked steps are slower than the row path.** At two 64-bit lanes the
    probe measured 62.542 ns a row for eight steps and 12.070 to 13.599 for two, where
    `java.time` reads the hour of the day in 26.147 (`results/forms.txt`, the section of
    `-XX:MaxVectorSize=16`). The session carries the host's reading as
    `VarkaSessionEmit.declineTwoLaneConditions`.
    Command: `grep -c 'boolean declineTwoLaneConditions,' $E/VarkaSessionEmit.java` prints 1.

**Not checked.** The code of rows 682, 683, 657 and 678 is not built: their names are from
`VARKA-682.md`, `VARKA-683.md` and `VARKA-657-PARTS.md`, and each sheet's B0 greps them. The
helpers of their suites (`VarkaBatchArgRunnerSuite`, `VarkaTimestampZoneSuite`,
`VarkaTimestampZoneQuerySuite`) are known only from those plans. What `changes` costs in row
678's data and what a zoned kernel costs to emit are not measured. Whether row 689 merges
before this row is not known; part c's B0 asks.

## 4. The design

### 4.1 The kernels of an evaluator (part a)

`VarkaEvaluatorBase` gains a private nested class. It is a class and not a record because its
state changes:

    private static final class Kernel {
      final VarkaEmitOptions options;       // what this kernel is emitted with
      boolean resolved;                     // runnerOf has answered, with a runner or with null
      @Nullable VarkaKernelRunner runner;
      @Nullable VarkaWarmupGate gate;
      @Nullable String identity;            // memoized, as kernelIdentity is today
      @Nullable String shapeHash;
      @Nullable String renderedOptions;
    }

The six fields of fact 1 go. In their place: `private Kernel[] kernels` and `private Kernel
current`. After part a the array has one element, built in the constructor from `emitOptions`,
and `current` is that element for the whole task. Every method that read a field reads
`current`:

| Today | After part a |
|---|---|
| `fusedRunner()` | returns `runnerOf(current)`; `runnerOf(Kernel)` is the old body over the kernel's fields |
| `newRunner(compiled)` | `newRunner(compiled, kernel)`: the shape key and `compiled.lane(...)` take `kernel.options`, and so does row 682's call of `VarkaBatchArgs.layout` |
| `shapeKey(compiled)` | stays, and returns `shapeKey(compiled, current.options)`; the new private overload builds the key |
| `kernelIdentity()`, `kernelShapeHash()`, `renderedOptions()` | return `identityOf(current)`, `shapeHashOf(current)`, `renderedOf(current)`, each memoized in the kernel |
| `kernelReady(input)` | the gate is `current.gate`, built from `runnerOf(current)` |
| `serveBatch`, the row-path failure's context | reads `current.runner` |

`emitOptions()`, `warmed()`, the scratch, the accounting, the ledger and the plan stay the
evaluator's: they do not differ by form.

**How no emitted byte is shown to move.** The part edits one main file, in `sql/core`. A diff
of `sql/catalyst/src/main` against the merge base prints nothing, so
`sql/varka/emitted_bytes.json` cannot differ. Two new tests hold what that file cannot see:
the evaluator's class is the cache's class for the same key, and a task looks it up once.

### 4.2 The range pass, once a batch (part b)

Row 682 put the pass inside `VarkaKernelRunner.fill`, per program. With several kernels the
pass must run before the form is chosen, and once, whatever the number of forms. So it moves
out of the runner into a new class of the evaluator, `E/varka/VarkaBatchRanges.java`:

    public final class VarkaBatchRanges {
      public static final int NOT_COVERED = Integer.MAX_VALUE;
      public VarkaBatchRanges(int[] input, VarkaZoneData[] zone)
      public int programs()
      public int measure(ColumnarBatch batch, int len, int[] inputOrdinals)
      public boolean live(int program); public long least(int program); public long greatest(int program)
    }

- `input[p]` is the kernel input that program `p` reads (`VarkaBatchArgs.operandColumn`) and
  `zone[p]` its zone data, in the order of `VarkaBatchArgs.layout`. That order does not depend
  on the form, so one `VarkaBatchRanges` serves every kernel of the evaluator.
- `measure` runs `LongRangeOps.leastAndGreatest` over each program's column and keeps the
  result in arrays that it owns. It returns the batch's **need**: the greatest
  `zone[p].changes(least, greatest)` over the live programs, 0 when no program has a live row,
  and `NOT_COVERED` when a live value is below `Long.MIN_VALUE + MAX_OFFSET_MICROS` or above
  `Long.MAX_VALUE - MAX_OFFSET_MICROS`. It allocates nothing.
- Under the memory sanitizer `measure` opens a window (`VarkaMemorySanitizer.begin`), registers
  each column's data and validity buffer as `VarkaKernelRunner.fill` does, and closes the
  window in a `finally`. Windows nest, so a call inside `runKernel`'s window is safe.
- `VarkaKernelRunner` takes the ranges in its constructor. In `fill`, row 682's three steps of
  a program become one: `ranges.live(p)` false writes `VarkaBatchArgs.neutral`; else
  `zone.toLocalArgs(ranges.least(p), ranges.greatest(p), steps, longArgs, first)`, which still
  throws when the changes exceed the steps. The runner's `long[2]` field goes.

The evaluator measures in a new private method, `prepare(input)`. Its first statement is
`fusedRunner()`, which builds the ranges with the first runner. Without it a `kernelReady`
asked before any `canRun` would set `prepared` with nothing measured, and the kernel call
after it would fill neutral arguments: UTC's answers under a zone. In part b `prepare`
declines the batch (`VarkaBatchDeclined(STATUS_BATCH_ARGS)`) when the need is more than its
one kernel's steps. An evaluator with no zone program has no ranges and measures nothing.

**Once a batch, for every caller.** `kernelReady(input)` calls `prepare` first, before it asks
whether the warm-up is on, and sets a field `prepared`; the warm-up's `runner.fill` then reads
this batch's ranges. The kernel path takes its runner from a new method:

    protected final VarkaKernelRunner batchRunner(ColumnarBatch input) {
      fusedRunner();
      if (!prepared) {
        prepare(input);
      }
      prepared = false;
      return fusedRunner();
    }

`runKernel` and `VarkaFilterEvaluator.filterMask` call it where they called `fusedRunner()`. A
caller that came through `serveBatch` finds the batch prepared; a test that calls `project`
alone (fact 5) prepares here. `serveBatch` ends in a `finally` that calls `discardPrepared()`,
so a batch that was prepared and then took the row path leaves nothing for the next one.
`VarkaKernelEvaluator` overrides `discardPrepared` to reach its parts.

### 4.3 The option (part c)

- **Declared.** `ZoneForm` gains a last constant, `LADDER(-1)`. `steps()` throws
  `IllegalStateException(VarkaZoneLadder.LADDER_REASON)` on it: "zoneForm=LADDER is no form of
  a kernel: an evaluator takes a rung for each batch, and an emission that names none takes
  VarkaZoneLadder.ADMISSION (VARKA-685)". `VarkaEmitOptions.DEFAULTS` and the `zoneForm` entry
  of `VarkaEmitOption.TABLE` take `ZoneForm.LADDER` as the default.
- **Resolved by the evaluator.** Under `LADDER`, an evaluator whose plan holds a zone node has
  one `Kernel` a rung, each with `emitOptions.withZoneForm(rung)`. Every shape key and layout
  it asks for carries a rung. No lowering reads `LADDER`, and a kernel has exactly one form.
- **An emission under the default, with no evaluator** (fact 9): the compiler's admission at
  plan time, the suites that emit coverage rows, `dev/varka_emit.sh`. Each reader of the form
  in the emitter calls `VarkaZoneLadder.emitted(options)`: the options' own form, and for
  `LADDER` the constant `ADMISSION`, `STEPS_8`, the rung with the most lane calls. It is one
  rule in one method, not a choice the emitter makes.
- **Admission and grouping.** On the base `VarkaLoopEmitter.fitsBudgets` reads heights,
  columns, the node count and the plan's refusal, and no weight: its answer is the same for
  every rung. Grouping differs: the weight that packs outputs into methods is
  `VarkaEmitBudget.weightOf(node, options, narrow)`, by row 682 the lane calls of
  `emitted(options)`, so 1, 5 or 17 for a rung's kernel, against `GROUP_BUDGET`, 16. A lighter
  rung packs more outputs into one method than the admission form did, and nothing proves
  that method within the emitter's limits. A test holds it: every rung emits every zoned
  coverage row and a projection of sixteen zoned outputs (5.3).
- **A shape with no zone node** never reads the form. Its key under the defaults renders as it
  did (`zoneForm` shows in `canonical()` only off its default), so its class name and bytes
  do not move. **`ToLocal.props()`** takes the admission form's weight, 17 (`1 + 2 * 8`);
  only a reader with no options sees it (`weightOf(node)`, the budget suite's register).

`VarkaZoneLadder`, new, `V/VarkaZoneLadder.java`, a final class of statics:

    public static final List<ZoneForm> RUNGS = List.of(ZoneForm.ONE, ZoneForm.STEPS_2, ZoneForm.STEPS_8);
    public static final ZoneForm ADMISSION = ZoneForm.STEPS_8;
    public static ZoneForm emitted(VarkaEmitOptions options)
    public static int widest(boolean maskedTwoLanes)
    public static int rung(int changes, int widest)

`widest(maskedTwoLanes)` is the index in `RUNGS` of the last rung a ladder takes: 1
(`STEPS_2`) when `maskedTwoLanes`, else `RUNGS.size() - 1`. `rung(changes, widest)` is the
least index `i <= widest` whose rung has `steps() >= changes`, or -1; it reads a private
`int[]` of the rungs' steps, with no allocation and no boxing.

### 4.4 The choice (part c)

In part c `prepare(input)` becomes, for an evaluator with zone programs:

1. `need = ranges.measure(input, len, inputOrdinals)`.
2. Under a forced form: `need > steps` declines with `STATUS_BATCH_ARGS`, as in part b.
3. Under the ladder: `r = VarkaZoneLadder.rung(need, widest)`. If `r < 0` the batch declines
   with `STATUS_BATCH_ARGS` and `current` stays. Else `current = kernels[r]`, and
   `runnerOf(current)` builds its runner if this is the first batch that needs it.

`widest` is computed once, when the plan resolves, from
`sessionEmit.declineTwoLaneConditions()`: the host's reading that row 503's refusal uses, true
where 64-bit masks at two lanes fall back to Java. By fact 13 eight masked steps are then
slower than the row path and two are faster, so the ladder stops at `STEPS_2` and a batch that
needs more takes the row path, as G7 asks. A test that must reach `STEPS_8` sets that conf
to false (5.3). Row 689 gives the steps a form with no mask; once its option `zoneStepMask`
exists the argument is `declineTwoLaneConditions() && emitOptions.zoneStepMask()`.

The choice adds no pass and no call: the pass and `changes` move from row 682's fill to
`prepare`. What is new is one compare a rung and one array read.

### 4.5 A form compiled in the middle of a task (part c)

- **The first rung is built with the task.** `canRun` forces `fusedRunner()` as today, and
  `current` starts as the kernel of `ONE`. So the first batch emits `ONE`, which also builds
  the ranges, and an emission failure on it is met where it is met today.
- **With the warm-up off:** the first batch that needs a rung emits and defines its class on
  the task thread, inside `prepare`, and runs it at once. The delay is one emission (fact 8).
- **With the warm-up on,** no batch waits. `kernelReady` asks the gate of the least rung. A
  cold rung is claimed there and its warm-up starts on a copy of this batch, filled in that
  rung's form. While it warms, the batch takes the least wider rung whose runner this task has
  built and whose warmth is ready; with none, it takes the row path and counts as a warm-up
  batch, as any batch of a cold kernel does today. A wider rung is exact for the batch, so
  this is no second run and no fallback. Without it the ladder would be slower than the forced
  `STEPS_2` it replaces: a task whose first batches need two steps would send every later
  batch that needs none to the row path until `ONE` has its verdict.
- **Two tasks of one projection** share the classes and the warmth through the shape cache, and
  nothing else: each has its own `Kernel` array, runners, argument arrays and ranges.
- **An emission failure of a later rung** is counted and logged once, by `runnerOf`'s catches.
  That batch and every later batch of the task take the row path: `serveBatch` asks
  `emissionFailed()` after `kernelReady`, and `canRun` then finds no runner.
- **What a task that sees every form costs.** Three classes for one zoned kernel where it had
  one: three of the cache's 100 entries (fact 7), shared by every task of the JVM. For the
  task, one kernel instance and one set of argument arrays a rung: seven arrays as long as
  the kernel's inputs or outputs, and a `longArgs` of the literals plus 1, 5 or 17 entries a
  program. With the warm-up on, up to three jobs on the warm-up's thread. Row 686 makes it four.

### 4.6 A forced form

`zoneForm=ONE`, `STEPS_2` or `STEPS_8` is one kernel, as before this row. A batch whose need
is more than its steps takes the row path with `STATUS_BATCH_ARGS`; the evaluator takes no
other form for it. That is what a matrix arm checks: each form alone, on every batch it covers.

### 4.7 Counters

- **Which form served a batch:** `VarkaExecMetrics` gains `List<SQLMetric> servedByZoneForm`,
  parallel to `VarkaZoneLadder.RUNGS`, registered by `nodeMetrics` on every Varka node under
  `numZoneFormBatches[ONE]`, `numZoneFormBatches[STEPS_2]` and `numZoneFormBatches[STEPS_8]`.
  `serveBatch` adds one after the kernel path has returned a result, for each kernel of the
  projection that holds a zone program, under the form of the kernel that ran. A forced form
  counts under its own name; a kernel with no zone program counts nowhere.
- **The fallback reasons** stay where the house keeps them: a batch that no rung covers, one
  that a forced form does not cover and one within 18 hours of a long's end all count under
  `numFallbackBatchesDeclined` and `numDeclinedBatches[batch-args]`; a batch that waited for a
  warm-up under `numWarmupBatches`. `numVarkaCacheHits` and `numVarkaCacheMisses` count one
  lookup a kernel, so a task adds up to three for a zoned kernel; the documentation says so.

### 4.8 The matrix, the pins and the skip list

- **The matrix.** The arms that differ from the default are now `zoneForm=ONE`,
  `zoneForm=STEPS_2` and `zoneForm=STEPS_8`: one configuration more, the old default.
- **Reach.** The house rule sends an option that an evaluator reads as it runs to
  `VarkaTestReach.FULL`, and the evaluator reads `zoneForm` to choose between the ladder and
  a forced decline. So `FULL` gains `zoneForm`, as it holds `exactRefusalStatus`, and each of
  its three configurations runs every test. The record hears every lookup (fact 11), which
  may make the entry more than is needed: lifting it is the owner's decision (risk 6).
- **The pins.** `emitted_bytes.json` and `width_audit.json` pin the coverage rows under the
  defaults. A zoned row's digests move once, from two steps to the admission form's eight; no
  other row's move. `coverage.json` and the generated table of `docs/sql-varka.md` move by
  the note of 5.3 step 9. `pinnedArms` gains nothing.
- **The skip list.** Row 657's lines under `zoneForm=ONE` stay true; this row's tests set the
  form they test, the ladder included, and need no line (5.3 step 12).

### 4.9 Edge cases, and what declines

- **Null rows** are skipped by the pass (row 683); a null row's lane gets a value that the
  output's validity hides. **A batch with no live row** needs 0: `ONE`, neutral arguments.
- **The ends of a long:** `NOT_COVERED`, the row path, in every form. **A range that ends on a
  change** counts it, and one that starts on it does not: `changes` is row 682's.
- **Two lanes of 64 bits:** the ladder ends at `STEPS_2` (4.4). **AVX2 against AVX-512:** the
  same choice; the forms' bytes are row 682's.
- **A fixed zone** has no node, no program and no ranges: one kernel, no pass. **Two zone
  nodes in one kernel:** one form, the greatest need; the other program is padded.
- **Declines.** The row adds no decline at compile time and no status. At run time a batch
  declines with `STATUS_BATCH_ARGS`, which `docs/sql-varka.md` words as "more changes of offset
  in the batch's range than the widest form has steps, or values within 18 hours of the ends
  of the range of a long".

### 4.10 The fields that need no offset (part e, conditional)

`second(ts)` reads the same value from the instant wherever every offset is a whole minute,
and `minute(ts)` wherever each is a whole hour. Under the ladder that has an exact form with
no new kernel: such a batch needs no step, so it runs `ONE`.

- `ToLocal` gains a component, `long grain`, in microseconds; 0 is the full conversion. A node
  of grain `g > 0` is the instant plus `floorMod(offset, g)`. That is a function of the row;
  it is the instant where every offset is a multiple of `g`; and its `second` (a grain of a
  minute) and `minute` (a grain of an hour) are the local time's everywhere, the odd offsets
  before 1972 included.
- The fill of a grained program reduces what the zone data gives: each offset modulo the
  grain, the steps whose reduced difference is 0 dropped. The program's need is the count of
  steps left. The kernel's bytes do not change.
- The compiler builds `second` over a node of grain `MICROS_PER_MINUTE` and `minute` over one
  of `MICROS_PER_HOUR`. That alone would split the one `ToLocal` that `hour(ts)`, `minute(ts)`
  and `second(ts)` share. So a pass over the kernel's final outputs gives every node over one
  operand and zone the coarsest grain among them, 0 if any is full. The gain is then only for
  a kernel whose every reader of a timestamp is `second` or `minute`.

**The gate.** Part e is built only if row 691's results show, at 256 or at 128 bits on the
AMD EPYC 7763, that the section `second(ts)` under `America/Los_Angeles` over a year in no
order costs at least a tenth more a row than the same section over a week, by the minimums of
two runs. The week's section runs `ONE`, which is what part e would give the year's.

## 5. The parts

Four pull requests, and a fifth that the gate decides. Each sheet follows
`sql/varka/plans/m8/BUILD_SHEETS.md`. Part a's last step makes row 685's line in
`sql/varka/plans/m9/PLAN.md` read `**Started**` for `**Planned**`, part d's `**Done**`. No
branch is named `varka-685`, which held this plan. Every shell starts:

    export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
    V=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    C=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen
    E=sql/core/src/main/java/org/apache/spark/sql/execution
    TJ=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    TS=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka
    XS=sql/core/src/test/scala/org/apache/spark/sql/execution
    XJ=sql/core/src/test/java/org/apache/spark/sql/execution

| Part | Title | Branch | Counted lines, estimate |
|---|---|---|---:|
| a | `[VARKA-685] Hold the evaluator's kernel in a list of kernels by form` | `varka-685a-kernels` | 230 |
| b | `[VARKA-685] Measure a batch's zone ranges once, before the kernel's fill` | `varka-685b-ranges` | 380 |
| c | `[VARKA-685] Run each batch on the least zone form that covers its range` | `varka-685c-ladder` | 760 |
| d | `[VARKA-685] Measure the ladder against each forced form` | `varka-685d-measure` | 260 |
| e | `[VARKA-685] Read second and minute with no offset where the zone allows` | `varka-685e-grain` | 560 |

A ScalaTest run passes with `failed 0` and no `*** FAILED ***`; a JUnit class prints sbt's
`Passed: Total N, Failed 0, Errors 0, Passed N`.

### 5.1 Part a: the list of kernels

**B0, the premises.** Each prints the number shown, or stop.

    git branch --list 'varka-685a-kernels' | wc -l                                             # 0
    grep -c 'private boolean runnerResolved;\|private VarkaKernelRunner runner;\|private VarkaWarmupGate warmupGate;' $E/VarkaEvaluatorBase.java   # 3
    grep -c 'private String kernelIdentity;\|private String kernelShapeHash;\|private String renderedOptions;' $E/VarkaEvaluatorBase.java          # 3
    grep -c 'protected final VarkaKernelRunner fusedRunner() {' $E/VarkaEvaluatorBase.java    # 1
    grep -c 'private VarkaKernelRunner newRunner(CompiledVarkaProjection compiled) {' $E/VarkaEvaluatorBase.java   # 1
    grep -c 'protected VarkaShapeKey shapeKey(CompiledVarkaProjection compiled) {' $E/VarkaEvaluatorBase.java      # 1
    grep -c 'runner != null ? runner.shapeHash : null' $E/VarkaEvaluatorBase.java             # 1
    grep -c 'class Kernel ' $E/VarkaEvaluatorBase.java                                        # 0
    grep -c 'VarkaBatchArgs.layout(' $E/VarkaEvaluatorBase.java                               # at least 1 (row 682)
    grep -c 'private def evaluator(' $XS/VarkaKernelEvaluatorSuite.scala                      # 1
    grep -c 'Optional<PartialVarkaProjection> partialPlan() {' $E/VarkaKernelEvaluator.java   # 1

**Steps.** One main file, `E/VarkaEvaluatorBase.java`; a suite; the row's line.

1. Add the class `Kernel` of 4.1 after the `RUNNER_HOOKS` field, with one constructor,
   `Kernel(VarkaEmitOptions options)`.
2. Replace the six fields of B0 by `private Kernel[] kernels;` and `private Kernel current;`,
   where `runnerResolved` stood. In the constructor, after `this.emitOptions` is set:
   `this.kernels = new Kernel[] {new Kernel(emitOptions)};` and `this.current = kernels[0];`.
3. `fusedRunner()` becomes `return runnerOf(current);`. `private VarkaKernelRunner
   runnerOf(Kernel kernel)`, directly after it, has the old body and its comments, with
   `kernel.resolved` for `runnerResolved`, `kernel.runner` for `runner`, `newRunner(compiled,
   kernel)`, `shapeKey(compiled, kernel.options)`, `identityOf(kernel)` for `kernelIdentity()`
   and `() -> shapeHashOf(kernel)` for `this::kernelShapeHash`.
4. `newRunner` takes `(CompiledVarkaProjection compiled, Kernel kernel)`: its `shapeKey` call
   and each `emitOptions()` (the argument of `compiled.lane` and of row 682's
   `VarkaBatchArgs.layout`) take `kernel.options`.
5. `shapeKey(compiled)` keeps its signature and returns `shapeKey(compiled, current.options)`;
   the new private overload after it has the old body, `options` in place of `emitOptions()`.
6. `kernelIdentity()` returns `identityOf(current)`: the old body, memoized in
   `kernel.identity` and `kernel.shapeHash`, its key from `shapeKey(compiled, kernel.options)`.
   `kernelShapeHash()` returns `shapeHashOf(current)`, and `renderedOptions()` returns
   `renderedOf(current)`, memoized in the kernel from `kernel.options`.
7. `kernelReady`: a local `Kernel kernel = current;`; the gate is `kernel.gate`, built from
   `runnerOf(kernel)` with the identity supplier `() -> identityOf(kernel)`. In `serveBatch`,
   `runner != null ? runner.shapeHash : null` reads `current.runner` in both places.
8. `XS/VarkaKernelEvaluatorSuite.scala`: the two tests below, after "the emit.useAVX level
   reaches the shape key".
9. `sql/varka/plans/m9/PLAN.md`, row 685: `**Planned**` becomes `**Started**`.

**Tests.**

| Test | What it holds | The failure only it catches |
|---|---|---|
| the evaluator's kernel is the class the shape cache holds for its plan and options | inside `withTask`, with `kernels = evaluator()` and `plan = kernels.partialPlan.get.fused`: `kernels.emittedClassBytes.get` has the elements of `VarkaShapeCache.getOrEmit(new VarkaShapeKey(plan.outputs, plan.inputOrdinals.size, plan.numLiterals, VarkaColumnarToRowExec.emitOptions(VarkaSessionEmit.DEFAULTS), false), "VARKA-685").entry.classBytes` | a kernel whose key lost an option |
| a task looks its kernel's shape up once, however many batches it serves | after `canRun`, `VarkaShapeCache.hitCount + VarkaShapeCache.missCount` is the same before and after two rounds of `kernels.release(kernels.project(input))` | a runner built again for each batch |

**Commands**, in order:

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaKernelEvaluatorSuite *VarkaEvaluatorComponentsSuite *VarkaSeveralKernelsSuite *VarkaFilterExecSuite *VarkaProjectExecSuite *VarkaWarmupEndToEndSuite *VarkaFallbackReasonSuite *VarkaBatchArgRunnerSuite *VarkaTimestampZoneQuerySuite *VarkaTimestampZoneSuite'
    git diff --stat $(git merge-base origin/master HEAD) -- sql/catalyst/src/main | wc -l
    dev/varka_regen_generated.sh --all --full
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json
    python3 dev/varka_push_check.py

Expected: `failed 0`, and `Failed 0, Errors 0` for `VarkaTimestampZoneSuite`, with no edit to
any suite but step 8's; the second prints 0; the `git diff` and the push check exit 0.

**Planted faults** (after the commit, each reverted):

| Fault | The test that must fail |
|---|---|
| `runnerOf` does not set `kernel.resolved` | a task looks its kernel's shape up once |
| the new `shapeKey` overload passes `VarkaEmitOptions.DEFAULTS` for its `options` | the emit.useAVX level reaches the shape key, so a session at a level emits its own class |

**Done when** the commands print the above and both faults fail as named. **Not here:** a
second kernel, a per-batch step, the option, any file under `sql/catalyst`, any other suite.

### 5.2 Part b: the range pass, once a batch

**B0.**

    git branch --list 'varka-685b-ranges' | wc -l                                              # 0
    grep -c 'private static final class Kernel {' $E/VarkaEvaluatorBase.java                  # 1 (part a)
    grep -c 'LongRangeOps.leastAndGreatest' $E/varka/VarkaKernelRunner.java                   # at least 1 (row 682)
    grep -c 'record BatchProgram(' $E/varka/VarkaKernelRunner.java                            # 1
    grep -c 'STATUS_BATCH_ARGS = 1 << 13' $E/varka/VarkaKernelRunner.java                     # 1
    grep -c 'VarkaKernelRunner kernel = fusedRunner();' $E/VarkaEvaluatorBase.java            # 1
    grep -c 'VarkaKernelRunner runner = fusedRunner();' $E/VarkaFilterEvaluator.java          # 1
    grep -c 'boolean kernelReady(ColumnarBatch input) {' $E/VarkaEvaluatorBase.java           # 1
    ls $E/varka/VarkaBatchRanges.java 2>/dev/null | wc -l                                      # 0
    grep -n 'zoneData()' $XS/VarkaBatchArgRunnerSuite.scala                                   # the suite's own provider

The last grep prints where the suite's evaluator overrides `zoneData()`. Read that helper and
the suite's call that runs the kernel on a batch: the new tests use both. With neither, stop.

**Steps.**

1. `E/varka/VarkaBatchRanges.java`: new, as 4.2. `measure` holds no stream, lambda or boxing.
2. `E/varka/VarkaKernelRunner.java`: the constructor gains `@Nullable VarkaBatchRanges
   ranges`, directly after `programs`. In `fill`, the loop over the programs is the one step of
   4.2. Delete the `long[2]` range field and the two declines that row 682's fill threw.
3. `E/VarkaEvaluatorBase.java`:
   - fields `private @Nullable VarkaBatchRanges ranges;` and `private boolean prepared;` after
     `current`; `Kernel` gains `int steps`.
   - `newRunner`: where row 682 builds the programs, build `ranges` first if it is null and the
     layout is not empty (`input[p]` from `VarkaBatchArgs.operandColumn`, `zone[p]` from
     `zoneData()`, a missing zone throwing as row 682 has it, and, once
     `VarkaBatchArgs.dataOf` exists, wrapped as `VarkaBatchArgs.dataOf(<the program's
     node>, data)`, `VARKA-690.md` 4.4); set `kernel.steps` to the steps
     of `kernel.options.zoneForm()`; pass `ranges` to the runner.
   - `private void prepare(ColumnarBatch input)`: first `fusedRunner();`, which builds
     `ranges` with the runner (4.2). Then, when `ranges` is not null, `need =
     ranges.measure(input, input.numRows(), inputOrdinals)`, and `need > current.steps` throws
     `new VarkaBatchDeclined(VarkaKernelRunner.STATUS_BATCH_ARGS)`. Then `prepared = true`.
   - `kernelReady`: its first statement is `prepare(input);`. `batchRunner` of 4.2, after
     `fusedRunner()`; `runKernel` takes its runner from `batchRunner(input)`.
   - `void discardPrepared()` sets `prepared = false`. In `serveBatch`, everything after the
     `canRun` test goes inside `try { ... } finally { discardPrepared(); }`.
4. `E/VarkaFilterEvaluator.java`, `filterMask`: `fusedRunner()` becomes `batchRunner(input)`.
   `E/VarkaKernelEvaluator.java`: override `discardPrepared()`: `super`, then each of `parts()`.
5. `XS/VarkaBatchArgRunnerSuite.scala`: a private class `CountingZone(inner: VarkaZoneData)
   extends VarkaZoneData` that delegates the six methods and counts the calls of `changes`;
   the four tests below, over an evaluator whose `zoneData()` wraps the suite's provider in it.

**Tests** added to `VarkaBatchArgRunnerSuite`:

| Test | What it holds | The failure only it catches |
|---|---|---|
| a batch's range is measured once, before the fill | three batches through `serveBatch`: `changes` was asked three times, and the answers equal the reference | a pass that still runs in `fill`, once a form |
| a caller with no serveBatch measures the batch itself | the suite's direct kernel call on a summer batch, then on a winter batch: each answer is its own batch's | a kernel path that reads the ranges of an earlier batch |
| a batch whose readiness is asked before any canRun is still measured | a fresh evaluator, no `canRun`: `kernelReady` on a summer batch, then the direct kernel call on it: summer's answers | ranges built after `prepared` was set: neutral arguments, UTC's answers under a zone |
| a batch that was prepared and not run leaves nothing behind | `serveBatch` on a summer batch with a kernel path that throws `new VarkaBatchDeclined(VarkaKernelRunner.STATUS_FORCED)`, then the direct call on a winter batch: winter's answers | `discardPrepared` missing: the next batch runs with stale arguments, a wrong answer |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaBatchArgRunnerSuite *VarkaKernelEvaluatorSuite *VarkaSeveralKernelsSuite *VarkaFilterExecSuite *VarkaWarmupEndToEndSuite *VarkaTimestampZoneQuerySuite *VarkaTimestampZoneSuite *VarkaReproductionEndToEndSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *VarkaBatchArgRunnerSuite *VarkaTimestampZoneSuite'
    git diff --stat $(git merge-base origin/master HEAD) -- sql/catalyst/src/main | wc -l
    python3 dev/varka_push_check.py

Expected: `failed 0` and `Failed 0, Errors 0` in both runs, none canceled, the four new
tests among the passed; the third prints 0; the push check exits 0.

**Planted faults:**

| Fault | The test that must fail |
|---|---|
| `fill` calls `zone.changes` again before `toLocalArgs` | a batch's range is measured once (six calls, not three) |
| `discardPrepared` does nothing | a batch that was prepared and not run leaves nothing behind |
| `batchRunner` never calls `prepare` | a caller with no serveBatch measures the batch itself |
| `prepare` does not call `fusedRunner()` first | a batch whose readiness is asked before any canRun is still measured |

**Done when** the commands print the above and the faults fail as named. **Not here:** a
second kernel, `LADDER`, a change of a default, a file under `sql/catalyst`.

### 5.3 Part c: the ladder

**B0.**

    git branch --list 'varka-685c-ladder' | wc -l                                              # 0
    grep -c 'STEPS_8(8)' $V/VarkaEmitOptions.java                                             # 1
    grep -c 'LADDER' $V/VarkaEmitOptions.java                                                 # 0
    grep -c 'ZoneForm.STEPS_2' $V/VarkaEmitOption.java                                        # at least 1 (the default)
    ls $V/VarkaZoneLadder.java $TJ/VarkaZoneLadderSuite.java 2>/dev/null | wc -l               # 0
    grep -c 'protected final VarkaKernelRunner batchRunner(ColumnarBatch input) {' $E/VarkaEvaluatorBase.java   # 1 (part b)
    grep -c 'public static String reasonKey(String reason) {' $E/VarkaExecMetrics.java        # 1
    grep -c 'new VarkaExecMetrics(' $E/VarkaExecMetrics.java                                  # 3
    grep -c 'hour(ltzy)' $TS/VarkaCoverageSuite.scala                                         # at least 1 (row 657)
    dev/varka_matrix.sh --list | grep -c 'zoneForm='                                           # 2
    grep -c 'zoneStepMask' $V/VarkaEmitOptions.java                                           # 0, or step 6's second form
    grep -rn 'zoneForm()' sql/catalyst/src sql/core/src | grep -v 'VarkaEmitOptions.java\|VarkaEmitOption.java'
    grep -n 'new ToLocal' $TS/VarkaEmitterBudgetSuite.scala
    grep -c '"exactRefusalStatus"' $TJ/VarkaTestReach.java                                    # 1
    grep -c 'private def methodHashes(roots: Seq\[VarkaVectorIR\], numInputs: Int, numLiterals: Int,' $TS/VarkaEmittedBytesSuite.scala   # 1

The two lists: the readers of steps 4 and 8, in main and in tests (in catalyst's main expect
`VarkaBatchArgs`, `VarkaZoneLowering`, `VarkaEmitBudget` and the slot planning; a reader in a
`Varka*Compiler.java` is a stop), and the budget rows of step 8.

**Steps.**

1. `V/VarkaEmitOptions.java`: `LADDER(-1)` last in `ZoneForm`; `steps()` throws on it (4.3);
   `DEFAULTS` holds `ZoneForm.LADDER`; the option's `@param` says what 4.3 says.
2. `V/VarkaEmitOption.java`: the `zoneForm` entry's default is `ZoneForm.LADDER`.
3. `V/VarkaZoneLadder.java`: new, as 4.3, with `LADDER_REASON`.
4. Each reader B0 printed in main, and each in a test class that is no suite and takes the
   steps of a kernel's own options (the fixtures, row 684's fuzz harness, a filler):
   `options.zoneForm()` becomes `VarkaZoneLadder.emitted(options)`. A harness so sizes and
   fills batch arguments as the emitter laid them out, under every arm.
   `V/VarkaVectorIR.java`: the weight in `ToLocal.props()` becomes 17.
   `TJ/VarkaTestReach.java`, `fullRun`: `full.put("zoneForm", "read by the evaluator as it
   runs, to take a rung or to decline a batch");`.
5. `E/VarkaExecMetrics.java`: the component `List<SQLMetric> servedByZoneForm`, last; the
   compact constructor copies it and accepts an empty list or one of `RUNGS.size()`; `NONE`
   passes `List.of()`; `public static String zoneFormKey(ZoneForm form)` returns
   `"numZoneFormBatches[" + form + "]"`; `nodeMetrics` registers one plain count a rung
   ("batches served by a kernel of zone form " + form) and `fromNode` requires each; the
   `Builder` gains the field, its setter and the argument of `build()`; `public static void
   incServed(VarkaExecMetrics metrics, int rung)` does nothing for an empty list.
6. `E/VarkaEvaluatorBase.java`:
   - fields `private boolean ladder;` and `private int widest;`; `Kernel` gains `int rung`,
     its index in `RUNGS`, or -1 with no zone program.
   - `plan()`, once the plan is set: `ladder` is `VarkaBatchArgs.zonesNeeded(plan.outputs()) >
     0 && emitOptions.zoneForm() == ZoneForm.LADDER`. If so, `kernels` becomes one `Kernel` a
     rung with `emitOptions.withZoneForm(rung)`, `current = kernels[0]`, and `widest =
     VarkaZoneLadder.widest(sessionEmit.declineTwoLaneConditions())`. If B0 found
     `zoneStepMask`, the argument is `sessionEmit.declineTwoLaneConditions() &&
     emitOptions.zoneStepMask()`.
   - `newRunner`: `kernel.steps` and `kernel.rung` from
     `VarkaZoneLadder.emitted(kernel.options)` when the layout is not empty. `prepare`: the
     three steps of 4.4.
   - `kernelReady`, after `prepare`: `if (fusedRunner() == null) return true;`; then, warmed, the
     gate of `current`; if it is not ready and `ladder`, the loop of 4.5 over the wider rungs
     up to `widest`, taking the first whose `runner` is not null and whose
     `runner.warmth.ready()`; else false.
   - `serveBatch`: after the `kernelReady` try, `if (emissionFailed()) { return
     fallback(input, fallbackPath); }`. After the kernel path returns its result, `countServed()`.
   - `void countServed()`: `if (current.rung >= 0) VarkaExecMetrics.incServed(metrics,
     current.rung);`.
7. `E/VarkaKernelEvaluator.java`: override `countServed()`: `super`, then each of `parts()`.
8. The suites of rows 682, 684 and 657 that read the default as two steps. Run the first two
   commands below. A test that fails because its options name no form, and that step 4's
   harness edit does not mend, takes `STEPS_2` only where its base's form is `LADDER`
   (`base.zoneForm() == ZoneForm.LADDER ? base.withZoneForm(ZoneForm.STEPS_2) : base`), so it
   still follows the arms `zoneForm=ONE` and `zoneForm=STEPS_8`. It gets no other edit. A row
   or assertion that pins the default weight of `ToLocal` at 5 takes 17. Else stop.
9. `TS/VarkaCoverageSuite.scala`: in the note of `hour(ltzy)`, "up to two steps a batch"
   becomes "up to eight steps a batch, in the least form that covers the batch". If the note
   lacks those words, stop.
10. `docs/sql-varka.md`, by hand: the metrics' list gains `numZoneFormBatches[<form>]`; the
    sentence on `batch-args` takes 4.9's words; "Timestamps in the session time zone" gains
    two sentences on the ladder and one on the two cache metrics (4.7).
11. The tests below.
12. `dev/varka_regen_generated.sh --all`. Then `dev/varka_matrix.sh --config zoneForm=ONE
    --config zoneForm=STEPS_2 --config zoneForm=STEPS_8 --module sql --suites
    VarkaTimestampZoneSuite,VarkaTimestampZoneQuerySuite,VarkaBatchArgRunnerSuite,VarkaCoverageHarnessSuite,VarkaCoverageDifferentialSuite`.
    A stale line of `zoneForm=ONE` that it names is deleted. A failure under `zoneForm=STEPS_2`
    or `zoneForm=STEPS_8`, or of a test this part adds, is a stop: no new line is expected.

**Tests.** `TJ/VarkaZoneLadderSuite.java`, new, JUnit 5, `@VarkaSuite`, six tests:

| Test | The failure only it catches |
|---|---|
| the rungs rise in steps, and the admission form is the rung with the most | a rung added out of order, which would make `rung` skip a cheaper form |
| the least rung that covers a count: 0 is `ONE`; 1 and 2 are `STEPS_2`; 3 and 8 are `STEPS_8`; 9 is none | a form that does not cover its batch, which is a wrong answer |
| a ladder that stops at two steps covers no count over two | eight masked steps at two lanes |
| `LADDER` has no steps, and its message is `LADDER_REASON` | a lowering that reads `LADDER` as a form |
| an emission under `LADDER` is the admission form's: `emitted` of the defaults is `ADMISSION`, and `(toLocal:0 col:0:long)` emitted under `VarkaEmitOptions.DEFAULTS` has the methods and code lengths (`VarkaEmittedClass.measure(bytes).codeLength`) of `DEFAULTS.withZoneForm(ZoneForm.STEPS_8)` | a reader that step 4 missed |
| the defaults' `canonical()` holds no `zoneForm=`, and `withZoneForm(STEPS_2)` shows `zoneForm=STEPS_2` | a shape with no zone node whose class name moved |

`TS/VarkaEmittedBytesSuite.scala`, one test, "every rung of the ladder emits every zoned
shape": for each rung of `RUNGS` and each of the suite's `widths`, the suite's `methodHashes`
with the arm `_.withZoneForm(rung)` throws nothing on every coverage row whose roots hold a
`ToLocal` (at least one does), and on sixteen outputs `new ToLocal(new ColumnRef(i,
LaneType.LONG), 0)`, `i` from 0 to 15. Only it catches a lighter rung whose grouping crosses
a limit the admission form never met: a throw in the middle of a task (4.3). Its failure is
a stop: the remedy, grouping every rung at one weight, moves bytes.

`XS/VarkaBatchArgRunnerSuite.scala`. The new tests' evaluators take `VarkaSessionEmit
.DEFAULTS`, whose `declineTwoLaneConditions` is false: no host decides them. A helper
`served(evaluator, warmed)` returns the rung
`f` for which `evaluator.kernelIdentity` starts with `VarkaShapeCache.sourceFileFor(
VarkaShapeCache.shapeHash(new VarkaShapeKey(plan.outputs, plan.inputOrdinals.size,
plan.numLiterals, base.withZoneForm(f), warmed)))`, with `base = VarkaMatrixRuns.BASE
.withZoneForm(ZoneForm.LADDER)` set through `VarkaColumnarToRowExec.setEmitOptionsForTesting`
and restored after. Batches are built to span a given count of changes of `Europe/Podgorica`,
read from `ZoneRules.nextTransition`. Each kernel path given to `serveBatch` is
`evaluator.runKernel` with a `VectorAllocator` that counts its calls.

| Test | What it holds | The failure only it catches |
|---|---|---|
| under the ladder each batch runs the least form that covers it | batches of 0, 1, 2, 3, 8 and 9 changes: `served` is `ONE`, `STEPS_2`, `STEPS_2`, `STEPS_8`, `STEPS_8`; the last declines with `batch-args`; every answer equals the reference | a batch on a dearer form than it needs |
| no batch runs twice | over the same six batches the output allocator is asked once an output a served batch, and `changes` once a batch | a second run of a batch on another form |
| a form first needed in the middle of a task is built then, and the earlier form still serves | batches of 0, 3, 0, 2 changes: `served` is `ONE`, `STEPS_8`, `ONE`, `STEPS_2`; `hitCount + missCount` of the cache rises by 1 at `canRun`, at the second batch and at the fourth, by 0 at the third: three lookups in all | a rung built again, or a task stuck on its widest rung |
| a forced form declines what it does not cover and takes no other | `STEPS_2` with 3 changes and `ONE` with 1: declined with `batch-args`; `served` stays the forced form | a matrix arm that the ladder quietly rescues |
| two tasks of one projection choose apart | two evaluators of one plan, one fed 0 changes and one 3, then swapped: each batch is served by its own rung with its own answers | a rung or a range shared between tasks |
| a session whose masks fall back at two lanes stops at two steps | an evaluator built with a `VarkaSessionEmit` whose `declineTwoLaneConditions` is true: 2 changes are served by `STEPS_2`, 3 decline with `batch-args` | eight masked steps, slower than the row path |
| while a form warms, a wider compiled form serves, and with none the batch waits on the row path | evaluators with the warm-up on (`assert(VarkaKernelWarmup.warms(true))`; if false, stop). The warmth of `STEPS_2`'s warmed entry is released and that of `ONE`'s claimed, both through `VarkaShapeCache.getOrEmit(key, "t").entry.warmth`. An evaluator fed 2 changes, then 0: `served` is `STEPS_2` twice. A fresh evaluator fed 0: `kernelReady` is false. `ONE`'s warmth is released in a `finally` | a ladder slower than the form it replaced |

`XJ/VarkaTimestampZoneSuite.java`, three tests on the suite's own fixture of a zone and a
window, the form set as the suite sets one (`VarkaMatrixRuns.BASE.withZoneForm(LADDER)`).
Each adds `spark.sql.codegen.varka.declineTwoLaneConditions=false` to the suite's conf, and
so does the query suite's test below, by `withSQLConf`. Unset, the conf is the host's answer:
true under `-XX:MaxVectorSize=16`, where the ladder stops at `STEPS_2` (4.4) and a test that
expects `STEPS_8` fails. Set to false, the tests check every rung at every width; the cap
has its own test above. A branch on `VarkaKernelPlan.twoLaneMasksFallBack` would test less.

| Test | The failure only it catches |
|---|---|
| a year of instants in no order under America/Los_Angeles declines no batch under the ladder (as one batch and as batches of 64 rows) | the row's done-when |
| Africa/Casablanca over 2017, in no order, declines no batch under the ladder (the test asserts that the window holds more than two changes and at most eight) | a ladder that never reaches eight steps |
| ten years under America/Los_Angeles as one batch decline with `STATUS_BATCH_ARGS` under the ladder | a form that serves a batch past its steps |

`XS/VarkaTimestampZoneQuerySuite.scala`, one test, "the plan's metrics name the form that
served each batch": three cached batches under `America/Los_Angeles`, a week, a year and three
years in no order, built as the suite's test of three cached batches builds its own, the form
set to `LADDER` where that test pins `STEPS_2`:
`numZoneFormBatches[ONE]`, `[STEPS_2]` and `[STEPS_8]` are each 1, `numVarkaBatches` is 3 and
`numFallbackBatchesDeclined` is 0. Only it catches a count that the plan does not show.

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaZoneLadderSuite *VarkaBatchArgsSuite *VarkaEmitterZoneSuite *VarkaZoneSweepSuite *VarkaZonePlantedFaultSuite *VarkaZoneArgsPropertySuite *VarkaZoneCompilerSuite *VarkaEmitOptionSuite *VarkaMatrixSuite *VarkaEmitterBudgetSuite *VarkaNodePropsSuite *VarkaIrFuzzSuite *VarkaTestReachSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaBatchArgRunnerSuite *VarkaTimestampZoneSuite *VarkaTimestampZoneQuerySuite *VarkaKernelEvaluatorSuite *VarkaEvaluatorComponentsSuite *VarkaSeveralKernelsSuite *VarkaWarmupEndToEndSuite *VarkaFamilySwitchQuerySuite'
    dev/varka_regen_generated.sh --all
    python3 dev/varka_bytes_moved.py origin/master --ignore option_arms | grep -v 'ltzy\|fuzz_long\|fuzz_mixed' | wc -l
    git status --short > target/status-685.txt; git diff > target/diff-685.txt
    JDK_JAVA_OPTIONS=-XX:UseAVX=2 dev/varka_regen_generated.sh --all --full
    git status --short | diff - target/status-685.txt; git diff | diff - target/diff-685.txt
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaCoverageSuite *VarkaEmittedBytesSuite *VarkaWidthAuditSuite *VarkaWarmupReachSuite' 'sql/testOnly *VarkaCoverageHarnessSuite *VarkaCoverageDifferentialSuite'
    dev/varka_matrix.sh --list | grep -c 'zoneForm='
    python3 dev/varka_push_check.py

Expected: the first two print `failed 0` and `Failed 0, Errors 0`; `VarkaZoneLadderSuite` prints
`Passed: Total 6, Failed 0, Errors 0, Passed 6`. The fourth prints 0: every digest that moved
is a coverage row's over `ltzy`, or a long or mixed fuzz block, which holds a `ToLocal` only
once row 684's draw has merged. Both `diff`s print nothing: the AVX2 run changes no file's
state and no file's contents. The
suites of the next command pass; row 655's queries suites pass unchanged, ten years in no
order still `ALL_FALL_BACK`. The matrix lists 3. The push check exits 0.

**Planted faults:**

| Fault | The test that must fail |
|---|---|
| `rung` compares `steps() > changes` | the least rung that covers a count |
| `prepare` takes `kernels[widest]` for every batch | under the ladder each batch runs the least form |
| `prepare` takes the ladder's path under a forced form | a forced form declines what it does not cover |
| `emitted` returns `STEPS_2` for `LADDER` | an emission under `LADDER` is the admission form's |
| `serveBatch` does not call `countServed` | the plan's metrics name the form that served each batch |

**Done when** the commands print the above, the five faults fail as named, and the size count
is under 900. Over it, the metric (steps 5 and 7, `countServed`, the query suite's test, the
docs' line) becomes a pull request of its own after this one. **Not here:** the form `TABLE`,
`zoneStepMask`, a grain, a benchmark, a new status or decline text.

### 5.4 Part d: the measurement and the close

**B0.**

    git branch --list 'varka-685d-measure' | wc -l; ls $XJ/benchmark/VarkaZoneLadderBenchmark.java 2>/dev/null | wc -l   # 0 and 0
    grep -c 'LADDER' $V/VarkaZoneLadder.java                                                  # at least 1
    grep -c 'protected static void withEmitOptions(VarkaEmitOptions options, Runnable body)' $XJ/benchmark/VarkaSqlBenchmarkBase.java   # 1
    ls $XJ/benchmark/VarkaBatchArgsBenchmark.java | wc -l                                      # 1 (row 682, part f: the model)

**Steps.**

1. `XJ/benchmark/VarkaZoneLadderBenchmark.java`: new, Java, over `VarkaSqlBenchmarkBase`,
   modelled on `VarkaBatchArgsBenchmark`. The query is `hour(ts)` over a cached `TIMESTAMP`
   column under `America/Los_Angeles`, 4096 rows a batch. One group a data set: "a week", "a
   year, no order", "four years, no order" (eight changes), "ten years, no order", "a week
   and a year, batch by batch". Each group's cases, in order: "row path" (Varka off, the
   control), "zoneForm=ONE", "zoneForm=STEPS_2", "zoneForm=STEPS_8", "zoneForm=LADDER", each
   through `withEmitOptions`. A last group, "a form's first use: one emission and
   definition", has one case a rung: the kernel of `hour(ts)` emitted under that rung and
   defined in a fresh class loader, as `VarkaEmissionBenchmark`'s group "emitting a fused
   kernel: bytes and a defined class" does.
2. The dry run below. The lead dispatches the timed run on the EPYC 7763 runners at 256 and
   128 bits and commits `sql/core/benchmarks/VarkaZoneLadderBenchmark-jdk25-results.txt` and
   its `-jdk25-128bit-results.txt` companion from the run's artifact.
3. `sql/varka/skills/testing-and-debugging.md`: one `##` section: a step that must run once
   a batch has two callers, `serveBatch` and a direct call, and `prepared` joins them.
   Then `dev/varka_toc.py SKILLS.md --from sql/varka/skills`.
4. This plan's section 11 and the row's line in `PLAN.md`, which reads `**Done**`: P1 to P9,
   P11 and P12 scored, each scored time prediction added with `dev/varka_calibration.py
   --add-row`. P10 and the gate of part e are not scored here, since row 691 needs this row
   first. Both texts say "P10 and the gate of 4.10 are read by row 691"; row 691's close
   writes them into section 11, and part e, if the gate opens, follows it.

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" 'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaZoneLadderBenchmark" 'set Test/javaOptions += "-XX:MaxVectorSize=32"' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaZoneLadderBenchmark" 'set Test/javaOptions += "-XX:MaxVectorSize=16"' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaZoneLadderBenchmark" > target/dry-685.log 2>&1; echo $?
    grep -c 'Exception' target/dry-685.log
    python3 dev/varka_plan_lint.py --check --offline sql/varka/plans/m9/PLAN.md
    python3 dev/varka_push_check.py

Expected: `0`; `0`; the lint and the push check exit 0. **Done when** the dry run passes at
three widths, the lead has committed both results files, and every prediction of section 7
but P10 has a score. **Not here:** part e's gate, a timed run on the shared laptop, a change
to main code, a number in a document that is not in a results file.

### 5.5 Part e: the fields that need no offset (only if the gate of 4.10 opens)

**B0.** The lead names the two lines of row 691's results and their ratio, or stop. Then:

    git branch --list 'varka-685e-grain' | wc -l                                               # 0
    grep -c 'record ToLocal(VarkaVectorIR instant, int zone, int hash)' $V/VarkaVectorIR.java # 1
    grep -c 'public int measure(ColumnarBatch batch, int len, int\[\] inputOrdinals)' $E/varka/VarkaBatchRanges.java   # 1
    grep -c 'CompiledVarkaProjection projection(List<VarkaVectorIR> outputs, List<DataType> outputTypes) {' $C/VarkaExpressionCompiler.java   # 1
    grep -n 'static Optional<VarkaVectorIR> toLocal(' -A2 $C/VarkaZoneCompiler.java           # row 657's signature

**Steps.**

1. `V/VarkaVectorIR.java`: `record ToLocal(VarkaVectorIR instant, int zone, long grain, int
   hash)`. The public constructor `(instant, zone)` passes grain 0; a second takes the grain.
   The compact constructor admits 0, `MICROS_PER_MINUTE` or `MICROS_PER_HOUR`; the hash takes
   the grain; `withChildren` keeps it. The canonical text is unchanged for grain 0 and
   `(toLocal:<zone>:<grain> <child>)` otherwise; `TJ/VarkaCanonicalIr.java` parses both.
   `TJ/VarkaReference.java`, the arm of `ToLocal`: with a grain, `micros +
   Math.floorMod(offsetMicros, grain)`.
2. `E/varka/VarkaBatchRanges.java`: the constructor gains `long[] grain`. A grained program
   has a scratch `long[1 + 2 * GRAIN_STEPS]`, `GRAIN_STEPS = 64`. In `measure`, more than
   `GRAIN_STEPS` changes is `NOT_COVERED`; else `toLocalArgs(least, greatest, GRAIN_STEPS,
   scratch, 0)`, then in place: the base modulo the grain, each step's difference as the
   difference of its two offsets modulo the grain, the steps of difference 0 dropped. The
   need is the count kept. New `public void fill(int program, int steps, long[] args, int
   first)` writes the reduced base, the kept steps and padding for a grained program, and
   calls `zone.toLocalArgs` otherwise. `VarkaKernelRunner.fill` calls it for a live program.
3. `C/VarkaZoneCompiler.java`: `toLocal` gains a last parameter `long grain`; the arm of
   `Second` passes `MICROS_PER_MINUTE`, of `Minute` `MICROS_PER_HOUR`, every other 0. New
   `static List<VarkaVectorIR> shareGrains(List<VarkaVectorIR> outputs)`: for each pair of
   operand and zone, the coarsest grain among its nodes (0, then an hour, then a minute), and
   the outputs rebuilt bottom-up with `VarkaVectorIR.childrenOf` and `withChildren`.
   `C/VarkaExpressionCompiler.java`, `Tables.projection`: its first statement is `outputs =
   VarkaZoneCompiler.shareGrains(outputs);`.
4. Row 684's grammar draws a grain; its sweep and its property suite run each grain.

**Tests**, each with the planted fault that must fail it:

| Suite and test | The failure only it catches | Planted fault |
|---|---|---|
| `VarkaZoneSweepSuite`: a grained node is the instant plus the offset modulo its grain, in every zone | a reduction that drops a step it needs | the reference ignores the grain |
| `VarkaTimestampZoneSuite`: `second(ts)` and `minute(ts)` equal the row engine on 0001-01-01 under `America/Los_Angeles`, whose offset is no whole minute | `second` read from the instant where the offset is odd | the fill writes a base of 0 for a grained program |
| `VarkaBatchArgRunnerSuite`: `second` over ten years in no order under `America/Los_Angeles` is served by `ONE` | a grained program that still counts whole-hour changes | the reduction keeps a step of difference 0 |
| `VarkaZoneCompilerSuite`: `hour(ts)`, `minute(ts)` and `second(ts)` in one projection hold one `ToLocal`, of grain 0; `minute(ts)` and `second(ts)` hold one, of grain an hour | three conversions where there was one | `shareGrains` takes the finest grain |

**Commands:** part c's first two, with `*VarkaReproductionSuite` added to the first;
`dev/varka_regen_generated.sh --all`; `python3 dev/varka_bytes_moved.py origin/master --ignore
option_arms`, which prints nothing; `python3 dev/varka_push_check.py`. **Done when** they pass
and the four faults fail as named. **Not here:** a new form, a status, the lowering's bytes.

## 6. Tests, proofs, fuzzing and coverage

| Suite | Language, module | Holds | The failure only it catches |
|---|---|---|---|
| `VarkaZoneLadderSuite` (new) | Java, catalyst | the rungs, `rung`, `widest`, `emitted`, the refusal of `LADDER` | a form chosen that does not cover its batch |
| `VarkaBatchArgRunnerSuite` (row 682, extended) | Scala, sql/core | one measurement a batch; the least form; no second run; a form built in the middle of a task; a forced form; two tasks; a wider form while one warms | a stale or shared range, which is a wrong answer no fallback catches |
| `VarkaTimestampZoneSuite` (row 657, extended) | Java, sql/core | a year and `Africa/Casablanca` decline no batch; ten years decline | a ladder that agrees with Spark only where two steps did |
| `VarkaTimestampZoneQuerySuite` (row 657, extended) | Scala, sql/core | the plan's metrics by form | a count the user cannot see |
| `VarkaKernelEvaluatorSuite` (extended) | Scala, sql/core | the refactoring: one class, one lookup | a runner built again a batch |
| `VarkaEmittedBytesSuite` (extended) | Scala, catalyst | every rung emits every zoned coverage row and sixteen zoned outputs | a rung that cannot emit a shape the admission form admitted |
| the suites of rows 682, 684 and 657, and row 655's queries suites | existing | every forced form, every zone, each section's batch check | a default that moved an answer |

- **Unchanged suites.** After parts a and b every existing suite passes with no edit; after
  part c the only edits to existing tests and harnesses are steps 4's and 8's.
- **Proofs.** No lowering is added, so no proof file. The choice rests on one claim: a form of
  `k` steps is exact for a batch whose range holds at most `k` changes. Row 684's check "every
  form that covers a batch gives the same rows" holds it; it sets each form itself.
- **Fuzzing.** `VarkaIrFuzzSuite` draws the option's constants; a draw of `LADDER` emits the
  admission form (4.3), one more draw of eight steps. `VarkaSparkFuzzSuite` runs sessions
  under the default, so from part c on it runs the ladder over the zones row 684 gave it.
- **Coverage.** `dev/varka_gen_coverage.sh` after part c: every line of `VarkaZoneLadder` and
  `VarkaBatchRanges` covered. **The sanitizer:** part b's second command, and the matrix.
- **Mutation.** `dev/varka_pit.sh` runs ScalaTest suites only (`VARKA-682.md` 3). It reaches
  `VarkaBatchRanges` and `prepare` through `VarkaBatchArgRunnerSuite`: part c's pull request
  lists or kills each surviving mutant. The planted faults stand in for `VarkaZoneLadder`.

## 7. Measurement

`VarkaZoneLadderBenchmark` (part d) runs on the AMD EPYC 7763 runners at 256 and at 128 bits;
the laptop adds 512 when idle. Its A/B arms are the forms by name (5.4), with the row path as
each group's control. A ratio under 1.3 is run again and compared by minimums.

**Predictions, registered on 26 November 2026, before any run.**

| # | Prediction | Scored by |
|---|---|---|
| P1 | The choice costs nothing a section can show: "a year, no order" under `zoneForm=LADDER` is within three percent of `zoneForm=STEPS_2`, either way, at both widths | part d, by minimums of two runs |
| P2 | "a week": `zoneForm=LADDER` is not slower than `zoneForm=STEPS_2` and is under five percent faster. The step's share is small: 0.148 less 0.077 ns a row in the body (`results/forms.txt`), beside a columnar floor of about 2.5 ns a row (`VARKA-657.md` 6.1) | part d |
| P3 | "four years, no order" at 256 bits: `zoneForm=STEPS_2` equals the row path (every batch declines) and `zoneForm=LADDER` is at least five times it | part d |
| P4 | "four years, no order" at 128 bits: `zoneForm=LADDER` is not slower than the row path, because the ladder stops at two steps there and the batches decline: yes | part d |
| P5 | "ten years, no order" declines every batch under every arm until row 686: yes | part c's test of ten years, and row 655's `ALL_FALL_BACK` sections, unchanged |
| P6 | A form's first use: one emission and definition of a rung of `hour(ts)` is under half a millisecond on the runner at 256 bits (the laptop took 71842.8 ns for `year(d)`) | part d's last group |
| P7 | With the warm-up on, a batch whose least form is cold never waits: it runs a wider compiled form, or the row path: yes | part c's test of a form that warms |
| P8 | A task that meets every rung adds three entries to the class cache for one zoned kernel, and no other class: yes | part c's test of a form built in the middle of a task, by the cache's counts |
| P9 | No existing shape's bytes move: after parts a and b no generated file differs; after part c only zoned rows and zoned fuzz blocks: yes | each part's commands |
| P10 | The gate of part e stays shut: `second(ts)` over a year in no order costs under a twentieth more than over a week at 256 bits | row 691, after this row is Done (5.4 step 4) |
| P11 | Four parts built, none over 800 counted lines; part c is the largest | `dev/varka_msg_check.py size` |
| P12 | Test time: `VarkaZoneLadderSuite` under 2 s; the tests added to `VarkaBatchArgRunnerSuite` in parts b and c under 15 s together; the test added to `VarkaTimestampZoneQuerySuite` under 5 s | the suites' reported times |

P12 was set after reading `python3 dev/varka_calibration.py`. Its nearest rows: a JUnit class
with no session took 0.15 s (VARKA-328); a compile-only suite 1.9 to 2.3 s (VARKA-318); short
queries over one cached view 3.7 s (VARKA-318) and 4.5 s (VARKA-315). P12 is not doubled.

## 8. Who builds it

Every part at full strength: `m9/PLAN.md` 1.4 has rows 656, 657 and 682 to 692 so. Parts a
and b change the evaluator's per-batch path, where a slip is a wrong kernel or a stale range;
part c changes a default that every session takes. The row moves nothing of G5.

## 9. Risks

1. **A stale range is a wrong answer** that no fallback catches. `prepared` is consumed by the
   kernel path and discarded by `serveBatch`; part b's three tests and faults hold it.
2. **The emission under `LADDER` departs from the family's first wording,** which had the
   emitter refuse it. Fact 9 forces a rule: the compiler and the coverage suites emit under the
   defaults. If the owner prefers the refusal, part c's step 4 becomes a throw and each direct
   emitter of a zoned shape names a form by hand.
3. **Admission by the largest rung** can split or decline a projection that two steps admitted
   whole (weight 17 a node, where it was 5). A section of row 655's classes that stops fusing
   is a stop for part c. Each rung groups at its own weight; 4.3's test holds its emission.
4. **A cold rung with the warm-up on** sends batches to the row path where no wider rung is
   compiled: today's rule for every new kernel. P7 and the benchmark's mixed group show it.
5. **The cap at two lanes reads the driver's host flag,** the debt the catalogue's item 127
   records. Row 689 must change `widest`'s argument, or the cap outlives its reason.
6. **`zoneForm` in `VarkaTestReach.FULL`** makes three configurations run every test on the
   fork (4.8). The record may be enough, since it hears each rung looked up; taking the
   entry out is the owner's decision, after a run shows that no test's answer needs it.
7. **The plan ages before it is built.** Rows 682, 683, 657 and 678 are not built: each B0
   greps their names, and part c's step 8 covers their tests that read the default as two
   steps. A premise that fails, or a failure the step does not describe, stops the part.
8. **Four or five pull requests where the family plan had two**, and row 686 adds a rung:
   `RUNGS`, `ADMISSION`, the metrics' list and the docs each name the forms.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md` and `BUILD_SHEETS.md`, the rules a builder here is likeliest to break:

- "No allocation, streams, lambdas or boxing on a path that runs per batch or per row."
  `prepare`, `measure`, `rung`, `countServed` and `batchRunner` run for every batch.
- "A refactor is proven by `emitted_bytes.json` byte-identical." Parts a and b touch no file
  under `sql/catalyst`. "A switch over a sealed type or an enum has no `default`": a switch
  over `ZoneForm` names `LADDER`.
- "Anything that can return a *wrong* answer rather than fail ... gets its own differential
  coverage, because the fallback cannot catch it." The ranges and the choice are such.
- "A test starts from `VarkaMatrix.base`, not `VarkaEmitOptions.DEFAULTS`"; a test whose
  subject is a form sets that form.
- A comment describes the code as it is. Generated files are never edited by hand. A pull
  request stays under 1000 counted lines. New production code is Java; a new catalyst suite
  is JUnit 5 under `@VarkaSuite` with a `@DisplayName` on each test.

Do not: time a batch to choose its form; run a batch twice; let a lowering read `LADDER`; add
a status or a decline text; touch `VarkaWarmupGate` or `VarkaKernelWarmup`; add `TABLE` or
`zoneStepMask`; run a timed benchmark on the shared laptop; push or open a pull request.

## 11. Outcome

To be written when the row closes.
