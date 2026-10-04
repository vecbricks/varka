# VARKA-67: year-month interval columns in the date lane

## 1. Where this came from

`m4/PLAN.md` row 67 and section 2.32, added 5 September 2026 by the
owner's decision after a survey of which Spark types share the date's lane:
`YearMonthIntervalType` is the third Spark type that is int32 inside and in
the Arrow cache, and the owner wants it supported now, partly for the public
statement the milestone's write-up will make - that Varka covers three types
- provided the statement matches exactly what fuses. VARKA-68 (section 2.33)
is the interval algebra on top of VARKA-63; this task is the type's admission
where no new arithmetic is needed, and it needs neither of them.

## 2. The admission check, done

**The representation.** A `YearMonthIntervalType(start, end)` value is a
count of months in every unit: `INTERVAL '2' YEAR` is stored as 24,
`INTERVAL '1-3' YEAR TO MONTH` as 15, and the unit only constrains which
values a literal or a cast may produce and how the value prints
(`ToStringBase`). `DateAddYMInterval.nullSafeEval` is
`DateTimeUtils.dateAddMonths(days, months)` with the stored int, whatever the
unit, so `d + ym_col` is VARKA-40's node over the column's lanes with no
conversion, under VARKA-60's runtime bound on the months (checked here: the
column is unbounded like VARKA-60's int column, and the same guard covers it,
since the guard is on the count lanes and not on the column's Spark type).

**The Arrow side.** `ArrowUtils.toArrowType` maps the type to
`ArrowType.Interval(YEAR_MONTH)`, whose vector is `IntervalYearVector`: a
`BaseFixedWidthVector` of `TYPE_WIDTH` 4 with a data buffer of int32 months and
a validity bitmap - `extractMorsel`'s contract. The fork's
`ArrowCachedBatchSerializer` already stores such columns and collects their
statistics (`IntColumnStats`, `calculateMinMaxYearMonthInterval`), and
`ArrowColumnVector` reads them through `IntervalYearAccessor.getInt`. So a
cached table with an interval column reaches the exec node as an
`ArrowColumnVector` over an `IntervalYearVector` today; `isArrowBacked` turns
it away only because the class is not on its list. Writing an output the same
way needs `allocateVector` to know the type.

