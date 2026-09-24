# The emitter and the IR

Lessons from the bytecode emitter and the IR it walks - budgets, sharing, guards and refusals.

One of Varka's lesson files; the index over all of them is
[`SKILLS.md`](../../../SKILLS.md) at the repository root, which is generated from
these files by `dev/varka_toc.py`.

## Sharing below the node level in an emitter

- **The values worth sharing are not always nodes.** Varka's DAG-CSE memoizes on
  structural equality between IR nodes, which cannot help when the redundancy is
  *inside* one node's emitted run: `year(d)` and `month(d)` are two nodes that each
  emit the same forty-op civil-from-days decomposition, and era, century and the rest
  are locals, invisible to any walk over nodes. The fix does not need an IR change -
  a *fragment*, keyed on (kind, child) and tracked in a per-lane-group set beside the
  node-level `computed` set, shares the locals directly. A multi-value IR node emits
  identical bytes, so choosing between them is engineering cost, not throughput; the
  fragment wins because it generalizes to every node built on the same prefix without
  the IR naming any of them.
- **A shared run must be keyed on everything it reads, not just on its input.** The
  prefix at the time also emitted a range guard (task 26's, since moved by tasks 51
  and 52), ANDed with the node's validity word. Every plain extraction aliases its
  word to its child's, so those shared safely - but `add_months(d, n)` ANDs the date's
  word with the month count's, so keying on the child alone would have given it a
  guard computed under a different mask. Put the extra input in the key and the
  mistake becomes unrepresentable rather than a rule in a comment; the key kept the
  extra input after the guard left, since the lesson is about what a run reads.
- **A generated class's raw bytes never compare equal across two emissions**, because
  the harness names each class afresh and the name is in the constant pool. An
  assertion of the form "this option changed / did not change the bytecode" has to
  compare a *method's* code size (or its instructions), not `Arrays.equals` on the
  class. The false direction is the dangerous one: `!Arrays.equals` passes for any two
  emissions whatsoever, so a test written that way proves nothing at all.
- **Widen a method for what is strictly less work, never for what is merely shared.**
  Task 17 measured that merging two outputs over a shared eight-op chain into one
  method *loses* 1.4x, and that finding is why the calendar fields sat in separate
  methods for eleven tasks after the fragment could have shared them. The grouping
  clause that finally admitted them (task 32 B2) is not "the marginal cost fits", which
  would have re-merged task 17's pair; it is "joining skips a prefix the method already
  computes" (`saved > 0`), bounded by its own `FUSED_CEILING`. The one situation where a
  wider method is less work rather than a trade is the only one that opens the bound,
  and a byte-identity test over non-calendar shapes pins that it opens nothing else.
  The reason to tie the rule to a shape property rather than to a measurement showed up
  while B2 was built: task 17's own two rows reversed in the regeneration task 46
  committed (budget 24 at 5492.1 against budget 16 at 4237.4, where twelve earlier
  regenerations had 16 ahead by ~1.4x), because moving the validity OR ahead of the
  vector work let it inline in the wider method - the loss was a refused call, not
  register pressure. A rule keyed on "what task 17 measured" would have been wrong
  either before or after that commit; "skips work the method already did" is right in
  both states, and the question task 17 asked is back on task 43's desk.
  Measured, the same four fields that lost as four merged chains win 2.15x as one
  method with one prefix. Two consequences worth carrying: weights that only had to
  "exceed the budget" become wrong the day they bound a method, so recount them from
  emitted instructions (add_months' tail turned out to be 81 ops, not the ~6 the plan
  assumed, and it - not the fields - decides how many outputs a ceiling admits); and
  greedy grouping in output order leaves `year(d), year(d2), month(d)` with a prefix it
  need not pay, a limitation to pin rather than a bug to fix in the same change.

## Generated Code Can Carry Its Own Debug Info (Class-File API)

- `CodeBuilder.lineNumber(n)` needs no options or flags: a `LineNumberTable` lands in
  the emitted method, and with a `SourceFile` attribute the JVM fills in file and line
  on every stack frame through the generated code - so a generated loop can name the
  *IR node* that threw, not just the method. Place the marker immediately before the
  node's own defining instruction, after its children are emitted: a marker at the
  start of a post-order case attributes the parent's op to whichever child was emitted
  last.
- Pick line numbers from a property of the IR (task 16 uses the children-before-parents
  topological index), not of the emission order, and record the decoding key inside the
  class - a custom attribute is the natural place, since it travels with the bytes into
  a heap dump or a `javap` capture.
- A custom attribute's payload is fixed-width: adding a field means updating the
  `attribute_length` the writer emits (4 -> 6 for two -> three constant-pool refs) *and*
  the reader's offsets. They are two sides of one format and belong in one commit.

## The Class-File API's Stack-Map Generator Is a Free Verifier

- `ClassFile.of().build(...)` computes stack map frames and rejects inconsistent
  operand stacks at *emit* time (`IllegalArgumentException` naming the bytecode
  offset, with a full instruction dump). A double-store bug in task 11 never reached
  the JVM - one layer earlier than the `ClassFile.verify`-before-load discipline,
  and two earlier than a runtime `VerifyError`.
- Member-resolution mistakes (wrong erased descriptor) still pass both build and
  verify and surface at first execution as `NoSuchMethodError`; keep the
  wrong-descriptor negative-control test so that failure mode stays diagnosable.

## What "the masked method is the dense method's bytes" actually took

- Task 70's one-body result - a masked loop or epilogue method whose every validity word is
  dead comes out byte-identical to its dense twin - held on the first run for `year(d)`, the
  four shared fields and `next_day(d, k)`, on the loop and the epilogue both. It needed three
  things to be true at once, and two of them are about slots rather than instructions. The
  per-group read and write and the null-state prologue have to go, which is the visible
  part. A dead *own* word must not get a slot, because the dense body allocates none and
  every later local would shift by two. And a dead *input* word must keep its slot, because
  the dense body allocates that one too - the prologue's `srcValSeg`/`dead`/`hasNulls`/`word`
  quartet is planned per referenced input in every body, dense included, and has been since
  task 24. So liveness drives what is emitted for an input word and what is allocated for an
  own word, and they are deliberately not the same rule. Byte identity is a layout property
  as much as a code property; the emitter suite asserts it on size because the two methods
  differ in name in the constant pool and nowhere else.

