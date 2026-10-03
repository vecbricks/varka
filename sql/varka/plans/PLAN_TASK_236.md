# Task 236: plan a kernel's size before building it

*Planned 2 October 2026 (milestone 6 row 236), with its admission check run the same day on
master at `8afa3fe4bd3`.*

## 1. Where this came from

Row 236 of `PLAN_MILESTONE_6.md`, added on 30 September 2026 from the review of size control after
task 190 step 2 (#529). Size control reacts to measurement one fallback at a time. `emit` builds a
class, measures it, and then regroups on bytes, splits on call sites, rolls those splits back,
drops the exact grouping and then the prediction, or splits the driver into stages, each a build;
the compiler bisects a class-wide decline (task 169), which is also how several kernels find their
split. The pieces for a plan exist: task 199's fitted model predicts each group method's bytes and
call sites, task 200's exact partition is a dynamic program over the grouping's runs, and the
driver from a table grows by a fixed number of bytes a group (`PLAN_TASK_190.md` 10.5).

The row also carries a decision. Task 190 put both forms past the driver's ceiling on by default:
the split driver (A') for the driver's ceiling, several kernels (B) for more than 64 columns and the
class-file caps. On the runner B ran 0.9 to 2.6% faster per row but planned five to six times
slower, because its split is found by bisection, and the owner kept both on "until 236 removes the
search" (`PLAN_TASK_190.md` 11.6). On 2 October 2026 the owner asked for that decision to be
reconsidered in this plan; section 3.6 does.

## 2. The admission check, done

On master at `8afa3fe4bd3`. Each shape below was emitted with the emitter's trace
(`VarkaEmitTrace`) counting builds and reactions:
* every shape of the cost audit's held-out corpus;
* task 200's mixed and interleaved families, and its sixty coverage compositions;
* the size ladder at 800 and 1200 entries;
* forty compositions of wide draws, twenty of each lane, each drawn as the IR fuzzer's wide test
  draws: until 250 roots are drawn or twelve draws are made, with repeated roots then dropped.

This plan's first step pins the builds of 2.1 in the cost audit (`emit_cost_audit.json`, section
`grouping`), except the coverage compositions. Their draw indexes `coverage.json`, so a pinned file
over them would move with every expression added; they are this check's reading alone. The
readings of 2.2 to 2.4 come from a scratch suite over the same shapes, not committed. Everything
below is a count; nothing is timed.

### 2.1 What rebuilds at the defaults

| family | shapes | built once, prediction off | built once, prediction on | the rebuilds forced by |
|---|---:|---:|---:|---|
| fuzz, both lanes | 2000 | 2000 | 2000 | - |
| wide int | 100 | 98 | 100 | call sites |
| wide long | 100 | 83 | 98 | call sites; one shape bytes too, prediction off |
| size, `make_date` and cheap-tail ladders | 9 | 7 | 9 | call sites, the cheap tails |
| mixed and interleaved families | 8 | 8 | 8 | - |
| coverage compositions, not pinned | 60 | 50 | 55 | call sites |
| size ladder at 800 and 1200 entries | 2 | 0 | 0 | stages |
| wide compositions, int | 20 | 0 | 0 | stages; one shape call sites too, prediction off |
| wide compositions, long | 20 | 1 | 4 | stages and call sites; one shape bytes too, prediction off, until task 223's review |

At the shipped options, where the prediction is off, 72 of 2319 shapes build more than once: 44 on
call sites, 37 on stages, 9 of them on both, and 2 on bytes. With the prediction on, 45, and two
reactions account for all of them: the stage split, on all 37 shapes past the driver's ceiling, and
the call-site split of task 209, on 9, one of them also staged. The byte regroup did not fire with
the prediction on, and nothing declined. Without the coverage compositions, as the audit pins it, 62
of 2259 rebuild with the prediction off and 40 with it on.

*Since task 223's review (#547, merged after this check): its count removes bytes from long
composition 5, whose one byte regroup with the prediction off no longer fires, so the audit now
reads 1 shape on bytes rather than 2; every other figure above stands.*

### 2.2 The driver and its stages are known before the build

The driver from a table, measured with the split driver off and a budget out of reach:

| shape | groups | dense driver | masked driver |
|---|---:|---:|---:|
| one `date_add` a group | 1, 2, 100, 181, 182, 300, 400 | 20 + 40 a group | 13 to 15 bytes more |
| the size ladder | 100, 200, 300 | 20 + 44 a group | 1 byte more |

Exact at every reading, so the driver's bytes are a function of the group count, but not one
formula: the ladder's class also passes each group's loop and epilogue the scratch address of task
198's materialized prefix, four bytes a group. A plan should read the driver off a driver built
alone rather than track its code in a formula.

With the prediction on, the first grouping alone gives the stage count the measured driver later
chooses on all 42 ladders and compositions: two stages on the 37 past the ceiling, none on the five
long compositions under it. One long composition regrouped on call sites, adding two groups, and
kept its stage count. With the prediction off, the first grouping under-counts the groups of 13
compositions, which call-site splits then add, and on one of them the added groups take the driver
past the ceiling: two stages where the first grouping gives none.

### 2.3 Several kernels' split is known before the build

With the split driver off, so that B serves the driver's ceiling, a planned cut - the largest
prefix whose first grouping's driver fits the budget, read off one grouping of the entries and
checked at the boundary - was set against the bisection the compiler runs today, on the same 42
shapes:

* **With the prediction off, the two agree on 41 of 42**: the ladder at 800 entries in 724 + 76,
  at 1200 in 724 + 476, and each composition in two kernels or in one; on the 42nd the plan cuts
  one entry earlier. The cut took 4 grouping walks for a projection of two kernels, and the
  bisection 10 to 13 emissions; for one kernel, one each.
* **With the prediction on, 34 of 42 agree.** On eight compositions the plan cuts 1 to 34 entries
  earlier, and on one of them it makes a second kernel (314 + 8) where the bisection kept 322
  entries together.
* **Where they differ, the bisection kept a prefix the first grouping puts past the driver's
  limit**, 182 to 198 groups, which fits only after the emitter's loop falls back to a grouping of
  fewer groups: without the exact partition in eight of the nine, and after rolling back its
  call-site splits in six, which leaves methods over the call-site budget.
* Each planned kernel built once, except where a call-site split regrouped it: 13 kernels with the
  prediction off, 2 with it on.

### 2.4 Where B's cut costs

B cuts in projection order and does not see what entries share. Sixty-four dates, four fields of
each (`year`, `month`, `quarter`, `dayofmonth`):

* **Listed by date**, sixty-four groups, one kernel either way.
* **Listed by field**, 256 one-output groups, past the driver's ceiling. A' builds one class with
  two stages, in which each date's calendar prefix is computed by the group that first reads it
  and loaded by the three after it (task 198). B splits it 181 + 75, and both kernels read all
  sixty-four dates, so the second computes sixty-four prefixes that A''s class loads, and reads
  sixty-four columns again.

The ladder, where B led per row in 11.6, has one date: B's cut costs it one prefix and one column.

### 2.5 What the check admits

The row as written. Every rebuild left with the prediction on is predictable or nearly so: the stage
splits exactly, from the first grouping and the driver's bytes; several kernels' split on 41 of 42
shapes with the prediction off and on 34 with it on, the rest through fallbacks the plan should not
take; and the call-site splits already fall from 44 shapes to 9 when the prediction closes groups,
which is the remainder a margin and one correction have to cover. What the check does not support is
a new reaction: at the defaults the byte budget almost never binds, so a correction mechanism is
exercised only under the small budgets the fuzzer draws.

### 2.6 The review of this plan's first step, 2 October 2026

A code review of the pull request found ten problems with the first step's audit code, none in
the plan's figures. The fixes:
* **Coverage compositions out of the pinned audit.** They had made the file depend on
  `coverage.json`, been admitted by the compiler under one arm's options, and been drawn by a
  shared Scala object against the rule that new Varka code is Java. The draw is
  `VarkaGroupingBoundSuite`'s own again.
* **Docs no longer claim what the corpus does not do.** `pastCeiling` does not guarantee 250 roots
  or a shape past the ceiling, and the audit counts the emitter's builds, not the compiler's
  bisection. Step 2's tests, not this file, give the before-count of several kernels' emissions.
* **The reaction names live in `VarkaEmitTrace.reactions()`**, one list instead of two parallel
  ones.
* **The two composition loops are one helper.** The fuzzer's own composition, which wants its
  draws' column domains as well, keeps its loop.
* **The cost of the extra families**: the audit's suite runs in about 20 seconds where it took
  about 12, which it keeps paying, since a file checked only at regeneration would not be checked.

## 3. The design

### 3.1 One plan per kernel, before the first build

`VarkaKernelPlan`, computed from the IR and the options before anything is built:

* **The groups**: the grouping `emit` already makes - task 200's exact partition over the rule's
  runs - with the prediction closing groups against the budgets less the margin of 3.3.
* **Each group method's predicted bytes and call sites**, from task 199's prices.
* **The driver's bytes**, read off a driver built alone for that many groups, with the class's
  arguments: no group bodies, one method, exact by construction (2.2).
* **The stage size**, from those bytes as `stageSize` computes it today, where the driver is over
  the budget and the split driver is on.
* **Whether one class serves the kernel**: its driver within the budget or staged, and its
  methods within the budgets by prediction.

The plan uses the emitter's own grouping, the way task 200's exact grouping uses the rule's own
`admit`, so the plan and the build cannot group differently.

### 3.2 Built once, corrected once

`emit` builds the plan's class and measures it, as today. Where everything fits, that is the class:
one build. Where a method measured over a limit, the prediction was wrong, and today's reaction to
that measurement, a group halved at its middle output or the stages resized off the measured driver,
corrects it in one rebuild. Halving settles a group in one rebuild while the prediction errs by less
than half, and the audit's worst error is 34% for bytes and 30% for call sites. Anything still over
after that runs today's loop from where it stands, unchanged: the rollback, the fallbacks, the
declines. That loop is the last resort, and the trace counts the first correction apart from it, so
the audit can name every shape that needed either.

The size loop is not rewritten. It stays as the last resort the row asks for, behind a plan that
makes it rarely run; `SCOPE_MILESTONE_7.md` item 74.2, which assumed this task would replace it,
says so.

### 3.3 The margin

The prediction closes a group at the budget less a margin, one for bytes and one for call sites,
each the largest under-prediction the fit makes on its training methods in the band where the
budget binds. Both are written into `VarkaEmitCostTable` with the prices by the same generator, so
a refit moves them; nothing is typed in by hand. On master the audit's held-out methods of 8000
bytes and over are under-predicted by at most 4.9% (task 235). The audit then counts both
directions on held-out shapes, as task 235 asked: a method measured past its prediction by more
than the margin, which needs a correction, and a group the margin closed that would have fit,
which costs a loop method.

### 3.4 Several kernels from the plan

Where the plan says one class cannot serve the fused entries - its driver over the budget with the
split driver off - `classify` asks the plan for the largest prefix one class serves, from one
grouping of the entries checked at its boundary (2.3), and sets the rest aside for the next kernel,
as it sets aside the bisection's demoted suffix today. Each kernel is then admitted in one emission
through the shape cache, as now.

A planned kernel that still declines class-wide was mispredicted. The decline carries the
grouping the emitter built, so the compiler cuts it once at the driver's limit read off that
grouping; a second decline falls back to the bisection, which stays as the last resort for the
class-file caps the plan cannot predict.

The cut keeps the first grouping. Where the bisection's probe fits today only through the
emitter's fallbacks (2.3), the plan cuts a few entries earlier, or makes one kernel more: the
fallbacks fit the driver by giving up the exact partition's fewer operations or the call-site
budget, and a method over the call-site budget is refused by C1 and runs interpreted until C2
compiles it.

### 3.5 The prediction on by default (`SCOPE_MILESTONE_7.md` item 71)

A shape builds once only where the prediction closes its groups, so the defaults this task needs
have `predictGrouping` on. That is item 71's question, moved to milestone 7 on 30 September and
brought into this task on the owner's decision of 2 October 2026: it asks what the predicted
grouping costs at run time on the shapes it regroups, and whether an output that adds nothing to a
group should count toward the group's width for the call-site exemption. This task answers both,
since its default depends on them: the runner's run of section 6 times the families the prediction
regroups, and the audit counts the rebuilds a duplicate output causes with the width counted either
way. The default follows the numbers, as item 71 asked.

### 3.6 Task 190's question, reconsidered

What the planner changes: B's search goes (2.3), and so does A''s second build (2.2), so both
forms' plan time falls. From 11.6's runner numbers, B's classes took 197 and 283 ms at 800 and 1200
entries, and A' built once would take about half its two builds' 427 and 742 ms. Planned, B would
plan about 8% and 24% faster than A', where today it plans 6.4 and 5.7 times slower. The case
11.6 kept A' on for is gone; what is left to choose on is run time, where B led by 0.9 to 2.6% on
the ladder in a run whose A' case always ran first, and where 2.4 shows a shape on which B's cut
does the work A' does once.

The options, once B's split is planned:

* **(a) Both on, both planned**, 11.5's division of duty unchanged. For: one class keeps every
  output's sharing and reads each column once per batch, the case of 2.4; no default moves. Against:
  B's lead on the ladder goes unused at the driver's ceiling, and one ceiling keeps two mechanisms.
* **(b) B alone**, A' kept as an option arm. For: one mechanism for every ceiling - the driver, the
  column limit, the class-file caps; faster per row on the ladder; slightly faster to plan, its
  classes smaller. Against: its cut ignores sharing, and on sixty-four dates listed by field its
  second kernel recomputes sixty-four prefixes, which should cost far more than its lead on the
  ladder; every batch runs more kernels, each with its own warm-up and scratch.
* **(c) B alone with a cut that keeps sharers together**: the cut becomes a clustering of entries
  by shared columns, task 72's reordering (`SCOPE_MILESTONE_7.md` item 15). Not this milestone.
* **(d) A' alone**: leaves a projection past 64 columns to the row engine, as 11.5 found.

**Recommendation: plan both, keep both on, and settle the question by measurement.** The done-when
needs B's split planned whatever the default, so building both costs nothing extra. The runner's
run of section 6 times A' and B, both planned, on the ladder (B's best case) and on sixty-four
dates by field (its worst), with A' run first and again last as a control for the case order that
11.6 did not control. The rule, registered here: B alone becomes the default if it is no slower
than A' on either family beyond the control's spread, and plans no slower; otherwise both stay on,
and B alone waits for item 15's cut. I expect both to stay on.

