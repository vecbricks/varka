# Task 84: one value-range lattice, instead of two overlapping analyses

*Milestone 5, section 2.15. Opened 8 September 2026 from task 63's review; planned
15 September 2026 as the first task of the milestone's spine after the sync (117).*

## 1. Where this came from

Two traversals in `VarkaExpressionCompiler` compute overlapping facts about what a
node can hold. `dayRange` (line 1404) answers "which epoch days can this subtree
produce", for admitting a calendar node's child; `intBound` (line 940) answers "how
large can this int be in absolute value", for taking the check off a checked
operation. They duplicate the literal-slot lookup, they disagree about what a
runtime guard proves, and `intBound`'s `datediff` arm has to call `dayRange` with
`guarded = false` to bridge them. Task 63's review found three wrong answers in
the compiler and two of them sat in that seam: a bound that assumed the date
contract for operands a literal shift had pushed out of int range, and nested
bounds combined with wrapping `Long` arithmetic, so a product past 2^63 came back
small and positive and "proved" a checked operation safe. Both were fixed by hand
- `exactly` and `withinInt` are those fixes - and nothing prevents the next arm
from forgetting either discipline, because there is no place where the
discipline lives.

Milestone 5 makes this urgent rather than tidy. Task 85 parameterises the emitter
on a lane, and the lane descriptor has to carry an interval type; every 64-bit
decision after it is a range query - whether a `TIME` division is exact, whether
an interval extract needs its bound, whether `make_time`'s multiply keeps its
check, whether `date - date` can widen unguarded. Writing a third bound function
for `long` beside these two would give the new lane the bugs the review found.
The owner confirmed 84 before 85 on 8 September for exactly that reason.

## 2. The admission check, done

**What the two functions actually compute, read rather than remembered.**

`intBound` returns `Option[Long]`, a magnitude: a literal is its absolute value;
the calendar fields are fixed constants - `year` 40 000, `month` 12, `dayofmonth`
31, `quarter` 4, `dayofyear` 366, `weekofyear` 53, `dayofweek` 7, `weekday` 6,
`dayofweek_iso` 7; `datediff` is the widest difference of its operands' day
intervals, both asked *unguarded* and refused unless both fit int32; `IntArith`
combines its operands' magnitudes with `multiplyExact` for `MUL` and `addExact`
otherwise, returning `None` on `Long` overflow; `IntNeg` passes its child through;
everything else - a column, a date-valued node used as an int - is `None`. Its
one consumer that matters is `cannotOverflow`, which builds the candidate
`IntArith` and asks whether its magnitude stays at or under `Int.MaxValue`.

