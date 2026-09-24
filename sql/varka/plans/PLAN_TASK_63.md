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

### 3.4 What task 70 changed under this plan

*Added 7 September 2026, after task 70 merged. Sections 3.1 to 3.3 were
written before it and name the emitter as it was - `childrenOf`, `analyze`,
`planWordRef`, `planSlots`. That list is no longer complete, and two of the
additions are not optional: the code will not compile without them.*

**The two liveness switches are exhaustive, on purpose.** `liveWords` walks a
body twice - once over every node it emits, once over the queue of nodes whose
own word is demanded - and since task 70's review both switches cover the
sealed IR with no `default` arm, the discipline `childrenOf` and `analyze`
already had. The reason is that a missing arm there is not a wrong answer but a
silent one: the emitter throws, `VarkaKernelEvaluator` catches it as an
emission failure, and every batch of that shape drops to the per-row path while
EXPLAIN still claims fusion. So `IntArith` and `IntNeg` need arms in both, and
the compiler will say so.

* The consumer walk demands nothing for `WRAP` and `FAIL`, whose words are read
  by their own root write or by the guard below, and demands the node's own
  word for `NULL`, which stores it unconditionally - `MakeDate`'s arm is the
  precedent and the reason.
* The propagation loop demands both operands' words for `IntArith` and the
  child's for `IntNeg`, which is what those arms load.

**The overflow check extends one predicate, not two conditions.** 3.1 puts the
`FAIL` mask into `s.guardAcc` and says the accumulator is allocated whenever the
body has a `FAIL` node or a guarded producer. Since task 70, "is this node
guarded" was `guardedWord(analysis, node, producersGuarding, selfGuarding)`, read
by `planSlots` for the temporary and by `liveWords` to keep the word alive,
precisely so a third guarded node kind cannot be added to one and forgotten in
the other. `IntArith` and `IntNeg` under `FAIL` are that third kind.

*Corrected on 8 September 2026, after this task's own review.* **Extending that
one predicate is no longer the right instruction, and the failure mode it
promised is no longer the one you get.** Adding this task's kind showed that its
two readers want different things. `liveWords` asks "must this node's word stay
alive", which is true of a checked arithmetic node, because `emitGuardCollect`
loads it. `planSlots` asks "does this node need a scratch local", which is
false of one: `emitIntArith` parks its operands and result in `intArithTmp` and
`emitIntNeg` reads its operand back with `dup`, so every checked node was
reserving a local that no instruction ever loaded. `planSlots` reads
`guardScratch` now; `liveWords` reads `guardedWord`.

So a fourth guarded kind is two edits, and getting it wrong is **silent**. Add
it to `guardedWord` alone and its word stays alive but no `guardTmp` is planned,
so `emitAndValidatedOp`'s `if (guardTmp != null)` is false and the guard is
simply never emitted - a wrong date, not the loud `emitGuardCollect` refusal the
paragraph above used to promise. Ask which of the two questions the new kind
answers yes to, and add it to each that it does. `PLAN_MILESTONE_5.md` 2.14
(task 83) proposes replacing the pair with a single refusal property, which is
the real fix for a predicate that has now been split once and extended twice.

**The word algebra is where the free win is, and 3.3 does not have it.** Task 70
added two views of a node's validity: `ownerOf`, naming the word a node's
validity *is*, and `pureOf`, giving the bitmap expression that word denotes when
it is a pure function of the input bitmaps. A root whose expression is a
single-operator chain over input bitmaps has its whole validity bitmap written
once per batch by the driver, and the loop then makes no per-group validity call
at all; for a shape whose every word dies that way, the masked loop and epilogue
are the dense ones' bytes.

Under `WRAP` and `FAIL` this node's word is the AND of its operands', which is
exactly such an expression, so both arms are worth adding:

