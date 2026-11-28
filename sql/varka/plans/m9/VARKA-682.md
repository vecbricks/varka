# VARKA-682: `ToLocal` and its batch arguments

*Planned on 26 November 2026. This plan stands alone: a builder needs this file and the
repository's `AGENTS.md` files, nothing else. Not started: milestone 9 waits for the owner's
word (`m9/PLAN.md` decision 18). The code facts below were checked on master `176db41f369`.*

## 1. What this row is

Row 682 of `sql/varka/plans/m9/PLAN.md`: "`ToLocal` and its batch arguments". It adds the IR
node that turns an instant into local time in one zone, and everything that lets a kernel read
values that change with each batch: where they live, who fills them, and what happens when
they cannot be filled.

What it gives the rows after it:

- row 657 (the session-zone expressions) builds `ToLocal` in its compiler arms and wires the
  zone data of row 678 into the hook this row leaves;
- row 684 draws `ToLocal` in the fuzzer's grammar; rows 685 and 686 add the forms `LADDER` and
  `TABLE` to the option this row adds; row 690 adds `ToInstant` to the sealed family `Zoned`;
- row 688 lifts the refusal of a computed operand; row 689 adds `zoneStepMask`; row 691
  measures the forms on expressions.

What must be merged before it starts:

- the family design, `sql/varka/plans/m9/VARKA-657.md`, on master (parts a to f cite it);
- nothing else for parts a to d;
- row 683 (`LongRangeOps.leastAndGreatest`) before part e.

The goals it serves (`m9/PLAN.md` section 1.1), each with the item of section 1.2 it moves:

