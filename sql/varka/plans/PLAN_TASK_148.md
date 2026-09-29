# Task 148: The weight the budget counts is wrong for a division

*Planned 29 September 2026 (milestone 6 row 148), with its admission check run the same day.*

## 1. Where this came from

Milestone 6 row 148, first recorded as milestone 5's section 2.84 on 19 September 2026 and
carried into milestone 6's section 2.2a on 23 September. `VarkaEmitBudget.weightOf` prices an
int-lane `ConstDivide` at the default weight of 1, and the row put its emitted form at seven
lane operations. Two later tasks changed the row's premise without closing it:

* **Task 149, on 20 September**, made the multiply-high form the int lane's default. The
  seven-operation conversion through double lanes is now the reference arm, selected by turning
  `mulHiDivide` off.
* **Tasks 87 and 168, on 24 September**, put every emitted method, the epilogue included, under
  the byte budget, and task 209 added the call-site budget beside it. An under-counted division
  can no longer produce an unbounded method, which is what section 2.84 worried about. What it
  costs now is a group the regroup has to split after the class is built, or a group heavier
  than the budget meant.

On 29 September 2026 the owner approved a narrow scope, in these words: "Weight the division at
the larger of its two forms, as the long lane already does. Pin that weight to the emitted count
with a test, and add a shape with several outputs. Regenerate the oracle and fix the stale
comment. Leave the question of what weights are for to item 63." Section 2.2a's second question,
whether weights should bound size at all now that bytes and call sites are measured, is
therefore `SCOPE_MILESTONE_7.md` item 63's.

## 2. The admission check, done

**What each form emits.** `dev/varka_emit.sh "extract(YEAR FROM ym)" --columns ym:ym`, whose IR
is `(divc:12 col:0)`, counted in `loopDense0`:

| form | `IntVector` | `LongVector` | `DoubleVector` | `convertShape` | the division's own |
|---|---:|---:|---:|---:|---:|
| multiply-high, the default | 5 | 4 | 0 | 4 | 11 |
| conversion, `mulHiDivide` off | 3 | 0 | 2 | 4 | 7 |

The division's own count is the body's less the column's load and the output's store, which are
two `IntVector` calls. Read from `emitMulHiDivide`: each half widens, multiplies, shifts and
narrows (eight operations), then an `or`, a shift and an `add` finish the quotient; a negative
divisor adds a multiply by -1. The conversion form's count does not depend on the divisor's
sign. So the larger form is the multiply-high one: 11, and 12 for a negative divisor. The long
lane already follows this rule, weighing its division at the magic form's 14 where its
conversion form is 3.

**The metric.** The count has to include every vector type. The test base's `laneOps` counts
`IntVector` calls alone, and reads the multiply-high division as three operations, since eight of
its eleven run on `LongVector` and on `Vector` itself. The long lane's 14 is counted the same
way, across types.

**Where the weight reaches.** `weightOf` has one caller, the `GroupOps` that `groupOutputs` uses
to form loop-method groups, so a kernel with one output cannot change. The only int-lane
`ConstDivide` the compiler builds is `extract(YEAR FROM ...)` over a year-month interval, which
divides by twelve (`VarkaIntervalCompiler`). A projection of that over four interval columns
emits one loop method today, with the four divisions' 16 `LongVector` calls and 16 conversions
in `loopDense0`.

**The oracle.** `emitted_bytes.json`'s coverage rows hold the division as `extract(YEAR FROM ym)`
and `extract(YEAR FROM ym) - 1`, both single outputs. Its int-lane fuzz sequence draws divisions
by 2, 3, 7, 12, 100, -3 and -12. A scratch dump of that sequence at its fixed seed, not
committed, finds 1946 of its 10000 shapes holding an int-lane division, 1334 of them with more
than one output. Those 1334 are the only shapes the change can move.

**What the check would have rejected:** a weight of 7, the row's number. It is the reference
arm's count, and it under-counts the shipped form by four operations.

## 3. The design

### 3.1 The weight

