# VARKA-250: emitBody split into driver, loop and epilogue emitters

## 1. Where this came from

Milestone 7's row 250, from item 74.3 of `m10/SCOPE.md` ("Refactor the compiler's untidiest
code"): `VarkaBodyEmitter.emitBody` is one 372-line method for three roles - the driver, a group's
loop method and its epilogue - both bodies, and both driver forms, the table driver
(`driverOutputTable`) threading through the unrolled driver's numbered steps as guards. Split it
into driver, loop and epilogue emitters sharing the prologue helpers. The row's proof is
`emitted_bytes.json` unchanged. It follows VARKA-249, which gave the emitted methods' names one
class.

## 2. The admission check, done

A refactor that moves no byte needs no measurement, but it needs a proof that reaches every path
it rewrites, and the bytes oracle does not: it pins the defaults shape by shape at both widths
(the coverage rows and 10,000 shapes from each fuzzer) and the five `useAVX` arms as digests, so
the unrolled driver (`driverOutputTable=false`), the stage driver (`splitDriver`), the
single-epilogue form (`methodByteBudget=0`) and every other arm are emitted by no pinned test.
Read against `emitBody`, the step guards are: mode (eight places), `driverTable` (six), `dense`
(four), `perGroup` (two), `methodByteBudget` (one) and `stageGroups` (one). So the oracle alone
would have left most of the rewritten branches unproven; section 5 adds the check that reaches
them.

## 3. The design

### 3.1 Three emitters over shared prologue steps

`emitBody` today is the prologue's numbered steps - (1) the empty-batch return, (2) the nominal
sizes, the scratch segment, (3) the output segments and, in the driver, the validity zero or
fill, (4) the inputs' null state, (4b) the bitmap pass or the table driver's one call, (5) the
all-null shortcut, then the species, the hoisted literals and the guard accumulator - followed by
a switch on the mode. Each step decides for itself which role it is in. After the split:

* **`emitDriver`** emits the driver in either form. The table form is short and gets its own
  method, `emitTableDriver`: the empty-batch return, the output-plan call, the table shortcut and
  the calls to the groups, with none of the prologue the unrolled form maps. The unrolled form,
  `emitUnrolledDriver`, is the prologue's steps in the driver's role, the bitmap pass and the
  unrolled shortcut, then the calls.
* **`emitGroupBody`** emits a loop method or an epilogue for one group (or every output, in the
  form without a byte budget): the prologue's steps in the group's role, then the vector loop or
  the single masked pass, then the status return.
* **The prologue steps** become private helpers that both call, each with the guards it still
  needs and no more: `emitEmptyReturn`, `emitSizes`, `emitScratch`, `emitOutputSegments`,
  `emitInputState`, `emitSpeciesAndLiterals`, `emitGuardInit`. The driver's calls to the groups
  become `emitGroupCalls`.

The order in which each method emits its instructions is the order it emits them today, step for
step, which is what keeps every byte where it is. `VarkaLoopEmitter` calls the two entry points
instead of `emitBody` with a mode. `BodyMode` stays: `Slots.plan` and the validity predicates
take it.

### 3.2 What is deliberately unchanged

Every emitted byte, under every option. `Slots.plan`, which decides each role's locals;
`emitStage`, already its own method; `emitLaneGroup`, `emitVectorLoop` and `emitEpilogue`, which
the split calls as it does now; the size loop in `VarkaLoopEmitter.emit` (item 74.2); the
comments' history notes, rewritten only where the code under them moves (VARKA-252 owns the
rest).

### 3.3 Registered op counts

None move: the change emits the same instructions.

## 4. Files

| file | what |
|---|---|
| `VarkaBodyEmitter.java` | `emitBody` split into `emitDriver` (table and unrolled forms), `emitGroupBody` and the prologue helpers |
| `VarkaLoopEmitter.java` | the call sites, to the two entry points |
| `m7/VARKA-250.md`, `m7/PLAN.md` | this plan, row 250's status |

