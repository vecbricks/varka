# VARKA-597: No decimal-reading value slower than the row path at two long lanes

Row 597 of `PLAN.md`. Added on 10 November 2026 on the owner's word, from row 596's build
(#1478, branch `varka-596-pair-value-two-lanes`, open), whose diagnosis found the decimal load,
not the comparison, losing at two long lanes. Planned on 11 November 2026.

***Changed on 28 November 2026.** The owner brought the row's second half, 597b, back to
milestone 8 as a gate of its close; the first half, 597a, stays in `m10/SCOPE.md` item 140.
Section 11 is new and is the plan of what is left: one part, written for a builder with nobody
to ask. Sections 0 to 9 are the plan of 11 November 2026, kept word for word as the record;
where section 11 differs it wins, and 11.9 lists each difference. Section 10 is unchanged.*

***Fix round of 28 November 2026** (review 1 of the plans of rows 350, 346 and 597b). The owner
answered 11.7's questions 1 and 2: 597b removes the arm-level reasons `TWO_LANE_REASON` and
`TWO_LANE_WIDE_REASON` (11.5 step 3b), and `bigintPair` declines with the readers, the side
that lands second adding its call (11.5 step 2). Every changed passage is marked "fix round".*

## 0. What exists now (admission check)

From master at `d241c12372d`, by reading the compilers, the IR, the decimal baseline's committed
runner files, the census cells and the open pull requests #1478 (row 596) and #1484 (row 104),
and the unpushed branch `varka-360-decimal-add` (row 360, at `44365202b7f`). Nothing was
dispatched and no JVM was run for this plan. **[I]** marks a claim read from the code and not yet
run; 597a's dry run (5 item 1) checks each one.

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
     5.6X (`m8/results/VARKA-503/`). Both of the last two sources are on open branches or the laptop:
     they are directions only, no decision below rests on them, and 597a owes the EPYC control in
     the same class.
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
    makes it "a row of its own", and no row or scope item holds it yet (`m10/SCOPE.md` item 101 is
    the 2-lane long kernel's mask-free form, not S).

### What differs from the row text

- The row lists `-x`, `abs(x)`, "the decimal arithmetic and casts that fuse" and "a decimal
  column passed through" as values to measure. On master none of them fuses as a value (items 1
  and 11), and a column passed through is forwarded. So the forms this row can measure today are
  items 4 and 5, beside 596's item 3. The others are covered by the mechanism, not by a section,
  until their rows land (section 2.2, question 7.4).
- The row declines each form "that loses". This plan declines every decimal-reading value at two
  long lanes, ties and unmeasured later forms included, unless the owner exempts a measured win
  (2.2, question 7.2).
- The row's "same rule as row 596" is kept as the session setting, but the key moves from the
  `BoolOutput` root to the decimal operand (2.1), which also covers item 10.

## 1. Goal and non-goal

**Goal.** At two long lanes on x86 no value that reads a decimal runs fused slower than Spark's
row path. Each form that fuses today is measured on the EPYC 7763 at 128 bits, and every value that
reads a decimal declines there under row 503's setting, with its reason in `EXPLAIN`, unless a
measured win and the owner's word exempt it (2.2). A later form declines by the same mechanism
from the day it lands.

**Non-goal.** A cheaper lowering at two lanes, S or any other: question 7.3. Filters (row 503,
done). Any host other than x86 at two long lanes: aarch64 lowers two-lane masks, and
`twoLaneMasksFallBack` is false there. The 256-bit and 512-bit kernels, whose bytes do not move.

## 2. Design

### 2.1 The decline: keyed on the decimal operand, in the compiler's readers

Three candidates, with their costs:

- **(D1) Widen #1478's IR key.** In `hostRefusal`, refuse every long-driven root whose tree reads
  a `DecimalColumn` or `DecimalWord`, not only a `BoolOutput`. That is about ten lines, the
  `instanceof BoolOutput` test dropped. It misses item 10's leaf-free pair. A marker for that
  pair, "holds an unsigned long compare" (only `DecimalWords.compare` and 360's carry and borrow
  build `ULT` and `ULE`), catches its ordered compares and carries. It misses its `=`, `<>` and
  `<=>`, which are signed `EQ` compares, so a later pair form needs a new marker each time.
- **(D2) Key on the operand, at the decimal compiler's readers.** The builders that make a
  decimal operand out of something other than a constant decline, through one helper, where the
  session's admission says the host falls back: the long-lane column (`column`,
  `VarkaDecimalCompiler.java:1074`), the pair's column (`columnPair`, `:574`), and 360's
  `bigintPair` when it lands. This uses #1484's route, `DeclineSink` carrying the session flag.
  The check sits in the readers, not at the four call sites from outside (`compared` at `:500`,
  `nullTested` at `:737`, `compileDecimalIn` at `VarkaConditionCompiler.java:1051` and `output`
  at `:650`), so a later caller, such as the casts of rows 350, 364 and 366 from the cast
  compilers, meets it with no edit of its own. `output` needs no test of its own either: a
  `TIME` producer reaches no reader, and a 348b value that reads a column declines in the reader.
  So every decimal operand declines by construction, whatever its root:
  - a column on either representation, a rescale or choice over one, 360's `bigintPair`, or a
    form 348b or 359b builds over them;
  - but not a decimal conjunct of a filter: on #1484's branch only a projection's sink takes the
    session's admission (the `DeclineSink(childOutput, rangeSets, VarkaAdmission)` constructor,
    "a projection's sink"), and the predicate compile's sink keeps the flag off, so filters keep
    503's refusal and reason and nothing in the width audit moves.

  A later row that adds a new reader (a builder of a decimal from a non-decimal column, as 350's
  integral casts to a decimal will be) calls the same helper; 3 item 2's sweep holds the rows that
  add coverage. #1478's `readsDecimal` and its `BoolOutput` arm become redundant and are removed.
  About 40 lines of source, plus the tests of 3. Before it is built, 597b reads how a decline
  inside `nullTested`'s probe (`:737-745`) is treated, since a probe that swallows a reader's
  decline would admit a null test the reader refused.
- **(D3) A cheaper form.** S at two long lanes, behind an option as row 604 does for the
  remainder. That is a new emission mode and a new row (item 12, question 7.3), not this one.

**Recommended: D2** (question 7.1). It is the only key that covers item 10 and every row of item
11 without a marker per form. It follows #1484's precedent for the projection compile, and it
leaves `hostRefusal` as 503 wrote it.

