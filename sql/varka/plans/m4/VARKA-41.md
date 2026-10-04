# VARKA-41: `unix_date` and `date_from_unix_date`

The smallest task in the milestone, and the only one that adds **no IR node, no
emitter code and no lane arithmetic at all**. Two compiler arms.

Read `VARKA-33.md` section 3 for general mechanics, but most of it will not
apply.

**Depends on VARKA-38** for half of the work - see section 3. The other half can
land today.

## 1. What you are building

```
unix_date(d)             DateType    -> IntegerType
date_from_unix_date(i)   IntegerType -> DateType
```

Both are the identity on the value. Spark's own implementations are, in full:

```scala
override def nullSafeEval(input: Any): Any = input.asInstanceOf[Int]
```

A date *is* a day count; these two expressions only relabel the type. There is
nothing to compute, and this task should emit nothing.

## 2. The design: unwrap, do not add a node

**Do not add `UnixDate` and `DateFromUnixDate` IR nodes.** The IR has one lane
type, the value is unchanged, and `CompiledVarkaProjection.outputTypes` already
takes its type from the *entry* expression rather than from the IR. So the
whole lowering is:

```scala
case UnixDate(child) => compileNode(child, inputs, literals, sink)
case DateFromUnixDate(child) => compileNode(child, inputs, literals, sink)
```

The IR that comes out is whatever the child compiled to, and the entry's output
type is `IntegerType` or `DateType` because that is what the Catalyst
expression says. Both are null-intolerant identities, so validity is the
child's by construction - there is nothing to alias.

Consequences worth understanding before you start, because they are what makes
this safe:

* **Neither pinned value moves**, because no rendering changes.
* **`SELECT unix_date(d)` and `SELECT d` produce the same IR and the same shape
  hash**, and therefore share an emitted class. That is correct: kernel
  identity is about lane math, and their lane math is identical. The evaluator
  allocates the output vector from `outputTypes`, which still differs.
* The interesting shape this creates is an output whose IR root is a **bare
  `ColumnRef`** - a loop that only loads and stores. That is legal and the
  emitter handles it, but it may not have been exercised before; section 4 asks
  for a test of exactly that.

If you find yourself writing an `emitValue` arm, stop: you have misread the
task.

## 3. What lands when

* `unix_date(d)` reads a **date** column, which the engine already accepts, so
  it works as soon as the arm exists.
* `date_from_unix_date(i)` reads an **integer** column, which nothing can read
  until **VARKA-38** lands. Write both arms anyway - the second one simply
  declines through the existing "non-date column" path until 38 opens it, and
  then starts working with no further change.

Say in the pull request which of the two you were able to test end to end.

## 4. The tests

1. **Compiler suite**: `unix_date(d)` compiles to a bare `ColumnRef(0)` with
   `outputTypes === Seq(IntegerType)`; `date_add(date_from_unix_date(i), 3)`
   compiles to `AddDays(ColumnRef, LiteralSlot)` - the point being that the
   relabel vanishes rather than appearing in the IR.
2. **Emitter suite**: a matrix case whose output root is a bare `ColumnRef`,
   over every length and null pattern. This is the shape section 2 says may be
   new; if it fails, that failure is the finding and belongs in the outcome
   section rather than being worked around.
3. **Differential**: `SELECT unix_date(d)` and, once VARKA-38 has landed,
   `SELECT date_from_unix_date(i)` and a mixed chain like
   `date_add(date_from_unix_date(i), 3)`.
4. **The blocking case, which is the actual argument for this task**: a
   projection like `SELECT date_add(d, 1) AS a, unix_date(d) AS b` must fuse
   **both** entries. Before this task the second entry declines and takes the
   whole projection to the row path; that is what the task is worth.

Then VARKA-33's section 4 command block, unchanged. Acceptance is the strongest
of any task here: **no pinned value moves, no committed number moves, and no
emitted bytes change for any shape that existed before.**

## 5. Explicitly out of VARKA-41

* **An IR node for either expression.** Section 2.
* **Any other identity or reinterpreting cast.** `cast(date AS int)` is not the
  same expression and is not covered by this task; if it turns out to reach the
  compiler as something else, note it rather than adding an arm.
* **Making a bare column projection fuse in general.** If `SELECT d` does not
  fuse today, this task does not change that; it only ensures the relabelled
  form is not a blocker inside a projection that has other work.
* **A relabel nested inside a larger expression.** `compileNode` fails the
  whole enclosing entry the moment any subtree declines, with no partial
  credit inside one expression tree - unlike across projection entries, where
  VARKA-12's residual classification already isolates one bad entry from its
  siblings. A relabel buried inside such a tree (`greatest(date_add(d, 1),
  unix_date(x))` where `x` cannot yet be read) takes the whole entry down with
  it today. Not tested here (found and recorded during the code review that
  followed this task, per section 6); if this shape recurs, it is a follow-up
  recipe, not a defect in this task.

## 6. Outcome

