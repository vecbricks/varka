# VARKA-38: a day offset that is a column, not a literal

Another recipe for a cheap agent, in the shape VARKA-33 established - but not a
new expression. Every guard this task moves already exists to enforce
milestone 1's scope ("foldable integer day offsets"), and the lane math it
needs is already emitted. The work is opening a door, not building a room.

Read `VARKA-33.md` section 3 for the general mechanics. This task depends
on nothing and can start immediately.

If you find yourself making a design decision, stop and say so in the pull
request instead of choosing.

## 1. What you are building

`date_add(d, n)` and `d + n` where `n` is an **`IntegerType` column** rather
than a literal, and the `date_sub`/`d - n` counterparts. Both spellings reach
the compiler as `DateAdd(date, days)` - `d + n` through
`BinaryArithmeticWithDatetimeResolver:104` - so there is one node to teach, not
two.

Today all four of these decline, and the reason worth understanding before you
start is that **Varka cannot read a non-date column at all**. Foldability is
the visible guard; the column type is the real one. That is why this task
touches the evaluator as well as the compiler.

`d + INTERVAL '3' DAY` already fuses and is not part of this task: the analyzer
rewrites it to `DateAdd` with a folded literal, which works today. An interval
*column* is out - see section 6.

## 2. The four guards

Each of these must move, and each declines for a different reason. Find all
four before changing any of them:

| # | where | what it does today |
|---|---|---|
| 1 | `VarkaExpressionCompiler`, the leaf arm | accepts `BoundReference` only when `dataType == DateType` |
| 2 | `VarkaExpressionCompiler.foldOffset` | requires `Literal(value: Number, _)`, notes "day offset is not a foldable literal" |
| 3 | `VarkaLoopEmitter.analyze`, `requireLiteralOffset` | **throws** unless the offset is a `LiteralSlot` |
| 4 | `VarkaKernelEvaluator.isArrowBacked` | requires every referenced column to be an Arrow `DateDayVector` |

Guard 3 throws rather than declines, which tells you what it is: a scope guard,
not a capability guard. `emitValue`'s `AddDays` arm is already
`emitValue(days); emitValue(offset); add(Vector)` - vector-vector lane math
that does not care where the offset came from.

## 3. The trap: validity

**This is the part of the task that can produce wrong answers rather than a
decline, and it is the reason this recipe exists.**

`VarkaLoopEmitter.planWordRef` reads:

```java
case AddDays n -> s.wordRef.get(n.days());
case SubDays n -> s.wordRef.get(n.days());
```

The result's validity is aliased to the *date* child alone. That is correct
today because the offset is always a literal and a literal is always valid. The
moment the offset can be a nullable column it is **wrong**: `date + NULL` must
be NULL, and this would produce a non-null row holding whatever the null lane
contained.

The fix is two characters short of trivial, and it is deliberately the same
shape `DateDiff` already uses:

```java
case AddDays n -> andRef(s.wordRef.get(n.days()), s.wordRef.get(n.offset()));
case SubDays n -> andRef(s.wordRef.get(n.days()), s.wordRef.get(n.offset()));
```

**This is a no-op for every shape that exists today.** A `LiteralSlot`'s word
reference is `WORD_ALL_TRUE`, and `andRef(a, WORD_ALL_TRUE)` returns `a` by its
first two lines. So the emitted bytes for a literal offset are unchanged, which
is what makes section 5's "nothing existing moves" acceptance criterion true.
Make this change first, before anything else, and confirm the suites are still
green with it alone - that isolates it from everything after.

## 4. The edits

### 4.1 The emitter

* `planWordRef`: section 3.
* `Analysis.analyze`: delete the two `requireLiteralOffset` calls, and delete
  the method if nothing else calls it. Keep the `analyzeOp(node, false,
  n.days(), n.offset())` calls exactly as they are - they already register the
  offset as a child, which is what makes column tracking and CSE work.

### 4.2 The compiler

* The leaf arm becomes `case br: BoundReference if br.dataType == DateType ||
  br.dataType == IntegerType`. Nothing downstream needs to know which it was:
  the IR has one lane type, the analyzer has already type-checked the tree, and
  output types are tracked per entry rather than per input.
* `foldOffset` stops being the only path. `DateAdd`/`DateSub` should try the
  folded literal first - keeping today's `LiteralSlot` shape, so existing plans
  and their cached kernels are untouched - and fall back to
  `compileNode(days, ...)` when it is not foldable.
* **Decline `ShortType` and `ByteType` offsets.** `DateAdd.inputTypes` is
  `Seq(DateType, TypeCollection(IntegerType, ShortType, ByteType))`, so a short
  or byte column arrives with **no cast** and its Arrow vector is 2 or 1 bytes
  wide - which an int32 lane load would read as garbage. This is the second way
  this task can produce wrong answers rather than a decline. The leaf arm above
  gets this right by naming `IntegerType` exactly; do not broaden it to
  "any integral type".

### 4.3 The evaluator

