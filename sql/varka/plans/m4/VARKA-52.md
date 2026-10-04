# VARKA-52: guard at the producer, not the extraction

**Status: planned, not started; section 10 is the execution plan (third
version, 4 September 2026), written against a master where everything the
second version waited on has landed.** VARKA-51 (PR #73) removed the per-extraction
range guard VARKA-26 shipped; this task closes the gap that removal opened. Read
`VARKA-51.md` and `m4/PLAN.md` sections 2.21-2.22 first - they
carry the argument for why the guard moved rather than simply disappearing, and
this file assumes that argument rather than repeating it.

This is the second version of this plan. The first, written in the same PR as
the removal, guarded only `date_add`/`date_sub` with a *column* offset, on the
claim that "a literal offset's magnitude is visible to the compiler". Visible,
yes; bounded, no. `DateVarkaSupport.foldDaysOffset` accepts any `Int` literal,
nothing checks it, and `year(date_add(d, 20000000))` fuses today - the exact
query the removed differential test used - and after VARKA-51 returns a wrong
year. The first version would have left that in place. Section 2 below is the
corrected scope; section 1 is the rule that produces it.

If you find yourself making a design decision beyond what sections 1-3 settle,
stop and say so in the pull request instead of choosing.

## 1. The rule

A calendar node (`Year`, `Month`, `DayOfMonth`, `Quarter`, `DayOfYear`,
`LastDay`, `AddMonths` - everything `isChrono` admits) runs the narrowed
civil-from-days decomposition, which is exact only on
`VarkaChrono.NARROW_MIN_DAYS..NARROW_MAX_DAYS` (-5394572..11382643, i.e.
1 March -12800 to 15 August 33134). The project's contract is that a date
*column* holds `[0001-01-01, 9999-12-31]`, epoch days `[-719162, 2932896]`.
The narrowed range therefore has slack on both sides of the contract:

    -4675410 <= shift <= 8449747

A day value reaching a calendar node is a contract column plus whatever the IR
between them added. The question VARKA-26's guard answered at run time on every
extraction, and this task answers once, is: **how much can the IR between a
column and this calendar node shift the day?**

* **Bounded, and inside the slack** (`year(d)`, `year(date_add(d, 30))`,
  `year(add_months(d, 12))`, `year(next_day(d, 'MON'))`): the value cannot leave
  the range. No guard, at compile time or run time. This is almost every query.
* **Bounded, and outside the slack** (`year(date_add(d, 20000000))`,
  `year(date_sub(d, 5000000))`): the compiler knows the answer before anything
  runs. **Decline the calendar entry at compile time** with a task-16 reason; the
  row engine computes it correctly. Cost: nothing at run time, one residual
  entry in a query nobody writes.
* **Unbounded** (`year(date_add(d, offset_col))`, VARKA-38 / PR #62): the shift
  is a per-row value the compiler cannot see. **A runtime guard at the producer
  node** - the `AddDays`/`SubDays` whose offset is a column - and only when a
  calendar node consumes it, behind an emit option.

That is the whole task: an interval analysis in the compiler, and the old guard's
bytecode re-emitted at one node type instead of six, only where the analysis
says it is needed.

## 2. Scope, settled

* **The compile-time half is not behind the flag.** It is free, it is correct,
  and it is the only half reachable on today's master, where every day offset
  is a literal. It also lands independently of PR #62.
* **The runtime half guards `AddDays`/`SubDays` with a non-literal offset, and
  only those with a calendar node above them.** `date_add(d, offset_col)`
  projected on its own, or compared, or diffed, produces whatever `int` add
  produces - the same as Spark's `DateAdd`, which does not check either - and
  no decomposition ever reads it. Guarding it would decline batches Spark
  answers fine, for nothing. The emitter's analysis decides "has a calendar
  consumer" by walking each calendar node's subtree, not by looking at the
  producer alone.
* **`NextDay` and `AddMonths` need nothing new.** `NextDay`'s shift is `1..7`
  by construction (VARKA-33 accepts only a foldable weekday). `AddMonths`'s
  literal month count is already bounded by VARKA-40's compile-time decline at
  `MONTH_ARITH_MIN/MAX_MONTHS` (`+-24564` months, about 2047 years, so at most
  `+-24564 * 31` days: inside the slack from any contract date, and inside it
  from anything a bounded `AddDays` chain leaves inside it too). Both enter the
  interval analysis as bounded shifts; neither gets a case of its own.
* **A bare `ColumnRef` is the contract, not a check.** No ingestion-time check;
  the contract is what makes the slack computable at all.
* **Every calendar extraction stays guard-free**, per VARKA-51.
* **Pass-through nodes (`Greatest`, `Least`, `IfElse`) take the hull** of their
  date operands' intervals. `DateDiff` produces a day *count*, not a date; a
  calendar node cannot consume it (the compiler's type gate), so it is not in
  the analysis.
* **The runtime guard's flag is `VarkaEmitOptions.guardDayProducers`**, default
  chosen by section 5's measurement, expected on: it costs nothing on any
  query with no column-offset date arithmetic under a calendar function, and
  section 5 prices what it costs on the one shape that has it.

## 3. Mechanics

### 3.1 The compiler: `dayShift`, and where it declines

In `VarkaExpressionCompiler`, beside `foldOffset`/`foldMonths`, a total function
over the IR the calendar arms have already built for their child:

    dayShift(node): Option[(Long, Long)]        // None = unbounded
      ColumnRef            -> Some((0, 0))
      AddDays(x, lit k)    -> dayShift(x) map { (lo, hi) => (lo + k, hi + k) }
      SubDays(x, lit k)    -> dayShift(x) map { (lo, hi) => (lo - k, hi - k) }
      AddDays/SubDays(x, non-literal) -> None
      NextDay(x, _)        -> dayShift(x) map { (lo, hi) => (lo + 1, hi + 7) }
      AddMonths(x, lit m)  -> dayShift(x) map { (lo, hi) => (lo + min(31m, 28m), hi + max(31m, 28m)) }
      Greatest/Least/IfElse over dates -> hull of the date operands' intervals
                                          (None if any operand is None)
      any calendar node    -> Some((0, 0))   // its output is a field or a date it
                                             // recomposed inside the range (AddMonths);
                                             // a date-typed calendar output (LastDay,
                                             // AddMonths) re-enters the contract range
      LiteralSlot as a date operand (IfElse branch) -> Some((0, 0)) if the literal is a
                                             date in [0001, 9999]; the compiler's literal
                                             arm already restricts to DateType

Longs, not ints, so two literals of 2 billion cannot wrap the sum. The literal's
value comes from the compiler's own `literals` table, which the arm building the
`LiteralSlot` just wrote; the analysis runs in the calendar arms (`Year(child)`,
`LastDay(child)`, `AddMonths(startDate, ...)` and the rest), after
`compileNode(child, ...)` returns, on the IR it returned.

The calendar arm then does one of three things:

* `Some((lo, hi))` with `lo >= -4675410 && hi <= 8449747`: build the node as
  today.
* `Some((lo, hi))` otherwise: `sink.note("day shift of [lo, hi] can leave the
  calendar range", expr)` and return `None` - the entry declines, the row engine
  serves it, verbose `EXPLAIN` says why (VARKA-16).
* `None`: build the node as today. The emitter decides the rest.

The two slack constants are named in `VarkaChrono` beside `NARROW_MIN_DAYS`/
`NARROW_MAX_DAYS`, derived from them and from the contract's two epoch days in
the source (not typed in as decimals), with the derivation in their javadoc.

### 3.2 The emitter: which producers, and where the bytes go

**Which.** In `Analysis`, beside `skipping`/`columns`: for each calendar node
(`isChrono`), walk its subtree and collect every `AddDays`/`SubDays` whose
`offset` is not a `LiteralSlot`. The union over all calendar nodes is
`guardedProducers`, a `Set<VarkaVectorIR>` on `Analysis`. Empty on every kernel
with no column-offset date arithmetic under a calendar node - which is what
makes the default cheap. The predicate that replaces VARKA-51's deleted
`hasChrono` in `planSlots` is: this body's outputs' subtrees intersect
`guardedProducers` (and `options.guardDayProducers()`). Only then is
`s.guardAcc` allocated, and `emitStatusReturn` already does the rest.

**Where.** PR #62 routes `AddDays`/`SubDays` through `emitAndValidatedOp`,
which leaves the result vector on the operand stack after `invokevirtual add`/
`sub`. When `node` is in `guardedProducers` and the option is on, immediately
after the op:

    dup                                        // keep the result for the parent
    astore  guardTmp(node)                     // one Object slot per guarded node,
                                               //   planned like dowTmp
    aload   guardTmp; getstatic LT; ldc NARROW_MIN_DAYS; invokevirtual compare
    aload   guardTmp; getstatic GT; ldc NARROW_MAX_DAYS; invokevirtual compare
    invokevirtual VectorMask.or
    [masked body]  aload species; loadWord(wordRef(node)); invokestatic fromLong;
                   invokevirtual VectorMask.and
    [epilogue]     aload epilogueMask; invokevirtual VectorMask.and
    aload guardAcc; invokevirtual VectorMask.or; astore guardAcc

This is VARKA-26's guard - the block VARKA-51 deleted from `emitEra`, at
`git show 35f4000c407:.../VarkaLoopEmitter.java` lines 2593-2619 - retargeted at
the producer's *output*. Two things carry over unchanged and for the same
reasons: the AND with the node's validity word (a null row's lanes are undefined
and must not condemn the batch; under #62 the node's word is the AND of the
date's and the offset's, so a null *offset* is covered too), and the AND with
the epilogue's bounds mask (the padding lanes of a partial group hold whatever
the masked load left, and `0 + Int.MinValue` is out of range). The dense body
skips the word AND: every lane is valid there.

The guarded node is emitted once per lane group whatever its use count -
`emitValue`'s `computed` set already guarantees that - so the guard runs once
per distinct producer, not once per consumer. That is the whole difference from
VARKA-26's placement.

### 3.3 What the flag does and does not gate

`guardDayProducers = false`: no `guardTmp` planned, no guard emitted,
`s.guardAcc` null, `emitStatusReturn` emits a constant zero - byte for byte
VARKA-51's emitter. The compile-time decline in 3.1 still applies; there is no
option that turns a free correctness check off.

## 4. Files

* `VarkaChrono.java` - the two slack constants and their derivation.
* `VarkaExpressionCompiler.scala` - `dayShift`, the check in every calendar arm,
  the decline reason string.
* `VarkaEmitOptions.java` - `guardDayProducers`, `withGuardDayProducers`,
  `canonical()`.
* `VarkaLoopEmitter.java` - `Analysis.guardedProducers`; the `planSlots`
  predicate and `guardTmp`; the block in `emitAndValidatedOp`; `emitEra`'s
  task-51 javadoc, which says the gap is "a column offset, not a literal one" -
  corrected to point here; `emitStatusReturn`'s doc ("a constant zero where
  nothing is guarded") is already right.
* `VarkaExpressionCompilerSuite.scala` - section 5.1.
* `VarkaLoopEmitterSuite.scala` - section 5.2.
* `sql/core/.../VarkaDifferentialSuite.scala` - section 5.3.
* `VarkaEmitterParityBenchmark.scala` and its committed file - section 5.4.
* `VARKA-51.md` section 4 was corrected by PR #76 to name the literal case
  and closes in the past tense with this task; `m4/PLAN.md` row 52 and
  section 2.22 likewise.

## 5. Validation

### 5.1 The compiler, by the bound's edges

In `VarkaExpressionCompilerSuite`, with the constants read from `VarkaChrono`
rather than retyped:

* `year(date_add(d, HI))` fuses; `year(date_add(d, HI + 1))` declines, with the
  reason naming the interval. Same at `LO` / `LO - 1` through `date_sub`.
* `date_add(d, HI + 1)` alone fuses - no calendar consumer, no bound.
* Two literals under the bound whose sum is over it decline:
  `year(date_add(date_add(d, 5000000), 5000000))`.
* The hull: `year(greatest(date_add(d, 5000000), d))` fuses (interval
  `[0, 5000000]`); `year(if(c, date_add(d, HI + 1), d))` declines.
* `year(add_months(d, MONTH_ARITH_MAX_MONTHS))` fuses; the composition
  `year(add_months(date_add(d, HI - 100), 12))` declines (12 months of days
  past 100 days of slack).
* `year(date_add(d, offset_col))` fuses (unbounded is not declined; that is the
  emitter's case) - this test needs PR #62 and is written on top of it.

### 5.2 The emitter, by the old guard's own cases

In `VarkaLoopEmitterSuite`, on top of #62:

* `year(date_add(d, off))` with `off` a column: a batch with one lane at
  `NARROW_MAX_DAYS + 1 - d` in the vector loop returns `STATUS_CHRONO_RANGE`;
  one in an epilogue-only lane likewise; the same lane under a null `off` row
  returns 0; an in-range batch returns 0. This is VARKA-51's rewritten
  `"a day outside the covered range is no longer declined"` test, un-rewritten
  and moved to the producer - and under `withGuardDayProducers(false)` every
  case returns 0, which is asserted too.
* `date_add(d, off)` alone, same out-of-range data: returns 0 under both flag
  values, and `codeSize` of its `loopMasked0` is identical under both - no guard
  was emitted for a producer with no calendar consumer.
* `year(date_add(d, off))`'s and `year(d)`'s `loopMasked0` sizes differ by the
  guard and nothing else; `year(d)`'s is identical under both flag values.
* The existing matrix, all null patterns and lengths, for
  `year(date_add(d, off))` under both flag values on in-range offsets: results
  identical, status 0 throughout.
* **Pinned oracles: not expected to move.** The `everyNode` fixture's `AddDays`
  has a `LiteralSlot` offset and so is never a guarded producer; the guard adds
  bytes, not nodes, so the line map's key is unchanged; `DEFAULTS` renders empty
  in the hash whichever way the default goes.

### 5.3 The differential, restoring what VARKA-51 removed

The two tests `VARKA-51.md` section 3 lists as removed, reshaped:

* `SELECT year(date_add(d, off)) FROM t` where `off` is a column holding
  20,000,000: with the flag on, `checkAnswer` against the row engine passes and
  `numFallbackBatchesDeclined > 0`, `numFallbackBatchesKernel == 0`; with the
  flag off, the batch runs on the kernel (`numFallbackBatchesDeclined == 0`) -
  asserted on the *metric only*, never on the value, for the reason
  `VARKA-51.md` section 3 gives for not encoding a wrong answer as green.
* The filter path: `WHERE year(date_add(d, off)) = year(d2)` under the same
  data, through `VarkaFilterExec`'s decline route, flag on.
* `SELECT year(date_add(d, 20000000)) FROM t`: the entry is residual, the plan
  shows the reason, and the answer matches the row engine - the compile-time
  half, which needs no flag and no #62.
* In-range `off` values on the same table still fuse and never decline, flag on.

### 5.4 The number that sets the default

`VarkaEmitterParityBenchmark`, a new case beside the "year" section's existing
ones, same 4096-row chunks, both widths, five iterations over two-second windows,
three runs, minimums for anything near 1.3x:

| case | flag off | flag on |
|---|---|---|
| `year(date_add(d, off))`, null-free, `off` in range | new | new |
| `year(date_add(d, off))`, mixed nulls | new | new |
| `date_add(d, off)` alone, null-free | new | must equal flag off exactly |

The guard is two compares, an OR, up to two ANDs and an OR into the
accumulator, once per lane group, on a body of ~50 ops; VARKA-26 measured its
own version of exactly this block at a few percent. If the flag-on column costs
more than 10% on the first row, that is a finding to explain before the default
flips; below it, the default flips on, since the cost is paid only by the shape
that needs it.

## 6. Predictions, registered before the run

1. The guard costs **3% to 8%** on `year(date_add(d, off))` at AVX-512, less
   than 10% at 128-bit (more lane groups, but the same six ops each).
   Confidence: medium-high - this is VARKA-26's guard, whose cost is in the
   committed record from before VARKA-51.
2. `date_add(d, off)` alone is byte-identical under both flag values.
   Confidence: high; asserted, not measured.
3. No committed number for any existing case moves: no existing case has a
   column-offset date under a calendar node. Confidence: high.
4. No pinned oracle moves. Confidence: high (5.2).
5. The compile-time half declines nothing in the milestone's corpus (TPC-H uses
   `year(d)`; TPC-DS pre-materialises fields). Confidence: high - and if it
   does decline something, the query has a shift past 4.6 million days and the
   decline is the right answer.

## 7. Risks

1. **The interval analysis is wrong in a direction that matters.** Too tight
   declines a correct query (slow, visible in `EXPLAIN`, not wrong); too loose
   lets a shifted value into the decomposition (wrong, silent). Every rule in
   3.1 is written to over-approximate the shift, and 5.1's edge tests are at
   `+-1` of the bound on purpose. `AddMonths`'s `31m` is the deliberately loose
   end.
2. **A null offset lane condemns a batch.** Covered by the word AND in 3.2 and
   by the null-offset case in 5.2, which is silent-and-slow if missed, not
   silent-and-wrong - but it would make every batch with a null offset fall
   back, which is the "silent total loss of fusion" VARKA-26's own javadoc warned
   about.
3. **A new date-producing node.** `dayShift` is total over today's IR by
   construction (a `default` arm that returns `None` is the safe failure: an
   unknown node is treated as unbounded, and a future column-driven producer
   would then need to join `guardedProducers` to get a guard). The emitter
   side's `guardedProducers` walk is a hand-picked pair of node types; its
   javadoc says so, and `dayShift`'s `None`-for-unknown is what turns a missed
   node into a decline rather than a wrong answer... only if the compiler is
   also taught that `None` from an unknown *producer* means decline, not
   "runtime guard elsewhere". Section 3.1's third bullet is therefore precise:
   `None` builds the node only when the `None` came from a column-offset
   `AddDays`/`SubDays`; `None` from an unrecognised node declines. Write it that
   way.
4. **Sequencing against #62.** The runtime half is meaningless without column
   offsets. Commit 1 does not depend on #62; commit 2 does (section 8).
5. **VARKA-49.** An exact long-lane decomposition would make the *range* check
   unnecessary for the extraction, but this task's check is about the query's
   arithmetic leaving the contract's neighbourhood, not about the lowering's
   exactness; 2.19's update note already says so. Nothing here changes for 49.

## 8. Sequencing

Two commits, in two PRs if #62 has not landed when the first is ready:

1. **The compile-time half**, off `master` once #73 has landed: the slack
   constants, `dayShift`, the calendar arms' check, 5.1's tests (minus the
   column-offset row), 5.3's literal-offset differential, the plan-file
   corrections in section 4. No emitter change, no flag, no pinned value, no
   committed number. This alone closes every instance of the task-51 gap that
   exists on master today.
2. **The runtime half**, on top of #62 and commit 1: the option, the analysis
   set, the guard block, 5.2, the rest of 5.3, 5.4's cases and one regeneration
   run, the default chosen and recorded here in a section 9 with predictions
   scored; `m4/PLAN.md` row 52 to DONE and 2.22 swept; the regression
   window `VARKA-51.md` section 4 records closed in the past tense.

## 9. Explicitly out of VARKA-52

* An ingestion-time check of the column contract itself.
* Guarding a producer with no calendar consumer (section 2, second bullet).
* Any change to the fragment mechanism, `emitEra`, or the calendar tails.
* Output reordering or any grouping change (VARKA-32 B2 owns grouping).
* VARKA-30's `try_*` and ANSI paths, which will want a status bit of their own -
  `STATUS_CHRONO_RANGE` is bit 0 of a bitmask for exactly that reason, and
  nothing here makes it harder.

## 10. Execution plan (third version)

Sections 1-9 are the design and stay the spec. This section is what building
it looks like against master at 403ecbbb5cf, where VARKA-38's column offsets
(PR #62) and the regeneration tooling (`dev/varka_bench_regen.sh`, PR #108) have
both landed, so the two halves ship in one PR as two commits. Where a finding
below differs from sections 3-5, this section wins; each difference says why.

### 10.1 What a user observes

Nothing changes in SQL semantics: no null, no exception, no new error.
`date_add`/`date_sub` keep returning what 32-bit addition gives (Spark's
`DateAdd` checks nothing either), and a day past the range that is only
projected, compared or diffed is never guarded. When a calendar function
consumes such a day, the *route* changes: the kernel reports the batch
(`STATUS_CHRONO_RANGE`), the evaluator discards the kernel's outputs and
recomputes that whole batch on the row engine, whose `LocalDate`-based
`getYear` handles any int day, so `year(date_add(DATE'2000-01-01', 20000000))`
returns the true year, counted in `numFallbackBatchesDeclined` and logged at
debug. The compile-time decline is the same outcome one step earlier: the
entry is residual and the row engine computes it for every row. The only
behavioural difference from today's master is that today the kernel answers
wrongly.

### 10.2 Findings that adjust sections 3-5

* **Paths.** The compiler and its suite live in package
  `...expressions.codegen`, not `codegen/varka`:
  `VarkaExpressionCompiler.scala` and `VarkaExpressionCompilerSuite.scala`
  sit directly under `.../sql/catalyst/expressions/codegen/` in the main and
  test trees. The parity benchmark is `VarkaEmitterParityBenchmark.scala` in
  `sql/catalyst/src/test/scala/org/apache/spark/sql/`.
* **Absolute intervals instead of shifts.** Section 3.1 phrases the analysis
  as a shift relative to the column contract plus two slack constants. An
  interval of possible epoch days is equivalent and simpler: a column is
  `[CONTRACT_MIN_DAYS, CONTRACT_MAX_DAYS]`, a date literal is `[v, v]`, and
  the check is directly against `NARROW_MIN_DAYS`/`NARROW_MAX_DAYS`. No slack
  constants; the two contract constants are new in `VarkaChrono` (main source
  has none today; `-719162`/`2932896` appear only in tests), derived in source
  from `LocalDate.of(1, 1, 1)` and `LocalDate.of(9999, 12, 31)`.
* **No free pass for date-typed calendar outputs.** Section 3.1's "any
  calendar node -> (0, 0)" is too loose for `LastDay`: its input passed the
  check at the `LastDay` arm, but the output is up to 30 days later and can
  cross `NARROW_MAX_DAYS`. `LastDay` propagates the child's interval plus
  `[0, 30]`; `AddMonths` plus `[min(28m, 31m), max(28m, 31m)]`, as 3.1's own
  explicit rule already says. Field-typed outputs (`Year` .. `DayOfYear`,
  `DayOfWeek`, `WeekDay`, `DateDiff`) cannot be a calendar node's child
  (Spark's type gate) and are outside the analysis.
* **Three outcomes, not two.** Risk 3 requires telling "unbounded because of
  a column offset" (build; the emitter guards) from "unknown node" (decline).
  A small sealed result type carries that.
* **The `AddMonths` arms need reshaping.** They are `for`/`yield`s that build
  the `LiteralSlot` inside the `yield`; the check needs the compiled child, so
  they become `flatMap`s.
* **Reading a literal's value back.** `literals: LinkedHashMap[Int, Int]`
  maps value to slot index in insertion order, so
  `literals.keys.toIndexedSeq(slot.index)` is the value. The table is untyped
  (day offsets, month counts and dates share it), so the analysis reads a
  slot's value by its IR position only.
* **Once-per-lane-group emission holds only under CSE.** `computed` and
  `sharedSlot` exist for `cse && useCount > 1`; with `cse=false` a
  multiply-used producer re-emits, guard included. Correct, redundant, said in
  the javadoc; no code for it.
* **Slot numbers move if `guardAcc` is allocated.** Its old place in
  `planSlots` precedes `epilogueMask` and the per-node pass, so allocating it
  shifts every later local. It is allocated only when the flag is on and this
  body's outputs reach a guarded producer, which keeps every unguarded shape
  byte-identical (asserted).
* **Production emits with `VarkaEmitOptions.DEFAULTS`** through the shape
  cache (the three-argument `VarkaShapeKey` in `VarkaKernelEvaluator`); there
  is no config surface for options, by design. The flag-off differential
  therefore needs a test hook beside the three `VarkaColumnarToRowExec`
  already owns.
* **Docs are stale from VARKA-51, not just incomplete.** `docs/sql-varka.md`'s
  calendar bullet and its fallback-cause bullet still describe the removed
  runtime decline as live; `SKILLS.md` still cites "VARKA-26's guard" in the
  fragment-key lesson and the weekday-reciprocal lesson. `VARKA-51.md` 4
  was already corrected by PR #76 (it names the literal case), so section 4's
  bullet about it is done; what remains is closing the window in the past
  tense. `m4/PLAN.md` row 38 lacks its DONE marker although PR #62
  landed; fixed in passing.

### 10.3 Commit 1: the compile-time half

**`VarkaChrono.java`.** Beside `NARROW_MIN_DAYS`/`NARROW_MAX_DAYS`, in the same
javadoc style: `CONTRACT_MIN_DAYS = (int) LocalDate.of(1, 1, 1).toEpochDay()`
and `CONTRACT_MAX_DAYS = (int) LocalDate.of(9999, 12, 31).toEpochDay()`. A
`VarkaChronoSuite` assertion pins them to -719162 and 2932896 and asserts both
lie strictly inside the narrowed range, the fact the whole analysis rests on.

**`VarkaExpressionCompiler.scala`.** A value helper in the block with
`foldMonths` and `foldWeekday`:

    private sealed trait DayRange
    private case class Bounded(lo: Long, hi: Long) extends DayRange
    private case object ColumnShifted extends DayRange  // AddDays/SubDays, column offset
    private case object Unknown extends DayRange        // a node this analysis does not know

    private def dayRange(node: VarkaVectorIR, literals: LinkedHashMap[Int, Int]): DayRange

| node | range |
|---|---|
| `ColumnRef` | `[CONTRACT_MIN_DAYS, CONTRACT_MAX_DAYS]` |
| `LiteralSlot` as a date operand | `[v, v]`, `v` read back from `literals` |
| `AddDays(x, LiteralSlot k)` / `SubDays` | child `+k` / `-k` |
| `AddDays`/`SubDays(x, ColumnRef)` | `ColumnShifted` |
| `NextDay(x, _)` | child `+[1, 7]` |
| `AddMonths(x, LiteralSlot m)` | child `+[min(28m, 31m), max(28m, 31m)]` |
| `LastDay(x)` | child `+[0, 30]` |
| `Greatest`/`Least`/`IfElse` | hull of the date operands (`IfElse`: both branches; `Coalesce` compiles to `IfElse`, so it is covered) |
| anything else | `Unknown` |

Combination: any operand `Unknown` -> `Unknown`; else any `ColumnShifted` ->
`ColumnShifted`; else the hull. Longs, so two literals of two billion cannot
wrap. A separate pass over the IR the arm already built, not folded into
compilation.

In each of the seven calendar arms (`Year`, `Month`, `DayOfMonth`, `Quarter`,
`DayOfYear`, `LastDay`, `AddMonths`, plus `DateAddYMInterval`), after the child
compiles and before the node is built:

    dayRange(node, literals) match {
      case Bounded(lo, hi) if lo >= NARROW_MIN_DAYS && hi <= NARROW_MAX_DAYS => Some(node)
      case Bounded(lo, hi) =>
        sink.note(s"day range [$lo, $hi] leaves the calendar lowering's range", expr); None
      case ColumnShifted => Some(node)   // the emitter guards the producer (commit 2)
      case Unknown =>
        sink.note("day producer the calendar range analysis does not bound", expr); None
    }

Reason strings follow the house style (lower case, noun phrase, no period);
`compilePartial`'s invariant that every `None` notes a reason holds.

**Tests.** `VarkaExpressionCompilerSuite`, constants read from `VarkaChrono`,
never retyped; `HI = NARROW_MAX_DAYS - CONTRACT_MAX_DAYS` (8449747) and
`LO = NARROW_MIN_DAYS - CONTRACT_MIN_DAYS` (-4675410) computed in the test:

* `year(date_add(d, HI))` fuses, `year(date_add(d, HI + 1))` declines with the
  exact reason; same at `LO`/`LO - 1` via `date_sub`.
* `date_add(d, HI + 1)` alone fuses: no calendar consumer.
* `year(date_add(date_add(d, 5000000), 5000000))` declines.
* Hull: `year(greatest(date_add(d, 5000000), d))` fuses;
  `year(if(d > d2, date_add(d, HI + 1), d))` and
  `year(coalesce(d, date_add(d2, HI + 1)))` decline.
* `year(add_months(d, MONTH_ARITH_MAX_MONTHS))` fuses;
  `year(add_months(date_add(d, HI - 100), 12))` and
  `year(last_day(date_add(d, HI - 10)))` decline.
* `year(next_day(date_add(d, HI - 7), 'MON'))` fuses; at `HI - 6` it declines.
* `year(date_add(d, off))` with `off` an int column fuses (`ColumnShifted`).
* `VarkaKernelEvaluatorSuite`'s task-16 report test shows the new reason.
* `VarkaDifferentialSuite`: `SELECT year(date_add(d, 20000000)) FROM varka_dates`
  with `expectFused = false`, `checkAnswer` against the row engine, and
  `verboseStringWithOperatorId()` containing the residual reason. In-range
  literal shifts on the same table still fuse.

### 10.4 Commit 2: the runtime half

**`VarkaEmitOptions.java`.** Eighth component `boolean guardDayProducers`,
`DEFAULTS` value true provisionally (10.5 decides; the owner picks from the
numbers), `withGuardDayProducers`, every `with*` arg list extended,
`canonical()` appends it in declaration order. `DEFAULTS` still renders empty,
so production shape hashes do not move whichever default wins.

**`VarkaLoopEmitter.java`.**

* `Analysis.guardedProducers`, computed in `emit` after the `analyzeRoot`
  loop: for every `isChrono` node in `topoOrder`, walk `chronoChild`'s subtree
  via `childrenOf` and collect `AddDays`/`SubDays` whose `offset()` is not a
  `LiteralSlot` (`requireOffsetShape` guarantees it is then a `ColumnRef`).
  The javadoc says the pair is hand-picked and why `dayRange`'s `Unknown`
  makes a missed producer a decline rather than a wrong answer.
* `Slots.guardTmp`, one Object slot per guarded producer, the `dowTmp` shape.
* `planSlots`: `guardAcc` allocated when the flag is on, the body is not the
  driver, and one of its outputs reaches a guarded producer (a small
  `subtreeContainsAny` walker over `childrenOf`); `guardTmp` allocated in the
  topo pass for members of `guardedProducers` under the same flag.
* `emitAndValidatedOp`: after `invokevirtual add|sub` and after the
  `emitAndWord` call (the node's own word must be stored before the guard
  reads it), when `s.guardTmp` has the node:

      dup; astore guardTmp
      aload guardTmp; getstatic LT; ldc NARROW_MIN_DAYS; invokevirtual compare
      aload guardTmp; getstatic GT; ldc NARROW_MAX_DAYS; invokevirtual compare
      invokevirtual VectorMask.or
      [masked body]  aload species; loadWord(wordRef(node)); fromLong; and
      [epilogue]     aload epilogueMask; and
      aload guardAcc; or; astore guardAcc

  This is VARKA-26's block (`git show 35f4000c407:.../VarkaLoopEmitter.java`,
  lines 2593-2619) retargeted at the producer's result; the only new lines are
  `dup; astore`, because the old block read a local. The word AND covers a
  null offset (under #62 the node's word is date AND offset). The dense body
  skips the word AND. `emitStatusReturn` and the driver's OR are untouched.
* `emitEra`'s task-51 javadoc rewritten to describe the two halves as built.

**Evaluator.** A fourth hook in `VarkaColumnarToRowExec` beside
`setDeclineKernelForTesting`: `setEmitOptionsForTesting`, read where
`VarkaKernelEvaluator` builds the `VarkaShapeKey`, so the key carries the
options. Same discipline: static, reset in a finally block, a comment saying
why it is not a SQLConf. No production change to the fallback route:
`serveBatch`/`recordDeclinedBatch` and `VarkaFilterExec.filterBatch` already
act on a non-zero status.

**Tests, `VarkaLoopEmitterSuite`.**

* `Year(AddDays(col0, col1))`: one lane with `d + off = NARROW_MAX_DAYS + 1`
  in a loop lane returns `STATUS_CHRONO_RANGE`; in an epilogue-only lane
  likewise; the same lane under a null `off` row returns 0; a null `d` row
  returns 0; an in-range batch returns 0. Under `withGuardDayProducers(false)`
  every case returns 0. `SubDays` mirrored at `NARROW_MIN_DAYS - 1`.
* `AddDays(col0, col1)` alone on the same data: 0 under both flag values and
  `codeSize(loopMasked0)` identical under both.
* `codeSize` of `Year(AddDays(col0, col1))`'s `loopMasked0` differs between
  flag values; `Year(col0)`'s does not.
* `checkMatrix` over `Year(AddDays(col0, col1))` and `Year(SubDays(...))`,
  in-range offsets, both flag values: identical results, status 0.
* `cse=false` with the producer used twice (`Year(p)`, `Month(p)`): still
  declines at the same lane; documents the redundant re-emission.
* The two task-51 tests stay: they feed out-of-range days through a bare
  `ColumnRef`, which is the contract's responsibility, not a guard's. Their
  comments gain a sentence saying so.
* Pinned oracles (`pinnedLineMap`, `everyNode`, the shape-cache hash) do not
  move: no node type added, `everyNode`'s `AddDays` has a literal offset,
  `DEFAULTS` canonical stays empty.

**Tests, `VarkaDifferentialSuite` and `VarkaSharedSessions`.**

* New fixture `cacheDatesFarOffset`: `d` dates (one null), `off` int with
  20000000, -20000000, 3, null, 100 (the `cacheDatesNullableOffset` shape).
* Flag on: `SELECT year(date_add(d, off)) FROM ...` matches the row engine,
  `numFallbackBatchesDeclined > 0`, `numFallbackBatchesKernel == 0`, reached
  without the decline hook (that test's comment claiming sibling coverage is
  corrected, and section 3's tombstone comment is replaced by the restored
  tests).
* Flag off (hook): the same query, `numFallbackBatchesDeclined == 0`, asserted
  on the metric only, never on the value (`VARKA-51.md` 3's reason).
* Filter path: `WHERE year(date_add(d, off)) = year(d2)` on a paired fixture,
  flag on, through `VarkaFilterExec`: declined count above zero, answers match.
* In-range `off` on the same fixture still fuses with a zero declined count.

### 10.5 The measurement

`VarkaEmitterParityBenchmark`, "year" section, adjacent cases like the VARKA-48
and VARKA-53 A/Bs, using `nf2Data` as the offset column (its epoch-day values keep
`d + off` inside the range) and the two-input run shape the `twoDates` case
already uses:

| case | class ids |
|---|---|
| `year(date_add(d, off))`, guard on / off, null-free and mixed nulls | 830, 831 |
| `date_add(d, off)` alone, guard on / off, null-free (the control: byte-identical by the suite's assertion) | 832, 833 |

Regenerated with `dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark`
on an idle machine, both widths, after #107 has merged (it regenerates the same
three files, and results files are never merged textually). Ratios under 1.3x
compared by minimums across a second run. Section 6's predictions stand; if the
guard costs more than 10% on the first row, that is a finding to explain before
the default flips on. The owner picks the default from the committed numbers;
the choice and the scored predictions go in a section 11.

### 10.6 Docs

* `docs/sql-varka.md`: the calendar bullet rewritten (compile-time decline for
  a bounded out-of-range shift, its reason string added to the EXPLAIN list;
  runtime decline only for a column-offset producer under a calendar function;
  the stored-column sentence replaced by the contract statement, unchecked at
  ingestion per section 9); the "until VARKA-38 lands" clause dropped.
* `SKILLS.md`: the fragment-key and weekday-reciprocal lessons reworded away
  from "VARKA-26's guard"; a new lesson: the guard moved from six consumers to
  one producer, the interval analysis is what made that safe, and a `LastDay`
  output can leave the range its input was checked in.
* This file: section 11 with the numbers and the default; section 4's stale
  bullet about `VARKA-51.md` removed. `VARKA-51.md` 4: the window
  closed, past tense. `m4/PLAN.md`: row 52 DONE, 2.22 update note,
  row 38 marked DONE (PR #62).

### 10.7 Verification

The standing gate (`dev/varka_gate.sh`), which runs both suites at both vector
widths, the catalyst doc build, the engine module and the linters, plus
`dev/varka_precommit.sh --working-tree`. Specific to this task:

* Commit 1 alone is green with no emitter change, no pinned value moving and
  no committed number moving.
* Commit 2: every unguarded shape byte-identical under both flag values, the
  pinned oracles unchanged, the differential's declined metric firing without
  the test hook, the parity file regenerated in one run with provenance.
* `dev/varka_emit.sh --table` over `year(date_add(d, off))` with
  `--variant guardDayProducers=false` as the reviewer's check that the guard
  is the only difference.

### 10.8 Sequencing

1. Worktree off `origin/master`; commit 1.
2. Commit 2, flag default provisionally on.
3. Once #107 has merged: merge master, regenerate, record the numbers, the
   owner picks the default, docs and plan files requoted from that run.
4. Push and open the PR.

## 11. The measurement, and the default

`VarkaEmitterParityBenchmark`, "year" section, regenerated at both widths by
`dev/varka_bench_regen.sh` on an idle machine after the merge with #116 (load
0.83 at start, canary compute +0.0%, cache +3.1%, memory -0.7%, governor
`powersave` with the balanced profile, as this machine's committed runs are),
then run a second time at both widths to scratch logs and compared by
minimums, since every ratio here is under 1.3x. Rates in M rows/s, from the
committed file; where the second run disagreed, the text says by how much.
An earlier regeneration, before the merge, is in this file's history and gave
the same picture with the null-free 256-bit pair 9% apart.

| case | 256-bit, guard on / off | 128-bit, guard on / off |
|---|---|---|
| `year(date_add(d, off))`, null-free | 2696.7 / 3154.3, **-14.5%** | 1193.3 / 1224.3, **-2.5%** |
| `year(date_add(d, off))`, mixed nulls | 1908.5 / 2183.7, **-12.6%** | 648.6 / 755.0, **-14.1%** |
| `date_add(d, off)` alone (control) | 9876.1 / 9888.1, -0.1% | 10447.2 / 10603.1, -1.5% |

The control row is the noise floor: those two kernels are byte-identical (the
emitter suite asserts it), so its 0.1% at 256 bits and 1.5% at 128 bits is
what run-to-run variance looks like on a 2 ms row in this run - and the second
run put the same control pair 4% apart at 256 bits and 5% at 128, which is
the floor to read the null-free rows against. The second run's pairs: 5.1%
null-free and 13.5% mixed at 256 bits, 2.9% and 13.6% at 128 bits. So the
mixed-null cost is 13-14% at both widths in every run, and the null-free cost
is 3-6% at 128 bits and somewhere between 5% and 15% at 256 bits, which a 7 ms
row measured to whole milliseconds cannot resolve finer. The A/B pairs are
adjacent cases in one run, so they share JIT and thermal state.

**Predictions scored.**

1. *3-8% at AVX-512, under 10% at 128-bit.* Null-free: 5-15% at 256 bits
   across the runs and 2.5-3% at 128 bits - inside the range at 128, at or
   over its edge at 256. Mixed nulls: 12.6% and 14.1%, **over the 10% line at
   both widths**, in every run. Per row, from the committed file at 256 bits,
   the guard costs 0.05 ns null-free and 0.07 ns with mixed nulls; the second
   run's null-free pair puts it at 0.02 ns, so the masked body pays between
   one and a half and three times the dense body's price for the same two
   compares, not merely a larger fraction of a slower body. The masked
   body's guard is the dense body's plus the validity AND: `aload species`,
   `lload word`, `VectorMask.fromLong`, `and` - and `fromLong` is a mask
   materialization from a scalar, not a lane op, which is the part the
   prediction priced as one op and is not. The dense body pays only the two
   compares, the `or` and the accumulator `or`, and lands where VARKA-26's
   guard did.
2. *`date_add(d, off)` alone is byte-identical under both flag values.*
   Asserted in `VarkaLoopEmitterSuite`; the control row measures it as noise.
3. *No committed number for an existing case moves.* The null-free calendar
   rows did not (`year` null-free 3455.8 against 3452.2 before this task); the
   mixed-null calendar rows moved 6-7% up and the shared four-field rows 3-5%
   either way against the file this branch started from, with the four scalar
   controls within 0.8%, which is the machine-day variance `VARKA-54.md`
   9.2 and `SKILLS.md` already record, not this task: the guard is emitted for
   no shape but its own.
4. *No pinned oracle moves.* None did.
5. *The compile-time half declines nothing in the corpus.* Nothing in TPC-H or
   TPC-DS shifts a date under a calendar function by more than a few days.

**The default: on.** The owner's call, made on these numbers. What decides
it: the cost is paid only by a calendar function over a column-offset
`date_add`/`date_sub`, a shape the corpus does not contain and that no other
kernel's bytes change for; the shape that pays it is the one that returns a
wrong year without it; and the mixed-null cost, though over the prediction,
is a bounded price on a correct answer against an unbounded one on a wrong
one. Off stays a reference variant for the A/B, on `FloorMod7`'s precedent.
The masked-body `fromLong` is the thing to attack if this cost ever matters:
the validity word is already in a local, and a guard that ANDs the compare
masks with a mask the body has already materialized for the store would save
the conversion.

**Merged after #116 (VARKA-35).** `trunc(date, ...)` landed while this PR was
open, as a calendar node with a date-typed output. Its arm in `compileNode`
now takes its child through `calendarInput`, like the seven this task
covered, and `dayRange` bounds its output at its input shifted by
`[-365, 0]` - a truncated date is its input or an earlier day of the same
period, at most the 31st of December of a leap year back to the 1st of
January. The compiler suite holds `year(trunc(date_sub(d, k), 'YEAR'))` at
365 short of `date_sub`'s own last fusing shift, and one past it. The parity
file was regenerated once more after the merge, since the VARKA-35 pair and
this task's pair now share it.
