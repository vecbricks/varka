# Task 60: add_months with a month-count column

## 1. Where this came from

`PLAN_MILESTONE_4.md` row 60 and section 2.27, from the coverage survey of 4
September 2026. Task 40 covers `add_months(d, 12)` and `d + INTERVAL n MONTH`
with a literal count, bounded at compile time to
`VarkaChrono.MONTH_ARITH_MIN/MAX_MONTHS` (-24576..24564) because the
lowering's magic division by 12 is exact only there. A month-count **column**
declines today with "month count is not a foldable literal". The column form
is task 38's widening (`date_add` with a column offset) applied to
`AddMonths`, with task 52's runtime guard attached where the compile-time
bound used to be: the count becomes a `ColumnRef` operand, and the bound is
checked on the count lanes per batch instead of on a literal at plan time.
Task 59 did the same widening for `next_day` on its open branch, so this task
retires the last use of the emitter's literal-only rule.

## 2. The admission check, done

**The lowering is lanewise in the count.** `emitAddMonths` (checked in this
worktree) reads the count once, through `emitValue(node.months())`, into
`k = (month - 1) + months + MONTH_ARITH_BIAS`; every later step works on `k`,
`q = k / 12`, `nm` and `ny`. Nothing else in the method depends on the count
being constant, and a `ColumnRef` at that position is one masked load in
place of a broadcast. The node's validity word is already the AND of the
date's and the count's words (`planWordRef`, and the `emitAndWord` the arm
does by hand), so a null count nulls its row today and nothing changes there.

**The exactness domain is the count's alone.** The magic multiply needs
`k * MONTH_ARITH_M < 2^31` with `k >= 0`, which is exactly `months` in
`[MONTH_ARITH_MIN_MONTHS, MONTH_ARITH_MAX_MONTHS]` (`VarkaChrono`'s
derivation); the date's own range is the calendar prefix's, admitted by
`calendarInput` as for every chrono node. So the runtime check is two compares
on the count lanes against the two constants, and that is the whole guard.

**The result never leaves the narrowed range on its own.** An in-range count
shifts the day by at most `31 * 24564 = 761,484` days forward and
`31 * 24576 = 761,856` back; the contract column's slack to the narrowed
range is 8,449,747 forward and 4,675,410 back (`PLAN_TASK_52.md`). So under a
runtime count guard the output is *bounded*, and task 52's `dayRange` can
answer `Bounded` for a column count - the child's interval shifted by
`[31 * MIN, 31 * MAX]` - rather than the `ColumnShifted` section 2.27 named.
That is tighter, it composes (`year(add_months(date_add(d, 8000000), m))`
declines at compile time as it should), and it needs no second guard on the
result: the count guard is the one guard. Recorded as a correction to 2.27.

**The interval spelling.** `d + CAST(i AS INTERVAL MONTH)` reaches the
compiler as `DateAddYMInterval(d, Cast(i, YearMonthIntervalType(MONTH)))`,
with no extraction wrapper (unlike days). `Cast`'s
`intToYearMonthInterval` returns `v` unchanged for an end field of `MONTH`
and `multiplyExact(v, 12)` for `YEAR` (checked in this worktree), so the
`MONTH` cast is exact and unbounded - admitted as the column itself, the way
task 56 admits the day cast with a bound - and the `YEAR` cast, which can
throw, declines. `d - INTERVAL m MONTH` arrives as `UnaryMinus` over the cast
and declines until task 63's negate composes with it.

**What the check would have rejected:** a lowering that folded the count
into emit-time constants (as `next_day` once did its weekday), a slot plan
that depended on the count, or a shift bound wider than the range's slack.
None is the case.

## 3. The design

### 3.1 The count as a column, the bound as a guard on its lanes