## A store the loop repeats per group with a constant operand is a fill the driver should do once

Task 45. The emitted dense loop ended every value output with
`orValidityBitsAt(seg, i, -1L, lanes)` - a 212-byte helper that does not inline in a wide loop -
once per lane group per output, ORing a word of all ones into a bitmap the driver had zeroed a
moment earlier. On a dense batch the dispatcher has already proven every input null-free and
task 11's invariant makes every value output valid on every row, so those bits were known before
the loop started. Setting them once in the driver, and not emitting the tail, is worth:

| shape | AVX-512 | 128-bit |
|---|---|---|
| shared four-field calendar | +86% | +149% |
| `year` | +26% | +41% |
| `dayofweek` | +12% | +46% |

(AMD Ryzen AI 9 HX PRO 370, OpenJDK 25.0.4, one regeneration on the tree merged with task 53.) The
four-field shape then beats `ChronoVectorOps.vectorFourFields`, the hand-written ceiling task 32
spent its time chasing, by 2.3x.

Three things generalise beyond this one store.

* **The constant operand is the tell.** A per-iteration call whose data argument is a compile-time
  constant is doing work whose answer the emitter already knows. Look for the loop-invariant
  operand rather than for the expensive-looking call.
* **The win is larger at narrow widths, and that is arithmetic rather than luck.** A four-lane
  group makes four times as many calls per row as a sixteen-lane one and the call's cost is per
  call, not per lane. Any per-group fixed cost is worth four times as much to remove at 128-bit.
  This was registered as a prediction and held for all three shapes.
* **The ratio can invert between widths.** `dayofweek` gains *less* than `year` at AVX-512 (+12%
  against +26%) and *more* than it at 128-bit (+46% against +41%), because a ~14-op body at four
  lanes is dominated by per-group overhead while the same body at sixteen lanes is dominated by
  its stores. A ranking measured at one width is not a ranking.

**And bit-exactness is the contract, not an implementation detail.** The old path zeroed
`(rows + 7) / 8` bytes and OR-ed lane-masked words, leaving the bits past `rows` in the final
byte at zero. The replacement has to set exactly `rows` bits and not fill that last byte, because
the differential compares dense against masked validity byte for byte - and because nothing
promises every Arrow reader stops at `valueCount`. Producing identical bits is what lets the
existing differential be the change's oracle rather than something to rewrite.

## A refused call is refused by the caller's budget, and the caller's budget is spent in program order

Task 46, which set out to make the per-lane-group validity write inline by shrinking the callee,
measured a win, and then found from the compiled code that the win was somewhere else and the
write was still a call. Everything below was read off the JVM's own output; none of it was
inferred from a timing.

**Price a per-group call from the committed A/B before designing around it.** The
`denseValidityOnce=false` variant is an exact A/B for one `orValidityBitsAt` per output per lane
group, and dividing its cost back out over the rows a group covers gives 1.95 and 1.87 ns at
AVX-512 and 128-bit for `year`, 2.35 and 2.18 for the shared four-field shape. The cost is per
call and does not move with the vector width - which is the arithmetic behind "a per-group cost
is worth four times as much to remove at 128-bit". This part of the analysis was right.

**Read the inlining log against the source, or you will read C1 as C2.** `PrintInlining` prints
both compilers' decisions in one tree. `callee is too large` and `callee uses too much stack`
are C1's (`c1_GraphBuilder.cpp`: `C1MaxInlineSize` 35, `C1InlineStackLimit` 10, the latter on
`max_stack + max_locals - parameter slots`); `inline (hot)` is set only in C2's
`bytecodeInfo.cpp`. Task 46's first reading counted C1's size refusals as evidence that the
212-byte writer was too big for C2. In C2 the general writer and the 33-byte specialised one
were refused exactly as often, and for one reason: `NodeCountInliningCutoff`.

**That reason is about the caller, and it is spent in program order.** A print at the refusal in
a fastdebug build (`src/hotspot/share/opto/bytecodeInfo.cpp`, the `over_inlining_cutoff` branch)
showed `unique()` at 18250 to 18520 nodes against the cutoff of 18000 when C2 reached the OR in
the masked `year` loop - in both arms, for the standard and the OSR compile alike, with
`incremental=0`. The `year` body's Vector API intrinsics parse to about the cutoff on their own,
so whichever call is *last in program order* is the one refused, whatever its size. Two
consequences worth carrying: `-XX:LiveNodeCountInliningCutoff` governs the incremental branch and
cannot lift this (task 32 tried 400000 and saw nothing move, for this reason); and the develop
flag it actually is cannot be set on a product JVM at all. The lever the emitter has is order.
A value root's validity OR depends on its word, not on its vector store, and the word is an
input word for every calendar extraction, so the OR now goes first (`validityOrFirst`). C2 meets
it at a few hundred nodes and inlines it, and every masked row in the parity file moved: `year`
+20% at AVX-512 and +30% at 128-bit, the 64-op shape +75%, the budget-24 shape +83% and +180%.
The 64-op figure is one half of a two-sided move and was read as a win because the other half was
not looked at: the same commit took that row from 270.4 to 8.8 M rows/s at 128-bit, where it stayed
for several regenerations (`PLAN_MILESTONE_4.md` 2.39). A number quoted at one width when the change
moved both is half a measurement.
The exact safety test is "the word is an input word or the constant", not "the root computes no
word": `Year(IfElse(...))` aliases the blend's computed slot, and reading it early is a frame with
no such local, which the verifier rejects.

**Read the loop, not the nmethod.** Whole-nmethod instruction counts for the two arms differed by
nine and looked identical; the hot loop differed by seven scalar instructions out of about
eighty, and that was the entire measured gain of the width-named helpers - the general reader's
`>>> (row % 8)`, which C2 cannot prove is zero at 16 lanes, plus the `lanes` argument. Count
inside the back-edge. `VarkaAssemblyProbe` now takes an emit variant and runs the masked body,
so two options can be compared this way in one command.