**The owner's decision, 2 October 2026: measure, and let the rule decide.**

### 3.7 What is deliberately unchanged

* The prices and the fit (task 199), the exact partition (task 200) and the weights (item 63).
* The output order (task 72, item 15).
* The measurement of the built class, which keeps the last word, and every decline (tasks 87, 169,
  219).
* The shape cache's admission without defining a class (task 237): the plan reduces how many
  probes there are, not what a probe is.
* A filter's predicate, still one kernel; it never nears the driver's ceiling.

### 3.8 Registered op counts

None: the plan decides which builds happen, not what a build emits. Where the plan's first build
is the class today's loop ends with - the split driver's two builds, a kernel the bisection would
have kept - the class is the same byte for byte.

## 4. Files

| file | what |
|---|---|
| `VarkaKernelPlan.java` (new) | the plan of 3.1 and the cut of 3.4 |
| `VarkaLoopEmitter.java`, `VarkaBodyEmitter.java` | `emit` builds the plan first; the driver built alone |
| `VarkaEmitTrace.java` | the first correction counted apart from the last resort |
| `VarkaEmitOptions.java` | `planSize`, off until step 3; `predictGrouping`'s default in step 3 |
| `VarkaEmitCostFit.java`, `VarkaEmitCostTable.java` | the margins, generated with the prices |
| `VarkaEmitDeclined.java` | the built grouping of a class-wide decline |
| `VarkaExpressionCompiler.scala` | `classify` cuts by the plan; the bisection as the last resort |
| `VarkaEmitCostAudit.java`, its suite and `emit_cost_audit.json` | builds and reactions per shape over the families of 2.1 |
| `VarkaWideKernelBenchmark.scala` and its results file | the plan-time arms, item 71's section, sixty-four dates and the control |
| `VarkaKernelPlanSuite.scala` (new) and the suites of section 5 | the tests |
| `PLAN_MILESTONE_6.md`, `SCOPE_MILESTONE_7.md` | row 236; items 71 and 74.2 |