* `ownerOf`: `andOwner(node, left, right)` for `IntArith`, the child's owner for
  `IntNeg`. Without an arm the node falls to `Own(node)`, which agrees with
  `planWordRef`'s own default and passes the agreement assertion - so this is a
  pessimisation rather than a failure. It costs a word slot and an AND on every
  shape whose operands already share a word.
* `pureOf`: `andExpr` of the operands' expressions for `IntArith`, the child's
  for `IntNeg`, and **nothing for `NULL` mode**, whose word is that AND with an
  overflow mask cleared - a function of values, not of bitmaps, the same
  boundary `make_date` and `IfElse` sit on. The fail-safe default is already
  null, so `NULL` needs no arm; it needs a test saying it is unserved on purpose.

The consequence for 3.3 and for section 6: `year(d) * 100 + month(d)` over one
date column has the word of a single input, so the bitmap pass serves it and its
masked loop should be its dense loop's bytes. That is a byte count the register
test can assert and a row the parity benchmark can show, and neither is in this
plan as written.

**Two smaller consequences.** `VarkaEmitOptions` gains `checkIntOverflow` as its
eighteenth component, beside the two task 70 added. And
`VarkaLoopEmitter.bitmapPassCounts`, the served-and-declined counter the review
added as the pass's safety net, is pinned per shape by the emitter suite: this
task's shapes belong in that test - served for the arithmetic over one date,
unserved for `NULL` mode.

## 4. Files

| file | what |
|---|---|
| `VarkaVectorIR.java` | `IntOp`, `Overflow`, `IntArith`, `IntNeg`; the renderings |
| `VarkaEmitOptions.java` | `checkIntOverflow` |
| `VarkaLoopEmitter.java` | the arms in `childrenOf`, `analyze`, `planWordRef`, `planSlots`, `weightOf`, `emitValue`; the check block factored from `emitRangeGuard`'s tail; `guardAcc` allocation widened; the `NULL` word; `nullsFromValidInputs` for `NULL` nodes; `requireOffsetShape` widened for `AddDays`/`SubDays` | Since task 70 (3.4), also mandatory: `liveWords`' two exhaustive switches, `guardedWord` *and* `guardScratch` for the `FAIL` node - they answer different questions since this task's review, and 3.4 says which - and `ownerOf`/`pureOf` for the word.
| `VarkaReferenceEvaluator.scala` | the three modes per op: Scala's wrapping op, `Math.addExact` caught to a decline marker, and `None` |
| `VarkaLoopEmitterSuite.scala` | the boundary matrices, the status tests, the `NULL` validity test, the register, both pinned fixtures re-pinned |
| `VarkaIrFuzzSuite.scala` | arms for the three ops in `WRAP` over bounded operands, and `FAIL` over operands the bound keeps from overflowing |
| `VarkaShapeCacheSuite.scala` | the hash re-pinned |
| `VarkaExpressionCompiler.scala` (+ suite) | `intOperand`, the arms, the mode mapping, `compare`'s operand, `compileOffset`'s arithmetic arm, the reasons |
| `VarkaSharedSessions.scala`, `VarkaDifferentialSuite.scala` | an overflow-dense fixture (`Int.MaxValue` neighbours beside ordinary ints and nulls); the differentials of section 5 |
| `VarkaEmitterParityBenchmark.scala` + files, `VarkaThroughputBenchmark.scala` + files | section 6 |
| `docs/sql-varka.md`, `SKILLS.md`, `SCOPE_MILESTONE_7.md` item 12 | the surface bullet and reasons; the lesson; item 12's "until task 30 lands" corrected to this task |
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
6. *Added with 3.4.* `year(d) * 100 + month(d)` under `WRAP` lands on its dense
   twin, because its word is one input's bitmap and task 70's pass writes that
   once per batch: `loopMasked0` and `epilogueMasked` byte-equal to their dense
   siblings, and the masked mixed-null parity row within the tie floor of the
   null-free one. Under `FAIL` the guard keeps a word alive, so the two bodies
   differ and the row does not - the split task 70 measured between `year(d)`
   and `year(date_add(d, off))`.

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

