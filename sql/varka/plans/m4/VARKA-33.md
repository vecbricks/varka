# VARKA-33: `next_day(date, <literal weekday>)`

A deliberately small vocabulary task, written as a recipe rather than an essay
because it is meant to be executed by a cheap agent. Everything it needs
already exists: the mod-7 magic multiply `dayofweek` uses, the unary
null-intolerant node shape, and the five emitter switches a node type is
threaded through. Nothing here requires a design decision. If you find
yourself making one, stop and say so in the pull request instead of choosing.

## 1. What you are building

Spark's `next_day(start_date, day_of_week)` returns the first date **strictly
later** than `start_date` falling on the named weekday. `next_day(DATE
'2015-07-27', 'Mon')` is `2015-08-03`, not `2015-07-27`, even though
2015-07-27 is itself a Monday.

The reference implementation is two methods in
`sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/util/DateTimeUtils.scala`:

```scala
def getDayOfWeekFromString(string: UTF8String): Int    // line 497
def getNextDateForDayOfWeek(startDay: Int, dayOfWeek: Int): Int = {
  startDay + 1 + ((dayOfWeek - 1 - startDay) % 7 + 7) % 7
}                                                      // line 518
```

`getDayOfWeekFromString` accepts `SU`/`SUN`/`SUNDAY` and the six siblings,
case-insensitively, and throws `SparkIllegalArgumentException` on anything
else. It returns a number in `[1, 7]`.

**Scope**: the weekday argument must be a foldable expression - in practice a
string literal - and must parse. Every other shape declines to the row path.
That is the whole task; do not try to support a weekday column.

## 2. The lowering

Write `k = dayOfWeek - 1`, a compile-time constant in `[0, 6]`. The reference
formula is then `d + 1 + floorMod(k - d, 7)`, and you emit it directly:

```
w = k - d                       // wrapping int subtraction, deliberately
r = floorMod(w, 7)              // in [0, 6]  - emitFloorMod7, already exists
result = d + r + 1              // wrapping again
```

**Emit `k - d` even though it overflows near `Integer.MIN_VALUE`.** That looks
like the trap `SKILLS.md` records - *apply constant offsets after a mod, not
before* - and here the opposite is right, for a reason worth understanding
before you write any code:

* `dayofweek`'s oracle is `LocalDate`, which is exact at every int day. There,
  an overflow in the emitted arithmetic would be a *disagreement with the
  oracle*, so the fold order has to avoid it.
* `next_day`'s oracle is Spark's own `getNextDateForDayOfWeek`, quoted above,
  which computes `dayOfWeek - 1 - startDay` in plain `int` arithmetic and
  therefore **wraps**. Byte-exactness with the row engine means reproducing
  that wrap, not avoiding it.

This was checked, not assumed: reducing first - `floorMod(k - floorMod(d, 7),
7)` - disagrees with Spark on the bottom handful of int days for every one of
the seven weekdays, 28 cases in the boundary set. At `d = Integer.MIN_VALUE`
and `k = 2`, Spark answers -2147483647 and the reduce-first form answers
-2147483643. Emitting the wrap directly agrees with Spark on every case tested.
`emitFloorMod7` is full-range - its sign fixup exists precisely because
`2^32 = 4 (mod 7)` - so it is correct on the wrapped value.

Expected size: about 17 vector ops, of which about 12 are the existing mod-7
lowering. `dayofweek` is the shape to copy throughout.

`k` is a **runtime literal**, not a baked constant: it goes in a `LiteralSlot`
like `date_add`'s offset, so one emitted class serves all seven weekdays. That
is the IR's stated rule - "a chain's identity is its shape, not its constants"
- and `AddDays` is the pattern to copy. Do not add an `int` field to the
record; a reviewer will ask you to undo it.

## 3. The edits, in order

Work in a git worktree off `origin/master`, on a branch named
`varka-task-33`. Follow `CLAUDE.md`'s pre-flight checks first.

### 3.1 The IR node

`sql/catalyst/src/main/java/.../codegen/varka/VarkaVectorIR.java`

1. Add `VarkaVectorIR.NextDay` to the `permits` clause.
2. Add the record beside `WeekDay`, with a javadoc saying what it computes and
   that `offset` is `dayOfWeek - 1 + 7`:
   ```java
   record NextDay(VarkaVectorIR days, VarkaVectorIR offset) implements VarkaVectorIR {}
   ```
