# VARKA-380: A fused conjunct no longer runs ahead of a residual one that can raise

## 1. Where this came from

Row 380 of `m7/PLAN.md`, from `VARKA-298.md` 9.2: when Varka splits a filter, the conjuncts it
fuses run in the Varka node below and the rest in a row `FilterExec` above, so a row a fused
conjunct rejects never reaches a residual conjunct that Spark evaluates before it. Where that
residual conjunct raises on the row - under ANSI most arithmetic and `make_date`, and year-month
interval arithmetic in every mode - Spark fails the query and Varka returns rows. VARKA-298's
census met it four times in 17,490 nested compositions and kept five shrunk reproducers,
`sql/varka/fuzz/spark/varka-380-<seed>-<iteration>.sql`, as `status: known 380`. It is the mirror
of row 273, which gave the per-row fallback the whole condition in Spark's order and assumed the
raising conjunct was the fused one. The row is in milestone 7 because it is a wrong answer (1.1
point 2); it is done when the five reproducers are regressions that agree and
`-Dvarka.sparkfuzz.nested=false` is gone from the `sparkfuzz` step of `dev/varka_nightly.sh`.

## 2. The admission check, done

The five reproducers, marked `status: regression` on this branch before any code change, fail
`VarkaSparkReproducerSuite` (16 October 2026, at `d0c686d4275`, the head of `varka-298`). The
suite stops at the first file, so a throwaway suite (not committed) replayed all five through the
same `disagreement` and also ran each query on the row engine alone with whole-stage codegen on
(the default) and off:

| reproducer | ANSI | Varka against the row engine | vanilla, codegen | vanilla, interpreted |
|---|---|---|---|---|
| 20261102-4775 | on | only the row engine throws | `INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION` | the same |
| 20261104-2199 | on | only the row engine throws | `DATETIME_FIELD_OUT_OF_BOUNDS.WITHOUT_SUGGESTION` | the same |
| 20261105-291 | on | only the row engine throws | `ARITHMETIC_OVERFLOW` | the same |
| 20261105-3145 | on | only the row engine throws | `ARITHMETIC_OVERFLOW` | the same |
| 20261106-4069 | off | only the row engine throws | `INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION` | the same |

So every one is Varka answering where Spark raises, and Spark raises the same condition in both
of its evaluation orders: on these five the oracle does not depend on which order Spark uses
(3.1). What the check would have rejected: a reproducer on which vanilla Spark answers in one
order and raises in the other would have made "what Spark does" a choice, not a fact, and the fix
a matter of picking an order.

## 3. The design

### 3.1 What Spark does, which is the oracle

Spark documents no order for a filter's conjuncts, and its code has two:

* **Interpreted, and Varka's own fallback**: `Predicate.create` over the `AND` tree, which
  evaluates the conjuncts as written and stops at the first false one.
* **Whole-stage codegen** (`FilterExec.generatePredicateCode`, `basicPhysicalOperators.scala`):
  the conjuncts that are not `IsNotNull` as written, each preceded by the `IsNotNull` tests of the
  columns it references (and of the null-intolerant expressions it contains), and the `IsNotNull`
  tests nothing referenced at the end. The source's own note above that algorithm says it may
  revisit the order and consider reordering predicates: the order is an implementation choice.

The optimizer adds to this: `InferFiltersFromConstraints` prepends inferred conjuncts, which is
how reproducer 291's `(l2 + 12) = 5000000000` comes to be first, and `NullDownPropagation`
rewrites null tests (reproducer 4069). Spark 4 runs ANSI by default, so the leading residual
conjunct is often arithmetic that can raise. The goal is the owner's: Varka raises wherever
vanilla Spark raises and returns the same rows wherever it does not, with the default session
(whole-stage codegen on) as the vanilla Spark of the differential.

### 3.2 The mechanism: demote, in the compiler