The task shipped in six commits on `varka-task-63`. Sections 9.1 to 9.4 are
the measurement, 9.5 scores 6.1's predictions, 9.6 records what moved that
this plan did not list - which, this time, is most of the work - and 9.7 what two
reviews found afterwards, including in the fixes for what the first one found.

The numbers are `VarkaArithmeticBenchmark`'s, a file of its own rather than
the section in `VarkaEmitterParityBenchmark` that section 6 asked for; 9.6
gives the reason. It was measured at `6722db54e31` on an idle machine (load
0.76, canary compute +0.1%, cache +3.8%, memory -1.2%) at both widths in one
run of `dev/varka_bench_regen.sh`. `VarkaThroughputBenchmark` was regenerated
separately, for a reason that is not this task's arithmetic at all (9.6).

### 9.1 What the ANSI check costs, as an A/B on one node

The check is the sign test: four lanewise ops and a compare for `+` and `-`,
one compare for unary minus, which reads its operand rather than its result.
Each row below is the same IR emitted twice, with `checkIntOverflow` on and
off, so the difference is the check and nothing else. The emitter suite pins
that: with the flag off a `FAIL` node is byte-identical to the `WRAP` node,
method for method.

*Requoted on 8 September 2026 from the regeneration at `80d06a51560`. Two
reviews forced two regenerations: the second review, because dropping the dead
scratch local (9.7) changed the emitted bytecode of every checked row, and the
third, because the benchmark's second column was filled by a second call to the
same helper with the same arguments and so was a bit-for-bit copy of the first,
which made every `datediff` case here a disguised `datediff(x, x)` (9.7). The
first of those moves corrects one of this section's claims - see below - and the
second moves only the two-input rows, which is the evidence that it was a
fixture bug and not a measurement one. The superseded figures are in this file's
history and in the results files'.*

| shape | AVX-512 | 128-bit |
|---|---|---|
| `i + 1`, checked -> off | 19180.2 -> 19449.2 (1.4%) | 13780.6 -> 18776.2 (26.6%) |
| `i + 1`, mixed nulls | 18542.2 -> 19295.0 (3.9%) | 6522.9 -> 19073.8 (65.8%) |
| `i - 1`, checked -> off | 19033.8 -> 19415.2 (2.0%) | 13412.0 -> 18635.9 (28.0%) |
| `-i`, checked -> off | 19176.2 -> 19419.0 (1.3%) | 12759.0 -> 18768.8 (32.0%) |
| `try_add(i, 1)` against `LEGACY`, mixed | 16866.3 against 19381.3 | 3780.6 against 18946.9 |

**The width decides, and in the narrow lanes the mask decides more.** At
AVX-512 the check is a rounding error on a memory-bound loop, masked or not -
1.4% and 3.9%, and unary minus, which reads its operand rather than its result
and so tests one value instead of three, is the cheapest at 1.3%. At 128 bits
the same five ops are a much larger share of a four-lane group's work and cost a
quarter to a third; and there the masked body is a different story again, 65.8%
for an addition whose arithmetic did not change. What the masked arm adds is the
disposal - `emitGuardCollect` converts the overflow mask to a `long`, ANDs it
with the node's validity word and ORs it into the batch accumulator - and those
conversions do not vectorize the way the lane ops do. `try_add`, which disposes
of the same mask by narrowing the word instead, is slower again.

**One claim here was wrong, and the requote is what found it.** The first run
put the AVX-512 masked cost at 19.0% and this section attributed it to the same
disposal. It was mostly a *dead local slot*: the scratch temporary every checked
node reserved and no instruction read (9.7). Removing it moved that row 26.1%
and left the disposal 3.9% - so at AVX-512 the disposal is nearly free and only
the 128-bit figure ever supported the claim. An unused local costing fifteen
points of throughput at one width and nothing at the other is itself worth
knowing, and `SKILLS.md` records it; the wide body carries more live vector
values, so it is the one with no register headroom to spare.

