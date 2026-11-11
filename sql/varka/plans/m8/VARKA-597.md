# VARKA-597: No decimal-reading value slower than the row path at two long lanes

Row 597 of `PLAN.md`. Added on 10 November 2026 on the owner's word, from row 596's build
(#1478, branch `varka-596-pair-value-two-lanes`, open), whose diagnosis found the decimal load,
not the comparison, losing at two long lanes. Planned on 11 November 2026.

## 0. What exists now (admission check)

From master at `d241c12372d`, by reading the compilers, the IR, the decimal baseline's committed
runner files, the census cells and the open pull requests #1478 (row 596) and #1484 (row 104),
and the unpushed branch `varka-360-decimal-add` (row 360, at `44365202b7f`). Nothing was
dispatched and no JVM was run for this plan. **[I]** marks a claim read from the code and not yet
run; step 1's dry run checks each one.

1. **On master a decimal reaches a kernel only through a condition.** The decimal compiler's
   readers (`value`, `VarkaDecimalCompiler.java:188-238`, and the pair's) are reached from four
   sites, all in `VarkaConditionCompiler`: `IN` (`:1053`), the null tests (`:1268`), the
   comparisons (`:1484`) and `<=>` (`:1659`). A
   decimal-valued root goes to `VarkaDecimalCompiler.output` (`:650-666`), which admits only the two
   `TIME` producers, `time_to_seconds` and `extract(SECOND ...)`, and declines every other
   decimal value naming row 348's part 348b. A bare decimal column read by a kernel declines too
   (`VarkaTimeCompiler.java:226-235`), and a bare column as a projection entry is forwarded, not
   read (`VarkaOutputSpec.ForwardedOutput`, `VarkaOutputSpec.java:57`). The `TIME` producers write a
   decimal and read none.
2. **So the census of decimal-reading values today is the value nodes that hold a condition.**
   `VarkaVectorIR` has three: `IfElse` (`varka/VarkaVectorIR.java:1801`), `CondValue` (`:1824`)
   and `BoolOutput` (`:1850`). Every other node holding a `Cond` is a `Cond` itself. Every tree
   holding a decimal leaf drives at the long lane (`VarkaKernelPlan.emissionLane`,
   `varka/VarkaKernelPlan.java:347-373`), so each of these is a long-driven root, the case row
   503's host check reads.
3. **`BoolOutput`: a condition over a decimal written as a `BOOLEAN` output.** Any comparison,
   `IN`, `<=>`, `BETWEEN`, null test or connective over them (row 27's arm). Row 596 (#1478) sends
   it to the row path at two long lanes: `VarkaKernelPlan.hostRefusal` gains a `BoolOutput` arm
   that walks the root for a `DecimalColumn` or `DecimalWord` leaf (`readsDecimal`), under row
   503's setting `spark.sql.codegen.varka.declineTwoLaneConditions`, which #1478 also carries into
   the projection compile.
4. **`CondValue`: a decimal condition cast to a number.** `CAST(x < y AS INT)` and the other
   integral targets build `CondValue` over the condition in `VarkaIntegralCastCompiler.select`
   (`:233-255`), with a `NarrowLane` to the target. A cast to a floating target goes through
   `VarkaFloatingCastCompiler.booleanValue` (`:135-149`), which narrows a long condition's value to
   the int lane before converting. Both fuse at two long lanes **[I]**: the root is a value, not a
   `Cond`, and not a `BoolOutput`, so neither 503's refusal nor 596's reaches it.
5. **`IfElse`: a choice on a decimal condition with non-decimal branches.**
   `CASE WHEN x < y THEN l ELSE 0 END`, `IF(x IS NULL, l, l2)` and `nvl2(x, l, 0)` (the optimizer
   writes `nvl2` as `If(IsNotNull(x), ...)`) compile through `VarkaConditionCompiler.ifElse`
   (`:150-184`) or `caseWhen`, the condition by `compileCond`. With `BIGINT` branches the
   condition stays on the long lane (`onValueLane`, `:333-339`) and the choice fuses **[I]**; with
   `INT` branches the lanes differ and `sameLane` likely declines it **[I]**. A decimal operand of a
   `coalesce` is a decimal value, so it declines as an output (item 1).
6. **No coverage row and no test pins items 4 and 5 over a decimal.** `coverage.json` holds decimal
   columns only under a comparison or a null test (`CASE WHEN d9 < d9b THEN d9 ELSE d9s4 END > 0.0`,
   `IF(d18 > 0.00, d38, d38b) IS NULL`), and no test source writes `CAST(d9 < d9b AS INT)`,
   `IF(d9 < d9b, l, 0)` or `nvl2(d9, ...)`. Hence the **[I]**s.
7. **What the record measures at 128 bits.** `VarkaDecimalBaselineBenchmark`'s 128-bit runner file
   (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`)
   holds one decimal-reading value as fused, `x < y` as a value, `BoolOutput`, at four precisions
   and two densities. Varka arm against the row path, best times:

   | `x < y` as a value | no nulls | one row in ten null | run |
   |---|---|---|---|
   | `DECIMAL(9, 2)`, long lane | 484 / 126 ms, 0.3X (lines 723-724) | 599 / 124 ms, 0.2X | MaxGekk/spark 41340265935 |
   | `DECIMAL(18, 4)`, long lane | 473 / 127 ms, 0.3X (867-868) | 599 / 123 ms, 0.2X | the same |
   | `DECIMAL(38, 10)`, pair | 871 / 778 ms, 0.9X (435-436) | 995 / 740 ms, 0.7X | MaxGekk/spark 42773203967 |
   | `DECIMAL(20, 2)`, pair | 882 / 836 ms, 0.9X (579-580) | 1020 / 779 ms, 0.8X | the same |

   Per row at `(9, 2)` that is 241.9 ns against the row path's 63.2. #1478 re-times all eight on
   the row path (vecbricks/varka run 42964088305, 1.0X on both arms) and splices them on its branch.
   At 256 bits the same sections run 4.5X to 5.0X on the long lane and 21.3X to 31.6X for the pair
   (the 256-bit file, the same lines). **Nothing else in the file times a fused decimal-reading
   value at 128 bits.** Every other value section there is `NONE` by the class and ties: the
   arithmetic, casts, choices, remainders and widening sums. Two kinds of section can mislead:
   - The `DECIMAL(9, 2)` and `(18, 4)` "comparison" sections at 0.2X are filters timed on
     1 November (run 41340265935), before #1262 made them decline. They are filters, not values,
     and stale.
   - The two `TIME` producers' 1.0X is a row-path baseline. Their run's commit `c2455573cca`
     predates #1348, which fused them, so they are unmeasured as fused at 128 bits.
8. **The mechanism, as the record has it.** C2 lowers none of the decimal load's work at two long
   lanes on AVX2:
   - **Census cells.** The EPYC 7763's cells at 128 bits
     (`m8/results/VARKA-329-census-epyc-7763.json`) read `long.dec_check_rearrange`, 347's load with
     its check, as the fallback at 584 bytes a row. The two-vector `selectFrom` form is the fallback
     too (72 bytes; on the laptop's AVX-512VL it is intrinsic, `VARKA-347.md` 10.1 items 1 and 2),
     and so is `long.LT` (92 bytes). The sign shift `ASHR` and `NEG` are intrinsic.
   - **Width audit.** On #1478's branch the audit's new constructions record what `PrintIntrinsics`
     refuses at 128 bits: 2 calls in `l < l2` as a boolean output (the compare and the mask's
     `toLong`), 14 in `d9 < d9b` and 15 in `d38 < d38b`, the rearrange, the blend and the slot
     check's compares among them. None is refused at 256 or 512.
   - **Spike.** `VarkaDecimalPairSpikeBenchmark` at 128 bits on the EPYC 7763
     (`sql/catalyst/benchmarks/...-runner-epyc7763-128bit-results.txt`, MaxGekk/spark run
     41264904788) has the pair's load and check alone at 135.3 ns a row in P and 1.6 in S, the
     scalar loop. The kernel costs more than the spike's loop: 435.4 ns a row for the fused
     `x < y` at `(38, 10)`, against the spike's 165.2 to 168.7 for the same compare into a word.
   - **What is directional.** "The load, not the compare" rests on the laptop's ladder (#1478's
     `results/VARKA-596/two-lane-before-128bit.txt`, a shared machine): `l < l2` as a value at 1.6X
     and 1.9X of the row path and `DECIMAL(9, 2)` at 0.2X. The EPYC record has no `BIGINT` value
     control at 128 bits on master. #1484's runs do (wrapping `BIGINT` arithmetic at 3.8x to 8.7x,
     vecbricks/varka run 42930757311, on that branch), and row 503's laptop blends ran at 1.3X to
     5.6X (`m8/results/VARKA-503/`). Step 1 owes the control in the same class.
9. **Two refusal mechanisms are in flight.** #1478 refuses on the IR, in `hostRefusal`, keyed on a
   root's leaves. #1484 refuses at compiler arms: `DeclineSink.declinesTwoLaneChecks`, read from the
   session's `VarkaAdmission`, declines a checked `BIGINT` operation as it is built
   (`TWO_LANE_CHECK_REASON`), "keyed on the `BIGINT` arms rather than on the IR". Both change the
   `compilePartial` overloads: #1478 removes the ones without an admission, #1484 keeps them for
   tests.
10. **`readsDecimal` and row 360's leaf-free pair.** On #1478 every lane-pair compare reads a
    `DecimalWord` leaf or a long-lane `DecimalColumn` under its sign extension, so the leaf test is
    exact there. Row 360's 360a (branch `varka-360-decimal-add`, unpushed) adds `bigintPair`
    (`VarkaDecimalCompiler.java:613-628` there): `CAST(l AS DECIMAL(20, 0))` is the long `l` and
    its sign `l >> 63`, with no decimal leaf. So
    `CAST(l AS DECIMAL(20, 0)) < CAST(l2 AS DECIMAL(20, 0))` as a value has the pair's three
    compares and two mask joins but no leaf, and `readsDecimal` misses it. It pays no slot load,
    so whether it loses is open; its compares are the fallback. It is unmeasured.
11. **Forms that will read a decimal as a value, and their rows.** None fuses on master. 348b: `-x`,
    `abs`, the choices and picks, the rescaling cast and 349's sums and products within 18 digits,
    written through 157's store, with row 578's null family (#1436) above them. 359b: the pair's
    store, choices, negation, `abs` and `IN` as values. 360: the wide sums as values (360d), the
    capped sums (360c, through row 481's `NULL` mode) and the leaf-free pair of item 10. 361 and
    362: products. 363: the scale-cutting rules and the rounding functions with 351. 350, 364 and
    366: casts of a decimal to integral and floating types, non-decimal outputs that read one.
    577b: the remainders, which already decline at two long lanes by default (row 604 adds the
    opt-in form). Each plan already says its projections are "measured against the row path at
    128 bits and decline if they lose" (`VARKA-359.md` 2.6, `VARKA-360.md` 2.7 on 360's branch,
    `VARKA-364.md` 2.6), with no shared mechanism.
12. **The cheaper form exists only as a spike.** S, the scalar loop, is the form the spike shows
    winning at two long lanes on x86 (`VARKA-359.md` 11.4, 11.8 point 3). It is a new emission mode,
    a scalar subtree feeding a vector kernel. The owner's answer (a) to `VARKA-359.md` question 3
    makes it "a row of its own", and no row or scope item holds it yet (`m9/SCOPE.md` item 101 is
    the 2-lane long kernel's mask-free form, not S).

### What differs from the row text

- The row lists `-x`, `abs(x)`, "the decimal arithmetic and casts that fuse" and "a decimal
  column passed through" as values to measure. On master none of them fuses as a value (items 1
  and 11), and a column passed through is forwarded. So the forms this row can measure today are
  items 4 and 5, beside 596's item 3. The others are covered by the mechanism, not by a section,
  until their rows land (section 2.2, question 7.4).
- The row's "same rule as row 596" is kept as the session setting, but the key moves from the
  `BoolOutput` root to the decimal operand (2.1), which also covers item 10.

## 1. Goal and non-goal

**Goal.** At two long lanes on x86 no value that reads a decimal runs fused slower than Spark's
row path. Each form that fuses today is measured on the EPYC 7763 at 128 bits. Each that loses
declines there under row 503's setting, with its reason in `EXPLAIN`. Every later form that reads a
decimal declines there by the same mechanism until its own measurement says otherwise.

**Non-goal.** A cheaper lowering at two lanes, S or any other: question 7.3. Filters (row 503,
done). Any host other than x86 at two long lanes: aarch64 lowers two-lane masks, and
`twoLaneMasksFallBack` is false there. The 256-bit and 512-bit kernels, whose bytes do not move.

## 2. Design

### 2.1 The decline: keyed on the decimal operand, at the compiler's entry

Three candidates, with their costs:

- **(D1) Widen #1478's IR key.** In `hostRefusal`, refuse every long-driven root whose tree reads
  a `DecimalColumn` or `DecimalWord`, not only a `BoolOutput`. That is about ten lines, the
  `instanceof BoolOutput` test dropped. It misses item 10's leaf-free pair. A marker for that
  pair, "holds an unsigned long compare" (only `DecimalWords.compare` and 360's carry and borrow
  build `ULT` and `ULE`), catches its ordered compares and carries. It misses its `=`, `<>` and
  `<=>`, which are signed `EQ` compares, so a later pair form needs a new marker each time.
- **(D2) Key on the operand, at the decimal compiler's entry.** Every call into
  `VarkaDecimalCompiler` from outside opens with `sink.enterDecimal()`, at three sites: the
  comparisons and `<=>` (`compared`, `VarkaDecimalCompiler.java:500`), the null test
  (`nullTested`, `:737`) and `IN` (`compileDecimalIn`, `VarkaConditionCompiler.java:1051`). The
  fourth entry is `output` (`:650`), for a decimal-valued root. Each declines with a reason of its
  own where the session's admission says the host falls back, `output` only for a root that reads
  a decimal (the `TIME` producers read none). This uses #1484's route, `DeclineSink` carrying the
  session flag. Every decimal operand then declines by construction, whatever its root or later
  row:
  - a leaf, a rescale, 360's `bigintPair`, or a form 348b or 359b adds;
  - a decimal conjunct of a filter too, which 503 already sends to the row path, so the plan is the
    same and only its reason changes.

  #1478's `readsDecimal` and its `BoolOutput` arm become redundant and are removed. About 40 lines
  of source, plus the tests of 3.
- **(D3) A cheaper form.** S at two long lanes, behind an option as row 604 does for the
  remainder. That is a new emission mode and a new row (item 12, question 7.3), not this one.

**Recommended: D2** (question 7.1). It is the only key that covers item 10 and every row of item
11 without a marker per form. It follows #1484's precedent for the projection compile, and it
leaves `hostRefusal` as 503 wrote it.

**Cost.** D2 lands after both #1478 and #1484, since it uses #1478's plumbing of the admission
into `compilePartial` and #1484's `DeclineSink` flag, and their overloads disagree (item 9). If
#1484 has not merged when the step starts, D1 with the marker is the fallback under the same
tests, and the leaf-free pair's equality is left to row 360's measurement.

### 2.2 Decline by default, lift by measurement

The decline is the default for every decimal operand at two long lanes, not a list of measured
losers. A list of losers lets each later row fuse a new form there unmeasured, which is how row 596
arose: 359a's `x < y` as a value fused at two lanes because 503 measured only filters. Under the
default:

- A later row whose form might win at two lanes measures it with the refusal off
  (`-Dspark.sql.codegen.varka.declineTwoLaneConditions=false` in `extra-java-options`, as
  `VARKA-359.md` 10.5 and #1484 prepared). It commits that run as a side record under
  `results/VARKA-<n>/`, not spliced, and asks the owner to exempt the form.
- **No exemption mechanism is built until a measured win asks for one.** The candidates for such a
  win are the heavy forms whose row path is `BigDecimal` work: at 128 bits the row path takes
  1041 ms for `x + y` at `DECIMAL(20, 2)`, 2012 for `pmod` at `(20, 2)` and 2461 for the
  `DECIMAL(38, 10)` chain, against 126 for `x < y` at `(9, 2)`. **[I]** that any of them wins:
  the kernel cost 435.4 ns a row where the spike's loop cost 168.7 (item 8), so the spike does not
  predict the kernel.

This is the default the owner chose for the remainder (`VARKA-577.md` 7.4, answer (c): decline at
two long lanes, the cheaper form an opt-in row) and 503's "refused by the mechanism" for the
64-bit types it did not time.

### 2.3 What is deliberately unchanged

- Row 503's filter refusal (`TWO_LANE_CONDITION_REASON`).
- Row 104's checked `BIGINT` refusal.
- The `BIGINT` and `DOUBLE` boolean outputs (596 leaves them fused, and the step 1 control keeps
  measuring `BIGINT`).
- The `TIME` producers, which read no decimal.
- Every emitted byte: `emitted_bytes.json` does not move. The refusal decides admission, not
  emission.

## 3. Tests and proofs

1. **The census as an admission test** (catalyst, `VarkaTwoLaneConditionSuite`, compile only). Run
   under the two-lane admission, then with the refusal off. Each of items 3 to 5 is tried at
   `DECIMAL(9, 2)` and `DECIMAL(38, 10)`: `x < y` as a value, `CAST(x < y AS INT)`,
   `CAST(x < y AS DOUBLE)`, `IF(x < y, l, 0)` and `nvl2(x, l, 0)`.
   - Under the admission, each declines with the decimal reason.
   - With the refusal off, each is admitted.
   - `BIGINT` controls of the same shapes are admitted under both.

   After 360a, `CAST(l AS DECIMAL(20, 0)) < CAST(l2 AS DECIMAL(20, 0))` as a value joins, in the
   later of the two pull requests. Planted by hand, then reverted: `pair`'s entry not checking the
   flag fails the `(38, 10)` and leaf-free cases.
2. **The sweep that guards later rows** (catalyst, compile only). Each `coverage.json` expression
   whose columns include a decimal is compiled as a projection entry, cast to `INT` where it is a
   condition, under the two-lane admission, and must decline or be a forwarded column. A row
   that adds a decimal coverage row is held to the default with no edit here.
3. **The task-side compile** (sql/core, `VarkaTwoLaneDefault128Suite`, own JVM under
   `-XX:UseAVX=2 -XX:MaxVectorSize=16`, setting unset). `SELECT CAST(p < p2 AS INT),
   IF(w < w2, l, 0), nvl2(p, l, 0), CAST(l < l2 AS INT)` without and with nulls.
   - The answers equal Varka off.
   - The decimal entries are residual with the reason, and the `BIGINT` one is fused.
   - The task's evaluator, built from the node's settings, declines the same entries (596's check).
4. **The baseline's classes** (`VarkaDecimalBaselineQueriesSuite`), with the refusal off and on, for
   every section of 4.
5. **The width audit** at 128 bits: no construction moves. A `CAST(d9 < d9b AS INT)` construction
   is added only if 597a's dry run shows it fusing (it records the same refused calls as 596's).

No proof file moves. No IR node is added, so no registry suite moves.

## 4. The baseline, and predictions

**The sections** (597a, `VarkaDecimalBaselineBenchmark`). Each decimal shape runs at the four
precisions the comparisons already use, `DECIMAL(9, 2)`, `(18, 4)`, `(38, 10)` and `(20, 2)`, at
both null densities:

- `CAST(x < y AS INT)`, the `CondValue` form;
- `IF(x < y, l, 0)`, the `IfElse` form;
- `nvl2(x, l, 0)`, a choice on a null test alone, where Spark's row path reads only the null bit.

That is 24 sections. Two `BIGINT` controls at both densities, `CAST(l > 0 AS INT)` and
`IF(l > 0, l, 0)`, add 4, for 28. `l` is the class's existing `BIGINT` column, so no column is
added and every earlier section's values stay as committed. The classes are `WHOLE` at both widths
before 597b. After it, the decimal sections are `NONE` where the session refuses (the
`fusedFilter` term 596 uses), and the controls stay `WHOLE`.

**The runs.** `VarkaDecimalBaselineBenchmark` on the EPYC 7763 at 256 and 128 bits, the whole
class: row 599 is open, so a `sections` run cannot write its numbers. The 28 sections are spliced
into the two runner files with `dev/varka_bench_sections.py splice`, each provenance naming its
run. These are dispatched on vecbricks/varka on the owner's word only:

    gh workflow run benchmark.yml -R vecbricks/varka --ref master -f pr=<597a's PR> \
      -f class=VarkaDecimalBaselineBenchmark -f jdk=25 -f generate-tpcds=false \
      -f expected-cpu="AMD EPYC 7763"
    gh workflow run benchmark.yml -R vecbricks/varka --ref master -f pr=<597a's PR> \
      -f class=VarkaDecimalBaselineBenchmark -f jdk=25 -f generate-tpcds=false \
      -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-XX:MaxVectorSize=16

597b reruns the second dispatch on its own pull request, so the decimal sections are re-timed on
the row path, and splices them.

**Predictions, registered before the runs** (EPYC 7763, best times, Varka arm against the row
path):

1. At 128 bits, at `(9, 2)` and `(18, 4)`, `CAST(x < y AS INT)` and `IF(x < y, l, 0)` run at 0.2X
   to 0.4X. They pay the same load as `x < y` as a value (0.2X and 0.3X, item 7), and a narrowing
   or a blend adds little against it.
2. At 128 bits, for the pair at `(38, 10)` and `(20, 2)`, those two run at 0.6X to 1.0X, as `x < y`
   as a value did (0.7X to 0.9X). The row path's `BigDecimal` compare hides part of the load.
3. At 128 bits `nvl2(x, l, 0)` loses at every precision, at 0.1X to 0.5X, and the pair's loss is
   larger than the comparisons'. The kernel loads and checks the slots under the null test
   (`VARKA-359.md` 0 item 5), while the row path reads only the null bit.
4. At 128 bits the `BIGINT` controls run at 1.3X or more, as 503's blends did on the laptop (1.3X
   to 5.6X). This is the EPYC control item 8 lacks; a control below 1.0X would say the loss is not
   the load alone, and 597b would stop for a replan.
5. At 256 bits every decimal section runs at 3X or more (`x < y` as a value: 4.5X to 5.0X on the
   long lane, 21.3X to 31.6X for the pair).
6. After 597b, at 128 bits, every decimal section ties the row path (0.9X to 1.1X), both arms
   being the row path, and the controls do not move.

**The decision rule.** A decimal section under 1.0X declines. Any ratio within 1.3x of 1.0 is
re-run once and read by minimums before it is written down (`sql/varka/AGENTS.md`, "Measurements,
not adjectives"). A decimal section at 1.3X or more by minimums in both runs goes to the owner as
an exemption candidate (2.2) rather than declining silently. Under predictions 1 to 3 none does.

## 5. Order, files and size

1. **597a, the baseline addition**, its own pull request, about 150 to 250 lines:
   - the 28 sections of 4 with their class terms;
   - the class doc;
   - the `VarkaDecimalBaselineQueriesSuite` lists;
   - the dry runs at the default, under `-XX:UseAVX=2`, and under
     `-XX:UseAVX=2 -XX:MaxVectorSize=16`, each holding the class: the check of items 4 and 5's
     **[I]**s.

   A shape the dry run shows declining today is dropped, and the plan is corrected to say so.
   Then the two runs and their splice, and predictions 1 to 5 scored in 10. **After #1478**, whose
   edits to the same class and the same 128-bit file it would otherwise conflict with.
2. **597b, the decline**, after 597a's reading, #1478 and #1484, about 300 to 450 lines. D2 (or
   the answer to question 7.1), the tests of 3, the classes following, the 128-bit rerun and its
   splice, and prediction 6 scored. If 597a finds a winner, 2.2 applies to it before 597b is
   built. `VarkaKernelPlan`'s javadoc, the session setting's doc and the
   `vector-api-and-width.md` lesson #1478 adds are updated to name the operand key.
3. **Row 360's leaf-free pair.** No pull request of its own: whichever of 597b and 360a lands
   second adds the case of 3 item 1.
4. **The later rows.** No pull request: 597b's class doc and this plan state the default (2.2),
   and the plans of rows 348, 359, 360, 361 to 364 and 366 already measure their projections at 128
   bits.

| file | what |
|---|---|
| `VarkaDecimalCompiler.java`, `VarkaConditionCompiler.java` | D2's check at the four entries, its reason |
| `varka/VarkaKernelPlan.java` | #1478's `BoolOutput` arm and `readsDecimal` removed, javadoc |
| `SQLConf.scala` | the setting's doc names the decimal operands |
| `VarkaTwoLaneConditionSuite.java`, a sweep suite or a case in it | 3 items 1 and 2 |
| `VarkaTwoLaneDefault128Suite.java` | 3 item 3 |
| `VarkaDecimalBaselineBenchmark.java`, its queries suite | 4's sections and classes |
| results | the two runner files and their provenance (generated, not counted) |

**Size: medium as the row says.** Each pull request is well under the 1000-line cap. The
measurement is two dispatches for 597a and one for 597b, each on the owner's word.

## 6. Out of this row

- S or any cheaper two-lane lowering (question 7.3).
- The exemption mechanism, until a measured win asks for one (2.2).
- The `TIME` producers' fused reading at 128 bits: they read no decimal. The class's runner
  files still hold their pre-#1348 row-path numbers, and a rerun of the class re-times them.
- `DOUBLE` boolean outputs, which 596 left fused and unmeasured on the runner (row 543).

## 7. Questions for the owner

1. **The mechanism.** (a) D2: decline at the decimal compiler's entry through the session flag on
   `DeclineSink`, after #1478 and #1484, removing 596's `BoolOutput` arm. (b) D1: widen #1478's IR
   key to every root, with an unsigned-compare marker for the leaf-free pair. *Recommended: (a)*.
   It covers every decimal operand, row 360's `bigintPair` and every later row's form by
   construction. D1 needs a marker per new leaf-free form and misses the pair's equality.
2. **The default for forms that fuse later.** (a) Every decimal operand declines at two long lanes
   by default. A later row lifts it for a form only with a measured win, a refusal-off run at 128
   bits on the EPYC 7763 committed as a side record, and the owner's word. (b) Each later row
   measures its own forms and declines its losers. *Recommended: (a)*. Under (b) the first form a
   row forgets fuses and loses, as 359a's did, and (a) is the default already chosen for the
   remainder (question 7.4 of `VARKA-577.md`, answer (c)).
3. **S, the cheaper form.** (a) No milestone 8 row. Record S as a scope item beside `m9/SCOPE.md`
   item 101, with the spike's 128-bit numbers (item 8) and the owner's answer (a) to
   `VARKA-359.md` question 3. (b) Open a milestone 8 row now, S behind an option at two long lanes,
   as row 604 is for the remainder. *Recommended: (a)*. Milestone 8's goal is the new types, and S
   is a new emission mode whose only win is where an x86 JVM's long species has two lanes (an SSE
   or AVX-only machine, `m9/SCOPE.md` item 101, or `-XX:MaxVectorSize=16`), which the decline
   already serves at the row path's speed.
4. **The done-when's scope.** The row's text asks for every decimal-reading value to be measured,
   including forms that do not fuse as values yet (0, "What differs"). (a) 597 is done with the
   forms that fuse at 597b's merge measured and the mechanism in place. Later forms are held by the
   default of question 2 and 3 item 2's sweep. (b) 597 stays open until 348b, 359b, 360d, 364 and
   366 land and each form has a section. *Recommended: (a)*. Under (b) the row waits on five other
   rows for forms that already decline by construction.

## 8. Done when

- **Measured.** Each decimal-reading value form that fuses on master when 597b merges has a section
  in `VarkaDecimalBaselineBenchmark`, measured on the EPYC 7763 at 128 and 256 bits, with a `BIGINT`
  control. These are 596's `BOOLEAN` output and 0 items 4 and 5 at the long lane and the pair, at
  both null densities. The sections are spliced into both runner files with provenance naming the
  runs.
- **Declined.** Each form that loses declines at two long lanes on x86 under row 503's setting,
  with its reason in `EXPLAIN`, and stays fused with the setting off.
- **By construction.** The decline covers every decimal operand, row 360's leaf-free pair among
  them once 360a lands, held by 3's admission test and sweep.
- **Rerun.** After it, the 128-bit rerun has no decimal section below 1.0X by the decision rule of
  4, and the `BIGINT` controls hold.
- **Unchanged elsewhere.** Nothing moves at 256 or 512 bits or on aarch64, and
  `emitted_bytes.json` does not move.
- **Scored.** 4's predictions are scored in 10, and the answers to section 7 are recorded.

Or, under question 4's answer (b), the same for every form of 0 item 11 as its row lands.

## 9. Open points for the lead

1. **Merge order.** #1478, then #1484, then 597b. 597a needs only #1478. #1478's row-596 edit and
   this plan's row-597 edit are adjacent lines of `PLAN.md`, so the second to merge resolves a
   one-line conflict as master plus its own row.
2. **Row 599.** If it lands before 597a's runs, the dispatches take `sections` with the 28
   titles instead of running the whole class.

## 10. Outcome

Nothing built yet.
