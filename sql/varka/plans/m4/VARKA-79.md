# VARKA-79: the guard and the untaken arm

## 1. Where this came from

`m4/PLAN.md` row 79 and section 2.41, opened 7 September 2026 out of
VARKA-60's review and widened by VARKA-63 (`VARKA-63.md` 9.8) when checked
arithmetic became a third node kind disposing of its mask through the same
`emitGuardCollect`. The observation: a vector body computes both branches of an
`IfElse`, and a guard does not produce a value that a blend can discard - it
condemns the batch. So a guarded node inside a `CASE` arm declines batches on
rows the condition sends to the other arm. Answers stay correct, since the row
engine recomputes the declined batch; the fusion is what is lost.

## 2. The admission check, done

Checked against master (`5448fe05f62`) with `dev/varka_emit.sh`, and in one
place by patching the compiler and reverting. 2.1 confirms 2.41's design hazard.
2.2 finds that 2.41's motivating example is unreachable today, and how far away
it is. 2.3 pins the shapes that are reachable. Together they settle which fix is
worth building, and they narrow the sharing rule the fix depends on.

### 2.1 The sharing hazard is real, and two lines of SQL reach it

2.41 warns that a guarded node can be shared between a use inside an arm and a
use outside it, so a guard qualified by the arm's mask would be emitted once
under that mask and reused by the unconditional consumer - which would then stop
declining batches it must decline. Silently wrong, not slow.

It is not hypothetical. For

    CASE WHEN d < DATE'2020-01-01' THEN add_months(d, m) ELSE DATE'1999-01-01' END,
    add_months(d, m)

the emitter's line map has seven nodes and `(addMonths 1 4)` appears once:

    1=col:0   2=lit:0   3=(cmp:LT 1 2)   4=col:1
    5=(addMonths 1 4)   6=lit:1   7=(if 3 5 6)

`sharedSlot` is keyed on `analysis.useCount > 1` with no notion of arm, and
`emitValue` reloads on `computed.contains(node)` alone; its own javadoc says
sharing crosses outputs "since the loop body is one straight line". So a
sharing rule of the kind 2.41 states - a node used anywhere unconditionally
keeps an unqualified guard - is mandatory for the honest fix, not a refinement
of it; 3.3 has the form this task builds, which is narrower than 2.41's, and
why.

### 2.2 The motivating example does not fuse today, and what stands between

2.41 motivates the task with

    CASE WHEN m BETWEEN -1000 AND 1000 THEN add_months(d, m) ELSE NULL END

and says the cliff "defeats exactly the range test the user wrote to keep the
shape fused". The emitter's answer for that projection is
`nothing fused: every entry declined`. The condition is the reason: a
comparison's operand goes through `compileNode`, whose value leaf is `DateType`
(and, since VARKA-67, a year-month interval) - a bare `IntegerType` column in
predicate position has no arm and declines. `VARKA-63.md` 9.8 already noted
this for the arithmetic instance; it is true of the whole family.

**So the user cannot write that range test today, and the sentence that
motivates this task is wrong as it stands.** The cliff is real - see 2.3 - but
what a user can currently express is "a condition about something else", not
"my own bound on the count".

*Extended on 8 September 2026, after the paragraph above was first written and
the obvious follow-up question was asked: could those shapes fuse?* They could,
and the distance is short, which matters for how this task is argued rather than
for what it builds. One blocker, and one thing that looked like a blocker and is
not; both established by patching the compiler experimentally and reverting:

* **A bare int column has no arm in comparison operand position.** `compare`'s
  local `operand` admits an int *literal* directly - that is what makes
  `month(d) = 6` fuse - and sends everything else to `compileNode`, whose value
  leaf is `DateType` and the year-month interval. Adding one case for an
  `IntegerType` `BoundReference` was enough to fuse
  `CASE WHEN m > 0 THEN d ELSE d2 END` and, with it, 2.41's own shape in its
  explicit form:

      CASE WHEN m >= -1000 AND m <= 1000 THEN add_months(d, m)
           ELSE DATE'1999-01-01' END
      -> (if (and (cmp:GE col:0 lit:0) (cmp:LE col:0 lit:1))
              (addMonths col:1 col:0) lit:2)