### 9.2 The composite key, where the bound removes the check

| shape (null-free unless said; requoted from `80d06a51560`) | AVX-512 | 128-bit |
|---|---|---|
| `year(d) * 100 + month(d)`, as shipped | 2674.9 | 996.7 |
| the same with its outer add checked | 2433.8 (9.0% slower) | 904.7 (9.2% slower) |
| the same, mixed nulls | 2676.2 | 999.3 |
| `year(d)` alone (control) | 3517.0 | 1345.4 |
| `year(d)`, `month(d)`, no arithmetic (control) | 2825.0 | 1058.8 |
| `datediff(d, d2) + 1`, as shipped | 12301.3 | 10974.4 |
| the same, checked | 11841.6 (3.7% slower) | 9209.5 (16.1% slower) |

The two `datediff` rows are the only ones the third review's fixture fix moved,
and they moved because they are the only two-input rows in the file: the
difference they compute was zero for every lane until the second column stopped
being a copy of the first. The bytes read, the lanes issued and the kernel
emitted were the same either way, which is why the check's cost at 128 bits
moved from 21.1% to 16.1% rather than to something unrecognisable - a real
subtraction and a subtraction of equals are the same instruction.

Both operands of the key are bounded - the calendar bounds every field, the
date contract bounds `datediff` - so the compiler proves the result cannot
leave int32 and emits every node as `WRAP`. The shipped row is therefore the
unchecked one, and the checked row beside it is what the emitter would have
produced without the bound analysis. There is no third arm with the multiply
checked, because there is no such kernel: an int lane has no cheap overflow
test for `*`, the compiler declines a checked one, and without the bound the
whole expression would be residual rather than 9% slower.

The mixed-null row is the interesting one: it lands 0.05% above the null-free
row at AVX-512 and 0.26% above it at 128-bit, which is 6.1's prediction 6 and
the emitter suite pins the byte equality behind it.

### 9.3 End to end, against the row engine

`VarkaThroughputBenchmark`, 2 million Arrow-cached rows, the whole query
through the planner in the default (ANSI) session, so these rows include
everything the kernel-level file above leaves out.

| query | AVX-512 | 128-bit |
|---|---|---|
| `year(d) * 100 + month(d)` | 334.7 against 35.9, **9.3x** | 280.9 against 35.4, **7.9x** |
| `datediff(d, DATE'2000-01-01') + 1` | 407.2 against 44.8, **9.1x** | 403.9 against 43.7, **9.2x** |
| `try_add(datediff(...), i)` | 358.7 against 33.1, **10.8x** | 319.8 against 32.4, **9.9x** |
| `datediff(d, d2)` alone (control) | 259.1 against 38.8, 6.7x | 244.3 against 39.6, 6.2x |

**The arithmetic improves the ratio rather than spending it.** `datediff + 1`
beats `datediff` alone - 9.1x against 6.7x - because the extra operation costs
the row engine a per-row add and the kernel one lanewise call on a value
already in a register. The same reading explains the composite key: the row
engine decomposes the date once per field and Varka once for both.

**And one residual entry costs a projection more than the arithmetic ever
saves it.** The file now carries the same three-entry projection twice, and the
pair is the clearest end-to-end statement of what this task did:

| `SELECT date_add(d, 3), i, <entry>` | AVX-512 | 128-bit |
|---|---|---|
| `i + 1`, which fuses since this task | 346.7, **10.8x** | 327.4, **10.0x** |
| `i % 7`, which does not | 76.1, 2.3x | 84.7, 2.6x |

One entry the compiler cannot lower drops the whole projection from 10.8x to
2.3x, because every batch pays the per-row path for that column and the merge
around it. That is 4.6x on a query whose other two entries did not change, and
it is the argument for lowering the next expression family as much as any
kernel number here.

### 9.4 What the emitter emits