3. Add a case to `canonical` rendering as `(nextDay <days> <offset>)`, with
   both children recursed through `canonical` - copy `AddDays` exactly.
4. Add the matching case to `canonicalShallow`, using `lineOf.applyAsInt` for
   both children - copy `AddDays`.

Both switches are exhaustive, so the file will not compile until you have done
3 and 4.

### 3.2 The emitter

`sql/catalyst/src/main/java/.../codegen/varka/VarkaLoopEmitter.java`

Add an import for the new node beside the others (they are alphabetical), then
one case in each of these five switches. Search for `WeekDay` to find them all;
every one of them is a compile error until you do.

| method | what to add |
|---|---|
| `childrenOf` | `case NextDay n -> new VarkaVectorIR[] {n.days(), n.offset()};` |
| `Analysis.analyze` | `case NextDay n -> { requireLiteralOffset(n.offset()); analyzeOp(node, false, n.days(), n.offset()); }` - copy `AddDays` |
| `planWordRef` | `case NextDay n -> s.wordRef.get(n.days());` - the offset is a literal, so validity is the date's |
| `planSlots` | give `NextDay` its own `dowTmp` entry of **three** slots (`new int[] {slot++, slot++, slot++}`); `emitFloorMod7` uses the first two and the third holds the date across the mod |
| `emitValue` | the arm below |

The `emitValue` arm:

```java
case NextDay n -> {
  int date = s.dowTmp.get(node)[2];
  emitValue(cb, n.days(), dense, analysis, s, computed);
  line(cb, analysis, node);
  cb.astore(date);
  // w = k - d, wrapping on purpose; see section 2.
  cb.aload(date);
  cb.invokevirtual(INT_VECTOR, "neg", LANEWISE_UNARY);
  emitValue(cb, n.offset(), dense, analysis, s, computed);
  cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
  emitFloorMod7(cb, node, analysis, s);          // r = floorMod(w, 7), in [0, 6]
  // result = d + r + 1, wrapping again.
  cb.aload(date);
  cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
  cb.loadConstant(1);
  cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
}
```

Three things in there to check rather than assume:

* `LANEWISE_UNARY` may not exist in the descriptor table. If it does not, add
  it beside the others as `MethodTypeDesc.of(INT_VECTOR)` and say so in the
  pull request. Every descriptor lives in that one table by house rule; do not
  inline one at a call site. (`IntVector.neg()` does exist - that much is
  verified.)
* `emitFloorMod7` stores *its own input* in `dowTmp[0]`, which here is `w`, not
  the date. That is why the date needs the third slot of its own. If you find
  yourself wanting to change `emitFloorMod7`, you have left this task's scope.
* `emitValue` for the offset emits a broadcast of the literal. Emitting it
  after the `neg` rather than before keeps the operand order right for
  `add(Vector)`; do not reorder these two lines.

### 3.3 The compiler

`sql/catalyst/src/main/scala/.../codegen/VarkaExpressionCompiler.scala`

Add `NextDay` to the Catalyst import list and `NextDay => IRNextDay` to the IR
import list (both alphabetical), then this arm right after the `WeekDay` one:

```scala
case NextDay(start, dow, _) if dow.foldable =>
  val name = dow.eval()
  if (name == null) {
    sink.note("next_day with a null weekday", dow)
    None
  } else {
    try {
      val k = DateTimeUtils.getDayOfWeekFromString(name.asInstanceOf[UTF8String]) - 1
      compileNode(start, inputs, literals, sink).map { d =>
        new IRNextDay(d, new LiteralSlot(literals.getOrElseUpdate(k, literals.size)))
      }
    } catch {
      case _: SparkIllegalArgumentException =>
        sink.note("next_day with an unrecognized weekday", dow)
        None
    }
  }
case n: NextDay =>
  sink.note("next_day with a non-foldable weekday", n)
  None
```

The order matters: the guarded arm must come first. Declining rather than
throwing is the point - an invalid weekday is the row engine's business, and it
has two different behaviours for it depending on ANSI mode which Varka must not
try to reproduce.

### 3.4 The tests