* **`BETWEEN` is not a second blocker, and the appearance that it was is an
  artifact of the tool.** `Between` is a `RuntimeReplaceable` whose replacement
  is `With(input) { ref => And(GreaterThanOrEqual(ref, lower),
  LessThanOrEqual(ref, upper)) }` unless `ALWAYS_INLINE_COMMON_EXPR` is set, and
  the compiler has no arm for `With`/`CommonExpressionRef` - which is why
  `dev/varka_emit.sh` declines it. But that tool resolves names and functions
  and *nothing else*: it never runs the optimizer, and
  `RewriteWithExpression` inlines a binding whose child satisfies
  `CollapseProject.isCheap`, which an `Attribute` or `BoundReference` does. So
  in a real query a `BETWEEN` over a column arrives as the plain `And` above.

  The repo already proves it: `d BETWEEN DATE'2020-06-01' AND DATE'2021-06-01'`
  is a `Surface.filter` entry and VARKA-62's 1B-row run has it fusing whole at
  7.96x. If the `With` reached the compiler that entry would decline.

  So after VARKA-86's widening, `m BETWEEN -1000 AND 1000` fuses with no further
  work. What remains unexamined is a `BETWEEN` over an input `isCheap` refuses,
  where the rewrite hoists the common expression into a `Project` instead of
  inlining it; that changes the plan shape rather than the expression, and
  nothing here has looked at what Varka makes of it.

**Neither is taken here, and the first is deliberately not taken here.**
Widening `compare`'s `operand` alone is a widening of one of the four
near-copies that milestone 5's VARKA-86 exists to unify, and widening one copy in
isolation is exactly the move that produced the ghost fallback VARKA-86 was
opened for - `date_add(d, weekday(d2) + 1)` accepted by the compiler, marked
fused in EXPLAIN and refused at emit time. It looks safe here, because `Compare`
already takes arbitrary IR operands and both lanes are int32, so the emitter
side probably needs nothing; "probably" is the word that cost a PR. It is folded
into VARKA-86, whose enumeration test - the compiler's admitted set equals the
emitter's accepted set, per position - is what turns that "probably" into an
assertion.

What this does to the present task is change its argument, not its design. If
the bound becomes expressible, ignoring it is worse rather than better, so the
honest fix in 3.1 is more motivated and the cheap candidate less. 3.1 is written
against the shapes reachable today and holds either way; this note records that
it also holds after VARKA-86, which is the case where getting it wrong would be
most visible to a user.

### 2.3 What is actually reachable

Conditions that fuse are date- or fused-field-driven, and both instances of the
cliff are reachable through them:

    CASE WHEN d < DATE'2020-01-01' THEN add_months(d, m) ELSE DATE'1999-01-01' END
      -> (if (cmp:LT col:0 lit:0) (addMonths col:0 col:1) lit:1), fused, and the
         column-count AddMonths is in `selfGuarding`, so its guard fires on every
         lane whatever the condition says.

    CASE WHEN d < DATE'2020-01-01' THEN i + 1 ELSE year(d) END
      -> (if (cmp:LT col:0 lit:0) (int:ADD:FAIL col:1 lit:1) (year col:0)), fused,
         with the ANSI check's mask going through the same collect.

    year(CASE WHEN d < DATE'2020-01-01' THEN date_add(d, off) ELSE d END)
      -> VARKA-52's guarded day producer under an arm, with the calendar node that
         the guard protects sitting *outside* the arm and reading the blend.

    CASE WHEN d < DATE'2020-01-01' THEN make_date(y, m, dd) ELSE d END
      -> VARKA-42's make_date, which is *always* in `selfGuarding` - its year-range
         check, and under ANSI its invalid-date check, condemn the batch through
         the same collect. Found by 2.4, not by 2.41: the pinned `everyNode`
         fixture already has two of them under an arm.

The third is the task's original instance and the one with the soundness
argument worth writing down (3.3): the guard belongs to the producer but exists
for the consumer, and the consumer is outside the arm - yet narrowing the guard
to the arm is still sound, because on an untaken-arm lane the blend hands the
consumer the other branch's value and the producer's out-of-range day never
reaches it.

All four fuse today and all four decline a batch for a row the condition
discards. That is the task's whole surface: a batch-condemning node under an
arm, with a condition that cannot mention the thing being guarded. Four node
kinds, then, not the three the milestone row counts: VARKA-52, VARKA-60, VARKA-63 and VARKA-42.

### 2.4 Verified after the plan's review, before any code

Four things the first draft asserted or left open were checked in the emitter.
All four resolve without a design change; one of them adds a node kind (2.3's
fourth shape) and one removes a whole mode from scope.

