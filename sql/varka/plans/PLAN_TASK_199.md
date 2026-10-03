# Task 199: Method bytes predicted before emission

*Planned 30 September 2026 (milestone 6 row 199), with its admission check run the same morning.*

## 1. Where this came from

Milestone 6 row 199, from `READING_MILESTONE_6.md` section 4. The emitter builds a class, measures
every method and regroups when one is over a limit, so a wide kernel is emitted once per regroup.
The row proposes a model of each method's bytes fitted from its node counts on the IR fuzzer's own
random shapes, as Halide's autoscheduler trains its cost model on random programs, with the regroup
choosing from the prediction so the common case emits once and the built class keeping the last
word. Tasks 191, 172 and 219 each point here: 191 as what would remove the rebuilds its cheap
emissions still pay, 172 as the cost task 200's exact grouping needs, and 219 as the alternative to
reading the JDK's refusal. The owner asked for this plan on 30 September 2026.

Two things have changed since the row was written. Task 191 made an emission cheap - a
four-hundred-output kernel in 30.5 ms (`PLAN_TASK_191.md` 9) - and task 209 added a second limit
read off the built class, 93 Vector API call sites per group method, which a model of bytes alone
would not see.

## 2. The admission check, done

Both halves ran on master at `5431e94230b` through a scratch suite and a temporary counter in the
emit loop, neither committed.

**How often the regroup rebuilds.** Every shape emitted at the default options, counting builds and
what forced each regroup:

| corpus | builds per emission | regroups forced by |
|---|---|---|
| the oracle's 10000 int-lane fuzz shapes | 1 for all | nothing |
| the oracle's 10000 long-lane fuzz shapes | 1 for all | nothing |
| the size ladder at 16, 54, 100, 200 and 400 entries, both widths | 1 | nothing |
| 12 and 60 `make_date` outputs, both widths | 1 | nothing |
| 22 cheap tails `year(d) + k`, both widths | 2 | the call-site budget, once |
| 64 cheap tails, both widths | 3 | the call-site budget, twice |

Each whole emission of the cheap tails took under 3 ms. At the defaults the rebuilds the row set
out to remove are a few milliseconds on one shape family, once per shape per JVM; the byte budget
forced no regroup anywhere, because the weights already keep every group of these shapes under it.
Rebuilds on the byte budget occur under the budgets the fuzzer and the suites set far below 8000,
and on single heavy outputs, which no prediction splits.

**Whether bytes can be predicted from node counts.** For the 9963 fuzz shapes, of both lanes, that
emit one loop-method group, a least-squares fit of each method's bytes and Vector API call sites on
the count of each node kind, the lane and the output count, trained on the even-numbered shapes and
tested on the odd:

| predicted | median error | 90th percentile | 99th percentile | within 10% |
|---|---|---|---|---|
| dense loop bytes | 6.5% | 17.0% | 27.4% | 66% |
| masked loop bytes | 8.4% | 23.8% | 87.9% | 57% |
| dense loop call sites | 11.6% | 40.6% | 73.2% | 45% |
| masked loop call sites | 17.8% | 64.0% | 121.2% | 32% |

On the 241 masked loop methods of 1500 bytes or more the error is 6.2% at the median and 17.3% at
worst, and the fit under-predicts 181 of them. The corpus's largest masked loop is 4552 bytes:
the grammar draws one to three roots, so no fuzz shape reaches the 8000-byte budget, which is the
only size at which a byte prediction decides anything.

**What the check rejects.** Task 199 as a speed-up of emission: at the defaults there is almost
nothing to remove. And the row's model as stated: a regression on node counts, fitted to shapes an
order of magnitude below the budget, errs low on the large methods it would be asked about, and
low is the direction that makes a method measure over the limit and rebuild anyway.

**What it admits.** The model's second use, the one task 200 and `SCOPE_MILESTONE_8.md` item 63
need: a cost for a candidate group that is cheaper to ask than an emission, in the units the limits
are read in. Task 200's dynamic program asks for the cost of every contiguous run of outputs,
about n * n / 2 of them, which at 400 outputs is 80000 emissions and out of reach at any emission
speed. So the task is re-scoped: a cost model of bytes and call sites per group, measured for
accuracy where the limits bind, and used first where it is cheapest to check - to form the first
grouping.

## 3. The design

### 3.1 Two models, measured against each other

