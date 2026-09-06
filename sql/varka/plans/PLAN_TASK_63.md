# Task 63: int32 arithmetic in the date lane

## 1. Where this came from

`PLAN_MILESTONE_4.md` row 63 and section 2.30, added on 4 September 2026 by
the owner's decision after task 37 found `WHERE weekofyear(d) = 53` declining
at the literal and task 57 had to give `weekday(d) + 1` a node of its own
because the compiler has no arm for `Add`. Every operation this task adds
already exists in the emitter's int32 lanes; what was missing was a place to
send an overflowing lane in ANSI mode, and tasks 52 and 56 built that place -
the per-batch decline route through the kernel status. The owner's directive
of 5 September 2026: after the task 61 stack, take 63. The plan and the
admission check are written now, off master at `5d7461d3aa2`; the code starts
when the stack (#123 to #126, #128, #130) has landed, because three of its
pieces are needed here and because every one of those PRs edits the same
files.

## 2. The admission check, done

**What the row engine does, per mode.** `BinaryArithmetic.evalMode` is fixed
when the expression is built (`NumericEvalContext`, from
`spark.sql.ansi.enabled` at analysis), so the mode is a property of the
expression the compiler sees, not of the batch:

| mode | `Add`/`Subtract`/`Multiply` on `IntegerType` | `UnaryMinus` |
|---|---|---|
| `LEGACY` | the Java op, wrapping | `-x`, wrapping (`failOnError = false`) |
| `ANSI` | `MathUtils.addExact` etc.: throws `ARITHMETIC_OVERFLOW` with the query context | `negateExact`: the same error at `Int.MinValue` |
| `TRY` | `failOnError` is true and the throw is caught: the result is NULL | no `TRY` form exists (`try_negative` is not a function) |

`try_add`, `try_subtract` and `try_multiply` are `RuntimeReplaceable`, and for
two numeric operands their replacement is the same `Add`/`Subtract`/`Multiply`
under `EvalMode.TRY` - so the compiler's existing `RuntimeReplaceable` arm
reaches the `TRY` node with no spelling of its own. Nullability: an `ANSI` or
`LEGACY` node is null only where an input is null; a `TRY` node can be null on
valid inputs, which is the fact the emitter's dense body cannot express and
task 42 already solved (a kernel with such a node has no dense methods).

**The error is the row engine's, by the route, not by imitation.** Under
`ANSI` the kernel never raises: an overflowing lane sets the batch status
through task 52's accumulator, the evaluator discards the kernel's outputs
and recomputes that batch on the row engine, which raises Spark's own
`ARITHMETIC_OVERFLOW` for the same row with the same query context - task 56's
error-identity rule, already asserted by its differential. Nothing is
reproduced in the kernel but the detection.

**Detection, per op, in lanes.** Same-signed operands and a differently
signed result: for `r = a + b`, `((a ^ r) & (b ^ r)) < 0`; for `r = a - b`,
`((a ^ b) & (a ^ r)) < 0`; for `-a`, `a == Int.MinValue`. Two `XOR`, one `AND`
and one compare, or one compare, all lanewise and all in `VectorOperators`.
For `r = a * k` with a literal `k`: `a` outside
`[ceil(MIN / k), floor(MAX / k)]` (the two bounds folded at compile time,
swapped for negative `k`; `k = -1`
is the single compare `a == MIN`; `k = 0` never overflows) - two compares.
For `a * b` with two columns the exact test needs the product's high half,
which the Vector API has no lanewise operation for (`SKILLS.md`, the Julian
map section): either a lanewise `DIV` afterwards (`a != 0 && r / a != b`,
plus the `MIN * -1` case), which x86 scalarises at about 8x the cost of a
multiply, or a widening to long lanes, which is milestone 5's task 28. **The
column-by-column multiply is therefore admitted in `LEGACY` mode only in this
task; under `ANSI` and `TRY` it declines with its own reason**, recorded in
9's leftovers as the follow-up that task 28's widening makes cheap. The
shapes users write - `year(d) * 100`, `i * 7`, `datediff(a, b) * 24` - have a
literal multiplier.

**Types.** Spark promotes before the compiler sees the tree: `year(d) + 1L` is
a `LongType` add, `datediff(a, b) / 2` is a `DoubleType` divide, `s + 1` over a
`ShortType` column is a `ShortType` add with its own overflow arithmetic. The
arms accept a node whose `dataType` is `IntegerType` with both operands
`IntegerType`, and nothing else; every other width declines with the type in
the reason. `Literal(v: Int, IntegerType)` and an `IntegerType` column are the
new operand leaves, admitted through a dedicated operand function rather than
through `compileNode`'s `DateType` leaf, so task 38's "do not open it wider"
holds: the int column still reaches only the positions this task names
(an arithmetic operand, and through arithmetic a comparison operand, which
task 37's `compare` already admits for a literal).

**`date_add(d, i * 7)`.** The one interaction with task 52's range analysis,
per section 2.30: an arithmetic offset is column-shifted whatever the
arithmetic, so `dayRange` answers `ColumnShifted` for an `AddDays` whose offset
is not a literal - the branch it already has - and the producer guard protects
a calendar consumer as it does for a bare column offset. The emitter's
`requireOffsetShape` (a literal slot or a column) widens to "any int value
node" for `AddDays`/`SubDays`, which `collectColumnOffsetProducers` already
treats as guarded; nothing else in the guard changes.

**What the check would have rejected:** a mode that is a per-batch fact (it
is per expression), an error the kernel would have to raise itself (the
route raises it), a wrap-around the row engine does not perform under
`LEGACY` (it does, exactly as Java), and a dense fast path for `TRY` nodes
(task 42's rule forbids it, for the right reason).

## 3. The design

### 3.1 The nodes, the arms, the check

**Two IR records**, in the value family, with the mode as a shape-bearing
enum the way `Compare` carries its op and `MakeDate` carries `failOnError`:

    enum IntOp { ADD, SUB, MUL }
    enum Overflow { WRAP, FAIL, NULL }          // LEGACY, ANSI, TRY
    record IntArith(IntOp op, Overflow mode, VarkaVectorIR left, VarkaVectorIR right)
    record IntNeg(Overflow mode, VarkaVectorIR child)   // WRAP or FAIL only

Rendered `(int:ADD:FAIL a b)` and `(neg:FAIL a)`, so the shape hash tells the
modes apart. Weight 1 under `WRAP`, 2 under `FAIL` and `NULL` (the check is
four or five cheap ops; `GROUP_BUDGET` is not the concern, but the register
counts them). The word is the AND of the children's, `emitAndValidatedOp`'s
rule, in `WRAP` and `FAIL`; in `NULL` mode it is that AND with the overflow
mask cleared, stored as the node's own word, and the analysis marks the kernel
as one that nulls valid inputs (task 42's `nullsFromValidInputs`, so the
dispatch has no dense methods).

**The emitted check** (`FAIL`), after the lanewise op, behind
`VarkaEmitOptions.checkIntOverflow` (default true; false emits the `FAIL` node
as `WRAP`, for the A/B only, exactly as `guardDayProducers = false` prices
task 52's guard): the result is parked in a scratch slot like
`emitRangeGuard` parks its value, the mask above is built, ANDed with the
node's word in the masked body and with the epilogue mask, and ORed into
`s.guardAcc`, which is allocated whenever the body has a `FAIL` node or a
guarded producer. `emitStatusReturn` turns the accumulator into
`STATUS_CHRONO_RANGE`, as today - one accumulator, one bit. **Open question
for the owner** (section 7): whether the overflow deserves its own status bit
(a second accumulator, so `numFallbackBatchesDeclined` could be split by
cause in telemetry) or the shared bit is enough, since the evaluator's action
is the same either way. The plan ships the shared bit.

**The compiler arms**, after task 57's `Add(WeekDay, 1)` arm so that shape
keeps its cheaper node:

    case a @ Add(l, r, _) if a.dataType == IntegerType =>
      for (x <- intOperand(l); y <- intOperand(r)) yield new IntArith(ADD, mode(a.evalMode), x, y)
    // Subtract, Multiply likewise; Multiply with two non-literal operands under FAIL or NULL
    // declines: "ansi multiply of two columns needs a widening check"
    case n @ UnaryMinus(c, failOnError) if n.dataType == IntegerType =>
      intOperand(c).map(new IntNeg(if (failOnError) FAIL else WRAP, _))

with `intOperand`: an `IntegerType` `BoundReference` to a `ColumnRef` (the new
leaf, through `columnRef`), `Literal(v: Int, IntegerType)` to a `LiteralSlot`,
and everything else through `compileNode` - which yields the fused int fields
(`DateDiff`, the extractions, `DayOfWeekIso`, and after #123 `WeekOfYear`)
and nested arithmetic. Any other type declines with
"int arithmetic operand of type <t>"; a node of another result type with
"int arithmetic over a non-int type <t>". `compare`'s `operand` (task 37)
becomes this same function, so `year(d) * 100 + month(d) = 202409` is one
predicate. `compileOffset` gains an arm for an `IntegerType` arithmetic
expression, so `date_add(d, i * 7)` and `date_add(d, datediff(a, b) + 1)`
build `AddDays(d, IntArith(...))`.

**What a user observes.** `year(d) * 100 + month(d)`, `datediff(a, b) + 1`,
`i * 7`, `-i`, `try_add(...)`, the day of quarter, the week of month and the
composite keys section 2.24 lists fuse; under ANSI an overflowing row raises
Spark's own error from the row engine, under `LEGACY` it wraps as Spark's
does, and `try_*` gives NULL; a comparison over any of these is a fused
predicate. Nothing changes for a query that has no such arithmetic.

### 3.2 What is deliberately unchanged

* `/`, `div`, `%`, `pmod`, `abs`, and every `LongType`, `ShortType`, `ByteType`,
  `DecimalType` and `DoubleType` form: milestone 5's, with section 2.30's note
  on literal divisors as the recorded exception to argue for later.
* Task 57's `DayOfWeekIso`: kept, as the cheaper lowering of its one shape.
* The evaluator: outputs of `IntegerType` already exist (`datediff`), the
  decline route and its metric already exist, and no batch-side check is
  added.
* `compileNode`'s `BoundReference` leaf stays `DateType`-only; the int column
  enters through `intOperand` and `compileOffset` alone.
* The column-by-column multiply under `FAIL`/`NULL` (declined, 2 above).

### 3.3 Registered op counts

Dense-loop `IntVector` calls in `loopDense0` (`dev/varka_emit.sh --table`),
the after column filled from the emitted bytes before the emitter commit is
made and asserted by the register test:

| kernel | before | after (predicted) |
|---|---|---|
| `year(d) + 1`, `LEGACY` | declines | `year`'s 40-ish plus 1 |
| `year(d) + 1`, `ANSI` | declines | plus 5 more: two `XOR`, `AND`, compare, the mask `OR` |
| `year(d) * 100 + month(d)`, `ANSI` | declines | one shared prefix, two tails, two ops, two checks: about 60 |
| `datediff(d2, d) + 1`, `ANSI` | declines | 1 + 1 + 5 |
| `i * 7`, `ANSI` | declines | 1 + 3 (two compares and the `OR`) |
| `-i`, `ANSI` | declines | 1 + 2 |
| `try_add(datediff(d2, d), i)` | declines | no dense loop (masked body only); the masked count registered instead |
| `year(d)`, `datediff(d2, d)`, `date_add(d, i)` (controls) | as today | unmoved, asserted |

## 4. Files

| file | what |
|---|---|
| `VarkaVectorIR.java` | `IntOp`, `Overflow`, `IntArith`, `IntNeg`; the renderings |
| `VarkaEmitOptions.java` | `checkIntOverflow` |
| `VarkaLoopEmitter.java` | the arms in `childrenOf`, `analyze`, `planWordRef`, `planSlots`, `weightOf`, `emitValue`; the check block factored from `emitRangeGuard`'s tail; `guardAcc` allocation widened; the `NULL` word; `nullsFromValidInputs` for `NULL` nodes; `requireOffsetShape` widened for `AddDays`/`SubDays` |
| `VarkaReferenceEvaluator.scala` | the three modes per op: Scala's wrapping op, `Math.addExact` caught to a decline marker, and `None` |
| `VarkaLoopEmitterSuite.scala` | the boundary matrices, the status tests, the `NULL` validity test, the register, both pinned fixtures re-pinned |
| `VarkaIrFuzzSuite.scala` | arms for the three ops in `WRAP` over bounded operands, and `FAIL` over operands the bound keeps from overflowing |
| `VarkaShapeCacheSuite.scala` | the hash re-pinned |
| `VarkaExpressionCompiler.scala` (+ suite) | `intOperand`, the arms, the mode mapping, `compare`'s operand, `compileOffset`'s arithmetic arm, the reasons |
| `VarkaSharedSessions.scala`, `VarkaDifferentialSuite.scala` | an overflow-dense fixture (`Int.MaxValue` neighbours beside ordinary ints and nulls); the differentials of section 5 |
| `VarkaEmitterParityBenchmark.scala` + files, `VarkaThroughputBenchmark.scala` + files | section 6 |
| `docs/sql-varka.md`, `SKILLS.md`, `SCOPE_MILESTONE_6.md` item 12 | the surface bullet and reasons; the lesson; item 12's "until task 30 lands" corrected to this task |
| `PLAN_MILESTONE_4.md`, this file | row 63, section 9 |

## 5. Tests, and what each is for

The oracle is Spark's own arithmetic: `Math.addExact` and friends for what
must be flagged, Java's wrapping ops for `WRAP`, per row.

* **Emitter, the boundaries.** `checkMatrix` over each op and mode with
  operands cycling through `Int.MaxValue`, `Int.MinValue`, their neighbours,
  zero, small values and the literal multipliers `100`, `7`, `-1`, `0`, over
  every null pattern of both columns and both widths: under `WRAP` the wrapped
  value, under `NULL` a null lane exactly where `addExact` throws and the
  value elsewhere, under `FAIL` the value where nothing overflows. The failure
  it catches: a sign test with the operands swapped, a literal bound off by
  one, a `NULL` word that keeps the overflowed lane valid.
* **Emitter, the status.** `FAIL` with one overflowing lane in a loop lane
  and in an epilogue-only lane returns `STATUS_CHRONO_RANGE`; the same lane
  under a null input returns 0; `checkIntOverflow = false` returns 0 and the
  bytes equal the `WRAP` node's; a kernel with a `NULL` node has no dense
  methods (the class's method list) and a null-free batch through it is
  correct.
* **Emitter, the register and the fixtures.** The counts of 3.3; the line map
  and shape hash re-pinned from the failing output, once.
* **Compiler.** Each arm's shape and mode from a tree built under each
  evaluation mode, and from `TryAdd` through its replacement; the operand
  leaves; the declines with their reasons (a `LongType` add, a `ShortType`
  column, a `DoubleType` divide, `%`, the two-column multiply under `ANSI`);
  `weekday(d) + 1` still `DayOfWeekIso`; `year(d) * 100 + month(d) = 202409`
  as a predicate; `date_add(d, i * 7)` as `AddDays` over the arithmetic with
  `dayRange` answering `ColumnShifted` (`year(date_add(d, i * 7))` fuses with
  the producer guard).
* **Differential.** Under ANSI: the composite key, `datediff + 1`, the day of
  quarter `datediff(d, trunc(d, 'QUARTER')) + 1`, the week of month
  `(day(d) - 1) div 7 + 1` (declines: `div` is out, asserted as residual with
  the reason), `i * 7`, `-i`, all matching the row engine on the ordinary
  fixture with zero fallbacks; on the overflow fixture the same query raises
  through both engines and the conditions and messages are equal (task 56's
  idiom); `numFallbackBatchesDeclined` counts the overflowing batches and no
  other. Under `LEGACY`: the overflow fixture matches value for value, zero
  fallbacks. `try_add`, `try_subtract`, `try_multiply` over the overflow
  fixture: NULL where the row engine gives NULL, zero fallbacks, and the
  metric shows no dense path taken (the kernel's method list through the
  telemetry bytes, or the plan's `verboseString`).
* **Fuzzer.** `WRAP` arms over any operands; `FAIL` arms over operands the
  generator bounds so no lane overflows, and the reference's decline marker
  asserted never to fire there.

## 6. The measurement

`VarkaEmitterParityBenchmark`, an "int arithmetic" section beside the task 52
A/B: `year(d) * 100 + month(d)` under `WRAP`, `FAIL` with the check, `FAIL`
with `checkIntOverflow = false`, and `NULL`; `datediff(d2, d) + 1` under
`WRAP` and `FAIL`; `i * 7` under `FAIL`; the controls `year(d)`, `month(d)`
and `datediff(d2, d)`, which must not move; null-free and mixed nulls; and a
per-row anchor computing the composite key with `Math.addExact` and
`LocalDate`. `VarkaThroughputBenchmark`: `year(d) * 100 + month(d)` and
`datediff(d2, d) + 1` in the default (ANSI) session beside `year(d)` and
`datediff(d2, d)` as controls, and `try_add(datediff(d2, d), i)`. Both widths,
`dev/varka_bench_regen.sh` on the idle machine.

### 6.1 Predictions, registered before the run

1. The register: the after column of 3.3 within one op per row; the controls
   unmoved.
2. The `ANSI` check costs under 5% on `year(d) * 100 + month(d)` at both
   widths: five cheap ops behind a prefix that is latency-bound (task 54's
   lesson), so they fill slots the chain leaves empty. On `datediff(d2, d) +
   1`, a memory-bound shape, the check costs 10-25%, the way task 52's guard
   cost 5-15% on `date_add`.
3. The `NULL` mode runs at 0.6x-0.8x of `WRAP` on the composite key null-free,
   because it forfeits the dense body (task 10 measured the dense body at
   2.3x-2.9x the masked one, and the prefix dilutes that).
4. Throughput: the composite key at 3x-6x Janino - the row engine decomposes
   the date twice, once per field, and Varka once; `datediff + 1` at the
   `datediff` row's ratio within 10%.
5. The overflow differential declines exactly the batches with an overflowing
   live row, and the ANSI error text is identical to the row engine's.

## 7. Risks and open questions

1. **The `NULL` word interacts with CSE and the epilogue mask.** A `TRY` node
   used twice must clear the same lanes in both uses; the matrix under
   `cse = false` and the epilogue-only lengths cover it.
2. **The check reads the result after the parent consumed it.** The result is
   parked in a scratch slot first, as `emitRangeGuard` does; the
   `VerifyError` task 60 met is the failure mode, caught by every matrix run.
3. **The optimizer reshapes the tree** (`ReorderAssociativeOperator`,
   constant folding of `x + 1 + 2`): the compiler suite builds trees through
   the optimizer for the composite key and the differential runs real SQL, so
   a shape the arms miss shows as a decline, never as a wrong value.
4. **The status bit is shared with the calendar range guard** (3.1's open
   question): telemetry cannot tell an overflow decline from a range decline.
   Owner's call; a second accumulator is a small addition if wanted.
5. **Stack conflicts.** Every emitter and compiler file this task touches is
   touched by #123 to #130; the code starts after they land, and `compare`'s
   operand function from #123 is reused rather than duplicated.

## 8. Sequencing

1. This plan and the milestone row (now, on `varka-task-63` off master).
2. After the stack lands: the IR, the emit option, the emitter for the three
   modes with the check factored from the guard tail, the reference arms, the
   matrices, the status tests, the register, the fixtures re-pinned.
3. The compiler: `intOperand`, the arms, `compare`, the declines, the suite.
4. `compileOffset`'s arithmetic arm and the widened `requireOffsetShape`, with
   the range-analysis and guard tests.
5. The differential, the docs, the SKILLS note, item 12's correction.
6. The benchmark cases, one regeneration at both widths, section 9, row 63.

## 9. Outcome

<!-- Filled in when the measurement lands: the numbers with the committed file
     they trace to (dev/varka_quote_check.py holds you to this), 6.1's
     predictions scored one by one, what moved that the plan did not list, and
     what the task leaves for later - which goes to the milestone's debt
     register or a scope document, never to a code comment. -->
