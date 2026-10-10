# VARKA-83: One refusal, instead of four

## 1. Where this came from

Row 83 of `m7/PLAN.md`, scoped in `m5/PLAN.md` 2.14 from VARKA-63's review and moved here as
`m8/SCOPE.md` item 39. A kernel refuses a batch when some lane holds a value its lowering cannot
compute: the lane is ORed into the body's accumulator (`Slots.guardAcc`), and a non-empty
accumulator returns `STATUS_CHRONO_RANGE`, which sends the batch to the row engine. Each kind of
refusing node arrived with its own bookkeeping. When 2.14 was written there were four kinds; today
there are six, and the bookkeeping is spread over:

* three analysis sets in `Analysis`: `guardedProducers` (a column-offset `AddDays`/`SubDays` a
  calendar node reads, behind `guardDayProducers`), `selfGuarding` (a column-count `AddMonths`, and
  `MakeDate`) and `checkedArith` (FAIL-mode `IntArith` and `IntNeg`, behind `checkIntOverflow`);
* two kinds the sets do not hold, `GuardedDay` and `GuardedRange`, recognised by `instanceof`;
* in `Slots`, a `Guard` enum of four families, four per-body flags computed from it, and two
  predicates over them, `guardScratch` (needs a scratch local) and `guardedWord` (must keep its
  validity word alive), which take the flags as arguments, as does `WordWalk`.

A seventh kind has to be added to the right set, the right family, and the right disjunct of
either predicate. VARKA-63's review found the two predicates asking different questions, after
every checked node had been reserving a local nothing loaded.

## 2. The admission check, done

The check is the one 2.14 set: **no emitted byte moves.** `VarkaEmittedBytesSuite` hashes every
emitted method body, rendered symbolically, for every coverage row and a fixed run of fuzz shapes
at each lane, at 128 and 512 bits, plus one digest per selectable option arm
(`sql/varka/emitted_bytes.json`). The pinned line map, the shape hash and the `codeSize`
assertions in the emitter suites cover the rest. This task changes how the emitter decides,
never what it emits, so every one of them must pass without a regeneration.

The check was possible before any code: `VarkaEmittedBytesSuite` and the emitter suites pass on
this branch's base, and the refactor is judged by running them again. What it would reject: a
single local allocated, dropped or reordered, or a word killed or kept, in any shape or arm.

## 3. The design

### 3.1 The refusal, declared once

* **`Analysis.Refusal`**, an enum with one constant per reason, each carrying the one fact the
  slot planner needs from it: whether the guard parks its value in a scratch local
  (`parksValue`).

  | reason | node | parks its value |
  |---|---|---|
  | `DAY_PRODUCER` | column-offset `AddDays`/`SubDays` under a calendar node | yes |
  | `MONTH_COUNT` | column-count `AddMonths` | yes |
  | `MAKE_DATE` | `MakeDate` | no: it guards out of `makeDateTmp` |
  | `INT_OVERFLOW` | FAIL `IntArith` (not MUL), FAIL `IntNeg` | no: operands sit in `intArithTmp` |
  | `DAY_RANGE` | `GuardedDay` | yes |
  | `VALUE_RANGE` | `GuardedRange` | yes |