`weightOf` weighs an int-lane `ConstDivide` at `INT_CONST_DIVIDE_WEIGHT`, 11, plus one for a
negative divisor. The long lane's constant, `CONST_DIVIDE_WEIGHT`, is renamed
`LONG_CONST_DIVIDE_WEIGHT` so that each lane's constant names its lane.

No option switch. A weight is not a lowering: it decides which outputs share a loop method and
changes nothing a division emits, and the long lane's weight went in the same way. The oracle
pins the groupings, and the new test pins the weight to the count.

### 3.2 The stale comments

* `weightOf`'s note on the division says the epilogue is "the method no byte budget bounds",
  which has not been true since task 87, and that the int lane keeps a weight of 1. Both go.
* `weightOf`'s javadoc says `MAX_FUSED_NODES` "still counts nodes, so a projection may fuse as
  many calendar fields as it likes". Since task 190 the op cap applies only with the byte budget
  off, and under the budget a kernel is bounded by bytes. The sentence is corrected.

### 3.3 What is deliberately unchanged

* Every other weight, `GROUP_BUDGET` and `FUSED_CEILING`, and the question whether weights
  should still bound size: `SCOPE_MILESTONE_7.md` item 63.
* Every emitted body. The change moves which methods a kernel has, never what a division emits.

### 3.4 Registered op counts

None change. The division emits 11 under the default and 7 under the reference arm, before and
after, and section 5's first test asserts both.

## 4. Files

| file | what |
|---|---|
| `VarkaEmitBudget.java` | `INT_CONST_DIVIDE_WEIGHT`, the long lane's constant renamed, the comments |
| `VarkaEmitterBudgetSuite.scala` | the division's register at both lanes; the four-column shape |
| `sql/varka/emitted_bytes.json` | regenerated |
| `PLAN_MILESTONE_6.md`, `SCOPE_MILESTONE_7.md` | row 148 and section 2.2a; item 63's note |
| `sql/varka/skills/benchmarking.md` | the metric's lesson, as a bullet of the lesson on counting by owner |

## 5. Tests, and what each is for

* **A constant division weighs the larger of its two forms, at both lanes.** Each form is
  emitted alone and counted across every vector type, the body less the body of a division by
  one, which emits nothing of its own: at the int lane the multiply-high form against the
  conversion, for every divisor the fuzz grammar draws, and at the long lane the magic form
  against the conversion. A lowering change that moves either count fails here and names the
  constant to recount.
* **Four int-lane divisions over four columns take a loop method each, and answer as the
  reference evaluator does** at both widths. It is the shape the oracle's coverage rows cannot
  show: one division alone forms one group whatever it weighs.
* **The oracle**, regenerated, with every moved entry explained.

## 6. The measurement

No benchmark. The task claims no speed: it corrects the unit one input to the grouping is
counted in, and the grouping's own budget is unchanged. The oracle's regeneration is the
measurement of what moved.

### 6.1 Predictions, registered before the run

1. **No coverage row's hash moves**, because both division rows are single outputs and the
   weight is read only by the grouping.
2. **No long-lane fuzz block moves**, because the long lane's weight is unchanged.
3. **Every int-lane fuzz shape that moves holds an int-lane division and has more than one
   output**, so at most 1334 of the 10000 move, and **no moved shape ends with fewer loop
   methods** than it had.
4. **The four-column projection goes from one loop method to four**, since 11 and 11 exceed the
   budget of 16.
5. **The option arms' digests move** if any int-lane shape moves, since each digests every shape.

## 7. Risks

1. **A division shape grouped more finely may run slower**, with more loop methods each paying a
   method's fixed cost per batch. The task makes no speed claim either way. The budget of 16 is
   `GROUP_BUDGET`'s own and was chosen by measurement (`PLAN_TASK_71.md` 10.5); measuring what
   accurate weights do to speed is item 63's.
2. **A shape the byte or call-site budget regrouped may now group differently on the first
   pass.** The oracle's diff shows it, and prediction 3 bounds where it can happen.

## 8. Sequencing

1. This plan, with row 148 marked Planned.
2. The weight, the comments, the tests, the regenerated oracle and section 9, in one commit: the
   weight alone would fail the oracle, so the two cannot be green apart.

## 9. Outcome