* `isArrowBacked`: accept an Arrow `IntVector` beside `DateDayVector`, with the
  same `getValueCount() == input.numRows()` check.
* `extractMorsel(ddv: DateDayVector, len: Int)` takes the concrete type; widen
  it to `BaseFixedWidthVector`. Both vectors are four bytes wide with the same
  buffer layout, so the body does not change. Both types are already imported.
* Check the call site at `:504`, which casts to `DateDayVector`.

### 4.4 Docs

`docs/sql-varka.md` says the surface is over date columns "and foldable integer
day offsets". Both halves of that sentence are now wrong; fix it, and say that
an integer *column* may be an offset while short and byte columns decline.

## 5. The tests, and what must pass

1. **Emitter suite**: a matrix case with a two-column shape - `AddDays(col0,
   col1)` - over every null pattern, including nulls in the offset column and
   nulls in both. The reference evaluator already handles this shape correctly
   (`for (d <- ...; o <- ...) yield d + o` is null-propagating), so it is the
   emitted side being tested. **A case with a null offset and a non-null date
   is the one that fails if section 3 was skipped.**
2. **Compiler suite**: `date_add(d, i)` compiles to `AddDays(ColumnRef, ColumnRef)`
   with two input ordinals; `date_add(d, 3)` still compiles to a `LiteralSlot`
   as it does today; a `ShortType` offset column declines.
3. **Differential**: `cacheDates` already gives you a table with a date column
   `d` and an int column `i`, so `SELECT date_add(d, i), d + i, date_sub(d, i)`
   is a one-line test. `i` there is **not nullable**, so add a second view with
   a nullable int column - the null-offset case is the whole point.
4. **A decline test**: an interval column still does not fuse (section 6).

Then VARKA-33's section 4 command block, unchanged.

**Acceptance is unusually strong for this task, and you should hold yourself to
it**: no new IR node type, so **neither pinned value moves**, and no existing
emitted shape changes, so **no committed benchmark number moves**. If a pinned
hash or a parity number moves, something in section 4 went further than it
should have - find out what before going on.

## 6. Explicitly out of VARKA-38

* **An interval column.** `DayTimeIntervalType(DAY, DAY)` is physically *long*
  microseconds, so extracting days needs int64 lanes (VARKA-29) and a division
  by 86400000000 on long lanes, where there is no multiply-high either. It
  needs its own range-narrowing argument and is not this task.
* **Short and byte offset columns** - section 4.2, and they must decline.
* **Reading an integer column anywhere else.** This task makes `IntegerType`
  loadable, which is a bigger door than it needs; do not open it wider by
  adding integer comparisons, integer arithmetic, or an integer output that
  did not already exist. Those are their own tasks with their own arguments.
* **`datediff` returning an integer column that another kernel reads** - that
  already works inside one chain and is unrelated.

## 7. Outcome

Built as planned: the four guards moved (compiler leaf arm, `foldOffset`
replaced by a folded-literal-then-column fallback, `requireLiteralOffset`
deleted from the emitter's analyze pass, `isArrowBacked`/`extractMorsel`
widened to `IntVector`/`BaseFixedWidthVector`), `date_add(d, i)` and
`date_sub(d, i)` compile to a two-column `AddDays`/`SubDays`, a foldable
offset still compiles to a `LiteralSlot` unchanged, and `ShortType`/`ByteType`
offset columns and a non-foldable interval column all still decline. No
pinned value moved and no committed benchmark number moved, both as
predicted - verified rather than assumed: for a literal offset,
`s.wordRef.get(offset)` is `WORD_ALL_TRUE`, so `andRef` always returns the
`days` reference and `s.ownWord` never gains the node, so the new
`emitAndWord` branch never emits for any existing shape.

**Section 3's trap was real, and the recipe under-stated it.** It named the
`planWordRef` fix correctly, but that fix alone is not enough: `planWordRef`
only decides *whether* a node needs its own validity word slot (`s.ownWord`);
the slot is filled by an `emitAndWord` call written into `emitValue`, which
`DateDiff`'s arm already has and `AddDays`/`SubDays`'s arms did not. The
recipe quoted only the `planWordRef` half of `DateDiff`'s pattern, not the
`emitValue` half - "the same shape `DateDiff` already uses" was true but
incomplete as a pointer. Missing this would not have failed loudly: the
kernel would have compiled and run, `s.ownWord` would have held a slot,
and that slot's local would simply never be written before its use in the
epilogue mask, an uninitialized-local situation the class-file verifier
should reject at load time (or, in the worst case, silently reads a stale
value). The column-offset validity test in `VarkaLoopEmitterSuite` was
written specifically to catch exactly this, and the first failure surfaced
there before the change ever reached a build with the emitAndWord call.