Pinned by the register test as one table of `IntVector` calls in `loopDense0`,
with the claims stated as differences so a change to the shared year prefix
moves both sides rather than the claim. The absolute numbers include the
loop's unrolling, which is why `datediff(d, d2)` reads 4 rather than 1:

* the add's check is four calls on top of the op, the same in `year(d) + 1`
  (36 -> 40) and in `datediff(d, d2) + 1` (6 -> 10) - two `XOR`, an `AND` and
  the compare;
* the negation's check is one call (3 -> 4), because it reads its operand
  rather than its result;
* the composite key is the two fields' 40 calls plus two, so the year prefix
  is computed once for both tails;
* `year(d)` (34), `datediff(d, d2)` (4) and `date_add(d, off)` (4) are
  unmoved.

A `TRY` node has no dense body at all - it can null a lane whose operands were
both valid, so the dispatcher never sends it a dense batch - and a `FAIL` node
keeps both. Both are asserted on the emitted method list.

### 9.5 The predictions, scored as 6.1 registered them

1. **The register: hit on the differences, and 3.3's absolute numbers were
   the wrong unit.** The check costs what 3.3 said it would, with one
   correction: it wrote five calls counting the mask OR, and the OR is a
   `VectorMask` call rather than an `IntVector` one, so the pinned difference
   is four. The absolute counts are larger than 3.3's arithmetic suggests
   because `loopDense0` is unrolled, which that table did not account for -
   `datediff(d, d2)` alone is 4 calls, not 1. The three controls are unmoved,
   which is the part of the row that was load-bearing.
2. **The check under 5% on the key at both widths: not applicable as
   written, and the second half is a partial hit.** The prediction assumed the
   key would carry a check that the bound in fact removes, so there is no
   checked shipped row to score. Against the deliberately checked arm the cost
   is 9.1% and 9.2%, above the 5% the prediction expected of a
   latency-bound prefix. On `datediff + 1` the prediction said 10-25%: 21.1%
   at 128-bit is inside it, 5.7% at AVX-512 is below it.
3. **`NULL` at 0.6x-0.8x of `WRAP`: missed at both widths, in opposite
   directions, and measured on a different shape.** `try_add(i, 1)` runs at
   0.90x of the wrapping add at AVX-512, above the range; at 128-bit it is
   0.21x, far below it. The prediction reasoned from the forfeited dense body
   alone and missed the mask-to-long disposal, which is the larger cost in
   narrow lanes and nearly free in wide ones (9.1).
4. **Throughput: both halves missed, in the same direction.** The composite
   key was predicted at 3x-6x Janino and reads 9.3x and 7.9x. `datediff + 1`
   was predicted within 10% of the `datediff` row's ratio and reads 9.1x
   against that row's 6.7x, and 9.2x against 6.2x - 36% and 48% above it. Both
   misses have one cause the prediction did not allow for: an added lanewise
   operation is nearly free in the kernel and is a whole per-row operation in
   Janino, so lengthening a fused expression *raises* the ratio.
5. **The overflow differential: hit.** Under ANSI the overflowing batch
   declines, the row engine raises, and the condition and message are equal to
   the row engine's own for `+`, `-` and unary minus; `try_*` nulls exactly
   those lanes with no decline; `LEGACY` wraps value for value with no
   decline. All asserted in `VarkaDifferentialSuite`.
6. **The composite key on its dense twin: hit.** `loopMasked0` and
   `epilogueMasked` are byte-equal to their dense siblings under `WRAP`, and
   the mixed-null row is within 0.1% and 0.07% of the null-free one (9.2).
   Under `FAIL` the masked loop is larger, as the same prediction said it
   would be.

### 9.6 What moved that the plan did not list

**The benchmark is its own file.** Section 6 put these rows in
`VarkaEmitterParityBenchmark`. The owner's instruction during the work was to
give a new expression family its own benchmark class and its own results
files, so `VarkaArithmeticBenchmark` and its three files are what shipped. The
practical gain is that this task's numbers regenerate in four minutes rather
than inside a file whose other rows it never touched.