## 5. Tests, and what each is for

* **The plan's driver is the built driver**, byte for byte, on one-output groups from 1 to 400 and
  on every shape past the ceiling, and its stage size is the one the measured driver gives: a
  change to the driver's code fails here instead of costing a rebuild.
* **The planned split driver is the two-build class** byte for byte, at 800 and 1200 ladder
  entries and on the wide compositions.
* **Every shape of the audit's corpus builds once under the plan**, except the corrections the
  audit names, and none builds a third time before the last resort; the audit file pins the list.
* **The compiler's cut costs one emission per kernel** (`VarkaShapeCache.buildCount`, task 237),
  gives the bisection's kernels on the ladders and the compositions with the prediction off, and,
  under a test hook that makes the plan under-count a kernel's groups, corrects once from the
  decline's grouping.
* **Answers**: the IR fuzzer draws `planSize` like every boolean, the wide compositions included;
  `VarkaSeveralKernelsSuite` runs a planned cut against the row engine end to end.

## 6. The measurement

Three readings, of which the first two are counts and run anywhere:

* **Builds**: the audit's builds section over the families of 2.1, before (today's defaults) and
  after (the plan and the prediction), each correction named with the method, its prediction and its
  measurement.
* **Emissions for several kernels**: per projection, with the split driver off, the bisection's
  against the cut's.
