# VARKA-608: A filter's per-row fallback evaluates Spark's condition tree, not the conjuncts' fold

## 1. Where this came from

Milestone 8's row 608, added 12 November 2026 on the owner's word from row 541's build. The
#1528 description ("Was this patch tested", the trade-offs left) ends on it: "outside this change,
with codegen off and no residual conjunct the fallback evaluates the fused conjuncts' left fold
rather than Spark's tree". Row 541's item 7 (`VARKA-541.md` 0) says the two agree by construction
where Spark does not compile the filter; that holds only where the Varka node carries the filter's
own condition, which today is only with a residual conjunct.

## 2. The admission check, done

Read at origin/master `e5d70012bdc` (20 November 2026), after row 541 (#1528, `88d9f785f99`, and
its follow-up #1538) landed, which the row waited for. The problem holds, by reading; nothing was
run, since a sql/core test compile in a fresh worktree does not fit this planning pass's budget,
and step 1 is the run.

1. `VarkaColumnarRule.rewriteFilter` builds the node as
   `mkVarka(predicate.fusedConjuncts.asScala.reduceLeft(And(_, _)), child,
   residual.map(_ => condition))`: with no residual conjunct, `wholeCondition` is `None` and the
   node's `condition` is the left fold of the fused conjuncts, which
   `VarkaExpressionCompiler.conjuncts` flattened from every `AND` of the spine.
2. `VarkaFilterExecBase.fallbackCondition` is `wholeCondition.getOrElse(condition)`, reordered by
   `VarkaConjunctOrder.inCodegenOrder` only when `compiledBySpark`. So with whole-stage codegen
   off the fallback is `Predicate.create`-equivalent code over the fold
   (`VarkaFallbackPredicate`, eliminating subexpressions on
   `spark.sql.subexpressionElimination.enabled`, the 541 gate).
3. Row 541's guard, `fallbackHoistsIntoAnAnd`, is `compiledBySpark && ...`, so it does not look at
   this branch; nor does `residualMissesElimination`, which needs a residual.
4. Spark's `FilterExec` without codegen runs `Predicate.create` over its own tree, and
   `GeneratePredicate` with elimination on keeps an `AND` of the spine that is a common
   subexpression as one conjunct and calls the subexpressions inside it before its first
   operand (SPARK-61197, `VARKA-541.md` 0 item 6 (a)). The fold has no such `AND` where the tree
   is not its own left fold, so the two can differ on which conjunct runs first.
5. A filter directly over a scan never shows it. `InMemoryScans` plans a filter over a cached
   relation through `PhysicalOperation`, which splits the condition into conjuncts, and
   `SparkPlanner.pruneFilterProject` rebuilds it as `filterPredicates.reduceLeftOption(And)`
   (`SparkPlanner.scala:100`): the `FilterExec` that `rewriteFilter` receives is already the left
   fold, and Spark's own filter evaluates that fold too. `FileSourceStrategy` and
   `DataSourceV2Strategy` rebuild theirs from the split conjuncts the same way. Only a filter that
   `BasicOperators` plans, `FilterExec(condition, planLater(child))` over a child that is not a
   scan, keeps the optimizer's tree.

The row's sketch is the reproducer once it sits over such a child. Row 541's fixture
`varka_cse_and_nn` (`VarkaDifferentialSuite`, non-nullable `i`, `s`, `m`, `d`, `d0`, `d1`, `d2`;
rows `i = 1, m = 13` and `i = 2, m = 1`) has every column but the leading `j`; with `i > 0` in
`j > 0`'s place and a `LIMIT` that keeps the filter off the scan (no rule pushes a filter
through a limit), `SELECT i FROM (SELECT * FROM varka_cse_and_nn LIMIT 10) WHERE i > 0 AND
(i > 1 AND make_date(2021, m, 1) < d) AND IF(i > 1 AND make_date(2021, m, 1) < d, d0, d1) <
make_date(2021, m, 1)` should raise `DATETIME_FIELD_OUT_OF_BOUNDS` in Spark with codegen off
and elimination on, and answer `[2]` in Varka's fallback. Step 1 confirms it before any change;
if the physical `FilterExec`'s condition is its own left fold, the test takes another non-scan
child (an aggregate or a join) whose filter is not its fold, as the row says.