**Once the write inlines, naming the width is a per-shape trade, not a win.** With the order
fixed in both arms: `year` masked is 4% to 8% *slower* width-named (the call gone, C2 unrolled
that loop to 172 vector instructions in the body and the general arm to 64 - task 32's register
file at four lanes), the four-field shape +11% and +27%, the selection kernel +43% and +6%. The
option is on for the multi-write and selection shapes; the single-write loss is recorded.

**Instrument the JVM when the log stops explaining.** The whole chain above - which compiler,
which branch, how far over - took one twelve-line print in `bytecodeInfo.cpp` and an incremental
`make jdk` of the fastdebug tree. Timings from that build mean nothing; its counts settled in a
minute what three benchmark regenerations could not.

**A defect the same work surfaced:** `VarkaEmitOptions.canonical()` had omitted `truncDate` since
task 35, so two option values differing only there rendered the same string and shared one
execution identity in the shape cache's side table. A test that walks the record's components
and requires each to change the rendering is worth more than the fix.

## A budget that bounds the method is not a budget that bounds the work

`GROUP_BUDGET` had rested since task 11 on one measurement: two outputs over a
shared depth-8 chain ran about 1.4x faster as two loop methods than as one, read
as register pressure. Task 71 reopened it because the rows had reversed, expecting
to pick a bigger number. The number did not move. What was wrong was where the
bound was applied.

The grouping condition compares `group.ops + marginal` against the budget, and
`marginal` already excludes nodes the group holds. So task 17's pair is 14 nodes
plus 6 against a budget of 16 - and the two methods it is split into cost 28
nodes of work, where the one method it is refused costs 20. **The condition
rejects the cheaper arrangement because it bounds the method, not the work.**

Task 32 step B2 had already found this and fixed the calendar half of it: an
output reusing a civil-from-days prefix may join past the budget, "because
skipping the prefix makes the method less work rather than more". That argument
is not about prefixes. Any node the group already holds is work the joining
output skips. Generalising the exception - measured as what an output would cost
alone, less what it actually adds - merges exactly the shapes a wider budget
would merge and nothing else, at the shipped budget.

And the budget was the wrong lever for a second reason. It is what keeps compile
time in hand: past about 1900 bytes C1 refuses a loop method and it runs
interpreted until C2 lands, a third of a second once per shape per JVM. Raising
it to buy one shape's CSE pays for that everywhere. The rule buys it only where
an output has reuse to act on.

Two habits, and the second is the one that keeps being learned here:

- **When a threshold produces a result that looks wrong, check what it is
  measured against before changing its value.** A bound compared against the
  wrong quantity gives wrong answers at every value, and retuning it hides that
  under a number that happens to work on the shape you tested.
- **Survey statically before benchmarking a ladder.** Emitting the corpus at
  every rung and counting methods and ops took one command and answered the whole
  question: of nine shapes, three regroup across 16 to 64, and only one above 24 -
  for a saving of one lane op out of 38. A five-rung throughput ladder would have
  spent hours and then had to beat the file's noise to say the same thing, on
  rows the band shows cannot rank small effects anyway.

The postscript is about predictions. This task registered four and three were
wrong - the win was not the inlining it named, the flattening came a rung earlier
than predicted, and a default change re-pinned one assertion rather than the ten
predicted. Each was reasoned from the shape of the code (how tests address
methods, what a javadoc says a call costs) instead of from measuring it, in a
plan written to correct exactly that failure elsewhere. Registering a prediction
is not the same as having evidence for it, and the value of writing it down is
precisely that it can be scored against something.

## A range guard belongs where the value is made, not where it is read

Task 52. Task 26 checked the narrowed civil-from-days range at every calendar extraction, per
lane, per batch; task 51 removed that on the argument that the range is decidable once; task 52
is the decision. Three things came out of building it.

- **Most of the guard is a compile-time interval.** A date column is the contract range
  0001..9999 (`VarkaChrono.CONTRACT_MIN/MAX_DAYS`, derived from `LocalDate` in source), a literal
  day offset shifts it by exactly its value, `next_day` by 1..7, `add_months(n)` by 28n..31n,
  `last_day` by 0..30, and `greatest`/`least`/`if`/`coalesce` take the hull. The check is one
  compare of that interval against `NARROW_MIN_DAYS`/`NARROW_DECOMPOSE_MAX_DAYS` in the
  compiler's calendar arms (`dayRange`/`calendarInput` in `VarkaExpressionCompiler`), and it
  costs nothing at run time. The slack is large - 11833917 days forward and 4675410 back from
  the contract - so the corpus never trips it, and a query that does is computed by the row
  engine with the interval named in `EXPLAIN`. (Task 52 wrote `NARROW_MAX_DAYS` on both sides
  and 8449747 forward; task 69 gave the upward side its own constant, which is the bullet on
  asymmetric admission checks above.)
- **A date-typed calendar output is not "back in range".** The tempting rule "a calendar node's
  output re-enters the contract" is false for `last_day` and `add_months`: their input passed
  the check at their own arm, but the output is up to 30 days (or 31 per month) later, so a
  second calendar node over it needs the child's interval plus that bound. The analysis
  propagates the interval through them for exactly this reason; the +-1 tests at the bound
  are what keep the rule honest.
- **The runtime half is one producer, guarded once, behind an option.** The only shift the
  compiler cannot see is a column offset (task 38), so `AddDays`/`SubDays` with a `ColumnRef`
  offset under a calendar node re-emit task 26's guard block on their own result
  (`emitRangeGuard`), ANDed with the node's validity word (a null offset must not condemn a
  batch) and the epilogue mask, ORed into the per-body accumulator task 51 left in place. The
  accumulator is allocated only when the body reaches such a producer and
  `VarkaEmitOptions.guardDayProducers` is on, so every other shape is byte-identical under
  both settings - the suite asserts it on method sizes. The analysis returns two answers:
  bounded and unknown (decline), so a producer nobody has taught to the analysis fails as a
  residual entry, never as a wrong year. It returned a third for a while - "column-shifted:
  admit, the emitter guards it" - and the entry below is what that cost.
