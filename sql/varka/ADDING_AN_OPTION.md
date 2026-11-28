# Adding an emit option to Varka

This page describes today's way; row 642 of `plans/m9/PLAN.md` will declare an option in one
place, and that row updates this page.

An emit option is one component of the record `VarkaEmitOptions`: a switch that makes the emitter
write a kernel in another form. Tests, the fuzzer, benchmarks and the option matrix set it; a SQL
session reaches only the few that step 7 names. The steps below add a boolean option, called
`demoOption` here. The sibling to copy is `uncountedMainLoop`, the newest boolean option
(`git log -1 -SuncountedMainLoop --format=%h -- '*VarkaEmitOptions.java'` finds its commit).

`E` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/` and
`T` is the same package under `sql/catalyst/src/test/`, in `java/` or `scala/`. Every `build/sbt`
and `dev/varka_matrix.sh` line below starts a JVM and takes no lock; on a machine shared with
other agents read `dev/varka_ops/AGENTS.md` before running one.

## What the default must satisfy

Decide this first, because it decides how much the change moves.

* **The default keeps every emitted kernel as it is** (a new arm, off by default; the usual case).
  Then no committed shape hash, byte count or audit line moves, and no generated file changes.
  The reason is in the class comment of `E/VarkaEmitOptions.java`, "Defaults hash to what they
  always hashed", and in the comment on `canonical()` there.
* **The default changes kernels** (new behaviour on, the old form kept as the other value). Then
  the generated files move and step 6 applies, and the old behaviour must stay a value the option
  matrix runs (step 5). `plans/TEMPLATE_TASK.md` 3.1 gives the rule: the new form goes behind the
  option, and the default flips in the task's last commit.

In both cases the option's tag (step 2) must show nothing at the default, so that the defaults
render as the empty string and every older variant renders as it did before.

## The path

**1. Declare the option** in `E/VarkaEmitOptions.java`, at six places, each time after
`uncountedMainLoop`, the last component:

| place | what to add |
| :--- | :--- |
| the record's Javadoc | `@param demoOption ...`: what both values emit, and why the default |
| the record header | `boolean demoOption` as the last component |
| `DEFAULTS` | the default as the last argument (the call has no names: append only) |
| `toBuilder()` | `b.demoOption = demoOption;` |
| `Builder` | the field, the setter `Builder demoOption(boolean)`, the last argument of `build()` |
| the `with*` methods | `withDemoOption(boolean)`, which is `toBuilder().demoOption(v).build()` |

> *Check:* `build/sbt catalyst/Test/compile` passes.

**2. Add the table entry** as the last element of `TABLE` in `E/VarkaEmitOption.java`:

    new Flag("demoOption", Reason.REFERENCE, false,
        VarkaEmitOptions::demoOption, Builder::demoOption,
        new FlagTag("demoOption", true)),

Choose the `Reason` from the enum's Javadoc in that file; it also decides whether the fuzzer and
the matrix draw the option (`Reason.drawn()`). A new option never renders `POSITIONAL`: that is
for the 26 options that predate the tags. `FlagTag(token, when)` shows `|token` when the value
is `when`, so `when` is the non-default value. If the two values differ only under other options,
or only one node kind reads the option, pass a `Subject` or `readOnlyBy` as `roundToOddByShifts`
does. Put a comment above the entry that says why the option exists.

> *Check:* `build/sbt 'catalyst/testOnly *VarkaEmitOptionSuite *VarkaShapeCacheSuite'`. The first
> fails until the table equals the record in order and `DEFAULTS` equals the table; the second
> fails if the option cannot change the rendering that reaches the shape hash.

**3. Read the option** where the emitter decides the form (`analysis.options.demoOption()` in
`E/VarkaBodyEmitter.java` is how the sibling is read). An option nothing reads passes every suite.

> *Check:* `dev/varka_emit.sh "year(d)" --options demoOption=true` prints other bytes or op counts
> than the same line without `--options`, for an expression the option should change.

**4. Test both values** in a suite named `Varka...Suite`, so the matrix finds it:
`T/java/.../VarkaUncountedMainLoopSuite.java` is the model (the emitted structure under the arm,
and every answer equal to the reference's). Start from `VarkaMatrixRuns.BASE` (`VarkaMatrix.base`
in Scala), not `VarkaEmitOptions.DEFAULTS`. In `VarkaEmitOptionSuite`, the test "the reasons the
table records" pins the reason: add the option's line there, as the sibling has.

> *Check:* `build/sbt 'catalyst/testOnly *VarkaDemoOptionSuite *VarkaEmitOptionSuite'`.

**5. The option matrix and the hand-kept lists.** The matrix configurations are not declared
anywhere: `VarkaMatrixRuns.configurations()` derives them from `TABLE`, so a drawn flag gets the
configuration `demoOption=<non-default value>` by its table entry (`dev/varka_matrix.sh --list`
prints them, after a build). The `varka-option-matrix` workflow, the script and the tables under
`sql/varka/matrix/` need no edit: a configuration with no timing line is weighed at the median.
Four lists are kept by hand and no test compares them with what the option does, so decide each:

| list | add the option when |
| :--- | :--- |
| `C2_OPTIONS` in `VarkaMatrixRuns.java` | it changes what C2 compiles, not only the bytes |
| `FULL` in `VarkaTestReach.java` | code other than the compiler and the emitter reads it |
| `pairGroups` in `VarkaEmittedBytesSuite.scala` | it reads what a group there reads |
| `sql/varka/matrix/skips.tsv` | a test fails under its configuration by design |

Each entry carries its reason, as its neighbours do; a `C2_OPTIONS` entry also gets a `changesC2`
line in `VarkaMatrixSuite`. The first three files are under `T`. Never add the option to
`streamOptions` in `VarkaIrFuzzSuite`: that list is frozen, and the fuzzer draws a later option
from a stream of its own.

> *Check:* `build/sbt 'catalyst/testOnly *VarkaMatrixSuite *VarkaIrFuzzSuite'`, then
> `dev/varka_matrix.sh --config demoOption=true --suites VarkaDemoOptionSuite`. The pull request's
> option matrix runs the configuration over every suite (`AGENTS.md`, "Measurements, not
> adjectives").

**6. Regenerate, if the default changed a kernel** or the option's `Subject` is not the defaults
(its arms are then pinned in `emitted_bytes.json` under `option_arms`). Run
`dev/varka_regen_generated.sh --all`: it runs the JVM steps in one sbt call, takes the heavy
lock itself, and skips them when nothing they read changed (`--dry-run` prints the steps). The
bytes file alone is `VARKA_BYTES_REGEN=true build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite'`.
An arm that no session selects can be pinned by hand in that suite's `pinnedArms`, as
`maskFromWord=true` is. Never edit a generated file. After a change to what the emitter writes,
also do the AVX2 recheck that `AGENTS.md` describes.

