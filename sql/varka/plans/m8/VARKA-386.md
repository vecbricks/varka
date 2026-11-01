# VARKA-386: A filter past a NULL conjunct raises as Spark's default session does

## 0. What exists now (admission check)

From master at `239995f4fe4`, the local branch `varka-388`, apache/spark's master, and
apache/spark#59573, read on 1 November 2026 with `git` and `gh api` GETs. Nothing was dispatched
and no JVM was run. `VARKA-388.md` below is the plan on the unmerged branch, read with `git show
varka-388:sql/varka/plans/m7/VARKA-388.md`. It is not in master's tree (question 3).

1. **The upstream decision has come.** apache/spark#59573 (SPARK-60315) merged on 22 October
   2026: `930c571628d` on master and `ba2dec7f05d` on branch-4.x. Its last comment, from the
   merge script, gives both commits. Under the new default, whole-stage codegen evaluates the
   conjuncts after a NULL one, and only a FALSE conjunct stops it. The internal legacy conf
   `spark.sql.legacy.filterShortCircuitOnNullConjunct` (default `false`, version 4.4.0) restores
   the old codegen behaviour. The interpreted path is the same under both settings.
2. **Varka's master does not have it.** `930c571628d` is not an ancestor of `origin/master`. The
   last upstream sync was #804 (`varka-sync-apache-1014`, merged 14 October 2026). Its upstream
   base is `3d4eb2e2a52`, 320 commits behind apache/spark's master at `4fecd73323f`
   (31 October 2026). Upstream enters only through a sync pull request
   (`sql/varka/skills/working-in-this-repo.md`, "Upstream enters only through a sync pull
   request"). No sync branch or pull request is open.
3. **A dry merge of that upstream master into Varka's master** (`git merge-tree`, nothing written)
   has two content conflicts. One is `.github/workflows/update_build_status.yml` (SPARK-59293).
   The other is `sql/core/.../columnar/ArrowCachedBatchSerializer.scala` (SPARK-60707 and
   SPARK-60776, which Varka's cached-batch serializer also edits). Of the four files the upstream
   commit touches, Varka has edited only `basicPhysicalOperators.scala`, with a three-line comment
   that names `VarkaConjunctOrder.codegenOrder` as a port. Varka's `SQLConf.scala` edits are
   elsewhere in that file, and they merge clean in the dry run.
4. **The work for that semantics was built against an earlier revision of the upstream pull
   request.** The branch `varka-388` (17 October 2026, unpushed as a pull request) carries:
   - a cherry-pick of the pull request's first revision (`4c019f800c5`, 225 lines added);
   - VARKA-388's plan (`995732fd61c`);
   - the rule (`dc631b7896f`) and the review's fixes (`feb69a34d30`);
   - a merge of master (`67d561f6eed`) and the record of where it stopped (`de8af7121c9`).

   The merged revision is larger: 582 lines added, `basicPhysicalOperators.scala` changing 311
   lines rather than 83. The review rounds added two things. First, `precedingNotNullChecks`:
   once a conjunct that can be NULL is generated and the flag is on, the `IsNotNull` checks that
   precede a later conjunct in the condition are generated before it, in written order, rather
   than lazily before the first conjunct that reads their column. Second, an `IsNotNull` of an
   expression written after a conjunct is not generated before it (the SPARK-51356 nested checks
   are suppressed after a nullable conjunct). The flag is generated only when some conjunct is
   nullable after binding and a later conjunct, or a check, satisfies `mayRaise`, which is
   `!ExprUtils.canEvaluateUnconditionally`. So codegen's order now depends on the legacy conf,
   on the bound nullability, and on Spark's `mayRaise`. Varka's `codegenOrder`
   (`VarkaConjunctOrder.java:392`) reads none of the three: it ports the lazy placement only.
5. **What master's Varka side does today.** `VarkaFilterExecBase.fallbackCondition`
   (`VarkaFilterExec.scala:80-89`) folds the condition as an `AND` tree. It folds in written
   order when Spark would not compile the filter, and in `VarkaConjunctOrder.inCodegenOrder`
   otherwise. `And.eval` goes on past a NULL side, so the fallback already raises past a NULL
   conjunct, which is the new default and the row's reproducer's "only Varka throws". The
   kernel never stops early, and a fused node that can raise declines the batch to that fallback
   (`varka-388`'s `VARKA-388.md` 3.2, which reads `VarkaVectorWalk.emitGuardCollect`).
   `VarkaConjunctOrder.java` has changed on master since `varka-388`'s base only through the
   type rows' `canRaise` and `errorFreeNode` arms. `VarkaFilterExec.scala` has changed through
   #911 (row 387) and the type rows.
6. **The reproducer suite trips on the sync alone.** `VarkaSparkReproducerSuite` fails a
   reproducer marked `status: known <row>` that now agrees
   (`VarkaSparkReproducerSuite.scala:29`, `:58-61`). `varka-386-20261017-22897.sql` is the one
   such file. After the sync, Spark's default raises `INTERVAL_ARITHMETIC_OVERFLOW` there as
   Varka does (`VARKA-388.md` 2: the reproducer passed as `status: regression` with the first
   revision cherry-picked). So the sync pull request cannot pass its own Build without flipping
   that status line.
7. **The nightly.** `dev/varka_nightly.sh:141-143` still passes `-Dvarka.sparkfuzz.nested=false`,
   with the comment "until row 386 is fixed". Row 387, the other reason `m7` row 380 gave, is done
   (#911).
8. **A finding with no row on master.** `VARKA-388.md` 9 item 4 found that the fallback's
   `Predicate.create` hoists every common subexpression to the top of `eval`, where codegen
   evaluates each one before the first conjunct that reads it, so Varka raises where Spark's
   default answers. That branch recorded it as row 389. Master's `m7` row 389 is a different
   finding, the warm-up's traps, so this one is in no table. Upstream lists the codegen-off half
   of the same mechanism as SPARK-60392 in the merged commit's message.

### What differs from the row text

- The row waits "for the upstream decision". The decision came on 22 October 2026: merged, with
  the flag. The done-when's "if it merged" branch applies.
- The row says VARKA-388's change is "built on branch `varka-388`". It is built on the pull
  request's first revision. The merged revision changes the `IsNotNull` placement that Varka's
  `codegenOrder` ports (item 4), so the branch's rule is a base to rebuild from, not a change to
  merge.
- The branch carries the upstream change as a cherry-pick. Upstream enters only through a sync
  (item 2), so the cherry-pick is dropped, and the row starts after a sync of apache/spark's
  master that holds `930c571628d`.
- Under the new default, Varka already gives the reproducer's answer (item 5). The work left is
  three things: the legacy setting in the fallback; case b of `VARKA-388.md` 2, a nullable fused
  conjunct ahead of a raising residual one, which the new default makes a wrong answer the day
  the sync lands; and the flagged placement of item 4.
- Size: medium, not small to medium. The row carries a port of the merged placement rule and
  sits behind an upstream sync.

## 1. Goal and non-goal

**Goal.** A Varka filter raises, or answers, as Spark's filter does after SPARK-60315 in each of
four sessions: whole-stage codegen on or off, crossed with the legacy conf on or off. The 386
reproducer is a regression that agrees, and the nightly draws nested trees again.

**Non-goal.** No change to the kernel, the emitter or the evaluators: no emitted byte moves
(`VARKA-388.md` 3.2 holds on master, item 5). No fix for the upstream disagreements the merged
commit lists as left open (SPARK-60329, SPARK-60377, SPARK-60392, SPARK-60393). Varka follows
the default session's answer in those, as it does today. No change to aggregate `FILTER` clauses
or join conditions, which Varka does not fuse (`VARKA-388.md` 3.6).

## 2. Design

### 2.1 The sync first

A `varka-sync-apache-<MMDD>` pull request merges apache/spark's master, which holds
`930c571628d`, into Varka's master. It resolves the two conflicts of item 3 and updates the
three-line comment in `basicPhysicalOperators.scala` to name the new helpers. It flips the 386
reproducer to `status: regression` and edits `sql/varka/fuzz/spark/README.txt` to match, since
its own Build fails otherwise (item 6). That flip is the only Varka edit it carries. This row's
pull request branches from the sync's head and opens the same day (question 1).

### 2.2 `codegenOrder` follows the merged placement

`codegenOrder(conjuncts)` becomes `codegenOrder(conjuncts, childOutput, legacy)`, a port of the
merged `generatePredicateCode`:

- `splitNotNullPreds`' count of the checks that precede each other conjunct;
- the flag: off under `legacy`, and otherwise on when some other conjunct, bound over
  `childOutput` with the checked columns non-nullable, is nullable, and a later conjunct or a
  check `mayRaise`;
- `precedingNotNullChecks` once a nullable conjunct is placed, and the synthetic column check
  only where Spark's `idx == -1 || !afterNullable` allows it;
- the nested checks suppressed after a nullable conjunct;
- the leftover checks last.

`mayRaise` calls `ExprUtils.canEvaluateUnconditionally`, Spark's own predicate, and not Varka's
`canRaise` allowlist. The order has to be Spark's even where `canRaise` is more conservative,
and a demotion decision may stay conservative while the order may not. The binding is the one
`feb69a34d30` already does for nullability (`VARKA-388.md` 9, correction 1).

### 2.3 The fallback reads the conf; the planner holds a fused conjunct to every order

`inCodegenOrder` takes the legacy flag, as `varka-388` built it. `fallbackCondition` passes
`conf.legacyFilterShortCircuitOnNullConjunct` when the filter is compiled. Under `legacy`, each
step of the fold other than a null test is wrapped `coalesce(step, false)`, so the fold stops at
NULL as old codegen did.

`behindRaising` keeps its three checks: row 380's two, and `varka-388`'s `demoteNullableAhead`
for case b. It runs them against three orders: the written order, the codegen order with the
flag as the default computes it, and the legacy codegen order. A fused conjunct stays fused only
when no order would let Spark reach a raising residual conjunct that Varka's split hides. This
keeps 388's property that the planner reads no conf, so EXPLAIN and the task's evaluator
classify a condition the same way under any session. The cost is that a conjunct is demoted if
any order needs it (question 2).

### 2.4 The nightly

`-Dvarka.sparkfuzz.nested=false` and its comment leave `dev/varka_nightly.sh`, which closes
`m7` row 380's last step too.

### 2.5 Rebuilt from `varka-388`

The pull request starts from the sync's head and takes `dc631b7896f` and `feb69a34d30` by
cherry-pick, conflicts resolved against master's type rows. It does not take `4c019f800c5` or
the branch's two merges. `VARKA-388.md` becomes this row's record (question 3).

## 3. Tests and proofs

1. `VarkaConjunctOrderSuite`: `codegenOrder` against the order the merged
   `generatePredicateCode` emits, for the merged commit's own cases in
   `WholeStageCodegenSuite`. That covers a nullable conjunct with an `IsNotNull` written before a
   later raising conjunct, the nested check written after its conjunct, the flag off when nothing
   after the nullable conjunct may raise, and the legacy order. The cases come from the
   upstream suite's conditions, so a drift in Spark's placement fails here.
2. `VarkaConjunctOrderSuite`: `demoteNullableAhead` against each of the three orders, plus a
   case each order demotes alone. This shows 2.3's union is needed rather than assumed (**[I]**:
   the plan did not find such a case. If none exists, the union costs nothing and the test says
   so).
3. `VarkaDifferentialSuite`: `varka-388`'s "a NULL conjunct does not stop a filter before a later
   one that raises" in the four sessions, with one case added for item 4: an `IsNotNull` written
   between the nullable conjunct and a raising one that does not read its column.
4. `VarkaSparkReproducerSuite`: the 386 reproducer as a regression (from the sync).
5. Upstream's `WholeStageCodegenSuite` tests come with the sync and run Spark alone.

## 4. Predictions and costs, registered before the build

- P1: once the sync lands, the 386 reproducer agrees with no Varka change, and case b disagrees
  ("only Spark raises") until this row's pull request lands (`VARKA-388.md` 2, case b, three of
  four sessions).
- P2: the flagged placement changes at least one `codegenOrder` result in the fuzzer's nested
  population (**[I]**: an inference from item 4. The merged commit says a row with no NULL
  conjunct "can then change too, in both directions").
- P3: the census at `VARKA-388.md` 9.1's seeds, 5,000 compositions each, finds no disagreement.
  The compositions with a Varka node fall by at most 1% against the sync's head without this
  row (`VARKA-388.md` 6.1 item 3's bound). It runs in `dev/varka_nightly.sh`'s `sparkfuzz` step,
  since no workflow runs `VarkaSparkFuzzSuite` (question 5).
- P4: added test time is under 40 s, about 388's differential test plus one case at the table's
  sql/core ratios of 0.37 to 0.75 (VARKA-318, VARKA-336) over 388's measured times.
- P5: no emitted byte moves (`emitted_bytes.json` unchanged).

## 5. Order, files and size

| File | Change |
|---|---|
| sync pull request | upstream merged, two conflicts, the comment, the reproducer's status |
| `.../codegen/VarkaConjunctOrder.java` | `codegenOrder` with the flag, three orders |
| `sql/core/.../execution/VarkaFilterExec.scala` | `fallbackCondition` passes the legacy conf |
| `VarkaConjunctOrderSuite.java`, `VarkaDifferentialSuite.scala` | 3.1 to 3.3 |
| `dev/varka_nightly.sh` | the nested-tree switch gone |
| `sql/varka/plans/m8/VARKA-386.md`, `m7/VARKA-388.md` | the record |
| `sql/varka/skills/` (the filter-order lesson) | the merged rule and the three orders |

Order:

1. the sync;
2. this row's pull request on top of it, with its PR Build and an option-matrix dispatch;
3. the nightly census after the merge, as P3.

Size: medium.

## 6. Out of this row

- SPARK-60329, SPARK-60377, SPARK-60392 and SPARK-60393, where codegen on and off still disagree
  upstream. Varka follows the default session there.
- The fallback's common-subexpression hoist (item 8), which needs a row of its own (section 9).
- Reading the legacy conf in the compiler (`VARKA-388.md` 3.5, alternative 2).

## 7. Questions for the owner

1. **Who opens the sync, and does case b wait.** Between the sync's merge and this row's, master
   gives a wrong answer for case b under the default session (P1). Options:
   - (a) the sync merges alone, and this row's pull request follows the same day;
   - (b) one pull request holds the sync's merge commit and this row's commits, merged as a merge
     commit.

   Recommended: (a). A sync is reviewed as upstream's change and a Varka pull request as Varka's,
   and the window is hours.
2. **The planner's three orders.** Holding a fused conjunct to every order (2.3) demotes a
   conjunct that only one session's order needs. Recommended: keep it, as row 380 and
   `varka-388` did, so EXPLAIN does not depend on a session conf. The fusion cost is measured
   by P3.
3. **VARKA-388's record.** Recommended: commit `VARKA-388.md` to `m7/` as the record of the
   first build, with a note that it was built on the first revision and superseded by this
   plan. The branch is then left unmerged.
4. **Which upstream line.** Recommended: master's sync, the usual target. Branch-4.x has the
   commit too, but Varka does not follow it.
5. **Where the nested-tree census runs.** No workflow runs `VarkaSparkFuzzSuite`. The
   `sparkfuzz` step and its `nested=false` switch live in the laptop's `dev/varka_nightly.sh`,
   so the done-when's nightly is a laptop run. That sits awkwardly with the rule that CI
   verifies a pull request. Options: (a) the PR is verified by its Build and an option-matrix
   dispatch, and the first laptop nightly after the merge is the done-when's check; (b) a
   `sparkfuzz` slice with nested trees joins `varka-fuzz.yml`. Recommended: (a). The nightly
   already owns the step, and (b) is a workflow change of its own, worth a row if the owner
   wants the fuzzer on CI.

## 8. Done when

- a sync holding `930c571628d` is on Varka's master, with the 386 reproducer a regression that
  agrees;
- Varka agrees with Spark in the four sessions on the differential test of 3.3, the legacy
  sessions included;
- `-Dvarka.sparkfuzz.nested=false` is gone from the nightly, and one nightly with nested trees
  passes its `sparkfuzz` step.

## 9. Open points for the lead

1. Item 8's finding, the fallback's common-subexpression hoist, is a wrong answer recorded only
   on an unmerged branch. It wants a row of its own in this milestone's table. It is a wrong
   answer under the default session, so it is milestone work by 1.1 point 2.
2. Row 380's cell in `m7/PLAN.md` says "Done, apart from the nightly switch, which moves to
   milestone 8 with row 386". This row's merge closes that switch, and the `m7` cell wants a
   pointer.
3. Spark's `mayRaise` and Varka's `canRaise` now answer the same question two ways. Should
   `canRaise` defer to `canEvaluateUnconditionally` where that predicate is stricter? Worth a
   look, not this row's.

## 10. Outcome