* **On a GitHub runner, `VarkaWideKernelBenchmark`**: the plan-time section with each form as
  today and planned, in one run; item 71's section, the families the prediction regroups, both
  bodies, predicted against the weights; and task 190's section, A' and B both planned, at 800 and
  1200 ladder entries and on sixty-four dates by field, with the A' case first and again last.

### 6.1 Predictions, registered before the run

1. **Builds.** Under the plan and the prediction every shape of 2.1 builds once, except shapes
   whose call sites the model under-predicts past the margin; there are fewer than today's 9,
   each named, and none takes a third build.
2. **Stages.** All 37 shapes past the ceiling build once under the split driver, where each takes
   two builds today, and each class is byte for byte the two-build class.
3. **Kernels.** With the split driver off, the cut takes one emission per kernel where the
   bisection takes 10 to 13 for two, and it gives the bisection's kernels wherever the bisection
   did not fit through a fallback.
4. **Plan time.** The split driver plans in about half 11.6's 427 and 742 ms, and several kernels
   in their classes' 197 and 283 ms plus under 5%, against 2712 and 4226 ms with the search.
5. **No class moves** where today's first grouping already built once with the prediction; the
   classes the prediction and the margin regroup are the ones the audit lists.
6. **Item 71.** The predicted grouping runs within the run's noise of the weights' on the families
   it regroups.