> *Check:* `build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite'` with no variable set, and
> `git status` shows no generated file changed when the default kept every kernel.

**7. A session setting, only if a session must select the option.** There are two surfaces: the
emit option, and a `spark.sql.codegen.varka.*` configuration a user sets. Three options have
both (`useAVX`, `exactRefusalStatus`, `mathUlpContract`); every other option is test-only and
gets no configuration and no line in `docs/sql-varka.md`. If the option needs one, copy
`exactRefusalStatus`: the entry in `SQLConf.scala` (with `.withBindingPolicy`, which
`dev/varka_lint.py` requires) and its accessor, the binding in
`sql/core/src/main/java/org/apache/spark/sql/execution/VarkaSessionEmit.java`, the text in
`docs/sql-varka.md`, a session test, and a pinned arm (step 6): the class comment of
`VarkaEmitOptions` says a field that gains a configuration gains a pinned arm.

> *Check:* `git grep -n -i exactRefusalStatus -- ':!sql/varka/plans'` lists those places.

**8. Check that nothing was missed.** Run the sibling search over the whole tree:

    git grep -n -i uncountedMainLoop
    git grep -n -i demoOption

The rule: every hit of the first search has the new option beside it in the second, or is on
this list of places that need nothing:

* `T/java/.../VarkaUncountedMainLoopSuite.java`, `E/VarkaLoopStride.java` and the reading site in
  `E/VarkaBodyEmitter.java`: the sibling's own suite, helper and consumer (yours are steps 3, 4);
* `C2_OPTIONS` and the `changesC2` line in `VarkaMatrixSuite`: only when step 5 says so;
* `dev/varka_kernel_history.sh` and `T/java/.../VarkaKernelHistoryProbe.java`: examples in
  comments of a tool the sibling was built for;
* `sql/varka/plans/` and `sql/varka/skills/`: the sibling's task record (yours is your task's
  plan file, and a skills section if the work taught a lesson).

`DEFAULTS` holds no names, so no search finds it: `VarkaEmitOptionSuite` checks it (step 2).

> *Check:* the two searches, then `dev/varka_gate.sh`, the standing gate.
