# Task 174: The emitter's shared pieces out of the facade, and doc comments where they belong

*Opened and done 25 September 2026, from `PLAN_MILESTONE_6.md` 9.2's list
after its top five; scope items 40 and 43, which 2.7 of the milestone joins.*

## 1. The question

Task 159 split the emitter into pieces - the analysis, the slot plan, the body
emitter, the vector walk, the lowerings - behind `VarkaLoopEmitter`, the
facade the rest of Spark calls. But six of the pieces reached back into the
facade with `import static VarkaLoopEmitter.*` for constants and helpers the
facade happened to hold, and three imported nested types from it, so the
dependency graph had cycles that hid who needs what: a lowering imported the
facade to get a constant. Item 40's done-when: no class under
`codegen/varka` imports the facade, the facade imports the pieces and not the
reverse, and the bytes oracle is unchanged. Item 43, beside it: the refactor
had found doc comments attached to the wrong declaration, because Java
attaches a `/** */` block to the next declaration and two stacked blocks raise
no warning; checkstyle's `InvalidJavadocPosition` reports exactly that.

## 2. The change

**What moved where**, each to the class whose concern it is, read off the
compiler's own "cannot find symbol" list once the wildcard imports were
removed, so the inventory is the compiler's and not a reading:

| member | new home | why there |
| :-- | :-- | :-- |
| `MAX_CHAIN_DEPTH`, `MAX_FUSED_NODES`, `MAX_INPUTS` | `VarkaEmitBudget` | the limits, beside the budgets they bound |
| `WORD_ALL_TRUE`, `WORD_DEAD` | `VarkaVectorWalk` | beside `loadWord` and `storeWord`, the one path every word goes through |
| `childrenOf`, `reaches`, `isDayOffsetShape` | `VarkaVectorIR` | facts about the IR's shape; the last is the rule the compiler and the emitter share, and the compiler now asks the IR for it |
| `chronoChild`, `tailReadsMarchMonth` | `VarkaChronoLowering` | facts about the calendar family its lowering owns |
| `FragmentKind`, `FragmentKey`, `fragmentKey`, `planFragmentsReadingMonth` | `Slots` | the fragment plan fills `Slots.fragmentsReadingMonth` and is keyed there |
| `BodyMode`, `invokeCall` | `VarkaBodyEmitter` | the body roles and the call a body forwards through |
| `ArmStep`, `referenced` | `Analysis` | the arm-chain walk and the referenced-column bitset are the analysis's |
| `emitLanes`, `hasValidityHelpers` | `Lane` | which width a lane's class bakes in |

Two members that had no doc comment got one line each (`childrenOf`,
`referenced`), and the word sentinel's `//` comment became a doc comment. The
facade's imports of IR records it no longer names went with them.

**Doc comments.** `InvalidJavadocPosition` is on in `dev/checkstyle.xml`, and
`dev/checkstyle-suppressions.xml` scopes it to paths containing a `varka`
directory, so Spark's own sources are not held to it by this fork. It found
seven blocks: the five the refactor's review had not fixed, in `Analysis`
(the guarded-producers walk), `Slots` (the parked guard local),
`VarkaBodyEmitter` (one lane group), `VarkaChrono` (`daysFromCivil`) and
`VarkaChronoLowering` (`trunc`), and two more in the test support class
(`lineNumbers`, `methodBodies`). Each was an orphan stacked above another
block, and each described an undocumented declaration further down; each
moved onto it.

## 3. Verification

* No class under `codegen/varka` imports `VarkaLoopEmitter`.
* The emitted-bytes oracle passes unchanged, so no emitted class moved; with
  it the emitter, budget, contract, lane, compiler, coverage, fuzz, huge-method
  and shape-cache suites, 204 tests.
* `dev/lint-java` passes with the new check on, and `dev/scalastyle` passes.

## 4. Outcome

**Done, 25 September 2026**, as section 3 reads. The facade now imports the
pieces and not the reverse.

## 5. Explicitly out of this task

* `fitsBudgets`, which the compiler calls on the facade; it is the facade's
  public entry, not a lent member.
* Turning `InvalidJavadocPosition` on for Spark's sources, which is upstream's
  call.
