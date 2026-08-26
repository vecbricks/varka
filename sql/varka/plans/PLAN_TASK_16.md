# Task 16: debuggability quick wins

**Status: DONE.** See `PLAN_MILESTONE_2.md` (task row 16) for the milestone
context. Like task 13 this task had no pre-written plan - the milestone row and
its validation column were the whole spec, written from the debuggability
review that followed task 13 - so this file records what was built and the
decisions taken along the way.

## 1. What the task is for

Task 13 made the emitted classes self-describing, and a review then asked what
the telemetry still does not answer. Four questions, all of them asked in
practice and none answerable before this task:

* *Which IR node is this frame?* A stack trace through the generated loop named
  a method (`loopMasked0`) and nothing finer.
* *Which kernel gave up?* The ghost-fallback warnings carried the exception
  only, so a log line could not identify the plan node that fell back.
* *What does the emitted bytecode look like?* The bytes existed only in
  Metaspace and behind an internal accessor.
* *Why didn't my projection fuse?* `compilePartial` classified every entry and
  dropped the reason on the floor - the silent per-entry `None`.

Each is a small extension of machinery that already exists, which is why they
travel together as one task rather than four.

## 2. What was built

### 2.1 Bytecode maps back to IR nodes (catalyst)

The emitter attributes each node's own instructions to a line number: line `n`
is the `n`-th node of `Analysis.topoOrder`, the children-before-parents order
the scalar tail already schedules by, so the numbering is a property of the IR
rather than of the emission order. `Analysis` records the index as it appends
to `topoOrder`, and a one-line `line(cb, analysis, node)` helper sits
immediately before each node's *defining* instruction - the invoke that does
the node's work, after its children have been emitted. That placement is what
makes a frame accurate: a marker before the children would attribute the
parent's op to whichever child was emitted last.

The decoding key travels in the class: `VarkaDebugInfo` gained a third field,
one `<line>=<node>` entry per line, newline separated. Its normative byte
format is therefore now `u2 ir_index; u2 plan_index; u2 lines_index` - three
constant-pool UTF-8 references, `attribute_length` 6 - and the write side in
the emitter and the read side in `VarkaDebugInfo.read` moved together.

The validation is the milestone row's: the misdescribe hook fails the `AddDays`
call site at link time inside the loop, and the test asserts the resulting
frame carries the class's `SourceFile` name and a line whose key entry starts
with `AddDays`. That is a real kernel failure's shape, not a synthetic one.

### 2.2 Fallbacks name their kernel (sql/core)

`VarkaKernelEvaluator.kernelIdentity` renders the kernel the way its telemetry
names it - the `SourceFile` name plus the IR it computes - without forcing
emission, and all three ghost-fallback warnings (emission failure in the
evaluator, per-batch kernel failure in both exec nodes) now carry it. The
`SourceFile` name moved into a `sourceFileName` helper shared by the warning
path and `FusedRunner`, and it reads the stage as -1 outside a task rather
than throwing, so diagnostics work off the task thread too.

### 2.3 The class reaches disk (sql/core, one config)

`spark.sql.codegen.varka.classDumpDirectory` (internal, unset by default)
writes each emitted class under its `SourceFile` name, so `javap -c -p` reaches
a generated loop with no debugger. It follows the established path for Varka
configuration: read on the driver in the exec nodes, passed to the evaluator
like `offHeapColumnVectorEnabled` rather than read from `SQLConf` in the task.
Every failure is logged and swallowed - a query must not fail over a debug
write - and tasks of one stage emit identical bytes for one projection, so they
overwrite one file rather than racing to distinct ones.

### 2.4 `EXPLAIN` says why an entry did not fuse (catalyst + sql/core)

The compiler now reports at the point of failure: a `DeclineSink` threaded
through `compileNode`/`compileCond` records the *first* note, which is the
innermost cause rather than the outermost expression that inherited it, and
`compilePartial` hands the reason for each declining entry to
`PartialVarkaProjection.declines` (position-keyed, diagnostics only - no
execution path reads it). The vocabulary is deliberately small: unsupported
expression, day offset is not a foldable literal, CASE WHEN without an ELSE
branch, unsupported predicate, non-date column of type X.

Two decisions worth recording. The recursion works on *bound* expressions,
whose `BoundReference`s render as `input[1, int, true]`; the sink puts the
child's attributes back before keeping the text, so a reason reads in the
query's own column names. And `ResidualOutput` stayed a case object - the
reasons live in a parallel map on `PartialVarkaProjection` - because turning it
into a case class would have churned every pattern match in the evaluator and
the exec nodes for a diagnostics-only field.

`VarkaFusionReport` renders one line per entry ("a: fused", "i: forwarded from
i", "inc: residual (unsupported expression: (i + 1))") and both exec nodes
print it from `verboseStringWithOperatorId` in the house style
(`ExplainUtils.generateFieldString`), beside Output and Input. The evaluator
logs the same account once per task at debug level.

## 3. Verification

* `VarkaLoopEmitterSuite`: the line numbers index the recorded key across all
  four body methods (dense and masked, loop and tail); a kernel failure's stack
  frame resolves to the IR node that threw; the telemetry round trip now also
  asserts the key. Green at the preferred width and under
  `-XX:MaxVectorSize=16`.
* `VarkaKernelEvaluatorSuite`: the dumped class is byte-identical to
  `emittedClassBytes` and still parses (which is what makes `javap` on it worth
  anything); the fusion report names each entry's fate; a non-foldable offset
  and a missing ELSE report their own reasons.
* `VarkaProjectExecSuite`: the fallback warning names the kernel
  (`Varka_Project_Stage*` and the IR) through a log appender; verbose `EXPLAIN`
  accounts for all three entries of the mixed projection with the residual
  entry's reason.
* Full sweep: the Varka suites in both modules, scalastyle on both modules'
  main and test sources, `dev/lint-java`, the 100-char and ASCII scans.

## 4. Explicitly out of task 16

* **The heavier debuggability ideas** stay scoped in `PLAN_MILESTONE_3.md`
  section 14: fallback-cause metrics in the SQL UI (which now has this task's
  decline vocabulary to speak), loop-method grouping recorded in the debug
  attribute, a field differential mode, JFR events, and distinct generated
  class names (tied to the cache's key).
* **Reconciling the line map with a cross-task byte cache** - the same
  reconciliation the `SourceFile` name and the IR already owe milestone 3's
  cache item, which the diagnosis in `PLAN_TASK_14.md` 7.5 reshaped around
  loaded-class reuse.
* **A decline reason for the emitter's own caps** (chain depth, node count,
  input count): those reject the whole kernel at emission, not an entry at
  compile time, and they already surface through the emission-failure warning -
  which now names the kernel.