- G1, item 1 (at least three quarters of `TIMESTAMP_LTZ`'s pairs fused): only through row 657
  and the rows after it. This row compiles no SQL expression. `m9/PLAN.md` 3.1 lists the row
  under G1 alone.
- G2, item 2 (the every-zone sweep passes in CI): only through row 684, which sweeps this
  row's lowering.
- G5, item 8 (the share of pull requests the cheaper models built, from row 658's ledger):
  only if the owner gives parts of this row to such a model (section 8).
- G7, item 10 (five times the row path where a kernel reads the zone): only through row 691.
  This row measures the fill and the kernel alone.

## 2. What an agent must know

**The idea.** A timestamp with local time zone is a count of microseconds since the epoch in
UTC, held in a long. Local time is that count plus the zone's offset at that instant. The
offset changes at a few instants, the zone's changes (a daylight-saving step, a law). Inside
one batch the values usually span no change, or one or two. So the kernel does not look up a
zone table per row. Before the kernel runs, the runner finds the least and the greatest value
of the column, asks the zone's data for the changes inside that range, and writes them into
the kernel's argument array. The kernel adds the first offset to every row and, for each
change, adds a difference to the rows at or after that change.

**Terms.**

- *Instant*: microseconds since the epoch in UTC, a long.
- *Zone index*: the position of a zone id in the compile result's zone list. The IR holds the
  index, never the id, so two sessions in different zones share one kernel.
- *Batch argument*: an entry of the kernel's `long[] longArgs` that the runner writes before
  each call. Batch arguments follow the literals in that array.
- *Program*: the run of batch arguments of one zone node: its first index and its count.
- *Form*: how the kernel applies offsets. `ONE` adds one offset. `STEPS_2` and `STEPS_8` add
  one offset and up to two or eight differences.
- *Step*: one change, as a pair `at` (the instant of the change) and `delta` (new offset minus
  old offset, in microseconds).
- *Padding*: a step that changes nothing: `at = Long.MAX_VALUE`, `delta = 0`.
- *Decline*: the runner gives the batch to Spark's own code path and counts the reason.
- *Refusal*: the compiler does not build a kernel for the expression at all.

**The kernel's signature.** `VarkaFusedKernel.run(srcData, srcValidity, srcNullCount, dstData,
dstValidity, int[] scalarArgs, long[] longArgs, length)`. A kernel whose lanes are all long
reads every literal from `longArgs`. A kernel of two lanes keeps its int literals in
`scalarArgs`, and the runner's `longArgs` there is only as long as the long table. The shape key
counts literals as `numLiterals`: both literal tables together. Batch arguments start at index
`numLiterals` of `longArgs` in every kernel, so the runner pads `longArgs` to that length first
(`argsLength`); the emitter and the runner must both use `numLiterals`, never the long table's
size.

**The names.** "Reads" exists on master; "adds" is from the family's design, `VARKA-657.md`
section 3.

| Name | Reads or adds | Meaning |
|---|---|---|
| `VarkaVectorIR` | reads | the sealed IR interface; each node is a record with a cached `hash` |
| `VarkaVectorIR.Zoned` | adds | `sealed interface Zoned extends VarkaVectorIR`, with `int zone()` and `VarkaVectorIR operand()` |
| `VarkaVectorIR.ToLocal` | adds | `record ToLocal(VarkaVectorIR instant, int zone, int hash)`: instant to local time |
| `VarkaBatchArgs` | adds | the layout of the batch arguments: `Program`, `layout`, `count` |
| `VarkaBatchArgs.Program` | adds | `record Program(Zoned node, int first, int count)` |
| `VarkaZoneLowering` | adds | the emitter's lowering of zone nodes |
| `VarkaEmitOptions.ZoneForm` | adds | `ONE`, `STEPS_2`, `STEPS_8`; rows 685 and 686 add `LADDER` and `TABLE` |
| `zoneForm` | adds | the emit option; default `STEPS_2` |
| `CompiledVarkaProjection.zones()` | adds | `List<String>`: the zone ids, by zone index |
| `VarkaZoneData` | adds | one zone's changes: `id`, `fixed`, `changes`, `localChanges`, `toLocalArgs`, `toInstantArgs` |
| `VarkaZoneData.Provider` | adds | `VarkaZoneData of(String zoneId)` |
| `VarkaJavaTimeZoneData` | adds | the reference `VarkaZoneData` over `java.time`, in test scope |
| `STATUS_BATCH_ARGS` | adds | the runner's decline status for a batch whose arguments cannot be filled |
| `LongRangeOps.leastAndGreatest` | reads (row 683) | `boolean leastAndGreatest(long srcData, long srcValidity, int srcNullCount, int length, long[] out)`; false when no row is live |
| `VarkaBatchArgsSuite`, `VarkaEmitterZoneSuite`, `VarkaBatchArgRunnerSuite` | adds | the row's suites |

The arguments of one `ToLocal` program, in order: `base`, then `at[0]`, `delta[0]`, `at[1]`,
`delta[1]`, and so on. Its count is `1 + 2 * steps`, where `steps` is 0, 2 or 8 by form.

**What the probes measured.** On the laptop, ns a row at 8, 4 and 2 lanes of 64 bits: one
offset 0.078, 0.077, 0.088; two steps written out 0.086, 0.148, 13.020; eight steps 0.289,
0.755, 62.542; the pass for least and greatest 0.058, 0.232, 0.393; `java.time`'s offset
lookup at best 10.293, 10.285, 10.956. Source:
`sql/varka/spikes/zone-offset/results/forms.txt`. Two lanes are slow because a masked add of
two long lanes has no intrinsic there; row 689 answers that, not this row.

## 3. The admission check

Paths: `V` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka`,
`C` is its parent folder, `E` is `sql/core/src/main/java/org/apache/spark/sql/execution`, `TJ`
and `TS` are the `src/test/java` and `src/test/scala` twins of `V`. Every fact was read in
`/home/max/proj/varka-657-plan-w`.

1. **No name of this row exists.**
   `ls $V/VarkaBatchArgs.java $V/VarkaZoneLowering.java $V/VarkaZoneData.java` fails;
   `grep -c 'zoneForm' $V/VarkaEmitOptions.java` prints 0; `grep -c 'zone' $C/DeclineSink.java`
   prints 0.
2. **A new record meets exhaustive switches with no `default`.** In main code:
   `VarkaVectorIR` (`laneType`, `narrowValuesIn`, `needsNarrowSpecies`, `canonicalWith`,
   `canonicalShallow`, `childrenOf`, `withChildren`), `Analysis.collectRefusals`,
   `Analysis.analyze`, `Slots.WordWalk.visit`, `VarkaVectorWalk.emitValue`,
   `VarkaKernelPlan.longNarrowRole`, `VarkaRangeAnalysis.range`, `VarkaSameAnswer`,
   `VarkaChronoCompiler.rearm`. In tests: `VarkaReference.value` and `longValue`,
   `VarkaIrGrammar.boundsOf`, `VarkaGuardSpecs.fires`, `VarkaWordCensus.pure`,
   `VarkaCanonicalIr.node`, `VarkaSmtNodes.template`, `VarkaReproductionSuite`.
   Command: `grep -rln 'HashStep' sql/catalyst/src sql/core/src` lists them.
3. **Rule R1 forces the first part to be emit-complete.** `dev/varka_push_check.py`
   (`REGISTRIES`) asks for the record's name in `VarkaWithChildrenSuite`, `VarkaIrFuzzSuite`,
   `VarkaLaneTypeSuite`, `VarkaEmitterBudgetSuite`, `VarkaReproductionSuite`,
   `VarkaEmitCostSuite`, `VarkaSmtNodesSuite` and `VarkaRangeAnalysisSuite`.
   `VarkaEmitterBudgetSuite` emits the node and counts its lane calls. So the record cannot
   merge before its lowering. Command: `grep -n 'REGISTRIES' -A 40 dev/varka_push_check.py`.
4. **The emitter knows `numLiterals`, not the long table's size.** `VarkaLoopEmitter.analyze`
   takes `outputs, numInputs, numLiterals, options`; `VarkaShapeKey` holds `numLiterals`;
   `CompiledVarkaProjection.numLiterals()` returns `literals.size() + longLiterals.size()`.
   Command: `grep -n 'numLiterals' $V/VarkaLoopEmitter.java $V/VarkaShapeKey.java`.
   Consequence: batch arguments start at index `numLiterals` (section 4.2).
5. **Literals are loaded once per group body.** `VarkaBodyEmitter.emitGroupBody` calls
   `emitLiterals(cb, analysis, s)`; `emitLiterals` loads `scalarArgs[j]` with the lane's array
   load. `grep -c 'emitLiterals(cb, analysis, s);' $V/VarkaBodyEmitter.java` prints 2.
6. **`longArgs` is parameter 7 of `run`.** `Lane.pLongArgs` is `P_SCALAR_ARGS + 1`.
   Command: `grep -n 'pLongArgs' $V/Lane.java`.
7. **The lane has the three calls the lowering needs:** `lanewiseVI` (vector, scalar),
   `lanewiseVIMasked` (vector, scalar, mask) and `compareVI` (vector, scalar).
   Command: `grep -n 'lanewiseVI\|compareVI' $V/Lane.java`.
8. **The warm-up already runs those calls on the long lane.** `VarkaVectorTemplateWarmup`
   calls `a.compare(op, 4L)`, `a.add(4L)` and `a.add(1L, m)` in its long rounds.
   Command: `grep -n 'a.compare(op, 4L)\|a.add(1L, m)' $V/VarkaVectorTemplateWarmup.java`.
9. **One site builds the compile result in production.** `VarkaExpressionCompiler.Tables`;
   two test sites build it by hand: `VarkaEmitterBoolSuite` and `VarkaExpressionCompilerSuite`.
   Command: `grep -rn 'new CompiledVarkaProjection(' sql/catalyst/src sql/core/src`.
10. **The sink rolls back by marks.** `DeclineSink.longMark`, `truncateLong`;
    `VarkaExpressionCompiler.Marks(inputs, literals, longs, bounds, used)` with `of` and
    `restore`, and an explicit rollback sequence in `classify`.
    Command: `grep -n 'record Marks' -A 14 $C/VarkaExpressionCompiler.java`.
11. **One site builds the runner.** `VarkaEvaluatorBase.newRunner` calls the constructor
    `VarkaKernelRunner(entry, lane, inputOrdinals, derived, bounds, numOutputs, scalarArgs,
    longArgs, scratch, accounting, hooks)`. `VarkaKernelRunner.fill` checks input bounds and
    throws `VarkaBatchDeclined(STATUS_INPUT_BOUND)`. Statuses in use: `1 << 10`, `1 << 11`,
    `1 << 12` once row 346's parts 346b1a and 346a have merged (they take the kernel's bits 8 and
    9 and move the runner's three bits up by two; before that, `1 << 8` to `1 << 10`). Command: `grep -n 'STATUS_.* = 1 <<' $E/varka/VarkaKernelRunner.java`.
12. **A status gets its metric from two arrays.** `VarkaFallbackAccounting.STATUS_BITS` and
    `STATUS_NAMES`; `VarkaEvaluatorComponentsSuite` asserts `evaluatorBits.size == 3`.
    Command: `grep -n 'STATUS_BITS\|STATUS_NAMES' $E/varka/VarkaFallbackAccounting.java`.
13. **The warm-up copies the arguments after the fill.** `VarkaWarmupGate.startWarmup` calls
    `runner.fill`, then `VarkaKernelWarmup.start`, whose `Job` keeps `longArgs.clone()`.
    Command: `grep -n 'longArgs.clone()' $V/VarkaKernelWarmup.java`.
14. **The reproduction file takes the literal tables, not the runner's arrays.**
    `VarkaEvaluatorBase.reproduceKernelFailure` passes `compiled.literals(),
    compiled.longLiterals()`; `grep -c 'compiled.longLiterals(),' $E/VarkaEvaluatorBase.java`
    prints 2. `VarkaReproduction.parse` already has an optional line, `batch omitted`.
15. **An option read by one node kind stays out of the matrix.** `VarkaEmitOption.drawnInGeneral`
    is false for an option with `readOnlyBy`; `VarkaMatrixRuns.configurations` adds it only
    when its class is in `COMPILED_NODE_KINDS`. So `zoneForm` adds no configuration here.
    Command: `grep -n 'COMPILED_NODE_KINDS' $TJ/VarkaMatrixRuns.java`.
16. **`VarkaEmittedBytesSuite` pins arms by name,** so a new option moves `emitted_bytes.json`
    only if its arm is added to `pinnedArms`. Command: `grep -n 'pinnedArms' -r $TS $TJ`.
17. **The harness store needs no change.** `VarkaHarnessStore` keys on `tz=` and its `Stamp`
    digests the options table. Command: `grep -n 'tz=' -r sql/core/src/test/java`.
18. **Emitted sizes of the nearest shape** (run on 26 November 2026, laptop, eight long
    lanes): `dev/varka_emit.sh "l & 255L" --columns l:bigint` prints IR
    `(bit:AND col:0:long lit:0:long)`, `loopDense0` 146 bytes, `epilogueDense0` 159 bytes,
    4 `LongVector` calls. `dev/varka_emit.sh "hour(t)" --columns t:time` prints IR
    `(narrow:INT (divc:3600000000000 col:0:long))`: an int output over a long node is a
    narrowing root on a long-lane kernel, which reads `longArgs`.

**Not checked.** `LongRangeOps` does not exist yet (row 683); its signature is the one
`VARKA-683.md` section 4 gives. The byte counts of section 7 are computed from fact 18, not
emitted. The speed of the lowering with scalar arguments (section 4.4) is not measured: the probes
kept broadcast vectors in locals. `dev/varka_pit.sh` runs ScalaTest suites only, so the mutation
run cannot use the row's JUnit suites (section 6).

## 4. The design

### 4.1 The node

In `VarkaVectorIR`:

    sealed interface Zoned extends VarkaVectorIR permits ToLocal {
      int zone();
      VarkaVectorIR operand();
    }
    record ToLocal(VarkaVectorIR instant, int zone, int hash) implements Zoned

- The compact constructor checks: `instant` is on the long lane; `instant` is not a `Cond`;
  `zone >= 0`. Then it sets `hash = hashOf(instant, zone)`, whatever was passed, as
  `ReinterpretLane` and `HashStep` do: the reflective test helpers call the canonical
  constructor with a made-up hash. A public constructor `ToLocal(instant, zone)` passes 0.
  `hashCode()` returns `hash`. `operand()` returns `instant`.
- `props()`: ring `NEVER`, no int overflow check, validity `STRICT`, weight 1 in part a and 5
  from part b on (the default form), cost kind `"ToLocal"`, `FILLS_NOTHING`,
  `RESTRICTS_NOTHING`, `LongNarrowReader.NONE`.
- Canonical text: `(toLocal:<zone> <child>)`. `VarkaCanonicalIr.node` parses it:
  `case "toLocal" -> new ToLocal(node(), Integer.parseInt(parts[1]))`.
- Every switch arm names `ToLocal`, never `Zoned`, so that row 690's `ToInstant` fails to
  compile until it decides each arm.

The arms, one per switch of fact 2:

| Switch | Arm for `ToLocal n` |
|---|---|
| `laneType` | `LONG` |
| `narrowValuesIn`, `needsNarrowSpecies` | as `ReinterpretLane`'s arm does for a one-child long node |
| `childrenOf` | `{n.instant()}` |
| `withChildren` | `new ToLocal(children[0], n.zone())` |
| `Analysis.collectRefusals` | empty |
| `Analysis.analyze` | `analyzeOp(node, false, n.instant())` |
| `Slots.WordWalk.visit` | empty |
| `VarkaVectorWalk.emitValue` | `VarkaZoneLowering.emitToLocal(cb, n, dense, analysis, s, computed)` |
| `VarkaKernelPlan.longNarrowRole` | joins the `NONE` list |
| `VarkaRangeAnalysis.range` | `kind != Kind.INT ? UNKNOWN : ZONE_SHIFT.over(range(n.instant(), ...))` |
| `VarkaSameAnswer` | joins the `byValueChildren` list |
| `VarkaChronoCompiler.rearm` | `new Rebuilt(node, false, false)` list |
| `VarkaReference.value` | throws: "ToLocal is on the long lane" |
| `VarkaReference.longValue` | throws: "ToLocal is answered by mixedValue with the zone ids" |
| `VarkaIrGrammar.boundsOf` | throws: "ToLocal has no production draw (VARKA-684)" |
| `VarkaGuardSpecs.fires` | joins the no-guard list |
| `VarkaWordCensus.pure` | `pure(x.instant(), ...)` |
| `VarkaSmtNodes` | entry in `REFUSED`: "offsets are batch arguments, not a function of the row" |

`ZONE_SHIFT` is `new Shift(-VarkaBatchArgs.MAX_OFFSET_MICROS, VarkaBatchArgs.MAX_OFFSET_MICROS)`.

### 4.2 The layout of the batch arguments

New final class `V/VarkaBatchArgs.java`:

    public static final long MAX_OFFSET_MICROS = 18L * 3600 * 1_000_000;  // ZoneOffset.MAX
    public static final long NEVER = Long.MAX_VALUE;
    public record Program(VarkaVectorIR.Zoned node, int first, int count) {}
    public static List<Program> layout(List<VarkaVectorIR> outputs, int numLiterals,
        VarkaEmitOptions options)
    public static int count(VarkaVectorIR.Zoned node, VarkaEmitOptions.ZoneForm form)
    public static long[] argsLength(long[] longLiterals, int numLiterals, List<Program> programs)
    public static void neutral(Program program, long[] args)
    public static int operandColumn(VarkaVectorIR.Zoned node)
    public static int zonesNeeded(List<VarkaVectorIR> outputs)

- `layout` walks the outputs in order, a node's children (in `childrenOf` order) before the
  node, with a `HashSet` of nodes seen. Each distinct `Zoned` node gets one program. The first
  program starts at `numLiterals`; each next one starts where the last ended.
- `count` is `1 + 2 * form.steps()` for a `ToLocal`. It switches over `Zoned` with no
  `default`.
- `argsLength` returns the literal array itself when there is no program. Otherwise it returns
  a new array of length `numLiterals + total`, with the literals copied to the front.
- `neutral` writes `base = 0` and padding steps: the arguments of a batch with no live row.
- `operandColumn` strips `GuardedRange` wrappers from `node.operand()`; a long `ColumnRef`
  gives its ordinal, anything else gives -1.
- `zonesNeeded` is the greatest zone index in the outputs plus one, or 0.

**A name the first draft of the family had.** It wrote `layout(outputs, numLongLiterals,
options)`. The emitter is never told the long table's size (fact 4). So the parameter is
`numLiterals`, the count the shape key already holds, and `VARKA-657.md` 3.2 writes it so. In
a kernel of two lanes the long table is shorter than `numLiterals`, and the padding of
`argsLength` covers the gap; a test of part a holds it (a narrowing `ToLocal` root beside an
int-lane value with an int literal).

In part a there is no option yet: `layout` and `count` take no form and every program has
count 1. Part b adds the parameters shown above.

### 4.3 What the emitter reads

- `Analysis` gains `List<VarkaBatchArgs.Program> batchArgs` and `Map<VarkaVectorIR, Program>
  programOf`, set in `VarkaLoopEmitter.analyze`, the one place that builds an `Analysis`.
- `Slots` gains `Map<VarkaVectorIR, int[]> batchArgLocal` (one long local per argument) and
  `Map<VarkaVectorIR, int[]> zoneTmp` (one vector local, only when the form has steps). Both
  are planned in `Slots.plan`'s per-node loop, beside `canonTmp`.
- `VarkaBodyEmitter.emitBatchArgs(cb, analysis, s)` runs directly after `emitLiterals` in
  `emitGroupBody`. For each program in layout order whose node is in `s.emittedBody`, and for
  each argument `k`: `aload longArgs; loadConstant(first + k); laload; lstore local`.
- An int output over a zone node needs nothing more (fact 18).

### 4.4 The lowering

New final class `V/VarkaZoneLowering.java`, modelled on `VarkaHashLowering`:

    static void emitToLocal(CodeBuilder cb, ToLocal n, boolean dense, Analysis analysis,
        Slots s, Set<VarkaVectorIR> computed)
    static int laneCalls(VarkaEmitOptions.ZoneForm form)    // 1 + 2 * form.steps()

The bytecode, with `v` the operand's vector:

1. `emitValue(n.instant())` leaves `v` on the stack.
2. With steps: `dup; astore tmp`.
3. `lload base;` then the lane's `add(long)` through `lanewiseVI`. The stack holds `v + base`.
4. For each step `i`: `lload delta_i; aload tmp; getstatic GE; lload at_i;` the lane's
   `compare(GE, long)` through `compareVI`; then `add(long, mask)` through `lanewiseVIMasked`.

The arguments stay scalars. No broadcast vector is kept in a local: a vector held in a local
across the loop pins a register (`sql/varka/SKILLS.md`, the lesson on hoisted vectors).
Whether hoisting wins is an A/B arm for row 691, not this row.

Edge cases:

- **Null rows.** Validity is `STRICT`: the output's validity word is the operand's. The kernel
  adds offsets to whatever is under a null row; nobody reads it. No check, no decline.
- **The ends of a long.** The runner declines a batch whose least value is below
  `Long.MIN_VALUE + MAX_OFFSET_MICROS` or whose greatest is above `Long.MAX_VALUE -
  MAX_OFFSET_MICROS`. So the kernel's adds never wrap on a live row, and a live value never
  equals the padding's `at`.
- **A batch with no live row.** `leastAndGreatest` returns false; the runner writes the
  neutral arguments and runs the kernel.
- **Two lanes of 64 bits.** The same bytecode. The masked add falls to the Vector API's Java
  path there and is slow (section 2). Row 689's `zoneStepMask` answers it. Form `ONE` has no
  mask and is fast at every width.
- **AVX2 against AVX-512.** The same bytecode; the JIT picks blend or mask registers.

What does not compile, by name:

- `VarkaKernelPlan.Kind.ZONE_OPERAND`, with `VarkaKernelPlan.ZONE_OPERAND_REASON`: "a time
  zone conversion over a computed value: a batch's offsets follow the least and greatest
  value of a column, and a computed value's range in a batch is not worked out (VARKA-688)".
  `VarkaKernelPlan.of` refuses when `VarkaBatchArgs.operandColumn(node) < 0`.
  `VarkaExpressionCompiler.admission` turns the refusal into the decline reason by that text;
  the emitter throws it if asked anyway.

### 4.5 The option

In `VarkaEmitOptions`: `enum ZoneForm { ONE(0), STEPS_2(2), STEPS_8(8) }` with `int steps()`,
a component `zoneForm` (last), default `ZoneForm.STEPS_2`. In `VarkaEmitOption.TABLE`, last:

    new Choice<>("zoneForm", Reason.MACHINE, ZoneForm.class, ZoneForm.STEPS_2,
        VarkaEmitOptions::zoneForm, Builder::zoneForm, new ChoiceTag("zoneForm="),
        Optional.of(VarkaVectorIR.Zoned.class))

The reason is `MACHINE`: the best form depends on the width (section 2). The option is read
only by `Zoned` nodes, so the matrix gains no configuration until row 657 adds `Zoned` to
`COMPILED_NODE_KINDS` (fact 15). `VarkaEmitBudget.weightOf(node, options, narrow)` returns
`VarkaZoneLowering.laneCalls(options.zoneForm())` for a `ToLocal`. `emitted_bytes.json` does
not change: the pins of the forms live in the row's own suites.

### 4.6 The zone list

- `CompiledVarkaProjection` gains a ninth component, `List<String> zones`. The constructor
  copies it and checks `VarkaBatchArgs.zonesNeeded(outputs) <= zones.size()`.
- `DeclineSink` gains `int zone(String id)` (interns by id, in first-use order), `int
  zoneMark()`, `void truncateZones(int mark)` and `List<String> zoneIds()`.
- `VarkaExpressionCompiler.Marks` gains `zones`, in `of`, in `restore` and in the explicit
  rollback of `classify`. `Tables` passes `sink.zoneIds()`.
- Nothing here promises that the session zone is index 0. Row 657's arms decide the order.

### 4.7 The zone data

New interface `V/VarkaZoneData.java`:

    public interface VarkaZoneData {
      String id();
      boolean fixed();
      int changes(long least, long greatest);
      int localChanges(long least, long greatest);
      void toLocalArgs(long least, long greatest, int steps, long[] args, int first);
      void toInstantArgs(VarkaVectorIR.Zoned node, long least, long greatest, int steps,
          long[] args, int first);
      interface Provider {
        Provider NONE = id -> null;
        @Nullable VarkaZoneData of(String zoneId);
      }
    }

- `changes(least, greatest)`: the number of changes `c` with `least < c <= greatest`, capped
  at `MANY = 4096`. A change at exactly `least` is already in the base offset.
- `toLocalArgs`: `args[first]` is the offset at `least`, in microseconds; then one `at`,
  `delta` pair per change in order; then padding up to `steps`. It throws
  `IllegalArgumentException` when the changes exceed `steps`: the caller asked `changes`
  first.
- `Provider.of` returns null for a zone it does not serve. The family's design says that the
  compile then declines by name; null is how the provider tells the compiler.
- `localChanges` and `toInstantArgs` are declared for row 690. The reference implements
  `localChanges`; its `toInstantArgs` switches over `Zoned` and throws for `ToLocal`.

The reference, `TJ/VarkaJavaTimeZoneData.java` (catalyst test scope, exported in the test
jar): it reads `ZoneId.of(id).getRules()`. It compares in whole seconds
(`Math.floorDiv(micros, 1_000_000)`), so nothing overflows at the ends of a long. It walks
`nextTransition` from the instant of `least`. `at` is the change's epoch second times
1,000,000, which is exact because a change falls on a whole second.

### 4.8 The runner

- `VarkaKernelRunner.STATUS_BATCH_ARGS = 1 << 13`; `VarkaFallbackAccounting` gains the bit and
  the name `"batch-args"`.
- The constructor gains `BatchProgram[] programs`. `BatchProgram(Zoned node, int input,
  VarkaZoneData zone, int steps, int first)` is a public record, as `VarkaKernelRunner.Bound`
  is, because `VarkaEvaluatorBase.newRunner` builds it from another package. The runner also
  gains a `long[2]` field for the range.
- `fill`, after the bounds loop, for each program:
  1. `LongRangeOps.leastAndGreatest(...)` over input `input`; false: `VarkaBatchArgs.neutral`,
     next program.
  2. Decline with `STATUS_BATCH_ARGS` when `least < Long.MIN_VALUE + MAX_OFFSET_MICROS`, when
     `greatest > Long.MAX_VALUE - MAX_OFFSET_MICROS`, or when `zone.changes(least, greatest) >
     steps`.
  3. Else `zone.toLocalArgs(least, greatest, steps, longArgs, first)`.
- `VarkaEvaluatorBase` gains `protected VarkaZoneData.Provider zoneData()`, returning
  `Provider.NONE`. Row 657 overrides it with row 678's data. `newRunner` builds the programs
  from `VarkaBatchArgs.layout`, sizes the array with `argsLength`, and throws
  `IllegalStateException("no zone data for " + id)` when the provider returns null. That
  lands in `fusedRunner`'s catch, which is the emission-failure fallback. Once
  `VarkaBatchArgs.dataOf` exists (row 690's part b), the data a program keeps is
  `VarkaBatchArgs.dataOf(program.node(), data)`: a local-time node reads the local line
  through it, and the runner itself never calls `toInstantArgs` (`VARKA-690.md` 4.4).
- **Warm-up:** no code change (fact 13). A test holds it.
- **Reproduction:** `VarkaReproduction` gains `List<String> zones`, written as an optional
  line `zones: <ids joined by a comma>` after the long literals. `FORMAT` stays 1: a file
  without the line parses as before. `write` gains a `zones` parameter.
  `reproduceKernelFailure` passes the runner's own `scalarArgs` and `longArgs` (which hold
  the batch arguments as filled) and `compiled.zones()`. `VarkaReplay.run` already passes the
  file's long list to the kernel.
- **Harness store:** no change (fact 17).

## 5. The parts

Six pull requests. Rule R1 (fact 3) makes part a carry the record, the layout, the emitter's
read and the one-offset lowering together. Each sheet follows
`sql/varka/plans/m8/BUILD_SHEETS.md`: the exports, the lock, the linters, one local commit, no
push. Each part's last step edits row 682's line in `sql/varka/plans/m9/PLAN.md`: part a makes
it open with `**Started**`, part f with `**Done**`. Every shell starts with:

    export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
    V=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    C=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen
    E=sql/core/src/main/java/org/apache/spark/sql/execution
    TJ=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    TS=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka

### Part a: the node, the layout and the one-offset kernel

Title: `[VARKA-682] Add the ToLocal node, its batch arguments and the one-offset kernel`.
Branch: `varka-682a-node`. Estimate: 650 counted lines.

**B0, the premises.** Each prints the number shown, or stop.

    grep -c 'ToLocal\|Zoned\|VarkaBatchArgs' $V/VarkaVectorIR.java                          # 0
    ls $V/VarkaBatchArgs.java $V/VarkaZoneLowering.java 2>/dev/null | wc -l                   # 0
    grep -c 'record ReinterpretLane(VarkaVectorIR child, LaneType target, int hash)' $V/VarkaVectorIR.java   # 1
    grep -c 'emitLiterals(cb, analysis, s);' $V/VarkaBodyEmitter.java                       # 2
    grep -c 'static void emitGroupBody(CodeBuilder cb, boolean dense, BodyMode mode, int group,' $V/VarkaBodyEmitter.java   # 1
    grep -c 'private static Analysis analyze(List<VarkaVectorIR> outputs, int numInputs, int numLiterals,' $V/VarkaLoopEmitter.java   # 1
    grep -c 'final Map<VarkaVectorIR, int\[\]> canonTmp = new HashMap<>();' $V/Slots.java   # 1
    grep -c 'static LongNarrowRole longNarrowRole(VarkaVectorIR node)' $V/VarkaKernelPlan.java   # 1
    grep -c 'private static Rearmed rearm(VarkaVectorIR node, VarkaSlotTable literals)' $C/VarkaChronoCompiler.java   # 1
    grep -c 'private static final class MixedRow {' $TJ/VarkaReference.java                 # 1
    ls sql/varka/plans/m9/VARKA-657.md | wc -l                                              # 1

**Steps.**

1. `V/VarkaVectorIR.java`: add `Zoned` and `ToLocal` (section 4.1) after `ReinterpretLane`, add
`Zoned` to the interface's `permits` list, and add the arms of `laneType`, `narrowValuesIn`,
`needsNarrowSpecies`, `canonicalWith`, `canonicalShallow`, `childrenOf` and `withChildren`.
`props()` has weight 1. 2. `V/VarkaBatchArgs.java`: new, as section 4.2, without the form
parameters: `count(node)` returns 1 through a switch over `Zoned`. 3. `V/Analysis.java`: the two
fields of section 4.3 after `served`; the arms of `collectRefusals` and `analyze`.
`V/VarkaLoopEmitter.java`, `analyze`: set both fields from `VarkaBatchArgs.layout(outputs,
numLiterals)`. 4. `V/Slots.java`: `batchArgLocal` and `zoneTmp` after `canonTmp`; in `plan`'s
per-node loop, for a `Zoned` node with a program, `batchArgLocal.put(node, <count long locals>)`;
the empty arm of `WordWalk.visit`. 5. `V/VarkaBodyEmitter.java`: `emitBatchArgs` (section 4.3),
called after `emitLiterals` in `emitGroupBody` only. 6. `V/VarkaZoneLowering.java`: new;
`emitToLocal` emits steps 1 and 3 of section 4.4; `laneCalls()` returns 1.
`V/VarkaVectorWalk.java`: the arm of `emitValue`. 7. `V/VarkaKernelPlan.java`:
`Kind.ZONE_OPERAND`, `ZONE_OPERAND_REASON`, the refusal in `of`, and `ToLocal` in
`longNarrowRole`'s `NONE` list. `C/VarkaExpressionCompiler.java`, `admission`: no new mapping; a
refusal's reason already flows through unchanged. Check it with the suite. 8.
`V/VarkaRangeAnalysis.java`, `V/VarkaSameAnswer.java`, `C/VarkaChronoCompiler.java`: the arms of
the table in section 4.1. 9. Tests' registries, each as the table in section 4.1 or as named here:
`TJ/VarkaReference.java` (the two refusing arms; new overloads `mixedValue` and `mixedCond` with a
last parameter `List<String> zones`; `MixedRow` gains the field and the arm: `micros +
rules.getOffset(Instant.ofEpochSecond(floorDiv(micros, 1_000_000), floorMod(micros, 1_000_000) *
1000)).getTotalSeconds() * 1_000_000L`; the old overloads pass `List.of()`),
`TJ/VarkaIrGrammar.java`, `TJ/VarkaGuardSpecs.java`, `TJ/VarkaWordCensus.java`,
`TJ/VarkaCanonicalIr.java`, `TJ/VarkaSmtNodes.java` (`entry(ToLocal.class, ...)` in `REFUSED`),
`TJ/VarkaReproductionSuite.java` (`theRest` builds one), `TJ/VarkaNodePropsSuite.java` (family
list gains `VarkaVectorIR.Zoned.class`; the floor `61` becomes `62`). 10. Scala registries:
`TS/VarkaLaneTypeSuite.scala` (`case "ToLocal"` in `instance`; in the
    long set of `atLong`; and the suite's equation of `atInt` and the other sets with the types
    must still hold, so `ToLocal` joins the set of types that have no int form, beside
    `DecimalColumn` and `DecimalWord`, if the equation fails without it), `TS/VarkaIrFuzzSuite.scala` (`"ToLocal"` in `missing` and in
    `outOfTheLongCorpora`), `TS/VarkaWithChildrenSuite.scala` (`"ToLocal"` in `undrawn`, and
    one direct rebuild test), `TS/VarkaEmitCostSuite.scala` (`"ToLocal"` in `awaitingRefit`),
    `TS/VarkaEmitterBudgetSuite.scala` (row `(new ToLocal(col, 0), 1, 0, defaults)`),
    `TS/VarkaRangeAnalysisSuite.scala` (`case x: ToLocal` in `walk.value` and in
    `admissible.go`).
11. `TJ/VarkaBatchArgsSuite.java`: new (below).

**Tests.** `VarkaBatchArgsSuite`, JUnit 5, `@VarkaSuite(ownJvm = true)`, each test with a
`@DisplayName`, options from `VarkaMatrixRuns.BASE`:

| Test | The failure only it catches |
|---|---|
| layout: two zone nodes in two outputs get programs in output order, starting at `numLiterals` | a program that overlaps a literal slot |
| layout: one node under two outputs gets one program | a shared node filled twice, or read from a slot nobody filled |
| layout: no zone node gives no program and `argsLength` returns the same array | a kernel without zones whose argument array grows |
| the record refuses an int-lane operand, a `Cond` operand, a negative zone | a node the emitter cannot lower |
| canonical text round-trips; two zones give two hashes | two zones sharing one kernel slot |
| kernel: `(toLocal:0 col:0:long)` with `base` hand-set gives `in + base` at every length of `VarkaEmitterFixtures.LENGTHS`, dense and with nulls | an argument read from the wrong index, or before the literals end |
| kernel: `(bit:AND (toLocal:0 col:0:long) lit:0:long)` reads the literal at 0 and `base` at 1 | batch arguments placed at the long table's start |
| kernel: an int output `(narrow:INT (toLocal:0 col:0:long))` reads the same slot | a narrowing root that loads from `scalarArgs` |
| kernel: a narrowing `ToLocal` root beside an int-lane output with an int literal reads `base` at `numLiterals` of a padded `longArgs` | arguments placed or sized by the long table, which is shorter in a kernel of two lanes |
| plan: `(toLocal:0 (add col col))` is refused with `ZONE_OPERAND_REASON` | a computed operand compiled with no range to fill from |
| bytes: `loopDense0` of `(toLocal:0 col:0:long)` and its `LongVector` call count are pinned, tagged `PinsDefaults` | a lowering that grows unseen |

**Commands**, in order:

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/Test/compile'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaBatchArgsSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaNodePropsSuite *VarkaLaneTypeSuite *VarkaIrFuzzSuite *VarkaWithChildrenSuite *VarkaEmitCostSuite *VarkaEmitterBudgetSuite *VarkaRangeAnalysisSuite *VarkaSmtNodesSuite *VarkaReproductionSuite *VarkaEmittedBytesSuite *VarkaVectorIRSharingSuite'
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json
    python3 dev/varka_push_check.py

Expected: the compile succeeds; the second, a JUnit class, prints sbt's `Passed: Total 11, Failed
0, Errors 0, Passed 11` (ScalaTest's `Tests: succeeded` line does not appear for JUnit); the third
prints `failed 0` and no `*** FAILED ***`; the `git diff` exits 0; the push check exits 0 and its
R1 lines name no missing registry.

**Planted faults** (after the commit, each reverted): load `base` from index `first - 1`
(the two kernel tests with a literal fail); drop the `HashSet` in `layout` (the shared-node
test fails); make `operandColumn` return 0 for any operand (the plan test fails).

**Done when** the commands print the above, the three faults fail the named tests, and the
size count is under 900.

**Not here:** no option, no steps, no zone data, no runner change, no compiler arm that
builds a `ToLocal`, no grammar draw, no change to a generated file.

### Part b: the option and the step forms

Title: `[VARKA-682] Add option zoneForm and the kernels of two and eight steps`.
Branch: `varka-682b-steps`. Estimate: 230 counted lines. Needs part a.

**B0.**

    grep -c 'zoneForm\|ZoneForm' $V/VarkaEmitOptions.java                                   # 0
    grep -c 'static void emitToLocal(' $V/VarkaZoneLowering.java                            # 1
    grep -c 'IntervalFault.NONE, VarkaEmitBudget.AUTO_CHAIN_DEPTH, false);' $V/VarkaEmitOptions.java   # 1
    grep -c 'floatingFault, intervalFault, maxChainDepth, uncountedMainLoop);' $V/VarkaEmitOptions.java   # 1
    grep -c 'b.uncountedMainLoop = uncountedMainLoop;' $V/VarkaEmitOptions.java             # 1
    grep -c 'public VarkaEmitOptions withUncountedMainLoop(boolean uncounted) {' $V/VarkaEmitOptions.java   # 1
    grep -c 'static int weightOf(VarkaVectorIR node, VarkaEmitOptions options, NarrowConversionForm narrow)' $V/VarkaEmitBudget.java   # 1
    grep -c 'Seq("decimalAbsLanewise" -> classOf\[VarkaVectorIR.DecimalColumn\],' $TS/VarkaEmitOptionSuite.scala   # 1

If the first six do not match, row 642 (an option declared in one place) may have merged:
stop and report.

**Steps.**

1. `V/VarkaEmitOptions.java`: the enum, the component after `uncountedMainLoop`, and each
   site an option has: the `@param` line, `DEFAULTS`, the null check, `toBuilder`, the
   `Builder` field and setter, `build()`, and `withZoneForm`.
2. `V/VarkaEmitOption.java`: the `TABLE` entry of section 4.5, last.
3. `V/VarkaBatchArgs.java`: `layout` and `count` take the options and the form (section 4.2);
   `neutral` writes the padding. `V/VarkaLoopEmitter.java`: pass the options.
4. `V/VarkaZoneLowering.java`: steps 2 and 4 of section 4.4; `laneCalls(form)`.
   `V/Slots.java`: plan `zoneTmp` when `form.steps() > 0`.
5. `V/VarkaVectorIR.java`: `ToLocal.props()` weight 5. `V/VarkaEmitBudget.java`: the arm in
   `weightOf(node, options, narrow)`.
6. `TS/VarkaEmitOptionSuite.scala`: `"zoneForm" -> classOf[VarkaVectorIR.Zoned]` in the
   `readOnlyBy` list, and `zoneForm` in the `MACHINE` reasons.
   `TS/VarkaEmitterBudgetSuite.scala`: two more rows, forms `ONE` and `STEPS_8`.
7. `TJ/VarkaBatchArgsSuite.java`: the tests below; the part a kernel tests now set form `ONE`
   by `VarkaMatrixRuns.BASE.withZoneForm(ZoneForm.ONE)`.

**Tests** added to `VarkaBatchArgsSuite`:

| Test | The failure only it catches |
|---|---|
| each form: `count` is 1, 5, 17; the second program starts that much later | a form whose kernel reads past its program |
| `STEPS_2` and `STEPS_8`, hand-set steps: the answer equals a plain Java loop (`v + base + sum of delta_i where v >= at_i`), with values at `at - 1`, `at`, `at + 1` | `GT` in place of `GE`; a step applied to the shifted value instead of the instant |
| all steps padded: the answer is `in + base`, also for `in = Long.MAX_VALUE - MAX_OFFSET_MICROS` | padding that fires |
| bytes and `LongVector` calls of each form pinned; no `VectorMask` call | a form that changes size unseen; a mask materialised |
| the shape keys of the three forms differ | two forms sharing one cached kernel |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaBatchArgsSuite *VarkaEmitOptionSuite *VarkaMatrixSuite *VarkaEmitterBudgetSuite *VarkaEmittedBytesSuite *VarkaNodePropsSuite'
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json
    python3 dev/varka_push_check.py

Expected: `failed 0`, no `*** FAILED ***`; `VarkaBatchArgsSuite` reports 15 tests; the diff
exits 0; the push check exits 0. `VarkaMatrixSuite` passing shows the matrix gained no
configuration.

**Planted faults:** `GT` for `GE` (the step test fails at `v == at`); compare the shifted
value (the step test fails); weight left at 1 (`VarkaEmitterBudgetSuite` fails).

**Done when** the commands print the above and the faults fail as named.

**Not here:** no `TABLE` or `LADDER` form, no `zoneStepMask`, no entry in `pinnedArms`, no
change to `COMPILED_NODE_KINDS`.

### Part c: the zone data and its reference

Title: `[VARKA-682] Add VarkaZoneData and its java.time reference for tests`.
Branch: `varka-682c-zone-data`. Estimate: 580 counted lines. Needs part b.

**B0.**

    ls $V/VarkaZoneData.java $TJ/VarkaJavaTimeZoneData.java 2>/dev/null | wc -l             # 0
    grep -c 'public static void neutral(Program program, long\[\] args)' $V/VarkaBatchArgs.java   # 1
    grep -c 'static OptionalLong mixedValue(.*List<String> zones' $TJ/VarkaReference.java   # 1 (one line of part a's overload; if part a split it, grep its last line)
    grep -c 'withZoneForm' $V/VarkaEmitOptions.java                                         # 1

**Steps.**

1. `V/VarkaZoneData.java`: the interface of section 4.7, with a doc comment on each method
   that states the half-open rule (`least < c <= greatest`) and the padding.
2. `TJ/VarkaJavaTimeZoneData.java`: the reference of section 4.7. `fixed()` is
   `rules.isFixedOffset()`. `localChanges` counts the changes whose local instant, before or
   after the change, falls in the range widened by `MAX_OFFSET_MICROS` on both sides. That
   is an upper bound: the production data counts the windows the range touches, `hi >
   least` and `lo <= greatest` (`VARKA-678.md` 4.5), which is never more. A test compares
   the two counts with `<=`, never with equality.
   A static `Provider PROVIDER = VarkaJavaTimeZoneData::new`.
3. `TJ/VarkaEmitterZoneSuite.java`: new (below).

**Tests.** `VarkaEmitterZoneSuite`, JUnit 5, `@VarkaSuite(ownJvm = true)`. Its helper fills a
kernel's arguments from `VarkaJavaTimeZoneData` and compares each row with
`VarkaReference.mixedValue(..., zones)`. Zones: `UTC`, `+05:30`, `Europe/Podgorica`,
`America/Los_Angeles`, `Australia/Lord_Howe` (a step of half an hour), `Asia/Kathmandu`,
`Pacific/Apia` (a day skipped).

| Test | The failure only it catches |
|---|---|
| data: `changes` over a range with 0, 1, 2 and 9 changes; a change at `least` is not counted, one at `greatest` is | an off-by-one at either end of the range |
| data: `toLocalArgs` writes base, steps in order, then padding; throws when changes exceed steps | steps out of order; a stale step left from the last batch |
| data: ranges touching `Long.MIN_VALUE` and `Long.MAX_VALUE` neither throw nor overflow | microsecond arithmetic that wraps |
| data: `fixed()` zones report 0 changes everywhere | a fixed zone that declines |
| kernel, each zone and each form: random instants inside one year, with nulls, equal the reference | a wrong offset sign; a `delta` taken as new offset instead of difference |
| kernel: rows exactly at a change, one microsecond before and after | a boundary row in the old offset |
| kernel: a batch spanning two changes under `STEPS_2`; eight under `STEPS_8` | steps that do not accumulate |
| kernel at four lanes and at two (`lanesOverride`, emit and run only where `VarkaSpeciesGuard` allows; else emit and verify) | a width-specific slip in the masked add |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaEmitterZoneSuite *VarkaBatchArgsSuite'
    python3 dev/varka_push_check.py

Expected: both suites are JUnit, so sbt prints `Passed: Total N, Failed 0, Errors 0` for the
run; `VarkaEmitterZoneSuite` reports 8 tests; the push check exits 0.

**Planted faults:** count a change at `least` (the first data test fails); write `delta` as
the new offset (the kernel tests fail for every zone with a change); skip the padding (the
second data test fails).

**Done when** the commands print the above and the faults fail as named.

**Not here:** no production implementation of `VarkaZoneData` (row 678), no change in
`sql/core`, no use of `java.time` in main code.

### Part d: the zone list

Title: `[VARKA-682] Carry the zone list in the compile result and the reproduction`.
Branch: `varka-682d-zone-list`. Estimate: 260 counted lines. Needs part a; independent of
parts b and c.

**B0.**

    grep -c 'zone' $C/DeclineSink.java $C/CompiledVarkaProjection.java $V/VarkaReproduction.java   # each file prints path:0
    grep -c 'List<VarkaFamily> families) {' $C/CompiledVarkaProjection.java                 # 1
    grep -c 'static Tables tables(VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink,' $C/VarkaExpressionCompiler.java   # 1
    grep -c 'public static Optional<Path> write(Supplier<VarkaErrorContext> context,' $V/VarkaReproduction.java   # 1
    grep -c 'String omitted = reader.next("batch omitted") ? reader.take("batch omitted") : null;' $V/VarkaReproduction.java   # 1
    grep -c 'compiled.longLiterals(),' $E/VarkaEvaluatorBase.java                           # 2
    grep -c 'fused.longLiterals()' $C/VarkaSizeAdmission.java                               # 1
    grep -c 'public static int zonesNeeded(List<VarkaVectorIR> outputs)' $V/VarkaBatchArgs.java   # 1

**Steps.**

1. `C/CompiledVarkaProjection.java`: the component and the check (section 4.6).
2. `C/DeclineSink.java`: the four methods, beside `longSlot`, `longMark`, `truncateLong`.
3. `C/VarkaExpressionCompiler.java`: `Marks.zones` in `of`, `restore` and `classify`'s
   rollback; `Tables` passes `sink.zoneIds()`.
4. `TS/VarkaEmitterBoolSuite.scala` and `TS/VarkaExpressionCompilerSuite.scala`: the two
   hand-built results pass an empty list.
5. `V/VarkaReproduction.java`: the component, a second constructor without it, the optional
   line in `render` and `parse`, the parameter of `write`. Callers:
   `E/VarkaEvaluatorBase.java` twice and `C/VarkaSizeAdmission.java` once pass
   `compiled.zones()`; the test callers pass `List.of()`.
6. `TJ/VarkaZoneListSuite.java`: new (below).

**Tests.** `VarkaZoneListSuite`, JUnit 5, `@VarkaSuite`:

| Test | The failure only it catches |
|---|---|
| the sink interns: the same id twice gives one index; ids keep first-use order | two indexes for one zone, so two kernels |
| `truncateZones(mark)` drops later ids and a re-added id gets the dropped index | a declined expression that leaves its zone behind |
| `Marks.restore` restores the zone list | a rollback that forgets the fifth table |
| the result refuses outputs whose zone index is past the list | a kernel filled from a zone that is not there |
| the reproduction round-trips with zones; a file without the line parses to an empty list | old reproduction files that stop parsing |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaZoneListSuite *VarkaReproductionSuite *VarkaExpressionCompilerSuite *VarkaEmitterBoolSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/Test/compile'
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json
    python3 dev/varka_push_check.py

Expected: `failed 0`; `VarkaZoneListSuite` reports 5 tests; the compile succeeds; the diff
exits 0; the push check exits 0.

**Planted faults:** intern by position instead of id (the first test fails); leave `zones`
out of `restore` (the third fails); write the line always (the old-file test still passes,
so instead: make `parse` require the line, and the fifth fails).

**Done when** the commands print the above and the faults fail as named.

**Not here:** no compiler arm that calls `sink.zone`, no change to `FORMAT`, no runner change.

### Part e: the runner

Title: `[VARKA-682] Fill the batch arguments in the runner, or decline`.
Branch: `varka-682e-runner`. Estimate: 520 counted lines. Needs parts b, c and d, and row 683.

**B0.**

    grep -c 'STATUS_BATCH_ARGS\|zoneData' $E/varka/VarkaKernelRunner.java $E/VarkaEvaluatorBase.java   # each file prints path:0
    grep -c 'static boolean leastAndGreatest(long srcData, long srcValidity, int srcNullCount, int length,' $V/LongRangeOps.java   # 1
    grep -c 'public VarkaKernelRunner(VarkaShapeEntry entry, LaneType lane, int\[\] inputOrdinals,' $E/varka/VarkaKernelRunner.java   # 1
    grep -c 'public void fill(ColumnarBatch input, int len)' $E/varka/VarkaKernelRunner.java   # 1
    grep -c 'private VarkaKernelRunner newRunner(CompiledVarkaProjection compiled)' $E/VarkaEvaluatorBase.java   # 1
    grep -c 'private static final int\[\] STATUS_BITS = {' $E/varka/VarkaFallbackAccounting.java   # 1
    grep -c 'evaluatorBits.size == 3' sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaEvaluatorComponentsSuite.scala   # 1
    grep -c 'List<String> zones' $C/CompiledVarkaProjection.java                            # 1
    grep -c 'interface Provider' $V/VarkaZoneData.java                                      # 1

If `LongRangeOps` lives in another folder, find it with `git grep -l 'class LongRangeOps'`
and use that path; if its signature differs from `VARKA-683.md` section 4, stop.

**Steps.**

1. `E/varka/VarkaKernelRunner.java`: the status, the record, the constructor parameter, the
   fill (section 4.8). The fill allocates nothing: the `long[2]` is a field.
2. `E/varka/VarkaFallbackAccounting.java`: the bit and `"batch-args"`, last in both arrays.
3. `E/VarkaEvaluatorBase.java`: `zoneData()`; `newRunner` builds the programs and sizes the
   array; `reproduceKernelFailure` passes the runner's arrays, its public final fields
   `scalarArgs` and `longArgs`.
4. `docs/sql-varka.md`: add `batch-args` to the sentence that lists the fallback reasons,
   after `forced`, and one sentence: a batch whose time-zone offsets cannot be prepared (too
   many offset changes in the batch's range, or values within 18 hours of the ends of the
   range of a long) runs on Spark's own path.
5. `VarkaEvaluatorComponentsSuite.scala`: `evaluatorBits.size == 4`.
6. `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaBatchArgRunnerSuite.scala`:
   new (below). ScalaTest, as the house rule keeps `sql/core` suites.

**Tests.** The suite holds the tests below and one more, which the row's done-when asks for:
two runners of one shape, filled from batches in two different offsets and run in turn, each
give their own batch's answer (a shared argument array or range field fails it). Part f's
done-when names it. `VarkaBatchArgRunnerSuite` builds a `CompiledVarkaProjection` by hand over
`(toLocal:0 col:0:long)` with zones `Seq("Europe/Podgorica")`, and an evaluator subclass
whose `zoneData()` returns `VarkaJavaTimeZoneData.PROVIDER`:

| Test | The failure only it catches |
|---|---|
| a batch inside one offset: answers equal the reference; no decline | a fill that never runs |
| three batches in turn: summer, across the October change, winter | arguments left from the last batch |
| a batch spanning three changes under `STEPS_2`: declined, metric `batch-args` is 1, rows come from Spark's path and are right | a silent wrong answer past the form's steps |
| the same batch under `STEPS_8`: not declined | a decline that ignores the form |
| a batch holding `Long.MIN_VALUE + 1`, and one holding `Long.MAX_VALUE - 1`: declined | a wrapped add on a live row |
| an all-null batch and an empty batch: run, no decline, output all null | a decline or a throw with no live row |
| nulls over poison values (`VarkaEmitterFixtures.makeLongInput`): the range ignores them | a range taken over dead rows |
| a provider that returns null: the evaluator falls back as for an emission failure, and logs the zone id | a null dereference per batch |
| warm-up: a recording kernel started through `VarkaKernelWarmup.start` sees the filled arguments | a warm-up that runs on zeros and trains the wrong branch |
| a forced kernel failure (`setFailKernelForTesting`): the reproduction file holds the batch arguments and the zones, and `VarkaReplay` reruns it to the same answer | a reproduction that cannot reproduce |
| two zone nodes over two columns: each program is filled from its own column | programs crossed |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaBatchArgRunnerSuite *VarkaEvaluatorComponentsSuite *VarkaReproductionEndToEndSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *VarkaBatchArgRunnerSuite'
    dev/varka_regen_generated.sh
    python3 dev/varka_push_check.py

Expected: `failed 0`; `VarkaBatchArgRunnerSuite` reports 11 tests in both runs, none
canceled; the push check exits 0.

**Planted faults:** drop the `changes > steps` test (the third test fails on wrong rows);
skip `neutral` (the all-null test reads stale arguments: make the batch before it span a
change); pass `compiled.longLiterals()` to the reproduction (the tenth fails).

**Done when** the commands print the above and the faults fail as named.

**Not here:** no override of `zoneData()` in a production evaluator, no SQL expression, no
change to `VarkaWarmupGate` or `VarkaHarnessStore`.

### Part f: the measurement and the close

Title: `[VARKA-682] Measure the fill and the kernels`.
Branch: `varka-682f-measure`. Estimate: 230 counted lines. Needs part e.

**B0.**

    grep -c 'STATUS_BATCH_ARGS' $E/varka/VarkaKernelRunner.java                             # at least 1
    ls sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaBatchArgsBenchmark.java 2>/dev/null | wc -l   # 0
    grep -c 'protected static void withEmitOptions(VarkaEmitOptions options, Runnable body)' sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaSqlBenchmarkBase.java   # 1

**Steps.**

1. `sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaBatchArgsBenchmark.java`:
new, modelled on `VarkaEvaluatorOverheadBenchmark`, over `VarkaSqlBenchmarkBase`, whose
`withEmitOptions` the B0 greps. Sections (4096 rows a batch, `Europe/Podgorica`, values inside one
month with no change unless said): "fill" (range pass plus `changes` plus `toLocalArgs`, reference
data); "kernel ONE", "kernel STEPS_2", "kernel STEPS_8"; "kernel STEPS_2, one change in the
batch"; "java.time per row" (the scalar loop of `VarkaReference`'s arm). 2. Run the dry run on the
laptop (below). The lead dispatches the timed run on the EPYC 7763 runners (256 and 128 bits) and
commits the results file `sql/core/benchmarks/VarkaBatchArgsBenchmark-jdk25-results.txt` and its
`-jdk25-128bit-results.txt` companion from the run's artifact. 3. `sql/varka/SKILLS.md`: one
entry, what the row learned about batch arguments (where they start, why scalars and not hoisted
vectors, the half-open rule). 4. This plan's section 11 and the row's line in `PLAN.md`: each
prediction scored.

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" 'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaBatchArgsBenchmark" 'set Test/javaOptions += "-XX:MaxVectorSize=32"' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaBatchArgsBenchmark" 'set Test/javaOptions += "-XX:MaxVectorSize=16"' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaBatchArgsBenchmark" > target/dry-682.log 2>&1; echo $?
    python3 dev/varka_plan_lint.py --check --offline
    python3 dev/varka_push_check.py

Expected: `0`; `grep -c 'Exception' target/dry-682.log` prints 0; the lint and the push
check exit 0.

**Done when** the dry run passes at three widths, the results file is committed by the lead,
and every prediction of section 7 has a score.

**Not here:** no timed run on the laptop beside other agents, no change to main code, no
number in a document that is not in the results file.

## 6. Tests, proofs, fuzzing and coverage

| Suite | Language, module | Holds | The failure only it catches |
|---|---|---|---|
| `VarkaBatchArgsSuite` | Java, catalyst | layout, record, hand-filled kernels, byte pins | an argument index off by the literal count |
| `VarkaEmitterZoneSuite` | Java, catalyst | zone data rules; kernels against `java.time` | a boundary row in the wrong offset |
| `VarkaZoneListSuite` | Java, catalyst | sink, marks, compile result, reproduction text | a zone left behind by a rollback |
| `VarkaBatchArgRunnerSuite` | Scala, sql/core | fill, decline, warm-up, reproduction | stale or missing arguments at run time |
| the registry suites of fact 3 | existing | `ToLocal` in every registry | a switch that forgot the node |

**Proofs.** The house rule asks for a proof file with a lowering that is exact only over a
bound. This lowering wraps outside 18 hours of a long's ends, and the runner's check holds that
bound. The file is `zone_step.smt2`, which row 684 writes with the step's other claims; until
it merges, the fifth test of `VarkaBatchArgRunnerSuite` is the only holder, and this plan says
so by name in part b's pull request. `VarkaSmtNodes` lists `ToLocal` as refused with its
reason, because its answer depends on batch arguments, not on the row alone.

**Fuzzing.** Nothing draws `ToLocal` in this row: it is in `VarkaIrFuzzSuite`'s `missing`
list and `VarkaWithChildrenSuite`'s `undrawn` list. Row 684 adds the draw and removes both
entries. `VarkaLaneFuzz.admitsLane` builds nodes reflectively and needs no change.

**Coverage.** After part c, `dev/varka_gen_coverage.sh` must show every line of
`VarkaZoneLowering` and `VarkaBatchArgs` covered in `sql/varka/coverage/emitter.md`; an
uncovered line is a missing test. **Mutation.** `dev/varka_pit.sh` runs ScalaTest suites
only (section 3), so it does not see the row's JUnit suites. The planted faults of each part
stand in for it. Row 684's fuzz draw brings the node under the mutation run.

## 7. Measurement

`VarkaBatchArgsBenchmark` (part f) runs on the AMD EPYC 7763 runners at 256 and at 128
bits; the laptop adds 512 bits when idle. There is no A/B arm beyond the three forms. The
arm "hoisted broadcast vectors against scalar arguments" belongs to row 691.

**Predictions, registered on 26 November 2026, before any run.**

| # | Prediction | Scored by |
|---|---|---|
| P1 | `loopDense0` of `(toLocal:0 col:0:long)` at eight long lanes, form `ONE`: 133 bytes (146 of fact 18, minus the literal's broadcast and `and`, plus a scalar `add`) | part a's pin |
| P2 | the same, `STEPS_2`: 190 bytes; `STEPS_8`: 363 bytes | part b's pins |
| P3 | `LongVector` calls: 3, 7 and 19; `VectorMask` calls: 0 | part b's pins |
| P4 | no existing shape's emitted bytes move: `emitted_bytes.json`, `coverage.json` and `width_audit.json` are identical after parts a, b and d: yes | the `git diff --exit-code` of each part |
| P5 | the fill costs under 0.4 ns a row at 256 bits on 4096-row batches with the reference data (the range pass dominates) | section "fill" of the benchmark |
| P6 | at 256 bits, kernel `STEPS_2` with no change in the batch is within twice kernel `ONE` | sections "kernel ONE", "kernel STEPS_2" |
| P7 | at 128 bits, kernel `STEPS_2` is slower than `java.time` per row: yes | sections "kernel STEPS_2", "java.time per row" |
| P8 | six parts, none over 700 counted lines | `dev/varka_msg_check.py size` |
| P9 | `VarkaBatchArgsSuite` runs under 5 s, `VarkaEmitterZoneSuite` under 20 s, `VarkaBatchArgRunnerSuite` under 30 s | the suites' reported times |

P5 to P7 rest on the probes quoted in section 2. P9 was set after reading `python3
dev/varka_calibration.py`: its nearest rows are an emitter suite predicted at 20 s that took
9.1 s (VARKA-342), a `sql/core` suite at 8.4 s (VARKA-365) and a JUnit class at 0.15 s
(VARKA-328). Earlier predictions for emitter suites ran high by about half, so P9 leans
low.

## 8. Who builds it

| Part | Builder | Why |
|---|---|---|
| a | full strength | 30 files, every registry, the emitter's slot planning; a slip costs a red master |
| b | a cheaper model, from its sheet | one option through sites the sheet lists; mechanical; tests decide |
| c | a cheaper model, from its sheet | one interface, one reference, tests against `java.time` |
| d | a cheaper model, from its sheet | a component threaded through named sites |
| e | full strength | the runner's hot path, the decline, the reproduction: wrong answers hide here |
| f | a cheaper model, from its sheet | a benchmark from a model file, a note, the close |

Parts b, c, d and f go to a cheaper model only after row 659's pilot admits that way of
working (`m9/PLAN.md` section 2.7). Until then the lead builds them. Each moves goal G5.

## 9. Risks

1. **Row 642 merges first and changes how an option is declared.** Part b's B0 stops; the
   lead rewrites its steps 1 and 2 only.
2. **Row 683's signature differs from the one in `VARKA-683.md`.** Part e's B0 stops.
3. **Scalar arguments are slower than hoisted vectors.** P6 scores it; row 691 holds the A/B
   arm. The layout does not depend on the choice.
4. **Part a is large (650 lines, 30 files).** R1 forbids a smaller first part. Its stop rule
   is 900 lines; if it passes that, move the int-output test and the range arm's test to
   part b.
5. **The byte predictions miss.** They are pins, not gates; a miss is recorded in the
   outcome with the reason.
6. **A `java.time` rule differs from Spark's.** Spark's `DateTimeUtils` uses the same
   `ZoneRules`; the reference and Spark agree by construction. Row 678 owns the production
   data and its own comparison.
7. **`Provider.of` returning null is this plan's choice.** The lead adds it to `VARKA-657.md`
   section 3 before part c; rows 678 and 657 read it there.
8. **A change exactly at `least`.** The half-open rule is stated on the interface and held
   by `VarkaEmitterZoneSuite`'s first and sixth tests.
9. **Six pull requests where the family plan budgeted four.** Each is under the size rule;
   parts c and d can run beside each other.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md` and `BUILD_SHEETS.md`, the ones a builder of this row is most
likely to break:

- A switch over a sealed type has no `default`. Name `ToLocal` in each arm, not `Zoned`.
- No allocation, stream, lambda or boxing per batch. The runner's fill reuses its fields.
- New production code and new catalyst suites are Java; a catalyst suite is JUnit 5 with
  `@VarkaSuite` and a `@DisplayName` on each test. A `sql/core` suite stays ScalaTest.
- A test starts from `VarkaMatrixRuns.BASE` (or `VarkaMatrix.base`) and changes only what it
  tests. A test that pins the defaults carries the `PinsDefaults` tag.
- Comments describe the code as it is. No `TODO`, no `FIXME`, no history.
- Never edit `emitted_bytes.json`, `coverage.json` or `width_audit.json` by hand.
- A pull request stays under 1000 counted lines.
- No second vector species in a shared JVM: a width test follows `VarkaSpeciesGuard`.
- Source lines within 100 columns; ASCII outside string literals.

Do not: build a `ToLocal` in any compiler arm; add `Zoned` to `COMPILED_NODE_KINDS`; add a
grammar draw; change `FORMAT`; use `java.time` in main code; run a timed benchmark on a
shared laptop; push, open a pull request or dispatch a workflow.

## 11. Outcome

To be written when the row closes.