`VarkaExpressionCompiler.predicate` already loops: it classifies the conjuncts
(`predicateOnce`), and when the size admission refuses the fused set it demotes a conjunct and
classifies again. The order rule joins that loop, ahead of the size admission: after each pass,
`VarkaConjunctOrder.behindRaising` finds the fused conjuncts that Spark evaluates after a residual
conjunct that can raise, and they are demoted, with the reason "Spark evaluates it after `<that
conjunct>`, which can raise an error; fused, it would drop the rows that raise it". A demoted
conjunct that can raise is itself a raising residual on the next pass, and a size demotion can
make another, so the rule is asked again every round; the demoted set only grows, so the loop
ends. The residual `FilterExec` then evaluates the demoted conjuncts with the raising one, in
Spark's order.

The check holds a fused conjunct to both of Spark's orders: it stays fused only if it comes before
the first raising residual conjunct in the written order *and* in the codegen order. The written
order alone would keep `isnotnull(l)` fused ahead of a raising `(l2 + 12) = ...`, which codegen
evaluates first; the codegen order alone would be wrong for a session that runs interpreted.

Whether a conjunct can raise is `VarkaConjunctOrder.canRaise`, conservative: a tree is error-free
only when every node is one of attributes, literals, `IS [NOT] NULL`, `NOT`/`AND`/`OR`,
comparisons, `IN`, `IF`/`CASE`/`COALESCE`, `startswith`/`endswith`/`contains`, `+`/`-`/`*`/negation
over floating point in any mode or over integers outside ANSI, and a widening cast (number to
wider number, date to timestamp, anything to a string). Anything else counts as raising, which can
cost fusion and never an answer. The compiler had no such check to reuse: its ANSI handling is
the range analysis of the arithmetic it fuses (`intArith`, `IntRangeOps`), and a residual
conjunct is by definition one it did not compile.

It lives in the compiler, not in `VarkaColumnarRule.rewriteFilter`, because three callers classify
the same condition - the rule, the evaluator in the task, and `VarkaFusionReport` for EXPLAIN -
and must agree; EXPLAIN then shows the demotion reason per conjunct with no extra code.

### 3.3 The alternatives, and why they are not built

1. **Run the residual conjuncts first, on the batch.** A row filter cannot sit below a columnar
   node without converting the batch to rows and back; it gives up the kernel's row reduction on
   every mixed filter, not only the affected ones.
2. **Keep fusing, and use the fused mask only after the residual check** (for a row the mask
   rejects, still evaluate the residual conjuncts Spark would have evaluated first, for their
   errors). For the rows the fused prefix rejects this does nothing that demotion does not; for
   the rows it keeps, it saves evaluating the demoted conjuncts per row, at the cost of a second
   selection output (the prefix's mask beside the full one) and a per-row guard pass in both
   evaluators, which are row 318's files. It wins only on a shape with a fusible conjunct after a
   cheap raising residual and a selective fusible one after that; no filter benchmark has a
   residual conjunct at all (6), so there is nothing to measure it on. Left as an option for the
   owner (9).
3. **Fuse only the prefix before the first residual conjunct of any kind.** Correct without a
   raise check, but `s = 'x' AND d < ...` - row 273's shape and the common one - would lose its
   fused conjunct for a string comparison that cannot raise.

### 3.4 What is deliberately unchanged

* The kernel, the emitter and the evaluators: the fix changes which conjuncts fuse, not how a
  fused set runs, so no emitted byte moves and no `VarkaEmitOptions` switch is added (a switch
  that brings a wrong answer back is not an option arm).
* Row 273's fallback evaluates the whole condition as written, while codegen interleaves the
  `IsNotNull` tests. Where a *fused* conjunct raises on a row a later-written `IsNotNull` would
  reject, the two can still differ; that is the fallback's order, a narrower gap than this row's,
  recorded in 9.
* `VarkaFilterExec.scala`, `VarkaFilterEvaluator.java`, `VarkaColumnarRule.scala` (row 318) and
  `DeclineSink`, `VarkaRangeAnalysis`, `VarkaChronoCompiler` (row 379) are not touched.

### 3.5 Registered op counts

None move: no fused set that passes the rule changes, and the emitted-bytes fixture holds no split
filter with a raising residual conjunct.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/.../codegen/VarkaConjunctOrder.java` | new: `canRaise`, Spark's codegen order, `behindRaising` |
| `sql/catalyst/.../codegen/VarkaExpressionCompiler.java` | the order rule in `predicate`'s loop |
| `sql/catalyst/src/test/java/.../codegen/VarkaConjunctOrderSuite.java` | new JUnit suite |
| `sql/core/src/test/.../VarkaDifferentialSuite.scala` | the differential test of 5 |
| `sql/varka/fuzz/spark/varka-380-*.sql`, `README.txt` | the five reproducers made regressions |
| `dev/varka_nightly.sh` | the `sparkfuzz` step draws the nested trees again |