## 5. Tests, and what each is for

* `VarkaEmittedBytesSuite`: the defaults and the `useAVX` arms, shape by shape, both widths,
  unchanged.
* During development, not committed: the old `emitBody` kept beside the new emitters behind a
  test switch, and every oracle shape emitted under every option arm the bytes suite's inventory
  lists (`VarkaEmitOption.TABLE`), at both widths, by both, the classes compared byte for byte.
  This is the check that reaches the unrolled driver, the stage driver and the budget-off form.
* The Varka suites through `dev/varka_gate.sh`, both widths.

## 6. The measurement

None: no emitted byte moves, so nothing runs differently.

### 6.1 Predictions, registered before the run

1. `emitted_bytes.json` byte-identical.
2. The old and new emitters agree byte for byte on every oracle shape under every option arm, at
   both widths.
3. No new method of `VarkaBodyEmitter` is longer than 100 lines, where `emitBody` is 379.

## 7. Risks

1. **An instruction emitted in a different order** in one role or one arm moves bytes the
   oracle does not pin; the side-by-side comparison over every arm is what rules it out.
2. **A guard dropped as dead that was live** under some option - for instance the table driver
   reading a slot only the unrolled form stores; the same comparison would show it as a
   difference, or the class would fail verification in the suites.

## 8. Sequencing

One pull request: the split with the side-by-side check run and deleted, the plan, row 250's
status.

## 9. Outcome

### 9.1 The split, 5 October 2026

1. **Held.** `emitted_bytes.json` is byte-identical: `VarkaEmittedBytesSuite` passes unchanged.
2. **Held.** With the old `emitBody` kept beside the split behind a test switch, the two agreed
   byte for byte on all 164 arm-width pairs - the defaults and every option arm of
   `VarkaEmitOption.TABLE`, at 4 and 16 int lanes - over the coverage rows and 10,000 shapes from
   each fuzzer, the oracle's own set; a shape a form declines compared by its decline. The check
   bit: with one instruction of the new driver's calls changed, it failed on the defaults at both
   widths. It ran its two phases on twenty threads, five and a half minutes for the full set, and
   was deleted with the old method.
3. **Held.** The longest new method is `emitInputState` at 55 lines, then `emitOutputSegments`
   at 49 and `emitUnrolledDriver` at 46, where `emitBody` was 379; `emitLaneGroup`, which the
   task does not touch, stays the longest method of the class at 109.

`Slots.plan` confirmed what the table driver can drop: it plans no inputs, output segments or
literals for it, and no driver of either form gets a guard accumulator or a scratch segment, so
`emitTableDriver` calls no prologue helper at all. The group body no longer takes the class
descriptor, which only the driver's calls read.

**What the plan named that was built otherwise.** Section 3.1 planned `emitSpeciesAndLiterals`
and `emitGuardInit`. The code has `emitSpecies` and `emitLiterals` as two helpers, since the table
driver takes neither and the unrolled driver both, and keeps the guard accumulator's three
instructions inline in `emitGroupBody`, the only body with one. The 372 lines of section 1 are
item 74.3's count when the item was written; the method measured 379 when this task started.

**The review of the pull request** (`/code-review high`) found no correctness bug and changed
four things: `emitGroupBody` switches over its mode exhaustively again, a driver mode an error
rather than an epilogue; the comments that cited the old numbered steps name the helpers instead,
in `VarkaBodyEmitter`, `Slots.plan` and `VarkaEmitterValiditySuite`; `emitTableDriver`'s javadoc no
longer claims the driver has no locals but its status - `Slots.plan` numbers about a dozen for
every body, and the table driver uses the status alone; and the output list is built once in the
unrolled driver and only where used in a group body. Left for later: the table driver still pays a
whole-kernel `Slots.plan` for its one status slot. Giving it a small plan of its own would move
that slot's number and so the bytes, and it costs emission time only, a few small plans per
sizing round.

