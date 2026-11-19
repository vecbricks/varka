# VARKA-609: A guard in an IfElse condition is qualified by the arms around the IfElse

## 1. Where this came from

Milestone 8's row 609, added 12 November 2026 on the owner's word from step 103a2's build. It
costs fusion, not answers: a condemned batch declines to the row path, which answers as Spark.
`Analysis.assignArmChain` enters every `IfElse` condition with the empty chain, which is the
unqualified answer, so a guarded node a condition reads condemns the batch from every lane, under
an arm Spark does not take too. `VARKA-79.md` 3.2 made condition position count as "outside any
arm" when guards were first qualified; its reason, that an `IfElse` computes its condition on
every lane it is itself computed on, holds, but on a lane the enclosing arms do not select Spark
computes neither the `IfElse` nor its condition.

## 2. The admission check, done

Read at origin/master `e5d70012bdc` (20 November 2026). The problem holds, by reading; no
kernel was run in this planning pass, and step 1 is the run.

1. `Analysis.assignArmChain`, the `IfElse` branch: `assignArmChain(n.cond(), List.of())`, then
   the arms with `extend(pushed, n, true|false)`. The empty chain demotes the node and is pushed
   down the whole condition (`List<ArmStep> pushed = next.isEmpty() ? List.of() : chain`).
2. `collectArmContexts`' javadoc states the rule ("A condition's subtree is entered with the
   empty chain, never the enclosing one"), and its post-pass unqualifies a node inside the
   condition of an arm one of its own chains runs through; `unqualifyArmCycles` unqualifies
   guards whose chain conditions need each other.
3. `abs` of a year-month interval is `IfElse(x < 0, -x, x)` (`VarkaIntervalCompiler.absolute`)
   and `ym - ymm` is a `FAIL` subtraction, so in `abs(ym - ymm)` the subtraction sits in `abs`'s
   condition and is unqualified wherever `abs` sits.
4. `VarkaAnsiForm` runs every coverage row in the untaken arm of a `CASE`
   (`CASE WHEN rid < 0 THEN <row> ...`) and as a later `COALESCE` argument (an `IfElse` on
   `IsNotNull`); `DECLINED` lists `ym - ymm` only on the right side of a false `AND`, so the
   subtraction alone is served in both skipped places, qualified by the arm. The coverage table
   has `abs(ym)` and `ym - ymm` but no `abs(ym - ymm)`, so no committed check sees the case.
