# VARKA-159: refactor for readability, with the bytes oracle as the proof

*Opened 20 September 2026 from the owner's review of the week's pull requests:
some classes are long enough that a reader cannot hold them, and the project
is about to be read by outsiders. The plan names the seams file by file, states
one acceptance test for every step, and orders the steps by value per risk.*

## 1. Why now, and what it costs today

The sizes on master (lines): `VarkaLoopEmitter.java` 6860 with 86 methods,
`VarkaExpressionCompiler.scala` 2211, `ArrowCachedBatchSerializer.scala` 1775,
`VarkaKernelEvaluator.scala` 1527; among the tests `VarkaLoopEmitterSuite.scala`
5297. The emitter's largest units are one record of 790 lines, `emitBody` at 284,
`emitValue` at 248 and `planSlots` at 245; the compiler's `compileNode` is one
match of 478 lines.

The cost is not abstract. Two nodes were added this week, `NarrowLane` (#268)
and `BoundedDivide` (#274), and each touched about twelve sites in the emitter
- `childrenOf`, two word-owner maps, `analyze`, the word reference, two
liveness switches, `emitValue` - plus the IR, the range lattice, the reference
evaluator, the grammar and three suites. Two of those sites were found by a
clean compile after the incremental build had passed, one by the emitter's own
liveness invariant, one by the fuzzer. `m5/PLAN.md` 2.13 measured the
same thing for VARKA-63 and drew the rule that the expensive decision is the one
farthest from an exhaustive match. `VarkaEmitOptions` shows the other pattern:
24 components and 24 copy methods that each spell all 24 arguments, so adding a
component is an edit to 23 calls.

## 2. The acceptance test, stated once

Every step is a refactor in the strict sense: no emitted byte changes. The
repository already has the instrument, and it is the reason this task is safe
to do at all.

- `sql/varka/emitted_bytes.json` does not move: not a coverage key, not a fuzz
  block, at either width. Checked by the flattened-key diff the emitter lesson
  describes, not by the suite's pass alone.
- No shape hash moves: `VarkaShapeCacheSuite`'s committed hashes and
  `VarkaEmitOptions.canonical()` render as before.
- No committed results file, band file or provenance changes; no benchmark is
  rerun for a refactor, since the bytes prove the kernel is the same.
- The full Varka suites of `catalyst` and `core` pass, `dev/scalastyle`,
  `dev/lint-java` where it applies, the quote check and the docs check pass,
  and `docs/sql-varka.md` does not change except where a step moves a
  documented name.
- Every moved member keeps its comment, and every new file opens with the
  comment that says what it is for, per the house rule for new readers.

A step whose oracle diff is not empty is not a refactor and stops until the
difference is explained in the step's PR.

## 3. The steps

### 3.1 `VarkaEmitOptions` as a record with a builder (small)

`toBuilder()` returning a mutable builder with one setter per component and
`build()` calling the canonical constructor, replacing the 24 `with*` copy
methods; the callers' `withX(v)` become `toBuilder().x(v).build()` or keep thin
`withX` wrappers that delegate. `canonical()` renders the components by walking
the record's components in declaration order, which is what
`VarkaShapeCacheSuite` already asserts each component can change. The
rendering string for every existing value is byte-identical, which the shape
hashes prove.

*Done 21 September 2026.* `toBuilder()` and a nested `Builder` with one field
and one setter per component; the twenty-five `with*` methods stay, each one
line delegating to the builder, so the 167 call sites in the suites and
benchmarks do not change. `canonical()` stays hand-pinned: the shape cache
suite already walks the components and fails if one cannot change the
rendering, which is the guard that matters, and a reflective walk would add a
dependency for no reader's benefit. Adding a component is now the record
component, the builder's field and setter, one argument in `build()`, the
rendering and the default, instead of an edit to every copy method. The bytes
oracle, the shape hashes and the emitter suite are unchanged.

### 3.2 `VarkaLoopEmitter` split by its own phases (large; one PR per seam)

The file's section headers are the plan. In order of least entanglement:

1. **The lane and the descriptor table** (lines around 474 to 1160 on master):
   `Lane`, the descriptor constants, `speciesField`, `emitLanes`, the
   validity-helper naming, into `VarkaLane.java`. Package-private; the emitter
   keeps calling them by the same names.
   *Done 21 September 2026, as two files rather than one:* the descriptor
   table (the `ClassDesc` and `MethodTypeDesc` constants and the `run`
   method's parameter slots) is `VarkaDescriptors.java`, a final class the
   emitter and the lane import statically, so no use site changes; the enum is
   `Lane.java`, top level under its own name, since renaming it to `VarkaLane`
   would have touched a hundred sites for no reader's gain. Two visibility
   changes and nothing else: the enum's `laneType` field and its methods went
   from private to package-private, and the three suite references to the
   nested name became the top-level one. The emitter lost 435 lines; the
   bytes oracle, the shape hashes, the emitter suite and the javadoc build are
   unchanged. `speciesField`, `emitLanes` and the validity-helper naming stay
   where they were: `speciesField` is the lane's and moved with it, the other
   two are body-emitter code and belong to seam 6.
2. **The weights and budgets** (around 266): the calendar op weights,
   `GROUP_BUDGET`, `FUSED_CEILING`, `fitsBudgets`' arithmetic, into
   `VarkaEmitBudget.java`.
   *Done 21 September 2026.* `GROUP_BUDGET` and `FUSED_CEILING`, the
   per-lowering weights and temporary-slot counts, `weightOf` and `isChrono`
   are `VarkaEmitBudget.java`, imported statically by the emitter; `fitsBudgets`
   stays on the emitter as the entry the compiler calls, and the three
   validation limits (`MAX_CHAIN_DEPTH`, `MAX_FUSED_NODES`, `MAX_INPUTS`) stay
   with the validation they bound, which is seam 3's. The moved constants went
   from private to package-private; the options' default and the emitter
   suite's references name the new class. The javadoc that linked emitter
   members from the moved text names them as code instead of linking, which is
   shorter than the link it replaces, so no comment line moved. The emitter
   lost 343 lines; the bytes oracle, the shape hashes, the suites and the
   javadoc build are unchanged.
3. **The analysis** (around 1703 to 2538): `Analysis` with the validation, the
   DAG walk, the word algebra, the bitmap pass, the fragment keys, into
   `VarkaEmitAnalysis.java`, with the 790-line record broken into the passes
   it already runs in sequence (`analyzeRoot`, `collectArmContexts`,
   `collectGuardedProducers`, `planWordAlgebra`, `planBitmapPass`).
   *Done 22 September 2026, as `Analysis.java`* under the name the emitter
   already used, like `Lane`: the class with the validity-word algebra's types
   (`WordOwner`, `WordExpr`, `BitmapPass`) nested in it, since the emitter
   reads them through the analysis and nothing else does. The passes were
   already methods called in sequence from `emit`; splitting the class further
   was not needed to read it. What crossed the file boundary: four emitter
   helpers the analysis calls (`childrenOf`, `chronoChild`, `emitLanes`,
   `isDayOffsetShape`) and two nested types it names (`ArmStep`, `Divider`)
   went from private to package-private; `referenced` stayed with the body
   emitters that call it. Unused imports were pruned on both sides for
   checkstyle. The emitter lost 833 lines, to 5401. The bytes oracle, the shape
   hashes, the emitter suite and the javadoc build are unchanged; the width
   census check failed on untouched master the same morning because the
   laptop's JDK had been updated overnight and the census compares its JDK
   string, which is a tooling fix of its own and not this seam's.
4. **Slot planning** (around 2539 to 3314): `Slots` and `planSlots` into
   `VarkaSlotPlan.java`.
   *Done 22 September 2026, as `Slots.java`*, again under the name the emitter
   already used: the frame layout as the class, `planSlots` as its static
   `plan`, and the planner's own helpers (the word-reference aliasing, the
   algebra cross-check, the guard predicates, the word liveness pass) private
   to it. The class comment says what the layout is and the two facts that
   shape it - slot numbers are pinned bytes, words are aliased before they are
   allocated - which the old nested class never said in one place. Two doc
   comments that had drifted onto the wrong methods (`guardedWord`'s onto
   `isDayOffsetShape`, `guardScratch`'s onto `reachesGuardedDay`) sit on their
   methods again. `isDayOffsetShape` stayed in the emitter: it is the shape rule
   the compiler shares, not slot planning, and only lived among it. What
   crossed the file boundary: `BodyMode`, `FragmentKind` and `FragmentKey`, the
   two word sentinels and seven emitter predicates the planner calls
   (`fragmentKey`, `keepsPerGroupWrite`, `reaches`, `referenced`,
   `takesMagicDivide`, `takesMulHiDivide`, `wordWrites`) went from private to
   package-private. The emitter lost 779 lines, to 4622. The bytes oracle, the
   shape hashes, the emitter suite and the javadoc build are unchanged.
5. **The lowerings by family** (from around 4270): the calendar family
   (`emitChronoPrefix`, `emitAddMonths`, `emitMakeDate`, `emitPick`, the
   `ChronoDivide` forms) into `VarkaChronoLowering.java`; the division family
   (`emitConstDivide`, the magic and multiply-high forms, `emitBoundedDivide`,
   `Divider`) into `VarkaDivisionLowering.java`; the stores, wide and narrowed,
   with the body emitters.
   *The division family done 22 September 2026, as `VarkaDivisionLowering.java`.*
   `Divider`, `emitConstDivide`, the multiply-high, magic-number and double
   forms with their predicates and `signedMagic`, in a class whose comment says
   what its two callers want from it. The line between the families is where
   the code already drew it: `ChronoDivide`, `emitDivide`, `emitMagic` and
   `emitCarry` are the calendar prefix's range-narrowed divisions and stay for
   the chrono move; the double forms are shared and live here, since the
   prefix's `emitDivide` calls `emitDoubleDivide` for them. `emitBoundedDivide`
   in the list above does not exist - the bounded division is an arm of
   `emitValue` two operations long, and stays one. A doc comment that had
   drifted off `emitDoubleDivide` onto `takesMagicDivide` sits on its method
   again. What crossed the file boundary: `emitValue`, `line`, `emitShift` and
   `ChronoDivide` went from private to package-private; the emitter, `Slots`,
   `Analysis` and the emitter suite name the new class where they call it. The
   emitter lost 416 lines, to 4206.
   *The calendar family done 22 September 2026, as `VarkaChronoLowering.java`.*
   `emitMakeDate`, the day-of-week arithmetic, `emitChrono` with the prefix,
   every field's tail, `emitAddMonths`, `emitDaysFromCivil`, the truncations,
   `emitEra`, and the prefix's own divisions (`ChronoDivide`, `emitDivide`,
   `emitMagic`, `emitCarry`), under a class comment that says what the prefix
   is and why every division here can name a `ChronoDivide`. `emitPick` stayed:
   the null-skipping `greatest`/`least` is not calendar code, it only sat next
   to it. What crossed the file boundary: `emitAndWord`, `emitRangeGuard`,
   `emitGuardCollect`, `loadWord`, `storeWord` and `tailReadsMarchMonth` went
   from private to package-private, and the five entry points `emitValue`
   dispatches to (`emitChrono`, `emitAddMonths`, `emitMakeDate`,
   `emitFloorMod7`, `emitModOffset`) are named with their class at the call
   sites. The emitter lost 1564 lines, to 2642.
6. **The body emitters** (around 3315 to 4269): `emitBody`, `emitLaneGroup`,
   the prologue, loop and epilogue, the driver, into `VarkaBodyEmitter.java`,
   which is what remains of the class besides the facade.
   *The body methods done 22 September 2026, as `VarkaBodyEmitter.java`*:
   `emitBody` with the three roles, the vector loop and the epilogue, the
   lane-group step, the validity reads and writes, the bitmap pass, the
   narrowed store, the status return, the telemetry renderers and the word
   invariant check, under a class comment that says what a driver, a loop
   method and an epilogue each are. The vector walk - `emitValue`, `emitCond`,
   the int arithmetic, the guards, the picks, and `line`, `loadWord`,
   `storeWord` that every consumer reads a word through - is the one region
   left besides the facade, and this list did not name it: it goes out as its
   own file next, under the name its banner already gives it. What crossed
   the file boundary: `invokeCall`, `planFragmentsReadingMonth` and `emitCond`
   went from private to package-private; the facade names the new class at
   its eight calls, `Slots` at its two. The emitter lost 920 lines, to 1722.
   *The vector walk done 22 September 2026, as `VarkaVectorWalk.java`*:
   `emitValue` and `emitCond`, the int arithmetic with its overflow masks, the
   guards and their arm contexts, the picks, `emitShift`, and `line`,
   `loadWord`, `storeWord`, under a class comment that says what the walk
   leaves on the stack and in the slots and which families it hands off. The
   body emitter and the two lowering classes reach it by a static import, the
   way they already reach the facade's constants; nothing else crossed the
   boundary, since seam 5 had already widened what the lowerings call. The
   facade's class comment lost its "largest file in the engine" opening for a
   map of the nine files the emitter now is, and keeps the design notes on the
   emitted class, which hold across them. The emitter lost 780 lines, to 942:
   `emit`, the budgets and grouping, the fragment keys, the class assembly and
   the entry-point validation.

`VarkaLoopEmitter` keeps `emit`, `fitsBudgets`, `bitmapPassCounts` and the
class-level javadoc that explains the whole, and becomes the map a reader
starts from. Each PR moves one seam and nothing else; a move that needs a
signature change to cross the new file boundary makes it, and the oracle says
whether anything else moved.

### 3.3 `VarkaLoopEmitterSuite` into suites by family (medium)

A `VarkaEmitterTestBase` trait holding `emitMulti`, `load`, `checkMatrix`,
`checkLongMatrix`, the input and output builders and the null patterns; the
tests move into `VarkaEmitterArithmeticSuite`, `VarkaEmitterChronoSuite`,
`VarkaEmitterDivisionSuite`, `VarkaEmitterLongLaneSuite`,
`VarkaEmitterValiditySuite` and `VarkaEmitterBudgetSuite`, each opening with
what its family is. The tests do not change; a targeted run stops paying for
the whole file.

*Done 22 September 2026*, as the trait and seven suites rather than six: the
tests of `emit`'s own contract - the refusals with a reason, the descriptor
failure, verification and unloading, the pinned renderings, the telemetry
attributes - fit none of the families and got `VarkaEmitterContractSuite`.
Every test kept its name and body; the 161 tests run as 155 passed and the
six opt-in sweeps cancelled, the same as before. A helper went to the base
when two families need it and to the family when one does, which put 52 in
the base (the matrices, the columns, the calendar boundary days that the
budget tests read too) and 29 in the families. The base's members are
`protected` where they were `private`. Every place that named the old suite -
the gate and nightly scripts' opt-in run, `ADDING_AN_EXPRESSION.md`, the
javadoc of the budget constants and the IR, the sibling suites' comments -
names the base or the family it meant. The sections below the base's doc
comment show the mapping: the suite that runs a family's tests is
`*VarkaEmitter<Family>Suite`, and `*VarkaEmitter*Suite` runs them all.

### 3.4 `VarkaExpressionCompiler` by expression family, with VARKA-86 (medium)

`compileNode`'s match and `compileTime` become one object per family - the date
calendar, the year-month intervals, the long lane and `TIME`, the predicates -
over the shared `DeclineSink`, `compilePartial` and the tables. VARKA-86's one
operand-admission table lands here, since it is the thing the families share,
and its first exercise (a bare int column in comparison position) comes with
it. In Scala: the split is not the moment to port, and no PR of this task
changes structure and language at once; a family at a time can be ported after,
against the same oracle.

*Done 22 September 2026, without VARKA-86.* Four family objects beside the
compiler: `VarkaChronoCompiler` (date arithmetic, the day-of-week nodes,
`next_day`, the extractions, `make_date`, `last_day`, the ISO week, `trunc`,
month arithmetic, with the range analysis that admits a day producer and the
weekday and trunc-level folds), `VarkaIntervalCompiler` (the year-month
interval leaves, casts and algebra), `VarkaTimeCompiler` (the long-lane and
TIME leaves and casts, `compileTime` and its target table) and
`VarkaConditionCompiler` (`compileCond` with the comparisons, `IN`, the
validity predicates and the connectives, and the value-side `IF`, `CASE WHEN`
and `coalesce`). Each exposes its arms of `compileNode` as a partial function
over the three tables, and `compileNode` chains them after the date leaves and
before the int arithmetic, the picks and the fallbacks it keeps. The chain is
order-safe because no expression matches arms of two families - every arm is
gated by the expression class or its data type - and the fallbacks stay last;
within a family the arms keep their original order. The families reach the
shared operand helpers and the recursion through an import of the compiler
object; the compiler qualifies the few family members it calls back
(`isDayOfWeekIso`, `andFold`, `foldPick`, `compileCond`, `compileTime`,
`timeTargets`). Behaviour did not change: the compiler suite, the coverage
suite and the bytes oracle pass unchanged, which is the proof; the coverage
suite's source scan of the arms reads the five files rather than one, and
said so itself when it found seventeen names instead of sixty. VARKA-86's
operand-admission table is a behaviour change, which section 4 keeps out of
this task, and it is a milestone 6 row now (`m8/SCOPE.md` item 39),
so it did not come along. The compiler went from 2271 lines to 862.

### 3.5 `VarkaKernelEvaluator` by responsibility (medium)

The Arrow admission (`isArrowBacked` and its vector-class table), the output
allocation, the derived-input fills, the run loop with its metrics, and the
compaction, each its own file behind the evaluator that composes them.

*Done 22 September 2026, as one class per file rather than one responsibility
per file.* `VarkaKernelEvaluator.scala` held five classes; it keeps the
projection evaluator with its companion object and the owned-vector wrapper,
and `VarkaEvaluatorBase.scala` (the task-lifetime machinery every evaluator
shares, with its two exceptions and the `Morsel` it hands the kernel),
`VarkaFilterEvaluator.scala` (the selection bitmap and the compaction, with
`VarkaSelection`) and `VarkaExecMetrics.scala` (the metric names) are files
of their own. The responsibilities the list above names are methods of the
base class over its own state - the allocator, the open-batch ledger, the
scratch slots, the runner - and pulling each into a file would mean a trait
or a helper object per responsibility with that state threaded through, which
is more structure than the reading needs: the base class reads in order,
admission, tracking, scratch, cleanup, fills, run, and is 815 lines. No code
moved inside a class; the imports of each file are what it uses.

### 3.6 `ArrowCachedBatchSerializer` one class per file (small)

The five iterators and the shared `ArrowColumnReader` into files of their own,
the serializer keeping the entry points.

*Done 22 September 2026.* Four iterators, not five - the row and the columnar
writers, the columnar and the row readers - each in its own file, and
`ArrowColumnReader` with its companion in a fifth; the serializer keeps its
class and companion object, 1007 lines of 1775. Every class was already a
top-level package-private class, so nothing changed but the file it sits in
and the imports, which each file now limits to what it uses. The gate's
`*ArrowCachedBatchSerializerSuite` and the CI scope, which is by directory,
name nothing that moved.

### 3.7 One place per node (large; last)

Task 2.13's finding made a rule; this step makes it structure. Each IR node's
emitter knowledge - its children, its word rule (own, child's, pure), its
value emission, its range rule - in one class behind a sealed interface, so
that adding a node is one class and the compiler still refuses a missing part.
The thirteen switches over the IR become one dispatch. It goes last because
3.2 has to make the sites visible first, and because it is the one step that
changes how the emitter is read rather than where.

## 4. What this task does not do

No behaviour changes, no lowering changes, no option default changes, however
tempting a nearby improvement looks; those are rows of their own and a refactor
PR that carries one cannot be proved by the oracle. No member reordering beyond
the move itself. No port to Java inside a split. No renaming of the public
entry points the evaluator and the tests call.

## 5. Sequencing and size

3.1, then 3.2 seam by seam, then 3.3, 3.4 with 86, 3.5, 3.6, 3.7. The first two
are the ones the week's work argued for; 3.1 is an afternoon and a good first
PR, 3.2 is the bulk. Every step waits for the pull requests open at the time of
writing (#267 to #275) to merge, since each of them touches the emitter or the
compiler and a move under an open change is a conflict for both.

## 6. Risks

- **History.** A moved method loses `git blame` continuity; the plans and
  `SKILLS.md` carry the history, and a move commit names the source file.
- **Visibility.** `private static` becomes package-private across the new
  files; nothing outside the package gains access, and the test support class
  already lives in the package.
- **The per-node step.** One class per node can hide a cross-cutting rule (the
  word algebra reads several nodes at once); 3.7 keeps such passes as passes
  and moves only what is genuinely per node.
- **Open work.** Anything in flight against the emitter has to land or rebase;
  section 5 sequences for it.

## 7. Outcome

*Written 22 September 2026, with the last seam's pull request.*

Steps 3.1 to 3.6 landed, one pull request per seam and each a pure move: the
options builder; the emitter's six seams, which left `VarkaLoopEmitter` a
942-line facade over `Slots`, `Analysis`, `VarkaDivisionLowering`,
`VarkaChronoLowering`, `VarkaBodyEmitter` and `VarkaVectorWalk`; the emitter
suite by family; the expression compiler by family, with VARKA-86's coverage
scan over the five files; the evaluator by responsibility; and the serializer
one class per file. The acceptance test of section 2 held at every step:
`emitted_bytes.json` and the shape hashes unchanged under the flattened-key
diff, no results file, band or docs table moved, and every new file opens with
its purpose.

Step 3.7, one place per node, is not done here. It is the one step that is not
a move, and it is deferred to milestone 6 as `m8/SCOPE.md` item 47,
which records what was learned about it on the way: the passes that read
several nodes at once are the part a per-node dispatch cannot absorb.