## 5. Tests, and what each is for

* `VarkaSparkReproducerSuite`: the five reproducers, now `status: regression`, agree.
* `VarkaConjunctOrderSuite` (catalyst, JUnit): `canRaise` over the list and its edges (ANSI against
  LEGACY integer arithmetic, floating point, interval arithmetic in LEGACY, a narrowing cast);
  the codegen order of a condition with `IsNotNull` tests referenced and unreferenced; and through
  `explainPredicate`, that a raising residual first demotes the fused conjuncts after it with the
  reason, that a fused conjunct written before it stays fused, that an `isnotnull` written first
  but placed after it by codegen is demoted, and that a residual which cannot raise demotes nothing.
* `VarkaDifferentialSuite`, "a residual conjunct that can raise ...": the conjunct-order case on a
  cached table under ANSI and LEGACY against the row engine - raising first, raising between two
  fused conjuncts, a fused conjunct that rejects the raising row before it, the codegen `IsNotNull`
  case, and year-month interval arithmetic under LEGACY - each with whether the plan still fuses.
* The nested-tree census of 6.

## 6. The measurement

**Correctness volume.** `VarkaSparkFuzzSuite` with the nested trees at the three seeds that met
the bug in VARKA-298 9.1 (20261102, 20261104, 20261105), 5,000 compositions each,
`-Dvarka.sparkfuzz.maxFailures` lifted, one JVM per seed on cores 12 to 23.

**Cost.** Every predicate of `VarkaFilterBenchmark` and `VarkaFilterNarrowingBenchmark` fuses
whole: none has a residual conjunct, so the rule demotes nothing and the plans and kernels are
those of the merge base. The pair on GitHub runners is given in 9 for the owner to dispatch.

### 6.1 Predictions, registered before the run

1. The five reproducers agree, each by Varka raising Spark's condition.
2. The census finds no disagreement in 15,000 compositions (VARKA-298 found three of 9.2's kind
   at these seeds).
3. The filter pair reads every row within the runner's band: the change is planning only, one
   pass over the conjuncts per classification round.
4. Existing Varka suites that assert fusion of a filter whose raising residual conjunct comes
   first now fail by design; I expect none or one, since the suites' mixed filters put a string
   comparison beside the fused conjuncts.

## 7. Risks

1. `canRaise` lists an expression that can raise: the census and the differential test would
   show it as a disagreement; each listed node is a comparison, a null test or a choice, plus
   arithmetic and casts guarded by mode and type.
2. Spark's codegen order changes upstream: the port in `VarkaConjunctOrder.codegenOrder` would
   drift silently. The rule also holds the written order, so a drift can only lose the cases
   where the two differ, which are the `IsNotNull` ones; the differential test's `IsNotNull` case
   shows it.
3. Fusion lost in real queries under ANSI: a leading BIGINT arithmetic conjunct now demotes every
   fused conjunct after it. That is the correct cost; alternative 2 of 3.3 is how to win it back.

## 8. Sequencing

1. This plan with the admission check.
2. The rule and its tests, the five reproducers made regressions, the nightly switch removed; the
   census and 9.

## 9. Outcome

