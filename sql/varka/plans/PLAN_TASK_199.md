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

**What it admits.** The model's second use, the one task 200 and `SCOPE_MILESTONE_7.md` item 63
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
2. **The call-site limit depends on the shape, not only the count** (`SCOPE_MILESTONE_7.md` item
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
