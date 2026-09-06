# Task 67: year-month interval columns in the date lane

## 1. Where this came from

`PLAN_MILESTONE_4.md` row 67 and section 2.32, added 5 September 2026 by the
owner's decision after a survey of which Spark types share the date's lane:
`YearMonthIntervalType` is the third Spark type that is int32 inside and in
the Arrow cache, and the owner wants it supported now, partly for the public
statement the milestone's write-up will make - that Varka covers three types
- provided the statement matches exactly what fuses. Task 68 (section 2.33)
is the interval algebra on top of task 63; this task is the type's admission
where no new arithmetic is needed, and it needs neither of them.

## 2. The admission check, done

**The representation.** A `YearMonthIntervalType(start, end)` value is a
count of months in every unit: `INTERVAL '2' YEAR` is stored as 24,
`INTERVAL '1-3' YEAR TO MONTH` as 15, and the unit only constrains which
values a literal or a cast may produce and how the value prints
(`ToStringBase`). `DateAddYMInterval.nullSafeEval` is
`DateTimeUtils.dateAddMonths(days, months)` with the stored int, whatever the
unit, so `d + ym_col` is task 40's node over the column's lanes with no
conversion, under task 60's runtime bound on the months (checked here: the
column is unbounded like task 60's int column, and the same guard covers it,
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
(same-typed operands), `Cast`, and the arithmetic and constructors task 68
owns. It cannot reach `date_add`'s offset, `datediff`, a calendar extraction
or `AddMonths`'s date operand, because those are typed `DateType` or
`IntegerType`; a `Cast` between an interval and an int is explicit in the
tree. So widening the compiler's value leaf from `DateType` to `DateType |
YearMonthIntervalType` lets an interval column flow only into positions that
are correct for it, and task 38's "do not open it wider" - which was about an
int column reaching every date position - is not at stake: an interval column
in a date position is a type error the analyzer already rejected.

**The casts.** `Cast(int, YearMonthIntervalType(MONTH, MONTH))` is
`intToYearMonthInterval`, which for the `MONTH` end unit returns the value
unchanged - a relabel, as `unix_date` is. The `YEAR` end unit multiplies by
12 with `multiplyExact` and can throw: task 63's checked multiply, declined
here with its own reason. `Cast(ym, IntegerType)` is
`yearMonthIntervalToInt`: the months for a `MONTH`-ended interval (a relabel),
the months divided by 12 for a `YEAR`-ended one (task 68's literal
division; declined here). `Cast(ym(MONTH), ym(YEAR TO MONTH))` and the
other unit changes are `buildCast[Int]` identity relabels with a range check
on some paths (`intToYearMonthInterval` again) - taken only where the source
unit's range is inside the target's, which for `MONTH` to `YEAR TO MONTH` it
is; otherwise declined.

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
* `DateAddYMInterval(date, interval)`'s `compileMonths` (task 60) takes an
  interval column of any unit through `columnRef` in place of today's
  decline. `d - ym_col` resolves to `UnaryMinus` over the column, and the
  negation of a column is task 63's `IntNeg`, so it declines here with the
  reason "negated interval column waits for int arithmetic" and is one arm
  when 63 lands (the literal form already folds);
* `Cast(e, YearMonthIntervalType(MONTH, MONTH))` over an int-typed fused
  field or int column, and `Cast(ym, IntegerType)` over a `MONTH`-ended
  interval, as relabels (the `unix_date` arm's pattern, no node); the
  `YEAR`-unit casts decline with "year-unit interval cast waits for int
  arithmetic".

**What a user observes.** A cached table with interval columns fuses in
`SELECT d + ym`, `WHERE ym > INTERVAL '1' YEAR`, `greatest(ym1, ym2)`,
`CASE WHEN d < d2 THEN ym1 ELSE ym2 END`, `coalesce(ym, INTERVAL '0' MONTH)`,
`CAST(i AS INTERVAL MONTH)`, `CAST(ym AS INT)`; a batch whose interval count
is past task 60's bound is recomputed by the row engine and counted under
`numFallbackBatchesDeclined`; answers are the row engine's in every case.
The docs' type list says: dates, int32 columns in the positions the
functions take them, and year-month intervals.

### 3.2 What is deliberately unchanged

* No IR node and no emitter byte: both pinned fixtures stay as they are,
  asserted by the suites as they stand.
* Everything that computes an interval from something else (task 68): the
  constructor, the extracts, the literal multiply and divide, `ym +- ym`,
  `-ym`, `abs(ym)`, the `YEAR`-unit casts.
* `DayTimeIntervalType` (int64 microseconds, milestone 5) and
  `CalendarIntervalType` (a struct): not lanes.
* Task 60's bound and its guard: an interval column is exactly the case the
  guard was built for; no new bound.

### 3.3 Registered op counts

None move: no emitted byte changes. `d + ym_col` is task 60's
`add_months(d, m)` kernel at its registered 114 dense-loop calls with the
guard; the register test in `VarkaLoopEmitterSuite` is untouched.

## 4. Files

| file | what |
|---|---|
| `VarkaKernelEvaluator.scala` | the `IntervalYearVector` arm in `isArrowBacked`; the `YearMonthIntervalType` arm in `allocateVector` |
| `VarkaExpressionCompiler.scala` (+ suite) | the leaf widening, the interval literal, the interval column in `compileMonths`, the two relabel casts, the three new reasons |
| `VarkaKernelEvaluatorSuite.scala` | an `IntervalYearVector` input and an interval-typed output on hand-built batches, with allocator accounting |
| `VarkaSharedSessions.scala`, `VarkaDifferentialSuite.scala` | `varka_dates_intervals`: `d`, `ym` (`YEAR TO MONTH`), `ymm` (`MONTH`), `ymy` (`YEAR`), nulls, values past the month bound; section 5's differentials |
| `sql/varka/bench/.../Surface.java` | the interval entries for task 62's table |
| `VarkaThroughputBenchmark.scala` + files | one pair: `d + ym` against `add_months(d, m)` on an int column of the same values |
| `docs/sql-varka.md`, `SKILLS.md` | the type list and the surface; a lesson on allowlists by class (section 2's finding) |
| `PLAN_MILESTONE_4.md`, this file | row 67, section 9 |

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
interval entries join task 62's surface for its next run.

### 6.1 Predictions, registered before the run

1. `d + ym` and `add_months(d, m)` agree within 3% at both widths; a larger
   gap is a finding about the Arrow read path, not about the lane.
2. No committed number moves.
3. The differential's far-interval fixture declines exactly the batches
   holding a live out-of-bound interval, as task 60's int fixture does.

## 7. Risks

1. **A unit-changing cast that is not a relabel.** Section 2 lists which
   are; the compiler test drives each from the analyzer's own tree, and any
   other unit change declines.
2. **The analyzer's tree for `d - ym_col`** is `DateAddYMInterval(d,
   UnaryMinus(col))`; declined here by design, one arm after task 63.
3. **A stored interval column in the Arrow cache with a `valueCount` that is
   not the batch's** - the same rule as every other vector class, in the
   same place.
4. **Saying "three types" wider than it is.** The docs sentence is written
   from the compiler's arms, and task 62's surface entries are what the
   public table shows.

## 8. Sequencing

1. This plan and the milestone row.
2. The evaluator's two arms and its test.
3. The compiler's leaf, literal, `compileMonths` and cast arms with the
   suite; the fixture and the differential.
4. The surface entries, the throughput pair, the docs, section 9, row 67.

## 9. Outcome

<!-- Filled in when the measurement lands: the numbers with the committed file
     they trace to (dev/varka_quote_check.py holds you to this), 6.1's
     predictions scored one by one, what moved that the plan did not list, and
     what the task leaves for later - which goes to the milestone's debt
     register or a scope document, never to a code comment. -->