- **A mask guard costs its `fromLong`, not its compares.** Measured on `year(date_add(d, off))`
  (`VarkaEmitterParityBenchmark`, two regenerations and a second run each): the guard costs
  13-14% with mixed nulls at both widths in every run, against 5-15% null-free at 256 bits
  and 2.5-4% at 128 - 0.05 to 0.07 ns per row in the masked body against 0.02 to 0.05 in the
  dense one for the same two compares. The difference is the validity AND, whose
  `VectorMask.fromLong` materializes a mask from a scalar word; the prediction counted it as
  one lane op and it is not. A guard that reuses a mask the body has already built for its
  store would not pay it.
- **The guard generalizes to a value bounded by anything other than the day range - and the
  block itself needed no change to do it.** Task 60 widened `add_months`' month count from a
  compile-time-bounded literal to a column, and reused this same block
  (renamed `emitProducerGuard` to `emitRangeGuard`, taking the two bounds as parameters) to
  guard the count against `MONTH_ARITH_MIN/MAX_MONTHS` instead of the day range. The correction
  this forced onto `PLAN_MILESTONE_4.md` 2.27: a column bounded by a runtime guard is a
  `Bounded` day range at the guard's own extremes (`shifted(days, 31 * MIN, 31 * MAX)`), not an
  unbounded shift - "unbounded" is for a shift the compiler genuinely cannot bound at all, which
  a *guarded* column is not. Getting this wrong would have re-widened every consumer's
  range to "unknowable" for no reason, the same over-approximation task 51 had just finished
  removing.
- **State a guard's guarantee as an interval, not as a verdict, or it will not compose.** Task
  60's review found the hole this makes. The analysis had a `ColumnShifted` answer meaning
  "some producer below is guarded at run time, so admit this", and `admitCalendar` admitted it
  without any range test. That is sound only while the guarded producer is the calendar node's
  direct child. Put anything above it that moves the day - `add_months` with a column count,
  worth up to 31 * `MONTH_ARITH_MAX_MONTHS` days - and the verdict still said "admit", because
  a verdict carries no arithmetic for the shift to act on. Both runtime guards passed on their
  own operands and `year(add_months(date_add(d, off), m))` answered 87585 for a true -14848.
  The fix is a one-liner and the lesson is in its shape: have the guarded producer return
  `Bounded(NARROW_MIN_DAYS, NARROW_MAX_DAYS)` - the interval its guard actually establishes -
  and every existing rule composes with it for free, because they were already written to shift
  intervals. `ColumnShifted` then has no producer and is deleted. Generally: when a runtime
  check establishes a fact the compiler wants to rely on, encode the *fact* in the same
  representation the analysis already manipulates, never as a special case meaning "trust me".
  The special case is invisible to every rule written before it.
- **A constant's name can be a claim nobody checked, and the claim can be too tight.**
  `NARROW_MAX_DAYS` is `(1 << NARROW_ERA_K) - 1 - NARROW_BIAS`, the ceiling of the *shift
  domain* of the era step, and it had been read for four tasks as the range the narrowed
  civil-from-days decomposition is exact over. It is not. Task 60's review noticed the first
  layer - what binds above is the multiply's own overflow, `w * NARROW_ERA_M < 2^31`, looser
  than the shift domain by about 5,600 years. Task 69 found a second: `eraOf` adds one era when
  the magic undershoots, and that correction keeps the split exact past the point the multiply
  wraps, ending only where the undershoot reaches *two* eras. The real limit is 9,266 years
  above the constant that had been standing in for it, and the four shapes declining against it
  were being told their day could leave a domain it could not reach. The habit worth keeping:
  when a bound is named after the mechanism that produces it rather than after the property it
  is supposed to guarantee, the two are not the same number, and which one a caller needs is a
  question to ask rather than to inherit. The way to answer it is task 69's section 2 - prove
  the identity over the extended domain and sweep it exhaustively against `java.time`, because
  "the multiply does not overflow" says nothing on its own about whether the result is still
  the era.
- **An interval-based admission check should be asymmetric when the mechanism is.** The same
  task's whole shipped change is that `admitCalendar` tests `lo` against `NARROW_MIN_DAYS` and
  `hi` against `NARROW_DECOMPOSE_MAX_DAYS`. There is no headroom below - `NARROW_MIN_DAYS` is
  exactly `w = 0`, and one day under it `w` is read as about 4.29e9 - while above, the
  correction buys nine thousand years. Writing one constant on both sides had looked like
  symmetry and was actually an accident of there being only one constant. It also left the
  shape that motivated the debt still residual: `weekofyear` shifts `+-3` through `ThursdayOf`,
  a shape declines on the union of its directions, and a bound loosened upward alone cannot
  reach it. Recovering that one needs the other lever entirely - the guard's own compare
  against a bound the compiler picks - which is milestone 5's task 91.