**The condition's slots are per node and outlive the arms, so no context stack
is needed.** `s.kt`, `s.kf` and `s.condMask` are `Map<VarkaVectorIR, Integer>`:
every condition owns its own local, and `emitValue`'s `IfElse` arm emits the
condition, then the else branch, then the then branch, and only then loads
`kt.get(n.cond())` for the blend. So the condition's word is live across both
arms by construction, a nested condition takes a different slot and cannot
clobber the outer one, and the arm context for a node under nested arms is
simply the AND of each enclosing condition's own `kt` or its complement, all of
them still in their slots. The emission order also settles a detail of 3.3: the
*else* arm is emitted first, so a node shared by both arms of one `IfElse` meets
its first textual use under the else context - and the rule gives it an
unqualified guard anyway, since then and else are different chains.

**Reading `kT` in the guard creates no liveness demand.** VARKA-70's pass governs
the words that go through `storeWord`/`loadWord`, which record into
`wordDefs`/`wordUses` and are what `assertWordsLive` reconciles. The condition's
`kt` is stored with a bare `lstore` and loaded with a bare `lload`, outside that
bookkeeping, and the same holds for the dense body's `condMask`. So
`emitGuardCollect` can load the arm context without a new demand edge and
without tripping the invariant. The word the collect already ANDs in - the
node's own - keeps the demand it has today.

**`NULL`-mode disposal has no cliff and is out of this task.** The `IfElse` word
blend is `(kT & validThen) | (~kT & validElse)`, read straight off the emitted
bytes: the untaken arm's word is masked out entirely. A `try_add` under an
untaken arm clears a validity bit in a word the blend then discards, so nothing
is lost and nothing declines. VARKA-79 is about the batch-condemning disposals
only - `FAIL` and the three runtime guards - and 5's tests do not include a
`NULL`-mode case. The same line is the direct evidence for 3.2's polarity: the
else arm's share of the blend is `~kT`, not `kF`.

**The pinned oracles do not move, but the pinned fixture is a guarded shape.**
The pinned line map and the shape hash in `VarkaShapeCacheSuite`
(`1661b1b146818e6a`) are both derived from the IR, and this task adds no node
type, so neither moves. But `everyNode` holds two `MakeDate`s under its else
arm, and `MakeDate` is always in `selfGuarding`, so `everyNode`'s *emitted
bytes* change under this task - each `MakeDate` is a distinct node used once
under one chain, so both guards narrow. No `codeSize` is pinned on `everyNode`,
so no test breaks; 6.1's prediction 2 is reworded to say what actually holds.
`everyNode`'s `AddMonths` has a literal count and is not guarded.

**The parity benchmark's `CASE` rows do not move.** Its `IfElse` case (ids 400
and 401) blends two `AddDays`/`SubDays` chains with literal offsets - no guarded
producer in either arm - so its bytes are unchanged and section 6 adds rows
rather than requoting those.

**What 2.5 did not settle, and is the implementation's first design decision:
where the use-context analysis lives.** Three sites collect guarded nodes -
`collectColumnOffsetProducers` under each calendar node, the `selfGuarding`
additions for `AddMonths` and `MakeDate`, and `checkedArith` in `analyze()` -
and none records *where* a node is used. `analyze()`'s traversal does visit
every use, through `analyzeOp(node, true, cond, then, else)` for `IfElse`, so a
context stack there can record per node the chain of each use, and
`collectGuardedProducers`, which runs after every root is analyzed, can read one
shared per-node answer. That is the shape 3.3 assumes; the alternative - a
fourth walk - is what to avoid.

### 2.5 What the check would have rejected