**Cost.** If a later change hands the flag to the predicate sink too, the filters' reason moves,
and two consumers move with it: `sql/varka/width_audit.json`'s 59 decimal rows at 128 bits declined
with `TWO_LANE_CONDITION_REASON` (such as `(-d18) IS NOT NULL`), and
`VarkaWidthAuditShapes.predicateRow` (`:180`), which reads a host refusal by equality with that
reason. 597b keeps the predicate sink as #1484 leaves it, so neither moves. D2 lands after both
#1478 and #1484, since it uses #1478's plumbing of the admission
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
  win are the heavy forms whose row path is `BigDecimal` work: at 128 bits the row path arm takes
  1019 ms for `x + y` at `DECIMAL(20, 2)`, 2006 for `pmod` at `(20, 2)` and 2448 for the
  `DECIMAL(38, 10)` chain (the 128-bit file's lines 1359, 1311 and 243), against 126 for `x < y`
  at `(9, 2)` (line 723). **[I]** that any of them wins:
  the kernel cost 435.4 ns a row where the spike's loop cost 168.7 (item 8), so the spike does not
  predict the kernel.

This is the default the owner chose for the remainder (`VARKA-577.md` 7.4, answer (c): decline at
two long lanes, the cheaper form an opt-in row) and 503's "refused by the mechanism" for the
64-bit types it did not time.

### 2.3 What is deliberately unchanged

- Row 503's filter refusal (`TWO_LANE_CONDITION_REASON`).
- Row 104's checked `BIGINT` refusal.
- The `BIGINT` and `DOUBLE` boolean outputs (596 leaves them fused, and 597a's controls keep
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
   - `BIGINT` controls of the same shapes (`CAST(l < l2 AS INT)`, `IF(l < l2, l, l2)`) are
     admitted under both.

   After 360a, `CAST(l AS DECIMAL(20, 0)) < CAST(l2 AS DECIMAL(20, 0))` as a value joins, in the
   later of the two pull requests. Planted by hand, then reverted: `columnPair` not calling the
   helper fails the `(38, 10)` cases and no `(9, 2)` one, and, after 360a, `bigintPair` not
   calling it fails the leaf-free case alone.
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
5. **The width audit** at 128 bits: no row moves, since filters keep 503's reason (2.1). No
   construction is added: a `CAST(d9 < d9b AS INT)` construction would record the calls 596's
   already records.

No proof file moves. No IR node is added, so no registry suite moves.

## 4. The baseline, and predictions

**The sections** (597a, `VarkaDecimalBaselineBenchmark`). Each decimal shape runs at the four
precisions the comparisons already use, `DECIMAL(9, 2)`, `(18, 4)`, `(38, 10)` and `(20, 2)`, at
both null densities:

- `CAST(x < y AS INT)`, the `CondValue` form through the integral cast;
- `CAST(x < y AS DOUBLE)`, the `CondValue` form through the floating cast, whose narrowing and
  conversion differ;
- `IF(x < y, l, 0)`, the `IfElse` form;
- `nvl2(x, l, 0)`, a choice on a null test alone, where Spark's row path reads only the null bit.

That is 32 sections. Two `BIGINT` references at both densities, `CAST(l > 0 AS INT)` and
`IF(l > 0, l, 0)`, add 4, for 36. They read one column against a literal, since the class has one
`BIGINT` column, so they are not the decimal shapes' twins: they say what a long compare and a
blend cost at two lanes without a slot load. The dry run of 5 item 1 also plans, untimed, the
forms 0 item 5 reads but does not time: `CASE WHEN x < y THEN l ELSE 0 END`, `IF(x IS NULL, l, 0)`
and `IF(x < y, i, 0)` with `INT` branches (expected to decline on its lanes), and a shape that
plans unlike the code's reading corrects 0 before any section is timed. `l` is the class's existing `BIGINT` column, so no column is
added and every earlier section's values stay as committed. The classes are `WHOLE` at both widths
before 597b. After it, the decimal sections are `NONE` where the session refuses (the
`fusedFilter` term 596 uses), and the controls stay `WHOLE`.

**The runs.** `VarkaDecimalBaselineBenchmark` on the EPYC 7763 at 256 and 128 bits, the whole
class: row 599 is open, so a `sections` run cannot write its numbers. The 36 sections are spliced
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

1. At 128 bits, at `(9, 2)` and `(18, 4)`, `CAST(x < y AS INT)`, `CAST(x < y AS DOUBLE)` and `IF(x < y, l, 0)` run at 0.2X
   to 0.4X. They pay the same load as `x < y` as a value (0.2X and 0.3X, item 7), and a narrowing
   or a blend adds little against it.
2. At 128 bits, for the pair at `(38, 10)` and `(20, 2)`, those three run at 0.6X to 1.0X, as `x < y`
   as a value did (0.7X to 0.9X). The row path's `BigDecimal` compare hides part of the load.
3. At 128 bits `nvl2(x, l, 0)` loses at every precision, at 0.1X to 0.5X, and the pair's loss is
   larger than the comparisons'. The kernel loads and checks the slots under the null test
   (`VARKA-359.md` 0 item 5), while the row path reads only the null bit.
4. At 128 bits the `BIGINT` references run at 0.8X to 2X. Their compare and blend are the
   fallback on the EPYC 7763 too (`long.LT` at 92 bytes a row, item 8), and the only evidence for a
   win is the laptop's (503's blends at 1.3X to 5.6X). Either way they do not gate 597b, which
   rests on the decimal sections' own readings: a reference below 1.0X says `BIGINT` value forms
   lose at two lanes as well, a finding for a new row under 503's record, not a reason to fuse
   the decimals.
5. At 256 bits every decimal section runs at 3X or more (`x < y` as a value: 4.5X to 5.0X on the
   long lane, 21.3X to 31.6X for the pair).
6. After 597b, at 128 bits, every decimal section ties the row path (0.9X to 1.1X), both arms
   being the row path, and the `BIGINT` references do not move.

**The decision rule.** Under 2.2 every decimal form declines at two long lanes. The readings
decide only which forms go to the owner as exemption candidates: a decimal section at 1.3X or more
by minimums in two runs. Any ratio within 1.3x of 1.0 is re-run once and read by minimums before
it is written down (`sql/varka/AGENTS.md`, "Measurements, not adjectives"); a form between 1.0X and
1.3X is a tie and declines with the rest, its reading recorded. Under predictions 1 to 3 no form is
a candidate.

## 5. Order, files and size

1. **597a, the baseline addition**, its own pull request, about 200 to 300 lines:
   - the 36 sections of 4 with their class terms;
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
| `VarkaDecimalCompiler.java` | D2's helper, called by `column`, `columnPair` (and `bigintPair`), its reason |
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

*Answered by the owner on 11 November 2026: 1, the decline lives in the decimal compiler's readers; 2 yes, every decimal-reading value declines by default at two long lanes and a form is lifted only on a measured win and the owner's word; 3 yes, the scalar-loop form is a scope item beside `m10/SCOPE.md` item 101; 4 yes, the done-when covers the forms that fuse when 597b merges.*

1. **The mechanism.** (a) D2: decline in the decimal compiler's readers through the session flag on
   `DeclineSink`, after #1478 and #1484, removing 596's `BoolOutput` arm. (b) D1: widen #1478's IR
   key to every root, with an unsigned-compare marker for the leaf-free pair. *Recommended: (a)*.
   It covers every decimal operand, row 360's `bigintPair` and every later row's form by
   construction. D1 needs a marker per new leaf-free form and misses the pair's equality.
2. **Decline by default, today's forms and later ones.** (a) Every decimal operand declines at
   two long lanes by default, a tie or an unmeasured later form included. A later row lifts it for a form only with a measured win, a refusal-off run at 128
   bits on the EPYC 7763 committed as a side record, and the owner's word. (b) Each later row
   measures its own forms and declines its losers. *Recommended: (a)*. Under (b) the first form a
   row forgets fuses and loses, as 359a's did, and (a) is the default already chosen for the
   remainder (question 7.4 of `VARKA-577.md`, answer (c)).
3. **S, the cheaper form.** (a) No milestone 8 row. Record S as a scope item beside `m10/SCOPE.md`
   item 101, with the spike's 128-bit numbers (item 8) and the owner's answer (a) to
   `VARKA-359.md` question 3. (b) Open a milestone 8 row now, S behind an option at two long lanes,
   as row 604 is for the remainder. *Recommended: (a)*. Milestone 8's goal is the new types, and S
   is a new emission mode whose only win is where an x86 JVM's long species has two lanes (an SSE
   or AVX-only machine, `m10/SCOPE.md` item 101, or `-XX:MaxVectorSize=16`), which the decline
   already serves at the row path's speed.
4. **The done-when's scope.** The row's text asks for every decimal-reading value to be measured,
   including forms that do not fuse as values yet (0, "What differs"). (a) 597 is done with the
   forms that fuse at 597b's merge measured and the mechanism in place. Later forms are held by the
   default of question 2 and 3 item 2's sweep. (b) 597 stays open until 348b, 359b, 360d, 364 and
   366 land and each form has a section. *Recommended: (a)*. Under (b) the row waits on five other
   rows for forms that already decline by construction.

## 8. Done when

- **Measured.** A form is an IR shape (`CondValue`, `IfElse`, `BoolOutput`), so `CASE WHEN` and
  `IF(x IS NULL, ...)` share the timed `IfElse` sections. Each decimal-reading value form that fuses on master when 597b merges has a section
  in `VarkaDecimalBaselineBenchmark`, measured on the EPYC 7763 at 128 and 256 bits, beside the
  `BIGINT` references. These are 596's `BOOLEAN` output, the two casts of 0 item 4 and the choices
  of 0 item 5 that 597a's dry run finds fusing, at the long lane and the pair, at both null
  densities. The sections are spliced into both runner files with provenance naming the
  runs.
- **Declined.** Every decimal-reading value declines at two long lanes on x86 under row 503's
  setting, with its reason in `EXPLAIN`, and stays fused with the setting off, except a form the
  owner exempts on a measured win (2.2).
- **By construction.** The decline covers every decimal operand, row 360's leaf-free pair among
  them once 360a lands, held by 3's admission test and sweep.
- **Rerun.** After it, the 128-bit rerun has no decimal section below 1.0X by the decision rule of
  4, and the `BIGINT` references are read and recorded.
- **Unchanged elsewhere.** Nothing moves at 256 or 512 bits or on aarch64, and
  neither `emitted_bytes.json` nor `width_audit.json` moves.
- **Scored.** 4's predictions are scored in 10, and the answers to section 7 are recorded.

Or, under question 4's answer (b), the same for every form of 0 item 11 as its row lands.

## 9. Open points for the lead

