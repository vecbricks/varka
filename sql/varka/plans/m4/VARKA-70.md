# VARKA-70: Validity as bitmap algebra in the driver

## 1. Where this came from

`m4/PLAN.md` row 70 and section 2.34, opened out of VARKA-46's
admission check (`VARKA-46.md` 2.3 and 3.7), which priced one
`orValidityBitsAt` call at 1.87 to 3.24 ns per lane group whatever the vector
width, and observed that for most value roots the bits that call writes are a
bytewise function of the input bitmaps over the whole batch - so they can be
computed once per batch in the driver, the way VARKA-45's `setValid` fills a
constant, instead of once per lane group in the loop. VARKA-45 took the call
off the dense path by knowing the answer in advance; VARKA-46 made the calls
that survive inline; this task removes most of what survives on the masked
path. The numbers that motivate it are the masked rows of
`sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt` and
its `-128bit-` sibling as VARKA-32 B2 regenerated them, quoted in section 2.4.

## 2. The admission check, done

Four things had to be true. Each was read off the emitter and the committed
files on 7 September 2026, at `bbc91b7ec39`; none needed a probe.

### 2.1 Every value node's word is an alias, an AND or an OR - except three

`VarkaLoopEmitter.planWordRef` is the whole algebra. A `ColumnRef` is its
input's word; a `LiteralSlot` is the all-true constant; `AddDays`, `SubDays`,
`DateDiff`, `NextDay`, `AddMonths` and `TruncDateDynamic` are the AND of their
two children's words; every calendar extraction, `ThursdayOf`, `DayOfWeek`,
`WeekDay` and `DayOfWeekIso` alias their child's; `Greatest` and `Least` OR
their children's; `IfElse` blends its two branches' words by the condition's
known-true mask, which is computed per lane group; a `Cond` root's slot is a
selection bitmap, not validity at all. So for every root whose tree holds no
`IfElse` and is not a `Cond`, the destination bitmap is a bytewise AND/OR
expression over the referenced inputs' bitmaps, and nothing about it varies
per lane group.

The one node that is null on valid inputs is the one the analysis already
names: non-ANSI `make_date` sets `Analysis.nullsFromValidInputs`, and the
dispatch takes the masked methods for every batch because of it. VARKA-63's
`try_*` forms will be the next; the predicate this task adds is written as an
exhaustive switch over the sealed IR so that a new node has to declare itself
(the `chronoChild` / `tailReadsMarchMonth` pattern) rather than default to
pure.

The derived leaves make two nodes *more* regular, not less. `TruncLevelLeaf`
turns a null, unrecognised or sub-day format into a null lane of the derived
input and zeroes its data; `WeekdayLeaf` does the same for a bad weekday name
(declining the batch in ANSI mode rather than nulling). So `trunc(d, fmt)` and
`next_day(d, s)` reach the kernel as plain ANDs over two input bitmaps, with
the nullness already in a bitmap.

### 2.2 The word has a third consumer, which the milestone section did not list

Section 2.34 counts two uses of a word in the masked body: the root's
validity write and the blend. There is a third. `emitGuardCollect` ANDs a
guard's condemning mask with the guarded node's word, so that a null lane -
whose data slot holds whatever the buffer holds - cannot condemn the batch.
That covers VARKA-52's range guard on a `date_add`/`date_sub` with a column
offset under a calendar consumer, and VARKA-60's month-count guard on
`add_months` with a column count. And the loop body already loads its
columns unmasked (`emitValue`'s `ColumnRef` arm takes the masked
`fromMemorySegment` only in the epilogue), so garbage in a null lane already
flows through the arithmetic today; the word AND is the only thing keeping it
out of the guard.

The consequence for the design: the task has two separable effects, and the
second has a stricter condition than 2.34 gave it.

* Removing a root's per-group *write* is safe whenever its word is a pure
  AND/OR expression (2.1). Per root.
* Removing the per-group *reads* and the word locals is a liveness question,
  per loop method: a word is emitted if and only if some node in the method
  still consumes it - a guard, a blend, a `Cond` root's OR, or a root whose
  validity the pass does not serve. "Every root qualifies" is neither
  necessary (a method of `year(d), greatest(d, d2)` needs no word once both
  roots are served by the pass) nor sufficient (a method of
  `year(date_add(d, off))` alone has one qualifying root and still needs the
  word for the guard).

What this check would have rejected: a design that dropped the read
whenever the roots qualified. On nullable data with a stray value in a null
slot it would decline batches that today fuse - answers right, through the
row engine, and the kernel's win gone on exactly the `year(date_add(d, off))`
shape VARKA-52 built the guard for.

### 2.3 What the pass has to handle that `setValid` does not