* **A. The regression**, as the row states it: bytes and call sites of each group method as a
  linear function of the count of each node kind in the group, fitted on a training corpus and
  stored as a table of coefficients.
* **B. The register**: each node kind's emitted bytes and call sites measured by emitting it alone
  and beside a partner, the way `VarkaEmitterBudgetSuite`'s calendar register pins
  `CHRONO_PREFIX_WEIGHT` and the tails, summed over a group's distinct nodes with a shared
  calendar prefix counted once - `GroupOps`'s accounting, in bytes and call sites instead of
  weights - plus a fixed cost per method for the prologue, the loop and the stores. It is the
  register item 63 asks for, and it needs no fitting.

Both are built as `VarkaEmitCost`, one class with one method per model that takes a candidate group
and returns predicted bytes and call sites for its dense and masked loop and epilogue methods, and
neither changes an emission in this step.

### 3.2 A corpus at the budget

The fuzz grammar's shapes are too small to test a prediction at 8000 bytes. The audit corpus adds
shapes built to reach the limits: the size ladder up to 400 entries, the `make_date` and cheap-tail
ladders, the corpus shapes `VarkaEmitterBudgetSuite` uses, and a wide-draw variant of the grammar
that draws 20 to 200 roots per shape. Every group of every shape is emitted under a byte budget high
enough that nothing regroups, so each measured method is one group's own.

### 3.3 The audit, committed

`VarkaEmitCostAudit` emits the corpus, predicts each group method with A and B, and writes predicted
against measured to a committed results file with its provenance: the error distribution per model,
method kind and size band, and the share under-predicted. It is not a timing, so it runs on any
machine; it is the file every later claim about the model quotes.

### 3.4 The first grouping, behind a switch

`VarkaEmitOptions.predictGrouping`, off by default: `groupOutputs` closes a group when the chosen
model predicts it past the byte budget or, for a group of more than `HEAVY_GROUP_OUTPUTS` outputs,
past the call-site budget, instead of when its weights pass `GROUP_BUDGET`; the emit loop's
measurement and regroup stay as they are and remain the last word. Fuzzed the day it lands, as every
option is.

### 3.5 What is deliberately unchanged

* The default grouping and every emitted byte: the switch is off, and `emitted_bytes.json` gains an
  option arm for it only.
* The measurement of the built class, the regroup, and the declines (tasks 87, 168, 169, 209, 219).
* The weights in `VarkaEmitBudget` and whether they survive: item 63's, which this task's register
  informs.
* The exact grouping: task 200's, which takes whichever model this task chooses as its cost.

### 3.6 Registered op counts

None: no lowering changes.

## 4. Files

| file | what |
|---|---|
| `VarkaEmitCost.java` | models A and B |
| `VarkaEmitOptions.java`, `VarkaLoopEmitter.java` | `predictGrouping`; `groupOutputs` asks the model under it |
| `VarkaEmitCostSuite.scala` | the register, pinned against emitted counts; the model's contract |
| `VarkaEmitCostAudit.scala` and its results file | the audit of 3.3 |
| `VarkaIrGrammar.scala` | the wide draw for the audit corpus |
| `emitted_bytes.json` | the option arm for `predictGrouping` |
| `PLAN_MILESTONE_6.md` | row 199 |

## 5. Tests, and what each is for

* **Every entry of B's register equals what its node emits alone**, and beside its partner adds
  exactly its tail: a lowering change fails here and names the entry to recount.
* **A and B are total over the IR**: every node kind the grammar draws has a coefficient and a
  register entry, so a new node cannot be priced at zero by omission.
* **Under `predictGrouping` every shape answers as the reference evaluator does**, through the
  fuzzer's option draw; the switch cannot change a result, only which outputs share a method.
* **Under `predictGrouping` a shape emits no more loop methods than under the weights** on the
  audit corpus, or the difference is listed and explained in section 9.

## 6. The measurement

The audit of 3.3 decides between A and B. The switch is measured by the audit corpus's builds per
emission and loop methods per shape, with and against it, and by emission time on the size ladder
at 400 entries and the cheap tails at 64, the shapes that rebuild or are widest; the time is a
laptop A/B for the plan's record, not a published number.

### 6.1 Predictions, registered before the run

1. **B beats A where the limits bind.** On group methods of 2000 bytes or more, B's error is under
   5% at the 99th percentile and A's is not.
