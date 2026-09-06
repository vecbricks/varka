# Varka Milestone 4 Plan: the date family, and the emitter under it

**Re-scoped on 4 September 2026.** This milestone opened as *breadth* and the
paragraphs below still say so, because a plan is a record. What it became, task
by task, is the `DateType` family and the emitter and evaluator infrastructure
every kernel rides on, and the owner re-scoped it to exactly that: every task
whose subject is another lane or output type - 27 (boolean outputs), 28
(lane-width conversion), 29 (int64 lanes), 30 (ANSI integer arithmetic), 39
(`date - date`, an int64 output) and 49 (civil-from-days in long lanes) - moved
to `PLAN_MILESTONE_5.md`, text and task numbers unchanged, with the catalogue
items about other lanes (1-5, 7-10). Their sections and rows here are stubs
that point there, so citations still resolve. The coverage milestone that was
called milestone 5 is now milestone 6 (`SCOPE_MILESTONE_6.md`). Everything
below that says "milestone 5" in the old sense has been repointed.

Milestone 3 closed with task 23, so this file is no longer the scope document it
opened as: it is the task plan that document promised, written against the
measurements it said should order it. The scope catalogue it grew from is kept
whole in section 10, with every item's number unchanged, because other plans cite
those numbers (`PLAN_TASK_21.md` cites items 5 and 11, `SKILLS.md` cites item
13, and `SCOPE_MILESTONE_6.md` cites items 1 through 12 throughout). Where the
catalogue and this plan disagree, this plan wins - the catalogue records what
was thought before the survey and before milestone 3's numbers, and several of
those thoughts have already been corrected in writing (`SCOPE_MILESTONE_6.md`
section 2).