**`i + 1` stopped being residual, and nine places depended on it.** Six
suites and three benchmark cases used it as the entry the compiler would
refuse. Two failed once the arm landed; the rest kept passing while asserting
or measuring something else, including three committed throughput rows
labelled "partial fusion" over a query that was now fully fused. They use
`i % 7` now. This is why `VarkaThroughputBenchmark` was regenerated at all:
not because this task's arithmetic changed those numbers, but because the
queries behind three of its rows had to change to keep meaning what their
labels say. It also gained four rows - the three task 63 queries and the
fully fused twin of the mixed projection - so the pair in 9.3 is two committed
rows of one file rather than a comparison against a number in a scratch log.
The lesson is written up in `SKILLS.md`.

**The bound analysis shipped unsound, and the review caught it.** The three
ways are recorded in 9.7 with the fixes; the shortest statement is that a
bound must be exact, must not assume a runtime guard that nothing arms, and
must be compared against `Int.MaxValue` rather than `MIN_VALUE`'s magnitude.

**A checked multiply declines, which is wider than 3.3 assumed.** The plan
expected `i * 7` under ANSI to fuse with a check. There is no int-lane
overflow test for `*` that does not need the 64-bit product or a lane
division, so the compiler declines unless the operands' bounds prove the
product safe. `year(d) * 100` fuses; `i * 3` under ANSI does not.

**The bound analysis was not in the plan and does most of the work.** 3.1
described a check on every ANSI node. What shipped computes an absolute bound
per node - literals by value, calendar fields by their definitions, `datediff`
by the contract width - and emits `WRAP` where the bound rules overflow out.
That is what makes the composite key fuse under ANSI at all, and it is why the
`i * 7` case above is a decline rather than a wrong answer. *The `datediff`
clause is corrected in 9.7: the contract width is that node's bound only where
both of its operands are themselves bounded, which the review found it was not
asking. What the sentence describes is what shipped, not what stands.*

**Three emitter bugs the differential found, which the unit tests had not.**
The accumulator was never allocated for a checked node with no other guarded
producer; the guard's word was killed by task 70's liveness pass, so
`guardedWord` had to learn about this third guarded node kind (and was later split from
`guardScratch`, 3.4); and `AND`, `OR`
and `XOR` are declared `Associative` rather than `Binary` in the Vector API,
so the emitted `getstatic` needed a different descriptor and failed at link
time until it got one.

**The day offset took arithmetic too.** Step 4 widened `compileOffset` and
`compileIntOperand`, so `date_add(d, off * 7)` and `make_date(y + 1, m, d)`
fuse. A calendar node over such a producer is guarded rather than declined,
because task 52's range analysis already reads any non-literal offset as a
column shift.

### 9.7 What the review found, after the task was written up

*Added 8 September 2026. Two max-effort reviews ran on this PR: one over the
task, one over the fixes that first one produced. Together they found four
bugs in code this task added and four more in the fixes, and the pattern in
them is what `PLAN_MILESTONE_5.md` 2.13 is built on.*

**Three wrong answers, all in the bound analysis of 9.6.** The bound was
compared against `abs(Int.MinValue)`, so a magnitude of exactly 2^31 - one past
the largest int - proved an operation safe: `quarter(d) * 536870912` answered
-2147483648 under ANSI where Spark raises. `intBound`'s `datediff` arm returned
the contract width for every `IRDateDiff`, but `date_add(d, 2147483647)` is a
legal operand whose int32 lane wraps, and over one the contract width is a
fiction. And nested bounds were combined with wrapping `Long` arithmetic, so a
bound past 2^63 came back small and positive and proved anything at all. None
of the three was reachable by a missing switch arm, which is why all three
survived to a review rather than to a compile error.