**Where the type can appear in a fused tree, by Spark's own rules.** The
analyzer admits an interval only where an expression's `inputTypes` say so:
`DateAddYMInterval` (a date and an interval), the comparisons and `IN` (it is
in `TypeCollection.Ordered`), `Least`/`Greatest`/`Coalesce`/`If`/`CaseWhen`
(same-typed operands), `Cast`, and the arithmetic and constructors VARKA-68
owns. It cannot reach `date_add`'s offset, `datediff`, a calendar extraction
or `AddMonths`'s date operand, because those are typed `DateType` or
`IntegerType`; a `Cast` between an interval and an int is explicit in the
tree. So widening the compiler's value leaf from `DateType` to `DateType |
YearMonthIntervalType` lets an interval column flow only into positions that
are correct for it, and VARKA-38's "do not open it wider" - which was about an
int column reaching every date position - is not at stake: an interval column
in a date position is a type error the analyzer already rejected.

**The casts.** `Cast(int, YearMonthIntervalType(MONTH, MONTH))` is
`intToYearMonthInterval`, which for the `MONTH` end unit returns the value
unchanged - a relabel, as `unix_date` is. The `YEAR` end unit multiplies by
12 with `multiplyExact` and can throw: VARKA-63's checked multiply, declined
here with its own reason. `Cast(ym, IntegerType)` is
`yearMonthIntervalToInt`: the months for a `MONTH`-ended interval (a relabel),
the months divided by 12 for a `YEAR`-ended one (VARKA-68's literal
division; declined here). `Cast(ym(MONTH), ym(YEAR TO MONTH))` and the
other unit changes are `buildCast[Int]` identity relabels with a range check
on some paths (`intToYearMonthInterval` again) - taken only where the source
unit's range is inside the target's, which for `MONTH` to `YEAR TO MONTH` it
is; otherwise declined.

### 2.1 Reconciled against VARKA-63, which landed after this plan was written

*Added 8 September 2026. Sections above were written on 5 September, when int32
arithmetic was still ahead of this task; three of their decisions defer to it.
Checked against master at `a26ab686fe0`, and one of the three was wrong.*

**`d - ym_col` does not become "one arm", and it should stay out of this task.**
The analyzer produces `DatetimeSub(l, r, DateAddYMInterval(l, UnaryMinus(r,
ansi)))` (`BinaryArithmeticWithDatetimeResolver.scala:117`), so the month count
is a negation of an interval column. VARKA-63 does supply `IntNeg` - but its
compile arm is `case n @ UnaryMinus(c, failOnError) if n.dataType ==
IntegerType`, and this negation is `YearMonthIntervalType`, so that arm does not
match and widening it would be wrong: int arithmetic is an int-typed concept.
The arm would have to be local to `compileMonths`, which is fine.

The blocker is one layer down, in the emitter. `analyze()` calls
`requireOffsetShape(n.months(), "add_months' month count")`, which admits a
`LiteralSlot` or a `ColumnRef` and nothing else, so an `IntNeg` in that position
is refused - and section 3.2 of this plan promises no emitter byte moves. So
`d - ym_col` stays declined here and belongs to VARKA-68, which owns `-ym`
anyway. Its decline reason changes, though: not "waits for int arithmetic",
which has arrived, but that the month-count position takes a literal or a column
and this is neither.

*What the refusal is not.* It would be easy to read the emitter's strictness as
"a month count carries a runtime bound a derived value cannot declare" - that
sentence is in `requireOffsetShape`'s own comment as of VARKA-78, and for the
month count it is imprecise. A column-count `AddMonths` joins `selfGuarding`
and is guarded at run time against `MONTH_ARITH_MIN/MAX_MONTHS` by a lanewise
check on the count's *value*, which does not care what produced it; a derived
count would be covered by exactly that guard. `next_day`'s weekday is the one
that genuinely cannot, being folded or a task-59 derived leaf with no lanewise
range guard behind it. The two positions share a check and do not share the
reason, and whoever widens this for VARKA-68 should split them rather than
believe the shared comment. Recorded here, and left for that task.

**The `YEAR`-unit casts stay declined, for a different reason than section 2
gives.** `IntervalUtils.intToYearMonthInterval` is `Math.multiplyExact(v, 12)`
for a `YEAR` end field - always checked, whatever the session's ANSI mode, and
throwing `castingCauseOverflowError` rather than `ARITHMETIC_OVERFLOW`. VARKA-63
lowers a checked multiply only where `intBound` proves it cannot overflow, and
an interval or int column carries no bound, so the common case still declines.
What has changed is that the compiler's comment - "admitting it needs the
multiply in the lane, not a bound" - now names a thing that exists, so it reads
as an invitation it is not. The reason string and that comment are corrected in
this task even though the outcome does not move, because the next reader of that
arm will otherwise try the multiply and find out the hard way.

*Corrected during implementation, the same day.* This paragraph first said a
bounded operand would now fuse - `CAST(year(d) AS INTERVAL YEAR)`, bounded at
40000, whose 12x stays inside int32 - and that the widening was free. It is not,
and the reason is the one three paragraphs above: the multiply would be an
`IntArith` in the month-count position, which `requireOffsetShape` refuses. The
compiler would have claimed fusion and the emitter refused it, which is a ghost
fallback and not a widening. The arm was written that way and reverted before it
compiled. `d - ym_col` and this cast are blocked by the same check, they go to
VARKA-68 together, and the fact that this plan diagnosed the blocker two
paragraphs earlier and still walked into it is the argument for milestone 5's
VARKA-86: one operand admission, stated once, so the compiler cannot widen a
position the emitter has not.

**`CAST(ym AS INT)` on a `YEAR`-ended interval is unchanged**, and correctly so:
`yearMonthIntervalToInt` is `v / MONTHS_PER_YEAR`, and VARKA-63 lowers no
division. VARKA-68, as section 3.2 already says.

**What the check would have rejected:** a unit that changed the stored
value (it does not), an Arrow vector that is not fixed-width int32 (it is), a
cast that is not a relabel for the `MONTH` unit (it is), and a leaf widening
that could put an interval where a date belongs (Spark's typing forbids it).

## 3. The design

### 3.1 Admission on both sides of the kernel

**Evaluator.** `isArrowBacked` gains `case (v: IntervalYearVector, None) =>
v.getValueCount() == input.numRows()`; `allocateVector` gains `case _:
YearMonthIntervalType => new IntervalYearVector(...)`. The exec nodes' output
typing already comes from the compiled plan's `outputTypes`, which carry the
Spark expression's type, so a fused `d + ym` is `DateType` and a fused
`greatest(ym1, ym2)` is `YearMonthIntervalType` with the unit Spark inferred,
and the row path reads both through the accessor `ArrowColumnVector` already
has. Nothing else moves: the morsel extraction, the derived-input scratch,
the bounds check and the status route are type-blind.

**Compiler.** Three arms and one widening:

* the value leaf `case br: BoundReference if br.dataType == DateType` becomes
  `DateType | _: YearMonthIntervalType` (2's argument for why that is safe);
* `Literal(months: Int, _: YearMonthIntervalType)` becomes a `LiteralSlot`,
  beside the date literal, which is what makes `ym_col < INTERVAL '6' MONTH`
  and `coalesce(ym, INTERVAL '0' MONTH)` reachable;
* `DateAddYMInterval(date, interval)`'s `compileMonths` (VARKA-60) takes an
  interval column of any unit through `columnRef` in place of today's
  decline. `d - ym_col` resolves to `UnaryMinus` over the column, and the
  negation of a column is VARKA-63's `IntNeg`, so it declines here with the
  reason "negated interval column waits for int arithmetic" and is one arm
  when 63 lands (the literal form already folds);
* `Cast(e, YearMonthIntervalType(MONTH, MONTH))` over an int-typed fused
  field or int column, and `Cast(ym, IntegerType)` over a `MONTH`-ended
  interval, as relabels (the `unix_date` arm's pattern, no node); the
  `YEAR`-unit casts decline, with a reason naming the unbounded checked
  multiply rather than a wait that is over (2.1), except over an operand
  `intBound` can bound - `CAST(year(d) AS INTERVAL YEAR)` - which fuses on
  VARKA-63's existing `IntArith` arm.

**What a user observes.** A cached table with interval columns fuses in
`SELECT d + ym`, `WHERE ym > INTERVAL '1' YEAR`, `greatest(ym1, ym2)`,
`CASE WHEN d < d2 THEN ym1 ELSE ym2 END`, `coalesce(ym, INTERVAL '0' MONTH)`,
`CAST(i AS INTERVAL MONTH)`, `CAST(ym AS INT)`; a batch whose interval count
is past VARKA-60's bound is recomputed by the row engine and counted under
`numFallbackBatchesDeclined`; answers are the row engine's in every case.
The docs' type list says: dates, int32 columns in the positions the
functions take them, and year-month intervals.

### 3.2 What is deliberately unchanged

* No IR node and no emitter byte: both pinned fixtures stay as they are,
  asserted by the suites as they stand.
* Everything that computes an interval from something else (VARKA-68): the
  constructor, the extracts, the literal multiply and divide, `ym +- ym`,
  `-ym`, `abs(ym)`, the `YEAR`-unit casts.
* `DayTimeIntervalType` (int64 microseconds, milestone 5) and
  `CalendarIntervalType` (a struct): not lanes.
* VARKA-60's bound and its guard: an interval column is exactly the case the
  guard was built for; no new bound.

### 3.3 Registered op counts

None move: no emitted byte changes. `d + ym_col` is VARKA-60's
`add_months(d, m)` kernel at its registered 114 dense-loop calls with the
guard; the register test in `VarkaLoopEmitterSuite` is untouched.

## 4. Files

| file | what |
|---|---|
| `VarkaKernelEvaluator.scala` | the `IntervalYearVector` arm in `isArrowBacked`; the `YearMonthIntervalType` arm in `allocateVector` |
| `VarkaExpressionCompiler.scala` (+ suite) | the leaf widening, the interval literal, the interval column in `compileMonths`, the two relabel casts, the three new reasons |
| `VarkaKernelEvaluatorSuite.scala` | an `IntervalYearVector` input and an interval-typed output on hand-built batches, with allocator accounting |
| `VarkaSharedSessions.scala`, `VarkaDifferentialSuite.scala` | `varka_dates_intervals`: `d`, `ym` (`YEAR TO MONTH`), `ymm` (`MONTH`), `ymy` (`YEAR`), nulls, values past the month bound; section 5's differentials |
| `sql/varka/bench/.../Surface.java` | the interval entries for VARKA-62's table |
| `VarkaThroughputBenchmark.scala` + files | one pair: `d + ym` against `add_months(d, m)` on an int column of the same values |
| `docs/sql-varka.md`, `SKILLS.md` | the type list and the surface; a lesson on allowlists by class (section 2's finding) |
| `m4/PLAN.md`, this file | row 67, section 9 |

## 5. Tests, and what each is for

* **Compiler.** `d + ym_col` compiles to `AddMonths(ColumnRef, ColumnRef)`
  for each of the three units, with the guard's producer set as for an int
  column; `ym_col < INTERVAL '6' MONTH`, `ym IN (...)`, `greatest(ym1, ym2)`,
  `coalesce(ym, INTERVAL '0' MONTH)` and `CASE` compile on the existing nodes
  with `outputTypes` carrying the interval type; `CAST(i AS INTERVAL MONTH)`
  and `CAST(ymm AS INT)` compile to the child alone; the `YEAR`-unit casts
  and `d - ym_col` decline with their reasons; an interval column offered to
  `date_add` cannot be built (the analyzer refuses it), asserted through the
  analyzer rather than the compiler. The failure it catches: an interval
  leaking into a date position through some arm the survey missed.
* **Evaluator.** A batch with an `IntervalYearVector` input runs `d + ym`
  with the row engine's values; an interval-typed output vector is
  allocated, written and released with the allocator's accounting exact; a
  batch with an on-heap interval column is refused as any other.
* **Differential**, over `varka_dates_intervals`, both ANSI modes: the
  projection shapes above beside their row-engine answers with zero
  fallbacks where the plan fuses; the far-interval rows declining the batch
  through the guard, counted, never wrong; the filter route
  (`WHERE ym > INTERVAL '1' YEAR`, `WHERE d + ym < d2`).
* **The pinned fixtures**: unmoved, which the suites assert as they are.

## 6. The measurement

One throughput pair at both widths, `SELECT d + ym FROM varka_dates_intervals`
against `SELECT add_months(d, m) FROM ...` over an int column holding the
same values: the same kernel, so the rows must agree within the machine's
variance - the measurement is that the type costs nothing, not that it is
fast. Regenerated with `dev/varka_bench_regen.sh` on the idle machine. The
interval entries join VARKA-62's surface for its next run.

### 6.1 Predictions, registered before the run

1. `d + ym` and `add_months(d, m)` agree within 3% at both widths; a larger
   gap is a finding about the Arrow read path, not about the lane.
2. No committed number moves.
3. The differential's far-interval fixture declines exactly the batches
   holding a live out-of-bound interval, as VARKA-60's int fixture does.

## 7. Risks

1. **A unit-changing cast that is not a relabel.** Section 2 lists which
   are; the compiler test drives each from the analyzer's own tree, and any
   other unit change declines.
2. **The analyzer's tree for `d - ym_col`** is `DateAddYMInterval(d,
   UnaryMinus(col))`; declined here by design, one arm after VARKA-63.
3. **A stored interval column in the Arrow cache with a `valueCount` that is
   not the batch's** - the same rule as every other vector class, in the
   same place.
4. **Saying "three types" wider than it is.** The docs sentence is written
   from the compiler's arms, and VARKA-62's surface entries are what the
   public table shows.

## 8. Sequencing

1. This plan and the milestone row.
2. The evaluator's two arms and its test.
3. The compiler's leaf, literal, `compileMonths` and cast arms with the
   suite; the fixture and the differential.
4. The surface entries, the throughput pair, the docs, section 9, row 67.

## 9. Outcome

### 9.1 The pair, and what the instrument could not answer

`VarkaThroughputBenchmark`, on one fixture holding the same count twice - as an
int column and as a `MONTH`-unit interval - so the two rows differ in the
operand's Spark type and nothing else.

| row (varka side, M rows/s) | AVX-512 | 128-bit |
|---|---|---|
| `add_months(d, m)`, int count, the control | 184.9 | 119.6 |
| `d + ym`, interval count | 191.9 | 120.6 |
| `add_months(d, m)` on VARKA-60's own fixture | 182.0 | 120.0 |

The control lands within 1.6% of VARKA-60's existing row at both widths, which
is the check that the new fixture measures the same thing as the old one.

**Prediction 1 is confirmed at 128 bits and unanswerable at AVX-512, and the
second half of that is about the instrument.** It asked the two rows to agree
within 3%, with a larger gap being a finding about the Arrow read path. At 128
bits they agree to 0.8%, and to 1.5% in a first run. At AVX-512 the gap is 3.8%
here and was 8.4% in the first run - not stable, in the direction of the
interval being *faster*, which is not what a read-path cost looks like. The
control row moved 0.6% between those runs and the interval row 3.7%, so the
movement is in the measurement rather than in either shape.

That is worth stating plainly rather than filed as a miss: this file's
run-to-run spread on this machine reaches 19% on rows this task does not touch
(`trunc, literal format` moved 19.2%, `dayofweek` 11.6%, `datediff` 10.3%,
`case when, predictable` -6.3%), so a 3% question cannot be put to it here. The
claim the task actually needs - that admitting the type costs the kernel
nothing - is carried by the 128-bit rows and by the fact that both spellings
compile to the identical IR, which `VarkaExpressionCompilerSuite` asserts
directly and for all three units. A 3% claim at AVX-512 needs the pinned runner
VARKA-62 uses.

**Prediction 2, "no committed number moves", is missed** - and by the same
noise. Twenty-four rows moved by more than 3% between the two regenerations, in
both directions, none of them touching an interval. The prediction was written
expecting a code change that moves bytes; this task adds a fixture and two rows
and changes no emitted byte, so what it actually predicted was stability the
instrument does not have.

**Prediction 3 is confirmed.** The differential's far counts - 24565, one past
`MONTH_ARITH_MAX_MONTHS`, and -300000 - decline their batch through VARKA-60's
guard with `numFallbackBatchesDeclined > 0` and `numFallbackBatchesKernel == 0`,
and the row engine answers every row correctly, exactly as it does for an int
count. The guard reads the count's lanes and not the column's Spark type, which
is what made this free.

### 9.2 What moved that the plan did not list

**`IN` needed widening, and it was not in section 4's file list.** The arm was
gated on `value.dataType == DateType`, so `ymm IN (INTERVAL '3' MONTH, ...)`
declined although section 3.1 promised it. `literalDays` now reads an interval
literal as well as a date one - both are int32 lanes and an `IN` list is
type-homogeneous, so one function serves both and the type gate stays on the
arm. Its decline reason lost the word "date"; the one test pinning that string
moved with it.

**Three shapes that were declined are now the task's own tests**, rather than
being deleted from where they were pinned. VARKA-60's decline test loses the
stored-interval-column case and keeps a comment saying where it went, because a
reader chasing "why did `add_months` stop declining an interval" should land
somewhere.

**A fixture lesson, for the third time in one day.** Adding the interval columns
to `VarkaExpressionCompilerSuite`'s shared `childOutput` renumbered `dow` from
ordinal 5 to 8 and broke four task-59 and task-61 tests that assert the ordinal
their appended column lands on. The intervals got their own `withIntervals` list
instead. The same trap produced `VarkaNarrowingBenchmark` as a separate file
under VARKA-78 and `varka_date_interval_counts` as a separate fixture here: a
shared fixture is an interface, and widening it is a change to every test that
reads it.

### 9.3 What this leaves for later

Both go to VARKA-68, and both are blocked on the same thing rather than on
arithmetic: `d - ym_col` and the `YEAR`-unit casts need an `IntArith`/`IntNeg`
in `add_months`' month-count position, which the emitter's `requireOffsetShape`
refuses. 2.1 records that the refusal's stated reason - a runtime bound a
derived value cannot declare - is true of `next_day`'s weekday and not of the
month count, which is guarded on its value by VARKA-60 and would cover a derived
count. VARKA-68 should split the two positions rather than believe the shared
comment.

The 3% question at AVX-512, for the pinned runner, if anyone still wants it
answered.