1. **`VarkaLoopEmitterSuite.scala`** - `evalValue`'s match is exhaustive, so it
   will not compile until you add a `NextDay` arm. Write the oracle from the
   *reference formula*, not from your lowering:
   ```scala
   case n: NextDay =>
     for (d <- evalValue(n.days(), row, lits); k <- evalValue(n.offset(), row, lits))
       yield d + 1 + Math.floorMod(k - d, 7)
   ```
   Scala's `Int` arithmetic wraps exactly as the lanes do, so this oracle is
   right at `Int.MinValue` too - and it is the same expression Spark's
   `getNextDateForDayOfWeek` evaluates.
   Then add a test in the shape of the `dayofweek` one, driving all seven
   weekday offsets and the extreme days (`Int.MinValue`, `Int.MaxValue`, 0, -1,
   the 15-bit fold boundaries `32767`/`32768`/`-32768`/`-32769`), through
   `checkMatrix` at every length and null pattern.

2. **The two pinned fixtures.** Adding a node type does not by itself move the
   pinned shape hash or line map, but house convention says to extend the
   `everyNode` trees so no rendering is unguarded - so **they will move, and
   that is expected**. Add `NextDay` to the fixture in
   `VarkaShapeCacheSuite.scala` and to the one in `VarkaLoopEmitterSuite.scala`,
   run them, take the new values from the failure messages, paste them in, and
   record both old and new values in this file's outcome section. Do not
   "fix" a pinned test any other way.

3. **`VarkaExpressionCompilerSuite.scala`** - one test that
   `next_day(d, 'MO')` compiles to `IRNextDay(ColumnRef(0), LiteralSlot(0))`
   with `outputTypes === Seq(DateType)`, and one that a non-foldable weekday
   column declines (`compile(...).isEmpty`).

4. **`VarkaDifferentialSuite.scala`** - one test in the shape of the
   `dayofweek` one: a cached view of dates including nulls, pre-1970 and the
   epoch, `SELECT next_day(d, 'MO') AS a, next_day(d, 'SUNDAY') AS b FROM ...
   ORDER BY a, b`, `expectFused = true`.

## 4. What to run, and what must pass

```
build/sbt catalyst/Test/compile sql/Test/compile
build/sbt 'catalyst/testOnly *Varka*' 'sql/testOnly *Varka*'
build/sbt "project catalyst" \
  'set Test/javaOptions += "-XX:MaxVectorSize=16"' 'testOnly *Varka*'
build/sbt "project sql" \
  'set Test/javaOptions += "-XX:MaxVectorSize=16"' 'testOnly *Varka*'
build/sbt catalyst/doc
dev/lint-java && dev/scalastyle
```

The narrow-vector runs are not optional and `JAVA_OPTS` will **not** work for
them - it reaches the sbt launcher, not the forked test JVM. Use the two-step
form above.

Acceptance:

* Every Varka suite green **at both vector widths**.
* No line over 100 characters, no non-ASCII in code or comments, no `TODO` or
  `FIXME` anywhere (`sql/varka/AGENTS.md`).
* The two pinned values re-pinned and both old and new recorded here.
* No committed benchmark number moves. This task adds a node type and changes
  no existing shape, so if a committed number moves you have changed something
  you should not have - find out what before going further.

## 5. Explicitly out of VARKA-33

* **A weekday column** rather than a literal. It needs a runtime
  string-to-number conversion, which this engine has no vocabulary for.
* **A benchmark.** `next_day` is about nineteen ops of which twelve are already
  measured as `dayofweek`; there is no new question to ask, and the parity file
  is regenerated only when a number moves.
* **`last_day`, `dayofyear`, `trunc`.** Named in the same survey as `next_day`
  and each needing its own argument to enter, per the milestone 3 rule.
* **Touching `emitFloorMod7`, `GROUP_BUDGET`, or the guard machinery.** If the
  task seems to need any of them, it does not - say so instead.

## 6. Outcome