7. **Task 190's question.** On the ladder B planned beats A' planned by about 11.6's 0.9 to 2.6%,
   more than the control's spread. On sixty-four dates by field B is at least 10% slower: its
   second kernel computes sixty-four prefixes, about thirty-one operations each, where the one
   class loads six vectors, and the fields themselves are a few operations each.

## 7. Risks

1. **A plan that halving cannot fix in one rebuild**: a prediction off by half or more, past the
   audit's worst error. The audit names any shape that builds a third time; the fix would be a
   split in proportion to the measurement rather than at the middle output.
2. **The margin costs run time**: each group it closes early is a loop method more. It moves only
   groups predicted within a few percent of a budget, the audit lists them, and item 71's section
   times them.
3. **The compiler and the emitter must group a kernel alike** before either has built it. Both
   take the options the shape key carries, as task 169 made every caller do, and the cut test
   compares the plan's grouping with the build's.
4. **B's cut and sharing** (2.4): the reason the default is measured rather than argued.
5. **A plan costs time too**: a grouping walk, and four for a cut of two kernels, where every
   emission already walks the grouping at least once.

## 8. Sequencing

1. This plan, with the audit's builds section extended over the families of 2.1 at today's
   defaults, which is the before; row 236 marked Planned. No emitted byte moves.