`dayRange` returns a `DayRange` - `Bounded(lo, hi)` or `Unknown` - under a
`guarded` flag with no default: a `ColumnRef` is the contract range; a literal is
itself; a literal day shift moves the interval by exactly its value; `next_day`
by 1 to 7; `add_months(n)` by 28n to 31n in whichever order, and a column month
count by 31 times the emitter's `MONTH_ARITH_MIN/MAX_MONTHS`; `last_day` by 0 to
30; `trunc` by -365 to 0; `make_date` is the whole years of the narrow range;
`GuardedDay` is the narrow range regardless of its child; `ThursdayOf` is -3 to
3; `greatest`, `least` and `IfElse` take the hull; anything else is `Unknown`. A
*column* day offset is the crux: with `guarded` on it answers the narrow range on
the strength of the runtime guard a calendar consumer arms, with it off it answers
`Unknown`. `guardsBelow` re-arms the flag under a node that is itself a calendar
consumer (`isChrono`'s set plus `AddMonths`), so `datediff(last_day(date_add(d,
i)), d2)` is bounded although `datediff` arms nothing. Its consumers are
`admitCalendar` (asymmetric: `NARROW_MIN_DAYS` below, `NARROW_DECOMPOSE_MAX_DAYS`
above), `rearm`, and `intBound`'s `datediff` arm.

**The callers a replacement must serve**, by name (line numbers rot with every
merge; `grep -n` for these): `intBound` is called from the three year-month
interval arms of `compileNode` (`Add`, `Subtract` and `UnaryMinus` over
`YearMonthIntervalType`, each deciding `checked`), from `compileMonths`, from its
own `IntArith` and `IntNeg` arms, and from `cannotOverflow`; `dayRange` from
`intBound`'s `datediff` arm (twice, `guarded = false`), from its own `shifted`,
`hull` and `columnShifted` helpers, from `rearm` (once, `guarded = true`) and from
`admitCalendar` (once, `guarded = true`); `admitCalendar` from the two week arms of
`compileNode` (over a `ThursdayOf`) and from `calendarInput`. The runtime-bound
registry - `bound(ordinal, lo, hi)`, `VarkaInputBound`, `inputBounds` - is a
separate thing: it records what the emitted guards *enforce*, and the analysis
only *reads* that promise. It stays as it is.

**The oracles already exist, which is what makes this refactor safe to attempt.**
`VarkaExpressionCompilerSuite`: 91 tests (`grep -cE '^\s*(test|gridTest)\('`),
seven of them on "checked int multiply whose operands do not rule out overflow",
the rest asserting decline reasons, guards and ranges. `VarkaCoverageSuite`: every
row of the coverage table compiles, and `coverage.json` is byte-compared, so any
row whose classification moved fails it. `VarkaDifferentialSuite`: the fusion
classification of every committed query. Nothing in this task is measured by a
timing; it is measured by these staying identical. (An earlier draft also named
`dev/varka_emit.sh` "over the 64 inventory entries"; the tool takes SQL strings
and has no inventory mode, and the entries are Java in the bench module, so that
oracle is replaced in section 5 by a fixed list of shapes run by hand.)

**The property test 2.15 asks for does not exist.** Nothing today asserts, over
random IR, that the interval a node reports contains what the reference evaluator
computes. `VarkaIrFuzzSuite` has the generator (the private class `Shapes` and its
`Gen` records) and `VarkaReferenceEvaluator.evalValue(node, row: Seq[Option[Int]],
lits: Array[Int]): Option[Int]` has the oracle; neither has been pointed at a
range. The fuzz suite does carry a *third* bound function of its own, `boundsOf`,
whose second component is the largest value any guarded day producer under a node
reaches - which is the executable form of "the rows on which the runtime guard
passes", and section 5 reuses it as the property test's row filter. Step 1 below
writes that test first, against a thin adapter over today's two functions, so the
plan learns whether the current code passes it before anything is replaced - and
section 6.1 predicts the answer.

**Found by review before implementation, 15 September 2026.** Three things the
first draft of this plan left for the implementer to discover, each at the point
where discovering it means improvising. They are folded into sections 3 and 5
below; this paragraph is the record that they were missing.

1. *The IR is untyped.* `ColumnRef` carries an ordinal and nothing else, and
   `Greatest`, `Least` and `IfElse` are the same node whether they hold days or
   ints. Today the type is implicit in which function is asked: a `ColumnRef` is the
   contract day range to `dayRange` and unbounded to `intBound`; a hull is a hull to
   one and unbounded to the other. A single `range(node, literals, policy)` cannot
   reproduce that, so the query takes the operand *kind* as well, set by the
   parent's slot (section 3.1, table 3.4). Written without it, the natural
   `ColumnRef` arm answers the contract range for an int column and lets
   `cannotOverflow` take the check off `c * 700`, and the fuzz grammar would not
   notice, because it draws column values below 2 500 000.
2. *Interval arithmetic is tighter than today's magnitude arithmetic* for `+` and
   `-` over opposite signs and for hull nodes under an int, and section 3.2
   promises no tightening. The first draft did not say which wins. Section 3.1 now
   does: under the int kind every transfer function answers a zero-symmetric
   interval, which reproduces today's rule exactly, and the tightening is a listed
   debt.
3. *The literal table is live.* It grows as compilation proceeds and is truncated
   on every decline (`truncate(literals, mark)` in `compileNode`, `intArith` and
   their neighbours), so an operator "built once per compile" is stale by the next
   arm. The analysis reads the map through a closure at query time.

## 3. The design

### 3.1 The mechanism

Two Java files in `codegen/varka/`, beside `VarkaChrono.java` and
`IntRangeOps.java`, and the compiler's two traversals deleted.

**`VarkaValueRange.java` - the lattice, pure data.** A sealed interface `Range`
with two members: the record `Bounded(long lo, long hi)` and the singleton
`Unknown`. Operations, every one total and every one saturating to `Unknown` on
`Long` overflow rather than wrapping: `shift(lo, hi)`, `hull(other)`, `add`,
`sub`, `mul` (interval arithmetic over the four corner products), `neg`, `abs`,
and the queries `magnitude()` (the larger of `|lo|` and `|hi|`, as an
`OptionalLong`), `fitsInt()`, and `within(lo, hi)`. Saturation is a property of
the type, not a discipline of its callers: `exactly` and `withinInt` cease to
exist because there is nothing left for them to guard. The laws are unit-tested
directly - `hull` is commutative, associative and idempotent; `shift` composes;
`mul` of two ranges contains every product of members; and every operation on
`Unknown` is `Unknown`.

**`VarkaRangeAnalysis.java` - one traversal, two questions as queries.** A single
`range(node, kind, policy, literals)` returning a `Range` for any `VarkaVectorIR`,
with an exhaustive `switch` over the sealed interface - so a node type without a
transfer function refuses to compile, which is the protection the old code did
not have (three bugs, none reachable by a missing arm). `Cond` and `Chrono` are
sealed sub-interfaces with their own `permits` lists (five and nine members), so
the switch either names their members or has one arm per sub-interface; the
compiler enforces whichever it is.

`kind` is `DAY` or `INT` and says what the caller is asking about, because the IR
does not: a `ColumnRef` and the three hull nodes take their kind from the slot
they sit in, every other node has a fixed result kind, and a node asked for the
other kind answers `Unknown` - which is what today's two functions do for each
other's nodes. The parent decides the child's kind: both `DateDiff` operands and
every `.days()` child are `DAY`; an `AddDays`, `SubDays` or `NextDay` offset, an
`AddMonths` month count and the three `MakeDate` operands are `INT` (and none of
them is ever queried - the transfer functions read the literal or treat a column
as the guard's promise); both `IntArith` operands and `IntNeg`'s child are `INT`;
hull nodes pass their own kind down. Table 3.4 lists all of it.

Under `INT` every transfer function answers a zero-symmetric interval `[-m, m]`,
so that `magnitude()` is today's `intBound` exactly: a literal is `[-|v|, |v|]`,
`year` is `[-40000, 40000]`, and interval `add`, `sub` and `mul` over symmetric
operands are symmetric, so `+` and `-` bound as the sum of magnitudes and `*` as
their product, as today. Hull nodes under `INT` answer `Unknown`, as today.
Both are looser than interval arithmetic could be, on purpose: section 3.2's
promise is "identical", and the tightening (asymmetric `INT` intervals, hulls
under `INT`) is a debt-register entry with this task's number on it, not a side
effect of it.

The literal table crosses the Java boundary as an `IntUnaryOperator` from slot
index to value that reads the compiler's `LinkedHashMap[Int, Int]` at call time
- never a snapshot, because the map grows during compilation and is truncated on
every decline. The map's shape is the surprising part and is worth knowing before
writing the operator: its *keys* are the literal values in insertion order and
the slot index is a key's position, so today's lookup is
`literals.keysIterator.drop(slot.index).next()`.

`policy` replaces `guarded: Boolean` plus `guardsBelow` with an explicit
`GuardPolicy`: `ARMED` - a calendar consumer above has armed the producer guards,
so a column-offset producer answers the narrow range - and `NONE` - nothing is
armed, so it answers `Unknown`. A calendar-consumer node re-arms the policy for
its subtree exactly where `guardsBelow = true` does today: both `AddMonths` arms,
`LastDay`, `TruncDate` and `TruncDateDynamic`. Making the policy a parameter of
the *query* rather than a fact baked into one traversal's arms is 2.15's second
property: the calendar admission and the overflow check need the same distinction
and only one of them had it.

The two old questions become two queries:

* `dayRange(node)` is `range(node, DAY, ARMED, literals)` from `admitCalendar`
  and `rearm`, and `range(node, DAY, NONE, literals)` where `intBound`'s
  `datediff` arm asked unguarded - the transfer functions reproduce today's shifts
  and constants exactly.
* `intBound(node)` is `range(node, INT, NONE, literals).magnitude()`, with the
  calendar fields' magnitudes as transfer functions of the field nodes - the
  same nine constants - and `datediff` as `[-m, m]` for `m` the larger of
  `|ehi - slo|` and `|elo - shi|` over the operands' `DAY`/`NONE` ranges, refused
  unless all four ends fit int32, which is today's rule with its saturation moved
  into the type.

`cannotOverflow`, `admitCalendar`, `rearm` and the interval arms keep their
signatures and call the analysis; `DayRange`, `Bounded`, `Unknown`, `exactly` and
`withinInt` leave the compiler. `rearm` stays in Scala - it rewrites IR and
belongs with the compiler - and reads `GuardedDay`'s transfer function (the narrow
range, regardless of child) from the analysis like everything else.

**The option not taken, and why.** A Scala-side refactor that merely merges the
two functions would be smaller. It was not taken because the milestone's next task
makes the lane a parameter and the one after that adds `long` values, and both
need a domain whose operations are total over `long` and whose transfer functions
are enumerated by the compiler - which is a Java sealed type and an exhaustive
switch, not a Scala partial function with a `case _ => Unknown` at the bottom.

### 3.2 What is deliberately unchanged

**Every bound is reproduced, not tightened.** The nine field constants, the
28n-to-31n month rule, `trunc`'s -365, `ThursdayOf`'s 3, the unguarded `datediff`
rule, the asymmetric admission limits: all identical, on purpose, because the
admission check is "every shape admitted or declined identically" and a tighter
bound would admit a shape today declined - `year` is really bounded well under
40 000 - and move the oracle. Tightening is a separate task with its own before
and after, and it is entered in the debt register by this one.

The runtime-bound registry, the `GuardedDay` node, `rearm`'s rewrite rule (a node
is re-armed only when its *own* shift is runtime-valued), the decline reasons'
wording, the shape key, and every emitted byte. The IR does not change, so no
shape hash changes, so no committed benchmark number can move.

### 3.3 Registered op counts

Not applicable: no IR or emitter change. The check that stands in for it is the
byte-compared `coverage.json`, the differential classification, and the fixed
list of shapes in section 5 run through `dev/varka_emit.sh` before and after.

### 3.4 The transfer functions, as a table

Written from today's `intBound` and `dayRange`, before the analysis is, so the
per-node tests in section 5 are written from this table and the analysis is made
to pass them. `NARROW` is `[NARROW_MIN_DAYS, NARROW_MAX_DAYS]`, `CONTRACT` is
`[CONTRACT_MIN_DAYS, CONTRACT_MAX_DAYS]` (1 January 1 to 31 December 9999),
`MAKE_DATE` is `[1 January MAKE_DATE_MIN_YEAR, 31 December MAKE_DATE_MAX_YEAR]`,
all from `VarkaChrono`. "shift child by `[a, b]`" means the child's range under
the same kind and the stated policy, with `a` added to its low end and `b` to its
high end, `Unknown` if the child is. `v` is a literal's value; `m(x)` is the
magnitude of an operand's `INT` range, `Unknown` if that range is. A cell reading
"other kind" means the node answers `Unknown` when asked for the kind it does not
produce.

| Node | Result kind | `DAY`, policy `NONE` | `DAY`, policy `ARMED` | `INT` (any policy) |
|---|---|---|---|---|
| `ColumnRef` | from slot | `CONTRACT` | `CONTRACT` | `Unknown` |
| `LiteralSlot` | from slot | `[v, v]` | `[v, v]` | `[-abs(v), abs(v)]` |
| `AddDays`, literal offset | DAY | shift days by `[v, v]`, same policy | same | other kind |
| `AddDays`, column offset | DAY | `Unknown` | `NARROW` if days is not `Unknown` under `ARMED`, else `Unknown` | other kind |
| `SubDays` | DAY | as `AddDays` with `-v` | as `AddDays` | other kind |
| `NextDay` | DAY | shift days by `[1, 7]`, same policy | same | other kind |
| `ThursdayOf` | DAY | shift days by `[-3, 3]`, same policy | same | other kind |
| `AddMonths`, literal `m` | DAY | shift days by `[min(28m, 31m), max(28m, 31m)]`, child under `ARMED` | same | other kind |
| `AddMonths`, column count | DAY | shift days by `[31 * MONTH_ARITH_MIN_MONTHS, 31 * MONTH_ARITH_MAX_MONTHS]`, child under `ARMED` | same | other kind |
| `LastDay` | DAY | shift days by `[0, 30]`, child under `ARMED` | same | other kind |
| `TruncDate`, `TruncDateDynamic` | DAY | shift days by `[-365, 0]`, child under `ARMED` | same | other kind |
| `MakeDate` | DAY | `MAKE_DATE`, children unqueried | same | other kind |
| `GuardedDay` | DAY | `NARROW`, child unqueried | same | other kind |
| `Greatest`, `Least`, `IfElse` | from slot | hull of both branches, same policy; `Unknown` if either is | same | `Unknown` |
| `DateDiff` | INT | other kind | other kind | operands under `DAY`/`NONE`; if both `Bounded` and all four ends fit int32, `[-m, m]` with `m = max(abs(ehi - slo), abs(elo - shi))`; else `Unknown` |
| `Year` | INT | other kind | other kind | `[-40000, 40000]`, child unqueried |
| `Month` | INT | other kind | other kind | `[-12, 12]` |
| `DayOfMonth` | INT | other kind | other kind | `[-31, 31]` |
| `Quarter` | INT | other kind | other kind | `[-4, 4]` |
| `DayOfYear` | INT | other kind | other kind | `[-366, 366]` |
| `WeekOfYear` | INT | other kind | other kind | `[-53, 53]` |
| `DayOfWeek`, `DayOfWeekIso` | INT | other kind | other kind | `[-7, 7]` |
| `WeekDay` | INT | other kind | other kind | `[-6, 6]` |
| `IntArith`, `MUL` | INT | other kind | other kind | `[-(m(l) * m(r)), m(l) * m(r)]`, `Unknown` on `Long` overflow |
| `IntArith`, `ADD` or `SUB` | INT | other kind | other kind | `[-(m(l) + m(r)), m(l) + m(r)]`, `Unknown` on `Long` overflow |
| `IntNeg` | INT | other kind | other kind | the child's `INT` range (symmetric, so unchanged) |
| `Compare`, `And`, `Or`, `Not`, `IsNotNull` | none | `Unknown` | `Unknown` | `Unknown` |

Two cells are looser than the arithmetic allows and are the debt this task
registers rather than pays: the hull nodes under `INT`, and the symmetric `INT`
intervals generally. Every other cell is today's rule verbatim.

## 4. Files

* `sql/catalyst/src/main/java/.../codegen/varka/VarkaValueRange.java` - new.
* `sql/catalyst/src/main/java/.../codegen/varka/VarkaRangeAnalysis.java` - new.
* `sql/catalyst/src/main/scala/.../codegen/VarkaExpressionCompiler.scala` -
  `intBound`, `dayRange`, `DayRange`, `Bounded`, `Unknown`, `exactly` and
  `withinInt` removed; `cannotOverflow`, `admitCalendar`, `rearm`, the `datediff`
  arm and the interval arms call the analysis. For step 1 only, `intBound` and
  `dayRange` go from `private def` to `private[codegen] def` so the adapter in
  the test package can call them; the object is already `private[sql]`.
* `sql/catalyst/src/test/scala/.../codegen/varka/VarkaRangeAnalysisSuite.scala` -
  new: the lattice laws, the transfer functions against hand-computed intervals
  for every node type, and the property test.
* `sql/catalyst/src/test/scala/.../codegen/varka/VarkaIrFuzzSuite.scala` - its
  value grammar (`Shapes`, `Gen`, `boundsOf`) extracted to a shared test object so
  the property test generates the same trees the fuzzer does, on the
  `VarkaSqlResolve` precedent of one generator rather than two that drift. The
  extraction gives each input ordinal a kind, `DAY` or `INT`, and draws a leaf
  only from ordinals of the kind its slot wants, which the fuzzer today does not
  need because every column it draws is within the contract.
* `sql/varka/plans/PLAN_MILESTONE_5.md` - row 84. The debt-register entry for
  tightening goes where milestone 5 keeps its register: `PLAN_MILESTONE_4.md`'s
  debt register, which milestone 5's section 1.1 says "these tasks keep sweeping".
* `docs/sql-varka.md` - the "Key design decisions" paragraph on range analysis, one
  sentence.

## 5. Tests, and what each is for

* **Lattice laws** (`VarkaRangeAnalysisSuite`): saturation on every operation at
  both `Long` extremes; `hull` commutative, associative, idempotent; `mul`
  containing every corner product; `Unknown` absorbing. These are the discipline
  task 63 added by hand, now asserted of the type.
* **Transfer functions, one test per row of table 3.4**, each against the
  interval the table gives - so the "reproduced, not tightened" promise is a
  test, not a review note. Includes the policy: a column day offset is `Unknown`
  under `NONE` and the narrow range under `ARMED`, and a `last_day` above it
  re-arms; and the kind: a `ColumnRef` is `CONTRACT` under `DAY` and `Unknown`
  under `INT`, and `greatest` is a hull under `DAY` and `Unknown` under `INT`.
* **The property test**, the one 2.15 says the task is not done without: over
  random IR from the shared grammar, random literal tables and random lane rows
  including nulls, at every node of every tree and for the kind its slot gives
  it, `range(node, kind, NONE)` contains `evalValue(node, row, lits)` wherever
  that returns `Some` (a `None` is a null lane and is skipped); and under
  `ARMED`, the same on every row that passes the guard's filter. The filter is
  `boundsOf`'s second component made per-row: every column-offset producer under
  the node, and the child of every `GuardedDay`, has its own value in `NARROW` on
  that row. The `GuardedDay` clause matters because the reference passes a
  `GuardedDay` value through unchanged while the analysis answers `NARROW`
  regardless; without it the test fails on rows the kernel would have declined.
  Rows draw `DAY` columns inside `CONTRACT` (the input promise the `ColumnRef`
  arm relies on) and `INT` columns over the whole of int32, so a `ColumnRef` arm
  that answered a bounded range under `INT` would be caught here and nowhere
  else. Ten thousand trees by default, the seed printed, replay by
  `-Dvarka.range.seed=<seed>`, budget by `-Dvarka.range.trees=<n>`, on the
  fuzz suite's `-Dvarka.fuzz.*` precedent.
* **The oracles, unchanged**: `VarkaExpressionCompilerSuite`, `VarkaCoverageSuite`
  (including the byte comparison of `coverage.json`) and `VarkaDifferentialSuite`'s
  classification. Plus a fixed list run through `dev/varka_emit.sh` before and
  after, verdict and shape hash quoted in section 9 - the shapes that exercise
  the seams this task touches, none of which the tool can enumerate from the
  inventory on its own:

      dev/varka_emit.sh "datediff(last_day(date_add(d, i)), d2)" --columns d:date,i:int,d2:date
      dev/varka_emit.sh "year(d) * 50000" --columns d:date
      dev/varka_emit.sh "greatest(year(d), month(d)) * 5" --columns d:date
      dev/varka_emit.sh "year(d) - 2000 + month(d)" --columns d:date
      dev/varka_emit.sh "add_months(date_add(d, i), 3)" --columns d:date,i:int
      dev/varka_emit.sh "datediff(date_add(d, 2147483647), d)" --columns d:date
      dev/varka_emit.sh "make_date(y, m, dd)" --columns y:int,m:int,dd:int

  The commands, per step, so nothing is left to remember:

      build/sbt -batch "catalyst/testOnly *VarkaRangeAnalysisSuite"
      build/sbt -batch "catalyst/testOnly *VarkaExpressionCompilerSuite"
      build/sbt -batch "catalyst/testOnly *VarkaCoverageSuite *VarkaDifferentialSuite *VarkaIrFuzzSuite"
      dev/varka_gate.sh

## 6. The measurement

None. This task produces no number and moves none; section 3.3 says how that is
checked.

### 6.1 Predictions, registered before the run

1. **Today's code passes the property test** on the shapes the fuzzer generates,
   with the `INT` columns drawn over the whole of int32.
   2.15 says the test is one "the current code cannot pass"; the prediction here is
   that task 63's `exactly` and `withinInt` closed the holes it would have found
   and the test's value is keeping them closed. If it fails against today's code,
   the failure is a live bug in the compiler, gets its own fix and its own commit
   before any line of the refactor, and this prediction is scored wrong in the
   informative direction.
2. **Zero oracle differences**: 97 compiler-suite tests, 4 coverage checks with an
   unchanged `coverage.json`, an unmoved differential classification, and 64
   identical verdicts and shape hashes from `dev/varka_emit.sh`.
3. **The compiler shrinks by about 150 lines and the Java gains about 250**, the
   difference being the laws stated once and the transfer functions listed
   exhaustively rather than defaulted.

## 7. Risks

* **Tightening by accident.** A transfer function written from the definition
  rather than from today's rule gives a tighter interval, which admits more and
  moves the oracle. The per-node tests in section 5 are written from today's
  constants before the analysis is, so the analysis is made to pass them.
* **The policy's semantics drifting from the guard's.** `ARMED` means "a
  calendar consumer above has armed the producer guards"; the property test's row
  filter is the executable form of that sentence, and a change to the guard's
  promise (task 91 chooses a different bound) changes the filter first.
* **The Java/Scala seam.** The analysis takes an `IntUnaryOperator` for literals
  and returns a Java sealed type the Scala side pattern-matches; Scala 2.13 sees
  Java sealed interfaces as non-exhaustive, so the Scala callers match with an
  explicit `case _` that throws, never one that answers.
* **Asking the wrong kind.** A caller that asks `DAY` of a `DateDiff` or `INT` of
  an `AddDays` gets `Unknown` and a silently checked or declined shape. The
  compiler suite's decline reasons are what would show it; the kind table is
  what prevents it, and every call site in section 2 names its kind.
* **`rearm`.** It reads intervals mid-rewrite; if the analysis is called on a
  partly rewritten subtree, the policy must be the one the finished tree will
  have. The existing `rearm` tests plus the emit before/after are what catch a
  mismatch, and `rearm` is touched last.

## 8. Sequencing

1. The shared fuzz grammar with kinds, and the property test, run against a thin
   adapter over today's `intBound` and `dayRange` (widened to `private[codegen]`
   for this step). Score prediction 1. If it fails, stop and fix the bug first.
2. `VarkaValueRange` and its law tests.
3. The per-node tests written from table 3.4, then `VarkaRangeAnalysis` with every
   transfer function made to pass them; the exhaustive switch is what enumerates
   "every".
4. The compiler switched over caller by caller - `cannotOverflow`, the `datediff`
   arm, the interval arms, `admitCalendar`, `rearm` last - with the compiler suite
   run after each; then the old functions deleted.
5. The oracles: coverage suite, differential suite, `dev/varka_emit.sh` before/after
   over the inventory, the full gate.
6. Section 9, the milestone row, the debt-register entry for tightening, and the
   `docs/sql-varka.md` sentence.

## 9. Outcome

*To be written from the oracles and the property test.*