2. **A stays biased low** on the large methods, as in section 2; B, built from emitted counts, errs
   in neither direction by more than its per-method fixed cost.
3. **Under `predictGrouping` with B, every shape of the audit corpus emits in one build**, the
   cheap tails included, and no shape gains a loop method.
4. **Emission time moves by less than 10%** on the size ladder at 400 entries: the model is cheaper
   than a build but is asked once per output as the grouping walks.

## 7. Risks

1. **Sharing makes costs non-additive.** CSE, a shared prefix and whole-node reuse make a group's
   bytes less than the sum of its nodes'; B counts distinct nodes and one prefix per date, as
   `GroupOps` does, and the audit's error by shape family shows where that is not enough.
2. **The call-site limit depends on the shape, not only the count** (`SCOPE_MILESTONE_8.md` item
   61): a prediction exact in call sites can still disagree with C1 on a mask-heavy group. The
   measurement stays the last word, so a miss costs a rebuild, not a wrong method.
3. **The register goes stale** when a lowering changes, as the weights did (task 148). The suite
   pins every entry against emitted counts, so it fails instead.

## 8. Sequencing

1. This plan, with row 199 marked Planned.
2. The audit corpus, the audit and models A and B, with the register pinned: no emission changes.
3. The audit's results file, and the choice between A and B scored against 6.1.
4. `predictGrouping` behind the switch, fuzzed, with its option arm in the oracle.
5. Section 9, and the chosen model handed to task 200 and item 63.

## 9. Outcome
### 9.1 Built, 30 September 2026

`VarkaEmitCost` describes a candidate group by counts of features: a fixed cost per method and per
output, a price for every distinct node keyed by its kind together with whatever chooses its
lowering (lane, operator, overflow mode, divisor sign, truncation level, `make_date`'s failure
mode), and the calendar prefix counted once per date, once more when a tail reads the month, or as
a load where an earlier group materializes it. It is the accounting `GroupOps` does in weights,
done in the units the limits are read in. Both models price the same features, so the audit
compares two ways of pricing them rather than two feature sets:

* **B, the register**, is read off emitted classes one feature at a time: a shape is emitted as one
  group, then again with one root added whose new features all have prices but one. The
  difference, less the priced features' share, is that one feature's price. Each node kind is
  added over its own children at up to nine of its occurrences in the fuzz sequences, and its
  price is the median reading.
* **A, the regression**, is a least-squares fit of the same features over the even-numbered fuzz
  and wide shapes. It uses a small ridge, and the register's probes are added as rows so that a
  kind the corpus rarely draws still gets a price.

Both tables are generated Java. `VarkaEmitCostTable`, in the emitter, holds the chosen prices;
`VarkaEmitCostRegister`, beside the suites, holds the other, which only the audit reads.
`VarkaEmitCostSuite` derives both from emitted classes and fails when either committed file
differs, so a lowering change that moves a price names the feature, the way the weights' register
does. Catalyst has no main resources directory, which is why the tables are Java source rather than
a data file. (As first built both tables sat in the emitter, 86 features each; 9.5 says what the
review changed.)
`VarkaIrGrammar` gains a wide draw of 20 to 200 roots per shape on seeds of its own, so no
committed corpus moves. `VarkaEmitCostCorpus` holds the corpus of 3.2. `VarkaEmitCostAuditSuite`
writes `sql/varka/emit_cost_audit.json`. That file scores both models on shapes neither was derived
from (the odd-numbered fuzz and wide shapes and every ladder), each under its default grouping and
under groups up to the fused ceiling, at sixteen int lanes.

`VarkaEmitOptions.predictGrouping`, off by default, makes `groupOutputs` also close a group where
the chosen model predicts the next output would put a method over the byte budget, or, for a group
wider than `HEAVY_GROUP_OUTPUTS`, over the call-site budget. The emit loop is unchanged except for
one fallback: a class the predicted grouping would make decline is built again with the weights
alone. (As first built `emitted_bytes.json` also gained a `predictGrouping=true` arm; 9.5 says why
the review removed it.)

**Three departures from section 3, made while building.**

* 3.4 said the prediction closes groups "instead of" the weights. It closes them *as well as* the
  weights. `GROUP_BUDGET` bounds a group for register residency and C2's budgets, which is a speed
  question and not a size one. Replacing it would make every group as wide as 8000 bytes and
  change steady-state speed, which 3.5 holds unchanged.