That the sharing hazard was theoretical (it is two lines of SQL); that the
user's own bound is what the cliff defeats today (the user cannot express it
until VARKA-86); that a guard's arm mask can always be assembled where the guard
is emitted (it cannot when the node's uses span two conditions - 3.3); and that
the cheap fix is obviously better (2.2 removes its main argument - see 3.1).

## 3. The design

### 3.1 What the two candidates are worth, after 2.2

2.41 offers two. *AND the arm's condition mask into the guard* keeps the shape
fused and needs 3.3's sharing rule. *Decline a guarded node under an arm at
compile time* is cheaper and gives the shape up with a reason instead.

That second one has to be said precisely, because 2.41's phrasing - "exclude
the node from the guarded set" - describes a wrong answer if it is read as an
emitter change. A node the *emitter* leaves out of its guarded set is not
declined; it is unguarded, and computes silently wrong on an out-of-range lane -
the bug VARKA-52 exists to prevent. The fallback, if it is ever built, lives in
the *compiler*: `dayRange` and `intBound` refuse a guarded producer or a checked
node whenever it sits under an `IfElse` arm, so the entry is residual with a
reason and no kernel runs. The emitter's guarded set is never the place to
implement a decline.

2.2 cuts the second one's argument down, and cuts it twice. It was attractive
while the story was "the user wrote a bound and we ignored it", because then a
compile-time decline at least tells them so. But the user cannot write that
bound today, so the choice is between a shape that fuses and occasionally
declines a batch, and a shape that never fuses at all. Declining every batch of
a shape that would otherwise fuse whenever no extreme row is present is a
regression for the common case, not a repair - the opposite of VARKA-78, where
declining recovered a real loss. And once VARKA-86 makes the bound expressible,
the cheap candidate is worse again for the opposite reason: it would decline the
very shape a user wrote a bound to keep.

**So this task builds the honest fix**, and the cheap one is recorded here as
the fallback if section 6's measurement says the arm mask costs more than the
declines it prevents.

### 3.2 Threading the arm context

`emitValue`'s `IfElse` arm already emits `emitCond` before the branch values, so
the condition's known-true word is in `s.kt` and its dense mask in `s.condMask`
by the time a node inside an arm is emitted (2.41 read this out and it holds).
What is missing is context, not the mask: `emitGuardCollect` does not know which
arm it is under or with which polarity.

The change is an arm context threaded through the value walk, and
`emitGuardCollect` ANDs it in beside the node's word and the epilogue mask.

Its polarity follows SQL's `CASE`, in which an unknown condition falls to
`ELSE`. So the then-arm's context is `kT` (known-true) and the else-arm's is
`NOT kT` - which is known-false *plus unknown*, not `kF`. Using `kF` for the
else arm would exclude the unknown-condition lanes that SQL routes to `ELSE`,
and a guarded node there would stop condemning a batch it must condemn. Nested
arms compose by AND of their contexts.

The context needs no stack: every condition owns its own `kt`/`condMask` slot
and those slots outlive the arms (2.4), so a nested context is the AND of the
enclosing conditions' own slots. It has one representation per body kind,
because the two bodies keep the condition differently: in the masked body it is
a word, `s.kt` and its complement, ANDed with the node's word before the
collect; in the dense body it
is a `VectorMask`, `s.condMask` and its `not()`, ANDed into the guard's mask
before `toLong`. `emitGuardCollect` already takes both a word and a mask, so the
context joins whichever side its body uses.

`NULL`-mode disposal is not touched: the blend discards the untaken arm's word,
so a `try_add` there loses nothing (2.4). Only the batch-condemning collect
takes the context.

A guarded node in the *condition itself* - `CASE WHEN add_months(d, m) < ...
THEN ... END` - is computed and needed on every lane, and its context is
unconditional: condition position counts as "outside any arm" for the rule in
3.3, and the plan says so here because the phrase "under an `IfElse`" would
otherwise be read to include it.

### 3.3 The rule that keeps sharing sound

Per node, over the walk that already collects the guarded set. 2.41 stated the
rule as a disjunction - a node used under several arms takes the OR of their
masks - and that is not implementable as written, for an ordering reason the
first draft of this plan repeated. A shared node is emitted at its first textual
use, and its guard with it. If its uses sit under two *different* conditions,
the second condition's `kT` may not have been computed yet when the node is
first emitted under the first, so the OR is not available where the guard has to
be built.

The rule this task builds is the conservative one that has no such dependency:
**a guarded node's context is narrowed only when every use of the node sits
under one and the same innermost arm chain**. A node with a use outside any arm
(condition position included, per 3.2), or with uses under different arms, or
with one use inside an arm and another bare, keeps an unqualified guard exactly
as today. That gives up the multi-arm case, which nothing in 2.3 needs, and
keeps the property that matters: no guard is ever narrower than the set of
lanes whose value can reach a consumer.

The soundness argument for the case 2.3's third shape raises - the producer in
the arm, the consumer outside it - is the same property from the consumer's
side. The consumer reads the blend. On a lane where the arm is untaken the blend
holds the other branch's value, so the producer's out-of-range day is never what
the consumer decomposes, and a guard confined to the arm's lanes misses nothing
the consumer could see.

The context adds no liveness demand: the condition's `kt` and `condMask` live
outside VARKA-70's `wordDefs`/`wordUses` bookkeeping (2.4), so the guard can load
them without a new edge in `liveWords` and without disturbing `assertWordsLive`.
Where the per-node use-context is computed is 2.4's last paragraph: a context
stack in `analyze()`'s existing traversal, read by `collectGuardedProducers`.

`SKILLS.md` records this class one level down - VARKA-32's prefix fragment had to
be keyed on the guard's extra input rather than the child alone - and this task
cites it rather than rediscovering it.

`Greatest` and `Least` are unaffected and the rule should say so where it is
written: they are validity-driven, with no untaken arm.

## 4. Files

| file | what |
|---|---|
| `VarkaLoopEmitter.java` | the arm mask threaded through `emitValue`; `emitGuardCollect` taking it; the per-node use-context disjunction in the guarded-set walk |
| `VarkaLoopEmitterSuite.scala` | 2.3's four shapes as status matrices; the shared-node and two-condition cases of 3.3 asserted to keep declining; a guarded node in condition position asserted unqualified; the `codeSize` deltas |
| `VarkaDifferentialSuite.scala` | all four shapes end to end over a fixture whose extreme rows are routed to the untaken arm, with the declined metric at zero |
| `VarkaEmitterParityBenchmark.scala` + results | section 6's A/B, since the emitted bytes move |
| `m4/PLAN.md`, this file | row 79, section 2.41's motivating example corrected per 2.2, section 9 |

## 5. Tests, and what each is for

* **The status matrix**, over 2.3's four shapes: a batch whose only
  out-of-range row is sent to the untaken arm returns 0 instead of declining -
  the whole point - while a batch whose out-of-range row is in the *taken* arm
  still declines. The second half is what fails if the arm context is built with
  the wrong polarity. A third column sends the out-of-range row through an
  *unknown* condition (a null in the condition's column), which SQL routes to
  `ELSE`: a guarded node in the else arm must still decline on that row, which
  is what fails if `kF` was used where `NOT kT` belongs (3.2).
* **The cases that must keep declining**, from 3.3, each written before the fix
  so it fails first: the guarded node used inside an arm and bare in the same
  projection (2.1's shape); the guarded node used under two different
  conditions; and the guarded node in condition position. These are the
  silent-wrong-answer tests.
* **Nested arms**, one `CASE` inside another's arm, composing by AND.
* **The differential**, all four shapes, with `numFallbackBatchesDeclined` at
  zero where today it is positive, and answers equal either way - answers cannot
  move, since only the decline route changes. The fixture is `varka_date_months`'
  shape with the extreme counts placed on rows whose `d` is on or after
  `DATE'2020-01-01'`, so the `d < DATE'2020-01-01'` condition routes every one
  of them to the arm without the guarded node.
* **Every unguarded shape byte-identical**, which is the assertion that the
  thread reached only the guards.

## 6. The measurement

`VarkaEmitterParityBenchmark`, the guarded shapes it already carries, plus 2.3's
`CASE` forms: the arm mask adds one AND per guarded node per lane group, so the
prediction is that it is inside the noise on a memory-bound loop and the win is
the declines it prevents, which the differential counts rather than times.

### 6.1 Predictions, registered before the run

1. The arm mask costs under 3% at both widths on the `CASE` shapes, and nothing
   measurable on the shapes without an `IfElse`.
2. The pinned line map and the shape hash do not move, being IR-derived; no
   shape without a batch-condemning node under an arm changes a byte, the
   parity file's existing `CASE` rows included; and `everyNode`'s bytes *do*
   change, since its two `MakeDate`s are guarded and under an arm (2.4).
3. On a fixture where every out-of-range row falls in the untaken arm, the
   declined count goes from positive to zero, and the answers do not move.

## 7. Risks

1. **Polarity.** A three-valued condition makes "not known-true" and
   "known-false" different sets, and SQL sends the unknown lanes to `ELSE`. The
   else arm's context is therefore `NOT kT`, never `kF`; using `kF` drops the
   unknown lanes from the else arm's guard and is silently wrong. 3.2 states it
   and 5's third status-matrix column tests it.
2. **The sharing rule** (2.1, 3.3), which is the one that is silently wrong if
   it is got wrong. The first draft of this plan carried 2.41's OR-of-arms form,
   which cannot be built at the point the guard is emitted; the same-arm-chain
   rule replaces it, and its three keep-declining tests are written first.
3. **Nested arms composing by AND** and the epilogue mask still being ANDed in.
4. **A guarded node under a `Coalesce`**, which compiles to `IfElse` - the rule
   must treat it as an arm like any other, and the test list says so.

## 8. Sequencing

1. This plan and the milestone row, with 2.41's motivating example corrected in
   place - done in the PR that carries the plan.
2. The failing tests: 5's three keep-declining cases and the status matrix,
   including its unknown-condition column.
3. The same-arm-chain analysis in the guarded-set walk, then the arm context
   threaded through `emitValue` and into `emitGuardCollect`.
4. The A/B, section 9, row 79.

## 9. Outcome

### 9.1 The A/B, and why it is quoted as a ratio and not as a rate

The four cases are in `VarkaEmitterParityBenchmark` (ids 960 and 961, null-free
and mixed nulls) and they run the same `CASE` over the same guarded producer
with `guardUnderArm` on and off. Measured twice, on a machine in two very
different states, the context costs:

| | null-free | mixed nulls |
|---|---|---|
| AVX-512 | 0.5% | -0.7% (on faster) |
| 128-bit | 1.4% | -0.9% (on faster) |

Two of the four are negative, which is the honest way of saying the cost is
under this benchmark's noise floor - as one mask AND per guarded node per lane
group should be. The first run, on a hot machine hours earlier, read 0.6% and
0.16% at AVX-512; the agreement across two machine states is the reason to
believe the number, since an A/B is a ratio within one invocation and a
uniformly slow machine cancels out of it.

**The absolute rates are deliberately not committed, and this section quotes no
rate.** Two regenerations of this file produced two different and mutually
inconsistent pictures of rows this task does not touch:

* On a hot machine, every vectorized row moved 3% to 16.5% in one direction -
  hand-written `DateVectorOps` kernels among them, which no emitter change can
  reach - while every scalar and per-row control held to 0.2%.
* On a cool machine, with the controls inside 0.5% and the canary clean, the
  moves became incoherent rather than uniform: `fused, depth 1` at -75.5%,
  `budget 16 (shipped)` at -54.5%, two chunked chain rows at +50.7% and +45.0%,
  the hand-written `datediff` at -11.7%, in both directions.

A -75.5% on `fused, depth 1` - a plain chain with no `IfElse` and no guard -
would be a catastrophic regression, and the emitter suite disproves it directly:
a shape with no batch-condemning node under an arm is byte-identical with the
option either way, which 5's last test asserts. So the file's absolute numbers
are not reproducing, for a reason that is neither the machine's temperature nor
this change, and committing them would rewrite fifty rows that other plans quote
with values this section cannot stand behind.

What that costs: the four new cases have no committed results row, so a later
reader cannot trace the percentages above to a file. They are recorded here as
measured, twice, with the runs' provenance in the session and the cases in the
tree ready to re-run. It is a debt and 9.3 registers it.

### 9.2 The predictions, scored

**1 holds.** The arm context costs under 3% at both widths on the `CASE`
shapes - 0.5% and 1.4% null-free, negative on both mixed-null rows - and nothing
on the shapes without an `IfElse`, which the byte-identity test makes exact
rather than
statistical: those shapes emit the same bytes with the option on or off, so
there
is nothing to measure.

**2 holds, in the half that is checkable.** The pinned line map and the shape
hash are IR-derived and did not move, and no shape without a batch-condemning
node under an arm changed a byte. The half about `everyNode`'s bytes moving is
unverified: no `codeSize` is pinned on it, so the suite cannot say, and the
file-level evidence that would have said is the one this section refuses to
trust.

**3 holds.** The differential's two shapes go from a positive declined count to
zero when the condition routes the extreme rows to the arm without the guarded
node, and still decline when it routes them into it. The answers do not move in
either direction, because only the decline route changed.

### 9.3 What moved that the plan did not list, and what is left

**A fourth node kind.** 2.3 listed three and the milestone row counted three;
`make_date` is a fourth. It is *always* in `selfGuarding` - its year-range
check,
and its invalid-date check under ANSI, both condemn through the same collect -
so
it meets this cliff like the others. Found by checking the pinned `everyNode`
fixture rather than by reasoning from the row, which is why 2.4 exists.

**The disjunction rule was not buildable.** 2.41 and this plan's first draft
said a node used under several arms takes the OR of their masks. A shared node
is emitted at its first textual use and its guard with it, so a second
condition's word may not exist at that point. 3.3 builds the conservative same-arm-chain
rule
instead, and the milestone section carries a note saying so.

**Left for later**, both in the milestone's debt register rather than here: the
parity file's absolute numbers, which two runs could not reproduce and which
want
their own investigation - the chunked 20M-row runner's JIT state depending on
case
ordering is the first thing to look at, since adding four cases mid-file is what
changed. And with it, the committed row for this task's own pair.

