# Task 13: telemetry

**Status: DONE.** See `PLAN_MILESTONE_2.md` (2.8) for the milestone context.
Unlike tasks 9-12 this task had no pre-written plan - the milestone's 2.8 and
its validation row were the whole spec - so this file is a record of what was
built and the decisions made along the way, in the same voice as the other
task files' outcome sections.

## 1. What the task is for

A Varka-fused query runs bytecode that exists nowhere on disk and carries a
fixed, meaningless name: every profile, stack trace or heap dump that crosses
the generated `run` says `VarkaFusedProjection` and nothing else, and once two
plans (or two stages of one plan) fuse different projections, that name cannot
distinguish them. Task 13 bakes the identity into the class bytes themselves,
where every JVM tool already looks:

* a **`SourceFile` attribute** named for the operator and stage
  (`Varka_Project_Stage3.java`), so a stack frame through the generated code
  names the plan node with no mapping table; and
* a **`VarkaDebugInfo` custom attribute** carrying the vector IR and the plan
  fragment it was compiled from, so a captured class is self-describing.

Both are class-file metadata the JVM ignores by specification (unrecognized
attributes must be skipped), so they cost nothing at load, JIT or run time -
the emitted methods are byte-identical to before.

## 2. What was built

### 2.1 The attribute and its reader (catalyst, Java)

`VarkaDebugInfo` extends `java.lang.classfile.CustomAttribute` with an
`AttributeMapper` whose payload is two constant-pool UTF-8 references -
`u2 ir_index; u2 plan_index` - which makes its stability `CP_REFS` and its
attribute_length a constant 4. One implementation lesson worth recording: the
mapper's `writeAttribute` must write the entire attribute structure, the
six-byte name-and-length header included (the JDK's built-in mappers all do),
while `readAttribute`'s position points at the payload *after* that header.
The first cut wrote only the payload and produced a truncated class file; the
verification test caught it immediately.

`VarkaDebugInfoReader` is the diagnostics helper of the milestone's 2.8: given
raw class bytes it returns the `SourceFile` name, the rendered IR and the plan
fragment as plain strings (null when absent), registering the mapper and
parsing with `ClassFile.parse` internally. It exists as a separate
plain-signature class for the same reason `VarkaLoopEmitter` is Java: scalac
cannot typecheck the Class-File API's sealed hierarchy ("illegal cyclic
reference"), and `VarkaDebugInfo` itself is inside that hierarchy through its
supertype, so no Scala code - the evaluator and both test suites included -
can mention the attribute type or any `java.lang.classfile` type. Every
Scala-facing surface is a plain `byte[] -> String` signature.

### 2.2 The emitter carries them (catalyst, Java)

`VarkaLoopEmitter.emit` gained a six-argument form taking `sourceFile` and
`planFragment` strings and attaching both attributes in the class build; the
IR string is rendered by the emitter itself from the output trees
(`outputs=..., numInputs=..., numLiterals=...` - the records' structural
`toString`, faithful because the IR is records all the way down). The
four-argument form remains for callers holding no plan - tests and the parity
benchmark - defaulting the `SourceFile` to the class's own simple name and the
plan fragment to empty, so every emitted class carries the attributes.

### 2.3 The evaluator names the class (sql/core)

`VarkaKernelEvaluator` took a fourth constructor argument, `operatorName`
("Project" from `VarkaProjectExec`, "ProjectToRow" from
`VarkaColumnarToRowExec`, supplied inside the evaluator factories), and its
`FusedRunner` builds the `SourceFile` as
`Varka_<operatorName>_Stage<stageId>.java`. The stage id comes from
`TaskContext.get().stageId()` - the runner is constructed lazily inside the
task, which is exactly where that identity is available; the plan node itself
carries no stage id at emission time. The plan fragment is the whole
`projectList`, forwarded and residual entries included, so a captured class
shows the fused entries in their context rather than an unrecognizable
subset. The emitted bytes are kept for the runner's (task's) lifetime behind
`emittedClassBytes`, so diagnostics read the attributes off exactly the bytes
that ran.

### 2.4 The slot-planning debt sweep (the register's fifth item)

`PLAN_MILESTONE_3.md`'s debt register recorded three cosmetic untidinesses in
the emitter's slot planning, to be swept "in task 13's emitter visit". All
three are done:

* **Dead cross-role slots.** `planSlots` now takes the body role (`BodyMode`)
  and plans per-node slots only for the role that emits them: the vector-walk
  slots (words, CSE, condition pairs, operand temporaries) for a loop method,
  the scalar-tail slots for the tail method, neither for the driver, which
  runs only the shared prologue. The literal-broadcast hoist slots moved under
  the same gate, which also let the emit-side condition collapse to
  `broadcastSlot != null`.
* **Per-group validity words.** The masked vector loop computed a validity
  word per lane group for every input any output references; a loop method
  only reads the columns of its own group's subtrees, and now computes the
  word union-gated on exactly those. Unlike the other two items this one was
  work in the hot loop, not just dead slot numbers - though never measured as
  a cost, since multi-group kernels with disjoint column sets are wide by
  construction.
* **The fixed-slot convention.** `DATA_BYTES = 8` / `VALIDITY_BYTES = 10`
  coupled `planSlots` and `emitBody` through hard-coded slot numbers; the two
  are now ordinary `Slots` fields allocated like every other slot.

The bodies the three sweeps produce are semantically identical; the full
emitter differential matrix (36 catalyst tests) is the regression net.

## 3. Verification

* `VarkaLoopEmitterSuite`: the milestone's named validation - emit with an
  explicit `SourceFile` and plan fragment, parse the bytes back, and assert
  both attributes round-trip through the diagnostics reader; a mapper-less
  parse still sees the attribute under its name (the third-party-tool view);
  the class with attributes passes class-file verification; and the
  four-argument form derives its defaults. Every pre-existing test now also
  runs over attribute-carrying classes via those defaults.
* `VarkaKernelEvaluatorSuite`: end to end off the production path - project a
  mixed batch, take `emittedClassBytes`, and assert the `SourceFile` names the
  operator and the task's stage and the debug attribute carries the fused IR
  and the full projection (the residual entry's alias included).
* Full sweep: 36 catalyst + 81 sql/core Varka tests green; scalastyle on both
  modules' main and test sources; `dev/lint-java`; the 100-char/ASCII scans.

## 4. Explicitly out of task 13

* **Reconciling telemetry with byte caching.** The attributes bake the stage
  into the class bytes, which a milestone-3 cross-task cache would replay
  verbatim for another query. That conflict was declared part of the cache's
  design in the milestone plan's 2.7 (`PLAN_MILESTONE_3.md`, item 2), not
  anticipated here - `VarkaDebugInfo`'s doc points at it.
* **A config to disable emission of the attributes.** They are a few hundred
  bytes per class, one class per task; a knob would cost more than it saves.
* **Task 14's benchmark and docs refresh**, including the question the task 12
  outcome handed it (row-consumer read-back vs chain depth).