Milestone 1 built kernels. Milestone 2 built the emitter and proved it on int32
date chains. Milestone 3 made that fast path reach real queries: filters, the
four gating shapes, cross-task class reuse. Milestone 4 is **breadth**: the
engine stops being a single-type demo and learns the types, expressions and
loop schedules a query actually contains. Task numbering continues the
project's single sequence and resumes at 24, after milestone 3's 18-23; the
committed spine is tasks 24-31 (24-30 as planned, plus 31, added during task 24
at the owner's request; see 2.2).

## 1. Why this order

The scope document refused to order itself until three inputs existed. All
three now do:

* **The survey ran** (`SCOPE_MILESTONE_6.md` section 1), and its corrections
  are folded in rather than re-litigated: there is not one `DOUBLE` or `FLOAT`
  column in TPC-DS or TPC-H, so item 3 is the taxi benchmark's item; the old
  item 8 was two items and is now 8 (string functions, 37 uses) and 9 (string
  keys, 275 references, with the cheap subset pulled into milestone 6);
  decimals - the most-aggregated type in both corpora - stay out per item 12,
  and their design pass is milestone 6 items 1 and 2. Item 6's calibration
  survived: `year(` appears 3 times, the rest of the extraction family zero.
* **Milestone 3 landed the enablers.** Task 18's shape cache is what makes item
  13 affordable at all (a longer C2 compile is now paid once per shape, not per
  task); task 21 made masks first-class values and priced the compaction that
  item 11 is expected to replace (~1-3 ns/row typed copy - the ceiling on what
  `compress(mask)` can recover on this machine, `PLAN_TASK_21.md` section 5);
  task 23's emit-options record is the option surface this milestone's emitter
  work rides on.
* **The headline decision is made.** The owner picked TPC-H and TPC-DS as the
  corpus this milestone builds toward, not the taxi benchmark. Consequences:
  item 3 (float and double lanes) leaves the committed spine and waits with the
  follow-ons - only its oracle decision lands early, because it is a reading
  task and it unblocks the item whenever it is argued back in (section 7). The
  taxi whole-query claim stays milestone 6's target 5, and becomes reachable
  the day items 2, 3 and 6 have all landed.

The scope document's three invariants still decide what can share a task, and
the spine keeps them apart: one lane width per kernel (items 1, 2), every value
lane-shaped (item 7, deferred), no lane reads its neighbour (items 8-11's
cross-lane work, of which only item 11's compaction is taken, in the operator
where task 21 already bounded it). A task that takes two invariants at once is
a task whose failure cannot be attributed to one of them.

## 2. Design

### 2.1 The scalar tail, mask interrogation, compaction (task 24, item 11)

The tail is the argument. The emitter's remainder handling is a *second full
walk of the IR*, emitting scalar bytecode for every node - roughly half of the
per-node emitter surface, and every new node type in tasks 26-30 would
otherwise be written twice. `indexInRange` produces the mask for a partial lane
group directly, so the replacement shape is: the main loop stays **unmasked**
(masked ops cost 2.3x-2.9x even all-true, `SKILLS.md`), and one masked epilogue
iteration replaces the entire scalar walk. The temptation to resist, named
here so the task does not discover it: masking the main loop would be simpler
still and would cost more than the tail ever did.

The task opens with the measurement the scope filed as open question 3: the
tail's actual share of emission time and of loop runtime. If the runtime share
is negligible, the case rests entirely on emitter code size - still a good
case, but it must be made on the honest number.

Second deliverable: `compress(mask)` compaction in `VarkaFilterExec`, replacing
task 21's scalar typed copy. The ceiling is committed (~1-3 ns/row), so the
prediction registers against it before the run. On x64 with AVX-512 `compress`
intrinsifies to `VPCOMPRESSD`; the development machine (Zen 5) will flatter
it, so the number is taken at `-XX:MaxVectorSize=16` as well, and the fallback
verdict is written either way. Third: `anyTrue`/`allTrue` per-lane-group
all-null and all-valid fast paths, where the prologue today has them only per
batch.

This task changes emitted bytes, and the expectation written here before it
ran was that the two pinned shape hashes and the pinned line-map literal
would move with them. **They did not, and task 24 records why**
(`PLAN_TASK_24.md` section 5): the hashes are taken over the IR, the input
counts and the emit options, and the line map over the IR's topological
schedule - none of which a change to the emitted method structure touches.
So the pinned oracles were this task's behaviour-preservation proof rather
than its collateral. The spine's first task that legitimately moves them is
26, which adds IR nodes.

### 2.2 Asserting the instructions, not the ratio (task 31)

Added during task 24, at the owner's request, and scheduled before task 25
because it is task 25's instrument. Every vectorization claim this project
makes today is inferred from a throughput ratio - the parity gate's "emitted
loop within 0.9x of the hand-written kernel" stands in for "C2 intrinsified the
Vector API calls". Task 24 showed how weak that inference is: the same kernels
measured 50-190% apart under `-XX:CompileCommand=inline,jdk/incubator/vector/*.*`
in the engine's JMH harness and within 1% under it in the catalyst harness, so a
ratio can move for reasons that have nothing to do with the instructions
emitted. A test that reads the instructions cannot.

The mechanism is a forked JVM with `-XX:+UnlockDiagnosticVMOptions
-XX:+PrintAssembly -XX:CompileCommand=compileonly,<class>::<method>`, whose
output is scanned for the instruction *family* the shape should produce. Four
things decide whether this is a good test or a flaky one:

* **It asserts a family, never a mnemonic.** The lane width is a property of the
  host - `zmm` on AVX-512, `ymm` on AVX2, NEON on aarch64, and `xmm` under the
  narrow-vector CI run - so the assertion is "a packed integer add on a vector
  register of the width this host reports", derived from `IntVector.SPECIES_PREFERRED`
  rather than hard-coded. The interesting negative is a *scalar* body where a
  vector one was expected, and that is what the test names when it fails.
* **It skips cleanly without `hsdis`.** `PrintAssembly` degrades to a warning and
  bytecode-level output when the disassembler is absent, which is the likely
  state of a CI runner; the suite must detect that and skip rather than fail, and
  say which it did. It is a gate on the developer machine and the runners that
  have `hsdis`, not a gate that goes red for missing tooling.
* **It names methods the emitter generates.** Emitted classes are named for their
  shape hash, so `compileonly` takes a wildcard over the generated package and
  the loop-method naming scheme (`loopDense0`, `epilogueMasked`), which task 24
  made stable.
* **The kernels come first.** `DateVectorOps` is the reference and its shapes are
  fixed; the emitted loops follow, one per gating shape, so a regression is
  attributable to the emitter rather than to the whole stack.

The deliverable that makes this pay beyond a one-off: the assertion sits beside
the existing parity gate, so a future task learns from a *named missing
instruction* instead of from a number that drifted.

This task also owns the second half of the same question, deferred here by the
owner: **whether forcing C2 to inline Varka's own packages changes anything**.
Task 24 measured the JDK half - `-XX:CompileCommand=inline,jdk/incubator/vector/*.*`
moved the engine's JMH numbers by 50-190% and the catalyst benchmark by under 1%,
which turned out to be a fact about the JMH harness rather than about Varka (see
section 9's debt register). The same flag aimed at
`org/apache/spark/sql/varka/**` and at the emitted classes' package is untested,
and belongs with the assembly work because both answer "what did C2 actually do"
with evidence rather than with a ratio. Whatever it finds, a JVM flag cannot be
the shipped answer - it would have to be set on every executor - so the outcome
is either a documented recommendation in `docs/sql-varka.md` or a recorded
decline.

**Answered, and declined (`PLAN_TASK_31.md` section 13).** The directive leaves
every loop body byte-identical - the emitted `year` body stays at 327
instructions with the same 10/8/4/8 vector mix over three runs each, zero
variance - so nothing about vectorization improves. What it changes is the
method boundary: the emitted `run` grows from 271 instructions with no vector
ops to 471 carrying the whole year lowering, and `runDense` stops being compiled
standalone. That is the sibling-method structure task 24 built and
`GROUP_BUDGET` exists to control, so the flag works *against* the emitter's
design, and it would have to be set on every executor to do so. No
recommendation goes into `docs/sql-varka.md`. If method fusion is ever worth
measuring, the emitter produces it directly through `GROUP_BUDGET`, per-shape
and with no flag.

**Update, planning (`PLAN_TASK_31.md` section 2).** The feasibility question this
section leaves implicit - can the *product* JVM disassemble at all - was answered
by running it rather than by reasoning about it, and the answer is yes: an
`hsdis-amd64.so` on `LD_LIBRARY_PATH` plus `-XX:+UnlockDiagnosticVMOptions`
against the system JDK 25 product build returns a disassembled C2 nmethod. Three
things that run established change the mechanism described above:

* **`-XX:CompileCommand=print,<class>::<method>` replaces `-XX:+PrintAssembly`.**
  It prints one method's disassembly rather than the whole compilation log, which
  is the difference between parsing hundreds of lines and parsing tens of
  megabytes. The `compileonly` filter this section pairs with `PrintAssembly` is
  then unnecessary.
* **Both a C1 and a C2 nmethod are printed for the same method.** C1's body is
  scalar by construction, so the assertion must split on the nmethod headers and
  read only the C2 one; asserting over the concatenated output would pass or fail
  for the wrong reason.
* **hsdis separates the mnemonic from its operands with tabs, not spaces.** A
  regex written for spaces matches nothing, and a detector that matches nothing
  is indistinguishable from a body with no vector instructions - so the suite's
  first test is a scalar/vector self-test pair, before any Varka shape.

### 2.3 Instruction-level parallelism (task 25, item 13)

The debt register's rule applies: a prediction goes in writing before the
first measurement, and the honest null hypothesis is that C2 plus the
out-of-order engine already collect most of the available overlap on a 16-op
body, so K pays only on the long chains. The three confounders move together,
never one at a time: K, the broadcast strategy (pinned locals collapsed
throughput 7x at ~32 broadcasts, so unrolling and pre-broadcasting *compete*),
and `GROUP_BUDGET`, which unrolling multiplies against a ~1 ms-per-vector-op
C2 compile (**confirmed by task 43**: 1.1 ms per op at AVX-512 and 2.0 at
128-bit, measured across a 20-to-248-op ladder - see `PLAN_TASK_43.md` 8.2, and
the reconciliation note in 2.16). The candidates are the shapes that are compute-bound and already
carry a committed number to beat: `dayofweek` (a 20-op fold), `CASE WHEN` on
an unpredictable condition, and the depth-8 chain. Row-consumer shapes are
bounded by the ~25 ns/row read-back floor and the filter path by compaction;
no kernel-side ILP moves either, so neither is a candidate. One negative
result worth carrying in: a 2-way unrolled add kernel over a misaligned
buffer still lost 50-60% to the aligned case (section 8's buffer-alignment
entry) - unrolling does not incidentally hide the alignment penalty, so this
task's outcome and that entry's are independent questions, not one deferring
to the other.

Open question 4 is answered, ahead of the task and with the broadcast
confounder held fixed at "emitted per use" so it does not contaminate the
result (`VarkaUnrollFactorBenchmark`, committed results file in
`sql/varka/engine/benchmarks/`, four runs total including two taken after
merging task 24's PR and enabling the machine's performance mode, neither of
which changed a conclusion): on an 8-op chain, K = 1, 2 and 4 are flat at
both vector widths, on every run - within 4% either way, no consistent
winner. The honest null hypothesis holds exactly on a body this short. On a
20-op chain (the `dayofweek`-length candidate), K = 2 wins reproducibly at
both widths and on every run - +2.6% to +9.2% at AVX-512, +1.2% to +6.2% at
128-bit - and K = 4 adds no further, consistent benefit over K = 2 on either
width (the sign varies run to run, always within a few percent). So "K pays
only on the long chains" is confirmed rather than merely predicted, and the
planner version below should cap K at 2 rather than search further: 4 was
measured to buy nothing on the one shape where unrolling helped at all, while
still paying `GROUP_BUDGET`'s doubled cost over K = 2. This measurement is
also where a real methodology trap surfaced and was caught: comparing K = 1
(straight-line unrolled source, the shape a real emission carries) against an
earlier K = 2/4 written as a small constant-bound runtime loop over the op
index produced a spurious 30-60% *loss* at K = 4 - an artifact of the loop
shape, not of unrolling. Rewriting K > 1 as straight-line interleaved code,
matching K = 1's shape exactly, is what produced the numbers above (`SKILLS.md`
carries the general lesson).

Re-measured in forked JVMs after the harness debt closed (the results file's
header says how): the same picture. Depth 8 flat at both widths; depth 20
gains +4.4% from K = 2 at AVX-512 and +4.3% at 128-bit by min, and K = 4 over
K = 2 is +3.4% at one width and -1.9% at the other. The conclusion did not
depend on the harness, which is what a plain add/sub chain should show.

If a factor above 1 pays, the deliverable is the planner version: the emitter
already knows the DAG's live-temporary count per lane group, so K is chosen
per shape, and a shape whose live set fills the register file declines to
unroll. That version exists only because the loop is generated - it is the
whole reason this item belongs to Varka rather than to hand-written kernels.

Task 24 goes first because an unrolled body's remainder is `K * lanes - 1`
rows, so the tail question and the unroll question share a harness (open
questions 4 and 5) - and the batch-size knee sweep (question 6) rides the same
harness for the wide-shape case. Whatever the outcome, the `SKILLS.md`
unrolling bullet is rewritten with the numbers, as it promises itself.

### 2.4 Calendar extraction, `year` first (task 26, item 6)

The one vocabulary item that fits milestone 2's machinery as it stands: int32
lanes, existing operators, task 14's range-narrowed magic multiply (this file
cited task 17 for it until task 26 traced it; the technique shipped as task
14's follow-up, `PLAN_TASK_14.md` 7.7). The task
*opens* with its admission check, before any emitter work: each of 146097,
36524, 1461 and 153 needs its own range-narrowing argument - the value shrunk
until both `v * e < 2^k` and `v * M < 2^31` hold inside the low 32 bits `mul`
returns - or it has no vector lowering, because lanewise `DIV` scalarizes at
~9x. A constant that will not narrow changes the algorithm choice
(Neri-Schneider preferred, Cassels and Hinnant the alternates), and if none
survives for a given field, that field is declined with a task-16 reason
rather than shipped slow.

`year` first - it is the one extraction TPC-H uses (q7, q8, q9) and the whole
of what the headline corpus asks of this family - and `month` and
`dayofmonth` committed with it, by the owner's decision during planning: the
candidate algorithms are civil-from-days decompositions that produce all
three fields in one pass (Cassels' `(5 * d + 2) / 153` form exists precisely
to yield month and day), so once the admission check clears the constants,
the two extra functions are the same lowering rather than extra algorithm
work. `quarter` rides `month` as `(month + 2) / 3`, a division whose constant
narrows trivially; `dayofyear` and date-level `date_trunc` follow as the
algebra yields them. The corpus calibration stands and is not overridden:
`month(` and `quarter(` appear zero times in the benchmarks, so everything
past `year` is vocabulary completeness, taken because it is nearly free - not
because the corpus asks for it.

### 2.5 Boolean outputs (task 27, item 5)

Moved to `PLAN_MILESTONE_5.md` section 2.1 on 4 September 2026, when this
milestone was re-scoped to the date family and the emitter under it; the text
is there unchanged. The heading stays so citations of this section number
still resolve.

### 2.6 Lane-width conversion (task 28, item 1)

Moved to `PLAN_MILESTONE_5.md` section 2.2 on 4 September 2026, when this
milestone was re-scoped to the date family and the emitter under it; the text
is there unchanged. The heading stays so citations of this section number
still resolve.

### 2.7 int64 lanes: `TimestampNTZ` and `bigint` (task 29, item 2)

Moved to `PLAN_MILESTONE_5.md` section 2.3 on 4 September 2026, when this
milestone was re-scoped to the date family and the emitter under it; the text
is there unchanged. The heading stays so citations of this section number
still resolve.

### 2.8 ANSI-correct integer arithmetic (task 30, item 4)

Moved to `PLAN_MILESTONE_5.md` section 2.4 on 4 September 2026, when this
milestone was re-scoped to the date family and the emitter under it; the text
is there unchanged. The heading stays so citations of this section number
still resolve.

### 2.9 One decomposition, several fields (task 32, from the debt register)

Added after task 26 measured what its own design cost, which is the only
reason it is here: `SELECT year(d)` runs at 1797 M rows/s and
`SELECT year(d), month(d), dayofmonth(d), quarter(d)` at 435 - 4.1x for four
fields, which is near enough to 4x that nothing is being shared but the column
load and the loop control.

The cause is structural rather than accidental. All four fields fall out of one
civil-from-days decomposition, and ~45 of a field's ~51 vector ops are that
shared work; only the last handful differ. But `Year(col0)`, `Month(col0)`,
`DayOfMonth(col0)` and `Quarter(col0)` are four distinct IR nodes, each
emitting the whole decomposition before its own tail. The emitter's DAG-CSE
cannot help: it memoizes on structural equality between *nodes*, and the values
worth sharing here - era, century, year of century, day of year, the
March-based month - are not nodes at all. They are locals inside one node's
emitted bytecode, invisible to the walk that would share them.

**The task opens with the ceiling, not the mechanism.** A hand-written kernel
computing all four fields from one decomposition, against the 441 M rows/s the
four separate nodes reach, at both widths. That gate exists because task 17
already measured the opposite of the obvious answer: raising `GROUP_BUDGET` so
two outputs could keep their cross-output CSE in one method *lost*, 4119.9
against 2928.2 M rows/s in the current committed parity file, because the wider
method's register pressure cost more
than recomputing the shared ops. Here the shared work is ~45 ops rather than
eight, but five values would have to stay live across four output tails, so the
same effect is in play and the direction is not predictable from op counts. If
the ceiling is close to 441, the task is declined with a task-16 reason before
any IR changes - which is a real possible outcome, not a formality.

If it clears, three mechanisms, in the order they should be considered:

1. **A multi-value node and its selectors.** `ChronoFields(days)` computes the
   decomposition into slots and leaves nothing on the operand stack;
   `Year(fields)`, `Month(fields)` and the rest read one slot each. This is the
   general answer: any future primitive with several results - `divmod`, a
   string operation returning an offset and a length, date-level `date_trunc`
   beside `year` - takes the same shape. It is also the expensive one, because
   the IR's whole contract is that a node evaluates to exactly one value:
   `emitValue`, slot planning, the CSE memo, `canonicalShallow` and the line
   map, the shape hash, and a rule keeping a multi-value node out of value
   positions the way `Cond` is kept out of them today.
2. **Emitter-side fusion, with no IR change.** When one group holds two or more
   calendar nodes over the same child, emit the decomposition once and branch to
   the tails. Local and cheap, but it argues with task 26's own node weight,
   which deliberately puts each calendar output in its own loop method; the
   grouping would need the opposite rule for exactly this case, and the fused
   method lands near 60 ops across four outputs - the multi-output shape task 11
   measured the C2 compile cliff on.
3. **Decomposing into primitive IR nodes** so ordinary DAG-CSE shares them.
   **Declined in advance, with the reason on the record**: one `year` is ~51
   ops, so a four-field projection would be ~60 distinct nodes against
   `MAX_FUSED_NODES = 64` and a `GROUP_BUDGET` of 16, and the IR would acquire a
   general arithmetic vocabulary to serve one family.

Whatever the outcome, the deliverable includes sweeping the debt register entry
in the past tense with what the measurement found, per `sql/varka/AGENTS.md`.

**Outcome: the gate clears at AVX-512, and the task proceeds. Replanned in
`PLAN_TASK_32.md`, which supersedes this section.**

It was first answered the other way. A ceiling kernel was built
(`sql/varka/engine/.../vector/ChronoVectorOps.java`, differentially tested against
`java.time.LocalDate` in `ChronoVectorOpsTest`), measured 225.8 M rows/s against 430.7 for
the four emitted nodes, and task 32 was declined on that number. **The number was wrong, and
in a way worth recording**: the kernel factored its decomposition into a `computeFields`
helper returning a record of four `IntVector`s, that helper compiled to 376 bytecode bytes,
and C2's `FreqInlineSize` is 325 - so it never inlined into the loop, the record and its four
vectors could not be scalar-replaced, and the kernel really allocated five objects per lane
group. `emitChrono`, the path it exists to model, emits no call boundary at all. The kernel
was measuring the cost of a Java abstraction the emitted code does not have. `SKILLS.md`
carries the general lesson.

Rebuilt with the whole lane path written out by hand (`javap` and `-XX:+PrintInlining` both
confirm no call survives in the loop), with the narrow-range guard it had omitted, and writing
four destination validity buffers instead of one - so that both arms are charged the same
things. Measured in `VarkaEmitterParityBenchmark`'s "year" section, same 4096-row chunks and
the same `eachChunk` walk as the case it sits beside:

| | AVX-512 (M rows/s) | 128-bit (M rows/s) |
|---|---|---|
| four separate emitted nodes | 450.4, 448.8, 435.1, 445.7 | 154.1 to 157.6 over five runs |
| shared decomposition, hand-written | **692.4, 678.8, 661.7, 679.0** | 165.6 to 167.0, once 236.1 |
| ratio | **1.54x, 1.51x, 1.52x, 1.52x** | 1.06x, once 1.50x |

So sharing is worth about **1.5x at AVX-512**, reproducibly, and is a **wash at 128-bit** -
four of five runs at 1.06x, one at 1.50x, with zero stdev inside each run and 42% between
them. That bimodality is a compilation the JVM either finds or does not; C1 declines the
936-byte body outright ("out of virtual registers in linear scan") at both widths. Task 17's
register-pressure effect is real and visible here, but as a width-dependent ceiling on the
size of the win rather than as a reversal of its sign: 32 vector registers and 8 mask
registers hold five live intermediates and four outputs comfortably, 16 vector registers
holding masks as well do not.

Mechanism 3 (decomposing into primitive IR nodes) stays declined in advance for the reason
above. Mechanism 1 (a multi-value IR node) is also declined, on a reason this section did not
have: mechanisms 1 and 2 emit *identical bytes*, so the choice between them is engineering
cost, not throughput, and mechanism 2 - emitter-side sharing keyed on (fragment, child node) -
needs no IR change and generalizes to tasks 33, 34 and 40's nodes for free. `PLAN_TASK_32.md`
section 3 has the design; the default is not flipped on the AVX-512 number alone, since the
narrow-vector shape has to be measured on the emitted path first.

**Step B1 is built and on by default; step B2 is still gated.** The fragment mechanism ships
(`VarkaEmitOptions.shareChronoPrefix`, `FragmentKey`, `emitChronoPrefixOnce`) with the
grouping policy untouched, so every calendar output still gets its own loop method and no loop
body has anything to share. What it changes is the epilogue, which task 24 made one method
over *every* output: four fields over one date now decompose once there rather than four
times, and the 8000-byte `HugeMethodLimit` crossing moves from 17 calendar outputs to 40.
That is a compilability win on a shape a user can write, it needed no benchmark to justify -
which is why the default flipped here rather than waiting on B2 - and it moved no pinned
oracle and no committed number, the latter established by a test asserting every loop method
is byte for byte what it was rather than by a re-measurement. `PLAN_TASK_32.md` section 7.1
has the ladder and the two places the plan turned out to be wrong (the fragment key needs the
validity word, and a 16-field projection over one date does not exist). B2 - the grouping
relaxation that buys the 1.5x - cleared its gate at both widths (`PLAN_TASK_32.md` 7.2, 7.4,
7.5) and is planned in that file's section 10: a grouping clause that admits an output into
a wider method only when it reuses a fragment the method already computed - so task 17's
plain-chain case stays split - bounded by a `fusedCeiling` that a ladder past four fields sets.

### 2.10 `next_day`, as a handover experiment (task 33)

The smallest piece of vocabulary the survey after task 26 turned up, taken for
a reason that is not about vocabulary at all: it is the first task written to
be executed by a cheap agent rather than by whoever planned it, and it is
chosen because it is the one candidate where nothing has to be decided.

`next_day(d, <literal weekday>)` is `d + 1 + floorMod(k - d, 7)` for a
compile-time `k`, and every piece of that already exists - the mod-7 magic
multiply from task 14's follow-up, the unary null-intolerant node shape, the
literal-in-a-slot convention from `date_add`. About seventeen vector ops,
twelve of them already measured as `dayofweek`. There is no measurement to
take, no range to guard, no lowering to choose between.

The one trap is a trap in the opposite direction from the one `SKILLS.md`
records. `k - d` does overflow near `Integer.MIN_VALUE` - but Spark's own
`getNextDateForDayOfWeek` computes it in plain `int` arithmetic and wraps, so
byte-exactness requires reproducing the overflow rather than avoiding it. The
planning pass wrote the careful version first and checked it: reducing before
subtracting disagrees with the row engine on the bottom handful of int days for
every weekday, 28 cases in the boundary set. `dayofweek` is the reverse case,
because its oracle is `LocalDate`, which never wraps. Whose arithmetic the
oracle is decides which way the rule points, and that distinction is now in the
recipe because it is exactly what a cheap agent would get wrong.

`PLAN_TASK_33.md` is written as a step-by-step recipe - exact files,
exact switches, the oracle to write the test against, the two-step form of the
narrow-vector run that `JAVA_OPTS` silently gets wrong - and its outcome
section asks the executing agent to record which steps turned out to be
misleading. That record is the point of the experiment: whether a task of this
shape can be handed over, and what a recipe has to contain before it can be.

The corpus does not ask for `next_day` any more than it asked for `month`.
This task is not claiming otherwise; it is buying a measurement of the handover
itself, and picking the cheapest possible payload to buy it with.

### 2.11 The rest of the date-field family (tasks 34-37)

Four more expressions the survey after task 26 turned up, taken for the same
reason task 33 was: each is a **tail on a decomposition that already exists**,
so the whole of each task is one IR node, one case in `emitChrono`'s tail
switch, and its tests. They are separate tasks rather than one because they are
meant to go to separate agents, and because they are genuinely independent -
the only ordering is that 34 builds the leap flag 35, 36 and 37 all want.

| task | expression | lowering | size |
|---|---|---|---|
| 34 | `dayofyear` | `doy >= 306 ? doy - 305 : doy + 60 + L` | ~10 ops |
| 35 | `trunc(d, 'YEAR'\|'MONTH'\|'QUARTER')` | `d - dayofyear + 1`, `d - dom + 1`, and a four-way quarter-start select | ~5-15 ops |
| 36 | `last_day` | `d + length - dom`, length from the same linear form the day tail uses, February special-cased | ~12 ops |
| 37 | `weekofyear` | ISO-8601 by the Thursday rule (`SKILLS.md`, the `datealgo-rs` review): shift the day to its week's Thursday, `t = d + 3 - weekday0(d)`, then `week = (dayofyear(t) - 1) / 7 + 1` with task 34's January day-of-year computed over `t` rather than `d`. The ISO week-year is the Thursday's year and the week is the Thursday's ordinal day in sevens, so both year-boundary corrections and the weeks-in-year helper disappear by construction; `(dayofyear - 1) / 7` is an exact magic on 0..365. The weekday it starts from should be the reciprocal-bits form from the same review (3 ops, range-guarded), not today's 16-op fold | ~60 ops: weekday 3, the shift 2, the prefix and day-of-year tail over `t` ~51, the division 3 |

**Every formula above was verified during planning against `java.time` over
all 3,652,059 days of `0001-01-01..9999-12-31` - zero mismatches**, by
`plans/verify_chrono_tails.py`, which is committed beside the recipes so the
claim is re-runnable by whoever is asked to trust it. That check
is why they are worth handing over: the four recipes carry arithmetic that has
already been run, so the executing agent is transcribing rather than deriving.
It also earned its keep immediately - the first draft of `dayofyear` used 59
where the answer is 60, and failed on 84% of days.

Two things the four have in common, both written into the recipes. Their
oracles are all `LocalDate`, which is exact, so the ordinary no-overflow rule
applies - the opposite of task 33, where Spark's own arithmetic wraps and the
lowering has to wrap with it. And the leap flag they need is computed from the
*reported* year with two magic multiplies over a year biased by 13200, rather
than from `yoc` and `century` with bit tricks that go wrong at the century and
era boundaries.

The corpus asks for none of them. As with 33, that is said plainly rather than
argued around: what these buy is a second, wider trial of the handover - four
tasks, four agents, one of them (37) deliberately harder than the rest, and
four outcome sections recording where the recipes misled whoever ran them.

### 2.12 A day offset that is a column (task 38)

Not a new expression: `date_add(d, n)` and `d + n` already reach the compiler
as `DateAdd`, and the emitter's arm for it is already vector-vector lane math.
What declines them when `n` is a column rather than a literal is four guards,
three of which exist to enforce milestone 1's scope - "foldable integer day
offsets" - rather than to protect against anything the engine cannot do.

The finding that makes this worth a task is that foldability is the visible
guard and not the real one: **Varka cannot read a non-date column at all.** The
compiler's only leaf is a `DateType` `BoundReference`, and `isArrowBacked`
requires every referenced column to be an Arrow `DateDayVector`. So this task
is really the input boundary opening by one type, and the day offset is what
makes that concrete and testable.

Two things in it can produce wrong answers rather than declines, which is why
it is written as a recipe rather than left as a one-line note. `planWordRef`
aliases `AddDays`'s validity to the date child alone - correct while the offset
is always a literal, wrong the moment it can be a nullable column, and the fix
is `andRef` over both children, which is provably a no-op for a literal because
`andRef(a, WORD_ALL_TRUE)` returns `a`. And `DateAdd.inputTypes` accepts
`ShortType` and `ByteType` **without a cast**, so a short column would be read
by an int32 lane load as garbage; those must decline by naming `IntegerType`
exactly rather than by accepting any integral type.

Because no node type is added and the literal path is untouched, this is the
one task in the milestone whose acceptance includes **neither pinned value
moving and no committed number moving** - which also makes it the easiest to
review.

The corpus does not ask for this either. What argues for it is that the door it
opens is on the way to everywhere else: an `IntegerType` column is the first
non-date input the engine has ever read, and items 2, 3 and 4 all need that
boundary open before they can start.

### 2.13 `date - date`, the first mixed-width kernel (task 39)

Moved to `PLAN_MILESTONE_5.md` section 2.5 on 4 September 2026, when this
milestone was re-scoped to the date family and the emitter under it; the text
is there unchanged. The heading stays so citations of this section number
still resolve.

### 2.14 days-from-civil, and month arithmetic (task 40)

The headline of this task is not an expression. It is **days-from-civil** - the
inverse of task 26's decomposition - which `make_date`, `months_between`,
`date_trunc('QUARTER')` and interval month arithmetic all want, and none of
which has it. `date + INTERVAL n MONTH / YEAR` is what makes it concrete and
testable, and it comes with `add_months(d, n)` and `d - INTERVAL n MONTH` for
free: all three are the same node, and the subtraction arrives as a
`RuntimeReplaceable` the compiler already unwraps.

Notably this needs **none** of tasks 28, 29 or 30: a year-month interval is
physically a month count, so the whole thing is int32 and is available as soon
as 26 lands.

The investigation behind it turned up two things worth recording here rather
than only in the recipe.

**The inverse is cheaper than the forward direction.** Its divisions are by
400, 4, 100 and 5, all on small operands, so every one admits an *exact* magic
multiply with no correction step, where task 26's forward direction needed two
round-down magics with carries. Checked: the round trip is the identity over
all 3,652,059 days from year 1 to year 9999.

**The natural formulation of the month arithmetic does not work.** Folding the
year into a total month count and dividing by 12 puts the dividend near
400,000, far past the ~46341 bound an exact magic needs and past the ~160,000
that round-down plus one correction reaches. Keeping the dividend small - the
month index plus the offset, divided by 12, with the quotient added to the year
- makes it exact at `M = 43691, k = 19`, and bounds the literal the compiler
will accept at about two thousand years. The planning pass wrote the wrong one
first; `plans/verify_days_from_civil.py` is committed beside the recipe so the
right one can be re-run rather than trusted.

There is no vectorization-specific algorithm here to find, and the recipe says
so: Hinnant's `days_from_civil` and Neri-Schneider's optimized form are plain
branch-free integer arithmetic, which is exactly what makes them vectorize.
What is not avoidable is the decompose-adjust-recompose round trip, because the
clamp - 31 January plus one month is 28 or 29 February - needs the day of
month. About 90 ops, roughly twice `year`.

### 2.15 The two ends of the date-integer boundary (tasks 41, 42)

Both come out of the same sweep as tasks 34-40, and both are about the boundary
between a date and the integers it is made of rather than about calendar
arithmetic.

**Task 41, `unix_date` and `date_from_unix_date`**, is the smallest task in the
milestone and the only one that adds no IR node, no emitter code and no lane
arithmetic. Spark's implementation of each is `input.asInstanceOf[Int]` in
full: a date *is* a day count and these two only relabel the type. So the
lowering is two compiler arms that unwrap to the child, and the entry's output
type comes from the Catalyst expression as it already does. Neither pinned
value moves and no emitted bytes change.

The argument for it is not the functions, which nobody calls. It is that one
unsupported expression demotes a whole projection entry to the row path, so a
free relabel sitting in the middle of an otherwise fusible chain currently
blocks everything around it. The task's real test is a projection with one
ordinary entry and one relabelled one, which must fuse both.

**Task 42, `make_date`**, is the other direction and much the larger: three
integer columns in, a date out. It is the first expression to read three
integer columns - so it waits on task 38 - and the first whose result can be
**null for a non-null input**, which is what makes it worth a recipe.

Its three-way distinction is the thing an implementer will get wrong. A null
input is ordinary validity. An **invalid** date - month 13, 30 February - is a
*semantic* result: null in non-ANSI, an exception in ANSI. A year beyond what
the lowering's magic multiplies cover is an *engine limitation*, which declines
the batch in both modes and lets the row engine answer. Confusing the last two
gives wrong answers in one direction and spurious errors in the other. In ANSI
mode the invalid case also declines, because a lane cannot throw - the same
trick task 39 uses, and the reason this task needs no error machinery of its
own.

### 2.16 What `GROUP_BUDGET` does not bound (tasks 43, 44)

Both come out of the review of task 26, and both are the same discovery from
two sides: `GROUP_BUDGET` bounds one of the three method shapes the emitter
produces, and task 26's wide nodes made the other two visible. Neither is a
calendar problem - both would have arrived with any node worth more than a few
ops - so they are their own tasks rather than corrections to 26.

Both are **design tasks, not recipes**: each opens with a measurement whose
answer decides between three mechanisms, so neither is delegable the way tasks
33-42 are.

**Task 43: a loop method inside one output is unbounded.** `groupOutputs`
partitions *between* outputs and never inside one, so `GROUP_BUDGET` binds only
when the ops are spread across several. `CHRONO_WEIGHT` therefore separates
calendar nodes that are separate output roots and does nothing for calendar
nodes under one root. Measured on the emitter as it stands:
`CASE WHEN d < DATE '...' THEN year(d) ELSE month(d) END` is one root and emits
**one** loop method of 926 bytecode bytes; `least(greatest(year, month),
greatest(dayofmonth, quarter))` is one root and emits **one** method of 1672
bytes, roughly 190 vector ops. The budget's own javadoc records single-output
loops as healthy "at every width tried", and the width tried was 59 ops.

**Reconciling two numbers this file already carried.** Before task 43 measured
anything, the project stated the cost of compiling a wide vector loop in two
incompatible ways. `VarkaLoopEmitter`'s `GROUP_BUDGET` javadoc and `SKILLS.md`
say a 64-op loop's tier-4 compile took **~10 seconds**. Section 2.3 and the debt
register's item 13 say **~1 ms per vector op**, which at 64 ops is 64 ms. The two
differ by about 150x and had coexisted unremarked.

Task 43's ladder adjudicates: 1.1 ms per op at AVX-512 and 2.0 at 128-bit,
linear from 20 to 248 ops, which agrees with the per-op figure and not with the
ten seconds. And the ten seconds is most plausibly not ten seconds of compiler
*work*: `SKILLS.md`'s own account of it is that "fresh JVMs got it in during
warmup, busy ones did not", which describes a compile task **queueing** behind
others under load rather than one taking ten seconds to run. That reading keeps
what was actually observed - a loop running the C1 version with boxed vectors at
~1% speed until its compile lands, with the rate jumping 9 to ~1000 M rows/s at
t=12s - while dropping the inference that op count caused it. Under load, a
queued compile can bite at any op count, which is a scheduling property and not
something `GROUP_BUDGET` can bound.

The practical consequence is that `GROUP_BUDGET = 16` has no surviving
compile-time justification, and task 43's second half is therefore not 2.16's
"split, decline or accept" - all three assume a cliff to steer around - but
whether 16 is far too low. Task 32 step B2 already measured fusing calendar
fields as a win (1.29x, 1.57x, 1.80x at two, three and four fields), and at 1.1
ms per op a 200-op method costs about 220 ms once per shape per JVM. If the
budget rises, `CHRONO_WEIGHT` and `DAY_OF_YEAR_WEIGHT` are deleted rather than
re-tuned: they exist only to force calendar outputs apart under a 16-op budget.

**The development machine's AVX-512 is 256 bits wide**, measured after task 43
landed by running its own ladder at three widths: 128 -> 256 is 2.48x and
256 -> 512 is 0.95x, so doubling the lanes from 256 to 512 buys nothing here.
Every number in this milestone labelled AVX-512 is therefore a
256-bit-datapath number. No decision in it moves - each is a comparison between
two lowerings at one fixed width, with both arms on the same hardware - but the
label overstates what was tested, and a host with a full-width datapath (Intel
Sapphire Rapids or Emerald Rapids, AMD EPYC Turin) has roughly a factor of two
available on unmodified code. `SKILLS.md` carries the table and the method.

**Two corrections, measured while planning task 43 (`PLAN_TASK_43.md` 1.1).**
The second example no longer demonstrates the problem: on today's emitter
`least(greatest(year, month), greatest(dayofmonth, quarter))` is **61**
`IntVector` ops, not ~190, because task 32 step B1's fragment sharing collapses
its four calendar prefixes into one - four decompositions became one
decomposition and four tails. The figure was true when written. (`year` alone
still measures 39 ops, matching `PLAN_TASK_48.md`, so the emitter has not
drifted; the shape has.) And `HugeMethodLimit` is **not** what bounds a
single-output loop: measured across a 20-to-248-op ladder, `loopDense0` runs
from 287 to 1989 bytes, about 7.4 bytes per op, so it would take roughly 1050
ops to reach the 8000-byte refusal threshold. Whatever bounds a loop method
inside one output has to show up in compile time or register residency, not in
a size limit - which is what task 43 measures.

The task opens by finding out where that stops being true: single-output loops
at 60, 100, 150, 190 and 250 vector ops, measuring both steady-state throughput
and the time to reach it, which is the axis task 11 measured when it set the
budget. If there is a cliff, three mechanisms, and the choice is the task:
split inside an output (which the budget's javadoc rejects on register-residency
grounds, but rejected it without data past 59 ops); decline the shape at compile
time through `fitsBudgets`, which is honest and loses fusion for a
`CASE WHEN year ELSE month`; or accept it and record where the cliff sits so
the next wide node is weighed against a number.

**Task 44: the epilogue is one method over every output.** Task 24 decided that
deliberately - the epilogue runs one pass per batch, so the compile-time
argument behind `GROUP_BUDGET` does not apply to it - and that reasoning is
still right about compile *time* and silent about bytecode *size*. HotSpot
refuses to compile any method past `HugeMethodLimit`, 8000 bytes by default, so
past that the epilogue is not compiled by C1 or C2 at all and runs interpreted
with boxed vectors: the ~1% state the `GROUP_BUDGET` javadoc describes, on
every batch whose length is not a lane multiple.

Measured, by emitting the classes and reading the method: `epilogueMasked` is
7530 bytes at 16 calendar outputs and **8079 at 17** - so the limit is crossed
at seventeen, well inside `MAX_FUSED_NODES = 64`, and five date columns of four
fields is twenty. The same 32-output projection built from `date_add` instead
is 1811 bytes, so this is new with wide nodes rather than a standing property.

The task's own trap is that the benchmark cannot see this: every case in the
year section drives 4096-row chunks, so the epilogue's early return always
fires and the wide epilogue is never timed. That is exactly the lesson
`SKILLS.md` records from task 24 - a size ladder needs 4095 and 63 on it
deliberately - and getting the measurement to show the problem is half the
task. Then the mechanism: group the epilogue as the loops are grouped, bound it
by emitted bytes rather than op count, or decline the shape.

### 2.17 The validity write, which costs more than the arithmetic (tasks 45-47)

Added after task 32, and for the same reason 2.9 was added after task 26: a
measurement said something the design did not expect, and the number was worth
tasks.

Task 32's repaired ceiling kernel gives three AVX-512 points on one line -
`year` alone at 0.556 ns/row over ~50 vector ops, the shared four-field kernel
at 1.512 over ~65, and four independent nodes at 2.298 over ~200. Fitting them
puts the marginal cost of a vector op at about **0.0058 ns/row**, which makes
the shared kernel's entire civil-from-days decomposition worth about **0.38
ns/row of its 1.512**. The other **1.13 ns/row - three quarters of it - is
fixed per-lane-group cost**, and going from one output to four adds about 0.29
ns/row *per extra output*: at sixteen lanes that is ~4.6 ns per extra
store-and-validity pair per lane group, roughly eighteen cycles. A vector store
is a fraction of that.

The suspect is `VarkaVectorSupport.orValidityBitsAt`, and task 32 established
from `-XX:+PrintInlining` rather than from timings that it **does not inline**
inside a wide loop: 212 bytes, refused with `NodeCountInliningCutoff` on one
compilation and `callee is too large` on another, and neither
`-XX:CompileCommand=inline` nor `-XX:LiveNodeCountInliningCutoff` at 400000
lifts it. So each of the four calls per lane group is a real call doing bounds
checks, a four-arm switch on `groupBytes(lanes)` and a read-modify-write.

**This was a three-point fit, not a measurement, and task 32's step B2 gate
settled it** (`PLAN_TASK_32.md` section 7.2, finding 2):
`ChronoVectorOps.vectorFourFieldsNoValidity` is the same arithmetic and the
same guard with every validity buffer and every `orValidityBitsAt` call
removed, and across three runs it costs 0.65-0.67 ns/row against the
validity-carrying kernel's 1.50-1.52 - **55.6% to 56.7% of the ceiling
kernel's time is the validity write**, not the three quarters the fit
estimated (that number priced everything the decomposition does not touch, of
which validity is the majority but not all). The fit's direction was right
and tasks 45-47 are confirmed worth more than task 32's own mechanism on this
number alone.

Three tasks, in this order, because each may shrink the next:

* **Task 45, the null-free fast path. DONE** (`PLAN_TASK_45.md` 11), and it was
  worth more than this section expected. The driver sets a dense batch's value
  outputs valid once with `VarkaVectorSupport.setValid`; the loop's per-lane-group
  `orValidityBitsAt` is not emitted. Measured as an A/B in one run on the tree
  merged with task 53: the shared four-field kernel goes **821.3 to 1531.0 M
  rows/s at AVX-512 (+86%)** and **282.9 to 705.0 at 128-bit (+149%)**, `year`
  +26% and +41%, `dayofweek` +12% and +46%. The four-field shape now beats the
  hand-written ceiling it was chasing by 2.3x. The forecast below that 45 "does nothing for the masked one"
  held exactly: both mixed-null rows sit within 2% at both widths, which is what
  the byte-identity assertion on the masked bodies promised. The gain is larger
  at 128-bit for every shape, which is the argument this section makes for task
  46 - now measured rather than reasoned, and applying to 45 first.

  The original description follows. Arrow permits an output vector with
  `null_count == 0` to carry no validity buffer at all, and the driver already
  knows `srcNullCount == 0` when it dispatches to the dense body. Today the
  dense path still loads `-1L` per lane group and calls `orValidityBitsAt`
  anyway (`emitLaneGroup`, the `loadConstant(-1L)` beside
  `invokestatic(SUPPORT, orValidityBits(s), ...)`). One fill in the driver -
  which already walks the buffer once to zero it - replaces every one of those
  calls on the shape most real queries take. The masked path is untouched.
* **Task 46, validity helpers that can inline.** The emitter already chooses
  the helper by *name* at emit time (`validityBits`, `orValidityBits`, which
  select the partial variants for the epilogue), and it knows the species, so
  the width can join the name: `orValidityBitsAt16` and its siblings, each about
  thirty bytes with the switch already resolved, under `MaxInlineSize` and
  therefore inlinable whether or not the call site is judged hot. The current
  helper cannot fold its switch because it cannot inline, and cannot inline
  because it has not folded; naming the width breaks that cycle. This one is
  generic - every Varka kernel calls these helpers, not just the calendar ones -
  which is both its value and the reason it needs the whole suite green at both
  widths rather than a calendar-shaped argument.
* **Task 47, one validity write per word instead of per lane group.** At sixteen
  lanes a lane group covers sixteen rows and touches two bytes; four lane groups
  fill one 64-bit word. Accumulating the bits in a register and storing once per
  64 rows turns four read-modify-writes into one store, and removes the
  read entirely. It is the largest change of the three - the loop grows a
  second, coarser stride, and the epilogue has to flush a partial accumulator -
  so it goes last, and only if 45 and 46 leave something on the table.

Task 45 was expected to make the null-free case nearly free and do nothing for
the masked one - the second half held exactly and the first half is a large win
rather than "nearly free": the four-field shape's remaining 1531.0 M rows/s
still carries the decomposition, the stores and the loop control. 46 to pay
across the board and most at narrow widths, where
lane groups are smallest and the per-group cost is amortised over four rows
instead of sixteen; 47 to pay only on the masked path once 45 has taken the
dense one away. All three are measured on `VarkaEmitterParityBenchmark`'s
existing cases rather than new ones, because the point is what they do to
kernels that already exist.

### 2.18 A `year` that does not compute the month (task 48)

`emitChrono`'s year tail needs one bit out of the March-based month: whether
the March year has turned January, which is `mp >= 10`. But `mp` is
`(5 * doy + 2) / 153`, so `mp >= 10` is exactly `doy >= 306` - integer
arithmetic, no approximation, and `doy` is already in a local when the tail
runs. So `year` alone never needs the month step at all: one compare replaces a
multiply, an add, a magic multiply and a shift.

It is worth its own task rather than a line in another because of which shape
it helps. `year` alone is what TPC-H q7, q8 and q9 run, and it is the only
calendar extraction the headline corpus asks for; it is also the case task 26
measured at 1797 M rows/s and the one every later calendar task is compared
against. Four or five ops off a ~50-op body is a few percent, which is inside
the noise of a single run and therefore has to be measured on the
interleaved-A/B, compare-by-minimums methodology rather than asserted.

It does **not** help a shared prefix: if task 32's step B lands, the prefix
computes `mp` for the month, day-of-month and quarter tails regardless, and
`year` reading `doy >= 306` instead saves one op rather than five. So this task
is about the single-output path, it is independent of task 32 either way, and
whichever of the two lands second inherits the smaller half of the win.

**Update, after step B1 landed first**: read literally, that leaves this task the one-op
half. `PLAN_TASK_48.md` does not accept it: the prefix's month step is dead work exactly
when no consumer of that prefix reads `marchMonth`, and the emitter knows its consumers
per body at plan time, so the step becomes conditional on them - the full five-op win for
a `year`-only kernel, correctly nothing for `year(d), month(d)` in one method, and for free
to `dayofyear` and `trunc(d, 'YEAR')`, which test `doy >= 306` themselves. The identity is
proved in that plan's section 2 and asserted over all 366 values rather than stated.

### 2.19 Exact civil-from-days in long lanes (task 49)

Moved to `PLAN_MILESTONE_5.md` section 2.6 on 4 September 2026, when this
milestone was re-scoped to the date family and the emitter under it; the text
is there unchanged. The heading stays so citations of this section number
still resolve.

### 2.20 Making a bad register allocation visible (task 50)

Task 32 spent six failed hypotheses on a kernel that ran at either 165 or 236 M
rows/s under `-XX:MaxVectorSize=16` - stdev 0 inside a run, 42% between runs -
before the cause turned out to be C2's register allocator. The two compilations
contain *identical* vector op counts; the whole difference is spill traffic,
four stack moves against seventy-four. The allocator sometimes finds a clean
assignment for a body that sits at the edge of the 16-register xmm file and
sometimes does not, from the same IR. `SKILLS.md` has the evidence.

The structural answer is task 32's own: do not put four outputs in one loop
method at a width whose register file cannot hold them, which is what the
`shareChronoPrefix` decision becomes once its default is made width-dependent.
This task is the other half - **not preventing it, but noticing it** - because
today a badly-allocated kernel is completely invisible. It costs 30 to 40% and
nothing anywhere reports that it happened.

**It is observable with public API.** JFR's `jdk.Compilation` event carries
`method`, `compileLevel`, `isOsr` and `codeSize`, and
`jdk.jfr.consumer.RecordingStream` (public since JDK 14) can consume those
events in-process with no agent and no diagnostic flags. The fast and slow
allocations of the same kernel differ by about 2x in compiled size - 1581
instructions against 3000 - so the anomaly is plainly present in that one field.

**The expectation is self-calibrating, which is what makes this worth building.**
The obvious design is a committed table of expected sizes per shape, and it is
the wrong one: it has to come from somewhere and it drifts every time the
emitter changes. Varka already keys every kernel by a shape hash, and the same
shape emits byte-identical bytecode, so the comparison is between *compilations
of the same shape hash* rather than against any constant. The first compilation
of a shape establishes the size; a later one that differs materially is the
report. No table, no drift, and it gets more accurate the longer a JVM lives.

Scope, deliberately narrow:

* A `RecordingStream` subscribed to `jdk.Compilation`, filtered to Varka's
  generated kernel classes, with OSR compilations excluded - they are not what
  the steady-state path runs and task 32 found them identical across both modes
  anyway.
* Per shape hash, the first non-OSR `codeSize` seen, and a metric plus a debug
  log when a later one for the same hash differs by more than a threshold the
  task picks from measured data rather than guessing.
* Off unless enabled, through Varka's own configuration surface. Subscribing to
  `jdk.Compilation` is not free and this is a diagnostic, not a feature, so it
  should cost exactly nothing when nobody has asked for it.
* **A diagnostic and never a control loop.** Section 9's debt entry records the
  detect-and-re-emit idea and why it is not being built.

Risks worth stating: JFR may be unavailable or disabled in a deployment, in
which case this reports nothing and must degrade silently; the stream costs a
thread; and a shape whose kernel is only ever compiled once in a JVM produces no
comparison at all, which is the common case for a short query and means this
mostly serves long-lived sessions.

**Correction, made while planning (`PLAN_TASK_50.md` 2.1): "per shape hash" is
too coarse a key.** A generated kernel is not one method - task 24 deliberately
split it into siblings, so one shape emits `run`, `runDense`, `runMasked`,
`loopDense<g>`, `loopMasked<g>`, `epilogueDense` and `epilogueMasked`, whose
compiled sizes differ from each other by an order of magnitude. Keyed on the
shape alone, the *second method* compiled for a shape is compared against the
first and reported as a divergence, so the detector would fire constantly on a
perfectly healthy JVM. The key is `(shape hash, method name, compile level)`:
two compilations sharing it are compiling identical bytecode at the same tier,
so any size difference between them is the allocator's doing and nobody
else's.

### 2.21 Remove the per-extraction range guard (task 51)

Every calendar extraction (`Year`, `Month`, `DayOfMonth`, `Quarter`,
`DayOfYear`, `LastDay`, `AddMonths`) has carried a per-lane range check since
task 26: two compares against `VarkaChrono.NARROW_MIN_DAYS`/`NARROW_MAX_DAYS`,
ANDed with validity and the epilogue's bounds mask, ORed into a body-wide
accumulator that declines the whole batch to the row engine if any lane's day
fell outside the range the narrowed civil-from-days lowering is proven exact
over. The guard was correct and load-bearing when it shipped.

The owner's objection, raised while reviewing task 36's own copy of the same
guard: the check re-verifies a fact at every calendar extraction that reads a
given value, when CSE and task 32's fragment sharing already prove the *same*
value's range once, for every field read off it in the same query. A query
extracting `year`, `month` and `last_day` from one column pays the guard once
today, not three times, because the fragment sharing already collapses the
extractions onto one shared prefix - but a query with a hundred *different*
calendar expressions, none of them CSE-equal, still pays it a hundred times for
what is, in the cases that matter, the same underlying guarantee: the day came
from a column, which the project's own contract already promises is
`[0001, 9999]`, or from arithmetic the compiler already bounded (`add_months`'s
literal-month-count check, task 40).

That argument does not cover every case. `date_add`/`date_sub` with a *column*
offset (task 38) can push a day arbitrarily far from any bound using a runtime
value the compiler cannot see - the value did not cross the Spark boundary as
data, Varka's own `AddDays`/`SubDays` node manufactured it. A guard at the
extraction is not redundant for that value; it is the only place a check has
ever existed for it, since the arithmetic node that created the value carries
no check of its own. So the guard's job splits into two real questions -
"is this a fact already established elsewhere" (the case for removing the
extraction-side check) and "who established it, and where" (the case for a
narrower check somewhere else) - and the owner's ruling was to act on the first
now and treat the second as its own task: **remove the guard, then add it back
only at the nodes that can actually manufacture an out-of-range day**, tracked
as task 52.

**What changed.** `hasChrono` (whose only caller decided whether a body
allocated a guard accumulator) is deleted. `emitEra` no longer emits the two
compares, the validity/epilogue-mask ANDing, or the OR into `s.guardAcc`; it is
now only the day-of-era arithmetic, unconditionally. `s.guardAcc` is therefore
always null today, and `emitStatusReturn` - already written to return a
constant zero whenever nothing set a guard - needed no change at all to do the
right thing. The `int run` ABI, `STATUS_CHRONO_RANGE`, the evaluator's fallback
routing and its metric all stay: nothing sets the bit today, but task 52 is the
next task and would need every piece of this back immediately, so leaving it
is not speculative scaffolding, it is scoped, already-planned reuse.

**What this costs, honestly.** `VarkaChrono.narrowed` is unchanged and is
still undefined - not merely inaccurate - outside `NARROW_MIN_DAYS`..
`NARROW_MAX_DAYS`. Before this task, a day outside that range was declined to
the row engine; after it, the same day is computed anyway and can produce a
wrong year, month, day or quarter with no signal above debug logging. This is
a real, temporary violation of the ghost-fallback contract in
`sql/varka/AGENTS.md` ("a Varka failure degrades to the row engine and never
returns a wrong answer"), accepted deliberately by the owner rather than found
and fixed, and it stays open until task 52 lands a check at the nodes that can
actually produce such a day. Two differential tests that asserted the old
decline behaviour end to end were removed rather than rewritten to assert the
new, weaker one (`VarkaDifferentialSuite.scala`, see the test file for the
pointer back here); two unit tests that checked the same thing at the emitter
level were rewritten to assert that an out-of-range day is now computed, not
declined (`VarkaLoopEmitterSuite.scala`).

**No emitted byte moves for an in-range shape.** The guard's removal deletes
code, it does not change the arithmetic any calendar node runs on an in-range
day, so neither pinned fixture (`VarkaLoopEmitterSuite`'s line map,
`VarkaShapeCacheSuite`'s shape hash) moves, and no committed parity number is
expected to change - a body that used to also compute a guard mask now simply
does not, which can only help, and is not itself a claim this task measures.

### 2.22 Guard at the producer, not the extraction (task 52)

**Update, task 52 done (`PLAN_TASK_52.md` section 10).** Built as described
below, with two refinements the build found: the analysis works in absolute
epoch-day intervals from the column contract (`VarkaChrono.CONTRACT_MIN/MAX_DAYS`)
rather than in shifts, and a date-typed calendar output (`last_day`,
`add_months`) is not treated as back in range - it carries the child's
interval plus its own bound, since its input was checked at its own arm and
its output can be up to 30 days (31 per month) past it. The runtime guard is
`VarkaEmitOptions.guardDayProducers`, default on; section 10.5 of the task file
has the number behind that default. Row 38, whose column offsets this task's
runtime half rests on, is marked done here too (PR #62 had landed without the
marker).

The other half of 2.21's ruling. Where task 26's guard checked
every calendar *extraction's* input, this task checks the *producer* nodes
that can put a day outside `VarkaChrono`'s narrowed range using a value the
compiler cannot bound at compile time - today, that is exactly `AddDays`/
`SubDays` when the offset is a column rather than a literal (task 38's
day-offset support). **Second version of the plan:** a literal offset is not
bounded either - `foldDaysOffset` accepts any `Int`, so
`year(date_add(d, 20000000))` fuses today - and the task now opens with a
compile-time day-shift interval analysis that declines such an entry for free,
with the runtime guard reserved for the genuinely unbounded column-offset case.
`PLAN_TASK_52.md` section 1 has the rule. `NextDay`'s own offset is not in this set even though it
takes the same `(days, offset)` shape: task 33's compiler arm accepts only a
foldable weekday and always compiles it to a `LiteralSlot`, and floorMod7's
result is bounded to `[0, 6]`, so `NextDay` cannot move a day far enough to
matter and needs no guard of its own. `add_months`'s literal month count is
already bounded at compile time too (task 40's `MONTH_ARITH_MIN_MONTHS`/
`MONTH_ARITH_MAX_MONTHS` decline), so it needs no runtime check under this
scheme either; a bare `ColumnRef` needs none, under the project's standing
contract that column data is `[0001, 9999]` at the Spark boundary. A downstream
calendar extraction trusts whatever its input already established instead of
re-checking it - which is exactly task 51's removal, now paired with a check
that actually covers the gap it opened.

**Shape.** Reuses task 51's still-live plumbing: `s.guardAcc`,
`emitStatusReturn`'s zero-vs-`STATUS_CHRONO_RANGE` return, and the evaluator's
existing fallback route and metric. The new work is entirely in deciding
*which* nodes set the accumulator - the column-offset arithmetic nodes, not
the calendar extractions - and only when their offset operand is not a
literal the compiler already bounded.

**Behind a flag, off until measured.** Every column-offset `date_add`/
`date_sub`/`next_day` pays this whether or not a calendar extraction ever
reads its result, which is a different cost shape than task 26's guard (paid
per calendar output, shared by fragment sharing) - it needs its own number
before it is the default, the way task 32's `shareChronoPrefix` and task 49's
long-lane lowering each earned their default from a measurement rather than an
argument. A `VarkaEmitOptions` switch, default off, is where task 52 starts;
the owner picks the default from the number the way every other guard-shaped
decision in this milestone has been decided.

**Validation.** A differential shaped like the two task 51 removed - a
column-offset `date_add` pushing a date past the range, checked end to end
through both the projection and filter paths - but anchored on the producer
node rather than the extraction, since that is now where the check lives. Both
flag settings green; a committed number for the guard's cost isolates what it
adds on top of the arithmetic it protects.

### 2.23 `date + INTERVAL n DAY` with a column interval (task 56)

The analyzer resolves `date + INTERVAL n DAY` two ways. A literal interval
folds to `date_add(d, n)` before the compiler sees it, and is covered. A
column interval becomes `DateAdd(d, ExtractANSIIntervalDays(col))`, and
`ExtractANSIIntervalDays` has no arm, so the whole entry declines through the
"day offset is not a foldable literal or an integer column" reason task 38
pinned. A `DayTimeIntervalType(DAY)` value is physically **int64
microseconds**, which is why this is not simply another leaf: the kernel has
no int64 lane to read it into, and the division by 86400000000 has to happen
before any narrowing to int32.

The task takes the half of it a date lane can express and names the other
half honestly. **The rewrite.** When the interval is itself a cast of an
`IntegerType` column - `d + CAST(i AS INTERVAL DAY)`, the spelling the
differential suite already uses, and `d + i * INTERVAL '1' DAY` where the
optimizer folds the multiply into the same cast - the compiler sees
`ExtractANSIIntervalDays(Cast(i, DayTimeIntervalType(DAY)))` and the day
count is `i` itself: the cast multiplies by the day's micros and the extractor
divides them back out, both exact for every int. The arm unwraps the pair to
`compileOffset(i)`, task 41's pattern of retiring an expression onto existing
IR, and the result is task 38's `AddDays(d, ColumnRef)` with everything that
already comes with it - the AND of the two validity words, task 52's producer
guard under a calendar consumer. **The decline, by decision.** A stored
`INTERVAL DAY` column, or an interval computed from anything but an int cast,
keeps declining, with its own reason ("day interval column, not an int cast").
The owner scoped this task to the date lane alone: the int64 column is not
read by a kernel, neither through milestone 5's width machinery nor through a
per-row narrowing in the evaluator, unless a query is one day found to need it,
at which point it is argued in on its own - the corpus writes literal
intervals, which fold, and int-cast intervals, which this task covers.

Validation: the rewrite's shape pinned in the compiler suite (`AddDays(col,
col)` from the cast form, literal slots untouched); a differential over
`d + CAST(i AS INTERVAL DAY)` and `d - CAST(i AS INTERVAL DAY)` against the row
engine with nulls on both sides; the stored-interval column still residual,
with its reason in `EXPLAIN`. No new node, so neither pinned fixture moves and
no committed number changes.

### 2.24 `extract(DAYOFWEEK_ISO)` and `DOW_ISO` (task 57)

`Extract` resolves the two spellings to `Add(WeekDay(d), Literal(1))`
(`datetimeExpressions.scala`, the `DAYOFWEEK_ISO` arm): Monday 1 to Sunday 7.
`WeekDay` compiles; the integer `Add` on top of a non-date value does not, on
purpose - a general int-arithmetic arm would admit `datediff(a, b) + 1`, whose
overflow semantics are milestone 5's task 30. This value cannot overflow: the
addend is a constant one over a result in 0..6.

So the task is one narrow arm, not a general one: `Add(WeekDay(child),
Literal(1, IntegerType))`, in either operand order, compiles to a new
`DayOfWeekIso(child)` node whose tail is `WeekDay`'s (`emitFloorMod7` on the
`(v + 3) mod 7` form) plus one lanewise add - the same one op that separates
`dayofweek` from `weekday` today. A user writing `weekday(d) + 1` by hand hits
the same arm and gets the same bytes, which is correct, since it is the same
function. The node joins `weightOf` at `NEXT_DAY_WEIGHT`'s scale (a floorMod7
tail), `tailReadsMarchMonth` is not involved (no prefix), and both pinned
fixtures move once for the new node type, re-pinned from their failing output.

Validation: the reference evaluator's arm is `DateTimeUtils.getWeekDay(d) + 1`
- the definition, not the lowering; the boundary matrix over a whole week at
both widths under the three `FloorMod7` variants; a differential with
`extract(DAYOFWEEK_ISO FROM d)`, `date_part('DOW_ISO', d)` and `weekday(d) + 1`
in one query against every null pattern; `dayofweek` and `weekday` byte
for byte unchanged.

### 2.25 `extract(YEAROFWEEK)` (task 58)

The ISO week-numbering year: `DateTimeUtils.getWeekBasedYear`, i.e.
`LocalDate.get(IsoFields.WEEK_BASED_YEAR)` - 2004 for 2005-01-02, 2021 for
2020-12-31 in a year whose last days belong to week 1. There is no registered
function; only `extract(YEAROFWEEK FROM d)` and `date_part` reach it, and the
corpus never does, so this is completeness - and it is nearly free once task
37 has landed, which is why it is here.

The definition has the same shape as task 37's Thursday rule: the ISO year of
a day is the calendar year of the Thursday of its ISO week, `t = d + 3 -
weekday0(d)` with `weekday0` Monday-based, and `yearofweek(d) = year(t)`.
Task 37 builds the Thursday shift as the day its own prefix runs over; this
task composes `Year` over that same shifted day. The composition is decided
by what 37 ships: if 37 exposes the shift as an IR node, this is a compiler
arm building `Year(ThursdayOf(d))` and nothing in the emitter; if 37 keeps
the shift inside its own tail, this task lifts it out into that node and 37's
`WeekOfYear` tail reads it too, so the two nodes over one date share it. Task
52's `dayRange` needs an arm for the shift, `[-3, +3]` days (task 37's
admission check; this paragraph first said `[-6, +3]`), so `Year` over it is
admitted at compile time. No new arithmetic, no new constant, no leap
question: `year` of a computed day is the prefix the family already has.

Validation: the boundary rows are the ones the ISO year moves on - December 28
to January 4 of years whose week 1 starts in the old year (2004/2005,
2020/2021, 2026/2027) and of years where it does not, plus the century years -
against `DateTimeUtils.getWeekBasedYear`; a differential with `weekofyear`,
`yearofweek` and `year` over the same column, since the three disagree on
exactly the rows that matter; both widths.

### 2.26 `next_day` with a weekday column (task 59)

Task 33 covers `next_day(d, 'MON')`: the weekday resolves at compile time and
travels as the literal `k = dayOfWeek - 1`. A weekday **column** declines,
because the kernel cannot read a string lane and the row engine's parse -
`DateTimeUtils.getDayOfWeekFromString`, case-insensitive, three spellings per
day, null or an error for anything else - is per row. This task adds the one
mechanism that lets a string-argument date function run in the kernel without
string lanes, and it is worth having because task 61 reuses it.

**The derived leaf.** The evaluator computes, per batch and before the kernel
runs, an int32 column the kernel then reads like any other input: here
`k[i] = getDayOfWeekFromString(name[i]) - 1`, null where the name is null or
unrecognised in the non-ANSI mode. It is the row engine's own parser applied
to one column, so its semantics are the definition's by construction, and it
is the same shape as the validity bitmap the evaluator already synthesises
for a dense batch. In ANSI mode an unrecognised name must throw, and the
pre-pass does exactly what the row engine does - calls the same function,
which raises the same error at the same row - so ANSI needs no decline; it is
the one place where a kernel-side value can throw *correctly*, because the
throw happens before the kernel. The IR sees `NextDay(days, ColumnRef(k))`:
`requireLiteralOffset` on `NextDay` widens to `requireOffsetShape` the way
task 38 widened `AddDays`, the lowering is unchanged - `floorMod7(k - d)` is
lanewise whether `k` is a broadcast or a vector - and the node's validity word
becomes the AND of the two inputs' words through `andRef`, so a null weekday
nulls its row.

**The number that decides it.** The pre-pass is a scalar parse per row, the
kind of cost the kernel exists to remove, and the date arithmetic behind it is
cheap; the honest prediction is that fusing `next_day(d, col)` wins little over
the row engine when the column is a string, and a lot when the *same* derived
column feeds several fused expressions. Measured in `VarkaEmitterParityBenchmark`
against the row path; if the fused form does not beat the row engine by at
least 1.3x on a single `next_day`, the mechanism still ships (task 61 needs
it) and the entry records that its value is in reuse, not in this shape.

Validation: the derived leaf's null and error rules pinned in the evaluator
suite (null name, `'monday'`, `'MO'`, `'xyz'` under both ANSI settings, the
ANSI error identical to the row engine's); the emitter's two-column `NextDay`
over every null pattern at both widths; a differential over a weekday column
mixing spellings and nulls; the literal form byte for byte unchanged.

### 2.27 `add_months` with a month-count column (task 60)

Task 40 covers `add_months(d, 12)` and `d + INTERVAL n MONTH` with a literal
count, bounded at compile time to `MONTH_ARITH_MIN/MAX_MONTHS` because the
magic division by 12 is exact only there. A month-count **column** declines
("month count is not a foldable literal"). The column form is task 38's story
again, with task 52's mechanism attached: the count becomes a `ColumnRef`
operand, `requireLiteralOffset` on `AddMonths` widens to `requireOffsetShape`,
the validity word becomes the AND of both inputs, and the lowering does not
change - the numerator `(month - 1) + months + BIAS` is lanewise either way.

What changes is where the bound is checked. At compile time nothing bounds a
column, so the check moves to run time, and it is exactly the producer guard
task 52 built for `date_add(d, off)`: two compares on the month-count lanes
against `MONTH_ARITH_MIN/MAX_MONTHS`, ANDed with the node's validity word and
the epilogue mask, ORed into `s.guardAcc`, and `STATUS_CHRONO_RANGE` declines
the batch to the row engine when any lane is out. Task 52's `dayRange` gives
`AddMonths` with a column count the `ColumnShifted` answer, so a calendar node
over it is admitted and the guard is what protects the decomposition; the
guard on the count is what protects the month magic. Both sit behind
`guardDayProducers`, which is already on.

Validation: the compiler shape (`AddMonths(col, col)`) and the literal form
unchanged; the emitter's guard declining a batch with one lane past the bound
in a loop lane and an epilogue lane, not under a null count, and computing an
in-range batch at both widths; the differential with in-range and
out-of-range counts and nulls, the declined metric firing only for the latter;
the cost of the count guard measured on `add_months(d, m)` in the parity
benchmark beside task 52's row.

### 2.28 `trunc` with a format column (task 61)

Task 35 covers `trunc(d, 'MONTH')`: the level resolves at compile time and
selects which code is emitted, which is why it is a shape-bearing field. A
format **column** makes the level a per-row value, and the lowering has to
change shape rather than gain an operand: the code for every level has to be
present and the row picks. This is the last of the three string-argument
functions and the one the corpus is least likely to write; it is here because
task 59's derived leaf makes it cheap to finish the family.

**The derived leaf, again.** The evaluator's pre-pass maps the format column
through `DateTimeUtils.parseTruncLevel` to an int32 level column: the four date
levels as small codes, and a null where the format is null, unrecognised or
below a day - which is exactly the row engine's result for those rows, a NULL,
so the derived column's validity *is* the output's. **The node.**
`TruncDateDynamic(days, levelRef)` is a chrono node whose tail computes the
subtract form's three date-level results off one prefix - `MONTH` two ops,
`YEAR` and `QUARTER` sharing the leap flag and the January day of year - and
the `WEEK` result through the `next_day` arithmetic task 33 owns, then selects
per lane with three blends on the level lanes. Roughly the cost of all four
levels at once, paid per row, which is the price of not knowing the level at
compile time; the literal form stays the shape a query should write and the
doc says so. `tailReadsMarchMonth` answers yes, `dayRange` treats the output
like `TruncDate`'s, and the node takes the widest of the level weights.

Validation: the reference evaluator's arm is `DateTimeUtils.truncDate` per row
over the parsed level, null where the parse fails; the boundary matrix with the
level column cycling through all four levels and the invalid codes at both
widths; a differential over a format column mixing `'YEAR'`, `'mm'`,
`'QUARTER'`, `'week'`, `'HOUR'`, `'QTR'` and null, whose answers are the row
engine's including the NULL rows; the literal `trunc` byte for byte unchanged.

### 2.29 The closing measurement: every date expression against stock Spark (task 62)

Every number this milestone has committed is a comparison the project ran
against itself, on one laptop whose AVX-512 is issued through a 256-bit
datapath (`SKILLS.md`, "This machine's AVX-512 is 256 bits wide"), against a
Janino baseline that is this fork with Varka switched off. That is the right
instrument for choosing between two lowerings; it is not the number a reader
of the README wants, which is how much faster a date expression runs on Varka
than on the Spark they have today, on hardware that can use the vector width
Varka emits. Task 62 produces that number, and is the milestone's last task
because it measures everything the milestone added. Its audience is outside
this repository: the owner intends to present Varka publicly once the
milestone closes, and the table and the reproduction guide this task writes
are what a blog post quotes and what a reader who has never seen this tree
follows - which is why the baseline is stock Spark, why every step is spelled
out, and why the losses are printed beside the wins.

**What is measured.** Every date expression the compiler covers when the task
starts - the survey of section 2.23's neighbourhood is the list: `date_add`,
`date_sub`, `datediff`, the six extractions and `dayofweek`/`weekday`, `next_day`,
`last_day`, `add_months`, `trunc` at its four levels, `unix_date` and
`date_from_unix_date`, `make_date`, `weekofyear`, the two ISO fields, and the
predicate forms (comparisons, `BETWEEN`, `IN`, `CASE WHEN`, `coalesce`,
`greatest`/`least`, `IS NULL`) - each as one SQL query over one Arrow-cached
date table, in the two shapes the engine distinguishes: a projection read by a
columnar consumer and a filter counted. One row per query in the results,
nothing synthetic: the query text is committed beside the number, so a reader
can paste it into a `spark-sql` shell.

**Three distributions, one driver.** The baseline is **stock Apache Spark**,
the release this fork tracks, not this fork with Varka off - the two differ
by the Arrow cache serializer and whatever else the fork carries, and the
README should not have to explain that away. It runs twice: on **JDK 17**,
Spark's default runtime, and on **JDK 25**, so the reader can separate what
the JDK gives Janino from what Varka gives on top. Varka runs on JDK 25, the
only JDK it builds on (the Class-File and Vector APIs). The driver is a small
Java application under `sql/varka/bench` (Java, per the house rule), submitted
to each distribution with `spark-submit`: it builds the table at a fixed row
count and seed, warms each query, runs it for at least five iterations over
two-second windows, and writes one results file per distribution with the
provenance block `dev/varka_bench_regen.sh` already writes - git SHA, JDK,
kernel, CPU model and flags, `MaxVectorSize` as the JVM reports it, governor,
load. A shell driver, `dev/varka_bench_surface.sh`, runs the three in
sequence on an idle machine and hands the three files to
`dev/varka_bench_diff.py`, whose output is the README's table.

**The machine, through the benchmark workflow.** The run is a dispatch of
`.github/workflows/benchmark.yml`, not a machine of ours: the workflow already
takes the JDK, extra driver JVM options, and an `expected-cpu` model that fails
the job early when the runner's CPU is not the one asked for, which is how a
required CPU is picked on hosted runners - dispatch, and re-dispatch until the
pool hands out the model. The model to pin is an Intel Xeon with a full-width
512-bit unit (the hosted pool's Xeon Platinum 8370C and 8272CL both qualify;
the pool's AMD EPYC 7763 has no AVX-512 at all, and Zen 4 and Zen 5 mobile
parts are double-pumped). The three distributions run in one dispatch so the
CPU is the same for all three, and the datapath is proven rather than
assumed: the provenance block carries task 43's op-count ladder at
`-XX:MaxVectorSize` 32 and 64, whose 256-to-512 step must be near 2x, not the
laptop's 0.95x (`SKILLS.md`, "This machine's AVX-512 is 256 bits wide") - a
run whose ladder says 256 bits is labelled as such, whatever the model name
claims. The workflow's commit input lands the three files on the branch; the
laptop's run is committed too, as the second data point, so the two show what
the datapath is worth - the unmeasured headroom that `SKILLS.md` entry names.

**The README.** Its benchmark section is rewritten from the three files: one
table, every covered date expression, Varka against stock Spark on JDK 17 and
on JDK 25, both shapes, on the 512-bit runner, with the laptop's numbers linked
rather than repeated; the honest rows stay (a row consumer at the read-back
floor is still 0.8x); and a reproduction section a reader can follow two ways
- the workflow dispatch with its inputs (class, JDK, `expected-cpu`), and by
hand without this repository's history: the three downloads, the three
commands, the seed and row count, how long it takes, and how to tell a
512-bit host from a double-pumped one before believing a number.
`docs/sql-varka.md` points at it. The quote check's rule holds: every figure in
the README traces to one of the three committed files.

**The job size: the per-job fixed cost under 5% of every Varka row.** Added
4 September 2026 from task 56's measurement (`PLAN_TASK_56.md` 9.2). The
throughput benchmark's Varka rows over 2M rows run in about 10 ms of wall
time, and a JFR and task-metrics probe taken after that run showed most of
those 10 ms is the job's fixed cost - the driver analysing and planning the
write again on every run, serialising and broadcasting the task, launching
it, the executor deserialising it - not the scan, the kernel or the output.
The number a reader is shown must not be that overhead: it makes the Varka
rate look several times lower than the pipeline's, it makes the ratio against
Janino several times smaller than the executor's, and it is why the committed
rows drift 10-30% between regenerations, a few milliseconds of standard
deviation on a ten-millisecond measurement. The rule for this task's driver,
then: **the table is sized so that the fixed per-job cost is under 5% of the
wall time of every Varka row**, which on a run whose fixed cost is about
10 ms means every Varka query runs for at least 200 ms - a row count in the
tens of millions on the laptop, or, where the runner's memory is the limit,
a view that unions the cached table enough times that one job runs enough
sequential tasks (the driver's cost is paid once per job, the per-task cost
is a fraction of a millisecond and stays in the executor's share). The
driver **records executor time beside wall time** for every row, through a
`SparkListener` over `TaskMetrics.executorRunTime` and `executorCpuTime`,
printed in the harness's own column layout under a second header so the diff
script and the quote check read it unchanged; the fixed share is then a
number in the file - wall minus executor time, over wall - and the 5% rule is
checked from the file, not asserted. The wall-time table stays the README's
table, because a reader understands how long a query took without a footnote;
the executor-time table beside it is what says how much of that was the
engine. The probe's own numbers are not committed and are not quoted; the
driver's first run on the laptop is where they are measured properly, and is
the baseline-first PR (`SKILLS.md`, the baseline rule) that lands before any
row of the public table.

**Predictions, registered when the task file is written, not here** - except
one that this section can already make: on the same host, the fork-with-Varka-off
numbers and stock Spark on JDK 25 will agree within noise for every shape,
which is the check that the baseline is honest.

Depends on every other open row of this milestone: it is the last one.

### 2.30 Int32 arithmetic in the date lane (task 63)

Added on 4 September 2026 by the owner's decision, after task 37 found that
`WHERE weekofyear(d) = 53` declined at the literal and task 57 had to be a
node of its own because `weekday(d) + 1` could not be an `Add`. The compiler
has no arm for `Add`, `Subtract`, `Multiply` or `UnaryMinus`, so any
arithmetic over a fused int field - `year(d) * 100 + month(d)`, the composite
key every month-grouped query writes; `datediff(a, b) + 1`; `weekday(d) + 2`
- declines whole and the row engine computes the entry, although every one of
those operations exists in the emitter's int32 lanes. The reason it was
deferred was semantics, not mechanics: in ANSI mode, Spark 4's default, an
overflow must raise `ARITHMETIC_OVERFLOW`, and until tasks 52 and 56 built the
per-batch decline route there was nowhere to send an overflowing lane.

**The scope.** `Add`, `Subtract`, `Multiply` and `UnaryMinus` over int32,
with operands that are fused int fields, `IntegerType` columns and int
literals, in both evaluation modes:

* non-ANSI: the lanewise op, which wraps exactly as Java's does, nothing
  else - the free case;
* ANSI: the same op plus an overflow mask per lane - same signs in and a
  different sign out for the add and subtract, a widened or saturating
  check for the multiply - ORed into the body's accumulator, so the batch
  declines through task 52's status route and the row engine recomputes it
  and raises Spark's own error for the same row (task 56's error-identity
  rule); behind a `VarkaEmitOptions` switch so the check's cost is measured
  against the unchecked form;
* `try_add`, `try_subtract` and `try_multiply`: the overflow mask cleared
  from the validity word - milestone 5's "difference-mask-as-validity path",
  which is the cheapest form of all.

Out, and staying in milestone 5: `/` (a double in Spark), `div` (a long,
task 29's lane), `%` and `pmod` (the divide-by-zero rule and no SIMD
division), and every int64 form. The one interaction with task 52's range
analysis is an int expression feeding a date function, `date_add(d, i * 7)`,
which is column-shifted whatever the arithmetic and needs no new bound.

*Note added 5 September 2026, from reading Stumpf and Povyshev, "Architectural
Patterns and Performance Analysis of Integer Surrogate Keys for Time-Series
Data Warehousing" (IJDMS 17(6), December 2025).* The two reasons above for
leaving `%`, `pmod` and `div` out - the divide-by-zero rule and the absence of
a SIMD division - both vanish when the divisor is a non-zero int *literal*: the
quotient is then one magic multiply and a shift, the operation the calendar
prefix is made of, and the remainder is one multiply and a subtract after it.
That is exactly the idiom warehouses write over integer-coded dates,
`YYYYMMDD` columns binned as `t div 10000` (the year), `t div 100 % 100` (the
month) and `t % 100`, and month keys as `t div 100`; the paper documents the
pattern across telecom, IoT and trading schemas, and TPC-DS's own `date_dim`
surrogate keys are its relative. Two spellings would need a narrowing rule
rather than a lane: Spark's `div` returns a long, and `CAST(t / 10000 AS INT)`
goes through a double, so both are int32 only when the consumer is int32 and
the compiler can prove the quotient fits. Whether that belongs to this task's
family or to milestone 5's int64 lanes is the owner's call; it is recorded
here because the shapes are int32 in and int32 out, and the divisor being a
literal is what makes them cheap.

**What it closes.** The compositions section 2.24 and scope item 11 of
`SCOPE_MILESTONE_6.md` list as residual today - the day of quarter, the week
of month, the Julian day number - and the composite keys, which join the
closing table (task 62) as rows of their own. Task 57's node stays: it is
the cheaper lowering of its one shape, and the arm for `Add(WeekDay, 1)` is
simply subsumed. The size is task 56's: a compiler arm set, one
overflow-detection block in the emitter behind an option, boundary tests at
`Int.MaxValue` and `Int.MinValue` in both modes, the error-identity
differential under ANSI, the `try_*` differential over overflow-dense and
overflow-free data, and a parity pair for the ANSI check's price with a
registered prediction. It comes after tasks 57 and 58 and is infrastructure,
so it takes a row of its own rather than a date function's.

### 2.31 Statistics-directed guard selection (task 64)

Added on 4 September 2026 from a question the owner asked about task 52's
runtime guard: the input batch, or the node before, may already know the
range of a column, and then the per-lane check is work the batch has proved
unnecessary. Task 52 (#115) puts a per-lane range check on a `date_add` whose
offset is a column and whose result a calendar node reads, at a measured 5-15%
of that kernel null-free and 13-14% with mixed nulls (`PLAN_TASK_52.md` 11).
The check exists because the compiler cannot bound a column at compile time;
a batch can.

**Three sources of the bound, in order of plumbing.** First, compute it: a
vector minimum and maximum over the offset column before the kernel runs,
which task 56 already does for the interval bound through
`IntRangeOps.allWithin` and which the throughput benchmark could not measure.
A date column holds the contract range (`CONTRACT_MIN_DAYS..CONTRACT_MAX_DAYS`),
so if every offset of the batch lies in `[NARROW_MIN_DAYS - CONTRACT_MIN_DAYS,
NARROW_MAX_DAYS - CONTRACT_MAX_DAYS]` no lane of `date_add(d, off)` can leave
the calendar range and the batch runs the **unguarded** kernel - the class
task 52's option already emits, since the shape cache keys on options. Second,
read it: the cached-batch serializers, the Arrow one included, compute count,
null count, lower and upper bound per column for every cached batch, and use
them today only to prune batches under a filter at the scan; the fork owns the
serializer and the scan-to-batch iterator, so the bounds can ride with the
`ColumnarBatch` to the exec node, where the check costs nothing - the null
count already travels that way for the null-free fast path. Third, the file:
Parquet row-group and page statistics, which the Arrow-native datasource
(`SCOPE_MILESTONE_6.md`, item 8's neighbourhood) is the place to attach.

**The design, in two steps.** Step one, the pre-pass: the evaluator, for each
compiled projection whose plan carries a guarded producer, runs
`IntRangeOps.allWithin` over the offset input with the bound above and picks
the unguarded kernel when it holds, the guarded one when it does not - both
from the shape cache, both already tested by task 52's suite, so the change is
in `VarkaKernelEvaluator` alone and the emitter does not move. The in-kernel
guard stays as the answer for the batch whose offsets say "maybe", which in
the corpus is never. Step two, the statistics: `ArrowCachedBatchSerializer`'s
per-batch bounds attached to the batch it deserializes, read by the evaluator
before it computes anything, so the pre-pass is skipped when the bound is
already known; the same channel answers task 56's interval bound for free and
opens batch pruning inside the fused pipeline later. Both steps behind their
own switch, with the pass and the lookup priced against the guard on the
parity benchmark's `year(date_add(d, off))` pair and on the throughput
benchmark's `date_add(d, i)` control.

**What it does not change.** Task 52's compile-time analysis is what says
which producers need a check at all; this task decides per batch whether a
given one does. A batch with a far offset still declines, through the same
route, and the differential's far-offset fixtures hold that. Depends on #115
and on task 56's kernel, both on master before it starts.

### 2.32 Year-month interval columns in the date lane (task 67)

Added 5 September 2026 by the owner's decision, after a survey of which
Spark types share the date's lane. Exactly three Spark types are int32
inside and in the Arrow cache: `DateType` (`DateDayVector`), `IntegerType`
(`IntVector`) and `YearMonthIntervalType`, whose value is a count of months
whatever its unit (`INTERVAL YEAR`, `INTERVAL MONTH`, `INTERVAL YEAR TO
MONTH`) and whose Arrow vector is `IntervalYearVector`, a
`BaseFixedWidthVector` of width four - the buffer layout the kernels already
read. The Arrow cache serializer already stores and prunes such columns
(`IntColumnStats`, `calculateMinMaxYearMonthInterval`), and
`ArrowColumnVector` already reads them (`IntervalYearAccessor`). Nothing in
the lane changes; what is missing is admission on both sides of the kernel:
the evaluator's `isArrowBacked` names the vector classes it serves
(`DateDayVector`, `IntVector`, `VarCharVector` at a derived source), and its
`allocateVector` names the output types it writes (`DateType`,
`IntegerType`); the compiler's value leaf admits a `DateType` column only.
Task 60 declines a stored interval column at the compiler ("year-month
interval column is not readable by the int32 lanes") because the evaluator
did not read it, and the evaluator did not read it because no arm asked.

**The task.** Admit the type end to end where no new arithmetic is needed:
an interval column as a value leaf and an interval literal as a slot; an
interval-typed kernel output; `d + ym` and `d - ym` with a column interval
through task 60's guarded `AddMonths` (the value is months in every unit,
which is what `DateAddYMInterval` adds); comparisons, `IN`, `BETWEEN`,
`greatest`/`least`, `coalesce`, `IF`/`CASE` over intervals, which the
existing nodes give once the leaf exists and Spark's type rules keep out of
the date-only positions; `CAST(i AS INTERVAL MONTH)` as the relabel it is
(`intToYearMonthInterval` returns its argument for the `MONTH` unit) and
`CAST(ym AS INT)` for a `MONTH`-ended interval likewise; the interval
entries in task 62's surface; and the wording in the docs, so that "three
types" is said exactly as far as it is true. What waits for task 63: the
`YEAR`-unit casts (a multiply or a division by 12 with the overflow check).
What is task 68's: everything that computes an interval from something else.

**Why it is worth a row of its own.** The change is small - two arms in the
evaluator, one leaf and a literal arm in the compiler, a fixture, a
differential and the surface entries; no IR node, no emitter byte, both
pinned fixtures unmoved - and it is the difference between the engine
covering one Spark type and three, which is the sentence the milestone's
public write-up leads with. The owner's reasoning, recorded: a cheap,
lane-compatible type is worth taking early for the message it sends,
provided the claim matches what fuses.

### 2.33 Year-month interval algebra (task 68)

The expressions that produce or transform an interval, once task 63's int32
arithmetic and task 67's type admission are in: `make_ym_interval(y, m)`
(`y * 12 + m`, exact in every mode, so always the checked form);
`extract(YEAR | MONTH FROM ym)` (`/ 12` and `% 12`, a literal-divisor magic
multiply - the node section 2.30's note asks for, which scope item 11's
`yyyymmdd` form reuses); `ym * k` and `ym / k` with a literal `k`
(`MultiplyYMInterval` exact; `DivideYMInterval` rounds `HALF_UP`, so its
literal form is a magic division plus a rounding step, registered before it
is built); `ym + ym`, `ym - ym`, `-ym`, `abs(ym)` (task 63's nodes with an
interval-typed output; Spark computes them with `addExact` and
`negateExact` in every mode, so they are always the checked form); the
`YEAR`-unit casts task 67 left. Out, and said so: `ym div ym` (a long),
`signum(ym)` (a double), `sequence` with an interval step, the timestamp
side, and `sum`/`avg` of intervals (milestone 6's aggregates). Its
admission check is the rounding of the literal division and the exactness
range of the two magic divisions over the full int32 month range, the way
task 65 is admitted; its measurement is a parity row per new node beside
the int arithmetic rows task 63 adds.

## 3. Task breakdown

Tasks 24-44 were the committed spine, in dependency order: 24 halves the
per-node emitter surface every later task would otherwise pay twice; 31 gives
25 an instrument that reads instructions rather than ratios, which is what 25's
central question needs (see 2.2); 25 shares
24's harness and changes how every later kernel is emitted; 26 and 27 spend
milestone 2's machinery before 28 complicates it; 28 enables 29 and 30's
widening. **Since the re-scope of 4 September 2026, 27, 28, 29, 30, 39 and 49
are milestone 5's** (`PLAN_MILESTONE_5.md`), and this table no longer carries
them. 32 and 33 are the two tasks here that no scope document predicted. 32 exists
because 26 measured what its own design cost and the number was worth a task
(see 2.9), which is the milestone's own rule about debts working as intended;
33 exists to measure something else entirely - whether a task can be handed to
a cheap agent as a recipe (see 2.10) - and picks the smallest payload it can
to do it. 34-37 widen that trial to four more payloads of increasing size
(see 2.11), and each of them makes task 32's debt a little more expensive,
which is worth watching rather than ignoring.
45-48 are the third unplanned addition, and they arrive the way 32 did: task
32's own measurement, once it was repaired, showed that three quarters of the
four-field kernel's time is validity bookkeeping rather than date arithmetic
(see 2.17). 45, 46 and 47 are that bookkeeping, and unlike everything else in
this milestone they are not about the calendar at all - every Varka kernel
writes validity, so whatever they win, every kernel wins. They run after 32
because 32's kernel is the instrument that measures them, and 45 opens by
turning its own premise into a number before any of the three is built. 48 is
unrelated to all of it and is here only because task 32's arithmetic review
noticed it (see 2.18). 49 comes from the same review asking why the calendar
lowering is range-narrowed at all, and finding that the answer - no
multiply-high on int lanes - stops applying once the lanes are int64 (see
2.19); it depends on task 29 and it competes with task 32's step B rather than
adding to it (both 29 and 49 are milestone 5's now).
Items 7, 10, 9 and 8 are the follow-on ladder in that order - each
needs its own argument to enter, per the milestone 3 rule. Numbering continues
the single sequence; this plan has already grown twice the way milestone 3's did
(task 31, section 2.2, then tasks 32-44, sections 2.9 to 2.16, and now tasks
45-48, sections 2.17 and 2.18, task 49, section 2.19, task 50, section 2.20,
and now tasks 51 and 52, sections 2.21 and 2.22); tasks 53-55 followed
within this milestone, and the next milestone's numbering continues from
wherever the sequence stands when its plan is written.

Task 51 is a fourth unplanned addition, and unlike 32, 45-48 and 49 it did not
come from a measurement - it came from the owner questioning task 26's guard
design directly, mid-review of task 36 (see 2.21). 52 is 51's other half,
tracked separately because the owner asked for the guard's removal and its
replacement to ship as two decisions rather than one: 51 is done, 52 is a plan
only, and nothing currently blocks it from being picked up next.

The table was audited against master on 4 September 2026: every row without a
DONE marker was checked against the code, its plan file and the merged pull
requests, and the state each row now carries is what that check found. Rows 37
and 42 are the planned recipes with nothing unmerged in their way; 25 and 44
have no plan file yet. Rows 27, 28, 29, 30, 39 and 49 left this table with the
re-scope the same day (`PLAN_MILESTONE_5.md` section 3), and rows 56-61 joined
it: the date-lane gaps a survey of Spark's date surface found after the
re-scope (sections 2.23 to 2.28) - the column forms of three functions whose
literal forms are covered, the two ISO week fields `extract` reaches, and the
int-cast `INTERVAL DAY` offset (the stored int64 column stays out, by
decision). 56 and 57 depend on nothing unmerged; 58 waits on 37, 60 on 52, and
61 on 59, which brings the derived-leaf mechanism both
string-argument forms need. Row 62 closes the milestone: every covered date
expression measured against stock Spark on JDK 17 and JDK 25, on a host with a
real 512-bit datapath, and the README rewritten from that run (2.29).

| # | Task | Deliverables | Validation |
|---|---|---|---|
| 24 | The scalar tail, interrogation, compaction. **DONE** (`PLAN_TASK_24.md`) | The tail-cost measurement (open question 3) recorded first; the unmasked-body-plus-masked-epilogue loop via `indexInRange`, deleting the emitter's second scalar IR walk; `compress(mask)` compaction in `VarkaFilterExec` against the committed ~1-3 ns/row ceiling, with the non-AVX-512 verdict; per-lane-group `anyTrue`/`allTrue` fast paths | Differential green at both vector widths, all null patterns, all-selected and none-selected; the pinned hashes and line map unchanged, which is the proof the refactor preserved behaviour (they were expected to move; see `PLAN_TASK_24.md` section 5); filter ladder re-run and committed; emitter per-node surface reduction stated as a number |
| 31 | Assert the instructions, not the ratio. **DONE** (`PLAN_TASK_31.md`) - eleven cases at both widths; every hand-written kernel and every emitted loop measured genuinely packed, which the project had been assuming from throughput ratios; the inline-directive question answered and declined (2.2's update note) | A forked-JVM disassembly harness on `-XX:CompileCommand=print` rather than `-XX:+PrintAssembly` (see 2.2's update note); host-derived instruction-family assertions over the `DateVectorOps` kernels and one emitted loop per gating shape; a scalar/vector self-test first, so a detector that matches nothing cannot pass the rest vacuously; no count or code-size assertions, since task 32's bimodality found identical vector-op counts with a 2x instruction count; a clean skip where `hsdis` is absent, distinguishing "not found" from "found and refused to load" | The suite fails on a scalar body where a vector one is expected, and says which method and which family; green at both vector widths; skipped-not-failed on a runner without a disassembler |
| 25 | ILP: the unroll factor as a plan decision. **Not started**; the harness it needs stopped measuring a degraded JIT state with PR #105 | The registered prediction, then the three-confounder matrix (K x broadcast strategy x `GROUP_BUDGET`) on `dayofweek`, unpredictable `CASE WHEN`, and the depth-8 chain; if K > 1 pays, per-shape K chosen from the live-temporary count the emitter already computes; the `SKILLS.md` bullet rewritten with the numbers; the batch-size knee sweep (question 6) on a wide fused shape | A committed number per candidate shape against its existing baseline; prediction scored honestly; no committed number regresses on shapes where K stays 1 |
| 26 | Calendar extraction, `year` first. **DONE** (`PLAN_TASK_26.md`) | The four-constant range-narrowing admission check, recorded before emitter work; `year`, `month` and `dayofmonth` committed - one civil-from-days decomposition yields all three - with `quarter` riding `month` and `dayofyear`/date-level `date_trunc` as the algebra yields them; fields whose constants will not narrow declined with a task-16 reason | Differential across the Gregorian range including pre-1970, leap years, month-length boundaries and the 400-year cycle edges, at both widths; parity numbers committed; `year` demonstrably compiling on the TPC-H q7/q8/q9 shape |
| 33 | `next_day`, as a handover experiment. **DONE** (`PLAN_TASK_33.md`, PR #61) | The node, the compiler arm declining every non-literal weekday, and the emitter arm over the existing mod-7 lowering; `PLAN_TASK_33.md` written as an executable recipe and scored in its own outcome section on which steps misled the agent that ran it | Every Varka suite green at both widths; the two pinned fixtures re-pinned under their update rule; no committed benchmark number moves, since the task adds a node type and changes no existing shape |
| 34 | `dayofyear`. **DONE** (`PLAN_TASK_34.md`, PR #64) | The node, the January-based conversion off `emitChrono`'s March-based day of year, and the shared leap-flag helper tasks 35-37 reuse | Every Varka suite green at both widths; the pinned fixtures re-pinned; a day outside the covered range still declines (**this decline was removed by task 51**; see 2.19's update note and `PLAN_TASK_51.md`) |
| 35 | `trunc(date, YEAR/MONTH/QUARTER)`. **DONE** (`PLAN_TASK_35.md` section 8) | One node carrying the level as a shape-bearing field, three lowerings, and the decline path for every level and format this task does not cover | As 34, plus a `DateType` output proved to feed further date arithmetic in the same chain |
| 36 | `last_day`. **DONE** (`PLAN_TASK_36.md`) | The node and the month-length tail, with February's leap case as its own branch | As 34, with every month length exercised in both a leap and a common year (the decline this inherited from 34 is likewise removed by task 51) |
| 37 | `weekofyear`. **DONE** (`PLAN_TASK_37.md`, rewritten on 4 September 2026 around the Thursday rule as two nodes, `WeekOfYear(ThursdayOf(d))`, so task 58 is `Year` over the same shift; the rule, the division magic and the reciprocal weekday swept in its section 2) | The node and the Thursday rule; no boundary corrections and no weeks-in-year helper, so the prefix runs over a computed day (`t`, not the column) and cannot share a fragment with the other calendar fields of the same date - state that in the plan rather than discover it | As 34, plus a dense day-by-day sweep across forty year boundaries rather than a boundary list: the rule claims to make the boundaries automatic, and the sweep is what checks the claim. Import Velox's Spark-compatibility `weekOfYear` fixtures as pinned cases beside the sweep (`velox/functions/sparksql/tests/DateTimeFunctionsTest.cpp`): 1919-12-31 and 1969-12-31 in week 1, 1960-01-01 in week 53, 0001-01-01 in week 1, 9999-12-31 in week 52, and the leap years ending on Thursday, Friday and Saturday - written against Spark by people who had to match it exactly |
| 38 | A day offset that is a column. **DONE** (`PLAN_TASK_38.md`, PR #62) | The four guards moved, the `andRef` validity fix, `IntegerType` leaves and Arrow `IntVector` inputs accepted, short and byte offsets declining | A null offset producing a null row, at both widths; short and byte columns declining; **no pinned value and no committed number moves**, since no node type is added and the literal path is untouched |
| 40 | days-from-civil, and month arithmetic. **DONE** (`PLAN_TASK_40.md`, PR #67) | `emitDaysFromCivil` as a helper three later expressions can call; the node behind `date +- INTERVAL n MONTH/YEAR` and `add_months`; the small-dividend month arithmetic and the literal bound it implies | The round trip tested on its own, not only through the expression; the clamp cases in both directions; a non-foldable or over-large month count declining; green at both widths |
| 41 | `unix_date` / `date_from_unix_date`. **DONE** (`PLAN_TASK_41.md`, PR #63) | Two compiler arms that unwrap to the child, no IR node and no emitted code; the bare-`ColumnRef` output shape tested | A projection mixing a relabelled entry with an ordinary one fuses both; no pinned value moves, no committed number moves, no emitted bytes change for any existing shape |
| 42 | `make_date`. **DONE** (`PLAN_TASK_42.md`, re-planned on 4 September 2026 against master and #115: the decline mask through task 52's accumulator, the year limit as the calendar range's whole years so `year(make_date(...))` fuses, and the first node that nulls a valid input, which takes a body off the dense fast path; starts after #115) | The three-child node, the validity predicate as a computed word in non-ANSI and a decline in ANSI, and the engine's year limit declining in both modes | The three-way distinction tested apart - null input, invalid date, unsupported year; both ANSI settings; the ANSI exception identical to the row engine's, compared by running both |
| 43 | What bounds a loop method inside one output. **Measured; the decision waits on the emitter** (`PLAN_TASK_43.md` 8) - a 20-to-248-op ladder in one loop method shows no cliff at either width: nanoseconds per row per op flat to +-4% at AVX-512 and improving then flat at 128-bit, and tier-4 compile linear at ~1.1 ms/op, 271 ms standard and **186 ms OSR** at 248 ops against the ~10 seconds at 64 ops the `GROUP_BUDGET` javadoc still cites. Two of four predictions missed: 128-bit does not degrade, and C1's virtual-register refusal is width-independent and lands on the epilogue, never on a loop | The cliff located first - single-output loops at 60 to 250 ops, throughput and time-to-peak - then split, decline or accept, chosen on that number | A committed number per width; whichever mechanism wins, `CASE WHEN year ELSE month` either fuses within a stated bound or declines with a recorded reason |
| 44 | The epilogue's size. **Not started**; its baseline has moved four times since it was written (the unshared crossing 17, 19, 20, then 21 outputs, the shared 40 then 44) and the crossing's cost is already priced in `PLAN_TASK_32.md` 7.3, so it opens by measuring fresh | A size ladder that can see the problem (4095 and 63, not only 4096), the epilogue measured against `HugeMethodLimit`, and the mechanism chosen on it | The wide-projection epilogue compiles, or declines; the committed ladder shows the epilogue's cost at a non-aligned length, which no committed case does today |
| 32 | One decomposition, several fields. **REPLANNED; steps A and B1 done (PR #72), step B2 planned and not built** (`PLAN_TASK_32.md` 10, PR #74; the gate is cleared, `groupOutputs` is unchanged and every calendar output still gets its own loop method, as the emitter suite pins) | The first ceiling kernel measured a non-inlining `computeFields` helper rather than the sharing, and was rebuilt hand-inlined, guarded and writing four validity buffers: 692.4/678.8 against 450.4/448.8 M rows/s at AVX-512 (1.5x), and a wash at 128-bit (1.06x, one run of five at 1.50x). Step B builds emitter-side fragment sharing behind a `VarkaEmitOptions` switch, with the default decided at both widths rather than closed. Step B1 built the fragment and made it the default: the epilogue's `HugeMethodLimit` crossing moves from 17 calendar outputs to 40 (**task 51 moves this again, to 19/44, task 48 to 20/44 and task 54 to 21/44 - see the debt register and `PLAN_TASK_54.md` 9**), and B2's grouping relaxation stays gated on the two-field measurement. **The gate cleared** (`PLAN_TASK_32.md` 7.2): 1.29x at two fields, 1.57x at three, 1.80x at four, growing rather than shrinking against prediction 3's expectation - and the emitted dense-body shared kernel turns out to beat `ChronoVectorOps`'s own "ceiling" by 1.15-1.20x, since that kernel has no dense path and was measuring the masked body throughout - and the same 1.29x/1.57x/1.80x pattern reproduces at 128-bit (1.31x/1.47x/1.67x), so the width-dependent bimodality that made the hand-written ceiling a wash at 128-bit (`PLAN_TASK_32.md` 7.4) turns out to belong to that specific kernel and not to the sharing mechanism. The compile-cliff risk `GROUP_BUDGET` itself exists to avoid was also measured directly rather than assumed (`PLAN_TASK_32.md` 7.5): `-XX:+PrintCompilation` on every kernel in the parity suite shows the widest single loop method (200 ops, four fields) reaching tier 4 in 272 ms and the widest kernel overall (twenty separate methods) in 2.4 s, nowhere near the historic 10-second cliff and with no `blocked` compile task anywhere in the log | `ChronoVectorOpsTest` differentials the kernel against `java.time` over its exact sweep range and a boundary set, at both widths (the engine module's own narrow-vector Maven profile); the emitted lowering swept against `LocalDate` over all 16,777,216 covered days under both settings; no pinned oracle moved, and every loop method asserted byte for byte unchanged, so no committed number for any existing shape can have |
| 45 | The null-free validity fast path. **DONE** (`PLAN_TASK_45.md` 11) - the driver sets a dense batch's value outputs valid once and the loop's per-lane-group `orValidityBitsAt` is not emitted; the shared four-field kernel gains 86% at AVX-512 and 149% at 128-bit, `year` 26% and 41%, `dayofweek` 12% and 46%, with both mixed-null controls within 2% at both widths. Prediction 3 - that the narrow width gains more, the same argument 2.17 makes for task 46 - is the cleanest hit; prediction 4 held at AVX-512 and missed at 128-bit | The bound first: a validity-free variant of `ChronoVectorOps` sizing the prize (2.17), then the dense driver filling the output validity once per batch instead of the dense loop ORing it per lane group | The whole Varka suite at both widths with the dense/masked pair still agreeing bit for bit; the committed parity cases regenerated in one run, with the null-free and mixed-null rows of each moving in opposite directions or not at all |
| 46 | Validity helpers that inline. **Not started, and narrower than written**: task 45 removed the dense path's per-lane-group validity call, so only the masked path is left to improve (`PLAN_TASK_45.md` 11.3) | Width-specialised `validityBitsAt`/`orValidityBitsAt` siblings under `MaxInlineSize`, selected by the emitter's existing name choice, with the switch resolved at emit time | `-XX:+PrintInlining` showing no `failed to inline` for them in a wide loop - the diagnostic, not the timing, is the deliverable - plus the full suite at both widths and one parity regeneration |
| 47 | One validity write per word. **Not started**; same re-scoping as 46, and gated on it and on task 44's non-aligned lengths | Bits accumulated across lane groups and stored once per 64 rows, with the epilogue flushing a partial accumulator | The masked path's committed cases, the 4095/63 non-aligned lengths task 44 adds, and the dense/masked agreement; gated on what 45 and 46 leave |
| 48 | A `year` that does not compute the month. **DONE** (`PLAN_TASK_48.md`) - `MARCH_TO_JANUARY_DAYS = 306` with the identity proved and asserted over all 366 cases; the prefix's month step made conditional on a per-lane-group consumer set behind `VarkaEmitOptions.elideChronoMonth`, so a `year`-only loop method takes the full five-op win section 2.18 wanted rather than the one-op remainder it predicted for whichever of this and task 32 step B landed second. A year-only body goes from 43 to 39 `IntVector` ops; the A/B is 1.01x at AVX-512 and 1.00x at 128-bit, i.e. inside the file's resolution, which the plan registered as a legitimate outcome before measuring. The unshared `HugeMethodLimit` crossing moves 19 -> 20 (third move, third unrelated reason); shared stays at 44. The regeneration also surfaced that task 51 shipped a ~19% win to every single-field calendar kernel without regenerating the parity file - see `PLAN_TASK_48.md` 9.2 | `doy >= 306` replacing the March-month step in the year tail only, with the equivalence recorded as an integer identity rather than an approximation | The existing exhaustive `VarkaChronoSuite` sweep unchanged and still green; the parity `year` case measured by interleaved A/B compared by minimums, since the expected effect is inside a single run's noise |
| 50 | Make a bad register allocation visible. **DONE** (`PLAN_TASK_50.md`) - the watch, keyed on (shape, method, tier) rather than the shape alone; the healthy spread measured at zero, byte-identical across three JVMs; and the reach established rather than assumed - a per-JVM baseline cannot see task 32's between-run bimodality, and what gives it something to compare is re-emission (`maxEntries = 0`, eviction, or the parked resample) | A `jdk.Compilation` JFR stream filtered to Varka's generated kernels, non-OSR only, comparing `codeSize` between compilations of the same shape hash rather than against any committed table; a metric and a debug log on divergence; off unless enabled | The stream observed to see Varka kernel compilations and report their sizes at both widths; zero cost when disabled, asserted rather than assumed; explicitly no re-emission on detection (see section 9) |
| 51 | Remove the per-extraction range guard. **DONE** (`PLAN_TASK_51.md`) | `hasChrono` and `s.guardAcc`'s allocation deleted; `emitEra`'s two compares and the mask ANDing/ORing into the accumulator removed, leaving only the day-of-era arithmetic; `emitStatusReturn`, the `int run` ABI and `STATUS_CHRONO_RANGE` left in place, unset, for task 52 to reuse; the two guard-specific differential tests removed and the two guard-decline unit tests rewritten to assert the new, weaker behaviour | Every Varka suite green at both widths in both modules; the two pinned fixtures unchanged (no emitted byte for an in-range shape moves); `dev/lint-java`, `dev/scalastyle`, `build/sbt catalyst/doc` clean |
| 53 | The Neri-Schneider month block. **DONE** (`PLAN_TASK_53.md` 14) - one affine numerator `num = 2141 * doy + 197913` gives the month index as `num >>> 16` and the day of month out of `num & 0xFFFF`, on the March = 3 axis; the op counts landed exactly, `dayofmonth` gains 13.5% at AVX-512 and 12.6% at 128-bit (mixed nulls 11.9% and 12.7%), `month` 4.3% and 5.7%, the shared four-field shape least at 3.6% and 5.0%, and `year` does not move. Three predictions held, one was beaten and two missed: the four-field shape gains least, not most, because fragment sharing already pays the block once across four outputs, and the unshared `HugeMethodLimit` crossing stays at 19 fits / 20 crosses on both axes because a boundary measured in whole outputs does not move for a saving smaller than one output. The 0-based lowering stays as the reference variant the exhaustive sweep runs against | The month index and the day of month from one affine numerator - `num = 2141 * doy + 197913`, the month as `num >>> 16` and the day of month out of `num & 0xFFFF` - replacing the magic-multiply month step and the `emitMonthStart` inversion behind it; the month axis moved to Neri-Schneider's March = 3, which is what removes the add in front of the reported month; `emitMonthStart` as a shift. The era and year steps are explicitly out of scope and stay as they are: the paper's correction-free century needs a dividend of 2^26 against a multiplier no larger than 32, and its year step needs the high half of a 64-bit product, so both wait for task 49's int64 lanes | The three identities over their exact domains (366, 65536 and 12 cases); the exhaustive sweep over all 16777216 covered days with both variants, both sharing modes and both widths, since `add_months` and `last_day` recompose through these constants; op counts asserted off the class file per `PLAN_TASK_53.md` 3.4; the parity file regenerated by this task rather than by the next one |
| 52 | Guard at the producer, not the extraction. **DONE** (`PLAN_TASK_52.md` section 10) | A compile-time day-shift interval analysis in the compiler that declines a calendar entry whose literal `date_add`/`date_sub` chain can leave the narrowed range's slack around the `[0001, 9999]` contract (free, not flagged, closes the gap reachable on master today), and a flag-gated runtime guard on `AddDays`/`SubDays` with a column offset (PR #62) that a calendar node consumes - the old guard's bytecode at the producer's output, once per distinct producer, reusing task 51's still-live `s.guardAcc`/`STATUS_CHRONO_RANGE` plumbing | The bound's edges at `+-1` in the compiler suite; the producer guard declining in a loop lane, an epilogue lane and not under a null offset; `date_add(d, off)` alone byte-identical under both flag values; the two differentials task 51 removed restored around the producer and the compile-time decline; a committed number for the guard's cost on the one shape that pays it |
| 54 | The Julian map in the prefix. **DONE** (`PLAN_TASK_54.md` 9) | Ben Joffe's replacement for the century-then-year split, behind `VarkaEmitOptions.julianMap` and now the default: `quad = 4 * doe + 3`, the century by one round-down magic and a carry, `jul = quad + 4 * century`, the year of era by one more magic and carry, the day of year as the remainder shifted right by two - no leap test in the prefix, no `century == 4` fold, no year-step underflow correction, and `100 * century` gone from the year assembly. `t[4]` holds the year of era; `emitChronoYear` takes the form. Five `IntVector` invocations fewer on `year`, `dayofyear`, `last_day` and `add_months`, three on the other tails - and **`year` null-free 2769.1 to 3452.2 M rows/s at AVX-512 (+25%; +25% and +26% in the same-run A/B at the two widths)**, four fields unshared +17%, shared +11%, `add_months` +3%; the mixed-null rows moved 0-14%. The op count predicted 8-12%: the stage removed was five serial masked ops on a latency-bound chain, worth its depth rather than its count. The epilogue ladder moved a fourth time, to 21/44 | The map over all 146097 days of an era against `java.time` and the old form on every build; both forms over the calendar boundaries with `last_day` and under every `add_months` offset; the Julian axis on the whole-range and `last_day` sweeps (run, green); the registered op counts off the class file, first time green; `VarkaChrono.narrowed` follows the default, the old form stays a live reference variant on `FloorMod7`'s precedent |
| 55 | Assert no allocation inside a kernel loop. **DONE** (`PLAN_TASK_55.md`) | The one failure that keeps every packed instruction in place and still costs 3-13x: a heap box per lane group, which two species of one lane type in one JVM produce (`SKILLS.md`, "Every operator the plans rely on"). Asserted as a *measured rate* - the probe reports heap bytes per call at steady state, the suite allows at most one byte per row - because a count of allocation sites in the disassembly cannot tell a per-call segment view from a per-iteration box (`vectorFourFields` carries four of the former). The site detector (allocation prefetch, mark-word store) is the printed diagnosis, with the `-XX:+PrintInlining` tell. On every kernel and emitted-loop case at both widths | The self-test pair at both widths: a gather alone reads 0 bytes per call and shows `vpgatherdd`; the same gather interleaved with a second species reads 26 bytes per row at 512 bits and 192 at 128, packed instructions intact. A `selectFrom` lookup, the first choice, boxes only if the second species ran hot first, so the gather is the calibrating shape; every existing case reads 0 except `vectorFourFields` at 0.16 per row, its per-call views |
| 56 | `date + INTERVAL n DAY` with a column interval. **DONE** (`PLAN_TASK_56.md`; its admission check found the int-to-interval cast throws past 106751991 days in every mode, so the rewrite carries a per-batch bound the evaluator checks, measured below this benchmark's resolution at both widths) | The compiler unwrapping `ExtractANSIIntervalDays(Cast(i, INTERVAL DAY))` to the int column itself, onto task 38's `AddDays(col, col)`; a stored `INTERVAL DAY` column declining with its own reason - by the owner's decision this task stays in the date lane, and the int64 column is not read | The rewrite's shape pinned; `d + CAST(i AS INTERVAL DAY)` and `d - CAST(i AS INTERVAL DAY)` differential with nulls on both sides; the stored-interval column still residual with its reason; no pinned value or committed number moves |
| 57 | `extract(DAYOFWEEK_ISO)` / `DOW_ISO`. **DONE** (`PLAN_TASK_57.md`) | One narrow arm, `Add(WeekDay(child), Literal(1))`, to a new `DayOfWeekIso` node: `WeekDay`'s floorMod7 tail plus one add; no general int arithmetic | The reference arm `getWeekDay + 1`; a whole week under the three `FloorMod7` variants at both widths; the three spellings in one differential; both pinned fixtures re-pinned once; `dayofweek`/`weekday` bytes unchanged |
| 58 | `extract(YEAROFWEEK)`. **DONE** (`PLAN_TASK_58.md`; one compiler arm over task 37's `ThursdayOf`, after 37) | `year` over task 37's Thursday shift, `t = d + 3 - weekday0(d)`: a compiler composition if 37 exposes the shift as a node, else the shift lifted out so both nodes share it; a `dayRange` arm of `[-6, +3]` | The December 28 to January 4 rows of years whose week 1 starts early and late, and the century years, against `getWeekBasedYear`; `weekofyear`, `yearofweek` and `year` in one differential; both widths |
| 59 | `next_day` with a weekday column | The derived int32 leaf: an evaluator pre-pass mapping a string column through the row engine's own parser before the kernel runs, null or the row engine's ANSI error per its rules; `NextDay(days, ColumnRef)` with `requireOffsetShape` and the two words ANDed; the parity number against the row path, registered prediction that a lone `next_day` wins little and reuse wins more | The leaf's null and error rules under both ANSI settings; the two-column `NextDay` over every null pattern at both widths; a differential over mixed spellings and nulls; the literal form byte for byte unchanged; the number committed whichever way it falls |
| 60 | `add_months` with a month-count column | `AddMonths(days, ColumnRef)` with `requireOffsetShape` and the words ANDed; the compile-time month bound moved to a runtime guard on the count lanes through task 52's `emitProducerGuard` plumbing, `STATUS_CHRONO_RANGE` on an out-of-range lane; `dayRange` answering `ColumnShifted` | The guard declining in a loop lane and an epilogue lane, not under a null count; in-range batches computed at both widths; the differential with in-range and out-of-range counts and nulls, the declined metric firing only for the latter; the count guard's cost measured beside task 52's row |
| 61 | `trunc` with a format column | Task 59's derived leaf mapping the format through `parseTruncLevel` to a level column whose validity is the output's; `TruncDateDynamic(days, levelRef)`, a chrono node computing all four levels off one prefix and selecting per lane by three blends; the doc saying the literal form is the shape to write | The reference arm `truncDate` per row over the parsed level; the matrix cycling all levels and the invalid codes at both widths; a differential over a format column mixing every level, invalid formats and null; the literal `trunc` byte for byte unchanged |
| 63 | Int32 arithmetic in the date lane: `Add`, `Subtract`, `Multiply`, `UnaryMinus` over fused fields, int columns and literals (section 2.30) | The compiler arms with an `IntegerType` column leaf; the lanewise op in non-ANSI; the per-lane overflow mask ORed into task 52's accumulator in ANSI, behind a `VarkaEmitOptions` switch; `try_add`/`try_subtract`/`try_multiply` as the mask cleared from validity; `/`, `div`, `%` and int64 left to milestone 5 | Boundary tests at `Int.MaxValue`/`Int.MinValue` in both modes; the error-identity differential under ANSI (same error, same row, as the row engine); the `try_*` differential over overflow-dense and overflow-free data; `year(d) * 100 + month(d)` and `datediff(a, b) + 1` fusing end to end; a parity pair for the check's cost with a registered prediction; both pinned fixtures re-pinned once |
| 64 | Statistics-directed guard selection (section 2.31) | Step one: the evaluator runs `IntRangeOps.allWithin` over a guarded producer's offset column against `[NARROW_MIN_DAYS - CONTRACT_MIN_DAYS, NARROW_MAX_DAYS - CONTRACT_MAX_DAYS]` and picks the unguarded or the guarded kernel from the shape cache per batch; step two: the Arrow cache's per-batch column bounds attached to the `ColumnarBatch` and read before the pass, answering task 56's bound too; each behind a switch | The guarded kernel never runs on the differential's in-range fixtures and the far-offset fixtures still decline; the pass and the lookup priced against the guard on the parity `year(date_add(d, off))` pair, both widths, with a registered prediction that the null-free and mixed-null cost of task 52's guard is recovered; byte identity of the emitter |
| 67 | Year-month interval columns in the date lane (section 2.32). **Planned** (`PLAN_TASK_67.md`) | `IntervalYearVector` admitted in `isArrowBacked` and `YearMonthIntervalType` in `allocateVector`; the interval column as a value leaf and the interval literal as a slot in the compiler; `d + ym` and `d - ym` with a column interval through task 60's guarded `AddMonths`; comparisons, `IN`, `BETWEEN`, `greatest`/`least`, `coalesce`, `IF`/`CASE` over intervals; the `MONTH`-unit casts as relabels; the interval entries in task 62's surface; the docs' type list. No IR node, no emitter byte | The differential over a cached table with columns of all three units, nulls and values past task 60's month bound, in both ANSI modes, through the projection and the filter, with zero fallbacks where the plan fuses and the declined metric where the bound trips; the evaluator suite with an `IntervalYearVector` input and output; both pinned fixtures unmoved; the compiler suite's shapes and declines (the `YEAR`-unit casts declined with their reason until task 63) |
| 68 | Year-month interval algebra (section 2.33) | `make_ym_interval`, `extract(YEAR | MONTH FROM ym)` by literal-divisor magic, `ym * k` and `ym / k` with a literal, `ym +- ym`, `-ym`, `abs(ym)` on task 63's nodes with an interval output, the `YEAR`-unit casts; the literal-divisor node that section 2.30's note and scope item 11 both want | The admission check on the rounding of the literal division and the exactness range of the magic divisions over the whole int32 month range; the differential in both ANSI modes with the overflow rows raising the row engine's own error; a parity row per new node beside task 63's; both pinned fixtures re-pinned once |
| 62 | The closing measurement: every date expression, on a 512-bit datapath, against stock Spark on JDK 17 and JDK 25. **(A) done** (`PLAN_TASK_62.md` 9: the driver, its module and shell driver, and the laptop's four files at 500M rows); (B) the pinned runner and (C) the README open | A Java driver under `sql/varka/bench` submitted to three distributions - stock Spark on JDK 17, stock Spark on JDK 25, this fork on JDK 25 - in one dispatch of the benchmark workflow with `expected-cpu` pinned to a full-width Xeon, running one committed SQL query per covered date expression in the projection and filter shapes over a table sized so the per-job fixed cost is under 5% of every Varka row's wall time (at least 200 ms per Varka query), recording executor time beside wall time for every row, with provenance including the 256-to-512 op-count ladder that proves the datapath; `dev/varka_bench_surface.sh` and the diff script producing the table; README's benchmark section rewritten from the three files with a reproduction guide a reader can follow from the downloads alone; the laptop's run committed as the second data point | Three results files with provenance, generated by one workflow dispatch on a 512-bit runner; every README figure tracing to them (the quote check); every Varka row's fixed share (wall minus executor time, over wall) under 5% in the files; the fork-with-Varka-off row agreeing with stock Spark on JDK 25 within noise on every shape; the ladder in the provenance showing the 256-to-512 step near 2x, or the file labelled 256-bit |

## 4. Files

* **Changed (catalyst):** `VarkaVectorIR` (the extraction and date-arithmetic
  nodes), `VarkaLoopEmitter` (masked epilogue, unrolling, the calendar tails and
  the prefix fragment), `VarkaEmitOptions` (the unroll factor joins the record
  if task 25 says it exists), `VarkaExpressionCompiler` (the extraction family
  and the date arithmetic), `VarkaShapeCacheImpl` only if the key vocabulary
  grows. The second `LaneType`, conversions, the overflow detectors and boolean
  outputs are milestone 5's (`PLAN_MILESTONE_5.md` section 4).
* **Changed (sql/core):** `VarkaFilterExec` (compaction), the evaluators, and
  `VarkaColumnarRule` (new eligible roots).
* **Engine module:** new hand-written reference kernels only where a parity
  anchor is needed (the task-26 algorithms), per the reference-code commenting
  rule.
* **Docs:** `docs/sql-varka.md` and `README.md` requoted from one run
  whenever a task moves committed numbers - tasks 24 and 25 will; the later
  tasks add numbers rather than move them.

## 5. Verification

The standing gates, inherited, with the two hardenings the scope promised:

* Differential against the row engine over every new shape, null patterns
  included, at the preferred width **and** `-XX:MaxVectorSize=16` - now at
  every lane width the milestone adds, not just every vector width.
* Parity: an emitted loop stays at or above the hand-written kernel where one
  exists; committed results regenerated in a single run on an idle machine, on
  the five-iteration two-second-window methodology.
* The ghost fallback still never fails a query; a shape the engine cannot
  express correctly is declined with a task-16 reason, never computed wrongly.
* The error-identity differential (task 30) and the lane-width gates moved to
  milestone 5 with their tasks (`PLAN_MILESTONE_5.md` section 5). The
  byte-exact oracle holds everywhere this milestone goes.
* The pinned shape hashes remain the behaviour oracle for refactors, and a
  task that legitimately moves them (24 above all) regenerates them under
  their update rule and says so, rather than treating the oracle as noise.

## 6. Risks

* **Masking the main loop.** The cheap implementation of task 24 masks every
  iteration and pays 2.3x-2.9x everywhere to save a tail that costs almost
  nothing per batch. The epilogue-only design is the whole point; the
  tail-cost measurement exists so the trade is visible.
* **A constant that will not narrow.** Task 26's algorithms live or die by
  four magic multiplies. The admission check runs before emitter work so a
  dead constant changes the algorithm, not the shipped semantics.
* **Numbers move under the milestone's own feet.** Tasks 24 and 25 change
  emitted bytes and committed relatives; docs are requoted from one run, never
  patched case by case.
* **Scope creep through the catalogue.** Items 7, 10, 9 and 8 are real and
  keep their full design input in `PLAN_MILESTONE_5.md` section 9; each enters
  only with its own argument, the way `In` and `Coalesce` entered milestone 3.

## 7. Open questions, and where each is settled

The scope's section 8, each question now owned by a task or settled here:

1. **The ULP oracle** (item 3): milestone 5's (`PLAN_MILESTONE_5.md` section
   7), with item 3.
2. **Mixed-width loop shape**: milestone 5's, measured and decided
   (narrowest-drive) before task 28 opens.
3. **What the scalar tail actually costs**: task 24 opens with it.
4. **Does an unroll factor above 1 pay**: task 25, prediction first.
5. **Does an unrolled loop still want a scalar tail**: tasks 24 and 25 share
   the harness, as the scope required.
6. **Is 4096 rows still the knee for wide fused shapes**: rides task 25's
   harness; either a knee worth respecting or the question retired in writing.
7. **Where the survey's corpus ends**: settled by the headline decision in
   section 1 - TPC-H and TPC-DS rank this milestone, taxi ranks item 3, and
   the type ranking the corpus could not give came from semantics-readiness
   (int64 first, because its semantics are already written).

## 8. Explicitly out of milestone 4

* **Every lane but `DateType`'s int32** - tasks 27, 28, 29, 30, 39 and 49 and
  catalogue items 1-5, since the re-scope of 4 September 2026: milestone 5.
* **Item 3, float and double lanes** - the headline decision's consequence,
  now milestone 5's deferred item; it re-enters whenever the taxi target is
  argued for, with its catalogue entry intact there.
* **Items 7 (aggregation), 10 (windows), 9 (string keys and dictionaries),
  8 (string functions)** - the follow-on ladder, in that order, carried in
  milestone 5's catalogue. Item 7 is first in line because milestone 6's
  aggregate wiring depends on it; none enters without its own argument.
* **The Varka Java configuration surface** - task 23 built and then scoped it
  out; the owner left it unscheduled. The design, the two converter lessons
  and the three increments are recorded in `PLAN_TASK_23.md` under "Deferred
  to a dedicated task"; it takes a number when it starts.
* **`DecimalType`** - per item 12; its design pass is `SCOPE_MILESTONE_6.md`
  items 1 and 2.
* **Grouped aggregation, hash joins, sorting** - grouping is hashing and
  partitioning, a milestone of its own after item 7.
* **The Arrow-native Parquet reader and writer** - the project owner's work.
  Coordinate, do not duplicate.
* **Buffer alignment enforcement** - the missing measurement is no longer
  missing (`VarkaMilestone4MeasurementsBenchmark`, section 2.5's committed
  results file, `addAligned`/`addMisaligned`): a buffer start offset by 4
  bytes (still 4-byte int-aligned, but every AVX-512 load then spans two
  64-byte cache lines) costs 1.56-1.79x throughput at the default width and
  1.22-1.25x at 128-bit, reproduced on all four runs (including two taken
  after merging task 24's PR with the machine's performance mode on, which
  changed nothing), over the L1/L2-resident 4096-row working set every real
  Varka kernel actually runs at.
  Section 2.3's ILP item does not absorb this for free either: a 2-way
  unrolled version of the same misaligned kernel (not committed - a scratch
  check, not this file's methodology) still lost 50-60%, so unrolling and
  alignment are independent levers, not substitutes. The measurement item 13
  was waiting on is done; what stays out of milestone 4's committed spine is
  the enforcement itself - an allocator-level change - which is now a design
  question with real numbers behind it rather than a deferred unknown, to be
  argued in with its own task the way item 13 or the string items would be.
* **Whole-stage code generation** - in the charter (`VISION.md` section 13),
  not in this milestone.

## 9. Debt register

One bullet per debt: what it is, why it is a debt, and what closing it would
take. Opened during task 24, per `sql/varka/AGENTS.md` - a swept entry is
rewritten in the past tense with what the sweep found, never deleted.

* **`GROUP_BUDGET` bounds one of the emitter's three method shapes.** **Adopted as tasks
  43 and 44 (see 2.16)**, both found by the review of task 26 rather than planned.
  `groupOutputs` partitions between outputs and never inside one, so a single output root
  holding several wide nodes emits one unbounded loop method - measured at 1672 bytes and
  roughly 190 vector ops for `least(greatest(year, month), greatest(dayofmonth, quarter))`,
  against the 59-op width the budget's own evidence covers. And the epilogue is one method
  over every output by task 24's deliberate decision, which is right about compile time and
  silent about bytecode size: `epilogueMasked` measured 7530 bytes at 16 calendar outputs
  and 8079 at 17, crossing the 8000-byte `HugeMethodLimit` past which HotSpot compiles
  nothing at all. Neither is a calendar defect; task 26 only made them reachable.
  **Task 32 step B1 moved the second number and did not close either task**: sharing the
  civil-from-days prefix between calendar outputs over one date takes the crossing from 17
  outputs to 40 - four date columns to ten - with the full ladder in `PLAN_TASK_32.md`
  section 7.1. Task 44 therefore plans against ten columns rather than four, and task 43's
  case is untouched, because a single output holding several wide nodes shares nothing.
  **The crossing's cost is now priced, not just its bytecode size**
  (`PLAN_TASK_32.md` section 7.3, twenty calendar outputs over five dates): 1.36x at both
  an aligned chunk (64 rows) and a batch one row short of aligned (4095), and 7.3x where
  most of a batch is remainder (chunk 63). The 1.36x at chunk 64 is the more important of
  the two, because it fires even though the epilogue does no real work there -
  `emitEpilogue`'s own generated body returns immediately when the batch divides evenly,
  but the *method itself* is still called on every batch, and calling into a method
  HotSpot will never compile at any tier costs something even when that call does nothing.
  **This corrects the framing above**: "runs interpreted... on every batch whose length is
  not a lane multiple" describes only where the interpreter does real *arithmetic*; the
  interpreter-call tax on the early return is paid on every batch, aligned included, small
  at these widths but not zero.
  **Task 51 moved both numbers again, for a reason unrelated to sharing.** Removing the
  per-extraction range guard (2.21) shrinks every calendar node's emitted bytecode, shared
  or not - the guard lived in `emitEra`, which both paths call - so both crossings moved
  out again: unshared from 17 outputs to 19, shared from 40 to 44. The full re-measured
  ladder is in `PLAN_TASK_32.md` section 7.1's update, kept alongside the original rather
  than overwriting it. Task 44's own baseline (ten date columns, from step B1) is now a
  baseline for a number that has since moved twice for two independent reasons - sharing,
  then guard removal - which is worth knowing before task 44 is actually picked up: measure
  fresh against the emitter as it stands then, not against either ladder here.
  **Task 48 moved the unshared number a third time, to 20.** Letting a year-only prefix skip
  the March-month step (2.18) shrinks every unshared calendar prefix whose consumer is a
  `Year`; the shared column is unmoved at 44, since the epilogue holds every output and so
  every fragment in that shape has a month consumer. Third ladder, measured the same way, in
  `PLAN_TASK_32.md` section 7.1's second update.

* **A calendar field is computed once per output, not once per date.** **Half closed by
  task 32 step B1, the rest gated on a measurement (see 2.9 and `PLAN_TASK_32.md`)**, after
  a first pass that swept this entry the other way and had to be redone. Step B1 built the
  emitter-side fragment and made it the default, so a projection's *epilogue* now computes
  the decomposition once per date rather than once per field; the loop methods still compute
  it once per field, because relaxing the grouping policy that keeps them apart is step B2
  and B2 is gated on measuring the two-field case first. A calendar field is ~50 vector ops, ~45 of which are the
  shared civil-from-days prefix and ~5 the field's own tail; four fields therefore cost
  ~200 ops as four independent nodes against ~65 shared, a saving of ~135 ops. The
  hand-written ceiling kernel that prices that saving reaches 679.0 M rows/s against the
  four emitted nodes' 445.7 in the committed parity file - **1.5x** over four runs - and 165.6 to
  167.0 against 154.1 to 157.6 at 128-bit, a wash (`ChronoVectorOps`,
  `ChronoVectorOpsTest`, sql/varka/engine). The first pass measured 225.8 for the same
  kernel and declined the task; that kernel had a 376-byte helper past C2's 325-byte
  inlining budget in its lane path, so it priced a heap allocation per lane group rather
  than the sharing. Task 17's finding (raising `GROUP_BUDGET` so two outputs could share
  cross-output CSE in one method *lost*, 4119.9 against 2928.2 M rows/s in the current
  committed results file) still holds and is visible here as the reason the win is 1.5x
  rather than the ~3x the op count alone would predict, and as the reason it disappears at
  128-bit - but it does not reverse the sign. Closing the debt needs neither a multi-value
  IR node nor any IR change: the values worth sharing are locals inside one node's emitted
  bytecode, and the emitter can share them keyed on (fragment, child node). That is step B
  of `PLAN_TASK_32.md`. A multi-value node stays parked here for a future primitive whose
  shared value must be visible to the *planner* rather than only to the emitter -
  `divmod` and a string operation returning an offset and a length were the general
  examples; neither has been measured.

* **A badly-allocated kernel could be detected and re-emitted, and deliberately is not.**
  Every Varka kernel is emitted into a fresh class, so re-emitting the same shape under a new
  class name gives C2's register allocator a fresh roll - and task 50 makes the bad roll
  detectable. A detect-and-resample loop is therefore *buildable* with no new machinery. It is
  not scheduled, in this milestone or the next, and the reasoning is recorded here so it is not
  re-proposed as an obvious win. Each resample costs another class, another compilation and
  another warm-up, against a kernel a short query may run only a handful of times, so the
  expected value is negative wherever it matters most; class churn is already a watched concern
  (`VarkaClassLoaderTest` stresses a thousand loaders against metaspace); nothing bounds the
  retry, so a cap turns it into a slot machine and no cap turns it into a loop; and it treats a
  symptom whose structural cause - four outputs in one loop method at a width with sixteen
  vector registers - task 32 can remove outright at zero runtime cost. Revisit only for a
  kernel long-lived enough that one extra compilation amortises, and only with task 50's
  numbers in hand to say how often the bad roll actually happens.

* **`DateVectorOpsBenchmark` measures a degraded JIT state.** CLOSED. The engine's JMH
  runs with `forks = 0`, in the surefire JVM, *after* the JUnit suites have
  exercised the same kernels - so every committed figure in
  `DateVectorOpsBenchmark-jdk25-results.txt` is measured against profiles those
  suites polluted. Task 24 found it the hard way: three rounds of A/B in that
  harness said a kernel change cost 4-50%, and the clean catalyst harness then
  put the same change inside its own noise. The tell was
  `-XX:CompileCommand=inline,jdk/incubator/vector/*.*`, which moved the JMH
  numbers by 50-190% and the catalyst numbers by under 1% - a flag worth that
  much in only one harness is measuring the harness. A second symptom, visible
  in the committed file's own error columns: `scalarSubDays.MIXED_NULL`, which
  no recent task has touched, swings 3x between runs. Closing it means giving
  the JMH phase its own JVM (`forks = 1`) or separating it from the test phase,
  and then regenerating the whole results file, because every number in it
  moves. It matters now because task 25 is about to ask this harness whether an
  unroll factor pays, and on today's evidence it cannot answer.

  One cause is now named from C2's own output (September 2026; `SKILLS.md`, "Every
  operator the plans rely on"). `VarkaMilestone4MeasurementsBenchmark` builds `ISPEC_HALF`,
  an int species at half the long width - `Int256Vector` beside the preferred
  `Int512Vector` here - and its single `@Setup(Level.Trial)` runs the lane-width kernels as
  a correctness check before *every* benchmark in the class. Two species of one lane type
  in one JVM make the shared `IntVector` templates inline bimorphically, and C2 then keeps
  a heap box per loop iteration in some shapes: a probe measured the same loops at 2.7x to
  12.8x slower once a second species had been touched. With `forks = 0` that state also
  reaches every benchmark run after it in the surefire JVM. The fix is the one this entry
  already prescribes, `forks = 1`, which the runner can do (a forked JMH child inherits the
  parent's `-XX:MaxVectorSize` and module flags, verified), plus a `@State` of its own for
  the lane-width pair so only `laneWidthNarrowestDrive`'s fork ever sees the second species;
  Closed: the three engine runners now fork one JVM per benchmark (the child
  inherits the surefire argLine, `-XX:MaxVectorSize=16` included), the lane-width
  pair has a `@State` of its own so only `laneWidthNarrowestDrive`'s fork sees the
  second species, and all three results files were regenerated with what moved
  listed at the end of each. The number task 24 could only reach by forcing C2 to
  inline the Vector API - `vectorDateDiff` null-free at 10000 rows, 1276 against
  the in-process 435 - is what a plain forked JVM measures (1211): the flag was
  measuring the harness, as the entry above suspected.
* **The week fold costs more than its op count (task 37).** `weekofyear` is 64
  dense-loop calls against `year`'s prefix-plus-tail, yet runs at 0.41x of `year`'s
  rate at 256 bits and 0.38x at 128 (`PLAN_TASK_37.md` section 9, prediction 2), a
  lower share than the extra ops account for. A debt because the same tail shape
  returns for task 58's `yearofweek`; closing it takes the dense loop's assembly
  (`dev/varka_emit.sh --asm`) for `weekofyear` beside `year`, to see whether the
  shift's `floorMod7` scratch or the fold's dependent chain is what the register
  does not count.
* **Two calendar outputs over one shift share nothing in production (task 58).** The
  `weekofyear + yearofweek, one shift` parity row runs at 0.58x of `weekofyear` alone, the
  ratio of no sharing (64 + 51 ops), where the shared prefix and shift predict 0.83x; the
  suite's sharing test holds only under a wide `GROUP_BUDGET`, and the shipped budget
  gives each calendar output its own loop method, so the pair shares only in the epilogue.
  Closing it is task 44's decision (one loop method for the group) measured on this row.
* **The parity harness's `dayofweek, chunk 64/63` rows are bimodal on branches after task
  37.** Two of five regenerations on branches carrying task 37's emitter change read those
  three rows 23-38% under both master runs (task 37's first run, task 58's second); the
  other three, and every master run, agree within 6%. No code these rows execute changed.
  Closing it takes running the section alone under `-XX:+PrintCompilation` in both states
  to see which kernel's compile the chunk rows land after - the harness's JIT order, not
  a kernel, is the suspect.

* **A projection that only narrows a Varka filter's columns runs through rows.** Found by
  task 62's laptop run (`PLAN_TASK_62.md` 9.1): `SELECT d FROM t WHERE d < d2` plans a Janino
  `Project [d]` over the row-producing `VarkaFilterColumnarToRowExec`, because the columnar
  rule takes a projection only when at least one entry fuses, and a projection whose entries
  are all forwarded columns of a Varka child has none - so the filter's kernel runs and then
  every selected row crosses the read-back floor anyway: 43.2 M rows/s against stock's 67.7
  at 70% selected, on a predicate whose kernel alone runs at 681.2 when nothing is selected.
  It is a debt because it is the common shape of a two-column predicate under a projection.
  Closing it is small: let `VarkaFilterExec` prune its output to the parent's required
  columns, or let the rule take a forwarded-only projection above a Varka node; either way
  the differential over `SELECT d ... WHERE d < d2` through a columnar consumer is the gate.

## 10. Scope catalogue

The pre-plan catalogue, item numbers preserved. Items the plan above adopts
are condensed to a pointer; items about other lanes and the follow-on ladder
(1-5, 7-10) moved to `PLAN_MILESTONE_5.md` section 9 with the re-scope of
4 September 2026 and keep their full design input there; items 6, 11, 12 and
13 stay here.

### Item 1. Lane-width conversion, and mixed-type expression trees

Moved to `PLAN_MILESTONE_5.md` section 9 with the re-scope of 4 September 2026;
the design input is there in full, under the same item number.

### Item 2. int64 lanes: `TimestampNTZ`, `bigint`, and the second lane width

Moved to `PLAN_MILESTONE_5.md` section 9 with the re-scope of 4 September 2026;
the design input is there in full, under the same item number.

### Item 3. Float and double lanes, and the numeric function family

Moved to `PLAN_MILESTONE_5.md` section 9 with the re-scope of 4 September 2026;
the design input is there in full, under the same item number.

### Item 4. ANSI-correct integer arithmetic, priced rather than assumed

Moved to `PLAN_MILESTONE_5.md` section 9 with the re-scope of 4 September 2026;
the design input is there in full, under the same item number.

### Item 5. Boolean outputs

Moved to `PLAN_MILESTONE_5.md` section 9 with the re-scope of 4 September 2026;
the design input is there in full, under the same item number.

### Item 6. Calendar field extraction, `year` first

Adopted as task 26 (see 2.4). The corpus calibration kept on the record:
TPC-DS pre-materialises calendar parts (`d_year`, `d_moy`, `d_dom`, `d_qoy`,
`d_dow`), so extraction appears zero times there; TPC-H q7, q8 and q9 use
`year(date)` and nothing else. Intuition overweights this item; the corpus
says it is one function wide.

### Item 7. Aggregation: the first horizontal reduction

Moved to `PLAN_MILESTONE_5.md` section 9 with the re-scope of 4 September 2026;
the design input is there in full, under the same item number.

### Item 8. String functions, and the byte lanes they need

Moved to `PLAN_MILESTONE_5.md` section 9 with the re-scope of 4 September 2026;
the design input is there in full, under the same item number.

### Item 9. String keys: equality, hashing, dictionaries

Moved to `PLAN_MILESTONE_5.md` section 9 with the re-scope of 4 September 2026;
the design input is there in full, under the same item number.

### Item 10. Cross-lane movement: windows, prefix sums, row indices

Moved to `PLAN_MILESTONE_5.md` section 9 with the re-scope of 4 September 2026;
the design input is there in full, under the same item number.

### Item 11. Compaction, mask interrogation, and the scalar tail

Adopted as task 24 (see 2.1), with task 21's committed compaction numbers as
the ceiling the scope asked for.

### Item 12. Considered and set aside

Recorded so they are not re-proposed:

* **`Float16`**: no Spark type maps to it, not even through Arrow.
* **Unsigned comparison and min/max**: Spark has no unsigned integer type;
  available as internals (hash bucketing, `shiftrightunsigned`), not an
  expression family.
* **`DecimalType`**: not a lane type - precision <= 18 fits an int64
  unscaled value, but the general case is 128-bit with no lane at any
  species. It needs its own design pass, not an item here; that pass is
  `SCOPE_MILESTONE_6.md` items 1 and 2, made urgent by the survey.
* **`CPUFeatures`**: package-private, so a fallback decision comes from a
  measurement or the species width, never a feature query.
* **Hash joins**: scalar probing over off-heap tables with SIMD reserved for
  radix partitioning and post-probe projection; they want item 7 first and a
  milestone of their own after it.
* **`reinterpretAs*` / `viewAs*`**: useful inside item 3 for NaN
  canonicalisation, not an item.

### Item 13. Instruction-level parallelism: the unroll factor

Adopted as task 25 (see 2.3). The full three-constraint pricing - the 7x
pinned-broadcast collapse, the ~1 ms-per-vector-op compile cliff against
`GROUP_BUDGET` (the per-op rate confirmed at 1.1 ms by task 43, though "cliff"
is the wrong word for something linear - see 2.16's reconciliation note), and
`DIV` scalarization that unrolling cannot rescue - lives
in `SKILLS.md`'s "Vector API on HotSpot, Measured", whose unrolling bullet
task 25 rewrites with the numbers. The morsel-locality half was satisfied by
construction (a 4096-row int32 batch is 16 KB, L1-resident); the wide-shape
knee is open question 6 and rides task 25's harness.
