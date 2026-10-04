# VARKA-147: the 64-bit dividend bound is stated and not enforced

*Scoped 19 September 2026 (milestone 5 section 2.83, row 147) from VARKA-88 step
3's review; planned 22 September 2026.*

## 1. The question

`VarkaVectorIR.ConstDivide` documents a precondition and names the constant for
it - a dividend whose magnitude is under `EXACT_DIVIDEND_BOUND`, which is 2^52 -
and nothing anywhere checks it. There is no constructor check, no analysis
check and no emitted guard, and `VarkaRangeAnalysis.range` answers UNKNOWN for
every non-INT lane, so the lattice could not discharge the obligation even if
something asked it to. The node's own javadoc says as much, in a paragraph
headed "Nothing checks the bound".

Past the bound the two lowerings fail differently and neither fails safely. The
conversion form through double lanes is off by one, which is the ordinary
rounding story. The magic-number form a host without intrinsified converts
takes is not: bit 52 of the dividend is the low bit of the exponent field its
`0x4330000000000000` identity depends on, so the OR drops it and the value read
back is the dividend modulo 2^52 - wrong by about 4.5e12 for a nanosecond
divisor rather than by one.

**Nothing computes a wrong answer today, and that is the reason to do this
now rather than later.** Every `ConstDivide` the compiler builds over a `LONG`
child is over nanoseconds of day or a quotient of one: `hour`, `minute` and
`second` through `narrowed`, `time_to_millis` and `time_to_micros`, and
`remainderOfSixty`'s inner division. A `TIME` value is under 2^47 by its type,
so each of those is structurally under the bound, and `VarkaTimeCompiler` says
so in a comment at the site. The gap is that the next caller inherits no such
protection. VARKA-103's `extract(DAY FROM dt)` divides signed microseconds
across the whole int64 and is milestone 6's; when it is taken up, the safe
thing must already be the easy thing.

So this task is not a bug fix. It is closing an obligation that is currently
discharged by a comment, before a caller arrives who reads the comment and not
the range.

## 2. The change

The row's deliverable read "a per-batch guard at the long lane declining an
out-of-range dividend to the row engine, on `emitRangeGuard`'s two-compare
pattern and a new status bit beside `STATUS_CHRONO_RANGE`". Two parts of that
are reconsidered here, and the reasons are below: the guard should not be
unconditional, and the status bit should not be new.

### 2.1 Three places the obligation could live

| arm | where the check is | cost where the bound is provable | bytes |
| :--- | :--- | :--- | :--- |
| A | a `GuardedRange` wrapped under every long-lane `ConstDivide` | two compares and a mask OR per lane group, always | moves on every `TIME` shape |
| B | a bound carried on the node, checked in its constructor | none | none today |
| C | `Analysis` refuses a long-lane `ConstDivide` that carries no bound | none | none today |

**Arm A is what the row proposed, and it taxes the shapes the milestone
ships.** Every `TIME` extract would pay a guard for a range its type already
guarantees, on the rows `VARKA-118.md` quotes. It also moves
`emitted_bytes.json` for each of them, which is a change whose subject is a
different lane - the objection section 2.84 raises against correcting the group
budget in the same breath.

**Arm B is the house pattern, written down one record above the gap.**
`BoundedDivide(child, divisor, bound, multiplier, shift)` exists at the int
lane for exactly this: the caller states the bound it proved, the constructor
proves exactness over that bound before the node exists, and its javadoc
finishes with "a caller that cannot prove the bound guards below it with
`GuardedRange` or uses `ConstDivide`". The long lane simply never held anyone
to the second half of that sentence.

**Arm C makes the omission a compile error rather than a silent one.** A
`ConstDivide` over a `LONG` child with no bound is refused where the analysis
walks it, with a message naming the node, so a compiler arm that forgets is a
failing test and not a wrong number.

**B and C together, then**, with A as the discharge a caller reaches for when
it has no structural bound. That is the combination in which no shape pays for
a range its type guarantees and no shape can be emitted unguarded by accident.

### 2.2 What is built

1. **`ConstDivide` carries the bound at the long lane.** The record becomes
   `ConstDivide(child, divisor, dividendBound)`, where `dividendBound` is an
   exclusive bound on the dividend's magnitude that the caller undertakes. At
   the int lane it is `1L << 31`, which every int32 satisfies, and the
   constructor fills it so no int-lane call site changes. At the long lane the
   constructor refuses a bound above `EXACT_DIVIDEND_BOUND`, which is where
   the obligation becomes unforgeable: a node holding a bound it cannot honour
   does not exist.