What the check would have rejected: a 541 change that already passed the tree, or a fallback
that already rebuilt it. Neither is on master.

## 3. The design

### 3.1 Carry the tree where it is not the fold

`rewriteFilter` passes `wholeCondition = Some(condition)` whenever there is a residual (as now)
**or** the condition it receives is not semantically its fused conjuncts' left fold. The node's
`condition` stays the fold, which the tasks compile into the kernel (the compiler flattens either
form to the same conjuncts, so the kernel is unchanged). The fallback then evaluates Spark's own
tree through the same `VarkaFallbackPredicate` and the same elimination gate, which is item 7's
"by construction".

Alternatives considered:

* **Always `Some(condition)`.** The simplest rule, but `wholeCondition` is a constructor field
  that `stringArgs` prints, so every fully fused filter's plan string would change from `None`;
  the conditional form changes only the plans whose fallback changes.
* **The node's `condition` becomes Spark's tree when nothing is residual.** Also correct for the
  fallback, but the `condition` is the kernel's input everywhere else (`VarkaFusionReport`, the
  tasks' compile, the evaluator factories), and a node whose `condition` is no longer the
  conjuncts the kernel serves is a second meaning for the field that the residual case would
  not share. Rejected.
* **Rebuild the tree inside `fallbackCondition`.** It has only the fold. Rejected.

With codegen on, nothing changes: `inCodegenOrder` flattens the tree and the fold to the same
conjunct list, and `FilterExec(...).eliminatesSubexpressions`, which 541's gate asks of
`wholeCondition.getOrElse(condition)`, splits either into the same conjuncts. Step 3's test runs
the codegen-on cells to show it.

The name and doc of `wholeCondition` ("the filter's whole condition when the rule split residual
conjuncts off it") widen to "the filter's own condition wherever the fallback needs it: a split
filter, or a condition that is not its conjuncts' fold"; `VarkaFilterExecBase`'s class doc and
`fallbackCondition`'s say the same.

No `VarkaEmitOptions` switch: the change is in the plan rule's fallback, not in emission, and the
old behaviour is the bug. This follows row 541, which changed the fallback without one.

### 3.2 What is deliberately unchanged

* The kernel and every emitted byte.
* Row 541's three checks: `fallbackHoistsIntoAnAnd`, `residualMissesElimination` and the gate.
* Row 386 (planned) edits the same method for SPARK-60315's ordering; whichever lands second
  rebases on the other (the row's own note).
* SPARK-61197 itself: Varka follows Spark's uncompiled behaviour, whichever it is.

### 3.3 Registered op counts

None.

## 4. Files

| file | what |
|---|---|
| `VarkaColumnarRule.scala` | `rewriteFilter` passes the tree where it is not the fold |
| `VarkaFilterExec.scala` | the doc of `wholeCondition` and `fallbackCondition` |
| `VarkaDifferentialSuite.scala` | the differential test |

## 5. Tests, and what each is for

1. `VarkaDifferentialSuite`, "with codegen off, a filter with no residual conjunct falls back on
   Spark's condition tree, not its conjuncts' fold": over `varka_cse_and_nn`, the query of
   section 2. It first asserts the plan's shape: one Varka filter, no `FilterExec`, and a
   condition of Spark's physical `FilterExec` (the one `rewriteFilter` receives, read from the
   plan with Varka off) that is not its fused conjuncts' left fold (else it fails, naming that
   tree, so a silent planner or optimizer change cannot pass it vacuously). Then, with
   `spark.sql.codegen.wholeStage` off, under `subexpressionElimination.enabled` true and false,
   Varka raises or answers exactly as Spark does: by the row's reading, both raise with
   elimination on and both answer `[2]` with it off. The batch holding `m = 13` is declined by
   `make_date`'s own guard, so no test hook is needed; the test also runs 541's
   `checkFourCells` so the codegen-on cells are shown unchanged (both answer).
2. The planted fault: `rewriteFilter` back to `residual.map(_ => condition)`. Test 1's
   elimination-on, codegen-off cell must fail with only Spark raising; recorded in section 11.