1. **Merge order.** #1478, then #1484, then 597b. 597a needs only #1478. #1478's row-596 edit and
   this plan's row-597 edit are adjacent lines of `PLAN.md`, so the second to merge resolves a
   one-line conflict as master plus its own row.
2. **Row 599.** If it lands before 597a's runs, the dispatches take `sections` with the 36
   titles instead of running the whole class.

## 10. Outcome

Nothing built yet.

## 11. What is left, as one part a Sonnet-class agent can build (28 November 2026)

*New on 28 November 2026. Code facts were read on master `6dafb8bd2f6`, with no JVM. This section
stands alone: a builder needs it, the `AGENTS.md` files and `sql/varka/ADDING_AN_EXPRESSION.md`.
Where it differs from sections 0 to 9 it wins, and 11.9 lists each difference. Short names:
`C` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen`,
`CT` is the same path under `src/test/java`, `CS` under `src/test/scala`,
`DC` is `C/VarkaDecimalCompiler.java`, `KP` is `C/varka/VarkaKernelPlan.java`,
`BENCH` is `sql/core/src/test/java/org/apache/spark/sql/execution/benchmark`, and `R128` is
`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`.*

### 11.1 What row 597 is on this day

The owner split the row on 28 November 2026 (the row's note in `PLAN.md`). Part 597a, the 36
baseline sections of section 4, stays in `m10/SCOPE.md` item 140 and is not built here. Part 597b,
the decline, gates the close of milestone 8 under item 3 of `PLAN.md` 1.1 (`PLAN.md` 12.5).

**The term.** A "two-lane host" is an x86 JVM whose preferred 64-bit vector species has two lanes
or fewer: an x86 CPU without AVX2, or any x86 JVM started with `-XX:MaxVectorSize=16`. There C2
lowers no vector mask and no two-vector rearrange, so those calls run the Vector API's Java
fallback, which allocates. Arm is never a two-lane host in this sense: its back end lowers the
same calls at 128 bits, so nothing declines there.

**Why the row came back.** Three facts, each with its command, run from the repository root:

| fact | command | prints |
|---|---|---|
| every 64-bit decimal value is a projection output since #1628 | `git log --oneline -1 59924639192` | `[VARKA-348] Fuse every 64-bit decimal value as a projection output (#1628)` |
| the 128-bit runner file is from #1797 | `git log --oneline -1 9e7b1612402` | `[VARKA-362][VARKA-363] Refresh the decimal baseline's runner results at both widths (#1797)` |
| twelve fused decimal sections lose there | `python3 two_lane_rule.py R128 \| tail -1` (the script is 11.4's) | `RULE: 180 sections, 12 fused decimal readers: 12 LOSER, 0 TIE, 0 WINNER` |

**What exists.** Three refusals at the roots, in `KP.hostRefusal` (rows 503, 596 and 359), and
three declines in compiler arms (rows 104, 612 and 350). All read one session setting:

| name | where it sits | what it stops on a two-lane host |
|---|---|---|
| `TWO_LANE_CONDITION_REASON` | root, `KP.hostRefusal` | a filter's condition in a kernel driven at a 64-bit lane |
| `TWO_LANE_DECIMAL_VALUE_REASON` | root, `KP.hostRefusal` with `readsDecimal` | a `BOOLEAN` output whose condition reads a decimal column |
| `TWO_LANE_DECIMAL_PAIR_OUTPUT_REASON` | root, `KP.hostRefusal` | a decimal output wider than 18 digits (its store) |
| `TWO_LANE_CHECK_REASON` | compiler, `VarkaNodeCompiler` and `VarkaIntervalCompiler.twoLaneMeasured` | a checked `BIGINT` or day-time interval operation |
| `VarkaDecimalCastCompiler.TWO_LANE_REASON` | compiler, three arms of the cast compiler | a decimal cast to `BIGINT`, to `BOOLEAN` and between decimals; 597b removes it (11.5 step 3b) |

Not on master on this day, and named by the plans of rows 360 to 363 written the same day:
`VarkaDecimalCompiler.TWO_LANE_WIDE_REASON`, tested first in each new wide arm (the rescales past
a long, the checked sums, the products and the cut scales). *[Fix round, 28 November 2026.]* The
owner answered 11.7 question 1 "remove": 597b removes both arm-level reasons, whichever of their
uses are on master when it is built, and 11.5 step 3b says what a chain part built after 597b
does instead.

**How the host is told.** One chain, and 597b adds nothing to it:

1. `VarkaHostIsa.host()` reads `os.arch`; `Reading.x86()` is true for `amd64` and `x86_64`.
2. `KP.twoLaneMasksFallBack(Reading host, int preferredLongLanes)` returns
   `host.x86() && preferredLongLanes <= 2`. Arm answers false before the lane count is read.
3. `VarkaSessionEmit.bind` (sql/core) takes the setting
   `spark.sql.codegen.varka.declineTwoLaneConditions` where the session sets it, else step 2's
   answer, into `VarkaSessionEmit.declineTwoLaneConditions`.
4. `VarkaSessionEmit.admission()` hands it to the compiler as
   `VarkaAdmission.declineTwoLaneConditions`.
5. A projection's `DeclineSink` (the constructor that takes a `VarkaAdmission`) exposes it as
   `sink.declinesTwoLaneChecks()`. A predicate's sink is built from the families alone
   (`VarkaExpressionCompiler.predicateOnce`), so its flag is always false: a filter never meets a
   compiler-arm decline and keeps `TWO_LANE_CONDITION_REASON`.

    grep -c 'return host.x86() && preferredLongLanes <= 2;' KP                    -> 1
    grep -c 'new DeclineSink(childOutput, options, admission.familiesOff())' \
        C/VarkaExpressionCompiler.java                                            -> 1
    grep -c 'new DeclineSink(childOutput, options, admission)' \
        C/VarkaExpressionCompiler.java                                            -> 2

### 11.2 The decision rule, as numbers

The numbers are the best times of `R128` (vecbricks/varka run 46057448792, commit `6d1f14ae33a`,
AMD EPYC 7763, `-XX:MaxVectorSize=16`), row path over Varka arm, as `two_lane_rule.py` prints
them. Every section the file's header lists as fused that reads a decimal column:

| section of `VarkaDecimalBaselineBenchmark` | kind | no nulls, ms | ratio | one in ten null, ms | ratio |
|---|---|---|---|---|---|
| `DECIMAL(9, 2) cast to DECIMAL(18, 4)` | rescaling cast | 116 / 401 | 0.29 | 116 / 447 | 0.26 |
| `DECIMAL(9, 2) choice` | choice | 139 / 637 | 0.22 | 145 / 788 | 0.18 |
| `DECIMAL(18, 4) choice` | choice | 144 / 639 | 0.23 | 143 / 784 | 0.18 |
| `DECIMAL(9, 2) x + y as a value` | sum | 168 / 588 | 0.29 | 163 / 700 | 0.23 |
| `DECIMAL(9, 2) x - w as a value, w at another scale` | difference over a rescale | 234 / 587 | 0.40 | 212 / 700 | 0.30 |
| `DECIMAL(9, 2) x8 * y as a value, x8 a DECIMAL(8, 2)` | product | 221 / 592 | 0.37 | 205 / 707 | 0.29 |

The file's four other fused sections read no decimal column and win: `time_to_seconds over
TIME(6)` at 3.19 and 2.92 (731 / 229 and 652 / 223 ms) and `extract(SECOND) over TIME(6)` at 1.28
and 1.16 (418 / 327 and 389 / 336 ms). They write a decimal through the same store, so the store
is not what loses. The load is: section 0 item 8, and the skill
`sql/varka/skills/vector-api-and-width.md`, "At two long lanes a decimal read loses before its
compare does". Row 596's reading before its refusal had `x < y` as a value at 0.2X and 0.3X on the
long lane and 0.7X to 0.9X for the pair (section 0 item 7).

**The rule.** With `ratio` as above, and the house's band of 1.3 (`sql/varka/AGENTS.md`,
"Measurements, not adjectives"):

1. **A kind with a section declines where any of its sections is below 1.0.** All four measured
   kinds are: the highest of the twelve ratios is 0.40.
2. **A kind with no section declines too.** This is the owner's answer to question 2 of section 7
   (11 November 2026): every decimal operand declines by default at two long lanes, and a form is
   lifted only by a measured win and the owner's word. A win is a ratio of 1.3 or more by best
   times in two runs on the EPYC 7763 at 128 bits. The kinds with no section, all of which pay the
   same load: `-x`, `abs(x)`, `coalesce`, `greatest`, `least`, a decimal `IN` or null test written
   as a value, `hash` and `xxhash64` over a decimal, `CAST(x < y AS INT)`, `IF(x < y, l, 0)`,
   `nvl2(x, l, 0)`, and each of these over the lane pair.
3. **A value that reads no decimal column and builds no decimal pair is not declined by this
   row.** The `TIME` producers, the `BIGINT` and `DOUBLE` values, a constant.

So the rule today has no exception, and 597b builds no exemption mechanism (2.2).

### 11.3 The design

**The key is the decimal operand, in the decimal compiler's readers** (the owner's answer to
question 1 of section 7). Every decimal leaf of the IR is built in `DC`, at three sites:

    grep -n 'new DecimalColumn(\|new DecimalWord(' DC
        -> four lines at three sites: 837 and 838 (columnPair), 1116 (valid), 1537 (leaf)

`leaf` is called by `column` and by `valid`. `valid` runs only after its caller compiled or probed
the value (`nullTested` probes through `value` or `wideProbe`, which reach `column` and
`columnPair`). So two guarded readers cover every decimal read of a projection:

- `column(BoundReference, DecimalType, VarkaSlotTable, DeclineSink)`: a decimal column on the
  long lane, 18 digits or fewer;
- `columnPair(BoundReference, DecimalType, VarkaSlotTable, DeclineSink)`: a wider column as its
  two words.

A third exists once row 360's `BIGINT` cast is on master: `bigintPair`, the pair of a long and
its sign with no decimal leaf (section 0 item 10). It declines by the same test, as the owner's
answer says ("and 360's `bigintPair` when it lands"): as an output it is refused by its store
already, and under a value root its three compares and two mask joins are the fallback. The
owner confirmed it on 28 November 2026 (11.7 question 2). It reaches master by the lead's merge
of `varka-360a-bigint-cast` (step 0 of the chain's order, `VARKA-360.md` 11.2), so who adds its
call depends on the order: 11.5 step 2 has the rule.

*[Fix round, 28 November 2026.]* **A fourth source: a cast into a decimal from a source that is
not a decimal.** With the arm-level reasons removed (11.7 question 1, answered "remove"), a
decimal value that reads no decimal column could still be built from an integral, boolean or
floating source (rows 350a1b and 346c, `DCC.intoDecimal`). Today `DCC.TWO_LANE_REASON` at the
head of `intoDecimal` declines it; 597b keeps that decline by calling the same helper there for
a source that is not a decimal. So every source of a decimal value declines through one method,
`declinesAtTwoLanes`, which is package-private for that call: `column`, `columnPair`,
`bigintPair`, and the head of `intoDecimal`. A decimal literal is the one other leaf; an
expression of literals alone is folded by Spark before Varka sees it.

**What 597b declines that the other parts do not.** "Root" means `KP.hostRefusal`; "arm" means a
compiler arm's own test.

| shape, as a projection entry | on a two-lane host today | after 597b |
|---|---|---|
| a 64-bit decimal value over a column: `x + y`, `x - w`, `x8 * y`, `-x`, `abs(x)`, `IF`, `CASE`, `coalesce`, `greatest`, `least`, a rescaling cast | fuses, 0.18 to 0.40 of the row path | declines, `TWO_LANE_READ_REASON` |
| a non-decimal value over a 64-bit decimal condition: `CAST(x < y AS INT)`, `CAST(x < y AS DOUBLE)`, `IF(x < y, l, 0)`, `nvl2(x, l, 0)` | fuses, unmeasured | declines, the same |
| the same over a wide condition: `CAST(x38 < y38 AS INT)`, `IF(x20 < y20, l, 0)` | fuses, unmeasured | declines, the same |
| class U at one scale under a value root, `CAST(x20 + y20 < z20 AS INT)` (row 360's part 360a, which `VARKA-360.md` 11 leaves to this row) | fuses, unmeasured | declines, the same |
| `hash(x)`, `xxhash64(x)` over a decimal column | fuses, unmeasured | declines, the same |
| a `BOOLEAN` output over a decimal column, `x < y AS c` | root, `TWO_LANE_DECIMAL_VALUE_REASON` | arm, `TWO_LANE_READ_REASON`; the root's arm is removed |
| a decimal output wider than 18 digits over a column, `-x38` | root, `TWO_LANE_DECIMAL_PAIR_OUTPUT_REASON` | arm, `TWO_LANE_READ_REASON`, which fires first; the root's arm stays for a pair that reads no column |
| a decimal cast to `BIGINT`, to `BOOLEAN`, between decimals | arm, `VarkaDecimalCastCompiler.TWO_LANE_REASON` | reader, `TWO_LANE_READ_REASON`; the arm's test and its constant are removed (step 3b) |
| a cast into a decimal from an integral, boolean or floating source (350a1b, 346c) | arm, `VarkaDecimalCastCompiler.TWO_LANE_REASON` | the head of `intoDecimal`, `TWO_LANE_READ_REASON` (step 3b) |
| a new wide arm of rows 360 to 363 | arm, `TWO_LANE_WIDE_REASON` | reader, `TWO_LANE_READ_REASON`; the arm's test and the constant are removed (step 3b) |
| a filter's decimal conjunct | root, `TWO_LANE_CONDITION_REASON` | unchanged: a predicate's sink has the flag off |

**The reason text**, one constant in `DC`:

    static final String TWO_LANE_READ_REASON = "a DECIMAL operand of a projection entry, on an "
        + "x86 host whose 64-bit species have two lanes or fewer: C2 lowers no mask or "
        + "two-vector rearrange there, so the decimal slots' load and check, and a lane pair's "
        + "compares, would run the Vector API's Java fallback, slower than the row engine "
        + "(VARKA-597)";

**Lifting it later for one shape is a small change, by construction.** The test is one
package-private method, `declinesAtTwoLanes(Expression at, DeclineSink sink)`, with one call in
each reader and one in `DCC.intoDecimal`.

- *One load form* (what row 698 of `m9/PLAN.md` may show: a two-word load with no mask that wins):
  delete the call in `columnPair`, remove the `InterleaveDecimal` arm of `KP.hostRefusal` if the
  store wins too, and move the pair lines of the test of 11.5 step 5 from "declines" to "fuses".
  About ten lines.
- *One expression shape over a load that still loses* (a heavy shape whose row path is
  `BigDecimal` work): the arm that owns the shape compiles its operands inside a bracket on the
  sink, as `DeclineSink.enterDecimal` and `exitDecimal` are a bracket: a counter
  `twoLaneLifted`, and `declinesAtTwoLanes` returns false while it is above zero. About twenty
  lines, with the shape's 128-bit section as evidence. Not built here: no shape has a win (11.2).

**What does not change.** No IR node, no emitted byte, no proof file. The predicate compile. The
answers: a declined entry is computed by the row engine. Every compile under
`VarkaAdmission.NO_HOST_REFUSAL`, which is every committed oracle: `coverage.json`,
`emitted_bytes.json`, `casts_fused.tsv`, `census.tsv`.

### 11.4 The census and the coverage table: a decline at one width un-counts nothing

- **The census counts under no host refusal.** `dev/varka_census.py` reads `coverage.json`, which
  `VarkaCoverageSuite` renders by compiling each row under `VarkaAdmission.NO_HOST_REFUSAL`
  ("so its answers are the same on every machine", the javadoc of that constant). A pair fused on
  a four-lane host stays counted. The oracle of the part: after the regeneration,
  `git diff --stat` shows no change in `coverage.json`, `emitted_bytes.json`, `census.tsv` or
  `casts_fused.tsv`.
- **The width-dependent record is its own file.**
  `sql/varka/plans/m8/results/VARKA-612-refused.txt` lists the coverage table's value rows a
  two-lane host refuses as projection entries; its header on master reads
  `# 26 of 547 value rows refused; family, row, reason:`. 597b adds every value row that reads a
  decimal column and re-words the rows row 596 put there. No row leaves the list. The count after
  the part is read from the regenerated header and recorded in section 10.
- **`width_audit.json`** is one CPU's record (`host.cpu` in the file). At 128 bits its projection
  rows compile under the host's admission (`VarkaWidthAuditShapes.hostAdmission`), so on an x86
  host the decimal value rows move there from a shape with refused calls to a declined row with
  the new reason. At 256 and 512 bits nothing moves. The regeneration script says where the file
  is owed on another host.
- **`docs/sql-varka.md`**: the setting's text in the generated configuration block moves with
  `SQLConf.scala`.

### 11.5 The build sheet

**Title:** `[VARKA-597] Leave decimal-reading values to Spark at two long lanes`.
**Branch:** `varka-597b-two-lane-values`, from master. **Builder:** Sonnet-class.
**Waits on:** nothing. *[Fix round, 28 November 2026, finding 3d of review 1.]* `PLAN.md`'s
row note says "built after the first parts of rows 360 to 363"; 597b needs none of them to
compile, and B0 reads which of them are on master. **Order:** 597b may land before or after
the lead's merge of `varka-360a-bigint-cast` and before or after any part of rows 350 and 360
to 363; steps 2, 3b and 5 say what each order adds, and the part that lands second adds it.

**Landing the plan.** *[Fix round, finding 2.]* The new text of this file (sections 11 and 12,
over 500 lines more than master's 449) goes to master first, in its own plan-only pull request
titled "Plan: re-plan row 597's second half for a Sonnet-class builder"; plan-only pull requests
are exempt from the 1000-line cap, and the lead opens it. This part's branch starts from a
master that has it, and its pull request adds only the record of done-when item 5 (section 10).

**B0, the premises.** Each must print what is stated, or the builder stops and reports.

    grep -c 'private static Optional<Scaled> column(' DC                          -> 1
    grep -c 'private static Optional<Pair> columnPair(' DC                        -> 1
    grep -c 'TWO_LANE_READ_REASON' DC                                             -> 0
    grep -c 'readsDecimal' KP                                                     -> 3
    grep -c 'TWO_LANE_DECIMAL_VALUE_REASON' KP                                    -> 2
    grep -rl 'TWO_LANE_DECIMAL_VALUE_REASON' sql/catalyst/src sql/core/src | wc -l -> 4
    grep -c 'bigintPair(' DC          -> 0 (skip step 2's last line) or more (do it)
    grep -c 'Fusion.WHOLE' BENCH/VarkaDecimalBaselineBenchmark.java               -> 9
    grep -c 'TWO_LANE_REASON' C/VarkaDecimalCastCompiler.java                     -> 4, or 5 (step 3b)
    grep -c 'TWO_LANE_WIDE_REASON' DC                                             -> 0 or more (step 3b)
    grep -c 'static boolean sourceCanOverflow' C/VarkaDecimalCastCompiler.java    -> 0 or 1 (step 5)

The `Fusion.WHOLE` count is master `6dafb8bd2f6`'s; a part of rows 360 to 363 merged since may
have raised it, which changes nothing below. `TWO_LANE_REASON` prints 4 on `6dafb8bd2f6` (the
constant and the arms of `toIntegral`, `test` and `intoDecimal`) and 5 once row 350's part 350b1
merged (`toFloating`).

**Step 1, the helper and the reason, in `DC`**, beside `isDecimal`:

    /**
     * Whether a decimal operand declines here: on an x86 host whose 64-bit species have two
     * lanes or fewer every decimal is loaded by two two-vector rearranges and a check of each
     * slot, none of which C2 lowers there, and each fused decimal value of the 128-bit baseline
     * ran at 0.18 to 0.40 of the row path (VARKA-597.md 11.2). Only a projection's sink carries
     * the setting: a filter's condition is refused at its root (VarkaKernelPlan.hostRefusal).
     */
    static boolean declinesAtTwoLanes(Expression at, DeclineSink sink) {
      if (!sink.declinesTwoLaneChecks()) {
        return false;
      }
      sink.decline(TWO_LANE_READ_REASON, at);
      return true;
    }

and the constant of 11.3. `sink.decline(String, Expression)` notes the reason and returns an empty
`Optional`; its result is dropped here on purpose. The method is package-private, not private:
`VarkaDecimalCastCompiler.intoDecimal` calls it too (step 3b).

**Step 2, the readers.** First statement of `column`:

    if (declinesAtTwoLanes(br, sink)) {
      return Optional.empty();
    }

The same first statement in `columnPair`, and, where B0 found it, in `bigintPair` with its cast
expression as `at`. Nothing else in `DC` changes: `valid` is reached only behind a compiled or
probed value.

*[Fix round, 28 November 2026, finding 3c of review 1.]* **Who adds `bigintPair`'s call, by
order.** `bigintPair` reaches master with the lead's merge of `varka-360a-bigint-cast`.

- If B0 found it (`bigintPair(` prints 1 or more): this part adds, as `bigintPair`'s first
  statement, `if (declinesAtTwoLanes(c, sink)) { return Optional.empty(); }`, and step 5's
  `pair` list gains `Cast(l, DecimalType(20, 0))`.
- If B0 printed 0: this part adds neither. Whoever brings `bigintPair` to a master that has
  597b adds both: the lead, in the merge of master into `varka-360a-bigint-cast` before that
  pull request merges (`VARKA-360.md` 11.7 says so); and, should that merge have landed without
  them, part 360b2, whose B0 checks it.

The check either way: on a master with both, `grep -c 'declinesAtTwoLanes(c, sink)' DC` prints
1 (the other readers pass `br`).

**Step 3, the root's arm goes** (the owner's answer to question 1: "removing 596's `BoolOutput`
arm"). In `KP`: delete the `BoolOutput` branch of `hostRefusal`, the method `readsDecimal`, the
constant `TWO_LANE_DECIMAL_VALUE_REASON`, and the `HashSet` import if nothing else uses it. Keep
the `Cond` branch and the `InterleaveDecimal` branch. In `hostRefusal`'s javadoc replace the
paragraph that starts "A projection's {@code BOOLEAN} output" by:

    A projection entry that reads a decimal column never reaches this method on such a host:
    the decimal compiler's readers decline it first (VarkaDecimalCompiler.declinesAtTwoLanes,
    VARKA-597), a BOOLEAN output over a decimal column among them, which row 596 refused here.
    A decimal output of the lane pair (InterleaveDecimal) is still refused here, for the pair
    that reads no column: its store is two two-vector rearranges.

Update the three other texts that describe row 596's refusal as the root's: the `Kind` javadoc of
`TWO_LANE_CONDITION` in `KP`, the class javadoc of `C/varka/VarkaAdmission.java`, and the doc of
`VARKA_DECLINE_TWO_LANE_CONDITIONS` in `SQLConf.scala`, whose sentence "A projection's BOOLEAN
entry over a DECIMAL column, such as x < y AS c, is refused there too, its decimal load being
slower than the row engine at two lanes." becomes "A projection entry that reads a DECIMAL
column, such as x + y AS c or x < y AS c, is left to the row engine there too, its decimal load
being slower than the row engine at two lanes."

**Step 3b, the arm-level two-lane reasons go.** *[Fix round, 28 November 2026: the owner's
answer (b) to 11.7 question 1.]* The readers of step 2 decline every decimal operand before an
arm's own test could matter, so the arm tests and their two constants are removed here.

1. `C/VarkaDecimalCastCompiler.java` (`DCC`): delete the constant `TWO_LANE_REASON` with its
   javadoc (master's lines 95 to 107), and every statement

        if (sink.declinesTwoLaneChecks()) {
          return sink.decline(TWO_LANE_REASON, c);
        }

   (three on `6dafb8bd2f6`: in `toIntegral`, `test` and `intoDecimal`; a fourth in
   `toFloating` once 350b1 merged). `grep -c 'TWO_LANE_REASON' DCC` then prints 0.
2. In `DCC.intoDecimal`, where its statement was, write:

        // A source that is not a decimal reads no decimal column, so the readers' two-lane test
        // is made here: an integral or floating source's guard and a boolean's select are
        // long-lane masks too (VARKA-597).
        if (!(c.child().dataType() instanceof DecimalType)
            && VarkaDecimalCompiler.declinesAtTwoLanes(c, sink)) {
          return Optional.empty();
        }

   On `6dafb8bd2f6` no source but a decimal reaches `intoDecimal` (`DC.cast` declines the others
   with "to a decimal is row 350's"), so the line is dead until 350a1b or 346c; it is written now
   so that neither of them carries a two-lane test of its own. A decimal source reads its column
   through `value`, whose readers decline.
3. `DC`, only if B0's `TWO_LANE_WIDE_REASON` line printed 1 or more (a part of rows 360 to 363
   merged first): list the uses with `grep -rn 'TWO_LANE_WIDE_REASON' sql/catalyst/src/main`.
   Delete the constant with its javadoc, and each statement of the form

        if (sink.declinesTwoLaneChecks()) {
          return sink.decline(TWO_LANE_WIDE_REASON, <the arm's expression>);
        }

   whole, in whichever file holds it. A statement that does more than this decline is not
   deleted: the builder stops and reports it (the chain plans write none). Then
   `grep -rc 'TWO_LANE_WIDE_REASON' sql/catalyst/src/main | grep -v ':0$'` prints nothing, and
   `grep -c 'declinesTwoLaneChecks' DC` prints 1 (the helper of step 1).
4. **The tests that move.** List them:

        grep -rln 'TWO_LANE_REASON\|TWO_LANE_WIDE_REASON\|a decimal cast on an x86 host\|a decimal rescale, checked sum or product past' \
            sql/catalyst/src/test sql/core/src/test

   On `6dafb8bd2f6` it prints one file, `CS/VarkaExpressionCompilerSuite.scala`, for the test
   "a decimal cast's value declines on a two-lane host, and fuses elsewhere" (master's line
   3599). In every file it prints: an assertion that expects
   `VarkaDecimalCastCompiler.TWO_LANE_REASON` or `VarkaDecimalCompiler.TWO_LANE_WIDE_REASON`
   expects `VarkaDecimalCompiler.TWO_LANE_READ_REASON`, with the same expression and admission;
   one in `sql/core` that compares a reason's text compares `startsWith(READ_REASON_START)`
   (step 4's constant). A test keeps its name. If such an assertion then fails because the entry
   fuses on the two-lane admission, the builder prints the expression and stops: it is a source
   of a decimal value that 11.3's four do not cover.
5. **What a part of rows 350 or 360 to 363 built after this one does instead.** It reads
   `grep -c 'TWO_LANE_READ_REASON' C/VarkaDecimalCompiler.java`; `1` or more means 597b merged.
   Then it adds no `TWO_LANE_REASON`, no `TWO_LANE_WIDE_REASON` and no two-lane statement in an
   arm: its operands reach the readers, which decline first. In its B0 a line that counts
   `TWO_LANE_WIDE_REASON` or `TWO_LANE_REASON` is read as `0`, and one that counts
   `declinesTwoLaneChecks` in `DC` as `1`. Each of its tests that expects either removed reason
   expects `VarkaDecimalCompiler.TWO_LANE_READ_REASON`, under the same admission; the test
   counts its sheet states do not change. A part built before 597b merged adds them as its plan
   writes, and this step removes them. The plans point here: `VARKA-350.md` 11.3 decision 6,
   `VARKA-346.md` 11.3 decision 7, `VARKA-360.md` 11.7, `VARKA-361.md` 11.3 decision 5,
   `VARKA-362.md` and `VARKA-363.md` 11.3 decision 8.

The regenerated `sql/varka/width_audit.json` and `VARKA-612-refused.txt` then show the read
reason where they showed the cast's (`CAST(d9 AS BIGINT)` and its kin); step 9 regenerates them.

**Step 4, the tests that named the old reason.**

- `CT/varka/VarkaTwoLaneConditionSuite.java`, `decimalBooleanOutput`: the plan no longer refuses
  the IR root. Rename it `decimalBooleanOutputIsTheCompilersToDecline`, keep its four trees, and
  assert `admit(value, true).isEmpty()` and `admit(value, false).isEmpty()` for each; drop the
  `Kind` and reason assertions; keep the display name's sense ("a boolean output over a decimal
  column is no longer the plan's refusal: the decimal readers decline it in the compiler").
- `CS/VarkaExpressionCompilerSuite.scala`, test "a two-lane admission refuses a checked bigint and
  a decimal boolean in one projection": `VarkaKernelPlan.TWO_LANE_DECIMAL_VALUE_REASON` becomes
  `VarkaDecimalCompiler.TWO_LANE_READ_REASON`.
- `sql/core/src/test/java/org/apache/spark/sql/execution/VarkaTwoLaneDefault128Suite.java`,
  `decimalValuesDecline`: the same replacement (import `VarkaDecimalCompiler` is package-private
  to catalyst's `codegen` package, so compare with the text instead: declare
  `private static final String READ_REASON_START = "a DECIMAL operand of a projection entry"`
  and assert `d.reason().startsWith(READ_REASON_START)`).

**Step 5, the new compiler test**, in `CS/VarkaExpressionCompilerSuite.scala`, after the test "a
decimal cast's value declines on a two-lane host, and fuses elsewhere". It uses the suite's
`withLong` columns (`l`, `l2` among them) and its `out` helper, as that test does.

    test("a value that reads a decimal column declines on a two-lane host, and fuses elsewhere") {
      // VARKA-597. Catches: a reader that forgets the test (the pair's lines fail alone when
      // columnPair does), a decline that reaches a filter, and a host test other than "x86 at
      // two long lanes or fewer" (the Arm line).
      val p = AttributeReference("p", DecimalType(10, 2))()
      val p2 = AttributeReference("p2", DecimalType(10, 2))()
      val w = AttributeReference("w", DecimalType(38, 10))()
      val w2 = AttributeReference("w2", DecimalType(38, 10))()
      val columns = withLong ++ Seq(p, p2, w, w2)
      def reasons(e: Expression, admission: VarkaAdmission): Seq[String] =
        VarkaExpressionCompiler.declines(Seq(out(e)), columns, VarkaEmitOptions.DEFAULTS,
          admission).asScala.values.map(_.reason).toSeq
      val x86 = new VarkaHostIsa.Reading("amd64", "avx, avx2", null, 128)
      val arm = new VarkaHostIsa.Reading("aarch64", "fp, asimd", null, 128)
      def host(reading: VarkaHostIsa.Reading, lanes: Int): VarkaAdmission =
        new VarkaAdmission(java.util.Set.of(),
          VarkaKernelPlan.twoLaneMasksFallBack(reading, lanes))
      val longLane = Seq[Expression](Add(p, p2), Subtract(p, p2), UnaryMinus(p), Abs(p),
        If(LessThan(p, p2), p, p2), Cast(p, DecimalType(18, 4)), LessThan(p, p2),
        Cast(LessThan(p, p2), IntegerType), If(LessThan(p, p2), l, l2), IsNull(p),
        If(IsNotNull(p), l, l2))
      val pair = Seq[Expression](LessThan(w, w2), Cast(LessThan(w, w2), IntegerType),
        UnaryMinus(w), If(LessThan(w, w2), l, l2), IsNull(w))
      for (e <- longLane ++ pair) {
        withClue(e) {
          assert(reasons(e, host(x86, 2)) === Seq(VarkaDecimalCompiler.TWO_LANE_READ_REASON))
          assert(reasons(e, host(x86, 1)) === Seq(VarkaDecimalCompiler.TWO_LANE_READ_REASON))
          assert(reasons(e, host(x86, 4)).isEmpty)
          assert(reasons(e, host(arm, 2)).isEmpty)
          assert(reasons(e, VarkaAdmission.NO_HOST_REFUSAL).isEmpty)
        }
      }
      // No decimal is read: the long lane's own values fuse on every host.
      for (e <- Seq[Expression](Add(l, l2, EvalMode.LEGACY), If(LessThan(l, l2), l, l2),
          LessThan(l, l2), Cast(LessThan(l, l2), IntegerType))) {
        withClue(e)(assert(reasons(e, host(x86, 2)).isEmpty))
      }
      // A filter keeps the root's reason: a predicate's sink does not carry the setting.
      val filter = VarkaExpressionCompiler.explainAndCompilePredicate(LessThan(p, p2), columns,
        VarkaEmitOptions.DEFAULTS, host(x86, 2)).specs.asScala.toSeq
      assert(filter.nonEmpty && filter.forall(s =>
        !s.fused && s.decline.get.reason == VarkaKernelPlan.TWO_LANE_CONDITION_REASON))
    }

Four rules for this test, so that the builder computes and does not judge:

1. **A line that does not fuse under `NO_HOST_REFUSAL` is not this row's.** Run the test once
   before steps 1 to 3b are applied, with only the last three assertions of the loop. A line that
   fails there declines on every host for a reason of its own: remove that line, and record the
   expression and its reason in section 10. Section 0 marks `CAST(x < y AS INT)` and
   `IF(x < y, l, 0)` as read from the code and never run, so this can happen (11.8 has the
   commands that settle it before the build).
2. **The filter's lines are the suite's own way to classify a predicate**: the test "the
   two-lane host refuses a DOUBLE conjunct as it does a long one (VARKA-503)" in the same file
   calls `explainAndCompilePredicate(...).specs` the same way. The suite imports `VarkaAdmission`
   and `VarkaKernelPlan` from `codegen.varka` already; add `VarkaHostIsa` to that import.
3. **The planted fault**, by hand, reverted before the commit: delete the call in `columnPair`;
   the five `pair` lines must fail and no `longLane` line. Record the run in the pull request.
4. **Two lines that depend on the order** *[fix round, 28 November 2026]*. Where B0's
   `bigintPair(` line printed 1 or more, `pair` gains `Cast(l, DecimalType(20, 0))` (the planted
   fault then fails six `pair` lines). Where B0's `sourceCanOverflow` line printed 1 (row 350's
   part 350a1b merged), `longLane` gains `Cast(l, DecimalType(18, 2))`, which declines at the
   head of `intoDecimal` (step 3b). Where either printed 0, the part that brings the method adds
   the line later (`VARKA-350.md` 11.5, and step 2 above for `bigintPair`).

**Step 6, the session's test**, in
`sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaEvaluatorComponentsSuite.scala`,
inside the test "a session's two-lane default comes from the host reading its level comes from",
after its first loop:

    // VARKA-597: the same hosts, through the admission the compiler takes. On x86 at two long
    // lanes a decimal sum declines; at four lanes and on Arm at two it fuses.
    val p = AttributeReference("p", DecimalType(10, 2))()
    val sum = Seq[NamedExpression](Alias(Add(p, p), "c")())
    for ((host, lanes, refuses) <- Seq((x86, 2, true), (x86, 4, false), (arm, 2, false))) {
      val declines = VarkaExpressionCompiler.declines(sum, Seq(p), VarkaEmitOptions.DEFAULTS,
        VarkaSessionEmit.of(conf, host, lanes).admission())
      assert(declines.isEmpty === !refuses, s"${host.arch} at $lanes long lanes")
    }

*[Fix round, 28 November 2026, finding 6.]* The suite imports none of the names the snippet
uses beyond `VarkaEmitOptions` and `VarkaHostIsa`. Add, in the import block's order (java,
scala, third party, then `org.apache.spark` alphabetically, as `dev/lint-scala` checks):

    import org.apache.spark.sql.catalyst.expressions.{Add, Alias, AttributeReference, NamedExpression}
    import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler

after `import org.apache.spark.{SparkFunSuite, TaskContext}`, and
`import org.apache.spark.sql.types.DecimalType` after
`import org.apache.spark.sql.internal.SQLConf`. The first import is 98 columns in the file,
within the limit of 100. The snippet goes before the loop that sets
`VARKA_DECLINE_TWO_LANE_CONDITIONS`, so `conf`
holds no setting there.

**Step 7, the 128-bit suite**, `VarkaTwoLaneDefault128Suite`. Add one test after
`decimalValuesDecline`, with the same checks that test makes (answers equal to the session with
Varka off; the declined positions; a fused entry beside them; the task's compile declining the
same positions; `numVarkaBatches` above zero), over this query, without and with the `n` suffix:

    SELECT p + p2 AS a, CASE WHEN p < p2 THEN p ELSE p2 END AS b, CAST(p < p2 AS INT) AS c,
           IF(w < w2, l, l2) AS d, greatest(l, 5) AS e, least(l, l2) AS f, greatest(l, l2) AS g,
           CASE WHEN l < l2 THEN l ELSE l2 END AS h, i + i2 AS k
    FROM two_lane_default

*[Fix round, 28 November 2026: the query written once, finding 6.]* Positions 0 to 3 decline
with a reason that starts with `READ_REASON_START`; positions 4 to 8 are `FusedOutput`.
`greatest` and `least` stand where checked sums would decline by row 104's rule in the view's
ANSI session. Five fused entries against four declined keep the node under the rule that leaves
a residual-heavy projection to Spark (row 613), as the suite's `longLaneChecksDecline` explains.
**Stop rule:** if any of positions 4 to 8 declines, the builder prints `partial.declines()` (or
the test's own decline list) and stops: the node then falls to Spark under row 613's rule, and
it is the query that is wrong, not the code. Only a JVM settles whether `i + i2` (an ANSI int
sum) and the `CASE` over `l` fuse at two lanes in that session (11.8).

**Step 8, the baseline's classes**, in `BENCH/VarkaDecimalBaselineBenchmark.java`,
`sections(boolean declinesLongConditions)`. Five edits, each a class that no longer depends on
the lane alone:

| line of code today | becomes |
|---|---|
| `p.precision() == 9 ? Fusion.WHOLE : Fusion.NONE` (the cast to `castTo`) | `p.precision() == 9 ? fusedFilter : Fusion.NONE` |
| `Fusion choice = longLane ? Fusion.WHOLE : fusedFilter;` | `Fusion choice = fusedFilter;` and delete the now unused `longLane` |
| `" + " + y + " AS a FROM varka_decimal", Fusion.WHOLE` (x + y as a value) | `fusedFilter` |
| `" - " + w + " AS a FROM varka_decimal", Fusion.WHOLE` (x - w as a value) | `fusedFilter` |
| `x8 + " * " + y + " AS a FROM varka_decimal", Fusion.WHOLE` (x8 * y as a value) | `fusedFilter` |

If a part of row 350 merged first and turned a section to `addCasts`'s `fused` argument, keep
its argument and apply this table to the lines that still say `Fusion.WHOLE`. The two `TIME`
sections stay `Fusion.WHOLE`. Rewrite the three comments above those lines and the
class javadoc's sentences that say a value root "fuses whole in either session" to say: "a value
that reads a decimal column fuses whole where the session takes a 64-bit condition and is `NONE`
where it refuses one, the decimal readers declining there (VARKA-597)". A section added by rows
360 to 363 since `6dafb8bd2f6` that is `Fusion.WHOLE` and reads a decimal column gets the same
edit; the queries suite of step 9 names each one that needs it.

**Step 9, the commands, in order, and what each must print.** `<w>` is the worktree.

    build/sbt catalyst/Test/compile sql/Test/compile                  -> [success]
    grep -c 'TWO_LANE_REASON' C/VarkaDecimalCastCompiler.java         -> 0
    grep -rc 'TWO_LANE_WIDE_REASON' sql/catalyst/src | grep -v ':0$'   -> prints nothing
    grep -c 'declinesAtTwoLanes(' DC                                  -> 3, or 4 with bigintPair
    build/sbt 'catalyst/testOnly *VarkaExpressionCompilerSuite -- -z "two-lane"'
        -> Tests: succeeded 4 or more, failed 0
    build/sbt 'catalyst/testOnly *VarkaTwoLaneConditionSuite'         -> Passed: Total 9, Failed 0
    build/sbt 'sql/testOnly *VarkaEvaluatorComponentsSuite -- -z "two-lane default"'
        -> Tests: succeeded 1, failed 0
    build/sbt 'sql/testOnly *VarkaTwoLaneDefault128Suite'             -> Passed: Total 6, Failed 0
    build/sbt 'sql/testOnly *BaselineQueriesSuite *VarkaTwoLaneLong*Suite *VarkaBooleanOutput*Suite'
        -> failed 0; a failure names a section whose class must follow the setting (step 8)
    VARKA_COVERAGE_REGEN=true build/sbt 'catalyst/testOnly *VarkaCoverageSuite'
        -> rewrites VARKA-612-refused.txt; read its header's count
    dev/varka_regen_generated.sh --all                                -> varka regen: done
    git diff --stat -- sql/varka/coverage.json sql/varka/emitted_bytes.json \
        sql/varka/plans/m8/census.tsv sql/varka/plans/m8/casts_fused.tsv   -> prints nothing
    python3 dev/varka_msg_check.py size                               -> under 1000
    dev/varka_ops/pre-pr-slot.sh <w>                                  -> pre-pr: passed on <sha>

`VarkaTwoLaneConditionSuite` has nine tests on master (`grep -c '@Test'` prints 9) and
`VarkaTwoLaneDefault128Suite` five, six after step 7. On a host that is not x86 the 128-bit suite
checks the default and skips its queries.

**Step 10, the measurement.** After the pull request is open the builder dispatches (the owner's
answer of 28 November 2026 to question 3 of 11.7; dispatching is approved for any open pull
request), on the pull request's head:

    gh workflow run benchmark.yml -R vecbricks/varka --ref master -f pr=<the PR> \
      -f class=VarkaDecimalBaselineBenchmark -f jdk=25 -f generate-tpcds=false \
      -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-XX:MaxVectorSize=16

The builder splices the twelve sections of 11.2 into `R128` with

    python3 dev/varka_bench_sections.py splice R128 <NEW> --sections "<the twelve titles>" --out R128

where `<NEW>` is the results file the run uploads for this class at 128 bits (the lead
downloads it and gives its path), and the twelve titles are each first-column entry of 11.2's
table followed by `, no nulls` and by `, one row in ten null`, as `R128` names its sections
(`DECIMAL(9, 2) choice, no nulls`, ...). The script's usage, its line 21:
`splice OLD NEW --sections "title one,title two" [--out FILE]`; a misspelt title is an error
that lists the titles NEW has. The builder adds the run to the provenance file beside `R128`,
commits both, and runs the rule:

    python3 two_lane_rule.py R128 | tail -1
        -> RULE: <n> sections, 0 fused decimal readers: 0 LOSER, 0 TIE, 0 WINNER

`two_lane_rule.py` is committed by this part as
`sql/varka/plans/m8/results/VARKA-597/two_lane_rule.py`, with the Apache header of a neighbouring
Python file under `dev/`; its text is the 50 lines kept beside this plan at
`/home/max/varka-ops/m8-row-plans/scratch-597/two_lane_rule.py`. It parses the header lines that
end "fuses whole on the varka on arm" and the two rows of each section.

**Done when.**

1. The commands of step 9 print what is stated, the Build is green and the option matrix is
   green.
2. In `R128` after the splice, no section that reads a decimal column is listed as fused, and
   each of the twelve sections of the rule's table reads between 0.9X and 1.1X in the file's
   `Relative` column, both arms being the row path. Before the part they read 0.2X to 0.4X.
3. The four `TIME` sections of `R128` are untouched by the splice.
4. Nothing moves in the 256-bit file, in `coverage.json`, `emitted_bytes.json`, `census.tsv` or
   `casts_fused.tsv`.
5. Section 10 records: the header count of `VARKA-612-refused.txt` before and after, the lines
   rule 1 of step 5 removed if any, the planted fault's run, and the twelve ratios after.
6. Row 597 in `PLAN.md` opens with `**Done**` for 597b, 597a staying in `m10/SCOPE.md` item 140.

**Not here.** The 36 sections of 597a. Any exemption mechanism. The scalar-loop form S. The
predicate compile. Any `.smt2` file. `width_audit.json` on a host other than the builder's.

### 11.6 Predictions, registered before the build

1. After the part, at 128 bits on the EPYC 7763, the twelve sections of the rule's table read
   0.9X to 1.1X.
2. `VARKA-612-refused.txt`'s count rises from 26 by at least 40: the coverage table has more
   than 40 value rows over `d9`, `d9s4`, `d16s9`, `d18`, `d38` and their kin, and the pair's
   output rows are in the 26 already. The header after the regeneration scores it.
3. No emitted byte moves: `emitted_bytes.json` is unchanged.
4. The part is under 550 counted lines (*fix round:* 450 before step 3b; the removal adds about
   40 on master and about 10 more for each chain part merged first).

### 11.7 Questions for the owner

*Questions 1 to 3 were answered by the owner on 28 November 2026; each answer follows its
question.*

1. **Should 597b also remove the arm-level two-lane tests it makes redundant?** They are the
   three tests of `VarkaDecimalCastCompiler.TWO_LANE_REASON` and the uses of
   `TWO_LANE_WIDE_REASON` that rows 360 to 363 add. `VARKA-360.md` 11.7 and the plans of rows 362
   and 363 say "597 removes them". Options: (a) keep them: each runs before its operand is read
   and names its arm, a cast into a decimal from an integral type (row 350's next part) reads no
   decimal column and needs its own test anyway, and the removal would edit reason strings that
   tests of three rows in flight pin; (b) remove them in 597b, one reason for every decimal
   operand. **Recommended: (a). Default applied: (a).** **Answered on 28 November 2026: (b),
   remove them in 597b, since 597b's reader-level decline covers them.** So the chain plans'
   "597 removes them" is true. 11.5 step 3b writes the removal, the tests that move, and what a
   chain part built after 597b does instead; the cast into a decimal from a source that is not
   a decimal, which reads no decimal column, declines through the same helper at the head of
   `DCC.intoDecimal` (11.3), so option (a)'s first reason no longer holds.
2. **`bigintPair`, the pair with no decimal column.** Options: (a) it declines with the readers,
   as the answer of 11 November 2026 to question 1 says; (b) it fuses under a value root until a
   section measures it. **Recommended: (a)**: its compares and mask joins are the fallback, and
   decline-by-default is the answered rule. **Default applied: (a).** **Answered on
   28 November 2026: yes, (a).** The gap of review 1's finding 3c (who adds the call when 597b
   lands before `bigintPair` does) is closed in 11.5 step 2: the side that lands second adds it.
3. **The measurement's dispatch** is the builder's (step 10); **answered on 28 November 2026**:
   the builder dispatches, as recommended, not the lead. If no dispatch is possible before the
   close, done-when items 2 and 3 are read from `VarkaDecimalBaselineQueriesSuite` instead (the
   twelve sections are `NONE` under the refusal), and the splice is owed to the next refresh of
   `R128`. **Recommended and applied:** dispatch.

### 11.8 What only a JVM can settle, and the command for each

The lead may run these before the builder starts; each is one compile, no timing.

| claim | command | settles |
|---|---|---|
| `CAST(x < y AS INT)` over two `DECIMAL(9, 2)` columns fuses today | `dev/varka_emit.sh "CAST(x < y AS INT)" --columns x:dec9s2,y:dec9s2` | the line ends `FusedOutput(0)`, or step 5's rule 1 drops it |
| `IF(x < y, l, l2)` with `BIGINT` branches fuses today | `dev/varka_emit.sh "IF(x < y, l, l2)" --columns x:dec9s2,y:dec9s2,l:bigint,l2:bigint` | the same |
| the same two over `DECIMAL(38, 10)` | the same commands with `dec38s10` | the same |
| the count of refused value rows after the part | `VARKA_COVERAGE_REGEN=true build/sbt 'catalyst/testOnly *VarkaCoverageSuite'` | prediction 2 |
| what moves in `width_audit.json` at 128 bits | `dev/varka_regen_generated.sh --all` on the file's host | 11.4's third item |
| every assertion that named a removed reason now sees `TWO_LANE_READ_REASON` (fix round) | `build/sbt 'catalyst/testOnly *VarkaExpressionCompilerSuite'` after step 3b | step 3b item 4's stop rule |
| the five entries of step 7 fuse beside the four declined (fix round) | `build/sbt 'sql/testOnly *VarkaTwoLaneDefault128Suite'` on an x86 host | step 7's stop rule |

A decimal column is spelled `dec<p>s<s>` there (the `columnType` switch of `VarkaEmitDump.java`),
since the column list splits on commas. The test of step 5 run under `NO_HOST_REFUSAL` settles
the first three claims as well.

### 11.9 Where this section replaces sections 0 to 9

| section | what it said | what holds now |
|---|---|---|
| 0 items 1, 2, 11 | no decimal value fuses as an output; the decimal-reading values are three nodes that hold a condition | every 64-bit decimal value is an output since #1628; 11.2 lists the kinds |
| 0 item 7 | the 128-bit file times one fused decimal value, `x < y` | it times twelve more, 11.2 |
| 0 item 9, 2.1 "Cost", 5 | D2 waits on #1478 and #1484 | both merged; `DeclineSink.declinesTwoLaneChecks` is on master |
| 2.1 | "about 40 lines of source" | holds; the readers are `column`, `columnPair` and `bigintPair` |
| 3 items 1 to 3 | a sweep over `coverage.json` and new suites | steps 5 to 7 of the build sheet; the sweep's job is done by `VARKA-612-refused.txt`, which `VarkaCoverageSuite` renders from every value row |
| 3 item 5 | "the width audit: no row moves" | at 128 bits on x86 the decimal value rows move to declined, 11.4 |
| 4, 5 item 1, 8 "Measured" | 597a's 36 sections first | 597a stays in `m10/SCOPE.md` item 140; the rule of 11.2 reads the committed file and decides the unmeasured kinds by the owner's answer 2 |
| 8 "Unchanged elsewhere" | `width_audit.json` does not move | see 3 item 5 above |
| 5 item 3, 11.7 question 1's default | "whichever of 597b and 360a lands second adds the case"; the arm-level tests stay | the owner's answers of 28 November 2026: the arm-level tests go (11.5 step 3b); the side that lands second adds `bigintPair`'s call (11.5 step 2) |

## 12. The house rules that bind this part

Each is written where named; the builder reads them there.

- Answers equal vanilla Spark's under both ANSI settings: `sql/varka/AGENTS.md`, "House rules
  that bite here specifically", the ghost fallback. A declined entry is the row engine's.
- A test pins the configuration it depends on, and the option matrix must pass under every
  configuration: the root `AGENTS.md`, "Build and Test". The new compiler test passes
  `VarkaEmitOptions.DEFAULTS` and an explicit admission, so no matrix arm changes it.
- Benchmarks are committed files: `sql/varka/AGENTS.md`, "Measurements, not adjectives". A ratio
  within 1.3x of 1.0 is a tie.
- Never push by hand, and the gate is `pre-pr.sh`: `ADDING_AN_EXPRESSION.md`, "Closing a row".
- Do not edit a generated file by hand: regenerate.