2. **The compiler states the bound it proved.** `VarkaTimeCompiler`'s five
   sites pass `NANOS_PER_DAY`, which is the type's own bound and the comment
   they already carry; `remainderOfSixty` passes the quotient's bound derived
   from its child's, so the derivation is arithmetic rather than a second
   assertion. A site with nothing to prove wraps its child in
   `GuardedRange(child, -(EXACT_DIVIDEND_BOUND - 1), EXACT_DIVIDEND_BOUND - 1)`
   and passes `EXACT_DIVIDEND_BOUND`; none does today, and VARKA-103 will be
   the first.
3. **`Analysis` refuses an unbounded long-lane division.** The walk that
   already validates the DAG checks that a `LONG`-child `ConstDivide`'s bound
   is at most `EXACT_DIVIDEND_BOUND`, and refuses with a reason naming the
   node's canonical form. This is belt over braces - the constructor refuses
   the same shape - and it is worth both, because the constructor guards
   construction while the analysis guards a tree that arrived any other way,
   which is what the fuzzer does.
4. **The canonical form does not carry the bound** - see 2.4, which corrects
   what this item first said.

### 2.3 The status bit stays `STATUS_CHRONO_RANGE`

A guard that fires reaches the caller through `emitStatusReturn`, which reduces
one accumulator to one constant. A second reason would need a second
accumulator, a second reduction and a second return path in every guarded body,
which moves the bytes of every kernel that guards anything - for a distinction
the query plan already makes plainly, since a dividend guard appears only under
a `ConstDivide` and a calendar guard only under a calendar node. `GuardedRange`
already reports through this bit for `TIME + INTERVAL`, which is the same kind
of decline for the same kind of reason. **The row's "new status bit" is
therefore not built**, and this section is the record of that decision rather
than a silent omission.

### 2.4 Correction, 22 September 2026: the bound stays out of the shape key

Item 4 of 2.2 first read "the canonical form carries the bound, so two
divisions that differ only in what their caller proved are different shapes and
cannot share a kernel", by analogy with `GuardedRange`, which does hold its
bounds inside the IR. Checking it against `canonical` before building it shows
the analogy is the wrong one, and predictions 1 and 3 would have failed by
construction: `canonical` is a hand-written switch, so a new record component
reaches the shape hash only if someone puts it there, and putting it there
renames every committed key that contains a `ConstDivide`.

The distinction the first version missed is whether a bound is an *emission*
parameter or a *compile-time obligation*.

- `GuardedRange(child, lo, hi)` emits two compares against `lo` and `hi`. Two
  nodes with different bounds emit different bytes, so they must be different
  shapes, and its javadoc's reasoning - a placement carried beside the IR would
  let one shape be served the other's guards - is about code that exists.
- `BoundedDivide(child, divisor, bound, multiplier, shift)` renders as
  `divb:60/3600`, and its bound belongs there for the same reason: the search
  derives the multiply-and-shift pair *from* the bound, so two bounds are two
  different pairs of emitted constants.
- `ConstDivide`'s dividend bound emits nothing at all. The guard, where one is
  needed, is a separate `GuardedRange` node that the shape key already
  separates. Two trees differing only in what their caller proved emit
  identical bytes, and splitting the shape cache between them would buy
  nothing and cost a second compilation.

So the bound is a record component the constructor and the analysis read, and
the rendering is left exactly as it is. The test that pins this is the one
`VarkaShapeCacheSuite` already runs over a key using every node type: if the
rendering moves, it fails, and this task expects it not to.

## 3. Predictions, registered before the run

1. **No committed hash in `emitted_bytes.json` moves.** Every current long-lane
   division proves its bound structurally, so no guard is emitted and the bytes
   are what they were. This is the prediction that says the design did what it
   was chosen for; if it fails, arm B was implemented as arm A.
2. **No row of the `TIME` surface moves outside its band.**
   `TimeSurface-jdk25-band.txt` puts `hour(t)` at tier 0; a regeneration after
   this task reads within 3% on the extract rows and within their own tiers
   elsewhere. No benchmark is regenerated for this task unless prediction 1
   fails, in which case both are.
3. **The shape hash changes for no existing shape**, since every int-lane
   `ConstDivide` keeps the bound the constructor fills, and the long-lane
   sites keep theirs.
4. **A long column past the bound declines the batch and the row engine
   answers it**, under both lowerings - the conversion form and, with
   `useAVX` forcing the magic form, that one - with the same rows out of both
   paths and the decline visible in the metrics.
5. **The fuzzer refuses to build an unbounded long-lane division** rather than
   emitting one: its grammar draws `ConstDivide` over long children, so arm C
   is exercised by a test that already exists rather than by one written for
   it.

## 4. Verification

- `VarkaEmitterDivisionSuite` and `VarkaEmitterLongLaneSuite`, extended with
  the decline case of prediction 4 at both lowerings and a construction test
  for the refused bound.