**One ghost fallback.** `compileOffset` admitted any int-typed `Add` as a day
offset, and `weekday(d2) + 1` lowers to task 57's `DayOfWeekIso`, which
`requireDayOffsetShape` does not take - so the entry was fused in EXPLAIN and
refused at emit time, where the evaluator turns the refusal into a silent
per-batch fallback. Two independent statements of one admission rule, drifting,
which is what `PLAN_MILESTONE_5.md` 2.17 (task 86) proposes to end.

**Then four more, in the fixes themselves**, which is the part worth reading
twice:

* The `datediff` fix was made one node deep. Asking an operand for its day
  range "with no guard assumed" is right at the top of a `datediff` and wrong
  inside it: a calendar node *within* the operand does arm task 52's guard on
  the producers below it, so `datediff(last_day(date_add(d, i)), d2) + 1` lost
  a bound it correctly had and gained a check it does not need. Guard
  dependence descends: `dayRange`'s `guardsBelow` turns it back on under a
  calendar node.
* The same flag defaulted to the unsafe value, so a future caller would
  reintroduce the fixed bug by omitting an argument rather than by writing one.
  The default is gone; both call sites say which mode they mean.
* The fuzzer's own bounds had the identical `Long` wrap, and the first fix
  saturated only the arms that can reach 2^63 unaided - which is not enough,
  because `Long.MaxValue` wraps negative the moment a parent adds anything to
  it. Every arm that combines two bounds saturates now, in `boundsOf` and in
  the generator.
* And 3.4's instruction to "extend `guardedWord`, do not add a condition beside
  it" had become the wrong advice, with a silent failure mode rather than the
  loud one it promised. Corrected in place, because a plan that tells the next
  editor to do the thing that produces a wrong date is worse than no plan.

**The dead local was not free, which the regeneration found and the review did
not.** Splitting that predicate removed a `guardTmp` slot every checked node had
reserved and no instruction had read - dead code, and reported as tidiness. But
a slot change moves the emitted bytecode, so `sql/varka/AGENTS.md` requires the
results files be regenerated rather than the figures patched, and that
regeneration moved the AVX-512 masked row 26.1%, from 14706.5 to 18542.2 M
rows/s. The 128-bit row did not move at all.

*Caveated on 9 September 2026 by `PLAN_MILESTONE_5.md` 2.21.* That 26.1% is a
diff between two regenerations rather than an A/B inside one run, and it was
taken unpinned. The later investigation measured what an *unchanged* parity
file does between two runs on this machine: pinned, 22 of 211 cases move more
than 10% and the worst is 26%; unpinned the worst is 75%. So the magnitude
here is inside the noise band and is not evidence, though the mechanism may
still be real - the emitted bytes did change, and a dead local does change
register pressure. Task 82 inherits the claim and should re-take it pinned and
repeated before scoping itself on it. So an unused local was costing
fifteen points of throughput at one width and nothing at the other - a register
pressure signature, the wide body having more live vector values and no headroom
- and 9.1's original attribution of that cost to the mask disposal was wrong at
AVX-512, though it stands at 128 bits. The lesson is in `SKILLS.md`; the
consequence for task 82 is that it is a 128-bit task, and its own scope section
now says so.

*Withdrawn as evidence on 24 September 2026, by the band item 49 of
`SCOPE_MILESTONE_7.md` asked for.* Ten pinned runs of the arithmetic benchmark
at 512 bits (`VarkaArithmeticBenchmark-jdk25-band.txt`) put the row that moved -
`i + 1`, ANSI, checked, mixed nulls - in tier 3, with a spread of 26.03% between
its fastest and slowest run of the same code. A 26.1% move on that row is the
width of its own noise, so it says nothing about the dead local either way. The
mechanism is not refuted, only unmeasured: re-taking it needs the two code
states built and run as an A/B inside one pinned session, which no one has done.
Task 82's narrowing to "a 128-bit task" rested on this figure and should be
re-read without it.

