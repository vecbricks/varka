# Task 169: no exception escapes the emitter

## 1. Where this came from

Milestone 6 row 169, section 2.3 of `PLAN_MILESTONE_6.md`, and the milestone's
first "done when": *no shape Varka admits can fail to emit; a shape the caps
decline is declined at compile time with a reason rather than throwing and
degrading silently*. The ghost-fallback contract in `sql/varka/AGENTS.md` keeps
the answers right when a kernel fails - the row engine takes over - but a
failure at emission is the expensive kind: it happens on the executor, once per
task, after EXPLAIN has already claimed the projection fused.

Task 87 made that failure reachable by design. Its byte budget, the default
since step 6b, declines a shape no regroup can fit with `VarkaEmitDeclined`,
which names the method, its bytes, the budget and the outputs to blame
(`PLAN_TASK_87.md` 9.4). `VarkaExpressionCompiler` admits outputs by the weight
caps alone (`VarkaLoopEmitter.fitsBudgets`) and never asks about bytes, so a
heavy single output - a balanced `greatest` over thirty-two `add_months(d, k)`,
whose masked loop method reads 25629 bytes - is fused at plan time and declined
on every task. The review of step 6b added a stopgap: the shape cache remembers
the decline and the evaluator logs it once per JVM (`PLAN_TASK_87.md` 9.7). This
task removes the need for it.

## 2. The admission check, done

Three things had to be true for the design below to be worth building, each
checked on master `ec84698b4b2`, 24 September 2026.

**2.1 The throw census.** Every `throw new IllegalArgumentException` in the
emitter package, sorted by who can reach it:

| class | sites | reachable from a compiled plan? |
|---|---|---|
| The structural caps: `MAX_CHAIN_DEPTH` (`Analysis` 324), `MAX_FUSED_NODES` (824), `MAX_INPUTS` (`VarkaLoopEmitter` 346), mixed lanes (`VarkaLoopEmitter` 249, `Analysis` 628) | 5 | No: the compiler asks `fitsBudgets` and its lane rule before admitting an entry, and says why in `VarkaDecline` |
| The byte budget: `VarkaEmitDeclined` (`VarkaLoopEmitter` 425) | 1 | **Yes.** Nothing at plan time asks about bytes. This is the gap |
| IR contract violations: a condition in a value position, a narrowing below the root, an ordinal outside its table, a guard with the wrong bounds, a malformed `trunc` level, `IntNeg` in NULL mode, a division by -1, a dividend bound its guard does not deliver, an `IsNotNull` over a non-column, an offset or months operand of the wrong kind, `WeekOfYear` over a non-`ThursdayOf` (`Analysis` 435 to 928, fourteen sites), a NULL-mode mask in a dense body (`VarkaVectorWalk` 514), and the IR records' own constructors | ~30 | Not by design: the compiler's lowering never builds these, and each is a compiler bug if it fires. They stay exceptions, caught by the ghost fallback, and are not declines - a decline is a shape the compiler may legitimately ask for |
| Width-dependent: a constant division with no double species to convert through (`Analysis` 773) | 1 | No: `Divider.of` answers "no species" only for the int lane at one lane, a width no JVM offers |
| Option validation (`VarkaEmitOptions`, `VarkaShapeKey`) | 10 | No: construction-time, from code, not from a plan |

So the task is one gap, not a family of them: the byte budget. The IR contract
violations need a check that the compiler never produces them, which is a test
(section 5), not a design change.

**2.2 A size decision taken on the driver holds on the executor.** Planning
runs on the driver, whose vector width may differ from the executor's. Measured
with `dev/varka_emit.sh --options methodByteBudget=0,lanesOverride=<w>` on the
balanced `greatest` tree, masked loop method in bytes:

| leaves | 4 lanes | 8 lanes | 16 lanes |
| ---: | ---: | ---: | ---: |
| 8 | 5748 | 5749 | 5749 |
| 12 | 9134 | 9135 | 9135 |
| 16 | 12492 | 12493 | 12493 |

One byte, from a constant push. A shape within a byte of the budget could be
judged differently by two widths; nothing else can. The design takes the byte
as a margin rather than emitting at every width.

**2.3 Asking the emitter costs one emission per shape per JVM.**
`VarkaEmissionBenchmark` prices an emission without definition at 17361.9 ns
for a copied column to 78092.8 ns for four calendar outputs over one date, and
with definition at 29299.6 to 97922.4 ns
(`VarkaEmissionBenchmark-jdk25-results.txt`). That is per *emission*. The
compiler runs more often than once per shape: `compilePartial` is called by
`VarkaColumnarRule` at planning, by both exec nodes, by `VarkaFusionReport` for
EXPLAIN, and by `VarkaKernelEvaluator` once per task on the executor. So the
check has to go through the shape cache, where a second ask is a hit, rather
than call the emitter directly - which would put an emission on every task, the
very cost this task exists to remove.

What the check would have rejected: a design that estimates bytes from weight
at plan time. Weight is what task 87 found does not bound size; the estimate
would reintroduce the disagreement between the admission and the emission.

## 3. The design

### 3.1 The compiler asks the shape cache

