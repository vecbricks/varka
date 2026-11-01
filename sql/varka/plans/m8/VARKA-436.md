# VARKA-436: `p IS TRUE` over a guarded predicate keeps the dense body

*Row 436 of `m8/PLAN.md`, found by the 21 October 2026 review of row 336.*

## 0. What exists now (admission check)

Read on 1 November 2026 from the worktree at `130280562f3` (code as of master `5c660201b35`); the
line numbers are that tree's. Master has since moved to `1bb9c97b5d0`, whose diff to the files these
rows touch (`Analysis.java`, `Slots.java`, `VarkaIrGrammar.java`, row 157's decimal store) shifts
some lines and changes no finding. Nothing was run or measured.

1. **The composition is unchanged since row 336.** `VarkaConditionCompiler.nullSafeEqual`
   compiles a predicate against a boolean literal, which is how `p IS [NOT] TRUE|FALSE` parses,
   as `And(known, IsNotNull(known))`, where `known` is `p` or `Not(p)` (`:1337-1353`). Rows 331,
   460 and 27 landed after the review. They added `BoolNullSafeEq` for two predicates and left
   this arm as it was. Row 342's `isnan(x)` uses the same composition,
   `And(IsNotNull(x = NaN), x = NaN)` (`:596`).
2. **Why the dense body is lost.** `Analysis.collectRefusals` sets `guardUnderNullTest` when any
   node with a refusal (a guard) is reached from the child of an `IsNotNull` over a condition
   (`Analysis.java:988-996`). `maskedOnly()` is then true and every batch runs the masked body
   (`:418`). The reason is in `VARKA-334.md` 9 item 2: a dense body answers such a test constant
   true without lowering the condition, so a guard under it would decline a batch in the masked
   body and not in the dense one, and a guard's verdict must not depend on which body runs.
3. **That reason does not hold for `And(p, IsNotNull(p))`.** The `And`'s left arm is `p` itself,
   outside any null test. The dense body lowers it, guards included. So every guard under the
   null test is also condemned in the dense body, and the verdict is the same in both bodies. The
   rule is coarser than its reason: it asks whether a guard is reached under a null test, when
   the reason only needs to know whether a guard is reached *only* under one.
4. **Through SQL the example carries a guard.** `year(d)` over a column carries its column guard
   (`VARKA-334.md` 9 item 3), so `(year(d) = 2021) IS TRUE` over a nullable `d` is masked-only.
   `year(d) = 2021` alone keeps its dense body. A non-nullable `p IS TRUE` reaches Varka as `p`
   (`VARKA-336.md` 3) and is not affected.
5. **No figure exists.** The row says "Not measured yet", and no committed results file holds
   either form.

### What differs from the row text

- The row offers "the composition is lowered so that a total `p IS TRUE` keeps the dense body".
  Item 3 shows that the change belongs in the analysis's rule rather than in the composition, and
  that it covers `isnan` and every `p AND p IS NOT NULL` shape as well as `IS TRUE`. No new IR
  node is needed. `VARKA-336.md` 2.3's alternative was a dedicated `IsTrue(Cond)` node, which
  this plan does not take.
- Measure first: the row's done-when allows recording the loss instead of fixing it. By the
  baseline-as-its-own-pull-request practice, the benchmark lands first, on unchanged code.

## 1. Goal and non-goal

Goal: the cost of `(year(d) = 2021) IS TRUE` against `year(d) = 2021` measured at a dense batch,
and, if it is a loss, `guardUnderNullTest` set only for a guard reached nowhere but under a null
test, so that the composed form keeps its dense body.

Non-goal: `prefixUnderNullTest` (row 334's other masked-only reason, `Analysis.java:411-418`),
which has its own case; and the two-predicate `BoolNullSafeEq` (row 331).

## 2. The design

1. **The baseline (its own pull request).** `VarkaFilterBenchmark` gains two arms over a nullable
   date column: `year(d) = 2021` and `(year(d) = 2021) IS TRUE`. Each runs at a batch with no
   nulls (the dense body) and at one null in ten (the masked body, the control: the two forms
   should be level there). The arms go through SQL, which is the point, since the column guard
   that triggers the rule exists only on that path. The runs go on the EPYC 7763 runner through
   `benchmark.yml` at both widths (256 bits native, 128 bits by `-XX:MaxVectorSize=16`), and
   the results are committed from the artifact.
2. **The rule.** `collectRefusals` computes a second set, `outside`: the nodes reached from the
   roots without entering the child of an `IsNotNull` over a condition. `guardUnderNullTest` is
   set when a refused node is in `under` and not in `outside`. For `And(p, IsNotNull(p))`, `p`'s
   guards are in `outside`, and the kernel keeps its dense body. For `Or(x, IsNotNull(p))` with
   `p` only under the test, the rule is unchanged.
3. **The slot plan.** `Slots.emittedNodes` already follows the emission past an `IsNotNull`
   over a condition (`VARKA-334.md` 9 item 3), and the shared `p` is one record. The dense body
   must plan `p`'s slots once for the left arm and not again for the test. The build checks that
   with `VarkaUnreadLocalsSuite`, which fails on a slot stored and never read.
4. Behind a switch, as the template asks: `VarkaEmitOptions.guardRuleOutsideNullTest`, default
   off and then flipped, with the old rule kept as the live reference variant. The switch is
   small enough that the owner may prefer no switch (question 2).

## 3. Tests

- `VarkaEmitterValiditySuite`: `And(GuardedRange(year(c0)) = lit, IsNotNull(same))` has a dense
  body under the switch and none without it, and both answer the reference over batches that
  are inside the guard, outside it (the batch declines in both bodies alike), with nulls and
  without. Also `Or(c1 > 0, IsNotNull(guarded p))` is masked-only under both, which is the case
  the rule keeps.
- `VarkaCoverageDifferentialSuite`'s domain test over `(year(d) = 2021 AND i > 0) IS TRUE`,
  which row 336 added. It is the end-to-end check that a batch outside the domain declines in
  the dense body too. It runs unchanged.
- `VarkaUnreadLocalsSuite` and `VarkaEmittedBytesSuite`: the second moves only for coverage rows
  that hold `IS TRUE`, `IS NOT FALSE`, `isnan` or their kind over a guard, once the default flips.

## 4. Predictions, registered before the run

- P1: at the dense batch, `(year(d) = 2021) IS TRUE` runs slower than `year(d) = 2021` on master,
  by at least 1.3X at 256 bits. The masked body carries validity words and the composed form's
  extra known-mask work.
- P2: after the rule, the two forms are within their round-to-round spread at the dense batch,
  and nothing moves at the one-in-ten batch, the control.
- P3: the added test time is under 10 s, emission and a few small batches, at the compile-only
  suites' ratio in the calibration table (VARKA-318, 0.37 to 0.46).
- If P1 fails, meaning the forms are level on master, the row closes by its second arm: the
  figure is recorded and the rule is left as it is (question 1).

## 5. Order, files and size

| File | Change |
|---|---|
| `VarkaFilterBenchmark.java`, its results files | the two arms, before and after |
| `Analysis.java` | the `outside` set, the switch |
| `VarkaEmitOptions.java`, the option table | the switch |
| `VarkaEmitterValiditySuite.scala` | section 3 |
| `emitted_bytes.json`, `coverage.json` | regenerated where the default flip moves them |

Order: the baseline pull request with its runner results; then the rule behind the switch, with
the option-matrix dispatch; the rerun on the runner; and the default flip. Size: small (about a
day and a half, most of it the two runner rounds).

## 6. Out of scope

- `prefixUnderNullTest`: a date's prefix materialized for a calendar node under a null test is a
  different reason, and it stays.
- A dedicated `IsTrue` node (`VARKA-336.md` 2.3's alternative).

## 7. Questions for the owner

1. **If the forms are level on master.** Close the row with the figure, without the rule.
   *Recommended: yes*, as the row's done-when allows. The rule is then a simplification with no
   measured gain.
2. **A switch for an analysis rule.** (a) Behind a `VarkaEmitOptions` switch with the old rule as
   reference, as the template asks for new behaviour; (b) no switch, the rule changed directly,
   with the validity suite holding both cases. *Recommended: (a).* It moves the dense/masked
   dispatch of every shape it reaches, and the option matrix then runs both rules.

## 8. Done when

Row 436's done-when: the cost measured on `(year(d) = 2021) IS TRUE` against `year(d) = 2021` at
the dense batch and committed, and either the rule changed so that the composed form keeps its
dense body (its rerun committed), or the loss recorded with its figure.

## 9. Open points for the lead

1. The same rule change reaches `isnan(x)` over a guarded operand (item 1). The coverage rows
   that move are listed by the regenerated `emitted_bytes.json` in the pull request.

## 10. Outcome

(Filled in when the row is built.)