3. Existing: `VarkaDifferentialSuite`, `VarkaFilterExecSuite`, `VarkaFilterRaiseOrderSuite`,
   the four 541 tests among them, `VarkaFilterExecSuite`'s EXPLAIN tests, and
   `VarkaGoldenCorpusSuite`, whose verdicts are classified from the plan string and must not
   move: only filters whose condition is not its fold gain a `Some(...)` in their plan string.

## 6. The measurement

No speed claim: the fallback runs only on a batch the kernel declines, and evaluates the same
conjuncts in a different tree. Step 4 lists the plan strings that move.

### 6.1 Predictions, registered before the run

1. Before the change, test 1's elimination-on, codegen-off cell shows Spark raising and Varka
   answering `[2]`; the other cells agree. If it does not, the row closes as not reproducing,
   with the physical `FilterExec`'s condition recorded.
2. After it, all cells agree.
3. Test time: a new test in an existing sql/core suite over an already-cached fixture, about
   4 cells times 2 settings of short queries; under 4 s. The calibration table's tests of this
   shape ran under their estimates (VARKA-318's sql/core tests at 0.37, VARKA-317's at 0.03, both
   short queries over one cached view), so no ratio above 1 is applied; the bound is VARKA-541's
   four tests' measured 3.3 s, a test of about the same size, rounded up.
4. Plan strings move only for a filter not over a scan (section 2 item 5) where
   `InferFiltersFromConstraints` or a written parenthesis leaves a non-fold tree; no golden-corpus
   verdict and no EXPLAIN fusion line moves, since both read the conjuncts, which the tree and the
   fold share.

## 7. Risks

1. `semanticEquals` against the fold misjudges a tree as its fold, or the reverse: the reverse
   is harmless (the fallback reads an equal tree), and the first leaves today's behaviour; test 1
   asserts the tree is not the fold.
2. A plan reader keys on `wholeCondition.isDefined` meaning "split", or reads the tree
   differently from the fold. The readers (`git grep wholeCondition` under `src/main`):
   `fallbackCondition` (section 2); `compiledBySpark`'s `CodegenFallback` scan, which visits the
   same non-`AND` nodes in either form; `fallbackEliminatesSubexpressions`, whose
   `FilterExec(...).eliminatesSubexpressions` splits either into the same conjuncts;
   `VarkaFusionReport.predicateLines`, which flattens the conjuncts, so EXPLAIN's fusion lines
   are unchanged; the two copies in `VarkaColumnarRule` (lines 108 and 113) and the two
   evaluator factories, which carry the field or `fallbackCondition` unread. None tests
   `isDefined`, so a non-fold tree changes only the fallback's evaluation order.
3. Row 386 lands first and reshapes `rewriteFilter`: rebase; the test is independent of the
   order rule.

## 8. Sequencing

One pull request of about 120 counted lines: step 1 runs test 1 on master and records the
disagreement; step 2 is the rule change and docs; step 3 the planted fault; step 4 the existing
suites. Commands, each ending a step:

1. `build/sbt 'sql/testOnly *VarkaDifferentialSuite -- -z "falls back on Spark"'` on master
   with the test alone: fails in the predicted cell.
2. The same after the change: passes.
3. The same with the planted fault: fails in that cell only.
4. `build/sbt 'sql/testOnly *VarkaDifferentialSuite *VarkaFilterExecSuite
   *VarkaFilterRaiseOrderSuite *VarkaGoldenCorpusSuite'`: pass.

## 9. Questions for the owner

None blocking. SPARK-61197 has no upstream fix (no apache/spark pull request names it as of 20
November 2026); if one syncs first, test 1 shows both answering, and `VARKA-541.md` 0 item 7 gets
a dated note either way, as the row asks.

## 10. Done when

The fallback of a filter with no residual conjunct evaluates the filter's own condition tree;
test 1 checks first that the plan holds a Varka filter, no residual `FilterExec` and a filter
over a non-scan child whose physical condition is not its own left fold, and then with
whole-stage codegen off, under
`spark.sql.subexpressionElimination.enabled` true and false, Varka raises or answers exactly as
Spark does on that batch; `VARKA-541.md` 0 item 7 gets a dated note.

## 10a. Decisions taken while the owner was away (20 November 2026)

Built in a local-only window (the owner's order of 07:30 CET: implementation first, tests and CI
in a later phase, at most one targeted run of the new test). Each decision below is the option
the plan, `sql/varka/AGENTS.md` or the plan's own recommendations favour.