`compilePartial` keeps admitting entries by weight, as today, and then asks for
the kernel the admitted outputs make, through `VarkaShapeCache.getOrEmit` with
the same `VarkaShapeKey` the executor will build (outputs, inputs, literals and
the session's emit options). On a hit or a clean emission the plan stands, and
the class the executor will want is already in that JVM's cache. On a
`VarkaEmitDeclined`:

1. **Named outputs** - the ones whose own group cannot fit - are demoted to
   residual with the decline's reason in their `VarkaDecline`, which EXPLAIN
   already prints per entry, and the rest are asked again.
2. **No named output** - a class-wide limit, a driver over the budget - demotes
   the last-admitted fused output with the class-wide reason and asks again.
   The driver grows with the number of outputs, so removing outputs is what
   shrinks it; the last-admitted is the deterministic choice.
3. **Every output demoted** means the projection is not fused at all, which is
   `compilePartial` returning `None`, as for any projection nothing of which
   compiles.

Each round removes at least one output, so the loop ends within the number of
outputs; each round is one emission, and at the production limit the ladder
measured in task 87 never takes a second.

The filter path (`compilePredicate`, conjuncts AND-folded into one condition)
asks the same way. A conjunct cannot be split out of a folded condition by the
emitter's naming, since the fold is one output, so a declined condition demotes
its last-admitted conjunct and asks again.

The emit options have to reach the compiler for the key to match: today they
live in the exec node (`emitUseAVX`) and a test hook
(`VarkaColumnarToRowExec.currentEmitOptions`). `compilePartial` gains an
`options` parameter, defaulting to the session's, and every caller passes what
its evaluator would.

### 3.2 What is deliberately unchanged

* The IR contract throws stay exceptions (2.1). The ghost fallback keeps
  catching them; a test proves the compiler does not produce them.
* The shape cache's decline memo and the evaluator's log-once (`PLAN_TASK_87.md`
  9.7) stay as the last resort, now unreachable for size from a compiled plan.
* The weight caps stay the first admission test: they are cheap, and they
  bound the IR before anything is built.
* Whether 8000 is the right limit is task 170's.

### 3.3 Registered op counts

None: the emitted classes do not change. `emitted_bytes.json` is unmoved.

## 4. Files

| file | what |
|---|---|
| `VarkaExpressionCompiler.scala` | ask the cache after admission; demote named outputs, or the last-admitted, with the reason; the same for conjuncts; an `options` parameter |
| `VarkaColumnarRule.scala`, `VarkaProjectExec.scala`, `VarkaColumnarToRowExec.scala`, `VarkaKernelEvaluator.scala`, `VarkaFilterEvaluator.scala`, `VarkaFusionReport.scala` | pass the emit options through |
| `VarkaExpressionCompilerSuite`, `VarkaProjectExecSuite`, `VarkaFilterExecSuite` | section 5 |
| `PLAN_MILESTONE_6.md` | row 169 |

## 5. Tests, and what each is for

* **A heavy single output is declined at plan time with its reason.** The
  balanced `greatest` over thirty-two `add_months`: EXPLAIN's fusion report
  names the output residual with "over the method budget", the query's answers
  equal the row engine's, and `numEmissionFailures` is 0 - the property the
  task exists for, read from the metric the executor path counts.
* **The rest of a projection still fuses.** The heavy output beside
  `year(d)` and `month(d)`: those two fused, the heavy one residual.
* **A class-wide decline demotes the last-admitted output.** Reached with a
  small budget through the options parameter, since the production limit keeps
  every driver the IR caps admit far under 8000 (`PLAN_TASK_87.md` 9.4 measured
  2806 bytes at sixty outputs).
* **A filter whose folded condition is over the budget** demotes its
  last-admitted conjunct and still filters correctly.
* **The compiler never produces an IR contract violation.** Every coverage row
  of `VarkaExpressionCompilerSuite` compiled and emitted at both widths with no
  `IllegalArgumentException` - the census of 2.1 turned into an assertion
  rather than left as a reading of the code.
* **A second plan of the same shape is a cache hit**, so the check costs one
  emission per shape per JVM, as 2.3 requires.

Section 2.3 of the milestone asks for "a fuzz campaign that produces declines
and no emission failures". `VarkaIrFuzzSuite` draws IR directly and never goes
through the compiler, so it cannot observe the compiler admitting anything; the
coverage sweep above is the compiler-level form of that check, and the fuzzer
keeps checking what it checks today. The deviation is recorded here rather than
papered over.

## 6. The measurement

None of throughput: the kernels do not change. Planning cost is registered
instead:

### 6.1 Predictions, registered before the run

1. **Planning a projection costs at most one extra emission per shape per
   JVM**, and none on a second plan of the same shape: at most 97922.4 ns on the
   emission benchmark's widest shape. Measured by planning the ladder of
   `PLAN_TASK_87.md` twice in one session.
2. **No emission failure is counted** in the Varka suites under `sql/`, where
   today the heavy shapes of task 87's tests would count one per task.

## 7. Risks

1. **The compiler's key and the executor's key differ**, and the check emits a
   class the executor never asks for. The options parameter is the guard, and
   the cache-hit test of section 5 catches a mismatch: the executor's lookup of
   a planned shape must be a hit in local mode.
2. **A shape within a byte of the budget** could be admitted on one width and
   declined on another (2.2). The compiler asks against the budget less the
   widest difference measured, so the driver's answer is conservative.
3. **A plan-time emission can throw something other than a decline** - an IR
   contract violation that no coverage row reaches. It is caught at plan time
   exactly as it is on the executor today: the projection falls back, with the
   exception's message as the reason, and the census test gains the row.

## 8. Sequencing

1. This plan, and row 169 marked Planned.
2. The coverage sweep of section 5 alone: the compiler produces no IR contract
   violation at either width. No behaviour change; it pins the census first.
3. The options parameter through every caller of `compilePartial`. No
   behaviour change.
4. The compiler asks the cache and demotes, for projections and filters, with
   the tests of section 5 and the predictions of 6.1 read.
5. Row 169 done; `PLAN_TASK_87.md`'s note of what it left, answered.

## 9. Outcome

<!-- Filled in when the work lands. -->