**What is registered rather than fixed.** `intBound`'s calendar constants
(`IRYear` 40000 and its siblings) hold because task 52's guard fires, and that
guard sits behind `VarkaEmitOptions.guardDayProducers`. With that option off -
never in production, and the option's own javadoc already says a lane is then
"computed wrongly rather than declined" - the constants are fiction and an ANSI
check can come off a shape that needs it. So this task gave that switch a
second job: it no longer only decides whether a date field is right, it decides
whether an overflow check exists. The same is true of the date-column contract
itself, which is a declared assumption nothing enforces at ingestion
(`docs/sql-varka.md`) and which now also gates overflow checks. Both are the
unfixed half of the fixed bug class, and both are what
`PLAN_MILESTONE_5.md` 2.15 (task 84) exists to answer: one lattice in which
"what does a runtime guard prove" is an explicit parameter rather than a fact
baked into a constant.

**A third review, over the fixes.** It found no wrong answer - the two rounds
before it took those - but it found one test the suite never had and one
benchmark that could not have caught itself being wrong.

The test: `compileIntOperand`'s own doc says `make_date(y + 1, m, d)` fuses and
`VarkaExpressionCompilerSuite` pins the IR that widening produces, but nothing
had ever emitted that IR, run it, or checked a value it computed - the fuzzer's
`make_date` arm reads a date's own fields back and never arithmetic over one of
them. So the shape this task documents as its widest reach was, at the level
where a wrong answer would appear, untested. It is a value matrix now, over
every null pattern and both widths, against the reference evaluator; its triples
exclude `MAKE_DATE_MAX_YEAR` and every February 29, both of which a `+1`
correctly *declines*, and both of which belong to task 42's decline test.

The benchmark: `fill` was called twice with the same arguments to make two
columns, so the second was a bit-for-bit copy of the first and every `datediff`
case here computed `datediff(x, x)`. The throughput it published was real - the
same bytes, the same lanes, the same emitted kernel - which is exactly why
nothing caught it and why the requoted rows move so little. But a benchmark
whose second column is a copy of its first can never be extended into a value
check, because nothing it computes can be wrong; the fixture now takes a shift,
and 9.1's note records which rows that moved.

And four sentences that had stopped describing the code beside them, including
`emitOverflowMask`'s claim to be the only place in the emitter that narrows a
word after storing it - `emitMakeDate`'s non-ANSI tail does the same thing. That
is the same category as the corrected instruction above and the same reason it
matters: prose is what the next editor reads before deciding what is safe.

### 9.8 What this leaves for later

* **A checked node under a `CASE` arm condemns the batch from the untaken arm.** The
  `FAIL` mask goes through `emitGuardCollect`, which ANDs the node's word and the
  epilogue mask but not the enclosing `IfElse`'s condition, and a vector body computes
  both arms - so `CASE WHEN d < DATE'2020-01-01' THEN i + 1 ELSE year(d) END` falls
  back on a batch whose overflowing row the condition sends to the `ELSE` arm. The
  answers stay right and only the fusion is lost, on exactly the data the check is
  there for. This is milestone 4's task 79, which owned the cliff for guarded day
  producers and `add_months`; this task adds a third node kind to it, and that row is
  widened rather than a new one opened. A user cannot even write the natural guard
  (`i < 2147483647`) as the condition, because a bare int column does not compile in
  predicate position - so the reachable shapes are date- or field-driven conditions.
* **The masked check's mask-to-long disposal** (9.1) costs 66% at 128-bit on
  a shape whose arithmetic is one add. That is a kernel-level finding about
  `emitGuardCollect`, which task 52's range guard shares, so it is worth its
  own task rather than a note here.
* `/`, `div`, `%` and the int64 lane stay out, as 3.2 said.
* The status bit is still shared with the calendar range guard, so telemetry
  cannot tell an overflow decline from a range decline (7.4). Unchanged, and
  still the owner's call.
