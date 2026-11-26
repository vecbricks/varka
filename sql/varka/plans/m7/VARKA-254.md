# VARKA-254: The grouping weights against the emitted counts

## 1. Where this came from

Row 254 of `m7/PLAN.md`, from item 63 of `m10/SCOPE.md` (added from VARKA-209's build). The emitter
splits a projection's outputs into groups, one loop method each. Its first rule prices each node by
`VarkaEmitBudget.weightOf`, a hand-kept register of the vector operations a node's lowering emits,
and closes a group when the sum would pass `GROUP_BUDGET` (16). An output that reuses what the
group already computes - a calendar prefix, or whole nodes under the default `shareWholeNodes` -
may join up to `FUSED_CEILING` (400). The cost model's prediction (VARKA-199, VARKA-236) and the
measured byte and call-site limits can close a group earlier, never later.

Item 63 found the register over-counting a cheap calendar tail and asked for one of two things:
every weight equal to the count its node emits alone, pinned by a test; or the register retired and
the grouping read from measured costs. Its second clause: the shared-prefix ladder's group counts at
each ceiling are the ones the measured costs give. `m7/PLAN.md` open question 2 ties the choice to
VARKA-236's planner, which reads the same costs.

## 2. The admission check, done

**The census** (12 October 2026, a throwaway suite, not committed). Every IR record emitted alone at
the defaults, its `loopDense0` (`loopMasked0` where no dense body exists) read per Vector API class.
Counted in `IntVector`, `LongVector`, `DoubleVector` and `Vector` calls, not `VectorMask`, net of
the operands' loads, the literals' broadcasts and the output's store - the unit most of the register
is written in (the division register reads it; the calendar register and two other entries do not,
below):

| node | weight | emitted | | node | weight | emitted |
|---|---:|---:|---|---|---:|---:|
| `DayOfWeek` | **1** | **16** | | `IntArith` WRAP / FAIL / NULL | 1 / 5 / 5 | 1 / 5 / 5 |
| `WeekDay` | **1** | **15** | | `IntNeg` WRAP / FAIL | 1 / 2 | 1 / 2 |
| `GuardedDay` | 1 | 2 | | `AddDays`, `SubDays`, `DateDiff` | 1 | 1 |
| `BoundedDivide` | 1 | 2 | | `Greatest`, `Least`, `IfElse` | 1 | 1 |
| `InRanges` | 6 | 3 | | `Compare`, `NarrowLane` | 1 | 1 |
| `DayOfWeekIso` | 18 | 16 | | `NextDay` | 15 | 15 |
| `GuardedRange` | 1 | not read | | `MakeDate` | 60 | not read |
| `ThursdayOf` | 19 | 17 | | `ConstDivide` int / long | 11 / 14 | 11 / 14 (the larger form) |
| calendar prefix | 31 | 29 | | `And`, `Or`, `Not`, `IsNotNull` | 1 | 0 (mask calls only) |

The register mixes two counts. The calendar register (`VarkaEmitterBudgetSuite`, through
`laneOps`) reads `IntVector` calls gross, the column's load and the output's store included, so
`CHRONO_PREFIX_WEIGHT` (31) is 29 operations plus those two; `ThursdayOf` (19) and `DayOfWeekIso`
(18) were read the same way. The division register and the flat entries are net. The four field
tails share one constant at the widest (7) where they emit 4, 5, 5 and 7. `WeekOfYear`'s tail is 18,
not 16: it emits 64, which is 2 + 17 (the shift) + 27 (the prefix, which elides the month step
there) + 18. `MakeDate` builds only a masked body under `failOnError = false` and `GuardedRange`
needs a dividend bound its guard admits; 8.3 reads both.

**What the corrections do to the grouping.** `VarkaEmitCostAudit` counts loop methods over the
corpus. With the census weights applied (masks excluded), on the four corpora that move:

| corpus | master | weights corrected, budget 16 | only `DayOfWeek`/`WeekDay` | corrected, budget 400 |
|---|---:|---:|---:|---:|
| wide int, planned | 8886 | 9152 (+3.0%) | 9142 | **2194 (-75%)** |
| wide compositions int, planned | 5006 | 5150 (+2.9%) | 5137 | **1238 (-75%)** |
| fuzz int | 1639 | 1656 (+1.0%) | 1655 | **1019 (-38%)** |
| wide long, planned | 6545 | 6545 | - | 1803 (-72%) |

So the under-counts dominate: correcting `DayOfWeek` and `WeekDay` alone accounts for nearly all of
the increase, and the calendar, guard and division corrections roughly cancel. And the budget is the
larger lever: at 16 it closes about three groups in four that the prediction would admit. Its
reason (`VARKA-71.md` 10.5, and the constant's own doc) was to keep methods below C1's refusal, which
`CALL_SITE_BUDGET` and the planned prediction now do on the built class. Builds rise under budget
400 (100 to 139 on the wide int corpus under the prediction), since more groups reach the limits.

**What it does to speed.** `VarkaGroupingBenchmark`, committed on unchanged code as its own pull
request, holds four shapes at budget 16 and 400. An uncommitted trial with the census weights
applied, at 256 bits on the laptop:

| shape | master | corrected, budget 16 | corrected, budget 400 |
|---|---|---|---|
| `dayofweek`/`weekday` over two dates + 4 `date_add` | 2 methods, 0.35 ns/row | **5, 0.51 (-32%)** | 2, 0.35 |
| 40 mixed outputs | 10, 1.98 | 16, 2.10 (-6%) | **8, 1.74 (+14%)** |
| 24 `date_add`, 8 checked sums (controls) | unchanged | unchanged | unchanged |

Correct weights alone make these shapes slower; correct weights with the budget lifted make them as
fast or faster. The controls build one class in every arm. On the benchmark's first run the checked
sums still read 6% and 17% apart between the two arms; the classes are byte-identical (the budget is
read in one place, and one group is one group), so that was where the JIT placed each class. The
benchmark now times a class once and gives arms that build it one row (#746).

**Answer to open question 2.** Not retired: the prediction is fitted (VARKA-199 9.4: a register of
costs measured alone does not predict a wide group) and it judges a group, where the grouping needs a
price per node to walk with; row 200's exact partition needs one too. So the weights are retuned and
pinned, and the budget they are summed against is decided by measurement, below.

## 3. The design

### 3.1 The unit

A weight is the Vector API calls on a lane type (`IntVector`, `LongVector`, `DoubleVector`,
`Vector`) its node adds to a dense loop body alone, net of the operands' loads, the literals'
broadcasts and the output's store. `VectorMask` calls are not counted, as no register entry counted
them. A calendar node's weight is the prefix in its full form plus its tail, at the default month
axis; a node whose group never reads the month emits two fewer, which `GroupOps` already accounts
for. A connective (`And`, `Or`, `Not`, `IsNotNull`) keeps weight 1 as a floor: it emits only mask
calls, and a weight of 0 would make it free to join any group.

This moves the calendar entries, `ThursdayOf` and `DayOfWeekIso` by 2 from the gross count they were
read in. `GROUP_BUDGET` and `FUSED_CEILING` are not rescaled: the budget is the subject of 3.4, and
the ceiling was set from compile times on VARKA-32's ladder, a bound a 2-operation shift per output
does not move. What the shift does move - how many outputs fit under a lowered ceiling - is
prediction 4.

### 3.2 The weights

`DayOfWeek` 16 and `WeekDay` 15 get their own constants; `GuardedDay` 2, `BoundedDivide` 2,
`InRanges` 3, `DayOfWeekIso` 16, `ThursdayOf` 17; `CHRONO_PREFIX_WEIGHT` 29; the field tails split
into `YEAR` 5, `MONTH` 4, `DAY_OF_MONTH` 5 and `QUARTER` 7; `WEEK_OF_YEAR_TAIL_WEIGHT` 18; `MakeDate`
and `GuardedRange` from 8.3's reading. Every other weight already holds.

### 3.3 The register test

`VarkaEmitterBudgetSuite`'s two register tests become one, over every record the sealed
`VarkaVectorIR` permits (read through `getPermittedSubclasses`, as `VarkaWithChildrenSuite` does), so
a new node without a register row fails it. Each row is a node emitted alone, counted in 3.1's unit,
and the assertion names the constant to recount when it fails. Three kinds of row:

* **A plain node**: its count equals `weightOf`.
* **A calendar node**: its count equals `weightOf` less the month step where its own tail does not
  read the month (`year`, `dayofyear`, `trunc YEAR`, `weekofyear`), the existing test's
  `readsMonth` rule kept; and beside `month(d)` in one method it adds exactly its tail.
* **A connective**: its lane-type count is 0 and its weight is the floor of 1, asserted as such, so
  the exemption is a row and not a gap.

Tagged `PinsDefaults`: the weights are the default options' counts (the division's larger form is
read under `withUseAVX(2)`, as today).

### 3.4 The budget, two arms

* **A.** The corrected weights against `GROUP_BUDGET` 16, as today.
* **B.** The first rule's bound is the fused ceiling instead of the budget, but only where something
  else bounds the group, and the bound it removes is replaced where the exemptions assumed it:
  * only when the prediction runs with the plan's margins and a call-site budget
    (`predictGrouping`, `planSize`, a byte budget and a non-zero `callSiteBudget`): without the
    margins the prediction once filled a method of 64 cheap tails that ran 13 times slower
    (`VARKA-236.md` 9.3), and without a call-site budget only bytes would bound a group;
  * only while the cost model prices the group: `predictedWithin` admits a group it cannot price,
    and there the budget binds as in A;
  * a group that an output joined past the budget by B, and not by reusing what the group
    computes, is judged on predicted call sites whatever its width. `HEAVY_GROUP_OUTPUTS` exempts a
    group of up to six outputs from the call-site check on the premise that such a group forms only
    by sharing a prefix, where no split helps; B would otherwise put six unrelated `make_date`
    (6 x 60) or three `add_months` (3 x 112) in one method past C1's limit with nothing to split it.

  A new emit option, `groupBudgetUnderPrediction`, registered in `VarkaEmitOption.TABLE` so the
  option matrix covers both values; true is A.

Both are built; 6 picks the default, and the losing value stays a matrix configuration, since the
winner may depend on the machine.

### 3.5 The ladder clause

`VarkaEmitterBudgetSuite` gains a test over the shared-prefix ladder's two shapes
(`VarkaSharedPrefixBenchmark`: 64 cheap tails and 60 `make_date`) at ceilings 400, 200, 100 and 50:
the group counts the weights give equal the counts the measured costs give, read as the cost
model's predicted grouping without the weights' bound. The counts are pinned, and 6.1 registers
which move.

### 3.6 What is deliberately unchanged

`FUSED_CEILING`, `CALL_SITE_BUDGET`, the byte budget, the prediction and its margins, and every
lowering. No answer changes: the grouping decides only which loop method computes an output.

## 4. Files

| file | what |
|---|---|
| `VarkaEmitBudget.java` | the weights, 3.2 |
| `VarkaEmitOptions.java`, `VarkaEmitOption.java`, `VarkaLoopEmitter.java` | arm B's option, its matrix entry, and the first rule with its width check, 3.4 |
| `VarkaEmitterBudgetSuite.scala` | the register over every node, the ladder test, 3.3 and 3.5 |
| `sql/varka/emitted_bytes.json`, `sql/varka/emit_cost_audit.json` | regenerated |
| `VarkaGroupingBenchmark.java` | its arms toggle `groupBudgetUnderPrediction`, with an arm per value and the budget-400 arm kept, so the file keeps measuring after the default changes |
| `sql/catalyst/benchmarks/VarkaGroupingBenchmark-*`, `VarkaSharedPrefixBenchmark-*` | regenerated |
| `sql/varka/plans/m7/PLAN.md`, `VARKA-254.md` | the row and this plan |

## 5. Tests, and what each is for

* **The register (3.3)**: every weight equals its node's count, and a node without a row fails.
* **The ladder (3.5)**: item 63's second clause.
* **Arm B's bounds**: six `make_date` over six columns and three `add_months` stay split under B,
  each group within `CALL_SITE_BUDGET`; B off where `planSize` is off, the call-site budget is 0, or
  the cost model cannot price a group.
* **The bytes oracle**, regenerated: in a first trial (with mask calls counted, so heavier
  weights than 3.1's) 170 blocks of 100 shapes moved, which is the
  regrouping and not a lowering change; the plan's 9 records the counts.
* **The suites that pin a group layout** (`PinsDefaults`) are updated where the corrected weights
  or the chosen arm move them, each change named in 9. In that trial only the calendar register
  failed at budget 16, of `VarkaEmitterBudgetSuite` and `VarkaGroupingBoundSuite`.
* **Every emitter and fuzz suite at both widths** in the gate, and the option matrix with arm B's
  configuration: no answer may change.

## 6. The measurement

`VarkaGroupingBenchmark` (its arms A and B on one head) and `VarkaSharedPrefixBenchmark` at both
widths, base against head through `dev/varka_bench_pair.sh`, and the audit's loop-method counts. Arm B becomes the default if no
benchmark row is slower than master by more than 3% at either width and the corpus builds fewer loop
methods; otherwise A, with B kept as an option.

### 6.1 Predictions, registered before the run

1. **Arm A is slower on the weekday shape**: 5 loop methods against 2, at least 25% slower at 256
   bits; the mixed shape builds 16 and is 3 to 10% slower.
2. **Arm B is as fast on the weekday shape** (within 3%, 2 methods) **and faster on the mixed
   shape**: 8 methods, at least 10% faster at both widths.
3. **The corpus**: arm A builds 1 to 3% more loop methods, arm B 30 to 75% fewer, with more builds
   under B (the regroups the measured limits ask for). The trial had none of 3.4's bounds; they
   keep narrow heavy groups split, so B's reduction may come out below the trial's.
4. **The shared-prefix ladder moves at the lowered ceilings, under both arms.** A cheap tail
   `year(d) + k` costs 39 for a group's first output and 8 after it today, 35 and 6 corrected, so
   by the weights alone 64 recomputed tails take 22 groups at ceiling 50 (32 today) and 6 at 100
   (8 today); materialized arms, which load the prefix at 6, move less. At 200 and 400 the
   call-site budget decides and nothing moves. The `make_date` ladder (weight 60 a rung) is
   unchanged unless 8.3 moves `MakeDate`'s weight.

## 7. Risks

1. **Wider methods spill.** Arm B's methods hold more live vectors; at 128 bits there are as many
   registers and twice the operations a row, so a spill shows there first. The 128-bit rows are the
   check, and a row past 3% sends the default to A.
2. **The prediction's margins were set on the old groupings.** VARKA-236 read its margins (21.1% on
   bytes, 17.7% on call sites) where each limit binds. Arm B puts more groups in that band, so more
   built classes may measure over a limit and rebuild; the audit's build counts are the check.
3. **Compile time and warm-up.** Fewer, larger methods move C2's work around; the warm-up suites in
   the gate's quiet step are the check.
4. **Heavy outputs that share nothing.** 3.4's width check is what keeps B from packing them past
   C1's limit; its test is the check, and a planted removal of the check must fail it.
5. **Scope.** If arm B wins it changes the default grouping everywhere, which is more than the row's
   size. If its measurement or its fallout outgrows the row, B becomes its own row and 254 lands as
   A with B off by default - recorded here, not decided now.

## 8. Sequencing

1. `VarkaGroupingBenchmark` on unchanged code, its own pull request (#746).
2. This plan, and the row marked Planned.
3. The census holes: `MakeDate`'s dense count under `failOnError = true`, `GuardedRange` under a
   bound its guard admits, and the long lane under both division forms.
4. The weights and the register; the ladder test.
5. Arm B's option, its bounds and their test; the benchmark's arms on the option.
6. Section 6 at both widths, the default chosen, the oracles and results files regenerated.
7. Section 9.

## 9. Outcome

Done on 12 October 2026: `VarkaGroupingBenchmark` on unchanged code first (#746), then the
weights, the register, arm B and the default in one pull request.

### 9.1 The weights

Every weight equals the lane calls its node emits alone, net of the operands' loads, the
literals' broadcasts and the output's store (3.1). Beyond the census in 2, the holes 8.3 read:

| weight | was | now | why |
|---|---:|---:|---|
| `DayOfWeek`, `WeekDay` | 1 (the default) | 16, 15 | never named; the flat default |
| `CHRONO_PREFIX_WEIGHT` | 31 | 29 | the load and store were counted in |
| the four field tails | 7 each | 5, 4, 5, 7 | one constant at the widest |
| `WEEK_OF_YEAR_TAIL_WEIGHT` | 16 | 18 | the shift's load and store counted twice |
| `ADD_MONTHS_TAIL_WEIGHT` | 81 | 80 | the month literal's broadcast counted in |
| `TRUNC_DYNAMIC_TAIL_WEIGHT` | 60 | 59 | the level column's load counted in |
| `MAKE_DATE_WEIGHT` | 60 | 53 | read off the dense body under `failOnError` |
| `THURSDAY_OF_WEIGHT`, `DAY_OF_WEEK_ISO_WEIGHT` | 19, 18 | 17, 16 | gross counts |
| `GuardedDay`, `GuardedRange`, `BoundedDivide` | 1 | 2 | two compares, or a multiply and a shift |
| `InRanges` | 6 | 3 | its mask calls were counted |

`weightOf` is an exhaustive switch over the sealed IR, so a node added to it has no weight until it
is given one; `DayOfWeek` and `WeekDay` reached 1 by falling to a default. The register test covers
every record the IR permits and fails for one without a row; the calendar tails beside `month(d)`
are a second test. The ladder test pins item 63's second clause: at every ceiling the benchmark
sweeps, recomputed or materialized, the first grouping is the one the built class keeps - one build.

### 9.2 Arm B, and a bug its corpus count found

The first build of arm B checked call sites at any width for every later output of a group the
prediction had widened, including those that joined by reusing a prefix. The corpus counts showed
it at once: the size ladder took 770 loop methods against 193, the `make_date` ladder 72 against
12. The check now applies to the step the prediction admits, which still catches six unrelated
`make_date` (a planted removal of the check packs them into two groups, and the test fails), and an
output that joins by reuse keeps the heavy-group exemption the ladders rely on.

The unpriced branch of 3.4 is guarded in code and not tested: `VarkaEmitCostSuite` asserts that
every feature the corpus reaches has a price, so no default emission reaches it.

### 9.3 The review, 12 October 2026

A code review of the pull request found thirteen problems, all fixed in it:

1. **A widened group's later joins by reuse skipped the call-site check.** `year(d)`, two
   `date_add` and `add_months(d)` planned as one group of about 127 call sites, under the six
   outputs the after-build split exempts. Section 9.2's step-only check was too narrow; its first
   form was too wide because it flagged a group's *first* output, which is heavier than the budget
   on every ladder rung. A group is now flagged at its first join past the budget that is not by
   reuse, and checked on call sites at every later join. The test plants the step-only check back
   and fails ("the build regrouped the plan").
2. **The size loop's fallbacks closed more groups.** Rolling back the call-site budget or dropping
   the prediction turned the rule off and the budget of sixteen bound again; a fallback may only
   close fewer. Where the caller's options let the prediction bound groups, the fallback grouping
   is bounded at the fused ceiling instead.
3. **The after-build split never touched a narrow widened group.** Groups the prediction widened
   are recorded, and the call-site split halves them however narrow. The same planted run as 1
   shows it: with 1's check removed and this one kept, the build split the four-output group in
   three.
4. **The shipped default had no test holding the exact grouping to the best partition**; one now
   does, on the mixed family and twenty corpus wide shapes, each emitted.
5. and 6. **Docs and javadocs** stated the budget's rule as the only one; `docs/sql-varka.md`,
   `sql/varka/AGENTS.md`, the IR diagram (regenerated) and five javadocs now state both.
7. **A dead assertion** in arm B's test, replaced by 1's test.
8. **`make_date`'s null form** has a register row of its own.
9. to 13. Test-helper duplicates and `weightOf`'s unused bindings, removed.

Its effect on the corpus: arm B takes 17,583 loop methods (it took 16,779 before the fixes), the
mixed family over one date 88 (127), the same as arm A, and the ladders are unchanged.

### 9.4 The measurement

`VarkaGroupingBenchmark` at both widths on `a93cb83e706` (load 0.67, canary within 1.1%), against
#746's rows on unchanged code, in M rows/s at 256 and 128 bits:

| shape | before | arm B, the default | arm A, the budget binds |
|---|---|---|---|
| weekday | 2 methods, 2887.5 / 1330.9 | 2, 2815.2 / 1318.2 | 5, 1937.0 / 922.5 |
| 40 mixed | 10 methods, 505.5 / 207.3 | 8, 574.8 / 258.3 | 16, 468.4 / 201.6 |
| mixed families (new) | - | 11, 72.2 / 27.8 | the same class |
| 24 `date_add` | 2 methods, 1573.7 / 739.8 | 2, 1611.0 / 739.6 | the same class |
| checked sums | 1 method, 3113.0 / 1080.8 | 1, 2960.7 / 1071.4 | the same class |

The regeneration before this one read the checked sums under the default at 1038.9 at 256 bits,
against 2938 for its two controls, the same class loaded twice more: one loaded copy of a class
compiled to a third of the others' speed, timed twice. A regeneration on the same commit did not
repeat it, and the file is that one's.

Over the cost audit's corpus, arm B takes 17,583 loop methods where arm A takes 29,037 (-39%), and
master took 28,404 on its own weights. By family the wide long shapes take 2,226 against 6,689 and
two dates interleaved 71 against 204; the ladders and the mixed family over one date are arm A's.

`VarkaSharedPrefixBenchmark` regenerated on the same commit: the recomputed cheap tails take six
groups at every ceiling (four before), the `make_date` ladder ten at ceiling 400 (eleven or
twelve) and twenty at 200 (twenty-six or thirty). Its committed file predates other merged
changes, so its times are not read as this change's; the grouping benchmark is the controlled
comparison.

### 9.5 Predictions scored

1. **Held.** Arm A's weekday shape takes five methods and runs 33% and 31% slower than before; the
   mixed shape takes sixteen and runs 7% and 3% slower.
2. **Held.** Arm B's weekday shape runs within 3% of before (-2.5%, -1.0%) with two methods; the
   mixed shape runs 14% and 25% faster with eight.
3. **Held.** Arm A builds 1 to 3% more loop methods on the families that move; arm B builds 39%
   fewer over the corpus, inside the 30 to 75% predicted, and more on no family once the review's
   first fix was in (before it, the mixed family over one date took 44% more).
4. **Wrong.** The ladder's cheap tails do not move with the ceiling at all under the defaults: the
   call-site budget and the exact grouping decide them, at six groups recomputed and four
   materialized from ceiling 400 down to 50. The `make_date` ladder moved, but with `MakeDate`'s
   weight, which 8.3's census changed.

**The default.** Section 6's rule asked that no row be slower than before by more than 3% at either
width. The checked sums are, at 256 bits: 4.9% below before. They build the same class under every
arm, and their two control rows in the same run read 2920.4 and 2942.2, as low; #746's band puts
that row's run-to-run spread at about 10%. The rule is read against the band, which is what it was
committed for, and arm B is the default.

### 9.6 The tests

Nine tests pinned a grouping the budget made; each now names the budget's rule
(`groupBudgetUnderPrediction` on), since its subject is that rule or a mechanism that needs two
groups to exist: the calendar siblings, whole-node reuse, the int-lane divisions and the
call-site regroup in `VarkaEmitterBudgetSuite`, the shared node's slot in
`VarkaEmitterArithmeticSuite`, the materialized prefix's guard word in `VarkaEmitterValiditySuite`,
the two dates in `VarkaEmitterChronoSuite`, `VarkaExactGroupingSuite`'s counts, and arm B's own
test, which compared B with a default that is now B. The make_date split test counts the weights'
three groups (four before). The price tables were refitted on the recounted weights (margins 20.9%
and 18.2%, from 21.1% and 17.7%), and the wide-shape list lost shape 59.

### 9.7 Corrections to the plan

* 3.4's "from then on" was the bug of 9.2.
* 3.3's register needed a fourth kind of row: `IsNotNull` reads its column's validity and no value,
  so it pays no load.
* `ADD_MONTHS_TAIL_WEIGHT` and `TRUNC_DYNAMIC_TAIL_WEIGHT` moved too; 3.2 did not list them.
* Arm B's test needed the weekday shape: twenty cheap outputs do not separate the arms, since the
  prediction prices one at about 4.6 call sites against the 3 it emits and closes at sixteen, where
  the budget does.
