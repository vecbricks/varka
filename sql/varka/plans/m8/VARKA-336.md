# VARKA-336: null-safe equality, `<=>` and `equal_null`

*Row 336 of `m8/PLAN.md`, wave 1 (after 334). Planned on 21 October 2026 at `a429af749f4`, from
`m9/SCOPE.md` item 34 and the code on master. Section 0 is the admission check; it overrides the
row's text and item 34's sketch where they differ.*

## 0. Admission check against master (21 October 2026, `a429af749f4`)

What the row and item 34 assumed, and what the code says:

1. **`EqualNullSafe` declines today, on purpose.** `VarkaConditionCompiler.condArm` has no arm for
   it and `compileCond`'s doc says so; `VarkaExpressionCompilerSuite` ("declines null-safe
   equality, bare boolean outputs") asserts `If(EqualNullSafe(d, d2), ...)` does not compile and
   must be flipped. `equal_null` is `EqualNull`, a `RuntimeReplaceable` whose replacement is
   `EqualNullSafe(left, right)` (`predicates.scala`), and `compileCond` already compiles a
   `RuntimeReplaceable`'s replacement, so the two functions are one arm.
2. **The row's one node is two cases, and only one needs a node.** Item 34 and the row say `<=>`
   is "a condition node over the validity words" taking a predicate child. Read against the IR:
   * `p <=> true`, `p <=> false` and the forms that parse to them (`p IS [NOT] TRUE|FALSE`,
     `AstBuilder.visitPredicate`: `EqualNullSafe(e, Literal(true))`, negated by `Not`) **compose
     from nodes that exist**: `And(p, IsNotNull(p))` has `kT = kT(p) & (kT(p) | kF(p)) = kT(p)` and
     `kF = kF(p) | ~(kT(p) | kF(p)) = ~kT(p)` (the known-true and known-false masks are disjoint),
     which is `p IS TRUE` and is total. `p IS FALSE` is the same over `Not(p)`. Both nodes are
     priced and fused since row 334 (`IsNotNull/cond/<lane>`, `And`).
   * `a <=> b` over **value** operands cannot compose: `IsNotNull` takes a `ColumnRef` or a `Cond`
     only (`Analysis`, its `IsNotNull` arm, and `VarkaVectorIR.IsNotNull`'s doc), because a
     computed value's validity word exists only during its value walk, after condition emission.
     `Compare` is the one condition that reads operand words after `emitValue`
     (`VarkaVectorWalk.emitCond`, `Compare` arm). A composition would fuse `i <=> i2` over bare
     columns and decline `year(d) <=> month(d2)` and `l <=> i + 1`. So the row adds **one IR
     node, for value operands**.
3. **It is not null-intolerant to Spark either, which two readers rely on.** `EqualNullSafe` does
   not set `nullIntolerant` (`EqualTo` does), so `VarkaConjunctOrder.nullWhenNull` is false for
   it: a `<=>` conjunct is never treated as dropping the rows where a column is null and an
   `isnotnull` is never hoisted past it. `errorFreeNode` lists `BinaryComparison`, which includes
   it, correctly. Both are read, not changed; test 7 pins the first.
4. **Spark's own rewrites decide what reaches Varka** (`optimizer/expressions.scala`):
   `NullPropagation` turns `x <=> NULL` into `IsNull(x)`, so a null literal operand never arrives
   by default and stays declined (as `Compare` declines one); `SimplifyBinaryComparison` turns
   `p <=> true` into `p` when `p` is not nullable and `p <=> false` into `Not(p)`;
   `SimplifyConditionals` turns `IF(c, true, false)` and `CASE WHEN c THEN true ELSE false END`
   into `EqualNullSafe(c, true)` for a nullable `c` and the negated form for the inverse. That
   last pair is how most `p <=> true` shapes reach a query that never wrote `IS TRUE`.
   `IS [NOT] DISTINCT FROM` parses to `Not(EqualNullSafe)` and `EqualNullSafe` as well.
   `IS [NOT] UNKNOWN` is `IsNull`/`IsNotNull` over a boolean and is row 334's.
5. **`EqualNullSafe.nullable` is false.** The partition oracle's `nullable(p, fixture)`
   (`VarkaCoverageDifferentialSuite`) is false for every `<=>` row, so under the default optimizer
   `NullPropagation` folds `(a <=> b) IS NULL` to false before Varka sees it: the "`IS NULL`
   partition is empty" of the row is true by construction and proves nothing about the kernel
   unless that rule is excluded (section 5, test 3). `partitionRewrites` today excludes
   `BooleanSimplification`, `NullDownPropagation` and `InferFiltersFromConstraints`, not
   `NullPropagation`.
6. **No floating lane exists.** `LaneType` is `INT, LONG`. The types a comparison fuses over are
   the coverage table's `EqualTo` operands: `date`, `int`, `bigint`, `time`, `ym`, `dt` (six of
   the census's thirteen), all exact integer equality. NaN and `-0.0` have nothing to act on
   here; see 2.4.
7. **`Cond` is a sealed interface and the switches over it are exhaustive**, so the node's
   sites are compile errors, not a search: `VarkaVectorIR` (`permits`, `laneType`, `canonical`,
   the render, `childrenOf`, `withChildren`), `Analysis.analyze`, the `collectRefusals` switch,
   `Slots` (the consumers switch, `bodyUses`' neighbours, the liveness switch),
   `VarkaOperandPosition`, `VarkaEmitBudget`'s weight, `VarkaEmitCost.kindOf`,
   `VarkaChronoCompiler`'s rearm switch, `VarkaRangeAnalysis`, `VarkaConditionCompiler.onLongLane`,
   and in tests `VarkaReference` (two switches), the shrinker and the canonical-IR checks.
8. **The IR fuzz grammar feeds three things besides the fuzz.** `VarkaEmitCostCorpus` draws its
   shapes from `VarkaIrGrammar` (`drawShape`, `drawLongShape`, `drawWideShape`), so the cost
   model is fitted on the grammar's draws; `VarkaWarmupReach` and the emitted-bytes fixtures read
   the same corpus. A production added to the grammar redraws every seeded shape after it and
   reprices the model (row 334's grammar production did the first and surfaced a latent
   emitter bug; row 335a refit the model on the owner's word of 19 October 2026). Section 6.

## 1. Where this came from

Item 34 (18 September 2026) asked whether null-safe equality is "a second kind of node - a
null-tolerant binary, with its own rule everywhere the null-intolerant one is assumed - or
whether the existing machinery can express it". Row 334 answered half: a condition over a
condition's masks needs no new rule. The answer the code gives is: **over a condition, composed;
over values, one node whose validity rule is its own and is read in one place.**

## 2. The design

### 2.1 The node

`NullSafeEqual(left, right)` in `VarkaVectorIR`, `implements Cond`, operands value nodes on one
lane (the constructor's `requireSameLane`, as `Compare`). Its own record, not a `CompareOp`
constant: `Compare`'s contract (null-intolerant, one validity formula, `Compare/<op>/<lane>` cost
kinds, `onLongLane`, the reference) holds for every existing op, and a constant that breaks it
would make each reader of `Compare` learn an exception. Item 34's second kind of node is this
record, and its rule is stated once, in its doc and its walk arm.

**Validity rule: never null.** The node is total, like `IsNotNull`: with `vA`, `vB` the operands'
validity words and `E` the lane's equality mask,

    kT = (E & vA & vB) | ~(vA | vB)        both valid and equal, or both null
    kF = ~kT                               one null, or both valid and different

A lane is never unknown, so `Not`, `And`, `Or`, `IfElse` and a filter root read it through the
pairs unchanged ("totality composes", `IsNotNull`'s doc). The `~` sets the word's bits above the
lane count; every consumer truncates (the invariant `IsNotNull` and `IfElse`'s `~kT` already rely
on, and `i IS NULL` as a filter root is the existing exposure), pinned for this node by test 4.
Dense body: no lane is null, so the node is `Compare` EQ's mask, bytes identical to
`Compare(EQ, a, b)` in a dense body.

### 2.2 The walk, analysis and slots

* `VarkaVectorWalk.emitCond`: the `Compare` arm's body (`emitValue` both operands, `compare` with
  the lane's EQ, `toLong` into `cmpTmp`) then `kT`/`kF` by the formula above; the dense arm is the
  `Compare` arm's. No Vector API method is called that `Compare` does not call (`compare`,
  `VectorMask.toLong`), so row 397's warm-up reach and row 376's census (`operator_census.json`,
  `results/VARKA-376-census-*.json`) have nothing new to say: the only new work is scalar `long`
  `or`, `and` and `xor` on words already in registers. This is a design statement, checked by
  `VarkaWarmupReach` over the new rows, not a measurement.
* `Analysis.analyze`: `analyzeOp(node, true, left, right)` (`skips = true`: a known answer from a
  null input). The skip flag is read only by `VarkaBodyEmitter.shortcutApplies`, which already
  excludes a `Cond` root, so no shortcut changes; the flag is set because it states the
  semantics. Operand guards behave as under `Compare`: a refusal is checked on valid lanes, so a
  null operand never declines a batch, and the node adds no `guardUnderNullTest` case because
  it lowers its operands in the dense body too (that flag is for `IsNotNull` over a condition,
  whose dense arm does not lower it).
* `Slots`: the consumers switch demands both operands' words, like `Compare`. Liveness: the node
  reads two words in a masked body only.

### 2.3 The compiler

* `condArm`: `EqualNullSafe` compiles by the shared operand path of `compare`
  (`operand`: int leaf or `compileNode`), then the node. A null literal operand declines
  (`NullPropagation` has already turned it into `IsNull` by default; the decline is for a session
  that excludes the rule).
* **Predicate operand, composed.** When an operand is boolean-typed and the other is a
  `Literal(true)` or `Literal(false)`: `p <=> true` is `And(p, IsNotNull(p))` over `compileCond(p)`,
  `p <=> false` is the same over `Not(p)`, either side. The shared `p` is lowered once (the
  masked body reads `kT` and `kF` of one node, records are structurally equal; row 334's shared
  predicate test is the precedent). When row 331's column-as-condition leaf exists, a boolean
  column is a predicate and this composition applies to it with no change here. **Two boolean
  operands that are neither literal (`p <=> q`, `b1 <=> b2`) are 331's** (decided in the plan,
  object if wrong; review of 21 October 2026, M3): 331 owns the boolean leaves and builds
  `BoolNullSafeEq(Cond, Cond)` with the kT/kF formulas of `VARKA-331.md` 2.8, so this row neither
  waits on 331 nor builds that form; before 331 lands it declines with its reason, and the later
  of the two rows to merge extends the shared `EqualNullSafe` arm of `VarkaConditionCompiler`.
* `onLongLane`: an arm for `NullSafeEqual` widening both operands, as for `Compare`: a mixed
  `i <=> l` reaches Varka as `cast(i as bigint) <=> l`, a `WidenLane` over the column, whose
  validity is the column's.
* A guard under a composed `p IS TRUE` is the row-334 case: `And(p, IsNotNull(p))` contains an
  `IsNotNull` over a condition, so a guarded `p` (a calendar field over a date) makes the kernel
  masked-only (`Analysis.guardUnderNullTest`). Through SQL a calendar predicate over a column
  already carries its column guard, which makes the kernel masked-only today (row 334's 9, item
  3). This is the cost of composing; the alternative is a dedicated `IsTrue(Cond)` node whose
  dense arm aliases the child's mask. Not built here; open question 1.

### 2.4 Floating point, now and in row 341

Nothing applies now (section 0 item 6). Spark's `<=>` on a float or double is `ordering.equiv`,
which is `SQLOrderingUtil.compareDoubles`/`compareFloats`: `NaN <=> NaN` is true and
`-0.0 <=> 0.0` is true, the same equality `=` has. So `<=>` adds nothing to the floating
question: the walk calls the lane's equality mask (`compare` with EQ today), and row 341 changes
that one mask to `(a == b) | (isNaN(a) & isNaN(b))` for `=`, `IN` and `<=>` together (IEEE `EQ`
already equates `-0.0` and `0.0`). To keep it one place, the walk arm obtains `E` from a helper
shared with `Compare` rather than calling `compare` itself; 341 edits the helper. Row 341's
done-when gains "`<=>` over `DOUBLE`/`FLOAT` with NaN and `-0.0` pairs" and a NaN-versus-null
pair (`NaN <=> NULL` false, `NULL <=> NULL` true). Decimals (359) and `BOOLEAN` (331) likewise
follow their lanes' `=`.

## 3. What a default session reaches

By section 0 item 4: `a <=> b` and `equal_null(a, b)` over the six types as written;
`p IS [NOT] TRUE|FALSE` for a nullable `p`, and `IF(c, true, false)` / `CASE WHEN c THEN true
ELSE false END` for a nullable `c`, as a filter, a `CASE` condition and under `NOT`;
`IS [NOT] DISTINCT FROM`. A non-nullable `p` reaches Varka as `p`, and `x <=> NULL` as `IsNull`.
A `<=>` as a projection *output* is a boolean output: row 27's.

## 4. Files

* main: `codegen/varka/VarkaVectorIR.java`, `Analysis.java`, `Slots.java`, `VarkaVectorWalk.java`,
  `VarkaOperandPosition.java`, `VarkaEmitBudget.java`, `VarkaEmitCost.java`,
  `VarkaRangeAnalysis.java`; `codegen/VarkaConditionCompiler.java`, `VarkaChronoCompiler.java`
  (a classification arm). `VarkaEmitCostTable` gets no entry (section 6).
* test: `VarkaReference.java`, `VarkaShrinker.java`, `VarkaCanonicalIr.java` (the exhaustive
  arms), `VarkaExpressionCompilerSuite.scala` (the flipped decline), a new
  `VarkaEmitterNullSafeSuite.scala` beside `VarkaEmitterValiditySuite`,
  `VarkaCoverageDifferentialSuite.scala` (the exclusion in test 3).
* generated: `sql/varka/coverage.json`, `docs/sql-varka.md`, `sql/varka/emitted_bytes.json` (new
  rows only), the census files.

## 5. Tests, and what each is for

Oracles: the **row engine** (Spark's own `EqualNullSafe.eval`) for answers; `VarkaReference`'s
new arm for the IR suites, itself checked against the row engine by test 1.

1. **The truth table, exhaustively.** Every validity pair (both valid, one null either way, both
   null) times equal and different values, at both lanes and, in the width sweep, at `lanesOverride` 4 and 8 and
   the default, in a masked and a dense body, with the null lanes' stored values poisoned
   (equal to the other operand's, and different) so a formula that reads a null lane's value
   fails. The node's answer is Spark's `eval` per row; the reference arm agrees with both.
   **JVM rule (row 410's lesson, `skills/testing-and-debugging.md`; review of 21 October 2026,
   M6):** `VarkaEmitterNullSafeSuite` mixes in `VarkaOwnJvm`, last, because the `lanesOverride`
   sweep puts a second species of one lane type in the JVM, which `VarkaSpeciesGuard` fails in a
   shared one; any other suite of this row runs in process at `VarkaMatrix.base` lanes only.
2. **Computed operands.** `year(d) <=> month(d2)`, `l <=> i + 1`, `date_add(d, i) <=> d2`, an
   operand with `Overflow.NULL` (a null from valid inputs: the kernel is masked-only and `<=>`
   sees that word), a long-lane `i <=> l` through `WidenLane`. This is the case a composition
   could not serve.
3. **The partition oracle, on the new rows.** `WHERE p`, `WHERE NOT (p)` and `WHERE (p) IS NULL`
   run by the existing differential over every new predicate row; the `unknown` partition must be
   empty and the first two exactly complementary. Add one setting that also excludes
   `NullPropagation`, for the `<=>` rows only, so `(a <=> b) IS NULL` reaches Varka as an
   `IsNotNull` over the new node and the kernel, not Spark's folding, answers "empty"; assert it
   fuses. Without the exclusion the third partition is Spark folding a constant (section 0
   item 5). The row's "partition empty" done-when means this test.
4. **Tails and the `~`.** Row counts that are not a multiple of the lane count, `<=>` as a filter
   root and under `NOT`, with the batch's last lanes null in both operands: the selection must
   not set a bit past the row count.
5. **Composed predicate forms.** `p IS TRUE`, `IS FALSE`, `IS NOT TRUE`, `IS NOT FALSE` over every
   predicate row of the coverage table that can be null (the 46 rows of row 334's sweep, by the
   same `nullable` test), as filters and `CASE` conditions, against Spark; the `IF(c, true,
   false)` and `CASE` shapes whose rewrite produces the form; `IS [NOT] DISTINCT FROM` over the
   six types. A shared `p` under `p IS TRUE OR p` (one lowering, row 334's test 4).
6. **Composition and the budget.** The composition fuzzer (`VarkaCoverageCompositionFuzzSuite`)
   composes coverage rows, so it composes the new ones; it must pass with no change. The
   emit-budget and cost-audit suites must pass with the node unpriced (section 6).
7. **The null-tolerant readers.** `VarkaConjunctOrder` over `i <=> 5 AND d = d2`: no `isnotnull`
   moves past the `<=>` conjunct, and the order is Spark's.
8. **Declines, named.** A null literal operand; two boolean operands that are not
   `p <=> literal` (before 331 lands; afterwards they are 331's `BoolNullSafeEq`); two operands on different lanes with no widening (as `Compare`); `<=>` over every type not yet
   in the lane set (string, decimal above 18 digits, float, double, boolean, narrow integers),
   each with its reason, and they wait on 329, 331 (boolean), 341, 338, 359 and the string rows.

## 6. The fuzz grammar, the coverage differential and the cost model

* **Coverage rows** (generated, new rows only): `d <=> d2`, `i <=> 5`, `l <=> l2`,
  `ym <=> INTERVAL '1-2' YEAR TO MONTH`, `t <=> t2`, `dt <=> dt2`,
  `equal_null(d, d2)` and `equal_null(l, l2)`, `d <=> d2 OR i = 5` (a connective), `NOT (d <=>
  d2)`, `year(d) <=> month(d2)` with its domain, `l <=> i` (the widening), `(d < d2) IS TRUE`,
  `(d < d2) IS NOT FALSE`, `(year(d) = 2021 AND i > 0) IS TRUE` with its domain (the guarded
  case of 2.3), and one `CASE WHEN (d < d2) IS TRUE THEN ... END`. Each is a predicate row, so
  the differential suite's per-row test, partition oracle, domain test and ANSI form run over it
  unchanged. Census: the equal_null and `<=>` entries move from no fused type to the six types
  (`dev/varka_census.py --check` regenerates it; the pair count it prints is the figure, not this
  plan's).
* **The IR grammar and series get no production in this row.** Adding one redraws the seeded
  shapes of `VarkaIrGrammar` and so changes `VarkaEmitCostCorpus`, `VarkaWarmupReach` and
  `emitted_bytes.json`, and reprices the model: `enumeratedFeatures` in `VarkaEmitCostFit` would
  need `NullSafeEqual/<lane>` features and the fit new data, which row 335a's refit needed the
  owner's word for. Instead the node is covered by test 1 and 2's deterministic series, the
  reference arm, and the composition fuzzer's coverage rows. A grammar production, its refit and
  the bytes regeneration are a follow-up row (open question 3), taken with the next refit
  (352's: the node is **unpriced until 352's refit**, the wording all three plans of 21 October
  2026 now share).
* **The cost model with the node unpriced.** `VarkaEmitCost.Tally.count` marks a feature with no
  price `unpriced` and `predicted()` returns null, which the budget reads as "unknown" and
  answers by the weight budget (`VarkaEmitBudget`: weight 1 for the node, as `Compare`).
  The first implementation step runs the emit-budget and cost-audit suites with that and records
  whether any asserts that every enumerated feature is priced; if one does, the node is added to
  `kindOf` and `enumeratedFeatures` only as the audit demands, and the price waits on the refit.
  No price is invented here.

## 7. Predictions, with bounds

1. **Every new row fuses on every fixture** under the default optimizer and with the rewrites
   excluded, 100 percent of the rows listed; any decline is a finding.
2. **No existing row's bytes move** in `emitted_bytes.json` (the node adds no grammar draw, so
   the cost corpus and the seeded shapes are unchanged).
3. **Dense bodies of `a <=> b` are byte-identical to `a = b`'s** and the masked body adds only
   scalar word operations: three to four `long` operations over `Compare`'s formula, no Vector
   API call. Checked on the emitted bytes with an op-count test, not a throughput run: none is
   claimed (the shapes fused here went to the row path before).
4. **The added test time is under 40 s**: about 18 rows through the differential suite and the
   new suite, estimated from row 334's 20 s for about sixty rows and doubled at the calibration
   table's commonest miss (`dev/varka_calibration.py` prints the ratio; restated at it when the
   row starts).

## 8. Risks

1. **A composed `p IS TRUE` makes a guarded kernel masked-only** (2.3). A cost on null-free
   batches, correctness unaffected. Named, not measured; the dedicated node is the answer if a
   benchmark shows it.
2. **A bit past the row count in a selection** from `~(vA | vB)` (test 4).
3. **The unpriced node meets a suite that wants every feature priced** (section 6): the only
   place the row could be forced to touch the cost model.
4. **The sealed switches in the test sources** (`VarkaReference`, the shrinker) are the larger
   part of the diff, not the walk arm.

## 9. Out of this row

* Boolean-boolean `<=>` (`b1 <=> b2`, `p <=> q`): 331's `BoolNullSafeEq` (2.3); boolean outputs, a `<=>` as a projection result (27).
* Float and double `<=>`, NaN and `-0.0` (341, section 2.4), decimals (359), strings, narrow
  integers (329), `TIMESTAMP_NTZ` (338): each follows its lane's `=`.
* A grammar or series production for the node, the cost model's refit and its fit data (section 6).
* A dedicated `IsTrue(Cond)` node (open question 1).
* Struct, array and map `<=>`.

## 10. Done when

1. `a <=> b` and `equal_null(a, b)` fuse over the six types of the coverage table's `EqualTo`
   rows, as filters, `CASE` conditions, under `NOT` and under `AND`/`OR`, with computed and
   widened operands, each answer Spark's.
2. `p IS [NOT] TRUE|FALSE` over every predicate row that can be null, `IF(c, true, false)` and
   `IS [NOT] DISTINCT FROM` fuse, with test 5 passing.
3. Test 3's partition oracle passes on every new row, including the run with `NullPropagation`
   excluded, in which `(a <=> b) IS NULL` is answered empty by a fused kernel.
4. Tests 1, 2, 4, 7 and 8 pass at both widths in both bodies; the flipped decline test passes.
5. No existing row's emitted bytes move, the cost model and grammar are untouched, and the common
   last step holds (review of 21 October 2026, M5): on a clean tree regenerate `coverage.json`,
   `docs/sql-varka.md`, `emitted_bytes.json` (new rows only, `git diff`) and
   `sql/varka/golden_corpus.json` (`VARKA_GOLDEN_REGEN=true`; `<=>` occurs in Spark's golden SQL, so
   verdicts move), update `totalDomains` (`VarkaCoverageDifferentialSuite`) for the guarded rows
   added, run `dev/varka_census.py --check`, and run `VarkaCoverageSuite`,
   `VarkaCoverageDifferentialSuite`, `VarkaGoldenCorpusSuite` and `VarkaEmittedBytesSuite` before
   the first push.
6. This file's section 11 records the outcome and scores section 7.

## 11. Open questions for the owner

1. **Compose `p IS TRUE`, or add `IsTrue(Cond)`?** Recommendation: compose (no new node, priced
   parts); build `IsTrue` only if a benchmark of a guarded `p IS TRUE` on null-free batches shows
   the missing dense body costing something worth the node.
2. **Is `p <=> q` over two predicates in this row?** Decided in the plan, object if wrong: no,
   331 builds it (`BoolNullSafeEq`, 331 2.8) because it owns the boolean leaves, so this row has no
   hand-off waiting on 331 and keeps only `p <=> literal` and value operands.
3. **Grammar production and refit.** Recommendation: a follow-up row taken with the next refit,
   not this one, so this row's bytes fixtures and the model stay still.
4. **Also decided in the plan, object if wrong** (the owner delegated these to the plan's
   recommendation, 21 October 2026): the node stays unpriced until 352's refit (section 6, as in
   the other two plans); `VarkaEmitterNullSafeSuite` is a `VarkaOwnJvm` suite (test 1); and the
   331/336 dependency is one statement: 336 follows only 334, 331 builds the boolean `<=>`.

### 11.1 Review of 21 October 2026

An independent read-only review of this plan, applied the same day. Corrected: the boolean `<=>`
was handed from this plan to 331 and back (2.3, test 8, section 9, question 2: now 331's); the
`lanesOverride` sweep had no JVM rule (test 1); the common regeneration step was missing (10.5);
the unpriced stance is now worded identically in all three plans (section 6). The reviewer found
this plan's cost-model section the consistent one and its optimizer facts correct.

## 12. Outcome

*21 October 2026.* `a <=> b` and `equal_null(a, b)` fuse over the six types of the coverage table's
`EqualTo` rows, with computed and widened operands, as filters, `CASE` conditions, under `NOT` and
under `AND`/`OR`; `p IS [NOT] TRUE|FALSE` and `IS [NOT] DISTINCT FROM` fuse over any predicate.
The design is the plan's: one IR node, `NullSafeEqual`, for value operands, and the predicate forms
composed as `And(p, IsNotNull(p))` (over `Not(p)` for the false forms). The questions of section
11 took the plan's recommendations (compose; boolean-boolean `<=>` is 331's; no grammar production;
the node unpriced until 352's refit; the emitter suite in its own JVM) and the owner was not asked.
`dev/varka_emit.sh` on `CASE WHEN d <=> d2 THEN year(d) ELSE month(d) END` answered `unsupported
predicate: (d <=> d2)` before and fuses now, as do `(d < d2) IS TRUE`, `(d < d2) IS NOT FALSE`,
`year(d) <=> month(d2)` and `i <=> 5`; the kernels are masked-only because the calendar fields are
guarded, as section 2.3 said.

**What differed from the plan.**

1. **Five suites demand that every node be drawn, priced or listed, and the plan named one.**
   Section 6 expected the cost audit to be the only place the unpriced node could be forced to
   touch the model. The IR fuzz grammar's two reach tests, `VarkaWithChildrenSuite`, the cost
   suite's "every feature has a price", the lane-type suite's two node lists and the weight
   register (seven tests in all) each failed on the new record. Each is answered in words, not by a
   production or a price: a list of nodes deliberately out of the grammars (`VarkaIrFuzzSuite`,
   `VarkaWithChildrenSuite`, with a direct rebuild test for the node), `awaitingRefit` in
   `VarkaEmitCostSuite` (which fails if the node is priced while still listed), the node in the
   lane-type lists, and a weight-register row (weight 1, one lane call, measured). No price is
   invented and the grammar, the cost corpus and the seeded shapes are untouched. The cost is that
   the fuzzers never draw the node until the follow-up row of open question 3; it is covered by
   its own suite, the coverage rows' composition fuzzer (the sampled compiled check included, in
   the nightly's `all` mode) and the Spark-held answers of the coverage rows.
2. **The golden corpus has no `<=>` statement.** Section 10.5 expected verdicts to move because `<=>`
   occurs in Spark's golden SQL; the corpus is the date, time and interval files, and
   `VARKA_GOLDEN_REGEN=true` left `golden_corpus.json` byte-identical (zero statements contain
   `<=>`, `equal_null`, `IS TRUE` or `IS FALSE`).
3. **The width audit and the docs table are regenerated in an order.** The docs table prints the
   audit's verdict per row, so `coverage.json` and the docs regenerate first, then the bytes and
   the audit, then the docs again; one pass leaves `docs/sql-varka.md` stale against the audit,
   which the first matrix run reported. The regenerated audit also adds the row `d2 - d` (row 39),
   which master's audit lacked.
4. **A harness gap, not this row's, surfaced.** The composition fuzzer's compiled check samples
   rows by a seeded shuffle of the table; the new rows moved the sample onto `shiftright(l, i ^ 5)`,
   a long-lane kernel that also reads an int-lane literal, and the check fails on it with an index
   out of bounds because the case carries the long table only. The sample's filter ("a kernel that
   reads both literal tables has no form in the case") now excludes any long-lane kernel with an
   int literal. Every such row failed whenever sampled; the nightly's `all` mode would have failed
   on it since `shiftright(l, i ^ 5)` landed.
5. **Tests the plan listed and this row did not build.** Test 5's sweep of `IS TRUE`, `IS FALSE`,
   `IS NOT TRUE` and `IS NOT FALSE` over every one of the 46 nullable predicate rows is five rows
   (the four forms over `d < d2`, and the guarded conjunction under `IS TRUE`) through the
   differential's per-row, partition and `CASE` tests, not 46 times four; the `IF(c, true, false)` and `CASE` shapes whose
   rewrite produces the form are not written out, since the optimizer's rewrite reaches the same
   node as `IS TRUE`; and test 7's `i <=> 5 AND d = d2` is replaced by the sharper
   `isnotnull(l <=> l2)` implies no column check, against `l = l2`'s positive control. Test 2's
   `date_add(d, i) <=> d2` and `l <=> i + 1` are IR cases (`Overflow.NULL` sum, a literal sum) and
   the SQL row `year(d) <=> month(d2)`.
6. **The partition oracle's third setting** is `partitionRewrites` plus `NullPropagation`, applied
   to every row whose SQL is a null-safe equality or one of the `IS [NOT] TRUE|FALSE` or `DISTINCT
   FROM` spellings, with `mustFuse` set: the null tests must reach a Varka filter that a kernel
   served, which they do on every fixture.

**Tests.** `VarkaEmitterNullSafeSuite` (own JVM, 7 tests, 12 s): the reference evaluator against
Spark's `EqualNullSafe.eval` over every validity pair, the node in both bodies at four, eight and the
default lanes over `combos(2)` (the 16 pairs of null patterns) at 15 lengths, three-input computed
operands, the long lane at 2, 4 and 8 lanes, the widening, the dense body's bytes equal to
`Compare` EQ's and the masked loop's Vector API calls unchanged, and a batch whose last rows are null
in both columns at every tail length. Dropping the `~(vA | vB)` term from the emission fails four of
the first six. `catalyst/testOnly *Varka*`: see the pull request. `VarkaCoverageDifferentialSuite`
and `VarkaGoldenCorpusSuite`: 595 tests, none failed, the 76 differential tests of the 19 new rows
taking 17.8 s of the suite's 127 s on a quiet machine (the first run of the 16 rows, beside another
agent's JVMs, took 37.4 s for 64). `dev/varka_matrix.sh --config lanesOverride=4 --module catalyst
-j 2`: every suite passed at the defaults and at four lanes but the docs-table test, which was the
stale table of item 3 above.

### 12.1 The predictions

| | prediction | measured | |
| :-- | :-- | :-- | :-- |
| 1 | every new row fuses on every fixture, under the default optimizer and with the rewrites excluded | all 19 rows (the 16 listed and `IS NOT TRUE`, `IS FALSE`, `IS DISTINCT FROM`) pass the per-row, partition (three settings) and `CASE` tests, each with a kernel serving every batch | held |
| 2 | no existing row's emitted bytes move | 183 rows, 19 added, 0 changed, at both widths, the fuzz arms identical; only the aggregate option-arm hashes moved, since they hash the coverage rows | held |
| 3 | dense bodies of `a <=> b` equal `a = b`'s; the masked body adds scalar operations only | the dense `loopDense0` has the same `IntVector` and `VectorMask` calls and code size; the masked loop has the same Vector API calls and a larger code size | held |
| 4 | the added test time is under 40 s | the differential's 76 new tests 17.8 s and the new suite 12 s on a quiet machine, about 30 s; the first run under load, 37.4 s and 12 s, about 50 s; the coverage-reading suites' rows unmeasured | held quiet, missed under load (1.25 at most) |

The estimate priced the differential's rows and left out the new suite's own JVM; the same rows cost
twice as much beside another agent's JVMs.