**The compiler.** `foldMonths` becomes `compileMonths(months, inputs, sink)`,
returning the operand: a foldable count folds to a bounded `LiteralSlot` as
today (same two reasons for a null or out-of-range literal); an
`IntegerType` `BoundReference` becomes `columnRef(br, inputs)`; the pattern
`MonthIntervalOffset(br)` - `Cast(br, YearMonthIntervalType(_, MONTH))` over
an int column, the `DayIntervalOffset` twin - becomes the same; anything else
declines with "month count is neither a foldable literal nor an integer
column". A stored `YearMonthIntervalType` column declines too, by name: the
Arrow cache holds it as an `IntervalYearVector`, which `isArrowBacked` does
not read, so admitting it would fuse at plan time and refuse every batch.
Both arms (`AddMonths`, `DateAddYMInterval`) call it. `dayRange`'s
`AddMonths` arm answers `shifted(days, 31 * MONTH_ARITH_MIN_MONTHS,
31 * MONTH_ARITH_MAX_MONTHS)` for a non-literal count.

**The emitter.** `analyze`'s `AddMonths` arm calls `requireOffsetShape`;
`requireLiteralOffset` has no caller left and goes (task 59's branch rewrites
its javadoc; whichever merges second deletes it). `Analysis.guardedProducers`
widens from "column-offset `AddDays`/`SubDays` under a calendar node" to
"column-driven producers": `collectGuardedProducers` adds every `AddMonths`
whose count is not a `LiteralSlot`, wherever it sits, because this guard
protects the node's own arithmetic, not a consumer's. `emitProducerGuard`
generalises to `emitRangeGuard(cb, node, guardTmp, dense, s, lo, hi)` with
the day producers passing the narrowed-range constants; `emitAddMonths`, when
`s.guardTmp` holds the node, guards the *count* vector right after
`emitValue(node.months())` with `MONTH_ARITH_MIN/MAX_MONTHS`: dup, park, two
compares, OR, AND with the node's word in the masked body (a null count's
lanes are undefined and must not condemn the batch; the node's word is the
AND of both inputs), AND with the epilogue mask, OR into `s.guardAcc`.
`emitStatusReturn` and the driver's OR are untouched: an out-of-range count
returns `STATUS_CHRONO_RANGE` and the evaluator recomputes the batch on the
row engine, which computes any int count. The guard sits behind
`VarkaEmitOptions.guardDayProducers` with the day guard, as section 2.27
says: on by default, off only as the parity benchmark's reference variant,
and `guardAcc` is allocated only when a body reaches a guarded producer, so
every literal shape stays byte-identical under either setting.

**What a user observes.** `add_months(d, m)` and `d + CAST(m AS INTERVAL
MONTH)` fuse; a batch with a count past about 2047 years either way is
computed by the row engine and counted under `numFallbackBatchesDeclined`;
answers are the row engine's in every case.

### 3.2 What is deliberately unchanged

* The literal form: `add_months(d, 13)` folds, bounds and emits as before;
  its bytes do not move (asserted on method sizes under both option values).
* `emitAddMonths`' arithmetic, the chrono prefix, `MONTH_ARITH_*`.
* `dayRange`'s literal arm and the compile-time decline of an out-of-range
  literal: a literal that would always decline at run time declines at plan
  time, for free.