* **`Analysis.refusals`**, one `Map<VarkaVectorIR, Refusal>`, filled by one walk
  (`collectRefusals`, today's `collectGuardedProducers`) after every root is analysed. It holds a
  node exactly when that node refuses under the session's options: the two optional reasons
  (`guardDayProducers`, `checkIntOverflow`) are applied when the map is filled, so no reader
  repeats them.
* **One slot rule.** A loop or epilogue body takes the accumulator when it emits any node in the
  map, and a node takes a scratch local when its reason parks its value. `guardedWord` becomes
  "the node is in the map", and `guardScratch` becomes "in the map, and its reason parks its
  value", so the containment the code comments argue for holds by construction. The `Guard`
  enum, the four flags and the flag arguments to `liveWords` and `WordWalk` go.
* **One collect.** `emitGuardCollect` and `emitRangeGuard` are already shared by every kind;
  they stay.

### 3.2 What is deliberately unchanged

* **The status bit.** Every reason still returns `STATUS_CHRONO_RANGE`. A bit per reason is what
  makes "which kind fired" answerable, and the enum makes it one constant per reason. But it
  changes emitted constants and the evaluator's reading of the status word, which this task's
  admission check forbids. It becomes row 304.
* The guards' emission (`emitRangeGuard`, `emitGuardCollect`, `emitArmContext`); the compiler's
  bounds (`dayRange`, `intBound`, VARKA-84); a NULL-mode overflow, which narrows its own word and
  refuses nothing.

### 3.3 Registered op counts

None move.

## 4. Files

| file | what |
|---|---|
| `Analysis.java` | `Refusal`, `refusals`, `collectRefusals`; the three sets go |
| `Slots.java` | one slot rule; `Guard`, the flags and their arguments go |
| `VarkaLoopEmitter.java` | calls `collectRefusals` |
| `sql/varka/skills/emitter-and-ir.md` | the set names it cites |
| `VarkaEmitterChronoSuite.scala` | the test in 5 |

## 5. Tests, and what each is for

* **`VarkaEmittedBytesSuite`**, unchanged and not regenerated: the admission check.
* **The emitter suites** (`VarkaEmitter*Suite`, `VarkaLoopEmitterSuite`): the pinned line map,
  shape hashes and `codeSize`, and the guards' behaviour - a refused batch declines, a null lane
  does not condemn.
* **A new test** that the map holds each reason exactly where its node refuses: one shape per
  reason, each optional reason checked with its option on and off.
* **The gate**, both widths: every Varka suite, which runs every refusing kind end to end.

## 6. The measurement

None: nothing emitted changes, so there is nothing to time.

## 7. Risks

1. **A slot for a node the body does not emit.** The slot loop walks the kernel's topological
   order outside group-local frames, and today's flags are per body and per family, so a guarded
   node the body does not emit could take, or miss, a scratch local by a different rule than it
   will under 3.1. `VarkaEmittedBytesSuite` would show it as a moved hash; if one moves, the
   family stays as a field on `Refusal` instead.
2. **A reason filled under the wrong option.** The new test in 5 checks each optional reason
   with its option off.

## 8. Sequencing

1. This plan.
2. The refactor, the test, the skills note and the row marked Done, in one commit.

## 9. Outcome

Done as 3.1 describes, in one commit after this plan.

* **The admission check held.** `VarkaEmittedBytesSuite` passes against the committed
  `emitted_bytes.json` with no regeneration, as it did on the base, so no emitted method moved in
  any coverage row, fuzz shape or option arm at either width.
* **Risk 1 did not happen.** The clean rule - a scratch local for any node whose reason parks
  its value - gave the same slots as the per-body families did, so the families did not survive
  as a field.
* **What went:** the three sets, the `Guard` enum, the four per-body flags, and the three flag
  arguments `liveWords` and `WordWalk` took. A seventh reason is now one enum constant and one
  arm in `collectRefusals`; `refuse` throws if two rules ever give one node two reasons.
* **The test in 5** checks eleven shapes with both options on and both off, and the two reasons
  that park nothing.
* **Left for later:** a status bit per reason, row 304 (3.2).

### 9.1 Review of #712 (`/code-review high`), 10 October 2026

* **Risk 1 did happen, and section 9 above is wrong about it.** The per-node scratch rule moved
  bytes in the arms whose frames walk the whole kernel, `groupLocalSlots=false` and a byte budget
  of 0: a group's method gave another group's day producer or month count a scratch local it
  never loads. A probe of eight multi-group shapes, each with refusing
  nodes in some groups only, under eight option sets (the default, a group budget of one, both
  reference arms, and each with one option off), changed the class bytes of 23 of the 64 cases
  against master. `VarkaEmittedBytesSuite` could not see it: its option arms are the settings a
  session can select (`useAVX`, `validityOrFirst`), not the reference arms, so 2's "every option
  arm" holds for those and not for these. The family is back, as the plan's fallback said, as
  `Refusal.Family`: a parking node takes its scratch local in a body that emits a node of its
  family, or in every body for a re-armed range check. After it, all 64 cases are byte-identical
  to master. Pinning the reference arms is row 306.
* **`collectRefusals` is one exhaustive switch** over the IR, without a `default`, so a new node
  type has to say whether it refuses (`sql/varka/AGENTS.md`, Java code). The walk under the
  calendar nodes visits each node once, where it had revisited shared subtrees.
* **The emitted overflow check reads the map.** `emitIntNeg` checks exactly when the map says
  `INT_OVERFLOW`, and the FAIL collect refuses by name a node the map does not declare, so the
  emission and the planner's accumulator cannot disagree.
* **The test checks each optional reason against its own option**, with the two options set
  differently, where it had only set both together.
* `analyze` is private again, and the test reads the map through `refusalsForTest`; the
  accumulator test skips the body walk when the map is empty; a broken `{@link}` is fixed.
