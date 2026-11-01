# VARKA-541: The fallback evaluates a common subexpression only where Spark's codegen would

## 0. What exists now (admission check)

From the plans worktree at `130280562f3` (master `5c660201b35` plus plan commits), the local
branch `varka-388`, and apache/spark's master (`upstream/master`, fetched 1 November 2026), read
with `git` and `gh api` GETs. Nothing was dispatched and no JVM was run. `VARKA-388.md` below is
the plan on the unmerged branch, read with `git show varka-388:sql/varka/plans/m7/VARKA-388.md`.

1. **The finding.** `VARKA-388.md` 9 item 4: under ANSI, `s = 'x' AND make_date(2021, m, 1) < d
   AND make_date(2021, m, 1) > d0`, cached, over a row with `s` = 'y' and `m` = 13, codegen on:
   Spark answered and Varka raised `DATETIME_FIELD_OUT_OF_BOUNDS`; codegen off, both raised. A
   throwaway test, not committed. It is recorded nowhere on master but `VARKA-386.md` 0 item 8 and
   this row.
2. **Varka's fallback.** Both filter evaluators build the fallback with
   `Predicate.create(wholeCondition.getOrElse(condition), childOutput)`
   (`VarkaFilterExec.scala:245` and `:504`), which is `GeneratePredicate.generate(e,
   SQLConf.get.subexpressionEliminationEnabled)` (`predicates.scala:79-81`). Its generated `eval`
   puts every common subexpression's code first (`GeneratePredicate.scala:62-64`, `$evalSubexpr`
   before `${eval.code}`). `EquivalentExpressions` would keep a conditionally evaluated `AND`
   operand out only under `spark.sql.subexpressionElimination.skipForShortcutExpr`, default
   `false` (`SQLConf.scala:1682-1690`). So with the default confs every subexpression shared by
   two conjuncts is evaluated before the first conjunct.
3. **What Spark's whole-stage filter does.** `FilterExec.doConsume`
   (`basicPhysicalOperators.scala:291-457` on master) takes one of two paths:
   - **the CSE path**, when `subexpressionEliminationEnabled`,
     `spark.sql.subexpressionElimination.filterExec.enabled` (default `true`, version 4.2.0,
     SPARK-56032) and a common subexpression of the non-null-test conjuncts that is not
     `CollapseProject.isCheap`. Each state is emitted just before the first conjunct whose tree
     contains it (`statesByFirstUse`, `boundOtherPreds.indexWhere(_.exists(...))`), and the
     conjunct's `IsNotNull` checks are interleaved by rules of its own;
   - **otherwise `generatePredicateCode`**, no CSE: each conjunct generated in turn, each
     short-circuiting the row.

   `VarkaConjunctOrder.codegenOrder` ports the second path's order only.
4. **What Spark's interpreted filter does.** `FilterExec.doExecute` uses
   `FilterEvaluatorFactory`, whose predicate is `Predicate.create(condition, childOutput)`
   (`FilterEvaluatorFactory.scala:39`), the same hoist as Varka's. Upstream lists that half of
   the mechanism as SPARK-60392. Varka's `fallbackCondition` already takes the uncompiled branch
   exactly where Spark would not compile the filter (`VarkaFilterExec.scala:80-89`), so there the
   two agree, both raising.
5. **The kernel.** A fused node that can raise declines its batch to the fallback
   (`VARKA-388.md` 3.2), so the kernel answers or raises as the fallback does, and a fix in the
   fallback is a fix for both.
6. **Upstream moved the CSE path too.** On apache/spark's master, SPARK-60315
   (`930c571628d`, the commit row 386 syncs) changed both paths: its CSE branch keeps
   `statesByFirstUse` and adds the `nullableSeen` flag and `precedingNotNullChecks` there as well
   (`upstream/master:.../basicPhysicalOperators.scala:536-590`). So the CSE path's order, the
   thing this row ports, is not the same on Varka's master and on the sync that row 386 waits on.
7. **The fuzzer does not reach the shape.** `VarkaSparkFuzz.withNested`
   (`VarkaSparkFuzz.scala:310-335`) adds independently drawn trees, so two conjuncts share a
   non-cheap subtree only by chance, and no seed's census has recorded one.

### What differs from the row text

- The row says codegen evaluates "each one just before the first conjunct that reads it". That
  holds only on the CSE path. Without a non-cheap common subexpression, or with
  `filterExec.enabled` off, Spark generates no CSE at all; and "reads it" is "whose tree contains
  it", conditional branches included, so Spark's codegen also evaluates a shared subexpression
  that its first conjunct would have skipped by a short circuit.