2. `planSize` behind its switch: the plan, the margins, the corrections counted, the compiler's
   cut, and the tests of section 5; the audit with the switch on.
3. The runner's run, the predictions scored, and the defaults set: `planSize`, `predictGrouping`
   with item 71's answer, and task 190's question by the rule of 3.6.

## 9. Outcome

### 9.1 Built, 3 October 2026

Step 2, behind `VarkaEmitOptions.planSize`, off by default until step 3.

**The plan.** `emit` builds the drivers alone before the class: a class of the two driver
methods and nothing else, whose calls name methods the class does not have, which the Class-File
API does not mind. From a table the driver is its calls, so what the drivers built alone measure
is what the full class's drivers measure, byte for byte, which `VarkaKernelPlanSuite` holds from
one group to four hundred. The plan is read for every grouping whose stages are not yet sized:
the first, and one a call-site rollback or a grouping fallback starts afresh, so no build is spent
finding a driver over.

* **Under the split driver**, a driver over the budget sets the stage size the loop used to read
  off the built class, so the first build is the second build the loop made: the same class
  byte for byte at 800 and 1200 ladder entries, 300 and 1500 one-output groups, and the forty
  compositions of wide draws. The audit counts the stages planned: 37, one for every shape past
  the ceiling (prediction 2).
* **Without the split driver**, the plan declines before any build, naming in
  `VarkaEmitDeclined.plannedCut` the outputs of the first groups whose calls fit the budget, by
  the arithmetic that sizes a stage. The compiler takes the cut in one step and asks for the
  prefix, which plans itself on its own grouping; a prefix whose driver is still over cuts once
  more, and after two cuts the bisection decides, as the last resort for the class-file caps the
  plan cannot read. On eight hundred ladder entries the loop bisects in more than ten emissions,
  each a class built and measured; the plan takes one emission that builds nothing and then one
  build a kernel. Its cut is the bisection's: the driver's bytes are exact, so the largest prefix
  one class serves is read rather than searched for. (The first build of this step kept the
  stage margin's bytes out of the cut too and landed a group early, 716 against 720; the review
  found it.)
* **The margins** are derived with the prices by `VarkaEmitCostFit.margins` and written into
  `VarkaEmitCostTable` by the same generator: the largest under-prediction the fit makes on its
  own groups in the band where each budget binds, methods of 2000 bytes and over for bytes and of
  62 call sites and over for sites. They came out at 21.1% and 17.7%, so a planned group closes
  at a predicted 6312 bytes and 76 call sites. The audit counts both directions: 4 held-out
  methods of 2000 bytes and over are under-predicted past the byte margin and none past the
  site margin, and the margins and the width rule below cost 18 loop methods over the corpus's
  28,404 with the prediction alone (28,422; the weights give 28,481), on twelve shapes the audit
  names.
* **Corrections.** A reaction to the planned build is counted and named in `VarkaEmitTrace`
  with the method, what the plan predicted for it and what it measured; the loop after it is
  unchanged. Before item 71's width rule the audit found nine corrections on three shapes, every
  one a call-site split of a group of heavy outputs the prediction already said was over the
  budget, exempt by its width of six, which a seventh output that added no node - a tree the
  group already held - took past the exemption after the prediction's last word. That is item
  71's second question, and the plan answers it by counting such an output toward the width:
  under `planSize` the prediction judges that step on its call sites alone - not on bytes, where
  splitting a duplicate off would only re-emit the tree it shares - and the group closes before
  it. With the rule, every one of the audit's 2259 shapes builds once and the audit lists no
  correction. The item's first question, what the predicted grouping costs at run time, is the
  benchmark's (6).
* **Item 74.2** of `SCOPE_MILESTONE_7.md` assumed this task would replace the size loop; it
  stays, as 3.2 planned, the last resort behind the plan.