**Section 4.2's "fall back to `compileNode(days, ...)`" was the recipe's real
mistake, and this one *did* fail loudly - as a change in unrelated tests, not
as a wrong answer.** `compileNode` is the one function every compiler arm
routes through for every child position - `Compare`, `DateDiff`,
`Coalesce`, `Greatest`, all of it - so widening its shared `BoundReference`
leaf arm to accept `IntegerType` did not just legalize `date_add`'s offset:
it legalized an `IntegerType` column *anywhere* `compileNode` is called,
including as a `Compare` operand. `GreaterThan(i, Literal(5))` and
`DateDiff(x, i)` (i an int column) would have started fusing as plain
integer comparisons and mixed date/int subtraction - correct arithmetic,
since int32 lanewise ops do not care what the bits mean, but a capability
VARKA-38 never asked for and section 6 explicitly rules out ("do not open it
wider"). Two catalyst-suite tests caught it immediately, because they used a
bare int column as their "this declines" example and started compiling
instead. The fix was a dedicated `compileOffset` fallback that pattern
matches `BoundReference` directly rather than recursing through
`compileNode`, so the general leaf arm never moved - only the exact
grammatical position (`DateAdd`/`DateSub`'s offset) accepts an int column.
Three more tests across `sql/core` (`VarkaColumnarWriteSuite`,
`VarkaKernelEvaluatorSuite`, `VarkaEndToEndSuite`) had the same "bare int
column declines" assumption baked into their example query and needed the
same swap, to `i + 1` (still non-foldable, still not a bare column).
Between the two failure rounds, five pre-existing tests needed their decline
example changed - a normal and expected consequence of legalizing a shape
that used to be the canonical "this doesn't fuse" example, not a sign
anything was wrong with the fix itself.

**Review pass, addressing 7 findings.** A `/code-review` run against this
commit found seven issues, all fixed here:

1. **`requireLiteralOffset`'s deletion was too broad, and is now narrowed
   rather than removed.** The recipe's own decision to delete the check
   outright (section 4.1) turned out wrong: it left the emitter with no
   fail-fast guard against a hand-built `AddDays`/`SubDays` whose offset is
   neither a `LiteralSlot` nor a `ColumnRef` - not reachable through
   `VarkaExpressionCompiler` today, but a real defense-in-depth loss for any
   future IR producer. Replaced with `requireOffsetShape`, accepting either
   shape and rejecting anything else, restoring the class javadoc's
   out-of-shape-IR list to match.
2. **The evaluation-order question in `compileNode`'s `DateAdd`/`DateSub`
   arms turned out to have a right answer already on the books.** The review
   flagged that compiling the date child before the offset (this diff's
   choice) changed which of two independently-true decline reasons wins when
   both are unfusable, versus the pre-task-38 offset-first order. Reverting
   to offset-first looked like the safe fix and was tried first - but it
   broke two passing tests (`VarkaExpressionCompilerSuite`'s literal-slot and
   column-ordinal ordering tests), because `VarkaExpressionCompiler` already
   documents a house rule for exactly this (`CaseWhen`'s comment: "input
   ordinals and literal slots register deterministically in reading order").
   Child-before-offset is what that rule requires, once an offset can be a
   column rather than always a foldable literal with nothing to register.
   Kept the child-first order, documented why, and added a test
   (`"with two independently unfusable operands, the child's reason is
   reported"`) pinning the decline-priority behavior as intentional rather
   than leaving it as an unrecorded accident of evaluation order.
3. **`andRef`/`emitAndWord` in the emitter's `AddDays`/`SubDays`/`DateDiff`
   arms were hand-copied a second and third time.** Factored the
   emit-both-children-then-AND-their-validity-words shape into
   `emitAndValidatedOp`, called by all three arms, so the exact silent
   miscompilation this task's own development hit once (a dropped
   `emitAndWord` call, caught only by a dedicated test) becomes structurally
   harder to reintroduce on a fourth binary date-arithmetic node.
4. **`docs/sql-varka.md`'s EXPLAIN section quoted the wrong decline-reason
   text** for a `ShortType`/`ByteType` offset column, and had dropped the
   pre-existing "not a foldable literal" mention entirely. Corrected to quote
   the actual strings `compileOffset` emits.
5. **No test exercised a column-offset `date_add` inside a filter
   predicate**, only inside a projection. Added one
   (`"a column-offset date_add fuses inside a filter predicate too"`),
   confirming `VarkaFilterEvaluator`'s inherited widening actually works on
   the mask-kernel path, not only the projection path.
6. **`compileOffset`'s `IntegerType` leaf duplicated `compileNode`'s
   `DateType` leaf's one-line `ColumnRef`-interning expression.** Factored
   into a shared `columnRef` helper both call.
7. **A stale comment** in `VarkaLoopEmitterSuite` pointed at a test title
   that did not exist. Fixed to name the real test.

Re-verified end to end after the fixes: 96 catalyst / 131 sql-core Varka
tests green at both vector widths, `dev/lint-java` and `dev/scalastyle` both
pass, and the "no pinned value, no committed number" claim above still holds
- none of these seven fixes touch emitted bytes for any shape besides the
new column-offset one.