- The disagreement exists only where Spark compiles the filter. The uncompiled branch already
  agrees (item 4).
- The row sits behind row 386's sync for its port target (item 6).
- Size: medium. A port of the first-use grouping is a generator of Varka's own (question 1).

## 1. Goal and non-goal

**Goal.** Where Spark would compile the filter, Varka's fallback evaluates each shared
subexpression where Spark's whole-stage filter does, under every value of the three confs
(`subexpressionElimination.enabled`, `.filterExec.enabled`, `.skipForShortcutExpr`), so that a row
an earlier conjunct drops answers in Varka as in Spark. The fuzzer draws the shape.

**Non-goal.** No change to the uncompiled branch (Spark hoists there too, SPARK-60392). No change
to the kernel, the emitter or the IR: no emitted byte moves. No change to projections:
`UnsafeProjection` over a projection list is Spark's own non-whole-stage path and Varka's
projection fallback, and a whole-stage projection hoists its common subexpressions as well
(question 4).

## 2. Design

### 2.1 Varka's fallback predicate, generated as the whole-stage filter does

A new `VarkaFallbackPredicate.create(conjuncts, notNullPreds, childOutput, settings)` (sql/core,
Scala, beside `VarkaFilterExec`) returns a `BasePredicate` whose `eval` is generated from the
conjuncts in the fallback's order (`inCodegenOrder`'s list, not its `AND` fold):

- **The non-CSE path**, when Spark's would be taken: each conjunct's code in turn, `if (isNull ||
  !value) return false;` after each, with `useSubexprElimination = false`. This is exactly
  `GeneratePredicate.generate(fold, false)` in effect, and is built as that.