- `VarkaIrFuzzSuite`, for prediction 5.
- `VarkaEmittedBytesSuite`, `VarkaShapeCacheSuite` and `VarkaCoverageSuite`,
  unchanged, for predictions 1 and 3; the coverage table's `TIME` rows keep
  their entries and their declines.
- `VarkaExpressionCompilerSuite` for the bounds the compiler now states.
- `sql/varka/plans/verify_double_division.py` re-run, since it is the script
  that derives the two lowerings' bounds and the node now names one of them in
  its signature.
- The `sql/core` differential over `TIME`, for prediction 4 end to end.
- `dev/scalastyle`, `dev/lint-java`, the 100-column and non-ASCII scans.

## 5. Outcome, 22 September 2026

Built as 2.2 describes it, with 2.4's correction and four things the plan did
not look for. No emitted byte moved and no shape hash changed, which is what
the design was chosen for.

**The predictions.**

1. *No committed hash in `emitted_bytes.json` moves.* **Held**, after one
   detour worth recording: an early version of the fuzzer's arm clamped the
   bound it propagates to its children, which changed the corpus the grammar
   draws and moved seven `fuzz_long` blocks. The clamp was on the wrong number.
   The node's stated bound needs the clamp and the propagated one does not, and
   with that separated the oracle is untouched.
2. *No row of the `TIME` surface moves outside its band.* **Not measured, and
   not needed**: prediction 1 held, so no kernel changed and there is nothing
   for a benchmark to see. No results file is regenerated.
3. *The shape hash changes for no existing shape.* **Held.**
   `VarkaShapeCacheSuite`'s pinned key over every node type is unchanged, which
   is 2.4's decision working.
4. *A long column past the bound declines the batch under both lowerings.*
   **Held**, in a new `VarkaEmitterLongLaneSuite` case over a guarded division
   at four combinations of lowering and width, in a full lane group and in the
   masked tail, with the quotient checked at both signs where the guard holds.
5. *The fuzzer exercises the refusal rather than a test written for it.*
   **Held differently than expected**: the grammar had tracked each subtree's
   bound all along and simply never told the node, so the arm states what it
   already knew and nothing was refused at all. The refusal is exercised by the
   division suite instead.

**What the task found that it did not look for.** All four are lessons about
the shape of this kind of obligation rather than about this node.

1. **The analysis check 2.2 item 3 asked for was dead code.** A record cannot
   be built except through its constructor, so "refuse a long-lane division
   that carries no bound" could never fire there. What replaced it is not
   subsumed: when a caller discharges the obligation by guarding, the guard has
   to deliver the bound the caller then states, and that is a relation between
   two nodes rather than a property of one. It is the check that caught the
   off-by-one in the fuzzer's arm.
2. **The bound is part of the record's equality, so it must be derived from
   the tree and not written at the call site.** Two equal subtrees carrying
   separately-written bounds would stop being one common subexpression, and
   the emitted bytes would move for a reason a reader would never guess. That
   is why `remainderOfSixty` takes a `ConstDivide` and derives its child's
   bound from it, in the compiler and in both suites that mirror it.
3. **A sentinel of zero collides with a real bound.** A caller that divides a
   bounded value far enough arrives at zero, which the fuzzer does; with zero
   as the "unstated" marker the node reported a missing bound where one had
   been given. The sentinel is negative.
4. **The two bounds count differently and the boundary is where that shows.**
   The fuzzer's bound is the widest magnitude a value takes, inclusive; the
   node's is one the value stays under. A guard from `-b` to `b` admits `b`
   itself, so a claim of `b` over it is one short - which is exactly the shape
   the new analysis check refuses, and the first thing it refused.

**One deviation from the row.** Its deliverable asked for a new status bit;
2.3 records why the existing one is used instead, and the decline is visible in
the metrics either way.

Nothing here became a new milestone row: every finding is about this change and
is recorded above.

## 6. Explicitly out of this task

- **Extending `VarkaRangeAnalysis` to the long lane.** The lattice answering
  UNKNOWN for every non-INT lane is what makes a caller-stated bound the right
  shape here, and teaching it the long lane is a larger task with its own
  payoff (it would discharge bounds the compiler currently proves in prose). It
  belongs beside VARKA-64's statistics-directed bounds, in milestone 6.
- **A second status bit, or any per-reason decline channel.** Section 2.3
  records the decision and its reason; if a later task needs to tell two
  declines apart in a metric, it pays for the second accumulator then, with the
  need as its argument.
- **VARKA-103's `extract(DAY FROM dt)`**, which is the first caller that will
  have to guard rather than prove. It is milestone 6's (`m8/SCOPE.md`
  item 39) and this task is what makes its guard one line.
- **The int lane's own bound.** Every int32 dividend converts exactly, so the
  int lane has no precondition to enforce and the filled-in bound there is a
  formality that keeps one record rather than two.