5. Row 103a1 (#1534, merged 15 November 2026) built `abs(dt)` as the same blend, so
   `abs(dt * 3)` is the same case on the long lane.

What the check would have rejected: a chain already pushed into conditions, or an `abs` lowered
without a choice. Neither is on master.

## 3. The design

### 3.1 The chain into the condition

`assignArmChain` enters `n.cond()` with `pushed`, the chain the `IfElse` itself is reached by,
instead of `List.of()`. Where the `IfElse` sits under no arm, `pushed` is empty and the
condition stays unqualified as now; under arm chain `C`, a guard in the condition takes `C`, so
it condemns only on lanes `C` selects.

Why this is sound: the condition is computed on every lane the `IfElse` is, and the `IfElse`'s
value reaches a consumer only on lanes `C` selects (3.3's argument in `VARKA-79.md`, from the
consumer's side), so Spark evaluates the condition on exactly those lanes.

Why the two later passes still hold:

* A node inside both the condition and an arm of the same `IfElse` now meets `C` from the
  condition and `C + [n, then|else]` from the arms; the longer chains contain `C` and are not
  kept, so the node is qualified by `C` alone, which the outer condition's word, emitted before
  the `IfElse`'s arm is entered (`VARKA-79.md` 3.2), already provides.
* The post-pass of `collectArmContexts` checks a node's chains against the conditions of their
  own steps; a chain that reaches a node through a condition now carries the outer steps, so the
  check sees more chains, never fewer, and still unqualifies a node inside the condition of an
  arm it is qualified by. The case it was written for, `VarkaGuardEdgeSuite`'s "a guard inside
  the condition of an arm it sits under is unqualified", has its outer `IfElse` under no arm, so
  nothing changes there.
* `unqualifyArmCycles` reads `armChainsOf` and `condReach` only; more qualified guards can make
  more cycles, which it unqualifies, declining more and never answering wrongly.

The javadoc of `collectArmContexts`, and `armChains`' (which says the empty list "includes ... a
use in a condition"), change to say a use in a condition takes its `IfElse`'s chain.
`VarkaGuardSpecs`, the test-side verdict model, reads the analysis's chains through
`guardsForTest`, so it follows without a change of its own; test 2 checks that it does.

**No new option.** The change sits under `VarkaEmitOptions.guardUnderArm`, which already gates
every arm qualification; with it off every guard is unqualified, which is the live reference
variant. Question 1 asks the owner whether he wants a switch of its own.

### 3.2 What is deliberately unchanged

* `NULL`-mode disposal and the blend: only the batch-condemning collect reads the chain.
* A `DecimalColumn` slot check and a guard over a column's stored values, which keep their
  rules in `armChainsOf`.
* Row 103a2's choice to write `dt / k`'s sign bias as arithmetic: it stays; it can be revisited
  once this lands, by row 103.

### 3.3 Registered op counts

A guard that becomes qualified ANDs its chain's condition word or mask into its collect: one or
two mask operations per step of the chain, in the bodies where the guard sits. Step 3 prints the
before and after for `CASE WHEN rid < 0 THEN abs(ym - ymm) ELSE ym END` with
`dev/varka_emit.sh`; the test in section 5 item 1 asserts the qualification, not the count.

## 4. Files

| file | what |
|---|---|
| `Analysis.java` | the chain into the condition; the two javadocs |
| `VarkaGuardEdgeSuite.scala` | the nested-condition case at both widths |
| `sql/varka/coverage.json` | the row `abs(ym - ymm)` |
| `VarkaAnsiForm.java` | nothing, unless an entry turns stale (test 3 says) |
| `emitted_bytes.json`, `emit_cost_audit.json`, census files | regenerated |

## 5. Tests, and what each is for

1. `VarkaGuardEdgeSuite`, "a guard in the condition of an IfElse under an outer arm is
   qualified by that arm": `IF(c0 < 0, IF(c1 + c2 > c3, c1, c3), c3)` with the sum `FAIL`, at 4
   and 16 int lanes and at the long lane: `guardsForTest` reports the sum with one chain (the
   outer then arm); a batch whose overflowing row has `c0 >= 0` answers, one whose overflowing row
   has `c0 < 0` declines with `STATUS_INT_OVERFLOW`, each body checked by
   `VarkaKernelCheck.runEachBodyStatus` and the answered batch against the reference. This is the
   test of the rule itself.
2. The existing "a guard inside the condition of an arm it sits under is unqualified" passes
   unchanged, and a twin puts its whole shape under an outer arm `A`: the sum now meets the
   chains `A + [inner, then]` and `A + [root, then]`, the post-pass finds it inside `root`'s
   condition and unqualifies it as before, the class verifies, and `VarkaGuardSpec.verdict`
   admits every body's status. It is the test that the new chains do not slip past the
   post-pass.
3. The coverage row `abs(ym - ymm)`, with `VarkaAnsiForm`'s harness (`VarkaCoverageHarnessSuite`'s
   ANSI test, under ANSI on the extremes fixture): served whole by a kernel in
   the untaken `CASE` arm and as the later `COALESCE` argument at the defaults, with no `DECLINED`
   entry there, and raising as Spark does alone. Before the change, the same run must list it as
   declined in those two positions; that run is step 1's admission reading.
4. `VarkaIrFuzzSuite` at its defaults and the long-lane untaken-arm variant that found test 2's
   shape: the fuzz is what finds an ordering this plan did not foresee.
5. `VarkaEmittedBytesSuite` regenerated, `dev/varka_bytes_moved.py origin/master` quoted in the
   pull request: the coverage keys and fuzz blocks that move are the ones with a guarded node in
   a condition under an arm, and no other.

## 6. The measurement

No speed claim. The change removes declines; it adds mask operations only to a guard that was
unqualified, in the bodies that hold it. The pull request lists the moved bytes keys.

### 6.1 Predictions, registered before the run

1. Before the change, `abs(ym - ymm)` is declined in the untaken `CASE` arm and the later
   `COALESCE` argument on the extremes fixture; after it, served in both. If it is served before
   the change, the row closes as not holding, with that run.
2. `dev/varka_bytes_moved.py` names a minority of fuzz blocks in `fuzz`, `fuzz_long` and
   `fuzz_mixed` (a guarded node in a nested condition needs a `FAIL` draw inside a condition
   inside an arm) and no coverage row but the new one and rows whose own tree nests a checked
   condition under an arm; `option_arms` moves as always.
3. Test time: one more `VarkaGuardEdgeSuite` test of a few small kernels, under 2 s (the suite
   is 2.6 s in `suite-times.tsv`); the coverage row adds one row to each coverage-reading suite,
   under 5 s in all, at VARKA-334's sixty-row cost of 23 s scaled down.

## 7. Risks

1. **Emission order.** A guard qualified by `C` reads `C`'s condition words where its node is
   computed. Inside an inner condition that node is computed while the inner condition is, which
   is inside the outer arm, after the outer condition's word exists; but a node shared with a
   use elsewhere is emitted at its first use. A first use before the outer condition is emitted
   gives the node two chains, and then `emitArmConditionsFirst` emits `C`'s condition ahead. If
   some order slips through, the class fails to verify or reads an unset local: tests 1, 2 and 4.
2. **More cycles.** More qualified guards can meet `unqualifyArmCycles` more often; it declines
   them, never answers wrongly; test 4 and the bytes diff show how often.
3. **Owner's switch.** If question 1 is answered with a switch of its own, the change moves behind
   it, the bytes do not move at the defaults until the last commit, and the matrix gains one
   configuration.

## 8. Sequencing

One pull request, about 150 counted lines of hand-written source and plan (generated files
excluded): step 1 adds the coverage row and records the two declines; step 2 changes
`assignArmChain` and the javadocs; step 3 adds tests 1 and 2 and prints the op counts; step 4
regenerates with `dev/varka_regen_generated.sh --all` and runs the gate suites. Each step ends in a
command:

1. `build/sbt 'sql/testOnly *VarkaCoverageHarnessSuite'` lists `abs(ym - ymm)` declined in the two
   positions (the stale-or-missing check names them).
2. The same suite passes with no `DECLINED` entry for the row.
3. `build/sbt 'catalyst/testOnly *VarkaGuardEdgeSuite'` passes.
4. `dev/varka_bytes_moved.py origin/master` prints the moved keys, quoted;
   `build/sbt 'catalyst/testOnly *VarkaIrFuzzSuite *VarkaEmittedBytesSuite'` passes.

## 9. Questions for the owner

1. **A switch of its own, or under `guardUnderArm`?** The template puts new emitter behaviour
   behind a `VarkaEmitOptions` switch. *Recommendation:* under the existing `guardUnderArm`,
   because the old behaviour has no case where it is better (it only declines more), the
   reference variant already exists as `guardUnderArm=false`, and a switch adds a matrix
   configuration for no measurement. I cannot decide it because the template's rule is the
   owner's, and an exception to it is too.

## 10. Done when

An `IfElse` condition is entered with the chain its `IfElse` is reached by, so a guard inside it
is qualified by the enclosing arms and stays unqualified where the `IfElse` sits under none; the
post-pass of `collectArmContexts` and `unqualifyArmCycles` still hold (`VarkaGuardEdgeSuite`'s
"a guard inside the condition of an arm it sits under is unqualified" passes, and a case beside it
puts a guard in a nested condition under an outer arm, condemning from a lane that takes the arm
and not from one that does not, at both widths); and the coverage row `abs(ym - ymm)` is served
whole by a kernel under ANSI on the extremes fixture in the untaken `CASE` arm and as the later
`COALESCE` argument, at the defaults, with no `DECLINED` entry there.