Built essentially as section 3 specified: `VarkaVectorIR.NextDay(days, offset)`,
the five emitter switch arms, the new `LANEWISE_UNARY` descriptor (it did not
exist, as predicted), the compiler arm ahead of the calendar-extraction block,
and tests in all four files section 3.4 named. The emitted `emitValue` arm,
the three-slot `dowTmp` entry, and the compiler's guarded-arm-first ordering
all worked as written. One place did not: section 3.1 step 2 instructs the
javadoc to say `offset` is `dayOfWeek - 1 + 7`, and the shipped javadoc and
runtime code both wrote `dayOfWeek - 1` (no `+7`) instead - a silent,
undisclosed deviation the first version of this outcome section did not
mention, caught by this task's own code review rather than by the agent that
made the change. The runtime code never actually used the `+7` form, so there
was no live arithmetic bug, only a gap between what the recipe said and what
this section claimed happened. See section 7 for what else the review found
and how it was addressed - the honest statement is that the recipe format
mostly held up, not that it held up "without modification."

Two places needed a judgment call the recipe left open, both flagged rather
than guessed past:

* **Where to graft `NextDay` into the two pinned `everyNode` fixtures.** The
  recipe says to extend the trees so no rendering is unguarded but does not
  say where a fifth binary node joins a tree already built from the other
  four. Wrapped the existing `Least(WeekDay, ...)` in one more
  `Least(WeekDay, NextDay)`, matching the fixture's existing style of nesting
  same-arity nodes rather than appending a sibling at the root.
* **The differential fixture's day set.** The recipe names the `dayofweek`
  test as the shape to copy, but that test's `varka_dow` view has no exact
  1970-01-01 row, and this task's own acceptance criteria asks for "including
  nulls, pre-1970 and the epoch". Built a new view, `varka_next_day`, with the
  epoch date included explicitly rather than stretching the existing fixture
  to cover something it was not built for.

One thing confirmed rather than assumed: the recipe states that an invalid
weekday "has two different behaviours... depending on ANSI mode which Varka
must not try to reproduce" without saying whether the *arithmetic* differs
between modes too, only the error handling. Checked `DateTimeExpressionUtils
.getNextDateExact` (the ANSI path): it calls the same
`DateTimeUtils.getNextDateForDayOfWeek` as the non-ANSI path, so the wrapping
formula is identical in both modes and only the invalid-weekday handling
differs - which is exactly what a compile-time-only decline needs to be true,
and it is.

**Pinned values, old to new** (VARKA-26's re-pin was the most recent prior
value for both):

| fixture | old | new |
|---|---|---|
| `VarkaLoopEmitterSuite` line map (`pinnedLineMap`) | ended `21=(dayOfWeek 1)`, `22=(dateDiff 20 21)`, `23=(weekDay 1)`, `24=(least 22 23)`, `25=(if 10 13 24)` | ends `21=(dayOfWeek 1)`, `22=(dateDiff 20 21)`, `23=(weekDay 1)`, `24=(nextDay 1 2)`, `25=(least 23 24)`, `26=(least 22 25)`, `27=(if 10 13 26)` |
| `VarkaShapeCacheSuite` shape hash | `041e35db20d62e91` | `cb7581449132ebaf` |

No committed benchmark number moved (none was expected to, per section 4's
acceptance criteria - this task adds a node type and touches no existing
shape). Not independently measured: the predicted ~17-op size, since VARKA-33
explicitly excludes a benchmark and there was no existing committed number to
compare a new one against.

All Varka suites green at both vector widths (94 catalyst, 128 sql/core,
0 failures - up from the pre-task baseline of 92 and 127 by the two new
emitter/compiler tests and the one new differential test this task added);
`catalyst/doc`, `dev/lint-java` and `dev/scalastyle` all pass; no non-ASCII,
no line over 100 characters, no `TODO`/`FIXME` in any changed file.

## 7. Second pass: code review

`/code-review` ran against this branch and returned 12 findings, most severe
first. All twelve were addressed; none were declined.

1. **Real bug, fixed.** `dow.eval()` for the weekday literal ran outside the
   try/catch meant to turn a bad weekday into a decline, so any other
   exception it raised (evaluating a computed, not just literal, foldable
   expression) crashed query planning instead of declining - a ghost-fallback
   violation. Folded the eval, the null check, and both catches into one new
   `foldWeekday` helper (mirroring `foldOffset`'s shape, which also answers
   finding 11) so there is exactly one place this can go wrong.
2. **Real bug, fixed.** The `NextDay` `emitValue` arm called `line(cb,
   analysis, node)` after emitting only its first child, so the later
   `emitValue(n.offset())` call re-tagged the rest of the bytecode to the
   literal's line. Fixed by emitting both children before the single
   `line()` call, matching `AddDays`/`SubDays`/`DateDiff`'s existing pattern.