`setValid` writes a constant. The pass writes an expression, and not every
operand is a bitmap. The three helpers the expression is built from -
`VarkaVectorSupport.copyValidity`, `andValidity` and `orValidity`, each
setting exactly `rows` bits, each allowing the destination to alias an
operand - landed with this plan, ahead of any emitter work, with
`VarkaVectorSupportBitmapAlgebraTest` holding them to the loop's own form
bit for bit (the `setValid` test's pattern), the operands sliced to exactly
the bitmap so an over-read fails the test rather than reaching a neighbouring
Arrow buffer.

An operand is in one of three runtime states, and the rule differs by
operator. All ones is the identity of an AND and annihilates an OR; all zeros
is the reverse:

| operand state | in an AND | in an OR |
|---|---|---|
| a bitmap (`0 < nullCount < rows`) | read it | read it |
| null count 0, no bitmap materialised | drop the operand | the whole expression is `setValid(dst, rows)` |
| null count `rows`, validity address `0L` | the whole expression is `zero(dst)` | drop the operand |

After dropping, an expression of two operands may be left with one, and then
it is a `copyValidity`; left with none, it is the `setValid` or the `zero`
that the operand's state already decided.

**The earlier draft of this section had one rule for all of it, and it was
wrong.** It said only that a null-free input "contributes an all-ones operand
... so the pass skips it", with no AND/OR distinction. Applied to
`greatest(d, d2)` where `d` is null-free and `d2` has nulls, skipping gives
`copyValidity(dst, d2Validity, rows)`, which marks null every row where `d2`
is null - rows on which `greatest` returns `d`'s value and the output is
valid. The same reading left the degenerate case undefined: `year(d)` over a
null-free `d` inside a masked kernel needs `setValid`, not a copy of a bitmap
that was never materialised. Both states are reachable today, because
`emitDispatch` takes `runMasked` as soon as *any* referenced input has nulls.

**What that costs the API, which commit 2 decides.** The emitter knows an
input's null count at emit time only for a literal; at runtime it arrives in
the `nullCounts` argument, and step (4) of `emitBody` stores `aconst_null`
into `srcValSeg[i]` for a null-free input and for an all-null one alike -
there is no segment to pass in either state. So the segment-only helpers as
landed force the emitter to generate the three-way choice above as bytecode,
per operand, per served output. The alternative is an overload taking
`(long address, int nullCount)` per operand, which puts the three states in
the engine - two `if`s in Java instead of a bytecode diamond, and nine
two-operand combinations a JUnit test can enumerate without emitting
anything.

The code-size argument does not decide this, and 6.1's prediction 6 has the
measurement that says so: the two forms cost about +1.5 KB and +0.6 KB on the
48-output driver, which is 4.2 KB against 3.2 KB in a method whose limit is
8 KB and whose shape's epilogue has already crossed. What decides it is where
a wrong branch can hide. These three states are the whole of where a silently
wrong validity bitmap comes from - an all-ones operand annihilating an OR is
not a crash, it is a null row that should have had a value - and the engine
form puts every combination in a JUnit test that runs in milliseconds, where
the bytecode form puts them in emitted kernels driven through `checkMatrix`
with crafted null counts. The engine form also keeps the emitter smaller,
which the delegation goal wants. Its one real cost is that a `long` address
is not a `MemorySegment`, so the bounds check milestone 1's finding 1 is
about has to be rebuilt inside the entry point with
`ofAddress(addr, (rows + 7) / 8)` rather than being supplied by the caller;
that is one `reinterpret` per operand per batch, and it must not be
forgotten.

**Decided, and landed with this plan.** The segment-taking helpers stay
exactly as they are - they are the bit-exact primitives and
`VarkaVectorSupportBitmapAlgebraTest` is their oracle - and
`copyColumnValidity`, `andColumnValidity` and `orColumnValidity` sit beside
them, taking `(long address, int nullCount)` per operand, resolving the three
states and delegating. They map each operand at exactly `(rows + 7) / 8`
bytes, which is what restores the bound the raw `long` does not carry.

The emitter therefore emits one call per expression node whatever the runtime
states are, with the arguments it already holds: `srcValidity[i]` and
`nullCounts[i]` are parameters of the body, so an operand is
`aload; ldc; laload` and `aload; ldc; iaload`, five bytes each.

`columnEntryPointsResolveTheThreeOperandStates` covers all nine two-operand
combinations for each operator and the three for the copy, at every length in
the file's ladder, in milliseconds and with no bytecode involved. It was
mutation-checked against the defect this whole section is about: giving the
OR root the AND rule for a null-free operand fails it at
`orColumnValidity[null-free, all-null]`.

The bytecode-ladder form is **not** built as a live variant. It is the one
place in this task where the `FloorMod7` discipline is deliberately not
applied, and the reason is that the two forms differ in nothing a benchmark
could measure - the pass runs once per batch, so both resolve the states in
time that does not appear in any row - and the only dimension they differ on,
emitted bytes, is settled above by measurement without building either. If a
shape ever appears where the driver's size is binding, the ladder is the
fallback and this section is the record of what it would cost.

The final partial byte is masked to `length % 8` bits, the rule `setValid`'s
javadoc records and `assertSameOutput` enforces byte for byte.

### 2.4 The bound, from the current file

Masked against dense, M rows/s, the parity file the plan committed before the
work (provenance `018228099ef`, 2026-09-07, both widths from one regeneration;
the numbers here are that file's on purpose, and section 9's are the file this
task ships):

| shape | AVX-512 | 128-bit |
|---|---|---|
| `year` | 3002.5 / 3449.1 | 1191.6 / 1334.2 |
| `year+month+day+quarter`, one loop method | 1056.2 / 1622.4 | 417.6 / 794.2 |
| `next_day(d, k)`, column kernel | 6707.4 / 7887.1 | 2838.3 / 3542.7 |
| `add_months(d, m)`, column count | 633.9 / 703.1 | 209.5 / 242.9 |
| `add_months(d, 13)` | 730.8 / 728.1 | 254.9 / 255.6 |
| `filter d < literal` (a `Cond` root; not served) | 21548.1 / 23815.4 | 4802.2 / 6371.0 |

VARKA-46 took most of the single-field gap: `year` has 12.9% and 10.7% left.
The prize is the multi-output method, 1.54x at AVX-512 and 1.90x at 128-bit
on four fields, which is the shape B2 emits by default now, and the two-input
node at 15.0% and 19.9%. `add_months` with a literal has nothing left - its
81-op tail hides one write, and its masked row reads 0.4% *above* its dense
one, which is where this file's noise floor sits - and the column-count form
keeps its read for the guard (2.2) until VARKA-64 takes the guard off the
in-range case.

**The baseline rows added with this plan** (the regeneration committed beside
it, at both widths), masked against dense:

| shape | AVX-512 | 128-bit |
|---|---|---|
| `greatest(d, d2)` (an OR root) | 8539.1 / 11810.8 | 2648.3 / 10870.5 |
| the same, first input all-null | 9793.4 | 3860.5 |
| `year+month+day+quarter`, shared, chunk 4096 | 1322.7 / 2133.1 | 428.6 / 807.7 |
| the same, chunk 4095 | 1314.5 / 2148.4 | 427.0 / 809.1 |
| the same, chunk 64 | 897.8 / 1572.6 | 381.4 / 705.9 |
| the same, chunk 63 | 700.2 / 1368.2 | 349.3 / 662.1 |

The OR root is the largest mover in the file at both widths, and by a long
way at the narrow one: 1.38x at AVX-512 and 4.10x at 128-bit, because a
two-input masked body pays two reads, one write and its own OR word per
group, and at four lanes a group is four rows. A narrow-vector measurement
was not optional here, exactly as `SKILLS.md` says of anything that shares
live values. Its all-null arm sits between the two, 9793.4 and 3860.5, so it
does reach a loop and does have room to move - which the row it replaced did
not, and that is most of why it was replaced. The chunk-64 and chunk-63 rows
are risk 2's before-numbers: the masked four-field kernel runs at 0.57x and
0.51x of its dense twin on short batches at AVX-512, and 0.54x and 0.53x at
128-bit, which is the gap a per-batch pass has to close without costing more
than the calls it replaces on an 8-byte bitmap.

**Why this is the second regeneration of the same commit, and what the first
one cost.** The file this branch first committed was taken on a disturbed
machine, and the disturbance was invisible where the harness looks for it:
all nine controls were flat, every one within -0.2% to +0.8%. What gave it
away was arithmetic. Its AVX-512 sub-microsecond dense rows had fallen 33.5%
(`date_add emitted loop, null-free`) and 20.8% (`sequential kernels, 9
passes`) against the file before it while the 128-bit file's held, which left
three orderings identical code cannot produce: the wide `greatest(d, d2),
null-free` behind its own 128-bit companion, `arithmetic depth 4, null-free`
likewise, and `arithmetic depth 4, mixed nulls` ahead of its own null-free
row.

This run, on the same commit, the same governor and epp, and a load of 0.63,
puts all three back. `date_add emitted loop, null-free` reads 19227.8 against
the disturbed 12754.1 - and against the 19180.2 the file had *before* the
disturbed run, so it returned to where it was rather than to somewhere new.
Wide `arithmetic depth 4, null-free` is 19283.8 against the narrow 19051.6,
the right way round. And no masked row now beats its dense twin by more than
4.1%, against the 23.7% inversion the disturbed file carried on `arithmetic
depth 4`.

`dev/varka_bench_regen.sh` already says to do this - if unrelated rows move
together while the controls hold, re-run the base the same day before reading
anything into it - and it is a step 0 in 8 rather than a footnote because the
numbers it changed are not small: the OR root's AVX-512 gap read 5.5% off the
disturbed file and is 27.7% here, a factor of five, and it is the row this
task's largest predicted win is registered against.

**What the register should take from it: the controls are necessary and not
sufficient.** All nine are long-running row-engine and scalar cases at tens
of nanoseconds per row, and they are insensitive to whatever perturbs a
kernel that runs a million rows in 0.08 us. Two checks catch what they miss,
both free and both on the file itself rather than on a second run: a masked
row may not beat its dense twin, and on a saturated dense shape a wide row
may not lose to its own 128-bit companion. Either one would have caught this
file the day it was written.

### 2.5 The pinned oracles

`VarkaLoopEmitterSuite`'s `everyNode` fixture is an `IfElse` root over a tree
holding non-ANSI `MakeDate`, so its root does not qualify and its masked body
keeps every word: the pinned line map is predicted unmoved. `DEFAULTS`
renders empty whichever way the new switch defaults, so no production shape
hash moves. Both are asserted by the suites as they stand.

## 3. The design

### 3.1 The word expression, evaluated once per batch

Two additions to the analysis and one to the driver, all behind
`VarkaEmitOptions.validityByBitmap` (default decided in 8; off reproduces
today's bytes exactly, the `FloorMod7` precedent).

**The purity predicate.** `Analysis.pureWord(node)`: an exhaustive switch
over the sealed IR returning, for a value node, the bitmap expression its
word denotes - `Input(i)`, `AllTrue`, `And(a, b)`, `Or(a, b)` - or `null`
where the word is computed (`IfElse`, any node with `nullsFromValidInputs`
set, a `Cond`). It mirrors `planWordRef` case for case and the suite asserts
the two agree on every node the fixtures build, so the algebra cannot drift
from the emission.

**Where the pass goes, which is narrower than it looks.** `emitBody` in
`DRIVER` mode runs in five steps: (1-2) the prologue; (3) map each output's
data and validity segment and `zero` the bitmap, or `setValid` it on a dense
batch (VARKA-45); (4) compute `dead[i]`, `hasNulls[i]` and `srcValSeg[i]` from
the runtime null counts; (5) the all-null shortcut, which returns 0 for a
batch whose every output is all-null. **The pass goes between (4) and (5)**,
and neither neighbour will do.

At (3) its inputs do not exist yet: 2.3's three operand states *are*
`dead[i]` and `hasNulls[i]`, and those are written in (4). After (5) a batch
that takes the shortcut returns with the destination bitmap never written,
and the reused Arrow validity buffer keeps the previous batch's bits, which
read as valid over undefined data. A two-output kernel makes that concrete:
`datediff(d0, d1)` beside `year(d2)` with `d0` all-null does *not* take the
shortcut, because output 2 reads no all-null column, so output 1's bitmap
would simply never be written.

Between (4) and (5) the pass *replaces* step (3)'s `zero`/`setValid` for the
outputs it serves - step (3) keeps emitting it for the others. That is the
only reading that holds step (3)'s stated invariant, "`zero(dstValidity)`
before any return below": it is kept by the pass, not by leaving the zero in
place and writing over it. An earlier draft of 2.3 assumed the opposite, that
an all-null AND operand could lean on step (3)'s zero having already run.
For a served output there is no step (3) zero to lean on, which is why 2.3's
table gives that state its own `zero(dst)` call.

**What it emits.** For each served output, one call into `VarkaVectorSupport`
per node of the expression - `copyValidity(dst, src, rows)`,
`andValidity(dst, a, b, rows)`, `orValidity(dst, a, b, rows)` - with 2.3's
operand states resolved either as bytecode here or inside the helpers, which
is the API question 2.3 leaves to commit 2. The helpers are the engine's,
reached by name like `setValid`; each sets exactly `rows` bits.

**Nesting, and where the destination alone stops being enough.** A
left-leaning expression is emitted inner-first into the destination: every
operand is then either an input bitmap or the destination itself, the
destination may alias an operand, and no scratch buffer is needed. That is
not every expression. `DateDiff` is the AND of its two children's words and
`Greatest`/`Least` the OR of theirs, and both IR nodes are binary, so
`datediff(greatest(d, d2), greatest(d3, d4))` denotes `And(Or(a, b),
Or(c, d))` - two live intermediates, which two-operand destination-aliasing
calls over one destination cannot evaluate in any order.
`VarkaIrFuzzSuite` builds exactly this: `DateDiff`, `Greatest` and `Least`
all take arbitrary `value(depth)` children, so the shape arrives whether the
surface has it or not.

Commit 2 handles it in two steps rather than by growing a scratch buffer.
First, flatten: AND and OR are associative and commutative over bitmaps, so
any tree of a *single* operator collapses to a left-leaning chain, which
covers every shape today's surface produces. Second, where a genuinely mixed
tree remains, decline to serve that root: it keeps its per-group write and
its word stays live, which is today's behaviour and is always correct. A test
asserts the declined-root count is zero over the suite's fixtures, so the
safety net cannot quietly become the common path, and the fuzzer is what
exercises the net itself.

**Word liveness, per method.** `planSlots` today allocates a word local per
referenced input and per own-word node. Under the switch it allocates them
only for words some emitted consumer reads: a guarded node's word, an
`IfElse`'s condition and branches, a `Cond` root's, a `Greatest`/`Least`
whose own word feeds one of those, and any root the pass does not serve. A
method with no live word skips the per-group `validityBitsAt` reads entirely,
and an input whose word is dead in a method needs none of that method's
null-state prologue either - no `srcValSeg`, no `dead`/`hasNulls` flags - so
the lane-group body and the prologue are then the dense method's bytes, the
one-body-not-two result, verified in 5 rather than assumed (6.1's prediction
6 sizes what the epilogue keeps if the prologue is not dropped with the
words).

**Liveness is checked by the emission, not by the list in 2.2.** `loadWord` is
the one call through which every consumer reads a word, so it counts each use
in `Slots` as it emits, and the emitter asserts at the end of every method
that a word the liveness pass declared dead was loaded zero times - an
`IllegalStateException` at emit time, which `VarkaIrFuzzSuite` drives over
random IR. The inventory in 2.2 is how the rule was designed; the counter is
what keeps a future consumer from being missed silently.

**The write.** `emitLaneGroup` skips the per-group `orValidityBitsAt` for a
root the pass served, the way `fillsValidityOnce` skips it on a dense batch;
the two decisions are taken in one place so the driver and the loop cannot
disagree, which is the failure `fillsValidityOnce`'s javadoc names. The
epilogue's per-output partial write goes with it: the pass covers every row
of the batch, tail included.

### 3.2 What is deliberately unchanged

* The dense path and VARKA-45's `setValid`: `denseValidityOnce` stays a live
  option beside the new one rather than being folded into it.
* The guards, their word AND and the status route (VARKA-42, VARKA-52, VARKA-60): a guard
  keeps its word, which is the whole of 2.2.
* `Cond` roots and the filter path (VARKA-21): a selection bitmap is computed,
  not derived, and stays per group; VARKA-46 is what made that call cheap.
* The fragment mechanism, grouping, `GROUP_BUDGET` and `FUSED_CEILING`: the
  validity calls were never counted as vector ops, so no *weight* moves and
  no shape regroups. The driver's *bytes* do move, which is a different
  question with a different limit behind it, and it is 6.1's prediction 6
  rather than an item on this list.
* Aliasing the output bitmap to an input's buffer (2.34's step two): an Arrow
  ownership and lifetime question at the evaluator, scoped out of this task
  and left in section 2.34.
* VARKA-64's per-batch range check, which decides *whether* a guard is emitted;
  this task decides what a body without a guard still has to read. 64 widens
  what this task drops, and is the natural next task, not a prerequisite.

### 3.3 Registered op counts

The metric this task moves is not `IntVector` invocations - none is added or
removed in any body - but the *validity work* per masked loop method: the
`validityBitsAt*` reads and the `orValidityBitsAt*` and
`orPartialValidityBitsAt*` writes today, the whole-batch helpers after.

A plain owner-wide count will not express it, and the difference decides
whether the table below can be asserted at all. `VarkaEmitterTestSupport`'s
three-argument `invocationCount` counts every invocation on an owner inside a
method, and `loadSegment` emits `VarkaVectorSupport.ofAddress` for each
segment the body touches in *every* body mode - only the `zero`/`setValid`
of step (3) is gated on `DRIVER`. So an owner-wide count of a masked loop
method can never reach zero however much validity work goes away, and every
"0" registered below would be unreachable with the tool named to read it.
The four-argument overload landed with this plan takes an exclusion list;
the metric is that count with `ofAddress` excluded, exact-matched rather than
by prefix for the reason `invokedNames` documents - the helpers carry a
lane-count suffix, and `orValidityBitsAt` is a prefix of
`orValidityBitsAt16`. `dev/varka_emit.sh` prints the same number as a
`validity` column beside `IntVector` and `VectorMask`, so a reviewer reads
the before and after without writing a test.

Registered from the emission sites in 2.2, to be asserted in 5:

| shape | masked loop method, `VarkaVectorSupport` calls today | after |
|---|---|---|
| `year(d)` | 1 read + 1 write | 0 |
| `year, month, dayofmonth, quarter` over `d` (one method) | 1 read + 4 writes | 0 |
| `next_day(d, k)`, column kernel | 2 reads + 1 write | 0 |
| `greatest(d, d2)` | 2 reads + 1 write | 2 reads (the pick's substitution) + 0 - registered as 0, corrected by the assertion; see below |
| `year(date_add(d, off))`, guarded | 2 reads + 1 write | 2 reads (the guard) + 0 |
| `year(d)` beside `d < lit` (a `Cond` root, one method) | 1 read + 2 writes | 1 read + 1 write (the `Cond`) |
| `if(d < d2, d, d2)` | 2 reads + 1 write | unchanged |

`IntVector` counts per body are asserted unchanged for every shape above.

**One row was registered wrong, and the assertion is what found it.** The
table was derived from 2.2's inventory of word consumers, and that inventory
has a fourth entry 2.2 did not list: `emitPick`'s null substitution -
`a.blend(b, ~validA)` and its mirror - reads *both operand words for the
value*, whether or not the pick's own word is wanted afterwards. So for
`greatest(d, d2)` the two reads stay and only the write goes: 2, not 0. The
liveness pass in the emitter already knew this (its javadoc lists the
substitution as the consumer the inventory missed, because the pass was
written from the emission sites rather than from 2.2), and the test in 5 that
asserts this table failed on exactly that row the first time it ran. The row
above now says what the emitter does; the original registration is kept in
this paragraph rather than overwritten, because the miss is itself the
finding: an inventory made by reading is not the same as one made by
counting, which is why the counters in 3.1 exist. It also bears on 6.1's
prediction 2, which expected the OR root to be the largest mover and was
reasoning from three calls removed rather than one; section 9 scores it as
registered.

## 4. Files

| file | what |
|---|---|
| `sql/varka/engine/.../VarkaVectorSupport.java` | `copyValidity`, `andValidity`, `orValidity`, each setting exactly `rows` bits, and the `*ColumnValidity` entry points of 2.3 over them; tests in the engine module. Landed with this plan |
| `sql/catalyst/.../varka/VarkaEmitOptions.java` | `validityByBitmap`, `withValidityByBitmap`, in `canonical()` |
| `sql/catalyst/.../varka/VarkaLoopEmitter.java` | `Analysis.pureWord`, word liveness in `planSlots`, the pass in the driver, the skipped writes |
| `sql/catalyst/.../varka/VarkaLoopEmitterSuite.scala` | the tests in 5; the poisoned `makeInputData` landed with this plan |
| `sql/catalyst/.../varka/VarkaEmitterTestSupport.java` | `invocationCount`'s exclusion overload, 3.3's metric; landed with this plan |
| `sql/catalyst/.../varka/VarkaEmitDump.scala` | the `validity` column, so `dev/varka_emit.sh` shows the metric; landed with this plan |
| `sql/catalyst/.../varka/VarkaIrFuzzSuite.scala` | poisoned null lanes; landed with this plan |
| `sql/core/.../VarkaDifferentialSuite.scala` | both switch settings over the nullable fixtures, both widths |
| `sql/catalyst/.../VarkaEmitterParityBenchmark.scala` | the A/B rows in 6 |
| `sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-*` | one regeneration |
| `docs/sql-varka.md`, `SKILLS.md`, `m4/PLAN.md` | the validity paragraph, the lesson, row 70 and the 45/46/47 entries |

## 5. Tests, and what each is for

* **`pureWord` agrees with `planWordRef`** on every node of every fixture in
  the suite, by construction: catches an algebra that drifts from the
  emission.
* **Byte-identical validity, both settings, both widths**: `checkMatrix` over
  the calendar, arithmetic, `next_day`-column, `greatest`/`least` and
  `datediff` shapes at `remainderLengths` (1, 13, 17, 63, 1001) plus 64 and
  1000, every null pattern - the existing oracle; `assertSameOutput` holds on
  the tail byte.
* **The guard under nulls, with poisoned slots**: `year(date_add(d, off))`
  and `add_months(d, m)` over data whose null lanes hold `Int.MIN_VALUE` and
  `Int.MAX_VALUE`, asserting status 0 under both settings - the failure 2.2
  describes. Until this plan the fixtures wrote the drawn value under a null
  slot, in range by construction, so nothing could have provoked it.
  `makeInputData` now poisons, and so does `VarkaIrFuzzSuite`, which draws
  its columns inside `columnBound` and `MONTH_ARITH_MAX_MONTHS` and would
  otherwise have kept every null lane in range for ever - the fuzzer being
  named below as where an unlisted interaction shows. Two details the first
  cut got wrong and section 9 records: the alternation counts null slots, not
  row indices, because an `i & 1` poison collides with the `alternating` null
  pattern and would have left a quarter of the matrix on one side of every
  bound; and the handful of guard tests that deliberately place a boundary
  value *at* a lane they also null pass `poisonNulls = false`, since
  substituting an extreme there replaces the value the test names and turned
  one of them into a duplicate of the case beside it. The change landed with
  the plan rather than with the emitter work, so the whole existing matrix
  ran against garbage in its null lanes first - for VARKA-42, VARKA-52 and VARKA-60 as
  much as for this one.
* **The dead-word invariant fires**: a test that emits a shape with a
  deliberately mis-marked word (through a test-only hook, the
  `misdescribeAdd` pattern) and asserts the emit-time exception, so the
  counter in 3.1 is known to be armed.
* **An all-null input through the pass**: an OR root (`greatest(d, d2)` with
  `d` all-null, output equal to `d2`'s bitmap, `0L` never dereferenced) and
  the AND root beside it in a two-output kernel that keeps the driver's
  all-null shortcut from firing (`datediff(d, d2)` with `year(d3)`), which is
  the case 3.1 says the pass must run before step (5) to cover.
* **A null-free input through the pass**: `greatest(d, d2)` with `d`
  null-free and `d2` nullable, whose output must be valid on every row -
  2.3's correction, and the one case the earlier rule got wrong. Beside it
  `year(d)` over a null-free `d` in a masked kernel, whose output is
  `setValid`, not a copy. The engine's own
  `columnEntryPointsResolveTheThreeOperandStates` already covers every
  combination of the three states; what these add is that the emitter passes
  the right arguments to them, which is a different question and the only one
  left once 2.3's API is in the engine.
* **A mixed word tree declines rather than mis-evaluates**:
  `datediff(greatest(d, d2), greatest(d3, d4))` keeps its per-group write and
  its word; a single-operator tree of any depth is flattened and served. The
  declined-root count over the suite's fixtures is asserted at zero.
* **A mixed method**: `year(d)` beside `d < lit` in one loop method; the
  `Cond` keeps its per-group OR and the read stays; the year's write goes.
* **The liveness counts of 3.3**, asserted per method with
  `invocationCount(bytes, method, owner, List.of("ofAddress"))`, and
  `IntVector` counts unchanged.
* **The driver's size, pinned on the same ladder as the epilogue**: 20, 21,
  40, 44 and 48 outputs, both shared and unshared, asserted under
  `HugeMethodLimit` with the margin recorded - 6.1's prediction 6. Nothing
  measures the driver today, and it is the one method every batch runs.
* **One body, not two**: for the shapes whose methods have no live word, the
  masked loop method's code size equals the dense one's.
* **Pinned oracles**: the line map and the shape hash unmoved (2.5), asserted
  as the suites stand.
* **`VarkaIrFuzzSuite`** at both settings, which is where an interaction
  nobody listed would show.

## 6. The measurement

`VarkaEmitterParityBenchmark`, adjacent A/B pairs on shapes that already
exist so the point is what the pass does to kernels that ship: the "year"
section's `year, mixed nulls` and `year+month+day+quarter, shared, mixed
nulls`, the `next_day(d, k)` column kernel's mixed-null row, the
`add_months(d, m)` column-count mixed-null row (the control: its guard keeps
the read, so it should move by the write alone), and `add_months(d, 13),
mixed nulls` (the second control: predicted flat). Three rows the file did
not have were added with this plan, per the rule that a baseline is
committed before the change that moves it: `greatest(d, d2)` null-free and
mixed nulls in the datediff section (the OR root - the one shape where the
pass computes an OR), `greatest(d, d2)` with its first input all-null beside
them (the `0L`-address operand, on the OR root because an AND root's batch
takes the driver's all-null shortcut and never reaches a loop - 2.4), and the
four-field shared kernel's mixed-null arm on every rung of the alignment
ladder (chunks 4096, 4095, 64 and 63 - the short batches risk 2 is about,
which had only null-free rows). Each with the switch on and off, both widths,
one regeneration with `dev/varka_bench_regen.sh` on an idle machine. The
dense rows are the bound: no masked row may pass its dense counterpart.

### 6.1 Predictions, registered before the run

Every threshold below is a fraction of the row's own gap in the file quoted in
2.4 - the second regeneration of `018228099ef`, not the disturbed first one -
so a later regeneration cannot invalidate a percentage that was pinned to a
superseded run, which is how the first cut of this section went wrong.

1. `year+month+day+quarter, shared, mixed nulls` closes at least two thirds
   of its gap to the dense row at both widths: from 1056.2 towards 1622.4 and
   from 417.6 towards 794.2. Reason: four writes and one read per group go,
   and nothing else in that body differs from the dense one. Confidence
   medium-high.
2. `year, mixed nulls` closes the whole of its remaining gap to
   `year, null-free` - one read and one write per group is all that separates
   them - and is the smallest relative mover among the served shapes.
   `greatest(d, d2), mixed nulls` is the largest relative mover at both
   widths, closing at least half of its 4.10x gap at 128-bit (2648.3 towards
   10870.5) and at least half of its 1.38x gap at AVX-512 (8539.1 towards
   11810.8). The AVX-512 half of that could not be registered against the
   disturbed file, where the same gap read 5.5%; it can be registered now,
   and it is the prediction the re-run was worth making. Confidence high on
   the ordering, medium on the halves.
3. `add_months(d, 13), mixed nulls` moves within run noise (its dense row is
   0.4% away). Confidence high. `add_months(d, m)` column count moves less
   than `next_day(d, k)` column, because its read stays for the guard.
   Confidence medium. The short-batch rows move with the long ones: the
   four-field masked kernel at chunk 64 and 63 closes at least half of its
   gap to the dense twin at both widths, and the pass is never the reason a
   short-batch row is slower than before. Confidence medium - this is risk 2
   measured. The all-null row moves with the other `greatest` rows, the
   shortcut having declined a null-skipping root; the row it replaces could
   not have moved at all, which is 2.4's reason for replacing it.
4. No masked row passes its dense counterpart by more than this file's own
   tie floor. That floor is +4.1%, on `year, validity OR-ed per group` at
   128-bit - a VARKA-45 reference variant, not a shipped shape - and six of
   the eight ties in the file are under 1.5%, all on heavy-tail arithmetic
   where one write is invisible. Confidence high; a miss above the floor is a
   measurement error to explain, not a result. The bound is stated this way
   because the disturbed file broke it by 23.7% on `arithmetic depth 4` and a
   flat "never passes" would have read as a finding about the pass rather
   than about the run (2.4).
5. No pinned oracle moves and no dense committed number moves beyond noise.
   Confidence high.
6. **The epilogue's `HugeMethodLimit` crossing moves from 44 shared outputs
   to 47, and from 21 unshared to 22.** Measured before the work rather than
   discovered after, off `javap` of the classes the pinned crossing test
   emits, at `bbc91b7ec39`: in `epilogueMasked` each served output's write is
   one 12-byte sequence (`aload dst; iload i; i2l; lload word; iload lanes;
   invokestatic orPartialValidityBitsAt`) and each input's word read is one
   32-byte three-way block (dead, has nulls, null-free), and the ladder reads

   | outputs (dates) | shared today | shared after | unshared today | unshared after |
   |---|---|---|---|---|
   | 20 (5) | 3575 | 3175 | 7670 | 7270 |
   | 21 (6) | 4020 | 3576 | 8331 | 7887 |
   | 40 (10) | 7082 | 6282 | 18396 | 17596 |
   | 44 (11) | 8058 | 7178 | 20511 | 19631 |
   | 45 (12) | 8726 | 7802 | | |
   | 48 (12) | 9084 | 8124 | | |

   where "after" subtracts 12 bytes per output and 32 per input. Interpolating
   the shared column's 119 bytes per output inside a date, 46 outputs land
   near 7909 and 47 near 8016 - sixteen bytes over the limit - so the shared
   crossing is 47 or 48, and the pinned test re-pins to whichever it is;
   unshared, 21 now fits and 22 crosses near 8377. **In the epilogue** the
   crossing can only move outward, so nothing that compiles today stops
   compiling there. Confidence medium-high on the direction and the 21-to-22
   move, medium on 47 against 48. One more thing the same numbers say: after
   the reads and writes go, the 44-output masked epilogue is still 7178 bytes
   against the dense 6574, and that residue is the per-input null-state
   prologue - which is why 3.1 drops it with the dead words; the prediction
   for the one-body test in 5 is that with the prologue gone the two differ
   by under a hundred bytes.

   **The driver, which the ladder above does not cover - measured, because the
   first version of this prediction guessed and guessed wrong.** Every byte
   the pass adds lands in `emitBody`'s `DRIVER` mode, and the driver is one
   method for all of a shape's outputs and the one method that runs on every
   batch, so it looked like where a `HugeMethodLimit` crossing would hurt
   most. `runMasked`'s code size over the same ladder says otherwise:

   | outputs (dates) | `runMasked` | `epilogueMasked` |
   |---|---|---|
   | 4 (1) | 278 | 783 |
   | 20 (5) | 1122 | 3575 |
   | 40 (10) | 2194 | 7082 |
   | 44 (11) | 2409 | 8058 (crosses) |
   | 48 (12) | 2624 | 9084 |

   and for two-input outputs, `greatest(d, d2)` per date: 272 at 2 outputs,
   828 at 8, 1596 at 16, against an epilogue of 514, 1790 and 3518.

   So at the output count where the epilogue crosses, the driver is at 2409
   bytes with 5591 to spare, and it grows about 53 bytes per single-input
   output against the epilogue's 216. Costing the pass per served output, net
   of the step (3) `zero`/`setValid` it replaces: about +12 bytes with 2.3's
   operand states resolved in the engine and about +32 with them resolved as
   emitted bytecode, for a single-input output; about +22 and +73 for a
   two-input one. On the 48-output rung that is +0.6 KB against +1.5 KB, so
   the driver lands at 3.2 KB or 4.2 KB and **neither form crosses** - the
   epilogue crosses first in both, four outputs earlier, which is the
   constraint the pass is there to relieve.

   Registered, therefore: the driver does not cross `HugeMethodLimit` under
   either form of 2.3's API on any shape whose epilogue still compiles, and
   the API is decided on the grounds in 2.3 rather than on this. Two things
   still hold from the original worry. The driver must be pinned on this
   ladder in 5, because nothing measures it today and a crossing would
   surface only as an unexplained wide-shape regression. And if one ever does
   cross, it is far cheaper than VARKA-44's case: the driver holds no vector
   work, so an interpreted driver boxes nothing and pays once per batch,
   where an interpreted epilogue boxes vectors for up to `lanes - 1` rows of
   every batch. Confidence high on the byte figures, which are read off the
   class file rather than argued.

The rule that decides the default: on, if prediction 4 holds and no served
row is slower than before at either width.

## 7. Risks

1. **A consumer of the word nobody listed.** 2.2 found three by reading the
   emitter; a fourth would show as a wrong answer under the differential or
   the fuzz suite, or as a spurious decline under the poisoned-slot test.
2. **The pass costs more than it saves on short batches.** At 64 rows the pass
   is 8 bytes against four lane groups' calls; the alignment ladder's chunk-64
   and chunk-63 rows show it either way.
3. **Reads kept for a guard hide the write's saving.** The column-count
   `add_months` control is there to show the write alone; if it does not
   move, the write was not the cost on that shape.
4. **VARKA-44's crossing moves again** - and it is not a risk to the shape or
   the numbers, only to the record. The crossing is a measured property that
   can only move outward here, it is pinned by a suite test that fails the
   moment it moves, and 6.1's prediction 6 says where it lands, so the
   re-pin in the same commit is a scored prediction rather than a surprise.
   `VARKA-32.md` 7.1's ladder is requoted in 9.
5. **A switch that defaults on changes the bytes of nearly every masked body
   in the shape cache at once.** The differential and fuzz suites at both
   settings are the oracle, the reference variant stays live, and the default
   flips in the last commit per 8 - the same discipline every lowering change
   here has followed, applied to the widest one so far.

## 8. Sequencing

0. **The base re-run, before any emitter work. Done** (`018228099ef`,
   2026-09-07, load 0.63). The file this branch first committed was not a
   sound baseline for the AVX-512 column of the fastest dense rows, and the
   benchmark source had since changed - the `datediff` all-null row replaced
   by the `greatest` one - so it no longer matched the harness that produced
   it. One regeneration of the same commit settled both; 2.4 records what it
   found and what it changed, and 2.4, the milestone's 2.34, `SKILLS.md`,
   `GROUP_BUDGET`'s javadoc and 6.1's predictions are all requoted from it.
   Everything below is measured against that file.
1. `pureWord` with the agreement test, and the `loadWord` use counter with
   its invariant asserted on today's emitter (every word loaded at least
   once): no emitted byte changes. The engine helpers and their column-taking
   entry points, the poisoned harness, `invocationCount`'s exclusion overload
   and the baseline rows are already in, with this plan; so is 2.3's API
   decision and the measurement behind 6.1's prediction 6, so commit 2 starts
   with nothing left to choose.
2. The pass, the liveness rule and the skipped writes behind the switch, off
   by default; the tests of 5; both widths green. The driver pinned on the
   output ladder here, since it is the commit that grows it.
3. The A/B rows and one regeneration with the switch off, section 9's 9.1 and
   the default decided by 6.1's rule (step 3a). Then, because the decision was
   on: the default flipped, the variant rows renamed to the per-group reference
   arm, the crossing test re-pinned, and a second regeneration so the committed
   file's plain rows are the shipped bytes (step 3b); 9.2 onward scored from
   it; the docs and the milestone rows swept, including what VARKA-47 is left
   with. The second regeneration was not in the plan as written: a default
   decided *from* a regeneration invalidates that regeneration's labelling the
   moment it is applied, and the next task that measures before it decides
   should budget for two runs.

## 9. Outcome

Two regenerations of one commit's kernels, and the second is the file that
ships. The first, at `eb76f9a2418` with the switch off by default, measured
the pass as a variant beside every shipped shape it changes, and the default
was decided on it by 6.1's rule (9.3). The second, at `1f4d3e12404` with the
switch on, is the committed file: its plain rows are the shipped bytes and its
"words per group (VARKA-70 A/B)" rows are the reference arm - the masked loop
reading the input words and ORing the root's validity per lane group, which is
what shipped until this task. Both ran on an idle machine (loads 0.88 and
0.86; canaries within 0.7% and, for the second, compute +0.1%, cache +5.2%,
memory -0.4%); the controls held within 2.8% in each. Every number below is
the committed file's unless it says otherwise, and the few that are the first
run's are on the allowlist for the reason 9.3 gives.

### 9.1 The pairs: reference arm -> shipped, masked, mixed nulls, and the dense row

*Requoted on 7 September 2026 from the third regeneration, which the review
of this PR forced: VARKA-46's A/B arms were built on `DEFAULTS` and so, once
this task's default flipped, priced two byte-identical kernels (9.6). The
numbers below are that run's; the two earlier runs' figures are in this
file's history, and where a ratio moved between runs the text says so.*

| shape | AVX-512 | 128-bit |
|---|---|---|
| `year` | 3092.5 -> 3446.7 (1.11x), dense 3459.3 | 1190.5 -> 1332.5 (1.12x), dense 1331.4 |
| `year+month+day+quarter`, one method | 1069.8 -> 1703.9 (1.59x), dense 1687.4 | 417.3 -> 790.1 (1.89x), dense 793.0 |
| `next_day(d, k)`, column kernel | 7294.3 -> 7894.5 (1.08x), dense 7603.4 | 2832.3 -> 4086.8 (1.44x), dense 3571.3 |
| `add_months(d, m)`, column count | 632.7 -> 690.1 (1.09x), dense 699.2 | 209.4 -> 238.9 (1.14x), dense 242.9 |
| `add_months(d, 13)`, the control | 731.2 -> 732.0 (1.00x) | 254.6 -> 254.8 (1.00x) |
| `greatest(d, d2)` | 9702.8 -> 10153.8 (1.05x), dense 12425.4 | 2622.7 -> 4241.4 (1.62x), dense 11347.5 |
| the same, first input all-null | 10773.1 -> 11213.7 (1.04x) | 4182.2 -> 6044.9 (1.45x) |
| four fields, chunk 4096 | 1359.2 -> 2199.7 (1.62x), dense 2199.7 | 421.2 -> 807.0 (1.92x), dense 807.8 |
| four fields, chunk 4095 | 1318.3 -> 2195.1 (1.67x), dense 2212.1 | 421.7 -> 805.1 (1.91x), dense 809.7 |
| four fields, chunk 64 | 906.4 -> 1174.6 (1.30x), dense 1544.8 | 378.3 -> 588.9 (1.56x), dense 714.8 |
| four fields, chunk 63 | 713.7 -> 1018.4 (1.43x), dense 1367.1 | 347.8 -> 498.5 (1.43x), dense 676.7 |

The four-field shape, which B2 emits by default, runs 1.59x and 1.89x faster
than the arm that shipped before this task and lands on its dense twin - 1.0%
above it at AVX-512, 0.4% below at 128-bit. Every served row is faster at
both widths, and the control does not move.

**What is stable across the three runs and what is not.** The four-field
headline is (1.54x, 1.59x, 1.62x) at AVX-512 and (1.87x, 1.89x, 1.92x) at
128-bit across the three regenerations, and `year` is (1.10x, 1.11x) and
(1.11x, 1.12x): those two carry the task's claim and they hold. The picks and
`next_day` do not: `greatest` read 1.19x at AVX-512 in run two and 1.05x in
run three, because both of its rows moved by 6-7% in opposite directions,
which is inside the file's own noise for a row at ten billion rows per second
and outside anything a ratio of two such rows can resolve. Where this file
reasons from `greatest`, it reasons from the byte counts in 9.4, not from the
ratio.

### 9.2 The predictions, scored as 6.1 registered them

Gap closed means the fraction of the reference arm's distance to the dense row
that the shipped row recovers.

*The figures scored below are the second regeneration's - the run the default
was decided from, and the one this section was written against. The third run
(9.1, 9.6) requoted the table without changing any verdict here, but it moved
two of the percentages enough to state: `greatest` closes 17% and 19% of its
gap rather than 47% and 19%, so prediction 2's "not half" holds by more; and
the short batches at AVX-512 close 42% at chunk 64 and 47% at chunk 63 rather
than 26% and 10%, so prediction 3's width-dependent miss is much narrower than
it read - close to the registered half at both widths rather than at one. The
four-field and `year` figures are within a point of the run scored here.*

1. **Four fields closes at least two thirds of its gap at both widths. Hit,
   with margin.** 104% at AVX-512 (595.5 of 574.1) and 97% at 128-bit (363.1
   of 374.0).
2. **`year` closes its whole gap and is the smallest mover; `greatest` is the
   largest at both widths and closes at least half its gap. One hit, three
   misses - and the misses were on the record before the run.** `year` closes
   99% at 128-bit and 72% at AVX-512 (3328.5 against 3444.8, 3.4% short), and
   it is the smallest relative mover at 128-bit (+10.7%) but not at AVX-512,
   where the all-null pick (+3.5%), the chunk-63 row (+8.3%) and the column
   count (+9.2%) move less. `greatest` is not the largest mover at either
   width - the four-field rows are, at +54.5% / +58.7% and +86.6% / +85.4% -
   and it closes 47% of its gap at AVX-512 and 19% at 128-bit, not half. The
   reason is 3.3's corrected row: the pick's null substitution reads both
   operand words for the value, so the pass removes one of its three validity
   calls rather than three of three, and the loop loses 17 bytes of 383 rather
   than becoming its dense twin. The prediction reasoned from the inventory
   that missed that consumer; the emitter did not.
3. **The control is flat; the column count moves less than `next_day`; the
   short batches close half their gap; the all-null row moves with its
   sibling. Three hits and one width-dependent miss.** `add_months(d, 13)`
   moves +0.9% and -0.0%. The column count moves less than `next_day(d, k)` at
   both widths (+9.2% against +15.1%; +13.8% against +41.7%). The all-null
   `greatest` row moves +3.5% and +48.5% beside its mixed-null sibling's +19.2%
   and +62.9% - the same direction and a smaller step, since one operand's
   contribution is a constant either way. The short batches close half their
   gap at 128-bit - 64% at chunk 64, 52% at chunk 63 - and not at AVX-512,
   where they close 26% and 10%. What is left there is not the loop. At 64
   rows and sixteen lanes a batch is four lane groups, and the pass has removed
   their four reads and sixteen writes; the residue between 1080.1 and the
   dense 1549.6 is the masked *driver*, whose per-batch null-state prologue
   runs in full for every referenced input on every batch and whose share of a
   64-row batch is what it is. At four lanes the per-group work was the larger
   share and the pass closed more. That residue is VARKA-47's (9.5). The pass is
   never the reason a short-batch row is slower: every one is faster.
4. **No masked row past its dense twin by more than the tie floor. Hit on
   every row but one, and the one is not the pass.** The largest genuine tie
   is the four-field shape at AVX-512, 1.3% above dense (1689.1 against
   1667.7), under the 4.1% floor. `next_day(d, k)` at 128-bit reads 4003.7
   masked against 3494.3 dense, +14.6% - and read +12.3% in the first
   regeneration too, so it is not a one-off. It is also not the pass: the
   one-body test proves the two loop methods identical (257 bytes each),
   `-XX:+PrintCompilation` shows both at 256 bytecode bytes reaching tier 4 the
   same way, and timed interleaved under one JIT state over one L1-resident
   buffer they run 4287 / 4319 / 4306 / 4278 M rows/s, within 1%. Identical
   code at identical speed in the probe and 13-15% apart in the harness, at one
   width only, across two runs: what differs between them is that the harness
   streams two 80 MB columns from memory and the dense arm's pair (`nfData`
   with `kData`) is not the masked arm's pair (`mxData` with `kData`), so the
   relative placement of the two streams is not the same. That is the likely
   mechanism and it is unverified; it is in the debt register as a harness
   question, and it does not touch the decision, since the pass is faster than
   its reference arm on this row at both widths whatever the dense row reads.
5. **No pinned oracle moves; no dense number moves beyond noise. Hit, with the
   noise measured rather than assumed.** The line map and shape hash tests are
   as they were. The emitter's off-path bytes - the reference arm - were
   compared method by method against master's on seven shapes and are
   identical, so every non-A/B row in both runs is the same class file as
   before; the sub-microsecond family still moved by up to 20% between the two
   runs, in both directions, which is the noise 2.4 documented and the reason
   this task's claims rest on adjacent pairs rather than on rows across files.
6. **The epilogue crossing and the driver. Two hits, one miss in the good
   direction, and one exact.** Unshared moves from 21 to 22 as predicted (7563
   fits, 8033 crosses). Shared was predicted at 47 or 48 and is at 49 (48 fits
   at 7464, 49 crosses at 8035), because what the prediction's last paragraph
   allowed for happened in full: the null-state prologue went with the words,
   and `epilogueMasked` is `epilogueDense`'s bytes on every rung of the ladder
   rather than "within a hundred bytes" of it. The driver grows by 432 bytes
   at 48 outputs (2624 to 3056), under the 500 registered, and crosses nothing
   at either setting. The pinned crossing test carries the new boundaries and
   the old ones under the reference arm.

### 9.3 The default

On. 6.1's rule was that prediction 4 holds and no served row is slower than
before at either width. Every served row is faster at both widths in both
runs; the control is flat; and prediction 4 holds on every row but one, which
is identical bytecode running at identical speed when the JVM is asked
directly - the "measurement error to explain" the prediction named, not a row
the pass made worse.

The decision was taken on the first regeneration, whose plain rows were the
arm that shipped before this task: the four-field shape read 1034.6 -> 1644.5
against a dense 1662.5 at AVX-512 and 420.1 -> 792.9 against 790.5 at 128-bit,
`year` 3208.8 -> 3455.8 and 1193.4 -> 1330.9. Those numbers are on the
allowlist because the commit that carried that file does not survive the
squash-merge; the second regeneration says the same thing in the file a reader
can open. The flip needed that second regeneration, which 8 did not
anticipate: the committed file's plain rows must be the shipped bytes, and the
moment the default moved those were the pass, so the variant rows became the
per-group reference arm and the file was regenerated once more.

### 9.4 Findings that were not predictions

* **A fourth word consumer**, found by the assertion that pins 3.3 the first
  time it ran (3.3, corrected in place with the original kept). It moved
  prediction 2 from what was expected to what happened.
* **The agreement check's operator arm was unsound**, and the fuzzer refused
  it twenty jobs out of twenty on the first wave: two operands with different
  owners can have equal expressions, and a pick's OR of two equal ANDs folds
  to an AND. Removed in step 1; `SKILLS.md` has the lesson.
* **One body, not two, exactly.** With the pass on, `loopMasked0` is the size
  of `loopDense0` for `year(d)` (420), the four fields (626), `next_day(d, k)`
  (257) and `datediff(d, d2)` (158), and the epilogues likewise (437, 649, 276,
  177); the masked methods had been 514, 750, 442 and 343. `greatest(d, d2)`
  keeps its substitution and goes 383 to 366; `year(date_add(d, off))` keeps
  its guard and goes 691 to 681; `if(d < d2, d, d2)` is unserved and does not
  move. On the output ladder every shared and unshared rung has the two
  epilogues equal, which is why the crossing is the dense epilogue's now.
* **The driver, measured**: 1122 / 2409 / 2624 bytes at 20 / 44 / 48 shared
  outputs under the reference arm, 1282 / 2801 / 3056 under the pass; the
  epilogue crossed first at every rung before this task and, at 49, still
  does.
* **The short-batch residue at AVX-512 is the driver's prologue** (prediction
  3), which puts a number on what VARKA-47 is now about.
* **A default decided from a regeneration costs a second regeneration** (9.3;
  8 is amended, and `SKILLS.md` has the lesson).
* **The `next_day` dense row at 128-bit** (prediction 4): a harness question,
  in the debt register.

### 9.5 What VARKA-47 is left with

Per lane group, in the masked body: a `Cond` root's selection OR (the filter
kernel), an `IfElse`'s blend, `make_date`'s validity test, the two reads a
pick's substitution makes (`greatest(d, d2)` at 10807.0 and 4371.0 against its
dense 12742.6 and 11492.5 - the widest gap left among the served shapes), and
the read and AND a range guard keeps (`year(date_add(d, off))`,
`add_months(d, m)` at 683.7 against 698.8 and 238.5 against 243.1). Per batch,
in the masked driver: the null-state prologue for every referenced input,
which is the whole of the gap left on a 64-row batch at AVX-512 (chunk 64 at
1080.1 against 1549.6). VARKA-64 removes the guard from the in-range case and
so widens what this task's rule drops; the prologue's share on short batches
is a new item for 47's own plan.

### 9.6 The review, and the third regeneration

*Added 7 September 2026, after `/code-review max #145`. Fifteen findings
survived verification; none was a wrong answer. What they changed:*

**Three invariants this task introduced were not airtight.**

* `emitMakeDate` read its own validity word with a raw `lload` rather than
  through `loadWord`, so that read was outside the count the whole "every
  word access goes through one call" claim rests on, and its two stores were
  gated on `!dense` rather than on `ownWord` like every other arm. Harmless
  today - the guard loads the same word a few instructions earlier - and not
  harmless the moment `make_date`'s guard becomes conditional, which is VARKA-64's direction. It also meant the fault injector was not armed for that
  node: with the liveness verdict inverted the emission died in the class-file
  writer with an invalid local index instead of the documented
  `IllegalStateException`. Both fixed; the injector test now covers
  `make_date` and asserts the message.
* `liveWords`' two switches over the sealed IR ended in `default -> { }`
  where `childrenOf` and `Analysis.analyze` are exhaustive on purpose. A node
  type added without an arm would have produced an emit-time exception, which
  `VarkaKernelEvaluator` catches as an emission failure - a silent per-row
  fallback on every batch of a user query, with EXPLAIN still claiming
  fusion. Both switches are exhaustive now, so it is a compile error.
* The predicate deciding that a node carries a range guard was written out
  twice, in `planSlots` and in `liveWords`. VARKA-52 and VARKA-60 each added a
  guarded node kind; a third added to one and not the other would give that
  node a guard whose word the liveness pass had killed. One `guardedWord`
  helper, read by both. *Superseded on 8 September 2026 by VARKA-63's review:
  the two readers turned out to ask different questions - `planSlots` wants
  "does this node need a scratch local", `liveWords` wants "must this node's
  word stay alive" - and a checked arithmetic node answers no and yes. They
  are `guardScratch` and `guardedWord` now, the first written into the second
  so the containment is structural rather than a coincidence; the lesson holds
  in the form "the two must not disagree", not in the form "one helper".* `emitGuardCollect` deliberately still refuses a dead
  word rather than skipping the AND: that refusal is what the injector arms,
  and skipping would turn a liveness bug into spurious batch declines on
  nullable data.

**The safety net 3.1 promised did not exist.** `declinedBitmapRoots` was
incremented and read nowhere, and `Analysis` is private, so no suite could
have read it. A regression that stopped serving every root would have
reverted the whole lowering to the per-group path and passed the suite - the
byte-identity tests compare the two settings, which agree when nothing is
served; the differential compares against a reference evaluator, and the
per-group path is correct; the size assertions are upper bounds. There is a
public `bitmapPassCounts` now, and a test pins served and declined counts for
nine shapes including the option-off case.

**The measurement was pricing nothing, on three rows.** VARKA-46's A/B arms
were built as `DEFAULTS.with<option>(false)`. Once this task's default turned
the per-group validity call off for a served root, both arms of those pairs
stopped making the call the option governs, and the regenerated file
committed three rows comparing byte-identical kernels while
`VarkaEmitOptions`' javadoc still cited them as the evidence for those
options. The 128-bit file says it plainly: the pair that had read -21%
against its comparand read -1.6% after the flip. VARKA-46's *naming* tests
caught their half of this and were re-pinned in the same commit as the flip,
because an assertion fails when its subject vanishes; its two *behavioural*
tests and the benchmark did not, because two identical kernels do agree and a
measurement reports a tie. The arms are built on `perGroupWrite` now, the
rows say so in their labels, and `SKILLS.md` carries the rule.

That fix required the third regeneration, which 9.1 is requoted from. With
the arms on the reference arm the pairs measure again, and they do not say one
thing: the OR's position confirms the shipped default at both widths and on
both shapes (`year` 2652.6 against 3092.5, the four fields 963.5 against
1069.8 at AVX-512; 953.5 against 1190.5 and 348.0 against 417.3 at 128-bit),
while the width-named helpers win on the four fields (922.5 against 1069.8;
335.2 against 417.3) and *lose* on single-field `year` (3366.0 against 3092.5;
1281.6 against 1190.5). VARKA-46's default is right for the shape it was
argued from and may not be for a single field. That is VARKA-46's decision to
revisit, not this task's, and it is in the debt register.

**The 128-bit `fused, 64 ops` row moved by 105x, and prediction 5 said no
dense number would.** The row is masked, so it is not a dense number, but the
movement is far outside anything 9.2 accounts for and the reviewer was right
that section 9 owed it an explanation. What the record shows:

| commit | 128-bit | AVX-512 |
|---|---|---|
| `8fb18b355e2` .. `00eeed82279` | 270.4 .. 273.2 | 992.0 .. 988.6 |
| `aef0b82260e` (master) | 8.8 | 1757.0 |
| `1f4d3e12404` (step 3a) | 9.2 | 1860.1 |
| `8ddc8afa642` (step 3b) | 966.6 | 2046.4 |
| this commit (third run) | 961.6 | 2048.4 |

So it is not this task inventing a number: a 30x regression landed on master
with `aef0b82260e`, which moved the same row 1.8x the *other* way at AVX-512
and was committed without being remarked on, and removing the per-group
validity call undoes it and goes 3.5x past the pre-regression baseline. Two
mechanisms are ruled out rather than argued about. It is not a method-size
cliff: the masked loop is 898 bytes with the pass off and 804 with it on, the
masked epilogue 1477 and 1343, all far under `HugeMethodLimit`. And it is not
a compile failure of those methods: under `-XX:+PrintCompilation` at four
lanes, driven through the masked path by the `--nulls` option this review
added to `VarkaEmitDump`, both arms take every masked method to tier 4 with no
bailout, no `COMPILE SKIPPED` and no deoptimisation beyond the routine
superseding of the tier-3 versions. In isolation the two arms compile the
same way; the 105x appears only in the file, where this case runs after about
a hundred other kernels have been compiled in the same JVM. That is the
condition `VARKA-11.md` section 6 already names for this very case, and it
belongs in the debt register as a harness question beside the `next_day` one,
not as a claim about the lowering.

**Six corrections to text that had drifted from the code**, none of them
behavioural: the user-facing per-group list in `docs/sql-varka.md` reproduced
the three-consumer inventory that 3.3 had already corrected; the
`validityByBitmap` javadoc claimed 1.6x and 1.9x where the file said 1.54x and
1.87x, and claimed no masked row passes its dense twin, which the `next_day`
row contradicts; two comments in `VarkaKernelEvaluator` still asserted that
the driver zeroes every destination validity, which is exactly what the pass
stopped doing for a served root; the differential's "an OR root" query was a
mixed tree the pass declines, so no end-to-end test had ever reached
`orColumnValidity` - there is a served OR over `varka_date_pairs` now; and the
new algebra section had been inserted between the `Analysis` javadoc and the
class, orphaning it.