* The fallback is new. Without it the switch made two wide long-lane shapes decline that the
  weights emit: the predicted grouping closes groups the weights keep, each group is one more call
  in the driver, and on those shapes the driver went past the byte budget. The call-site budget
  already had the same rebuild for the same reason.
* The audit is a suite that writes a committed file, not a separate tool, so the file cannot drift
  from the code; `VarkaEmitCostAudit.scala` in section 4 became `VarkaEmitCostAuditSuite.scala`.

### 9.2 The audit

From `sql/varka/emit_cost_audit.json`: the error of each method's predicted bytes against its
measured bytes, as a percentage of the measured, over 53550 held-out group methods.

| model | methods | median | 90th percentile | 99th percentile | worst | under-predicted |
|---|---|---|---|---|---|---|
| A, 2000 to 7999 bytes | 7132 | 2.2% | 10.4% | 12.4% | 33.8% | 44% |
| A, 8000 bytes and over | 161 | 1.8% | 3.6% | 4.3% | 4.5% | 90% |
| B, 2000 to 7999 bytes | 7132 | 7.8% | 54.2% | 69.5% | 98.0% | 14% |
| B, 8000 bytes and over | 161 | 51.2% | 57.5% | 63.7% | 64.3% | 0% |

For call sites on methods of 2000 bytes or more, A is 1.7% at the median and 8.7% at the 99th
percentile, and B is 2.5% and 32.0%. **A is the chosen model**: its prices are
`VarkaEmitCostTable`, and B's moved to `VarkaEmitCostRegister` for the audit alone (9.5).

**Why the register fails where it is needed.** B over-predicts large groups by about half, and the
size ladder, a group of many calendar outputs over one date, shows it most (45.8% at the median).
A node priced beside only its own children pays for validity and mask code that a wide group
shares between its nodes. The comparisons show it: across its operators and lanes, a `Compare` is
priced at 15 to 21 bytes in a dense loop and at 45 to 220 in a masked one. Every price in the
register is therefore a node's cost at the start of a group, and a wide group is not at its start.
The regression is fitted on groups of every width, so it prices each feature at its average share.
Its worst errors are on small methods, under 500 bytes, where no limit binds.

### 9.3 The predictions, scored

1. **Wrong, and the other way round.** B does not beat A. On methods of 2000 bytes or more, A's
   99th percentile is 12.4% and B's is 69.5%. The plan expected the register to be exact because
   each entry is measured exactly, and the entries are exact; it is their sum over a wide group
   that is not (9.2).
2. **Wrong for A; also wrong for B.** A is not biased low once its training corpus reaches the
   limits: it under-predicts 44% of the methods from 2000 to 7999 bytes. Above 8000 bytes it
   under-predicts 90% of them, but by 1.8% at the median. The bias section 2 found came from
   fitting on methods an order of magnitude below the budget. B errs high, not within its fixed
   cost: it under-predicts none of the methods of 8000 bytes or more.
3. **Held for builds, with two exceptions for methods.** Under the switch with A, every shape the
   emitter keeps emits in one build: 2000 of 2000 fuzz shapes, all ladders, and the cheap tails at
   22 and 64 (one build where the weights took two and three, and 5 loop methods where they took
   6). The fuzz shapes are the held-out 1000 of each lane. The exceptions are the two wide long-lane shapes of 9.1, which the fallback rebuilds.
   Loop methods fall overall (2839 against 2848 on the held-out wide long-lane shapes, 2870 against
   2873 on the int ones). Two wide shapes gain one each, wide int 120 (31 to 32) and wide long 95
   (40 to 41), for one reason. Under the weights one group measured over the call-site budget and
   the regroup halved it: 9 outputs into 4 and 5, and 11 into 5 and 6. The greedy close fills the
   first group to the heavy-group limit of 6, and the outputs left over then break into two groups,
   because the prefix they shared was in the group they left. Halving happens to split nearer the
   middle than greedy filling does. `VarkaEmitCostSuite` pins both shapes, so a third is noticed.