- **A guard the compiler relies on cannot sit behind an option the compiler cannot see.** The
  same review caught the count guard filed with the option-gated day-producer guards while
  `dayRange` returned `Bounded` for a column count unconditionally. With
  `guardDayProducers=false` the guard vanished and the compile-time bound stayed - wrong
  answers, not a slower reference variant. The criterion that sorts these is already in the
  code: `selfGuarding` (task 42's `make_date`) is "the check is the node's own correctness" and
  is never optional, `guardedProducers` is "insurance for a consumer" and may be. A count guard
  protecting its own magic multiply is the former, and moving it there made the option's name
  honest again as well.
- **Removing a dependency chain is only a win where the chain exists, and a validity group
  smaller than a byte is where it exists.** Task 76 found task 46's helper choice inverting at
  four lanes and named the mechanism: a group is `lanes` bits, so at 4 lanes it is half a byte,
  two consecutive groups read-modify-write the same byte, and they serialise on it. Task 47
  built the writer that removes the read entirely - accumulate the word in a register, store
  all eight bytes, no load - and measured it on task 76's own rungs at three widths. It wins 6
  to 9% at 4 lanes at one and two writes, and the inversion disappears with it, which is the
  mechanism confirmed. It *loses* 11 to 20% at 8 and 16 lanes, where a group owns whole bytes
  and there was never a chain: an eight-byte store plus an accumulator, a mask, a shift and a
  branch is more work than a one-byte read-modify-write whose helper already inlines. The
  general lesson is the one the numbers force rather than the one the row's title assumed -
  "one write per word" is not an improvement, it is an improvement *at sub-byte group widths* -
  and the rule that follows is keyed on the bit layout (`lanes < 8`), which is one condition
  read off the mechanism, not the two thresholds fitted to a machine that task 76 declined.
- **Before reading a ladder's numbers, check the ladder emits what it claims to.** Task 47's
  ladder has a step at three writes that neither its model nor task 76's predicts, and the
  first candidate - a rung crossing `GROUP_BUDGET` into two loop methods, which would pay every
  per-method cost twice - is checkable in one test and false: all four rungs emit one loop
  method and grow ~130 bytes per write. That turned "the numbers are strange at k=3" into "the
  JVM does something at k=3", which is a different investigation with a named suspect (task
  46's inlining cutoff on the caller). The assertion is committed, so the next reader of either
  ladder meets the fact before the number. The failed first version of it is worth recording
  too: it built the k rungs from one repeated literal slot, so the k roots were the same tree,
  CSE collapsed them, and every rung emitted one write - a ladder that measures nothing while
  looking exactly like one that does.
- **A local written only inside a branch is `top` at the merge, and the verifier says so.**
  Task 47's accumulator is cleared under `if ((i & 63) == 0)` and read straight after; the
  first `lload` failed with `VerifyError: Bad local variable type ... Type top ... is not
  assignable to long`, because one incoming edge had assigned the local and the other had not.
  Initialising it once before the loop is two bytecodes and the fix. Emitting a store inside a
  branch is fine; reading it at a point some path reaches without that store is not.
- **A word this block reads must already be stored, not merely available on the stack.** The
  guard's mask-body AND reads `Slots#wordRef` for the node under guard - a *stored local*, not
  whatever the emitter last pushed. For `AddDays`/`SubDays` that word is computed immediately
  before the guard runs (`emitAndValidatedOp`'s own call site), so this was never visible at
  task 52. `add_months` computes its own word differently: task 40's dispatcher ran
  `emitAndWord` *after* `emitAddMonths` returned, once the whole value was on the stack - fine
  for every reader that came after, but the guard needed to run *inside* `emitAddMonths`, right
  after the count loads and before the magic-multiply's bias folds it in, which is earlier than
  that word existed. The result was `VerifyError: Bad local variable type ... top ... not
  assignable to long` in the masked epilogue, the one body where the guard reads that word.
  What made it invisible was not the absence of a calendar consumer - `Year(AddMonths(col,
  col))` aliases the same word slot and would have failed the same way - but that no curated
  test ran a *live* violation through the masked epilogue at all; the shapes that did reach it
  nulled the offending lane, where the guard is silent either way. The fix moved the
  `emitAndWord` call earlier, into `emitAddMonths` itself, right after the count's
  `emitValue` - both children's words are provably ready by then, so nothing about the word
  itself changed, only when it is stored. The general lesson: before reusing a block that
  reads "the node's own word," check where that word is written relative to where the reused
  block will run, not just that it is written somewhere.

## A derived input must never raise, because the row engine's null check comes first

Task 59. A string-argument date function (`next_day(d, s)` with a weekday column) runs in the
kernel without string lanes by having the evaluator derive an int32 column per batch, before
the kernel, through the row engine's own parser (`WeekdayLeaf`); the kernel then reads a plain
int input (`CompiledVarkaProjection.derivedInputs`, a plan property like `inputBounds`, keyed
under a negative synthetic input-table key so the compiler's mark-and-truncate rollback covers
it). Two things came out of building it.

- **The obvious ANSI story was wrong.** The milestone's section 2.26 said the pre-pass could
  simply call the same function and raise the same error at the same row. But
  `NextDay.nullSafeEval` never parses the name when the date beside it is null:
  `next_day(NULL, 'xyz')` is NULL under ANSI, not an error. A pre-pass that raised on the parse
  alone would err where the row engine does not. So the leaf never throws: under ANSI an
  unrecognised name declines the batch (`STATUS_DERIVED_INPUT`) and the row engine computes it
  by its own rules - NULL beside a null date, the error beside a live one - which also keeps the
  invariant that no kernel-side code raises a user-facing exception. The general rule: a derived
  input reproduces a *function's* semantics, and the expression around it may have null
  short-circuits the function does not; the row engine is the only safe place to raise.
- **An ASCII fast path must delegate every non-ASCII row, not just reject it.**
  `"\u017Funday".toUpperCase(Locale.ROOT)` is `SUNDAY` (long s) and `"fr\u0131day"` is
  `FRIDAY` (dotless i), so a byte-level parser that rejected non-ASCII input would disagree with
  the definition on rows that are, by the definition, weekdays. The parser hands any row with a
  byte at or above 0x80 to `getDayOfWeekFromString` itself, and `WeekdayLeafSuite` holds both
  parsers to the definition over every case pattern of the 21 spellings, every one- and
  two-byte ASCII string and every printable one-byte mutation of every spelling.
- **The second leaf had no ANSI question at all, and that was a finding, not an assumption**
  (task 61, `trunc(d, fmt)` with a format column). `TruncDate` has no `failOnError`, and
  `TruncInstant.evalHelper` answers every non-date level - a null format, an unrecognised
  spelling, `'DAY'` and below - with NULL in both modes, so the kind (`TRUNC_LEVEL`) has no
  ANSI twin and `TruncLevelLeaf` never declines. The order of work that made this cheap: read
  the row engine's eval for the error path first, and derive the leaf's contract (never throw;
  null lane or decline) from what that path does. The other transferable piece: when the
  derived value selects *which computation* applies (the level) rather than feeding one (the
  weekday's `k`), the kernel computes every alternative and blends on the lane - 91 dense-loop
  ops for the four periods against 36..62 for one literal level - so the literal form remains
  the shape a query should write, and the doc says so.

## Read two fields out of one product, and put the axis where the formula wants it

Task 53. The civil-from-days prefix used to find the month with a magic multiply on the March
day-of-year and then run `emitMonthStart` *forwards* to recover the day of month, on a March = 0
axis that needed an add in front of every reported month. Neri and Schneider (2022) show that one
affine numerator does both jobs at once: with `num = 2141 * doy + 197913`, the month index is
`num >>> 16` and the day of month is `((num & 0xFFFF) * 31345 >>> 26) + 1`. The high half and the
low half of one product are two fields, and the constants only work on the March = 3 axis, which
is exactly the axis that makes the reported month `m3 < 13 ? m3 : m3 - 12` with no add. The
identities are exact over their domains (366, 65536 and 12 cases) and the exhaustive sweep runs
both axes over all 16,777,216 covered days, so the older lowering stays as the reference variant
the new one is checked against rather than dead code.

| shape | AVX-512 | 128-bit |
|---|---|---|
| `dayofmonth` (null-free / mixed nulls) | +13.5% / +11.9% | +12.6% / +12.7% |
| `month` (null-free / mixed nulls) | +4.3% / +0.7% | +5.7% / +4.2% |
| shared four-field calendar | +3.6% | +5.0% |

Three lessons that outlive the constants.

* **When a formula wants a different origin, move the origin rather than correcting for it.**
  The March = 3 axis looks like churn - every slot comment, every helper and every test moved -
  but the alternative was a permanent add on the hot path to translate between the paper's axis
  and ours. The prefix slot `t[5]` now holds the numerator, not a month, and the tails that read
  it (`tailReadsMarchMonth`) are an exhaustive switch so a new tail cannot silently assume the
  old contents.
* **A shared shape gains least from a cheaper shared step, and that is the denominator, not a
  surprise.** The four-field shape was predicted to gain most "because it pays the month block
  once and the tails three times". Fragment sharing already collapses its four prefixes into one,
  so it saves the block once spread across four outputs and a body five times the size, while
  `dayofmonth` alone saves it on every row of a small body. Prediction 4 missed for exactly this
  reason; write the denominator down before predicting a ratio.
* **A boundary measured in whole outputs does not move for a saving smaller than one output.**
  The unshared `HugeMethodLimit` crossing was predicted to move from 19/20 outputs to 21 or 22.
  It did not move on either axis: an output costs roughly 400 bytes of epilogue and the saving
  was 149. Count the units the boundary is measured in before predicting it will shift.

## The shape a test picked because nothing lowered it

Six suites and three benchmark cases in this repo used `i + 1` as their
residual entry. None of them cared about addition: they needed one entry the
compiler would refuse, so the mixed-projection machinery - fused beside
forwarded beside residual - had something to be mixed about. `i + 1` was the
shortest expression that qualified, and it stayed the shortest one for
fourteen tasks.

Task 63 lowered int arithmetic, and every one of those nine places quietly
started asserting something else. Two failed outright (`assertNotFused` on a
plan that now fuses, and a value expectation). The rest kept passing while
measuring or checking a different thing: a projection with nothing residual in
it, an "ineligible" plan that was now eligible, and three committed benchmark
rows labelled "partial fusion" over a fully fused query. The last kind is the
expensive one, because a passing test that measures the wrong thing publishes
a number nobody re-reads.

The lesson generalises past this instance: **a fixture chosen for what the
compiler cannot do has a hidden dependency on the compiler's frontier**, and
that frontier is exactly what each task moves. The grep that finds them is not
"which tests fail" - it is "which tests name a shape as unsupported". Before
lowering a new expression kind, search the suites for that kind spelled out,
including in SQL strings and in comments, and look at every hit even when the
suite is green. Comments matter as much as code here: the sentence "`i + 1` is
still a non-foldable, non-column offset expression" is how the next reader
learns the fixture's purpose, and a stale one teaches the wrong thing.

When you replace such a fixture, pick the replacement from the far side of the
frontier and say so in the comment: `i % 7` is residual because integer
division has no arm, and the comment says that rather than "not a kernel op",
so the next task to lower `%` finds a sentence that tells it to look.

## A refusal shared by two positions carries one reason, and it can be true of only one

Task 68's whole compiler-visible change was possible because one emitter check
turned out to be two. `VarkaLoopEmitter.requireOffsetShape` policed both
`next_day`'s weekday operand and `add_months`' month count, admitting a literal
slot or a bare column in either and refusing everything else. Its javadoc gave
one reason for both: "a weekday and a month count carry runtime bounds a
derived value cannot declare". That sentence is true of the weekday and false
of the month count, and the difference is exactly task 60's guard, which checks
the count's *value* lanewise against `MONTH_ARITH_MIN/MAX_MONTHS` and cares
nothing about what produced it. A negated column and a column are the same
thing to it. `next_day` has no such guard, so its position really does need the
shape restriction.

The cost of the conflation was two tasks. Task 60 could have admitted a derived
count when it added the guard; task 67 wrote `d - ym_col` and the `YEAR`-unit
cast off as "residual, blocked on the emitter" without asking why. Neither was
wrong to trust the comment - the comment was the only statement of the rule.

Three habits follow, and they are the refusal-side twin of the checklist's rule
about a predicate with more than one reader:

- **When one predicate serves two positions, its reason has to hold for each
  position separately, and the javadoc has to say which position it is talking
  about.** The fix here was not new logic: `isDayOffsetShape` stayed exactly as
  it was and gained a second, laxer caller, `requireMonthCountShape`. What
  changed was that each caller now states its own reason, so the next reader is
  not told that a guarded position is unguarded.
- **A runtime guard on a value is what buys a position its derived inputs.** A
  guard that tests a *shape* at compile time constrains what may reach it
  forever; a guard that tests a *value* per lane constrains nothing upstream.
  So when a task adds a value guard, the next question is which compile-time
  shape restrictions that guard has just made unnecessary - that is a gain the
  guard's own task can bank, not a follow-up.
- **A restriction inherited without its reason is a decline nobody rechecked.**
  Task 67 pinned `d - ym_col` as declining with a test, which is the right way
  to record a limitation; what was missing was the note saying whose limitation
  it was. A pinned decline should name the check that produces it, so the task
  that changes that check finds the test by grepping for it.

The general shape: the ghost-fallback contract makes the compiler and the
emitter agree by forcing the stricter of the two to win. That is safe and it is
also lossy, because the stricter side's reason travels with the *predicate*
rather than with each *call site*, and a reason that is only sometimes true
gets applied everywhere the name appears.

## Adding an IR node moves the bytes oracle's fuzz digests, not its shapes - prove it by diffing keys

`VarkaEmittedBytesSuite` pins two shape sets: every coverage row, keyed by name,
and ten thousand shapes drawn from `VarkaIrGrammar` at a fixed seed, keyed by
block. A new node type adds an arm to the grammar's `rnd.nextInt(n)` draw, and
that reshuffles the whole sample: every fuzz block "moves" although no
emission changed. The suite's failure lists the blocks and says to regenerate
if the change was meant, which is the wrong test to apply here - what has to be
established is that *only* the fuzz half moved.

The check that establishes it, from task 102's `GuardedRange` (19 September
2026): regenerate, then diff the JSON's flattened keys between `HEAD` and the
working tree. The expected answer is exactly two changed keys,
`lanes/4/fuzz/blocks` and `lanes/16/fuzz/blocks`, no coverage key changed, none
added or removed. Any coverage key in that diff is a real emission change and
needs its own explanation. The coverage half is the oracle for emission; the
fuzz half is the oracle for the grammar. Since task 119 the file carries a
second sequence, `fuzz_long`, drawn by the long-lane grammar from its own seed:
a lane-generic node moves both sequences' blocks, a calendar node only the
first, and the two long block lists moving on an int-only change is the signal
that the long grammar was touched when it should not have been.

## A projection that only forwards columns is a selection, and the node with no kernel is the fast path

Varka's eligibility question is "does any entry of this projection *fuse*", and
forwarding a bare column is not fusing it. So `SELECT i2 FROM t WHERE i > k` -
a predicate reading more columns than its consumer wants, which is what survives
Spark's own column pruning - compiles to no kernel at all. Two costs followed
from that, and they are different costs at different layers.

**In the plan**, a projection that is not eligible cannot become a Varka node, so
the only node able to perform the narrowing was the filter's *to-row* node, and
absorbing it there settles the plan at a row boundary. A consumer that wanted
batches was handed rows however it asked, and the query paid task 19's read-back
floor through a plan difference rather than a kernel difference. The fix is to
ask "can Varka serve this plan columnar" rather than "does any entry fuse":
`VarkaColumnarRule`'s pre stage builds `VarkaProjectExec(narrowing, filter)`, the
pair `columnarSibling` had always built for the cache path, and the post stage
collapses it back into the fused row node wherever a transition was inserted
anyway - so the columnar route is added without any row-consumer plan changing.

**In the evaluator**, the same "no kernel" fact sent every batch to the per-row
fallback, which allocates a batch and projects row by row. That produced columns
the input batch already held, and it cost about five sixths of what the plan fix
had just won: 120.3 to 164.6 M rows/s, where the un-narrowed shape ran at 862.
A projection that only forwards is a **selection** - the output is the input's
own vectors, reordered and dropped - and with that path it reads 883.7.

Two things to carry. First, *a node without a kernel can still be the fast path*;
"nothing fuses" is a statement about arithmetic, not about whether Varka should
handle the batch. Second, the ownership rule that makes such a batch safe:
`VarkaEvaluatorBase.release` closes a batch it does not recognise **whole**, so a
forwarded-only batch has to be tracked owning nothing. Building one with
`new ColumnarBatch(...)` and handing it out would close the input's vectors
underneath the child - invisible in every answer and fatal to the next batch.

And the measurement lesson beside it: the same change appeared to regress an
untouched shape by 23%, and the band measured afterwards put that case at a
30.6% spread with nothing changed - tier 3, unreadable. A file without a band
cannot distinguish a regression from its own noise, which is `PLAN_TASK_145.md`
5.4 and the reason row 90's rule exists.

## The bytes oracle pins one point in the option space, so an option a session can set needs an arm of its own

`emitted_bytes.json` hashes every emitted method body at
`VarkaEmitOptions.DEFAULTS`. While every option was a test hook that was the
whole story: a suite that wanted a variant asked for it and asserted on the
result in the suite itself. Task 121 gave `useAVX` a session configuration, and
from then on a user could select an emission no committed hash covered.

The rule the file now carries, from task 167: a field that gains a
configuration gains a pinned arm with it, recorded as one digest per arm per
width in the oracle's `option_arms` section rather than a shape-by-shape block,
because five arms of ten thousand shapes would multiply the file to say the
same thing. The defaults keep the per-shape detail that says *which* shape
moved; an arm's digest only has to say that the arm moved.

Two things the audit that produced it is worth reusing for. It emits **both**
values of every boolean, so the report names the default in its own numbers -
the arm that moves nothing - instead of resting on a reader's memory of the
`DEFAULTS` constructor. And it asserts only that it emitted something: a loop
over an empty option list would otherwise report "no option moves anything",
which is the one outcome that looks like success and is not. Run it with

    VARKA_OPTION_AUDIT=true build/sbt \
      'catalyst/testOnly *VarkaEmittedBytesSuite -- -z "option audit"'

An option that moves no hash over the whole set is a finding, not a pass: it is
either dead or guarding a shape the corpus does not contain, and the audit
reports the count rather than telling those apart.

## A lane can change width at a root's store without the loop ever holding two widths

Task 102's `hour(t)`, `minute(t)` and `second(t)` are 64-bit divisions whose
results are ints, and the plan's first reading (`PLAN_TASK_102.md` 2.5) was that
an int output from a long-lane kernel waited on task 28's bi-lane loop. It did
not. The computation stays in the long lane to the last instruction; only the
store narrows, and a store is per root. So the IR gained one node,
`NarrowLane(child)` - an `INT` value over a `LONG` child - admitted **at an
output root only**, and the emitter gained one store: `convertShape(L2I)` into
the int species of the same width, then a masked store of the low half of the
lanes at `i * 4` instead of `i * 8`. Nothing else in the kernel changed, and the
bytes oracle's coverage keys prove it: three rows added, none moved.

What made it small, and what to carry to the next width change:

- **Ask what the emission lane is, not what the root's lane is.** A kernel is one
  species, and until this node every root's lane was the species. `emissionLane`
  answers "the child's lane for a narrowing root, the root's otherwise", and the
  three places that choose the species - `laneOf`, `fitsBudgets`, the
  compiler's mixed-lane check - ask it. The root's own `laneType()` stays `INT`,
  which is what the evaluator's output allocation and the tree-building
  constructors need to see.
- **The two costly things were both avoidable.** A second `IntVector` species in
  the class would make the shared templates bimorphic and box every other int
  kernel (`PLAN_TASK_28.md` 2.2); the store uses the width's own int species and
  a mask instead. And the mask is an int mask, which C2 lowers at every width,
  where the long lane's masks are per-lane at two lanes (task 153).
- **A root-only node has three doors to guard, and two of them are not the
  emitter's.** The emitter refuses it under another node, ahead of the lane check
  so the message names the cause. The compiler must decline it first, because
  `hour(t) + 1` type-checks and builds an int-lane tree over it - `compileRoot`
  carries an `atRoot` flag into the one place the node is made, so the interior
  route declines with the reason. And the fuzzer's reach tests must name it as
  deliberately out of reach, since the grammar composes nodes under nodes and a
  root-only node is not a shape to draw until task 28 makes it one.
- **The word liveness walk must know a node reads no word.** The first arm
  mirrored `GuardedRange` and demanded the child's validity word; that word is
  consumed only by the root write, which the bitmap pass may take over, and the
  emitter's own invariant caught it - "word slot stored but never loaded" -
  before any test compared a value. A pass-through node that needs the word for
  itself (a guard) and one that merely forwards it (a narrowing) look alike in
  the IR and differ exactly here.
- **A new permitted subtype is invisible to the incremental compiler's exhaustiveness
  check.** Two Scala matches over the sealed IR in `VarkaRangeAnalysisSuite` had no
  `NarrowLane` arm, and every `Test/compile` in the working tree that added the node
  stayed green: the sealed hierarchy is a Java interface, and Zinc did not recompile
  the Scala files that match on it. A fresh worktree's full compile failed on both as
  fatal warnings, which is what CI would have done. After adding a node type, compile
  the test sources from clean once, or grep for the matches over the hierarchy's
  sibling arms and add the new one by hand before trusting the incremental build.
- **A changed invariant has readers in the tests too, and they read regenerated
  files.** "A root's lane is the kernel's lane" had been re-derived in five
  places: two in the emitter, two in the compiler, and one each in the width
  audit's shapes and the bytes oracle, both under `src/test`. A grep over
  `src/main` fixed the first four and missed the last two, which then sent a
  long-lane kernel through the int entry point. Enumerate readers with a
  whole-tree `git grep` and have every harness ask the one accessor
  (`emissionLane`, or the compiled projection's `lane`) rather than keep a copy.
  And sequence the regenerations: the audit and the oracle read the coverage
  rows from `sql/varka/coverage.json`, which `VarkaCoverageSuite` writes, so in
  one combined run they audited the old rows and passed; run the coverage suite
  first and the readers after, and rerun the readers when it changed the file.

## The class-file caps are out of reach of any admitted shape, so their checks are pinned on hand-built measurements

The IR caps (`MAX_FUSED_NODES` 64, `MAX_CHAIN_DEPTH` 16) bound what one kernel can carry, and
the heaviest shapes they admit stop well short of the class-file format's caps of 65535 bytes of
code in a method and 65535 constant pool entries. The heaviest ladder the op cap admits - sixty-two
`add_months(d, k)` outputs - puts the legacy single epilogue at 49339 bytes, and a balanced
`greatest` over thirty-two `add_months`, the heaviest single output, at 25629 bytes for its masked
loop method; the constant pool stays in the hundreds either way (task 87, step 5). So no test can
reach those caps with an expression, and a test that claimed to would be pinning a shape's
current size, not the cap. `VarkaEmitBudget.overLimits` reads the caps from a `VarkaEmittedClass`
measurement, and the test that pins the readings builds the measurement by hand. A change that
raises the IR caps has to re-read this: past roughly 80 `add_months`-weight outputs the legacy
epilogue would cross the method cap, and the emitter would then measure it before the JVM did.

## The single-epilogue form is a reference variant at `methodByteBudget` 0, and the tests that pin its facts say so

Since task 87 the default emission splits the epilogue per group and sets up each group's
methods for that group alone, so a single-group kernel's epilogues are `epilogueDense0` and
`epilogueMasked0`, and the form before it - one epilogue over every output, every method set up
for the whole kernel - is what `methodByteBudget` 0 emits. That form is not dead: it is the arm
`VarkaMethodSizeBenchmark` measures the split against, and several facts the suites pin are facts
about it - where its single epilogue crossed 8000 bytes, that prefix sharing moved the crossing,
that a heavy single output landed in a method HotSpot never compiled. A test that pins one of
those passes `withMethodByteBudget(0)` explicitly (`VarkaEmitterTestBase.epilogueSize` does it
for its callers), so the assertion keeps measuring the thing it names when the default moves
again. A test that names `"epilogueMasked"` without the suffix under the defaults is asking for a
method that no longer exists, and the failure reads as a missing method, not as a wrong number.