* The guard block's shape and the status plumbing (task 52); `IntRangeOps`
  (task 64's pre-check can later pick an unguarded kernel for counts too).
* `INTERVAL YEAR` casts, `d - INTERVAL m MONTH` (task 63), stored
  `YearMonthIntervalType` columns: residual, each with its reason.
* The IR fuzzer's `AddMonths` arm keeps a literal count: its columns hold
  day-magnitude values, so a column count would decline every batch and the
  fuzzer asserts status zero.

### 3.3 Registered op counts

Dense-loop `IntVector` calls in `loopDense0`, printed with `dev/varka_emit.sh`
(`--table`, columns `d:date,off:int`) in this worktree before any change;
the register test in section 5 asserts the after column.

| kernel | before | after |
|---|---|---|
| `add_months(d, 13)` | 112 | 112 (unchanged, asserted) |
| `add_months(d, m)`, `m` a column, guard on | - | 114 (predicted) |
| `add_months(d, m)`, guard off | - | 112 (predicted) |
| `year(add_months(d, 13))` | 144 | unchanged |
| `year(date_add(d, off))`, task 52's guarded shape | 38 | unchanged |

The prediction: the count's load replaces its broadcast (one call either
way, task 38's delta), and the guard adds its two `compare` calls; the mask
`or`/`and`/`fromLong` calls are `VectorMask` invocations the register does
not count.

## 4. Files

| file | what |
|---|---|
| `VarkaLoopEmitter.java` | `analyze`'s arm; `collectGuardedProducers`; `emitRangeGuard` from `emitProducerGuard`; the count guard in `emitAddMonths`; `requireLiteralOffset` removed; the `guardedProducers` javadoc |
| `VarkaVectorIR.java` | the `AddMonths` record doc: a literal slot or a column |
| `VarkaExpressionCompiler.scala` | `compileMonths`, `MonthIntervalOffset`, the two arms, `dayRange`'s column arm, the reasons |
| `VarkaLoopEmitterSuite.scala` | the guard tests (loop lane, epilogue lane, null count, in-range matrix, both option values); the register; the literal form's sizes |
| `VarkaIrFuzzSuite.scala` | a comment on why the arm stays literal |
| `VarkaExpressionCompilerSuite.scala` | the shapes, the interval spelling, the declines, `dayRange` composition at the bound |
| `VarkaSharedSessions.scala` | `cacheDatesMonthCounts` |
| `VarkaDifferentialSuite.scala` | in-range and out-of-range counts, nulls, the interval spelling, the declined metric |
| `VarkaEmitterParityBenchmark.scala` + files | `add_months(d, m)` guard on/off, both null patterns, beside task 52's rows |
| `VarkaThroughputBenchmark.scala` + files | `add_months(d, m)` and its literal control over a month-count fixture |
| `docs/sql-varka.md`, `SKILLS.md` | the surface line and the reasons; a note under task 52's lesson |
| `PLAN_MILESTONE_4.md`, this file | row 60, the 2.27 correction, section 9 |

## 5. Tests, and what each is for

The oracle is `DateTimeUtils.dateAddMonths`, the definition, as task 40's
reference arm already has it; it is null-correct over a nullable count.

* **Emitter, the guard.** `AddMonths(col0, col1)` as the root: a lane with
  the count at `MONTH_ARITH_MAX_MONTHS + 1` in a loop lane returns
  `STATUS_CHRONO_RANGE`; the same in an epilogue-only batch (17 rows);
  `MONTH_ARITH_MIN_MONTHS - 1` likewise; the same lane under a null count
  returns zero; a null date beside a bad count returns zero; both bounds
  themselves compute. Under `withGuardDayProducers(false)` every case returns
  zero. The failure it catches: a guard on the wrong vector, or one that
  reads a null lane's undefined data.
* **Emitter, the matrix.** `checkMatrix` over `AddMonths(col0, col1)` and
  `Year(AddMonths(col0, col1))`, the count cycling across the whole bound
  including both ends, every null pattern of both columns, both widths,
  both option values: the failure is an off-by-one at the bound or a word
  that ignores the count's nulls.
* **Emitter, the bytes.** `codeSize(loopMasked0)` of `AddMonths(col0,
  LiteralSlot)` identical under both option values; the register asserts
  3.3's after column and that `add_months(d, 13)` stays at 112.
* **Compiler.** `add_months(d, m)` and `d + CAST(m AS INTERVAL MONTH)`
  compile to `AddMonths(ColumnRef(0), ColumnRef(1))` with no literal;
  `d + CAST(m AS INTERVAL YEAR)`, `d - INTERVAL m MONTH` (the `UnaryMinus`),
  `add_months(d, m + 1)` and a `YearMonthIntervalType` column decline with
  their reasons; `year(add_months(d, m))` fuses; `year(add_months(date_add(d,
  shiftHi - 761484), m))` fuses and one day further declines with the
  interval named - the `dayRange` composition that `ColumnShifted` would have
  waved through.
* **Differential**, over `varka_date_months` (`d`, `m` with counts in range,
  at both bounds, `null`, and `30000` / `-30000` past them; one partition):
  `add_months(d, m)` and `d + CAST(m AS INTERVAL MONTH)` match the row
  engine with `numFallbackBatchesDeclined > 0` and the kernel and row-path
  counters at zero; the same over `WHERE m BETWEEN -24576 AND 24564` (a
  fused filter) with the declined counter at zero; `year(add_months(d, m))`
  through the filter route; `cacheDatesNullableOffset`'s `off` column as an
  in-range count with independent nulls.

## 6. The measurement

`VarkaEmitterParityBenchmark`, the year section, beside task 52's pair:
`add_months(d, m)` with the guard on and off, null-free and mixed nulls,
`nf2Data`/`mx2Data` as the count column (values in `[-10000, 10000)`, inside
the bound, so the status must read zero); `add_months(d, 13)` is the control
that must not move, and task 52's `year(date_add(d, off))` pair says what a
guard costs on a light kernel for comparison with this heavy one. Both
widths, regenerated with `dev/varka_bench_regen.sh catalyst
VarkaEmitterParityBenchmark` on an idle machine. `VarkaThroughputBenchmark`:
`add_months(d, m)` over a new 2M-row `varka_date_months` fixture (counts in
`[-120, 120]`) beside `add_months(d, 13)` on the same fixture as the control.

### 6.1 Predictions, registered before the run

1. The register: 114 with the guard, 112 without; the literal row unmoved.
2. The count guard costs under 4% null-free and under 6% with mixed nulls at
   both widths: task 52 measured its guard at 0.02-0.07 ns per row on a
   38-call kernel, and this kernel is 112 calls, so the same absolute cost
   is a third of the share.
3. The column kernel with the guard off runs within 3% of the literal
   kernel: one load in place of a broadcast on a 112-call body.
4. Throughput: the column row within 10% of the literal row, both above
   the baseline by the ratio the `add_months` row already shows.

## 7. Risks

1. **A guard on the count that reads a null lane** and declines a batch it
   should compute: the null-count guard test and the mixed-null matrix.
2. **`guardAcc` not allocated for a root-level `AddMonths`**: `reaches`
   tests the root itself first (checked), and the root-as-producer test
   covers it.
3. **The `dayRange` correction being wrong** - a count in range producing a
   day outside the narrowed range: the arithmetic in section 2, and the
   matrix at both bounds under `Year`.
4. **Conflicts** with task 59 (the `requireLiteralOffset` doc versus its
   deletion; `analyze`'s neighbouring arm) and task 42 (compiler arms): all
   additive or one-sided; whichever merges second takes them.
5. **The option off producing wrong answers** for out-of-range counts: by
   design a reference variant, documented on the option as task 52 did.

## 8. Sequencing

1. The emitter: `requireOffsetShape`, the widened `guardedProducers`,
   `emitRangeGuard`, the count guard, the tests and the register. Green
   alone; the compiler still declines a column, so nothing user-visible
   changes and no pinned value moves.
2. The compiler and the differential: `compileMonths`,
   `MonthIntervalOffset`, `dayRange`'s arm, the fixture, the docs.
3. The measurement: both benchmarks regenerated on an idle machine, section
   9, the milestone row, the SKILLS.md note.

## 9. Outcome

Built as sections 3 and 8 describe, in two commits (emitter, then compiler
and differential), with one correction found by the tests and recorded in
`SKILLS.md`: the guard reads the node's own validity word, which for
`AddMonths` was computed by the dispatcher after `emitAddMonths` returned,
so the AND-of-words moved inside the method, right after the count loads.
Both benchmarks were then regenerated after the review of PR #128 (section
10), because that review changed what is measurable here: the count guard
became unconditional, so the guard-on/guard-off A/B this section was built
around emits identical bytes and measures nothing. The committed pair is now
the column count against a *literal* count on the same two-stream runner,
adjacent in the file, which is also what review finding 20 asked for - the
previous control (`add_months(d, 13)`, one stream) sat dozens of cases away.

Both files come from `dev/varka_bench_regen.sh` at both widths on the idle
machine under the `performance` governor: parity at load 0.61 (canary
compute +0.2%, cache +2.1%, memory -0.5%), throughput at load 0.59 (canary
compute +0.2%, cache +1.9%, memory -1.0%). The throughput run needs the
fully-qualified class - the script resolves a bare name to
`org.apache.spark.sql.<Class>` and this benchmark is under
`org.apache.spark.sql.execution.benchmark` - which is the "corrected class
path" the first version of this section referred to. Rates in M rows/s from
the committed files.

| case | 256-bit | 128-bit |
|---|---|---|
| `add_months(d, m), column count (task 60), null-free` | 693.0 | 238.9 |
| `add_months(d, 13), literal count (task 60 control), null-free` | 727.3 | 253.1 |
| `add_months(d, m), column count (task 60), mixed nulls` | 624.3 | 209.4 |
| `add_months(d, 13), literal count (task 60 control), mixed nulls` | 677.8 | 241.9 |
| `add_months(d, 13), null-free` (the one-stream case, id 811) | 727.5 | 252.5 |
| `year(date_add(d, off))`, guard on / off (task 52's pair, null-free) | 2933.6 / 3193.8 | 1179.9 / 1246.1 |
| `year(date_add(d, off))`, guard on / off (task 52's pair, mixed nulls) | 1817.5 / 2076.5 | 648.7 / 727.2 |
| throughput `add_months, column count (task 60)`, varka / Janino | 185.2 / 25.3 (7.3x) | 120.5 / 25.6 (4.7x) |
| throughput `add_months, literal (task 60 control)`, varka / Janino | 189.2 / 29.0 (6.5x) | 121.7 / 28.8 (4.2x) |

The column form costs **-4.7% null-free and -7.9% with mixed nulls at 256
bits, -5.6% and -13.4% at 128**, against the literal control. That delta is
the guard *plus* one column load in place of a broadcast, and it cannot be
split further with this harness - see prediction 3. The second stream itself
is free: the two-stream literal control reads 727.3 against the one-stream
`add_months(d, 13)`'s 727.5 at 256 bits and 253.1 against 252.5 at 128, so
the runner change review finding 20 objected to was not distorting the old
comparison, though the adjacency it asked for is still the right structure.

**Predictions scored.** Predictions 1 to 3 were written against a
guard-on/guard-off A/B and were scored against it in the run this section
first carried; the review removed that variant, so each is marked with what
it said then and what the current file can say now. The original scorings
stand as a record of the code as it was, and are not restated as if they
were re-measured.

1. *The register: 114 with the guard, 112 without; the literal row
   unmoved.* Held exactly, and still asserted by the suite - but read the
   112 as the literal form's count, not as an option-off column form: after
   the review there is no option-off column kernel, and the register test
   now pins (literal 112, option-off 114, guarded 114). `year(date_add(d,
   off))`'s 38 did not move either.
2. *The count guard under 4% null-free and under 6% with mixed nulls at
   both widths.* Scored then: held at 256 bits (-2.9% null-free, -5.7%
   mixed) and for the null-free row at 128 (-3.8%); missed with mixed nulls
   at 128 bits, -12.8%. The overnight run read the same four within a point
   (-3.6%, -5.5%, -3.7%, -12.2%), so the miss was the shape, not noise:
   task 52's finding again - the masked body's guard pays
   `VectorMask.fromLong` for the word AND, and at 128 bits that is a larger
   share of a four-lane group. Not re-measurable now, because isolating the
   guard needs the variant without it. What the current file shows is
   consistent with it: the column-versus-literal delta widens in the same
   places (-7.9% mixed at 256, -13.4% mixed at 128), and task 52's own pair,
   still option-gated, still costs most with mixed nulls at 128 (648.7
   against 727.2, -10.8%).
3. *The column kernel with the guard off within 3% of the literal kernel.*
   Scored then: held, -2.1% at 256 bits and -1.1% at 128. Unscoreable now,
   and the reason is worth keeping: the prediction was probing whether one
   column load in place of a broadcast is free, and with the guard no longer
   removable that question is inside prediction 2's delta rather than beside
   it. The part that could still be checked was checked - the second input
   stream is free (727.3 against 727.5 at 256 bits) - so what is left
   unmeasured is the load-versus-broadcast difference alone, and separating
   it would take a measurement-only emitter option. That is deliberately not
   reintroduced: an option the compiler cannot see is what the review found
   wrong with the count guard in the first place.
4. *Throughput: the column row within 10% of the literal row, both above
   the baseline by the `add_months` ratio.* Held, and re-measured cleanly:
   -2.1% at 256 bits (185.2 against 189.2) and -1.0% at 128 (120.5 against
   121.7), at 7.3x and 4.7x over Janino against the literal's 6.5x and 4.2x.
   The Janino path is again slower with a column (25.3 against 29.0), so the
   ratio is higher for the column form for the same reason as before.

What moved that the plan did not list: the differential's "WHERE m BETWEEN
..." idea does not fuse - a bare int column is not a filter operand the
compiler reads (task 38's scope note) - so the guard-silent and filter-route
cases use the nullable-offset fixture and task 52's calendar-equality shape
instead; and the parity harness's run-to-run bimodality showed a third face:
the overnight file had the `CASE WHEN` and `arithmetic depth 4` rows at a
third of their value, this re-run has them in family and instead carries
the three task 45 "validity OR-ed per group" rows 21-46% under #121, none of
which this node's bytes touch. That movement belongs to the register's
"depressed-row cluster per regeneration" entry, which is what names this
run-to-run behaviour; no entry specific to those three rows exists yet, and
writing one needs the regeneration this task's numbers are pending anyway,
so the claim that it was "recorded" was ahead of itself. The task 60 rows
themselves agree across the two runs within one point.

Left for later: the narrow-width mixed-null guard cost belongs with task
52's `fromLong` finding, already a lesson in `SKILLS.md` and a candidate for
the mask-reuse it names; and task 69, opened by the review below.

## 10. What the review of PR #128 changed

Two findings gated the merge, and both were about the boundary between what
the compiler assumes and what the emitter guarantees.

**The composition hole.** `dayRange` had a third verdict, `ColumnShifted`,
meaning "a guarded producer is somewhere below, so admit this", and
`admitCalendar` admitted it with no range test. That is sound only while the
guarded producer is the calendar node's direct child. This task made it
unsound by adding a node that shifts a day by up to `31 *
MONTH_ARITH_MAX_MONTHS` above such a producer: `year(add_months(date_add(d,
off), m))` fused and answered year 87585 where the truth is -14848, with
both runtime guards passing on their own operands and nothing checking the
composition. The fix is that a guarded producer now contributes the interval
its guard establishes, `Bounded(NARROW_MIN_DAYS, NARROW_MAX_DAYS)`, so every
existing rule shifts it and `admitCalendar` tests the result; `ColumnShifted`
has no producer left and is deleted. The general lesson is in `SKILLS.md`:
encode what a runtime check establishes in the representation the analysis
already manipulates, never as a verdict meaning "trust me".

**The option the compiler could not see.** The count guard was registered in
`guardedProducers`, behind `VarkaEmitOptions.guardDayProducers`, while
`dayRange` returned `Bounded` for a column count unconditionally - so with
the option off the guard vanished and the compile-time bound stayed, which
is a wrong answer rather than a reference variant. It moved to
`selfGuarding`, task 42's set, whose criterion it always met: the check
protects its own magic multiply rather than insuring a consumer. That is
what makes the A/B in section 9 degenerate, and it also made the option's
name honest again - `guardDayProducers` now genuinely covers only day
producers, so the rename the review suggested is unnecessary.

**What the fix costs.** Stating the guarded producer's interval is correct
in both directions but tighter than needed upward: `last_day`, `next_day`
and a positive literal `date_add` over a column offset now decline although
they are exact, and so does `weekofyear` over a column offset, whose
`ThursdayOf` shifts both ways. Those declines are pinned by a test that task
69 exists to flip. The downward siblings - `trunc` over a column offset, a
negative literal - decline correctly and were answering wrongly before, on
master as well.

Everything else the review found was coverage, diagnostics and
documentation, applied in the same pass: the operand name restored to
`requireOffsetShape` (one message for four operands is what sent the IR
fuzzer's first failure, #110, hunting the wrong node), a live violation
driven through the masked epilogue, the `ownWord == false` branch covered,
the fuzzer given a small-magnitude column so it can reach a column count at
all, `canonical()`'s missing `truncDate`, and a `CASE`/`IF` fusion cliff
registered rather than fixed. `dev/varka_quote_check.py` also gained
`--full-history`: this branch's merge resolution had made 16 committed
numbers unreachable to it, which is a tooling bug rather than a bad quote.