Landed as planned: two compiler arms (`case UnixDate(child) => compileNode(...)`,
`case DateFromUnixDate(child) => compileNode(...)`), no IR node, no emitter
code. The no-IR-node design survived contact with no changes - the two arms
are exactly the one-liners section 2 predicted, placed beside the identity
`Cast` arm in `VarkaExpressionCompiler.compileNode` (the other "unwrap to
child, nothing to compute" arm).

**The bare `ColumnRef` output shape worked first time**, both at the compiler
level (`unix_date(d)` compiles to `Seq(new ColumnRef(0))` with
`outputTypes === Seq(IntegerType)`) and at the emitter level (a
`checkMatrix(Seq(new ColumnRef(0)), ...)` case added to
`VarkaLoopEmitterSuite` - a loop that only loads and stores) - it is not a new
kind of failure the way section 2 worried it might be.

**As predicted in section 3**: `unix_date(d)` was testable end to end today
(its child is a date column). `date_from_unix_date(i)` still declines, through
the ordinary non-date-column path, exactly as anticipated - VARKA-38 has not
landed on this branch's base. Both arms are written and will start working
for `date_from_unix_date` with no further change once 38 does.

**One correction to the milestone doc's motivating claim.** `m4/PLAN.md`
section 2.15 frames the argument for this task as "one unsupported expression
demotes a whole projection entry to the row path" / "blocks everything around
it." That is not quite what happens today: VARKA-12's per-entry eligibility
already means a declined entry (`UnixDate`/`DateFromUnixDate` hitting
`compileNode`'s catch-all) becomes a *residual* entry, evaluated correctly
per-row via Janino, while sibling entries still fuse through the kernel - see
`VarkaExpressionCompiler`'s class doc on `compilePartial`. So
`SELECT date_add(d, 1) AS a, unix_date(d) AS b` was already CORRECT before
this task, just not fully vectorized: `b` paid a per-row Janino re-evaluation
instead of riding the same loop as `a`. Confirmed by temporarily reverting the
two compiler arms and re-reading `compilePartial`'s classification logic
rather than trusting the milestone doc's framing. The actual argument for the
task holds, but as a vectorization gain (no residual fallback for a relabel)
rather than a correctness one; where the doc's framing would be literally true
is a relabel nested *inside* a larger expression - filed as its own bullet in
section 5, since it is a gap in this task's own scope rather than ownerless
follow-up work.

The end-to-end differential added (`VarkaDifferentialSuite`, "VARKA-41:
unix_date and date_from_unix_date fuse as a pure relabel") covers all three
cases: `unix_date(d)` fused, `date_from_unix_date(i)` correctly unfused today,
and the mixed `date_add`/`unix_date` projection - the compiler-level test
proves the mixed case is *fully* fused (both entries `FusedOutput`, via
`VarkaExpressionCompiler.compile`'s all-fused special case) rather than merely
"eligible," which is the stronger claim this task is actually worth.

Neither pinned value moved, and no committed benchmark number moved - both
verified by running the suites rather than assumed: `VarkaShapeCacheSuite`'s
`everyNode` hash and `VarkaLoopEmitterSuite`'s line map are untouched from
master, and no existing benchmark shape changed emitted bytes.

## 7. Second pass: code review

`/code-review` ran against this branch (10 finder angles plus a gap sweep, max
effort) and found no correctness bugs - every finding was documentation,
process, or test-coverage. All five were addressed:

1. **Test-coverage gap, fixed.** The two new relabel arms make `compileNode`
   return a bare `ColumnRef`, the shape `compileCoalesce` and `compileValidity`
   both use as their proxy for "this operand is a bare column" - untested for
   a relabel specifically. Added `unix_date(d) IS NOT NULL` (via `If(IsNotNull
   (UnixDate(d)), ...)`) and `coalesce(unix_date(d), unix_date(d2))` to the
   task-41 compiler test, confirming both compile exactly as a bare-column
   guard would.
2. **Docs gap, fixed.** `docs/sql-varka.md`'s supported-expression list did
   not mention `unix_date`. Added a bullet; `date_from_unix_date` correctly
   stays unmentioned, since its child is still an unreadable integer column on
   this branch's base.
3. **Process gap, fixed.** The relabel-nested-inside-a-larger-expression gap
   was loose prose in this section rather than filed per `sql/varka/AGENTS.md`.
   Moved to section 5 as its own bullet.
4. **Simplification, tried and reverted, documented rather than forced.** The
   two relabel arms are identical one-liners differing only in the matched
   case class. Tried collapsing them into one arm via a Scala alternative
   pattern, `case UnixDate(child) | DateFromUnixDate(child) =>` - illegal in
   Scala, which does not allow a pattern variable to be bound inside an
   alternative (`illegal variable in pattern alternative`). A dedicated
   extractor object (`private object DateRelabel { def unapply(e: Expression)
   ... }`) would work around that, but for exactly two one-line arms it trades
   a two-line duplication for a small standalone object plus an extra level of
   indirection - not a clear win. Left as two explicit arms; worth revisiting
   if a third relabel expression arrives and the duplication triples.
5. **Latent gap, left as documented rather than fixed.** `compileNode`'s
   date-literal arm does not match a null-valued `Literal`, and the two
   relabel arms add two more recursive paths into that same match with no
   null-aware case of their own. Left as-is: `ConstantFolding` removes a null
   date literal from any real query before it reaches here, so there is
   nothing to test with real SQL, and the ghost-fallback contract already
   routes a hand-built tree that does reach it to the row engine via the
   generic decline. Documented explicitly with a comment on the date-literal
   arm instead, naming the two new paths that share the blind spot.

Also documented, not counted as a numbered finding: the `ColumnRef`-as-proxy
invariant a late-arriving finder angle raised - `compileCoalesce` and
`compileValidity`'s javadocs now say explicitly that the guard is exact only
because every `ColumnRef`-producing arm is either a real column read or a
null-intolerant identity relabel, so a future non-identity relabel would need
to be excluded from it deliberately.

Re-verified after the fixes: 94 catalyst / 128 sql-core Varka tests green at
both vector widths (unchanged counts from before the review - these fixes
extended the existing task-41 test with more assertions rather than adding
new test cases), `dev/lint-java` and `dev/scalastyle` both pass, no
non-ASCII, no line over 100 characters, no `TODO`/`FIXME` in any changed file.
Neither pinned value moved and no committed benchmark number moved, unchanged
from section 6's claim - none of these five fixes touch emitted bytes for any
shape.
