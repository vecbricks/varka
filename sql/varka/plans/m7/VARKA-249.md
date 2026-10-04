# VARKA-249: One class owns the emitted method names

*Row 249 of `m7/PLAN.md`, wave 0, from `m8/SCOPE.md` item 74.6; opened 4 October 2026, the first
of milestone 7's refactors.*

## 1. Where this came from

An emitted kernel class has a fixed set of methods: the public `run` that dispatches, a driver per
side (`runDense`, `runMasked`), stages when the driver is split (`stageDense0`...), a loop method
per group (`loopDense0`...) and an epilogue, one over every output or one per group
(`epilogueDense`, `epilogueDense0`...). The names are the class's layout, and the code reads them
back to find a group, a driver or a stage. Item 74.6 found them spelled as strings and matched by
prefix in about a dozen places in main code and many in tests, so a change to the layout's names
would have to find every spelling.

## 2. The admission check, done

**Main code.** The names are built in `VarkaLoopEmitter.emitBodies` and the drivers' bodies, and
in `VarkaBodyEmitter`'s calls to stages, loops and epilogues; they are read in
`VarkaLoopEmitter` (a driver or a stage over the budget, the widest driver, the planned cut),
`VarkaEmitBudget.groupOf` (a group's index off its name) and `VarkaEmitCost.METHODS` (the four
kinds of group method the cost table prices). Nine places.

**Tests.** About a hundred lines match a name by prefix or assemble one from parts; 171 write a
full name as an expected value (`"loopDense0" -> 93`).

## 3. The design

### 3.1 `VarkaMethodNames`

A final class of static functions and constants in the emitter's package: `driver(dense)`,
`stage(dense, k)`, `loop(dense, group)`, `epilogue(dense)` and `epilogue(dense, group)` build a
name; `isDriver`, `isStage`, `isLoop`, `isEpilogue`, `isGroupMethod` and `groupOf` read one; `DISPATCH`,
`DRIVERS` and `GROUP_METHOD_KINDS` are the fixed names and the cost table's four columns. Every main
-code spelling goes through it, and so does every test that matches or assembles a name.

**Literal names in assertions stay literal.** A test that pins a class's layout -
`Seq("loopDense0" -> 93)` - is that layout's specification and reads best as the names; the new
class's own test pins that its functions produce exactly those names, so the two cannot drift
apart unseen.

**Considered and set aside.** An enum of method kinds with a parser: more structure than nine call
sites need, and every caller wants a string or a boolean, not a kind.

### 3.2 What is deliberately unchanged

Every emitted name and so every emitted byte; the methods' order in the class; the literal names in
the tests' expected values.

### 3.3 Registered op counts

None: no emitted byte moves. `emitted_bytes.json` unchanged is the proof.

## 4. Files

`VarkaMethodNames.java` (new), `VarkaLoopEmitter.java`, `VarkaBodyEmitter.java`,
`VarkaEmitBudget.java`, `VarkaEmitCost.java`; the tests and benchmarks that match a name by prefix;
`VarkaMethodNamesSuite` (new).

## 5. Tests, and what each is for

* `VarkaMethodNamesSuite`: every builder against the literal name it must produce, every reader on
  every kind of name and on names that are not the class's (the constructor, the dispatch).
* The bytes oracle, unchanged: the refactor moved no emitted byte.
* The Varka suites at both widths through the gate.

## 6. The measurement

None: a refactor with no runtime path changed.

### 6.1 Predictions, registered before the run

1. `emitted_bytes.json` and `emit_cost_audit.json` are byte-identical after the change.
2. No main-code file outside `VarkaMethodNames` spells a method name or matches one by prefix.

## 7. Risks

1. **A reader whose prefix test was looser than its name**: `startsWith("run")` matched the
   dispatch `run` as well as the drivers. Each reader keeps its exact meaning, named for it.

## 8. Sequencing

One pull request.

## 9. Outcome

Built on 4 October 2026.

1. **Held.** `emitted_bytes.json` and `emit_cost_audit.json` are byte-identical: both suites pass
   against the committed files with the change in.
2. **Held.** No main-code file outside `VarkaMethodNames` spells a method name or matches one by
   prefix; the one mention left is a comment that names a method.

The tests' 70 prefix matches and the few names they assembled from parts go through the class,
each keeping its exact meaning - `startsWith("run")` matched the dispatch as well as the drivers
and is `isDriverOrDispatch`, `isLoop(n, true) || isLoop(n, false)` is `isLoop(n)` - and the Scala
tests import the readers they use by name. The literal names in expected values stay literal, and
`VarkaMethodNamesSuite` pins every builder to them. The gate passed every step but the linters on
its first run: the inserted imports broke scalastyle's import order in seven files, where an
import wrapped over two lines sorts by its package and not by its brace; fixed, and scalastyle and
`dev/lint-java` pass.