- **The CSE path**, when Spark's test holds (the three conditions of 0 item 3, over the
  non-null-test conjuncts bound as `FilterExec` binds them): an `EquivalentExpressions` over the
  bound conjuncts, `ctx.subexpressionEliminationForWholeStageCodegen` for the states, the states
  grouped by the index of the first conjunct whose tree contains each (Spark's own `exists` test),
  and each group's `evaluateSubExprEliminationState` emitted just before its conjunct, the null
  tests where the fallback's order already puts them.

Spark's `EquivalentExpressions`, `CollapseProject.isCheap` and `CodegenContext` are reused, so the
CSE analysis is Spark's; only the placement loop is ported. *[I]: that
`subexpressionEliminationForWholeStageCodegen` generates over `INPUT_ROW` with `currentVars`
unset is an inference from reading `CodegenContext`; step 1 checks it before anything else is
built.*

### 2.2 The settings are resolved on the driver

The fallback predicate is built lazily on the executor. `fallbackCondition`'s caller resolves the
three confs and Spark's CSE-path test once on the driver, into a small serializable
`FallbackSettings` beside `sessionEmit`, so the planning-time order and the task's predicate
agree, as row 386 carries the legacy flag.

### 2.3 Which order the CSE path follows

The CSE path interleaves the `IsNotNull` checks by its own loop (direct check, synthetic column
check, nested check, leftovers last), which master's comment holds "in lockstep" with
`generatePredicateCode`. `VarkaConjunctOrder.codegenOrder` ports the latter. This row adds a test
that holds `codegenOrder` to the CSE path's order on the same cases, and ports the difference if
one shows (*[I]: the plan found none by reading; the two loops' rules read alike*).

### 2.4 The fuzzer draws the shape

`withNested` gains one step after its existing draws from `n`, so every current seed's
composition is unchanged up to the addition: on a filter with a nested conjunct, one time in
four, a second conjunct is built that reuses a non-leaf subtree of an existing conjunct (drawn
from the conjunct's tree), compared against a fresh operand. The step has its own salt. The
census then has compositions with a non-cheap shared subtree; `VarkaSparkFuzzSuite` counts them
in its summary so the done-when can read that the shape was reached.

## 3. Tests

1. `VarkaFallbackPredicateSuite` (sql/core, `SharedSparkSession`): for each case, the generated
   `eval` against Spark's own whole-stage `FilterExec` over the same rows, outcome compared with
   `VarkaMaybeThrows.difference`: 388's reproducer; the shared subtree in the first conjunct's
   short-circuited `OR` arm (where Spark raises); a cheap shared subtree (`s.x > 5 AND s.x < 100`,
   non-CSE path); each of the three confs flipped.
2. `VarkaConjunctOrderSuite`: the order of 2.3 against the CSE path's on the merged commit's
   `WholeStageCodegenSuite` cases.
3. `VarkaDifferentialSuite`: 388's reproducer through a Varka filter, served and declined
   (`setDeclineKernelForTesting`), codegen on and off, against Spark's answer; a residual
   conjunct beside a fused one.
4. `VarkaSparkReproducerSuite`: the reproducer committed as `status: regression`.
5. The fuzzer's new step: a selftest that a seed's compositions before the step are unchanged.

## 4. Predictions and costs, registered before the build

- P1: before the fix, test 3's reproducer differs only with codegen on ("only Varka raises"),
  served and declined alike; after it, it agrees in all four cells.
- P2: the short-circuited-arm case of test 1 raises in Spark; a non-CSE fallback (question 1's
  option (a)) would answer it, and the ported one raises (*[I]: from reading `statesByFirstUse`*).
- P3: the nightly census with the new step finds at least one shared-subtree composition per 100
  nested ones, and no disagreement after the fix.
- P4: added test time under 30 s, at the calibration table's sql/core ratios (VARKA-318,
  VARKA-336) over 388's differential test, which ran in about 6 to 21 s (`VARKA-388.md` 9.2, on its
  branch).
- P5: no emitted byte moves.

## 5. Order, files and size

1. Step 1, a laptop debugging check by the build agent: generate the CSE path's predicate for
   388's reproducer and compile it (the [I] of 2.1).
2. After row 386's sync and pull request merge: the generator against the merged CSE path, the
   settings, the tests, the fuzzer step; one pull request, its PR Build and an option-matrix
   dispatch.

| file | what |
|---|---|
| `sql/core/.../execution/VarkaFallbackPredicate.scala` | new: the two paths |
| `sql/core/.../execution/VarkaFilterExec.scala` | the settings; both evaluators call it |
| `.../codegen/VarkaConjunctOrder.java` | the CSE path's order, if it differs |
| tests | 3's list |
| `sql/core/.../execution/VarkaSparkFuzz.scala` | the shared-subtree step |
| `sql/varka/fuzz/spark/` | the reproducer |

Size: **medium**, about two days.

## 6. Out of this row

- The uncompiled branch (SPARK-60392). Varka follows Spark there.
- A whole-stage compile that fails at run time and falls back (`hugeMethodLimit`), row 380's
  gap, as `VARKA-388.md` 9 correction 3 says.

## 7. Questions for the owner

1. **Port the placement, or drop the CSE.** (a) The fallback generates no CSE where Spark
   compiles: small, agrees with the reproducer and with Spark's non-CSE path exactly, and differs
   from Spark only where the shared subtree sits in a short-circuited part of its first conjunct
   (Spark raises, Varka answers). (b) Port the first-use placement (2.1). *Recommended: (b)*, since
   row answers follow vanilla Spark always; (a) is half a day smaller.
2. **Before or after row 386.** Both edit `fallbackCondition`, and the CSE path Varka would port
   changed upstream in the commit 386 syncs (0 item 6). *Recommended: after 386's pull request*,
   so the port is of one CSE path, not two.
3. **The fuzzer's step on the nightly.** *Recommended:* the step joins every nested composition
   draw (behind no switch), since it adds a conjunct and leaves the rest of each seed's draw as
   it was.
4. **Projections.** A whole-stage `ProjectExec` evaluates its common subexpressions before every
   output, as `UnsafeProjection` does, so no projection analogue is known. *Recommended:* none in
   this row; the census of P3 runs projections too and would show one.

## 8. Done when

388's reproducer, committed as a regression, answers as Spark's default does through Varka's
fallback and kernel alike, codegen on and off; the short-circuited-arm case raises as Spark does
(under question 1's (b)); the three confs flipped agree; the fuzzer's census reports
shared-subtree compositions reached with no disagreement; no emitted byte moves.

## 9. Open points for the lead

1. `m7` row 389 is the warm-up's traps, while `varka-388`'s record called this finding row 389.
   `VARKA-388.md`, when committed under row 386's question 3, wants a note naming 541.
2. `VarkaConjunctOrder`'s comment in `basicPhysicalOperators.scala` names `codegenOrder` as the
   port of `generatePredicateCode`; after this row it names `VarkaFallbackPredicate` for the CSE
   branch too, in Varka's edit of that file.

## 10. Outcome

(Written when the work lands.)
