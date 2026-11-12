# VARKA-541: The fallback evaluates a common subexpression only where Spark's codegen would

Re-planned on 11 November 2026, after the apache/spark sync #1482 (merged as `d241c12372d`)
brought SPARK-60332 (upstream `9d6d9af0502`). The plan of 1 November (at `7e640372d25`, #1270)
ported the whole-stage filter's first-use placement into a Varka predicate generator. Upstream's
`GeneratePredicate` now places the subexpressions that way itself, so that port is withdrawn. This
plan replaces that one, which is kept unchanged in the appendix; what it held, and what each part
became, is in section 0's last list.

## 0. What exists now (admission check)

From master at `d241c12372d`, read with `git` on 11 November 2026. Nothing was dispatched and no
JVM was run. Claims marked *[I]* are read from the code, not run; step 1 of section 5 runs them.

1. **The finding, unchanged.** `VARKA-388.md` 9 item 4 (branch `varka-388`, unmerged): under
   ANSI, `s = 'x' AND make_date(2021, m, 1) < d AND make_date(2021, m, 1) > d0`, cached, over a
   row with `s` = 'y' and `m` = 13, codegen on: Spark answered and Varka raised
   `DATETIME_FIELD_OUT_OF_BOUNDS`. Varka's fallback hoisted the shared `make_date(2021, m, 1)`
   ahead of `s = 'x'`.
2. **Upstream's fix.** SPARK-60332, `9d6d9af0502`, changes `GeneratePredicate`: with
   subexpression elimination on and a top-level `And`, it calls the new
   `CodegenContext.generateConjunctsWithSubexprCodes` (`GeneratePredicate.scala:48-50`,
   `CodeGenerator.scala:2445`), which finds the common subexpressions in the whole predicate as
   before, splits the `And` chain into conjuncts, and calls each subexpression's function just
   before the first conjunct whose tree contains it. The test is
   `CodegenContext.firstReferencingExpression` (`CodeGenerator.scala:2414`), the same function
   `FilterExec.doConsume`'s CSE branch now calls (`basicPhysicalOperators.scala:537-540`). The
   conjuncts are checked as `And.eval` does: a FALSE one returns false, a NULL one sets a flag
   and the evaluation goes on. No flag restores the hoist: the migration guide entry names none.
   This is the mechanism SPARK-60315's commit message (`930c571628d`) tracked as SPARK-60392.
3. **The sync took it whole.** `GeneratePredicate.scala` and `predicates.scala` on master are
   identical to the upstream side of the merge (`700ef462308`). Against that side, Varka's
   `CodeGenerator.scala` differs only in `CodeAndComment`'s comment and constructor layout and in
   `CodeGenerator.invalidateCodegenCache`, and `basicPhysicalOperators.scala` only in the comment
   naming `VarkaConjunctOrder.codegenOrder` as a port of `generatePredicateCode`. The merge
   commit's combined diff (`git show --cc 3f30698d9fc`) is empty for all three files: no
   conflict was resolved there.
4. **Varka's fallback reaches it, with no path of its own around it.** Both filter evaluators
   build the fallback with `Predicate.create(wholeCondition.getOrElse(condition), childOutput)`
   (`VarkaFilterExec.scala:247` and `:508`), which is `GeneratePredicate.generate(e,
   SQLConf.get.subexpressionEliminationEnabled)` (`predicates.scala:80`). Where Spark would
   compile the filter, `wholeCondition` is `fallbackCondition`'s `inCodegenOrder` fold
   (`VarkaFilterExec.scala:80-90`), a left-nested `And` (`VarkaConjunctOrder.java:377-389`), so
   it takes the new conjunct path whenever a subexpression is common. Nothing else in Varka's
   sources calls `Predicate.create` or `GeneratePredicate`. A kernel batch that throws is routed
   to the same fallback (`VarkaFilterExec.scala:315-324`). Residual conjuncts are another
   matter: on a served batch they run in the row `FilterExec` the rule puts above the Varka node
   (`VarkaColumnarRule.scala:286-300`), which is Spark's own filter, but one that decides Spark's
   CSE gate from the residual alone (item 10); only a declined batch runs them in the fallback,
   with the whole condition. *[I]: so 388's reproducer now answers in
   Varka as in Spark, codegen on and off: `s = 'x'` is the first conjunct, FALSE on the row, and
   `make_date` is first referenced by the second.*
5. **Where the fallback still differs from Spark's compiled filter: the CSE gate.**
   `FilterExec.doConsume` takes its CSE branch only when `subexpressionElimination.enabled`,
   `subexpressionElimination.filterExec.enabled` (default `true`) and a common subexpression of
   the whole condition's non-null-test conjuncts that is not `CollapseProject.isCheap`
   (`basicPhysicalOperators.scala:514-518`, over `boundOtherPreds`, `:423-433`). Otherwise it
   generates no CSE at all, and each conjunct short-circuits inside itself. `GeneratePredicate`
   has no such gate: it eliminates any common subexpression. The two then differ on a common
   subexpression whose first reference sits in a short-circuited part of its first conjunct.
   `EquivalentExpressions` descends into both operands of an `Or`
   (`EquivalentExpressions.scala:158-171`: only a `ConditionalExpression` is limited to its
   always-evaluated inputs), so in

   `i > 0 AND (s = 'x' OR arr[5] > 0) AND b < 0 AND arr[5] < 10`

   under ANSI, `arr[5]` (`GetArrayItem`, `failOnError` from ANSI) is common and first referenced
   by the second conjunct. It is an `ExtractValue` over a column and a literal, so `isCheap`
   (`Optimizer.scala:1999-2011`), and Spark's compiled filter takes the non-CSE path. `i > 0`
   fuses and cannot raise; the `OR` is residual and can raise (`GetArrayItem` is not in
   `VarkaConjunctOrder`'s error-free set), so `behindRaising` (`VarkaConjunctOrder.java:138`)
   demotes `b < 0` behind it and the Varka node fuses `i > 0` alone (*[I]*: and the kernel fuses no
   array read, so `arr[5] < 10` is residual too). A leading conjunct that
   fuses is what puts a Varka node in the plan at all: without `i > 0`, nothing fuses and the
   query runs as plain `FilterExec`. On a row with `i` = 1, `s` = 'x', `b` = 1 and a two-element
   `arr`, *[I]*: Spark's `Or` stops at `s = 'x'`, `b < 0` drops the row, and the query answers;
   Varka's served batch does the same, since the residuals run in Spark's filter and its gate is
   false here too (item 10 is where the two gates part); Varka's
   declined batch runs the fallback, which calls `arr[5]` before the second conjunct and raises
   `INVALID_ARRAY_INDEX`. The same holds for any common subexpression with `filterExec.enabled`
   off. With the defaults, a non-cheap one takes Spark's CSE branch, which evaluates it before
   that conjunct too, so the two agree (the shared behaviour upstream tracks as SPARK-60774).
   With codegen off Spark itself raises, through the same `Predicate.create`: this is a
   codegen-on against codegen-off disagreement inside Spark, and Varka's fallback, on the
   compiled branch, owes Spark's codegen-on answer.
6. **Two residual differences upstream left in `GeneratePredicate` alone.** (a) An `And` of the
   top-level chain that is itself a common subexpression stays one conjunct, so a subexpression
   first referenced inside it is evaluated before its first operand (SPARK-61197). `FilterExec`
   splits every conjunct before its analysis, so its CSE branch does not do this, and on the
   compiled branch Varka's fallback can then evaluate earlier than Spark. Upstream's example is an
   `IF` condition that repeats the `AND`: in `s = 'x' AND make_date(2021, m, 1) < d AND IF(s =
   'x' AND make_date(2021, m, 1) < d, d0, d1) < d2`, `And(c1, c2)` is common, and *[I]* where it
   is also a node of the fallback's chain, `GeneratePredicate` calls `make_date` before `s = 'x'`
   and raises on 388's row, where Spark's compiled filter answers. `And` is a
   `CommutativeExpression`: its canonical form flattens the chain and sorts the operands
   (`predicates.scala:933-937`, `Expression.scala:1428-1446`), so the common `AND` matches a node
   of the chain when a prefix of `inCodegenOrder`'s left fold has the same conjuncts, in any
   order, null checks included, not only when it is written alike. The `IS NOT NULL` checks the
   optimizer infers for `s`, `m` and `d` come first in that fold, so every prefix holding `c1`
   and `c2` holds them too, and *[I]* the shape may be unreachable through a Varka filter over
   nullable columns. Step 1
   checks whether any plan reaches it. It is the same mechanism as this row's finding, open
   upstream. (Not the `NOT (c1 AND c2) OR c3` form: `BooleanSimplification` folds `a AND (NOT a
   OR c)` to `a AND c` when `a` is not nullable, `expressions.scala:546`.) (b) With
   `skipForShortcutExpr` (internal, default `false`), `GeneratePredicate` eliminates only the
   first conjunct's subexpressions, while `FilterExec` adds each conjunct to
   `EquivalentExpressions` on its own and so still eliminates across them; there the fallback
   evaluates less, never earlier, and so can only answer where Spark raises on the SPARK-60774
   shape.
7. **Where Spark does not compile the filter, the two now agree by construction.** Spark's
   `FilterEvaluatorFactory` builds the same `Predicate.create`, and `fallbackCondition` keeps
   the written order there (`VarkaFilterExec.scala:80-90`).
8. **Row 386 no longer holds this row back.** SPARK-60315 (`930c571628d`) is an ancestor of
   master, so the CSE branch this row compares with is the merged one. Row 386 (planned, not
   built) still edits `fallbackCondition` to read the legacy flag; the two rows touch the same
   method and either can land first.
9. **The fuzzer still does not draw the shape.** `VarkaSparkFuzz.withNested`
   (`VarkaSparkFuzz.scala:310`) adds independently drawn trees, so two conjuncts share a subtree
   only by chance. And it could not draw item 5's shape if it tried: `VarkaCatalystGrammar` has
   no `ExtractValue` and the view no array or struct column, and a foldable tree is folded before
   planning, so every reusable non-leaf subtree is one `CollapseProject.isCheap` rejects.
10. **A served batch's residual filter decides Spark's gate from the residual alone.**
   `rewriteFilter` builds `FilterExec(residual, varka)` (`VarkaColumnarRule.scala:299`), whose
   CSE gate reads the residual conjuncts, where Spark's reads the whole condition. A common
   subexpression of the residual is common in the whole condition too, so the residual's gate
   implies the whole's, and the difference runs one way: the whole's true, the residual's false.
   Spark's CSE branch then eliminates every common subexpression, cheap ones included
   (`subexpressionEliminationForWholeStageCodegen` takes all of `getCommonSubexpressions`,
   `CodeGenerator.scala:2201`), and calls each before its first conjunct; the residual filter
   eliminates none. In

   `year(d) > 2000 AND year(d) < 2030 AND (s = 'x' OR arr[5] > 0) AND b < 0 AND arr[5] < 10`

   under ANSI, on item 5's row with `d` in 2021, *[I]*: `year(d)` is common and not cheap, so
   Spark's compiled filter takes the CSE branch, calls `arr[5]` before the `OR` and raises
   `INVALID_ARRAY_INDEX` (SPARK-60774's early evaluation); the two `year` conjuncts fuse, and the
   served batch's residual filter, whose one common subexpression is the cheap `arr[5]`, takes
   the non-CSE path and answers. The declined batch runs the whole condition and raises, today
   and with 2.1; codegen off, all cells raise through `Predicate.create`. So the served codegen-on
   cell answers where Spark raises, and after 2.1 a query's served and declined batches would
   disagree with each other. Section 2.4 and question 4.

### What the 1 November plan held, and what each part became

The plan is kept unchanged in the appendix. Its parts:

- 2.1, a Varka predicate generator with a CSE path placing each state before its first conjunct:
  **withdrawn**, upstream's `GeneratePredicate` does it (items 2 to 4). Its non-CSE path, used
  where Spark's gate is false, **stays**, smaller: a flag to `GeneratePredicate`, not a
  generator (2.1 below).
- 2.2, the confs resolved on the driver: **stays**, for that gate only.
- 2.3, a test holding `codegenOrder` to the CSE branch's `IsNotNull` order: **stays** (2.2).
- 2.4, the fuzzer step: **stays** (2.3).
- Question 1 (port the placement or drop the CSE) and question 2 (before or after row 386):
  **settled**, by upstream and by item 8.
- 9 point 2, naming `VarkaFallbackPredicate` in `basicPhysicalOperators.scala`'s comment:
  **withdrawn**, no such generator is built.
- 0 item 4 and the non-goal "Spark hoists there too": **no longer true** after `9d6d9af0502`.
- Section 4's predictions P1 to P5 were never scored, since nothing was built; section 4 below
  replaces them.
- Size: medium, about two days, becomes small.

## 1. Goal and non-goal

**Goal.** Where Spark would compile the filter, Varka's fallback evaluates a shared
subexpression no earlier than Spark's whole-stage filter does, under each value of
`subexpressionElimination.enabled` and `.filterExec.enabled`, so a row an earlier conjunct or
operand drops answers in Varka as in Spark, and a row Spark's CSE branch raises on is not
answered by a served batch's residual filter (item 10, question 4); 388's reproducer is
committed as a regression; the fuzzer draws the shape.

**Non-goal.** No change to the uncompiled branch (item 7) or to the kernel, the emitter or the
IR: no emitted byte moves. No Varka fix of SPARK-61197 (item 6 (a)), of SPARK-60774, or of item
6 (b): each is committed as a known difference with its ticket, and follows upstream's fix.
Projections, as the 1 November plan's question 4 found: none known.

## 2. Design

### 2.1 Spark's CSE gate, resolved on the driver

Where `fallbackCondition` takes the compiled branch, the gate is resolved on the driver beside
it, where each node builds its evaluator factory with `wholeCondition = Some(fallbackCondition)`:
in `VarkaFilterExec.doExecuteColumnar` (`VarkaFilterExec.scala:195-206`), for
`VarkaFilterEvaluatorFactory`, and in `VarkaFilterColumnarToRowExec.doExecute` (`:448-459`), for
`VarkaFilterToRowEvaluatorFactory`; `VarkaFilterExec.doExecute` throws and is no path. Each
factory takes the gate as an argument beside `wholeCondition`, so it needs no field on the
nodes, and no rebuild of a node in `VarkaColumnarRule` can lose it. It is `FilterExec`'s test
over the whole condition, `wholeCondition.getOrElse(condition)`, not the node's fused
`condition`: subexpression elimination and `filterExec.enabled` on, non-null-test conjuncts
present, and a common subexpression of them, each added to `EquivalentExpressions` on its own
and bound as `FilterExec` binds them, that is not `CollapseProject.isCheap`.

One copy (question 1's (a)): the test moves out of `FilterExec.doConsume` into a method of
`GeneratePredicateHelper` (which already holds `splitNotNullPreds`) that takes the non-null-test
conjuncts and their `EquivalentExpressions` as arguments. `FilterExec` passes its cached
`otherPredsEquivalentExpressions` (`basicPhysicalOperators.scala:429`), which its CSE branch
hands on to `subexpressionEliminationForWholeStageCodegen` as today, so no second analysis is
built, and exposes the result as a `private[sql]` lazy value. Varka reads that value from a
`FilterExec(whole, child)` built on the driver for the purpose and never executed, so the split,
the binding against the tightened `output` and the analysis are `FilterExec`'s own, not a Varka
copy. That is about 15 lines in Varka's edit of `basicPhysicalOperators.scala` (the method, its
comment, the call and the value), lines a sync can conflict on, and about 10 in
`VarkaFilterExec.scala` (the two call sites and the factories' argument). The evaluators build
the fallback through a small `CodeGeneratorWithInterpretedFallback` beside `VarkaFilterExec`, as
`Predicate` is, whose generated object is `GeneratePredicate.generate(bound, gate)`. Gate true or
the uncompiled branch: exactly today's `Predicate.create`. Gate false: no elimination, so each
conjunct, and each operand inside it, short-circuits as Spark's `generatePredicateCode` does.

### 2.2 The `IsNotNull` order against the CSE branch

The CSE branch interleaves its `IsNotNull` checks by a loop of its own, which master's comment
holds in lockstep with `generatePredicateCode`, the loop `VarkaConjunctOrder.codegenOrder`
ports. A test holds `codegenOrder` to the CSE branch's order on `WholeStageCodegenSuite`'s
SPARK-60315 and SPARK-60332 cases, and the row ports a difference if one shows (*[I]: none found
by reading*).

### 2.3 The fuzzer draws the shape

`withNested` gains one step after its existing draws, with its own salt, so every current seed's
composition is unchanged up to the addition: on a filter with a nested conjunct, one time in
four, a second conjunct shares a subtree with that conjunct, compared with a fresh operand. The
shared subtree is, one time in two each:

- a non-leaf subtree of the nested conjunct's own tree, which `isCheap` always rejects (item 9):
  the SPARK-60774 shape, where Spark's gate is true;
- a cheap extract `arr[k]`, `k` drawn from 0 to 3, which the step puts into both conjuncts:
  item 5's shape, where the gate is false. It needs a column the view lacks, `arr`, built as
  `array(i, i)` from the row's own values so that no seed's data draw moves, and only this step
  draws it.

One time in two, the step then wraps the conjunct the subtree came from, un-negated, in an `OR`
with a fresh comparison first, and places it before the reusing conjunct, which puts the shared
subtree in a short-circuited operand ahead of its second use (a negated `OR` is an `AND` and
loses the shape). Only wrapped draws count toward the shape's reach: `VarkaSparkFuzzSuite`
counts them in its summary, per production. *[I]: that a Varka filter is planned over a view
with an array column; test 1's item 5 case needs the same, so step 1 checks it, and if it is
not, the extract production is dropped and P3 counts the other alone.* The row's added line
(from `READING_SQLANCER.md` and `READING_COMET.md`) asks the same of a grammar production that
keeps a duplicated raising subexpression in a form Spark's optimizer does not fold; this step is
that production. The same line's raising dead `CASE` arms are a third production of the step,
the SPARK-60774 shape again, where the census should find Varka agreeing with Spark's codegen. A
disagreement of a committed known difference (item 6 (a))
is matched by its reproducer's shape and reported under that name, not as a failure, the way
row 545 (planned) means to label a codegen-against-interpreter disagreement.

### 2.4 A served batch's residual filter (question 4)

Item 10's difference is in the plan, not the fallback: the residual `FilterExec` is Spark's, and
its gate reads what it is given. Chosen (question 4's (a)): `rewriteFilter` asks 2.1's gate
at planning of `FilterExec(condition, child)` and of `FilterExec(residual, varka)`, and when the
first is true, the second false, and a residual conjunct references a common subexpression of
the whole condition, it leaves the filter to Spark (`None`, the path where no conjunct fuses).
The plan is then Spark's own and answers as Spark does, at the cost of the fusion on that shape:
a non-cheap shared subexpression beside a residual that shares one too. About 10 lines in
`VarkaColumnarRule.scala`.

## 3. Tests

Each Varka query runs in four cells: the kernel serving and declined
(`setDeclineKernelForTesting`), codegen on and off; only the declined cells run residual
conjuncts in the fallback (item 4), and the served cells run them in a `FilterExec` with a gate
of its own (item 10).

1. `VarkaDifferentialSuite`: 388's reproducer, a leading fused conjunct ahead of it so a Varka
   node is planned; item 5's case, and the same with a non-cheap shared subexpression, each under
   `filterExec.enabled` on and off; item 10's case, likewise; item 6 (a)'s case.
2. A unit test of 2.1's gate on the cases of `WholeStageCodegenSuite`'s CSE tests: Varka's
   driver answer equals the branch `FilterExec.doConsume` takes; and of 2.4's check on item 10's
   case and on item 5's, which it must leave fused.
3. `VarkaConjunctOrderSuite`: 2.2.
4. `VarkaSparkReproducerSuite`: 388's reproducer and item 5's case as `status: regression`;
   item 10's case likewise, under question 4's (a); item 6 (a)'s case, if step 1 reaches it,
   as a known difference naming SPARK-61197.
5. The fuzzer step: a selftest that a seed's compositions before the step are unchanged, and
   that a wrapped draw puts the un-negated `OR` before the reusing conjunct.

## 4. Predictions, registered before the build

- P1 (step 1): at master, 388's reproducer agrees with Spark in all four cells; item 5's case
  agrees in the served cells and in both codegen-off cells (raising), and raises in Varka's
  declined codegen-on cell where Spark answers; item 6 (a)'s case, over nullable columns, is not
  reached (its `AND` is not a node of the fold) and agrees; over non-nullable ones it raises in
  the declined codegen-on cell where Spark answers; item 10's case raises in Spark codegen on, in
  Varka's declined cell and in all codegen-off cells, and answers in Varka's served codegen-on
  cell.
- P2: after 2.1, item 5's case agrees in all four cells, and a non-cheap shared subexpression in
  the same place raises in both with the defaults; after 2.4 under question 4's (a), item 10's
  case plans no Varka node and agrees in all four cells; item 6 (a)'s case, where reached, still
  differs.
- P3: the nightly census with the new step finds at least one wrapped shared-subtree
  composition per 100 nested ones for each production it keeps, and no disagreement but the
  named known differences.
- P4: added test time 9 to 16 s, under 20 s. By count, test 1 is about 32 queries (388's four
  cells; items 5 and 10 with their non-cheap twin under two `filterExec.enabled` values; item
  6 (a)), at the 0.23 s a differential test took in VARKA-336 (76 in 17.8 s), about 8 s, and
  tests 2 to 5 about 4 s more: 12 s. The calibration table's rows for tests added to the sql/core
  differential, as `sql/varka/AGENTS.md` asks, are VARKA-334 (at least 1.3) and VARKA-336 (0.75
  quiet, at most 1.25 beside another agent's JVMs), which put 12 s at 9 to 16 s. The rows the
  1 November plan and the first draft cited (VARKA-315, VARKA-318, VARKA-379) priced new suites
  and switch suites, not additions to the differential.
- P5: no emitted byte moves.

## 5. Order, files and size

1. A laptop debugging check by the build agent, before any code: test 1's queries at master in
   the four cells, scoring P1, item 10's case among them, which also checks that a Varka filter
   is planned over a view with an array column. If item 5's declined cell agrees at master,
   2.1 is dropped: item 5's case is committed as a regression test alone, the row is 2.4, the
   tests and the fuzzer step, and the outcome records why. A served cell alone cannot score it.
2. One pull request: 2.1, 2.4, the tests, the fuzzer step; its PR Build and an option-matrix
   dispatch, on the owner's word.

| file | what |
|---|---|
| `sql/core/.../execution/VarkaFilterExec.scala` | the gate in `VarkaFilterExec.doExecuteColumnar` and `VarkaFilterColumnarToRowExec.doExecute`; both factories take it |
| `sql/core/.../execution/basicPhysicalOperators.scala` | the gate's one copy (question 1) |
| `sql/core/.../execution/VarkaColumnarRule.scala` | 2.4's check (question 4) |
| `.../codegen/VarkaConjunctOrder.java` | the CSE branch's order, if it differs |
| tests | section 3 |
| `sql/core/.../execution/VarkaSparkFuzz.scala` | the shared-subtree step, the `arr` column, the labels |
| `sql/varka/fuzz/spark/` | the reproducers |

Size: **small**, about a day and a half.

## 6. Out of this row

- SPARK-60774 (a subexpression first met in a conditional part of a conjunct is evaluated before
  it, by both generators) and SPARK-61197 (item 6 (a)). Varka follows upstream's fix of each.
- Item 6 (b), under the internal `skipForShortcutExpr`.
- A whole-stage compile that fails at run time and falls back (`hugeMethodLimit`), row 380's gap.

## 7. Questions for the owner

*Answered by the owner on 11 November 2026: 1 (a), one copy in `GeneratePredicateHelper`; 2
yes, 2.1 is kept if step 1 confirms item 5; 3 yes, an upstream ticket is filed for item 5.
4 (a), on 11 November 2026 after it was added: the rule leaves such a filter to Spark (2.4),
and 2.4's check is removed if SPARK-60774's fix makes Spark answer there.*

*11 November 2026: filed as SPARK-61459
(https://issues.apache.org/jira/browse/SPARK-61459), after a reproduction on Apache Spark
master `183ccbe75d4` with no Varka code: with whole-stage codegen off, `(s = 'x' OR arr[5] > 0)
AND b < 0 AND arr[5] < 10` under ANSI raises `INVALID_ARRAY_INDEX`, and with it on Spark
answers. A non-cheap shared extract (`arr[b + 6]`) raises in both modes, which is SPARK-60774;
the ticket names that overlap. Item 5 now has a measured reproduction upstream; step 1 still
reads it through Varka's fallback.*

1. **One copy of Spark's gate or two.** (a) Move the test into `GeneratePredicateHelper`, called
   by `FilterExec` and Varka: one copy, a few more lines in a Spark file Varka already edits. (b)
   A copy beside `VarkaFilterExec`, held to `FilterExec`'s branch by test 2. *Recommended: (a)*,
   since `codegenOrder` already shows a hand-kept second copy needing a lockstep comment.
2. **Keep 2.1 if step 1 confirms item 5.** *Recommended: yes*: it is Spark's default session
   against Varka's on a cheap raising extract. Without it the row is tests and the fuzzer step,
   and item 5 is committed as a known difference.
3. **An upstream ticket for item 5.** It is a disagreement inside Spark too: `GeneratePredicate`
   applies no `filterExec` gate, so a filter with codegen off raises where codegen on answers. A
   JIRA, filed only on the owner's word, would let `GeneratePredicate` take the gate for filter
   conditions and fix both. *Recommended:* file it, and build 2.1 meanwhile; 2.1 is dropped when
   such a fix syncs.
4. **A served batch's residual filter where Spark's gate is true and its own false (item 10).**
   (a) 2.4: the rule leaves such a filter to Spark; exact, about 10 lines, and the shape loses
   its fusion. (b) Give the residual `FilterExec` the whole condition's gate: a constructor field
   on a Spark case class that every sync and every `FilterExec(c, child)` match meets, for a
   shape (a) already makes exact. (c) Commit it as a known difference naming SPARK-60774, since
   Spark's raise there is that ticket's early evaluation: no code, but after 2.1 a served and a
   declined batch of one query disagree with each other, and Varka answers where Spark's default
   raises. *Recommended: (a)*, since row answers follow vanilla Spark, and the shape (a non-cheap
   shared subexpression beside a residual that shares one too) is rare enough that its fusion is
   a small loss; if SPARK-60774's fix makes Spark answer there, (a)'s check is removed with it.

## 8. Done when

388's reproducer, committed as a regression, answers as Spark's default does through Varka's
fallback and kernel alike, in all four cells; item 5's case does too, under `filterExec.enabled`
on and off, through 2.1, or, if step 1 finds it agreeing at master, as a regression test with
2.1 dropped and the reason in the outcome; item 10's case answers as Spark's default does in all
four cells under question 4's (a); item 6 (a)'s case is committed as a known difference naming
SPARK-61197 if step 1
reaches it, and recorded as unreachable with the plan that shows it otherwise; the fuzzer's
census reports wrapped shared-subtree compositions of each production it keeps reached with no
disagreement but the named known differences; no emitted byte moves.

## 9. Outcome

(Written when the work lands.)

## Appendix: the plan of 1 November 2026, superseded

The plan as committed at `7e640372d25` (#1270), unchanged but for its headings, one level down.
Its section numbers are its own; this file's sections above replace it.

### 0. What exists now (admission check)

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

#### What differs from the row text

- The row says codegen evaluates "each one just before the first conjunct that reads it". That
  holds only on the CSE path. Without a non-cheap common subexpression, or with
  `filterExec.enabled` off, Spark generates no CSE at all; and "reads it" is "whose tree contains
  it", conditional branches included, so Spark's codegen also evaluates a shared subexpression
  that its first conjunct would have skipped by a short circuit.
- The disagreement exists only where Spark compiles the filter. The uncompiled branch already
  agrees (item 4).
- The row sits behind row 386's sync for its port target (item 6).
- Size: medium. A port of the first-use grouping is a generator of Varka's own (question 1).

### 1. Goal and non-goal

**Goal.** Where Spark would compile the filter, Varka's fallback evaluates each shared
subexpression where Spark's whole-stage filter does, under every value of the three confs
(`subexpressionElimination.enabled`, `.filterExec.enabled`, `.skipForShortcutExpr`), so that a row
an earlier conjunct drops answers in Varka as in Spark. The fuzzer draws the shape.

**Non-goal.** No change to the uncompiled branch (Spark hoists there too, SPARK-60392). No change
to the kernel, the emitter or the IR: no emitted byte moves. No change to projections:
`UnsafeProjection` over a projection list is Spark's own non-whole-stage path and Varka's
projection fallback, and a whole-stage projection hoists its common subexpressions as well
(question 4).

### 2. Design

#### 2.1 Varka's fallback predicate, generated as the whole-stage filter does

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

#### 2.2 The settings are resolved on the driver

The fallback predicate is built lazily on the executor. `fallbackCondition`'s caller resolves the
three confs and Spark's CSE-path test once on the driver, into a small serializable
`FallbackSettings` beside `sessionEmit`, so the planning-time order and the task's predicate
agree, as row 386 carries the legacy flag.

#### 2.3 Which order the CSE path follows

The CSE path interleaves the `IsNotNull` checks by its own loop (direct check, synthetic column
check, nested check, leftovers last), which master's comment holds "in lockstep" with
`generatePredicateCode`. `VarkaConjunctOrder.codegenOrder` ports the latter. This row adds a test
that holds `codegenOrder` to the CSE path's order on the same cases, and ports the difference if
one shows (*[I]: the plan found none by reading; the two loops' rules read alike*).

#### 2.4 The fuzzer draws the shape

`withNested` gains one step after its existing draws from `n`, so every current seed's
composition is unchanged up to the addition: on a filter with a nested conjunct, one time in
four, a second conjunct is built that reuses a non-leaf subtree of an existing conjunct (drawn
from the conjunct's tree), compared against a fresh operand. The step has its own salt. The
census then has compositions with a non-cheap shared subtree; `VarkaSparkFuzzSuite` counts them
in its summary so the done-when can read that the shape was reached.

### 3. Tests

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

### 4. Predictions and costs, registered before the build

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

### 5. Order, files and size

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

### 6. Out of this row

- The uncompiled branch (SPARK-60392). Varka follows Spark there.
- A whole-stage compile that fails at run time and falls back (`hugeMethodLimit`), row 380's
  gap, as `VARKA-388.md` 9 correction 3 says.

### 7. Questions for the owner

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

### 8. Done when

388's reproducer, committed as a regression, answers as Spark's default does through Varka's
fallback and kernel alike, codegen on and off; the short-circuited-arm case raises as Spark does
(under question 1's (b)); the three confs flipped agree; the fuzzer's census reports
shared-subtree compositions reached with no disagreement; no emitted byte moves.

### 9. Open points for the lead

1. `m7` row 389 is the warm-up's traps, while `varka-388`'s record called this finding row 389.
   `VARKA-388.md`, when committed under row 386's question 3, wants a note naming 541.
2. `VarkaConjunctOrder`'s comment in `basicPhysicalOperators.scala` names `codegenOrder` as the
   port of `generatePredicateCode`; after this row it names `VarkaFallbackPredicate` for the CSE
   branch too, in Varka's edit of that file.

### 10. Outcome

(Written when the work lands.)