3. **Real bug, fixed.** `weightOf()` priced `NextDay` at the flat default
   weight of 1 against `GROUP_BUDGET` despite it emitting fifteen real vector
   ops (its own subtract and two adds, plus `emitFloorMod7`'s twelve under
   the shipped `MAGIC` lowering). Added `NEXT_DAY_WEIGHT = 15`, counted the
   same way `CHRONO_WEIGHT` is.
4. **Javadoc error plus a real test gap, fixed.** The javadoc claimed
   `DateTimeUtils#getDayOfWeekFromString` returns `[1, 7]`; it returns
   `[0, 6]` (`THURSDAY = 0 .. WEDNESDAY = 6`), so the real literal
   `k = dayOfWeek - 1` ranges over `[-1, 5]`, and the negative case
   (THURSDAY, k = -1) was untested everywhere. Fixed the javadoc and added
   THURSDAY coverage to the emitter matrix, the compiler suite, and the
   differential suite.
5. **Test coverage gap, fixed.** The null-weekday and unrecognized-weekday
   decline paths had no test. Added both, plus a third the review did not
   ask for but `foldWeekday`'s fix makes newly reachable: a weekday
   expression that throws some other exception during `eval()`, proving
   finding 1 is actually fixed rather than just plausible.
6. **Docs gap, fixed.** `docs/sql-varka.md`'s supported-expression list did
   not mention `next_day`. Added a bullet matching the file's existing style.
7. **Docs gap, fixed.** `SKILLS.md`'s unconditional "apply constant offsets
   after a mod, not before" rule was not updated with the exception this
   task discovered (`next_day` must apply the offset *before* the mod,
   because Spark's own oracle wraps). Added the exception and the reasoning
   for it to that entry.
8. **Simplification, fixed.** `w = k - d` was computed as
   `date.neg().add(offset)`, needing a new `LANEWISE_UNARY` descriptor and
   the emitter's only `neg()` call. Rewritten as `offset.sub(date)` using
   the file's existing `sub` idiom - one fewer instruction, and
   `LANEWISE_UNARY` is now dead code, removed.
9. **Simplification, fixed.** The dedicated third `dowTmp` slot for `date`
   was unnecessary: `emitFloorMod7` only touches the stack top plus its own
   two scratch slots, so `date`'s second use now rides the operand stack via
   `dup`/`swap` instead of a named local, and `NextDay` shares
   `DayOfWeek`/`WeekDay`'s existing two-slot allocation. This interacted
   with finding 8's rewrite (the new op order is what makes the `dup`/`swap`
   sequence line up correctly with `sub`'s `[receiver, arg]` shape) and with
   finding 2 (both children are now emitted, in this order, before the
   single `line()` call).
10. **Plan-honesty gap, fixed.** This outcome section originally claimed
    section 3 was followed "exactly as specified... without modification,"
    but section 3.1's own instruction to javadoc the offset as
    `dayOfWeek - 1 + 7` was silently changed to `dayOfWeek - 1` in the
    shipped javadoc. Section 6 above now discloses this.
11. **Simplification, fixed.** `NextDay`'s weekday fold/decline logic was
    inline across two match arms. Factored into `foldWeekday`, alongside
    fixing finding 1 - `compileNode`'s `NextDay` arm is now a three-line
    for-comprehension matching `DateAdd`/`DateSub`'s shape.
12. **Documentation gap, fixed.** `emitFloorMod7`'s javadoc documented the
    numeric algorithm but not its `dowTmp[0]`/`dowTmp[1]` slot contract with
    callers. Added a paragraph stating the contract explicitly, naming
    `NextDay` as the caller that needs to keep its own copy of a value the
    call does not preserve.

Re-verified after the fixes: all Varka suites green at both vector widths,
`dev/lint-java` and `dev/scalastyle` pass, no non-ASCII, no line over 100
characters, no `TODO`/`FIXME` in any changed file. The `dowTmp` slot-count
change (finding 9) and the weight change (finding 3) are both compile-time
constants with no effect on any node's *value*, only on temp-slot count and
loop-method grouping respectively - re-run to see whether either pinned
fixture moved again, and the result is in section 6's pinned-values table
above if so, otherwise this line stands as the record that they were checked
and did not.