Done on 16 October 2026 on branch `varka-380`, based on `varka-298` (PR #871).

**What was built.** `VarkaConjunctOrder` (3.2) and its call in `VarkaExpressionCompiler.predicate`;
the five reproducers marked `status: regression` with a note naming this row; the `sparkfuzz`
step of `dev/varka_nightly.sh` without `-Dvarka.sparkfuzz.nested=false` (put back on 17 October
2026, see the last paragraph of this section); `VarkaConjunctOrderSuite`
(twelve tests) and one test in `VarkaDifferentialSuite`; a lesson in `skills/emitter-and-ir.md`.

*Corrections to 1-8, from the review of the diff* (`/code-review high` on the branch, nine
findings, all taken, one in a smaller form; a section 9 that did not yet exist was one of them).

1. **3.4 was wrong about the fallback: it is changed.** The per-row fallback of both filter nodes
   evaluated the condition as written, so where a *fused* conjunct raises and the kernel declines
   the batch, an `isnotnull(d2)` written first dropped a row whole-stage codegen raises on.
   Checked, not assumed: `d2 IS NOT NULL AND make_date(2021, i, 1) < d` with ANSI on, over a row
   whose `d2` is null and whose `i` is 13, raised `DATETIME_FIELD_OUT_OF_BOUNDS` on Spark and
   answered on Varka before the change. It predates this row (row 273's fallback has the same
   order), but the order rule of 3.2 would otherwise hold a split to codegen's order and its own
   fallback to the other. `VarkaFilterExecBase.fallbackCondition` now folds the condition in
   codegen order (`VarkaConjunctOrder.inCodegenOrder`) when the session runs whole-stage codegen,
   and as written when it does not; the differential test has the case. This touches
   `VarkaFilterExec.scala` (row 318's file) in one helper and the two places that pass it.
2. **A fused conjunct can move a residual null test.** 3.2's rule looked only for fused
   conjuncts after a raising one. A fused conjunct that reads a column also decides, in codegen
   order, where that column's residual `IsNotNull` runs; without it the residual filter runs the
   test at its end, possibly after a raising conjunct Spark runs after the test. The rule now also
   demotes such a "puller" (`demotePullers`), and iterates both checks to their fixed point
   before the compiler classifies again, so a cascade costs one more classification, not one per
   step. Reaching it needs a residual null test beside a fused conjunct on the same column, which
   only a lane or size demotion makes; `VarkaConjunctOrderSuite` holds it at the rule's level.
3. **A date to timestamp cast can raise**: `daysToMicros` multiplies exactly and overflows past
   the year 294247, so it is off the error-free list; only numeric widening and casts to a string
   stay.
4. **The list was too short.** `DateDiff(DateAdd(d2, 9), i) < 3`, a residual conjunct of
   `VarkaExpressionCompilerSuite`'s table-rollback test, would have demoted the fused one after it
   (prediction 4 of 6.1 was not checked against that suite before the review). `date_add`,
   `date_sub` and `datediff` wrap rather than raise (`nullSafeEval` is a plain `+`/`-`), and
   `upper`, `lower`, `length`, `substring`, `isnan` and `LIKE` with a constant pattern cannot
   raise on a row, so they joined it. So did the fields of a date (`year`, `month` and the rest of
   `GetDateField`), after the first run of the suites below.
5. `basicPhysicalOperators.scala`'s note that its two copies of the `IsNotNull` interleaving
   change together now names `VarkaConjunctOrder.codegenOrder` as the third.

   The review asked for Spark to expose the order as one function all three call; that is the
   cleaner design and a change to Spark's operator for Varka's sake, so the cross-reference is
   the smaller step taken here.
6. **The composition fuzzer of the compiler knew two composition declines and met a third.**
   The first `dev/varka_matrix.sh --defaults` run failed `VarkaCoverageCompositionFuzzSuite`'s
   "random conjunctions ... never thrown" at seed 20260925, iteration 3: `dt IS NOT NULL`, which
   fuses alone, was demoted behind `year(d) = 2021`, residual there for the one-lane rule and then
   counted as raising. Two changes: the date fields joined the list (item 4), and every reason of
   the rule carries `VarkaConjunctOrder.OUT_OF_ORDER`, which the suite now accepts beside the
   budget and the lane as a decline the other conjuncts cause.
7. Each demotion round recompiled every conjunct; the rule now returns at once when no residual
   conjunct can raise, and reaches its fixed point before the next classification (item 2). A
   hand-written tree walk became `TreeNode.exists`.

*Corrections from a second review* (`/code-review high` on the whole branch after the first
round's fixes, eight findings: six taken, two answered).

1. **The fallback's order followed a flag, not Spark's plan.** With codegen on, Spark still runs a
   filter as written when the stage would read more than `spark.sql.codegen.maxFields` columns or
   holds an expression that falls back. `fallbackCondition` now asks the question
   `CollapseCodegenStages.supportCodegen` asks of the node, and the differential test runs the
   fallback case with codegen off too, where both engines answer.
2. **The port missed codegen's own null checks.** `generatePredicateCode` also checks a column
   before the first conjunct that reads it when a null test of a larger null-intolerant
   expression implies it (`isnotnull(l + l2)` implies `l`), with no conjunct of its own.
   `codegenOrder` now returns steps - a conjunct or such a `NullCheck` - and `inCodegenOrder`
   folds the checks into the fallback's condition.
3. **The puller rule demoted more than it had to, and less.** It is now a comparison of what
   reaches each raising residual conjunct: the steps before it in the full codegen order against
   every fused conjunct plus the steps before it in the residual filter's own codegen order. A
   null test or check on one side and not the other is harmless when the other side drops the
   same null rows anyway (a fused `l2 > 0` drops every row a later `isnotnull(l2)` would), and
   otherwise the fused conjuncts that pulled it into place are demoted. The first version's test
   asserted a demotion of exactly such a harmless puller; it now asserts the harmless case and a
   `coalesce` puller that does need it, and a test holds the implied check.
4. The safety net for a drifted port demoted under the `OUT_OF_ORDER` phrase, which the
   composition fuzzer accepts as a composition decline, so a drift would have passed silently;
   it has a reason of its own now.
5. `VarkaConjunctOrder` split the `AND` spine with a copy of the compiler's helper; the
   compiler's `conjuncts` is package-private now and shared, so the indices cannot drift apart.
6. The differential test drops its views.

Answered: the shared function in Spark (the altitude finding again, as in the first review), and
order demotions that stay when a later round's demotion makes the conjunct they sat behind fuse
after all. That needs a raising conjunct residual only for the one-lane rule and a later
demotion of the conjunct holding the lane; what it costs is fusion and a reason that names a
conjunct that ended up fused, never an answer, and recomputing the order demotions from scratch
each round would trade it for a loop that can oscillate.

The compiler's composition fuzzer, "random conjunctions of coverage predicates", at 3,000
compositions on each of three seeds (20260925, its default, 20261016 and 20261102): no finding,
so the drift safety net of item 4, whose reason that suite does not accept, never fired.

The suites after both rounds (`dev/varka_matrix.sh --defaults`, sanitizer on, eight JVMs): 1,375
passed and two failed. One is the `VarkaTierWitnessSuite` trap of 9.2 item 4, pre-existing. The
other, `VarkaWarmupEndToEndSuite`'s "a projection's first query takes the row path and its next
one the compiled kernel", is a projection's warm-up verdict that did not arrive in two minutes
beside seven other test JVMs (`runNanos` about 120 s, state `RELEASED`); it passed in the
second of the three matrix runs and alone (5 of 5), and touches no filter.

### 9.1 The census

`VarkaSparkFuzzSuite`'s main test at the three seeds that met the bug in `VARKA-298.md` 9.1,
5,000 compositions each, `-Dvarka.sparkfuzz.maxFailures=1000`, one JVM per seed run from the
exported test classpath on cores 12 to 23, on the final code and, as the control, on the head of
`varka-298` with the same launcher:

| seed | compositions (nested) | disagreements, this branch | disagreements, `varka-298` | compositions with a Varka node, this branch / `varka-298` |
|---|---|---|---|---|
| 20261102 | 5,000 (2,499) | 0 | 1, iteration 4775 (the reproducer) | 4,202 / 4,259 |
| 20261104 | 5,000 (2,498) | 0 | 1, iteration 2199 (the reproducer) | 4,132 / 4,218 |
| 20261105 | 5,000 (2,499) | 0 | 1, iteration 3145 (the reproducer) | 4,142 / 4,195 |

Every composition that errored on both sides raised the same condition on both, no unexpected
condition appeared, and no listed pair raced (9.3 of VARKA-298). The last column is what the rule
costs in the fuzzer's own population: 53 to 86 of each 5,000 compositions (1.1 to 1.7%) lose every
Varka node. The control's counts include its shrinker's extra comparisons (22 to 60 per seed), so
the loss is if anything a little smaller. The fuzzer draws a raising residual conjunct far more
often than a workload would - BIGINT arithmetic and `make_date` over hostile values under ANSI - so
this is an upper bound on the fusion a real filter loses, not an estimate of it. Each seed took 10
to 12 minutes. An earlier run at `54c925d67ef`, before the date fields joined the list (item 4),
also found no disagreement and lost 63 to 93 compositions a seed.

### 9.2 Predictions scored

1. **Held.** All five agree; in each Varka now raises the condition Spark raises.
2. **Held.** No disagreement in 15,000 compositions, 7,496 of them nested; the control met three,
   one per seed, each a known reproducer.
3. **Not scored**: the pair is for the owner to dispatch (9.3).
4. **Refuted.** Two existing tests would have failed under the first list: the compiler suite's
   table rollback (item 4 of the corrections above, found by the review) and the composition
   fuzzer's (item 6, found by the suites). The second `dev/varka_matrix.sh --defaults` run, with
   the sanitizer, passed 1,374 tests and failed one, `VarkaTierWitnessSuite`'s "the witness sees
   each tier": a warmed kernel trapped (`speculate_null_assert` in `VarkaMemorySanitizer.check`)
   where the test expects C2. It fails the same way on the head of `varka-298` with this branch's
   sources taken out (`--suites VarkaTierWitnessSuite`, 6 passed and that one failed), so it is
   the sanitizer's branch under this JDK, not this change; no emitted byte moved here.

### 9.3 The cost pair, for the owner to dispatch

No filter benchmark has a residual conjunct, so the rule demotes nothing in them, and the
fallback's reordering runs only on a batch the kernel declines, which none of them has. Predicted:
every row within the runner's band. Once this branch is a pull request of `vecbricks/varka`:

```
gh workflow run benchmark.yml -R vecbricks/varka -f pr=<PR> -f pair=true -f rounds=5 \
  -f class="VarkaFilterBenchmark,VarkaFilterNarrowingBenchmark,VarkaRangeFilterBenchmark"
```

### 9.4 Left for later

* **Fusion lost behind a raising residual conjunct** (9.1): alternative 2 of 3.3 - keep the
  conjuncts fused and use the mask only after the residual check - wins it back on the shape
  where it pays, at the cost of a second selection output in both evaluators. Worth building only
  with a benchmark that has the shape; for the owner to decide.
* **The error-free list is a list.** A residual conjunct off it demotes the fused ones after it.
  Widening it is safe one expression at a time, each with a reason it cannot raise.
  *Note, 1 November 2026:* `VARKA-542.md` widened it by one case: a decimal cast to an integer
  type that holds its integer digits under ANSI, or of any width outside ANSI, which cannot raise.
* **The warmed arm and the pivot test still draw `drawRows`** (`VARKA-298.md` 9, corrections from
  its review): they avoided 9.2 and 9.3 there; 9.2 is fixed and 9.3 is row 381.

**The nightly switch, put back (17 October 2026).** Row 381's census, run with the nested trees
on this row's base, found two more wrong answers of the nested trees, rows 386 and 387: an earlier
conjunct that is NULL does not stop Varka evaluating a later one that raises, and a filter kernel
raises a later row's error before the projection raises an earlier row's. A nightly drawing the
nested trees would meet one of them about two nights in three, so `dev/varka_nightly.sh` keeps
`-Dvarka.sparkfuzz.nested=false`, its comment now naming those rows. This row's done-when is met
except for that switch, and removing it, once 386 and 387 are fixed, closes the row.