4. **Held.** On the 400-entry size ladder with the byte budget out of reach, where the model is
   asked at every output and never closes a group, one emission took 41.2 ms against 40.9 ms with
   the weights alone (best of 47 and 49 iterations), 0.8% more. The cheap tails at 64, which the
   switch builds once where the weights built three times, took 0.69 ms against 1.85 ms. These are
   laptop readings from the new section of `VarkaEmissionBenchmark`, "grouping by prediction",
   taken on this plan's day with the fuzz campaign finished (AMD Ryzen AI 9 HX PRO 370, JDK 25).
   They are for this record, not a published number, and the committed results file gains the
   section at its next regeneration on a runner.

**Fuzzed.** `VarkaIrFuzzSuite` draws the switch like every boolean option. Eight fresh seeds
(19930001 to 19930008) at 20000 iterations on both lanes passed, and so did
`VarkaCoverageCompositionFuzzSuite` at 2000 compositions. The fuzz shapes are too narrow for a
prediction to close a group, and `VarkaEmitCostSuite` shows it: under the switch the first 400
shapes of each lane emit byte for byte as without it. So the suite also checks the switch against
the reference evaluator where it does bind: the 64 cheap tails at the int lane, and forty outputs
over one long-lane division that the call-site budget splits.

### 9.4 What the task leaves

* **For task 200:** A is the cost to use. It prices a candidate group from its features alone,
  so the dynamic program can ask for every contiguous run of outputs. It also removes the greedy
  close of 9.3, which is task 200's own subject: an exact partition would have found the halving.
* **For `SCOPE_MILESTONE_8.md` item 63:** a register of per-node costs measured alone does not
  predict a wide group (9.2). If the weights are to be replaced by measured costs, the register
  should be read in context or fitted, as A is.
* **Not done:** the switch stays off. It changes no default and no emitted byte, and flipping it
  is a decision for task 200, which will use the same prediction. A producer group does not know
  at grouping time whether a later group will load its prefix, so the six vector stores that
  materialize a prefix are not counted in the producer's cost.

### 9.5 The review, 30 September 2026

A code review of the first build found ten problems. The owner asked for all of them to be
addressed, and asked separately why the test helpers were Scala: new Varka code is Java unless a
ScalaTest base forces Scala. The corpus, the fit and the audit are now Java under
`sql/catalyst/src/test/java` (`VarkaEmitCostCorpus`, `VarkaEmitCostFit`, `VarkaEmitCostAudit`, the
last the name section 4 planned), and the two suites are thin Scala over them. No figure in 9.2 or
9.3 moved.

1. **`NarrowLane` had no price.** It sits at the root of every TIME kernel, the fuzz grammar never
   draws it, and the coverage test trusted the grammar, so TIME kernels were silently unpredicted.
   It is priced on its root form, and the test now enumerates every feature from the IR's own
   enums, keeps the variants the emitter accepts, and fails for any IR record type no feature
   names. The grammar's own gap is milestone 6 row 235.
2. **The prediction checked call sites after the emit loop had dropped the call-site budget.** The
   grouping now reads the budget in force.
3. **A decline naming one output over the budget was rebuilt under the weights**, which cannot
   change it. The fallback now skips it. A decline of the driver is still rebuilt, since only the
   weights' grouping can tell whether fewer groups fit, and the audit now pins what that costs: of
   the held-out wide shapes that decline either way, the int ones take 94 builds under the switch
   against 47, and the long ones 95 against 55.
4. **The oracle's `predictGrouping` arm could never move**, since no oracle shape nears a budget.
   It is removed, and `emit_cost_audit.json` instead carries a digest of the first groupings the
   prediction forms per shape family, which moves whenever the prices regroup a shape.
5. **The audit assumed the build kept the first grouping.** The emitter now counts its builds
   (`emitCountingBuilds`); a shape built more than once is left out of the fit and the audit rather
   than paired with the wrong group, and only a decline is caught, so an emitter bug on a corpus
   shape fails the suite.
6. **The audit's "one build" was inferred from loop counts.** It is now the build count.
7. **The features were counted by a second walk beside the grouping's, and the audit rebuilt the
   groups a third way.** The counting is now fed by `GroupOps`' own walk, so it follows the
   grouping's sharing exactly - which also corrects an unshared prefix, now counted per calendar
   node as the emitter emits it - and the suites read those very tallies (`talliesForTest`).
8. **The prediction was re-summed over every feature at each output.** It is a running total.
9. **The suites' cost** was estimated at minutes. Measured, the table derivation takes 6 s and the
   audit 12 s, so both stay in the default run.
10. **The class doc named a class that did not exist.** `VarkaEmitCostAudit` now exists.