**Files.** `VarkaLoopEmitter` (the plan, `driverAlone`, the corrections and the cut),
`VarkaEmitTrace` (the corrections, the stages planned and the planned declines),
`VarkaEmitDeclined` (`plannedCut`), `VarkaEmitOptions` (`planSize`), `VarkaEmitCostTable` and
`VarkaEmitCostFit` (the margins), `VarkaExpressionCompiler` (the cut before the bisection),
`VarkaEmitCostAudit` and `emit_cost_audit.json` (the planned arm and the margin counts),
`VarkaWideKernelBenchmark` (the planned arms, task 190's section and item 71's), and the tests:
`VarkaKernelPlanSuite`, two tests in `VarkaExpressionCompilerSuite`, one in
`VarkaSeveralKernelsSuite`. The IR fuzzer draws `planSize` as it draws every boolean. No
`VarkaKernelPlan` class: the plan is a few readings inside `emit`, where the grouping it needs
already is, and a class of its own would have carried them out and back for nothing.

**The review of this step** (3 October 2026) found seven problems, all fixed before the merge:
the cut kept the stage margin's bytes out of the driver where no stage exists, so every planned
split of several kernels landed a group early, and with it the plan declined off the exact
grouping where the loop's fallbacks would have fitted the greedy one (one fix: the cut reads the
driver's exact bytes against the whole budget, which is the bisection's prefix); a duplicate
output was judged on bytes as well as call sites, which could split the tree it shares (now
call sites alone); the test hook was a static read on the production path, invisible to the
shape key (now `misdescribeDriverBytes`, an option like the two `misdescribe` switches); the
plan re-read the driver after every regroup rather than for a grouping started afresh; and
three copies each of the scaling arithmetic and the analysis prologue (`fitGroups`, `analyze`).

### 9.2 The predictions, scored so far

1. **Held, beyond what it asked.** Over the audit's 2259 shapes the plan builds 2259 classes,
   one each, where the weights build 2343 and the prediction 2300. Three shapes built twice
   before item 71's width rule, all on the call-site exemption; with it, none, and none builds a
   third time (`VarkaKernelPlanSuite`).
2. **Stages.** Held: all 37 shapes past the ceiling build once under the split driver, each the
   two-build class byte for byte.
3. **Kernels.** Held in the count: the cut takes one emission that builds nothing and one build
   a kernel where the bisection took more than ten emissions for two kernels, and the cut is the
   bisection's prefix.
4. **Plan time.** On the laptop (`VarkaWideKernelBenchmark-jdk25-results.txt`, 3 October 2026,
   under `performance`): the split driver plans in 103 and 180 ms at 800 and 1200 entries where
   its two builds took 203 and 356, half as predicted; several kernels plan in 108 and 200 ms
   against 96 and 139 for their classes alone and 1316 and 2032 with the search - 12% and 44%
   over the classes, not the 5% predicted, the difference being the emission that declines
   before building and the prefix's own grouping. The runner's reading is step 3's.
5. **No class moves** where the first grouping built once: the planned class is the loop's,
   byte for byte, on every shape of the plan suite; the audit's digest of first groupings moves
   only where the margins or the width rule close a group.
6. **Item 71, on the laptop.** The predicted and the planned grouping run within the control's
   spread of the weights' on the mixed family (69 against 70 and 69 ms) and the ladder (255 and
   260 against 255 and 258); on 64 cheap tails, where the prediction closes three loop methods
   for the weights' four and the plan closes four again, both read 7 ms against the weights' 6.
   The runner decides the default.
7. **Task 190's question, on the laptop.** Several kernels planned beat the split driver planned
   by 2% at 800 ladder entries (521 against 533 and 530 ms) and 7% at 1200 (783 against 839 and
   838), more than the control's spread; on sixty-four dates by field they lose by 3 to 14% (66
   against 64 and 58 ms null-free, 65 against 59 and 57 with nulls), the one class loading each
   date's prefix where the second kernel decomposes 64 dates again. As predicted in direction;
   the rule of 3.6 reads the runner.