1. **The fold test is structural, not `semanticEquals`.** Section 3.1 and risk 1 say
   "semantically its fused conjuncts' left fold"; that test can never fire. `And.canonicalized`
   (`predicates.scala`, `buildCanonicalizedPlan`) gathers the whole `AND` spine, sorts its
   operands by hash and re-reduces them, so every `AND` tree is semantically equal to its own
   fold. `rewriteFilter` compares `fused != condition` (case-class equality, the conjuncts being
   the condition's own nodes), and the test asserts the same way. A false "not the fold" from a
   rewritten conjunct is the harmless direction (risk 1): the fallback then reads Spark's tree.
2. **The reproducer's vehicle is a union with `PushDownPredicates` excluded, not a `LIMIT`.**
   Section 2's `LIMIT` child cannot fuse: both of `VarkaColumnarRule`'s filter arms require
   `child.supportsColumnar && arrowFriendly(child)`, and `GlobalLimitExec` and `LocalLimitExec`
   are row nodes (`limit.scala` has no `supportsColumnar`). An aggregate or a join, the plan's
   fallback, is a row node too. The child has to be columnar, arrow-friendly, opaque to
   `PhysicalOperation` (or `pruneFilterProject` folds the condition) and not pushed through by
   the optimizer (the pushdowns read rebuild with `reduce(And)`, the one through a union among
   them). The columnar non-scan children are Varka's own nodes and `UnionExec` (columnar when
   its children are, `vectorTypes` `None`, so `arrowFriendly`), and a filter stays above either
   only when a pushdown is blocked: by a nondeterministic expression, which Varka declines, or
   by an expensive alias (a UDF or a costly regex) projected under the filter. That last path,
   a partial `VarkaProjectExec` computing the UDF row by row under a filter that reads it twice,
   was not worked through (`PushPredicateThroughNonJoin`'s split around expensive conditions
   decides what tree stays up), so a default-configuration reproducer is not ruled out. With `spark.sql.optimizer.excludedRules` naming
   `PushDownPredicates`, a public conf and an excludable rule, the filter stays above
   `UNION ALL` of the cached table with itself, and `BasicOperators` keeps the optimizer's tree.
   The test uses that shape and `SELECT *`, so no column pruning puts a row `ProjectExec`
   between the filter and the union. **This narrows the row's reach**: the shapes found by
   reading need a non-default optimizer configuration, or the unexamined expensive-alias path,
   to show. The fix stays,
   because it is the by-construction argument of `VARKA-541.md` 0 item 7 and costs a field in
   the plan string of only the shapes it changes; the owner may prefer to close the row as
   low-reach once the run confirms.
3. **The one allowed run is spent after the change**, on test 1. It carries an in-test control
   in place of the pre-change run: the same `WHERE` directly over the cached table, on Spark
   alone, with codegen off and elimination on, must answer `[2]` (Spark is handed the fold
   there), while over the union Spark must raise. That shows the tree and the fold differ in
   Spark's own uncompiled filter in the same run. Section 8's step 1 (the test on master),
   step 3 (the planted fault) and step 4 (the four suites) are owed to the test phase.

## 11. Outcome

*Partial, 20 November 2026: built, one run; the rest is owed to the test phase.*

Built: `rewriteFilter` passes `wholeCondition = Some(condition)` when there is a residual or the
condition is not structurally its fused conjuncts' fold; the docs of `wholeCondition`,
`fallbackCondition` and `VarkaFilterExecBase` say so; test 1 in `VarkaDifferentialSuite` over a
`UNION ALL` with `PushDownPredicates` excluded (decision 2), with the shape checks, the control
over the scan, the codegen-off cells under elimination on and off, and the codegen-on cells
through `checkFourCells`; `VARKA-541.md` 0 item 7 has its dated note.

Predictions (section 6.1):

1. Before the change, Spark raises and Varka answers in the elimination-on, codegen-off cell:
   **owed** (step 1 not run; decision 3).
2. After it, all cells agree: see the run below.
3. Test time under 4 s: see the run below.
4. Plan strings move only for a filter not over a scan whose tree is not its fold: **owed**
   (step 4); by decision 2's reading, no filter planned at the default configuration moves.

